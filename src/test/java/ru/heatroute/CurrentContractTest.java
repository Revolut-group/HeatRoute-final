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

class CurrentContractTest {
  @TempDir Path temp;

  Rules rules() throws Exception {
    return new Rules(Path.of("config/rules-2026-09-19.yaml"));
  }

  ObjectNode result(ObjectNode scene, String name) throws Exception {
    Path input = Scenes.save(temp.resolve(name + ".json"), scene),
        output = temp.resolve(name + "-out.json");
    new CalculationService().solve(input, output, temp.resolve(name + "-diag.json"), rules(), 1);
    Map<String, Object> report = new Verifier().verify(input, output, rules());
    assertTrue((Boolean) report.get("valid"), report.toString());
    return (ObjectNode) Json.M.readTree(output.toFile());
  }

  JsonNode summary(ObjectNode result) {
    for (JsonNode f : result.path("features"))
      if (f.path("properties").path("object_type").asText().equals("variant_summary"))
        return f.path("properties");
    throw new AssertionError();
  }

  @Test
  void defaultUsesNewAppendixAndExampleCost() throws Exception {
    assertTrue(rules().current());
    ObjectNode scene = Scenes.n06();
    ((ObjectNode) scene.path("features").get(4).path("properties")).put("flow_tph", 20);
    ObjectNode output = result(scene, "example");
    JsonNode summary = summary(output);
    assertEquals(0, summary.path("unconnected_oks_ids").size());
    // Optimizer may choose a new camera if it is cheaper; all chamber and tie costs belong to
    // construction.
    BigDecimal sum = BigDecimal.ZERO;
    for (JsonNode f : output.path("features")) {
      String type = f.path("properties").path("object_type").asText();
      assertTrue(
          Set.of("heat_network", "heat_chamber", "technical_node", "variant_summary")
              .contains(type));
      if (Set.of("heat_network", "heat_chamber").contains(type))
        sum = sum.add(f.path("properties").path("cost").decimalValue());
    }
    sum = sum.add(summary.path("existing_chamber_tie_in_cost").decimalValue());
    assertEquals(0, sum.compareTo(summary.path("construction_cost").decimalValue()));
  }

  @Test
  void expensiveAvailableConsumerMustStillBeConnected() throws Exception {
    ObjectNode scene = Scenes.n06();
    ObjectNode point = (ObjectNode) scene.path("features").get(4);
    point.set("geometry", Geo.json(Geo.point(Scenes.p(0, 2200))));
    ((ObjectNode) point.path("properties")).put("flow_tph", 400);
    JsonNode sum = summary(result(scene, "expensive"));
    assertEquals(0, sum.path("unconnected_oks_ids").size());
    assertTrue(sum.path("calculated_cost").doubleValue() > 300000000);
  }

  @Test
  void multipleUpliftsAndParallelBranchesUsePathLength() throws Exception {
    ObjectNode scene = Scenes.n06();
    Path input = Scenes.save(temp.resolve("diameters.json"), scene);
    try (Dataset data = new Ingest().read(input)) {
      Existing.Root root = new Existing.Root(data.features.get("ch"), Scenes.p(0, 0), 0, 100, 2);
      Network.Tree longTree = new Network.Tree(root);
      Network.Node end = new Network.Node("end", Scenes.p(0, 1000));
      Network.Edge longEdge =
          new Network.Edge(longTree.start, end, Geo.line(longTree.start.p, end.p));
      longEdge.flow = BigDecimal.ONE;
      longTree.edges.add(longEdge);
      PathDiameters.assign(longTree);
      assertEquals(200, longEdge.dn);
      Network.Tree tree = new Network.Tree(root);
      Network.Node branch = new Network.Node("b", Scenes.p(0, 40));
      Network.Edge trunk = new Network.Edge(tree.start, branch, Geo.line(tree.start.p, branch.p));
      trunk.flow = BigDecimal.valueOf(2);
      tree.edges.add(trunk);
      for (int sign : new int[] {-1, 1}) {
        Network.Node leaf = new Network.Node("leaf" + sign, Scenes.p(sign * 100, 40));
        Network.Edge edge = new Network.Edge(branch, leaf, Geo.line(branch.p, leaf.p));
        edge.flow = BigDecimal.ONE;
        tree.edges.add(edge);
      }
      PathDiameters.assign(tree);
      for (Network.Edge edge : tree.edges)
        assertEquals(50, edge.dn, "140 m paths fit DN50 although total tree is 240 m");
    }
  }

