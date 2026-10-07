package com.storeql.order.config;

import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.spi.JsonProvider;
import java.io.Reader;

/**
 * The JSON-P entry points order-svc uses, over one provider looked up once. {@code
 * jakarta.json.Json.createObjectBuilder()} and friends resolve the provider through a service
 * loader on every call, which is wasted work on the hot paths (every priced line, every event).
 */
public final class Json {

  private static final JsonProvider PROVIDER = JsonProvider.provider();

  private Json() {}

  /** A new object builder. */
  public static JsonObjectBuilder createObjectBuilder() {
    return PROVIDER.createObjectBuilder();
  }

  /** A new array builder. */
  public static JsonArrayBuilder createArrayBuilder() {
    return PROVIDER.createArrayBuilder();
  }

  /** A reader over the text; the caller closes it. */
  public static JsonReader createReader(Reader reader) {
    return PROVIDER.createReader(reader);
  }
}
