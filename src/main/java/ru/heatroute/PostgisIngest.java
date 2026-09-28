package ru.heatroute;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.operation.valid.IsValidOp;

/** Two-pass per-feature spool; polygon rings are never held in memory above the part budget. */
final class PostgisIngest {
  PostgisIngest() {
    this(false);
  }

  PostgisIngest(boolean current) {
    this(new Ingest());
    small.current = current;
  }

  /**
   * Shares the in-memory reader's policies (profile, invalid_geometry, unknown_restriction) so both
   * paths accept the same inputs.
   */
  PostgisIngest(Ingest policies) {
    small = policies;
  }

  private final Ingest small;
  private Dataset data;
  private PostgisFeatureStore store;

  Dataset read(Path input) throws IOException {
    Path dir = Files.createTempDirectory(Path.of("work").toAbsolutePath(), "ingest-");
    data = new Dataset();
    try {
      store = new PostgisFeatureStore();
      data.store = store;
      String type = null, crs = null;
      boolean features = false;
      try (JsonParser p = Json.M.getFactory().createParser(input.toFile())) {
        if (p.nextToken() != JsonToken.START_OBJECT)
          throw new Failure("INVALID_JSON", "Expected FeatureCollection");
        while (p.nextToken() != JsonToken.END_OBJECT) {
          String field = p.currentName();
          p.nextToken();
          if (field.equals("features")) {
            if (p.currentToken() != JsonToken.START_ARRAY)
              throw new Failure("INVALID_JSON", "features must be array");
            while (p.nextToken() != JsonToken.END_ARRAY) feature(p, dir);
            features = true;
          } else if (field.equals("type")) type = p.getValueAsString();
          else if (field.equals("crs")) {
            JsonNode n = Json.M.readTree(p);
            crs = n.path("properties").path("name").asText();
          } else p.skipChildren();
        }
        if (p.nextToken() != null) throw new Failure("INVALID_JSON", "Trailing content");
      }
      if (!"FeatureCollection".equals(type) || !features)
        throw new Failure("INVALID_JSON", "FeatureCollection required");
      if (crs != null
          && !Set.of(
                  "CRS84",
                  "EPSG:4326",
                  "urn:ogc:def:crs:OGC:1.3:CRS84",
                  "urn:ogc:def:crs:EPSG::4326")
              .contains(crs)) throw new Failure("UNSUPPORTED_CRS", crs);
      small.normalize(data);
      store.validateParts(small.repair ? data : null);
      return data;
    } catch (Failure e) {
      if (store != null) store.close();
      throw e;
    } catch (Exception e) {
      if (store != null) store.close();
      if (e instanceof JsonProcessingException) throw new Failure("INVALID_JSON", e.getMessage());
      throw new IOException(e);
    } finally {
      try (java.util.stream.Stream<Path> paths = Files.list(dir)) {
        for (Path p : (Iterable<Path>) paths::iterator) Files.deleteIfExists(p);
      }
      Files.deleteIfExists(dir);
    }
  }

