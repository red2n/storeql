package com.storeql.order.dto;

import com.storeql.order.domain.Quantities;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Request/response DTOs for order-svc. tenant_id never in request — comes from JWT context. */
public final class Dtos {

  private Dtos() {}

  // ── Order ─────────────────────────────────────────────────────────────────

  @Schema(name = "OrderItemRequest")
  public record OrderItemRequest(
      @Schema(description = "UUID of the product variant.") @NotBlank String variantId,
      @Schema(
              description =
                  "How many, or how much of a weighed or measured product: counted to three"
                      + " decimal places and never rounded — finer is 400 VALIDATION_FAILED. A POS"
                      + " sale's line is the till's reading: a label's net weight read to as many"
                      + " as five places (GS1 AI 3105, 0.37512) or weighings added in floating"
                      + " point (0.30000000000000004) is the reading it is within a billionth of,"
                      + " at most six places, counted at the gram below (0.375, 0.300) and"
                      + " charged, held and kept at that; finer, or under 0.001, is 400. The body"
                      + " takes up to twenty places for a till's double.")
          @NotNull
          @Positive
          @Digits(integer = Quantities.WHOLE_DIGITS, fraction = Quantities.TILL_PLACES)
          BigDecimal qty,
      @Schema(
              description =
                  "The net unit price as the client has it. Under server-side pricing"
                      + " enforcement the price charged is always the server's, never this — on a"
                      + " POS sale it is read for one thing only: with the quantity, the goods as"
                      + " the till rang them up, up to which a full-basket staff discount is capped"
                      + " at the order's subtotal rather than refused (a discount above them is"
                      + " still refused). Required and trusted only in legacy mode, where it is"
                      + " money typed and refused when finer than the currency. Zero is allowed"
                      + " (a price-hidden storefront that never resolves a real price"
                      + " client-side, or a genuine free/comped item).")
          @PositiveOrZero
          BigDecimal unitPrice,
      String notes,
      @Schema(
              description =
                  "For a line sold by weight: the weighing instrument the reading came from, from"
                      + " tenant-svc's register (Weights and Measures Act 1985 s.11). The till"
                      + " offers only certified instruments, and the order is refused (409"
                      + " ORDER_SCALE_NOT_CERTIFIED) when the order's store's register does not"
                      + " hold it or it is not certified today. The line records which it was.")
          String weighingInstrumentId,
      @Schema(
              description =
                  "The reduce-to-clear markdown a scanned sticker named (05.4): the line is priced"
                      + " at the sticker and stands outside every promotion. pricing-svc checks it"
                      + " is live, in date, for this product at this store, with packs left.")
          String markdownId,
      @Schema(
              description =
                  "The lot the pack declared, when a GS1 2D code carried it (AI 10). Checked"
                      + " against open recalls: a pack of a recalled lot is refused (409"
                      + " ORDER_LINE_RECALLED). Not stored.")
          @Size(max = 64)
          String batchNo,
      @Schema(
              description =
                  "The expiry the pack declared (AI 17), as an ISO date. Checked against open"
                      + " recalls with batchNo. Not stored.")
          String expiry) {}

  @Schema(
      name = "PlaceOrderRequest",
      description = "Places an order. tenantId comes from the caller's JWT, never from this body.")
  public record PlaceOrderRequest(
      @Schema(description = "UUID of the store this order is placed at.") @NotBlank String storeId,
      @Schema(description = "UUID of the customer; ignored for a signed-in customer caller.")
          String customerId,
      @Schema(description = "ONLINE or POS.") @NotBlank String channel,
      @Schema(description = "INSTORE, DELIVERY, etc. Defaults to in-store.") String fulfilmentType,
      @NotNull @Valid List<OrderItemRequest> items,
      @Schema(description = "Ignored when server-side pricing enforcement is on.") @PositiveOrZero
          BigDecimal taxAmount,
      @Schema(
              description =
                  "Manual staff discount off the subtotal. Honoured whether or not pricing"
                      + " enforcement is on: staff only, never above the subtotal, and never above"
                      + " the caller role's configured percentage ceiling. Requires"
                      + " discountReason. In the order currency's minor units; on a POS sale,"
                      + " where the till works it out in floating point, rounded half up to"
                      + " them, and capped at the subtotal when no more than the goods as the"
                      + " till rang them up (its lines added unrounded, then rounded once).")
          @PositiveOrZero
          BigDecimal discountAmount,
      @Schema(description = "Why the discount was given. Required whenever discountAmount is set.")
          String discountReason,
      @Schema(
              description =
                  "ISO 4217 currency code. Defaults to the tenant's own currency; a value that contradicts it is rejected with ORDER_CURRENCY_MISMATCH.")
          String currency,
      String notes,
      @Schema(
              description =
                  "Coupon codes the customer presented, matched case-insensitively. Codes that do"
                      + " not apply are reported by pricing-svc and do not fail the order.")
          List<String> couponCodes,
      @Schema(description = "Legacy fallback for the Idempotency-Key header.")
          String idempotencyKey,
      Boolean taxExempt,
      String exemptReason,
      @Schema(description = "Required when fulfilmentType is DELIVERY.") String deliveryLine1,
      String deliveryLine2,
      @Schema(description = "Required when fulfilmentType is DELIVERY.") String deliveryCity,
      @Schema(description = "Required when fulfilmentType is DELIVERY.") String deliveryPostalCode,
      @Schema(description = "Required when fulfilmentType is DELIVERY.")
          String deliveryRecipientName,
      @Schema(description = "Required when fulfilmentType is DELIVERY.")
          String deliveryRecipientPhone,
      String contactPhone,
      @Schema(
              description =
                  "How the customer intends to pay: CASH, CARD, UPI, or WALLET. CASH + DELIVERY is"
                      + " cash-on-delivery; settlement itself is recorded by payment-svc.")
          String paymentMethod,
      @Schema(
              description =
                  "A catalog-mode till order (SJ-D41): the goods are known, the prices are not."
                      + " Placed as AWAITING_PRICE for a manager to price; never swept as"
                      + " stranded. POS channel only.")
          Boolean awaitingPrice,
      @Schema(
              description =
                  "Who is credited with the sale, when it is not the person at the till: on a counter"
                      + " one assistant sells and another takes the money, and a shop paying"
                      + " commission pays the seller. POS only, and the operator when omitted; an"
                      + " online sale is credited to nobody.")
          String sellerUserId,
      @Schema(
              description =
                  "Whether the store may substitute a line it cannot fill (substitutions for"
                      + " out-of-stock online lines). On unless the shopper turns it off.")
          Boolean allowSubstitutions,
      @Schema(
              description =
                  "The delivery or collection window chosen at checkout (delivery and collection"
                      + " slots): the fulfilment_windows id, from GET /storefront/fulfilment-slots."
                      + " Both this and slotStartsAt are required together, only for an online"
                      + " delivery or pickup order, and only where the fulfilling store offers"
                      + " windows of that type.")
          String slotWindowId,
      @Schema(
              description =
                  "The chosen occurrence's start (ISO instant), exactly as"
                      + " GET /storefront/fulfilment-slots gave it.")
          String slotStartsAt,
      @Schema(
              description =
                  "A till sale replayed from the till's offline queue: when the cashier completed"
                      + " it (ISO instant). A sale made offline has already happened, so a replay"
                      + " captured no later than now and no further back than"
                      + " storeql.order.offline-replay.grace-hours (24) is always placed; a line"
                      + " a recall covered, or a scale not fit for trade weighed, at that moment"
                      + " is written on the audit trail for a manager"
                      + " (OFFLINE_SALE_OF_RECALLED_ITEM, OFFLINE_SALE_ON_UNFIT_SCALE). A capture"
                      + " time up to storeql.order.offline-replay.clock-skew-seconds (300) after"
                      + " now is a till clock a little ahead, taken as captured now. A capture"
                      + " time outside those bounds is"
                      + " judged as a sale made now and refused as one, in words for a manager."
                      + " POS only; not read online, and absent for a sale made now.")
          String capturedAt,
      @Schema(
              description =
                  "A till sale replayed from the till's offline queue: the user id of whoever was"
                      + " signed in at the till when the sale was made, which may not be whoever"
                      + " sends the queue. Its audit-trail entries name them only when they are a"
                      + " login of this business allowed at this store; otherwise the entries"
                      + " read as rung up by an unknown member of staff, and the sale is placed"
                      + " all the same. A UUIDv7 or absent (400 INVALID_UUID otherwise). POS"
                      + " only; not read online.")
          String rungUpBy,
      @Schema(
              description =
                  "Gift cards sold on this order (a GIFT_CARD_LOAD line each): the card is issued,"
                      + " or the named card topped up, when the order is paid, for what was paid."
                      + " Added to the total with no VAT, no stock and no fulfilment; an order may"
                      + " carry only these, and then send items as an empty list ([]): items is"
                      + " required.")
          @Valid
          List<GiftCardLoadRequest> giftCardLoads) {

    /** A builder with every member unset; name only what the request carries. */
    public static Builder builder() {
      return new Builder();
    }

    /** A builder starting from this request, to change a member or two. */
    public Builder toBuilder() {
      Builder b = new Builder();
      b.storeId = storeId;
      b.customerId = customerId;
      b.channel = channel;
      b.fulfilmentType = fulfilmentType;
      b.items = items;
      b.taxAmount = taxAmount;
      b.discountAmount = discountAmount;
      b.discountReason = discountReason;
      b.currency = currency;
      b.notes = notes;
      b.couponCodes = couponCodes;
      b.idempotencyKey = idempotencyKey;
      b.taxExempt = taxExempt;
      b.exemptReason = exemptReason;
      b.deliveryLine1 = deliveryLine1;
      b.deliveryLine2 = deliveryLine2;
      b.deliveryCity = deliveryCity;
      b.deliveryPostalCode = deliveryPostalCode;
      b.deliveryRecipientName = deliveryRecipientName;
      b.deliveryRecipientPhone = deliveryRecipientPhone;
      b.contactPhone = contactPhone;
      b.paymentMethod = paymentMethod;
      b.awaitingPrice = awaitingPrice;
      b.sellerUserId = sellerUserId;
      b.allowSubstitutions = allowSubstitutions;
      b.slotWindowId = slotWindowId;
      b.slotStartsAt = slotStartsAt;
      b.capturedAt = capturedAt;
      b.rungUpBy = rungUpBy;
      b.giftCardLoads = giftCardLoads;
      return b;
    }

    /**
     * Named construction of a {@link PlaceOrderRequest}; a member added later touches nothing here.
     */
    public static final class Builder {
      private String storeId;
      private String customerId;
      private String channel;
      private String fulfilmentType;
      private List<OrderItemRequest> items;
      private BigDecimal taxAmount;
      private BigDecimal discountAmount;
      private String discountReason;
      private String currency;
      private String notes;
      private List<String> couponCodes;
      private String idempotencyKey;
      private Boolean taxExempt;
      private String exemptReason;
      private String deliveryLine1;
      private String deliveryLine2;
      private String deliveryCity;
      private String deliveryPostalCode;
      private String deliveryRecipientName;
      private String deliveryRecipientPhone;
      private String contactPhone;
      private String paymentMethod;
      private Boolean awaitingPrice;
      private String sellerUserId;
      private Boolean allowSubstitutions;
      private String slotWindowId;
      private String slotStartsAt;
      private String capturedAt;
      private String rungUpBy;
      private List<GiftCardLoadRequest> giftCardLoads;

      private Builder() {}

      public Builder storeId(String v) {
        this.storeId = v;
        return this;
      }

      public Builder customerId(String v) {
        this.customerId = v;
        return this;
      }

      public Builder channel(String v) {
        this.channel = v;
        return this;
      }

      public Builder fulfilmentType(String v) {
        this.fulfilmentType = v;
        return this;
      }

      public Builder items(List<OrderItemRequest> v) {
        this.items = v;
        return this;
      }

      public Builder taxAmount(BigDecimal v) {
        this.taxAmount = v;
        return this;
      }

      public Builder discountAmount(BigDecimal v) {
        this.discountAmount = v;
        return this;
      }

      public Builder discountReason(String v) {
        this.discountReason = v;
        return this;
      }

      public Builder currency(String v) {
        this.currency = v;
        return this;
      }

      public Builder notes(String v) {
        this.notes = v;
        return this;
      }

      public Builder couponCodes(List<String> v) {
        this.couponCodes = v;
        return this;
      }

      public Builder idempotencyKey(String v) {
        this.idempotencyKey = v;
        return this;
      }

      public Builder taxExempt(Boolean v) {
        this.taxExempt = v;
        return this;
      }

      public Builder exemptReason(String v) {
        this.exemptReason = v;
        return this;
      }

      public Builder deliveryLine1(String v) {
        this.deliveryLine1 = v;
        return this;
      }

      public Builder deliveryLine2(String v) {
        this.deliveryLine2 = v;
        return this;
      }

      public Builder deliveryCity(String v) {
        this.deliveryCity = v;
        return this;
      }

      public Builder deliveryPostalCode(String v) {
        this.deliveryPostalCode = v;
        return this;
      }

      public Builder deliveryRecipientName(String v) {
        this.deliveryRecipientName = v;
        return this;
      }

      public Builder deliveryRecipientPhone(String v) {
        this.deliveryRecipientPhone = v;
        return this;
      }

      public Builder contactPhone(String v) {
        this.contactPhone = v;
        return this;
      }

      public Builder paymentMethod(String v) {
        this.paymentMethod = v;
        return this;
      }

      public Builder awaitingPrice(Boolean v) {
        this.awaitingPrice = v;
        return this;
      }

      public Builder sellerUserId(String v) {
        this.sellerUserId = v;
        return this;
      }

      public Builder allowSubstitutions(Boolean v) {
        this.allowSubstitutions = v;
        return this;
      }

      public Builder slotWindowId(String v) {
        this.slotWindowId = v;
        return this;
      }

      public Builder slotStartsAt(String v) {
        this.slotStartsAt = v;
        return this;
      }

      public Builder capturedAt(String v) {
        this.capturedAt = v;
        return this;
      }

      public Builder rungUpBy(String v) {
        this.rungUpBy = v;
        return this;
      }

      public Builder giftCardLoads(List<GiftCardLoadRequest> v) {
        this.giftCardLoads = v;
        return this;
      }

      public PlaceOrderRequest build() {
        return new PlaceOrderRequest(
            storeId,
            customerId,
            channel,
            fulfilmentType,
            items,
            taxAmount,
            discountAmount,
            discountReason,
            currency,
            notes,
            couponCodes,
            idempotencyKey,
            taxExempt,
            exemptReason,
            deliveryLine1,
            deliveryLine2,
            deliveryCity,
            deliveryPostalCode,
            deliveryRecipientName,
            deliveryRecipientPhone,
            contactPhone,
            paymentMethod,
            awaitingPrice,
            sellerUserId,
            allowSubstitutions,
            slotWindowId,
            slotStartsAt,
            capturedAt,
            rungUpBy,
            giftCardLoads);
      }
    }
  }

