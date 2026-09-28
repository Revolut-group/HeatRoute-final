package ru.heatroute;

import java.math.BigDecimal;
import java.util.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.linearref.LengthIndexedLine;

/**
 * Global Steiner-forest planner for the current appendix.
 *
 * <p>1. A clearance raster is built per DN class: forbidden restriction zones are blocked, special
 * objects (roads, tram tracks, cables, existing networks) form bands that can only be passed by a
 * straight "jump" that satisfies the crossing angle and the straight extensions. 2. Any-angle-ish
 * Dijkstra fields (16-neighbourhood + jumps) are computed from every terminal entrance ray. 3. The
 * forest is built by cheapest insertion (new root, branch on an edge, branch at a chamber) under an
 * approximate but complete cost model (flows, DN, chambers, tie-ins, penalties), then improved by
 * ruin-and-recreate. 4. Grid paths are tautened into exact vector geometry and the result is
 * re-evaluated by the common Evaluation/CurrentExporter code and later by the independent verifier.
 */
final class GridPlanner {
  private static final byte FREE = 0, BLOCK = 1, BAND = 2;
  private static final int[][] MOVES = {
    {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}, {1, 2}, {2, 1}, {-1, 2},
    {-2, 1}, {1, -2}, {2, -1}, {-1, -2}, {-2, -1}
  };
  private static final byte NONE = -1, SEEDC = -2, JUMP = -3;

  /**
   * Largest turn between a branch and its trunk (or between the pipes at a chamber) that the raster
   * search accepts, measured over 8 m of raster path. The rule is 90°; the margin covers raster
   * directions that the exact tautening turns further (it rechecks 89.9°). Each value is a family
   * of restarts: a wider window finds perpendicular branches off heavy trunks, a narrower one keeps
   * more forests exact; all families compete in one pool.
   */
  private static double[] families(String fallback) {
    return Arrays.stream(System.getProperty("heatroute.turnFamilies", fallback).split(","))
        .mapToDouble(Double::parseDouble)
        .toArray();
  }

  /** Turn families of this search: 75/85°; five from 70° to 88° in the deep mode. */
  private final double[] turnFamilies;

  /** Raster turn limit of this worker (its restart family). */
  private double maxTurn;

  /**
   * Deep mode: deterministic like reproducible, about ten times the search (five turn families,
   * twice the restarts, two perturbation rounds). For a dataset known in advance.
   */
  private final boolean deep;

  private final Dataset data;
  private final Rules rules;
  private final Existing existing;
  private final GeometryRules checks;
  private final Evaluation evaluator;
  private final FeasibleEntrances entrances;
  final Map<String, Object> diagnostics = new LinkedHashMap<>();
  private long deadline;
  private final boolean fast;

  /**
   * Current search stream; every restart and every polish gets its own, derived from the seed (see
   * rng).
   */
  private Random random;

  private final Router router;

  GridPlanner(Dataset d, Rules r, Existing ex, Router router) {
    data = d;
    rules = r;
    existing = ex;
    this.router = router;
    checks = new GeometryRules(d, r);
    evaluator = new Evaluation(d, r, router);
    entrances = new FeasibleEntrances(d, r);
    // Reproducible mode never looks at the clock: effort is bounded by restarts, elite tries and
    // repair rounds only.
    fast = rules.text("execution.mode").equals("fast");
    deep = rules.text("execution.mode").equals("deep");
    turnFamilies = families(deep ? "70,75,80,85,88" : "75,85");
    maxTurn = turnFamilies[0];
    deadline =
        fast
            ? Math.min(
                rules.searchDeadline(),
                System.nanoTime() + (long) (rules.number("execution.planner_seconds") * 1e9))
            : Long.MAX_VALUE;
    random = new Random(rules.integer("execution.seed"));
    grids = new HashMap<>();
    seedsByDn = new HashMap<>();
    cancel = new java.util.concurrent.atomic.AtomicBoolean();
    owner = this;
  }

  /**
   * A search worker: shares the prepared rasters, terminal fields and seeds of its parent
   * (read-only after prepare), owns everything a search or a vectorization writes. Workers run in
   * parallel; every result depends only on the work item and its own random stream, never on the
   * schedule.
   */
  private GridPlanner(GridPlanner p, Random random) {
    data = p.data;
    rules = p.rules;
    existing = p.existing;
    router = new Router(p.data, p.rules);
    checks = new GeometryRules(data, rules);
    evaluator = new Evaluation(data, rules, router);
    entrances = new FeasibleEntrances(data, rules);
    fast = p.fast;
    deep = p.deep;
    turnFamilies = p.turnFamilies;
    deadline = p.deadline;
    start = p.start;
    this.random = random;
    grids = p.grids;
    seedsByDn = p.seedsByDn;
    sharedCore = p.sharedCore;
    sharedDir = p.sharedDir;
    sharedK = p.sharedK;
    sharedExt = p.sharedExt;
    region = p.region;
    resolution = p.resolution;
    terminals = p.terminals;
    plan = p.plan;
    bannedRoots.addAll(p.bannedRoots);
    polishRounds = p.polishRounds;
    eliteLimit = p.eliteLimit;
    maxTurn = p.maxTurn;
    cancel = p.cancel;
    owner = p.owner;
  }

  // ------------------------------------------------------------------ parallel work
  /** The planner that prepared the shared state; shared rasters belong to it, not to a worker. */
  private final GridPlanner owner;

  /** Set when the job is cancelled; every worker stops at its next check. */
  private final java.util.concurrent.atomic.AtomicBoolean cancel;

  /**
   * Workers: up to 12, no more than the cores, and one per 256 MB of heap (a worker holds about 100
   * MB of fields and occupancy on a 4.8 million cell raster). Results never depend on it.
   */
  private static final java.util.concurrent.ForkJoinPool POOL =
      new java.util.concurrent.ForkJoinPool(
          Integer.getInteger(
              "heatroute.threads",
              (int)
                  Math.max(
                      1,
                      Math.min(
                          Math.min(12, Runtime.getRuntime().availableProcessors()),
                          Runtime.getRuntime().maxMemory() / (256L << 20)))));

  private void checkCancel() {
    if (cancel.get() || Thread.currentThread().isInterrupted())
      throw new Failure("CANCELLED", "Search cancelled");
  }

  /**
   * Runs the jobs on the shared pool and returns their results in job order. With one thread (or
   * one job) they run inline, which gives the same results: nothing depends on the schedule.
   */
  private <T> List<T> parallel(List<java.util.concurrent.Callable<T>> jobs) {
    List<T> out = new ArrayList<>();
    if (POOL.getParallelism() == 1 || jobs.size() <= 1) {
      for (java.util.concurrent.Callable<T> job : jobs) {
        checkCancel();
        try {
          out.add(job.call());
        } catch (RuntimeException | Error e) {
          throw e;
        } catch (Exception e) {
          throw new IllegalStateException(e);
        }
      }
      return out;
    }
    List<java.util.concurrent.ForkJoinTask<T>> tasks = new ArrayList<>();
    for (java.util.concurrent.Callable<T> job : jobs)
      tasks.add(java.util.concurrent.ForkJoinTask.adapt(job));
    if (java.util.concurrent.ForkJoinTask.getPool() == POOL)
      java.util.concurrent.ForkJoinTask.invokeAll(tasks);
    else {
      java.util.concurrent.Future<?> all =
          POOL.submit(() -> java.util.concurrent.ForkJoinTask.invokeAll(tasks));
      try {
        all.get();
      } catch (InterruptedException e) {
        cancel.set(true);
        Thread.currentThread().interrupt();
        throw new Failure("CANCELLED", "Search cancelled");
      } catch (java.util.concurrent.ExecutionException e) {
        Throwable cause = e.getCause();
        while (cause instanceof RuntimeException
            && !(cause instanceof Failure)
            && cause.getCause() != null
            && cause.getClass() == RuntimeException.class) cause = cause.getCause();
        if (cause instanceof RuntimeException) throw (RuntimeException) cause;
        if (cause instanceof Error) throw (Error) cause;
        throw new IllegalStateException(cause);
      }
    }
    for (java.util.concurrent.ForkJoinTask<T> t : tasks) out.add(t.join());
    return out;
  }

  private GridPlanner worker(Random stream) {
    return new GridPlanner(this, stream);
  }

  /** A finished work item with the diagnostics of the worker that produced it. */
  private static final class Done<T> {
    final T value;
    final Map<String, Object> diagnostics;
    final long rejectedJumps;

    Done(T value, Map<String, Object> diagnostics, long rejectedJumps) {
      this.value = value;
      this.diagnostics = diagnostics;
      this.rejectedJumps = rejectedJumps;
    }
  }

  private <T> Done<T> done(T value) {
    return new Done<>(value, diagnostics, rejectedJumps);
  }

  /** Worker diagnostics in job order: counters and seconds add up, everything else is copied. */
  private <T> T absorb(Done<T> w) {
    for (Map.Entry<String, Object> e : w.diagnostics.entrySet()) {
      Object old = diagnostics.get(e.getKey()), v = e.getValue();
      if (old instanceof Integer && v instanceof Integer)
        diagnostics.put(e.getKey(), (Integer) old + (Integer) v);
      else if (old instanceof Double && v instanceof Double && e.getKey().endsWith("_seconds"))
        diagnostics.put(e.getKey(), (Double) old + (Double) v);
      else diagnostics.put(e.getKey(), v);
    }
    rejectedJumps += w.rejectedJumps;
    return w.value;
  }

  // ------------------------------------------------------------------ cost model
  private double wc() {
    return rules.number("cost.weight_cost") / rules.number("cost.base_cost_rub");
  }

  private double wl() {
    return rules.number("cost.weight_length") / rules.number("cost.base_length_m");
  }

  private double perMetre(int dn) {
    return wc() * Catalog.pipe(dn).newPrice.doubleValue() + wl();
  }

  private double money(double rub) {
    return wc() * rub;
  }

  // ------------------------------------------------------------------ grid
  final class Grid {
    final int dn;
    final double x0, y0, h;
    final int nx, ny;
    final byte[] state;
    final boolean[] strip, core;
    final float[] coreDir, coreK, coreExt;

    /**
     * Straight passages from each strip cell, computed on first use. The value depends only on the
     * raster, so concurrent searches may fill the cache in any order.
     */
    final java.util.concurrent.ConcurrentHashMap<Integer, Jumps> jumpCache =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Price share of the per-metre objective for this DN: special extra metres are weighted exactly
     * as in S.
     */
    final double alpha;

    /** A private copy whose state may be changed (reroute): shares every read-only raster. */
    Grid(Grid base) {
      dn = base.dn;
      h = base.h;
      alpha = base.alpha;
      x0 = base.x0;
      y0 = base.y0;
      nx = base.nx;
      ny = base.ny;
      state = base.state.clone();
      strip = base.strip;
      core = base.core;
      coreDir = base.coreDir;
      coreK = base.coreK;
      coreExt = base.coreExt;
    }

    Grid(int dn, Envelope env, double h) {
      this.dn = dn;
      this.h = h;
      alpha = wc() * Catalog.pipe(dn).newPrice.doubleValue() / perMetre(dn);
      x0 = env.getMinX();
      y0 = env.getMinY();
      nx = (int) Math.ceil(env.getWidth() / h) + 1;
      ny = (int) Math.ceil(env.getHeight() / h) + 1;
      int n = nx * ny;
      state = new byte[n];
      strip = new boolean[n];
      // special-object cores do not depend on DN: one copy shared by every raster
      boolean fresh = sharedCore == null;
      if (fresh) {
        sharedCore = new boolean[n];
        sharedDir = new float[n];
        sharedK = new float[n];
        sharedExt = new float[n];
        Arrays.fill(sharedDir, Float.NaN);
      }
      core = sharedCore;
      coreDir = sharedDir;
      coreK = sharedK;
      coreExt = sharedExt;
      double pad = .04;
      for (Dataset.Obstacle o : data.near(env, rules.queryMargin())) {
        double required = o.required(dn, rules);
        if (o.special()) {
          double ext = Set.of("road", "tram_tracks").contains(o.type) ? 3 : 2;
          fill(
              o.geometry.buffer(required + pad, 8),
              c -> {
                if (state[c] == FREE) state[c] = BAND;
              });
          fill(o.geometry.buffer(ext + 1.2, 4), c -> strip[c] = true);
          if (fresh) markCore(o, ext);
        } else fill(o.geometry.buffer(required + pad, 8), c -> state[c] = BLOCK);
      }
      // Cells of hard zones are never jump sources.
      for (int c = 0; c < n; c++) if (state[c] != FREE) strip[c] = false;
    }

    int cell(double x, double y) {
      int i = (int) Math.floor((x - x0) / h), j = (int) Math.floor((y - y0) / h);
      return i < 0 || j < 0 || i >= nx || j >= ny ? -1 : j * nx + i;
    }

    Coordinate center(int c) {
      return new Coordinate(x0 + (c % nx + .5) * h, y0 + (c / nx + .5) * h);
    }

    void fill(Geometry g, java.util.function.IntConsumer mark) {
      List<double[]> edges = new ArrayList<>();
      for (int k = 0; k < g.getNumGeometries(); k++) {
        Geometry part = g.getGeometryN(k);
        if (!(part instanceof Polygon)) continue;
        Polygon p = (Polygon) part;
        ring(p.getExteriorRing().getCoordinates(), edges);
        for (int r = 0; r < p.getNumInteriorRing(); r++)
          ring(p.getInteriorRingN(r).getCoordinates(), edges);
      }
      Envelope e = g.getEnvelopeInternal();
      int j0 = Math.max(0, (int) Math.floor((e.getMinY() - y0) / h) - 1),
          j1 = Math.min(ny - 1, (int) Math.ceil((e.getMaxY() - y0) / h) + 1);
      double[] xs = new double[edges.size()];
      for (int j = j0; j <= j1; j++) {
        double y = y0 + (j + .5) * h;
        int m = 0;
        for (double[] s : edges)
          if ((s[1] <= y) != (s[3] <= y))
            xs[m++] = s[0] + (y - s[1]) * (s[2] - s[0]) / (s[3] - s[1]);
        Arrays.sort(xs, 0, m);
        for (int q = 0; q + 1 < m; q += 2) {
          int i0 = Math.max(0, (int) Math.ceil((xs[q] - x0) / h - .5)),
              i1 = Math.min(nx - 1, (int) Math.floor((xs[q + 1] - x0) / h - .5));
          for (int i = i0; i <= i1; i++) mark.accept(j * nx + i);
        }
      }
    }

    private void ring(Coordinate[] c, List<double[]> edges) {
      for (int i = 1; i < c.length; i++)
        edges.add(new double[] {c[i - 1].x, c[i - 1].y, c[i].x, c[i].y});
    }

    private void markCore(Dataset.Obstacle o, double ext) {
      Geometry g = o.geometry;
      if (g.getDimension() == 2)
        fill(
            g,
            c -> {
              core[c] = true;
              coreK[c] = (float) Math.max(coreK[c], o.factor());
              coreExt[c] = (float) Math.max(coreExt[c], ext);
            });
      Geometry lines = g.getDimension() == 2 ? g.getBoundary() : g;
      for (int k = 0; k < lines.getNumGeometries(); k++) {
        Coordinate[] c = lines.getGeometryN(k).getCoordinates();
        for (int i = 1; i < c.length; i++) {
          double len = c[i - 1].distance(c[i]);
          if (len < 1e-9) continue;
          float dir = (float) Math.atan2(c[i].y - c[i - 1].y, c[i].x - c[i - 1].x);
          int steps = (int) Math.ceil(len / (h / 4));
          for (int s = 0; s <= steps; s++) {
            double t = (double) s / steps;
            int cell =
                cell(
                    c[i - 1].x + t * (c[i].x - c[i - 1].x), c[i - 1].y + t * (c[i].y - c[i - 1].y));
            if (cell < 0) continue;
            if (core[cell]
                && !Float.isNaN(coreDir[cell])
                && Math.abs(Math.sin(coreDir[cell] - dir)) > .2)
              coreDir[cell] =
                  Float.POSITIVE_INFINITY; // two directions meet: only a junction-safe crossing is
            // allowed later by the exact check
            else if (Float.isNaN(coreDir[cell])
                || core[cell] && coreDir[cell] != Float.POSITIVE_INFINITY) coreDir[cell] = dir;
            core[cell] = true;
            coreK[cell] = (float) Math.max(coreK[cell], o.factor());
            coreExt[cell] = (float) Math.max(coreExt[cell], ext);
          }
        }
      }
    }

    /** Straight passages through special bands starting at a free cell. */
    Jumps jumps(int c) {
      Jumps known = jumpCache.get(c);
      if (known != null) return known;
      List<Integer> to = new ArrayList<>();
      List<Float> cost = new ArrayList<>(), span = new ArrayList<>();
      if (strip[c] && state[c] == FREE) {
        Coordinate a = center(c);
        double step = h / 3;
        for (int k = 0; k < 32; k++) {
          double ang = k * Math.PI / 16, ux = Math.cos(ang), uy = Math.sin(ang);
          double first = -1, last = -1, ext = 0, kk = 1;
          boolean inCore = false, ok = true;
          int landing = -1;
          double landT = 0;
          for (double t = step; t < 80; t += step) {
            int cell = cell(a.x + ux * t, a.y + uy * t);
            if (cell < 0) {
              ok = false;
              break;
            }
            if (state[cell] == BLOCK) {
              ok = false;
              break;
            }
            if (core[cell]) {
              float d = coreDir[cell];
              if (d == Float.POSITIVE_INFINITY) {
                ok = false;
                break;
              }
              if (!Float.isNaN(d)) {
                double sin = Math.abs(Math.sin(ang - d));
                if (sin < Math.sin(Math.toRadians(47))) {
                  ok = false;
                  break;
                }
              }
              if (!inCore && first < 0) first = t;
              inCore = true;
              last = t;
              ext = Math.max(ext, coreExt[cell]);
              kk = Math.max(kk, coreK[cell]);
            } else inCore = false;
            if (first < 0 && t > 6) {
              ok = false;
              break;
            }
            if (first >= 0 && first < ext + .05) {
              ok = false;
              break;
            }
            if (first >= 0 && !inCore && t >= last + ext + .1 && state[cell] == FREE) {
              landing = cell;
              landT = t;
              break;
            }
          }
          if (!ok || landing < 0) continue;
          to.add(landing);
          cost.add((float) (landT + (kk - 1) * alpha * (last - first + 2 * ext)));
          span.add((float) ((kk - 1) * (last - first + 2 * ext)));
        }
      }
      int[] t = new int[to.size()];
      float[] w = new float[to.size()], sp = new float[to.size()];
      for (int i = 0; i < t.length; i++) {
        t[i] = to.get(i);
        w[i] = cost.get(i);
        sp[i] = span.get(i);
      }
      Jumps made = new Jumps(t, w, sp);
      known = jumpCache.putIfAbsent(c, made);
      return known != null ? known : made;
    }
  }

  static final class Jumps {
    final int[] to;
    final float[] cost, span;

    /**
     * Exact-check state of every jump: 0 not checked yet, 1 valid, 2 rejected. Checked only when a
     * jump would improve a field; a race only repeats the same check.
     */
    final byte[] state;

    Jumps(int[] to, float[] cost, float[] span) {
      this.to = to;
      this.cost = cost;
      this.span = span;
      state = new byte[to.length];
    }
  }

  private long rejectedJumps;

  /**
   * Exact check once per jump, on first use: what the vector stage will accept between the two cell
   * centres.
   */
  private boolean validJump(Grid g, Jumps jumps, int c, int k) {
    byte known = jumps.state[k];
    if (known == 0) {
      known = exactJump(g.center(c), g.center(jumps.to[k]), g.dn) ? (byte) 1 : (byte) 2;
      jumps.state[k] = known;
      if (known == 2) rejectedJumps++;
    }
    return known == 1;
  }

  /** Same predicate as valid() without the forest-dependent parts (turns, placed pipes). */
  private boolean exactJump(Coordinate a, Coordinate b, int dn) {
    if (!checks.segment(a, b, dn, null, null)) return false;
    LineString l = Geo.line(a, b);
    double len = a.distance(b);
    for (Dataset.Obstacle o : data.near(l.getEnvelopeInternal(), rules.queryMargin())) {
      if (!o.special()) continue;
      Geometry hit = l.intersection(o.geometry);
      if (hit.isEmpty()) continue;
      double ext = Set.of("road", "tram_tracks").contains(o.type) ? 3 : 2;
      for (Coordinate c : hit.getCoordinates()) {
        double s0 = a.distance(c);
        if (s0 < ext + .02 || len - s0 < ext + .02) return false;
      }
    }
    for (Coordinate v : new Coordinate[] {a, b})
      for (Dataset.Obstacle o : data.near(new Envelope(v), rules.queryMargin()))
        if (o.special() && o.distance(Geo.point(v)) < o.required(dn, rules) - 1e-6) return false;
    return true;
  }

  private final Map<Integer, Grid> grids;
  private boolean[] sharedCore;
  private float[] sharedDir, sharedK, sharedExt;
  private Envelope region;
  private double resolution;

  private Grid grid(int dn) {
    synchronized (grids) {
      Grid g = grids.get(dn);
      if (g == null) {
        g = owner.new Grid(dn, region, resolution);
        grids.put(dn, g);
      }
      return g;
    }
  }

  // ------------------------------------------------------------------ search fields
  static final class Field {
    final Grid g;

    /**
     * Rows covered by the arrays at full raster width: the search window plus the seeds (the whole
     * raster without one). A row band keeps the cell-to-slot map a subtraction in the hot loop.
     */
    final int j0, h;

    private final int offset;

    /** Search window {i0,j0,i1,j1} including the seeds; null for the whole raster. */
    final int[] bounds;

    private final float[] dist;
    private final byte[] code;
    final Terminal terminal;
    final Map<Integer, Float> extra = new HashMap<>();
    final Map<Integer, Integer> jumpFrom = new HashMap<>();

    Field(Grid g, Terminal t) {
      this(g, t, null);
    }

    Field(Grid g, Terminal t, int[] bounds) {
      this.g = g;
      terminal = t;
      this.bounds = bounds;
      j0 = bounds == null ? 0 : bounds[1];
      h = bounds == null ? g.ny : bounds[3] - bounds[1] + 1;
      offset = j0 * g.nx;
      dist = new float[g.nx * h];
      code = new byte[g.nx * h];
      Arrays.fill(dist, Float.POSITIVE_INFINITY);
      Arrays.fill(code, NONE);
    }

    private int local(int c) {
      int l = c - offset;
      return l < 0 || l >= dist.length ? -1 : l;
    }

    /** Field distance of raster cell c; infinite outside the covered cells. */
    float d(int c) {
      int l = local(c);
      return l < 0 ? Float.POSITIVE_INFINITY : dist[l];
    }

