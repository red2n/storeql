package com.storeql.order.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.order.domain.TradeScales.Entry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Whether a weighed line's scale may weigh for trade at the order's store, on the register's own
 * word: registered there and certified today, or refused with the reason in the register's words. A
 * till sale replayed from the till's offline queue within the grace is flagged for a scale that was
 * not fit for trade at the moment it was rung up, in words for the audit trail — and for one the
 * register cannot show was fit then, saying why; one whose capture time is not honoured is refused
 * for a manager.
 */
class TradeScalesTest {

  private static final UUID DELI = Ids.newId();
  private static final Instant NINE = Instant.parse("2026-09-29T09:00:00Z");

  private static Entry standing(String standing) {
    return new Entry(DELI, true, "Deli 1", "CERTIFIED".equals(standing), standing);
  }

  private static String refusal(Entry e) {
    return TradeScales.refusal(TradeScales.unfit(List.of(DELI), Map.of(DELI, e)));
  }

  @Test
  void aCertifiedScaleAtTheStoreWeighsForTrade() {
    assertThat(TradeScales.unfit(List.of(DELI), Map.of(DELI, standing("CERTIFIED"))), is(empty()));
  }

  @Test
  void aScaleThatIsNotCertifiedIsRefusedInTheRegistersWords() {
    assertThat(
        refusal(standing("OVERDUE")),
        is(
            "Sold by weight on a scale that may not be used for trade here: item 1 was weighed on"
                + " Deli 1, which is overdue for re-verification. Weigh it again on a scale"
                + " certified at this store."));
    assertThat(refusal(standing("OUT_OF_SERVICE")), containsString("which is out of service"));
    assertThat(refusal(standing("RETIRED")), containsString("which is retired"));
    assertThat(refusal(standing("NEVER_VERIFIED")), containsString("has never been verified"));
    assertThat(refusal(standing("FAILED")), containsString("failed its last check"));
    assertThat(
        refusal(standing("REPAIRED_SINCE")),
        containsString("has been repaired since it was last verified"));
  }

  @Test
  void aScaleTheStoresRegisterDoesNotHoldIsRefused() {
    assertThat(
        refusal(Entry.notRegistered(DELI)),
        containsString("a scale that is not in this store's register of weighing instruments"));
  }

  @Test
  void aLineSoldByTheEachOrOnAScaleNobodyCouldReadIsNotJudged() {
    UUID unread = Ids.newId();
    assertThat(TradeScales.unfit(Arrays.asList(null, unread), Map.of()), is(empty()));
  }

  @Test
  void everyLineWeighedOnAnUnfitScaleIsNamed() {
    List<UUID> lines = Arrays.asList(DELI, null, DELI);
    var unfit = TradeScales.unfit(lines, Map.of(DELI, standing("FAILED")));

    assertThat(unfit, hasSize(2));
    assertThat(TradeScales.refusal(unfit), containsString("item 1 was weighed on Deli 1"));
    assertThat(TradeScales.refusal(unfit), containsString("; item 3 was weighed on Deli 1"));
    assertThat(TradeScales.refusal(unfit), containsString("Weigh them again"));
    assertThat(
        TradeScales.details(unfit).get(1),
        is("items[2].weighingInstrumentId " + DELI + ": standing FAILED"));
  }

  // ── how a scale stood when a replayed sale was rung up ─────────────────────────

  /** A pass recorded a year ago, due again on {@code due}; the instrument unchanged since. */
  private static Entry verified(String standing, LocalDate due) {
    Instant yearAgo = NINE.minus(Duration.ofDays(365));
    return new Entry(
        DELI, true, "Deli 2", "CERTIFIED".equals(standing), standing, yearAgo, due, yearAgo);
  }

  private static TradeScales.AtSale at(Entry e, Instant rungUpAt) {
    return e.atSale(rungUpAt);
  }

