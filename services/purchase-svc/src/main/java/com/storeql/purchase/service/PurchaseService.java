package com.storeql.purchase.service;

import com.storeql.ids.Ids;
import com.storeql.purchase.client.PricingClient;
import com.storeql.purchase.config.ServiceConfig;
import com.storeql.purchase.domain.BankAccount;
import com.storeql.purchase.domain.Domain;
import com.storeql.purchase.domain.Domain.GoodsReceipt;
import com.storeql.purchase.domain.Domain.GoodsReceiptLine;
import com.storeql.purchase.domain.Domain.IntercompanyInvoice;
import com.storeql.purchase.domain.Domain.NominalLedgerEntry;
import com.storeql.purchase.domain.Domain.PurchaseOrder;
import com.storeql.purchase.domain.Domain.PurchaseOrderLine;
import com.storeql.purchase.domain.Domain.Supplier;
import com.storeql.purchase.domain.Handle;
import com.storeql.purchase.domain.LedgerPosting;
import com.storeql.purchase.domain.Money;
import com.storeql.purchase.domain.PeriodControl;
import com.storeql.purchase.domain.SpendAuthority;
import com.storeql.purchase.domain.ThreeWayMatch;
import com.storeql.purchase.domain.Totals;
import com.storeql.purchase.dto.Dtos.AddPurchaseOrderLineRequest;
import com.storeql.purchase.dto.Dtos.CancelPurchaseOrderRequest;
import com.storeql.purchase.dto.Dtos.CaptureSupplierInvoiceRequest;
import com.storeql.purchase.dto.Dtos.CreateGoodsReceiptRequest;
import com.storeql.purchase.dto.Dtos.CreatePurchaseOrderRequest;
import com.storeql.purchase.dto.Dtos.CreateSupplierRequest;
import com.storeql.purchase.dto.Dtos.DecidePurchaseOrderRequest;
import com.storeql.purchase.dto.Dtos.PostJournalRequest;
import com.storeql.purchase.dto.Dtos.RaiseIntercompanyInvoiceRequest;
import com.storeql.purchase.dto.Dtos.RaiseVendorReturnRequest;
import com.storeql.purchase.dto.Dtos.RecordCreditNoteRequest;
import com.storeql.purchase.dto.Dtos.ResolveSupplierInvoiceRequest;
import com.storeql.purchase.dto.Dtos.UpdateSupplierRequest;
import com.storeql.purchase.repo.PurchaseRepository;
import com.storeql.service.OutboxRow;
import com.storeql.web.ApiException;
import com.storeql.web.Parsing;
import com.storeql.web.Permissions;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Business logic for purchase-svc. No HTTP types here. */
@ApplicationScoped
public class PurchaseService {

  // BACS standard payment terms: 30 days per UK Finance / HMRC guidance
  private static final int BACS_TERMS_DAYS = 30;

  @Inject PurchaseRepository repo;

  @Inject com.storeql.service.TenantProfiles tenants;

  @Inject PricingClient pricing;
  @Inject com.storeql.purchase.client.InventoryClient inventory;

  @Inject ServiceConfig config;
  @Inject com.storeql.service.FxRates fx;

  // ── Currency ──────────────────────────────────────────────────────────────────

  /**
   * The tenant's own trading currency, for use when the caller names none (SJ-D23).
   *
   * <p>This service stamped a hardcoded {@code "GBP"} onto suppliers, purchase orders and
   * intercompany invoices alike — the SJ-D2 defect, in the one service SJ-D2's sweep never reached.
   * On a platform whose tenants trade in USD, JPY, INR and CNY, that is not a cosmetic default: it
   * is one country's currency written onto another country's money, and every downstream figure
   * built on it inherits the error.
   *
   * <p>There is no platform default behind it any more (SJ-D53). The configured fallback was "GBP",
   * so a yen tenant's supplier was set up in pounds whenever tenant-svc was slow; a refused write
   * can be retried, a wrong currency on a supplier cannot be told from a right one.
   *
   * @param tenantId the tenant whose currency is wanted
   * @return an ISO 4217 code, never null
   * @throws ApiException 503 {@code TENANT_PROFILE_UNAVAILABLE} when tenant-svc cannot answer
   */
  String resolveTenantCurrency(UUID tenantId) {
    return tenants.requireCurrency(tenantId);
  }

  // ── Suppliers ─────────────────────────────────────────────────────────────────

  /**
   * Registers a supplier.
   *
   * <p>The supplier's currency is the one they invoice in. It defaults to the tenant's own — most
   * suppliers are domestic — but is deliberately settable, because the case that matters is the one
   * that is not: a UK tenant buying from a Japanese supplier is invoiced in JPY, and every purchase
   * order raised against that supplier is a JPY commitment.
   */
  public Supplier createSupplier(CreateSupplierRequest req, TenantContext ctx) {
    UUID tenantId = ctx.requireTenantId();
    String currency =
        req.currency() != null
            ? Money.requireIso4217(req.currency())
            : resolveTenantCurrency(tenantId);
    BankAccount.Details bank =
        bankDetails(
            req.bankAccountName(),
            req.bankSortCode(),
            req.bankAccountNumber(),
            req.bankIban(),
            req.bankBic());
    // Where a supplier is paid is a finance decision (17.10): a storekeeper may add a supplier,
    // not the account its money goes to.
    if (!bank.empty()) ctx.requirePermission(Permissions.FINANCE_PAYMENTS);
    com.storeql.einvoice.ElectronicAddress address =
        einvoiceAddress(req.einvoiceScheme(), req.einvoiceId());
    Instant now = Instant.now();
    Supplier s =
        new Supplier(
            Ids.newId(),
            tenantId,
            req.name(),
            req.vatNumber(),
            req.vatRegistered(),
            tenants.countryOr(tenantId, req.countryCode()),
            currency,
            req.paymentTermsDays() != null ? req.paymentTermsDays() : BACS_TERMS_DAYS,
            now,
            now,
            blankToNull(req.remittanceEmail()),
            bank.accountName(),
            bank.sortCode(),
            bank.accountNumber(),
            bank.iban(),
            bank.bic(),
            bank.empty() ? null : now,
            bank.empty() ? null : ctx.userId(),
            address == null ? null : address.scheme(),
            address == null ? null : address.id(),
            req.leadTimeDays());
    return repo.createSupplier(s);
  }

  /**
   * A supplier's e-invoicing address as entered, or null for none.
   *
   * @throws ApiException {@code PURCHASE_EINVOICE_ADDRESS_INVALID} (400)
   */
  private static com.storeql.einvoice.ElectronicAddress einvoiceAddress(String scheme, String id) {
    try {
      return com.storeql.einvoice.ElectronicAddress.parse(scheme, id);
    } catch (IllegalArgumentException e) {
      throw new ApiException(
          400, "PURCHASE_EINVOICE_ADDRESS_INVALID", e.getMessage(), List.of(), e);
    }
  }

  /**
   * Corrects a supplier's master data (SJ-D34). Every field is replaceable; the currency only while
   * no purchase order against the supplier is open, because each open order is a commitment in that
   * currency and the orders already raised keep theirs either way.
   *
   * @param ctx caller context; supplies the tenant
   * @param id the supplier to correct
   * @param req the master data as it should now read; currency, country and terms unchanged when
   *     omitted
   * @return the supplier as it now stands
   * @throws ApiException {@code PURCHASE_SUPPLIER_NOT_FOUND} (404); {@code
   *     PURCHASE_SUPPLIER_CURRENCY_IN_USE} (409) when the currency would change under an open
   *     order; {@code PURCHASE_SUPPLIER_DUPLICATE} (409) when the name is taken
   */
  public Supplier updateSupplier(TenantContext ctx, UUID id, UpdateSupplierRequest req) {
    Supplier existing = getSupplier(ctx, id);
    String currency =
        req.currency() != null ? Money.requireIso4217(req.currency()) : existing.currency();
    if (!currency.equals(existing.currency())) {
      int open = repo.countOpenPurchaseOrders(existing.tenantId(), id);
      if (open > 0) {
        throw ApiException.conflict(
            "PURCHASE_SUPPLIER_CURRENCY_IN_USE",
            open
                + " open purchase order(s) are denominated in "
                + existing.currency()
                + "; receive, close or cancel them before changing the currency");
      }
    }
    // Bank details are replaced as a set or not touched; clearing them is explicit. Either needs
    // finance.payments, and only an actual change moves the stamp a payment run checks.
    BankAccount.Details bank = existing.bank();
    Instant bankChangedAt = existing.bankDetailsChangedAt();
    UUID bankChangedBy = existing.bankDetailsChangedBy();
    boolean clear = Boolean.TRUE.equals(req.clearBankDetails());
    BankAccount.Details asked =
        bankDetails(
            req.bankAccountName(),
            req.bankSortCode(),
            req.bankAccountNumber(),
            req.bankIban(),
            req.bankBic());
    if (clear || !asked.empty()) {
      ctx.requirePermission(Permissions.FINANCE_PAYMENTS);
      BankAccount.Details next = clear ? BankAccount.Details.NONE : asked;
      if (!next.equals(bank)) {
        bank = next;
        bankChangedAt = Instant.now();
        bankChangedBy = ctx.userId();
      }
    }
    String remittanceEmail =
        req.remittanceEmail() == null
            ? existing.remittanceEmail()
            : validEmailOrNull(req.remittanceEmail());
    // Both halves omitted leaves the address as it was; both empty removes it.
    com.storeql.einvoice.ElectronicAddress address =
        req.einvoiceScheme() != null || req.einvoiceId() != null
            ? einvoiceAddress(req.einvoiceScheme(), req.einvoiceId())
            : existing.einvoiceId() == null
                ? null
                : new com.storeql.einvoice.ElectronicAddress(
                    existing.einvoiceScheme(), existing.einvoiceId());
    Supplier updated =
        new Supplier(
            existing.id(),
            existing.tenantId(),
            req.name().trim(),
            req.vatNumber(),
            req.vatRegistered(),
            req.countryCode() != null
                ? req.countryCode().toUpperCase(java.util.Locale.ROOT)
                : existing.countryCode(),
            currency,
            req.paymentTermsDays() != null ? req.paymentTermsDays() : existing.paymentTermsDays(),
            existing.createdAt(),
            Instant.now(),
            remittanceEmail,
            bank.accountName(),
            bank.sortCode(),
            bank.accountNumber(),
            bank.iban(),
            bank.bic(),
            bankChangedAt,
            bankChangedBy,
            address == null ? null : address.scheme(),
            address == null ? null : address.id(),
            req.leadTimeDays() == null ? existing.leadTimeDays() : req.leadTimeDays());
    if (!repo.updateSupplier(updated)) {
      throw ApiException.notFound("PURCHASE_SUPPLIER_NOT_FOUND", "Supplier not found: " + id);
    }
    return getSupplier(ctx, id);
  }

