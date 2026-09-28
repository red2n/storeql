package com.storeql.tenant.api;

import com.storeql.tenant.dto.FxDtos.RateResponse;
import com.storeql.tenant.dto.FxDtos.SetRateRequest;
import com.storeql.tenant.dto.FxDtos.SheetResponse;
import com.storeql.tenant.mapper.FxMappers;
import com.storeql.tenant.service.FxRateService;
import com.storeql.web.ApiException;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The business's exchange rates (03.x). The sheet is a staff-readable leaf — pricing-svc and
 * purchase-svc read it under a staff identity — while setting a rate and reading its history are
 * management's, by the default rule for {@code /admin}.
 */
@Path("/admin/tenant/fx-rates")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
@Tag(name = "Exchange rates")
public class FxRateResource {

  @Inject FxRateService service;
  @Inject TenantContext ctx;

  /**
   * The home currency and the rate in force today for every other currency the business keeps.
   *
   * @return the sheet
   */
  @Operation(
      summary = "The business's exchange rates",
      description =
          "The home currency and, per other currency kept, the home units one unit of it buys"
              + " today. Staff-readable: the services that show a price or measure a spend in another"
              + " currency read it. A business that keeps no rates has only its home currency.")
  @APIResponse(responseCode = "200", description = "The sheet")
  @GET
  public ApiResponse<SheetResponse> sheet() {
    return ApiResponse.ok(
        FxMappers.toSheet(service.sheet(ctx.requireTenantId())),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * Sets a rate, from a day, with a reason. Append-only: the history keeps every rate ever set.
   *
   * @param currency the other currency, ISO 4217
   * @param req the rate, the day and the reason
   * @return the rate as kept
   * @throws ApiException {@code 400 FX_CURRENCY_INVALID}, {@code FX_RATE_INVALID}, {@code
   *     FX_DATE_INVALID}, {@code FX_REASON_REQUIRED}
   */
  @Operation(
      summary = "Set an exchange rate",
      description =
          "Home units per one unit of the currency, from a day (today when absent, at most a month"
              + " ahead), with a reason. A new row every time — the history is the audit trail."
              + " Management only.")
  @APIResponse(responseCode = "200", description = "The rate as kept")
  @APIResponse(
      responseCode = "400",
      description = "Refused by name: currency, rate, date or reason")
  @PUT
  @Path("/{currency}")
  public ApiResponse<RateResponse> set(@PathParam("currency") String currency, SetRateRequest req) {
    Validations.validate(req);
    LocalDate from = null;
    if (req.effectiveFrom() != null && !req.effectiveFrom().isBlank()) {
      try {
        from = LocalDate.parse(req.effectiveFrom().trim());
      } catch (DateTimeParseException e) {
        throw new ApiException(
            400, "FX_DATE_INVALID", "effectiveFrom must be a date, yyyy-MM-dd", List.of(), e);
      }
    }
    return ApiResponse.ok(
        FxMappers.toRate(
            service.set(
                ctx.requireTenantId(), currency, req.rate(), from, req.reason(), ctx.userId())),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * Every rate ever set for a currency, newest first.
   *
   * @param currency the other currency
   * @return the history
   */
  @Operation(summary = "A currency's rate history", description = "Newest first. Management only.")
  @APIResponse(responseCode = "200", description = "The history")
  @GET
  @Path("/{currency}/history")
  public ApiResponse<List<RateResponse>> history(@PathParam("currency") String currency) {
    return ApiResponse.ok(
        FxMappers.toRates(service.history(ctx.requireTenantId(), currency)),
        ApiResponse.Meta.of(ctx.requestId()));
  }
}