    byte k(int c) {
      int l = local(c);
      return l < 0 ? NONE : code[l];
    }

    void set(int c, float d, byte k) {
      int l = local(c);
      dist[l] = d;
      code[l] = k;
    }

    /** Predecessor cell; -1 for a seed, -2 when unreached. */
    int pred(int c) {
      byte k = k(c);
      if (k == NONE) return -2;
      if (k == SEEDC) return -1;
      if (k == JUMP) return jumpFrom.get(c);
      return c - (MOVES[k][1] * g.nx + MOVES[k][0]);
    }
  }

  private static final class Heap {
    long[] a = new long[1024];
    int n;

    void push(float d, int c) {
      if (n == a.length) a = Arrays.copyOf(a, n * 2);
      long v = ((long) Float.floatToIntBits(d) << 32) | (c & 0xffffffffL);
      int i = n++;
      while (i > 0) {
        int p = (i - 1) / 2;
        if (a[p] <= v) break;
        a[i] = a[p];
        i = p;
      }
      a[i] = v;
    }

    long pop() {
      long top = a[0];
      long v = a[--n];
      int i = 0;
      while (true) {
        int l = 2 * i + 1;
        if (l >= n) break;
        int r = l + 1;
        int m = r < n && a[r] < a[l] ? r : l;
        if (a[m] >= v) break;
        a[i] = a[m];
        i = m;
      }
      a[i] = v;
      return top;
    }
  }

  private Field search(Grid g, Terminal t) {
    return search(g, t, t.seedCells, t.seedS, t.ux, t.uy, -1);
  }

  /** Optional search window in cell indices {i0,j0,i1,j1}; null = whole raster. */
  private int[] window;

  private int[] windowAround(Grid g, List<Coordinate> pts, double margin) {
    double x0 = Double.POSITIVE_INFINITY, y0 = x0, x1 = Double.NEGATIVE_INFINITY, y1 = x1;
    for (Coordinate c : pts) {
      x0 = Math.min(x0, c.x);
      y0 = Math.min(y0, c.y);
      x1 = Math.max(x1, c.x);
      y1 = Math.max(y1, c.y);
    }
    return new int[] {
      Math.max(0, (int) ((x0 - margin - g.x0) / g.h)),
      Math.max(0, (int) ((y0 - margin - g.y0) / g.h)),
      Math.min(g.nx - 1, (int) ((x1 + margin - g.x0) / g.h)),
      Math.min(g.ny - 1, (int) ((y1 + margin - g.y0) / g.h))
    };
  }

  private Field search(
      Grid g, Terminal t, int[] seedCells, double[] seedS, double ux, double uy, int target) {
    int[] bounds = null;
    if (window != null) {
      bounds = window.clone();
      for (int c : seedCells) {
        bounds[0] = Math.min(bounds[0], c % g.nx);
        bounds[1] = Math.min(bounds[1], c / g.nx);
        bounds[2] = Math.max(bounds[2], c % g.nx);
        bounds[3] = Math.max(bounds[3], c / g.nx);
      }
    }
    Field f = new Field(g, t, bounds);
    Heap heap = new Heap();
    for (int i = 0; i < seedCells.length; i++) {
      int c = seedCells[i];
      if (seedS[i] < f.d(c)) {
        f.set(c, (float) seedS[i], SEEDC);
        heap.push(f.d(c), c);
      }
    }
    double[] len = new double[MOVES.length];
    for (int m = 0; m < MOVES.length; m++) len[m] = Math.hypot(MOVES[m][0], MOVES[m][1]) * g.h;
    while (heap.n > 0) {
      long v = heap.pop();
      int c = (int) v;
      float d = Float.intBitsToFloat((int) (v >>> 32));
      if (d > f.d(c)) continue;
      if (c == target) break;
      int i = c % g.nx, j = c / g.nx;
      boolean seed = f.k(c) == SEEDC;
      for (int m = 0; m < MOVES.length; m++) {
        int dx = MOVES[m][0], dy = MOVES[m][1], ii = i + dx, jj = j + dy;
        if (ii < 0 || jj < 0 || ii >= g.nx || jj >= g.ny) continue;
        if (window != null
            && (ii < window[0] || jj < window[1] || ii > window[2] || jj > window[3])) continue;
        if (seed && dx * ux + dy * uy < -1e-9) continue;
        int n = jj * g.nx + ii;
        if (g.state[n] != FREE) continue;
        if (Math.abs(dx) + Math.abs(dy) >= 2) {
          int sx = Integer.signum(dx), sy = Integer.signum(dy);
          if (Math.abs(dx) == 1 && Math.abs(dy) == 1) {
            if (g.state[j * g.nx + i + sx] != FREE || g.state[(j + sy) * g.nx + i] != FREE)
              continue;
          } else if (Math.abs(dx) == 2) {
            if (g.state[j * g.nx + i + sx] != FREE || g.state[(j + sy) * g.nx + i + sx] != FREE)
              continue;
          } else {
            if (g.state[(j + sy) * g.nx + i] != FREE || g.state[(j + sy) * g.nx + i + sx] != FREE)
              continue;
          }
        }
        float nd = (float) (d + len[m]);
        if (nd < f.d(n)) {
          f.set(n, nd, (byte) m);
          f.jumpFrom.remove(n);
          f.extra.remove(n);
          heap.push(nd, n);
        }
      }
      if (g.strip[c]) {
        Jumps jumps = g.jumps(c);
        int[] to = jumps.to;
        float[] w = jumps.cost, sp = jumps.span;
        for (int k = 0; k < to.length; k++) {
          if (seed) {
            Coordinate a = g.center(c), b = g.center(to[k]);
            if ((b.x - a.x) * ux + (b.y - a.y) * uy < 0) continue;
          }
          if (window != null) {
            int li = to[k] % g.nx, lj = to[k] / g.nx;
            if (li < window[0] || lj < window[1] || li > window[2] || lj > window[3]) continue;
          }
          float nd = d + w[k];
          if (nd < f.d(to[k]) && validJump(g, jumps, c, k)) {
            f.set(to[k], nd, JUMP);
            f.jumpFrom.put(to[k], c);
            f.extra.put(to[k], sp[k]);
            heap.push(nd, to[k]);
          }
        }
      }
    }
    return f;
  }

  /**
   * (K-1) x special length of each step of a traced path (element i: step between cells i-1 and i).
   */
  private float[] stepExtras(Field f, int[] cells) {
    float[] ex = new float[cells.length];
    for (int i = 1; i < cells.length; i++) {
      if (f.pred(cells[i - 1]) == cells[i]) ex[i] = f.extra.getOrDefault(cells[i - 1], 0f);
      else if (f.pred(cells[i]) == cells[i - 1]) ex[i] = f.extra.getOrDefault(cells[i], 0f);
    }
    return ex;
  }

  private static double sum(float[] a, int from, int to) {
    double s = 0;
    for (int i = from; i < to; i++) s += a[i];
    return s;
  }

  /** Cells from the given cell back to the terminal seed (first element = given cell). */
  private int[] trace(Field f, int c) {
    List<Integer> out = new ArrayList<>();
    while (c >= 0) {
      out.add(c);
      c = f.pred(c);
    }
    int[] a = new int[out.size()];
    for (int i = 0; i < a.length; i++) a[i] = out.get(i);
    return a;
  }

  // ------------------------------------------------------------------ terminals and roots
  final class Terminal {
    final Dataset.Demand demand;
    final Coordinate p;
    final double flow;
    final int dn;
    Coordinate port;
    double ux, uy;
    int[] seedCells;
    double[] seedS;
    Field field;
    boolean usable;

    Terminal(Dataset.Demand d) {
      demand = d;
      p = Geo.canonical(d.terminals.get(0).geometry.getCoordinate());
      flow = d.flow.doubleValue();
      dn = Catalog.base(d.flow).dn;
    }

    void entrance(Grid g, Coordinate port) {
      this.port = port;
      double s0 = p.distance(port);
      ux = (port.x - p.x) / s0;
      uy = (port.y - p.y) / s0;
      List<Integer> cells = new ArrayList<>();
      List<Double> ss = new ArrayList<>();
      Set<Integer> seen = new HashSet<>();
      boolean started = false;
      for (double s = s0; s < s0 + 60; s += g.h / 2) {
        int c = g.cell(p.x + ux * s, p.y + uy * s);
        if (c < 0) break;
        // only a rounding-size gap of hard-zone cells may be skipped; never run along a special
        // band
        if (g.state[c] != FREE) {
          if (started || g.state[c] == BAND || s > s0 + .75) break;
          continue;
        }
        started = true;
        if (seen.add(c)) {
          cells.add(c);
          ss.add(s);
        }
      }
      seedCells = new int[cells.size()];
      seedS = new double[cells.size()];
      for (int i = 0; i < seedCells.length; i++) {
        seedCells[i] = cells.get(i);
        seedS[i] = ss.get(i);
      }
      usable = seedCells.length > 0;
    }

    boolean free;

    /** A point outside every building: the search starts at the point itself, no entrance ray. */
    void freePoint(Grid g) {
      free = true;
      port = p;
      ux = 0;
      uy = 0;
      int c = g.cell(p.x, p.y);
      usable = c >= 0 && g.state[c] == FREE;
      seedCells = usable ? new int[] {c} : new int[0];
      seedS = usable ? new double[] {p.distance(g.center(c))} : new double[0];
    }

    Coordinate rayPoint(int seedCell) {
      if (free) return p;
      for (int i = 0; i < seedCells.length; i++)
        if (seedCells[i] == seedCell)
          return new Coordinate(p.x + ux * seedS[i], p.y + uy * seedS[i]);
      return null;
    }
  }

  static final class RootSite {
    final Existing.Root root;
    final Coordinate p;
    final boolean chamber;
    final int existingDn;
    final int freeRays;

    RootSite(Existing.Root r, boolean chamber, int freeRays) {
      root = r;
      p = r.p;
      this.chamber = chamber;
      existingDn = r.existingDn;
      this.freeRays = freeRays;
    }
  }

  static final class Seed {
    final RootSite site;
    final int cell;
    final double ray;
    final double rx, ry;
    final Coordinate landing;

    Seed(RootSite s, int c, double ray, double rx, double ry, Coordinate landing) {
      site = s;
      cell = c;
      this.ray = ray;
      this.rx = rx;
      this.ry = ry;
      this.landing = landing;
    }
  }

  private final Map<Integer, List<Seed>> seedsByDn;

  /** Root seeds of a DN, always built on the shared raster (never on a reroute copy). */
  private List<Seed> seeds(Grid g) {
    Grid base = grid(g.dn);
    synchronized (seedsByDn) {
      List<Seed> s = seedsByDn.get(g.dn);
      if (s == null) {
        s = new ArrayList<>();
        buildSeeds(base, s);
        seedsByDn.put(g.dn, s);
      }
      return s;
    }
  }

  private void buildSeeds(Grid g, List<Seed> seeds) {
    double radius = rules.number("geometry.chamber_radius_m");
    Map<String, RootSite> sites = new TreeMap<>();
    // one shared rule with the legacy generator and the verifier (snapped chambers, 10 m + margin)
    for (Dataset.Feature c : existing.availableChambers()) {
      int deg = existing.degree(c);
      RootSite s =
          new RootSite(
              new Existing.Root(c, c.geometry.getCoordinate(), 0, existing.chamberDn(c), deg),
              true,
              4 - deg);
      sites.put(s.root.key, s);
      rays(g, s, null, seeds);
    }
    for (Dataset.Feature f : data.networks) {
      if (!existing.connected(f)) continue;
      LineString line = (LineString) f.geometry;
      LengthIndexedLine li = new LengthIndexedLine(line);
      double total = line.getLength();
      for (double s = 0; s <= total + 1e-9; s += Math.max(g.h, .5)) {
        Coordinate q = li.extractPoint(Math.min(s, total));
        if (!region.contains(q)) continue;
        if (existing.forcesChamber(q)) continue;
        // a tie-in point inside the clearance zone of another existing line would violate it on the
        // first run
        boolean crowded = false;
        for (Dataset.Feature other : data.networks) {
          if (other == f) continue;
          double dd = other.geometry.distance(Geo.point(q));
          if (dd > rules.number("geometry.topology_snap_m")
              && dd < 1.0 + Catalog.pipe(Math.max(50, other.dn())).width / 2 + 0.9) {
            crowded = true;
            break;
          }
        }
        if (crowded && Boolean.getBoolean("heatroute.avoidCrowdedTieIns"))
          continue; // measured: excluding them costs 0.2 S on the official set
        int degree = 0, dn = f.dn();
        double snap = rules.number("geometry.topology_snap_m");
        for (Dataset.Feature other : data.networks)
          if (other.geometry.distance(Geo.point(q)) <= snap) {
            double along = new LengthIndexedLine(other.geometry).project(q);
            degree += along < snap || other.geometry.getLength() - along < snap ? 1 : 2;
            dn = Math.max(dn, other.dn());
          }
        if (degree >= 4) continue;
        Existing.Root r = new Existing.Root(f, q, s, dn, degree);
        RootSite site = new RootSite(r, false, 4 - degree);
        if (sites.containsKey(r.key)) continue;
        sites.put(r.key, site);
        Coordinate a = li.extractPoint(Math.max(0, s - .5)),
            b = li.extractPoint(Math.min(total, s + .5));
        rays(g, site, new Coordinate(b.x - a.x, b.y - a.y), seeds);
      }
    }
  }

  private void rays(Grid g, RootSite site, Coordinate tangent, List<Seed> seeds) {
    double base = tangent == null ? 0 : Math.atan2(tangent.y, tangent.x);
    double[] angles =
        tangent == null
            ? new double[] {
              0, 22.5, 45, 67.5, 90, 112.5, 135, 157.5, 180, 202.5, 225, 247.5, 270, 292.5, 315,
              337.5
            }
            : new double[] {45, 67.5, 90, 112.5, 135, 225, 247.5, 270, 292.5, 315};
    for (double a : angles) {
      double ang = base + Math.toRadians(a), ux = Math.cos(ang), uy = Math.sin(ang);
      for (double t = .5; t < 14; t += g.h / 2) {
        Coordinate q = new Coordinate(site.p.x + ux * t, site.p.y + uy * t);
        int c = g.cell(q.x, q.y);
        if (c < 0) break;
        if (g.state[c] == BLOCK) break;
        if (g.state[c] == FREE) {
          seeds.add(new Seed(site, c, t, ux, uy, q));
          break;
        }
      }
    }
  }

  // ------------------------------------------------------------------ forest model
  static final int ROOT = 0, BRANCH = 1, LEAF = 2;

  static final class PNode {
    int kind;
    Coordinate p;
    int cell = -1;
    RootSite site;
    Terminal terminal;
    PEdge in;
    final List<PEdge> out = new ArrayList<>();
  }

  static final class PEdge {
    PNode from, to;
    int[] cells;
    float[] ex;
    double len, extra;
    Coordinate first,
        last; // first/last: exact coordinates next to the nodes (root landing / terminal ray point)
    Seed seed;
    int dn, plannedDn;
    double flow;
  }

  static final class Forest {
    /** Restart that built this forest (-1 unknown); its polish stream is derived from it. */
    int origin = -1;

    /** Raster turn limit of the restart family that built it. */
    double turn = 75;

    final List<PNode> nodes = new ArrayList<>();
    final List<PEdge> edges = new ArrayList<>();
    final Map<String, PNode> roots = new HashMap<>();
    final Set<Terminal> connected = new LinkedHashSet<>();

    Forest copy() {
      Forest f = new Forest();
      f.origin = origin;
      f.turn = turn;
      Map<PNode, PNode> m = new IdentityHashMap<>();
      for (PNode n : nodes) {
        PNode c = new PNode();
        c.kind = n.kind;
        c.p = n.p;
        c.cell = n.cell;
        c.site = n.site;
        c.terminal = n.terminal;
        m.put(n, c);
        f.nodes.add(c);
      }
      for (PEdge e : edges) {
        PEdge c = new PEdge();
        c.from = m.get(e.from);
        c.to = m.get(e.to);
        c.cells = e.cells;
        c.ex = e.ex;
        c.len = e.len;
        c.extra = e.extra;
        c.first = e.first;
        c.last = e.last;
        c.seed = e.seed;
        c.dn = e.dn;
        c.plannedDn = e.plannedDn;
        c.flow = e.flow;
        c.from.out.add(c);
        c.to.in = c;
        f.edges.add(c);
      }
      for (Map.Entry<String, PNode> r : roots.entrySet())
        f.roots.put(r.getKey(), m.get(r.getValue()));
      f.connected.addAll(connected);
      return f;
    }
  }

  private double[] measure(Grid g, int[] cells, Coordinate first, Coordinate last) {
    double len = 0, extra = 0;
    List<Coordinate> pts = new ArrayList<>();
    if (first != null) pts.add(first);
    for (int c : cells) pts.add(g.center(c));
    if (last != null) pts.add(last);
    for (int i = 1; i < pts.size(); i++) {
      double d = pts.get(i - 1).distance(pts.get(i));
      len += d;
    }
    return new double[] {len, extra};
  }

  /** Flows, minimum DN with the per-path length rule, total approximate score. */
  private double score(Forest f) {
    double total = 0;
    for (PEdge e : f.edges) {
      e.flow = 0;
    }
    for (PNode n : f.nodes)
      if (n.kind == LEAF) {
        double q = n.terminal.flow;
        for (PEdge e = n.in; e != null; e = e.from.in) e.flow += q;
      }
    // Same bottom-up sizing as PathDiameters: sibling lengths are never added.
    for (PNode n : f.nodes) if (n.kind == ROOT) for (PEdge e : n.out) size(e);
    for (PEdge e : f.edges) {
      total += perMetre(e.dn) * e.len + wc() * Catalog.pipe(e.dn).newPrice.doubleValue() * e.extra;
      if (!fits(e)) return Double.POSITIVE_INFINITY;
    }
    for (PNode n : f.nodes) {
      if (n.kind == BRANCH) {
        int dn = n.in.dn;
        for (PEdge e : n.out) dn = Math.max(dn, e.dn);
        total += money(Catalog.chamber(dn).doubleValue());
      } else if (n.kind == ROOT) {
        if (n.site.chamber) total += money(rules.number("cost.tie_in_rub")) * n.out.size();
        else {
          int dn = n.site.existingDn;
          for (PEdge e : n.out) dn = Math.max(dn, e.dn);
          total += money(Catalog.chamber(dn).doubleValue());
        }
      }
    }
    for (Terminal t : terminals)
      if (!f.connected.contains(t)) total += money(rules.penalty(t.demand.flow).doubleValue());
    return total;
  }

  private double size(PEdge e) {
    int minimum = Catalog.base(BigDecimal.valueOf(Math.round(e.flow * 1e6) / 1e6)).dn;
    Map<PEdge, Double> tails = new LinkedHashMap<>();
    for (PEdge c : e.to.out) {
      tails.put(c, size(c));
      minimum = Math.max(minimum, c.dn);
    }
    for (Catalog.Pipe pipe : Catalog.PIPES)
      if (pipe.dn >= minimum) {
        double tail = 0;
        for (Map.Entry<PEdge, Double> c : tails.entrySet())
          if (c.getKey().dn == pipe.dn) tail = Math.max(tail, c.getValue());
        if (e.len + tail <= pipe.limit + 1e-6) {
          e.dn = pipe.dn;
          return e.len + tail;
        }
      }
    e.dn = 1400;
    return e.len;
  }

  /**
   * Raster check that an edge planned for a smaller DN also keeps the clearance of its final DN.
   */
  private boolean fits(PEdge e) {
    if (e.dn <= e.plannedDn) return true;
    Grid g = grid(e.dn);
    int skipStart = e.from.kind == ROOT ? 12 : 3, skipEnd = e.to.kind == LEAF ? 3 : 3;
    for (int k = skipStart; k < e.cells.length - skipEnd; k++)
      if (g.state[e.cells[k]] != FREE) return false;
    return true;
  }

  // ------------------------------------------------------------------ insertion
  private List<Terminal> terminals = new ArrayList<>();
  private Grid plan;

  private static final class Option {
    double lower;
    int kind;
    PEdge edge;
    int index;
    PNode node;
    Seed seed;
    int attachCell;
    double value;
  }

  /** Cells occupied by the forest, dilated by about one metre. */
  /** Cells within 1.2 m of a pipe of the forest (a bit set: rebuilt for every insertion). */
  private BitSet occupancy(Forest f, Grid g) {
    BitSet owner = new BitSet(g.nx * g.ny);
    int r = (int) Math.ceil(1.2 / g.h);
    for (int id = 0; id < f.edges.size(); id++) {
      PEdge e = f.edges.get(id);
      List<Integer> cells = new ArrayList<>();
      for (int c : expand(g, e.cells)) cells.add(c);
      // straight root prefix and terminal entrance run are part of the pipe too
      if (e.first != null && e.cells.length > 0)
        segmentCells(g, e.first, g.center(e.cells[0]), cells);
      if (e.to.kind == LEAF && e.last != null) segmentCells(g, e.last, e.to.terminal.p, cells);
      for (int c : cells) {
        int i = c % g.nx, j = c / g.nx;
        for (int dj = -r; dj <= r; dj++)
          for (int di = -r; di <= r; di++) {
            int ii = i + di, jj = j + dj;
            if (ii < 0 || jj < 0 || ii >= g.nx || jj >= g.ny) continue;
            if (di * di + dj * dj <= r * r) owner.set(jj * g.nx + ii);
          }
      }
    }
    return owner;
  }

  private static void segmentCells(Grid g, Coordinate a, Coordinate b, List<Integer> out) {
    double len = a.distance(b);
    int n = (int) Math.ceil(len / (g.h / 2));
    for (int k = 0; k <= n; k++) {
      double t = n == 0 ? 0 : (double) k / n;
      int c = g.cell(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t);
      if (c >= 0) out.add(c);
    }
  }

  private int[] expand(Grid g, int[] cells) {
    List<Integer> out = new ArrayList<>();
    for (int k = 0; k < cells.length; k++) {
      if (k > 0) {
        int a = cells[k - 1], b = cells[k];
        int ai = a % g.nx, aj = a / g.nx, bi = b % g.nx, bj = b / g.nx;
        int steps = Math.max(Math.abs(ai - bi), Math.abs(aj - bj));
        for (int s = 1; s < steps; s++) {
          out.add((aj + (bj - aj) * s / steps) * g.nx + ai + (bi - ai) * s / steps);
        }
      }
      out.add(cells[k]);
    }
    int[] a = new int[out.size()];
    for (int i = 0; i < a.length; i++) a[i] = out.get(i);
    return a;
  }

  /**
   * Path from the attachment cell to the terminal must stay clear of the forest except near the
   * attachment.
   */
  private boolean clearPath(Grid g, int[] path, BitSet owner, Coordinate attach) {
    for (int c : expand(g, path)) {
      if (!owner.get(c)) continue;
      if (g.center(c).distance(attach) > 2.6) return false;
    }
    return true;
  }

