package com.storeql.tenant.repo;

import com.storeql.service.BaseJdbcRepository;
import com.storeql.tenant.domain.Meters.UsagePeriod;
import com.storeql.tenant.domain.Subscriptions.BillingProfile;
import com.storeql.tenant.domain.Subscriptions.Buyer;
import com.storeql.tenant.domain.Subscriptions.Invoice;
import com.storeql.tenant.domain.Subscriptions.InvoiceLine;
import com.storeql.tenant.domain.Subscriptions.Payment;
import com.storeql.tenant.domain.Subscriptions.Subscription;
import com.storeql.tenant.domain.Subscriptions.SubscriptionEvent;
import com.storeql.tenant.domain.Subscriptions.VatRate;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What a business owes the platform, and the invoice that says so (21.9).
 *
 * <p>Two rules run through every statement here.
 *
 * <p><b>An invoice is never edited.</b> It is issued once, numbered, and after that only its
 * payments move. A mistake is withdrawn with a reason and explained by a credit note; the number
 * stays where it is, because a gap in the sequence is what an auditor asks about.
 *
 * <p><b>A period is invoiced once, by construction.</b> {@code uq_invoices_period} means the
 * billing run can be run twice, or two replicas can run it at the same instant, and the second one
 * loses on the unique index rather than on a check somebody remembered to write.
 */
@ApplicationScoped
public class BillingRepository extends BaseJdbcRepository {

  // ── the platform's own identity ─────────────────────────────────────────────

  private static final String SELECT_PROFILE =
      "SELECT legal_name, address_line1, address_line2, city, postcode, country, vat_number,"
          + " company_number, invoice_prefix, payment_terms_days, tax_rate, bank_details,"
          + " updated_by, updated_at FROM platform_billing_profile WHERE id = 1";

  private static final String UPSERT_PROFILE =
      "INSERT INTO platform_billing_profile (id, legal_name, address_line1, address_line2, city,"
          + " postcode, country, vat_number, company_number, invoice_prefix, payment_terms_days,"
          + " tax_rate, bank_details, updated_by, updated_at)"
          + " VALUES (1,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
          + " ON CONFLICT (id) DO UPDATE SET legal_name = EXCLUDED.legal_name,"
          + " address_line1 = EXCLUDED.address_line1, address_line2 = EXCLUDED.address_line2,"
          + " city = EXCLUDED.city, postcode = EXCLUDED.postcode, country = EXCLUDED.country,"
          + " vat_number = EXCLUDED.vat_number, company_number = EXCLUDED.company_number,"
          + " invoice_prefix = EXCLUDED.invoice_prefix,"
          + " payment_terms_days = EXCLUDED.payment_terms_days, tax_rate = EXCLUDED.tax_rate,"
          + " bank_details = EXCLUDED.bank_details, updated_by = EXCLUDED.updated_by,"
          + " updated_at = EXCLUDED.updated_at";

  /** The platform's own identity, as an invoice prints it; empty until somebody has set it. */
  public Optional<BillingProfile> profile() {
    return query(SELECT_PROFILE, ps -> {}, BillingRepository::readProfile, "read profile").stream()
        .findFirst();
  }

  public void saveProfile(BillingProfile p) {
    exec(
        UPSERT_PROFILE,
        ps -> {
          ps.setString(1, p.legalName());
          ps.setString(2, p.addressLine1());
          ps.setString(3, p.addressLine2());
          ps.setString(4, p.city());
          ps.setString(5, p.postcode());
          ps.setString(6, p.country());
          ps.setString(7, p.vatNumber());
          ps.setString(8, p.companyNumber());
          ps.setString(9, p.invoicePrefix());
          ps.setInt(10, p.paymentTermsDays());
          ps.setBigDecimal(11, p.taxRate());
          ps.setString(12, p.bankDetails());
          ps.setObject(13, p.updatedBy());
          ps.setObject(14, p.updatedAt().atOffset(ZoneOffset.UTC));
        },
        "save profile");
  }

  // ── the rates the platform charges where it has to ──────────────────────────

  private static final String SELECT_RATE =
      "SELECT rate FROM platform_vat_rates WHERE country = ? AND effective_from <= ?"
          + " ORDER BY effective_from DESC LIMIT 1";

