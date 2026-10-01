package com.storeql.order.fiscal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.order.domain.Domain.TseDevice;
import com.storeql.order.domain.Domain.TseStamp;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The cloud module is driven over HTTP in the shape the market's cloud TSEs share. A stub server
 * plays the provider so the request bodies and the parsing of what comes back are pinned.
 */
class CloudTseProviderTest {

  private HttpServer server;
  private final List<String> paths = new ArrayList<>();
  private final List<String> bodies = new ArrayList<>();
  private int finishStatus = 200;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        ex -> {
          String path =
              ex.getRequestURI().getPath()
                  + (ex.getRequestURI().getQuery() == null
                      ? ""
                      : "?" + ex.getRequestURI().getQuery());
          String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          paths.add(ex.getRequestMethod() + " " + path);
          bodies.add(body);
          String reply;
          int status = 200;
          if (path.equals("/auth")) {
            reply =
                body.contains("\"api_key\":\"key\"")
                    ? "{\"access_token\":\"tok\",\"access_token_expires_in\":300}"
                    : "{\"error\":\"no\"}";
            status = body.contains("\"api_key\":\"key\"") ? 200 : 401;
          } else if (path.startsWith("/tss/tss-1/tx/") && path.endsWith("tx_revision=1")) {
            reply = "{\"state\":\"ACTIVE\"}";
          } else if (path.startsWith("/tss/tss-1/tx/") && path.endsWith("tx_revision=2")) {
            status = finishStatus;
            reply =
                status == 200
                    ? "{\"number\":17,\"time_start\":1757671200,\"time_end\":1757671205,"
                        + "\"tss_serial_number\":\"serial-1\","
                        + "\"signature\":{\"value\":\"SIGVALUE\",\"counter\":99,"
                        + "\"algorithm\":\"ecdsa-plain-SHA256\",\"public_key\":\"PUB\"},"
                        + "\"log\":{\"timestamp_format\":\"unixTime\"},"
                        + "\"qr_code_data\":\"V0;till;Kassenbeleg-V1;x;17;99;a;b;c;d;SIGVALUE;PUB\"}"
                    : "{\"code\":\"E_TSS_DISABLED\"}";
          } else if (path.equals("/tss/tss-1")) {
            reply =
                "{\"serial_number\":\"serial-1\",\"public_key\":\"PUB\",\"signature_algorithm\":\"ecdsa-plain-SHA256\",\"signature_timestamp_format\":\"unixTime\"}";
          } else {
            status = 404;
            reply = "{}";
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

  private CloudTseProvider provider(String key) {
    return CloudTseProvider.forTest(
        "http://127.0.0.1:" + server.getAddress().getPort(), key, "secret");
  }

  private static TseDevice device() {
    return new TseDevice(
        Ids.newId(),
        Ids.newId(),
        Ids.newId(),
        "CLOUD",
        "till",
        "serial-1",
        "PUB",
        "ecdsa-plain-SHA256",
        "unixTime",
        null,
        "tss-1",
        0,
        0,
        Instant.now(),
        null);
  }

  private static SaleFigures sale() {
    return new SaleFigures(
        Ids.newId(),
        "EUR",
        Instant.parse("2026-09-12T10:00:00Z"),
        List.of(
            new SaleFigures.RateAmount(new BigDecimal("19.00"), new BigDecimal("11.90")),
            new SaleFigures.RateAmount(new BigDecimal("7.00"), new BigDecimal("2.14"))),
        List.of(
            new SaleFigures.TenderAmount("CASH", new BigDecimal("10.00")),
            new SaleFigures.TenderAmount("CARD", new BigDecimal("4.04"))));
  }

  @Test
  void theBearerTokenIsReusedUntilItExpiresNotFetchedPerSale() {
    CloudTseProvider p = provider("key");
    p.sign(device(), sale());
    p.sign(device(), sale());
    p.sign(device(), sale());
    assertEquals(1, paths.stream().filter(x -> x.equals("POST /auth")).count());
    assertEquals(6, paths.stream().filter(x -> x.startsWith("PUT ")).count());
  }

  @Test
  void aSaleIsOpenedThenFinishedWithAmountsPerRateAndPerPaymentType() {
    TseStamp stamp = provider("key").sign(device(), sale());

    assertEquals(3, paths.size(), paths.toString());
    assertEquals("POST /auth", paths.get(0));
    assertTrue(
        paths.get(1).matches("PUT /tss/tss-1/tx/[0-9a-f-]{36}\\?tx_revision=1"), paths.get(1));
    assertTrue(
        paths.get(2).matches("PUT /tss/tss-1/tx/[0-9a-f-]{36}\\?tx_revision=2"), paths.get(2));
    assertTrue(bodies.get(1).contains("\"state\":\"ACTIVE\""));
    String finish = bodies.get(2);
    assertTrue(finish.contains("\"state\":\"FINISHED\""), finish);
    assertTrue(finish.contains("\"receipt_type\":\"RECEIPT\""), finish);
    assertTrue(finish.contains("{\"vat_rate\":\"NORMAL\",\"amount\":\"11.90\"}"), finish);
    assertTrue(finish.contains("{\"vat_rate\":\"REDUCED_1\",\"amount\":\"2.14\"}"), finish);
    assertTrue(finish.contains("{\"payment_type\":\"CASH\",\"amount\":\"10.00\"}"), finish);
    assertTrue(finish.contains("{\"payment_type\":\"NON_CASH\",\"amount\":\"4.04\"}"), finish);

    assertEquals(17L, stamp.transactionNumber());
    assertEquals(99L, stamp.signatureCounter());
    assertEquals("SIGVALUE", stamp.signature());
    assertEquals("serial-1", stamp.serialNumber());
    assertEquals(Instant.ofEpochSecond(1757671200), stamp.startedAt());
    assertEquals(Instant.ofEpochSecond(1757671205), stamp.finishedAt());
    assertEquals("Beleg^11.90_2.14_0.00_0.00_0.00^10.00:Bar_4.04:Unbar", stamp.processData());
    assertTrue(stamp.qr().startsWith("V0;till;"));
    assertEquals(null, stamp.error());
  }

  @Test
  void aRefusedTransactionIsAnExceptionTheModuleRecordsRatherThanASale() {
    finishStatus = 422;
    var e =
        assertThrows(TseProvider.TseException.class, () -> provider("key").sign(device(), sale()));
    assertTrue(e.getMessage().contains("422"), e.getMessage());
  }

  @Test
  void wrongCredentialsFailBeforeAnyTransactionIsOpened() {
    var e =
        assertThrows(
            TseProvider.TseException.class, () -> provider("wrong").sign(device(), sale()));
    assertTrue(e.getMessage().contains("credentials"), e.getMessage());
    assertEquals(1, paths.size());
  }

  @Test
  void registeringReadsTheDeviceFromTheProvider() {
    TseDevice d =
        provider("key")
            .register(
                new TseProvider.RegistrationRequest(
                    Ids.newId(), Ids.newId(), "till-2", "tss-1", null));
    assertEquals("serial-1", d.serialNumber());
    assertEquals("PUB", d.publicKey());
    assertEquals("tss-1", d.externalTssId());
    assertEquals("till-2", d.clientId());
    assertEquals(null, d.privateKey());
  }

  @Test
  void registeringWithoutAnIdOrWithoutCredentialsIsRefusedByName() {
    var noId =
        assertThrows(
            com.storeql.web.ApiException.class,
            () ->
                provider("key")
                    .register(
                        new TseProvider.RegistrationRequest(
                            Ids.newId(), Ids.newId(), "t", "", null)));
    assertEquals("FISCAL_TSE_ID_REQUIRED", noId.code());
    var unconfigured = CloudTseProvider.forTest("http://127.0.0.1:1", "", "");
    var noCreds =
        assertThrows(
            com.storeql.web.ApiException.class,
            () ->
                unconfigured.register(
                    new TseProvider.RegistrationRequest(
                        Ids.newId(), Ids.newId(), "t", "tss-1", null)));
    assertEquals("FISCAL_TSE_CLOUD_NOT_CONFIGURED", noCreds.code());
  }
}
