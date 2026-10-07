package com.storeql.web;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import java.util.logging.Filter;
import java.util.logging.Handler;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;

/**
 * Masks payment card numbers in every log record before any handler writes it.
 *
 * <p>Part of the PCI DSS scope control (see {@link CardData}): the gateway refuses a card number at
 * the door, and this catches the other way one reaches disk — a request body or an exception
 * message logged by code that never meant to log a card. Installed as the {@link Filter} on every
 * root handler at startup (the console handler from logging.properties and the OTLP bridge), which
 * is the one place in java.util.logging that sees a record after it is built and before it is
 * formatted. A record that carries no digits costs one scan and nothing else.
 */
@ApplicationScoped
public class LogScrubber {

  /** The filter itself, for handlers added after startup to install on themselves. */
  public static final Filter FILTER = LogScrubber::scrub;

  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    for (Handler h : LogManager.getLogManager().getLogger("").getHandlers()) {
      install(h);
    }
  }

  /**
   * Puts the scrubber on a handler, keeping any filter it already had.
   *
   * @param handler the handler to scrub records for
   */
  public static void install(Handler handler) {
    Filter existing = handler.getFilter();
    if (existing == null || existing == FILTER) {
      handler.setFilter(FILTER);
    } else if (!(existing instanceof Chained)) {
      // idempotent: a handler already carrying the scrubbing chain is left alone, so installing
      // twice never stacks filters
      handler.setFilter(new Chained(existing));
    }
  }

  /**
   * The handler's own filter followed by the scrubber; the type is the "already installed" mark.
   */
  private static final class Chained implements Filter {
    private final Filter existing;

    Chained(Filter existing) {
      this.existing = existing;
    }

    @Override
    public boolean isLoggable(LogRecord record) {
      return existing.isLoggable(record) && scrub(record);
    }
  }

  /** Masks the message and any string parameters in place; always lets the record through. */
  static boolean scrub(LogRecord record) {
    String msg = record.getMessage();
    if (msg != null && CardData.containsPan(msg)) {
      record.setMessage(CardData.mask(msg));
    }
    Object[] params = record.getParameters();
    if (params != null) {
      for (int i = 0; i < params.length; i++) {
        if (params[i] instanceof String s && CardData.containsPan(s)) {
          params[i] = CardData.mask(s);
        }
      }
    }
    return true;
  }
}
