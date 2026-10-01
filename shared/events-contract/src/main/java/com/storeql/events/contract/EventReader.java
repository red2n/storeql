package com.storeql.events.contract;

import com.storeql.ids.Ids;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Typed access to one event payload. Unknown members are never looked at, so a producer that adds a
 * field breaks nobody; a member that is absent or JSON null reads as empty; a required member that
 * is missing, or an id that is not a version-7 UUID, throws {@link IllegalArgumentException} (a
 * consumer logs it and skips the message, as it does any malformed payload).
 */
public final class EventReader {

  private final JsonObject o;

  private EventReader(JsonObject o) {
    this.o = o;
  }

  /** Parses {@code json}, checks it is an event of {@code expectedType}, and returns its reader. */
  public static EventReader open(String json, String expectedType) {
    JsonObject obj;
    try (var r = Json.createReader(new StringReader(json))) {
      obj = r.readObject();
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("Not a JSON object payload for " + expectedType, e);
    }
    EventReader reader = new EventReader(obj);
    String type = reader.string("eventType");
    if (!expectedType.equals(type)) {
      throw new IllegalArgumentException("Expected " + expectedType + " but got " + type);
    }
    return reader;
  }

  /** The common members; refuses a payload with no tenant when the contract says it has one. */
  public Envelope envelope(TenantScope scope) {
    Optional<UUID> tenant = optUuid("tenantId");
    if (scope == TenantScope.REQUIRED && tenant.isEmpty()) {
      throw new IllegalArgumentException("tenantId is required on " + string("eventType"));
    }
    return new Envelope(
        uuid("eventId"), string("eventType"), tenant, uuid("aggregateId"), instant("occurredAt"));
  }

  public boolean has(String name) {
    return o.containsKey(name) && !o.isNull(name);
  }

  public String string(String name) {
    return optString(name).orElseThrow(() -> missing(name));
  }

  public Optional<String> optString(String name) {
    return has(name) && o.get(name) instanceof JsonString s
        ? Optional.of(s.getString())
        : Optional.empty();
  }

  public UUID uuid(String name) {
    return Ids.parse(string(name));
  }

  public Optional<UUID> optUuid(String name) {
    return optString(name).map(Ids::parse);
  }

  public BigDecimal decimal(String name) {
    return optDecimal(name).orElseThrow(() -> missing(name));
  }

  public Optional<BigDecimal> optDecimal(String name) {
    return has(name) && o.get(name) instanceof JsonNumber n
        ? Optional.of(n.bigDecimalValue())
        : Optional.empty();
  }

  public int integer(String name) {
    return optDecimal(name).orElseThrow(() -> missing(name)).intValueExact();
  }

  /** A boolean member; absent reads as {@code false}. */
  public boolean bool(String name) {
    return has(name) && o.get(name).getValueType() == JsonValue.ValueType.TRUE;
  }

  public LocalDate date(String name) {
    return LocalDate.parse(string(name));
  }

  public Optional<LocalDate> optDate(String name) {
    return optString(name).map(LocalDate::parse);
  }

  public Instant instant(String name) {
    return Instant.parse(string(name));
  }

  public Optional<Instant> optInstant(String name) {
    return optString(name).map(Instant::parse);
  }

  /** An array of ids; absent reads as an empty list. */
  public List<UUID> uuids(String name) {
    List<UUID> out = new ArrayList<>();
    for (JsonValue v : array(name)) {
      if (v instanceof JsonString s) {
        out.add(Ids.parse(s.getString()));
      }
    }
    return List.copyOf(out);
  }

  /** An array of objects; absent reads as an empty list. */
  public List<JsonObject> objects(String name) {
    List<JsonObject> out = new ArrayList<>();
    for (JsonValue v : array(name)) {
      if (v instanceof JsonObject obj) {
        out.add(obj);
      }
    }
    return List.copyOf(out);
  }

  /** An object member; absent reads as an empty object. */
  public JsonObject object(String name) {
    return has(name) && o.get(name) instanceof JsonObject obj ? obj : JsonObject.EMPTY_JSON_OBJECT;
  }

  private JsonArray array(String name) {
    return has(name) && o.get(name) instanceof JsonArray a ? a : JsonValue.EMPTY_JSON_ARRAY;
  }

  private static IllegalArgumentException missing(String name) {
    return new IllegalArgumentException(name + " is missing");
  }
}
