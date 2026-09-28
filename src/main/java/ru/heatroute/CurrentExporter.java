package ru.heatroute;

import java.math.*;
import java.util.*;
import org.locationtech.jts.geom.*;

/** Four output object types defined by the 19 September appendix. */
final class CurrentExporter {
  private final Dataset data;
  private final Rules rules;
  private final boolean depthStage;
  private int count;
  private String variant, prefix;
  private Map<Network.Edge, DepthProfile.Profile> depth;

  /** Depth stage only: per tie-in sides of crossings, endpoint policy and depth range. */
  final List<Map<String, Object>> depthReport = new ArrayList<>();

  private final List<Map<String, Object>> features = new ArrayList<>();
  private BigDecimal pipes = BigDecimal.ZERO, chambers = BigDecimal.ZERO, length = BigDecimal.ZERO;

  CurrentExporter(Dataset d, Rules r) {
    this(d, r, false);
  }

  CurrentExporter(Dataset d, Rules r, boolean depthStage) {
    data = d;
    rules = r;
    this.depthStage = depthStage;
  }

  Network.Variant export(List<Network.Tree> input, int rank) {
    variant = "variant_" + rank;
    prefix = "lct19_" + rank + "_";
    while (data.features.keySet().stream().anyMatch(id -> id.startsWith(prefix)))
      prefix = "_" + prefix;
    List<Network.Tree> trees = new ArrayList<>(input);
    trees.sort(Comparator.comparing(t -> t.root.key + ":" + t.demands));
    if (depthStage) {
      DepthProfile solver = new DepthProfile(data, rules);
      depth = solver.solve(trees);
      depthReport.addAll(solver.diagnostics);
    }
    Map<String, Object> roots = new TreeMap<>();
    Map<String, Integer> rootDn = new TreeMap<>();
    Map<String, Existing.Root> anchors = new TreeMap<>();
    int ties = 0;
    Set<String> used = new TreeSet<>();
    for (Network.Tree tree : trees) {
      anchors.put(tree.root.key, tree.root);
      int dn =
          Math.max(
              tree.root.existingDn,
              tree.outgoing(tree.start).stream().mapToInt(e -> e.dn).max().orElse(50));
      rootDn.merge(tree.root.key, dn, Math::max);
      if (!roots.containsKey(tree.root.key))
        roots.put(
            tree.root.key,
            tree.root.target.type.equals("heat_chamber") ? original(tree.root.target.id) : id());
      if (tree.root.target.type.equals("heat_chamber")) ties += tree.outgoing(tree.start).size();
    }
    for (String key : roots.keySet())
      if (anchors.get(key).target.type.equals("heat_network"))
        chamber(roots.get(key), anchors.get(key).p, rootDn.get(key));
    for (Network.Tree tree : trees) {
      used.addAll(tree.demands);
      Map<Network.Node, Object> nodes = new IdentityHashMap<>();
      nodes.put(tree.start, roots.get(tree.root.key));
      List<Network.Node> ordered = tree.nodes();
      ordered.sort(Comparator.comparing(n -> n.key));
      for (Network.Node node : ordered)
        if (node != tree.start) {
          if (node.terminalId != null) nodes.put(node, original(node.terminalId));
          else {
            String id = id();
            nodes.put(node, id);
            chamber(
                id,
                node.p,
                tree.edges.stream()
                    .filter(e -> e.from == node || e.to == node)
                    .mapToInt(e -> e.dn)
                    .max()
                    .orElseThrow());
          }
        }
      List<Network.Edge> edges = new ArrayList<>(tree.edges);
      edges.sort(Comparator.comparing(e -> e.from.key + ">" + e.to.key));
      for (Network.Edge edge : edges) emit(edge, nodes.get(edge.from), nodes.get(edge.to));
    }
    BigDecimal penalty = BigDecimal.ZERO;
    List<Object> missed = new ArrayList<>();
    for (Dataset.Demand d : data.demands)
      if (!used.contains(d.id)) {
        missed.add(original(d.id));
        penalty = penalty.add(rules.penalty(d.flow));
      }
    for (Dataset.Demand d : data.invalidDemands) {
      missed.add(original(d.id));
      penalty = penalty.add(rules.penalty(d.flow));
    }
    BigDecimal
        tieCost = rules.money(rules.decimal("cost.tie_in_rub").multiply(BigDecimal.valueOf(ties))),
        construction = pipes.add(chambers).add(tieCost),
        cost = construction.add(penalty);
    Map<String, Object> summary = props(id(), "variant_summary");
    summary.put("rank", rank);
    summary.put("construction_cost", construction);
    summary.put("chamber_construction_cost", chambers);
    summary.put("existing_chamber_tie_in_count", ties);
    summary.put("existing_chamber_tie_in_cost", tieCost);
    summary.put("unconnected_penalty", penalty);
    summary.put("calculated_cost", cost);
    summary.put("new_network_length", length);
    summary.put("score", rules.score(cost, length));
    summary.put("unconnected_oks_ids", missed);
    feature(null, summary);
    return new Network.Variant(trees, summary, features);
  }

