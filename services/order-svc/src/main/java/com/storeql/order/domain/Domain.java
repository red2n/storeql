package com.storeql.order.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Domain records for order-svc. Money is BigDecimal; times are UTC Instant. */
public final class Domain {

  private Domain() {}

  // ── Core order ────────────────────────────────────────────────────────────

  /**
   * A numbered legal receipt.
   *
   * <p>Distinct from {@code order_receipts}, which logs how many times a document was printed or
   * emailed. This is the document — one per sale, numbered consecutively, never renumbered and
   * never deleted. A voided sale keeps its number, because suppressing it is the fraud the
   * numbering exists to expose.
   */
  public record FiscalReceipt(
      UUID id,
      UUID tenantId,
      UUID storeId,
      String seriesCode,
      String period,
      long number,
      String fullNumber,
      UUID orderId,
      Instant issuedAt,
      UUID issuedBy,
      String currency,
      BigDecimal grossTotal,
      BigDecimal taxTotal,
      Instant voidedAt,
      String voidReason,
      /** Hash of the document before this one in its series; GENESIS for the first (18.4). */
      String prevHash,
      /** SHA-256 over this document's figures and {@code prevHash}; null before the chain. */
      String hash,
      /** The regime the store was under when this was issued (18.5); NONE before it existed. */
      String regime,
      /** The security module's stamp, when the regime has one; null otherwise. */
      TseStamp tse,
      /** The Portuguese document signature, when the regime has one; null otherwise. */
      PtStamp pt) {
    /** The series a store uses when the jurisdiction does not require one per till. */
    public static final String DEFAULT_SERIES = "MAIN";

    /** The previous-hash of the first document in a chain. */
    public static final String GENESIS = "GENESIS";

    /** A draft, before the number and the hashes are taken. */
    public FiscalReceipt(
        UUID id,
        UUID tenantId,
        UUID storeId,
        String seriesCode,
        String period,
        long number,
        String fullNumber,
        UUID orderId,
        Instant issuedAt,
        UUID issuedBy,
        String currency,
        BigDecimal grossTotal,
        BigDecimal taxTotal,
        Instant voidedAt,
        String voidReason) {
      this(
          id,
          tenantId,
          storeId,
          seriesCode,
          period,
          number,
          fullNumber,
          orderId,
          issuedAt,
          issuedBy,
          currency,
          grossTotal,
          taxTotal,
          voidedAt,
          voidReason,
          null,
          null,
          FiscalStoreSettings.REGIME_NONE,
          null,
          null);
    }

    /** The same document with a different stamp — used while it is being assembled. */
    public FiscalReceipt withStamps(String regime, TseStamp tse, PtStamp pt) {
      return new FiscalReceipt(
          id,
          tenantId,
          storeId,
          seriesCode,
          period,
          number,
          fullNumber,
          orderId,
          issuedAt,
          issuedBy,
          currency,
          grossTotal,
          taxTotal,
          voidedAt,
          voidReason,
          prevHash,
          hash,
          regime,
          tse,
          pt);
    }
  }

  /**
   * What a German security module (TSE) wrote against one transaction (KassenSichV §2, §6; BSI
   * TR-03153). Everything a receipt must print and DSFinV-K's transactions_tse table lists. {@code
   * error} is set, and the rest null, when the device could not be reached: the sale goes ahead and
   * the outage is the record.
   */
  public record TseStamp(
      String serialNumber,
      String clientId,
      Long transactionNumber,
      Long signatureCounter,
      String signature,
      String algorithm,
      String publicKey,
      String timeFormat,
      Instant startedAt,
      Instant finishedAt,
      String processType,
      String processData,
      String qr,
      String error) {

    /** The process type a sale is signed under (DSFinV-K Anlage I). */
    public static final String PROCESS_TYPE_RECEIPT = "Kassenbeleg-V1";

    /** A stamp that records only that the device failed. */
    public static TseStamp failed(String clientId, String error) {
      return new TseStamp(
          null, clientId, null, null, null, null, null, null, null, null, null, null, null, error);
    }
  }

