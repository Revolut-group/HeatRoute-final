package ru.heatroute;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import org.locationtech.jts.geom.*;

/**
 * Everything the report shows, read from the input, the saved result and the verification report.
 * Nothing is recomputed by the optimizer: costs and lengths are the values written into the result,
 * the cost structure is derived from them (pipe length x catalogue price, special surcharge = cost
 * - base).
 */
final class ReportData {

  /** One heat_network feature of a variant. */
  static final class Edge {
    String id, from, to;
    double flow, length, cost, baseCost;

    /** Depth of the pipe-pair top at both ends; NaN in the 2D set. */
    double depthStart = Double.NaN, depthEnd = Double.NaN;

    int dn;
    boolean special;
    LineString line;

    double factor() {
      return baseCost > 0 ? cost / baseCost : 1;
    }
  }

  /** heat_chamber or technical_node of a variant. */
  static final class Node {
    String id, kind; // chamber | tie_in (new chamber on an existing line) | technical
    Coordinate p;
    int dn;
    double cost;
  }

  /** One connection point as seen by one variant. */
  static final class PointRow {
    String id;
    BigDecimal flow;
    boolean connected;
    int entryDn;
    double pathLength;
    String tieIn;
    Coordinate p;
  }

  static final class Variant {
    String id;
    int rank;
    double construction, chambers, tieCost, penalty, cost, length, score;
    int tieCount;
    List<String> unconnected = new ArrayList<>();
    final List<Edge> edges = new ArrayList<>();
    final List<Node> nodes = new ArrayList<>();

    /** Roots of the new trees: new chambers on existing lines or ids of existing chambers. */
    final Set<String> roots = new TreeSet<>();

    /** Short names of the tie-ins (В1, В2, ...), numbered by the number of points they serve. */
    final Map<String, String> tieLabels = new LinkedHashMap<>();

    final List<PointRow> points = new ArrayList<>();
    double pipeBase, specialExtra, specialLength;

    /** Depth set only: Kгл surcharge and the range of top depths. */
    double depthExtra, minDepth = Double.NaN, maxDepth = Double.NaN;

    int specialCount, chamberCount;

    boolean depth() {
      return !Double.isNaN(minDepth);
    }

    double costPart(ReportData d) {
      return d.weightCost * cost / d.baseCost;
    }

    double lengthPart(ReportData d) {
      return d.weightLength * length / d.baseLength;
    }

    int connectedCount() {
      int n = 0;
      for (PointRow p : points) if (p.connected) n++;
      return n;
    }
  }

  final Dataset data;
  final Path input, result;
  final String title, inputHash;
  final List<Variant> variants = new ArrayList<>();
  final Map<String, Object> verify;
  final String verifySource;
  final List<Map<String, Object>> decisions;
  final double weightCost, weightLength, baseCost, baseLength, tieInRub;
  final Set<String> hostIds = new HashSet<>();
  final Set<String> existingChamberIds = new HashSet<>();

  /** Existing heat_network features; the ingest also keeps them as special-crossing obstacles. */
  final Set<String> existingNetworkIds = new HashSet<>();

  @SuppressWarnings("unchecked")
  ReportData(Path input, Path result, Path verifyReport, Rules rules, String title)
      throws IOException {
    this.input = input;
    this.result = result;
    this.title = title != null ? title : input.getFileName().toString();
    weightCost = rules.number("cost.weight_cost");
    weightLength = rules.number("cost.weight_length");
    baseCost = rules.number("cost.base_cost_rub");
    baseLength = rules.number("cost.base_length_m");
    tieInRub = rules.number("cost.tie_in_rub");
    inputHash = Json.hash(input);
    data = new Ingest().read(input, rules);
    for (Dataset.Feature c : data.chambers) existingChamberIds.add(c.id);
    for (Dataset.Feature n : data.networks) existingNetworkIds.add(n.id);
    for (Dataset.Demand d : data.demands)
      for (Dataset.Obstacle host : data.hosts(d.terminals.get(0).geometry.getCoordinate()))
        hostIds.add(host.id);
    readResult(Json.M.readTree(result.toFile()));
    if (verifyReport != null) {
      verify = Json.M.readValue(verifyReport.toFile(), Map.class);
      verifySource = verifyReport.getFileName().toString();
    } else {
      verify = new Verifier().verify(input, result, rules);
      verifySource = "проверка выполнена при построении отчёта";
    }
    Object d = verify.get("rule_decisions");
    decisions = d instanceof List ? (List<Map<String, Object>>) d : rules.decisions();
  }

