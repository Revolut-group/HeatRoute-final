package ru.heatroute;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.math.*;
import java.nio.file.*;
import java.util.*;
import org.locationtech.jts.geom.*;

/** Streaming FeatureCollection reader. Each small feature is validated before indexing. */
public final class Ingest {
  boolean current, repair;
  String unknownRestriction = "error";
  double snap = .1;
  static final Set<String>
      TYPES =
          Set.of(
              "source",
              "heat_network",
              "heat_chamber",
              "oks_connection_point",
              "oks_future",
              "oks_existing",
              "restriction"),
      LINEAR = Set.of("gas_pipeline", "power_cable", "heat_network"),
      UNREFERENCED = Set.of("restriction", "oks_existing");

  /**
   * Without rules the reader is strict (legacy contract). With rules, input.invalid_geometry=repair
   * and input.unknown_restriction=skip|forbid_1m make restriction defects warnings; the current
   * profile also tolerates attribute defects (DN, flow_tph, ids, MultiLineString networks).
   */
  public Dataset read(Path file, Rules rules) throws IOException {
    current = rules.current();
    repair = rules.text("input.invalid_geometry").equals("repair");
    unknownRestriction = rules.text("input.unknown_restriction");
    snap = rules.number("geometry.topology_snap_m");
    return read(file);
  }

  public Dataset read(Path file) throws IOException {
    if (Files.size(file) > 64L * 1024 * 1024
        || Boolean.parseBoolean(System.getenv("HEATROUTE_POSTGIS"))) {
      Files.createDirectories(Path.of("work"));
      return new PostgisIngest(this).read(file);
    }
    Dataset d = new Dataset();
    String rootType = null, crs = null;
    boolean found = false;
    try (JsonParser p = Json.M.getFactory().createParser(file.toFile())) {
      if (p.nextToken() != JsonToken.START_OBJECT)
        throw new Failure("INVALID_JSON", "Expected FeatureCollection object");
      while (p.nextToken() != JsonToken.END_OBJECT) {
        String field = p.currentName();
        p.nextToken();
        if ("features".equals(field)) {
          if (p.currentToken() != JsonToken.START_ARRAY)
            throw new Failure("INVALID_JSON", "features must be an array");
          while (p.nextToken() != JsonToken.END_ARRAY) {
            JsonNode n = Json.M.readTree(p);
            add(d, n);
          }
          found = true;
        } else if ("type".equals(field)) rootType = p.getValueAsString();
        else if ("crs".equals(field)) {
          JsonNode c = Json.M.readTree(p);
          crs = c.path("properties").path("name").asText();
        } else p.skipChildren();
      }
      if (p.nextToken() != null) throw new Failure("INVALID_JSON", "Trailing JSON data");
    } catch (JsonProcessingException e) {
      throw new Failure("INVALID_JSON", e.getOriginalMessage());
    }
    if (!"FeatureCollection".equals(rootType) || !found)
      throw new Failure("INVALID_JSON", "FeatureCollection and features required");
    if (crs != null
        && !Set.of(
                "urn:ogc:def:crs:OGC:1.3:CRS84", "CRS84", "EPSG:4326", "urn:ogc:def:crs:EPSG::4326")
            .contains(crs)) throw new Failure("UNSUPPORTED_CRS", crs);
    normalize(d);
    return d;
  }

  public static String id(JsonNode n) {
    if (n != null && n.isNumber()) return n.decimalValue().stripTrailingZeros().toPlainString();
    if (n != null && n.isTextual() && !n.textValue().trim().isEmpty()) return n.textValue();
    throw new Failure("INVALID_ATTRIBUTE", "ID must be a nonempty string or number");
  }

