package ru.heatroute;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import org.locationtech.jts.geom.Envelope;

/**
 * 1600x1000 PNG of the recommended variant for README and slides: map with legend on the left,
 * summary panel on the right.
 */
final class ReportPng {
  static final int W = 1600, H = 1000, MAP = 1060;
  private final ReportData d;
  private final Graphics2D g;
  private final ReportCanvas.Awt c;
  private final boolean rouble;

  private ReportPng(ReportData d, Graphics2D g, String family) {
    this.d = d;
    this.g = g;
    this.c = new ReportCanvas.Awt(g, family);
    rouble = new Font(family, Font.PLAIN, 12).canDisplay('₽');
  }

  static void write(ReportData d, Path file) throws IOException {
    System.setProperty("java.awt.headless", "true");
    BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(
        RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    new ReportPng(d, g, family()).draw();
    g.dispose();
    Path dest = file.toAbsolutePath();
    Files.createDirectories(dest.getParent());
    Path temp = Files.createTempFile(dest.getParent(), ".heatroute-", ".png");
    try {
      ImageIO.write(image, "png", temp.toFile());
      Json.atomicMove(temp, dest);
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  /** First installed font family with Cyrillic; the logical SansSerif font is the last resort. */
  static String family() {
    Set<String> installed =
        new HashSet<>(
            Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getAvailableFontFamilyNames(Locale.ROOT)));
    for (String f :
        new String[] {
          "PT Sans", "Helvetica Neue", "Arial", "DejaVu Sans", "Liberation Sans", "Noto Sans"
        })
      if (installed.contains(f) && new Font(f, Font.PLAIN, 12).canDisplayUpTo("Трассы Ду") < 0)
        return f;
    return Font.SANS_SERIF;
  }

  private String t(String s) {
    return rouble ? s : s.replace("₽", "руб.");
  }

  private void text(String s, double x, double y, float size, String color, boolean bold) {
    c.text(t(s), x, y, size, color, bold, "start", null);
  }

  private void right(String s, double x, double y, float size, String color, boolean bold) {
    c.text(t(s), x, y, size, color, bold, "end", null);
  }

  private void fill(Shape s, String color) {
    g.setPaint(Color.decode(color));
    g.fill(s);
  }

  private void stroke(Shape s, String color, float w) {
    g.setPaint(Color.decode(color));
    g.setStroke(new BasicStroke(w));
    g.draw(s);
  }

  private void draw() {
    ReportData.Variant v = d.variants.get(0);
    // map
    fill(new Rectangle(0, 0, MAP, H), ReportMap.BG);
    Envelope focus = ReportMap.focus(d);
    ReportMap.Projection proj = new ReportMap.Projection(focus, MAP, H, 24);
    Shape clip = g.getClip();
    g.setClip(0, 0, MAP, H);
    new ReportMap(d, proj, c, proj.world(MAP, H)).draw(List.of(v));
    north(24, 24, ReportMap.northAngle(focus));
    scale(76, 30, 1 / proj.scale);
    legend(proj, v);
    g.setClip(clip);
    panel(v);
  }

  private void card(double x, double y, double w, double h) {
    RoundRectangle2D r = new RoundRectangle2D.Double(x, y, w, h, 14, 14);
    g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.93f));
    fill(r, "#ffffff");
    g.setComposite(AlphaComposite.SrcOver);
    stroke(r, "#e6e3dc", 1);
  }

  private void north(double x, double y, double angle) {
    card(x, y, 42, 58);
    AffineTransform old = g.getTransform();
    g.translate(x + 21, y + 24);
    g.rotate(Math.toRadians(angle));
    fill(ReportCanvas.polygon(0, -16, 8, 8, 0, 3, -8, 8), "#1f2328");
    fill(ReportCanvas.polygon(0, -16, 0, 3, -8, 8), "#6b7280");
    g.setTransform(old);
    c.text("С", x + 21, y + 52, 12, "#1f2328", true, "middle", null);
  }

  private void scale(double x, double y, double metresPerPixel) {
    double len = ReportMap.niceLength(metresPerPixel, 150), px = len / metresPerPixel;
    card(x, y - 6, px + 90, 40);
    g.setPaint(Color.decode("#1f2328"));
    g.setStroke(new BasicStroke(2f));
    g.draw(new Line2D.Double(x + 12, y + 12, x + 12, y + 20));
    g.draw(new Line2D.Double(x + 12, y + 20, x + 12 + px, y + 20));
    g.draw(new Line2D.Double(x + 12 + px, y + 12, x + 12 + px, y + 20));
    text(Report.num(len, 0) + " м", x + 20 + px, y + 22, 13, "#1f2328", true);
  }