  private static final String UPSERT_RATE =
      "INSERT INTO platform_vat_rates (country, effective_from, rate, note, updated_by,"
          + " updated_at) VALUES (?,?,?,?,?,?) ON CONFLICT (country, effective_from)"
          + " DO UPDATE SET rate = EXCLUDED.rate, note = EXCLUDED.note,"
          + " updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at";

  /**
   * The rate in force in a country on a date.
   *
   * @return empty when the platform has set none — which refuses the invoice rather than guessing
   *     at a rate, because a guessed rate is VAT charged wrongly in somebody else's country
   */
  public Optional<BigDecimal> rateOn(String country, LocalDate on) {
    return query(
            SELECT_RATE,
            ps -> {
              ps.setString(1, country);
              ps.setObject(2, on);
            },
            rs -> rs.getBigDecimal(1),
            "read vat rate")
        .stream()
        .findFirst();
  }

  private static final String ALL_RATES =
      "SELECT country, effective_from, rate, note FROM platform_vat_rates"
          + " ORDER BY country, effective_from DESC";

  /** Every rate the platform has set, so a console shows what it will charge and where. */
  public List<VatRate> rates() {
    return query(
        ALL_RATES,
        ps -> {},
        rs ->
            new VatRate(
                rs.getString("country"),
                rs.getObject("effective_from", LocalDate.class),
                rs.getBigDecimal("rate"),
                rs.getString("note")),
        "vat rates");
  }

  /** Who set a rate and when is kept: a rate is a tax position somebody took. */
  public void saveRate(String country, LocalDate from, BigDecimal rate, String note, UUID actorId) {
    exec(
        UPSERT_RATE,
        ps -> {
          ps.setString(1, country);
          ps.setObject(2, from);
          ps.setBigDecimal(3, rate);
          ps.setString(4, note);
          ps.setObject(5, actorId);
          ps.setObject(6, Instant.now().atOffset(ZoneOffset.UTC));
        },
        "save vat rate");
  }

  // ── subscriptions ───────────────────────────────────────────────────────────

  private static final String SUBSCRIPTION_COLUMNS =
      "SELECT id, tenant_id, plan_id, status, price_amount, currency, billing_interval,"
          + " period_start, period_end, trial_end, pending_plan_id, cancel_at_period_end,"
          + " buyer_name, buyer_line1, buyer_line2, buyer_city, buyer_postcode, buyer_country,"
          + " buyer_vat_number, vat_checked_at, vat_checked_by, vat_check_source, billing_email,"
          + " started_at, cancelled_at, created_at, updated_at FROM subscriptions";

  private static final String BY_TENANT = SUBSCRIPTION_COLUMNS + " WHERE tenant_id = ?";

  private static final String BY_ID = SUBSCRIPTION_COLUMNS + " WHERE id = ?";

  /**
   * Every subscription whose period has run out on a date and which is still being billed. Ordered
   * so a run that is interrupted resumes in the same order it left off.
   */
  private static final String DUE =
      SUBSCRIPTION_COLUMNS
          + " WHERE period_end <= ? AND status IN ('TRIALING', 'ACTIVE', 'PAST_DUE')"
          + " ORDER BY period_end, id";

  private static final String INSERT_SUBSCRIPTION =
      "INSERT INTO subscriptions (id, tenant_id, plan_id, status, price_amount, currency,"
          + " billing_interval, period_start, period_end, trial_end, pending_plan_id,"
          + " cancel_at_period_end, buyer_name, buyer_line1, buyer_line2, buyer_city,"
          + " buyer_postcode, buyer_country, buyer_vat_number, vat_checked_at, vat_checked_by,"
          + " vat_check_source, billing_email, started_at, cancelled_at, created_at, updated_at)"
          + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

  private static final String UPDATE_SUBSCRIPTION =
      "UPDATE subscriptions SET plan_id = ?, status = ?, price_amount = ?, currency = ?,"
          + " billing_interval = ?, period_start = ?, period_end = ?, trial_end = ?,"
          + " pending_plan_id = ?, cancel_at_period_end = ?, buyer_name = ?, buyer_line1 = ?,"
          + " buyer_line2 = ?, buyer_city = ?, buyer_postcode = ?, buyer_country = ?,"
          + " buyer_vat_number = ?, vat_checked_at = ?, vat_checked_by = ?, vat_check_source = ?,"
          + " billing_email = ?, cancelled_at = ?, updated_at = ? WHERE id = ?";

