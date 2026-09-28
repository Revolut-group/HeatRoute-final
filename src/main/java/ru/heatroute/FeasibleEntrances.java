package ru.heatroute;

import java.util.*;
import org.locationtech.jts.geom.*;

/** User-requested fallback: explore the full containing boundary, never other buildings. */
final class FeasibleEntrances {
  private final Dataset data;
  private final Rules rules;
  private final GeometryRules checks;
  private final Map<String, List<Polygon>> isolatedPockets = new HashMap<>();
  private final Map<String, List<Coordinate>> cache = new LinkedHashMap<>();

  FeasibleEntrances(Dataset data, Rules rules) {
    this.data = data;
    this.rules = rules;
    checks = new GeometryRules(data, rules);
  }

  List<Coordinate> ports(Coordinate p, int dn, boolean expanded) {
    String key = Geo.key(p) + ":" + dn + ":" + expanded;
    List<Coordinate> found = cache.get(key);
    if (found != null) return found;
    List<Dataset.Obstacle> hosts = data.hosts(p);
    List<Coordinate> nearest = new ArrayList<>();
    for (Coordinate raw : TerminalAccess.ports(p, hosts, dn, rules)) {
      Coordinate port = extendPastCrossings(p, raw, dn);
      if (checks.segment(port, p, dn, p, null) && reachableComponent(p, port, dn))
        nearest.add(port);
    }
    if (!expanded && !nearest.isEmpty()) {
      cache.put(key, nearest);
      return nearest;
    }
    TreeMap<String, Coordinate> valid = new TreeMap<>();
    for (Coordinate port : nearest) valid.put(Geo.key(port), port);
    for (Dataset.Obstacle host : hosts) {
      Geometry boundary = host.geometry.getBoundary(),
          buffer = host.geometry.buffer(host.required(dn, rules) + .05);
      double reach =
          host.geometry.getEnvelopeInternal().getDiameter() * 2 + host.required(dn, rules) * 4 + 10;
      for (int ring = 0; ring < boundary.getNumGeometries(); ring++) {
        Coordinate[] c = boundary.getGeometryN(ring).getCoordinates();
        for (int i = 1; i < c.length; i++) {
          LineSegment side = new LineSegment(c[i - 1], c[i]);
          double length = side.getLength();
          if (length < 1e-7) continue;
          // Include exact orthogonal projection and vertices, then search valid intervals along
          // this side.
          TreeSet<Double> stations = new TreeSet<>();
          stations.add(Math.max(0, Math.min(1, side.projectionFactor(p))));
          int steps = Math.max(1, (int) Math.ceil(length / .5));
          for (int j = 0; j <= steps; j++) stations.add((double) j / steps);
          Double previous = null;
          Coordinate previousPort = null, best = null;
          double bestDistance = Double.POSITIVE_INFINITY;
          for (double t : stations) {
            if (Thread.currentThread().isInterrupted())
              throw new Failure("CANCELLED", "Calculation cancelled");
            Coordinate q = side.pointAlong(t), port = at(p, q, host, buffer, reach, dn);
            if (previous != null && (port == null) != (previousPort == null)) {
              double a = previous, b = t;
              Coordinate refined = port != null ? port : previousPort;
              while ((b - a) * length > .01) {
                double middle = (a + b) / 2;
                Coordinate trial = at(p, side.pointAlong(middle), host, buffer, reach, dn);
                if ((trial != null) == (previousPort != null)) a = middle;
                else b = middle;
                if (trial != null) refined = trial;
              }
              if (refined != null && insideLength(p, refined) < bestDistance) {
                best = refined;
                bestDistance = insideLength(p, refined);
              }
            }
            if (port != null && insideLength(p, port) < bestDistance) {
              best = port;
              bestDistance = insideLength(p, port);
            }
            if (port == null && previousPort != null && best != null) {
              valid.put(Geo.key(best), best);
              best = null;
              bestDistance = Double.POSITIVE_INFINITY;
            }
            previous = t;
            previousPort = port;
          }
          if (best != null) valid.put(Geo.key(best), best);
        }
      }
    }
    if (valid.isEmpty()) angularFallback(p, hosts, dn, valid);
    found = new ArrayList<>(valid.values());
    found.sort(
        Comparator.comparingDouble((Coordinate port) -> insideLength(p, port))
            .thenComparing(Geo::key));
    if (cache.size() > 256) cache.clear();
    cache.put(key, found);
    return found;
  }

