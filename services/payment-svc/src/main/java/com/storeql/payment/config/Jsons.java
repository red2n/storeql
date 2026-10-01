package com.storeql.payment.config;

import jakarta.json.spi.JsonProvider;

/**
 * One JSON provider for the module. The static factory methods on {@code Json} look the provider up
 * through a {@code ServiceLoader} and build a fresh one, with a fresh buffer pool, on every call;
 * the provider is thread-safe and meant to be cached, so every reader and builder here comes from
 * this one.
 */
public final class Jsons {

  /** The shared provider: {@code Jsons.PROVIDER.createReader(..)} and the builders. */
  public static final JsonProvider PROVIDER = JsonProvider.provider();

  private Jsons() {}
}