  /**
   * The Portuguese document signature (Despacho 8632/2014): an RSA-SHA1 over the document's date,
   * entry time, number, gross total and the previous document's signature, base64. {@code
   * invoiceNo} is the number in the form the SAF-T file requires; {@code atcud} is the AT's series
   * validation code joined to the number.
   */
  public record PtStamp(
      String invoiceNo,
      String hash,
      String hashControl,
      String atcud,
      String certificateNumber,
      /** The four characters of {@code hash} a receipt prints: positions 1, 11, 21 and 31. */
      String printedExcerpt) {}

  /**
   * The fiscal regime a store trades under and the identity its file names the business by (18.5).
   * One row per store; a store with no row is under NONE.
   */
  public record FiscalStoreSettings(
      UUID tenantId,
      UUID storeId,
      String regime,
      String taxRegistrationNumber,
      String certificateNumber,
      String seriesValidationCode,
      Instant updatedAt,
      UUID updatedBy) {

    public static final String REGIME_NONE = "NONE";
    public static final String REGIME_DE_KASSENSICHV = "DE_KASSENSICHV";
    public static final String REGIME_PT_SAFT = "PT_SAFT";
    public static final java.util.List<String> REGIMES =
        java.util.List.of(REGIME_NONE, REGIME_DE_KASSENSICHV, REGIME_PT_SAFT);

    /** What a store with no settings row is under. */
    public static FiscalStoreSettings none(UUID tenantId, UUID storeId) {
      return new FiscalStoreSettings(tenantId, storeId, REGIME_NONE, null, null, null, null, null);
    }
  }

  /**
   * The security module a German store signs with — the registration a DSFinV-K tse table lists and
   * every receipt names. The SIMULATED provider keeps its key here, which is exactly why it is not
   * a certified device.
   */
  public record TseDevice(
      UUID id,
      UUID tenantId,
      UUID storeId,
      String provider,
      String clientId,
      String serialNumber,
      String publicKey,
      String signatureAlgorithm,
      String timeFormat,
      String privateKey,
      String externalTssId,
      long signatureCounter,
      long transactionCounter,
      Instant registeredAt,
      UUID registeredBy) {

    public static final String PROVIDER_SIMULATED = "SIMULATED";
    public static final String PROVIDER_CLOUD = "CLOUD";
    public static final java.util.List<String> PROVIDERS =
        java.util.List.of(PROVIDER_SIMULATED, PROVIDER_CLOUD);
  }

  /** A hole in a receipt series, inclusive at both ends. */
  public record SequenceGap(long from, long to) {}

  /**
   * One age check at the till, as it was made: the rule in force at the moment, who checked, and
   * whether the sale went ahead or was refused, and why. Append-only — the due-diligence record.
   */
  public record AgeVerification(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID cashierId,
      UUID posSessionId,
      UUID variantId,
      String category,
      int minimumAge,
      String country,
      boolean storePolicy,
      java.time.LocalDate bornBefore,
      boolean bornBeforePolicy,
      String outcome,
      String reason,
      String idType,
      UUID orderId,
      Instant checkedAt) {

    public static final String OUTCOME_PASSED = "PASSED";

    /** Refused for the date of birth: born on or after the cut-off, whatever the age (10.8). */
    public static final String REASON_BORN_AFTER_CUTOFF = "BORN_AFTER_CUTOFF";

    public static final String OUTCOME_REFUSED = "REFUSED";
    public static final java.util.Set<String> REASONS =
        java.util.Set.of(
            "UNDER_AGE", "NO_ID", "ID_REJECTED", "PROXY_SALE", REASON_BORN_AFTER_CUTOFF, "OTHER");
    public static final java.util.Set<String> ID_TYPES =
        java.util.Set.of(
            "PASSPORT", "DRIVING_LICENCE", "PASS_CARD", "MILITARY_ID", "NATIONAL_ID", "OTHER");
  }

