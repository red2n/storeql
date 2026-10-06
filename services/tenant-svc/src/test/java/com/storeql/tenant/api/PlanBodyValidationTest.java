package com.storeql.tenant.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.tenant.domain.Plans.PlanFile;
import com.storeql.tenant.dto.PlanDtos.GrantRequest;
import com.storeql.tenant.dto.PlanDtos.GrantsRequest;
import com.storeql.tenant.dto.PlanDtos.PlanMeterRequest;
import com.storeql.tenant.dto.PlanDtos.PlanMetersRequest;
import com.storeql.tenant.service.PlanService;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import com.storeql.web.V7JsonbProvider;
import jakarta.json.bind.Jsonb;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * What a plan includes, and of each meter, is checked grant by grant and meter by meter (2 Oct
 * 2026). Each list held its records without {@code @Valid}, so {@code Validations.validate} never
 * looked inside one: a grant's {@code limitValue} of -1 passed its {@code @Min(0)} and broke {@code
 * chk_plan_entitlements_limit} as a 500, and a grant or meter with no key reached the service. Now
 * each is the platform's {@code 400 VALIDATION_FAILED} naming the field, the platform administrator
 * is asked first, and nothing reaches the service. One rule is left to the service on purpose: a
 * meter's {@code included} below nothing is {@code 400 PLAN_METER_INCLUDED_INVALID}, the code the
 * route has always published ({@code PlanMeterRulesTest}).
 *
 * <p>The real {@link TenantContext} decides; the service is a stub that records what reached it.
 */
class PlanBodyValidationTest {

  private static TenantContext caller(UUID tenant, String role) {
    TenantContext ctx = new TenantContext();
    try {
      Method set =
          TenantContext.class.getDeclaredMethod(
              "set", UUID.class, UUID.class, Set.class, Set.class, String.class);
      set.setAccessible(true);
      set.invoke(ctx, tenant, Ids.newId(), Set.of(role), Set.of(), "req");
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("could not populate a TenantContext", e);
    }
    return ctx;
  }

  private static ApiException refused(Executable act, int status, String code) {
    ApiException e = assertThrows(ApiException.class, act);
    assertEquals(status, e.status(), e.getMessage() + " " + e.details());
    assertEquals(code, e.code(), e.getMessage() + " " + e.details());
    return e;
  }

  private static void invalid(Executable act, String... details) {
    assertEquals(
        Arrays.asList(details), refused(act, 400, "VALIDATION_FAILED").details(), "details");
  }

  /** Where the stub stops once a call has reached it: the test is about what gets that far. */
  private static final class Reached extends RuntimeException {
    private static final long serialVersionUID = 1L;

    Reached(String what) {
      super(what);
    }
  }

  private static final class Plans extends PlanService {
    final List<String> acted = new ArrayList<>();

    @Override
    public PlanFile setGrants(UUID id, List<GrantRequest> wanted) {
      wanted.forEach(g -> acted.add("grant " + g.key() + "=" + g.limitValue()));
      throw new Reached("grants");
    }

    @Override
    public PlanFile setMeters(UUID id, List<PlanMeterRequest> wanted) {
      wanted.forEach(m -> acted.add("meter " + m.meter() + "=" + m.included()));
      throw new Reached("meters");
    }
  }

  private static PlanResource resource(Plans plans, TenantContext ctx) {
    PlanResource r = new PlanResource();
    r.svc = plans;
    r.ctx = ctx;
    return r;
  }

  private static GrantsRequest grants(GrantRequest... grants) {
    return new GrantsRequest(List.of(grants));
  }

  private static PlanMetersRequest meters(PlanMeterRequest... meters) {
    return new PlanMetersRequest(List.of(meters));
  }

  @Test
  @DisplayName(
      "A grant's limit below nothing was a 500 from chk_plan_entitlements_limit; each grant and"
          + " each meter is now checked, and only a right one reaches the service")
  void eachGrantAndEachMeterIsChecked() {
    Plans plans = new Plans();
    PlanResource r = resource(plans, caller(null, "PLATFORM_ADMIN"));
    UUID plan = Ids.newId();

    invalid(
        () -> r.setGrants(plan, grants(new GrantRequest("stores.max", -1L, null))),
        "limitValue: must be greater than or equal to 0");
    invalid(
        () -> r.setGrants(plan, grants(new GrantRequest("stores.max", Long.MIN_VALUE, null))),
        "limitValue: must be greater than or equal to 0");
    invalid(
        () ->
            r.setGrants(
                plan,
                grants(
                    new GrantRequest("stores.max", 3L, null), new GrantRequest(" ", null, true))),
        "key: must not be blank");
    invalid(
        () -> r.setGrants(plan, grants(new GrantRequest("k".repeat(61), null, true))),
        "key: size must be between 0 and 60");
    invalid(
        () -> r.setMeters(plan, meters(new PlanMeterRequest(null, 5L, false))),
        "meter: must not be blank");
    invalid(
        () -> r.setMeters(plan, meters(new PlanMeterRequest("M".repeat(21), 5L, false))),
        "meter: size must be between 0 and 20");
    assertEquals(List.of(), plans.acted, "nothing reached the service");

    assertThrows(
        Reached.class,
        () ->
            r.setGrants(
                plan,
                grants(
                    new GrantRequest("stores.max", 0L, null),
                    new GrantRequest("feature.storefront", null, true))));
    assertThrows(
        Reached.class, () -> r.setMeters(plan, meters(new PlanMeterRequest("ORDERS", 0L, true))));
    assertEquals(
        List.of("grant stores.max=0", "grant feature.storefront=null", "meter ORDERS=0"),
        plans.acted);
  }

