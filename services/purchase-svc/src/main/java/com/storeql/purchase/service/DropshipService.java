package com.storeql.purchase.service;

import com.storeql.ids.Ids;
import com.storeql.purchase.client.PricingClient;
import com.storeql.purchase.domain.Domain;
import com.storeql.purchase.domain.Domain.DropshipArrangement;
import com.storeql.purchase.domain.Domain.NominalLedgerEntry;
import com.storeql.purchase.domain.Domain.PurchaseOrder;
import com.storeql.purchase.domain.Domain.PurchaseOrderLine;
import com.storeql.purchase.domain.Domain.Supplier;
import com.storeql.purchase.domain.Handle;
import com.storeql.purchase.domain.LedgerPosting;
import com.storeql.purchase.domain.Totals;
import com.storeql.purchase.dto.Dtos.CreateDropshipArrangementRequest;
import com.storeql.purchase.repo.DropshipRepository;
import com.storeql.purchase.repo.PurchaseRepository;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Dropship (consignment and dropship stock ownership), the buyer's side: stock the business never
 * holds. An arrangement names the supplier that fulfils a variant per order and tells
 * inventory-svc; a confirmed order with such a line raises one draft purchase order per supplier,
 * shipped to the customer, for a person to submit; the order is never received into stock — its
 * delivery to the customer is marked, and the cost of goods never held is posted against what the
 * supplier will invoice.
 */
@ApplicationScoped
public class DropshipService {

  private static final Logger LOG = System.getLogger(DropshipService.class.getName());
  static final String ORDER_CONSUMER = "purchase-svc/dropship-order";
  static final String FULFILMENT_DROPSHIP = "DROPSHIP";
  static final String FULFILMENT_STOCK = "STOCK";

  @Inject DropshipRepository repo;
  @Inject PurchaseRepository purchases;
  @Inject PricingClient pricing;

  // ── Arrangements ───────────────────────────────────────────────────────────

  /**
   * @throws ApiException 404 {@code PURCHASE_SUPPLIER_NOT_FOUND}; 409 {@code
   *     PURCHASE_DROPSHIP_ARRANGEMENT_EXISTS} when the variant already has a live one
   */
  public DropshipArrangement create(TenantContext ctx, CreateDropshipArrangementRequest req) {
    UUID tenantId = ctx.requireTenantId();
    Supplier supplier =
        purchases
            .findSupplier(tenantId, req.supplierId())
            .orElseThrow(
                () ->
                    ApiException.notFound(
                        "PURCHASE_SUPPLIER_NOT_FOUND", "Supplier not found: " + req.supplierId()));
    DropshipArrangement a =
        new DropshipArrangement(
            Ids.newId(),
            tenantId,
            req.variantId(),
            supplier.id(),
            req.unitCost(),
            req.vatCode() == null || req.vatCode().isBlank() ? "T1" : req.vatCode().trim(),
            true,
            ctx.userId(),
            Instant.now(),
            null);
    return repo.create(
        a,
        Events.variantSourcingChanged(tenantId, a.variantId(), FULFILMENT_DROPSHIP, supplier.id()));
  }

  public List<DropshipArrangement> list(TenantContext ctx, int limit) {
    return repo.findAll(ctx.requireTenantId(), limit);
  }

  /** Ends an arrangement: the variant is stocked again, and inventory-svc is told. */
  public DropshipArrangement end(TenantContext ctx, UUID id) {
    UUID tenantId = ctx.requireTenantId();
    DropshipArrangement a =
        repo.find(tenantId, id)
            .orElseThrow(
                () ->
                    ApiException.notFound(
                        "PURCHASE_DROPSHIP_ARRANGEMENT_NOT_FOUND", "Arrangement not found: " + id));
    repo.end(
        tenantId,
        id,
        Events.variantSourcingChanged(tenantId, a.variantId(), FULFILMENT_STOCK, null));
    return repo.find(tenantId, id).orElse(a);
  }

  // ── The order-driven purchase order ────────────────────────────────────────