  @Test
  void nearestHostBoundaryAndTurnLimitAreEnforced() throws Exception {
    ObjectNode scene = Scenes.n06();
    Scenes.add(scene, "host", "restriction", Scenes.box(-20, 90, 20, 120))
        .put("restriction_type", "oks");
    Path input = Scenes.save(temp.resolve("host.json"), scene);
    try (Dataset d = new Ingest().read(input)) {
      GeometryRules geometry = new GeometryRules(d, rules());
      Existing.Root root = new Existing.Root(d.features.get("ch"), Scenes.p(0, 0), 0, 100, 2);
      assertDoesNotThrow(
          () ->
              geometry.events(
                  Geo.line(Scenes.p(0, 0), Scenes.p(0, 100)), 100, Scenes.p(0, 100), root));
      assertEquals(
          "HOST_NEAREST_BOUNDARY",
          assertThrows(
                  Failure.class,
                  () ->
                      geometry.events(
                          Geo.line(Scenes.p(50, 100), Scenes.p(0, 100)),
                          100,
                          Scenes.p(0, 100),
                          null))
              .code);
      assertEquals(
          "TURN_ANGLE",
          assertThrows(
                  Failure.class,
                  () ->
                      geometry.events(
                          Geo.line(List.of(Scenes.p(50, 50), Scenes.p(70, 50), Scenes.p(60, 60))),
                          100,
                          null,
                          null))
              .code);
    }
  }

  @Test
  void polygonAngleUsesEntryBoundaryAndNoTurnPremium() throws Exception {
    ObjectNode scene = Scenes.n06();
    Scenes.add(scene, "road", "restriction", Scenes.box(-40, 40, 40, 60))
        .put("restriction_type", "road");
    try (Dataset data = new Ingest().read(Scenes.save(temp.resolve("road.json"), scene))) {
      GeometryRules geometry = new GeometryRules(data, rules());
      Existing.Root root = new Existing.Root(data.features.get("ch"), Scenes.p(0, 0), 0, 100, 2);
      List<Intervals.Zone> zones =
          geometry.events(Geo.line(Scenes.p(0, 0), Scenes.p(0, 100)), 100, Scenes.p(0, 100), root);
      assertEquals(1, zones.size());
      assertEquals(37, zones.get(0).a, 1e-5);
      assertEquals(63, zones.get(0).b, 1e-5);
      assertEquals(1.6, zones.get(0).k);
      assertEquals(1, rules().number("cost.nonstandard_turn_factor"));
    }
  }

  @Test
  void savedFileRejectsCostMutationAndAcceptsReversedLines() throws Exception {
    ObjectNode scene = Scenes.n06(), output = result(scene, "mutations");
    Path input = temp.resolve("mutations.json"), path = temp.resolve("reverse.json");
    for (JsonNode f : output.path("features"))
      if (f.path("properties").path("object_type").asText().equals("heat_network")) {
        ObjectNode p = (ObjectNode) f.path("properties");
        JsonNode start = p.get("start_node_id");
        p.set("start_node_id", p.get("end_node_id"));
        p.set("end_node_id", start);
        ArrayNode coords = (ArrayNode) f.path("geometry").path("coordinates");
        List<JsonNode> reversed = new ArrayList<>();
        coords.forEach(reversed::add);
        Collections.reverse(reversed);
        coords.removeAll();
        reversed.forEach(coords::add);
      }
    Json.write(path, output);
    Map<String, Object> report = new Verifier().verify(input, path, rules());
    assertTrue((Boolean) report.get("valid"), report.toString());
    ((ObjectNode) summary(output)).put("construction_cost", 0);
    Json.write(path, output);
    assertFalse((Boolean) new Verifier().verify(input, path, rules()).get("valid"));
  }

  @Test
  void numericIdsStayNumericAndNewRootHasNoAdditionalTieCost() throws Exception {
    ObjectNode scene = Scenes.collection();
    Scenes.add(scene, "src", "source", Geo.point(Scenes.p(-50, 0)));
    Scenes.add(scene, "net", "heat_network", Geo.line(Scenes.p(-50, 0), Scenes.p(50, 0)))
        .put("diameter", 300)
        .put("flow_tph", 10000);
    ObjectNode point = Scenes.add(scene, "17", "oks_connection_point", Geo.point(Scenes.p(0, 100)));
    point.put("id", 17);
    point.put("flow_tph", 10);
    ObjectNode output = result(scene, "numeric");
    JsonNode sum = summary(output);
    assertEquals(0, sum.path("existing_chamber_tie_in_count").asInt());
    assertEquals(0, sum.path("existing_chamber_tie_in_cost").decimalValue().signum());
    assertEquals(
        0,
        new BigDecimal("5000000").compareTo(sum.path("chamber_construction_cost").decimalValue()));
    assertTrue(
        output.path("features").findValues("end_node_id").stream().anyMatch(JsonNode::isNumber));
  }
}
