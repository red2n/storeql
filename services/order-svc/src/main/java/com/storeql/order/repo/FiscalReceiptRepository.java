package com.storeql.order.repo;

import com.storeql.order.domain.Domain;
import com.storeql.order.domain.Domain.FiscalReceipt;
import com.storeql.order.domain.Domain.PtStamp;
import com.storeql.order.domain.Domain.SequenceGap;
import com.storeql.order.domain.Domain.TseStamp;
import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The gapless receipt sequence.
 *
 * <p>Everything here exists to make one promise keepable: the numbers in a series run consecutively
 * with no holes, and that can be demonstrated to an inspector.
 */
@ApplicationScoped
public class FiscalReceiptRepository extends BaseJdbcRepository {

  private static final String COLUMNS =
      "id, tenant_id, store_id, series_code, period, number, full_number, order_id, issued_at,"
          + " issued_by, currency, gross_total, tax_total, voided_at, void_reason, prev_hash, hash,"
          + " regime, tse_serial, tse_client_id, tse_transaction_number, tse_signature_counter,"
          + " tse_signature, tse_algorithm, tse_public_key, tse_time_format, tse_started_at,"
          + " tse_finished_at, tse_process_type, tse_process_data, tse_qr, tse_error,"
          + " pt_invoice_no, pt_hash, pt_hash_control, pt_atcud, pt_certificate_number";

  /**
   * Signs a document inside its allocation, once its number is known and the previous document is
   * fixed (18.5). The Portuguese signature chains on the previous document's signature, which is
   * why it cannot be computed before the counter row is locked.
   */
  @FunctionalInterface
  public interface DocumentSigner {
    /**
     * @param numbered the document with its number, full number, issue time and previous hash
     * @param previous the document before it in the series, or null for the first
     * @return the stamp to store, or null for none
     */
    PtStamp sign(FiscalReceipt numbered, FiscalReceipt previous);
  }

  /** Issues with no document signer — a store under NONE or DE_KASSENSICHV. */
  public FiscalReceipt issue(FiscalReceipt draft, String defaultPrefix) {
    return issue(draft, defaultPrefix, null);
  }

