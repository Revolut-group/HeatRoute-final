package ru.heatroute;

import java.math.*;
import java.util.*;
import org.locationtech.jts.geom.*;

public final class Exporter {
  private final Dataset data;
  private final Existing existing;
  private final Rules rules;
  private int counter;
  private String variant, prefix;

  public Exporter(Dataset d, Existing e, Rules r) {
    data = d;
    existing = e;
    rules = r;
  }

  public Network.Variant export(List<Network.Tree> input, int rank) {
    if (rules.current()) return new CurrentExporter(data, rules).export(input, rank);
    counter = 0;
    variant = "variant_" + rank;
    prefix = "hrv5_" + rank + "_";
    while (data.features.keySet().stream().anyMatch(id -> id.startsWith(prefix)))
      prefix = "_" + prefix;
    List<Network.Tree> trees = new ArrayList<>(input);
    trees.sort(Comparator.comparing(t -> t.root.key + ":" + t.demands));
    List<Map<String, Object>> out = new ArrayList<>();
    Map<String, BigDecimal> sums = new LinkedHashMap<>();
    for (String k :
        List.of(
            "construction_cost",
            "chamber_construction_cost",
            "tie_in_cost",
            "reconstruction_cost",
            "chamber_reconstruction_cost",
            "unconnected_penalty",
            "new_network_length",
            "reconstruction_length")) sums.put(k, BigDecimal.ZERO);
    List<Reconstruction.Part> recon = Reconstruction.calculate(data, existing, trees);
    Map<String, Integer> anchorDn = new TreeMap<>();
    Map<String, Existing.Root> anchors = new TreeMap<>();
    for (Network.Tree t : trees) {
      int dn =
          Math.max(
              t.root.existingDn,
              t.edges.stream()
                  .filter(e -> e.from == t.start)
                  .mapToInt(e -> e.dn)
                  .max()
                  .orElseGet(
                      () ->
                          Catalog.base(
                                  t.start.demands.stream()
                                      .map(d -> d.flow)
                                      .reduce(BigDecimal.ZERO, BigDecimal::add))
                              .dn));
      for (Reconstruction.Part p : recon)
        if ((t.root.target.type.equals("heat_network")
                ? p.feature.id.equals(t.root.target.id)
                : existing.anchors.get(t.root.target.id).edges.stream()
                    .anyMatch(e -> e.feature.id.equals(p.feature.id)))
            && p.line.distance(Geo.point(t.root.p)) < .1) dn = Math.max(dn, p.dn);
      anchors.put(t.root.key, t.root);
      anchorDn.merge(t.root.key, dn, Math::max);
    }
    Set<String> used = new TreeSet<>();
    for (Network.Tree t : trees) {
      used.addAll(t.demands);
      Map<Network.Node, String> ids = new IdentityHashMap<>();
      String tie = id();
      ids.put(t.start, tie);
      int rayDn =
          t.edges.stream()
              .filter(e -> e.from == t.start)
              .mapToInt(e -> e.dn)
              .max()
              .orElseGet(
                  () ->
                      Catalog.base(
                              t.start.demands.stream()
                                  .map(d -> d.flow)
                                  .reduce(BigDecimal.ZERO, BigDecimal::add))
                          .dn);
      Map<String, Object> tp = props(tie, "tie_in");
      tp.put("existing_object_id", t.root.target.id);
      tp.put("existing_object_type", t.root.target.type);
      tp.put("existing_diameter", t.root.existingDn);
      tp.put("required_diameter", rayDn);
      tp.put("cost", rules.money(rules.decimal("cost.tie_in_rub")));
      out.add(feature(Geo.point(t.root.p), tp));
      add(sums, "tie_in_cost", (BigDecimal) tp.get("cost"));
      List<Network.Node> ns = t.nodes();
      ns.sort(Comparator.comparing(n -> n.key));
      for (Network.Node n : ns)
        if (n != t.start) {
          if (n.terminalId != null) ids.put(n, n.terminalId);
          else {
            String key = id();
            ids.put(n, key);
            int dn =
                t.edges.stream()
                    .filter(e -> e.from == n || e.to == n)
                    .mapToInt(e -> e.dn)
                    .max()
                    .orElseThrow();
            Map<String, Object> p = props(key, "heat_chamber");
            p.put("diameter", dn);
            p.put("cost", rules.money(Catalog.chamber(dn)));
            out.add(feature(Geo.point(n.p), p));
            add(sums, "chamber_construction_cost", (BigDecimal) p.get("cost"));
          }
        }
      List<Network.Edge> es = new ArrayList<>(t.edges);
      es.sort(Comparator.comparing(e -> e.from.key + ">" + e.to.key));
      for (Network.Edge e : es) emitEdge(out, sums, e, ids.get(e.from), ids.get(e.to));
    }
    for (String key : anchors.keySet()) {
      Existing.Root root = anchors.get(key);
      int dn = anchorDn.get(key);
      if (root.target.type.equals("heat_network")) {
        Map<String, Object> p = props(id(), "heat_chamber");
        p.put("diameter", dn);
        p.put("cost", rules.money(Catalog.chamber(dn)));
        out.add(feature(Geo.point(root.p), p));
        add(sums, "chamber_construction_cost", (BigDecimal) p.get("cost"));
      } else if (dn > root.existingDn) {
        Map<String, Object> p = props(id(), "heat_chamber_reconstruction");
        p.put("existing_object_id", root.target.id);
        p.put("existing_diameter", root.existingDn);
        p.put("required_diameter", dn);
        p.put("cost", rules.money(Catalog.chamber(dn)));
        out.add(feature(Geo.point(root.p), p));
        add(sums, "chamber_reconstruction_cost", (BigDecimal) p.get("cost"));
      }
    }
    for (Reconstruction.Part part : recon) {
      BigDecimal len = Catalog.length(part.line.getLength()),
          cost = rules.money(len.multiply(Catalog.pipe(part.dn).reconstructionPrice));
      Map<String, Object> p = props(id(), "heat_network_reconstruction");
      p.put("existing_object_id", part.feature.id);
      p.put("existing_flow_tph", part.feature.flow());
      p.put("added_flow_tph", part.added);
      p.put("calculated_flow_tph", part.feature.flow().add(part.added));
      p.put("existing_diameter", part.feature.dn());
      p.put("required_diameter", part.dn);
      p.put("length", len);
      p.put("cost", cost);
      out.add(feature(part.line, p));
      add(sums, "reconstruction_cost", cost);
      add(sums, "reconstruction_length", len);
    }
    List<String> missed = new ArrayList<>();
    for (Dataset.Demand d : data.demands)
      if (!used.contains(d.id)) {
        missed.add(d.id);
        add(sums, "unconnected_penalty", rules.penalty(d.flow));
      }
    Map<String, Object> summary = props(id(), "variant_summary");
    summary.put("rank", rank);
    BigDecimal cost = BigDecimal.ZERO;
    for (Map.Entry<String, BigDecimal> s : sums.entrySet()) {
      boolean length = s.getKey().endsWith("length");
      BigDecimal v =
          length ? s.getValue().setScale(9, RoundingMode.HALF_UP) : rules.money(s.getValue());
      summary.put(s.getKey(), v);
      if (!length) cost = cost.add(v);
    }
    BigDecimal length =
        sums.get("new_network_length")
            .add(sums.get("reconstruction_length"))
            .setScale(9, RoundingMode.HALF_UP);
    summary.put("calculated_cost", cost);
    summary.put("length", length);
    summary.put("score", rules.score(cost, length));
    summary.put("unconnected_oks_ids", missed);
    out.add(feature(null, summary));
    List<String> order =
        List.of(
            "heat_network",
            "tie_in",
            "heat_network_reconstruction",
            "heat_chamber",
            "heat_chamber_reconstruction",
            "technical_node",
            "variant_summary");
    out.sort(
        Comparator.comparingInt(
                (Map<String, Object> f) ->
                    order.indexOf(((Map<?, ?>) f.get("properties")).get("object_type")))
            .thenComparing(f -> ((Map<?, ?>) f.get("properties")).get("id").toString()));
    return new Network.Variant(trees, summary, out);
  }

