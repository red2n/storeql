package com.storeql.notification.messaging;

import com.storeql.ids.Ids;
import com.storeql.notification.channel.AccountEmailSender;
import com.storeql.notification.repo.NotificationRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Sends the password reset email: the platform's own words ({@link PasswordResetWords}), never a
 * business's — the email carries a key to a login, which a business must not be able to reword.
 * Belongs to no business: {@code tenant_id} on the log row is always null, exactly like the welcome
 * email.
 *
 * <p>Sent by SMTP alone, through a dedicated {@link AccountEmailSender} — never in-app, MQTT or
 * SMS, so the link cannot reach a feed a business's own console can read. With no email transport
 * configured, or a failed send, nothing is retried (a retry could send the same links twice): the
 * row is written NOT_SENT and the consumer moves on, exactly once per event either way.
 *
 * <p>{@code subject_id} is the shopper login's own id when the address holds one, else the first
 * login's — so a person who deletes their account finds this row erased with their others by the
 * same {@code AccountDeleted} → {@code redactForAccount} path the welcome email already uses; an
 * event whose chosen login carries no readable id simply leaves it null, and the email still goes.
 * The row is not kept forever either way: it is a platform rule, not any business's retention
 * schedule (it belongs to none), so {@link com.storeql.notification.service.RetentionPurgeService}
 * deletes it after {@code storeql.notification.password-reset.retention-days} (default 30).
 */
@ApplicationScoped
class PasswordResetRequestedHandler {

  private static final Logger LOG = System.getLogger(PasswordResetRequestedHandler.class.getName());

  /**
   * {@code tenant_id} is always null on this row, so no tenant's own notification-log read finds
   * it.
   */
  static final String TYPE = "PASSWORD_RESET";

  private static final String SENT = "SENT";
  private static final String NOT_SENT = "NOT_SENT";
  private static final String LINK_REMOVED = "[link removed]";

  /**
   * A second pass after each entry's own link is replaced: anything left that still looks like a
   * web address. The first pass is the one that matters — it does not depend on how the operator
   * wrote {@code storeql.platform.web-url}, which may lack a scheme.
   */
  private static final Pattern LINK = Pattern.compile("https?://\\S+");

  @Inject AccountEmailSender sender;
  @Inject NotificationRepository repo;

  void handle(String json) {
    UUID eventId;
    String email;
    String language;
    Instant expiresAt;
    List<PasswordResetWords.Entry> entries;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      eventId = Ids.parse(obj.getString("eventId"));
      email = obj.getString("email", null);
      language = obj.getString("language", null);
      expiresAt = Instant.parse(obj.getString("expiresAt"));
      entries = entries(obj.getJsonArray("entries"));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed PasswordResetRequested payload skipped: " + e.getMessage());
      return;
    }
    if (email == null || email.isBlank() || entries.isEmpty()) {
      return;
    }
    if (repo.alreadyNotified(eventId, TYPE)) {
      return;
    }

    PasswordResetWords.Rendered words = PasswordResetWords.render(language, expiresAt, entries);
    String status;
    if (!sender.live()) {
      LOG.log(
          Level.WARNING, "No email transport configured — password reset to {0} not sent", email);
      status = NOT_SENT;
    } else if (sender.send(email, words.subject(), words.body())) {
      status = SENT;
    } else {
      status = NOT_SENT;
    }

    repo.recordNotification(
        null, // belongs to no business
        subjectOf(entries), // the shopper login's id, or the first login's — see the class doc
        eventId,
        TYPE,
        "EMAIL",
        email,
        words.subject(),
        redact(words.body(), entries),
        status,
        words.language(),
        null);
  }

  /**
   * The shopper entry's login id when the address holds one, else the first entry's; null when that
   * entry's id was missing or unparseable. {@code entries} is never empty here — {@link #handle}
   * already returned for that.
   */
  private static UUID subjectOf(List<PasswordResetWords.Entry> entries) {
    return entries.stream()
        .filter(e -> PasswordResetWords.Entry.SHOPPER.equals(e.kind()))
        .findFirst()
        .orElse(entries.get(0))
        .userId();
  }

  private static List<PasswordResetWords.Entry> entries(JsonArray array) {
    List<PasswordResetWords.Entry> out = new ArrayList<>();
    for (var value : array) {
      JsonObject o = value.asJsonObject();
      out.add(
          new PasswordResetWords.Entry(
              o.getString("kind"),
              o.getString("businessName", null),
              o.getString("link", null),
              userIdOf(o)));
    }
    return List.copyOf(out);
  }

  /**
   * The entry's login id, or null when absent or not a valid UUID — never fails the event on it.
   */
  private static UUID userIdOf(JsonObject entry) {
    String raw = entry.getString("userId", null);
    if (raw == null) {
      return null;
    }
    try {
      return Ids.parse(raw);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * Every link replaced: the raw token must not be stored anywhere in notification-svc. Each
   * entry's link is replaced as the literal text it is, whatever its shape — a base address
   * configured without {@code https://} would slip past any pattern — and then anything still
   * shaped like a web address, in case the words ever carry one of their own.
   */
  static String redact(String body, List<PasswordResetWords.Entry> entries) {
    String out = body;
    for (PasswordResetWords.Entry e : entries) {
      if (e.link() != null && !e.link().isBlank()) {
        out = out.replace(e.link(), LINK_REMOVED);
      }
    }
    return LINK.matcher(out).replaceAll(LINK_REMOVED);
  }
}
