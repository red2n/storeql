package com.storeql.tenant.mapper;

import com.storeql.tenant.domain.Meters;
import com.storeql.tenant.domain.Plans;
import com.storeql.tenant.domain.Plans.Grant;
import com.storeql.tenant.domain.Plans.PlanFile;
import com.storeql.tenant.domain.Plans.TenantPlan;
import com.storeql.tenant.domain.Plans.Usage;
import com.storeql.tenant.dto.PlanDtos;
import java.util.Map;
import java.util.stream.Collectors;

/** Plans as the wire carries them (21.8). */
public final class PlanMappers {

  private PlanMappers() {}

  private static final Map<String, String> LABELS =
      Plans.CATALOGUE.stream()
          .collect(Collectors.toUnmodifiableMap(Plans.Entitlement::key, Plans.Entitlement::label));

  public static PlanDtos.PlanResponse toDto(PlanFile f) {
    Plans.Plan p = f.plan();
    return new PlanDtos.PlanResponse(
        p.id().toString(),
        p.code(),
        p.name(),
        p.description(),
        p.status(),
        p.billingInterval(),
        p.trialDays(),
        p.isDefault(),
        p.isPublic(),
        p.sortOrder(),
        f.prices().stream()
            .map(
                price ->
                    new PlanDtos.PriceResponse(
                        price.currency(), price.amount(), price.effectiveFrom().toString()))
            .toList(),
        f.grants().stream().map(PlanMappers::toDto).toList(),
        f.meters().stream().map(PlanMappers::toDto).toList(),
        f.meterPrices().stream()
            .map(
                mp ->
                    new PlanDtos.MeterPriceResponse(
                        mp.meter(), mp.currency(), mp.unitAmount(), mp.effectiveFrom().toString()))
            .toList());
  }

  private static PlanDtos.PlanMeterResponse toDto(Meters.PlanMeter m) {
    Meters.Meter meter = Meters.meter(m.meter()).orElse(null);
    return new PlanDtos.PlanMeterResponse(
        m.meter(),
        meter == null ? m.meter() : meter.label(),
        meter == null ? null : meter.unit(),
        m.included(),
        m.hard());
  }

  private static PlanDtos.GrantResponse toDto(Grant g) {
    return new PlanDtos.GrantResponse(
        g.key(), LABELS.getOrDefault(g.key(), g.key()), g.limitValue(), g.enabled());
  }

  public static PlanDtos.TenantPlanResponse toDto(TenantPlan t) {
    return new PlanDtos.TenantPlanResponse(
        t.plan() == null ? null : toDto(t.plan()),
        t.usage().stream().map(PlanMappers::toDto).toList(),
        t.note());
  }

  /** Just the allowances of the plan a business is on; empty when it is on none. */
  public static java.util.List<PlanDtos.GrantResponse> grantsOf(TenantPlan t) {
    return t.plan() == null
        ? java.util.List.of()
        : t.plan().grants().stream()
            .filter(g -> Plans.isEnforced(g.key()))
            .map(PlanMappers::toDto)
            .toList();
  }

  private static PlanDtos.UsageResponse toDto(Usage u) {
    return new PlanDtos.UsageResponse(u.key(), u.label(), u.limitValue(), u.used(), u.over());
  }
}
