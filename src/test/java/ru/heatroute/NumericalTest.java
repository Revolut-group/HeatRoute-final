package ru.heatroute;

import static org.junit.jupiter.api.Assertions.*;

import java.math.*;
import java.util.*;
import org.junit.jupiter.api.*;

class NumericalTest {
  Rules r;

  @BeforeEach
  void rules() throws Exception {
    r = new Rules(java.nio.file.Path.of("config/rules-v5.yaml"));
  }

  @Test
  void N01_N04_capacityBoundaries() {
    assertEquals(100, Catalog.base(new BigDecimal("22.3")).dn);
    assertEquals(125, Catalog.base(new BigDecimal("22.300001")).dn);
    assertEquals(125, Catalog.base(new BigDecimal("30")).dn);
    assertEquals(1400, Catalog.base(new BigDecimal("22501.9")).dn);
    assertThrows(Failure.class, () -> Catalog.base(new BigDecimal("22501.900001")));
  }

  @Test
  void completeCatalog() {
    int[] dn = {
      50, 65, 80, 100, 125, 150, 200, 250, 300, 400, 500, 600, 700, 800, 900, 1000, 1200, 1400
    };
    int[] prices = {
      74023, 78631, 83530, 89748, 97275, 105507, 120275, 135323, 150022, 190299, 224137, 264790,
      324298, 325996, 327693, 418777, 428074, 683417
    };
    int[] limits = {
      181, 245, 327, 419, 554, 696, 1042, 1379, 1718, 2477, 3245, 4037, 4775, 5644, 6518, 7419,
      9288, 11276
    };
    assertEquals(18, Catalog.PIPES.size());
    for (int i = 0; i < dn.length; i++) {
      Catalog.Pipe p = Catalog.pipe(dn[i]);
      assertEquals(prices[i], p.newPrice.intValueExact());
      assertEquals(limits[i], p.limit);
      assertEquals(2 * p.shell + p.gap, p.width, 1e-12);
      assertEquals(p.shell, p.height, 0);
    }
  }

  @Test
  void N05_N10_money() {
    assertEquals(
        new BigDecimal("8353000"), Catalog.pipe(80).newPrice.multiply(new BigDecimal("100")));
    assertEquals(
        new BigDecimal("0.673884000000"),
        r.score(new BigDecimal("13353000"), new BigDecimal("100")));
    assertEquals(
        new BigDecimal("2672960.00"),
        r.money(
            Catalog.pipe(80)
                .newPrice
                .multiply(new BigDecimal("20"))
                .multiply(new BigDecimal("1.6"))));
    assertEquals(
        new BigDecimal("15152250"),
        Catalog.pipe(250).reconstructionPrice.multiply(new BigDecimal("75")));
    assertEquals(new BigDecimal("5000000"), Catalog.chamber(300));
    assertEquals(new BigDecimal("102040000.00"), r.penalty(new BigDecimal("4.08")));
  }

  @Test
  void N11_realPenalty() throws Exception {
    Dataset d = new Ingest().read(java.nio.file.Path.of("src/test/resources/fixtures/legacy-dataset.geojson"));
    BigDecimal p =
        d.demands.stream().map(a -> r.penalty(a.flow)).reduce(BigDecimal.ZERO, BigDecimal::add);
    assertEquals(new BigDecimal("1944360000.00"), p);
    assertEquals(new BigDecimal("54.442080000000"), r.score(p, BigDecimal.ZERO));
  }

  @Test
  void N14_N15_overlap() {
    List<Intervals.Zone> z =
        List.of(new Intervals.Zone(0, 10, 1.6, "road"), new Intervals.Zone(8, 20, 1.75, "tram"));
    assertEquals(2923550, price(Intervals.combine(z, true)), 1e-8);
    assertEquals(2823314, price(Intervals.combine(z, false)), 1e-8);
    assertEquals(
        2,
        Intervals.combine(
                List.of(
                    new Intervals.Zone(0, 10, 1.6, "a"),
                    new Intervals.Zone(10.00001, 20, 1.75, "b")),
                true)
            .size());
  }

  @Test
  void N16_detourObjective() {
    BigDecimal special = r.score(new BigDecimal("2672960"), new BigDecimal("20"));
    assertTrue(r.score(new BigDecimal("2088250"), new BigDecimal("25")).compareTo(special) < 0);
    assertTrue(r.score(new BigDecimal("2171780"), new BigDecimal("26")).compareTo(special) > 0);
  }

  @Test
  void allCapacityReconstructionAndEnvelopeRows() {
    String[] capacities = {
      "3.5", "8.3", "13.2", "22.3", "40.2", "65.1", "152.3", "274.9", "437.4", "943.1", "1663.4",
      "2627.7", "3735.1", "5296.8", "7165.0", "9391.8", "15012.8", "22501.9"
    };
    int[] reconstruction = {
      96180, 109989, 117582, 133694, 148030, 152295, 181766, 202030, 228707, 271317, 333884, 372703,
      439571, 489918, 553607, 606679, 825692, 978584
    };
    double[] shells = {
      .125, .140, .160, .180, .225, .250, .315, .400, .450, .560, .710, .800, .900, 1, 1.1, 1.2,
      1.425, 1.6
    };
    double[] widths = {
      .400, .430, .470, .510, .600, .650, .880, 1.050, 1.150, 1.370, 1.670, 1.850, 2.050, 2.250,
      2.450, 2.650, 3.100, 3.450
    };
    for (int i = 0; i < 18; i++) {
      Catalog.Pipe p = Catalog.PIPES.get(i);
      assertEquals(0, new BigDecimal(capacities[i]).compareTo(p.capacity));
      assertEquals(reconstruction[i], p.reconstructionPrice.intValueExact());
      assertEquals(shells[i], p.shell, 0);
      assertEquals(i < 6 ? .15 : .25, p.gap, 0);
      assertEquals(widths[i], p.width, 0);
      assertEquals(p.dn, Catalog.base(new BigDecimal(capacities[i])).dn);
      if (i < 17)
        assertEquals(
            Catalog.PIPES.get(i + 1).dn,
            Catalog.base(new BigDecimal(capacities[i]).add(new BigDecimal(".000001"))).dn);
    }
    for (int dn : new int[] {50, 200}) assertEquals(3000000, Catalog.chamber(dn).intValueExact());
    for (int dn : new int[] {250, 500}) assertEquals(5000000, Catalog.chamber(dn).intValueExact());
    for (int dn : new int[] {600, 1000}) assertEquals(8000000, Catalog.chamber(dn).intValueExact());
    for (int dn : new int[] {1200, 1400})
      assertEquals(12000000, Catalog.chamber(dn).intValueExact());
  }

  private double price(List<Intervals.Zone> zones) {
    return zones.stream().mapToDouble(z -> (z.b - z.a) * z.k * 83530).sum();
  }
}