  /**
   * Issues the receipt for an order, allocating the next number in its series.
   *
   * <p><b>Idempotent.</b> A second call for the same order returns the document already issued
   * rather than allocating another. A reprint is not a sale, and two numbers for one sale is how a
   * day's takings end up counted twice.
   *
   * <p><b>The allocation is the point.</b> The counter is incremented with an {@code UPDATE …
   * RETURNING} inside this transaction, so the row lock serialises concurrent tills and a rollback
   * puts the number back. A {@code SEQUENCE} would be faster and would gap on the first aborted
   * transaction — and the gap is exactly what an inspector asks about.
   *
   * @param draft the document before its number: the device's stamp, if the regime has one, is
   *     already on it
   * @param defaultPrefix what a newly opened series prints in front of its numbers
   * @param signer the regime's document signer, or null
   */
  public FiscalReceipt issue(FiscalReceipt draft, String defaultPrefix, DocumentSigner signer) {
    return inTx(
        c -> {
          var existing = findByOrderTx(c, draft.tenantId(), draft.orderId());
          if (existing.isPresent()) {
            return existing.get();
          }

          // First sale in this series and period opens it. DO NOTHING rather than a prior SELECT:
          // two tills opening the same store on the same morning is the normal case, not a race
          // worth failing.
          try (var open =
              c.prepareStatement(
                  "INSERT INTO receipt_series"
                      + " (tenant_id, store_id, series_code, period, next_number, prefix)"
                      + " VALUES (?,?,?,?,1,?) ON CONFLICT DO NOTHING")) {
            open.setObject(1, draft.tenantId());
            open.setObject(2, draft.storeId());
            open.setString(3, draft.seriesCode());
            open.setString(4, draft.period());
            open.setString(5, defaultPrefix);
            open.executeUpdate();
          }

          long number;
          String prefix;
          try (var take =
              c.prepareStatement(
                  "UPDATE receipt_series SET next_number = next_number + 1"
                      + " WHERE tenant_id = ? AND store_id = ? AND series_code = ? AND period = ?"
                      + " RETURNING next_number - 1, prefix")) {
            take.setObject(1, draft.tenantId());
            take.setObject(2, draft.storeId());
            take.setString(3, draft.seriesCode());
            take.setString(4, draft.period());
            try (ResultSet rs = take.executeQuery()) {
              if (!rs.next()) {
                throw new SQLException("receipt series vanished between open and take");
              }
              number = rs.getLong(1);
              prefix = rs.getString(2);
            }
          }

          String full =
              (prefix == null || prefix.isBlank() ? "" : prefix + "-")
                  + draft.period()
                  + "-"
                  + String.format("%06d", number);
          // The chain (18.4): the previous document's hash, or GENESIS for the first — and for
          // the first after a document issued before the chain existed. The counter row is still
          // locked, so the previous document is committed and nothing can slip between.
          FiscalReceipt previous =
              findByNumberTx(
                      c,
                      draft.tenantId(),
                      draft.storeId(),
                      draft.seriesCode(),
                      draft.period(),
                      number - 1)
                  .orElse(null);
          String prevHash =
              previous == null || previous.hash() == null
                  ? Domain.FiscalReceipt.GENESIS
                  : previous.hash();
          // Issued-at is part of the hash, so it is chosen here rather than by the database, at
          // the microsecond precision the column keeps.
          Instant issuedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
          FiscalReceipt numbered =
              new FiscalReceipt(
                  draft.id(),
                  draft.tenantId(),
                  draft.storeId(),
                  draft.seriesCode(),
                  draft.period(),
                  number,
                  full,
                  draft.orderId(),
                  issuedAt,
                  draft.issuedBy(),
                  draft.currency(),
                  draft.grossTotal(),
                  draft.taxTotal(),
                  null,
                  null,
                  prevHash,
                  null,
                  draft.regime(),
                  draft.tse(),
                  null);
          // The document signer runs here (18.5), with the number and the previous document both
          // fixed under the same lock, so the Portuguese chain is as gapless as the numbers.
          PtStamp pt = signer == null ? null : signer.sign(numbered, previous);
          FiscalReceipt chained = numbered.withStamps(draft.regime(), draft.tse(), pt);
          String hash = hashOf(chained);
          insertTx(c, chained, hash);
          return findByOrderTx(c, draft.tenantId(), draft.orderId()).orElseThrow();
        },
        "issue fiscal receipt");
  }

  private static void insertTx(Connection c, FiscalReceipt r, String hash) throws SQLException {
    TseStamp t = r.tse();
    PtStamp p = r.pt();
    try (PreparedStatement ins =
        c.prepareStatement(
            "INSERT INTO fiscal_receipts ("
                + COLUMNS
                + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,"
                + "?,?,?)")) {
      int i = 1;
      ins.setObject(i++, r.id());
      ins.setObject(i++, r.tenantId());
      ins.setObject(i++, r.storeId());
      ins.setString(i++, r.seriesCode());
      ins.setString(i++, r.period());
      ins.setLong(i++, r.number());
      ins.setString(i++, r.fullNumber());
      ins.setObject(i++, r.orderId());
      ins.setObject(i++, r.issuedAt().atOffset(ZoneOffset.UTC));
      ins.setObject(i++, r.issuedBy());
      ins.setString(i++, r.currency());
      ins.setBigDecimal(i++, r.grossTotal());
      ins.setBigDecimal(i++, r.taxTotal());
      ins.setObject(i++, null);
      ins.setString(i++, null);
      ins.setString(i++, r.prevHash());
      ins.setString(i++, hash);
      ins.setString(i++, r.regime());
      ins.setString(i++, t == null ? null : t.serialNumber());
      ins.setString(i++, t == null ? null : t.clientId());
      ins.setObject(i++, t == null ? null : t.transactionNumber());
      ins.setObject(i++, t == null ? null : t.signatureCounter());
      ins.setString(i++, t == null ? null : t.signature());
      ins.setString(i++, t == null ? null : t.algorithm());
      ins.setString(i++, t == null ? null : t.publicKey());
      ins.setString(i++, t == null ? null : t.timeFormat());
      ins.setObject(
          i++, t == null || t.startedAt() == null ? null : t.startedAt().atOffset(ZoneOffset.UTC));
      ins.setObject(
          i++,
          t == null || t.finishedAt() == null ? null : t.finishedAt().atOffset(ZoneOffset.UTC));
      ins.setString(i++, t == null ? null : t.processType());
      ins.setString(i++, t == null ? null : t.processData());
      ins.setString(i++, t == null ? null : t.qr());
      ins.setString(i++, t == null ? null : t.error());
      ins.setString(i++, p == null ? null : p.invoiceNo());
      ins.setString(i++, p == null ? null : p.hash());
      ins.setString(i++, p == null ? null : p.hashControl());
      ins.setString(i++, p == null ? null : p.atcud());
      ins.setString(i, p == null ? null : p.certificateNumber());
      ins.executeUpdate();
    }
  }

