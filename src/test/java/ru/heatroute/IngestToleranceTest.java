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

/**
 * A4: real-world input defects become input_audit warnings instead of aborting the run
 * (clarifications 9 and 17).
 */
class IngestToleranceTest {
  @TempDir Path temp;

  private Dataset read(ObjectNode s, String name, Rules r) throws Exception {
    return new Ingest().read(Scenes.save(temp.resolve(name), s), r);
  }

  private Rules rules(String yaml) throws Exception {
    Path p = temp.resolve("rules-" + Math.abs(yaml.hashCode()) + ".yaml");
    Files.writeString(p, yaml);
    return new Rules(p);
  }

  private static ObjectNode last(ObjectNode s) {
    ArrayNode fs = (ArrayNode) s.get("features");
    return (ObjectNode) fs.get(fs.size() - 1);
  }

  private static int warnings(Dataset d, String code) {
    return d.warningCounts.getOrDefault(code, 0);
  }

  private static List<Dataset.Obstacle> parts(Dataset d, String id) {
    List<Dataset.Obstacle> o = new ArrayList<>();
    for (Dataset.Obstacle x : d.obstacles) if (x.id.equals(id)) o.add(x);
    return o;
  }

  private static ObjectNode props(ObjectNode s, int i) {
    return (ObjectNode) s.path("features").get(i).path("properties");
  }

  @Test
  void restrictionTypesAreNormalizedSkippedOrForbidden() throws Exception {
    ObjectNode s = Scenes.n06();
    Scenes.add(s, "cem", "restriction", Scenes.box(100, 100, 110, 110))
        .put("restriction_type", "cemetery");
    Scenes.add(s, "road", "restriction", Scenes.box(-30, 40, 30, 50))
        .put("restriction_type", " Road ");
    Scenes.add(s, "none", "restriction", Scenes.box(200, 200, 210, 210));
    Scenes.add(s, "hn", "restriction", Geo.line(Scenes.p(-80, -40), Scenes.p(80, -40)))
        .put("restriction_type", "heat_network")
        .put("diameter", 350);
    Scenes.add(s, "consumer", "heat_consumer", Geo.point(Scenes.p(300, 300)));
    try (Dataset d = read(s, "types.json", new Rules(null))) {
      assertFalse(d.features.containsKey("cem"));
      assertFalse(d.features.containsKey("none"));
      assertTrue(d.containsId("cem") && d.containsId("none") && d.containsId("consumer"));
      assertEquals("road", parts(d, "road").get(0).type);
      assertEquals(1, warnings(d, "RESTRICTION_TYPE_NORMALIZED"));
      assertEquals(2, warnings(d, "UNSUPPORTED_RESTRICTION"));
      assertEquals(1, warnings(d, "UNSUPPORTED_OBJECT_TYPE"));
      Dataset.Obstacle hn = parts(d, "hn").get(0);
      assertEquals("heat_network", hn.type);
      assertEquals(Catalog.pipe(400).width, hn.width, 1e-12);
      assertEquals(2, d.networks.size());
      assertTrue(((List<?>) d.audit().get("warnings")).size() >= 5);
    }
    try (Dataset d = read(s, "forbid.json", rules("input:\n  unknown_restriction: forbid_1m\n"))) {
      assertEquals("prohibited_site", parts(d, "cem").get(0).type);
      assertEquals("prohibited_site", parts(d, "none").get(0).type);
      assertEquals(1, parts(d, "cem").get(0).clearance(150), 0);
    }
    assertEquals(
        "UNSUPPORTED_RESTRICTION",
        assertThrows(
                Failure.class,
                () -> read(s, "error.json", rules("input:\n  unknown_restriction: error\n")))
            .code);
    assertEquals(
        "INVALID_ATTRIBUTE",
        assertThrows(Failure.class, () -> rules("input:\n  unknown_restriction: maybe\n")).code);
    assertEquals(
        "UNSUPPORTED_RESTRICTION",
        assertThrows(
                Failure.class, () -> read(s, "v5.json", new Rules(Path.of("config/rules-v5.yaml"))))
            .code);
    new Rules(Path.of("config/rules-2026-09-19.yaml"));
  }

