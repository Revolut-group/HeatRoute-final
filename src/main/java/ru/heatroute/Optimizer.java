package ru.heatroute;

import java.math.*;
import java.util.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.linearref.LengthIndexedLine;

/** Finite component pool + conflict-aware covering search. No continuous optimality claim. */
public final class Optimizer {
  public java.util.function.Consumer<Network.Variant> checkpoint = v -> {};
  private final Dataset data;
  private final Rules rules;
  private final Existing existing;
  private final Router router;
  private final Evaluation evaluation;
  private final Exporter exporter;
  private final List<Network.Variant> baselines = new ArrayList<>();
  private final List<Network.Tree> pool = new ArrayList<>();
  private final List<Network.Variant> finalists = new ArrayList<>();
  private final Map<String, List<Network.Tree>> byDemand = new TreeMap<>();
  private final Map<String, Double> privateScore = new HashMap<>();
  private final Map<String, Object> diagnostics = new LinkedHashMap<>();
  private final Map<Network.Tree, Double> privateCache = new IdentityHashMap<>();
  private long evaluations;
  private double best = Double.POSITIVE_INFINITY;

  public Optimizer(Dataset d, Rules r, Existing ex) {
    data = d;
    rules = r;
    existing = ex;
    router = new Router(d, r);
    evaluation = new Evaluation(d, r, router);
    exporter = new Exporter(d, ex, r);
  }

