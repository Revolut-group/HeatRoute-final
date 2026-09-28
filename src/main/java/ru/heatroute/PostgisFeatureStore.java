package ru.heatroute;

import java.io.*;
import java.sql.*;
import java.util.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.io.*;
import org.locationtech.jts.operation.polygonize.Polygonizer;
import org.locationtech.jts.operation.union.UnaryUnionOp;

/** Immutable per-import PostGIS geometry parts; all queries are scoped by import_id. */
final class PostgisFeatureStore implements FeatureStore {
  final Connection connection;
  final String job = UUID.randomUUID().toString();
  private final WKBWriter writer = new WKBWriter();
  private final WKBReader reader = new WKBReader(Geo.GF);

  PostgisFeatureStore() throws SQLException {
    String url =
        System.getenv()
            .getOrDefault("HEATROUTE_JDBC_URL", "jdbc:postgresql://localhost:15432/heatroute");
    connection =
        DriverManager.getConnection(
            url,
            System.getenv().getOrDefault("HEATROUTE_DB_USER", "heatroute"),
            System.getenv().getOrDefault("HEATROUTE_DB_PASSWORD", "heatroute-local"));
    try (Statement s = connection.createStatement()) {
      s.execute("CREATE EXTENSION IF NOT EXISTS postgis");
      s.execute(
          "CREATE TABLE IF NOT EXISTS input_features(import_id varchar(36),id text,object_type"
              + " text,properties jsonb,PRIMARY KEY(import_id,id))");
      s.execute(
          "CREATE TABLE IF NOT EXISTS geometry_parts(import_id varchar(36),id text,part"
              + " integer,kind text,width double precision,fingerprint text,geom"
              + " geometry(Geometry,32637),bounds geometry(Polygon,32637),disk boolean NOT NULL"
              + " DEFAULT false,PRIMARY KEY(import_id,id,part))");
      s.execute(
          "CREATE INDEX IF NOT EXISTS geometry_parts_bbox ON geometry_parts USING gist(bounds)");
      s.execute("CREATE INDEX IF NOT EXISTS geometry_parts_import ON geometry_parts(import_id)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS geometry_segments(import_id varchar(36),id text,part"
              + " integer,ring integer,seq bigint,x1 double precision,y1 double precision,x2 double"
              + " precision,y2 double precision,geom geometry(LineString,32637) GENERATED ALWAYS AS"
              + " (ST_SetSRID(ST_MakeLine(ST_MakePoint(x1,y1),ST_MakePoint(x2,y2)),32637))"
              + " STORED)");
      s.execute(
          "CREATE INDEX IF NOT EXISTS geometry_segments_bbox ON geometry_segments USING"
              + " gist(geom)");
      s.execute(
          "CREATE INDEX IF NOT EXISTS geometry_segments_key ON"
              + " geometry_segments(import_id,id,part,ring)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS imports(import_id varchar(36) PRIMARY KEY,backend_pid integer"
              + " NOT NULL,backend_start timestamptz,started timestamptz NOT NULL DEFAULT now())");
    }
    // Each import is registered with the server session that owns it. close() removes the rows; a
    // killed or crashed process
    // cannot, so rows of imports whose session no longer exists are removed here (gigabytes after a
    // failed 3 GiB run).
    try (PreparedStatement p =
        connection.prepareStatement(
            "INSERT INTO imports(import_id,backend_pid,backend_start) SELECT ?,pid,backend_start"
                + " FROM pg_stat_activity WHERE pid=pg_backend_pid()")) {
      p.setString(1, job);
      p.executeUpdate();
    }
    List<String> orphans = new ArrayList<>();
    // backend_start is hidden for sessions of other roles without pg_read_all_stats: then the pid
    // alone keeps the import.
    try (Statement s = connection.createStatement();
        ResultSet r =
            s.executeQuery(
                "SELECT import_id FROM imports i WHERE NOT EXISTS(SELECT 1 FROM pg_stat_activity a"
                    + " WHERE a.pid=i.backend_pid AND (a.backend_start IS NULL OR i.backend_start"
                    + " IS NULL OR a.backend_start=i.backend_start)) ORDER BY started")) {
      while (r.next()) orphans.add(r.getString(1));
    }
    for (String orphan : orphans) delete(orphan);
  }

