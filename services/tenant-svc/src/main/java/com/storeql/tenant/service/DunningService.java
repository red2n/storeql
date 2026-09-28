package com.storeql.tenant.service;

import com.storeql.events.OutboxRecord;
import com.storeql.ids.Ids;
import com.storeql.service.CapabilityTokens;
import com.storeql.service.OutboxRow;
import com.storeql.tenant.domain.Dunning;
import com.storeql.tenant.domain.Dunning.Overdue;
import com.storeql.tenant.domain.Dunning.Policy;
import com.storeql.tenant.domain.Subscriptions;
import com.storeql.tenant.domain.Subscriptions.Invoice;
import com.storeql.tenant.domain.Subscriptions.Subscription;
import com.storeql.tenant.repo.BillingRepository;
import com.storeql.tenant.repo.DunningRepository;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Chasing an overdue invoice, and taking the platform away when chasing does not work (21.12).
 *
 * <p>Three things about this are decisions rather than mechanics.
 *
 * <p><b>Every notice a business missed is sent, once.</b> If the run has not run for a week, the
 * business is owed each reminder it did not get — skipping to the latest step would suspend a
 * business the platform never finished telling was late. The unique index on (invoice, step) is
 * what makes "once" true, so the run is safe to run twice. A notice is a {@code
 * DunningNoticeIssued} event written with the step in one transaction (SJ-D68: the step used to be
 * recorded and the notice never sent); notification-svc writes and sends it, to the address on the
 * subscription — the owner's from sign-up, or the one the business set since.
 *
 * <p><b>It skips and names, like the billing run.</b> One business whose notice cannot be sent must
 * not stop everybody else being chased; but an invoice nobody chased is money not asked for, so it
 * is named rather than quietly left.
 *
 * <p><b>Paying up lifts only what the platform imposed.</b> A business switched off for {@code
 * NON_PAYMENT} comes back when it pays; one an administrator switched off does not, whatever it
 * pays, because that was a decision somebody took and money does not overrule it. Without the
 * reason on the row the two are indistinguishable, which is why V23 adds it.
 */
@ApplicationScoped
public class DunningService {

  /** How many overdue invoices one run looks at. Enough for a platform, bounded all the same. */
  private static final int RUN_LIMIT = 1000;

  static final String NOTICE_EVENT = "DunningNoticeIssued";
  static final String NOTICE_TOPIC = OutboxRecord.topicFor("tenant", "dunning-notice-issued");

  private static final System.Logger LOG = System.getLogger(DunningService.class.getName());

  @Inject DunningRepository repo;

  /** For the rows: which invoices are open, and moving a subscription's status. */
  @Inject BillingRepository invoices;

  /** For the money: applying a payment settles the invoice and may bring the subscription back. */
  @Inject BillingService billing;

  @Inject TenantService tenants;

  /** The link a notice carries, shared with the trial's first invoice (21.13). */
  @Inject PayLinks payLinks;

  /** What one run did. */
  public record Run(List<Step> taken, List<Skipped> skipped) {
    public Run {
      taken = List.copyOf(taken);
      skipped = List.copyOf(skipped);
    }
  }

  /** One step actually taken, so the caller can see what the platform did in its name. */
  public record Step(UUID tenantId, String invoiceNumber, String step) {}

  /** An invoice the run could not act on, named so somebody can. */
  public record Skipped(UUID tenantId, String invoiceNumber, String reason) {}

  /** The platform's policy, or the defaults standing in for one. */
  public Policy policy() {
    return repo.policy();
  }

  /**
   * Sets the policy.
   *
   * @throws ApiException 400 {@code DUNNING_POLICY_INVALID} when the stages are out of order — a
   *     suspension before the last reminder takes the platform away from a business that has not
   *     finished being told it is late
   */
  public Policy setPolicy(Policy wanted, UUID actorId) {
    List<Integer> days = wanted.reminderDays();
    if (days.isEmpty()) {
      throw ApiException.badRequest(
          "DUNNING_POLICY_INVALID", "A business is told it is late before anything is taken away");
    }
    int last = days.get(days.size() - 1);
    if (wanted.suspendAfterDays() <= last) {
      throw ApiException.badRequest(
          "DUNNING_POLICY_INVALID",
          "The platform would be taken away on day "
              + wanted.suspendAfterDays()
              + ", before the last reminder on day "
              + last);
    }
    if (wanted.uncollectibleAfterDays() <= wanted.suspendAfterDays()) {
      throw ApiException.badRequest(
          "DUNNING_POLICY_INVALID",
          "The debt would be given up on before the service was interrupted");
    }
    repo.savePolicy(wanted, actorId);
    return repo.policy();
  }

