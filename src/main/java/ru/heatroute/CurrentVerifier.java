package ru.heatroute;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.networknt.schema.*;
import java.io.*;
import java.math.*;
import java.nio.file.*;
import java.util.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.linearref.LengthIndexedLine;
import ru.heatroute.Verifier.F;

/** Reads the saved four-type contract, orients its undirected graph and recomputes all sums. */
final class CurrentVerifier {
  private final Verifier v = new Verifier();
  private Dataset data;
  private Rules rules;
  private Existing existing;
  private final Map<String, F> all = new HashMap<>();
  private final Map<String, List<F>> adjacent = new HashMap<>(), out = new HashMap<>();
  private final Map<String, F> incoming = new HashMap<>();
  private final Set<String> roots = new TreeSet<>(), visited = new HashSet<>();
  private final List<F> oriented = new ArrayList<>();
  private final Map<String, BigDecimal> flow = new HashMap<>();
  private final Map<String, Integer> selectedDn = new HashMap<>();

  Map<String, Object> verify(Path input, Path result, Rules rules) throws IOException {
    this.rules = rules;
    v.rules = rules;
    List<Map<String, Object>> metrics = new ArrayList<>();
    try (Dataset dataset = new Ingest().read(input, rules)) {
      data = dataset;
      v.data = data;
      existing = new Existing(data, rules);
      JsonNode output = Json.M.readTree(result.toFile());
      JsonSchema schema;
      try (InputStream in = getClass().getResourceAsStream("/output-current.schema.json")) {
        schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(in);
      }
      for (ValidationMessage message : schema.validate(output))
        v.error("SCHEMA", null, message.getMessage());
      if (v.errors.isEmpty()) {
        Map<String, List<F>> groups = new TreeMap<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode node : output.path("features")) {
          F f = new F(node);
          if (!ids.add(f.id) || data.containsId(f.id))
            v.error("DUPLICATE_ID", f, "Output id collides with an input/output id");
          groups.computeIfAbsent(f.variant, k -> new ArrayList<>()).add(f);
        }
        if (groups.size() < 1 || groups.size() > 3)
          v.error("VARIANT_COUNT", null, "Expected one to three alternatives");
        Set<String> fingerprints = new HashSet<>();
        for (List<F> group : groups.values())
          try {
            List<String> shapes = new ArrayList<>();
            for (F f : group)
              if (f.type.equals("heat_network"))
                shapes.add(Geo.fingerprint(f.g) + ":" + f.i("diameter"));
            Collections.sort(shapes);
            if (!fingerprints.add(shapes.toString()))
              v.error("DUPLICATE_VARIANT", group.get(0), "Same physical network");
            metrics.add(check(group));
          } catch (RuntimeException failure) {
            v.error(
                failure instanceof Failure ? ((Failure) failure).code : "INVALID_RESULT",
                group.get(0),
                failure.toString());
          }
      }
      metrics.sort(Comparator.comparingInt(m -> ((Number) m.get("rank")).intValue()));
      int rank = 1;
      BigDecimal previous = BigDecimal.valueOf(-1);
      for (Map<String, Object> metric : metrics) {
        if (((Number) metric.get("rank")).intValue() != rank++)
          v.error("RANK", null, "Nonsequential ranks");
        BigDecimal score = (BigDecimal) metric.get("score");
        if (score.compareTo(previous) < 0) v.error("RANK", null, "Scores out of order");
        previous = score;
      }
    } catch (Failure failure) {
      v.error(failure.code, null, failure.getMessage());
    }
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("valid", v.errors.isEmpty());
    report.put("rules_hash", rules.hash);
    report.put("errors", v.errors);
    report.put("warnings", v.warnings);
    report.put("variants", metrics);
    report.put(
        "scope",
        "Saved geometry, graph, flows, diameters, cost; route-search completeness and global"
            + " optimality are not proven");
    report.put("rule_decisions", rules.decisions());
    report.put("judge_tolerance_uses", v.toleranceUses);
    report.put(
        "minimum_clearance_margin_m", Double.isFinite(v.minimumMargin) ? v.minimumMargin : null);
    return report;
  }

  private Map<String, Object> check(List<F> features) {
    all.clear();
    adjacent.clear();
    out.clear();
    incoming.clear();
    roots.clear();
    visited.clear();
    oriented.clear();
    flow.clear();
    selectedDn.clear();
    F summary = null;
    List<F> lines = new ArrayList<>();
    for (F f : features) {
      all.put(f.id, f);
      if (f.type.equals("variant_summary")) {
        if (summary != null) v.error("SUMMARY_COUNT", f, "Multiple summaries");
        summary = f;
      }
      if (f.type.equals("heat_network")) {
        lines.add(f);
        adjacent.computeIfAbsent(f.s("start_node_id"), k -> new ArrayList<>()).add(f);
        adjacent.computeIfAbsent(f.s("end_node_id"), k -> new ArrayList<>()).add(f);
      }
    }
    if (summary == null) throw new Failure("SUMMARY_COUNT", "Missing summary");
    // Appendix §5: a file is either the 2D set (all depths null) or the separate depth set (all
    // depths given).
    boolean depthStage = lines.stream().anyMatch(f -> f.p.path("depth_start").isNumber());
    for (F line : lines) {
      if (!(line.g instanceof LineString))
        throw new Failure("INVALID_GEOMETRY", "Expected LineString", line.id);
      Coordinate start = point(line.s("start_node_id")), end = point(line.s("end_node_id"));
      Coordinate[] c = line.g.getCoordinates();
      if (start == null || end == null)
        v.error(
            "DANGLING_REFERENCE",
            line,
            "Only input demand/camera or output camera/technical node allowed");
      else if (start.distance(c[0]) > 2e-6 || end.distance(c[c.length - 1]) > 2e-6)
        v.error("ENDPOINT", line, "Geometry endpoint differs from referenced point");
      for (String ref : List.of("start_node_id", "end_node_id")) {
        String id = line.s(ref);
        JsonNode expected =
            all.containsKey(id)
                ? all.get(id).p.get("id")
                : data.features.containsKey(id) ? data.features.get(id).properties.get("id") : null;
        if (expected != null && !sameId(expected, line.p.get(ref)))
          v.error(
              "REFERENCE_ID_TYPE",
              line,
              "Reference must preserve input/output id value and JSON type");
      }
      Catalog.pipe(line.i("diameter"));
      v.equal(line, "length", Catalog.length(line.g.getLength()), new BigDecimal("0.000000002"));
      if (line.p.path("depth_start").isNull() != line.p.path("depth_end").isNull()
          || line.p.path("depth_start").isNull() == depthStage)
        v.error(
            "DEPTH",
            line,
            depthStage
                ? "Depth stage: every line needs depth_start and depth_end"
                : "Base 2D depth must be null");
    }
    Map<String, Dataset.Feature> targets = new TreeMap<>();
    Set<String> possibleRoots = new TreeSet<>(adjacent.keySet());
    for (F f : features) if (f.type.equals("heat_chamber")) possibleRoots.add(f.id);
    for (Dataset.Feature camera : data.chambers)
      for (Dataset.Demand d : data.demands)
        if (camera.geometry.distance(d.terminals.get(0).geometry) < 1e-6)
          possibleRoots.add(camera.id);
    for (String id : possibleRoots) {
      Dataset.Feature original = data.features.get(id);
      F f = all.get(id);
      Coordinate p = point(id);
      if (p == null) continue;
      if (original != null && original.type.equals("heat_chamber")) {
        roots.add(id);
        targets.put(id, original);
      } else if (f != null && f.type.equals("heat_chamber")) {
        Dataset.Feature target =
            data.networks.stream()
                .filter(n -> n.geometry.distance(Geo.point(p)) <= 1e-6)
                .min(Comparator.comparing(n -> n.id))
                .orElse(null);
        if (target != null) {
          roots.add(id);
          targets.put(id, target);
        }
      }
    }
    for (String root : roots) orient(root, null, new HashSet<>());
    if (visited.size() != lines.size())
      v.error("ORPHAN_EDGE", summary, "Some lines have no chamber on the existing network");
    Set<String> connected = new TreeSet<>();
    Map<String, List<Dataset.Demand>> attachments = new HashMap<>();
    for (Dataset.Demand demand : data.demands) {
      Dataset.Feature terminal = demand.terminals.get(0);
      String id = terminal.id;
      if (incoming.containsKey(id)) {
        if (!out.getOrDefault(id, List.of()).isEmpty())
          v.error("DEMAND_TRANSIT", summary, "A connection point cannot be a transit branch");
        connected.add(demand.id);
        attachments.computeIfAbsent(id, k -> new ArrayList<>()).add(demand);
      } else {
        String at = null;
        for (String leaf : incoming.keySet())
          if (out.getOrDefault(leaf, List.of()).isEmpty()
              && point(leaf) != null
              && point(leaf).distance(Geo.canonical(terminal.geometry.getCoordinate())) < 1e-6) {
            at = leaf;
            break;
          }
        if (at == null)
          for (String root : roots)
            if (point(root).distance(Geo.canonical(terminal.geometry.getCoordinate())) < 1e-6) {
              at = root;
              break;
            }
        if (at != null) {
          connected.add(demand.id);
          attachments.computeIfAbsent(at, k -> new ArrayList<>()).add(demand);
        }
      }
    }
    for (String root : roots) {
      accumulate(root, attachments, new HashSet<>());
      for (F edge : out.getOrDefault(root, List.of())) size(edge);
    }
    for (F edge : oriented) {
      F parent = incoming.get(edge.s("start_node_id"));
      if (parent != null) {
        Coordinate[] a = parent.g.getCoordinates(), b = edge.g.getCoordinates();
        if (Geo.turn(a[a.length - 2], a[a.length - 1], b[1]) > 90.000001)
          v.error("TURN_ANGLE", edge, "Turn at node exceeds 90 degrees");
      }
      v.equal(edge, "flow_tph", flow.getOrDefault(edge.id, BigDecimal.ZERO), BigDecimal.ZERO);
      if (edge.i("diameter") != selectedDn.getOrDefault(edge.id, 0))
        v.error("DIAMETER", edge, "Expected minimum DN " + selectedDn.get(edge.id));
    }
    int tieCount = 0;
    BigDecimal chamberCost = BigDecimal.ZERO;
    Map<String, F> geometryNodes = new HashMap<>(all);
    for (String root : roots) {
      Dataset.Feature target = targets.get(root);
      int rays = adjacent.getOrDefault(root, List.of()).size(),
          existingDegree =
              target.type.equals("heat_chamber")
                  ? Math.max(existing.degree(target), existingDegree(point(root)))
                  : existingDegree(point(root));
      if (rays + existingDegree > 4)
        v.error("NODE_DEGREE", all.get(root), "More than four incident sections at root " + root);
      if (target.type.equals("heat_chamber")) tieCount += rays;
      else
        for (Dataset.Feature camera : existing.availableChambers()) {
          int newRays = adjacent.getOrDefault(camera.id, List.of()).size();
          if (rules.nearChamber(camera.geometry.distance(Geo.point(point(root))))
              && existing.degree(camera) + newRays + rays <= 4)
            v.error(
                "NEARBY_CHAMBER",
                all.get(root),
                "Available existing chamber " + camera.id + " within 10 m");
        }
      ObjectNode props = Json.M.createObjectNode();
      props.put("id", root);
      props.put("variant_id", summary.variant);
      props.put("object_type", "tie_in");
      props.put("existing_object_id", target.id);
      props.put("existing_object_type", target.type);
      ObjectNode n = Json.M.createObjectNode();
      n.set("properties", props);
      n.set("geometry", Geo.json(Geo.point(point(root))));
      geometryNodes.put(root, new F(n));
    }
    for (F f : features)
      if (f.type.equals("heat_chamber") || f.type.equals("technical_node")) {
        int degree = adjacent.getOrDefault(f.id, List.of()).size();
        if (f.type.equals("technical_node")) {
          if (degree != 2) v.error("TECHNICAL_BRANCH", f, "Technical node degree must be two");
        } else {
          if (degree < 1 && attachments.getOrDefault(f.id, List.of()).isEmpty()
              || degree > 4
              || !roots.contains(f.id) && degree < 3)
            v.error("CHAMBER_DEGREE", f, "Unused or invalid branch chamber");
          int dn =
              adjacent.getOrDefault(f.id, List.of()).stream()
                  .mapToInt(e -> e.i("diameter"))
                  .max()
                  .orElse(50);
          if (roots.contains(f.id))
            for (Dataset.Feature network : data.networks)
              if (network.geometry.distance(f.g) < 1e-6) dn = Math.max(dn, network.dn());
          if (f.i("diameter") != dn) v.error("CHAMBER_DN", f, "Expected incident maximum " + dn);
          v.equal(f, "cost", rules.money(Catalog.chamber(dn)), BigDecimal.ZERO);
          chamberCost = chamberCost.add(f.n("cost"));
        }
      }
    Map<String, Object> quality = new GeometryVerifier(v).check(oriented, out, geometryNodes);
    if (depthStage) {
      List<Coordinate> tieIns = new ArrayList<>();
      for (String root : roots) tieIns.add(point(root));
      new DepthVerifier(v, rules).check(lines, tieIns);
    }
    BigDecimal
        pipes = lines.stream().map(f -> f.n("cost")).reduce(BigDecimal.ZERO, BigDecimal::add),
        length = lines.stream().map(f -> f.n("length")).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal
        tieCost =
            rules.money(rules.decimal("cost.tie_in_rub").multiply(BigDecimal.valueOf(tieCount))),
        penalty = BigDecimal.ZERO;
    Set<String> missed = new TreeSet<>();
    Map<String, JsonNode> expectedIds = new HashMap<>();
    for (Dataset.Demand demand : data.demands)
      if (!connected.contains(demand.id)) {
        missed.add(demand.id);
        expectedIds.put(demand.id, demand.terminals.get(0).properties.get("id"));
        penalty = penalty.add(rules.penalty(demand.flow));
      }
    for (Dataset.Demand demand : data.invalidDemands) {
      missed.add(demand.id);
      expectedIds.put(demand.id, demand.terminals.get(0).properties.get("id"));
      penalty = penalty.add(rules.penalty(demand.flow));
    }
    Set<String> reported = new TreeSet<>();
    for (JsonNode id : summary.p.path("unconnected_oks_ids")) {
      reported.add(Ingest.id(id));
      if (!sameId(id, expectedIds.get(Ingest.id(id))))
        v.error("UNCONNECTED_IDS", summary, "ID value/type or connected status differs from input");
    }
    if (!reported.equals(missed)) v.error("UNCONNECTED_IDS", summary, "Incorrect unconnected set");
    BigDecimal construction = pipes.add(chamberCost).add(tieCost),
        cost = construction.add(penalty),
        score = rules.score(cost, length);
    v.equal(summary, "construction_cost", construction, BigDecimal.ZERO);
    v.equal(summary, "chamber_construction_cost", chamberCost, BigDecimal.ZERO);
    v.equal(
        summary, "existing_chamber_tie_in_count", BigDecimal.valueOf(tieCount), BigDecimal.ZERO);
    v.equal(summary, "existing_chamber_tie_in_cost", tieCost, BigDecimal.ZERO);
    v.equal(summary, "unconnected_penalty", penalty, BigDecimal.ZERO);
    v.equal(summary, "calculated_cost", cost, BigDecimal.ZERO);
    v.equal(summary, "new_network_length", length, BigDecimal.ZERO);
    v.equal(summary, "score", score, BigDecimal.ZERO);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("variant_id", summary.variant);
    m.put("rank", summary.i("rank"));
    m.put("connected_demands", connected.size());
    m.put(
        "connected_flow_tph",
        data.demands.stream()
            .filter(d -> connected.contains(d.id))
            .map(d -> d.flow)
            .reduce(BigDecimal.ZERO, BigDecimal::add));
    m.put("calculated_cost", cost);
    m.put("length", length);
    m.put("score", score);
    m.put("unconnected_oks_ids", missed);
    m.put("geometry_quality", quality);
    return m;
  }

  private static boolean sameId(JsonNode a, JsonNode b) {
    return a != null
        && b != null
        && (a.isNumber() && b.isNumber()
            ? a.decimalValue().compareTo(b.decimalValue()) == 0
            : a.equals(b));
  }

  private Coordinate point(String id) {
    F f = all.get(id);
    if (f != null)
      return (f.type.equals("heat_chamber") || f.type.equals("technical_node"))
              && f.g instanceof Point
          ? f.g.getCoordinate()
          : null;
    Dataset.Feature input = data.features.get(id);
    return input != null && Set.of("oks_connection_point", "heat_chamber").contains(input.type)
        ? Geo.canonical(input.geometry.getCoordinate())
        : null;
  }

  private int existingDegree(Coordinate p) {
    int degree = 0;
    Point point = Geo.point(p);
    for (Dataset.Feature f : data.networks)
      if (f.geometry.distance(point) <= rules.number("geometry.topology_snap_m")) {
        LineString line = (LineString) f.geometry;
        double station = new LengthIndexedLine(line).project(p);
        degree +=
            station < rules.number("geometry.topology_snap_m")
                    || line.getLength() - station < rules.number("geometry.topology_snap_m")
                ? 1
                : 2;
      }
    return degree;
  }

  private void orient(String node, String parent, Set<String> path) {
    if (!path.add(node)) {
      v.error("NEW_CYCLE", null, "Cycle at " + node);
      return;
    }
    if (parent != null && roots.contains(node)) {
      v.error("MULTIPLE_ROOTS", all.get(node), "New component joins two existing roots");
      return;
    }
    List<F> neighbors = new ArrayList<>(adjacent.getOrDefault(node, List.of()));
    neighbors.sort(Comparator.comparing(f -> f.id));
    for (F original : neighbors) {
      if (original.id.equals(parent)) continue;
      if (!visited.add(original.id)) {
        v.error("NEW_CYCLE", original, "Edge visited twice");
        continue;
      }
      F edge = original;
      if (!original.s("start_node_id").equals(node)) {
        ObjectNode p = original.p.deepCopy();
        p.set("start_node_id", original.p.get("end_node_id"));
        p.set("end_node_id", original.p.get("start_node_id"));
        p.set("depth_start", original.p.get("depth_end"));
        p.set("depth_end", original.p.get("depth_start"));
        ObjectNode n = Json.M.createObjectNode();
        n.set("properties", p);
        n.set("geometry", Geo.json(original.g.reverse()));
        edge = new F(n);
      }
      oriented.add(edge);
      out.computeIfAbsent(node, k -> new ArrayList<>()).add(edge);
      if (incoming.put(edge.s("end_node_id"), edge) != null)
        v.error("MULTIPLE_PARENTS", edge, "More than one path to root");
      orient(edge.s("end_node_id"), edge.id, new HashSet<>(path));
    }
  }

  private BigDecimal accumulate(
      String node, Map<String, List<Dataset.Demand>> attachments, Set<String> path) {
    if (!path.add(node)) return BigDecimal.ZERO;
    BigDecimal sum =
        attachments.getOrDefault(node, List.of()).stream()
            .map(d -> d.flow)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    for (F edge : out.getOrDefault(node, List.of())) {
      BigDecimal downstream = accumulate(edge.s("end_node_id"), attachments, path);
      flow.put(edge.id, downstream);
      sum = sum.add(downstream);
    }
    return sum;
  }

  private double size(F first) {
    List<F> chain = new ArrayList<>();
    F end = first;
    chain.add(end);
    while (out.getOrDefault(end.s("end_node_id"), List.of()).size() == 1) {
      F next = out.get(end.s("end_node_id")).get(0);
      if (flow.get(next.id).compareTo(flow.get(first.id)) != 0) break;
      if (chain.contains(next)) throw new Failure("NEW_CYCLE", "Cycle while sizing");
      chain.add(next);
      end = next;
    }
    List<F> children = out.getOrDefault(end.s("end_node_id"), List.of());
    Map<String, Double> tails = new HashMap<>();
    int minimum = Catalog.base(flow.get(first.id)).dn;
    for (F child : children) {
      tails.put(child.id, size(child));
      minimum = Math.max(minimum, selectedDn.get(child.id));
    }
    double length = chain.stream().mapToDouble(f -> f.g.getLength()).sum();
    for (Catalog.Pipe pipe : Catalog.PIPES)
      if (pipe.dn >= minimum) {
        double longest = length;
        for (F child : children)
          if (selectedDn.get(child.id) == pipe.dn)
            longest = Math.max(longest, length + tails.get(child.id));
        if (longest <= pipe.limit + 1e-6) {
          for (F edge : chain) selectedDn.put(edge.id, pipe.dn);
          return longest;
        }
      }
    throw new Failure("LENGTH_LIMIT", "No diameter satisfies continuous path length");
  }
}
