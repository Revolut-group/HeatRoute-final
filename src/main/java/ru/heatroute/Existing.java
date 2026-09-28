package ru.heatroute;

import java.util.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.linearref.LengthIndexedLine;

/** Station-based graph; XY intersections without a chamber are never inserted. */
public final class Existing {
  public static final class Node {
    public final Coordinate p;
    public final String anchor;
    public final List<Edge> edges = new ArrayList<>();
    public int component = -1, depth = -1;
    public Edge parent;

    Node(Coordinate p, String a) {
      this.p = p.copy();
      anchor = a;
    }
  }

  public static final class Edge {
    public final Dataset.Feature feature;
    public final double a, b;
    public final Node u, v;

    Edge(Dataset.Feature f, double a, double b, Node u, Node v) {
      feature = f;
      this.a = a;
      this.b = b;
      this.u = u;
      this.v = v;
    }

    public Node other(Node n) {
      return n == u ? v : u;
    }

    public boolean forward() {
      return u.depth < v.depth;
    }
  }

  public static final class Root {
    public final Dataset.Feature target;
    public final Coordinate p;
    public final double station;
    public final int existingDn, degree;
    public final String key;

    public Root(Dataset.Feature f, Coordinate p, double s, int dn, int degree) {
      target = f;
      this.p = Geo.canonical(p);
      station =
          f.geometry instanceof LineString ? new LengthIndexedLine(f.geometry).project(this.p) : s;
      existingDn = dn;
      this.degree = degree;
      key = f.id + ":" + Geo.key(this.p);
    }
  }

  public final Dataset data;
  public final Rules rules;
  public final List<Node> nodes = new ArrayList<>();
  public final List<Edge> edges = new ArrayList<>();
  public final Map<String, Node> anchors = new TreeMap<>();
  public final Set<Integer> sourceComponents = new HashSet<>(),
      ambiguousComponents = new HashSet<>();
  private final Set<String> cameraKeys = new HashSet<>();
  public int components;

  public Existing(Dataset d, Rules r) {
    data = d;
    rules = r;
    build();
    for (Dataset.Feature camera : data.chambers)
      cameraKeys.add(Geo.key(camera.geometry.getCoordinate()));
  }

  private Node node(Coordinate p, String anchor) {
    double snap = rules.number("geometry.topology_snap_m");
    for (Node n : nodes)
      if (n.p.distance(p) <= snap
          && (anchor == null || n.anchor == null || n.anchor.equals(anchor))) {
        boolean fits = true;
        for (Edge e : n.edges) {
          Coordinate q =
              e.u == n
                  ? new LengthIndexedLine(e.feature.geometry).extractPoint(e.a)
                  : new LengthIndexedLine(e.feature.geometry).extractPoint(e.b);
          if (q.distance(p) > snap) {
            fits = false;
            break;
          }
        }
        if (fits) return n;
      }
    Node n = new Node(p, anchor);
    nodes.add(n);
    return n;
  }