  /**
   * Moves a subscription from one status to another, naming the status it moves <em>from</em>.
   *
   * @return false when it was not in that status, which is how two runs racing on the same
   *     subscription end with one of them doing the work — the 21.8 lesson, where moving from
   *     whatever the row happened to be made "retire a retired plan" succeed and mean nothing
   */
  /**
   * Moves a subscription's status, keeping {@code cancelled_at} true to it in the same statement:
   * set on the move to CANCELLED, cleared on any other move.
   *
   * <p>{@code ck_subscriptions_cancelled} holds the two together, and this statement used to set
   * the status alone. So every move to CANCELLED broke the constraint — a subscription cancelled at
   * its period end was passed over by every billing run and never ended, and a debt given up on
   * never ended the subscription it was owed on (SJ-D69). Found by the first test that let a period
   * end.
   */
  private static final String MOVE_STATUS =
      "UPDATE subscriptions SET status = ?,"
          + " cancelled_at = CASE WHEN ? = 'CANCELLED' THEN COALESCE(cancelled_at, ?) END,"
          + " updated_at = ? WHERE id = ? AND status = ?";

  public Optional<Subscription> ofTenant(UUID tenantId) {
    return query(
            BY_TENANT,
            ps -> ps.setObject(1, tenantId),
            BillingRepository::readSubscription,
            "subscription of tenant")
        .stream()
        .findFirst();
  }

  public Optional<Subscription> byId(UUID id) {
    return query(
            BY_ID,
            ps -> ps.setObject(1, id),
            BillingRepository::readSubscription,
            "subscription by id")
        .stream()
        .findFirst();
  }

  private static final String TRIALING_ENDING_BY =
      SUBSCRIPTION_COLUMNS
          + " WHERE status = 'TRIALING' AND trial_end IS NOT NULL AND trial_end <= ?"
          + " ORDER BY trial_end, id";

  /** Every trial that ends on or before a day, soonest first (21.13). */
  public List<Subscription> trialingEndingBy(LocalDate day) {
    return query(
        TRIALING_ENDING_BY,
        ps -> ps.setObject(1, day),
        BillingRepository::readSubscription,
        "trials ending by");
  }

  public List<Subscription> due(LocalDate asOf) {
    return query(
        DUE, ps -> ps.setObject(1, asOf), BillingRepository::readSubscription, "subscriptions due");
  }

  public Subscription create(Subscription s) {
    exec(INSERT_SUBSCRIPTION, ps -> bindInsert(ps, s), "create subscription");
    return s;
  }

  public boolean save(Subscription s) {
    return inTx(
        c -> {
          try (PreparedStatement ps = c.prepareStatement(UPDATE_SUBSCRIPTION)) {
            bindUpdate(ps, s);
            return ps.executeUpdate() == 1;
          }
        },
        "save subscription");
  }

  public boolean moveStatus(UUID id, String from, String to) {
    return inTx(
        c -> {
          try (PreparedStatement ps = c.prepareStatement(MOVE_STATUS)) {
            OffsetDateTime now = Instant.now().atOffset(ZoneOffset.UTC);
            ps.setString(1, to);
            ps.setString(2, to);
            ps.setObject(3, now);
            ps.setObject(4, now);
            ps.setObject(5, id);
            ps.setString(6, from);
            return ps.executeUpdate() == 1;
          }
        },
        "move subscription status");
  }

  // ── what happened to it ─────────────────────────────────────────────────────

  private static final String INSERT_EVENT =
      "INSERT INTO subscription_events (id, tenant_id, subscription_id, kind, detail, actor_id,"
          + " created_at) VALUES (?,?,?,?,?,?,?)";

  private static final String EVENTS =
      "SELECT id, kind, detail, actor_id, created_at FROM subscription_events"
          + " WHERE tenant_id = ? AND subscription_id = ? ORDER BY created_at DESC, id DESC";

  public void record(
      UUID id, UUID tenantId, UUID subscriptionId, String kind, String detail, UUID actorId) {
    exec(
        INSERT_EVENT,
        ps -> {
          ps.setObject(1, id);
          ps.setObject(2, tenantId);
          ps.setObject(3, subscriptionId);
          ps.setString(4, kind);
          ps.setString(5, detail);
          ps.setObject(6, actorId);
          ps.setObject(7, Instant.now().atOffset(ZoneOffset.UTC));
        },
        "record subscription event");
  }