  /**
   * Lists the tenant's suppliers.
   *
   * @param ctx caller context; supplies the tenant
   * @param limit maximum rows; the caller is expected to have clamped this
   * @return the suppliers
   */
  /** Validates bank details as keyed; a bad set is a 400 naming what is wrong. */
  private static BankAccount.Details bankDetails(
      String name, String sortCode, String accountNumber, String iban, String bic) {
    try {
      return BankAccount.details(name, sortCode, accountNumber, iban, bic);
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, "PURCHASE_BANK_DETAILS_INVALID", e.getMessage(), List.of(), e);
    }
  }

  private static final java.util.regex.Pattern EMAIL =
      java.util.regex.Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

  /** An empty value clears the email; anything else must look like one. */
  private static String validEmailOrNull(String raw) {
    String v = blankToNull(raw);
    if (v != null && !EMAIL.matcher(v).matches()) {
      throw ApiException.badRequest(
          "PURCHASE_REMITTANCE_EMAIL_INVALID", "remittanceEmail is not an email address");
    }
    return v;
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }

  public List<Supplier> listSuppliers(TenantContext ctx, int limit) {
    return repo.findSuppliers(ctx.requireTenantId(), limit);
  }

  /**
   * Reads one supplier.
   *
   * @param ctx caller context; supplies the tenant
   * @param id the supplier to read
   * @return the supplier
   * @throws ApiException {@code PURCHASE_SUPPLIER_NOT_FOUND} (404) when it does not exist in this
   *     tenant
   */
  public Supplier getSupplier(TenantContext ctx, UUID id) {
    return repo.findSupplier(ctx.requireTenantId(), id)
        .orElseThrow(
            () ->
                ApiException.notFound("PURCHASE_SUPPLIER_NOT_FOUND", "Supplier not found: " + id));
  }

  // ── Purchase Orders ───────────────────────────────────────────────────────────

  /**
   * Raises a draft purchase order against a supplier.
   *
   * <p><b>The order's currency is the supplier's</b>, not the tenant's and not a literal (SJ-D24).
   * A purchase order is a commitment to pay whoever is going to invoice, so it is denominated in
   * the currency that supplier bills in: a UK tenant ordering from a Japanese supplier commits to
   * JPY, and stamping GBP on it would misstate the liability, the approval threshold and every
   * downstream total.
   *
   * <p>A caller naming a different currency is refused rather than silently overridden, on the
   * SJ-D2 precedent — a request whose stated currency is not the one recorded is worse than an
   * error. Changing what a supplier invoices in is a change to the supplier, not to one order.
   *
   * @throws ApiException 404 if the supplier does not exist for this tenant; 400 {@code
   *     PURCHASE_CURRENCY_MISMATCH} if an explicit currency contradicts the supplier's; 400 {@code
   *     PURCHASE_INVALID_CURRENCY} if it is not an ISO 4217 code
   */
  public PurchaseOrder createPurchaseOrder(CreatePurchaseOrderRequest req, TenantContext ctx) {
    Supplier supplier = getSupplier(ctx, req.supplierId());
    String ownership = ownershipOf(req.ownership());
    String dutyStatus = dutyStatusOf(req.dutyStatus());
    String currency = supplier.currency();
    if (req.currency() != null) {
      String asked = Money.requireIso4217(req.currency());
      if (!asked.equals(currency))
        throw ApiException.badRequest(
            "PURCHASE_CURRENCY_MISMATCH",
            "currency "
                + asked
                + " does not match supplier "
                + supplier.name()
                + "'s invoicing currency "
                + currency);
    }
    PurchaseOrder po =
        new PurchaseOrder(
            Ids.newId(),
            ctx.requireTenantId(),
            req.supplierId(),
            req.storeId(),
            Domain.PO_DRAFT,
            currency,
            // Scaled to the currency rather than a bare ZERO, so a JPY order opens at 0 and a
            // GBP one at 0.00 — the same figure every later restatement will produce.
            Totals.zero(currency).net(),
            Totals.zero(currency).vat(),
            Totals.zero(currency).gross(),
            req.expectedDelivery() != null
                ? Parsing.date(req.expectedDelivery(), "expectedDelivery")
                : null,
            Instant.now(),
            Instant.now(),
            null,
            null,
            null,
            null,
            // Who raised it, from the verified JWT — never from the request body. Identity is
            // subject to golden rule #3 for the same reason tenant_id is.
            ctx.userId(),
            null,
            null,
            Domain.PO_SOURCE_MANUAL);
    po = po.withOwnership(ownership).withDutyStatus(dutyStatus);
    return repo.createPurchaseOrder(
        po, Events.purchaseOrderCreated(ctx.requireTenantId(), po.id()));
  }

  /**
   * Lists the tenant's purchase orders.
   *
   * @param ctx caller context; supplies the tenant
   * @param limit maximum rows; the caller is expected to have clamped this
   * @return the purchase orders
   */
  public List<PurchaseOrder> listPurchaseOrders(TenantContext ctx, int limit) {
    return repo.findPurchaseOrders(ctx.requireTenantId(), limit);
  }

  /**
   * Reads one purchase order.
   *
   * <p>Also the tenant-scoping guard the other purchase-order methods lean on: they call this first
   * so an order from another tenant reads as absent rather than being operated on.
   *
   * @param ctx caller context; supplies the tenant
   * @param id the purchase order to read
   * @return the purchase order
   * @throws ApiException {@code PURCHASE_PO_NOT_FOUND} (404) when it does not exist in this tenant
   */
  public PurchaseOrder getPurchaseOrder(TenantContext ctx, UUID id) {
    return repo.findPurchaseOrder(ctx.requireTenantId(), id)
        .orElseThrow(
            () ->
                ApiException.notFound("PURCHASE_PO_NOT_FOUND", "Purchase order not found: " + id));
  }

  /**
   * Appends a line to a draft purchase order and restates the order's totals (SJ-D22).
   *
   * <p>The totals were the defect. {@code total_net}, {@code total_vat} and {@code total_gross}
   * were inserted as zero by {@link #createPurchaseOrder} and no code anywhere ever updated them,
   * so every purchase order in the product reported a value of zero — on the API, and on the two
   * places the procurement screen renders it. That is not a dormant column: it is a commitment
   * figure a buyer reads before approving, and the spend authority built on top of it would have
   * been authorising against nothing.
   *
   * <p>The VAT table is fetched before the transaction opens rather than inside it, so a slow
   * pricing-svc holds no database transaction open. Its absence is not fatal — see {@link
   * Totals#of} for why an unresolvable VAT code rates at zero instead of refusing the line.
   */
  public PurchaseOrderLine addPurchaseOrderLine(
      TenantContext ctx, UUID poId, AddPurchaseOrderLineRequest req) {
    PurchaseOrder po = getPurchaseOrder(ctx, poId);
    if (!Domain.PO_DRAFT.equals(po.status()))
      throw ApiException.badRequest(
          "PURCHASE_PO_NOT_DRAFT", "Lines can only be added to DRAFT purchase orders");
    PurchaseOrderLine line =
        new PurchaseOrderLine(
            Ids.newId(),
            ctx.requireTenantId(),
            poId,
            req.variantId(),
            req.qty(),
            req.unitPrice(),
            req.vatCode() != null ? req.vatCode().toUpperCase(java.util.Locale.ROOT) : "T1",
            Instant.now(),
            null);
    return repo.addPurchaseOrderLine(
        line, po.currency(), pricing.findVatRates(ctx.requireTenantId()));
  }

  /**
   * Lists a purchase order's lines.
   *
   * @param ctx caller context; supplies the tenant
   * @param poId the purchase order whose lines to list
   * @return the order's lines
   * @throws ApiException {@code PURCHASE_PO_NOT_FOUND} (404) when the order does not exist in this
   *     tenant
   */
  public List<PurchaseOrderLine> listPurchaseOrderLines(TenantContext ctx, UUID poId) {
    getPurchaseOrder(ctx, poId);
    return repo.findPurchaseOrderLines(ctx.requireTenantId(), poId);
  }

  /**
   * Submits a draft purchase order, routing it for approval when it is above the submitter's own
   * spend authority.
   *
   * <p>Before this, any staff role could commit the business to any amount: {@code
   * /purchase-orders} is not under {@code /admin/}, so the authorisation filter asked only for
   * "some staff role", and a cashier could submit an order for a million pounds. The order now
   * lands in {@code SUBMITTED} if the submitter's authority covers it and {@code PENDING_APPROVAL}
   * if it does not — and either way the submission is recorded in the append-only trail, so a
   * question about who committed what has an answer.
   *
   * <p><b>Separation of duties falls out of this rather than being bolted on.</b> An order only
   * reaches PENDING_APPROVAL because it exceeded the submitter's ceiling — so by construction that
   * same person cannot approve it, because {@link #approvePurchaseOrder} applies the identical
   * check. There is deliberately no separate "you may not approve your own order" rule: it would be
   * redundant here, and it would deadlock a single-owner shop where one person legitimately raises
   * and approves everything within their unlimited authority.
   *
   * @throws ApiException 400 {@code PURCHASE_PO_NOT_DRAFT} if the order is not DRAFT; 409 if it
   *     stopped being DRAFT between the read and the write
   */
  /**
   * Cross-docking: an order on its way announces its allocations to inventory-svc as they stand, so
   * the shops count them on their way and the delivery goes straight across the dock.
   */
  private static java.util.function.Function<
          List<Domain.LineAllocation>, java.util.Optional<OutboxRow>>
      allocationsStand(PurchaseOrder po) {
    return a ->
        java.util.Optional.of(
            Events.crossDockAllocationsSet(po.tenantId(), po.id(), po.storeId(), a));
  }

  /** An order that stops being on its way announces that nothing is owed to the shops any more. */
  private static java.util.function.Function<
          List<Domain.LineAllocation>, java.util.Optional<OutboxRow>>
      allocationsLapse(PurchaseOrder po) {
    return a ->
        java.util.Optional.of(
            Events.crossDockAllocationsSet(po.tenantId(), po.id(), po.storeId(), List.of()));
  }

  public PurchaseOrder submitPurchaseOrder(TenantContext ctx, UUID poId) {
    UUID tenantId = ctx.requireTenantId();
    PurchaseOrder po = getPurchaseOrder(ctx, poId);
    if (!Domain.PO_DRAFT.equals(po.status()))
      throw ApiException.badRequest("PURCHASE_PO_NOT_DRAFT", "Only DRAFT orders can be submitted");

    SpendAuthority authority =
        SpendAuthority.decide(
            po.totalNet(), po.currency(), ctx.roles(), config.approvalLimits(), translation(po));
    String landing = authority.authorised() ? Domain.PO_SUBMITTED : Domain.PO_PENDING_APPROVAL;

    boolean submitted =
        repo.submitPurchaseOrder(
            tenantId,
            poId,
            landing,
            trailRow(ctx, po, Domain.APPROVAL_REQUESTED, authority, authority.reason()),
            allocationsStand(po));
    if (!submitted)
      throw ApiException.conflict(
          "PURCHASE_PO_NOT_DRAFT", "The order stopped being DRAFT before it could be submitted");
    if (authority.translation() != null) {
      // The figure the decision was made against, kept: a rate moves, the record must not.
      SpendAuthority.Translation t = authority.translation();
      repo.recordTranslation(tenantId, poId, t.rate(), t.homeAmount(), t.homeCurrency());
    }
    return getPurchaseOrder(ctx, poId);
  }

  /**
   * The order's net in the business's home currency at the rate it keeps (03.x), or null when the
   * order is already in the home currency or no rate is kept — in which case an unconfigured
   * currency fails closed, as before.
   */
  private SpendAuthority.Translation translation(PurchaseOrder po) {
    if (po.totalNet() == null || po.currency() == null) return null;
    return fx.toHome(po.tenantId(), po.totalNet(), po.currency())
        .filter(c -> !c.currency().equals(po.currency()))
        .map(c -> new SpendAuthority.Translation(c.amount(), c.currency(), c.rate()))
        .orElse(null);
  }

  /**
   * Approves an order that was above its submitter's authority.
   *
   * <p>The approver's own authority is checked against the same figure by the same function — an
   * approval by someone who could not have submitted the order themselves would defeat the entire
   * control, and is the obvious way to get this wrong.
   *
   * <p>The order's total is re-read here rather than taken from the request, and stamped onto the
   * trail row: an order can be edited after a rejection, so approving against a figure the caller
   * supplied would let the amount change between the review and the decision.
   *
   * @throws ApiException 404 if no such order; 409 {@code PURCHASE_PO_NOT_PENDING_APPROVAL} if it
   *     is not awaiting a decision; 403 {@code PURCHASE_APPROVAL_EXCEEDS_AUTHORITY} if the
   *     approver's own ceiling does not cover it
   */
  public PurchaseOrder approvePurchaseOrder(
      TenantContext ctx, UUID poId, DecidePurchaseOrderRequest req) {
    ctx.requirePermission(Permissions.PURCHASING_APPROVE);
    UUID tenantId = ctx.requireTenantId();
    PurchaseOrder po = requirePendingApproval(ctx, poId);

    SpendAuthority authority =
        SpendAuthority.decide(
            po.totalNet(), po.currency(), ctx.roles(), config.approvalLimits(), translation(po));
    if (!authority.authorised())
      throw ApiException.forbidden("PURCHASE_APPROVAL_EXCEEDS_AUTHORITY", authority.reason());

    boolean decided =
        repo.decidePurchaseOrder(
            tenantId,
            poId,
            true,
            trailRow(
                ctx,
                po,
                Domain.APPROVAL_APPROVED,
                authority,
                req == null ? null : trimmed(req.reason())),
            allocationsStand(po));
    if (!decided)
      throw ApiException.conflict(
          "PURCHASE_PO_NOT_PENDING_APPROVAL",
          "The order was decided by someone else before this approval landed");
    return getPurchaseOrder(ctx, poId);
  }

  /**
   * Rejects an order awaiting approval, returning it to DRAFT so it can be corrected and
   * resubmitted.
   *
   * <p>A reason is required, and an approval's is not, because only the rejection leaves somebody
   * with work to do and no idea what to change.
   *
   * <p>Rejecting needs no spend authority. Refusing to commit money is not itself a commitment, and
   * requiring authority to say no would mean an order too large for anyone configured could never
   * be cleared out of the queue at all.
   *
   * @throws ApiException 404 if no such order; 409 if it is not awaiting a decision; 400 {@code
   *     PURCHASE_APPROVAL_REASON_REQUIRED} if no reason is given
   */
  public PurchaseOrder rejectPurchaseOrder(
      TenantContext ctx, UUID poId, DecidePurchaseOrderRequest req) {
    UUID tenantId = ctx.requireTenantId();
    PurchaseOrder po = requirePendingApproval(ctx, poId);
    String reason = req == null ? null : trimmed(req.reason());
    if (reason == null)
      throw ApiException.badRequest(
          "PURCHASE_APPROVAL_REASON_REQUIRED",
          "A rejection must say why, so the buyer knows what to change");

    SpendAuthority authority =
        SpendAuthority.decide(
            po.totalNet(), po.currency(), ctx.roles(), config.approvalLimits(), translation(po));
    boolean decided =
        repo.decidePurchaseOrder(
            tenantId,
            poId,
            false,
            trailRow(ctx, po, Domain.APPROVAL_REJECTED, authority, reason),
            allocationsLapse(po));
    if (!decided)
      throw ApiException.conflict(
          "PURCHASE_PO_NOT_PENDING_APPROVAL",
          "The order was decided by someone else before this rejection landed");
    return getPurchaseOrder(ctx, poId);
  }

  /**
   * The order's complete approval history — every submission and every decision.
   *
   * @throws ApiException 404 if the order does not exist for this tenant
   */
  public List<Domain.PurchaseOrderApproval> purchaseOrderApprovals(TenantContext ctx, UUID poId) {
    getPurchaseOrder(ctx, poId); // 404s another tenant's order before reading its trail
    return repo.findApprovals(ctx.requireTenantId(), poId);
  }

  /**
   * Whether spend authority is configured at all; false means submission is never routed.
   *
   * @return {@code true} when at least one approval limit is configured
   */
  public boolean approvalEnabled() {
    return config.approvalEnabled();
  }

  /**
   * The caller's own spend ceiling in one currency.
   *
   * <p>Asks "what is my ceiling", not "may I spend this" — the same decision routine answers both,
   * given a null total.
   *
   * @param ctx caller context; supplies the tenant and the roles the ceiling is derived from
   * @param currency ISO-4217 code; limits are configured per currency because StoreQL does no FX
   * @return the caller's authority in that currency
   * @throws ApiException {@code 400} when the currency is not an ISO-4217 code
   */
  public SpendAuthority spendAuthority(TenantContext ctx, String currency) {
    ctx.requireTenantId();
    // A null total asks "what is my ceiling", not "may I spend this", and decide() answers both.
    return SpendAuthority.decide(
        null, Money.requireIso4217(currency), ctx.roles(), config.approvalLimits());
  }

  private PurchaseOrder requirePendingApproval(TenantContext ctx, UUID poId) {
    PurchaseOrder po = getPurchaseOrder(ctx, poId);
    if (!Domain.PO_PENDING_APPROVAL.equals(po.status()))
      throw ApiException.conflict(
          "PURCHASE_PO_NOT_PENDING_APPROVAL",
          "Only an order awaiting approval can be decided (status: " + po.status() + ")");
    return po;
  }

  /**
   * Builds one append-only trail row, capturing the figure and the authority as they stand at this
   * moment rather than leaving either to be re-derived later from data that can change.
   */
  private Domain.PurchaseOrderApproval trailRow(
      TenantContext ctx,
      PurchaseOrder po,
      String decision,
      SpendAuthority authority,
      String reason) {
    return new Domain.PurchaseOrderApproval(
        Ids.newId(),
        po.tenantId(),
        po.id(),
        decision,
        po.totalNet(),
        po.currency(),
        authority.ceiling(),
        ctx.userId(),
        authority.role(),
        reason,
        Instant.now());
  }

  private static String trimmed(String s) {
    if (s == null) return null;
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }

  /**
   * Cancels a purchase order raised in error (SJ-D3).
   *
   * <p>{@code CANCELLED} was declared in V1's CHECK constraint and in {@link Domain} from the
   * start, but nothing ever wrote it -- the only transitions in the service were DRAFT to SUBMITTED
   * here and SUBMITTED to RECEIVED inside {@code createGoodsReceipt}. A purchase order raised by
   * mistake was therefore stuck forever, and a stuck SUBMITTED order stays receivable indefinitely.
   *
   * <p>Cancellable from DRAFT and SUBMITTED only. A RECEIVED order has stock booked against it, so
   * cancelling it would silently orphan that stock -- reverse it with a return to vendor instead
   * (not yet built). Re-cancelling an already-cancelled order is refused rather than treated as
   * idempotent: the second caller's reason would be discarded, and a cancellation whose stated
   * reason is not the one recorded is worse than an error.
   *
   * <p>The state guard is enforced in the UPDATE's WHERE clause, not by the read above it, so a
   * cancel racing a goods receipt cannot both succeed. The read exists only to distinguish 404 from
   * 409 for the caller.
   *
   * @param ctx the caller's tenant context
   * @param poId the purchase order to cancel
   * @param req the cancellation request, carrying the required reason
   * @return the cancelled purchase order
   * @throws ApiException 404 {@code PURCHASE_PO_NOT_FOUND} if no such order exists for this tenant;
   *     409 {@code PURCHASE_PO_NOT_CANCELLABLE} if it is already RECEIVED or CANCELLED
   */
  public PurchaseOrder cancelPurchaseOrder(
      TenantContext ctx, UUID poId, CancelPurchaseOrderRequest req) {
    UUID tenantId = ctx.requireTenantId();
    PurchaseOrder po = getPurchaseOrder(ctx, poId);
    String reason = req.reason().trim();

    boolean cancelled =
        repo.cancelPurchaseOrder(
            tenantId,
            poId,
            reason,
            Events.purchaseOrderCancelled(tenantId, poId, reason),
            allocationsLapse(po));
    if (!cancelled)
      throw ApiException.conflict(
          "PURCHASE_PO_NOT_CANCELLABLE",
          "Only DRAFT or SUBMITTED purchase orders can be cancelled (status: " + po.status() + ")");
    return getPurchaseOrder(ctx, poId);
  }

  /**
   * What is still outstanding on a purchase order, line by line.
   *
   * <p>The reason partial receipt needs a screen and not only a status: a buyer chasing a supplier
   * has to know <em>what</em> is missing, and "PARTIALLY_RECEIVED" does not say.
   */
  public List<Domain.PurchaseOrderLineProgress> purchaseOrderProgress(
      TenantContext ctx, UUID poId) {
    getPurchaseOrder(ctx, poId); // 404s for another tenant's order before reading any quantity
    return repo.findLineProgress(ctx.requireTenantId(), poId);
  }

  /**
   * Short-closes a partially received order: the balance is never arriving and we have stopped
   * waiting for it.
   *
   * <p>Without this a partially received order that the supplier never completes sits in
   * PARTIALLY_RECEIVED forever — the same "stuck for good" shape SJ-D3 fixed for DRAFT and
   * SUBMITTED, which is why building partial receipt without building this would have traded one
   * dead end for another.
   *
   * <p>CLOSED rather than RECEIVED because "we got it all" and "we gave up on the rest" are
   * different facts, and a supplier scorecard that cannot tell them apart is worthless. CLOSED
   * rather than CANCELLED because stock is booked against this order — SJ-D3's own reason for
   * refusing to cancel a received one.
   */
  public PurchaseOrder closePurchaseOrderShort(
      TenantContext ctx, UUID poId, CancelPurchaseOrderRequest req) {
    UUID tenantId = ctx.requireTenantId();
    PurchaseOrder po = getPurchaseOrder(ctx, poId);
    boolean closed =
        repo.closePurchaseOrderShort(tenantId, poId, req.reason().trim(), allocationsLapse(po));
    if (!closed)
      throw ApiException.conflict(
          "PURCHASE_PO_NOT_CLOSEABLE",
          "only a PARTIALLY_RECEIVED order can be short-closed — this one is "
              + po.status()
              + ". Nothing delivered? Cancel it. Everything delivered? It is already RECEIVED.");
    return getPurchaseOrder(ctx, poId);
  }

  // ── Supplier invoices (three-way match) ───────────────────────────────────────

  /**
   * Records a supplier's invoice against a purchase order and matches it three ways.
   *
   * <p>The invoice is stored whether or not it matches. Flagging never blocks capture: an invoice
   * that arrived is a fact, and refusing to record one that disagrees with the order destroys the
   * evidence of the disagreement — which is exactly what somebody needs in order to argue with the
   * supplier.
   *
   * <p>The one thing that <em>is</em> refused is a currency the order was not placed in. That is
   * not a variance to flag; it is a different document, and matching a JPY invoice against a GBP
   * order would compare two numbers that share nothing but a decimal point (SJ-D24, SJ-D25).
   *
   * @throws ApiException 404 if the order does not exist for this tenant; 400 {@code
   *     PURCHASE_CURRENCY_MISMATCH} for the wrong currency; 409 {@code PURCHASE_INVOICE_DUPLICATE}
   *     if this supplier's invoice number was already captured
   */
  public Domain.SupplierInvoice captureSupplierInvoice(
      TenantContext ctx, CaptureSupplierInvoiceRequest req) {
    UUID tenantId = ctx.requireTenantId();
    PurchaseOrder po = getPurchaseOrder(ctx, req.poId());
    if (po.consigned()) {
      throw ApiException.conflict(
          "PURCHASE_CONSIGNMENT_NOT_INVOICED",
          "a consignment order is settled on its sales (see /admin/consignment/settlements), not"
              + " invoiced on receipt");
    }

    if (req.lines() == null || req.lines().isEmpty())
      throw ApiException.badRequest(
          "PURCHASE_INVOICE_NO_LINES", "an invoice with no lines has nothing to match");

    String currency = po.currency();
    if (req.currency() != null) {
      String asked = Money.requireIso4217(req.currency());
      if (!asked.equals(currency))
        throw ApiException.badRequest(
            "PURCHASE_CURRENCY_MISMATCH",
            "invoice currency " + asked + " does not match the order's " + currency);
    }

    // Matched against the order and every receipt AND every earlier invoice on it — see
    // findMatchPositions for why the invoiced leg has to be cumulative.
    List<ThreeWayMatch.MatchLine> matched =
        ThreeWayMatch.match(
            req.lines().stream()
                .map(l -> new ThreeWayMatch.InvoicedLine(l.variantId(), l.qty(), l.unitPrice()))
                .toList(),
            repo.findMatchPositions(tenantId, req.poId()),
            config.matchTolerance());

    BigDecimal net = BigDecimal.ZERO;
    for (var l : req.lines()) {
      net = net.add(Money.round(l.qty().multiply(l.unitPrice()), currency));
    }
    net = Money.round(net, currency);
    BigDecimal vat =
        Money.round(req.vatAmount() == null ? BigDecimal.ZERO : req.vatAmount(), currency);

    boolean allMatched = matched.stream().allMatch(ThreeWayMatch.MatchLine::matched);
    BigDecimal gross = net.add(vat);
    LocalDate invoiceDate = Parsing.date(req.invoiceDate(), "invoiceDate");

    // The header check: the supplier's own total against the sum of the supplier's own lines.
    // An invoice that does not add up is wrong before any line is compared with anything.
    BigDecimal statedGross =
        req.statedGross() == null ? null : Money.round(req.statedGross(), currency);
    List<String> headerVariances = new ArrayList<>();
    if (statedGross != null && config.matchTolerance().totalMismatch(statedGross, gross)) {
      headerVariances.add(ThreeWayMatch.TOTAL_MISMATCH);
    }

    // Payment terms: the supplier's, counted from the invoice date. The one figure accounts
    // payable schedules by.
    Supplier supplier = getSupplier(ctx, po.supplierId());
    LocalDate dueDate = invoiceDate.plusDays(supplier.paymentTermsDays());

    // Posted whether or not it matched (SAP's model, not "post on approval"): the liability
    // exists the moment the supplier has invoiced. What a variance stops is payment.
    requireOpenPeriod(tenantId, po.storeId(), invoiceDate);

    UUID invoiceId = Ids.newId();
    Instant now = Instant.now();
    Domain.SupplierInvoice invoice =
        new Domain.SupplierInvoice(
            invoiceId,
            tenantId,
            po.id(),
            po.supplierId(),
            req.invoiceNumber().trim(),
            invoiceDate,
            currency,
            net,
            vat,
            gross,
            allMatched && headerVariances.isEmpty()
                ? Domain.INVOICE_MATCHED
                : Domain.INVOICE_FLAGGED,
            now,
            ctx.userId(),
            now,
            dueDate,
            statedGross,
            String.join(",", headerVariances),
            now,
            null,
            null,
            null,
            null,
            null);

    List<Domain.SupplierInvoiceLine> lines = new ArrayList<>(req.lines().size());
    for (int i = 0; i < req.lines().size(); i++) {
      var in = req.lines().get(i);
      lines.add(
          new Domain.SupplierInvoiceLine(
              Ids.newId(),
              tenantId,
              invoiceId,
              in.variantId(),
              in.qty(),
              in.unitPrice(),
              in.vatCode() != null ? in.vatCode().toUpperCase(java.util.Locale.ROOT) : "T1",
              String.join(",", matched.get(i).variances()),
              Instant.now()));
    }
    return repo.captureSupplierInvoice(
        invoice,
        lines,
        Events.supplierInvoiceCaptured(tenantId, invoice),
        invoicePosting(tenantId, invoice, po.storeId()));
  }

  /**
   * Lists the supplier invoices captured against a purchase order.
   *
   * @param ctx caller context; supplies the tenant
   * @param poId the purchase order whose invoices to list
   * @param limit maximum rows; the caller is expected to have clamped this
   * @return the supplier invoices
   */
  public List<Domain.SupplierInvoice> listSupplierInvoices(
      TenantContext ctx, UUID poId, String status, int limit) {
    String wanted = null;
    if (status != null && !status.isBlank()) {
      wanted = status.trim().toUpperCase(java.util.Locale.ROOT);
      if (!INVOICE_STATUSES.contains(wanted)) {
        throw ApiException.badRequest(
            "PURCHASE_INVOICE_STATUS_UNKNOWN",
            "status must be one of " + INVOICE_STATUSES + ", not " + status);
      }
    }
    return repo.findSupplierInvoices(ctx.requireTenantId(), poId, wanted, limit);
  }

  private static final java.util.Set<String> INVOICE_STATUSES =
      java.util.Set.of(
          Domain.INVOICE_MATCHED,
          Domain.INVOICE_FLAGGED,
          Domain.INVOICE_APPROVED,
          Domain.INVOICE_REJECTED);

  /**
   * Reads one supplier invoice.
   *
   * @param ctx caller context; supplies the tenant
   * @param id the invoice to read
   * @return the supplier invoice
   * @throws ApiException {@code PURCHASE_INVOICE_NOT_FOUND} (404) when it does not exist in this
   *     tenant
   */
  public Domain.SupplierInvoice getSupplierInvoice(TenantContext ctx, UUID id) {
    return repo.findSupplierInvoice(ctx.requireTenantId(), id)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "PURCHASE_INVOICE_NOT_FOUND", "Supplier invoice not found: " + id));
  }

  /**
   * The invoice's lines with all three documents' figures beside them.
   *
   * <p>The variances come from the stored line rather than being recomputed, because they are the
   * figures the decision was made against: the purchase order can be amended after an invoice is
   * flagged, and re-matching on read would silently erase the disagreement it was flagged for. The
   * ordered and received columns beside them are read live, so the screen can show both what was
   * true then and what is true now.
   */
  public List<Domain.SupplierInvoiceLine> supplierInvoiceLines(TenantContext ctx, UUID invoiceId) {
    getSupplierInvoice(ctx, invoiceId);
    return repo.findSupplierInvoiceLines(ctx.requireTenantId(), invoiceId);
  }

  /**
   * What the order and the receipts say about each variant, before any new invoice is applied.
   *
   * <p>The baseline a three-way match is computed against.
   *
   * @param ctx caller context; supplies the tenant
   * @param poId the purchase order to read positions for
   * @return one position per ordered variant
   * @throws ApiException {@code PURCHASE_PO_NOT_FOUND} (404) when the order does not exist in this
   *     tenant
   */
  public List<ThreeWayMatch.OrderPosition> matchPositions(TenantContext ctx, UUID poId) {
    getPurchaseOrder(ctx, poId);
    return repo.findMatchPositions(ctx.requireTenantId(), poId);
  }

  // ── Goods Receipts ────────────────────────────────────────────────────────────

  /**
   * Books a delivery against a purchase order, moving it to PARTIALLY_RECEIVED or RECEIVED.
   *
   * <p>A partially received order is still receivable — that is the point of the state. The status
   * check here only fails a hopeless request early with a clear message; the authoritative
   * over-receipt check runs inside the repository transaction under a row lock.
   *
   * @param req the purchase order and the quantities received per variant
   * @param ctx caller context; supplies the tenant
   * @param idempotencyKey the caller's {@code Idempotency-Key}, so a retried delivery is not booked
   *     twice
   * @return the recorded goods receipt
   * @throws ApiException {@code PURCHASE_PO_NOT_FOUND} (404) when the order does not exist; {@code
   *     PURCHASE_PO_NOT_RECEIVABLE} (400) when it is not SUBMITTED or PARTIALLY_RECEIVED
   */
  public GoodsReceipt receiveGoods(
      CreateGoodsReceiptRequest req, TenantContext ctx, String idempotencyKey) {
    PurchaseOrder po = getPurchaseOrder(ctx, req.poId());
    if (po.dropship()) {
      throw ApiException.conflict(
          "PURCHASE_DROPSHIP_NOT_RECEIVED",
          "a dropship order ships to the customer and is never received into stock; mark it"
              + " delivered instead (POST /purchase-orders/{id}/dropship-delivered)");
    }
    // A partially received order is still receivable — that is the whole point of the state. The
    // authoritative check is inside the repository transaction, under a row lock; this one exists
    // to fail a hopeless request early with a clearer message than a rolled-back transaction.
    if (!Domain.PO_SUBMITTED.equals(po.status())
        && !Domain.PO_PARTIALLY_RECEIVED.equals(po.status()))
      throw ApiException.badRequest(
          "PURCHASE_PO_NOT_RECEIVABLE",
          "a purchase order can only be received while SUBMITTED or PARTIALLY_RECEIVED — this one"
              + " is "
              + po.status());
    if (req.lines() == null || req.lines().isEmpty())
      throw ApiException.badRequest("PURCHASE_GRN_EMPTY", "GRN must have at least one line");

    GoodsReceipt gr =
        new GoodsReceipt(
            Ids.newId(),
            ctx.requireTenantId(),
            req.poId(),
            req.storeId(),
            Instant.now(),
            Instant.now(),
            idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey : null);
    List<GoodsReceiptLine> lines =
        req.lines().stream()
            .map(
                l ->
                    new GoodsReceiptLine(
                        Ids.newId(),
                        ctx.requireTenantId(),
                        gr.id(),
                        l.variantId(),
                        l.qtyReceived(),
                        Instant.now()))
            .toList();
    // Dr Stock / Cr GR/IR, in the receipt's own transaction. The period is checked first so a
    // closed month refuses the receipt before anything is written.
    requireOpenPeriod(ctx.requireTenantId(), gr.storeId(), today());
    java.util.Map<UUID, BigDecimal> unitPrice = unitPriceByVariant(ctx.requireTenantId(), po);
    return repo.createGoodsReceipt(
        gr,
        lines,
        Events.goodsReceived(
            ctx.requireTenantId(),
            gr.id(),
            gr.storeId(),
            gr.poId(),
            lines,
            unitPrice,
            po.ownership(),
            po.supplierId(),
            po.dutyStatus()),
        receiptPosting(po, gr, lines, unitPrice));
  }

  /**
   * OWNED when unsaid; CONSIGNMENT for goods the supplier keeps until they sell.
   *
   * @throws ApiException 400 {@code PURCHASE_OWNERSHIP_INVALID} for an ownership nobody defined
   */
  static String ownershipOf(String ownership) {
    if (ownership == null || ownership.isBlank()) return Domain.PO_OWNERSHIP_OWNED;
    String code = ownership.trim().toUpperCase(java.util.Locale.ROOT);
    if (!Domain.PO_OWNERSHIP_OWNED.equals(code) && !Domain.PO_OWNERSHIP_CONSIGNMENT.equals(code)) {
      throw ApiException.badRequest(
          "PURCHASE_OWNERSHIP_INVALID", "ownership must be OWNED or CONSIGNMENT; got " + ownership);
    }
    return code;
  }

  /**
   * DUTY_PAID when unsaid; DUTY_SUSPENDED for excise goods arriving into bond.
   *
   * @throws ApiException 400 {@code PURCHASE_DUTY_STATUS_INVALID} for a status nobody defined
   */
  static String dutyStatusOf(String dutyStatus) {
    if (dutyStatus == null || dutyStatus.isBlank()) return Domain.PO_DUTY_PAID;
    String code = dutyStatus.trim().toUpperCase(java.util.Locale.ROOT);
    if (!Domain.PO_DUTY_PAID.equals(code) && !Domain.PO_DUTY_SUSPENDED.equals(code)) {
      throw ApiException.badRequest(
          "PURCHASE_DUTY_STATUS_INVALID",
          "dutyStatus must be DUTY_PAID or DUTY_SUSPENDED; got " + dutyStatus);
    }
    return code;
  }

  /** The order's price per variant — the first line's, where a variant appears twice. */
  private java.util.Map<UUID, BigDecimal> unitPriceByVariant(UUID tenantId, PurchaseOrder po) {
    var priceByVariant = new java.util.HashMap<UUID, BigDecimal>();
    for (PurchaseOrderLine l : repo.findPurchaseOrderLines(tenantId, po.id())) {
      priceByVariant.putIfAbsent(l.variantId(), l.unitPrice());
    }
    return priceByVariant;
  }

  /**
   * Lists the deliveries booked against a purchase order.
   *
   * @param ctx caller context; supplies the tenant
   * @param poId the purchase order whose receipts to list
   * @return the goods receipts
   * @throws ApiException {@code PURCHASE_PO_NOT_FOUND} (404) when the order does not exist in this
   *     tenant
   */
  public List<GoodsReceipt> listGoodsReceipts(TenantContext ctx, UUID poId) {
    getPurchaseOrder(ctx, poId);
    return repo.findGoodsReceiptsByPo(ctx.requireTenantId(), poId);
  }

  // ── Return to vendor and debit note (07.8) ────────────────────────────────────

  /** The roles that may send goods back: warehouse and management, not the till. */
  private static final String[] RETURN_ROLES = {
    "PLATFORM_ADMIN", "OWNER", "MANAGER", "STOREKEEPER"
  };

  /**
   * Sends goods back to the supplier against a received purchase order and raises the debit note
   * for their value at the order's own prices (07.8) — the reverse SJ-D3 named.
   *
   * <p>The store is the order's, never the request's: goods go back from where they were delivered.
   * Each line is priced from the order's line for that variant, with VAT at the order's VAT code
   * the way the order's own totals are, so the debit note and the invoice it offsets agree to the
   * penny. What can go back is what was received less what already went back — the repository
   * enforces that under the order's lock. The purchase order's status is untouched: what was
   * received was received, and the three-way match still compares the invoice to it; the debit note
   * is the offset.
   *
   * <p>Stock leaves through {@code ReturnedToVendor}, which inventory-svc consumes; on-hand at the
   * store is checked first when inventory-svc can be reached, so a return for goods already sold is
   * refused here rather than half-applied there.
   *
   * @param req what is going back and why
   * @param ctx the caller; a warehouse or management role, assigned to the order's store
   * @param idempotencyKey the caller's replay guard
   * @return the return, numbered
   * @throws ApiException {@code PURCHASE_RTV_REASON_UNKNOWN} (400); {@code PURCHASE_RTV_EMPTY}
   *     (400); {@code PURCHASE_PO_NOT_FOUND} (404); {@code PURCHASE_RTV_NOTHING_RECEIVED} (409);
   *     {@code PURCHASE_RTV_NOT_ON_ORDER}, {@code PURCHASE_RTV_OVER_RETURN}, {@code
   *     PURCHASE_RTV_INSUFFICIENT_STOCK} (422)
   */
  public Domain.VendorReturn raiseVendorReturn(
      RaiseVendorReturnRequest req, TenantContext ctx, String idempotencyKey) {
    ctx.requireAnyRole(RETURN_ROLES);
    UUID tenantId = ctx.requireTenantId();
    String reason =
        req.reason() == null ? "" : req.reason().trim().toUpperCase(java.util.Locale.ROOT);
    if (!Domain.RETURN_REASONS.contains(reason)) {
      throw ApiException.badRequest(
          "PURCHASE_RTV_REASON_UNKNOWN",
          "reason must be one of "
              + new java.util.TreeSet<>(Domain.RETURN_REASONS)
              + " — got: "
              + req.reason());
    }
    if (req.lines() == null || req.lines().isEmpty()) {
      throw ApiException.badRequest(
          "PURCHASE_RTV_EMPTY", "a return must send at least one line back");
    }
    PurchaseOrder po = getPurchaseOrder(ctx, req.poId());
    ctx.requireStoreAccess(po.storeId());
    if (!Domain.PO_PARTIALLY_RECEIVED.equals(po.status())
        && !Domain.PO_RECEIVED.equals(po.status())
        && !Domain.PO_CLOSED.equals(po.status())) {
      throw ApiException.conflict(
          "PURCHASE_RTV_NOTHING_RECEIVED",
          "goods can only go back against an order something was received on — this one is "
              + po.status());
    }
    // The order's price per variant: what the debit note charges back.
    java.util.Map<UUID, PurchaseOrderLine> priced = new java.util.HashMap<>();
    for (PurchaseOrderLine l : repo.findPurchaseOrderLines(tenantId, po.id())) {
      priced.putIfAbsent(l.variantId(), l);
    }
    java.util.Map<String, BigDecimal> vatRates = pricing.findVatRates(tenantId);
    UUID returnId = Ids.newId();
    List<Domain.VendorReturnLine> lines = new ArrayList<>();
    BigDecimal net = BigDecimal.ZERO;
    BigDecimal vat = BigDecimal.ZERO;
    for (var lr : req.lines()) {
      PurchaseOrderLine ordered = priced.get(lr.variantId());
      if (ordered == null) {
        throw ApiException.unprocessable(
            "PURCHASE_RTV_NOT_ON_ORDER",
            "variant " + lr.variantId() + " is not on this purchase order");
      }
      BigDecimal lineNet = Money.round(ordered.unitPrice().multiply(lr.qty()), po.currency());
      BigDecimal rate =
          vatRates.getOrDefault(
              ordered.vatCode().toUpperCase(java.util.Locale.ROOT), BigDecimal.ZERO);
      vat = vat.add(Money.round(lineNet.multiply(rate), po.currency()));
      net = net.add(lineNet);
      lines.add(
          new Domain.VendorReturnLine(
              Ids.newId(),
              tenantId,
              returnId,
              lr.variantId(),
              lr.qty(),
              ordered.unitPrice(),
              ordered.vatCode(),
              lineNet,
              Instant.now()));
    }
    // The order's own ceiling first — received less already returned — so a return that is both
    // more than the order allows and more than the shelf holds is refused for the reason that
    // matters; the repository re-checks the same ceiling under the order's lock. The live run
    // found the shelf answering first.
    java.util.Map<UUID, BigDecimal> returnable = new java.util.HashMap<>();
    for (Domain.PurchaseOrderLineProgress p : repo.findLineProgress(tenantId, po.id())) {
      returnable.put(p.variantId(), p.qtyReceived().subtract(p.qtyReturned()));
    }
    for (Domain.VendorReturnLine l : lines) {
      BigDecimal ceiling = returnable.getOrDefault(l.variantId(), BigDecimal.ZERO);
      if (l.qty().compareTo(ceiling) > 0) {
        throw ApiException.unprocessable(
            "PURCHASE_RTV_OVER_RETURN",
            "variant "
                + l.variantId()
                + ": "
                + l.qty().toPlainString()
                + " to return against "
                + ceiling.toPlainString()
                + " received and not yet returned");
      }
    }
    // What is on the shelf, when inventory-svc can say: a return of goods already sold is refused
    // here, where the buyer can see it, rather than skipped line by line in the consumer.
    for (Domain.VendorReturnLine l : lines) {
      inventory
          .onHand(tenantId, po.storeId(), l.variantId())
          .filter(onHand -> onHand.compareTo(l.qty()) < 0)
          .ifPresent(
              onHand -> {
                throw ApiException.unprocessable(
                    "PURCHASE_RTV_INSUFFICIENT_STOCK",
                    "variant "
                        + l.variantId()
                        + ": "
                        + l.qty().toPlainString()
                        + " to return but "
                        + onHand.toPlainString()
                        + " on hand at the store");
              });
    }
    Domain.VendorReturn draft =
        new Domain.VendorReturn(
            returnId,
            tenantId,
            po.id(),
            po.supplierId(),
            po.storeId(),
            Domain.RETURN_RAISED,
            reason,
            trimmed(req.notes()),
            po.currency(),
            net,
            vat,
            net.add(vat),
            null,
            Instant.now(),
            ctx.userId(),
            null,
            null,
            null,
            null,
            null,
            idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey : null);
    return repo.createVendorReturn(
        draft,
        lines,
        Events.returnedToVendor(tenantId, returnId, po.storeId(), po.id(), po.supplierId(), lines));
  }

  /**
   * @param ctx the caller
   * @param poId an order, or null for every return in the tenant
   * @return the returns, newest first
   * @throws ApiException {@code PURCHASE_PO_NOT_FOUND} (404) when the order is not this tenant's
   */
  public List<Domain.VendorReturn> listVendorReturns(TenantContext ctx, UUID poId) {
    if (poId != null) {
      getPurchaseOrder(ctx, poId);
    }
    return repo.findVendorReturns(ctx.requireTenantId(), poId);
  }

  /**
   * @throws ApiException {@code PURCHASE_RTV_NOT_FOUND} (404) when the return is not this tenant's
   */
  public Domain.VendorReturn getVendorReturn(TenantContext ctx, UUID id) {
    return repo.findVendorReturn(ctx.requireTenantId(), id)
        .orElseThrow(
            () -> ApiException.notFound("PURCHASE_RTV_NOT_FOUND", "No such return to vendor"));
  }

  /** The lines of a return this tenant owns. */
  public List<Domain.VendorReturnLine> vendorReturnLines(TenantContext ctx, UUID id) {
    getVendorReturn(ctx, id);
    return repo.findVendorReturnLines(ctx.requireTenantId(), id);
  }

  /**
   * Records the supplier's credit note against a return, closing it. Management only: matching
   * money received to money owed is a finance decision, not a warehouse one.
   *
   * @throws ApiException {@code PURCHASE_RTV_NOT_FOUND} (404); {@code
   *     PURCHASE_RTV_ALREADY_CREDITED} (409); {@code PURCHASE_CREDIT_DATE_INVALID} (400)
   */
  public Domain.VendorReturn recordCreditNote(
      TenantContext ctx, UUID id, RecordCreditNoteRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
    Domain.VendorReturn ret = getVendorReturn(ctx, id);
    LocalDate date = Parsing.date(req.creditNoteDate(), "creditNoteDate");
    BigDecimal amount =
        Money.round(req.amount() == null ? ret.grossAmount() : req.amount(), ret.currency());
    requireOpenPeriod(ctx.requireTenantId(), ret.storeId(), date);
    boolean credited =
        repo.creditVendorReturn(
            ctx.requireTenantId(),
            id,
            req.creditNoteNumber().trim(),
            date,
            amount,
            ctx.userId(),
            creditNotePosting(ctx.requireTenantId(), ret, date, amount));
    if (!credited) {
      throw ApiException.conflict(
          "PURCHASE_RTV_ALREADY_CREDITED",
          "this return already carries credit note " + ret.creditNoteNumber());
    }
    return getVendorReturn(ctx, id);
  }

  /**
   * Dr Creditors for the credit, Cr VAT input for its VAT share and Cr Stock for the rest. The VAT
   * share is the return's own VAT ratio applied to the amount credited, so a partial credit splits
   * the way the debit note did. Empty when nothing was credited.
   */
  private List<NominalLedgerEntry> creditNotePosting(
      UUID tenantId, Domain.VendorReturn ret, LocalDate date, BigDecimal amount) {
    if (amount.signum() <= 0) return List.of();
    BigDecimal vat = BigDecimal.ZERO;
    if (ret.grossAmount() != null && ret.grossAmount().signum() > 0 && ret.vatAmount() != null) {
      vat =
          Money.round(
              amount.multiply(ret.vatAmount()).divide(ret.grossAmount(), 10, RoundingMode.HALF_UP),
              ret.currency());
    }
    BigDecimal net = amount.subtract(vat);
    return LedgerPosting.of(
            tenantId,
            date,
            "Supplier credit note against " + ret.debitNoteNumber(),
            Domain.SOURCE_CREDIT_NOTE,
            ret.id(),
            ret.storeId())
        .debit(Domain.CODE_CREDITORS, Domain.NAME_CREDITORS, amount)
        .credit(Domain.CODE_VAT_INPUT, Domain.NAME_VAT_INPUT, vat)
        .credit(stockCodeFor(tenantId, ret.storeId()), Domain.NAME_STOCK, net)
        .build();
  }

  // ── Intercompany Invoices (Gap #20) ───────────────────────────────────────────

  /**
   * Raises an AR invoice for the sending store and an AP invoice for the receiving store
   * atomically. Posts the corresponding FRS 102 / UK GAAP double-entry nominal ledger entries.
   *
   * <p>BACS payment due date = invoice_date + 30 days (UK standard trade terms).
   *
   * <p>Group VAT: if vatDisregarded=true (HMRC VAT Notice 700/2 — same VAT group), no VAT nominal
   * entries are posted and vat_disregarded is set on both records.
   *
   * <p>Transfer pricing: caller provides net_amount which should reflect arm's length pricing per
   * HMRC INTM (typically: cost price of the transferred goods).
   */
  public List<IntercompanyInvoice> raiseIntercompanyInvoices(
      RaiseIntercompanyInvoiceRequest req, TenantContext ctx) {
    UUID tenantId = ctx.requireTenantId();
    UUID fromStore = Ids.parse(req.fromStoreId());
    UUID toStore = Ids.parse(req.toStoreId());
    if (fromStore.equals(toStore))
      throw ApiException.badRequest(
          "PURCHASE_IC_SAME_STORE", "from and to store must be different");

    UUID transferRef = req.transferRef() != null ? Ids.parse(req.transferRef()) : null;
    String vatCode =
        req.vatCode() != null ? req.vatCode().toUpperCase(java.util.Locale.ROOT) : "T1";
    // Intercompany invoicing is store-to-store inside one tenant, so the tenant's own currency is
    // the right default here — unlike a purchase order, where the counterparty is an outside
    // supplier who may invoice in their own (SJ-D23/SJ-D24).
    String currency =
        req.currency() != null
            ? Money.requireIso4217(req.currency())
            : resolveTenantCurrency(tenantId);
    LocalDate today = LocalDate.now();
    LocalDate dueDate = today.plusDays(BACS_TERMS_DAYS);

    UUID arId = Ids.newId();
    UUID apId = Ids.newId();

    IntercompanyInvoice ar =
        new IntercompanyInvoice(
            arId,
            tenantId,
            Domain.INV_AR,
            fromStore,
            toStore,
            transferRef,
            req.netAmount(),
            req.vatAmount(),
            req.grossAmount(),
            vatCode,
            req.vatDisregarded(),
            Domain.INV_RAISED,
            today,
            dueDate,
            currency,
            Instant.now());

    IntercompanyInvoice ap =
        new IntercompanyInvoice(
            apId,
            tenantId,
            Domain.INV_AP,
            fromStore,
            toStore,
            transferRef,
            req.netAmount(),
            req.vatAmount(),
            req.grossAmount(),
            vatCode,
            req.vatDisregarded(),
            Domain.INV_RAISED,
            today,
            dueDate,
            currency,
            Instant.now());

    List<NominalLedgerEntry> arEntries =
        asJournal(buildArEntries(tenantId, arId, req, today), Domain.SOURCE_INTERCOMPANY);
    List<NominalLedgerEntry> apEntries =
        asJournal(buildApEntries(tenantId, apId, req, today), Domain.SOURCE_INTERCOMPANY);

    return repo.createIntercompanyInvoicePair(
        ar,
        arEntries,
        Events.intercompanyInvoiceRaised(tenantId, arId),
        ap,
        apEntries,
        Events.intercompanyInvoiceRaised(tenantId, apId));
  }

  private List<NominalLedgerEntry> buildArEntries(
      UUID tenantId, UUID arId, RaiseIntercompanyInvoiceRequest req, LocalDate today) {
    String desc = "Intercompany AR invoice " + Handle.of(arId);
    List<NominalLedgerEntry> entries = new ArrayList<>();
    BigDecimal gross = req.grossAmount();
    BigDecimal net = req.netAmount();
    BigDecimal vat = req.vatAmount();

    // DR 1100 Debtors (gross amount owed to sending store)
    entries.add(
        ledgerEntry(
            tenantId,
            today,
            Domain.CODE_DEBTORS,
            Domain.NAME_DEBTORS,
            gross,
            BigDecimal.ZERO,
            desc,
            arId));
    if (!req.vatDisregarded() && vat.compareTo(BigDecimal.ZERO) > 0) {
      // CR 2200 VAT Output
      entries.add(
          ledgerEntry(
              tenantId,
              today,
              Domain.CODE_VAT_OUTPUT,
              Domain.NAME_VAT_OUTPUT,
              BigDecimal.ZERO,
              vat,
              desc,
              arId));
      // CR 4000 Intercompany Sales (net only)
      entries.add(
          ledgerEntry(
              tenantId,
              today,
              Domain.CODE_IC_SALES,
              Domain.NAME_IC_SALES,
              BigDecimal.ZERO,
              net,
              desc,
              arId));
    } else {
      // CR 4000 Intercompany Sales (gross = net when VAT disregarded)
      entries.add(
          ledgerEntry(
              tenantId,
              today,
              Domain.CODE_IC_SALES,
              Domain.NAME_IC_SALES,
              BigDecimal.ZERO,
              gross,
              desc,
              arId));
    }
    return entries;
  }

  private List<NominalLedgerEntry> buildApEntries(
      UUID tenantId, UUID apId, RaiseIntercompanyInvoiceRequest req, LocalDate today) {
    String desc = "Intercompany AP invoice " + Handle.of(apId);
    List<NominalLedgerEntry> entries = new ArrayList<>();
    BigDecimal gross = req.grossAmount();
    BigDecimal net = req.netAmount();
    BigDecimal vat = req.vatAmount();

    if (!req.vatDisregarded() && vat.compareTo(BigDecimal.ZERO) > 0) {
      // DR 5000 Purchases (net)
      entries.add(
          ledgerEntry(
              tenantId,
              today,
              Domain.CODE_IC_PURCHASES,
              Domain.NAME_IC_PURCHASES,
              net,
              BigDecimal.ZERO,
              desc,
              apId));
      // DR 2201 VAT Input
      entries.add(
          ledgerEntry(
              tenantId,
              today,
              Domain.CODE_VAT_INPUT,
              Domain.NAME_VAT_INPUT,
              vat,
              BigDecimal.ZERO,
              desc,
              apId));
    } else {
      // DR 5000 Purchases (gross = net when VAT disregarded)
      entries.add(
          ledgerEntry(
              tenantId,
              today,
              Domain.CODE_IC_PURCHASES,
              Domain.NAME_IC_PURCHASES,
              gross,
              BigDecimal.ZERO,
              desc,
              apId));
    }
    // CR 2100 Creditors (gross)
    entries.add(
        ledgerEntry(
            tenantId,
            today,
            Domain.CODE_CREDITORS,
            Domain.NAME_CREDITORS,
            BigDecimal.ZERO,
            gross,
            desc,
            apId));
    return entries;
  }

  /**
   * Reads one intercompany invoice.
   *
   * @param ctx caller context; supplies the tenant
   * @param id the invoice to read
   * @return the invoice, either the AR or the AP side of a pair
   * @throws ApiException {@code PURCHASE_INVOICE_NOT_FOUND} (404) when it does not exist in this
   *     tenant
   */
  public IntercompanyInvoice getIntercompanyInvoice(TenantContext ctx, UUID id) {
    return repo.findIntercompanyInvoice(ctx.requireTenantId(), id)
        .orElseThrow(
            () -> ApiException.notFound("PURCHASE_INVOICE_NOT_FOUND", "Invoice not found: " + id));
  }

  /**
   * Lists the tenant's intercompany invoices, both AR and AP sides.
   *
   * @param ctx caller context; supplies the tenant
   * @param limit maximum rows; the caller is expected to have clamped this
   * @return the invoices
   */
  public List<IntercompanyInvoice> listIntercompanyInvoices(TenantContext ctx, int limit) {
    return repo.findIntercompanyInvoices(ctx.requireTenantId(), limit);
  }

  /**
   * Settles an intercompany invoice, posting the matching nominal-ledger entries.
   *
   * <p>Which entries depends on the side: an AR invoice debits Bank and credits Debtors, an AP one
   * the mirror image, so the two books stay in agreement.
   *
   * @param ctx caller context; supplies the tenant
   * @param id the invoice to settle
   * @throws ApiException {@code PURCHASE_INVOICE_NOT_FOUND} (404) when it does not exist in this
   *     tenant
   */
  public void settleIntercompanyInvoice(TenantContext ctx, UUID id) {
    IntercompanyInvoice inv = getIntercompanyInvoice(ctx, id);
    LocalDate today = LocalDate.now();
    String desc = "Settlement of intercompany invoice " + Handle.of(id);
    List<NominalLedgerEntry> settlements = new ArrayList<>();

    if (Domain.INV_AR.equals(inv.invoiceType())) {
      // DR 1200 Bank / CR 1100 Debtors
      settlements.add(
          ledgerEntry(
              ctx.requireTenantId(),
              today,
              Domain.CODE_BANK,
              Domain.NAME_BANK,
              inv.grossAmount(),
              BigDecimal.ZERO,
              desc,
              id));
      settlements.add(
          ledgerEntry(
              ctx.requireTenantId(),
              today,
              Domain.CODE_DEBTORS,
              Domain.NAME_DEBTORS,
              BigDecimal.ZERO,
              inv.grossAmount(),
              desc,
              id));
    } else {
      // DR 2100 Creditors / CR 1200 Bank
      settlements.add(
          ledgerEntry(
              ctx.requireTenantId(),
              today,
              Domain.CODE_CREDITORS,
              Domain.NAME_CREDITORS,
              inv.grossAmount(),
              BigDecimal.ZERO,
              desc,
              id));
      settlements.add(
          ledgerEntry(
              ctx.requireTenantId(),
              today,
              Domain.CODE_BANK,
              Domain.NAME_BANK,
              BigDecimal.ZERO,
              inv.grossAmount(),
              desc,
              id));
    }
    repo.settleIntercompanyInvoice(
        ctx.requireTenantId(), id, asJournal(settlements, Domain.SOURCE_SETTLEMENT));
  }

  // ── Nominal Ledger ────────────────────────────────────────────────────────────

  /** Cursor-paginated nominal ledger. Cursor wraps {@code entryDate|createdAt|id}. */
  public com.storeql.web.Cursor.Page<NominalLedgerEntry> getNominalLedger(
      TenantContext ctx,
      String nominalCode,
      String fromStr,
      String toStr,
      String after,
      int limit) {
    LocalDate from = fromStr != null ? Parsing.date(fromStr, "from") : null;
    LocalDate to = toStr != null ? Parsing.date(toStr, "to") : null;
    String rawKey = com.storeql.web.Cursor.decode(after);
    LocalDate afterEntryDate = null;
    java.time.Instant afterCreatedAt = null;
    UUID afterId = null;
    if (rawKey != null) {
      String[] parts = rawKey.split("\\|", 3);
      if (parts.length != 3) {
        throw new ApiException(
            400, "INVALID_CURSOR", "Malformed pagination cursor", List.of(), null);
      }
      try {
        afterEntryDate = LocalDate.parse(parts[0]);
        afterCreatedAt = java.time.Instant.parse(parts[1]);
        afterId = Ids.parse(parts[2]);
      } catch (RuntimeException e) {
        throw new ApiException(400, "INVALID_CURSOR", "Malformed pagination cursor", List.of(), e);
      }
    }
    List<NominalLedgerEntry> rows =
        repo.findNominalLedger(
            ctx.requireTenantId(),
            nominalCode,
            from,
            to,
            afterEntryDate,
            afterCreatedAt,
            afterId,
            limit + 1);
    return com.storeql.web.Cursor.page(
        rows, limit, e -> e.entryDate() + "|" + e.createdAt() + "|" + e.id());
  }

  // ── Helpers ───────────────────────────────────────────────────────────────────

  private static NominalLedgerEntry ledgerEntry(
      UUID tenantId,
      LocalDate date,
      String code,
      String name,
      BigDecimal debit,
      BigDecimal credit,
      String desc,
      UUID sourceRef) {
    return new NominalLedgerEntry(
        Ids.newId(),
        tenantId,
        date,
        code,
        name,
        debit,
        credit,
        desc,
        sourceRef,
        Instant.now(),
        null,
        null,
        null);
  }

  /** Stamps one journal id and a source type onto lines built as a set. */
  private static List<NominalLedgerEntry> asJournal(
      List<NominalLedgerEntry> lines, String sourceType) {
    UUID journal = Ids.newId();
    return lines.stream()
        .map(
            e ->
                new NominalLedgerEntry(
                    e.id(),
                    e.tenantId(),
                    e.entryDate(),
                    e.nominalCode(),
                    e.nominalName(),
                    e.debit(),
                    e.credit(),
                    e.description(),
                    e.sourceRef(),
                    e.createdAt(),
                    journal,
                    sourceType,
                    e.storeId()))
        .toList();
  }

  // ── The accounting seam: postings, period control, journals, trial balance ──

  private static LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  /**
   * Refuses a posting into a month finance has closed (04.7).
   *
   * <p>Periods live in inventory-svc beside the costing they freeze; this reads them through the
   * client and applies {@link PeriodControl}'s rule. Unreachable is treated as open, and logged
   * there — see the client for why.
   *
   * @throws ApiException 409 {@code PURCHASE_PERIOD_CLOSED}
   */
  void requireOpenPeriod(UUID tenantId, UUID storeId, LocalDate date) {
    if (storeId == null) return;
    var periods = inventory.accountingPeriods(tenantId, storeId);
    if (periods.isPresent() && PeriodControl.closedOn(periods.get(), date)) {
      throw ApiException.conflict(
          "PURCHASE_PERIOD_CLOSED",
          "the accounting period covering " + date + " is closed for this store");
    }
  }

  /** The nominal code a store's stock posts to: its GL mapping, or the default (17.3). */
  String stockCodeFor(UUID tenantId, UUID storeId) {
    return inventory.storeNominalCode(tenantId, storeId).orElse(Domain.CODE_STOCK);
  }

  /**
   * Dr Stock / Cr GR/IR for the goods received, at the order's own prices. Empty when the receipt
   * values to nothing — an order priced at zero is recorded, not posted.
   */
  private List<NominalLedgerEntry> receiptPosting(
      PurchaseOrder po,
      GoodsReceipt gr,
      List<GoodsReceiptLine> lines,
      java.util.Map<UUID, BigDecimal> priceByVariant) {
    // Consignment stock is the supplier's until it sells: no asset, and nothing owed at the door.
    if (po.consigned()) return List.of();
    BigDecimal value = BigDecimal.ZERO;
    for (GoodsReceiptLine l : lines) {
      BigDecimal price = priceByVariant.get(l.variantId());
      if (price != null) {
        value = value.add(Money.round(l.qtyReceived().multiply(price), po.currency()));
      }
    }
    value = Money.round(value, po.currency());
    if (value.signum() <= 0) return List.of();
    return LedgerPosting.of(
            po.tenantId(),
            today(),
            "Goods received against " + po.reference(),
            Domain.SOURCE_GOODS_RECEIPT,
            gr.id(),
            gr.storeId())
        .debit(stockCodeFor(po.tenantId(), gr.storeId()), Domain.NAME_STOCK, value)
        .credit(Domain.CODE_GRIR, Domain.NAME_GRIR, value)
        .build();
  }

  /** Dr GR/IR net, Dr VAT input, Cr Creditors gross: the liability, the moment it exists. */
  private static List<NominalLedgerEntry> invoicePosting(
      UUID tenantId, Domain.SupplierInvoice inv, UUID storeId) {
    if (inv.grossAmount().signum() <= 0) return List.of();
    return LedgerPosting.of(
            tenantId,
            inv.invoiceDate(),
            "Supplier invoice " + inv.invoiceNumber(),
            Domain.SOURCE_SUPPLIER_INVOICE,
            inv.id(),
            storeId)
        .debit(Domain.CODE_GRIR, Domain.NAME_GRIR, inv.netAmount())
        .debit(Domain.CODE_VAT_INPUT, Domain.NAME_VAT_INPUT, inv.vatAmount())
        .credit(Domain.CODE_CREDITORS, Domain.NAME_CREDITORS, inv.grossAmount())
        .build();
  }

  /**
   * Decides a flagged invoice (07.7): APPROVE releases it for payment; REJECT reverses its posting,
   * takes it out of the VAT return and frees the quantities it billed, so the supplier's corrected
   * invoice matches cleanly. Management only, and the reason is kept — a decision about money with
   * no reason is the thing an auditor asks about first.
   *
   * @throws ApiException 400 {@code PURCHASE_RESOLUTION_UNKNOWN} for an action that is neither; 404
   *     when the invoice does not exist in this tenant; 409 {@code PURCHASE_INVOICE_NOT_FLAGGED}
   *     when it is not awaiting a decision; 409 {@code PURCHASE_INVOICE_ALREADY_RESOLVED} when
   *     another decision landed first; 409 {@code PURCHASE_PERIOD_CLOSED} when the reversal would
   *     land in a closed month
   */
  public Domain.SupplierInvoice resolveSupplierInvoice(
      TenantContext ctx, UUID id, ResolveSupplierInvoiceRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
    ctx.requirePermission(Permissions.PURCHASING_INVOICES_DECIDE);
    UUID tenantId = ctx.requireTenantId();
    String action = req.action().trim().toUpperCase(java.util.Locale.ROOT);
    boolean approve = "APPROVE".equals(action);
    if (!approve && !"REJECT".equals(action)) {
      throw ApiException.badRequest(
          "PURCHASE_RESOLUTION_UNKNOWN", "action must be APPROVE or REJECT, not " + req.action());
    }
    Domain.SupplierInvoice inv = getSupplierInvoice(ctx, id);
    if (!Domain.INVOICE_FLAGGED.equals(inv.status())) {
      throw ApiException.conflict(
          "PURCHASE_INVOICE_NOT_FLAGGED",
          "only a FLAGGED invoice can be decided — this one is " + inv.status());
    }
    List<NominalLedgerEntry> reversal = List.of();
    com.storeql.service.OutboxRow event = null;
    if (!approve) {
      PurchaseOrder po = getPurchaseOrder(ctx, inv.poId());
      requireOpenPeriod(tenantId, po.storeId(), today());
      List<NominalLedgerEntry> posted =
          repo.findPostingFor(tenantId, Domain.SOURCE_SUPPLIER_INVOICE, inv.id());
      if (!posted.isEmpty()) {
        reversal =
            LedgerPosting.reversalOf(
                    posted,
                    today(),
                    "Rejected supplier invoice " + inv.invoiceNumber() + ": " + req.reason().trim(),
                    Domain.SOURCE_INVOICE_REVERSAL)
                .build();
      }
      event = Events.supplierInvoiceRejected(tenantId, inv, Ids.derived(inv.id(), "rejected"));
    }
    boolean decided =
        repo.resolveSupplierInvoice(
            tenantId,
            id,
            approve ? Domain.INVOICE_APPROVED : Domain.INVOICE_REJECTED,
            ctx.userId(),
            req.reason().trim(),
            reversal,
            event);
    if (!decided) {
      throw ApiException.conflict(
          "PURCHASE_INVOICE_ALREADY_RESOLVED", "this invoice has already been decided");
    }
    return getSupplierInvoice(ctx, id);
  }

  /**
   * Posts a manual journal (17.1). Management only. The lines must balance and each must carry a
   * debit or a credit, never both; a store, when named, must be one the caller may operate in and
   * its period for the date must be open.
   *
   * @throws ApiException 400 {@code PURCHASE_JOURNAL_LINE_INVALID}; 422 {@code
   *     PURCHASE_JOURNAL_UNBALANCED}; 403 {@code STORE_ACCESS_DENIED}; 409 {@code
   *     PURCHASE_PERIOD_CLOSED}
   */
  public Domain.Journal postJournal(TenantContext ctx, PostJournalRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
    ctx.requirePermission(Permissions.FINANCE_JOURNAL);
    UUID tenantId = ctx.requireTenantId();
    LocalDate date = Parsing.date(req.entryDate(), "entryDate");
    UUID storeId = Parsing.optionalUuid(req.storeId(), "storeId");
    if (storeId != null) ctx.requireStoreAccess(storeId);
    LedgerPosting posting;
    try {
      posting =
          LedgerPosting.of(tenantId, date, req.description(), Domain.SOURCE_JOURNAL, null, storeId);
      for (var l : req.lines()) {
        posting.line(l.nominalCode().trim(), l.nominalName(), l.debit(), l.credit());
      }
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, "PURCHASE_JOURNAL_LINE_INVALID", e.getMessage(), List.of(), e);
    }
    if (posting.size() < 2) {
      throw ApiException.badRequest(
          "PURCHASE_JOURNAL_LINE_INVALID", "a journal needs at least two lines with an amount");
    }
    if (!posting.balanced()) {
      throw ApiException.unprocessable(
          "PURCHASE_JOURNAL_UNBALANCED",
          "debits "
              + posting.totalDebit().toPlainString()
              + " do not equal credits "
              + posting.totalCredit().toPlainString());
    }
    requireOpenPeriod(tenantId, storeId, date);
    List<NominalLedgerEntry> lines = posting.build();
    repo.postJournal(lines);
    return journalOf(lines);
  }

  /**
   * Reads one journal whole. Management only: the ledger's lines are readable by any member of
   * staff, but a journal is a finance document and its reader is finance.
   *
   * @throws ApiException 404 {@code PURCHASE_JOURNAL_NOT_FOUND}
   */
  public Domain.Journal getJournal(TenantContext ctx, UUID journalId) {
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
    List<NominalLedgerEntry> lines = repo.findJournal(ctx.requireTenantId(), journalId);
    if (lines.isEmpty()) {
      throw ApiException.notFound("PURCHASE_JOURNAL_NOT_FOUND", "Journal not found: " + journalId);
    }
    return journalOf(lines);
  }

  private static Domain.Journal journalOf(List<NominalLedgerEntry> lines) {
    NominalLedgerEntry first = lines.get(0);
    return new Domain.Journal(
        first.journalId(),
        first.entryDate(),
        first.description(),
        first.sourceType(),
        first.sourceRef(),
        first.storeId(),
        lines);
  }

  /**
   * The trial balance: every code's debits, credits and balance over a range (17.1). Management
   * only.
   *
   * @throws ApiException 400 {@code PURCHASE_INVALID_PERIOD} when the range ends before it starts
   */
  public List<Domain.TrialBalanceRow> trialBalance(
      TenantContext ctx, String fromStr, String toStr, String storeIdStr) {
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
    LocalDate from = fromStr != null ? Parsing.date(fromStr, "from") : null;
    LocalDate to = toStr != null ? Parsing.date(toStr, "to") : null;
    if (from != null && to != null && to.isBefore(from)) {
      throw ApiException.badRequest("PURCHASE_INVALID_PERIOD", "to must not be before from");
    }
    UUID storeId = Parsing.optionalUuid(storeIdStr, "storeId");
    return repo.findTrialBalance(ctx.requireTenantId(), from, to, storeId);
  }
}
