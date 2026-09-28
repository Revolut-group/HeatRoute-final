package ru.heatroute;

import java.awt.Shape;
import java.awt.geom.*;
import java.util.*;
import java.util.List;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.geom.Polygon;

/**
 * The map of the report, drawn from the input only (no online tiles): restrictions, existing and
 * new network.
 */
final class ReportMap {
  static final String BG = "#f2efe9",
      NEW = "#d1242f",
      NEW_DARK = "#9f1c24",
      SPECIAL = "#f5a524",
      EXISTING = "#2d5b8c",
      INK = "#1f2328";

  /** Metres (EPSG:32637) to screen pixels, north up. */
  static final class Projection {
    final double minX, maxY, scale, ox, oy;

    Projection(Envelope world, double width, double height, double pad) {
      scale =
          Math.min((width - 2 * pad) / world.getWidth(), (height - 2 * pad) / world.getHeight());
      minX = world.getMinX();
      maxY = world.getMaxY();
      ox = (width - world.getWidth() * scale) / 2;
      oy = (height - world.getHeight() * scale) / 2;
    }

    double x(double wx) {
      return ox + (wx - minX) * scale;
    }

    double y(double wy) {
      return oy + (maxY - wy) * scale;
    }

    Point2D p(Coordinate c) {
      return new Point2D.Double(x(c.x), y(c.y));
    }

    Envelope world(double width, double height) {
      return new Envelope(
          minX - ox / scale,
          minX + (width - ox) / scale,
          maxY - (height - oy) / scale,
          maxY + oy / scale);
    }

    Shape shape(Geometry g) {
      Path2D p = new Path2D.Double(Path2D.WIND_EVEN_ODD);
      append(p, g);
      return p;
    }

    private void append(Path2D p, Geometry g) {
      if (g instanceof Polygon) {
        Polygon poly = (Polygon) g;
        ring(p, poly.getExteriorRing().getCoordinates(), true);
        for (int i = 0; i < poly.getNumInteriorRing(); i++)
          ring(p, poly.getInteriorRingN(i).getCoordinates(), true);
      } else if (g instanceof GeometryCollection) {
        for (int i = 0; i < g.getNumGeometries(); i++) append(p, g.getGeometryN(i));
      } else if (g instanceof Point) {
        Point2D c = p(g.getCoordinate());
        p.moveTo(c.getX(), c.getY());
        p.lineTo(c.getX(), c.getY());
      } else ring(p, g.getCoordinates(), false);
    }

    private void ring(Path2D p, Coordinate[] c, boolean close) {
      if (c.length == 0) return;
      p.moveTo(x(c[0].x), y(c[0].y));
      for (int i = 1; i < c.length; i++) p.lineTo(x(c[i].x), y(c[i].y));
      if (close) p.closePath();
    }
  }

  /** Stroke width of a new pipe in screen pixels: grows with DN so trunks read as trunks. */
  static float width(int dn) {
    return (float) Math.min(9, 1.6 + dn * 0.0135);
  }

  static float existingWidth(int dn) {
    return (float) Math.min(5, 1.2 + dn * 0.006);
  }

  /** Styling of a restriction layer: fill, stroke, stroke width. */
  static String[] style(String type, boolean host) {
    switch (type) {
      case "building":
        return host
            ? new String[] {"#f3dcc2", "#c98b4f", "1.1"}
            : new String[] {"#d9d3c9", "#b7ae9f", "0.8"};
      case "water":
        return new String[] {"#b7d5ea", "#86b3d6", "0.8"};
      case "railway":
        return new String[] {"hatch", "#7d766b", "0.9"};
      case "road":
        return new String[] {"#ffffff", "#cfc6b6", "0.8"};
      case "tram_tracks":
        return new String[] {"#ddd1ea", "#9d86b8", "0.9"};
      case "gas_pipeline":
        return new String[] {null, "#d4a017", "2"};
      case "power_cable":
        return new String[] {null, "#8e44ad", "2"};
      case "heat_network":
        return new String[] {null, "#5b8bbf", "2"};
      default:
        return new String[] {"#f4d6d6", "#d68a8a", "0.8"};
    }
  }

