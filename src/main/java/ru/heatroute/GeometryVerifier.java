package ru.heatroute;

import java.math.*;
import java.util.*;
import org.locationtech.jts.algorithm.MinimumDiameter;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.linearref.LengthIndexedLine;
import ru.heatroute.Verifier.F;

/**
 * Reconstructs physical runs across technical splits; checks actual geometry independently; shares
 * the local entrance candidate catalogue.
 */
final class GeometryVerifier {
  private final Verifier v;
  private FeasibleEntrances entranceChoices;

  GeometryVerifier(Verifier v) {
    this.v = v;
  }

  static final class Run {
    final LineString line;
    final String start, end;
    final double width;
    final F feature;

    Run(LineString l, List<F> chain) {
      line = l;
      start = chain.get(0).s("start_node_id");
      end = chain.get(chain.size() - 1).s("end_node_id");
      width =
          chain.stream().mapToDouble(f -> Catalog.pipe(f.i("diameter")).width).max().orElseThrow();
      feature = chain.get(0);
    }
  }

  Map<String, Object> check(List<F> lines, Map<String, List<F>> out, Map<String, F> all) {
    Set<String> checked = new HashSet<>();
    List<Run> runs = new ArrayList<>();
    for (F first : lines)
      if (!tech(all.get(first.s("start_node_id")))) {
        List<F> chain = new ArrayList<>();
        List<Coordinate> points = new ArrayList<>();
        F at = first;
        while (at != null) {
          if (!checked.add(at.id)) {
            v.error("NEW_CYCLE", at, "Repeated edge");
            break;
          }
          chain.add(at);
          Coordinate[] c = at.g.getCoordinates();
          for (int i = points.isEmpty() ? 0 : 1; i < c.length; i++) points.add(c[i]);
          if (!tech(all.get(at.s("end_node_id")))) break;
          List<F> next = out.getOrDefault(at.s("end_node_id"), List.of());
          at = next.size() == 1 ? next.get(0) : null;
        }
        if (points.size() > 1) {
          LineString line = Geo.line(points);
          run(line, chain, all);
          runs.add(new Run(line, chain));
        }
      }
    for (int i = 0; i < runs.size(); i++)
      for (int j = i + 1; j < runs.size(); j++) {
        Run a = runs.get(i), b = runs.get(j);
        Geometry hit = a.line.intersection(b.line);
        Set<String> common = new HashSet<>(List.of(a.start, a.end));
        common.retainAll(List.of(b.start, b.end));
        Coordinate anchor = null;
        if (!common.isEmpty()) anchor = v.coordinate(common.iterator().next(), all);
        F ar = all.get(a.start), br = all.get(b.start);
        if (anchor == null
            && ar != null
            && br != null
            && ar.type.equals("tie_in")
            && br.type.equals("tie_in")
            && ar.s("existing_object_id").equals(br.s("existing_object_id"))
            && Geo.key(ar.g.getCoordinate()).equals(Geo.key(br.g.getCoordinate())))
          anchor = ar.g.getCoordinate();
        if (!hit.isEmpty()) {
          boolean bad = anchor == null || hit.getDimension() > 0;
          for (Coordinate c : hit.getCoordinates())
            if (anchor == null || c.distance(anchor) > 1e-6) bad = true;
          if (bad)
            v.error(
                "NEW_INTERSECTION",
                a.feature,
                "Unnoded or overlapping physical run " + b.feature.id);
        }
        if (anchor == null) {
          if (a.line.distance(b.line) < (a.width + b.width) / 2)
            v.error("NEW_GABARIT_OVERLAP", a.feature, "Pipe pair overlaps " + b.feature.id);
        } else if (!JunctionGeometry.fits(a.line, a.width, b.line, b.width, anchor))
          v.error(
              "NEW_GABARIT_OVERLAP",
              a.feature,
              "Pipe pairs overlap beyond common junction " + b.feature.id);
      }
    List<LineString> geometry = new ArrayList<>();
    List<String> starts = new ArrayList<>(), ends = new ArrayList<>();
    for (Run r : runs) {
      geometry.add(r.line);
      starts.add(r.start);
      ends.add(r.end);
    }
    List<Integer> kinkRuns = new ArrayList<>();
    List<String> kinkText = new ArrayList<>();
    Map<String, Object> quality = quality(geometry, starts, ends, kinkRuns, kinkText);
    for (int k = 0; k < kinkRuns.size(); k++)
      v.warning("SMALL_KINK", runs.get(kinkRuns.get(k)).feature, kinkText.get(k));
    return quality;
  }

