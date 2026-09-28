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

class CurrentDetailedTest {
  @TempDir Path temp;

  @Test
  void exactAppendixExampleAndMutationChecks() throws Exception {
    Rules rules = new Rules(null);
    ObjectNode scene = Scenes.n06();
    ((ObjectNode) scene.path("features").get(4).path("properties")).put("flow_tph", 20);
    Path input = Scenes.save(temp.resolve("example.json"), scene),
        output = temp.resolve("result.json");
    try (Dataset data = new Ingest().read(input, rules)) {
      Existing ex = new Existing(data, rules);
      Existing.Root root =
          ex.roots(Scenes.p(0, 100)).stream()
              .filter(r -> r.target.id.equals("ch"))
              .findFirst()
              .orElseThrow();
      Network.Tree tree = new Network.Tree(root);
      Dataset.Demand demand = data.demands.get(0);
      Network.Node leaf = new Network.Node("leaf", Scenes.p(0, 100), List.of(demand), "p1");
      tree.edges.add(new Network.Edge(tree.start, leaf, Geo.line(tree.start.p, leaf.p)));
      tree = new Evaluation(data, rules, new Router(data, rules)).evaluate(tree, false);
      Network.Variant variant = new CurrentExporter(data, rules).export(List.of(tree), 1);
      assertEquals(100, tree.edges.get(0).dn);
      assertEquals(
          13974800, ((BigDecimal) variant.summary.get("calculated_cost")).doubleValue(), .01);
      assertEquals(.6912944, variant.score().doubleValue(), 1e-8);
      Json.write(output, Exporter.collection(List.of(variant)));
      assertTrue((Boolean) new Verifier().verify(input, output, rules).get("valid"));
      ObjectNode original = (ObjectNode) Json.M.readTree(output.toFile());
      for (String key : List.of("flow_tph", "diameter", "cost", "length")) {
        ObjectNode changed = original.deepCopy();
        for (JsonNode f : changed.path("features"))
          if (f.path("properties").path("object_type").asText().equals("heat_network")) {
            ((ObjectNode) f.path("properties")).put(key, key.equals("diameter") ? 125 : 999);
            break;
          }
        Json.write(output, changed);
        assertFalse((Boolean) new Verifier().verify(input, output, rules).get("valid"), key);
      }
    }
  }

  @Test
  void overlapIsPiecewiseMaximumAndStillSplitsAtEveryBoundary() throws Exception {
    Rules rules = new Rules(null);
    ObjectNode scene = Scenes.n06();
    Scenes.add(scene, "gas", "restriction", Geo.line(Scenes.p(-20, 50), Scenes.p(20, 50)))
        .put("restriction_type", "gas_pipeline");
    Scenes.add(scene, "power", "restriction", Geo.line(Scenes.p(-20, 52), Scenes.p(20, 52)))
        .put("restriction_type", "power_cable");
    try (Dataset d = new Ingest().read(Scenes.save(temp.resolve("overlap.json"), scene), rules)) {
      Existing.Root root = new Existing.Root(d.features.get("ch"), Scenes.p(0, 0), 0, 150, 2);
      List<Intervals.Zone> zones =
          new GeometryRules(d, rules)
              .events(Geo.line(Scenes.p(0, 0), Scenes.p(0, 100)), 100, Scenes.p(0, 100), root);
      assertEquals(3, zones.size());
      assertEquals(1.25, Intervals.factor(zones, 49));
      assertEquals(1.25, Intervals.factor(zones, 51));
      assertEquals(1.15, Intervals.factor(zones, 53));
      assertEquals(1, Intervals.factor(zones, 55));
    }
  }

  @Test
  void noBendInsideThreeMetreRoadExtension() throws Exception {
    Rules rules = new Rules(null);
    ObjectNode scene = Scenes.n06();
    Scenes.add(scene, "road", "restriction", Scenes.box(-40, 40, 40, 60))
        .put("restriction_type", "road");
    try (Dataset d = new Ingest().read(Scenes.save(temp.resolve("extent.json"), scene), rules)) {
      LineString line = Geo.line(List.of(Scenes.p(-10, 20), Scenes.p(0, 38), Scenes.p(0, 100)));
      Failure failure =
          assertThrows(
              Failure.class,
              () -> new GeometryRules(d, rules).events(line, 100, Scenes.p(0, 100), null));
      assertEquals("SPECIAL_STRAIGHT", failure.code);
    }
  }

