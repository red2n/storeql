package com.storeql.reporting.api;

import com.storeql.ids.Ids;
import com.storeql.reporting.mapper.Mappers;
import com.storeql.reporting.service.ReportingService;
import com.storeql.web.ApiException;
import com.storeql.web.ApiResponse;
import com.storeql.web.Parsing;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * N4: sales revenue reporting, built from the OrderConfirmed / PaymentRefunded / OrderVoided
 * projection; a till sale voided after the fact is left out of every report here. {@code
 * from}/{@code to} are inclusive calendar dates (ISO {@code yyyy-MM-dd}); {@code to} covers the
 * whole day. tenant comes from the JWT.
 */
@Path("/admin/reports/sales")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Sales Reports")
public class SalesReportResource {

  @Inject ReportingService service;
  @Inject TenantContext ctx;

  /** Gross / refunded / net revenue and order count, grouped by currency. */
  @Operation(
      summary = "Sales revenue summary",
      description =
          "Gross/refunded/net revenue and order count, grouped by currency, over the given"
              + " inclusive date range. Optionally filtered by store and/or channel"
              + " (ONLINE/POS). A till sale voided after the fact (OrderVoided) is left out,"
              + " its refunds with it.")
  @APIResponse(responseCode = "200", description = "Sales summary rows, one per currency")
  @APIResponse(
      responseCode = "400",
      description = "from/to is not a valid yyyy-MM-dd date, or storeId is not a valid UUID")
  @GET
  @Path("/summary")
  public ApiResponse<Object> summary(
      @QueryParam("from") String from,
      @QueryParam("to") String to,
      @QueryParam("storeId") String storeId,
      @QueryParam("channel") String channel) {
    var rows =
        service.salesSummary(
            ctx.tenantId(), fromDay(from), toDay(to), optUuid(storeId), blankToNull(channel));
    return ApiResponse.ok(Mappers.toSalesSummaryReport(rows));
  }

  /** Daily revenue buckets (per currency), newest day first. */
  @Operation(
      summary = "Daily sales revenue buckets",
      description =
          "Daily revenue buckets (per currency), newest day first, over the given inclusive date"
              + " range. Optionally filtered by store and/or channel (ONLINE/POS). A voided till"
              + " sale is left out.")
  @APIResponse(responseCode = "200", description = "Daily sales rows")
  @APIResponse(
      responseCode = "400",
      description = "from/to is not a valid yyyy-MM-dd date, or storeId is not a valid UUID")
  @GET
  @Path("/by-day")
  public ApiResponse<Object> byDay(
      @QueryParam("from") String from,
      @QueryParam("to") String to,
      @QueryParam("storeId") String storeId,
      @QueryParam("channel") String channel) {
    var rows =
        service.salesByDay(
            ctx.tenantId(), fromDay(from), toDay(to), optUuid(storeId), blankToNull(channel));
    return ApiResponse.ok(Mappers.toSalesByDayReport(rows));
  }

  private static Instant fromDay(String s) {
    return s == null || s.isBlank()
        ? null
        : Parsing.date(s, "from").atStartOfDay(ZoneOffset.UTC).toInstant();
  }

  /**
   * {@code to} is inclusive of the whole day, so the exclusive upper bound is the next midnight.
   */
  private static Instant toDay(String s) {
    return s == null || s.isBlank()
        ? null
        : Parsing.date(s, "to").plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
  }

  private static UUID optUuid(String s) {
    return s == null || s.isBlank() ? null : Ids.parse(s);
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }

  /** Labour against sales, day by day. */
  @Operation(
      summary = "Sales by category",
      description =
          "What each category took over the inclusive date range, from the sale lines and the"
              + " catalogue's own word on where each variant sits: one row per category and"
              + " currency, largest first, each with its share of the currency's total. `level=leaf`"
              + " (default) groups by the product's own category; `level=top` rolls each up to its"
              + " top-level ancestor. A row with no `categoryId` is the lines the report cannot"
              + " place — a product with no category, or a variant the catalogue has not announced"
              + " (product-svc's re-announce fills that) — shown rather than dropped, because takings"
              + " that cannot be placed are still takings. Gross is before refunds: a refund is known"
              + " by order, not by line. The lines of a voided till sale are left out. Names are the"
              + " catalogue's; this report answers in ids.")
  @APIResponse(responseCode = "200", description = "One row per category and currency")
  @APIResponse(
      responseCode = "400",
      description =
          "from/to is not a yyyy-MM-dd date, storeId is not a UUID, or level is not leaf or top")
  @GET
  @Path("/by-category")
  public ApiResponse<Object> byCategory(
      @QueryParam("from") String from,
      @QueryParam("to") String to,
      @QueryParam("storeId") String storeId,
      @QueryParam("channel") String channel,
      @QueryParam("level") String level) {
    String chosen = level == null || level.isBlank() ? "leaf" : level.toLowerCase(Locale.ROOT);
    if (!"leaf".equals(chosen) && !"top".equals(chosen)) {
      throw ApiException.badRequest("REPORT_LEVEL_INVALID", "level must be leaf or top");
    }
    var rows =
        service.salesByCategory(
            ctx.tenantId(),
            fromDay(from),
            toDay(to),
            optUuid(storeId),
            blankToNull(channel),
            "top".equals(chosen));
    return ApiResponse.ok(Mappers.toSalesByCategoryReport(chosen, rows));
  }

  @Operation(
      summary = "What each day took, and what its hours cost",
      description =
          "The two numbers a manager puts side by side, and neither means much alone: takings without"
              + " the cost of the hours that earned them is half a story, and a labour cost without"
              + " takings is a number to worry about for no reason. Labour comes from tenant-svc's"
              + " clock, projected here — the event carries a store, a day and money and **no person**,"
              + " so pay stays in the service that keeps it. A day with takings and no hours recorded"
              + " is as real as a day with hours and no sales, and both appear. Where some of a day's"
              + " hours had no pay rate in force the cost is **null rather than zero**, and"
              + " `uncostedHours` says how much could not be costed: a Saturday shown as free labour"
              + " would be worse than one that says it does not know. A voided till sale is not"
              + " takings and is left out.")
  @APIResponse(responseCode = "200", description = "One row per day, newest first")
  @APIResponse(
      responseCode = "400",
      description = "from/to is not a yyyy-MM-dd date, or storeId is not a UUID")
  @GET
  @Path("/labour")
  public ApiResponse<Object> labour(
      @QueryParam("from") String from,
      @QueryParam("to") String to,
      @QueryParam("storeId") String storeId) {
    var rows = service.labourByDay(ctx.tenantId(), fromDay(from), toDay(to), optUuid(storeId));
    return ApiResponse.ok(Mappers.toLabourReport(rows));
  }
}
