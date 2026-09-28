package ru.heatroute;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;

/**
 * The verifier's own kink measure (appendix §2.1, clarification 5): reported as warnings, never an
 * error.
 */
class GeometryQualityTest {
  private static LineString line(double... xy) {
    List<Coordinate> c = new ArrayList<>();
    for (int i = 0; i < xy.length; i += 2) c.add(Scenes.p(xy[i], xy[i + 1]));
    return Geo.line(c);
  }

  private static Map<String, Object> measure(
      List<LineString> runs, List<String> starts, List<String> ends, List<String> kinks) {
    return GeometryVerifier.quality(runs, starts, ends, new ArrayList<>(), kinks);
  }

  @Test
  void fanOfShortSegmentIsReportedAsSmallKinks() {
    // 10 m east, 0.30 m at 9.5 degrees, then 10 m at 16.9 degrees: two corners next to a 0.3 m
    // segment
    List<String> kinks = new ArrayList<>();
    Map<String, Object> q =
        measure(List.of(line(0, 0, 10, 0, 10.3, 0.05, 20, 3)), List.of("a"), List.of("b"), kinks);
    assertEquals(2, q.get("small_kinks"));
    assertEquals(1, q.get("short_segments"));
    assertEquals(2, kinks.size());
    assertTrue((Double) q.get("min_segment_m") < 0.31);
  }

  @Test
  void cleanTurnAndTechnicalSplitAreNotKinks() {
    // a 45 degree corner between long segments, and a collinear technical split that is not a
    // corner
    Map<String, Object> q =
        measure(
            List.of(line(0, 0, 10, 0, 20, 10), line(0, 20, 5, 20, 10, 20)),
            List.of("a", "c"),
            List.of("b", "d"),
            new ArrayList<>());
    assertEquals(0, q.get("small_kinks"));
    assertEquals(0, q.get("short_segments"));
    assertEquals(1, q.get("corners"));
  }

  @Test
  void vertexOffTheChordByLessThanACentimetreIsNotACorner() {
    // measured on the official set: 16.70 m, then 0.68 m turning 0.012°, then the entrance ray
    double dy = .68 * Math.sin(Math.toRadians(.012));
    Map<String, Object> q =
        measure(
            List.of(line(0, 0, 16.7, 0, 17.38, dy, 17.38 + 7, 10)),
            List.of("a"),
            List.of("b"),
            new ArrayList<>());
    assertEquals(0, q.get("small_kinks"));
    assertEquals(1, q.get("corners"));
  }

  @Test
  void nearlyStraightContinuationThroughChamberIsAJog() {
    // run 1 arrives at chamber X heading east; run 2 leaves X two degrees off the straight line
    double dy = 10 * Math.tan(Math.toRadians(2));
    Map<String, Object> q =
        measure(
            List.of(line(0, 0, 10, 0), line(10, 0, 20, dy)),
            List.of("r", "X"),
            List.of("X", "t"),
            new ArrayList<>());
    assertEquals(1, q.get("chamber_jogs"));
    assertEquals(0, q.get("small_kinks"));
  }
}
