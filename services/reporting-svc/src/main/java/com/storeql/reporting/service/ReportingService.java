package com.storeql.reporting.service;

import com.storeql.ids.Ids;
import com.storeql.reporting.domain.Domain.InventoryProjection;
import com.storeql.reporting.domain.Domain.MovementStat;
import com.storeql.reporting.domain.Domain.OpenSupplyLine;
import com.storeql.reporting.domain.Domain.SaleLine;
import com.storeql.reporting.domain.Domain.SalesCategoryStat;
import com.storeql.reporting.domain.Domain.SalesDayStat;
import com.storeql.reporting.domain.Domain.SalesSummary;
import com.storeql.reporting.repo.ReportingRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Business logic for reporting-svc. Thin resource → this service → repository.
 *
 * <p>Two distinct roles: the {@code apply*}/{@code record*} methods are the write side, called by
 * the Kafka handlers to fold domain events into local projections; the query methods are the read
 * side behind the report endpoints. reporting-svc owns no source-of-truth data — every projection
 * is derived from events published by inventory-svc, order-svc and payment-svc, so reports are
 * eventually consistent with those services.
 */
@ApplicationScoped
public class ReportingService {

  @Inject ReportingRepository repo;

  // ── Gap #47: Cross-store on-hand ─────────────────────────────────────────

  /**
   * Cross-store on-hand quantities, projected from consumed stock-movement events.
   *
   * @param tenantId owning tenant
   * @param storeId restrict to one store, or {@code null} for every store in the tenant
   * @param variantId restrict to one variant, or {@code null} for every variant
   * @return one row per store/variant pair with stock on hand
   */
  public List<InventoryProjection> onHand(UUID tenantId, UUID storeId, UUID variantId) {
    return repo.queryOnHand(tenantId, storeId, variantId);
  }

  // ── Gap #48: Supply/demand netting ───────────────────────────────────────

  /**
   * On-hand stock paired with the in-transit lines that will land against it.
   *
   * @param onHand current on-hand rows per store/variant
   * @param supplyLines open transfer lines shipped but not yet received
   */
  public record NettingResult(List<InventoryProjection> onHand, List<OpenSupplyLine> supplyLines) {}

  /**
   * Nets on-hand stock against open in-transit supply to show net available.
   *
   * @param tenantId owning tenant
   * @param storeId restrict to one store, or {@code null} for every store in the tenant
   * @param variantId restrict to one variant, or {@code null} for every variant
   * @return the on-hand rows and the open supply lines to net them against
   */
  public NettingResult supplyDemandNetting(UUID tenantId, UUID storeId, UUID variantId) {
    return new NettingResult(
        repo.queryOnHand(tenantId, storeId, variantId),
        repo.querySupplyLines(tenantId, storeId, variantId));
  }

  // ── Gap #49: Movement statistics ─────────────────────────────────────────

  /**
   * Stock in/out/net totals bucketed over a fixed window.
   *
   * @param tenantId owning tenant
   * @param storeId restrict to one store, or {@code null} for every store in the tenant
   * @param variantId restrict to one variant, or {@code null} for every variant
   * @param bucketDays days per bucket (1 daily, 7 weekly, 30 monthly-ish); values outside 1..365
   *     are silently coerced to 7 rather than rejected, so a nonsense query still returns a report
   * @return one row per bucket per store/variant
   */
  public List<MovementStat> movementStats(
      UUID tenantId, UUID storeId, UUID variantId, int bucketDays) {
    int days = (bucketDays < 1 || bucketDays > 365) ? 7 : bucketDays;
    return repo.queryMovementStats(tenantId, storeId, variantId, days);
  }

  // ── Projection update helpers (called by Kafka handlers) ─────────────────

  /**
   * Apply one signed stock delta (received/deducted/adjusted), deduped on eventId atomically with
   * the projection writes. Returns false if the event was already processed.
   */
  public boolean applyStockDeltaOnce(
      UUID eventId,
      String consumerName,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal delta,
      String eventType) {
    return repo.applyStockDeltaOnce(
        eventId, consumerName, tenantId, storeId, variantId, delta, eventType);
  }

  /**
   * Opens an in-transit supply line per variant when a stock transfer ships.
   *
   * <p>The lines are keyed by {@code eventId} so {@link #applyTransferReceived} can retire exactly
   * this shipment's lines when the goods land.
   *
   * @param tenantId owning tenant
   * @param eventId the {@code TransferShipped} event id, retained as the retirement key
   * @param fromStoreId store the stock left
   * @param toStoreId store the stock is bound for
   * @param variantIds variants shipped, positionally paired with {@code qtys}
   * @param qtys quantities shipped, positionally paired with {@code variantIds}
   */
  public void applyTransferShipped(
      UUID tenantId,
      UUID eventId,
      UUID fromStoreId,
      UUID toStoreId,
      List<UUID> variantIds,
      List<BigDecimal> qtys) {
    for (int i = 0; i < variantIds.size(); i++) {
      repo.insertSupplyLine(
          new com.storeql.reporting.domain.Domain.OpenSupplyLine(
              Ids.newId(),
              tenantId,
              fromStoreId,
              toStoreId,
              variantIds.get(i),
              qtys.get(i),
              eventId));
    }
  }

  /**
   * Closes the in-transit supply lines opened by the matching shipment.
   *
   * <p>Idempotent by construction: a redelivered event deletes rows that are already gone.
   *
   * @param eventId the {@code TransferShipped} event id the lines were opened under
   */
  public void applyTransferReceived(UUID eventId) {
    repo.deleteSupplyLinesByEvent(eventId);
  }

  // ── N4: Sales reporting ──────────────────────────────────────────────────

