package com.storeql.pricing.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.pricing.domain.Domain.VatObligation;
import com.storeql.pricing.domain.Domain.VatRegistration;
import com.storeql.pricing.domain.Domain.VatReturn;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * HMRC's VAT (MTD) API as a stub: the token exchange, the obligations, the return — with the
 * versioned Accept header, the bearer token and the fraud-prevention headers pinned, because a
 * request missing any of them is one HMRC rejects.
 */
class HmrcMtdVatProviderTest {

  private HttpServer server;
  private final List<String> requests = new ArrayList<>();
  private final List<Headers> headers = new ArrayList<>();
  private final List<String> bodies = new ArrayList<>();
  private int returnsStatus = 201;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        ex -> {
          // Raw, as it crossed the wire: a decoded path would hide a query sent escaped.
          String path =
              ex.getRequestURI().getRawPath()
                  + (ex.getRequestURI().getRawQuery() == null
                      ? ""
                      : "?" + ex.getRequestURI().getRawQuery());
          requests.add(ex.getRequestMethod() + " " + path);
          headers.add(ex.getRequestHeaders());
          bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          int status = 200;
          String reply;
          if (path.equals("/oauth/token")) {
            reply =
                "{\"access_token\":\"acc\",\"refresh_token\":\"ref\",\"expires_in\":14400,\"token_type\":\"bearer\"}";
          } else if (path.startsWith("/organisations/vat/123456782/obligations")) {
            reply =
                "{\"obligations\":[{\"start\":\"2026-04-01\",\"end\":\"2026-06-30\",\"due\":\"2026-08-07\",\"status\":\"O\",\"periodKey\":\"26A2\"},{\"start\":\"2026-01-01\",\"end\":\"2026-03-31\",\"due\":\"2026-05-07\",\"status\":\"F\",\"received\":\"2026-04-20\",\"periodKey\":\"26A1\"}]}";
          } else if (path.equals("/organisations/vat/123456782/returns")) {
            status = returnsStatus;
            reply =
                status == 201
                    ? "{\"processingDate\":\"2026-09-12T10:00:00.000Z\",\"paymentIndicator\":\"BANK\",\"formBundleNumber\":\"256660290587\",\"chargeRefNumber\":\"aCxFaNx0FZsCvyWF\"}"
                    : "{\"code\":\"BUSINESS_ERROR\",\"message\":\"Business validation error\",\"errors\":[{\"code\":\"DUPLICATE_SUBMISSION\",\"message\":\"The VAT return was already submitted for the given period.\"}]}";
            ex.getResponseHeaders().add("Receipt-ID", "2dd537bc-4244-4ebf-bac9-96321be13cdc");
          } else {
            status = 404;
            reply = "{\"code\":\"MATCHING_RESOURCE_NOT_FOUND\"}";
          }
          byte[] out = reply.getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.sendResponseHeaders(status, out.length);
          try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
          }
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private static TokenCipher cipher() {
    TokenCipher c = new TokenCipher();
    c.use(Base64.getEncoder().encodeToString(new byte[16]));
    return c;
  }

  private HmrcMtdVatProvider provider(TokenCipher c) {
    return HmrcMtdVatProvider.forTest(
        "http://127.0.0.1:" + server.getAddress().getPort(), "client", "secret", c);
  }

  private static VatRegistration connected(TokenCipher c) {
    return new VatRegistration(
        Ids.newId(),
        "123456782",
        "HMRC",
        c.encrypt("acc"),
        c.encrypt("ref"),
        Instant.now().plusSeconds(3600),
        Instant.now(),
        null,
        null);
  }

  private static VatReturn boxes() {
    return new VatReturn(
        new BigDecimal("100.00"),
        BigDecimal.ZERO,
        new BigDecimal("100.00"),
        new BigDecimal("30.00"),
        new BigDecimal("70.00"),
        new BigDecimal("500"),
        new BigDecimal("150"),
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        "a",
        "b");
  }

  @Test
  void theAuthorizeUrlCarriesTheVatScopesAndTheRedirect() {
    String url = provider(cipher()).authorizeUrl("https://shop.example/admin/vat/hmrc", "tenant-1");
    assertTrue(url.startsWith("http://127.0.0.1:"));
    assertTrue(
        url.contains(
            "/oauth/authorize?response_type=code&client_id=client&scope=read%3Avat+write%3Avat&redirect_uri=https%3A%2F%2Fshop.example%2Fadmin%2Fvat%2Fhmrc&state=tenant-1"),
        url);
  }

