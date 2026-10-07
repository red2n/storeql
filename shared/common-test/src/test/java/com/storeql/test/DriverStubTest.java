package com.storeql.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class DriverStubTest {

  private static HttpResponse<String> send(HttpClient c, HttpRequest r) throws Exception {
    return c.send(r, HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void recordsWhatWasSentAndAnswersWhatWasProgrammed() throws Exception {
    try (DriverStub api = DriverStub.start(r -> DriverStub.Reply.json(201, "{\"ok\":true}"))) {
      HttpRequest req =
          HttpRequest.newBuilder(URI.create(api.url() + "/v1/charges?expand=a%20b"))
              .header("Content-Type", "application/x-www-form-urlencoded")
              .header("Idempotency-Key", "k1")
              .POST(HttpRequest.BodyPublishers.ofString("amount=1250&currency=usd&note=a+b%26c"))
              .build();
      HttpResponse<String> res = send(HttpClient.newHttpClient(), req);
      assertEquals(201, res.statusCode());
      assertEquals("{\"ok\":true}", res.body());
      DriverStub.Recorded r = api.last();
      assertEquals("POST", r.method());
      assertEquals("/v1/charges", r.route());
      assertEquals("a b", r.query().get("expand").get(0));
      assertEquals("1250", r.form().get("amount").get(0));
      assertEquals("a b&c", r.form().get("note").get(0));
      assertEquals("k1", r.header("idempotency-key"));
      assertEquals(1, api.count());
    }
  }

  @Test
  void oneIdempotencyKeyPerCallIsCheckedBothWays() throws Exception {
    try (DriverStub api = DriverStub.start(r -> DriverStub.Reply.empty(204))) {
      HttpClient c = HttpClient.newHttpClient();
      for (String k : new String[] {"a", "b"}) {
        send(
            c,
            HttpRequest.newBuilder(URI.create(api.url() + "/x"))
                .header("Idempotency-Key", k)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
      }
      api.assertOneIdempotencyKeyPerCall("Idempotency-Key");
      send(
          c,
          HttpRequest.newBuilder(URI.create(api.url() + "/x"))
              .header("Idempotency-Key", "a")
              .POST(HttpRequest.BodyPublishers.noBody())
              .build());
      assertThrows(
          AssertionError.class, () -> api.assertOneIdempotencyKeyPerCall("Idempotency-Key"));
      assertEquals(java.util.List.of("a", "b", "a"), api.idempotencyKeys("Idempotency-Key"));
    }
  }

  @Test
  void aMissingKeyIsRefused() throws Exception {
    try (DriverStub api = DriverStub.start(r -> DriverStub.Reply.empty(204))) {
      send(
          HttpClient.newHttpClient(),
          HttpRequest.newBuilder(URI.create(api.url() + "/x"))
              .POST(HttpRequest.BodyPublishers.noBody())
              .build());
      assertThrows(
          AssertionError.class, () -> api.assertOneIdempotencyKeyPerCall("Idempotency-Key"));
    }
  }

  @Test
  void minorUnitsFollowTheCurrencyNotAnAssumption() {
    assertEquals(1250, DriverStub.minorUnits(new BigDecimal("12.50"), "USD"));
    assertEquals(1250, DriverStub.minorUnits(new BigDecimal("1250"), "JPY"));
    assertEquals(12500, DriverStub.minorUnits(new BigDecimal("12.500"), "KWD"));
    DriverStub.assertMinorUnits("1250", new BigDecimal("12.5"), "EUR");
    assertThrows(
        AssertionError.class,
        () -> DriverStub.assertMinorUnits("12.50", new BigDecimal("12.50"), "EUR"));
    assertThrows(
        ArithmeticException.class, () -> DriverStub.minorUnits(new BigDecimal("1.234"), "USD"));
  }

  @Test
  void hmacIsComputedAndCheckedOverTheRawBody() throws Exception {
    String key = "k-" + com.storeql.ids.Ids.newId();
    String body = "{\"a\": 1,  \"b\":[2]}";
    try (DriverStub api = DriverStub.start(r -> DriverStub.Reply.empty(200))) {
      send(
          HttpClient.newHttpClient(),
          HttpRequest.newBuilder(URI.create(api.url() + "/hook"))
              .header("X-Signature", "sha256=" + Hmac.sha256Hex(key, body))
              .header("X-Signed", "1700000000 " + Hmac.sha256Hex(key, "1700000000." + body))
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build());
      DriverStub.Recorded r = api.last();
      assertTrue(r.hmacHexMatches("X-Signature", key, "sha256="));
      assertFalse(r.hmacHexMatches("X-Signature", key + "x", "sha256="));
      assertFalse(r.hmacHexMatches("X-Signature", key, "v1="));
      assertFalse(r.hmacHexMatches("X-Missing", key, ""));
    }
    assertTrue(Hmac.verifyHex("k", "m", Hmac.sha256Hex("k", "m").toUpperCase()));
    assertFalse(Hmac.verifyHex("k", "m2", Hmac.sha256Hex("k", "m")));
    assertTrue(Hmac.verifyBase64("k", "m", Hmac.sha256Base64("k", "m")));
    assertFalse(Hmac.verifyBase64("k", "m", null));
    // RFC 4231 test case 2
    assertEquals(
        "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
        Hmac.sha256Hex("Jefe", "what do ya want for nothing?"));
  }

  @Test
  void timestampedSignaturesAreChecked() throws Exception {
    String key = "k";
    String body = "{}";
    try (DriverStub api = DriverStub.start(r -> DriverStub.Reply.empty(200))) {
      send(
          HttpClient.newHttpClient(),
          HttpRequest.newBuilder(URI.create(api.url() + "/hook"))
              .header("X-Sig", "v1=" + Hmac.sha256Hex(key, "17." + body))
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build());
      assertTrue(api.last().hmacHexMatchesWithTimestamp("X-Sig", key, "v1=", "17"));
      assertFalse(api.last().hmacHexMatchesWithTimestamp("X-Sig", key, "v1=", "18"));
    }
  }

  @Test
  void tlsStubUsesAThrowawayCertificateOnlyItsClientTrusts() throws Exception {
    try (DriverStub api = DriverStub.startTls(r -> DriverStub.Reply.text(200, "secure"))) {
      assertTrue(api.url().startsWith("https://localhost:"));
      HttpRequest req = HttpRequest.newBuilder(URI.create(api.url() + "/t")).GET().build();
      HttpResponse<String> ok =
          send(HttpClient.newBuilder().sslContext(api.clientSslContext()).build(), req);
      assertEquals("secure", ok.body());
      assertThrows(Exception.class, () -> send(HttpClient.newHttpClient(), req));
      assertEquals("/t", api.last().route());
    }
  }

  @Test
  void aStubWithoutTlsHasNoClientContext() {
    try (DriverStub api = DriverStub.start(r -> DriverStub.Reply.empty(200))) {
      assertThrows(IllegalStateException.class, api::clientSslContext);
    }
  }

  @Test
  void answersCanChangeAndAThrowingAnswerIsA500() throws Exception {
    try (DriverStub api = DriverStub.start(r -> DriverStub.Reply.empty(200))) {
      HttpClient c = HttpClient.newHttpClient();
      HttpRequest req = HttpRequest.newBuilder(URI.create(api.url() + "/a")).GET().build();
      assertEquals(200, send(c, req).statusCode());
      api.answerWith(r -> DriverStub.Reply.json(429, "{}").withHeader("Retry-After", "3"));
      HttpResponse<String> limited = send(c, req);
      assertEquals(429, limited.statusCode());
      assertEquals("3", limited.headers().firstValue("Retry-After").orElseThrow());
      api.answerWith(
          r -> {
            throw new IllegalStateException("boom");
          });
      assertEquals(500, send(c, req).statusCode());
      assertEquals(3, api.requests().size());
    }
  }
}
