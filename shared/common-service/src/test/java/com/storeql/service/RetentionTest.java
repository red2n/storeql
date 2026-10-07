package com.storeql.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.web.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The schedule a purger obeys: read whole or not at all, cached, and its holds answered. */
class RetentionTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID CUSTOMER = Ids.newId();
  private static final UUID ORDER = Ids.newId();

  private static final String SHEET =
      "{\"data\":{\"country\":\"GB\",\"countries\":[\"GB\"],\"classes\":["
          + "{\"code\":\"TRANSACTIONS\",\"floorDays\":2190,\"periodDays\":2190},"
          + "{\"code\":\"ORDER_PERSONAL_DATA\",\"periodDays\":0},"
          + "{\"code\":\"CUSTOMER_RECORDS\"},"
          + "{\"code\":\"NOTIFICATION_LOG\",\"periodDays\":365}],"
          + "\"holds\":["
          + "{\"id\":\"x\",\"dataClass\":\"CUSTOMER_RECORDS\",\"subjectKind\":\"CUSTOMER\",\"subjectId\":\""
          + CUSTOMER
          + "\"},"
          + "{\"id\":\"y\",\"subjectKind\":\"ORDER\",\"subjectId\":\""
          + ORDER
          + "\"},"
          + "{\"id\":\"z\",\"dataClass\":\"NOTIFICATION_LOG\",\"subjectKind\":\"ALL\"}]}}";

  @Test
  void aSheetIsReadWholeAndItsHoldsAnswered() {
    Retention r = Retention.forTest(t -> Optional.of(SHEET), Clock.systemUTC());
    Retention.Sheet sheet = r.sheet(TENANT);
    assertEquals(Optional.of(0), sheet.periodDays(Retention.ORDER_PERSONAL_DATA));
    assertEquals(Optional.empty(), sheet.periodDays(Retention.CUSTOMER_RECORDS));
    assertTrue(sheet.classHeld(Retention.NOTIFICATION_LOG));
    assertFalse(sheet.classHeld(Retention.CUSTOMER_RECORDS));
    // A hold on one class holds that class; a hold naming no class holds every class.
    assertEquals(Set.of(CUSTOMER), sheet.heldSubjects(Retention.CUSTOMER_RECORDS, "CUSTOMER"));
    assertEquals(Set.of(), sheet.heldSubjects(Retention.ORDER_PERSONAL_DATA, "CUSTOMER"));
    assertEquals(Set.of(ORDER), sheet.heldSubjects(Retention.ORDER_PERSONAL_DATA, "ORDER"));
    assertEquals(Set.of(ORDER), sheet.heldSubjects(Retention.TRANSACTIONS, "ORDER"));
  }

  @Test
  void everyPurgeReadsTheScheduleAfreshSoAHoldPlacedAMomentAgoIsObeyed() {
    AtomicInteger reads = new AtomicInteger();
    String[] body = {
      "{\"data\":{\"classes\":[{\"code\":\"NOTIFICATION_LOG\",\"periodDays\":365}],\"holds\":[]}}"
    };
    Retention r =
        Retention.forTest(
            t -> {
              reads.incrementAndGet();
              return Optional.of(body[0]);
            },
            Clock.systemUTC());
    boolean[] held = {true};
    Retention.Purge seeHold =
        (cutoff, classHeld, sheet, payload) -> {
          held[0] = classHeld;
          return new Retention.Counts(0, 0);
        };
    r.purge(TENANT, "test-svc", Retention.NOTIFICATION_LOG, seeHold);
    assertFalse(held[0]);
    // A hold on the whole class is placed; the very next purge obeys it.
    body[0] = SHEET;
    r.purge(TENANT, "test-svc", Retention.NOTIFICATION_LOG, seeHold);
    assertTrue(held[0]);
    assertEquals(2, reads.get());
  }

  @Test
  void aPurgeWithNoPeriodDoesNothingAndOneWithAPeriodCutsAtItAndAnnouncesItsCounts() {
    Instant now = Instant.parse("2026-09-15T03:00:00Z");
    Retention r = Retention.forTest(t -> Optional.of(SHEET), Clock.fixed(now, ZoneOffset.UTC));
    AtomicInteger calls = new AtomicInteger();
    assertTrue(
        r.purge(
                TENANT,
                "test-svc",
                Retention.CUSTOMER_RECORDS,
                (cutoff, classHeld, sheet, payload) -> {
                  calls.incrementAndGet();
                  return new Retention.Counts(1, 0);
                })
            .isEmpty());
    assertEquals(0, calls.get());

    Instant[] seen = new Instant[1];
    String[] announced = new String[1];
    Retention.Run run =
        r.purge(
                TENANT,
                "test-svc",
                Retention.NOTIFICATION_LOG,
                (cutoff, classHeld, sheet, payload) -> {
                  seen[0] = cutoff;
                  Retention.Counts counts = new Retention.Counts(4, 2);
                  announced[0] = Retention.announce("t", TENANT, payload).apply(counts).payload();
                  return counts;
                })
            .orElseThrow();
    assertEquals(now.minus(Duration.ofDays(365)), seen[0]);
    assertEquals(4, run.rowsAffected());
    assertEquals(2, run.heldSkipped());
    assertTrue(announced[0].contains("\"rowsAffected\":4"), announced[0]);
    assertTrue(announced[0].contains("\"heldSkipped\":2"), announced[0]);
    assertTrue(announced[0].contains("\"service\":\"test-svc\""), announced[0]);
  }

  @Test
  void aSheetThatCannotBeReadStopsThePurge() {
    Retention down = Retention.forTest(t -> Optional.empty(), Clock.systemUTC());
    ApiException e = assertThrows(ApiException.class, () -> down.sheet(TENANT));
    assertEquals(503, e.status());
    assertEquals("RETENTION_UNAVAILABLE", e.code());
    Retention garbled =
        Retention.forTest(t -> Optional.of("{\"data\":{\"classes\":[{}"), Clock.systemUTC());
    ApiException g = assertThrows(ApiException.class, () -> garbled.sheet(TENANT));
    assertEquals(503, g.status());
    assertEquals("RETENTION_UNAVAILABLE", g.code());
    Retention thin =
        Retention.forTest(
            t -> Optional.of("{\"data\":{\"classes\":[{\"code\":\"X\",\"periodDays\":1}]}}"),
            Clock.systemUTC());
    ApiException n = assertThrows(ApiException.class, () -> thin.sheet(TENANT));
    assertEquals(503, n.status());
    assertEquals("RETENTION_UNAVAILABLE", n.code());
  }

  @Test
  void aPurgeWithNoScheduleToObeyDeletesNothingAndAnnouncesNothing() {
    // The refusal is the purge's own, before the purging service is asked to touch a row: a
    // schedule that cannot be read whole must never be read as "nothing is held".
    AtomicInteger deleted = new AtomicInteger();
    Retention.Purge purger =
        (cutoff, classHeld, sheet, payload) -> {
          deleted.incrementAndGet();
          return new Retention.Counts(1, 0);
        };
    String[] unreadable = {null, "{\"data\":{\"classes\":[{}", "{\"data\":null}", "{}", "not json"};
    for (String body : unreadable) {
      Retention r = Retention.forTest(t -> Optional.ofNullable(body), Clock.systemUTC());
      ApiException e =
          assertThrows(
              ApiException.class,
              () -> r.purge(TENANT, "test-svc", Retention.NOTIFICATION_LOG, purger),
              String.valueOf(body));
      assertEquals(503, e.status(), String.valueOf(body));
      assertEquals("RETENTION_UNAVAILABLE", e.code(), String.valueOf(body));
    }
    assertEquals(0, deleted.get(), "no purge ran on a schedule that could not be read");
  }
}
