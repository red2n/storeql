package com.storeql.pricing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** All request and response DTOs for pricing-svc (records = immutable, no domain types). */
public final class Dtos {

  private Dtos() {}

  // ── VAT Rates ─────────────────────────────────────────────────────────────

  // The limits mirror vat_rates' column sizes and rate CHECK: past them the insert fails in the
  // database and the caller got a 500 instead of a 400.
  @Schema(name = "CreateVatRateRequest")
  public record CreateVatRateRequest(
      @Schema(description = "HMRC VAT code, e.g. T1, T0, T5.", maxLength = 8)
          @NotBlank
          @Size(max = 8)
          String code,
      @NotBlank @Size(max = 100) String name,
      @Schema(description = "Fraction between 0 and 1, e.g. 0.20 for 20%.")
          @NotNull
          @PositiveOrZero
          @DecimalMax("1")
          BigDecimal rate,
      @Schema(description = "True if this code is VAT-exempt (no VAT charged).") boolean exempt,
      @Size(max = 255) String description,
      @Schema(description = "ISO-8601 instant this rate takes effect, e.g. 2026-01-01T00:00:00Z.")
          @NotBlank
          String effectiveFrom) {}

  @Schema(
      name = "SetActiveRequest",
      description = "Stop or restart a promotion or price list. The reason is required either way.")
  public record SetActiveRequest(
      @Schema(
              description =
                  "Why. Required in both directions — restarting a promotion is the change more"
                      + " likely to be questioned later, and a trail that records only why things"
                      + " were stopped answers the easier half of the question.")
          @NotBlank
          String reason) {}

  @Schema(name = "StatusChangeResponse", description = "One entry in the on/off history.")
  public record StatusChangeResponse(
      UUID id,
      String subjectType,
      UUID subjectId,
      @Schema(description = "The state it was changed TO.") boolean active,
      String reason,
      UUID changedBy,
      Instant changedAt) {}

  @Schema(name = "VatRateResponse")
  public record VatRateResponse(
      UUID id,
      UUID tenantId,
      String code,
      String name,
      BigDecimal rate,
      boolean exempt,
      String description,
      String effectiveFrom,
      String effectiveTo,
      String createdAt) {}

  // ── Product VAT Categories ────────────────────────────────────────────────

  @Schema(name = "UpsertProductVatCategoryRequest")
  public record UpsertProductVatCategoryRequest(
      @Schema(description = "UUID of the product variant.") @NotBlank String variantId,
      @Schema(description = "Must match an existing VAT rate code.") @NotBlank String vatCode) {}

  @Schema(name = "ProductVatCategoryResponse")
  public record ProductVatCategoryResponse(
      UUID id,
      UUID tenantId,
      UUID variantId,
      String vatCode,
      String effectiveFrom,
      String effectiveTo,
      String createdAt) {}

  // ── Customer VAT Status ───────────────────────────────────────────────────

  @Schema(
      name = "UpsertCustomerVatStatusRequest",
      description = "B2B customer VAT registration status.")
  public record UpsertCustomerVatStatusRequest(
      @Schema(description = "UUID of the customer.") @NotBlank String customerId,
      String vatNumber,
      boolean vatRegistered,
      @Schema(description = "True if this customer is eligible for reverse-charge VAT.")
          boolean reverseChargeEligible,
      @Schema(description = "ISO 3166-1 alpha-2 country code. The tenant's own when omitted.")
          String countryCode,
      @Schema(
              description =
                  "The name the customer is registered for VAT under: the buyer an invoice names"
                      + " (BT-44).")
          @jakarta.validation.constraints.Size(max = 200)
          String legalName,
      @Schema(
              description =
                  "Where the customer receives e-invoices: a Peppol electronic address scheme"
                      + " (EAS), with einvoiceId; both or neither.")
          @jakarta.validation.constraints.Size(max = 8)
          String einvoiceScheme,
      @Schema(description = "The customer's identifier within that scheme.")
          @jakarta.validation.constraints.Size(max = 128)
          String einvoiceId) {}

  @Schema(name = "CustomerVatStatusResponse")
  public record CustomerVatStatusResponse(
      UUID id,
      UUID tenantId,
      UUID customerId,
      String vatNumber,
      boolean vatRegistered,
      boolean reverseChargeEligible,
      String countryCode,
      String legalName,
      String einvoiceScheme,
      String einvoiceId,
      String createdAt,
      String updatedAt) {}

  // ── Price Lists ───────────────────────────────────────────────────────────

  @Schema(name = "CreatePriceListRequest")
  public record CreatePriceListRequest(
      @NotBlank String name,
      @Schema(description = "ALL, ONLINE, or POS. Defaults to ALL.") String channel,
      @Schema(description = "ISO 4217 currency code. The tenant's own when omitted.")
          String currency,
      @Schema(
              description =
                  "ISO-8601 instant this price list takes effect, e.g."
                      + " 2026-01-01T00:00:00Z. The column is TIMESTAMPTZ; a bare date is"
                      + " rejected with INVALID_DATE.")
          @NotBlank
          String effectiveFrom,
      String effectiveTo,
      @Schema(
              description =
                  "A price zone this list is bound to (03.x): it then prices that zone's stores"
                      + " and no other. Omitted, the list is tenant-wide.")
          String zoneId) {}

  @Schema(name = "PriceListResponse")
  public record PriceListResponse(
      UUID id,
      UUID tenantId,
      String name,
      String channel,
      String currency,
      String effectiveFrom,
      String effectiveTo,
      boolean active,
      String createdAt,
      UUID zoneId) {}

