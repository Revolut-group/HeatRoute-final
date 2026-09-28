package ru.heatroute;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;

public final class Json {
  public static final ObjectMapper M =
      new ObjectMapper()
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

  private Json() {}

  public static void write(Path path, Object value) throws IOException {
    Path dest = path.toAbsolutePath();
    Files.createDirectories(dest.getParent());
    Path temp = Files.createTempFile(dest.getParent(), ".heatroute-", ".tmp");
    try {
      M.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), value);
      atomicMove(temp, dest);
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  public static void atomicMove(Path temp, Path dest) throws IOException {
    try {
      Files.move(temp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(temp, dest, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  public static String hash(byte[] b) {
    try {
      return hex(MessageDigest.getInstance("SHA-256").digest(b));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String hash(Path file) throws IOException {
    try {
      MessageDigest d = MessageDigest.getInstance("SHA-256");
      try (InputStream in = Files.newInputStream(file)) {
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) >= 0) d.update(b, 0, n);
      }
      return hex(d.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String hex(byte[] b) {
    StringBuilder s = new StringBuilder();
    for (byte v : b) s.append(String.format("%02x", v & 255));
    return s.toString();
  }
}