  static final List<String> LAYERS =
      List.of(
          "water",
          "road",
          "tram_tracks",
          "railway",
          "metro",
          "prohibited_site",
          "gas_pipeline",
          "power_cable",
          "heat_network",
          "building");

  /**
   * Bounds of the area of interest: connection points, new networks of all variants and the source.
   */
  static Envelope focus(ReportData d) {
    Envelope e = new Envelope();
    for (Dataset.Demand demand : d.data.demands)
      e.expandToInclude(demand.terminals.get(0).geometry.getCoordinate());
    for (ReportData.Variant v : d.variants)
      for (ReportData.Edge edge : v.edges) e.expandToInclude(edge.line.getEnvelopeInternal());
    if (!e.isNull()) {
      double reach = Math.max(300, Math.max(e.getWidth(), e.getHeight()) * 0.6);
      for (Dataset.Feature s : d.data.sources)
        if (e.distance(s.geometry.getEnvelopeInternal()) <= reach)
          e.expandToInclude(s.geometry.getEnvelopeInternal());
    }
    if (e.isNull())
      for (Dataset.Feature f : d.data.features.values())
        e.expandToInclude(f.geometry.getEnvelopeInternal());
    double margin = Math.max(40, Math.max(e.getWidth(), e.getHeight()) * 0.06);
    e.expandBy(margin);
    return e;
  }

  private final ReportData d;
  private final Projection proj;
  private final ReportCanvas c;
  private final Envelope view;

  ReportMap(ReportData d, Projection proj, ReportCanvas c, Envelope view) {
    this.d = d;
    this.proj = proj;
    this.c = c;
    this.view = view;
  }

  /**
   * Draw background, existing network and the given variants (each in its own switchable group).
   */
  void draw(List<ReportData.Variant> variants) {
    background();
    existing();
    for (ReportData.Variant v : variants) variant(v);
    sources();
  }

  private void background() {
    Map<String, List<Dataset.Obstacle>> layers = new TreeMap<>();
    for (Dataset.Obstacle o : d.data.near(view, 0)) {
      if (!d.background(o)) continue;
      String layer = LAYERS.contains(o.type) ? o.type : "prohibited_site";
      layers.computeIfAbsent(layer, k -> new ArrayList<>()).add(o);
    }
    double tolerance = 0.35 / proj.scale;
    for (String layer : LAYERS) {
      List<Dataset.Obstacle> list = layers.get(layer);
      if (list == null) continue;
      c.group("layer-" + layer, "bg", null);
      for (Dataset.Obstacle o : list) {
        boolean host = o.building() && d.hostIds.contains(o.id);
        String[] s = style(o.type, host);
        Geometry g =
            o.geometry.getDimension() == 2
                ? org.locationtech.jts.simplify.TopologyPreservingSimplifier.simplify(
                    o.geometry, tolerance)
                : o.geometry;
        c.path(
            proj.shape(g),
            o.geometry.getDimension() == 2 ? s[0] : null,
            s[1],
            Float.parseFloat(s[2]),
            null,
            1,
            null);
      }
      c.end();
    }
  }

  private void existing() {
    c.group("existing", null, null);
    for (Dataset.Feature f : d.data.networks) {
      if (!f.geometry.getEnvelopeInternal().intersects(view)) continue;
      int dn = f.dn();
      c.path(proj.shape(f.geometry), null, "#ffffff", existingWidth(dn) + 2f, null, 0.9, null);
      c.path(proj.shape(f.geometry), null, EXISTING, existingWidth(dn), null, 0.9, null);
      c.hit(proj.shape(f.geometry), 10, "Существующая тепловая сеть " + f.id + "\nДу " + dn);
    }
    for (Dataset.Feature f : d.data.chambers) {
      Point2D p = proj.p(f.geometry.getCoordinate());
      if (!view.contains(f.geometry.getCoordinate())) continue;
      c.marker(p.getX(), p.getY(), 0, "Существующая камера " + f.id);
      c.path(
          new Rectangle2D.Double(-3.8, -3.8, 7.6, 7.6), EXISTING, "#ffffff", 1.2f, null, 1, null);
      c.endMarker();
    }
    c.end();
  }

