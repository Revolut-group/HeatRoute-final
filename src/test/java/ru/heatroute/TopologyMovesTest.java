package ru.heatroute;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.*;
import java.math.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.*;

class TopologyMovesTest {
  @TempDir Path temp;

  @Test
  void transactionalMovesRecomputeDemandFlowAndDn() throws Exception {
    ObjectNode s = Scenes.n06();
    ((ObjectNode) s.path("features").get(4))
        .set("geometry", Geo.json(Geo.point(Scenes.p(-30, 100))));
    Scenes.add(s, "p2", "oks_connection_point", Geo.point(Scenes.p(30, 100))).put("flow_tph", 20);
    Path in = Scenes.save(temp.resolve("scene.json"), s);
    Rules rules = new Rules(java.nio.file.Path.of("config/rules-v5.yaml"));
    try (Dataset d = new Ingest().read(in)) {
      Existing ex = new Existing(d, rules);
      Router router = new Router(d, rules);
      Evaluation evaluator = new Evaluation(d, rules, router);
      Existing.Root root =
          ex.roots(Scenes.p(0, 100)).stream()
              .filter(r -> r.target.id.equals("ch"))
              .findFirst()
              .orElseThrow();
      Network.Tree seed = new Network.Tree(root);
      Network.Node branch = new Network.Node("branch", Scenes.p(0, 40));
      seed.edges.add(new Network.Edge(seed.start, branch, Geo.line(seed.start.p, branch.p)));
      for (Dataset.Demand dem : d.demands) {
        Network.Node leaf =
            new Network.Node(
                dem.id, dem.terminals.get(0).geometry.getCoordinate(), List.of(dem), dem.id);
        seed.edges.add(new Network.Edge(branch, leaf, Geo.line(branch.p, leaf.p)));
      }
      seed = evaluator.evaluate(seed, false);
      String original = seed.signature();
      TopologyMoves moves = new TopologyMoves(d, rules, router);
      Network.Tree dropped = moves.drop(seed, "p2");
      assertEquals(Set.of("p1"), dropped.demands);
      assertEquals(1, dropped.edges.size());
      assertEquals(80, dropped.edges.get(0).dn);
      assertEquals(0, new BigDecimal("10").compareTo(dropped.edges.get(0).flow));
      assertEquals(original, seed.signature());
      assertEquals(2, seed.demands.size());
      Network.Tree shifted = moves.moveBranch(seed, "branch", 3, 0);
      assertNotNull(shifted);
      assertNotEquals(seed.signature(), shifted.signature());
      Network.Tree rerouted = moves.reroute(seed);
      assertNotNull(rerouted);
      Existing.Root another =
          ex.roots(Scenes.p(-30, 40)).stream()
              .filter(
                  r ->
                      r.target.type.equals("heat_network") && r.p.distance(Scenes.p(-30, 0)) < 1e-4)
              .findFirst()
              .orElseThrow();
      Network.Tree rooted = moves.changeRoot(seed, another);
      assertNotNull(rooted);
      int i = 0;
      for (Network.Tree tree : List.of(seed, dropped, shifted, rerouted, rooted)) {
        Path output = temp.resolve("move" + (i++) + ".json");
        Json.write(
            output,
            Exporter.collection(List.of(new Exporter(d, ex, rules).export(List.of(tree), 1))));
        Map<String, Object> report = new Verifier().verify(in, output, rules);
        assertTrue((Boolean) report.get("valid"), report.toString());
      }
    }
  }
}