  /** How much of the new network and the points falls into a screen rectangle. */
  private int busy(Rectangle2D r, ReportMap.Projection proj, ReportData.Variant v) {
    int n = 0;
    for (ReportData.PointRow p : v.points) if (p.p != null && r.contains(proj.p(p.p))) n += 20;
    for (Dataset.Feature s : d.data.sources)
      if (r.contains(proj.p(s.geometry.getCoordinate()))) n += 40;
    for (ReportData.Edge e : v.edges) n += 2 * samples(r, proj, e.line);
    for (Dataset.Feature f : d.data.networks) n += samples(r, proj, f.geometry);
    return n;
  }

  private static int samples(
      Rectangle2D r, ReportMap.Projection proj, org.locationtech.jts.geom.Geometry line) {
    int n = 0;
    org.locationtech.jts.geom.Coordinate[] c = line.getCoordinates();
    for (int i = 1; i < c.length; i++) {
      Point2D a = proj.p(c[i - 1]), b = proj.p(c[i]);
      int steps = (int) Math.ceil(a.distance(b) / 8) + 1;
      for (int k = 0; k <= steps; k++)
        if (r.contains(
            a.getX() + (b.getX() - a.getX()) * k / steps,
            a.getY() + (b.getY() - a.getY()) * k / steps)) n++;
    }
    return n;
  }

  /** Legend card in the map corner that hides the least of the network. */
  private void legend(ReportMap.Projection proj, ReportData.Variant v) {
    List<String> rows =
        new ArrayList<>(
            List.of(
                "source",
                "point",
                "pipe",
                "special",
                "chamber",
                "tie",
                "tech",
                "existing",
                "building"));
    Set<String> present = d.restrictionIds().keySet();
    for (String type : ReportMap.LAYERS)
      if (!type.equals("building") && present.contains(type)) rows.add(type);
    double rowH = 21, h = 38 + rows.size() * rowH, w = 300;
    double[][] corners = {{20, H - 20 - h}, {MAP - 20 - w, H - 20 - h}, {MAP - 20 - w, 20}};
    double x = corners[0][0], y = corners[0][1];
    int least = Integer.MAX_VALUE;
    for (double[] corner : corners) {
      int b = busy(new Rectangle2D.Double(corner[0], corner[1], w, h), proj, v);
      if (b < least) {
        least = b;
        x = corner[0];
        y = corner[1];
      }
    }
    card(x, y, w, h);
    text("Легенда", x + 14, y + 24, 14, "#1f2328", true);
    double ry = y + 36;
    for (String r : rows) {
      double sx = x + 14, sy = ry + rowH / 2, cx = sx + 17;
      String label;
      switch (r) {
        case "source":
          fill(new RoundRectangle2D.Double(cx - 8, sy - 8, 16, 16, 4, 4), "#1f2328");
          fill(
              ReportCanvas.polygon(
                  cx + 1.5, sy - 6, cx - 3.5, sy + 1, cx - 0.2, sy + 1, cx - 1.5, sy + 6, cx + 3.5,
                  sy - 1, cx + 0.2, sy - 1),
              "#ffd23f");
          label = "Источник теплоснабжения";
          break;
        case "point":
          fill(new Ellipse2D.Double(cx - 8, sy - 8, 16, 16), "#1f2328");
          c.text("7", cx, sy + 3.4, 9.5f, "#ffffff", true, "middle", null);
          label = "Точка подключения ОКС (номер)";
          break;
        case "pipe":
          g.setPaint(Color.decode(ReportMap.NEW));
          g.setStroke(new BasicStroke(ReportMap.width(100)));
          g.draw(new Line2D.Double(sx, sy - 4, sx + 34, sy - 4));
          g.setStroke(new BasicStroke(ReportMap.width(400)));
          g.draw(new Line2D.Double(sx, sy + 4, sx + 34, sy + 4));
          label = "Новая сеть, толщина ~ Ду (100 / 400)";
          break;
        case "special":
          g.setPaint(Color.decode(ReportMap.SPECIAL));
          g.setStroke(new BasicStroke(11));
          g.draw(new Line2D.Double(sx, sy, sx + 34, sy));
          g.setPaint(Color.decode(ReportMap.NEW));
          g.setStroke(new BasicStroke(4));
          g.draw(new Line2D.Double(sx, sy, sx + 34, sy));
          label = "Спецпрокладка, K > 1";
          break;
        case "chamber":
          c.circle(cx, sy, 4.4, "#ffffff", ReportMap.NEW, 2.2f);
          label = "Новая камера";
          break;
        case "tie":
          c.path(
              ReportCanvas.polygon(cx, sy - 7.5, cx + 7.5, sy, cx, sy + 7.5, cx - 7.5, sy),
              ReportMap.NEW,
              "#ffffff",
              1.8f,
              null,
              1,
              null);
          label = "Врезка в сущ. сеть (В1, В2…)";
          break;
        case "tech":
          c.circle(cx, sy, 2.6, ReportMap.INK, "#ffffff", 1f);
          label = "Технический узел";
          break;
        case "existing":
          g.setPaint(Color.decode(ReportMap.EXISTING));
          g.setStroke(new BasicStroke(3));
          g.draw(new Line2D.Double(sx, sy, sx + 34, sy));
          c.path(
              new Rectangle2D.Double(cx - 4, sy - 4, 8, 8),
              ReportMap.EXISTING,
              "#ffffff",
              1.2f,
              null,
              1,
              null);
          label = "Существующая сеть и камеры";
          break;
        case "building":
          c.path(
              new Rectangle2D.Double(sx + 1, sy - 6, 15, 12),
              "#d9d3c9",
              "#b7ae9f",
              1,
              null,
              1,
              null);
          c.path(
              new Rectangle2D.Double(sx + 19, sy - 6, 15, 12),
              "#f3dcc2",
              "#c98b4f",
              1,
              null,
              1,
              null);
          label = "ОКС / ОКС с точкой подключения";
          break;
        default:
          {
            String[] s = ReportMap.style(r, false);
            if (s[0] == null) c.line(sx, sy, sx + 34, sy, s[1], 2);
            else c.path(new Rectangle2D.Double(sx, sy - 6, 34, 12), s[0], s[1], 1, null, 1, null);
            label = ReportData.typeName(r);
          }
      }
      text(label, x + 58, sy + 4.5, 12.5f, "#1f2328", false);
      ry += rowH;
    }
  }

