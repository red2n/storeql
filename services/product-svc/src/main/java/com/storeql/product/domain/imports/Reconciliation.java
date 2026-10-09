package com.storeql.product.domain.imports;

import com.storeql.product.domain.imports.DryRun.Existing;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * What the file said against what the system now holds, measure by measure (pure). A migration is
 * trusted when someone can see that the numbers agree: rows, SKUs, barcodes, prices by VAT code,
 * stock quantity and value, and every SKU that did not arrive named.
 */
public final class Reconciliation {

  private Reconciliation() {}

  /** One measure: the file's figure, the loaded figure, whether they agree. */
  public record Measure(String name, String file, String loaded, boolean match) {}

  /** The prices of one VAT code, file against price list. */
  public record PriceLine(
      String vatCode, int fileCount, BigDecimal fileSum, int loadedCount, BigDecimal loadedSum) {
    public boolean match() {
      return fileCount == loadedCount && fileSum.compareTo(loadedSum) == 0;
    }
  }

  /** What opening stock holds for the job, as inventory-svc totals it. */
  public record StockLoaded(int lines, BigDecimal qty, BigDecimal value, int uncosted) {}

  /** The whole report. */
  public record Report(
      List<Measure> measures,
      List<PriceLine> prices,
      List<String> unmatchedSkus,
      int unmatchedCount,
      List<String> priceMismatches,
      int priceMismatchCount,
      boolean reconciled) {}

  /** How many names a report lists before it counts the rest. */
  public static final int LISTED = 50;

  /**
   * Compares the file's rows with what was loaded.
   *
   * @param items the rows the apply was to write, one per SKU
   * @param existing the catalogue's variants by SKU
   * @param holders who holds each GTIN-14 now (a variant's barcode or an alias), as the SKU
   * @param variantIds the variant id by SKU, for reading prices back
   * @param loadedPrices the price list's prices by variant id; null when the file carried none
   * @param stock what the job opened at its store; null when the file carried no stock
   */
  public static Report compare(
      List<ImportItem> items,
      Map<String, Existing> existing,
      Map<String, String> holders,
      Map<String, UUID> variantIds,
      Map<String, BigDecimal> loadedPrices,
      StockLoaded stock) {
    List<Measure> measures = new ArrayList<>();

    List<String> unmatched = new ArrayList<>();
    int skusLoaded = 0;
    for (ImportItem i : items) {
      if (existing.containsKey(i.sku())) skusLoaded++;
      else unmatched.add(i.sku());
    }
    measures.add(count("SKUS", items.size(), skusLoaded));

    int barcodesFile = 0;
    int barcodesLoaded = 0;
    int aliasesFile = 0;
    int aliasesLoaded = 0;
    for (ImportItem i : items) {
      if (i.gtin14() != null) {
        barcodesFile++;
        if (i.sku().equals(holders.get(i.gtin14()))) barcodesLoaded++;
      }
      for (ImportItem.Alias a : i.aliases()) {
        aliasesFile++;
        if (i.sku().equals(holders.get(a.gtin14()))) aliasesLoaded++;
      }
    }
    measures.add(count("BARCODES", barcodesFile, barcodesLoaded));
    measures.add(count("ALIASES", aliasesFile, aliasesLoaded));

    List<PriceLine> prices = new ArrayList<>();
    List<String> mismatches = new ArrayList<>();
    int mismatchCount = 0;
    if (loadedPrices != null) {
      Map<String, int[]> counts = new TreeMap<>();
      Map<String, BigDecimal[]> sums = new TreeMap<>();
      for (ImportItem i : items) {
        if (i.price() == null) continue;
        String code = i.vatCode() == null ? "NONE" : i.vatCode();
        counts.computeIfAbsent(code, k -> new int[2]);
        sums.computeIfAbsent(code, k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
        counts.get(code)[0]++;
        sums.get(code)[0] = sums.get(code)[0].add(i.price());
        UUID variant = variantIds.get(i.sku());
        BigDecimal there = variant == null ? null : loadedPrices.get(variant.toString());
        if (there != null) {
          counts.get(code)[1]++;
          sums.get(code)[1] = sums.get(code)[1].add(there);
        }
        if (there == null || there.compareTo(i.price()) != 0) {
          mismatchCount++;
          if (mismatches.size() < LISTED) {
            mismatches.add(
                i.sku()
                    + ": file "
                    + i.price().toPlainString()
                    + ", price list "
                    + (there == null ? "has none" : there.toPlainString()));
          }
        }
      }
      counts.forEach(
          (code, c) ->
              prices.add(new PriceLine(code, c[0], sums.get(code)[0], c[1], sums.get(code)[1])));
    }

    if (stock != null) {
      int lines = 0;
      int uncosted = 0;
      BigDecimal qty = BigDecimal.ZERO;
      BigDecimal value = BigDecimal.ZERO;
      for (ImportItem i : items) {
        if (i.stockQty() == null || i.stockQty().signum() <= 0) continue;
        lines++;
        qty = qty.add(i.stockQty());
        if (i.cost() == null) uncosted++;
        else value = value.add(i.stockQty().multiply(i.cost()));
      }
      measures.add(count("STOCK_LINES", lines, stock.lines()));
      measures.add(figure("STOCK_QTY", qty, stock.qty()));
      measures.add(figure("STOCK_VALUE", value, stock.value()));
      measures.add(count("STOCK_UNCOSTED", uncosted, stock.uncosted()));
    }

    boolean reconciled =
        unmatched.isEmpty()
            && measures.stream().allMatch(Measure::match)
            && prices.stream().allMatch(PriceLine::match)
            && mismatchCount == 0;
    return new Report(
        measures,
        prices,
        unmatched.stream().limit(LISTED).toList(),
        unmatched.size(),
        mismatches,
        mismatchCount,
        reconciled);
  }

  private static Measure count(String name, int file, int loaded) {
    return new Measure(name, String.valueOf(file), String.valueOf(loaded), file == loaded);
  }

  private static Measure figure(String name, BigDecimal file, BigDecimal loaded) {
    return new Measure(
        name, file.toPlainString(), loaded.toPlainString(), file.compareTo(loaded) == 0);
  }
}
