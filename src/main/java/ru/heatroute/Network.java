package ru.heatroute;

import java.math.*;
import java.util.*;
import org.locationtech.jts.geom.*;

public final class Network {
  public static final class Node {
    public final String key;
    public final Coordinate p;
    public final List<Dataset.Demand> demands;
    public final String terminalId;

    public Node(String key, Coordinate p) {
      this(key, p, new ArrayList<>(), null);
    }

    public Node(String key, Coordinate p, List<Dataset.Demand> d, String terminal) {
      this.key = key;
      this.p = Geo.canonical(p);
      demands = new ArrayList<>(d);
      terminalId = terminal;
    }
  }

  public static final class Edge {
    public final Node from, to;
    public LineString line;
    public BigDecimal flow;
    public int dn, base;
    public List<Intervals.Zone> zones = List.of();

    public Edge(Node from, Node to, LineString l) {
      this.from = from;
      this.to = to;
      line = l;
    }

    public Edge copy() {
      Edge e = new Edge(from, to, (LineString) line.copy());
      e.flow = flow;
      e.dn = dn;
      e.base = base;
      e.zones = zones;
      return e;
    }
  }

  public static final class Tree {
    public final Existing.Root root;
    public final Node start;
    public final List<Edge> edges = new ArrayList<>();
    public final SortedSet<String> demands = new TreeSet<>();

    public Tree(Existing.Root root) {
      this.root = root;
      start = new Node("root:" + root.key, root.p);
    }

    private Tree(Existing.Root r, Node n) {
      root = r;
      start = n;
    }

    public Tree copy() {
      Map<Node, Node> nodes = new IdentityHashMap<>();
      for (Node n : nodes()) nodes.put(n, new Node(n.key, n.p, n.demands, n.terminalId));
      Tree t = new Tree(root, nodes.get(start));
      for (Edge e : edges) {
        Edge c = e.copy();
        Edge n = new Edge(nodes.get(e.from), nodes.get(e.to), c.line);
        n.flow = c.flow;
        n.base = c.base;
        n.dn = c.dn;
        n.zones = c.zones;
        t.edges.add(n);
      }
      t.demands.addAll(demands);
      return t;
    }

    private String cachedSignature;

    public String signature() {
      if (cachedSignature != null) return cachedSignature;
      List<String> parts = new ArrayList<>();
      for (Edge e : edges)
        parts.add(e.from.key + ">" + e.to.key + ":" + Geo.fingerprint(e.line) + ":" + e.dn);
      Collections.sort(parts);
      return cachedSignature = root.key + parts + demands;
    }

    public List<Edge> outgoing(Node n) {
      List<Edge> o = new ArrayList<>();
      for (Edge e : edges) if (e.from == n) o.add(e);
      return o;
    }

    public List<Node> nodes() {
      LinkedHashSet<Node> n = new LinkedHashSet<>();
      n.add(start);
      for (Edge e : edges) {
        n.add(e.from);
        n.add(e.to);
      }
      return new ArrayList<>(n);
    }
  }

  public static final class Variant {
    public final List<Tree> trees;
    public final Map<String, Object> summary;
    public final List<Map<String, Object>> features;
    public final String signature;

    public Variant(
        List<Tree> trees, Map<String, Object> summary, List<Map<String, Object>> features) {
      this.trees = List.copyOf(trees);
      this.summary = summary;
      this.features = features;
      List<String> s = new ArrayList<>();
      for (Tree t : trees) s.add(t.signature());
      Collections.sort(s);
      signature = String.join("|", s);
    }

    public BigDecimal score() {
      return (BigDecimal) summary.get("score");
    }
  }
}
