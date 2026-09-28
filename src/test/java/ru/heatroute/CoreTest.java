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

class CoreTest {
  @TempDir Path temp;

  @Test
  void projectionOracle() {
    Coordinate c = Geo.xy(37.6344054041544, 55.6994810644531);
    assertEquals(414174.10708232445, c.x, 1e-5);
    assertEquals(6173478.452612657, c.y, 1e-5);
    Coordinate ll = Geo.ll(c);
    assertEquals(37.6344054041544, ll.x, 1e-10);
    assertEquals(55.6994810644531, ll.y, 1e-10);
  }

  @Test
  void G01_G08_G09_T01_T02_T06_realAudit() throws Exception {
    Dataset d = new Ingest().read(Path.of("src/test/resources/fixtures/legacy-dataset.geojson"));
    assertEquals(144, d.features.size());
    assertEquals(17, d.demands.size());
    assertEquals(0, d.totalFlow().compareTo(new BigDecimal("488.72")));
    assertEquals(244, d.buildingParts);
    assertEquals(74, d.holeCount);
    assertEquals(6288, d.coordinateCount);
    assertEquals(
        1459.580055, d.networks.stream().mapToDouble(f -> f.geometry.getLength()).sum(), 1e-5);
    assertNotEquals(
        d.hosts(d.features.get("12").geometry.getCoordinate()).get(0).key,
        d.hosts(d.features.get("15").geometry.getCoordinate()).get(0).key);
    assertEquals(67, d.features.get("96").geometry.getNumGeometries());
    Existing ex = new Existing(d, new Rules(java.nio.file.Path.of("config/rules-v5.yaml")));
    assertEquals(1, ex.components);
    assertEquals(30, ex.nodes.size());
    assertEquals(29, ex.edges.size());
    assertTrue(d.reduced);
    assertEquals(false, ex.capabilities().get("reconstruction_evaluable"));
  }

  @Test
  void G04_G05_largeIdAndCollision() throws Exception {
    ObjectNode s = Scenes.n06();
    ObjectNode p = (ObjectNode) s.path("features").get(4).path("properties");
    p.put("id", new BigInteger("900719925474099312345"));
    Dataset d = new Ingest().read(Scenes.save(temp.resolve("large.json"), s));
    assertTrue(d.features.containsKey("900719925474099312345"));
    p.put("id", 1);
    Scenes.add(s, "1", "source", Geo.point(Scenes.p(500, 0)));
    Failure f =
        assertThrows(
            Failure.class, () -> new Ingest().read(Scenes.save(temp.resolve("duplicate.json"), s)));
    assertEquals("DUPLICATE_ID", f.code);
  }

  @Test
  void G02_crsModes() throws Exception {
    ObjectNode s = Scenes.n06();
    for (String crs : List.of("CRS84", "EPSG:4326", "urn:ogc:def:crs:OGC:1.3:CRS84")) {
      s.putObject("crs").putObject("properties").put("name", crs);
      assertEquals(1, new Ingest().read(Scenes.save(temp.resolve("crs.json"), s)).demands.size());
    }
    s.putObject("crs").putObject("properties").put("name", "EPSG:3857");
    assertEquals(
        "UNSUPPORTED_CRS",
        assertThrows(
                Failure.class, () -> new Ingest().read(Scenes.save(temp.resolve("crs.json"), s)))
            .code);
  }

  @Test
  void G06_invalidGeometry() throws Exception {
    ObjectNode s = Scenes.n06();
    Polygon bow =
        Geo.GF.createPolygon(
            new Coordinate[] {
              Scenes.p(0, 0), Scenes.p(10, 10), Scenes.p(10, 0), Scenes.p(0, 10), Scenes.p(0, 0)
            });
    Scenes.add(s, "bad", "restriction", bow).put("restriction_type", "water");
    assertEquals(
        "INVALID_GEOMETRY",
        assertThrows(
                Failure.class,
                () -> new Ingest().read(Scenes.save(temp.resolve("invalid.json"), s)))
            .code);
  }

  @Test
  void T05_upstreamCycleAndDangling() throws Exception {
    ObjectNode s = Scenes.n06();
    ObjectNode p = (ObjectNode) s.path("features").get(1).path("properties");
    p.put("upstream_object_id", "ch");
    assertEquals(
        "UPSTREAM_CYCLE",
        assertThrows(
                Failure.class, () -> new Ingest().read(Scenes.save(temp.resolve("cycle.json"), s)))
            .code);
    p.put("upstream_object_id", "absent");
    assertEquals(
        "DANGLING_REFERENCE",
        assertThrows(
                Failure.class,
                () -> new Ingest().read(Scenes.save(temp.resolve("dangling.json"), s)))
            .code);
  }

