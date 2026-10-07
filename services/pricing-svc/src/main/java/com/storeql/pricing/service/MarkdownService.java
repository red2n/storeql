package com.storeql.pricing.service;

import com.storeql.ids.Ids;
import com.storeql.pricing.client.InventoryClient;
import com.storeql.pricing.domain.Domain.Markdown;
import com.storeql.pricing.domain.Domain.MarkdownLadder;
import com.storeql.pricing.domain.Domain.MarkdownStep;
import com.storeql.pricing.domain.Domain.MarkdownSuggestion;
import com.storeql.pricing.domain.Domain.PriceList;
import com.storeql.pricing.domain.Domain.PriceListItem;
import com.storeql.pricing.domain.MarkdownLabel;
import com.storeql.pricing.dto.Dtos.CreateMarkdownRequest;
import com.storeql.pricing.dto.Dtos.MarkdownStepRequest;
import com.storeql.pricing.repo.PricingRepository;
import com.storeql.web.ApiException;
import com.storeql.web.Parsing;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Date-code markdown — reduce to clear (05.4), and the ladder that plans it (03.9).
 *
 * <p>A markdown is a batch stickered at a lower price so it sells before its date. The sticker
 * carries a new barcode; the till scans it, this service says what it means, and the sale is priced
 * at the sticker's price with no promotion on top. The ladder turns the morning's work into a list:
 * every batch coming up to its date, and what to sticker it at.
 */
@ApplicationScoped
public class MarkdownService {

  /** The roles that sticker a counter: warehouse and management, not the till. */
  private static final String[] STOCK_ROLES = {"PLATFORM_ADMIN", "OWNER", "MANAGER", "STOREKEEPER"};

  @Inject PricingRepository repo;
  @Inject InventoryClient inventory;
  @Inject com.storeql.service.TenantProfiles profiles;

  /** Today, in UTC — the date a markdown's status is judged on. */
  static LocalDate today() {
    return Instant.now().atZone(ZoneOffset.UTC).toLocalDate();
  }

  // ── the ladder (03.9) ──────────────────────────────────────────────────────

  /** The ladder that applies at a store: its own, the tenant's, or the default. */
  public MarkdownLadder ladder(UUID tenantId, UUID storeId) {
    return repo.findLadder(tenantId, storeId)
        .orElse(
            new MarkdownLadder(null, MarkdownLadder.DEFAULT_STEPS, MarkdownLadder.SOURCE_DEFAULT));
  }

  /**
   * Replaces a ladder. Management only: how much margin to give away on the counter is a policy.
   *
   * @throws ApiException {@code PRICING_LADDER_DUPLICATE_STEP} (400) when two steps share a day
   */
  public MarkdownLadder setLadder(
      TenantContext ctx, UUID storeId, List<MarkdownStepRequest> steps) {
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
    Set<Integer> days = new HashSet<>();
    List<MarkdownStep> parsed = new ArrayList<>();
    for (MarkdownStepRequest s : steps) {
      if (!days.add(s.daysToExpiry())) {
        throw ApiException.badRequest(
            "PRICING_LADDER_DUPLICATE_STEP", "two steps at " + s.daysToExpiry() + " days");
      }
      parsed.add(
          new MarkdownStep(s.daysToExpiry(), s.percentOff().setScale(2, RoundingMode.HALF_UP)));
    }
    repo.replaceLadder(ctx.requireTenantId(), storeId, parsed, ctx.userId());
    return ladder(ctx.requireTenantId(), storeId);
  }

  // ── the plan (03.9) ────────────────────────────────────────────────────────

  /** The plan and whether inventory-svc could be read for it. */
  public record Plan(
      MarkdownLadder ladder, boolean inventoryReachable, List<MarkdownSuggestion> suggestions) {}

