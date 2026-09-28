package ru.heatroute;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.*;

class AdvancedTest {
  @TempDir Path temp;

  @Test
  void G03_X05_tenDeterministicRunsAndReordering() throws Exception {
    ObjectNode scene = Scenes.n06();
    Path input = Scenes.save(temp.resolve("input.json"), scene);
    Rules r = new Rules(java.nio.file.Path.of("config/rules-v5.yaml"));
    byte[] reference = null;
    for (int i = 0; i < 10; i++) {
      if (i == 5) {
        ArrayNode arr = (ArrayNode) scene.get("features");
        List<JsonNode> items = new ArrayList<>();
        arr.forEach(items::add);
        Collections.reverse(items);
        arr.removeAll();
        items.forEach(arr::add);
        Scenes.save(input, scene);
      }
      Dataset d = new Ingest().read(input);
      List<Network.Variant> results = new Optimizer(d, r, new Existing(d, r)).solve(1);
      byte[] bytes = Json.M.writeValueAsBytes(Exporter.collection(results));
      if (reference == null) reference = bytes;
      else assertArrayEquals(reference, bytes);
    }
  }

  @Test
  void T08_T09_T10_T24_reconstructionIntervals() throws Exception {
    ObjectNode s = Scenes.collection();
    Scenes.add(s, "src", "source", Geo.point(Scenes.p(0, 0)));
    ObjectNode net =
        Scenes.add(
            s,
            "net",
            "heat_network",
            Geo.line(List.of(Scenes.p(0, 0), Scenes.p(40, 0), Scenes.p(40, 60))));
    net.put("diameter", 80).put("flow_tph", 10);
    Scenes.add(s, "a", "oks_connection_point", Geo.point(Scenes.p(40, 20))).put("flow_tph", 10);
    Scenes.add(s, "b", "oks_connection_point", Geo.point(Scenes.p(40, 50))).put("flow_tph", 10);
    Dataset d = new Ingest().read(Scenes.save(temp.resolve("recon.json"), s));
    Rules r = new Rules(java.nio.file.Path.of("config/rules-v5.yaml"));
    Existing ex = new Existing(d, r);
    Dataset.Feature f = d.features.get("net");
    List<Network.Tree> trees = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      Dataset.Demand demand = d.demands.get(i);
      double at = i == 0 ? 60 : 90;
      Existing.Root root =
          new Existing.Root(
              f,
              new org.locationtech.jts.linearref.LengthIndexedLine(f.geometry).extractPoint(at),
              at,
              80,
              2);
      Network.Tree t = new Network.Tree(root);
      Network.Node leaf =
          new Network.Node(
              "d:" + demand.id,
              Scenes.p(50, i == 0 ? 20 : 50),
              List.of(demand),
              demand.terminals.get(0).id);
      Network.Edge e = new Network.Edge(t.start, leaf, Geo.line(t.start.p, leaf.p));
      e.flow = new BigDecimal("10");
      e.dn = 80;
      t.edges.add(e);
      trees.add(t);
    }
    List<Reconstruction.Part> result = Reconstruction.calculate(d, ex, trees);
    assertEquals(2, result.size());
    assertEquals(125, result.get(0).dn);
    assertEquals(100, result.get(1).dn);
    assertEquals(60, result.get(0).line.getLength(), 1e-6);
    assertEquals(3, result.get(0).line.getNumPoints());
    assertEquals(30, result.get(1).line.getLength(), 1e-6);
    assertEquals(0, new BigDecimal("20").compareTo(result.get(0).added));
  }

  @Test
  void T17_T18_wholeComponentLength() throws Exception {
    ObjectNode s = Scenes.n06();
    ((ObjectNode) s.path("features").get(4).path("properties")).put("flow_tph", 4);
    Dataset d = new Ingest().read(Scenes.save(temp.resolve("length.json"), s));
    Rules r = new Rules(java.nio.file.Path.of("config/rules-v5.yaml"));
    Existing ex = new Existing(d, r);
    Existing.Root root =
        ex.roots(Scenes.p(0, 100)).stream()
            .filter(a -> a.target.id.equals("ch"))
            .findFirst()
            .orElseThrow();
    Network.Tree tree = new Network.Tree(root);
    Dataset.Demand dem = d.demands.get(0);
    Network.Node end = new Network.Node("d:p1", Scenes.p(0, 250), List.of(dem), "p1");
    tree.edges.add(new Network.Edge(tree.start, end, Geo.line(tree.start.p, end.p)));
    Network.Tree checked = new Evaluation(d, r, new Router(d, r)).evaluate(tree, false);
    assertEquals(65, checked.edges.get(0).base);
    assertEquals(80, checked.edges.get(0).dn);
  }

  @Test
  void T03_noTransitiveSnap() throws Exception {
    ObjectNode s = Scenes.collection();
    Scenes.add(s, "s", "source", Geo.point(Scenes.p(-20, 0)));
    for (int i = 0; i < 3; i++)
      Scenes.add(
              s, "n" + i, "heat_network", Geo.line(Scenes.p(i * .09, 0), Scenes.p(i * .09, 20 + i)))
          .put("diameter", 80);
    Dataset d = new Ingest().read(Scenes.save(temp.resolve("snap.json"), s));
    Existing e = new Existing(d, new Rules(java.nio.file.Path.of("config/rules-v5.yaml")));
    long near = e.nodes.stream().filter(n -> n.p.distance(Scenes.p(0, 0)) < .3).count();
    assertEquals(2, near);
  }

  @Test
  void G20_disjointSpecialZonesRemainSeparate() {
    List<Intervals.Zone> out =
        Intervals.combine(
            List.of(
                new Intervals.Zone(0, 10, 1.6, "road"), new Intervals.Zone(12, 20, 1.75, "tram")),
            true);
    assertEquals(2, out.size());
    assertEquals(1, Intervals.factor(out, 11));
  }

  @Test
  void G14_G15_bufferVerticesMustMeetExactDistance() throws Exception {
    ObjectNode s = Scenes.n06();
    Scenes.add(s, "water", "restriction", Scenes.box(10, 10, 20, 20))
        .put("restriction_type", "water");
    Dataset d = new Ingest().read(Scenes.save(temp.resolve("box.json"), s));
    GeometryRules geometry =
        new GeometryRules(d, new Rules(java.nio.file.Path.of("config/rules-v5.yaml")));
    assertFalse(geometry.segment(Scenes.p(8.8, 10), Scenes.p(8.8, 20), 80, null, null));
    assertTrue(geometry.segment(Scenes.p(8.7, 10), Scenes.p(8.7, 20), 80, null, null));
  }
}
