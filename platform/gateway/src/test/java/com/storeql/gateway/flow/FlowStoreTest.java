package com.storeql.gateway.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import io.lettuce.core.RedisException;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** The counters on a real Redis: the keys written, what they live for, and what is read back. */
class FlowStoreTest {

  private static final Instant NOW = Instant.parse("2026-10-07T10:15:42Z");

  private final RedisCommands<String, String> redis = TestRedis.commands();
  private final FlowStore store = new FlowStore(redis, 500, Clock.fixed(NOW, ZoneOffset.UTC));

  private static FlowRecord rec(
      String tenant, Instant at, String group, int status, String pattern) {
    return new FlowRecord(
        at,
        Ids.newId().toString(),
        "GET",
        pattern,
        group,
        status,
        null,
        tenant,
        tenant == null ? null : Ids.newId().toString(),
        3);
  }

  private static FlowRecord rec(String tenant, Instant at, String group, int status) {
    return rec(tenant, at, group, status, "/api/v1/" + group + "/things");
  }

  // ── the plan (pure) ───────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A batch is added up before it is sent: one delta per business, bucket and field")
  void planAggregates() {
    String t = Ids.newId().toString();
    FlowStore.Plan plan =
        FlowStore.plan(
            List.of(
                rec(t, NOW, "order-svc", 200),
                rec(t, NOW.plusSeconds(1), "order-svc", 201),
                rec(t, NOW.plusSeconds(2), "order-svc", 404),
                rec(t, NOW.plusSeconds(3), "order-svc", 500),
                rec(t, NOW.plusSeconds(10), "payment-svc", 200),
                rec(t, NOW.plusSeconds(120), "order-svc", 200)),
            NOW);
    long m = FlowBuckets.minuteOf(NOW);
    long h = FlowBuckets.hourOf(NOW);
    assertEquals(
        Map.of(
            "order-svc|ok",
            2L,
            "order-svc|client",
            1L,
            "order-svc|failed",
            1L,
            "payment-svc|ok",
            1L),
        plan.minutes().get(FlowBuckets.minuteKey(t, m)));
    assertEquals(Map.of("order-svc|ok", 1L), plan.minutes().get(FlowBuckets.minuteKey(t, m + 2)));
    assertEquals(
        Map.of(
            "order-svc|ok",
            3L,
            "order-svc|client",
            1L,
            "order-svc|failed",
            1L,
            "payment-svc|ok",
            1L),
        plan.hours().get(FlowBuckets.hourKey(t, h)));
    assertEquals(2, plan.minutes().size(), "the minute now and the one two on");
    assertEquals(1, plan.hours().size());
  }

  @Test
  @DisplayName(
      "Traffic with no verified business is counted under the unattributed key and never listed")
  void planKeepsUnattributedApart() {
    FlowStore.Plan plan = FlowStore.plan(List.of(rec(null, NOW, "order-svc", 401)), NOW);
    String key = FlowBuckets.minuteKey(FlowBuckets.UNATTRIBUTED, FlowBuckets.minuteOf(NOW));
    assertEquals(Map.of("order-svc|failed", 1L), plan.minutes().get(key));
    assertTrue(plan.failures().isEmpty(), "no business to show it to");
  }

  @Test
  @DisplayName(
      "Only a business's failures are listed: not its successes, not its client errors, not the screen's own reads")
  void planListsOnlyFailures() {
    String t = Ids.newId().toString();
    FlowStore.Plan plan =
        FlowStore.plan(
            List.of(
                rec(t, NOW, "order-svc", 200),
                rec(t, NOW, "order-svc", 404),
                rec(t, NOW, "order-svc", 409),
                rec(t, NOW, "order-svc", 500),
                rec(t, NOW, "order-svc", 403),
                rec(t, NOW, "system-health", 403, "/api/v1/system-health/summary")),
            NOW);
    assertEquals(1, plan.failures().size());
    assertEquals(2, plan.failures().get(FlowBuckets.failuresKey(t)).size(), "the 500 and the 403");
    assertEquals(
        Map.of(
            "order-svc|ok",
            1L,
            "order-svc|client",
            2L,
            "order-svc|failed",
            2L,
            "system-health|failed",
            1L),
        plan.hours().get(FlowBuckets.hourKey(t, FlowBuckets.hourOf(NOW))),
        "but the screen's own reads are still counted");
  }