  private void delete(String importId) throws SQLException {
    for (String table : List.of("geometry_segments", "geometry_parts", "input_features", "imports"))
      try (PreparedStatement p =
          connection.prepareStatement("DELETE FROM " + table + " WHERE import_id=?")) {
        p.setString(1, importId);
        p.executeUpdate();
      }
  }

  // Import rows go out in JDBC batches (one round trip per BATCH rows instead of one per row: about
  // 10x faster imports).
  // Every read of these tables calls flush() first. Duplicate ids are still reported at once: a
  // 64-bit hash set of the ids
  // (8 B per slot, no strings kept) flags a possible repeat, and only then the pending rows are
  // flushed and the table is asked.
  private static final int BATCH = 1000;
  private PreparedStatement featureInsert, partInsert;
  private int pendingFeatures, pendingParts, idCount;
  private long[] idHashes = new long[1 << 12];

  void feature(String id, String type, String props) {
    try {
      if (!rememberId(id)) {
        flush();
        if (containsId(id)) throw new Failure("DUPLICATE_ID", "Duplicate normalized ID", id);
      }
      if (featureInsert == null)
        featureInsert =
            connection.prepareStatement("INSERT INTO input_features VALUES(?,?,?,?::jsonb)");
      featureInsert.setString(1, job);
      featureInsert.setString(2, id);
      featureInsert.setString(3, type);
      featureInsert.setString(4, props);
      featureInsert.addBatch();
      if (++pendingFeatures >= BATCH) flush();
    } catch (SQLException e) {
      throw new Failure("INFRASTRUCTURE_ERROR", message(e), id);
    }
  }

  void part(
      String id,
      int part,
      String type,
      double width,
      Geometry geometry,
      String fingerprint,
      Envelope bbox,
      boolean disk) {
    try {
      if (partInsert == null)
        partInsert =
            connection.prepareStatement(
                "INSERT INTO geometry_parts"
                    + " VALUES(?,?,?,?,?,?,ST_GeomFromWKB(?,32637),ST_MakeEnvelope(?,?,?,?,32637),?)");
      PreparedStatement p = partInsert;
      p.setString(1, job);
      p.setString(2, id);
      p.setInt(3, part);
      p.setString(4, type);
      p.setDouble(5, width);
      p.setString(6, fingerprint);
      p.setBytes(7, geometry == null ? null : writer.write(geometry));
      p.setDouble(8, bbox.getMinX());
      p.setDouble(9, bbox.getMinY());
      p.setDouble(10, bbox.getMaxX());
      p.setDouble(11, bbox.getMaxY());
      p.setBoolean(12, disk);
      p.addBatch();
      if (++pendingParts >= BATCH) flush();
    } catch (SQLException e) {
      throw new Failure("INFRASTRUCTURE_ERROR", message(e), id);
    }
  }

  /** Sends pending feature and part rows. */
  void flush() throws SQLException {
    if (pendingFeatures > 0) {
      pendingFeatures = 0;
      featureInsert.executeBatch();
    }
    if (pendingParts > 0) {
      pendingParts = 0;
      partInsert.executeBatch();
    }
  }

  private static String message(SQLException e) {
    SQLException next = e.getNextException();
    return next != null && next.getMessage() != null ? next.getMessage() : e.getMessage();
  }

  /** false when the id may already be imported (64-bit hash already present). */
  private boolean rememberId(String id) {
    long h = 1125899906842597L;
    for (int i = 0; i < id.length(); i++) h = 31 * h + id.charAt(i);
    h ^= h >>> 33;
    h *= 0xff51afd7ed558ccdL;
    h ^= h >>> 33;
    h *= 0xc4ceb9fe1a85ec53L;
    h ^= h >>> 33;
    if (h == 0) h = 1;
    if (idCount * 10L >= idHashes.length * 7L) {
      long[] old = idHashes;
      idHashes = new long[old.length * 2];
      idCount = 0;
      for (long x : old) if (x != 0) insertHash(x);
    }
    return insertHash(h);
  }

  private boolean insertHash(long h) {
    int mask = idHashes.length - 1;
    for (int i = (int) (h ^ h >>> 32) & mask; ; i = (i + 1) & mask) {
      if (idHashes[i] == h) return false;
      if (idHashes[i] == 0) {
        idHashes[i] = h;
        idCount++;
        return true;
      }
    }
  }

