package com.storeql.tenant.api;

import com.storeql.tenant.dto.StatutoryDtos;
import com.storeql.tenant.mapper.StatutoryMappers;
import com.storeql.tenant.service.StatutoryService;
import com.storeql.web.ApiException;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.LocalDate;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * {@code /admin/tenant/statutory-returns}: what this business owes each jurisdiction, when, and
 * what it filed.
 *
 * <p>Management's. A statutory filing is a statement to a tax authority on the business's behalf,
 * and the person who makes it needs to be the person answerable for it.
 *
 * <p>The obligations sheet at {@code /admin/tenant/obligations} says what the law <em>asks</em>;
 * this says what this business <em>owes and when</em>, and holds the evidence that it went.
 */
@Path("/admin/tenant/statutory-returns")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Statutory reporting")
public class StatutoryResource {

  @Inject StatutoryService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "The returns this business owes, period by period",
      description =
          "Each period's due date and state are worked out on every read from the return's frequency"
              + " and the offset its instrument sets — nothing is stored, because a stored deadline"
              + " goes stale the first time a rule changes. A regime's return reaches a business for"
              + " the periods its country was a member, asked of the period's own dates and not of"
              + " today. `outstanding` is the same list filtered to what needs acting on, oldest"
              + " first.")
  @GET
  public ApiResponse<StatutoryDtos.CalendarResponse> calendar(@QueryParam("asOf") String asOf) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    LocalDate on = day(asOf);
    var tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        StatutoryMappers.calendar(on, svc.calendar(tenantId, on), svc.outstanding(tenantId, on)));
  }

  @Operation(
      summary = "Everything this business has filed",
      description =
          "Superseded filings included: a correction replaces one and both stay on the record, for the"
              + " same reason an invoice is never edited.")
  @GET
  @Path("/filings")
  public ApiResponse<List<StatutoryDtos.StatutoryFilingResponse>> filings() {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(StatutoryMappers.filings(svc.filings(ctx.requireTenantId())));
  }

  @Operation(
      summary = "Records that a return was filed",
      description =
          "The platform does not file on a business's behalf — a statutory return leaves only when a"
              + " person says so, and nothing here is authorised to speak to a tax authority"
              + " unprompted. This records that it went: the authority's receipt where there is one,"
              + " and the digest of what was sent so the filing can be proved against an export"
              + " produced later. Filing a period twice needs `supersedes`, which names the filing"
              + " being corrected.")
  @POST
  @Path("/{code}/filings")
  public ApiResponse<StatutoryDtos.ObligationResponse> file(
      @PathParam("code") String code, StatutoryDtos.FileRequest req) {
    BusinessWide.require(ctx);
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    return ApiResponse.ok(
        StatutoryMappers.toDto(
            svc.file(
                ctx.requireTenantId(),
                code,
                day(req.periodStart()),
                req.reference(),
                req.provider(),
                req.payloadDigest(),
                req.note(),
                req.supersedes(),
                ctx.requireUserId(),
                LocalDate.now())));
  }

  /**
   * A day from the query, or today.
   *
   * @throws ApiException 400 {@code STATUTORY_DATE_INVALID}
   */
  private static LocalDate day(String value) {
    if (value == null || value.isBlank()) return LocalDate.now();
    try {
      return LocalDate.parse(value.strip());
    } catch (java.time.format.DateTimeParseException e) {
      throw new ApiException(
          400, "STATUTORY_DATE_INVALID", "A date is written as 2026-09-18", List.of(), e);
    }
  }
}
