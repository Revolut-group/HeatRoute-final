package ru.heatroute;

import java.util.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.linearref.LengthIndexedLine;

/** Local angle at every linear crossing, or at the entry into a polygon. */
final class CrossingBoundary {
  static boolean accepts(LineString route, Geometry obstacle) {
    // A cut-out passage ends exactly on the boundary only up to rounding: extend it by 0.1 mm so
    // entry and exit are found robustly.
    if (obstacle.getDimension() == 2) route = extend(route, 1e-4);
    Geometry boundary = obstacle.getDimension() == 2 ? obstacle.getBoundary() : obstacle;
    Geometry hit = route.intersection(boundary);
    if (hit.isEmpty() || hit.getDimension() > 0) return false;
    List<Coordinate> sites = new ArrayList<>();
    if (obstacle.getDimension() == 2) {
      LengthIndexedLine index = new LengthIndexedLine(route);
      Coordinate entry = null;
      double station = Double.POSITIVE_INFINITY;
      for (Coordinate p : hit.getCoordinates()) {
        double s = index.project(p);
        if (s < station) {
          station = s;
          entry = p;
        }
      }
      if (entry != null) sites.add(entry);
    } else sites.addAll(Arrays.asList(hit.getCoordinates()));
    for (Coordinate site : sites) {
      boolean found = false;
      Coordinate[] r = route.getCoordinates();
      for (int j = 1; j < r.length; j++) {
        LineSegment routePart = new LineSegment(r[j - 1], r[j]);
        if (routePart.getLength() < 1e-9 || routePart.distance(site) > 1e-5) continue;
        for (int n = 0; n < boundary.getNumGeometries(); n++) {
          Coordinate[] c = boundary.getGeometryN(n).getCoordinates();
          for (int i = 1; i < c.length; i++) {
            LineSegment edge = new LineSegment(c[i - 1], c[i]);
            if (edge.getLength() < 1e-9 || edge.distance(site) > 1e-5) continue;
            found = true;
            double cosine =
                Math.abs(
                        (r[j].x - r[j - 1].x) * (c[i].x - c[i - 1].x)
                            + (r[j].y - r[j - 1].y) * (c[i].y - c[i - 1].y))
                    / (routePart.getLength() * edge.getLength());
            if (Math.toDegrees(Math.acos(Math.min(1, cosine))) < 45 - 1e-6) return false;
          }
        }
      }
      if (!found) return false;
    }
    return !sites.isEmpty();
  }

  private static LineString extend(LineString l, double d) {
    Coordinate[] c = l.getCoordinates();
    if (c.length < 2) return l;
    c = Arrays.copyOf(c, c.length);
    double la = c[0].distance(c[1]), lb = c[c.length - 1].distance(c[c.length - 2]);
    if (la > 1e-9)
      c[0] =
          new Coordinate(c[0].x + (c[0].x - c[1].x) / la * d, c[0].y + (c[0].y - c[1].y) / la * d);
    if (lb > 1e-9)
      c[c.length - 1] =
          new Coordinate(
              c[c.length - 1].x + (c[c.length - 1].x - c[c.length - 2].x) / lb * d,
              c[c.length - 1].y + (c[c.length - 1].y - c[c.length - 2].y) / lb * d);
    return Geo.line(Arrays.asList(c));
  }
}