  /**
   * A confirmed order: for its lines under a live arrangement, one DRAFT purchase order per
   * supplier — source DROPSHIP, the customer as ship-to, the sale named — at the arrangement's
   * cost. Once per event.
   */
  public void orderConfirmed(JsonObject o) {
    UUID eventId = Ids.parse(o.getString("eventId"));
    UUID tenantId = Ids.parse(o.getString("tenantId"));
    UUID orderId = Ids.parse(o.getString("orderId"));
    UUID storeId = optUuid(o, "storeId");
    Map<UUID, BigDecimal> wanted = new LinkedHashMap<>();
    if (o.containsKey("lines") && !o.isNull("lines")) {
      for (JsonValue v : o.getJsonArray("lines")) {
        JsonObject line = v.asJsonObject();
        UUID variantId = Ids.parse(line.getString("variantId"));
        BigDecimal qty = line.getJsonNumber("qty").bigDecimalValue();
        wanted.merge(variantId, qty, BigDecimal::add);
      }
    }
    if (wanted.isEmpty()) return;
    List<DropshipArrangement> live = repo.activeFor(tenantId, wanted.keySet());
    if (live.isEmpty()) return;
    // Idempotent on the event: a redelivered confirmation raises nothing twice.
    if (!purchases.markProcessedIfNew(Ids.derived(eventId, "dropship"), ORDER_CONSUMER)) return;

    String shipTo = shipTo(o);
    Map<String, BigDecimal> vatRates = pricing.findVatRates(tenantId);
    Map<UUID, List<DropshipArrangement>> bySupplier = new LinkedHashMap<>();
    for (DropshipArrangement a : live) {
      bySupplier.computeIfAbsent(a.supplierId(), k -> new java.util.ArrayList<>()).add(a);
    }
    Instant now = Instant.now();
    for (Map.Entry<UUID, List<DropshipArrangement>> e : bySupplier.entrySet()) {
      Optional<Supplier> supplier = purchases.findSupplier(tenantId, e.getKey());
      if (supplier.isEmpty()) {
        LOG.log(
            Level.WARNING,
            "dropship supplier {0} of tenant {1} is gone; order {2} not raised",
            e.getKey(),
            tenantId,
            orderId);
        continue;
      }
      String currency = supplier.get().currency();
      PurchaseOrder po =
          new PurchaseOrder(
                  Ids.newId(),
                  tenantId,
                  supplier.get().id(),
                  storeId,
                  Domain.PO_DRAFT,
                  currency,
                  Totals.zero(currency).net(),
                  Totals.zero(currency).vat(),
                  Totals.zero(currency).gross(),
                  LocalDate.now(ZoneOffset.UTC).plusDays(3),
                  now,
                  now,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  Domain.PO_SOURCE_DROPSHIP)
              .withDropship(orderId, shipTo);
      purchases.createPurchaseOrder(po, Events.purchaseOrderCreated(tenantId, po.id()));
      for (DropshipArrangement a : e.getValue()) {
        purchases.addPurchaseOrderLine(
            new PurchaseOrderLine(
                Ids.newId(),
                tenantId,
                po.id(),
                a.variantId(),
                wanted.get(a.variantId()),
                a.unitCost(),
                a.vatCode(),
                now,
                "dropship for sale " + Handle.of(orderId) + ", shipped to the customer"),
            currency,
            vatRates);
      }
      LOG.log(
          Level.INFO,
          "dropship order {0} raised for sale {1} of tenant {2}",
          po.id(),
          orderId,
          tenantId);
    }
  }

  /** The customer, as the order said: who, where, and how to reach them. */
  static String shipTo(JsonObject o) {
    StringBuilder sb = new StringBuilder();
    String name = optString(o, "deliveryRecipientName");
    String address = optString(o, "deliveryAddress");
    String phone = optString(o, "deliveryRecipientPhone");
    if (name != null) sb.append(name);
    if (address != null) sb.append(sb.length() == 0 ? "" : ", ").append(address);
    if (phone != null) sb.append(sb.length() == 0 ? "" : ", ").append("tel ").append(phone);
    return sb.length() == 0 ? null : sb.toString();
  }

  private static String optString(JsonObject o, String key) {
    if (!o.containsKey(key) || o.isNull(key)) return null;
    String s = o.getString(key);
    return s.isBlank() ? null : s;
  }

  private static UUID optUuid(JsonObject o, String key) {
    return o.containsKey(key) && !o.isNull(key) ? Ids.parse(o.getString(key)) : null;
  }

  // ── Delivered to the customer ──────────────────────────────────────────────

  /**
   * Marks a submitted dropship order delivered: the move a goods receipt makes for stock that
   * arrives here. Posts the cost of goods the business never held against what the supplier will
   * invoice (Dr Purchases - Dropship, Cr Goods Received Not Invoiced).
   *
   * @throws ApiException 404 {@code PURCHASE_PO_NOT_FOUND}; 409 {@code PURCHASE_PO_NOT_DELIVERABLE}
   */
  public PurchaseOrder deliver(TenantContext ctx, UUID poId) {
    UUID tenantId = ctx.requireTenantId();
    PurchaseOrder po =
        purchases
            .findPurchaseOrder(tenantId, poId)
            .orElseThrow(
                () ->
                    ApiException.notFound(
                        "PURCHASE_PO_NOT_FOUND", "Purchase order not found: " + poId));
    if (!Domain.PO_SOURCE_DROPSHIP.equals(po.source())
        || !Domain.PO_SUBMITTED.equals(po.status())) {
      throw ApiException.conflict(
          "PURCHASE_PO_NOT_DELIVERABLE",
          "only a SUBMITTED dropship order is delivered to the customer; this one is "
              + po.source()
              + " and "
              + po.status());
    }
    repo.markDelivered(tenantId, poId, deliveryPosting(po));
    return purchases.findPurchaseOrder(tenantId, poId).orElse(po);
  }

  static List<NominalLedgerEntry> deliveryPosting(PurchaseOrder po) {
    if (po.totalNet() == null || po.totalNet().signum() <= 0) return List.of();
    return LedgerPosting.of(
            po.tenantId(),
            LocalDate.now(ZoneOffset.UTC),
            "Dropship "
                + po.reference()
                + (po.salesOrderId() == null ? "" : " for sale " + Handle.of(po.salesOrderId()))
                + " delivered to the customer",
            Domain.SOURCE_DROPSHIP_DELIVERY,
            po.id(),
            po.storeId())
        .debit(Domain.CODE_DROPSHIP_PURCHASES, Domain.NAME_DROPSHIP_PURCHASES, po.totalNet())
        .credit(Domain.CODE_GRIR, Domain.NAME_GRIR, po.totalNet())
        .build();
  }
}