  void segments(String id, int part, java.nio.file.Path binary) throws Exception {
    try (DataInputStream in =
            new DataInputStream(
                new BufferedInputStream(java.nio.file.Files.newInputStream(binary)));
        PreparedStatement p =
            connection.prepareStatement(
                "INSERT INTO geometry_segments(import_id,id,part,ring,seq,x1,y1,x2,y2)"
                    + " VALUES(?,?,?,?,?,?,?,?,?)")) {
      flush();
      connection.setAutoCommit(false);
      int batch = 0;
      for (; ; ) {
        int ring;
        try {
          ring = in.readInt();
        } catch (EOFException done) {
          break;
        }
        long size = in.readLong();
        double ax = in.readDouble(), ay = in.readDouble();
        for (long i = 1; i < size; i++) {
          double bx = in.readDouble(), by = in.readDouble();
          p.setString(1, job);
          p.setString(2, id);
          p.setInt(3, part);
          p.setInt(4, ring);
          p.setLong(5, i - 1);
          p.setDouble(6, ax);
          p.setDouble(7, ay);
          p.setDouble(8, bx);
          p.setDouble(9, by);
          p.addBatch();
          ax = bx;
          ay = by;
          if (++batch >= 4096) {
            p.executeBatch();
            connection.commit();
            batch = 0;
          }
        }
      }
      p.executeBatch();
      connection.commit();
      connection.setAutoCommit(true);
    } catch (Exception e) {
      connection.rollback();
      connection.setAutoCommit(true);
      throw e;
    }
  }

  void validateDisk(String id, int part) throws SQLException {
    try (Statement stats = connection.createStatement()) {
      stats.execute("ANALYZE geometry_segments");
    }
    // Nonadjacent self intersections and inter-ring intersections are invalid.
    String sql =
        "SELECT a.ring,a.seq,b.ring,b.seq FROM geometry_segments a JOIN geometry_segments b ON"
            + " a.import_id=b.import_id AND a.id=b.id AND a.part=b.part AND (a.ring<b.ring OR"
            + " (a.ring=b.ring AND a.seq<b.seq)) AND a.geom && b.geom WHERE a.import_id=? AND"
            + " a.id=? AND a.part=? AND ST_Intersects(a.geom,b.geom) AND NOT (a.ring=b.ring AND"
            + " (b.seq=a.seq+1 OR (a.seq=0 AND"
            + " ST_Equals(ST_StartPoint(a.geom),ST_EndPoint(b.geom)))) AND"
            + " ST_Dimension(ST_Intersection(a.geom,b.geom))=0) LIMIT 1";
    try (PreparedStatement p = connection.prepareStatement(sql)) {
      p.setString(1, job);
      p.setString(2, id);
      p.setInt(3, part);
      try (ResultSet r = p.executeQuery()) {
        if (r.next())
          throw new Failure(
              "INVALID_GEOMETRY", "Disk polygon has intersecting nonadjacent segments", id);
      }
    }
    try (PreparedStatement p =
        connection.prepareStatement(
            "SELECT ring,x1,y1 FROM geometry_segments WHERE import_id=? AND id=? AND part=? AND"
                + " seq=0 ORDER BY ring")) {
      p.setString(1, job);
      p.setString(2, id);
      p.setInt(3, part);
      List<double[]> starts = new ArrayList<>();
      try (ResultSet r = p.executeQuery()) {
        while (r.next()) starts.add(new double[] {r.getInt(1), r.getDouble(2), r.getDouble(3)});
      }
      for (double[] s : starts)
        if (s[0] > 0) {
          if (!inRing(id, part, 0, new Coordinate(s[1], s[2])))
            throw new Failure("INVALID_GEOMETRY", "Hole outside shell", id);
          for (double[] other : starts)
            if (other[0] > 0
                && other[0] != s[0]
                && inRing(id, part, (int) other[0], new Coordinate(s[1], s[2])))
              throw new Failure("INVALID_GEOMETRY", "Nested holes", id);
        }
    }
  }

