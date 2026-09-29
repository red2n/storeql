package com.storeql.order.api;

import com.storeql.order.dto.Dtos.SalesByHourRowResponse;
import com.storeql.order.dto.Dtos.SalesByStaffRowResponse;
import com.storeql.order.mapper.Mappers;
import com.storeql.order.service.OrderService;
import com.storeql.web.ApiResponse;
import com.storeql.web.Parsing;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Sales by hour of day and by member of staff — two of the four reports the readiness review still
 * listed as missing.
 *
 * <p>Both live in order-svc because the data does: reporting-svc's {@code sales_facts} carries one
 * row per order with no cashier on it, and the POS transaction journal that knows who served whom
 * is this service's table.
 *
 * <p>Under {@code /admin/} so the authorisation filter gates them by path — takings per cashier is
 * management information, and the by-path form is what stops a method added here later from
 * shipping open (SJ-D10, SJ-D11).
 */
@RequestScoped
@Path("/admin/reports")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Reports")
public class SalesAnalyticsResource {

  private static final int MAX_LIMIT = 100;
  private static final int DEFAULT_LIMIT = 50;

  @Inject OrderService svc;
  @Inject TenantContext ctx;

  /**
   * Revenue bucketed by hour, so a manager can see when the shop is actually busy.
   *
   * <p>Everything is stored in UTC, so {@code tz} matters: without it a London peak lands an hour
   * out in summer and an Indian one half an hour out all year. Only CONFIRMED and FULFILLED orders
   * count, and hours with no trade are absent rather than zero.
   *
   * @param storeId a store that is named is checked against the caller's own (SJ-D74's {@code
   *     reportStores}) and refused with {@code 403 STORE_ACCESS_DENIED} otherwise; with none named,
   *     a caller held to no store reads the whole business and a caller held to some reads exactly
   *     those, added together
   * @param channel restrict to {@code ONLINE} or {@code POS}, or {@code null} for both
   * @param from inclusive start as a full ISO-8601 instant, not a bare date
   * @param to exclusive end as a full ISO-8601 instant
   * @param tz IANA zone name whose clock the hours are counted on
   * @return one row per hour that traded, earliest first
   * @throws com.storeql.web.ApiException {@code 400} for an unknown tz or channel, an unparseable
   *     timestamp, or {@code from} not before {@code to}; {@code 403} when the caller is not OWNER
   *     or MANAGER, or names a store they are not assigned to
   */
  @Operation(
      summary = "Takings by hour of the trading day",
      description =
          "Revenue orders bucketed by hour, so a manager can see when the shop is actually busy and"
              + " staff it accordingly. Pass tz as an IANA zone name (Europe/London, Asia/Kolkata)"
              + " to count hours on that clock — everything is stored in UTC, and without tz a"
              + " London peak lands an hour out in summer and an Indian one half an hour out all"
              + " year. Filter by channel to compare the till against the website. Only CONFIRMED"
              + " and FULFILLED orders count; hours with no trade are absent rather than zero."
              + " from and to take a full ISO-8601 instant, not a bare date.")
  @APIResponse(responseCode = "200", description = "One row per hour that traded, earliest first")
  @APIResponse(
      responseCode = "400",
      description = "Unknown tz or channel, unparseable timestamp, or from is not before to")
  @APIResponse(responseCode = "403", description = "Caller is not OWNER or MANAGER")
  @GET
  @Path("/sales-by-hour")
  public Response salesByHour(
      @QueryParam("storeId") String storeId,
      @QueryParam("channel") String channel,
      @QueryParam("from") String from,
      @QueryParam("to") String to,
      @QueryParam("tz") String tz) {
    UUID requestedStore = Parsing.optionalUuid(storeId, "storeId");
    Set<UUID> stores = ctx.reportStores(requestedStore);
    List<SalesByHourRowResponse> rows =
        svc
            .salesByHour(
                ctx.requireTenantId(),
                stores,
                channel,
                Parsing.optionalInstant(from, "from"),
                Parsing.optionalInstant(to, "to"),
                tz)
            .stream()
            .map(Mappers::toDto)
            .toList();
    return Response.ok(ApiResponse.ok(rows, ApiResponse.Meta.of(ctx.requestId()))).build();
  }

  /**
   * What each cashier rang up, what they discounted, and their average basket.
   *
   * <p>Read from the POS transaction journal, so this is in-store only — an online order has no
   * cashier, and these totals will therefore not reconcile against the sales summary. {@code
   * UNATTRIBUTED} buckets journal entries naming nobody rather than dropping them.
   *
   * @param storeId a store that is named is checked against the caller's own (SJ-D74's {@code
   *     reportStores}) and refused with {@code 403 STORE_ACCESS_DENIED} otherwise; with none named,
   *     a caller held to no store reads the whole business and a caller held to some reads exactly
   *     those, added together
   * @param from inclusive start as a full ISO-8601 instant
   * @param to exclusive end as a full ISO-8601 instant
   * @param limit maximum rows; clamped to the resource's own bounds
   * @return one row per cashier, biggest taker first
   * @throws com.storeql.web.ApiException {@code 400} for an unparseable timestamp or storeId, or
   *     {@code from} not before {@code to}; {@code 403} when the caller is not OWNER or MANAGER, or
   *     names a store they are not assigned to
   */
  @Operation(
      summary = "Takings by member of staff",
      description =
          "What each cashier rang up, what they discounted, and their average basket, biggest taker"
              + " first. Read from the POS transaction journal, so this is in-store only — an"
              + " online order has no cashier, and these totals will not add up to the sales"
              + " summary for that reason. UNATTRIBUTED buckets journal entries naming nobody"
              + " rather than dropping them.")
  @APIResponse(responseCode = "200", description = "One row per cashier, biggest taker first")
  @APIResponse(
      responseCode = "400",
      description = "Unparseable timestamp or storeId, or from is not before to")
  @APIResponse(responseCode = "403", description = "Caller is not OWNER or MANAGER")
  @GET
  @Path("/sales-by-staff")
  public Response salesByStaff(
      @QueryParam("storeId") String storeId,
      @QueryParam("from") String from,
      @QueryParam("to") String to,
      @QueryParam("limit") Integer limit) {
    int clamped = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
    UUID requestedStore = Parsing.optionalUuid(storeId, "storeId");
    Set<UUID> stores = ctx.reportStores(requestedStore);
    List<SalesByStaffRowResponse> rows =
        svc
            .salesByStaff(
                ctx.requireTenantId(),
                stores,
                Parsing.optionalInstant(from, "from"),
                Parsing.optionalInstant(to, "to"),
                clamped)
            .stream()
            .map(Mappers::toDto)
            .toList();
    return Response.ok(ApiResponse.ok(rows, ApiResponse.Meta.of(ctx.requestId()))).build();
  }
}
