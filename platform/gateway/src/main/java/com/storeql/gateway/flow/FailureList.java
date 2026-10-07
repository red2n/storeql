package com.storeql.gateway.flow;

import com.storeql.gateway.flow.SystemHealthDtos.FailureItem;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import java.io.StringReader;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * How a failure is kept: a sorted set per business whose score is the moment in epoch microseconds
 * (exact in a double well past the year 2200) and whose member is the failure as JSON. The score is
 * what makes the set a list of the last day: trimmed by age on write, cut off by age on read, paged
 * by the score of the last entry seen so that a failure arriving between two pages moves nothing.
 *
 * <p>The member holds the nine fields the screen shows and nothing else — not the business, which
 * is the key, and never a body, a query string or a raw path.
 */
public final class FailureList {

  private FailureList() {}

  /**
   * A failure ready to write.
   *
   * @param score its place in the set
   * @param member the failure as JSON
   */
  public record Entry(long score, String member) {}

  /**
   * @param at a moment
   * @return its score: whole microseconds since the epoch
   */
  public static long score(Instant at) {
    return at.getEpochSecond() * 1_000_000L + at.getNano() / 1_000L;
  }

  /**
   * @param now the moment the list is read or written
   * @return the score at and below which a failure is more than a day old and no longer shown
   */
  public static long cutoff(Instant now) {
    return score(now.minusSeconds(FlowBuckets.FAILURE_TTL_SECONDS));
  }

  /**
   * @param record a failed request
   * @return what to write for it
   */
  public static Entry entry(FlowRecord record) {
    Instant at = record.at().truncatedTo(ChronoUnit.MICROS);
    JsonObjectBuilder json =
        Json.createObjectBuilder()
            .add("at", at.toString())
            .add("requestId", record.requestId())
            .add("method", record.method())
            .add("routePattern", record.routePattern())
            .add("group", record.group())
            .add("status", record.status());
    if (record.code() == null) json.addNull("code");
    else json.add("code", record.code());
    if (record.userId() == null) json.addNull("userId");
    else json.add("userId", record.userId());
    json.add("ms", record.ms());
    return new Entry(score(at), json.build().toString());
  }

  /**
   * @param member a stored failure
   * @return the failure, or empty when it does not read (the read skips it)
   */
  public static Optional<FailureItem> parse(String member) {
    try (var reader = Json.createReader(new StringReader(member))) {
      JsonObject o = reader.readObject();
      return Optional.of(
          new FailureItem(
              Instant.parse(o.getString("at")),
              o.getString("requestId"),
              o.getString("method"),
              o.getString("routePattern"),
              o.getString("group"),
              o.getInt("status"),
              o.getString("code", null),
              o.getString("userId", null),
              o.getJsonNumber("ms").longValue()));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }
}
