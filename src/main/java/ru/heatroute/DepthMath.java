package ru.heatroute;

import java.math.*;

/** Appendix numerical oracles only. XY export stays depth=null until Q04 is confirmed. */
public final class DepthMath {
  private DepthMath() {}

  public static BigDecimal rampCost(double length, int dn, double start, double end) {
    if (length <= 0 || Math.min(start, end) < .7 || Math.abs(end - start) > .1 * length + 1e-9)
      throw new Failure("INVALID_DEPTH", "Cover or slope violation");
    double integral;
    if ((start - 3) * (end - 3) < 0) {
      double f = (3 - start) / (end - start);
      integral =
          length * f * (coefficient(start) + 1) / 2 + length * (1 - f) * (1 + coefficient(end)) / 2;
    } else integral = length * (coefficient(start) + coefficient(end)) / 2;
    return Catalog.money(BigDecimal.valueOf(integral).multiply(Catalog.pipe(dn).newPrice));
  }

  private static double coefficient(double h) {
    return 1 + .1 * Math.max(0, h - 3);
  }
}
