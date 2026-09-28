package ru.heatroute;

import java.util.*;
import java.util.function.*;

/** Finite set partition search; lower bound excludes nonnegative shared infrastructure costs. */
final class FiniteCoverSearch<T> {
  private final List<T> pool;
  private final SortedSet<String> ids;
  private final Function<T, Set<String>> covered;
  private final ToDoubleFunction<T> privateCost;
  private final Map<String, Double> penalty;
  private final BiPredicate<List<T>, T> compatible;
  private final ToDoubleFunction<List<T>> exact;
  private final Consumer<List<T>> retain;
  private final Map<String, List<T>> choices = new TreeMap<>();
  private final long limit;
  BooleanSupplier stop = () -> false;
  long evaluations;
  boolean exhausted;
  double best;

  FiniteCoverSearch(
      List<T> pool,
      Set<String> ids,
      Function<T, Set<String>> covered,
      ToDoubleFunction<T> privateCost,
      Map<String, Double> penalty,
      BiPredicate<List<T>, T> compatible,
      ToDoubleFunction<List<T>> exact,
      Consumer<List<T>> retain,
      double incumbent,
      long limit) {
    this.pool = pool;
    this.ids = new TreeSet<>(ids);
    this.covered = covered;
    this.privateCost = privateCost;
    this.penalty = penalty;
    this.compatible = compatible;
    this.exact = exact;
    this.retain = retain;
    this.best = incumbent;
    this.limit = limit;
    for (T t : pool)
      for (String id : covered.apply(t)) choices.computeIfAbsent(id, k -> new ArrayList<>()).add(t);
    for (List<T> candidates : choices.values())
      candidates.sort(
          Comparator.comparingDouble(t -> privateCost.applyAsDouble(t) / covered.apply(t).size()));
  }

  void run() {
    visit(new ArrayList<>(), ids, 0);
  }

  private void visit(List<T> selected, Set<String> free, double dropped) {
    if (evaluations >= limit || stop.getAsBoolean()) {
      exhausted = true;
      return;
    }
    evaluations++;
    if (Thread.currentThread().isInterrupted()) throw new Failure("CANCELLED", "Search cancelled");
    if (free.isEmpty()) {
      double score = exact.applyAsDouble(selected);
      if (score <= best + 1e-10) {
        best = Math.min(best, score);
        retain.accept(List.copyOf(selected));
      }
      return;
    }
    double bound = dropped;
    for (T t : selected) bound += privateCost.applyAsDouble(t);
    for (String id : free) {
      double value = penalty.get(id);
      for (T t : choices.getOrDefault(id, List.of()))
        if (free.containsAll(covered.apply(t)))
          value = Math.min(value, privateCost.applyAsDouble(t) / covered.apply(t).size());
      bound += value;
    }
    if (bound > best + 1e-10) return;
    String id =
        free.stream()
            .min(
                Comparator.comparingInt((String a) -> choices.getOrDefault(a, List.of()).size())
                    .thenComparing(a -> a))
            .orElseThrow();
    for (T t : choices.getOrDefault(id, List.of()))
      if (free.containsAll(covered.apply(t)) && compatible.test(selected, t)) {
        selected.add(t);
        Set<String> rest = new TreeSet<>(free);
        rest.removeAll(covered.apply(t));
        visit(selected, rest, dropped);
        selected.remove(selected.size() - 1);
        if (exhausted) return;
      }
    Set<String> rest = new TreeSet<>(free);
    rest.remove(id);
    visit(selected, rest, dropped + penalty.get(id));
  }
}
