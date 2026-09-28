package ru.heatroute;

import java.util.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.linearref.LengthIndexedLine;

/**
 * Depth stage of the appendix (§5). The top of the new pipe pair stays at the normal depth unless a
 * crossing needs another level. Every vertical requirement is a "ball" on the network: all points
 * of the pipes within a radius (measured along the pipes) of a crossing must be above an upper
 * bound or below a lower bound. With the slope limit m the profile
 *
 * <p>h(x) = min(max(normal, lower(x)), upper(x)), lower(x) = max(cover, max_i L_i - m * dist(x,
 * ball_i)), upper(x) = min_j U_j + m * dist(x, ball_j),
 *
 * <p>is m-Lipschitz along the tree (so continuous at every node and within the slope on every
 * edge), meets every bound whenever lower <= upper everywhere, and is pointwise the shallowest
 * admissible profile wherever it is deeper than the normal depth. Kгл = 1 + 0.1 * max(0, h - 3) is
 * non-decreasing in h, so this profile has the minimum cost for the chosen sides of the crossings.
 * Sides are enumerated (all "above" first, which never costs extra when it fits). No depth grid is
 * used: breakpoints are exact intersections of the envelope lines.
 */
final class DepthProfile {
  /** Depth where Kгл starts to grow; a ramp through it is split there. */
  static final double KINK = 3.0;

  private static final double EPS = 1e-9;

  /** Top depth of one edge, oriented from edge.from to edge.to, linear between breakpoints. */
  static final class Profile {
    final double[] s, h;

    Profile(double[] s, double[] h) {
      this.s = s;
      this.h = h;
    }

    double at(double x) {
      if (x <= s[0]) return h[0];
      for (int i = 1; i < s.length; i++)
        if (x <= s[i]) {
          double span = s[i] - s[i - 1];
          return span < 1e-12 ? h[i] : h[i - 1] + (h[i] - h[i - 1]) * (x - s[i - 1]) / span;
        }
      return h[h.length - 1];
    }
  }

  /**
   * One vertical requirement. above: deepest admissible top passing over (NaN if impossible);
   * below: shallowest top passing under.
   */
  static final class Crossing {
    final String obstacle, type;
    final int edge;
    final double station, radius, above, below;

    Crossing(
        String obstacle,
        String type,
        int edge,
        double station,
        double radius,
        double above,
        double below) {
      this.obstacle = obstacle;
      this.type = type;
      this.edge = edge;
      this.station = station;
      this.radius = radius;
      this.above = above;
      this.below = below;
    }

    boolean choosable() {
      return !Double.isNaN(above);
    }
  }

  private static final class Ball {
    final int edge;
    final double station, radius, value;
    final boolean lower;

    Ball(int edge, double station, double radius, double value, boolean lower) {
      this.edge = edge;
      this.station = station;
      this.radius = radius;
      this.value = value;
      this.lower = lower;
    }
  }

  private static final class Eval {
    boolean feasible = true;
    double violation, extra;
    Profile[] profiles;
  }

  private final Dataset data;
  private final Rules rules;
  private final double normal, cover, slope;
  private final boolean conservative;

  /**
   * One entry per root: sides chosen at crossings, endpoint policy, depth range and the extra cost
   * of depth.
   */
  final List<Map<String, Object>> diagnostics = new ArrayList<>();

  DepthProfile(Dataset data, Rules rules) {
    this.data = data;
    this.rules = rules;
    normal = rules.number("depth.normal_m");
    cover = rules.number("depth.minimum_cover_m");
    slope = rules.number("depth.maximum_slope");
    conservative = rules.text("depth.policy").equals("meeting_conservative");
  }

  /** Kгл averaged over a linear piece that does not cross 3 m (appendix §5). */
  static java.math.BigDecimal factor(double start, double end) {
    return java.math.BigDecimal.ONE.add(
        java.math.BigDecimal.valueOf(.05)
            .multiply(
                java.math.BigDecimal.valueOf(Math.max(0, start - KINK))
                    .add(java.math.BigDecimal.valueOf(Math.max(0, end - KINK)))));
  }