  private void build() {
    List<Dataset.Feature> marked = new ArrayList<>(data.chambers);
    marked.addAll(data.sources);
    marked.sort(Comparator.comparing(f -> f.id));
    for (Dataset.Feature f : marked) anchors.put(f.id, node(f.geometry.getCoordinate(), f.id));
    double snap = rules.number("geometry.topology_snap_m");
    // A chamber that touches no line within topology_snap_m is attached to the nearest line within
    // geometry.chamber_snap_m (current profile): real survey data places chamber points beside the
    // pipe axis.
    Map<String, Dataset.Feature> snapped = new HashMap<>();
    if (rules.current())
      for (Dataset.Feature c : data.chambers) {
        Dataset.Feature best = null;
        double bestDistance = rules.number("geometry.chamber_snap_m");
        boolean touching = false;
        for (Dataset.Feature f : data.networks) {
          double d = f.geometry.distance(c.geometry);
          if (d <= snap) {
            touching = true;
            break;
          }
          if (d <= bestDistance
              && (best == null || d < bestDistance || f.id.compareTo(best.id) < 0)) {
            best = f;
            bestDistance = d;
          }
        }
        if (!touching && best != null) snapped.put(c.id, best);
      }
    for (Dataset.Feature f : data.networks) {
      LineString line = (LineString) f.geometry;
      LengthIndexedLine li = new LengthIndexedLine(line);
      TreeMap<Double, Node> stations = new TreeMap<>();
      stations.put(0.0, node(line.getCoordinateN(0), null));
      stations.put(line.getLength(), node(line.getCoordinateN(line.getNumPoints() - 1), null));
      for (Dataset.Feature m : marked)
        if (line.distance(m.geometry) <= snap || snapped.get(m.id) == f) {
          double s = li.project(m.geometry.getCoordinate());
          if (s < snap) s = 0;
          if (line.getLength() - s < snap) s = line.getLength();
          stations.put(s, anchors.get(m.id));
        }
      Map.Entry<Double, Node> prev = null;
      for (Map.Entry<Double, Node> at : stations.entrySet()) {
        if (prev != null && at.getKey() - prev.getKey() > 1e-8) {
          Edge e = new Edge(f, prev.getKey(), at.getKey(), prev.getValue(), at.getValue());
          edges.add(e);
          e.u.edges.add(e);
          e.v.edges.add(e);
        }
        prev = at;
      }
    }
    for (Node start : nodes)
      if (start.component < 0) {
        int component = components++;
        List<Node> cc = new ArrayList<>();
        Deque<Node> q = new ArrayDeque<>();
        q.add(start);
        start.component = component;
        while (!q.isEmpty()) {
          Node n = q.remove();
          cc.add(n);
          for (Edge e : n.edges) {
            Node o = e.other(n);
            if (o.component < 0) {
              o.component = component;
              q.add(o);
            }
          }
        }
        List<Node> sources = new ArrayList<>();
        for (Dataset.Feature s : data.sources) {
          Node n = anchors.get(s.id);
          if (n.component == component) sources.add(n);
        }
        if (sources.isEmpty()) continue;
        sourceComponents.add(component);
        int ec = cc.stream().mapToInt(n -> n.edges.size()).sum() / 2;
        if (sources.size() != 1 || ec != cc.size() - 1) ambiguousComponents.add(component);
        Node root = sources.get(0);
        root.depth = 0;
        q.add(root);
        while (!q.isEmpty()) {
          Node n = q.remove();
          for (Edge e : n.edges) {
            Node o = e.other(n);
            if (o.depth < 0) {
              o.depth = n.depth + 1;
              o.parent = e;
              q.add(o);
            }
          }
        }
      }
    for (Dataset.Feature feature : data.features.values())
      if (!rules.current()
          && feature.properties.has("upstream_object_id")
          && Set.of("heat_network", "heat_chamber").contains(feature.type)) {
        Dataset.Feature target =
            data.features.get(Ingest.id(feature.properties.get("upstream_object_id")));
        if (target == null) continue;
        Node upstream =
            feature.type.equals("heat_chamber")
                ? anchors.get(feature.id)
                : edges.stream()
                    .filter(e -> e.feature == feature)
                    .flatMap(e -> java.util.stream.Stream.of(e.u, e.v))
                    .min(Comparator.comparingInt(n -> n.depth < 0 ? Integer.MAX_VALUE : n.depth))
                    .orElse(null);
        if (upstream == null || upstream.depth < 0)
          throw new Failure(
              "AMBIGUOUS_TOPOLOGY", "Explicit upstream is not connected to a source", feature.id);
        if (target.type.equals("heat_network")) {
          boolean incident =
              upstream.edges.stream()
                  .anyMatch(e -> e.feature == target && e.other(upstream).depth < upstream.depth);
          if (!incident)
            throw new Failure(
                "AMBIGUOUS_TOPOLOGY",
                "Explicit upstream disagrees with geometric orientation",
                feature.id);
        } else if (anchors.get(target.id) != upstream)
          throw new Failure(
              "AMBIGUOUS_TOPOLOGY", "Explicit upstream anchor disagrees with geometry", feature.id);
      }
    for (Dataset.Feature c : data.chambers) {
      int inferred = anchors.get(c.id).edges.stream().mapToInt(e -> e.feature.dn()).max().orElse(0);
      if (!rules.current() && c.dn() != 0 && c.dn() < inferred)
        throw new Failure("INVALID_ATTRIBUTE", "Chamber DN below incident network DN", c.id);
    }
  }

  public int chamberDn(Dataset.Feature c) {
    return !rules.current() && c.dn() != 0
        ? c.dn()
        : anchors.get(c.id).edges.stream().mapToInt(e -> e.feature.dn()).max().orElse(0);
  }

  public boolean connected(Dataset.Feature f) {
    if (rules.current()) return f.type.equals("heat_network") || !anchors.get(f.id).edges.isEmpty();
    if (f.type.equals("heat_chamber"))
      return sourceComponents.contains(anchors.get(f.id).component);
    return edges.stream().anyMatch(e -> e.feature == f && sourceComponents.contains(e.u.component));
  }

  public Edge edgeAt(String id, double station) {
    return edges.stream()
        .filter(e -> e.feature.id.equals(id) && station >= e.a - 1e-6 && station <= e.b + 1e-6)
        .findFirst()
        .orElseThrow(() -> new Failure("AMBIGUOUS_TOPOLOGY", "No edge at station", id));
  }

  private Root lineRoot(Dataset.Feature target, Coordinate point, double station) {
    if (!rules.current()) return new Root(target, point, station, target.dn(), 2);
    int degree = 0, dn = target.dn();
    for (Dataset.Feature feature : data.networks)
      if (feature.geometry.distance(Geo.point(point)) <= rules.number("geometry.topology_snap_m")) {
        double along = new LengthIndexedLine(feature.geometry).project(point);
        degree +=
            along < rules.number("geometry.topology_snap_m")
                    || feature.geometry.getLength() - along
                        < rules.number("geometry.topology_snap_m")
                ? 1
                : 2;
        dn = Math.max(dn, feature.dn());
      }
    return new Root(target, point, station, dn, degree);
  }

  private List<Root> fixedRoots;
  private List<Dataset.Feature> available;

