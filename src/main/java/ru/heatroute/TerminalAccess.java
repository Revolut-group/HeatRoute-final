package ru.heatroute;

import java.util.*;
import org.locationtech.jts.geom.*;

final class TerminalAccess {
  static boolean singleEntry(LineString suffix, Coordinate terminal, Geometry polygon) {
    Geometry inside = suffix.intersection(polygon);
    int positive = 0;
    for (int i = 0; i < inside.getNumGeometries(); i++)
      if (inside.getGeometryN(i).getLength() > 1e-7) positive++;
    if (positive != 1 || inside.distance(Geo.point(terminal)) > 1e-6) return false;
    Geometry boundary = suffix.intersection(polygon.getBoundary());
    if (boundary.isEmpty()) return false;
    Coordinate first = boundary.getCoordinate();
    for (Coordinate p : boundary.getCoordinates()) if (p.distance(first) > 1e-5) return false;
    return true;
  }

  static boolean accepts(LineString suffix, Coordinate terminal, Geometry polygon, Rules rules) {
    return rules.flexibleEntrances()
        ? singleEntry(suffix, terminal, polygon)
        : nearest(suffix, terminal, polygon);
  }

  static boolean nearest(LineString suffix, Coordinate terminal, Geometry polygon) {
    Geometry boundary = polygon.getBoundary();
    double shortest = boundary.distance(Geo.point(terminal));
    Geometry hit = suffix.intersection(boundary);
    if (hit.isEmpty()) return shortest < 1e-5;
    double found = Double.POSITIVE_INFINITY;
    for (Coordinate p : hit.getCoordinates()) found = Math.min(found, p.distance(terminal));
    for (Coordinate p : hit.getCoordinates())
      if (p.distance(terminal) > shortest + 1e-4) return false;
    return found <= shortest + 1e-4;
  }

  static List<Coordinate> ports(Coordinate p, List<Dataset.Obstacle> hosts, int dn, Rules rules) {
    TreeMap<String, Coordinate> ports = new TreeMap<>();
    for (Dataset.Obstacle host : hosts) {
      Geometry boundary = host.geometry.getBoundary();
      double nearest = boundary.distance(Geo.point(p));
      for (int n = 0; n < boundary.getNumGeometries(); n++) {
        Coordinate[] ring = boundary.getGeometryN(n).getCoordinates();
        for (int i = 1; i < ring.length; i++) {
          LineSegment segment = new LineSegment(ring[i - 1], ring[i]);
          Coordinate q = segment.closestPoint(p);
          double distance = p.distance(q);
          if (distance > nearest + 1e-5) continue;
          double extension =
              host.required(dn, rules) + rules.number("geometry.routing_safety_margin_m") + .15;
          if (distance > 1e-6) {
            Coordinate port =
                new Coordinate(
                    q.x + (q.x - p.x) / distance * extension,
                    q.y + (q.y - p.y) / distance * extension);
            double reach = host.geometry.getEnvelopeInternal().getDiameter() * 2 + extension * 4;
            LineString ray =
                Geo.line(
                    p,
                    new Coordinate(
                        p.x + (q.x - p.x) / distance * reach,
                        p.y + (q.y - p.y) / distance * reach));
            Geometry hit = ray.intersection(host.geometry.buffer(host.required(dn, rules) + .05));
            double exit = 0;
            for (int part = 0; part < hit.getNumGeometries(); part++) {
              Geometry interval = hit.getGeometryN(part);
              if (interval.distance(Geo.point(p)) < 1e-6)
                for (Coordinate point : interval.getCoordinates())
                  exit = Math.max(exit, p.distance(point));
            }
            if (exit > 0)
              port =
                  new Coordinate(
                      p.x + (q.x - p.x) / distance * (exit + .1),
                      p.y + (q.y - p.y) / distance * (exit + .1));
            ports.put(Geo.key(port), port);
          } else {
            double length = segment.getLength();
            if (length < 1e-8) continue;
            for (int sign : new int[] {-1, 1}) {
              Coordinate port =
                  new Coordinate(
                      p.x + sign * (ring[i].y - ring[i - 1].y) / length * extension,
                      p.y - sign * (ring[i].x - ring[i - 1].x) / length * extension);
              if (!host.covers(Geo.point(port))) ports.put(Geo.key(port), port);
            }
          }
        }
      }
    }
    return new ArrayList<>(ports.values());
  }
}
