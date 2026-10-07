package com.storeql.gateway.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The route pattern is the only part of an address a record keeps, so what must never survive is
 * tested as hard as what must.
 */
class RoutePatternTest {

  private static final Set<String> GROUPS =
      Set.of("order-svc", "payment-svc", "iam-svc", "reporting-svc", "system-health", "versions");

  private static final String WAITING_WORK =
      "reporting-svc/admin/reports/system-health/waiting-work";

  private static RoutePattern of(String path) {
    return RoutePattern.of(path, GROUPS);
  }

  @Test
  @DisplayName("Words stay, ids go: a UUID segment becomes {id}")
  void uuidsBecomeIds() {
    String id = Ids.newId().toString();
    RoutePattern r = of("/api/v1/order-svc/orders/" + id + "/lines");
    assertEquals("/api/v1/order-svc/orders/{id}/lines", r.pattern());
    assertEquals("order-svc", r.group());
    assertFalse(r.pattern().contains(id));
  }

  @Test
  @DisplayName(
      "Numbers, emails, phone numbers and mixed-case or digit-bearing tokens never survive")
  void personalValuesNeverSurvive() {
    for (String value :
        new String[] {
          "12345",
          "42",
          "a.b@example.com",
          "john.smith@x.co",
          "+447700900123",
          "447700900123",
          "Zm9vYmFy",
          "AbCdEf",
          "sk_live_9f8a7b6c5d",
          "9f8a7b6c-5d4e-3f2a-1b0c-9d8e7f6a5b4c",
          "2026-10-07",
          "O'Brien",
          "john smith",
          "a_b",
          ".well-known",
          "security.txt",
          "-leading-hyphen",
          "x".repeat(41),
          "x-".repeat(30)
        }) {
      String shown = of("/api/v1/order-svc/customers/" + value).pattern();
      assertEquals("/api/v1/order-svc/customers/{id}", shown, value);
    }
  }

  @Test
  @DisplayName("A word of up to 40 lowercase letters and hyphens is kept, a 41st character is not")
  void theWordBoundary() {
    String forty = "a" + "b".repeat(39);
    assertEquals("/api/v1/order-svc/" + forty, of("/api/v1/order-svc/" + forty).pattern());
    assertEquals("/api/v1/order-svc/{id}", of("/api/v1/order-svc/" + forty + "b").pattern());
    assertEquals(
        "/api/v1/order-svc/z-report/end-of-day",
        of("/api/v1/order-svc/z-report/end-of-day").pattern());
  }

  @Test
  @DisplayName("The query string, a matrix parameter and a fragment are never part of a pattern")
  void queryStringsDoNotSurvive() {
    RoutePattern r = of("/api/v1/order-svc/orders?email=a@b.c&token=SECRET");
    assertFalse(r.pattern().contains("SECRET"));
    assertFalse(r.pattern().contains("?"));
    assertEquals("/api/v1/order-svc/{id}", r.pattern(), "the whole '?...' tail is not a word");
    assertEquals(
        "/api/v1/order-svc/orders/{id}", of("/api/v1/order-svc/orders/a;jsessionid=Q").pattern());
  }

  @Test
  @DisplayName("The version segment is kept only where a version goes, right after /api")
  void versionSegmentIsKeptOnlyAfterApi() {
    assertEquals("/api/v1/order-svc/orders", of("/api/v1/order-svc/orders").pattern());
    assertEquals("/api/v12/order-svc/orders", of("/api/v12/order-svc/orders").pattern());
    assertEquals("/api/order-svc/orders", of("/api/order-svc/orders").pattern());
    assertEquals("/api/v1/order-svc/{id}", of("/api/v1/order-svc/v1").pattern());
  }

  @Test
  @DisplayName("Leading, trailing and doubled slashes do not change the pattern")
  void slashesAreNormalised() {
    assertEquals("/api/v1/order-svc/orders", of("api/v1/order-svc/orders/").pattern());
    assertEquals("/api/v1/order-svc/orders", of("//api//v1//order-svc//orders").pattern());
    assertEquals("/", of("").pattern());
    assertEquals("/", of("/").pattern());
    assertEquals("/", of(null).pattern());
  }

