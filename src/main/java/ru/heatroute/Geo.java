package ru.heatroute;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.*;
import java.util.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.locationtech.jts.operation.valid.IsValidOp;
import org.locationtech.proj4j.*;

public final class Geo {
  public static final GeometryFactory GF = new GeometryFactory(new PrecisionModel(), 32637);
  private static final CRSFactory CRSF = new CRSFactory();

  // proj4j transforms keep scratch coordinates inside: one pair per thread (the planner is
  // parallel)
  private static final ThreadLocal<CoordinateTransform> FWD =
      ThreadLocal.withInitial(() -> transform("EPSG:4326", "EPSG:32637"));
  private static final ThreadLocal<CoordinateTransform> INV =
      ThreadLocal.withInitial(() -> transform("EPSG:32637", "EPSG:4326"));

  private static CoordinateTransform transform(String from, String to) {
    synchronized (CRSF) {
      return new CoordinateTransformFactory()
          .createTransform(CRSF.createFromName(from), CRSF.createFromName(to));
    }
  }

  public static Coordinate xy(double lon, double lat) {
    ProjCoordinate p = new ProjCoordinate();
    FWD.get().transform(new ProjCoordinate(lon, lat), p);
    return new Coordinate(p.x, p.y);
  }

  public static Coordinate ll(Coordinate c) {
    ProjCoordinate p = new ProjCoordinate();
    INV.get().transform(new ProjCoordinate(c.x, c.y), p);
    return new Coordinate(p.x, p.y);
  }

  public static Coordinate canonical(Coordinate c) {
    Coordinate p = ll(c);
    return xy(round(p.x, 12), round(p.y, 12));
  }

  public static double round(double x, int places) {
    return BigDecimal.valueOf(x).setScale(places, RoundingMode.HALF_UP).doubleValue();
  }

  public static String key(Coordinate c) {
    Coordinate p = ll(c);
    return String.format(Locale.ROOT, "%.12f,%.12f", p.x, p.y);
  }

  public static Point point(Coordinate p) {
    return GF.createPoint(p);
  }

  public static LineString line(Coordinate a, Coordinate b) {
    return GF.createLineString(new Coordinate[] {a.copy(), b.copy()});
  }

  public static LineString line(List<Coordinate> c) {
    return GF.createLineString(c.toArray(new Coordinate[0]));
  }

  public static Geometry read(JsonNode g, String id) {
    return read(g, id, null);
  }

  /**
   * notes==null: strict (any defect is INVALID_GEOMETRY). Otherwise tolerant: GeometryCollection is
   * accepted, an invalid geometry is repaired with GeometryFixer, and a
   * missing/empty/degenerate/unreadable geometry yields null; every such decision is appended to
   * notes.
   */
  public static Geometry read(JsonNode g, String id, List<String> notes) {
    try {
      if (g == null || g.isNull() || !g.isObject())
        throw new IllegalArgumentException("Geometry missing");
      Geometry out = parse(g, id, notes != null);
      IsValidOp v = new IsValidOp(out);
      if (notes != null && !degenerate(out) && !v.isValid()) {
        notes.add("Invalid geometry repaired with GeometryFixer: " + v.getValidationError());
        out = org.locationtech.jts.geom.util.GeometryFixer.fix(out);
        v = new IsValidOp(out);
      }
      if (degenerate(out) || !v.isValid())
        throw new IllegalArgumentException(
            v.getValidationError() == null
                ? "Empty/degenerate geometry"
                : v.getValidationError().toString());
      return out;
    } catch (Failure f) {
      if (notes == null) throw f;
      notes.add(f.getMessage());
      return null;
    } catch (RuntimeException ex) {
      if (notes == null) throw new Failure("INVALID_GEOMETRY", ex.getMessage(), id);
      notes.add(ex.getMessage() == null ? ex.toString() : ex.getMessage());
      return null;
    }
  }

  private static boolean degenerate(Geometry g) {
    return g.isEmpty() || (g.getDimension() == 1 && g.getLength() == 0);
  }

  private static Geometry parse(JsonNode g, String id, boolean collections) {
    JsonNode c = g.get("coordinates");
    String type = g.path("type").asText();
    switch (type) {
      case "Point":
        return point(coord(c));
      case "LineString":
        return GF.createLineString(coords(c));
      case "MultiLineString":
        LineString[] ls = new LineString[c.size()];
        for (int i = 0; i < ls.length; i++) ls[i] = GF.createLineString(coords(c.get(i)));
        return GF.createMultiLineString(ls);
      case "Polygon":
        return polygon(c);
      case "MultiPolygon":
        Polygon[] ps = new Polygon[c.size()];
        for (int i = 0; i < ps.length; i++) ps[i] = polygon(c.get(i));
        return GF.createMultiPolygon(ps);
      case "GeometryCollection":
        if (collections && g.path("geometries").isArray()) {
          Geometry[] parts = new Geometry[g.get("geometries").size()];
          for (int i = 0; i < parts.length; i++)
            parts[i] = parse(g.get("geometries").get(i), id, true);
          return GF.createGeometryCollection(parts);
        }
      default:
        throw new Failure(
            "UNSUPPORTED_GEOMETRY_FOR_RESTRICTION", "Unsupported geometry " + type, id);
    }
  }

