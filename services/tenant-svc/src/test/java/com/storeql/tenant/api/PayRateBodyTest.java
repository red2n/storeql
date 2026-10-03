package com.storeql.tenant.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.tenant.domain.Workforce.PayRate;
import com.storeql.tenant.dto.WorkforceDtos.AddPayRateRequest;
import com.storeql.tenant.service.WorkforceService;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import com.storeql.web.V7JsonbProvider;
import jakarta.json.bind.Jsonb;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code POST /admin/workforce/pay-rates} takes its {@code hourlyRate} as text (2 Oct 2026). The
 * shared guard in common-web judges numbers a body carries, never text, so a rate sent as {@code
 * "1E+2147483647"} — thirteen characters, well inside the field's twenty — was built into a figure
 * whose whole digits wrapped the column check round and broke the insert as a 500. A rate is now
 * read only written out (digits, one point, a sign): an exponent, a word, or a figure past the
 * column is {@code 400 WORKFORCE_RATE_INVALID}, and nothing reaches the service.
 *
 * <p>The real {@link TenantContext} decides; the service is a stub that records what reached it.
 */
class PayRateBodyTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID PERSON = Ids.newId();
  private static final Jsonb JSONB = new V7JsonbProvider().getContext(Object.class);

  /** What reached the service. */
  private static final class Recording extends WorkforceService {
    final List<BigDecimal> rates = new ArrayList<>();

    @Override
    public PayRate addRate(
        UUID tenantId,
        UUID userId,
        LocalDate from,
        BigDecimal hourlyRate,
        String currency,
        String note,
        UUID actorId) {
      rates.add(hourlyRate);
      return new PayRate(
          Ids.newId(),
          tenantId,
          userId,
          LocalDate.now(),
          hourlyRate,
          "GBP",
          note,
          Instant.now(),
          actorId);
    }
  }

  private static TenantContext owner() {
    TenantContext ctx = new TenantContext();
    try {
      Method set =
          TenantContext.class.getDeclaredMethod(
              "set", UUID.class, UUID.class, Set.class, Set.class, String.class);
      set.setAccessible(true);
      set.invoke(ctx, TENANT, Ids.newId(), Set.of("OWNER"), Set.of(), "req");
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("could not populate a TenantContext", e);
    }
    return ctx;
  }

  private static WorkforceResource resource(Recording svc) {
    WorkforceResource r = new WorkforceResource();
    r.ctx = owner();
    r.svc = svc;
    r.maxWindowDays = 62;
    return r;
  }

  private static AddPayRateRequest rate(String hourlyRate) {
    return new AddPayRateRequest(PERSON.toString(), null, hourlyRate, null, null);
  }

  @Test
  @DisplayName(
      "A rate written with an exponent, or as anything but a figure, is refused by name and never"
          + " reaches the service")
  void aRateIsReadWrittenOutOrRefused() {
    Recording svc = new Recording();
    WorkforceResource r = resource(svc);
    for (String text :
        List.of(
            "1E+999999999",
            "1E+2147483647",
            "0E+2147483647",
            "1E-2147483647",
            "1e3",
            "Infinity",
            "NaN",
            "twelve",
            "12,50",
            "0x10")) {
      ApiException refused = assertThrows(ApiException.class, () -> r.addRate(rate(text)), text);
      assertEquals(400, refused.status(), text);
      assertEquals("WORKFORCE_RATE_INVALID", refused.code(), text);
    }
    assertEquals(List.of(), svc.rates, "nothing reached the service");

    assertEquals(201, r.addRate(rate("12.50")).getStatus());
    assertEquals(201, r.addRate(rate(" 10.4167 ")).getStatus());
    assertEquals(List.of(new BigDecimal("12.50"), new BigDecimal("10.4167")), svc.rates);
  }

  @Test
  @DisplayName(
      "A rate sent as a JSON number reaches the same reading as one sent as text: an exponent is"
          + " refused either way")
  void aRateSentAsANumberIsReadTheSameWay() {
    Recording svc = new Recording();
    WorkforceResource r = resource(svc);
    for (String number : List.of("1E+999999999", "1E+2147483647", "1e3")) {
      String body = "{\"userId\":\"" + PERSON + "\",\"hourlyRate\":" + number + "}";
      ApiException refused =
          assertThrows(
              ApiException.class,
              () -> r.addRate(JSONB.fromJson(body, AddPayRateRequest.class)),
              number);
      assertEquals(400, refused.status(), number);
      assertEquals("WORKFORCE_RATE_INVALID", refused.code(), number);
    }
    assertEquals(List.of(), svc.rates, "nothing reached the service");
  }

  @Test
  @DisplayName(
      "The field's own bounds are asked first: past twenty characters is VALIDATION_FAILED")
  void theFieldsOwnBoundsComeFirst() {
    Recording svc = new Recording();
    ApiException refused =
        assertThrows(ApiException.class, () -> resource(svc).addRate(rate("1" + "0".repeat(25))));
    assertEquals("VALIDATION_FAILED", refused.code());
    assertEquals(List.of(), svc.rates);
  }
}
