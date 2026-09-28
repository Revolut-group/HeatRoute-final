package ru.heatroute;

import java.util.*;
import org.locationtech.jts.geom.*;

/** Implicit metric grid with incoming direction and Pareto cost/length labels. */
final class GridRouter {
  private final Dataset data;
  private final Rules rules;
  private final GeometryRules check;
  private final long deadline;

  GridRouter(Dataset data, Rules rules) {
    this(data, rules, rules.searchDeadline());
  }

  GridRouter(Dataset data, Rules rules, long deadline) {
    this.data = data;
    this.rules = rules;
    this.deadline = deadline;
    check = new GeometryRules(data, rules);
  }

  private static final int[] DX = {1, 1, 0, -1, -1, -1, 0, 1}, DY = {0, 1, 1, 1, 0, -1, -1, -1};

  private static final class Label {
    final int x, y, dir;
    final double cost, length, priority;
    final Label prev;
    final List<Coordinate> prefix;

    Label(
        int x,
        int y,
        int d,
        double c,
        double l,
        double p,
        Label previous,
        List<Coordinate> prefix) {
      this.x = x;
      this.y = y;
      dir = d;
      cost = c;
      length = l;
      priority = p;
      prev = previous;
      this.prefix = prefix;
    }
  }

  LineString route(
      Coordinate start,
      Coordinate end,
      int dn,
      Coordinate terminal,
      Existing.Root root,
      List<Coordinate> startPorts,
      double pad) {
    double step = rules.number("geometry.coarse_grid_m");
    Envelope box = new Envelope(start, end);
    box.expandBy(pad);
    double ox = Math.floor(box.getMinX() / step) * step,
        oy = Math.floor(box.getMinY() / step) * step;
    int nx = (int) Math.ceil(box.getWidth() / step) + 2,
        ny = (int) Math.ceil(box.getHeight() / step) + 2;
    PriorityQueue<Label> queue =
        new PriorityQueue<>(
            Comparator.comparingDouble((Label l) -> l.priority)
                .thenComparingDouble(l -> l.cost)
                .thenComparingInt(l -> l.x)
                .thenComparingInt(l -> l.y)
                .thenComparingInt(l -> l.dir));
    Map<Long, List<Label>> labels = new HashMap<>();
    for (Coordinate port : startPorts) {
      int cx = (int) Math.round((port.x - ox) / step), cy = (int) Math.round((port.y - oy) / step);
      for (int x = cx - 1; x <= cx + 1; x++)
        for (int y = cy - 1; y <= cy + 1; y++) {
          Coordinate at = new Coordinate(ox + x * step, oy + y * step);
          if (at.distance(port) < 1e-6 || !check.segment(port, at, dn, terminal, root)) continue;
          double length = start.distance(port) + port.distance(at);
          List<Coordinate> prefix = new ArrayList<>();
          prefix.add(start);
          if (port.distance(start) > 1e-6) prefix.add(port);
          prefix.add(at);
          Label label = new Label(x, y, 8, length, length, length + at.distance(end), null, prefix);
          queue.add(label);
          labels.computeIfAbsent(key(x, y, 8), k -> new ArrayList<>()).add(label);
        }
    }
    double goalRadius = 50;
    for (Dataset.Obstacle host : data.hosts(end))
      goalRadius = Math.max(goalRadius, host.geometry.getEnvelopeInternal().getDiameter() + 15);
    Map<Long, Boolean> legal = new HashMap<>();
    int visits = 0,
        budget = Math.max(50000, rules.integer("execution.maximum_candidate_evaluations") * 2);
    double capacityLength =
        Catalog.pipe(dn).index + 1 < Catalog.PIPES.size()
            ? Catalog.next(Catalog.pipe(dn)).limit
            : Catalog.pipe(dn).limit;
    while (!queue.isEmpty() && visits++ < budget) {
      if (System.nanoTime() >= deadline) return null;
      if (Thread.currentThread().isInterrupted())
        throw new Failure("CANCELLED", "Grid routing cancelled");
      Label current = queue.remove();
      if (!labels.getOrDefault(key(current.x, current.y, current.dir), List.of()).contains(current))
        continue;
      Coordinate at = new Coordinate(ox + current.x * step, oy + current.y * step);
      if (at.distance(end) < goalRadius && check.segment(at, end, dn, terminal, root)) {
        List<Coordinate> points = new ArrayList<>();
        Label p = current;
        while (p.prev != null) {
          points.add(new Coordinate(ox + p.x * step, oy + p.y * step));
          p = p.prev;
        }
        Collections.reverse(points);
        List<Coordinate> full = new ArrayList<>(p.prefix);
        full.addAll(points);
        full.add(end);
        LineString path = Geo.simplify(Geo.line(full));
        try {
          check.events(path, dn, terminal, root);
          return path;
        } catch (Failure ignored) {
        }
      }
      for (int direction = 0; direction < 8; direction++) {
        int x = current.x + DX[direction], y = current.y + DY[direction];
        if (x < 0 || y < 0 || x >= nx || y >= ny) continue;
        Coordinate next = new Coordinate(ox + x * step, oy + y * step);
        double length = current.length + at.distance(next);
        if (length + next.distance(end) > capacityLength + 1e-6) continue;
        long edge = key(current.x, current.y, direction);
        Boolean ok = legal.get(edge);
        if (ok == null) {
          ok = check.segment(at, next, dn, terminal, root);
          legal.put(edge, ok);
        }
        if (!ok) continue;
        double turn =
            current.dir == 8
                ? 0
                : Math.min(Math.abs(direction - current.dir), 8 - Math.abs(direction - current.dir))
                    * 45;
        if (turn >= 179.99) continue;
        double kt =
            turn == 0 || turn == 45 || turn == 90
                ? 1
                : rules.number("cost.nonstandard_turn_factor");
        double base =
            Catalog.pipe(dn).newPrice.doubleValue()
                    * rules.number("cost.weight_cost")
                    / rules.number("cost.base_cost_rub")
                + rules.number("cost.weight_length") / rules.number("cost.base_length_m");
        double cost =
            current.cost
                + at.distance(next)
                    * (kt
                            * Catalog.pipe(dn).newPrice.doubleValue()
                            * rules.number("cost.weight_cost")
                            / rules.number("cost.base_cost_rub")
                        + rules.number("cost.weight_length") / rules.number("cost.base_length_m"))
                    / base;
        long k = key(x, y, direction);
        List<Label> existing = labels.computeIfAbsent(k, unused -> new ArrayList<>());
        boolean dominated = false;
        for (Label other : existing)
          if (other.cost <= cost + 1e-7 && other.length <= length + 1e-7) {
            dominated = true;
            break;
          }
        if (dominated) continue;
        final double c = cost, l = length;
        existing.removeIf(other -> c <= other.cost && l <= other.length);
        if (existing.size() >= rules.integer("execution.beam_width")) continue;
        Label n =
            new Label(x, y, direction, cost, length, cost + next.distance(end), current, null);
        existing.add(n);
        queue.add(n);
      }
    }
    return null;
  }

  private static long key(int x, int y, int dir) {
    return ((long) x << 35) | ((long) y << 4) | dir;
  }
}