  /**
   * Vertical rule of a crossed object: top depth, height, clearance, or a minimum top depth under a
   * road.
   */
  static double[] vertical(Dataset data, Dataset.Obstacle o, boolean conservative) {
    double top, height, gap;
    switch (o.type) {
      case "gas_pipeline":
        top = 2.8;
        height = .4;
        gap = .2;
        break;
      case "power_cable":
        top = 2.7;
        height = .2;
        gap = .5;
        break;
      case "heat_network":
        {
          Dataset.Feature f = data.features.get(o.id);
          top = 3.0;
          height = Catalog.pipe(f == null ? 50 : f.dn()).height;
          gap = .5;
          break;
        }
      case "road":
        return new double[] {Double.NaN, 1.0};
      case "tram_tracks":
        return new double[] {Double.NaN, 1.2};
      default:
        return null;
    }
    if (conservative) gap = Math.max(gap, .7);
    return new double[] {top, height, gap};
  }

  /**
   * Sine of the angle between the route and the crossed line at p (the smallest one if several
   * segments meet there).
   */
  static double sine(LineString route, Geometry obstacle, Coordinate p) {
    double best = 1;
    Coordinate[] r = route.getCoordinates();
    for (int j = 1; j < r.length; j++) {
      LineSegment a = new LineSegment(r[j - 1], r[j]);
      if (a.getLength() < 1e-9 || a.distance(p) > 1e-6) continue;
      for (int n = 0; n < obstacle.getNumGeometries(); n++) {
        Coordinate[] c = obstacle.getGeometryN(n).getCoordinates();
        for (int i = 1; i < c.length; i++) {
          LineSegment b = new LineSegment(c[i - 1], c[i]);
          if (b.getLength() < 1e-9 || b.distance(p) > 1e-6) continue;
          double cross =
              Math.abs(
                      (a.p1.x - a.p0.x) * (b.p1.y - b.p0.y) - (a.p1.y - a.p0.y) * (b.p1.x - b.p0.x))
                  / (a.getLength() * b.getLength());
          best = Math.min(best, cross);
        }
      }
    }
    return Math.max(best, 1e-3);
  }

  Map<Network.Edge, Profile> solve(List<Network.Tree> trees) {
    Map<String, List<Network.Tree>> groups = new TreeMap<>();
    for (Network.Tree t : trees)
      if (!t.edges.isEmpty()) groups.computeIfAbsent(t.root.key, k -> new ArrayList<>()).add(t);
    Map<Network.Edge, Profile> out = new IdentityHashMap<>();
    for (List<Network.Tree> group : groups.values()) new Group(group).solve(out);
    return out;
  }

  /**
   * Trees sharing one tie-in form one metric tree: the depth at the shared chamber must be the same
   * for all of them.
   */
  private final class Group {
    final String key;
    final Coordinate rootPoint;
    final List<Network.Edge> edges = new ArrayList<>();
    final List<Crossing> crossings = new ArrayList<>();
    final List<Integer> terminals = new ArrayList<>();
    final int root;
    int[] u, v;
    double[] len;
    double[][] dist;
    int nodes;

