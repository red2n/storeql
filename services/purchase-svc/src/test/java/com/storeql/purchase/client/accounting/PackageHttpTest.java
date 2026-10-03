package com.storeql.purchase.client.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one exchange every package shares, against a stub: a request the connection's settings cannot
 * form is refused before anything leaves — never retried by the clock, never a raw exception that
 * would leave a claimed push with nobody told.
 */
class PackageHttpTest {

  private static PackageStub stub;
  private static PackageHttp http;

  @BeforeAll
  static void start() {
    stub = PackageStub.start(r -> new PackageStub.Answer(200, "{\"ok\":true}"));
    http = new PackageHttp(Duration.ofSeconds(5));
  }

  @AfterAll
  static void stop() {
    stub.close();
  }

  @BeforeEach
  void forget() {
    stub.requests.clear();
  }

  @Test
  @DisplayName(
      "An address that cannot be formed is refused unsent: status 0, not retryable, nothing on the wire")
  void anAddressThatCannotBeFormedIsRefusedUnsent() {
    for (String path :
        new String[] {
          "/v3/company/91 30/journalentry?minorversion=75",
          "/v3/company/9130%/journalentry?minorversion=75",
          "/v3/company/91\"30/query?query=x"
        }) {
      AccountingPackage.Refused refused =
          assertThrows(
              AccountingPackage.Refused.class,
              () -> http.send("POST", stub.url() + path, Map.of(), "application/json", "{}"),
              path);
      assertEquals(AccountingPackage.Refused.UNSENT, refused.status(), path);
      assertFalse(refused.sent(), "nothing left: " + path);
      assertFalse(refused.retryable(), "the clock cannot mend a setting: " + path);
      assertTrue(refused.getMessage().contains("address"), refused.getMessage());
    }
    assertEquals(0, stub.all().size(), "the package was never asked");
  }

  @Test
  @DisplayName(
      "A header holding a line break is refused unsent, and the refusal does not repeat its value")
  void aHeaderWithALineBreakIsRefusedUnsent() {
    String secret = "tok-" + System.nanoTime();
    AccountingPackage.Refused refused =
        assertThrows(
            AccountingPackage.Refused.class,
            () ->
                http.send(
                    "GET",
                    stub.url() + "/accounts",
                    Map.of("Authorization", "Bearer " + secret + "\r\nX-Injected: 1"),
                    null,
                    null));
    assertFalse(refused.sent());
    assertFalse(refused.retryable());
    assertTrue(refused.getMessage().contains("Authorization"), refused.getMessage());
    assertFalse(refused.getMessage().contains(secret), "a token is never echoed into a log");
    assertEquals(0, stub.all().size(), "the package was never asked");
  }

  @Test
  @DisplayName("A well-formed request still goes as written: its query is a query, not the path")
  void aWellFormedRequestGoesAsWritten() {
    PackageHttp.Reply reply =
        http.send(
            "POST",
            stub.url() + "/v3/company/9130/journalentry?minorversion=75&requestid=abc",
            Map.of("Accept", "application/json"),
            "application/json",
            "{}");
    assertEquals(200, reply.status());
    assertEquals(1, stub.all().size());
    assertEquals("/v3/company/9130/journalentry?minorversion=75&requestid=abc", stub.last().path());
    assertEquals("application/json", stub.last().header("Content-Type"));
    assertEquals(PackageHttp.USER_AGENT, stub.last().header("User-Agent"));
  }
}
