package com.storeql.product.domain.imports;

import com.storeql.product.domain.imports.ImportItem.Alias;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads the rows of an export under a mapping and judges each one (intent/catalogue-import.md),
 * pure: no database, no other service. A row either becomes an {@link ImportItem} (with the gaps a
 * go-live should close) or is refused with every reason found — a row is judged whole, never
 * half-read — and nothing about a refused row is applied.
 */
public final class ImportRows {

  /** The longest lot a row may carry (inventory-svc keeps it to the same). */
  static final int MAX_LOT = 64;

  private ImportRows() {}

  /** Why a row was refused: a stable code and the words for the report. */
  public record Refusal(String code, String detail) {}

  /**
   * One row's outcome.
   *
   * @param item the row read, or null when refused
   * @param refusals every reason it was refused
   * @param gaps things a go-live should close that did not stop the row (no barcode, no VAT
   *     category, no price, stock with no cost)
   * @param duplicateOf the line of an earlier row with the same SKU and the same content, which
   *     makes this one a harmless repeat to skip; 0 when it is not
   */
  public record ReadRow(
      int line,
      String sku,
      ImportItem item,
      List<Refusal> refusals,
      List<String> gaps,
      int duplicateOf) {
    public ReadRow {
      refusals = List.copyOf(refusals);
      gaps = List.copyOf(gaps);
    }

    public boolean refused() {
      return !refusals.isEmpty();
    }
  }

  private static final Map<String, Set<String>> UNITS =
      Map.of(
          "WEIGHT", Set.of("KG", "G"),
          "VOLUME", Set.of("L", "ML"),
          "LENGTH", Set.of("M", "CM", "MM"));

  /**
   * Reads every row of a file.
   *
   * @param mapping how the business's export maps
   * @param bound the mapping's columns found in this file's headers
   * @param rows the data rows
   * @param width how many columns the header row has
   * @param scale the business currency's minor units: a price finer than this is refused
   */
  public static List<ReadRow> readAll(
      ImportMapping mapping,
      ImportMapping.Bound bound,
      List<CsvTable.Row> rows,
      int width,
      int scale) {
    List<ReadRow> out = new ArrayList<>(rows.size());
    Map<String, ImportItem> bySku = new HashMap<>();
    Map<String, Integer> skuLine = new HashMap<>();
    Map<String, String> skuOfCode = new HashMap<>();
    for (CsvTable.Row row : rows) {
      ReadRow r = read(mapping, bound, row, width, scale);
      if (r.item() != null) {
        String sku = r.item().sku();
        ImportItem earlier = bySku.get(sku);
        if (earlier != null) {
          // A repeat of a row already read: harmless if it says the same, a conflict if it does
          // not.
          if (earlier.sameContentAs(r.item())) {
            out.add(new ReadRow(r.line(), sku, null, List.of(), List.of(), skuLine.get(sku)));
          } else {
            out.add(
                refuse(
                    r.line(),
                    sku,
                    new Refusal(
                        "DUPLICATE_SKU_CONFLICT",
                        "SKU "
                            + sku
                            + " is also on line "
                            + skuLine.get(sku)
                            + " with different content")));
          }
          continue;
        }
        // A barcode (or alias) already used by another SKU in this file.
        String clash = null;
        for (String code : codesOf(r.item())) {
          String holder = skuOfCode.get(code);
          if (holder != null && !holder.equals(sku)) {
            clash = code + " is also SKU " + holder;
            break;
          }
        }
        if (clash != null) {
          out.add(refuse(r.line(), sku, new Refusal("BARCODE_REPEATED_IN_FILE", clash)));
          continue;
        }
        bySku.put(sku, r.item());
        skuLine.put(sku, r.line());
        for (String code : codesOf(r.item())) skuOfCode.put(code, sku);
      }
      out.add(r);
    }
    return out;
  }

  private static Set<String> codesOf(ImportItem i) {
    Set<String> codes = new LinkedHashSet<>();
    if (i.gtin14() != null) codes.add(i.gtin14());
    for (Alias a : i.aliases()) codes.add(a.gtin14());
    return codes;
  }

  private static ReadRow refuse(int line, String sku, Refusal... why) {
    return new ReadRow(line, sku, null, List.of(why), List.of(), 0);
  }

