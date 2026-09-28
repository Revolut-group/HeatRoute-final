package ru.heatroute;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;

/** Strict section 2.2: exercise both feasible and forbidden nearest entrances. */
class StrictEntranceTest {
  @Test
  void strictProfileUsesLiteralNearestBoundaryRule() throws Exception {
    Rules rules = new Rules(java.nio.file.Path.of("config/rules-strict.yaml"));
    assertEquals("lct_2026_09_19", rules.text("rules_version"));
    assertEquals("nearest_boundary", rules.text("input.terminal_entry_policy"));
    assertEquals("containing_building_part_only", rules.text("input.host_access"));
    assertEquals("grid_steiner", rules.text("execution.planner"));
    // the packaged profile behind API/UI profile=strict is the same file
    Rules packaged = Rules.profile("strict");
    assertEquals(rules.hash, packaged.hash);
    assertEquals(new Rules(null).hash, Rules.profile("default").hash);
    // the default profile uses the current R02/R03 interpretation
    Rules current = new Rules(null);
    assertEquals("nearest_feasible_boundary", current.text("input.terminal_entry_policy"));
    assertEquals("containing_feature", current.text("input.host_access"));
  }

  private Polygon u(double gap) {
    return Geo.GF.createPolygon(
        new Coordinate[] {
          Scenes.p(-20, 0), Scenes.p(gap + 20, 0), Scenes.p(gap + 20, 80),
          Scenes.p(gap, 80), Scenes.p(gap, 10), Scenes.p(0, 10),
          Scenes.p(0, 80), Scenes.p(-20, 80), Scenes.p(-20, 0)
        });
  }

  private Dataset data(Polygon host, Geometry other) {
    Dataset d = new Dataset();
    add(d, new Dataset.Obstacle("host", "building", 0, host, 0));
    if (other != null) add(d, new Dataset.Obstacle("host", "building", 1, other, 0));
    d.index.build();
    return d;
  }

  private void add(Dataset d, Dataset.Obstacle o) {
    d.obstacles.add(o);
    d.index.insert(o.geometry.getEnvelopeInternal(), o);
  }

  private List<Coordinate> ports(Dataset d, Coordinate p, int dn, Rules r) {
    return TerminalAccess.ports(p, d.hosts(p), dn, r);
  }

  @Test
  void nearestWallWithEnoughRoomAcceptsEntryBeforeOppositeWing() throws Exception {
    Rules r = new Rules(java.nio.file.Path.of("config/rules-2026-09-19.yaml"));
    Coordinate p = Scenes.p(-5, 40);
    Dataset d = data(u(20), null);
    List<Coordinate> ports = ports(d, p, 100, r);
    assertEquals(1, ports.size());
    assertTrue(ports.get(0).x > Scenes.X && ports.get(0).x < Scenes.X + 10);
    GeometryRules check = new GeometryRules(d, r);
    assertTrue(check.segment(ports.get(0), p, 100, p, null));
    assertDoesNotThrow(() -> check.events(Geo.line(ports.get(0), p), 100, p, null));
  }

  @Test
  void narrowGapCannotBeCrossedToReachTheNearestWall() throws Exception {
    Rules r = new Rules(java.nio.file.Path.of("config/rules-2026-09-19.yaml"));
    Coordinate p = Scenes.p(-5, 40);
    Dataset d = data(u(4), null);
    List<Coordinate> ports = ports(d, p, 100, r);
    assertEquals(1, ports.size());
    assertFalse(new GeometryRules(d, r).segment(ports.get(0), p, 100, p, null));
  }

  @Test
  void allEquallyNearestWallsAreConsideredIncludingAnOpenSide() throws Exception {
    Rules r = new Rules(java.nio.file.Path.of("config/rules-2026-09-19.yaml"));
    Coordinate p = Scenes.p(-10, 40);
    Dataset d = data(u(4), null);
    List<Coordinate> ports = ports(d, p, 100, r);
    assertEquals(2, ports.size());
    GeometryRules check = new GeometryRules(d, r);
    long valid = ports.stream().filter(q -> check.segment(q, p, 100, p, null)).count();
    assertEquals(1, valid);
  }

  @Test
  void CourtyardBoundaryIsEligibleWhenClearanceFits() throws Exception {
    Polygon shell = Scenes.box(-40, -40, 40, 40), hole = Scenes.box(-20, -20, 20, 20);
    Polygon host =
        Geo.GF.createPolygon(
            (LinearRing) shell.getExteriorRing(),
            new LinearRing[] {(LinearRing) hole.getExteriorRing()});
    Rules r = new Rules(java.nio.file.Path.of("config/rules-2026-09-19.yaml"));
    Coordinate p = Scenes.p(24, 0);
    Dataset d = data(host, null);
    List<Coordinate> ports = ports(d, p, 100, r);
    assertEquals(1, ports.size());
    assertTrue(ports.get(0).x < Scenes.X + 20);
    assertTrue(new GeometryRules(d, r).segment(ports.get(0), p, 100, p, null));
  }

  @Test
  void OtherMultipartPartKeepsItsClearanceEvenDuringOwnBuildingEntry() throws Exception {
    Rules r = new Rules(java.nio.file.Path.of("config/rules-2026-09-19.yaml"));
    Coordinate p = Scenes.p(-2, 0);
    Dataset d = data(Scenes.box(-20, -20, 0, 20), Scenes.box(1, 3, 3, 5));
    List<Coordinate> ports = ports(d, p, 200, r);
    assertEquals(1, ports.size());
    assertFalse(new GeometryRules(d, r).segment(ports.get(0), p, 200, p, null));
  }

  @Test
  void choosingFartherWallIsNotAnAllowedFallback() throws Exception {
    Rules r = new Rules(java.nio.file.Path.of("config/rules-2026-09-19.yaml"));
    Coordinate p = Scenes.p(-5, 40);
    Dataset d = data(u(4), null);
    Coordinate outside = Scenes.p(-30, 40);
    assertFalse(new GeometryRules(d, r).segment(outside, p, 100, p, null));
    assertEquals(
        "HOST_NEAREST_BOUNDARY",
        assertThrows(
                Failure.class,
                () -> new GeometryRules(d, r).events(Geo.line(outside, p), 100, p, null))
            .code);
  }

  @Test
  void bendInsideOwnClearanceCannotHideBehindTheHostException() throws Exception {
    Rules r = new Rules(java.nio.file.Path.of("config/rules-2026-09-19.yaml"));
    Coordinate p = Scenes.p(0, 100);
    Dataset d = data(Scenes.box(-20, 90, 20, 120), null);
    LineString bent = Geo.line(List.of(Scenes.p(20, 80), Scenes.p(0, 88), p));
    assertEquals(
        "HOST_TRANSIT",
        assertThrows(Failure.class, () -> new GeometryRules(d, r).events(bent, 100, p, null)).code);
  }
}
