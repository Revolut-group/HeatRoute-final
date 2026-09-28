package ru.heatroute;

import java.math.*;
import java.util.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.linearref.LengthIndexedLine;

/** Incremental shared forest: always add a feasible consumer, then compare full costs. */
final class CurrentOptimizer {
  private final Dataset data;
  private final Rules rules;
  private final Existing existing;
  private final Router router;
  private final Evaluation evaluator;
  final Map<String, Object> diagnostics = new LinkedHashMap<>();
  private long attempts;
  private final int corridorDn;

  CurrentOptimizer(Dataset d, Rules r, Existing ex, Router router) {
    data = d;
    rules = r;
    existing = ex;
    this.router = router;
    evaluator = new Evaluation(d, r, router);
    corridorDn = Catalog.base(d.totalFlow().min(Catalog.pipe(1400).capacity)).dn;
    if (rules.flexibleEntrances()) router.reserveExterior(corridorDn);
  }

  /**
   * Connect the missing demands to an existing (e.g. planner-built) forest with the exact vector
   * router.
   */
  List<Network.Tree> extend(List<Network.Tree> forest, List<Dataset.Demand> missing) {
    List<Dataset.Demand> left = new ArrayList<>(missing);
    for (int pass = 0; pass < 2 && !left.isEmpty() && !router.budgetExpired(); pass++) {
      for (Dataset.Demand d : new ArrayList<>(left)) {
        List<Network.Tree> next;
        try {
          next = connect(forest, d, pass == 0 ? 8 : 32);
        } catch (Failure f) {
          if (f.code.equals("CANCELLED")) throw f;
          next = null;
        }
        if (next != null) {
          forest = next;
          left.remove(d);
        }
      }
    }
    return forest;
  }

  List<Network.Variant> solve(int count) {
    List<Network.Tree> forest = new ArrayList<>();
    List<Dataset.Demand> missing = new ArrayList<>(data.demands);
    Map<String, String> reasons = new TreeMap<>();
    for (int pass = 0;
        pass < Math.max(2, data.demands.size()) && !missing.isEmpty() && !router.budgetExpired();
        pass++) {
      boolean progress = false;
      List<Dataset.Demand> remaining = new ArrayList<>(missing);
      while (!remaining.isEmpty() && !router.budgetExpired()) {
        final List<Network.Tree> current = forest;
        remaining.sort(
            Comparator.comparingDouble((Dataset.Demand d) -> distance(current, d))
                .thenComparing(d -> d.id));
        Dataset.Demand demand = remaining.remove(0);
        List<Network.Tree> best = connect(forest, demand, pass == 0 ? 4 : 32);
        if (best != null) {
          forest = best;
          Set<String> connected = new HashSet<>();
          for (Network.Tree tree : forest) connected.addAll(tree.demands);
          missing.removeIf(d -> connected.contains(d.id));
          remaining.removeIf(d -> connected.contains(d.id));
          for (String id : connected) reasons.remove(id);
          progress = true;
        } else
          reasons.put(
              demand.id,
              "NO_COMPATIBLE_ROUTE_FOUND within explored visibility graph; not proven impossible");
        System.err.println(
            "shared pass="
                + pass
                + " demand="
                + demand.id
                + " connected="
                + (data.demands.size() - missing.size())
                + " roots="
                + forest.size()
                + " trials="
                + attempts);
      }
      if (!progress) break;
    }
    for (Dataset.Demand d : missing)
      reasons.putIfAbsent(d.id, "SEARCH_BUDGET_EXHAUSTED before a compatible route was found");
    Network.Variant best = new CurrentExporter(data, rules).export(forest, 1);
    diagnostics.put("algorithm", "incremental_shared_forest");
    diagnostics.put("connection_policy", "feasible_connection_before_cost");
    diagnostics.put("B3", best.summary);
    diagnostics.put("unconnected_reasons", reasons);
    diagnostics.put("candidate_evaluations", attempts);
    diagnostics.put("routing_expansions", router.expansions());
    diagnostics.put("time_budget_exhausted", router.budgetExpired());
    diagnostics.put("continuous_optimum_proven", false);
    diagnostics.put(
        "search_status", router.budgetExpired() ? "SEARCH_BUDGET_EXHAUSTED" : "HEURISTIC_COMPLETE");
    return List.of(best);
  }

