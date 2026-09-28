package ru.heatroute;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class Rules {
  private final Map<String, JsonNode> values;
  public final String hash, executionHash;

  private static ObjectMapper yaml() {
    return new ObjectMapper(new YAMLFactory())
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  }

  /**
   * A profile packaged with the service: "default" (config/rules-current.yaml, the documented
   * R02/R03 interpretation) or "strict" (config/rules-strict.yaml, the literal appendix text).
   */
  public static Rules profile(String name) throws IOException {
    if (name == null || name.equals("default")) return new Rules((Path) null);
    if (!name.equals("strict"))
      throw new Failure("INVALID_ATTRIBUTE", "profile must be default or strict");
    try (InputStream in = Rules.class.getResourceAsStream("/config/rules-strict.yaml")) {
      return new Rules(yaml().readTree(in), true);
    }
  }

  public Rules(Path file) throws IOException {
    this(file == null ? null : yaml().readTree(file.toFile()), true);
  }

  private Rules(JsonNode given, boolean parsed) throws IOException {
    ObjectMapper yaml = yaml();
    JsonNode defaults;
    try (InputStream in = Rules.class.getResourceAsStream("/config/rules-current.yaml")) {
      defaults = yaml.readTree(in);
    }
    JsonNode actual = given == null ? defaults : given;
    Map<String, JsonNode> d = new TreeMap<>(), v = new TreeMap<>();
    flatten("", defaults, d);
    flatten("", actual, v);
    for (String k : v.keySet())
      if (!d.containsKey(k)) throw new Failure("INVALID_ATTRIBUTE", "Unknown rules key: " + k);
    d.putAll(v);
    values = Collections.unmodifiableMap(d);
    for (Map.Entry<String, JsonNode> e : d.entrySet()) {
      JsonNode def = get(defaults, e.getKey());
      JsonNode a = e.getValue();
      if (!def.isNull()
          && (a.isNull()
              || def.isNumber() != a.isNumber()
              || def.isBoolean() != a.isBoolean()
              || def.isTextual() != a.isTextual()
              || (def.isIntegralNumber() && a.decimalValue().stripTrailingZeros().scale() > 0)))
        throw new Failure("INVALID_ATTRIBUTE", "Invalid rule type: " + e.getKey());
    }
    if (!Set.of("lct_2026_09_19", "lct_2026_09_20_user", "heatroute_v5_2026_09_16")
        .contains(text("rules_version")))
      throw new Failure("INVALID_ATTRIBUTE", "Unknown rules_version");
    if (!Set.of("containing_building_part_only", "containing_feature")
        .contains(text("input.host_access")))
      throw new Failure("INVALID_ATTRIBUTE", "Unknown host access policy");
    if (!Set.of("nearest_boundary", "nearest_feasible_boundary")
        .contains(text("input.terminal_entry_policy")))
      throw new Failure("INVALID_ATTRIBUTE", "Unknown terminal entry policy");
    if (current()
        && (!text("diameter.length_scope").equals("continuous_path")
            || !text("diameter.uplift").equals("minimum_feasible_diameter")
            || number("cost.nonstandard_turn_factor") != 1
            || !text("cost.special_overlap").equals("piecewise_max")))
      throw new Failure(
          "INVALID_ATTRIBUTE",
          "Current appendix policies cannot be mixed with obsolete diameter/turn/overlap rules");
    if (!text("input.projected_crs").equals("EPSG:32637"))
      throw new Failure("UNSUPPORTED_CRS", "Only EPSG:32637 is registered");
    if (!Set.of("union_max", "piecewise_max").contains(text("cost.special_overlap")))
      throw new Failure("INVALID_ATTRIBUTE", "Unknown special overlap");
    if (!Set.of("outgoing_straight_run", "whole_logical_section").contains(text("cost.turn_scope")))
      throw new Failure("INVALID_ATTRIBUTE", "Unknown turn policy");
    if (number("geometry.numeric_epsilon_m") < 0
        || number("geometry.routing_safety_margin_m") <= 0
        || number("cost.weight_cost") < 0
        || number("cost.weight_length") < 0)
      throw new Failure("INVALID_ATTRIBUTE", "Negative tolerance/weight or missing safety margin");
    if (!Set.of("skip", "forbid_1m", "error").contains(text("input.unknown_restriction")))
      throw new Failure(
          "INVALID_ATTRIBUTE", "Unknown restriction policy: " + text("input.unknown_restriction"));
    if (!Set.of("repair", "error").contains(text("input.invalid_geometry")))
      throw new Failure(
          "INVALID_ATTRIBUTE",
          "Unknown invalid geometry policy: " + text("input.invalid_geometry"));
    for (String key :
        List.of(
            "input.profile",
            "input.source_crs",
            "input.missing_existing_flow",
            "geometry.nearby_chamber_selection",
            "geometry.railway_policy",
            "geometry.metro_policy",
            "diameter.selection"))
      if (!values.get(key).equals(get(defaults, key)))
        throw new Failure("INVALID_ATTRIBUTE", "Unsupported policy: " + key + "=" + text(key));
    if (bool("depth.output_z") || bool("geometry.final_grid_snap"))
      throw new Failure(
          "INVALID_ATTRIBUTE",
          "Z coordinates and grid snapping are not part of the output contract");
    if (!Set.of("appendix", "meeting_conservative").contains(text("depth.policy")))
      throw new Failure("INVALID_ATTRIBUTE", "Unknown depth policy: " + text("depth.policy"));
    if (number("depth.minimum_cover_m") <= 0
        || number("depth.maximum_slope") <= 0
        || number("depth.normal_m") < number("depth.minimum_cover_m"))
      throw new Failure(
          "INVALID_ATTRIBUTE",
          "Depth rules need positive cover and slope, normal depth not above the cover");
    for (String key :
        List.of(
            "output.coordinate_decimals",
            "output.length_decimals",
            "output.cost_decimals",
            "output.score_decimals",
            "execution.worker_count"))
      if (!values.get(key).equals(get(defaults, key)))
        throw new Failure(
            "INVALID_ATTRIBUTE", "This fixed contract setting cannot be overridden: " + key);
    for (String key :
        List.of(
            "cost.base_cost_rub",
            "cost.base_length_m",
            "geometry.coarse_grid_m",
            "execution.maximum_candidate_evaluations",
            "execution.beam_width",
            "execution.ingest_batch_bytes",
            "execution.route_cache_bytes"))
      if (number(key) <= 0) throw new Failure("INVALID_ATTRIBUTE", "Rule must be positive: " + key);
    for (String key :
        List.of(
            "geometry.topology_snap_m",
            "geometry.chamber_snap_m",
            "geometry.chamber_radius_margin_m",
            "geometry.judge_distance_tolerance_m",
            "geometry.chamber_radius_m",
            "geometry.railway_clearance_m",
            "geometry.metro_clearance_m",
            "cost.tie_in_rub",
            "cost.unconnected_fixed_rub",
            "cost.unconnected_per_tph_rub"))
      if (number(key) < 0)
        throw new Failure("INVALID_ATTRIBUTE", "Rule must not be negative: " + key);
    if (number("cost.weight_cost") + number("cost.weight_length") <= 0)
      throw new Failure("INVALID_ATTRIBUTE", "At least one objective weight must be positive");
    if (!Set.of("from_polygon", "from_clearance_buffer").contains(text("cost.road_special_extent")))
      throw new Failure("INVALID_ATTRIBUTE", "Unsupported road extent");
    if (!Set.of("reproducible", "fast", "deep").contains(text("execution.mode")))
      throw new Failure("INVALID_ATTRIBUTE", "Unsupported execution mode");
    JsonNode time = values.get("execution.time_budget_seconds");
    if (!time.isNull() && (!time.isNumber() || time.doubleValue() <= 0))
      throw new Failure("INVALID_ATTRIBUTE", "time_budget_seconds must be null or positive");
    Map<String, JsonNode> math = new TreeMap<>(), exec = new TreeMap<>();
    d.forEach(
        (k, a) -> {
          if (k.startsWith("execution.")) exec.put(k, a);
          else math.put(k, a);
        });
    hash =
        Json.hash(
            (Json.M.writeValueAsString(math) + new String(Catalog.CONTENT, StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8));
    executionHash = Json.hash(Json.M.writeValueAsBytes(exec));
  }

  public Rules withMode(String mode) throws IOException {
    return mode == null ? this : new Rules(this, mode);
  }

  private Rules(Rules original, String mode) throws IOException {
    if (!Set.of("reproducible", "fast", "deep").contains(mode))
      throw new Failure("INVALID_ATTRIBUTE", "Unsupported execution mode");
    Map<String, JsonNode> copy = new TreeMap<>(original.values);
    copy.put("execution.mode", com.fasterxml.jackson.databind.node.TextNode.valueOf(mode));
    values = Collections.unmodifiableMap(copy);
    hash = original.hash;
    Map<String, JsonNode> exec = new TreeMap<>();
    copy.forEach(
        (k, v) -> {
          if (k.startsWith("execution.")) exec.put(k, v);
        });
    executionHash = Json.hash(Json.M.writeValueAsBytes(exec));
  }

  private static JsonNode get(JsonNode n, String key) {
    for (String p : key.split("\\.")) n = n.path(p);
    return n;
  }

  private static void flatten(String prefix, JsonNode node, Map<String, JsonNode> out) {
    node.fields()
        .forEachRemaining(
            e -> {
              String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
              if (e.getValue().isObject()) flatten(key, e.getValue(), out);
              else out.put(key, e.getValue());
            });
  }

  public long searchDeadline() {
    double seconds = number("execution.time_budget_seconds");
    return text("execution.mode").equals("fast") && seconds > 0
        ? System.nanoTime() + (long) (seconds * .8 * 1e9)
        : Long.MAX_VALUE;
  }

  public double queryMargin() {
    return Math.max(
        15,
        Math.max(number("geometry.railway_clearance_m"), number("geometry.metro_clearance_m")) + 2);
  }

  public boolean current() {
    return !text("rules_version").equals("heatroute_v5_2026_09_16");
  }

  public boolean hostFeature() {
    return text("input.host_access").equals("containing_feature");
  }

  public boolean flexibleEntrances() {
    return current() && text("input.terminal_entry_policy").equals("nearest_feasible_boundary");
  }

  public boolean nearChamber(double distance) {
    double radius = number("geometry.chamber_radius_m"), eps = number("geometry.numeric_epsilon_m");
    return bool("geometry.chamber_radius_inclusive")
        ? distance <= radius + eps
        : distance < radius - eps;
  }

  public String text(String key) {
    return values.get(key).asText();
  }

  public double number(String key) {
    return values.get(key).doubleValue();
  }

  public int integer(String key) {
    return values.get(key).intValue();
  }

  public BigDecimal decimal(String key) {
    return values.get(key).decimalValue();
  }

  public boolean bool(String key) {
    return values.get(key).asBoolean();
  }

  public BigDecimal penalty(BigDecimal flow) {
    return money(
        decimal("cost.unconnected_fixed_rub")
            .add(flow.multiply(decimal("cost.unconnected_per_tph_rub"))));
  }

  public BigDecimal score(BigDecimal cost, BigDecimal length) {
    return cost.multiply(decimal("cost.weight_cost"))
        .divide(decimal("cost.base_cost_rub"), 20, RoundingMode.HALF_UP)
        .add(
            length
                .multiply(decimal("cost.weight_length"))
                .divide(decimal("cost.base_length_m"), 20, RoundingMode.HALF_UP))
        .setScale(integer("output.score_decimals"), RoundingMode.HALF_UP);
  }

  public BigDecimal money(BigDecimal n) {
    return n.setScale(integer("output.cost_decimals"), RoundingMode.HALF_UP);
  }

  public List<Map<String, Object>> decisions() {
    if (current()) {
      List<Map<String, Object>> out = new ArrayList<>();
      String src = "Техническое приложение и разъяснения ЛЦТ, 19.09.2026";
      out.add(
          decision(
              "R01",
              src,
              "from_appendix",
              "2D; без реконструкции существующей сети и надбавки за повороты; ДУ по каждому"
                  + " непрерывному пути (разъяснения 1–2); выход — heat_network, heat_chamber,"
                  + " technical_node, variant_summary; неподключение только если маршрут не найден"
                  + " (разъяснение 15)"));
      out.add(
          decision(
              "R02",
              src + ", разъяснение 3",
              flexibleEntrances() ? "documented_interpretation" : "from_appendix",
              flexibleEntrances()
                  ? "Финальный прямой участок идёт от ближайшей к точке границы содержащего"
                      + " полигона. Если такой прямой участок геометрически невозможен (перекрыт"
                      + " другим ограничением), берётся ближайшая допустимая точка границы того"
                      + " же полигона; такие точки перечислены в diagnostics.terminal_approaches"
                  : "Финальный прямой участок строго от ближайшей границы содержащего полигона"));
      out.add(
          decision(
              "R03",
              src + ", разъяснения 3–4, приложение §1.2",
              hostFeature() ? "documented_interpretation" : "from_appendix",
              hostFeature()
                  ? "«Полигон ОКС» = весь объект restriction oks, содержащий точку (все части"
                      + " MultiPolygon): отступ к нему не действует только на финальном прямом"
                      + " участке; пересекать другие части запрещено"
                  : "Исключение отступа только для части MultiPolygon, содержащей точку"));
      out.add(
          decision(
              "R04",
              src + ", таблица 2, разъяснения 6–8, 10",
              "from_appendix",
              "road/tram: один прямой спецпроход под углом ≥45° к линии или границе полигона на"
                  + " входе, продление 3 м, K 1,60/1,75; gas/power/heat_network ±2 м, K"
                  + " 1,25/1,15/1,05; перекрытие — максимальный K; railway и прочие запретные —"
                  + " отступ 1 м"));
      out.add(
          decision(
              "R05",
              src + ", разъяснения 11–13",
              "documented_interpretation",
              "Существующая камера обязательна в радиусе "
                  + number("geometry.chamber_radius_m")
                  + " м (+"
                  + number("geometry.chamber_radius_margin_m")
                  + " м запас) при свободной степени; камеры, не лежащие на линии, привязываются к"
                  + " ближайшей линии в пределах "
                  + number("geometry.chamber_snap_m")
                  + " м; стоимость новой камеры — по наибольшему ДУ всех примыкающих участков,"
                  + " включая существующую линию"));
      out.add(
          decision(
              "R06",
              src + ", приложение §2.1, разъяснение 5",
              "documented_interpretation",
              "Поворот ≤90° проверяется и внутри линии, и в камере между входящей трубой и каждой"
                  + " исходящей"));
      out.add(
          decision(
              "R07",
              src + ", разъяснения 9 и 17",
              "documented_interpretation",
              "Неизвестные restriction_type: "
                  + text("input.unknown_restriction")
                  + "; невалидные полигоны: "
                  + text("input.invalid_geometry")
                  + "; каждое допущение записывается в diagnostics.input_audit.warnings"));
      out.add(
          decision(
              "R08",
              src + ", приложение §5, таблица 2",
              "documented_interpretation",
              "Режим с глубиной (--stage depth) — отдельный набор вариантов поверх тех же трасс."
                  + " Глубина в точках подключения и врезках — обычная "
                  + number("depth.normal_m")
                  + " м, если профиль это допускает; иначе она освобождается и это пишется в"
                  + " диагностику. Вертикальные требования к газу, кабелю и существующей сети"
                  + " действуют на всём спецучастке ±2 м (и на всём плановом перекрытии габаритов"
                  + " при малом угле); под дорогой и трамваем — на всём спецучастке. Сторона"
                  + " (выше/ниже) выбирается по минимальной стоимости, профиль — огибающая с"
                  + " уклоном "
                  + number("depth.maximum_slope")
                  + "; Kгл по среднему концов, деление в точке 3,0 м"
                  + (text("depth.policy").equals("meeting_conservative")
                      ? "; просвет не меньше 0,7 м (консервативная трактовка)"
                      : "")));
      return out;
    }
    String[] descriptions = {
      "Стоимость 0.7, длина 0.3",
      "Область отвода: " + text("cost.turn_scope"),
      "Спецзоны: " + text("cost.special_overlap"),
      "XY, глубины null",
      "Камеры: радиус 10 м включительно, полная стоимость",
      "Railway/metro: запрет и отступ 1 м",
      "Выход только из содержащей части здания",
      "Без входных расходов линейная реконструкция недоступна",
      "Векторная финальная геометрия",
      "Новая камера при врезке в линию",
      "Официальная метрика XY не предоставлена",
      "Java 11, Spring Boot 2.7.18"
    };
    List<Map<String, Object>> out = new ArrayList<>();
    for (int i = 0; i < descriptions.length; i++)
      out.add(
          Map.of(
              "id",
              String.format("Q%02d", i + 1),
              "source",
              "ТЗ v5 §5",
              "interpretation",
              descriptions[i],
              "status",
              "provisional"));
    return out;
  }

  private static Map<String, Object> decision(
      String id, String source, String status, String interpretation) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", id);
    m.put("source", source);
    m.put("status", status);
    m.put("interpretation", interpretation);
    return m;
  }
}
