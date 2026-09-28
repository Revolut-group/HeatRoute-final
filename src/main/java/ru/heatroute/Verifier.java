package ru.heatroute;

import com.fasterxml.jackson.databind.*;
import com.networknt.schema.*;
import java.io.*;
import java.math.*;
import java.nio.file.*;
import java.util.*;
import org.locationtech.jts.geom.*;

/** Independent new-graph traversal and financial aggregation from the actual saved file. */
public final class Verifier {
  static final class F {
    final String id, type, variant;
    final JsonNode p;
    final Geometry g;

    F(JsonNode n) {
      p = n.get("properties");
      id = Ingest.id(p.path("id"));
      type = p.path("object_type").asText();
      variant = p.path("variant_id").asText();
      g = n.path("geometry").isNull() ? null : Geo.read(n.get("geometry"), id);
    }

    String s(String key) {
      return key.endsWith("_id") && p.has(key) ? Ingest.id(p.get(key)) : p.path(key).asText();
    }

    int i(String key) {
      return p.path(key).intValue();
    }

    BigDecimal n(String key) {
      return p.path(key).decimalValue();
    }
  }

  final List<Map<String, Object>> errors = new ArrayList<>();

  /**
   * Findings that never make a result invalid (e.g. small kinks, appendix §2.1): reported, not
   * enforced.
   */
  final List<Map<String, Object>> warnings = new ArrayList<>();

  Dataset data;
  Rules rules;
  VerifierTopology existing;
  int toleranceUses;
  double minimumMargin = Double.POSITIVE_INFINITY;

  public Map<String, Object> verify(Path input, Path result, Rules rules) throws IOException {
    if (rules.current()) return new CurrentVerifier().verify(input, result, rules);
    this.rules = rules;
    data = new Ingest().read(input);
    existing = new VerifierTopology(data, rules);
    errors.clear();
    JsonNode output;
    try {
      output = Json.M.readTree(result.toFile());
    } catch (IOException e) {
      return Map.of(
          "valid",
          false,
          "errors",
          List.of(Map.of("code", "INVALID_JSON", "message", e.getMessage())));
    }
    JsonSchema schema;
    try (InputStream in = getClass().getResourceAsStream("/output.schema.json")) {
      schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(in);
    }
    for (ValidationMessage v : schema.validate(output)) error("SCHEMA", null, v.getMessage());
    List<Map<String, Object>> metrics = new ArrayList<>();
    Map<String, List<F>> variants = new TreeMap<>();
    Set<String> ids = new HashSet<>();
    if (errors.isEmpty()) {
      for (JsonNode n : output.path("features")) {
        F f = new F(n);
        if (!ids.add(f.id) || data.containsId(f.id))
          error("DUPLICATE_ID", f, "Output ID collision");
        variants.computeIfAbsent(f.variant, k -> new ArrayList<>()).add(f);
      }
      Set<String> uniqueVariants = new HashSet<>();
      for (List<F> fs : variants.values()) {
        Map<String, F> points = new HashMap<>();
        for (F f : fs) if (f.g instanceof Point) points.put(f.id, f);
        List<String> pieces = new ArrayList<>();
        for (F f : fs) {
          Map<String, Object> p = new TreeMap<>();
          f.p
              .fields()
              .forEachRemaining(
                  e -> {
                    if (!Set.of("id", "variant_id", "rank").contains(e.getKey()))
                      p.put(e.getKey(), e.getValue());
                  });
          for (String key : List.of("start_node_id", "end_node_id"))
            if (f.p.has(key)) {
              F node = points.get(f.s(key));
              if (node != null)
                p.put(
                    key,
                    node.type
                        + ":"
                        + Geo.key(node.g.getCoordinate())
                        + ":"
                        + node.s("existing_object_id"));
            }
          pieces.add(
              Json.M.writeValueAsString(p) + ":" + (f.g == null ? "null" : Geo.fingerprint(f.g)));
        }
        Collections.sort(pieces);
        if (!uniqueVariants.add(String.join("|", pieces)))
          error("DUPLICATE_VARIANT", fs.get(0), "Identical physical alternative");
      }
      if (variants.size() < 1 || variants.size() > 3)
        error("VARIANT_COUNT", null, "Expected one to three variants");
      for (List<F> fs : variants.values())
        try {
          metrics.add(check(fs));
        } catch (RuntimeException e) {
          error(e instanceof Failure ? ((Failure) e).code : "INVALID_RESULT", null, e.toString());
        }
    }
    metrics.sort(Comparator.comparingInt(m -> ((Number) m.get("rank")).intValue()));
    BigDecimal last = BigDecimal.valueOf(-1);
    int rank = 1;
    for (Map<String, Object> m : metrics) {
      if (((Number) m.get("rank")).intValue() != rank++) error("RANK", null, "Nonsequential ranks");
      BigDecimal score = (BigDecimal) m.get("score");
      if (score.compareTo(last) < 0) error("RANK", null, "Scores not sorted");
      last = score;
    }
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("valid", errors.isEmpty());
    report.put("rules_hash", rules.hash);
    report.put("capabilities", existing.capabilities());
    report.put("errors", new ArrayList<>(errors));
    report.put("variants", metrics);
    report.put("minimum_clearance_margin_m", Double.isFinite(minimumMargin) ? minimumMargin : null);
    report.put("judge_tolerance_uses", toleranceUses);
    report.put("rule_decisions", rules.decisions());
    report.put("scope", "Internal verifier; not organizer acceptance");
    data.close();
    return report;
  }