  public List<SubscriptionEvent> events(UUID tenantId, UUID subscriptionId) {
    return query(
        EVENTS,
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, subscriptionId);
        },
        rs ->
            new SubscriptionEvent(
                rs.getObject("id", UUID.class),
                rs.getString("kind"),
                rs.getString("detail"),
                rs.getObject("actor_id", UUID.class),
                instant(rs, "created_at")),
        "subscription events");
  }

  // ── invoices ────────────────────────────────────────────────────────────────

  /**
   * The next number of the year, taken under the counter's own row lock.
   *
   * <p>Gapless, and it has to be: the sequence is read by people whose job is to ask what happened
   * to number 41. A Postgres sequence would not do — a sequence hands out a number and keeps it
   * even when the transaction that asked rolls back, which is a hole. Here the number is allocated
   * in the same transaction that writes the invoice, so a failure gives the number back.
   *
   * <p>The lock serialises invoice creation platform-wide for the length of one insert. That is the
   * price of the guarantee, and at the rate a platform bills its own subscribers it is free.
   */
  private static final String LOCK_YEAR =
      "SELECT next_number FROM billing_invoice_numbers WHERE year = ? FOR UPDATE";

  private static final String START_YEAR =
      "INSERT INTO billing_invoice_numbers (year, next_number) VALUES (?, 1)"
          + " ON CONFLICT (year) DO NOTHING";

  private static final String BUMP_YEAR =
      "UPDATE billing_invoice_numbers SET next_number = next_number + 1 WHERE year = ?";

  private static final String INSERT_INVOICE =
      "INSERT INTO billing_invoices (id, tenant_id, subscription_id, number, kind, status,"
          + " issue_date, due_date, period_start, period_end, currency, net_amount, tax_treatment,"
          + " tax_rate, tax_amount, total_amount, amount_paid, seller_snapshot, buyer_snapshot,"
          + " buyer_vat_number, pay_token_hash, voided_reason, created_at, updated_at)"
          + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

  private static final String INSERT_LINE =
      "INSERT INTO billing_invoice_lines (id, tenant_id, invoice_id, line_no, kind, description,"
          + " quantity, unit_amount, amount) VALUES (?,?,?,?,?,?,?,?,?)";

  private static final String INVOICE_COLUMNS =
      "SELECT id, tenant_id, subscription_id, number, kind, status, issue_date, due_date,"
          + " period_start,"
          + " period_end, currency, net_amount, tax_treatment, tax_rate, tax_amount, total_amount,"
          + " amount_paid, seller_snapshot, buyer_snapshot, buyer_vat_number, voided_reason,"
          + " created_at, updated_at FROM billing_invoices";

  private static final String INVOICE_BY_ID = INVOICE_COLUMNS + " WHERE id = ?";

  private static final String INVOICES_OF_TENANT =
      INVOICE_COLUMNS + " WHERE tenant_id = ? ORDER BY issue_date DESC, id DESC LIMIT ?";

  private static final String LINES_OF =
      "SELECT id, line_no, kind, description, quantity, unit_amount, amount"
          + " FROM billing_invoice_lines WHERE invoice_id = ? ORDER BY line_no";

  /**
   * Issues an invoice: its number, the invoice and its lines, in one transaction.
   *
   * @param prefix what the number begins with, from the platform's profile
   * @return the invoice as it was written, carrying the number it was given
   */
  public Invoice issue(Invoice invoice, List<InvoiceLine> lines, String prefix) {
    return issue(invoice, lines, prefix, List.of());
  }

  /**
   * Numbers and writes an invoice with its lines, and what each meter billed with it (21.10), in
   * one transaction: an invoice and its usage stand or fall together, so a second replica renewing
   * the same period rolls both back rather than billing the same usage twice.
   */
  public Invoice issue(
      Invoice invoice, List<InvoiceLine> lines, String prefix, List<UsagePeriod> usage) {
    return inTx(
        c -> {
          int year = invoice.issueDate().getYear();
          long next = nextNumber(c, year);
          String number = String.format("%s-%d-%06d", prefix, year, next);
          Invoice numbered = withNumber(invoice, number);
          try (PreparedStatement ps = c.prepareStatement(INSERT_INVOICE)) {
            bindInvoice(ps, numbered);
            ps.executeUpdate();
          }
          try (PreparedStatement ps = c.prepareStatement(INSERT_LINE)) {
            for (InvoiceLine line : lines) {
              ps.setObject(1, line.id());
              ps.setObject(2, numbered.tenantId());
              ps.setObject(3, numbered.id());
              ps.setInt(4, line.lineNo());
              ps.setString(5, line.kind());
              ps.setString(6, line.description());
              ps.setBigDecimal(7, line.quantity());
              ps.setBigDecimal(8, line.unitAmount());
              ps.setBigDecimal(9, line.amount());
              ps.addBatch();
            }
            ps.executeBatch();
          }
          UsageRepository.insertPeriods(c, usage, numbered.id());
          return numbered;
        },
        "issue invoice");
  }

  private static long nextNumber(Connection c, int year) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(START_YEAR)) {
      ps.setInt(1, year);
      ps.executeUpdate();
    }
    long next;
    try (PreparedStatement ps = c.prepareStatement(LOCK_YEAR)) {
      ps.setInt(1, year);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) throw new SQLException("the invoice counter for " + year + " is missing");
        next = rs.getLong(1);
      }
    }
    try (PreparedStatement ps = c.prepareStatement(BUMP_YEAR)) {
      ps.setInt(1, year);
      ps.executeUpdate();
    }
    return next;
  }

  public Optional<Invoice> invoice(UUID id) {
    return query(
            INVOICE_BY_ID, ps -> ps.setObject(1, id), BillingRepository::readInvoice, "invoice")
        .stream()
        .findFirst();
  }

  public List<Invoice> invoicesOf(UUID tenantId, int limit) {
    return query(
        INVOICES_OF_TENANT,
        ps -> {
          ps.setObject(1, tenantId);
          ps.setInt(2, limit);
        },
        BillingRepository::readInvoice,
        "invoices of tenant");
  }

  public List<InvoiceLine> linesOf(UUID invoiceId) {
    return query(
        LINES_OF,
        ps -> ps.setObject(1, invoiceId),
        rs ->
            new InvoiceLine(
                rs.getObject("id", UUID.class),
                rs.getInt("line_no"),
                rs.getString("kind"),
                rs.getString("description"),
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("unit_amount"),
                rs.getBigDecimal("amount")),
        "invoice lines");
  }

  // ── money against an invoice ────────────────────────────────────────────────

  private static final String INSERT_PAYMENT =
      "INSERT INTO billing_payments (id, tenant_id, invoice_id, amount, currency, method, provider,"
          + " provider_ref, idempotency_key, received_on, recorded_by, created_at)"
          + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";

  /**
   * Adds to what has been paid and settles the invoice when that reaches the total, under the
   * invoice's own row. An invoice already VOID or UNCOLLECTIBLE takes no payment: the {@code status
   * = 'OPEN'} predicate is what stops a payment arriving against an invoice that was withdrawn
   * while it was in flight.
   */
  private static final String APPLY_PAYMENT =
      "UPDATE billing_invoices SET amount_paid = amount_paid + ?,"
          + " status = CASE WHEN amount_paid + ? >= total_amount THEN 'PAID' ELSE status END,"
          + " updated_at = ? WHERE id = ? AND status = 'OPEN'";

  private static final String PAYMENTS_OF =
      "SELECT id, invoice_id, amount, currency, method, provider, provider_ref, received_on,"
          + " recorded_by, created_at FROM billing_payments WHERE invoice_id = ?"
          + " ORDER BY received_on, created_at";

  private static final String VOID_INVOICE =
      "UPDATE billing_invoices SET status = 'VOID', voided_reason = ?, updated_at = ?"
          + " WHERE id = ? AND status = 'OPEN' AND amount_paid = 0";

  /**
   * Records money received and applies it.
   *
   * @return false when the invoice was not open, so nothing was applied and nothing was recorded
   */
  public boolean pay(Payment payment, UUID tenantId, String idempotencyKey) {
    return inTx(
        c -> {
          try (PreparedStatement ps = c.prepareStatement(APPLY_PAYMENT)) {
            ps.setBigDecimal(1, payment.amount());
            ps.setBigDecimal(2, payment.amount());
            ps.setObject(3, Instant.now().atOffset(ZoneOffset.UTC));
            ps.setObject(4, payment.invoiceId());
            if (ps.executeUpdate() != 1) return false;
          }
          try (PreparedStatement ps = c.prepareStatement(INSERT_PAYMENT)) {
            ps.setObject(1, payment.id());
            ps.setObject(2, tenantId);
            ps.setObject(3, payment.invoiceId());
            ps.setBigDecimal(4, payment.amount());
            ps.setString(5, payment.currency());
            ps.setString(6, payment.method());
            ps.setString(7, payment.provider());
            ps.setString(8, payment.providerRef());
            ps.setString(9, idempotencyKey);
            ps.setObject(10, payment.receivedOn());
            ps.setObject(11, payment.recordedBy());
            ps.setObject(12, payment.createdAt().atOffset(ZoneOffset.UTC));
            ps.executeUpdate();
          }
          return true;
        },
        "record billing payment");
  }

  public List<Payment> paymentsOf(UUID invoiceId) {
    return query(
        PAYMENTS_OF,
        ps -> ps.setObject(1, invoiceId),
        rs ->
            new Payment(
                rs.getObject("id", UUID.class),
                rs.getObject("invoice_id", UUID.class),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getString("method"),
                rs.getString("provider"),
                rs.getString("provider_ref"),
                rs.getObject("received_on", LocalDate.class),
                rs.getObject("recorded_by", UUID.class),
                instant(rs, "created_at")),
        "invoice payments");
  }

  /**
   * The invoices still owed, most overdue first: the platform's receivables.
   *
   * <p>Ordered by due date rather than by issue date, because what a receivables screen is for is
   * the oldest thing unpaid, which is not always the oldest thing issued.
   */
  private static final String RECEIVABLES =
      INVOICE_COLUMNS + " WHERE status = 'OPEN' ORDER BY due_date, number LIMIT ?";

  /** One invoice, but only if it belongs to this business. */
  private static final String OWN_INVOICE = INVOICE_COLUMNS + " WHERE tenant_id = ? AND id = ?";

  public List<Invoice> receivables(int limit) {
    return query(
        RECEIVABLES, ps -> ps.setInt(1, limit), BillingRepository::readInvoice, "receivables");
  }

  /**
   * @return empty when the invoice is another business's, so a guessed id is indistinguishable from
   *     one that does not exist — a 404 tells a guesser nothing, where a 403 confirms the id is
   *     real
   */
  public Optional<Invoice> ownInvoice(UUID tenantId, UUID invoiceId) {
    return query(
            OWN_INVOICE,
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, invoiceId);
            },
            BillingRepository::readInvoice,
            "own invoice")
        .stream()
        .findFirst();
  }

  /**
   * Gives up on a debt (21.12): the invoice is owed and not expected.
   *
   * <p>Not the same as withdrawing it. A void says the invoice should never have been raised; this
   * says it was right and will not be paid, which is what a write-off is and what the ledger needs
   * to hear.
   *
   * @return false when it was not open, so a run that runs twice gives up once
   */
  private static final String WRITE_OFF =
      "UPDATE billing_invoices SET status = 'UNCOLLECTIBLE', updated_at = ?"
          + " WHERE id = ? AND status = 'OPEN'";

  /**
   * Moves a due date <em>out</em> (21.12): a promise to pay pauses the chase without forgiving the
   * debt.
   *
   * <p>The predicate does the refusing. Only an open invoice, and only to a later date — a due date
   * that could move inwards would let somebody shorten the time a business has to pay after the
   * fact, and one that could move on a settled invoice would be editing history.
   */
  private static final String EXTEND_DUE_DATE =
      "UPDATE billing_invoices SET due_date = ?, updated_at = ?"
          + " WHERE id = ? AND status = 'OPEN' AND due_date < ?";

  public boolean writeOff(UUID invoiceId) {
    return inTx(
        c -> {
          try (PreparedStatement ps = c.prepareStatement(WRITE_OFF)) {
            ps.setObject(1, Instant.now().atOffset(ZoneOffset.UTC));
            ps.setObject(2, invoiceId);
            return ps.executeUpdate() == 1;
          }
        },
        "write off invoice");
  }

  public boolean extendDueDate(UUID invoiceId, LocalDate to) {
    return inTx(
        c -> {
          try (PreparedStatement ps = c.prepareStatement(EXTEND_DUE_DATE)) {
            ps.setObject(1, to);
            ps.setObject(2, Instant.now().atOffset(ZoneOffset.UTC));
            ps.setObject(3, invoiceId);
            ps.setObject(4, to);
            return ps.executeUpdate() == 1;
          }
        },
        "extend due date");
  }

  /** Withdraws an unpaid invoice. One that has taken money is settled or credited, never voided. */
  public boolean voidInvoice(UUID id, String reason) {
    return inTx(
        c -> {
          try (PreparedStatement ps = c.prepareStatement(VOID_INVOICE)) {
            ps.setString(1, reason);
            ps.setObject(2, Instant.now().atOffset(ZoneOffset.UTC));
            ps.setObject(3, id);
            return ps.executeUpdate() == 1;
          }
        },
        "void invoice");
  }

  // ── readers ─────────────────────────────────────────────────────────────────

  private static Subscription readSubscription(ResultSet rs) throws SQLException {
    return new Subscription(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("plan_id", UUID.class),
        rs.getString("status"),
        rs.getBigDecimal("price_amount"),
        rs.getString("currency"),
        rs.getString("billing_interval"),
        rs.getObject("period_start", LocalDate.class),
        rs.getObject("period_end", LocalDate.class),
        rs.getObject("trial_end", LocalDate.class),
        rs.getObject("pending_plan_id", UUID.class),
        rs.getBoolean("cancel_at_period_end"),
        new Buyer(
            rs.getString("buyer_name"),
            rs.getString("buyer_line1"),
            rs.getString("buyer_line2"),
            rs.getString("buyer_city"),
            rs.getString("buyer_postcode"),
            rs.getString("buyer_country"),
            rs.getString("buyer_vat_number"),
            instant(rs, "vat_checked_at"),
            rs.getObject("vat_checked_by", UUID.class),
            rs.getString("vat_check_source")),
        rs.getString("billing_email"),
        instant(rs, "started_at"),
        instant(rs, "cancelled_at"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  private static Invoice readInvoice(ResultSet rs) throws SQLException {
    return new Invoice(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("subscription_id", UUID.class),
        rs.getString("number"),
        rs.getString("kind"),
        rs.getString("status"),
        rs.getObject("issue_date", LocalDate.class),
        rs.getObject("due_date", LocalDate.class),
        rs.getObject("period_start", LocalDate.class),
        rs.getObject("period_end", LocalDate.class),
        rs.getString("currency"),
        rs.getBigDecimal("net_amount"),
        rs.getString("tax_treatment"),
        rs.getBigDecimal("tax_rate"),
        rs.getBigDecimal("tax_amount"),
        rs.getBigDecimal("total_amount"),
        rs.getBigDecimal("amount_paid"),
        rs.getString("seller_snapshot"),
        rs.getString("buyer_snapshot"),
        rs.getString("buyer_vat_number"),
        rs.getString("voided_reason"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  private static BillingProfile readProfile(ResultSet rs) throws SQLException {
    return new BillingProfile(
        rs.getString("legal_name"),
        rs.getString("address_line1"),
        rs.getString("address_line2"),
        rs.getString("city"),
        rs.getString("postcode"),
        rs.getString("country"),
        rs.getString("vat_number"),
        rs.getString("company_number"),
        rs.getString("invoice_prefix"),
        rs.getInt("payment_terms_days"),
        rs.getBigDecimal("tax_rate"),
        rs.getString("bank_details"),
        rs.getObject("updated_by", UUID.class),
        instantOf(rs.getObject("updated_at", OffsetDateTime.class)));
  }

  // ── binders ─────────────────────────────────────────────────────────────────

  private static void bindInsert(PreparedStatement ps, Subscription s) throws SQLException {
    ps.setObject(1, s.id());
    ps.setObject(2, s.tenantId());
    ps.setObject(3, s.planId());
    ps.setString(4, s.status());
    ps.setBigDecimal(5, s.priceAmount());
    ps.setString(6, s.currency());
    ps.setString(7, s.billingInterval());
    ps.setObject(8, s.periodStart());
    ps.setObject(9, s.periodEnd());
    ps.setObject(10, s.trialEnd());
    ps.setObject(11, s.pendingPlanId());
    ps.setBoolean(12, s.cancelAtPeriodEnd());
    Buyer b = s.buyer();
    ps.setString(13, b.name());
    ps.setString(14, b.line1());
    ps.setString(15, b.line2());
    ps.setString(16, b.city());
    ps.setString(17, b.postcode());
    ps.setString(18, b.country());
    ps.setString(19, b.vatNumber());
    ps.setObject(20, offset(b.vatCheckedAt()));
    ps.setObject(21, b.vatCheckedBy());
    ps.setString(22, b.vatCheckSource());
    ps.setString(23, s.billingEmail());
    ps.setObject(24, offset(s.startedAt()));
    ps.setObject(25, offset(s.cancelledAt()));
    ps.setObject(26, offset(s.createdAt()));
    ps.setObject(27, offset(s.updatedAt()));
  }

  private static void bindUpdate(PreparedStatement ps, Subscription s) throws SQLException {
    ps.setObject(1, s.planId());
    ps.setString(2, s.status());
    ps.setBigDecimal(3, s.priceAmount());
    ps.setString(4, s.currency());
    ps.setString(5, s.billingInterval());
    ps.setObject(6, s.periodStart());
    ps.setObject(7, s.periodEnd());
    ps.setObject(8, s.trialEnd());
    ps.setObject(9, s.pendingPlanId());
    ps.setBoolean(10, s.cancelAtPeriodEnd());
    Buyer b = s.buyer();
    ps.setString(11, b.name());
    ps.setString(12, b.line1());
    ps.setString(13, b.line2());
    ps.setString(14, b.city());
    ps.setString(15, b.postcode());
    ps.setString(16, b.country());
    ps.setString(17, b.vatNumber());
    ps.setObject(18, offset(b.vatCheckedAt()));
    ps.setObject(19, b.vatCheckedBy());
    ps.setString(20, b.vatCheckSource());
    ps.setString(21, s.billingEmail());
    ps.setObject(22, offset(s.cancelledAt()));
    ps.setObject(23, Instant.now().atOffset(ZoneOffset.UTC));
    ps.setObject(24, s.id());
  }

  private static void bindInvoice(PreparedStatement ps, Invoice i) throws SQLException {
    ps.setObject(1, i.id());
    ps.setObject(2, i.tenantId());
    ps.setObject(3, i.subscriptionId());
    ps.setString(4, i.number());
    ps.setString(5, i.kind());
    ps.setString(6, i.status());
    ps.setObject(7, i.issueDate());
    ps.setObject(8, i.dueDate());
    ps.setObject(9, i.periodStart());
    ps.setObject(10, i.periodEnd());
    ps.setString(11, i.currency());
    ps.setBigDecimal(12, i.netAmount());
    ps.setString(13, i.taxTreatment());
    ps.setBigDecimal(14, i.taxRate());
    ps.setBigDecimal(15, i.taxAmount());
    ps.setBigDecimal(16, i.totalAmount());
    ps.setBigDecimal(17, i.amountPaid());
    ps.setString(18, i.sellerSnapshot());
    ps.setString(19, i.buyerSnapshot());
    ps.setString(20, i.buyerVatNumber());
    ps.setString(21, null);
    ps.setString(22, i.voidedReason());
    ps.setObject(23, offset(i.createdAt()));
    ps.setObject(24, offset(i.updatedAt()));
  }

  private static Invoice withNumber(Invoice i, String number) {
    return new Invoice(
        i.id(),
        i.tenantId(),
        i.subscriptionId(),
        number,
        i.kind(),
        i.status(),
        i.issueDate(),
        i.dueDate(),
        i.periodStart(),
        i.periodEnd(),
        i.currency(),
        i.netAmount(),
        i.taxTreatment(),
        i.taxRate(),
        i.taxAmount(),
        i.totalAmount(),
        i.amountPaid(),
        i.sellerSnapshot(),
        i.buyerSnapshot(),
        i.buyerVatNumber(),
        i.voidedReason(),
        i.createdAt(),
        i.updatedAt());
  }

  private static OffsetDateTime offset(Instant at) {
    return at == null ? null : at.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    return instantOf(rs.getObject(column, OffsetDateTime.class));
  }

  private static Instant instantOf(OffsetDateTime at) {
    return at == null ? null : at.toInstant();
  }
}
