package ru.heatroute;

import java.math.*;
import java.util.*;
import org.locationtech.jts.geom.*;

/** Full-profile propagation on existing upstream intervals. Input flows are never resummed. */
public final class Reconstruction {
  public static final class Part {
    public final Dataset.Feature feature;
    public final double a, b;
    public final BigDecimal added;
    public final int dn;
    public final LineString line;

    Part(Dataset.Feature f, double a, double b, BigDecimal g, int dn) {
      feature = f;
      this.a = a;
      this.b = b;
      added = g;
      this.dn = dn;
      line = Geo.canonical(Geo.sub((LineString) f.geometry, a, b));
    }
  }

  private static final class Load {
    double a, b;
    BigDecimal flow;

    Load(double a, double b, BigDecimal f) {
      this.a = Math.min(a, b);
      this.b = Math.max(a, b);
      flow = f;
    }
  }

  public static List<Part> calculate(Dataset data, Existing existing, List<Network.Tree> trees) {
    if (data.reduced) return List.of();
    Map<String, List<Load>> loads = new TreeMap<>();
    for (Network.Tree t : trees) {
      BigDecimal flow =
          t.edges.stream()
              .filter(e -> e.from == t.start)
              .map(e -> e.flow)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      for (Dataset.Demand d : t.start.demands) flow = flow.add(d.flow);
      Existing.Node node;
      if (t.root.target.type.equals("heat_chamber")) node = existing.anchors.get(t.root.target.id);
      else {
        Existing.Edge edge = existing.edgeAt(t.root.target.id, t.root.station);
        if (existing.ambiguousComponents.contains(edge.u.component))
          throw new Failure(
              "AMBIGUOUS_TOPOLOGY", "Reconstruction path is ambiguous", edge.feature.id);
        node = edge.forward() ? edge.u : edge.v;
        double station = edge.forward() ? edge.a : edge.b;
        add(loads, edge.feature, t.root.station, station, flow);
      }
      if (existing.ambiguousComponents.contains(node.component))
        throw new Failure("AMBIGUOUS_TOPOLOGY", "Reconstruction path is ambiguous");
      while (node.parent != null) {
        Existing.Edge e = node.parent;
        add(loads, e.feature, e.a, e.b, flow);
        node = e.other(node);
      }
    }
    List<Part> parts = new ArrayList<>();
    for (Map.Entry<String, List<Load>> entry : loads.entrySet()) {
      Dataset.Feature f = data.features.get(entry.getKey());
      if (f.flow() == null) throw new Failure("MISSING_EXISTING_FLOW", "Missing input flow", f.id);
      TreeSet<Double> bounds = new TreeSet<>();
      for (Load l : entry.getValue()) {
        bounds.add(l.a);
        bounds.add(l.b);
      }
      Double prev = null;
      for (double at : bounds) {
        if (prev != null && at - prev > 1e-6) {
          double mid = (prev + at) / 2;
          BigDecimal g = BigDecimal.ZERO;
          for (Load l : entry.getValue()) if (mid >= l.a && mid <= l.b) g = g.add(l.flow);
          if (g.signum() > 0) {
            int dn = Catalog.base(f.flow().add(g)).dn;
            if (dn > f.dn()) parts.add(new Part(f, prev, at, g, dn));
          }
        }
        prev = at;
      }
    }
    return parts;
  }

  private static void add(
      Map<String, List<Load>> loads, Dataset.Feature f, double a, double b, BigDecimal g) {
    if (Math.abs(a - b) > 1e-8)
      loads.computeIfAbsent(f.id, k -> new ArrayList<>()).add(new Load(a, b, g));
  }
}
