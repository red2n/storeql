package com.storeql.gateway.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeql.gateway.GatewayConfig;
import com.storeql.ids.Ids;
import com.storeql.web.ApiResponse;
import com.storeql.web.ErrorBody;
import com.storeql.web.Problem;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.StreamingOutput;
import jakarta.ws.rs.core.UriInfo;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The recording pipeline up to the store: what the response filter puts in a record, and how the
 * queue and the drainer treat it. The store's own behaviour against a real Redis is {@code
 * FlowStoreTest}; the whole thing through the real filter chain is {@code FlowRecordingIT}.
 */
class GatewayFlowRecorderTest {

  private static final Instant NOW = Instant.parse("2026-10-07T10:15:42.250Z");
  private static final String TENANT = Ids.newId().toString();
  private static final String USER = Ids.newId().toString();

  // ── a sink that remembers, can be told to fail, and can be held ───────────────────────────

  private static final class Sink implements FlowRecorder.Sink {
    final List<List<FlowRecord>> batches = Collections.synchronizedList(new ArrayList<>());
    final AtomicBoolean failing = new AtomicBoolean();
    final AtomicInteger calls = new AtomicInteger();
    volatile CountDownLatch hold;

    @Override
    public void write(List<FlowRecord> batch) {
      calls.incrementAndGet();
      CountDownLatch h = hold;
      if (h != null) {
        try {
          h.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      if (failing.get()) throw new IllegalStateException("redis is down");
      batches.add(List.copyOf(batch));
    }

    List<FlowRecord> written() {
      synchronized (batches) {
        return batches.stream().flatMap(List::stream).toList();
      }
    }

    Set<String> writtenIds() {
      return new HashSet<>(written().stream().map(FlowRecord::requestId).toList());
    }
  }

  private static FlowRecorder.Settings settings(int queue, int batch) {
    return new FlowRecorder.Settings(queue, batch, 1000, 8000);
  }

  private static FlowRecord rec(String id, int status) {
    return new FlowRecord(
        NOW, id, "GET", "/api/v1/order-svc/orders", "order-svc", status, null, TENANT, USER, 5);
  }

  // ── the queue ─────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "fullQueueDropsSuccesses: past its size a queue drops a success, counts it, and keeps what it had")
  void fullQueueDropsSuccesses() {
    Sink sink = new Sink();
    FlowRecorder recorder = new FlowRecorder(sink, settings(3, 100), System::nanoTime);
    for (int i = 1; i <= 5; i++) recorder.record(rec("ok" + i, 200));
    assertEquals(2, recorder.droppedSinceStart(), "the 4th and 5th success found no room");
    recorder.flush();
    assertEquals(Set.of("ok1", "ok2", "ok3"), sink.writtenIds());
  }

  @Test
  @DisplayName("A client error (a 404, a 409) is as droppable as a success: the system worked")
  void clientErrorsAreDroppableToo() {
    FlowRecorder recorder = new FlowRecorder(new Sink(), settings(1, 100), System::nanoTime);
    recorder.record(rec("a", 200));
    recorder.record(rec("b", 404));
    assertEquals(1, recorder.droppedSinceStart());
  }

  @Test
  @DisplayName(
      "A failure is never dropped before it is queued while a success can make room: it evicts the oldest")
  void aFailureEvictsTheOldestSuccess() {
    Sink sink = new Sink();
    FlowRecorder recorder = new FlowRecorder(sink, settings(3, 100), System::nanoTime);
    recorder.record(rec("ok1", 200));
    recorder.record(rec("ok2", 200));
    recorder.record(rec("ok3", 200));
    recorder.record(rec("bad1", 500));
    recorder.record(rec("bad2", 403));
    assertEquals(2, recorder.droppedSinceStart(), "two successes were given up for two failures");
    recorder.flush();
    assertEquals(Set.of("ok3", "bad1", "bad2"), sink.writtenIds());
  }

  @Test
  @DisplayName(
      "A queue holding nothing but failures cannot grow: memory wins and the extra failure is counted")
  void aQueueOfFailuresStaysBounded() {
    Sink sink = new Sink();
    FlowRecorder recorder = new FlowRecorder(sink, settings(2, 100), System::nanoTime);
    recorder.record(rec("bad1", 500));
    recorder.record(rec("bad2", 500));
    recorder.record(rec("bad3", 500));
    assertEquals(1, recorder.droppedSinceStart());
    recorder.flush();
    assertEquals(Set.of("bad1", "bad2"), sink.writtenIds());
  }

  @Test
  @DisplayName(
      "Recording never blocks, even with the store stuck and thousands of requests in a hurry")
  void recordingNeverBlocks() throws Exception {
    Sink sink = new Sink();
    sink.hold = new CountDownLatch(1); // the store never answers
    FlowRecorder recorder = new FlowRecorder(sink, settings(100, 10), System::nanoTime);
    int threads = 8;
    int each = 5_000;
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      assertTimeoutPreemptively(
          Duration.ofSeconds(10),
          () -> {
            List<Future<?>> done = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
              int base = t;
              done.add(
                  pool.submit(
                      () -> {
                        for (int i = 0; i < each; i++) {
                          recorder.record(rec("r" + base + "-" + i, i % 50 == 0 ? 500 : 200));
                        }
                      }));
            }
            for (Future<?> f : done) f.get();
          });
    }
    sink.hold.countDown();
    recorder.flush();
    long produced = (long) threads * each;
    assertEquals(
        produced,
        sink.written().size() + recorder.droppedSinceStart(),
        "every record was either written or counted as dropped, none lost silently");
    assertTrue(recorder.droppedSinceStart() > 0, "the queue held 100, so most were dropped");
  }

