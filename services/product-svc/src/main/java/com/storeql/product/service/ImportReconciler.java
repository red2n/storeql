package com.storeql.product.service;

import com.storeql.product.client.Caller;
import com.storeql.product.client.InventoryClient;
import com.storeql.product.client.PricingClient;
import com.storeql.product.domain.imports.ImportItem;
import com.storeql.product.domain.imports.ImportRecords.Job;
import com.storeql.product.domain.imports.Reconciliation;
import com.storeql.product.domain.imports.Reconciliation.Report;
import com.storeql.product.domain.imports.Reconciliation.StockLoaded;
import com.storeql.product.repo.ImportRepository;
import com.storeql.service.TenantProfiles;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The reconciliation report of an applied import: the file read again, set against the catalogue,
 * the price list and the store's opening stock as they are now. Read-only; it writes nothing.
 */
@ApplicationScoped
public class ImportReconciler {

  @Inject ImportService imports;
  @Inject ImportRepository repo;
  @Inject PricingClient pricing;
  @Inject InventoryClient inventory;
  @Inject TenantProfiles profiles;

  /** The job's report, and the job it is about. */
  public record Result(Job job, Report report) {}

  /**
   * Reconciles an apply job.
   *
   * @throws ApiException 404 {@code IMPORT_JOB_NOT_FOUND} for a job that is not the business's; 409
   *     {@code IMPORT_NOT_AN_APPLY} for a dry run, which loaded nothing to reconcile
   */
  public Result reconcile(TenantContext ctx, UUID id) {
    Job job = imports.getJob(ctx, id);
    if (!Job.APPLY.equals(job.kind())) {
      throw ApiException.conflict(
          "IMPORT_NOT_AN_APPLY", "a dry run loaded nothing; reconcile the apply that followed it");
    }
    UUID tenantId = job.tenantId();
    Caller caller = Caller.of(ctx);

    ImportWorker.Cx file = ImportWorker.Cx.load(repo, profiles, job);
    List<ImportItem> items = new ArrayList<>();
    for (int line : repo.applicableLines(tenantId, job.dryRunOf())) {
      ImportItem item = file.byLine().get(line);
      if (item != null) items.add(item);
    }
    Set<String> skus = new LinkedHashSet<>();
    Set<String> codes = new LinkedHashSet<>();
    for (ImportItem i : items) {
      skus.add(i.sku());
      if (i.gtin14() != null) codes.add(i.gtin14());
      i.aliases().forEach(a -> codes.add(a.gtin14()));
    }
    var existing = repo.existingBySku(tenantId, skus);
    var holders = repo.holdersOf(tenantId, codes);
    Map<String, UUID> variantIds = repo.variantIdsBySku(tenantId, skus);

    boolean priced = job.priceListId() != null && items.stream().anyMatch(i -> i.price() != null);
    Map<String, BigDecimal> prices =
        priced ? pricing.pricesOn(caller, job.priceListId().toString()) : null;

    StockLoaded stock = null;
    if (items.stream().anyMatch(i -> i.stockQty() != null && i.stockQty().signum() > 0)) {
      stock = openedAt(caller, job);
    }
    return new Result(
        job, Reconciliation.compare(items, existing, holders, variantIds, prices, stock));
  }

  /** What the dry run's opening put into the job's store, from inventory-svc. */
  private StockLoaded openedAt(Caller caller, Job job) {
    var totals = inventory.openedBy(caller, job.dryRunOf());
    Map<String, InventoryClient.StoreTotals> byStore = new TreeMap<>();
    totals.forEach(t -> byStore.put(t.storeId(), t));
    var here = byStore.get(job.storeId().toString());
    return here == null
        ? new StockLoaded(0, BigDecimal.ZERO, BigDecimal.ZERO, 0)
        : new StockLoaded(here.lines(), here.qty(), here.value(), here.uncostedLines());
  }
}
