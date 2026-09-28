package com.storeql.purchase.service;

import com.storeql.ids.Ids;
import com.storeql.purchase.client.PricingClient;
import com.storeql.purchase.domain.Domain;
import com.storeql.purchase.domain.Domain.PurchaseOrder;
import com.storeql.purchase.domain.Domain.PurchaseOrderLine;
import com.storeql.purchase.domain.Domain.Supplier;
import com.storeql.purchase.domain.Money;
import com.storeql.purchase.domain.Rfq;
import com.storeql.purchase.domain.Rfq.Award;
import com.storeql.purchase.domain.Rfq.Bid;
import com.storeql.purchase.domain.Rfq.Detail;
import com.storeql.purchase.domain.Rfq.Header;
import com.storeql.purchase.domain.Rfq.Line;
import com.storeql.purchase.domain.SupplierScorecard;
import com.storeql.purchase.domain.Totals;
import com.storeql.purchase.dto.RfqDtos.CreateRfqRequest;
import com.storeql.purchase.dto.RfqDtos.RfqAwardLineRequest;
import com.storeql.purchase.dto.RfqDtos.RfqAwardRequest;
import com.storeql.purchase.dto.RfqDtos.RfqLineRequest;
import com.storeql.purchase.dto.RfqDtos.RfqQuoteLineRequest;
import com.storeql.purchase.dto.RfqDtos.RfqQuoteRequest;
import com.storeql.purchase.repo.PurchaseRepository;
import com.storeql.purchase.repo.RfqRepository;
import com.storeql.service.FxRates;
import com.storeql.service.TenantProfiles;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * RFQ and sourcing: a buyer asks several suppliers to quote for the same lines, records what each
 * says, reads the quotes side by side in the business's own money with each supplier's record
 * beside them, and awards the lines — which raises a draft order per supplier at the quoted prices,
 * for a person to submit as any draft.
 */
@ApplicationScoped
public class RfqService {

  private static final Logger LOG = System.getLogger(RfqService.class.getName());

  /** Who may ask, record and award: buying is warehouse and management work, not the till. */
  private static final String[] BUYING = {"PLATFORM_ADMIN", "OWNER", "MANAGER", "STOREKEEPER"};

  private static final Set<String> STATUSES =
      Set.of(Rfq.DRAFT, Rfq.ISSUED, Rfq.AWARDED, Rfq.CANCELLED);

  @Inject RfqRepository repo;
  @Inject PurchaseRepository purchases;
  @Inject PricingClient pricing;
  @Inject FxRates fx;
  @Inject TenantProfiles tenants;
  @Inject SupplierPerformanceService performance;

  // ── Raise and read ─────────────────────────────────────────────────────────

  /**
   * @throws ApiException 400 {@code PURCHASE_RFQ_LINES_REQUIRED}, {@code
   *     PURCHASE_RFQ_SUPPLIERS_REQUIRED}, {@code PURCHASE_RFQ_LINE_DUPLICATE}, {@code
   *     PURCHASE_RFQ_SUPPLIER_DUPLICATE}; 404 {@code PURCHASE_SUPPLIER_NOT_FOUND}
   */
  public Detail create(TenantContext ctx, CreateRfqRequest req) {
    ctx.requireAnyRole(BUYING);
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = Parsing.uuid(req.storeId(), "storeId");
    ctx.requireStoreAccess(storeId);
    if (req.lines().isEmpty()) {
      throw ApiException.badRequest(
          "PURCHASE_RFQ_LINES_REQUIRED", "a request names at least one line to quote for");
    }
    if (req.supplierIds().isEmpty()) {
      throw ApiException.badRequest(
          "PURCHASE_RFQ_SUPPLIERS_REQUIRED", "a request names at least one supplier to ask");
    }
    UUID rfqId = Ids.newId();
    List<Line> lines = new ArrayList<>(req.lines().size());
    Set<UUID> variants = new HashSet<>();
    int order = 0;
    for (RfqLineRequest l : req.lines()) {
      UUID variantId = Parsing.uuid(l.variantId(), "lines.variantId");
      if (!variants.add(variantId)) {
        throw ApiException.badRequest(
            "PURCHASE_RFQ_LINE_DUPLICATE", "variant " + variantId + " is asked for twice");
      }
      lines.add(
          new Line(
              Ids.newId(), tenantId, rfqId, variantId, l.qty(), order++, blankToNull(l.notes())));
    }
    List<UUID> suppliers = new ArrayList<>(req.supplierIds().size());
    for (String s : req.supplierIds()) {
      UUID supplierId = Parsing.uuid(s, "supplierIds");
      if (suppliers.contains(supplierId)) {
        throw ApiException.badRequest(
            "PURCHASE_RFQ_SUPPLIER_DUPLICATE", "supplier " + supplierId + " is asked twice");
      }
      if (purchases.findSupplier(tenantId, supplierId).isEmpty()) {
        throw ApiException.notFound(
            "PURCHASE_SUPPLIER_NOT_FOUND", "Supplier not found: " + supplierId);
      }
      suppliers.add(supplierId);
    }
    Header h =
        new Header(
            rfqId,
            tenantId,
            null,
            req.title().trim(),
            storeId,
            Rfq.DRAFT,
            date(req.neededBy(), "neededBy"),
            date(req.closesOn(), "closesOn"),
            blankToNull(req.notes()),
            ctx.userId(),
            Instant.now(),
            null,
            null,
            null,
            null,
            null);
    repo.create(h, lines, suppliers);
    return detail(ctx, rfqId);
  }