  @Test
  void theCodeIsExchangedAsAFormPostAndTheTokensComeBack() {
    var t = provider(cipher()).exchangeCode("the-code", "https://shop.example/cb");
    assertEquals("acc", t.accessToken());
    assertEquals("ref", t.refreshToken());
    assertTrue(t.expiresAt().isAfter(Instant.now().plusSeconds(14000)));
    assertEquals("POST /oauth/token", requests.get(0));
    assertEquals("application/x-www-form-urlencoded", headers.get(0).getFirst("Content-Type"));
    assertTrue(
        bodies
            .get(0)
            .contains(
                "grant_type=authorization_code&code=the-code&redirect_uri=https%3A%2F%2Fshop.example%2Fcb&client_id=client&client_secret=secret"),
        bodies.get(0));
  }

  @Test
  void obligationsAreReadWithTheVersionedAcceptAndTheBearer() {
    TokenCipher c = cipher();
    List<VatObligation> o =
        provider(c)
            .obligations(
                connected(c),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-09-01T00:00:00Z"),
                Set.of());
    assertEquals(
        "GET /organisations/vat/123456782/obligations?from=2026-01-01&to=2026-09-01",
        requests.get(0));
    assertEquals("application/vnd.hmrc.1.0+json", headers.get(0).getFirst("Accept"));
    assertEquals("Bearer acc", headers.get(0).getFirst("Authorization"));
    assertEquals("WEB_APP_VIA_SERVER", headers.get(0).getFirst("Gov-Client-Connection-Method"));
    assertEquals(2, o.size());
    assertEquals("26A2", o.get(0).periodKey());
    assertEquals(VatObligation.STATUS_FULFILLED, o.get(1).status());
    assertEquals(Instant.parse("2026-04-20T00:00:00Z"), o.get(1).received());
  }

  @Test
  void theReturnIsFiledWithTheNineBoxesFinalisedAndTheFraudHeaders() {
    TokenCipher c = cipher();
    var r =
        provider(c)
            .submit(
                connected(c),
                "26A2",
                boxes(),
                Map.of(
                    "Gov-Client-Timezone",
                    "UTC+01:00",
                    "Gov-Client-Device-ID",
                    "dev-1",
                    "Gov-Client-Screens",
                    "width=1920&height=1080&scaling-factor=1&colour-depth=24"));
    assertEquals("POST /organisations/vat/123456782/returns", requests.get(0));
    String body = bodies.get(0);
    assertTrue(body.contains("\"periodKey\":\"26A2\""), body);
    assertTrue(body.contains("\"vatDueSales\":100.00"), body);
    assertTrue(body.contains("\"netVatDue\":70.00"), body);
    assertTrue(body.contains("\"totalValueSalesExVAT\":500"), body);
    assertTrue(body.contains("\"finalised\":true"), body);
    Headers h = headers.get(0);
    assertEquals("UTC+01:00", h.getFirst("Gov-Client-Timezone"));
    assertEquals("dev-1", h.getFirst("Gov-Client-Device-ID"));
    assertEquals(
        "width=1920&height=1080&scaling-factor=1&colour-depth=24",
        h.getFirst("Gov-Client-Screens"));
    assertEquals("StoreQL", h.getFirst("Gov-Vendor-Product-Name"));
    assertEquals("StoreQL=test", h.getFirst("Gov-Vendor-Version"));
    assertEquals("256660290587", r.formBundleNumber());
    assertEquals("BANK", r.paymentIndicator());
    assertEquals("aCxFaNx0FZsCvyWF", r.chargeRefNumber());
    assertEquals("2dd537bc-4244-4ebf-bac9-96321be13cdc", r.receiptId());
    assertEquals(Instant.parse("2026-09-12T10:00:00.000Z"), r.processingDate());
  }

  @Test
  void hmrcsRefusalComesBackAsItsOwnCode() {
    returnsStatus = 403;
    TokenCipher c = cipher();
    var e =
        assertThrows(
            VatSubmissionProvider.ProviderException.class,
            () -> provider(c).submit(connected(c), "26A2", boxes(), Map.of()));
    assertEquals("DUPLICATE_SUBMISSION", e.code());
    assertTrue(e.getMessage().contains("already submitted"));
    assertEquals(false, e.retryable());
  }

  @Test
  void withoutTheGrantNothingIsSent() {
    TokenCipher c = cipher();
    var reg =
        new VatRegistration(Ids.newId(), "123456782", "HMRC", null, null, null, null, null, null);
    var e =
        assertThrows(
            VatSubmissionProvider.ProviderException.class,
            () -> provider(c).submit(reg, "26A2", boxes(), Map.of()));
    assertEquals("HMRC_NOT_CONNECTED", e.code());
    assertEquals(0, requests.size());
  }
}
