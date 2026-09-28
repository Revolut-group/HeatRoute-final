package ru.heatroute;

import java.util.*;
import org.locationtech.jts.geom.*;

/** Concrete evidence about the mandatory final approach, separate from route-search failure. */
final class TerminalDiagnostics {
  static Map<String, Object> inspect(Dataset data, Rules rules) {
    Map<String, Object> results = new TreeMap<>();
    GeometryRules geometry = new GeometryRules(data, rules);
    FeasibleEntrances entrances = new FeasibleEntrances(data, rules);
    for (Dataset.Demand demand : data.demands) {
      Coordinate p = demand.terminals.get(0).geometry.getCoordinate();
      int dn = Catalog.base(demand.flow).dn;
      List<Dataset.Obstacle> hosts = data.hosts(p);
      if (hosts.isEmpty()) continue;
      List<Map<String, Object>> candidates = new ArrayList<>();
      boolean possible = false;
      for (Coordinate port :
          rules.flexibleEntrances()
              ? entrances.ports(p, dn, false)
              : TerminalAccess.ports(p, hosts, dn, rules)) {
        LineString line = Geo.line(port, p);
        boolean valid = geometry.segment(port, p, dn, p, null);
        possible |= valid;
        List<Object> blockers = new ArrayList<>();
        for (Dataset.Obstacle obstacle :
            data.near(line.getEnvelopeInternal(), rules.queryMargin())) {
          if (obstacle.distance(line) + 1e-6 >= obstacle.required(dn, rules)) continue;
          if (hosts.contains(obstacle)) {
            if (!TerminalAccess.accepts(line, p, obstacle.geometry, rules)) {
              Geometry hits = line.intersection(obstacle.geometry.getBoundary());
              List<Double> distances = new ArrayList<>();
              for (Coordinate point : hits.getCoordinates()) distances.add(point.distance(p));
              Collections.sort(distances);
              blockers.add(
                  Map.of(
                      "code",
                      "HOST_REENTRY_ON_NEAREST_APPROACH",
                      "obstacle_id",
                      obstacle.id,
                      "polygon_part_zero_based",
                      obstacle.part,
                      "nearest_boundary_m",
                      obstacle.geometry.getBoundary().distance(Geo.point(p)),
                      "boundary_intersections_distance_from_terminal_m",
                      distances));
            }
          } else if (!obstacle.special())
            blockers.add(
                Map.of(
                    "code",
                    "OTHER_OBSTACLE_CLEARANCE",
                    "obstacle_id",
                    obstacle.id,
                    "polygon_part_zero_based",
                    obstacle.part,
                    "axis_distance_m",
                    obstacle.distance(line),
                    "required_axis_distance_m",
                    obstacle.required(dn, rules)));
        }
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("outside_port_wgs84", Geo.position(port));
        candidate.put("valid", valid);
        candidate.put("obstacles", blockers);
        candidates.add(candidate);
      }
      Map<String, Object> report = new LinkedHashMap<>();
      report.put("entry_policy", rules.text("input.terminal_entry_policy"));
      report.put("minimum_flow_diameter", dn);
      report.put("prescribed_final_approach_found", possible);
      report.put("candidates", candidates);
      results.put(demand.id, report);
    }
    return results;
  }
}
