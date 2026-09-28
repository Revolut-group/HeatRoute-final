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

class ContractRegressionTest {
  @TempDir Path temp;

  @Test
  void T23_coincidentDemandsAndZeroLengthAttachment() throws Exception {
    ObjectNode s = Scenes.n06();
    Scenes.add(s, "p2", "oks_connection_point", Geo.point(Scenes.p(0, 100))).put("flow_tph", 20);
    Path in = Scenes.save(temp.resolve("same.json"), s), out = temp.resolve("same-out.json");
    Rules r = new Rules(java.nio.file.Path.of("config/rules-v5.yaml"));
    new CalculationService().solve(in, out, temp.resolve("diag.json"), r, 1);
    Map<String, Object> report = new Verifier().verify(in, out, r);
    assertTrue((Boolean) report.get("valid"), report.toString());
    Map<?, ?> metric = (Map<?, ?>) ((List<?>) report.get("variants")).get(0);
    assertEquals(2, metric.get("connected_demands"));
    assertEquals(0, new BigDecimal("30").compareTo((BigDecimal) metric.get("connected_flow_tph")));
    s = Scenes.n06();
    ((ObjectNode) s.path("features").get(4)).set("geometry", Geo.json(Geo.point(Scenes.p(0, 0))));
    ((ObjectNode) s.path("features").get(4).path("properties")).put("flow_tph", 30);
    in = Scenes.save(temp.resolve("zero.json"), s);
    new CalculationService().solve(in, out, temp.resolve("zero-diag.json"), r, 1);
    JsonNode result = Json.M.readTree(out.toFile());
    int lines = 0;
    for (JsonNode f : result.path("features"))
      if (f.path("properties").path("object_type").asText().equals("heat_network")) lines++;
    assertEquals(0, lines);
    report = new Verifier().verify(in, out, r);
    assertTrue((Boolean) report.get("valid"), report.toString());
    metric = (Map<?, ?>) ((List<?>) report.get("variants")).get(0);
    assertEquals(1, metric.get("connected_demands"));
    assertEquals(
        0, new BigDecimal("5000000").compareTo((BigDecimal) metric.get("calculated_cost")));
  }

  @Test
  void G08_G09_realBuildingPartsStaySeparate() throws Exception {
    try (Dataset d = new Ingest().read(Path.of("src/test/resources/fixtures/legacy-dataset.geojson"))) {
      Dataset.Obstacle a = d.hosts(d.features.get("12").geometry.getCoordinate()).get(0),
          b = d.hosts(d.features.get("15").geometry.getCoordinate()).get(0);
      assertEquals("92", a.id);
      assertEquals("92", b.id);
      assertNotEquals(a.key, b.key);
      assertEquals(67, d.obstacles.stream().filter(o -> o.id.equals("96")).count());
    }
  }

  @Test
  void T04_existingAxisCrossingDoesNotJoin() throws Exception {
    ObjectNode s = Scenes.collection();
    Scenes.add(s, "src", "source", Geo.point(Scenes.p(-20, 0)));
    Scenes.add(s, "a", "heat_network", Geo.line(Scenes.p(-20, 0), Scenes.p(20, 0)))
        .put("diameter", 80);
    Scenes.add(s, "b", "heat_network", Geo.line(Scenes.p(0, -20), Scenes.p(0, 20)))
        .put("diameter", 80);
    try (Dataset d = new Ingest().read(Scenes.save(temp.resolve("cross.json"), s))) {
      Existing ex = new Existing(d, new Rules(java.nio.file.Path.of("config/rules-v5.yaml")));
      assertTrue(ex.connected(d.features.get("a")));
      assertFalse(ex.connected(d.features.get("b")));
    }
  }

  @Test
  void T11_exactCameraRadiusPolicy() throws Exception {
    Rules r = new Rules(java.nio.file.Path.of("config/rules-v5.yaml"));
    assertTrue(r.nearChamber(9.999));
    assertTrue(r.nearChamber(10));
    assertFalse(r.nearChamber(10.001));
  }