  @Schema(
      name = "PriceOrderRequest",
      description = "A manager's prices for an AWAITING_PRICE order, one per variant on it.")
  public record PriceOrderRequest(
      @NotNull @Valid List<PriceLine> lines,
      @Schema(description = "VAT on the priced order; zero when omitted.") @PositiveOrZero
          BigDecimal taxAmount) {}

  @Schema(name = "PriceLine")
  public record PriceLine(
      @NotNull String variantId,
      @Schema(description = "Unit price, in the order's currency.") @NotNull @PositiveOrZero
          BigDecimal unitPrice) {}

  @Schema(name = "OrderItemResponse")
  public record OrderItemResponse(
      String id,
      String variantId,
      BigDecimal qty,
      BigDecimal unitPrice,
      BigDecimal lineTotal,
      String notes,
      @Schema(description = "The instrument a sold-by-weight line was weighed on; null otherwise.")
          String weighingInstrumentId,
      @Schema(description = "How much of qty has been handed over so far (SJ-D35).")
          BigDecimal fulfilledQty,
      @Schema(
              description =
                  "The VAT on this line as the quote priced it (18.5); null when the line was"
                      + " placed with server-side pricing off.")
          BigDecimal vatAmount,
      @Schema(description = "The markdown a scanned sticker priced this line at (05.4), if any.")
          String markdownId,
      @Schema(
              description =
                  "How much of qty will never be handed over: closed short or replaced by a"
                      + " substitute (substitutions for out-of-stock online lines).")
          BigDecimal shortQty,
      @Schema(description = "qty less what was handed over and what was closed short.")
          BigDecimal outstandingQty,
      @Schema(description = "The line this one stands in for, when it is a substitute.")
          String substitutesItemId,
      @Schema(
              description =
                  "What the customer paid for the line after every discount, VAT included, on an"
                      + " order sold at shelf prices; absent on an order priced net."
                      + " lineTotal is what is left once vatAmount is taken out of it.")
          BigDecimal grossTotal,
      @Schema(description = "The shelf price of one unit before any promotion, when known.")
          BigDecimal listUnitPrice,
      @Schema(description = "The VAT code the line was taxed under, when known.") String vatCode,
      @Schema(description = "The rate it was taxed at, as a fraction: 0.2000 for 20%.")
          BigDecimal vatRate) {}

  @Schema(
      name = "FulfilRequest",
      description =
          "Which lines, and how much of each, are being handed over now. Omit the body, or the"
              + " lines, to hand over everything still outstanding.")
  public record FulfilRequest(List<FulfilLine> lines) {}

  @Schema(name = "FulfilLine")
  public record FulfilLine(
      @NotNull String variantId,
      @Schema(
              description =
                  "Units handed over now, to three decimal places; at most what is still"
                      + " outstanding.")
          @NotNull
          @jakarta.validation.constraints.Positive
          @Digits(integer = Quantities.WHOLE_DIGITS, fraction = Quantities.SCALE)
          BigDecimal qty) {}

