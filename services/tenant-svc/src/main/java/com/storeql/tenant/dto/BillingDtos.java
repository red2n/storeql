package com.storeql.tenant.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Request and response shapes of subscription billing and invoicing (21.9). */
public final class BillingDtos {

  private BillingDtos() {}

  // ── the platform's own identity ─────────────────────────────────────────────

  /**
   * What the platform bills as. Nothing is invoiced until this is set: an invoice with no seller is
   * not an invoice anywhere the platform trades.
   */
  @Schema(name = "BillingProfileRequest")
  public record ProfileRequest(
      @NotBlank @Size(max = 200) String legalName,
      // Required, because the table requires them: an address is what makes an invoice an invoice,
      // and letting them through as null turns a missing field into a 500 rather than a 400.
      @NotBlank @Size(max = 200) String addressLine1,
      @Size(max = 200) String addressLine2,
      @NotBlank @Size(max = 120) String city,
      @Size(max = 20) String postcode,
      @Schema(description = "ISO 3166-1 alpha-2; decides which VAT treatment a sale falls under.")
          @NotBlank
          @Size(min = 2, max = 2)
          String country,
      @Size(max = 40) String vatNumber,
      @Size(max = 40) String companyNumber,
      @Schema(description = "What an invoice number begins with, e.g. INV.")
          @NotBlank
          @Size(max = 12)
          String invoicePrefix,
      @Schema(description = "How many days after issue an invoice falls due.") @NotNull @Min(0)
          Integer paymentTermsDays,
      @Schema(description = "The platform's own standard rate, as a fraction: 0.2000 for 20%.")
          @NotNull
          @DecimalMin("0.0000")
          @Digits(integer = 1, fraction = 4)
          BigDecimal taxRate,
      @Size(max = 2000) String bankDetails) {}

  @Schema(name = "BillingProfile")
  public record ProfileResponse(
      String legalName,
      String addressLine1,
      String addressLine2,
      String city,
      String postcode,
      String country,
      String vatNumber,
      String companyNumber,
      String invoicePrefix,
      int paymentTermsDays,
      BigDecimal taxRate,
      String bankDetails,
      String updatedAt) {}

  /**
   * A rate the platform charges in a country it has to. Never guessed: a country with no rate
   * refuses the invoice rather than inventing one.
   */
  @Schema(name = "VatRateRequest")
  public record RateRequest(
      @NotBlank @Size(min = 2, max = 2) String country,
      @Schema(description = "The day it takes effect; the latest one not after the invoice's date.")
          @NotBlank
          @Size(max = 10)
          String effectiveFrom,
      @NotNull @DecimalMin("0.0000") @Digits(integer = 1, fraction = 4) BigDecimal rate,
      @Size(max = 500) String note) {}

  @Schema(name = "VatRate")
  public record RateResponse(String country, String effectiveFrom, BigDecimal rate, String note) {}

  // ── a subscription ──────────────────────────────────────────────────────────

  /** Where the business is, for billing, and the legal name its invoice carries. */
  @Schema(name = "BillingDetailsRequest")
  public record BuyerRequest(
      @Schema(description = "The legal name it is invoiced as.") @Size(max = 200) String name,
      @Size(max = 200) String line1,
      @Size(max = 200) String line2,
      @Size(max = 120) String city,
      @Size(max = 20) String postcode,
      @Schema(
              description =
                  "ISO 3166-1 alpha-2. Where the business is established, which is not the same"
                      + " question as where its shops are, and decides the VAT treatment.")
          @NotBlank
          @Size(min = 2, max = 2)
          String country,
      @Schema(
              description =
                  "The business's own VAT number. Changing it clears any check against the old one:"
                      + " a number nobody has verified is treated as no number, so the business is"
                      + " charged its own country's rate until somebody checks the new one.")
          @Size(max = 40)
          String vatNumber,
      @Schema(description = "Where invoices are sent; the owner's address when unset.")
          @Email
          @Size(max = 320)
          String billingEmail) {}

  /**
   * A VAT number and the check behind it. Recording a check is not the same as having a number: the
   * reverse charge rests on the number being real, so who checked it and when is kept.
   */
  @Schema(name = "VatCheckRequest")
  public record VatCheckRequest(
      @NotBlank @Size(max = 40) String vatNumber,
      @Schema(description = "VIES, MANUAL or SIMULATED.") @NotBlank @Size(max = 20)
          String source) {}

