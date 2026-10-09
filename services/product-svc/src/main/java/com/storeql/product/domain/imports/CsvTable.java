package com.storeql.product.domain.imports;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A supermarket's export read as a table (intent/catalogue-import.md), pure: bytes in, rows out.
 *
 * <p>An export arrives the way the last till system wrote it: with or without a byte-order mark, as
 * UTF-8, Windows-1252 or UTF-16, separated by commas, semicolons (where the decimal mark is a
 * comma), tabs or bars, with cells in quotes that hold the separator, doubled quotes and line
 * breaks. All of that is read without being told. Nothing is interpreted here — a barcode that
 * Excel turned into {@code 5.01E+12} is still the text it was; refusing it is the next step's job.
 */
public final class CsvTable {

  private CsvTable() {}

  /** One data row: the line it started on in the file and its cells, untrimmed. */
  public record Row(int line, List<String> cells) {
    public Row {
      cells = List.copyOf(cells);
    }
  }

  /**
   * The table read.
   *
   * @param headers the first row's cells, trimmed
   * @param rows the data rows, blank lines left out
   * @param delimiter the separator found
   * @param encoding the text encoding found
   */
  public record Parsed(List<String> headers, List<Row> rows, char delimiter, String encoding) {
    public Parsed {
      headers = List.copyOf(headers);
      rows = List.copyOf(rows);
    }
  }

  private static final char[] DELIMITERS = {',', ';', '\t', '|'};
  private static final int SNIFF_RECORDS = 20;

  /**
   * Reads a file.
   *
   * @param bytes the file
   * @param maxRows the most data rows one import takes
   * @return the headers and the rows
   * @throws CsvException for an empty file, a quote never closed, or more than {@code maxRows}
   */
  public static Parsed parse(byte[] bytes, int maxRows) {
    if (bytes == null || bytes.length == 0) {
      throw new CsvException(CsvException.EMPTY, "the file is empty", 0);
    }
    Decoded decoded = decode(bytes);
    String text = decoded.text();
    char delimiter = sniff(text);
    List<List<String>> records = new ArrayList<>();
    List<Integer> lines = new ArrayList<>();
    read(text, delimiter, records, lines, maxRows + 2);
    if (records.isEmpty()) {
      throw new CsvException(CsvException.EMPTY, "the file has no header row", 0);
    }
    List<String> headers = new ArrayList<>();
    for (String h : records.get(0)) headers.add(h.trim());
    if (records.size() - 1 > maxRows) {
      throw new CsvException(
          CsvException.TOO_MANY_ROWS,
          "the file has more than " + maxRows + " rows; split it and import the parts",
          lines.get(maxRows + 1));
    }
    List<Row> rows = new ArrayList<>();
    for (int i = 1; i < records.size(); i++) {
      rows.add(new Row(lines.get(i), records.get(i)));
    }
    return new Parsed(headers, rows, delimiter, decoded.encoding());
  }

  // ── encoding ─────────────────────────────────────────────────────────────────

  private record Decoded(String text, String encoding) {}

  private static Decoded decode(byte[] b) {
    if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
      return new Decoded(new String(b, 3, b.length - 3, StandardCharsets.UTF_8), "UTF-8");
    }
    if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
      return new Decoded(new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE), "UTF-16LE");
    }
    if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
      return new Decoded(new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE), "UTF-16BE");
    }
    // UTF-16 with no mark: text whose every other byte is zero.
    if (b.length >= 4 && b[1] == 0 && b[3] == 0 && b[0] != 0) {
      return new Decoded(new String(b, StandardCharsets.UTF_16LE), "UTF-16LE");
    }
    try {
      String s =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(b))
              .toString();
      return new Decoded(s, "UTF-8");
    } catch (CharacterCodingException e) {
      // Not UTF-8: what a till of the nineties-to-now writes in Western Europe.
      return new Decoded(new String(b, Charset.forName("windows-1252")), "windows-1252");
    }
  }

  // ── delimiter ────────────────────────────────────────────────────────────────

  /**
   * The separator that splits the first records into the same number of columns, more than one,
   * most often. A tie goes to the comma.
   */
  private static char sniff(String text) {
    char best = ',';
    long bestScore = -1;
    for (char d : DELIMITERS) {
      List<List<String>> records = new ArrayList<>();
      try {
        read(text, d, records, new ArrayList<>(), SNIFF_RECORDS);
      } catch (CsvException e) {
        continue;
      }
      if (records.isEmpty()) continue;
      int width = records.get(0).size();
      if (width < 2) continue;
      long consistent = records.stream().filter(r -> r.size() == width).count();
      long score = consistent * 1000 + width;
      if (score > bestScore) {
        bestScore = score;
        best = d;
      }
    }
    return best;
  }

  // ── records ──────────────────────────────────────────────────────────────────

  /**
   * Splits {@code text} into records of cells, honouring quotes. Stops after {@code limit} records.
   * Blank lines are skipped.
   */
  private static void read(
      String text, char delimiter, List<List<String>> out, List<Integer> lines, int limit) {
    List<String> cells = new ArrayList<>();
    StringBuilder cell = new StringBuilder();
    boolean quoted = false;
    boolean wasQuoted = false;
    int line = 1;
    int recordLine = 1;
    int quoteLine = 1;
    int n = text.length();
    int i = 0;
    while (i < n) {
      char c = text.charAt(i);
      i++;
      if (quoted) {
        if (c == '"') {
          if (i < n && text.charAt(i) == '"') {
            cell.append('"');
            i++;
          } else {
            quoted = false;
          }
        } else {
          if (c == '\n') line++;
          cell.append(c);
        }
        continue;
      }
      if (c == '"' && cell.length() == 0 && !wasQuoted) {
        quoted = true;
        wasQuoted = true;
        quoteLine = line;
      } else if (c == delimiter) {
        cells.add(cell.toString());
        cell.setLength(0);
        wasQuoted = false;
      } else if (c == '\n' || c == '\r') {
        if (c == '\r' && i < n && text.charAt(i) == '\n') i++;
        cells.add(cell.toString());
        cell.setLength(0);
        wasQuoted = false;
        boolean blank = cells.size() == 1 && cells.get(0).isBlank();
        if (!blank) {
          out.add(cells);
          lines.add(recordLine);
          if (out.size() >= limit) return;
        }
        cells = new ArrayList<>();
        line++;
        recordLine = line;
      } else {
        cell.append(c);
      }
    }
    if (quoted) {
      throw new CsvException(
          CsvException.MALFORMED,
          "a quoted cell opened on line " + quoteLine + " is never closed",
          quoteLine);
    }
    if (cell.length() > 0 || !cells.isEmpty() || wasQuoted) {
      cells.add(cell.toString());
      boolean blank = cells.size() == 1 && cells.get(0).isBlank();
      if (!blank && out.size() < limit) {
        out.add(cells);
        lines.add(recordLine);
      }
    }
  }
}