  @Test
  void unknownRuleRejected() throws Exception {
    String yaml = Files.readString(Path.of("config/rules-v5.yaml")) + "\ntypo: true\n";
    Path p = temp.resolve("rules.yaml");
    Files.writeString(p, yaml);
    assertEquals("INVALID_ATTRIBUTE", assertThrows(Failure.class, () -> new Rules(p)).code);
  }

  @Test
  void G07_G09_G12_G15_hostAndClearance() throws Exception {
    ObjectNode s = Scenes.n06();
    Scenes.add(
            s,
            "host",
            "restriction",
            Geo.GF.createMultiPolygon(
                new Polygon[] {Scenes.box(-10, 90, 10, 110), Scenes.box(20, 30, 40, 50)}))
        .put("restriction_type", "oks");
    Dataset d = new Ingest().read(Scenes.save(temp.resolve("host.json"), s));
    GeometryRules checks =
        new GeometryRules(d, new Rules(java.nio.file.Path.of("config/rules-v5.yaml")));
    assertTrue(checks.segment(Scenes.p(0, 80), Scenes.p(0, 100), 80, Scenes.p(0, 100), null));
    assertFalse(checks.segment(Scenes.p(30, 20), Scenes.p(30, 60), 80, Scenes.p(0, 100), null));
    assertFalse(checks.segment(Scenes.p(15, 30), Scenes.p(15, 50), 80, null, null));
  }

  @Test
  void G23_turns() {
    assertEquals(0, Geo.turn(Scenes.p(0, 0), Scenes.p(1, 0), Scenes.p(2, 0)), 1e-7);
    assertEquals(45, Geo.turn(Scenes.p(0, 0), Scenes.p(1, 0), Scenes.p(2, 1)), 1e-7);
    assertEquals(90, Geo.turn(Scenes.p(0, 0), Scenes.p(1, 0), Scenes.p(1, 1)), 1e-7);
    assertEquals(
        60, Geo.turn(Scenes.p(0, 0), Scenes.p(1, 0), Scenes.p(1.5, Math.sqrt(3) / 2)), 1e-6);
  }

  @Test
  void N06_X03_endToEndAndMutations() throws Exception {
    Path input = Scenes.save(temp.resolve("n06.geojson"), Scenes.n06());
    new CalculationService()
        .solve(
            input,
            temp.resolve("result.geojson"),
            temp.resolve("diagnostics.json"),
            new Rules(java.nio.file.Path.of("config/rules-v5.yaml")),
            1);
    ObjectNode output = (ObjectNode) Json.M.readTree(temp.resolve("result.geojson").toFile());
    ArrayNode fs = (ArrayNode) output.get("features");
    assertEquals(3, fs.size());
    JsonNode summary = fs.get(2).get("properties");
    assertEquals(0.673884, summary.path("score").asDouble(), 1e-8);
    assertEquals(13353000, summary.path("calculated_cost").asDouble(), .1);
    assertEquals(100, summary.path("length").asDouble(), 1e-6);
    assertTrue(valid(input, output));
    ObjectNode bad = output.deepCopy();
    ((ObjectNode) bad.path("features").get(0).path("properties")).put("cost", 1);
    assertFalse(valid(input, bad));
    bad = output.deepCopy();
    ((ObjectNode) bad.path("features").get(0).path("properties")).put("flow_tph", 11);
    assertFalse(valid(input, bad));
    bad = output.deepCopy();
    ((ArrayNode) bad.get("features")).remove(0);
    assertFalse(valid(input, bad));
    bad = output.deepCopy();
    ((ObjectNode) bad.path("features").get(1).path("properties"))
        .put("existing_object_id", "missing");
    assertFalse(valid(input, bad));
    bad = output.deepCopy();
    ((ObjectNode) bad.path("features").get(2).path("properties")).put("score", 0);
    assertFalse(valid(input, bad));
    bad = output.deepCopy();
    ((ObjectNode) bad.path("features").get(0).path("properties")).put("extra", 42);
    assertFalse(valid(input, bad));
  }

  private boolean valid(Path input, ObjectNode output) throws Exception {
    Path p = temp.resolve("mutation.geojson");
    Json.write(p, output);
    return Boolean.TRUE.equals(
        new Verifier()
            .verify(input, p, new Rules(java.nio.file.Path.of("config/rules-v5.yaml")))
            .get("valid"));
  }
}
