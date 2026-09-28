package ru.heatroute;

import static org.junit.jupiter.api.Assertions.*;

import java.math.*;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;

class DepthAndAxisTest {
  @Test
  void N12_depthAppendixRamp() {
    assertEquals(new BigDecimal("877065.00"), DepthMath.rampCost(10, 80, 3, 4));
  }

  @Test
  void N13_depthAppendixAcrossThreeMetres() {
    assertEquals(new BigDecimal("1712365.00"), DepthMath.rampCost(20, 80, 2, 4));
  }

  @Test
  void G17_localRoadAxis() {
    Geometry road = Scenes.box(-100, -5, 100, 5);
    AxisService.Axis axis = AxisService.estimate(road, Scenes.p(0, 0));
    assertNotNull(axis);
    assertEquals("local_voronoi", axis.method);
    assertEquals(
        90,
        GeometryRules.axisAngle(Geo.line(Scenes.p(0, -20), Scenes.p(0, 20)), axis.vector),
        1e-6);
    assertEquals(
        0, GeometryRules.axisAngle(Geo.line(Scenes.p(-20, 0), Scenes.p(20, 0)), axis.vector), 1e-6);
  }
}
