package com.storeql.product.domain.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.product.domain.imports.ImportMapping.AliasColumn;
import com.storeql.product.domain.imports.ImportRows.ReadRow;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An export's rows judged under a mapping: what is read, what is refused and why, what is a gap.
 */
class ImportRowsTest {

  private static ImportMapping mapping() {
    return new ImportMapping(
        Map.ofEntries(
            Map.entry("sku", "PLU"),
            Map.entry("name", "Description"),
            Map.entry("barcode", "EAN"),
            Map.entry("category", "Department"),
            Map.entry("vatCode", "VAT"),
            Map.entry("price", "Retail Price"),
            Map.entry("cost", "Cost"),
            Map.entry("soldBy", "Sold By"),
            Map.entry("unit", "Unit"),
            Map.entry("stockQty", "On Hand"),
            Map.entry("expiry", "Expiry")),
        List.of(new AliasColumn("Old EAN", "OLD_EAN", 1)),
        Map.of("A", "T1", "B", "T5", "C", "T0"),
        null,
        "INCLUSIVE",
        '.',
        "dd/MM/yyyy",
        Map.of("EACH", "EACH", "KG", "WEIGHT"),
        ">");
  }

  private static final String HEADER =
      "PLU,Description,EAN,Department,VAT,Retail Price,Cost,Sold By,Unit,On Hand,Expiry,Old EAN\n";

  private static List<ReadRow> read(String... rows) {
    return read(mapping(), rows);
  }

  private static List<ReadRow> read(ImportMapping m, String... rows) {
    var t =
        CsvTable.parse(
            (HEADER + String.join("\n", rows) + "\n").getBytes(StandardCharsets.UTF_8), 1000);
    var bound = m.bind(t.headers());
    assertTrue(bound.ok(), bound.missing().toString());
    return ImportRows.readAll(m, bound, t.rows(), t.headers().size(), 2);
  }

  private static String codes(ReadRow r) {
    return r.refusals().stream().map(ImportRows.Refusal::code).sorted().toList().toString();
  }

  @Test
  @DisplayName("a good row is read whole, in StoreQL's terms")
  void aGoodRow() {
    ReadRow r =
        read("100123,Hovis Wholemeal 800g,5012345678900,Bakery > Bread,A,1.29,0.80,EACH,,12,31/10/2026,")
            .get(0);

    assertFalse(r.refused());
    var i = r.item();
    assertEquals("100123", i.sku());
    assertEquals("Hovis Wholemeal 800g", i.name());
    assertEquals("05012345678900", i.gtin14());
    assertEquals(List.of("Bakery", "Bread"), i.categoryPath());
    assertEquals("T1", i.vatCode());
    assertEquals("A", i.sourceVatCode());
    assertEquals(new BigDecimal("1.29"), i.price());
    assertEquals(new BigDecimal("0.80"), i.cost());
    assertEquals("EACH", i.soldBy());
    assertEquals(new BigDecimal("12"), i.stockQty());
    assertEquals(LocalDate.of(2026, 10, 31), i.expiry());
    assertTrue(r.gaps().isEmpty());
    assertEquals(2, i.line());
  }

  @Test
  @DisplayName("a row with no SKU or no name is refused, and says both when both are missing")
  void missingKeys() {
    assertEquals("[SKU_MISSING]", codes(read(",Bread,,,,,,,,,,").get(0)));
    assertEquals("[NAME_MISSING]", codes(read("1,,,,,,,,,,,").get(0)));
    assertEquals("[NAME_MISSING, SKU_MISSING]", codes(read(",,,,,,,,,,,").get(0)));
  }

  @Test
  @DisplayName("a row with the wrong number of cells is refused, not guessed at")
  void rowShape() {
    assertEquals("[ROW_SHAPE]", codes(read("1,Bread,5012345678900").get(0)));
  }

