package com.storeql.product.domain.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.product.domain.imports.DryRun.Existing;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What an import would do, against what the business already holds. */
class DryRunTest {

  private static final String HEADER =
      "PLU,Description,EAN,Department,VAT,Retail Price,Sold By,Unit,Brand\n";

  private static ImportMapping mapping() {
    return new ImportMapping(
        Map.of(
            "sku",
            "PLU",
            "name",
            "Description",
            "barcode",
            "EAN",
            "category",
            "Department",
            "vatCode",
            "VAT",
            "price",
            "Retail Price",
            "soldBy",
            "Sold By",
            "unit",
            "Unit",
            "brand",
            "Brand"),
        List.of(),
        Map.of("A", "T1", "B", "T5"),
        null,
        "INCLUSIVE",
        '.',
        null,
        Map.of("EACH", "EACH", "KG", "WEIGHT"),
        ">");
  }

  private static DryRun.Plan plan(
      Map<String, Existing> existing,
      Map<String, String> holders,
      Set<String> rates,
      String... rows) {
    var t =
        CsvTable.parse(
            (HEADER + String.join("\n", rows) + "\n").getBytes(StandardCharsets.UTF_8), 1000);
    var read =
        ImportRows.readAll(mapping(), mapping().bind(t.headers()), t.rows(), t.headers().size(), 2);
    return DryRun.plan(read, existing, holders, rates);
  }

  private static String actionOf(DryRun.Plan p, int index) {
    return p.rows().get(index).action();
  }

  @Test
  @DisplayName(
      "a SKU the catalogue lacks is created; one it has, unchanged; a changed field is an update naming it")
  void createUpdateUnchanged() {
    var existing =
        Map.of(
            "1",
                new Existing(
                    "1", "Bread", "05012345678900", "EACH", null, "Hovis", "Bakery > Bread"),
            "2", new Existing("2", "Jam", null, "EACH", null, null, null));

    var p =
        plan(
            existing,
            Map.of(),
            Set.of("T1", "T5"),
            "1,Bread,5012345678900,Bakery > Bread,A,1.29,,,Hovis",
            "2,Strawberry Jam,,,B,1.99,,,",
            "3,Milk,,,A,0.99,,,");

    assertEquals("UNCHANGED", actionOf(p, 0));
    assertEquals("UPDATE", actionOf(p, 1));
    assertEquals(List.of("name"), p.rows().get(1).changes());
    assertEquals("CREATE", actionOf(p, 2));
    assertEquals(1, p.summary().newProducts());
  }

  @Test
  @DisplayName(
      "a blank cell never erases: a row that names no barcode, brand or category leaves the held ones alone")
  void blankNeverErases() {
    var existing =
        Map.of(
            "1",
            new Existing(
                "1", "Bread", "05012345678900", "WEIGHT", "KG", "Hovis", "Bakery > Bread"));

    var p = plan(existing, Map.of(), Set.of("T1"), "1,Bread,,,A,1.29,,,");

    assertEquals("UNCHANGED", actionOf(p, 0));
  }

  @Test
  @DisplayName("a barcode (or alias) another SKU already holds refuses the row, naming that SKU")
  void barcodeHeldByAnother() {
    var p =
        plan(
            Map.of(),
            Map.of("05012345678900", "OTHER"),
            Set.of("T1"),
            "1,Bread,5012345678900,,A,1.29,,,",
            "2,Loaf,5012345678917,,A,1.29,,,");

    assertEquals("REFUSED", actionOf(p, 0));
    assertEquals("BARCODE_HELD_BY_OTHER", p.rows().get(0).refusals().get(0).code());
    assertTrue(p.rows().get(0).refusals().get(0).detail().contains("OTHER"));
    assertEquals("CREATE", actionOf(p, 1));
  }

  @Test
  @DisplayName("a barcode this same SKU already holds is no clash")
  void ownBarcodeIsNoClash() {
    var p =
        plan(
            Map.of(),
            Map.of("05012345678900", "1"),
            Set.of("T1"),
            "1,Bread,5012345678900,,A,1.29,,,");

    assertEquals("CREATE", actionOf(p, 0));
  }

  @Test
  @DisplayName(
      "a mapped VAT code with no rate configured is a gap, so the quote's refusal is predicted")
  void vatRateGap() {
    var p = plan(Map.of(), Map.of(), Set.of("T1"), "1,Bread,,,A,1.29,,,", "2,Jam,,,B,1.99,,,");

    assertTrue(p.rows().get(1).gaps().contains("VAT_RATE_NOT_CONFIGURED"));
    assertTrue(!p.rows().get(0).gaps().contains("VAT_RATE_NOT_CONFIGURED"));
    assertEquals(1, p.summary().gaps().get("VAT_RATE_NOT_CONFIGURED"));
  }

  @Test
  @DisplayName("refused rows, repeats and the file's own VAT legend are counted")
  void summary() {
    var p =
        plan(
            Map.of(),
            Map.of(),
            Set.of("T1", "T5"),
            "1,Bread,,,A,1.29,,,",
            "1,Bread,,,A,1.29,,,",
            ",NoSku,,,A,1.29,,,",
            "3,Odd,,,Q,1.29,,,");

    var s = p.summary();
    assertEquals(4, s.rows());
    assertEquals(1, s.byAction().get("CREATE"));
    assertEquals(1, s.byAction().get("SKIPPED"));
    assertEquals(2, s.byAction().get("REFUSED"));
    assertEquals(1, s.refusals().get("SKU_MISSING"));
    assertEquals(1, s.refusals().get("VAT_CODE_UNMAPPED"));
    assertEquals(1, s.vatCodes().get("A"));
  }

  @Test
  @DisplayName("a saved mapping round-trips through its JSON and hashes the same each time")
  void mappingJson() {
    var m = mapping();

    var back = ImportMappingJson.read(ImportMappingJson.write(m));

    assertEquals(m.columns(), back.columns());
    assertEquals(m.vatCodes(), back.vatCodes());
    assertEquals(m.priceBasis(), back.priceBasis());
    assertEquals(ImportMappingJson.write(m), ImportMappingJson.write(back));
  }
}
