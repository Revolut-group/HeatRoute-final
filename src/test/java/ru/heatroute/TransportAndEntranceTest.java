package ru.heatroute;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.*;

class TransportAndEntranceTest {
  private Rules extended() throws Exception {
    return new Rules(null);
  }

  @TempDir Path temp;

  private Polygon u() {
    return Geo.GF.createPolygon(
        new Coordinate[] {
          Scenes.p(-20, 0),
          Scenes.p(24, 0),
          Scenes.p(24, 80),
          Scenes.p(4, 80),
          Scenes.p(4, 10),
          Scenes.p(0, 10),
          Scenes.p(0, 80),
          Scenes.p(-20, 80),
          Scenes.p(-20, 0)
        });
  }

  private Dataset buildings(Polygon host, Polygon other) {
    Dataset d = new Dataset();
    for (Dataset.Obstacle o :
        other == null
            ? List.of(new Dataset.Obstacle("host", "building", 0, host, 0))
            : List.of(
                new Dataset.Obstacle("host", "building", 0, host, 0),
                new Dataset.Obstacle("host", "building", 1, other, 0))) {
      d.obstacles.add(o);
      d.index.insert(o.geometry.getEnvelopeInternal(), o);
    }
    d.index.build();
    return d;
  }

  @Test
  void blockedNearestEntranceFallsBackToAnotherValidWall() throws Exception {
    Rules rules = extended();
    assertTrue(rules.flexibleEntrances());
    Coordinate p = Scenes.p(-5, 40);
    Dataset d = buildings(u(), null);
    List<Coordinate> choices = new FeasibleEntrances(d, rules).ports(p, 100, false);
    assertFalse(choices.isEmpty());
    GeometryRules checks = new GeometryRules(d, rules);
    for (Coordinate port : choices) {
      assertTrue(checks.segment(port, p, 100, p, null));
      assertTrue(TerminalAccess.singleEntry(Geo.line(port, p), p, u()));
    }
  }

  @Test
  void reachableNearestEntranceRemainsFirstChoice() throws Exception {
    Rules r = extended();
    Dataset d = buildings(Scenes.box(-20, 90, 20, 120), null);
    Coordinate p = Scenes.p(0, 100);
    List<Coordinate> ports = new FeasibleEntrances(d, r).ports(p, 100, false);
    assertEquals(1, ports.size());
    assertEquals(Scenes.X, ports.get(0).x, 1e-7);
    assertTrue(ports.get(0).y < Scenes.Y + 90);
  }

  @Test
  void farSideShortcutCannotReplaceShortestEntrance() throws Exception {
    Rules r = extended();
    Polygon host = Scenes.box(-20, 0, 20, 80);
    Dataset d = buildings(host, null);
    Coordinate terminal = Scenes.p(-18, 40);
    LineString route = new Router(d, r).route(Scenes.p(60, 40), terminal, 100, terminal, null);
    assertNotNull(route);
    assertEquals(2, route.intersection(host).getLength(), .001);
    assertTrue(route.getLength() > 100);
    Failure error =
        assertThrows(
            Failure.class,
            () ->
                new GeometryRules(d, r)
                    .events(Geo.line(Scenes.p(60, 40), terminal), 100, terminal, null));
    assertEquals("HOST_SHORTEST_FEASIBLE", error.code);
  }

  @Test
  void blockedNearestUsesShortestValidInteriorDistance() throws Exception {
    Rules r = extended();
    Dataset d = buildings(u(), null);
    Coordinate terminal = Scenes.p(-5, 40);
    FeasibleEntrances entrances = new FeasibleEntrances(d, r);
    List<Coordinate> ports = entrances.ports(terminal, 100, false);
    assertFalse(ports.isEmpty());
    double shortest = entrances.insideLength(terminal, ports.get(0));
    LineString route = new Router(d, r).route(Scenes.p(-60, 40), terminal, 100, terminal, null);
    assertNotNull(route);
    assertEquals(shortest, route.intersection(u()).getLength(), .011);
    assertEquals(15, shortest, .011);
  }