  @Schema(name = "UpsertPriceListItemRequest")
  public record UpsertPriceListItemRequest(
      @Schema(description = "UUID of the product variant.") @NotBlank String variantId,
      @NotNull @Positive BigDecimal price,
      @Schema(description = "Minimum quantity this price applies from. Defaults to 1.")
          @NotNull
          @Positive
          BigDecimal minQty) {}

  @Schema(name = "BatchUpsertPriceListItemsRequest")
  public record BatchUpsertPriceListItemsRequest(
      @NotNull @Valid java.util.List<UpsertPriceListItemRequest> items) {}

  @Schema(
      name = "BatchUpsertResult",
      description = "Result of a batch upsert; never a 4xx on partial failure.")
  public record BatchUpsertResult(
      @Schema(description = "Number of items successfully upserted.") int upserted,
      @Schema(description = "One message per item that failed, e.g. \"variantId=...: reason\".")
          java.util.List<String> errors) {}

  @Schema(name = "PriceListItemResponse")
  public record PriceListItemResponse(
      UUID id,
      UUID tenantId,
      UUID priceListId,
      UUID variantId,
      BigDecimal price,
      BigDecimal minQty,
      String createdAt,
      String updatedAt) {}

  // ── Price Resolution ──────────────────────────────────────────────────────

  @Schema(name = "ResolvePriceRequest")
  public record ResolvePriceRequest(
      @Schema(description = "UUID of the product variant.") @NotBlank String variantId,
      @Schema(description = "UUID of the store; used to select a store-scoped promotion/price.")
          String storeId,
      @Schema(description = "ONLINE or POS. Defaults to ALL.") String channel,
      @Schema(description = "Quantity being priced. Defaults to 1.") BigDecimal qty,
      @Schema(description = "UUID of the customer, for customer-specific pricing.")
          String customerId,
      @Schema(
              description =
                  "ISO 4217 code to show the price in as well (03.x), at the business's own rate;"
                      + " the price stays in the business's currency. 400 FX_RATE_MISSING without a"
                      + " rate for it.")
          String displayCurrency) {}

  @Schema(
      name = "DisplayPriceResponse",
      description =
          "The same figures in another currency at the business's rate (03.x): what a shopper sees,"
              + " not what is charged.")
  public record DisplayPriceResponse(
      String currency,
      @Schema(description = "Home units per one unit of the display currency.") BigDecimal rate,
      BigDecimal unitPrice,
      BigDecimal totalWithVat) {}

  @Schema(
      name = "DisplayBasketResponse",
      description = "A basket's totals in another currency at the business's rate (03.x).")
  public record DisplayBasketResponse(
      String currency,
      BigDecimal rate,
      BigDecimal subtotal,
      BigDecimal totalDiscount,
      BigDecimal vatAmount,
      BigDecimal total) {}

  @Schema(name = "DisplayRateResponse", description = "Home units per one unit of a currency.")
  public record DisplayRateResponse(String currency, BigDecimal rate) {}

  @Schema(
      name = "CurrenciesResponse",
      description =
          "The currencies a shop can show prices in: its own, then those it keeps a rate for, with"
              + " the rates so a client can show a figure it already holds.")
  public record CurrenciesResponse(
      String home, List<String> currencies, List<DisplayRateResponse> rates) {}

  @Schema(name = "ResolvedPriceResponse")
  public record ResolvedPriceResponse(
      UUID variantId,
      BigDecimal unitPrice,
      @Schema(description = "HMRC VAT code applied, e.g. T1.") String vatCode,
      @Schema(description = "Fraction between 0 and 1, e.g. 0.20 for 20%.") BigDecimal vatRate,
      BigDecimal vatAmount,
      BigDecimal totalWithVat,
      String currency,
      UUID priceListId,
      @Schema(description = "Name of the promotion applied, if any.") String promotionApplied,
      @Schema(
              description =
                  "The unit price of totalWithVat — per kg, litre, metre, m² or item — when the"
                      + " variant's measure is declared; null otherwise.")
          UnitPriceResponse unitPricing,
      @Schema(description = "Whether a unit price is law for this business's offers today.")
          boolean unitPriceRequired,
      @Schema(
              description =
                  "While a promotion applies: the lowest price, VAT included, applied in the 30 days"
                      + " before the reduction began (Directive 98/6/EC art.6a); null otherwise.")
          BigDecimal priorPrice,
      @Schema(
              description =
                  "NOT_REDUCED; ANNOUNCEABLE; NO_HISTORY (no price recorded before the reduction);"
                      + " SHORT_HISTORY (under 30 days recorded before it); NOT_LOWER (the prior"
                      + " price is not above today's); PENDING (a change is still being recorded);"
                      + " UNCERTAIN (the 30 days cross a span the record could not establish). Null"
                      + " without a promotion.")
          String priorPriceStatus,
      @Schema(description = "When the reduction, or its first progressive step, began.")
          String reductionStartedAt,
      @Schema(description = "Whether art.6a binds this business's announcements today.")
          boolean priorPriceRequired,
      @Schema(
              description =
                  "Whether this price may be announced as a reduction: a promotion applies and, where"
                      + " art.6a binds, its prior price is known and above today's.")
          boolean reductionAnnounceable,
      @Schema(description = "The price in the display currency asked for; absent when none was.")
          DisplayPriceResponse display) {}

