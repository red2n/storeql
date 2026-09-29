package com.storeql.order.client;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Reading inventory-svc's list of open recalls, {@code GET /admin/inventory/recalls/active}, as it
 * answers today: one scope line per entry, a lot and dates only where the recall names them. An
 * answer that cannot be read is no answer, never an empty list, because an empty list would clear
 * every line of a sale. When each recall opened is read too, for a till sale replayed from an
 * offline queue; a time that is missing or cannot be read is taken as open before any sale. So is
 * when and how one ended, on the read a replay makes; an end that cannot be read is taken as none.
 */
class RecallClientParseTest {

  private static final String JAM = "01a0f2b0-611e-7037-a4b7-c854f0266af1";
  private static final String RECALL = "01a0f2b0-611e-7040-8000-0000000000c1";

  @Test
  void readsEachScopeLineAsTheTillDoes() {
    var read =
        RecallClient.parse(
            "{\"data\":[{\"recallId\":\""
                + RECALL
                + "\",\"reference\":\"R-2026-017\",\"kind\":\"RECALL\",\"hazard\":\"ALLERGEN\","
                + "\"customerNotice\":null,\"variantId\":\""
                + JAM
                + "\",\"batchNo\":\"L42\",\"expiryFrom\":\"2026-10-01\",\"expiryTo\":null}],"
                + "\"meta\":{}}");

    assertThat(read.isPresent(), is(true));
    assertThat(read.get(), hasSize(1));
    var line = read.get().get(0);
    assertThat(line.reference(), is("R-2026-017"));
    assertThat(line.variantId().toString(), is(JAM));
    assertThat(line.batchNo(), is("L42"));
    assertThat(line.expiryFrom(), is(LocalDate.parse("2026-10-01")));
    assertThat(line.expiryTo(), is(nullValue()));
    assertThat(line.coversEveryPack(), is(false));
  }

  @Test
  void noOpenRecallIsAnEmptyList() {
    assertThat(RecallClient.parse("{\"data\":[]}").map(l -> l.size()).orElse(-1), is(0));
  }

  @Test
  void anAnswerThatCannotBeReadIsNoAnswer() {
    assertThat(RecallClient.parse("<html>busy</html>").isPresent(), is(false));
    assertThat(RecallClient.parse("{\"data\":null}").isPresent(), is(false));
    var spoiled =
        RecallClient.parse(
            "{\"data\":[{\"recallId\":\""
                + RECALL
                + "\",\"variantId\":\"not-an-id\",\"kind\":\"RECALL\"}]}");
    assertThat(
        "one line that cannot be read spoils the list rather than leaving a hole in it",
        spoiled.isPresent(),
        is(false));
  }

  @Test
  void readsWhenEachRecallOpened() {
    var read =
        RecallClient.parse(
            "{\"data\":["
                + line("\"openedAt\":\"2026-09-29T09:10:11.123456Z\"")
                + ","
                + line("\"openedAt\":null")
                + ","
                + line("\"openedAt\":\"yesterday\"")
                + "]}");

    assertThat(read.orElseThrow(), hasSize(3));
    assertThat(read.get().get(0).openedAt(), is(Instant.parse("2026-09-29T09:10:11.123456Z")));
    assertThat("not said: open before any sale", read.get().get(1).openedAt(), is(nullValue()));
    assertThat(
        "unreadable: open before any sale, and the list still stands",
        read.get().get(2).openedAt(),
        is(nullValue()));
  }

  @Test
  void readsWhenAndHowARecallEndedSince() {
    var read =
        RecallClient.parse(
            "{\"data\":["
                + line("\"endedAt\":\"2026-09-29T10:00:00Z\",\"endedAs\":\"CANCELLED\"")
                + ","
                + line("\"endedAt\":\"after lunch\",\"endedAs\":\"CLOSED\"")
                + ","
                + line("\"openedAt\":\"2026-09-29T08:00:00Z\"")
                + "]}");

    var cancelled = read.orElseThrow().get(0);
    assertThat(cancelled.endedAt(), is(Instant.parse("2026-09-29T10:00:00Z")));
    assertThat(cancelled.endedAs(), is("CANCELLED"));
    assertThat(
        "an end that cannot be read: taken as not ended, so the line still judges the sale",
        read.get().get(1).endedAt(),
        is(nullValue()));
    assertThat("the till's own list says no end", read.get().get(2).endedAt(), is(nullValue()));
    assertThat(read.get().get(2).endedAs(), is(nullValue()));
  }

  /** One scope line of every pack of the jam, with {@code extra} members. */
  private static String line(String extra) {
    return "{\"recallId\":\""
        + RECALL
        + "\",\"reference\":\"R-1\",\"kind\":\"RECALL\",\"hazard\":\"ALLERGEN\","
        + "\"variantId\":\""
        + JAM
        + "\","
        + extra
        + "}";
  }
}
