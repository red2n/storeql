package com.storeql.gateway.filters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.storeql.discovery.ServiceRegistry;
import com.storeql.ids.Ids;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The plan's rate as the gateway reads it (21.11): from the grants tenant-svc answers with, the one
 * key this filter is about, absent when the plan names none; cached for a minute per business and
 * never past the cap; and unlimited when tenant-svc cannot be found, because a limit fails open.
 */
@ExtendWith(MockitoExtension.class)
class TenantAllowancesTest {

  @Mock ServiceRegistry registry;
  private TenantAllowances allowances;

  @BeforeEach
  void setUp() {
    allowances = new TenantAllowances();
    allowances.registry = registry;
    // Unused by the parsing test, so lenient: the strict extension would call it a mistake.
    lenient().when(registry.resolve(any())).thenReturn(Optional.empty());
  }

  @Test
  void theRateIsReadFromTheGrantsAndOnlyThatKey() {
    assertEquals(
        OptionalLong.of(120),
        TenantAllowances.parse(
            "{\"data\":{\"grants\":[{\"key\":\"stores.max\",\"limitValue\":2},"
                + "{\"key\":\"requests.per-minute\",\"limitValue\":120},"
                + "{\"key\":\"feature.storefront\",\"enabled\":true}]}}"));
    assertEquals(
        OptionalLong.empty(),
        TenantAllowances.parse(
            "{\"data\":{\"grants\":[{\"key\":\"stores.max\",\"limitValue\":2}]}}"));
    assertEquals(OptionalLong.empty(), TenantAllowances.parse("{\"data\":{\"grants\":[]}}"));
    assertEquals(OptionalLong.empty(), TenantAllowances.parse("{\"data\":null}"));
    assertEquals(OptionalLong.empty(), TenantAllowances.parse("not json"));
  }

  @Test
  void unresolvableTenantServiceMeansUnlimitedAndTheAnswerIsCachedForTheMinute() {
    String id = Ids.newId().toString();
    assertTrue(allowances.requestsPerMinute(id).isEmpty());
    allowances.requestsPerMinute(id);
    allowances.requestsPerMinute(id);
    verify(registry, times(1)).resolve("tenant-svc");
  }

  @Test
  void theCacheNeverGrowsPastTheCapUnderTenantIdChurn() {
    String[] ids = new String[TenantAllowances.MAX_ENTRIES + 5];
    for (int i = 0; i < ids.length; i++) {
      ids[i] = Ids.newId().toString();
      allowances.requestsPerMinute(ids[i]);
    }
    verify(registry, times(ids.length)).resolve("tenant-svc");
    for (String id : ids) allowances.requestsPerMinute(id);
    verify(registry, atLeast(ids.length + 1)).resolve("tenant-svc");
  }
}