  @Test
  void aCertifiedScaleVerifiedAndUnchangedBeforeTheSaleWasFitThen() {
    Entry fit = verified("CERTIFIED", LocalDate.parse("2027-09-28"));
    assertThat(at(fit, NINE), is(new TradeScales.AtSale(true, null)));
    assertThat(TradeScales.unfit(List.of(DELI), Map.of(DELI, fit), NINE), is(empty()));
  }

  @Test
  void aCertifiedScaleVerifiedOrChangedAfterTheSaleCannotBeShownFitThen() {
    Instant after = NINE.plusSeconds(60);
    Instant before = NINE.minusSeconds(60);
    LocalDate due = LocalDate.parse("2027-09-28");
    Entry verifiedSince = new Entry(DELI, true, "Deli 1", true, "CERTIFIED", after, due, before);
    assertThat(at(verifiedSince, NINE).doubt(), is(TradeScales.Doubt.CHECKED_SINCE));
    assertThat("fit today, all the same", verifiedSince.fitForTrade(), is(true));
    Entry changedSince = new Entry(DELI, true, "Deli 1", true, "CERTIFIED", before, due, after);
    assertThat(
        "it may have been out of service then",
        at(changedSince, NINE).doubt(),
        is(TradeScales.Doubt.CHANGED_SINCE));
    Entry undated = new Entry(DELI, true, "Deli 1", true, "CERTIFIED");
    assertThat(at(undated, NINE).doubt(), is(TradeScales.Doubt.CHECK_UNDATED));
    Entry changeUndated = new Entry(DELI, true, "Deli 1", true, "CERTIFIED", before, due, null);
    assertThat(at(changeUndated, NINE).doubt(), is(TradeScales.Doubt.CHANGE_UNDATED));

    var flagged = TradeScales.unfit(List.of(DELI), Map.of(DELI, verifiedSince), NINE);
    assertThat("doubt is flagged, never passed", flagged, hasSize(1));
    assertThat(flagged.get(0).unknownAtSale(), is(true));
    assertThat(
        "a sale made now is judged by today's standing: fit",
        TradeScales.unfit(List.of(DELI), Map.of(DELI, verifiedSince)),
        is(empty()));
  }

  @Test
  void anOverdueScaleWasFitUntilTheEndOfItsDueDateAndUnfitFromIt() {
    // Due on 28 September: certified through that day by the date in UTC, as the register counts.
    Entry overdue = verified("OVERDUE", LocalDate.parse("2026-09-28"));
    Instant midnight = Instant.parse("2026-09-29T00:00:00Z");
    assertThat(at(overdue, midnight.minusSeconds(1)).fit(), is(true));
    assertThat(at(overdue, midnight), is(new TradeScales.AtSale(false, null)));
    assertThat(
        "a sale made now is refused",
        TradeScales.unfit(List.of(DELI), Map.of(DELI, overdue)),
        hasSize(1));
    Entry reVerifiedSince =
        new Entry(
            DELI, true, "Deli 2", false, "OVERDUE", NINE, LocalDate.parse("2026-09-28"), null);
    assertThat(
        "its latest entry came after a sale before the due date: not shown fit",
        at(reVerifiedSince, midnight.minusSeconds(60)).doubt(),
        is(TradeScales.Doubt.CHECKED_SINCE));
  }

