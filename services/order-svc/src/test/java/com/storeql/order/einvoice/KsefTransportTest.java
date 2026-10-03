package com.storeql.order.einvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.einvoice.EInvoices;
import com.storeql.einvoice.Invoice;
import com.storeql.ids.Ids;
import com.storeql.order.domain.EInvoiceTransports;
import com.storeql.order.domain.SalesInvoiceDraft;
import com.storeql.order.domain.SalesInvoiceDraft.Address;
import com.storeql.order.domain.SalesInvoiceDraft.Buyer;
import com.storeql.order.domain.SalesInvoiceDraft.Document;
import com.storeql.order.domain.SalesInvoiceDraft.Line;
import com.storeql.order.domain.SalesInvoiceDraft.Seller;
import com.storeql.order.einvoice.EInvoiceTransport.Dispatch;
import com.storeql.order.einvoice.EInvoiceTransport.Outbound;
import com.storeql.order.einvoice.EInvoiceTransport.Outcome;
import com.storeql.order.einvoice.EInvoiceTransport.TransportException;
import com.storeql.order.support.Checks;
import com.storeql.order.support.KsefStub;
import com.storeql.test.JsonStub;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * KSeF's sign-in, session and status, against a stub system doing the ministry's own cryptography:
 * the token and the session key opened with its private key, the document decrypted and its hashes
 * checked, the answer as the system's statuses.
 */
class KsefTransportTest {

  private static final String NIP = "5260250274";
  private static final String TOKEN = "ksef-token-1";

  private static JsonStub stub;
  private static KsefStub system;
  private static KsefTransport transport;
  private static String ubl;

  @BeforeAll
  static void start() {
    stub = JsonStub.start();
    system = KsefStub.on(stub, NIP, TOKEN);
    transport = KsefTransport.forTest(stub.baseUrl() + KsefStub.PREFIX, "StoreQL test");
    Invoice inv =
        SalesInvoiceDraft.build(
            new Document(
                SalesInvoiceDraft.TYPE_INVOICE,
                "FV/2026/000001",
                LocalDate.of(2026, 9, 16),
                "PLN",
                "order-1",
                null,
                null,
                "Płatne w terminie 14 dni",
                BigDecimal.ZERO,
                new BigDecimal("246.00"),
                false),
            new Seller(
                "Sklep Portowy sp. z o.o.",
                null,
                "PL" + NIP,
                null,
                null,
                new Address("ul. Portowa 1", null, "Gdańsk", "80-001", null, "PL")),
            new Buyer(
                "Kawiarnia Molo sp. z o.o.",
                "PL7740001454",
                null,
                null,
                new Address("ul. Długa 2", null, "Gdańsk", "80-002", null, "PL"),
                false),
            List.of(
                new Line(
                    "Kawa ziarnista 1 kg",
                    "KAWA-1",
                    null,
                    new BigDecimal("2"),
                    "KGM",
                    new BigDecimal("200.00"),
                    new BigDecimal("23"),
                    null)));
    ubl = EInvoices.toUbl(inv);
  }

  @AfterAll
  static void stop() {
    stub.close();
  }

  private static Outbound document(String secret) {
    return new Outbound(
        Ids.newId(),
        Ids.newId(),
        "Invoice",
        "FV/2026/000001",
        null,
        null,
        ubl,
        null,
        "PL" + NIP,
        null,
        secret);
  }

  @Test
  void signsInWithTheTokenOpensASessionSendsTheDocumentAsFa3AndIsGivenAKsefNumberWhenAsked() {
    system.mode("accept");
    Dispatch d = transport.send(document(TOKEN));
    assertEquals(EInvoiceTransports.STATUS_PENDING, d.outcome().state());
    assertEquals("SES-1/INV-1", d.providerRef());
    assertTrue(system.lastInvoice().contains("<NIP>" + NIP + "</NIP>"));
    assertTrue(system.lastInvoice().contains("<P_12>23</P_12>"));
    assertTrue(system.lastInvoice().contains("<P_15>246.00</P_15>"));
    Outcome first = transport.status(document(TOKEN), "SES-1/INV-1");
    assertEquals(EInvoiceTransports.STATUS_PENDING, first.state());
    Outcome done = transport.status(document(TOKEN), "SES-1/INV-1");
    assertEquals(EInvoiceTransports.STATUS_ACCEPTED, done.state());
    assertEquals("5260250274-20260916-010203ABCDEF-01", done.reference());
    assertTrue(done.detail().startsWith("KSeF number 5260250274-20260916-010203ABCDEF-01"));
    // Once numbered, nothing is left to ask.
    assertEquals(
        EInvoiceTransports.STATUS_ACCEPTED,
        transport.status(document(TOKEN), done.reference()).state());
    // The access token is kept: a second document signs in no more.
    int signIns = system.signIns();
    transport.send(document(TOKEN));
    assertEquals(signIns, system.signIns());
  }