  @Schema(name = "OrderResponse")
  public record OrderResponse(
      String id,
      String storeId,
      String customerId,
      @Schema(
              description =
                  "The login that placed the order, for an online sale by a signed-in shopper."
                      + " Distinct from customerId, which is the shop's own record of that person:"
                      + " a login is global and a customer record is per-tenant (SJ-D44). Null for"
                      + " a guest checkout and for a till sale.")
          String loginId,
      @Schema(description = "ONLINE or POS.") String channel,
      String fulfilmentType,
      @Schema(description = "PENDING, CONFIRMED, FULFILLED, CANCELLED, or VOIDED.") String status,
      BigDecimal subtotal,
      BigDecimal taxAmount,
      @Schema(
              description =
                  "The staff discount: a deliberate act by a named person, with a reason and a"
                      + " role ceiling. Automatic promotional money is promotionDiscount, kept"
                      + " apart so an offer cannot spend a cashier's authority.")
          BigDecimal discountAmount,
      @Schema(
              description =
                  "What the promotion engine took off the basket as a whole. Line-level"
                      + " promotions are already inside the line prices and therefore inside"
                      + " subtotal.")
          BigDecimal promotionDiscount,
      BigDecimal total,
      String currency,
      String notes,
      String createdAt,
      String updatedAt,
      List<OrderItemResponse> items,
      boolean taxExempt,
      String exemptReason,
      String deliveryLine1,
      String deliveryLine2,
      String deliveryCity,
      String deliveryPostalCode,
      String deliveryRecipientName,
      String deliveryRecipientPhone,
      String contactPhone,
      @Schema(description = "CASH, CARD, UPI, or WALLET.") String paymentMethod,
      @Schema(
              description =
                  "The return-scheme deposits on the sale's drinks containers, added to total"
                      + " (09.16). Absent on shapes that do not carry them.")
          BigDecimal depositAmount,
      List<OrderDepositResponse> deposits,
      @Schema(
              description =
                  "Who is credited with the sale, which is not who rang it up. Absent for a sale"
                      + " credited to nobody, which is the ordinary case online.")
          String sellerUserId,
      @Schema(
              description =
                  "The checkout this order is a part of, when a delivery was split across shops"
                      + " (order orchestration). Absent for an order never split, and on lists.")
          OrderGroupResponse group,
      @Schema(
              description =
                  "How a picked online order was handed over (ship-from-store): dispatched to a"
                      + " carrier, or collected by its shopper. Absent until it is.")
          HandoverResponse handover,
      @Schema(
              description =
                  "Whether the shopper allows the store to substitute a line it cannot fill;"
                      + " their choice at checkout.")
          Boolean allowSubstitutions,
      @Schema(
              description =
                  "The delivery or collection window this order holds (delivery and collection"
                      + " slots); null for a till sale or an order at a store with no windows.")
          SlotResponse slot,
      @Schema(
              description =
                  "contactPhone in international form, e.g. +919886021001 (a phone at the till):"
                      + " read in the store's own country, then the business's. Absent when no"
                      + " number was given, or the one given could not be read.")
          String contactPhoneE164,
      @Schema(
              description =
                  "When a PENDING order lapses if it is not paid (ISO instant): its creation plus"
                      + " the business's unpaid-order limit, or the platform's default while it has"
                      + " set none. Absent once the order is no longer waiting for payment.")
          String expiresAt,
      @Schema(
              description =
                  "True when the order was sold at shelf prices, VAT inside: subtotal is then the"
                      + " lines' net after every discount, total the sum of what they were paid"
                      + " plus deposits, and discountAmount and promotionDiscount are what was"
                      + " given, already inside the lines.")
          boolean taxInclusive) {

    /** This answer with the time a PENDING order lapses; unchanged when it is null. */
    public OrderResponse withExpiresAt(String at) {
      if (at == null) return this;
      return new OrderResponse(
          id,
          storeId,
          customerId,
          loginId,
          channel,
          fulfilmentType,
          status,
          subtotal,
          taxAmount,
          discountAmount,
          promotionDiscount,
          total,
          currency,
          notes,
          createdAt,
          updatedAt,
          items,
          taxExempt,
          exemptReason,
          deliveryLine1,
          deliveryLine2,
          deliveryCity,
          deliveryPostalCode,
          deliveryRecipientName,
          deliveryRecipientPhone,
          contactPhone,
          paymentMethod,
          depositAmount,
          deposits,
          sellerUserId,
          group,
          handover,
          allowSubstitutions,
          slot,
          contactPhoneE164,
          at,
          taxInclusive);
    }
  }

  @Schema(
      name = "ReceiptDocumentResponse",
      description =
          "The receipt of a sale at shelf prices, VAT inside, built once on the server: lines at the"
              + " shelf price, a VAT table by code whose gross adds up to what the lines were paid,"
              + " and the seller's legal name and VAT number when the business has set them. The"
              + " till's screen, the thermal print and the emailed copy are all drawn from it.")
  public record ReceiptDocumentResponse(
      String orderId,
      @Schema(description = "The last eight characters of the order id: what staff say aloud.")
          String reference,
      String status,
      String currency,
      @Schema(description = "When the sale was placed, UTC.") String issuedAt,
      @Schema(description = "The store's own zone; date and time below are in it.") String timeZone,
      String localDate,
      String localTime,
      ReceiptSellerResponse seller,
      List<ReceiptLineResponse> lines,
      @Schema(description = "The staff discount that was given; already inside the lines.")
          BigDecimal staffDiscount,
      @Schema(description = "What the whole-basket offers took off; already inside the lines.")
          BigDecimal promotionDiscount,
      @Schema(description = "The container deposit added to the sale.") BigDecimal deposit,
      @Schema(description = "What the customer pays: the lines plus the deposit.") BigDecimal total,
      List<ReceiptVatRowResponse> vat,
      @Schema(description = "The VAT inside the total.") BigDecimal vatTotal,
      List<ReceiptTenderResponse> tenders) {}

  @Schema(name = "ReceiptSellerResponse")
  public record ReceiptSellerResponse(String legalName, String tradingName, String vatNumber) {}

  @Schema(name = "ReceiptLineResponse")
  public record ReceiptLineResponse(
      String variantId,
      @Schema(description = "The product's name; absent when product-svc could not say.")
          String name,
      BigDecimal qty,
      @Schema(description = "The shelf price of one before any offer; absent when not recorded.")
          BigDecimal listUnitPrice,
      @Schema(description = "What the customer paid for the line, VAT included.")
          BigDecimal lineGross,
      @Schema(description = "What offers and discounts took off the shelf price of the line.")
          BigDecimal saved,
      String vatCode,
      BigDecimal vatRate) {}

  @Schema(name = "ReceiptVatRowResponse")
  public record ReceiptVatRowResponse(
      String vatCode, BigDecimal rate, BigDecimal gross, BigDecimal net, BigDecimal vat) {}

  @Schema(name = "ReceiptTenderResponse")
  public record ReceiptTenderResponse(String method, BigDecimal amount) {}

  @Schema(
      name = "SlotResponse",
      description =
          "The delivery or collection window an order holds: the chosen occurrence's UTC instants"
              + " and the store's own zone, plus date/startTime/endTime already computed in that"
              + " zone so a client shows the store's own local time without converting anything.")
  public record SlotResponse(
      String startsAt,
      String endsAt,
      @Schema(description = "IANA zone id, e.g. Europe/Warsaw.") String timeZone,
      @Schema(description = "The occurrence's date in the store's own zone, yyyy-MM-dd.")
          String date,
      @Schema(description = "The occurrence's start, HH:mm in the store's own zone.")
          String startTime,
      @Schema(description = "The occurrence's end, HH:mm in the store's own zone.")
          String endTime) {}

  @Schema(
      name = "DispatchRequest",
      description = "Hand a picked (FULFILLED) delivery order to a carrier (ship-from-store).")
  public record DispatchRequest(
      @Schema(description = "The carrier's name as the store knows it.") @NotBlank @Size(max = 80)
          String carrier,
      @Schema(description = "The carrier's reference or tracking number, when it gave one.")
          @Size(max = 80)
          String reference,
      @Schema(description = "How many parcels left, when counted.") @Min(1) @Max(999)
          Integer parcels) {}

  @Schema(
      name = "CollectRequest",
      description = "Hand a picked (FULFILLED) pickup order to its shopper at the counter.")
  public record CollectRequest(
      @Schema(description = "Who took it, when staff noted it.") @Size(max = 120)
          String collectedBy) {}

  @Schema(
      name = "ShortCloseRequest",
      description =
          "Close a line short (substitutions for out-of-stock online lines): the quantity that will"
              + " never be handed over comes off the order and the money for it goes back.")
  public record ShortCloseRequest(
      @Schema(
              description =
                  "How much to close, to three decimal places; everything still outstanding when"
                      + " omitted.")
          @DecimalMin("0.001")
          @Digits(integer = Quantities.WHOLE_DIGITS, fraction = Quantities.SCALE)
          BigDecimal qty,
      @Schema(description = "Why, for the history.") @Size(max = 200) String reason) {}

  @Schema(
      name = "SubstituteRequest",
      description =
          "Put a substitute in the bag for a line the store cannot fill: a new line, charged at no"
              + " more than the original, the original closed short for the quantity.")
  public record SubstituteRequest(
      @Schema(description = "The variant put in the bag.") @NotBlank String substituteVariantId,
      @Schema(
              description =
                  "How much, to three decimal places; everything still outstanding when omitted.")
          @DecimalMin("0.001")
          @Digits(integer = Quantities.WHOLE_DIGITS, fraction = Quantities.SCALE)
          BigDecimal qty,
      @Schema(
              description =
                  "The substitute's net unit price, only when server-side pricing is off; ignored"
                      + " otherwise.")
          BigDecimal unitPrice,
      @Schema(description = "Why, for the history.") @Size(max = 200) String reason) {}

  @Schema(
      name = "SubstituteSuggestionResponse",
      description =
          "A stand-in the business declared for a line's product, with what the order's store has"
              + " of it.")
  public record SubstituteSuggestionResponse(
      String variantId, String productName, String sku, BigDecimal available) {}

  @Schema(
      name = "OwingLineResponse",
      description = "A line of an online order the store still owes something on.")
  public record OwingLineResponse(
      String variantId,
      BigDecimal qty,
      BigDecimal fulfilledQty,
      BigDecimal shortQty,
      BigDecimal outstandingQty) {}

