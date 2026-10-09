package com.storeql.product.api;

import com.storeql.ids.Ids;
import com.storeql.product.domain.imports.ImportMapping;
import com.storeql.product.domain.imports.ImportMapping.AliasColumn;
import com.storeql.product.domain.imports.ImportRecords.Job;
import com.storeql.product.domain.imports.ImportRecords.Mapping;
import com.storeql.product.domain.imports.ImportRecords.RowResult;
import com.storeql.product.dto.ImportDtos.ImportJobResponse;
import com.storeql.product.dto.ImportDtos.ImportMappingRequest;
import com.storeql.product.dto.ImportDtos.ImportMappingResponse;
import com.storeql.product.dto.ImportDtos.ImportRowResponse;
import com.storeql.product.service.ImportService;
import com.storeql.web.ApiException;
import com.storeql.web.ApiResponse;
import com.storeql.web.Cursor;
import com.storeql.web.Parsing;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Importing a supermarket's own export (intent/catalogue-import.md): save a mapping, upload a file
 * for a dry run, read the report. Owners and managers of the whole business only.
 */
@RequestScoped
@Path("/admin/catalogue-imports")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Catalogue import")
public class ImportResource {

  @Inject ImportService svc;
  @Inject com.storeql.product.service.ImportReconciler reconciler;
  @Inject TenantContext ctx;

  // ── mappings ─────────────────────────────────────────────────────────────

  @Operation(
      summary = "Save a column mapping",
      description =
          "Saves how a business's export maps onto StoreQL under a name, replacing the one of that"
              + " name. A mapping that cannot be used is 400 IMPORT_MAPPING_INVALID listing why.")
  @APIResponse(responseCode = "200", description = "Saved")
  @APIResponse(responseCode = "400", description = "IMPORT_MAPPING_INVALID")
  @PUT
  @Path("/mappings/{name}")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response saveMapping(@PathParam("name") String name, ImportMappingRequest req) {
    if (req == null || req.columns() == null) {
      throw ApiException.badRequest("IMPORT_MAPPING_INVALID", "the mapping names no columns");
    }
    ImportMapping m =
        new ImportMapping(
            req.columns(),
            req.aliasColumns() == null
                ? List.of()
                : req.aliasColumns().stream()
                    .map(a -> new AliasColumn(a.header(), a.kind(), a.packQty()))
                    .toList(),
            upper(req.vatCodes()),
            req.defaultVatCode(),
            req.priceBasis(),
            req.decimalMark() == null || req.decimalMark().isEmpty()
                ? '.'
                : req.decimalMark().charAt(0),
            req.dateFormat(),
            upper(req.soldByValues()),
            req.categorySeparator() == null ? ">" : req.categorySeparator());
    return Response.ok(ApiResponse.ok(toDto(svc.saveMapping(ctx, name, m)))).build();
  }

  @Operation(summary = "Read a saved mapping")
  @GET
  @Path("/mappings/{name}")
  public Response getMapping(@PathParam("name") String name) {
    return Response.ok(ApiResponse.ok(toDto(svc.getMapping(ctx, name)))).build();
  }

  @Operation(summary = "List the saved mappings")
  @GET
  @Path("/mappings")
  public Response listMappings() {
    return Response.ok(
            ApiResponse.ok(svc.listMappings(ctx).stream().map(ImportResource::toDto).toList()))
        .build();
  }

  // ── the dry run ──────────────────────────────────────────────────────────

  @Operation(
      summary = "Upload a file for a dry run",
      description =
          "The body is the CSV file itself (up to 12 MB and 25,000 rows). Every row is read and"
              + " judged against the catalogue and the report is saved; nothing of the catalogue,"
              + " its prices or its stock is written. One store's export per file. A retry under"
              + " the same Idempotency-Key answers with the first job.")
  @APIResponse(responseCode = "201", description = "The job, with its report")
  @APIResponse(responseCode = "400", description = "The file cannot be read as a table")
  @APIResponse(
      responseCode = "404",
      description = "A mapping or a store that is not the business's")
  @APIResponse(responseCode = "413", description = "IMPORT_FILE_TOO_LARGE")
  @POST
  @Consumes({"text/csv", "text/plain", MediaType.APPLICATION_OCTET_STREAM})
  public Response dryRun(
      byte[] body,
      @QueryParam("storeId") String storeId,
      @QueryParam("mapping") String mapping,
      @QueryParam("fileName") String fileName,
      @QueryParam("priceListId") String priceListId,
      @HeaderParam("Idempotency-Key") String key) {
    if (storeId == null || storeId.isBlank() || mapping == null || mapping.isBlank()) {
      throw ApiException.badRequest("VALIDATION_FAILED", "storeId and mapping are required");
    }
    String normalisedKey = key == null || key.isBlank() ? null : Ids.parse(key).toString();
    Job job =
        svc.dryRun(
            ctx,
            Parsing.uuid(storeId, "storeId"),
            fileName,
            body,
            mapping,
            Parsing.optionalUuid(priceListId, "priceListId"),
            normalisedKey);
    return Response.status(201).entity(ApiResponse.ok(toDto(job))).build();
  }

  // ── the apply ────────────────────────────────────────────────────────────

