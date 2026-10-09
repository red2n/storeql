package com.storeql.product.domain.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A supermarket's export, read however its last till system happened to write it. */
class CsvTableTest {

  private static byte[] utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("a plain comma file: headers, rows and the lines they came from")
  void aPlainFile() {
    var t = CsvTable.parse(utf8("sku,name,price\nA1,Bread,1.29\nA2,Jam,1.99\n"), 100);

    assertEquals(List.of("sku", "name", "price"), t.headers());
    assertEquals(2, t.rows().size());
    assertEquals(List.of("A1", "Bread", "1.29"), t.rows().get(0).cells());
    assertEquals(2, t.rows().get(0).line());
    assertEquals(3, t.rows().get(1).line());
    assertEquals(',', t.delimiter());
    assertEquals("UTF-8", t.encoding());
  }

  @Test
  @DisplayName("a UTF-8 byte-order mark is not part of the first header")
  void aUtf8Bom() {
    byte[] body = utf8("sku,name\nA1,Bread\n");
    byte[] withBom = new byte[body.length + 3];
    withBom[0] = (byte) 0xEF;
    withBom[1] = (byte) 0xBB;
    withBom[2] = (byte) 0xBF;
    System.arraycopy(body, 0, withBom, 3, body.length);

    var t = CsvTable.parse(withBom, 100);

    assertEquals("sku", t.headers().get(0));
  }

  @Test
  @DisplayName("Windows-1252 (a till's pound sign and accents) is read, not garbled")
  void windows1252() {
    byte[] b =
        "sku;name;price\nA1;Crème fraîche £2;2,49\n".getBytes(Charset.forName("windows-1252"));

    var t = CsvTable.parse(b, 100);

    assertEquals("windows-1252", t.encoding());
    assertEquals("Crème fraîche £2", t.rows().get(0).cells().get(1));
  }

  @Test
  @DisplayName("UTF-16 with a mark is read")
  void utf16() {
    byte[] b = "sku,name\nA1,Bread\n".getBytes(StandardCharsets.UTF_16LE);
    byte[] withBom = new byte[b.length + 2];
    withBom[0] = (byte) 0xFF;
    withBom[1] = (byte) 0xFE;
    System.arraycopy(b, 0, withBom, 2, b.length);

    var t = CsvTable.parse(withBom, 100);

    assertEquals("UTF-16LE", t.encoding());
    assertEquals(List.of("A1", "Bread"), t.rows().get(0).cells());
  }

  @Test
  @DisplayName("semicolons, tabs and bars are found without being told")
  void otherDelimiters() {
    assertEquals(';', CsvTable.parse(utf8("sku;name;price\nA1;Bread;1,29\n"), 100).delimiter());
    assertEquals(
        '\t', CsvTable.parse(utf8("sku\tname\tprice\nA1\tBread\t1.29\n"), 100).delimiter());
    assertEquals('|', CsvTable.parse(utf8("sku|name|price\nA1|Bread|1.29\n"), 100).delimiter());
  }

  @Test
  @DisplayName("a decimal comma inside a semicolon file stays inside its cell")
  void decimalCommaInASemicolonFile() {
    var t = CsvTable.parse(utf8("sku;name;price\nA1;Bread;1,29\nA2;Jam;1,99\n"), 100);

    assertEquals(';', t.delimiter());
    assertEquals("1,29", t.rows().get(0).cells().get(2));
  }

  @Test
  @DisplayName("quoted cells hold the separator, doubled quotes and line breaks")
  void quotedCells() {
    var t =
        CsvTable.parse(
            utf8(
                "sku,name,note\nA1,\"Jam, strawberry\",\"says \"\"fresh\"\"\"\nA2,Tea,\"two\nlines\"\nA3,Salt,x\n"),
            100);

    assertEquals("Jam, strawberry", t.rows().get(0).cells().get(1));
    assertEquals("says \"fresh\"", t.rows().get(0).cells().get(2));
    assertEquals("two\nlines", t.rows().get(1).cells().get(2));
    // The line a row started on survives the line break inside the one before it.
    assertEquals(3, t.rows().get(1).line());
    assertEquals(5, t.rows().get(2).line());
  }

  @Test
  @DisplayName("CRLF, lone CR and blank lines are all line ends or nothing")
  void lineEndsAndBlankLines() {
    var t = CsvTable.parse(utf8("sku,name\r\nA1,Bread\r\n\r\nA2,Jam\rA3,Tea"), 100);

    assertEquals(3, t.rows().size());
    assertEquals("Tea", t.rows().get(2).cells().get(1));
  }

  @Test
  @DisplayName("a ragged row is returned as it is; refusing it is the next step's job")
  void aRaggedRow() {
    var t = CsvTable.parse(utf8("sku,name,price\nA1,Bread\nA2,Jam,1.99,extra\n"), 100);

    assertEquals(2, t.rows().get(0).cells().size());
    assertEquals(4, t.rows().get(1).cells().size());
  }

  @Test
  @DisplayName("a barcode Excel turned into an exponent is still the text it was")
  void anExponentBarcodeIsLeftAlone() {
    var t = CsvTable.parse(utf8("sku,barcode\nA1,5.01E+12\n"), 100);

    assertEquals("5.01E+12", t.rows().get(0).cells().get(1));
  }

  @Test
  @DisplayName(
      "a file of headers only has no rows; an empty one or one with a dangling quote is refused")
  void emptyAndMalformed() {
    assertEquals(0, CsvTable.parse(utf8("sku,name\n"), 100).rows().size());
    assertEquals(
        CsvException.EMPTY,
        assertThrows(CsvException.class, () -> CsvTable.parse(new byte[0], 100)).code());
    assertEquals(
        CsvException.EMPTY,
        assertThrows(CsvException.class, () -> CsvTable.parse(utf8("\n\n"), 100)).code());
    var bad =
        assertThrows(CsvException.class, () -> CsvTable.parse(utf8("a,b\n1,\"open\n2,3\n"), 100));
    assertEquals(CsvException.MALFORMED, bad.code());
    assertEquals(2, bad.line());
  }

  @Test
  @DisplayName("more rows than one import takes are refused, naming the first line over")
  void tooManyRows() {
    StringBuilder sb = new StringBuilder("sku,name\n");
    for (int i = 0; i < 6; i++) sb.append("S").append(i).append(",N\n");

    var e = assertThrows(CsvException.class, () -> CsvTable.parse(utf8(sb.toString()), 5));

    assertEquals(CsvException.TOO_MANY_ROWS, e.code());
    assertEquals(7, e.line());
    assertEquals(5, CsvTable.parse(utf8(sb.substring(0, sb.length() - 6)), 5).rows().size());
  }

  @Test
  @DisplayName("a single-column file is read as one column, not split on something in the data")
  void oneColumn() {
    var t = CsvTable.parse(utf8("sku\nA1\nA2\n"), 100);

    assertEquals(List.of("sku"), t.headers());
    assertEquals(2, t.rows().size());
  }
}
