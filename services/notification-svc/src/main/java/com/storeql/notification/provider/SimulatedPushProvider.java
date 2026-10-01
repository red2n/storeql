package com.storeql.notification.provider;

import com.storeql.ids.Ids;
import jakarta.enterprise.context.ApplicationScoped;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Pushes that go nowhere but the log. A token that starts with {@code gone-} is answered as a
 * device the provider no longer knows, so the forget-the-device path can be driven end to end.
 */
@ApplicationScoped
public class SimulatedPushProvider implements PushProvider {

  public static final String NAME = "SIMULATED";
  private static final Logger LOG = System.getLogger(SimulatedPushProvider.class.getName());
  private static final int KEEP = 100;

  /** {@code data} is the payload rendered as text, so the record holds nothing mutable. */
  public record Sent(String token, String title, String body, String data) {}

  private final Deque<Sent> sent = new ArrayDeque<>();

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean isConfigured() {
    return true;
  }

  @Override
  public String send(String token, String title, String body, Map<String, String> data) {
    if (token.startsWith("gone-")) {
      throw new ProviderException(
          ProviderException.UNREGISTERED, "the device is no longer registered", false);
    }
    Sent entry = new Sent(token, title, body, new java.util.TreeMap<>(data).toString());
    // Only the in-memory list is guarded; the log line (a blocking console write) is outside it.
    synchronized (sent) {
      sent.addFirst(entry);
      while (sent.size() > KEEP) sent.removeLast();
    }
    LOG.log(Level.INFO, "[simulated push -> {0}] {1}", token, title);
    return "sim-" + Ids.newId();
  }

  public List<Sent> sent() {
    synchronized (sent) {
      return List.copyOf(sent);
    }
  }
}
