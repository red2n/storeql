package com.storeql.iam.service;

import com.storeql.ids.Ids;
import com.storeql.service.OutboxRow;
import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code PasswordChanged}: the one event, on {@code storeql.iam.password-changed}, that tells
 * notification-svc a login's password was changed (signed-in change or reset link), so the address
 * gets a platform-worded notice with no link. It belongs to no business (outbox tenant null), like
 * {@code PasswordResetRequested}.
 *
 * <pre>
 * { "eventType": "PasswordChanged", "eventId": "&lt;uuidv7&gt;", "email": "...",
 *   "language": null, "userId": "&lt;uuidv7&gt;", "kind": "SHOPPER" | "STAFF",
 *   "businessName": "..." | null   (STAFF only, always present for STAFF),
 *   "changedAt": "&lt;ISO-8601 UTC&gt;", "via": "CHANGE" | "RESET" }
 * </pre>
 *
 * The event carries no password, hash, token or link. Pure: {@link PasswordResetService} and {@link
 * AuthService} supply the facts.
 */
public final class PasswordChangedEvent {

  private PasswordChangedEvent() {}

  public static final String TYPE = "PasswordChanged";
  public static final String TOPIC = "storeql.iam.password-changed";
  public static final String VIA_CHANGE = "CHANGE";
  public static final String VIA_RESET = "RESET";

  /**
   * Whether a change is announced: the login has an address, and is not the platform administrator
   * (whose credentials the operator's bootstrap owns — the reset flow excludes it too).
   */
  public static boolean announces(String email, boolean platformAdmin) {
    return email != null && !email.isBlank() && !platformAdmin;
  }

  /**
   * The outbox row for one change.
   *
   * @param staff the login is a business account (one that belongs to a business, or a sign-up not
   *     yet onboarded), not a shopper's
   * @param businessName the business's name for staff; null when unknown or a shopper
   * @param via {@link #VIA_CHANGE} or {@link #VIA_RESET}
   */
  public static OutboxRow row(
      UUID userId,
      String email,
      boolean staff,
      String businessName,
      String via,
      Instant changedAt) {
    return row(userId, email, staff, businessName, via, null, changedAt);
  }

  /**
   * As above, with the language the person was using ({@code PasswordReset.language}: {@code
   * [a-z]{2,3}} or null, already validated).
   */
  public static OutboxRow row(
      UUID userId,
      String email,
      boolean staff,
      String businessName,
      String via,
      String language,
      Instant changedAt) {
    UUID eventId = Ids.newId();
    JsonObjectBuilder payload =
        Json.createObjectBuilder()
            .add("eventType", TYPE)
            .add("eventId", eventId.toString())
            .add("email", email);
    if (language == null) {
      payload.addNull("language");
    } else {
      payload.add("language", language);
    }
    payload.add("userId", userId.toString()).add("kind", staff ? "STAFF" : "SHOPPER");
    if (staff) {
      if (businessName != null) {
        payload.add("businessName", businessName);
      } else {
        payload.addNull("businessName");
      }
    }
    payload.add("changedAt", changedAt.toString()).add("via", via);
    return new OutboxRow(TYPE, TOPIC, null, eventId, payload.build().toString());
  }
}
