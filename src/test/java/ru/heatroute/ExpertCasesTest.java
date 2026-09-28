package ru.heatroute;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * Engineering scenes with hand-computed expectations from benchmarks/expert-cases. Each claim is
 * checked on the saved variant 1; the score
 * may be better than the hand optimum but not worse than its tolerance. All 17 scenes run (1-4 s
 * each); the separate depth set of every scene must pass the verifier too.
 */
class ExpertCasesTest {
  @TempDir Path temp;
  private static final Path DIR = Path.of("benchmarks/expert-cases");

  @TestFactory
  Stream<DynamicTest> expertCases() throws Exception {
    JsonNode expected = Json.M.readTree(DIR.resolve("expected.json").toFile());
    List<String> names = new ArrayList<>();
    expected.fieldNames().forEachRemaining(names::add);
    Collections.sort(names);
    return names.stream()
        .map(
            name ->
                DynamicTest.dynamicTest(
                    name, () -> check(name, expected.path(name).path("claims"))));
  }

  /**
   * A point that cannot be reached (C12) must not cost the alternatives: both engines' variants are
   * pooled.
   */
  @Test
  void unreachablePointKeepsThreeVariants() throws Exception {
    Path input = DIR.resolve("C12_railway_ring_unconnected.geojson"),
        out = temp.resolve("c12-3.geojson");
    Rules rules = new Rules(null);
    new CalculationService().solve(input, out, temp.resolve("c12-3-diag.json"), rules, 3);
    assertTrue((Boolean) new Verifier().verify(input, out, rules).get("valid"));
    List<String> missed = new ArrayList<>();
    for (JsonNode f : Json.M.readTree(out.toFile()).path("features"))
      if (f.path("properties").path("object_type").asText().equals("variant_summary"))
        missed.add(f.path("properties").path("unconnected_oks_ids").toString());
    assertEquals(3, missed.size());
    assertEquals(
        1,
        new HashSet<>(missed).size(),
        "every variant leaves out the same unreachable point: " + missed);
  }

  private void check(String name, JsonNode claims) throws Exception {
    Path input = DIR.resolve(name + ".geojson"), out = temp.resolve(name + "-result.geojson");
    Rules rules = new Rules(null);
    Path depth = temp.resolve(name + "-depth.geojson");
    new CalculationService()
        .solve(input, out, temp.resolve(name + "-diag.json"), rules, 1, x -> {}, depth);
    Map<String, Object> report = new Verifier().verify(input, out, rules),
        depthReport = new Verifier().verify(input, depth, rules);
    assertTrue((Boolean) report.get("valid"), name + ": " + report.get("errors"));
    assertTrue(
        (Boolean) depthReport.get("valid"), name + " depth set: " + depthReport.get("errors"));
    JsonNode result = Json.M.readTree(out.toFile());
    JsonNode summary = null;
    List<JsonNode> lines = new ArrayList<>();
    int specials = 0;
    for (JsonNode f : result.path("features")) {
      JsonNode p = f.path("properties");
      if (!p.path("variant_id").asText().equals("variant_1")) continue;
      switch (p.path("object_type").asText()) {
        case "variant_summary":
          summary = p;
          break;
        case "heat_network":
          lines.add(p);
          if (p.path("laying_method").asText().equals("special")) specials++;
          break;
        default:
      }
    }
    assertNotNull(summary, name);
    if (claims.has("connected"))
      assertEquals(
          claims.get("connected").asInt(), countConnected(input, summary), name + " connected");
    if (claims.has("unconnected_ids")) {
      Set<String> exp = new TreeSet<>(), got = new TreeSet<>();
      claims.get("unconnected_ids").forEach(x -> exp.add(x.asText()));
      summary.path("unconnected_oks_ids").forEach(x -> got.add(x.asText()));
      assertEquals(exp, got, name + " unconnected");
    }
    if (claims.has("edge_dn_to"))
      for (Iterator<Map.Entry<String, JsonNode>> it = claims.get("edge_dn_to").fields();
          it.hasNext(); ) {
        Map.Entry<String, JsonNode> e = it.next();
        int dn = -1;
        for (JsonNode l : lines)
          if (l.path("end_node_id").asText().equals(e.getKey())) dn = l.path("diameter").asInt();
        assertEquals(e.getValue().asInt(), dn, name + " DN of the line into " + e.getKey());
      }
    if (claims.has("tie_in_count"))
      assertEquals(
          claims.get("tie_in_count").asInt(),
          summary.path("existing_chamber_tie_in_count").asInt(),
          name + " tie-ins");
    if (claims.has("special_count")) {
      int lo = claims.get("special_count").get(0).asInt(),
          hi = claims.get("special_count").get(1).asInt();
      assertTrue(
          specials >= lo && specials <= hi,
          name + " special sections " + specials + " not in [" + lo + "," + hi + "]");
    }
    if (claims.has("score")) {
      double exp = claims.get("score").get(0).asDouble(),
          tol = claims.get("score").get(1).asDouble(),
          got = new BigDecimal(summary.path("score").asText()).doubleValue();
      assertTrue(
          got <= exp + tol,
          name + " score " + got + " worse than hand optimum " + exp + " +/- " + tol);
    }
  }

  private static int countConnected(Path input, JsonNode summary) throws Exception {
    int demands = 0;
    for (JsonNode f : Json.M.readTree(input.toFile()).path("features"))
      if (f.path("properties").path("object_type").asText().equals("oks_connection_point"))
        demands++;
    return demands - summary.path("unconnected_oks_ids").size();
  }
}
