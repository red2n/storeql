package com.storeql.product.domain.imports;

import com.storeql.product.domain.imports.ImportItem.Alias;
import com.storeql.product.domain.imports.ImportRows.ReadRow;
import com.storeql.product.domain.imports.ImportRows.Refusal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What an import would do, judged against what the business already has
 * (intent/catalogue-import.md), pure: each row is created, updated, left unchanged, skipped or
 * refused, and the go-live gaps are counted. Nothing is written by deciding this.
 */
public final class DryRun {

  private DryRun() {}

  /** What the catalogue holds now for a SKU. */
  public record Existing(
      String sku,
      String name,
      String gtin14,
      String soldBy,
      String unit,
      String brand,
      String category) {}

  /** One row's verdict. */
  public record RowVerdict(
      int line,
      String sku,
      String action,
      List<Refusal> refusals,
      List<String> gaps,
      List<String> changes) {
    public RowVerdict {
      refusals = List.copyOf(refusals);
      gaps = List.copyOf(gaps);
      changes = List.copyOf(changes);
    }
  }

  /** The whole file's verdict. */
  public record Plan(List<RowVerdict> rows, Summary summary) {
    public Plan {
      rows = List.copyOf(rows);
    }
  }

  /**
   * The counts the owner reads.
   *
   * @param byAction rows per action
   * @param refusals refusal code → how many rows, in code order
   * @param gaps gap code → how many rows
   * @param vatCodes the file's own VAT codes → how many rows, so the legend can be checked
   * @param newProducts how many products the file would add
   */
  public record Summary(
      int rows,
      Map<String, Integer> byAction,
      Map<String, Integer> refusals,
      Map<String, Integer> gaps,
      Map<String, Integer> vatCodes,
      int newProducts) {
    public Summary {
      byAction = Map.copyOf(byAction);
      refusals = Map.copyOf(refusals);
      gaps = Map.copyOf(gaps);
      vatCodes = Map.copyOf(vatCodes);
    }
  }

  /**
   * Judges the rows.
   *
   * @param read the rows as read from the file
   * @param existing the catalogue's variants for the file's SKUs, by SKU
   * @param holders who holds each GTIN-14 now (a variant's barcode or an alias): code → SKU
   * @param configuredVatCodes the VAT codes the business has a rate for, or null when unknown
   */
  public static Plan plan(
      List<ReadRow> read,
      Map<String, Existing> existing,
      Map<String, String> holders,
      Set<String> configuredVatCodes) {
    List<RowVerdict> rows = new ArrayList<>(read.size());
    Map<String, Integer> byAction = new LinkedHashMap<>();
    Map<String, Integer> refusals = new java.util.TreeMap<>();
    Map<String, Integer> gaps = new java.util.TreeMap<>();
    Map<String, Integer> vatCodes = new java.util.TreeMap<>();
    int creates = 0;
    for (ReadRow r : read) {
      RowVerdict v = judge(r, existing, holders, configuredVatCodes);
      rows.add(v);
      byAction.merge(v.action(), 1, Integer::sum);
      if ("CREATE".equals(v.action())) creates++;
      for (Refusal f : v.refusals()) refusals.merge(f.code(), 1, Integer::sum);
      for (String g : v.gaps()) gaps.merge(g, 1, Integer::sum);
      if (r.item() != null
          && r.item().sourceVatCode() != null
          && !r.item().sourceVatCode().isEmpty()) {
        vatCodes.merge(r.item().sourceVatCode(), 1, Integer::sum);
      }
    }
    return new Plan(rows, new Summary(read.size(), byAction, refusals, gaps, vatCodes, creates));
  }

  private static RowVerdict judge(
      ReadRow r,
      Map<String, Existing> existing,
      Map<String, String> holders,
      Set<String> configured) {
    if (r.refused()) {
      // A refused row is not loaded, so what it lacks is not a gap to close.
      return new RowVerdict(r.line(), r.sku(), "REFUSED", r.refusals(), List.of(), List.of());
    }
    if (r.item() == null) {
      return new RowVerdict(
          r.line(),
          r.sku(),
          "SKIPPED",
          List.of(),
          List.of(),
          List.of("repeats line " + r.duplicateOf()));
    }
    ImportItem i = r.item();
    List<Refusal> why = new ArrayList<>();
    if (i.gtin14() != null) {
      String holder = holders.get(i.gtin14());
      if (holder != null && !holder.equals(i.sku())) {
        why.add(new Refusal("BARCODE_HELD_BY_OTHER", i.gtin14() + " is already SKU " + holder));
      }
    }
    for (Alias a : i.aliases()) {
      String holder = holders.get(a.gtin14());
      if (holder != null && !holder.equals(i.sku())) {
        why.add(new Refusal("ALIAS_HELD_BY_OTHER", a.gtin14() + " is already SKU " + holder));
      }
    }
    List<String> gaps = new ArrayList<>(r.gaps());
    if (i.vatCode() != null && configured != null && !configured.contains(i.vatCode())) {
      gaps.add("VAT_RATE_NOT_CONFIGURED");
    }
    if (!why.isEmpty()) {
      return new RowVerdict(r.line(), i.sku(), "REFUSED", why, List.of(), List.of());
    }
    Existing e = existing.get(i.sku());
    if (e == null) {
      return new RowVerdict(r.line(), i.sku(), "CREATE", List.of(), gaps, List.of());
    }
    List<String> changes = new ArrayList<>();
    if (!Objects.equals(i.name(), e.name())) changes.add("name");
    if (i.gtin14() != null && !i.gtin14().equals(e.gtin14())) changes.add("barcode");
    if (i.soldBy() != null && !i.soldBy().equals(e.soldBy())) changes.add("soldBy");
    if (i.unit() != null && !i.unit().equals(e.unit())) changes.add("unit");
    if (i.brand() != null && !i.brand().equalsIgnoreCase(e.brand() == null ? "" : e.brand()))
      changes.add("brand");
    if (!i.categoryPath().isEmpty()
        && !String.join(" > ", i.categoryPath())
            .equalsIgnoreCase(e.category() == null ? "" : e.category())) {
      changes.add("category");
    }
    return new RowVerdict(
        r.line(), i.sku(), changes.isEmpty() ? "UNCHANGED" : "UPDATE", List.of(), gaps, changes);
  }
}
