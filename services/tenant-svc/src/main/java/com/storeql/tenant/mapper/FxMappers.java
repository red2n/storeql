package com.storeql.tenant.mapper;

import com.storeql.tenant.domain.Domain.FxRate;
import com.storeql.tenant.dto.FxDtos.RateResponse;
import com.storeql.tenant.dto.FxDtos.SheetResponse;
import com.storeql.tenant.service.FxRateService.Sheet;
import java.util.List;

/** Exchange rates to the wire (03.x). */
public final class FxMappers {
  private FxMappers() {}

  public static RateResponse toRate(FxRate r) {
    return new RateResponse(
        r.currency(),
        r.rate(),
        r.effectiveFrom().toString(),
        r.reason(),
        r.setBy() == null ? null : r.setBy().toString(),
        r.setAt().toString());
  }

  public static SheetResponse toSheet(Sheet s) {
    return new SheetResponse(s.home(), s.rates().stream().map(FxMappers::toRate).toList());
  }

  public static List<RateResponse> toRates(List<FxRate> rates) {
    return rates.stream().map(FxMappers::toRate).toList();
  }
}