  @Test
  void entrancePortExtendsPastRoadWithoutMovingFacade() throws Exception {
    Rules r = extended();
    Dataset d = new Dataset();
    Polygon host = Scenes.box(-20, 0, 20, 80);
    for (Dataset.Obstacle o :
        List.of(
            new Dataset.Obstacle("host", "building", 0, host, 0),
            new Dataset.Obstacle(
                "road", "road", 0, Geo.line(Scenes.p(-27, -100), Scenes.p(-27, 150)), 0))) {
      d.obstacles.add(o);
      d.index.insert(o.geometry.getEnvelopeInternal(), o);
    }
    d.index.build();
    Coordinate terminal = Scenes.p(-18, 40);
    List<Coordinate> ports = new FeasibleEntrances(d, r).ports(terminal, 100, false);
    assertEquals(1, ports.size());
    assertTrue(ports.get(0).x < Scenes.X - 30);
    LineString route = new Router(d, r).route(Scenes.p(-60, 10), terminal, 100, terminal, null);
    assertNotNull(route);
    assertEquals(2, route.intersection(host).getLength(), .001);
    assertDoesNotThrow(() -> new GeometryRules(d, r).events(route, 100, terminal, null));
  }

  @Test
  void closedCourtyardIsSkippedForNextShortValidWall() throws Exception {
    Rules r = extended();
    Polygon host =
        Geo.GF.createPolygon(
            Geo.GF.createLinearRing(Scenes.box(0, 0, 100, 100).getCoordinates()),
            new LinearRing[] {
              Geo.GF.createLinearRing(Scenes.box(10, 10, 90, 90).getCoordinates())
            });
    Dataset d = buildings(host, null);
    Coordinate p = Scenes.p(8, 50);
    FeasibleEntrances entrances = new FeasibleEntrances(d, r);
    List<Coordinate> ports = entrances.ports(p, 100, false);
    assertFalse(ports.isEmpty());
    assertEquals(8, entrances.insideLength(p, ports.get(0)), .011);
    LineString route = new Router(d, r).route(Scenes.p(-40, 50), p, 100, p, null);
    assertNotNull(route);
    assertEquals(8, route.intersection(host).getLength(), .011);
    d.networks.add(
        new Dataset.Feature(
            "courtyard-network",
            "heat_network",
            Geo.line(Scenes.p(30, 40), Scenes.p(70, 40)),
            Json.M.createObjectNode()));
    FeasibleEntrances supplied = new FeasibleEntrances(d, r);
    assertEquals(2, supplied.insideLength(p, supplied.ports(p, 100, false).get(0)), .011);
  }

  @Test
  void fallbackCannotTransitOtherWingOrCrossOtherMultipartPart() throws Exception {
    Rules r = extended();
    Coordinate p = Scenes.p(-5, 40);
    Dataset d = buildings(u(), null);
    assertFalse(new GeometryRules(d, r).segment(Scenes.p(40, 40), p, 100, p, null));
    Dataset multipart = buildings(Scenes.box(-20, -20, 0, 20), Scenes.box(1, 3, 3, 5));
    // Another part of the containing restriction: its clearance is waived on the final straight
    // run, crossing it is not.
    assertTrue(
        new GeometryRules(multipart, r)
            .segment(Scenes.p(10, 0), Scenes.p(-2, 0), 200, Scenes.p(-2, 0), null));
    assertFalse(
        new GeometryRules(multipart, r)
            .segment(Scenes.p(6, 12), Scenes.p(-2, 0), 200, Scenes.p(-2, 0), null));
  }

