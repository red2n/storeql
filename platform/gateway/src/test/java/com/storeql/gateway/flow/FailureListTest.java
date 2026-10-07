package com.storeql.gateway.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.gateway.flow.SystemHealthDtos.FailureItem;
import com.storeql.ids.Ids;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.json.Json;
import java.io.StringReader;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A business's failures: kept in a sorted set, newest first, capped, and gone after twenty-four
 * hours — judged on a real Redis, with a clock the test moves.
 */
class FailureListTest {

  private static final Instant T0 = Instant.parse("2026-10-07T08:00:00Z");

  /** A clock the test moves. */
  private static final class Moving extends Clock {
    private volatile Instant now;

    Moving(Instant start) {
      this.now = start;
    }

    void to(Instant t) {
      now = t;
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private final RedisCommands<String, String> redis = TestRedis.commands();
  private final Moving clock = new Moving(T0);

  private FlowStore store(int cap) {
    return new FlowStore(redis, cap, clock);
  }

  private static FlowRecord failure(String tenant, Instant at, String id, int status) {
    return new FlowRecord(
        at,
        id,
        "POST",
        "/api/v1/payment-svc/payments/{id}/capture",
        "payment-svc",
        status,
        "PAYMENT_DECLINED",
        tenant,
        Ids.newId().toString(),
        87);
  }

  private static List<String> ids(FlowStore.FailureSlice slice) {
    return slice.items().stream().map(FailureItem::requestId).toList();
  }

  @Test
  @DisplayName("expiresAfter24Hours: a failure is listed for a day, then gone — and so is the key")
  void expiresAfter24Hours() {
    String tenant = Ids.newId().toString();
    FlowStore store = store(500);
    store.write(List.of(failure(tenant, T0, "old", 500)));

    long ttl = redis.ttl(FlowBuckets.failuresKey(tenant));
    assertTrue(ttl > 0 && ttl <= 24 * 3600, "the key expires within a day of the write: " + ttl);

    assertEquals(List.of("old"), ids(store.failures(tenant, T0, 20, null)));
    Instant almost = T0.plus(Duration.ofHours(24)).minusSeconds(1);
    assertEquals(
        List.of("old"),
        ids(store.failures(tenant, almost, 20, null)),
        "one second short of a day it is still there");
    Instant day = T0.plus(Duration.ofHours(24));
    assertEquals(
        List.of(),
        ids(store.failures(tenant, day, 20, null)),
        "a day on it is not returned, though Redis may still hold it");
    assertEquals(List.of(), ids(store.failures(tenant, day.plusSeconds(1), 20, null)));
  }

  @Test
  @DisplayName(
      "An old failure is also trimmed from the set by the next write, and the key's life is renewed")
  void writesTrimByAgeAndRenewTheKey() {
    String tenant = Ids.newId().toString();
    FlowStore store = store(500);
    store.write(List.of(failure(tenant, T0, "old", 500)));

    Instant later = T0.plus(Duration.ofHours(25));
    clock.to(later);
    store.write(List.of(failure(tenant, later, "new", 503)));

    assertEquals(
        1, redis.zcard(FlowBuckets.failuresKey(tenant)), "the day-old one was trimmed on write");
    assertEquals(List.of("new"), ids(store.failures(tenant, later, 20, null)));
    long ttl = redis.ttl(FlowBuckets.failuresKey(tenant));
    assertTrue(ttl > 23 * 3600 && ttl <= 24 * 3600, "renewed to a full day: " + ttl);
  }

  @Test
  @DisplayName(
      "The list is capped: past the cap the oldest are dropped, the newest kept, newest first")
  void capKeepsTheNewest() {
    String tenant = Ids.newId().toString();
    FlowStore store = store(3);
    List<FlowRecord> five = new ArrayList<>();
    for (int i = 1; i <= 5; i++) five.add(failure(tenant, T0.plusSeconds(i), "f" + i, 500));
    store.write(five);
    assertEquals(
        List.of("f5", "f4", "f3"), ids(store.failures(tenant, T0.plusSeconds(10), 20, null)));
    assertEquals(3, redis.zcard(FlowBuckets.failuresKey(tenant)));

    store.write(List.of(failure(tenant, T0.plusSeconds(6), "f6", 500)));
    assertEquals(
        List.of("f6", "f5", "f4"), ids(store.failures(tenant, T0.plusSeconds(10), 20, null)));
  }

  @Test
  @DisplayName(
      "Pages are cut by the moment of the last failure seen, so a new failure does not shift them")
  void cursorPaging() {
    String tenant = Ids.newId().toString();
    FlowStore store = store(500);
    List<FlowRecord> five = new ArrayList<>();
    for (int i = 1; i <= 5; i++) five.add(failure(tenant, T0.plusSeconds(i), "f" + i, 500));
    store.write(five);
    Instant now = T0.plusSeconds(60);

    FlowStore.FailureSlice first = store.failures(tenant, now, 2, null);
    assertEquals(List.of("f5", "f4"), ids(first));
    assertTrue(first.nextBefore() != null, "there is more");

    store.write(
        List.of(failure(tenant, T0.plusSeconds(30), "f-new", 500))); // arrives between pages

    FlowStore.FailureSlice second = store.failures(tenant, now, 2, first.nextBefore());
    assertEquals(List.of("f3", "f2"), ids(second), "no repeat, no skip");
    FlowStore.FailureSlice third = store.failures(tenant, now, 2, second.nextBefore());
    assertEquals(List.of("f1"), ids(third));
    assertNull(third.nextBefore(), "the last page has no cursor");

    FlowStore.FailureSlice exact = store.failures(tenant, now, 6, null);
    assertEquals(6, exact.items().size());
    assertNull(exact.nextBefore(), "exactly a page's worth is not 'more'");
  }

  @Test
  @DisplayName("Another business's failures are never in the list, whatever the request id")
  void listsAreSeparatePerBusiness() {
    String ours = Ids.newId().toString();
    String theirs = Ids.newId().toString();
    FlowStore store = store(500);
    store.write(
        List.of(failure(ours, T0, "ours", 500), failure(theirs, T0.plusSeconds(1), "theirs", 500)));
    assertEquals(List.of("ours"), ids(store.failures(ours, T0.plusSeconds(5), 20, null)));
    assertEquals(List.of("theirs"), ids(store.failures(theirs, T0.plusSeconds(5), 20, null)));
    assertEquals(
        List.of(), ids(store.failures(Ids.newId().toString(), T0.plusSeconds(5), 20, null)));
  }

  @Test
  @DisplayName("A stored failure holds exactly the nine fields the screen shows, and no payload")
  void storedShape() {
    String tenant = Ids.newId().toString();
    store(500).write(List.of(failure(tenant, T0, "shape", 502)));
    String member = redis.zrange(FlowBuckets.failuresKey(tenant), 0, -1).get(0);
    Set<String> keys =
        new TreeSet<>(Json.createReader(new StringReader(member)).readObject().keySet());
    assertEquals(
        new TreeSet<>(
            Set.of(
                "at",
                "requestId",
                "method",
                "routePattern",
                "group",
                "status",
                "code",
                "userId",
                "ms")),
        keys);
    assertFalse(member.contains(tenant), "the business is the key, not part of the entry");
  }

  @Test
  @DisplayName("An entry that does not parse is skipped, not a reason to fail the read")
  void corruptMembersAreSkipped() {
    String tenant = Ids.newId().toString();
    FlowStore store = store(500);
    store.write(List.of(failure(tenant, T0, "good", 500)));
    redis.zadd(
        FlowBuckets.failuresKey(tenant), FailureList.score(T0.plusSeconds(1)), "not json at all");
    redis.zadd(
        FlowBuckets.failuresKey(tenant),
        FailureList.score(T0.plusSeconds(2)),
        "{\"at\":\"yesterday\"}");
    assertEquals(List.of("good"), ids(store.failures(tenant, T0.plusSeconds(5), 20, null)));
  }
}
