package ru.heatroute;

import java.util.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.operation.buffer.*;

/** Sparse local visibility graph; candidate links are checked lazily. */
public final class Router {
  private final Dataset data;
  private final Rules rules;
  private final GeometryRules checks;
  private final FeasibleEntrances entrances;
  private Coordinate lockedPort;
  private int exteriorReserveDn;

  void reserveExterior(int dn) {
    exteriorReserveDn = dn;
  }

  private List<Network.Edge> avoid = List.of();
  private Coordinate join, heading;

  public LineString routeAvoiding(
      Coordinate start,
      Coordinate end,
      int dn,
      Coordinate terminal,
      Existing.Root root,
      List<Network.Tree> forest) {
    return routeAvoiding(start, end, dn, terminal, root, forest, null);
  }

  public LineString routeAvoiding(
      Coordinate start,
      Coordinate end,
      int dn,
      Coordinate terminal,
      Existing.Root root,
      List<Network.Tree> forest,
      Coordinate approach) {
    List<Network.Edge> previous = avoid;
    Coordinate previousJoin = join, previousHeading = heading;
    heading = approach;
    avoid = new ArrayList<>();
    for (Network.Tree tree : forest) avoid.addAll(tree.edges);
    join = start;
    try {
      return route(start, end, dn, terminal, root);
    } finally {
      avoid = previous;
      join = previousJoin;
      heading = previousHeading;
    }
  }

  private boolean segment(
      Coordinate a, Coordinate b, int dn, Coordinate terminal, Existing.Root root) {
    if (lockedPort != null
        && terminal != null
        && (a.distance(terminal) < 1e-5 || b.distance(terminal) < 1e-5)) {
      Coordinate outside = a.distance(terminal) < 1e-5 ? b : a;
      if (new LineSegment(terminal, outside).distance(lockedPort) > 1e-4
          || outside.distance(terminal) + 1e-5 < lockedPort.distance(terminal)) return false;
    }
    if (heading != null
        && join != null
        && (a.distance(join) < 1e-5 || b.distance(join) < 1e-5)
        && Geo.turn(heading, join, a.distance(join) < 1e-5 ? b : a) > 90.000001) return false;
    boolean terminalLink =
        lockedPort != null
            && terminal != null
            && (a.distance(terminal) < 1e-5
                || b.distance(terminal) < 1e-5
                || a.distance(lockedPort) < 1e-5
                || b.distance(lockedPort) < 1e-5);
    if (!checks.segment(a, b, terminalLink ? dn : Math.max(dn, exteriorReserveDn), terminal, root))
      return false;
    LineString line = Geo.line(a, b);
    for (Network.Edge edge : avoid) {
      // keep the pipe gabarit clear of already built new sections (Evaluation NEW_GABARIT_OVERLAP),
      // except at the joint
      boolean atJoin =
          join != null
              && (a.distance(join) < 1e-5 || b.distance(join) < 1e-5)
              && (edge.line.getStartPoint().getCoordinate().distance(join) < 1e-5
                  || edge.line.getEndPoint().getCoordinate().distance(join) < 1e-5);
      if (!atJoin
          && line.distance(edge.line) < Catalog.pipe(Math.max(dn, 50)).width + .05
          && line.distance(edge.line) > 1e-9) return false;
      if (!line.getEnvelopeInternal().intersects(edge.line.getEnvelopeInternal())) continue;
      Geometry hit = line.intersection(edge.line);
      if (hit.isEmpty()) continue;
      if (hit.getDimension() > 0) return false;
      for (Coordinate p : hit.getCoordinates())
        if (join == null || p.distance(join) > 1e-5) return false;
    }
    return true;
  }

  private final Map<String, Geometry> candidateBuffers = new LinkedHashMap<>();
  private long expansions;
  private final long deadline;

  public boolean budgetExpired() {
    return System.nanoTime() >= deadline;
  }

  public Router(Dataset d, Rules r) {
    data = d;
    rules = r;
    checks = new GeometryRules(d, r);
    entrances = new FeasibleEntrances(d, r);
    deadline = r.searchDeadline();
  }

  public long expansions() {
    return expansions;
  }

