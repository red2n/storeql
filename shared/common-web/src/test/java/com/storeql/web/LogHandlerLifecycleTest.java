package com.storeql.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.OpenTelemetry;
import jakarta.enterprise.inject.Instance;
import java.lang.reflect.Proxy;
import java.util.logging.Filter;
import java.util.logging.Handler;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;

class LogHandlerLifecycleTest {

  private static final class Quiet extends Handler {
    @Override
    public void publish(LogRecord record) {}

    @Override
    public void flush() {}

    @Override
    public void close() {}
  }

  @Test
  void installingTheScrubberTwiceDoesNotStackFilters() {
    Handler h = new Quiet();
    Filter own = r -> true;
    h.setFilter(own);
    LogScrubber.install(h);
    Filter once = h.getFilter();
    assertNotNull(once);
    LogScrubber.install(h);
    assertSame(once, h.getFilter(), "second install is a no-op");

    Handler plain = new Quiet();
    LogScrubber.install(plain);
    LogScrubber.install(plain);
    assertSame(LogScrubber.FILTER, plain.getFilter());
  }

  @Test
  @SuppressWarnings("unchecked")
  void theBridgeTakesItsRootHandlerOffAtShutdownAndInstallsOnlyOnce() {
    Instance<OpenTelemetry> source =
        (Instance<OpenTelemetry>)
            Proxy.newProxyInstance(
                Instance.class.getClassLoader(),
                new Class<?>[] {Instance.class},
                (p, m, a) ->
                    switch (m.getName()) {
                      case "isResolvable" -> true;
                      case "get" -> OpenTelemetry.noop();
                      default -> throw new UnsupportedOperationException(m.getName());
                    });
    OtelLoggingBridge bridge = new OtelLoggingBridge();
    bridge.openTelemetry = source;
    var root = LogManager.getLogManager().getLogger("");
    int before = root.getHandlers().length;

    bridge.onStart(new Object());
    bridge.onStart(new Object());
    assertEquals(before + 1, root.getHandlers().length, "started twice, installed once");
    Handler mine = bridge.installedHandler();
    assertNotNull(mine);
    assertTrue(java.util.Arrays.asList(root.getHandlers()).contains(mine));

    bridge.onStop();
    assertEquals(before, root.getHandlers().length);
    assertFalse(java.util.Arrays.asList(root.getHandlers()).contains(mine));
    assertNull(bridge.installedHandler());
  }
}
