package com.storeql.order.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeql.ids.Ids;
import com.storeql.order.client.RecallClient;
import com.storeql.order.client.StaffClient;
import com.storeql.order.client.TenantClient;
import com.storeql.order.domain.Domain.OfflineSaleFlag;
import com.storeql.order.domain.OfflineReplay.Timing;
import com.storeql.order.domain.StopSale.ActiveRecall;
import com.storeql.order.domain.StopSale.Pack;
import com.storeql.order.domain.TradeScales.Entry;
import com.storeql.web.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The two checks an order's lines pass before it stands, with inventory-svc and tenant-svc as
 * mocks: a recalled line and a line weighed on an unfit scale are refused with 409 in words; what
 * cannot be read refuses nothing; a business's recalls are kept a few seconds and never a failed
 * read; each scale is asked about once however many lines it weighed. A till sale replayed from the
 * till's offline queue within the grace is never refused: what would have stopped it when it was
 * rung up comes back as entries for the audit trail — a recall closed since, and a scale the
 * register cannot show was fit then or could not be read for at all, included — naming who the till
 * says rang it up only when the staff directory holds them at the store, and who sent it. A replay
 * older than the grace, or dated after now beyond the tolerance for a till clock ahead, is judged
 * as a sale made now and refused for a manager.
 */
