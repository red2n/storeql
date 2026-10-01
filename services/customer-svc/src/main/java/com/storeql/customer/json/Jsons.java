package com.storeql.customer.json;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonBuilderFactory;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.JsonReaderFactory;
import jakarta.json.JsonWriter;
import jakarta.json.JsonWriterFactory;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.util.Map;

/**
 * JSON-P factories made once. {@code Json.createReader(...)} and friends look the provider up
 * through a ServiceLoader and get a provider with a fresh buffer pool on every call; the factories
 * are thread-safe and keep their buffers, so the hot paths (every consumed event, every request
 * that builds JSON) use these instead.
 */
public final class Jsons {

  private static final JsonReaderFactory READERS = Json.createReaderFactory(Map.of());
  private static final JsonBuilderFactory BUILDERS = Json.createBuilderFactory(Map.of());
  private static final JsonWriterFactory WRITERS = Json.createWriterFactory(Map.of());

  private Jsons() {}

  /**
   * @return a reader over {@code in}
   */
  public static JsonReader reader(Reader in) {
    return READERS.createReader(in);
  }

  /**
   * @return a reader over {@code in}
   */
  public static JsonReader reader(InputStream in) {
    return READERS.createReader(in);
  }

  /**
   * @return a fresh object builder
   */
  public static JsonObjectBuilder object() {
    return BUILDERS.createObjectBuilder();
  }

  /**
   * @return a fresh array builder
   */
  public static JsonArrayBuilder array() {
    return BUILDERS.createArrayBuilder();
  }

  /**
   * @return a writer onto {@code out}
   */
  public static JsonWriter writer(OutputStream out) {
    return WRITERS.createWriter(out);
  }

  /**
   * @return a writer onto {@code out}
   */
  public static JsonWriter writer(java.io.Writer out) {
    return WRITERS.createWriter(out);
  }
}