  /**
   * Geometry quality of physical runs (appendix §2.1, clarification 5: no unjustified small kinks,
   * zigzags or steps). The verifier's own measure, reported per variant and as SMALL_KINK warnings
   * but never an error. Collinear technical splits are not corners. small_kinks: corner turning
   * less than 20° next to a segment shorter than 2 m; short_segments: segments between corners
   * shorter than 1 m; chamber_jogs: a run leaving a chamber within 5° of the incoming run without
   * being straight.
   */
  static Map<String, Object> quality(
      List<LineString> lines,
      List<String> starts,
      List<String> ends,
      List<Integer> kinkRuns,
      List<String> kinkText) {
    int small = 0, near = 0, shortSegments = 0, corners = 0, jogs = 0;
    double minSegment = Double.POSITIVE_INFINITY;
    List<List<Coordinate>> all = new ArrayList<>();
    Map<String, Coordinate[]> arriving = new HashMap<>();
    for (int r = 0; r < lines.size(); r++) {
      List<Coordinate> c = corners(lines.get(r).getCoordinates());
      all.add(c);
      for (int i = 1; i < c.size(); i++) {
        double len = c.get(i - 1).distance(c.get(i));
        minSegment = Math.min(minSegment, len);
        if (len < 1) shortSegments++;
      }
      for (int i = 1; i + 1 < c.size(); i++) {
        corners++;
        double turn = Geo.turn(c.get(i - 1), c.get(i), c.get(i + 1)),
            a = c.get(i - 1).distance(c.get(i)),
            b = c.get(i).distance(c.get(i + 1));
        if (turn < 3) near++;
        if (Math.min(a, b) < 2 && turn < 20) {
          small++;
          kinkRuns.add(r);
          kinkText.add(
              String.format(
                  Locale.ROOT,
                  "turn %.1f deg between %.2f m and %.2f m at %s",
                  turn,
                  a,
                  b,
                  Geo.key(c.get(i))));
        }
      }
      if (c.size() >= 2)
        arriving.put(ends.get(r), new Coordinate[] {c.get(c.size() - 2), c.get(c.size() - 1)});
    }
    for (int r = 0; r < lines.size(); r++) {
      Coordinate[] in = arriving.get(starts.get(r));
      List<Coordinate> c = all.get(r);
      if (in == null || c.size() < 2) continue;
      double turn = Geo.turn(in[0], in[1], c.get(1));
      if (turn >= .01 && turn < 5) jogs++;
    }
    Map<String, Object> q = new LinkedHashMap<>();
    q.put("corners", corners);
    q.put("small_kinks", small);
    q.put("near_collinear", near);
    q.put("short_segments", shortSegments);
    q.put("chamber_jogs", jogs);
    q.put(
        "min_segment_m",
        Double.isFinite(minSegment) ? Math.round(minSegment * 1000) / 1000.0 : null);
    return q;
  }

