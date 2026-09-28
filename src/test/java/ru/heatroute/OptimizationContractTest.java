package ru.heatroute;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.*;

class OptimizationContractTest {
  @TempDir Path temp;

  @Test
  void T19_secondUpliftAfterMergingComponentsIsRejected() throws Exception {
    ObjectNode s = Scenes.n06();
    ObjectNode f = (ObjectNode) s.path("features").get(4);
    f.set("geometry", Geo.json(Geo.point(Scenes.p(0, 450))));
    ((ObjectNode) f.path("properties")).put("flow_tph", 4);
    Scenes.add(s, "p2", "oks_connection_point", Geo.point(Scenes.p(20, 200))).put("flow_tph", 9);
    Rules r = new Rules(java.nio.file.Path.of("config/rules-v5.yaml"));
    try (Dataset d = new Ingest().read(Scenes.save(temp.resolve("uplift.json"), s))) {
      Existing ex = new Existing(d, r);
      Network.Tree t =
          new Network.Tree(
              ex.roots(Scenes.p(0, 200)).stream()
                  .filter(a -> a.target.id.equals("ch"))
                  .findFirst()
                  .orElseThrow());
      Network.Node branch = new Network.Node("b", Scenes.p(0, 200));
      t.edges.add(new Network.Edge(t.start, branch, Geo.line(t.start.p, branch.p)));
      for (Dataset.Demand dem : d.demands) {
        Network.Node leaf =
            new Network.Node(
                dem.id, dem.terminals.get(0).geometry.getCoordinate(), List.of(dem), dem.id);
        t.edges.add(new Network.Edge(branch, leaf, Geo.line(branch.p, leaf.p)));
      }
      assertEquals(
          "LENGTH_LIMIT",
          assertThrows(
                  Failure.class, () -> new Evaluation(d, r, new Router(d, r)).evaluate(t, false))
              .code);
    }
  }

  @Test
  void T20_groupWidthIsRechecked() throws Exception {
    ObjectNode s = Scenes.n06();
    ((ObjectNode) s.path("features").get(4).path("properties")).put("flow_tph", 1);
    Scenes.add(s, "p2", "oks_connection_point", Geo.point(Scenes.p(0, 100))).put("flow_tph", 99);
    Scenes.add(s, "left", "oks_existing", Scenes.box(-20, 40, -5.3, 60));
    Scenes.add(s, "right", "oks_existing", Scenes.box(5.3, 40, 20, 60));
    Rules r = new Rules(java.nio.file.Path.of("config/rules-v5.yaml"));
    try (Dataset d = new Ingest().read(Scenes.save(temp.resolve("width.json"), s))) {
      Existing ex = new Existing(d, r);
      Network.Tree t =
          new Network.Tree(
              ex.roots(Scenes.p(0, 100)).stream()
                  .filter(a -> a.target.id.equals("ch"))
                  .findFirst()
                  .orElseThrow());
      Network.Node leaf =
          new Network.Node("leaf", Scenes.p(0, 100), List.of(d.demands.get(0)), "p1");
      t.edges.add(new Network.Edge(t.start, leaf, Geo.line(t.start.p, leaf.p)));
      Evaluation ev = new Evaluation(d, r, new Router(d, r));
      assertEquals(50, ev.evaluate(t, false).edges.get(0).dn);
      leaf.demands.add(d.demands.get(1));
      assertEquals("CLEARANCE", assertThrows(Failure.class, () -> ev.evaluate(t, false)).code);
    }
  }

