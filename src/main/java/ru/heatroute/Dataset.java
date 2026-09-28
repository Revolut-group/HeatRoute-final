package ru.heatroute;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.*;
import java.util.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.index.strtree.STRtree;

public final class Dataset implements AutoCloseable {
  FeatureStore store;
  long sourceFeatureCount;

  /**
   * PostGIS mode: the last queried window and its obstacles, per thread (the planner is parallel).
   */
  private final ThreadLocal<Object[]> cached = ThreadLocal.withInitial(() -> new Object[2]);

  /** Input ids skipped by tolerant ingest; output ids must still not collide with them. */
  final Set<String> ignoredIds = new HashSet<>();

  /**
   * Tolerated input defects, reported as input_audit.warnings (first 20 per code, 500 total) and
   * warning_counts.
   */
  public final List<Map<String, Object>> warnings = new ArrayList<>();

  public final Map<String, Integer> warningCounts = new TreeMap<>();

  /**
   * Connection points without a usable flow_tph: never routed, always reported unconnected with the
   * fixed penalty.
   */
  public final List<Demand> invalidDemands = new ArrayList<>();

  public void warn(String code, String id, String message) {
    warningCounts.merge(code, 1, Integer::sum);
    if (warnings.size() < 500 && warningCounts.get(code) <= 20) {
      Map<String, Object> w = new LinkedHashMap<>();
      w.put("code", code);
      if (id != null) w.put("feature_id", id);
      w.put("message", message);
      warnings.add(w);
    }
  }

  public boolean containsId(String id) {
    return features.containsKey(id)
        || ignoredIds.contains(id)
        || (store != null && store.containsId(id));
  }

  @Override
  public void close() {
    if (store != null) store.close();
  }

  public static final class Feature {
    public final String id, type;
    public final Geometry geometry;
    public final ObjectNode properties;

    public Feature(String id, String type, Geometry g, ObjectNode p) {
      this.id = id;
      this.type = type;
      geometry = g;
      properties = p;
    }

    public BigDecimal flow() {
      return properties.has("flow_tph") ? properties.get("flow_tph").decimalValue() : null;
    }

    public int dn() {
      return properties.path("diameter").asInt();
    }
  }

  public static final class Demand {
    public final String id;
    public final BigDecimal flow;
    public final List<Feature> terminals = new ArrayList<>();

    Demand(String id, BigDecimal flow) {
      this.id = id;
      this.flow = flow;
    }
  }

  public static final class Obstacle {
    public final String id, type, key;
    public final int part;
    public final Geometry geometry;
    public final double width;
    private final org.locationtech.jts.geom.prep.PreparedGeometry prepared;
    private final org.locationtech.jts.operation.distance.IndexedFacetDistance facets;

    Obstacle(String id, String type, int part, Geometry g, double width) {
      this(id, type, part, g, width, Geo.fingerprint(g));
    }

    Obstacle(String id, String type, int part, Geometry g, double width, String fingerprint) {
      this.id = id;
      this.type = type;
      this.part = part;
      geometry = g;
      this.width = width;
      key = id + ":" + fingerprint;
      prepared = org.locationtech.jts.geom.prep.PreparedGeometryFactory.prepare(g);
      facets = new org.locationtech.jts.operation.distance.IndexedFacetDistance(g);
    }

    public boolean covers(Point point) {
      return prepared.covers(point);
    }

    public double distance(Geometry other) {
      return prepared.intersects(other) ? 0 : facets.distance(other);
    }

    public boolean building() {
      return type.equals("building");
    }

    public boolean special() {
      return Set.of("road", "tram_tracks", "gas_pipeline", "power_cable", "heat_network")
          .contains(type);
    }

    public double clearance(int dn) {
      if (building()) return dn < 500 ? 5 : dn < 900 ? 7 : 9;
      switch (type) {
        case "road":
        case "tram_tracks":
          return 1.5;
        case "gas_pipeline":
        case "power_cable":
          return 2;
        default:
          return 1;
      }
    }