  @Test
  void theSystemsRefusalIsFinalADuplicateIsItsNumberAndItsOwnErrorIsAskedAgain() {
    system.mode("reject");
    Outcome refused = transport.status(document(TOKEN), "SES-1/INV-1");
    assertEquals(EInvoiceTransports.STATUS_REJECTED, refused.state());
    assertTrue(refused.detail().contains("450"));
    assertTrue(refused.detail().contains("P_15 niezgodne"));
    system.mode("duplicate");
    Outcome dup = transport.status(document(TOKEN), "SES-1/INV-1");
    assertEquals(EInvoiceTransports.STATUS_ACCEPTED, dup.state());
    assertEquals("5260250274-20260916-0A1B2C3D4E5F-01", dup.reference());
    system.mode("flaky");
    assertThrows(TransportException.class, () -> transport.status(document(TOKEN), "SES-1/INV-1"));
    system.mode("down");
    assertThrows(TransportException.class, () -> transport.send(document(TOKEN)));
    system.mode("accept");
  }

  @Test
  void aCheckSignsInAndStopsThereTellingARefusedTokenFromAMinistryThatIsDown() {
    system.mode("accept");
    int invoicesBefore = system.sessionsOpened();
    EInvoiceTransport.Readiness ready = transport.check(document(TOKEN));
    assertEquals(EInvoiceTransport.Readiness.READY, ready.state());
    assertEquals("KSeF signed the business in", ready.detail());
    assertEquals(
        invoicesBefore, system.sessionsOpened(), "a check opens no session for a document");

    EInvoiceTransport.Readiness wrong = transport.check(document("not-the-token"));
    assertEquals(EInvoiceTransport.Readiness.REFUSED, wrong.state());
    assertTrue(wrong.detail().contains("refused the business's token"));
    EInvoiceTransport.Readiness none = transport.check(document(null));
    assertEquals(EInvoiceTransport.Readiness.REFUSED, none.state());
    assertTrue(none.detail().contains("none is held"));

    // A business KSeF cannot know: its VAT number is not a NIP, so nothing is asked at all.
    Outbound english = Checks.credentials(null, "GB123456789", null, TOKEN);
    EInvoiceTransport.Readiness notPolish = transport.check(english);
    assertEquals(EInvoiceTransport.Readiness.REFUSED, notPolish.state());
    assertTrue(notPolish.detail().contains("not a Polish number"));

    // Nothing listening where the ministry should be: a wait, not something a person can fix.
    KsefTransport nowhere = KsefTransport.forTest("http://127.0.0.1:1", "StoreQL test");
    assertEquals(EInvoiceTransport.Readiness.UNREACHABLE, nowhere.check(document(TOKEN)).state());
  }

  @Test
  void aWrongTokenAndMissingPiecesAreRefusedWithoutARetry() {
    Dispatch wrong = transport.send(document("not-the-token"));
    assertEquals(EInvoiceTransports.STATUS_REJECTED, wrong.outcome().state());
    assertTrue(wrong.outcome().detail().contains("refused the business's token"));
    assertNull(wrong.providerRef());
    Dispatch none = transport.send(document(null));
    assertEquals(EInvoiceTransports.STATUS_REJECTED, none.outcome().state());
    assertTrue(transport.needsSecret());
    assertTrue(transport.isConfigured());
    assertFalse(KsefTransport.forTest("", "x").isConfigured());
    assertEquals("KSEF", transport.name());
  }
}
