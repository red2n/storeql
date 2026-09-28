package com.storeql.purchase.repo;

import com.storeql.purchase.domain.EInvoiceIntake.ItemCode;
import com.storeql.purchase.domain.EInvoiceIntake.LineMatch;
import com.storeql.purchase.domain.EInvoiceIntake.SupplierRef;
import com.storeql.purchase.domain.SupplierEInvoices.Document;
import com.storeql.purchase.domain.SupplierEInvoices.Line;
import com.storeql.purchase.domain.SupplierEInvoices.Original;
import com.storeql.service.BaseOutboxRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC persistence for received supplier e-invoices (07.13). Every query filters by tenant first.
 */
@ApplicationScoped
public class SupplierEInvoiceRepository extends BaseOutboxRepository {

  /** What the business's e-invoice documents weigh, in bytes (21.11). */
  public long documentBytes(UUID tenantId) {
    return query(
            "SELECT COALESCE(SUM(octet_length(document)), 0) AS total FROM supplier_einvoices"
                + " WHERE tenant_id = ?",
            ps -> ps.setObject(1, tenantId),
            rs -> rs.getLong("total"),
            "measure e-invoice documents")
        .stream()
        .findFirst()
        .orElse(0L);
  }

  static final String COLUMNS =
      "id,tenant_id,received_at,received_by,channel,content_type,container,syntax,embedded_filename,"
          + "document_sha256,customization_id,type_code,invoice_number,issue_date,currency,seller_name,"
          + "seller_vat_id,seller_endpoint,buyer_vat_id,buyer_endpoint,order_reference,preceding_invoice,"
          + "net_amount,vat_amount,gross_amount,payable_amount,violations,status,problem,supplier_id,po_id,"
          + "supplier_invoice_id,vendor_return_id,decided_at,decided_by,decision_reason,updated_at,"
          + "delivery_ref";

  static final String LINE_COLUMNS =
      "id,tenant_id,einvoice_id,position,line_id,item_name,sellers_item_id,buyers_item_id,"
          + "standard_item_id,order_line_reference,quantity,unit_code,net_amount,net_price,vat_category,"
          + "vat_rate,po_line_id,variant_id,matched_by";

