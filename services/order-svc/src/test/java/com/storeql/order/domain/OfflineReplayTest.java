package com.storeql.order.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.order.domain.Domain.OfflineSaleFlag;
import com.storeql.order.domain.OfflineReplay.Timing;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * How a till sale is judged: a sale made now is judged now; one replayed from the till's offline
 * queue, captured no later than now and no further back than the grace, has already happened and is
 * recorded whatever the checks say, flagged for a manager instead; any other capture time is the
 * till's word only, and that sale is judged as made now.
 */
class OfflineReplayTest {

  private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
  private static final Duration DAY = Duration.ofHours(24);

  /** A scale's re-verification due date well after the sales here. */
  private static final LocalDate DUE = LocalDate.parse("2026-12-31");

  @Test
  void aSaleMadeNowIsJudgedNow() {
    Timing live = OfflineReplay.timing(null, NOW, DAY);
    assertThat(live, is(Timing.LIVE));
    assertThat(live.honoured(), is(false));
    assertThat(live.replayed(), is(false));
  }

  @Test
  void aReplayWithinTheGraceIsHonouredAtItsCaptureTime() {
    Instant rungUp = NOW.minusSeconds(600);
    Timing replay = OfflineReplay.timing(rungUp, NOW, DAY);
    assertThat(replay.honoured(), is(true));
    assertThat(replay.rungUpAt(), is(rungUp));
    assertThat(
        "the present itself is not after now",
        OfflineReplay.timing(NOW, NOW, DAY).rungUpAt(),
        is(NOW));
    assertThat(
        "the grace's own edge is inside it",
        OfflineReplay.timing(NOW.minus(DAY), NOW, DAY).rungUpAt(),
        is(NOW.minus(DAY)));
  }

  @Test
  void aCaptureOlderThanTheGraceIsJudgedAsASaleMadeNow() {
    Timing old = OfflineReplay.timing(NOW.minus(DAY).minusSeconds(1), NOW, DAY);
    assertThat(old, is(Timing.NOT_HONOURED));
    assertThat(old.rungUpAt(), is(nullValue()));
    assertThat("still a replay, refused in words for a manager", old.replayed(), is(true));
    assertThat(
        OfflineReplay.timing(NOW.minus(Duration.ofDays(10)), NOW, DAY), is(Timing.NOT_HONOURED));
  }

  @Test
  void aCaptureTimeLaterThanNowIsNotHonoured() {
    assertThat(
        "a till clock ahead of ours",
        OfflineReplay.timing(NOW.plusSeconds(90), NOW, DAY),
        is(Timing.NOT_HONOURED));
  }

  @Test
  void aTillClockALittleAheadIsTakenAsCapturedNow() {
    Duration five = Duration.ofMinutes(5);
    Timing ahead = OfflineReplay.timing(NOW.plusSeconds(90), NOW, DAY, five);
    assertThat("honoured, not refused", ahead.honoured(), is(true));
    assertThat("as rung up now, never in the future", ahead.rungUpAt(), is(NOW));
    assertThat(
        "the tolerance's own edge is inside it",
        OfflineReplay.timing(NOW.plus(five), NOW, DAY, five).rungUpAt(),
        is(NOW));
    assertThat(
        "a second beyond it is judged as a sale made now",
        OfflineReplay.timing(NOW.plus(five).plusSeconds(1), NOW, DAY, five),
        is(Timing.NOT_HONOURED));
    assertThat(
        "a time before now keeps its own moment",
        OfflineReplay.timing(NOW.minusSeconds(30), NOW, DAY, five).rungUpAt(),
        is(NOW.minusSeconds(30)));
    assertThat(
        "no tolerance takes none",
        OfflineReplay.timing(NOW.plusSeconds(1), NOW, DAY, Duration.ZERO),
        is(Timing.NOT_HONOURED));
    assertThat(
        "nor does a negative one",
        OfflineReplay.timing(NOW.plusSeconds(1), NOW, DAY, Duration.ofSeconds(-60)),
        is(Timing.NOT_HONOURED));
    assertThat(
        "no grace honours nothing, however close",
        OfflineReplay.timing(NOW.plusSeconds(1), NOW, Duration.ZERO, five),
        is(Timing.NOT_HONOURED));
  }

