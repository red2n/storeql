package com.storeql.tenant.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.tenant.domain.Dunning;
import com.storeql.tenant.domain.Subscriptions;
import com.storeql.tenant.dto.BillingDtos;
import com.storeql.tenant.dto.Dtos.RecordDutyRequest;
import com.storeql.tenant.dto.StatutoryDtos;
import com.storeql.tenant.service.BillingService;
import com.storeql.tenant.service.DunningService;
import com.storeql.tenant.service.SecurityIncidentService;
import com.storeql.tenant.service.StatutoryService;
import com.storeql.tenant.service.SubscriptionService;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * The billing, security-notice and statutory-filing routes check their bodies with common-web
 * {@code Validations.validate}, not with {@code @Valid}: a bad body is the platform's {@code 400
 * VALIDATION_FAILED} problem (Helidon's own constraint mapper answered these before, in its own
 * shape), a number no rate or amount could be ({@code 1E+80000000}) is {@code "<field>: is out of
 * range"}, a hole in a list is {@code "<field>[i]: must not be null"} rather than a 500, and
 * nothing reaches the service. Who the caller is is still asked first.
 *
 * <p>The real {@link TenantContext} decides; the services are stubs that record what reached them
 * and stop there.
 */
class BillingBodyValidationTest {

  private static final UUID TENANT = Ids.newId();

  /** What no rate or amount is: too many whole digits, too many places, an int wrap. */
  private static final List<String> ABSURD = List.of("1E+80000000", "1E-80000000", "1E+2147483647");

  private static TenantContext caller(UUID tenant, String role, Set<UUID> heldTo) {
    TenantContext ctx = new TenantContext();
    try {
      Method set =
          TenantContext.class.getDeclaredMethod(
              "set", UUID.class, UUID.class, Set.class, Set.class, String.class);
      set.setAccessible(true);
      set.invoke(ctx, tenant, Ids.newId(), Set.of(role), heldTo, "req");
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("could not populate a TenantContext", e);
    }
    return ctx;
  }

  private static TenantContext platformAdmin() {
    return caller(null, "PLATFORM_ADMIN", Set.of());
  }

  private static ApiException refused(Executable act, int status, String code) {
    ApiException e = assertThrows(ApiException.class, act);
    assertEquals(status, e.status(), e.getMessage() + " " + e.details());
    assertEquals(code, e.code(), e.getMessage() + " " + e.details());
    return e;
  }

  private static void invalid(Executable act, String... details) {
    assertEquals(
        Arrays.asList(details), refused(act, 400, "VALIDATION_FAILED").details(), "details");
  }

  private static void fieldRefused(Executable act, String field) {
    ApiException e = refused(act, 400, "VALIDATION_FAILED");
    assertEquals(1, e.details().size(), e.details().toString());
    assertTrue(e.details().get(0).startsWith(field + ": "), e.details().toString());
  }

  /** Where a stub stops once a call has reached it: the test is about what gets that far. */
  private static final class Reached extends RuntimeException {
    private static final long serialVersionUID = 1L;

    Reached(String what) {
      super(what);
    }
  }

  private static void reaches(Executable act) {
    assertThrows(Reached.class, act);
  }

  private static final class Billing extends BillingService {
    final List<String> acted = new ArrayList<>();

    @Override
    public Subscriptions.BillingProfile saveProfile(BillingDtos.ProfileRequest req, UUID actorId) {
      acted.add("profile " + req.taxRate());
      throw new Reached("profile");
    }

    @Override
    public List<Subscriptions.VatRate> saveRate(BillingDtos.RateRequest req, UUID actorId) {
      acted.add("rate " + req.country() + " " + req.rate());
      return List.of();
    }

    @Override
    public Subscriptions.InvoiceFile recordPayment(
        UUID invoiceId, BillingDtos.RecordPaymentRequest req, UUID actorId) {
      acted.add("payment " + req.amount());
      throw new Reached("payment");
    }

    @Override
    public Subscriptions.InvoiceFile voidInvoice(UUID invoiceId, String reason) {
      acted.add("void " + reason);
      throw new Reached("void");
    }
  }

  private static final class Subs extends SubscriptionService {
    final List<String> acted = new ArrayList<>();

    @Override
    public Subscriptions.SubscriptionFile recordVatCheck(
        UUID tenantId, String vatNumber, String source, UUID actorId) {
      acted.add("vat check " + vatNumber);
      throw new Reached("vat check");
    }

    @Override
    public Subscriptions.SubscriptionFile setDetails(
        UUID tenantId, BillingDtos.BuyerRequest req, UUID actorId) {
      acted.add("details " + req.country());
      throw new Reached("details");
    }

    @Override
    public Subscriptions.SubscriptionFile changePlan(
        UUID tenantId, BillingDtos.PlanChangeRequest req, UUID actorId) {
      acted.add("plan " + req.when());
      throw new Reached("plan");
    }

    @Override
    public Subscriptions.SubscriptionFile cancelAtPeriodEnd(
        UUID tenantId, String reason, UUID actorId) {
      acted.add("cancel " + reason);
      throw new Reached("cancel");
    }
  }

  private static final class Chase extends DunningService {
    final List<String> acted = new ArrayList<>();

    @Override
    public Dunning.Policy setPolicy(Dunning.Policy wanted, UUID actorId) {
      acted.add("policy " + wanted.reminderDays());
      throw new Reached("policy");
    }

    @Override
    public Subscriptions.Invoice extendDueDate(
        UUID invoiceId, LocalDate to, String reason, UUID actorId) {
      acted.add("due " + to);
      throw new Reached("due");
    }
  }

  private static PlatformBillingResource platform(
      Billing billing, Subs subs, Chase chase, TenantContext ctx) {
    PlatformBillingResource r = new PlatformBillingResource();
    r.svc = billing;
    r.subscriptions = subs;
    r.dunning = chase;
    r.ctx = ctx;
    r.testClock = true;
    return r;
  }

  private static BillingDtos.RateRequest rate(String value) {
    return new BillingDtos.RateRequest("IE", "2026-01-01", new BigDecimal(value), null);
  }

  private static BillingDtos.ProfileRequest profile(String taxRate) {
    return profile(14, taxRate);
  }

  private static BillingDtos.ProfileRequest profile(int paymentTermsDays, String taxRate) {
    return new BillingDtos.ProfileRequest(
        "StoreQL Platform Ltd",
        "1 Quay Street",
        null,
        "Dublin",
        "D02 XY45",
        "IE",
        "IE1234567X",
        null,
        "INV",
        paymentTermsDays,
        new BigDecimal(taxRate),
        null);
  }

  // ── the platform's own billing ─────────────────────────────────────────────

  @Test
  @DisplayName(
      "A VAT rate no rate could be is refused as out of range, and no rate is written; a real one"
          + " is")
  void aVatRateNoRateCouldBeIsRefused() {
    Billing billing = new Billing();
    PlatformBillingResource r = platform(billing, new Subs(), new Chase(), platformAdmin());
    for (String absurd : ABSURD) {
      invalid(() -> r.saveRate(rate(absurd)), "rate: is out of range");
    }
    // With the rest of what is wrong, as before: each field once, sorted.
    invalid(
        () -> r.saveRate(new BillingDtos.RateRequest(null, "2026-01-01", null, null)),
        "country: must not be blank",
        "rate: must not be null");
    refused(() -> r.saveRate(null), 400, "BODY_REQUIRED");
    assertEquals(List.of(), billing.acted, "no rate was written");

    r.saveRate(rate("0.2300"));
    assertEquals(List.of("rate IE 0.2300"), billing.acted);
  }

  @Test
  @DisplayName(
      "A VAT rate and the platform's own rate are fractions below one, and payment terms at most"
          + " 180 days, as their tables hold them: 1 and 9.9999 passed @Digits and broke"
          + " chk_platform_vat_rate or chk_billing_profile_rate as a 500, and 181 days"
          + " chk_billing_profile_terms; each is refused, and nothing is written")
  void ratesAndTermsAreHeldToTheirTables() {
    Billing billing = new Billing();
    PlatformBillingResource r = platform(billing, new Subs(), new Chase(), platformAdmin());
    for (String whole : List.of("1", "1.0000", "9.9999")) {
      invalid(() -> r.saveRate(rate(whole)), "rate: must be less than 1");
      invalid(() -> r.saveProfile(profile(whole)), "taxRate: must be less than 1");
    }
    for (int terms : List.of(181, 365, Integer.MAX_VALUE)) {
      invalid(
          () -> r.saveProfile(profile(terms, "0.2300")),
          "paymentTermsDays: must be less than or equal to 180");
    }
    invalid(
        () -> r.saveProfile(profile(-1, "0.2300")),
        "paymentTermsDays: must be greater than or equal to 0");
    assertEquals(List.of(), billing.acted, "nothing was written");

    // At the edge, and written with zeros a column keeps unchanged, each is taken.
    r.saveRate(rate("0.9999"));
    r.saveRate(rate("0.23000000"));
    reaches(() -> r.saveProfile(profile(180, "0.99990")));
    reaches(() -> r.saveProfile(profile(0, "0")));
    assertEquals(
        List.of("rate IE 0.9999", "rate IE 0.23000000", "profile 0.99990", "profile 0"),
        billing.acted);
  }

  @Test
  @DisplayName("Only the platform administrator is asked what they sent")
  void theRoleIsAskedBeforeTheBody() {
    Billing billing = new Billing();
    Subs subs = new Subs();
    Chase chase = new Chase();
    PlatformBillingResource r = platform(billing, subs, chase, caller(TENANT, "OWNER", Set.of()));
    refused(() -> r.saveRate(rate("1E+80000000")), 403, "FORBIDDEN");
    refused(() -> r.saveProfile(profile("1E+80000000")), 403, "FORBIDDEN");
    refused(
        () ->
            r.recordPayment(
                Ids.newId(),
                new BillingDtos.RecordPaymentRequest(
                    new BigDecimal("1E+80000000"), "CARD", null, null, null)),
        403,
        "FORBIDDEN");
    assertEquals(List.of(), billing.acted);
  }

  @Test
  @DisplayName(
      "The platform's profile, a payment, a VAT check, a void and a due date refuse what is wrong"
          + " in the platform's own problem, and reach the service only when right")
  void everyPlatformBillingBodyIsChecked() {
    Billing billing = new Billing();
    Subs subs = new Subs();
    Chase chase = new Chase();
    PlatformBillingResource r = platform(billing, subs, chase, platformAdmin());
    UUID invoice = Ids.newId();

    for (String absurd : ABSURD) {
      invalid(() -> r.saveProfile(profile(absurd)), "taxRate: is out of range");
      invalid(
          () ->
              r.recordPayment(
                  invoice,
                  new BillingDtos.RecordPaymentRequest(
                      new BigDecimal(absurd), "CARD", null, null, null)),
          "amount: is out of range");
    }
    refused(() -> r.saveProfile(null), 400, "BODY_REQUIRED");
    fieldRefused(
        () ->
            r.recordPayment(
                invoice,
                new BillingDtos.RecordPaymentRequest(
                    new BigDecimal("0"), "CARD", null, null, null)),
        "amount");
    refused(() -> r.recordPayment(invoice, null), 400, "BODY_REQUIRED");
    fieldRefused(
        () -> r.recordVatCheck(TENANT, new BillingDtos.VatCheckRequest("IE1234567X", "")),
        "source");
    fieldRefused(() -> r.voidInvoice(invoice, new BillingDtos.VoidRequest(" ")), "reason");
    refused(() -> r.voidInvoice(invoice, null), 400, "BODY_REQUIRED");
    fieldRefused(
        () -> r.extendDueDate(invoice, new BillingDtos.ExtendDueDateRequest("2026-10-302", null)),
        "dueDate");
    refused(() -> r.extendDueDate(invoice, null), 400, "BODY_REQUIRED");
    assertEquals(List.of(), billing.acted);
    assertEquals(List.of(), subs.acted);
    assertEquals(List.of(), chase.acted);

    reaches(() -> r.saveProfile(profile("0.2300")));
    reaches(
        () ->
            r.recordPayment(
                invoice,
                new BillingDtos.RecordPaymentRequest(
                    new BigDecimal("100.50"), "CARD", null, null, null)));
    reaches(() -> r.voidInvoice(invoice, new BillingDtos.VoidRequest("issued twice")));
    reaches(
        () -> r.recordVatCheck(TENANT, new BillingDtos.VatCheckRequest("IE1234567X", "MANUAL")));
    reaches(
        () -> r.extendDueDate(invoice, new BillingDtos.ExtendDueDateRequest("2026-11-30", "ok")));
    assertEquals(List.of("profile 0.2300", "payment 100.50", "void issued twice"), billing.acted);
    assertEquals(List.of("vat check IE1234567X"), subs.acted);
    assertEquals(List.of("due 2026-11-30"), chase.acted);
  }

  @Test
  @DisplayName(
      "A dunning policy with a hole in its reminder days is a 400 naming it, never a 500 from"
          + " sorting a null")
  void aDunningPolicyWithAHoleIsRefused() {
    Chase chase = new Chase();
    PlatformBillingResource r = platform(new Billing(), new Subs(), chase, platformAdmin());
    invalid(
        () ->
            r.setDunningPolicy(
                new BillingDtos.DunningPolicyRequest(true, Arrays.asList(1, null, 5), 7, 30)),
        "reminderDays[1]: must not be null");
    refused(() -> r.setDunningPolicy(null), 400, "BODY_REQUIRED");
    assertEquals(List.of(), chase.acted);

    reaches(
        () ->
            r.setDunningPolicy(
                new BillingDtos.DunningPolicyRequest(true, List.of(5, 1, 3), 7, 30)));
    assertEquals(List.of("policy [1, 3, 5]"), chase.acted);
  }

  @Test
  @DisplayName(
      "An invoice's new due date is read as a date where the test clock is off, as in production;"
          + " one that is no date is BILLING_DATE_INVALID")
  void aDueDateIsADateWhateverTheTestClock() {
    Chase chase = new Chase();
    PlatformBillingResource r = platform(new Billing(), new Subs(), chase, platformAdmin());
    r.testClock = false;
    UUID invoice = Ids.newId();
    refused(
        () -> r.extendDueDate(invoice, new BillingDtos.ExtendDueDateRequest("2026-13-01", null)),
        400,
        "BILLING_DATE_INVALID");
    assertEquals(List.of(), chase.acted);

    reaches(
        () -> r.extendDueDate(invoice, new BillingDtos.ExtendDueDateRequest("2026-11-30", "ok")));
    assertEquals(List.of("due 2026-11-30"), chase.acted);
  }

  // ── a business's own subscription ──────────────────────────────────────────

  private static TenantBillingResource mine(Subs subs, TenantContext ctx) {
    TenantBillingResource r = new TenantBillingResource();
    r.subscriptions = subs;
    r.billing = new Billing();
    r.ctx = ctx;
    return r;
  }

  @Test
  @DisplayName(
      "A business's billing details, plan change and cancellation are checked in the platform's"
          + " problem; a cancellation still needs no body")
  void aBusinesssOwnBillingBodiesAreChecked() {
    Subs subs = new Subs();
    TenantBillingResource r = mine(subs, caller(TENANT, "OWNER", Set.of()));
    fieldRefused(
        () ->
            r.details(
                new BillingDtos.BuyerRequest(null, null, null, null, null, "IRL", null, null)),
        "country");
    refused(() -> r.details(null), 400, "BODY_REQUIRED");
    invalid(
        () -> r.changePlan(new BillingDtos.PlanChangeRequest(null, "NOW")),
        "planId: must not be null");
    refused(() -> r.changePlan(null), 400, "BODY_REQUIRED");
    fieldRefused(() -> r.cancel(new BillingDtos.CancelRequest("x".repeat(501))), "reason");
    assertEquals(List.of(), subs.acted);

    // Held to a store, or not management: refused before the body is looked at.
    refused(
        () -> mine(subs, caller(TENANT, "MANAGER", Set.of(Ids.newId()))).changePlan(null),
        403,
        "BUSINESS_WIDE_ONLY");
    refused(() -> mine(subs, caller(TENANT, "CASHIER", Set.of())).cancel(null), 403, "FORBIDDEN");
    assertEquals(List.of(), subs.acted);

    reaches(
        () ->
            r.details(
                new BillingDtos.BuyerRequest("Shop", null, null, null, null, "DE", null, null)));
    reaches(() -> r.changePlan(new BillingDtos.PlanChangeRequest(Ids.newId(), "NOW")));
    reaches(() -> r.cancel(null));
    reaches(() -> r.cancel(new BillingDtos.CancelRequest("closing")));
    assertEquals(List.of("details DE", "plan NOW", "cancel null", "cancel closing"), subs.acted);
  }

  // ── a breach notice's duty, a statutory filing ─────────────────────────────

  private static final class Incidents extends SecurityIncidentService {
    final List<String> acted = new ArrayList<>();

    @Override
    public com.storeql.tenant.domain.Domain.NoticeDuties report(
        UUID tenantId, UUID noticeId, RecordDutyRequest req, UUID actor) {
      acted.add("report " + req.duty());
      throw new Reached("report");
    }
  }

  private static final class Filings extends StatutoryService {
    final List<String> acted = new ArrayList<>();

    @Override
    public com.storeql.tenant.domain.StatutoryReturns.Obligation file(
        UUID tenantId,
        String code,
        LocalDate periodStart,
        String reference,
        String provider,
        String payloadDigest,
        String note,
        UUID supersedes,
        UUID actorId,
        LocalDate asOf) {
      acted.add("file " + code + " " + periodStart);
      throw new Reached("file");
    }
  }

  @Test
  @DisplayName(
      "A breach notice's duty and a statutory filing are checked in the platform's problem, and"
          + " reach the service only when right")
  void aDutyAndAFilingAreChecked() {
    Incidents incidents = new Incidents();
    SecurityNoticeResource notices = new SecurityNoticeResource();
    notices.service = incidents;
    notices.ctx = caller(TENANT, "OWNER", Set.of());
    UUID notice = Ids.newId();
    invalid(
        () -> notices.report(notice, new RecordDutyRequest(" ", null, null, null)),
        "duty: must not be blank");
    refused(() -> notices.report(notice, null), 400, "BODY_REQUIRED");
    assertEquals(List.of(), incidents.acted);
    reaches(
        () -> notices.report(notice, new RecordDutyRequest("BOARD_REPORTED", null, null, null)));
    assertEquals(List.of("report BOARD_REPORTED"), incidents.acted);

    Filings filings = new Filings();
    StatutoryResource statutory = new StatutoryResource();
    statutory.svc = filings;
    statutory.ctx = caller(TENANT, "OWNER", Set.of());
    invalid(
        () ->
            statutory.file(
                "UK_VAT",
                new StatutoryDtos.FileRequest("2026-07-01", null, null, null, null, null)),
        "provider: must not be blank");
    refused(() -> statutory.file("UK_VAT", null), 400, "BODY_REQUIRED");
    assertEquals(List.of(), filings.acted);
    reaches(
        () ->
            statutory.file(
                "UK_VAT",
                new StatutoryDtos.FileRequest("2026-07-01", null, "MANUAL", null, null, null)));
    assertEquals(List.of("file UK_VAT 2026-07-01"), filings.acted);
  }
}
