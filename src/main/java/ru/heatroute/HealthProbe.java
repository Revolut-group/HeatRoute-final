package ru.heatroute;

import java.net.*;
import java.net.http.*;
import java.time.Duration;

final class HealthProbe {
  static void check() throws Exception {
    HttpResponse<String> r =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build()
            .send(
                HttpRequest.newBuilder(
                        URI.create(
                            "http://127.0.0.1:"
                                + System.getenv().getOrDefault("PORT", "8080")
                                + "/api/v1/health"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    if (r.statusCode() != 200 || !Json.M.readTree(r.body()).path("ready").asBoolean())
      throw new Failure("INFRASTRUCTURE_ERROR", "Service healthcheck failed");
  }
}
