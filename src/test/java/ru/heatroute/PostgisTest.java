package ru.heatroute;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.sql.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.*;

@EnabledIfEnvironmentVariable(named = "HEATROUTE_TEST_POSTGIS", matches = "true")
class PostgisTest {
  @TempDir Path temp;

  @Test
  void realStoreParityAndCrs() throws Exception {
    Dataset memory = new Ingest().read(Path.of("src/test/resources/fixtures/legacy-dataset.geojson"));
    try (Dataset disk = new PostgisIngest().read(Path.of("src/test/resources/fixtures/legacy-dataset.geojson"))) {
      assertEquals(memory.audit(), disk.audit());
      for (Dataset.Demand d : memory.demands) {
        Coordinate p = d.terminals.get(0).geometry.getCoordinate();
        assertEquals(memory.hosts(p).get(0).key, disk.hosts(p).get(0).key);
      }
      // Same connection settings as the store (HEATROUTE_JDBC_URL/_DB_USER/_DB_PASSWORD), not a
      // hard-coded port.
      Connection c = ((PostgisFeatureStore) disk.store).connection;
      try (Statement s = c.createStatement();
          ResultSet r =
              s.executeQuery(
                  "SELECT ST_X(g),ST_Y(g) FROM (SELECT"
                      + " ST_Transform(ST_SetSRID(ST_MakePoint(37.6344054041544,55.6994810644531),4326),32637)"
                      + " g) t")) {
        r.next();
        Coordinate xy = Geo.xy(37.6344054041544, 55.6994810644531);
        assertEquals(xy.x, r.getDouble(1), 1e-5);
        assertEquals(xy.y, r.getDouble(2), 1e-5);
      }
    }
  }

  /**
   * Batched import (1000 rows per round trip) must still see duplicates at once, also across batch
   * boundaries.
   */
  @Test
  void batchedImportKeepsDuplicateIdParity() throws Exception {
    ObjectNode scene = Scenes.n06();
    for (int i = 0; i < 2500; i++)
      Scenes.add(
              scene,
              "r" + i,
              "restriction",
              Scenes.box(
                  300 + (i % 50) * 12,
                  300 + (i / 50) * 12,
                  308 + (i % 50) * 12,
                  308 + (i / 50) * 12))
          .put("restriction_type", i % 3 == 0 ? "park" : "water");
    Scenes.add(scene, "r5", "restriction", Scenes.box(-400, -400, -390, -390))
        .put("restriction_type", "water");
    Scenes.add(scene, "r2499", "oks_existing", Scenes.box(-500, -400, -490, -390));
    Path input = Scenes.save(temp.resolve("batched.geojson"), scene);
    Rules current = new Rules(null);
    try (Dataset memory = new Ingest().read(input, current);
        Dataset disk = new PostgisIngest(policies(current)).read(input)) {
      assertEquals(memory.audit(), disk.audit());
      assertEquals(2, disk.warningCounts.get("DUPLICATE_ID"));
      assertTrue(disk.containsId("r5~dup"));
      assertTrue(disk.containsId("r2499~dup"));
      assertFalse(disk.containsId("r2500"));
      Envelope far = new Envelope(Scenes.p(-395, -395));
      assertEquals(memory.near(far, 1).size(), disk.near(far, 1).size());
    }
    Rules v5 = new Rules(Path.of("config/rules-v5.yaml"));
    assertEquals(
        "DUPLICATE_ID", assertThrows(Failure.class, () -> new Ingest().read(input, v5)).code);
    assertEquals(
        "DUPLICATE_ID",
        assertThrows(Failure.class, () -> new PostgisIngest(policies(v5)).read(input)).code);
  }

  /**
   * A killed process never runs close(): the next import removes rows whose owning session is gone.
   */
  @Test
  void importsOfDeadSessionsAreRemoved() throws Exception {
    PostgisFeatureStore dead = new PostgisFeatureStore();
    dead.feature("a", "restriction", "{}");
    Polygon box = Scenes.box(0, 0, 1, 1);
    dead.part("a", 0, "water", 0, box, Geo.fingerprint(box), box.getEnvelopeInternal(), false);
    dead.flush();
    int pid;
    try (Statement s = dead.connection.createStatement();
        ResultSet r = s.executeQuery("SELECT pg_backend_pid()")) {
      r.next();
      pid = r.getInt(1);
    }
    try (PostgisFeatureStore alive = new PostgisFeatureStore()) {
      alive.feature("b", "restriction", "{}");
      alive.flush();
      dead.connection.close();
      for (int i = 0; i < 100 && running(alive.connection, pid); i++) Thread.sleep(50);
      try (PostgisFeatureStore next = new PostgisFeatureStore()) {
        assertEquals(0, count(next.connection, "geometry_parts", dead.job));
        assertEquals(0, count(next.connection, "input_features", dead.job));
        assertEquals(0, count(next.connection, "imports", dead.job));
        assertEquals(1, count(next.connection, "input_features", alive.job));
      }
    }
  }

