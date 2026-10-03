package com.storeql.notification.provider;

import com.storeql.ids.Ids;
import jakarta.enterprise.context.ApplicationScoped;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Texts that go nowhere but the log — what a stack without a carrier account runs, and what the
 * tests and the k6 suites drive. Keeps the last hundred so a test can read what was "sent".
 */
@ApplicationScoped
public class SimulatedSmsProvider implements SmsProvider {

  public static final String NAME = "SIMULATED";
  private static final Logger LOG = System.getLogger(SimulatedSmsProvider.class.getName());
  private static final int KEEP = 100;

  public record Sent(String to, String body, String messageId) {}

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
  public String send(String to, String body) {
    String id = "sim-" + Ids.newId();
    // Only the in-memory list is guarded; the log line (a blocking console write) is outside it.
    synchronized (sent) {
      sent.addFirst(new Sent(to, body, id));
      while (sent.size() > KEEP) sent.removeLast();
    }
    LOG.log(Level.INFO, "[simulated SMS -> {0}] {1}", to, body);
    return id;
  }

  /** What was sent, newest first. */
  public List<Sent> sent() {
    synchronized (sent) {
      return List.copyOf(sent);
    }
  }
}