    Group(List<Network.Tree> trees) {
      key = trees.get(0).root.key;
      rootPoint = trees.get(0).start.p;
      Map<Network.Node, Integer> ids = new IdentityHashMap<>();
      root = 0;
      nodes = 1;
      for (Network.Tree t : trees) ids.put(t.start, root);
      List<int[]> ends = new ArrayList<>();
      for (Network.Tree t : trees) {
        List<Network.Edge> sorted = new ArrayList<>(t.edges);
        sorted.sort(Comparator.comparing(e -> e.from.key + ">" + e.to.key));
        for (Network.Edge e : sorted) {
          for (Network.Node n : List.of(e.from, e.to))
            if (!ids.containsKey(n)) {
              ids.put(n, nodes++);
              if (n.terminalId != null) terminals.add(ids.get(n));
            }
          edges.add(e);
          ends.add(new int[] {ids.get(e.from), ids.get(e.to)});
        }
      }
      u = new int[edges.size()];
      v = new int[edges.size()];
      len = new double[edges.size()];
      for (int i = 0; i < edges.size(); i++) {
        u[i] = ends.get(i)[0];
        v[i] = ends.get(i)[1];
        len[i] = edges.get(i).line.getLength();
      }
      dist = new double[nodes][nodes];
      for (int s = 0; s < nodes; s++) {
        double[] d = dist[s];
        Arrays.fill(d, Double.POSITIVE_INFINITY);
        d[s] = 0;
        boolean[] done = new boolean[nodes];
        for (int round = 0; round < nodes; round++) {
          int best = -1;
          for (int n = 0; n < nodes; n++) if (!done[n] && (best < 0 || d[n] < d[best])) best = n;
          if (best < 0 || d[best] == Double.POSITIVE_INFINITY) break;
          done[best] = true;
          for (int e = 0; e < edges.size(); e++) {
            if (u[e] == best && d[best] + len[e] < d[v[e]]) d[v[e]] = d[best] + len[e];
            if (v[e] == best && d[best] + len[e] < d[u[e]]) d[u[e]] = d[best] + len[e];
          }
        }
      }
      for (int i = 0; i < edges.size(); i++) events(i);
      crossings.sort(
          Comparator.comparingInt((Crossing c) -> c.edge)
              .thenComparingDouble(c -> c.station)
              .thenComparing(c -> c.obstacle));
    }

    /** Crossings recomputed from the edge geometry (not from optimiser labels). */
    void events(int i) {
      Network.Edge e = edges.get(i);
      LineString line = e.line;
      LengthIndexedLine li = new LengthIndexedLine(line);
      Catalog.Pipe pipe = Catalog.pipe(e.dn);
      double snap = rules.number("geometry.topology_snap_m");
      List<Dataset.Obstacle> near =
          new ArrayList<>(data.near(line.getEnvelopeInternal(), rules.queryMargin()));
      near.sort(Comparator.comparing(o -> o.key));
      for (Dataset.Obstacle o : near) {
        double[] rule = vertical(data, o, conservative);
        if (rule == null || !line.intersects(o.geometry)) continue;
        boolean road = Double.isNaN(rule[0]);
        double above = road ? Double.NaN : rule[0] - rule[2] - pipe.height,
            below = road ? rule[1] : rule[0] + rule[1] + rule[2];
        if (!road && above < cover) above = Double.NaN;
        double extension = road ? 3 : 2;
        Geometry hit = line.intersection(o.geometry);
        if (o.geometry.getDimension() == 2) {
          for (int n = 0; n < hit.getNumGeometries(); n++) {
            Geometry part = hit.getGeometryN(n);
            if (part.getLength() < 1e-6) continue;
            double a = Double.POSITIVE_INFINITY, b = Double.NEGATIVE_INFINITY;
            for (Coordinate c : part.getCoordinates()) {
              double s = li.project(c);
              a = Math.min(a, s);
              b = Math.max(b, s);
            }
            crossings.add(
                new Crossing(o.id, o.type, i, (a + b) / 2, (b - a) / 2 + extension, above, below));
          }
        } else {
          for (Coordinate p : hit.getCoordinates()) {
            if (o.type.equals("heat_network") && p.distance(rootPoint) <= snap)
              continue; // the tie-in itself, not a crossing
            double overlap = (o.width + pipe.width) / 2 / sine(line, o.geometry, p);
            crossings.add(
                new Crossing(
                    o.id, o.type, i, li.project(p), Math.max(extension, overlap), above, below));
          }
        }
      }
    }

    List<Ball> balls(long mask, int pins) {
      List<Ball> out = new ArrayList<>();
      int k = 0;
      for (Crossing c : crossings) {
        boolean under = true;
        if (c.choosable()) under = (mask >> k++ & 1) == 1;
        out.add(
            under
                ? new Ball(c.edge, c.station, c.radius, c.below, true)
                : new Ball(c.edge, c.station, c.radius, c.above, false));
      }
      List<Integer> fixed = new ArrayList<>();
      if (pins < 2) fixed.add(root);
      if (pins < 1) fixed.addAll(terminals);
      for (int n : fixed) {
        int e = 0;
        while (u[e] != n && v[e] != n) e++;
        double s = u[e] == n ? 0 : len[e];
        out.add(new Ball(e, s, 0, normal, true));
        out.add(new Ball(e, s, 0, normal, false));
      }
      return out;
    }