  private static Optional<FiscalReceipt> findByOrderTx(Connection c, UUID tenantId, UUID orderId)
      throws SQLException {
    try (var st =
        c.prepareStatement(
            "SELECT " + COLUMNS + " FROM fiscal_receipts WHERE tenant_id = ? AND order_id = ?")) {
      st.setObject(1, tenantId);
      st.setObject(2, orderId);
      try (ResultSet rs = st.executeQuery()) {
        return rs.next() ? Optional.of(map(rs)) : Optional.empty();
      }
    }
  }

  private static Optional<FiscalReceipt> findByNumberTx(
      Connection c, UUID tenantId, UUID storeId, String series, String period, long number)
      throws SQLException {
    try (var st =
        c.prepareStatement(
            "SELECT "
                + COLUMNS
                + " FROM fiscal_receipts WHERE tenant_id = ? AND store_id = ?"
                + " AND series_code = ? AND period = ? AND number = ?")) {
      st.setObject(1, tenantId);
      st.setObject(2, storeId);
      st.setString(3, series);
      st.setString(4, period);
      st.setLong(5, number);
      try (ResultSet rs = st.executeQuery()) {
        return rs.next() ? Optional.of(map(rs)) : Optional.empty();
      }
    }
  }

  /**
   * The receipt issued for one sale.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param orderId the sale whose receipt to read
   * @return the receipt, or empty when none has been issued yet
   */
  public Optional<FiscalReceipt> findByOrder(UUID tenantId, UUID orderId) {
    var rows =
        query(
            "SELECT " + COLUMNS + " FROM fiscal_receipts WHERE tenant_id = ? AND order_id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, orderId);
            },
            FiscalReceiptRepository::map,
            "find receipt by order");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /**
   * Marks a receipt's sale as void. The number stays.
   *
   * <p>Deleting or renumbering it is precisely the fraud gapless numbering exists to make visible:
   * ring the sale, take the cash, void the receipt, close the gap, and the till balances. Here the
   * document remains, numbered, marked void and with a reason against it.
   */
  public boolean markVoided(UUID tenantId, UUID orderId, String reason) {
    return inTx(
        c -> {
          try (var st =
              c.prepareStatement(
                  "UPDATE fiscal_receipts SET voided_at = now(), void_reason = ?"
                      + " WHERE tenant_id = ? AND order_id = ? AND voided_at IS NULL")) {
            st.setString(1, reason);
            st.setObject(2, tenantId);
            st.setObject(3, orderId);
            return st.executeUpdate() > 0;
          }
        },
        "void fiscal receipt");
  }

