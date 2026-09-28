package ru.heatroute;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.function.*;
import org.junit.jupiter.api.*;

class ExhaustiveForestTest {
  static final class Option {
    int index, anchor;
    double price, load;
    Set<String> demands;
  }

  @Test
  void twentyIndependentExhaustiveFiniteForestOracles() {
    for (int seed = 0; seed < 20; seed++) {
      Random random = new Random(16092026 + seed);
      Set<String> ids = new TreeSet<>();
      Map<String, Double> penalties = new TreeMap<>();
      for (int i = 0; i < 6; i++) {
        ids.add("" + i);
        penalties.put("" + i, 25.0 + i);
      }
      List<Option> pool = new ArrayList<>();
      for (int i = 0; i < 14; i++) {
        Option o = new Option();
        o.index = i;
        o.anchor = random.nextInt(3);
        o.price = 3 + random.nextInt(24);
        o.load = 1 + random.nextInt(10);
        o.demands = new TreeSet<>();
        for (int k = 0; k < 1 + random.nextInt(3); k++) o.demands.add("" + random.nextInt(6));
        pool.add(o);
      }
      boolean[][] conflicts = new boolean[14][14];
      for (int i = 0; i < 14; i++)
        for (int j = i + 1; j < 14; j++)
          conflicts[i][j] = conflicts[j][i] = random.nextInt(12) == 0;
      ToDoubleFunction<List<Option>> objective =
          selected -> {
            Set<String> used = new HashSet<>();
            double cost = 0;
            double[] loads = new double[3];
            for (Option o : selected) {
              used.addAll(o.demands);
              cost += o.price;
              loads[o.anchor] += o.load;
            }
            for (String id : ids) if (!used.contains(id)) cost += penalties.get(id);
            for (double load : loads) if (load > 0) cost += 7 + (load > 12 ? 23 : 0);
            return cost;
          };
      BiPredicate<List<Option>, Option> compatible =
          (selected, o) -> {
            for (Option x : selected)
              if (!Collections.disjoint(x.demands, o.demands) || conflicts[x.index][o.index])
                return false;
            return true;
          };
      // Separate subset enumeration: does not reuse search traversal, bound, cover partitions, or
      // compatibility predicate.
      double oracle = Double.POSITIVE_INFINITY;
      for (int mask = 0; mask < (1 << pool.size()); mask++) {
        boolean valid = true;
        Set<String> seen = new HashSet<>();
        double cost = 0;
        double[] loads = new double[3];
        for (int i = 0; i < 14 && valid; i++)
          if ((mask & (1 << i)) != 0) {
            Option o = pool.get(i);
            for (String id : o.demands)
              if (!seen.add(id)) {
                valid = false;
                break;
              }
            for (int j = 0; j < i; j++)
              if ((mask & (1 << j)) != 0 && conflicts[i][j]) valid = false;
            cost += o.price;
            loads[o.anchor] += o.load;
          }
        if (!valid) continue;
        for (String id : ids) if (!seen.contains(id)) cost += penalties.get(id);
        for (double load : loads) if (load > 0) cost += 7 + (load > 12 ? 23 : 0);
        oracle = Math.min(oracle, cost);
      }
      FiniteCoverSearch<Option> search =
          new FiniteCoverSearch<>(
              pool,
              ids,
              o -> o.demands,
              o -> o.price,
              penalties,
              compatible,
              objective,
              s -> {},
              objective.applyAsDouble(List.of()),
              1000000);
      search.run();
      assertFalse(search.exhausted);
      assertEquals(oracle, search.best, 1e-9, "finite pool scene " + seed);
    }
  }
}
