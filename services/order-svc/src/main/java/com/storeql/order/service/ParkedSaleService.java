package com.storeql.order.service;

import com.storeql.ids.Ids;
import com.storeql.order.dto.Dtos.NoSaleRequest;
import com.storeql.order.dto.Dtos.NoSaleResponse;
import com.storeql.order.dto.Dtos.ParkSaleRequest;
import com.storeql.order.dto.Dtos.ParkedSaleItemResponse;
import com.storeql.order.dto.Dtos.ParkedSaleResponse;
import com.storeql.order.repo.ParkedSaleRepository;
import com.storeql.web.ApiException;
import com.storeql.web.Parsing;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Parked (suspended) sales and no-sale/open-drawer audit. A parked sale holds line items in a draft
 * state so the cashier can serve the next customer and resume the original sale later. Parked sales
 * are not inventory-committed.
 */
@ApplicationScoped
public class ParkedSaleService {

  @Inject ParkedSaleRepository repo;

  /**
   * Parks an in-progress sale, totalling its lines and discounts as it stands.
   *
   * <p>No stock is committed — a parked sale is a draft, so nothing is held against it.
   *
   * @param tenantId owning tenant
   * @param cashierId the cashier parking it
   * @param req the store, customer and the basket rung so far
   * @param ctx the caller, who must keep the store
   * @return the parked sale with its computed subtotal and discount total
   * @throws ApiException {@code PARK_EMPTY} (400) when the sale has no items; {@code
   *     STORE_ACCESS_DENIED} (403) for a caller held to other stores
   */
  public ParkedSaleResponse park(
      UUID tenantId, UUID cashierId, ParkSaleRequest req, TenantContext ctx) {
    if (req.items() == null || req.items().isEmpty()) {
      throw new ApiException(400, "PARK_EMPTY", "Cannot park a sale with no items", List.of());
    }
    UUID storeId = Parsing.uuid(req.storeId(), "storeId");
    ctx.requireStoreAccess(storeId);
    UUID saleId = Ids.newId();

    List<ParkedSaleItemResponse> items =
        req.items().stream()
            .map(
                item -> {
                  BigDecimal discount =
                      item.discountAmount() == null ? BigDecimal.ZERO : item.discountAmount();
                  BigDecimal lineTotal = item.unitPrice().multiply(item.qty()).subtract(discount);
                  if (item.markdownId() != null && !item.markdownId().isBlank()) {
                    Parsing.uuid(item.markdownId(), "markdownId");
                  }
                  return new ParkedSaleItemResponse(
                      item.variantId(),
                      item.qty(),
                      item.unitPrice(),
                      discount,
                      lineTotal,
                      item.notes(),
                      item.markdownId() == null || item.markdownId().isBlank()
                          ? null
                          : item.markdownId());
                })
            .collect(Collectors.toList());

    BigDecimal subtotal =
        items.stream()
            .map(ParkedSaleItemResponse::lineTotal)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal discountTotal =
        items.stream()
            .map(i -> i.discountAmount() == null ? BigDecimal.ZERO : i.discountAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    return repo.park(
        tenantId,
        saleId,
        cashierId,
        storeId,
        req.customerId(),
        req.customerName(),
        subtotal,
        discountTotal,
        req.notes(),
        items);
  }

  /**
   * Reads one open parked sale, to resume it at the till.
   *
   * @param tenantId owning tenant
   * @param saleId the parked sale to read
   * @param ctx the caller, who must keep the sale's store
   * @return the parked sale with its basket
   * @throws ApiException a 404 when no such open parked sale exists in this tenant; 403 {@code
   *     STORE_ACCESS_DENIED} for a caller held to other stores
   */
  public ParkedSaleResponse get(UUID tenantId, UUID saleId, TenantContext ctx) {
    ParkedSaleResponse sale = repo.findById(tenantId, saleId);
    ctx.requireStoreAccess(Parsing.uuid(sale.storeId(), "storeId"));
    return sale;
  }

  /**
   * The still-open parked sales at the stores the caller keeps.
   *
   * @param tenantId owning tenant
   * @param storeId restrict to one store (which the caller must keep), or {@code null} for every
   *     store the caller keeps: the whole business for a caller held to none
   * @param ctx the caller
   * @return the open parked sales
   * @throws ApiException 403 {@code STORE_ACCESS_DENIED} for a store the caller does not keep
   */
  public List<ParkedSaleResponse> list(UUID tenantId, UUID storeId, TenantContext ctx) {
    return repo.listOpen(tenantId, ctx.reportStores(storeId));
  }

  /**
   * Picks a parked sale back up: it leaves the open list and the sale records who resumed it, which
   * may not be the cashier who parked it. The till then rings the basket up as an ordinary sale.
   *
   * @param tenantId owning tenant
   * @param saleId the parked sale
   * @param ctx the caller, who must keep the sale's store
   * @return the sale as resumed, with its basket
   * @throws ApiException 404 {@code PARKED_SALE_NOT_FOUND}; 409 {@code PARKED_SALE_NOT_OPEN} when
   *     it was picked up already; 403 {@code STORE_ACCESS_DENIED}
   */
  public ParkedSaleResponse resume(UUID tenantId, UUID saleId, TenantContext ctx) {
    get(tenantId, saleId, ctx);
    return repo.resume(tenantId, saleId, ctx.userId());
  }

  /**
   * Throws a parked sale away without resuming it, recording who did and when.
   *
   * <p>Nothing was sold and no stock was committed, so there is nothing to reverse.
   *
   * @param tenantId owning tenant
   * @param saleId the parked sale to discard
   * @param ctx the caller, who must keep the sale's store
   * @throws ApiException 404 {@code PARKED_SALE_NOT_FOUND} when there is no open sale by that id in
   *     this business (one already resumed or discarded included); 403 {@code STORE_ACCESS_DENIED}
   */
  public void cancel(UUID tenantId, UUID saleId, TenantContext ctx) {
    ParkedSaleResponse sale = repo.findById(tenantId, saleId);
    ctx.requireStoreAccess(Parsing.uuid(sale.storeId(), "storeId"));
    repo.discard(tenantId, saleId, ctx.userId());
  }

  /**
   * Records a cash-drawer open with no accompanying sale.
   *
   * <p>Audited because an unexplained drawer open is how cash leaves a till with no transaction to
   * show for it.
   *
   * @param tenantId owning tenant
   * @param cashierId the cashier who opened the drawer
   * @param req the store, till session and stated reason
   * @return the logged entry
   */
  public NoSaleResponse logNoSale(UUID tenantId, UUID cashierId, NoSaleRequest req) {
    UUID storeId = req.storeId() == null ? null : Parsing.uuid(req.storeId(), "storeId");
    UUID sessionId =
        req.tillSessionId() == null ? null : Parsing.uuid(req.tillSessionId(), "tillSessionId");
    return repo.logNoSale(tenantId, storeId, cashierId, sessionId, req.reason(), null);
  }
}