  private Object original(String id) {
    return data.features.get(id).properties.get("id");
  }

  private void chamber(Object id, Coordinate p, int dn) {
    Map<String, Object> properties = props(id, "heat_chamber");
    BigDecimal cost = rules.money(Catalog.chamber(dn));
    properties.put("diameter", dn);
    properties.put("cost", cost);
    feature(Geo.point(p), properties);
    chambers = chambers.add(cost);
  }

  private void emit(Network.Edge edge, Object start, Object end) {
    TreeSet<Double> cuts = new TreeSet<>();
    cuts.add(0.0);
    cuts.add(edge.line.getLength());
    for (Intervals.Zone zone : edge.zones) {
      cuts.add(zone.a);
      cuts.add(zone.b);
    }
    DepthProfile.Profile profile = depth == null ? null : depth.get(edge);
    if (profile != null)
      for (double s : profile.s) {
        Double below = cuts.floor(s), above = cuts.ceiling(s);
        if ((below == null || s - below > 1e-6) && (above == null || above - s > 1e-6)) cuts.add(s);
      }
    Double depthAt = profile == null ? null : DepthProfile.round(profile.at(0));
    List<Double> stations = new ArrayList<>(cuts);
    Object previous = start;
    for (int i = 1; i < stations.size(); i++) {
      double a = stations.get(i - 1), b = stations.get(i);
      if (b - a < 1e-7) continue;
      LineString line = Geo.canonical(Geo.sub(edge.line, a, b));
      Object next = i == stations.size() - 1 ? end : id();
      if (i < stations.size() - 1) feature(line.getEndPoint(), props(next, "technical_node"));
      Double depthEnd = profile == null ? null : DepthProfile.round(profile.at(b));
      BigDecimal len = Catalog.length(line.getLength()),
          cost =
              rules.money(
                  len.multiply(Catalog.pipe(edge.dn).newPrice)
                      .multiply(BigDecimal.valueOf(Intervals.factor(edge.zones, (a + b) / 2)))
                      .multiply(
                          profile == null
                              ? BigDecimal.ONE
                              : DepthProfile.factor(depthAt, depthEnd)));
      Map<String, Object> p = props(id(), "heat_network");
      p.put("start_node_id", previous);
      p.put("end_node_id", next);
      p.put("flow_tph", edge.flow);
      p.put("diameter", edge.dn);
      p.put("length", len);
      p.put("laying_method", Intervals.factor(edge.zones, (a + b) / 2) > 1 ? "special" : "base");
      p.put("depth_start", depthAt);
      p.put("depth_end", depthEnd);
      p.put("cost", cost);
      feature(line, p);
      pipes = pipes.add(cost);
      length = length.add(len);
      previous = next;
      depthAt = depthEnd;
    }
  }

  private String id() {
    String id;
    do {
      id = prefix + (++count);
    } while (data.containsId(id));
    return id;
  }

  private Map<String, Object> props(Object id, String type) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("id", id);
    p.put("object_type", type);
    p.put("variant_id", variant);
    return p;
  }

  private void feature(Geometry geometry, Map<String, Object> p) {
    Map<String, Object> f = new LinkedHashMap<>();
    f.put("type", "Feature");
    f.put("geometry", geometry == null ? null : Geo.json(geometry));
    f.put("properties", p);
    features.add(f);
  }
}