  @Schema(
      name = "OwingOrderResponse",
      description =
          "An online order at the store still owing something — confirmed or part-picked — with"
              + " the lines it owes and whether the shopper allows substitutions.")
  public record OwingOrderResponse(
      String orderId,
      String status,
      String fulfilmentType,
      boolean allowSubstitutions,
      String createdAt,
      List<OwingLineResponse> lines) {}

  @Schema(
      name = "HandoverResponse",
      description =
          "The one handover of a picked online order: DISPATCHED to a carrier, or COLLECTED by"
              + " the shopper.")
  public record HandoverResponse(
      String kind,
      String carrier,
      String reference,
      Integer parcels,
      String collectedBy,
      String at,
      @Schema(description = "The member of staff who recorded it.") String by) {}

  @Schema(
      name = "OrderGroupResponse",
      description =
          "One checkout placed as several orders, one per shop, paid once: its total is the"
              + " parts' totals added up.")
  public record OrderGroupResponse(
      String id,
      @Schema(
              description =
                  "The login that placed the checkout; payment-svc checks a shopper pays only"
                      + " their own.")
          String loginId,
      BigDecimal total,
      String currency,
      String createdAt,
      @Schema(description = "The parts, the delivery-area store's first.")
          List<OrderPartResponse> parts) {}

  @Schema(name = "OrderPartResponse", description = "One order of a split checkout.")
  public record OrderPartResponse(
      String orderId,
      String storeId,
      String status,
      BigDecimal total,
      @Schema(description = "How many items the part carries.") BigDecimal units,
      @Schema(
              description =
                  "The delivery or collection window the checkout holds; the same for every"
                      + " part, since it takes one place (delivery and collection slots).")
          SlotResponse slot) {}

  @Schema(name = "OrderStatusHistoryResponse", description = "Append-only order status transition.")
  public record OrderStatusHistoryResponse(
      String id,
      String orderId,
      String fromStatus,
      String toStatus,
      String reason,
      String changedBy,
      String changedAt) {}

  // ── Returns (Gap #14) ─────────────────────────────────────────────────────

  @Schema(name = "ReturnItemRequest")
  public record ReturnItemRequest(
      @NotBlank String variantId,
      @Schema(description = "How much comes back, to three decimal places; never rounded.")
          @NotNull
          @Positive
          @Digits(integer = Quantities.WHOLE_DIGITS, fraction = Quantities.SCALE)
          BigDecimal qty,
      @Schema(
              description =
                  "Required. SEALED goes back on sale; OPENED to inspection; DAMAGED and FAULTY"
                      + " off sale. Missing is 400 ORDER_RETURN_CONDITION_REQUIRED, any other"
                      + " value 400 ORDER_RETURN_CONDITION_INVALID.")
          String condition) {}

  @Schema(name = "CreateReturnRequest")
  public record CreateReturnRequest(
      @NotBlank String reason,
      @Schema(
              description =
                  "ORIGINAL (the default: back to how the sale was paid), STORE_CREDIT (the sale"
                      + " must name a customer) or GIFT_CARD.")
          String refundMethod,
      @NotNull @Valid List<ReturnItemRequest> items,
      @Schema(
              description =
                  "The recall notice this refund settles (05.10): the notice is marked refunded"
                      + " in the same transaction. Must be a notice about this order.")
          String recallNoticeId,
      @Schema(
              description =
                  "GIFT_CARD only: the code of this business's card to top up. Absent, a new card"
                      + " is issued for the refund.")
          @Size(max = 64)
          String giftCardCode,
      @Schema(
              description =
                  "At a till: the till session the cash refund is paid out of, so that drawer's"
                      + " report counts it. Used by payment-svc only when it is this business's"
                      + " session at the sale's own store; otherwise the refund is not counted at"
                      + " any till. A refund is never refused over it.")
          String tillSessionId) {}

  @Schema(name = "ReturnItemResponse")
  public record ReturnItemResponse(
      String id,
      String variantId,
      BigDecimal qty,
      BigDecimal refundAmount,
      String condition,
      @Schema(description = "A no-receipt line only: one unit at today's price, VAT included.")
          BigDecimal unitPrice,
      @Schema(description = "A no-receipt line only: the VAT in the line.") BigDecimal taxAmount) {}

  @Schema(name = "ReturnResponse")
  public record ReturnResponse(
      String id,
      String orderId,
      String storeId,
      String reason,
      BigDecimal refundAmount,
      String refundMethod,
      String status,
      @Schema(
              description =
                  "The member of staff who took the goods back; null before the audit trail.")
          String createdBy,
      String createdAt,
      String completedAt,
      List<ReturnItemResponse> items,
      @Schema(
              description =
                  "The sales.refund holder who allowed a return outside the policy; null when it"
                      + " was within it.")
          String approvedBy,
      @Schema(
              description =
                  "Why it needed a manager: WINDOW, CEILING or FAULTY_PAST_WINDOW; empty when it"
                      + " was within the policy.")
          List<String> outsidePolicy,
      @Schema(
              description =
                  "GIFT_CARD only, on the response to the return itself: the card the refund went"
                      + " on, with its code as the issue endpoint shows it.")
          ReturnGiftCardResponse giftCard,
      @Schema(
              description =
                  "A direct exchange only: the sale the returned goods pay towards; null"
                      + " otherwise.")
          String exchangeOrderId,
      @Schema(description = "True for a return taken with no receipt, when orderId is null.")
          boolean noReceipt,
      @Schema(description = "The customer a no-receipt refund goes to; null when none is named.")
          String customerId) {}

  @Schema(name = "ExchangeRequest")
  public record ExchangeRequest(
      @NotBlank String reason,
      @NotNull @Valid List<ReturnItemRequest> returnItems,
      @NotNull @Valid List<@NotNull ExchangeNewItemRequest> newItems,
      @Schema(
              description =
                  "The customer the new sale is for; the returned sale's customer when absent.")
          String customerId) {}

  @Schema(name = "ExchangeNewItemRequest")
  public record ExchangeNewItemRequest(
      @NotBlank String variantId,
      @Schema(
              description =
                  "As on a till sale's line, for the new sale is one: the till scans the new"
                      + " basket, so a label's net weight read to as many as five places (GS1 AI"
                      + " 3105, 0.37512) or weighings added in floating point"
                      + " (0.30000000000000004) is the reading it is within a billionth of, at"
                      + " most six places, counted at the gram below (0.375, 0.300) and charged,"
                      + " held and kept at that; finer, or under 0.001, is 400 VALIDATION_FAILED"
                      + " naming newItems[i].qty. The body takes up to twenty places for a till's"
                      + " double.")
          @NotNull
          @Positive
          @Digits(integer = Quantities.WHOLE_DIGITS, fraction = Quantities.TILL_PLACES)
          BigDecimal qty,
      @Schema(
              description =
                  "As on a till sale's line: the certified weighing instrument a weighed item was"
                      + " read on. Refused (409 ORDER_SCALE_NOT_CERTIFIED) as on a sale when the"
                      + " store's register does not hold it or it is not certified today.")
          String weighingInstrumentId,
      @Schema(
              description =
                  "As on a till sale's line: the reduce-to-clear markdown a scanned sticker named"
                      + " (05.4). The new sale's line is priced at the sticker, as a sale's is.")
          String markdownId,
      @Schema(
              description =
                  "As on a till sale's line: the lot the pack declared (GS1 AI 10). A pack of a"
                      + " recalled lot is refused (409 ORDER_LINE_RECALLED), as on a sale. Not"
                      + " stored.")
          @Size(max = 64)
          String batchNo,
      @Schema(
              description =
                  "As on a till sale's line: the expiry the pack declared (AI 17), as an ISO"
                      + " date, checked against open recalls with batchNo; not a date is 400"
                      + " ORDER_LINE_EXPIRY_INVALID naming newItems[i].expiry. Not stored.")
          String expiry) {
    // One constructor only: JSON-B (Yasson) builds a record through its canonical constructor
    // only when it is the record's sole one, so a second would refuse every exchange body.
  }

  @Schema(name = "NoReceiptReturnRequest")
  public record NoReceiptReturnRequest(
      @NotBlank String storeId,
      @NotBlank String reason,
      @Schema(
              description =
                  "STORE_CREDIT or GIFT_CARD. Never ORIGINAL: there is no sale to pay back.")
          @NotBlank
          String refundMethod,
      @Schema(description = "Required for STORE_CREDIT: the customer whose credit it goes to.")
          String customerId,
      @Schema(
              description =
                  "The phone or email the customer gives, kept for the record and never logged.")
          @NotBlank
          @Size(max = 200)
          String customerContact,
      @Schema(description = "GIFT_CARD only: a card of this business to top up; absent, a new one.")
          @Size(max = 64)
          String giftCardCode,
      @NotNull @Valid List<ReturnItemRequest> items) {}

  @Schema(name = "ReturnGiftCardResponse")
  public record ReturnGiftCardResponse(String id, String code, BigDecimal balance) {}

  @Schema(name = "ReturnableLineResponse")
  public record ReturnableLineResponse(
      String variantId,
      @Schema(description = "What was handed over.") BigDecimal soldQty,
      @Schema(description = "What has already come back.") BigDecimal returnedQty,
      @Schema(description = "What can still come back: sold less returned.")
          BigDecimal returnableQty,
      @Schema(description = "The price paid for one, before any refund.") BigDecimal unitPrice) {}

