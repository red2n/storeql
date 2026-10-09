package com.storeql.pricing.service;

import com.storeql.ids.Ids;
import com.storeql.money.TaxInclusive;
import com.storeql.pricing.domain.Domain;
import com.storeql.pricing.domain.Domain.BasketLine;
import com.storeql.pricing.domain.Domain.CustomerVatStatus;
import com.storeql.pricing.domain.Domain.DisplayPrice;
import com.storeql.pricing.domain.Domain.PriceList;
import com.storeql.pricing.domain.Domain.PriceListItem;
import com.storeql.pricing.domain.Domain.PriceOverride;
import com.storeql.pricing.domain.Domain.ProductVatCategory;
import com.storeql.pricing.domain.Domain.Promotion;
import com.storeql.pricing.domain.Domain.PromotionItem;
import com.storeql.pricing.domain.Domain.PromotionWindow;
import com.storeql.pricing.domain.Domain.ResolvedPrice;
import com.storeql.pricing.domain.Domain.TaxGrouping;
import com.storeql.pricing.domain.Domain.TaxSummary;
import com.storeql.pricing.domain.Domain.TaxSummaryRow;
import com.storeql.pricing.domain.Domain.TaxSummaryTotals;
import com.storeql.pricing.domain.Domain.TaxTransaction;
import com.storeql.pricing.domain.Domain.VatRate;
import com.storeql.pricing.domain.Domain.VatReturn;
import com.storeql.pricing.dto.Dtos.AddPromotionItemRequest;
import com.storeql.pricing.dto.Dtos.AppliedPromotionResponse;
import com.storeql.pricing.dto.Dtos.BatchUpsertPriceListItemsRequest;
import com.storeql.pricing.dto.Dtos.BatchUpsertProductVatCategoriesRequest;
import com.storeql.pricing.dto.Dtos.BatchUpsertResult;
import com.storeql.pricing.dto.Dtos.CreatePriceListRequest;
import com.storeql.pricing.dto.Dtos.CreatePriceOverrideRequest;
import com.storeql.pricing.dto.Dtos.CreatePromotionRequest;
import com.storeql.pricing.dto.Dtos.CreateVatRateRequest;
import com.storeql.pricing.dto.Dtos.QuoteBasketRequest;
import com.storeql.pricing.dto.Dtos.QuoteBasketResponse;
import com.storeql.pricing.dto.Dtos.QuoteLineResponse;
import com.storeql.pricing.dto.Dtos.RecordTaxTransactionRequest;
import com.storeql.pricing.dto.Dtos.ResolvePriceRequest;
import com.storeql.pricing.dto.Dtos.SetActiveRequest;
import com.storeql.pricing.dto.Dtos.UpsertCustomerVatStatusRequest;
import com.storeql.pricing.dto.Dtos.UpsertPriceListItemRequest;
import com.storeql.pricing.dto.Dtos.UpsertProductVatCategoryRequest;
import com.storeql.pricing.repo.PricingRepository;
import com.storeql.pricing.repo.TaxReportRepository;
import com.storeql.web.ApiException;
import com.storeql.web.Cursor;
import com.storeql.web.Parsing;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Business logic for pricing-svc. Controllers call this; no HTTP types here. */
@ApplicationScoped
public class PricingService {

  @Inject PricingRepository repo;
  @Inject com.storeql.pricing.repo.RepricingRepository zones;
  @Inject com.storeql.service.TenantProfiles profiles;
  @Inject com.storeql.service.FxRates fx;
  @Inject com.storeql.service.Jurisdictions jurisdictions;
  @Inject AppliedPriceService appliedPrices;
  @Inject PromotionEngine engine;
  @Inject MarkdownService markdowns;
  @Inject TaxReportRepository taxReportRepo;

  /** No rate is configured for the VAT code a price needs, so it is not quoted (SJ-D56). */
  public static final String VAT_RATE_NOT_CONFIGURED = "PRICING_VAT_RATE_NOT_CONFIGURED";

  // ── VAT Rates ─────────────────────────────────────────────────────────────

  /**
   * Creates a VAT rate for the tenant.
   *
   * @param req the code, name, rate as a fraction (0.20 = 20%), exempt flag and effective date
   * @param ctx caller context; supplies the tenant
   * @return the created rate, its code upper-cased
   * @throws ApiException {@code PRICING_INVALID_RATE} (400) when the rate exceeds 1
   */
  public VatRate createVatRate(CreateVatRateRequest req, TenantContext ctx) {
    if (req.rate().compareTo(BigDecimal.ONE) > 0)
      throw ApiException.badRequest("PRICING_INVALID_RATE", "VAT rate must be between 0 and 1");
    VatRate r =
        new VatRate(
            Ids.newId(),
            ctx.tenantId(),
            req.code().toUpperCase(java.util.Locale.ROOT),
            req.name(),
            req.rate(),
            req.exempt(),
            req.description(),
            Parsing.instant(req.effectiveFrom(), "effectiveFrom"),
            null,
            Instant.now());
    return repo.createVatRate(r);
  }

  /**
   * Lists the tenant's VAT rates.
   *
   * @param ctx caller context; supplies the tenant
   * @return the configured rates
   */
  public List<VatRate> listVatRates(TenantContext ctx) {
    return repo.findVatRates(ctx.tenantId());
  }

  /**
   * Reads one VAT rate by code.
   *
   * @param ctx caller context; supplies the tenant
   * @param code the VAT code, matched case-insensitively
   * @return the rate
   * @throws ApiException {@code PRICING_VAT_CODE_NOT_FOUND} (404) when no such code exists
   */
  public VatRate getVatRate(TenantContext ctx, String code) {
    return repo.findVatRate(ctx.tenantId(), code.toUpperCase(java.util.Locale.ROOT))
        .orElseThrow(
            () ->
                ApiException.notFound("PRICING_VAT_CODE_NOT_FOUND", "VAT code not found: " + code));
  }

  /**
   * Updates a VAT rate in place.
   *
   * <p>Overwrites the rate rather than superseding it, so historical tax transactions already
   * recorded against this code keep the figures they were stamped with, while future ones use the
   * new value.
   *
   * @param ctx caller context; supplies the tenant
   * @param code the VAT code to update
   * @param req the new name, rate, exempt flag and optional effective date
   * @return the updated rate
   * @throws ApiException {@code PRICING_VAT_CODE_NOT_FOUND} (404) when no such code exists; {@code
   *     PRICING_INVALID_RATE} (400) when the rate exceeds 1
   */
  public VatRate updateVatRate(TenantContext ctx, String code, CreateVatRateRequest req) {
    VatRate existing = getVatRate(ctx, code);
    if (req.rate().compareTo(BigDecimal.ONE) > 0)
      throw ApiException.badRequest("PRICING_INVALID_RATE", "VAT rate must be between 0 and 1");
    Instant newEffectiveFrom =
        req.effectiveFrom() != null
            ? Parsing.instant(req.effectiveFrom(), "effectiveFrom")
            : existing.effectiveFrom();
    VatRate updated =
        new VatRate(
            existing.id(),
            ctx.tenantId(),
            code.toUpperCase(java.util.Locale.ROOT),
            req.name(),
            req.rate(),
            req.exempt(),
            req.description(),
            newEffectiveFrom,
            existing.effectiveTo(),
            existing.createdAt());
    return repo.updateVatRate(updated);
  }

  // ── Product VAT Categories ────────────────────────────────────────────────