  double insideLength(Coordinate p, Coordinate port) {
    double length = 0;
    LineString line = Geo.line(p, port);
    for (Dataset.Obstacle host : data.hosts(p))
      length = Math.max(length, line.intersection(host.geometry).getLength());
    return length;
  }

  private void angularFallback(
      Coordinate p, List<Dataset.Obstacle> hosts, int dn, Map<String, Coordinate> valid) {
    for (Dataset.Obstacle host : hosts) {
      Geometry buffer = host.geometry.buffer(host.required(dn, rules) + .05);
      List<Geometry> forbidden = new ArrayList<>();
      for (Dataset.Obstacle other :
          data.near(host.geometry.getEnvelopeInternal(), rules.queryMargin())) {
        if (hosts.contains(other) || other.special()) continue;
        Geometry zone =
            other.geometry.buffer(
                other.required(dn, rules) + rules.number("geometry.routing_safety_margin_m"));
        if (zone.isWithinDistance(buffer, .3)) forbidden.add(zone);
      }
      Geometry union =
          forbidden.isEmpty()
              ? Geo.GF.createPolygon()
              : org.locationtech.jts.operation.union.UnaryUnionOp.union(forbidden);
      TreeSet<Double> critical = new TreeSet<>();
      for (Geometry geometry :
          List.of(
              host.geometry, buffer, union, buffer.getBoundary().intersection(union.getBoundary())))
        for (Coordinate point : geometry.getCoordinates())
          critical.add(Math.atan2(point.y - p.y, point.x - p.x));
      List<Double> angles = new ArrayList<>(critical);
      double reach = host.geometry.getEnvelopeInternal().getDiameter() * 2 + 100;
      for (int i = 0; i < angles.size(); i++) {
        if (Thread.currentThread().isInterrupted())
          throw new Failure("CANCELLED", "Calculation cancelled");
        double first = angles.get(i),
            next = i + 1 < angles.size() ? angles.get(i + 1) : angles.get(0) + 2 * Math.PI;
        for (double angle : new double[] {first, (first + next) / 2}) {
          double ux = Math.cos(angle), uy = Math.sin(angle);
          LineString ray = Geo.line(p, new Coordinate(p.x + ux * reach, p.y + uy * reach));
          Geometry hit = ray.intersection(buffer);
          double exit = 0;
          for (int n = 0; n < hit.getNumGeometries(); n++) {
            Geometry part = hit.getGeometryN(n);
            if (part.distance(Geo.point(p)) < 1e-6)
              for (Coordinate point : part.getCoordinates())
                exit = Math.max(exit, p.distance(point));
          }
          if (exit == 0) continue;
          Coordinate port = new Coordinate(p.x + ux * (exit + .1), p.y + uy * (exit + .1));
          port = extendPastCrossings(p, port, dn);
          if (checks.segment(port, p, dn, p, null) && reachableComponent(p, port, dn))
            valid.put(Geo.key(port), port);
        }
      }
    }
  }

  private boolean reachableComponent(Coordinate terminal, Coordinate port, int dn) {
    List<Dataset.Obstacle> hosts = data.hosts(terminal);
    if (hosts.isEmpty()) return true;
    String key =
        dn
            + ":"
            + hosts.stream()
                .map(o -> o.key)
                .sorted()
                .collect(java.util.stream.Collectors.joining("|"));
    List<Polygon> pockets = isolatedPockets.get(key);
    if (pockets == null) {
      List<Geometry> zones = new ArrayList<>();
      Envelope window = new Envelope();
      for (Dataset.Obstacle host : hosts)
        window.expandToInclude(host.geometry.getEnvelopeInternal());
      for (Dataset.Obstacle obstacle : data.near(window, rules.queryMargin()))
        if (!obstacle.special() && !obstacle.type.equals("heat_network"))
          zones.add(
              obstacle.geometry.buffer(
                  obstacle.required(dn, rules) + rules.number("geometry.routing_safety_margin_m")));
      Geometry union =
          zones.isEmpty()
              ? Geo.GF.createPolygon()
              : org.locationtech.jts.operation.union.UnaryUnionOp.union(zones);
      pockets = new ArrayList<>();
      for (int i = 0; i < union.getNumGeometries(); i++)
        if (union.getGeometryN(i) instanceof Polygon) {
          Polygon polygon = (Polygon) union.getGeometryN(i);
          for (int k = 0; k < polygon.getNumInteriorRing(); k++) {
            Polygon hole = Geo.GF.createPolygon(polygon.getInteriorRingN(k).getCoordinates());
            // A closed courtyard is usable only if an existing network can supply it from inside.
            boolean supplied = false;
            for (Dataset.Feature network : data.networks)
              if (hole.intersects(network.geometry)) {
                supplied = true;
                break;
              }
            if (!supplied) pockets.add(hole);
          }
        }
      if (isolatedPockets.size() > 256) isolatedPockets.clear();
      isolatedPockets.put(key, pockets);
    }
    Point point = Geo.point(port);
    for (Polygon pocket : pockets) if (pocket.covers(point)) return false;
    return true;
  }

