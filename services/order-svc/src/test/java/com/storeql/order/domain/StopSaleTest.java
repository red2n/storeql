package com.storeql.order.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.order.domain.StopSale.ActiveRecall;
import com.storeql.order.domain.StopSale.Pack;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Stop-sale at checkout, the rule on its own. It is the till's rule line for line ({@code
 * pos_recall_check.dart}): what the till stops outright is refused, what the till leaves to the
 * cashier's eye is not, and a pack of another lot or date sells. A till sale replayed from the
 * till's offline queue within the grace is flagged for the recalls open when it was rung up — one
 * closed or cancelled since included — in words for the audit trail; one whose capture time is not
 * honoured is refused for a manager.
 */
class StopSaleTest {

  private static final UUID JAM = Ids.newId();
  private static final UUID HONEY = Ids.newId();
  private static final LocalDate OCT_1 = LocalDate.parse("2026-10-01");
  private static final LocalDate OCT_10 = LocalDate.parse("2026-10-10");
  private static final Instant NINE = Instant.parse("2026-09-29T09:00:00Z");

  private static ActiveRecall recall(UUID variant, String lot, LocalDate from, LocalDate to) {
    return new ActiveRecall(
        Ids.newId(), "R-2026-017", "RECALL", "ALLERGEN", variant, lot, from, to);
  }

  /** A recall of every pack of the jam, opened at {@code opened}. */
  private static ActiveRecall openedAt(Instant opened) {
    return new ActiveRecall(
        Ids.newId(), "R-2026-017", "RECALL", "ALLERGEN", JAM, null, null, null, opened);
  }

  private static boolean stops(ActiveRecall r, String lot, LocalDate expiry) {
    return StopSale.stopping(List.of(r), new Pack(0, JAM, lot, expiry)).isPresent();
  }

  @Test
  void aRecallOfEveryPackStopsThePackWhateverItSays() {
    ActiveRecall every = recall(JAM, null, null, null);
    assertThat(stops(every, null, null), is(true));
    assertThat(stops(every, "L42", OCT_1), is(true));
  }

  @Test
  void aLotRecallStopsThatLotAndSellsEveryOther() {
    ActiveRecall lot = recall(JAM, "L42", null, null);
    assertThat(stops(lot, "L42", null), is(true));
    assertThat("printed either way, it is the same lot", stops(lot, " l42 ", null), is(true));
    assertThat(stops(lot, "L43", null), is(false));
  }

  @Test
  void aPackThatSaysNothingOfItselfIsLeftToTheCashier() {
    assertThat(stops(recall(JAM, "L42", null, null), null, null), is(false));
    assertThat(stops(recall(JAM, null, OCT_1, OCT_10), null, null), is(false));
    assertThat(
        "a lot says nothing of a date the recall names",
        stops(recall(JAM, null, OCT_1, OCT_10), "L42", null),
        is(false));
  }

  @Test
  void anExpiryWindowHoldsBothEnds() {
    ActiveRecall window = recall(JAM, null, OCT_1, OCT_10);
    assertThat(stops(window, null, OCT_1), is(true));
    assertThat(stops(window, null, OCT_10), is(true));
    assertThat(stops(window, null, OCT_1.minusDays(1)), is(false));
    assertThat(stops(window, null, OCT_10.plusDays(1)), is(false));
  }

  @Test
  void aWindowOpenAtOneEndRunsOnForever() {
    ActiveRecall onwards = recall(JAM, null, OCT_1, null);
    assertThat(stops(onwards, null, OCT_1.plusYears(1)), is(true));
    assertThat(stops(onwards, null, OCT_1.minusDays(1)), is(false));
    ActiveRecall until = recall(JAM, null, null, OCT_10);
    assertThat(stops(until, null, OCT_10.minusYears(1)), is(true));
    assertThat(stops(until, null, OCT_10.plusDays(1)), is(false));
  }

  @Test
  void aLotAndDatesMustBothAgree() {
    ActiveRecall both = recall(JAM, "L42", OCT_1, OCT_10);
    assertThat(stops(both, "L42", OCT_1), is(true));
    assertThat(stops(both, "L42", OCT_10.plusDays(1)), is(false));
    assertThat(stops(both, "L41", OCT_1), is(false));
    assertThat(stops(both, "L42", null), is(false));
  }

  @Test
  void anotherVariantsRecallNeverStopsThisOne() {
    assertThat(stops(recall(HONEY, null, null, null), null, null), is(false));
    assertThat(StopSale.stopped(List.of(), List.of(new Pack(0, JAM, null, null))), is(empty()));
  }

