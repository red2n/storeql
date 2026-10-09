package com.storeql.product.domain.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.product.domain.imports.DryRun.Existing;
import com.storeql.product.domain.imports.ImportItem.Alias;
import com.storeql.product.domain.imports.Reconciliation.Measure;
import com.storeql.product.domain.imports.Reconciliation.Report;
import com.storeql.product.domain.imports.Reconciliation.StockLoaded;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The file against what was loaded, measure by measure (pure). */
class ReconciliationTest {

  private static ImportItem item(
      String sku,
      String gtin,
      String vat,
      String price,
      String cost,
      String stock,
      List<Alias> aliases) {
    return new ImportItem(
        1,
        sku,
        "Item " + sku,
        gtin,
        gtin,
        List.of(),
        vat,
        vat,
        price == null ? null : new BigDecimal(price),
        cost == null ? null : new BigDecimal(cost),
        null,
        null,
        null,
        stock == null ? null : new BigDecimal(stock),
        null,
        null,
        aliases);
  }

  private static Existing exists(String sku) {
    return new Existing(sku, "Item " + sku, null, null, null, null, null);
  }

  /** A world in which everything the three items say has been loaded. */
  private static final class World {
    final List<ImportItem> items =
        List.of(
            item("A", "05012345678900", "T1", "1.29", "0.80", "10", List.of()),
            item(
                "B",
                "04006381333931",
                "T1",
                "2.00",
                "1.10",
                "4.5",
                List.of(new Alias("00036000291452", "OLD_EAN", 1))),
            item("C", null, "T5", "0.99", null, "3", List.of()));
    final Map<String, Existing> existing = new HashMap<>();
    final Map<String, String> holders = new HashMap<>();
    final Map<String, UUID> ids = new HashMap<>();
    final Map<String, BigDecimal> prices = new HashMap<>();
    StockLoaded stock = new StockLoaded(3, new BigDecimal("17.500"), new BigDecimal("12.9500"), 1);

    World() {
      for (ImportItem i : items) {
        existing.put(i.sku(), exists(i.sku()));
        UUID id = Ids.newId();
        ids.put(i.sku(), id);
        prices.put(id.toString(), i.price());
        if (i.gtin14() != null) holders.put(i.gtin14(), i.sku());
      }
      holders.put("00036000291452", "B");
    }

    Report run() {
      return Reconciliation.compare(items, existing, holders, ids, prices, stock);
    }
  }

  private static Measure measure(Report r, String name) {
    return r.measures().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
  }

  @Test
  @DisplayName("when everything arrived every measure agrees and the import is reconciled")
  void agrees() {
    Report r = new World().run();

    assertTrue(r.reconciled());
    assertTrue(r.measures().stream().allMatch(Measure::match));
    assertEquals("3", measure(r, "SKUS").loaded());
    assertEquals("2", measure(r, "BARCODES").file());
    assertEquals("1", measure(r, "ALIASES").file());
    assertEquals(
        0, new BigDecimal("17.5").compareTo(new BigDecimal(measure(r, "STOCK_QTY").file())));
    assertEquals(
        0, new BigDecimal("12.95").compareTo(new BigDecimal(measure(r, "STOCK_VALUE").file())));
    assertEquals("1", measure(r, "STOCK_UNCOSTED").file());
    // prices by the file's VAT code
    assertEquals(2, r.prices().size());
    assertEquals("T1", r.prices().get(0).vatCode());
    assertEquals(2, r.prices().get(0).fileCount());
    assertEquals(0, new BigDecimal("3.29").compareTo(r.prices().get(0).fileSum()));
    assertEquals("T5", r.prices().get(1).vatCode());
    assertTrue(r.prices().stream().allMatch(Reconciliation.PriceLine::match));
  }

  @Test
  @DisplayName("a SKU that did not arrive is named, and the report is not reconciled")
  void unmatchedSku() {
    World w = new World();
    w.existing.remove("C");

    Report r = w.run();

    assertFalse(r.reconciled());
    assertEquals(List.of("C"), r.unmatchedSkus());
    assertEquals(1, r.unmatchedCount());
    assertFalse(measure(r, "SKUS").match());
  }

  @Test
  @DisplayName("a barcode or alias held by another item counts as not loaded")
  void barcodeHeldByAnother() {
    World w = new World();
    w.holders.put("05012345678900", "SOMEONE-ELSE");
    w.holders.remove("00036000291452");

    Report r = w.run();

    assertFalse(r.reconciled());
    assertEquals("1", measure(r, "BARCODES").loaded());
    assertEquals("0", measure(r, "ALIASES").loaded());
  }

  @Test
  @DisplayName("a price changed or missing in the list shows by VAT code and by SKU")
  void priceDiffers() {
    World w = new World();
    w.prices.put(w.ids.get("A").toString(), new BigDecimal("1.39"));
    w.prices.remove(w.ids.get("C").toString());

    Report r = w.run();

    assertFalse(r.reconciled());
    assertEquals(2, r.priceMismatchCount());
    assertTrue(r.priceMismatches().contains("A: file 1.29, price list 1.39"));
    assertTrue(r.priceMismatches().contains("C: file 0.99, price list has none"));
    var t1 = r.prices().get(0);
    assertFalse(t1.match());
    assertEquals(0, new BigDecimal("3.39").compareTo(t1.loadedSum()));
    var t5 = r.prices().get(1);
    assertEquals(0, t5.loadedCount());
  }

  @Test
  @DisplayName("stock that another opening got to first leaves the quantity and value short")
  void stockShort() {
    World w = new World();
    w.stock = new StockLoaded(2, new BigDecimal("14.5"), new BigDecimal("8.00"), 1);

    Report r = w.run();

    assertFalse(r.reconciled());
    assertFalse(measure(r, "STOCK_LINES").match());
    assertFalse(measure(r, "STOCK_QTY").match());
    assertFalse(measure(r, "STOCK_VALUE").match());
    assertTrue(measure(r, "STOCK_UNCOSTED").match());
  }

  @Test
  @DisplayName("a file with no prices and no stock has neither to reconcile")
  void nothingToCompare() {
    World w = new World();

    Report r = Reconciliation.compare(w.items, w.existing, w.holders, w.ids, null, null);

    assertTrue(r.prices().isEmpty());
    assertTrue(r.measures().stream().noneMatch(m -> m.name().startsWith("STOCK")));
    assertTrue(r.reconciled());
  }

  @Test
  @DisplayName("a long list of unmatched SKUs is counted in full and listed to a limit")
  void listedToALimit() {
    World w = new World();
    w.existing.clear();

    Report r = w.run();

    assertEquals(3, r.unmatchedCount());
    var many = new java.util.ArrayList<ImportItem>();
    for (int i = 0; i < 80; i++) many.add(item("X" + i, null, "T1", "1.00", null, null, List.of()));
    Report big = Reconciliation.compare(many, Map.of(), Map.of(), Map.of(), null, null);
    assertEquals(80, big.unmatchedCount());
    assertEquals(Reconciliation.LISTED, big.unmatchedSkus().size());
  }
}