    double centre(int node, Ball b) {
      return Math.min(
          dist[node][u[b.edge]] + b.station, dist[node][v[b.edge]] + len[b.edge] - b.station);
    }

    Eval evaluate(List<Ball> balls, boolean keep) {
      Eval out = new Eval();
      if (keep) out.profiles = new Profile[edges.size()];
      int m = balls.size();
      double[][] nodeDist = new double[nodes][m];
      for (int n = 0; n < nodes; n++)
        for (int b = 0; b < m; b++) nodeDist[n][b] = centre(n, balls.get(b));
      double[] nodeDepth = new double[nodes];
      for (int n = 0; n < nodes; n++) {
        double[] t = new double[m];
        for (int b = 0; b < m; b++) {
          Ball ball = balls.get(b);
          double d = Math.max(0, nodeDist[n][b] - ball.radius);
          t[b] = ball.lower ? ball.value - slope * d : ball.value + slope * d;
        }
        double[] bounds = bounds(balls, t);
        nodeDepth[n] = depth(bounds);
        if (bounds[0] > bounds[1] + EPS) {
          out.feasible = false;
          out.violation += bounds[0] - bounds[1];
        }
      }
      // A bound that cannot matter on an edge is left out there: a lower bound never above the
      // cover, an upper bound never
      // below the deepest level any lower bound (or the normal depth) can ask for. The profile is
      // exactly the same.
      double ceiling = normal;
      for (Ball ball : balls) if (ball.lower) ceiling = Math.max(ceiling, ball.value);
      for (int e = 0; e < edges.size(); e++) {
        List<Ball> active = new ArrayList<>();
        List<Double> fromU = new ArrayList<>(), fromV = new ArrayList<>();
        for (int b = 0; b < m; b++) {
          Ball ball = balls.get(b);
          double gap =
              ball.edge == e
                  ? 0
                  : Math.max(0, Math.min(nodeDist[u[e]][b], nodeDist[v[e]][b]) - ball.radius);
          if (ball.lower ? ball.value - slope * gap <= cover : ball.value + slope * gap >= ceiling)
            continue;
          active.add(ball);
          fromU.add(nodeDist[u[e]][b]);
          fromV.add(nodeDist[v[e]][b]);
        }
        double[] du = new double[active.size()], dv = new double[active.size()];
        for (int b = 0; b < du.length; b++) {
          du[b] = fromU.get(b);
          dv[b] = fromV.get(b);
        }
        TreeSet<Double> stations = new TreeSet<>();
        stations.add(0.0);
        stations.add(len[e]);
        for (Intervals.Zone z : edges.get(e).zones) {
          add(stations, z.a, e);
          add(stations, z.b, e);
        }
        for (int b = 0; b < du.length; b++) {
          Ball ball = active.get(b);
          if (ball.edge == e) {
            add(stations, ball.station - ball.radius, e);
            add(stations, ball.station + ball.radius, e);
          } else {
            add(stations, (len[e] + dv[b] - du[b]) / 2, e);
            add(stations, ball.radius - du[b], e);
            add(stations, len[e] - (ball.radius - dv[b]), e);
          }
        }
        List<Double> base = new ArrayList<>(stations);
        // Envelope kinks: intersections of every pair of bound lines on each elementary interval,
        // and with the constants.
        for (int j = 1; j < base.size(); j++) {
          double p = base.get(j - 1), q = base.get(j);
          if (q - p < 1e-9) continue;
          double[] fp = terms(active, du, dv, e, p), fq = terms(active, du, dv, e, q);
          int t = fp.length;
          for (int x = 0; x < t + 2; x++)
            for (int y = x + 1; y < t + 2; y++) {
              double ap = x < t ? fp[x] : x == t ? normal : cover,
                  aq = x < t ? fq[x] : x == t ? normal : cover,
                  bp = y < t ? fp[y] : y == t ? normal : cover,
                  bq = y < t ? fq[y] : y == t ? normal : cover;
              double dp = ap - bp, dq = aq - bq;
              if (dp * dq < 0) stations.add(p + (q - p) * dp / (dp - dq));
            }
        }
        List<Double> s = new ArrayList<>(stations);
        List<Double> h = new ArrayList<>();
        for (int j = 0; j < s.size(); j++) {
          double at = s.get(j);
          if (j == 0) {
            h.add(nodeDepth[u[e]]);
            continue;
          }
          if (j == s.size() - 1) {
            h.add(nodeDepth[v[e]]);
            continue;
          }
          double[] bounds = bounds(active, terms(active, du, dv, e, at));
          if (bounds[0] > bounds[1] + EPS) {
            out.feasible = false;
            out.violation += bounds[0] - bounds[1];
          }
          h.add(depth(bounds));
        }
        // Split every ramp that passes through 3 m (appendix §5), then drop collinear breakpoints.
        for (int j = 1; j < s.size(); j++) {
          double a = h.get(j - 1) - KINK, b = h.get(j) - KINK;
          if (a * b < 0 && Math.abs(a) > 1e-12 && Math.abs(b) > 1e-12) {
            double at = s.get(j - 1) + (s.get(j) - s.get(j - 1)) * a / (a - b);
            s.add(j, at);
            h.add(j, KINK);
            j++;
          }
        }
        for (int j = 1; j + 1 < s.size(); ) {
          double in = (h.get(j) - h.get(j - 1)) / Math.max(1e-12, s.get(j) - s.get(j - 1)),
              next = (h.get(j + 1) - h.get(j)) / Math.max(1e-12, s.get(j + 1) - s.get(j));
          boolean kink =
              Math.abs(h.get(j) - KINK) < 1e-12
                  && (h.get(j - 1) - KINK) * (h.get(j + 1) - KINK) < 0;
          if (Math.abs(in - next) < 1e-9 && !kink) {
            s.remove(j);
            h.remove(j);
          } else j++;
        }
        double price = Catalog.pipe(edges.get(e).dn).newPrice.doubleValue();
        for (int j = 1; j < s.size(); j++) {
          double a = s.get(j - 1), b = s.get(j);
          out.extra +=
              price
                  * Intervals.factor(edges.get(e).zones, (a + b) / 2)
                  * (b - a)
                  * .05
                  * (Math.max(0, h.get(j - 1) - KINK) + Math.max(0, h.get(j) - KINK));
        }
        if (keep) {
          double[] ss = new double[s.size()], hh = new double[s.size()];
          for (int j = 0; j < ss.length; j++) {
            ss[j] = s.get(j);
            hh[j] = h.get(j);
          }
          out.profiles[e] = new Profile(ss, hh);
        }
      }
      return out;
    }

