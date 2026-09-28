package ru.heatroute;

import java.util.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.linearref.LengthIndexedLine;

/** Routing predicates. Independent result verification does not call this class. */
public final class GeometryRules {
  private FeasibleEntrances entranceChoices;
  public final Dataset data;
  public final Rules rules;
  private final LinkedHashMap<String, Geometry> buffers = new LinkedHashMap<>(128, .75f, true);
  private long bufferBytes;

  private static long bytes(Geometry g) {
    return 256L + g.getNumPoints() * 48L + g.getNumGeometries() * 128L;
  }

  private Geometry zone(Dataset.Obstacle o, double r) {
    String key = o.key + ":" + r;
    Geometry cached = buffers.get(key);
    if (cached != null) return cached;
    Geometry result = o.geometry.buffer(r);
    long size = bytes(result), budget = (long) rules.number("execution.route_cache_bytes");
    if (size > budget) return result;
    while (bufferBytes + size > budget && !buffers.isEmpty()) {
      String oldest = buffers.keySet().iterator().next();
      bufferBytes -= bytes(buffers.remove(oldest));
    }
    buffers.put(key, result);
    bufferBytes += size;
    return result;
  }

  public GeometryRules(Dataset d, Rules r) {
    data = d;
    rules = r;
  }

  public boolean segment(
      Coordinate a, Coordinate b, int dn, Coordinate terminal, Existing.Root root) {
    if (a.distance(b) < 1e-8) return false;
    LineString l = Geo.line(a, b);
    for (Dataset.Obstacle o : data.near(l.getEnvelopeInternal(), rules.queryMargin())) {
      double dist = o.distance(l), required = o.required(dn, rules);
      if (dist + 1e-7 >= required + rules.number("geometry.routing_safety_margin_m")) continue;
      if (o.building()
          && terminal != null
          && (a.distance(terminal) < 1e-5 || b.distance(terminal) < 1e-5)
          && o.covers(Geo.point(terminal))) {
        Coordinate other = a.distance(terminal) < 1e-5 ? b : a;
        if (o.geometry.distance(Geo.point(other)) >= required
            && singleCross(l, zone(o, required))
            && (!rules.current() || TerminalAccess.accepts(l, terminal, o.geometry, rules)))
          continue;
      }
      if (terminal != null
          && (a.distance(terminal) < 1e-5 || b.distance(terminal) < 1e-5)
          && data.hostSibling(o, terminal, rules)) {
        Coordinate other = a.distance(terminal) < 1e-5 ? b : a;
        if (!l.intersects(o.geometry) && o.geometry.distance(Geo.point(other)) >= required)
          continue;
      }
      if (o.type.equals("heat_network")
          && root != null
          && (a.distance(root.p) < 1e-5 || b.distance(root.p) < 1e-5)
          && isTarget(o, root)) {
        Coordinate other = a.distance(root.p) < 1e-5 ? b : a;
        if (o.geometry.distance(Geo.point(other)) >= required
            && singleCross(l, zone(o, required))
            && o.geometry.distance(Geo.point(root.p)) <= rules.number("geometry.topology_snap_m"))
          continue;
      }
      if (o.special() && crossable(l, o)) continue;
      return false;
    }
    return true;
  }

  public boolean isTarget(Dataset.Obstacle o, Existing.Root root) {
    return root.target.type.equals("heat_network")
        ? o.id.equals(root.target.id)
        : o.geometry.distance(Geo.point(root.p)) <= rules.number("geometry.topology_snap_m");
  }

  private static boolean singleCross(LineString line, Geometry zone) {
    Geometry inside = line.intersection(zone);
    int parts = 0;
    for (int i = 0; i < inside.getNumGeometries(); i++)
      if (inside.getGeometryN(i).getLength() > 1e-7) parts++;
    return parts == 1;
  }

  public static boolean transverse(LineString l, Geometry o) {
    Geometry x = l.intersection(o);
    if (o.getDimension() == 1)
      return !x.isEmpty() && x.getDimension() == 0 && x.getNumGeometries() == 1;
    return !x.isEmpty() && x.getLength() > 1e-6;
  }

  public static Coordinate axis(Geometry polygon, Coordinate where) {
    AxisService.Axis axis = AxisService.estimate(polygon, where);
    return axis == null ? null : axis.vector;
  }

