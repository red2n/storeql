package com.storeql.events;

import com.storeql.ids.Ids;
import java.time.Instant;
import java.util.UUID;

/**
 * Shared helpers for building JSON event payloads written to the outbox. Centralises the common
 * envelope fields (eventId, eventType, tenantId, aggregateId, occurredAt) so each service's {@code
 * Events} class only adds its own domain fields.
 */
public final class EventPayload {

  private EventPayload() {}

  /**
   * Returns the opening JSON fields for any domain event (without the closing brace). Callers
   * append domain-specific fields then close with {@code "}"}.
   *
   * <pre>{@code
   * return EventPayload.base("OrderPlaced", tenantId, orderId)
   *     + ",\"total\":" + total.toPlainString() + "}";
   * }</pre>
   *
   * @param eventType PascalCase past-tense event type name, e.g. {@code "OrderPlaced"}
   * @param tenantId tenant the event belongs to; written into the {@code tenantId} field verbatim
   * @param aggregateId id of the aggregate the event is about; written into {@code aggregateId}
   *     verbatim
   * @return an opening JSON object fragment (no closing brace) with {@code eventId} (freshly
   *     generated), {@code eventType}, {@code tenantId}, {@code aggregateId} and {@code occurredAt}
   *     (now, UTC)
   */
  public static String base(String eventType, UUID tenantId, UUID aggregateId) {
    return "{\"eventId\":\""
        + Ids.newId()
        + "\",\"eventType\":\""
        + eventType
        + "\",\"tenantId\":\""
        + tenantId
        + "\",\"aggregateId\":\""
        + aggregateId
        + "\",\"occurredAt\":\""
        + Instant.now()
        + "\"";
  }

  /**
   * Like {@link #base} but for an event whose tenant may be absent (a platform-scope event, or one
   * whose subject belongs to no business): the {@code tenantId} member is left out when {@code
   * tenantId} is null, never written as the text "null".
   */
  public static String baseOptionalTenant(String eventType, UUID tenantId, UUID aggregateId) {
    if (tenantId != null) {
      return base(eventType, tenantId, aggregateId);
    }
    return "{\"eventId\":\""
        + Ids.newId()
        + "\",\"eventType\":\""
        + eventType
        + "\",\"aggregateId\":\""
        + aggregateId
        + "\",\"occurredAt\":\""
        + Instant.now()
        + "\"";
  }

  /**
   * JSON-escapes a string value (backslash and double-quote only). Does not escape control
   * characters (newlines, tabs) — callers embedding free-text fields that may contain them should
   * strip or replace those separately before calling this.
   *
   * @param s the raw value to escape; may be {@code null}
   * @return {@code s} with {@code \} and {@code "} escaped, or {@code ""} if {@code s} is {@code
   *     null}
   */
  public static String esc(String s) {
    return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