  @Test
  void existingDiametersAreParsedAndMappedToTheCatalog() throws Exception {
    ObjectNode s = Scenes.n06();
    props(s, 1).put("diameter", 350);
    props(s, 3).remove("diameter");
    Scenes.add(s, "str", "heat_network", Geo.line(Scenes.p(50, 0), Scenes.p(80, 0)))
        .put("diameter", "400");
    Scenes.add(s, "float", "heat_network", Geo.line(Scenes.p(80, 0), Scenes.p(100, 0)))
        .put("diameter", 400.0);
    Scenes.add(s, "small", "heat_network", Geo.line(Scenes.p(100, 0), Scenes.p(120, 0)))
        .put("diameter", 32);
    Scenes.add(s, "huge", "heat_network", Geo.line(Scenes.p(120, 0), Scenes.p(140, 0)))
        .put("diameter", 2000);
    Scenes.add(s, "alone", "heat_network", Geo.line(Scenes.p(0, 300), Scenes.p(10, 300)))
        .put("diameter", "n/a");
    try (Dataset d = read(s, "dn.json", new Rules(null))) {
      Map<String, Integer> dn = new TreeMap<>();
      for (Dataset.Feature f : d.networks) dn.put(f.id, f.dn());
      assertEquals(
          Map.of(
              "net_in", 400, "net_out", 400, "str", 400, "float", 400, "small", 50, "huge", 1400,
              "alone", 300),
          dn);
      assertEquals(Catalog.pipe(400).width, parts(d, "net_in").get(0).width, 1e-12);
      assertEquals(0, Catalog.chamber(350).compareTo(Catalog.chamber(dn.get("net_in"))));
      assertEquals(5, warnings(d, "DN_NORMALIZED"));
      assertEquals(2, warnings(d, "DN_MISSING"));
      assertEquals(2, warnings(d, "DN_ASSUMED"));
      Existing ex = new Existing(d, new Rules(null));
      assertEquals(400, ex.chamberDn(d.features.get("ch")));
    }
    assertEquals(
        "INVALID_ATTRIBUTE",
        assertThrows(
                Failure.class,
                () -> read(s, "dn-v5.json", new Rules(Path.of("config/rules-v5.yaml"))))
            .code);
  }

