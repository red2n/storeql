package com.storeql.notification.provider;

import io.helidon.webclient.api.HttpClientResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Reads a provider's answer with a ceiling, so a runaway body cannot fill the heap. */
final class Bodies {

  /** No provider answer this service reads is longer than this many bytes. */
  static final int MAX_BYTES = 256 * 1024;

  private Bodies() {}

  /**
   * @return the answer's text, cut at {@link #MAX_BYTES}
   */
  static String text(HttpClientResponse res) {
    try (InputStream in = res.entity().inputStream()) {
      return new String(in.readNBytes(MAX_BYTES), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