  private Coordinate direction(Grid g, int[] cells, int from, int step) {
    int a = Math.max(0, Math.min(cells.length - 1, from)),
        b = Math.max(0, Math.min(cells.length - 1, from + step));
    if (a == b) return null;
    Coordinate p = g.center(cells[a]), q = g.center(cells[b]);
    return new Coordinate(q.x - p.x, q.y - p.y);
  }

  private static double angle(Coordinate u, Coordinate v) {
    return Math.toDegrees(
        Math.acos(
            Math.max(
                -1,
                Math.min(
                    1, (u.x * v.x + u.y * v.y) / (Math.hypot(u.x, u.y) * Math.hypot(v.x, v.y))))));
  }

  private Forest insert(Forest base, Terminal t, double noise) {
    return insert(base, t, noise, occupancy(base, plan), score(base));
  }

  private Forest insert(Forest base, Terminal t, double noise, BitSet owner, double baseValue) {
    Grid g = plan;
    Field f = t.field;
    if (f == null || !t.usable) return null;
    List<Option> options = new ArrayList<>();
    double unit = perMetre(t.dn);
    // new root line (or another line from an already used root)
    Map<String, Integer> usedRays = new HashMap<>();
    for (PNode r : base.roots.values()) usedRays.put(r.site.root.key, r.out.size());
    for (Seed s : seeds(f.g)) {
      if (!bannedRoots.isEmpty() && rootBanned(s.site)) continue;
      float d = f.d(s.cell);
      if (!Float.isFinite(d)) continue;
      int used = usedRays.getOrDefault(s.site.root.key, 0);
      if (used >= s.site.freeRays) continue;
      double fixed =
          s.site.chamber
              ? money(rules.number("cost.tie_in_rub"))
              : used > 0
                  ? 0
                  : money(Catalog.chamber(Math.max(s.site.existingDn, t.dn)).doubleValue());
      Option o = new Option();
      o.kind = ROOT;
      o.seed = s;
      o.attachCell = s.cell;
      o.lower = (d + s.ray) * unit + fixed;
      options.add(o);
    }
    // branch on an edge interior or at an existing branch chamber
    for (PEdge e : base.edges) {
      for (int k = 2; k < e.cells.length - 2; k++) {
        float d = f.d(e.cells[k]);
        if (!Float.isFinite(d)) continue;
        Option o = new Option();
        o.kind = BRANCH;
        o.edge = e;
        o.index = k;
        o.attachCell = e.cells[k];
        o.lower = d * unit + money(3000000);
        options.add(o);
      }
    }
    for (PNode n : base.nodes)
      if (n.kind == BRANCH && n.out.size() + 1 < 4) {
        float d = f.d(n.cell);
        if (!Float.isFinite(d)) continue;
        Option o = new Option();
        o.kind = LEAF;
        o.node = n;
        o.attachCell = n.cell;
        o.lower = d * unit;
        options.add(o);
      }
    if (!banned.isEmpty()) options.removeIf(o -> o.kind != ROOT && banned.get(o.attachCell));
    if (noise > 0) for (Option o : options) o.lower *= 1 + noise * random.nextDouble();
    options.sort(Comparator.comparingDouble(o -> o.lower));
    Forest best = null;
    double bestValue = Double.POSITIVE_INFINITY;
    int evaluated = 0;
    double baseWithoutT = baseValue - money(rules.penalty(t.demand.flow).doubleValue());
    int cap = Math.min(60, 12 + options.size() / 40);
    for (Option o : options) {
      if (evaluated >= cap) break;
      if (best != null && o.lower > bestValue - baseWithoutT) break;
      int[] path = trace(f, o.attachCell); // attach .. terminal seed
      Coordinate at =
          o.kind == ROOT ? o.seed.landing : o.kind == LEAF ? o.node.p : g.center(o.attachCell);
      if (!clearPath(g, path, owner, at)) continue;
      if (!admissible(base, t, o, path, owner, at, g)) continue;
      evaluated++;
      Forest trial = apply(base, t, o, path);
      double v = score(trial);
      if (!Double.isFinite(v)) continue;
      if (v < bestValue) {
        bestValue = v;
        best = trial;
      }
    }
    return best;
  }

  /**
   * Clearance of the entrance run and the turn/angle rules of an insertion path (attach .. seed).
   */
  private boolean admissible(
      Forest base, Terminal t, Option o, int[] path, BitSet owner, Coordinate at, Grid g) {
    {
      Coordinate rp = t.rayPoint(path[path.length - 1]);
      if (rp != null) {
        List<Integer> run = new ArrayList<>();
        segmentCells(g, rp, t.p, run);
        boolean hit = false;
        for (int c : run)
          if (owner.get(c) && g.center(c).distance(at) > 2.6) {
            hit = true;
            break;
          }
        if (hit) return false;
      }
    }
    Coordinate out = direction(g, path, 0, Math.min(16, path.length - 1));
    if (o.kind == ROOT) {
      if (out != null && angle(new Coordinate(o.seed.rx, o.seed.ry), out) > 90) return false;
      boolean clash = false;
      PNode r = base.roots.get(o.seed.site.root.key);
      if (r != null)
        for (PEdge e : r.out)
          if (angle(new Coordinate(e.seed.rx, e.seed.ry), new Coordinate(o.seed.rx, o.seed.ry))
              < 30) clash = true;
      if (clash) return false;
    } else if (o.kind == BRANCH) {
      Coordinate trunk =
          direction(g, o.edge.cells, o.index, Math.min(16, o.edge.cells.length - 1 - o.index));
      if (out == null || trunk == null || angle(trunk, out) > maxTurn || angle(trunk, out) < 30)
        return false;
      Coordinate back = direction(g, o.edge.cells, o.index, -Math.min(6, o.index));
      if (back != null && angle(back, out) < 20) return false;
    } else {
      Coordinate incoming =
          direction(
              g,
              o.node.in.cells,
              o.node.in.cells.length - 1,
              -Math.min(16, o.node.in.cells.length - 1));
      if (out == null || incoming == null) return false;
      Coordinate trunk = new Coordinate(-incoming.x, -incoming.y);
      if (angle(trunk, out) > maxTurn) return false;
      boolean clash = false;
      for (PEdge e : o.node.out) {
        Coordinate d = direction(g, e.cells, 0, Math.min(6, e.cells.length - 1));
        if (d != null && angle(d, out) < 20) clash = true;
      }
      if (clash) return false;
    }
    return true;
  }

  private Forest apply(Forest base, Terminal t, Option o, int[] path) {
    Grid g = plan;
    Forest f = base.copy();
    // map option objects into the copy
    PNode attach;
    int[] cellsToTerminal = path; // attach .. seed
    if (o.kind == ROOT) {
      PNode r = f.roots.get(o.seed.site.root.key);
      if (r == null) {
        r = new PNode();
        r.kind = ROOT;
        r.site = o.seed.site;
        r.p = o.seed.site.p;
        f.nodes.add(r);
        f.roots.put(r.site.root.key, r);
      }
      attach = r;
    } else if (o.kind == LEAF) {
      attach = f.nodes.get(base.nodes.indexOf(o.node));
    } else {
      PEdge e = f.edges.get(base.edges.indexOf(o.edge));
      PNode b = new PNode();
      b.kind = BRANCH;
      b.cell = e.cells[o.index];
      b.p = g.center(b.cell);
      f.nodes.add(b);
      PEdge tail = new PEdge();
      tail.plannedDn = e.plannedDn;
      tail.from = b;
      tail.to = e.to;
      tail.cells = Arrays.copyOfRange(e.cells, o.index, e.cells.length);
      tail.ex = Arrays.copyOfRange(e.ex, o.index, e.ex.length);
      tail.ex[0] = 0;
      tail.last = e.last;
      tail.seed = null;
      e.to.in = tail;
      b.out.add(tail);
      e.cells = Arrays.copyOfRange(e.cells, 0, o.index + 1);
      e.ex = Arrays.copyOfRange(e.ex, 0, o.index + 1);
      e.to = b;
      e.last = null;
      b.in = e;
      double[] m = measure(g, e.cells, e.first, b.p);
      e.len = m[0];
      e.extra = sum(e.ex, 0, e.ex.length);
      double[] n = measure(g, tail.cells, b.p, tail.last);
      tail.len = n[0] + (tail.to.kind == LEAF ? tail.last.distance(tail.to.p) : 0);
      tail.extra = sum(tail.ex, 0, tail.ex.length);
      f.edges.add(tail);
      attach = b;
    }
    PNode leaf = new PNode();
    leaf.kind = LEAF;
    leaf.terminal = t;
    leaf.p = t.p;
    f.nodes.add(leaf);
    PEdge e = new PEdge();
    e.from = attach;
    e.to = leaf;
    e.plannedDn = t.dn;
    int[] cells = new int[cellsToTerminal.length];
    for (int i = 0; i < cells.length; i++) cells[i] = cellsToTerminal[i];
    e.cells = cells;
    e.ex = stepExtras(t.field, cells);
    e.last = t.rayPoint(cells[cells.length - 1]);
    if (o.kind == ROOT) {
      e.seed = o.seed;
      e.first = o.seed.site.p;
    } else e.first = attach.p;
    double[] m = measure(t.field.g, e.cells, e.first, e.last);
    e.len = m[0] + (e.last == null ? 0 : e.last.distance(t.p));
    e.extra = sum(e.ex, 0, e.ex.length);
    attach.out.add(e);
    leaf.in = e;
    f.edges.add(e);
    f.connected.add(t);
    return f;
  }

  private Forest remove(Forest base, Terminal t) {
    Forest f = base.copy();
    PNode leaf = null;
    for (PNode n : f.nodes) if (n.kind == LEAF && n.terminal == t) leaf = n;
    if (leaf == null) return f;
    PEdge e = leaf.in;
    PNode parent = e.from;
    parent.out.remove(e);
    f.edges.remove(e);
    f.nodes.remove(leaf);
    f.connected.remove(t);
    if (parent.kind == ROOT) {
      if (parent.out.isEmpty()) {
        f.nodes.remove(parent);
        f.roots.remove(parent.site.root.key);
      }
    } else if (parent.kind == BRANCH && parent.out.size() == 1) {
      PEdge up = parent.in, down = parent.out.get(0);
      int[] cells = new int[up.cells.length + down.cells.length - 1];
      System.arraycopy(up.cells, 0, cells, 0, up.cells.length);
      System.arraycopy(down.cells, 1, cells, up.cells.length, down.cells.length - 1);
      float[] ex = new float[cells.length];
      System.arraycopy(up.ex, 0, ex, 0, up.ex.length);
      System.arraycopy(down.ex, 1, ex, up.ex.length, down.ex.length - 1);
      up.ex = ex;
      up.cells = cells;
      up.plannedDn = Math.min(up.plannedDn, down.plannedDn);
      up.to = down.to;
      up.last = down.last;
      down.to.in = up;
      up.len += down.len;
      up.extra += down.extra;
      f.edges.remove(down);
      f.nodes.remove(parent);
    }
    return f;
  }

  // ------------------------------------------------------------------ main
  List<Network.Tree> solve() {
    return solveVariants(1).get(0);
  }

  private long start;

  /**
   * Up to count materially different variants: V2/V3 forbid the main tie-ins of V1. Only V1 may be
   * unverified.
   */
  List<List<Network.Tree>> solveVariants(int count) {
    prepare();
    List<List<Network.Tree>> out = new ArrayList<>();
    long first = deadline; // V1 always gets the full budget; alternatives have their own
    long variantBudget = (long) (rules.number("execution.variant_seconds") * 1e9);
    // deterministic effort: the configured restarts up to 20 points, proportionally fewer above
    // (never below 4)
    int configured = rules.integer("execution.planner_restarts");
    int restarts =
        Integer.getInteger(
            "heatroute.restarts",
            (deep ? 2 : 1)
                * Math.max(
                    4,
                    Math.min(
                        configured,
                        (int) Math.round(configured * 20.0 / Math.max(20, terminals.size())))));
    diagnostics.put("planned_restarts", restarts);
    Object[] v1 = searchVariant(restarts, first, "");
    if (v1[1] == null || count < 2) {
      out.add(castTrees(v1[0]));
      return out;
    }
    List<Candidate> pool = new ArrayList<>(castCandidates(v1[2]));
    Forest f1 = (Forest) v1[1];
    List<PNode> roots = new ArrayList<>(f1.roots.values());
    score(f1);
    roots.sort(
        Comparator.comparingDouble((PNode r) -> -r.out.stream().mapToDouble(e -> e.flow).sum()));
    // alternatives: searches that forbid the main roots of V1 (lighter but still deterministic
    // effort); they are independent, so they run side by side
    List<java.util.concurrent.Callable<Done<Object[]>>> alternatives = new ArrayList<>();
    for (int v = 2; v <= count && v - 2 < roots.size(); v++) {
      Coordinate avoid = roots.get(v - 2).site.p;
      String tag = "v" + v + "_";
      alternatives.add(
          () -> {
            GridPlanner w = worker(rng(tag));
            w.bannedRoots.clear();
            w.bannedRoots.add(avoid);
            w.polishRounds = 2;
            w.eliteLimit = 4;
            return w.done(
                w.searchVariant(3, fast ? System.nanoTime() + variantBudget : Long.MAX_VALUE, tag));
          });
    }
    List<Done<Object[]>> found = parallel(alternatives);
    for (Done<Object[]> d : found) pool.addAll(castCandidates(absorb(d)[2]));
    // B8: every verified forest of every search competes. V1 is the best of the whole pool (more
    // connected points first, then the exact S); V2 and V3 are the best ones whose grouping of
    // points under roots and chambers differs from the variants already taken (TZ: alternatives
    // differ in roots, grouping, corridor or connected set; diversity never worsens V1).
    List<Candidate> ranked = new ArrayList<>(pool);
    ranked.sort(
        Comparator.comparingInt((Candidate c) -> -c.forest.connected.size())
            .thenComparing(c -> c.exact));
    List<String> taken = new ArrayList<>();
    for (Candidate c : ranked) {
      if (taken.size() == count) break;
      String grouping = grouping(c.forest);
      if (taken.contains(grouping)) continue;
      taken.add(grouping);
      out.add(c.trees);
      diagnostics.put(
          "variant_" + taken.size() + "_source", c.source + " S " + c.exact.toPlainString());
    }
    if (out.isEmpty()) out.add(castTrees(v1[0]));
    diagnostics.put("verified_pool", pool.size());
    return out;
  }

  @SuppressWarnings("unchecked")
  private static List<Network.Tree> castTrees(Object o) {
    return (List<Network.Tree>) o;
  }

  @SuppressWarnings("unchecked")
  private static List<Candidate> castCandidates(Object o) {
    return (List<Candidate>) o;
  }

  /** Roots and, for every pipe, the set of points it serves: equal only for the same grouping. */
  private static String grouping(Forest f) {
    List<String> parts = new ArrayList<>();
    for (PEdge e : f.edges) {
      List<String> ids = new ArrayList<>();
      Deque<PNode> st = new ArrayDeque<>();
      st.add(e.to);
      while (!st.isEmpty()) {
        PNode n = st.pop();
        if (n.kind == LEAF) ids.add(n.terminal.demand.id);
        for (PEdge o : n.out) st.push(o.to);
      }
      Collections.sort(ids);
      parts.add((e.from.kind == ROOT ? e.from.site.root.key + ">" : "") + ids);
    }
    Collections.sort(parts);
    return parts.toString();
  }

  private final List<Coordinate> bannedRoots = new ArrayList<>();

  private boolean rootBanned(RootSite site) {
    for (Coordinate c : bannedRoots) if (c.distance(site.p) < 40) return true;
    return false;
  }

  private void prepare() {
    start = System.nanoTime();
    for (Dataset.Demand d : data.demands) terminals.add(new Terminal(d));
    Envelope env = new Envelope();
    for (Terminal t : terminals) env.expandToInclude(t.p);
    Envelope terminalsEnv = new Envelope(env);
    // include the existing network around the points; if it is farther than 600 m, reach the
    // nearest part of it plus 250 m
    double nearestNet = Double.POSITIVE_INFINITY;
    for (Terminal t : terminals)
      for (Dataset.Feature n : data.networks)
        if (existing.connected(n))
          nearestNet = Math.min(nearestNet, n.geometry.distance(Geo.point(t.p)));
    double reach = Math.max(600, Double.isFinite(nearestNet) ? nearestNet + 250 : 600);
    for (Dataset.Feature n : data.networks) {
      Envelope e = n.geometry.getEnvelopeInternal();
      Envelope near = new Envelope(terminalsEnv);
      near.expandBy(reach);
      if (e.intersects(near)) env.expandToInclude(e.intersection(near));
    }
    env.expandBy(120);
    region = env;
    // cells ~ heap budget / (fields x 5 B + rasters): never exceed roughly 45 % of the heap
    double perCell = 5.0 * (terminals.size() + 4) + 3 * 6 + 13;
    double cellBudget = Math.min(6e6, .45 * Runtime.getRuntime().maxMemory() / perCell);
    resolution = Math.max(.5, Math.sqrt(env.getArea() / cellBudget));
    // Beyond ~1 m cells the raster no longer resolves 5 m clearance corridors reliably: leave such
    // inputs to the vector engine.
    if (resolution > rules.number("execution.planner_max_cell_m"))
      throw new Failure(
          "PLANNER_SCALE",
          "Raster planner would need "
              + String.format(Locale.ROOT, "%.2f", resolution)
              + " m cells for "
              + terminals.size()
              + " points; using the vector engine");
    double total = data.totalFlow().min(Catalog.pipe(1400).capacity).doubleValue();
    int planDn = Catalog.base(BigDecimal.valueOf(total)).dn;
    // Plan with the widest pipe that stays in the 5 m building clearance class when possible.
    if (planDn >= 500) planDn = Math.max(400, Catalog.base(BigDecimal.valueOf(total / 2)).dn);
    plan = grid(planDn);
    diagnostics.put(
        "grid", Map.of("cells", plan.nx * plan.ny, "resolution_m", plan.h, "plan_dn", planDn));
    // Entrances: nearest feasible boundary first, the next valid entrance only as fallback. The
    // fields of the points are independent: one worker each.
    List<java.util.concurrent.Callable<Done<Terminal>>> jobs = new ArrayList<>();
    for (Terminal t : terminals)
      jobs.add(
          () -> {
            GridPlanner w = worker(random);
            w.prepareTerminal(t);
            return w.done(t);
          });
    for (Done<Terminal> d : parallel(jobs)) absorb(d);
    diagnostics.put("fields_seconds", (System.nanoTime() - start) / 1e9);
  }

  /** Entrance ray and search field of one point (runs on a worker during prepare). */
  private void prepareTerminal(Terminal t) {
    Grid own = grid(t.dn);
    if (data.hosts(t.p).isEmpty()) {
      t.freePoint(own);
      if (t.usable) t.field = search(own, t);
      if (t.field == null || !reachesRoot(t)) {
        t.usable = false;
        diagnostics.put(
            "terminal_" + t.demand.id + "_unreachable",
            "free point is not reachable on the raster");
      }
      diagnostics.merge("free_points", 1, (x, y) -> (Integer) x + (Integer) y);
      return;
    }
    // Only entrances the evaluator accepts: the shortest feasible inside run
    // (HOST_SHORTEST_FEASIBLE), ties within 1 cm.
    List<Coordinate> ports = entrances.ports(t.p, t.dn, false);
    double shortest =
        ports.isEmpty() ? Double.POSITIVE_INFINITY : entrances.insideLength(t.p, ports.get(0));
    for (Coordinate port : ports) {
      if (entrances.insideLength(t.p, port) > shortest + .011) break;
      t.entrance(own, port);
      if (t.usable) {
        t.field = search(own, t);
        if (reachesRoot(t)) break;
      }
    }
    if (t.field != null && !reachesRoot(t)) {
      t.usable = false;
      diagnostics.put(
          "terminal_" + t.demand.id + "_unreachable",
          "admissible entrance leads nowhere on the raster");
    } else if (t.field == null) {
      t.usable = false;
      diagnostics.put(
          "terminal_" + t.demand.id + "_unreachable",
          ports.isEmpty()
              ? "no admissible entrance: every straight final run from the host boundary is"
                  + " blocked (R02/R03)"
              : "the shortest admissible entrance run (R02) is blocked before it clears the"
                  + " neighbouring restrictions");
    }
  }

  /** Returns {trees, forest-if-verified-else-null}. */
  private Object[] searchVariant(int maxRestarts, long until, String tag) {
    long saved = deadline;
    deadline = until;
    try {
      return searchVariantCore(maxRestarts, tag);
    } finally {
      deadline = saved;
    }
  }

  private static final long POLISH = 1L << 40, PERTURB = 1L << 41;

  private static long mix(long z) {
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }

  /** A stream derived from the seed, the search tag and the given numbers (SplitMix64 mixing). */
  private Random rng(String tag, long... parts) {
    long h = rules.integer("execution.seed") * 0x9E3779B97F4A7C15L + tag.hashCode();
    for (long p : parts) h = mix(h + p);
    return new Random(mix(h));
  }

  /** One polished, vectorized and exactly scored forest. */
  private static final class Candidate {
    String source;
    Forest forest;
    List<Network.Tree> trees;
    String problem;
    BigDecimal exact;
    double keyBefore, keyAfter;
  }

  private Candidate evaluateCandidate(Forest elite, String tag, int index) {
    Candidate c = new Candidate();
    c.keyBefore = key(elite);
    debugTag = (tag.isEmpty() ? "v1_" : tag) + elite.origin;
    banned = new BitSet();
    long t0 = System.nanoTime();
    Forest f = polish(elite);
    diagnostics.merge(
        tag + "polish_seconds", (System.nanoTime() - t0) / 1e9, (x, y) -> (Double) x + (Double) y);
    if (index == 0) diagnostics.put(tag + "polish_gain", score(elite) - score(f));
    long t1 = System.nanoTime();
    List<List<Network.Tree>> built = new ArrayList<>();
    String problem = vectorizeChecked(f, built);
    List<Network.Tree> trees = built.get(0);
    for (int fix = 0; fix < 3 && problem != null && System.nanoTime() < deadline; fix++) {
      Forest repaired = repair(f);
      if (repaired == null) break;
      f = repaired;
      built.clear();
      problem = vectorizeChecked(f, built);
      trees = built.get(0);
      diagnostics.put(tag + "elite_" + index + "_repair_" + fix, problem == null ? "ok" : problem);
    }
    banned = new BitSet();
    diagnostics.merge(
        tag + "vector_seconds", (System.nanoTime() - t1) / 1e9, (x, y) -> (Double) x + (Double) y);
    c.source =
        (tag.isEmpty() ? "v1" : tag.substring(0, tag.length() - 1)) + " restart " + elite.origin;
    c.forest = f;
    c.trees = trees;
    c.problem = problem;
    c.keyAfter = key(f);
    if (problem == null) c.exact = exactScore(trees);
    List<String> missing = new ArrayList<>();
    for (Terminal t : terminals) if (!f.connected.contains(t)) missing.add(t.demand.id);
    diagnostics.put(
        tag + "candidate_" + index,
        String.format(
            Locale.ROOT,
            "restart %d key %.4f -> %.4f %s%s",
            elite.origin,
            c.keyBefore,
            c.keyAfter,
            problem == null ? "S " + c.exact : "rejected: " + problem,
            missing.isEmpty() ? "" : " unconnected " + missing));
    if (problem != null) diagnostics.put(tag + "elite_" + index + "_rejected", problem);
    return c;
  }