  /**
   * What to sticker this morning at a store: every batch expiring within the horizon, its current
   * POS price, the ladder's step for its days to go, and the markdown already on it if any.
   */
  public Plan plan(TenantContext ctx, UUID storeId, int withinDays) {
    ctx.requireAnyRole(STOCK_ROLES);
    ctx.requireStoreAccess(storeId);
    UUID tenantId = ctx.requireTenantId();
    if (withinDays < 1 || withinDays > 60) {
      throw ApiException.badRequest("PRICING_INVALID_HORIZON", "withinDays must be 1–60");
    }
    MarkdownLadder ladder = ladder(tenantId, storeId);
    Optional<List<InventoryClient.ExpiringBatch>> batches =
        inventory.expiringBatches(tenantId, storeId, withinDays, ctx);
    if (batches.isEmpty()) {
      return new Plan(ladder, false, List.of());
    }
    List<UUID> batchIds =
        batches.get().stream().map(InventoryClient.ExpiringBatch::batchId).toList();
    Map<UUID, Markdown> existing = new HashMap<>();
    for (Markdown m : repo.findActiveMarkdownsForBatches(tenantId, batchIds)) {
      existing.put(m.batchId(), m);
    }
    List<MarkdownSuggestion> out = new ArrayList<>();
    for (InventoryClient.ExpiringBatch b : batches.get()) {
      Optional<PriceListItem> price =
          repo.resolveBasePrice(tenantId, b.variantId(), PriceList.CHANNEL_POS, BigDecimal.ONE);
      BigDecimal current = price.map(PriceListItem::price).orElse(null);
      String currency =
          price
              .flatMap(p -> repo.findPriceList(tenantId, p.priceListId()))
              .map(PriceList::currency)
              .orElse(null);
      MarkdownStep step = ladder.stepFor(Math.max(0, b.daysUntilExpiry()));
      BigDecimal suggested =
          current == null || step == null
              ? null
              : reducedPrice(
                  current,
                  step.percentOff(),
                  com.storeql.service.Fx.minorUnits(
                      currency != null ? currency : profiles.requireCurrency(tenantId)));
      out.add(
          new MarkdownSuggestion(
              b.batchId(),
              b.variantId(),
              b.batchNo(),
              b.expiryDate(),
              b.daysUntilExpiry(),
              b.remainingQty(),
              current,
              currency,
              step,
              suggested,
              existing.get(b.batchId())));
    }
    return new Plan(ladder, true, out);
  }

  /**
   * A price less a percentage, rounded half up to the currency's minor units ({@code scale}): whole
   * yen, pence, three-decimal dinars.
   */
  static BigDecimal reducedPrice(BigDecimal price, BigDecimal percentOff, int scale) {
    return price
        .multiply(BigDecimal.valueOf(100).subtract(percentOff))
        .divide(BigDecimal.valueOf(100), scale, RoundingMode.HALF_UP);
  }

  // ── stickering (05.4) ──────────────────────────────────────────────────────

