package ru.heatroute;

import java.util.*;

/** Linear-reference intervals. Positive ordinary gaps are never merged. */
public final class Intervals {
  public static final class Zone {
    public final double a, b, k;
    public final String source;

    public Zone(double a, double b, double k, String source) {
      this.a = a;
      this.b = b;
      this.k = k;
      this.source = source;
    }
  }

  public static List<Zone> combine(List<Zone> input, boolean union) {
    List<Zone> s = new ArrayList<>(input);
    s.sort(Comparator.comparingDouble(z -> z.a));
    List<Zone> out = new ArrayList<>();
    if (union) {
      for (Zone z : s) {
        if (out.isEmpty() || z.a > out.get(out.size() - 1).b) {
          out.add(z);
        } else {
          Zone p = out.remove(out.size() - 1);
          out.add(new Zone(p.a, Math.max(p.b, z.b), Math.max(p.k, z.k), p.source + "," + z.source));
        }
      }
    } else {
      TreeSet<Double> bounds = new TreeSet<>();
      for (Zone z : s) {
        bounds.add(z.a);
        bounds.add(z.b);
      }
      Double prev = null;
      for (double v : bounds) {
        if (prev != null) {
          double mid = (prev + v) / 2, k = 1;
          String id = "";
          for (Zone z : s)
            if (mid >= z.a && mid <= z.b && z.k > k) {
              k = z.k;
              id = z.source;
            }
          if (k > 1) out.add(new Zone(prev, v, k, id));
        }
        prev = v;
      }
    }
    return out;
  }

  public static double factor(List<Zone> zones, double s) {
    double k = 1;
    for (Zone z : zones) if (s >= z.a - 1e-9 && s <= z.b + 1e-9) k = Math.max(k, z.k);
    return k;
  }
}