  @Test
  void T21_economicallyExpensiveDemandCanBeDropped() throws Exception {
    ObjectNode s = Scenes.n06();
    for (JsonNode f : s.path("features"))
      if (Set.of("heat_network", "heat_chamber")
          .contains(f.path("properties").path("object_type").asText()))
        ((ObjectNode) f.path("properties")).put("diameter", 400);
    ObjectNode point = (ObjectNode) s.path("features").get(4);
    point.set("geometry", Geo.json(Geo.point(Scenes.p(0, 2200))));
    ((ObjectNode) point.path("properties")).put("flow_tph", 400);
    Path input = Scenes.save(temp.resolve("drop.json"), s), out = temp.resolve("drop-out.json");
    Rules r = new Rules(java.nio.file.Path.of("config/rules-v5.yaml"));
    new CalculationService().solve(input, out, temp.resolve("drop-diag.json"), r, 1);
    JsonNode result = Json.M.readTree(out.toFile());
    JsonNode summary =
        ContractRegressionTest.first((ObjectNode) result, "variant_summary").path("properties");
    assertEquals(1, summary.path("unconnected_oks_ids").size());
    assertEquals(
        0, new BigDecimal("300000000").compareTo(summary.path("calculated_cost").decimalValue()));
    assertEquals(0, summary.path("length").decimalValue().signum());
  }

  @Test
  void T22_sharedTrunkRestoresPreviouslyUnroutableDemand() throws Exception {
    ObjectNode s = Scenes.n06();
    Scenes.add(s, "far", "oks_connection_point", Geo.point(Scenes.p(50, 250))).put("flow_tph", 1);
    Path input = Scenes.save(temp.resolve("restore.json"), s),
        out = temp.resolve("restore-out.json");
    Rules r = new Rules(java.nio.file.Path.of("config/rules-v5.yaml"));
    new CalculationService().solve(input, out, temp.resolve("restore-diag.json"), r, 1);
    Map<String, Object> report = new Verifier().verify(input, out, r);
    assertTrue((Boolean) report.get("valid"), report.toString());
    assertEquals(
        2, ((Map<?, ?>) ((List<?>) report.get("variants")).get(0)).get("connected_demands"));
  }

  @Test
  void N09_newCameraUsesExistingDnCategoryAndCheckpointIsVerified() throws Exception {
    ObjectNode s = Scenes.collection();
    Scenes.add(s, "src", "source", Geo.point(Scenes.p(-50, 0)));
    Scenes.add(s, "net", "heat_network", Geo.line(Scenes.p(-50, 0), Scenes.p(50, 0)))
        .put("diameter", 300)
        .put("flow_tph", 20);
    Scenes.add(s, "p", "oks_connection_point", Geo.point(Scenes.p(0, 100))).put("flow_tph", 10);
    Path input = Scenes.save(temp.resolve("line-tie.json"), s);
    Rules r = new Rules(java.nio.file.Path.of("config/rules-v5.yaml"));
    try (Dataset d = new Ingest().read(input)) {
      Existing ex = new Existing(d, r);
      Network.Tree tree =
          new Network.Tree(new Existing.Root(d.features.get("net"), Scenes.p(0, 0), 50, 300, 2));
      Dataset.Demand demand = d.demands.get(0);
      Network.Node leaf = new Network.Node("p", Scenes.p(0, 100), List.of(demand), "p");
      tree.edges.add(new Network.Edge(tree.start, leaf, Geo.line(tree.start.p, leaf.p)));
      tree = new Evaluation(d, r, new Router(d, r)).evaluate(tree, false);
      Network.Variant variant = new Exporter(d, ex, r).export(List.of(tree), 1);
      assertEquals(
          0,
          new BigDecimal("5000000")
              .compareTo((BigDecimal) variant.summary.get("chamber_construction_cost")));
      assertEquals(
          0, new BigDecimal("5000000").compareTo((BigDecimal) variant.summary.get("tie_in_cost")));
      assertTrue(new Checkpoint(input, temp.resolve("result.json"), r).save(variant));
      assertTrue(Files.exists(temp.resolve("checkpoint.geojson")));
      assertTrue(
          Json.M.readTree(temp.resolve("checkpoint-state.json").toFile())
              .path("verified")
              .asBoolean());
    }
  }
}