    private void add(Set<Double> stations, double s, int e) {
      if (s > 1e-9 && s < len[e] - 1e-9) stations.add(s);
    }

    /**
     * Signed value of every bound at station s of edge e: lower bounds as they are, upper bounds as
     * they are.
     */
    private double[] terms(List<Ball> balls, double[] du, double[] dv, int e, double s) {
      double[] t = new double[balls.size()];
      for (int b = 0; b < t.length; b++) {
        Ball ball = balls.get(b);
        double d =
            ball.edge == e ? Math.abs(s - ball.station) : Math.min(s + du[b], len[e] - s + dv[b]);
        d = Math.max(0, d - ball.radius);
        t[b] = ball.lower ? ball.value - slope * d : ball.value + slope * d;
      }
      return t;
    }

    /** [lower, upper] from the values of all bounds at one point. */
    private double[] bounds(List<Ball> balls, double[] t) {
      double lower = cover, upper = Double.POSITIVE_INFINITY;
      for (int b = 0; b < t.length; b++)
        if (balls.get(b).lower) lower = Math.max(lower, t[b]);
        else upper = Math.min(upper, t[b]);
      return new double[] {lower, upper};
    }

    private double depth(double[] bounds) {
      return Math.min(Math.max(normal, bounds[0]), bounds[1]);
    }