  boolean valid() {
    return Boolean.TRUE.equals(verify.get("valid"));
  }

  int errorCount() {
    Object e = verify.get("errors");
    return e instanceof List ? ((List<?>) e).size() : 0;
  }

  Double minimumMargin() {
    Object m = verify.get("minimum_clearance_margin_m");
    return m instanceof Number ? ((Number) m).doubleValue() : null;
  }

  /**
   * Score of the variant as recomputed by the verifier, or null when the report does not list it.
   */
  Double verifiedScore(String variant) {
    Object list = verify.get("variants");
    if (!(list instanceof List)) return null;
    for (Object o : (List<?>) list) {
      Map<?, ?> m = (Map<?, ?>) o;
      if (variant.equals(m.get("variant_id")) && m.get("score") instanceof Number)
        return ((Number) m.get("score")).doubleValue();
    }
    return null;
  }

  BigDecimal totalFlow() {
    return data.totalFlow();
  }

  int demandCount() {
    return data.demands.size() + data.invalidDemands.size();
  }

  /**
   * Restrictions drawn in the background: the existing network is drawn separately, not as a
   * restriction.
   */
  boolean background(Dataset.Obstacle o) {
    return !(o.type.equals("heat_network") && existingNetworkIds.contains(o.id));
  }

  /** Restriction layers present in the input (for the legend and the input summary). */
  Map<String, Set<String>> restrictionIds() {
    Map<String, Set<String>> byType = new TreeMap<>();
    for (Dataset.Obstacle o : data.obstacles)
      if (background(o))
        byType
            .computeIfAbsent(
                ReportMap.LAYERS.contains(o.type) ? o.type : "prohibited_site",
                k -> new TreeSet<>())
            .add(o.id);
    return byType;
  }

  Variant variant(String id) {
    for (Variant v : variants) if (v.id.equals(id)) return v;
    return null;
  }

  private void readResult(JsonNode root) {
    Map<String, Variant> byId = new TreeMap<>();
    for (JsonNode f : root.path("features")) {
      JsonNode p = f.path("properties");
      String type = p.path("object_type").asText(), vid = p.path("variant_id").asText("variant_1");
      Variant v =
          byId.computeIfAbsent(
              vid,
              k -> {
                Variant n = new Variant();
                n.id = k;
                return n;
              });
      switch (type) {
        case "variant_summary":
          v.rank = p.path("rank").asInt();
          v.construction = p.path("construction_cost").asDouble();
          v.chambers = p.path("chamber_construction_cost").asDouble();
          v.tieCount = p.path("existing_chamber_tie_in_count").asInt();
          v.tieCost = p.path("existing_chamber_tie_in_cost").asDouble();
          v.penalty = p.path("unconnected_penalty").asDouble();
          v.cost = p.path("calculated_cost").asDouble();
          v.length = p.path("new_network_length").asDouble();
          v.score = p.path("score").asDouble();
          for (JsonNode id : p.path("unconnected_oks_ids")) v.unconnected.add(Ingest.id(id));
          break;
        case "heat_network":
          {
            Edge e = new Edge();
            e.id = Ingest.id(p.get("id"));
            e.from = Ingest.id(p.get("start_node_id"));
            e.to = Ingest.id(p.get("end_node_id"));
            e.flow = p.path("flow_tph").asDouble();
            e.dn = p.path("diameter").asInt();
            e.length = p.path("length").asDouble();
            e.cost = p.path("cost").asDouble();
            e.special = "special".equals(p.path("laying_method").asText());
            if (p.path("depth_start").isNumber() && p.path("depth_end").isNumber()) {
              e.depthStart = p.path("depth_start").asDouble();
              e.depthEnd = p.path("depth_end").asDouble();
            }
            e.line = (LineString) Geo.read(f.get("geometry"), e.id);
            e.baseCost = e.length * Catalog.pipe(e.dn).newPrice.doubleValue();
            v.edges.add(e);
            break;
          }
        case "heat_chamber":
        case "technical_node":
          {
            Node n = new Node();
            n.id = Ingest.id(p.get("id"));
            n.kind = type.equals("technical_node") ? "technical" : "chamber";
            n.p = Geo.read(f.get("geometry"), n.id).getCoordinate();
            n.dn = p.path("diameter").asInt();
            n.cost = p.path("cost").asDouble();
            v.nodes.add(n);
            break;
          }
        default:
      }
    }
    for (Variant v : byId.values()) {
      derive(v);
      variants.add(v);
    }
    variants.sort(Comparator.comparingInt((Variant v) -> v.rank).thenComparing(v -> v.id));
  }