  private void emitEdge(
      List<Map<String, Object>> out,
      Map<String, BigDecimal> sums,
      Network.Edge e,
      String start,
      String end) {
    Coordinate[] c = e.line.getCoordinates();
    TreeSet<Double> cuts = new TreeSet<>();
    cuts.add(0.0);
    cuts.add(e.line.getLength());
    for (Intervals.Zone z : e.zones) {
      cuts.add(z.a);
      cuts.add(z.b);
    }
    List<Intervals.Zone> turns = new ArrayList<>();
    double s = 0;
    boolean whole = false;
    for (int i = 1; i < c.length; i++) {
      double l = c[i - 1].distance(c[i]);
      double k = 1;
      if (i > 1) {
        double turn = Geo.turn(c[i - 2], c[i - 1], c[i]);
        double tolerance = rules.number("cost.standard_turn_tolerance_deg");
        if (turn > tolerance
            && Math.abs(turn - 45) > tolerance
            && Math.abs(turn - 90) > tolerance) {
          k = rules.number("cost.nonstandard_turn_factor");
          whole = true;
        }
      }
      turns.add(new Intervals.Zone(s, s + l, k, "turn"));
      s += l;
    }
    if (rules.text("cost.turn_scope").equals("whole_logical_section") && whole)
      turns =
          List.of(
              new Intervals.Zone(
                  0, e.line.getLength(), rules.number("cost.nonstandard_turn_factor"), "turn"));
    double lastK = 1;
    for (Intervals.Zone z : turns) {
      if (Math.abs(z.k - lastK) > 1e-9) cuts.add(z.a);
      lastK = z.k;
    }
    List<Double> stations = new ArrayList<>(cuts);
    String prev = start;
    for (int i = 1; i < stations.size(); i++) {
      double a = stations.get(i - 1), b = stations.get(i);
      if (b - a < 1e-7) continue;
      LineString piece = Geo.canonical(Geo.sub(e.line, a, b));
      String next = i == stations.size() - 1 ? end : id();
      if (!next.equals(end)) out.add(feature(piece.getEndPoint(), props(next, "technical_node")));
      BigDecimal len = Catalog.length(piece.getLength());
      double mid = (a + b) / 2,
          k = Intervals.factor(e.zones, mid),
          kt = Intervals.factor(turns, mid);
      BigDecimal cost =
          rules.money(
              len.multiply(Catalog.pipe(e.dn).newPrice)
                  .multiply(BigDecimal.valueOf(k))
                  .multiply(BigDecimal.valueOf(kt)));
      Map<String, Object> p = props(id(), "heat_network");
      p.put("start_node_id", prev);
      p.put("end_node_id", next);
      p.put("flow_tph", e.flow);
      p.put("diameter", e.dn);
      p.put("length", len);
      p.put("laying_method", k > 1 ? "special" : "base");
      p.put("depth_start", null);
      p.put("depth_end", null);
      p.put("cost", cost);
      out.add(feature(piece, p));
      add(sums, "construction_cost", cost);
      add(sums, "new_network_length", len);
      prev = next;
    }
  }

  private String id() {
    String value;
    do {
      value = prefix + String.format(Locale.ROOT, "%06d", ++counter);
    } while (data.containsId(value));
    return value;
  }

  private Map<String, Object> props(String id, String type) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("id", id);
    p.put("object_type", type);
    p.put("variant_id", variant);
    return p;
  }

  private static Map<String, Object> feature(Geometry geometry, Map<String, Object> properties) {
    Map<String, Object> f = new LinkedHashMap<>();
    f.put("type", "Feature");
    f.put("geometry", geometry == null ? null : Geo.json(geometry));
    f.put("properties", properties);
    return f;
  }

  private static void add(Map<String, BigDecimal> s, String k, BigDecimal v) {
    s.put(k, s.get(k).add(v));
  }

  public static Map<String, Object> collection(List<Network.Variant> variants) {
    List<Map<String, Object>> f = new ArrayList<>();
    for (Network.Variant v : variants) f.addAll(v.features);
    Map<String, Object> collection = new LinkedHashMap<>();
    collection.put("type", "FeatureCollection");
    collection.put("features", f);
    return collection;
  }
}