  void add(Dataset d, JsonNode f) {
    if (!f.isObject()
        || !"Feature".equals(f.path("type").asText())
        || !f.path("properties").isObject())
      throw new Failure("INVALID_JSON", "Expected Feature with properties");
    ObjectNode p = ((ObjectNode) f.get("properties")).deepCopy();
    if ((p.get("id") == null || p.get("id").isNull()) && f.hasNonNull("id")) {
      p.set("id", f.get("id"));
      d.warn("FEATURE_LEVEL_ID", id(f.get("id")), "id taken from the GeoJSON Feature member");
    }
    String id = id(p.get("id")), type = p.path("object_type").asText();
    if (!TYPES.contains(type)) {
      if (!current) throw new Failure("UNSUPPORTED_OBJECT_TYPE", type, id);
      d.ignoredIds.add(id);
      d.warn("UNSUPPORTED_OBJECT_TYPE", id, "object_type '" + type + "' ignored");
      return;
    }
    if (d.features.containsKey(id)) id = duplicate(d, id, type);
    String kind = type.equals("oks_existing") ? "building" : null;
    if (type.equals("restriction")) {
      kind = kind(d, id, p);
      if (kind == null) {
        d.ignoredIds.add(id);
        return;
      }
      p.put("restriction_type", kind);
      if (kind.equals("heat_network")) {
        d.warn(
            "RESTRICTION_HEAT_NETWORK",
            id,
            "restriction_type heat_network is used only as an existing-network obstacle (special"
                + " crossing, no tie-in)");
        p.put("diameter", dn(d, id, p.get("diameter"), 300));
      }
    }
    Geometry g;
    if (repair && kind != null) {
      List<String> notes = new ArrayList<>();
      g = Geo.read(f.get("geometry"), id, notes);
      for (String n : notes) d.warn("INVALID_GEOMETRY", id, n);
      if (g != null) g = obstacleGeometry(d, id, kind, g);
      if (g == null) {
        d.ignoredIds.add(id);
        d.warn("RESTRICTION_SKIPPED", id, "No usable " + kind + " geometry; feature ignored");
        return;
      }
    } else g = Geo.read(f.get("geometry"), id);
    if (Set.of("source", "heat_chamber", "oks_connection_point").contains(type)
        && !(g instanceof Point)) throw new Failure("INVALID_GEOMETRY", "Point required", id);
    if (type.equals("heat_network")
        && !(g instanceof LineString)
        && !(current && g instanceof MultiLineString))
      throw new Failure("INVALID_GEOMETRY", "LineString required", id);
    if ((type.equals("oks_future") || type.equals("oks_existing")) && g.getDimension() != 2)
      throw new Failure("INVALID_GEOMETRY", "Polygon required", id);
    if (current && type.equals("oks_connection_point") && !p.has("flow_tph"))
      d.warn("INVALID_FLOW", id, "flow_tph missing; point is reported unconnected");
    if (current && type.equals("oks_connection_point") && p.has("flow_tph")) {
      BigDecimal flow = decimal(p.get("flow_tph"));
      if (flow == null || flow.signum() < 0) {
        d.warn(
            "INVALID_FLOW",
            id,
            "flow_tph "
                + p.get("flow_tph")
                + " is not a non-negative number; point is reported unconnected");
        p.remove("flow_tph");
      } else if (!p.get("flow_tph").isNumber()) {
        d.warn(
            "FLOW_PARSED",
            id,
            "flow_tph string " + p.get("flow_tph") + " read as " + flow.toPlainString());
        p.put("flow_tph", flow);
      }
    }
    if ((!current || type.equals("oks_connection_point"))
        && p.has("flow_tph")
        && (!p.get("flow_tph").isNumber() || p.get("flow_tph").decimalValue().signum() < 0))
      throw new Failure("INVALID_ATTRIBUTE", "Invalid flow_tph", id);
    if (current && type.equals("heat_network")) {
      if (p.hasNonNull("diameter")
          && decimal(p.get("diameter")) != null
          && decimal(p.get("diameter")).signum() > 0)
        p.put("diameter", dn(d, id, p.get("diameter"), 0));
      else {
        d.warn(
            "DN_MISSING",
            id,
            "diameter " + p.get("diameter") + " missing or not a positive number");
        p.remove("diameter");
      }
    } else if (type.equals("heat_network") || !current && p.has("diameter")) {
      if (!p.path("diameter").isIntegralNumber())
        throw new Failure("INVALID_ATTRIBUTE", "Integer diameter required", id);
      Catalog.pipe(p.get("diameter").intValue());
    }
    if (!current) {
      if (p.has("oks_id")) id(p.get("oks_id"));
      if (p.has("upstream_object_id")) id(p.get("upstream_object_id"));
    }
    if (type.equals("heat_network") && g instanceof MultiLineString) {
      List<LineString> parts = new ArrayList<>();
      for (int i = 0; i < g.getNumGeometries(); i++)
        if (g.getGeometryN(i).getLength() > 0) parts.add((LineString) g.getGeometryN(i));
      if (parts.isEmpty()) throw new Failure("INVALID_GEOMETRY", "LineString required", id);
      d.warn(
          "MULTILINE_NETWORK_SPLIT",
          id,
          "MultiLineString read as "
              + parts.size()
              + " LineString part(s)"
              + (parts.size() > 1
                  ? "; parts after the first use internal ids "
                      + id
                      + "~partN, never written to output"
                  : ""));
      for (int i = 0; i < parts.size(); i++) {
        String key = i == 0 ? id : unused(d, id + "~part" + i);
        d.features.put(key, new Dataset.Feature(key, type, parts.get(i), p.deepCopy()));
        d.coordinateCount += parts.get(i).getNumPoints();
      }
      return;
    }
    d.features.put(id, new Dataset.Feature(id, type, g, p));
    d.coordinateCount += g.getNumPoints();
  }