  /** Counts for one store and period: the numbers a licensing officer asks for first. */
  public record AgeVerificationSummary(
      long total,
      long passed,
      long refused,
      java.util.Map<String, Long> refusedByReason,
      java.util.Map<String, Long> byCategory) {}

  public record Order(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID customerId,
      UUID loginId,
      String channel,
      String fulfilmentType,
      String status,
      BigDecimal subtotal,
      BigDecimal taxAmount,
      BigDecimal discountAmount,
      BigDecimal total,
      String currency,
      String notes,
      String idempotencyKey,
      Instant createdAt,
      Instant updatedAt,
      boolean taxExempt,
      String exemptReason,
      String deliveryLine1,
      String deliveryLine2,
      String deliveryCity,
      String deliveryPostalCode,
      String deliveryRecipientName,
      String deliveryRecipientPhone,
      String contactPhone,
      String paymentMethod,
      /**
       * What the promotion engine took off this order, kept apart from {@code discountAmount}. That
       * one is the staff discount — a named person, a required reason, a role ceiling and an audit
       * row (SJ-D6). This one is automatic and answers to a rule. Summing them would put
       * promotional money inside the role-ceiling check.
       */
      BigDecimal promotionDiscount,
      /**
       * Who is credited with the sale, which is not the same as who rang it up (the POS journal's
       * cashier). On a counter an assistant sells and a supervisor takes the money, and a shop that
       * pays commission pays the seller. Null for a sale nobody is credited with — the ordinary
       * case online.
       */
      UUID sellerUserId,
      /**
       * Whether the shopper allows the store to substitute a line it cannot fill (substitutions for
       * out-of-stock online lines): their choice at checkout, on unless they turned it off.
       */
      boolean allowSubstitutions,
      /**
       * The delivery or collection window this order holds (delivery and collection slots): the
       * {@code fulfilment_windows} row it was taken from. All four slot fields are set together or
       * not at all — a till sale and an order at a store with no windows carry none.
       */
      UUID slotWindowId,
      /** The chosen occurrence's start, in UTC. */
      Instant slotStartsAt,
      /** The chosen occurrence's end, in UTC. */
      Instant slotEndsAt,
      /**
       * The IANA zone the occurrence was resolved in at the moment it was taken — the store's own
       * zone, or the window's saved one when tenant-svc could not be read — so the server can
       * always show the store's own local time again without asking tenant-svc a second time.
       */
      String slotTimeZone,
      /**
       * {@code contactPhone} in international form (a phone at the till): read at placement in the
       * store's own country, then the business's, and what a recall text goes to. Null when no
       * number was given or the one given could not be read.
       */
      String contactPhoneE164) {

    /** An order as recorded before its contact number was kept in international form. */
    public Order(
        UUID id,
        UUID tenantId,
        UUID storeId,
        UUID customerId,
        UUID loginId,
        String channel,
        String fulfilmentType,
        String status,
        BigDecimal subtotal,
        BigDecimal taxAmount,
        BigDecimal discountAmount,
        BigDecimal total,
        String currency,
        String notes,
        String idempotencyKey,
        Instant createdAt,
        Instant updatedAt,
        boolean taxExempt,
        String exemptReason,
        String deliveryLine1,
        String deliveryLine2,
        String deliveryCity,
        String deliveryPostalCode,
        String deliveryRecipientName,
        String deliveryRecipientPhone,
        String contactPhone,
        String paymentMethod,
        BigDecimal promotionDiscount,
        UUID sellerUserId,
        boolean allowSubstitutions,
        UUID slotWindowId,
        Instant slotStartsAt,
        Instant slotEndsAt,
        String slotTimeZone) {
      this(
          id,
          tenantId,
          storeId,
          customerId,
          loginId,
          channel,
          fulfilmentType,
          status,
          subtotal,
          taxAmount,
          discountAmount,
          total,
          currency,
          notes,
          idempotencyKey,
          createdAt,
          updatedAt,
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
          promotionDiscount,
          sellerUserId,
          allowSubstitutions,
          slotWindowId,
          slotStartsAt,
          slotEndsAt,
          slotTimeZone,
          null);
    }

    /** An order as recorded before delivery and collection slots existed: no window. */
    public Order(
        UUID id,
        UUID tenantId,
        UUID storeId,
        UUID customerId,
        UUID loginId,
        String channel,
        String fulfilmentType,
        String status,
        BigDecimal subtotal,
        BigDecimal taxAmount,
        BigDecimal discountAmount,
        BigDecimal total,
        String currency,
        String notes,
        String idempotencyKey,
        Instant createdAt,
        Instant updatedAt,
        boolean taxExempt,
        String exemptReason,
        String deliveryLine1,
        String deliveryLine2,
        String deliveryCity,
        String deliveryPostalCode,
        String deliveryRecipientName,
        String deliveryRecipientPhone,
        String contactPhone,
        String paymentMethod,
        BigDecimal promotionDiscount,
        UUID sellerUserId,
        boolean allowSubstitutions) {
      this(
          id,
          tenantId,
          storeId,
          customerId,
          loginId,
          channel,
          fulfilmentType,
          status,
          subtotal,
          taxAmount,
          discountAmount,
          total,
          currency,
          notes,
          idempotencyKey,
          createdAt,
          updatedAt,
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
          promotionDiscount,
          sellerUserId,
          allowSubstitutions,
          null,
          null,
          null,
          null);
    }

    /** An order as recorded before the shopper could say: substitutions allowed. */
    public Order(
        UUID id,
        UUID tenantId,
        UUID storeId,
        UUID customerId,
        UUID loginId,
        String channel,
        String fulfilmentType,
        String status,
        BigDecimal subtotal,
        BigDecimal taxAmount,
        BigDecimal discountAmount,
        BigDecimal total,
        String currency,
        String notes,
        String idempotencyKey,
        Instant createdAt,
        Instant updatedAt,
        boolean taxExempt,
        String exemptReason,
        String deliveryLine1,
        String deliveryLine2,
        String deliveryCity,
        String deliveryPostalCode,
        String deliveryRecipientName,
        String deliveryRecipientPhone,
        String contactPhone,
        String paymentMethod,
        BigDecimal promotionDiscount,
        UUID sellerUserId) {
      this(
          id,
          tenantId,
          storeId,
          customerId,
          loginId,
          channel,
          fulfilmentType,
          status,
          subtotal,
          taxAmount,
          discountAmount,
          total,
          currency,
          notes,
          idempotencyKey,
          createdAt,
          updatedAt,
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
          promotionDiscount,
          sellerUserId,
          true);
    }

    public static final String CHANNEL_ONLINE = "ONLINE";
    public static final String CHANNEL_POS = "POS";
    public static final String FULFILMENT_PICKUP = "PICKUP";
    public static final String FULFILMENT_DELIVERY = "DELIVERY";
    public static final String FULFILMENT_INSTORE = "INSTORE";
    public static final String STATUS_PENDING = "PENDING";

    /**
     * A catalog-mode till order: placed with the goods but no prices, waiting for a manager to
     * price it (SJ-D41). Not PENDING, so the stranded-order sweeper never cancels it; priced, it
     * becomes PENDING and takes payment like any other order.
     */
    public static final String STATUS_AWAITING_PRICE = "AWAITING_PRICE";

    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_FULFILLED = "FULFILLED";

    /** Some, not all, of the goods have been handed over (SJ-D35). */
    public static final String STATUS_PARTIALLY_FULFILLED = "PARTIALLY_FULFILLED";

    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_VOIDED = "VOIDED";
    // Set when payment-svc reports a refund (PaymentRefunded) against a sold order.
    public static final String STATUS_PARTIALLY_REFUNDED = "PARTIALLY_REFUNDED";
    public static final String STATUS_REFUNDED = "REFUNDED";
  }