  /**
   * Chases everything that is overdue on a day.
   *
   * @return what it did, and what it could not do and why
   */
  public Run run(LocalDate asOf) {
    Policy policy = repo.policy();
    List<Step> taken = new ArrayList<>();
    List<Skipped> skipped = new ArrayList<>();
    if (!policy.enabled()) return new Run(taken, skipped);

    for (Overdue overdue : repo.overdueOn(asOf, RUN_LIMIT)) {
      for (String step : policy.stepsEarnedBy(overdue.daysOverdue())) {
        try {
          if (take(overdue, policy, step, asOf)) {
            taken.add(new Step(overdue.tenantId(), overdue.number(), step));
          }
        } catch (ApiException e) {
          skipped.add(new Skipped(overdue.tenantId(), overdue.number(), e.getMessage()));
          LOG.log(
              System.Logger.Level.WARNING,
              "dunning passed over {0} on {1}: {2}",
              overdue.number(),
              step,
              e.getMessage());
          break;
        }
      }
    }
    return new Run(taken, skipped);
  }

  /**
   * Takes one step, if it has not been taken.
   *
   * <p>The claim comes first. Doing the work and then recording it would let a run that dies
   * halfway suspend a business twice, or send a notice the record does not show; claiming first
   * means the database decides who acts, and a second run finds the step already claimed and does
   * nothing.
   *
   * <p>A reminder and the suspension are notices, and a notice is claimed whole: the step, the pay
   * link it carries and the event that writes it, in one transaction — so there is never a step on
   * the file that nobody was told about. Giving up on the debt tells nobody: the service has
   * already been taken away, and the subscription simply ends.
   *
   * @return whether this call is the one that took it
   */
  private boolean take(Overdue overdue, Policy policy, String step, LocalDate asOf) {
    // The run's own date, not the wall clock. With a test clock the two differ by months, and a
    // file that says "suspended" without saying as of when cannot be read back against the policy.
    String detail =
        step
            + " on "
            + asOf
            + " at "
            + overdue.daysOverdue()
            + " days overdue, invoice "
            + overdue.number();
    if (Dunning.UNCOLLECTIBLE.equals(step)) {
      if (!repo.claimStep(
          Ids.newId(), overdue.tenantId(), overdue.invoiceId(), step, detail, null)) {
        return false;
      }
      giveUp(overdue);
      return true;
    }
    if (!notice(overdue, policy, step, detail)) {
      return false;
    }
    if (Dunning.SUSPENDED.equals(step)) {
      suspend(overdue);
    }
    return true;
  }

  /**
   * Claims a step and queues the notice that tells the business about it, carrying a link that pays
   * the invoice without a sign-in.
   *
   * <p>The link is the point. By the time the later notices go the business may already be
   * suspended, and a suspended business cannot sign in — telling it to pay while denying it the
   * means is not a dunning process, it is a dead end. A fresh token per notice, and only its hash
   * is kept; the token itself travels in the event to notification-svc and never through a log.
   *
   * @throws ApiException 409 {@code DUNNING_NO_BILLING_EMAIL} when the subscription names no
   *     address — the run names the business rather than chasing it in silence, and nothing is
   *     claimed, so the notice is owed still
   */
  private boolean notice(Overdue overdue, Policy policy, String step, String detail) {
    Invoice invoice =
        invoices
            .invoice(overdue.invoiceId())
            .orElseThrow(() -> ApiException.notFound("INVOICE_NOT_FOUND", "No such invoice"));
    String recipient =
        invoices
            .ofTenant(overdue.tenantId())
            .map(Subscription::billingEmail)
            .filter(e -> e != null && !e.isBlank())
            .orElseThrow(
                () ->
                    ApiException.conflict(
                        "DUNNING_NO_BILLING_EMAIL",
                        "This business has no billing email, so there is nobody to tell that it"
                            + " is late"));
    LocalDate suspendOn =
        Dunning.SUSPENDED.equals(step)
            ? null
            : overdue.dueDate().plusDays(policy.suspendAfterDays());
    String token = CapabilityTokens.mint();
    String payload =
        Events.dunningNoticeIssued(
            overdue.tenantId(),
            invoice,
            step,
            overdue.daysOverdue(),
            recipient,
            payLinks.url(token),
            billing.profile().legalName(),
            suspendOn);
    return repo.claimNotice(
        Ids.newId(),
        overdue.tenantId(),
        overdue.invoiceId(),
        step,
        detail,
        CapabilityTokens.hash(token),
        new OutboxRow(
            NOTICE_EVENT, NOTICE_TOPIC, overdue.tenantId(), overdue.invoiceId(), payload));
  }

  /**
   * Interrupts the service: the subscription is suspended and the business switched off for
   * non-payment, through the existing {@code TenantStatusChanged} path so iam-svc locks the staff
   * out and the gateway closes the storefront. Nothing new is invented for the effect.
   */
  private void suspend(Overdue overdue) {
    invoices
        .ofTenant(overdue.tenantId())
        .ifPresent(s -> invoices.moveStatus(s.id(), s.status(), Subscriptions.SUSPENDED));
    // Named as the platform's own doing, and only if the business is not already off — an
    // administrator's reason is never overwritten with this one, or the next payment would lift a
    // suspension nobody asked it to lift.
    if (repo.deactivate(overdue.tenantId(), Dunning.NON_PAYMENT, null)) {
      tenants.announceStatus(overdue.tenantId(), "INACTIVE");
    }
  }