  /** Reads one row. */
  public static ReadRow read(
      ImportMapping mapping, ImportMapping.Bound bound, CsvTable.Row row, int width, int scale) {
    List<Refusal> why = new ArrayList<>();
    List<String> gaps = new ArrayList<>();
    List<String> cells = row.cells();
    if (cells.size() != width) {
      return refuse(
          row.line(),
          cell(cells, bound, "sku"),
          new Refusal(
              "ROW_SHAPE", "the row has " + cells.size() + " cells and the header has " + width));
    }
    String sku = cell(cells, bound, "sku");
    String name = cell(cells, bound, "name");
    if (sku.isEmpty()) why.add(new Refusal("SKU_MISSING", "the row has no SKU"));
    else if (sku.length() > 100)
      why.add(new Refusal("SKU_TOO_LONG", "a SKU is at most 100 characters"));
    if (name.isEmpty()) why.add(new Refusal("NAME_MISSING", "the row has no name"));
    else if (name.length() > 200)
      why.add(new Refusal("NAME_TOO_LONG", "a name is at most 200 characters"));

    // barcode and aliases
    String gtin = null;
    String barcode = null;
    var read = Gtin.read(cell(cells, bound, "barcode"));
    if (read.isPresent()) {
      if (read.get().ok()) {
        gtin = read.get().gtin14();
        barcode = cell(cells, bound, "barcode");
      } else
        why.add(
            new Refusal("BARCODE_" + read.get().problem().name(), cell(cells, bound, "barcode")));
    } else if (bound.fields().containsKey("barcode")) {
      gaps.add("NO_BARCODE");
    }
    List<Alias> aliases = new ArrayList<>();
    for (int a = 0; a < mapping.aliasColumns().size(); a++) {
      var col = mapping.aliasColumns().get(a);
      var ar = Gtin.read(cells.get(bound.aliasIndexes().get(a)));
      if (ar.isEmpty()) continue;
      if (ar.get().ok()) {
        aliases.add(new Alias(ar.get().gtin14(), col.kind(), col.packQty()));
      } else {
        why.add(
            new Refusal(
                "ALIAS_" + ar.get().problem().name(),
                col.header() + ": " + cells.get(bound.aliasIndexes().get(a)).trim()));
      }
    }

    // VAT
    String sourceVat = cell(cells, bound, "vatCode");
    String vat = null;
    if (!sourceVat.isEmpty()) {
      vat = mapping.vatCodes().get(sourceVat.toUpperCase(Locale.ROOT));
      if (vat == null) {
        why.add(
            new Refusal(
                "VAT_CODE_UNMAPPED", "the file's VAT code '" + sourceVat + "' is not mapped"));
      }
    } else if (mapping.defaultVatCode() != null) {
      vat = mapping.defaultVatCode();
    } else if (bound.fields().containsKey("vatCode")) {
      gaps.add("NO_VAT_CATEGORY");
    }

    // money
    BigDecimal price =
        money(cell(cells, bound, "price"), mapping.decimalMark(), scale, true, "PRICE", why);
    if (price == null
        && bound.fields().containsKey("price")
        && cell(cells, bound, "price").isEmpty()) {
      gaps.add("NO_PRICE");
    }
    BigDecimal cost =
        money(cell(cells, bound, "cost"), mapping.decimalMark(), 4, false, "COST", why);

    // how it is sold
    // Null when the file says nothing: a blank cell never changes a field, and a new product
    // defaults to EACH when it is created.
    String soldBy = null;
    String soldWord = cell(cells, bound, "soldBy");
    if (!soldWord.isEmpty()) {
      String mapped = mapping.soldByValues().get(soldWord.toUpperCase(Locale.ROOT));
      if (mapped == null) {
        why.add(
            new Refusal(
                "SOLD_BY_UNKNOWN",
                "'" + soldWord + "' is not mapped to EACH, WEIGHT, VOLUME or LENGTH"));
      } else {
        soldBy = mapped;
      }
    }
    String unit = cell(cells, bound, "unit").toUpperCase(Locale.ROOT);
    if (soldBy != null && !"EACH".equals(soldBy)) {
      if (unit.isEmpty()) {
        why.add(
            new Refusal(
                "WEIGHED_NO_UNIT",
                "an item sold by " + soldBy.toLowerCase(Locale.ROOT) + " needs a unit"));
      } else if (!UNITS.get(soldBy).contains(unit)) {
        why.add(
            new Refusal(
                "UNIT_NOT_FOR_SOLD_BY",
                "'" + unit + "' is not a unit of " + soldBy.toLowerCase(Locale.ROOT)));
      }
    }

    // stock
    BigDecimal stock = null;
    String stockText = cell(cells, bound, "stockQty");
    if (!stockText.isEmpty()) {
      stock = decimal(stockText, mapping.decimalMark());
      if (stock == null || stock.signum() < 0 || stock.scale() > 3) {
        why.add(
            new Refusal(
                "STOCK_QTY_UNREADABLE",
                "'" + stockText + "' is not a quantity of at most three decimals"));
        stock = null;
      }
    }
    LocalDate expiry = null;
    String expiryText = cell(cells, bound, "expiry");
    if (!expiryText.isEmpty()) {
      expiry = date(expiryText, mapping.dateFormat());
      if (expiry == null) {
        why.add(
            new Refusal(
                "EXPIRY_UNREADABLE",
                "'" + expiryText + "' is not a date as " + mapping.dateFormat()));
      }
    }
    String lot = emptyToNull(cell(cells, bound, "lot"));
    if (lot != null && lot.length() > MAX_LOT) {
      why.add(new Refusal("LOT_TOO_LONG", "a lot is at most " + MAX_LOT + " characters"));
      lot = null;
    }
    if (stock != null && stock.signum() > 0 && cost == null) gaps.add("STOCKED_NO_COST");

    // category
    List<String> path = new ArrayList<>();
    String categoryText = cell(cells, bound, "category");
    if (!categoryText.isEmpty()) {
      String sep =
          mapping.categorySeparator() == null || mapping.categorySeparator().isEmpty()
              ? ">"
              : mapping.categorySeparator();
      for (String level : categoryText.split(java.util.regex.Pattern.quote(sep))) {
        String t = level.trim();
        if (!t.isEmpty()) path.add(t);
      }
      if (path.size() > 5 || path.stream().anyMatch(l -> l.length() > 100)) {
        why.add(
            new Refusal(
                "CATEGORY_TOO_DEEP",
                "a category path is at most five levels of at most 100 characters"));
        path = new ArrayList<>();
      }
    }

    if (!why.isEmpty()) return new ReadRow(row.line(), sku, null, why, gaps, 0);
    return new ReadRow(
        row.line(),
        sku,
        new ImportItem(
            row.line(),
            sku,
            name,
            barcode,
            gtin,
            path,
            vat,
            sourceVat,
            price,
            cost,
            soldBy,
            unit.isEmpty() ? null : unit,
            emptyToNull(cell(cells, bound, "brand")),
            stock,
            expiry,
            lot,
            aliases),
        List.of(),
        gaps,
        0);
  }

