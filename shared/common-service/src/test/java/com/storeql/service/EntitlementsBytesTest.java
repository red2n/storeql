package com.storeql.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.web.ApiException;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A storage cap is "would this fit" (21.11): a write that lands exactly on the cap is taken, the
 * one past it is refused naming the plan's figure and what the business would then hold; no cap, or
 * a plan that cannot be read, holds nothing back.
 */
class EntitlementsBytesTest {

  private static final UUID TENANT = Ids.newId();

  private static Entitlements withImagesCap(long mb) {
    return Entitlements.forTest(
        t ->
            Optional.of(
                "{\"data\":{\"grants\":[{\"key\":\"images.mb.max\",\"limitValue\":" + mb + "}]}}"),
        Clock.systemUTC());
  }

  @Test
  void whatFitsExactlyIsTakenAndOneByteMoreIsRefused() {
    Entitlements e = withImagesCap(1);
    assertDoesNotThrow(
        () ->
            e.requireBytesWithin(
                TENANT, Entitlements.IMAGES_MB_MAX, "MB of product images", () -> 1024L * 1024L));
    ApiException refused =
        assertThrows(
            ApiException.class,
            () ->
                e.requireBytesWithin(
                    TENANT,
                    Entitlements.IMAGES_MB_MAX,
                    "MB of product images",
                    () -> 1024L * 1024L + 1));
    assertEquals(409, refused.status());
    assertEquals("PLAN_LIMIT_REACHED", refused.code());
    assertTrue(
        refused.getMessage().contains("allows 1 MB of product images"), refused.getMessage());
    assertTrue(refused.getMessage().contains("would make 1 MB"), refused.getMessage());
  }

  @Test
  void theRefusalSaysWhatTheBusinessWouldHoldInMegabytesAPersonCanRead() {
    Entitlements e = withImagesCap(5);
    ApiException refused =
        assertThrows(
            ApiException.class,
            () ->
                e.requireBytesWithin(
                    TENANT, Entitlements.IMAGES_MB_MAX, "MB of product images", () -> 5_557_452L));
    assertTrue(refused.getMessage().contains("would make 5.3 MB"), refused.getMessage());
    assertEquals("5.3 MB", Entitlements.megabytes(5_557_452L));
    assertEquals("0.2 MB", Entitlements.megabytes(204_800L));
    assertEquals("0 MB", Entitlements.megabytes(0L));
  }

  @Test
  void noCapAndAnUnreadablePlanBothHoldNothingBack() {
    Entitlements none =
        Entitlements.forTest(t -> Optional.of("{\"data\":{\"grants\":[]}}"), Clock.systemUTC());
    assertDoesNotThrow(
        () ->
            none.requireBytesWithin(
                TENANT, Entitlements.IMAGES_MB_MAX, "MB of product images", () -> Long.MAX_VALUE));
    Entitlements unreadable = Entitlements.forTest(t -> Optional.empty(), Clock.systemUTC());
    assertDoesNotThrow(
        () ->
            unreadable.requireBytesWithin(
                TENANT, Entitlements.DOCUMENTS_MB_MAX, "MB of documents", () -> Long.MAX_VALUE));
  }

  @Test
  void theNewKeysAreOnTheListAServiceChecksItselfAgainst() {
    assertTrue(Entitlements.keys().contains(Entitlements.REQUESTS_PER_MINUTE));
    assertTrue(Entitlements.keys().contains(Entitlements.IMAGES_MB_MAX));
    assertTrue(Entitlements.keys().contains(Entitlements.DOCUMENTS_MB_MAX));
  }
}
