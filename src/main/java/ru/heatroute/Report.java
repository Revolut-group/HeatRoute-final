package ru.heatroute;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.*;
import java.util.*;
import org.locationtech.jts.geom.Envelope;

/**
 * Self-contained offline report of a calculation: HTML (SVG map, variant switcher, tooltips,
 * totals, verification, per-point table, rule interpretations) and an optional 1600x1000 PNG for
 * slides. The HTML has no external resources: no fonts, scripts, styles or map tiles are fetched.
 */
public final class Report {
  static final String VERSION = Main.USAGE.split(" - ")[0];
  static final int MAP_W = 1400;

  private Report() {}

  /** CLI/API entry point. verifyReport may be null: the verifier then runs on the saved result. */
  public static ReportData generate(
      Path input, Path result, Path verifyReport, Path html, Path png, Rules rules, String title)
      throws IOException {
    ReportData d = new ReportData(input, result, verifyReport, rules, title);
    try {
      if (d.variants.isEmpty())
        throw new Failure(
            "INVALID_ATTRIBUTE", "--result contains no variant_summary / heat_network features");
      if (html != null) write(html, html(d));
      if (png != null) ReportPng.write(d, png);
    } finally {
      d.data.close();
    }
    return d;
  }

  private static void write(Path file, String text) throws IOException {
    Path dest = file.toAbsolutePath();
    Files.createDirectories(dest.getParent());
    Path temp = Files.createTempFile(dest.getParent(), ".heatroute-", ".tmp");
    try {
      Files.write(temp, text.getBytes(StandardCharsets.UTF_8));
      Json.atomicMove(temp, dest);
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  // ---------- number formatting (Russian: 1 234 567,89) ----------

  static String num(double v, int decimals) {
    DecimalFormatSymbols s = new DecimalFormatSymbols(Locale.ROOT);
    s.setDecimalSeparator(',');
    s.setGroupingSeparator(' ');
    s.setMinusSign('−');
    DecimalFormat f =
        new DecimalFormat(decimals == 0 ? "#,##0" : "#,##0." + "0".repeat(decimals), s);
    f.setRoundingMode(java.math.RoundingMode.HALF_UP);
    return f.format(v);
  }

  static String money(double rub) {
    return num(rub, 0) + " ₽";
  }

  static String millions(double rub) {
    return num(rub / 1e6, 1) + " млн ₽";
  }

  static String variantName(ReportData.Variant v) {
    return "Вариант " + v.id.replaceAll("\\D+", "");
  }

  static String shortHash(String h) {
    return h == null || h.length() < 12 ? String.valueOf(h) : h.substring(0, 12);
  }

  // ---------- HTML ----------

  static String html(ReportData d) {
    Envelope focus = ReportMap.focus(d);
    int mapH =
        (int)
            Math.round(Math.max(600, Math.min(1600, MAP_W * focus.getHeight() / focus.getWidth())));
    ReportMap.Projection proj = new ReportMap.Projection(focus, MAP_W, mapH, 0);
    Envelope view = proj.world(MAP_W, mapH);
    view.expandBy(Math.max(view.getWidth(), view.getHeight()) * 0.5);
    ReportCanvas.Svg svg = new ReportCanvas.Svg();
    new ReportMap(d, proj, svg, view).draw(d.variants);
    String first = d.variants.get(0).id;

    StringBuilder h = new StringBuilder(svg.out.length() + 60000);
    h.append("<!DOCTYPE html>\n<html lang=\"ru\">\n<head>\n<meta charset=\"utf-8\">\n")
        .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        .append("<title>Трассы подключения — ")
        .append(esc(d.title))
        .append("</title>\n")
        .append("<style>\n")
        .append(CSS)
        .append("</style>\n</head>\n<body data-v=\"")
        .append(esc(first))
        .append("\">\n")
        .append("<div class=\"app\">\n<main class=\"map\" id=\"mapbox\">\n");
    h.append("<svg id=\"map\" viewBox=\"0 0 ")
        .append(MAP_W)
        .append(' ')
        .append(mapH)
        .append("\" preserveAspectRatio=\"xMidYMid meet\" data-s=\"")
        .append(String.format(Locale.ROOT, "%.6f", proj.scale))
        .append("\" role=\"img\" aria-label=\"Карта трасс\">\n")
        .append(
            "<defs><pattern id=\"hatch\" patternUnits=\"userSpaceOnUse\" width=\"6\" height=\"6\""
                + " patternTransform=\"rotate(45)\">")
        .append(
            "<rect width=\"6\" height=\"6\" fill=\"#d6d1c8\"/><line x1=\"0\" y1=\"0\" x2=\"0\""
                + " y2=\"6\" stroke=\"#8f887c\" stroke-width=\"1.6\"/></pattern></defs>\n")
        .append("<rect x=\"")
        .append(-MAP_W * 3)
        .append("\" y=\"")
        .append(-mapH * 3)
        .append("\" width=\"")
        .append(MAP_W * 7)
        .append("\" height=\"")
        .append(mapH * 7)
        .append("\" fill=\"")
        .append(ReportMap.BG)
        .append("\"/>\n");
    // hide the alternatives until the script switches them
    String svgBody = svg.out.toString();
    for (ReportData.Variant v : d.variants)
      if (!v.id.equals(first))
        svgBody =
            svgBody.replace(
                "<g id=\"map-" + esc(v.id) + "\" class=\"vg\"",
                "<g id=\"map-" + esc(v.id) + "\" class=\"vg\" style=\"display:none\"");
    h.append(svgBody).append("</svg>\n");
    mapOverlays(h, d, focus);
    h.append("</main>\n<aside class=\"panel\">\n");
    panel(h, d, first);
    h.append("</aside>\n</div>\n<script>\n").append(JS).append("</script>\n</body>\n</html>\n");
    return h.toString();
  }

  static String esc(String s) {
    return ReportCanvas.esc(s);
  }

  private static void mapOverlays(StringBuilder h, ReportData d, Envelope focus) {
    double north = ReportMap.northAngle(focus);
    h.append(
            "<div class=\"ov tl\"><div class=\"north\" title=\"Истинный север (UTM 37N, сближение"
                + " меридианов ")
        .append(num(north, 1))
        .append(
            "°)\"><svg viewBox=\"-14 -18 28 36\" width=\"26\" height=\"34\"><g transform=\"rotate(")
        .append(String.format(Locale.ROOT, "%.2f", north))
        .append(
            ")\"><path d=\"M0 -15 L7 7 L0 3 L-7 7 Z\" fill=\"#1f2328\"/><path d=\"M0 -15 L0 3 L-7 7"
                + " Z\" fill=\"#6b7280\"/></g></svg><b>С</b></div>")
        .append(
            "<div class=\"scale\"><div id=\"scalebar\"></div><span"
                + " id=\"scaletext\">—</span></div></div>\n");
    h.append(
        "<div class=\"ov tr zoom\"><button id=\"zin\" title=\"Приблизить\">+</button><button"
            + " id=\"zout\" title=\"Отдалить\">−</button><button id=\"zfit\" title=\"Показать"
            + " всё\">⤢</button></div>\n");
    h.append("<details class=\"ov bl legend\" id=\"legend\" open><summary>Легенда</summary><ul>\n");
    legend(h, d);
    h.append("</ul></details>\n<div id=\"tip\" class=\"tip\" hidden></div>\n");
  }

  private static void legend(StringBuilder h, ReportData d) {
    String sw = "<svg width=\"30\" height=\"16\" viewBox=\"0 0 34 16\">";
    item(
        h,
        sw
            + "<rect x=\"9\" y=\"0\" width=\"16\" height=\"16\" rx=\"3\" fill=\"#1f2328\"/><path"
            + " d=\"M18.5 2 L13.5 9 L16.8 9 L15.5 14 L20.5 7 L17.2 7 Z\" fill=\"#ffd23f\"/></svg>",
        "Источник тепла");
    item(
        h,
        sw
            + "<circle cx=\"17\" cy=\"8\" r=\"7.5\" fill=\"#1f2328\"/><text x=\"17\" y=\"11\""
            + " font-size=\"8.5\" fill=\"#fff\" text-anchor=\"middle\""
            + " font-weight=\"700\">7</text></svg>",
        "Точка подключения");
    item(
        h,
        sw
            + "<line x1=\"1\" y1=\"4\" x2=\"33\" y2=\"4\" stroke=\"#d1242f\" stroke-width=\""
            + ReportCanvas.n(ReportMap.width(100))
            + "\"/><line x1=\"1\" y1=\"12\" x2=\"33\" y2=\"12\" stroke=\"#d1242f\" stroke-width=\""
            + ReportCanvas.n(ReportMap.width(400))
            + "\"/></svg>",
        "Новая сеть, толщина ~ Ду");
    item(
        h,
        sw
            + "<line x1=\"1\" y1=\"8\" x2=\"33\" y2=\"8\" stroke=\"#f5a524\""
            + " stroke-width=\"11\"/><line x1=\"1\" y1=\"8\" x2=\"33\" y2=\"8\" stroke=\"#d1242f\""
            + " stroke-width=\"4\"/></svg>",
        "Спецпрокладка, K > 1");
    item(
        h,
        sw
            + "<circle cx=\"17\" cy=\"8\" r=\"4.4\" fill=\"#fff\" stroke=\"#d1242f\""
            + " stroke-width=\"2.2\"/></svg>",
        "Новая камера");
    item(
        h,
        sw
            + "<path d=\"M17 0.5 L24.5 8 L17 15.5 L9.5 8 Z\" fill=\"#d1242f\" stroke=\"#fff\""
            + " stroke-width=\"1.5\"/></svg>",
        "Врезка в сеть (В1, В2…)");
    item(
        h,
        sw + "<circle cx=\"17\" cy=\"8\" r=\"2.6\" fill=\"#1f2328\" stroke=\"#fff\"/></svg>",
        "Технический узел");
    item(
        h,
        sw
            + "<line x1=\"1\" y1=\"8\" x2=\"33\" y2=\"8\" stroke=\"#2d5b8c\""
            + " stroke-width=\"3\"/><rect x=\"13\" y=\"4\" width=\"8\" height=\"8\""
            + " fill=\"#2d5b8c\" stroke=\"#fff\"/></svg>",
        "Сущ. сеть и камеры");
    Set<String> present = d.restrictionIds().keySet();
    if (present.contains("building"))
      item(
          h,
          sw
              + "<rect x=\"3\" y=\"2\" width=\"13\" height=\"12\" fill=\"#d9d3c9\""
              + " stroke=\"#b7ae9f\"/><rect x=\"18\" y=\"2\" width=\"13\" height=\"12\""
              + " fill=\"#f3dcc2\" stroke=\"#c98b4f\"/></svg>",
          "ОКС / с точкой подкл.");
    for (String type : ReportMap.LAYERS) {
      if (type.equals("building") || !present.contains(type)) continue;
      String[] s = ReportMap.style(type, false);
      String fill = s[0] == null ? "none" : s[0].equals("hatch") ? "url(#hatch)" : s[0];
      String shape =
          s[0] == null
              ? "<line x1=\"1\" y1=\"8\" x2=\"33\" y2=\"8\" stroke=\""
                  + s[1]
                  + "\" stroke-width=\"2\"/>"
              : "<rect x=\"2\" y=\"2\" width=\"30\" height=\"12\" fill=\""
                  + fill
                  + "\" stroke=\""
                  + s[1]
                  + "\"/>";
      item(h, sw + shape + "</svg>", ReportData.typeName(type));
    }
  }

  private static void item(StringBuilder h, String swatch, String label) {
    h.append("<li>").append(swatch).append("<span>").append(esc(label)).append("</span></li>\n");
  }

  private static void panel(StringBuilder h, ReportData d, String first) {
    ReportData.Variant best = d.variants.get(0);
    h.append("<header><div class=\"eyebrow\">")
        .append(esc(VERSION))
        .append(" · отчёт о расчёте</div>")
        .append("<h1>Трассы подключения к тепловым сетям</h1>")
        .append("<div class=\"sub\">")
        .append(esc(d.title))
        .append("</div>");
    h.append("<div class=\"facts\"><span>")
        .append(d.demandCount())
        .append(" точек подключения</span><span>")
        .append(num(d.totalFlow().doubleValue(), 2))
        .append(" т/ч</span><span>")
        .append(d.variants.size())
        .append(
            d.variants.size() == 1
                ? " вариант"
                : d.variants.size() < 5 ? " варианта" : " вариантов")
        .append("</span></div></header>\n");
    h.append("<nav class=\"switch\" role=\"tablist\">");
    for (ReportData.Variant v : d.variants) {
      h.append("<button role=\"tab\" class=\"vbtn")
          .append(v.id.equals(first) ? " on" : "")
          .append("\" data-v=\"")
          .append(esc(v.id))
          .append("\"><b>")
          .append(esc(variantName(v)))
          .append("</b><span>S ")
          .append(num(v.score, 3))
          .append("</span></button>");
    }
    h.append("</nav>\n");
    for (ReportData.Variant v : d.variants) variantPanel(h, d, v, best, !v.id.equals(first));
    compare(h, d);
    inputSummary(h, d);
    rules(h, d);
    h.append("<footer>Вход SHA-256 ")
        .append(shortHash(d.inputHash))
        .append("… · правила ")
        .append(shortHash(String.valueOf(d.verify.get("rules_hash"))))
        .append(
            "… · построено командой <code>report</code>. Отчёт автономный: без интернета, внешних"
                + " шрифтов, скриптов и картографических тайлов; подложка нарисована из входного"
                + " GeoJSON.</footer>\n");
  }

  private static void variantPanel(
      StringBuilder h,
      ReportData d,
      ReportData.Variant v,
      ReportData.Variant best,
      boolean hidden) {
    h.append("<section class=\"vp\" data-v=\"")
        .append(esc(v.id))
        .append('"')
        .append(hidden ? " hidden" : "")
        .append(">\n");
    if (v == best)
      h.append("<div class=\"badge rec\">")
          .append(esc(variantName(v)))
          .append(" — рекомендуемый</div>");
    else
      h.append("<div class=\"badge alt\">")
          .append(esc(variantName(v)))
          .append(" — альтернатива, S +")
          .append(num(v.score - best.score, 3))
          .append(" к варианту ")
          .append(esc(best.id.replaceAll("\\D+", "")))
          .append("</div>");
    double cp = v.costPart(d), lp = v.lengthPart(d), total = cp + lp;
    h.append(
            "<div class=\"score\"><div class=\"lbl\">Итоговый показатель S = 0,7·C / 25 млн ₽ +"
                + " 0,3·L / 100 м</div>")
        .append("<div class=\"big\">")
        .append(num(v.score, 3))
        .append("</div>")
        .append("<div class=\"sbar\"><span class=\"sc\" style=\"width:")
        .append(pct(cp, total))
        .append("%\"></span><span class=\"sl\" style=\"width:")
        .append(pct(lp, total))
        .append("%\"></span></div>")
        .append("<div class=\"slegend\"><span><i class=\"sc\"></i>стоимость 0,7·C/25 млн = <b>")
        .append(num(cp, 3))
        .append("</b></span><span><i class=\"sl\"></i>длина 0,3·L/100 = <b>")
        .append(num(lp, 3))
        .append("</b></span></div></div>\n");
    int connected = v.connectedCount(), all = v.points.size();
    h.append("<div class=\"kpis\">")
        .append(
            kpi(
                "Подключено",
                connected + " из " + all,
                connected == all ? "все точки, штраф 0" : "штраф " + millions(v.penalty),
                connected == all ? "ok" : "bad"))
        .append(
            kpi(
                "Длина новой сети L",
                num(v.length, 0) + " м",
                "спецпрокладка " + num(v.specialLength, 0) + " м",
                ""))
        .append(kpi("Полная стоимость C", millions(v.cost), money(v.cost), ""))
        .append("</div>\n");
    // totals in the same order as the usual engineering summary: pipes, chambers and tie-ins,
    // penalty, total
    double pipes = v.pipeBase + v.specialExtra + v.depthExtra;
    h.append("<h3>Итоги: ")
        .append(esc(variantName(v).toLowerCase(Locale.ROOT)))
        .append("</h3><table class=\"totals\">")
        .append(row("c1", "Строительство трубопроводов", money(pipes), false))
        .append(row("", " базовая прокладка", money(v.pipeBase), true))
        .append(
            row(
                "c2",
                " надбавка спецпрокладки ("
                    + v.specialCount
                    + " уч., "
                    + num(v.specialLength, 0)
                    + " м)",
                money(v.specialExtra),
                true))
        .append(
            v.depth()
                ? row(
                    "c2",
                    " надбавка за глубину Kгл (глубина верха "
                        + num(v.minDepth, 2)
                        + "–"
                        + num(v.maxDepth, 2)
                        + " м)",
                    money(v.depthExtra),
                    true)
                : "")
        .append(
            row(
                "c3",
                "Камеры ("
                    + v.chamberCount
                    + " шт., в т. ч. врезок в сеть: "
                    + tieInChambers(v)
                    + ")",
                money(v.chambers),
                false))
        .append(
            row(
                "c4",
                "Присоединения к существующим камерам ("
                    + v.tieCount
                    + " × "
                    + millions(d.tieInRub)
                    + ")",
                money(v.tieCost),
                false))
        .append(row("c5", "Штраф за неподключение", money(v.penalty), false))
        .append("<tr class=\"sum\"><td>Полная стоимость C</td><td>")
        .append(money(v.cost))
        .append("</td></tr>")
        .append("<tr class=\"sum\"><td>Длина новой сети L</td><td>")
        .append(num(v.length, 1))
        .append(" м</td></tr></table>\n");
    verifyBox(h, d, v);
    h.append(
        "<h3>Точки подключения</h3><div class=\"scroll\"><table"
            + " class=\"pts\"><thead><tr><th>№</th><th>Расход, т/ч</th><th>Ду ввода</th><th>До"
            + " врезки, м</th><th>Врезка</th></tr></thead><tbody>");
    for (ReportData.PointRow r : v.points) {
      h.append("<tr")
          .append(r.connected ? "" : " class=\"miss\"")
          .append("><td><b>")
          .append(esc(r.id))
          .append("</b></td><td>")
          .append(r.flow == null ? "—" : num(r.flow.doubleValue(), 2))
          .append("</td>");
      if (r.connected)
        h.append("<td>")
            .append(r.entryDn)
            .append("</td><td>")
            .append(num(r.pathLength, 1))
            .append("</td><td title=\"")
            .append(esc(r.tieIn))
            .append("\"><b>")
            .append(esc(v.tieLabels.getOrDefault(r.tieIn, r.tieIn)))
            .append("</b></td>");
      else h.append("<td colspan=\"3\">не подключена</td>");
      h.append("</tr>");
    }
    h.append("</tbody></table></div>\n</section>\n");
  }

  static int tieInChambers(ReportData.Variant v) {
    int n = 0;
    for (ReportData.Node node : v.nodes) if (node.kind.equals("tie_in")) n++;
    return n;
  }

  private static void verifyBox(StringBuilder h, ReportData d, ReportData.Variant v) {
    Double score = d.verifiedScore(v.id), margin = d.minimumMargin();
    boolean same = score != null && Math.abs(score - v.score) < 1e-9;
    h.append("<div class=\"verify ")
        .append(d.valid() ? "ok" : "bad")
        .append("\"><div class=\"vt\">")
        .append(
            d.valid() ? "✓ Независимая проверка пройдена" : "✗ Независимая проверка не пройдена")
        .append("</div><ul>")
        .append("<li>ошибок: <b>")
        .append(d.errorCount())
        .append("</b></li>");
    if (margin != null)
      h.append("<li>минимальный запас сверх нормативного отступа: <b>")
          .append(num(margin, 3))
          .append(" м</b></li>");
    if (score != null)
      h.append("<li>S пересчитан проверкой по сохранённому файлу: <b>")
          .append(num(score, 6))
          .append("</b> — ")
          .append(same ? "совпадает" : "<span class=\"warn\">расходится</span>")
          .append("</li>");
    h.append("<li>источник: ").append(esc(d.verifySource)).append("</li></ul></div>\n");
  }

  private static void compare(StringBuilder h, ReportData d) {
    double max = 0;
    for (ReportData.Variant v : d.variants) max = Math.max(max, v.cost);
    h.append(
        "<section class=\"all\"><h3>Сравнение вариантов</h3><table"
            + " class=\"cmp\"><thead><tr><th></th><th>S</th><th>C, млн ₽</th><th>L,"
            + " м</th><th>Камеры</th><th>Спец., м</th><th>Подкл.</th></tr></thead><tbody>");
    for (ReportData.Variant v : d.variants) {
      h.append("<tr data-v=\"")
          .append(esc(v.id))
          .append("\"")
          .append(v == d.variants.get(0) ? " class=\"on\"" : "")
          .append("><td>")
          .append(esc(variantName(v)))
          .append("</td><td><b>")
          .append(num(v.score, 3))
          .append("</b></td><td>")
          .append(num(v.cost / 1e6, 1))
          .append("</td><td>")
          .append(num(v.length, 0))
          .append("</td><td>")
          .append(v.chamberCount)
          .append("</td><td>")
          .append(num(v.specialLength, 0))
          .append("</td><td>")
          .append(v.connectedCount())
          .append("/")
          .append(v.points.size())
          .append("</td></tr>");
    }
    h.append("</tbody></table>\n<h3>Структура затрат</h3><div class=\"stacks\">");
    for (ReportData.Variant v : d.variants) {
      double pipes = v.pipeBase;
      h.append("<div class=\"stk\"><span class=\"sn\">")
          .append(esc(variantName(v)))
          .append("</span><div class=\"bar\" style=\"width:")
          .append(pct(v.cost, max))
          .append("%\">")
          .append(seg("c1", v.pipeBase, v.cost, "Трубопроводы, базовая прокладка: " + money(pipes)))
          .append(
              seg(
                  "c2",
                  v.specialExtra + v.depthExtra,
                  v.cost,
                  "Надбавка спецпрокладки"
                      + (v.depth() ? " и глубины" : "")
                      + ": "
                      + money(v.specialExtra + v.depthExtra)))
          .append(seg("c3", v.chambers, v.cost, "Камеры: " + money(v.chambers)))
          .append(
              seg(
                  "c4",
                  v.tieCost,
                  v.cost,
                  "Присоединения к существующим камерам: " + money(v.tieCost)))
          .append(seg("c5", v.penalty, v.cost, "Штраф за неподключение: " + money(v.penalty)))
          .append("</div><span class=\"sv\">")
          .append(num(v.cost / 1e6, 1))
          .append("</span></div>");
    }
    h.append(
        "</div><div class=\"keys\"><span><i class=\"c1\"></i>трубы (база)</span><span><i"
            + " class=\"c2\"></i>надбавка спецпрокладки</span><span><i"
            + " class=\"c3\"></i>камеры</span><span><i class=\"c4\"></i>присоединения к"
            + " камерам</span><span><i class=\"c5\"></i>штраф</span></div><div"
            + " class=\"note\">Значения в млн ₽; длина полосы пропорциональна"
            + " C.</div></section>\n");
  }

  private static void inputSummary(StringBuilder h, ReportData d) {
    Map<String, Set<String>> byType = d.restrictionIds();
    double existing = 0;
    for (Dataset.Feature f : d.data.networks) existing += f.geometry.getLength();
    h.append("<section class=\"all\"><h3>Исходные данные</h3><ul class=\"inp\">")
        .append("<li>Точек подключения: <b>")
        .append(d.demandCount())
        .append("</b>, суммарный расход <b>")
        .append(num(d.totalFlow().doubleValue(), 2))
        .append(" т/ч</b></li>")
        .append("<li>Существующая сеть: <b>")
        .append(d.data.networks.size())
        .append("</b> участков, ")
        .append(num(existing, 0))
        .append(" м; камер: <b>")
        .append(d.data.chambers.size())
        .append("</b></li><li>Ограничения: ");
    List<String> parts = new ArrayList<>();
    for (Map.Entry<String, Set<String>> e : byType.entrySet())
      parts.add(
          esc(ReportData.typeName(e.getKey())).toLowerCase(Locale.ROOT)
              + " — <b>"
              + e.getValue().size()
              + "</b>");
    h.append(parts.isEmpty() ? "нет" : String.join(", ", parts)).append("</li></ul></section>\n");
  }

  private static void rules(StringBuilder h, ReportData d) {
    h.append(
        "<section class=\"all\"><details open><summary><h3>Трактовки правил"
            + " R01–R07</h3></summary><dl class=\"rules\">");
    for (Map<String, Object> r : d.decisions) {
      h.append("<dt>")
          .append(esc(String.valueOf(r.get("id"))))
          .append("<small>")
          .append(
              "from_appendix".equals(r.get("status"))
                  ? "из приложения"
                  : "документированная трактовка")
          .append("</small></dt><dd>")
          .append(esc(String.valueOf(r.get("interpretation"))))
          .append("</dd>");
    }
    h.append("</dl></details></section>\n");
  }

  private static String kpi(String k, String v, String sub, String cls) {
    return "<div class=\"kpi "
        + cls
        + "\"><div class=\"k\">"
        + esc(k)
        + "</div><div class=\"v\">"
        + esc(v)
        + "</div><div class=\"s\">"
        + esc(sub)
        + "</div></div>";
  }

  private static String row(String color, String label, String value, boolean minor) {
    return "<tr"
        + (minor ? " class=\"minor\"" : "")
        + "><td>"
        + (color.isEmpty() ? "" : "<i class=\"" + color + "\"></i>")
        + esc(label)
        + "</td><td>"
        + value
        + "</td></tr>";
  }

  private static String seg(String cls, double value, double total, String title) {
    if (value <= 0 || total <= 0) return "";
    return "<span class=\""
        + cls
        + "\" style=\"width:"
        + pct(value, total)
        + "%\" title=\""
        + esc(title)
        + "\"></span>";
  }

  private static String pct(double part, double total) {
    return total <= 0 ? "0" : String.format(Locale.ROOT, "%.2f", 100 * part / total);
  }

  static final String CSS =
      String.join(
          "\n",
          ":root{--ink:#1f2328;--muted:#646b75;--line:#e6e3dc;--panel:#ffffff;--bg:#f2efe9;--red:#d1242f;--amber:#f5a524;--ok:#1a7f37;--okbg:#e9f6ec;--bad:#b42318;--badbg:#fdecea;",
          " --c1:#d1242f;--c2:#f5a524;--c3:#6d4bd8;--c4:#0e7490;--c5:#57534e}",
          "*{box-sizing:border-box}html,body{margin:0;height:100%}",
          "body{font:14px/1.45 -apple-system,BlinkMacSystemFont,'Segoe UI','PT"
              + " Sans',Roboto,'Helvetica"
              + " Neue',Arial,sans-serif;color:var(--ink);background:var(--bg)}",
          ".app{display:flex;height:100vh}",
          ".map{position:relative;flex:1;min-width:0;background:var(--bg);overflow:hidden;touch-action:none}",
          "#map{width:100%;height:100%;display:block;cursor:grab;user-select:none}#map.drag{cursor:grabbing}",
          "#map text{font-family:inherit}",
          "#map .hit{fill:none;stroke:transparent;pointer-events:stroke;cursor:help}",
          "#map .mk[data-t]{cursor:help}",
          ".ov{position:absolute;z-index:2}.tl{left:14px;top:14px;display:flex;gap:10px;align-items:flex-end}.tr{right:14px;top:14px}.bl{left:14px;bottom:14px}",
          ".north,.scale,.legend,.zoom button{background:rgba(255,255,255,.92);border:1px solid"
              + " var(--line);border-radius:10px;box-shadow:0 1px 3px rgba(0,0,0,.08)}",
          ".north{display:flex;flex-direction:column;align-items:center;padding:4px 7px"
              + " 3px;font-size:11px}",
          ".scale{padding:6px"
              + " 10px;font-size:12px;font-variant-numeric:tabular-nums}#scalebar{height:6px;border:2px"
              + " solid var(--ink);border-top:none;margin-bottom:3px;width:80px}",
          ".zoom{display:flex;flex-direction:column;gap:6px}.zoom"
              + " button{width:34px;height:34px;font-size:18px;line-height:1;cursor:pointer;color:var(--ink)}",
          ".legend{padding:7px 11px;font-size:12px}.legend"
              + " summary{font-weight:700;cursor:pointer}.legend ul{list-style:none;margin:6px 0"
              + " 0;padding:0;display:grid;grid-template-columns:auto auto;gap:3px 14px}",
          ".legend li{display:flex;align-items:center;gap:6px;white-space:nowrap}.legend"
              + " svg{flex:none}#map.far .dn{display:none}",
          ".tip{position:absolute;z-index:5;pointer-events:none;background:#1f2328;color:#fff;border-radius:8px;padding:8px"
              + " 10px;font-size:12.5px;max-width:300px;box-shadow:0 4px 14px rgba(0,0,0,.25)}",
          ".tip b{display:block;margin-bottom:2px}",
          ".panel{width:460px;flex:none;overflow:auto;background:var(--panel);border-left:1px solid"
              + " var(--line);padding:20px 22px 28px}",
          ".eyebrow{font-size:11px;letter-spacing:.06em;text-transform:uppercase;color:var(--muted)}",
          "h1{font-size:21px;line-height:1.2;margin:4px 0 4px}h3{font-size:14px;margin:18px 0 8px}",
          ".sub{color:var(--muted);font-size:13px}.facts{display:flex;flex-wrap:wrap;gap:6px;margin-top:8px}.facts"
              + " span{background:#f4f2ee;border-radius:999px;padding:2px 9px;font-size:12px}",
          ".switch{display:flex;gap:6px;margin:16px 0 12px}.vbtn{flex:1;border:1px solid"
              + " var(--line);background:#faf9f7;border-radius:10px;padding:7px"
              + " 6px;cursor:pointer;text-align:left;color:var(--ink);font:inherit}",
          ".vbtn b{display:block;font-size:13px}.vbtn"
              + " span{font-size:12px;color:var(--muted);font-variant-numeric:tabular-nums}.vbtn.on{border-color:var(--red);background:#fff5f5;box-shadow:inset"
              + " 0 0 0 1px var(--red)}",
          ".badge{display:inline-block;border-radius:999px;padding:3px"
              + " 11px;font-size:12.5px;font-weight:700}.badge.rec{background:var(--okbg);color:var(--ok)}.badge.alt{background:#f4f2ee;color:var(--muted)}",
          ".score{margin:12px 0 10px}.score .lbl{font-size:12px;color:var(--muted)}.score"
              + " .big{font-size:44px;font-weight:800;line-height:1.1;font-variant-numeric:tabular-nums;letter-spacing:-.01em}",
          ".sbar{display:flex;height:10px;border-radius:5px;overflow:hidden;margin:6px 0 5px}.sbar"
              + " .sc,.slegend i.sc{background:var(--red)}.sbar .sl,.slegend"
              + " i.sl{background:#2d5b8c}",
          ".slegend{display:flex;flex-wrap:wrap;gap:4px"
              + " 14px;font-size:12px;color:var(--muted)}.slegend"
              + " b{color:var(--ink);font-variant-numeric:tabular-nums}.slegend i,.keys i,.totals"
              + " i{display:inline-block;width:9px;height:9px;border-radius:2px;margin-right:5px}",
          ".kpis{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin:12px"
              + " 0}.kpi{border:1px solid var(--line);border-radius:10px;padding:8px 9px}.kpi"
              + " .k{font-size:11.5px;color:var(--muted)}",
          ".kpi .v{font-size:18px;font-weight:800;font-variant-numeric:tabular-nums;white-space:nowrap}.kpi"
              + " .s{font-size:11px;color:var(--muted)}.kpi.ok .v{color:var(--ok)}.kpi.bad"
              + " .v{color:var(--bad)}",
          "table{border-collapse:collapse;width:100%;font-variant-numeric:tabular-nums}td,th{padding:4px"
              + " 4px;border-bottom:1px solid"
              + " var(--line);text-align:right;vertical-align:top}td:first-child,th:first-child{text-align:left}",
          "th{font-size:11.5px;color:var(--muted);font-weight:600}.totals td{font-size:13px}.totals"
              + " tr.minor td{color:var(--muted);font-size:12px}.totals tr.sum"
              + " td{font-weight:800;border-bottom:none;border-top:2px solid var(--ink)}.totals"
              + " tr.sum+tr.sum td{border-top:none;font-weight:600}",
          ".c1{background:var(--c1)}.c2{background:var(--c2)}.c3{background:var(--c3)}.c4{background:var(--c4)}.c5{background:var(--c5)}",
          ".verify{border-radius:10px;padding:10px 12px;margin:14px 0"
              + " 4px;font-size:12.5px}.verify.ok{background:var(--okbg);border:1px solid"
              + " #b7e1c1}.verify.bad{background:var(--badbg);border:1px solid #f5b5ae}",
          ".verify .vt{font-weight:800;font-size:14px}.verify.ok .vt{color:var(--ok)}.verify.bad"
              + " .vt{color:var(--bad)}.verify ul{margin:4px 0"
              + " 0;padding-left:18px}.warn{color:var(--bad);font-weight:700}",
          ".scroll{max-height:360px;overflow:auto;border:1px solid"
              + " var(--line);border-radius:8px}.pts th{position:sticky;top:0;background:#fff}.pts"
              + " td{font-size:12.5px}.pts td.id{font-size:11px;color:var(--muted)}.pts tr.miss"
              + " td{color:var(--bad)}",
          ".cmp td{font-size:12.5px}.cmp tr.on td{background:#fff5f5;font-weight:600}.cmp"
              + " tr{cursor:pointer}",
          ".stacks{display:flex;flex-direction:column;gap:6px}.stk{display:flex;align-items:center;gap:8px;font-size:12px}.stk"
              + " .sn{width:72px;flex:none;color:var(--muted)}.stk"
              + " .sv{font-variant-numeric:tabular-nums}",
          ".stk .bar{display:flex;height:16px;border-radius:4px;overflow:hidden}.stk .bar"
              + " span{display:block;height:100%}",
          ".keys{display:flex;flex-wrap:wrap;gap:4px"
              + " 12px;font-size:11.5px;color:var(--muted);margin-top:8px}.note{font-size:11px;color:var(--muted);margin-top:2px}",
          ".inp{margin:0;padding-left:18px;font-size:12.5px}",
          "details>summary{cursor:pointer}details>summary h3{display:inline}",
          ".rules{font-size:12px;margin:8px 0 0}.rules dt{font-weight:800;margin-top:8px}.rules dt"
              + " small{font-weight:400;color:var(--muted);margin-left:6px}.rules dd{margin:2px 0"
              + " 0}",
          "footer{margin-top:18px;font-size:11px;color:var(--muted)}code{font-size:11px;background:#f4f2ee;padding:0"
              + " 4px;border-radius:4px}",
          "@media"
              + " (max-width:900px){.app{flex-direction:column;height:auto}.map{height:72vh;flex:none}.panel{width:auto;border-left:none;border-top:1px"
              + " solid"
              + " var(--line);padding:16px}.legend{max-width:250px;font-size:11.5px}.legend:not([open]){padding:6px"
              + " 10px}}",
          "@media"
              + " print{.zoom,.tip{display:none}.app{display:block;height:auto}.map{height:auto;aspect-ratio:1.3}.panel{width:auto;border:none}.scroll{max-height:none}}",
          "");

  static final String JS =
      String.join(
          "\n",
          "(function(){",
          "var svg=document.getElementById('map'),box=document.getElementById('mapbox'),tip=document.getElementById('tip');",
          "var S=parseFloat(svg.dataset.s),vb0=svg.viewBox.baseVal,W=vb0.width,H=vb0.height,vb={x:0,y:0,w:W,h:H};",
          "var marks=[].slice.call(svg.querySelectorAll('.mk'));",
          "function unit(){var r=svg.getBoundingClientRect();return"
              + " Math.max(vb.w/Math.max(1,r.width),vb.h/Math.max(1,r.height));}",
          "function apply(){svg.setAttribute('viewBox',vb.x+' '+vb.y+' '+vb.w+' '+vb.h);var"
              + " u=unit();svg.classList.toggle('far',u>1.35);",
          " for(var i=0;i<marks.length;i++){var"
              + " m=marks[i],a=m.getAttribute('data-a');m.setAttribute('transform','translate('+m.getAttribute('data-x')+'"
              + " '+m.getAttribute('data-y')+')'+(a?' rotate('+a+')':'')+' scale('+u+')');}",
          " var mpp=u/S,steps=[1,2,5],best=1;for(var b=1;b<=100000;b*=10)for(var"
              + " k=0;k<3;k++)if(b*steps[k]/mpp<=130)best=b*steps[k];",
          " document.getElementById('scalebar').style.width=(best/mpp)+'px';document.getElementById('scaletext').textContent=(best>=1000?(best/1000)+'"
              + " км':best+' м');}",
          "function zoom(f,cx,cy){var"
              + " nw=Math.min(W*1.6,Math.max(W/60,vb.w*f)),k=nw/vb.w;vb.x=cx-(cx-vb.x)*k;vb.y=cy-(cy-vb.y)*k;vb.w=nw;vb.h=vb.h*k;apply();}",
          "function toUser(e){var p=svg.createSVGPoint();p.x=e.clientX;p.y=e.clientY;return"
              + " p.matrixTransform(svg.getScreenCTM().inverse());}",
          "svg.addEventListener('wheel',function(e){e.preventDefault();var"
              + " p=toUser(e);zoom(Math.exp(e.deltaY*0.0015),p.x,p.y);},{passive:false});",
          "var drag=null;svg.addEventListener('pointerdown',function(e){drag={x:e.clientX,y:e.clientY,vx:vb.x,vy:vb.y,u:unit()};svg.classList.add('drag');svg.setPointerCapture(e.pointerId);});",
          "svg.addEventListener('pointermove',function(e){if(drag){vb.x=drag.vx-(e.clientX-drag.x)*drag.u;vb.y=drag.vy-(e.clientY-drag.y)*drag.u;apply();return;}showTip(e);});",
          "function"
              + " end(){drag=null;svg.classList.remove('drag');}svg.addEventListener('pointerup',end);svg.addEventListener('pointercancel',end);svg.addEventListener('pointerleave',function(){tip.hidden=true;});",
          "svg.addEventListener('dblclick',function(e){var p=toUser(e);zoom(0.5,p.x,p.y);});",
          "function centre(){return{x:vb.x+vb.w/2,y:vb.y+vb.h/2};}",
          "document.getElementById('zin').onclick=function(){var c=centre();zoom(0.6,c.x,c.y);};",
          "document.getElementById('zout').onclick=function(){var"
              + " c=centre();zoom(1/0.6,c.x,c.y);};",
          "document.getElementById('zfit').onclick=function(){vb={x:0,y:0,w:W,h:H};apply();};",
          "function showTip(e){var"
              + " t=e.target.closest?e.target.closest('[data-t]'):null;if(!t||!svg.contains(t)){tip.hidden=true;return;}",
          " var lines=t.getAttribute('data-t').split('\\n"
              + "');tip.textContent='';var"
              + " b=document.createElement('b');b.textContent=lines[0];tip.appendChild(b);",
          " for(var i=1;i<lines.length;i++){var"
              + " d=document.createElement('div');d.textContent=lines[i];tip.appendChild(d);}",
          " tip.hidden=false;var"
              + " r=box.getBoundingClientRect(),x=e.clientX-r.left+14,y=e.clientY-r.top+14;",
          " if(x+tip.offsetWidth>r.width-8)x=e.clientX-r.left-tip.offsetWidth-14;if(y+tip.offsetHeight>r.height-8)y=e.clientY-r.top-tip.offsetHeight-14;tip.style.left=x+'px';tip.style.top=y+'px';}",
          "function select(v){document.body.setAttribute('data-v',v);",
          " [].forEach.call(document.querySelectorAll('.vg'),function(g){g.style.display=g.getAttribute('data-v')===v?'inline':'none';});",
          " [].forEach.call(document.querySelectorAll('.vp'),function(s){s.hidden=s.getAttribute('data-v')!==v;});",
          " [].forEach.call(document.querySelectorAll('.vbtn,.cmp"
              + " tr[data-v]'),function(b){b.classList.toggle('on',b.getAttribute('data-v')===v);});",
          " if(history.replaceState)history.replaceState(null,'','#'+v);}",
          "[].forEach.call(document.querySelectorAll('.vbtn,.cmp"
              + " tr[data-v]'),function(b){b.addEventListener('click',function(){select(b.getAttribute('data-v'));});});",
          "document.addEventListener('keydown',function(e){var"
              + " b=document.querySelectorAll('.vbtn')[parseInt(e.key,10)-1];if(b&&!e.metaKey&&!e.ctrlKey)select(b.getAttribute('data-v'));});",
          "var h=location.hash.slice(1);if(h&&document.querySelector('.vbtn[data-v=\"'+h+'\"]'))select(h);",
          "if(box.clientHeight<640||box.clientWidth<700)document.getElementById('legend').open=false;",
          "window.addEventListener('resize',apply);apply();",
          "})();",
          "");
}
