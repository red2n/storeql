package com.storeql.order.api;

import com.storeql.order.mapper.Mappers;
import com.storeql.order.service.FiscalService;
import com.storeql.order.service.OrderService;
import com.storeql.web.ApiResponse;
import com.storeql.web.Parsing;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The gapless legal receipt sequence.
 *
 * <p>Separate from {@link ReceiptResource}, which records how many times a document was printed or
 * emailed. This is the document itself: one per sale, numbered consecutively within a series and
 * period, never renumbered, never deleted.
 *
 * <p>Numbers are taken automatically when a sale is confirmed. These endpoints exist for the two
 * cases that need a human: reading the number back, and proving to an inspector that the sequence
 * has no holes.
 */
@RequestScoped
@Path("/admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Fiscal receipts")
public class FiscalReceiptResource {

  @Inject OrderService svc;
  @Inject FiscalService fiscal;
  @Inject TenantContext ctx;

  /**
   * Allocates the numbered fiscal receipt for a completed sale.
   *
   * <p>Idempotent: a second call returns the document already issued rather than allocating another
   * number — a reprint is not a sale, and two numbers for one sale is how a day's takings get
   * counted twice. Normally unnecessary, since the number is taken when the sale is confirmed; this
   * is the recovery path for a sale that completed while issuance was failing.
   *
   * @param orderId the completed sale to receipt
   * @param series the numbering series, defaulting to {@code MAIN}
   * @return the receipt, newly issued or already existing
   * @throws com.storeql.web.ApiException {@code 400} when the order is not a completed sale —
   *     numbering a basket that is never paid for is where gaps come from; {@code 404} when the
   *     order does not exist
   */
  @Operation(
      summary = "Issue the numbered receipt for a sale",
      description =
          "Idempotent: a second call returns the document already issued rather than allocating"
              + " another number, because a reprint is not a sale and two numbers for one sale is"
              + " how a day's takings get counted twice.\\n\\n"
              + "Normally unnecessary — the number is taken when the sale is confirmed. This is"
              + " the recovery path for a sale that completed while receipt issuance was failing,"
              + " and it is safe to call at any time.\\n\\n"
              + "Refused for a PENDING or CANCELLED order: numbering a basket that is never paid"
              + " for is where gaps come from.")
  @APIResponse(responseCode = "200", description = "The receipt, newly issued or already existing")
  @APIResponse(responseCode = "400", description = "The order is not a completed sale")
  @APIResponse(responseCode = "404", description = "Order not found")
  @POST
  @Path("/orders/{orderId}/fiscal-receipt")
  public Response issue(@PathParam("orderId") UUID orderId, @QueryParam("series") String series) {
    return Response.ok(
            ApiResponse.ok(
                Mappers.toDto(
                    svc.issueReceipt(ctx.requireTenantId(), orderId, series, ctx.userId()))))
        .build();
  }