  /** A quantity of one variant to put back into stock — the lines a voided sale restocks. */
  public record RestockLine(UUID variantId, BigDecimal qty) {}

  /**
   * One line of an order. {@code weighingInstrumentId} names the instrument a sold-by-weight line
   * was weighed on (Weights and Measures Act 1985 s.11), from tenant-svc's register; null for a
   * line sold by the each.
   */
  public record OrderItem(
      UUID id,
      UUID tenantId,
      UUID orderId,
      UUID variantId,
      BigDecimal qty,
      BigDecimal unitPrice,
      BigDecimal lineTotal,
      String notes,
      UUID weighingInstrumentId,
      /** How much of {@code qty} has been handed over so far (SJ-D35); never above it. */
      BigDecimal fulfilledQty,
      /**
       * The VAT on this line as the quote priced it (18.5), so a fiscal file can list the sale by
       * rate. Null for a line placed with server-side pricing off.
       */
      BigDecimal vatAmount,
      /**
       * The reduce-to-clear markdown a scanned sticker named (05.4), so pricing-svc can count the
       * sticker down once the order stands. Null for a line sold at the list price.
       */
      UUID markdownId,
      /** The VAT code the quote applied (18.9); null for a line placed before it was kept. */
      String vatCode,
      /** The VAT rate the quote applied, as a fraction: 0.2000 for 20% (18.9). */
      BigDecimal vatRate,
      /**
       * How much of {@code qty} will never be handed over: closed short by the store, or replaced
       * by a substitute (substitutions for out-of-stock online lines). The line's charge is reduced
       * pro rata to what stands.
       */
      BigDecimal shortQty,
      /** The line this one stands in for, when it is a substitute the store put in the bag. */
      UUID substitutesItemId) {

    /** A line as recorded before short closes and substitutes existed. */
    public OrderItem(
        UUID id,
        UUID tenantId,
        UUID orderId,
        UUID variantId,
        BigDecimal qty,
        BigDecimal unitPrice,
        BigDecimal lineTotal,
        String notes,
        UUID weighingInstrumentId,
        BigDecimal fulfilledQty,
        BigDecimal vatAmount,
        UUID markdownId,
        String vatCode,
        BigDecimal vatRate) {
      this(
          id,
          tenantId,
          orderId,
          variantId,
          qty,
          unitPrice,
          lineTotal,
          notes,
          weighingInstrumentId,
          fulfilledQty,
          vatAmount,
          markdownId,
          vatCode,
          vatRate,
          BigDecimal.ZERO,
          null);
    }

    /** A line as recorded before its VAT code and rate were kept. */
    public OrderItem(
        UUID id,
        UUID tenantId,
        UUID orderId,
        UUID variantId,
        BigDecimal qty,
        BigDecimal unitPrice,
        BigDecimal lineTotal,
        String notes,
        UUID weighingInstrumentId,
        BigDecimal fulfilledQty,
        BigDecimal vatAmount,
        UUID markdownId) {
      this(
          id,
          tenantId,
          orderId,
          variantId,
          qty,
          unitPrice,
          lineTotal,
          notes,
          weighingInstrumentId,
          fulfilledQty,
          vatAmount,
          markdownId,
          null,
          null);
    }

    /** A line as placed: nothing handed over yet, VAT unknown, no sticker. */
    public OrderItem(
        UUID id,
        UUID tenantId,
        UUID orderId,
        UUID variantId,
        BigDecimal qty,
        BigDecimal unitPrice,
        BigDecimal lineTotal,
        String notes,
        UUID weighingInstrumentId) {
      this(
          id,
          tenantId,
          orderId,
          variantId,
          qty,
          unitPrice,
          lineTotal,
          notes,
          weighingInstrumentId,
          BigDecimal.ZERO,
          null,
          null);
    }

    /** A line as placed with its VAT known: nothing handed over yet, no sticker. */
    public OrderItem(
        UUID id,
        UUID tenantId,
        UUID orderId,
        UUID variantId,
        BigDecimal qty,
        BigDecimal unitPrice,
        BigDecimal lineTotal,
        String notes,
        UUID weighingInstrumentId,
        BigDecimal fulfilledQty,
        BigDecimal vatAmount) {
      this(
          id,
          tenantId,
          orderId,
          variantId,
          qty,
          unitPrice,
          lineTotal,
          notes,
          weighingInstrumentId,
          fulfilledQty,
          vatAmount,
          null);
    }

    /** What is still to be handed over: not yet picked, and not closed short. */
    public BigDecimal remainingQty() {
      return qty.subtract(fulfilledQty == null ? BigDecimal.ZERO : fulfilledQty)
          .subtract(shortQty == null ? BigDecimal.ZERO : shortQty);
    }

    /** What stands of the line: ordered less what was closed short. */
    public BigDecimal standingQty() {
      return qty.subtract(shortQty == null ? BigDecimal.ZERO : shortQty);
    }
  }

