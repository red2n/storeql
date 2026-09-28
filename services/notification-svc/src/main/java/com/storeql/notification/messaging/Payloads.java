package com.storeql.notification.messaging;

import jakarta.json.JsonObject;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** The optional parts of an event payload, read the one way every handler reads them. */
final class Payloads {

  private Payloads() {}

  /** An ISO day, or null when the field is absent or null. */
  static LocalDate day(JsonObject obj, String field) {
    return obj.containsKey(field) && !obj.isNull(field)
        ? LocalDate.parse(obj.getString(field))
        : null;
  }

  /** An ISO instant, or null when the field is absent or null (a slot's start or end). */
  static Instant instant(JsonObject obj, String field) {
    return obj.containsKey(field) && !obj.isNull(field)
        ? Instant.parse(obj.getString(field))
        : null;
  }

  /** A number, or zero when the field is absent or null. */
  static BigDecimal number(JsonObject obj, String field) {
    return obj.containsKey(field) && !obj.isNull(field)
        ? obj.getJsonNumber(field).bigDecimalValue()
        : BigDecimal.ZERO;
  }
}