  private boolean inRing(String id, int part, int ring, Coordinate c) throws SQLException {
    String sql =
        "SELECT count(*) FROM geometry_segments WHERE import_id=? AND id=? AND part=? AND ring=?"
            + " AND ((y1<=? AND y2>?) OR (y2<=? AND y1>?)) AND x1+(?-y1)*(x2-x1)/NULLIF(y2-y1,0)>?";
    try (PreparedStatement p = connection.prepareStatement(sql)) {
      p.setString(1, job);
      p.setString(2, id);
      p.setInt(3, part);
      p.setInt(4, ring);
      for (int i = 5; i <= 9; i++) p.setDouble(i, c.y);
      p.setDouble(10, c.x);
      try (ResultSet r = p.executeQuery()) {
        r.next();
        return r.getLong(1) % 2 == 1;
      }
    }
  }

  private boolean inside(String id, int part, Coordinate c) throws SQLException {
    if (!inRing(id, part, 0, c)) return false;
    try (PreparedStatement p =
        connection.prepareStatement(
            "SELECT DISTINCT ring FROM geometry_segments WHERE import_id=? AND id=? AND part=? AND"
                + " ring>0")) {
      p.setString(1, job);
      p.setString(2, id);
      p.setInt(3, part);
      List<Integer> rings = new ArrayList<>();
      try (ResultSet r = p.executeQuery()) {
        while (r.next()) rings.add(r.getInt(1));
      }
      for (int ring : rings) if (inRing(id, part, ring, c)) return false;
    }
    return true;
  }

  private Geometry localDisk(String id, int part, Envelope box) throws SQLException {
    Geometry clip = Geo.GF.toGeometry(box);
    List<Geometry> lines = new ArrayList<>();
    lines.add(clip.getBoundary());
    try (PreparedStatement p =
        connection.prepareStatement(
            "SELECT x1,y1,x2,y2 FROM geometry_segments WHERE import_id=? AND id=? AND part=? AND"
                + " geom && ST_MakeEnvelope(?,?,?,?,32637)")) {
      p.setString(1, job);
      p.setString(2, id);
      p.setInt(3, part);
      p.setDouble(4, box.getMinX());
      p.setDouble(5, box.getMinY());
      p.setDouble(6, box.getMaxX());
      p.setDouble(7, box.getMaxY());
      p.setFetchSize(1024);
      try (ResultSet r = p.executeQuery()) {
        while (r.next()) {
          Geometry g =
              Geo.line(
                      new Coordinate(r.getDouble(1), r.getDouble(2)),
                      new Coordinate(r.getDouble(3), r.getDouble(4)))
                  .intersection(clip);
          if (!g.isEmpty()) lines.add(g);
        }
      }
    }
    Polygonizer polygonizer = new Polygonizer();
    polygonizer.add(UnaryUnionOp.union(lines));
    List<Geometry> filled = new ArrayList<>();
    for (Object object : polygonizer.getPolygons()) {
      Polygon p = (Polygon) object;
      if (inside(id, part, p.getInteriorPoint().getCoordinate())) filled.add(p);
    }
    return filled.isEmpty() ? Geo.GF.createPolygon() : UnaryUnionOp.union(filled);
  }

  @Override
  public synchronized List<Dataset.Obstacle> query(Envelope requested) {
    // Artificial clipping boundaries are placed outside the entire search window.
    Envelope window = new Envelope(requested);
    window.expandBy(100);
    List<Dataset.Obstacle> result = new ArrayList<>();
    try {
      flush();
    } catch (SQLException e) {
      throw new Failure("INFRASTRUCTURE_ERROR", "PostGIS import flush: " + message(e));
    }
    String sql =
        "SELECT id,part,kind,width,fingerprint,ST_AsBinary(geom),disk FROM geometry_parts WHERE"
            + " import_id=? AND bounds && ST_MakeEnvelope(?,?,?,?,32637) ORDER BY id,fingerprint";
    try (PreparedStatement p = connection.prepareStatement(sql)) {
      p.setString(1, job);
      p.setDouble(2, window.getMinX());
      p.setDouble(3, window.getMinY());
      p.setDouble(4, window.getMaxX());
      p.setDouble(5, window.getMaxY());
      List<Object[]> rows = new ArrayList<>();
      try (ResultSet r = p.executeQuery()) {
        while (r.next())
          rows.add(
              new Object[] {
                r.getString(1),
                r.getInt(2),
                r.getString(3),
                r.getDouble(4),
                r.getString(5),
                r.getBytes(6),
                r.getBoolean(7)
              });
      }
      for (Object[] row : rows) {
        Geometry g =
            (Boolean) row[6]
                ? localDisk((String) row[0], (Integer) row[1], window)
                : reader.read((byte[]) row[5]);
        if (!g.isEmpty())
          result.add(
              new Dataset.Obstacle(
                  (String) row[0],
                  (String) row[2],
                  (Integer) row[1],
                  g,
                  (Double) row[3],
                  (String) row[4]));
      }
      return result;
    } catch (Exception e) {
      throw new Failure("INFRASTRUCTURE_ERROR", "PostGIS spatial query: " + e.getMessage());
    }
  }