  /**
   * Every receipt in one series, by number — the register a store keeps.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId the store whose register to read
   * @param series the numbering series
   * @param period the fiscal period, normally the year
   * @param limit maximum rows
   * @return the receipts in number order
   */
  public List<FiscalReceipt> listSeries(
      UUID tenantId, UUID storeId, String series, String period, int limit) {
    return query(
        "SELECT "
            + COLUMNS
            + " FROM fiscal_receipts"
            + " WHERE tenant_id = ? AND store_id = ? AND series_code = ? AND period = ?"
            + " ORDER BY number LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
          ps.setString(3, series);
          ps.setString(4, period);
          ps.setInt(5, limit);
        },
        FiscalReceiptRepository::map,
        "list receipt series");
  }

  /**
   * One page of a series, by number, after a given number (keyset paging): lets a caller walk a
   * year's register without ever holding more than a page of it.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId the store whose register to read
   * @param series the numbering series
   * @param period the fiscal period
   * @param afterNumber return only receipts numbered above this ({@code 0} for the first page)
   * @param limit page size
   * @return the next receipts in number order
   */
  public List<FiscalReceipt> listSeriesAfter(
      UUID tenantId, UUID storeId, String series, String period, long afterNumber, int limit) {
    return query(
        "SELECT "
            + COLUMNS
            + " FROM fiscal_receipts"
            + " WHERE tenant_id = ? AND store_id = ? AND series_code = ? AND period = ?"
            + " AND number > ? ORDER BY number LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
          ps.setString(3, series);
          ps.setString(4, period);
          ps.setLong(5, afterNumber);
          ps.setInt(6, limit);
        },
        FiscalReceiptRepository::map,
        "list receipt series page");
  }

  /**
   * Walks a whole series a page at a time, in number order, handing each receipt to the visitor;
   * stops early when the visitor returns false. Memory is one page, however long the series.
   *
   * @param pageSize receipts read per query ({@code storeql.order.fiscal.page-size})
   */
  public void forEachInSeries(
      UUID tenantId,
      UUID storeId,
      String series,
      String period,
      int pageSize,
      java.util.function.Predicate<FiscalReceipt> visitor) {
    long after = 0;
    int size = Math.max(1, pageSize);
    while (true) {
      List<FiscalReceipt> page = listSeriesAfter(tenantId, storeId, series, period, after, size);
      for (FiscalReceipt r : page) {
        if (!visitor.test(r)) return;
        after = r.number();
      }
      if (page.size() < size) return;
    }
  }

  /** One counter row: the series a store runs, where it has got to, and what it prints in front. */
  public record ReceiptSeries(
      UUID storeId, String seriesCode, String period, long nextNumber, String prefix) {}

  /**
   * Every series a store has opened, oldest first.
   *
   * @param tenantId owning tenant; the first condition
   * @param storeId the store
   * @return the counters
   */
  public List<ReceiptSeries> listSeriesConfig(UUID tenantId, UUID storeId) {
    return query(
        "SELECT store_id, series_code, period, next_number, prefix FROM receipt_series"
            + " WHERE tenant_id = ? AND store_id = ? ORDER BY period DESC, series_code",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
        },
        rs ->
            new ReceiptSeries(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getString(3),
                rs.getLong(4),
                rs.getString(5)),
        "list receipt series config");
  }

  /**
   * Opens a series if it does not exist and sets the prefix it prints. The counter is never touched
   * here: a prefix change affects the documents issued after it, and every document already issued
   * keeps the full number it was printed with.
   *
   * @param tenantId owning tenant
   * @param storeId the store
   * @param series the series code
   * @param period the fiscal period
   * @param prefix what is printed in front of the number, or {@code null} for nothing
   * @return the counter as it now stands
   */
  public ReceiptSeries setSeriesPrefix(
      UUID tenantId, UUID storeId, String series, String period, String prefix) {
    exec(
        "INSERT INTO receipt_series (tenant_id, store_id, series_code, period, next_number, prefix)"
            + " VALUES (?,?,?,?,1,?)"
            + " ON CONFLICT (tenant_id, store_id, series_code, period)"
            + " DO UPDATE SET prefix = EXCLUDED.prefix",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
          ps.setString(3, series);
          ps.setString(4, period);
          ps.setString(5, prefix);
        },
        "set receipt series prefix");
    return listSeriesConfig(tenantId, storeId).stream()
        .filter(r -> r.seriesCode().equals(series) && r.period().equals(period))
        .findFirst()
        .orElseThrow();
  }

  /**
   * Every hole in a series — the inspector's question, answered by the database rather than by
   * assertion.
   *
   * <p>A row is returned for each number that has an issued receipt before it and none at it, up to
   * the highest number issued. An empty result is the proof that the sequence is intact; it is
   * deliberately not a boolean, because "there is a gap" is not a useful answer without "where".
   */
  public List<SequenceGap> findGaps(UUID tenantId, UUID storeId, String series, String period) {
    return query(
        "WITH s AS ("
            + "  SELECT number FROM fiscal_receipts"
            + "   WHERE tenant_id = ? AND store_id = ? AND series_code = ? AND period = ?"
            + ")"
            + " SELECT r.number + 1 AS gap_from,"
            + "        (SELECT MIN(n.number) FROM s n WHERE n.number > r.number) - 1 AS gap_to"
            + "   FROM s r"
            + "  WHERE NOT EXISTS (SELECT 1 FROM s n WHERE n.number = r.number + 1)"
            + "    AND r.number < (SELECT MAX(number) FROM s)"
            + "  ORDER BY 1",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
          ps.setString(3, series);
          ps.setString(4, period);
        },
        rs -> new SequenceGap(rs.getLong(1), rs.getLong(2)),
        "find sequence gaps");
  }

  /** First number, last number and how many were issued — the header of the same audit. */
  public long[] seriesBounds(UUID tenantId, UUID storeId, String series, String period) {
    var rows =
        query(
            "SELECT COALESCE(MIN(number),0), COALESCE(MAX(number),0), COUNT(*)"
                + " FROM fiscal_receipts"
                + " WHERE tenant_id = ? AND store_id = ? AND series_code = ? AND period = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, storeId);
              ps.setString(3, series);
              ps.setString(4, period);
            },
            rs -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)},
            "receipt series bounds");
    return rows.isEmpty() ? new long[] {0, 0, 0} : rows.get(0);
  }

  private static Instant instant(ResultSet rs, int col) throws SQLException {
    OffsetDateTime v = rs.getObject(col, OffsetDateTime.class);
    return v == null ? null : v.toInstant();
  }

  private static Long longOrNull(ResultSet rs, int col) throws SQLException {
    long v = rs.getLong(col);
    return rs.wasNull() ? null : v;
  }

  private static FiscalReceipt map(ResultSet rs) throws SQLException {
    TseStamp tse = null;
    // A stamp exists when the device signed, or when it failed and the failure is the record.
    if (rs.getString(23) != null || rs.getString(32) != null) {
      tse =
          new TseStamp(
              rs.getString(19),
              rs.getString(20),
              longOrNull(rs, 21),
              longOrNull(rs, 22),
              rs.getString(23),
              rs.getString(24),
              rs.getString(25),
              rs.getString(26),
              instant(rs, 27),
              instant(rs, 28),
              rs.getString(29),
              rs.getString(30),
              rs.getString(31),
              rs.getString(32));
    }
    PtStamp pt = null;
    if (rs.getString(34) != null) {
      String hash = rs.getString(34);
      pt =
          new PtStamp(
              rs.getString(33),
              hash,
              rs.getString(35),
              rs.getString(36),
              rs.getString(37),
              com.storeql.order.fiscal.PtSignature.excerpt(hash));
    }
    return new FiscalReceipt(
        (UUID) rs.getObject(1),
        (UUID) rs.getObject(2),
        (UUID) rs.getObject(3),
        rs.getString(4),
        rs.getString(5),
        rs.getLong(6),
        rs.getString(7),
        (UUID) rs.getObject(8),
        rs.getObject(9, OffsetDateTime.class).toInstant(),
        (UUID) rs.getObject(10),
        rs.getString(11),
        rs.getBigDecimal(12),
        rs.getBigDecimal(13),
        instant(rs, 14),
        rs.getString(15),
        rs.getString(16),
        rs.getString(17),
        rs.getString(18),
        tse,
        pt);
  }

  /**
   * The SHA-256 a document must carry: over the figures an inspector reads off it and the hash of
   * the document before, so a changed figure or a re-inserted row no longer matches (18.4). Since
   * 18.5 the regime's stamp is part of it when there is one, so a swapped device signature breaks
   * the chain too; a document with no stamp hashes exactly as it did before.
   *
   * @param r the document, with the previous hash it chains to
   * @return 64 hex characters
   */
  public static String hashOf(FiscalReceipt r) {
    StringBuilder canonical =
        new StringBuilder(
            String.join(
                "|",
                r.tenantId().toString(),
                r.storeId().toString(),
                r.seriesCode(),
                r.period(),
                Long.toString(r.number()),
                r.fullNumber(),
                r.orderId().toString(),
                r.issuedAt().toString(),
                r.currency(),
                r.grossTotal().setScale(4, java.math.RoundingMode.HALF_UP).toPlainString(),
                r.taxTotal().setScale(4, java.math.RoundingMode.HALF_UP).toPlainString(),
                r.prevHash()));
    if (r.tse() != null && r.tse().signature() != null) {
      canonical.append("|tse:").append(r.tse().signature());
    }
    if (r.pt() != null && r.pt().hash() != null) {
      canonical.append("|pt:").append(r.pt().hash());
    }
    try {
      var md = java.security.MessageDigest.getInstance("SHA-256");
      return java.util.HexFormat.of()
          .formatHex(md.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 missing", e);
    }
  }

  /** What the audit says about the chain: intact or not, from where, and where it breaks. */
  public record ChainVerdict(Boolean intact, Long from, Long brokenAt) {}

  /**
   * Walks a series in number order and re-derives every hash (18.4). Documents from before the
   * chain (null hash) are passed over; the first chained document is not held to a particular
   * previous hash, every later one must name its predecessor's, and each must equal the hash of its
   * own stored figures.
   *
   * @return intact null when nothing in the series is chained yet
   */
  public ChainVerdict verifyChain(UUID tenantId, UUID storeId, String series, String period) {
    // One page of the series in memory at a time, however long the year; the walk stops at the
    // first break.
    Long[] from = {null};
    String[] prev = {null};
    ChainVerdict[] broken = {null};
    forEachInSeries(
        tenantId,
        storeId,
        series,
        period,
        pageSize(),
        r -> {
          if (r.hash() == null) {
            if (from[0] != null) {
              broken[0] = new ChainVerdict(false, from[0], r.number());
              return false;
            }
            return true;
          }
          boolean linked = prev[0] == null || prev[0].equals(r.prevHash());
          if (!linked || r.prevHash() == null || !hashOf(r).equals(r.hash())) {
            broken[0] =
                new ChainVerdict(
                    false, from[0] == null ? Long.valueOf(r.number()) : from[0], r.number());
            return false;
          }
          if (from[0] == null) {
            from[0] = r.number();
          }
          prev[0] = r.hash();
          return true;
        });
    if (broken[0] != null) return broken[0];
    return new ChainVerdict(from[0] == null ? null : Boolean.TRUE, from[0], null);
  }

  /**
   * Receipts read per query when a whole series is walked: {@code storeql.order.fiscal.page-size}.
   */
  public static int pageSize() {
    return org.eclipse.microprofile.config.ConfigProvider.getConfig()
        .getOptionalValue("storeql.order.fiscal.page-size", Integer.class)
        .filter(n -> n > 0)
        .orElse(1000);
  }

  /** One order line under the document it was sold on, for the register exports. */
  public record RegisterLine(
      long number,
      UUID variantId,
      BigDecimal qty,
      BigDecimal unitPrice,
      BigDecimal lineTotal,
      /** As the quote priced it; null for a line placed with pricing enforcement off. */
      BigDecimal vatAmount) {}

  /**
   * Every order line behind every document in a series, in document order — the same service's
   * tables, joined here rather than fetched per document.
   */
  public List<RegisterLine> linesInSeries(
      UUID tenantId, UUID storeId, String series, String period) {
    return query(
        "SELECT fr.number, oi.variant_id, oi.qty, oi.unit_price, oi.line_total, oi.vat_amount"
            + " FROM fiscal_receipts fr"
            + " JOIN order_items oi ON oi.tenant_id = fr.tenant_id AND oi.order_id = fr.order_id"
            + " WHERE fr.tenant_id = ? AND fr.store_id = ? AND fr.series_code = ? AND fr.period = ?"
            + " ORDER BY fr.number, oi.created_at",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
          ps.setString(3, series);
          ps.setString(4, period);
        },
        rs ->
            new RegisterLine(
                rs.getLong(1),
                (UUID) rs.getObject(2),
                rs.getBigDecimal(3),
                rs.getBigDecimal(4),
                rs.getBigDecimal(5),
                rs.getBigDecimal(6)),
        "register lines");
  }

  /** One tender behind a document: how the sale was paid, for the fiscal files (18.5). */
  public record RegisterTender(long number, String method, BigDecimal amount) {}

  /**
   * Every captured tender behind every document in a series, from the payment ledger the
   * PaymentCaptured events built.
   */
  public List<RegisterTender> tendersInSeries(
      UUID tenantId, UUID storeId, String series, String period) {
    return query(
        "SELECT fr.number, pe.method, pe.amount"
            + " FROM fiscal_receipts fr"
            + " JOIN order_payment_events pe"
            + "   ON pe.tenant_id = fr.tenant_id AND pe.order_id = fr.order_id"
            + " WHERE fr.tenant_id = ? AND fr.store_id = ? AND fr.series_code = ? AND fr.period = ?"
            + " ORDER BY fr.number, pe.applied_at",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
          ps.setString(3, series);
          ps.setString(4, period);
        },
        rs -> new RegisterTender(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3)),
        "register tenders");
  }

  /**
   * The captured tenders behind one sale, from the payment ledger — what a German security module
   * signs as the payment split.
   */
  public List<RegisterTender> tendersOfOrder(UUID tenantId, UUID orderId) {
    return query(
        "SELECT 0, method, amount FROM order_payment_events"
            + " WHERE tenant_id = ? AND order_id = ? ORDER BY applied_at",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, orderId);
        },
        rs -> new RegisterTender(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3)),
        "tenders of order");
  }

  /** The order header behind a document: what the fiscal files need beyond the document itself. */
  public record RegisterOrder(
      long number,
      String channel,
      String paymentMethod,
      BigDecimal subtotal,
      BigDecimal taxAmount,
      BigDecimal total,
      UUID customerId) {}

  /** The order header behind every document in a series, in document order. */
  public List<RegisterOrder> ordersInSeries(
      UUID tenantId, UUID storeId, String series, String period) {
    return query(
        "SELECT fr.number, o.channel, o.payment_method, o.subtotal, o.tax_amount, o.total,"
            + " o.customer_id"
            + " FROM fiscal_receipts fr"
            + " JOIN orders o ON o.tenant_id = fr.tenant_id AND o.id = fr.order_id"
            + " WHERE fr.tenant_id = ? AND fr.store_id = ? AND fr.series_code = ? AND fr.period = ?"
            + " ORDER BY fr.number",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
          ps.setString(3, series);
          ps.setString(4, period);
        },
        rs ->
            new RegisterOrder(
                rs.getLong(1),
                rs.getString(2),
                rs.getString(3),
                rs.getBigDecimal(4),
                rs.getBigDecimal(5),
                rs.getBigDecimal(6),
                (UUID) rs.getObject(7)),
        "register orders");
  }
}