  @Test
  void invalidAndUnusualRestrictionGeometriesAreRepairedOrSkipped() throws Exception {
    ObjectNode s = Scenes.n06();
    Scenes.add(
            s,
            "bow",
            "restriction",
            Geo.GF.createPolygon(
                new Coordinate[] {
                  Scenes.p(100, 100),
                  Scenes.p(110, 110),
                  Scenes.p(110, 100),
                  Scenes.p(100, 110),
                  Scenes.p(100, 100)
                }))
        .put("restriction_type", "water");
    Scenes.add(
            s,
            "overlap",
            "restriction",
            Geo.GF.createMultiPolygon(
                new Polygon[] {Scenes.box(200, 0, 210, 10), Scenes.box(205, 0, 215, 10)}))
        .put("restriction_type", "oks");
    Scenes.add(
            s,
            "touch",
            "restriction",
            Geo.GF.createPolygon(
                new Coordinate[] {
                  Scenes.p(300, 0),
                  Scenes.p(310, 0),
                  Scenes.p(310, 10),
                  Scenes.p(305, 0),
                  Scenes.p(300, 10),
                  Scenes.p(300, 0)
                }))
        .put("restriction_type", "park");
    Scenes.add(s, "null", "restriction", Scenes.box(0, 0, 1, 1)).put("restriction_type", "park");
    last(s).set("geometry", NullNode.instance);
    Scenes.add(s, "point", "restriction", Geo.point(Scenes.p(400, 400)))
        .put("restriction_type", "park");
    Scenes.add(s, "collection", "restriction", Scenes.box(0, 0, 1, 1))
        .put("restriction_type", "park");
    ObjectNode gc = Json.M.createObjectNode();
    gc.put("type", "GeometryCollection");
    gc.putArray("geometries")
        .add(Geo.json(Scenes.box(500, 0, 510, 10)))
        .add(Geo.json(Geo.point(Scenes.p(520, 0))))
        .add(Geo.json(Geo.line(Scenes.p(530, 0), Scenes.p(540, 0))));
    last(s).set("geometry", gc);
    Scenes.add(s, "gas", "restriction", Scenes.box(600, 0, 610, 10))
        .put("restriction_type", "gas_pipeline");
    Scenes.add(
            s,
            "power",
            "restriction",
            Geo.GF.createMultiPolygon(
                new Polygon[] {Scenes.box(700, 0, 710, 10), Scenes.box(720, 0, 730, 10)}))
        .put("restriction_type", "power_cable");
    Scenes.add(s, "oksline", "restriction", Geo.line(Scenes.p(800, 0), Scenes.p(810, 0)))
        .put("restriction_type", "oks");
    // repair is an explicit option; the default rejects (organisers' Q&A: invalid input geometry
    // gets a diagnostic error, no automatic fixing)
    try (Dataset d = read(s, "geometry.json", rules("input:\n  invalid_geometry: repair\n"))) {
      for (String id : List.of("bow", "overlap", "touch"))
        for (Dataset.Obstacle o : parts(d, id))
          assertTrue(o.geometry.isValid() && o.geometry.getDimension() == 2, id);
      assertEquals(2, parts(d, "bow").size());
      assertEquals(1, parts(d, "overlap").size());
      assertEquals(150, parts(d, "overlap").get(0).geometry.getArea(), 1e-3);
      for (String id : List.of("null", "point", "oksline")) {
        assertTrue(parts(d, id).isEmpty(), id);
        assertTrue(d.containsId(id));
      }
      List<Dataset.Obstacle> gcParts = parts(d, "collection");
      assertEquals(2, gcParts.size());
      assertEquals(2, gcParts.get(0).geometry.getDimension());
      assertEquals(1, gcParts.get(1).geometry.getDimension());
      assertEquals(1, parts(d, "gas").size());
      assertEquals(1, parts(d, "gas").get(0).geometry.getDimension());
      assertEquals(40, parts(d, "gas").get(0).geometry.getLength(), 1e-6);
      assertEquals(.4, parts(d, "gas").get(0).width, 0);
      assertEquals(2, parts(d, "power").size());
      assertFalse(d.features.containsKey("power~part1"));
      for (Dataset.Obstacle o : parts(d, "power")) assertEquals("power_cable", o.type);
      assertEquals(4, warnings(d, "INVALID_GEOMETRY"));
      assertEquals(3, warnings(d, "RESTRICTION_SKIPPED"));
      assertEquals(3, warnings(d, "GEOMETRY_PART_DROPPED"));
      assertEquals(1, warnings(d, "GEOMETRY_COLLECTION"));
      assertEquals(2, warnings(d, "LINEAR_RESTRICTION_POLYGON"));
      assertEquals(
          "gas_pipeline",
          d.near(new Envelope(Scenes.p(605, 5)), 6).stream()
              .filter(o -> o.id.equals("gas"))
              .findFirst()
              .orElseThrow()
              .type);
    }
    assertEquals(
        "INVALID_GEOMETRY",
        assertThrows(
                Failure.class,
                () -> read(s, "strict.json", new Rules(Path.of("config/rules-v5.yaml"))))
            .code);
    Failure byDefault = assertThrows(Failure.class, () -> read(s, "default.json", new Rules(null)));
    assertEquals("INVALID_GEOMETRY", byDefault.code);
    assertNotNull(byDefault.featureId);
  }

