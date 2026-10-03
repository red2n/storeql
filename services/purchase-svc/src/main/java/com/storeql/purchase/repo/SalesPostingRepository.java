package com.storeql.purchase.repo;

import com.storeql.ids.Ids;
import com.storeql.purchase.domain.Domain;
import com.storeql.purchase.domain.Domain.NominalLedgerEntry;
import com.storeql.purchase.domain.Domain.OpenClearing;
import com.storeql.purchase.domain.Domain.SalesOrder;
import com.storeql.purchase.domain.Domain.SalesTender;
import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * JDBC persistence for sales and tender posting (17.7). Each write marks the event processed, keeps
 * the projection row and inserts the journal in one transaction, so a redelivered event and a
 * second announcement of the same sale or tender both post nothing twice.
 */
@ApplicationScoped
public class SalesPostingRepository extends BaseJdbcRepository {

  /**
   * Records a confirmed sale and its journal.
   *
   * @return {@code false} when the event was already processed, or the sale was already posted
   */
  public boolean recordSaleOnce(
      UUID eventId, String consumer, SalesOrder sale, List<NominalLedgerEntry> posting) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, eventId, consumer)) return false;
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO sales_orders (tenant_id, order_id, store_id, currency, total, tax_amount)"
                      + " VALUES (?,?,?,?,?,?) ON CONFLICT (tenant_id, order_id) DO NOTHING")) {
            ps.setObject(1, sale.tenantId());
            ps.setObject(2, sale.orderId());
            ps.setObject(3, sale.storeId());
            ps.setString(4, sale.currency());
            ps.setBigDecimal(5, sale.total());
            ps.setBigDecimal(6, sale.taxAmount());
            if (ps.executeUpdate() == 0) return false;
          }
          LedgerWriter.insert(c, posting);
          return true;
        },
        "post sale");
  }

  /**
   * Records a captured tender and its journal.
   *
   * @return {@code false} when the tender was already posted
   */
  public boolean recordTenderOnce(
      String consumer, SalesTender tender, List<NominalLedgerEntry> posting) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, tender.paymentId(), consumer)) return false;
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO sales_tenders (tenant_id, payment_id, order_id, store_id, method, amount)"
                      + " VALUES (?,?,?,?,?,?) ON CONFLICT (tenant_id, payment_id) DO NOTHING")) {
            ps.setObject(1, tender.tenantId());
            ps.setObject(2, tender.paymentId());
            ps.setObject(3, tender.orderId());
            ps.setObject(4, tender.storeId());
            ps.setString(5, tender.method());
            ps.setBigDecimal(6, tender.amount());
            if (ps.executeUpdate() == 0) return false;
          }
          LedgerWriter.insert(c, posting);
          return true;
        },
        "post tender");
  }

  /** Records a refund's journal, once per event. */
  public boolean recordRefundOnce(UUID eventId, String consumer, List<NominalLedgerEntry> posting) {
    return recordJournalOnce(eventId, consumer, posting, "post refund");
  }

  /** Records a journal that needs no record of its own beside it, once per event. */
  public boolean recordJournalOnce(
      UUID eventId, String consumer, List<NominalLedgerEntry> posting, String what) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, eventId, consumer)) return false;
          LedgerWriter.insert(c, posting);
          return true;
        },
        what);
  }

  public Optional<SalesOrder> findSale(UUID tenantId, UUID orderId) {
    var rows =
        query(
            "SELECT tenant_id, order_id, store_id, currency, total, tax_amount FROM sales_orders"
                + " WHERE tenant_id = ? AND order_id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, orderId);
            },
            rs ->
                new SalesOrder(
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("order_id", UUID.class),
                    rs.getObject("store_id", UUID.class),
                    rs.getString("currency"),
                    rs.getBigDecimal("total"),
                    rs.getBigDecimal("tax_amount")),
            "find sale");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /** Sales and VAT earlier refunds of this order took back, for the cap on the next. */
  public java.math.BigDecimal revenueRefunded(UUID tenantId, UUID orderId) {
    return query(
            "SELECT COALESCE(SUM(debit), 0) AS taken FROM nominal_ledger_entries"
                + " WHERE tenant_id = ? AND source_type = ? AND source_ref = ?"
                + " AND nominal_code IN (?, ?)",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, com.storeql.purchase.domain.Domain.SOURCE_SALE_REFUND);
              ps.setObject(3, orderId);
              ps.setString(4, com.storeql.purchase.domain.Domain.CODE_SALES);
              ps.setString(5, com.storeql.purchase.domain.Domain.CODE_VAT_OUTPUT);
            },
            rs -> rs.getBigDecimal("taken"),
            "revenue refunded so far")
        .get(0);
  }

  /** The store an order's tenders were taken at, for a refund of a sale the ledger never saw. */
  public Optional<UUID> findTenderStore(UUID tenantId, UUID orderId) {
    var rows =
        query(
            "SELECT store_id FROM sales_tenders WHERE tenant_id = ? AND order_id = ?"
                + " AND store_id IS NOT NULL ORDER BY captured_at LIMIT 1",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, orderId);
            },
            rs -> rs.getObject("store_id", UUID.class),
            "find tender store");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /**
   * Orders whose receipts clearing has not netted to zero, oldest first.
   *
   * @param stores the stores to read, or null for every store in the business
   */
  public List<OpenClearing> findOpenClearing(UUID tenantId, Set<UUID> stores, int limit) {
    return query(
        "SELECT source_ref, MAX(store_id::text) AS store_id,"
            + "       SUM(debit) - SUM(credit) AS balance,"
            + "       MIN(entry_date) AS first_posted, MAX(entry_date) AS last_posted"
            + "  FROM nominal_ledger_entries"
            + " WHERE tenant_id = ? AND nominal_code = ? AND source_ref IS NOT NULL"
            + (stores == null ? "" : " AND store_id = ANY(?)")
            + " GROUP BY source_ref"
            + " HAVING SUM(debit) - SUM(credit) <> 0"
            + " ORDER BY MIN(entry_date), source_ref LIMIT ?",
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          ps.setString(i++, Domain.CODE_SALES_CLEARING);
          if (stores != null) {
            ps.setArray(i++, ps.getConnection().createArrayOf("uuid", stores.toArray()));
          }
          ps.setInt(i, limit);
        },
        rs -> {
          String store = rs.getString("store_id");
          return new OpenClearing(
              rs.getObject("source_ref", UUID.class),
              store == null ? null : Ids.parse(store),
              rs.getBigDecimal("balance"),
              rs.getObject("first_posted", LocalDate.class),
              rs.getObject("last_posted", LocalDate.class));
        },
        "find open sales clearing");
  }
}