  @Test
  void G21_sharedRootCannotHideSecondIntersection() {
    LineString a = Geo.line(List.of(Scenes.p(0, 0), Scenes.p(20, 20), Scenes.p(40, 20)));
    LineString b = Geo.line(List.of(Scenes.p(0, 0), Scenes.p(20, -20), Scenes.p(30, 30)));
    assertEquals(2, a.intersection(b).getNumPoints());
    assertFalse(JunctionGeometry.fits(a, 2, b, 2, Scenes.p(0, 0)));
    assertTrue(
        JunctionGeometry.fits(
            Geo.line(Scenes.p(0, 0), Scenes.p(0, 100)),
            2,
            Geo.line(Scenes.p(0, 0), Scenes.p(100, 0)),
            2,
            Scenes.p(0, 0)));
  }

  @Test
  void G22_polygonisedTubesDoNotFakeAJunctionOverlap() {
    // measured false rejection (gas/power set): DN200 arriving, DN100 leaving 142 degrees apart
    // with
    // a bend after 6.2 m; the tubes meet only at the chamber, yet the polygon slivers were 1.5e-6
    // m²
    Coordinate at = new Coordinate(414136.58975539193, 6173262.867843252);
    LineString in = Geo.line(new Coordinate(414157.79113075265, 6173255.886868887), at);
    LineString out =
        Geo.line(
            List.of(
                at,
                new Coordinate(414130.715488263, 6173260.792725319),
                new Coordinate(414128.66794862907, 6173255.728295492)));
    assertTrue(
        JunctionGeometry.fits(in, Catalog.pipe(200).width, out, Catalog.pipe(100).width, at));
    // a branch that runs back alongside the trunk after its first run still fails
    LineString back =
        Geo.line(
            List.of(at, new Coordinate(at.x - 3, at.y - 3), new Coordinate(at.x + 12, at.y - 3.4)));
    assertFalse(
        JunctionGeometry.fits(in, Catalog.pipe(200).width, back, Catalog.pipe(100).width, at));
  }

