package ru.heatroute;

import java.util.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.linearref.LengthIndexedLine;
import ru.heatroute.Verifier.F;

/**
 * Depth-stage checks on the saved file (appendix §5): cover, slope, continuity at every node, a
 * split wherever a ramp passes 3 m, and the vertical rule at every crossing recomputed from
 * geometry. Depth is linear inside a feature, so a bound holds on a stretch when it holds at the
 * stretch ends.
 */
final class DepthVerifier {
  private static final double TOLERANCE = 1e-5;
  private final Verifier v;
  private final Rules rules;
  private final Map<String, List<F>> adjacent = new HashMap<>();

  DepthVerifier(Verifier v, Rules rules) {
    this.v = v;
    this.rules = rules;
  }

  void check(List<F> lines, Collection<Coordinate> roots) {
    double cover = rules.number("depth.minimum_cover_m"),
        slope = rules.number("depth.maximum_slope");
    boolean conservative = rules.text("depth.policy").equals("meeting_conservative");
    Map<String, Double> nodeDepth = new HashMap<>();
    lines =
        lines.stream()
            .filter(f -> f.p.path("depth_start").isNumber() && f.p.path("depth_end").isNumber())
            .collect(java.util.stream.Collectors.toList());
    for (F f : lines) {
      adjacent.computeIfAbsent(f.s("start_node_id"), k -> new ArrayList<>()).add(f);
      adjacent.computeIfAbsent(f.s("end_node_id"), k -> new ArrayList<>()).add(f);
      double a = f.p.path("depth_start").asDouble(),
          b = f.p.path("depth_end").asDouble(),
          length = f.g.getLength();
      if (Math.min(a, b) < cover - 1e-9)
        v.error("DEPTH_COVER", f, "Top of the pipe pair above the minimum depth " + cover + " m");
      if (Math.abs(b - a) > slope * length + TOLERANCE)
        v.error(
            "DEPTH_SLOPE",
            f,
            "Slope " + Math.abs(b - a) / Math.max(length, 1e-9) + " exceeds " + slope);
      if ((a - DepthProfile.KINK) * (b - DepthProfile.KINK) < -1e-12)
        v.error("DEPTH_SPLIT", f, "A ramp through 3 m must be split by a technical node");
      for (String[] end :
          new String[][] {{"start_node_id", "depth_start"}, {"end_node_id", "depth_end"}}) {
        double h = f.p.path(end[1]).asDouble();
        Double seen = nodeDepth.putIfAbsent(f.s(end[0]), h);
        if (seen != null && Math.abs(seen - h) > 1e-9)
          v.error(
              "DEPTH_CONTINUITY",
              f,
              "Different depths at node " + f.s(end[0]) + ": " + seen + " and " + h);
      }
    }
    double snap = rules.number("geometry.topology_snap_m");
    for (F f : lines) {
      LineString line = (LineString) f.g;
      LengthIndexedLine li = new LengthIndexedLine(line);
      Catalog.Pipe pipe = Catalog.pipe(f.i("diameter"));
      for (Dataset.Obstacle o : v.data.near(line.getEnvelopeInternal(), rules.queryMargin())) {
        double[] rule = DepthProfile.vertical(v.data, o, conservative);
        if (rule == null || !line.intersects(o.geometry)) continue;
        boolean road = Double.isNaN(rule[0]);
        Geometry hit = line.intersection(o.geometry);
        if (o.geometry.getDimension() == 2) {
          for (int n = 0; n < hit.getNumGeometries(); n++) {
            Geometry part = hit.getGeometryN(n);
            if (part.getLength() < 1e-6) continue;
            double a = Double.POSITIVE_INFINITY, b = Double.NEGATIVE_INFINITY;
            for (Coordinate c : part.getCoordinates()) {
              double s = li.project(c);
              a = Math.min(a, s);
              b = Math.max(b, s);
            }
            List<Double> depths = ball(f, (a + b) / 2, (b - a) / 2);
            if (road) requireBelow(f, o, depths, rule[1]);
            else side(f, o, depths, rule, pipe, cover);
          }
        } else
          for (Coordinate p : hit.getCoordinates()) {
            if (o.type.equals("heat_network")
                && roots.stream().anyMatch(r -> r.distance(p) <= snap)) continue;
            double overlap = (o.width + pipe.width) / 2 / DepthProfile.sine(line, o.geometry, p);
            List<Double> depths = ball(f, li.project(p), road ? overlap : Math.max(2, overlap));
            if (road) requireBelow(f, o, depths, rule[1]);
            else side(f, o, depths, rule, pipe, cover);
          }
      }
    }
  }

  private void requireBelow(F f, Dataset.Obstacle o, List<Double> depths, double minimum) {
    for (double h : depths)
      if (h < minimum - TOLERANCE) {
        v.error(
            "DEPTH_UNDER_ROAD",
            f,
            "Top at " + h + " m under " + o.id + ", required at least " + minimum + " m");
        return;
      }
  }

  /**
   * Over the whole crossing stretch the pair stays either above the object with the gap, or below
   * it with the gap.
   */
  private void side(
      F f,
      Dataset.Obstacle o,
      List<Double> depths,
      double[] rule,
      Catalog.Pipe pipe,
      double cover) {
    double above = rule[0] - rule[2] - pipe.height, below = rule[0] + rule[1] + rule[2];
    boolean over = above >= cover, under = true;
    for (double h : depths) {
      if (h > above + TOLERANCE) over = false;
      if (h < below - TOLERANCE) under = false;
    }
    if (!over && !under)
      v.error(
          "DEPTH_CLEARANCE",
          f,
          "Vertical clearance at "
              + o.type
              + " "
              + o.id
              + ": need top <= "
              + DepthProfile.round(above)
              + " or >= "
              + DepthProfile.round(below)
              + " m on the crossing stretch, found "
              + depths);
  }

  /**
   * Depths at the ends of every stretch of the network within radius r (along the pipes) of station
   * s on feature f.
   */
  private List<Double> ball(F f, double s, double r) {
    List<Double> out = new ArrayList<>();
    double length = f.g.getLength();
    out.add(at(f, s));
    out.add(at(f, Math.max(0, s - r)));
    out.add(at(f, Math.min(length, s + r)));
    if (s - r < 0) walk(f.s("start_node_id"), f.id, r - s, out, new HashSet<>());
    if (s + r > length) walk(f.s("end_node_id"), f.id, s + r - length, out, new HashSet<>());
    return out;
  }

  private void walk(
      String node, String from, double remaining, List<Double> out, Set<String> seen) {
    if (!seen.add(node)) return;
    for (F g : adjacent.getOrDefault(node, List.of())) {
      if (g.id.equals(from)) continue;
      boolean forward = g.s("start_node_id").equals(node);
      double length = g.g.getLength();
      out.add(at(g, forward ? 0 : length));
      out.add(at(g, forward ? Math.min(length, remaining) : Math.max(0, length - remaining)));
      if (remaining > length)
        walk(
            forward ? g.s("end_node_id") : g.s("start_node_id"),
            g.id,
            remaining - length,
            out,
            seen);
    }
  }

  private static double at(F f, double s) {
    double a = f.p.path("depth_start").asDouble(),
        b = f.p.path("depth_end").asDouble(),
        length = f.g.getLength();
    return length < 1e-12 ? a : a + (b - a) * Math.max(0, Math.min(1, s / length));
  }
}
