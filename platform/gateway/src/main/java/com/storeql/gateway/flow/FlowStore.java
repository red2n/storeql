package com.storeql.gateway.flow;

import com.storeql.gateway.GatewayConfig;
import com.storeql.gateway.flow.SystemHealthDtos.FailureItem;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.ScoredValue;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The flow counters and failures in Redis: the one place that knows the key layout ({@link
 * FlowBuckets}) and speaks to it. Every method that touches Redis throws when Redis cannot be
 * reached, and the caller decides what that means — the recorder discards and backs off, the read
 * says "unavailable".
 *
 * <p>A batch is added up in memory first ({@link #plan}) and sent as one script, so a thousand
 * requests are a handful of increments in one round trip, and a key's count and its expiry are set
 * in the same step: a crash cannot leave a counter that never expires.
 */
@ApplicationScoped
public class FlowStore implements FlowRecorder.Sink {

  /**
   * Arguments, per key in order: {@code H ttl n (field delta)*n} adds to a hash and renews its
   * life; {@code Z ttl cap cutoff n (score member)*n} adds to a sorted set, drops what is at or
   * below the cutoff (a day old) and what is past the cap, and renews its life.
   */
  private static final RedisScript WRITE =
      new RedisScript(
          """
          local a = 1
          for i = 1, #KEYS do
            local kind = ARGV[a]
            local ttl = tonumber(ARGV[a + 1])
            if kind == 'H' then
              local n = tonumber(ARGV[a + 2])
              a = a + 3
              for _ = 1, n do
                redis.call('HINCRBY', KEYS[i], ARGV[a], ARGV[a + 1])
                a = a + 2
              end
            else
              local cap = tonumber(ARGV[a + 2])
              local cutoff = ARGV[a + 3]
              local n = tonumber(ARGV[a + 4])
              a = a + 5
              for _ = 1, n do
                redis.call('ZADD', KEYS[i], ARGV[a], ARGV[a + 1])
                a = a + 2
              end
              redis.call('ZREMRANGEBYSCORE', KEYS[i], '-inf', cutoff)
              redis.call('ZREMRANGEBYRANK', KEYS[i], 0, -(cap + 1))
            end
            redis.call('EXPIRE', KEYS[i], ttl)
          end
          return #KEYS
          """);

  /** Every key is a hash; the reply is one flat field/value list per key. */
  private static final RedisScript READ =
      new RedisScript(
          """
          local out = {}
          for i = 1, #KEYS do
            out[i] = redis.call('HGETALL', KEYS[i])
          end
          return out
          """);

  @Inject RedisCommands<String, String> redis;
  @Inject GatewayConfig config;

  private int failureCap;
  private Clock clock = Clock.systemUTC();

  public FlowStore() {}

  /** For tests: a store on the given Redis, with its own cap and clock. */
  FlowStore(RedisCommands<String, String> redis, int failureCap, Clock clock) {
    this.redis = redis;
    this.failureCap = failureCap;
    this.clock = clock;
  }

  @PostConstruct
  void init() {
    // A cap below one would trim the whole list on every write.
    failureCap = Math.max(1, config.flowFailureCap());
  }

  /** One page of failures. */
  public record FailureSlice(List<FailureItem> items, Long nextBefore) {
    public FailureSlice {
      items = List.copyOf(items);
    }
  }

  /** What a batch comes to: counts to add to hashes, and failures to add to sets. */
  record Plan(
      Map<String, Map<String, Long>> minutes,
      Map<String, Map<String, Long>> hours,
      Map<String, List<FailureList.Entry>> failures) {}

  /**
   * Adds a batch up. A business's failures are listed for it; traffic with no verified business is
   * counted under the unattributed key and listed for nobody; and the screen's own reads ({@link
   * FlowRecord#isScreenRead}, which {@link FlowRecordingFilter} asks too) are never listed, so a
   * client polling through a refusal cannot push the failures a person came to see out of a list
   * that holds five hundred.
   *
   * @param batch the requests
   * @param now the moment of the write, which fixes what is too old to list
   * @return the increments and the failures
   */
  static Plan plan(List<FlowRecord> batch, Instant now) {
    Map<String, Map<String, Long>> minutes = new LinkedHashMap<>();
    Map<String, Map<String, Long>> hours = new LinkedHashMap<>();
    Map<String, List<FailureList.Entry>> failures = new LinkedHashMap<>();
    long cutoff = FailureList.cutoff(now);
    for (FlowRecord r : batch) {
      String tenant = r.tenantId() == null ? FlowBuckets.UNATTRIBUTED : r.tenantId();
      String field = FlowBuckets.field(r.group(), r.outcome());
      minutes
          .computeIfAbsent(
              FlowBuckets.minuteKey(tenant, FlowBuckets.minuteOf(r.at())),
              k -> new LinkedHashMap<>())
          .merge(field, 1L, Long::sum);
      hours
          .computeIfAbsent(
              FlowBuckets.hourKey(tenant, FlowBuckets.hourOf(r.at())), k -> new LinkedHashMap<>())
          .merge(field, 1L, Long::sum);
      if (r.outcome() == FlowOutcome.FAILED && r.tenantId() != null && !r.isScreenRead()) {
        FailureList.Entry entry = FailureList.entry(r);
        if (entry.score() > cutoff) {
          failures
              .computeIfAbsent(FlowBuckets.failuresKey(r.tenantId()), k -> new ArrayList<>())
              .add(entry);
        }
      }
    }
    return new Plan(minutes, hours, failures);
  }

  /**
   * Writes a batch.
   *
   * @param batch the requests to count
   * @throws RuntimeException when Redis cannot be written
   */
  @Override
  public void write(List<FlowRecord> batch) {
    if (batch.isEmpty()) return;
    Instant now = clock.instant();
    Plan plan = plan(batch, now);
    List<String> keys = new ArrayList<>();
    List<String> args = new ArrayList<>();
    plan.minutes()
        .forEach(
            (key, deltas) -> counters(keys, args, key, FlowBuckets.MINUTE_TTL_SECONDS, deltas));
    plan.hours()
        .forEach((key, deltas) -> counters(keys, args, key, FlowBuckets.HOUR_TTL_SECONDS, deltas));
    String cutoff = Long.toString(FailureList.cutoff(now));
    plan.failures()
        .forEach(
            (key, entries) -> {
              keys.add(key);
              args.add("Z");
              args.add(Long.toString(FlowBuckets.FAILURE_TTL_SECONDS));
              args.add(Integer.toString(failureCap));
              args.add(cutoff);
              args.add(Integer.toString(entries.size()));
              for (FailureList.Entry e : entries) {
                args.add(Long.toString(e.score()));
                args.add(e.member());
              }
            });
    WRITE.run(
        redis, ScriptOutputType.INTEGER, keys.toArray(String[]::new), args.toArray(String[]::new));
  }

  private static void counters(
      List<String> keys, List<String> args, String key, long ttl, Map<String, Long> deltas) {
    keys.add(key);
    args.add("H");
    args.add(Long.toString(ttl));
    args.add(Integer.toString(deltas.size()));
    deltas.forEach(
        (field, delta) -> {
          args.add(field);
          args.add(Long.toString(delta));
        });
  }

  /**
   * A business's counters for the screen: the last sixty minutes and twenty-four hours, in one
   * round trip.
   *
   * @param tenant the verified business
   * @param now the moment the screen is drawn for
   * @return the buckets, oldest first
   * @throws RuntimeException when Redis cannot be read
   */
  public FlowCounters counters(String tenant, Instant now) {
    List<Long> minuteNumbers = FlowBuckets.lastMinutes(now);
    List<Long> hourNumbers = FlowBuckets.lastHours(now);
    String[] keys = new String[minuteNumbers.size() + hourNumbers.size()];
    int at = 0;
    for (long m : minuteNumbers) keys[at++] = FlowBuckets.minuteKey(tenant, m);
    for (long h : hourNumbers) keys[at++] = FlowBuckets.hourKey(tenant, h);
    List<Object> reply = READ.run(redis, ScriptOutputType.MULTI, keys);
    List<Map<String, Long>> minutes = new ArrayList<>();
    List<Map<String, Long>> hours = new ArrayList<>();
    for (int i = 0; i < keys.length; i++) {
      Map<String, Long> hash = i < reply.size() ? hash(reply.get(i)) : Map.of();
      (i < minuteNumbers.size() ? minutes : hours).add(hash);
    }
    return new FlowCounters(minutes, hours);
  }

  /**
   * A flat {@code field, value, field, value} reply as a map; a pair that is not a count is
   * skipped.
   */
  private static Map<String, Long> hash(Object flat) {
    if (!(flat instanceof List<?> pairs) || pairs.isEmpty()) return Map.of();
    Map<String, Long> out = new LinkedHashMap<>();
    for (int i = 0; i + 1 < pairs.size(); i += 2) {
      try {
        out.put(String.valueOf(pairs.get(i)), Long.parseLong(String.valueOf(pairs.get(i + 1))));
      } catch (NumberFormatException ignored) {
        // a field that is not a count adds nothing to any number
      }
    }
    return out;
  }

  /**
   * A page of a business's failures from the last day, newest first.
   *
   * @param tenant the verified business
   * @param now the moment of the read, which fixes the day
   * @param limit the most to return
   * @param beforeMicros the {@code nextBefore} of the previous page, or null for the first
   * @return the page, and where the next begins when there is one
   * @throws RuntimeException when Redis cannot be read
   */
  public FailureSlice failures(String tenant, Instant now, int limit, Long beforeMicros) {
    Range<Double> range =
        Range.from(
            Range.Boundary.excluding((double) FailureList.cutoff(now)),
            beforeMicros == null
                ? Range.Boundary.<Double>unbounded()
                : Range.Boundary.excluding((double) beforeMicros));
    List<ScoredValue<String>> got =
        redis.zrevrangebyscoreWithScores(
            FlowBuckets.failuresKey(tenant), range, Limit.create(0, limit + 1L));
    boolean more = got.size() > limit;
    List<FailureItem> items = new ArrayList<>();
    Long next = null;
    for (int i = 0; i < Math.min(got.size(), limit); i++) {
      FailureList.parse(got.get(i).getValue()).ifPresent(items::add);
      next = (long) got.get(i).getScore();
    }
    if (!more) next = null;
    return new FailureSlice(List.copyOf(items), next);
  }
}