  /**
   * Append-only record of a manual discount granted on an order (SJ-D6).
   *
   * <p>{@code grantedRole} is the caller role whose ceiling authorised the amount. It is stored
   * rather than looked up later because role assignments change: without it, "was this discount
   * within the grantor's authority at the time?" becomes unanswerable.
   */
  public record OrderDiscount(
      UUID id,
      UUID tenantId,
      UUID orderId,
      UUID storeId,
      BigDecimal subtotal,
      BigDecimal discountAmount,
      BigDecimal discountPct,
      String reason,
      UUID grantedBy,
      String grantedRole,
      Instant createdAt) {}

  /** Append-only status audit log. */
  public record OrderStatusHistory(
      UUID id,
      UUID tenantId,
      UUID orderId,
      String fromStatus,
      String toStatus,
      String reason,
      UUID changedBy,
      Instant changedAt) {}

  // ── Returns (Gap #14) ─────────────────────────────────────────────────────

  public record Return(
      UUID id,
      UUID tenantId,
      UUID orderId,
      UUID storeId,
      String reason,
      BigDecimal refundAmount,
      String refundMethod,
      String status,
      Instant createdAt,
      Instant completedAt,
      UUID createdBy) {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String METHOD_ORIGINAL = "ORIGINAL";
    public static final String METHOD_STORE_CREDIT = "STORE_CREDIT";
    public static final String METHOD_GIFT_CARD = "GIFT_CARD";
  }