  private void sources() {
    for (Dataset.Feature f : d.data.sources) {
      if (!view.contains(f.geometry.getCoordinate())) continue;
      Point2D p = proj.p(f.geometry.getCoordinate());
      String name = f.properties.path("name").asText("Источник");
      c.marker(p.getX(), p.getY(), 0, "Источник теплоснабжения\n" + name);
      c.path(
          new RoundRectangle2D.Double(-8, -8, 16, 16, 4, 4), INK, "#ffffff", 1.6f, null, 1, null);
      c.path(
          ReportCanvas.polygon(1.5, -6, -3.5, 1, -0.2, 1, -1.5, 6, 3.5, -1, 0.2, -1),
          "#ffd23f",
          null,
          0,
          null,
          1,
          null);
      c.text(name, 12, 4, 12, INK, true, "start", "#ffffff");
      c.endMarker();
    }
  }

  /**
   * Logical edge = chain of heat_network segments joined at technical nodes (same pipe between
   * chambers).
   */
  private static List<List<ReportData.Edge>> chains(ReportData.Variant v) {
    Set<String> technical = new HashSet<>();
    for (ReportData.Node n : v.nodes) if (n.kind.equals("technical")) technical.add(n.id);
    Map<String, ReportData.Edge> byFrom = new HashMap<>();
    for (ReportData.Edge e : v.edges) byFrom.put(e.from, e);
    List<List<ReportData.Edge>> out = new ArrayList<>();
    for (ReportData.Edge e : v.edges) {
      if (technical.contains(e.from)) continue;
      List<ReportData.Edge> chain = new ArrayList<>();
      chain.add(e);
      ReportData.Edge at = e;
      while (technical.contains(at.to) && byFrom.containsKey(at.to) && chain.size() < 1000) {
        at = byFrom.get(at.to);
        chain.add(at);
      }
      out.add(chain);
    }
    return out;
  }

