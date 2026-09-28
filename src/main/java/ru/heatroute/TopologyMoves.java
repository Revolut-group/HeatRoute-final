package ru.heatroute;

import java.util.*;
import org.locationtech.jts.geom.*;

/** Transactional geometry/topology moves; every returned tree is fully reevaluated. */
final class TopologyMoves {
  final Dataset data;
  final Rules rules;
  final Router router;
  final Evaluation evaluation;

  TopologyMoves(Dataset d, Rules r, Router router) {
    data = d;
    rules = r;
    this.router = router;
    evaluation = new Evaluation(d, r, router);
  }

  Network.Tree drop(Network.Tree original, String demand) {
    Network.Tree t = original.copy();
    for (Network.Node n : t.nodes()) n.demands.removeIf(d -> d.id.equals(demand));
    boolean changed;
    do {
      changed = false;
      for (Network.Edge e : new ArrayList<>(t.edges))
        if (e.to.demands.isEmpty() && t.outgoing(e.to).isEmpty()) {
          t.edges.remove(e);
          changed = true;
        }
    } while (changed);
    if (t.edges.isEmpty() && t.start.demands.isEmpty()) return null;
    do {
      changed = false;
      for (Network.Node n : t.nodes())
        if (n != t.start && n.demands.isEmpty()) {
          List<Network.Edge> out = t.outgoing(n);
          List<Network.Edge> in = new ArrayList<>();
          for (Network.Edge e : t.edges) if (e.to == n) in.add(e);
          if (in.size() == 1 && out.size() == 1) {
            Network.Edge a = in.get(0), b = out.get(0);
            List<Coordinate> points = new ArrayList<>(Arrays.asList(a.line.getCoordinates()));
            points.addAll(Arrays.asList(b.line.getCoordinates()).subList(1, b.line.getNumPoints()));
            t.edges.remove(a);
            t.edges.remove(b);
            t.edges.add(new Network.Edge(a.from, b.to, Geo.simplify(Geo.line(points))));
            changed = true;
            break;
          }
        }
    } while (changed);
    return evaluation.evaluate(t, true);
  }

  Network.Tree changeRoot(Network.Tree original, Existing.Root root) {
    if (!original.start.demands.isEmpty()) return null;
    Network.Tree copy = original.copy(), result = new Network.Tree(root);
    for (Network.Edge e : copy.edges) {
      if (e.from == copy.start) {
        LineString path =
            router.route(root.p, e.to.p, e.dn, e.to.demands.isEmpty() ? null : e.to.p, root);
        if (path == null) return null;
        result.edges.add(new Network.Edge(result.start, e.to, path));
      } else result.edges.add(e);
    }
    return evaluation.evaluate(result, true);
  }

  Network.Tree reroute(Network.Tree original) {
    Network.Tree t = original.copy();
    for (Network.Edge e : t.edges) {
      LineString path =
          router.route(
              e.from.p,
              e.to.p,
              e.dn,
              e.to.demands.isEmpty() ? null : e.to.p,
              e.from == t.start ? t.root : null);
      if (path == null) return null;
      e.line = path;
    }
    return evaluation.evaluate(t, true);
  }

  Network.Tree moveBranch(Network.Tree original, String nodeKey, double dx, double dy) {
    Network.Tree t = original.copy();
    Network.Node node =
        t.nodes().stream()
            .filter(n -> n.key.equals(nodeKey) && n != t.start && n.terminalId == null)
            .findFirst()
            .orElse(null);
    if (node == null) return null;
    node.p.setCoordinate(Geo.canonical(new Coordinate(node.p.x + dx, node.p.y + dy)));
    if (!data.hosts(node.p).isEmpty()) return null;
    for (Network.Edge e : t.edges)
      if (e.from == node || e.to == node) {
        LineString path =
            router.route(
                e.from.p,
                e.to.p,
                e.dn,
                e.to.demands.isEmpty() ? null : e.to.p,
                e.from == t.start ? t.root : null);
        if (path == null) return null;
        e.line = path;
      }
    return evaluation.evaluate(t, true);
  }
}