    public double required(int dn, Rules r) {
      return required(dn)
          + (type.equals("railway")
              ? r.number("geometry.railway_clearance_m") - 1
              : type.equals("metro") ? r.number("geometry.metro_clearance_m") - 1 : 0);
    }

    public double required(int dn) {
      return clearance(dn) + Catalog.pipe(dn).width / 2 + width / 2;
    }

    public double factor() {
      switch (type) {
        case "road":
          return 1.6;
        case "tram_tracks":
          return 1.75;
        case "gas_pipeline":
          return 1.25;
        case "power_cable":
          return 1.15;
        case "heat_network":
          return 1.05;
        default:
          return 1;
      }
    }
  }

  public final NavigableMap<String, Feature> features = new TreeMap<>();
  public final List<Demand> demands = new ArrayList<>();
  public final List<Feature> networks = new ArrayList<>(),
      chambers = new ArrayList<>(),
      sources = new ArrayList<>();
  public final List<Obstacle> obstacles = new ArrayList<>();
  public final STRtree index = new STRtree();
  public long coordinateCount;
  public int buildingParts, holeCount;
  public boolean reduced;

  public BigDecimal totalFlow() {
    return demands.stream().map(d -> d.flow).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  @SuppressWarnings("unchecked")
  public List<Obstacle> near(Envelope e, double margin) {
    Envelope x = new Envelope(e);
    x.expandBy(margin);
    if (store != null) {
      Object[] c = cached.get();
      if (c[0] == null || !((Envelope) c[0]).contains(x)) {
        Envelope window = new Envelope(x);
        window.expandBy(100);
        c[1] = store.query(window);
        c[0] = window;
      }
      List<Obstacle> found = new ArrayList<>();
      for (Obstacle obstacle : (List<Obstacle>) c[1])
        if (obstacle.geometry.getEnvelopeInternal().intersects(x)) found.add(obstacle);
      for (Obstacle obstacle : obstacles)
        if (obstacle.geometry.getEnvelopeInternal().intersects(x)) found.add(obstacle);
      found.sort(Comparator.comparing(a -> a.key));
      return found;
    }
    List<Obstacle> o = new ArrayList<>((List<Obstacle>) (List<?>) index.query(x));
    o.sort(Comparator.comparing(a -> a.key));
    return o;
  }

  /**
   * Another part of the restriction feature that contains the terminal; only its clearance is
   * waived on the final straight run.
   */
  public boolean hostSibling(Obstacle o, Coordinate terminal, Rules rules) {
    if (terminal == null || !rules.hostFeature() || !o.building() || o.covers(Geo.point(terminal)))
      return false;
    for (Obstacle h : hosts(terminal)) if (h.id.equals(o.id)) return true;
    return false;
  }

  public List<Obstacle> hosts(Coordinate p) {
    List<Obstacle> h = new ArrayList<>();
    for (Obstacle o : near(new Envelope(p), 0))
      if (o.building() && o.covers(Geo.point(p))) h.add(o);
    return h;
  }

  public Map<String, Object> audit() {
    Map<String, Object> a = new LinkedHashMap<>();
    a.put("features", sourceFeatureCount > 0 ? sourceFeatureCount : features.size());
    a.put("demands", demands.size());
    a.put("flow_tph", totalFlow());
    a.put("building_parts", buildingParts);
    a.put("polygon_holes", holeCount);
    a.put("coordinate_positions", coordinateCount);
    a.put("networks", networks.size());
    a.put("chambers", chambers.size());
    a.put("sources", sources.size());
    a.put("existing_length_m", networks.stream().mapToDouble(f -> f.geometry.getLength()).sum());
    a.put(
        "invalid_demand_ids",
        invalidDemands.stream().map(d -> d.id).collect(java.util.stream.Collectors.toList()));
    a.put("warning_counts", warningCounts);
    a.put("warnings", warnings);
    return a;
  }
}