  @Test
  @DisplayName(
      "The waiting-work read is the screen's own too: never listed as a failure, on either form; its neighbours are")
  void planDoesNotListTheWaitingWorkRead() {
    String t = Ids.newId().toString();
    String tail = "/reporting-svc/admin/reports/system-health/waiting-work";
    FlowStore.Plan plan =
        FlowStore.plan(
            List.of(
                rec(t, NOW, "reporting-svc", 403, "/api/v1" + tail),
                rec(t, NOW, "reporting-svc", 500, "/api" + tail),
                rec(t, NOW, "reporting-svc", 500, "/api/v1/reporting-svc/admin/reports/dashboard"),
                rec(t, NOW, "order-svc", 500, "/api/v1" + tail.replace("reporting", "order"))),
            NOW);
    List<FailureList.Entry> listed = plan.failures().get(FlowBuckets.failuresKey(t));
    assertEquals(2, listed.size(), "the other report's 500 and the other service's 500");
    assertEquals(
        List.of(
            "/api/v1/reporting-svc/admin/reports/dashboard",
            "/api/v1/order-svc/admin/reports/system-health/waiting-work"),
        listed.stream()
            .map(e -> FailureList.parse(e.member()).orElseThrow().routePattern())
            .toList());
  }

  @Test
  @DisplayName("A failure older than a day is not even sent")
  void planDropsStaleFailures() {
    String t = Ids.newId().toString();
    FlowStore.Plan plan =
        FlowStore.plan(List.of(rec(t, NOW.minusSeconds(24 * 3600 + 1), "order-svc", 500)), NOW);
    assertTrue(plan.failures().isEmpty());
  }

  // ── against Redis ─────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("What is written is read back, bucket by bucket, in the order the screen draws it")
  void roundTrip() {
    String t = Ids.newId().toString();
    store.write(
        List.of(
            rec(t, NOW, "order-svc", 200),
            rec(t, NOW, "order-svc", 500),
            rec(t, NOW.minusSeconds(60), "order-svc", 200),
            rec(t, NOW.minusSeconds(3600 * 3), "payment-svc", 404)));

    FlowCounters c = store.counters(t, NOW);

    assertEquals(60, c.minutes().size());
    assertEquals(24, c.hours().size());
    assertEquals(Map.of("order-svc|ok", 1L, "order-svc|failed", 1L), c.minutes().get(59));
    assertEquals(Map.of("order-svc|ok", 1L), c.minutes().get(58));
    assertEquals(Map.of(), c.minutes().get(0));
    assertEquals(Map.of("order-svc|ok", 2L, "order-svc|failed", 1L), c.hours().get(23));
    assertEquals(Map.of("payment-svc|client", 1L), c.hours().get(20), "three hours back");
  }

  @Test
  @DisplayName("Two writes add; counters are increments, not overwrites")
  void writesAccumulate() {
    String t = Ids.newId().toString();
    store.write(List.of(rec(t, NOW, "order-svc", 200)));
    store.write(List.of(rec(t, NOW, "order-svc", 200), rec(t, NOW, "order-svc", 200)));
    assertEquals(Map.of("order-svc|ok", 3L), store.counters(t, NOW).minutes().get(59));
  }

  @Test
  @DisplayName(
      "Minute counters live a day and hour counters a week, set in the same step as the count")
  void expiries() {
    String t = Ids.newId().toString();
    store.write(List.of(rec(t, NOW, "order-svc", 200)));
    long minuteTtl = redis.ttl(FlowBuckets.minuteKey(t, FlowBuckets.minuteOf(NOW)));
    long hourTtl = redis.ttl(FlowBuckets.hourKey(t, FlowBuckets.hourOf(NOW)));
    assertTrue(
        minuteTtl > 0 && minuteTtl <= FlowBuckets.MINUTE_TTL_SECONDS, "minute ttl " + minuteTtl);
    assertTrue(
        hourTtl > 3 * 24 * 3600 && hourTtl <= FlowBuckets.HOUR_TTL_SECONDS, "hour ttl " + hourTtl);
  }