  // ── the drainer ───────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("The drainer writes in batches no bigger than the batch size")
  void batchesAreBounded() {
    Sink sink = new Sink();
    FlowRecorder recorder = new FlowRecorder(sink, settings(100, 2), System::nanoTime);
    for (int i = 1; i <= 5; i++) recorder.record(rec("r" + i, 200));
    recorder.flush();
    assertEquals(List.of(2, 2, 1), sink.batches.stream().map(List::size).toList());
  }

  @Test
  @DisplayName("The background thread writes on its own, and stopping it ends the thread")
  void theBackgroundThreadDrains() throws Exception {
    Sink sink = new Sink();
    FlowRecorder recorder =
        new FlowRecorder(sink, new FlowRecorder.Settings(100, 10, 5, 100), System::nanoTime);
    recorder.start();
    try {
      recorder.record(rec("a", 200));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (sink.written().isEmpty() && System.nanoTime() < deadline) Thread.sleep(5);
      assertEquals(Set.of("a"), sink.writtenIds());
    } finally {
      recorder.stop();
    }
    assertFalse(recorder.running());
    recorder.record(rec("after-stop", 200));
    recorder.stop();
  }

  @Test
  @DisplayName(
      "Redis down: records are discarded and counted, the requests are unaffected, and the drainer backs off")
  void redisDownBacksOff() {
    Sink sink = new Sink();
    AtomicLong clock = new AtomicLong(1_000_000_000_000L);
    FlowRecorder recorder = new FlowRecorder(sink, settings(100, 10), clock::get);
    sink.failing.set(true);

    for (int i = 0; i < 3; i++) recorder.record(rec("a" + i, 200));
    recorder.flush(); // tries, fails, gives the batch up
    assertEquals(1, sink.calls.get());
    assertEquals(3, recorder.droppedSinceStart());

    for (int i = 0; i < 2; i++) recorder.record(rec("b" + i, 200));
    recorder.flush(); // still backing off: nothing is tried, the records are let go
    assertEquals(1, sink.calls.get(), "no attempt inside the back-off");
    assertEquals(5, recorder.droppedSinceStart());

    clock.addAndGet(Duration.ofSeconds(2).toNanos()); // one failure: flush interval * 2
    recorder.record(rec("c", 200));
    recorder.flush(); // the probe, which fails again and doubles the wait
    assertEquals(2, sink.calls.get());
    assertEquals(6, recorder.droppedSinceStart());

    clock.addAndGet(Duration.ofSeconds(3).toNanos()); // two failures: 4 s, so 3 s is not enough
    recorder.record(rec("d", 200));
    recorder.flush();
    assertEquals(2, sink.calls.get(), "the wait doubled");

    sink.failing.set(false);
    clock.addAndGet(Duration.ofSeconds(2).toNanos());
    recorder.record(rec("e", 200));
    recorder.flush();
    assertEquals(Set.of("e"), sink.writtenIds(), "Redis is back: the next record is written");

    recorder.record(rec("f", 200));
    recorder.flush();
    assertEquals(Set.of("e", "f"), sink.writtenIds(), "and the wait is reset");
  }

