package com.storeql.product.domain.imports;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * How a business's export maps onto StoreQL (intent/catalogue-import.md): which header holds which
 * field, what the customer's own VAT codes and sold-by words mean, how a price is written and
 * whether it is a shelf price. Saved once per business under a name and applied to every store's
 * file by header text, so a second store's export needs no re-mapping.
 *
 * <p>Pure. A header is matched ignoring case and spacing ({@code "Retail Price"} finds {@code
 * "retail price"} and {@code "RetailPrice"}); a header the mapping names that the file lacks is a
 * refusal of the file, not a silent column of blanks.
 *
 * @param columns field → the header text that holds it. Fields: {@code sku}, {@code name}
 *     (required), {@code barcode}, {@code category}, {@code vatCode}, {@code price}, {@code cost},
 *     {@code soldBy}, {@code unit}, {@code brand}, {@code stockQty}, {@code expiry}
 * @param aliasColumns further barcode columns: an old code, a multipack, a case
 * @param vatCodes the customer's VAT code → StoreQL's (upper-cased keys)
 * @param defaultVatCode the StoreQL code for a row whose VAT cell is blank, or null: never guessed
 * @param priceBasis {@code INCLUSIVE} (shelf prices, VAT inside) or {@code EXCLUSIVE}
 * @param decimalMark {@code '.'} or {@code ','}: how the file writes a decimal
 * @param dateFormat a {@link java.time.format.DateTimeFormatter} pattern for the expiry, or null
 * @param soldByValues the customer's word → {@code EACH}, {@code WEIGHT}, {@code VOLUME} or {@code
 *     LENGTH} (upper-cased keys); a word not here is {@code EACH}
 * @param categorySeparator what separates the levels of a category path, e.g. {@code ">"}
 */
public record ImportMapping(
    Map<String, String> columns,
    List<AliasColumn> aliasColumns,
    Map<String, String> vatCodes,
    String defaultVatCode,
    String priceBasis,
    char decimalMark,
    String dateFormat,
    Map<String, String> soldByValues,
    String categorySeparator) {

  /** The fields a mapping can bind to a header. */
  public static final Set<String> FIELDS =
      Set.of(
          "sku",
          "name",
          "barcode",
          "category",
          "vatCode",
          "price",
          "cost",
          "soldBy",
          "unit",
          "brand",
          "stockQty",
          "expiry");

  /** What an alias column's codes are. */
  public static final Set<String> ALIAS_KINDS = Set.of("OLD_EAN", "MULTIPACK", "CASE", "PLU");

  /** A further barcode column: its header, what its codes are, and the units a pack holds. */
  public record AliasColumn(String header, String kind, int packQty) {}

  public ImportMapping {
    columns = Map.copyOf(columns);
    aliasColumns = List.copyOf(aliasColumns);
    vatCodes = Map.copyOf(vatCodes);
    soldByValues = Map.copyOf(soldByValues);
  }

  /** The mapping's own mistakes, in words; empty when it can be used. */
  public List<String> problems() {
    List<String> out = new ArrayList<>();
    if (!columns.containsKey("sku")) out.add("the mapping names no column for sku");
    if (!columns.containsKey("name")) out.add("the mapping names no column for name");
    for (String field : columns.keySet()) {
      if (!FIELDS.contains(field)) out.add("'" + field + "' is not a field an import can set");
    }
    if (!"INCLUSIVE".equals(priceBasis) && !"EXCLUSIVE".equals(priceBasis)) {
      out.add("priceBasis is INCLUSIVE (shelf prices) or EXCLUSIVE (VAT added)");
    }
    if (decimalMark != '.' && decimalMark != ',') out.add("decimalMark is '.' or ','");
    if (columns.containsKey("expiry") && (dateFormat == null || dateFormat.isBlank())) {
      out.add("an expiry column needs a dateFormat");
    }
    for (AliasColumn a : aliasColumns) {
      if (!ALIAS_KINDS.contains(a.kind())) out.add("alias kind " + a.kind() + " is not known");
      if (a.packQty() < 1) out.add("an alias column's packQty is at least 1");
    }
    for (String v : soldByValues.values()) {
      if (!Set.of("EACH", "WEIGHT", "VOLUME", "LENGTH").contains(v)) {
        out.add("sold-by value " + v + " is not EACH, WEIGHT, VOLUME or LENGTH");
      }
    }
    return out;
  }

  /** Header text compared ignoring case, spacing and punctuation of the usual kinds. */
  static String key(String header) {
    return header == null ? "" : header.toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-.]+", "");
  }

  /** Headers bound to column positions, or what is missing. */
  public record Bound(
      Map<String, Integer> fields, List<Integer> aliasIndexes, List<String> missing) {
    public Bound {
      fields = Map.copyOf(fields);
      aliasIndexes = List.copyOf(aliasIndexes);
      missing = List.copyOf(missing);
    }

    public boolean ok() {
      return missing.isEmpty();
    }
  }

  /**
   * Binds the mapping to a file's headers.
   *
   * @param headers the file's header row
   * @return the position of every mapped column, or the headers the file lacks
   */
  public Bound bind(List<String> headers) {
    Map<String, Integer> byKey = new LinkedHashMap<>();
    for (int i = 0; i < headers.size(); i++) byKey.putIfAbsent(key(headers.get(i)), i);
    Map<String, Integer> fields = new LinkedHashMap<>();
    List<String> missing = new ArrayList<>();
    for (var e : columns.entrySet()) {
      Integer at = byKey.get(key(e.getValue()));
      if (at == null) missing.add(e.getValue());
      else fields.put(e.getKey(), at);
    }
    List<Integer> aliases = new ArrayList<>();
    for (AliasColumn a : aliasColumns) {
      Integer at = byKey.get(key(a.header()));
      if (at == null) missing.add(a.header());
      else aliases.add(at);
    }
    return new Bound(fields, aliases, missing);
  }
}