  private double distance(List<Network.Tree> forest, Dataset.Demand d) {
    Coordinate p = d.terminals.get(0).geometry.getCoordinate();
    double best = Double.POSITIVE_INFINITY;
    for (Dataset.Feature f : data.networks)
      best = Math.min(best, f.geometry.distance(Geo.point(p)));
    for (Network.Tree tree : forest)
      for (Network.Edge edge : tree.edges) best = Math.min(best, edge.line.distance(Geo.point(p)));
    return best;
  }

  private List<Network.Tree> connect(
      List<Network.Tree> forest, Dataset.Demand demand, int rootLimit) {
    List<Network.Tree> best = null;
    BigDecimal score = null;
    Coordinate destination = demand.terminals.get(0).geometry.getCoordinate();
    // Connections to nearby built branches are tried before another independent root.
    List<Network.Tree> near = new ArrayList<>(forest);
    near.sort(
        Comparator.comparingDouble(
            t ->
                t.edges.stream()
                    .mapToDouble(e -> e.line.distance(Geo.point(destination)))
                    .min()
                    .orElse(Double.POSITIVE_INFINITY)));
    for (Network.Tree seed : near.subList(0, Math.min(3, near.size()))) {
      List<Network.Tree> rest = new ArrayList<>(forest);
      rest.remove(seed);
      for (Network.Tree candidate : insert(seed, demand, forest)) {
        if (!evaluator.compatible(rest, candidate)) continue;
        List<Network.Tree> trial = new ArrayList<>(rest);
        trial.add(candidate);
        BigDecimal value = new CurrentExporter(data, rules).export(trial, 1).score();
        if (score == null || value.compareTo(score) < 0) {
          score = value;
          best = trial;
        }
      }
    }
    int tried = 0;
    List<Existing.Root> roots = existing.roots(destination);
    for (Existing.Root root : roots) {
      if (tried++ >= rootLimit || router.budgetExpired()) break;
      try {
        int dn = Catalog.base(demand.flow).dn;
        if (root.p.distance(destination) < 1e-6) {
          Network.Tree zero = new Network.Tree(root);
          zero.start.demands.addAll(coincident(destination));
          zero = evaluator.evaluate(zero, false);
          if (evaluator.compatible(forest, zero)) {
            List<Network.Tree> trial = new ArrayList<>(forest);
            trial.add(zero);
            BigDecimal value = new CurrentExporter(data, rules).export(trial, 1).score();
            if (score == null || value.compareTo(score) < 0) {
              score = value;
              best = trial;
            }
          }
          continue;
        }
        LineString line = route(root.p, destination, dn, root, forest, null);
        if (line == null) continue;
        Network.Tree candidate = new Network.Tree(root);
        Network.Node leaf =
            new Network.Node(
                "d:" + demand.id, destination, coincident(destination), demand.terminals.get(0).id);
        candidate.edges.add(new Network.Edge(candidate.start, leaf, line));
        attempts++;
        candidate = evaluator.evaluate(candidate, true);
        if (!evaluator.compatible(forest, candidate)) continue;
        List<Network.Tree> trial = new ArrayList<>(forest);
        trial.add(candidate);
        BigDecimal value = new CurrentExporter(data, rules).export(trial, 1).score();
        if (score == null || value.compareTo(score) < 0) {
          score = value;
          best = trial;
        }
      } catch (Failure failure) {
        if (failure.code.equals("CANCELLED")) throw failure;
      }
    }
    return best;
  }

