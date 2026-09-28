package ru.heatroute;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.*;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.servers.Server;
import java.util.*;
import org.springframework.context.annotation.*;

@Configuration
public class OpenApiConfig {
  static final String DESCRIPTION =
      String.join(
          "\n",
          "Heat network routing service (LCT-2026). Accepts the input GeoJSON FeatureCollection in"
              + " WGS 84 lon/lat (CRS84 / EPSG:4326; sources, existing heat_network/heat_chamber,"
              + " oks_connection_point, restriction polygons),",
          "builds up to `variants` new network layouts and returns only results that passed the"
              + " independent verifier.",
          "",
          "Asynchronous flow: `POST /api/v1/jobs` -> 202 with `job_id`; poll `GET"
              + " /api/v1/jobs/{id}` until `state` is SUCCEEDED, FAILED or CANCELLED;",
          "then `GET /api/v1/jobs/{id}/result` (application/geo+json) and `GET"
              + " /api/v1/jobs/{id}/diagnostics`.",
          "`stage=depth` (optional appendix 5) also writes a separate, separately ranked set with"
              + " depth_start/depth_end and the depth factor: `GET /api/v1/jobs/{id}/result-depth`;"
              + " `/calculate?stage=depth` returns that set.",
          "`POST /api/v1/calculate` does the same synchronously. One heavy job runs at a time, up"
              + " to 16 queued; inputs up to 3 GiB.",
          "",
          "Every error body has the shape"
              + " `{\"error\":{\"code\":...,\"message\":...,\"feature_id\":...}}` (see"
              + " ErrorResponse).");

  @Bean
  public OpenAPI heatRouteOpenApi() {
    Schema<?> error =
        new ObjectSchema()
            .description(
                "Structured error. HTTP status by code: NOT_FOUND 404, NOT_READY 409,"
                    + " RESOURCE_LIMIT 413, QUEUE_FULL and INFRASTRUCTURE_ERROR 503,"
                    + " FINAL_VALIDATION_FAILED 500, every other code 422 (INVALID_JSON,"
                    + " INVALID_ATTRIBUTE, INVALID_GEOMETRY, UNSUPPORTED_CRS,"
                    + " UNSUPPORTED_RESTRICTION, DUPLICATE_ID, CANCELLED, ...)")
            .addProperty(
                "error",
                new ObjectSchema()
                    .addProperty("code", new StringSchema().example("INVALID_ATTRIBUTE"))
                    .addProperty(
                        "message",
                        new StringSchema()
                            .example("Expected stage=xy, variants=1..3, supported mode"))
                    .addProperty(
                        "feature_id",
                        new StringSchema()
                            .description("Input feature id, when the error refers to one")
                            .example("p1"))
                    .required(List.of("code", "message")))
            .required(List.of("error"));
    Schema<?> feature =
        new ObjectSchema()
            .addProperty("type", new StringSchema()._enum(List.of("Feature")))
            .addProperty(
                "geometry",
                new ObjectSchema()
                    .description(
                        "GeoJSON geometry, lon/lat degrees (CRS84 / EPSG:4326): Point, LineString,"
                            + " Polygon, Multi*")
                    .addProperty("type", new StringSchema())
                    .addProperty("coordinates", new ArraySchema().items(new Schema<>())))
            .addProperty(
                "properties",
                new ObjectSchema()
                    .description(
                        "id and object_type are required; flow_tph for oks_connection_point,"
                            + " diameter for heat_network, restriction_type for restriction")
                    .addProperty(
                        "id", new Schema<>().description("string or integer, kept as given"))
                    .addProperty(
                        "object_type",
                        new StringSchema()
                            ._enum(
                                List.of(
                                    "source",
                                    "heat_network",
                                    "heat_chamber",
                                    "oks_connection_point",
                                    "oks_future",
                                    "oks_existing",
                                    "restriction")))
                    .addProperty("flow_tph", new NumberSchema())
                    .addProperty("diameter", new IntegerSchema())
                    .addProperty("restriction_type", new StringSchema())
                    .required(List.of("id", "object_type")))
            .required(List.of("type", "geometry", "properties"));
    Schema<?> collection =
        new ObjectSchema()
            .description(
                "GeoJSON FeatureCollection; full contract in src/main/resources/input.schema.json"
                    + " (input) and output-current.schema.json (result)")
            .addProperty("type", new StringSchema()._enum(List.of("FeatureCollection")))
            .addProperty(
                "crs",
                new ObjectSchema()
                    .description(
                        "Optional; only CRS84 / EPSG:4326 names are accepted, others give"
                            + " UNSUPPORTED_CRS"))
            .addProperty(
                "features",
                new ArraySchema().items(new Schema<>().$ref("#/components/schemas/Feature")))
            .required(List.of("type", "features"));
    Schema<?> accepted =
        new ObjectSchema()
            .addProperty("job_id", new StringSchema().format("uuid"))
            .addProperty("status_url", new StringSchema())
            .addProperty("result_url", new StringSchema())
            .addProperty("diagnostics_url", new StringSchema());
    Schema<?> health =
        new ObjectSchema()
            .addProperty("ready", new BooleanSchema())
            .addProperty("java", new StringSchema().example("11.0.24"))
            .addProperty(
                "storage",
                new StringSchema().description("PostGIS full version, or 'local H2 job metadata'"))
            .addProperty("active_heavy_workers", new IntegerSchema())
            .addProperty("error", new StringSchema().example("DATABASE_UNAVAILABLE"));
    return new OpenAPI()
        .info(new Info().title("HeatRoute API").version("6.0.0").description(DESCRIPTION))
        .servers(List.of(new Server().url("/").description("Same host as this document")))
        .components(
            new Components()
                .addSchemas("ErrorResponse", error)
                .addSchemas("Feature", feature)
                .addSchemas("FeatureCollection", collection)
                .addSchemas("JobAccepted", accepted)
                .addSchemas("Health", health));
  }
}