  /**
   * Clarification 11: an existing chamber must be used for a tie-in within chamber_radius_m when it
   * is on the network (snapped as above), has fewer than four incident sections and a known DN.
   * Shared by the root generators and CurrentVerifier.
   */
  public boolean eligible(Dataset.Feature c) {
    return anchors.containsKey(c.id)
        && connected(c)
        && anchors.get(c.id).edges.size() < 4
        && chamberDn(c) > 0;
  }

  public List<Dataset.Feature> availableChambers() {
    if (available == null) {
      List<Dataset.Feature> a = new ArrayList<>();
      for (Dataset.Feature c : data.chambers) if (eligible(c)) a.add(c);
      available = Collections.unmodifiableList(a);
    }
    return available;
  }

  /** Existing incident sections of an existing chamber (after snapping). */
  public int degree(Dataset.Feature c) {
    return anchors.get(c.id).edges.size();
  }

  /**
   * A new chamber (line tie-in) at p is not allowed: an available existing chamber is within
   * chamber_radius_m + chamber_radius_margin_m. The margin keeps generated roots clear of the
   * verifier's exact 10 m test.
   */
  public boolean forcesChamber(Coordinate p) {
    double margin = rules.number("geometry.chamber_radius_margin_m");
    for (Dataset.Feature c : availableChambers())
      if (rules.nearChamber(p.distance(c.geometry.getCoordinate()) - margin)) return true;
    return cameraKeys.contains(Geo.key(p));
  }

  public List<Root> roots(Coordinate point) {
    if (fixedRoots == null) fixedRoots = generateRoots(null);
    TreeMap<String, Root> candidates = new TreeMap<>();
    for (Root root : fixedRoots) candidates.put(root.key, root);
    for (Dataset.Feature f : data.networks)
      if (connected(f)) {
        LengthIndexedLine li = new LengthIndexedLine(f.geometry);
        double station = li.project(point);
        Coordinate p = li.extractPoint(station);
        if (!forcesChamber(p)) {
          Root root = lineRoot(f, p, station);
          candidates.put(root.key, root);
        }
      }
    List<Root> sorted = new ArrayList<>(candidates.values());
    sorted.sort(
        Comparator.comparingDouble((Root r) -> r.p.distance(point)).thenComparing(r -> r.key));
    return sorted;
  }

  private List<Root> generateRoots(Coordinate point) {
    TreeMap<String, Root> roots = new TreeMap<>();
    for (Dataset.Feature c : availableChambers()) {
      Root root =
          new Root(c, c.geometry.getCoordinate(), 0, chamberDn(c), anchors.get(c.id).edges.size());
      roots.put(root.key, root);
    }
    for (Dataset.Feature f : data.networks)
      if (connected(f)) {
        LengthIndexedLine li = new LengthIndexedLine(f.geometry);
        TreeSet<Double> ss = new TreeSet<>();
        if (point != null) ss.add(li.project(point));
        double l = f.geometry.getLength();
        ss.add(0.0);
        ss.add(l);
        for (double s = 0; s < l; s += 25) {
          ss.add(s);
          ss.add(l - s);
        }
        for (Dataset.Feature c : data.chambers)
          if (f.geometry.distance(c.geometry) < rules.number("geometry.chamber_radius_m") + 1) {
            double s = li.project(c.geometry.getCoordinate()),
                h = li.extractPoint(s).distance(c.geometry.getCoordinate()),
                r =
                    rules.number("geometry.chamber_radius_m")
                        + rules.number("geometry.chamber_radius_margin_m")
                        + .02,
                d = Math.sqrt(Math.max(0, r * r - h * h));
            ss.add(Math.max(0, s - d));
            ss.add(Math.min(l, s + d));
          }
        for (double s : ss) {
          Coordinate p = li.extractPoint(s);
          if (!forcesChamber(p)) {
            Root root = lineRoot(f, p, s);
            roots.put(root.key, root);
          }
        }
      }
    List<Root> list = new ArrayList<>(roots.values());
    if (point != null)
      list.sort(
          Comparator.comparingDouble((Root a) -> a.p.distance(point)).thenComparing(a -> a.key));
    return list;
  }

  public Map<String, Object> capabilities() {
    if (rules.current())
      return Map.of(
          "existing_flow_required",
          false,
          "reconstruction_evaluable",
          false,
          "components",
          components,
          "edges",
          edges.size(),
          "vertices",
          nodes.size());
    boolean explicit = data.networks.stream().allMatch(f -> f.properties.has("upstream_object_id"));
    return Map.of(
        "explicit_topology",
        explicit,
        "inferred_topology",
        !explicit,
        "existing_flow_available",
        data.networks.stream().allMatch(f -> f.flow() != null),
        "chamber_dn_known",
        data.chambers.stream().allMatch(c -> chamberDn(c) > 0),
        "reconstruction_evaluable",
        data.networks.stream().allMatch(f -> f.flow() != null) && ambiguousComponents.isEmpty(),
        "components",
        components,
        "edges",
        edges.size(),
        "vertices",
        nodes.size());
  }
}