  public LineString route(
      Coordinate start, Coordinate end, int dn, Coordinate terminal, Existing.Root root) {
    Coordinate previous = lockedPort;
    try {
      if (!rules.flexibleEntrances() || terminal == null || data.hosts(end).isEmpty())
        return routeCore(start, end, dn, terminal, root);
      // Fix the locally shortest valid entrance before comparing exterior routes.
      // A* and shortcut smoothing must use this same ray through the facade.
      List<Coordinate> choices = entrances.ports(end, dn, false);
      if (choices.isEmpty()) return null;
      double shortest = entrances.insideLength(end, choices.get(0));
      for (Coordinate port : choices) {
        if (entrances.insideLength(end, port) > shortest + .011) break;
        lockedPort = port;
        LineString path = routeCore(start, end, dn, terminal, root);
        if (path != null) return path;
      }
      return null;
    } finally {
      lockedPort = previous;
    }
  }

  private List<Coordinate> terminalPorts(Coordinate p, int dn) {
    if (lockedPort != null) return List.of(lockedPort);
    return rules.flexibleEntrances()
        ? entrances.ports(p, dn, false)
        : TerminalAccess.ports(p, data.hosts(p), dn, rules);
  }

  private LineString routeCore(
      Coordinate start, Coordinate end, int dn, Coordinate terminal, Existing.Root root) {
    if (budgetExpired()) return null;
    if (rules.current() && !endpointPossible(end, dn, terminal)) return null;
    if (start.distance(end) < 1e-7) return null;
    if (segment(start, end, dn, terminal, root)) {
      LineString direct = Geo.line(start, end);
      try {
        checks.events(direct, dn, terminal, root);
        return compareRoadDetour(direct, start, end, dn, terminal, root);
      } catch (Failure ignored) {
      }
    }
    // A host/root connection needs an outside port even for a completely straight path.
    for (double padding : rules.current() ? new double[] {60, 180} : new double[] {80, 200, 600}) {
      LineString path = search(start, end, dn, terminal, root, padding);
      if (path != null) {
        path = improve(path, dn, terminal, root);
        try {
          checks.events(path, dn, terminal, root);
          return compareRoadDetour(path, start, end, dn, terminal, root);
        } catch (Failure ex) {
          /* Expand graph, keep all input geometry intact. */
        }
      }
    }
    if (rules.current()) return null;
    List<Coordinate> starts = root == null ? List.of(start) : ports(start, dn, false, root);
    for (double padding : new double[] {120, 400}) {
      LineString path =
          new GridRouter(data, rules, deadline)
              .route(start, end, dn, terminal, root, starts, padding);
      if (path != null)
        try {
          return improve(path, dn, terminal, root);
        } catch (Failure ignored) {
        }
    }
    return null;
  }

  private boolean endpointPossible(Coordinate p, int dn, Coordinate terminal) {
    Point point = Geo.point(p);
    for (Dataset.Obstacle o : data.near(new Envelope(p), rules.queryMargin())) {
      if (o.building() && terminal != null && o.covers(point)) continue;
      if (!o.special() && o.distance(point) + 1e-6 < o.required(dn, rules)) return false;
    }
    if (terminal != null && !data.hosts(p).isEmpty()) {
      boolean possible = false;
      for (Coordinate port : terminalPorts(p, dn))
        if (checks.segment(port, p, dn, p, null)) {
          possible = true;
          break;
        }
      if (!possible) return false;
    }
    return true;
  }

  private List<Coordinate> ports(Coordinate p, int dn, boolean host, Existing.Root root) {
    if (!host) dn = Math.max(dn, exteriorReserveDn);
    List<Coordinate> out = new ArrayList<>();
    double reach = 10;
    List<Dataset.Obstacle> obstacles = host ? data.hosts(p) : new ArrayList<>();
    if (host) {
      for (Dataset.Obstacle o : obstacles)
        reach =
            Math.max(
                reach, o.geometry.getEnvelopeInternal().getDiameter() + o.required(dn, rules) + 2);
    } else reach = Catalog.pipe(dn).width / 2 + 4;
    if (host && obstacles.isEmpty()) {
      out.add(p);
      return out;
    }
    if (host && rules.current() && !obstacles.isEmpty()) return terminalPorts(p, dn);
    for (int i = 0; i < 32; i++) {
      double angle = 2 * Math.PI * i / 32;
      Coordinate far = new Coordinate(p.x + reach * Math.cos(angle), p.y + reach * Math.sin(angle));
      LineString ray = Geo.line(p, far);
      double station = 0;
      List<Dataset.Obstacle> zones = host ? obstacles : data.near(new Envelope(p), 5);
      for (Dataset.Obstacle o : zones)
        if (host || o.type.equals("heat_network") && root != null && checks.isTarget(o, root)) {
          Geometry z = o.geometry.buffer(o.required(dn, rules) + .08, 4);
          Geometry hit = ray.intersection(z);
          for (Coordinate c : hit.getCoordinates()) station = Math.max(station, p.distance(c));
        }
      Coordinate port =
          new Coordinate(
              p.x + (station + .1) * Math.cos(angle), p.y + (station + .1) * Math.sin(angle));
      if (checks.segment(p, port, dn, host ? p : null, root)) out.add(port);
    }
    return out;
  }