  private static String cell(List<String> cells, ImportMapping.Bound bound, String field) {
    Integer at = bound.fields().get(field);
    return at == null || at >= cells.size() ? "" : cells.get(at).trim();
  }

  private static String emptyToNull(String s) {
    return s.isEmpty() ? null : s;
  }

  /**
   * A price or cost. Strict by design: the file's own decimal mark only, no thousands separator, an
   * optional leading currency sign; a figure finer than the allowed places is refused, never
   * rounded.
   */
  private static BigDecimal money(
      String text, char mark, int maxPlaces, boolean positive, String field, List<Refusal> why) {
    if (text.isEmpty()) return null;
    BigDecimal v = decimal(text, mark);
    if (v == null) {
      why.add(
          new Refusal(
              field + "_UNREADABLE",
              "'" + text + "' is not an amount written with '" + mark + "'"));
      return null;
    }
    if (positive ? v.signum() <= 0 : v.signum() < 0) {
      why.add(
          new Refusal(
              field + "_NOT_POSITIVE",
              "'" + text + "' is not " + (positive ? "above zero" : "zero or more")));
      return null;
    }
    if (v.stripTrailingZeros().scale() > maxPlaces) {
      why.add(
          new Refusal(
              field + "_TOO_PRECISE",
              "'" + text + "' has more than " + maxPlaces + " decimal places"));
      return null;
    }
    return v;
  }

  /** A decimal written with {@code mark}; null for anything else. */
  static BigDecimal decimal(String text, char mark) {
    String t = text.trim();
    if (!t.isEmpty() && "£€$".indexOf(t.charAt(0)) >= 0) t = t.substring(1).trim();
    String pattern = mark == ',' ? "[0-9]{1,12}(,[0-9]{1,6})?" : "[0-9]{1,12}(\\.[0-9]{1,6})?";
    if (!t.matches(pattern)) return null;
    return new BigDecimal(mark == ',' ? t.replace(',', '.') : t);
  }

  private static LocalDate date(String text, String pattern) {
    try {
      DateTimeFormatter f =
          DateTimeFormatter.ofPattern(pattern.replace('y', 'u'), Locale.ROOT)
              .withResolverStyle(ResolverStyle.STRICT);
      return LocalDate.parse(text.trim(), f);
    } catch (DateTimeParseException | IllegalArgumentException e) {
      return null;
    }
  }
}