  private void feature(JsonParser parser, Path dir) throws Exception {
    if (Thread.currentThread().isInterrupted()) throw new Failure("CANCELLED", "Import cancelled");
    if (parser.currentToken() != JsonToken.START_OBJECT)
      throw new Failure("INVALID_JSON", "Expected feature");
    ObjectNode props = null;
    String type = null;
    boolean geometry = false;
    JsonNode featureId = null;
    Path spool = dir.resolve("geometry.json");
    while (parser.nextToken() != JsonToken.END_OBJECT) {
      String name = parser.currentName();
      parser.nextToken();
      if (name.equals("properties")) {
        JsonNode p = Json.M.readTree(parser);
        if (!p.isObject()) throw new Failure("INVALID_JSON", "Properties object required");
        props = (ObjectNode) p;
      } else if (name.equals("type")) type = parser.getValueAsString();
      else if (name.equals("id")) featureId = Json.M.readTree(parser);
      else if (name.equals("geometry")) {
        try (JsonGenerator g =
            Json.M.getFactory().createGenerator(spool.toFile(), JsonEncoding.UTF8)) {
          g.copyCurrentStructure(parser);
        }
        geometry = true;
      } else parser.skipChildren();
    }
    if (!"Feature".equals(type) || props == null)
      throw new Failure("INVALID_JSON", "Incomplete feature");
    if ((props.get("id") == null || props.get("id").isNull())
        && featureId != null
        && !featureId.isNull()) {
      props.set("id", featureId);
      data.warn(
          "FEATURE_LEVEL_ID", Ingest.id(featureId), "id taken from the GeoJSON Feature member");
    }
    String id = Ingest.id(props.get("id")), object = props.path("object_type").asText();
    boolean free = Ingest.UNREFERENCED.contains(object);
    // Only the geometry type is read here: a polygon of any size is streamed from the spool below,
    // never held as a JSON tree.
    String geometryType = geometry ? geometryType(spool) : null;
    if (!geometry && !(free && small.repair))
      throw new Failure("INVALID_JSON", "Incomplete feature");
    try {
      store.feature(id, object, Json.M.writeValueAsString(props));
    } catch (Failure e) {
      if (!e.code.equals("DUPLICATE_ID")
          || !small.current
          || !free && data.features.containsKey(id)) throw e;
      String alias = Ingest.unused(data, id + "~dup"),
          old = data.features.containsKey(id) ? data.features.get(id).type : store.objectType(id);
      store.feature(alias, object, Json.M.writeValueAsString(props));
      // Same wording as Ingest.duplicate, so both paths report identical audits.
      data.warn(
          "DUPLICATE_ID",
          id,
          "normalized id shared by "
              + old
              + " and "
              + object
              + "; the "
              + (free ? object : old)
              + " is kept under internal id "
              + alias);
      if (free) id = alias;
    }
    data.sourceFeatureCount++;
    if (!free) {
      ObjectNode f = Json.M.createObjectNode();
      f.put("type", "Feature");
      f.set("properties", props);
      f.set("geometry", shape(spool, geometry));
      small.add(data, f);
      return;
    }
    String kind = object.equals("oks_existing") ? "building" : small.kind(data, id, props);
    if (kind == null) {
      data.ignoredIds.add(id);
      return;
    }
    if (object.equals("restriction")) props.put("restriction_type", kind);
    boolean lines = Set.of("LineString", "MultiLineString").contains(geometryType),
        areas = Set.of("Polygon", "MultiPolygon").contains(geometryType),
        linear = Ingest.LINEAR.contains(kind);
    // Anything the streaming path does not stream (null/Point/GeometryCollection, gas/power/heat
    // polygons, oks lines, heat_network restrictions) goes through the tolerant in-memory reader,
    // so both paths accept the same geometry types.
    if (small.repair
        && (!lines && !areas
            || linear && areas
            || kind.equals("building") && lines
            || kind.equals("heat_network"))) {
      ObjectNode f = Json.M.createObjectNode();
      f.put("type", "Feature");
      f.set("properties", props);
      f.set("geometry", shape(spool, geometry));
      small.add(data, f);
      return;
    }
    if (lines && !kind.equals("building")) {
      List<String> notes = small.repair ? new ArrayList<>() : null;
      Geometry g = Geo.read(shape(spool, geometry), id, notes);
      if (notes != null) for (String n : notes) data.warn("INVALID_GEOMETRY", id, n);
      if (g == null) {
        data.ignoredIds.add(id);
        data.warn("RESTRICTION_SKIPPED", id, "No usable " + kind + " geometry; feature ignored");
        return;
      }
      data.coordinateCount += g.getNumPoints();
      double width = kind.equals("gas_pipeline") ? .4 : kind.equals("power_cable") ? .2 : 0;
      for (int i = 0; i < g.getNumGeometries(); i++) {
        Geometry part = g.getGeometryN(i);
        store.part(
            id, i, kind, width, part, Geo.fingerprint(part), part.getEnvelopeInternal(), false);
      }
      return;
    }
    if (!areas) throw new Failure("UNSUPPORTED_GEOMETRY_FOR_RESTRICTION", kind, id);
    try (JsonParser p = Json.M.getFactory().createParser(spool.toFile())) {
      p.nextToken();
      while (p.nextToken() != JsonToken.END_OBJECT) {
        String name = p.currentName();
        p.nextToken();
        if (!name.equals("coordinates")) {
          p.skipChildren();
          continue;
        }
        if (geometryType.equals("Polygon")) polygon(p, id, 0, kind, dir);
        else {
          int part = 0;
          while (p.nextToken() != JsonToken.END_ARRAY) polygon(p, id, part++, kind, dir);
          if (part == 0) throw new Failure("INVALID_GEOMETRY", "Empty MultiPolygon", id);
        }
      }
    }
  }

  private static JsonNode shape(Path spool, boolean geometry) throws IOException {
    return geometry ? Json.M.readTree(spool.toFile()) : null;
  }

  /**
   * The spooled geometry's "type" member, read by streaming (coordinates are skipped, not parsed
   * into a tree).
   */
  private static String geometryType(Path spool) throws IOException {
    try (JsonParser p = Json.M.getFactory().createParser(spool.toFile())) {
      if (p.nextToken() != JsonToken.START_OBJECT) return null;
      while (p.nextToken() == JsonToken.FIELD_NAME) {
        String name = p.currentName();
        p.nextToken();
        if (name.equals("type"))
          return p.currentToken().isScalarValue() ? p.getValueAsString() : null;
        p.skipChildren();
      }
      return null;
    }
  }