  /** Gives up on the debt: the invoice is uncollectible and the subscription is over. */
  private void giveUp(Overdue overdue) {
    billing.writeOff(overdue.invoiceId());
    invoices
        .ofTenant(overdue.tenantId())
        .ifPresent(s -> invoices.moveStatus(s.id(), s.status(), Subscriptions.CANCELLED));
  }

  /**
   * Pays an invoice from the link in a notice, with no sign-in.
   *
   * <p>Deliberately unauthenticated, for the same reason the marketing opt-out is: the business
   * this reaches has been suspended and cannot sign in. The token is the whole capability and it
   * can do exactly one thing — pay the invoice it names. A token for an invoice since paid or
   * withdrawn opens nothing, so an old link cannot pay twice.
   *
   * @throws ApiException 404 {@code PAY_LINK_INVALID} for a token that is not one this platform
   *     issued, or is for an invoice no longer open — the same answer either way, because
   *     distinguishing them tells whoever is guessing which half they got right
   */
  public Invoice payByLink(String token, UUID recordedBy) {
    DunningRepository.Payable payable =
        repo.payableByToken(CapabilityTokens.hash(token))
            .orElseThrow(
                () ->
                    ApiException.notFound(
                        "PAY_LINK_INVALID", "This link does not open an invoice that can be paid"));
    Invoice paid = billing.payInFull(payable.invoiceId(), payable.outstanding(), recordedBy);
    repo.claimStep(
        Ids.newId(),
        payable.tenantId(),
        payable.invoiceId(),
        Dunning.RESOLVED,
        "paid in full from the link in a notice",
        recordedBy);
    liftIfOwedNothing(payable.tenantId());
    return paid;
  }

  /**
   * Switches a business back on once it owes nothing — but only one the platform switched off.
   *
   * <p>The check that matters in this whole row. An administrator's suspension is a decision
   * somebody took, and a payment is not an argument against it: the reason on the row is what tells
   * the two apart, and {@code reactivateIf} will not move a business whose reason is anything but
   * {@code NON_PAYMENT}.
   */
  public void liftIfOwedNothing(UUID tenantId) {
    boolean owesNothing = invoices.invoicesOf(tenantId, 200).stream().noneMatch(Invoice::open);
    if (!owesNothing) return;
    if (repo.reactivateIf(tenantId, Dunning.NON_PAYMENT)) {
      tenants.announceStatus(tenantId, "ACTIVE");
      LOG.log(System.Logger.Level.INFO, "{0} paid up and is trading again", tenantId);
    }
  }

  /**
   * Moves an invoice's due date out — a promise to pay, which pauses the chase without forgiving
   * the debt. Recorded, so an extension is on the file rather than a due date that quietly moved.
   *
   * @throws ApiException 400 {@code DUE_DATE_NOT_LATER} when the new date is not after the old one
   */
  public Invoice extendDueDate(UUID invoiceId, LocalDate to, String reason, UUID actorId) {
    Invoice moved = billing.extendDueDate(invoiceId, to);
    repo.claimStep(
        Ids.newId(),
        moved.tenantId(),
        invoiceId,
        Dunning.DUE_DATE_EXTENDED,
        "due date moved to " + to + (reason == null || reason.isBlank() ? "" : ": " + reason),
        actorId);
    return moved;
  }

  /**
   * A fresh pay link for one invoice, which is what a notice carries.
   *
   * <p>Minted on demand rather than stored in the clear: only the hash is kept, so asking again
   * replaces the link rather than retrieving it. An older link for the same invoice stops working,
   * which is the right trade — a link is a capability and the newest notice is the one to act on.
   *
   * @throws ApiException 409 {@code INVOICE_NOT_OPEN} for an invoice that cannot be paid, because a
   *     link to it would be a dead end
   */
  public String issuePayToken(UUID invoiceId) {
    return payLinks.mint(invoiceId);
  }

  /** What has been done about one overdue invoice, oldest first. */
  public List<Dunning.Event> eventsOf(UUID invoiceId) {
    return repo.eventsOf(invoiceId);
  }

  /** What is overdue on a day, with the stage each invoice has reached and what it earns next. */
  public List<Overdue> overdue(LocalDate asOf, int limit) {
    Policy policy = repo.policy();
    return repo.overdueOn(asOf, limit).stream()
        .map(
            o -> {
              List<String> earned = policy.stepsEarnedBy(o.daysOverdue());
              String next = nextAfter(policy, o.daysOverdue());
              return new Overdue(
                  o.invoiceId(),
                  o.tenantId(),
                  o.number(),
                  o.dueDate(),
                  o.daysOverdue(),
                  o.stage() == null && !earned.isEmpty() ? null : o.stage(),
                  next);
            })
        .toList();
  }

  /** The first step this invoice has not yet earned, so an operator can see what is coming. */
  private static String nextAfter(Policy policy, int daysOverdue) {
    for (int day : policy.reminderDays()) {
      if (daysOverdue < day) return Dunning.reminder(day);
    }
    if (daysOverdue < policy.suspendAfterDays()) return Dunning.SUSPENDED;
    if (daysOverdue < policy.uncollectibleAfterDays()) return Dunning.UNCOLLECTIBLE;
    return null;
  }
}