  @Test
  @DisplayName(
      "barcodes: exponent form, a bad check digit, a lost zero and a scale label are each refused")
  void barcodes() {
    assertEquals("[BARCODE_EXPONENT_FORM]", codes(read("1,X,5.01E+12,,A,1.29,,,,,,").get(0)));
    assertEquals(
        "[BARCODE_BAD_CHECK_DIGIT]", codes(read("1,X,5012345678901,,A,1.29,,,,,,").get(0)));
    assertEquals("[BARCODE_BAD_LENGTH]", codes(read("1,X,36000291452,,A,1.29,,,,,,").get(0)));
    assertEquals("[BARCODE_LABEL_STYLE]", codes(read("1,X,2012345001234,,A,1.29,,,,,,").get(0)));
  }

  @Test
  @DisplayName("an old EAN is an alias; a bad one is refused under its column's name")
  void aliases() {
    ReadRow ok = read("1,X,5012345678900,,A,1.29,,,,,,036000291452").get(0);
    assertEquals("00036000291452", ok.item().aliases().get(0).gtin14());
    assertEquals("OLD_EAN", ok.item().aliases().get(0).kind());

    ReadRow bad = read("1,X,5012345678900,,A,1.29,,,,,,5.01E+12").get(0);
    assertEquals("[ALIAS_EXPONENT_FORM]", codes(bad));
    assertTrue(bad.refusals().get(0).detail().startsWith("Old EAN"));
  }

  @Test
  @DisplayName(
      "the customer's VAT code is mapped; an unmapped one is refused naming it; a blank one is a gap")
  void vatCodes() {
    assertEquals("T5", read("1,X,,,b,1.29,,,,,,").get(0).item().vatCode());
    ReadRow bad = read("1,X,,,Q,1.29,,,,,,").get(0);
    assertEquals("[VAT_CODE_UNMAPPED]", codes(bad));
    assertTrue(bad.refusals().get(0).detail().contains("'Q'"));
    assertTrue(read("1,X,,,,1.29,,,,,,").get(0).gaps().contains("NO_VAT_CATEGORY"));
  }

  @Test
  @DisplayName(
      "a mapping that names a default VAT code fills a blank one; nothing is defaulted otherwise")
  void defaultVat() {
    var m = mapping();
    var withDefault =
        new ImportMapping(
            m.columns(),
            m.aliasColumns(),
            m.vatCodes(),
            "T1",
            m.priceBasis(),
            m.decimalMark(),
            m.dateFormat(),
            m.soldByValues(),
            m.categorySeparator());

    ReadRow r = read(withDefault, "1,X,,,,1.29,,,,,,").get(0);

    assertEquals("T1", r.item().vatCode());
    assertFalse(r.gaps().contains("NO_VAT_CATEGORY"));
  }

  @Test
  @DisplayName(
      "prices: unreadable, thousands separators, a wrong decimal mark, zero and too fine are refused; a pound sign is allowed")
  void prices() {
    assertEquals("[PRICE_UNREADABLE]", codes(read("1,X,,,A,abc,,,,,,").get(0)));
    assertEquals("[PRICE_UNREADABLE]", codes(read("1,X,,,A,\"1,299.00\",,,,,,").get(0)));
    assertEquals("[PRICE_UNREADABLE]", codes(read("1,X,,,A,\"1,29\",,,,,,").get(0)));
    assertEquals("[PRICE_NOT_POSITIVE]", codes(read("1,X,,,A,0,,,,,,").get(0)));
    assertEquals("[PRICE_NOT_POSITIVE]", codes(read("1,X,,,A,0.00,,,,,,").get(0)));
    assertEquals("[PRICE_TOO_PRECISE]", codes(read("1,X,,,A,1.295,,,,,,").get(0)));
    assertEquals(new BigDecimal("1.29"), read("1,X,,,A,£1.29,,,,,,").get(0).item().price());
    assertEquals(new BigDecimal("1.2900"), read("1,X,,,A,1.2900,,,,,,").get(0).item().price());
    assertTrue(read("1,X,,,A,,,,,,,").get(0).gaps().contains("NO_PRICE"));
  }