  @Schema(name = "SubscriptionBuyer")
  public record BuyerResponse(
      String name,
      String line1,
      String line2,
      String city,
      String postcode,
      String country,
      String vatNumber,
      @Schema(description = "When the number was verified; null when nobody has.")
          String vatCheckedAt,
      String vatCheckSource,
      @Schema(
              description =
                  "Whether the number may be relied on. False means the business is charged its own"
                      + " country's rate, not the reverse charge.")
          boolean vatChecked) {}

  @Schema(name = "Subscription")
  public record SubscriptionResponse(
      String id,
      String planId,
      String planCode,
      String planName,
      @Schema(description = "TRIALING, ACTIVE, PAST_DUE, SUSPENDED or CANCELLED.") String status,
      @Schema(description = "What it was sold the plan at, locked at the sale.")
          BigDecimal priceAmount,
      String currency,
      String billingInterval,
      String periodStart,
      @Schema(description = "The day the next period begins; this one does not include it.")
          String periodEnd,
      String trialEnd,
      @Schema(description = "A plan change waiting for this period to finish.")
          String pendingPlanId,
      boolean cancelAtPeriodEnd,
      BuyerResponse buyer,
      String billingEmail,
      String startedAt,
      String cancelledAt) {}

  /** One thing that happened to a subscription. Append-only. */
  @Schema(name = "SubscriptionEvent")
  public record EventResponse(String id, String kind, String detail, String createdAt) {}

  @Schema(name = "SubscriptionFile")
  public record SubscriptionFileResponse(
      SubscriptionResponse subscription, List<EventResponse> events) {
    public SubscriptionFileResponse {
      events = events == null ? List.of() : List.copyOf(events);
    }
  }

  /** Which plan to move to, and when that takes effect. */
  @Schema(name = "PlanChangeRequest")
  public record PlanChangeRequest(
      @NotNull java.util.UUID planId,
      @Schema(
              description =
                  "NOW bills the days left on the new price against what was billed for them on the"
                      + " old one; PERIOD_END waits for the period already paid for.")
          @NotBlank
          @Size(max = 12)
          String when) {}

  @Schema(name = "CancelRequest")
  public record CancelRequest(@Size(max = 500) String reason) {}

  // ── an invoice ──────────────────────────────────────────────────────────────

  @Schema(name = "InvoiceLine")
  public record LineResponse(
      int lineNo,
      @Schema(description = "PLAN, PRORATION, CREDIT or USAGE (beyond the plan, in arrears).")
          String kind,
      String description,
      BigDecimal quantity,
      BigDecimal unitAmount,
      BigDecimal amount) {}

  @Schema(name = "InvoicePayment")
  public record PaymentResponse(
      String id,
      BigDecimal amount,
      String currency,
      String method,
      String provider,
      String providerRef,
      String receivedOn) {}

  @Schema(name = "Invoice")
  public record InvoiceResponse(
      String id,
      @Schema(
              description =
                  "The business the invoice was sent to, so the platform's receivables can name"
                      + " whose each one is. On a business's own answers it is always its own id.")
          String tenantId,
      @Schema(description = "Gapless within its year.") String number,
      @Schema(
              description =
                  "PERIOD (one per period) or ADJUSTMENT (a proration, any number per period).")
          String kind,
      @Schema(description = "OPEN, PAID, VOID or UNCOLLECTIBLE.") String status,
      String issueDate,
      String dueDate,
      String periodStart,
      String periodEnd,
      String currency,
      BigDecimal netAmount,
      @Schema(description = "DOMESTIC, REVERSE_CHARGE, DESTINATION or OUT_OF_SCOPE.")
          String taxTreatment,
      BigDecimal taxRate,
      BigDecimal taxAmount,
      BigDecimal totalAmount,
      BigDecimal amountPaid,
      BigDecimal outstanding,
      String voidedReason) {}

  /** An invoice with everything printed on it, as the detail screen and a PDF need it. */
  @Schema(name = "InvoiceFile")
  public record InvoiceFileResponse(
      InvoiceResponse invoice,
      List<LineResponse> lines,
      List<PaymentResponse> payments,
      @Schema(description = "The seller as it stood the day it was issued.") String sellerSnapshot,
      @Schema(description = "The buyer as it stood the day it was issued.") String buyerSnapshot,
      String buyerVatNumber,
      @Schema(
              description =
                  "The words a reverse-charge invoice must carry (Directive 2006/112/EC art."
                      + " 226(11a)); absent on any other treatment.")
          String taxNote) {
    public InvoiceFileResponse {
      lines = lines == null ? List.of() : List.copyOf(lines);
      payments = payments == null ? List.of() : List.copyOf(payments);
    }
  }