  private Map<String, Object> check(List<F> fs) {
    Map<String, F> all = new HashMap<>(), incoming = new HashMap<>();
    List<F> lines = new ArrayList<>(), roots = new ArrayList<>();
    Map<String, List<F>> out = new HashMap<>();
    F summary = null;
    for (F f : fs) {
      all.put(f.id, f);
      switch (f.type) {
        case "heat_network":
          lines.add(f);
          out.computeIfAbsent(f.s("start_node_id"), k -> new ArrayList<>()).add(f);
          if (incoming.put(f.s("end_node_id"), f) != null)
            error("MULTIPLE_PARENTS", f, "Multiple incoming edges");
          break;
        case "tie_in":
          roots.add(f);
          break;
        case "variant_summary":
          if (summary != null) error("SUMMARY_COUNT", f, "Multiple summaries");
          summary = f;
          break;
        default:
          break;
      }
    }
    if (summary == null) throw new Failure("SUMMARY_COUNT", "Missing summary");
    Set<String> missed = new TreeSet<>(), known = new TreeSet<>();
    for (JsonNode id : summary.p.path("unconnected_oks_ids"))
      if (!missed.add(id.asText())) error("DUPLICATE_DEMAND", summary, "Duplicate penalty");
    for (Dataset.Demand d : data.demands) known.add(d.id);
    if (!known.containsAll(missed)) error("UNKNOWN_DEMAND", summary, "Unknown penalty ID");
    for (F e : lines) {
      Coordinate a = coordinate(e.s("start_node_id"), all), b = coordinate(e.s("end_node_id"), all);
      Coordinate[] c = e.g.getCoordinates();
      if (a == null || b == null) error("DANGLING_REFERENCE", e, "Unknown node ID");
      else if (a.distance(c[0]) > 1e-6 || b.distance(c[c.length - 1]) > 1e-6)
        error("ENDPOINT", e, "Coordinates differ from referenced nodes");
      if (!e.p.path("depth_start").isNull() || !e.p.path("depth_end").isNull())
        error("DEPTH", e, "XY depth must be null");
      equal(e, "length", Catalog.length(e.g.getLength()), new BigDecimal("0.000000002"));
      Catalog.pipe(e.i("diameter"));
    }
    Set<String> leaves = new TreeSet<>(incoming.keySet());
    leaves.removeAll(out.keySet());
    for (F r : roots) leaves.add(r.id);
    Map<String, List<Dataset.Demand>> attachments = new HashMap<>();
    Set<String> attached = new TreeSet<>();
    for (Dataset.Demand d : data.demands)
      if (!missed.contains(d.id)) {
        String chosen = null;
        for (Dataset.Feature t : d.terminals) {
          if (leaves.contains(t.id)) {
            chosen = t.id;
            break;
          }
          for (String leaf : leaves) {
            Coordinate p = coordinate(leaf, all);
            F exported = all.get(leaf);
            Dataset.Feature original = data.features.get(leaf);
            if (p != null
                && Geo.key(p).equals(Geo.key(t.geometry.getCoordinate()))
                && (exported != null && exported.type.equals("tie_in")
                    || original != null && original.type.equals("oks_connection_point"))) {
              chosen = leaf;
              break;
            }
          }
          if (chosen != null) break;
        }
        if (chosen == null) error("MISSING_DEMAND", summary, "Missing attachment " + d.id);
        else {
          attachments.computeIfAbsent(chosen, k -> new ArrayList<>()).add(d);
          attached.add(d.id);
        }
      }
    Map<String, BigDecimal> flow = new HashMap<>();
    Set<String> nodes = new HashSet<>(), edges = new HashSet<>();
    for (F r : roots) walk(r.id, out, attachments, flow, nodes, edges);
    if (edges.size() != lines.size())
      error("ORPHAN_EDGE", summary, "Not all lines have one root path");
    for (F e : lines)
      equal(e, "flow_tph", flow.getOrDefault(e.id, BigDecimal.ZERO), BigDecimal.ZERO);
    uplift(lines, flow);
    Map<String, Integer> rays = new HashMap<>();
    Map<String, F> anchors = new HashMap<>();
    for (F r : roots) {
      Dataset.Feature target = data.features.get(r.s("existing_object_id"));
      if (target == null || !target.type.equals(r.s("existing_object_type"))) {
        error("TARGET_REFERENCE", r, "Invalid existing object");
        continue;
      }
      if (!existing.connected(target))
        error("SOURCE_REACHABILITY", r, "Tie has no source-connected component");
      if (target.type.equals("heat_chamber")
          ? !Geo.key(target.geometry.getCoordinate()).equals(Geo.key(r.g.getCoordinate()))
          : target.geometry.distance(r.g) > 1e-6) error("TIE_LOCATION", r, "Tie off target");
      int dn = target.type.equals("heat_chamber") ? existing.chamberDn(target) : target.dn();
      if (dn != r.i("existing_diameter")) error("TARGET_DN", r, "Wrong original DN");
      int required =
          out.getOrDefault(r.id, List.of()).stream()
              .mapToInt(e -> e.i("diameter"))
              .max()
              .orElseGet(
                  () ->
                      Catalog.base(
                              attachments.getOrDefault(r.id, List.of()).stream()
                                  .map(d -> d.flow)
                                  .reduce(BigDecimal.ZERO, BigDecimal::add))
                          .dn);
      if (required != r.i("required_diameter")) error("TIE_DN", r, "Wrong new-ray DN");
      equal(r, "cost", rules.money(rules.decimal("cost.tie_in_rub")), BigDecimal.ZERO);
      String key = target.id + ":" + Geo.key(r.g.getCoordinate());
      anchors.put(key, r);
      rays.merge(key, out.getOrDefault(r.id, List.of()).size(), Integer::sum);
      if (target.type.equals("heat_network"))
        for (Dataset.Feature ch : data.chambers)
          if (existing.connected(ch)
              && existing.anchors.get(ch.id).edges.size() < 4
              && rules.nearChamber(ch.geometry.distance(r.g)))
            error("NEARBY_CHAMBER", r, "Available existing camera in radius");
    }
    for (String key : anchors.keySet()) {
      F r = anchors.get(key);
      Dataset.Feature target = data.features.get(r.s("existing_object_id"));
      int degree =
          target.type.equals("heat_network") ? 2 : existing.anchors.get(target.id).edges.size();
      if (degree + rays.get(key) > 4) error("NODE_DEGREE", r, "Too many rays");
    }
    for (F n : fs)
      if (n.type.equals("technical_node") || n.type.equals("heat_chamber")) {
        int degree =
            out.getOrDefault(n.id, List.of()).size() + (incoming.containsKey(n.id) ? 1 : 0);
        boolean anchor = roots.stream().anyMatch(r -> r.g.distance(n.g) < 1e-6);
        if (n.type.equals("technical_node") && degree != 2)
          error("TECHNICAL_BRANCH", n, "Technical node must have degree two");
        if (n.type.equals("heat_chamber") && !anchor && (degree < 3 || degree > 4))
          error("CHAMBER_DEGREE", n, "Branch camera must have three or four rays");
      }
    chambers(fs, lines, roots);
    new GeometryVerifier(this).check(lines, out, all);
    new ReconstructionVerifier(this).check(fs, roots, out, attachments, flow);
    Map<String, BigDecimal> sums = new LinkedHashMap<>();
    for (String k :
        List.of(
            "construction_cost",
            "chamber_construction_cost",
            "tie_in_cost",
            "reconstruction_cost",
            "chamber_reconstruction_cost",
            "unconnected_penalty",
            "new_network_length",
            "reconstruction_length")) sums.put(k, BigDecimal.ZERO);
    Map<String, String> cats =
        Map.of(
            "heat_network",
            "construction_cost",
            "tie_in",
            "tie_in_cost",
            "heat_chamber",
            "chamber_construction_cost",
            "heat_network_reconstruction",
            "reconstruction_cost",
            "heat_chamber_reconstruction",
            "chamber_reconstruction_cost");
    for (F f : fs) {
      String k = cats.get(f.type);
      if (k != null) sums.put(k, sums.get(k).add(f.n("cost")));
      if (f.type.equals("heat_network"))
        sums.put("new_network_length", sums.get("new_network_length").add(f.n("length")));
      if (f.type.equals("heat_network_reconstruction"))
        sums.put("reconstruction_length", sums.get("reconstruction_length").add(f.n("length")));
    }
    for (Dataset.Demand d : data.demands)
      if (missed.contains(d.id))
        sums.put("unconnected_penalty", sums.get("unconnected_penalty").add(rules.penalty(d.flow)));
    BigDecimal cost = BigDecimal.ZERO;
    for (String k : sums.keySet()) {
      equal(summary, k, sums.get(k), BigDecimal.ZERO);
      if (!k.endsWith("length")) cost = cost.add(sums.get(k));
    }
    BigDecimal length = sums.get("new_network_length").add(sums.get("reconstruction_length")),
        score = rules.score(cost, length);
    equal(summary, "calculated_cost", cost, BigDecimal.ZERO);
    equal(summary, "length", length, BigDecimal.ZERO);
    equal(summary, "score", score, BigDecimal.ZERO);
    return Map.of(
        "variant_id",
        summary.variant,
        "rank",
        summary.i("rank"),
        "connected_demands",
        attached.size(),
        "connected_flow_tph",
        data.demands.stream()
            .filter(d -> attached.contains(d.id))
            .map(d -> d.flow)
            .reduce(BigDecimal.ZERO, BigDecimal::add),
        "calculated_cost",
        cost,
        "length",
        length,
        "score",
        score,
        "unconnected_oks_ids",
        missed);
  }

