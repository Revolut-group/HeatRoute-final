package ru.heatroute;

import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.nio.file.*;
import org.locationtech.jts.geom.*;

final class Scenes {
  static final double X = 414000, Y = 6173000;

  static ObjectNode collection() {
    ObjectNode n = Json.M.createObjectNode();
    n.put("type", "FeatureCollection");
    n.putArray("features");
    return n;
  }

  static Coordinate p(double x, double y) {
    return new Coordinate(X + x, Y + y);
  }

  static ObjectNode add(ObjectNode scene, String id, String type, Geometry g) {
    ObjectNode f = ((ArrayNode) scene.get("features")).addObject();
    f.put("type", "Feature");
    f.set("geometry", Geo.json(g));
    ObjectNode props = f.putObject("properties");
    props.put("id", id);
    props.put("object_type", type);
    return props;
  }

  static ObjectNode n06() {
    ObjectNode s = collection();
    add(s, "src", "source", Geo.point(p(-50, 0)));
    ObjectNode in = add(s, "net_in", "heat_network", Geo.line(p(-50, 0), p(0, 0)));
    in.put("diameter", 150);
    in.put("flow_tph", 20);
    in.put("upstream_object_id", "src");
    ObjectNode ch = add(s, "ch", "heat_chamber", Geo.point(p(0, 0)));
    ch.put("diameter", 150);
    ch.put("upstream_object_id", "net_in");
    ObjectNode out = add(s, "net_out", "heat_network", Geo.line(p(0, 0), p(50, 0)));
    out.put("diameter", 150);
    out.put("flow_tph", 20);
    out.put("upstream_object_id", "ch");
    add(s, "p1", "oks_connection_point", Geo.point(p(0, 100))).put("flow_tph", 10);
    return s;
  }

  static Path save(Path path, ObjectNode scene) throws IOException {
    Json.write(path, scene);
    return path;
  }

  static Polygon box(double a, double b, double c, double d) {
    return Geo.GF.createPolygon(new Coordinate[] {p(a, b), p(c, b), p(c, d), p(a, d), p(a, b)});
  }
}