  /**
   * Assigns a variant to a VAT code.
   *
   * <p>The code is checked against the tenant's own rates first, so a typo cannot leave a product
   * pointing at a band that does not exist and silently falling back to standard rate at checkout.
   *
   * @param req the variant and the VAT code to assign it
   * @param ctx caller context; supplies the tenant
   * @return the stored assignment
   * @throws ApiException {@code PRICING_VAT_CODE_NOT_FOUND} (404) when the code is not configured
   */
  public ProductVatCategory upsertProductVatCategory(
      UpsertProductVatCategoryRequest req, TenantContext ctx) {
    String vatCode = req.vatCode().toUpperCase(java.util.Locale.ROOT);
    repo.findVatRate(ctx.tenantId(), vatCode)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "PRICING_VAT_CODE_NOT_FOUND", "VAT code not found: " + vatCode));
    ProductVatCategory pvc =
        new ProductVatCategory(
            Ids.newId(),
            ctx.tenantId(),
            Ids.parse(req.variantId()),
            vatCode,
            Instant.now(),
            null,
            Instant.now());
    ProductVatCategory saved = repo.upsertProductVatCategory(pvc);
    appliedPrices.catchUp(ctx.tenantId());
    return saved;
  }

  /**
   * Assigns many variants to VAT codes at once, all or none: the way a catalogue import and a
   * go-live fix-up give a priced shop its categories. Every row is checked before anything is
   * written, and the applied-price ledger is caught up once, not per row.
   *
   * @param req the assignments, one per variant
   * @param ctx caller context; supplies the tenant
   * @return how many variants were assigned
   * @throws ApiException {@code PRICING_VAT_BATCH_INVALID} (400) naming the rows that are wrong
   */
  public int upsertProductVatCategories(
      BatchUpsertProductVatCategoriesRequest req, TenantContext ctx) {
    UUID tenantId = ctx.requireTenantId();
    Set<String> rates = new java.util.HashSet<>();
    for (VatRate r : repo.findVatRates(tenantId)) rates.add(r.code());
    Set<UUID> seen = new java.util.HashSet<>();
    List<String> problems = new ArrayList<>();
    List<ProductVatCategory> rows = new ArrayList<>();
    Instant now = Instant.now();
    int row = 0;
    for (UpsertProductVatCategoryRequest item : req.items()) {
      row++;
      try {
        com.storeql.web.Validations.validate(item);
        UUID variantId = Parsing.uuid(item.variantId(), "variantId");
        String code = item.vatCode().trim().toUpperCase(java.util.Locale.ROOT);
        if (!rates.contains(code)) {
          problems.add(
              "row " + row + ": VAT code " + code + " is not one of this business's rates");
        } else if (!seen.add(variantId)) {
          problems.add("row " + row + ": variant " + variantId + " appears twice");
        } else {
          rows.add(new ProductVatCategory(Ids.newId(), tenantId, variantId, code, now, null, now));
        }
      } catch (ApiException e) {
        problems.add("row " + row + ": " + e.getMessage());
      }
      if (problems.size() >= 10) break;
    }
    if (!problems.isEmpty()) {
      throw ApiException.badRequest(
          "PRICING_VAT_BATCH_INVALID", "nothing was assigned: " + String.join("; ", problems));
    }
    repo.upsertProductVatCategories(rows);
    appliedPrices.catchUp(tenantId);
    return rows.size();
  }

  /**
   * What stands between the business and tax-inclusive pricing, or what is wrong with its VAT
   * set-up if it already uses it: the priced variants with no VAT category (a page of them and how
   * many in all) and the codes variants are assigned to that have no rate.
   *
   * @param ctx caller context; supplies the tenant
   * @param after the last variant of the previous page, or {@code null}
   * @param limit page size
   * @return the readiness report, its gaps page, and the cursor of the next page or {@code null}
   */
  public VatReadinessPage vatReadiness(TenantContext ctx, String after, int limit) {
    UUID tenantId = ctx.requireTenantId();
    String raw = Cursor.decode(after);
    UUID cursor = raw == null ? null : Parsing.uuid(raw, "after");
    List<Domain.VatGap> rows = repo.findVatGaps(tenantId, cursor, limit + 1);
    Cursor.Page<Domain.VatGap> page = Cursor.page(rows, limit, g -> g.variantId().toString());
    List<String> modes = repo.findActiveTaxModes(tenantId);
    int missing = repo.countVatGaps(tenantId);
    List<String> noRate = repo.findVatCodesWithoutRate(tenantId);
    return new VatReadinessPage(
        modes.isEmpty() ? null : modes.get(0),
        missing,
        noRate,
        page.items(),
        missing == 0 && noRate.isEmpty(),
        page.nextCursor());
  }

  /** One page of the VAT readiness report. */
  public record VatReadinessPage(
      String taxMode,
      int variantsWithoutCategory,
      List<String> codesWithoutRate,
      List<Domain.VatGap> gaps,
      boolean ready,
      String nextCursor) {}

  /**
   * Reads a variant's VAT assignment.
   *
   * <p>Unlike price resolution, which falls back to the standard rate, this reports the absence:
   * the admin screen needs to know a product was never categorised.
   *
   * @param ctx caller context; supplies the tenant
   * @param variantId the variant to look up
   * @return the assignment
   * @throws ApiException {@code PRICING_VAT_CATEGORY_NOT_FOUND} (404) when none is assigned
   */
  public ProductVatCategory getProductVatCategory(TenantContext ctx, UUID variantId) {
    return repo.findProductVatCategory(ctx.tenantId(), variantId)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "PRICING_VAT_CATEGORY_NOT_FOUND",
                    "no VAT category assigned for variant " + variantId));
  }

  // ── Customer VAT Status ───────────────────────────────────────────────────

  /**
   * Records a customer's VAT registration and reverse-charge eligibility.
   *
   * @param req the customer, VAT number, registration and reverse-charge flags, and country
   *     (defaulting to {@code GB})
   * @param ctx caller context; supplies the tenant
   * @return the stored status
   */
  public CustomerVatStatus upsertCustomerVatStatus(
      UpsertCustomerVatStatusRequest req, TenantContext ctx) {
    String country = profiles.countryOr(ctx.tenantId(), req.countryCode());
    // What an invoice to this customer will name it by (18.9): a VAT identifier with its country
    // prefix, or for an Indian customer its GSTIN, and a Peppol address when it has one.
    String vatNumber;
    try {
      vatNumber =
          "IN".equals(country)
              ? com.storeql.einvoice.Gstin.parse(req.vatNumber())
              : com.storeql.einvoice.VatIdentifier.parse(req.vatNumber());
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, "PRICING_VAT_NUMBER_INVALID", e.getMessage(), List.of(), e);
    }
    com.storeql.einvoice.ElectronicAddress address;
    try {
      address =
          com.storeql.einvoice.ElectronicAddress.parse(req.einvoiceScheme(), req.einvoiceId());
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, "PRICING_EINVOICE_ADDRESS_INVALID", e.getMessage(), List.of(), e);
    }
    CustomerVatStatus cvs =
        new CustomerVatStatus(
            Ids.newId(),
            ctx.tenantId(),
            Ids.parse(req.customerId()),
            vatNumber,
            req.vatRegistered(),
            req.reverseChargeEligible(),
            country,
            req.legalName() == null || req.legalName().isBlank() ? null : req.legalName().strip(),
            address == null ? null : address.scheme(),
            address == null ? null : address.id(),
            Instant.now(),
            Instant.now());
    return repo.upsertCustomerVatStatus(cvs);
  }

  /**
   * Reads a customer's VAT status.
   *
   * @param ctx caller context; supplies the tenant
   * @param customerId the customer to look up
   * @return the stored status
   * @throws ApiException {@code PRICING_CUSTOMER_VAT_NOT_FOUND} (404) when none is recorded
   */
  public CustomerVatStatus getCustomerVatStatus(TenantContext ctx, UUID customerId) {
    return repo.findCustomerVatStatus(ctx.tenantId(), customerId)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "PRICING_CUSTOMER_VAT_NOT_FOUND", "no VAT status for customer " + customerId));
  }

  // ── Price Lists ───────────────────────────────────────────────────────────

  /**
   * Creates a price list, active from the moment it is created.
   *
   * @param req the name, channel (defaulting to ALL), currency (the tenant's own when omitted) and
   *     effective window
   * @param ctx caller context; supplies the tenant
   * @return the created price list
   */
  public PriceList createPriceList(CreatePriceListRequest req, TenantContext ctx) {
    // 03.x: a list bound to a price zone prices that zone's stores and no other.
    UUID zoneId = Parsing.optionalUuid(req.zoneId(), "zoneId");
    if (zoneId != null && zones.findZone(ctx.tenantId(), zoneId).isEmpty()) {
      throw ApiException.badRequest(
          "PRICING_ZONE_UNKNOWN", "price zone " + req.zoneId() + " is not one of this business's");
    }
    PriceList pl =
        new PriceList(
            Ids.newId(),
            ctx.tenantId(),
            req.name(),
            req.channel() != null ? req.channel() : PriceList.CHANNEL_ALL,
            profiles.currencyOr(ctx.tenantId(), req.currency()),
            Parsing.instant(req.effectiveFrom(), "effectiveFrom"),
            req.effectiveTo() != null ? Parsing.instant(req.effectiveTo(), "effectiveTo") : null,
            true,
            Instant.now(),
            zoneId,
            taxModeOf(req.taxMode()));
    return repo.createPriceList(pl);
  }

  /**
   * The tax mode a request names: EXCLUSIVE when it names none.
   *
   * @throws ApiException 400 {@code PRICING_TAX_MODE_UNKNOWN} for any other word
   */
  static String taxModeOf(String raw) {
    if (raw == null || raw.isBlank()) return PriceList.TAX_EXCLUSIVE;
    String mode = raw.trim().toUpperCase(java.util.Locale.ROOT);
    if (!PriceList.TAX_EXCLUSIVE.equals(mode) && !PriceList.TAX_INCLUSIVE.equals(mode)) {
      throw ApiException.badRequest(
          "PRICING_TAX_MODE_UNKNOWN",
          "taxMode is EXCLUSIVE (prices are net, VAT is added) or INCLUSIVE (prices are shelf"
              + " prices, VAT inside); not "
              + raw);
    }
    return mode;
  }

  /**
   * Cursor-paginated price lists. The cursor wraps the last row's created_at|id keyset.
   *
   * @param ctx caller context; supplies the tenant
   * @param after cursor from the previous page, or {@code null} to start
   * @param limit page size
   * @return the page of price lists
   */
  public Cursor.Page<PriceList> listPriceLists(TenantContext ctx, String after, int limit) {
    Cursor.CreatedAtId key = Cursor.decodeCreatedAtId(after);
    List<PriceList> rows =
        repo.findPriceLists(
            ctx.tenantId(),
            key == null ? null : key.createdAt(),
            key == null ? null : key.id(),
            limit + 1);
    return Cursor.page(rows, limit, pl -> pl.createdAt() + "|" + pl.id());
  }

  /**
   * Reads one price list.
   *
   * <p>Also the tenant-scoping guard the price-list-item methods call first.
   *
   * @param ctx caller context; supplies the tenant
   * @param id the price list to read
   * @return the price list
   * @throws ApiException {@code PRICING_LIST_NOT_FOUND} (404) when it does not exist in this tenant
   */
  public PriceList getPriceList(TenantContext ctx, UUID id) {
    return repo.findPriceList(ctx.tenantId(), id)
        .orElseThrow(
            () -> ApiException.notFound("PRICING_LIST_NOT_FOUND", "price list not found: " + id));
  }

  // ── Price List Items ──────────────────────────────────────────────────────

  /**
   * Sets a variant's price on a price list, publishing {@code PriceChanged}.
   *
   * @param ctx caller context; supplies the tenant
   * @param priceListId the price list to write to
   * @param req the variant, price and optional minimum quantity (defaulting to 1)
   * @return the stored item
   * @throws ApiException {@code PRICING_LIST_NOT_FOUND} (404) when the price list does not exist in
   *     this tenant
   */
  public PriceListItem upsertPriceListItem(
      TenantContext ctx, UUID priceListId, UpsertPriceListItemRequest req) {
    PriceListItem saved = setPrice(ctx, priceListId, req);
    appliedPrices.catchUp(ctx.tenantId());
    return saved;
  }

  /** Sets one price, queuing its evaluation (03.12) without working it. */
  private PriceListItem setPrice(
      TenantContext ctx, UUID priceListId, UpsertPriceListItemRequest req) {
    PriceList list = getPriceList(ctx, priceListId);
    UUID variantId = Ids.parse(req.variantId());
    // A shelf price's VAT is the rate of the variant's category; with none the rate is a guess,
    // and a guess is printed on a receipt (intent/vat-inclusive-pricing.md).
    if (list.taxInclusive() && repo.findProductVatCategory(ctx.tenantId(), variantId).isEmpty()) {
      throw ApiException.conflict(
          "PRICING_VAT_CATEGORY_REQUIRED",
          "variant "
              + variantId
              + " has no VAT category; give it one before pricing it on a tax-inclusive list");
    }
    PriceListItem item =
        new PriceListItem(
            Ids.newId(),
            ctx.tenantId(),
            priceListId,
            variantId,
            priceIn(req.price(), list.currency()),
            req.minQty() != null ? req.minQty() : BigDecimal.ONE,
            Instant.now(),
            Instant.now());
    return repo.upsertPriceListItem(item, Events.priceChanged(ctx.tenantId(), priceListId));
  }

  /** The most whole digits typed money may have: more than any money column holds is a typo. */
  static final int MOST_WHOLE_DIGITS = 18;

  /** Whole digits of promotions.value, NUMERIC(18,4): what the column holds, no more. */
  static final int PROMOTION_VALUE_WHOLE_DIGITS = 14;

  /**
   * Whole digits of competitor_prices.price and repricing_rules.value, NUMERIC(19,4): what the
   * columns hold, no more.
   */
  static final int FOUR_PLACE_WHOLE_DIGITS = 15;

  /**
   * A list price checked against the list's currency and kept at its own minor units: no finer than
   * the currency can be paid in (a yen price is whole yen, a dinar price has at most three
   * decimals), and {@code 10} in pounds is kept as {@code 10.00}.
   *
   * @throws ApiException 400 {@code VALIDATION_FAILED} for a price with more decimals than the
   *     currency has
   */
  static BigDecimal priceIn(BigDecimal price, String currency) {
    return amountIn(price, currency, "price");
  }

  /**
   * Money a person typed, checked against its currency and kept at its own minor units: refused by
   * name when finer than the currency, never rounded behind the typist's back.
   *
   * @param field the request field, named in the refusal
   * @return {@code amount} at the currency's scale; null in, null out
   * @throws ApiException 400 {@code VALIDATION_FAILED} for an amount with more decimals than the
   *     currency has, or more than {@link #MOST_WHOLE_DIGITS} whole digits
   */
  static BigDecimal amountIn(BigDecimal amount, String currency, String field) {
    return amountIn(amount, currency, field, MOST_WHOLE_DIGITS);
  }

  /**
   * As {@link #amountIn(BigDecimal, String, String)}, for a column that holds fewer whole digits
   * than any money column does ({@code NUMERIC(18,4)} holds fourteen): a figure it cannot hold is
   * refused here, by name, and never left to the database's overflow, which answers 500.
   *
   * @param wholeDigits the most whole digits the column holds
   */
  static BigDecimal amountIn(BigDecimal amount, String currency, String field, int wholeDigits) {
    if (amount == null) return null;
    requireWholeDigits(amount, field, wholeDigits);
    int units = com.storeql.service.Fx.minorUnits(currency);
    if (amount.stripTrailingZeros().scale() > units) {
      // toString, not toPlainString: 1E-80000000 written out in full is eighty million digits.
      String why =
          field + " " + amount + " has more decimals than " + currency + " has (" + units + ")";
      throw new ApiException(
          400, com.storeql.web.ErrorCodes.VALIDATION_FAILED, why, List.of(field + ": " + why));
    }
    return amount.setScale(units, RoundingMode.UNNECESSARY);
  }

  /**
   * A typed figure of no more than {@code wholeDigits} whole digits. More than any column holds is
   * no figure, refused before it is scaled: scaling 1E+80000000 to pence builds an eighty-million-
   * digit number first.
   *
   * @throws ApiException 400 {@code VALIDATION_FAILED} naming {@code field}
   */
  static void requireWholeDigits(BigDecimal amount, String field, int wholeDigits) {
    if (amount != null && (long) amount.precision() - amount.scale() > wholeDigits) {
      String why = field + " has more than " + wholeDigits + " whole digits";
      throw new ApiException(
          400, com.storeql.web.ErrorCodes.VALIDATION_FAILED, why, List.of(field + ": " + why));
    }
  }

  /**
   * A promotion's {@code value} as it is handed to the column. For the types whose value is money
   * (FLAT, BASKET_FLAT, SPEND_THRESHOLD, MIX_MATCH) it is returned at the business's currency's
   * minor units, and a value finer than they are is refused by name, not rounded. For a percentage,
   * or a BOGO (described by its quantities), it is no money and is returned as typed. For any type,
   * a value of more than {@link #PROMOTION_VALUE_WHOLE_DIGITS} whole digits is refused, not left to
   * overflow the column.
   *
   * @param type the promotion's type, upper case
   * @param value as typed
   * @param currency the business's own currency; read only for a type whose value is money
   * @throws ApiException 400 {@code VALIDATION_FAILED} for an amount with more decimals than the
   *     currency has, or a value of more than {@link #PROMOTION_VALUE_WHOLE_DIGITS} whole digits
   */
  static BigDecimal promotionValueIn(String type, BigDecimal value, String currency) {
    if (Promotion.isAmountValued(type)) {
      return amountIn(value, currency, "value", PROMOTION_VALUE_WHOLE_DIGITS);
    }
    requireWholeDigits(value, "value", PROMOTION_VALUE_WHOLE_DIGITS);
    return value;
  }

  /**
   * Sets many prices on one price list in a single call.
   *
   * <p>Deliberately not atomic: each item is attempted independently and a failure is collected
   * rather than rolled back, so one bad row in a bulk price upload does not discard the rest. The
   * caller must read {@code errors} — a partial success still returns 200.
   *
   * @param ctx caller context; supplies the tenant
   * @param priceListId the price list to write to
   * @param req the items to upsert
   * @return how many succeeded, and one message per failure
   * @throws ApiException {@code PRICING_LIST_NOT_FOUND} (404) when the price list does not exist in
   *     this tenant
   */
  public BatchUpsertResult batchUpsertPriceListItems(
      TenantContext ctx, UUID priceListId, BatchUpsertPriceListItemsRequest req) {
    getPriceList(ctx, priceListId);
    int upserted = 0;
    var errors = new java.util.ArrayList<String>();
    for (var r : req.items()) {
      try {
        com.storeql.web.Validations.validate(r);
        setPrice(ctx, priceListId, r);
        upserted++;
      } catch (Exception e) {
        errors.add("variantId=" + r.variantId() + ": " + e.getMessage());
      }
    }
    // One catch-up for the upload, not one per row: the sweeper drains the rest (03.12).
    if (upserted > 0) appliedPrices.catchUp(ctx.tenantId());
    return new BatchUpsertResult(upserted, errors);
  }

  /**
   * Lists the priced variants on one price list.
   *
   * @param ctx caller context; supplies the tenant
   * @param priceListId the price list whose items to list
   * @return the items
   * @throws ApiException {@code PRICING_LIST_NOT_FOUND} (404) when the price list does not exist in
   *     this tenant
   */
  public List<PriceListItem> listPriceListItems(TenantContext ctx, UUID priceListId) {
    getPriceList(ctx, priceListId);
    return repo.findPriceListItems(ctx.tenantId(), priceListId);
  }

  /**
   * One page of a price list's items, keyed on {@code (variant, quantity break)}.
   *
   * @param ctx caller context; supplies the tenant
   * @param priceListId the price list whose items to list
   * @param after the previous page's {@code meta.nextCursor}, or {@code null} for the first page
   * @param limit the page size, already clamped
   * @return the page with its next cursor
   * @throws ApiException {@code PRICING_LIST_NOT_FOUND} (404) when the price list does not exist in
   *     this tenant; {@code INVALID_CURSOR} (400) for a cursor that is not ours
   */
  public com.storeql.web.Cursor.Page<PriceListItem> listPriceListItemsPage(
      TenantContext ctx, UUID priceListId, String after, int limit) {
    getPriceList(ctx, priceListId);
    String raw = com.storeql.web.Cursor.decode(after);
    UUID afterVariant = null;
    java.math.BigDecimal afterQty = null;
    if (raw != null) {
      int bar = raw.indexOf('|');
      try {
        afterVariant = Ids.parse(raw.substring(0, bar));
        afterQty = new java.math.BigDecimal(raw.substring(bar + 1));
      } catch (RuntimeException e) {
        throw new ApiException(400, "INVALID_CURSOR", "Malformed pagination cursor", List.of(), e);
      }
    }
    List<PriceListItem> rows =
        repo.findPriceListItems(ctx.tenantId(), priceListId, afterVariant, afterQty, limit + 1);
    return com.storeql.web.Cursor.page(
        rows, limit, it -> it.variantId() + "|" + it.minQty().toPlainString());
  }

  // ── Price Resolution ──────────────────────────────────────────────────────

  /**
   * Prices one variant for a product page: base price, item-level promotions, and VAT.
   *
   * <p>Basket-level and coupon promotions are excluded on purpose — a spend-threshold price shown
   * against a single item advertises a total the shopper will not be charged, and a coupon they
   * have not presented is not theirs yet. {@link #quoteBasket} is where those can be tested.
   *
   * <p>A variant with no VAT category falls back to the standard rate rather than failing.
   *
   * @param req the variant, optional quantity (defaulting to 1), channel and store
   * @param ctx caller context; supplies the tenant
   * @return the resolved unit price with its VAT code, rate, amount and gross
   * @throws ApiException {@code PRICING_PRICE_NOT_FOUND} (404) when no active price is configured
   *     for the variant
   */
  public ResolvedPrice resolvePrice(ResolvePriceRequest req, TenantContext ctx) {
    return resolve(req, ctx, true);
  }

  /** As {@link #resolvePrice}; without promotions, the regular price a shelf label shows beside. */
  private ResolvedPrice resolve(
      ResolvePriceRequest req, TenantContext ctx, boolean withPromotions) {
    return resolveAt(ctx.tenantId(), req, withPromotions, Instant.now(), withPromotions);
  }

  /**
   * The price a shopper is offered as of {@code at}: the price lists in force then and the
   * promotions running then, by today's switches and prices. Without a request context.
   *
   * @param withPriorPrice whether to read the reduction's prior price from the ledger (03.12)
   */
  public ResolvedPrice resolveAt(
      UUID tenantId,
      ResolvePriceRequest req,
      boolean withPromotions,
      Instant at,
      boolean withPriorPrice) {
    return displayed(
        tenantId, price(tenantId, req, withPromotions, at, withPriorPrice, false), req);
  }

  /**
   * The price in the display currency asked for (03.x), at the business's rate — shown beside the
   * price, never in its place. The price's own currency converts to itself.
   *
   * @throws ApiException {@code 400 FX_RATE_MISSING} when the business keeps no rate for it
   */
  private ResolvedPrice displayed(UUID tenantId, ResolvedPrice rp, ResolvePriceRequest req) {
    String wanted = displayCurrency(req.displayCurrency());
    if (wanted == null) return rp;
    return rp.withDisplay(
        new DisplayPrice(
            wanted,
            fxRateFor(tenantId, rp.currency(), wanted),
            convertForDisplay(tenantId, rp.unitPrice(), rp.currency(), wanted),
            convertForDisplay(tenantId, rp.totalWithVat(), rp.currency(), wanted)));
  }

  static String displayCurrency(String requested) {
    if (requested == null || requested.isBlank()) return null;
    String code = requested.trim().toUpperCase(java.util.Locale.ROOT);
    if (!com.storeql.service.Fx.isCurrency(code)) {
      throw ApiException.badRequest(
          "FX_CURRENCY_INVALID", "displayCurrency must be an ISO 4217 code, e.g. USD");
    }
    return code;
  }

  private BigDecimal fxRateFor(UUID tenantId, String from, String to) {
    if (from.equals(to)) return BigDecimal.ONE;
    return fx.rate(tenantId, to).map(r -> r.rate()).orElseThrow(() -> noRate(from, to));
  }

  private BigDecimal convertForDisplay(UUID tenantId, BigDecimal amount, String from, String to) {
    if (amount == null || from.equals(to)) return amount;
    return fx.fromHome(tenantId, amount, to)
        .map(c -> c.amount())
        .orElseThrow(() -> noRate(from, to));
  }

  private static ApiException noRate(String from, String to) {
    return ApiException.badRequest(
        "FX_RATE_MISSING",
        "the business keeps no exchange rate for " + to + "; prices are in " + from);
  }

  /**
   * The currencies a shop can show prices in — its own first, then those it keeps a rate for — with
   * the rates, so a client can show a figure it already holds in the shop's currency.
   */
  public com.storeql.pricing.dto.Dtos.CurrenciesResponse currencies(UUID tenantId) {
    java.util.Optional<com.storeql.service.FxRates.Table> table = fx.table(tenantId);
    if (table.isEmpty()) {
      String home = profiles.requireCurrency(tenantId);
      return new com.storeql.pricing.dto.Dtos.CurrenciesResponse(
          home, java.util.List.of(home), java.util.List.of());
    }
    java.util.List<com.storeql.pricing.dto.Dtos.DisplayRateResponse> rates =
        table.get().rates().values().stream()
            .sorted(java.util.Comparator.comparing(com.storeql.service.Fx.Rate::currency))
            .map(r -> new com.storeql.pricing.dto.Dtos.DisplayRateResponse(r.currency(), r.rate()))
            .toList();
    return new com.storeql.pricing.dto.Dtos.CurrenciesResponse(
        table.get().home(), fx.currencies(tenantId), rates);
  }

  /**
   * The price as it stood at {@code at} (03.12), rebuilt from what had been made, switched and
   * priced by then: the applied-price ledger evaluates each change after it commits, and must not
   * read a later list, promotion, scope, switch, list price or VAT assignment into an earlier
   * moment.
   */
  public ResolvedPrice resolveAsRecorded(
      UUID tenantId, ResolvePriceRequest req, boolean withPromotions, Instant at) {
    return price(tenantId, req, withPromotions, at, false, true);
  }

  private ResolvedPrice price(
      UUID tenantId,
      ResolvePriceRequest req,
      boolean withPromotions,
      Instant at,
      boolean withPriorPrice,
      boolean asRecorded) {
    UUID variantId = Ids.parse(req.variantId());
    BigDecimal qty = req.qty() != null ? req.qty() : BigDecimal.ONE;
    String channel =
        req.channel() != null
            ? req.channel().toUpperCase(java.util.Locale.ROOT)
            : PriceList.CHANNEL_ALL;

    // SJ-D55: a quantity tier is a volume price, never a reason a fraction of a unit has no price.
    // A weighed line arrives as its weight (0.375 kg), and the list price's minimum quantity is 1,
    // so a fraction is matched as one; a list holding only a bulk tier still refuses a single item.
    // 03.x: the store decides which price list answers — its price zone's, or the tenant-wide one.
    UUID storeId =
        req.storeId() == null || req.storeId().isBlank()
            ? null
            : Parsing.uuid(req.storeId(), "storeId");
    PriceListItem baseItem =
        (asRecorded
                ? repo.resolveBasePriceAsOf(
                    tenantId, variantId, channel, qty.max(BigDecimal.ONE), at, storeId)
                : repo.resolveBasePrice(
                    tenantId, variantId, channel, qty.max(BigDecimal.ONE), at, storeId))
            .orElseThrow(
                () ->
                    ApiException.notFound(
                        "PRICING_PRICE_NOT_FOUND",
                        "no active price configured for variant " + req.variantId()));

    BigDecimal unitPrice = baseItem.price();
    String promoApplied = null;
    // The price list's own currency; the tenant's when the list is gone — never a literal (SJ-D53).
    // Its minor units decide every rounding below: whole yen, pence, three-decimal dinars.
    Optional<PriceList> priceList = repo.findPriceList(tenantId, baseItem.priceListId());
    String currency =
        priceList.map(PriceList::currency).orElseGet(() -> profiles.requireCurrency(tenantId));
    boolean inclusive = priceList.map(PriceList::taxInclusive).orElse(false);
    int scale = com.storeql.service.Fx.minorUnits(currency);

    // A single-variant quote runs the same engine as the basket path, on a basket of one, with
    // the basket-level promotions filtered out. They are excluded deliberately rather than for
    // convenience: this endpoint answers "what does this item cost" for a product page, and
    // showing a spend-threshold price against one item advertises a total the shopper will not be
    // charged. The checkout path calls quoteBasket, where the threshold can actually be tested.
    List<Promotion> candidates =
        (withPromotions
                ? (asRecorded
                    ? repo.findCandidatePromotionsAsOf(tenantId, storeId, channel, at)
                    : repo.findCandidatePromotions(tenantId, storeId, channel, at))
                : List.<Promotion>of())
            .stream()
                .filter(p -> !p.isBasketLevel())
                // A coupon promotion is not applied to a browsing price: the customer has not
                // presented it, and this endpoint takes no codes.
                .filter(p -> !p.requiresCoupon())
                .toList();
    if (!candidates.isEmpty()) {
      var scopes =
          repo.findPromotionVariantScopes(
              tenantId, candidates.stream().map(Promotion::id).toList(), asRecorded ? at : null);
      var outcome =
          engine.apply(
              List.of(new BasketLine(variantId, qty, unitPrice)),
              candidates,
              scopes,
              List.of(),
              Map.of(),
              scale);
      BigDecimal off = outcome.totalDiscount();
      if (off.signum() > 0) {
        BigDecimal lineTotal = unitPrice.multiply(qty);
        unitPrice =
            lineTotal.subtract(off).max(BigDecimal.ZERO).divide(qty, scale, RoundingMode.HALF_UP);
        promoApplied =
            outcome.lineDiscounts().stream()
                .map(com.storeql.pricing.domain.Domain.LineDiscount::promotionName)
                .distinct()
                .collect(java.util.stream.Collectors.joining(" + "));
      }
    }

    String vatCode = vatCodeFor(tenantId, variantId, asRecorded ? at : null, inclusive);
    VatRate vatRate = rateFor(tenantId, vatCode, asRecorded ? at : null);
    BigDecimal vatAmount;
    BigDecimal totalWithVat;
    if (inclusive) {
      // The shelf price is the price: what the customer pays is what the list says (less any
      // promotion), and the VAT in it is derived from it, never the other way round.
      totalWithVat = unitPrice.setScale(scale, RoundingMode.HALF_UP);
      vatAmount =
          TaxInclusive.vatInside(
              totalWithVat, vatRate.exempt() ? BigDecimal.ZERO : vatRate.rate(), scale);
      unitPrice = totalWithVat.subtract(vatAmount);
    } else {
      vatAmount = vatOn(unitPrice, vatRate, scale);
      totalWithVat = unitPrice.add(vatAmount);
    }

    // 03.13: the unit price of what the shopper pays — VAT and any promotion in — per kilogram,
    // litre, metre, square metre or item. Shown whenever the measure is declared; whether it is
    // law here only decides whether its absence is a gap.
    var unitPricing =
        UnitPricing.of(totalWithVat, repo.findMeasure(tenantId, variantId).orElse(null), currency);
    // The ledger records prices, not what may be said about them: the law is read only for a
    // shopper.
    PriorPrices.Rules rules =
        asRecorded ? PriorPrices.Rules.STRICT : reductionRules(tenantId, storeId);
    return new ResolvedPrice(
            variantId,
            unitPrice,
            vatCode,
            vatRate.rate(),
            vatAmount,
            totalWithVat,
            currency,
            baseItem.priceListId(),
            promoApplied,
            unitPricing,
            asRecorded || unitPriceRequired(tenantId, storeId),
            withPriorPrice && promoApplied != null
                ? appliedPrices.priorPrice(
                    tenantId,
                    variantId,
                    channel,
                    storeId,
                    totalWithVat,
                    resolveAt(tenantId, req, false, at, false).totalWithVat(),
                    at,
                    rules.progressive())
                : null,
            rules.required())
        .withTaxInclusive(inclusive);
  }

  /** A variant's VAT code; with {@code asOf}, as assigned by then. The standard code when none. */
  private String vatCodeFor(UUID tenantId, UUID variantId, Instant asOf) {
    return vatCodeFor(tenantId, variantId, asOf, false);
  }

  /**
   * As above; with {@code required}, a variant with no category is refused rather than charged at
   * the standard rate. A tax-inclusive price's VAT is worked from the rate and printed on a
   * receipt, so a guessed rate is a wrong receipt.
   *
   * @throws ApiException 409 {@code PRICING_VAT_CATEGORY_MISSING} when required and absent
   */
  private String vatCodeFor(UUID tenantId, UUID variantId, Instant asOf, boolean required) {
    var found = repo.findProductVatCategory(tenantId, variantId, asOf);
    if (found.isEmpty() && required) {
      throw ApiException.conflict(
          "PRICING_VAT_CATEGORY_MISSING",
          "variant "
              + variantId
              + " has no VAT category, so the VAT in its shelf price cannot be worked out; give"
              + " it one under Pricing, VAT readiness");
    }
    return found.map(ProductVatCategory::vatCode).orElse(VatRate.T1);
  }

  /**
   * The rate a VAT code carries; with {@code asOf}, as configured by then. Never a literal rate
   * (SJ-D56): a business that charges no VAT sets its standard rate exempt, and until a rate is set
   * nothing is quoted.
   *
   * @throws ApiException 409 {@code PRICING_VAT_RATE_NOT_CONFIGURED} when the code has no rate
   */
  private VatRate rateFor(UUID tenantId, String vatCode, Instant asOf) {
    return repo.findVatRate(tenantId, vatCode, asOf)
        .orElseThrow(
            () ->
                ApiException.conflict(
                    VAT_RATE_NOT_CONFIGURED,
                    "no VAT rate is configured for code "
                        + vatCode
                        + "; set this business's rate under Pricing, VAT rates — exempt if it"
                        + " charges no VAT"));
  }

  /**
   * The VAT on a net amount, rounded half up to the currency's minor units ({@code scale}); the
   * rate itself is kept as configured.
   */
  static BigDecimal vatOn(BigDecimal net, VatRate rate, int scale) {
    return rate.exempt()
        ? BigDecimal.ZERO
        : net.multiply(rate.rate()).setScale(scale, RoundingMode.HALF_UP);
  }

  /**
   * What art.6a asks of an offer today (03.12), from every country whose law reaches it: the
   * business's own and, at a store, that store's — with no store, every store's. Its prior price
   * binds when any of them binds it; a member-state option applies only when every country that
   * binds has taken it up. When the rules cannot be read, the strictest reading: a reduction is
   * never announced on a guess that the law does not apply.
   */
  PriorPrices.Rules reductionRules(UUID tenantId, UUID storeId) {
    try {
      java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
      List<String> bound = new java.util.ArrayList<>();
      for (String country : jurisdictions.countriesTrading(tenantId, storeId)) {
        if (jurisdictions.inForceIn(
            tenantId, country, PriorPrices.PRICE_REDUCTION_PRIOR_PRICE, today)) {
          bound.add(country);
        }
      }
      if (bound.isEmpty()) return PriorPrices.Rules.NOT_BOUND;
      return new PriorPrices.Rules(
          true,
          takenUpEverywhere(tenantId, bound, PriorPrices.PRICE_REDUCTION_PROGRESSIVE, today),
          takenUpEverywhere(tenantId, bound, PriorPrices.PRICE_REDUCTION_PERISHABLE_EXEMPT, today));
    } catch (ApiException e) {
      return PriorPrices.Rules.STRICT;
    }
  }

  private boolean takenUpEverywhere(
      UUID tenantId, List<String> countries, String option, java.time.LocalDate day) {
    return countries.stream().allMatch(c -> jurisdictions.inForceIn(tenantId, c, option, day));
  }

  /**
   * Whether a unit price is law for an offer today, in any country whose law reaches it. When the
   * rules cannot be read it is taken to be — a price is never refused over it, and a missing unit
   * price is never excused.
   */
  boolean unitPriceRequired(UUID tenantId, UUID storeId) {
    try {
      return jurisdictions.inForceWhereTrading(
          tenantId,
          storeId,
          UnitPricing.UNIT_PRICING,
          java.time.LocalDate.now(java.time.ZoneOffset.UTC));
    } catch (ApiException e) {
      return true;
    }
  }

  /**
   * What a reduced-price sticker may say at the till (03.12). Where art.6a does not bind, or binds
   * but every country it binds in exempts short-dated goods and the sticker is for that, the price
   * it was reduced from. Otherwise only the prior price the ledger proves for the variant at the
   * till, against the sticker's price with VAT; none when it proves none.
   */
  public com.storeql.pricing.domain.Domain.MarkdownReduction markdownReduction(
      UUID tenantId, com.storeql.pricing.domain.Domain.Markdown m) {
    PriorPrices.Rules rules = reductionRules(tenantId, m.storeId());
    boolean exempt =
        rules.required()
            && rules.perishableExempt()
            && com.storeql.pricing.domain.Domain.Markdown.REASON_SHORT_DATED.equals(m.reason());
    if (!rules.required() || exempt) {
      return new com.storeql.pricing.domain.Domain.MarkdownReduction(
          m.originalPrice(), null, null, rules.required(), exempt);
    }
    Instant now = Instant.now();
    var req =
        new ResolvePriceRequest(
            m.variantId().toString(),
            m.storeId() == null ? null : m.storeId().toString(),
            PriceList.CHANNEL_POS,
            BigDecimal.ONE,
            null,
            null);
    BigDecimal regular;
    try {
      regular = resolveAt(tenantId, req, false, now, false).totalWithVat();
    } catch (ApiException e) {
      if (e.status() != 404) throw e;
      return new com.storeql.pricing.domain.Domain.MarkdownReduction(
          null, null, PriorPrices.NO_HISTORY, true, false);
    }
    BigDecimal gross;
    if (m.taxInclusive()) {
      gross = m.markdownPrice();
    } else {
      VatRate rate = rateFor(tenantId, vatCodeFor(tenantId, m.variantId(), null), null);
      gross =
          m.markdownPrice()
              .add(vatOn(m.markdownPrice(), rate, com.storeql.service.Fx.minorUnits(m.currency())));
    }
    var prior =
        appliedPrices.priorPrice(
            tenantId,
            m.variantId(),
            PriceList.CHANNEL_POS,
            m.storeId(),
            gross,
            regular,
            now,
            rules.progressive());
    return new com.storeql.pricing.domain.Domain.MarkdownReduction(
        PriorPrices.ANNOUNCEABLE.equals(prior.status()) ? prior.priorPriceNet() : null,
        prior.priorPrice(),
        prior.status(),
        true,
        false);
  }

  /**
   * Whether the storefront may advertise a promotion as a reduction (03.12). Art.6a governs the
   * announcement of a reduced price: an unconditional percentage or amount off an item. A basket
   * threshold, a coupon or a multi-buy is a condition, not a reduced price. Where art.6a binds, an
   * item promotion is advertised only while every reduced price on the storefront can be announced
   * with its prior price — a banner cannot say "20% off" over a product whose own page may not call
   * its price reduced.
   */
  public boolean advertisable(UUID tenantId, Promotion p) {
    boolean itemReduction =
        (Promotion.TYPE_PERCENT.equals(p.type()) || Promotion.TYPE_FLAT.equals(p.type()))
            && !p.requiresCoupon();
    if (!itemReduction) return true;
    if (!reductionRules(tenantId, p.storeId()).required()) return true;
    return appliedPrices.reductionsAnnounceable(tenantId, store -> reductionRules(tenantId, store));
  }

  /** The reductions on offer on a channel, each with its prior price (03.12). */
  public List<com.storeql.pricing.domain.Domain.Reduction> reductions(
      UUID tenantId, String channel) {
    return appliedPrices.reductions(
        tenantId,
        channel,
        store -> reductionRules(tenantId, store),
        AppliedPriceService.REDUCTIONS_LIMIT);
  }

  /**
   * Shelf-edge labels (03.13): for each variant, the regular price and its unit price and, while a
   * promotion applies at that store and channel, the promotional price and its unit price.
   *
   * @throws ApiException 400 {@code PRICING_LABELS_INVALID} for no variants, more than 200, or a
   *     variant id that is not one
   */
  public List<com.storeql.pricing.domain.Domain.ShelfLabel> shelfLabels(
      List<String> variantIds, String storeId, String channel, TenantContext ctx) {
    if (variantIds == null || variantIds.isEmpty() || variantIds.size() > 200) {
      throw ApiException.badRequest("PRICING_LABELS_INVALID", "variantIds lists 1 to 200 variants");
    }
    UUID tenantId = ctx.tenantId();
    UUID store = storeId == null || storeId.isBlank() ? null : Parsing.uuid(storeId, "storeId");
    boolean required = unitPriceRequired(tenantId, store);
    List<com.storeql.pricing.domain.Domain.ShelfLabel> out = new java.util.ArrayList<>();
    for (String id : variantIds.stream().distinct().toList()) {
      UUID variantId = Parsing.uuid(id, "variantIds");
      var regularReq = new ResolvePriceRequest(id, storeId, channel, BigDecimal.ONE, null, null);
      ResolvedPrice regular;
      try {
        regular = resolve(regularReq, ctx, false);
      } catch (ApiException e) {
        if (e.status() != 404) throw e;
        out.add(
            new com.storeql.pricing.domain.Domain.ShelfLabel(
                variantId,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                repo.findMeasure(tenantId, variantId).isPresent(),
                required,
                null,
                null,
                reductionRules(tenantId, store).required()));
        continue;
      }
      ResolvedPrice offered = resolve(regularReq, ctx, true);
      boolean promoted =
          offered.promotionApplied() != null
              && offered.totalWithVat().compareTo(regular.totalWithVat()) != 0;
      out.add(
          new com.storeql.pricing.domain.Domain.ShelfLabel(
              variantId,
              true,
              regular.currency(),
              regular.totalWithVat(),
              regular.unitPricing(),
              promoted ? offered.totalWithVat() : null,
              promoted ? offered.unitPricing() : null,
              promoted ? offered.promotionApplied() : null,
              regular.unitPricing() != null,
              required,
              promoted && offered.priorPrice() != null ? offered.priorPrice().priorPrice() : null,
              promoted && offered.priorPrice() != null ? offered.priorPrice().status() : null,
              offered.priorPriceRequired()));
    }
    return out;
  }

  /** Priced variants with no declared measure, and whether a unit price is law here. */
  public com.storeql.pricing.domain.Domain.UnitPriceGaps unitPriceGaps(UUID tenantId) {
    return new com.storeql.pricing.domain.Domain.UnitPriceGaps(
        unitPriceRequired(tenantId, null), repo.unitPriceGaps(tenantId, 500));
  }

  /**
   * Resolves many lines in one call. Each line is still resolved independently (same DB reads as
   * {@link #resolvePrice}), but collapsing this into one service call removes the per-line HTTP
   * round trip (and circuit-breaker/retry overhead) a caller like order-svc's checkout would
   * otherwise pay once per order line.
   *
   * <p>Independent resolution also means this cannot see the basket: it applies no basket-level or
   * coupon promotion, exactly as {@link #resolvePrice} does not.
   *
   * @param reqs the lines to price
   * @param ctx caller context; supplies the tenant
   * @return one resolved price per request, in the order supplied
   * @throws ApiException {@code PRICING_PRICE_NOT_FOUND} (404) as soon as any line has no active
   *     price — the whole call fails rather than returning a partial list
   */
  public java.util.List<ResolvedPrice> resolvePrices(
      java.util.List<ResolvePriceRequest> reqs, TenantContext ctx) {
    return reqs.stream().map(r -> resolvePrice(r, ctx)).toList();
  }

  // ── Basket quoting ────────────────────────────────────────────────────────

  /**
   * One basket line's share of the discount the engine computed for its variant.
   *
   * <p>Split by line value, with the variant's last line taking the remainder, so the shares sum to
   * exactly the engine's figure. A variant appearing on a single line — the ordinary case — takes
   * the whole thing and this behaves exactly as it did before.
   *
   * @param variantId the line's variant
   * @param index this line's position in the basket
   * @param lineTotal this line's value before any discount
   * @param perVariantDiscount the engine's total discount per variant
   * @param variantLineValue total value of all lines carrying each variant
   * @param lastLineOfVariant index of the final line carrying each variant
   * @param taken running total already apportioned per variant; updated here
   * @param scale the basket currency's minor units
   * @return this line's share
   */
  private static BigDecimal shareOfVariantDiscount(
      UUID variantId,
      int index,
      BigDecimal lineTotal,
      Map<UUID, BigDecimal> perVariantDiscount,
      Map<UUID, BigDecimal> variantLineValue,
      Map<UUID, Integer> lastLineOfVariant,
      Map<UUID, BigDecimal> taken,
      int scale) {
    BigDecimal variantDiscount = perVariantDiscount.getOrDefault(variantId, BigDecimal.ZERO);
    if (variantDiscount.signum() == 0) {
      return BigDecimal.ZERO;
    }
    BigDecimal variantValue = variantLineValue.getOrDefault(variantId, BigDecimal.ZERO);
    boolean last = Integer.valueOf(index).equals(lastLineOfVariant.get(variantId));
    if (last || variantValue.signum() == 0) {
      // The remainder, so rounding never loses or invents a penny. A zero-value variant cannot be
      // split by value at all, so its whole discount lands on the last line.
      return variantDiscount.subtract(taken.getOrDefault(variantId, BigDecimal.ZERO));
    }
    BigDecimal share =
        variantDiscount.multiply(lineTotal).divide(variantValue, scale, RoundingMode.HALF_UP);
    taken.merge(variantId, share, BigDecimal::add);
    return share;
  }

  /**
   * Prices a whole basket, promotions and VAT included.
   *
   * <p>This is the method the old engine could not have had. {@code resolvePrices} was {@code
   * lines.stream().map(resolvePrice)} — each line priced in isolation — so a rule that needed to
   * see the order total had nowhere to stand. Spend thresholds, basket percentages and
   * buy-one-get-one were not merely unimplemented; there was no object for them to be about.
   *
   * <p>VAT is computed per line on the discounted amount, and the basket-level discount is
   * apportioned across the lines by value before that happens — otherwise a £10-off-the-basket
   * promotion would be VAT-free money, which it is not. Apportionment is by value rather than
   * evenly because lines can sit at different rates, and the zero-rated line must not absorb a
   * share of relief that belongs to the standard-rated one.
   *
   * @param req the lines, channel, store, customer and any coupon codes the customer presented;
   *     codes that do not apply come back in {@code rejectedCoupons} with a reason rather than
   *     being silently dropped
   * @param ctx caller context; supplies the tenant
   * @return the priced lines with subtotal, discounts, VAT, total and the promotions applied
   * @throws ApiException {@code PRICING_INVALID_QTY} (400) when a line's quantity is not positive;
   *     {@code PRICING_PRICE_NOT_FOUND} (404) when a variant has no active price
   */
  public QuoteBasketResponse quoteBasket(QuoteBasketRequest req, TenantContext ctx) {
    UUID tenantId = ctx.tenantId();
    String channel =
        req.channel() != null
            ? req.channel().toUpperCase(java.util.Locale.ROOT)
            : PriceList.CHANNEL_ALL;
    UUID storeId =
        req.storeId() == null || req.storeId().isBlank()
            ? null
            : Parsing.uuid(req.storeId(), "storeId");
    UUID customerId =
        req.customerId() == null || req.customerId().isBlank()
            ? null
            : Parsing.uuid(req.customerId(), "customerId");

    // 1. Base prices, one resolution per line.
    //
    // A line a reduced-price sticker was scanned for (05.4) is priced at the sticker, not the
    // list: the markdown is checked as the till would check it — live, in date, this variant, this
    // store, packs left — and the line then stands outside the promotion engine altogether. A
    // sticker is already the reduction; a promotion on top would sell short-dated stock below
    // what the ladder decided.
    List<BasketLine> basket = new java.util.ArrayList<>();
    List<UUID> lineMarkdowns = new java.util.ArrayList<>();
    List<String> vatCodes = new java.util.ArrayList<>();
    List<Boolean> lineInclusive = new java.util.ArrayList<>();
    Map<UUID, Optional<PriceList>> listsSeen = new HashMap<>();
    String currency = null;
    for (var l : req.lines()) {
      UUID variantId = Parsing.uuid(l.variantId(), "variantId");
      BigDecimal qty = l.qty() != null ? l.qty() : BigDecimal.ONE;
      if (qty.signum() <= 0)
        throw ApiException.badRequest(
            "PRICING_INVALID_QTY", "qty must be greater than zero for variant " + l.variantId());
      UUID markdownId = Parsing.optionalUuid(l.markdownId(), "markdownId");
      boolean inclusiveLine;
      if (markdownId != null) {
        var md = markdowns.forQuoteLine(tenantId, markdownId, variantId, storeId, qty);
        basket.add(new BasketLine(variantId, qty, md.markdownPrice()));
        if (currency == null) currency = md.currency();
        inclusiveLine = md.taxInclusive();
      } else {
        var baseItem =
            // SJ-D55: a fraction of a unit is matched against the tiers as one.
            repo.resolveBasePrice(
                    tenantId, variantId, channel, qty.max(BigDecimal.ONE), Instant.now(), storeId)
                .orElseThrow(
                    () ->
                        ApiException.notFound(
                            "PRICING_PRICE_NOT_FOUND",
                            "no active price configured for variant " + l.variantId()));
        basket.add(new BasketLine(variantId, qty, baseItem.price()));
        Optional<PriceList> list =
            listsSeen.computeIfAbsent(
                baseItem.priceListId(), id -> repo.findPriceList(tenantId, id));
        if (currency == null) currency = list.map(PriceList::currency).orElse(null);
        inclusiveLine = list.map(PriceList::taxInclusive).orElse(false);
      }
      lineMarkdowns.add(markdownId);
      lineInclusive.add(inclusiveLine);
      vatCodes.add(vatCodeFor(tenantId, variantId, null, inclusiveLine));
    }

    // A basket is priced in one mode: its prices are either shelf prices or net, never a sum of
    // both. Mixed lists are refused when they are made, so this is a basket that crossed a switch.
    boolean inclusive = lineInclusive.contains(Boolean.TRUE);
    if (inclusive && lineInclusive.contains(Boolean.FALSE)) {
      throw ApiException.conflict(
          "PRICING_TAX_MODE_MIXED",
          "this basket draws on both tax-inclusive and net price lists; a business prices one way");
    }

    // The basket's currency, whose minor units every amount below is rounded to: whole yen,
    // pence, three-decimal dinars.
    String basketCurrency =
        currency != null ? currency : profiles.requireCurrency(ctx.requireTenantId());
    int scale = com.storeql.service.Fx.minorUnits(basketCurrency);

    // 2. Promotions, over the lines a sticker did not already price.
    List<BasketLine> promotable = new java.util.ArrayList<>();
    int lastPromotable = -1;
    for (int i = 0; i < basket.size(); i++) {
      if (lineMarkdowns.get(i) == null) {
        promotable.add(basket.get(i));
        lastPromotable = i;
      }
    }
    List<Promotion> candidates =
        repo.findCandidatePromotions(tenantId, storeId, channel, Instant.now());
    var scopes =
        repo.findPromotionVariantScopes(tenantId, candidates.stream().map(Promotion::id).toList());
    var exhausted = repo.findExhaustedPromotions(tenantId, candidates, customerId);
    var outcome = engine.apply(promotable, candidates, scopes, req.couponCodes(), exhausted, scale);

    // 3. Fold the line discounts back onto their lines.
    //
    // Both BasketLine and LineDiscount are keyed by variantId, so the engine cannot tell two basket
    // lines of the same variant apart and returns one combined figure for them. Applying that
    // figure to each line — which is what getOrDefault(variantId) does — charged the discount once
    // per line: the response's own lines then contradicted its subtotal and totalDiscount, and
    // order-svc, which derives the stored unit price from lineTotal minus discount, undercharged.
    //
    // The variant's discount is therefore split across its lines by value, with the last line of
    // that variant taking the rounding remainder — the same apportionment this method already uses
    // for the basket discount below, and for the same reason: the parts must sum to exactly the
    // whole rather than a penny either side.
    Map<UUID, BigDecimal> perLineDiscount = new java.util.LinkedHashMap<>();
    for (var d : outcome.lineDiscounts())
      perLineDiscount.merge(d.variantId(), d.amount(), BigDecimal::add);

    Map<UUID, BigDecimal> variantLineValue = new java.util.LinkedHashMap<>();
    Map<UUID, Integer> lastLineOfVariant = new java.util.LinkedHashMap<>();
    for (int i = 0; i < basket.size(); i++) {
      if (lineMarkdowns.get(i) != null) continue;
      BasketLine b = basket.get(i);
      variantLineValue.merge(
          b.variantId(),
          b.unitPrice().multiply(b.qty()).setScale(scale, RoundingMode.HALF_UP),
          BigDecimal::add);
      lastLineOfVariant.put(b.variantId(), i);
    }
    Map<UUID, BigDecimal> variantDiscountTaken = new java.util.LinkedHashMap<>();

    BigDecimal subtotal =
        basket.stream()
            .map(b -> b.unitPrice().multiply(b.qty()))
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(scale, RoundingMode.HALF_UP);
    BigDecimal basketDiscount =
        outcome.basketDiscounts().stream()
            .map(com.storeql.pricing.domain.Domain.LineDiscount::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal promotableSubtotal =
        promotable.stream()
            .map(b -> b.unitPrice().multiply(b.qty()))
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(scale, RoundingMode.HALF_UP);
    BigDecimal afterLine =
        promotableSubtotal.subtract(
            perLineDiscount.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));

    // 4. Per-line VAT, on each line's share of what is left.
    //
    // A net basket works on net amounts and adds VAT to them. A tax-inclusive basket works on shelf
    // amounts, and the VAT in what each line finally costs is taken out of it, one division to the
    // currency's minor units, so a shelf price of 1.29 is 1.29 on the receipt and its VAT is 0.22
    // (intent/vat-inclusive-pricing.md).
    int lines = basket.size();
    BigDecimal[] lineTotals = new BigDecimal[lines];
    BigDecimal[] lineDiscounts = new BigDecimal[lines];
    for (int i = 0; i < lines; i++) {
      BasketLine b = basket.get(i);
      lineTotals[i] = b.unitPrice().multiply(b.qty()).setScale(scale, RoundingMode.HALF_UP);
      lineDiscounts[i] =
          lineMarkdowns.get(i) != null
              ? BigDecimal.ZERO
              : shareOfVariantDiscount(
                      b.variantId(),
                      i,
                      lineTotals[i],
                      perLineDiscount,
                      variantLineValue,
                      lastLineOfVariant,
                      variantDiscountTaken,
                      scale)
                  .min(lineTotals[i]);
    }
    List<BigDecimal> grossShares =
        inclusive
            ? basketSharesByGross(basketDiscount, lineTotals, lineDiscounts, lineMarkdowns, scale)
            : null;

    List<QuoteLineResponse> lineResponses = new java.util.ArrayList<>();
    BigDecimal vatTotal = BigDecimal.ZERO;
    BigDecimal grossTotal = BigDecimal.ZERO;
    BigDecimal apportioned = BigDecimal.ZERO;
    Map<String, BigDecimal[]> byRate = new java.util.LinkedHashMap<>();
    Map<String, BigDecimal> rateOf = new HashMap<>();
    for (int i = 0; i < lines; i++) {
      BasketLine b = basket.get(i);
      BigDecimal lineTotal = lineTotals[i];
      BigDecimal lineDisc = lineDiscounts[i];
      boolean stickered = lineMarkdowns.get(i) != null;
      VatRate rate = vatRateFor(tenantId, vatCodes.get(i));
      BigDecimal rateValue = rate.exempt() ? BigDecimal.ZERO : rate.rate();
      BigDecimal net;
      BigDecimal vat;
      BigDecimal gross;
      if (inclusive) {
        gross = lineTotal.subtract(lineDisc).subtract(grossShares.get(i)).max(BigDecimal.ZERO);
        vat = TaxInclusive.vatInside(gross, rateValue, scale);
        net = gross.subtract(vat);
      } else {
        net = lineTotal.subtract(lineDisc);
        // The basket discount is shared by value. The last line takes the rounding remainder, so
        // the apportioned parts always sum to exactly the discount rather than a penny either side.
        BigDecimal share;
        if (stickered || basketDiscount.signum() == 0 || afterLine.signum() <= 0) {
          share = BigDecimal.ZERO;
        } else if (i == lastPromotable) {
          share = basketDiscount.subtract(apportioned);
        } else {
          share = basketDiscount.multiply(net).divide(afterLine, scale, RoundingMode.HALF_UP);
          apportioned = apportioned.add(share);
        }
        net = net.subtract(share).max(BigDecimal.ZERO);
        vat = vatOn(net, rate, scale);
        gross = net.add(vat);
      }
      vatTotal = vatTotal.add(vat);
      grossTotal = grossTotal.add(gross);
      BigDecimal[] sums =
          byRate.computeIfAbsent(
              vatCodes.get(i),
              k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
      sums[0] = sums[0].add(gross);
      sums[1] = sums[1].add(net);
      sums[2] = sums[2].add(vat);
      rateOf.put(vatCodes.get(i), rateValue);

      BigDecimal paidPerOne = gross.divide(b.qty(), 6, RoundingMode.HALF_UP);
      var unitPricing =
          UnitPricing.of(
              paidPerOne, repo.findMeasure(tenantId, b.variantId()).orElse(null), basketCurrency);
      lineResponses.add(
          new QuoteLineResponse(
              b.variantId(),
              b.qty(),
              b.unitPrice(),
              lineTotal,
              lineDisc,
              net,
              vat,
              vatCodes.get(i),
              rateValue,
              lineMarkdowns.get(i),
              com.storeql.pricing.mapper.Mappers.toUnitPrice(unitPricing),
              gross));
    }

    BigDecimal totalDiscount = outcome.totalDiscount();
    BigDecimal total = inclusive ? grossTotal : subtotal.subtract(totalDiscount).add(vatTotal);
    List<com.storeql.pricing.dto.Dtos.VatRateTotalResponse> vatByRate =
        byRate.entrySet().stream()
            .map(
                e ->
                    new com.storeql.pricing.dto.Dtos.VatRateTotalResponse(
                        e.getKey(),
                        rateOf.get(e.getKey()),
                        e.getValue()[0],
                        e.getValue()[1],
                        e.getValue()[2]))
            .toList();

    List<AppliedPromotionResponse> appliedResponses = new java.util.ArrayList<>();
    for (var d :
        java.util.stream.Stream.concat(
                outcome.lineDiscounts().stream(), outcome.basketDiscounts().stream())
            .toList()) {
      appliedResponses.add(
          new AppliedPromotionResponse(
              d.promotionId(), d.promotionName(), d.variantId(), d.amount()));
    }

    String wanted = displayCurrency(req.displayCurrency());
    com.storeql.pricing.dto.Dtos.DisplayBasketResponse display = null;
    if (wanted != null) {
      display =
          new com.storeql.pricing.dto.Dtos.DisplayBasketResponse(
              wanted,
              fxRateFor(tenantId, basketCurrency, wanted),
              convertForDisplay(tenantId, subtotal, basketCurrency, wanted),
              convertForDisplay(tenantId, totalDiscount, basketCurrency, wanted),
              convertForDisplay(tenantId, vatTotal, basketCurrency, wanted),
              convertForDisplay(tenantId, total, basketCurrency, wanted));
    }
    return new QuoteBasketResponse(
        lineResponses,
        subtotal,
        totalDiscount,
        basketDiscount,
        vatTotal,
        total,
        basketCurrency,
        appliedResponses,
        outcome.rejectedCoupons(),
        display,
        inclusive,
        vatByRate);
  }

  /**
   * The basket discount's share of each line of a tax-inclusive basket, by what the line still
   * costs after its own discount, exact to the minor unit (the shares add up to the discount, and
   * no line gives more than it costs). A stickered line, already a reduced price, takes none.
   */
  private static List<BigDecimal> basketSharesByGross(
      BigDecimal basketDiscount,
      BigDecimal[] lineTotals,
      BigDecimal[] lineDiscounts,
      List<UUID> lineMarkdowns,
      int scale) {
    List<BigDecimal> bases = new java.util.ArrayList<>();
    BigDecimal held = BigDecimal.ZERO;
    for (int i = 0; i < lineTotals.length; i++) {
      BigDecimal base =
          lineMarkdowns.get(i) != null ? BigDecimal.ZERO : lineTotals[i].subtract(lineDiscounts[i]);
      bases.add(base);
      held = held.add(base);
    }
    BigDecimal amount = basketDiscount.setScale(scale, RoundingMode.HALF_UP).min(held);
    return TaxInclusive.shareByGross(amount, bases, scale);
  }

  /**
   * The tenant's rate for a VAT code, falling back to the standard rate the same way resolvePrice
   * does.
   */
  private VatRate vatRateFor(UUID tenantId, String vatCode) {
    return rateFor(tenantId, vatCode, null);
  }

  /**
   * Records that the promotions on a quote were used by an order.
   *
   * <p>Separate from quoting on purpose: a basket is quoted many times as a shopper adds items, and
   * a coupon must not be spent by looking at it. Only the checkout calls this, once the order
   * exists to attribute the redemption to.
   *
   * @param ctx caller context; supplies the tenant
   * @param orderId the order the redemptions are attributed to
   * @param customerId the redeeming customer, or {@code null} for a guest
   * @param applied the promotions the quote applied, summed per promotion here
   * @param currency the order's currency
   * @return how many redemptions this call actually recorded; a replay records none
   */
  public int recordRedemptions(
      TenantContext ctx,
      UUID orderId,
      UUID customerId,
      List<AppliedPromotionResponse> applied,
      String currency) {
    String cur = profiles.currencyOr(ctx.requireTenantId(), currency);
    Map<UUID, BigDecimal> perPromotion = new java.util.LinkedHashMap<>();
    for (var a : applied) perPromotion.merge(a.promotionId(), a.amount(), BigDecimal::add);
    int recorded = 0;
    for (var e : perPromotion.entrySet()) {
      if (repo.recordRedemption(ctx.tenantId(), e.getKey(), orderId, customerId, e.getValue(), cur))
        recorded++;
    }
    return recorded;
  }

  // ── Promotions ────────────────────────────────────────────────────────────

  /**
   * Creates a promotion, validating the shape its type requires.
   *
   * <p>The database enforces the same rules with CHECK constraints, deliberately: a half-configured
   * BOGO is a promotion that silently discounts nothing, which is the failure mode this whole
   * rebuild exists to end, and it must not be reachable however the row is written. What this adds
   * is the message — a constraint violation tells a caller only that something was wrong.
   *
   * @param req the promotion's type, value, window, scope-affecting settings and optional coupon
   * @param ctx caller context; supplies the tenant
   * @return the created promotion, active immediately
   * @throws ApiException {@code PRICING_INVALID_PROMOTION_TYPE}, {@code PRICING_INCOMPLETE_BOGO},
   *     {@code PRICING_INVALID_PROMOTION_SHAPE}, {@code PRICING_MISSING_THRESHOLD} or {@code
   *     PRICING_INVALID_PERCENT} (all 400) when the shape does not match the type
   */
  public Promotion createPromotion(CreatePromotionRequest req, TenantContext ctx) {
    String type = req.type().toUpperCase(java.util.Locale.ROOT);
    validatePromotionShape(type, req);

    // The business's currency is read only when something typed is money: an amount-valued type or
    // a spend threshold. A percentage promotion needs no currency, and so none to fail to read.
    String currency =
        Promotion.isAmountValued(type) || req.minOrderAmount() != null
            ? profiles.requireCurrency(ctx.requireTenantId())
            : null;

    Promotion p =
        new Promotion(
            Ids.newId(),
            ctx.tenantId(),
            req.storeId() != null ? Ids.parse(req.storeId()) : null,
            req.name(),
            type,
            // The value is money for the amount types: no finer than the currency.
            promotionValueIn(type, req.value(), currency),
            // A spend threshold is typed money in the business's own currency too.
            amountIn(req.minOrderAmount(), currency, "minOrderAmount"),
            req.channel() != null
                ? req.channel().toUpperCase(java.util.Locale.ROOT)
                : PriceList.CHANNEL_ALL,
            true,
            Parsing.instant(req.startsAt(), "startsAt"),
            req.endsAt() != null ? Parsing.instant(req.endsAt(), "endsAt") : null,
            Instant.now(),
            req.priority() != null ? req.priority() : 100,
            Boolean.TRUE.equals(req.exclusive()),
            req.couponCode() == null || req.couponCode().isBlank() ? null : req.couponCode().trim(),
            req.maxRedemptions(),
            req.maxPerCustomer(),
            req.buyQty(),
            req.getQty(),
            req.getDiscountPct());
    return repo.createPromotion(p, Events.promotionActivated(ctx.tenantId(), p.id()));
  }

  private static final java.util.Set<String> PROMOTION_TYPES =
      java.util.Set.of(
          Promotion.TYPE_PERCENT,
          Promotion.TYPE_FLAT,
          Promotion.TYPE_BASKET_PERCENT,
          Promotion.TYPE_BASKET_FLAT,
          Promotion.TYPE_SPEND_THRESHOLD,
          Promotion.TYPE_BOGO,
          Promotion.TYPE_MIX_MATCH);

  private static void validatePromotionShape(String type, CreatePromotionRequest req) {
    if (!PROMOTION_TYPES.contains(type))
      throw ApiException.badRequest(
          "PRICING_INVALID_PROMOTION_TYPE",
          "type must be one of " + PROMOTION_TYPES + " — got: " + type);

    // A limit is a count of uses: 1 or more, or absent for none. Zero or less would either never
    // apply or read as exhausted on its first quote, and is a slip, not a setting.
    if ((req.maxRedemptions() != null && req.maxRedemptions() < 1)
        || (req.maxPerCustomer() != null && req.maxPerCustomer() < 1))
      throw ApiException.badRequest(
          "PRICING_INVALID_LIMIT",
          "maxRedemptions and maxPerCustomer must be 1 or more, or left out for no limit");

    boolean bogo = Promotion.TYPE_BOGO.equals(type);
    if (bogo) {
      if (req.buyQty() == null || req.getQty() == null || req.getDiscountPct() == null)
        throw ApiException.badRequest(
            "PRICING_INCOMPLETE_BOGO",
            "BOGO requires buyQty, getQty and getDiscountPct — a partial one would apply to every"
                + " basket and discount nothing");
      if (req.buyQty().signum() <= 0 || req.getQty().signum() <= 0)
        throw ApiException.badRequest(
            "PRICING_INCOMPLETE_BOGO", "buyQty and getQty must both be greater than zero");
      if (req.getDiscountPct().signum() <= 0
          || req.getDiscountPct().compareTo(new BigDecimal("100")) > 0)
        throw ApiException.badRequest(
            "PRICING_INCOMPLETE_BOGO", "getDiscountPct must be between 0 and 100 (100 = free)");
    } else if (Promotion.TYPE_MIX_MATCH.equals(type)) {
      // "Any N for a price": buyQty is the bundle size and value the bundle price. A bundle of one
      // is a unit price, and a bundle with no size would apply to nothing.
      if (req.buyQty() == null
          || req.buyQty().compareTo(new BigDecimal("2")) < 0
          || req.buyQty().stripTrailingZeros().scale() > 0)
        throw ApiException.badRequest(
            "PRICING_INCOMPLETE_MIX_MATCH",
            "MIX_MATCH requires buyQty — the bundle size, a whole number of at least 2 — and value,"
                + " the bundle price");
      if (req.getQty() != null || req.getDiscountPct() != null)
        throw ApiException.badRequest(
            "PRICING_INVALID_PROMOTION_SHAPE",
            "getQty / getDiscountPct belong to a BOGO; a MIX_MATCH has a bundle size and a price");
    } else if (req.buyQty() != null || req.getQty() != null || req.getDiscountPct() != null) {
      throw ApiException.badRequest(
          "PRICING_INVALID_PROMOTION_SHAPE",
          "buyQty / getQty / getDiscountPct belong to a BOGO or a MIX_MATCH — got type " + type);
    }

    if (Promotion.TYPE_SPEND_THRESHOLD.equals(type) && req.minOrderAmount() == null)
      throw ApiException.badRequest(
          "PRICING_MISSING_THRESHOLD",
          "SPEND_THRESHOLD requires minOrderAmount — without one it discounts every basket");

    boolean percent =
        Promotion.TYPE_PERCENT.equals(type) || Promotion.TYPE_BASKET_PERCENT.equals(type);
    if (percent && req.value().compareTo(new BigDecimal("100")) > 0)
      throw ApiException.badRequest(
          "PRICING_INVALID_PERCENT",
          "a percentage promotion cannot exceed 100 — got " + req.value());
  }

  /**
   * Stops a promotion or a price list, or starts it again (SJ-D33).
   *
   * <p><code>active</code> has existed on both tables since V1 and the engine has always filtered
   * on it. Nothing ever wrote it, so a promotion created with no end date ran forever and could
   * only be stopped by reaching into the database. A discount nobody can switch off is the most
   * expensive version of the "declared column with no writer" shape this branch keeps finding.
   *
   * <p>A reason is required in both directions, not just for stopping. Turning a promotion back on
   * is the change more likely to be questioned later, and a trail that records why something was
   * stopped but not why it was restarted answers the easier half of the question.
   *
   * <p>Already-in-that-state is a 409 rather than a silent success: two people stopping the same
   * runaway promotion should not both be told they did it, and the trail must not gain a row for a
   * switch that did not move.
   *
   * @param ctx caller context; supplies the tenant and the user recorded against the change
   * @param subjectType {@code PROMOTION} or {@code PRICE_LIST}
   * @param id the promotion or price list to switch
   * @param active {@code true} to start it, {@code false} to stop it
   * @param req the reason, required in both directions
   * @return the recorded status change
   * @throws ApiException 400 if no reason is given; 404 if there is no such subject for this
   *     tenant; 409 {@code PRICING_ALREADY_IN_STATE} if it is already on or off as requested
   */
  public Domain.StatusChange setActive(
      TenantContext ctx, String subjectType, UUID id, boolean active, SetActiveRequest req) {
    UUID tenantId = ctx.requireTenantId();
    String table = Domain.StatusChange.PROMOTION.equals(subjectType) ? "promotions" : "price_lists";
    String reason = req == null || req.reason() == null ? null : req.reason().trim();
    if (reason == null || reason.isEmpty())
      throw ApiException.badRequest(
          "PRICING_REASON_REQUIRED",
          "say why — a promotion that stopped with no recorded reason is a discount that vanished"
              + " from the shop floor with nobody accountable");

    Boolean current = repo.findActive(table, tenantId, id);
    if (current == null)
      throw ApiException.notFound(
          "PRICING_SUBJECT_NOT_FOUND",
          subjectType.toLowerCase(java.util.Locale.ROOT) + " not found: " + id);

    Domain.StatusChange change =
        new Domain.StatusChange(
            Ids.newId(), tenantId, subjectType, id, active, reason, ctx.userId(), Instant.now());
    if (!repo.setActive(table, change))
      throw ApiException.conflict(
          "PRICING_ALREADY_IN_STATE",
          "already " + (active ? "active" : "inactive") + " — nothing to change");
    return change;
  }

  /**
   * The on/off history for one promotion or price list.
   *
   * @param ctx caller context; supplies the tenant
   * @param subjectType {@code PROMOTION} or {@code PRICE_LIST}
   * @param id the promotion or price list whose history to read
   * @return the recorded status changes
   */
  public List<Domain.StatusChange> statusChanges(TenantContext ctx, String subjectType, UUID id) {
    return repo.findStatusChanges(subjectType, ctx.requireTenantId(), id);
  }

  /**
   * Every currently active promotion in the tenant.
   *
   * <p>Filters on the {@code active} flag only — a promotion whose window has not opened, or has
   * closed, still appears here. The engine applies the date test when quoting.
   *
   * @param ctx caller context; supplies the tenant
   * @return the active promotions
   */
  public List<Promotion> listActivePromotions(TenantContext ctx) {
    return repo.findAllActivePromotions(ctx.tenantId());
  }

  /**
   * The promotions that touch a store since {@code from}, as windows in time for the demand
   * forecast in inventory-svc (06.x): each with its scope resolved to variants, and a promotion
   * that is off now ending when it was switched off rather than on its end date. A promotion whose
   * window closed before {@code from} is left out.
   *
   * @param ctx caller context; supplies the tenant
   * @param storeId the store
   * @param from the first moment of interest
   * @return the windows, earliest start first
   */
  public List<PromotionWindow> promotionWindows(TenantContext ctx, UUID storeId, Instant from) {
    UUID tenantId = ctx.tenantId();
    List<Promotion> promotions = repo.findPromotionsTouching(tenantId, storeId, from);
    List<UUID> ids = promotions.stream().map(Promotion::id).toList();
    Map<UUID, Set<UUID>> scopes = repo.findPromotionVariantScopes(tenantId, ids);
    Map<UUID, Instant> switchedOff = repo.findLastSwitchOff(tenantId, ids);
    List<PromotionWindow> out = new ArrayList<>();
    for (Promotion p : promotions) {
      Instant endsAt = p.endsAt();
      Instant off = p.active() ? null : switchedOff.get(p.id());
      if (off != null && (endsAt == null || off.isBefore(endsAt))) {
        endsAt = off;
      }
      if (endsAt != null && endsAt.isBefore(from)) {
        continue;
      }
      // Absent from the scopes means unscoped, which the engine reads as everything.
      Set<UUID> variants = scopes.get(p.id());
      out.add(
          new PromotionWindow(
              p.id(),
              p.storeId(),
              p.name(),
              p.type(),
              p.value(),
              p.channel(),
              p.active(),
              p.startsAt(),
              endsAt,
              variants == null ? Set.of() : variants,
              variants == null));
    }
    return out;
  }

  /**
   * Scopes a promotion to a variant, a category, or everything.
   *
   * <p>A CATEGORY scope resolves at quote time to the variants of every product whose category path
   * carries that category, through the catalogue product-svc announces, so a parent category
   * reaches its children's products. A category nothing has been announced for discounts nothing.
   *
   * @param ctx caller context; supplies the tenant
   * @param promotionId the promotion to scope
   * @param req the scope type ({@code VARIANT}, {@code CATEGORY} or {@code ALL}) and, for VARIANT
   *     and CATEGORY, the variant or category id
   * @return the stored scope row
   * @throws ApiException {@code PRICING_INVALID_SCOPE} (400) when the scope is unknown, or a
   *     VARIANT or CATEGORY scope names no id; {@code PRICING_SUBJECT_NOT_FOUND} (404) when the
   *     promotion is not this business's
   */
  public PromotionItem addPromotionItem(
      TenantContext ctx, UUID promotionId, AddPromotionItemRequest req) {
    String scopeType = req.scopeType().toUpperCase(java.util.Locale.ROOT);
    boolean needsId =
        PromotionItem.SCOPE_VARIANT.equals(scopeType)
            || PromotionItem.SCOPE_CATEGORY.equals(scopeType);
    if (!needsId && !PromotionItem.SCOPE_ALL.equals(scopeType))
      throw ApiException.badRequest(
          "PRICING_INVALID_SCOPE",
          "scopeType must be VARIANT, CATEGORY or ALL — got: " + scopeType);
    if (needsId && (req.scopeId() == null || req.scopeId().isBlank()))
      throw ApiException.badRequest(
          "PRICING_INVALID_SCOPE",
          "a "
              + scopeType
              + " scope needs a scopeId naming the "
              + scopeType.toLowerCase(java.util.Locale.ROOT));
    // A CATEGORY scope (03.8) resolves to variants at quote time through the catalogue product-svc
    // announces; a category nothing has been announced for discounts nothing, not everything.
    UUID scopeId = needsId ? Parsing.uuid(req.scopeId(), "scopeId") : null;
    PromotionItem pi =
        new PromotionItem(
            Ids.newId(), ctx.tenantId(), promotionId, scopeType, scopeId, Instant.now());
    PromotionItem stored = repo.addPromotionItem(pi);
    // A promotion that is not this business's is not found, whoever else's it is.
    if (stored == null)
      throw ApiException.notFound(
          "PRICING_SUBJECT_NOT_FOUND", "promotion not found: " + promotionId);
    return stored;
  }

  // ── Tax Transactions ──────────────────────────────────────────────────────

  /**
   * Records one line's tax position against an order, for the VAT return and tax summary.
   *
   * <p>Append-only: the figures are stamped as they stood at the tax point, so a later rate change
   * does not rewrite what was charged.
   *
   * @param req the order, line, variant, store, VAT code and rate, net/VAT/gross amounts, exempt
   *     flag, tax point and invoice reference
   * @param ctx caller context; supplies the tenant
   * @return the recorded transaction
   */
  public TaxTransaction recordTaxTransaction(RecordTaxTransactionRequest req, TenantContext ctx) {
    // The line's money in the business's own currency's minor units (half up): whole yen,
    // three-decimal dinars.
    int units = com.storeql.service.Fx.minorUnits(profiles.requireCurrency(ctx.requireTenantId()));
    TaxTransaction tt =
        new TaxTransaction(
            Ids.newId(),
            ctx.tenantId(),
            req.orderId(),
            req.orderLineId(),
            req.variantId(),
            req.storeId(),
            req.vatCode().toUpperCase(java.util.Locale.ROOT),
            req.vatRate(),
            req.netAmount() == null ? null : req.netAmount().setScale(units, RoundingMode.HALF_UP),
            req.vatAmount() == null ? null : req.vatAmount().setScale(units, RoundingMode.HALF_UP),
            req.grossAmount() == null
                ? null
                : req.grossAmount().setScale(units, RoundingMode.HALF_UP),
            req.exempt(),
            Parsing.instant(req.taxPointDate(), "taxPointDate"),
            req.invoiceRef(),
            Instant.now());
    return repo.recordTaxTransaction(tt);
  }

  /**
   * The tax lines recorded against one order.
   *
   * @param ctx caller context; supplies the tenant
   * @param orderId the order whose tax lines to read
   * @return the transactions, empty when none were recorded
   */
  public List<TaxTransaction> listTaxTransactionsByOrder(TenantContext ctx, UUID orderId) {
    return repo.findTaxTransactionsByOrder(ctx.tenantId(), orderId);
  }

  // ── MTD VAT Return ────────────────────────────────────────────────────────

  // ── Gap #41: Price overrides ──────────────────────────────────────────────

  /**
   * Records a manual price override against a store, and optionally an order.
   *
   * <p>An audit row, not a price change: it captures that someone sold at a different figure and
   * why. Nothing here alters the price list the override departed from.
   *
   * @param ctx caller context; supplies the tenant
   * @param req the variant, store, original and override prices, reason and who authorised it
   * @return the recorded override
   */
  public PriceOverride createPriceOverride(TenantContext ctx, CreatePriceOverrideRequest req) {
    UUID tenantId = ctx.requireTenantId();
    // What was charged, in the business's own currency: no finer than it, kept at its minor units
    // — whole yen, three-decimal dinars.
    String currency = profiles.requireCurrency(tenantId);
    var override =
        new PriceOverride(
            Ids.newId(),
            tenantId,
            req.orderId() != null ? Ids.parse(req.orderId()) : null,
            Ids.parse(req.variantId()),
            Ids.parse(req.storeId()),
            amountIn(req.originalPrice(), currency, "originalPrice"),
            amountIn(req.overridePrice(), currency, "overridePrice"),
            req.overrideReason(),
            req.overriddenBy() != null ? Ids.parse(req.overriddenBy()) : null,
            java.time.Instant.now());
    return repo.insertPriceOverride(override);
  }

  /**
   * Cursor-paginated price overrides (admin audit log).
   *
   * @param ctx caller context; supplies the tenant
   * @param storeIdStr restrict to one store, or {@code null} for all
   * @param variantIdStr restrict to one variant, or {@code null} for all
   * @param after cursor from the previous page, or {@code null} to start
   * @param limit page size
   * @return the page of overrides
   */
  public Cursor.Page<PriceOverride> listPriceOverrides(
      TenantContext ctx, String storeIdStr, String variantIdStr, String after, int limit) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = storeIdStr != null ? Ids.parse(storeIdStr) : null;
    UUID variantId = variantIdStr != null ? Ids.parse(variantIdStr) : null;
    Cursor.CreatedAtId key = Cursor.decodeCreatedAtId(after);
    List<PriceOverride> rows =
        repo.listPriceOverrides(
            tenantId,
            storeId,
            variantId,
            key == null ? null : key.createdAt(),
            key == null ? null : key.id(),
            limit + 1);
    return Cursor.page(rows, limit, o -> o.createdAt() + "|" + o.id());
  }

  /**
   * The tax summary report: the working behind the VAT return's single figures.
   *
   * <p>Box 1 and Box 6 are each one number computed over the same rows this groups. An accountant
   * filing the return needs to see which rate bands, sites or months make them up — both to sanity
   * check the figure and to explain it if HMRC asks.
   *
   * <p>Totals are folded from the returned rows rather than queried separately, so the summary can
   * never disagree with its own detail. They also expose one thing the return hides: Box 1 filters
   * to non-exempt supplies, so VAT sitting on a row marked exempt vanishes from it silently. Here
   * that shows up as {@code vatAmount} differing from {@code outputVat}.
   *
   * @param ctx caller context; tenant comes from the verified JWT, never the request
   * @param fromStr inclusive ISO-8601 lower bound on the tax point
   * @param toStr exclusive ISO-8601 upper bound
   * @param stores restrict to these stores, or {@code null} for every store the caller may see —
   *     already resolved by {@link TenantContext#reportStores} against the caller's own store
   *     assignment, never a raw request value
   * @param groupByStr CODE, STORE or MONTH; defaults to CODE
   * @return the grouped rows with folded totals and the period they cover
   * @throws ApiException 400 when the period is malformed or not strictly increasing, or {@code
   *     groupBy} is not one of the three groupings
   */
  public TaxSummary taxSummary(
      TenantContext ctx, String fromStr, String toStr, Set<UUID> stores, String groupByStr) {
    Instant from = Parsing.instant(fromStr, "from");
    Instant to = Parsing.instant(toStr, "to");
    if (!from.isBefore(to))
      throw ApiException.badRequest("PRICING_INVALID_PERIOD", "from must be before to");

    List<TaxSummaryRow> rows =
        taxReportRepo.aggregate(ctx.tenantId(), stores, from, to, grouping(groupByStr));
    // The business's own currency decides the report's precision: whole yen, three-decimal dinars.
    int scale = com.storeql.service.Fx.minorUnits(profiles.requireCurrency(ctx.tenantId()));
    return taxSummaryOf(rows, scale, fromStr, toStr);
  }

  /**
   * The report from its summed rows: each row and the folded totals rounded half up to the
   * currency's minor units ({@code scale}), the totals folded from the same rows they sit beside.
   */
  static TaxSummary taxSummaryOf(
      List<TaxSummaryRow> summed, int scale, String fromStr, String toStr) {
    List<TaxSummaryRow> rows = new java.util.ArrayList<>(summed.size());
    BigDecimal net = BigDecimal.ZERO.setScale(scale);
    BigDecimal vat = BigDecimal.ZERO.setScale(scale);
    BigDecimal outputVat = BigDecimal.ZERO.setScale(scale);
    BigDecimal gross = BigDecimal.ZERO.setScale(scale);
    long transactions = 0;
    for (TaxSummaryRow s : summed) {
      TaxSummaryRow r =
          new TaxSummaryRow(
              s.groupKey(),
              s.exempt(),
              s.netAmount().setScale(scale, RoundingMode.HALF_UP),
              s.vatAmount().setScale(scale, RoundingMode.HALF_UP),
              s.grossAmount().setScale(scale, RoundingMode.HALF_UP),
              s.transactions());
      rows.add(r);
      net = net.add(r.netAmount());
      vat = vat.add(r.vatAmount());
      if (!r.exempt()) outputVat = outputVat.add(r.vatAmount());
      gross = gross.add(r.grossAmount());
      transactions += r.transactions();
    }
    return new TaxSummary(
        rows, new TaxSummaryTotals(net, vat, outputVat, gross, transactions), fromStr, toStr);
  }

  /** Defaults to CODE — "which rate bands is my VAT made of" is what this is opened for. */
  private static TaxGrouping grouping(String raw) {
    if (raw == null || raw.isBlank()) return TaxGrouping.CODE;
    try {
      return TaxGrouping.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ApiException(
          400,
          "PRICING_INVALID_GROUPING",
          "groupBy must be CODE, STORE or MONTH — got: " + raw,
          List.of(),
          e);
    }
  }

  /**
   * Computes HMRC MTD VAT return boxes 1-9 for a period.
   *
   * <p>Box 4 (input VAT on purchases) and box 7 (net purchases) come from the supplier invoices
   * purchase-svc captured, by invoice date (SJ-D39). Boxes 8 and 9, goods traded with the EU, are
   * zero: nothing records that trade.
   *
   * @param ctx caller context; supplies the tenant
   * @param fromStr inclusive ISO-8601 lower bound on the tax point
   * @param toStr exclusive ISO-8601 upper bound
   * @return the nine box figures with the period they cover
   * @throws ApiException {@code PRICING_INVALID_PERIOD} (400) when the period is malformed or not
   *     strictly increasing
   */
  public VatReturn computeVatReturn(TenantContext ctx, String fromStr, String toStr) {
    return computeVatReturn(
        ctx.tenantId(), Parsing.instant(fromStr, "from"), Parsing.instant(toStr, "to"));
  }

  /**
   * The same nine boxes for a tenant and a period already parsed — what a filing (18.5) sends,
   * computed by the one method so the figures on the screen and the figures on the wire are the
   * same figures.
   *
   * @param tenantId owning tenant
   * @param from inclusive lower bound on the tax point
   * @param to exclusive upper bound
   * @return the nine box figures with the period they cover
   * @throws ApiException {@code PRICING_INVALID_PERIOD} (400) when from is not before to
   */
  public VatReturn computeVatReturn(UUID tenantId, Instant from, Instant to) {
    String fromStr = from.toString();
    String toStr = to.toString();
    if (!from.isBefore(to))
      throw ApiException.badRequest("PRICING_INVALID_PERIOD", "from must be before to");

    BigDecimal box1 = repo.sumOutputVat(tenantId, from, to).setScale(2, RoundingMode.HALF_UP);
    BigDecimal box2 = BigDecimal.ZERO;
    BigDecimal box3 = box1.add(box2);
    // SJ-D39: box 4 and box 7 from the supplier invoices purchase-svc captured, by invoice date.
    BigDecimal box4 = repo.sumInputVat(tenantId, from, to).setScale(2, RoundingMode.HALF_UP);
    BigDecimal box5 = box3.subtract(box4).abs();
    BigDecimal box6 = repo.sumNetSales(tenantId, from, to).setScale(2, RoundingMode.HALF_UP);
    BigDecimal box7 = repo.sumNetPurchases(tenantId, from, to).setScale(2, RoundingMode.HALF_UP);
    BigDecimal box8 = BigDecimal.ZERO;
    BigDecimal box9 = BigDecimal.ZERO;

    return new VatReturn(box1, box2, box3, box4, box5, box6, box7, box8, box9, fromStr, toStr);
  }
}