  private BigDecimal walk(
      String n,
      Map<String, List<F>> out,
      Map<String, List<Dataset.Demand>> attachments,
      Map<String, BigDecimal> flow,
      Set<String> nodes,
      Set<String> edges) {
    if (!nodes.add(n)) {
      error("NEW_CYCLE", null, "Cycle or multiple root ownership at " + n);
      return BigDecimal.ZERO;
    }
    BigDecimal g =
        attachments.getOrDefault(n, List.of()).stream()
            .map(d -> d.flow)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    for (F e : out.getOrDefault(n, List.of())) {
      BigDecimal v = walk(e.s("end_node_id"), out, attachments, flow, nodes, edges);
      flow.put(e.id, v);
      edges.add(e.id);
      g = g.add(v);
    }
    return g;
  }

  private void uplift(List<F> lines, Map<String, BigDecimal> flow) {
    Map<String, Integer> dn = new HashMap<>(), base = new HashMap<>();
    for (F f : lines) {
      int b = Catalog.base(flow.getOrDefault(f.id, BigDecimal.ZERO)).dn;
      dn.put(f.id, b);
      base.put(f.id, b);
    }
    for (int pass = 0; pass <= lines.size(); pass++) {
      Set<String> seen = new HashSet<>();
      boolean changed = false;
      for (F f : lines)
        if (seen.add(f.id)) {
          List<F> cc = new ArrayList<>();
          Deque<F> q = new ArrayDeque<>();
          q.add(f);
          while (!q.isEmpty()) {
            F a = q.remove();
            cc.add(a);
            for (F b : lines)
              if (!seen.contains(b.id) && dn.get(a.id).equals(dn.get(b.id)) && incident(a, b)) {
                seen.add(b.id);
                q.add(b);
              }
          }
          if (cc.stream().mapToDouble(a -> a.g.getLength()).sum()
              > Catalog.pipe(dn.get(f.id)).limit + 1e-6) {
            for (F a : cc) {
              int d = dn.get(a.id);
              if (Catalog.pipe(d).index >= Catalog.pipe(base.get(a.id)).index + 1) {
                error("LENGTH_LIMIT", a, "Second uplift required");
                return;
              }
              dn.put(a.id, Catalog.next(Catalog.pipe(d)).dn);
            }
            changed = true;
          }
        }
      if (!changed) break;
    }
    for (F f : lines)
      if (f.i("diameter") != dn.get(f.id))
        error("DN_POLICY", f, "Wrong base/whole-component uplift");
  }