  @Test
  void X01_X02_X03_X07_fullSchemaAndAllMutationClasses() throws Exception {
    ObjectNode scene = Scenes.collection();
    Scenes.add(scene, "src", "source", Geo.point(Scenes.p(-100, 0)));
    Scenes.add(scene, "net", "heat_network", Geo.line(Scenes.p(-100, 0), Scenes.p(120, 0)))
        .put("diameter", 50)
        .put("flow_tph", 5);
    Scenes.add(scene, "ch", "heat_chamber", Geo.point(Scenes.p(0, 0))).put("diameter", 50);
    Scenes.add(scene, "a", "oks_connection_point", Geo.point(Scenes.p(-40, 100)))
        .put("flow_tph", 10);
    Scenes.add(scene, "b", "oks_connection_point", Geo.point(Scenes.p(40, 100)))
        .put("flow_tph", 20);
    Scenes.add(scene, "c", "oks_connection_point", Geo.point(Scenes.p(80, 120)))
        .put("flow_tph", 10);
    Scenes.add(scene, "road", "restriction", Scenes.box(-120, 75, 120, 85))
        .put("restriction_type", "road");
    Scenes.add(scene, "building", "oks_existing", Scenes.box(150, 50, 170, 70));
    Path in = Scenes.save(temp.resolve("mutations-input.json"), scene);
    Rules r = new Rules(java.nio.file.Path.of("config/rules-v5.yaml"));
    try (Dataset d = new Ingest().read(in)) {
      Existing ex = new Existing(d, r);
      Existing.Root root =
          ex.roots(Scenes.p(0, 40)).stream()
              .filter(a -> a.target.id.equals("ch"))
              .findFirst()
              .orElseThrow();
      Network.Tree t = new Network.Tree(root);
      Network.Node branch = new Network.Node("branch", Scenes.p(0, 40));
      t.edges.add(new Network.Edge(t.start, branch, Geo.line(t.start.p, branch.p)));
      for (int i = 0; i < 2; i++) {
        Dataset.Demand dem = d.demands.get(i);
        Network.Node end =
            new Network.Node(
                dem.id, dem.terminals.get(0).geometry.getCoordinate(), List.of(dem), dem.id);
        t.edges.add(
            new Network.Edge(
                branch, end, Geo.line(List.of(branch.p, Scenes.p(i == 0 ? -40 : 40, 40), end.p))));
      }
      Dataset.Feature net = d.features.get("net");
      Network.Tree second = new Network.Tree(new Existing.Root(net, Scenes.p(80, 0), 180, 50, 2));
      Dataset.Demand dem = d.demands.get(2);
      Network.Node leaf = new Network.Node("c", Scenes.p(80, 120), List.of(dem), "c");
      second.edges.add(new Network.Edge(second.start, leaf, Geo.line(second.start.p, leaf.p)));
      Evaluation ev = new Evaluation(d, r, new Router(d, r));
      t = ev.evaluate(t, false);
      second = ev.evaluate(second, false);
      assertTrue(ev.compatible(List.of(t), second));
      Path out = temp.resolve("result.json");
      Json.write(
          out, Exporter.collection(List.of(new Exporter(d, ex, r).export(List.of(t, second), 1))));
      Map<String, Object> report = new Verifier().verify(in, out, r);
      assertTrue((Boolean) report.get("valid"), report.toString());
      ObjectNode original = (ObjectNode) Json.M.readTree(out.toFile());
      Set<String> kinds = new TreeSet<>();
      for (JsonNode f : original.path("features"))
        kinds.add(f.path("properties").path("object_type").asText());
      assertEquals(7, kinds.size());
      List<java.util.function.Consumer<ObjectNode>> mutations = new ArrayList<>();
      mutations.add(n -> props(first(n, "heat_network")).put("cost", 1));
      mutations.add(n -> props(first(n, "heat_network")).put("flow_tph", 1));
      mutations.add(
          n -> {
            ArrayNode fs = (ArrayNode) n.path("features");
            for (int i = 0; i < fs.size(); i++)
              if (fs.get(i).path("properties").path("end_node_id").asText().equals("a")) {
                fs.remove(i);
                break;
              }
          });
      mutations.add(
          n -> {
            ObjectNode line = first(n, "heat_network");
            ArrayNode coordinates = (ArrayNode) line.path("geometry").path("coordinates");
            Coordinate ll = Geo.ll(Scenes.p(160, 60));
            coordinates.insert(1, Json.M.createArrayNode().add(ll.x).add(ll.y));
          });
      mutations.add(
          n -> {
            ArrayNode fs = (ArrayNode) n.path("features");
            for (int i = 0; i < fs.size(); i++)
              if (fs.get(i).path("properties").path("object_type").asText().equals("heat_chamber")
                  && Geo.read(fs.get(i).path("geometry"), "ch").distance(Geo.point(Scenes.p(0, 40)))
                      < 1e-4) {
                fs.remove(i);
                break;
              }
          });
      mutations.add(
          n -> {
            for (JsonNode f : n.path("features"))
              if (f.path("properties").path("laying_method").asText().equals("special")) {
                props((ObjectNode) f).put("laying_method", "base");
                break;
              }
          });
      mutations.add(n -> props(first(n, "tie_in")).put("existing_object_id", "building"));
      mutations.add(
          n -> {
            ObjectNode duplicate = first(n, "heat_network_reconstruction").deepCopy();
            props(duplicate).put("id", "duplicate_reconstruction");
            ((ArrayNode) n.path("features")).add(duplicate);
          });
      mutations.add(n -> props(first(n, "variant_summary")).put("score", 0));
      mutations.add(
          n -> {
            ArrayNode fs = (ArrayNode) n.path("features");
            for (int i = fs.size() - 1; i >= 0; i--)
              if (fs.get(i)
                  .path("properties")
                  .path("object_type")
                  .asText()
                  .equals("heat_chamber_reconstruction")) fs.remove(i);
          });
      for (int i = 0; i < mutations.size(); i++) {
        ObjectNode n = original.deepCopy();
        mutations.get(i).accept(n);
        Json.write(out, n);
        assertFalse(
            (Boolean) new Verifier().verify(in, out, r).get("valid"),
            "Mutation " + i + " accepted");
      }
    }
  }

  static ObjectNode props(ObjectNode n) {
    return (ObjectNode) n.path("properties");
  }

  static ObjectNode first(ObjectNode n, String type) {
    for (JsonNode f : n.path("features"))
      if (f.path("properties").path("object_type").asText().equals(type)) return (ObjectNode) f;
    throw new AssertionError(type);
  }
}
