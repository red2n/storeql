package com.storeql.inventory.service;

import com.storeql.ids.Ids;
import com.storeql.inventory.domain.Domain.Batch;
import com.storeql.inventory.domain.Domain.MovementAttribution;
import com.storeql.inventory.dto.OpeningStockDtos.LineResult;
import com.storeql.inventory.dto.OpeningStockDtos.OpeningStockRequest;
import com.storeql.inventory.dto.OpeningStockDtos.OpeningStockResult;
import com.storeql.inventory.dto.OpeningStockDtos.OpeningStockSummary;
import com.storeql.inventory.dto.OpeningStockDtos.StoreTotals;
import com.storeql.inventory.repo.OpeningStockRepository;
import com.storeql.inventory.repo.OpeningStockRepository.Loaded;
import com.storeql.inventory.repo.OpeningStockRepository.Outcome;
import com.storeql.service.OutboxRow;
import com.storeql.service.TenantProfiles;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Opens a store's stock from an import: management's, at a store the caller keeps, once per item,
 * every line through the door every arrival uses.
 */
@ApplicationScoped
public class OpeningStockService {

  @Inject OpeningStockRepository repo;
  @Inject TenantProfiles profiles;

  /** The most lines one call takes, as a bulk receive. */
  @Inject
  @ConfigProperty(name = "storeql.inventory.bulk.receive-max-lines", defaultValue = "500")
  int maxLines;

  /**
   * Opens the lines, each on its own transaction, so a call cut short is finished by asking again:
   * a line this job opened is answered REPLAYED and written no more.
   *
   * @throws ApiException 400 for a body that is wrong anywhere (nothing is written); 403 for a
   *     caller who is not management or not at the store; 404 for a store the business does not
   *     have
   */
  public OpeningStockResult load(TenantContext ctx, OpeningStockRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    UUID tenantId = ctx.requireTenantId();
    UUID jobId = Ids.parse(req.jobId());
    UUID storeId = Ids.parse(req.storeId());
    ctx.requireStoreAccess(storeId);
    if (req.lines().size() > maxLines) {
      throw ApiException.badRequest(
          "INVENTORY_BULK_TOO_LARGE", "an opening takes at most " + maxLines + " lines");
    }
    if (!profiles.stores(tenantId, storeId).has(storeId)) {
      throw ApiException.notFound("INVENTORY_STORE_NOT_FOUND", "no such store");
    }
    List<Batch> batches = new ArrayList<>();
    Set<UUID> seen = new HashSet<>();
    for (var line : req.lines()) {
      UUID variantId = Ids.parse(line.variantId());
      if (!seen.add(variantId)) {
        throw ApiException.badRequest(
            "INVENTORY_OPENING_DUPLICATE_LINE", "item " + variantId + " is on two lines");
      }
      batches.add(batch(tenantId, storeId, variantId, line));
    }
    MovementAttribution who =
        ctx.userId() == null
            ? MovementAttribution.system()
            : MovementAttribution.by(ctx.userId(), null);
    int loaded = 0;
    int replayed = 0;
    int refused = 0;
    List<LineResult> results = new ArrayList<>();
    for (Batch b : batches) {
      Loaded done =
          repo.load(
              b,
              jobId,
              new OutboxRow(
                  "StockReceived",
                  "storeql.inventory.stock-received",
                  tenantId,
                  b.id(),
                  Events.stockReceived(tenantId, storeId, b.variantId(), b.id(), b.receivedQty())),
              who);
      if (done.outcome() == Outcome.LOADED) loaded++;
      else if (done.outcome() == Outcome.REPLAYED) replayed++;
      else refused++;
      results.add(
          new LineResult(
              b.variantId().toString(),
              done.outcome().name(),
              done.batchId().toString(),
              done.held()));
    }
    return new OpeningStockResult(loaded, replayed, refused, results);
  }

  /** What a job opened, by store; empty when it opened nothing or the job is another business's. */
  public OpeningStockSummary summary(TenantContext ctx, UUID jobId) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    UUID tenantId = ctx.requireTenantId();
    List<StoreTotals> stores =
        repo.summary(tenantId, jobId).stream()
            .filter(s -> ctx.hasStoreAccess(s.storeId()))
            .map(
                s ->
                    new StoreTotals(
                        s.storeId().toString(),
                        s.lines(),
                        s.qty().toPlainString(),
                        s.value().toPlainString(),
                        s.uncostedLines()))
            .toList();
    return new OpeningStockSummary(jobId.toString(), stores);
  }

  private static Batch batch(
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      com.storeql.inventory.dto.OpeningStockDtos.Line line) {
    LocalDate expiry = null;
    if (line.expiryDate() != null && !line.expiryDate().isBlank()) {
      try {
        expiry = LocalDate.parse(line.expiryDate().trim());
      } catch (DateTimeParseException e) {
        throw new ApiException(
            400,
            "INVENTORY_EXPIRY_INVALID",
            "expiryDate is not an ISO date: " + line.expiryDate(),
            List.of(),
            e);
      }
    }
    String lot = line.batchNo() == null || line.batchNo().isBlank() ? null : line.batchNo().trim();
    return new Batch(
        Ids.newId(),
        tenantId,
        storeId,
        variantId,
        lot,
        line.qty(),
        line.qty(),
        line.unitCost(),
        expiry,
        Instant.now(),
        Batch.STATUS_ACTIVE,
        Batch.MATERIAL_AVAILABLE,
        null,
        null,
        null,
        Batch.OWNERSHIP_OWNED,
        null,
        Batch.DUTY_PAID);
  }
}