  @Schema(
      name = "ReceiptLookupResponse",
      description = "A sale found by its receipt, with how much of each line can still come back.")
  public record ReceiptLookupResponse(
      OrderResponse order,
      @Schema(description = "The printed fiscal number, when one has been issued.")
          String receiptNumber,
      List<ReturnableLineResponse> lines) {}

  @Schema(name = "PendingLimitRequest")
  public record PendingLimitRequest(
      @Schema(
              description =
                  "Whole hours an unpaid order is held before it is cancelled, 1 or more"
                      + " (ORDER_PENDING_LIMIT_INVALID otherwise); null puts the platform's own"
                      + " default back.")
          Integer pendingLimitHours) {}

  @Schema(name = "PendingLimitResponse")
  public record PendingLimitResponse(
      @Schema(description = "The business's own limit; null while it has set none.")
          Integer pendingLimitHours,
      @Schema(description = "The hours in force: the business's own, else the platform's default.")
          int effectiveHours,
      boolean usingDefault) {}

  @Schema(name = "PriceWaitRequest")
  public record PriceWaitRequest(
      @Schema(
              description =
                  "Minutes an order waits for a price before a manager is told; null for never.")
          Integer flagMinutes,
      @Schema(
              description =
                  "Minutes before the order is cancelled and its stock released; null for never."
                      + " Not before flagMinutes (ORDER_PRICE_WAIT_INVALID).")
          Integer cancelMinutes) {}

  @Schema(name = "PriceWaitResponse")
  public record PriceWaitResponse(Integer flagMinutes, Integer cancelMinutes) {}

  @Schema(name = "ReturnPolicyRequest")
  public record ReturnPolicyRequest(
      @Schema(description = "Calendar days from handover in which a cashier may take a return.")
          @NotNull
          @Min(1)
          @Max(3650)
          Integer windowDays,
      @Schema(
              description =
                  "The most a cashier may refund on one return, in the business's home currency;"
                      + " absent for no ceiling.")
          @PositiveOrZero
          BigDecimal cashierCeiling,
      @Schema(description = "Whether a return with no receipt may be taken at all.") @NotNull
          Boolean noReceiptAllowed,
      @Schema(description = "The most a no-receipt return may refund, home currency.")
          @PositiveOrZero
          BigDecimal noReceiptCeiling) {}

  @Schema(name = "ReturnPolicyResponse")
  public record ReturnPolicyResponse(
      int windowDays,
      BigDecimal cashierCeiling,
      boolean noReceiptAllowed,
      BigDecimal noReceiptCeiling,
      @Schema(description = "The business's home currency, which the ceilings are in.")
          String currency,
      @Schema(description = "True while the business has set nothing and the default applies.")
          boolean usingDefault) {}

  // ── Post-void (Gap #14) ───────────────────────────────────────────────────

  // ── Order list (header-only, no items embedded) ───────────────────────────

  @Schema(name = "OrderSummaryResponse", description = "Order header only, without line items.")
  public record OrderSummaryResponse(
      String id,
      String storeId,
      String customerId,
      @Schema(description = "ONLINE or POS.") String channel,
      String fulfilmentType,
      @Schema(description = "PENDING, CONFIRMED, FULFILLED, CANCELLED, or VOIDED.") String status,
      BigDecimal subtotal,
      BigDecimal taxAmount,
      BigDecimal discountAmount,
      BigDecimal total,
      String currency,
      String createdAt,
      String updatedAt,
      String paymentMethod,
      @Schema(
              description =
                  "The split checkout this order is a part of (order orchestration); absent for an"
                      + " order never split.")
          String groupId,
      @Schema(
              description =
                  "How a picked online order was handed over (ship-from-store); absent until it"
                      + " is.")
          HandoverResponse handover,
      @Schema(
              description =
                  "Whether the shopper allows the store to substitute a line it cannot fill;"
                      + " their choice at checkout.")
          Boolean allowSubstitutions,
      @Schema(
              description =
                  "The delivery or collection window this order holds (delivery and collection"
                      + " slots); null for a till sale or an order at a store with no windows.")
          SlotResponse slot,
      @Schema(
              description =
                  "When an order still waiting for payment lapses, as an ISO instant; absent for"
                      + " an order that is not PENDING.")
          String expiresAt) {}

  @Schema(name = "VoidRequest")
  public record VoidRequest(
      @Schema(description = "Why the sale is voided; recorded on the void log and the receipt.")
          @NotBlank
          @Size(max = 500)
          String reason,
      @Schema(
              description =
                  "At a till: the till session the cash goes back out of; see CreateReturnRequest.")
          String tillSessionId) {}

  @Schema(name = "VoidResponse")
  public record VoidResponse(String orderId, String reason, String voidedAt) {}

  // ── Layaway (Gap #14) ─────────────────────────────────────────────────────

  @Schema(name = "LayawayItemRequest")
  public record LayawayItemRequest(
      @NotBlank String variantId,
      @Schema(description = "To three decimal places; never rounded.")
          @NotNull
          @Positive
          @Digits(integer = Quantities.WHOLE_DIGITS, fraction = Quantities.SCALE)
          BigDecimal qty,
      @NotNull @Positive BigDecimal unitPrice) {}

  @Schema(name = "CreateLayawayRequest")
  public record CreateLayawayRequest(
      @NotBlank String storeId,
      String customerId,
      @NotNull @Valid List<LayawayItemRequest> items,
      @Schema(description = "Initial deposit; cannot exceed the layaway's total amount.")
          @NotNull
          @Positive
          BigDecimal initialDeposit,
      String paymentMethod,
      String dueDate,
      String notes) {}

  @Schema(name = "AddDepositRequest")
  public record AddDepositRequest(
      @NotNull @Positive BigDecimal amount, @NotBlank String paymentMethod, String reference) {}

  @Schema(name = "LayawayItemResponse")
  public record LayawayItemResponse(
      String id, String variantId, BigDecimal qty, BigDecimal unitPrice, BigDecimal lineTotal) {}

  @Schema(name = "LayawayDepositResponse")
  public record LayawayDepositResponse(
      String id, BigDecimal amount, String paymentMethod, String reference, String paidAt) {}

  @Schema(name = "LayawayResponse")
  public record LayawayResponse(
      String id,
      String storeId,
      String customerId,
      BigDecimal totalAmount,
      BigDecimal depositPaid,
      @Schema(description = "Total amount minus deposits paid so far.") BigDecimal balance,
      @Schema(description = "ACTIVE, COMPLETED, or CANCELLED.") String status,
      String notes,
      String createdAt,
      String dueDate,
      String completedAt,
      String cancelledAt,
      List<LayawayItemResponse> items,
      List<LayawayDepositResponse> deposits) {}

  // ── Gift cards (Gap #14) ──────────────────────────────────────────────────

  @Schema(
      name = "IssueGiftCardRequest",
      description =
          "A manager's issue of a gift card with no sale behind it. A card a customer pays for is a"
              + " GIFT_CARD_LOAD line on the sale (giftCardLoads on the order), issued when the"
              + " sale is paid; a cashier cannot issue one (GIFT_CARD_NEEDS_SALE).")
  public record IssueGiftCardRequest(
      @NotBlank String storeId,
      @Schema(description = "Initial stored-value amount.") @NotNull @Positive BigDecimal amount,
      @Schema(
              description =
                  "ISO 4217 currency code. Defaults to the tenant's own currency; a value that contradicts it is rejected with ORDER_CURRENCY_MISMATCH.")
          String currency,
      String expiresAt,
      @Schema(description = HAND_PAID_BY_DESCRIPTION) String paidBy,
      @Schema(description = REASON_DESCRIPTION) String reason,
      @Schema(description = "A note kept with the reason and who gave it.") @Size(max = 200)
          String note) {}

  @Schema(
      name = "ReloadGiftCardRequest",
      description =
          "A manager's top-up of a gift card with no sale behind it; a card topped up by a"
              + " customer's payment is a GIFT_CARD_LOAD line naming its code.")
  public record ReloadGiftCardRequest(
      @NotNull @Positive BigDecimal amount,
      String reference,
      @Schema(description = HAND_PAID_BY_DESCRIPTION) String paidBy,
      @Schema(description = REASON_DESCRIPTION) String reason,
      @Schema(description = "A note kept with the reason and who gave it.") @Size(max = 200)
          String note) {}

  static final String REASON_DESCRIPTION =
      "Required: why value is being given, one of GOODWILL, PROMOTION, COMPENSATION or MIGRATION"
          + " (GIFT_CARD_REASON_REQUIRED otherwise). Kept with who gave it.";

  static final String HAND_PAID_BY_DESCRIPTION =
      "Optional, and only PROMOTIONAL when given: value handed out by hand is given away. Money"
          + " taken for a card is a sale (GIFT_CARD_NEEDS_SALE).";

  /** A gift-card line on a sale: value the customer pays for with the order. */
  @Schema(
      name = "GiftCardLoadRequest",
      description =
          "A gift card sold as a line of the order. The card is issued (or topped up, when code"
              + " names one) when the order is paid, for this amount, and not before.")
  public record GiftCardLoadRequest(
      @Schema(description = "What the card is loaded with, in the order's currency.")
          @NotNull
          @Positive
          BigDecimal amount,
      @Schema(description = "An existing card of this business to top up; absent for a new card.")
          String code) {}

