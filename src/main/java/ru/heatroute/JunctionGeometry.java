package ru.heatroute;

import org.locationtech.jts.geom.*;

/** Geometric primitive for the finite common part of two tube pairs at one physical junction. */
final class JunctionGeometry {
  /**
   * Tubes are polygons: the tube of a whole line and the tube of its first run differ by slivers of
   * the polygonised round caps and joins (up to about r(1 - cos 5.6°) = 2 mm at 8 segments per
   * quadrant). Measured on a clean 142° junction: 1.5e-6 m² of such slivers, all within 0.5 m of
   * the chamber. Only overlap more than 1 cm beyond the junction counts (the organisers' Q&A: 4.999
   * m against a 5 m rule must not break a solution); a real violation runs for metres.
   */
  static final double TOLERANCE_M = 0.01;

  static boolean fits(LineString a, double widthA, LineString b, double widthB, Coordinate anchor) {
    Geometry overlap = a.buffer(widthA / 2, 8).intersection(b.buffer(widthB / 2, 8));
    if (overlap.isEmpty()) return true;
    LineString ar = firstRun(a, anchor), br = firstRun(b, anchor);
    Geometry junction = ar.buffer(widthA / 2, 8).intersection(br.buffer(widthB / 2, 8));
    return overlap.difference(junction.buffer(TOLERANCE_M, 2)).getArea() < 1e-8;
  }

  private static LineString firstRun(LineString line, Coordinate anchor) {
    Coordinate[] c = Geo.simplify(line).getCoordinates();
    return c[0].distance(anchor) < c[c.length - 1].distance(anchor)
        ? Geo.line(c[0], c[1])
        : Geo.line(c[c.length - 1], c[c.length - 2]);
  }
}
