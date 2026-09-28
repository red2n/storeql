package com.storeql.purchase.service;

import com.storeql.ids.Ids;
import com.storeql.purchase.domain.Domain;
import com.storeql.purchase.domain.Domain.ConsignmentSale;
import com.storeql.purchase.domain.Domain.ConsignmentSettlement;
import com.storeql.purchase.domain.Domain.NominalLedgerEntry;
import com.storeql.purchase.domain.Domain.Supplier;
import com.storeql.purchase.domain.Handle;
import com.storeql.purchase.domain.LedgerPosting;
import com.storeql.purchase.domain.Money;
import com.storeql.purchase.dto.Dtos.CreateConsignmentSettlementRequest;
import com.storeql.purchase.repo.ConsignmentRepository;
import com.storeql.purchase.repo.PurchaseRepository;
import com.storeql.web.ApiException;
import com.storeql.web.Parsing;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Consignment stock, the buyer's side: what inventory-svc sold of a supplier's stock is owed to
 * that supplier at the order's price the moment it sells, and a settlement gathers a period's
 * unsettled sales into one statement. Nothing is owed at the door; see {@code V23__consignment}.
 */
@ApplicationScoped
public class ConsignmentService {

  private static final Logger LOG = System.getLogger(ConsignmentService.class.getName());
  static final String SALE_CONSUMER = "purchase-svc/consignment-sale";

  @Inject ConsignmentRepository repo;
  @Inject PurchaseRepository purchases;

  /**
   * Records a sale inventory-svc announced, owed to the supplier: cost of sales against the
   * supplier's account, once per announcement. A supplier this business does not have is not owed
   * anything here — the announcement is logged and dropped.
   *
   * @return whether the sale was recorded now; false when it already was, or when it was not ours
   */
  public boolean recordSale(
      UUID eventId,
      UUID tenantId,
      UUID storeId,
      UUID supplierId,
      UUID variantId,
      UUID batchId,
      UUID orderId,
      BigDecimal qty,
      BigDecimal unitCost,
      LocalDate soldOn) {
    Optional<Supplier> supplier = purchases.findSupplier(tenantId, supplierId);
    if (supplier.isEmpty()) {
      LOG.log(
          Level.WARNING,
          "ConsignmentStockSold {0} names supplier {1}, which tenant {2} does not have; dropped",
          eventId,
          supplierId,
          tenantId);
      return false;
    }
    String currency = supplier.get().currency();
    BigDecimal cost = unitCost == null ? BigDecimal.ZERO : unitCost;
    BigDecimal amount = Money.round(qty.multiply(cost), currency);
    ConsignmentSale sale =
        new ConsignmentSale(
            Ids.newId(),
            tenantId,
            eventId,
            supplierId,
            storeId,
            variantId,
            batchId,
            orderId,
            qty,
            cost,
            amount,
            currency,
            soldOn,
            null,
            Instant.now());
    return repo.recordSaleOnce(eventId, SALE_CONSUMER, sale, salePosting(sale, supplier.get()));
  }

  /** Dr Purchases - Consignment, Cr Trade Creditors: the debt, the moment the goods sold. */
  static List<NominalLedgerEntry> salePosting(ConsignmentSale sale, Supplier supplier) {
    if (sale.amount().signum() <= 0) return List.of();
    return LedgerPosting.of(
            sale.tenantId(),
            sale.soldOn(),
            "Consignment sale of "
                + sale.qty().toPlainString()
                + " x variant "
                + Handle.of(sale.variantId())
                + " ("
                + supplier.name()
                + ")",
            Domain.SOURCE_CONSIGNMENT_SALE,
            sale.id(),
            sale.storeId())
        .debit(Domain.CODE_CONSIGNMENT_PURCHASES, Domain.NAME_CONSIGNMENT_PURCHASES, sale.amount())
        .credit(Domain.CODE_CREDITORS, Domain.NAME_CREDITORS, sale.amount())
        .build();
  }

  public List<ConsignmentSale> listSales(
      TenantContext ctx, String supplierId, Boolean settled, int limit) {
    return repo.findSales(
        ctx.requireTenantId(), Parsing.optionalUuid(supplierId, "supplierId"), settled, limit);
  }

  /**
   * Gathers a supplier's unsettled sales of a period into one statement, once.
   *
   * @throws ApiException 404 {@code PURCHASE_SUPPLIER_NOT_FOUND}; 400 {@code
   *     PURCHASE_CONSIGNMENT_PERIOD_INVALID} when the period ends before it starts; 409 {@code
   *     PURCHASE_CONSIGNMENT_NOTHING_TO_SETTLE} when nothing is left to settle
   */
  public ConsignmentSettlement settle(TenantContext ctx, CreateConsignmentSettlementRequest req) {
    UUID tenantId = ctx.requireTenantId();
    Supplier supplier =
        purchases
            .findSupplier(tenantId, req.supplierId())
            .orElseThrow(
                () ->
                    ApiException.notFound(
                        "PURCHASE_SUPPLIER_NOT_FOUND", "Supplier not found: " + req.supplierId()));
    LocalDate from = Parsing.date(req.from(), "from");
    LocalDate to = Parsing.date(req.to(), "to");
    if (from.isAfter(to)) {
      throw ApiException.badRequest(
          "PURCHASE_CONSIGNMENT_PERIOD_INVALID",
          "the period ends (" + to + ") before it starts (" + from + ")");
    }
    UUID id = Ids.newId();
    return repo.settle(
        new ConsignmentSettlement(
            id,
            tenantId,
            supplier.id(),
            "CS-" + Ids.shortRef(id).toUpperCase(java.util.Locale.ROOT),
            from,
            to,
            supplier.currency(),
            BigDecimal.ZERO,
            0,
            ctx.userId(),
            Instant.now()));
  }

  public List<ConsignmentSettlement> listSettlements(
      TenantContext ctx, String supplierId, int limit) {
    return repo.findSettlements(
        ctx.requireTenantId(), Parsing.optionalUuid(supplierId, "supplierId"), limit);
  }

  public ConsignmentSettlement getSettlement(TenantContext ctx, UUID id) {
    return repo.findSettlement(ctx.requireTenantId(), id)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "PURCHASE_CONSIGNMENT_SETTLEMENT_NOT_FOUND", "Settlement not found: " + id));
  }

  public List<ConsignmentSale> settlementSales(TenantContext ctx, UUID settlementId) {
    return repo.findSalesOfSettlement(ctx.requireTenantId(), settlementId);
  }
}