  static BigDecimal decimal(JsonNode n) {
    if (n != null && n.isNumber()) return n.decimalValue();
    if (n != null && n.isTextual())
      try {
        return new BigDecimal(n.textValue().trim());
      } catch (NumberFormatException e) {
        return null;
      }
    return null;
  }

  /**
   * Catalog DN used for clearance/gabarit: the nearest catalog DN not below the value (DN1400 above
   * the table); fallback when unreadable.
   */
  static int dn(Dataset d, String id, JsonNode raw, int fallback) {
    BigDecimal v = decimal(raw);
    if (v == null || v.signum() <= 0) {
      if (fallback > 0)
        d.warn("DN_ASSUMED", id, "diameter " + raw + " unreadable; DN" + fallback + " assumed");
      return fallback;
    }
    int dn = Catalog.PIPES.get(Catalog.PIPES.size() - 1).dn;
    for (Catalog.Pipe pipe : Catalog.PIPES)
      if (v.compareTo(BigDecimal.valueOf(pipe.dn)) <= 0) {
        dn = pipe.dn;
        break;
      }
    String text = raw.isNumber() ? v.toPlainString() : raw.toString();
    if (v.compareTo(BigDecimal.valueOf(dn)) != 0)
      d.warn(
          "DN_NORMALIZED",
          id,
          "diameter " + text + " is not in Table 1; DN" + dn + " used for clearance and gabarit");
    else if (!raw.isIntegralNumber())
      d.warn(
          "DN_NORMALIZED",
          id,
          "diameter "
              + text
              + " ("
              + (raw.isNumber() ? "non-integer number" : "string")
              + ") read as DN"
              + dn);
    return dn;
  }

  /**
   * Same normalized id twice: tolerated (current profile) only when one of the two is never
   * referenced by the output (restriction/oks_existing); that one moves to an internal id.
   */
  String duplicate(Dataset d, String id, String type) {
    Dataset.Feature old = d.features.get(id);
    boolean free = UNREFERENCED.contains(type);
    if (!current || !free && !UNREFERENCED.contains(old.type))
      throw new Failure("DUPLICATE_ID", "Duplicate normalized ID", id);
    String alias = unused(d, id + "~dup");
    d.warn(
        "DUPLICATE_ID",
        id,
        "normalized id shared by "
            + old.type
            + " and "
            + type
            + "; the "
            + (free ? type : old.type)
            + " is kept under internal id "
            + alias);
    if (free) return alias;
    d.features.remove(id);
    d.features.put(alias, new Dataset.Feature(alias, old.type, old.geometry, old.properties));
    return id;
  }

  static String unused(Dataset d, String base) {
    String id = base;
    for (int n = 2; d.containsId(id); n++) id = base + n;
    return id;
  }

  /**
   * restriction_type after trim/lowercase; unknown types follow input.unknown_restriction (null =
   * skip).
   */
  String kind(Dataset d, String id, ObjectNode p) {
    JsonNode node = p.get("restriction_type");
    String raw = node == null || node.isNull() ? "" : node.asText(),
        r = raw.trim().toLowerCase(Locale.ROOT);
    String known = known(r);
    if (known != null && !r.equals(raw))
      d.warn(
          "RESTRICTION_TYPE_NORMALIZED", id, "restriction_type '" + raw + "' read as '" + r + "'");
    if (known != null) return known;
    switch (unknownRestriction) {
      case "skip":
        d.warn(
            "UNSUPPORTED_RESTRICTION",
            id,
            "restriction_type '"
                + raw
                + "' is not in Table 2; feature ignored (input.unknown_restriction=skip)");
        return null;
      case "forbid_1m":
        d.warn(
            "UNSUPPORTED_RESTRICTION",
            id,
            "restriction_type '"
                + raw
                + "' is not in Table 2; treated as prohibited_site with 1 m clearance");
        return "prohibited_site";
      default:
        throw new Failure("UNSUPPORTED_RESTRICTION", raw, id);
    }
  }