  /**
   * The receipt issued for a sale: its number, when it was issued, and whether the sale was later
   * voided.
   *
   * @param orderId the sale whose receipt to read
   * @return the receipt
   * @throws com.storeql.web.ApiException {@code 404} when no receipt has been issued for this sale
   */
  @Operation(
      summary = "The receipt issued for a sale",
      description = "Its number, when it was issued, and whether the sale was later voided.")
  @APIResponse(responseCode = "200", description = "The receipt")
  @APIResponse(responseCode = "404", description = "No receipt has been issued for this sale")
  @GET
  @Path("/orders/{orderId}/fiscal-receipt")
  public Response get(@PathParam("orderId") UUID orderId) {
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.receiptOf(ctx.requireTenantId(), orderId))))
        .build();
  }

  /**
   * The fiscal regime a store trades under (18.5), its registered device, and what this deployment
   * offers beside them.
   *
   * @param storeId the store
   * @return the settings; NONE when none were ever set
   */
  @Operation(
      summary = "The fiscal regime a store trades under",
      description =
          "NONE (the register alone), DE_KASSENSICHV (every sale signed by a security module,"
              + " DSFinV-K export) or PT_SAFT (every document RSA-signed, SAF-T (PT) export); the"
              + " store's device when it has one; and what the deployment offers: the regimes and"
              + " device providers available, and whether a Portuguese signing key is installed."
              + " Management-only.")
  @APIResponse(responseCode = "200", description = "The settings")
  @GET
  @Path("/fiscal-receipts/settings")
  public Response settings(@QueryParam("storeId") String storeId) {
    return Response.ok(
            ApiResponse.ok(
                Mappers.toDto(
                    fiscal.settings(ctx.requireTenantId(), Parsing.uuid(storeId, "storeId")))))
        .build();
  }

  /**
   * Places a store under a fiscal regime (18.5).
   *
   * @param req the regime and what it needs
   * @return the settings as they now stand
   */
  @Operation(
      summary = "Place a store under a fiscal regime",
      description =
          "DE_KASSENSICHV needs the store's tax number and a security module — tseProvider"
              + " SIMULATED registers one in software, CLOUD names one at the configured provider"
              + " by tseTssId. PT_SAFT needs a valid NIF and a signing key installed on the"
              + " server. Documents already issued keep the stamps they were issued with; the"
              + " change applies from the next sale. Management-only.")
  @APIResponse(responseCode = "200", description = "The settings as they now stand")
  @APIResponse(
      responseCode = "400",
      description = "An unknown regime, or a regime missing what it needs")
  @APIResponse(
      responseCode = "409",
      description =
          "The store is not this tenant's, or the server lacks the key or credentials the regime needs")
  @APIResponse(
      responseCode = "502",
      description =
          "FISCAL_TSE_UNAVAILABLE: the security module provider refused or could not be reached"
              + " when the device was registered; nothing was changed")
  @PUT
  @Path("/fiscal-receipts/settings")
  public Response setSettings(com.storeql.order.dto.Dtos.SetFiscalSettingsRequest req) {
    com.storeql.web.Validations.validate(req);
    return Response.ok(
            ApiResponse.ok(
                Mappers.toDto(
                    fiscal.setSettings(
                        ctx.requireTenantId(),
                        Parsing.uuid(req.storeId(), "storeId"),
                        new FiscalService.SettingsChange(
                            req.regime(),
                            req.taxRegistrationNumber(),
                            req.certificateNumber(),
                            req.seriesValidationCode(),
                            req.tseProvider(),
                            req.tseTssId(),
                            req.tseClientId()),
                        ctx.userId()))))
        .build();
  }

  @Operation(
      summary = "The series a store runs",
      description =
          "Each counter: series code, fiscal period, the next number it will hand out, and the"
              + " prefix it prints. Management-only.")
  @APIResponse(responseCode = "200", description = "The counters, newest period first")
  @GET
  @Path("/fiscal-receipts/series")
  public Response series(@QueryParam("storeId") String storeId) {
    return Response.ok(
            ApiResponse.ok(
                svc.receiptSeriesConfig(ctx.requireTenantId(), Parsing.uuid(storeId, "storeId"))))
        .build();
  }

  @Operation(
      summary = "Set a series prefix",
      description =
          "What is printed in front of the number, e.g. GB-LDN-01. Opens the series if it is new."
              + " The counter is never touched: documents already issued keep the full number they"
              + " were printed with. Management-only.")
  @APIResponse(responseCode = "200", description = "The counter as it now stands")
  @APIResponse(responseCode = "400", description = "A prefix or period outside the allowed shape")
  @PUT
  @Path("/fiscal-receipts/series")
  public Response setSeries(com.storeql.order.dto.Dtos.SetReceiptSeriesRequest req) {
    com.storeql.web.Validations.validate(req);
    return Response.ok(
            ApiResponse.ok(
                svc.setReceiptSeriesPrefix(
                    ctx.requireTenantId(),
                    Parsing.uuid(req.storeId(), "storeId"),
                    req.seriesCode(),
                    req.period(),
                    req.prefix())))
        .build();
  }

  /**
   * The register a store keeps: every receipt in a series, by number.
   *
   * @param storeId the store whose register to read
   * @param series the numbering series, defaulting to {@code MAIN}
   * @param period the fiscal year, e.g. {@code 2026}; defaults to the current UTC year
   * @param limit maximum rows, defaulting to 100
   * @return the receipts in number order
   */
  @Operation(
      summary = "Every receipt in a series, in order",
      description =
          "The register a store keeps. series defaults to MAIN; period is the fiscal year, e.g."
              + " 2026.")
  @APIResponse(responseCode = "200", description = "Receipts by number")
  @GET
  @Path("/fiscal-receipts")
  public Response list(
      @QueryParam("storeId") String storeId,
      @QueryParam("series") String series,
      @QueryParam("period") String period,
      @QueryParam("limit") @DefaultValue("100") int limit) {
    return Response.ok(
            ApiResponse.ok(
                svc
                    .receiptSeries(
                        ctx.requireTenantId(),
                        Parsing.uuid(storeId, "storeId"),
                        series,
                        requirePeriod(period),
                        limit)
                    .stream()
                    .map(Mappers::toDto)
                    .toList()))
        .build();
  }

  /**
   * Proves the numbering sequence has no holes — the inspector's question, answered by the database
   * rather than by assertion.
   *
   * <p>{@code intact: true} with an empty {@code gaps} array is the proof. A gap is not necessarily
   * fraud; it is the thing that has to be explained, which is why each is given as a range rather
   * than a count.
   *
   * @param storeId the store whose series to audit
   * @param series the numbering series, defaulting to {@code MAIN}
   * @param period the fiscal year; defaults to the current UTC year
   * @return first and last numbers, issued and expected counts, an {@code intact} flag, and every
   *     gap with its range
   */
  @Operation(
      summary = "Prove the sequence has no holes",
      description =
          "The inspector's question, answered by the database rather than by assertion. Returns"
              + " the first and last numbers issued, how many were issued, how many the span"
              + " implies, and every gap with its range.\\n\\n"
              + "`intact: true` with an empty `gaps` array is the proof. A gap is not necessarily"
              + " fraud — it is the thing that has to be explained, which is why the range is"
              + " given rather than a count.")
  @APIResponse(responseCode = "200", description = "The audit")
  @GET
  @Path("/fiscal-receipts/audit")
  public Response audit(
      @QueryParam("storeId") String storeId,
      @QueryParam("series") String series,
      @QueryParam("period") String period) {
    Map<String, Object> result =
        svc.receiptAudit(
            ctx.requireTenantId(), Parsing.uuid(storeId, "storeId"), series, requirePeriod(period));
    return Response.ok(ApiResponse.ok(result)).build();
  }

  /**
   * The register as a file (18.4).
   *
   * @param format {@code csv} (default) or {@code json}, the latter with the order lines
   * @return CSV text or a JSON document
   */
  @Operation(
      summary = "Export a series: the register, or the inspector's file",
      description =
          "Every document in the series, in number order, with the hash it was issued with and"
              + " the hash it chains to — as CSV rows, or as JSON with the order lines behind"
              + " each document. Since 18.5 also as the file a regime's inspector asks for:"
              + " format=dsfinvk is the German DSFinV-K 2.3 zip (cash-point closings, the"
              + " transactions with their TSE stamps, lines, VAT and payments, with index.xml);"
              + " format=saft-pt is the Portuguese SAF-T (PT) 1.04_01 sales-invoice file with"
              + " every document's signature and ATCUD. Both name the business and the store as"
              + " tenant-svc holds them and the products as product-svc names them, and refuse"
              + " with 503 rather than write a file that names nobody. Management-only.")
  @APIResponse(responseCode = "200", description = "The register or the file")
  @APIResponse(responseCode = "400", description = "An unknown format")
  @APIResponse(responseCode = "503", description = "The store's identity could not be read")
  @GET
  @Path("/fiscal-receipts/export")
  @Produces({"text/csv", MediaType.APPLICATION_JSON, "application/zip", MediaType.APPLICATION_XML})
  public Response export(
      @QueryParam("storeId") String storeId,
      @QueryParam("series") String series,
      @QueryParam("period") String period,
      @QueryParam("format") @DefaultValue("csv") String format) {
    String f = format == null ? "csv" : format.trim().toLowerCase(java.util.Locale.ROOT);
    if ("dsfinvk".equals(f) || "saft-pt".equals(f)) {
      FiscalService.Export file =
          fiscal.export(
              ctx.requireTenantId(),
              Parsing.uuid(storeId, "storeId"),
              series,
              requirePeriod(period),
              f,
              ctx);
      return Response.ok(file.bytes())
          .type(file.contentType())
          .header("Content-Disposition", "attachment; filename=\"" + file.fileName() + "\"")
          .build();
    }
    if (!"csv".equals(f) && !"json".equals(f)) {
      throw com.storeql.web.ApiException.badRequest(
          "FISCAL_EXPORT_FORMAT_UNKNOWN", "format must be csv, json, dsfinvk or saft-pt");
    }
    Object out =
        svc.exportRegister(
            ctx.requireTenantId(),
            Parsing.uuid(storeId, "storeId"),
            series,
            requirePeriod(period),
            format);
    if (out instanceof String csv) {
      return Response.ok(csv)
          .type("text/csv")
          .header(
              "Content-Disposition",
              "attachment; filename=\"receipts-" + requirePeriod(period) + ".csv\"")
          .build();
    }
    return Response.ok(ApiResponse.ok(out)).type(MediaType.APPLICATION_JSON).build();
  }

  private static String requirePeriod(String period) {
    if (period == null || period.isBlank()) {
      return String.valueOf(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).getYear());
    }
    return period.trim();
  }
}