  @Test
  void everyStoppedLineIsNamedInWordsACashierCanActOn() {
    List<ActiveRecall> open =
        List.of(recall(JAM, null, null, null), recall(HONEY, "L42", OCT_1, OCT_10));
    List<Pack> sale =
        List.of(
            new Pack(0, JAM, null, null),
            new Pack(1, Ids.newId(), null, null),
            new Pack(2, HONEY, "L42", OCT_1));

    var stopped = StopSale.stopped(open, sale);

    assertThat(stopped, hasSize(2));
    String words = StopSale.refusal(stopped);
    assertThat(
        words,
        containsString(
            "item 1 is under product recall R-2026-017 (undeclared allergen), every pack"));
    assertThat(
        words,
        containsString(
            "item 3 is under product recall R-2026-017 (undeclared allergen), lot L42, best"
                + " before 2026-10-01 to 2026-10-10"));
    assertThat(words, containsString("Take them out of the sale and hand them to a supervisor."));
    assertThat(StopSale.details(stopped).get(1), containsString("items[2]: variant " + HONEY));
  }

  @Test
  void aWithdrawalIsCalledOne() {
    var withdrawal =
        new ActiveRecall(Ids.newId(), "W-9", "WITHDRAWAL", "LABELLING", JAM, null, null, OCT_10);
    var stopped = StopSale.stopped(List.of(withdrawal), List.of(new Pack(0, JAM, null, OCT_1)));

    assertThat(
        StopSale.refusal(stopped),
        is(
            "This sale has stock that must not be sold: item 1 is under product withdrawal W-9"
                + " (labelling error), best before 2026-10-10 or earlier. Take it out of the sale"
                + " and hand it to a supervisor."));
  }

  @Test
  void aReplayedSaleIsFlaggedForTheRecallsOpenWhenItWasRungUp() {
    List<Pack> jar = List.of(new Pack(0, JAM, null, null));
    List<ActiveRecall> later = List.of(openedAt(NINE.plusSeconds(600)));

    assertThat(
        "opened after the sale was made, so it did not cover it: nothing to flag",
        StopSale.stopped(StopSale.openWhen(later, NINE), jar),
        is(empty()));
    assertThat(
        "a sale made now is stopped by it",
        StopSale.stopped(StopSale.openWhen(later, null), jar),
        hasSize(1));
    List<ActiveRecall> earlier = List.of(openedAt(NINE.minusSeconds(60)));
    assertThat(
        "opened before the sale, so the replay is flagged for it",
        StopSale.stopped(StopSale.openWhen(earlier, NINE), jar),
        hasSize(1));
  }

  /** A recall of every pack of the jam, opened at {@code opened} and ended at {@code ended}. */
  private static ActiveRecall ended(Instant opened, Instant ended, String endedAs) {
    return new ActiveRecall(
        Ids.newId(),
        "R-2026-017",
        "RECALL",
        "ALLERGEN",
        JAM,
        null,
        null,
        null,
        opened,
        ended,
        endedAs);
  }

  @Test
  void aRecallEndedSinceTheSaleStillCoveredItWhenItWasRungUp() {
    List<Pack> jar = List.of(new Pack(0, JAM, null, null));
    List<ActiveRecall> closedSince =
        List.of(ended(NINE.minusSeconds(3600), NINE.plusSeconds(600), "CLOSED"));
    assertThat(
        "open when the sale was made, closed before the replay: flagged",
        StopSale.stopped(StopSale.openWhen(closedSince, NINE), jar),
        hasSize(1));
    assertThat(
        "a sale made now is not stopped by a recall that has ended",
        StopSale.stopped(StopSale.openWhen(closedSince, null), jar),
        is(empty()));
    List<ActiveRecall> endedBefore =
        List.of(ended(NINE.minusSeconds(3600), NINE.minusSeconds(60), "CLOSED"));
    assertThat(
        "ended before the sale was made: nothing to flag",
        StopSale.stopped(StopSale.openWhen(endedBefore, NINE), jar),
        is(empty()));
    List<ActiveRecall> endedThen = List.of(ended(NINE.minusSeconds(3600), NINE, "CLOSED"));
    assertThat(
        "ended at the very moment of the sale: in doubt, so flagged",
        StopSale.stopped(StopSale.openWhen(endedThen, NINE), jar),
        hasSize(1));
  }