  static String known(String r) {
    switch (r) {
      case "oks":
      case "building":
      case "oks_existing":
        return "building";
      case "social_area":
      case "school":
      case "kindergarten":
        return "social_area";
      case "water":
      case "park":
      case "prohibited_site":
      case "road":
      case "tram_tracks":
      case "gas_pipeline":
      case "power_cable":
      case "railway":
      case "metro":
      case "heat_network":
        return r;
      default:
        return null;
    }
  }

  /**
   * Tolerant obstacle geometry: collections are exploded, points dropped, gas/power/heat polygons
   * replaced by their boundary lines, non-areal oks parts dropped.
   */
  static Geometry obstacleGeometry(Dataset d, String id, String kind, Geometry g) {
    List<Geometry> parts = new ArrayList<>(), keep = new ArrayList<>();
    explode(g, parts);
    boolean linear = LINEAR.contains(kind), boundary = false, dropped = false;
    for (Geometry part : parts) {
      if (part.isEmpty()) continue;
      if (part.getDimension() == 0 || kind.equals("building") && part.getDimension() != 2) {
        dropped = true;
        continue;
      }
      if (linear && part.getDimension() == 2) {
        boundary = true;
        explode(part.getBoundary(), keep);
      } else keep.add(part);
    }
    if (g.getClass() == GeometryCollection.class)
      d.warn(
          "GEOMETRY_COLLECTION", id, "GeometryCollection exploded into " + parts.size() + " parts");
    if (dropped)
      d.warn(
          "GEOMETRY_PART_DROPPED",
          id,
          (kind.equals("building") ? "non-areal" : "point")
              + " parts cannot form a "
              + kind
              + " restriction and were ignored");
    if (boundary)
      d.warn(
          "LINEAR_RESTRICTION_POLYGON",
          id,
          kind + " given as a polygon; its boundary lines are used as the " + kind + " axis");
    return keep.isEmpty() ? null : Geo.GF.buildGeometry(keep);
  }

  private static void explode(Geometry g, List<Geometry> out) {
    if (g instanceof GeometryCollection)
      for (int i = 0; i < g.getNumGeometries(); i++) explode(g.getGeometryN(i), out);
    else out.add(g);
  }

  void normalize(Dataset d) {
    Map<String, Dataset.Demand> demands = new TreeMap<>();
    if (current) assumeMissingDn(d);
    for (Dataset.Feature f : d.features.values()) {
      switch (f.type) {
        case "source":
          d.sources.add(f);
          break;
        case "heat_network":
          d.networks.add(f);
          obstacles(d, f, "heat_network", Catalog.pipe(f.dn()).width);
          break;
        case "heat_chamber":
          d.chambers.add(f);
          break;
        case "oks_existing":
          obstacles(d, f, "building", 0);
          break;
        case "restriction":
          String kind = restriction(f);
          obstacles(d, f, kind, kind.equals("heat_network") ? Catalog.pipe(f.dn()).width : 0);
          break;
        case "oks_connection_point":
          String demandId = f.id;
          BigDecimal flow = f.flow();
          if (!current && f.properties.has("oks_id")) {
            demandId = id(f.properties.get("oks_id"));
            Dataset.Feature host = d.features.get(demandId);
            if (host == null || !host.type.equals("oks_future"))
              throw new Failure("DANGLING_REFERENCE", "oks_id must reference oks_future", f.id);
            if (flow != null && host.flow() != null && flow.compareTo(host.flow()) != 0)
              throw new Failure("CONFLICTING_DEMAND", "Point and OKS flows differ", f.id);
            flow = host.flow() == null ? flow : host.flow();
          }
          if (flow == null && current) {
            Dataset.Demand invalid = new Dataset.Demand(f.id, BigDecimal.ZERO);
            invalid.terminals.add(f);
            d.invalidDemands.add(invalid);
            break;
          }
          if (flow == null) throw new Failure("INVALID_ATTRIBUTE", "flow_tph is required", f.id);
          Dataset.Demand demand = demands.get(demandId);
          if (demand == null) {
            demand = new Dataset.Demand(demandId, flow);
            demands.put(demandId, demand);
          } else if (flow.compareTo(demand.flow) != 0)
            throw new Failure(
                "CONFLICTING_DEMAND", "Alternative points have conflicting flow", f.id);
          demand.terminals.add(f);
          break;
        default:
          break;
      }
    }
    d.demands.addAll(demands.values());
    d.reduced = d.networks.stream().allMatch(f -> f.flow() == null);
    for (Dataset.Feature f : d.features.values())
      if (!current && f.properties.has("upstream_object_id")) {
        String ref = id(f.properties.get("upstream_object_id"));
        Dataset.Feature next = d.features.get(ref);
        if (next == null || !Set.of("source", "heat_network", "heat_chamber").contains(next.type))
          throw new Failure("DANGLING_REFERENCE", "Invalid upstream reference " + ref, f.id);
        Set<String> seen = new HashSet<>();
        Dataset.Feature at = f;
        while (at.properties.has("upstream_object_id")) {
          if (!seen.add(at.id))
            throw new Failure("UPSTREAM_CYCLE", "Cycle in upstream references", f.id);
          at = d.features.get(id(at.properties.get("upstream_object_id")));
          if (at == null) break;
        }
      }
    d.index.build();
  }