  @Test
  void anOverdueScaleWhoseLatestEntryWasRecordedAfterTheSaleIsInDoubtAfterItsDueDate() {
    // A pass performed on 1 February and due again on 1 March, but only entered on 2 June. Until
    // then the latest entry was an earlier pass due at the end of the year, so on 1 June the scale
    // was certified: the lapse the register shows now cannot be placed before the sale.
    Instant enteredLate = Instant.parse("2026-06-02T10:00:00Z");
    Instant rungUp = Instant.parse("2026-06-01T12:00:00Z");
    Entry x =
        new Entry(
            DELI,
            true,
            "Deli 2",
            false,
            "OVERDUE",
            enteredLate,
            LocalDate.parse("2026-03-01"),
            enteredLate);
    assertThat(at(x, rungUp), is(new TradeScales.AtSale(false, TradeScales.Doubt.CHECKED_SINCE)));
    var flagged = TradeScales.unfit(List.of(DELI), Map.of(DELI, x), rungUp);
    assertThat("in doubt, so flagged, never a definite accusation", flagged, hasSize(1));
    assertThat(
        TradeScales.soldOffline(flagged.get(0)),
        is(
            "Sold while the till was offline: item 1 was weighed on Deli 2, which is overdue for"
                + " re-verification now; whether it could be used for trade here when the sale was"
                + " rung up cannot be shown, because its latest check was recorded after the"
                + " sale."));
    assertThat(
        "entered before the sale: unfit for certain",
        at(x, enteredLate.plusSeconds(60)),
        is(new TradeScales.AtSale(false, null)));
    Entry undated =
        new Entry(
            DELI, true, "Deli 2", false, "OVERDUE", null, LocalDate.parse("2026-03-01"), null);
    assertThat(
        "when that entry was recorded is not said",
        at(undated, rungUp).doubt(),
        is(TradeScales.Doubt.CHECK_UNDATED));
  }

  @Test
  void anOverdueScaleWhoseDueDateTheRegisterDidNotGiveIsInDoubt() {
    Instant yearAgo = NINE.minus(Duration.ofDays(365));
    Entry noDue = new Entry(DELI, true, "Deli 2", false, "OVERDUE", yearAgo, null, yearAgo);
    assertThat(at(noDue, NINE).doubt(), is(TradeScales.Doubt.DUE_UNDATED));
    var flagged = TradeScales.unfit(List.of(DELI), Map.of(DELI, noDue), NINE);
    assertThat(flagged, hasSize(1));
    assertThat(flagged.get(0).unknownAtSale(), is(true));
    assertThat(
        TradeScales.soldOffline(flagged.get(0)),
        containsString(
            "cannot be shown, because the register does not say when it fell due for"
                + " re-verification."));
    assertThat(
        "a sale made now is refused on today's standing all the same",
        TradeScales.unfit(List.of(DELI), Map.of(DELI, noDue)),
        hasSize(1));
  }

  @Test
  void aReplayOnAScaleTheRegisterCouldNotBeAskedAboutIsFlaggedInDoubt() {
    UUID unread = Ids.newId();
    var flagged = TradeScales.unfit(Arrays.asList(null, unread), Map.of(), NINE);

    assertThat("the line weighed on it, and not the one sold by the each", flagged, hasSize(1));
    TradeScales.Unfit u = flagged.get(0);
    assertThat(u.line(), is(1));
    assertThat(u.entry().instrumentId(), is(unread));
    assertThat(u.entry().fitForTrade(), is(false));
    assertThat(u.doubt(), is(TradeScales.Doubt.REGISTER_UNREADABLE));
    assertThat(
        "nothing is said of the scale but that nobody could read its register",
        TradeScales.soldOffline(u),
        is(
            "Sold while the till was offline: item 2 was weighed on a scale; whether it could be"
                + " used for trade here when the sale was rung up cannot be shown, because the"
                + " register could not be read when the sale was synced."));
    assertThat(
        "a sale made now still fails open",
        TradeScales.unfit(Arrays.asList(null, unread), Map.of()),
        is(empty()));
  }

  @Test
  void aFailedOrRepairedScaleWasUnfitFromThatEntryAndInDoubtBeforeIt() {
    Instant recorded = NINE;
    for (String standing : new String[] {"FAILED", "REPAIRED_SINCE"}) {
      Entry e = new Entry(DELI, true, "Deli 1", false, standing, recorded, null, recorded);
      assertThat(standing, at(e, recorded.plusSeconds(1)), is(new TradeScales.AtSale(false, null)));
      assertThat(standing, at(e, recorded), is(new TradeScales.AtSale(false, null)));
      assertThat(
          standing + ": the entry in force before is not read here",
          at(e, recorded.minusSeconds(1)).doubt(),
          is(TradeScales.Doubt.CHECKED_SINCE));
    }
  }

