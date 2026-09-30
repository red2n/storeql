package com.storeql.notification.messaging;

import com.storeql.ids.Ids;
import com.storeql.notification.channel.AccountEmailSender;
import com.storeql.notification.repo.NotificationRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Instant;
import java.util.UUID;

/**
 * Sends the "your password was changed" notice: the platform's own words ({@link
 * PasswordChangedWords}), to the address of the login whose password changed, by SMTP alone through
 * the same {@link AccountEmailSender} the reset email uses. It carries no link of any kind — the
 * notice must never be a way into the login. Belongs to no business ({@code tenant_id} null),
 * recorded against the login's own id so {@code AccountDeleted} erases it, and purged with the
 * reset rows after the platform's retention period. Once per event; with no transport, or a failed
 * send, the row says NOT_SENT and nothing retries.
 *
 * <p>Payload (documented in {@code PasswordChanged}, iam-svc): {@code eventId, email, language?,
 * kind (SHOPPER|STAFF), businessName? (staff only), userId, changedAt, via (CHANGE|RESET)}.
 */
@ApplicationScoped
class PasswordChangedHandler {

  private static final Logger LOG = System.getLogger(PasswordChangedHandler.class.getName());

  /** Log-row type; {@code tenant_id} is always null. */
  static final String TYPE = "PASSWORD_CHANGED";

  @Inject AccountEmailSender sender;
  @Inject NotificationRepository repo;

  void handle(String json) {
    UUID eventId;
    UUID userId;
    String email;
    String language;
    String kind;
    String businessName;
    Instant changedAt;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      eventId = Ids.parse(obj.getString("eventId"));
      userId = Ids.parse(obj.getString("userId"));
      email = obj.getString("email", null);
      language = obj.getString("language", null);
      kind = obj.getString("kind", PasswordChangedWords.SHOPPER);
      businessName = obj.getString("businessName", null);
      changedAt = Instant.parse(obj.getString("changedAt"));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed PasswordChanged payload skipped: " + e.getMessage());
      return;
    }
    if (email == null || email.isBlank()) {
      return;
    }
    if (repo.alreadyNotified(eventId, TYPE)) {
      return;
    }
    PasswordChangedWords.Rendered words =
        PasswordChangedWords.render(language, changedAt, kind, businessName);
    String status;
    if (!sender.live()) {
      LOG.log(Level.WARNING, "No email transport configured — password-changed notice not sent");
      status = "NOT_SENT";
    } else {
      status = sender.send(email, words.subject(), words.body()) ? "SENT" : "NOT_SENT";
    }
    repo.recordNotification(
        null,
        userId,
        eventId,
        TYPE,
        "EMAIL",
        email,
        words.subject(),
        words.body(),
        status,
        words.language(),
        null);
  }
}