  @Override
  public boolean containsId(String id) {
    try {
      flush();
    } catch (SQLException e) {
      throw new Failure("INFRASTRUCTURE_ERROR", message(e));
    }
    try (PreparedStatement p =
        connection.prepareStatement("SELECT 1 FROM input_features WHERE import_id=? AND id=?")) {
      p.setString(1, job);
      p.setString(2, id);
      try (ResultSet r = p.executeQuery()) {
        return r.next();
      }
    } catch (SQLException e) {
      throw new Failure("INFRASTRUCTURE_ERROR", e.getMessage());
    }
  }

  String objectType(String id) {
    try {
      flush();
    } catch (SQLException e) {
      throw new Failure("INFRASTRUCTURE_ERROR", message(e));
    }
    try (PreparedStatement p =
        connection.prepareStatement(
            "SELECT object_type FROM input_features WHERE import_id=? AND id=?")) {
      p.setString(1, job);
      p.setString(2, id);
      try (ResultSet r = p.executeQuery()) {
        return r.next() ? r.getString(1) : null;
      }
    } catch (SQLException e) {
      throw new Failure("INFRASTRUCTURE_ERROR", e.getMessage());
    }
  }

  private boolean partContains(String id, int part, boolean disk, Geometry small, Coordinate p)
      throws SQLException {
    return disk ? inside(id, part, p) : small.contains(Geo.point(p));
  }

  private Coordinate shellProbe(String id, int part, boolean disk, Geometry small)
      throws SQLException {
    if (!disk) return ((Polygon) small).getExteriorRing().getCoordinateN(0);
    try (PreparedStatement p =
        connection.prepareStatement(
            "SELECT x1,y1,x2,y2 FROM geometry_segments WHERE import_id=? AND id=? AND part=? AND"
                + " ring=0 ORDER BY seq LIMIT 1")) {
      p.setString(1, job);
      p.setString(2, id);
      p.setInt(3, part);
      try (ResultSet r = p.executeQuery()) {
        if (!r.next()) throw new Failure("INVALID_GEOMETRY", "Empty disk shell", id);
        return new Coordinate(
            (r.getDouble(1) + r.getDouble(3)) / 2, (r.getDouble(2) + r.getDouble(4)) / 2);
      }
    }
  }

