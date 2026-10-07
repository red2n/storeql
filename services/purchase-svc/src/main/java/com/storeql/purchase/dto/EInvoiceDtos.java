package com.storeql.purchase.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** The wire form of received supplier e-invoices (07.13). */
public final class EInvoiceDtos {

  private EInvoiceDtos() {}

  @Schema(
      name = "EInvoiceDelivery",
      description =
          "What a network is told when it delivers: the document's id in the receiver's inbox and"
              + " nothing of the receiver's own.")
  public record DeliveryResponse(
      UUID id,
      @Schema(description = "The network that delivered it.") String network,
      @Schema(description = "The network's reference, as it was presented.") String reference,
      @Schema(description = "These bytes had been received before; the first receipt's id.")
          boolean alreadyReceived,
      Instant receivedAt) {}

  @Schema(
      name = "SupplierEInvoiceResponse",
      description =
          "A supplier e-invoice as received: what it says, the EN 16931 and Peppol rules it broke on"
              + " arrival, and what it became — or what a person has to decide before it can.")
  public record SupplierEInvoiceResponse(
      UUID id,
      Instant receivedAt,
      @Schema(
              description =
                  "UPLOAD when a person posted it; PEPPOL, FR_PDP or SIMULATED when that network"
                      + " delivered it.")
          String channel,
      @Schema(description = "The network's own reference for the delivery; null for an upload.")
          String deliveryRef,
      @Schema(description = "XML, or PDF for a Factur-X or ZUGFeRD hybrid.") String container,
      @Schema(description = "UBL or CII.") String syntax,
      @Schema(description = "The PDF attachment the invoice was read from.")
          String embeddedFilename,
      @Schema(description = "UNTDID 1001: 380 invoice, 381 credit note, 384 corrected invoice.")
          String typeCode,
      boolean creditNote,
      String invoiceNumber,
      LocalDate issueDate,
      String currency,
      String sellerName,
      String sellerVatId,
      @Schema(description = "The seller's electronic address as scheme:identifier.")
          String sellerEndpoint,
      String buyerVatId,
      String buyerEndpoint,
      @Schema(description = "The purchase order reference the supplier gave (BT-13).")
          String orderReference,
      @Schema(description = "The invoice a credit note refers to (BT-25).") String precedingInvoice,
      BigDecimal netAmount,
      BigDecimal vatAmount,
      BigDecimal grossAmount,
      BigDecimal payableAmount,
      @Schema(
              description =
                  "NOT_COMPLIANT, MISDIRECTED, NEEDS_SUPPLIER, NEEDS_ORDER, NEEDS_LINES,"
                      + " NEEDS_RETURN, NEEDS_DECISION, DUPLICATE, CAPTURED, CREDITED or REFUSED.")
          String status,
      @Schema(description = "While it waits: what a person has to decide, in words.")
          String problem,
      UUID supplierId,
      UUID poId,
      @Schema(description = "CAPTURED: the supplier invoice it became.") UUID supplierInvoiceId,
      @Schema(description = "CREDITED: the return its credit note closed.") UUID vendorReturnId,
      Instant decidedAt,
      UUID decidedBy,
      String decisionReason,
      @Schema(description = "True when these exact bytes had been received before.")
          boolean alreadyReceived,
      List<RuleViolationResponse> violations,
      List<SupplierEInvoiceLineResponse> lines,
      @Schema(
              description =
                  "On a match asked to remember: what was not kept, and why, in words — a caller"
                      + " held to stores does not set a supplier's electronic address, the"
                      + " supplier's record being the whole business's. Null when everything asked"
                      + " was kept, and on every other answer.")
          String notRemembered) {}

  @Schema(name = "RuleViolationResponse", description = "One rule the document broke.")
  public record RuleViolationResponse(
      @Schema(description = "As the rule set names it: BR-CO-15, PEPPOL-EN16931-R003.") String rule,
      @Schema(description = "FATAL or WARNING.") String severity,
      String message) {}

  @Schema(name = "SupplierEInvoiceLineResponse", description = "One line, and its order line.")
  public record SupplierEInvoiceLineResponse(
      int position,
      String lineId,
      String itemName,
      String sellersItemId,
      String buyersItemId,
      String standardItemId,
      String orderLineReference,
      BigDecimal quantity,
      String unitCode,
      BigDecimal netAmount,
      BigDecimal netPrice,
      String vatCategory,
      BigDecimal vatRate,
      UUID poLineId,
      UUID variantId,
      @Schema(description = "ORDER_LINE, ITEM_CODE, PERSON, or null while unmatched.")
          String matchedBy) {}

  @Schema(
      name = "MatchSupplierEInvoiceRequest",
      description =
          "What a person decides so a waiting e-invoice can be captured: the supplier that sent it,"
              + " the order it bills, the return a credit note closes, and the order line of any"
              + " line that could not be matched. Anything left out is found as on arrival.")
  public record MatchSupplierEInvoiceRequest(
      UUID supplierId,
      UUID poId,
      UUID returnId,
      @Size(max = 500) List<@Valid LineChoiceRequest> lines,
      @Schema(
              description =
                  "True to remember the choices for this supplier: what its item codes are, and —"
                      + " for a caller held to no store — its electronic address, so its next"
                      + " invoice matches on its own. A caller held to stores is told the address"
                      + " was not kept (notRemembered); the match itself goes ahead.")
          boolean remember) {}

  @Schema(name = "LineChoiceRequest", description = "An invoice line, and the order line it is.")
  public record LineChoiceRequest(
      @Schema(description = "The line's position on the invoice, from 1.") @Min(1) int position,
      @NotNull UUID poLineId) {}

  @Schema(name = "RefuseSupplierEInvoiceRequest", description = "Why the e-invoice is refused.")
  public record RefuseSupplierEInvoiceRequest(@NotBlank @Size(max = 500) String reason) {}
}