  @Test
  @DisplayName("The alias and the versioned form share one group")
  void groupPeelsApiAndVersion() {
    assertEquals("order-svc", of("/api/order-svc/orders").group());
    assertEquals("order-svc", of("/api/v1/order-svc/orders").group());
    assertEquals("order-svc", of("/api/v1/order-svc").group());
    assertEquals("versions", of("/api/versions").group());
    assertEquals("system-health", of("/api/v1/system-health/summary").group());
  }

  @Test
  @DisplayName(
      "A group is a name the gateway knows; an invented one is 'other', so groups cannot multiply")
  void unknownGroupsCollapse() {
    assertEquals(RoutePattern.OTHER, of("/api/v1/not-a-service/x").group());
    assertEquals(RoutePattern.OTHER, of("/api/v1/" + Ids.newId() + "/x").group());
    assertEquals(RoutePattern.OTHER, of("/api").group());
    assertEquals(RoutePattern.OTHER, of("/api/v1").group());
    assertEquals(RoutePattern.OTHER, of("/api/v1/").group());
  }

  @Test
  @DisplayName("What the gateway serves itself outside /api is the 'gateway' group")
  void gatewayOwnPaths() {
    assertEquals(RoutePattern.GATEWAY, of("/.well-known/security.txt").group());
    assertEquals(RoutePattern.GATEWAY, of("/admin/security/script-integrity").group());
    assertEquals(RoutePattern.GATEWAY, of("/nonsense").group());
    assertEquals(RoutePattern.GATEWAY, of("/").group());
  }

  @Test
  @DisplayName("The screen's own reads: the gateway's system-health routes, on any version")
  void theGatewaysOwnScreenRoutesAreScreenReads() {
    assertTrue(of("/api/v1/system-health/summary").isScreenRead());
    assertTrue(of("/api/v1/system-health/failures").isScreenRead());
    assertTrue(of("/api/v2/system-health/summary").isScreenRead());
    assertTrue(of("/api/system-health/summary").isScreenRead());
  }

  @Test
  @DisplayName("The waiting-work read is a screen read, versioned and on the unversioned alias")
  void theWaitingWorkReadIsAScreenRead() {
    assertTrue(of("/api/v1/" + WAITING_WORK).isScreenRead());
    assertTrue(of("/api/" + WAITING_WORK).isScreenRead());
    assertEquals(
        "reporting-svc", of("/api/v1/" + WAITING_WORK).group(), "still grouped by service");
  }

  @Test
  @DisplayName(
      "A trailing, leading or doubled slash does not hide the waiting-work read from the match")
  void slashesDoNotChangeTheMatch() {
    assertTrue(of("/api/v1/" + WAITING_WORK + "/").isScreenRead());
    assertTrue(of("api/v1/" + WAITING_WORK).isScreenRead());
    assertTrue(of("//api//v1//" + WAITING_WORK + "//").isScreenRead());
    assertTrue(of("/api/" + WAITING_WORK + "/").isScreenRead());
  }

  @Test
  @DisplayName(
      "Every other reporting-svc route is traffic, including the other /admin/reports ones")
  void otherReportingRoutesAreNotScreenReads() {
    for (String tail :
        new String[] {
          "reporting-svc",
          "reporting-svc/admin/reports/system-health",
          "reporting-svc/admin/reports/system-health/summary",
          "reporting-svc/admin/reports/system-health/waiting-work/" + Ids.newId(),
          "reporting-svc/admin/reports/system-health/waiting-work/extra",
          "reporting-svc/admin/reports/sales-summary",
          "reporting-svc/admin/reports/dashboard",
          "reporting-svc/admin/reports/waiting-work",
          "reporting-svc/admin/pending-work",
          "reporting-svc/reports/system-health/waiting-work",
          "reporting-svc/admin/system-health/waiting-work",
          "reporting-svc/admin/reports/" + Ids.newId() + "/waiting-work"
        }) {
      assertFalse(of("/api/v1/" + tail).isScreenRead(), "/api/v1/" + tail);
      assertFalse(of("/api/" + tail).isScreenRead(), "/api/" + tail);
    }
  }