  @Test
  @DisplayName("One business never reads another's counters")
  void separatePerBusiness() {
    String ours = Ids.newId().toString();
    String theirs = Ids.newId().toString();
    store.write(List.of(rec(ours, NOW, "order-svc", 200), rec(theirs, NOW, "payment-svc", 500)));
    assertEquals(Map.of("order-svc|ok", 1L), store.counters(ours, NOW).minutes().get(59));
    assertEquals(Map.of("payment-svc|failed", 1L), store.counters(theirs, NOW).minutes().get(59));
    assertEquals(Map.of(), store.counters(Ids.newId().toString(), NOW).minutes().get(59));
  }

  @Test
  @DisplayName(
      "Unattributed traffic is counted for the operator and is not what any business is given")
  void unattributedIsNotAnyonesToRead() {
    String key = FlowBuckets.minuteKey(FlowBuckets.UNATTRIBUTED, FlowBuckets.minuteOf(NOW));
    long before = total(redis.hgetall(key));
    store.write(List.of(rec(null, NOW, "order-svc", 401), rec(null, NOW, "order-svc", 401)));
    assertEquals(before + 2, total(redis.hgetall(key)));
    String t = Ids.newId().toString();
    assertEquals(Map.of(), store.counters(t, NOW).minutes().get(59));
    assertEquals(0, redis.exists(FlowBuckets.failuresKey(FlowBuckets.UNATTRIBUTED)));
  }

  private static long total(Map<String, String> hash) {
    return hash.values().stream().mapToLong(Long::parseLong).sum();
  }

  @Test
  @DisplayName(
      "Nothing but route patterns, groups and counts is ever stored: no path, id, query or body")
  void storesNoPayload() {
    String t = Ids.newId().toString();
    String secretId = Ids.newId().toString();
    String raw = "/api/v1/order-svc/customers/" + secretId + "/ann@example.com?token=SECRET123";
    store.write(
        List.of(
            new FlowRecord(
                NOW,
                Ids.newId().toString(),
                "POST",
                RoutePattern.of(raw, java.util.Set.of("order-svc")).pattern(),
                "order-svc",
                500,
                "INTERNAL",
                t,
                Ids.newId().toString(),
                9)));
    for (String key : redis.keys("flow:*" + t + "*")) {
      String dump =
          switch (redis.type(key)) {
            case "hash" -> redis.hgetall(key).toString();
            case "zset" -> redis.zrange(key, 0, -1).toString();
            default -> "";
          };
      assertFalse(dump.contains(secretId), key + " holds a raw id");
      assertFalse(dump.contains("example.com"), key + " holds an email");
      assertFalse(dump.contains("SECRET123"), key + " holds a query value");
      assertFalse(dump.contains("?"), key + " holds a query string");
    }
  }

  @Test
  @DisplayName(
      "When Redis cannot be reached a write or a read throws, for the caller to treat as 'unavailable'")
  void redisDownThrows() {
    RedisException refused = new RedisException("Currently not connected. Commands are rejected.");
    @SuppressWarnings("unchecked")
    RedisCommands<String, String> broken =
        Mockito.mock(
            RedisCommands.class,
            invocation -> {
              throw refused;
            });
    FlowStore down = new FlowStore(broken, 500, Clock.fixed(NOW, ZoneOffset.UTC));
    assertThrows(
        RuntimeException.class, () -> down.write(List.of(rec("t", NOW, "order-svc", 200))));
    assertThrows(RuntimeException.class, () -> down.counters(Ids.newId().toString(), NOW));
    assertThrows(
        RuntimeException.class, () -> down.failures(Ids.newId().toString(), NOW, 20, null));
  }

  @Test
  @DisplayName("An empty batch is no work and no round trip")
  void emptyBatch() {
    @SuppressWarnings("unchecked")
    RedisCommands<String, String> untouched = Mockito.mock(RedisCommands.class);
    new FlowStore(untouched, 500, Clock.fixed(NOW, ZoneOffset.UTC)).write(List.of());
    Mockito.verifyNoInteractions(untouched);
  }
}
