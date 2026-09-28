package ru.heatroute;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.*;

/**
 * A5: generators and CurrentVerifier share one "existing chamber within 10 m" rule (clarification
 * 11).
 */
class ChamberSnapTest {
  @TempDir Path temp;

  private static ObjectNode scene(double chamberOffset) {
    ObjectNode s = Scenes.collection();
    Scenes.add(s, "src", "source", Geo.point(Scenes.p(-60, 0)));
    Scenes.add(s, "net", "heat_network", Geo.line(Scenes.p(-60, 0), Scenes.p(60, 0)))
        .put("diameter", 150);
    Scenes.add(s, "ch", "heat_chamber", Geo.point(Scenes.p(0, chamberOffset)));
    Scenes.add(s, "p1", "oks_connection_point", Geo.point(Scenes.p(4, 80))).put("flow_tph", 10);
    return s;
  }

  @Test
  void chamberBesideThePipeIsSnappedAndForcesTheTieIn() throws Exception {
    Rules r = new Rules(null);
    assertEquals(1.5, r.number("geometry.chamber_snap_m"), 0);
    assertEquals(.1, r.number("geometry.chamber_radius_margin_m"), 0);
    try (Dataset d = new Ingest().read(Scenes.save(temp.resolve("near.json"), scene(1.2)), r)) {
      Existing ex = new Existing(d, r);
      Dataset.Feature ch = d.features.get("ch");
      assertTrue(ex.eligible(ch));
      assertEquals(List.of(ch), ex.availableChambers());
      assertEquals(2, ex.degree(ch));
      assertEquals(150, ex.chamberDn(ch));
      assertEquals(1, ex.components);
      assertTrue(ex.forcesChamber(Scenes.p(10, 0)));
      assertTrue(
          ex.forcesChamber(Scenes.p(Math.sqrt(10.05 * 10.05 - 1.44), 0)),
          "10.05 m lies in the margin band");
      assertFalse(ex.forcesChamber(Scenes.p(10.2, 0)));
      List<Existing.Root> roots = ex.roots(Scenes.p(4, 80));
      assertEquals(
          "ch",
          roots.stream()
              .filter(a -> a.target.type.equals("heat_chamber"))
              .findFirst()
              .orElseThrow()
              .target
              .id);
      for (Existing.Root root : roots)
        if (root.target.type.equals("heat_network"))
          assertTrue(root.p.distance(ch.geometry.getCoordinate()) > 10.1, root.key);
      assertTrue(
          roots.stream()
              .anyMatch(
                  a ->
                      a.target.type.equals("heat_network")
                          && a.p.distance(ch.geometry.getCoordinate()) < 10.2),
          "closest admissible line root is generated just outside radius+margin");
    }
    try (Dataset d = new Ingest().read(Scenes.save(temp.resolve("far.json"), scene(2)), r)) {
      Existing ex = new Existing(d, r);
      assertFalse(ex.eligible(d.features.get("ch")));
      assertTrue(ex.availableChambers().isEmpty());
      assertFalse(ex.forcesChamber(Scenes.p(0, 0)));
    }
    Rules v5 = new Rules(Path.of("config/rules-v5.yaml"));
    try (Dataset d = new Ingest().read(Scenes.save(temp.resolve("legacy.json"), scene(1.2)))) {
      assertTrue(new Existing(d, v5).availableChambers().isEmpty());
    }
  }

  @Test
  void solvedResultUsesTheSnappedChamberAndPassesTheVerifier() throws Exception {
    Rules r = new Rules(null);
    Path input = Scenes.save(temp.resolve("solve.json"), scene(1.2)),
        out = temp.resolve("solve-out.json");
    new CalculationService().solve(input, out, temp.resolve("solve-diag.json"), r, 1);
    Map<String, Object> report = new Verifier().verify(input, out, r);
    assertTrue((Boolean) report.get("valid"), report.toString());
    Coordinate chamber = Scenes.p(0, 1.2);
    for (JsonNode f : Json.M.readTree(out.toFile()).path("features"))
      if (f.path("properties").path("object_type").asText().equals("heat_chamber")
          && !f.path("properties").path("id").asText().equals("ch")) {
        Geometry g = Geo.read(f.path("geometry"), "new");
        if (g.distance(
                Geo.GF.createLineString(new Coordinate[] {Scenes.p(-60, 0), Scenes.p(60, 0)}))
            < 1e-6)
          assertTrue(
              g.getCoordinate().distance(chamber) > 10.1,
              "new tie-in chamber at " + g.getCoordinate().distance(chamber));
      }
  }

  @Test
  void verifierRejectsANewTieInNextToASnappedChamber() throws Exception {
    Rules r = new Rules(null);
    Path input = Scenes.save(temp.resolve("reject.json"), scene(1.2)),
        out = temp.resolve("reject-out.json");
    try (Dataset d = new Ingest().read(input, r)) {
      Existing ex = new Existing(d, r);
      Existing.Root root = new Existing.Root(d.features.get("net"), Scenes.p(4, 0), 64, 150, 2);
      Network.Tree tree = new Network.Tree(root);
      Network.Node leaf =
          new Network.Node("leaf", Scenes.p(4, 80), List.of(d.demands.get(0)), "p1");
      tree.edges.add(new Network.Edge(tree.start, leaf, Geo.line(tree.start.p, leaf.p)));
      Json.write(
          out,
          Exporter.collection(
              List.of(
                  new CurrentExporter(d, r)
                      .export(
                          List.of(new Evaluation(d, r, new Router(d, r)).evaluate(tree, false)),
                          1))));
    }
    Map<String, Object> report = new Verifier().verify(input, out, r);
    assertFalse((Boolean) report.get("valid"));
    assertTrue(report.toString().contains("NEARBY_CHAMBER"), report.toString());
  }
}
