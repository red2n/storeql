package com.storeql.purchase;

import com.storeql.test.PostgresSupport;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Ids, a clean slate and request bodies the purchase integration tests share, so a test reads as
 * its scenario rather than as the JSON it posts.
 */
final class PurchaseFixtures {

  private PurchaseFixtures() {}

  static final String T = "01a090ae-611e-702c-a97b-d1b8025478e1";
  static final String T2 = "01a090ae-611e-7037-a4b7-c854f0266ace";
  static final String STORE_A = "01a090ae-611e-703c-a378-a4972ea461c8";
  static final String VARIANT = "01a090ae-611e-705c-994c-5daee3fbd033";
  static final String USER = "01a090ae-611e-700b-bde4-50df0324c37c";

  private static final String TABLES =
      String.join(
          ", ",
          "purchase.purchase_order_line_allocations",
          "purchase.rfq_awards",
          "purchase.rfq_quote_lines",
          "purchase.rfq_suppliers",
          "purchase.rfq_lines",
          "purchase.rfqs",
          "purchase.rfq_series",
          "purchase.supplier_deliveries",
          "purchase.duty_releases",
          "purchase.dropship_arrangements",
          "purchase.consignment_sales",
          "purchase.consignment_settlements",
          "purchase.order_proposal_runs",
          "purchase.supplier_item_codes",
          "purchase.supplier_einvoice_lines",
          "purchase.supplier_einvoices",
          "purchase.payment_hold_releases",
          "purchase.payment_statuses",
          "purchase.payment_status_reports",
          "purchase.paying_accounts",
          "purchase.payment_run_items",
          "purchase.sales_orders",
          "purchase.sales_tenders",
          "purchase.processed_events",
          "purchase.deferred_revenue_settings",
          "purchase.loyalty_events",
          "purchase.loyalty_point_pools",
          "purchase.gift_card_pools",
          "purchase.gift_card_loads",
          "purchase.payment_runs",
          "purchase.nominal_ledger_entries",
          "purchase.intercompany_invoices",
          "purchase.vendor_return_lines",
          "purchase.vendor_returns",
          "purchase.goods_receipt_lines",
          "purchase.goods_receipts",
          "purchase.supplier_invoice_lines",
          "purchase.supplier_invoices",
          "purchase.purchase_order_lines",
          "purchase.purchase_orders",
          "purchase.suppliers",
          "purchase.outbox");

  /** Empties every purchase table, so each test starts from nothing. */
  static void truncateAll(PostgresSupport pg) throws SQLException {
    try (var conn = DriverManager.getConnection(pg.jdbcUrl(), pg.username(), pg.password());
        var st = conn.createStatement()) {
      st.execute("TRUNCATE TABLE " + TABLES + " CASCADE");
    }
  }

  /** A GBP purchase order for the supplier, delivered to {@link #STORE_A}. */
  static String orderJson(String supplierId) {
    return String.format(
        "{\"supplierId\":\"%s\",\"storeId\":\"%s\",\"currency\":\"GBP\"}", supplierId, STORE_A);
  }

  /** One line of {@link #VARIANT} at a standard-rated VAT code. */
  static String lineJson(Object qty, String unitPrice) {
    return String.format(
        "{\"variantId\":\"%s\",\"qty\":%s,\"unitPrice\":%s,\"vatCode\":\"T1\"}",
        VARIANT, qty, unitPrice);
  }

  /** A goods receipt of {@code qty} of {@link #VARIANT} into {@link #STORE_A}. */
  static String receiptJson(String poId, Object qty) {
    return String.format(
        "{\"poId\":\"%s\",\"storeId\":\"%s\",\"lines\":[{\"variantId\":\"%s\",\"qtyReceived\":%s}]}",
        poId, STORE_A, VARIANT, qty);
  }

  /** A supplier invoice for {@code qty} of {@link #VARIANT} at {@code unitPrice}. */
  static String invoiceJson(
      String poId, String number, Object date, Object qty, String unitPrice, String vat) {
    return String.format(
        "{\"poId\":\"%s\",\"invoiceNumber\":\"%s\",\"invoiceDate\":\"%s\",\"vatAmount\":%s,"
            + "\"lines\":[{\"variantId\":\"%s\",\"qty\":%s,\"unitPrice\":%s}]}",
        poId, number, date, vat, VARIANT, qty, unitPrice);
  }
}