  private LineString search(
      Coordinate start,
      Coordinate end,
      int dn,
      Coordinate terminal,
      Existing.Root root,
      double padding) {
    return search(start, end, dn, terminal, root, padding, false);
  }

  private LineString search(
      Coordinate start,
      Coordinate end,
      int dn,
      Coordinate terminal,
      Existing.Root root,
      double padding,
      boolean avoidRoad) {
    Envelope box = new Envelope(start, end);
    box.expandBy(padding);
    List<Coordinate> points = new ArrayList<>();
    points.add(start);
    points.add(end);
    points.addAll(ports(start, dn, false, root));
    List<Coordinate> terminalPorts = ports(end, dn, true, root);
    points.addAll(terminalPorts);
    for (Dataset.Obstacle o : data.near(box, rules.queryMargin())) {
      if (Set.of("gas_pipeline", "power_cable").contains(o.type)) continue;
      double required =
          o.required(Math.max(dn, exteriorReserveDn), rules)
              + rules.number("geometry.routing_safety_margin_m");
      BufferParameters params = new BufferParameters(2);
      double radius =
          (rules.current() && Set.of("road", "tram_tracks").contains(o.type)
                  ? Math.max(required + .03, 3.15)
                  : required + .03)
              / Math.cos(Math.PI / 8);
      String bufferKey = o.key + ":" + dn;
      Geometry buffer = candidateBuffers.get(bufferKey);
      if (buffer == null) {
        buffer = BufferOp.bufferOp(o.geometry, radius, params);
        if (rules.current())
          buffer = org.locationtech.jts.simplify.DouglasPeuckerSimplifier.simplify(buffer, .35);
        if (candidateBuffers.size() > 1024) candidateBuffers.clear();
        candidateBuffers.put(bufferKey, buffer);
      }
      for (int k = 0; k < buffer.getNumGeometries(); k++) {
        Geometry part = buffer.getGeometryN(k);
        if (!(part instanceof Polygon)) continue;
        Polygon p = (Polygon) part;
        addRing(points, p.getExteriorRing().getCoordinates(), box);
        for (int h = 0; h < p.getNumInteriorRing(); h++)
          addRing(points, p.getInteriorRingN(h).getCoordinates(), box);
      }
    }
    // Canonical point order makes the search insensitive to ring direction and feature order.
    List<Coordinate> extra = new ArrayList<>(points.subList(2, points.size()));
    extra.sort(Comparator.comparingDouble((Coordinate c) -> c.x).thenComparingDouble(c -> c.y));
    points = new ArrayList<>(points.subList(0, 2));
    Coordinate prev = null;
    for (Coordinate c : extra) {
      if (prev == null || c.distance(prev) > 1e-5) points.add(c);
      prev = c;
    }
    Set<Integer> goalPorts = new TreeSet<>();
    if (rules.current())
      for (int i = 0; i < points.size(); i++)
        for (Coordinate port : terminalPorts) if (points.get(i).equals2D(port)) goalPorts.add(i);
    STRtree idx = new STRtree();
    for (int i = 0; i < points.size(); i++) idx.insert(new Envelope(points.get(i)), i);
    idx.build();
    PriorityQueue<Label> queue =
        new PriorityQueue<>(
            Comparator.comparingDouble((Label l) -> l.f)
                .thenComparingDouble(l -> l.g)
                .thenComparingInt(l -> l.at)
                .thenComparingInt(l -> l.from));
    Map<Long, List<Label>> best = new HashMap<>();
    Label first = new Label(0, -1, 0, 0, start.distance(end), null);
    queue.add(first);
    best.put(pair(-1, 0), new ArrayList<>(List.of(first)));
    Map<Long, Boolean> visibility = new HashMap<>(), bendAtStart = new HashMap<>();
    Map<Integer, List<Integer>> angularCache = new HashMap<>();
    int budget =
        Math.min(
            rules.current() ? 6000 : 30000,
            rules.integer("execution.maximum_candidate_evaluations"));
    int count = 0;
    while (!queue.isEmpty() && count++ < budget) {
      if (budgetExpired()) return null;
      if (Thread.currentThread().isInterrupted())
        throw new Failure("CANCELLED", "Calculation cancelled");
      Label label = queue.poll();
      long lk = pair(label.from, label.at);
      if (!best.getOrDefault(lk, List.of()).contains(label)) continue;
      if (label.at == 1) {
        List<Coordinate> route = new ArrayList<>();
        for (Label a = label; a != null; a = a.parent) route.add(points.get(a.at));
        Collections.reverse(route);
        LineString candidate = Geo.simplify(Geo.line(route));
        try {
          checks.events(candidate, dn, terminal, root);
          return candidate;
        } catch (Failure invalid) {
          continue;
        }
      }
      expansions++;
      Coordinate at = points.get(label.at);
      List<Integer> neighbors = neighbors(idx, points, at, 36);
      if (rules.current()) {
        List<Integer> angular = angularCache.get(label.at);
        if (angular == null) {
          angular = new ArrayList<>();
          List<List<Integer>> sectors = new ArrayList<>();
          for (int sector = 0; sector < 16; sector++) sectors.add(new ArrayList<>());
          for (int index = 0; index < points.size(); index++)
            if (index != label.at) {
              Coordinate candidate = points.get(index);
              int sector =
                  (int)
                      Math.floor(
                          (Math.atan2(candidate.y - at.y, candidate.x - at.x) + Math.PI)
                              * 16
                              / (2 * Math.PI));
              sectors.get(Math.min(15, sector)).add(index);
            }
          final List<Coordinate> candidates = points;
          for (List<Integer> sector : sectors) {
            sector.sort(Comparator.comparingDouble(index -> candidates.get(index).distance(at)));
            int tried = 0;
            for (int index : sector) {
              if (tried++ >= 24) break;
              long link = pair(Math.min(label.at, index), Math.max(label.at, index));
              Boolean visible = visibility.get(link);
              if (visible == null) {
                visible = segment(at, points.get(index), dn, terminal, root);
                visibility.put(link, visible);
              }
              if (visible) {
                angular.add(index);
                break;
              }
            }
          }
          angularCache.put(label.at, angular);
        }
        neighbors.addAll(angular);
      }
      neighbors.add(1);
      neighbors.addAll(goalPorts);
      if (label.at == 0) {
        neighbors.addAll(neighbors(idx, points, at, 70));
      }
      for (int n : new TreeSet<>(neighbors)) {
        if (n == label.at || n == label.from || n == 0) continue;
        Coordinate next = points.get(n);
        double len = at.distance(next);
        if (len < 1e-6) continue;
        long key = pair(Math.min(label.at, n), Math.max(label.at, n));
        Boolean visible = visibility.get(key);
        if (visible == null) {
          visible = segment(at, next, dn, terminal, root);
          if (visible && avoidRoad) {
            LineString link = Geo.line(at, next);
            for (Dataset.Obstacle obstacle :
                data.near(link.getEnvelopeInternal(), rules.queryMargin()))
              if (Set.of("road", "tram_tracks").contains(obstacle.type)
                  && link.distance(obstacle.geometry)
                      < obstacle.required(dn, rules)
                          + rules.number("geometry.routing_safety_margin_m")) {
                visible = false;
                break;
              }
          }
          visibility.put(key, visible);
        }
        if (!visible) continue;
        double turn = label.parent == null ? 0 : Geo.turn(points.get(label.from), at, next);
        if (turn > (rules.current() ? 90.000001 : 179.99)) continue;
        if (rules.current() && turn > 1e-5) {
          long incoming = pair(label.at, label.from), outgoing = pair(label.at, n);
          Boolean incomingOK = bendAtStart.get(incoming);
          if (incomingOK == null) {
            incomingOK = bendAllowed(at, points.get(label.from));
            bendAtStart.put(incoming, incomingOK);
          }
          if (!incomingOK) continue;
          Boolean outgoingOK = bendAtStart.get(outgoing);
          if (outgoingOK == null) {
            outgoingOK = bendAllowed(at, next);
            bendAtStart.put(outgoing, outgoingOK);
          }
          if (!outgoingOK) continue;
        }
        double k =
            rules.current()
                    || turn < .01
                    || Math.abs(turn - 45) <= .01
                    || Math.abs(turn - 90) <= .01
                ? 1
                : 1.5;
        double g = label.g + len * effectivePrice(dn, k) / effectivePrice(dn, 1);
        double length = label.length + len;
        Catalog.Pipe pipe = Catalog.pipe(dn);
        double maxLength =
            rules.current()
                ? Catalog.pipe(1400).limit
                : pipe.index + 1 < Catalog.PIPES.size() ? Catalog.next(pipe).limit : pipe.limit;
        if (length + next.distance(end) > maxLength + 1e-6) continue;
        long keyLabel = pair(label.at, n);
        List<Label> known = best.computeIfAbsent(keyLabel, k0 -> new ArrayList<>());
        boolean dominated = false;
        for (Label other : known)
          if (other.g <= g + 1e-7 && other.length <= length + 1e-7) {
            dominated = true;
            break;
          }
        if (dominated) continue;
        known.removeIf(other -> g <= other.g && length <= other.length);
        if (known.size() >= rules.integer("execution.beam_width")) continue;
        Label proposed = new Label(n, label.at, g, length, g + next.distance(end), label);
        known.add(proposed);
        queue.add(proposed);
      }
    }
    return null;
  }

