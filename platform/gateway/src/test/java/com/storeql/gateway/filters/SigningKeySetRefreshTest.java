package com.storeql.gateway.filters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.test.SigningKeysFixture;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The key-set refresh is single-flight and never parks the callers that do not run it (group-1).
 */
class SigningKeySetRefreshTest {

  private HttpServer server;
  private final AtomicInteger hits = new AtomicInteger();
  private volatile long delayMs;
  private volatile String body;

  @BeforeEach
  void startIssuer() throws Exception {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext(
        "/auth/.well-known/jwks.json",
        ex -> {
          hits.incrementAndGet();
          try {
            Thread.sleep(delayMs);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          byte[] out = body.getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.sendResponseHeaders(200, out.length);
          ex.getResponseBody().write(out);
          ex.close();
        });
    server.start();
  }

  @AfterEach
  void stopIssuer() {
    server.stop(0);
  }

  private SigningKeySet set() {
    SigningKeySet set = new SigningKeySet();
    set.iamUrl = Optional.of("http://localhost:" + server.getAddress().getPort());
    set.webClient = io.helidon.webclient.api.WebClient.builder().build();
    return set;
  }

  @Test
  void manyCallersBeforeTheFirstReadCostIssuerOneRequest() throws Exception {
    SigningKeysFixture keys = SigningKeysFixture.generate("k1");
    body = keys.jwksJson();
    delayMs = 300;
    SigningKeySet set = set();

    ExecutorService pool = Executors.newFixedThreadPool(8);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Boolean>> results = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      results.add(
          pool.submit(
              () -> {
                go.await();
                return set.key("k1").isPresent();
              }));
    }
    go.countDown();
    for (Future<Boolean> f : results) assertTrue(f.get(), "every caller got the key");
    pool.shutdownNow();

    assertEquals(1, hits.get(), "one read, shared by every caller");
  }

  @Test
  void whileOneCallerReadsTheOthersAnswerFromTheLastGoodSetAtOnce() throws Exception {
    SigningKeysFixture one = SigningKeysFixture.generate("k1");
    body = one.jwksJson();
    delayMs = 0;
    SigningKeySet set = set();
    set.minRefetch = Duration.ZERO;
    assertTrue(set.key("k1").isPresent());
    assertEquals(1, hits.get());

    // A token names a key not seen yet: one caller goes to read, and the issuer is slow.
    delayMs = 1500;
    ExecutorService pool = Executors.newFixedThreadPool(1);
    Future<Optional<?>> refresher = pool.submit(() -> set.key("rotated"));
    long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
    while (hits.get() < 2 && System.nanoTime() < deadline) Thread.sleep(10);
    assertEquals(2, hits.get(), "the refresher is in flight");

    long start = System.nanoTime();
    boolean stillKnown = set.key("k1").isPresent();
    boolean unknown = set.key("rotated").isPresent();
    long tookMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

    assertTrue(stillKnown, "the last good set still verifies");
    assertTrue(!unknown, "an unknown key is simply not found, not waited for");
    assertTrue(tookMs < 500, "callers did not queue behind the read: " + tookMs + " ms");
    assertEquals(2, hits.get(), "no second read while one runs");
    refresher.get();
    pool.shutdownNow();
  }

  @Test
  void aFailedReadKeepsTheLastGoodSet() {
    SigningKeysFixture one = SigningKeysFixture.generate("k1");
    body = one.jwksJson();
    delayMs = 0;
    SigningKeySet set = set();
    set.minRefetch = Duration.ZERO;
    assertTrue(set.key("k1").isPresent());

    body = "<html>502 Bad Gateway</html>";
    assertTrue(set.key("other").isEmpty());

    assertTrue(set.key("k1").isPresent(), "the good set survives an unreadable answer");
    assertEquals(java.util.Set.of("k1"), set.kids());
  }
}