  @Test
  void aSaleThatSaysItWasRungUpAMomentAgoIsHonouredAndFlaggedOnPurpose() {
    // Accepted on purpose: the flag is the control. Its lines are recorded and each one a check
    // would have stopped is put in front of a manager, naming who rang it up and who sent it.
    Timing moment = OfflineReplay.timing(NOW.minusSeconds(3), NOW, DAY, Duration.ofMinutes(5));
    assertThat(moment.honoured(), is(true));
    assertThat(moment.rungUpAt(), is(NOW.minusSeconds(3)));
  }

  @Test
  void anEntryNamesWhoTheTillSaysRangItUpOnlyWhenTheBusinessHoldsThemHere() {
    UUID cashier = Ids.newId();
    UUID manager = Ids.newId();
    Map<UUID, Optional<Boolean>> directory = Map.of(cashier, Optional.of(true));
    Function<UUID, Optional<Boolean>> staffHere =
        id -> directory.getOrDefault(id, Optional.of(false));

    assertThat(
        "a manager sending a cashier's queue: the cashier, as the directory confirms",
        OfflineReplay.cashier(cashier, manager, true, staffHere),
        is(cashier));
    assertThat(
        "the sender's own sign-in settles it for a till naming the sender",
        OfflineReplay.cashier(manager, manager, true, id -> Optional.empty()),
        is(manager));
    assertThat(
        "somebody the business does not hold here: nobody is named",
        OfflineReplay.cashier(Ids.newId(), manager, true, staffHere),
        is(nullValue()));
    assertThat(
        "a directory that cannot answer names nobody",
        OfflineReplay.cashier(cashier, manager, true, id -> Optional.empty()),
        is(nullValue()));
    assertThat(
        "a till that said nothing: nobody, never the sender by default",
        OfflineReplay.cashier(null, manager, true, staffHere),
        is(nullValue()));
    assertThat(
        "a sender who is not staff here is asked about like anybody else",
        OfflineReplay.cashier(manager, manager, false, staffHere),
        is(nullValue()));
  }

  @Test
  void noGraceHonoursNoCaptureTime() {
    assertThat(
        OfflineReplay.timing(NOW.minusSeconds(600), NOW, Duration.ZERO), is(Timing.NOT_HONOURED));
  }

  @Test
  void onlyAReplayIsRungUpBeforeNow() {
    assertThrows(IllegalArgumentException.class, () -> new Timing(NOW, false));
  }

  @Test
  void aLineARecallCoveredLeavesAnEntryNamingItAndTheRecall() {
    UUID tenant = Ids.newId();
    UUID order = Ids.newId();
    UUID store = Ids.newId();
    UUID cashier = Ids.newId();
    UUID jam = Ids.newId();
    UUID recallId = Ids.newId();
    Instant rungUp = NOW.minusSeconds(600);
    var recall =
        new StopSale.ActiveRecall(
            recallId, "R-2026-017", "RECALL", "ALLERGEN", jam, "L42", null, null, rungUp);
    var pack = new StopSale.Pack(1, jam, "L42", LocalDate.parse("2026-10-05"));
    UUID id = Ids.newId();

    UUID manager = Ids.newId();
    OfflineSaleFlag flag =
        new OfflineReplay.Replayed(tenant, order, store, cashier, manager, rungUp)
            .recalledItem(id, new StopSale.Stopped(pack, recall));

    assertThat(flag.id(), is(id));
    assertThat(flag.kind(), is("OFFLINE_SALE_OF_RECALLED_ITEM"));
    assertThat("counted from one, as the receipt counts", flag.lineNo(), is(2));
    assertThat(flag.variantId(), is(jam));
    assertThat(flag.batchNo(), is("L42"));
    assertThat(flag.expiry(), is(LocalDate.parse("2026-10-05")));
    assertThat(flag.recallId(), is(recallId));
    assertThat(flag.recallReference(), is("R-2026-017"));
    assertThat(flag.instrumentId(), is(nullValue()));
    assertThat(flag.rungUpAt(), is(rungUp));
    assertThat(flag.cashierId(), is(cashier));
    assertThat("who sent it, beside who rang it up", flag.replayedBy(), is(manager));
    assertThat(
        flag.reason(),
        is(
            "Sold while the till was offline: when it was rung up, item 2 (lot L42, best before"
                + " 2026-10-05) was under product recall R-2026-017 (undeclared allergen), lot"
                + " L42."));
  }

