package com.storeql.purchase.api;

import com.storeql.purchase.domain.SupplierEInvoices.Original;
import com.storeql.purchase.dto.EInvoiceDtos.MatchSupplierEInvoiceRequest;
import com.storeql.purchase.dto.EInvoiceDtos.RefuseSupplierEInvoiceRequest;
import com.storeql.purchase.mapper.EInvoiceMappers;
import com.storeql.purchase.service.EInvoiceDeliveryService;
import com.storeql.purchase.service.EInvoiceDeliveryService.Delivery;
import com.storeql.purchase.service.SupplierEInvoiceService;
import com.storeql.purchase.service.SupplierEInvoiceService.Receipt;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Supplier e-invoices received (07.13): EN 16931 invoices and credit notes in UBL or CII, or inside
 * a Factur-X or ZUGFeRD PDF, read, checked against CEN's and Peppol's rules, and captured through
 * the three-way match — or kept waiting for a person, with the reason.
 */
@RequestScoped
@Path("/e-invoices")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Supplier E-Invoices")
public class SupplierEInvoiceResource {

  @Inject SupplierEInvoiceService svc;
  @Inject EInvoiceDeliveryService deliveries;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Receive a supplier e-invoice",
      description =
          "The body is the document itself: UBL or CII XML (application/xml), or a Factur-X or"
              + " ZUGFeRD PDF (application/pdf). It is read with DTDs refused and its size capped,"
              + " checked against EN 16931 and — when it says it is one — Peppol BIS Billing 3.0,"
              + " and kept exactly as it arrived. It is then matched: the supplier by the electronic"
              + " address it was sent from or the seller's VAT identifier; the purchase order by the"
              + " id in its order reference; each line by the order line it references or an item"
              + " code a person matched before. When all of that is found it is captured through the"
              + " three-way match, as a keyed invoice is, and a credit note closes the return it"
              + " credits. When something is not, it waits with the reason — NEEDS_SUPPLIER,"
              + " NEEDS_ORDER, NEEDS_LINES, NEEDS_RETURN, NEEDS_DECISION — and a document that breaks"
              + " a fatal rule, or is addressed to another business, is kept and not captured."
              + " A document that bills an order at a store the sender is not held to is kept"
              + " NEEDS_DECISION for a person who may act there, never captured on the sender's"
              + " word, and the sender is answered without that order (its id, its lines' order"
              + " lines, what it became)."
              + " Sending the same bytes again answers 200 with the document already received.")
  @APIResponse(responseCode = "201", description = "Received, and captured or waiting")
  @APIResponse(responseCode = "200", description = "These exact bytes were received before")
  @APIResponse(responseCode = "400", description = "Empty, or not an e-invoice that can be read")
  @APIResponse(responseCode = "413", description = "Larger than an e-invoice may be")
  @APIResponse(responseCode = "415", description = "Not XML or PDF")
  @APIResponse(
      responseCode = "403",
      description =
          "STORE_ACCESS_DENIED: these bytes were received before and now bill an order at a store"
              + " the caller is not held to")
  @POST
  @Consumes({"application/xml", "text/xml", "application/pdf", "application/octet-stream"})
  public Response receive(
      byte[] document, @HeaderParam(HttpHeaders.CONTENT_TYPE) String contentType) {
    Receipt r = svc.receive(ctx, document, contentType);
    return Response.status(r.alreadyReceived() ? 200 : 201).entity(ApiResponse.ok(dto(r))).build();
  }

  @Operation(
      summary = "A network delivers a supplier's e-invoice",
      description =
          "What a Peppol access point, France's approved platform or this platform's own simulated"
              + " network calls (07.13, the transport seam). No token: the request presents the"
              + " deployment's delivery key as X-EInvoice-Key, and the receiver is the business the"
              + " document names as its buyer — by electronic address, else by VAT identifier —"
              + " never anything in the request. The network's own reference, X-EInvoice-Reference,"
              + " is kept with the document. The body is the document, as for an upload: it is"
              + " read, checked and matched the same way, and the network learns the document's id"
              + " and nothing of the receiver's own. The same bytes delivered again answer 200.")
  @APIResponse(responseCode = "201", description = "Delivered into the receiver's inbox")
  @APIResponse(responseCode = "200", description = "These bytes were already there")
  @APIResponse(
      responseCode = "400",
      description =
          "A network that does not deliver in, a document that cannot be read, a reference"
              + " too long")
  @APIResponse(responseCode = "401", description = "The delivery key is missing or wrong")
  @APIResponse(responseCode = "404", description = "No business on this platform is the buyer")
  @APIResponse(responseCode = "409", description = "More than one business holds the address")
  @APIResponse(responseCode = "413", description = "Larger than an e-invoice may be")
  @APIResponse(responseCode = "415", description = "Not XML or PDF")
  @APIResponse(responseCode = "422", description = "The document names no buyer to deliver to")
  @APIResponse(
      responseCode = "503",
      description = "This deployment takes no deliveries, or the directory could not be asked")
  @POST
  @Path("/inbound/{network}")
  @Consumes({"application/xml", "text/xml", "application/pdf", "application/octet-stream"})
  public Response deliver(
      @PathParam("network") String network,
      byte[] document,
      @HeaderParam(HttpHeaders.CONTENT_TYPE) String contentType,
      @HeaderParam(com.storeql.web.HttpHeaders.EINVOICE_KEY) String key,
      @HeaderParam(com.storeql.web.HttpHeaders.EINVOICE_REFERENCE) String reference) {
    Delivery d = deliveries.deliver(ctx, network, key, reference, document, contentType);
    return Response.status(d.alreadyReceived() ? 200 : 201)
        .entity(ApiResponse.ok(EInvoiceMappers.toDto(d.document(), d.alreadyReceived())))
        .build();
  }

  @Operation(
      summary = "List received supplier e-invoices",
      description =
          "Newest first; ?status= narrows to one status — NEEDS_LINES is the queue waiting for a"
              + " person to match lines. A caller held to stores sees the documents that name no"
              + " order yet and those billing an order at one of their stores.")
  @APIResponse(responseCode = "200", description = "The e-invoices")
  @APIResponse(responseCode = "400", description = "A status that does not exist")
  @GET
  public Response list(
      @QueryParam("status") String status, @QueryParam("limit") @DefaultValue("50") int limit) {
    return Response.ok(
            ApiResponse.ok(
                svc.list(ctx, status, Math.min(Math.max(limit, 1), 100)).stream()
                    .map(d -> EInvoiceMappers.toDto(d, List.of(), false))
                    .toList()))
        .build();
  }

  @Operation(summary = "One received supplier e-invoice, with its lines and the rules it broke")
  @APIResponse(responseCode = "200", description = "The e-invoice")
  @APIResponse(responseCode = "404", description = "No such e-invoice")
  @APIResponse(
      responseCode = "403",
      description = "STORE_ACCESS_DENIED: it bills an order at a store the caller is not held to")
  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") UUID id) {
    return Response.ok(ApiResponse.ok(dto(svc.get(ctx, id)))).build();
  }

  @Operation(
      summary = "The document as it arrived",
      description =
          "The XML or PDF exactly as the supplier sent it: for an e-invoice the file is the invoice,"
              + " and it is kept unaltered.")
  @APIResponse(responseCode = "200", description = "The original document")
  @APIResponse(responseCode = "404", description = "No such e-invoice")
  @APIResponse(
      responseCode = "403",
      description = "STORE_ACCESS_DENIED: it bills an order at a store the caller is not held to")
  @GET
  @Path("/{id}/document")
  @Produces({"application/xml", "text/xml", "application/pdf", "application/octet-stream"})
  public Response document(@PathParam("id") UUID id) {
    Original o = svc.original(ctx, id);
    String extension = "PDF".equals(o.container()) ? "pdf" : "xml";
    String name =
        o.invoiceNumber() == null
            ? id.toString()
            : o.invoiceNumber().replaceAll("[^A-Za-z0-9._-]", "_");
    return Response.ok(o.bytes(), o.contentType())
        .header("Content-Disposition", "attachment; filename=\"" + name + "." + extension + "\"")
        .header("X-Content-Type-Options", "nosniff")
        .build();
  }

  @Operation(
      summary = "Match a waiting supplier e-invoice",
      description =
          "Gives what intake could not find: the supplier that sent it, the order it bills, the return"
              + " a credit note closes, the order line of any unmatched line. Anything left out is"
              + " found again as on arrival, and when that is enough the invoice is captured."
              + " remember keeps the choices — what the supplier's item codes are and, for a caller"
              + " held to no store, its electronic address — so its next invoice matches by itself;"
              + " a caller held to stores is told the address was not kept (notRemembered), the"
              + " supplier's record being the whole business's.")
  @APIResponse(responseCode = "200", description = "Captured, or still waiting with the reason")
  @APIResponse(responseCode = "400", description = "A choice that does not fit the invoice")
  @APIResponse(responseCode = "404", description = "No such e-invoice, supplier or order")
  @APIResponse(
      responseCode = "409",
      description =
          "Already settled (PURCHASE_EINVOICE_SETTLED), not compliant, addressed elsewhere, or being"
              + " matched by another request (PURCHASE_EINVOICE_BUSY)")
  @APIResponse(
      responseCode = "403",
      description =
          "STORE_ACCESS_DENIED: the order it bills, or the order chosen or named for it, is at a"
              + " store the caller is not held to; refused before anything is decided or kept")
  @POST
  @Path("/{id}/match")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response match(@PathParam("id") UUID id, MatchSupplierEInvoiceRequest req) {
    Validations.validate(req);
    return Response.ok(ApiResponse.ok(dto(svc.match(ctx, id, req)))).build();
  }

  @Operation(
      summary = "Refuse a waiting supplier e-invoice",
      description =
          "Needs purchasing.invoices.decide; the reason is what the supplier is to be told.")
  @APIResponse(responseCode = "200", description = "Refused")
  @APIResponse(responseCode = "400", description = "No reason")
  @APIResponse(
      responseCode = "403",
      description =
          "Without purchasing.invoices.decide, or STORE_ACCESS_DENIED: it bills an order at a"
              + " store the caller is not held to")
  @APIResponse(responseCode = "404", description = "PURCHASE_EINVOICE_NOT_FOUND")
  @APIResponse(responseCode = "409", description = "Already settled, or busy")
  @POST
  @Path("/{id}/refuse")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response refuse(@PathParam("id") UUID id, RefuseSupplierEInvoiceRequest req) {
    Validations.validate(req);
    return Response.ok(ApiResponse.ok(dto(svc.refuse(ctx, id, req)))).build();
  }

  private static Object dto(Receipt r) {
    return EInvoiceMappers.toDto(r.document(), r.lines(), r.alreadyReceived(), r.notRemembered());
  }
}
