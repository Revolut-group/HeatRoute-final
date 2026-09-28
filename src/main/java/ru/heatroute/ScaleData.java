package ru.heatroute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.locationtech.jts.geom.*;

/**
 * Deterministic valid scale fixtures with unique IDs: {@code <out> <MiB>} writes distinct dense
 * footprints around one point; {@code <out> <MiB> <source.geojson>} tiles a real dataset (see
 * {@link #tiles}).
 */
public final class ScaleData {
  public static void main(String[] args) throws Exception {
    long bytes = Long.parseLong(args[1]) * 1024 * 1024;
    if (args.length > 2) tiles(Path.of(args[0]), bytes, Path.of(args[2]));
    else generate(Path.of(args[0]), bytes);
  }

  /**
   * Realistic density: the source dataset is written unchanged as the centre tile; copies of its
   * restrictions (buildings, roads, rails, tram tracks, water ...) shifted by whole tile extents in
   * EPSG:32637 and renamed {@code t<i>_<j>_<id>} fill square rings around it until the byte budget
   * is reached (then space padding to exactly {@code bytes}). Ring 1 stays empty, so the centre
   * points see exactly the source obstacles and a correct large-input path must reproduce the
   * source result.
   */
  public static void tiles(Path file, long bytes, Path source) throws IOException {
    Files.createDirectories(file.toAbsolutePath().getParent());
    JsonNode base = Json.M.readTree(source.toFile());
    Envelope extent = new Envelope();
    List<JsonNode> copies = new ArrayList<>();
    for (JsonNode f : base.path("features")) {
      shift(f.path("geometry").path("coordinates"), 0, 0, extent);
      String type = f.path("properties").path("object_type").asText();
      if (type.equals("restriction") || type.equals("oks_existing")) copies.add(f);
    }
    double w = Math.ceil(extent.getWidth() / 10) * 10, h = Math.ceil(extent.getHeight() / 10) * 10;
    try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file), 1024 * 1024)) {
      ObjectNode head = Json.M.createObjectNode();
      head.put("type", "FeatureCollection");
      head.put("name", "scale-" + source.getFileName());
      if (base.has("crs")) head.set("crs", base.get("crs"));
      String h0 = Json.M.writeValueAsString(head);
      byte[] prefix =
          (h0.substring(0, h0.length() - 1) + ",\"features\":[").getBytes(StandardCharsets.UTF_8);
      out.write(prefix);
      long used = prefix.length, features = 0, copied = 0;
      boolean comma = false;
      for (JsonNode f : base.path("features")) {
        byte[] b = Json.M.writeValueAsBytes(f);
        if (comma) out.write(',');
        out.write(b);
        used += b.length + (comma ? 1 : 0);
        comma = true;
        features++;
      }
      int tiles = 0, ring = 1;
      boolean full = false;
      while (!full && copies.size() > 0) {
        ring++;
        for (int j = -ring; j <= ring && !full; j++)
          for (int i = -ring; i <= ring && !full; i++) {
            if (Math.max(Math.abs(i), Math.abs(j)) != ring) continue;
            ByteArrayOutputStream tile = new ByteArrayOutputStream(1 << 22);
            for (JsonNode f : copies) {
              ObjectNode props = ((ObjectNode) f.get("properties")).deepCopy();
              props.put("id", "t" + i + "_" + j + "_" + Ingest.id(f.path("properties").get("id")));
              ObjectNode geometry = ((ObjectNode) f.get("geometry")).deepCopy();
              geometry.set(
                  "coordinates", shift(f.path("geometry").path("coordinates"), i * w, j * h, null));
              ObjectNode n = Json.M.createObjectNode();
              n.put("type", "Feature");
              n.set("properties", props);
              n.set("geometry", geometry);
              tile.write(',');
              tile.write(Json.M.writeValueAsBytes(n));
            }
            if (used + tile.size() + 2 > bytes) {
              full = true;
              break;
            }
            tile.writeTo(out);
            used += tile.size();
            tiles++;
            features += copies.size();
            copied += copies.size();
          }
      }
      out.write(']');
      out.write('}');
      used += 2;
      byte[] spaces = new byte[8192];
      java.util.Arrays.fill(spaces, (byte) ' ');
      while (used < bytes) {
        int n = (int) Math.min(spaces.length, bytes - used);
        out.write(spaces, 0, n);
        used += n;
      }
      System.err.println(
          String.format(
              Locale.ROOT,
              "scale bytes=%d source=%s tile=%.0fx%.0f m copies=%d features=%d"
                  + " restriction_copies=%d outer_ring=%d",
              bytes,
              source,
              w,
              h,
              tiles,
              features,
              copied,
              ring));
    }
  }

  /**
   * Copies a GeoJSON coordinate array moved by (dx,dy) metres in EPSG:32637; with {@code seen} it
   * also collects the projected extent.
   */
  private static JsonNode shift(JsonNode c, double dx, double dy, Envelope seen) {
    if (!c.isArray()) return c;
    ArrayNode a = Json.M.createArrayNode();
    if (c.size() >= 2 && c.get(0).isNumber()) {
      Coordinate p = Geo.xy(c.get(0).doubleValue(), c.get(1).doubleValue());
      if (seen != null) seen.expandToInclude(p);
      Coordinate q = Geo.ll(new Coordinate(p.x + dx, p.y + dy));
      a.add(q.x);
      a.add(q.y);
      return a;
    }
    for (JsonNode x : c) a.add(shift(x, dx, dy, seen));
    return a;
  }

  public static void generate(Path file, long bytes) throws IOException {
    Files.createDirectories(file.toAbsolutePath().getParent());
    try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file), 1024 * 1024)) {
      byte[] prefix =
          "{\"type\":\"FeatureCollection\",\"features\":[".getBytes(StandardCharsets.UTF_8);
      out.write(prefix);
      long used = prefix.length;
      ObjectNode src = feature("src", "source", Geo.point(new Coordinate(413950, 6173000)));
      ObjectNode ni =
          feature(
              "net_in",
              "heat_network",
              Geo.line(new Coordinate(413950, 6173000), new Coordinate(414000, 6173000)));
      ((ObjectNode) ni.get("properties"))
          .put("diameter", 150)
          .put("flow_tph", 20)
          .put("upstream_object_id", "src");
      ObjectNode ch = feature("ch", "heat_chamber", Geo.point(new Coordinate(414000, 6173000)));
      ((ObjectNode) ch.get("properties")).put("diameter", 150).put("upstream_object_id", "net_in");
      ObjectNode no =
          feature(
              "net_out",
              "heat_network",
              Geo.line(new Coordinate(414000, 6173000), new Coordinate(414050, 6173000)));
      ((ObjectNode) no.get("properties"))
          .put("diameter", 150)
          .put("flow_tph", 20)
          .put("upstream_object_id", "ch");
      ObjectNode demand =
          feature("p1", "oks_connection_point", Geo.point(new Coordinate(414000, 6173100)));
      ((ObjectNode) demand.get("properties")).put("flow_tph", 10);
      boolean comma = false;
      for (ObjectNode n : new ObjectNode[] {src, ni, ch, no, demand}) {
        byte[] b = Json.M.writeValueAsBytes(n);
        if (comma) {
          out.write(',');
          used++;
        }
        out.write(b);
        used += b.length;
        comma = true;
      }
      int index = 0;
      while (used < bytes - 60000) {
        double cx = 416000 + (index % 250) * 30, cy = 6173000 + (index / 250) * 30;
        int points = 1024;
        Coordinate[] c = new Coordinate[points + 1];
        double rx = 7 + (index % 7) * .3, ry = 6 + (index % 11) * .2;
        for (int i = 0; i < points; i++) {
          double angle = i * Math.PI * 2 / points;
          c[i] = new Coordinate(cx + rx * Math.cos(angle), cy + ry * Math.sin(angle));
        }
        c[points] = c[0].copy();
        ObjectNode n = feature("building_" + index, "restriction", Geo.GF.createPolygon(c));
        ((ObjectNode) n.get("properties")).put("restriction_type", "oks");
        byte[] b = Json.M.writeValueAsBytes(n);
        if (used + b.length + 3 > bytes) break;
        out.write(',');
        out.write(b);
        used += 1 + b.length;
        index++;
      }
      out.write(']');
      out.write('}');
      used += 2;
      byte[] spaces = new byte[8192];
      java.util.Arrays.fill(spaces, (byte) ' ');
      while (used < bytes) {
        int n = (int) Math.min(spaces.length, bytes - used);
        out.write(spaces, 0, n);
        used += n;
      }
      System.err.println("scale bytes=" + bytes + " buildings=" + index);
    }
  }

  private static ObjectNode feature(String id, String type, Geometry g) {
    ObjectNode f = Json.M.createObjectNode();
    f.put("type", "Feature");
    f.set("geometry", Geo.json(g));
    f.putObject("properties").put("id", id).put("object_type", type);
    return f;
  }
}