  public static double axisAngle(LineString l, Coordinate axis) {
    Coordinate a = l.getCoordinateN(0), b = l.getCoordinateN(l.getNumPoints() - 1);
    double x = b.x - a.x, y = b.y - a.y;
    return Math.toDegrees(
        Math.acos(
            Math.min(
                1,
                Math.abs(x * axis.x + y * axis.y)
                    / (Math.hypot(x, y) * Math.hypot(axis.x, axis.y)))));
  }

  private boolean crossable(LineString l, Dataset.Obstacle o) {
    if (!transverse(l, o.geometry)) return false;
    if (rules.current()) {
      if (!Set.of("road", "tram_tracks").contains(o.type)) return true;
      if (o.geometry.getDimension() < 2) return CrossingBoundary.accepts(l, o.geometry);
      // every separate passage through a road/tram polygon is checked, as the verifier does
      Geometry x = l.intersection(o.geometry);
      LengthIndexedLine li = new LengthIndexedLine(l);
      for (int i = 0; i < x.getNumGeometries(); i++) {
        Geometry part = x.getGeometryN(i);
        if (part.getLength() < 1e-6) continue;
        double a = Double.POSITIVE_INFINITY, b = Double.NEGATIVE_INFINITY;
        for (Coordinate c : part.getCoordinates()) {
          double s = li.project(c);
          a = Math.min(a, s);
          b = Math.max(b, s);
        }
        if (!CrossingBoundary.accepts(Geo.sub(l, a, b), o.geometry)) return false;
      }
      return true;
    }
    if (o.geometry.getDimension() == 1) return true;
    Geometry x = l.intersection(o.geometry);
    if (x.getNumGeometries() != 1) return false;
    Coordinate axis = axis(o.geometry, x.getCentroid().getCoordinate());
    return axis != null && axisAngle(l, axis) + 1e-6 >= 45;
  }

