package ru.heatroute;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * Depth stage (appendix §5) on small scenes with hand-computed profiles. The new DN100 line runs
 * straight up x=0 from a chamber on the existing network to the point at (0,50); gas top 2.8 m, H
 * 0.4, gap 0.2; DN100 H 0.18, so passing over gas needs a top at most 2.42 m (5.8 m of ramp from 3
 * m) and passing under needs at least 3.4 m (4 m of ramp).
 */
class DepthStageTest {
  @TempDir Path temp;
  private Rules rules;

  @BeforeEach
  void rules() throws Exception {
    rules = new Rules(null);
  }

  private static ObjectNode scene() {
    ObjectNode s = Scenes.collection();
    Scenes.add(s, "SRC", "source", Geo.point(Scenes.p(-300, 0)));
    Scenes.add(s, "N1", "heat_network", Geo.line(Scenes.p(-300, 0), Scenes.p(300, 0)))
        .put("diameter", 200);
    Scenes.add(s, "B1", "restriction", Scenes.box(-10, 48, 10, 68)).put("restriction_type", "oks");
    Scenes.add(s, "P1", "oks_connection_point", Geo.point(Scenes.p(0, 50))).put("flow_tph", 20.0);
    return s;
  }

  private static void utility(ObjectNode s, String id, String type, double y) {
    Scenes.add(s, id, "restriction", Geo.line(Scenes.p(-300, y), Scenes.p(300, y)))
        .put("restriction_type", type);
  }

  private static final class Run {
    JsonNode xy, depth, stage;
    Path input, depthFile;

    JsonNode summary(JsonNode fc) {
      for (JsonNode f : fc.path("features"))
        if (f.path("properties").path("object_type").asText().equals("variant_summary"))
          return f.path("properties");
      return null;
    }

    JsonNode tieIn() {
      return stage.path("variants").get(0).path("tie_ins").get(0);
    }

    JsonNode crossing(String id) {
      for (JsonNode c : tieIn().path("crossings"))
        if (c.path("obstacle_id").asText().equals(id)) return c;
      return null;
    }

    List<JsonNode> lines() {
      List<JsonNode> l = new ArrayList<>();
      for (JsonNode f : depth.path("features"))
        if (f.path("properties").path("object_type").asText().equals("heat_network"))
          l.add(f.path("properties"));
      return l;
    }

    double depthAt(String node) {
      for (JsonNode p : lines()) {
        if (p.path("start_node_id").asText().equals(node)) return p.path("depth_start").asDouble();
        if (p.path("end_node_id").asText().equals(node)) return p.path("depth_end").asDouble();
      }
      return Double.NaN;
    }
  }

  private Run run(ObjectNode scene, String name) throws Exception {
    Run r = new Run();
    r.input = Scenes.save(temp.resolve(name + ".geojson"), scene);
    Path out = temp.resolve(name + "-result.geojson"), diag = temp.resolve(name + "-diag.json");
    r.depthFile = temp.resolve(name + "-depth.geojson");
    new CalculationService().solve(r.input, out, diag, rules, 1, x -> {}, r.depthFile);
    Map<String, Object> xy = new Verifier().verify(r.input, out, rules),
        depth = new Verifier().verify(r.input, r.depthFile, rules);
    assertTrue((Boolean) xy.get("valid"), name + " 2D: " + xy.get("errors"));
    assertTrue((Boolean) depth.get("valid"), name + " depth: " + depth.get("errors"));
    r.xy = Json.M.readTree(out.toFile());
    r.depth = Json.M.readTree(r.depthFile.toFile());
    r.stage = Json.M.readTree(diag.toFile()).path("depth_stage");
    for (JsonNode f : r.xy.path("features"))
      if (f.path("properties").has("depth_start"))
        assertTrue(f.path("properties").path("depth_start").isNull(), "2D set keeps depth null");
    for (JsonNode p : r.lines()) {
      double a = p.path("depth_start").asDouble(), b = p.path("depth_end").asDouble();
      assertTrue(Math.abs(b - a) <= .1 * p.path("length").asDouble() + 1e-5, "slope");
      assertTrue((a - 3) * (b - 3) >= -1e-12, "split at 3 m");
    }
    return r;
  }