  private void derive(Variant v) {
    Map<String, Edge> incoming = new HashMap<>();
    Set<String> ends = new HashSet<>();
    for (Edge e : v.edges) {
      incoming.put(e.to, e);
      ends.add(e.to);
      v.pipeBase += e.baseCost;
      // Kгл surcharge: cost = base x Kспец x Kгл, so the depth part is cost - cost / Kгл.
      double withoutDepth = e.cost;
      if (!Double.isNaN(e.depthStart)) {
        double kd = DepthProfile.factor(e.depthStart, e.depthEnd).doubleValue();
        withoutDepth = e.cost / kd;
        v.depthExtra += e.cost - withoutDepth;
        v.minDepth =
            Double.isNaN(v.minDepth)
                ? Math.min(e.depthStart, e.depthEnd)
                : Math.min(v.minDepth, Math.min(e.depthStart, e.depthEnd));
        v.maxDepth =
            Double.isNaN(v.maxDepth)
                ? Math.max(e.depthStart, e.depthEnd)
                : Math.max(v.maxDepth, Math.max(e.depthStart, e.depthEnd));
      }
      if (e.special) {
        v.specialExtra += withoutDepth - e.baseCost;
        v.specialLength += e.length;
        v.specialCount++;
      }
    }
    for (Edge e : v.edges) if (!ends.contains(e.from)) v.roots.add(e.from);
    for (Node n : v.nodes) {
      if (n.kind.equals("chamber")) {
        v.chamberCount++;
        if (v.roots.contains(n.id)) n.kind = "tie_in";
      }
    }
    List<Dataset.Demand> all = new ArrayList<>(data.demands);
    all.addAll(data.invalidDemands);
    for (Dataset.Demand d : all) {
      PointRow row = new PointRow();
      row.id = d.id;
      row.flow = d.flow;
      row.p = d.terminals.isEmpty() ? null : d.terminals.get(0).geometry.getCoordinate();
      Edge last = incoming.get(d.id);
      row.connected = last != null && !v.unconnected.contains(d.id);
      if (row.connected) {
        row.entryDn = last.dn;
        String at = d.id;
        Set<String> seen = new HashSet<>();
        for (Edge e = incoming.get(at); e != null && seen.add(at); e = incoming.get(at)) {
          row.pathLength += e.length;
          at = e.from;
        }
        row.tieIn = at;
      }
      v.points.add(row);
    }
    Map<String, Integer> served = new TreeMap<>();
    for (String r : v.roots) served.put(r, 0);
    for (PointRow r : v.points) if (r.connected) served.merge(r.tieIn, 1, Integer::sum);
    List<String> order = new ArrayList<>(served.keySet());
    order.sort(Comparator.comparing((String r) -> -served.get(r)).thenComparing(r -> r));
    for (String r : order) v.tieLabels.put(r, "В" + (v.tieLabels.size() + 1));
    v.points.sort(
        Comparator.comparing(
            (PointRow r) -> r.id.matches("\\d+") ? String.format("%12s", r.id) : r.id));
  }

  /** Human-readable name of a restriction type in the legend. */
  static String typeName(String type) {
    switch (type) {
      case "building":
        return "ОКС (здания)";
      case "water":
        return "Водные объекты";
      case "railway":
        return "Железная дорога";
      case "road":
        return "Автодороги (проезжая часть)";
      case "tram_tracks":
        return "Трамвайные пути";
      case "gas_pipeline":
        return "Газопровод";
      case "power_cable":
        return "Электрокабель";
      case "heat_network":
        return "Теплосеть (ограничение)";
      case "metro":
        return "Метро";
      default:
        return "Прочие запретные зоны";
    }
  }
}