  // ── what goes into a record ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A record has no field that could hold a body, a query string or a raw path")
  void recordsNoPayloadByConstruction() {
    List<String> names =
        Arrays.stream(FlowRecord.class.getRecordComponents())
            .map(RecordComponent::getName)
            .toList();
    assertEquals(
        List.of(
            "at",
            "requestId",
            "method",
            "routePattern",
            "group",
            "status",
            "code",
            "tenantId",
            "userId",
            "ms"),
        names);
  }

  /** A response filter on a recorder that remembers, over a request the test dresses. */
  private static final class Rig {
    final Sink sink = new Sink();
    final FlowRecorder recorder = new FlowRecorder(sink, settings(100, 100), System::nanoTime);
    final GatewayConfig config = mock(GatewayConfig.class);
    final FlowRecordingFilter filter = new FlowRecordingFilter();
    final ContainerRequestContext req = mock(ContainerRequestContext.class);
    final ContainerResponseContext res = mock(ContainerResponseContext.class);
    final UriInfo uri = mock(UriInfo.class);
    final MultivaluedMap<String, Object> responseHeaders = new MultivaluedHashMap<>();

    Rig() {
      when(config.flowEnabled()).thenReturn(true);
      when(config.routableServices()).thenReturn(Set.of("order-svc", "payment-svc"));
      filter.config = config;
      filter.recorder = recorder;
      filter.clock = Clock.fixed(NOW, ZoneOffset.UTC);
      filter.nanos = () -> 1_042_500_000L;
      lenient().when(req.getUriInfo()).thenReturn(uri);
      lenient().when(req.getMethod()).thenReturn("GET");
      lenient().when(uri.getPath()).thenReturn("api/v1/order-svc/orders");
      lenient().when(req.getProperty(FlowAttributes.REQUEST_ID)).thenReturn("rid-1");
      lenient().when(req.getProperty(FlowAttributes.STARTED_NANOS)).thenReturn(1_000_000_000L);
      lenient().when(res.getHeaders()).thenReturn(responseHeaders);
      lenient().when(res.getStatus()).thenReturn(200);
    }

    Rig tenant(String tenant, String user) {
      lenient().when(req.getProperty(FlowAttributes.TENANT_ID)).thenReturn(tenant);
      lenient().when(req.getProperty(FlowAttributes.USER_ID)).thenReturn(user);
      return this;
    }

    FlowRecord run() throws Exception {
      filter.filter(req, res);
      recorder.flush();
      List<FlowRecord> got = sink.written();
      assertEquals(1, got.size());
      return got.get(0);
    }
  }

