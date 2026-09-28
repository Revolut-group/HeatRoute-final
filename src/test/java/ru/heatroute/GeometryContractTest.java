package ru.heatroute;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.*;
import java.math.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.*;

class GeometryContractTest {
  @TempDir Path temp;

  @Test
  void G10_G11_hostCannotBeReenteredOrCrossedToCourtyard() throws Exception {
    ObjectNode s = Scenes.n06();
    Scenes.add(s, "host", "oks_existing", Scenes.box(-10, 90, 10, 110));
    Rules r = new Rules(java.nio.file.Path.of("config/rules-v5.yaml"));
    try (Dataset d = new Ingest().read(Scenes.save(temp.resolve("host.json"), s))) {
      Existing ex = new Existing(d, r);
      Existing.Root root =
          ex.roots(Scenes.p(0, 100)).stream()
              .filter(a -> a.target.id.equals("ch"))
              .findFirst()
              .orElseThrow();
      GeometryRules g = new GeometryRules(d, r);
      assertDoesNotThrow(
          () -> g.events(Geo.line(Scenes.p(0, 0), Scenes.p(0, 100)), 80, Scenes.p(0, 100), root));
      assertThrows(
          Failure.class,
          () ->
              g.events(
                  Geo.line(
                      List.of(
                          Scenes.p(0, 0),
                          Scenes.p(0, 120),
                          Scenes.p(20, 120),
                          Scenes.p(20, 100),
                          Scenes.p(0, 100))),
                  80,
                  Scenes.p(0, 100),
                  root));
    }
    Polygon shell = Scenes.box(-50, 50, 50, 150), hole = Scenes.box(-30, 70, 30, 130);
    Polygon courtyard =
        Geo.GF.createPolygon(
            (LinearRing) shell.getExteriorRing(),
            new LinearRing[] {(LinearRing) hole.getExteriorRing()});
    ObjectNode c = Scenes.collection();
    Scenes.add(c, "courtyard", "oks_existing", courtyard);
    try (Dataset d = new Ingest().read(Scenes.save(temp.resolve("court.json"), c))) {
      GeometryRules g = new GeometryRules(d, r);
      assertTrue(g.segment(Scenes.p(0, 100), Scenes.p(-40, 100), 80, Scenes.p(-40, 100), null));
      assertFalse(g.segment(Scenes.p(-100, 100), Scenes.p(0, 100), 80, null, null));
    }
  }

  @Test
  void G12_G13_metricToleranceSeparateFromRoutingMargin() throws Exception {
    Rules r = new Rules(java.nio.file.Path.of("config/rules-v5.yaml"));
    for (int k = 1; k <= 2; k++) {
      ObjectNode s = Scenes.n06();
      double distance = 5 + Catalog.pipe(80).width / 2 - k * .001;
      Scenes.add(s, "wall", "oks_existing", Scenes.box(distance, 30, distance + 10, 60));
      Path in = Scenes.save(temp.resolve("tol" + k + ".json"), s);
      try (Dataset d = new Ingest().read(in)) {
        Existing ex = new Existing(d, r);
        Existing.Root root =
            ex.roots(Scenes.p(0, 100)).stream()
                .filter(a -> a.target.id.equals("ch"))
                .findFirst()
                .orElseThrow();
        Network.Tree t = new Network.Tree(root);
        Dataset.Demand dem = d.demands.get(0);
        Network.Node leaf = new Network.Node("p1", Scenes.p(0, 100), List.of(dem), "p1");
        Network.Edge e =
            new Network.Edge(t.start, leaf, Geo.canonical(Geo.line(t.start.p, leaf.p)));
        e.flow = dem.flow;
        e.base = e.dn = 80;
        t.edges.add(e);
        t.demands.add("p1");
        assertThrows(
            Failure.class, () -> new Evaluation(d, r, new Router(d, r)).evaluate(t, false));
        Path out = temp.resolve("tol-out" + k + ".json");
        Json.write(out, Exporter.collection(List.of(new Exporter(d, ex, r).export(List.of(t), 1))));
        Map<String, Object> report = new Verifier().verify(in, out, r);
        assertEquals(k == 1, report.get("valid"), report.toString());
        if (k == 1) assertEquals(1, report.get("judge_tolerance_uses"));
      }
    }
  }