  private boolean bendAllowed(Coordinate at, Coordinate other) {
    LineString piece = Geo.line(at, other);
    for (Dataset.Obstacle obstacle : data.near(new Envelope(at), 3.01)) {
      if (!obstacle.special()) continue;
      double extension = Set.of("road", "tram_tracks").contains(obstacle.type) ? 3 : 2;
      if (obstacle.distance(Geo.point(at)) >= extension - 1e-6) continue;
      Geometry hit = piece.intersection(obstacle.geometry);
      for (Coordinate point : hit.getCoordinates())
        if (point.distance(at) < extension - 1e-6) return false;
    }
    return true;
  }

  private static long pair(int a, int b) {
    return ((long) (a + 1) << 32) | (b & 0xffffffffL);
  }

  private static void addRing(List<Coordinate> points, Coordinate[] c, Envelope box) {
    for (int i = 0; i < c.length - 1; i++) if (box.contains(c[i])) points.add(c[i]);
  }

  @SuppressWarnings("unchecked")
  private static List<Integer> neighbors(
      STRtree idx, List<Coordinate> points, Coordinate p, int max) {
    List<Integer> all = new ArrayList<>();
    for (double radius = 20; radius <= 4000; radius *= 2) {
      Envelope e = new Envelope(p);
      e.expandBy(radius);
      all = new ArrayList<>((List<Integer>) (List<?>) idx.query(e));
      if (all.size() >= max || radius >= 2000) break;
    }
    all.sort(
        Comparator.comparingDouble((Integer i) -> points.get(i).distance(p))
            .thenComparingInt(i -> i));
    return new ArrayList<>(all.subList(0, Math.min(max, all.size())));
  }

