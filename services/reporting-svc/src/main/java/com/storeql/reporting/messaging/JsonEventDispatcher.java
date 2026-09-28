package com.storeql.reporting.messaging;

import com.storeql.ids.Ids;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * The shape every dispatcher in this service shares: parse the payload, route it by topic, skip
 * what cannot be read (it never parses on redelivery either), and let a write failure propagate so
 * the consumer loop redelivers instead of losing the event. One subclass per domain keeps each
 * concern a single method; this class keeps the shape in one place instead of one copy per domain.
 */
abstract class JsonEventDispatcher {

  private final Logger log = System.getLogger(getClass().getName());
  private final String kind;

  protected JsonEventDispatcher(String kind) {
    this.kind = kind;
  }

  void dispatch(String topic, String json) {
    JsonObject obj;
    try (var reader = Json.createReader(new StringReader(json))) {
      obj = reader.readObject();
    } catch (RuntimeException e) {
      log.log(
          Level.WARNING, "Malformed {0} event on {1} skipped: {2}", kind, topic, e.getMessage());
      return;
    }
    try {
      if (!route(topic, obj)) {
        log.log(Level.WARNING, "Unknown topic {0} — ignored", topic);
      }
    } catch (RuntimeException e) {
      if (isMalformed(e)) {
        log.log(
            Level.WARNING, "Malformed {0} event on {1} skipped: {2}", kind, topic, e.getMessage());
        return;
      }
      throw e;
    }
  }

  /**
   * Routes one parsed event.
   *
   * @return false when the topic is not one this dispatcher knows
   */
  protected abstract boolean route(String topic, JsonObject obj);

  protected static UUID optUuid(JsonObject obj, String key) {
    return obj.containsKey(key) && !obj.isNull(key) ? Ids.parse(obj.getString(key)) : null;
  }

  /** An array of ids, or none when the key is absent or null. */
  protected static List<UUID> uuids(JsonObject obj, String key) {
    if (!obj.containsKey(key) || obj.isNull(key)) {
      return List.of();
    }
    return obj.getJsonArray(key).getValuesAs(JsonString.class).stream()
        .map(v -> Ids.parse(v.getString()))
        .toList();
  }

  /** When the publisher said it happened, or null when the payload does not say. */
  protected static Instant occurredAt(JsonObject obj) {
    return obj.containsKey("occurredAt") && !obj.isNull("occurredAt")
        ? Instant.parse(obj.getString("occurredAt"))
        : null;
  }

  /** A payload that lacks a field, carries the wrong type or an id that is not one. */
  static boolean isMalformed(RuntimeException e) {
    return e instanceof NullPointerException
        || e instanceof IllegalArgumentException
        || e instanceof ClassCastException
        || e instanceof DateTimeParseException
        || e instanceof jakarta.json.JsonException;
  }
}
