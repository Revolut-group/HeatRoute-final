package ru.heatroute;

import java.math.*;
import java.util.*;
import org.locationtech.jts.geom.*;

public final class Evaluation {
  private final Dataset data;
  private final Rules rules;
  private final Router router;
  private final GeometryRules geometry;

  public Evaluation(Dataset d, Rules r, Router router) {
    data = d;
    rules = r;
    this.router = router;
    geometry = new GeometryRules(d, r);
  }

  /**
   * Flows and exact DN of a copy of the tree (current profile) without any geometry check; edge
   * order is kept, so callers can map the copy back to their own edges (the planner uses it to find
   * the real culprit of a failure).
   */
  public Network.Tree sized(Network.Tree input) {
    Network.Tree t = input.copy();
    t.demands.clear();
    accumulate(t, t.start, new HashSet<>(), new HashSet<>());
    for (Network.Edge e : t.edges) {
      e.base = Catalog.base(e.flow).dn;
      e.dn = e.base;
      e.line = Geo.canonical(Geo.simplify(e.line));
    }
    PathDiameters.assign(t);
    return t;
  }

  public Network.Tree evaluate(Network.Tree input, boolean reroute) {
    Network.Tree t = input.copy();
    t.demands.clear();
    Set<Network.Node> visiting = new HashSet<>();
    accumulate(t, t.start, visiting, new HashSet<>());
    for (Network.Edge e : t.edges) {
      e.base = Catalog.base(e.flow).dn;
      e.dn = e.base;
      e.line = Geo.canonical(Geo.simplify(e.line));
    }
    if (rules.current()) PathDiameters.assign(t);
    else
      for (int pass = 0; pass <= t.edges.size(); pass++) {
        boolean changed = false;
        Set<Network.Edge> visited = new HashSet<>();
        for (Network.Edge first : t.edges)
          if (visited.add(first)) {
            List<Network.Edge> cc = new ArrayList<>();
            Deque<Network.Edge> q = new ArrayDeque<>();
            q.add(first);
            while (!q.isEmpty()) {
              Network.Edge e = q.remove();
              cc.add(e);
              for (Network.Edge n : t.edges)
                if (n.dn == e.dn
                    && !visited.contains(n)
                    && (n.from == e.from || n.from == e.to || n.to == e.from || n.to == e.to)) {
                  visited.add(n);
                  q.add(n);
                }
            }
            double length = cc.stream().mapToDouble(e -> e.line.getLength()).sum();
            if (length > Catalog.pipe(first.dn).limit + 1e-6) {
              for (Network.Edge e : cc) {
                if (Catalog.pipe(e.dn).index >= Catalog.pipe(e.base).index + 1)
                  throw new Failure("LENGTH_LIMIT", "Second DN uplift required");
                e.dn = Catalog.next(Catalog.pipe(e.dn)).dn;
                changed = true;
              }
            }
          }
        if (!changed) break;
        if (pass == t.edges.size()) throw new Failure("LENGTH_LIMIT", "DN stabilization failed");
      }
    for (Network.Edge e : t.edges) {
      Coordinate terminal = e.to.demands.isEmpty() ? null : e.to.p;
      Existing.Root root = e.from == t.start ? t.root : null;
      try {
        e.zones = geometry.events(e.line, e.dn, terminal, root);
      } catch (Failure failure) {
        if (!reroute) throw failure;
        LineString line = router.route(e.from.p, e.to.p, e.dn, terminal, root);
        if (line == null) throw failure;
        e.line = Geo.canonical(line);
        e.zones = geometry.events(e.line, e.dn, terminal, root);
      }
    }
    // A reroute can increase a same-DN component; re-evaluate the fixed geometry.
    if (reroute) return evaluate(t, false);
    for (Network.Node n : t.nodes()) {
      if (rules.current())
        for (Network.Edge parent : t.edges)
          if (parent.to == n)
            for (Network.Edge child : t.outgoing(n))
              if (Geo.turn(
                      parent.line.getCoordinateN(parent.line.getNumPoints() - 2),
                      n.p,
                      child.line.getCoordinateN(1))
                  > 90.000001)
                throw new Failure("TURN_ANGLE", "Turn at chamber exceeds 90 degrees");
      int degree = (int) t.edges.stream().filter(e -> e.from == n || e.to == n).count();
      if (n == t.start && degree + t.root.degree > 4 || n != t.start && degree > 4)
        throw new Failure("NODE_DEGREE", "Physical node exceeds four rays");
      if (!n.demands.isEmpty() && !t.outgoing(n).isEmpty())
        throw new Failure("HOST_BRANCH", "A demand endpoint cannot serve as a transit junction");
    }
    for (int i = 0; i < t.edges.size(); i++)
      for (int j = i + 1; j < t.edges.size(); j++) {
        Network.Edge a = t.edges.get(i), b = t.edges.get(j);
        Geometry hit = a.line.intersection(b.line);
        boolean shared = a.from == b.from || a.from == b.to || a.to == b.from || a.to == b.to;
        if (shared) {
          Network.Node node = a.from == b.from || a.from == b.to ? a.from : a.to;
          if (!JunctionGeometry.fits(
              a.line, Catalog.pipe(a.dn).width, b.line, Catalog.pipe(b.dn).width, node.p))
            throw new Failure(
                "NEW_GABARIT_OVERLAP", "Tube pairs meet again outside their junction");
        }
        if (!shared
            && a.line.distance(b.line) < (Catalog.pipe(a.dn).width + Catalog.pipe(b.dn).width) / 2)
          throw new Failure("NEW_GABARIT_OVERLAP", "Unrelated pipe pairs overlap");
        if (!hit.isEmpty() && (!shared || hit.getDimension() > 0 || hit.getNumPoints() > 1))
          throw new Failure("NEW_INTERSECTION", "Unnoded intersection or overlapping new lines");
      }
    return t;
  }

