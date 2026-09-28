package com.storeql.tenant.service;

import com.storeql.events.OutboxRecord;
import com.storeql.ids.Ids;
import com.storeql.service.OutboxRow;
import com.storeql.tenant.domain.Plans.Plan;
import com.storeql.tenant.domain.Subscriptions.BillingProfile;
import com.storeql.tenant.domain.Subscriptions.Invoice;
import com.storeql.tenant.domain.Subscriptions.Subscription;
import com.storeql.tenant.repo.BillingRepository;
import com.storeql.tenant.repo.PlanRepository;
import com.storeql.tenant.repo.TrialNoticeRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * What a business is told about its trial (21.13): that it is about to end, and that it has ended
 * with the first invoice and the way to pay it.
 *
 * <p>A trial that ends in silence is a charge nobody expected: the first invoice is raised the day
 * the trial ends, and a business that was not warned meets it as a surprise, then meets dunning. So
 * the billing run says it twice — some days before, naming the day and what the plan will then
 * cost; and on the day, naming the invoice and carrying the link that pays it with no sign-in. Each
 * stage once, however often the run runs ({@link TrialNoticeRepository}). A business with no
 * billing address cannot be told, and is not: the run passes it by and the notice stays owed.
 */
@ApplicationScoped
public class TrialNoticeService {

  private static final Logger LOG = System.getLogger(TrialNoticeService.class.getName());
  static final String EVENT = "TrialNoticeIssued";
  static final String TOPIC = OutboxRecord.topicFor("tenant", "trial-notice-issued");
  static final String ENDING = "ENDING";
  static final String ENDED = "ENDED";

  @Inject BillingRepository subscriptions;
  @Inject PlanRepository plans;
  @Inject TrialNoticeRepository notices;
  @Inject PayLinks payLinks;

  /** How many days before a trial ends the business is told it is ending. */
  @Inject
  @ConfigProperty(name = "storeql.billing.trial-notice-days", defaultValue = "3")
  int noticeDays;

  /** Every trial ending within the notice window on a day, told once that it is ending. */
  public List<UUID> endingSoon(LocalDate asOf, BillingProfile seller) {
    List<UUID> told = new ArrayList<>();
    for (Subscription s : subscriptions.trialingEndingBy(asOf.plusDays(noticeDays))) {
      if (s.trialEnd() == null || s.trialEnd().isBefore(asOf)) continue;
      if (announce(s, ENDING, seller, null)) told.add(s.tenantId());
    }
    return told;
  }

  /** The day a trial ends: the first invoice, and the link that pays it. */
  public boolean ended(Subscription s, Invoice invoice, BillingProfile seller) {
    return announce(s, ENDED, seller, invoice);
  }

  private boolean announce(Subscription s, String stage, BillingProfile seller, Invoice invoice) {
    String recipient = s.billingEmail();
    if (recipient == null || recipient.isBlank()) {
      LOG.log(
          Level.INFO, "trial {0} for {1}: no billing address, nobody to tell", stage, s.tenantId());
      return false;
    }
    Plan plan = plans.find(s.planId()).orElse(null);
    String payUrl = invoice == null ? null : payLinks.issue(invoice.id());
    String payload =
        Events.trialNoticeIssued(
            s,
            stage,
            plan == null ? "" : plan.name(),
            recipient,
            seller.legalName(),
            invoice,
            payUrl);
    return notices.claim(
        Ids.newId(),
        s.tenantId(),
        s.id(),
        stage,
        new OutboxRow(EVENT, TOPIC, s.tenantId(), s.id(), payload));
  }
}