  public List<Intervals.Zone> events(
      LineString l, int dn, Coordinate terminal, Existing.Root root) {
    if (rules.current()) {
      Coordinate[] turns = Geo.simplify(l).getCoordinates();
      for (int i = 1; i < turns.length - 1; i++)
        if (Geo.turn(turns[i - 1], turns[i], turns[i + 1]) > 90.000001)
          throw new Failure("TURN_ANGLE", "Turn exceeds 90 degrees");
    }
    if (!l.isSimple()) throw new Failure("INVALID_ROUTE", "Self intersection");
    List<Intervals.Zone> zones = new ArrayList<>();
    LengthIndexedLine li = new LengthIndexedLine(l);
    double length = l.getLength();
    for (Dataset.Obstacle o : data.near(l.getEnvelopeInternal(), rules.queryMargin())) {
      double required = o.required(dn, rules);
      if (o.distance(l) + 1e-6 >= required) continue;
      if (o.building() && terminal != null && o.covers(Geo.point(terminal))) {
        Geometry hit = l.intersection(zone(o, required));
        if (!singleCross(l, zone(o, required)) || !hit.isWithinDistance(Geo.point(terminal), 1e-5))
          throw new Failure("HOST_REENTRY", "Host must be one terminal suffix", o.id);
        // The host allowance is restricted to a single unbranched straight terminal run.
        Coordinate[] c = l.getCoordinates();
        LineString last = Geo.line(c[c.length - 2], c[c.length - 1]);
        if (hit.difference(last.buffer(1e-6)).getLength() > 1e-4)
          throw new Failure("HOST_TRANSIT", "Host exit must be local", o.id);
        if (rules.current() && !TerminalAccess.accepts(last, terminal, o.geometry, rules))
          throw new Failure(
              "HOST_NEAREST_BOUNDARY", "Terminal approach must use nearest host boundary", o.id);
        if (rules.flexibleEntrances()) {
          if (entranceChoices == null) entranceChoices = new FeasibleEntrances(data, rules);
          List<Coordinate> options = entranceChoices.ports(terminal, dn, false);
          if (options.isEmpty()
              || entranceChoices.insideLength(terminal, last.getCoordinateN(0))
                  > entranceChoices.insideLength(terminal, options.get(0)) + .011)
            throw new Failure(
                "HOST_SHORTEST_FEASIBLE", "A shorter valid local entrance exists", o.id);
        }
        continue;
      }
      if (terminal != null
          && l.getEndPoint().getCoordinate().distance(terminal) < 1e-5
          && data.hostSibling(o, terminal, rules)) {
        if (l.intersects(o.geometry))
          throw new Failure(
              "HOST_SIBLING_CROSSING",
              "Route crosses another part of the containing restriction",
              o.id);
        Coordinate[] c = l.getCoordinates();
        LineString last = Geo.line(c[c.length - 2], c[c.length - 1]);
        if (l.intersection(zone(o, required)).difference(last.buffer(1e-6)).getLength() > 1e-4)
          throw new Failure(
              "HOST_TRANSIT", "Host feature allowance beyond the final straight run", o.id);
        continue;
      }
      if (o.type.equals("heat_network") && root != null && isTarget(o, root)) {
        Geometry hit = l.intersection(zone(o, required));
        if (!singleCross(l, zone(o, required)) || !hit.isWithinDistance(Geo.point(root.p), 1e-5))
          throw new Failure("TARGET_REENTRY", "Target approach is not a root prefix", o.id);
        Coordinate[] c = l.getCoordinates();
        LineString first = Geo.line(c[0], c[1]);
        if (hit.difference(first.buffer(1e-6)).getLength() > 1e-4)
          throw new Failure("TARGET_TRANSIT", "Target approach must be a straight prefix", o.id);
        continue;
      }
      if (!o.special()) throw new Failure("CLEARANCE", "Obstacle clearance violated", o.id);
      Coordinate[] straight = Geo.simplify(l).getCoordinates();
      for (int k = 1; k < straight.length; k++) {
        LineString segment = Geo.line(straight[k - 1], straight[k]);
        if (o.distance(segment) + 1e-6 < required && !transverse(segment, o.geometry))
          throw new Failure(
              "SPECIAL_CORRIDOR",
              "Clearance exception extends beyond the crossing straight run",
              o.id);
      }
      Geometry hits = l.intersection(o.geometry);
      if (hits.isEmpty())
        throw new Failure("CLEARANCE", "Passing beside special object without clearance", o.id);
      for (int i = 0; i < hits.getNumGeometries(); i++) {
        Geometry hit = hits.getGeometryN(i);
        double start = Double.POSITIVE_INFINITY, end = Double.NEGATIVE_INFINITY;
        for (Coordinate p : hit.getCoordinates()) {
          double s = li.project(p);
          start = Math.min(start, s);
          end = Math.max(end, s);
        }
        if (o.geometry.getDimension() == 2) {
          if (hit.getLength() < 1e-7)
            throw new Failure("TANGENT_CONTACT", "Boundary touch is not crossing", o.id);
          LineString inside = Geo.sub(l, start, end);
          Coordinate axis =
              rules.current()
                  ? new Coordinate(1, 0)
                  : axis(o.geometry, hit.getCentroid().getCoordinate());
          if (axis == null
              || inside.getLength() > inside.getStartPoint().distance(inside.getEndPoint()) + 1e-5
              || (rules.current()
                  ? Set.of("road", "tram_tracks").contains(o.type)
                      && !CrossingBoundary.accepts(inside, o.geometry)
                  : axisAngle(inside, axis) + 1e-6 < 45))
            throw new Failure(
                "CROSSING_ANGLE", "Road/tram axis is ambiguous or angle below 45", o.id);
          if (rules.text("cost.road_special_extent").equals("from_clearance_buffer")) {
            Geometry h = l.intersection(zone(o, required));
            for (Coordinate p : h.getCoordinates()) {
              double s = li.project(p);
              start = Math.min(start, s);
              end = Math.max(end, s);
            }
          }
          start -= 3;
          end += 3;
        } else {
          if (rules.current()
              && Set.of("road", "tram_tracks").contains(o.type)
              && !CrossingBoundary.accepts(l, o.geometry))
            throw new Failure("CROSSING_ANGLE", "Linear crossing below 45 degrees", o.id);
          if (hit.getDimension() != 0)
            throw new Failure("COLLINEAR_CROSSING", "Overlap is not transverse crossing", o.id);
          double extension =
              rules.current() && Set.of("road", "tram_tracks").contains(o.type) ? 3 : 2;
          start -= extension;
          end += extension;
        }
        if (start < -1e-6 || end > length + 1e-6)
          throw new Failure(
              "SPECIAL_EXTENT", "Required special interval cannot be truncated", o.id);
        if (rules.current()) {
          LineString special = Geo.sub(l, Math.max(0, start), Math.min(length, end));
          if (special.getLength() > special.getStartPoint().distance(special.getEndPoint()) + 1e-5)
            throw new Failure(
                "SPECIAL_STRAIGHT",
                "Full special passage including extensions must be straight",
                o.id);
        }
        zones.add(new Intervals.Zone(Math.max(0, start), Math.min(length, end), o.factor(), o.id));
      }
    }
    return Intervals.combine(zones, rules.text("cost.special_overlap").equals("union_max"));
  }
}