  /** Money the platform has received against an invoice. */
  @Schema(name = "RecordPaymentRequest")
  public record RecordPaymentRequest(
      @NotNull @DecimalMin("0.01") @Digits(integer = 14, fraction = 2) BigDecimal amount,
      @Schema(description = "BANK_TRANSFER or CARD.") @NotBlank @Size(max = 20) String method,
      @Size(max = 40) String provider,
      @Size(max = 120) String providerRef,
      @Schema(description = "The day it arrived; today when unset.") @Size(max = 10)
          String receivedOn) {}

  @Schema(name = "VoidInvoiceRequest")
  public record VoidRequest(@NotBlank @Size(max = 500) String reason) {}

  /** A subscription the run passed over, and why. */
  @Schema(name = "BillingRunSkipped")
  public record SkippedResponse(String tenantId, String code, String reason) {}

  /**
   * What the billing run did.
   *
   * @param skipped the businesses it could not bill. One the platform has no tax position for must
   *     not stop everybody else being billed, so the run passes it over and names it here rather
   *     than stopping — but it is named, because an unbilled business is money not asked for.
   */
  @Schema(name = "BillingRunResponse")
  public record RunResponse(
      @Schema(description = "The day the run was for.") String asOf,
      int invoicesRaised,
      List<InvoiceResponse> invoices,
      List<SkippedResponse> skipped) {
    public RunResponse {
      invoices = invoices == null ? List.of() : List.copyOf(invoices);
      skipped = skipped == null ? List.of() : List.copyOf(skipped);
    }
  }

  /**
   * How hard the platform chases what it is owed.
   *
   * <p>The order of the stages is the policy, and it is refused if it is wrong: the service cannot
   * be interrupted before the last reminder has gone, and a debt cannot be given up on before the
   * service was interrupted.
   */
  @Schema(name = "DunningPolicyRequest")
  public record DunningPolicyRequest(
      @NotNull Boolean enabled,
      @Schema(description = "Days after the due date, e.g. [1, 3, 5, 7].")
          @NotNull
          @Size(min = 1, max = 12)
          List<@Min(1) @Max(365) Integer> reminderDays,
      @NotNull @Min(1) @Max(365) Integer suspendAfterDays,
      @NotNull @Min(2) @Max(730) Integer uncollectibleAfterDays) {}

  /**
   * @param set false when nobody has set a policy and these are the published defaults — said
   *     rather than implied, because "who decided this?" has no answer until somebody does
   */
  @Schema(name = "DunningPolicy")
  public record DunningPolicyResponse(
      boolean enabled,
      List<Integer> reminderDays,
      int suspendAfterDays,
      int uncollectibleAfterDays,
      boolean set,
      String updatedAt) {
    public DunningPolicyResponse {
      reminderDays = reminderDays == null ? List.of() : List.copyOf(reminderDays);
    }
  }

  /** One thing done about one overdue invoice. Append-only. */
  @Schema(name = "DunningEvent")
  public record DunningEventResponse(
      String id,
      @Schema(description = "REMINDER_n, SUSPENDED, UNCOLLECTIBLE, DUE_DATE_EXTENDED or RESOLVED.")
          String step,
      String detail,
      String createdAt) {}

  /**
   * An overdue invoice on the receivables screen.
   *
   * @param stage the last step taken, or null when it has not been chased yet
   * @param nextStep what it earns next, so an operator can see what is about to happen rather than
   *     find out from a suspended customer
   */
  @Schema(name = "OverdueInvoice")
  public record OverdueResponse(
      String invoiceId,
      String tenantId,
      String number,
      String dueDate,
      int daysOverdue,
      String stage,
      String nextStep) {}

  /** What one dunning run did, and what it could not do. */
  @Schema(name = "DunningRunResponse")
  public record DunningRunResponse(
      String asOf, int stepsTaken, List<DunningStepResponse> taken, List<SkippedResponse> skipped) {
    public DunningRunResponse {
      taken = taken == null ? List.of() : List.copyOf(taken);
      skipped = skipped == null ? List.of() : List.copyOf(skipped);
    }
  }

  @Schema(name = "DunningStep")
  public record DunningStepResponse(String tenantId, String invoiceNumber, String step) {}

  /**
   * The link a notice carries.
   *
   * <p>Returned once, here, because only its hash is kept — asking again mints a new one and the
   * old link stops working. A link is a capability, and the newest notice is the one to act on.
   */
  @Schema(name = "PayLink")
  public record PayLinkResponse(String token) {}

  /** A promise to pay: the date moves out, the debt does not move at all. */
  @Schema(name = "ExtendDueDateRequest")
  public record ExtendDueDateRequest(
      @NotBlank @Size(max = 10) String dueDate, @Size(max = 500) String reason) {}
}
