package com.storeql.order.domain;

import com.storeql.einvoice.Violation;
import com.storeql.order.config.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Invoices and credit notes to business buyers, as issued and kept (18.9). */
public final class SalesInvoices {

  /** The invoice series. */
  public static final String SERIES_INVOICE = "INV";

  /** The credit note series. */
  public static final String SERIES_CREDIT_NOTE = "CRN";

  /** Download formats. */
  public static final String FORMAT_UBL = "UBL";

  public static final String FORMAT_CII = "CII";
  public static final String FORMAT_FACTURX = "FACTURX";
  public static final String FORMAT_IRP = "IRP";

  private SalesInvoices() {}

  /**
   * A document as issued.
   *
   * @param document the UBL as issued
   * @param irpPayload India's INV-01 JSON, when the business is Indian and it passes the checks
   * @param irpProblems what the portal would refuse, as JSON, when it would
   */
  public record SalesInvoice(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID orderId,
      UUID returnId,
      String typeCode,
      String seriesCode,
      String period,
      long number,
      String fullNumber,
      LocalDate issueDate,
      Instant issuedAt,
      UUID issuedBy,
      UUID customerId,
      String buyerName,
      String buyerVatId,
      String currency,
      BigDecimal netAmount,
      BigDecimal vatAmount,
      BigDecimal payableAmount,
      UUID precedingInvoiceId,
      String customizationId,
      String document,
      String irpPayload,
      String irpProblems) {

    public boolean creditNote() {
      return SalesInvoiceDraft.TYPE_CREDIT_NOTE.equals(typeCode);
    }
  }

  /**
   * What a document is before its number: who it is for, what it bills, and how to write it once
   * the number is known.
   */
  public record Pending(
      UUID tenantId,
      UUID storeId,
      UUID orderId,
      UUID returnId,
      String typeCode,
      String seriesCode,
      String period,
      LocalDate issueDate,
      UUID issuedBy,
      UUID customerId,
      String buyerName,
      String buyerVatId,
      String currency,
      UUID precedingInvoiceId) {}

  /** The document written once its number is known. */
  public record Written(
      String customizationId,
      BigDecimal netAmount,
      BigDecimal vatAmount,
      BigDecimal payableAmount,
      String document,
      String irpPayload,
      String irpProblems) {}

  /** INV/2026/000001: short enough for India's 16 characters, and ordered as text. */
  public static String fullNumber(String series, String period, long number) {
    return series + "/" + period + "/" + String.format("%06d", number);
  }

  /** Rule violations as {@code irp_problems} keeps them. */
  public static String problemsJson(List<Violation> violations) {
    JsonArrayBuilder a = Json.createArrayBuilder();
    for (Violation v : violations) {
      a.add(
          Json.createObjectBuilder()
              .add("rule", v.rule())
              .add("severity", v.severity().name())
              .add("message", v.message()));
    }
    return a.build().toString();
  }

  /** {@code irp_problems} as "rule: message" lines; empty when there are none. */
  public static List<String> problems(String json) {
    if (json == null || json.isBlank()) return List.of();
    try (JsonReader reader = Json.createReader(new StringReader(json))) {
      List<String> out = new ArrayList<>();
      for (JsonValue v : reader.readArray()) {
        JsonObject o = v.asJsonObject();
        out.add(o.getString("rule", "") + ": " + o.getString("message", ""));
      }
      return List.copyOf(out);
    }
  }
}