  @Schema(
      name = "AppliedPriceResponse",
      description = "One row of the applied-price ledger (03.12).")
  public record AppliedPriceResponse(
      UUID id,
      String channel,
      UUID storeId,
      boolean priced,
      BigDecimal price,
      @Schema(description = "The same before VAT.") BigDecimal netPrice,
      BigDecimal regularPrice,
      String promotionName,
      String currency,
      String appliedFrom,
      @Schema(
              description =
                  "Set when what was offered from this moment until the next row could not be"
                      + " established; no reduction whose 30 days cross it is announced.")
          String uncertainSince,
      String recordedAt,
      String cause) {}

  @Schema(name = "PriceHistoryResponse")
  public record PriceHistoryResponse(
      UUID variantId,
      @Schema(description = "Evaluations due and not yet recorded for this business.") int pending,
      List<AppliedPriceResponse> rows) {}

  @Schema(
      name = "ReductionResponse",
      description = "A reduction on offer, with its prior price (03.12).")
  public record ReductionResponse(
      UUID variantId,
      String channel,
      @Schema(description = "The store a store-scoped promotion applies at; null business-wide.")
          UUID storeId,
      @Schema(description = "What the shopper is offered, VAT included.") BigDecimal price,
      BigDecimal regularPrice,
      String promotionName,
      String currency,
      BigDecimal priorPrice,
      String priorPriceStatus,
      String reductionStartedAt,
      boolean priorPriceRequired,
      boolean reductionAnnounceable) {}

  @Schema(name = "ReductionsResponse")
  public record ReductionsResponse(
      String channel,
      @Schema(description = "Evaluations due and not yet recorded for this business.") int pending,
      List<ReductionResponse> rows) {}

  @Schema(name = "UnitPriceResponse", description = "A unit price (03.13).")
  public record UnitPriceResponse(
      @Schema(description = "The price per one standard unit.") BigDecimal amount,
      @Schema(description = "KG, L, M, SQM, or EA for goods sold by number.") String unit,
      @Schema(description = "How much of the unit one price buys.") BigDecimal quantity,
      @Schema(description = "How it reads beside the price: per kg, per litre, each.")
          String label) {}

  @Schema(name = "ShelfLabelRequest")
  public record ShelfLabelRequest(
      @Schema(description = "1 to 200 variant UUIDs.") List<String> variantIds,
      @Schema(description = "UUID of the store; selects its prices and promotions.") String storeId,
      @Schema(description = "POS (the default) or ONLINE.") String channel) {}

  @Schema(name = "ShelfLabelResponse")
  public record ShelfLabelResponse(
      UUID variantId,
      @Schema(description = "False when no price is in force for the variant.") boolean priced,
      String currency,
      @Schema(description = "The regular selling price, VAT included.") BigDecimal regularPrice,
      UnitPriceResponse regularUnitPrice,
      @Schema(description = "While a promotion applies, the promotional price.")
          BigDecimal promotionalPrice,
      UnitPriceResponse promotionalUnitPrice,
      String promotionName,
      boolean measureDeclared,
      boolean unitPriceRequired,
      @Schema(description = "The reduction's prior price (art.6a), while a promotion applies.")
          BigDecimal priorPrice,
      String priorPriceStatus,
      boolean priorPriceRequired,
      @Schema(
              description =
                  "Whether the label may show the promotional price as a reduction with a was price.")
          boolean reductionAnnounceable) {}

  @Schema(name = "UnitPriceGapResponse")
  public record UnitPriceGapResponse(
      UUID variantId,
      UUID productId,
      @Schema(description = "Whether the catalogue has announced the variant at all.")
          boolean catalogued) {}

  @Schema(name = "UnitPriceGapsResponse")
  public record UnitPriceGapsResponse(boolean required, List<UnitPriceGapResponse> gaps) {}

  /**
   * Resolve many lines in one call instead of one HTTP round trip per line — order-svc's checkout
   * was issuing one synchronous {@code /prices/resolve} call per order line.
   */
  @Schema(name = "ResolvePriceBatchRequest")
  public record ResolvePriceBatchRequest(@NotEmpty @Valid List<ResolvePriceRequest> lines) {}

  /** Results are in the same order as the request's {@code lines}. */
  @Schema(
      name = "ResolvePriceBatchResponse",
      description = "Results are in the same order as the request's lines.")
  public record ResolvePriceBatchResponse(List<ResolvedPriceResponse> results) {}

  // ── Promotions ────────────────────────────────────────────────────────────