  @Test
  void aScaleOutOfServiceOrRetiredCannotBeShownFitBeforeItsLastChange() {
    Instant changed = NINE;
    for (String standing : new String[] {"OUT_OF_SERVICE", "RETIRED"}) {
      Entry e = new Entry(DELI, true, "Deli 3", false, standing, null, null, changed);
      assertThat(standing, at(e, changed.plusSeconds(60)), is(new TradeScales.AtSale(false, null)));
      assertThat(
          standing + ": any edit moves the last change, so the lapse may be later than the sale",
          at(e, changed.minusSeconds(60)).doubt(),
          is(TradeScales.Doubt.CHANGED_SINCE));
      Entry undated = new Entry(DELI, true, "Deli 3", false, standing);
      assertThat(at(undated, NINE).doubt(), is(TradeScales.Doubt.CHANGE_UNDATED));
    }
  }

  @Test
  void aScaleNeverFitOrNotHeldHereIsFlaggedHoweverEarlyTheSale() {
    Instant early = NINE.minusSeconds(86_400);
    Entry never = new Entry(DELI, true, "Deli 4", false, "NEVER_VERIFIED", null, null, NINE);
    var flagged = TradeScales.unfit(List.of(DELI), Map.of(DELI, never), early);
    assertThat(flagged, hasSize(1));
    assertThat("unfit for certain, not in doubt", flagged.get(0).unknownAtSale(), is(false));
    Entry elsewhere = Entry.notRegistered(DELI);
    assertThat(TradeScales.unfit(List.of(DELI), Map.of(DELI, elsewhere), early), hasSize(1));
  }

  @Test
  void aLineOnAScaleInDoubtIsNamedForTheTrailSayingWhy() {
    Entry off = new Entry(DELI, true, "Deli 3", false, "OUT_OF_SERVICE", null, null, NINE);
    var doubt = TradeScales.unfit(List.of(DELI), Map.of(DELI, off), NINE.minusSeconds(60));
    assertThat(
        TradeScales.soldOffline(doubt.get(0)),
        is(
            "Sold while the till was offline: item 1 was weighed on Deli 3, which is out of service"
                + " now; whether it could be used for trade here when the sale was rung up cannot"
                + " be shown, because it was changed in the register after the sale, and the"
                + " register does not keep when its status changed."));
  }

  @Test
  void aReplayWhoseTimeIsNotHonouredIsRefusedForAManager() {
    var unfit = TradeScales.unfit(List.of(DELI), Map.of(DELI, standing("OVERDUE")));
    assertThat(
        TradeScales.refusal(unfit, true),
        is(
            "This sale was rung up while the till was offline, at a time too long ago to be taken"
                + " on the till's word (or one later than now), so it is judged as a sale made"
                + " now, and it was weighed on a scale that may not be used for trade here: item 1"
                + " was weighed on Deli 1, which is overdue for re-verification. Hand the sale to"
                + " a manager."));
  }

  @Test
  void aLineSoldOfflineOnAnUnfitScaleIsNamedForTheTrail() {
    var retired = TradeScales.unfit(Arrays.asList(null, DELI), Map.of(DELI, standing("RETIRED")));
    assertThat(
        TradeScales.soldOffline(retired.get(0)),
        is(
            "Sold while the till was offline: item 2 was weighed on Deli 1 when it could not be"
                + " used for trade here (it is retired)."));
    var elsewhere = TradeScales.unfit(List.of(DELI), Map.of(DELI, Entry.notRegistered(DELI)));
    assertThat(
        TradeScales.soldOffline(elsewhere.get(0)),
        is(
            "Sold while the till was offline: item 1 was weighed on a scale that is not in this"
                + " store's register of weighing instruments."));
  }
}