  private LineString improve(LineString path, int dn, Coordinate terminal, Existing.Root root) {
    List<Coordinate> c = new ArrayList<>(Arrays.asList(path.getCoordinates()));
    for (int i = 0; i < c.size() - 2; i++)
      for (int j = c.size() - 1; j > i + 1; j--)
        if (segment(c.get(i), c.get(j), dn, terminal, root)) {
          List<Coordinate> proposed = new ArrayList<>(c.subList(0, i + 1));
          proposed.addAll(c.subList(j, c.size()));
          LineString next = Geo.line(proposed);
          try {
            checks.events(next, dn, terminal, root);
            if (roughPrice(next, dn, terminal, root)
                <= roughPrice(Geo.line(c), dn, terminal, root)) {
              c = proposed;
              break;
            }
          } catch (Failure ignored) {
          }
        }
    if (!rules.current())
      for (int i = 1; i < c.size() - 1; i++) {
        Coordinate a = c.get(i - 1), z = c.get(i + 1);
        double dx = z.x - a.x, dy = z.y - a.y;
        List<Coordinate> options = new ArrayList<>();
        options.add(new Coordinate(a.x, z.y));
        options.add(new Coordinate(z.x, a.y));
        double diagonal = Math.min(Math.abs(dx), Math.abs(dy));
        options.add(
            new Coordinate(a.x + Math.signum(dx) * diagonal, a.y + Math.signum(dy) * diagonal));
        options.add(
            new Coordinate(z.x - Math.signum(dx) * diagonal, z.y - Math.signum(dy) * diagonal));
        double price = roughPrice(Geo.line(c), dn, terminal, root);
        for (Coordinate middle : options) {
          if (a.distance(middle) < 1e-6 || middle.distance(z) < 1e-6) continue;
          List<Coordinate> proposed = new ArrayList<>(c);
          proposed.set(i, middle);
          LineString next = Geo.line(proposed);
          try {
            double value = roughPrice(next, dn, terminal, root);
            if (value + 1e-9 < price) {
              c = proposed;
              price = value;
            }
          } catch (Failure ignored) {
          }
        }
      }
    return Geo.simplify(Geo.line(c));
  }