  @Test
  @DisplayName(
      "The record carries what the request was and how it ended, and the business and caller the filter verified")
  void theRecordIsComplete() throws Exception {
    Rig rig = new Rig().tenant(TENANT, USER);
    when(rig.req.getMethod()).thenReturn("POST");
    when(rig.uri.getPath()).thenReturn("api/v1/order-svc/orders/" + Ids.newId() + "/lines");
    when(rig.res.getStatus()).thenReturn(409);
    when(rig.res.getEntity()).thenReturn(ApiResponse.error(ErrorBody.of("ORDER_CLOSED", "closed")));

    FlowRecord r = rig.run();

    assertEquals(NOW, r.at());
    assertEquals("rid-1", r.requestId());
    assertEquals("POST", r.method());
    assertEquals("/api/v1/order-svc/orders/{id}/lines", r.routePattern());
    assertEquals("order-svc", r.group());
    assertEquals(409, r.status());
    assertEquals("ORDER_CLOSED", r.code());
    assertEquals(TENANT, r.tenantId());
    assertEquals(USER, r.userId());
    assertEquals(42, r.ms());
  }

  @Test
  @DisplayName("The query string and the raw address are never read, so they cannot be stored")
  void queryStringsAreNeverRead() throws Exception {
    Rig rig = new Rig().tenant(TENANT, USER);
    when(rig.uri.getPath()).thenReturn("api/v1/order-svc/customers/ann@example.com");
    FlowRecord r = rig.run();
    assertEquals("/api/v1/order-svc/customers/{id}", r.routePattern());
    verify(rig.uri, never()).getRequestUri();
    verify(rig.uri, never()).getAbsolutePath();
    verify(rig.uri, never()).getQueryParameters();
    verify(rig.req, never()).getEntityStream();
    verify(rig.req, never()).hasEntity();
  }

  @Test
  @DisplayName(
      "The business comes from what JwtAuthFilter verified, never from a header the client wrote")
  void aSpoofedHeaderIsNotAnIdentity() throws Exception {
    Rig rig = new Rig(); // no verified tenant
    String victim = Ids.newId().toString();
    lenient().when(rig.req.getHeaderString("X-Tenant-Id")).thenReturn(victim);
    lenient().when(rig.req.getHeaderString("X-User-Id")).thenReturn(victim);
    lenient().when(rig.req.getHeaderString("X-Storefront-Tenant")).thenReturn(victim);
    FlowRecord r = rig.run();
    assertNull(r.tenantId(), "no verified business: the request is nobody's to show");
    assertNull(r.userId());
  }

  @Test
  @DisplayName("A tenant or user that is not a UUIDv7 names nobody, so it is not attributed")
  void onlyRealIdsAreAttributed() throws Exception {
    FlowRecord r = new Rig().tenant("not-an-id", "1-1-1-1-1").run();
    assertNull(r.tenantId());
    assertNull(r.userId());
    FlowRecord upper = new Rig().tenant(TENANT.toUpperCase(java.util.Locale.ROOT), USER).run();
    assertEquals(TENANT, upper.tenantId(), "kept in the one canonical, lowercase form");
  }

  @Test
  @DisplayName(
      "The error code is read from an error we built, whichever shape it has, and from nothing else")
  void codeSources() throws Exception {
    Rig envelope = new Rig().tenant(TENANT, USER);
    when(envelope.res.getEntity()).thenReturn(ApiResponse.error(ErrorBody.of("RATE_LIMITED", "x")));
    assertEquals("RATE_LIMITED", envelope.run().code());

    Rig problem = new Rig().tenant(TENANT, USER);
    when(problem.res.getEntity())
        .thenReturn(
            new Problem(
                "urn:storeql:problem:UNAUTHORIZED",
                "Unauthorized",
                401,
                "d",
                "/x",
                "UNAUTHORIZED",
                List.of(),
                "rid",
                ErrorBody.of("UNAUTHORIZED", "d"),
                null));
    assertEquals("UNAUTHORIZED", problem.run().code());

    Rig bare = new Rig().tenant(TENANT, USER);
    when(bare.res.getEntity()).thenReturn(ErrorBody.of("PAYLOAD_TOO_LARGE", "x"));
    assertEquals("PAYLOAD_TOO_LARGE", bare.run().code());

    Rig success = new Rig().tenant(TENANT, USER);
    when(success.res.getEntity()).thenReturn(ApiResponse.ok("data"));
    assertNull(success.run().code());

    Rig relayed = new Rig().tenant(TENANT, USER);
    StreamingOutput upstream = out -> out.write("{\"error\":{\"code\":\"NOT_READ\"}}".getBytes());
    when(relayed.res.getEntity()).thenReturn(upstream);
    when(relayed.res.getStatus()).thenReturn(500);
    assertNull(relayed.run().code(), "a relayed body is never buffered or parsed");
  }

