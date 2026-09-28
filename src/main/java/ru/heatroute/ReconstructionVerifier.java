package ru.heatroute;

import java.math.*;
import java.util.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.linearref.LengthIndexedLine;
import ru.heatroute.Verifier.F;

final class ReconstructionVerifier {
  private final Verifier v;

  ReconstructionVerifier(Verifier v) {
    this.v = v;
  }

  private static final class Load {
    final double a, b;
    final BigDecimal flow;

    Load(double a, double b, BigDecimal g) {
      this.a = Math.min(a, b);
      this.b = Math.max(a, b);
      flow = g;
    }
  }

  private static final class Expected {
    final Dataset.Feature f;
    final BigDecimal flow;
    final int dn;
    final LineString line;

    Expected(Dataset.Feature f, double a, double b, BigDecimal g, int dn) {
      this.f = f;
      flow = g;
      this.dn = dn;
      line = Geo.canonical(Geo.sub((LineString) f.geometry, a, b));
    }
  }

  void check(
      List<F> fs,
      List<F> roots,
      Map<String, List<F>> out,
      Map<String, List<Dataset.Demand>> attachments,
      Map<String, BigDecimal> flows) {
    List<F> actual = new ArrayList<>();
    for (F f : fs) if (f.type.equals("heat_network_reconstruction")) actual.add(f);
    if (v.data.reduced) {
      if (!actual.isEmpty())
        v.error("REDUCED_RECONSTRUCTION", actual.get(0), "No existing flow data");
      return;
    }
    Map<String, List<Load>> loads = new TreeMap<>();
    for (F root : roots) {
      Dataset.Feature target = v.data.features.get(root.s("existing_object_id"));
      if (target == null) continue;
      BigDecimal added =
          out.getOrDefault(root.id, List.of()).stream()
              .map(f -> flows.getOrDefault(f.id, BigDecimal.ZERO))
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      for (Dataset.Demand d : attachments.getOrDefault(root.id, List.of()))
        added = added.add(d.flow);
      VerifierTopology.Node node;
      if (target.type.equals("heat_chamber")) node = v.existing.anchors.get(target.id);
      else {
        double at = new LengthIndexedLine(target.geometry).project(root.g.getCoordinate());
        VerifierTopology.Edge seg = v.existing.edgeAt(target.id, at);
        node = seg.u.depth < seg.v.depth ? seg.u : seg.v;
        load(loads, target.id, at, node == seg.u ? seg.a : seg.b, added);
      }
      if (v.existing.ambiguousComponents.contains(node.component)) {
        v.error("AMBIGUOUS_TOPOLOGY", root, "Cannot certify upstream reconstruction");
        continue;
      }
      Set<VerifierTopology.Node> visited = new HashSet<>();
      while (node.depth > 0 && visited.add(node)) {
        VerifierTopology.Edge up = null;
        for (VerifierTopology.Edge e : node.edges)
          if (e.other(node).depth < node.depth) {
            if (up != null) {
              v.error("AMBIGUOUS_TOPOLOGY", root, "Multiple upstream paths");
              break;
            }
            up = e;
          }
        if (up == null) {
          v.error("SOURCE_REACHABILITY", root, "Missing upstream path");
          break;
        }
        load(loads, up.feature.id, up.a, up.b, added);
        node = up.other(node);
      }
    }
    List<Expected> expected = new ArrayList<>();
    for (String id : loads.keySet()) {
      Dataset.Feature f = v.data.features.get(id);
      TreeSet<Double> bounds = new TreeSet<>();
      for (Load l : loads.get(id)) {
        bounds.add(l.a);
        bounds.add(l.b);
      }
      Double prev = null;
      for (double at : bounds) {
        if (prev != null) {
          BigDecimal added = BigDecimal.ZERO;
          double mid = (prev + at) / 2;
          for (Load l : loads.get(id)) if (mid >= l.a && mid <= l.b) added = added.add(l.flow);
          int dn = Catalog.base(f.flow().add(added)).dn;
          if (added.signum() > 0 && dn > f.dn()) expected.add(new Expected(f, prev, at, added, dn));
        }
        prev = at;
      }
    }
    Set<Expected> seen = new HashSet<>();
    for (F f : actual) {
      Expected match = null;
      for (Expected e : expected)
        if (e.f.id.equals(f.s("existing_object_id")) && e.line.equalsExact(f.g, 2e-6)) {
          match = e;
          break;
        }
      if (match == null) {
        v.error("RECONSTRUCTION_GEOMETRY", f, "Unexpected interval");
        continue;
      }
      if (!seen.add(match)) v.error("DUPLICATE_RECONSTRUCTION", f, "Repeated interval");
      v.equal(f, "existing_flow_tph", match.f.flow(), BigDecimal.ZERO);
      v.equal(f, "added_flow_tph", match.flow, BigDecimal.ZERO);
      v.equal(f, "calculated_flow_tph", match.f.flow().add(match.flow), BigDecimal.ZERO);
      if (f.i("required_diameter") != match.dn || f.i("existing_diameter") != match.f.dn())
        v.error("RECONSTRUCTION_DN", f, "Incorrect DN");
      v.equal(f, "length", Catalog.length(f.g.getLength()), new BigDecimal("0.000000002"));
      v.equal(
          f,
          "cost",
          v.rules.money(f.n("length").multiply(Catalog.pipe(match.dn).reconstructionPrice)),
          BigDecimal.ZERO);
    }
    if (seen.size() != expected.size())
      v.error("MISSING_RECONSTRUCTION", null, "Required intervals absent");
  }

  private static void load(
      Map<String, List<Load>> map, String id, double a, double b, BigDecimal g) {
    if (Math.abs(a - b) > 1e-8)
      map.computeIfAbsent(id, k -> new ArrayList<>()).add(new Load(a, b, g));
  }
}
