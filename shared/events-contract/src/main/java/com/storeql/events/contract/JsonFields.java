package com.storeql.events.contract;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.UUID;

/**
 * Appends members to the opening fragment {@code EventPayload.base} returns. Optional members that
 * are null are left out (JSON-B and every reader in the platform treat an absent member as empty),
 * required ones refuse null so a producer cannot publish half an event.
 */
@SuppressWarnings("PMD.AvoidStringBufferField") // one short-lived builder per event
final class JsonFields {

  private final StringBuilder out;

  JsonFields(String base) {
    this.out = new StringBuilder(Math.max(256, base.length() * 2)).append(base);
  }

  JsonFields str(String name, String value) {
    return put(name, quote(require(name, value)));
  }

  JsonFields optStr(String name, String value) {
    return value == null ? this : put(name, quote(value));
  }

  JsonFields uuid(String name, UUID value) {
    return put(name, quote(require(name, value).toString()));
  }

  JsonFields optUuid(String name, UUID value) {
    return value == null ? this : uuid(name, value);
  }

  JsonFields num(String name, BigDecimal value) {
    return put(name, require(name, value).toPlainString());
  }

  JsonFields optNum(String name, BigDecimal value) {
    return value == null ? this : num(name, value);
  }

  JsonFields integer(String name, long value) {
    return put(name, Long.toString(value));
  }

  JsonFields bool(String name, boolean value) {
    return put(name, Boolean.toString(value));
  }

  JsonFields date(String name, LocalDate value) {
    return put(name, quote(require(name, value).toString()));
  }

  JsonFields optDate(String name, LocalDate value) {
    return value == null ? this : date(name, value);
  }

  JsonFields instant(String name, Instant value) {
    return put(name, quote(require(name, value).toString()));
  }

  JsonFields optInstant(String name, Instant value) {
    return value == null ? this : put(name, quote(value.toString()));
  }

  JsonFields uuids(String name, Collection<UUID> values) {
    StringBuilder a = new StringBuilder("[");
    String sep = "";
    for (UUID v : require(name, values)) {
      a.append(sep).append(quote(v.toString()));
      sep = ",";
    }
    return put(name, a.append(']').toString());
  }

  /** A member whose value is already valid JSON (an object or array built by the caller). */
  JsonFields raw(String name, String json) {
    return put(name, require(name, json));
  }

  String close() {
    return out.toString() + "}";
  }

  private JsonFields put(String name, String jsonValue) {
    out.append(",\"").append(name).append("\":").append(jsonValue);
    return this;
  }

  private static <T> T require(String name, T value) {
    if (value == null) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }

  /** Quotes and escapes for JSON, including control characters. */
  static String quote(String s) {
    StringBuilder b = new StringBuilder(s.length() + 2).append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> b.append("\\\"");
        case '\\' -> b.append("\\\\");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        default -> {
          if (c < 0x20) {
            b.append(String.format("\\u%04x", (int) c));
          } else {
            b.append(c);
          }
        }
      }
    }
    return b.append('"').toString();
  }
}