  private double effectivePrice(int dn, double k) {
    return k
            * Catalog.pipe(dn).newPrice.doubleValue()
            * rules.number("cost.weight_cost")
            / rules.number("cost.base_cost_rub")
        + rules.number("cost.weight_length") / rules.number("cost.base_length_m");
  }

  private LineString compareRoadDetour(
      LineString candidate,
      Coordinate start,
      Coordinate end,
      int dn,
      Coordinate terminal,
      Existing.Root root) {
    if (checks.events(candidate, dn, terminal, root).stream().noneMatch(z -> z.k >= 1.6))
      return candidate;
    LineString best = candidate;
    double price = roughPrice(best, dn, terminal, root);
    for (double pad : new double[] {80, 200}) {
      if (budgetExpired()) break;
      LineString other = search(start, end, dn, terminal, root, pad, true);
      if (other == null) continue;
      try {
        other = improve(other, dn, terminal, root);
        checks.events(other, dn, terminal, root);
        double value = roughPrice(other, dn, terminal, root);
        if (value < price) {
          best = other;
          price = value;
        }
      } catch (Failure ignored) {
      }
    }
    return best;
  }

  private double roughPrice(LineString line, int dn, Coordinate terminal, Existing.Root root) {
    List<Intervals.Zone> zones = checks.events(line, dn, terminal, root), turns = new ArrayList<>();
    TreeSet<Double> cuts = new TreeSet<>();
    cuts.add(0.0);
    cuts.add(line.getLength());
    for (Intervals.Zone z : zones) {
      cuts.add(z.a);
      cuts.add(z.b);
    }
    Coordinate[] points = Geo.simplify(line).getCoordinates();
    double at = 0;
    boolean nonstandard = false;
    for (int i = 1; i < points.length; i++) {
      double length = points[i - 1].distance(points[i]), k = 1;
      if (i > 1) {
        double angle = Geo.turn(points[i - 2], points[i - 1], points[i]),
            tol = rules.number("cost.standard_turn_tolerance_deg");
        if (angle > tol && Math.abs(angle - 45) > tol && Math.abs(angle - 90) > tol) {
          k = rules.number("cost.nonstandard_turn_factor");
          nonstandard = true;
        }
      }
      turns.add(new Intervals.Zone(at, at + length, k, "turn"));
      cuts.add(at);
      at += length;
    }
    if (nonstandard && rules.text("cost.turn_scope").equals("whole_logical_section"))
      turns =
          List.of(
              new Intervals.Zone(
                  0, line.getLength(), rules.number("cost.nonstandard_turn_factor"), "turn"));
    double cost = 0;
    Double previous = null;
    for (double station : cuts) {
      if (previous != null) {
        double mid = (previous + station) / 2;
        cost +=
            (station - previous)
                * effectivePrice(dn, Intervals.factor(zones, mid) * Intervals.factor(turns, mid));
      }
      previous = station;
    }
    return cost;
  }

  private static final class Label {
    final int at, from;
    final double g, length, f;
    final Label parent;

    Label(int a, int b, double g, double length, double f, Label p) {
      at = a;
      from = b;
      this.g = g;
      this.length = length;
      this.f = f;
      parent = p;
    }
  }
}
