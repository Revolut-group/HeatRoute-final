package ru.heatroute;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class CalculationService {
  public Map<String, Object> solve(
      Path input, Path output, Path diagnostics, Rules rules, int variants) throws IOException {
    return solve(input, output, diagnostics, rules, variants, s -> {});
  }

  public Map<String, Object> solve(
      Path input,
      Path output,
      Path diagnostics,
      Rules rules,
      int variants,
      java.util.function.Consumer<String> stage)
      throws IOException {
    return solve(input, output, diagnostics, rules, variants, stage, null);
  }

  /**
   * With depthOutput the depth stage (appendix §5) also writes its own verified, separately ranked
   * set there.
   */
  public Map<String, Object> solve(
      Path input,
      Path output,
      Path diagnostics,
      Rules rules,
      int variants,
      java.util.function.Consumer<String> stage,
      Path depthOutput)
      throws IOException {
    if (variants < 1 || variants > rules.integer("output.maximum_variants"))
      throw new Failure(
          "INVALID_ATTRIBUTE", "variants must be 1.." + rules.integer("output.maximum_variants"));
    if (Files.size(input) > 3L * 1024 * 1024 * 1024)
      throw new Failure("RESOURCE_LIMIT", "Input exceeds 3 GiB");
    stage.accept("VALIDATING");
    long start = System.nanoTime();
    try (Dataset data = new Ingest().read(input, rules)) {
      stage.accept("INDEXING");
      Existing existing = new Existing(data, rules);
      long imported = System.nanoTime();
      stage.accept("SOLVING");
      Optimizer optimizer = new Optimizer(data, rules, existing);
      optimizer.checkpoint = new Checkpoint(input, output, rules)::consider;
      List<Network.Variant> candidates = optimizer.solve(variants);
      long solved = System.nanoTime();
      Path dest = output.toAbsolutePath();
      Files.createDirectories(dest.getParent());
      Path temp = Files.createTempFile(dest.getParent(), ".heatroute-candidate-", ".geojson");
      Map<String, Object> report;
      List<Object> rejected = new ArrayList<>();
      boolean fallback = false;
      try {
        stage.accept("VERIFYING");
        Json.write(temp, Exporter.collection(candidates));
        report = new Verifier().verify(input, temp, rules);
        if (!Boolean.TRUE.equals(report.get("valid"))) {
          rejected.addAll((List<?>) report.get("errors"));
          Json.write(dest.resolveSibling("rejected-verify-report.json"), report);
          Files.copy(
              temp,
              dest.resolveSibling("rejected-candidate.geojson"),
              StandardCopyOption.REPLACE_EXISTING);
          Set<String> bad = new HashSet<>();
          boolean global = false;
          for (Object item : (List<?>) report.get("errors")) {
            Map<?, ?> error = (Map<?, ?>) item;
            if (error.containsKey("variant_id")) bad.add(error.get("variant_id").toString());
            else global = true;
          }
          List<Network.Variant> valid = new ArrayList<>();
          Exporter exporter = new Exporter(data, existing, rules);
          if (!global)
            for (Network.Variant candidate : candidates)
              if (!bad.contains(candidate.summary.get("variant_id")))
                valid.add(exporter.export(candidate.trees, valid.size() + 1));
          if (valid.isEmpty()) {
            // Preserve B1/B2 even when every top finalist is rejected. Never discard an already
            // found better baseline in favor of B0.
            fallback = true;
            for (Network.Variant baseline : optimizer.reserveCandidates()) {
              Network.Variant ranked = exporter.export(baseline.trees, 1);
              Json.write(temp, Exporter.collection(List.of(ranked)));
              Map<String, Object> checked = new Verifier().verify(input, temp, rules);
              if (Boolean.TRUE.equals(checked.get("valid"))) {
                valid.add(ranked);
                break;
              }
              rejected.addAll((List<?>) checked.get("errors"));
            }
          }
          if (valid.isEmpty() && rules.current()) {
            // Last resort: a verified result is always written. Every point is reported unconnected
            // with the reason in diagnostics.
            Network.Variant empty = new CurrentExporter(data, rules).export(List.of(), 1);
            Json.write(temp, Exporter.collection(List.of(empty)));
            Map<String, Object> checked = new Verifier().verify(input, temp, rules);
            if (Boolean.TRUE.equals(checked.get("valid"))) {
              valid.add(empty);
              System.err.println(
                  "WARNING: no routed candidate passed verification; emitting the verified"
                      + " all-unconnected result");
            }
          }
          if (valid.isEmpty())
            throw new Failure(
                "FINAL_VALIDATION_FAILED", "No candidate passed independent verification");
          candidates = valid;
          Json.write(temp, Exporter.collection(candidates));
          report = new Verifier().verify(input, temp, rules);
        }
        Json.write(dest.resolveSibling("verify-report.json"), report);
        if (!Boolean.TRUE.equals(report.get("valid"))) {
          Files.copy(
              temp,
              dest.resolveSibling("rejected-candidate.geojson"),
              StandardCopyOption.REPLACE_EXISTING);
          throw new Failure(
              "FINAL_VALIDATION_FAILED", "Saved GeoJSON rejected; see verify-report.json");
        }
        Json.atomicMove(temp, dest);
      } finally {
        Files.deleteIfExists(temp);
      }
      Map<String, Object> depth = null;
      long depthStart = System.nanoTime();
      if (depthOutput != null) {
        stage.accept("DEPTH");
        depth = depthStage(input, data, rules, candidates, depthOutput);
      }
      long depthEnd = System.nanoTime();
      Map<String, Object> diag = new LinkedHashMap<>();
      diag.put("input_sha256", Json.hash(input));
      diag.put("rules_hash", rules.hash);
      diag.put("execution_hash", rules.executionHash);
      diag.put("input_audit", data.audit());
      diag.put("capabilities", existing.capabilities());
      diag.put(
          "missing_existing_flow_ids",
          data.networks.stream()
              .filter(f -> f.flow() == null)
              .map(f -> f.id)
              .collect(java.util.stream.Collectors.toList()));
      diag.put(
          "profile",
          rules.current()
              ? rules.text("rules_version") + "_XY"
              : data.reduced ? "REDUCED_POINT_XY" : "FULL_POINT_XY");
      diag.put(
          "linear_reconstruction_status",
          rules.current()
              ? "not_in_current_model"
              : data.reduced ? "unavailable_missing_existing_flow" : "evaluated");
      diag.put("rule_decisions", rules.decisions());
      Map<String, Object> optimization = new LinkedHashMap<>(optimizer.diagnostics());
      optimization.put("B3", candidates.get(0).summary);
      optimization.put("baseline_fallback_used", fallback);
      optimization.put("rejected_finalist_errors", rejected);
      Map<String, Object> reasons = new TreeMap<>();
      Map<?, ?> initialReasons = (Map<?, ?>) optimization.get("unconnected_reasons");
      for (Object missed : (List<?>) candidates.get(0).summary.get("unconnected_oks_ids"))
        reasons.put(
            missed.toString(), unconnectedReason(missed, optimization, initialReasons, rules));
      optimization.put("initial_route_failures", initialReasons);
      optimization.put("unconnected_reasons", reasons);
      diag.put("optimization", optimization);
      diag.put("verified_variants", report.get("variants"));
      if (rules.current())
        diag.put("terminal_approaches", TerminalDiagnostics.inspect(data, rules));
      if (depth != null) diag.put("depth_stage", depth);
      diag.put(
          "timing_seconds",
          depthOutput == null
              ? Map.of(
                  "ingest",
                  (imported - start) / 1e9,
                  "solve",
                  (solved - imported) / 1e9,
                  "verify_export",
                  (depthStart - solved) / 1e9)
              : Map.of(
                  "ingest",
                  (imported - start) / 1e9,
                  "solve",
                  (solved - imported) / 1e9,
                  "verify_export",
                  (depthStart - solved) / 1e9,
                  "depth_stage",
                  (depthEnd - depthStart) / 1e9));
      diag.put("java_version", System.getProperty("java.version"));
      // Peak used heap per pool (the sum overestimates the simultaneous peak slightly); the bench's
      // memory rule reads it.
      long peak = 0;
      for (java.lang.management.MemoryPoolMXBean pool :
          java.lang.management.ManagementFactory.getMemoryPoolMXBeans())
        if (pool.getType() == java.lang.management.MemoryType.HEAP && pool.getPeakUsage() != null)
          peak += pool.getPeakUsage().getUsed();
      diag.put(
          "memory",
          Map.of(
              "heap_max_mb",
              Runtime.getRuntime().maxMemory() / 1048576,
              "peak_heap_pools_mb",
              peak / 1048576));
      Json.write(diagnostics, diag);
      System.err.println(
          "verified result="
              + dest
              + " variants="
              + candidates.size()
              + " score="
              + candidates.get(0).score());
      return diag;
    }
  }

  /**
   * Depth set: the verified 2D trees get the cheapest admissible depth profile, are ranked again by
   * their own score (the two sets are never ranked together) and verified independently. Variants
   * the verifier rejects are dropped.
   */
  private Map<String, Object> depthStage(
      Path input, Dataset data, Rules rules, List<Network.Variant> xy, Path output)
      throws IOException {
    List<Integer> order = new ArrayList<>();
    List<java.math.BigDecimal> scores = new ArrayList<>();
    for (int i = 0; i < xy.size(); i++) {
      order.add(i);
      scores.add(new CurrentExporter(data, rules, true).export(xy.get(i).trees, 1).score());
    }
    order.sort(Comparator.comparing((Integer i) -> scores.get(i)).thenComparingInt(i -> i));
    Path dest = output.toAbsolutePath();
    Files.createDirectories(dest.getParent());
    Path temp = Files.createTempFile(dest.getParent(), ".heatroute-depth-", ".geojson");
    Path reportFile = dest.resolveSibling("verify-report-depth.json");
    try {
      for (int attempt = 0; ; attempt++) {
        List<Network.Variant> ranked = new ArrayList<>();
        List<Map<String, Object>> variants = new ArrayList<>();
        for (int i : order) {
          CurrentExporter exporter = new CurrentExporter(data, rules, true);
          Network.Variant v = exporter.export(xy.get(i).trees, ranked.size() + 1);
          ranked.add(v);
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("variant_id", v.summary.get("variant_id"));
          m.put("from_xy_variant", xy.get(i).summary.get("variant_id"));
          m.put("score", v.score());
          m.put("xy_score", xy.get(i).score());
          m.put("tie_ins", exporter.depthReport);
          variants.add(m);
        }
        Json.write(temp, Exporter.collection(ranked));
        Map<String, Object> report = new Verifier().verify(input, temp, rules);
        Json.write(reportFile, report);
        if (Boolean.TRUE.equals(report.get("valid"))) {
          Json.atomicMove(temp, dest);
          Map<String, Object> out = new LinkedHashMap<>();
          out.put("output", dest.getFileName().toString());
          out.put("policy", rules.text("depth.policy"));
          out.put("normal_m", rules.number("depth.normal_m"));
          out.put("minimum_cover_m", rules.number("depth.minimum_cover_m"));
          out.put("maximum_slope", rules.number("depth.maximum_slope"));
          out.put("variants", variants);
          out.put("verified_variants", report.get("variants"));
          System.err.println(
              "verified depth result="
                  + dest
                  + " variants="
                  + ranked.size()
                  + " score="
                  + ranked.get(0).score());
          return out;
        }
        Set<String> bad = new HashSet<>();
        boolean global = false;
        for (Object item : (List<?>) report.get("errors")) {
          Map<?, ?> error = (Map<?, ?>) item;
          if (error.containsKey("variant_id")) bad.add(error.get("variant_id").toString());
          else global = true;
        }
        List<Integer> kept = new ArrayList<>();
        for (int r = 0; r < order.size(); r++)
          if (!bad.contains("variant_" + (r + 1))) kept.add(order.get(r));
        Files.copy(
            temp,
            dest.resolveSibling("rejected-depth-candidate.geojson"),
            StandardCopyOption.REPLACE_EXISTING);
        if (global || kept.isEmpty() || kept.size() == order.size() || attempt > 2)
          throw new Failure(
              "FINAL_VALIDATION_FAILED", "Depth set rejected; see verify-report-depth.json");
        order = kept;
      }
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  /**
   * Why a point stayed unconnected: the planner's formal reason, else the vector engine's initial
   * route failure, else the honest "not proven infeasible".
   */
  private static Object unconnectedReason(
      Object missed, Map<String, Object> optimization, Map<?, ?> initialReasons, Rules rules) {
    String id =
        missed instanceof com.fasterxml.jackson.databind.JsonNode
            ? ((com.fasterxml.jackson.databind.JsonNode) missed).asText()
            : missed.toString();
    Object planner = optimization.get("terminal_" + id + "_unreachable");
    if (planner instanceof String) return "NO_ADMISSIBLE_ROUTE: " + planner;
    if (initialReasons != null && initialReasons.containsKey(id)) return initialReasons.get(id);
    return rules.current()
        ? "NO_COMPATIBLE_ROUTE_FOUND; not proven infeasible"
        : "NOT_SELECTED_BY_OBJECTIVE_OR_COMPATIBILITY";
  }
}