  @Schema(
      name = "GiftCardLoadResponse",
      description =
          "A gift card sold on an order. PENDING until the sale is paid (no card, no code); then"
              + " LOADED with the card's id and code, whether it is a NEW card or a TOP_UP, and when.")
  public record GiftCardLoadResponse(
      String id,
      BigDecimal amount,
      @Schema(description = "PENDING or LOADED.") String status,
      String giftCardId,
      @Schema(description = "The card's code; absent while PENDING.") String code,
      @Schema(description = "NEW or TOP_UP; absent while PENDING.") String kind,
      @Schema(description = "ISO instant the card was loaded; absent while PENDING.")
          String loadedAt) {}

  @Schema(name = "RedeemGiftCardRequest")
  public record RedeemGiftCardRequest(
      @NotNull @Positive BigDecimal amount,
      @Schema(description = "UUID of the order this redemption pays for.") @NotBlank String orderId,
      String reference) {}

  @Schema(name = "RedeemGiftCardResponse")
  public record RedeemGiftCardResponse(
      String redemptionId, String giftCardId, BigDecimal amount, BigDecimal balance) {}

  @Schema(name = "GiftCardResponse")
  public record GiftCardResponse(
      String id,
      String storeId,
      String code,
      BigDecimal initialBalance,
      BigDecimal currentBalance,
      @Schema(description = "ACTIVE, DEPLETED, or EXPIRED.") String status,
      String currency,
      String issuedAt,
      String expiresAt) {}

  @Schema(name = "GiftCardTransactionResponse")
  public record GiftCardTransactionResponse(
      String id,
      @Schema(description = "ISSUE, RELOAD, or REDEEM.") String txType,
      BigDecimal amount,
      BigDecimal balanceBefore,
      BigDecimal balanceAfter,
      String orderId,
      String reference,
      String createdAt) {}

  // ── Gap #42: Special orders ───────────────────────────────────────────────

  @Schema(name = "SpecialOrderItemRequest")
  public record SpecialOrderItemRequest(
      @NotBlank String variantId,
      @Schema(description = "To three decimal places; never rounded.")
          @NotNull
          @Positive
          @Digits(integer = Quantities.WHOLE_DIGITS, fraction = Quantities.SCALE)
          BigDecimal qty,
      @NotNull @Positive BigDecimal unitPrice,
      String notes) {}

  @Schema(
      name = "CreateSpecialOrderRequest",
      description =
          "Places a customer order for future delivery at a store, without immediate inventory"
              + " deduction.")
  public record CreateSpecialOrderRequest(
      @NotBlank String storeId,
      String customerId,
      String customerName,
      String customerPhone,
      String customerEmail,
      String deliveryAddress,
      @Schema(description = "ISO-8601 date the customer has requested delivery by.")
          String requestedDeliveryDate,
      String notes,
      @NotNull @Valid List<SpecialOrderItemRequest> items,
      @Schema(
              description =
                  "ISO 4217 currency code. Defaults to the tenant's own currency; a value that contradicts it is rejected with ORDER_CURRENCY_MISMATCH.")
          String currency,
      String idempotencyKey) {}

  @Schema(name = "SpecialOrderItemResponse")
  public record SpecialOrderItemResponse(
      String id,
      String variantId,
      BigDecimal qty,
      BigDecimal unitPrice,
      BigDecimal lineTotal,
      String notes) {}

  @Schema(name = "SpecialOrderResponse")
  public record SpecialOrderResponse(
      String id,
      String storeId,
      String customerId,
      String customerName,
      String customerPhone,
      String customerEmail,
      String deliveryAddress,
      String requestedDeliveryDate,
      String notes,
      @Schema(description = "PENDING, CONFIRMED, FULFILLED, or CANCELLED.") String status,
      BigDecimal subtotal,
      BigDecimal total,
      String currency,
      String createdAt,
      String updatedAt,
      List<SpecialOrderItemResponse> items) {}

  // ── Gap #43: POSLog ───────────────────────────────────────────────────────

  @Schema(
      name = "PosLogEntryResponse",
      description = "Append-only POS transaction journal entry for a fulfilled order.")
  public record PosLogEntryResponse(
      String id,
      String orderId,
      String storeId,
      String cashierId,
      BigDecimal subtotal,
      BigDecimal taxAmount,
      BigDecimal discountAmount,
      BigDecimal total,
      String currency,
      boolean taxExempt,
      String exemptReason,
      String transactionTs,
      String createdAt) {}

  // ── Gap #44: Receipts ─────────────────────────────────────────────────────

  @Schema(name = "GenerateReceiptRequest")
  public record GenerateReceiptRequest(
      @Schema(
              description =
                  "How the receipt came out: PRINT (browser), THERMAL (ESC/POS), SAVE (file) or EMAIL.")
          @NotBlank
          @Size(max = 20)
          String receiptType,
      @Schema(description = "Required when receiptType is EMAIL.") String emailedTo,
      Integer printCount) {}

  @Schema(name = "OrderReceiptResponse")
  public record OrderReceiptResponse(
      String id,
      String orderId,
      String receiptType,
      String emailedTo,
      int printCount,
      String generatedAt) {}

  // ── Gap #50: SIM ↔ POS sync ───────────────────────────────────────────────

  @Schema(
      name = "PosStockPositionResponse",
      description =
          "Eventually-consistent local projection of on-hand stock for a store/variant, updated"
              + " asynchronously from inventory-svc events.")
  public record PosStockPositionResponse(
      String storeId, String variantId, String onHandQty, String updatedAt) {}

  // ── Parked (suspended) sales ──────────────────────────────────────────────

  @Schema(name = "ParkedSaleItemRequest")
  public record ParkedSaleItemRequest(
      @NotBlank String variantId,
      @Schema(
              description =
                  "As on a till sale: three decimal places; a label's finer weight or a"
                      + " double's noise is the reading it stands for, counted at the gram below;"
                      + " anything finer 400 VALIDATION_FAILED.")
          @NotNull
          @Positive
          @Digits(integer = Quantities.WHOLE_DIGITS, fraction = Quantities.TILL_PLACES)
          BigDecimal qty,
      @NotNull @PositiveOrZero BigDecimal unitPrice,
      @PositiveOrZero BigDecimal discountAmount,
      String notes,
      @Schema(description = "The reduce-to-clear markdown a scanned sticker named (05.4), if any.")
          String markdownId) {}

  @Schema(name = "ParkSaleRequest")
  public record ParkSaleRequest(
      @NotBlank String storeId,
      String customerId,
      String customerName,
      List<@NotNull @Valid ParkedSaleItemRequest> items,
      String notes) {}

  @Schema(name = "ResumeParkedSaleRequest")
  public record ResumeParkedSaleRequest(
      @Schema(description = "UUID of the parked sale to resume.") @NotBlank String parkedSaleId) {}

  @Schema(name = "ParkedSaleItemResponse")
  public record ParkedSaleItemResponse(
      String variantId,
      BigDecimal qty,
      BigDecimal unitPrice,
      BigDecimal discountAmount,
      BigDecimal lineTotal,
      String notes,
      @Schema(description = "The markdown the line was stickered at (05.4), if any.")
          String markdownId) {}

  @Schema(name = "ParkedSaleResponse")
  public record ParkedSaleResponse(
      String id,
      String storeId,
      String customerId,
      String customerName,
      BigDecimal subtotal,
      BigDecimal discountAmount,
      List<ParkedSaleItemResponse> items,
      String notes,
      String parkedAt,
      @Schema(description = "When this parked sale is auto-discarded if not resumed.")
          String expiresAt,
      @Schema(description = "The cashier who parked it.") String parkedBy,
      @Schema(description = "When it was picked back up; null while it is open.") String resumedAt,
      @Schema(description = "Who picked it back up, which may not be who parked it.")
          String resumedBy) {}

  // ── No-sale / open-drawer log ─────────────────────────────────────────────

  @Schema(
      name = "NoSaleRequest",
      description = "Logs a cash-drawer open with no accompanying sale.")
  public record NoSaleRequest(String storeId, String tillSessionId, String reason) {}

  @Schema(name = "NoSaleResponse")
  public record NoSaleResponse(String id, String storeId, String reason, String loggedAt) {}

  // ── Staff exception report ────────────────────────────────────────────────

  @Schema(
      name = "ExceptionRowResponse",
      description =
          "One cashier's, or one store's, staff-initiated exceptions over the period, with the"
              + " journalled sales that make them a rate rather than a ranking of who worked most.")
  public record ExceptionRowResponse(
      @Schema(
              description =
                  "The actor id or store id this line covers. UNATTRIBUTED covers exceptions"
                      + " recorded with no actor — bucketed rather than dropped, because an"
                      + " exception nobody is accountable for is the last one to hide.")
          String groupKey,
      @Schema(description = "How many discounts this group granted.") long discounts,
      @Schema(description = "Total value discounted.") BigDecimal discountAmount,
      @Schema(description = "How many sales this group voided.") long voids,
      @Schema(description = "How many times the drawer was opened with no sale.") long noSales,
      @Schema(
              description =
                  "Journalled POS sales for this group. Zero means nothing journalled the sale,"
                      + " which is NOT the same as no sales having happened — see"
                      + " ExceptionReportResponse.journalCoverage before reading any rate.")
          long sales,
      @Schema(description = "Value of those journalled sales.") BigDecimal salesValue) {}

  @Schema(
      name = "ExceptionReportResponse",
      description = "Staff exception report: discounts, voids and no-sales over a period.")
  public record ExceptionReportResponse(
      List<ExceptionRowResponse> rows,
      @Schema(
              description =
                  "True when the POS transaction journal has entries for this period. When false"
                      + " every sales figure above is zero because nothing journalled anything,"
                      + " and the exception counts must be read as raw counts with no denominator"
                      + " — a cashier who took a thousand sales and one who took three are not"
                      + " distinguishable.")
          boolean journalCoverage) {}