  private void variant(ReportData.Variant v) {
    c.group("map-" + v.id, "vg", v.id);
    // 1. special sections: amber casing under the pipe
    for (ReportData.Edge e : v.edges)
      if (e.special) c.path(proj.shape(e.line), null, SPECIAL, width(e.dn) + 7f, null, 1, null);
    // 2. pipes with a thin light casing so they separate from the background
    for (ReportData.Edge e : v.edges)
      c.path(proj.shape(e.line), null, "#ffffff", width(e.dn) + 2.2f, null, 0.85, null);
    for (ReportData.Edge e : v.edges)
      c.path(proj.shape(e.line), null, NEW, width(e.dn), null, 1, null);
    for (ReportData.Edge e : v.edges)
      c.hit(proj.shape(e.line), Math.max(12, width(e.dn) + 8), edgeTip(e));
    // 3. nodes
    for (ReportData.Node n : v.nodes) {
      Point2D p = proj.p(n.p);
      switch (n.kind) {
        case "technical":
          c.marker(
              p.getX(),
              p.getY(),
              0,
              "Технический узел " + n.id + "\nсмена способа прокладки (base ↔ special)");
          c.circle(0, 0, 2.4, INK, "#ffffff", 1f);
          break;
        case "tie_in":
          c.marker(
              p.getX(),
              p.getY(),
              0,
              "Врезка "
                  + v.tieLabels.getOrDefault(n.id, "")
                  + " в существующую сеть: новая камера "
                  + n.id
                  + "\nДу "
                  + n.dn
                  + " · "
                  + Report.money(n.cost));
          c.path(
              ReportCanvas.polygon(0, -7.5, 7.5, 0, 0, 7.5, -7.5, 0),
              NEW,
              "#ffffff",
              1.8f,
              null,
              1,
              null);
          c.text(
              v.tieLabels.getOrDefault(n.id, ""), 10, -6, 11, NEW_DARK, true, "start", "#ffffff");
          break;
        default:
          c.marker(
              p.getX(),
              p.getY(),
              0,
              "Новая камера " + n.id + "\nДу " + n.dn + " · " + Report.money(n.cost));
          c.circle(0, 0, 4.4, "#ffffff", NEW, 2.2f);
      }
      c.endMarker();
    }
    for (String root : v.roots) {
      if (!d.existingChamberIds.contains(root)) continue;
      Dataset.Feature f = d.data.features.get(root);
      Point2D p = proj.p(f.geometry.getCoordinate());
      c.marker(
          p.getX(),
          p.getY(),
          0,
          "Врезка "
              + v.tieLabels.get(root)
              + " в существующую камеру "
              + root
              + "\n"
              + Report.money(d.tieInRub)
              + " за каждое присоединение");
      c.circle(0, 0, 8, null, NEW, 2.4f);
      c.text(v.tieLabels.get(root), 11, -7, 11, NEW_DARK, true, "start", "#ffffff");
      c.endMarker();
    }
    // 4. connection points (variant-specific tooltips) and DN labels
    List<Rectangle2D> taken = new ArrayList<>();
    for (ReportData.PointRow row : v.points) {
      if (row.p == null) continue;
      Point2D p = proj.p(row.p);
      double r = Math.max(9, 3.1 * row.id.length() + 3.5);
      taken.add(new Rectangle2D.Double(p.getX() - r - 2, p.getY() - r - 2, 2 * r + 4, 2 * r + 4));
      c.marker(p.getX(), p.getY(), 0, pointTip(row, v));
      if (row.connected) c.circle(0, 0, r, INK, "#ffffff", 1.8f);
      else c.circle(0, 0, r, "#ffffff", NEW, 2.4f);
      c.text(
          row.id,
          0,
          3.4f,
          row.id.length() > 2 ? 8.5f : 9.5f,
          row.connected ? "#ffffff" : NEW,
          true,
          "middle",
          null);
      c.endMarker();
    }
    labels(v, taken);
    c.end();
  }

  private void labels(ReportData.Variant v, List<Rectangle2D> taken) {
    List<List<ReportData.Edge>> chains = chains(v);
    chains.sort(
        Comparator.comparingInt((List<ReportData.Edge> ch) -> -ch.get(0).dn)
            .thenComparingDouble(ch -> -ch.stream().mapToDouble(e -> e.length).sum()));
    for (List<ReportData.Edge> chain : chains) {
      List<Point2D> pts = new ArrayList<>();
      for (ReportData.Edge e : chain)
        for (Coordinate k : e.line.getCoordinates()) {
          Point2D p = proj.p(k);
          if (pts.isEmpty() || pts.get(pts.size() - 1).distance(p) > 1e-6) pts.add(p);
        }
      double total = 0;
      for (int i = 1; i < pts.size(); i++) total += pts.get(i - 1).distance(pts.get(i));
      String label = "Ду " + chain.get(0).dn;
      float size = 10;
      double w = label.length() * size * 0.6 + 6, h = size + 4;
      if (total < w + 24) continue;
      // the middle of the chain first, then other positions along it
      for (double at : new double[] {0.5, 0.33, 0.67, 0.2, 0.8})
        if (placeLabel(pts, total * at, label, size, w, h, width(chain.get(0).dn), taken)) break;
    }
  }