  private void validateDiskPairs(Dataset tolerant) throws SQLException {
    String candidates =
        "SELECT a.id,a.part,a.disk,ST_AsBinary(a.geom),b.part,b.disk,ST_AsBinary(b.geom) FROM"
            + " geometry_parts a JOIN geometry_parts b ON a.import_id=b.import_id AND a.id=b.id AND"
            + " a.part<b.part AND a.bounds && b.bounds WHERE a.import_id=? AND b.part>0 AND (a.disk"
            + " OR b.disk) ORDER BY a.id,a.part,b.part";
    try (PreparedStatement pairs = connection.prepareStatement(candidates)) {
      pairs.setString(1, job);
      pairs.setFetchSize(128);
      try (ResultSet row = pairs.executeQuery()) {
        while (row.next()) {
          String id = row.getString(1);
          int a = row.getInt(2), b = row.getInt(5);
          boolean ad = row.getBoolean(3), bd = row.getBoolean(6);
          Geometry ag = null, bg = null;
          try {
            if (!ad) ag = reader.read(row.getBytes(4));
            if (!bd) bg = reader.read(row.getBytes(7));
          } catch (ParseException e) {
            throw new Failure("INVALID_GEOMETRY", e.getMessage(), id);
          }
          String intersects;
          if (ad && bd)
            intersects =
                "SELECT 1 FROM geometry_segments a JOIN geometry_segments b ON"
                    + " a.import_id=b.import_id AND a.id=b.id AND a.geom && b.geom WHERE"
                    + " a.import_id=? AND a.id=? AND a.part=? AND b.part=? AND"
                    + " (ST_Crosses(a.geom,b.geom) OR"
                    + " ST_Dimension(ST_Intersection(a.geom,b.geom))=1) LIMIT 1";
          else
            intersects =
                "SELECT 1 FROM geometry_segments a JOIN geometry_parts b ON a.import_id=b.import_id"
                    + " AND a.id=b.id AND a.geom && b.bounds WHERE a.import_id=? AND a.id=? AND"
                    + " a.part=? AND b.part=? AND (ST_Relate(a.geom,b.geom,'T********') OR"
                    + " ST_Dimension(ST_Intersection(a.geom,ST_Boundary(b.geom)))=1) LIMIT 1";
          try (PreparedStatement p = connection.prepareStatement(intersects)) {
            p.setString(1, job);
            p.setString(2, id);
            p.setInt(3, ad ? a : b);
            p.setInt(4, ad ? b : a);
            try (ResultSet r = p.executeQuery()) {
              if (r.next()) {
                if (tolerant == null)
                  throw new Failure(
                      "INVALID_GEOMETRY", "MultiPolygon parts overlap across disk boundary", id);
                tolerant.warn(
                    "MULTIPOLYGON_OVERLAP",
                    id,
                    "MultiPolygon parts "
                        + a
                        + " and "
                        + b
                        + " overlap; each part is kept as an obstacle");
                continue;
              }
            }
          }
          if (partContains(id, a, ad, ag, shellProbe(id, b, bd, bg))
              || partContains(id, b, bd, bg, shellProbe(id, a, ad, ag))) {
            if (tolerant == null)
              throw new Failure(
                  "INVALID_GEOMETRY", "MultiPolygon parts are nested without a hole", id);
            tolerant.warn(
                "MULTIPOLYGON_OVERLAP",
                id,
                "MultiPolygon parts "
                    + a
                    + " and "
                    + b
                    + " are nested; each part is kept as an obstacle");
          }
        }
      }
    }
  }

  /**
   * tolerant!=null (input.invalid_geometry=repair): overlapping/nested MultiPolygon parts become
   * warnings; as obstacles their union is what matters.
   */
  void validateParts() throws SQLException {
    validateParts(null);
  }

  void validateParts(Dataset tolerant) throws SQLException {
    // End of import: fresh statistics. A new import_id is unknown to the old statistics (estimated
    // 1 row), and the planner then
    // joined every part with every other part of the import (nested loop, O(parts^2): 13 s for 9
    // 335 parts, hours at 1 GiB).
    flush();
    try (Statement stats = connection.createStatement()) {
      stats.execute("ANALYZE geometry_parts");
      stats.execute("ANALYZE input_features");
    }
    validateDiskPairs(tolerant);
    // b.part>0 keeps the build side of the join to multi-part features only.
    try (PreparedStatement p =
        connection.prepareStatement(
            "SELECT a.id FROM geometry_parts a JOIN geometry_parts b ON a.import_id=b.import_id AND"
                + " a.id=b.id AND a.part<b.part AND a.bounds && b.bounds WHERE a.import_id=? AND"
                + " b.part>0 AND NOT a.disk AND NOT b.disk AND ST_Dimension(a.geom)=2 AND"
                + " (ST_Relate(a.geom,b.geom,'T********') OR (NOT"
                + " ST_IsEmpty(ST_Intersection(ST_Boundary(a.geom),ST_Boundary(b.geom))) AND"
                + " ST_Dimension(ST_Intersection(ST_Boundary(a.geom),ST_Boundary(b.geom)))=1))"
                + " ORDER BY a.id"
                + (tolerant == null ? " LIMIT 1" : ""))) {
      p.setString(1, job);
      try (ResultSet r = p.executeQuery()) {
        if (tolerant == null && r.next())
          throw new Failure("INVALID_GEOMETRY", "MultiPolygon parts overlap", r.getString(1));
        Set<String> seen = new TreeSet<>();
        while (tolerant != null && r.next())
          if (seen.add(r.getString(1)))
            tolerant.warn(
                "MULTIPOLYGON_OVERLAP",
                r.getString(1),
                "MultiPolygon parts overlap; each part is kept as an obstacle");
      }
    }
  }

  @Override
  public void close() {
    try {
      delete(job);
      connection.close();
    } catch (SQLException e) {
      throw new Failure("INFRASTRUCTURE_ERROR", e.getMessage());
    }
  }
}
