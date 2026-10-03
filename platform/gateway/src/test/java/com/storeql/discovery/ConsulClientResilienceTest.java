package com.storeql.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Consul lookups are bounded, single-flight, and keep the last good list (group-9). */
class ConsulClientResilienceTest {

  private static final String ONE_INSTANCE =
      "[{\"Node\":{\"Address\":\"10.0.0.1\"},\"Service\":{\"Address\":\"10.0.0.9\",\"Port\":8080}}]";

  private HttpServer consul;
  private final AtomicInteger hits = new AtomicInteger();
  private volatile int status = 200;
  private volatile long delayMs;
  private volatile String body = ONE_INSTANCE;
  private final AtomicInteger registerHits = new AtomicInteger();

  @BeforeEach
  void startConsul() throws Exception {
    consul = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    consul.setExecutor(Executors.newCachedThreadPool());
    consul.createContext(
        "/v1/health/service/",
        ex -> {
          hits.incrementAndGet();
          try {
            Thread.sleep(delayMs);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          byte[] out = body.getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.sendResponseHeaders(status, out.length);
          ex.getResponseBody().write(out);
          ex.close();
        });
    consul.createContext(
        "/v1/agent/service/register",
        ex -> {
          registerHits.incrementAndGet();
          ex.sendResponseHeaders(200, -1);
          ex.close();
        });
    consul.start();
  }

  @AfterEach
  void stopConsul() {
    consul.stop(0);
  }

  private ConsulClient client(Duration readTimeout, Duration ttl) {
    return new ConsulClient(
        "localhost", consul.getAddress().getPort(), Duration.ofSeconds(1), readTimeout, ttl);
  }

  @Test
  void manyCallersOnACachedServiceExpiryMakeOneConsulCall() throws Exception {
    ConsulClient client = client(Duration.ofSeconds(2), Duration.ofSeconds(30));
    delayMs = 300;
    ExecutorService pool = Executors.newFixedThreadPool(8);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<List<ServiceInstance>>> results = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      results.add(
          pool.submit(
              () -> {
                go.await();
                return client.healthyInstances("svc");
              }));
    }
    go.countDown();
    for (var f : results) assertEquals(1, f.get().size());
    pool.shutdownNow();
    assertEquals(1, hits.get());
  }

  @Test
  void aFailedRefreshKeepsServingTheLastGoodList() throws Exception {
    ConsulClient client = client(Duration.ofSeconds(2), Duration.ofMillis(50));
    assertEquals(1, client.healthyInstances("svc").size());

    Thread.sleep(80);
    status = 500;
    body = "boom";
    assertEquals(1, client.healthyInstances("svc").size(), "a 500 from Consul drops nothing");

    Thread.sleep(80);
    status = 200;
    body = "[]";
    assertEquals(0, client.healthyInstances("svc").size(), "a real empty answer is believed");
  }

  @Test
  void aSlowConsulCostsARequestItsReadTimeoutNotHalfAMinute() {
    ConsulClient client = client(Duration.ofMillis(300), Duration.ofSeconds(30));
    delayMs = 4000;

    long start = System.nanoTime();
    var found = client.healthyInstances("svc");
    long tookMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

    assertTrue(found.isEmpty());
    assertTrue(tookMs < 2500, "bounded by the read timeout: " + tookMs + " ms");
  }

  @Test
  void registrationReadsItsAnswerAndClosesIt() {
    ConsulClient client = client(Duration.ofSeconds(2), Duration.ofSeconds(30));
    assertEquals("svc-8080", client.register("svc", "10.0.0.9", 8080));
    assertEquals(1, registerHits.get());
  }
}
