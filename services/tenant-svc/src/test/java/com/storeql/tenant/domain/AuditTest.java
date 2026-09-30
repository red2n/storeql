package com.storeql.tenant.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.web.ApiException;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The change log's own rules: the types it knows, the window it accepts, the entries it writes. */
class AuditTest {

  @Test
  @DisplayName(
      "The type filter is read without regard to case, empty means none, and a stranger is refused by name")
  void typeFilter() {
    assertEquals("STAFF_ASSIGNED", Audit.type(" staff_assigned "));
    assertNull(Audit.type(null));
    assertNull(Audit.type("  "));
    ApiException e = assertThrows(ApiException.class, () -> Audit.type("STORE_EXPLODED"));
    assertEquals(400, e.status());
    assertEquals("AUDIT_TYPE_INVALID", e.code());
  }

  @Test
  @DisplayName("Every type the log writes is one the filter accepts")
  void everyTypeIsFilterable() {
    for (String t :
        Set.of(
            Audit.STORE_CREATED,
            Audit.STORE_STATUS_CHANGED,
            Audit.STORE_TILL_PHONE_CHANGED,
            Audit.STAFF_ASSIGNED,
            Audit.STAFF_UNASSIGNED,
            Audit.ROLE_DEFINED,
            Audit.ROLE_CHANGED,
            Audit.ROLE_DELETED)) {
      assertEquals(t, Audit.type(t));
    }
    assertEquals(8, Audit.TYPES.size());
  }

  @Test
  @DisplayName("A window that ends before it starts is refused; open ends and equal ends are fine")
  void window() {
    Instant a = Instant.parse("2026-01-01T00:00:00Z");
    Instant b = Instant.parse("2026-02-01T00:00:00Z");
    Audit.requireOrderedWindow(a, b);
    Audit.requireOrderedWindow(a, a);
    Audit.requireOrderedWindow(null, b);
    Audit.requireOrderedWindow(a, null);
    Audit.requireOrderedWindow(null, null);
    ApiException e = assertThrows(ApiException.class, () -> Audit.requireOrderedWindow(b, a));
    assertEquals("AUDIT_RANGE_INVALID", e.code());
  }

  @Test
  @DisplayName("An entry gets its own time-ordered id and a database-exact time")
  void entryIsStamped() {
    var e = Audit.Entry.of(Ids.newId(), Audit.ROLE_DEFINED, null, null, null, "X", null, "y");
    assertNotNull(Ids.parse(e.id().toString()));
    assertEquals(0, e.occurredAt().getNano() % 1000, "microseconds, as the column holds them");
    assertTrue(e.occurredAt().isAfter(Instant.now().minusSeconds(5)));
  }

  @Test
  @DisplayName("Permissions are written sorted, so two writings of one set read the same")
  void permissionsAreSorted() {
    assertEquals("a,b,c", Audit.permissions(Set.of("c", "a", "b")));
    assertEquals("", Audit.permissions(Set.of()));
  }
}