  private void chambers(List<F> fs, List<F> lines, List<F> roots) {
    for (F f : fs)
      if (f.type.equals("heat_chamber")) {
        int dn = 0;
        for (F e : lines)
          if (e.s("start_node_id").equals(f.id) || e.s("end_node_id").equals(f.id))
            dn = Math.max(dn, e.i("diameter"));
        for (F r : roots)
          if (r.g.distance(f.g) < 1e-6) {
            dn = Math.max(dn, r.i("existing_diameter"));
            dn = Math.max(dn, r.i("required_diameter"));
          }
        for (F rec : fs)
          if (rec.type.equals("heat_network_reconstruction") && rec.g.distance(f.g) < 1e-6)
            dn = Math.max(dn, rec.i("required_diameter"));
        if (dn != f.i("diameter")) error("CHAMBER_DN", f, "Wrong incident maximum DN");
        equal(f, "cost", Catalog.chamber(dn), BigDecimal.ZERO);
      }
    for (F r : roots)
      if (r.s("existing_object_type").equals("heat_network")
          && fs.stream()
                  .filter(f -> f.type.equals("heat_chamber") && f.g.distance(r.g) < 1e-6)
                  .count()
              != 1) error("MISSING_CHAMBER", r, "Line tie requires exactly one chamber");
    Map<String, List<F>> rootsByCamera = new TreeMap<>();
    for (F root : roots)
      if (root.s("existing_object_type").equals("heat_chamber"))
        rootsByCamera
            .computeIfAbsent(root.s("existing_object_id"), k -> new ArrayList<>())
            .add(root);
    for (Map.Entry<String, List<F>> entry : rootsByCamera.entrySet()) {
      F root = entry.getValue().get(0);
      Dataset.Feature original = data.features.get(entry.getKey());
      if (original == null) continue;
      int existingDn = existing.chamberDn(original), required = existingDn;
      for (F r : entry.getValue()) required = Math.max(required, r.i("required_diameter"));
      for (F rec : fs)
        if (rec.type.equals("heat_network_reconstruction")
            && existing.anchors.get(original.id).edges.stream()
                .anyMatch(e -> e.feature.id.equals(rec.s("existing_object_id")))
            && rec.g.distance(root.g) < .1)
          required = Math.max(required, rec.i("required_diameter"));
      List<F> found = new ArrayList<>();
      for (F f : fs)
        if (f.type.equals("heat_chamber_reconstruction")
            && f.s("existing_object_id").equals(entry.getKey())) found.add(f);
      if (required > existingDn) {
        if (found.size() != 1)
          error(
              "MISSING_CHAMBER_RECONSTRUCTION",
              root,
              "Exactly one reconstructed tie chamber required");
        else {
          F f = found.get(0);
          if (f.i("existing_diameter") != existingDn
              || f.i("required_diameter") != required
              || !Geo.key(f.g.getCoordinate()).equals(Geo.key(root.g.getCoordinate())))
            error("CHAMBER_RECONSTRUCTION", f, "Camera location or incident maximum DN differs");
        }
      } else if (!found.isEmpty())
        error("CHAMBER_RECONSTRUCTION", found.get(0), "Unnecessary camera reconstruction");
    }
    Set<String> recs = new HashSet<>();
    for (F f : fs)
      if (f.type.equals("heat_chamber_reconstruction")) {
        if (!recs.add(f.s("existing_object_id")))
          error("DUPLICATE_RECONSTRUCTION", f, "Repeated camera");
        Dataset.Feature original = data.features.get(f.s("existing_object_id"));
        if (original == null
            || !original.type.equals("heat_chamber")
            || roots.stream().noneMatch(r -> r.s("existing_object_id").equals(original.id)))
          error("CHAMBER_RECONSTRUCTION", f, "Only tie chambers may be reconstructed");
        if (f.i("required_diameter") <= f.i("existing_diameter"))
          error("CHAMBER_RECONSTRUCTION", f, "No DN increase");
        equal(f, "cost", Catalog.chamber(f.i("required_diameter")), BigDecimal.ZERO);
      }
  }

