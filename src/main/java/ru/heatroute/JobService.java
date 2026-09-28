package ru.heatroute;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JobService {
  private final JobRepository repo;
  private final Path work;
  private final Rules rules, strict;
  private final ThreadPoolExecutor worker =
      new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16));
  private final Map<String, Future<?>> running = new ConcurrentHashMap<>();

  public JobService(
      JobRepository repo,
      @Value("${heatroute.work:work/jobs-data}") String directory,
      @Value("${heatroute.rules:config/rules-current.yaml}") String rulePath)
      throws IOException {
    this.repo = repo;
    work = Path.of(directory).toAbsolutePath();
    Files.createDirectories(work);
    rules = new Rules(Files.exists(Path.of(rulePath)) ? Path.of(rulePath) : null);
    strict = Rules.profile("strict");
    for (Job job : repo.findAll())
      if (!Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(job.state)) {
        job.state = "FAILED";
        job.errorCode = "INTERRUPTED";
        job.errorMessage = "Service restarted before completion";
        repo.save(job);
      }
  }

  public Job receive(InputStream in, int variants, String stage, String mode) throws IOException {
    return receive(in, variants, stage, mode, "default");
  }

  /**
   * profile: "default" (the service rules, documented R02/R03 interpretation) or "strict" (the
   * literal entrance rule of the appendix, config/rules-strict.yaml).
   */
  public Job receive(InputStream in, int variants, String stage, String mode, String profile)
      throws IOException {
    if (!Set.of("xy", "depth").contains(stage)
        || !Set.of("reproducible", "fast", "deep").contains(mode)
        || !Set.of("default", "strict").contains(profile)
        || variants < 1
        || variants > 3)
      throw new Failure(
          "INVALID_ATTRIBUTE",
          "Expected stage=xy|depth, variants=1..3, profile=default|strict, supported mode");
    Rules activeRules = rules(profile).withMode(mode);
    String id = UUID.randomUUID().toString();
    Job job = repo.save(new Job(id));
    Path dir = work.resolve(id);
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("profile.txt"), profile);
    Path file = dir.resolve("input.geojson");
    try (OutputStream out = Files.newOutputStream(file)) {
      byte[] buffer = new byte[65536];
      int n;
      long size = 0;
      while ((n = in.read(buffer)) >= 0) {
        size += n;
        if (size > 3L * 1024 * 1024 * 1024)
          throw new Failure("RESOURCE_LIMIT", "Input exceeds 3 GiB");
        out.write(buffer, 0, n);
      }
      job.receivedBytes = size;
      job.state = "VALIDATING";
      job.stage = "VALIDATING";
      repo.save(job);
    } catch (Exception ex) {
      fail(id, ex);
      Files.deleteIfExists(file);
      throw ex;
    }
    try {
      Future<?> task =
          worker.submit(
              () -> {
                try {
                  new CalculationService()
                      .solve(
                          file,
                          dir.resolve("result.geojson"),
                          dir.resolve("diagnostics.json"),
                          activeRules,
                          variants,
                          s -> stage(id, s),
                          stage.equals("depth") ? dir.resolve("result-depth.geojson") : null);
                  stage(id, "SUCCEEDED");
                } catch (Exception ex) {
                  fail(id, ex);
                } finally {
                  running.remove(id);
                }
              });
      running.put(id, task);
    } catch (RejectedExecutionException e) {
      fail(id, e);
      throw new Failure("QUEUE_FULL", "Job queue is full");
    }
    return get(id);
  }

  private synchronized void stage(String id, String stage) {
    Job j = get(id);
    if (j.state.equals("CANCELLED")) throw new Failure("CANCELLED", "Cancelled");
    j.stage = stage;
    j.state = stage;
    repo.save(j);
  }

  private synchronized void fail(String id, Exception e) {
    Job j = get(id);
    if (j.state.equals("CANCELLED")) return;
    j.state =
        e instanceof Failure && ((Failure) e).code.equals("CANCELLED") ? "CANCELLED" : "FAILED";
    j.stage = j.state;
    j.errorCode = e instanceof Failure ? ((Failure) e).code : "INFRASTRUCTURE_ERROR";
    j.errorMessage = e.getMessage();
    repo.save(j);
  }

  public Job get(String id) {
    return repo.findById(id).orElseThrow(() -> new Failure("NOT_FOUND", "Unknown job"));
  }

  public Path file(String id, String name) {
    Job j = get(id);
    if (!j.state.equals("SUCCEEDED") && name.startsWith("result"))
      throw new Failure("NOT_READY", "Result has not passed verification");
    Path file = work.resolve(id).resolve(name);
    if (!Files.exists(file)) throw new Failure("NOT_READY", "Artifact unavailable");
    return file;
  }

  public synchronized Job cancel(String id) {
    Job j = get(id);
    if (!Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(j.state)) {
      j.state = "CANCELLED";
      j.stage = "CANCELLED";
      repo.save(j);
      Future<?> future = running.get(id);
      if (future != null) future.cancel(true);
    }
    return get(id);
  }

  private Rules rules(String profile) {
    return profile.equals("strict") ? strict : rules;
  }

  /** Rule profile a job was calculated with. */
  public String profile(String id) throws IOException {
    get(id);
    Path file = work.resolve(id).resolve("profile.txt");
    return Files.exists(file) ? Files.readString(file).trim() : "default";
  }

  /** Offline HTML report of a finished job (map, variants, costs, verification), made once. */
  public synchronized Path report(String id) throws IOException {
    Path result = file(id, "result.geojson"), dir = result.getParent();
    Path html = dir.resolve("report.html");
    if (!Files.exists(html)) {
      Path verify = dir.resolve("verify-report.json");
      String profile = profile(id);
      Report.generate(
          dir.resolve("input.geojson"),
          result,
          Files.exists(verify) ? verify : null,
          html,
          null,
          rules(profile),
          profile.equals("strict") ? "Буквальный профиль ввода (R02)" : "Профиль по умолчанию");
    }
    return html;
  }

  public Job await(String id) throws InterruptedException {
    for (; ; ) {
      Job j = get(id);
      if (Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(j.state)) return j;
      Thread.sleep(100);
    }
  }

  @PreDestroy
  public void close() {
    worker.shutdownNow();
  }
}