  public List<Network.Variant> solve(int count) {
    if (rules.current() && rules.text("execution.planner").equals("grid_steiner")) {
      Network.Variant planned = null;
      try {
        GridPlanner planner = new GridPlanner(data, rules, existing, router);
        List<List<Network.Tree>> variants;
        try {
          variants = planner.solveVariants(count);
        } finally {
          diagnostics.putAll(planner.diagnostics);
        }
        List<List<Network.Tree>> ok = new ArrayList<>();
        List<BigDecimal> scores = new ArrayList<>();
        Set<String> shapes = new HashSet<>();
        for (int i = 0; i < variants.size(); i++) {
          List<Network.Tree> trees = variants.get(i);
          if (trees == null) continue;
          try {
            List<Network.Tree> evaluated = new ArrayList<>();
            for (Network.Tree t : trees) {
              Network.Tree e = evaluation.evaluate(t, false);
              if (!evaluation.compatible(evaluated, e))
                throw new Failure("NEW_INTERSECTION", "Planner trees conflict", t.root.key);
              evaluated.add(e);
            }
            Network.Variant probe = new CurrentExporter(data, rules).export(evaluated, 1);
            if (!shapes.add(probe.signature)) continue;

            ok.add(evaluated);
            scores.add(probe.score());
          } catch (Failure f) {
            if (i == 0) throw f;
            diagnostics.put(
                "planner_variant_" + (i + 1) + "_rejected", f.code + ": " + f.getMessage());
          }
        }
        // rescue: points the raster planner could not connect are tried with the exact vector
        // router on top of its forest
        for (int i = 0; i < ok.size(); i++) {
          Set<String> have = new HashSet<>();
          for (Network.Tree t : ok.get(i)) have.addAll(t.demands);
          List<Dataset.Demand> missing = new ArrayList<>();
          for (Dataset.Demand d : data.demands) if (!have.contains(d.id)) missing.add(d);
          if (missing.isEmpty()) continue;
          try {
            List<Network.Tree> ext =
                new CurrentOptimizer(data, rules, existing, new Router(data, rules))
                    .extend(ok.get(i), missing);
            Network.Variant probe = new CurrentExporter(data, rules).export(ext, 1);
            if (unconnected(probe) < missing.size()) {
              ok.set(i, ext);
              scores.set(i, probe.score());
              diagnostics.merge(
                  "rescued_points",
                  missing.size() - unconnected(probe),
                  (x, y) -> (Integer) x + (Integer) y);
            }
          } catch (Failure f) {
            if (f.code.equals("CANCELLED")) throw f;
            diagnostics.put("rescue_failure", f.code + ": " + f.getMessage());
          }
        }
        List<Integer> missingCount = new ArrayList<>();
        for (List<Network.Tree> v : ok) {
          Set<String> have = new HashSet<>();
          for (Network.Tree t : v) have.addAll(t.demands);
          missingCount.add(data.demands.size() - have.size());
        }
        // more connected points first (clarification 15), then score
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < ok.size(); i++) order.add(i);
        order.sort(
            Comparator.comparing((Integer i) -> missingCount.get(i)).thenComparing(scores::get));
        if (!order.isEmpty()) {
          int bestMissing = missingCount.get(order.get(0));
          order.removeIf(i -> missingCount.get(i) > bestMissing);
        }
        List<Network.Variant> ranked = new ArrayList<>();
        for (int i = 0; i < order.size(); i++)
          ranked.add(new CurrentExporter(data, rules).export(ok.get(order.get(i)), i + 1));
        plannedAll = ranked;
        planned = ranked.isEmpty() ? null : ranked.get(0);
      } catch (Failure failure) {
        if (failure.code.equals("CANCELLED")) throw failure;
        diagnostics.put("planner_failure", failure.code + ": " + failure.getMessage());
        System.err.println("planner failure " + failure.code + ": " + failure.getMessage());
        if (Boolean.getBoolean("heatroute.plannerOnly")) {
          System.err.println(diagnostics);
          throw failure;
        }
      } catch (OutOfMemoryError | RuntimeException error) {
        diagnostics.put(
            "planner_failure", error.getClass().getSimpleName() + ": " + error.getMessage());
        System.err.println("planner failure " + error);
        if (Boolean.getBoolean("heatroute.plannerOnly")) throw error;
      }
      if (planned != null) {
        diagnostics.put("algorithm", "grid_steiner_forest");
        diagnostics.put("B3", planned.summary);
        baselines.add(planned);
      }
      // Clarification 15: never trade a reachable connection for score; the legacy engine runs
      // whenever the planner is incomplete.
      if (planned != null && unconnected(planned) == 0) {
        if (plannedAll.size() > 1) baselines.addAll(plannedAll.subList(1, plannedAll.size()));
        return plannedAll;
      }
      // Both engines' variants in one pool: the most connected points first, then the score; up to
      // count distinct ones.
      List<Network.Variant> legacy = legacy(count);
      List<Network.Variant> pool = new ArrayList<>(plannedAll);
      pool.addAll(legacy);
      if (pool.isEmpty()) return legacy;
      Set<String> fromPlanner = new HashSet<>();
      for (Network.Variant v : plannedAll) fromPlanner.add(v.signature);
      pool.sort((a, b) -> better(a, b) ? -1 : better(b, a) ? 1 : 0);
      int fewest = unconnected(pool.get(0));
      List<Network.Variant> out = new ArrayList<>();
      Set<String> seen = new HashSet<>();
      for (Network.Variant v : pool)
        if (out.size() < count && unconnected(v) == fewest && seen.add(v.signature))
          out.add(new CurrentExporter(data, rules).export(v.trees, out.size() + 1));
      diagnostics.put(
          "engine_selected",
          fromPlanner.contains(pool.get(0).signature)
              ? "grid_steiner_forest"
              : "incremental_shared_forest");
      return out;
    }
    if (rules.current()) {
      CurrentOptimizer current = new CurrentOptimizer(data, rules, existing, router);
      List<Network.Variant> result = current.solve(count);
      diagnostics.putAll(current.diagnostics);
      baselines.addAll(result);
      return result;
    }
    keep(List.of());
    diagnostics.put("B0", finalists.get(0).summary);
    baselines.add(finalists.get(0));
    Map<String, String> reasons = new TreeMap<>();
    for (Dataset.Demand demand : data.demands) {
      if (router.budgetExpired()) break;
      int found = 0;
      for (Dataset.Feature terminal : demand.terminals) {
        List<Existing.Root> roots = existing.roots(terminal.geometry.getCoordinate());
        List<Existing.Root> selected = new ArrayList<>();
        int chambers = 0, lines = 0;
        for (Existing.Root root : roots) {
          if (root.target.type.equals("heat_chamber") && chambers++ < 3
              || root.target.type.equals("heat_network") && lines++ < 5) selected.add(root);
        }
        for (Existing.Root root : roots) if (!selected.contains(root)) selected.add(root);
        int attempted = 0;
        for (Existing.Root root : selected) {
          if (attempted++ >= 8 && found > 0) break;
          if (attempted > 32) break;
          Catalog.Pipe base = Catalog.base(demand.flow);
          if (root.p.distance(terminal.geometry.getCoordinate())
              > (base.index + 1 < Catalog.PIPES.size() ? Catalog.next(base) : base).limit) continue;
          if (Geo.key(root.p).equals(Geo.key(terminal.geometry.getCoordinate()))) {
            Network.Tree zero = new Network.Tree(root);
            zero.start.demands.add(demand);
            try {
              add(evaluation.evaluate(zero, false));
              addCoincident(zero, terminal.geometry.getCoordinate());
              found++;
              Network.Variant v = exporter.export(List.of(evaluation.evaluate(zero, false)), 1);
              if (((BigDecimal) v.summary.get("calculated_cost"))
                      .subtract((BigDecimal) v.summary.get("unconnected_penalty"))
                      .compareTo(rules.decimal("cost.tie_in_rub"))
                  == 0) break;
            } catch (Failure ignored) {
            }
            continue;
          }
          LineString line =
              router.route(
                  root.p,
                  terminal.geometry.getCoordinate(),
                  base.dn,
                  terminal.geometry.getCoordinate(),
                  root);
          if (line == null) continue;
          Network.Tree t = new Network.Tree(root);
          Network.Node leaf =
              new Network.Node(
                  "d:" + demand.id,
                  terminal.geometry.getCoordinate(),
                  List.of(demand),
                  terminal.id);
          t.edges.add(new Network.Edge(t.start, leaf, line));
          try {
            Network.Tree checked = evaluation.evaluate(t, true);
            add(checked);
            addCoincident(checked, terminal.geometry.getCoordinate());
            found++;
          } catch (Failure ignored) {
          }
        }
      }
      if (found == 0)
        reasons.put(
            demand.id, "NO_ROUTE_FOUND within explored roots/corridors; not proven infeasible");
      System.err.println("route demand=" + demand.id + " candidates=" + found);
    }
    List<Network.Tree> single = new ArrayList<>(pool);
    Network.Variant b1 = greedy(single);
    diagnostics.put("B1", b1.summary);
    baselines.add(b1);
    keep(b1.trees);
    // Insert missing consumers into real new edges; new branch positions are not restricted to
    // existing vertices.
    List<Network.Tree> frontier = bestComponents(single, 28);
    for (int round = 0; round < 3; round++) {
      if (router.budgetExpired()) break;
      System.err.println(
          "group round=" + round + " frontier=" + frontier.size() + " pool=" + pool.size());
      List<Network.Tree> generated = new ArrayList<>();
      for (Network.Tree seed : frontier) {
        List<Dataset.Demand> candidates = new ArrayList<>();
        for (Dataset.Demand d : data.demands) if (!seed.demands.contains(d.id)) candidates.add(d);
        candidates.sort(
            Comparator.comparingDouble(
                    (Dataset.Demand d) -> distance(seed, d.terminals.get(0).geometry))
                .thenComparing(d -> d.id));
        for (Dataset.Demand d : candidates.subList(0, Math.min(3, candidates.size()))) {
          Network.Tree t = insert(seed, d);
          if (t != null) {
            add(t);
            generated.add(t);
          }
        }
      }
      if (generated.isEmpty()) break;
      frontier = bestComponents(generated, 18);
    }
    Network.Variant b2 = greedy(pool);
    diagnostics.put("B2", b2.summary);
    baselines.add(b2);
    keep(b2.trees);
    for (Network.Tree t : pool) {
      for (String d : t.demands) byDemand.computeIfAbsent(d, k -> new ArrayList<>()).add(t);
      privateScore.put(t.signature(), privateObjective(t));
    }
    for (List<Network.Tree> choices : byDemand.values())
      choices.sort(
          Comparator.comparingDouble((Network.Tree t) -> increment(t) / t.demands.size())
              .thenComparing(Network.Tree::signature));
    Set<String> free = new TreeSet<>();
    for (Dataset.Demand d : data.demands) free.add(d.id);
    Map<String, Double> penalties = new TreeMap<>();
    for (Dataset.Demand demand : data.demands)
      penalties.put(
          demand.id, rules.score(rules.penalty(demand.flow), BigDecimal.ZERO).doubleValue());
    if (data.demands.size() <= rules.integer("execution.small_problem_max_demands")) {
      FiniteCoverSearch<Network.Tree> search =
          new FiniteCoverSearch<>(
              pool,
              free,
              t -> t.demands,
              this::privateObjective,
              penalties,
              evaluation::compatible,
              selected -> {
                try {
                  return exporter.export(selected, 1).score().doubleValue();
                } catch (Failure failure) {
                  return Double.POSITIVE_INFINITY;
                }
              },
              this::keep,
              best,
              rules.integer("execution.maximum_candidate_evaluations"));
      search.stop = router::budgetExpired;
      search.run();
      evaluations = search.evaluations;
    } else improveLarge(b2);
    finalists.sort(Comparator.comparing(Network.Variant::score).thenComparing(v -> v.signature));
    List<Network.Variant> result = new ArrayList<>();
    for (Network.Variant v : finalists)
      if (result.size() < count) result.add(exporter.export(v.trees, result.size() + 1));
    diagnostics.put("B3", result.get(0).summary);
    diagnostics.put("time_budget_exhausted", router.budgetExpired());
    diagnostics.put("pool_components", pool.size());
    diagnostics.put("cover_evaluations", evaluations);
    diagnostics.put("routing_expansions", router.expansions());
    diagnostics.put(
        "search_status",
        evaluations >= rules.integer("execution.maximum_candidate_evaluations")
            ? "SEARCH_BUDGET_EXHAUSTED"
            : "FINITE_POOL_SEARCH_COMPLETE");
    diagnostics.put("continuous_optimum_proven", false);
    diagnostics.put("unconnected_reasons", reasons);
    return result;
  }

  private void addCoincident(Network.Tree source, Coordinate point) {
    Network.Tree group = source.copy();
    Network.Node leaf =
        group.nodes().stream()
            .filter(n -> !n.demands.isEmpty() && Geo.key(n.p).equals(Geo.key(point)))
            .findFirst()
            .orElse(null);
    if (leaf == null) return;
    Set<String> ids = new HashSet<>();
    for (Dataset.Demand d : leaf.demands) ids.add(d.id);
    for (Dataset.Demand d : data.demands)
      if (!ids.contains(d.id)
          && d.terminals.stream()
              .anyMatch(f -> Geo.key(f.geometry.getCoordinate()).equals(Geo.key(point))))
        leaf.demands.add(d);
    if (leaf.demands.size() > ids.size())
      try {
        add(evaluation.evaluate(group, true));
      } catch (Failure ignored) {
      }
  }

  private void add(Network.Tree t) {
    Reconstruction.calculate(data, existing, List.of(t));
    String signature = t.signature();
    if (pool.stream().noneMatch(a -> a.signature().equals(signature))) pool.add(t);
  }

  private double distance(Network.Tree t, Geometry g) {
    return t.edges.stream().mapToDouble(e -> e.line.distance(g)).min().orElse(0);
  }

  private List<Network.Tree> bestComponents(List<Network.Tree> list, int n) {
    List<Network.Tree> a = new ArrayList<>(list);
    a.sort(
        Comparator.comparingDouble((Network.Tree t) -> increment(t) / t.demands.size())
            .thenComparing(Network.Tree::signature));
    return new ArrayList<>(a.subList(0, Math.min(n, a.size())));
  }

  private double increment(Network.Tree t) {
    double penalty = 0;
    for (Dataset.Demand d : data.demands)
      if (t.demands.contains(d.id))
        penalty += rules.score(rules.penalty(d.flow), BigDecimal.ZERO).doubleValue();
    return privateObjective(t) - penalty;
  }

  private double privateObjective(Network.Tree t) {
    if (privateCache.containsKey(t)) return privateCache.get(t);
    Network.Variant v = exporter.export(List.of(t), 1);
    BigDecimal c = (BigDecimal) v.summary.get("construction_cost");
    c = c.add((BigDecimal) v.summary.get("tie_in_cost"));
    // Only branch chambers are private; shared anchor and reconstruction costs are deliberately
    // omitted.
    for (Network.Node n : t.nodes())
      if (n != t.start && n.terminalId == null) {
        int dn =
            t.edges.stream()
                .filter(e -> e.from == n || e.to == n)
                .mapToInt(e -> e.dn)
                .max()
                .orElse(50);
        c = c.add(Catalog.chamber(dn));
      }
    double score = rules.score(c, (BigDecimal) v.summary.get("new_network_length")).doubleValue();
    privateCache.put(t, score);
    return score;
  }

  private Network.Variant greedy(List<Network.Tree> choices) {
    List<Network.Tree> selected = new ArrayList<>();
    Network.Variant current = exporter.export(selected, 1);
    for (; ; ) {
      Network.Tree chosen = null;
      Network.Variant next = current;
      for (Network.Tree t : choices)
        if (evaluation.compatible(selected, t)) {
          List<Network.Tree> trial = new ArrayList<>(selected);
          trial.add(t);
          try {
            Network.Variant v = exporter.export(trial, 1);
            if (v.score().compareTo(next.score()) < 0) {
              next = v;
              chosen = t;
            }
          } catch (Failure ignored) {
          }
        }
      if (chosen == null) return current;
      selected.add(chosen);
      current = next;
    }
  }

  private Network.Tree insert(Network.Tree seed, Dataset.Demand demand) {
    Network.Tree bestTree = null;
    BigDecimal bestScore = null;
    Dataset.Feature terminal = demand.terminals.get(0);
    Coordinate dest = terminal.geometry.getCoordinate();
    List<Network.Edge> near = new ArrayList<>(seed.edges);
    near.sort(Comparator.comparingDouble(e -> e.line.distance(terminal.geometry)));
    for (Network.Edge original : near.subList(0, Math.min(2, near.size()))) {
      double length = original.line.getLength();
      LengthIndexedLine li = new LengthIndexedLine(original.line);
      TreeSet<Double> stations = new TreeSet<>();
      stations.add(li.project(dest));
      stations.add(length * .4);
      stations.add(length * .7);
      for (double station : stations) {
        if (station < 8 || station > length - 8) continue;
        Coordinate branch = Geo.canonical(li.extractPoint(station));
        if (!data.hosts(branch).isEmpty()) continue;
        LineString addition = router.route(branch, dest, Catalog.base(demand.flow).dn, dest, null);
        if (addition == null) continue;
        Network.Tree t = seed.copy();
        Network.Edge old = t.edges.get(seed.edges.indexOf(original));
        t.edges.remove(old);
        Network.Node node = new Network.Node("branch:" + Geo.key(branch), branch);
        Network.Node leaf = new Network.Node("d:" + demand.id, dest, List.of(demand), terminal.id);
        LineString left = Geo.sub(old.line, 0, station), right = Geo.sub(old.line, station, length);
        Coordinate[] lc = left.getCoordinates();
        lc[lc.length - 1] = branch;
        left = Geo.GF.createLineString(lc);
        Coordinate[] rc = right.getCoordinates();
        rc[0] = branch;
        right = Geo.GF.createLineString(rc);
        t.edges.add(new Network.Edge(old.from, node, left));
        t.edges.add(new Network.Edge(node, old.to, right));
        t.edges.add(new Network.Edge(node, leaf, addition));
        try {
          t = evaluation.evaluate(t, true);
          Network.Variant v = exporter.export(List.of(t), 1);
          if (bestScore == null || v.score().compareTo(bestScore) < 0) {
            bestScore = v.score();
            bestTree = t;
          }
        } catch (Failure ignored) {
        }
      }
    }
    return bestTree;
  }

  private void keep(List<Network.Tree> trees) {
    try {
      Network.Variant v = exporter.export(trees, 1);
      if (finalists.stream().noneMatch(a -> a.signature.equals(v.signature))) finalists.add(v);
      finalists.sort(Comparator.comparing(Network.Variant::score).thenComparing(a -> a.signature));
      while (finalists.size() > 8) finalists.remove(finalists.size() - 1);
      double updated = finalists.get(0).score().doubleValue();
      if (updated < best) {
        best = updated;
        checkpoint.accept(finalists.get(0));
      }
    } catch (Failure ignored) {
    }
  }

  private void improveLarge(Network.Variant initial) {
    TopologyMoves moves = new TopologyMoves(data, rules, router);
    List<Network.Tree> current = new ArrayList<>(initial.trees);
    Map<String, Integer> attempts = new TreeMap<>();
    for (String name :
        List.of(
            "MERGE_GROUPS",
            "SPLIT_GROUP",
            "MOVE_DEMAND",
            "CHANGE_ROOT",
            "ADD_ROOT",
            "REMOVE_ROOT",
            "MOVE_BRANCH",
            "REROUTE",
            "DROP_DEMAND",
            "RESTORE_DEMAND")) attempts.put(name, 0);
    for (int round = 0; round < 2 && !router.budgetExpired(); round++) {
      // Destroy/repair may start from a worse partial state; the retained best is separate.
      List<Network.Tree> snapshot = new ArrayList<>(current);
      for (Network.Tree seed : snapshot.subList(0, Math.min(8, snapshot.size()))) {
        if (router.budgetExpired()) break;
        List<Network.Tree> rest = new ArrayList<>(current);
        rest.removeIf(t -> t.signature().equals(seed.signature()));
        attempts.merge("REMOVE_ROOT", 1, Integer::sum);
        current = accept(current, rest);
        for (String id :
            new ArrayList<>(seed.demands).subList(0, Math.min(2, seed.demands.size()))) {
          try {
            attempts.merge("DROP_DEMAND", 1, Integer::sum);
            Network.Tree reduced = moves.drop(seed, id);
            List<Network.Tree> trial = new ArrayList<>(rest);
            if (reduced != null) trial.add(reduced);
            current = accept(current, trial);
            for (Network.Tree target : rest.subList(0, Math.min(2, rest.size()))) {
              attempts.merge("MOVE_DEMAND", 1, Integer::sum);
              Dataset.Demand demand =
                  data.demands.stream().filter(d -> d.id.equals(id)).findFirst().orElseThrow();
              Network.Tree moved = insert(target, demand);
              if (moved != null) {
                trial = new ArrayList<>(rest);
                trial.remove(target);
                if (reduced != null) trial.add(reduced);
                trial.add(moved);
                current = accept(current, trial);
              }
            }
          } catch (Failure ignored) {
          }
        }
        for (Network.Tree other : rest.subList(0, Math.min(2, rest.size()))) {
          attempts.merge("MERGE_GROUPS", 1, Integer::sum);
          Network.Tree merged = seed;
          for (String id : other.demands) {
            Dataset.Demand d =
                data.demands.stream().filter(x -> x.id.equals(id)).findFirst().orElseThrow();
            merged = insert(merged, d);
            if (merged == null) break;
          }
          if (merged != null) {
            List<Network.Tree> trial = new ArrayList<>(rest);
            trial.remove(other);
            trial.add(merged);
            current = accept(current, trial);
            add(merged);
          }
        }
        attempts.merge("SPLIT_GROUP", 1, Integer::sum);
        List<Network.Tree> split = new ArrayList<>(rest);
        for (String id : seed.demands) {
          Network.Tree chosen = null;
          for (Network.Tree single : pool)
            if (single.demands.size() == 1
                && single.demands.contains(id)
                && evaluation.compatible(split, single)
                && (chosen == null || privateObjective(single) < privateObjective(chosen)))
              chosen = single;
          if (chosen != null) split.add(chosen);
        }
        current = accept(current, split);
        try {
          attempts.merge("REROUTE", 1, Integer::sum);
          Network.Tree rerouted = moves.reroute(seed);
          if (rerouted != null) {
            List<Network.Tree> trial = new ArrayList<>(rest);
            trial.add(rerouted);
            current = accept(current, trial);
            add(rerouted);
          }
        } catch (Failure ignored) {
        }
        int rootCount = 0;
        for (Existing.Root root : existing.roots(seed.start.p)) {
          if (root.key.equals(seed.root.key)) continue;
          if (rootCount++ >= 2 || router.budgetExpired()) break;
          try {
            attempts.merge("CHANGE_ROOT", 1, Integer::sum);
            Network.Tree shifted = moves.changeRoot(seed, root);
            if (shifted != null) {
              List<Network.Tree> trial = new ArrayList<>(rest);
              trial.add(shifted);
              current = accept(current, trial);
              add(shifted);
            }
          } catch (Failure ignored) {
          }
        }
        for (Network.Node node : seed.nodes())
          if (node != seed.start && node.terminalId == null) {
            for (double dx : new double[] {-3, 3})
              try {
                attempts.merge("MOVE_BRANCH", 1, Integer::sum);
                Network.Tree moved = moves.moveBranch(seed, node.key, dx, 0);
                if (moved != null) {
                  List<Network.Tree> trial = new ArrayList<>(rest);
                  trial.add(moved);
                  current = accept(current, trial);
                  add(moved);
                }
              } catch (Failure ignored) {
              }
            break;
          }
        List<Dataset.Demand> missing = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (Network.Tree t : current) used.addAll(t.demands);
        for (Dataset.Demand d : data.demands) if (!used.contains(d.id)) missing.add(d);
        missing.sort(Comparator.comparingDouble(d -> distance(seed, d.terminals.get(0).geometry)));
        for (Dataset.Demand d : missing.subList(0, Math.min(3, missing.size()))) {
          attempts.merge("RESTORE_DEMAND", 1, Integer::sum);
          Network.Tree restored = insert(seed, d);
          if (restored != null) {
            List<Network.Tree> trial = new ArrayList<>(rest);
            trial.add(restored);
            current = accept(current, trial);
            add(restored);
          }
        }
      }
      for (Network.Tree alternative : new ArrayList<>(pool)) {
        if (router.budgetExpired()) break;
        attempts.merge("ADD_ROOT", 1, Integer::sum);
        List<Network.Tree> trial = new ArrayList<>();
        for (Network.Tree t : current)
          if (Collections.disjoint(t.demands, alternative.demands)) trial.add(t);
        trial.add(alternative);
        current = accept(current, trial);
      }
    }
    diagnostics.put("neighborhood_attempts", attempts);
  }

  private List<Network.Tree> accept(List<Network.Tree> current, List<Network.Tree> trial) {
    List<Network.Tree> seen = new ArrayList<>();
    for (Network.Tree t : trial) {
      if (!evaluation.compatible(seen, t)) return current;
      seen.add(t);
    }
    try {
      Network.Variant candidate = exporter.export(trial, 1);
      if (candidate.score().compareTo(exporter.export(current, 1).score()) < 0) {
        keep(trial);
        return new ArrayList<>(trial);
      }
    } catch (Failure ignored) {
    }
    return current;
  }

  private int unconnectedFirst;
  private boolean legacyRun;
  private List<Network.Variant> plannedAll = List.of();

  private List<Network.Variant> legacy(int count) {
    legacyRun = true;
    CurrentOptimizer current = new CurrentOptimizer(data, rules, existing, new Router(data, rules));
    List<Network.Variant> result = current.solve(count);
    Map<String, Object> d = new LinkedHashMap<>(current.diagnostics);
    d.remove("B3");
    diagnostics.put("legacy", d);
    baselines.addAll(result);
    return result;
  }

  private static int unconnected(Network.Variant v) {
    return ((List<?>) v.summary.get("unconnected_oks_ids")).size();
  }

  /** More connected points first, then lower score. */
  static boolean better(Network.Variant a, Network.Variant b) {
    int ua = unconnected(a), ub = unconnected(b);
    return ua != ub ? ua < ub : a.score().compareTo(b.score()) < 0;
  }

  public List<Network.Variant> reserveCandidates() {
    if (!legacyRun && rules.current() && rules.text("execution.planner").equals("grid_steiner"))
      legacy(1);
    List<Network.Variant> reserve = new ArrayList<>(baselines);
    reserve.addAll(finalists);
    reserve.sort(Comparator.comparing(Network.Variant::score).thenComparing(v -> v.signature));
    return reserve;
  }

  public Map<String, Object> diagnostics() {
    return diagnostics;
  }
}
