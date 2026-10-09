package com.storeql.product.domain.imports;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One row of a supermarket's export, read and checked (intent/catalogue-import.md): what the
 * business sells, in StoreQL's own terms.
 *
 * @param line the line of the file the row started on
 * @param sku the business's own code for the product (a PLU or product code): the key a corrected
 *     file updates in place
 * @param barcode the barcode as the file wrote it (digits only), kept on the variant so an exact
 *     scan finds it, or null
 * @param gtin14 the same barcode as a GTIN-14, the form two spellings of one code compare in, or
 *     null
 * @param categoryPath the category levels from the top, possibly empty
 * @param vatCode the StoreQL VAT code the customer's own code maps to, or null
 * @param sourceVatCode the customer's code as written, for the report
 * @param price the price as typed, in the mapping's basis (shelf price or net), or null
 * @param cost the unit cost, or null
 * @param soldBy {@code EACH}, {@code WEIGHT}, {@code VOLUME} or {@code LENGTH}
 * @param unit the unit of a measured item ({@code KG}, {@code L}, {@code M}…), or null
 * @param stockQty what is on the shelf, or null
 * @param expiry the last day of sale of that stock, or null
 * @param aliases further codes that find the same product
 */
public record ImportItem(
    int line,
    String sku,
    String name,
    String barcode,
    String gtin14,
    List<String> categoryPath,
    String vatCode,
    String sourceVatCode,
    BigDecimal price,
    BigDecimal cost,
    String soldBy,
    String unit,
    String brand,
    BigDecimal stockQty,
    LocalDate expiry,
    List<Alias> aliases) {

  public ImportItem {
    categoryPath = List.copyOf(categoryPath);
    aliases = List.copyOf(aliases);
  }

  /** A further code for a product: an old EAN, a multipack, a case. */
  public record Alias(String gtin14, String kind, int packQty) {}

  /** Whether two rows of the file say the same thing about a product. */
  public boolean sameContentAs(ImportItem o) {
    return java.util.Objects.equals(name, o.name)
        && java.util.Objects.equals(gtin14, o.gtin14)
        && categoryPath.equals(o.categoryPath)
        && java.util.Objects.equals(vatCode, o.vatCode)
        && compare(price, o.price)
        && compare(cost, o.cost)
        && java.util.Objects.equals(soldBy, o.soldBy)
        && java.util.Objects.equals(unit, o.unit)
        && java.util.Objects.equals(brand, o.brand)
        && compare(stockQty, o.stockQty)
        && java.util.Objects.equals(expiry, o.expiry)
        && aliases.equals(o.aliases);
  }

  private static boolean compare(BigDecimal a, BigDecimal b) {
    return a == null ? b == null : b != null && a.compareTo(b) == 0;
  }
}