  public record ReturnItem(
      UUID id,
      UUID tenantId,
      UUID returnId,
      UUID variantId,
      BigDecimal qty,
      BigDecimal refundAmount,
      String condition) {}

  // ── Post-void (Gap #14) ───────────────────────────────────────────────────

  /** Post-void record; cancels a completed POS transaction before EOD. Append-only. */
  public record PosVoidLog(
      UUID id,
      UUID tenantId,
      UUID orderId,
      UUID storeId,
      String reason,
      UUID voidedBy,
      Instant voidedAt) {}

  // ── Layaway (Gap #14) ─────────────────────────────────────────────────────

  public record Layaway(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID customerId,
      BigDecimal totalAmount,
      BigDecimal depositPaid,
      BigDecimal balance,
      String status,
      String notes,
      Instant createdAt,
      Instant dueDate,
      Instant completedAt,
      Instant cancelledAt) {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_CANCELLED = "CANCELLED";
  }

  public record LayawayDeposit(
      UUID id,
      UUID tenantId,
      UUID layawayId,
      BigDecimal amount,
      String paymentMethod,
      String reference,
      Instant paidAt) {}

  public record LayawayItem(
      UUID id,
      UUID tenantId,
      UUID layawayId,
      UUID variantId,
      BigDecimal qty,
      BigDecimal unitPrice,
      BigDecimal lineTotal) {}

  // ── Gift cards (Gap #14) ──────────────────────────────────────────────────

  public record GiftCard(
      UUID id,
      UUID tenantId,
      UUID storeId,
      String code,
      BigDecimal initialBalance,
      BigDecimal currentBalance,
      String status,
      String currency,
      Instant issuedAt,
      Instant expiresAt) {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DEPLETED = "DEPLETED";
    public static final String STATUS_CANCELLED = "CANCELLED";
  }

