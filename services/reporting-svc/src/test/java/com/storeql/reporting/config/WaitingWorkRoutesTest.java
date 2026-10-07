package com.storeql.reporting.config;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.reporting.domain.PendingWork.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The one map of admin-app routes covers every kind, so adding a kind cannot ship without one. */
class WaitingWorkRoutesTest {

  @Test
  @DisplayName("Every kind with a screen opens an admin route; a kind with none opens nothing")
  void everyKindWithAScreenHasARoute() {
    for (Kind kind : Kind.values()) {
      String route = WaitingWorkRoutes.OPENS.get(kind);
      if (kind == Kind.CARD_REFUND) {
        assertNull(route, "no refunds screen exists yet");
      } else {
        assertTrue(route != null && route.startsWith("/admin/"), kind + " opens " + route);
      }
    }
  }

  @Test
  @DisplayName("The map is read-only")
  void mapIsReadOnly() {
    assertThrows(
        UnsupportedOperationException.class,
        () -> WaitingWorkRoutes.OPENS.put(Kind.PAYMENT_RUN, "/elsewhere"));
  }
}