  private void polygon(JsonParser p, String id, int part, String kind, Path dir) throws Exception {
    if (p.currentToken() != JsonToken.START_ARRAY)
      throw new Failure("INVALID_GEOMETRY", "Polygon coordinates must be array", id);
    Path binary = dir.resolve("polygon.bin");
    int ring = 0;
    long count = 0;
    Envelope envelope = new Envelope();
    long edgeHash = 0;
    try (RandomAccessFile f = new RandomAccessFile(binary.toFile(), "rw")) {
      f.setLength(0);
      DataOutputStream buffered =
          new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f.getFD()), 65536));
      while (p.nextToken() != JsonToken.END_ARRAY) {
        if (p.currentToken() != JsonToken.START_ARRAY)
          throw new Failure("INVALID_GEOMETRY", "Ring array required", id);
        buffered.flush();
        f.writeInt(ring);
        long sizeAt = f.getFilePointer();
        f.writeLong(0);
        long size = 0;
        Coordinate first = null, prev = null;
        while (p.nextToken() != JsonToken.END_ARRAY) {
          if (p.currentToken() != JsonToken.START_ARRAY)
            throw new Failure("INVALID_GEOMETRY", "Position array required", id);
          if (p.nextToken() == null || !p.currentToken().isNumeric())
            throw new Failure("INVALID_GEOMETRY", "Longitude required", id);
          double lon = p.getDoubleValue();
          if (p.nextToken() == null || !p.currentToken().isNumeric())
            throw new Failure("INVALID_GEOMETRY", "Latitude required", id);
          double lat = p.getDoubleValue();
          while (p.nextToken() != JsonToken.END_ARRAY) p.skipChildren();
          if (!Double.isFinite(lon)
              || !Double.isFinite(lat)
              || Math.abs(lon) > 180
              || Math.abs(lat) > 90)
            throw new Failure("INVALID_GEOMETRY", "Invalid WGS84 position", id);
          Coordinate c = Geo.xy(lon, lat);
          data.coordinateCount++;
          if (prev != null && prev.equals2D(c)) continue;
          buffered.writeDouble(c.x);
          buffered.writeDouble(c.y);
          envelope.expandToInclude(c);
          if (first == null) first = c;
          if (prev != null) {
            long a = Double.doubleToLongBits(prev.x) * 31 + Double.doubleToLongBits(prev.y),
                b = Double.doubleToLongBits(c.x) * 31 + Double.doubleToLongBits(c.y);
            edgeHash += Long.rotateLeft(Math.min(a, b), 17) ^ Math.max(a, b);
          }
          prev = c;
          size++;
        }
        if (size < 4 || !first.equals2D(prev))
          throw new Failure("INVALID_GEOMETRY", "Unclosed or short ring", id);
        buffered.flush();
        long end = f.getFilePointer();
        f.seek(sizeAt);
        f.writeLong(size);
        f.seek(end);
        ring++;
        count += size;
      }
    }
    if (ring == 0) throw new Failure("INVALID_GEOMETRY", "Empty polygon", id);
    data.holeCount += ring - 1;
    if (kind.equals("building")) data.buildingParts++;
    if (count <= 65536) {
      List<LinearRing> rings = new ArrayList<>();
      try (DataInputStream in =
          new DataInputStream(new BufferedInputStream(Files.newInputStream(binary)))) {
        for (int r = 0; r < ring; r++) {
          in.readInt();
          int size = (int) in.readLong();
          Coordinate[] points = new Coordinate[size];
          for (int i = 0; i < size; i++)
            points[i] = new Coordinate(in.readDouble(), in.readDouble());
          rings.add(Geo.GF.createLinearRing(points));
        }
      }
      Polygon polygon =
          Geo.GF.createPolygon(
              rings.get(0), rings.subList(1, rings.size()).toArray(new LinearRing[0]));
      IsValidOp valid = new IsValidOp(polygon);
      Geometry stored = polygon;
      if (!valid.isValid() && !small.repair)
        throw new Failure("INVALID_GEOMETRY", valid.getValidationError().toString(), id);
      if (!valid.isValid()) {
        data.warn(
            "INVALID_GEOMETRY",
            id,
            "Invalid geometry repaired with GeometryFixer: " + valid.getValidationError());
        stored = org.locationtech.jts.geom.util.GeometryFixer.fix(polygon);
        if (stored.isEmpty()) {
          data.warn(
              "RESTRICTION_SKIPPED",
              id,
              "Polygon part " + part + " is empty after repair; ignored");
          return;
        }
      }
      store.part(
          id, part, kind, 0, stored, Geo.fingerprint(stored), stored.getEnvelopeInternal(), false);
    } else {
      store.segments(id, part, binary);
      store.validateDisk(id, part);
      store.part(
          id,
          part,
          kind,
          0,
          null,
          "segments:" + Long.toUnsignedString(edgeHash, 16) + ":" + count + ":" + ring,
          envelope,
          true);
    }
  }
}