  @Test
  void aLineWeighedOnAnUnfitScaleLeavesAnEntryNamingTheScale() {
    UUID cheese = Ids.newId();
    UUID deli = Ids.newId();
    Instant rungUp = NOW.minusSeconds(600);
    UUID sender = Ids.newId();
    var replayed =
        new OfflineReplay.Replayed(Ids.newId(), Ids.newId(), Ids.newId(), null, sender, rungUp);
    // Due again on 28 September, so overdue from midnight (UTC) before the sale.
    LocalDate due = LocalDate.parse("2026-09-28");
    var overdue =
        new TradeScales.Entry(
            deli, true, "Deli 2", false, "OVERDUE", NOW.minus(Duration.ofDays(365)), due, null);
    var unfit = TradeScales.unfit(List.of(deli), Map.of(deli, overdue), rungUp).get(0);

    OfflineSaleFlag flag = replayed.unfitScale(Ids.newId(), cheese, unfit);

    assertThat(flag.kind(), is("OFFLINE_SALE_ON_UNFIT_SCALE"));
    assertThat(flag.lineNo(), is(1));
    assertThat(flag.variantId(), is(cheese));
    assertThat(flag.instrumentId(), is(deli));
    assertThat(flag.instrumentStanding(), is("OVERDUE"));
    assertThat(flag.recallId(), is(nullValue()));
    assertThat("nobody said who rang it up", flag.cashierId(), is(nullValue()));
    assertThat(flag.replayedBy(), is(sender));
    assertThat(
        flag.reason(),
        is(
            "Sold while the till was offline: item 1 was weighed on Deli 2 when it could not be"
                + " used for trade here (it is overdue for re-verification)."));

    var elsewhere = TradeScales.Entry.notRegistered(deli);
    var notHeld = TradeScales.unfit(List.of(deli), Map.of(deli, elsewhere), rungUp).get(0);
    assertThat(
        "a scale the store's register does not hold",
        replayed.unfitScale(Ids.newId(), cheese, notHeld).instrumentStanding(),
        is("NOT_REGISTERED"));
  }

  @Test
  void aLineOnAScaleTheRegisterCannotShowWasFitLeavesAnEntrySayingSo() {
    UUID cheese = Ids.newId();
    UUID deli = Ids.newId();
    Instant rungUp = NOW.minusSeconds(600);
    var replayed =
        new OfflineReplay.Replayed(Ids.newId(), Ids.newId(), Ids.newId(), null, null, rungUp);
    // Certified today, but verified after the sale: what stood before then is not read here.
    Instant since = NOW.minusSeconds(60);
    var verifiedSince =
        new TradeScales.Entry(deli, true, "Deli 1", true, "CERTIFIED", since, DUE, since);
    var doubt = TradeScales.unfit(List.of(deli), Map.of(deli, verifiedSince), rungUp).get(0);

    OfflineSaleFlag flag = replayed.unfitScale(Ids.newId(), cheese, doubt);

    assertThat(flag.kind(), is("OFFLINE_SALE_ON_UNFIT_SCALE"));
    assertThat(flag.instrumentStanding(), is(OfflineSaleFlag.UNKNOWN_AT_SALE));
    assertThat(
        flag.reason(),
        is(
            "Sold while the till was offline: item 1 was weighed on Deli 1, which is certified for"
                + " trade now; whether it could be used for trade here when the sale was rung up"
                + " cannot be shown, because its latest check was recorded after the sale."));
  }
}