  /**
   * Stickers a batch: the decision recorded, the sticker's barcode issued, the reduced price the
   * till will charge when it scans it.
   *
   * @throws ApiException {@code PRICING_MARKDOWN_REASON_UNKNOWN}, {@code
   *     PRICING_MARKDOWN_AMOUNT_REQUIRED}, {@code PRICING_MARKDOWN_AMOUNT_AMBIGUOUS}, {@code
   *     PRICING_MARKDOWN_NOT_A_REDUCTION}, {@code PRICING_MARKDOWN_EXPIRED_DATE}, {@code
   *     PRICING_MARKDOWN_LABEL_RANGE} (400); {@code PRICING_MARKDOWN_NO_PRICE} (404)
   */
  public Markdown create(TenantContext ctx, CreateMarkdownRequest req) {
    ctx.requireAnyRole(STOCK_ROLES);
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = Parsing.uuid(req.storeId(), "storeId");
    ctx.requireStoreAccess(storeId);
    UUID variantId = Parsing.uuid(req.variantId(), "variantId");
    UUID batchId = Parsing.optionalUuid(req.batchId(), "batchId");
    String reason = req.reason().trim().toUpperCase(Locale.ROOT);
    if (!Markdown.REASONS.contains(reason)) {
      throw ApiException.badRequest(
          "PRICING_MARKDOWN_REASON_UNKNOWN",
          "reason must be one of "
              + new java.util.TreeSet<>(Markdown.REASONS)
              + " — got: "
              + req.reason());
    }
    LocalDate expiry = Parsing.date(req.expiryDate(), "expiryDate");
    if (expiry.isBefore(today())) {
      throw ApiException.badRequest(
          "PRICING_MARKDOWN_EXPIRED_DATE",
          "the batch expired on " + expiry + "; it cannot be sold");
    }
    if (req.percentOff() == null && req.markdownPrice() == null) {
      throw ApiException.badRequest(
          "PRICING_MARKDOWN_AMOUNT_REQUIRED", "give percentOff or markdownPrice");
    }
    if (req.percentOff() != null && req.markdownPrice() != null) {
      throw ApiException.badRequest(
          "PRICING_MARKDOWN_AMOUNT_AMBIGUOUS", "give percentOff or markdownPrice, not both");
    }
    PriceListItem base =
        repo.resolveBasePrice(tenantId, variantId, PriceList.CHANNEL_POS, BigDecimal.ONE)
            .orElseThrow(
                () ->
                    ApiException.notFound(
                        "PRICING_MARKDOWN_NO_PRICE",
                        "variant " + variantId + " has no POS price to reduce from"));
    String currency =
        repo.findPriceList(tenantId, base.priceListId())
            .map(PriceList::currency)
            .orElseGet(() -> profiles.requireCurrency(tenantId));
    // Money in the list currency's own minor units; the percentage keeps its two decimals.
    int scale = com.storeql.service.Fx.minorUnits(currency);
    BigDecimal original = base.price().setScale(scale, RoundingMode.HALF_UP);
    BigDecimal reduced;
    BigDecimal percent;
    if (req.percentOff() != null) {
      percent = req.percentOff().setScale(2, RoundingMode.HALF_UP);
      reduced = reducedPrice(original, percent, scale);
    } else {
      // A typed sticker price is no finer than the currency, kept at its minor units.
      reduced = PricingService.amountIn(req.markdownPrice(), currency, "markdownPrice");
      percent =
          original.signum() == 0
              ? BigDecimal.ZERO
              : original
                  .subtract(reduced)
                  .multiply(BigDecimal.valueOf(100))
                  .divide(original, 2, RoundingMode.HALF_UP);
    }
    if (reduced.compareTo(original) >= 0) {
      throw ApiException.badRequest(
          "PRICING_MARKDOWN_NOT_A_REDUCTION",
          reduced + " is not below the current price " + original);
    }
    if (reduced.compareTo(MarkdownLabel.maxPrice(scale)) > 0) {
      throw ApiException.badRequest(
          "PRICING_MARKDOWN_LABEL_RANGE",
          "a sticker carries a price up to "
              + MarkdownLabel.maxPrice(scale).toPlainString()
              + " "
              + currency
              + "; "
              + reduced
              + " does not fit");
    }
    Markdown draft =
        new Markdown(
            Ids.newId(),
            tenantId,
            storeId,
            variantId,
            batchId,
            req.batchNo() == null || req.batchNo().isBlank() ? null : req.batchNo().trim(),
            expiry,
            req.qty(),
            currency,
            original,
            reduced,
            percent,
            reason,
            null,
            Markdown.STATUS_ACTIVE,
            ctx.userId(),
            Instant.now(),
            null,
            null,
            null,
            BigDecimal.ZERO);
    return repo.createMarkdown(draft);
  }

  /** A store's markdowns; {@code status} ACTIVE, EXPIRED, CANCELLED or null for all. */
  public List<Markdown> list(TenantContext ctx, UUID storeId, String status) {
    ctx.requireAnyRole(STOCK_ROLES);
    ctx.requireStoreAccess(storeId);
    String wanted =
        status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
    if (wanted != null
        && !Markdown.STATUS_ACTIVE.equals(wanted)
        && !Markdown.STATUS_CANCELLED.equals(wanted)
        && !Markdown.STATUS_EXPIRED.equals(wanted)) {
      throw ApiException.badRequest(
          "PRICING_MARKDOWN_STATUS_UNKNOWN", "status must be ACTIVE, EXPIRED or CANCELLED");
    }
    String stored = Markdown.STATUS_EXPIRED.equals(wanted) ? Markdown.STATUS_ACTIVE : wanted;
    LocalDate today = today();
    return repo.listMarkdowns(ctx.requireTenantId(), storeId, stored, 200).stream()
        .filter(m -> wanted == null || m.effectiveStatus(today).equals(wanted))
        .toList();
  }

  /**
   * @throws ApiException {@code PRICING_MARKDOWN_NOT_FOUND} (404) when it is not this tenant's
   */
  public Markdown get(TenantContext ctx, UUID id) {
    ctx.requireAnyRole(STOCK_ROLES);
    Markdown m =
        repo.findMarkdown(ctx.requireTenantId(), id)
            .orElseThrow(
                () -> ApiException.notFound("PRICING_MARKDOWN_NOT_FOUND", "No such markdown"));
    ctx.requireStoreAccess(m.storeId());
    return m;
  }