  private static Coordinate coord(JsonNode a) {
    if (a == null || !a.isArray() || a.size() < 2 || !a.get(0).isNumber() || !a.get(1).isNumber())
      throw new IllegalArgumentException("Invalid position");
    double lon = a.get(0).doubleValue(), lat = a.get(1).doubleValue();
    if (!Double.isFinite(lon)
        || !Double.isFinite(lat)
        || lon < -180
        || lon > 180
        || lat < -90
        || lat > 90) throw new IllegalArgumentException("Position outside WGS84");
    return xy(lon, lat);
  }

  private static Coordinate[] coords(JsonNode a) {
    if (a == null || !a.isArray()) throw new IllegalArgumentException("Coordinates not an array");
    Coordinate[] c = new Coordinate[a.size()];
    for (int i = 0; i < c.length; i++) c[i] = coord(a.get(i));
    return c;
  }

  private static Polygon polygon(JsonNode a) {
    if (a.size() == 0) throw new IllegalArgumentException("Empty polygon");
    LinearRing shell = GF.createLinearRing(coords(a.get(0)));
    LinearRing[] holes = new LinearRing[a.size() - 1];
    for (int i = 1; i < a.size(); i++) holes[i - 1] = GF.createLinearRing(coords(a.get(i)));
    return GF.createPolygon(shell, holes);
  }

  public static ObjectNode json(Geometry g) {
    ObjectNode n = Json.M.createObjectNode();
    n.put("type", g.getGeometryType());
    if (g instanceof Point) n.set("coordinates", position(g.getCoordinate()));
    else if (g instanceof LineString) n.set("coordinates", positions(g.getCoordinates()));
    else if (g instanceof Polygon) n.set("coordinates", rings((Polygon) g));
    else {
      ArrayNode a = n.putArray("coordinates");
      for (int i = 0; i < g.getNumGeometries(); i++) {
        Geometry p = g.getGeometryN(i);
        a.add(p instanceof Polygon ? rings((Polygon) p) : positions(p.getCoordinates()));
      }
    }
    return n;
  }

  private static ArrayNode rings(Polygon p) {
    ArrayNode a = Json.M.createArrayNode();
    a.add(positions(p.getExteriorRing().getCoordinates()));
    for (int i = 0; i < p.getNumInteriorRing(); i++)
      a.add(positions(p.getInteriorRingN(i).getCoordinates()));
    return a;
  }

  private static ArrayNode positions(Coordinate[] c) {
    ArrayNode a = Json.M.createArrayNode();
    for (Coordinate p : c) a.add(position(p));
    return a;
  }

  public static ArrayNode position(Coordinate c) {
    Coordinate p = ll(c);
    ArrayNode a = Json.M.createArrayNode();
    a.add(BigDecimal.valueOf(p.x).setScale(12, RoundingMode.HALF_UP));
    a.add(BigDecimal.valueOf(p.y).setScale(12, RoundingMode.HALF_UP));
    return a;
  }

  public static LineString canonical(LineString l) {
    return (LineString) read(json(l), "canonical");
  }

  public static LineString sub(LineString l, double a, double b) {
    return (LineString) new LengthIndexedLine(l).extractLine(a, b);
  }

  public static double turn(Coordinate a, Coordinate b, Coordinate c) {
    double ux = b.x - a.x, uy = b.y - a.y, vx = c.x - b.x, vy = c.y - b.y;
    return Math.toDegrees(
        Math.acos(
            Math.max(
                -1, Math.min(1, (ux * vx + uy * vy) / (Math.hypot(ux, uy) * Math.hypot(vx, vy))))));
  }

  public static String fingerprint(Geometry g) {
    Geometry n = g.copy();
    n.normalize();
    return Json.hash(new org.locationtech.jts.io.WKBWriter().write(n));
  }

  public static LineString simplify(LineString l) {
    List<Coordinate> c = new ArrayList<>();
    for (Coordinate p : l.getCoordinates()) {
      if (!c.isEmpty() && c.get(c.size() - 1).distance(p) < 1e-7) continue;
      while (c.size() > 1
          && org.locationtech.jts.algorithm.Distance.pointToSegment(
                  c.get(c.size() - 1), c.get(c.size() - 2), p)
              <= 1e-6
          && turn(c.get(c.size() - 2), c.get(c.size() - 1), p) < .01) c.remove(c.size() - 1);
      c.add(p);
    }
    return line(c);
  }
}
