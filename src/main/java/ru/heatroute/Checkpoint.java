package ru.heatroute;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Periodic, separately verified structural snapshot; never replaces the requested result. */
final class Checkpoint {
  private final Path input, directory;
  private final Rules rules;
  private long last = System.nanoTime();

  Checkpoint(Path input, Path output, Rules rules) {
    this.input = input;
    this.directory = output.toAbsolutePath().getParent();
    this.rules = rules;
  }

  void consider(Network.Variant candidate) {
    if (System.nanoTime() - last < 30_000_000_000L) return;
    last = System.nanoTime();
    try {
      save(candidate);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  boolean save(Network.Variant candidate) throws IOException {
    Files.createDirectories(directory);
    Path temp = Files.createTempFile(directory, ".checkpoint-", ".geojson");
    try {
      Json.write(temp, Exporter.collection(List.of(candidate)));
      Map<String, Object> report = new Verifier().verify(input, temp, rules);
      if (!Boolean.TRUE.equals(report.get("valid"))) {
        Json.write(directory.resolve("checkpoint-rejected-report.json"), report);
        return false;
      }
      Json.atomicMove(temp, directory.resolve("checkpoint.geojson"));
      Json.write(
          directory.resolve("checkpoint-state.json"),
          Map.of(
              "rules_hash",
              rules.hash,
              "execution_hash",
              rules.executionHash,
              "seed",
              rules.integer("execution.seed"),
              "verified",
              true,
              "score",
              candidate.score(),
              "input_sha256",
              Json.hash(input)));
      Json.write(directory.resolve("checkpoint-verify-report.json"), report);
      return true;
    } finally {
      Files.deleteIfExists(temp);
    }
  }
}