  @Schema(name = "CreatePromotionRequest")
  public record CreatePromotionRequest(
      @NotBlank String name,
      @Schema(
              description =
                  "PERCENT or FLAT (per line), BASKET_PERCENT or BASKET_FLAT (whole basket),"
                      + " SPEND_THRESHOLD (a flat amount once the basket clears minOrderAmount),"
                      + " or BOGO (buy X get Y at a discount).")
          @NotBlank
          String type,
      @Schema(
              description =
                  "Percentage (0-100) for the PERCENT types, or an amount for the FLAT ones."
                      + " Ignored for BOGO, which is described by buyQty/getQty/getDiscountPct.")
          @NotNull
          @Positive
          BigDecimal value,
      @Schema(
              description =
                  "Basket subtotal this promotion needs before it applies. Required for"
                      + " SPEND_THRESHOLD, optional on the other basket types. Previously stored"
                      + " and never read, so a 'spend £100' offer applied to a £3 basket.")
          BigDecimal minOrderAmount,
      @Schema(description = "ALL, ONLINE, or POS. Defaults to ALL.") String channel,
      @Schema(
              description =
                  "UUID of the store this promotion is scoped to; omit for every store. Previously"
                      + " stored and never filtered, so a store promotion ran in every store.")
          String storeId,
      @Schema(description = "ISO-8601 timestamp the promotion becomes active.") @NotBlank
          String startsAt,
      @Schema(description = "ISO-8601 timestamp the promotion ends; open-ended if omitted.")
          String endsAt,
      @Schema(
              description =
                  "Application order, ascending — lower runs first. Defaults to 100. Which of two"
                      + " overlapping offers wins used to be an accident of a SQL sort that"
                      + " compared a percentage against a sum of money.")
          Integer priority,
      @Schema(
              description =
                  "When true, this promotion stops every promotion after it — 'cannot be combined"
                      + " with any other offer'.")
          Boolean exclusive,
      @Schema(
              description =
                  "Code the customer must present. Omit for a promotion that applies on its own."
                      + " Matched case-insensitively and unique per tenant.")
          String couponCode,
      @Schema(description = "Total times this promotion may be redeemed. Null = uncapped.")
          Integer maxRedemptions,
      @Schema(
              description =
                  "Times one customer may redeem it. Null = uncapped. Cannot bind on a guest"
                      + " checkout, which has no identity to count against.")
          Integer maxPerCustomer,
      @Schema(description = "BOGO: how many must be bought.") BigDecimal buyQty,
      @Schema(description = "BOGO: how many are then discounted.") BigDecimal getQty,
      @Schema(description = "BOGO: by how much, as a percentage. 100 = free.")
          BigDecimal getDiscountPct) {}

  @Schema(name = "PromotionResponse")
  public record PromotionResponse(
      UUID id,
      UUID tenantId,
      UUID storeId,
      String name,
      @Schema(description = "PERCENT, FLAT, BASKET_PERCENT, BASKET_FLAT, SPEND_THRESHOLD or BOGO.")
          String type,
      BigDecimal value,
      BigDecimal minOrderAmount,
      String channel,
      boolean active,
      String startsAt,
      String endsAt,
      String createdAt,
      @Schema(description = "Application order, ascending.") int priority,
      @Schema(description = "True when this promotion suppresses every promotion after it.")
          boolean exclusive,
      @Schema(description = "Code the customer must present, or null when it applies on its own.")
          String couponCode,
      Integer maxRedemptions,
      Integer maxPerCustomer,
      BigDecimal buyQty,
      BigDecimal getQty,
      BigDecimal getDiscountPct,
      @Schema(
              description =
                  "On the storefront's list only: whether it may be advertised as a reduction. A"
                      + " basket, coupon or multi-buy offer always may; an item reduction only while"
                      + " every reduced price on the storefront can be announced (03.12).")
          Boolean reductionAnnounceable) {}

  @Schema(name = "AddPromotionItemRequest")
  public record AddPromotionItemRequest(
      @Schema(
              description =
                  "ALL, VARIANT or CATEGORY. A CATEGORY scope resolves to the variants of every"
                      + " product whose category path carries it — a parent reaches its children's"
                      + " products — through the catalogue product-svc announces (03.8).")
          @NotBlank
          String scopeType,
      @Schema(description = "UUID of the variant; null when scopeType is ALL.") String scopeId) {}

  // ── Basket quoting ────────────────────────────────────────────────────────

  @Schema(
      name = "QuoteBasketRequest",
      description =
          "Prices a whole basket at once. Distinct from /prices/resolve-batch, which prices each"
              + " line independently and therefore cannot see a spend threshold, a basket"
              + " percentage or a buy-one-get-one.")
  public record QuoteBasketRequest(
      @NotEmpty @Valid List<QuoteLineRequest> lines,
      @Schema(description = "UUID of the store; selects store-scoped prices and promotions.")
          String storeId,
      @Schema(description = "ONLINE or POS. Defaults to ALL.") String channel,
      @Schema(description = "UUID of the customer, for per-customer coupon caps.")
          String customerId,
      @Schema(description = "Coupon codes the customer presented. Matched case-insensitively.")
          List<String> couponCodes,
      @Schema(
              description =
                  "ISO 4217 code to show the totals in as well (03.x), at the business's own rate;"
                      + " the basket is charged in the business's currency.")
          String displayCurrency) {}

  @Schema(name = "QuoteLineRequest")
  public record QuoteLineRequest(
      @Schema(description = "UUID of the product variant.") @NotBlank String variantId,
      @Schema(description = "Quantity being bought. Defaults to 1.") BigDecimal qty,
      @Schema(
              description =
                  "The reduce-to-clear markdown a scanned sticker named (05.4). The line is priced"
                      + " at the sticker's price and no promotion touches it.")
          String markdownId) {}

  @Schema(name = "QuoteLineResponse", description = "One priced basket line.")
  public record QuoteLineResponse(
      UUID variantId,
      BigDecimal qty,
      @Schema(description = "Base price per unit, before promotions.") BigDecimal unitPrice,
      @Schema(description = "qty × unitPrice, before promotions.") BigDecimal lineTotal,
      @Schema(description = "Total taken off this line by line-level promotions.")
          BigDecimal discount,
      @Schema(description = "lineTotal minus discount.") BigDecimal netTotal,
      @Schema(description = "VAT on netTotal, at this variant's rate.") BigDecimal vatAmount,
      @Schema(description = "The VAT code applied.") String vatCode,
      @Schema(description = "The VAT rate applied, as a fraction: 0.20 for 20%; 0 when exempt.")
          BigDecimal vatRate,
      @Schema(description = "The markdown the line was priced at, when a sticker was scanned.")
          UUID markdownId,
      @Schema(
              description =
                  "The unit price of what this line charges per one, discounts and VAT in; null"
                      + " when the variant's measure is not declared (03.13).")
          UnitPriceResponse unitPricing) {}