  @Test
  void gasFarFromEndsIsPassedOverAtNoExtraCost() throws Exception {
    ObjectNode s = scene();
    utility(s, "G1", "gas_pipeline", 20);
    Run r = run(s, "over");
    assertEquals("above", r.crossing("G1").path("side").asText());
    assertEquals(2.42, r.crossing("G1").path("depth_at_crossing_m").asDouble(), 1e-6);
    assertEquals("normal_at_terminals_and_tie_in", r.tieIn().path("endpoint_policy").asText());
    assertEquals(3.0, r.depthAt("P1"), 1e-9);
    assertEquals(0, r.tieIn().path("extra_depth_cost_rub").asDouble(), 1e-6);
    assertEquals(
        r.summary(r.xy).path("score").asDouble(),
        r.summary(r.depth).path("score").asDouble(),
        1e-6);
  }

  @Test
  void gasSevenMetresFromTheInletIsPassedUnderWithKgl() throws Exception {
    // Plateau [41,45]; 5 m to the inlet at 3 m: 4 m of ramp for 3.4 m fits, 5.8 m for 2.42 m does
    // not.
    ObjectNode s = scene();
    utility(s, "G1", "gas_pipeline", 43);
    Run r = run(s, "under");
    assertEquals("below", r.crossing("G1").path("side").asText());
    assertEquals(3.4, r.crossing("G1").path("depth_at_crossing_m").asDouble(), 1e-6);
    assertEquals(3.0, r.depthAt("P1"), 1e-9);
    // Plateau 4 m at Kгл 1.04 with Kспец 1.25, two 4 m ramps at mean Kгл 1.02:
    // 89748*(4*1.25*.04+2*4*.02)
    assertEquals(
        89748 * (4 * 1.25 * .04 + 2 * 4 * .02),
        r.tieIn().path("extra_depth_cost_rub").asDouble(),
        1);
    boolean plateau = false;
    for (JsonNode p : r.lines())
      if (p.path("depth_start").asDouble() == 3.4 && p.path("depth_end").asDouble() == 3.4) {
        plateau = true;
        BigDecimal expected =
            rules.money(
                p.path("length")
                    .decimalValue()
                    .multiply(new BigDecimal("89748"))
                    .multiply(BigDecimal.valueOf(1.25))
                    .multiply(new BigDecimal("1.04")));
        assertEquals(0, expected.compareTo(p.path("cost").decimalValue()), "plateau cost " + p);
        assertEquals("special", p.path("laying_method").asText());
      }
    assertTrue(plateau, "constant-depth stretch at 3.4 m");
    assertTrue(
        r.summary(r.depth).path("score").asDouble() > r.summary(r.xy).path("score").asDouble());
  }

  @Test
  void noRampFitsNearTheInletSoTheInletDepthIsReleased() throws Exception {
    // Plateau [43.5,47.5]; 2.5 m to the inlet: neither ramp fits with the inlet at 3 m, over the
    // gas it is 2.42+0.25.
    ObjectNode s = scene();
    utility(s, "G1", "gas_pipeline", 45.5);
    Run r = run(s, "release");
    assertEquals("normal_at_tie_in", r.tieIn().path("endpoint_policy").asText());
    assertEquals("above", r.crossing("G1").path("side").asText());
    assertEquals(2.67, r.depthAt("P1"), 1e-6);
  }

  @Test
  void closeCrossingsShareOneProfile() throws Exception {
    // Gas at 20 and a cable at 23: over both, the cable plateau (2.02 m) pulls the gas crossing up
    // to 2.12 m.
    ObjectNode s = scene();
    utility(s, "G1", "gas_pipeline", 20);
    utility(s, "E1", "power_cable", 23);
    Run r = run(s, "shared");
    assertEquals("above", r.crossing("G1").path("side").asText());
    assertEquals("above", r.crossing("E1").path("side").asText());
    assertEquals(2.02, r.crossing("E1").path("depth_at_crossing_m").asDouble(), 1e-6);
    assertEquals(2.12, r.crossing("G1").path("depth_at_crossing_m").asDouble(), 1e-6);
    assertEquals(0, r.tieIn().path("extra_depth_cost_rub").asDouble(), 1e-6);
  }

