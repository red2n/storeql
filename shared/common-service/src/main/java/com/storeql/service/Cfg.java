package com.storeql.service;

import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Tolerant MicroProfile Config reads for the shared tuning keys (all optional, each with a default
 * in code and documented in this module's {@code microprofile-config.properties}). A missing config
 * implementation (a plain unit test) or an unparsable value falls back to the default rather than
 * failing the caller.
 */
final class Cfg {

  private Cfg() {}

  static long getLong(String key, long fallback) {
    try {
      return ConfigProvider.getConfig().getOptionalValue(key, Long.class).orElse(fallback);
    } catch (RuntimeException | LinkageError e) {
      return fallback;
    }
  }
}