  @Schema(name = "AppliedPromotionResponse", description = "One promotion that took money off.")
  public record AppliedPromotionResponse(
      UUID promotionId,
      String name,
      @Schema(description = "The variant discounted, or null for a whole-basket promotion.")
          UUID variantId,
      BigDecimal amount) {}

  @Schema(
      name = "QuoteBasketResponse",
      description = "A fully priced basket, with every promotion that applied itemised.")
  public record QuoteBasketResponse(
      List<QuoteLineResponse> lines,
      @Schema(description = "Sum of line totals before any promotion.") BigDecimal subtotal,
      @Schema(description = "Everything taken off, line-level and basket-level together.")
          BigDecimal totalDiscount,
      @Schema(description = "Whole-basket discounts, which belong to no single line.")
          BigDecimal basketDiscount,
      @Schema(description = "VAT across every line, computed after discounts.")
          BigDecimal vatAmount,
      @Schema(description = "subtotal − totalDiscount + vatAmount.") BigDecimal total,
      String currency,
      @Schema(description = "Every promotion that applied, in the order it ran.")
          List<AppliedPromotionResponse> appliedPromotions,
      @Schema(
              description =
                  "Coupon codes the caller presented that did not apply, and why:"
                      + " NO_SUCH_COUPON, NOT_APPLICABLE, COUPON_EXHAUSTED or"
                      + " COUPON_LIMIT_REACHED. Returned rather than ignored — a customer who"
                      + " typed a code is owed an answer.")
          Map<String, String> rejectedCoupons,
      @Schema(description = "The totals in the display currency asked for; absent when none was.")
          DisplayBasketResponse display) {}

  @Schema(
      name = "RecordRedemptionsRequest",
      description =
          "Tells pricing-svc an order used these promotions, so their usage caps are spent."
              + " Idempotent on the order.")
  public record RecordRedemptionsRequest(
      @Schema(description = "UUID of the order the promotions were used on.") @NotBlank
          String orderId,
      @Schema(description = "UUID of the customer, for per-customer caps. Null for a guest.")
          String customerId,
      @Schema(description = "ISO 4217 currency the amounts are in.") String currency,
      @NotEmpty List<AppliedPromotionResponse> appliedPromotions) {}

  @Schema(name = "RecordRedemptionsResponse")
  public record RecordRedemptionsResponse(
      @Schema(
              description =
                  "How many redemptions this call actually recorded. Zero means every one had"
                      + " already been recorded — a replay, not a failure.")
          int recorded) {}

  @Schema(name = "PromotionItemResponse")
  public record PromotionItemResponse(
      UUID id, UUID tenantId, UUID promotionId, String scopeType, UUID scopeId, String createdAt) {}

  @Schema(
      name = "PromotionWindowResponse",
      description =
          "A promotion as a window in time over some items at a store, read by inventory-svc for"
              + " the demand forecast (06.x).")
  public record PromotionWindowResponse(
      UUID promotionId,
      @Schema(description = "The store it is scoped to; null for every store of the business.")
          UUID storeId,
      String name,
      String type,
      BigDecimal value,
      String channel,
      @Schema(description = "Whether it is switched on now.") boolean active,
      String startsAt,
      @Schema(
              description =
                  "Its end date, or the moment it was switched off if that came first; null when"
                      + " open-ended.")
          String endsAt,
      @Schema(description = "The variants it applies to; empty when it applies to everything.")
          List<UUID> variantIds,
      boolean allVariants) {}

  // ── Tax Transactions (POSLog) ─────────────────────────────────────────────

  @Schema(
      name = "RecordTaxTransactionRequest",
      description = "POSLog-compatible tax transaction line per HMRC VAT Notice 700.")
  public record RecordTaxTransactionRequest(
      @NotNull UUID orderId,
      @NotNull UUID orderLineId,
      @NotNull UUID variantId,
      @NotNull UUID storeId,
      @Schema(description = "HMRC VAT code applied, e.g. T1.") @NotBlank String vatCode,
      @Schema(description = "Fraction between 0 and 1, e.g. 0.20 for 20%.") @NotNull @PositiveOrZero
          BigDecimal vatRate,
      BigDecimal netAmount,
      BigDecimal vatAmount,
      BigDecimal grossAmount,
      boolean exempt,
      @Schema(
              description =
                  "ISO-8601 instant of the VAT tax point (chargeable event), e.g."
                      + " 2026-01-01T00:00:00Z.")
          @NotBlank
          String taxPointDate,
      String invoiceRef) {}

  @Schema(name = "TaxTransactionResponse")
  public record TaxTransactionResponse(
      UUID id,
      UUID tenantId,
      UUID orderId,
      UUID orderLineId,
      UUID variantId,
      UUID storeId,
      String vatCode,
      BigDecimal vatRate,
      BigDecimal netAmount,
      BigDecimal vatAmount,
      BigDecimal grossAmount,
      boolean exempt,
      String taxPointDate,
      String invoiceRef,
      String createdAt) {}

  // ── MTD VAT Return ────────────────────────────────────────────────────────