  /**
   * @throws ApiException 400 {@code PURCHASE_RFQ_STATUS_INVALID}
   */
  public List<RfqRepository.Summary> list(TenantContext ctx, String status, int limit) {
    String code = null;
    if (status != null && !status.isBlank()) {
      code = status.trim().toUpperCase(Locale.ROOT);
      if (!STATUSES.contains(code)) {
        throw ApiException.badRequest(
            "PURCHASE_RFQ_STATUS_INVALID",
            "status must be DRAFT, ISSUED, AWARDED or CANCELLED; got " + status);
      }
    }
    return repo.list(ctx.requireTenantId(), code, limit);
  }

  /**
   * The request in full, its quotes compared in the business's own money.
   *
   * @throws ApiException 404 {@code PURCHASE_RFQ_NOT_FOUND}
   */
  public Detail detail(TenantContext ctx, UUID id) {
    UUID tenantId = ctx.requireTenantId();
    Header h = require(tenantId, id);
    List<Line> lines = repo.lines(tenantId, id);
    List<Bid> bids = repo.bids(tenantId, id);
    String home = tenants.requireCurrency(tenantId);
    Rfq.Comparison comparison =
        Rfq.compare(
            home,
            lines,
            bids,
            (currency, amount) -> fx.toHome(tenantId, amount, currency).map(c -> c.amount()));
    return new Detail(h, lines, bids, repo.awards(tenantId, id), comparison);
  }

  /** Each supplier's scorecard grade over the last ninety days: price read against the record. */
  public Map<UUID, String> grades(TenantContext ctx) {
    Map<UUID, String> grades = new HashMap<>();
    for (SupplierScorecard.Card c : performance.scorecards(ctx, null, null)) {
      if (c.grade() != null) grades.put(c.supplierId(), c.grade());
    }
    return grades;
  }

  // ── Move ───────────────────────────────────────────────────────────────────

  /**
   * @throws ApiException 409 {@code PURCHASE_RFQ_NOT_DRAFT}
   */
  public Detail issue(TenantContext ctx, UUID id) {
    ctx.requireAnyRole(BUYING);
    UUID tenantId = ctx.requireTenantId();
    Header h = require(tenantId, id);
    if (!Rfq.DRAFT.equals(h.status()) || !repo.issue(tenantId, id)) {
      throw ApiException.conflict(
          "PURCHASE_RFQ_NOT_DRAFT",
          "only a DRAFT request can be issued; this one is " + h.status());
    }
    return detail(ctx, id);
  }

