package ru.heatroute;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiContractTest {
  @Autowired TestRestTemplate http;
  static final String directory =
      Path.of("work", "http-test-" + UUID.randomUUID()).toAbsolutePath().toString();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> "jdbc:h2:mem:api_contract;DB_CLOSE_DELAY=-1");
    r.add("heatroute.work", () -> directory);
  }

  HttpEntity<byte[]> body(byte[] bytes, String content) {
    HttpHeaders h = new HttpHeaders();
    h.setContentType(MediaType.parseMediaType(content));
    return new HttpEntity<>(bytes, h);
  }

  @Test
  void syncAsyncHealthSchemaAndErrorCodes() throws Exception {
    ResponseEntity<JsonNode> health = http.getForEntity("/api/v1/health", JsonNode.class);
    assertEquals(200, health.getStatusCodeValue());
    assertTrue(health.getBody().path("ready").asBoolean());
    assertTrue(health.getBody().path("java").asText().startsWith("11."));
    JsonNode openapi = http.getForObject("/v3/api-docs", JsonNode.class);
    ResponseEntity<String> swagger = http.getForEntity("/swagger-ui.html", String.class);
    assertEquals(200, swagger.getStatusCodeValue());
    assertTrue(swagger.getBody().contains("Swagger UI"));
    // Optional system property heatroute.writeOpenApi writes the generated OpenAPI document
    String copy = System.getProperty("heatroute.writeOpenApi");
    if (copy != null)
      Json.M.writerWithDefaultPrettyPrinter()
          .writeValue(Path.of(copy).toAbsolutePath().toFile(), openapi);
    assertTrue(openapi.path("paths").has("/api/v1/jobs"));
    assertTrue(openapi.path("paths").has("/api/v1/calculate"));
    assertEquals("HeatRoute API", openapi.path("info").path("title").asText());
    assertTrue(openapi.path("components").path("schemas").has("ErrorResponse"));
    assertTrue(
        openapi.at("/paths/~1api~1v1~1jobs/post/requestBody/content").has("application/geo+json"));
    assertEquals(4, openapi.at("/paths/~1api~1v1~1jobs/post/parameters").size());
    // the offline page: profile switch between the default and the strict entrance rule
    ResponseEntity<String> page = http.getForEntity("/", String.class);
    assertEquals(200, page.getStatusCodeValue());
    assertTrue(page.getBody().contains("data-profile=\"strict\""));
    assertFalse(page.getBody().contains("http://") || page.getBody().contains("https://"));
    byte[] input = Json.M.writeValueAsBytes(Scenes.n06());
    ResponseEntity<byte[]> sync =
        http.postForEntity(
            "/api/v1/calculate?variants=1", body(input, "application/geo+json"), byte[].class);
    assertEquals(200, sync.getStatusCodeValue());
    assertEquals("application/geo+json", sync.getHeaders().getContentType().toString());
    ResponseEntity<JsonNode> posted =
        http.postForEntity(
            "/api/v1/jobs?variants=1", body(input, "application/json"), JsonNode.class);
    assertEquals(202, posted.getStatusCodeValue());
    String id = posted.getBody().path("job_id").asText();
    JsonNode state = null;
    long until = System.nanoTime() + 30_000_000_000L;
    do {
      state = http.getForObject("/api/v1/jobs/" + id, JsonNode.class);
      if (Set.of("SUCCEEDED", "FAILED").contains(state.path("state").asText())) break;
      Thread.sleep(25);
    } while (System.nanoTime() < until);
    assertEquals("SUCCEEDED", state.path("state").asText(), state.toString());
    assertArrayEquals(
        sync.getBody(), http.getForObject("/api/v1/jobs/" + id + "/result", byte[].class));
    ResponseEntity<String> report =
        http.getForEntity("/api/v1/jobs/" + id + "/report", String.class);
    assertEquals(200, report.getStatusCodeValue());
    assertTrue(report.getHeaders().getContentType().includes(MediaType.TEXT_HTML));
    assertTrue(report.getBody().contains("<svg"));
    assertEquals(
        422,
        http.postForEntity(
                "/api/v1/jobs?variants=1&profile=literal",
                body(input, "application/json"),
                JsonNode.class)
            .getStatusCodeValue());
    assertEquals(
        202,
        http.postForEntity(
                "/api/v1/jobs?variants=1&profile=strict",
                body(input, "application/json"),
                JsonNode.class)
            .getStatusCodeValue());
    assertEquals(
        200,
        http.getForEntity("/api/v1/jobs/" + id + "/diagnostics", JsonNode.class)
            .getStatusCodeValue());
    assertEquals(
        415,
        http.postForEntity("/api/v1/calculate", body(input, "text/plain"), String.class)
            .getStatusCodeValue());
    assertEquals(
        422,
        http.postForEntity(
                "/api/v1/calculate",
                body("{".getBytes(java.nio.charset.StandardCharsets.UTF_8), "application/json"),
                String.class)
            .getStatusCodeValue());
    assertEquals(404, http.getForEntity("/api/v1/jobs/unknown", String.class).getStatusCodeValue());
    assertEquals(
        422,
        http.postForEntity("/api/v1/jobs?stage=xyz", body(input, "application/json"), String.class)
            .getStatusCodeValue());
    // stage=depth: the synchronous call returns the separate depth set (appendix 5), the job also
    // keeps the 2D set.
    ResponseEntity<JsonNode> depth =
        http.postForEntity(
            "/api/v1/calculate?variants=1&stage=depth",
            body(input, "application/geo+json"),
            JsonNode.class);
    assertEquals(200, depth.getStatusCodeValue());
    for (JsonNode f : depth.getBody().path("features"))
      if (f.path("properties").path("object_type").asText().equals("heat_network"))
        assertTrue(f.path("properties").path("depth_start").isNumber());
    assertTrue(openapi.path("paths").has("/api/v1/jobs/{id}/result-depth"));
    ResponseEntity<JsonNode> badVariants =
        http.postForEntity(
            "/api/v1/jobs?variants=abc", body(input, "application/json"), JsonNode.class);
    assertEquals(422, badVariants.getStatusCodeValue());
    assertEquals("INVALID_ATTRIBUTE", badVariants.getBody().path("error").path("code").asText());
  }

  @Test
  void P06_cancelActiveCalculationDoesNotExposePartialResult() throws Exception {
    byte[] input = Files.readAllBytes(Path.of("src/test/resources/fixtures/legacy-dataset.geojson"));
    ResponseEntity<JsonNode> posted =
        http.postForEntity(
            "/api/v1/jobs?variants=1", body(input, "application/geo+json"), JsonNode.class);
    assertEquals(202, posted.getStatusCodeValue());
    String id = posted.getBody().path("job_id").asText();
    ResponseEntity<JsonNode> cancelled =
        http.exchange("/api/v1/jobs/" + id, HttpMethod.DELETE, HttpEntity.EMPTY, JsonNode.class);
    assertEquals(200, cancelled.getStatusCodeValue());
    assertEquals("CANCELLED", cancelled.getBody().path("state").asText());
    assertEquals(
        409,
        http.getForEntity("/api/v1/jobs/" + id + "/result", String.class).getStatusCodeValue());
    assertFalse(Files.exists(Path.of(directory, id, "result.geojson")));
  }
}