  @Test
  @DisplayName(
      "A relayed answer's code is taken from X-Error-Code if the service sent one, and only if it is a code")
  void codeFromTheRelayedHeader() throws Exception {
    Rig rig = new Rig().tenant(TENANT, USER);
    when(rig.res.getStatus()).thenReturn(503);
    rig.responseHeaders.putSingle("X-Error-Code", "INVENTORY_BUSY");
    assertEquals("INVENTORY_BUSY", rig.run().code());

    for (String junk :
        new String[] {"drop table x; --", "lower_case", "", " ", "X".repeat(65), "A B"}) {
      Rig bad = new Rig().tenant(TENANT, USER);
      bad.responseHeaders.putSingle("X-Error-Code", junk);
      assertNull(bad.run().code(), junk);
    }
  }

  @Test
  @DisplayName("An unknown method is recorded as OTHER, an unknown group as 'other'")
  void unknownThingsCollapse() throws Exception {
    Rig rig = new Rig().tenant(TENANT, USER);
    when(rig.req.getMethod()).thenReturn("BREW");
    when(rig.uri.getPath()).thenReturn("api/v1/" + Ids.newId() + "/x");
    FlowRecord r = rig.run();
    assertEquals("OTHER", r.method());
    assertEquals("other", r.group());
  }

  @Test
  @DisplayName("Every answer carries the request id, set by this filter if nothing earlier did")
  void theAnswerCarriesTheId() throws Exception {
    Rig rig = new Rig();
    rig.filter.filter(rig.req, rig.res);
    assertEquals("rid-1", rig.responseHeaders.getFirst("X-Request-Id"));

    Rig already = new Rig();
    already.responseHeaders.putSingle("X-Request-Id", "from-relay");
    already.filter.filter(already.req, already.res);
    assertEquals("from-relay", already.responseHeaders.getFirst("X-Request-Id"));
  }

  @Test
  @DisplayName("A request the id filter never saw still gets an id, and is recorded")
  void aMissingIdIsMinted() throws Exception {
    Rig rig = new Rig();
    when(rig.req.getProperty(FlowAttributes.REQUEST_ID)).thenReturn(null);
    when(rig.req.getProperty(FlowAttributes.STARTED_NANOS)).thenReturn(null);
    FlowRecord r = rig.run();
    assertTrue(Ids.isV7(Ids.parse(r.requestId())));
    assertEquals(r.requestId(), rig.responseHeaders.getFirst("X-Request-Id"));
    assertEquals(0, r.ms(), "no start time, no duration to invent");
  }

  @Test
  @DisplayName("Switched off, the filter still gives the id and records nothing")
  void switchedOff() throws Exception {
    Rig rig = new Rig();
    when(rig.config.flowEnabled()).thenReturn(false);
    rig.filter.filter(rig.req, rig.res);
    rig.recorder.flush();
    assertEquals("rid-1", rig.responseHeaders.getFirst("X-Request-Id"));
    assertTrue(rig.sink.written().isEmpty());
  }

  @Test
  @DisplayName("A recorder that fails must not fail the request")
  void aBrokenRecorderIsNotTheRequestsProblem() throws Exception {
    Rig rig = new Rig().tenant(TENANT, USER);
    when(rig.res.getEntity()).thenThrow(new IllegalStateException("boom"));
    rig.filter.filter(rig.req, rig.res); // does not throw
    assertEquals("rid-1", rig.responseHeaders.getFirst("X-Request-Id"));
  }
}
