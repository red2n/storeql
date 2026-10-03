package com.storeql.purchase;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.purchase.messaging.ConsignmentEventHandler;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every record bound to a store — an order and what hangs off it (its lines, trail, receipts,
 * invoices, returns, landed costs), a request for quotes, an intercompany invoice, a journal — is
 * read and changed at its own store: a caller held to other stores is refused {@code 403
 * STORE_ACCESS_DENIED} before anything moves, and lists hold to the caller's stores. What gathers
 * every store's records — a consignment statement, a dropship arrangement — is made only by the
 * whole business ({@code 403 BUSINESS_WIDE_ONLY}); a statement is read by whoever may read every
 * sale in it. Another business, of any role, finds none of it, and a shopper is turned away.
 */
@HelidonTest
class StoreScopeIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("purchase");

  static {
    // No spend authority configured: an owner's submission goes straight to the supplier.
    System.setProperty("storeql.purchase.approval.limits", "");
    TenantSvcStub.start()
        .with(PurchaseFixtures.T, "GBP", "GB")
        .with(PurchaseFixtures.T2, "GBP", "GB");
  }

  private static final String T = PurchaseFixtures.T;
  private static final String T2 = PurchaseFixtures.T2;
  private static final String STORE_A = PurchaseFixtures.STORE_A;
  private static final String STORE_B = Ids.newId().toString();
  private static final String STORE_C = Ids.newId().toString();
  private static final String VARIANT = PurchaseFixtures.VARIANT;
  private static final String USER = PurchaseFixtures.USER;

  @Inject WebTarget target;
  @Inject ConsignmentEventHandler consignment;

  @AfterAll
  static void stopDb() {
    PG.stop();
    System.clearProperty("storeql.purchase.approval.limits");
  }

  @BeforeEach
  void clean() throws Exception {
    PurchaseFixtures.truncateAll(PG);
  }

  // ── what one store of ours has on its books ─────────────────────────────────

  /** The records a store-A order leaves behind, as an owner of the whole business makes them. */
  private record AtStoreA(
      String supplier,
      String po,
      String gr,
      String invoice,
      String ret,
      String charge,
      String rfq,
      String ar,
      String ap) {}

  private AtStoreA storeA() {
    String supplier =
        Envelopes.created(
                owner("POST", "/suppliers", "{\"name\":\"Scope Ltd\",\"currency\":\"GBP\"}"))
            .getString("id");
    String po =
        Envelopes.created(owner("POST", "/purchase-orders", PurchaseFixtures.orderJson(supplier)))
            .getString("id");
    assertThat(
        owner("POST", "/purchase-orders/" + po + "/lines", PurchaseFixtures.lineJson(10, "1.00"))
            .getStatus(),
        is(201));
    assertThat(owner("POST", "/purchase-orders/" + po + "/submit", "{}").getStatus(), is(200));
    String gr =
        Envelopes.created(owner("POST", "/goods-receipts", PurchaseFixtures.receiptJson(po, 10)))
            .getString("id");
    // Billed at twice the order's price: flagged, and waiting for a decision.
    JsonInvoice invoice =
        new JsonInvoice(
            Envelopes.created(
                owner(
                    "POST",
                    "/supplier-invoices",
                    PurchaseFixtures.invoiceJson(
                        po, "INV-SCOPE-1", "2026-09-01", 10, "2.00", "0"))));
    assertThat(invoice.status(), is("FLAGGED"));
    String ret =
        Envelopes.created(
                owner(
                    "POST",
                    "/vendor-returns",
                    "{\"poId\":\""
                        + po
                        + "\",\"reason\":\"DAMAGED\",\"lines\":[{\"variantId\":\""
                        + VARIANT
                        + "\",\"qty\":1}]}"))
            .getString("id");
    String charge =
        Envelopes.created(
                owner(
                    "POST",
                    "/landed-costs",
                    "{\"grId\":\""
                        + gr
                        + "\",\"chargeType\":\"FREIGHT\",\"basis\":\"BY_VALUE\",\"amount\":5.00}"))
            .getString("id");
    String rfq =
        Envelopes.created(
                owner(
                    "POST",
                    "/rfqs",
                    "{\"title\":\"Scope\",\"storeId\":\""
                        + STORE_A
                        + "\",\"lines\":[{\"variantId\":\""
                        + VARIANT
                        + "\",\"qty\":5}],\"supplierIds\":[\""
                        + supplier
                        + "\"]}"))
            .getString("id");
    var pair =
        Envelopes.created(
            owner(
                "POST",
                "/intercompany-invoices",
                "{\"fromStoreId\":\""
                    + STORE_A
                    + "\",\"toStoreId\":\""
                    + STORE_B
                    + "\",\"netAmount\":10.00,\"vatAmount\":2.00,\"grossAmount\":12.00,"
                    + "\"currency\":\"GBP\"}"));
    return new AtStoreA(
        supplier,
        po,
        gr,
        invoice.id(),
        ret,
        charge,
        rfq,
        pair.getJsonObject("arInvoice").getString("id"),
        pair.getJsonObject("apInvoice").getString("id"));
  }

  private record JsonInvoice(jakarta.json.JsonObject o) {
    String id() {
      return o.getString("id");
    }

    String status() {
      return o.getString("status");
    }
  }

  /** Every row a write on these records could move, as one string. */
  private static String books() {
    return Envelopes.scalar(
        PG,
        "SELECT "
            + String.join(
                " || '/' || ",
                statuses("purchase_orders"),
                count("purchase_order_lines"),
                statuses("supplier_invoices"),
                statuses("vendor_returns"),
                statuses("landed_costs"),
                statuses("rfqs"),
                count("rfq_quote_lines"),
                statuses("intercompany_invoices"),
                count("intercompany_invoices"),
                count("consignment_settlements"),
                count("dropship_arrangements"),
                count("nominal_ledger_entries"),
                count("outbox")));
  }

  private static String statuses(String table) {
    return "(SELECT coalesce(string_agg(status, ',' ORDER BY id), '') FROM purchase." + table + ")";
  }

  private static String count(String table) {
    return "(SELECT count(*) FROM purchase." + table + ")";
  }

  // ── U1: deciding a flagged invoice ──────────────────────────────────────────

  @Test
  @DisplayName(
      "A flagged invoice on store A's order is approved or rejected only at store A: a manager"
          + " held to another store is refused STORE_ACCESS_DENIED and nothing moves; another"
          + " business finds no such invoice; a shopper is turned away")
  void aFlaggedInvoiceIsDecidedAtItsOrdersStore() {
    AtStoreA a = storeA();
    String path = "/supplier-invoices/" + a.invoice() + "/resolve";
    String before = books();

    for (String action : new String[] {"APPROVE", "REJECT"}) {
      String body = "{\"action\":\"" + action + "\",\"reason\":\"checked\"}";
      assertThat(
          action,
          code(send("POST", T, path, body, "MANAGER", STORE_B), 403),
          is("STORE_ACCESS_DENIED"));
      for (String roles : new String[] {"OWNER", "MANAGER"}) {
        assertThat(
            roles + " of another business",
            code(send("POST", T2, path, body, roles, null), 404),
            is("PURCHASE_INVOICE_NOT_FOUND"));
      }
      for (String roles : new String[] {"STOREKEEPER", "CASHIER"}) {
        assertThat(
            roles + " of another business",
            send("POST", T2, path, body, roles, null).getStatus(),
            anyOf(is(403), is(404)));
      }
      for (String tenant : new String[] {T, T2}) {
        assertThat(send("POST", tenant, path, body, "CUSTOMER", null).getStatus(), is(403));
      }
    }
    assertThat("nothing was decided, reversed or announced", books(), is(before));

    // The manager held to the order's store decides it: the rejection reverses the posting.
    assertThat(
        send(
                "POST",
                T,
                path,
                "{\"action\":\"REJECT\",\"reason\":\"billed twice the price\"}",
                "MANAGER",
                STORE_A)
            .getStatus(),
        is(200));
    assertThat(
        Envelopes.scalar(
            PG, "SELECT status FROM purchase.supplier_invoices WHERE id = '" + a.invoice() + "'"),
        is("REJECTED"));
    assertThat("the reversal was posted", books(), not(is(before)));
  }

  // ── U2: every store-bound record, read and changed ──────────────────────────

  @Test
  @DisplayName(
      "A manager held to another store reads none of store A's order, receipts, invoices,"
          + " returns, landed costs, request for quotes or journals, and changes none of them;"
          + " held to store A, the same calls are answered")
  void storeARecordsAreReadAndChangedAtStoreA() {
    AtStoreA a = storeA();
    String journal =
        Envelopes.scalar(
            PG,
            "SELECT journal_id FROM purchase.nominal_ledger_entries"
                + " WHERE source_type = 'GOODS_RECEIPT' AND source_ref = '"
                + a.gr()
                + "' LIMIT 1");
    String[] reads = {
      "/purchase-orders/" + a.po(),
      "/purchase-orders/" + a.po() + "/lines",
      "/purchase-orders/" + a.po() + "/approvals",
      "/purchase-orders/" + a.po() + "/progress",
      "/purchase-orders/" + a.po() + "/allocations",
      "/goods-receipts?poId=" + a.po(),
      "/supplier-invoices/" + a.invoice(),
      "/vendor-returns/" + a.ret(),
      "/vendor-returns?poId=" + a.po(),
      "/landed-costs/" + a.charge(),
      "/landed-costs?grId=" + a.gr(),
      "/landed-costs?poId=" + a.po(),
      "/rfqs/" + a.rfq(),
      "/nominal-ledger/journals/" + journal,
      "/nominal-ledger/trial-balance?storeId=" + STORE_A,
      "/nominal-ledger/sales-clearing?storeId=" + STORE_A,
    };
    String[][] writes = {
      {
        "POST",
        "/vendor-returns/" + a.ret() + "/credit",
        "{\"creditNoteNumber\":\"CN-1\",\"creditNoteDate\":\"2026-09-02\"}"
      },
      {"POST", "/rfqs/" + a.rfq() + "/issue", "{}"},
      {
        "PUT",
        "/rfqs/" + a.rfq() + "/quotes/" + a.supplier(),
        "{\"lines\":[{\"variantId\":\"" + VARIANT + "\",\"unitPrice\":1.00}]}"
      },
      {"POST", "/rfqs/" + a.rfq() + "/quotes/" + a.supplier() + "/decline", "{}"},
      {
        "POST",
        "/rfqs/" + a.rfq() + "/award",
        "{\"awards\":[{\"variantId\":\"" + VARIANT + "\",\"supplierId\":\"" + a.supplier() + "\"}]}"
      },
      {"POST", "/rfqs/" + a.rfq() + "/cancel", "{\"reason\":\"not needed\"}"},
      {
        "POST",
        "/landed-costs/" + a.charge() + "/reversal",
        "{\"reason\":\"charged to the wrong receipt\"}"
      },
    };
    String before = books();

    for (String read : reads) {
      assertThat(
          read,
          code(send("GET", T, read, null, "MANAGER", STORE_B), 403),
          is("STORE_ACCESS_DENIED"));
    }
    for (String[] w : writes) {
      assertThat(
          w[0] + " " + w[1],
          code(send(w[0], T, w[1], w[2], "MANAGER", STORE_B), 403),
          is("STORE_ACCESS_DENIED"));
    }
    // A storekeeper held to another store buys and returns no more than the manager does.
    for (String[] w : new String[][] {writes[1], writes[2], writes[4], writes[6]}) {
      assertThat(
          "STOREKEEPER " + w[0] + " " + w[1],
          code(send(w[0], T, w[1], w[2], "STOREKEEPER", STORE_B), 403),
          is("STORE_ACCESS_DENIED"));
    }
    // Lists hold to the caller's stores: none of store A's records is in a store-B list.
    for (String list :
        new String[] {
          "/purchase-orders",
          "/supplier-invoices",
          "/vendor-returns",
          "/landed-costs",
          "/rfqs",
          "/nominal-ledger"
        }) {
      String body = Envelopes.bodyOf(send("GET", T, list, null, "MANAGER", STORE_B), 200);
      for (String id : new String[] {a.po(), a.invoice(), a.ret(), a.charge(), a.rfq()}) {
        assertThat(list, body, not(containsString(id)));
      }
      assertThat(list, body, not(containsString(STORE_A)));
    }
    assertThat("nothing moved", books(), is(before));

    // Held to store A, every read is answered and the credit note is recorded.
    for (String read : reads) {
      assertThat(read, send("GET", T, read, null, "MANAGER", STORE_A).getStatus(), is(200));
    }
    assertThat(
        send("POST", T, writes[0][1], writes[0][2], "MANAGER", STORE_A).getStatus(), is(200));
    String list =
        Envelopes.bodyOf(send("GET", T, "/purchase-orders", null, "MANAGER", STORE_A), 200);
    assertThat(list, containsString(a.po()));
  }

  @Test
  @DisplayName(
      "An intercompany pair writes both stores' books: raised only by a caller held to both ends,"
          + " read at either end, each side settled at its own store")
  void intercompanyInvoicesAreForBothEnds() {
    AtStoreA a = storeA();
    String raise =
        "{\"fromStoreId\":\""
            + STORE_A
            + "\",\"toStoreId\":\""
            + STORE_B
            + "\",\"netAmount\":1.00,\"vatAmount\":0.20,\"grossAmount\":1.20,\"currency\":\"GBP\"}";
    String before = books();

    // A body that does not add up is answered for that (400), whoever sends it, before the stores.
    String unbalanced = raise.replace("\"grossAmount\":1.20", "\"grossAmount\":1.25");
    assertThat(
        code(send("POST", T, "/intercompany-invoices", unbalanced, "MANAGER", STORE_A), 400),
        is("PURCHASE_IC_GROSS_MISMATCH"));
    assertThat(
        code(send("POST", T, "/intercompany-invoices", raise, "MANAGER", STORE_A), 403),
        is("STORE_ACCESS_DENIED"));
    assertThat(
        code(send("GET", T, "/intercompany-invoices/" + a.ar(), null, "MANAGER", STORE_C), 403),
        is("STORE_ACCESS_DENIED"));
    // The receivable is the sender's (A) to settle, the payable the receiver's (B).
    assertThat(
        code(
            send(
                "POST",
                T,
                "/intercompany-invoices/" + a.ar() + "/settle",
                "{}",
                "MANAGER",
                STORE_B),
            403),
        is("STORE_ACCESS_DENIED"));
    assertThat(
        code(
            send(
                "POST",
                T,
                "/intercompany-invoices/" + a.ap() + "/settle",
                "{}",
                "MANAGER",
                STORE_A),
            403),
        is("STORE_ACCESS_DENIED"));
    String elsewhere =
        Envelopes.bodyOf(send("GET", T, "/intercompany-invoices", null, "MANAGER", STORE_C), 200);
    assertThat(elsewhere, not(containsString(a.ar())));
    assertThat("nothing raised or settled", books(), is(before));

    // Either end reads it; each side is settled at its own store; both ends raise.
    assertThat(
        send("GET", T, "/intercompany-invoices/" + a.ar(), null, "MANAGER", STORE_B).getStatus(),
        is(200));
    assertThat(
        send("POST", T, "/intercompany-invoices/" + a.ar() + "/settle", "{}", "MANAGER", STORE_A)
            .getStatus(),
        is(200));
    assertThat(
        send("POST", T, "/intercompany-invoices/" + a.ap() + "/settle", "{}", "MANAGER", STORE_B)
            .getStatus(),
        is(200));
    assertThat(
        send("POST", T, "/intercompany-invoices", raise, "MANAGER", STORE_A + "," + STORE_B)
            .getStatus(),
        is(201));
  }

  // ── another business, and a shopper ─────────────────────────────────────────

  @Test
  @DisplayName(
      "Another business's staff of every role find none of our store-bound records, by id or in a"
          + " list, and change none of them; a shopper is turned away")
  void anotherBusinessFindsNoneOfIt() {
    AtStoreA a = storeA();
    String[] byId = {
      "/purchase-orders/" + a.po(),
      "/purchase-orders/" + a.po() + "/lines",
      "/goods-receipts?poId=" + a.po(),
      "/supplier-invoices/" + a.invoice(),
      "/vendor-returns/" + a.ret(),
      "/landed-costs/" + a.charge(),
      "/landed-costs?grId=" + a.gr(),
      "/rfqs/" + a.rfq(),
      "/intercompany-invoices/" + a.ar(),
    };
    String[][] writes = {
      {
        "POST",
        "/supplier-invoices/" + a.invoice() + "/resolve",
        "{\"action\":\"APPROVE\",\"reason\":\"x\"}"
      },
      {
        "POST",
        "/vendor-returns/" + a.ret() + "/credit",
        "{\"creditNoteNumber\":\"CN-9\",\"creditNoteDate\":\"2026-09-02\"}"
      },
      {"POST", "/rfqs/" + a.rfq() + "/issue", "{}"},
      {"POST", "/rfqs/" + a.rfq() + "/cancel", "{\"reason\":\"x\"}"},
      {"POST", "/intercompany-invoices/" + a.ar() + "/settle", "{}"},
      {"POST", "/landed-costs/" + a.charge() + "/reversal", "{\"reason\":\"x\"}"},
    };
    String before = books();

    for (String roles : new String[] {"OWNER", "MANAGER", "STOREKEEPER", "CASHIER"}) {
      for (String read : byId) {
        assertThat(
            roles + " " + read,
            send("GET", T2, read, null, roles, null).getStatus(),
            anyOf(is(404), is(403)));
      }
      for (String[] w : writes) {
        assertThat(
            roles + " " + w[1],
            send(w[0], T2, w[1], w[2], roles, null).getStatus(),
            anyOf(is(404), is(403)));
      }
      for (String list :
          new String[] {
            "/purchase-orders",
            "/supplier-invoices",
            "/vendor-returns",
            "/rfqs",
            "/intercompany-invoices"
          }) {
        Response r = send("GET", T2, list, null, roles, null);
        if (r.getStatus() == 200) {
          String body = r.readEntity(String.class);
          for (String id : new String[] {a.po(), a.invoice(), a.ret(), a.rfq(), a.ar()}) {
            assertThat(roles + " " + list, body, not(containsString(id)));
          }
        } else {
          assertThat(roles + " " + list, r.getStatus(), is(403));
        }
      }
    }
    // Their owner and manager reach the service and are told it is not there.
    for (String roles : new String[] {"OWNER", "MANAGER"}) {
      assertThat(
          code(send("GET", T2, "/purchase-orders/" + a.po(), null, roles, null), 404),
          is("PURCHASE_PO_NOT_FOUND"));
      assertThat(
          code(send("GET", T2, "/supplier-invoices/" + a.invoice(), null, roles, null), 404),
          is("PURCHASE_INVOICE_NOT_FOUND"));
      assertThat(
          code(send("GET", T2, "/vendor-returns/" + a.ret(), null, roles, null), 404),
          is("PURCHASE_RTV_NOT_FOUND"));
      assertThat(
          code(send("GET", T2, "/rfqs/" + a.rfq(), null, roles, null), 404),
          is("PURCHASE_RFQ_NOT_FOUND"));
    }
    for (String tenant : new String[] {T, T2}) {
      for (String read : byId) {
        assertThat(
            "shopper " + read,
            send("GET", tenant, read, null, "CUSTOMER", null).getStatus(),
            is(403));
      }
      for (String[] w : writes) {
        assertThat(
            "shopper " + w[1],
            send(w[0], tenant, w[1], w[2], "CUSTOMER", null).getStatus(),
            is(403));
      }
    }
    assertThat("nothing moved", books(), is(before));
  }

  // ── what is the whole business's ────────────────────────────────────────────

  @Test
  @DisplayName(
      "What is the whole business's — its accounting connection and every journal's push, its"
          + " e-invoice inbox settings and fetch, its deferred-revenue estimates, a supplier's"
          + " master data, its payment runs and paying accounts, a journal at no store — refuses a"
          + " manager or owner held to a store BUSINESS_WIDE_ONLY, and another business's staff"
          + " naming our store, before anything moves; a shopper is turned away; held to none, a"
          + " manager is past it")
  void whatIsTheWholeBusinesssNeedsACallerHeldToNone() {
    AtStoreA a = storeA();
    String journal =
        "{\"entryDate\":\"2026-09-15\",\"description\":\"Accrual\",\"lines\":["
            + "{\"nominalCode\":\"5000\",\"debit\":10.00},"
            + "{\"nominalCode\":\"2100\",\"credit\":10.00}]}";
    String id = Ids.newId().toString();
    String[][] routes = {
      {"GET", "/accounting/connection", null},
      {"PUT", "/accounting/connection", "{\"provider\":\"SIMULATED\"}"},
      {"DELETE", "/accounting/connection", null},
      {"POST", "/accounting/connection/disable", "{}"},
      {"POST", "/accounting/connection/enable", "{}"},
      {"GET", "/accounting/connection/accounts", null},
      {"GET", "/accounting/connection/mappings", null},
      {"PUT", "/accounting/connection/mappings", "{\"mappings\":[]}"},
      {"POST", "/accounting/connection/sync", "{}"},
      {"GET", "/accounting/syncs", null},
      {"GET", "/accounting/syncs/" + id, null},
      {"POST", "/accounting/syncs/" + id + "/retry", "{}"},
      {"POST", "/accounting/syncs/" + id + "/resolve", "{\"outcome\":\"NOT_LANDED\"}"},
      {"POST", "/accounting/syncs/" + id + "/skip", "{\"reason\":\"keyed by hand\"}"},
      {"GET", "/admin/e-invoices/inbox/settings", null},
      {"PUT", "/admin/e-invoices/inbox/settings", "{\"network\":\"NONE\"}"},
      {"GET", "/admin/e-invoices/inbox/readiness", null},
      {"POST", "/admin/e-invoices/inbox/fetch", "{}"},
      {"GET", "/nominal-ledger/deferred-revenue", null},
      {
        "PUT",
        "/nominal-ledger/deferred-revenue/settings",
        "{\"pointValue\":0.01,\"pointsBreakagePct\":20,\"giftCardBreakagePct\":10,"
            + "\"reason\":\"year end\"}"
      },
      {"PUT", "/suppliers/" + a.supplier(), "{\"name\":\"Renamed Ltd\"}"},
      {"POST", "/nominal-ledger/journals", journal},
      {"POST", "/payment-runs", "{\"currency\":\"GBP\",\"payUpTo\":\"2026-12-31\"}"},
      {"GET", "/payment-runs", null},
      {"GET", "/payment-runs/" + id, null},
      {"POST", "/payment-runs/" + id + "/approve", "{}"},
      {"POST", "/payment-runs/" + id + "/pay", "{}"},
      {"POST", "/payment-runs/" + id + "/cancel", "{\"reason\":\"not now\"}"},
      {"GET", "/payment-runs/paying-accounts", null},
      {"PUT", "/payment-runs/paying-accounts/GBP", "{\"accountName\":\"Shop\"}"},
      {"POST", "/payment-runs/" + id + "/payments/" + a.supplier() + "/release", "{}"},
    };
    String supplierBefore =
        Envelopes.scalar(
            PG, "SELECT name FROM purchase.suppliers WHERE id = '" + a.supplier() + "'");
    String before = books();

    for (String[] r : routes) {
      // A journal naming no store is posted at a caller's only store (SJ-D74, below): the one it
      // refuses is a caller held to several, who must say which.
      String heldTo = "/nominal-ledger/journals".equals(r[1]) ? STORE_A + "," + STORE_B : STORE_A;
      for (String roles : new String[] {"OWNER", "MANAGER"}) {
        Response held = send(r[0], T, r[1], r[2], roles, heldTo);
        String body = held.readEntity(String.class);
        assertThat(roles + " " + r[0] + " " + r[1] + " " + body, held.getStatus(), is(403));
        // The connection itself is the owner's: a manager is refused that first.
        boolean ownerOnly =
            java.util.Set.of(
                    "PUT /accounting/connection",
                    "DELETE /accounting/connection",
                    "POST /accounting/connection/disable",
                    "POST /accounting/connection/enable")
                .contains(r[0] + " " + r[1]);
        assertThat(
            roles + " " + r[0] + " " + r[1],
            Envelopes.parse(body).getString("code"),
            is("MANAGER".equals(roles) && ownerOnly ? "FORBIDDEN" : "BUSINESS_WIDE_ONLY"));
      }
      // Our own storekeeper or cashier is refused for the role, before the scope: FORBIDDEN, never
      // told the thing is the whole business's (the k6 suites hold them to the same answer).
      for (String roles : new String[] {"STOREKEEPER", "CASHIER"}) {
        Response staff = send(r[0], T, r[1], r[2], roles, heldTo);
        String body = staff.readEntity(String.class);
        assertThat(roles + " " + r[0] + " " + r[1] + " " + body, staff.getStatus(), is(403));
        assertThat(
            roles + " " + r[0] + " " + r[1],
            Envelopes.parse(body).getString("code"),
            is("FORBIDDEN"));
      }
      // Another business's staff, of every role, even naming our store: nothing of ours moves.
      for (String roles : new String[] {"OWNER", "MANAGER", "STOREKEEPER", "CASHIER"}) {
        assertThat(
            roles + " of another business " + r[0] + " " + r[1],
            send(r[0], T2, r[1], r[2], roles, heldTo).getStatus(),
            anyOf(is(403), is(404)));
      }
      for (String tenant : new String[] {T, T2}) {
        assertThat(
            "a shopper " + r[0] + " " + r[1],
            send(r[0], tenant, r[1], r[2], "CUSTOMER", null).getStatus(),
            is(403));
      }
    }
    assertThat("nothing was posted, paid, pushed or announced", books(), is(before));
    assertThat(
        "the supplier was not renamed",
        Envelopes.scalar(
            PG, "SELECT name FROM purchase.suppliers WHERE id = '" + a.supplier() + "'"),
        is(supplierBefore));

    // A manager of the whole business is past the gate on every one of them.
    for (String[] r : routes) {
      Response whole = send(r[0], T, r[1], r[2], "MANAGER", null);
      String body = whole.readEntity(String.class);
      assertThat(r[0] + " " + r[1] + " " + body, body, not(containsString("BUSINESS_WIDE_ONLY")));
    }
    // A journal at their own store is still a store-held manager's to post.
    assertThat(
        send(
                "POST",
                T,
                "/nominal-ledger/journals",
                journal.replace("\"lines\"", "\"storeId\":\"" + STORE_A + "\",\"lines\""),
                "MANAGER",
                STORE_A)
            .getStatus(),
        is(201));
    // Held to that one store and naming none, it is posted at their store — which they read back.
    Response posted = send("POST", T, "/nominal-ledger/journals", journal, "MANAGER", STORE_A);
    String postedBody = posted.readEntity(String.class);
    assertThat(postedBody, posted.getStatus(), is(201));
    var data = Envelopes.parse(postedBody).getJsonObject("data");
    assertThat(data.getString("storeId"), is(STORE_A));
    assertThat(
        send(
                "GET",
                T,
                "/nominal-ledger/journals/" + data.getString("journalId"),
                null,
                "MANAGER",
                STORE_A)
            .getStatus(),
        is(200));
  }

  @Test
  @DisplayName(
      "A consignment statement and a dropship arrangement span every store: a manager held to"
          + " stores is refused making one BUSINESS_WIDE_ONLY; a manager of the whole business is"
          + " not")
  void statementsAndArrangementsAreMadeByTheWholeBusiness() {
    AtStoreA a = storeA();
    String settle =
        "{\"supplierId\":\"" + a.supplier() + "\",\"from\":\"2026-01-01\",\"to\":\"2026-12-31\"}";
    String arrange =
        "{\"variantId\":\""
            + Ids.newId()
            + "\",\"supplierId\":\""
            + a.supplier()
            + "\",\"unitCost\":1.00}";
    String before = books();
    String[][] refused = {
      {"POST", "/admin/consignment/settlements", settle},
      {"POST", "/admin/dropship/arrangements", arrange},
      {"POST", "/admin/dropship/arrangements/" + Ids.newId() + "/end", "{}"},
    };
    for (String[] c : refused) {
      assertThat(
          c[0] + " " + c[1],
          code(send(c[0], T, c[1], c[2], "MANAGER", STORE_A), 403),
          is("BUSINESS_WIDE_ONLY"));
    }
    assertThat("nothing was settled or arranged", books(), is(before));
    // Reading the statements is not refused: a manager held to stores is answered (with theirs).
    assertThat(
        send("GET", T, "/admin/consignment/settlements", null, "MANAGER", STORE_A).getStatus(),
        is(200));

    // Held to no store, the same manager is past the refusal: nothing sold, nothing to settle.
    assertThat(
        code(send("POST", T, refused[0][1], settle, "MANAGER", null), 409),
        is("PURCHASE_CONSIGNMENT_NOTHING_TO_SETTLE"));
    assertThat(
        send("GET", T, "/admin/consignment/settlements", null, "MANAGER", null).getStatus(),
        is(200));
    assertThat(send("POST", T, refused[1][1], arrange, "MANAGER", null).getStatus(), is(201));
  }

  @Test
  @DisplayName(
      "A consignment statement is read by whoever may read every sale in it: a manager held to"
          + " store A lists and reads the one made wholly of store A's sales and is refused"
          + " STORE_ACCESS_DENIED the one that took a store-B sale; another business finds"
          + " neither; a shopper is turned away; nothing moves")
  void aStatementIsReadWhereEverySaleInItWasMade() {
    String onlyAtA = supplier("Only A Ltd");
    String atBoth = supplier("Both Ltd");
    consignmentSold(onlyAtA, STORE_A);
    consignmentSold(onlyAtA, STORE_A);
    consignmentSold(atBoth, STORE_A);
    consignmentSold(atBoth, STORE_B);
    String wholly = statement(onlyAtA);
    String spanning = statement(atBoth);
    String list = "/admin/consignment/settlements";
    String before = books();

    // Held to store A: store A's statement is listed and read, with its sales; the other is not.
    String atA = Envelopes.bodyOf(send("GET", T, list, null, "MANAGER", STORE_A), 200);
    assertThat(atA, containsString(wholly));
    assertThat(atA, not(containsString(spanning)));
    JsonObject read = Envelopes.ok(send("GET", T, list + "/" + wholly, null, "MANAGER", STORE_A));
    assertThat(read.getJsonArray("sales").size(), is(2));
    assertThat(
        code(send("GET", T, list + "/" + spanning, null, "MANAGER", STORE_A), 403),
        is("STORE_ACCESS_DENIED"));
    // Held to store B: neither is wholly theirs.
    String atB = Envelopes.bodyOf(send("GET", T, list, null, "MANAGER", STORE_B), 200);
    assertThat(atB, not(containsString(wholly)));
    assertThat(atB, not(containsString(spanning)));
    for (String id : new String[] {wholly, spanning}) {
      assertThat(
          code(send("GET", T, list + "/" + id, null, "MANAGER", STORE_B), 403),
          is("STORE_ACCESS_DENIED"));
    }
    // Held to both, or to none: both.
    for (String stores : new String[] {STORE_A + "," + STORE_B, null}) {
      String body = Envelopes.bodyOf(send("GET", T, list, null, "MANAGER", stores), 200);
      assertThat(body, containsString(wholly));
      assertThat(body, containsString(spanning));
      assertThat(
          send("GET", T, list + "/" + spanning, null, "MANAGER", stores).getStatus(), is(200));
    }

    // Another business, of every role and even held to our store's id, finds neither.
    for (String roles : new String[] {"OWNER", "MANAGER", "STOREKEEPER", "CASHIER"}) {
      for (String stores : new String[] {null, STORE_A}) {
        Response r = send("GET", T2, list, null, roles, stores);
        if (r.getStatus() == 200) {
          String body = r.readEntity(String.class);
          assertThat(roles, body, not(containsString(wholly)));
          assertThat(roles, body, not(containsString(spanning)));
        } else {
          assertThat(roles + " " + list, r.getStatus(), is(403));
        }
        for (String id : new String[] {wholly, spanning}) {
          assertThat(
              roles + " " + id,
              send("GET", T2, list + "/" + id, null, roles, stores).getStatus(),
              anyOf(is(404), is(403)));
        }
      }
    }
    for (String roles : new String[] {"OWNER", "MANAGER"}) {
      assertThat(
          code(send("GET", T2, list + "/" + wholly, null, roles, STORE_A), 404),
          is("PURCHASE_CONSIGNMENT_SETTLEMENT_NOT_FOUND"));
    }
    for (String tenant : new String[] {T, T2}) {
      assertThat(send("GET", tenant, list, null, "CUSTOMER", null).getStatus(), is(403));
      assertThat(
          send("GET", tenant, list + "/" + wholly, null, "CUSTOMER", null).getStatus(), is(403));
    }
    assertThat("reading moved nothing", books(), is(before));
  }

  /** A supplier of ours, as the owner adds one. */
  private String supplier(String name) {
    return Envelopes.created(
            owner("POST", "/suppliers", "{\"name\":\"" + name + "\",\"currency\":\"GBP\"}"))
        .getString("id");
  }

  /** inventory-svc's announcement that one unit of the supplier's stock sold at the store. */
  private void consignmentSold(String supplierId, String storeId) {
    consignment.stockSold(
        "{\"eventId\":\""
            + Ids.newId()
            + "\",\"eventType\":\"ConsignmentStockSold\",\"tenantId\":\""
            + T
            + "\",\"aggregateId\":\""
            + Ids.newId()
            + "\",\"occurredAt\":\"2026-09-24T10:00:00Z\",\"storeId\":\""
            + storeId
            + "\",\"variantId\":\""
            + VARIANT
            + "\",\"batchId\":\""
            + Ids.newId()
            + "\",\"supplierId\":\""
            + supplierId
            + "\",\"orderId\":\""
            + Ids.newId()
            + "\",\"qty\":1,\"unitCost\":2.00}");
  }

  /** The owner settles the supplier's sales of the year: the statement's id. */
  private String statement(String supplierId) {
    return Envelopes.created(
            owner(
                "POST",
                "/admin/consignment/settlements",
                "{\"supplierId\":\""
                    + supplierId
                    + "\",\"from\":\"2026-01-01\",\"to\":\"2026-12-31\"}"))
        .getString("id");
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  private Response owner(String method, String path, String json) {
    return send(method, T, path, json, "OWNER", null);
  }

  /** Any method, as somebody of a business held to the stores named (comma-separated) or none. */
  private Response send(
      String method, String tenant, String path, String json, String roles, String storeIds) {
    var req =
        com.storeql.test.WebTargets.at(target, path)
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-Roles", roles)
            .header("X-User-Id", USER)
            .header("Idempotency-Key", Ids.newId().toString());
    if (storeIds != null) {
      req = req.header("X-Store-Ids", storeIds);
    }
    return json == null
        ? req.build(method).invoke()
        : req.build(method, Entity.entity(json, MediaType.APPLICATION_JSON)).invoke();
  }

  /** The stable code of a refused answer, after checking its status. */
  private static String code(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return Envelopes.parse(body).getString("code");
  }
}