  @Test
  void multiLineStringNetworkIsSplitWithoutLeakingInternalIds() throws Exception {
    ObjectNode s = Scenes.n06();
    ((ObjectNode) s.path("features").get(3))
        .set(
            "geometry",
            Geo.json(
                Geo.GF.createMultiLineString(
                    new LineString[] {
                      Geo.line(Scenes.p(0, 0), Scenes.p(25, 0)),
                      Geo.line(Scenes.p(25, 0), Scenes.p(50, 0))
                    })));
    Rules r = new Rules(null);
    Path input = Scenes.save(temp.resolve("mls.json"), s), out = temp.resolve("mls-out.json");
    try (Dataset d = new Ingest().read(input, r)) {
      assertEquals(3, d.networks.size());
      assertTrue(d.features.containsKey("net_out~part1"));
      assertEquals(1, warnings(d, "MULTILINE_NETWORK_SPLIT"));
      assertEquals(1, new Existing(d, r).components);
    }
    new CalculationService().solve(input, out, temp.resolve("mls-diag.json"), r, 1);
    assertFalse(Files.readString(out).contains("~part"));
    assertTrue((Boolean) new Verifier().verify(input, out, r).get("valid"));
    assertTrue(Files.readString(temp.resolve("mls-diag.json")).contains("MULTILINE_NETWORK_SPLIT"));
    assertEquals(
        "INVALID_GEOMETRY",
        assertThrows(
                Failure.class,
                () -> new Ingest().read(input, new Rules(Path.of("config/rules-v5.yaml"))))
            .code);
  }

  @Test
  void flowsFeatureIdsAndDuplicateIdsAreTolerated() throws Exception {
    ObjectNode s = Scenes.n06();
    props(s, 4).put("flow_tph", "10");
    Scenes.add(s, "bad", "oks_connection_point", Geo.point(Scenes.p(-20, 120)))
        .put("flow_tph", "abc");
    Scenes.add(s, "missing", "oks_connection_point", Geo.point(Scenes.p(20, 120)));
    Scenes.add(s, "negative", "oks_connection_point", Geo.point(Scenes.p(40, 120)))
        .put("flow_tph", -1);
    Scenes.add(s, "x", "oks_connection_point", Geo.point(Scenes.p(0, 150))).put("flow_tph", 5);
    props(s, 8).put("id", 1);
    Scenes.add(s, "x", "restriction", Scenes.box(300, 300, 310, 310))
        .put("restriction_type", "park");
    props(s, 9).put("id", "1");
    Scenes.add(s, "x", "restriction", Scenes.box(400, 300, 410, 310))
        .put("restriction_type", "water");
    props(s, 10).remove("id");
    last(s).put("id", 77);
    Rules r = new Rules(null);
    Path input = Scenes.save(temp.resolve("flows.json"), s), out = temp.resolve("flows-out.json");
    try (Dataset d = new Ingest().read(input, r)) {
      assertEquals(2, d.demands.size());
      assertEquals(0, new BigDecimal("15").compareTo(d.totalFlow()));
      assertEquals(
          List.of("bad", "missing", "negative"), ((List<?>) d.audit().get("invalid_demand_ids")));
      assertEquals("oks_connection_point", d.features.get("1").type);
      assertEquals("restriction", d.features.get("1~dup").type);
      assertEquals("water", parts(d, "77").get(0).type);
      assertEquals(1, warnings(d, "FLOW_PARSED"));
      assertEquals(3, warnings(d, "INVALID_FLOW"));
      assertEquals(1, warnings(d, "DUPLICATE_ID"));
      assertEquals(1, warnings(d, "FEATURE_LEVEL_ID"));
    }
    new CalculationService().solve(input, out, temp.resolve("flows-diag.json"), r, 1);
    Map<String, Object> report = new Verifier().verify(input, out, r);
    assertTrue((Boolean) report.get("valid"), report.toString());
    JsonNode summary =
        ContractRegressionTest.first((ObjectNode) Json.M.readTree(out.toFile()), "variant_summary")
            .path("properties");
    Set<String> missed = new TreeSet<>();
    for (JsonNode id : summary.path("unconnected_oks_ids")) missed.add(id.asText());
    assertTrue(missed.containsAll(List.of("bad", "missing", "negative")), missed.toString());
    assertTrue(
        summary.path("unconnected_penalty").decimalValue().compareTo(new BigDecimal("300000000"))
            >= 0);
    ObjectNode clash = Scenes.n06();
    Scenes.add(clash, "p1", "source", Geo.point(Scenes.p(500, 0)));
    assertEquals(
        "DUPLICATE_ID", assertThrows(Failure.class, () -> read(clash, "clash.json", r)).code);
    assertEquals(
        "INVALID_ATTRIBUTE",
        assertThrows(
                Failure.class,
                () -> read(s, "flows-v5.json", new Rules(Path.of("config/rules-v5.yaml"))))
            .code);
  }
}
