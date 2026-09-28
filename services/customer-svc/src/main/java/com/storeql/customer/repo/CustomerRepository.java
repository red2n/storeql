package com.storeql.customer.repo;

import com.storeql.customer.domain.CustomerSearch;
import com.storeql.customer.domain.Domain.Customer;
import com.storeql.customer.domain.Domain.CustomerAddress;
import com.storeql.customer.domain.Domain.LoyaltyAccount;
import com.storeql.customer.domain.Domain.LoyaltyLedgerEntry;
import com.storeql.customer.domain.Domain.MarketingConsentEntry;
import com.storeql.customer.domain.Domain.MarketingPreference;
import com.storeql.customer.domain.Domain.StoreCreditAccount;
import com.storeql.customer.domain.Domain.StoreCreditLedgerEntry;
import com.storeql.customer.domain.Domain.TierChange;
import com.storeql.customer.domain.LoyaltyProgramme;
import com.storeql.ids.Ids;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxRow;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Persistence for customers, addresses, loyalty, and store credit. Every query on tenant-owned data
 * filters by tenant_id FIRST (golden rule #3). loyalty_ledger and store_credit_ledger are
 * append-only (golden rule #8).
 */
@ApplicationScoped
public class CustomerRepository extends BaseOutboxRepository {

  // ─────────────────────────────────────────── customers

  /**
   * Inserts a customer and its {@code CustomerRegistered} event in one transaction.
   *
   * @param c the customer to persist; its {@code id} must already be a UUIDv7
   * @param event the outbox row to commit alongside the insert
   * @return the customer as stored
   */
  public Customer createCustomer(Customer c, OutboxRow event) {
    return inTx(
        conn -> {
          insertCustomer(conn, c);
          insertOutbox(conn, event);
          return c;
        },
        "create customer");
  }

  /**
   * Looks a customer up by id.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param customerId the customer to fetch
   * @return the customer, or empty when no such customer exists in this tenant
   */
  public Optional<Customer> findById(UUID tenantId, UUID customerId) {
    return query(
            "SELECT id, tenant_id, login_id, email, phone, first_name, last_name, dob, gender,"
                + " status, gdpr_consent_at, anonymized_at, created_at, updated_at, preferred_language,"
                + " phone_e164, phone_e164_checked_at"
                + " FROM customers WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, customerId);
            },
            CustomerRepository::mapCustomer,
            "find customer by id")
        .stream()
        .findFirst();
  }

  /** Connection-scoped read within an existing transaction; null (not Optional) if not found. */
  private static Customer findById(Connection conn, UUID tenantId, UUID customerId)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "SELECT id, tenant_id, login_id, email, phone, first_name, last_name, dob, gender,"
                + " status, gdpr_consent_at, anonymized_at, created_at, updated_at, preferred_language,"
                + " phone_e164, phone_e164_checked_at"
                + " FROM customers WHERE tenant_id = ? AND id = ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, customerId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? mapCustomer(rs) : null;
      }
    }
  }

  /**
   * Looks a customer up by email within a tenant.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param email the email to match; callers lower-case it first, as stored
   * @return the customer, or empty when nothing matches
   */
  public Optional<Customer> findByEmail(UUID tenantId, String email) {
    return query(
            "SELECT id, tenant_id, login_id, email, phone, first_name, last_name, dob, gender,"
                + " status, gdpr_consent_at, anonymized_at, created_at, updated_at, preferred_language,"
                + " phone_e164, phone_e164_checked_at"
                + " FROM customers WHERE tenant_id = ? AND email = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, email);
            },
            CustomerRepository::mapCustomer,
            "find customer by email")
        .stream()
        .findFirst();
  }

  /**
   * Looks a customer up by the login it belongs to.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param loginId the iam-svc login id
   * @return the customer linked to that login in this tenant, or empty when none is
   */
  public Optional<Customer> findByLogin(UUID tenantId, UUID loginId) {
    return query(
            "SELECT id, tenant_id, login_id, email, phone, first_name, last_name, dob, gender,"
                + " status, gdpr_consent_at, anonymized_at, created_at, updated_at, preferred_language,"
                + " phone_e164, phone_e164_checked_at"
                + " FROM customers WHERE tenant_id = ? AND login_id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, loginId);
            },
            CustomerRepository::mapCustomer,
            "find customer by login")
        .stream()
        .findFirst();
  }

  /**
   * Finds the customer record a login owns in this tenant, creating or adopting one if it has none
   * — the join SJ-D44 was missing, made in one transaction so two concurrent checkouts cannot
   * produce two records for one person.
   *
   * <p>Three cases, in order: the login is already linked and that record is returned untouched; a
   * record exists with the same email and no login of its own, which is the same person the shop
   * typed in at the till, so the link is added to it; or neither, and a new record is created from
   * the email alone. An anonymized record is never adopted or re-linked — erasure is final, and a
   * later order starts a fresh record rather than resurrecting an erased one.
   *
   * @param tenantId owning tenant; the first condition of every query here
   * @param loginId the iam-svc login to link
   * @param email the login's own email, as the gateway read it from the verified JWT
   * @param newId the id to give a record this call creates, minted by the caller so it can build
   *     the event that announces it
   * @param event the outbox row to commit alongside a newly created record; ignored when an
   *     existing record is returned or adopted, because neither creates anything to announce
   * @return the customer record for that login
   */
  public Customer linkLogin(
      UUID tenantId, UUID loginId, String email, UUID newId, OutboxRow event) {
    try {
      return linkLoginTx(tenantId, loginId, email, newId, event);
    } catch (ApiException e) {
      if (!"CUSTOMER_ALREADY_EXISTS".equals(e.code())) {
        throw e;
      }
      // Two checkouts for one login at the same instant: both found no record, both tried to
      // insert, and the unique index let one through. The loser is not a duplicate — it is the
      // same person, and the winner's row is what it should return. A race of eight claims in the
      // tests lost one this way before this branch existed. If the email is instead held by a
      // record linked to a different login, that is a real conflict and is reported as one.
      return findByLogin(tenantId, loginId)
          .orElseThrow(
              () ->
                  ApiException.conflict(
                      "CUSTOMER_EMAIL_LINKED_ELSEWHERE",
                      "this email is already linked to a different login in this shop"));
    }
  }

  private Customer linkLoginTx(
      UUID tenantId, UUID loginId, String email, UUID newId, OutboxRow event) {
    return inTx(
        conn -> {
          Customer linked = findByLoginInTx(conn, tenantId, loginId);
          if (linked != null) {
            return linked;
          }
          Customer adopted = adoptByEmailInTx(conn, tenantId, loginId, email);
          if (adopted != null) {
            return adopted;
          }
          Instant now = Instant.now();
          var created =
              new Customer(
                  newId,
                  tenantId,
                  loginId,
                  email,
                  null,
                  null,
                  null,
                  null,
                  null,
                  Customer.STATUS_ACTIVE,
                  null,
                  null,
                  now,
                  now,
                  null,
                  null,
                  null);
          insertCustomer(conn, created);
          insertOutbox(conn, event);
          return created;
        },
        "link login to customer");
  }

  /** Connection-scoped read within an existing transaction; null (not Optional) if not found. */
  private static Customer findByLoginInTx(Connection conn, UUID tenantId, UUID loginId)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "SELECT id, tenant_id, login_id, email, phone, first_name, last_name, dob, gender,"
                + " status, gdpr_consent_at, anonymized_at, created_at, updated_at, preferred_language,"
                + " phone_e164, phone_e164_checked_at"
                + " FROM customers WHERE tenant_id = ? AND login_id = ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, loginId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? mapCustomer(rs) : null;
      }
    }
  }

  /**
   * Attaches the login to an existing unlinked record with the same email, and returns it; null
   * when there is no such record. The UPDATE carries the whole condition so the adoption is atomic
   * with the check: two concurrent links race on the unique index, and the loser sees a row count
   * of zero rather than overwriting the winner's link.
   */
  private static Customer adoptByEmailInTx(
      Connection conn, UUID tenantId, UUID loginId, String email) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "UPDATE customers SET login_id = ?, updated_at = now()"
                + " WHERE tenant_id = ? AND email = ? AND login_id IS NULL"
                + " AND status != 'ANONYMIZED'")) {
      ps.setObject(1, loginId);
      ps.setObject(2, tenantId);
      ps.setString(3, email);
      if (ps.executeUpdate() == 0) {
        return null;
      }
    }
    return findByLoginInTx(conn, tenantId, loginId);
  }

  /**
   * A customer's store-credit history, newest first. Written on every issue and redemption and
   * never read back until now: a person's own money held by the shop is their data, so a data
   * export (UK GDPR art.20) has to include it.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param customerId the customer whose ledger to read
   * @param limit hard cap on rows
   * @return the entries, newest first
   */
  public List<StoreCreditLedgerEntry> listStoreCreditLedger(
      UUID tenantId, UUID customerId, int limit) {
    return query(
        "SELECT id, tenant_id, customer_id, type, amount, balance_after, currency, order_id,"
            + " reason, created_at"
            + " FROM store_credit_ledger WHERE tenant_id = ? AND customer_id = ?"
            + " ORDER BY created_at DESC LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, customerId);
          ps.setInt(3, limit);
        },
        CustomerRepository::mapStoreCreditEntry,
        "list store credit ledger");
  }

  private static StoreCreditLedgerEntry mapStoreCreditEntry(ResultSet rs) throws SQLException {
    UUID orderId = rs.getObject("order_id", UUID.class);
    return new StoreCreditLedgerEntry(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("customer_id", UUID.class),
        rs.getString("type"),
        rs.getBigDecimal("amount"),
        rs.getBigDecimal("balance_after"),
        rs.getString("currency"),
        orderId,
        rs.getString("reason"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  /**
   * Every store-credit account a customer holds, one per currency.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param customerId the customer whose accounts to read
   * @return the accounts, empty when the customer holds no credit
   */
  public List<StoreCreditAccount> listStoreCreditAccounts(UUID tenantId, UUID customerId) {
    return query(
        "SELECT id, tenant_id, customer_id, balance, currency, created_at, updated_at"
            + " FROM store_credit_accounts WHERE tenant_id = ? AND customer_id = ?"
            + " ORDER BY currency",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, customerId);
        },
        CustomerRepository::mapStoreCreditAccount,
        "list store credit accounts");
  }

  // ─────────────────────────────────────────── marketing consent

  /**
   * What this shop may currently send one person, channel by channel.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param customerId the customer whose preferences to read
   * @return one row per channel that has ever been decided; a channel with no row has no consent
   */
  public List<MarketingPreference> listPreferences(UUID tenantId, UUID customerId) {
    return query(
        "SELECT tenant_id, customer_id, channel, granted, basis, updated_at"
            + " FROM marketing_preferences WHERE tenant_id = ? AND customer_id = ?"
            + " ORDER BY channel",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, customerId);
        },
        CustomerRepository::mapPreference,
        "list marketing preferences");
  }

  /**
   * Reads one channel's preference.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param customerId the customer to check
   * @param channel the channel to check
   * @return the preference, or empty when nothing has ever been decided for that channel
   */
  public Optional<MarketingPreference> findPreference(
      UUID tenantId, UUID customerId, String channel) {
    return query(
            "SELECT tenant_id, customer_id, channel, granted, basis, updated_at"
                + " FROM marketing_preferences"
                + " WHERE tenant_id = ? AND customer_id = ? AND channel = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, customerId);
              ps.setString(3, channel);
            },
            CustomerRepository::mapPreference,
            "find marketing preference")
        .stream()
        .findFirst();
  }

  /**
   * Records one or more consent decisions: the current state and the evidence for it, together.
   *
   * <p>One transaction, because a preference without its log entry is a claim the shop cannot
   * demonstrate (UK GDPR art.7(1)), and a log entry without the preference is a promise it does not
   * keep.
   *
   * @param entries the decisions, each already carrying its own id, source and notice
   * @return how many channels were written
   */
  public int recordConsent(List<MarketingConsentEntry> entries) {
    if (entries.isEmpty()) {
      return 0;
    }
    return inTx(
        c -> {
          for (MarketingConsentEntry e : entries) {
            upsertPreferenceInTx(c, e);
            insertConsentLogInTx(c, e);
          }
          return entries.size();
        },
        "record marketing consent");
  }

  private static void upsertPreferenceInTx(Connection c, MarketingConsentEntry e)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO marketing_preferences"
                + " (tenant_id, customer_id, channel, granted, basis, updated_at)"
                + " VALUES (?,?,?,?,?, now())"
                + " ON CONFLICT (tenant_id, customer_id, channel) DO UPDATE"
                + " SET granted = EXCLUDED.granted, basis = EXCLUDED.basis, updated_at = now()")) {
      ps.setObject(1, e.tenantId());
      ps.setObject(2, e.customerId());
      ps.setString(3, e.channel());
      ps.setBoolean(4, e.granted());
      ps.setString(5, e.basis());
      ps.executeUpdate();
    }
  }

  private static void insertConsentLogInTx(Connection c, MarketingConsentEntry e)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO marketing_consent_log"
                + " (id, tenant_id, customer_id, channel, granted, basis, source, notice,"
                + "  actor_id, recorded_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, e.id());
      ps.setObject(2, e.tenantId());
      ps.setObject(3, e.customerId());
      ps.setString(4, e.channel());
      ps.setBoolean(5, e.granted());
      ps.setString(6, e.basis());
      ps.setString(7, e.source());
      ps.setString(8, e.notice());
      ps.setObject(9, e.actorId());
      ps.setObject(10, e.recordedAt().atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
  }

  /**
   * The marketing-consent cascade: withdrawing the MARKETING purpose switches off every channel
   * that is currently on, on the caller's own transaction, one {@code marketing_consent_log} row
   * per channel with source {@link MarketingConsentEntry#SOURCE_PURPOSE_WITHDRAWN}. Called from
   * {@code PrivacyRepository}, in the same package, so the purpose withdrawal and the channel
   * switch-off commit or roll back together.
   *
   * @param c the open connection of the caller's own transaction
   * @param tenantId owning tenant
   * @param customerId the person whose channels to switch off
   * @param actorId the staff member acting, or {@code null} when the person acted themselves —
   *     carried over from the purpose withdrawal that caused this
   * @param at when the withdrawal was recorded — carried over so the cascade dates with it
   * @return how many channels were switched off
   */
  static int cascadeWithdrawMarketingInTx(
      Connection c, UUID tenantId, UUID customerId, UUID actorId, Instant at) throws SQLException {
    List<String> onChannels = new ArrayList<>();
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT channel FROM marketing_preferences"
                + " WHERE tenant_id = ? AND customer_id = ? AND granted = TRUE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, customerId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          onChannels.add(rs.getString("channel"));
        }
      }
    }
    for (String channel : onChannels) {
      MarketingConsentEntry e =
          new MarketingConsentEntry(
              Ids.newId(),
              tenantId,
              customerId,
              channel,
              false,
              MarketingPreference.BASIS_NONE,
              MarketingConsentEntry.SOURCE_PURPOSE_WITHDRAWN,
              null,
              actorId,
              at);
      upsertPreferenceInTx(c, e);
      insertConsentLogInTx(c, e);
    }
    return onChannels.size();
  }

  /**
   * The tenants with a customer row still needing the marketing-consent-cascade reconciliation : a
   * channel recorded as granted whose MARKETING purpose stands withdrawn — predating this rule, or
   * from a gap before the cascade covered every path.
   *
   * @param limit the most tenants to name at once
   * @return distinct tenant ids, in no particular order
   */
  public List<UUID> distinctTenantsNeedingMarketingReconciliation(int limit) {
    return query(
        "SELECT DISTINCT mp.tenant_id FROM marketing_preferences mp"
            + " WHERE mp.granted = TRUE AND EXISTS ("
            + "   SELECT 1 FROM purpose_consents pc"
            + "    WHERE pc.tenant_id = mp.tenant_id AND pc.customer_id = mp.customer_id"
            + "      AND pc.purpose = 'MARKETING' AND pc.granted = FALSE)"
            + " LIMIT ?",
        ps -> ps.setInt(1, limit),
        rs -> rs.getObject("tenant_id", UUID.class),
        "tenants needing marketing purpose reconciliation");
  }

  /**
   * One batch of the start-up reconciliation, for one tenant: a customer whose MARKETING purpose
   * stands withdrawn but who still has a channel recorded as granted gets that channel switched off
   * the same way {@link #cascadeWithdrawMarketingInTx} does it live, with its own {@code
   * marketing_consent_log} row. Idempotent: once a channel is off, this stops finding it, so
   * running this twice in a row does the second time as nothing.
   *
   * @param tenantId the tenant to reconcile; every row touched is this tenant's
   * @param limit the most channels this batch corrects
   * @return how many channels were switched off in this batch
   */
  public int reconcileMarketingPurposeWithdrawalsBatchForTenant(UUID tenantId, int limit) {
    return inTx(
        c -> {
          List<MarketingConsentEntry> toFix = new ArrayList<>();
          Instant now = Instant.now();
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT mp.customer_id, mp.channel FROM marketing_preferences mp"
                      + " WHERE mp.tenant_id = ? AND mp.granted = TRUE AND EXISTS ("
                      + "   SELECT 1 FROM purpose_consents pc"
                      + "    WHERE pc.tenant_id = mp.tenant_id AND pc.customer_id = mp.customer_id"
                      + "      AND pc.purpose = 'MARKETING' AND pc.granted = FALSE)"
                      + " ORDER BY mp.customer_id, mp.channel LIMIT ?")) {
            ps.setObject(1, tenantId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                toFix.add(
                    new MarketingConsentEntry(
                        Ids.newId(),
                        tenantId,
                        rs.getObject("customer_id", UUID.class),
                        rs.getString("channel"),
                        false,
                        MarketingPreference.BASIS_NONE,
                        MarketingConsentEntry.SOURCE_PURPOSE_WITHDRAWN,
                        null,
                        null,
                        now));
              }
            }
          }
          for (MarketingConsentEntry e : toFix) {
            upsertPreferenceInTx(c, e);
            insertConsentLogInTx(c, e);
          }
          return toFix.size();
        },
        "reconcile marketing purpose withdrawals for tenant");
  }

  /**
   * The evidence trail behind one person's marketing preferences.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param customerId the customer whose trail to read
   * @param limit hard cap on rows
   * @return the entries, newest first
   */
  public List<MarketingConsentEntry> listConsentLog(UUID tenantId, UUID customerId, int limit) {
    return query(
        "SELECT id, tenant_id, customer_id, channel, granted, basis, source, notice, actor_id,"
            + " recorded_at FROM marketing_consent_log WHERE tenant_id = ? AND customer_id = ?"
            + " ORDER BY recorded_at DESC LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, customerId);
          ps.setInt(3, limit);
        },
        CustomerRepository::mapConsentEntry,
        "list marketing consent log");
  }

  /**
   * Stores the hash of a fresh unsubscribe token.
   *
   * @param tenantId owning tenant
   * @param customerId the customer the token opts out
   * @param tokenHash the hash of the token; the token itself is never stored
   */
  public void storeUnsubscribeToken(UUID tenantId, UUID customerId, String tokenHash) {
    inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO marketing_unsubscribe_tokens (token_hash, tenant_id, customer_id)"
                      + " VALUES (?,?,?) ON CONFLICT (token_hash) DO NOTHING")) {
            ps.setString(1, tokenHash);
            ps.setObject(2, tenantId);
            ps.setObject(3, customerId);
            ps.executeUpdate();
          }
          return null;
        },
        "store unsubscribe token");
  }

  /**
   * Who an unsubscribe token belongs to, without spending it.
   *
   * <p>A used token still resolves: an objection to marketing is absolute and does not expire, so
   * clicking the same link twice must opt the person out twice rather than fail the second time.
   *
   * @param tokenHash the hash of the presented token
   * @return the tenant and customer it opts out, or empty when the token is unknown
   */
  public Optional<UnsubscribeSubject> findUnsubscribeSubject(String tokenHash) {
    return query(
            "SELECT tenant_id, customer_id FROM marketing_unsubscribe_tokens WHERE token_hash = ?",
            ps -> ps.setString(1, tokenHash),
            rs ->
                new UnsubscribeSubject(
                    rs.getObject("tenant_id", UUID.class), rs.getObject("customer_id", UUID.class)),
            "find unsubscribe token")
        .stream()
        .findFirst();
  }

  /** Marks a token as having been used, for the audit trail rather than to block a second use. */
  public void markUnsubscribeTokenUsed(String tokenHash) {
    inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE marketing_unsubscribe_tokens SET used_at = now()"
                      + " WHERE token_hash = ? AND used_at IS NULL")) {
            ps.setString(1, tokenHash);
            ps.executeUpdate();
          }
          return null;
        },
        "mark unsubscribe token used");
  }

  /** Who an unsubscribe link belongs to. */
  public record UnsubscribeSubject(UUID tenantId, UUID customerId) {}

  private static MarketingPreference mapPreference(ResultSet rs) throws SQLException {
    return new MarketingPreference(
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("customer_id", UUID.class),
        rs.getString("channel"),
        rs.getBoolean("granted"),
        rs.getString("basis"),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private static MarketingConsentEntry mapConsentEntry(ResultSet rs) throws SQLException {
    return new MarketingConsentEntry(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("customer_id", UUID.class),
        rs.getString("channel"),
        rs.getBoolean("granted"),
        rs.getString("basis"),
        rs.getString("source"),
        rs.getString("notice"),
        rs.getObject("actor_id", UUID.class),
        rs.getObject("recorded_at", OffsetDateTime.class).toInstant());
  }

  /**
   * Looks a customer up by phone within a tenant: {@code phoneE164} — the same query parsed against
   * the business's own countries — matched exactly, first; a number that does not parse still finds
   * a match by today's exact-as-stored rule, kept as the fallback.
   *
   * @param tenantId owning tenant; the first condition of every query here
   * @param phone the phone number as typed, matched exactly against the stored column when the
   *     E.164 match finds nothing
   * @param phoneE164 {@code phone} parsed to E.164 against the business's own countries, or {@code
   *     null} when it does not parse under any of them
   * @return the customer, or empty when nothing matches either way
   */
  public Optional<Customer> findByPhone(UUID tenantId, String phone, String phoneE164) {
    if (phoneE164 != null) {
      Optional<Customer> byE164 =
          query(
                  "SELECT id, tenant_id, login_id, email, phone, first_name, last_name, dob,"
                      + " gender, status, gdpr_consent_at, anonymized_at, created_at, updated_at,"
                      + " preferred_language, phone_e164, phone_e164_checked_at FROM customers"
                      + " WHERE tenant_id = ? AND phone_e164 = ?",
                  ps -> {
                    ps.setObject(1, tenantId);
                    ps.setString(2, phoneE164);
                  },
                  CustomerRepository::mapCustomer,
                  "find customer by phone e164")
              .stream()
              .findFirst();
      if (byE164.isPresent()) {
        return byE164;
      }
    }
    return query(
            "SELECT id, tenant_id, login_id, email, phone, first_name, last_name, dob, gender,"
                + " status, gdpr_consent_at, anonymized_at, created_at, updated_at,"
                + " preferred_language, phone_e164, phone_e164_checked_at FROM customers"
                + " WHERE tenant_id = ? AND phone = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, phone);
            },
            CustomerRepository::mapCustomer,
            "find customer by phone")
        .stream()
        .findFirst();
  }

  /**
   * Cursor-based page — returns up to limit+1 rows so caller can detect next page.
   *
   * <p>The extra row is the caller's next-page signal, not data to render: whoever calls this must
   * trim to {@code limit} before returning it.
   *
   * <p>A search term matches, case-insensitively, anywhere in the full name (so in the first or the
   * last name too), the email or the phone. It is bound as a pattern with its wildcards escaped,
   * never written into the SQL, and narrows the same tenant-first, cursor-paged query as the plain
   * list, so a search pages exactly as the list does. A phone-shaped term of four digits or more
   * also matches the phone reduced to its digits ({@link CustomerSearch#phonePattern}), so the
   * spacing and punctuation of neither the term nor the stored number stand in the way; the same
   * term parsed to E.164 against the business's own countries is matched exactly against {@code
   * phone_e164}, so a Polish shop typing "512 345 678" finds "+48 512 345 678" however it was
   * stored.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param term trimmed text to find, or {@code null} to list every customer
   * @param phoneE164 {@code term} parsed to E.164 against the business's own countries, or {@code
   *     null} when {@code term} is not phone-shaped or none of the business's countries parse it
   * @param afterId cursor — the last id from the previous page, or {@code null} to start
   * @param limit page size; one extra row is fetched beyond it
   * @return up to {@code limit + 1} customers, newest first; anonymized customers are excluded
   */
  public List<Customer> listCustomers(
      UUID tenantId, String term, String phoneE164, String afterId, int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT id, tenant_id, login_id, email, phone, first_name, last_name, dob, gender,"
                + " status, gdpr_consent_at, anonymized_at, created_at, updated_at,"
                + " preferred_language, phone_e164, phone_e164_checked_at FROM customers"
                + " WHERE tenant_id = ? AND status != 'ANONYMIZED'");
    UUID after = afterId == null ? null : Ids.parse(afterId);
    if (after != null) sql.append(" AND id < ?");
    String pattern = term == null ? null : CustomerSearch.pattern(term);
    String digits = term == null ? null : CustomerSearch.phonePattern(term);
    if (pattern != null) {
      // concat_ws skips a null half, so a record with only a first or a last name still matches.
      sql.append(
          " AND (concat_ws(' ', first_name, last_name) ILIKE ? ESCAPE '\\'"
              + " OR email ILIKE ? ESCAPE '\\' OR phone ILIKE ? ESCAPE '\\'");
      // The stored phone as its ASCII digits, against the term's digits — both bound, so however
      // either was spaced or punctuated, the same number is found. Kept alongside the E.164 exact
      // match below, never replaced by it: a number that does not parse still deserves this.
      if (digits != null) sql.append(" OR regexp_replace(phone, '[^0-9]', '', 'g') LIKE ?");
      if (phoneE164 != null) sql.append(" OR phone_e164 = ?");
      sql.append(')');
    }
    sql.append(" ORDER BY created_at DESC, id LIMIT ?");
    String label =
        pattern != null
            ? "search customers"
            : after != null ? "list customers paged" : "list customers";
    return query(
        sql.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (after != null) ps.setObject(i++, after);
          if (pattern != null) {
            ps.setString(i++, pattern);
            ps.setString(i++, pattern);
            ps.setString(i++, pattern);
            if (digits != null) ps.setString(i++, digits);
            if (phoneE164 != null) ps.setString(i++, phoneE164);
          }
          ps.setInt(i, limit + 1);
        },
        CustomerRepository::mapCustomer,
        label);
  }

  /**
   * Writes a customer's mutable profile fields back.
   *
   * @param c the customer carrying the new values; its id and tenant select the row
   * @return the customer as stored
   */
  public Customer updateCustomer(Customer c) {
    // `AND status != 'ANONYMIZED'` makes the guard atomic with the write — without it, a profile
    // update racing a concurrent GDPR anonymize (or simply targeting an already-anonymized
    // customer directly) would silently resurrect erased PII.
    return inTx(
        conn -> {
          int rows;
          try (PreparedStatement ps =
              conn.prepareStatement(
                  "UPDATE customers SET phone=?, first_name=?, last_name=?, dob=?, gender=?,"
                      + " gdpr_consent_at=?, updated_at=?, preferred_language=?, phone_e164=?,"
                      + " phone_e164_checked_at=?"
                      + " WHERE tenant_id=? AND id=? AND status != 'ANONYMIZED'")) {
            ps.setString(1, c.phone());
            ps.setString(2, c.firstName());
            ps.setString(3, c.lastName());
            ps.setObject(4, c.dob() == null ? null : java.sql.Date.valueOf(c.dob()));
            ps.setString(5, c.gender());
            ps.setObject(
                6, c.gdprConsentAt() == null ? null : c.gdprConsentAt().atOffset(ZoneOffset.UTC));
            ps.setObject(7, c.updatedAt().atOffset(ZoneOffset.UTC));
            ps.setString(8, c.preferredLanguage());
            ps.setString(9, c.phoneE164());
            ps.setObject(
                10,
                c.phoneE164CheckedAt() == null
                    ? null
                    : c.phoneE164CheckedAt().atOffset(ZoneOffset.UTC));
            ps.setObject(11, c.tenantId());
            ps.setObject(12, c.id());
            rows = ps.executeUpdate();
          }
          if (rows == 0) {
            Customer existing = findById(conn, c.tenantId(), c.id());
            if (existing == null) {
              throw ApiException.notFound("CUSTOMER_NOT_FOUND", "Customer not found");
            }
            throw ApiException.conflict(
                "CUSTOMER_ANONYMIZED", "Customer has been anonymized and can no longer be updated");
          }
          return findById(conn, c.tenantId(), c.id());
        },
        "update customer");
  }

  /**
   * The tenants with a customer row still needing the phone-to-E.164 backfill: a phone on file that
   * has never been checked against readable regions, whether because this row predates the feature
   * or because a tenant's regions could not be read the last time it was tried.
   *
   * @param limit the most tenants to name at once
   * @return distinct tenant ids, in no particular order
   */
  public List<UUID> distinctTenantsNeedingPhoneBackfill(int limit) {
    return query(
        "SELECT DISTINCT tenant_id FROM customers"
            + " WHERE phone IS NOT NULL AND phone_e164 IS NULL AND phone_e164_checked_at IS NULL"
            + " LIMIT ?",
        ps -> ps.setInt(1, limit),
        rs -> rs.getObject("tenant_id", UUID.class),
        "tenants needing phone backfill");
  }

  /**
   * One batch of the phone-to-E.164 backfill for one tenant, whose regions the caller has already
   * read successfully: every candidate row is stamped with {@code phone_e164_checked_at} whatever
   * it finds, so a genuinely unparseable number is tried once and left alone rather than retried on
   * every future batch or start.
   *
   * @param tenantId the tenant to backfill; every row touched is this tenant's
   * @param homeCountry the business's own country, tried first; {@code null} when not known
   * @param storeCountries the business's stores' countries, tried next
   * @param limit the most rows this batch corrects
   * @return how many rows were stamped in this batch
   */
  public int phoneBackfillBatchForTenant(
      UUID tenantId, String homeCountry, Collection<String> storeCountries, int limit) {
    return inTx(
        c -> {
          record Candidate(UUID id, String phone) {}
          List<Candidate> candidates = new ArrayList<>();
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT id, phone FROM customers"
                      + " WHERE tenant_id = ? AND phone IS NOT NULL AND phone_e164 IS NULL"
                      + " AND phone_e164_checked_at IS NULL LIMIT ?")) {
            ps.setObject(1, tenantId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                candidates.add(
                    new Candidate(rs.getObject("id", UUID.class), rs.getString("phone")));
              }
            }
          }
          Instant now = Instant.now();
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE customers SET phone_e164 = ?, phone_e164_checked_at = ?"
                      + " WHERE tenant_id = ? AND id = ?")) {
            for (Candidate candidate : candidates) {
              String e164 =
                  com.storeql.service.PhoneNumbers.toE164(
                      candidate.phone(), homeCountry, storeCountries);
              ps.setString(1, e164);
              ps.setObject(2, now.atOffset(ZoneOffset.UTC));
              ps.setObject(3, tenantId);
              ps.setObject(4, candidate.id());
              ps.addBatch();
            }
            if (!candidates.isEmpty()) {
              ps.executeBatch();
            }
          }
          return candidates.size();
        },
        "phone backfill batch");
  }

  /**
   * Erases a customer's personal data in this service and tells every other service, in one
   * transaction.
   *
   * <p>SJ-D43. This was a single UPDATE on the customers row. It left every saved address in
   * customer_addresses, published nothing, and nothing anywhere listened — so the names, phones and
   * addresses on the customer's orders, and every message sent to them, survived an erasure.
   *
   * <p>The event is written only by the call that actually erased the customer, so repeating the
   * request does not ask every other service to do it again.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param customerId the customer to erase
   * @param erasedEvent the outbox row to commit alongside the erasure; carries ids only
   * @return the anonymized record, kept for audit
   */
  /**
   * The customers nothing has happened on since the cutoff (21.16): still active, not touched, no
   * loyalty movement and no consent change since. Candidates for the retention purge, which erases
   * each as the customer could have asked.
   */
  public List<UUID> inactiveSince(UUID tenantId, Instant cutoff) {
    return query(
        "SELECT c.id FROM customers c WHERE c.tenant_id = ? AND c.status = 'ACTIVE'"
            + " AND c.updated_at < ?"
            + " AND NOT EXISTS (SELECT 1 FROM loyalty_ledger l WHERE l.tenant_id = c.tenant_id"
            + "   AND l.customer_id = c.id AND l.created_at >= ?)"
            + " AND NOT EXISTS (SELECT 1 FROM marketing_consent_log m WHERE m.tenant_id = c.tenant_id"
            + "   AND m.customer_id = c.id AND m.recorded_at >= ?)"
            + " ORDER BY c.id",
        ps -> {
          var at = cutoff.atOffset(java.time.ZoneOffset.UTC);
          ps.setObject(1, tenantId);
          ps.setObject(2, at);
          ps.setObject(3, at);
          ps.setObject(4, at);
        },
        rs -> rs.getObject("id", UUID.class),
        "inactive customers");
  }

  /** Every business with a customer: the tenants a retention sweep visits. */
  public List<UUID> tenantsWithCustomers() {
    return query(
        "SELECT DISTINCT c.tenant_id FROM customers c ORDER BY c.tenant_id",
        ps -> {},
        rs -> rs.getObject("tenant_id", UUID.class),
        "tenants with customers");
  }

  /**
   * Writes a run's announcement (21.16) on its own: the erasures it counts each committed already.
   */
  public void recordRun(OutboxRow event) {
    inTx(
        c -> {
          insertOutbox(c, event);
          return null;
        },
        "record retention run");
  }

  public Customer anonymize(UUID tenantId, UUID customerId, OutboxRow erasedEvent) {
    Instant now = Instant.now();
    inTx(
        conn -> {
          int erased;
          try (var ps =
              conn.prepareStatement(
                  "UPDATE customers SET email = 'anon-' || id || '@deleted', phone = NULL,"
                      + " phone_e164 = NULL, first_name = 'Deleted', last_name = 'User',"
                      + " dob = NULL, gender = NULL,"
                      + " gdpr_consent_at = NULL, status = 'ANONYMIZED', anonymized_at = ?,"
                      + " updated_at = ? WHERE tenant_id = ? AND id = ? AND status <> 'ANONYMIZED'")) {
            ps.setObject(1, now.atOffset(ZoneOffset.UTC));
            ps.setObject(2, now.atOffset(ZoneOffset.UTC));
            ps.setObject(3, tenantId);
            ps.setObject(4, customerId);
            erased = ps.executeUpdate();
          }
          if (erased > 0) {
            try (var ps =
                conn.prepareStatement(
                    "DELETE FROM customer_addresses WHERE tenant_id = ? AND customer_id = ?")) {
              ps.setObject(1, tenantId);
              ps.setObject(2, customerId);
              ps.executeUpdate();
            }
            insertOutbox(conn, erasedEvent);
          }
          return null;
        },
        "anonymize customer");
    return findById(tenantId, customerId)
        .orElseThrow(() -> ApiException.notFound("CUSTOMER_NOT_FOUND", "Customer not found"));
  }

  // ─────────────────────────────────────────── addresses

  /**
   * Inserts a customer address.
   *
   * @param a the address to persist; its {@code id} must already be a UUIDv7
   * @return the address as stored
   */
  public CustomerAddress createAddress(CustomerAddress a) {
    return inTx(
        conn -> {
          if (a.isDefault()) {
            clearDefaultAddresses(conn, a.tenantId(), a.customerId());
          }
          insertAddress(conn, a);
          return a;
        },
        "create address");
  }

  /**
   * Lists a customer's addresses.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param customerId the customer whose addresses to list
   * @return the addresses, empty when none are on file
   */
  /**
   * Inserts an address unless the customer already holds {@code max}. The count is taken after
   * locking the customer's row, so two inserts racing for the last place serialise and the second
   * sees the first: the cap holds under concurrency rather than by hope.
   *
   * @param a the address to persist; its {@code id} must already be a UUIDv7
   * @param max the most addresses one customer may hold
   * @return the address as stored
   * @throws ApiException {@code CUSTOMER_ADDRESS_LIMIT} (409) when the book is full
   */
  public CustomerAddress createAddressCapped(CustomerAddress a, int max) {
    return inTx(
        conn -> {
          try (PreparedStatement lock =
              conn.prepareStatement(
                  "SELECT id FROM customers WHERE tenant_id = ? AND id = ? FOR UPDATE")) {
            lock.setObject(1, a.tenantId());
            lock.setObject(2, a.customerId());
            try (ResultSet rs = lock.executeQuery()) {
              if (!rs.next()) {
                throw ApiException.notFound("CUSTOMER_NOT_FOUND", "Customer not found");
              }
            }
          }
          try (PreparedStatement count =
              conn.prepareStatement(
                  "SELECT count(*) FROM customer_addresses WHERE tenant_id = ? AND"
                      + " customer_id = ?")) {
            count.setObject(1, a.tenantId());
            count.setObject(2, a.customerId());
            try (ResultSet rs = count.executeQuery()) {
              rs.next();
              if (rs.getLong(1) >= max) {
                throw ApiException.conflict(
                    "CUSTOMER_ADDRESS_LIMIT",
                    "an address book holds at most " + max + " addresses");
              }
            }
          }
          if (a.isDefault()) {
            clearDefaultAddresses(conn, a.tenantId(), a.customerId());
          }
          insertAddress(conn, a);
          return a;
        },
        "create address (capped)");
  }

  public List<CustomerAddress> listAddresses(UUID tenantId, UUID customerId) {
    return query(
        "SELECT id, tenant_id, customer_id, type, line1, line2, city, state, country,"
            + " pincode, is_default, created_at"
            + " FROM customer_addresses WHERE tenant_id = ? AND customer_id = ? ORDER BY created_at",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, customerId);
        },
        CustomerRepository::mapAddress,
        "list addresses");
  }

  /**
   * Writes an address back in full.
   *
   * @param a the address carrying the new values; its id, customer and tenant select the row
   * @return the address as stored
   */
  public CustomerAddress updateAddress(CustomerAddress a) {
    return inTx(
        conn -> {
          if (a.isDefault()) {
            clearDefaultAddresses(conn, a.tenantId(), a.customerId());
          }
          exec(
              conn,
              "UPDATE customer_addresses SET type=?, line1=?, line2=?, city=?, state=?,"
                  + " country=?, pincode=?, is_default=? WHERE tenant_id=? AND customer_id=? AND"
                  + " id=?",
              ps -> {
                ps.setString(1, a.type());
                ps.setString(2, a.line1());
                ps.setString(3, a.line2());
                ps.setString(4, a.city());
                ps.setString(5, a.state());
                ps.setString(6, a.country());
                ps.setString(7, a.pincode());
                ps.setBoolean(8, a.isDefault());
                ps.setObject(9, a.tenantId());
                ps.setObject(10, a.customerId());
                ps.setObject(11, a.id());
              });
          return findAddress(conn, a.tenantId(), a.customerId(), a.id());
        },
        "update address tx");
  }

  /**
   * Deletes one of a customer's addresses.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param customerId the owning customer, also matched so one customer cannot delete another's
   * @param addressId the address to delete
   */
  public void deleteAddress(UUID tenantId, UUID customerId, UUID addressId) {
    exec(
        "DELETE FROM customer_addresses WHERE tenant_id = ? AND customer_id = ? AND id = ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, customerId);
          ps.setObject(3, addressId);
        },
        "delete address");
  }

  /**
   * Looks one of a customer's addresses up by id.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param customerId the owning customer, also matched
   * @param addressId the address to fetch
   * @return the address, or empty when it does not exist or belongs to another customer
   */
  public Optional<CustomerAddress> findAddress(UUID tenantId, UUID customerId, UUID addressId) {
    return query(
            "SELECT id, tenant_id, customer_id, type, line1, line2, city, state, country,"
                + " pincode, is_default, created_at"
                + " FROM customer_addresses WHERE tenant_id = ? AND customer_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, customerId);
              ps.setObject(3, addressId);
            },
            CustomerRepository::mapAddress,
            "find address")
        .stream()
        .findFirst();
  }

  // ─────────────────────────────────────────── loyalty

  /**
   * Credits points, appends the ledger entry, re-derives the tier and writes the event —
   * atomically.
   *
   * <p>Creates the loyalty account on first use. The tier follows lifetime points, not the
   * spendable balance, so redeeming never demotes a customer.
   *
   * @param tenantId owning tenant
   * @param customerId the customer to credit
   * @param points the points to add
   * @param orderId the originating order, or {@code null} for a manual award
   * @param reason free-text reason recorded on the ledger entry
   * @param event the outbox row to commit alongside
   * @return the account with its new balance and tier
   */
  /**
   * Awards points by hand: a lot under the programme's expiry, the ledger entry, the tier the
   * qualifying points now reach (announced when it moves), and the event.
   */
  public LoyaltyAccount earnPoints(
      UUID tenantId,
      UUID customerId,
      BigDecimal points,
      UUID orderId,
      String reason,
      LoyaltyProgramme programme,
      OutboxRow event,
      Function<TierChange, OutboxRow> tierEvent) {
    return inTx(
        conn -> {
          LoyaltyAccount account = getOrCreateLoyaltyAccount(conn, tenantId, customerId);
          LoyaltyAccount updated =
              credit(
                  conn,
                  account,
                  points,
                  LoyaltyLedgerEntry.TYPE_EARN,
                  orderId,
                  reason,
                  programme,
                  tierEvent);
          insertOutbox(conn, event);
          return updated;
        },
        "earn loyalty points");
  }

  /**
   * Accrues a confirmed order's points once: the base points at the customer's tier multiplier,
   * said so in the reason, with the event built for the points actually awarded.
   */
  public LoyaltyAccount accrueFromOrderOnce(
      UUID eventId,
      String consumerName,
      UUID tenantId,
      UUID customerId,
      UUID orderId,
      BigDecimal basePoints,
      LoyaltyProgramme programme,
      Function<BigDecimal, OutboxRow> eventFor,
      Function<TierChange, OutboxRow> tierEvent) {
    return inTx(
        conn -> {
          if (!markProcessedIfNewTx(conn, eventId, consumerName)) {
            return null; // already accrued for this event
          }
          if (!customerExists(conn, tenantId, customerId)) {
            return null; // order referenced a customer this service doesn't hold — skip, no loop
          }
          LoyaltyAccount account = getOrCreateLoyaltyAccount(conn, tenantId, customerId);
          BigDecimal multiplier = programme.multiplierFor(account.tier());
          BigDecimal points = basePoints.multiply(multiplier).setScale(2, RoundingMode.DOWN);
          String reason =
              "Loyalty for order "
                  + orderId
                  + (multiplier.compareTo(BigDecimal.ONE) == 0
                      ? ""
                      : " · "
                          + account.tier()
                          + " ×"
                          + multiplier.stripTrailingZeros().toPlainString());
          LoyaltyAccount updated =
              credit(
                  conn,
                  account,
                  points,
                  LoyaltyLedgerEntry.TYPE_EARN,
                  orderId,
                  reason,
                  programme,
                  tierEvent);
          insertOutbox(conn, eventFor.apply(points));
          return updated;
        },
        "accrue loyalty from order");
  }

  private static boolean customerExists(Connection c, UUID tenantId, UUID customerId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT 1 FROM customers WHERE tenant_id = ? AND id = ? AND status <> 'ANONYMIZED'")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, customerId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  /** Spends points from the lot that dies first; refused when the balance is short. */
  public LoyaltyAccount redeemPoints(
      UUID tenantId,
      UUID customerId,
      BigDecimal points,
      UUID orderId,
      String reason,
      LoyaltyProgramme programme,
      OutboxRow event) {
    return inTx(
        conn -> {
          LoyaltyAccount account = getOrCreateLoyaltyAccount(conn, tenantId, customerId);
          if (account.pointsBalance().compareTo(points) < 0) {
            throw new ApiException(
                422,
                "LOYALTY_INSUFFICIENT_POINTS",
                "Insufficient loyalty points",
                java.util.List.of());
          }
          Instant now = Instant.now();
          LoyaltyLots.consume(
              conn, tenantId, LoyaltyLots.openLots(conn, account, programme, now), points);
          BigDecimal newBalance = account.pointsBalance().subtract(points);
          LoyaltyAccount updated =
              updateLoyaltyAccount(
                  conn,
                  tenantId,
                  customerId,
                  newBalance,
                  account.lifetimePoints(),
                  account.tier(),
                  account.qualifyingPoints(),
                  account.tierSince());
          insertLedgerEntry(
              conn,
              new LoyaltyLedgerEntry(
                  Ids.newId(),
                  tenantId,
                  customerId,
                  LoyaltyLedgerEntry.TYPE_REDEEM,
                  points.negate(),
                  newBalance,
                  orderId,
                  reason,
                  now));
          insertOutbox(conn, event);
          return updated;
        },
        "redeem loyalty points");
  }

  /**
   * A correction: an award is a lot like any earning and may move the tier; a deduction comes out
   * of the lots that die first, and never below zero.
   */
  public LoyaltyAccount adjustPoints(
      UUID tenantId,
      UUID customerId,
      BigDecimal points,
      String reason,
      LoyaltyProgramme programme,
      OutboxRow event,
      Function<TierChange, OutboxRow> tierEvent) {
    return inTx(
        conn -> {
          LoyaltyAccount account = getOrCreateLoyaltyAccount(conn, tenantId, customerId);
          LoyaltyAccount updated;
          if (points.signum() > 0) {
            updated =
                credit(
                    conn,
                    account,
                    points,
                    LoyaltyLedgerEntry.TYPE_ADJUST,
                    null,
                    reason,
                    programme,
                    tierEvent);
          } else {
            Instant now = Instant.now();
            BigDecimal taken = points.negate().min(account.pointsBalance());
            LoyaltyLots.consume(
                conn, tenantId, LoyaltyLots.openLots(conn, account, programme, now), taken);
            BigDecimal newBalance = account.pointsBalance().subtract(taken);
            updated =
                updateLoyaltyAccount(
                    conn,
                    tenantId,
                    customerId,
                    newBalance,
                    account.lifetimePoints(),
                    account.tier(),
                    account.qualifyingPoints(),
                    account.tierSince());
            insertLedgerEntry(
                conn,
                new LoyaltyLedgerEntry(
                    Ids.newId(),
                    tenantId,
                    customerId,
                    LoyaltyLedgerEntry.TYPE_ADJUST,
                    points,
                    newBalance,
                    null,
                    reason,
                    now));
          }
          insertOutbox(conn, event);
          return updated;
        },
        "adjust loyalty points");
  }

  /**
   * Credits points: the lot, the ledger entry, the balances, and the tier the qualifying points now
   * reach — announced through {@code tierEvent} when it moves.
   */
  private LoyaltyAccount credit(
      Connection conn,
      LoyaltyAccount account,
      BigDecimal points,
      String type,
      UUID orderId,
      String reason,
      LoyaltyProgramme programme,
      Function<TierChange, OutboxRow> tierEvent)
      throws SQLException {
    Instant now = Instant.now();
    UUID tenantId = account.tenantId();
    UUID customerId = account.customerId();
    if (points.signum() > 0) {
      // A balance from before lots existed becomes an opening lot first, so spending order holds.
      LoyaltyLots.openLots(conn, account, programme, now);
    }
    BigDecimal newBalance = account.pointsBalance().add(points);
    BigDecimal newLifetime = account.lifetimePoints().add(points);
    UUID entryId = Ids.newId();
    insertLedgerEntry(
        conn,
        new LoyaltyLedgerEntry(
            entryId, tenantId, customerId, type, points, newBalance, orderId, reason, now));
    LoyaltyLots.insertLot(
        conn, tenantId, customerId, entryId, points, now, programme.expiryFor(now));
    LoyaltyAccount provisional =
        new LoyaltyAccount(
            account.id(),
            tenantId,
            customerId,
            newBalance,
            newLifetime,
            account.tier(),
            account.createdAt(),
            now,
            account.qualifyingPoints(),
            account.tierSince());
    BigDecimal qualifying = LoyaltyLots.qualifyingPoints(conn, provisional, programme, now);
    String tier = programme.tierFor(qualifying).name();
    Instant tierSince =
        tier.equals(account.tier()) && account.tierSince() != null ? account.tierSince() : now;
    LoyaltyAccount updated =
        updateLoyaltyAccount(
            conn, tenantId, customerId, newBalance, newLifetime, tier, qualifying, tierSince);
    if (!tier.equals(account.tier()) && tierEvent != null) {
      insertOutbox(
          conn,
          tierEvent.apply(new TierChange(tenantId, customerId, account.tier(), tier, qualifying)));
    }
    return updated;
  }

  public Optional<LoyaltyAccount> findLoyaltyAccount(UUID tenantId, UUID customerId) {
    return query(
            "SELECT id, tenant_id, customer_id, points_balance, lifetime_points, tier,"
                + " created_at, updated_at, qualifying_points, tier_since"
                + " FROM loyalty_accounts WHERE tenant_id = ? AND customer_id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, customerId);
            },
            CustomerRepository::mapLoyaltyAccount,
            "find loyalty account")
        .stream()
        .findFirst();
  }

  /**
   * Reads the append-only loyalty ledger for a customer.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param customerId the customer whose ledger to read
   * @param limit maximum rows; the caller is expected to have capped this
   * @return the entries, newest first
   */
  public List<LoyaltyLedgerEntry> listLedger(UUID tenantId, UUID customerId, int limit) {
    return query(
        "SELECT id, tenant_id, customer_id, type, points, balance_after, order_id,"
            + " reason, created_at"
            + " FROM loyalty_ledger WHERE tenant_id = ? AND customer_id = ?"
            + " ORDER BY created_at DESC LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, customerId);
          ps.setInt(3, limit);
        },
        CustomerRepository::mapLedgerEntry,
        "list loyalty ledger");
  }

  // ─────────────────────────────────────────── store credit

  /**
   * Credits store credit, appends the ledger entry and writes the event — atomically.
   *
   * <p>Creates the per-currency account on first use.
   *
   * @param tenantId owning tenant
   * @param customerId the customer to credit
   * @param amount the amount to add
   * @param currency ISO-4217 code the balance is held in
   * @param orderId the originating order, or {@code null}
   * @param reason free-text reason recorded on the ledger entry
   * @param event the outbox row to commit alongside
   * @return the account with its new balance
   */
  public StoreCreditAccount issueStoreCredit(
      UUID tenantId,
      UUID customerId,
      BigDecimal amount,
      String currency,
      UUID orderId,
      String reason,
      OutboxRow event) {
    return inTx(
        conn -> {
          StoreCreditAccount account =
              getOrCreateStoreCreditAccount(conn, tenantId, customerId, currency);
          BigDecimal newBalance = account.balance().add(amount);
          StoreCreditAccount updated =
              updateStoreCreditAccount(conn, tenantId, customerId, currency, newBalance);
          insertStoreCreditEntry(
              conn,
              new StoreCreditLedgerEntry(
                  Ids.newId(),
                  tenantId,
                  customerId,
                  StoreCreditLedgerEntry.TYPE_ISSUE,
                  amount,
                  newBalance,
                  currency,
                  orderId,
                  reason,
                  Instant.now()));
          insertOutbox(conn, event);
          return updated;
        },
        "issue store credit");
  }

  /**
   * Debits store credit, appends the ledger entry and writes the event — atomically.
   *
   * <p>The balance check happens inside the transaction, so concurrent redemptions cannot together
   * overdraw the account.
   *
   * @param tenantId owning tenant
   * @param customerId the customer to debit
   * @param amount the amount to spend
   * @param currency ISO-4217 code the balance is held in
   * @param orderId the order being paid towards, or {@code null}
   * @param reason free-text reason recorded on the ledger entry
   * @param event the outbox row to commit alongside
   * @return the account with its new balance
   */
  public StoreCreditAccount redeemStoreCredit(
      UUID tenantId,
      UUID customerId,
      BigDecimal amount,
      String currency,
      UUID orderId,
      String reason,
      OutboxRow event) {
    return inTx(
        conn -> {
          StoreCreditAccount account =
              getOrCreateStoreCreditAccount(conn, tenantId, customerId, currency);
          // Idempotent per order: a store-credit tender against an order may be retried by
          // payment-svc; a REDEEM already recorded for this order is a no-op, not a second
          // deduction.
          if (orderId != null && storeCreditRedeemExistsTx(conn, tenantId, customerId, orderId)) {
            return account;
          }
          if (account.balance().compareTo(amount) < 0) {
            throw new ApiException(
                422, "STORE_CREDIT_INSUFFICIENT", "Insufficient store credit", java.util.List.of());
          }
          BigDecimal newBalance = account.balance().subtract(amount);
          StoreCreditAccount updated =
              updateStoreCreditAccount(conn, tenantId, customerId, currency, newBalance);
          insertStoreCreditEntry(
              conn,
              new StoreCreditLedgerEntry(
                  Ids.newId(),
                  tenantId,
                  customerId,
                  StoreCreditLedgerEntry.TYPE_REDEEM,
                  amount.negate(),
                  newBalance,
                  currency,
                  orderId,
                  reason,
                  Instant.now()));
          insertOutbox(conn, event);
          return updated;
        },
        "redeem store credit");
  }

  private static boolean storeCreditRedeemExistsTx(
      java.sql.Connection c, UUID tenantId, UUID customerId, UUID orderId) throws SQLException {
    try (var ps =
        c.prepareStatement(
            "SELECT 1 FROM store_credit_ledger"
                + " WHERE tenant_id = ? AND customer_id = ? AND order_id = ? AND type = 'REDEEM'")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, customerId);
      ps.setObject(3, orderId);
      try (var rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  /**
   * Reads a customer's store-credit balance in one currency.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param customerId the customer whose balance to read
   * @param currency ISO-4217 code; balances are held per currency
   * @return the account, or empty when the customer holds no credit in that currency
   */
  public Optional<StoreCreditAccount> findStoreCreditAccount(
      UUID tenantId, UUID customerId, String currency) {
    return query(
            "SELECT id, tenant_id, customer_id, balance, currency, created_at, updated_at"
                + " FROM store_credit_accounts WHERE tenant_id = ? AND customer_id = ? AND currency = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, customerId);
              ps.setString(3, currency);
            },
            CustomerRepository::mapStoreCreditAccount,
            "find store credit account")
        .stream()
        .findFirst();
  }

  // ─────────────────────────────────────────── private helpers

  @Override
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
      return new ApiException(
          409,
          "CUSTOMER_ALREADY_EXISTS",
          "A customer with this email already exists",
          java.util.List.of(),
          e);
    }
    return dbError(what, e);
  }

  private void insertCustomer(Connection c, Customer customer) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO customers (id, tenant_id, login_id, email, phone, first_name, last_name,"
                + " dob, gender, status, gdpr_consent_at, created_at, updated_at, preferred_language,"
                + " phone_e164, phone_e164_checked_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, customer.id());
      ps.setObject(2, customer.tenantId());
      ps.setObject(3, customer.loginId());
      ps.setString(4, customer.email());
      ps.setString(5, customer.phone());
      ps.setString(6, customer.firstName());
      ps.setString(7, customer.lastName());
      ps.setObject(8, customer.dob() == null ? null : java.sql.Date.valueOf(customer.dob()));
      ps.setString(9, customer.gender());
      ps.setString(10, customer.status());
      ps.setObject(
          11,
          customer.gdprConsentAt() == null
              ? null
              : customer.gdprConsentAt().atOffset(ZoneOffset.UTC));
      ps.setObject(12, customer.createdAt().atOffset(ZoneOffset.UTC));
      ps.setObject(13, customer.createdAt().atOffset(ZoneOffset.UTC));
      ps.setString(14, customer.preferredLanguage());
      ps.setString(15, customer.phoneE164());
      ps.setObject(
          16,
          customer.phoneE164CheckedAt() == null
              ? null
              : customer.phoneE164CheckedAt().atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
  }

  private void clearDefaultAddresses(Connection c, UUID tenantId, UUID customerId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE customer_addresses SET is_default = false WHERE tenant_id = ? AND customer_id = ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, customerId);
      ps.executeUpdate();
    }
  }

  private void insertAddress(Connection c, CustomerAddress a) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO customer_addresses (id, tenant_id, customer_id, type, line1, line2,"
                + " city, state, country, pincode, is_default, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, a.id());
      ps.setObject(2, a.tenantId());
      ps.setObject(3, a.customerId());
      ps.setString(4, a.type());
      ps.setString(5, a.line1());
      ps.setString(6, a.line2());
      ps.setString(7, a.city());
      ps.setString(8, a.state());
      ps.setString(9, a.country());
      ps.setString(10, a.pincode());
      ps.setBoolean(11, a.isDefault());
      ps.setObject(12, a.createdAt().atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
  }

  private CustomerAddress findAddress(Connection c, UUID tenantId, UUID customerId, UUID addressId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, tenant_id, customer_id, type, line1, line2, city, state, country,"
                + " pincode, is_default, created_at"
                + " FROM customer_addresses WHERE tenant_id = ? AND customer_id = ? AND id = ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, customerId);
      ps.setObject(3, addressId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) return mapAddress(rs);
        throw ApiException.notFound("ADDRESS_NOT_FOUND", "Address not found");
      }
    }
  }

  private void exec(Connection c, String sql, ThrowingConsumer<PreparedStatement> binder)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      binder.accept(ps);
      ps.executeUpdate();
    }
  }

  @FunctionalInterface
  private interface ThrowingConsumer<T> {
    void accept(T t) throws SQLException;
  }

  private LoyaltyAccount getOrCreateLoyaltyAccount(Connection c, UUID tenantId, UUID customerId)
      throws SQLException {
    Instant now = Instant.now();
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO loyalty_accounts (id, tenant_id, customer_id, points_balance,"
                + " lifetime_points, tier, created_at, updated_at, qualifying_points, tier_since)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?)"
                + " ON CONFLICT (tenant_id, customer_id) DO NOTHING")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, tenantId);
      ps.setObject(3, customerId);
      ps.setBigDecimal(4, BigDecimal.ZERO);
      ps.setBigDecimal(5, BigDecimal.ZERO);
      ps.setString(6, LoyaltyAccount.TIER_BRONZE);
      ps.setObject(7, now.atOffset(ZoneOffset.UTC));
      ps.setObject(8, now.atOffset(ZoneOffset.UTC));
      ps.setBigDecimal(9, BigDecimal.ZERO);
      ps.setObject(10, now.atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
    // FOR UPDATE: this row is read-modify-written by earn/redeem/adjust. Locking it for the
    // duration of the transaction serializes concurrent point mutations on the same account, so two
    // simultaneous redeems can't both pass the balance check and double-spend (golden rule:
    // money/balance mutations take a row lock — same pattern as gift-card/inventory/payment).
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, tenant_id, customer_id, points_balance, lifetime_points, tier,"
                + " created_at, updated_at, qualifying_points, tier_since"
                + " FROM loyalty_accounts WHERE tenant_id = ? AND customer_id = ? FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, customerId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) return mapLoyaltyAccount(rs);
      }
    }
    throw new IllegalStateException(
        "loyalty account missing after upsert for customer " + customerId);
  }

  private LoyaltyAccount updateLoyaltyAccount(
      Connection c,
      UUID tenantId,
      UUID customerId,
      BigDecimal balance,
      BigDecimal lifetime,
      String tier,
      BigDecimal qualifying,
      Instant tierSince)
      throws SQLException {
    Instant now = Instant.now();
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE loyalty_accounts SET points_balance=?, lifetime_points=?, tier=?, updated_at=?,"
                + " qualifying_points=?, tier_since=?"
                + " WHERE tenant_id=? AND customer_id=?")) {
      ps.setBigDecimal(1, balance);
      ps.setBigDecimal(2, lifetime);
      ps.setString(3, tier);
      ps.setObject(4, now.atOffset(ZoneOffset.UTC));
      ps.setBigDecimal(5, qualifying);
      ps.setObject(6, tierSince == null ? null : tierSince.atOffset(ZoneOffset.UTC));
      ps.setObject(7, tenantId);
      ps.setObject(8, customerId);
      ps.executeUpdate();
    }
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, tenant_id, customer_id, points_balance, lifetime_points, tier,"
                + " created_at, updated_at, qualifying_points, tier_since"
                + " FROM loyalty_accounts WHERE tenant_id = ? AND customer_id = ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, customerId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw new IllegalStateException("loyalty account disappeared after update");
        }
        return mapLoyaltyAccount(rs);
      }
    }
  }

  private void insertLedgerEntry(Connection c, LoyaltyLedgerEntry e) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO loyalty_ledger (id, tenant_id, customer_id, type, points, balance_after,"
                + " order_id, reason, created_at) VALUES (?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, e.id());
      ps.setObject(2, e.tenantId());
      ps.setObject(3, e.customerId());
      ps.setString(4, e.type());
      ps.setBigDecimal(5, e.points());
      ps.setBigDecimal(6, e.balanceAfter());
      ps.setObject(7, e.orderId());
      ps.setString(8, e.reason());
      ps.setObject(9, e.createdAt().atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
  }

  private StoreCreditAccount getOrCreateStoreCreditAccount(
      Connection c, UUID tenantId, UUID customerId, String currency) throws SQLException {
    Instant now = Instant.now();
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO store_credit_accounts (id, tenant_id, customer_id, balance, currency,"
                + " created_at, updated_at) VALUES (?,?,?,?,?,?,?)"
                + " ON CONFLICT (tenant_id, customer_id, currency) DO NOTHING")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, tenantId);
      ps.setObject(3, customerId);
      ps.setBigDecimal(4, BigDecimal.ZERO);
      ps.setString(5, currency);
      ps.setObject(6, now.atOffset(ZoneOffset.UTC));
      ps.setObject(7, now.atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
    // FOR UPDATE: store credit is real money. Lock the account row so concurrent issue/redeem on
    // the same account serialize — otherwise two simultaneous redeems both read the same balance,
    // both pass the guard, and both write, over-spending the balance (there is no DB CHECK
    // backstop).
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, tenant_id, customer_id, balance, currency, created_at, updated_at"
                + " FROM store_credit_accounts"
                + " WHERE tenant_id = ? AND customer_id = ? AND currency = ? FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, customerId);
      ps.setString(3, currency);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) return mapStoreCreditAccount(rs);
      }
    }
    throw new IllegalStateException(
        "store_credit_account missing after upsert for customer " + customerId);
  }

  private StoreCreditAccount updateStoreCreditAccount(
      Connection c, UUID tenantId, UUID customerId, String currency, BigDecimal balance)
      throws SQLException {
    Instant now = Instant.now();
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE store_credit_accounts SET balance=?, updated_at=?"
                + " WHERE tenant_id=? AND customer_id=? AND currency=?")) {
      ps.setBigDecimal(1, balance);
      ps.setObject(2, now.atOffset(ZoneOffset.UTC));
      ps.setObject(3, tenantId);
      ps.setObject(4, customerId);
      ps.setString(5, currency);
      ps.executeUpdate();
    }
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, tenant_id, customer_id, balance, currency, created_at, updated_at"
                + " FROM store_credit_accounts WHERE tenant_id=? AND customer_id=? AND currency=?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, customerId);
      ps.setString(3, currency);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw new IllegalStateException("store credit account disappeared after update");
        }
        return mapStoreCreditAccount(rs);
      }
    }
  }

  private void insertStoreCreditEntry(Connection c, StoreCreditLedgerEntry e) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO store_credit_ledger (id, tenant_id, customer_id, type, amount, balance_after,"
                + " currency, order_id, reason, created_at) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, e.id());
      ps.setObject(2, e.tenantId());
      ps.setObject(3, e.customerId());
      ps.setString(4, e.type());
      ps.setBigDecimal(5, e.amount());
      ps.setBigDecimal(6, e.balanceAfter());
      ps.setString(7, e.currency());
      ps.setObject(8, e.orderId());
      ps.setString(9, e.reason());
      ps.setObject(10, e.createdAt().atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
  }

  // ─────────────────────────────────────────── row mappers

  private static Customer mapCustomer(ResultSet rs) throws SQLException {
    OffsetDateTime gdpr = rs.getObject("gdpr_consent_at", OffsetDateTime.class);
    OffsetDateTime anon = rs.getObject("anonymized_at", OffsetDateTime.class);
    LocalDate dob = rs.getObject("dob", LocalDate.class);
    OffsetDateTime phoneChecked = rs.getObject("phone_e164_checked_at", OffsetDateTime.class);
    return new Customer(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("login_id", UUID.class),
        rs.getString("email"),
        rs.getString("phone"),
        rs.getString("first_name"),
        rs.getString("last_name"),
        dob,
        rs.getString("gender"),
        rs.getString("status"),
        gdpr == null ? null : gdpr.toInstant(),
        anon == null ? null : anon.toInstant(),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
        rs.getString("preferred_language"),
        rs.getString("phone_e164"),
        phoneChecked == null ? null : phoneChecked.toInstant());
  }

  private static CustomerAddress mapAddress(ResultSet rs) throws SQLException {
    return new CustomerAddress(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("customer_id", UUID.class),
        rs.getString("type"),
        rs.getString("line1"),
        rs.getString("line2"),
        rs.getString("city"),
        rs.getString("state"),
        rs.getString("country"),
        rs.getString("pincode"),
        rs.getBoolean("is_default"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  static LoyaltyAccount mapLoyaltyAccount(ResultSet rs) throws SQLException {
    OffsetDateTime tierSince = rs.getObject("tier_since", OffsetDateTime.class);
    return new LoyaltyAccount(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("customer_id", UUID.class),
        rs.getBigDecimal("points_balance"),
        rs.getBigDecimal("lifetime_points"),
        rs.getString("tier"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
        rs.getBigDecimal("qualifying_points"),
        tierSince == null ? null : tierSince.toInstant());
  }

  private static LoyaltyLedgerEntry mapLedgerEntry(ResultSet rs) throws SQLException {
    UUID orderId = rs.getObject("order_id", UUID.class);
    return new LoyaltyLedgerEntry(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("customer_id", UUID.class),
        rs.getString("type"),
        rs.getBigDecimal("points"),
        rs.getBigDecimal("balance_after"),
        orderId,
        rs.getString("reason"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  private static StoreCreditAccount mapStoreCreditAccount(ResultSet rs) throws SQLException {
    return new StoreCreditAccount(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("customer_id", UUID.class),
        rs.getBigDecimal("balance"),
        rs.getString("currency"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }
}