  private BigDecimal accumulate(
      Network.Tree t, Network.Node n, Set<Network.Node> visiting, Set<String> demandIds) {
    if (!visiting.add(n)) throw new Failure("NEW_CYCLE", "Cycle or shared downstream node");
    BigDecimal total = BigDecimal.ZERO;
    for (Dataset.Demand d : n.demands) {
      if (!demandIds.add(d.id))
        throw new Failure("DUPLICATE_DEMAND", "Duplicate demand attachment", d.id);
      total = total.add(d.flow);
      t.demands.add(d.id);
    }
    for (Network.Edge e : t.outgoing(n)) {
      e.flow = accumulate(t, e.to, visiting, demandIds);
      total = total.add(e.flow);
    }
    return total;
  }

  public boolean compatible(List<Network.Tree> current, Network.Tree next) {
    Set<String> used = new HashSet<>();
    int rootDegree = next.root.degree + next.outgoing(next.start).size();
    for (Network.Tree t : current) {
      used.addAll(t.demands);
      if (t.root.key.equals(next.root.key)) rootDegree += t.outgoing(t.start).size();
      for (Network.Edge a : t.edges)
        for (Network.Edge b : next.edges) {
          Geometry hit = a.line.intersection(b.line);
          boolean sameRoot =
              t.root.key.equals(next.root.key) && a.from == t.start && b.from == next.start;
          if (!hit.isEmpty()
              && (!sameRoot
                  || hit.getDimension() > 0
                  || hit.getNumPoints() > 1
                  || hit.distance(Geo.point(t.root.p)) > 1e-6)) return false;
          if (sameRoot
              && !JunctionGeometry.fits(
                  a.line, Catalog.pipe(a.dn).width, b.line, Catalog.pipe(b.dn).width, t.root.p))
            return false;
          // Cross-sectional overlap outside a shared root is not an independent parallel corridor.
          if (!sameRoot
              && a.line.distance(b.line)
                  < (Catalog.pipe(a.dn).width + Catalog.pipe(b.dn).width) / 2) return false;
        }
    }
    return rootDegree <= 4 && Collections.disjoint(used, next.demands);
  }
}