  @Test
  void linearRoadAndTramHaveThreeMetreStraightExtensions() throws Exception {
    for (String type : List.of("road", "tram_tracks")) {
      ObjectNode scene = Scenes.n06();
      Scenes.add(scene, "crossing", "restriction", Geo.line(Scenes.p(-100, 50), Scenes.p(100, 50)))
          .put("restriction_type", type);
      Rules r = extended();
      try (Dataset d = new Ingest().read(Scenes.save(temp.resolve(type + ".json"), scene), r)) {
        List<Intervals.Zone> zones =
            new GeometryRules(d, r)
                .events(
                    Geo.line(Scenes.p(0, 0), Scenes.p(0, 100)),
                    100,
                    Scenes.p(0, 100),
                    new Existing.Root(d.features.get("ch"), Scenes.p(0, 0), 0, 150, 2));
        assertEquals(1, zones.size());
        assertEquals(47, zones.get(0).a, 1e-4);
        assertEquals(53, zones.get(0).b, 1e-4);
        assertEquals(type.equals("road") ? 1.6 : 1.75, zones.get(0).k);
        LineString bend = Geo.line(List.of(Scenes.p(-20, 20), Scenes.p(0, 48), Scenes.p(0, 100)));
        assertEquals(
            "SPECIAL_STRAIGHT",
            assertThrows(
                    Failure.class,
                    () ->
                        new GeometryRules(d, r)
                            .events(
                                bend,
                                100,
                                Scenes.p(0, 100),
                                new Existing.Root(d.features.get("ch"), Scenes.p(0, 0), 0, 150, 2)))
                .code);
      }
    }
  }

  @Test
  void linearCrossingUsesLocalTangentInsteadOfWholeRouteChord() {
    LineString road = Geo.line(Scenes.p(-100, 0), Scenes.p(100, 0));
    LineString valid =
        Geo.line(List.of(Scenes.p(-90, -10), Scenes.p(0, -10), Scenes.p(0, 10), Scenes.p(90, 10)));
    assertTrue(CrossingBoundary.accepts(valid, road));
    LineString invalid =
        Geo.line(
            List.of(Scenes.p(-20, -100), Scenes.p(-20, -5), Scenes.p(20, 5), Scenes.p(20, 100)));
    assertFalse(CrossingBoundary.accepts(invalid, road));
  }

  @Test
  void railwayLineAndPolygonCannotBeCrossed() throws Exception {
    for (Geometry g :
        List.of(Geo.line(Scenes.p(-100, 50), Scenes.p(100, 50)), Scenes.box(-100, 45, 100, 55))) {
      ObjectNode scene = Scenes.n06();
      Scenes.add(scene, "rail", "restriction", g).put("restriction_type", "railway");
      Rules r = extended();
      try (Dataset d =
          new Ingest()
              .read(Scenes.save(temp.resolve("rail" + g.getDimension() + ".json"), scene), r)) {
        assertFalse(
            new GeometryRules(d, r)
                .segment(Scenes.p(0, 0), Scenes.p(0, 100), 100, Scenes.p(0, 100), null));
        assertEquals(
            "CLEARANCE",
            assertThrows(
                    Failure.class,
                    () ->
                        new GeometryRules(d, r)
                            .events(
                                Geo.line(Scenes.p(0, 0), Scenes.p(0, 100)),
                                100,
                                Scenes.p(0, 100),
                                new Existing.Root(d.features.get("ch"), Scenes.p(0, 0), 0, 150, 2)))
                .code);
      }
    }
  }

  @Test
  void solveAndVerifierAccountForRoadSpecialCost() throws Exception {
    ObjectNode scene = Scenes.n06();
    Scenes.add(scene, "road", "restriction", Geo.line(Scenes.p(-500, 50), Scenes.p(500, 50)))
        .put("restriction_type", "road");
    Path in = Scenes.save(temp.resolve("solve-road.json"), scene),
        out = temp.resolve("result.json");
    Rules r = extended();
    new CalculationService().solve(in, out, temp.resolve("diag.json"), r, 1);
    assertTrue((Boolean) new Verifier().verify(in, out, r).get("valid"));
    JsonNode result = Json.M.readTree(out.toFile());
    double special = 0;
    for (JsonNode f : result.path("features"))
      if (f.path("properties").path("object_type").asText().equals("heat_network")
          && f.path("properties").path("laying_method").asText().equals("special"))
        special += f.path("properties").path("length").asDouble();
    assertTrue(special >= 6 - 1e-4);
    for (JsonNode f : result.path("features"))
      if (f.path("properties").path("laying_method").asText().equals("special")) {
        ((ObjectNode) f.path("properties")).put("laying_method", "base");
        break;
      }
    Json.write(out, result);
    assertFalse((Boolean) new Verifier().verify(in, out, r).get("valid"));
  }
}