@ExtendWith(MockitoExtension.class)
class SaleChecksTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID SHOP = Ids.newId();
  private static final UUID JAM = Ids.newId();
  private static final UUID DELI = Ids.newId();
  private static final UUID ORDER = Ids.newId();
  private static final UUID CASHIER = Ids.newId();
  private static final UUID MANAGER = Ids.newId();

  @Mock RecallClient recalls;
  @Mock TenantClient tenants;
  @Mock StaffClient staff;

  private final Moving clock = new Moving();
  private SaleChecks checks;

  /** A clock the tests move by hand. */
  private static final class Moving extends Clock {
    Instant now = Instant.parse("2026-09-29T09:00:00Z");

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @BeforeEach
  void setUp() {
    checks =
        SaleChecks.forTest(
            recalls,
            tenants,
            staff,
            Duration.ofSeconds(10),
            clock,
            Duration.ofHours(24),
            Duration.ZERO);
  }

  private static List<ActiveRecall> everyPackOfJam() {
    return List.of(
        new ActiveRecall(Ids.newId(), "R-1", "RECALL", "ALLERGEN", JAM, null, null, null));
  }

  private static List<Pack> jam() {
    return List.of(new Pack(0, JAM, null, null));
  }

  @Test
  void aRecalledLineIsRefusedInWords() {
    when(recalls.active(TENANT)).thenReturn(Optional.of(everyPackOfJam()));

    ApiException e =
        assertThrows(ApiException.class, () -> checks.refuseRecalledStock(TENANT, jam()));

    assertThat(e.status(), is(409));
    assertThat(e.code(), is("ORDER_LINE_RECALLED"));
    assertThat(e.getMessage(), containsString("product recall R-1 (undeclared allergen)"));
    assertThat(e.details().get(0), containsString("items[0]: variant " + JAM));
  }

  @Test
  void recallsThatCannotBeReadRefuseNothing() {
    when(recalls.active(TENANT)).thenReturn(Optional.empty());
    assertDoesNotThrow(() -> checks.refuseRecalledStock(TENANT, jam()));
  }

  @Test
  void aBusinesssRecallsAreKeptAFewSecondsAndThenAskedForAgain() {
    when(recalls.active(TENANT)).thenReturn(Optional.of(List.of()));
    checks.refuseRecalledStock(TENANT, jam());
    clock.now = clock.now.plusSeconds(9);
    checks.refuseRecalledStock(TENANT, jam());
    verify(recalls, times(1)).active(TENANT);

    clock.now = clock.now.plusSeconds(1);
    checks.refuseRecalledStock(TENANT, jam());
    verify(recalls, times(2)).active(TENANT);
  }

  @Test
  void aFailedReadIsNeverKept() {
    when(recalls.active(TENANT))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(everyPackOfJam()));
    checks.refuseRecalledStock(TENANT, jam());
    assertThrows(ApiException.class, () -> checks.refuseRecalledStock(TENANT, jam()));
  }

  @Test
  void anOrderWithNoLinesAsksNobody() {
    checks.refuseRecalledStock(TENANT, List.of());
    checks.refuseUnfitScales(TENANT, SHOP, Arrays.asList(null, null));
    verify(recalls, never()).active(any());
    verify(tenants, never()).weighingInstrument(any(), any(), any());
  }

  @Test
  void aLineOnAnUncertifiedScaleIsRefusedInWords() {
    when(tenants.weighingInstrument(TENANT, SHOP, DELI))
        .thenReturn(Optional.of(new Entry(DELI, true, "Deli 1", false, "OVERDUE")));

    ApiException e =
        assertThrows(
            ApiException.class,
            () -> checks.refuseUnfitScales(TENANT, SHOP, Arrays.asList(null, DELI, DELI)));

    assertThat(e.status(), is(409));
    assertThat(e.code(), is("ORDER_SCALE_NOT_CERTIFIED"));
    assertThat(e.getMessage(), containsString("item 2 was weighed on Deli 1, which is overdue"));
    assertThat(e.getMessage(), containsString("item 3 was weighed on Deli 1"));
    verify(tenants, times(1)).weighingInstrument(TENANT, SHOP, DELI);
  }

  @Test
  void aScaleTheRegisterDoesNotHoldIsRefused() {
    when(tenants.weighingInstrument(TENANT, SHOP, DELI))
        .thenReturn(Optional.of(Entry.notRegistered(DELI)));
    ApiException e =
        assertThrows(
            ApiException.class, () -> checks.refuseUnfitScales(TENANT, SHOP, List.of(DELI)));
    assertThat(e.getMessage(), containsString("not in this store's register"));
  }

  @Test
  void aCertifiedScaleOrAnUnreadableRegisterRefusesNothing() {
    UUID unread = Ids.newId();
    when(tenants.weighingInstrument(TENANT, SHOP, DELI))
        .thenReturn(Optional.of(new Entry(DELI, true, "Deli 1", true, "CERTIFIED")));
    when(tenants.weighingInstrument(TENANT, SHOP, unread)).thenReturn(Optional.empty());
    assertDoesNotThrow(() -> checks.refuseUnfitScales(TENANT, SHOP, List.of(DELI, unread)));
  }

  // ── a till sale replayed from the offline queue ───────────────────────────────

  /** The jam, sold by the each, rung up by the cashier and sent by the cashier. */
  private static SaleChecks.Sale jamSale() {
    return jamSale(CASHIER, CASHIER);
  }

  /** The jam, as the till says {@code rungUpBy} rang it up and {@code sender} sent it. */
  private static SaleChecks.Sale jamSale(UUID rungUpBy, UUID sender) {
    return new SaleChecks.Sale(
        TENANT, SHOP, ORDER, rungUpBy, sender, true, jam(), Arrays.asList((UUID) null));
  }

  /** Cheese weighed on the deli scale, as a till sale of this order. */
  private static SaleChecks.Sale weighedSale() {
    return new SaleChecks.Sale(
        TENANT,
        SHOP,
        ORDER,
        CASHIER,
        CASHIER,
        true,
        List.of(new Pack(0, JAM, null, null)),
        List.of(DELI));
  }

  /** Every pack of the jam, recalled from {@code opened}. */
  private static ActiveRecall openedAt(UUID recallId, Instant opened) {
    return new ActiveRecall(recallId, "R-2", "RECALL", "ALLERGEN", JAM, null, null, null, opened);
  }

  @Test
  void aReplayRungUpAfterTheRecallOpenedIsPlacedAndFlagged() {
    UUID recallId = Ids.newId();
    ActiveRecall open = openedAt(recallId, clock.now.minusSeconds(3600));
    when(recalls.openOrEndedSince(eq(TENANT), any())).thenReturn(Optional.of(List.of(open)));
    Instant rungUp = clock.now.minusSeconds(600);

    List<OfflineSaleFlag> flags = checks.check(jamSale(), rungUp);

    assertThat("the sale is not refused, and one line is flagged", flags, hasSize(1));
    OfflineSaleFlag flag = flags.get(0);
    assertThat(flag.kind(), is(OfflineSaleFlag.KIND_RECALLED_ITEM));
    assertThat(flag.tenantId(), is(TENANT));
    assertThat(flag.orderId(), is(ORDER));
    assertThat(flag.storeId(), is(SHOP));
    assertThat(flag.cashierId(), is(CASHIER));
    assertThat(flag.replayedBy(), is(CASHIER));
    assertThat(flag.lineNo(), is(1));
    assertThat(flag.variantId(), is(JAM));
    assertThat(flag.recallId(), is(recallId));
    assertThat(flag.recallReference(), is("R-2"));
    assertThat(flag.rungUpAt(), is(rungUp));
    assertThat(flag.reason(), containsString("item 1 was under product recall R-2"));
  }

  @Test
  void aReplayRungUpBeforeTheRecallOpenedIsPlacedWithNothingToFlag() {
    ActiveRecall later = openedAt(Ids.newId(), clock.now.minusSeconds(60));
    when(recalls.openOrEndedSince(eq(TENANT), any())).thenReturn(Optional.of(List.of(later)));
    when(recalls.active(TENANT)).thenReturn(Optional.of(List.of(later)));

    assertThat(checks.check(jamSale(), clock.now.minusSeconds(600)), is(empty()));
    ApiException now = assertThrows(ApiException.class, () -> checks.check(jamSale(), null));
    assertThat("a sale made now is refused", now.code(), is("ORDER_LINE_RECALLED"));
    assertThat(now.getMessage(), containsString("Take it out of the sale"));
  }

  @Test
  void aReplayUnderARecallClosedSinceIsStillFlagged() {
    Instant rungUp = clock.now.minusSeconds(600);
    ActiveRecall closedSince =
        new ActiveRecall(
            Ids.newId(),
            "R-3",
            "RECALL",
            "ALLERGEN",
            JAM,
            null,
            null,
            null,
            rungUp.minusSeconds(3600),
            rungUp.plusSeconds(60),
            "CLOSED");
    when(recalls.openOrEndedSince(eq(TENANT), any())).thenReturn(Optional.of(List.of(closedSince)));

    List<OfflineSaleFlag> flags = checks.check(jamSale(), rungUp);

    assertThat("open when it was rung up, though closed before the replay", flags, hasSize(1));
    assertThat(flags.get(0).recallReference(), is("R-3"));
    assertThat(flags.get(0).reason(), containsString("The recall has since been closed."));
  }

  @Test
  void aReplayAsksForTheRecallsEndedWithinTheGraceAndKeepsTheReadAFewSeconds() {
    when(recalls.openOrEndedSince(eq(TENANT), any())).thenReturn(Optional.of(List.of()));

    checks.check(jamSale(), clock.now.minusSeconds(600));
    clock.now = clock.now.plusSeconds(5);
    checks.check(jamSale(), clock.now.minusSeconds(3600));

    Instant aDayBeforeTheFirst = Instant.parse("2026-09-28T09:00:00Z");
    verify(recalls, times(1)).openOrEndedSince(TENANT, aDayBeforeTheFirst);
    verify(recalls, never()).active(any());
  }

  @Test
  void aReplayWhoseEndedRecallsCannotBeReadIsJudgedByTheOpenOnes() {
    when(recalls.openOrEndedSince(eq(TENANT), any())).thenReturn(Optional.empty());
    ActiveRecall open = openedAt(Ids.newId(), clock.now.minusSeconds(3600));
    when(recalls.active(TENANT)).thenReturn(Optional.of(List.of(open)));

    assertThat(checks.check(jamSale(), clock.now.minusSeconds(600)), hasSize(1));
  }

  @Test
  void aReplayOlderThanTheGraceIsJudgedAsASaleMadeNowAndRefusedForAManager() {
    when(recalls.active(TENANT)).thenReturn(Optional.of(everyPackOfJam()));
    Instant tooOld = clock.now.minus(Duration.ofHours(24)).minusSeconds(1);

    ApiException e = assertThrows(ApiException.class, () -> checks.check(jamSale(), tooOld));

    assertThat(e.code(), is("ORDER_LINE_RECALLED"));
    assertThat(e.getMessage(), containsString("too long ago to be taken on the till's word"));
    assertThat(e.getMessage(), containsString("Hand the sale to a manager."));
  }

  @Test
  void aReplayDatedAfterNowIsJudgedAsASaleMadeNow() {
    when(recalls.active(TENANT)).thenReturn(Optional.of(everyPackOfJam()));
    ApiException e =
        assertThrows(ApiException.class, () -> checks.check(jamSale(), clock.now.plusSeconds(30)));
    assertThat(e.getMessage(), containsString("Hand the sale to a manager."));
  }

  @Test
  void aTillClockALittleAheadIsHonouredAsCapturedNow() {
    SaleChecks tolerant =
        SaleChecks.forTest(
            recalls,
            tenants,
            staff,
            Duration.ofSeconds(10),
            clock,
            Duration.ofHours(24),
            Duration.ofSeconds(300));
    ActiveRecall open = openedAt(Ids.newId(), clock.now.minusSeconds(3600));
    when(recalls.openOrEndedSince(eq(TENANT), any())).thenReturn(Optional.of(List.of(open)));

    List<OfflineSaleFlag> flags = tolerant.check(jamSale(), clock.now.plusSeconds(300));

    assertThat("placed, and flagged", flags, hasSize(1));
    assertThat("as rung up now, never in the future", flags.get(0).rungUpAt(), is(clock.now));
    when(recalls.active(TENANT)).thenReturn(Optional.of(List.of(open)));
    ApiException beyond =
        assertThrows(
            ApiException.class, () -> tolerant.check(jamSale(), clock.now.plusSeconds(301)));
    assertThat(beyond.getMessage(), containsString("Hand the sale to a manager."));
  }

  @Test
  void theGraceIsTheConfiguredOne() {
    SaleChecks day =
        SaleChecks.forTest(recalls, tenants, Duration.ofSeconds(10), clock, Duration.ofHours(24));

    assertThat(day.timing(clock.now.minusSeconds(600)).rungUpAt(), is(clock.now.minusSeconds(600)));
    assertThat(day.timing(clock.now.minus(Duration.ofDays(3))), is(Timing.NOT_HONOURED));
    assertThat(day.timing(clock.now.plusSeconds(5)), is(Timing.NOT_HONOURED));
    assertThat(day.timing(null), is(Timing.LIVE));
    SaleChecks none =
        SaleChecks.forTest(recalls, tenants, Duration.ofSeconds(10), clock, Duration.ZERO);
    assertThat(
        "no grace, no replay honoured",
        none.timing(clock.now.minusSeconds(600)),
        is(Timing.NOT_HONOURED));
  }

  // ── who the entries name ──────────────────────────────────────────────────────

  @Test
  void aManagerSendingACashiersQueueLeavesEntriesNamingTheCashier() {
    ActiveRecall open = openedAt(Ids.newId(), clock.now.minusSeconds(3600));
    when(recalls.openOrEndedSince(eq(TENANT), any())).thenReturn(Optional.of(List.of(open)));
    when(staff.isStaffAt(TENANT, SHOP, CASHIER)).thenReturn(Optional.of(true));

    List<OfflineSaleFlag> flags =
        checks.check(jamSale(CASHIER, MANAGER), clock.now.minusSeconds(60));

    assertThat("who rang it up, as the till recorded", flags.get(0).cashierId(), is(CASHIER));
    assertThat("and who sent it", flags.get(0).replayedBy(), is(MANAGER));
  }

  @Test
  void aTillNamingSomebodyTheBusinessDoesNotHoldHereLeavesEntriesNamingNobody() {
    ActiveRecall open = openedAt(Ids.newId(), clock.now.minusSeconds(3600));
    when(recalls.openOrEndedSince(eq(TENANT), any())).thenReturn(Optional.of(List.of(open)));
    UUID stranger = Ids.newId();
    when(staff.isStaffAt(TENANT, SHOP, stranger)).thenReturn(Optional.of(false));

    List<OfflineSaleFlag> flags =
        checks.check(jamSale(stranger, MANAGER), clock.now.minusSeconds(60));

    assertThat("placed and flagged all the same", flags, hasSize(1));
    assertThat("an unknown member of staff", flags.get(0).cashierId(), is(nullValue()));
    assertThat(flags.get(0).replayedBy(), is(MANAGER));
  }

  @Test
  void aDirectoryThatCannotAnswerOrATillThatSaidNobodyLeavesEntriesNamingNobody() {
    ActiveRecall open = openedAt(Ids.newId(), clock.now.minusSeconds(3600));
    when(recalls.openOrEndedSince(eq(TENANT), any())).thenReturn(Optional.of(List.of(open)));
    when(staff.isStaffAt(TENANT, SHOP, CASHIER)).thenReturn(Optional.empty());

    assertThat(
        checks.check(jamSale(CASHIER, MANAGER), clock.now.minusSeconds(60)).get(0).cashierId(),
        is(nullValue()));
    assertThat(
        "a queue kept by an older till: nobody, never the sender by default",
        checks.check(jamSale(null, MANAGER), clock.now.minusSeconds(60)).get(0).cashierId(),
        is(nullValue()));
  }

  @Test
  void theDirectoryIsAskedOnlyWhenThereIsAnEntryAndTheTillNamedSomebodyElse() {
    when(recalls.openOrEndedSince(eq(TENANT), any())).thenReturn(Optional.of(List.of()));
    checks.check(jamSale(CASHIER, MANAGER), clock.now.minusSeconds(60));

    ActiveRecall open = openedAt(Ids.newId(), clock.now.minusSeconds(3600));
    when(recalls.openOrEndedSince(eq(TENANT), any())).thenReturn(Optional.of(List.of(open)));
    clock.now = clock.now.plusSeconds(11);
    List<OfflineSaleFlag> own = checks.check(jamSale(CASHIER, CASHIER), clock.now.minusSeconds(60));

    assertThat("the sender's own sign-in settles it", own.get(0).cashierId(), is(CASHIER));
    verify(staff, never()).isStaffAt(any(), any(), any());
  }

  // ── scales ─────────────────────────────────────────────────────────────────────

  @Test
  void aReplayWeighedAfterTheScaleLapsedIsPlacedAndFlagged() {
    // Due on 28 September, so overdue from midnight (UTC); verified and last changed a year before.
    Instant yearAgo = clock.now.minus(Duration.ofDays(365));
    Instant midnight = Instant.parse("2026-09-29T00:00:00Z");
    when(recalls.openOrEndedSince(eq(TENANT), any())).thenReturn(Optional.of(List.of()));
    when(tenants.weighingInstrument(TENANT, SHOP, DELI))
        .thenReturn(
            Optional.of(
                new Entry(
                    DELI,
                    true,
                    "Deli 1",
                    false,
                    "OVERDUE",
                    yearAgo,
                    LocalDate.parse("2026-09-28"),
                    yearAgo)));

    assertThat(
        "weighed before it lapsed: nothing to flag",
        checks.check(weighedSale(), midnight.minusSeconds(60)),
        is(empty()));
    List<OfflineSaleFlag> flags = checks.check(weighedSale(), midnight.plusSeconds(60));
    assertThat(flags, hasSize(1));
    assertThat(flags.get(0).kind(), is(OfflineSaleFlag.KIND_UNFIT_SCALE));
    assertThat(flags.get(0).instrumentId(), is(DELI));
    assertThat(flags.get(0).instrumentStanding(), is("OVERDUE"));
    assertThat(flags.get(0).variantId(), is(JAM));
    assertThat(flags.get(0).reason(), containsString("item 1 was weighed on Deli 1"));

    when(recalls.active(TENANT)).thenReturn(Optional.of(List.of()));
    ApiException now = assertThrows(ApiException.class, () -> checks.check(weighedSale(), null));
    assertThat("a sale made now is refused", now.code(), is("ORDER_SCALE_NOT_CERTIFIED"));
  }

  @Test
  void aReplayOnAScaleTheRegisterCannotShowWasFitThenIsFlaggedSayingSo() {
    Instant rungUp = clock.now.minusSeconds(600);
    Instant verified = clock.now.minusSeconds(60);
    when(recalls.openOrEndedSince(eq(TENANT), any())).thenReturn(Optional.of(List.of()));
    when(tenants.weighingInstrument(TENANT, SHOP, DELI))
        .thenReturn(
            Optional.of(
                new Entry(
                    DELI,
                    true,
                    "Deli 1",
                    true,
                    "CERTIFIED",
                    verified,
                    LocalDate.parse("2027-09-29"),
                    verified)));

    List<OfflineSaleFlag> flags = checks.check(weighedSale(), rungUp);

    assertThat("certified today, verified after the sale: flagged", flags, hasSize(1));
    assertThat(flags.get(0).instrumentStanding(), is(OfflineSaleFlag.UNKNOWN_AT_SALE));
    assertThat(flags.get(0).reason(), containsString("cannot be shown"));
    when(recalls.active(TENANT)).thenReturn(Optional.of(List.of()));
    assertThat("a sale made now on it is fit", checks.check(weighedSale(), null), is(empty()));
  }

  @Test
  void aReplayOnAScaleTheStoreDoesNotHoldIsPlacedAndFlagged() {
    when(recalls.openOrEndedSince(eq(TENANT), any())).thenReturn(Optional.of(List.of()));
    when(tenants.weighingInstrument(TENANT, SHOP, DELI))
        .thenReturn(Optional.of(Entry.notRegistered(DELI)));

    List<OfflineSaleFlag> flags = checks.check(weighedSale(), clock.now.minusSeconds(600));

    assertThat(flags, hasSize(1));
    assertThat(flags.get(0).instrumentStanding(), is(OfflineSaleFlag.NOT_REGISTERED));
  }

  @Test
  void aReplayWhoseRegisterCannotBeReadIsPlacedAndFlaggedInDoubt() {
    // Nothing readable: the recalls leave no entry, as there is no recall to name, but the scale
    // does. A replay is never refused, so doubt costs nothing and must not pass in silence.
    when(recalls.openOrEndedSince(eq(TENANT), any())).thenReturn(Optional.empty());
    when(recalls.active(TENANT)).thenReturn(Optional.empty());
    when(tenants.weighingInstrument(TENANT, SHOP, DELI)).thenReturn(Optional.empty());
    Instant rungUp = clock.now.minusSeconds(600);

    List<OfflineSaleFlag> flags = checks.check(weighedSale(), rungUp);

    assertThat("placed, and the weighed line flagged", flags, hasSize(1));
    OfflineSaleFlag flag = flags.get(0);
    assertThat(flag.kind(), is(OfflineSaleFlag.KIND_UNFIT_SCALE));
    assertThat(flag.instrumentId(), is(DELI));
    assertThat(flag.instrumentStanding(), is(OfflineSaleFlag.UNKNOWN_AT_SALE));
    assertThat(flag.variantId(), is(JAM));
    assertThat(flag.rungUpAt(), is(rungUp));
    assertThat(flag.cashierId(), is(CASHIER));
    assertThat(
        flag.reason(),
        containsString("because the register could not be read when the sale was synced."));
    assertThat("a sale made now still fails open", checks.check(weighedSale(), null), is(empty()));
  }
}