  /**
   * S of the vectorized trees as the exporter prices them (penalties included); null if they fail.
   */
  private BigDecimal exactScore(List<Network.Tree> trees) {
    try {
      List<Network.Tree> ev = new ArrayList<>();
      for (Network.Tree t : trees) ev.add(evaluator.evaluate(t, false));
      return new CurrentExporter(data, rules).export(ev, 1).score();
    } catch (Failure f) {
      return null;
    }
  }

  private static String signature(Forest f) {
    List<String> parts = new ArrayList<>();
    for (PEdge e : f.edges)
      parts.add(Arrays.toString(e.cells) + (e.seed == null ? "" : e.seed.site.root.key));
    Collections.sort(parts);
    return parts.toString();
  }

  private Object[] searchVariantCore(int maxRestarts, String tag) {
    // Portfolio: every turn family runs the whole search - restarts, exact candidates and its own
    // perturbation round - on the same streams, so each family gives exactly its stand-alone
    // result; the stages of all families run as one parallel batch and the best of all wins.
    // Restart r depends only on (seed, tag, r): the same forest for any restart count and schedule.
    int families = tag.isEmpty() ? turnFamilies.length : 1;
    List<java.util.concurrent.Callable<Done<Forest>>> jobs = new ArrayList<>();
    for (int k = 0; k < families; k++)
      for (int r = 0; r < maxRestarts; r++) {
        int restart = r;
        double turn = turnFamilies[k];
        jobs.add(
            () -> {
              if (System.nanoTime() >= deadline) return null;
              GridPlanner w = worker(rng(tag, restart));
              w.maxTurn = turn;
              w.checkCancel();
              Forest f = w.construct(restart == 0 ? 0 : .25);
              f = w.improve(f);
              f.origin = restart;
              f.turn = turn;
              return w.done(f);
            });
      }
    List<List<Forest>> elites = new ArrayList<>();
    for (int k = 0; k < families; k++) elites.add(new ArrayList<>());
    int restarts = 0;
    for (Done<Forest> d : parallel(jobs))
      if (d != null) {
        Forest f = absorb(d);
        elites.get(family(f)).add(f);
        restarts++;
      }
    diagnostics.put(tag + "restarts", restarts);
    diagnostics.put(tag + "restarts_seconds", (System.nanoTime() - start) / 1e9);
    if (restarts == 0) return new Object[] {null, null, new ArrayList<Candidate>()};
    // Clarification 15: more connected points always first, then the score.
    Comparator<Forest> byKey =
        Comparator.comparingDouble(this::key).thenComparingInt((Forest f) -> f.origin);
    // B1: the raster key of an unpolished forest predicts the result poorly. Measured on the
    // official set: the restart ranked 5th (one point still unconnected, key 1024) polishes to the
    // best S, 12.9935, while the top three end at 13.17-13.87. So every restart is polished,
    // vectorized and scored exactly (in parallel); -Dheatroute.candidates=K keeps only the best K
    // of every block of 12. Either way the candidate set for more restarts contains the one for
    // fewer, so more search never gives a worse result.
    int perBlock = Integer.getInteger("heatroute.candidates", 12);
    List<Forest> candidates = new ArrayList<>(), rest = new ArrayList<>();
    for (List<Forest> elite : elites) {
      List<Forest> own = new ArrayList<>();
      Set<String> seen = new HashSet<>();
      for (int b = 0; b < elite.size(); b += 12) {
        List<Forest> block = new ArrayList<>(elite.subList(b, Math.min(elite.size(), b + 12)));
        block.sort(byKey);
        int taken = 0;
        for (Forest f : block)
          if (taken < perBlock && seen.add(signature(f))) {
            own.add(f);
            taken++;
          }
      }
      own.sort(byKey);
      candidates.addAll(own);
      for (Forest f : elite) if (!own.contains(f)) rest.add(f);
    }
    rest.sort(byKey);
    Candidate best = null, last = null;
    int bestIndex = -1, index = 0;
    List<Candidate> verified = new ArrayList<>();
    for (Candidate c : evaluateAll(candidates, tag, 0)) {
      last = c;
      if (c.problem == null && c.exact != null) verified.add(c);
      if (c.problem == null && better(c, best)) {
        best = c;
        bestIndex = index;
      }
      index++;
    }
    // Iterated local search: the best verified forests of each family are perturbed (a bigger
    // ruin and recreate with noise), improved, polished and scored exactly. Every result joins the
    // pool, so this never worsens the choice; each job has its own stream (schedule-free).
    int rounds = Integer.getInteger("heatroute.perturbRounds", tag.isEmpty() ? (deep ? 2 : 1) : 0);
    for (int round = 0; round < rounds && !verified.isEmpty(); round++) {
      List<Candidate> seeds = new ArrayList<>();
      List<Integer> counts = new ArrayList<>();
      for (int k = 0; k < families; k++) {
        List<Candidate> own = new ArrayList<>();
        for (Candidate c : verified) if (family(c.forest) == k) own.add(c);
        own.sort(
            Comparator.comparingInt((Candidate c) -> -c.forest.connected.size())
                .thenComparing(c -> c.exact));
        List<Candidate> top = new ArrayList<>();
        Set<String> groups = new HashSet<>();
        for (Candidate c : own) if (top.size() < 3 && groups.add(grouping(c.forest))) top.add(c);
        int per =
            top.isEmpty()
                ? 0
                : Math.max(1, Integer.getInteger("heatroute.perturbations", 24) / top.size());
        for (Candidate c : top) {
          seeds.add(c);
          counts.add(per);
        }
      }
      for (Candidate c : perturbAll(seeds, counts, tag, round, index)) {
        index++;
        if (c.problem == null && c.exact != null) {
          verified.add(c);
          if (better(c, best)) {
            best = c;
            bestIndex = index - 1;
          }
        }
      }
    }
    // no candidate verified: the remaining elite in key order, the first verified wins (as before
    // B1); evaluated a pool-width batch at a time, which picks the same forest as one by one
    int tries =
        Math.min(restarts, Integer.getInteger("heatroute.eliteTries", eliteLimit) * families);
    while (best == null && index < tries && !rest.isEmpty() && System.nanoTime() < deadline) {
      int n = Math.min(Math.min(rest.size(), tries - index), POOL.getParallelism());
      List<Forest> batch = new ArrayList<>(rest.subList(0, n));
      rest.subList(0, n).clear();
      for (Candidate c : evaluateAll(batch, tag, index)) {
        if (best != null) break;
        last = c;
        if (c.problem == null) {
          best = c;
          bestIndex = index;
          if (c.exact != null) verified.add(c);
        }
        index++;
      }
    }
    Candidate chosen = best != null ? best : last;
    if (best != null) diagnostics.put(tag + "elite_rank_used", bestIndex);
    if (chosen != null) diagnostics.put(tag + "approximate_score", score(chosen.forest));
    diagnostics.put("total_seconds", (System.nanoTime() - start) / 1e9);
    diagnostics.put("rejected_jumps", rejectedJumps);
    return new Object[] {
      chosen == null ? null : chosen.trees, best != null ? best.forest : null, verified
    };
  }

  /** Index of the turn family that built the forest. */
  private int family(Forest f) {
    for (int k = 0; k < turnFamilies.length; k++) if (turnFamilies[k] == f.turn) return k;
    return 0;
  }

  /** More connected points first (clarification 15), then the smaller exact S. */
  private static boolean better(Candidate c, Candidate best) {
    return best == null
        || c.forest.connected.size() > best.forest.connected.size()
        || c.forest.connected.size() == best.forest.connected.size()
            && c.exact != null
            && (best.exact == null || c.exact.compareTo(best.exact) < 0);
  }

  /** Perturbations of each seed forest, then polish, vectorize and exact score; in job order. */
  private List<Candidate> perturbAll(
      List<Candidate> seeds, List<Integer> counts, String tag, int round, int firstIndex) {
    List<java.util.concurrent.Callable<Done<Candidate>>> jobs = new ArrayList<>();
    int index = firstIndex;
    for (int i = 0; i < seeds.size(); i++)
      for (int p = 0; p < counts.get(i); p++) {
        Candidate seed = seeds.get(i);
        int k = p, at = index++;
        jobs.add(
            () -> {
              GridPlanner w = worker(rng(tag, seed.forest.origin, PERTURB, round, k));
              w.maxTurn = seed.forest.turn;
              Forest f = w.perturb(seed.forest);
              if (f == null) return null;
              f.origin = seed.forest.origin;
              f.turn = seed.forest.turn;
              Candidate c = w.evaluateCandidate(f, tag, at);
              c.source += " perturbed " + round + "." + k;
              return w.done(c);
            });
      }
    List<Candidate> out = new ArrayList<>();
    for (Done<Candidate> d : parallel(jobs)) if (d != null) out.add(absorb(d));
    return out;
  }

  /** Ruin 3-6 neighbouring points of a random centre and recreate them greedily with noise. */
  private Forest perturb(Forest f) {
    List<Terminal> in = new ArrayList<>(f.connected);
    if (in.size() < 4) return null;
    Terminal centre = in.get(random.nextInt(in.size()));
    in.sort(Comparator.comparingDouble(x -> x.p.distance(centre.p)));
    int m = Math.min(in.size() - 1, 3 + random.nextInt(4));
    Forest ruined = f;
    for (int i = 0; i < m; i++) ruined = remove(ruined, in.get(i));
    List<Terminal> back = new ArrayList<>(in.subList(0, m));
    Collections.shuffle(back, random);
    Forest rebuilt = ruined;
    for (Terminal t : back) {
      Forest with = insert(rebuilt, t, .25);
      if (with != null) rebuilt = with;
    }
    return improve(rebuilt);
  }

  /** Polish, vectorize and score the forests in parallel; results in input order. */
  private List<Candidate> evaluateAll(List<Forest> forests, String tag, int firstIndex) {
    List<java.util.concurrent.Callable<Done<Candidate>>> jobs = new ArrayList<>();
    for (int i = 0; i < forests.size(); i++) {
      Forest f = forests.get(i);
      int index = firstIndex + i;
      jobs.add(
          () -> {
            if (index > 0 && System.nanoTime() > deadline) return null;
            GridPlanner w = worker(rng(tag, f.origin, POLISH));
            w.maxTurn = f.turn;
            return w.done(w.evaluateCandidate(f, tag, index));
          });
    }
    List<Candidate> out = new ArrayList<>();
    for (Done<Candidate> d : parallel(jobs)) if (d != null) out.add(absorb(d));
    return out;
  }

  private final List<PEdge> culprits = new ArrayList<>();
  private BitSet banned = new BitSet();

  /**
   * Re-plan the terminals below a problematic edge with attachments near its start chamber
   * forbidden.
   */
  private Forest repair(Forest f) {
    if (culprits.isEmpty()) return null;
    // A wide ban can leave a moved point nowhere to attach: then the same culprits get narrower
    // bans (the overlap of two tube pairs is a few metres at most).
    BitSet before = (BitSet) banned.clone();
    for (double radius : new double[] {12, 6, 3}) {
      Forest r = repair(f, radius);
      if (r != null) return r;
      banned = (BitSet) before.clone();
    }
    return null;
  }

  private Forest repair(Forest f, double radius) {
    Grid g = plan;
    Forest r = f;
    Set<Terminal> moved = new LinkedHashSet<>();
    for (PEdge e : culprits) {
      Coordinate at = e.from.kind == ROOT ? e.from.site.p : e.from.p;
      int c = g.cell(at.x, at.y);
      if (c < 0) continue;
      int rad = (int) Math.ceil(radius / g.h), i0 = c % g.nx, j0 = c / g.nx;
      for (int dj = -rad; dj <= rad; dj++)
        for (int di = -rad; di <= rad; di++) {
          int ii = i0 + di, jj = j0 + dj;
          if (ii < 0 || jj < 0 || ii >= g.nx || jj >= g.ny || di * di + dj * dj > rad * rad)
            continue;
          banned.set(jj * g.nx + ii);
        }
      Deque<PNode> st = new ArrayDeque<>();
      st.add(e.to);
      while (!st.isEmpty()) {
        PNode n = st.pop();
        if (n.kind == LEAF) moved.add(n.terminal);
        for (PEdge o : n.out) st.push(o.to);
      }
    }
    for (Terminal t : moved) r = remove(r, t);
    List<Terminal> back = new ArrayList<>(moved);
    while (!back.isEmpty()) {
      Forest best = null;
      Terminal chosen = null;
      double bv = Double.POSITIVE_INFINITY;
      BitSet owner = occupancy(r, plan);
      double rv = score(r);
      for (Terminal t : back) {
        Forest trial = insert(r, t, 0, owner, rv);
        if (trial == null) continue;
        double v = score(trial) - (rv - money(rules.penalty(t.demand.flow).doubleValue()));
        if (v < bv) {
          bv = v;
          best = trial;
          chosen = t;
        }
      }
      if (best == null) return null;
      r = best;
      back.remove(chosen);
    }
    return improve(r);
  }

  private double key(Forest f) {
    return score(f) + 1000.0 * (terminals.size() - f.connected.size());
  }

  /** Same acceptance as the caller: every tree evaluates and trees are mutually compatible. */
  private int lastVectorFailures;

  private String exactProblem(List<Network.Tree> trees) {
    // a chain that could not be vectorised would silently disconnect its points (clarification 15)
    if (lastVectorFailures > 0)
      return "VECTOR_FAILURE: " + lastVectorFailures + " chain(s) could not be built exactly";
    List<Network.Tree> ok = new ArrayList<>();
    try {
      for (Network.Tree t : trees) {
        Network.Tree e = evaluator.evaluate(t, false);
        if (!evaluator.compatible(ok, e)) {
          locate(trees);
          return "NEW_INTERSECTION between trees";
        }
        ok.add(e);
      }
    } catch (Failure f) {
      locate(trees);
      return f.code + ": " + f.getMessage();
    }
    return null;
  }

  /**
   * The evaluator reports only its first failure: re-check every edge (its DN and the next one) to
   * find repairable culprits.
   */
  private void locate(List<Network.Tree> trees) {
    // The evaluator failed at the DN it sized from exact lengths, which can be one step above the
    // planned DN:
    // check every edge at that DN, and check junctions the way the evaluator does, before any
    // guessing.
    Map<Network.Edge, Integer> sizedDn = new IdentityHashMap<>();
    for (Network.Tree t : trees)
      try {
        Network.Tree s = evaluator.sized(t);
        for (int k = 0; k < t.edges.size(); k++) sizedDn.put(t.edges.get(k), s.edges.get(k).dn);
      } catch (Failure ignored) {
      }
    Map<PEdge, Integer> dnOf = new IdentityHashMap<>();
    for (PEdge e : lastLines.keySet()) {
      Network.Edge ne = lastNetEdges.get(e);
      dnOf.put(e, ne != null && sizedDn.containsKey(ne) ? Math.max(e.dn, sizedDn.get(ne)) : e.dn);
    }
    int before = culprits.size();
    boolean known = before > 0;
    for (Map.Entry<PEdge, LineString> en : lastLines.entrySet()) {
      PEdge e = en.getKey();
      int dn = dnOf.get(e);
      Coordinate terminal = e.to.kind == LEAF ? e.to.terminal.p : null;
      Existing.Root root = e.from.kind == ROOT ? e.from.site.root : null;
      try {
        checks.events(en.getValue(), dn, terminal, root);
      } catch (Failure f) {
        culprits.add(e);
        diagnostics.merge("located_culprits", 1, (x, y) -> (Integer) x + (Integer) y);
        diagnostics.put(
            "located_" + Geo.key(en.getValue().getCoordinateN(0)),
            f.code
                + " "
                + f.featureId
                + " dn "
                + dn
                + " -> "
                + (e.to.kind == LEAF ? "t" + e.to.terminal.demand.id : "branch"));
      }
      if (e.from.in != null) {
        LineString parent = lastLines.get(e.from.in);
        LineString child = en.getValue();
        if (parent != null) {
          double turn =
              Geo.turn(
                  parent.getCoordinateN(parent.getNumPoints() - 2),
                  child.getCoordinateN(0),
                  child.getCoordinateN(1));
          boolean joined =
              parent.getCoordinateN(parent.getNumPoints() - 1).distance(child.getCoordinateN(0))
                  < 1e-6;
          if (turn > 90.000001 || !joined) {
            culprits.add(e);
            diagnostics.put(
                "chamber_turn_" + Geo.key(child.getCoordinateN(0)),
                String.format(
                    Locale.ROOT,
                    "%.2f deg joined=%s parentDn=%d childDn=%d child->%s",
                    turn,
                    joined,
                    e.from.in.dn,
                    e.dn,
                    e.to.kind == LEAF ? "t" + e.to.terminal.demand.id : "branch"));
          }
        }
      }
    }
    // Junctions at the sized DN: two pipe pairs leaving one node must not meet again beyond it
    // (JunctionGeometry).
    List<PEdge> all = new ArrayList<>(lastLines.keySet());
    for (int a = 0; a < all.size(); a++)
      for (int b = a + 1; b < all.size(); b++) {
        PEdge ea = all.get(a), eb = all.get(b);
        LineString la = lastLines.get(ea), lb = lastLines.get(eb);
        Coordinate at = null;
        for (Coordinate p :
            new Coordinate[] {la.getCoordinateN(0), la.getCoordinateN(la.getNumPoints() - 1)})
          for (Coordinate q :
              new Coordinate[] {lb.getCoordinateN(0), lb.getCoordinateN(lb.getNumPoints() - 1)})
            if (p.distance(q) < 1e-6) at = p;
        if (at == null) continue;
        if (!JunctionGeometry.fits(
            la, Catalog.pipe(dnOf.get(ea)).width, lb, Catalog.pipe(dnOf.get(eb)).width, at)) {
          PEdge weak = ea.flow <= eb.flow ? ea : eb;
          culprits.add(weak);
          diagnostics.merge("located_junctions", 1, (x, y) -> (Integer) x + (Integer) y);
          diagnostics.put(
              "junction_" + Geo.key(at),
              "dn "
                  + dnOf.get(ea)
                  + "/"
                  + dnOf.get(eb)
                  + " weaker -> "
                  + (weak.to.kind == LEAF ? "t" + weak.to.terminal.demand.id : "branch"));
        }
      }
    // Nothing found anywhere: as a last resort look for edges that would break a rule one DN
    // larger.
    if (culprits.size() == before && !known)
      for (Map.Entry<PEdge, LineString> en : lastLines.entrySet()) {
        PEdge e = en.getKey();
        if (Catalog.pipe(e.dn).index + 1 >= Catalog.PIPES.size()) continue;
        try {
          checks.events(
              en.getValue(),
              Catalog.next(Catalog.pipe(e.dn)).dn,
              e.to.kind == LEAF ? e.to.terminal.p : null,
              e.from.kind == ROOT ? e.from.site.root : null);
        } catch (Failure f) {
          culprits.add(e);
          diagnostics.merge("located_culprits_next_dn", 1, (x, y) -> (Integer) x + (Integer) y);
        }
      }
  }

  private boolean reachesRoot(Terminal t) {
    for (Seed s : seeds(t.field.g)) if (Float.isFinite(t.field.d(s.cell))) return true;
    return false;
  }

  private Forest construct(double noise) {
    Forest f = new Forest();
    List<Terminal> left = new ArrayList<>(terminals);
    while (!left.isEmpty()) {
      Forest best = null;
      Terminal chosen = null;
      double bestDelta = Double.POSITIVE_INFINITY;
      double baseValue = score(f);
      BitSet owner = occupancy(f, plan);
      for (Terminal t : left) {
        Forest trial = insert(f, t, noise, owner, baseValue);
        if (trial == null || !Double.isFinite(score(trial))) continue;
        double delta =
            score(trial)
                - (baseValue
                    - money(
                        rules
                            .penalty(t.demand.flow)
                            .doubleValue())); // marginal cost of connecting t
        if (noise > 0) delta *= 1 + noise * random.nextDouble();
        if (delta < bestDelta) {
          bestDelta = delta;
          best = trial;
          chosen = t;
        }
      }
      if (best == null) break;
      f = best;
      left.remove(chosen);
    }
    return f;
  }

  private Forest improve(Forest f) {
    double current = score(f);
    for (int round = 0; round < 30 && System.nanoTime() < deadline; round++) {
      checkCancel();
      boolean better = false;
      List<Terminal> order = new ArrayList<>(terminals);
      Collections.shuffle(order, random);
      for (Terminal t : order) {
        Forest without = remove(f, t);
        Forest with = insert(without, t, 0);
        if (with == null) continue;
        double v = score(with);
        if (v < current - 1e-9 && with.connected.size() >= f.connected.size()) {
          f = with;
          current = v;
          better = true;
        }
      }
      // ruin and recreate: remove a random neighbourhood and reinsert greedily
      for (int k = 0; k < 6; k++) {
        List<Terminal> in = new ArrayList<>(f.connected);
        if (in.size() < 3) break;
        Terminal centre = in.get(random.nextInt(in.size()));
        in.sort(Comparator.comparingDouble(x -> x.p.distance(centre.p)));
        int m = 2 + random.nextInt(Math.min(4, in.size() - 1));
        Forest ruined = f;
        for (int i = 0; i < m; i++) ruined = remove(ruined, in.get(i));
        List<Terminal> back = new ArrayList<>(in.subList(0, m));
        Forest rebuilt = ruined;
        while (!back.isEmpty()) {
          Forest best = null;
          Terminal chosen = null;
          double bv = Double.POSITIVE_INFINITY;
          BitSet owner = occupancy(rebuilt, plan);
          double rv = score(rebuilt);
          for (Terminal t : back) {
            Forest trial = insert(rebuilt, t, 0, owner, rv);
            if (trial == null) continue;
            double v = score(trial) - (rv - money(rules.penalty(t.demand.flow).doubleValue()));
            if (v < bv) {
              bv = v;
              best = trial;
              chosen = t;
            }
          }
          if (best == null) break;
          rebuilt = best;
          back.remove(chosen);
        }
        double v = score(rebuilt);
        if (v < current - 1e-9 && rebuilt.connected.size() >= f.connected.size()) {
          f = rebuilt;
          current = v;
          better = true;
        }
      }
      if (!better) break;
    }
    return f;
  }

