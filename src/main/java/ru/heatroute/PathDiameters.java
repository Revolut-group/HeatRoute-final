package ru.heatroute;

import java.util.*;

/** Bottom-up sizing of constant-flow runs; sibling lengths are never added. */
final class PathDiameters {
  static void assign(Network.Tree tree) {
    for (Network.Edge edge : tree.outgoing(tree.start)) size(tree, edge);
  }

  private static double size(Network.Tree tree, Network.Edge first) {
    List<Network.Edge> run = new ArrayList<>();
    Network.Edge last = first;
    run.add(last);
    while (tree.outgoing(last.to).size() == 1 && last.to.demands.isEmpty()) {
      Network.Edge next = tree.outgoing(last.to).get(0);
      if (next.flow.compareTo(first.flow) != 0) break;
      run.add(next);
      last = next;
    }
    Map<Network.Edge, Double> tails = new LinkedHashMap<>();
    int minimum = Catalog.base(first.flow).dn;
    for (Network.Edge child : tree.outgoing(last.to)) {
      tails.put(child, size(tree, child));
      minimum = Math.max(minimum, child.dn);
    }
    double length = run.stream().mapToDouble(e -> e.line.getLength()).sum();
    for (Catalog.Pipe pipe : Catalog.PIPES)
      if (pipe.dn >= minimum) {
        double tail = 0;
        for (Map.Entry<Network.Edge, Double> child : tails.entrySet())
          if (child.getKey().dn == pipe.dn) tail = Math.max(tail, child.getValue());
        if (length + tail <= pipe.limit + 1e-6) {
          for (Network.Edge edge : run) edge.dn = pipe.dn;
          return length + tail;
        }
      }
    throw new Failure("LENGTH_LIMIT", "No catalog diameter meets the continuous path limit");
  }
}
