package ru.heatroute;

import java.util.*;
import org.locationtech.jts.algorithm.MinimumDiameter;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.locationtech.jts.triangulate.VoronoiDiagramBuilder;

/** Local medial-axis estimate; no global skeleton or invented normative road axis. */
final class AxisService {
  static final class Axis {
    final Coordinate vector;
    final String method;
    final Envelope window;

    Axis(Coordinate v, String m, Envelope w) {
      vector = v;
      method = m;
      window = w;
    }
  }

  static Axis estimate(Geometry polygon, Coordinate crossing) {
    double radius = Math.max(15, Math.min(80, Math.sqrt(polygon.getArea())));
    Envelope window = new Envelope(crossing);
    window.expandBy(radius);
    Geometry local = polygon.intersection(Geo.GF.toGeometry(window));
    double width = Math.max(2, 2 * local.getBoundary().distance(Geo.point(crossing)));
    double step = Math.max(.5, width / 4);
    List<Coordinate> sites = new ArrayList<>();
    for (int part = 0; part < local.getNumGeometries(); part++) {
      Geometry shape = local.getGeometryN(part);
      if (!(shape instanceof Polygon)) continue;
      Polygon p = (Polygon) shape;
      sample(p.getExteriorRing(), step, sites);
      for (int h = 0; h < p.getNumInteriorRing(); h++) sample(p.getInteriorRingN(h), step, sites);
    }
    if (sites.size() >= 4 && sites.size() < 1500) {
      try {
        VoronoiDiagramBuilder b = new VoronoiDiagramBuilder();
        b.setSites(sites);
        b.setClipEnvelope(window);
        Geometry diagram = b.getDiagram(Geo.GF);
        List<LineString> skeleton = new ArrayList<>();
        for (int i = 0; i < diagram.getNumGeometries(); i++) {
          Coordinate[] points = diagram.getGeometryN(i).getCoordinates();
          for (int j = 1; j < points.length; j++) {
            LineString edge = Geo.line(points[j - 1], points[j]);
            if (edge.getLength() < width * .1) continue;
            Coordinate mid = new LengthIndexedLine(edge).extractPoint(edge.getLength() / 2);
            if (local.contains(Geo.point(mid))
                && local.getBoundary().distance(Geo.point(mid)) > width * .2) skeleton.add(edge);
          }
        }
        skeleton.sort(
            Comparator.comparingDouble((LineString l) -> l.distance(Geo.point(crossing)))
                .thenComparing(Geo::fingerprint));
        if (!skeleton.isEmpty()) {
          LineString best = skeleton.get(0);
          Coordinate a = best.getCoordinateN(0),
              z = best.getCoordinateN(1),
              v = new Coordinate(z.x - a.x, z.y - a.y);
          boolean stable = true;
          for (LineString other : skeleton)
            if (other.distance(Geo.point(crossing)) < width * .15) {
              Coordinate x = other.getCoordinateN(0), y = other.getCoordinateN(1);
              double cosine =
                  Math.abs(v.x * (y.x - x.x) + v.y * (y.y - x.y))
                      / (Math.hypot(v.x, v.y) * x.distance(y));
              if (cosine < .9) {
                stable = false;
                break;
              }
            }
          if (stable) return new Axis(refine(local, crossing, v, width), "local_voronoi", window);
        }
      } catch (org.locationtech.jts.geom.TopologyException ignored) {
        /* Fall back without altering input topology. */
      }
    }
    Coordinate[] c = MinimumDiameter.getMinimumRectangle(local).getCoordinates();
    if (c.length < 4) return null;
    double a = c[0].distance(c[1]), b = c[1].distance(c[2]);
    if (Math.max(a, b) < 1.2 * Math.min(a, b)) return null;
    Coordinate v =
        a > b
            ? new Coordinate(c[1].x - c[0].x, c[1].y - c[0].y)
            : new Coordinate(c[2].x - c[1].x, c[2].y - c[1].y);
    return new Axis(v, "local_oriented_rectangle_fallback", window);
  }

  private static Coordinate refine(
      Geometry polygon, Coordinate point, Coordinate axis, double width) {
    Coordinate[] sides = new Coordinate[2];
    double[] nearest = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
    Geometry boundary = polygon.getBoundary();
    for (int part = 0; part < boundary.getNumGeometries(); part++) {
      Coordinate[] c = boundary.getGeometryN(part).getCoordinates();
      for (int i = 1; i < c.length; i++) {
        double dx = c[i].x - c[i - 1].x, dy = c[i].y - c[i - 1].y, len = Math.hypot(dx, dy);
        if (len < 1e-7) continue;
        double dot = (dx * axis.x + dy * axis.y) / (len * Math.hypot(axis.x, axis.y));
        if (Math.abs(dot) < .95) continue;
        double distance =
            org.locationtech.jts.algorithm.Distance.pointToSegment(point, c[i - 1], c[i]);
        if (distance > 2 * width) continue;
        double side =
            axis.x * ((c[i].y + c[i - 1].y) / 2 - point.y)
                - axis.y * ((c[i].x + c[i - 1].x) / 2 - point.x);
        int k = side < 0 ? 0 : 1;
        if (distance < nearest[k]) {
          nearest[k] = distance;
          double sign = dot < 0 ? -1 : 1;
          sides[k] = new Coordinate(sign * dx / len, sign * dy / len);
        }
      }
    }
    if (sides[0] != null
        && sides[1] != null
        && sides[0].x * sides[1].x + sides[0].y * sides[1].y > .98)
      return new Coordinate(sides[0].x + sides[1].x, sides[0].y + sides[1].y);
    return axis;
  }

  private static void sample(LineString ring, double step, List<Coordinate> out) {
    LengthIndexedLine li = new LengthIndexedLine(ring);
    for (double s = 0; s < ring.getLength(); s += step) out.add(li.extractPoint(s));
  }
}