  @Schema(
      name = "VatReturnResponse",
      description =
          "HMRC Making Tax Digital VAT return boxes 1-9 per VAT Notice 700 s.17. Box 4 (input VAT)"
              + " and boxes 7-9 remain zero until purchase-svc's data feeds them.")
  public record VatReturnResponse(
      @Schema(description = "Total output VAT due on sales.") BigDecimal box1,
      @Schema(description = "VAT due on EC acquisitions; always zero (no EC acquisitions modeled).")
          BigDecimal box2,
      @Schema(description = "Total VAT due (box1 + box2).") BigDecimal box3,
      @Schema(description = "Input VAT reclaimable; zero until purchase-svc feeds this.")
          BigDecimal box4,
      @Schema(description = "Net VAT to pay/reclaim: abs(box3 - box4).") BigDecimal box5,
      @Schema(description = "Total net value of sales excluding VAT.") BigDecimal box6,
      @Schema(description = "Total net value of purchases; zero until purchase-svc feeds this.")
          BigDecimal box7,
      @Schema(description = "Total net EC supplies; zero (no EC supplies modeled).")
          BigDecimal box8,
      @Schema(description = "Total net EC acquisitions; zero (no EC acquisitions modeled).")
          BigDecimal box9,
      String periodFrom,
      String periodTo,
      @Schema(description = "The boxes computed from this tenant's tax transactions.")
          List<Integer> computedBoxes,
      @Schema(
              description =
                  "The boxes this service cannot compute: input VAT and purchases live in"
                      + " purchase-svc and nothing carries them here (SJ-D39). Shown as 0 for"
                      + " shape only.")
          List<Integer> notComputedBoxes,
      @Schema(description = "False until every box is real. Do not file from a return that is not.")
          boolean fitToFile,
      @Schema(description = "Why it is not fit to file, in words for the screen.") String caveat) {}

  // ── Gap #41: Price overrides ──────────────────────────────────────────────

  @Schema(
      name = "CreatePriceOverrideRequest",
      description = "Staff-approved ad-hoc price change made at the point of sale.")
  public record CreatePriceOverrideRequest(
      @Schema(description = "UUID of the order this override applies to, if any.") String orderId,
      @Schema(description = "UUID of the product variant.") @NotBlank String variantId,
      @Schema(description = "UUID of the store where the override was made.") @NotBlank
          String storeId,
      @Schema(description = "The price before the override.") @PositiveOrZero
          BigDecimal originalPrice,
      @Schema(description = "The overridden price actually charged.") @NotNull @PositiveOrZero
          BigDecimal overridePrice,
      String overrideReason,
      @Schema(description = "UUID of the staff member who approved the override.")
          String overriddenBy) {}

  @Schema(name = "PriceOverrideResponse")
  public record PriceOverrideResponse(
      String id,
      String orderId,
      String variantId,
      String storeId,
      BigDecimal originalPrice,
      BigDecimal overridePrice,
      String overrideReason,
      String overriddenBy,
      String createdAt) {}

  @Schema(name = "TaxSummaryRow", description = "One aggregated line of the tax summary.")
  public record TaxSummaryRowResponse(
      @Schema(description = "The VAT code, store id, or YYYY-MM month this line sums.")
          String groupKey,
      @Schema(
              description =
                  "True for exempt supplies. Exempt lines are reported separately because the VAT"
                      + " return counts their net in Box 6 but their VAT in no box at all.")
          boolean exempt,
      BigDecimal netAmount,
      BigDecimal vatAmount,
      BigDecimal grossAmount,
      @Schema(description = "How many tax transactions this line covers.") long transactions) {}

  @Schema(
      name = "TaxSummaryTotals",
      description = "Period totals, summed from the returned rows so the two cannot disagree.")
  public record TaxSummaryTotalsResponse(
      @Schema(description = "Net across every line. Ties to VAT return Box 6.")
          BigDecimal netAmount,
      @Schema(description = "VAT across every line, exempt lines included.") BigDecimal vatAmount,
      @Schema(
              description =
                  "VAT across taxable lines only. Ties to VAT return Box 1. If this differs from"
                      + " vatAmount, a line marked exempt is carrying VAT — a data fault the Box 1"
                      + " query drops silently.")
          BigDecimal outputVat,
      BigDecimal grossAmount,
      long transactions) {}

  @Schema(
      name = "TaxSummaryReport",
      description =
          "VAT collected over a period, grouped, with totals that reconcile to the VAT return.")
  public record TaxSummaryResponse(
      List<TaxSummaryRowResponse> rows,
      TaxSummaryTotalsResponse totals,
      String periodFrom,
      String periodTo) {}

  // ── Date-code markdown (05.4, 03.9) ──────────────────────────────────────

  @Schema(name = "MarkdownStepRequest")
  public record MarkdownStepRequest(
      @NotNull @jakarta.validation.constraints.Min(0) Integer daysToExpiry,
      @NotNull @Positive @DecimalMax("100") BigDecimal percentOff) {}

  @Schema(
      name = "SetMarkdownLadderRequest",
      description =
          "How much off at how many days to expiry. storeId scopes it to one store; omitted, it"
              + " is the business's default. Replaces the ladder wholesale.")
  public record SetMarkdownLadderRequest(
      String storeId, @NotEmpty @Valid List<MarkdownStepRequest> steps) {}

  @Schema(name = "MarkdownLadderResponse")
  public record MarkdownLadderResponse(
      String storeId,
      @Schema(description = "STORE, TENANT or DEFAULT — where the steps came from.") String source,
      List<MarkdownStepRequest> steps) {}