  @Test
  void G18_G19_specialCrossingAndTargetStayIndependent() throws Exception {
    ObjectNode s = Scenes.n06();
    Scenes.add(s, "A", "heat_network", Geo.line(Scenes.p(-100, 40), Scenes.p(100, 40)))
        .put("diameter", 80);
    Rules r = new Rules(java.nio.file.Path.of("config/rules-v5.yaml"));
    try (Dataset d = new Ingest().read(Scenes.save(temp.resolve("special.json"), s))) {
      Existing.Root root =
          new Existing(d, r)
              .roots(Scenes.p(0, 100)).stream()
                  .filter(a -> a.target.id.equals("ch"))
                  .findFirst()
                  .orElseThrow();
      List<Intervals.Zone> zones =
          new GeometryRules(d, r)
              .events(Geo.line(Scenes.p(0, 0), Scenes.p(0, 100)), 80, Scenes.p(0, 100), root);
      assertEquals(1, zones.size());
      assertEquals(38, zones.get(0).a, 1e-6);
      assertEquals(42, zones.get(0).b, 1e-6);
    }
    ObjectNode road = Scenes.collection();
    Scenes.add(road, "road", "restriction", Scenes.box(-100, 0, 100, 10))
        .put("restriction_type", "road");
    try (Dataset d = new Ingest().read(Scenes.save(temp.resolve("road.json"), road))) {
      GeometryRules g = new GeometryRules(d, r);
      assertThrows(
          Failure.class,
          () -> g.events(Geo.line(Scenes.p(-50, -1.7), Scenes.p(50, -1.7)), 80, null, null));
      assertThrows(
          Failure.class,
          () ->
              g.events(
                  Geo.line(
                      List.of(
                          Scenes.p(0, -20), Scenes.p(0, 11), Scenes.p(50, 11), Scenes.p(50, 30))),
                  80,
                  null,
                  null));
    }
  }

  @Test
  void T12_T13_degreeAndTwoCommercialRays() throws Exception {
    ObjectNode s = Scenes.n06();
    Scenes.add(s, "p2", "oks_connection_point", Geo.point(Scenes.p(100, 100))).put("flow_tph", 10);
    Rules r = new Rules(java.nio.file.Path.of("config/rules-v5.yaml"));
    Path in = Scenes.save(temp.resolve("rays.json"), s);
    try (Dataset d = new Ingest().read(in)) {
      Existing ex = new Existing(d, r);
      Existing.Root root =
          ex.roots(Scenes.p(0, 100)).stream()
              .filter(a -> a.target.id.equals("ch"))
              .findFirst()
              .orElseThrow();
      List<Network.Tree> trees = new ArrayList<>();
      Evaluation ev = new Evaluation(d, r, new Router(d, r));
      for (Dataset.Demand dem : d.demands) {
        Network.Tree t = new Network.Tree(root);
        Network.Node leaf =
            new Network.Node(
                dem.id, dem.terminals.get(0).geometry.getCoordinate(), List.of(dem), dem.id);
        t.edges.add(new Network.Edge(t.start, leaf, Geo.line(t.start.p, leaf.p)));
        trees.add(ev.evaluate(t, false));
      }
      assertTrue(ev.compatible(List.of(trees.get(0)), trees.get(1)));
      Network.Variant v = new Exporter(d, ex, r).export(trees, 1);
      assertEquals(
          0, new BigDecimal("10000000").compareTo((BigDecimal) v.summary.get("tie_in_cost")));
      Path out = temp.resolve("rays-out.json");
      Json.write(out, Exporter.collection(List.of(v)));
      Map<String, Object> report = new Verifier().verify(in, out, r);
      assertTrue((Boolean) report.get("valid"), report.toString());
    }
    Scenes.add(s, "vertical", "heat_network", Geo.line(Scenes.p(0, -20), Scenes.p(0, 20)))
        .put("diameter", 150)
        .put("flow_tph", 20);
    try (Dataset d = new Ingest().read(Scenes.save(temp.resolve("full.json"), s))) {
      Existing ex = new Existing(d, r);
      assertEquals(4, ex.anchors.get("ch").edges.size());
      assertTrue(ex.roots(Scenes.p(0, 100)).stream().noneMatch(a -> a.target.id.equals("ch")));
      assertTrue(
          ex.roots(Scenes.p(0, 100)).stream()
              .anyMatch(
                  a -> a.target.type.equals("heat_network") && a.p.distance(Scenes.p(0, 0)) < 10));
    }
  }

  @Test
  void G24_reversingLinesPreservesReconstruction() throws Exception {
    ObjectNode s = Scenes.n06();
    Rules r = new Rules(java.nio.file.Path.of("config/rules-v5.yaml"));
    Path in = Scenes.save(temp.resolve("forward.json"), s);
    byte[] reference;
    try (Dataset d = new Ingest().read(in)) {
      reference =
          Json.M.writeValueAsBytes(
              Exporter.collection(new Optimizer(d, r, new Existing(d, r)).solve(1)));
    }
    for (com.fasterxml.jackson.databind.JsonNode f : s.path("features"))
      if (f.path("geometry").path("type").asText().equals("LineString")) {
        ArrayNode a = (ArrayNode) f.path("geometry").path("coordinates");
        List<com.fasterxml.jackson.databind.JsonNode> c = new ArrayList<>();
        a.forEach(c::add);
        Collections.reverse(c);
        a.removeAll();
        c.forEach(a::add);
      }
    Scenes.save(in, s);
    try (Dataset d = new Ingest().read(in)) {
      assertArrayEquals(
          reference,
          Json.M.writeValueAsBytes(
              Exporter.collection(new Optimizer(d, r, new Existing(d, r)).solve(1))));
    }
  }
}