  /** Append-only debit/credit ledger for a gift card. */
  public record GiftCardTransaction(
      UUID id,
      UUID tenantId,
      UUID giftCardId,
      String txType,
      BigDecimal amount,
      BigDecimal balanceBefore,
      BigDecimal balanceAfter,
      UUID orderId,
      String reference,
      Instant createdAt) {
    public static final String TX_ISSUE = "ISSUE";
    public static final String TX_RELOAD = "RELOAD";
    public static final String TX_REDEEM = "REDEEM";
    public static final String TX_REFUND = "REFUND";
    public static final String TX_CANCEL = "CANCEL";
  }

  // ── Gap #42: Special orders ───────────────────────────────────────────────

  public record SpecialOrder(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID customerId,
      String customerName,
      String customerPhone,
      String customerEmail,
      String deliveryAddress,
      java.time.LocalDate requestedDeliveryDate,
      String notes,
      String status,
      BigDecimal subtotal,
      BigDecimal total,
      String currency,
      String idempotencyKey,
      Instant createdAt,
      Instant updatedAt) {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_FULFILLED = "FULFILLED";
    public static final String STATUS_CANCELLED = "CANCELLED";
  }

  public record SpecialOrderItem(
      UUID id,
      UUID tenantId,
      UUID soId,
      UUID variantId,
      BigDecimal qty,
      BigDecimal unitPrice,
      BigDecimal lineTotal,
      String notes) {}

  /** Append-only status audit for special orders. */
  public record SpecialOrderStatusHistory(
      UUID id,
      UUID tenantId,
      UUID soId,
      String fromStatus,
      String toStatus,
      String reason,
      UUID changedBy,
      Instant changedAt) {}

  // ── Gap #43: POSLog entry (append-only) ───────────────────────────────────

  /**
   * One line of the staff exception report: everything one cashier (or one store) did over the
   * period that loss prevention cares about, with the sales count that makes it a rate rather than
   * a ranking of who worked hardest.
   *
   * <p>{@code sales} and {@code salesValue} come from the POS transaction journal. They are zero
   * when nothing journalled the sale, which is not the same as "this person made no sales" — the
   * report says so rather than dividing by it.
   */
  public record ExceptionRow(
      String groupKey,
      long discounts,
      java.math.BigDecimal discountAmount,
      long voids,
      long noSales,
      long sales,
      java.math.BigDecimal salesValue) {}

  /** How the exception report buckets its rows. */
  public enum ExceptionGrouping {
    ACTOR,
    STORE
  }

  /**
   * One hour of the trading day.
   *
   * <p>Hours with no trade are absent from the report rather than present as zeroes: a row of
   * zeroes asserts the shop was open and nobody came, which is a different fact from the shop being
   * shut, and only the caller knows which.
   *
   * @param hourOfDay 0-23 on the clock of the timezone the report was asked for, not UTC
   * @param orders how many revenue orders fell in this hour
   * @param grossAmount their total, after any discount
   * @param discountAmount how much was discounted away inside it
   * @param averageBasket gross divided by orders — null is impossible here, since an hour with no
   *     orders produces no row at all
   */
  public record SalesByHourRow(
      int hourOfDay,
      long orders,
      java.math.BigDecimal grossAmount,
      java.math.BigDecimal discountAmount,
      java.math.BigDecimal averageBasket) {}

  /**
   * One member of staff's takings.
   *
   * <p>Sourced from the POS transaction journal, so it covers in-store sales only — an online order
   * has no cashier. {@code UNATTRIBUTED} buckets journal entries that name nobody rather than
   * dropping them.
   *
   * @param groupKey the cashier's user id, or UNATTRIBUTED
   * @param sales how many sales they journalled
   * @param grossAmount what those sales came to
   * @param discountAmount how much they discounted away
   * @param averageBasket gross divided by sales
   * @param discountRate discount as a percentage of gross plus discount — what the sale would have
   *     been worth undiscounted. Null when there is nothing to take a percentage of.
   */
  public record SalesByStaffRow(
      String groupKey,
      long sales,
      java.math.BigDecimal grossAmount,
      java.math.BigDecimal discountAmount,
      java.math.BigDecimal averageBasket,
      java.math.BigDecimal discountRate) {}