  private static boolean running(Connection c, int pid) throws SQLException {
    try (Statement s = c.createStatement();
        ResultSet r = s.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE pid=" + pid)) {
      r.next();
      return r.getLong(1) > 0;
    }
  }

  private static long count(Connection c, String table, String job) throws SQLException {
    try (PreparedStatement p =
        c.prepareStatement("SELECT count(*) FROM " + table + " WHERE import_id=?")) {
      p.setString(1, job);
      try (ResultSet r = p.executeQuery()) {
        r.next();
        return r.getLong(1);
      }
    }
  }

  private static Ingest policies(Rules rules) {
    Ingest in = new Ingest();
    in.current = rules.current();
    in.repair = rules.text("input.invalid_geometry").equals("repair");
    in.unknownRestriction = rules.text("input.unknown_restriction");
    in.snap = rules.number("geometry.topology_snap_m");
    return in;
  }

  @Test
  void closedCourtyardEntranceMatchesMemoryStore() throws Exception {
    Rules rules = new Rules(null);
    ObjectNode scene = Scenes.collection();
    Polygon host =
        Geo.GF.createPolygon(
            Geo.GF.createLinearRing(Scenes.box(0, 0, 100, 100).getCoordinates()),
            new LinearRing[] {
              Geo.GF.createLinearRing(Scenes.box(10, 10, 90, 90).getCoordinates())
            });
    Scenes.add(scene, "host", "restriction", host).put("restriction_type", "building");
    Scenes.add(scene, "terminal", "oks_connection_point", Geo.point(Scenes.p(8, 50)))
        .put("flow_tph", 10);
    Path input = Scenes.save(temp.resolve("courtyard.geojson"), scene);
    try (Dataset memory = new Ingest().read(input, rules);
        Dataset disk = new PostgisIngest(true).read(input)) {
      Coordinate point = memory.demands.get(0).terminals.get(0).geometry.getCoordinate();
      FeasibleEntrances a = new FeasibleEntrances(memory, rules),
          b = new FeasibleEntrances(disk, rules);
      java.util.List<Coordinate> ap = a.ports(point, 100, false), bp = b.ports(point, 100, false);
      assertEquals(8, a.insideLength(point, ap.get(0)), .011);
      assertEquals(ap.size(), bp.size());
      for (int i = 0; i < ap.size(); i++) assertEquals(0, ap.get(i).distance(bp.get(i)), 1e-6);
    }
  }

  @Test
  void P03_diskPolygonAndLocalQuery() throws Exception {
    Path input = temp.resolve("giant.geojson");
    int points = 70000;
    try (JsonGenerator g = Json.M.getFactory().createGenerator(input.toFile(), JsonEncoding.UTF8)) {
      g.writeStartObject();
      g.writeStringField("type", "FeatureCollection");
      g.writeArrayFieldStart("features");
      g.writeStartObject();
      g.writeStringField("type", "Feature");
      g.writeObjectFieldStart("properties");
      g.writeStringField("id", "large");
      g.writeStringField("object_type", "restriction");
      g.writeStringField("restriction_type", "water");
      g.writeEndObject();
      g.writeObjectFieldStart("geometry");
      g.writeStringField("type", "Polygon");
      g.writeArrayFieldStart("coordinates");
      g.writeStartArray();
      for (int i = 0; i <= points; i++) {
        double a = 2 * Math.PI * (i % points) / points;
        Coordinate ll = Geo.ll(Scenes.p(100 * Math.cos(a), 100 * Math.sin(a)));
        g.writeStartArray();
        g.writeNumber(ll.x);
        g.writeNumber(ll.y);
        g.writeEndArray();
      }
      g.writeEndArray();
      g.writeEndArray();
      g.writeEndObject();
      g.writeEndObject();
      g.writeEndArray();
      g.writeEndObject();
    }
    try (Dataset d = new PostgisIngest().read(input)) {
      assertEquals(points + 1, d.coordinateCount);
      assertEquals(1, d.near(new Envelope(Scenes.p(0, 0)), 1).size());
      Dataset.Obstacle o = d.near(new Envelope(Scenes.p(0, 0)), 1).get(0);
      assertTrue(o.geometry.covers(Geo.point(Scenes.p(0, 0))));
      assertEquals(0, d.near(new Envelope(Scenes.p(1000, 0)), 1).size());
      Polygon nested = Scenes.box(-10, -10, 10, 10);
      PostgisFeatureStore store = (PostgisFeatureStore) d.store;
      store.part(
          "large",
          1,
          "water",
          0,
          nested,
          Geo.fingerprint(nested),
          nested.getEnvelopeInternal(),
          false);
      assertThrows(Failure.class, store::validateParts);
    }
  }
}