  private void panel(ReportData.Variant v) {
    fill(new Rectangle(MAP, 0, W - MAP, H), "#ffffff");
    g.setPaint(Color.decode("#e6e3dc"));
    g.fill(new Rectangle(MAP, 0, 1, H));
    double x = MAP + 30, r = W - 30, y = 44;
    text(
        (Report.VERSION + " · отчёт о расчёте").toUpperCase(Locale.ROOT),
        x,
        y,
        11,
        "#646b75",
        false);
    text("Трассы подключения к тепловым сетям", x, y + 30, 22, "#1f2328", true);
    text(ellipsis(d.title, 64), x, y + 52, 13, "#646b75", false);
    y += 70;
    // badge
    String badge = Report.variantName(v) + " — рекомендуемый";
    double bw = c.width(badge, 13, true) + 24;
    fill(new RoundRectangle2D.Double(x, y, bw, 26, 26, 26), "#e9f6ec");
    text(badge, x + 12, y + 18, 13, "#1a7f37", true);
    // S
    y += 50;
    text("Итоговый показатель S", x, y, 12.5f, "#646b75", false);
    text(Report.num(v.score, 3), x - 2, y + 50, 50, "#1f2328", true);
    double cp = v.costPart(d), lp = v.lengthPart(d);
    double sx = x + 200, sw = r - sx;
    text("0,7·C / 25 млн ₽", sx, y + 16, 12, "#646b75", false);
    right(Report.num(cp, 3), r, y + 16, 13, "#1f2328", true);
    text("0,3·L / 100 м", sx, y + 36, 12, "#646b75", false);
    right(Report.num(lp, 3), r, y + 36, 13, "#1f2328", true);
    double split = sw * cp / (cp + lp);
    fill(new RoundRectangle2D.Double(sx, y + 46, sw, 9, 9, 9), "#2d5b8c");
    fill(new RoundRectangle2D.Double(sx, y + 46, split, 9, 9, 9), ReportMap.NEW);
    fill(new Rectangle2D.Double(sx + split - 4, y + 46, 4, 9), ReportMap.NEW);
    // KPI tiles
    y += 76;
    int connected = v.connectedCount();
    double tw = (r - x - 16) / 3;
    tile(
        x,
        y,
        tw,
        "Подключено",
        connected + " из " + v.points.size(),
        connected == v.points.size() ? "штраф 0" : "штраф " + Report.millions(v.penalty),
        connected == v.points.size() ? "#1a7f37" : "#b42318");
    tile(
        x + tw + 8,
        y,
        tw,
        "Длина новой сети L",
        Report.num(v.length, 0) + " м",
        "спецпрокладка " + Report.num(v.specialLength, 0) + " м",
        "#1f2328");
    tile(
        x + 2 * (tw + 8),
        y,
        tw,
        "Полная стоимость C",
        Report.num(v.cost / 1e6, 1) + " млн ₽",
        v.chamberCount + " камер",
        "#1f2328");
    // totals
    y += 96;
    text("Итоги варианта " + v.id.replaceAll("\\D+", ""), x, y, 15, "#1f2328", true);
    y += 10;
    double pipes = v.pipeBase + v.specialExtra + v.depthExtra;
    y = line(x, r, y, "#d1242f", "Строительство трубопроводов", Report.money(pipes), false);
    y =
        line(
            x,
            r,
            y,
            "#f5a524",
            "  в т. ч. надбавка спецпрокладки",
            Report.money(v.specialExtra),
            true);
    if (v.depth())
      y =
          line(
              x,
              r,
              y,
              "#f5a524",
              "  в т. ч. надбавка за глубину, "
                  + Report.num(v.minDepth, 2)
                  + "–"
                  + Report.num(v.maxDepth, 2)
                  + " м",
              Report.money(v.depthExtra),
              true);
    y =
        line(
            x,
            r,
            y,
            "#6d4bd8",
            "Камеры (" + v.chamberCount + " шт., врезок в сеть: " + Report.tieInChambers(v) + ")",
            Report.money(v.chambers),
            false);
    y =
        line(
            x,
            r,
            y,
            "#0e7490",
            "Присоединения к сущ. камерам (" + v.tieCount + ")",
            Report.money(v.tieCost),
            false);
    y = line(x, r, y, "#57534e", "Штраф за неподключение", Report.money(v.penalty), false);
    g.setPaint(Color.decode("#1f2328"));
    g.fill(new Rectangle2D.Double(x, y + 4, r - x, 2));
    y += 24;
    text("Полная стоимость C", x, y, 14, "#1f2328", true);
    right(Report.money(v.cost), r, y, 14, "#1f2328", true);
    // cost structure bar
    y += 16;
    double[] parts = {v.pipeBase, v.specialExtra + v.depthExtra, v.chambers, v.tieCost, v.penalty};
    String[] colors = {"#d1242f", "#f5a524", "#6d4bd8", "#0e7490", "#57534e"};
    double at = x, total = Math.max(1, v.cost);
    Shape old = g.getClip();
    g.setClip(new RoundRectangle2D.Double(x, y, r - x, 12, 6, 6));
    for (int i = 0; i < parts.length; i++) {
      double w = (r - x) * parts[i] / total;
      fill(new Rectangle2D.Double(at, y, w + 0.5, 12), colors[i]);
      at += w;
    }
    g.setClip(old);
    // verification
    y += 28;
    boolean ok = d.valid();
    RoundRectangle2D box = new RoundRectangle2D.Double(x, y, r - x, 58, 12, 12);
    fill(box, ok ? "#e9f6ec" : "#fdecea");
    stroke(box, ok ? "#b7e1c1" : "#f5b5ae", 1);
    check(x + 22, y + 21, ok);
    text(
        ok ? "Независимая проверка пройдена" : "Независимая проверка не пройдена",
        x + 40,
        y + 26,
        14.5f,
        ok ? "#1a7f37" : "#b42318",
        true);
    Double margin = d.minimumMargin(), vs = d.verifiedScore(v.id);
    String detail =
        "ошибок: "
            + d.errorCount()
            + (margin != null ? " · мин. запас отступа " + Report.num(margin, 3) + " м" : "")
            + (vs != null
                ? " · S " + (Math.abs(vs - v.score) < 1e-9 ? "совпадает" : "расходится")
                : "");
    text(detail, x + 40, y + 46, 12.5f, "#1f2328", false);
    // comparison
    y += 90;
    text("Сравнение вариантов", x, y, 15, "#1f2328", true);
    y += 22;
    double[] cols = {x, x + 120, x + 205, x + 305, x + 385};
    String[] heads = {"", "S", "C, млн ₽", "L, м", "Подключено"};
    for (int i = 0; i < heads.length; i++) {
      if (i == 0) text(heads[i], cols[i], y, 11.5f, "#646b75", true);
      else right(heads[i], i + 1 < cols.length ? cols[i + 1] - 16 : r, y, 11.5f, "#646b75", true);
    }
    for (ReportData.Variant o : d.variants) {
      y += 6;
      g.setPaint(Color.decode("#e6e3dc"));
      g.fill(new Rectangle2D.Double(x, y, r - x, 1));
      y += 18;
      boolean me = o == v;
      String color = me ? "#1f2328" : "#646b75";
      text(Report.variantName(o) + (me ? " ★" : ""), cols[0], y, 13, color, me);
      right(Report.num(o.score, 3), cols[2] - 16, y, 13, color, true);
      right(Report.num(o.cost / 1e6, 1), cols[3] - 16, y, 13, color, me);
      right(Report.num(o.length, 0), cols[4] - 16, y, 13, color, me);
      right(o.connectedCount() + "/" + o.points.size(), r, y, 13, color, me);
    }
    // connection points, two columns
    y += 44;
    text("Точки подключения", x, y, 15, "#1f2328", true);
    text("№ · расход, т/ч · Ду ввода · до врезки, м · врезка", x + 150, y, 11.5f, "#646b75", false);
    int half = (v.points.size() + 1) / 2;
    double colW = (r - x - 20) / 2;
    // rows shrink when the panel above is taller (e.g. the extra depth line), so the list never
    // runs into the footer
    double step = Math.min(19, (H - 60 - (y + 22)) / Math.max(1, half - 1));
    for (int i = 0; i < v.points.size(); i++) {
      ReportData.PointRow p = v.points.get(i);
      double px = x + (i < half ? 0 : colW + 20), py = y + 22 + (i % half) * step;
      String ink = p.connected ? "#1f2328" : "#b42318";
      text(p.id, px, py, 12.5f, ink, true);
      right(
          p.flow == null ? "—" : Report.num(p.flow.doubleValue(), 2),
          px + 82,
          py,
          12.5f,
          ink,
          false);
      if (p.connected) {
        right("Ду " + p.entryDn, px + 138, py, 12.5f, ink, false);
        right(Report.num(p.pathLength, 0), px + 186, py, 12.5f, ink, false);
        right(v.tieLabels.getOrDefault(p.tieIn, "?"), px + colW, py, 12.5f, "#9f1c24", true);
      } else right("не подключена", px + colW, py, 12.5f, ink, true);
    }
    // footer
    text(
        "Офлайн: подложка нарисована из входного GeoJSON, без интернет-карт.",
        x,
        H - 40,
        11.5f,
        "#646b75",
        false);
    text(
        "Вход SHA-256 " + Report.shortHash(d.inputHash) + "… · команда report · " + Report.VERSION,
        x,
        H - 22,
        11.5f,
        "#646b75",
        false);
  }

