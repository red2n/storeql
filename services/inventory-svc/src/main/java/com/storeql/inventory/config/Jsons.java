package com.storeql.inventory.config;

import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.spi.JsonProvider;
import java.io.InputStream;
import java.io.Reader;

/**
 * One JSON-P provider for the service. {@code Jsons.createReader(..)} looks the provider up through
 * a service-loader scan on every call; on a hot consumer path that is a scan of the whole class
 * path per event. The provider is found once, here.
 */
public final class Jsons {

  private static final JsonProvider PROVIDER = JsonProvider.provider();

  private Jsons() {}

  /** A reader over text; close it. */
  public static JsonReader createReader(Reader reader) {
    return PROVIDER.createReader(reader);
  }

  /** A reader over UTF-8 (or detected) bytes; close it. */
  public static JsonReader createReader(InputStream in) {
    return PROVIDER.createReader(in);
  }

  /** A new object builder. */
  public static JsonObjectBuilder createObjectBuilder() {
    return PROVIDER.createObjectBuilder();
  }

  /** A new array builder. */
  public static JsonArrayBuilder createArrayBuilder() {
    return PROVIDER.createArrayBuilder();
  }
}