  private boolean placeLabel(
      List<Point2D> pts,
      double target,
      String label,
      float size,
      double w,
      double h,
      double lineWidth,
      List<Rectangle2D> taken) {
    double run = 0;
    for (int i = 1; i < pts.size(); i++) {
      Point2D a = pts.get(i - 1), b = pts.get(i);
      double seg = a.distance(b);
      if (run + seg < target || seg < 1e-9) {
        run += seg;
        continue;
      }
      double t = (target - run) / seg,
          x = a.getX() + (b.getX() - a.getX()) * t,
          y = a.getY() + (b.getY() - a.getY()) * t;
      double angle = Math.toDegrees(Math.atan2(b.getY() - a.getY(), b.getX() - a.getX()));
      if (angle > 90) angle -= 180;
      if (angle < -90) angle += 180;
      double off = lineWidth / 2 + 3 + h / 2, rad = Math.toRadians(angle);
      double cx = x + Math.sin(rad) * off, cy = y - Math.cos(rad) * off;
      double bw = Math.abs(Math.cos(rad)) * w + Math.abs(Math.sin(rad)) * h,
          bh = Math.abs(Math.sin(rad)) * w + Math.abs(Math.cos(rad)) * h;
      Rectangle2D box = new Rectangle2D.Double(cx - bw / 2, cy - bh / 2, bw, bh);
      for (Rectangle2D r : taken) if (r.intersects(box)) return false;
      taken.add(box);
      c.marker(x, y, angle, null, "dn");
      c.text(label, 0, (float) (-off + size * 0.36), size, NEW_DARK, true, "middle", "#ffffff");
      c.endMarker();
      return true;
    }
    return false;
  }

  static String edgeTip(ReportData.Edge e) {
    StringBuilder s = new StringBuilder();
    s.append("Участок ").append(e.id).append(" · Ду ").append(e.dn).append('\n');
    s.append("Расход: ").append(Report.num(e.flow, 2)).append(" т/ч\n");
    s.append("Длина: ").append(Report.num(e.length, 1)).append(" м\n");
    s.append("Стоимость: ").append(Report.money(e.cost)).append('\n');
    boolean depth = !Double.isNaN(e.depthStart);
    double kd = depth ? DepthProfile.factor(e.depthStart, e.depthEnd).doubleValue() : 1;
    if (e.special)
      s.append("Прокладка: special, K = ")
          .append(Report.num(e.factor() / kd, 2))
          .append(" (+")
          .append(Report.money(e.cost / kd - e.baseCost))
          .append(")");
    else s.append("Прокладка: base, K = 1");
    if (depth)
      s.append("\nГлубина верха: ")
          .append(Report.num(e.depthStart, 2))
          .append(" → ")
          .append(Report.num(e.depthEnd, 2))
          .append(" м, Kгл = ")
          .append(Report.num(kd, 3));
    s.append("\nот ").append(e.from).append(" → до ").append(e.to);
    return s.toString();
  }

  static String pointTip(ReportData.PointRow r, ReportData.Variant v) {
    StringBuilder s = new StringBuilder("Точка подключения ").append(r.id).append('\n');
    s.append("Расход: ")
        .append(r.flow == null ? "не задан" : Report.num(r.flow.doubleValue(), 2) + " т/ч")
        .append('\n');
    if (r.connected) {
      s.append("Ввод: Ду ").append(r.entryDn).append('\n');
      s.append("Длина до врезки: ").append(Report.num(r.pathLength, 1)).append(" м\n");
      s.append("Врезка: ")
          .append(v.tieLabels.getOrDefault(r.tieIn, ""))
          .append(" (")
          .append(r.tieIn)
          .append(")");
    } else s.append("Не подключена в этом варианте");
    return s.toString();
  }

  /** Angle (degrees, clockwise from screen up) of true north at the centre of the view. */
  static double northAngle(Envelope view) {
    Coordinate centre = view.centre(), ll = Geo.ll(centre), up = Geo.xy(ll.x, ll.y + 0.01);
    return Math.toDegrees(Math.atan2(up.x - centre.x, up.y - centre.y));
  }

  /** A round scale length (1-2-5 series) that fits into maxPixels at the given metres per pixel. */
  static double niceLength(double metresPerPixel, double maxPixels) {
    double best = 1;
    for (double base = 1; base <= 100000; base *= 10)
      for (double k : new double[] {1, 2, 5})
        if (base * k / metresPerPixel <= maxPixels) best = base * k;
    return best;
  }
}