  /**
   * @throws ApiException 409 {@code PURCHASE_RFQ_NOT_ISSUED}; 400 {@code
   *     PURCHASE_RFQ_SUPPLIER_NOT_INVITED}, {@code PURCHASE_RFQ_QUOTE_EMPTY}, {@code
   *     PURCHASE_RFQ_LINE_UNKNOWN}, {@code PURCHASE_RFQ_LINE_DUPLICATE}
   */
  public Detail quote(TenantContext ctx, UUID id, UUID supplierId, RfqQuoteRequest req) {
    ctx.requireAnyRole(BUYING);
    UUID tenantId = ctx.requireTenantId();
    Header h = requireIssued(tenantId, id);
    if (req.lines().isEmpty()) {
      throw ApiException.badRequest("PURCHASE_RFQ_QUOTE_EMPTY", "a quote prices at least one line");
    }
    requireInvited(tenantId, id, supplierId);
    Map<UUID, UUID> lineByVariant = new HashMap<>();
    for (Line l : repo.lines(tenantId, id)) lineByVariant.put(l.variantId(), l.id());
    Map<UUID, BigDecimal> prices = new LinkedHashMap<>();
    for (RfqQuoteLineRequest q : req.lines()) {
      UUID variantId = Parsing.uuid(q.variantId(), "lines.variantId");
      UUID lineId = lineByVariant.get(variantId);
      if (lineId == null) {
        throw ApiException.badRequest(
            "PURCHASE_RFQ_LINE_UNKNOWN",
            "variant " + variantId + " is not on request " + h.reference());
      }
      if (prices.put(lineId, q.unitPrice()) != null) {
        throw ApiException.badRequest(
            "PURCHASE_RFQ_LINE_DUPLICATE", "variant " + variantId + " is priced twice");
      }
    }
    String currency =
        req.currency() != null && !req.currency().isBlank()
            ? Money.requireIso4217(req.currency())
            : purchases.findSupplier(tenantId, supplierId).map(Supplier::currency).orElse(null);
    if (!repo.quote(
        tenantId,
        id,
        supplierId,
        currency,
        req.leadTimeDays(),
        date(req.validUntil(), "validUntil"),
        blankToNull(req.notes()),
        prices)) {
      throw notInvited(supplierId);
    }
    return detail(ctx, id);
  }

  /**
   * @throws ApiException 409 {@code PURCHASE_RFQ_NOT_ISSUED}; 400 {@code
   *     PURCHASE_RFQ_SUPPLIER_NOT_INVITED}
   */
  public Detail decline(TenantContext ctx, UUID id, UUID supplierId) {
    ctx.requireAnyRole(BUYING);
    UUID tenantId = ctx.requireTenantId();
    requireIssued(tenantId, id);
    requireInvited(tenantId, id, supplierId);
    if (!repo.decline(tenantId, id, supplierId)) throw notInvited(supplierId);
    return detail(ctx, id);
  }

