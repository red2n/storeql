package com.storeql.product.domain.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.product.domain.imports.ImportMapping.AliasColumn;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** One saved mapping serves every store's file, found by the text of the headers. */
class ImportMappingTest {

  private static ImportMapping mapping() {
    return new ImportMapping(
        Map.of(
            "sku", "PLU",
            "name", "Description",
            "vatCode", "VAT",
            "price", "Retail Price"),
        List.of(new AliasColumn("Old EAN", "OLD_EAN", 1)),
        Map.of("A", "T1", "B", "T5"),
        null,
        "INCLUSIVE",
        '.',
        null,
        Map.of(),
        ">");
  }

  private static List<ImportItem> read(String csv) {
    CsvTable.Parsed t = CsvTable.parse(csv.getBytes(StandardCharsets.UTF_8), 100);
    ImportMapping m = mapping();
    var bound = m.bind(t.headers());
    assertTrue(bound.ok(), "missing " + bound.missing());
    return ImportRows.readAll(m, bound, t.rows(), t.headers().size(), 2).stream()
        .map(ImportRows.ReadRow::item)
        .toList();
  }

  @Test
  @DisplayName(
      "the second store's export, columns in another order and spelled differently, reads the same")
  void secondStoresFile() {
    var first =
        read(
            "PLU,Description,Old EAN,VAT,Retail Price\n"
                + "100,Bread,,A,1.29\n"
                + "200,Jam,,B,1.99\n");
    var second =
        read(
            "retail_price,Branch,vat,DESCRIPTION,old-ean, plu \n"
                + "1.29,Leeds,A,Bread,,100\n"
                + "1.99,Leeds,B,Jam,,200\n");

    assertEquals(2, first.size());
    for (int i = 0; i < first.size(); i++) {
      assertTrue(first.get(i).sameContentAs(second.get(i)), "row " + i);
    }
  }

  @Test
  @DisplayName("a file that lacks a mapped column is named, not guessed at")
  void missingHeader() {
    CsvTable.Parsed t =
        CsvTable.parse("PLU,Description,VAT\n1,Bread,A\n".getBytes(StandardCharsets.UTF_8), 10);

    var bound = mapping().bind(t.headers());

    assertFalse(bound.ok());
    assertTrue(bound.missing().contains("Retail Price"));
    assertTrue(bound.missing().contains("Old EAN"));
  }
}
