package com.storeql.tenant.service;

import com.storeql.ids.Ids;
import com.storeql.service.Fx;
import com.storeql.tenant.domain.Domain.FxRate;
import com.storeql.tenant.domain.Domain.Tenant;
import com.storeql.tenant.repo.FxRateRepository;
import com.storeql.tenant.repo.TenantRepository;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The business's exchange rates (03.x): the home currency and, per other currency, the home units
 * one unit of it buys. Set by management with a reason, from a day; read by every service that
 * shows a price or measures a spend in another currency.
 */
@ApplicationScoped
public class FxRateService {

  /** A rate may be dated this far ahead: a Monday rate set on Friday, not a rate for next year. */
  static final int MAX_DAYS_AHEAD = 31;

  static final LocalDate EARLIEST = LocalDate.of(2000, 1, 1);

  @Inject FxRateRepository repo;
  @Inject TenantRepository tenants;

  /** The home currency and the rates in force today. */
  public record Sheet(String home, List<FxRate> rates) {}

  /**
   * @throws ApiException 404 {@code TENANT_NOT_FOUND}
   */
  public Sheet sheet(UUID tenantId) {
    Tenant tenant = requireTenant(tenantId);
    return new Sheet(tenant.currency(), repo.current(tenantId, LocalDate.now(ZoneOffset.UTC)));
  }

  /** Every rate ever set for a currency, newest first. */
  public List<FxRate> history(UUID tenantId, String currency) {
    requireTenant(tenantId);
    return repo.history(tenantId, code(currency, requireTenant(tenantId).currency()));
  }

  /**
   * Sets a rate.
   *
   * @throws ApiException 400 {@code FX_CURRENCY_INVALID} for a code ISO 4217 does not know or the
   *     home currency itself; {@code FX_RATE_INVALID} for a rate that is absent, not positive, too
   *     precise or absurd; {@code FX_DATE_INVALID} for a day before 2000 or more than a month
   *     ahead; 404 {@code TENANT_NOT_FOUND}
   */
  public FxRate set(
      UUID tenantId,
      String currency,
      BigDecimal rate,
      LocalDate effectiveFrom,
      String reason,
      UUID actor) {
    Tenant tenant = requireTenant(tenantId);
    String cur = code(currency, tenant.currency());
    String refusal = Fx.validateRate(rate);
    if (refusal != null) {
      throw ApiException.badRequest("FX_RATE_INVALID", refusal);
    }
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    LocalDate from = effectiveFrom == null ? today : effectiveFrom;
    if (from.isBefore(EARLIEST) || from.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
      throw ApiException.badRequest(
          "FX_DATE_INVALID",
          "effectiveFrom is a day from 2000 to at most " + MAX_DAYS_AHEAD + " days ahead");
    }
    if (reason == null || reason.isBlank()) {
      throw ApiException.badRequest("FX_REASON_REQUIRED", "a reason for the rate is required");
    }
    return repo.insert(
        new FxRate(
            Ids.newId(),
            tenantId,
            cur,
            rate.stripTrailingZeros().scale() < 0 ? rate.setScale(0) : rate,
            from,
            reason.trim(),
            actor,
            Instant.now()));
  }

  private static String code(String currency, String home) {
    if (!Fx.isCurrency(currency)) {
      throw ApiException.badRequest(
          "FX_CURRENCY_INVALID", "currency must be an ISO 4217 code, e.g. USD");
    }
    String cur = currency.trim().toUpperCase(Locale.ROOT);
    if (cur.equals(home)) {
      throw ApiException.badRequest(
          "FX_CURRENCY_INVALID", "the home currency " + home + " has no rate against itself");
    }
    return cur;
  }

  private Tenant requireTenant(UUID tenantId) {
    return tenants
        .findTenant(tenantId)
        .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "No such tenant"));
  }
}