  @Test
  void chamberCannotHideAnObtuseTurn() throws Exception {
    Rules rules = new Rules(null);
    ObjectNode scene = Scenes.n06();
    ((ObjectNode) scene.path("features").get(4))
        .set("geometry", Geo.json(Geo.point(Scenes.p(-30, 80))));
    Path input = Scenes.save(temp.resolve("junction.json"), scene);
    try (Dataset data = new Ingest().read(input, rules)) {
      Existing.Root root = new Existing.Root(data.features.get("ch"), Scenes.p(0, 0), 0, 150, 2);
      Network.Tree tree = new Network.Tree(root);
      Network.Node node = new Network.Node("b", Scenes.p(0, 100));
      Network.Node leaf =
          new Network.Node("leaf", Scenes.p(-30, 80), List.of(data.demands.get(0)), "p1");
      tree.edges.add(new Network.Edge(tree.start, node, Geo.line(tree.start.p, node.p)));
      tree.edges.add(new Network.Edge(node, leaf, Geo.line(node.p, leaf.p)));
      assertEquals(
          "TURN_ANGLE",
          assertThrows(
                  Failure.class,
                  () -> new Evaluation(data, rules, new Router(data, rules)).evaluate(tree, false))
              .code);
    }
  }

  @Test
  void coincidentPointsAreIndependentAndLegacyMetadataIsIgnored() throws Exception {
    ObjectNode scene = Scenes.n06();
    ((ObjectNode) scene.path("features").get(4).path("properties"))
        .put("oks_id", "unknown-legacy-reference");
    Scenes.add(scene, "p2", "oks_connection_point", Geo.point(Scenes.p(0, 100)))
        .put("flow_tph", 20);
    ((ObjectNode) scene.path("features").get(1).path("properties"))
        .put("upstream_object_id", "not-used-in-current-model");
    Rules rules = new Rules(null);
    Path input = Scenes.save(temp.resolve("coincident.json"), scene),
        output = temp.resolve("coincident-out.json");
    new CalculationService().solve(input, output, temp.resolve("diag.json"), rules, 1);
    Map<String, Object> report = new Verifier().verify(input, output, rules);
    assertTrue((Boolean) report.get("valid"), report.toString());
    Map<?, ?> summary = (Map<?, ?>) ((List<?>) report.get("variants")).get(0);
    assertEquals(2, summary.get("connected_demands"));
    assertEquals(0, new BigDecimal("30").compareTo((BigDecimal) summary.get("connected_flow_tph")));
  }

  @Test
  void zeroLengthAndFractionalNumericId() throws Exception {
    ObjectNode scene = Scenes.n06();
    ObjectNode point = (ObjectNode) scene.path("features").get(4);
    point.set("geometry", Geo.json(Geo.point(Scenes.p(0, 0))));
    ((ObjectNode) point.path("properties")).put("id", new BigDecimal("12.50"));
    Rules rules = new Rules(null);
    Path input = Scenes.save(temp.resolve("zero.json"), scene),
        output = temp.resolve("zero-out.json");
    new CalculationService().solve(input, output, temp.resolve("diag.json"), rules, 1);
    Map<String, Object> report = new Verifier().verify(input, output, rules);
    assertTrue((Boolean) report.get("valid"), report.toString());
    assertEquals(
        1, ((Map<?, ?>) ((List<?>) report.get("variants")).get(0)).get("connected_demands"));
  }

  @Test
  void nearestInputCannotTransitAnotherWingOfOwnPolygon() throws Exception {
    Polygon host =
        Geo.GF.createPolygon(
            new Coordinate[] {
              Scenes.p(-20, 0),
              Scenes.p(20, 0),
              Scenes.p(20, 40),
              Scenes.p(10, 40),
              Scenes.p(10, 10),
              Scenes.p(-10, 10),
              Scenes.p(-10, 40),
              Scenes.p(-20, 40),
              Scenes.p(-20, 0)
            });
    Coordinate terminal = Scenes.p(-15, 30);
    assertFalse(TerminalAccess.nearest(Geo.line(Scenes.p(40, 30), terminal), terminal, host));
  }
}