  @Schema(
      name = "SalesByHourRowResponse",
      description = "One hour of the trading day, on the clock of the requested timezone.")
  public record SalesByHourRowResponse(
      @Schema(
              description =
                  "Hour 0-23 in the timezone the report was asked for, not UTC. Hours with no"
                      + " trade are absent rather than zero: a row of zeroes would assert the shop"
                      + " was open and empty.")
          int hourOfDay,
      @Schema(description = "How many revenue orders fell in this hour.") long orders,
      @Schema(description = "Their total, after discount.") BigDecimal grossAmount,
      @Schema(description = "How much was discounted away inside this hour.")
          BigDecimal discountAmount,
      @Schema(description = "grossAmount divided by orders.") BigDecimal averageBasket) {}

  @Schema(
      name = "SalesByStaffRowResponse",
      description =
          "One member of staff's takings, from the POS transaction journal. In-store only — an"
              + " online order has no cashier.")
  public record SalesByStaffRowResponse(
      @Schema(
              description =
                  "The cashier's user id. UNATTRIBUTED covers journal entries naming nobody —"
                      + " bucketed rather than dropped, because a sale with no cashier is a gap in"
                      + " the audit trail.")
          String groupKey,
      @Schema(description = "How many sales they journalled.") long sales,
      @Schema(description = "What those sales came to, after discount.") BigDecimal grossAmount,
      @Schema(description = "How much they discounted away.") BigDecimal discountAmount,
      @Schema(description = "grossAmount divided by sales.") BigDecimal averageBasket,
      @Schema(
              description =
                  "Discount as a percentage of what the sales would have fetched undiscounted."
                      + " Null when there is nothing to take a percentage of.")
          BigDecimal discountRate) {}

  // ── Age verification: the due-diligence record ──────────────────────────────

  @Schema(
      name = "RecordAgeCheckRequest",
      description =
          "One age check as the till made it. The rule fields are copied from product-svc's answer"
              + " at that moment, so the record says what the rule was rather than what it later"
              + " became.")
  public record RecordAgeCheckRequest(
      @jakarta.validation.constraints.NotBlank String storeId,
      @jakarta.validation.constraints.NotBlank String variantId,
      @jakarta.validation.constraints.NotBlank
          @Schema(description = "ALCOHOL, TOBACCO, KNIVES, … — product-svc's category.")
          String category,
      @jakarta.validation.constraints.NotNull
          @jakarta.validation.constraints.Min(1)
          @jakarta.validation.constraints.Max(99)
          Integer minimumAge,
      @jakarta.validation.constraints.NotBlank
          @jakarta.validation.constraints.Pattern(regexp = "^[A-Za-z]{2}$")
          @Schema(description = "ISO 3166-1 alpha-2 country whose rule applied.")
          String country,
      @Schema(description = "True when the age came from the shop's own stricter policy.")
          Boolean storePolicy,
      @Schema(
              description =
                  "The birth-date cut-off that applied (yyyy-mm-dd), copied from product-svc's"
                      + " answer: anyone born on or after it is refused. Absent when the rule had"
                      + " none. The customer's own date of birth is never sent.")
          String bornBefore,
      @Schema(description = "True when the cut-off was the shop's own policy rather than the law.")
          Boolean bornBeforeStorePolicy,
      @jakarta.validation.constraints.NotBlank
          @Schema(description = "PASSED — the sale went ahead; REFUSED — it did not.")
          String outcome,
      @Schema(
              description =
                  "Required on a refusal, absent on a pass: UNDER_AGE, NO_ID, ID_REJECTED,"
                      + " PROXY_SALE (buying for someone under age), BORN_AFTER_CUTOFF (born on"
                      + " or after the cut-off, which needs bornBefore) or OTHER.")
          String reason,
      @Schema(
              description =
                  "What was shown when the sale went ahead, if the shop records it: PASSPORT,"
                      + " DRIVING_LICENCE, PASS_CARD, MILITARY_ID, NATIONAL_ID or OTHER.")
          String idType,
      @Schema(description = "The POS session the check was made in, when the till has one.")
          String posSessionId,
      @Schema(description = "The sale the check belonged to, once there is one.") String orderId) {}

  @Schema(name = "AgeVerificationResponse", description = "One recorded age check.")
  public record AgeVerificationResponse(
      String id,
      String storeId,
      String cashierId,
      String posSessionId,
      String variantId,
      String category,
      int minimumAge,
      String country,
      boolean storePolicy,
      String bornBefore,
      boolean bornBeforeStorePolicy,
      String outcome,
      String reason,
      String idType,
      String orderId,
      String checkedAt) {}

  @Schema(
      name = "AgeVerificationSummaryResponse",
      description = "Counts for one store and period, and the refusals broken down by reason.")
  public record AgeVerificationSummaryResponse(
      long total,
      long passed,
      long refused,
      java.util.Map<String, Long> refusedByReason,
      java.util.Map<String, Long> byCategory) {}

  // ── Fiscal regime (18.5) ──────────────────────────────────────────────────

  @Schema(
      name = "TseStampResponse",
      description =
          "What the German security module wrote against the sale (KassenSichV §6): what the"
              + " receipt prints. error is set, and the rest null, when the module could not be"
              + " reached — the sale went ahead and the outage is the record.")
  public record TseStampResponse(
      String serialNumber,
      String clientId,
      Long transactionNumber,
      Long signatureCounter,
      String signature,
      String algorithm,
      String publicKey,
      String timeFormat,
      String startedAt,
      String finishedAt,
      String processType,
      String processData,
      @Schema(description = "The QR payload the receipt prints, DSFinV-K Anlage I.") String qr,
      String error) {}

  @Schema(
      name = "PtStampResponse",
      description =
          "The Portuguese document signature: the SAF-T invoice number, the RSA-SHA1 hash, the key"
              + " version, the ATCUD, the software certificate number and the four characters the"
              + " receipt prints.")
  public record PtStampResponse(
      String invoiceNo,
      String hash,
      String hashControl,
      String atcud,
      String certificateNumber,
      String printedExcerpt) {}

  @Schema(
      name = "FiscalReceiptResponse",
      description =
          "A numbered legal receipt with its hash chain and, when the store is under a regime that"
              + " stamps documents, the regime's stamp.")
  public record FiscalReceiptResponse(
      String id,
      String storeId,
      String seriesCode,
      String period,
      long number,
      String fullNumber,
      String orderId,
      String issuedAt,
      String issuedBy,
      String currency,
      BigDecimal grossTotal,
      BigDecimal taxTotal,
      String voidedAt,
      String voidReason,
      String prevHash,
      String hash,
      @Schema(description = "NONE, DE_KASSENSICHV or PT_SAFT — the store's regime when issued.")
          String regime,
      TseStampResponse tse,
      PtStampResponse pt) {}

  @Schema(name = "TseDeviceResponse", description = "The security module a store signs with.")
  public record TseDeviceResponse(
      String id,
      @Schema(description = "SIMULATED or CLOUD.") String provider,
      String clientId,
      String serialNumber,
      String publicKey,
      String signatureAlgorithm,
      String timeFormat,
      String externalTssId,
      long signatureCounter,
      long transactionCounter,
      String registeredAt) {}

  @Schema(
      name = "FiscalSettingsResponse",
      description =
          "The fiscal regime a store trades under, its registered device, and what this"
              + " deployment can offer: the regimes and device providers available, and whether a"
              + " Portuguese signing key is installed.")
  public record FiscalSettingsResponse(
      String storeId,
      String regime,
      String taxRegistrationNumber,
      String certificateNumber,
      String seriesValidationCode,
      String updatedAt,
      String updatedBy,
      TseDeviceResponse tse,
      List<String> regimes,
      List<String> tseProviders,
      boolean ptKeyConfigured,
      String ptPublicKey) {}

  @Schema(
      name = "SetFiscalSettingsRequest",
      description =
          "Place a store under a fiscal regime. DE_KASSENSICHV needs a tax number and a device"
              + " (tseProvider SIMULATED, or CLOUD with tseTssId); PT_SAFT needs a valid NIF and a"
              + " signing key on the server. Documents already issued keep their stamps.")
  public record SetFiscalSettingsRequest(
      @NotBlank String storeId,
      @Schema(description = "NONE, DE_KASSENSICHV or PT_SAFT.") @NotBlank String regime,
      @Schema(description = "Steuernummer / USt-IdNr (DE) or NIF (PT).")
          @jakarta.validation.constraints.Size(max = 32)
          String taxRegistrationNumber,
      @Schema(description = "PT: the AT software certificate number printed on every document.")
          @jakarta.validation.constraints.Size(max = 16)
          String certificateNumber,
      @Schema(description = "PT: the AT series validation code; ATCUD is <code>-<number>.")
          @jakarta.validation.constraints.Size(max = 16)
          String seriesValidationCode,
      @Schema(description = "DE: SIMULATED or CLOUD. Registers or replaces the store's device.")
          String tseProvider,
      @Schema(description = "DE, CLOUD: the provider's id for the device.") String tseTssId,
      @Schema(description = "DE: what the device knows this register by; the store id when blank.")
          String tseClientId) {}