  static boolean incident(F a, F b) {
    return a.s("start_node_id").equals(b.s("start_node_id"))
        || a.s("start_node_id").equals(b.s("end_node_id"))
        || a.s("end_node_id").equals(b.s("start_node_id"))
        || a.s("end_node_id").equals(b.s("end_node_id"));
  }

  Coordinate coordinate(String id, Map<String, F> out) {
    F f = out.get(id);
    if (f != null
        && f.g instanceof Point
        && Set.of("tie_in", "heat_chamber", "technical_node").contains(f.type))
      return f.g.getCoordinate();
    Dataset.Feature original = data.features.get(id);
    return original != null && original.type.equals("oks_connection_point")
        ? Geo.canonical(original.geometry.getCoordinate())
        : null;
  }

  void equal(F f, String field, BigDecimal expected, BigDecimal tolerance) {
    if (f.n(field).subtract(expected).abs().compareTo(tolerance) > 0)
      error("VALUE_MISMATCH", f, field + " expected=" + expected + " actual=" + f.n(field));
  }

  void warning(String code, F f, String msg) {
    Map<String, Object> e = new LinkedHashMap<>();
    e.put("code", code);
    if (f != null) {
      e.put("variant_id", f.variant);
      e.put("feature_id", f.id);
    }
    e.put("message", msg);
    warnings.add(e);
  }

  void error(String code, F f, String msg) {
    Map<String, Object> e = new LinkedHashMap<>();
    e.put("code", code);
    if (f != null) {
      e.put("variant_id", f.variant);
      e.put("feature_id", f.id);
    }
    e.put("message", msg);
    errors.add(e);
  }
}