  @Schema(
      name = "CreateMarkdownRequest",
      description =
          "Sticker a batch at a lower price. Give percentOff or markdownPrice, not both; the"
              + " other is derived from the current POS price. The sticker's barcode comes back.")
  public record CreateMarkdownRequest(
      @NotBlank String storeId,
      @NotBlank String variantId,
      String batchId,
      @Size(max = 64) String batchNo,
      @Schema(description = "ISO date the batch expires.") @NotBlank String expiryDate,
      @NotNull @Positive BigDecimal qty,
      @Positive @DecimalMax("100") BigDecimal percentOff,
      @PositiveOrZero BigDecimal markdownPrice,
      @Schema(description = "SHORT_DATED, CLEARANCE, DAMAGED_PACK or OVERSTOCK.") @NotBlank
          String reason) {}

  @Schema(name = "CancelMarkdownRequest")
  public record CancelMarkdownRequest(@NotBlank @Size(max = 200) String reason) {}

  @Schema(name = "MarkdownResponse", description = "A batch stickered at a lower price.")
  public record MarkdownResponse(
      UUID id,
      UUID storeId,
      UUID variantId,
      UUID batchId,
      String batchNo,
      String expiryDate,
      BigDecimal qty,
      BigDecimal redeemedQty,
      BigDecimal remainingQty,
      String currency,
      BigDecimal originalPrice,
      BigDecimal markdownPrice,
      BigDecimal percentOff,
      String reason,
      @Schema(description = "The sticker's EAN-13: what the till scans.") String labelCode,
      @Schema(description = "ACTIVE, EXPIRED (active but past its date) or CANCELLED.")
          String status,
      UUID appliedBy,
      Instant createdAt,
      Instant cancelledAt,
      String cancelReason) {}

  @Schema(name = "MarkdownSuggestionResponse", description = "One line of the morning's plan.")
  public record MarkdownSuggestionResponse(
      UUID batchId,
      UUID variantId,
      String batchNo,
      String expiryDate,
      long daysToExpiry,
      BigDecimal remainingQty,
      @Schema(description = "The current POS price; null when the variant has none.")
          BigDecimal currentPrice,
      String currency,
      @Schema(description = "The ladder step that applies, in days; null when none does.")
          Integer stepDays,
      BigDecimal percentOff,
      BigDecimal suggestedPrice,
      @Schema(description = "The live markdown already on this batch, if any.")
          MarkdownResponse existing) {}

  @Schema(
      name = "MarkdownPlanResponse",
      description =
          "What to sticker this morning: every batch expiring within the horizon, its price, and"
              + " what the ladder says to do. inventoryReachable is false when inventory-svc"
              + " could not be read, in which case the list is empty rather than wrong.")
  public record MarkdownPlanResponse(
      UUID storeId,
      int withinDays,
      String ladderSource,
      boolean inventoryReachable,
      List<MarkdownSuggestionResponse> suggestions) {}

  @Schema(
      name = "MarkdownLabelResponse",
      description = "What a scanned reduced-price sticker means at the till.")
  public record MarkdownLabelResponse(
      UUID markdownId,
      UUID variantId,
      UUID storeId,
      String labelCode,
      BigDecimal markdownPrice,
      BigDecimal originalPrice,
      String currency,
      String expiryDate,
      BigDecimal remainingQty,
      @Schema(
              description =
                  "The price the till may strike through, before VAT (03.12); null when the law does"
                      + " not let the sticker be presented as reduced from one.")
          BigDecimal wasPrice,
      @Schema(description = "Whether the till may present the sticker price as a reduction.")
          boolean reductionAnnounceable,
      @Schema(
              description =
                  "The lowest price of the 30 days before, VAT included, where art.6a binds.")
          BigDecimal priorPrice,
      String priorPriceStatus,
      boolean priorPriceRequired,
      @Schema(description = "Exempt as short-dated goods where the law takes up art.6a(3).")
          boolean perishableExempt) {}

  @Schema(name = "MarkdownRedemptionLineRequest")
  public record MarkdownRedemptionLineRequest(
      @NotBlank String markdownId, @NotNull @Positive BigDecimal qty) {}

  @Schema(
      name = "RecordMarkdownRedemptionsRequest",
      description = "What an order sold at reduced prices; recorded once per markdown and order.")
  public record RecordMarkdownRedemptionsRequest(
      @NotBlank String orderId, @NotEmpty @Valid List<MarkdownRedemptionLineRequest> lines) {}

  // ── Making Tax Digital (18.5) ─────────────────────────────────────────────

  @Schema(
      name = "VatRegistrationResponse",
      description =
          "The VAT number this business files under, the path it files through, whether HMRC's"
              + " grant is held, and what the deployment offers.")
  public record VatRegistrationResponse(
      @Schema(description = "False until a number is registered; the offer is still named.")
          boolean registered,
      String vrn,
      @Schema(description = "SIMULATED or HMRC.") String provider,
      boolean connected,
      String connectedAt,
      String updatedAt,
      @Schema(description = "The providers available here.") List<String> providers,
      boolean hmrcConfigured) {}

  @Schema(name = "RegisterVatRequest")
  public record RegisterVatRequest(
      @Schema(description = "Nine digits; a GB prefix and spaces are allowed.")
          @NotBlank
          @Size(max = 16)
          String vrn,
      @Schema(description = "SIMULATED or HMRC.") @NotBlank String provider) {}

  @Schema(name = "HmrcConnectRequest")
  public record HmrcConnectRequest(
      @NotBlank String code,
      @Schema(description = "The redirect URI the code was issued against.") @NotBlank
          String redirectUri) {}

  @Schema(name = "VatObligationResponse", description = "One of HMRC's VAT obligations.")
  public record VatObligationResponse(
      String periodKey,
      String start,
      String end,
      String due,
      @Schema(description = "O open, F fulfilled.") String status,
      String received) {}

