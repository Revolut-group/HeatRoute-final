package ru.heatroute;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * The default grid Steiner planner must never return fewer connections than possible and must pass
 * the verifier.
 */
class PlannerTest {
  @TempDir Path temp;

  private JsonNode summary(Path out) throws Exception {
    return ContractRegressionTest.first(
            (ObjectNode) Json.M.readTree(out.toFile()), "variant_summary")
        .path("properties");
  }

  @Test
  void demoSceneIsFullyConnectedAndVerified() throws Exception {
    Rules r = new Rules(null);
    assertEquals("grid_steiner", r.text("execution.planner"));
    Path input = Scenes.save(temp.resolve("n06.json"), Scenes.n06()),
        out = temp.resolve("n06-result.json");
    Map<String, Object> diag =
        new CalculationService().solve(input, out, temp.resolve("n06-diag.json"), r, 1);
    assertNotNull(diag);
    assertEquals(0, summary(out).path("unconnected_oks_ids").size());
    Map<String, Object> report = new Verifier().verify(input, out, r);
    assertTrue((Boolean) report.get("valid"), report.toString());
  }

  /**
   * Reproducible mode: the grid planner must give the same bytes on every run (no clock, no
   * hash-order effects).
   */
  @Test
  void gridPlannerIsByteDeterministic() throws Exception {
    ObjectNode scene = Scenes.n06();
    Scenes.add(scene, "p2", "oks_connection_point", Geo.point(Scenes.p(40, 160)))
        .put("flow_tph", 12);
    Scenes.add(scene, "p3", "oks_connection_point", Geo.point(Scenes.p(-60, 140)))
        .put("flow_tph", 9);
    Path input = Scenes.save(temp.resolve("det.json"), scene);
    Rules r = new Rules(null);
    Path a = temp.resolve("det-a.json"), b = temp.resolve("det-b.json");
    new CalculationService().solve(input, a, temp.resolve("det-a-diag.json"), r, 2);
    new CalculationService().solve(input, b, temp.resolve("det-b-diag.json"), r, 2);
    assertArrayEquals(
        Files.readAllBytes(a), Files.readAllBytes(b), "two runs of the same input differ");
  }

  @Test
  void plannerAndLegacyAgreeOnConnectivity() throws Exception {
    ObjectNode scene = Scenes.n06();
    Scenes.add(scene, "p2", "oks_connection_point", Geo.point(Scenes.p(40, 160)))
        .put("flow_tph", 12);
    Path input = Scenes.save(temp.resolve("two.json"), scene);
    Rules planner = new Rules(null);
    Path out = temp.resolve("two-result.json");
    new CalculationService().solve(input, out, temp.resolve("two-diag.json"), planner, 1);
    assertEquals(0, summary(out).path("unconnected_oks_ids").size());
    assertTrue((Boolean) new Verifier().verify(input, out, planner).get("valid"));
  }
}