  @Test
  @DisplayName(
      "a file that writes decimals with a comma is read with a comma, and a point in it is refused")
  void commaDecimals() {
    var m = mapping();
    var comma =
        new ImportMapping(
            m.columns(),
            m.aliasColumns(),
            m.vatCodes(),
            null,
            m.priceBasis(),
            ',',
            m.dateFormat(),
            m.soldByValues(),
            m.categorySeparator());

    assertEquals(
        new BigDecimal("1.29"), read(comma, "1,X,,,A,\"1,29\",,,,,,").get(0).item().price());
    assertEquals("[PRICE_UNREADABLE]", codes(read(comma, "1,X,,,A,1.29,,,,,,").get(0)));
  }

  @Test
  @DisplayName("a price is held to the currency's minor units: whole yen refuse a decimal")
  void minorUnits() {
    var t =
        CsvTable.parse((HEADER + "1,X,,,A,129.50,,,,,,\n").getBytes(StandardCharsets.UTF_8), 10);
    var rows =
        ImportRows.readAll(mapping(), mapping().bind(t.headers()), t.rows(), t.headers().size(), 0);

    assertEquals("[PRICE_TOO_PRECISE]", codes(rows.get(0)));
  }

  @Test
  @DisplayName(
      "measured items need a unit that belongs to how they are sold; an unknown sold-by word is refused")
  void measured() {
    assertEquals("[WEIGHED_NO_UNIT]", codes(read("1,X,,,A,2.99,,KG,,,,").get(0)));
    assertEquals("[UNIT_NOT_FOR_SOLD_BY]", codes(read("1,X,,,A,2.99,,KG,L,,,").get(0)));
    ReadRow ok = read("1,X,,,A,2.99,,kg,kg,,,").get(0);
    assertEquals("WEIGHT", ok.item().soldBy());
    assertEquals("KG", ok.item().unit());
    assertEquals("[SOLD_BY_UNKNOWN]", codes(read("1,X,,,A,2.99,,PAIR,,,,").get(0)));
    // Nothing said, nothing changed: a new product is made EACH when it is created.
    assertNull(read("1,X,,,A,2.99,,,,,,").get(0).item().soldBy());
  }

  @Test
  @DisplayName(
      "stock: a quantity of at most three decimals, a date as the file writes it, and stock with no cost is a gap")
  void stock() {
    assertEquals("[STOCK_QTY_UNREADABLE]", codes(read("1,X,,,A,1.29,,,,-3,,").get(0)));
    assertEquals("[STOCK_QTY_UNREADABLE]", codes(read("1,X,,,A,1.29,,,,1.2345,,").get(0)));
    assertEquals("[EXPIRY_UNREADABLE]", codes(read("1,X,,,A,1.29,,,,5,2026-10-31,").get(0)));
    assertEquals("[EXPIRY_UNREADABLE]", codes(read("1,X,,,A,1.29,,,,5,31/02/2026,").get(0)));
    assertTrue(read("1,X,,,A,1.29,,,,5,,").get(0).gaps().contains("STOCKED_NO_COST"));
    assertFalse(read("1,X,,,A,1.29,0.8,,,5,,").get(0).gaps().contains("STOCKED_NO_COST"));
    assertFalse(read("1,X,,,A,1.29,,,,0,,").get(0).gaps().contains("STOCKED_NO_COST"));
  }