  @Test
  @DisplayName(
      "A meter's included below nothing keeps its published PLAN_METER_INCLUDED_INVALID: the"
          + " request lets it through, as sent, for the service to name")
  void aNegativeAllowanceIsTheServicesToName() {
    Plans plans = new Plans();
    PlanResource r = resource(plans, caller(null, "PLATFORM_ADMIN"));
    UUID plan = Ids.newId();

    assertThrows(
        Reached.class, () -> r.setMeters(plan, meters(new PlanMeterRequest("SMS", -1L, false))));
    assertThrows(
        Reached.class,
        () -> r.setMeters(plan, meters(new PlanMeterRequest("SMS", Long.MIN_VALUE, false))));
    assertThrows(
        Reached.class,
        () ->
            r.setMeters(
                plan,
                JSONB.fromJson(
                    "{\"meters\":[{\"meter\":\"SMS\",\"included\":-1}]}",
                    PlanMetersRequest.class)));
    assertEquals(
        List.of("meter SMS=-1", "meter SMS=" + Long.MIN_VALUE, "meter SMS=-1"),
        plans.acted,
        "each reached the service as it was sent");
    // The request still names a meter with no key, and a hole, before the service is asked.
    invalid(
        () -> r.setMeters(plan, meters(new PlanMeterRequest(" ", -1L, false))),
        "meter: must not be blank");
  }

  private static final Jsonb JSONB = new V7JsonbProvider().getContext(Object.class);

  @Test
  @DisplayName(
      "A body that leaves the list out is refused, not read as an empty list: a PUT of {} emptied"
          + " a plan's includes or meters; an explicit [] is still a deliberate empty")
  void aMissingListIsRefusedAndAnEmptyOneIsMeant() {
    Plans plans = new Plans();
    PlanResource r = resource(plans, caller(null, "PLATFORM_ADMIN"));
    UUID plan = Ids.newId();

    invalid(
        () -> r.setGrants(plan, JSONB.fromJson("{}", GrantsRequest.class)),
        "grants: must not be null");
    invalid(
        () -> r.setGrants(plan, JSONB.fromJson("{\"grants\":null}", GrantsRequest.class)),
        "grants: must not be null");
    invalid(
        () -> r.setMeters(plan, JSONB.fromJson("{}", PlanMetersRequest.class)),
        "meters: must not be null");
    invalid(
        () -> r.setMeters(plan, JSONB.fromJson("{\"meters\":null}", PlanMetersRequest.class)),
        "meters: must not be null");
    invalid(() -> r.setGrants(plan, new GrantsRequest(null)), "grants: must not be null");
    invalid(() -> r.setMeters(plan, new PlanMetersRequest(null)), "meters: must not be null");
    // A hole is named by the shared walk, not thrown while the body binds.
    invalid(
        () -> r.setGrants(plan, JSONB.fromJson("{\"grants\":[null]}", GrantsRequest.class)),
        "grants[0]: must not be null");
    invalid(
        () -> r.setMeters(plan, JSONB.fromJson("{\"meters\":[null]}", PlanMetersRequest.class)),
        "meters[0]: must not be null");
    assertEquals(List.of(), plans.acted, "nothing reached the service");

    List<String> reached = new ArrayList<>();
    reached.add(
        assertThrows(
                Reached.class,
                () -> r.setGrants(plan, JSONB.fromJson("{\"grants\":[]}", GrantsRequest.class)))
            .getMessage());
    reached.add(
        assertThrows(
                Reached.class,
                () -> r.setMeters(plan, JSONB.fromJson("{\"meters\":[]}", PlanMetersRequest.class)))
            .getMessage());
    assertEquals(List.of("grants", "meters"), reached, "an explicit [] is a deliberate empty");
  }

  @Test
  @DisplayName("A list that was sent cannot be changed under the service afterwards")
  void aSentListIsKeptAsSent() {
    List<GrantRequest> sent = new ArrayList<>(List.of(new GrantRequest("stores.max", 3L, null)));
    GrantsRequest req = new GrantsRequest(sent);
    sent.clear();
    assertEquals(1, req.grants().size());
    assertThrows(UnsupportedOperationException.class, () -> req.grants().clear());
    List<PlanMeterRequest> meters =
        new ArrayList<>(List.of(new PlanMeterRequest("SMS", 1L, false)));
    PlanMetersRequest m = new PlanMetersRequest(meters);
    meters.clear();
    assertEquals(1, m.meters().size());
    assertThrows(UnsupportedOperationException.class, () -> m.meters().clear());
  }

  @Test
  @DisplayName("Only the platform administrator is asked what they sent")
  void theRoleIsAskedBeforeTheBody() {
    Plans plans = new Plans();
    PlanResource r = resource(plans, caller(Ids.newId(), "OWNER"));
    UUID plan = Ids.newId();
    refused(
        () -> r.setGrants(plan, grants(new GrantRequest("stores.max", -1L, null))),
        403,
        "FORBIDDEN");
    refused(
        () -> r.setMeters(plan, meters(new PlanMeterRequest(null, -1L, false))), 403, "FORBIDDEN");
    assertEquals(List.of(), plans.acted);
  }
}