  static String restriction(Dataset.Feature f) {
    String r = known(f.properties.path("restriction_type").asText());
    if (r == null)
      throw new Failure(
          "UNSUPPORTED_RESTRICTION", f.properties.path("restriction_type").asText(), f.id);
    return r;
  }

  /**
   * heat_network without a readable DN: largest DN of lines sharing an end within topology_snap_m
   * (propagated), otherwise DN300.
   */
  void assumeMissingDn(Dataset d) {
    List<Dataset.Feature> missing = new ArrayList<>(), known = new ArrayList<>();
    for (Dataset.Feature f : d.features.values())
      if (f.type.equals("heat_network")) (f.properties.has("diameter") ? known : missing).add(f);
    for (boolean progress = true; progress && !missing.isEmpty(); ) {
      progress = false;
      for (Iterator<Dataset.Feature> it = missing.iterator(); it.hasNext(); ) {
        Dataset.Feature f = it.next();
        int dn = 0;
        for (Dataset.Feature o : known)
          if (touch(f.geometry, o.geometry) || touch(o.geometry, f.geometry))
            dn = Math.max(dn, o.dn());
        if (dn > 0) {
          f.properties.put("diameter", dn);
          d.warn("DN_ASSUMED", f.id, "DN" + dn + " taken from adjacent existing lines");
          known.add(f);
          it.remove();
          progress = true;
        }
      }
    }
    for (Dataset.Feature f : missing) {
      f.properties.put("diameter", 300);
      d.warn("DN_ASSUMED", f.id, "no adjacent line with a known DN; DN300 assumed");
    }
  }

  private boolean touch(Geometry a, Geometry b) {
    LineString l = (LineString) a;
    return b.distance(l.getStartPoint()) <= snap || b.distance(l.getEndPoint()) <= snap;
  }

  private static void obstacles(Dataset d, Dataset.Feature f, String type, double width) {
    boolean linear = LINEAR.contains(type);
    if (linear && f.geometry.getDimension() != 1
        || !linear && f.geometry.getDimension() < 1
        || type.equals("building") && f.geometry.getDimension() != 2)
      throw new Failure("UNSUPPORTED_GEOMETRY_FOR_RESTRICTION", type, f.id);
    if (type.equals("gas_pipeline")) width = .4;
    if (type.equals("power_cable")) width = .2;
    for (int i = 0; i < f.geometry.getNumGeometries(); i++) {
      Geometry g = f.geometry.getGeometryN(i);
      Dataset.Obstacle o = new Dataset.Obstacle(f.id, type, i, g, width);
      d.obstacles.add(o);
      d.index.insert(g.getEnvelopeInternal(), o);
      if (o.building()) d.buildingParts++;
      if (g instanceof Polygon) d.holeCount += ((Polygon) g).getNumInteriorRing();
    }
  }
}