  /**
   * Run vertices without repeated points and without collinear (technical) splits: a vertex less
   * than 1 cm off the chord of its neighbours is not a corner (measured: a 0.012° vertex next to a
   * 0.68 m segment, 0.14 mm off the line, was counted as a small kink).
   */
  private static List<Coordinate> corners(Coordinate[] raw) {
    List<Coordinate> pts = new ArrayList<>();
    for (Coordinate p : raw)
      if (pts.isEmpty() || pts.get(pts.size() - 1).distance(p) > 1e-9) pts.add(p);
    List<Coordinate> out = new ArrayList<>();
    if (pts.isEmpty()) return out;
    out.add(pts.get(0));
    for (int i = 1; i + 1 < pts.size(); i++) {
      Coordinate a = out.get(out.size() - 1), v = pts.get(i), b = pts.get(i + 1);
      if (Geo.turn(a, v, b) >= .01 && new LineSegment(a, b).distance(v) >= .01) out.add(v);
    }
    if (pts.size() > 1) out.add(pts.get(pts.size() - 1));
    return out;
  }

  private static boolean tech(F f) {
    return f != null && f.type.equals("technical_node");
  }

  private static boolean single(Geometry g) {
    int n = 0;
    for (int i = 0; i < g.getNumGeometries(); i++) if (g.getGeometryN(i).getLength() > 1e-7) n++;
    return n == 1;
  }