  /**
   * Takes the stickers off: the code stops scanning. What already sold at the price stays sold.
   *
   * @throws ApiException {@code PRICING_MARKDOWN_NOT_ACTIVE} (409)
   */
  public Markdown cancel(TenantContext ctx, UUID id, String reason) {
    Markdown m = get(ctx, id);
    if (!repo.cancelMarkdown(ctx.requireTenantId(), id, reason.trim(), ctx.userId())) {
      throw ApiException.conflict(
          "PRICING_MARKDOWN_NOT_ACTIVE",
          "markdown " + m.labelCode() + " is " + m.effectiveStatus(today()));
    }
    return get(ctx, id);
  }

  // ── the till ───────────────────────────────────────────────────────────────

  /**
   * What a scanned sticker means: the live markdown behind its code.
   *
   * @throws ApiException {@code PRICING_MARKDOWN_LABEL_UNKNOWN} (404); {@code
   *     PRICING_MARKDOWN_EXPIRED}, {@code PRICING_MARKDOWN_EXHAUSTED} (409)
   */
  public Markdown lookupLabel(UUID tenantId, String code) {
    String c = code == null ? "" : code.trim();
    Markdown m =
        (MarkdownLabel.isLabel(c)
                ? repo.findMarkdownByLabel(tenantId, c)
                : Optional.<Markdown>empty())
            .orElseThrow(
                () ->
                    ApiException.notFound(
                        "PRICING_MARKDOWN_LABEL_UNKNOWN",
                        "no live reduced-price sticker carries " + c));
    return requireSellable(m, BigDecimal.ONE);
  }

  /**
   * The markdown a basket line names, checked as the till would: live, not past its date, the
   * variant and the store the sticker was for, and packs still to sell.
   *
   * @throws ApiException {@code PRICING_MARKDOWN_NOT_FOUND} (404); {@code
   *     PRICING_MARKDOWN_VARIANT_MISMATCH}, {@code PRICING_MARKDOWN_STORE_MISMATCH} (400); {@code
   *     PRICING_MARKDOWN_CANCELLED}, {@code PRICING_MARKDOWN_EXPIRED}, {@code
   *     PRICING_MARKDOWN_EXHAUSTED} (409)
   */
  public Markdown forQuoteLine(
      UUID tenantId, UUID markdownId, UUID variantId, UUID storeId, BigDecimal qty) {
    Markdown m =
        repo.findMarkdown(tenantId, markdownId)
            .orElseThrow(
                () -> ApiException.notFound("PRICING_MARKDOWN_NOT_FOUND", "No such markdown"));
    if (!m.variantId().equals(variantId)) {
      throw ApiException.badRequest(
          "PRICING_MARKDOWN_VARIANT_MISMATCH",
          "sticker " + m.labelCode() + " is for another product");
    }
    if (storeId != null && !m.storeId().equals(storeId)) {
      throw ApiException.badRequest(
          "PRICING_MARKDOWN_STORE_MISMATCH",
          "sticker " + m.labelCode() + " was issued at another store");
    }
    return requireSellable(m, qty);
  }

  private static Markdown requireSellable(Markdown m, BigDecimal qty) {
    String status = m.effectiveStatus(today());
    if (Markdown.STATUS_CANCELLED.equals(status)) {
      throw ApiException.conflict(
          "PRICING_MARKDOWN_CANCELLED",
          "sticker " + m.labelCode() + " was cancelled: " + m.cancelReason());
    }
    if (Markdown.STATUS_EXPIRED.equals(status)) {
      throw ApiException.conflict(
          "PRICING_MARKDOWN_EXPIRED",
          "sticker " + m.labelCode() + " is for a batch that expired on " + m.expiryDate());
    }
    if (m.remainingQty().compareTo(qty) < 0) {
      throw ApiException.conflict(
          "PRICING_MARKDOWN_EXHAUSTED",
          "sticker "
              + m.labelCode()
              + ": "
              + m.remainingQty().toPlainString()
              + " left of "
              + m.qty().toPlainString()
              + " stickered");
    }
    return m;
  }

  /** Records what an order sold at reduced prices; once per markdown and order. */
  public int recordRedemptions(UUID tenantId, UUID orderId, Map<UUID, BigDecimal> qtyByMarkdown) {
    int written = 0;
    for (var e : qtyByMarkdown.entrySet()) {
      if (repo.recordMarkdownRedemption(tenantId, e.getKey(), orderId, e.getValue())) {
        written++;
      }
    }
    return written;
  }
}