  public record PosLogEntry(
      UUID id,
      UUID tenantId,
      UUID orderId,
      UUID storeId,
      UUID cashierId,
      BigDecimal subtotal,
      BigDecimal taxAmount,
      BigDecimal discountAmount,
      BigDecimal total,
      String currency,
      boolean taxExempt,
      String exemptReason,
      Instant transactionTs,
      Instant createdAt) {}

  // ── Gap #44: Receipt log (append-only) ───────────────────────────────────

  public record OrderReceipt(
      UUID id,
      UUID tenantId,
      UUID orderId,
      String receiptType,
      String emailedTo,
      int printCount,
      Instant generatedAt) {
    public static final String TYPE_PRINT = "PRINT";
    public static final String TYPE_EMAIL = "EMAIL";
  }

  // ── Gap #50: SIM ↔ POS sync — local stock projection ─────────────────────

  /** Local on-hand projection maintained from inventory-svc events. Read-only for POS screens. */
  public record PosStockPosition(
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      java.math.BigDecimal onHandQty,
      Instant updatedAt) {}

  // ── Business audit trail (20.11) ──────────────────────────────────────────

  /**
   * One sensitive action read back from the append-only log that recorded it: who did what, when,
   * at which store, to which order, with what money and reason. {@code detail} is the log's own
   * qualifier — the role that authorised a discount, the status a cancel came from, the refund
   * method of a return, the supervisor who authorised a no-sale.
   */
  public record AuditEvent(
      UUID id,
      String type,
      Instant occurredAt,
      UUID actorId,
      UUID storeId,
      UUID orderId,
      BigDecimal amount,
      String reason,
      String detail) {
    public static final String TYPE_DISCOUNT = "DISCOUNT";
    public static final String TYPE_VOID = "VOID";
    public static final String TYPE_NO_SALE = "NO_SALE";
    public static final String TYPE_CANCEL = "CANCEL";
    public static final String TYPE_RETURN = "RETURN";
    public static final java.util.List<String> TYPES =
        java.util.List.of(TYPE_DISCOUNT, TYPE_VOID, TYPE_NO_SALE, TYPE_CANCEL, TYPE_RETURN);
  }

  // ── Deposit return (09.16) ────────────────────────────

  /** The deposit a return scheme put on one line's drinks containers at the sale (09.16). */
  public record OrderDeposit(
      UUID id,
      UUID tenantId,
      UUID orderId,
      UUID orderItemId,
      UUID variantId,
      String material,
      int volumeMl,
      BigDecimal qty,
      BigDecimal depositEach,
      BigDecimal amount,
      String currency,
      /** OUTSIDE_SCOPE (VATA 1994 s.55B) or STANDARD (taxed as the drink, VerpackG §31). */
      String vatTreatment,
      BigDecimal vatRate,
      BigDecimal vatAmount,
      String schemeScope,
      String citation,
      java.time.Instant createdAt) {
    public static final String VAT_OUTSIDE_SCOPE = "OUTSIDE_SCOPE";
    public static final String VAT_STANDARD = "STANDARD";
  }

  /** One kind of container handed back at the till (09.16). */
  public record ContainerRefundLine(
      String material, int volumeMl, int count, BigDecimal depositEach, BigDecimal amount) {}

  /** Deposits paid back at the till for containers brought back (09.16). */
  public record ContainerRefund(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID tillSessionId,
      String currency,
      int containers,
      BigDecimal amount,
      String schemeScope,
      String idempotencyKey,
      UUID refundedBy,
      java.time.Instant createdAt,
      java.util.List<ContainerRefundLine> lines) {}

  /** Deposits charged and refunded over a period, by material (09.16). */
  public record DepositReportRow(
      String material,
      long chargedContainers,
      BigDecimal chargedAmount,
      BigDecimal chargedVat,
      long refundedContainers,
      BigDecimal refundedAmount) {}
}
