package ru.heatroute;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;

class CliTest {
  @Test
  void cliErrorsMapToStructuredCodes() throws Exception {
    assertEquals("INPUT_NOT_FOUND", Main.failure(new NoSuchFileException("missing.json")).code);
    assertEquals(
        "INPUT_NOT_FOUND",
        Main.failure(new UncheckedIOException(new FileNotFoundException("missing.json"))).code);
    try {
      Json.M.readTree("{\"type\":\"FeatureCollection\",\"features\":[");
      fail();
    } catch (IOException e) {
      assertEquals("INVALID_JSON", Main.failure(e).code);
    }
    assertEquals(
        "INFRASTRUCTURE_ERROR", Main.failure(new java.net.ConnectException("refused")).code);
    assertEquals("RESOURCE_LIMIT", Main.failure(new OutOfMemoryError()).code);
    Failure own = new Failure("INVALID_ATTRIBUTE", "x");
    assertSame(own, Main.failure(new RuntimeException(own)));
    assertEquals("INFRASTRUCTURE_ERROR", Main.failure(new IllegalStateException("boom")).code);
    for (String c : new String[] {"solve", "verify", "report", "audit", "serve", "health"})
      assertTrue(Main.USAGE.contains("  " + c), c);
  }
}