  @Schema(
      name = "SubmitVatReturnRequest",
      description =
          "File the return for an obligation. The boxes are computed from the period; the caller"
              + " declares them final. client carries what the browser collected for HMRC's"
              + " fraud-prevention headers: timezone, screens, windowSize, userAgent, deviceId,"
              + " doNotTrack, publicIp.")
  public record SubmitVatReturnRequest(
      @NotBlank @Size(max = 4) String periodKey,
      @NotBlank String from,
      @NotBlank String to,
      @NotNull Boolean finalised,
      Map<String, String> client) {}

  @Schema(
      name = "VatReturnSubmissionResponse",
      description = "A return as filed, and what HMRC answered.")
  public record VatReturnSubmissionResponse(
      String id,
      String vrn,
      String periodKey,
      String periodFrom,
      String periodTo,
      BigDecimal box1,
      BigDecimal box2,
      BigDecimal box3,
      BigDecimal box4,
      BigDecimal box5,
      BigDecimal box6,
      BigDecimal box7,
      BigDecimal box8,
      BigDecimal box9,
      boolean finalised,
      String provider,
      @Schema(description = "ACCEPTED or REJECTED.") String status,
      String submittedAt,
      String submittedBy,
      String processingDate,
      String formBundleNumber,
      String paymentIndicator,
      String chargeRefNumber,
      String receiptId,
      String receiptTimestamp,
      String errorCode,
      String errorMessage) {}

  // ── Price zones and competitor-driven repricing (03.x) ─────────────────────

  @Schema(name = "CreatePriceZoneRequest")
  public record CreatePriceZoneRequest(
      @NotBlank @Size(max = 80) String name, @Size(max = 500) String description) {}

  @Schema(name = "AssignZoneStoresRequest")
  public record AssignZoneStoresRequest(
      @Schema(
              description =
                  "The zone's stores, replacing the current membership. A store is in one"
                      + " zone at most: naming it here moves it out of any other.")
          @NotNull
          @Size(max = 500)
          List<String> storeIds) {}

  @Schema(name = "PriceZoneResponse")
  public record PriceZoneResponse(
      UUID id, String name, String description, List<UUID> storeIds, String createdAt) {}

  @Schema(name = "RecordCompetitorPriceRequest")
  public record RecordCompetitorPriceRequest(
      @NotBlank String variantId,
      @NotBlank @Size(max = 120) String competitor,
      @NotNull @Positive BigDecimal price,
      @Schema(
              description =
                  "The business's own currency, which is the default; another is refused"
                      + " (PRICING_COMPETITOR_CURRENCY_MISMATCH) — rivals are compared like for like.")
          String currency,
      @Schema(description = "The price zone this was seen in; omitted, it counts everywhere.")
          String zoneId,
      @Schema(description = "The day it was seen (ISO-8601 date); today when omitted.")
          String observedOn) {}

  @Schema(name = "BatchCompetitorPricesRequest")
  public record BatchCompetitorPricesRequest(
      @NotEmpty @Size(max = 500) List<@Valid RecordCompetitorPriceRequest> observations) {}

  @Schema(name = "BatchCompetitorPricesResult")
  public record BatchCompetitorPricesResult(int recorded) {}

  @Schema(name = "CompetitorPriceResponse")
  public record CompetitorPriceResponse(
      UUID id,
      UUID variantId,
      String competitor,
      BigDecimal price,
      String currency,
      UUID zoneId,
      String observedOn,
      String source,
      String recordedAt) {}

  @Schema(name = "CreateRepricingRuleRequest")
  public record CreateRepricingRuleRequest(
      @NotBlank @Size(max = 80) String name,
      @Schema(description = "The price list the rule writes into; its zone is the rule's.")
          @NotBlank
          String priceListId,
      @Schema(description = "MATCH_LOWEST, UNDERCUT_PERCENT or UNDERCUT_AMOUNT.") @NotBlank
          String strategy,
      @Schema(
              description =
                  "The percentage (0–100) or the amount to undercut by; 0 for MATCH_LOWEST.")
          @PositiveOrZero
          BigDecimal value,
      @Schema(
              description =
                  "The proposal never goes below this share of the current price."
                      + " pricing-svc holds no cost, so the floor is a share of the price, not of a margin.")
          @NotNull
          @DecimalMin("0.01")
          @DecimalMax("100")
          BigDecimal floorPercent,
      @Schema(description = "NONE (the currency's minor unit) or ENDING_99 (down to a .99).")
          String rounding,
      @Schema(description = "An observation older than this many days is stale; 14 by default.")
          Integer maxAgeDays) {}

  @Schema(name = "RepricingRuleResponse")
  public record RepricingRuleResponse(
      UUID id,
      String name,
      UUID priceListId,
      UUID zoneId,
      String strategy,
      BigDecimal value,
      BigDecimal floorPercent,
      String rounding,
      int maxAgeDays,
      boolean active,
      String createdAt) {}

  @Schema(name = "RepricingProposalResponse")
  public record RepricingProposalResponse(
      UUID id,
      UUID ruleId,
      UUID priceListId,
      UUID zoneId,
      UUID variantId,
      BigDecimal currentPrice,
      String competitor,
      BigDecimal competitorPrice,
      String observedOn,
      BigDecimal proposedPrice,
      String currency,
      String status,
      String proposedAt,
      String decidedAt) {}

  @Schema(name = "RepricingRunResponse")
  public record RepricingRunResponse(
      UUID ruleId, int examined, int proposed, List<RepricingProposalResponse> proposals) {}
}