  @Test
  void crossingNextToTheTieInReleasesTheChamberDepth() throws Exception {
    // As expert scene C17, with the DN100 tie-in line 5 m from the crossed DN1000 (top 3.0, H 1.2):
    // plateau [3,7].
    ObjectNode s = Scenes.collection();
    Scenes.add(s, "SRC", "source", Geo.point(Scenes.p(-300, 0)));
    Scenes.add(s, "NB", "heat_network", Geo.line(Scenes.p(-300, 0), Scenes.p(300, 0)))
        .put("diameter", 1000);
    Scenes.add(s, "NA", "heat_network", Geo.line(Scenes.p(-300, -5), Scenes.p(300, -5)))
        .put("diameter", 100);
    Scenes.add(s, "P1", "oks_connection_point", Geo.point(Scenes.p(0, 20))).put("flow_tph", 20.0);
    Run r = run(s, "tie-in");
    assertEquals("free_endpoints", r.tieIn().path("endpoint_policy").asText());
    assertEquals("above", r.crossing("NB").path("side").asText());
    assertEquals(2.32, r.crossing("NB").path("depth_at_crossing_m").asDouble(), 1e-6);
    assertEquals(0, r.tieIn().path("extra_depth_cost_rub").asDouble(), 1e-6);
  }

  @Test
  void tenCrossingsOnOneLineStayFastAndValid() throws Exception {
    // Ten gas lines 7 m apart across one 80 m connection: more than 8 crossings, so sides are
    // chosen greedily.
    ObjectNode s = Scenes.collection();
    Scenes.add(s, "SRC", "source", Geo.point(Scenes.p(-300, 0)));
    Scenes.add(s, "N1", "heat_network", Geo.line(Scenes.p(-300, 0), Scenes.p(300, 0)))
        .put("diameter", 200);
    Scenes.add(s, "B1", "restriction", Scenes.box(-10, 78, 10, 98)).put("restriction_type", "oks");
    Scenes.add(s, "P1", "oks_connection_point", Geo.point(Scenes.p(0, 80))).put("flow_tph", 20.0);
    for (int i = 0; i < 10; i++) utility(s, "G" + i, "gas_pipeline", 6 + 7 * i);
    Run r = run(s, "ten");
    assertEquals(10, r.tieIn().path("crossings").size());
    assertTrue(r.stage.path("variants").get(0).path("tie_ins").size() == 1);
    double seconds =
        Json.M.readTree(temp.resolve("ten-diag.json").toFile())
            .path("timing_seconds")
            .path("depth_stage")
            .asDouble();
    assertTrue(seconds < 20, "depth stage took " + seconds + " s");
  }

  @Test
  void verifierRejectsDepthsThatBreakTheRules() throws Exception {
    ObjectNode s = scene();
    utility(s, "G1", "gas_pipeline", 43);
    Run r = run(s, "tamper");
    // Normal depth everywhere: the pipe would pass through the gas line.
    assertTrue(
        errors(
                r,
                (p, i) -> {
                  p.put("depth_start", 3.0);
                  p.put("depth_end", 3.0);
                })
            .contains("DEPTH_CLEARANCE"));
    // One line without depth in a depth file.
    assertTrue(
        errors(
                r,
                (p, i) -> {
                  if (i == 0) {
                    p.putNull("depth_start");
                    p.putNull("depth_end");
                  }
                })
            .contains("DEPTH"));
    // Depth jump at a node.
    assertTrue(
        errors(
                r,
                (p, i) -> {
                  if (i == 0) p.put("depth_end", p.path("depth_end").asDouble() + .3);
                })
            .contains("DEPTH_CONTINUITY"));
    // Steeper than 0.1 on every line.
    assertTrue(
        errors(
                r,
                (p, i) ->
                    p.put(
                        "depth_end",
                        p.path("depth_start").asDouble() + .1 * p.path("length").asDouble() + .2))
            .contains("DEPTH_SLOPE"));
    // Shallower than the minimum cover of 0.7 m.
    assertTrue(
        errors(
                r,
                (p, i) -> {
                  if (i == 0) p.put("depth_start", .5);
                })
            .contains("DEPTH_COVER"));
  }

  private Set<String> errors(Run r, java.util.function.ObjIntConsumer<ObjectNode> edit)
      throws Exception {
    JsonNode copy = r.depth.deepCopy();
    int i = 0;
    for (JsonNode f : copy.path("features")) {
      ObjectNode p = (ObjectNode) f.path("properties");
      if (p.path("object_type").asText().equals("heat_network")) edit.accept(p, i++);
    }
    Path file = temp.resolve("tampered-" + System.nanoTime() + ".geojson");
    Json.write(file, copy);
    Map<String, Object> report = new Verifier().verify(r.input, file, rules);
    assertFalse((Boolean) report.get("valid"));
    Set<String> codes = new TreeSet<>();
    for (Object e : (List<?>) report.get("errors"))
      codes.add(String.valueOf(((Map<?, ?>) e).get("code")));
    return codes;
  }
}
