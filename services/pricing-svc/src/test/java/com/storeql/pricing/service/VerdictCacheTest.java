package com.storeql.pricing.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class VerdictCacheTest {

  @Test
  void theVerdictCacheStaysWithinItsBound() {
    AppliedPriceService svc = new AppliedPriceService();
    svc.verdictCacheMax = 3;
    for (int i = 0; i < 10; i++) {
      svc.remember(Ids.newId(), new AppliedPriceService.Verdict(Instant.now(), null, true));
      assertThat(svc.verdictCount() <= 3, is(true));
    }
  }
}