  /** The document received with these exact bytes, if any. */
  public Optional<UUID> findIdBySha(UUID tenantId, String sha256) {
    return query(
            "SELECT id FROM supplier_einvoices WHERE tenant_id = ? AND document_sha256 = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, sha256);
            },
            rs -> rs.getObject("id", UUID.class),
            "find supplier e-invoice by document")
        .stream()
        .findFirst();
  }

  /**
   * Keeps a received document, its original bytes and its lines in one transaction.
   *
   * @return false when the same bytes were kept first by another request
   */
  public boolean insert(Document d, byte[] bytes, List<Line> lines) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO supplier_einvoices ("
                      + COLUMNS
                      + ",document) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,"
                      + "?::jsonb,?,?,?,?,?,?,?,?,?,?,?,?)"
                      + " ON CONFLICT (tenant_id, document_sha256) DO NOTHING")) {
            ps.setObject(1, d.id());
            ps.setObject(2, d.tenantId());
            ps.setObject(3, odt(d.receivedAt()));
            ps.setObject(4, d.receivedBy());
            ps.setString(5, d.channel());
            ps.setString(6, d.contentType());
            ps.setString(7, d.container());
            ps.setString(8, d.syntax());
            ps.setString(9, d.embeddedFilename());
            ps.setString(10, d.sha256());
            ps.setString(11, d.customizationId());
            ps.setString(12, d.typeCode());
            ps.setString(13, d.invoiceNumber());
            ps.setObject(14, d.issueDate());
            ps.setString(15, d.currency());
            ps.setString(16, d.sellerName());
            ps.setString(17, d.sellerVatId());
            ps.setString(18, d.sellerEndpoint());
            ps.setString(19, d.buyerVatId());
            ps.setString(20, d.buyerEndpoint());
            ps.setString(21, d.orderReference());
            ps.setString(22, d.precedingInvoice());
            ps.setBigDecimal(23, d.netAmount());
            ps.setBigDecimal(24, d.vatAmount());
            ps.setBigDecimal(25, d.grossAmount());
            ps.setBigDecimal(26, d.payableAmount());
            ps.setString(27, d.violationsJson());
            ps.setString(28, d.status());
            ps.setString(29, d.problem());
            ps.setObject(30, d.supplierId());
            ps.setObject(31, d.poId());
            ps.setObject(32, d.supplierInvoiceId());
            ps.setObject(33, d.vendorReturnId());
            ps.setObject(34, odt(d.decidedAt()));
            ps.setObject(35, d.decidedBy());
            ps.setString(36, d.decisionReason());
            ps.setObject(37, odt(d.updatedAt()));
            ps.setString(38, d.deliveryRef());
            ps.setBytes(39, bytes);
            if (ps.executeUpdate() == 0) return false;
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO supplier_einvoice_lines ("
                      + LINE_COLUMNS
                      + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            for (Line l : lines) {
              ps.setObject(1, l.id());
              ps.setObject(2, l.tenantId());
              ps.setObject(3, l.einvoiceId());
              ps.setInt(4, l.position());
              ps.setString(5, l.lineId());
              ps.setString(6, l.itemName());
              ps.setString(7, l.sellersItemId());
              ps.setString(8, l.buyersItemId());
              ps.setString(9, l.standardItemId());
              ps.setString(10, l.orderLineReference());
              ps.setBigDecimal(11, l.quantity());
              ps.setString(12, l.unitCode());
              ps.setBigDecimal(13, l.netAmount());
              ps.setBigDecimal(14, l.netPrice());
              ps.setString(15, l.vatCategory());
              ps.setBigDecimal(16, l.vatRate());
              ps.setObject(17, l.poLineId());
              ps.setObject(18, l.variantId());
              ps.setString(19, l.matchedBy());
              ps.addBatch();
            }
            ps.executeBatch();
          }
          return true;
        },
        "receive supplier e-invoice");
  }

  public Optional<Document> find(UUID tenantId, UUID id) {
    return query(
            "SELECT " + COLUMNS + " FROM supplier_einvoices WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            SupplierEInvoiceRepository::map,
            "find supplier e-invoice")
        .stream()
        .findFirst();
  }

  /** Newest first, all or those with one status. */
  public List<Document> list(UUID tenantId, String status, int limit) {
    if (status == null) {
      return query(
          "SELECT "
              + COLUMNS
              + " FROM supplier_einvoices WHERE tenant_id = ?"
              + " ORDER BY received_at DESC, id DESC LIMIT ?",
          ps -> {
            ps.setObject(1, tenantId);
            ps.setInt(2, limit);
          },
          SupplierEInvoiceRepository::map,
          "list supplier e-invoices");
    }
    return query(
        "SELECT "
            + COLUMNS
            + " FROM supplier_einvoices WHERE tenant_id = ? AND status = ?"
            + " ORDER BY received_at DESC, id DESC LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setString(2, status);
          ps.setInt(3, limit);
        },
        SupplierEInvoiceRepository::map,
        "list supplier e-invoices by status");
  }

  public List<Line> lines(UUID tenantId, UUID einvoiceId) {
    return query(
        "SELECT "
            + LINE_COLUMNS
            + " FROM supplier_einvoice_lines WHERE tenant_id = ? AND einvoice_id = ? ORDER BY position",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, einvoiceId);
        },
        SupplierEInvoiceRepository::mapLine,
        "list supplier e-invoice lines");
  }

  /** The document exactly as it arrived. */
  public Optional<Original> original(UUID tenantId, UUID id) {
    return query(
            "SELECT content_type, container, invoice_number, document FROM supplier_einvoices"
                + " WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            rs ->
                new Original(
                    rs.getString("content_type"),
                    rs.getString("container"),
                    rs.getString("invoice_number"),
                    rs.getBytes("document")),
            "read supplier e-invoice document")
        .stream()
        .findFirst();
  }

  /**
   * Takes the document for one request: only while it waits in one of the given statuses, and only
   * when no other request holds it or its hold has lapsed.
   */
  public boolean claim(UUID tenantId, UUID id, UUID token, Collection<String> fromStatuses) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE supplier_einvoices SET claim_token = ?, claimed_at = now()"
                      + " WHERE tenant_id = ? AND id = ? AND status = ANY(?)"
                      + " AND (claim_token IS NULL OR claimed_at < now() - interval '2 minutes')")) {
            ps.setObject(1, token);
            ps.setObject(2, tenantId);
            ps.setObject(3, id);
            ps.setArray(4, c.createArrayOf("text", fromStatuses.toArray()));
            return ps.executeUpdate() == 1;
          }
        },
        "claim supplier e-invoice");
  }

  /** Lets go of a claim without settling anything. */
  public void release(UUID tenantId, UUID id, UUID token) {
    exec(
        "UPDATE supplier_einvoices SET claim_token = NULL, claimed_at = NULL"
            + " WHERE tenant_id = ? AND id = ? AND claim_token = ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, id);
          ps.setObject(3, token);
        },
        "release supplier e-invoice");
  }

  /**
   * Records what the document became or waits for, and the order line each line was matched to, and
   * lets go of the claim — only while the claim is still this request's.
   *
   * @param matches the lines' matches, or null to leave the lines as they are
   * @return false when the claim had lapsed and been taken by another request
   */
  public boolean settle(
      UUID tenantId,
      UUID id,
      UUID token,
      String status,
      String problem,
      UUID supplierId,
      UUID poId,
      UUID supplierInvoiceId,
      UUID vendorReturnId,
      List<LineMatch> matches,
      Instant decidedAt,
      UUID decidedBy,
      String decisionReason) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE supplier_einvoices SET status = ?, problem = ?, supplier_id = ?, po_id = ?,"
                      + " supplier_invoice_id = ?, vendor_return_id = ?,"
                      + " decided_at = COALESCE(?, decided_at), decided_by = COALESCE(?, decided_by),"
                      + " decision_reason = COALESCE(?, decision_reason),"
                      + " claim_token = NULL, claimed_at = NULL, updated_at = now()"
                      + " WHERE tenant_id = ? AND id = ? AND claim_token = ?")) {
            ps.setString(1, status);
            ps.setString(2, problem);
            ps.setObject(3, supplierId);
            ps.setObject(4, poId);
            ps.setObject(5, supplierInvoiceId);
            ps.setObject(6, vendorReturnId);
            ps.setObject(7, odt(decidedAt));
            ps.setObject(8, decidedBy);
            ps.setString(9, decisionReason);
            ps.setObject(10, tenantId);
            ps.setObject(11, id);
            ps.setObject(12, token);
            if (ps.executeUpdate() == 0) return false;
          }
          if (matches != null) {
            try (PreparedStatement ps =
                c.prepareStatement(
                    "UPDATE supplier_einvoice_lines SET po_line_id = ?, variant_id = ?, matched_by = ?"
                        + " WHERE tenant_id = ? AND einvoice_id = ? AND position = ?")) {
              for (LineMatch m : matches) {
                ps.setObject(1, m.poLineId());
                ps.setObject(2, m.variantId());
                ps.setString(3, m.matchedBy());
                ps.setObject(4, tenantId);
                ps.setObject(5, id);
                ps.setInt(6, m.position());
                ps.addBatch();
              }
              ps.executeBatch();
            }
          }
          return true;
        },
        "settle supplier e-invoice");
  }

  /** What a supplier's item codes were matched to by a person, oldest first. */
  public List<ItemCode> itemCodes(UUID tenantId, UUID supplierId) {
    return query(
        "SELECT kind, code, variant_id FROM supplier_item_codes"
            + " WHERE tenant_id = ? AND supplier_id = ? ORDER BY created_at, id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, supplierId);
        },
        rs ->
            new ItemCode(
                rs.getString("kind"), rs.getString("code"), rs.getObject("variant_id", UUID.class)),
        "list supplier item codes");
  }

  /** Remembers what one of a supplier's item codes is; a person's later answer replaces it. */
  public void learn(
      UUID id,
      UUID tenantId,
      UUID supplierId,
      String kind,
      String code,
      UUID variantId,
      UUID from,
      UUID by) {
    exec(
        "INSERT INTO supplier_item_codes"
            + " (id,tenant_id,supplier_id,kind,code,variant_id,learned_from,created_by,created_at)"
            + " VALUES (?,?,?,?,?,?,?,?,now())"
            + " ON CONFLICT (tenant_id, supplier_id, kind, code) DO UPDATE SET"
            + " variant_id = EXCLUDED.variant_id, learned_from = EXCLUDED.learned_from,"
            + " created_by = EXCLUDED.created_by, created_at = EXCLUDED.created_at",
        ps -> {
          ps.setObject(1, id);
          ps.setObject(2, tenantId);
          ps.setObject(3, supplierId);
          ps.setString(4, kind);
          ps.setString(5, code);
          ps.setObject(6, variantId);
          ps.setObject(7, from);
          ps.setObject(8, by);
        },
        "learn supplier item code");
  }

  /** The suppliers an e-invoice can be from: those with an electronic address or a VAT number. */
  public List<SupplierRef> supplierRefs(UUID tenantId) {
    return query(
        "SELECT id, name, vat_number, einvoice_scheme, einvoice_id FROM suppliers"
            + " WHERE tenant_id = ? AND (vat_number IS NOT NULL OR einvoice_id IS NOT NULL)"
            + " ORDER BY name LIMIT 10000",
        ps -> ps.setObject(1, tenantId),
        rs ->
            new SupplierRef(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("vat_number"),
                rs.getString("einvoice_scheme"),
                rs.getString("einvoice_id")),
        "list suppliers for e-invoice matching");
  }

  /** The order a supplier's invoice with this number was captured against. */
  public Optional<UUID> orderOfInvoice(UUID tenantId, UUID supplierId, String invoiceNumber) {
    return query(
            "SELECT po_id FROM supplier_invoices"
                + " WHERE tenant_id = ? AND supplier_id = ? AND lower(invoice_number) = lower(?)",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, supplierId);
              ps.setString(3, invoiceNumber);
            },
            rs -> rs.getObject("po_id", UUID.class),
            "find the order of a supplier invoice")
        .stream()
        .findFirst();
  }

  static Document map(ResultSet rs) throws SQLException {
    return new Document(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        instant(rs, "received_at"),
        rs.getObject("received_by", UUID.class),
        rs.getString("channel"),
        rs.getString("delivery_ref"),
        rs.getString("content_type"),
        rs.getString("container"),
        rs.getString("syntax"),
        rs.getString("embedded_filename"),
        rs.getString("document_sha256"),
        rs.getString("customization_id"),
        rs.getString("type_code"),
        rs.getString("invoice_number"),
        rs.getObject("issue_date", LocalDate.class),
        rs.getString("currency"),
        rs.getString("seller_name"),
        rs.getString("seller_vat_id"),
        rs.getString("seller_endpoint"),
        rs.getString("buyer_vat_id"),
        rs.getString("buyer_endpoint"),
        rs.getString("order_reference"),
        rs.getString("preceding_invoice"),
        rs.getBigDecimal("net_amount"),
        rs.getBigDecimal("vat_amount"),
        rs.getBigDecimal("gross_amount"),
        rs.getBigDecimal("payable_amount"),
        rs.getString("violations"),
        rs.getString("status"),
        rs.getString("problem"),
        rs.getObject("supplier_id", UUID.class),
        rs.getObject("po_id", UUID.class),
        rs.getObject("supplier_invoice_id", UUID.class),
        rs.getObject("vendor_return_id", UUID.class),
        instant(rs, "decided_at"),
        rs.getObject("decided_by", UUID.class),
        rs.getString("decision_reason"),
        instant(rs, "updated_at"));
  }

  static Line mapLine(ResultSet rs) throws SQLException {
    return new Line(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("einvoice_id", UUID.class),
        rs.getInt("position"),
        rs.getString("line_id"),
        rs.getString("item_name"),
        rs.getString("sellers_item_id"),
        rs.getString("buyers_item_id"),
        rs.getString("standard_item_id"),
        rs.getString("order_line_reference"),
        rs.getBigDecimal("quantity"),
        rs.getString("unit_code"),
        rs.getBigDecimal("net_amount"),
        rs.getBigDecimal("net_price"),
        rs.getString("vat_category"),
        rs.getBigDecimal("vat_rate"),
        rs.getObject("po_line_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getString("matched_by"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
    return t == null ? null : t.toInstant();
  }

  private static OffsetDateTime odt(Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }
}