  private List<Network.Tree> insert(
      Network.Tree seed, Dataset.Demand demand, List<Network.Tree> forest) {
    List<Network.Tree> found = new ArrayList<>();
    Coordinate dest = demand.terminals.get(0).geometry.getCoordinate();
    int dn = Catalog.base(demand.flow).dn;
    List<Network.Edge> edges = new ArrayList<>(seed.edges);
    edges.sort(Comparator.comparingDouble(e -> e.line.distance(Geo.point(dest))));
    Set<String> nodeTried = new HashSet<>();
    for (Network.Edge original : edges.subList(0, Math.min(3, edges.size()))) {
      if (router.budgetExpired()) break;
      for (Network.Node node : List.of(original.from, original.to)) {
        if (node.terminalId != null || !node.demands.isEmpty() || !nodeTried.add(node.key))
          continue;
        int degree =
            (int) seed.edges.stream().filter(e -> e.from == node || e.to == node).count()
                + (node == seed.start ? seed.root.degree : 0);
        if (degree >= 4) continue;
        Coordinate approach = null;
        for (Network.Edge parent : seed.edges)
          if (parent.to == node)
            approach = parent.line.getCoordinateN(parent.line.getNumPoints() - 2);
        LineString line =
            route(node.p, dest, dn, node == seed.start ? seed.root : null, forest, approach);
        if (line == null) continue;
        Network.Tree t = seed.copy();
        Network.Node copy =
            t.nodes().stream().filter(n -> n.key.equals(node.key)).findFirst().orElseThrow();
        t.edges.add(
            new Network.Edge(
                copy,
                new Network.Node(
                    "d:" + demand.id, dest, coincident(dest), demand.terminals.get(0).id),
                line));
        evaluate(t, found);
      }
      LengthIndexedLine indexed = new LengthIndexedLine(original.line);
      TreeSet<Double> stations = new TreeSet<>();
      double projected = indexed.project(dest);
      stations.add(projected);
      for (double offset : new double[] {2, 5, 12})
        if (projected > offset) stations.add(projected - offset);
      double at = 0;
      Coordinate[] c = original.line.getCoordinates();
      List<Double> vertices = new ArrayList<>();
      for (int i = 1; i < c.length - 1; i++) {
        at += c[i - 1].distance(c[i]);
        vertices.add(at);
      }
      vertices.sort(Comparator.comparingDouble(s -> indexed.extractPoint(s).distance(dest)));
      stations.addAll(vertices.subList(0, Math.min(2, vertices.size())));
      for (double station : stations) {
        if (station < 2 || original.line.getLength() - station < 2 || router.budgetExpired())
          continue;
        Coordinate branch = Geo.canonical(indexed.extractPoint(station));
        if (!data.hosts(branch).isEmpty()) continue;
        LineString addition =
            route(branch, dest, dn, null, forest, indexed.extractPoint(Math.max(0, station - .5)));
        if (addition == null) continue;
        Network.Tree t = seed.copy();
        Network.Edge old = t.edges.get(seed.edges.indexOf(original));
        t.edges.remove(old);
        Network.Node node = new Network.Node("branch:" + Geo.key(branch), branch);
        LineString left = Geo.sub(old.line, 0, station),
            right = Geo.sub(old.line, station, old.line.getLength());
        Coordinate[] lc = left.getCoordinates(), rc = right.getCoordinates();
        lc[lc.length - 1] = branch;
        rc[0] = branch;
        t.edges.add(new Network.Edge(old.from, node, Geo.line(Arrays.asList(lc))));
        t.edges.add(new Network.Edge(node, old.to, Geo.line(Arrays.asList(rc))));
        t.edges.add(
            new Network.Edge(
                node,
                new Network.Node(
                    "d:" + demand.id, dest, coincident(dest), demand.terminals.get(0).id),
                addition));
        evaluate(t, found);
      }
    }
    return found;
  }

  private List<Dataset.Demand> coincident(Coordinate point) {
    List<Dataset.Demand> group = new ArrayList<>();
    for (Dataset.Demand d : data.demands)
      if (Geo.canonical(d.terminals.get(0).geometry.getCoordinate()).distance(Geo.canonical(point))
          < 1e-6) group.add(d);
    return group;
  }

  private LineString route(
      Coordinate start,
      Coordinate end,
      int minimumDn,
      Existing.Root root,
      List<Network.Tree> forest,
      Coordinate approach) {
    if (!rules.flexibleEntrances()) {
      LineString line =
          router.routeAvoiding(
              start, end, Math.max(minimumDn, corridorDn), end, root, forest, approach);
      return line != null || minimumDn >= corridorDn
          ? line
          : router.routeAvoiding(start, end, minimumDn, end, root, forest, approach);
    }
    // An artificial trunk reserve must not move a consumer's entrance to a farther wall.
    // Evaluation reroutes affected edges if the actual hydraulic DN subsequently grows.
    return router.routeAvoiding(start, end, minimumDn, end, root, forest, approach);
  }

  private void evaluate(Network.Tree t, List<Network.Tree> found) {
    attempts++;
    try {
      found.add(evaluator.evaluate(t, true));
    } catch (Failure failure) {
      if (failure.code.equals("CANCELLED")) throw failure;
    }
  }
}