  @Test
  @DisplayName("a lot rides with the stock so a recall can find it; one too long refuses the row")
  void lot() {
    var base = mapping();
    var columns = new java.util.HashMap<>(base.columns());
    columns.put("lot", "Lot");
    var m =
        new ImportMapping(
            columns,
            base.aliasColumns(),
            base.vatCodes(),
            base.defaultVatCode(),
            base.priceBasis(),
            base.decimalMark(),
            base.dateFormat(),
            base.soldByValues(),
            base.categorySeparator());
    String head =
        "PLU,Description,EAN,Department,VAT,Retail Price,Cost,Sold By,Unit,On Hand,Expiry,Old EAN,Lot\n";
    CsvTable.Parsed t =
        CsvTable.parse(
            (head
                    + "1,X,,,A,1.29,0.8,,,5,31/12/2026,,L-77\n"
                    + "2,Y,,,A,1.29,0.8,,,5,,,"
                    + "L".repeat(65)
                    + "\n"
                    + "3,Z,,,A,1.29,0.8,,,5,,,\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8),
            100);

    var rows = ImportRows.readAll(m, m.bind(t.headers()), t.rows(), t.headers().size(), 2);

    assertEquals("L-77", rows.get(0).item().lot());
    assertEquals("[LOT_TOO_LONG]", codes(rows.get(1)));
    assertNull(rows.get(2).item().lot());
  }

  @Test
  @DisplayName(
      "a repeated SKU with the same content is a harmless repeat; with different content it is a conflict")
  void duplicateSkus() {
    var rows =
        read(
            "1,Bread,5012345678900,,A,1.29,,,,,,",
            "1,Bread,5012345678900,,A,1.29,,,,,,",
            "1,Bread,5012345678900,,A,1.39,,,,,,");

    assertFalse(rows.get(0).refused());
    assertEquals(2, rows.get(1).duplicateOf());
    assertNull(rows.get(1).item());
    assertFalse(rows.get(1).refused());
    assertEquals("[DUPLICATE_SKU_CONFLICT]", codes(rows.get(2)));
  }

  @Test
  @DisplayName("a barcode (or alias) used by two SKUs in one file refuses the later one")
  void duplicateBarcodes() {
    var rows =
        read(
            "1,Bread,5012345678900,,A,1.29,,,,,,",
            "2,Loaf,5012345678900,,A,1.49,,,,,,",
            "3,Rolls,,,A,1.19,,,,,,5012345678900");

    assertFalse(rows.get(0).refused());
    assertEquals("[BARCODE_REPEATED_IN_FILE]", codes(rows.get(1)));
    assertEquals("[BARCODE_REPEATED_IN_FILE]", codes(rows.get(2)));
  }

  @Test
  @DisplayName(
      "the mapping finds its headers ignoring case and spacing, and names the ones the file lacks")
  void binding() {
    var t =
        CsvTable.parse(
            "plu, DESCRIPTION ,ean,Department,vat,retailprice,Cost,sold_by,Unit,On Hand,Expiry,old ean\n"
                .getBytes(StandardCharsets.UTF_8),
            10);

    assertTrue(mapping().bind(t.headers()).ok());
    var lacking = mapping().bind(List.of("PLU", "Description"));
    assertFalse(lacking.ok());
    assertTrue(lacking.missing().contains("Retail Price"));
  }

  @Test
  @DisplayName("a mapping is checked: no SKU column, a bad basis, an expiry with no date format")
  void mappingProblems() {
    var m = mapping();
    assertTrue(m.problems().isEmpty());
    var bad =
        new ImportMapping(
            Map.of("name", "Description", "expiry", "Expiry", "color", "Colour"),
            List.of(),
            Map.of(),
            null,
            "GROSS",
            ';',
            null,
            Map.of("X", "BOX"),
            ">");
    var problems = bad.problems();
    assertTrue(problems.stream().anyMatch(p -> p.contains("sku")));
    assertTrue(problems.stream().anyMatch(p -> p.contains("priceBasis")));
    assertTrue(problems.stream().anyMatch(p -> p.contains("decimalMark")));
    assertTrue(problems.stream().anyMatch(p -> p.contains("dateFormat")));
    assertTrue(problems.stream().anyMatch(p -> p.contains("color")));
    assertTrue(problems.stream().anyMatch(p -> p.contains("BOX")));
  }
}