  /** Project a confirmed order into the sales facts (idempotent on the order PK). */
  public void recordSale(
      UUID tenantId,
      UUID orderId,
      UUID storeId,
      String channel,
      UUID customerId,
      BigDecimal gross,
      String currency) {
    recordSale(tenantId, orderId, storeId, channel, customerId, gross, currency, List.of());
  }

  /**
   * Record a sale with its lines (sales by category, 19.x). Idempotent on the order: a redelivered
   * OrderConfirmed leaves the sale and its lines as they were.
   *
   * @param lines the sale line by line, empty for an event minted before lines were carried
   */
  public void recordSale(
      UUID tenantId,
      UUID orderId,
      UUID storeId,
      String channel,
      UUID customerId,
      BigDecimal gross,
      String currency,
      List<SaleLine> lines) {
    repo.recordSaleOnce(
        tenantId,
        orderId,
        storeId,
        channel,
        customerId,
        gross,
        currency,
        lines == null ? List.of() : List.copyOf(lines));
  }

  /**
   * Project the catalogue's word on a product: where it sits and which variants are its. Later
   * words win; an earlier one redelivered late changes nothing.
   *
   * @param categoryPath leaf first, root last; empty for a product with no category
   * @param occurredAt when product-svc said it, or null to take the word as of now
   */
  public void applyProductCategorised(
      UUID tenantId,
      UUID productId,
      List<UUID> categoryPath,
      List<UUID> variantIds,
      Instant occurredAt) {
    repo.upsertProductCategory(
        tenantId,
        productId,
        List.copyOf(categoryPath),
        List.copyOf(variantIds),
        occurredAt == null ? Instant.now() : occurredAt);
  }

  /** A variant created after its product was announced belongs to that product. */
  public void applyVariantCreated(UUID tenantId, UUID variantId, UUID productId) {
    repo.upsertVariantProduct(tenantId, variantId, productId);
  }

  /**
   * What each category took over a range, by leaf category or rolled up to the top of the tree.
   *
   * @param top true to group by each category's top-level ancestor
   */
  public List<SalesCategoryStat> salesByCategory(
      UUID tenantId, Instant from, Instant to, UUID storeId, String channel, boolean top) {
    return repo.salesByCategory(tenantId, from, to, storeId, channel, top);
  }

  /**
   * Project one time entry's hours and cost into the labour facts.
   *
   * <p>Idempotent by the entry, not by the event: the same entry may be announced again, and the
   * last word about an entry is the right one. A correction names the entry it replaces so the
   * figure it replaced comes back out — a report that counted a corrected day twice would look
   * right and be wrong, which is the worst of the two.
   */
  public void recordLabour(
      UUID tenantId,
      UUID entryId,
      UUID supersedes,
      UUID storeId,
      java.time.LocalDate day,
      long minutes,
      BigDecimal cost,
      String currency) {
    repo.recordLabour(tenantId, entryId, supersedes, storeId, day, minutes, cost, currency);
  }

  /**
   * What each day took, and what its hours cost.
   *
   * @param from inclusive start, UTC
   * @param to exclusive end, UTC
   * @param storeId one store, or null for every store in the business
   */
  public java.util.List<com.storeql.reporting.domain.Domain.LabourDayStat> labourByDay(
      UUID tenantId, Instant from, Instant to, UUID storeId) {
    return repo.labourByDay(tenantId, from, to, storeId);
  }

  /** Add a refund to a sale's projected total, deduped on the payment event's eventId. */
  public void applySalesRefund(
      UUID eventId, String consumer, UUID tenantId, UUID orderId, BigDecimal amount) {
    repo.applySalesRefundOnce(eventId, consumer, tenantId, orderId, amount);
  }

  /**
   * Void a sale: order-svc voided a till sale after the fact ({@code OrderVoided}). The sale's fact
   * is marked, never deleted, and left out of every sales report from then on. Deduped on the
   * event's id; the first void heard for an order stands, and one heard before its sale voids the
   * sale when it lands.
   *
   * @param eventId the {@code OrderVoided} event id, the dedupe key
   * @param consumer the consumer name the dedupe mark is kept under
   * @param tenantId the business the event names, from the event itself
   * @param orderId the voided sale
   */
  public void applySaleVoided(UUID eventId, String consumer, UUID tenantId, UUID orderId) {
    repo.voidSaleOnce(eventId, consumer, tenantId, orderId);
  }

  /**
   * Aggregate sales totals over a period.
   *
   * @param tenantId owning tenant
   * @param from inclusive start of the period, UTC
   * @param to exclusive end of the period, UTC
   * @param storeId restrict to one store, or {@code null} for every store in the tenant
   * @param channel restrict to {@code ONLINE} or {@code POS}, or {@code null} for both
   * @return the summary rows, net of any refunds already projected; voided sales left out
   */
  public List<SalesSummary> salesSummary(
      UUID tenantId, Instant from, Instant to, UUID storeId, String channel) {
    return repo.salesSummary(tenantId, from, to, storeId, channel);
  }

  /**
   * Sales totals broken down by calendar day.
   *
   * @param tenantId owning tenant
   * @param from inclusive start of the period, UTC
   * @param to exclusive end of the period, UTC
   * @param storeId restrict to one store, or {@code null} for every store in the tenant
   * @param channel restrict to {@code ONLINE} or {@code POS}, or {@code null} for both
   * @return one row per day in the period that saw a sale that stands (voided sales left out)
   */
  public List<SalesDayStat> salesByDay(
      UUID tenantId, Instant from, Instant to, UUID storeId, String channel) {
    return repo.salesByDay(tenantId, from, to, storeId, channel);
  }
}
