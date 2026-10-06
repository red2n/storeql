package com.storeql.payment.repo;

import com.storeql.payment.domain.Domain.PaymentIntent;
import com.storeql.payment.domain.Domain.PaymentTender;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxRow;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/** Persistence for {@link PaymentIntent} and the provider-webhook dedupe table (V7). */
@ApplicationScoped
public class PaymentIntentRepository extends BaseOutboxRepository {

  private static final String COLUMNS =
      "id, tenant_id, order_id, store_id, provider, provider_ref, amount, captured_amount,"
          + " currency, status, next_action_url, failure_code, failure_message, payment_id,"
          + " idempotency_key, created_at, updated_at";

  /**
   * Inserts an intent, replaying the stored one when the same Idempotency-Key has been seen for
   * this tenant (golden rule #11). Replay matters more here than anywhere else in the service: a
   * retried checkout that creates a second intent places a second hold on the customer's card.
   *
   * @param intent the intent to store
   * @return the stored intent, or the original if this key was already used
   */
  public PaymentIntent create(PaymentIntent intent) {
    return inTx(
        c -> {
          if (intent.idempotencyKey() != null) {
            PaymentIntent existing = findByKeyTx(c, intent.tenantId(), intent.idempotencyKey());
            if (existing != null) {
              return existing;
            }
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO payment_intents ("
                      + COLUMNS
                      + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            bindAll(ps, intent);
            ps.executeUpdate();
          }
          return intent;
        },
        "create payment intent");
  }

  /**
   * @param tenantId owning tenant
   * @param id intent id
   * @return the intent, or null if this tenant has no such intent
   */
  public PaymentIntent findById(UUID tenantId, UUID id) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT " + COLUMNS + " FROM payment_intents WHERE tenant_id = ? AND id = ?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, id);
            try (ResultSet rs = ps.executeQuery()) {
              return rs.next() ? map(rs) : null;
            }
          }
        },
        "find payment intent");
  }

  /**
   * Resolves the intent a provider holds a reference for, whichever business it belongs to.
   *
   * <p>Deliberately not tenant-scoped: the provider's reference is the key a webhook is found by
   * (an event may carry nothing else of ours), so the tenant is what the row says it is rather than
   * something the caller may assert. That is safe because the reference is an opaque
   * provider-issued id which no external caller can guess, and because the signature has already
   * been verified before this is reached. The lookup crosses tenants, so it is named that way. When
   * the event also carries our own ids, {@code PaymentIntentService} compares them with the row
   * found and applies the event to nothing if they differ. Looking an intent up by those ids, in
   * the business they name, is {@link #findById}.
   *
   * @param provider provider name
   * @param providerRef the provider's reference for the intent
   * @return the intent, or null if the reference is unknown
   */
  public PaymentIntent findByProviderRefAcrossTenants(String provider, String providerRef) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT "
                      + COLUMNS
                      + " FROM payment_intents WHERE provider = ? AND provider_ref = ?")) {
            ps.setString(1, provider);
            ps.setString(2, providerRef);
            try (ResultSet rs = ps.executeQuery()) {
              return rs.next() ? map(rs) : null;
            }
          }
        },
        "find payment intent by provider ref");
  }

  /**
   * Gives an intent the provider reference it has not learnt yet, and says how it stands.
   *
   * <p>An intent has no reference from the moment its row is written until the provider's answer to
   * the authorisation is recorded, and keeps none for good when that answer never came, so an event
   * the provider sends meanwhile is matched by the intent's own id instead. Written here, the
   * reference lets the next event be matched by it and a capture name the provider's own object. A
   * reference an intent already holds is never replaced.
   *
   * @param tenantId owning tenant; the first condition of the statement
   * @param id intent id
   * @param providerRef the provider's reference, as an event named it
   * @return the intent as it now stands, holding {@code providerRef} unless another reference got
   *     there first; null if this tenant has no such intent
   */
  public PaymentIntent adoptProviderRef(UUID tenantId, UUID id, String providerRef) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE payment_intents SET provider_ref = ?, updated_at = now()"
                      + " WHERE tenant_id = ? AND id = ? AND provider_ref IS NULL")) {
            ps.setString(1, providerRef);
            ps.setObject(2, tenantId);
            ps.setObject(3, id);
            ps.executeUpdate();
          }
          return findByIdTx(c, tenantId, id);
        },
        "adopt payment intent provider ref");
  }

  /**
   * Records the provider's answer to {@code authorize}: its reference, the resulting status, and
   * any SCA step the customer must complete.
   *
   * <p>Forward only, because an event the provider sent in the meantime may have been applied
   * already (matched by the intent's own id, as it holds no reference until this runs): the
   * reference is written unless the intent holds one, an intent that is already AUTHORIZED keeps
   * its status and its next step rather than going back to waiting for the customer, and an intent
   * that has finished is not touched.
   *
   * @param tenantId owning tenant
   * @param id intent id
   * @param providerRef the provider's reference
   * @param status new status
   * @param nextActionUrl SCA redirect, or null
   */
  public void markAuthorized(
      UUID tenantId, UUID id, String providerRef, String status, String nextActionUrl) {
    inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE payment_intents SET provider_ref = COALESCE(provider_ref, ?),"
                      + " status = CASE WHEN status = 'AUTHORIZED' THEN status ELSE ? END,"
                      + " next_action_url = CASE WHEN status = 'AUTHORIZED' THEN next_action_url"
                      + " ELSE ? END, updated_at = now()"
                      + " WHERE tenant_id = ? AND id = ?"
                      + " AND status IN ('REQUIRES_ACTION','AUTHORIZED')")) {
            ps.setString(1, providerRef);
            ps.setString(2, status);
            ps.setString(3, nextActionUrl);
            ps.setObject(4, tenantId);
            ps.setObject(5, id);
            ps.executeUpdate();
          }
          return null;
        },
        "mark payment intent authorized");
  }

  /**
   * Moves an intent to a terminal failure state.
   *
   * @param tenantId owning tenant
   * @param id intent id
   * @param status {@code FAILED} or {@code CANCELLED}
   * @param failureCode provider code, or null
   * @param failureMessage provider message, or null
   * @return {@code true} if a row moved; {@code false} if it was already terminal
   */
  public boolean markTerminal(
      UUID tenantId, UUID id, String status, String failureCode, String failureMessage) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE payment_intents SET status = ?, failure_code = ?, failure_message = ?,"
                      + " updated_at = now()"
                      + " WHERE tenant_id = ? AND id = ?"
                      + " AND status IN ('REQUIRES_ACTION','AUTHORIZED')")) {
            ps.setString(1, status);
            ps.setString(2, failureCode);
            ps.setString(3, failureMessage);
            ps.setObject(4, tenantId);
            ps.setObject(5, id);
            return ps.executeUpdate() == 1;
          }
        },
        "mark payment intent terminal");
  }

  /**
   * Captures an intent: writes the tender that records the money, points the intent at it, and
   * publishes {@code PaymentCaptured} — all in one transaction, so an intent can never read as
   * captured without the tender that proves it, and the event can never escape without both.
   *
   * <p>The intent row is locked {@code FOR UPDATE} and the status re-checked inside the lock, so a
   * webhook racing a manual capture cannot both win and write two tenders for one authorisation.
   *
   * @param tenantId owning tenant
   * @param intentId intent to capture
   * @param tender the tender to write
   * @param event the outbox event to publish
   * @return the tender written, or the existing one if this intent was already captured
   */
  public PaymentTender captureGuarded(
      UUID tenantId, UUID intentId, PaymentTender tender, OutboxRow event) {
    return inTx(
        c -> {
          PaymentIntent intent = lockTx(c, tenantId, intentId);
          if (intent == null) {
            return null;
          }
          if (PaymentIntent.STATUS_CAPTURED.equals(intent.status())) {
            // Already captured — return the tender it points at rather than writing a second.
            return findTenderTx(c, tenantId, intent.paymentId());
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO payment_tenders"
                      + " (id, tenant_id, order_id, amount, method, reference,"
                      + "  idempotency_key, status, notes, created_at, store_id)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, tender.id());
            ps.setObject(2, tender.tenantId());
            ps.setObject(3, tender.orderId());
            ps.setBigDecimal(4, tender.amount());
            ps.setString(5, tender.method());
            ps.setString(6, tender.reference());
            ps.setString(7, tender.idempotencyKey());
            ps.setString(8, tender.status());
            ps.setString(9, tender.notes());
            ps.setObject(10, tender.createdAt().atOffset(ZoneOffset.UTC));
            ps.setObject(11, tender.storeId());
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE payment_intents SET status = ?, captured_amount = ?, payment_id = ?,"
                      + " next_action_url = NULL, updated_at = now()"
                      + " WHERE tenant_id = ? AND id = ?")) {
            ps.setString(1, PaymentIntent.STATUS_CAPTURED);
            ps.setBigDecimal(2, tender.amount());
            ps.setObject(3, tender.id());
            ps.setObject(4, tenantId);
            ps.setObject(5, intentId);
            ps.executeUpdate();
          }
          insertOutbox(c, event);
          return tender;
        },
        "capture payment intent");
  }

  /**
   * Dedupe guard for provider webhooks: records that this event was seen, and reports whether it is
   * new (golden rule #7). Every provider redelivers, so this is what stops one delivery capturing
   * money twice.
   *
   * @param provider provider name
   * @param providerEventId the provider's event id
   * @param eventType provider event type, stored for the audit trail
   * @return {@code true} if this event had not been seen before
   */
  public boolean hasSeenWebhook(String provider, String providerEventId) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT 1 FROM payment_webhook_events"
                      + " WHERE provider = ? AND provider_event_id = ?")) {
            ps.setString(1, provider);
            ps.setString(2, providerEventId);
            try (var rs = ps.executeQuery()) {
              return rs.next();
            }
          }
        },
        "check webhook event seen");
  }

  /**
   * Claims a provider webhook event for processing, exactly once.
   *
   * <p>The insert is the claim: the unique constraint on {@code (provider, providerEventId)} is
   * what makes a redelivered webhook a no-op, so this must be called before acting on the event
   * rather than after.
   *
   * <p>Not tenant-scoped — the provider's event id is the key, and the tenant is only known once
   * the referenced intent is resolved.
   *
   * @param provider the provider that sent the event, e.g. {@code STRIPE}
   * @param providerEventId the provider's own event id
   * @param eventType the provider's event type, recorded for traceability
   * @return {@code true} when this call claimed the event, {@code false} when it was already seen
   */
  public boolean markWebhookSeenIfNew(String provider, String providerEventId, String eventType) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO payment_webhook_events (provider, provider_event_id, event_type)"
                      + " VALUES (?,?,?) ON CONFLICT (provider, provider_event_id) DO NOTHING")) {
            ps.setString(1, provider);
            ps.setString(2, providerEventId);
            ps.setString(3, eventType);
            return ps.executeUpdate() == 1;
          }
        },
        "record webhook event");
  }

  /**
   * The tender an already-captured intent points at.
   *
   * @param tenantId owning tenant
   * @param intentId the captured intent
   * @return the tender, or null if the intent is unknown or not captured
   */
  public PaymentTender findCapturedTender(UUID tenantId, UUID intentId) {
    return inTx(
        c -> {
          PaymentIntent intent = findByIdTx(c, tenantId, intentId);
          return intent == null ? null : findTenderTx(c, tenantId, intent.paymentId());
        },
        "find captured tender");
  }

  private PaymentIntent findByIdTx(Connection c, UUID tenantId, UUID id) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT " + COLUMNS + " FROM payment_intents WHERE tenant_id = ? AND id = ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, id);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? map(rs) : null;
      }
    }
  }

  private PaymentIntent lockTx(Connection c, UUID tenantId, UUID id) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT "
                + COLUMNS
                + " FROM payment_intents WHERE tenant_id = ? AND id = ? FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, id);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? map(rs) : null;
      }
    }
  }

  private PaymentIntent findByKeyTx(Connection c, UUID tenantId, String key) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT "
                + COLUMNS
                + " FROM payment_intents WHERE tenant_id = ? AND idempotency_key = ?")) {
      ps.setObject(1, tenantId);
      ps.setString(2, key);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? map(rs) : null;
      }
    }
  }

  private PaymentTender findTenderTx(Connection c, UUID tenantId, UUID id) throws SQLException {
    if (id == null) {
      return null;
    }
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, tenant_id, order_id, amount, method, reference, idempotency_key, status,"
                + " notes, created_at, store_id"
                + " FROM payment_tenders WHERE tenant_id = ? AND id = ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, id);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return null;
        }
        return new PaymentTender(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("order_id", UUID.class),
            rs.getBigDecimal("amount"),
            rs.getString("method"),
            rs.getString("reference"),
            rs.getString("idempotency_key"),
            rs.getString("status"),
            rs.getString("notes"),
            rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
            rs.getObject("store_id", UUID.class));
      }
    }
  }

  private static void bindAll(PreparedStatement ps, PaymentIntent i) throws SQLException {
    ps.setObject(1, i.id());
    ps.setObject(2, i.tenantId());
    ps.setObject(3, i.orderId());
    ps.setObject(4, i.storeId());
    ps.setString(5, i.provider());
    ps.setString(6, i.providerRef());
    ps.setBigDecimal(7, i.amount());
    ps.setBigDecimal(8, i.capturedAmount());
    ps.setString(9, i.currency());
    ps.setString(10, i.status());
    ps.setString(11, i.nextActionUrl());
    ps.setString(12, i.failureCode());
    ps.setString(13, i.failureMessage());
    ps.setObject(14, i.paymentId());
    ps.setString(15, i.idempotencyKey());
    ps.setObject(16, i.createdAt().atOffset(ZoneOffset.UTC));
    ps.setObject(17, i.updatedAt().atOffset(ZoneOffset.UTC));
  }

  private static PaymentIntent map(ResultSet rs) throws SQLException {
    return new PaymentIntent(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("order_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getString("provider"),
        rs.getString("provider_ref"),
        rs.getBigDecimal("amount"),
        rs.getBigDecimal("captured_amount"),
        rs.getString("currency"),
        rs.getString("status"),
        rs.getString("next_action_url"),
        rs.getString("failure_code"),
        rs.getString("failure_message"),
        rs.getObject("payment_id", UUID.class),
        rs.getString("idempotency_key"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    var v = rs.getObject(column, java.time.OffsetDateTime.class);
    return v == null ? null : v.toInstant();
  }
}