    void solve(Map<Network.Edge, Profile> out) {
      List<Crossing> free = new ArrayList<>();
      for (Crossing c : crossings) if (c.choosable()) free.add(c);
      int k = free.size();
      Eval best = null;
      long bestMask = 0;
      int level;
      String[] policy = {"normal_at_terminals_and_tie_in", "normal_at_tie_in", "free_endpoints"};
      for (level = 0; level < 3; level++) {
        if (level == 1 && terminals.isEmpty()) continue;
        if (k <= 8) {
          List<Long> masks = new ArrayList<>();
          for (long mask = 0; mask < 1L << k; mask++) masks.add(mask);
          masks.sort(
              Comparator.comparingInt((Long x) -> Long.bitCount(x)).thenComparingLong(x -> x));
          for (long mask : masks) {
            Eval e = evaluate(balls(mask, level), false);
            if (e.feasible && (best == null || e.extra < best.extra - 1e-6)) {
              best = e;
              bestMask = mask;
            }
            if (best != null && best.extra < 1e-6) break;
          }
        } else {
          long mask = 0;
          Eval e = evaluate(balls(mask, level), false);
          while (!e.feasible) {
            long pick = -1;
            Eval chosen = null;
            for (int i = 0; i < k; i++)
              if ((mask >> i & 1) == 0) {
                Eval t = evaluate(balls(mask | 1L << i, level), false);
                if (chosen == null
                    || t.violation < chosen.violation - 1e-9
                    || Math.abs(t.violation - chosen.violation) <= 1e-9 && t.extra < chosen.extra) {
                  chosen = t;
                  pick = mask | 1L << i;
                }
              }
            if (chosen == null || chosen.violation >= e.violation - 1e-9 && !chosen.feasible) break;
            mask = pick;
            e = chosen;
          }
          if (e.feasible) {
            for (boolean improved = true; improved; ) {
              improved = false;
              for (int i = 0; i < k; i++)
                if ((mask >> i & 1) == 1) {
                  Eval t = evaluate(balls(mask & ~(1L << i), level), false);
                  if (t.feasible && t.extra < e.extra - 1e-6) {
                    mask &= ~(1L << i);
                    e = t;
                    improved = true;
                  }
                }
            }
            best = e;
            bestMask = mask;
          }
        }
        if (best != null) break;
      }
      if (best == null) {
        level = 2;
        bestMask = (1L << Math.min(k, 62)) - 1;
      } // only lower bounds remain: always feasible
      Eval result = evaluate(balls(bestMask, level), true);
      double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
      int breaks = 0;
      for (int e = 0; e < edges.size(); e++) {
        Profile p = result.profiles[e];
        out.put(edges.get(e), p);
        breaks += p.s.length - 2;
        for (double h : p.h) {
          min = Math.min(min, h);
          max = Math.max(max, h);
        }
      }
      List<Map<String, Object>> events = new ArrayList<>();
      int bit = 0;
      for (Crossing c : crossings) {
        boolean under = true;
        if (c.choosable()) under = (bestMask >> bit++ & 1) == 1;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(
            "obstacle_id",
            data.features.containsKey(c.obstacle)
                ? data.features.get(c.obstacle).properties.get("id")
                : c.obstacle);
        m.put("type", c.type);
        m.put(
            "side",
            c.type.equals("road") || c.type.equals("tram_tracks")
                ? "under_road"
                : under ? "below" : "above");
        m.put("required_top_depth_m", round(under ? c.below : c.above));
        m.put("bound", under ? "minimum" : "maximum");
        m.put("half_length_m", round(c.radius));
        m.put("depth_at_crossing_m", round(result.profiles[c.edge].at(c.station)));
        events.add(m);
      }
      Map<String, Object> d = new LinkedHashMap<>();
      d.put("tie_in", key);
      d.put("endpoint_policy", policy[level]);
      d.put("crossings", events);
      d.put("min_top_depth_m", round(min));
      d.put("max_top_depth_m", round(max));
      d.put("profile_breakpoints", breaks);
      d.put("extra_depth_cost_rub", Math.round(result.extra * 100) / 100.0);
      diagnostics.add(d);
    }
  }

  static double round(double x) {
    return Math.round(x * 1e6) / 1e6;
  }
}
