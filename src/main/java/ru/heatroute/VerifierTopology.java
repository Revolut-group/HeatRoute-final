package ru.heatroute;

import java.util.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.linearref.LengthIndexedLine;

/** Independent source-network reconstruction used only by the saved-result verifier. */
final class VerifierTopology {
  static final class Node {
    final Coordinate p;
    final String anchor;
    final List<Coordinate> members = new ArrayList<>();
    final List<Edge> edges = new ArrayList<>();
    int component = -1, depth = -1;

    Node(Coordinate p, String anchor) {
      this.p = p.copy();
      this.anchor = anchor;
      members.add(p.copy());
    }
  }

  static final class Edge {
    final Dataset.Feature feature;
    final double a, b;
    final Node u, v;

    Edge(Dataset.Feature f, double a, double b, Node u, Node v) {
      feature = f;
      this.a = a;
      this.b = b;
      this.u = u;
      this.v = v;
    }

    Node other(Node n) {
      return u == n ? v : u;
    }
  }

  final Dataset data;
  final Rules rules;
  final List<Node> nodes = new ArrayList<>();
  final List<Edge> edges = new ArrayList<>();
  final Map<String, Node> anchors = new TreeMap<>();
  final Set<Integer> ambiguousComponents = new HashSet<>(), sourceComponents = new HashSet<>();
  int components;

  VerifierTopology(Dataset data, Rules rules) {
    this.data = data;
    this.rules = rules;
    double tolerance = rules.number("geometry.topology_snap_m");
    List<Dataset.Feature> marks = new ArrayList<>();
    marks.addAll(data.chambers);
    marks.addAll(data.sources);
    marks.sort(Comparator.comparing(f -> f.id));
    for (Dataset.Feature f : marks) anchors.put(f.id, assign(f.geometry.getCoordinate(), f.id));
    for (Dataset.Feature f : data.networks) {
      LineString geometry = (LineString) f.geometry;
      LengthIndexedLine indexed = new LengthIndexedLine(geometry);
      TreeMap<Double, Node> cuts = new TreeMap<>();
      cuts.put(0.0, assign(geometry.getCoordinateN(0), null));
      cuts.put(
          geometry.getLength(), assign(geometry.getCoordinateN(geometry.getNumPoints() - 1), null));
      for (Dataset.Feature mark : marks)
        if (geometry.distance(mark.geometry) <= tolerance) {
          double at = indexed.project(mark.geometry.getCoordinate());
          if (at < tolerance) at = 0;
          if (geometry.getLength() - at < tolerance) at = geometry.getLength();
          cuts.put(at, anchors.get(mark.id));
        }
      Double start = null;
      Node previous = null;
      for (Map.Entry<Double, Node> cut : cuts.entrySet()) {
        if (start != null && cut.getKey() - start > 1e-8) {
          Edge e = new Edge(f, start, cut.getKey(), previous, cut.getValue());
          edges.add(e);
          e.u.edges.add(e);
          e.v.edges.add(e);
        }
        start = cut.getKey();
        previous = cut.getValue();
      }
    }
    // Connected components are discovered without relying on optimizer parent pointers.
    for (Node unassigned : nodes)
      if (unassigned.component < 0) {
        int id = components++;
        Set<Node> group = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Node> pending = new ArrayDeque<>();
        pending.add(unassigned);
        while (!pending.isEmpty()) {
          Node n = pending.removeFirst();
          if (!group.add(n)) continue;
          n.component = id;
          for (Edge edge : n.edges) pending.addLast(edge.other(n));
        }
        Set<Node> sources = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Dataset.Feature source : data.sources)
          if (group.contains(anchors.get(source.id))) sources.add(anchors.get(source.id));
        if (sources.isEmpty()) continue;
        sourceComponents.add(id);
        int count = 0;
        for (Node n : group) count += n.edges.size();
        if (sources.size() != 1 || count / 2 != group.size() - 1) ambiguousComponents.add(id);
        List<Node> frontier = new ArrayList<>(sources);
        frontier.sort(Comparator.comparing(n -> Geo.key(n.p)));
        int depth = 0;
        while (!frontier.isEmpty()) {
          List<Node> next = new ArrayList<>();
          for (Node n : frontier)
            if (n.depth < 0) {
              n.depth = depth;
              for (Edge edge : n.edges) if (edge.other(n).depth < 0) next.add(edge.other(n));
            }
          frontier = next;
          depth++;
        }
      }
    for (Dataset.Feature c : data.chambers) {
      int actual = anchors.get(c.id).edges.stream().mapToInt(e -> e.feature.dn()).max().orElse(0);
      if (c.dn() > 0 && c.dn() < actual)
        throw new Failure("INVALID_ATTRIBUTE", "Chamber DN below incident pipe", c.id);
    }
  }

  private Node assign(Coordinate p, String anchor) {
    double tolerance = rules.number("geometry.topology_snap_m");
    for (Node n : nodes) {
      if (anchor != null && n.anchor != null && !anchor.equals(n.anchor)) continue;
      if (n.p.distance(p) > tolerance) continue;
      boolean complete = true;
      for (Coordinate original : n.members)
        if (original.distance(p) > tolerance) {
          complete = false;
          break;
        }
      if (complete) {
        n.members.add(p.copy());
        return n;
      }
    }
    Node created = new Node(p, anchor);
    nodes.add(created);
    return created;
  }

  int chamberDn(Dataset.Feature camera) {
    if (camera.dn() > 0) return camera.dn();
    return anchors.get(camera.id).edges.stream().mapToInt(e -> e.feature.dn()).max().orElse(0);
  }

  boolean connected(Dataset.Feature feature) {
    if (feature.type.equals("heat_chamber"))
      return sourceComponents.contains(anchors.get(feature.id).component);
    for (Edge e : edges)
      if (e.feature.id.equals(feature.id) && sourceComponents.contains(e.u.component)) return true;
    return false;
  }

  Edge edgeAt(String id, double station) {
    for (Edge edge : edges)
      if (edge.feature.id.equals(id) && station >= edge.a - 1e-6 && station <= edge.b + 1e-6)
        return edge;
    throw new Failure("AMBIGUOUS_TOPOLOGY", "No original interval for tie", id);
  }

  Map<String, Object> capabilities() {
    boolean explicit = data.networks.stream().allMatch(f -> f.properties.has("upstream_object_id")),
        flows = data.networks.stream().allMatch(f -> f.flow() != null);
    return Map.of(
        "explicit_topology",
        explicit,
        "inferred_topology",
        !explicit,
        "existing_flow_available",
        flows,
        "chamber_dn_known",
        data.chambers.stream().allMatch(c -> chamberDn(c) > 0),
        "reconstruction_evaluable",
        flows && ambiguousComponents.isEmpty(),
        "components",
        components,
        "edges",
        edges.size(),
        "vertices",
        nodes.size());
  }
}