  @Test
  @DisplayName("The same tail under another service, or none the gateway knows, is traffic")
  void theSameTailUnderAnotherServiceIsNotAScreenRead() {
    String tail = "/admin/reports/system-health/waiting-work";
    assertFalse(of("/api/v1/order-svc" + tail).isScreenRead());
    assertFalse(of("/api/payment-svc" + tail).isScreenRead());
    assertFalse(of("/api/v1/not-a-service" + tail).isScreenRead());
    assertFalse(of("/api/v1/versions" + tail).isScreenRead());
    assertFalse(of("/api/v1/" + Ids.newId() + tail).isScreenRead());
  }

  @Test
  @DisplayName("Look-alike paths are traffic: a different word, case, extension or prefix")
  void lookAlikesAreNotScreenReads() {
    for (String path :
        new String[] {
          "/api/v1/" + WAITING_WORK + "-x",
          "/api/v1/" + WAITING_WORK + "s",
          "/api/v1/" + WAITING_WORK.replace("waiting-work", "waiting_work"),
          "/api/v1/" + WAITING_WORK.replace("waiting-work", "Waiting-Work"),
          "/api/v1/" + WAITING_WORK.replace("system-health", "system-health2"),
          "/api/v1/" + WAITING_WORK.replace("reporting-svc", "reporting"),
          "/api/v1/" + WAITING_WORK + ".json",
          "/api/v1/" + WAITING_WORK + ";jsessionid=Q",
          "/api/v1/" + WAITING_WORK.replace("/admin", "/%61dmin"),
          "/" + WAITING_WORK,
          "/x/api/v1/" + WAITING_WORK,
          "/api/v1/v1/" + WAITING_WORK,
          "/api/v1/api/v1/" + WAITING_WORK,
          "/admin/security/" + WAITING_WORK,
          "/"
        }) {
      assertFalse(of(path).isScreenRead(), path);
    }
  }

  @Test
  @DisplayName(
      "A query string is never part of the path handed over; if one ever were, the read is counted, not hidden")
  void aQueryStringIsNeverMatchedByAccident() {
    // The filter passes UriInfo.getPath(), which has no query. A '?' inside a segment is not a
    // word, so such a request stays visible as traffic: the safe side of a match.
    assertFalse(of("/api/v1/" + WAITING_WORK + "?x=1").isScreenRead());
    assertFalse(of("/api/v1/" + WAITING_WORK + "?path=/system-health/summary").isScreenRead());
    assertFalse(of("/api/v1/order-svc/orders?a=" + WAITING_WORK).isScreenRead());
  }

  @Test
  @DisplayName(
      "The match is made on a record's pattern and group alone, so the store can ask it too")
  void theMatchNeedsOnlyThePatternAndGroup() {
    RoutePattern read = of("/api/v1/" + WAITING_WORK);
    assertTrue(RoutePattern.isScreenRead(read.pattern(), read.group()));
    assertTrue(RoutePattern.isScreenRead("/api/v1/system-health/summary", "system-health"));
    assertFalse(RoutePattern.isScreenRead(read.pattern(), RoutePattern.OTHER));
    assertFalse(RoutePattern.isScreenRead(read.pattern(), "order-svc"));
    assertFalse(RoutePattern.isScreenRead(null, null));
    assertFalse(RoutePattern.isScreenRead("/api/v1/order-svc/orders", "order-svc"));
  }

  @Test
  @DisplayName("A path with more than twelve segments is cut, so a pattern stays short")
  void longPathsAreCut() {
    String path = "/api/v1/order-svc" + "/a".repeat(40);
    RoutePattern r = of(path);
    assertEquals(
        RoutePattern.MAX_SEGMENTS + 1,
        r.pattern().substring(1).split("/").length,
        "twelve segments and the marker that says more followed");
    assertTrue(r.pattern().endsWith("/{rest}"), r.pattern());
    assertTrue(r.pattern().length() < 120, r.pattern());
  }

  @Test
  @DisplayName(
      "Every character of a pattern is a lowercase letter, a digit of a version, or - / { }")
  void aPatternHasOnlyTheseCharacters() {
    String nasty = "/api/v1/order-svc/%0d%0a/<script>/日本/..;/" + "\n" + "/\u0000/a b/c%2fd/Ünï";
    String p = of(nasty).pattern();
    assertTrue(p.matches("[a-z0-9{}/-]*"), p);
  }
}