  private Coordinate extendPastCrossings(Coordinate p, Coordinate initial, int dn) {
    double exit = p.distance(initial);
    if (exit < 1e-7) return initial;
    double ux = (initial.x - p.x) / exit, uy = (initial.y - p.y) / exit;
    // A bend at the outside port must lie beyond both the clearance corridor
    // and the mandatory straight extension of every crossing on this ray.
    for (; ; ) {
      if (Thread.currentThread().isInterrupted())
        throw new Failure("CANCELLED", "Calculation cancelled");
      Coordinate port = new Coordinate(p.x + ux * exit, p.y + uy * exit);
      double next = exit;
      LineString ray =
          Geo.line(
              p,
              new Coordinate(
                  p.x + ux * (exit + rules.queryMargin() + 10),
                  p.y + uy * (exit + rules.queryMargin() + 10)));
      for (Dataset.Obstacle obstacle : data.near(ray.getEnvelopeInternal(), rules.queryMargin())) {
        if (data.hostSibling(obstacle, p, rules)) {
          Geometry zone = obstacle.geometry.buffer(obstacle.required(dn, rules) + .05);
          Geometry hits = ray.intersection(zone);
          for (int k = 0; k < hits.getNumGeometries(); k++) {
            double a = Double.POSITIVE_INFINITY, b = 0;
            for (Coordinate c : hits.getGeometryN(k).getCoordinates()) {
              a = Math.min(a, p.distance(c));
              b = Math.max(b, p.distance(c));
            }
            if (a <= exit + 1e-6) next = Math.max(next, b + .1);
          }
          continue;
        }
        if (!obstacle.special()) continue;
        double extension = Set.of("road", "tram_tracks").contains(obstacle.type) ? 3 : 2;
        Geometry hits = ray.intersection(obstacle.geometry);
        for (int k = 0; k < hits.getNumGeometries(); k++) {
          Geometry hit = hits.getGeometryN(k);
          if (hit.isEmpty()) continue;
          double a = Double.POSITIVE_INFINITY, b = 0;
          for (Coordinate c : hit.getCoordinates()) {
            double distance = p.distance(c);
            a = Math.min(a, distance);
            b = Math.max(b, distance);
          }
          if (a <= exit + 1e-6
              || hit.distance(Geo.point(port)) < obstacle.required(dn, rules) + .05)
            next = Math.max(next, b + Math.max(extension, obstacle.required(dn, rules)) + .15);
        }
      }
      if (next <= exit + 1e-7) return port;
      exit = next;
    }
  }

  private Coordinate at(
      Coordinate p, Coordinate q, Dataset.Obstacle host, Geometry buffer, double reach, int dn) {
    double distance = p.distance(q);
    if (distance < 1e-6) return null;
    LineString inside = Geo.line(p, q);
    if (!host.geometry.covers(inside) && inside.difference(host.geometry).getLength() > 1e-6)
      return null;
    double ux = (q.x - p.x) / distance, uy = (q.y - p.y) / distance;
    LineString ray = Geo.line(p, new Coordinate(p.x + ux * reach, p.y + uy * reach));
    Geometry hit = ray.intersection(buffer);
    double exit = 0;
    for (int i = 0; i < hit.getNumGeometries(); i++) {
      Geometry part = hit.getGeometryN(i);
      if (part.distance(Geo.point(p)) < 1e-6)
        for (Coordinate point : part.getCoordinates()) exit = Math.max(exit, p.distance(point));
    }
    if (exit == 0) return null;
    Coordinate port = new Coordinate(p.x + ux * (exit + .1), p.y + uy * (exit + .1));
    port = extendPastCrossings(p, port, dn);
    return checks.segment(port, p, dn, p, null) && reachableComponent(p, port, dn) ? port : null;
  }
}