  @Schema(
      name = "SetReceiptSeriesRequest",
      description = "Open a receipt series, or set what it prints in front of the number.")
  public record SetReceiptSeriesRequest(
      @jakarta.validation.constraints.NotBlank String storeId,
      @Schema(description = "MAIN when omitted.") String seriesCode,
      @jakarta.validation.constraints.NotBlank
          @Schema(description = "The fiscal period, e.g. 2026.")
          String period,
      @Schema(description = "Letters, digits and hyphens, at most 16; blank for none.")
          String prefix) {}

  // ── Business audit trail (20.11) ──────────────────────────────────────────

  @Schema(
      name = "AuditEventResponse",
      description =
          "One sensitive action from an append-only log: who, what, when, where, how much and"
              + " why.")
  public record AuditEventResponse(
      String id,
      @Schema(
              description =
                  "DISCOUNT, VOID, NO_SALE, CANCEL, RETURN, PRICED (a catalogue-mode order given its"
                      + " price by a manager), OFFLINE_SALE_OF_RECALLED_ITEM (a till"
                      + " sale replayed from the offline queue, recorded although a recall"
                      + " covered the line when it was rung up) or OFFLINE_SALE_ON_UNFIT_SCALE"
                      + " (the same, for a line weighed on a scale not fit for trade then).")
          String type,
      @Schema(description = "When it happened; for an offline sale, when the cashier rang it up.")
          String occurredAt,
      @Schema(description = "The member of staff responsible; null when the log recorded nobody.")
          String actorId,
      String storeId,
      @Schema(description = "The order acted on; null for a no-sale.") String orderId,
      @Schema(description = "The discount granted or the refund made; null for the rest.")
          BigDecimal amount,
      String reason,
      @Schema(
              description =
                  "The log's own qualifier: the role that authorised a discount, the status a"
                      + " cancel came from, a return's refund method, the supervisor who"
                      + " authorised a no-sale, the reference of the recall an offline sale sold"
                      + " under, the register's standing of the scale one was weighed on"
                      + " (NOT_REGISTERED when the store's register does not hold it,"
                      + " UNKNOWN_AT_SALE when the register cannot show it was fit then).")
          String detail,
      @Schema(description = "The product line an offline sale's entry is about; null for the rest.")
          String variantId,
      @Schema(
              description =
                  "Who sent an offline sale from the till's queue, which may be somebody other"
                      + " than who rang it up (actorId, null when that was not a member of staff"
                      + " the business holds at the store); null for the rest.")
          String replayedBy,
      @Schema(
              description =
                  "A return only: the sales.refund holder who allowed it outside the policy.")
          String approvedBy,
      @Schema(
              description =
                  "A return only: why it needed a manager (WINDOW, CEILING, FAULTY_PAST_WINDOW);"
                      + " empty when within the policy.")
          List<String> outsidePolicy,
      @Schema(description = "A return only: the lines, quantities and conditions.")
          List<AuditReturnLineResponse> lines,
      @Schema(description = "A return only: true when it was taken with no receipt.")
          boolean noReceipt) {}

  @Schema(name = "AuditReturnLineResponse")
  public record AuditReturnLineResponse(String variantId, BigDecimal qty, String condition) {}

  // ── Deposit return (09.16) ────────────────────────────

  @Schema(
      name = "OrderDepositResponse",
      description = "The deposit a return scheme put on one line's drinks containers.")
  public record OrderDepositResponse(
      String variantId,
      @Schema(description = "PET, ALUMINIUM, STEEL or GLASS.") String material,
      int volumeMl,
      BigDecimal qty,
      BigDecimal depositEach,
      @Schema(description = "qty × depositEach, as charged.") BigDecimal amount,
      @Schema(description = "OUTSIDE_SCOPE (VATA 1994 s.55B) or STANDARD (taxed as the drink).")
          String vatTreatment,
      BigDecimal vatRate,
      @Schema(description = "The VAT inside amount; 0 when outside the scope of VAT.")
          BigDecimal vatAmount,
      @Schema(description = "The scheme's country.") String schemeScope,
      String citation) {}

  @Schema(name = "ContainerRefundLineRequest")
  public record ContainerRefundLineRequest(
      @Schema(description = "PET, ALUMINIUM, STEEL or GLASS.") @NotBlank String material,
      @NotNull @Positive Integer volumeMl,
      @Schema(description = "How many containers of this kind came back.") @NotNull @Positive
          Integer count) {}

  @Schema(
      name = "ContainerRefundRequest",
      description = "Containers brought back to the till, for the deposit paid back on them.")
  public record ContainerRefundRequest(
      @NotBlank String storeId,
      @Schema(description = "The till session the cash leaves.") @NotBlank String tillSessionId,
      @NotNull @Valid List<ContainerRefundLineRequest> lines) {}

  @Schema(name = "ContainerRefundLineResponse")
  public record ContainerRefundLineResponse(
      String material, int volumeMl, int count, BigDecimal depositEach, BigDecimal amount) {}

  @Schema(name = "ContainerRefundResponse")
  public record ContainerRefundResponse(
      String id,
      String storeId,
      String tillSessionId,
      String currency,
      int containers,
      BigDecimal amount,
      String schemeScope,
      String refundedBy,
      String createdAt,
      List<ContainerRefundLineResponse> lines) {}

  @Schema(name = "DepositReportRowResponse")
  public record DepositReportRowResponse(
      String material,
      long chargedContainers,
      BigDecimal chargedAmount,
      BigDecimal chargedVat,
      long refundedContainers,
      BigDecimal refundedAmount) {}

  @Schema(
      name = "DepositReportResponse",
      description =
          "Deposits charged on sales that stand and paid back at the till over a period; the"
              + " difference is what the scheme holds unredeemed (09.16).")
  public record DepositReportResponse(
      String from,
      String to,
      String storeId,
      String currency,
      long chargedContainers,
      BigDecimal chargedAmount,
      @Schema(description = "The VAT inside the deposits charged, where the scheme taxes them.")
          BigDecimal chargedVat,
      long refundedContainers,
      BigDecimal refundedAmount,
      BigDecimal unredeemedAmount,
      List<DepositReportRowResponse> byMaterial) {}

  // ── Delivery and collection slots ─────────────────────────────────────────

  @Schema(
      name = "FulfilmentWindowResponse",
      description = "One weekly window a store offers, for delivery or for collection.")
  public record FulfilmentWindowResponse(
      String id,
      String storeId,
      @Schema(description = "DELIVERY or PICKUP.") String fulfilmentType,
      @Schema(description = "ISO weekday: 1=Monday .. 7=Sunday.") int weekday,
      @Schema(description = "The store's own local time, HH:mm.") String startTime,
      String endTime,
      @Schema(description = "How many orders this occurrence takes.") int capacity,
      @Schema(description = "Minutes before the start orders stop.") int cutoffMinutes,
      boolean active,
      @Schema(description = "The store's own IANA zone at the moment this was set.")
          String timeZone,
      String updatedAt,
      String updatedBy) {}

  @Schema(
      name = "CreateFulfilmentWindowRequest",
      description = "A new weekly window for one store and fulfilment type.")
  public record CreateFulfilmentWindowRequest(
      @NotBlank String storeId,
      @Schema(description = "DELIVERY or PICKUP.") @NotBlank String fulfilmentType,
      @Schema(description = "ISO weekday: 1=Monday .. 7=Sunday.") @NotNull Integer weekday,
      @Schema(description = "The store's own local time, HH:mm.") @NotBlank String startTime,
      @NotBlank String endTime,
      @Schema(description = "How many orders this occurrence takes; at least 1.") @NotNull
          Integer capacity,
      @Schema(description = "Minutes before the start orders stop; 0 when omitted.")
          Integer cutoffMinutes,
      @Schema(description = "On when omitted.") Boolean active) {}

  @Schema(
      name = "UpdateFulfilmentWindowRequest",
      description = "A window's shape. Its store and fulfilment type cannot be changed.")
  public record UpdateFulfilmentWindowRequest(
      @Schema(description = "ISO weekday: 1=Monday .. 7=Sunday.") @NotNull Integer weekday,
      @NotBlank String startTime,
      @NotBlank String endTime,
      @NotNull Integer capacity,
      Integer cutoffMinutes,
      Boolean active) {}

  @Schema(
      name = "FulfilmentSlotResponse",
      description = "One occurrence of a window, with what it has left.")
  public record FulfilmentSlotResponse(
      String windowId,
      String startsAt,
      String endsAt,
      @Schema(description = "The occurrence's start, HH:mm in the store's own zone.")
          String startTime,
      @Schema(description = "The occurrence's end, HH:mm in the store's own zone.") String endTime,
      @Schema(description = "Places still free; never above capacity.") int left,
      boolean full) {}

  @Schema(name = "FulfilmentSlotDayResponse", description = "One of the next seven days.")
  public record FulfilmentSlotDayResponse(
      @Schema(description = "yyyy-MM-dd in the store's own zone.") String date,
      @Schema(description = "This day's occurrences, earliest first; empty when none is left.")
          List<FulfilmentSlotResponse> slots) {}

  @Schema(
      name = "FulfilmentSlotsResponse",
      description =
          "The next seven days of a store's windows of one fulfilment type, in the store's own"
              + " time, with what each occurrence has left.")
  public record FulfilmentSlotsResponse(
      String storeId,
      @Schema(description = "DELIVERY or PICKUP.") String fulfilmentType,
      @Schema(description = "IANA zone id, e.g. Europe/Warsaw.") String timeZone,
      @Schema(description = "Whether the store offers at least one active window of this type.")
          boolean offered,
      @Schema(description = "Always exactly seven entries, today first.")
          List<FulfilmentSlotDayResponse> days) {}
}