  /**
   * Awards the lines and raises one DRAFT order per awarded supplier at the prices they quoted, in
   * their currency, for the day the goods are needed (or the quoted lead time from today when no
   * day was named). The orders are raised first and the award recorded last; an award that loses
   * the race to another leaves drafts a person can cancel, never an award without its orders.
   *
   * @throws ApiException 409 {@code PURCHASE_RFQ_NOT_ISSUED}, {@code PURCHASE_RFQ_NOT_QUOTED}; 400
   *     {@code PURCHASE_RFQ_AWARDS_REQUIRED}, {@code PURCHASE_RFQ_AWARD_DUPLICATE}, {@code
   *     PURCHASE_RFQ_LINE_UNKNOWN}
   */
  public Detail award(TenantContext ctx, UUID id, RfqAwardRequest req) {
    ctx.requireAnyRole(BUYING);
    UUID tenantId = ctx.requireTenantId();
    Header h = requireIssued(tenantId, id);
    if (req.awards().isEmpty()) {
      throw ApiException.badRequest(
          "PURCHASE_RFQ_AWARDS_REQUIRED", "an award gives at least one line to a supplier");
    }
    Map<UUID, Line> lineByVariant = new HashMap<>();
    for (Line l : repo.lines(tenantId, id)) lineByVariant.put(l.variantId(), l);
    Map<UUID, Bid> bidBySupplier = new HashMap<>();
    for (Bid b : repo.bids(tenantId, id)) bidBySupplier.put(b.supplierId(), b);

    // Which lines go to whom, each once and only to a supplier who priced it.
    Map<UUID, List<Line>> linesBySupplier = new LinkedHashMap<>();
    Set<UUID> given = new HashSet<>();
    for (RfqAwardLineRequest a : req.awards()) {
      UUID variantId = Parsing.uuid(a.variantId(), "awards.variantId");
      UUID supplierId = Parsing.uuid(a.supplierId(), "awards.supplierId");
      Line line = lineByVariant.get(variantId);
      if (line == null) {
        throw ApiException.badRequest(
            "PURCHASE_RFQ_LINE_UNKNOWN",
            "variant " + variantId + " is not on request " + h.reference());
      }
      if (!given.add(line.id())) {
        throw ApiException.badRequest(
            "PURCHASE_RFQ_AWARD_DUPLICATE", "variant " + variantId + " is awarded twice");
      }
      Bid bid = bidBySupplier.get(supplierId);
      if (bid == null || !Rfq.QUOTED.equals(bid.status()) || !bid.prices().containsKey(line.id())) {
        throw ApiException.conflict(
            "PURCHASE_RFQ_NOT_QUOTED",
            "supplier "
                + supplierId
                + " did not price variant "
                + variantId
                + "; a line goes only to a supplier who quoted it");
      }
      linesBySupplier.computeIfAbsent(supplierId, k -> new ArrayList<>()).add(line);
    }

    Instant now = Instant.now();
    Map<String, BigDecimal> vatRates = pricing.findVatRates(tenantId);
    List<Award> awards = new ArrayList<>();
    for (Map.Entry<UUID, List<Line>> e : linesBySupplier.entrySet()) {
      Bid bid = bidBySupplier.get(e.getKey());
      String currency = bid.currency();
      LocalDate expected =
          h.neededBy() != null
              ? h.neededBy()
              : bid.leadTimeDays() == null
                  ? null
                  : LocalDate.now(ZoneOffset.UTC).plusDays(bid.leadTimeDays());
      PurchaseOrder po =
          new PurchaseOrder(
              Ids.newId(),
              tenantId,
              e.getKey(),
              h.storeId(),
              Domain.PO_DRAFT,
              currency,
              Totals.zero(currency).net(),
              Totals.zero(currency).vat(),
              Totals.zero(currency).gross(),
              expected,
              now,
              now,
              null,
              null,
              null,
              null,
              ctx.userId(),
              null,
              null,
              Domain.PO_SOURCE_RFQ);
      purchases.createPurchaseOrder(po, Events.purchaseOrderCreated(tenantId, po.id()));
      for (Line line : e.getValue()) {
        BigDecimal price = bid.prices().get(line.id());
        purchases.addPurchaseOrderLine(
            new PurchaseOrderLine(
                Ids.newId(),
                tenantId,
                po.id(),
                line.variantId(),
                line.qty(),
                price,
                "T1",
                now,
                "awarded from " + h.reference() + " at the quoted price"),
            currency,
            vatRates);
        awards.add(
            new Award(
                Ids.newId(), tenantId, id, line.id(), e.getKey(), po.id(), price, currency, now));
      }
    }
    if (!repo.award(tenantId, id, ctx.userId(), awards)) {
      LOG.log(
          Level.WARNING,
          "rfq {0} of tenant {1} was awarded by someone else first; {2} draft order(s) raised here are left for a person to cancel",
          id,
          tenantId,
          linesBySupplier.size());
      throw ApiException.conflict(
          "PURCHASE_RFQ_NOT_ISSUED",
          "request " + h.reference() + " was awarded or closed meanwhile");
    }
    return detail(ctx, id);
  }

  /**
   * @throws ApiException 409 {@code PURCHASE_RFQ_CLOSED} once awarded or already cancelled
   */
  public Detail cancel(TenantContext ctx, UUID id, String reason) {
    ctx.requireAnyRole(BUYING);
    UUID tenantId = ctx.requireTenantId();
    Header h = require(tenantId, id);
    if (Rfq.AWARDED.equals(h.status())
        || Rfq.CANCELLED.equals(h.status())
        || !repo.cancel(tenantId, id, reason.trim())) {
      throw ApiException.conflict(
          "PURCHASE_RFQ_CLOSED",
          "request " + h.reference() + " is " + h.status() + " and cannot be cancelled");
    }
    return detail(ctx, id);
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  private Header require(UUID tenantId, UUID id) {
    return repo.find(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("PURCHASE_RFQ_NOT_FOUND", "no request " + id));
  }

  private Header requireIssued(UUID tenantId, UUID id) {
    Header h = require(tenantId, id);
    if (!Rfq.ISSUED.equals(h.status())) {
      throw ApiException.conflict(
          "PURCHASE_RFQ_NOT_ISSUED",
          "request " + h.reference() + " is " + h.status() + "; quotes and awards need it ISSUED");
    }
    return h;
  }

  private Bid requireInvited(UUID tenantId, UUID id, UUID supplierId) {
    Optional<Bid> bid =
        repo.bids(tenantId, id).stream().filter(b -> b.supplierId().equals(supplierId)).findFirst();
    return bid.orElseThrow(() -> notInvited(supplierId));
  }

  private static ApiException notInvited(UUID supplierId) {
    return ApiException.badRequest(
        "PURCHASE_RFQ_SUPPLIER_NOT_INVITED", "supplier " + supplierId + " was not asked to quote");
  }

  private static LocalDate date(String s, String field) {
    return s == null || s.isBlank() ? null : Parsing.date(s, field);
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }
}
