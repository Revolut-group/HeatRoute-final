package ru.heatroute;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.*;
import java.sql.*;
import java.util.*;
import javax.servlet.http.HttpServletRequest;
import javax.sql.DataSource;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "HeatRoute", description = "Calculation jobs, results and health")
public class Api {
  private final JobService jobs;
  private final DataSource ds;

  public Api(JobService jobs, DataSource ds) {
    this.jobs = jobs;
    this.ds = ds;
  }

  static final String EXAMPLE =
      "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.630991,55.695144]},\"properties\":{\"id\":\"src\",\"object_type\":\"source\"}},{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[37.630991,55.695144],[37.631786,55.695152]]},\"properties\":{\"id\":\"net\",\"object_type\":\"heat_network\",\"diameter\":150,\"flow_tph\":20,\"upstream_object_id\":\"src\"}},{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.631755,55.696051]},\"properties\":{\"id\":\"p1\",\"object_type\":\"oks_connection_point\",\"flow_tph\":10}}]}";

  @Operation(
      summary = "Submit an asynchronous calculation",
      description = "Content-Type must be application/geo+json or application/json, otherwise 415.",
      requestBody =
          @io.swagger.v3.oas.annotations.parameters.RequestBody(
              required = true,
              description = "Input GeoJSON FeatureCollection (up to 3 GiB, streamed to disk)",
              content = {
                @Content(
                    mediaType = "application/geo+json",
                    schema = @Schema(ref = "#/components/schemas/FeatureCollection"),
                    examples = @ExampleObject(name = "minimal", value = EXAMPLE)),
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(ref = "#/components/schemas/FeatureCollection"),
                    examples = @ExampleObject(name = "minimal", value = EXAMPLE))
              }),
      responses = {
        @ApiResponse(
            responseCode = "202",
            description = "Accepted; poll status_url",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(ref = "#/components/schemas/JobAccepted"))),
        @ApiResponse(
            responseCode = "413",
            description = "RESOURCE_LIMIT: input exceeds 3 GiB",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
        @ApiResponse(
            responseCode = "422",
            description =
                "Invalid input or parameters (INVALID_JSON, INVALID_ATTRIBUTE, INVALID_GEOMETRY,"
                    + " UNSUPPORTED_CRS, ...)",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
        @ApiResponse(
            responseCode = "503",
            description = "QUEUE_FULL or INFRASTRUCTURE_ERROR",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
      })
  @PostMapping(
      value = "/api/v1/jobs",
      consumes = {"application/json", "application/geo+json"})
  public ResponseEntity<Map<String, Object>> create(
      @Parameter(hidden = true) HttpServletRequest request,
      @Parameter(
              description =
                  "xy: 2D set; depth: additionally the separate depth set of appendix 5 (GET"
                      + " .../result-depth; /calculate returns it)",
              schema =
                  @Schema(
                      allowableValues = {"xy", "depth"},
                      defaultValue = "xy"))
          @RequestParam(defaultValue = "xy")
          String stage,
      @Parameter(
              description = "Number of ranked variants to return, 1..3",
              schema = @Schema(type = "integer", minimum = "1", maximum = "3", defaultValue = "3"))
          @RequestParam(defaultValue = "3")
          int variants,
      @Parameter(
              description =
                  "reproducible: deterministic, no wall-clock cut-off; fast: search stops at"
                      + " execution.time_budget_seconds; deep: deterministic, about ten times"
                      + " the search of reproducible",
              schema =
                  @Schema(
                      allowableValues = {"reproducible", "fast", "deep"},
                      defaultValue = "reproducible"))
          @RequestParam(defaultValue = "reproducible")
          String mode,
      @Parameter(
              description =
                  "default: entrance from the nearest admissible point of the boundary of the whole"
                      + " OKS object (R02/R03 interpretation); strict: only the nearest boundary"
                      + " of the containing building part, as the appendix text reads",
              schema =
                  @Schema(
                      allowableValues = {"default", "strict"},
                      defaultValue = "default"))
          @RequestParam(defaultValue = "default")
          String profile)
      throws IOException {
    Job job = jobs.receive(request.getInputStream(), variants, stage, mode, profile);
    return ResponseEntity.accepted()
        .body(
            Map.of(
                "job_id",
                job.id,
                "status_url",
                "/api/v1/jobs/" + job.id,
                "result_url",
                "/api/v1/jobs/" + job.id + "/result",
                "diagnostics_url",
                "/api/v1/jobs/" + job.id + "/diagnostics"));
  }

  @Operation(
      summary = "Calculate synchronously and return the verified result",
      description =
          "Blocks until the job finishes. Content-Type must be application/geo+json or"
              + " application/json, otherwise 415. The body is the result FeatureCollection:"
              + " heat_network, heat_chamber, technical_node and variant_summary features.",
      requestBody =
          @io.swagger.v3.oas.annotations.parameters.RequestBody(
              required = true,
              description = "Input GeoJSON FeatureCollection (up to 3 GiB, streamed to disk)",
              content = {
                @Content(
                    mediaType = "application/geo+json",
                    schema = @Schema(ref = "#/components/schemas/FeatureCollection"),
                    examples = @ExampleObject(name = "minimal", value = EXAMPLE)),
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(ref = "#/components/schemas/FeatureCollection"),
                    examples = @ExampleObject(name = "minimal", value = EXAMPLE))
              }),
      responses = {
        @ApiResponse(
            responseCode = "200",
            description = "Verified result",
            content =
                @Content(
                    mediaType = "application/geo+json",
                    schema = @Schema(ref = "#/components/schemas/FeatureCollection"))),
        @ApiResponse(
            responseCode = "413",
            description = "RESOURCE_LIMIT: input exceeds 3 GiB",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
        @ApiResponse(
            responseCode = "422",
            description =
                "Invalid input or parameters (INVALID_JSON, INVALID_ATTRIBUTE, INVALID_GEOMETRY,"
                    + " UNSUPPORTED_CRS, ...)",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
        @ApiResponse(
            responseCode = "503",
            description = "QUEUE_FULL or INFRASTRUCTURE_ERROR",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
        @ApiResponse(
            responseCode = "500",
            description = "FINAL_VALIDATION_FAILED: no candidate passed independent verification",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
      })
  @PostMapping(
      value = "/api/v1/calculate",
      consumes = {"application/json", "application/geo+json"},
      produces = "application/geo+json")
  public ResponseEntity<Resource> calculate(
      @Parameter(hidden = true) HttpServletRequest request,
      @Parameter(
              description =
                  "xy: 2D set; depth: additionally the separate depth set of appendix 5 (GET"
                      + " .../result-depth; /calculate returns it)",
              schema =
                  @Schema(
                      allowableValues = {"xy", "depth"},
                      defaultValue = "xy"))
          @RequestParam(defaultValue = "xy")
          String stage,
      @Parameter(
              description = "Number of ranked variants to return, 1..3",
              schema = @Schema(type = "integer", minimum = "1", maximum = "3", defaultValue = "3"))
          @RequestParam(defaultValue = "3")
          int variants,
      @Parameter(
              description =
                  "reproducible: deterministic, no wall-clock cut-off; fast: search stops at"
                      + " execution.time_budget_seconds; deep: deterministic, about ten times"
                      + " the search of reproducible",
              schema =
                  @Schema(
                      allowableValues = {"reproducible", "fast", "deep"},
                      defaultValue = "reproducible"))
          @RequestParam(defaultValue = "reproducible")
          String mode,
      @Parameter(
              description =
                  "default: entrance from the nearest admissible point of the boundary of the whole"
                      + " OKS object (R02/R03 interpretation); strict: only the nearest boundary"
                      + " of the containing building part, as the appendix text reads",
              schema =
                  @Schema(
                      allowableValues = {"default", "strict"},
                      defaultValue = "default"))
          @RequestParam(defaultValue = "default")
          String profile)
      throws Exception {
    Job j = jobs.receive(request.getInputStream(), variants, stage, mode, profile);
    j = jobs.await(j.id);
    if (!j.state.equals("SUCCEEDED"))
      throw new Failure(
          j.errorCode == null ? "CANCELLED" : j.errorCode,
          j.errorMessage == null ? "Cancelled" : j.errorMessage);
    return artifact(
        j.id,
        stage.equals("depth") ? "result-depth.geojson" : "result.geojson",
        "application/geo+json");
  }

  @Operation(
      summary = "Job status",
      description =
          "state: RECEIVING, VALIDATING, INDEXING, SOLVING, VERIFYING, SUCCEEDED, FAILED,"
              + " CANCELLED; errorCode/errorMessage are set on failure",
      responses = {
        @ApiResponse(responseCode = "200", description = "Job"),
        @ApiResponse(
            responseCode = "404",
            description = "NOT_FOUND: unknown job id",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
      })
  @GetMapping("/api/v1/jobs/{id}")
  public Job status(@PathVariable String id) {
    return jobs.get(id);
  }

  @Operation(
      summary = "Verified result of a finished job",
      responses = {
        @ApiResponse(
            responseCode = "200",
            description = "Result FeatureCollection",
            content =
                @Content(
                    mediaType = "application/geo+json",
                    schema = @Schema(ref = "#/components/schemas/FeatureCollection"))),
        @ApiResponse(
            responseCode = "404",
            description = "NOT_FOUND: unknown job id",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
        @ApiResponse(
            responseCode = "409",
            description = "NOT_READY: the job has not succeeded (yet)",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
      })
  @GetMapping("/api/v1/jobs/{id}/result")
  public ResponseEntity<Resource> result(@PathVariable String id) throws IOException {
    return artifact(id, "result.geojson", "application/geo+json");
  }

  @Operation(
      summary = "Verified depth set of a finished stage=depth job",
      description =
          "Separate set of variants with depth_start/depth_end and Kgl (appendix 5), ranked on its"
              + " own",
      responses = {
        @ApiResponse(
            responseCode = "200",
            description = "Result FeatureCollection",
            content =
                @Content(
                    mediaType = "application/geo+json",
                    schema = @Schema(ref = "#/components/schemas/FeatureCollection"))),
        @ApiResponse(
            responseCode = "404",
            description = "NOT_FOUND: unknown job id",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
        @ApiResponse(
            responseCode = "409",
            description = "NOT_READY: not finished or stage=xy",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
      })
  @GetMapping("/api/v1/jobs/{id}/result-depth")
  public ResponseEntity<Resource> resultDepth(@PathVariable String id) throws IOException {
    return artifact(id, "result-depth.geojson", "application/geo+json");
  }

  @Operation(
      summary = "Offline HTML report of a finished job",
      description =
          "Map of the variants, costs, S and the verification verdict; no external resources",
      responses = {
        @ApiResponse(responseCode = "200", description = "HTML report"),
        @ApiResponse(
            responseCode = "409",
            description = "NOT_READY: the job has not succeeded (yet)",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
      })
  @GetMapping(value = "/api/v1/jobs/{id}/report", produces = "text/html;charset=UTF-8")
  public ResponseEntity<Resource> report(@PathVariable String id) throws IOException {
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
        .body(new FileSystemResource(jobs.report(id)));
  }

  @Operation(
      summary = "Diagnostics of a job",
      description =
          "diagnostics.json of a finished job (timings, planner, rule decisions); for FAILED or"
              + " CANCELLED jobs the Job record itself",
      responses = {
        @ApiResponse(
            responseCode = "200",
            description = "Diagnostics object or Job",
            content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))),
        @ApiResponse(
            responseCode = "404",
            description = "NOT_FOUND: unknown job id",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(ref = "#/components/schemas/ErrorResponse"))),
        @ApiResponse(
            responseCode = "409",
            description = "NOT_READY: diagnostics not written yet",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
      })
  @GetMapping("/api/v1/jobs/{id}/diagnostics")
  public ResponseEntity<?> diagnostics(@PathVariable String id) throws IOException {
    Job j = jobs.get(id);
    if (j.state.equals("FAILED") || j.state.equals("CANCELLED")) return ResponseEntity.ok(j);
    return artifact(id, "diagnostics.json", "application/json");
  }

  @Operation(
      summary = "Cancel a job",
      description = "No partial result is exposed after cancellation",
      responses = {
        @ApiResponse(responseCode = "200", description = "Job after cancellation"),
        @ApiResponse(
            responseCode = "404",
            description = "NOT_FOUND: unknown job id",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(ref = "#/components/schemas/ErrorResponse")))
      })
  @DeleteMapping("/api/v1/jobs/{id}")
  public Job cancel(@PathVariable String id) {
    return jobs.cancel(id);
  }

  @Operation(
      summary = "Readiness: Java 11 runtime and metadata storage (PostGIS or H2)",
      responses = {
        @ApiResponse(
            responseCode = "200",
            description = "Ready",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(ref = "#/components/schemas/Health"))),
        @ApiResponse(
            responseCode = "503",
            description = "DATABASE_UNAVAILABLE",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(ref = "#/components/schemas/Health")))
      })
  @GetMapping("/api/v1/health")
  public ResponseEntity<?> health() {
    try (Connection c = ds.getConnection()) {
      boolean postgres = c.getMetaData().getDatabaseProductName().equals("PostgreSQL");
      String version = "local H2 job metadata";
      if (postgres)
        try (Statement s = c.createStatement();
            ResultSet rs = s.executeQuery("SELECT postgis_full_version()")) {
          rs.next();
          version = rs.getString(1);
        }
      return ResponseEntity.ok(
          Map.of(
              "ready",
              Runtime.version().feature() == 11,
              "java",
              System.getProperty("java.version"),
              "storage",
              version,
              "active_heavy_workers",
              1));
    } catch (SQLException ex) {
      return ResponseEntity.status(503)
          .body(Map.of("ready", false, "error", "DATABASE_UNAVAILABLE"));
    }
  }

  private ResponseEntity<Resource> artifact(String id, String file, String type)
      throws IOException {
    FileSystemResource r = new FileSystemResource(jobs.file(id, file));
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(type))
        .contentLength(r.contentLength())
        .body(r);
  }

  @ExceptionHandler(org.springframework.dao.DataAccessException.class)
  public ResponseEntity<?> databaseFailure() {
    return ResponseEntity.status(503)
        .body(new Failure("INFRASTRUCTURE_ERROR", "Metadata database unavailable").json());
  }

  @ExceptionHandler(
      org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
  public ResponseEntity<?> badParameter(
      org.springframework.web.method.annotation.MethodArgumentTypeMismatchException e) {
    return ResponseEntity.status(422)
        .body(new Failure("INVALID_ATTRIBUTE", "Invalid value for " + e.getName()).json());
  }

  @ExceptionHandler(Failure.class)
  public ResponseEntity<?> failure(Failure f) {
    int status;
    switch (f.code) {
      case "NOT_FOUND":
        status = 404;
        break;
      case "NOT_READY":
        status = 409;
        break;
      case "RESOURCE_LIMIT":
        status = 413;
        break;
      case "QUEUE_FULL":
      case "INFRASTRUCTURE_ERROR":
        status = 503;
        break;
      case "FINAL_VALIDATION_FAILED":
        status = 500;
        break;
      default:
        status = 422;
    }
    return ResponseEntity.status(status).body(f.json());
  }
}
