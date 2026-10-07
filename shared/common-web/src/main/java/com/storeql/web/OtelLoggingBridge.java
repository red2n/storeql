package com.storeql.web;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.LoggerProvider;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.context.Context;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.System.Logger.Level;
import java.text.MessageFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Handler;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;

/**
 * Bridges every {@code java.util.logging} record into the OpenTelemetry Logs API so services ship
 * structured, trace-correlated logs via OTLP instead of a separate log-scraping pipeline — one
 * shipping path, not two.
 *
 * <p>Reads the {@link OpenTelemetry} CDI bean that {@code helidon-microprofile-telemetry} produces.
 * Modules that depend on {@code common-web} without that telemetry module present skip installation
 * (see {@link #onStart}) instead of failing deployment.
 */
@ApplicationScoped
public class OtelLoggingBridge {

  private static final java.lang.System.Logger LOG =
      java.lang.System.getLogger(OtelLoggingBridge.class.getName());

  @Inject Instance<OpenTelemetry> openTelemetry;

  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    if (!openTelemetry.isResolvable()) {
      LOG.log(Level.DEBUG, "No OpenTelemetry bean available; OTLP log export disabled");
      return;
    }
    if (installed != null) return;
    Handler handler = new OtelHandler(openTelemetry.get());
    // Scrubbed like the console handler: a card number must not reach Loki either.
    LogScrubber.install(handler);
    LogManager.getLogManager().getLogger("").addHandler(handler);
    installed = handler;
  }

  /** The root handler this bean added, so shutdown can take it off again. */
  private volatile Handler installed;

  /** Removes the root handler on shutdown (a redeploy must not stack handlers on the JVM root). */
  @PreDestroy
  void onStop() {
    Handler handler = installed;
    if (handler == null) return;
    LogManager.getLogManager().getLogger("").removeHandler(handler);
    installed = null;
  }

  /** For tests: the handler this bean installed, or null. */
  Handler installedHandler() {
    return installed;
  }

  private static final class OtelHandler extends Handler {

    private final LoggerProvider loggerProvider;
    private final Map<String, Logger> loggers = new ConcurrentHashMap<>();

    OtelHandler(OpenTelemetry openTelemetry) {
      this.loggerProvider = openTelemetry.getLogsBridge();
      setLevel(java.util.logging.Level.ALL);
    }

    @Override
    public void publish(LogRecord record) {
      if (!isLoggable(record)) {
        return;
      }
      String loggerName = record.getLoggerName() == null ? "unknown" : record.getLoggerName();
      // The OTLP exporter's own gRPC/HTTP stack logs through JUL too; forwarding those would
      // risk a feedback loop when the collector is unreachable and every export retry logs.
      if (loggerName.startsWith("io.opentelemetry") || loggerName.startsWith("io.grpc")) {
        return;
      }

      Logger logger = loggers.computeIfAbsent(loggerName, loggerProvider::get);
      var builder =
          logger
              .logRecordBuilder()
              .setTimestamp(record.getInstant())
              .setContext(Context.current())
              .setSeverity(toSeverity(record.getLevel()))
              .setSeverityText(record.getLevel().getName())
              .setBody(formatMessage(record))
              .setAttribute(
                  AttributeKey.stringKey("thread.name"), Thread.currentThread().getName());
      if (record.getSourceClassName() != null) {
        builder.setAttribute(AttributeKey.stringKey("code.namespace"), record.getSourceClassName());
      }
      if (record.getSourceMethodName() != null) {
        builder.setAttribute(AttributeKey.stringKey("code.function"), record.getSourceMethodName());
      }
      Throwable thrown = record.getThrown();
      if (thrown != null) {
        builder.setAttribute(AttributeKey.stringKey("exception.type"), thrown.getClass().getName());
        builder.setAttribute(
            AttributeKey.stringKey("exception.message"), String.valueOf(thrown.getMessage()));
        StringWriter sw = new StringWriter();
        thrown.printStackTrace(new PrintWriter(sw));
        builder.setAttribute(AttributeKey.stringKey("exception.stacktrace"), sw.toString());
      }
      builder.emit();
    }

    @Override
    public void flush() {
      // No-op: the SDK's own BatchLogRecordProcessor owns buffering/flushing/export timing.
    }

    @Override
    public void close() {
      // No-op: SDK shutdown is driven by the OpenTelemetry SDK lifecycle, not this handler.
    }

    private static Severity toSeverity(java.util.logging.Level level) {
      int v = level.intValue();
      if (v >= java.util.logging.Level.SEVERE.intValue()) {
        return Severity.ERROR;
      }
      if (v >= java.util.logging.Level.WARNING.intValue()) {
        return Severity.WARN;
      }
      if (v >= java.util.logging.Level.INFO.intValue()) {
        return Severity.INFO;
      }
      if (v >= java.util.logging.Level.CONFIG.intValue()) {
        return Severity.DEBUG;
      }
      if (v >= java.util.logging.Level.FINE.intValue()) {
        return Severity.DEBUG2;
      }
      if (v >= java.util.logging.Level.FINER.intValue()) {
        return Severity.DEBUG3;
      }
      return Severity.TRACE;
    }

    private static String formatMessage(LogRecord record) {
      String msg = record.getMessage();
      if (msg == null) {
        return "";
      }
      Object[] params = record.getParameters();
      if (params == null || params.length == 0) {
        return msg;
      }
      try {
        return MessageFormat.format(msg, params);
      } catch (IllegalArgumentException e) {
        return msg;
      }
    }
  }
}
