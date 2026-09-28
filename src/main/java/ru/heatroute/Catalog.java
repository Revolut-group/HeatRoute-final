package ru.heatroute;

import java.io.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Immutable numerical appendix; no interpolation. */
public final class Catalog {
  public static final class Pipe {
    public final int dn, index;
    public final BigDecimal capacity, newPrice, reconstructionPrice;
    public final double limit, shell, gap, width, height;

    Pipe(String[] s, int i) {
      index = i;
      dn = Integer.parseInt(s[0]);
      capacity = new BigDecimal(s[1]);
      limit = Double.parseDouble(s[2]);
      newPrice = new BigDecimal(s[3]);
      reconstructionPrice = new BigDecimal(s[4]);
      shell = Double.parseDouble(s[5]);
      gap = Double.parseDouble(s[6]);
      width = Double.parseDouble(s[7]);
      height = Double.parseDouble(s[8]);
    }
  }

  public static final List<Pipe> PIPES;
  public static final byte[] CONTENT;

  static {
    try (InputStream in = Catalog.class.getResourceAsStream("/config/catalog-v5.csv")) {
      if (in == null) throw new IOException("Missing catalog");
      CONTENT = in.readAllBytes();
      List<Pipe> p = new ArrayList<>();
      String[] lines = new String(CONTENT, StandardCharsets.UTF_8).trim().split("\\R");
      for (int i = 1; i < lines.length; i++) p.add(new Pipe(lines[i].split(","), i - 1));
      PIPES = Collections.unmodifiableList(p);
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  public static Pipe pipe(int dn) {
    return PIPES.stream()
        .filter(p -> p.dn == dn)
        .findFirst()
        .orElseThrow(() -> new Failure("INVALID_ATTRIBUTE", "Unknown DN " + dn));
  }

  public static Pipe base(BigDecimal flow) {
    if (flow.signum() < 0) throw new Failure("INVALID_ATTRIBUTE", "Negative flow");
    return PIPES.stream()
        .filter(p -> p.capacity.compareTo(flow) >= 0)
        .findFirst()
        .orElseThrow(() -> new Failure("CAPACITY_EXCEEDED", "Flow exceeds DN1400"));
  }

  public static Pipe next(Pipe p) {
    if (p.index + 1 == PIPES.size()) throw new Failure("LENGTH_LIMIT", "DN1400 cannot be uplifted");
    return PIPES.get(p.index + 1);
  }

  public static BigDecimal chamber(int dn) {
    return BigDecimal.valueOf(
        dn <= 200 ? 3000000 : dn <= 500 ? 5000000 : dn <= 1000 ? 8000000 : 12000000);
  }

  public static BigDecimal money(BigDecimal x) {
    return x.setScale(2, RoundingMode.HALF_UP);
  }

  public static BigDecimal length(double x) {
    return BigDecimal.valueOf(x).setScale(9, RoundingMode.HALF_UP);
  }
}
