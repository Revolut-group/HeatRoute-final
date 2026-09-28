package ru.heatroute;

import java.nio.file.*;
import java.util.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class Main {
  static final String USAGE =
      String.join(
          "\n",
          "HeatRoute 6.0.0 - heat network routing (CLI and HTTP API)",
          "",
          "Usage: java [-Xmx4g] -jar heatroute.jar <command> [--option value ...]",
          "",
          "Commands:",
          "  solve   --input <in.geojson> --output <result.geojson> [--diagnostics"
              + " diagnostics.json]",
          "          [--variants 1..3] [--rules rules.yaml] [--mode reproducible|fast|deep]"
              + " [--stage xy|depth]",
          "          [--depth-output result-depth.geojson]",
          "          Solve the input and write the best independently verified result.",
          "          --mode deep: deterministic like reproducible, about ten times the search"
              + " (for a dataset known in advance).",
          "          --stage depth also writes the separate depth set (appendix 5:"
              + " depth_start/depth_end, Kgl)",
          "          next to the 2D result: <output>-depth.geojson and verify-report-depth.json.",
          "  verify  --input <in.geojson> --result <result.geojson> [--report verify-report.json]"
              + " [--rules rules.yaml]",
          "          Re-check a saved result against the input; exit 4 if it is invalid.",
          "  report  --input <in.geojson> --result <result.geojson> --output <report.html> [--png"
              + " report.png]",
          "          [--verify verify-report.json] [--title \"Dataset name\"] [--rules rules.yaml]",
          "          Offline HTML report (map, variants, costs, verification) and an optional"
              + " 1600x1000 PNG;",
          "          without --verify the verifier runs on the saved result.",
          "  audit   --input <in.geojson> [--rules rules.yaml]",
          "          Print an ingest summary of the input as JSON.",
          "  serve   [--server.port=8080] [other Spring Boot --key=value options]",
          "          Start the HTTP API: /api/v1/jobs, /api/v1/calculate, /api/v1/health,"
              + " /v3/api-docs, /swagger-ui.html.",
          "  health  Probe a running server at http://127.0.0.1:${PORT:-8080}/api/v1/health.",
          "  help    Print this text (also --help, -h, or '<command> --help').",
          "",
          "Exit codes: 0 ok, 2 input or usage error, 3 infrastructure or resource error, 4 result"
              + " failed verification.",
          "Errors go to stderr as {\"error\":{\"code\":...,\"message\":...}}; set HEATROUTE_DEBUG=1"
              + " for stack traces.",
          "Memory: -Xmx4g is recommended for solving; 1 GB works with lower quality.");
  private static final Set<String> COMMANDS =
      Set.of("solve", "verify", "report", "audit", "serve", "health");

  public static void main(String[] args) {
    if (args.length == 0) {
      System.out.println(USAGE);
      System.exit(2);
    }
    if (Set.of("help", "--help", "-h").contains(args[0])
        || COMMANDS.contains(args[0])
            && Arrays.stream(args).skip(1).anyMatch(a -> a.equals("--help") || a.equals("-h"))) {
      System.out.println(USAGE);
      return;
    }
    if (args[0].equals("serve")) {
      ConfigurableApplicationContext c =
          SpringApplication.run(Main.class, Arrays.copyOfRange(args, 1, args.length));
      System.err.println(
          "HeatRoute API listening on port "
              + c.getEnvironment().getProperty("local.server.port")
              + " (health: /api/v1/health, OpenAPI: /v3/api-docs, Swagger UI:"
              + " /swagger-ui.html)");
      return;
    }
    int exit = 0;
    try {
      if (!COMMANDS.contains(args[0]))
        throw new Failure(
            "INVALID_ATTRIBUTE",
            "Unknown command " + args[0] + "; run 'java -jar heatroute.jar help'");
      Map<String, String> options = options(args);
      Rules rules =
          new Rules(options.containsKey("rules") ? existing(options, "rules") : null)
              .withMode(options.get("mode"));
      switch (args[0]) {
        case "health":
          HealthProbe.check();
          break;
        case "audit":
          try (Dataset data = new Ingest().read(existing(options, "input"), rules)) {
            System.out.println(Json.M.writeValueAsString(data.audit()));
          }
          break;
        case "solve":
          {
            Path output = path(options, "output");
            String stage =
                options.getOrDefault("stage", rules.bool("depth.enabled") ? "depth" : "xy");
            Path depth =
                stage.equals("depth")
                    ? Path.of(
                        options.getOrDefault(
                            "depth-output",
                            output
                                .toAbsolutePath()
                                .resolveSibling(
                                    output.getFileName().toString().replaceFirst("\\.[^.]*$", "")
                                        + "-depth.geojson")
                                .toString()))
                    : null;
            new CalculationService()
                .solve(
                    existing(options, "input"),
                    output,
                    Path.of(options.getOrDefault("diagnostics", "diagnostics.json")),
                    rules,
                    variants(options),
                    s -> {},
                    depth);
            break;
          }
        case "verify":
          Map<String, Object> report =
              new Verifier().verify(existing(options, "input"), existing(options, "result"), rules);
          Json.write(Path.of(options.getOrDefault("report", "verify-report.json")), report);
          if (!Boolean.TRUE.equals(report.get("valid"))) {
            List<?> errors = (List<?>) report.getOrDefault("errors", List.of());
            Map<?, ?> first = errors.isEmpty() ? Map.of() : (Map<?, ?>) errors.get(0);
            if ("INVALID_JSON".equals(first.get("code")))
              throw new Failure("INVALID_JSON", "--result: " + first.get("message"));
            System.err.println(
                Json.M.writeValueAsString(
                    new Failure(
                            "FINAL_VALIDATION_FAILED",
                            errors.size()
                                + " verification error(s), first "
                                + first.get("code")
                                + ": "
                                + first.get("message")
                                + "; full list in "
                                + options.getOrDefault("report", "verify-report.json"))
                        .json()));
            exit = 4;
          }
          break;
        case "report":
          Report.generate(
              existing(options, "input"),
              existing(options, "result"),
              options.containsKey("verify") ? existing(options, "verify") : null,
              path(options, "output"),
              options.containsKey("png") ? Path.of(options.get("png")) : null,
              rules,
              options.get("title"));
          System.err.println(
              "report="
                  + path(options, "output")
                  + (options.containsKey("png") ? " png=" + options.get("png") : ""));
          break;
        default:
          throw new AssertionError("Unhandled command");
      }
    } catch (Throwable t) {
      Failure f = failure(t);
      try {
        System.err.println(Json.M.writeValueAsString(f.json()));
      } catch (Exception ignored) {
        System.err.println(f.code + ": " + f.getMessage());
      }
      if (System.getenv("HEATROUTE_DEBUG") != null) t.printStackTrace(System.err);
      exit =
          f.code.equals("FINAL_VALIDATION_FAILED")
              ? 4
              : Set.of("RESOURCE_LIMIT", "INFRASTRUCTURE_ERROR", "CANCELLED").contains(f.code)
                  ? 3
                  : 2;
    }
    if (exit != 0) System.exit(exit);
  }

  static Failure failure(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (c instanceof Failure) return (Failure) c;
      if (c instanceof NoSuchFileException || c instanceof java.io.FileNotFoundException)
        return new Failure("INPUT_NOT_FOUND", "File not found: " + c.getMessage());
      if (c instanceof AccessDeniedException)
        return new Failure("INFRASTRUCTURE_ERROR", "Access denied: " + c.getMessage());
      if (c instanceof com.fasterxml.jackson.core.JsonProcessingException)
        return new Failure(
            "INVALID_JSON",
            ((com.fasterxml.jackson.core.JsonProcessingException) c).getOriginalMessage());
      if (c instanceof java.net.ConnectException
          || c instanceof java.net.http.HttpConnectTimeoutException)
        return new Failure(
            "INFRASTRUCTURE_ERROR",
            "Service is not reachable at 127.0.0.1:"
                + System.getenv().getOrDefault("PORT", "8080")
                + "; start it with 'serve'");
      if (c instanceof OutOfMemoryError)
        return new Failure("RESOURCE_LIMIT", "Out of memory; raise -Xmx (4g recommended)");
    }
    return new Failure("INFRASTRUCTURE_ERROR", t.toString());
  }

  private static int variants(Map<String, String> o) {
    String v = o.getOrDefault("variants", "3");
    try {
      return Integer.parseInt(v.trim());
    } catch (NumberFormatException e) {
      throw new Failure("INVALID_ATTRIBUTE", "--variants must be an integer 1..3, got '" + v + "'");
    }
  }

  private static Path path(Map<String, String> o, String k) {
    if (!o.containsKey(k)) throw new Failure("INVALID_ATTRIBUTE", "Missing --" + k);
    return Path.of(o.get(k));
  }

  private static Path existing(Map<String, String> o, String k) {
    Path p = path(o, k);
    if (!Files.isRegularFile(p))
      throw new Failure("INPUT_NOT_FOUND", "--" + k + " file not found: " + p);
    return p;
  }

  private static Map<String, String> options(String[] args) {
    Map<String, String> o = new TreeMap<>();
    for (int i = 1; i < args.length; i += 2) {
      if (!args[i].startsWith("--") || i + 1 == args.length)
        throw new Failure("INVALID_ATTRIBUTE", "Expected --key value");
      String key = args[i].substring(2);
      if (!Set.of(
              "input",
              "output",
              "diagnostics",
              "rules",
              "stage",
              "variants",
              "mode",
              "result",
              "report",
              "suite",
              "size-mib",
              "verify",
              "png",
              "title",
              "depth-output",
              "tier",
              "compare",
              "bless",
              "jobs",
              "heap",
              "jar")
          .contains(key)) throw new Failure("INVALID_ATTRIBUTE", "Unknown option " + key);
      o.put(key, args[i + 1]);
    }
    if (o.containsKey("stage") && !Set.of("xy", "depth").contains(o.get("stage")))
      throw new Failure("INVALID_ATTRIBUTE", "--stage must be xy or depth");
    if (o.containsKey("mode") && !Set.of("reproducible", "fast", "deep").contains(o.get("mode")))
      throw new Failure("INVALID_ATTRIBUTE", "Unknown mode");
    return o;
  }
}