  @Test
  void anEntryForARecallEndedSinceSaysHowItEnded() {
    List<Pack> jar = List.of(new Pack(0, JAM, null, null));
    var closed = StopSale.stopped(List.of(ended(NINE, NINE.plusSeconds(60), "CLOSED")), jar);
    assertThat(
        StopSale.soldOffline(closed.get(0)),
        is(
            "Sold while the till was offline: when it was rung up, item 1 was under product"
                + " recall R-2026-017 (undeclared allergen), every pack. The recall has since been"
                + " closed."));
    var cancelled = StopSale.stopped(List.of(ended(NINE, NINE.plusSeconds(60), "CANCELLED")), jar);
    assertThat(
        StopSale.soldOffline(cancelled.get(0)),
        containsString("The recall has since been cancelled as raised in error."));
  }

  @Test
  void anEntryNamesARecallStillOpenOverOneThatHasEndedSince() {
    // Both covered the jar when it was rung up; one was closed before the replay, the other still
    // stops the product. A line leaves one entry, so it names the one still open: a manager told
    // only that the recall has been closed would take the product as cleared, and the buyer must
    // still be reached.
    List<Pack> jar = List.of(new Pack(0, JAM, null, null));
    ActiveRecall closedSince = ended(NINE.minusSeconds(86_400), NINE.plusSeconds(1800), "CLOSED");
    ActiveRecall stillOpen = openedAt(NINE.minusSeconds(1800));

    var stopped = StopSale.stopped(StopSale.openWhen(List.of(closedSince, stillOpen), NINE), jar);

    assertThat(stopped, hasSize(1));
    assertThat(stopped.get(0).recall(), is(stillOpen));
    assertThat(StopSale.soldOffline(stopped.get(0)), not(containsString("since been")));

    Instant halfPastEight = NINE.minusSeconds(1800);
    ActiveRecall lotOpen =
        new ActiveRecall(
            Ids.newId(), "R-2026-018", "RECALL", "ALLERGEN", JAM, "L42", null, null, halfPastEight);
    List<ActiveRecall> both = StopSale.openWhen(List.of(closedSince, lotOpen), NINE);
    assertThat(
        "a lot recall still open, over one of every pack since closed",
        StopSale.stopping(both, new Pack(0, JAM, "L42", null)),
        is(Optional.of(lotOpen)));
    assertThat(
        "an ended one is named only when no open one covers the pack",
        StopSale.stopping(both, new Pack(0, JAM, "L43", null)),
        is(Optional.of(closedSince)));
  }

  @Test
  void aRecallThatDoesNotSayWhenItOpenedStopsEverySale() {
    List<Pack> jar = List.of(new Pack(0, JAM, null, null));
    List<ActiveRecall> unsaid = List.of(openedAt(null));
    assertThat(StopSale.stopped(StopSale.openWhen(unsaid, NINE), jar), hasSize(1));
  }

  @Test
  void aReplayWhoseTimeIsNotHonouredIsRefusedForAManager() {
    List<Pack> jar = List.of(new Pack(0, JAM, null, null));
    var stopped = StopSale.stopped(List.of(openedAt(NINE.minusSeconds(60))), jar);

    assertThat(
        StopSale.refusal(stopped, true),
        is(
            "This sale was rung up while the till was offline, at a time too long ago to be taken"
                + " on the till's word (or one later than now), so it is judged as a sale made"
                + " now, and its stock is stopped from sale: item 1 is under product recall"
                + " R-2026-017 (undeclared allergen), every pack. Hand the sale to a manager."));
  }

  @Test
  void aLineSoldOfflineUnderARecallIsNamedForTheTrail() {
    var every = StopSale.stopped(List.of(openedAt(NINE)), List.of(new Pack(0, JAM, null, null)));
    assertThat(
        StopSale.soldOffline(every.get(0)),
        is(
            "Sold while the till was offline: when it was rung up, item 1 was under product"
                + " recall R-2026-017 (undeclared allergen), every pack."));

    var withdrawal =
        new ActiveRecall(Ids.newId(), "W-9", "WITHDRAWAL", "LABELLING", HONEY, "L42", null, null);
    var lot =
        StopSale.stopped(
            List.of(withdrawal),
            List.of(new Pack(0, JAM, null, null), new Pack(1, HONEY, "L42", OCT_1)));
    assertThat(
        "what the pack said of itself is named too",
        StopSale.soldOffline(lot.get(0)),
        is(
            "Sold while the till was offline: when it was rung up, item 2 (lot L42, best before"
                + " 2026-10-01) was under product withdrawal W-9 (labelling error), lot L42."));
  }
}