  private void run(LineString run, List<F> chain, Map<String, F> all) {
    F first = chain.get(0),
        last = chain.get(chain.size() - 1),
        root = all.get(first.s("start_node_id"));
    if (root != null && !root.type.equals("tie_in")) root = null;
    Dataset.Feature endpoint = v.data.features.get(last.s("end_node_id"));
    Coordinate terminal =
        endpoint != null && endpoint.type.equals("oks_connection_point")
            ? Geo.canonical(endpoint.geometry.getCoordinate())
            : null;
    if (!run.isSimple()) v.error("SELF_INTERSECTION", first, "Non-simple physical run");
    LengthIndexedLine li = new LengthIndexedLine(run);
    List<Intervals.Zone> raw = new ArrayList<>();
    int dn = chain.stream().mapToInt(f -> f.i("diameter")).max().orElseThrow();
    double total = run.getLength();
    for (Dataset.Obstacle o : v.data.near(run.getEnvelopeInternal(), v.rules.queryMargin())) {
      double clearance =
          o.type.equals("building")
              ? (dn < 500 ? 5 : dn < 900 ? 7 : 9)
              : Set.of("road", "tram_tracks").contains(o.type)
                  ? 1.5
                  : Set.of("gas_pipeline", "power_cable").contains(o.type) ? 2 : 1;
      if (o.type.equals("railway")) clearance = v.rules.number("geometry.railway_clearance_m");
      if (o.type.equals("metro")) clearance = v.rules.number("geometry.metro_clearance_m");
      double required = clearance + Catalog.pipe(dn).width / 2 + o.width / 2,
          margin = o.distance(run) - required;
      if (margin
          >= -v.rules.number("geometry.judge_distance_tolerance_m")
              - v.rules.number("geometry.numeric_epsilon_m")) {
        v.minimumMargin = Math.min(v.minimumMargin, margin);
        if (margin < -v.rules.number("geometry.numeric_epsilon_m")) v.toleranceUses++;
        continue;
      }
      Geometry zone = o.geometry.buffer(required, 16), hitZone = run.intersection(zone);
      if (o.building() && terminal != null && o.covers(Geo.point(terminal))) {
        if (!single(hitZone) || hitZone.distance(Geo.point(terminal)) > 1e-6)
          v.error("HOST_REENTRY", first, "Host is not a single terminal suffix " + o.key);
        Coordinate[] c = Geo.simplify(run).getCoordinates();
        LineString suffix = Geo.line(c[c.length - 2], c[c.length - 1]);
        if (hitZone.difference(suffix.buffer(2e-6)).getLength() > 1e-4)
          v.error("HOST_TRANSIT", first, "Host allowance beyond last straight run");
        if (v.rules.current() && !v.rules.flexibleEntrances()) {
          Geometry boundary = o.geometry.getBoundary(), hit = suffix.intersection(boundary);
          double nearest = boundary.distance(Geo.point(terminal)),
              chosen = Double.POSITIVE_INFINITY;
          for (Coordinate p : hit.getCoordinates()) chosen = Math.min(chosen, p.distance(terminal));
          for (Coordinate p : hit.getCoordinates())
            if (p.distance(terminal) > nearest + 1e-4) chosen = Double.POSITIVE_INFINITY;
          if (chosen > nearest + 1e-4)
            v.error(
                "HOST_NEAREST_BOUNDARY", first, "Input terminal must use nearest host boundary");
        }
        if (v.rules.flexibleEntrances()) {
          Geometry physical = suffix.intersection(o.geometry);
          int intervals = 0;
          for (int i = 0; i < physical.getNumGeometries(); i++)
            if (physical.getGeometryN(i).getLength() > 1e-7) intervals++;
          Geometry boundaryHits = suffix.intersection(o.geometry.getBoundary());
          boolean once = !boundaryHits.isEmpty();
          if (once) {
            Coordinate entry = boundaryHits.getCoordinate();
            for (Coordinate p : boundaryHits.getCoordinates())
              if (p.distance(entry) > 1e-5) once = false;
          }
          if (entranceChoices == null) entranceChoices = new FeasibleEntrances(v.data, v.rules);
          List<Coordinate> options = entranceChoices.ports(terminal, dn, false);
          double best = Double.POSITIVE_INFINITY;
          for (Coordinate option : options)
            best = Math.min(best, Geo.line(terminal, option).intersection(o.geometry).getLength());
          if (options.isEmpty() || physical.getLength() > best + .012)
            v.error(
                "HOST_SHORTEST_FEASIBLE",
                first,
                "Long interior transit; shortest valid local entrance=" + best);
          if (intervals != 1 || physical.distance(Geo.point(terminal)) > 1e-6 || !once)
            v.error("HOST_REENTRY", first, "Only one entry into containing polygon is allowed");
        }
        continue;
      }
      if (terminal != null && v.data.hostSibling(o, terminal, v.rules)) {
        Coordinate[] c = Geo.simplify(run).getCoordinates();
        LineString suffix = Geo.line(c[c.length - 2], c[c.length - 1]);
        if (run.intersects(o.geometry))
          v.error(
              "HOST_SIBLING_CROSSING",
              first,
              "Route crosses another part of the containing restriction " + o.key);
        if (hitZone.difference(suffix.buffer(2e-6)).getLength() > 1e-4)
          v.error(
              "HOST_TRANSIT", first, "Host feature allowance beyond last straight run " + o.key);
        continue;
      }
      if (o.type.equals("heat_network")
          && root != null
          && (root.s("existing_object_type").equals("heat_network")
              ? o.id.equals(root.s("existing_object_id"))
              : o.geometry.distance(root.g) <= v.rules.number("geometry.topology_snap_m"))) {
        if (!single(hitZone) || hitZone.distance(root.g) > 1e-6)
          v.error("TARGET_REENTRY", first, "Target is not a single root prefix");
        Coordinate[] c = Geo.simplify(run).getCoordinates();
        LineString prefix = Geo.line(c[0], c[1]);
        if (hitZone.difference(prefix.buffer(2e-6)).getLength() > 1e-4)
          v.error("TARGET_TRANSIT", first, "Target allowance beyond first straight run");
        continue;
      }
      if (!o.special()) {
        v.error("CLEARANCE", first, "Forbidden " + o.key + " margin=" + margin);
        continue;
      }
      Coordinate[] straight = Geo.simplify(run).getCoordinates();
      for (int segment = 1; segment < straight.length; segment++) {
        LineString piece = Geo.line(straight[segment - 1], straight[segment]);
        if (o.distance(piece)
            < required - v.rules.number("geometry.judge_distance_tolerance_m") - 1e-6) {
          Geometry actual = piece.intersection(o.geometry);
          boolean through =
              o.geometry.getDimension() == 1
                  ? !actual.isEmpty() && actual.getDimension() == 0
                  : actual.getLength() > 1e-6;
          if (!through)
            v.error(
                "SPECIAL_CORRIDOR",
                first,
                "Approach allowance extends beyond crossing run " + o.id);
        }
      }
      Geometry cross = run.intersection(o.geometry);
      if (cross.isEmpty()) {
        v.error("CLEARANCE", first, "Special approached without crossing " + o.id);
        continue;
      }
      for (int i = 0; i < cross.getNumGeometries(); i++) {
        Geometry part = cross.getGeometryN(i);
        double a = Double.POSITIVE_INFINITY, b = Double.NEGATIVE_INFINITY;
        for (Coordinate c : part.getCoordinates()) {
          double s = li.project(c);
          a = Math.min(a, s);
          b = Math.max(b, s);
        }
        if (o.geometry.getDimension() == 2) {
          if (part.getLength() < 1e-6) {
            v.error("TANGENT_CONTACT", first, "Boundary touch is not crossing");
            continue;
          }
          LineString inside = Geo.sub(run, a, b);
          if (v.rules.current()) {
            if (inside.getLength() > inside.getStartPoint().distance(inside.getEndPoint()) + 1e-5
                || Set.of("road", "tram_tracks").contains(o.type)
                    && !CrossingBoundary.accepts(inside, o.geometry))
              v.error(
                  "CROSSING_ANGLE",
                  first,
                  "Polygon entry boundary angle "
                      + o.id
                      + (inside.getLength()
                              > inside.getStartPoint().distance(inside.getEndPoint()) + 1e-5
                          ? " (bent inside)"
                          : ""));
          } else {
            Envelope win = new Envelope(part.getCentroid().getCoordinate());
            win.expandBy(Math.max(15, Math.min(80, Math.sqrt(o.geometry.getArea()))));
            Coordinate[] r =
                MinimumDiameter.getMinimumRectangle(o.geometry.intersection(Geo.GF.toGeometry(win)))
                    .getCoordinates();
            if (r.length < 4) {
              v.error("ROAD_AXIS", first, "Degenerate local axis");
              continue;
            }
            double x = r[0].distance(r[1]), y = r[1].distance(r[2]);
            Coordinate axis =
                x > y
                    ? new Coordinate(r[1].x - r[0].x, r[1].y - r[0].y)
                    : new Coordinate(r[2].x - r[1].x, r[2].y - r[1].y);
            Coordinate ca = inside.getCoordinateN(0),
                cb = inside.getCoordinateN(inside.getNumPoints() - 1);
            double angle =
                Math.toDegrees(
                    Math.acos(
                        Math.min(
                            1,
                            Math.abs((cb.x - ca.x) * axis.x + (cb.y - ca.y) * axis.y)
                                / (ca.distance(cb) * Math.hypot(axis.x, axis.y)))));
            if (Math.max(x, y) < 1.2 * Math.min(x, y)
                || angle < 45 - 1e-6
                || inside.getLength() > ca.distance(cb) + 1e-5)
              v.error("ROAD_AXIS", first, "Ambiguous axis or invalid angle");
          }
          if (v.rules.text("cost.road_special_extent").equals("from_clearance_buffer"))
            for (Coordinate c : hitZone.getCoordinates()) {
              double s = li.project(c);
              a = Math.min(a, s);
              b = Math.max(b, s);
            }
          a -= 3;
          b += 3;
        } else {
          if (part.getDimension() != 0) {
            v.error("COLLINEAR_CROSSING", first, "Line overlap");
            continue;
          }
          if (v.rules.current()
              && Set.of("road", "tram_tracks").contains(o.type)
              && !CrossingBoundary.accepts(run, o.geometry))
            v.error("CROSSING_ANGLE", first, "Linear crossing angle " + o.id);
          double extension =
              v.rules.current() && Set.of("road", "tram_tracks").contains(o.type) ? 3 : 2;
          a -= extension;
          b += extension;
        }
        if (a < -1e-6 || b > total + 1e-6)
          v.error("SPECIAL_EXTENT", first, "Mandatory interval truncated");
        if (v.rules.current() && a >= -1e-6 && b <= total + 1e-6) {
          LineString full = Geo.sub(run, Math.max(0, a), Math.min(total, b));
          if (full.getLength() > full.getStartPoint().distance(full.getEndPoint()) + 1e-5)
            v.error("SPECIAL_STRAIGHT", first, "Bend inside mandatory special passage");
        }
        double k =
            o.type.equals("road")
                ? 1.6
                : o.type.equals("tram_tracks")
                    ? 1.75
                    : o.type.equals("gas_pipeline")
                        ? 1.25
                        : o.type.equals("power_cable") ? 1.15 : 1.05;
        raw.add(new Intervals.Zone(a, b, k, o.id));
      }
    }
    List<Intervals.Zone>
        zones = Intervals.combine(raw, v.rules.text("cost.special_overlap").equals("union_max")),
        turns = new ArrayList<>();
    Coordinate[] coords = Geo.simplify(run).getCoordinates();
    double station = 0;
    boolean specialTurn = false;
    for (int i = 1; i < coords.length; i++) {
      double length = coords[i - 1].distance(coords[i]), k = 1;
      if (v.rules.current()
          && i > 1
          && Geo.turn(coords[i - 2], coords[i - 1], coords[i]) > 90.000001)
        v.error("TURN_ANGLE", first, "Turn exceeds 90 degrees");
      if (i > 1) {
        double angle = Geo.turn(coords[i - 2], coords[i - 1], coords[i]),
            tol = v.rules.number("cost.standard_turn_tolerance_deg");
        if (angle > tol && Math.abs(angle - 45) > tol && Math.abs(angle - 90) > tol) {
          k = v.rules.number("cost.nonstandard_turn_factor");
          specialTurn = true;
        }
      }
      turns.add(new Intervals.Zone(station, station + length, k, "turn"));
      station += length;
    }
    if (specialTurn && v.rules.text("cost.turn_scope").equals("whole_logical_section"))
      turns =
          List.of(
              new Intervals.Zone(0, total, v.rules.number("cost.nonstandard_turn_factor"), "turn"));
    station = 0;
    for (F f : chain) {
      double end = station + f.g.getLength(),
          mid = (station + end) / 2,
          ks = Intervals.factor(zones, mid),
          kt = Intervals.factor(turns, mid);
      TreeSet<Double> probes = new TreeSet<>();
      probes.add(station + 1e-5);
      probes.add(end - 1e-5);
      for (Intervals.Zone z : zones) {
        if (z.a > station + 1e-5 && z.a < end - 1e-5) {
          probes.add(z.a - 1e-5);
          probes.add(z.a + 1e-5);
        }
        if (z.b > station + 1e-5 && z.b < end - 1e-5) {
          probes.add(z.b - 1e-5);
          probes.add(z.b + 1e-5);
        }
      }
      for (Intervals.Zone z : turns)
        if (z.a > station + 1e-5 && z.a < end - 1e-5) {
          probes.add(z.a - 1e-5);
          probes.add(z.a + 1e-5);
        }
      for (double p : probes)
        if (Math.abs(Intervals.factor(zones, p) - ks) > 1e-8
            || Math.abs(Intervals.factor(turns, p) - kt) > 1e-8)
          v.error("UNSPLIT_PARAMETERS", f, "Cost parameter changes inside feature");
      if (!f.s("laying_method").equals(ks > 1 ? "special" : "base"))
        v.error("LAYING_METHOD", f, "Wrong special marking");
      BigDecimal kd =
          f.p.path("depth_start").isNumber()
              ? DepthProfile.factor(
                  f.p.path("depth_start").asDouble(), f.p.path("depth_end").asDouble())
              : BigDecimal.ONE;
      v.equal(
          f,
          "cost",
          v.rules.money(
              f.n("length")
                  .multiply(Catalog.pipe(f.i("diameter")).newPrice)
                  .multiply(BigDecimal.valueOf(ks))
                  .multiply(BigDecimal.valueOf(kt))
                  .multiply(kd)),
          BigDecimal.ZERO);
      station = end;
    }
  }
}