  private void tile(double x, double y, double w, String k, String v, String sub, String color) {
    RoundRectangle2D r = new RoundRectangle2D.Double(x, y, w, 72, 12, 12);
    stroke(r, "#e6e3dc", 1);
    text(k, x + 12, y + 20, 11.5f, "#646b75", false);
    float size = 21;
    while (size > 13 && c.width(t(v), size, true) > w - 22) size -= 1;
    text(v, x + 12, y + 46, size, color, true);
    text(sub, x + 12, y + 63, 11, "#646b75", false);
  }

  private double line(
      double x, double r, double y, String color, String label, String value, boolean minor) {
    y += 23;
    if (!minor) fill(new RoundRectangle2D.Double(x, y - 10, 10, 10, 3, 3), color);
    else fill(new RoundRectangle2D.Double(x + 14, y - 9, 8, 8, 3, 3), color);
    text(
        label.trim(),
        x + (minor ? 28 : 18),
        y,
        minor ? 12.5f : 13.5f,
        minor ? "#646b75" : "#1f2328",
        false);
    right(value, r, y, minor ? 12.5f : 13.5f, minor ? "#646b75" : "#1f2328", !minor);
    return y;
  }

  private void check(double x, double y, boolean ok) {
    fill(new Ellipse2D.Double(x - 10, y - 10, 20, 20), ok ? "#1a7f37" : "#b42318");
    g.setPaint(Color.WHITE);
    g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    Path2D p = new Path2D.Double();
    if (ok) {
      p.moveTo(x - 5, y);
      p.lineTo(x - 1.5, y + 4);
      p.lineTo(x + 5.5, y - 4);
    } else {
      p.moveTo(x - 4, y - 4);
      p.lineTo(x + 4, y + 4);
      p.moveTo(x + 4, y - 4);
      p.lineTo(x - 4, y + 4);
    }
    g.draw(p);
  }

  private static String ellipsis(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max - 1) + "…";
  }
}