  @Operation(
      summary = "Apply a dry run",
      description =
          "Queues the dry run's rows to be written, in chunks of 500, by a background worker: the"
              + " products first, then their VAT categories, then the prices. Needs a finished dry"
              + " run of the same mapping (less than a day old) and an Idempotency-Key; one apply"
              + " runs at a time per business. Read the job for its progress. Refused rows are not"
              + " written; the rest are.")
  @APIResponse(responseCode = "202", description = "Queued")
  @APIResponse(
      responseCode = "409",
      description = "A dry run is required, expired, or an apply is running")
  @POST
  @Path("/{id}/apply")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response apply(
      @PathParam("id") String id,
      @QueryParam("priceListId") String priceListId,
      @HeaderParam("Idempotency-Key") String key) {
    if (key == null || key.isBlank()) {
      throw ApiException.badRequest(
          "IDEMPOTENCY_KEY_REQUIRED", "an apply needs an Idempotency-Key");
    }
    Job job =
        svc.apply(
            ctx,
            Parsing.uuid(id, "id"),
            Parsing.optionalUuid(priceListId, "priceListId"),
            Ids.parse(key).toString());
    return Response.status(202).entity(ApiResponse.ok(toDto(job))).build();
  }

  // ── reads ────────────────────────────────────────────────────────────────

  @Operation(summary = "List import jobs, newest first")
  @GET
  public Response list(@QueryParam("limit") Integer limit) {
    return Response.ok(
            ApiResponse.ok(
                svc.listJobs(ctx, Cursor.clampLimit(limit)).stream()
                    .map(ImportResource::toDto)
                    .toList()))
        .build();
  }

  @Operation(summary = "Read one job and its report")
  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") String id) {
    return Response.ok(ApiResponse.ok(toDto(svc.getJob(ctx, Parsing.uuid(id, "id"))))).build();
  }

  @Operation(
      summary = "Reconcile an applied import",
      description =
          "Reads the file again and sets it against the catalogue, the price list and the store's"
              + " opening stock as they are now: rows, SKUs, barcodes, prices by VAT code, stock"
              + " quantity and value, and every SKU that did not arrive. Read-only.")
  @APIResponse(responseCode = "200", description = "The report")
  @APIResponse(responseCode = "409", description = "IMPORT_NOT_AN_APPLY")
  @GET
  @Path("/{id}/reconciliation")
  public Response reconciliation(@PathParam("id") String id) {
    var done = reconciler.reconcile(ctx, Parsing.uuid(id, "id"));
    var r = done.report();
    return Response.ok(
            ApiResponse.ok(
                new com.storeql.product.dto.ImportDtos.ImportReconciliationResponse(
                    done.job().id().toString(),
                    done.job().status(),
                    r.reconciled(),
                    r.measures().stream()
                        .map(
                            m ->
                                new com.storeql.product.dto.ImportDtos.MeasureResponse(
                                    m.name(), m.file(), m.loaded(), m.match()))
                        .toList(),
                    r.prices().stream()
                        .map(
                            p ->
                                new com.storeql.product.dto.ImportDtos.PriceByVatResponse(
                                    p.vatCode(),
                                    p.fileCount(),
                                    p.fileSum().toPlainString(),
                                    p.loadedCount(),
                                    p.loadedSum().toPlainString(),
                                    p.match()))
                        .toList(),
                    r.unmatchedSkus(),
                    r.unmatchedCount(),
                    r.priceMismatches(),
                    r.priceMismatchCount())))
        .build();
  }

  @Operation(
      summary = "A page of a job's rows",
      description =
          "In line order; ?action= filters to CREATE, UPDATE, UNCHANGED, SKIPPED or REFUSED.")
  @GET
  @Path("/{id}/rows")
  public Response rows(
      @PathParam("id") String id,
      @QueryParam("action") String action,
      @QueryParam("after") String after,
      @QueryParam("limit") Integer limit) {
    int page = Cursor.clampLimit(limit);
    String raw = Cursor.decode(after);
    int afterLine;
    try {
      afterLine = raw == null ? 0 : Integer.parseInt(raw);
    } catch (NumberFormatException e) {
      throw new ApiException(400, "INVALID_CURSOR", "Malformed pagination cursor", List.of(), e);
    }
    List<RowResult> rows = svc.rows(ctx, Parsing.uuid(id, "id"), action, afterLine, page + 1);
    var pageRows = Cursor.page(rows, page, r -> Integer.toString(r.line()));
    return Response.ok(
            ApiResponse.ok(
                pageRows.items().stream().map(ImportResource::toDto).toList(),
                new ApiResponse.Meta(ctx.requestId(), pageRows.nextCursor())))
        .build();
  }

  // ── mapping to the wire ──────────────────────────────────────────────────

  private static java.util.Map<String, String> upper(java.util.Map<String, String> m) {
    java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
    if (m != null) m.forEach((k, v) -> out.put(k.trim().toUpperCase(java.util.Locale.ROOT), v));
    return out;
  }

  private static JsonObject json(String text) {
    try (var r =
        Json.createReader(new StringReader(text == null || text.isBlank() ? "{}" : text))) {
      return r.readObject();
    }
  }

  private static jakarta.json.JsonArray array(String text) {
    try (var r =
        Json.createReader(new StringReader(text == null || text.isBlank() ? "[]" : text))) {
      return r.readArray();
    }
  }

  static ImportMappingResponse toDto(Mapping m) {
    return new ImportMappingResponse(
        m.name(),
        m.hash(),
        m.updatedAt() == null ? null : m.updatedAt().toString(),
        json(m.json()));
  }

  static ImportJobResponse toDto(Job j) {
    return new ImportJobResponse(
        j.id().toString(),
        j.kind(),
        j.status(),
        j.storeId().toString(),
        j.fileId().toString(),
        j.mappingHash(),
        j.fileSha256(),
        j.priceListId() == null ? null : j.priceListId().toString(),
        j.phase(),
        json(j.counts()),
        j.failureCode(),
        j.failureDetail(),
        j.createdAt().toString(),
        j.finishedAt() == null ? null : j.finishedAt().toString());
  }

  static ImportRowResponse toDto(RowResult r) {
    return new ImportRowResponse(
        r.line(), r.sku(), r.action(), array(r.refusals()), array(r.gaps()), array(r.changes()));
  }
}