  // ------------------------------------------------------------------ subtree regraft (SPR)
  private final LinkedHashMap<Long, Field> nodeFields =
      new LinkedHashMap<Long, Field>(16, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<Long, Field> e) {
          return size() > 3;
        }
      };

  /**
   * Field from one node within radius metres (a square window). A regraft never gains from an
   * attachment much farther away than the edge it replaces, so the window is the incoming edge plus
   * a margin instead of the whole raster (measured: regraft took 59 % of the polish time).
   */
  private Field nodeField(int cell, int dn, int radius) {
    long key = ((long) dn << 48) | ((long) radius << 32) | cell;
    Field f = nodeFields.get(key);
    if (f != null) return f;
    Grid g = grid(dn);
    if (g.state[cell] != FREE) return null;
    int[] saved = window;
    window = windowAround(g, List.of(g.center(cell)), radius);
    try {
      f = search(g, null, new int[] {cell}, new double[] {0}, 0, 0, -1);
    } finally {
      window = saved;
    }
    nodeFields.put(key, f);
    return f;
  }

  /**
   * Detach the subtree hanging below branch node b (index in f.nodes) and re-attach it at the best
   * place.
   */
  private Forest regraft(Forest base, int nodeIndex, double current) {
    PNode b0 = base.nodes.get(nodeIndex);
    if (b0.kind != BRANCH || b0.in == null) return null;
    int radius =
        10 * (int) Math.ceil((b0.in.len + Integer.getInteger("heatroute.regraftMargin", 60)) / 10);
    Field fb = nodeField(b0.cell, b0.in.dn, radius);
    if (fb == null) return null;
    Forest f = base.copy();
    PNode b = f.nodes.get(nodeIndex);
    PEdge old = b.in;
    PNode parent = old.from;
    // subtree membership
    // insertion order, so the flow sum below is the same on every run
    Set<PNode> sub = Collections.newSetFromMap(new LinkedHashMap<>());
    Deque<PNode> st = new ArrayDeque<>();
    st.add(b);
    while (!st.isEmpty()) {
      PNode n = st.pop();
      sub.add(n);
      for (PEdge e : n.out) st.push(e.to);
    }
    double flow = 0;
    for (PNode n : sub) if (n.kind == LEAF) flow += n.terminal.flow;
    parent.out.remove(old);
    f.edges.remove(old);
    b.in = null;
    if (parent.kind == ROOT && parent.out.isEmpty()) {
      f.nodes.remove(parent);
      f.roots.remove(parent.site.root.key);
    } else if (parent.kind == BRANCH && parent.out.size() == 1) mergeThrough(f, parent);
    Grid g = plan;
    // occupancy of everything except the detached subtree, inside the field's window (the only
    // place a new path can run)
    int[] wb = fb.bounds != null ? fb.bounds : new int[] {0, 0, g.nx - 1, g.ny - 1};
    int ow = wb[2] - wb[0] + 1, oh = wb[3] - wb[1] + 1;
    boolean[] occupied = new boolean[ow * oh];
    int r = (int) Math.ceil(1.2 / g.h);
    for (int id = 0; id < f.edges.size(); id++) {
      PEdge e = f.edges.get(id);
      if (sub.contains(e.to)) continue;
      for (int c : expand(g, e.cells)) {
        int i = c % g.nx, j = c / g.nx;
        if (i + r < wb[0] || i - r > wb[2] || j + r < wb[1] || j - r > wb[3]) continue;
        for (int dj = -r; dj <= r; dj++)
          for (int di = -r; di <= r; di++) {
            int ii = i + di, jj = j + dj;
            if (ii < wb[0] || jj < wb[1] || ii > wb[2] || jj > wb[3]) continue;
            if (di * di + dj * dj <= r * r) occupied[(jj - wb[1]) * ow + ii - wb[0]] = true;
          }
      }
    }
    int dn = b0.in.dn;
    double unit = perMetre(dn);
    List<Option> options = new ArrayList<>();
    Map<String, Integer> usedRays = new HashMap<>();
    for (PNode rn : f.roots.values()) usedRays.put(rn.site.root.key, rn.out.size());
    for (Seed sd : seeds(fb.g)) {
      if (!bannedRoots.isEmpty() && rootBanned(sd.site)) continue;
      float d = fb.d(sd.cell);
      if (!Float.isFinite(d)) continue;
      int used = usedRays.getOrDefault(sd.site.root.key, 0);
      if (used >= sd.site.freeRays) continue;
      double fixed =
          sd.site.chamber
              ? money(rules.number("cost.tie_in_rub"))
              : used > 0
                  ? 0
                  : money(Catalog.chamber(Math.max(sd.site.existingDn, dn)).doubleValue());
      Option o = new Option();
      o.kind = ROOT;
      o.seed = sd;
      o.attachCell = sd.cell;
      o.lower = (d + sd.ray) * unit + fixed;
      options.add(o);
    }
    for (PEdge e : f.edges) {
      if (sub.contains(e.to)) continue;
      for (int k = 2; k < e.cells.length - 2; k++) {
        float d = fb.d(e.cells[k]);
        if (!Float.isFinite(d)) continue;
        Option o = new Option();
        o.kind = BRANCH;
        o.edge = e;
        o.index = k;
        o.attachCell = e.cells[k];
        o.lower = d * unit + money(3000000);
        options.add(o);
      }
    }
    for (PNode n : f.nodes)
      if (n.kind == BRANCH && !sub.contains(n) && n.out.size() + 1 < 4) {
        float d = fb.d(n.cell);
        if (!Float.isFinite(d)) continue;
        Option o = new Option();
        o.kind = LEAF;
        o.node = n;
        o.attachCell = n.cell;
        o.lower = d * unit;
        options.add(o);
      }
    options.sort(Comparator.comparingDouble(o -> o.lower));
    // the rest of the forest without the subtree's own cost is a lower bound reference
    Forest best = null;
    double bestValue = current - 1e-9;
    int evaluated = 0;
    Coordinate bp = b.p;
    Coordinate down = null;
    {
      PEdge c = null;
      for (PEdge e : b.out) if (c == null || e.flow > c.flow) c = e;
      down = direction(g, c.cells, 0, Math.min(6, c.cells.length - 1));
    }
    for (Option o : options) {
      if (evaluated >= 30) break;
      int[] path = trace(fb, o.attachCell); // attach .. b
      Coordinate at =
          o.kind == ROOT ? o.seed.landing : o.kind == LEAF ? o.node.p : g.center(o.attachCell);
      boolean clear = true;
      for (int c : expand(g, path)) {
        int i = c % g.nx - wb[0], j = c / g.nx - wb[1];
        if (i < 0 || j < 0 || i >= ow || j >= oh || !occupied[j * ow + i]) continue;
        Coordinate q = g.center(c);
        if (q.distance(at) > 2.6 && q.distance(bp) > 2.6) {
          clear = false;
          break;
        }
      }
      if (!clear) continue;
      // turn at b: arriving direction must be within 90 degrees of every outgoing direction
      Coordinate arrive =
          path.length > 1
              ? new Coordinate(
                  bp.x - g.center(path[Math.max(0, path.length - 7)]).x,
                  bp.y - g.center(path[Math.max(0, path.length - 7)]).y)
              : null;
      boolean turnOk = arrive != null;
      if (turnOk)
        for (PEdge e : b.out) {
          Coordinate d = direction(g, e.cells, 0, Math.min(6, e.cells.length - 1));
          if (d != null && angle(arrive, d) > maxTurn + 5) turnOk = false;
        }
      if (!turnOk) continue;
      Coordinate out = direction(g, path, 0, Math.min(16, path.length - 1));
      if (o.kind == ROOT) {
        if (out != null && angle(new Coordinate(o.seed.rx, o.seed.ry), out) > 90) continue;
        PNode rr = f.roots.get(o.seed.site.root.key);
        boolean clash = false;
        if (rr != null)
          for (PEdge e : rr.out)
            if (e.seed != null
                && angle(new Coordinate(e.seed.rx, e.seed.ry), new Coordinate(o.seed.rx, o.seed.ry))
                    < 30) clash = true;
        if (clash) continue;
      } else if (o.kind == BRANCH) {
        Coordinate trunk =
            direction(g, o.edge.cells, o.index, Math.min(6, o.edge.cells.length - 1 - o.index));
        if (out == null || trunk == null || angle(trunk, out) > maxTurn || angle(trunk, out) < 30)
          continue;
      } else {
        Coordinate inc =
            direction(
                g,
                o.node.in.cells,
                o.node.in.cells.length - 1,
                -Math.min(6, o.node.in.cells.length - 1));
        if (out == null || inc == null) continue;
        if (angle(new Coordinate(-inc.x, -inc.y), out) > maxTurn + 5) continue;
        boolean clash = false;
        for (PEdge e : o.node.out) {
          Coordinate d = direction(g, e.cells, 0, Math.min(6, e.cells.length - 1));
          if (d != null && angle(d, out) < 30) clash = true;
        }
        if (clash) continue;
      }
      evaluated++;
      Forest trial = attachSubtree(f, b, o, path, fb, dn);
      double v = score(trial);
      if (v < bestValue) {
        bestValue = v;
        best = trial;
      }
    }
    return best;
  }

  private void mergeThrough(Forest f, PNode parent) {
    PEdge up = parent.in, down = parent.out.get(0);
    int[] cells = new int[up.cells.length + down.cells.length - 1];
    System.arraycopy(up.cells, 0, cells, 0, up.cells.length);
    System.arraycopy(down.cells, 1, cells, up.cells.length, down.cells.length - 1);
    float[] ex = new float[cells.length];
    System.arraycopy(up.ex, 0, ex, 0, up.ex.length);
    System.arraycopy(down.ex, 1, ex, up.ex.length, down.ex.length - 1);
    up.ex = ex;
    up.cells = cells;
    up.plannedDn = Math.min(up.plannedDn, down.plannedDn);
    up.to = down.to;
    up.last = down.last;
    down.to.in = up;
    up.len += down.len;
    up.extra += down.extra;
    f.edges.remove(down);
    f.nodes.remove(parent);
  }

  private Forest attachSubtree(Forest base, PNode b0, Option o, int[] path, Field fb, int dn) {
    Grid g = plan;
    Forest f = base.copy();
    PNode b = f.nodes.get(base.nodes.indexOf(b0));
    PNode attach;
    if (o.kind == ROOT) {
      PNode rr = f.roots.get(o.seed.site.root.key);
      if (rr == null) {
        rr = new PNode();
        rr.kind = ROOT;
        rr.site = o.seed.site;
        rr.p = o.seed.site.p;
        f.nodes.add(rr);
        f.roots.put(rr.site.root.key, rr);
      }
      attach = rr;
    } else if (o.kind == LEAF) attach = f.nodes.get(base.nodes.indexOf(o.node));
    else {
      PEdge e = f.edges.get(base.edges.indexOf(o.edge));
      PNode nb = new PNode();
      nb.kind = BRANCH;
      nb.cell = e.cells[o.index];
      nb.p = g.center(nb.cell);
      f.nodes.add(nb);
      PEdge tail = new PEdge();
      tail.plannedDn = e.plannedDn;
      tail.from = nb;
      tail.to = e.to;
      tail.cells = Arrays.copyOfRange(e.cells, o.index, e.cells.length);
      tail.ex = Arrays.copyOfRange(e.ex, o.index, e.ex.length);
      tail.ex[0] = 0;
      tail.last = e.last;
      e.to.in = tail;
      nb.out.add(tail);
      e.cells = Arrays.copyOfRange(e.cells, 0, o.index + 1);
      e.ex = Arrays.copyOfRange(e.ex, 0, o.index + 1);
      e.to = nb;
      e.last = null;
      nb.in = e;
      double[] m = measure(g, e.cells, e.first, nb.p);
      e.len = m[0];
      e.extra = sum(e.ex, 0, e.ex.length);
      double[] n = measure(g, tail.cells, nb.p, tail.last);
      tail.len = n[0] + (tail.to.kind == LEAF ? tail.last.distance(tail.to.p) : 0);
      tail.extra = sum(tail.ex, 0, tail.ex.length);
      f.edges.add(tail);
      attach = nb;
    }
    PEdge e = new PEdge();
    e.from = attach;
    e.to = b;
    e.plannedDn = dn;
    e.cells = path.clone();
    e.ex = stepExtras(fb, e.cells);
    // path is attach .. b; the field was grown from b so the order is already root-to-leaf
    if (o.kind == ROOT) {
      e.seed = o.seed;
      e.first = o.seed.site.p;
    } else e.first = attach.p;
    double[] m = measure(g, e.cells, e.first, b.p);
    e.len = m[0];
    e.extra = sum(e.ex, 0, e.ex.length);
    attach.out.add(e);
    b.in = e;
    f.edges.add(e);
    return f;
  }

  // ------------------------------------------------------------------ Steiner point relocation
  /**
   * Move branch chamber i of f to the cell minimising the weighted distances to its parent and
   * children; null if no gain.
   */
  private Forest relocate(Forest base, int nodeIndex, double current) {
    PNode b0 = base.nodes.get(nodeIndex);
    if (b0.kind != BRANCH || b0.in == null) return null;
    Grid g = plan;
    PEdge pe0 = b0.in;
    List<Coordinate> pts = new ArrayList<>();
    pts.add(b0.p);
    pts.add(pe0.from.kind == ROOT ? pe0.from.site.p : pe0.from.p);
    for (PEdge e : b0.out) pts.add(e.to.kind == LEAF ? e.to.terminal.p : e.to.p);
    int[] win = windowAround(g, pts, 40);
    // parent side field
    Field fp;
    window = win;
    try {
      Grid gp = grid(pe0.dn);
      if (pe0.from.kind == ROOT) {
        List<Seed> st = new ArrayList<>();
        for (Seed sd : seeds(gp)) if (sd.site.root.key.equals(pe0.from.site.root.key)) st.add(sd);
        if (st.isEmpty()) return null;
        int[] cells = new int[st.size()];
        double[] cost = new double[st.size()];
        for (int i = 0; i < cells.length; i++) {
          cells[i] = st.get(i).cell;
          cost[i] = st.get(i).ray;
        }
        fp = search(gp, null, cells, cost, 0, 0, -1);
      } else {
        if (gp.state[pe0.from.cell] != FREE) return null;
        fp = search(gp, null, new int[] {pe0.from.cell}, new double[] {0}, 0, 0, -1);
      }
      List<Field> fc = new ArrayList<>();
      for (PEdge e : b0.out) {
        if (e.to.kind == LEAF) fc.add(e.to.terminal.field);
        else {
          Grid gc = grid(e.dn);
          if (gc.state[e.to.cell] != FREE) return null;
          fc.add(search(gc, null, new int[] {e.to.cell}, new double[] {0}, 0, 0, -1));
        }
      }
      // weighted objective over the window
      int dnMax = pe0.dn;
      for (PEdge e : b0.out) dnMax = Math.max(dnMax, e.dn);
      Grid gm = grid(dnMax);
      double wp = perMetre(pe0.dn);
      double[] wc = new double[b0.out.size()];
      for (int k = 0; k < wc.length; k++) wc[k] = perMetre(b0.out.get(k).dn);
      List<long[]> cand = new ArrayList<>();
      for (int j = win[1]; j <= win[3]; j++)
        for (int i = win[0]; i <= win[2]; i++) {
          int c = j * g.nx + i;
          if (gm.state[c] != FREE || !Float.isFinite(fp.d(c))) continue;
          double v = wp * fp.d(c);
          boolean ok = true;
          for (int k = 0; k < wc.length && ok; k++) {
            float d = fc.get(k).d(c);
            if (!Float.isFinite(d)) ok = false;
            else v += wc[k] * d;
          }
          if (ok) cand.add(new long[] {Double.doubleToLongBits(v), c});
        }
      cand.sort(Comparator.comparingDouble(a -> Double.longBitsToDouble(a[0])));
      BitSet owner = null;
      Forest best = null;
      double bestValue = current - 1e-9;
      int tried = 0;
      for (long[] cv : cand) {
        if (tried >= 12) break;
        int x = (int) cv[1];
        if (x == b0.cell && tried == 0) {
          tried++;
          continue;
        }
        Forest f = base.copy();
        PNode b = f.nodes.get(nodeIndex);
        PEdge pe = b.in;
        if (owner == null) {
          owner = occupancyExcept(base, b0);
        }
        int[] up = trace(fp, x);
        int[] upFwd = new int[up.length];
        for (int q = 0; q < up.length; q++) upFwd[q] = up[up.length - 1 - q];
        if (!clearAround(
            g, up, owner, g.center(x), pe0.from.kind == ROOT ? pe0.from.site.p : pe0.from.p))
          continue;
        boolean ok = true;
        List<int[]> downs = new ArrayList<>();
        for (int k = 0; k < b.out.size(); k++) {
          int[] dn = trace(fc.get(k), x);
          PEdge e0 = b0.out.get(k);
          if (!clearAround(
              g, dn, owner, g.center(x), e0.to.kind == LEAF ? e0.to.terminal.p : e0.to.p)) {
            ok = false;
            break;
          }
          downs.add(dn);
        }
        if (!ok) continue;
        tried++;
        Coordinate xp = g.center(x);
        // turns at the moved chamber
        Coordinate inDir = direction(g, upFwd, upFwd.length - 1, -Math.min(16, upFwd.length - 1));
        if (inDir == null) continue;
        Coordinate trunk = new Coordinate(-inDir.x, -inDir.y);
        for (int[] dn : downs) {
          Coordinate out = direction(g, dn, 0, Math.min(16, dn.length - 1));
          if (out == null || angle(trunk, out) > maxTurn) {
            ok = false;
            break;
          }
        }
        if (!ok) continue;
        for (int a = 0; a < downs.size() && ok; a++)
          for (int c2 = a + 1; c2 < downs.size(); c2++) {
            Coordinate u = direction(g, downs.get(a), 0, Math.min(16, downs.get(a).length - 1)),
                w = direction(g, downs.get(c2), 0, Math.min(16, downs.get(c2).length - 1));
            if (u != null && w != null && angle(u, w) < 30) ok = false;
          }
        if (!ok) continue;
        b.cell = x;
        b.p = xp;
        pe.cells = upFwd;
        pe.ex = stepExtras(fp, upFwd);
        pe.extra = sum(pe.ex, 0, pe.ex.length);
        pe.last = null;
        if (pe.from.kind == ROOT) {
          Seed sd = null;
          for (Seed s2 : seeds(fp.g))
            if (s2.cell == upFwd[0] && s2.site.root.key.equals(pe.from.site.root.key)) {
              sd = s2;
              break;
            }
          if (sd == null) continue;
          pe.seed = sd;
          pe.first = sd.site.p;
        } else pe.first = pe.from.p;
        pe.len = measure(g, pe.cells, pe.first, xp)[0];
        for (int k = 0; k < b.out.size(); k++) {
          PEdge e = b.out.get(k);
          int[] dn = downs.get(k);
          e.cells = dn;
          e.ex = stepExtras(fc.get(k), dn);
          e.extra = sum(e.ex, 0, e.ex.length);
          e.first = xp;
          if (e.to.kind == LEAF) {
            e.last = e.to.terminal.rayPoint(dn[dn.length - 1]);
            if (e.last == null) {
              ok = false;
              break;
            }
            e.len = measure(g, dn, xp, e.last)[0] + e.last.distance(e.to.terminal.p);
          } else {
            e.last = null;
            e.len = measure(g, dn, xp, e.to.p)[0];
          }
        }
        if (!ok) continue;
        double v = score(f);
        if (v < bestValue) {
          bestValue = v;
          best = f;
        }
      }
      return best;
    } finally {
      window = null;
    }
  }

  /** Occupancy of every edge except those incident to node n. */
  private BitSet occupancyExcept(Forest f, PNode n) {
    Forest c = f.copy();
    PNode m = c.nodes.get(f.nodes.indexOf(n));
    c.edges.remove(m.in);
    for (PEdge e : m.out) c.edges.remove(e);
    return occupancy(c, plan);
  }

  private boolean clearAround(Grid g, int[] path, BitSet owner, Coordinate a, Coordinate b) {
    for (int c : expand(g, path)) {
      if (!owner.get(c)) continue;
      Coordinate q = g.center(c);
      if (q.distance(a) > 2.6 && q.distance(b) > 2.6) return false;
    }
    return true;
  }

  /** Polish the best forest by subtree regrafting until no move improves. */
  private int polishRounds = 6, eliteLimit = 12;

  private Forest polish(Forest f) {
    double current = score(f);
    for (int round = 0; round < polishRounds && System.nanoTime() < deadline; round++) {
      checkCancel();
      boolean better = false;
      for (int i = 0; i < f.nodes.size() && System.nanoTime() < deadline; i++) {
        if (f.nodes.get(i).kind != BRANCH) continue;
        Forest moved = relocate(f, i, current);
        if (moved != null) {
          double v = score(moved);
          if (v < current - 1e-9 && moved.connected.size() >= f.connected.size()) {
            f = moved;
            current = v;
            better = true;
            diagnostics.merge("relocations", 1, (a, b) -> (Integer) a + (Integer) b);
          }
        }
        Forest t = regraft(f, i, current);
        if (t != null) {
          double v = score(t);
          if (v < current - 1e-9) {
            f = t;
            current = v;
            better = true;
            f = improve(f);
            current = score(f);
          }
        }
      }
      if (!better) break;
    }
    return f;
  }

  // ------------------------------------------------------------------ vector geometry
  private final List<LineString> placed = new ArrayList<>();
  private final List<Double> placedWidth = new ArrayList<>();
  private final List<Coordinate[]> placedEnds = new ArrayList<>();

  private boolean valid(
      Coordinate a,
      Coordinate b,
      int dn,
      Coordinate terminal,
      Existing.Root root,
      Coordinate prev) {
    if (a.distance(b) < 1e-6) return false;
    if (prev != null && Geo.turn(prev, a, b) > 89.9) return false;
    if (!checks.segment(a, b, dn, terminal, root)) return false;
    LineString l = Geo.line(a, b);
    // only the first run from the root may approach the target network (TARGET_REENTRY /
    // TARGET_TRANSIT)
    if (root != null && a.distance(root.p) > 1e-6 && b.distance(root.p) > 1e-6) {
      for (Dataset.Obstacle o : data.near(l.getEnvelopeInternal(), rules.queryMargin()))
        if (o.type.equals("heat_network")
            && checks.isTarget(o, root)
            && o.distance(l) < o.required(dn, rules) + .02) return false;
    }
    // vertices stay in free space: a bend inside a road/tram zone would break the straight special
    // passage
    for (Coordinate v : new Coordinate[] {a, b}) {
      if (terminal != null && v.distance(terminal) < 1e-6
          || root != null && v.distance(root.p) < 1e-6) continue;
      for (Dataset.Obstacle o : data.near(new Envelope(v), rules.queryMargin()))
        if (o.special() && o.distance(Geo.point(v)) < o.required(dn, rules) - 1e-6) return false;
    }
    // mandatory straight extensions of special passages must lie inside this straight segment
    double len = a.distance(b);
    for (Dataset.Obstacle o : data.near(l.getEnvelopeInternal(), rules.queryMargin())) {
      if (!o.special()) continue;
      if (root != null
          && o.type.equals("heat_network")
          && checks.isTarget(o, root)
          && (a.distance(root.p) < 1e-6 || b.distance(root.p) < 1e-6)) continue;
      Geometry hit = l.intersection(o.geometry);
      if (hit.isEmpty()) continue;
      double ext = Set.of("road", "tram_tracks").contains(o.type) ? 3 : 2;
      for (Coordinate c : hit.getCoordinates()) {
        double s = a.distance(c);
        if (s < ext + .02 || len - s < ext + .02) return false;
      }
    }
    double w = Catalog.pipe(dn).width;
    for (int i = 0; i < placed.size(); i++) {
      LineString other = placed.get(i);
      if (!other.getEnvelopeInternal().intersects(l.getEnvelopeInternal()) && other.distance(l) > 5)
        continue;
      Coordinate[] ends = placedEnds.get(i);
      double gap = (w + placedWidth.get(i)) / 2 + .05;
      Coordinate node = null;
      for (Coordinate e : ends) if (e.distance(a) < 1e-6 || e.distance(b) < 1e-6) node = e;
      if (node == null) {
        for (Coordinate e : ends) if (l.distance(Geo.point(e)) < 1e-6) return false;
        if (l.distance(other) < gap) return false;
        continue;
      }
      Geometry x = l.intersection(other);
      for (Coordinate c : x.getCoordinates()) if (c.distance(node) > 1e-6) return false;
      // Near a common junction only the two first straight runs may overlap (JunctionGeometry
      // rule).
      Coordinate[] oc = other.getCoordinates();
      boolean atStart = oc[0].distance(node) < 1e-6;
      List<Coordinate> rest = new ArrayList<>();
      if (atStart) for (int k = 1; k < oc.length; k++) rest.add(oc[k]);
      else for (int k = 0; k < oc.length - 1; k++) rest.add(oc[k]);
      if (rest.size() >= 2 && l.distance(Geo.line(rest)) < gap) return false;
    }
    return true;
  }

  /**
   * Greedy exact tautening of a polyline; fixed first/last points. dn[i] is the DN valid from raw
   * point i onwards.
   */
  private boolean link(
      List<Coordinate> pts,
      int[] dn,
      int i,
      int j,
      Coordinate terminal,
      Existing.Root root,
      Coordinate prev,
      Coordinate after) {
    if (j == pts.size() - 1 && after != null && Geo.turn(pts.get(i), pts.get(j), after) > 89.9)
      return false;
    return valid(pts.get(i), pts.get(j), dn[i], terminal, root, prev);
  }

  private List<Integer> taut(
      List<Coordinate> pts,
      int[] dn,
      Coordinate terminal,
      Existing.Root root,
      Coordinate before,
      Coordinate after) {
    List<Integer> out = new ArrayList<>();
    out.add(0);
    int i = 0;
    Coordinate prev = before;
    while (i < pts.size() - 1) {
      int lo = i + 1, step = 1, hi = -1;
      if (!link(pts, dn, i, lo, terminal, root, prev, after)) {
        int far = -1;
        for (int j = Math.min(pts.size() - 1, i + 80); j > i + 1; j--)
          if (link(pts, dn, i, j, terminal, root, prev, after)) {
            far = j;
            break;
          }
        if (far < 0) {
          if (rawSteps < 40) {
            String why =
                prev != null && Geo.turn(prev, pts.get(i), pts.get(lo)) > 89.9
                    ? "turn"
                    : !checks.segment(pts.get(i), pts.get(lo), dn[i], terminal, root)
                        ? "rules"
                        : "placed";
            diagnostics.put(
                "raw_" + rawSteps, Geo.key(pts.get(i)) + " i=" + i + "/" + pts.size() + " " + why);
          }
          out.add(lo);
          prev = pts.get(i);
          i = lo;
          rawSteps++;
          continue;
        }
        lo = far;
      }
      while (true) {
        int next = Math.min(pts.size() - 1, lo + step);
        if (next == lo) break;
        if (link(pts, dn, i, next, terminal, root, prev, after)) {
          lo = next;
          step *= 2;
        } else {
          hi = next;
          break;
        }
      }
      if (hi > 0) {
        while (hi - lo > 1) {
          int mid = (lo + hi) / 2;
          if (link(pts, dn, i, mid, terminal, root, prev, after)) lo = mid;
          else hi = mid;
        }
      }
      out.add(lo);
      prev = pts.get(i);
      i = lo;
    }
    return out;
  }

  /**
   * Tauten a point list keeping every position in `pins` as an exact vertex (chamber boundaries);
   * each stretch between them is tautened on its own. Empty `pins` is a single stretch, identical
   * to a plain taut() call.
   */
  private List<Integer> tautPinned(
      List<Coordinate> raw,
      int[] dn,
      Coordinate terminal,
      Existing.Root root,
      Coordinate before,
      Coordinate after,
      List<Integer> pins) {
    List<Integer> bounds = new ArrayList<>(pins);
    if (bounds.isEmpty() || bounds.get(bounds.size() - 1) != raw.size() - 1)
      bounds.add(raw.size() - 1);
    List<Integer> idx = new ArrayList<>();
    idx.add(0);
    int from = 0;
    Coordinate prev = before;
    for (int b : bounds) {
      if (b <= from) return null;
      List<Coordinate> sub = new ArrayList<>(raw.subList(from, b + 1));
      int[] sdn = Arrays.copyOfRange(dn, from, b + 1);
      List<Integer> part = taut(sub, sdn, terminal, root, prev, b == raw.size() - 1 ? after : null);
      for (int q = 1; q < part.size(); q++) idx.add(from + part.get(q));
      prev = raw.get(idx.get(idx.size() - 2));
      from = b;
    }
    return idx;
  }

  /** Positions within `idx` of every raw index in `rawPins` (empty if `rawPins` is empty). */
  private List<Integer> pinPositions(List<Integer> idx, List<Integer> rawPins) {
    List<Integer> pos = new ArrayList<>();
    for (int r : rawPins) {
      int p = idx.indexOf(r);
      if (p >= 0) pos.add(p);
    }
    return pos;
  }

  private int[] dnAt(List<Integer> idx, int[] dn) {
    int[] out = new int[idx.size()];
    for (int k = 0; k < out.length; k++) out[k] = dn[idx.get(k)];
    return out;
  }

  private int rawSteps;

  /**
   * Move the bend on the entrance ray to the closest admissible station: shorter and still a <=90
   * degree turn.
   */
  private void slide(
      List<Coordinate> pts, Terminal t, int dn, Existing.Root root, Coordinate before) {
    if (t.free) return;
    int k = pts.size() - 1;
    if (k < 1) return;
    Coordinate v = pts.get(k - 1), r = pts.get(k);
    Coordinate beforeV = k >= 2 ? pts.get(k - 2) : before;
    double sMin = t.p.distance(t.port),
        sNow = t.p.distance(r),
        a = (v.x - t.p.x) * t.ux + (v.y - t.p.y) * t.uy;
    double hi = Math.min(sNow, a - 1e-3);
    if (hi < sMin) return;
    java.util.function.DoublePredicate ok =
        s -> {
          Coordinate q = new Coordinate(t.p.x + t.ux * s, t.p.y + t.uy * s);
          return Geo.turn(v, q, t.p) <= 89.9
              && valid(v, q, dn, t.p, root, beforeV)
              && checks.segment(q, t.p, dn, t.p, null);
        };
    // validity is not monotone along the ray: scan upwards from the shortest admissible station
    for (double s = sMin; s <= hi + 1e-9; s += .25)
      if (ok.test(Math.min(s, hi))) {
        double lo = Math.max(sMin, s - .25), up = Math.min(s, hi);
        for (int it = 0; it < 12; it++) {
          double mid = (lo + up) / 2;
          if (ok.test(mid)) up = mid;
          else lo = mid;
        }
        if (Math.abs(up - sNow) > 1e-4)
          pts.set(k, new Coordinate(t.p.x + t.ux * up, t.p.y + t.uy * up));
        return;
      }
  }

  /**
   * Pull interior vertices towards their chords while every touched segment stays valid. idx keeps
   * the raw index of each vertex.
   */
  private Set<Integer> pinnedRaw = Collections.emptySet();

  private void relax(
      List<Coordinate> p,
      List<Integer> idx,
      int[] dn,
      Coordinate terminal,
      Existing.Root root,
      Coordinate before,
      Coordinate after) {
    for (int sweep = 0; sweep < 25; sweep++) {
      boolean moved = false;
      for (int k = 1; k < p.size() - 1; k++) {
        if (pinnedRaw.contains(idx.get(k))) continue;
        Coordinate a = p.get(k - 1), v = p.get(k), b = p.get(k + 1);
        int da = dn[idx.get(k - 1)], dv = dn[idx.get(k)];
        Coordinate pa = k >= 2 ? p.get(k - 2) : before;
        if (valid(a, b, da, terminal, root, pa)
            && (k + 2 >= p.size()
                ? after == null || Geo.turn(a, b, after) <= 89.9
                : Geo.turn(a, b, p.get(k + 2)) <= 89.9)) {
          p.remove(k);
          idx.remove(k);
          k--;
          moved = true;
          continue;
        }
        LineSegment chord = new LineSegment(a, b);
        Coordinate foot = chord.closestPoint(v);
        double lo = 0, hi = 1;
        for (int it = 0; it < 14; it++) {
          double mid = (lo + hi) / 2;
          Coordinate q = new Coordinate(v.x + (foot.x - v.x) * mid, v.y + (foot.y - v.y) * mid);
          boolean ok =
              valid(a, q, da, terminal, root, pa)
                  && valid(q, b, dv, terminal, root, a)
                  && (k + 2 >= p.size()
                      ? after == null || Geo.turn(q, b, after) <= 89.9
                      : Geo.turn(q, b, p.get(k + 2)) <= 89.9);
          if (ok) lo = mid;
          else hi = mid;
        }
        if (lo > 1e-3 && v.distance(foot) * lo > 1e-3) {
          p.set(k, new Coordinate(v.x + (foot.x - v.x) * lo, v.y + (foot.y - v.y) * lo));
          moved = true;
        }
      }
      if (!moved) break;
    }
  }

  /** Sign of the turn at b (left > 0, right < 0, straight 0). */
  private static double side(Coordinate a, Coordinate b, Coordinate c) {
    double x = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x);
    return Math.abs(x) < 1e-12 ? 0 : Math.signum(x);
  }

  /** Intersection of line a->b with line c->d, null if parallel. */
  private static Coordinate crossing(Coordinate a, Coordinate b, Coordinate c, Coordinate d) {
    double rx = b.x - a.x, ry = b.y - a.y, sx = d.x - c.x, sy = d.y - c.y, den = rx * sy - ry * sx;
    if (Math.abs(den) < 1e-12) return null;
    double t = ((c.x - a.x) * sy - (c.y - a.y) * sx) / den;
    return new Coordinate(a.x + rx * t, a.y + ry * t);
  }

  /**
   * Appendix §2.1 (no unjustified small kinks): a fan of short same-direction turns - the raster
   * trace around one corner - becomes a single corner where the outer segments meet, if that corner
   * is valid, turns at most 89.9° and adds at most 0.15 m. Pinned chamber vertices stay; a pinned
   * chamber that is almost straight (< 5°) moves onto its chord when that is at most 0.3 m away and
   * both new segments are valid (side chains start from the new position).
   */
  private void defan(
      List<Coordinate> pts,
      List<Integer> idx,
      int[] dn,
      Coordinate terminal,
      Existing.Root root,
      Coordinate before,
      Coordinate after,
      Terminal term) {
    boolean ray = term != null && !term.free;
    double sMin = ray ? term.p.distance(term.port) : 0;
    for (int guard = 0; guard < pts.size(); guard++) {
      Coordinate bestX = null;
      int bestP = -1, bestQ = -1;
      double bestAdded = .15;
      for (int p = 1; p < pts.size(); p++) {
        Coordinate firstNext = p + 1 < pts.size() ? pts.get(p + 1) : after;
        if (firstNext == null) continue;
        // near-collinear vertices (< 3°) are jitter, not a direction: they join a fan of either
        // side
        double sign =
            Geo.turn(pts.get(p - 1), pts.get(p), firstNext) < 3
                ? 0
                : side(pts.get(p - 1), pts.get(p), firstNext);
        for (int q = p + 1; q < pts.size(); q++) {
          boolean rayEnd = q == pts.size() - 1;
          Coordinate next = q + 1 < pts.size() ? pts.get(q + 1) : after;
          if (next == null || pts.get(q - 1).distance(pts.get(q)) >= 5) break;
          if (Geo.turn(pts.get(q - 1), pts.get(q), next) >= 3) {
            double sq = side(pts.get(q - 1), pts.get(q), next);
            if (sign == 0) sign = sq;
            else if (sq != sign) break;
          }
          if (rayEnd && !ray) break;
          // only fans that hold a real small kink: a segment shorter than 2 m inside
          boolean small = false, pinned = false;
          int dnMax = dn[idx.get(p - 1)];
          for (int k = p; k <= q; k++) {
            if (pinnedRaw.contains(idx.get(k))) pinned = true;
            dnMax = Math.max(dnMax, dn[idx.get(k)]);
            if (pts.get(k - 1).distance(pts.get(k)) < 2 && k > p) small = true;
          }
          if (pinned) break;
          if (!small) continue;
          Coordinate a = pts.get(p - 1), b = rayEnd ? term.p : pts.get(q + 1), x;
          if (rayEnd)
            x =
                crossing(
                    a, pts.get(p), term.p, new Coordinate(term.p.x + term.ux, term.p.y + term.uy));
          else x = crossing(a, pts.get(p), pts.get(q), b);
          if (x == null
              || (x.x - a.x) * (pts.get(p).x - a.x) + (x.y - a.y) * (pts.get(p).y - a.y) <= 0)
            continue;
          if (rayEnd && (x.x - term.p.x) * term.ux + (x.y - term.p.y) * term.uy < sMin - 1e-9)
            continue;
          double old = 0;
          for (int k = p - 1; k < q; k++) old += pts.get(k).distance(pts.get(k + 1));
          old += pts.get(q).distance(b);
          double added = a.distance(x) + x.distance(b) - old;
          if (added > bestAdded) continue;
          Coordinate pa = p >= 2 ? pts.get(p - 2) : before;
          if (!valid(a, x, dnMax, terminal, root, pa)) continue;
          if (rayEnd) {
            if (Geo.turn(a, x, term.p) > 89.9 || !checks.segment(x, term.p, dnMax, term.p, null))
              continue;
          } else {
            Coordinate nb = q + 2 < pts.size() ? pts.get(q + 2) : after;
            if (!valid(x, b, dnMax, terminal, root, a) || nb != null && Geo.turn(x, b, nb) > 89.9)
              continue;
          }
          bestX = x;
          bestP = p;
          bestQ = q;
          bestAdded = added;
        }
      }
      if (bestX == null) break;
      int keep = idx.get(bestP);
      for (int k = bestQ; k >= bestP; k--) {
        pts.remove(k);
        idx.remove(k);
      }
      pts.add(bestP, bestX);
      idx.add(bestP, keep);
      diagnostics.merge("defanned", bestQ - bestP, (u, v) -> (Integer) u + (Integer) v);
    }
    for (int k = 1; k + 1 < pts.size(); k++) {
      if (!pinnedRaw.contains(idx.get(k))) continue;
      Coordinate a = pts.get(k - 1), v = pts.get(k), b = pts.get(k + 1);
      double turn = Geo.turn(a, v, b);
      if (turn < .01 || turn >= 5) continue;
      Coordinate q = new LineSegment(a, b).closestPoint(v);
      // only a visual jog: a larger move would lengthen the side branches of this chamber (measured
      // +0.8 m for 3.4 m)
      if (q.distance(v) > .3) continue;
      Coordinate pa = k >= 2 ? pts.get(k - 2) : before,
          nb = k + 2 < pts.size() ? pts.get(k + 2) : after;
      if (!valid(a, q, dn[idx.get(k - 1)], terminal, root, pa)
          || !valid(q, b, dn[idx.get(k)], terminal, root, a)) continue;
      if (nb != null && Geo.turn(q, b, nb) > 89.9) continue;
      pts.set(k, q);
      diagnostics.merge("straightened_chambers", 1, (u, w) -> (Integer) u + (Integer) w);
    }
  }

  /**
   * Appendix §2.1 on the final pieces of a chain, whichever stage built them: an interior vertex
   * that turns less than 3°, or less than 20° next to a segment shorter than 2 m, is dropped when
   * the straight replacement passes the same checks as any segment (clearance, special passages,
   * placed pipes, turns at both ends). Chamber, root and terminal ends and the entrance-ray corner
   * stay; a dropped vertex only shortens the line. Returns the input when nothing changed.
   */
  private List<LineString> straighten(
      List<PEdge> chain, List<LineString> pieces, Map<PNode, Coordinate> incoming) {
    List<LineString> out = new ArrayList<>(pieces);
    boolean any = false;
    for (int m = 0; m < chain.size(); m++) {
      PEdge e = chain.get(m);
      List<Coordinate> c = new ArrayList<>(Arrays.asList(out.get(m).getCoordinates()));
      Coordinate terminal = e.to.kind == LEAF ? e.to.terminal.p : null;
      Existing.Root root = e.from.kind == ROOT ? e.from.site.root : null;
      Coordinate before =
          m > 0
              ? out.get(m - 1).getCoordinateN(out.get(m - 1).getNumPoints() - 2)
              : incoming.get(e.from);
      Coordinate after = m + 1 < chain.size() ? out.get(m + 1).getCoordinateN(1) : null;
      // the corner where a leaf joins its entrance ray is fixed by R02
      int lastFree = terminal != null && !e.to.terminal.free ? c.size() - 3 : c.size() - 2;
      boolean changed = false;
      for (int k = 1; k <= lastFree && k + 1 < c.size(); ) {
        double turn = Geo.turn(c.get(k - 1), c.get(k), c.get(k + 1)),
            shorter = Math.min(c.get(k - 1).distance(c.get(k)), c.get(k).distance(c.get(k + 1)));
        boolean kink = turn < 3 || turn < 20 && shorter < 2;
        Coordinate pa = k >= 2 ? c.get(k - 2) : before,
            nb = k + 2 < c.size() ? c.get(k + 2) : after;
        if (kink
            && valid(c.get(k - 1), c.get(k + 1), e.dn, terminal, root, pa)
            && (nb == null || Geo.turn(c.get(k - 1), c.get(k + 1), nb) <= 89.9)) {
          c.remove(k);
          lastFree--;
          changed = true;
          diagnostics.merge("straightened_vertices", 1, (u, v) -> (Integer) u + (Integer) v);
          if (k > 1) k--;
        } else k++;
      }
      // A leaf that reaches its entrance ray over a short run with a small turn (typically a
      // chamber 0.3 m beside the ray line): slide the ray corner towards the building until that
      // run is 2 m long. The ray keeps its R02 direction and stays past its boundary station.
      if (terminal != null && !e.to.terminal.free && c.size() >= 3) {
        Terminal t = e.to.terminal;
        int q = c.size() - 2;
        Coordinate prev = c.get(q - 1), corner = c.get(q), pa = q >= 2 ? c.get(q - 2) : before;
        double turn = Geo.turn(prev, corner, terminal);
        if (turn >= .01 && turn < 20 && prev.distance(corner) < 2) {
          double sMin = t.p.distance(t.port),
              s0 = (corner.x - t.p.x) * t.ux + (corner.y - t.p.y) * t.uy;
          for (double st = s0 - .25; st >= sMin - 1e-9; st -= .25) {
            Coordinate x = new Coordinate(t.p.x + t.ux * st, t.p.y + t.uy * st);
            if (prev.distance(x) < 2) continue;
            if (Geo.turn(prev, x, terminal) > 89.9) break;
            if (!valid(prev, x, e.dn, terminal, root, pa)
                || !checks.segment(x, terminal, e.dn, terminal, null)) continue;
            c.set(q, x);
            changed = true;
            diagnostics.merge("ray_corners_slid", 1, (u, v) -> (Integer) u + (Integer) v);
            break;
          }
        }
      }
      if (changed) {
        out.set(m, Geo.line(c));
        any = true;
      }
    }
    return any ? out : pieces;
  }

  /** Vectorize; if the real lengths change a DN (length limit), fix the DN and vectorize again. */
  private boolean pinChambers = !Boolean.getBoolean("heatroute.noPin");

  /**
   * Vector geometry of a forest with its exact verdict. Chambers pinned at their Steiner points
   * first; if that geometry is rejected, the same forest again with chambers projected onto the
   * straightened chains (measured: pinning alone lost the best eight forests of the road dataset to
   * junction overlaps).
   */
  private String vectorizeChecked(Forest f, List<List<Network.Tree>> out) {
    List<Network.Tree> candidate = vectorize(f);
    String problem = exactProblem(candidate);
    if (problem != null && pinChambers) {
      List<PEdge> kept = new ArrayList<>(culprits);
      pinChambers = false;
      try {
        List<Network.Tree> unpinned = vectorize(f);
        if (exactProblem(unpinned) == null) {
          diagnostics.merge("unpinned_fallbacks", 1, (x, y) -> (Integer) x + (Integer) y);
          out.add(unpinned);
          return null;
        }
      } finally {
        pinChambers = true;
      }
      culprits.clear();
      culprits.addAll(kept);
    }
    out.add(candidate);
    return problem;
  }

  private List<Network.Tree> vectorize(Forest input) {
    Forest f = input.copy();
    score(f);
    List<Network.Tree> trees = null;
    for (int round = 0; round < 3; round++) {
      trees = vectorizeOnce(f);
      Map<PEdge, Integer> before = new IdentityHashMap<>();
      for (PEdge e : f.edges) before.put(e, e.dn);
      for (PEdge e : f.edges) {
        LineString l = lastLines.get(e);
        if (l != null) e.len = l.getLength();
      }
      for (PNode n : f.nodes) if (n.kind == ROOT) for (PEdge e : n.out) size(e);
      boolean changed = false;
      for (PEdge e : f.edges)
        if (e.dn != before.get(e)) {
          changed = true;
          e.plannedDn = Math.min(e.plannedDn, before.get(e));
        }
      if (!changed) break;
      diagnostics.merge("dn_revectorize", 1, (a, b) -> (Integer) a + (Integer) b);
    }
    return trees;
  }

  private Map<PEdge, LineString> lastLines = new IdentityHashMap<>();
  private Map<PEdge, Network.Edge> lastNetEdges = new IdentityHashMap<>();

  private List<Network.Tree> vectorizeOnce(Forest f) {
    placed.clear();
    placedWidth.clear();
    placedEnds.clear();
    debug.clear();
    rawSteps = 0;
    culprits.clear();
    Map<PNode, Network.Node> nodes = new IdentityHashMap<>();
    Map<PNode, Coordinate> incoming = new IdentityHashMap<>();
    Map<String, Network.Tree> trees = new TreeMap<>();
    for (PNode n : f.nodes) {
      if (n.kind == ROOT) {
        Network.Tree t = new Network.Tree(n.site.root);
        trees.put(n.site.root.key, t);
        nodes.put(n, t.start);
      } else if (n.kind == LEAF) {
        List<Dataset.Demand> ds = new ArrayList<>();
        for (Dataset.Demand d : data.demands)
          if (Geo.canonical(d.terminals.get(0).geometry.getCoordinate()).distance(n.terminal.p)
              < 1e-6) ds.add(d);
        nodes.put(
            n,
            new Network.Node(
                "d:" + n.terminal.demand.id,
                n.terminal.p,
                ds,
                n.terminal.demand.terminals.get(0).id));
      }
    }
    // A chain follows the heaviest child at every chamber, so trunks are straightened through their
    // chambers.
    Deque<PEdge> starts = new ArrayDeque<>();
    for (PNode n : f.nodes) if (n.kind == ROOT) starts.addAll(n.out);
    // insertion order (not identity hash order): the audit and the repair order are the same on
    // every run
    Map<PEdge, LineString> lines = new LinkedHashMap<>();
    Map<PEdge, Network.Edge> netEdges = new IdentityHashMap<>();
    lastLines = lines;
    lastNetEdges = netEdges;
    int failures = 0;
    while (!starts.isEmpty()) {
      PEdge first = starts.remove();
      List<PEdge> chain = new ArrayList<>();
      for (PEdge e = first; ; ) {
        chain.add(e);
        if (e.to.kind == LEAF) break;
        PEdge cont = null;
        for (PEdge c : e.to.out)
          if (cont == null
              || c.flow > cont.flow + 1e-9
              || Math.abs(c.flow - cont.flow) < 1e-9 && c.len > cont.len) cont = c;
        e = cont;
      }
      List<LineString> pieces = null;
      int rawBefore = rawSteps;
      if (chain.size() == 1) {
        LineString snapped = raySnap(chain.get(0), nodes, incoming, lines, netEdges, trees);
        if (snapped != null) {
          pieces = List.of(snapped);
          diagnostics.merge("ray_snaps", 1, (a, b) -> (Integer) a + (Integer) b);
        }
      }
      for (int attempt = 0; attempt < 2 && pieces == null; attempt++) {
        if (attempt == 1) {
          boolean any = false;
          for (PEdge e : chain) any |= reroute(e);
          if (!any) continue;
        }
        int rb = rawSteps;
        pieces = vectorChain(chain, nodes, incoming, false, lines);
        if (pieces != null
            && (rawSteps > rb
                || !junctionOk(chain, pieces, nodes, lines)
                || placedConflict(chain, pieces, lines))) pieces = null;
      }
      if (pieces == null
          && chain.get(0).from.kind == BRANCH
          && !Boolean.getBoolean("heatroute.noSlide")) {
        pieces = slideChamber(chain, nodes, incoming, lines, netEdges, trees);
        if (pieces != null)
          diagnostics.merge("chamber_slides", 1, (x, y) -> (Integer) x + (Integer) y);
      }
      if (pieces == null) {
        Map<PNode, Network.Node> saved = new IdentityHashMap<>(nodes);
        pieces = exactChain(chain, nodes, incoming, trees);
        if (pieces != null
            && (!junctionOk(chain, pieces, nodes, lines) || placedConflict(chain, pieces, lines))) {
          pieces = null;
          nodes.clear();
          nodes.putAll(saved);
        }
        if (pieces != null)
          diagnostics.merge("exact_router_chains", 1, (x, y) -> (Integer) x + (Integer) y);
      }
      if (pieces == null) {
        pieces = vectorChainCore(chain, nodes, incoming, true, false);
        culprits.add(chain.get(0));
      }
      if (pieces == null) {
        failures++;
        culprits.add(chain.get(0));
        continue;
      }
      if (rawSteps > rawBefore) culprits.add(chain.get(0));
      List<LineString> clean = straighten(chain, pieces, incoming);
      if (clean != pieces
          && junctionOk(chain, clean, nodes, lines)
          && !placedConflict(chain, clean, lines)) {
        pieces = clean;
        for (int m = 0; m < chain.size(); m++) {
          PEdge e = chain.get(m);
          LineString l = pieces.get(m);
          if (e.to.kind != LEAF) incoming.put(e.to, l.getCoordinateN(l.getNumPoints() - 2));
        }
      }
      for (int m = 0; m < chain.size(); m++) {
        PEdge e = chain.get(m);
        LineString line = pieces.get(m);
        lines.put(e, line);
        placed.add(line);
        placedWidth.add(Catalog.pipe(e.dn).width);
        placedEnds.add(
            new Coordinate[] {
              line.getCoordinateN(0), line.getCoordinateN(line.getNumPoints() - 1)
            });
        Network.Edge ne = new Network.Edge(nodes.get(e.from), nodes.get(e.to), line);
        netEdges.put(e, ne);
        treeOf(e, trees).edges.add(ne);
        if (e.to.kind != LEAF)
          for (PEdge c : e.to.out)
            if (m + 1 >= chain.size() || c != chain.get(m + 1)) starts.add(c);
      }
    }
    diagnostics.put("vector_failures", failures);
    diagnostics.put("raw_steps", rawSteps);
    lastVectorFailures = failures;
    // turn from the incoming pipe to every outgoing pipe at a chamber (Evaluation rule)
    for (PEdge e : lines.keySet()) {
      if (e.from.in == null) continue;
      LineString parent = lines.get(e.from.in), child = lines.get(e);
      if (parent == null) continue;
      if (Geo.turn(
              parent.getCoordinateN(parent.getNumPoints() - 2),
              child.getCoordinateN(0),
              child.getCoordinateN(1))
          > 90.000001) {
        culprits.add(e);
        diagnostics.merge("chamber_turn_problems", 1, (a, b) -> (Integer) a + (Integer) b);
      }
    }
    // Full audit mirroring Evaluation/compatible: every problem names the weaker edge as the
    // culprit.
    List<PEdge> all = new ArrayList<>(lines.keySet());
    int problems = 0;
    for (PEdge e : all) {
      try {
        checks.events(
            lines.get(e),
            e.dn,
            e.to.kind == LEAF ? e.to.terminal.p : null,
            e.from.kind == ROOT ? e.from.site.root : null);
      } catch (Failure x) {
        culprits.add(e);
        problems++;
        diagnostics.put("audit_" + problems, "EDGE " + x.code);
      }
    }
    for (int i = 0; i < all.size(); i++)
      for (int j = i + 1; j < all.size(); j++) {
        PEdge a = all.get(i), b = all.get(j);
        LineString la = lines.get(a), lb = lines.get(b);
        if (!la.getEnvelopeInternal().intersects(lb.getEnvelopeInternal()) && la.distance(lb) > 3)
          continue;
        PNode n =
            a.from == b.from || a.from == b.to
                ? a.from
                : a.to == b.from || a.to == b.to ? a.to : null;
        boolean sameRootPoint =
            n == null
                && a.from.kind == ROOT
                && b.from.kind == ROOT
                && a.from.site.root.key.equals(b.from.site.root.key);
        PEdge weak = a.flow <= b.flow ? a : b;
        String why = null;
        Geometry hit = la.intersection(lb);
        if (n != null || sameRootPoint) {
          Coordinate at = n != null ? nodes.get(n).p : a.from.site.p;
          if (hit.getDimension() > 0
              || hit.getNumPoints() > 1
              || !hit.isEmpty() && hit.distance(Geo.point(at)) > 1e-6) why = "INTERSECTION";
          else if (!JunctionGeometry.fits(
              la, Catalog.pipe(a.dn).width, lb, Catalog.pipe(b.dn).width, at)) why = "JUNCTION";
        } else {
          if (!hit.isEmpty()) why = "INTERSECTION";
          else if (la.distance(lb) < (Catalog.pipe(a.dn).width + Catalog.pipe(b.dn).width) / 2)
            why = "OVERLAP";
        }
        if (why != null) {
          culprits.add(weak);
          problems++;
          diagnostics.put("audit_" + problems, why + " dn " + a.dn + "/" + b.dn);
          debug.add(debugFeature(la, "vector", a, why));
          debug.add(debugFeature(lb, "vector", b, why));
        }
      }
    String path = System.getProperty("heatroute.debug");
    if (path != null && debugTag != null)
      path = path.replace(".geojson", "-" + debugTag + ".geojson");
    if (path != null)
      try {
        Map<String, Object> fc = new LinkedHashMap<>();
        fc.put("type", "FeatureCollection");
        fc.put("features", debug);
        Json.write(java.nio.file.Paths.get(path), fc);
      } catch (java.io.IOException ignored) {
      }
    return new ArrayList<>(trees.values());
  }

  /**
   * Exact geometry of a chain of edges (root/chamber .. terminal); chambers are placed on the
   * tautened line.
   */
  /**
   * Chambers stay where the optimiser put them (Steiner points) if that geometry is valid; else
   * they are projected onto the straightened chain.
   */
  private List<LineString> vectorChain(
      List<PEdge> chain,
      Map<PNode, Network.Node> nodes,
      Map<PNode, Coordinate> incoming,
      boolean force,
      Map<PEdge, LineString> lines) {
    if (chain.size() > 1 && pinChambers) {
      int rb = rawSteps;
      List<LineString> pinned = vectorChainCore(chain, nodes, incoming, false, true);
      if (pinned != null
          && rawSteps == rb
          && junctionOk(chain, pinned, nodes, lines)
          && !placedConflict(chain, pinned, lines)) {
        diagnostics.merge("pinned_chains", 1, (x, y) -> (Integer) x + (Integer) y);
        return pinned;
      }
      rawSteps = rb;
    }
    return vectorChainCore(chain, nodes, incoming, force, false);
  }

  private List<LineString> vectorChainCore(
      List<PEdge> chain,
      Map<PNode, Network.Node> nodes,
      Map<PNode, Coordinate> incoming,
      boolean force,
      boolean pin) {
    Grid g = plan;
    PEdge head = chain.get(0), tail = chain.get(chain.size() - 1);
    Existing.Root root = head.from.kind == ROOT ? head.from.site.root : null;
    Terminal term = tail.to.terminal;
    Coordinate terminal = term.p;
    Coordinate start = nodes.get(head.from).p;
    List<Coordinate> raw = new ArrayList<>();
    List<Integer> dnList = new ArrayList<>();
    int[] nodeRaw = new int[chain.size() - 1];
    raw.add(start);
    dnList.add(head.dn);
    for (int m = 0; m < chain.size(); m++) {
      PEdge e = chain.get(m);
      int k0 = m == 0 && head.from.kind == ROOT ? 0 : 1;
      for (int k = k0; k < e.cells.length; k++) {
        Coordinate c = g.center(e.cells[k]);
        if (m == chain.size() - 1 && k == e.cells.length - 1) c = e.last;
        if (raw.get(raw.size() - 1).distance(c) > 1e-6) {
          raw.add(c);
          dnList.add(e.dn);
        }
      }
      if (m < chain.size() - 1) {
        nodeRaw[m] = raw.size() - 1;
      }
    }
    int[] dn = new int[raw.size()];
    for (int i = 0; i < dn.length; i++) dn[i] = dnList.get(i);
    // DN of a raw point applies to the segment that starts there; a chamber point belongs to the
    // downstream edge.
    for (int m = 0; m < nodeRaw.length; m++) dn[nodeRaw[m]] = chain.get(m + 1).dn;
    for (int i = dn.length - 2; i >= 0; i--)
      if (i > 0 && dn[i] > dn[i - 1]) {} // DN never increases downstream
    Coordinate before = incoming.get(head.from);
    // Chambers stay exact vertices only in pinned mode; in unpinned mode the whole chain is one
    // stretch and
    // each chamber is projected onto the straightened line afterwards (below).
    List<Integer> pinList = new ArrayList<>();
    if (pin) for (int r : nodeRaw) pinList.add(r);
    List<Integer> idx = tautPinned(raw, dn, terminal, root, before, terminal, pinList);
    if (idx == null) return null;
    if (!pinList.isEmpty()) pinnedRaw = new HashSet<>(pinList);
    List<Coordinate> pts = new ArrayList<>();
    for (int i : idx) pts.add(raw.get(i));
    try {
      relax(pts, idx, dn, terminal, root, before, terminal);
      slide(pts, term, tail.dn, root, before);
      relax(pts, idx, dn, terminal, root, before, terminal);
      // A second tautening pass over the already-relaxed points: a chord invalid at raw grid
      // resolution near a
      // tight corner can become valid once neighbouring vertices sit on their own chords, so a
      // leftover vertex
      // collapses instead of leaving a small kink. A round that needs a raw step is dropped: the
      // sparse points
      // would otherwise be joined without a validity check and the callers' raw-step guards would
      // reject the chain.
      for (int round = 0; round < 2; round++) {
        List<Integer> pinPos = pinPositions(idx, pinList);
        int rawBefore = rawSteps;
        List<Integer> mapped =
            tautPinned(pts, dnAt(idx, dn), terminal, root, before, terminal, pinPos);
        if (rawSteps > rawBefore) {
          for (int k = rawBefore; k < rawSteps; k++) diagnostics.remove("raw_" + k);
          rawSteps = rawBefore;
          break;
        }
        if (mapped == null || mapped.size() >= pts.size()) break;
        List<Coordinate> newPts = new ArrayList<>();
        List<Integer> newIdx = new ArrayList<>();
        for (int q : mapped) {
          newPts.add(pts.get(q));
          newIdx.add(idx.get(q));
        }
        pts = newPts;
        idx = newIdx;
        relax(pts, idx, dn, terminal, root, before, terminal);
      }
      defan(pts, idx, dn, terminal, root, before, terminal, term);
    } finally {
      pinnedRaw = Collections.emptySet();
    }
    // place chambers on the tautened polyline
    List<Coordinate> line = new ArrayList<>(pts);
    List<Integer> lineIdx = new ArrayList<>(idx);
    int[] nodeAt = new int[nodeRaw.length];
    for (int m = 0; m < nodeRaw.length; m++) {
      int r = nodeRaw[m];
      int k = 0;
      while (k + 1 < lineIdx.size() && lineIdx.get(k + 1) <= r) k++;
      if (lineIdx.get(k) == r) {
        nodeAt[m] = k;
        continue;
      }
      if (k + 1 >= line.size()) return null;
      Coordinate a = line.get(k), b = line.get(k + 1);
      LineSegment seg = new LineSegment(a, b);
      double len = seg.getLength(),
          t = Math.max(0, Math.min(1, seg.projectionFactor(raw.get(r)))) * len;
      t = outsideWindows(a, b, t, root);
      if (t < 0) return null;
      t = Math.max(Math.min(t, len - .3), .3);
      if (len < .6) return null;
      Coordinate q = seg.pointAlong(t / len);
      line.add(k + 1, q);
      lineIdx.add(k + 1, r);
      nodeAt[m] = k + 1;
    }
    List<Coordinate> full = new ArrayList<>(line);
    full.add(terminal);
    // canonical chamber coordinates shared by both adjacent pieces
    for (int m = 0; m < nodeAt.length; m++) full.set(nodeAt[m], Geo.canonical(full.get(nodeAt[m])));
    List<LineString> pieces = new ArrayList<>();
    int from = 0;
    String problem = null;
    for (int m = 0; m < chain.size(); m++) {
      int to = m < nodeAt.length ? nodeAt[m] : full.size() - 1;
      if (to <= from) {
        problem = "EMPTY_PIECE";
        break;
      }
      LineString piece = Geo.canonical(Geo.simplify(Geo.line(full.subList(from, to + 1))));
      PEdge e = chain.get(m);
      try {
        checks.events(piece, e.dn, m == chain.size() - 1 ? terminal : null, m == 0 ? root : null);
      } catch (Failure x) {
        problem = x.code + " " + x.getMessage() + " " + x.featureId;
      }
      pieces.add(piece);
      from = to;
    }
    if (problem != null && !force) return null;
    if (problem != null) diagnostics.put("vector_failure_" + (vectorProblems++), problem);
    debug.add(debugFeature(Geo.line(raw), "raw", tail, null));
    for (int m = 0; m < pieces.size(); m++)
      debug.add(debugFeature(pieces.get(m), "vector", chain.get(m), problem));
    if (pieces.size() < chain.size()) return null;
    for (int m = 0; m < nodeAt.length; m++) {
      PNode n = chain.get(m).to;
      Coordinate q = full.get(nodeAt[m]);
      nodes.put(n, new Network.Node("branch:" + Geo.key(q), q));
      incoming.put(n, full.get(nodeAt[m] - 1));
    }
    return pieces;
  }

  /**
   * Pieces must keep the gabarit distance to every placed pipe they do not share a node with, and
   * turn <= 90 degrees at their start chamber.
   */
  private boolean placedConflict(
      List<PEdge> chain, List<LineString> pieces, Map<PEdge, LineString> lines) {
    for (int m = 0; m < chain.size(); m++) {
      PEdge e = chain.get(m);
      LineString l = pieces.get(m);
      for (Map.Entry<PEdge, LineString> o : lines.entrySet()) {
        PEdge q = o.getKey();
        boolean shared =
            q.from == e.from
                || q.to == e.from
                || q.from == e.to
                || q.to == e.to
                || e.from.kind == ROOT
                    && q.from.kind == ROOT
                    && e.from.site.root.key.equals(q.from.site.root.key);
        if (shared) continue;
        if (l.distance(o.getValue())
            < (Catalog.pipe(e.dn).width + Catalog.pipe(q.dn).width) / 2 + .02) return true;
      }
      if (e.from.in != null) {
        LineString parent = m > 0 ? pieces.get(m - 1) : lines.get(e.from.in);
        if (parent != null
            && Geo.turn(
                    parent.getCoordinateN(parent.getNumPoints() - 2),
                    l.getCoordinateN(0),
                    l.getCoordinateN(1))
                > 89.95) return true;
      }
    }
    return false;
  }

  /** Pieces of a new chain must not overlap already placed pipes beyond the common first runs. */
  private boolean junctionOk(
      List<PEdge> chain,
      List<LineString> pieces,
      Map<PNode, Network.Node> nodes,
      Map<PEdge, LineString> lines) {
    for (int m = 0; m < chain.size(); m++) {
      PEdge e = chain.get(m);
      for (Map.Entry<PEdge, LineString> o : lines.entrySet()) {
        PEdge q = o.getKey();
        PNode n =
            q.from == e.from || q.to == e.from
                ? e.from
                : q.from == e.to || q.to == e.to ? e.to : null;
        if (n == null || n.kind == LEAF) continue;
        if (!JunctionGeometry.fits(
            pieces.get(m),
            Catalog.pipe(e.dn).width,
            o.getValue(),
            Catalog.pipe(q.dn).width,
            nodes.get(n).p)) return false;
      }
      if (m > 0
          && !JunctionGeometry.fits(
              pieces.get(m),
              Catalog.pipe(e.dn).width,
              pieces.get(m - 1),
              Catalog.pipe(chain.get(m - 1).dn).width,
              nodes.get(e.from).p)) return false;
    }
    return true;
  }

  /**
   * A leaf hanging from a degree-3 chamber: move the chamber along the trunk to where the entrance
   * ray meets it.
   */
  /**
   * Checks for a chamber moved off the trunk: turns at and next to it, the far ends of one-segment
   * pieces, and the distance of both redrawn pieces to every other placed pipe.
   */
  private boolean movedCornerFits(
      Coordinate[] c1,
      Coordinate[] c2,
      LineString l1,
      LineString l2,
      Coordinate qq,
      PEdge up,
      PEdge cont,
      LineString a,
      LineString b) {
    if (Geo.turn(c1[c1.length - 2], qq, c2[1]) > 89.9) return false;
    if (c1.length >= 3 && Geo.turn(c1[c1.length - 3], c1[c1.length - 2], qq) > 89.9) return false;
    if (c2.length >= 3 && Geo.turn(qq, c2[1], c2[2]) > 89.9) return false;
    // a one-segment piece turns at its far end: allowed only from a root with this single line, or
    // into a branch
    // chamber whose placed outgoing lines still fit
    if (c1.length == 2 && !(up.from.kind == ROOT && up.from.out.size() == 1)) return false;
    if (c2.length == 2) {
      if (cont.to.kind != BRANCH) return false;
      Coordinate end = c2[1];
      for (int i = 0; i < placed.size(); i++) {
        LineString o = placed.get(i);
        if (o == a || o == b) continue;
        if (o.getCoordinateN(0).distance(end) < 1e-6
            && (Geo.turn(qq, end, o.getCoordinateN(1)) > 89.9
                || !JunctionGeometry.fits(
                    l2, Catalog.pipe(cont.dn).width, o, placedWidth.get(i), end))) return false;
      }
    }
    for (int i = 0; i < placed.size(); i++) {
      LineString o = placed.get(i);
      if (o == a || o == b) continue;
      Coordinate[] ends = placedEnds.get(i);
      boolean at1 = false, at2 = false;
      for (Coordinate en : ends) {
        if (en.distance(c1[0]) < 1e-6) at1 = true;
        if (en.distance(c2[c2.length - 1]) < 1e-6) at2 = true;
      }
      double gap = placedWidth.get(i) / 2 + .05;
      if (!at1 && o.distance(l1) < Catalog.pipe(up.dn).width / 2 + gap) return false;
      if (!at2 && o.distance(l2) < Catalog.pipe(cont.dn).width / 2 + gap) return false;
    }
    return true;
  }

  private LineString raySnap(
      PEdge e,
      Map<PNode, Network.Node> nodes,
      Map<PNode, Coordinate> incoming,
      Map<PEdge, LineString> lines,
      Map<PEdge, Network.Edge> netEdges,
      Map<String, Network.Tree> trees) {
    PNode n = e.from;
    if (n.kind != BRANCH || e.to.kind != LEAF || n.out.size() != 2 || n.in == null) return null;
    PEdge up = n.in, cont = n.out.get(0) == e ? n.out.get(1) : n.out.get(0);
    LineString a = lines.get(up), b = lines.get(cont);
    if (a == null || b == null || netEdges.get(up) == null || netEdges.get(cont) == null)
      return null;
    Terminal t = e.to.terminal;
    Coordinate p = t.p;
    List<Coordinate> comb = new ArrayList<>(Arrays.asList(a.getCoordinates()));
    Coordinate[] bc = b.getCoordinates();
    for (int i = 1; i < bc.length; i++) comb.add(bc[i]);
    LineString trunk = Geo.line(comb);
    LengthIndexedLine li = new LengthIndexedLine(trunk);
    double total = trunk.getLength(), sN = a.getLength();
    Existing.Root root = up.from.kind == ROOT ? up.from.site.root : null;
    double sMin = t.p.distance(t.port);
    // candidate chamber stations along the trunk x candidate bends on the entrance ray
    List<double[]> cands = new ArrayList<>();
    for (double sx = Math.max(1.5, sN - 25);
        sx <= Math.min(total - 1.5, sN + 25) + 1e-9;
        sx += .5) {
      Coordinate x = li.extractPoint(sx),
          before = li.extractPoint(Math.max(0, sx - 1)),
          after = li.extractPoint(Math.min(total, sx + 1));
      double ax = (x.x - p.x) * t.ux + (x.y - p.y) * t.uy; // station of x projected on the ray
      // direct: x lies on the ray
      Coordinate onRay = new Coordinate(p.x + t.ux * ax, p.y + t.uy * ax);
      if (ax >= sMin && onRay.distance(x) < .05) cands.add(new double[] {x.distance(p), sx, -1});
      for (double r = sMin; r <= sMin + 30 && r <= ax + 1e-9; r += .5) {
        Coordinate q = new Coordinate(p.x + t.ux * r, p.y + t.uy * r);
        if (Geo.turn(before, x, q) > 89.9 || Geo.turn(x, q, p) > 89.9) continue;
        if (angle(
                new Coordinate(after.x - x.x, after.y - x.y), new Coordinate(q.x - x.x, q.y - x.y))
            < 25) continue;
        // no stub: a bend closer than 2 m to the chamber is a small kink (appendix §2.1); the exact
        // crossing covers it
        if (x.distance(q) < 2) continue;
        cands.add(new double[] {x.distance(q) + r, sx, r});
      }
    }
    // Exact crossings of the trunk with the entrance ray line: a chamber there needs no bend on the
    // ray. The 0.5 m
    // station grid above almost never lands on the line, which used to leave a 0.5-1.5 m stub and
    // an extra turn.
    {
      Coordinate[] tc = trunk.getCoordinates();
      double run = 0, lo = Math.max(1.5, sN - 25), hi = Math.min(total - 1.5, sN + 25);
      for (int i = 1; i < tc.length; i++) {
        Coordinate s0 = tc[i - 1], s1 = tc[i];
        double dx = s1.x - s0.x,
            dy = s1.y - s0.y,
            len = Math.hypot(dx, dy),
            den = t.ux * dy - t.uy * dx;
        if (len > 1e-9 && Math.abs(den) > 1e-12) {
          double qx = s0.x - p.x,
              qy = s0.y - p.y,
              r = (qx * dy - qy * dx) / den,
              w = (qx * t.uy - qy * t.ux) / den,
              sx = run + w * len;
          if (w >= 0 && w <= 1 && r >= sMin && sx >= lo - 1e-9 && sx <= hi + 1e-9)
            cands.add(new double[] {r, Math.max(lo, Math.min(hi, sx)), -1});
        }
        run += len;
      }
    }
    // Move the chamber itself (a trunk corner) onto the ray line when the ray passes within 3 m of
    // it: the branch is
    // then the entrance ray alone and both trunk pieces stay straight, instead of a stub and an
    // extra turn.
    {
      Coordinate pN = bc[0];
      double sc = (pN.x - p.x) * t.ux + (pN.y - p.y) * t.uy;
      Coordinate foot = new Coordinate(p.x + t.ux * sc, p.y + t.uy * sc);
      if (sc >= sMin && foot.distance(pN) > 1e-6 && foot.distance(pN) < 3)
        cands.add(new double[] {sc, sN, -3, foot.x, foot.y});
    }
    cands.sort(Comparator.comparingDouble(c -> c[0]));
    // the current geometry length is the bar to beat only loosely; take the shortest fully valid
    // option
    LineString branch = null, p1 = null, p2 = null;
    Coordinate q = null, inDir = null;
    int tried = 0;
    for (double[] c : cands) {
      if (tried++ > 250) break;
      boolean move = c[2] == -3;
      Coordinate x = move ? new Coordinate(c[3], c[4]) : li.extractPoint(c[1]);
      if (!move) {
        Coordinate[] segEnds = segmentAround(comb, trunk, c[1]);
        if (outsideWindows(segEnds[0], segEnds[1], segEnds[0].distance(x), null) < 0) continue;
        double tw = outsideWindows(segEnds[0], segEnds[1], segEnds[0].distance(x), null);
        if (Math.abs(tw - segEnds[0].distance(x)) > 1e-6) continue;
      }
      Coordinate qq = Geo.canonical(x);
      List<Coordinate> br = new ArrayList<>();
      br.add(qq);
      if (c[2] >= 0) br.add(new Coordinate(p.x + t.ux * c[2], p.y + t.uy * c[2]));
      br.add(p);
      boolean ok = true;
      for (int i = 1; i < br.size(); i++)
        if (!valid(br.get(i - 1), br.get(i), e.dn, p, null, null)
            && !(i == br.size() - 1 && checks.segment(br.get(i - 1), p, e.dn, p, null))) {
          ok = false;
          break;
        }
      if (!ok) continue;
      LineString bl = Geo.canonical(Geo.simplify(Geo.line(br)));
      LineString l1 = move ? a : Geo.canonical(Geo.simplify(Geo.sub(trunk, 0, c[1]))),
          l2 = move ? b : Geo.canonical(Geo.simplify(Geo.sub(trunk, c[1], total)));
      Coordinate[] c1 = l1.getCoordinates().clone(), c2 = l2.getCoordinates().clone();
      c1[c1.length - 1] = qq;
      c2[0] = qq;
      l1 = Geo.line(Arrays.asList(c1));
      l2 = Geo.line(Arrays.asList(c2));
      if (move && !movedCornerFits(c1, c2, l1, l2, qq, up, cont, a, b)) continue;
      try {
        checks.events(l1, up.dn, null, root);
        checks.events(l2, cont.dn, cont.to.kind == LEAF ? cont.to.terminal.p : null, null);
        checks.events(bl, e.dn, p, null);
      } catch (Failure f) {
        continue;
      }
      if (Geo.turn(c1[c1.length - 2], qq, bl.getCoordinateN(1)) > 89.9) continue;
      if (!JunctionGeometry.fits(bl, Catalog.pipe(e.dn).width, l1, Catalog.pipe(up.dn).width, qq)
          || !JunctionGeometry.fits(
              bl, Catalog.pipe(e.dn).width, l2, Catalog.pipe(cont.dn).width, qq)
          || !JunctionGeometry.fits(
              l1, Catalog.pipe(up.dn).width, l2, Catalog.pipe(cont.dn).width, qq)) continue;
      boolean clash = false;
      for (int i = 0; i < placed.size(); i++) {
        LineString o = placed.get(i);
        if (o == a || o == b) continue;
        if (o.distance(bl) < (Catalog.pipe(e.dn).width + placedWidth.get(i)) / 2 + .05) {
          clash = true;
          break;
        }
      }
      if (clash) continue;
      branch = bl;
      p1 = l1;
      p2 = l2;
      q = qq;
      inDir = c1[c1.length - 2];
      if (move) {
        diagnostics.merge("moved_chambers", 1, (u, w) -> (Integer) u + (Integer) w);
        if (c2.length == 2) incoming.put(cont.to, qq);
      }
      break;
    }
    if (branch == null) return null;
    // commit: new chamber position, re-split trunk pieces
    Network.Node node = new Network.Node("branch:" + Geo.key(q), q);
    nodes.put(n, node);
    incoming.put(n, inDir);
    Network.Tree tree = treeOf(up, trees);
    Network.Edge oa = netEdges.get(up), ob = netEdges.get(cont);
    tree.edges.remove(oa);
    tree.edges.remove(ob);
    Network.Edge na = new Network.Edge(oa.from, node, p1), nb = new Network.Edge(node, ob.to, p2);
    tree.edges.add(na);
    tree.edges.add(nb);
    netEdges.put(up, na);
    netEdges.put(cont, nb);
    lines.put(up, p1);
    lines.put(cont, p2);
    for (int i = 0; i < placed.size(); i++) {
      if (placed.get(i) == a) {
        placed.set(i, p1);
        placedEnds.set(i, new Coordinate[] {p1.getCoordinateN(0), q});
      } else if (placed.get(i) == b) {
        placed.set(i, p2);
        placedEnds.set(i, new Coordinate[] {q, p2.getCoordinateN(p2.getNumPoints() - 1)});
      }
    }
    debug.add(debugFeature(branch, "vector", e, null));
    return branch;
  }

  /**
   * A chain that cannot leave its degree-3 chamber (turn/junction): slide the chamber along the
   * straightened trunk.
   */
  private List<LineString> slideChamber(
      List<PEdge> chain,
      Map<PNode, Network.Node> nodes,
      Map<PNode, Coordinate> incoming,
      Map<PEdge, LineString> lines,
      Map<PEdge, Network.Edge> netEdges,
      Map<String, Network.Tree> trees) {
    PNode n = chain.get(0).from;
    if (n.in == null || n.out.size() != 2) return null;
    PEdge up = n.in, cont = n.out.get(0) == chain.get(0) ? n.out.get(1) : n.out.get(0);
    LineString a = lines.get(up), b = lines.get(cont);
    if (a == null || b == null || netEdges.get(up) == null || netEdges.get(cont) == null)
      return null;
    int ia = -1, ib = -1;
    for (int i = 0; i < placed.size(); i++) {
      if (placed.get(i) == a) ia = i;
      if (placed.get(i) == b) ib = i;
    }
    if (ia < 0 || ib < 0) return null;
    List<Coordinate> comb = new ArrayList<>(Arrays.asList(a.getCoordinates()));
    Coordinate[] bc = b.getCoordinates();
    for (int i = 1; i < bc.length; i++) comb.add(bc[i]);
    LineString trunk = Geo.line(comb);
    LengthIndexedLine li = new LengthIndexedLine(trunk);
    double total = trunk.getLength(), sN = a.getLength();
    Existing.Root root = up.from.kind == ROOT ? up.from.site.root : null;
    Network.Node oldNode = nodes.get(n);
    Coordinate oldIncoming = incoming.get(n);
    Coordinate[] ea = placedEnds.get(ia), eb = placedEnds.get(ib);
    for (int k = 1; k <= 40; k++) {
      double d = (k + 1) / 2 * (k % 2 == 1 ? 1 : -1) * 0.75, sx = sN + d;
      if (sx < 1.5 || total - sx < 1.5) continue;
      Coordinate x = li.extractPoint(sx);
      Coordinate[] seg = segmentAround(comb, trunk, sx);
      double tw = outsideWindows(seg[0], seg[1], seg[0].distance(x), null);
      if (tw < 0 || Math.abs(tw - seg[0].distance(x)) > 1e-6) continue;
      Coordinate q = Geo.canonical(x);
      LineString l1 = Geo.canonical(Geo.simplify(Geo.sub(trunk, 0, sx))),
          l2 = Geo.canonical(Geo.simplify(Geo.sub(trunk, sx, total)));
      Coordinate[] c1 = l1.getCoordinates(), c2 = l2.getCoordinates();
      if (c1.length < 2 || c2.length < 2) continue;
      c1[c1.length - 1] = q;
      c2[0] = q;
      l1 = Geo.line(Arrays.asList(c1));
      l2 = Geo.line(Arrays.asList(c2));
      try {
        checks.events(l1, up.dn, null, root);
        checks.events(l2, cont.dn, cont.to.kind == LEAF ? cont.to.terminal.p : null, null);
      } catch (Failure f) {
        continue;
      }
      if (!JunctionGeometry.fits(l1, Catalog.pipe(up.dn).width, l2, Catalog.pipe(cont.dn).width, q))
        continue;
      // tentative state for the child chain
      nodes.put(n, new Network.Node("branch:" + Geo.key(q), q));
      incoming.put(n, c1[c1.length - 2]);
      placed.set(ia, l1);
      placedEnds.set(ia, new Coordinate[] {l1.getCoordinateN(0), q});
      placed.set(ib, l2);
      placedEnds.set(ib, new Coordinate[] {q, l2.getCoordinateN(l2.getNumPoints() - 1)});
      lines.put(up, l1);
      lines.put(cont, l2);
      int rb = rawSteps;
      List<LineString> pieces = vectorChain(chain, nodes, incoming, false, lines);
      if (pieces != null
          && rawSteps == rb
          && junctionOk(chain, pieces, nodes, lines)
          && !placedConflict(chain, pieces, lines)) {
        Network.Node node = nodes.get(n);
        Network.Tree tree = treeOf(up, trees);
        Network.Edge oa = netEdges.get(up), ob = netEdges.get(cont);
        tree.edges.remove(oa);
        tree.edges.remove(ob);
        Network.Edge na = new Network.Edge(oa.from, node, l1),
            nb = new Network.Edge(node, ob.to, l2);
        tree.edges.add(na);
        tree.edges.add(nb);
        netEdges.put(up, na);
        netEdges.put(cont, nb);
        return pieces;
      }
      rawSteps = rb;
      nodes.put(n, oldNode);
      if (oldIncoming != null) incoming.put(n, oldIncoming);
      placed.set(ia, a);
      placedEnds.set(ia, ea);
      placed.set(ib, b);
      placedEnds.set(ib, eb);
      lines.put(up, a);
      lines.put(cont, b);
    }
    return null;
  }

  private static Coordinate[] segmentAround(List<Coordinate> comb, LineString trunk, double s) {
    double acc = 0;
    for (int i = 1; i < comb.size(); i++) {
      double l = comb.get(i - 1).distance(comb.get(i));
      if (acc + l >= s - 1e-9) return new Coordinate[] {comb.get(i - 1), comb.get(i)};
      acc += l;
    }
    return new Coordinate[] {comb.get(comb.size() - 2), comb.get(comb.size() - 1)};
  }

  /** Fallback: route each edge of the chain with the exact visibility-graph router. */
  private List<LineString> exactChain(
      List<PEdge> chain,
      Map<PNode, Network.Node> nodes,
      Map<PNode, Coordinate> incoming,
      Map<String, Network.Tree> trees) {
    List<LineString> pieces = new ArrayList<>();
    Map<PNode, Coordinate> positions = new IdentityHashMap<>();
    Coordinate approach = incoming.get(chain.get(0).from);
    List<Network.Tree> forest = new ArrayList<>(trees.values());
    for (int m = 0; m < chain.size(); m++) {
      PEdge e = chain.get(m);
      Coordinate a = m == 0 ? nodes.get(e.from).p : positions.get(e.from);
      Coordinate b = e.to.kind == LEAF ? e.to.terminal.p : Geo.canonical(e.to.p);
      LineString line;
      try {
        line =
            router.routeAvoiding(
                a,
                b,
                e.dn,
                e.to.kind == LEAF ? b : null,
                m == 0 && e.from.kind == ROOT ? e.from.site.root : null,
                forest,
                approach);
      } catch (Failure f) {
        line = null;
      }
      if (line == null) return null;
      line = Geo.canonical(line);
      try {
        checks.events(
            line,
            e.dn,
            e.to.kind == LEAF ? b : null,
            m == 0 && e.from.kind == ROOT ? e.from.site.root : null);
      } catch (Failure f) {
        return null;
      }
      pieces.add(line);
      if (e.to.kind != LEAF) positions.put(e.to, b);
      approach = line.getCoordinateN(line.getNumPoints() - 2);
    }
    for (PEdge e : chain)
      if (e.to.kind != LEAF) {
        Coordinate q = positions.get(e.to);
        nodes.put(e.to, new Network.Node("branch:" + Geo.key(q), q));
      }
    for (int m = 0; m < chain.size(); m++) {
      PEdge e = chain.get(m);
      if (e.to.kind != LEAF) {
        LineString l = pieces.get(m);
        incoming.put(e.to, l.getCoordinateN(l.getNumPoints() - 2));
      }
      debug.add(debugFeature(pieces.get(m), "vector", e, null));
    }
    return pieces;
  }

  /** Station on segment a-b nearest to t that is not inside a mandatory special-passage window. */
  private double outsideWindows(Coordinate a, Coordinate b, double t, Existing.Root root) {
    LineString l = Geo.line(a, b);
    double len = l.getLength();
    List<double[]> windows = new ArrayList<>();
    for (Dataset.Obstacle o : data.near(l.getEnvelopeInternal(), rules.queryMargin())) {
      if (!o.special()) continue;
      Geometry hit = l.intersection(o.geometry);
      if (hit.isEmpty()) continue;
      double ext = Set.of("road", "tram_tracks").contains(o.type) ? 3 : 2;
      double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
      for (Coordinate c : hit.getCoordinates()) {
        double s = a.distance(c);
        lo = Math.min(lo, s);
        hi = Math.max(hi, s);
      }
      windows.add(new double[] {lo - ext - .05, hi + ext + .05});
    }
    for (int iter = 0; iter < 10; iter++) {
      boolean inside = false;
      for (double[] w : windows)
        if (t > w[0] && t < w[1]) {
          inside = true;
          t = t - w[0] < w[1] - t ? w[0] : w[1];
        }
      if (!inside) return t >= 0 && t <= len ? t : -1;
    }
    return -1;
  }

  private int vectorProblems;
  private final List<Map<String, Object>> debug = new ArrayList<>();

  /** Names the debug dump of a candidate's vectorization (-Dheatroute.debug=path.geojson). */
  private String debugTag;

  private Map<String, Object> debugFeature(LineString line, String kind, PEdge e, String problem) {
    Map<String, Object> f = new LinkedHashMap<>(), p = new LinkedHashMap<>();
    f.put("type", "Feature");
    f.put("geometry", Geo.json(line));
    p.put("kind", kind);
    p.put("dn", e.dn);
    p.put("flow", e.flow);
    p.put("from", e.from.kind);
    p.put("to", e.to.kind == LEAF ? "t" + e.to.terminal.demand.id : String.valueOf(e.to.kind));
    if (problem != null) p.put("problem", problem);
    f.put("properties", p);
    return f;
  }

  /** Re-plan one edge on the raster of its final DN (the forest topology is kept). */
  private boolean reroute(PEdge e) {
    Grid base = grid(e.dn), g = new Grid(base);
    // Already placed pipes become obstacles, except around this edge's own start chamber/root.
    double own = Catalog.pipe(e.dn).width;
    for (int i = 0; i < placed.size(); i++) {
      LineString l = placed.get(i);
      double r = (own + placedWidth.get(i)) / 2 + .35;
      g.fill(l.buffer(r, 4), c -> g.state[c] = BLOCK);
    }
    Coordinate s0 = e.from.kind == ROOT ? e.from.site.p : e.from.p;
    int rad = (int) Math.ceil(3.5 / g.h), ci = g.cell(s0.x, s0.y);
    if (ci >= 0) {
      int i0 = ci % g.nx, j0 = ci / g.nx;
      for (int dj = -rad; dj <= rad; dj++)
        for (int di = -rad; di <= rad; di++) {
          int ii = i0 + di, jj = j0 + dj;
          if (ii < 0 || jj < 0 || ii >= g.nx || jj >= g.ny) continue;
          int c = jj * g.nx + ii;
          g.state[c] = base.state[c];
        }
    }
    return rerouteCore(e, g);
  }

  private boolean rerouteCore(PEdge e, Grid g) {
    int[] cells;
    double[] costs;
    double ux = 0, uy = 0;
    Coordinate endPoint = null;
    if (e.to.kind == LEAF) {
      Terminal t = e.to.terminal;
      Terminal copy = new Terminal(t.demand);
      copy.entrance(g, t.port);
      if (!copy.usable) return false;
      cells = copy.seedCells;
      costs = copy.seedS;
      ux = copy.ux;
      uy = copy.uy;
      int target = -1;
      List<Seed> starts = new ArrayList<>();
      if (e.from.kind == ROOT) {
        for (Seed sd : seeds(g)) if (sd.site.root.key.equals(e.from.site.root.key)) starts.add(sd);
      } else {
        target = e.from.cell;
        if (g.state[target] != FREE) return false;
      }
      Field f = search(g, copy, cells, costs, ux, uy, target);
      int best = -1;
      Seed bestSeed = null;
      double bd = Double.POSITIVE_INFINITY;
      if (target >= 0) {
        if (Float.isFinite(f.d(target))) {
          best = target;
        }
      } else
        for (Seed sd : starts) {
          double d = f.d(sd.cell) + sd.ray;
          if (d < bd) {
            bd = d;
            best = sd.cell;
            bestSeed = sd;
          }
        }
      if (best < 0) return false;
      e.cells = trace(f, best);
      e.ex = stepExtras(f, e.cells);
      e.extra = sum(e.ex, 0, e.ex.length);
      e.last = copy.rayPoint(e.cells[e.cells.length - 1]);
      if (bestSeed != null) {
        e.seed = bestSeed;
        e.first = bestSeed.site.p;
      }
      return true;
    }
    // interior edge: from node (root or branch) to a branch node
    int target = e.to.cell;
    if (target < 0 || g.state[target] != FREE) return false;
    if (e.from.kind == ROOT) {
      List<Seed> starts = new ArrayList<>();
      for (Seed sd : seeds(g)) if (sd.site.root.key.equals(e.from.site.root.key)) starts.add(sd);
      cells = new int[starts.size()];
      costs = new double[starts.size()];
      for (int i = 0; i < cells.length; i++) {
        cells[i] = starts.get(i).cell;
        costs[i] = starts.get(i).ray;
      }
    } else {
      if (g.state[e.from.cell] != FREE) return false;
      cells = new int[] {e.from.cell};
      costs = new double[] {0};
    }
    Field f = search(g, null, cells, costs, 0, 0, target);
    if (!Float.isFinite(f.d(target))) return false;
    int[] back = trace(f, target);
    int[] fwd = new int[back.length];
    for (int i = 0; i < back.length; i++) fwd[i] = back[back.length - 1 - i];
    e.cells = fwd;
    e.ex = stepExtras(f, fwd);
    e.extra = sum(e.ex, 0, e.ex.length);
    if (e.from.kind == ROOT)
      for (Seed sd : seeds(g))
        if (sd.site.root.key.equals(e.from.site.root.key) && sd.cell == fwd[0]) {
          e.seed = sd;
          e.first = sd.site.p;
          break;
        }
    return true;
  }

  private Network.Tree treeOf(PEdge e, Map<String, Network.Tree> trees) {
    PNode n = e.from;
    while (n.kind != ROOT) n = n.in.from;
    return trees.get(n.site.root.key);
  }
}
