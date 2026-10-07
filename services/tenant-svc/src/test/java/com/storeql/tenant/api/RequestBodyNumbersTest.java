package com.storeql.tenant.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.tenant.dto.BillingDtos;
import com.storeql.tenant.dto.CommissionDtos;
import com.storeql.tenant.dto.Dtos;
import com.storeql.tenant.dto.Fits;
import com.storeql.tenant.dto.PlanDtos;
import com.storeql.tenant.dto.RetentionDtos;
import com.storeql.tenant.dto.StoreTaskDtos;
import com.storeql.tenant.dto.WholeNumbers;
import com.storeql.web.ApiException;
import com.storeql.web.V7JsonbProvider;
import com.storeql.web.Validations;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbException;
import jakarta.json.bind.annotation.JsonbTypeDeserializer;
import jakarta.validation.Constraint;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A number in a request body reaches the service as it was sent, or the request is refused (2 Oct
 * 2026).
 *
 * <p>JSON-B cuts a whole number down while it binds the body ({@code 4294967326} into an {@code
 * Integer} is 30), before common-web {@code Validations.validate} sees it, so every whole-number
 * field is read by {@link WholeNumbers} instead. A decimal is bound exactly, but a column of fixed
 * precision overflows past its whole digits (a 500) and rounds past its places (a figure other than
 * the one sent), so every decimal field carries the {@link Fits} of where it is kept — never Bean
 * Validation's {@code @Digits}, which counts trailing zeros as places and so refused a rate of
 * {@code 0.23000} that the column keeps unchanged. Both are checked here for every body every
 * resource of this service takes, so a new field cannot forget either; the binding itself is
 * checked through the JSON-B every service binds with. So is the cascade: a constraint on a record
 * inside a body runs only when the field holding it says {@code @Valid}, so every such field must.
 */
class RequestBodyNumbersTest {

  private static final Set<Class<? extends Annotation>> NOT_THE_BODY =
      Set.of(
          PathParam.class,
          QueryParam.class,
          HeaderParam.class,
          CookieParam.class,
          MatrixParam.class,
          FormParam.class,
          BeanParam.class,
          Context.class);

  private static final Set<Class<? extends Annotation>> VERBS =
      Set.of(GET.class, POST.class, PUT.class, PATCH.class, DELETE.class);

  private static final Jsonb JSONB = new V7JsonbProvider().getContext(Object.class);

  /**
   * Decimals held by the service under a refusal of their own, before anything is written: a
   * {@code @Fits} would answer some of them as {@code VALIDATION_FAILED} instead.
   */
  private static final Map<String, String> BOUNDED_BY_THE_SERVICE =
      Map.of(
          "dto.FxDtos$SetRateRequest.rate",
          "common-service Fx.validateRate: FX_RATE_INVALID past ten places or a trillion,"
              + " within NUMERIC(24,10)");

  // ── every body: whole numbers read exactly, decimals bounded ──────────────────

  /** Every type a resource method of this service takes as its body. */
  private static Map<String, Type> bodies() {
    Map<String, Type> out = new java.util.TreeMap<>();
    for (JavaClass c :
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.storeql.tenant.api")) {
      Class<?> resource = c.reflect();
      if (!resource.isAnnotationPresent(Path.class)) {
        continue;
      }
      for (Method m : resource.getDeclaredMethods()) {
        if (Arrays.stream(m.getAnnotations()).noneMatch(a -> VERBS.contains(a.annotationType()))) {
          continue;
        }
        for (Parameter p : m.getParameters()) {
          if (Arrays.stream(p.getAnnotations())
              .noneMatch(a -> NOT_THE_BODY.contains(a.annotationType()))) {
            out.put(resource.getSimpleName() + "." + m.getName(), p.getParameterizedType());
          }
        }
      }
    }
    return out;
  }

  private static void check(Type type, String at, Set<Type> seen, Set<String> problems) {
    if (type instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> raw) {
      if (Collection.class.isAssignableFrom(raw)) {
        check(pt.getActualTypeArguments()[0], at + "[]", seen, problems);
      } else if (Map.class.isAssignableFrom(raw)) {
        check(pt.getActualTypeArguments()[1], at + "{}", seen, problems);
      }
      return;
    }
    if (!(type instanceof Class<?> cls) || !seen.add(type)) {
      return;
    }
    if (cls.isArray()) {
      check(cls.getComponentType(), at + "[]", seen, problems);
      return;
    }
    if (!cls.isRecord() || !cls.getName().startsWith("com.storeql.")) {
      if (isWhole(cls) || cls == BigDecimal.class || isBinaryFloat(cls)) {
        problems.add(at + ": a bare " + cls.getSimpleName() + " is no body; wrap it in a record");
      }
      return;
    }
    for (RecordComponent rc : cls.getRecordComponents()) {
      String where =
          cls.getName().replaceFirst("^com\\.storeql\\.tenant\\.", "") + "." + rc.getName();
      Field field;
      try {
        field = cls.getDeclaredField(rc.getName());
      } catch (NoSuchFieldException e) {
        throw new AssertionError(where, e);
      }
      Class<?> raw = rc.getType();
      Class<?> reader =
          field.isAnnotationPresent(JsonbTypeDeserializer.class)
              ? field.getAnnotation(JsonbTypeDeserializer.class).value()
              : null;
      if (raw == int.class || raw == Integer.class) {
        expect(reader, WholeNumbers.ExactInt.class, where, problems);
      } else if (raw == long.class || raw == Long.class) {
        expect(reader, WholeNumbers.ExactLong.class, where, problems);
      } else if (isWhole(raw) || isBinaryFloat(raw) || raw == BigInteger.class) {
        problems.add(
            where
                + ": a "
                + raw.getSimpleName()
                + " is read inexactly; use int, long or BigDecimal");
      } else if (raw == BigDecimal.class) {
        if (field.isAnnotationPresent(Digits.class)) {
          problems.add(
              where + ": @Digits counts trailing zeros as places (1.0000 is four); use @Fits");
        } else if (!field.isAnnotationPresent(Fits.class)
            && !BOUNDED_BY_THE_SERVICE.containsKey(where)) {
          problems.add(where + ": a BigDecimal with no @Fits of where it is kept");
        }
      } else if (Collection.class.isAssignableFrom(raw)
          && rc.getGenericType() instanceof ParameterizedType pt) {
        Type element = pt.getActualTypeArguments()[0];
        if (element == Integer.class) {
          expect(
              reader,
              Set.class.isAssignableFrom(raw)
                  ? WholeNumbers.ExactIntSet.class
                  : WholeNumbers.ExactIntList.class,
              where,
              problems);
        } else if (element instanceof Class<?> e
            && (isWhole(e) || isBinaryFloat(e) || e == BigDecimal.class || e == BigInteger.class)) {
          problems.add(where + ": a collection of " + e.getSimpleName() + " is not bounded");
        } else {
          check(element, where + "[]", seen, problems);
        }
      } else if (Map.class.isAssignableFrom(raw)
          && rc.getGenericType() instanceof ParameterizedType pt) {
        Type value = pt.getActualTypeArguments()[1];
        if (value instanceof Class<?> v
            && (isWhole(v) || isBinaryFloat(v) || v == BigDecimal.class || v == BigInteger.class)) {
          problems.add(where + ": a map of " + v.getSimpleName() + " is not bounded");
        } else {
          check(value, where + "{}", seen, problems);
        }
      } else {
        check(rc.getGenericType(), where, seen, problems);
      }
    }
  }

  private static void expect(Class<?> reader, Class<?> wanted, String where, Set<String> problems) {
    if (reader != wanted) {
      problems.add(
          where
              + ": read by "
              + (reader == null ? "JSON-B, which cuts it" : reader.getSimpleName())
              + ", not WholeNumbers."
              + wanted.getSimpleName());
    }
  }

  private static boolean isWhole(Class<?> c) {
    return Set.<Class<?>>of(
            int.class,
            Integer.class,
            long.class,
            Long.class,
            short.class,
            Short.class,
            byte.class,
            Byte.class)
        .contains(c);
  }

  private static boolean isBinaryFloat(Class<?> c) {
    return Set.<Class<?>>of(double.class, Double.class, float.class, Float.class).contains(c);
  }

  @Test
  @DisplayName(
      "Every whole number in every request body is read exactly, and every decimal carries the"
          + " @Fits of where it is kept")
  void everyNumberInEveryBodyIsHeld() {
    Map<String, Type> bodies = bodies();
    assertTrue(bodies.size() > 50, "the scan found the resources: " + bodies.keySet());
    Set<String> problems = new TreeSet<>();
    Set<Type> seen = new HashSet<>();
    bodies.forEach((route, type) -> check(type, route, seen, problems));
    assertEquals(List.of(), new ArrayList<>(problems), String.join("\n", problems));
  }

  // ── every record inside a body is validated ──────────────────────────────────

  private static boolean ours(Type type) {
    return type instanceof Class<?> c && c.isRecord() && c.getName().startsWith("com.storeql.");
  }

  private static Field fieldOf(Class<?> record, RecordComponent rc) {
    try {
      return record.getDeclaredField(rc.getName());
    } catch (NoSuchFieldException e) {
      throw new AssertionError(record.getName() + "." + rc.getName(), e);
    }
  }

  /** Whether an annotation is one Bean Validation acts on: a constraint, or a cascade. */
  private static boolean validating(Annotation a) {
    return a instanceof Valid || a.annotationType().isAnnotationPresent(Constraint.class);
  }

  /** Whether a written type, at any depth ({@code List<@NotNull X>}), carries one. */
  private static boolean validating(AnnotatedType type) {
    if (Arrays.stream(type.getAnnotations()).anyMatch(RequestBodyNumbersTest::validating)) {
      return true;
    }
    return type instanceof AnnotatedParameterizedType apt
        && Arrays.stream(apt.getAnnotatedActualTypeArguments())
            .anyMatch(RequestBodyNumbersTest::validating);
  }

  /** Whether a record, or a record it holds, has a constraint Bean Validation would run. */
  private static boolean constrained(Type type, Set<Type> seen) {
    if (!ours(type) || !seen.add(type)) {
      return false;
    }
    Class<?> cls = (Class<?>) type;
    for (RecordComponent rc : cls.getRecordComponents()) {
      Field field = fieldOf(cls, rc);
      if (Arrays.stream(field.getAnnotations()).anyMatch(RequestBodyNumbersTest::validating)
          || validating(field.getAnnotatedType())) {
        return true;
      }
      Type held = rc.getGenericType();
      if (held instanceof ParameterizedType pt) {
        for (Type argument : pt.getActualTypeArguments()) {
          if (constrained(argument, seen)) {
            return true;
          }
        }
      } else if (constrained(held, seen)) {
        return true;
      }
    }
    return false;
  }

  private static void cascade(Type type, String at, Set<Type> seen, Set<String> problems) {
    if (type instanceof ParameterizedType pt
        && pt.getRawType() instanceof Class<?> raw
        && Collection.class.isAssignableFrom(raw)) {
      if (constrained(pt.getActualTypeArguments()[0], new HashSet<>())) {
        problems.add(at + ": a bare list body is never cascaded into; wrap it in a record");
      }
      return;
    }
    if (!ours(type) || !seen.add(type)) {
      return;
    }
    Class<?> cls = (Class<?>) type;
    for (RecordComponent rc : cls.getRecordComponents()) {
      String where =
          cls.getName().replaceFirst("^com\\.storeql\\.tenant\\.", "") + "." + rc.getName();
      Field field = fieldOf(cls, rc);
      boolean marked = field.isAnnotationPresent(Valid.class);
      Type held = rc.getGenericType();
      if (ours(held)) {
        if (!marked && constrained(held, new HashSet<>())) {
          problems.add(where + ": its constraints never run; mark it @Valid");
        }
        cascade(held, where, seen, problems);
      } else if (held instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> raw) {
        boolean isMap = Map.class.isAssignableFrom(raw);
        if (!isMap && !Collection.class.isAssignableFrom(raw)) {
          continue;
        }
        int index = isMap ? 1 : 0;
        Type element = pt.getActualTypeArguments()[index];
        boolean elementMarked =
            field.getAnnotatedType() instanceof AnnotatedParameterizedType apt
                && apt.getAnnotatedActualTypeArguments()[index].isAnnotationPresent(Valid.class);
        if (ours(element)) {
          if (!marked && !elementMarked && constrained(element, new HashSet<>())) {
            problems.add(
                where
                    + ": its elements' constraints never run; write List<@Valid "
                    + ((Class<?>) element).getSimpleName()
                    + ">");
          }
          cascade(element, where + "[]", seen, problems);
        }
      }
    }
  }

  @Test
  @DisplayName(
      "Every record inside every request body is validated: a constraint on one its field does"
          + " not mark @Valid never runs, and what it was meant to refuse reaches the table")
  void everyRecordInEveryBodyIsValidated() {
    Set<String> problems = new TreeSet<>();
    Set<Type> seen = new HashSet<>();
    bodies().forEach((route, type) -> cascade(type, route, seen, problems));
    assertEquals(List.of(), new ArrayList<>(problems), String.join("\n", problems));
  }

  // ── a required field left out stays missing ──────────────────────────────────

  @Test
  @DisplayName(
      "A required field left out of any body is still missing when it is checked: a body that"
          + " turned a missing list into an empty one let a PUT of {} empty a plan (2 Oct 2026)")
  void aRequiredFieldLeftOutStaysMissing() throws ReflectiveOperationException {
    Set<String> problems = new TreeSet<>();
    for (Map.Entry<String, Type> body : bodies().entrySet()) {
      if (!ours(body.getValue())) {
        continue;
      }
      Class<?> cls = (Class<?>) body.getValue();
      Object bound;
      try {
        bound = bind("{}", cls);
      } catch (JsonbException e) {
        continue; // refused while it binds: it never reaches a check to defeat
      }
      for (RecordComponent rc : cls.getRecordComponents()) {
        if (!fieldOf(cls, rc).isAnnotationPresent(NotNull.class)) {
          continue;
        }
        Object value = rc.getAccessor().invoke(bound);
        if (value != null) {
          problems.add(
              body.getKey()
                  + ": "
                  + rc.getName()
                  + " is @NotNull, yet a body without it binds it as "
                  + value);
        }
      }
    }
    assertEquals(List.of(), new ArrayList<>(problems), String.join("\n", problems));
  }

  // ── the binding itself, through the JSON-B every service binds with ─────────────

  private static <T> T bind(String json, Class<T> type) {
    return JSONB.fromJson(json, type);
  }

  private static void refusedAtBinding(String json, Class<?> type) {
    assertThrows(JsonbException.class, () -> bind(json, type), json);
  }

  private static final String PROFILE =
      "{\"legalName\":\"P\",\"addressLine1\":\"1 St\",\"city\":\"C\",\"country\":\"GB\","
          + "\"invoicePrefix\":\"INV\",\"paymentTermsDays\":%s}";

  @Test
  @DisplayName(
      "A billing profile's payment terms are the days sent, or the body is refused: 4294967326 is"
          + " not bound as 30, nor 1E+80000000 as 0, nor 30.9 as 30")
  void paymentTermsAreReadExactly() {
    for (String cut : List.of("4294967326", "1E+80000000", "1E-80000000", "30.9", "-2147483649")) {
      refusedAtBinding(PROFILE.formatted(cut), BillingDtos.ProfileRequest.class);
    }
    assertEquals(
        30, bind(PROFILE.formatted("30"), BillingDtos.ProfileRequest.class).paymentTermsDays());
    assertEquals(
        30, bind(PROFILE.formatted("30.0"), BillingDtos.ProfileRequest.class).paymentTermsDays());
    assertEquals(
        30, bind(PROFILE.formatted("3E1"), BillingDtos.ProfileRequest.class).paymentTermsDays());
    assertEquals(
        30, bind(PROFILE.formatted("\"30\""), BillingDtos.ProfileRequest.class).paymentTermsDays());
    refusedAtBinding(PROFILE.formatted("\"30.9\""), BillingDtos.ProfileRequest.class);
    assertEquals(
        null, bind(PROFILE.formatted("null"), BillingDtos.ProfileRequest.class).paymentTermsDays());
  }

  @Test
  @DisplayName(
      "A dunning policy's days are the days sent: 4294967310 is not bound as 14, and a reminder"
          + " day past an int is refused while a hole is kept for Validations to name")
  void dunningDaysAreReadExactly() {
    String policy = "{\"reminderDays\":%s,\"suspendAfterDays\":%s,\"uncollectibleAfterDays\":60}";
    refusedAtBinding(
        policy.formatted("[3,7]", "4294967310"), BillingDtos.DunningPolicyRequest.class);
    refusedAtBinding(policy.formatted("[3,7]", "14.5"), BillingDtos.DunningPolicyRequest.class);
    refusedAtBinding(
        policy.formatted("[3,4294967297]", "14"), BillingDtos.DunningPolicyRequest.class);
    refusedAtBinding(
        policy.formatted("[3,1E+80000000]", "14"), BillingDtos.DunningPolicyRequest.class);
    refusedAtBinding(policy.formatted("[[3]]", "14"), BillingDtos.DunningPolicyRequest.class);
    refusedAtBinding(policy.formatted("3", "14"), BillingDtos.DunningPolicyRequest.class);
    BillingDtos.DunningPolicyRequest ok =
        bind(policy.formatted("[3,null,7]", "14"), BillingDtos.DunningPolicyRequest.class);
    assertEquals(14, ok.suspendAfterDays());
    assertEquals(Arrays.asList(3, null, 7), ok.reminderDays());
  }

  @Test
  @DisplayName(
      "A plan grant's limit, a retention period and a task's weekdays are the figures sent, or the"
          + " body is refused")
  void otherWholeNumbersAreReadExactly() {
    refusedAtBinding(
        "{\"key\":\"stores.max\",\"limitValue\":9223372036854775808}", PlanDtos.GrantRequest.class);
    refusedAtBinding("{\"key\":\"stores.max\",\"limitValue\":2.5}", PlanDtos.GrantRequest.class);
    assertEquals(
        Long.MAX_VALUE,
        bind(
                "{\"key\":\"stores.max\",\"limitValue\":9223372036854775807}",
                PlanDtos.GrantRequest.class)
            .limitValue());
    refusedAtBinding("{\"periodDays\":4294967326}", RetentionDtos.SetPeriodRequest.class);
    assertEquals(
        30, bind("{\"periodDays\":30}", RetentionDtos.SetPeriodRequest.class).periodDays());
    refusedAtBinding(
        "{\"title\":\"t\",\"kind\":\"WEEKLY\",\"daysOfWeek\":[4294967297]}",
        StoreTaskDtos.TemplateRequest.class);
  }

  // ── decimals held to where they are kept ─────────────────────────────────────

  /** The details Validations gives a body bound from {@code json}, for one field only. */
  private static List<String> refusalsOf(String json, Class<?> type, String field) {
    Object body = bind(json, type);
    try {
      Validations.validate(body);
      return List.of();
    } catch (ApiException e) {
      assertEquals("VALIDATION_FAILED", e.code(), e.details().toString());
      return e.details().stream().filter(d -> d.startsWith(field + ": ")).toList();
    }
  }

  private static void held(String json, Class<?> type, String field) {
    assertFalse(refusalsOf(json, type, field).isEmpty(), json);
  }

  private static void taken(String json, Class<?> type, String field) {
    assertEquals(List.of(), refusalsOf(json, type, field), json);
  }

  @Test
  @DisplayName(
      "A store's coordinates are on the globe and no finer than the six places it keeps: 1000"
          + " overflowed NUMERIC(9,6) as a 500")
  void aStoresCoordinatesAreHeld() {
    for (Class<?> type : List.of(Dtos.CreateStoreRequest.class, Dtos.UpdateStoreRequest.class)) {
      for (String lat : List.of("1000", "90.000001", "-91", "51.5073511")) {
        held("{\"geoLat\":" + lat + "}", type, "geoLat");
      }
      for (String lng : List.of("1000", "180.5", "-180.000001", "-0.1275001")) {
        held("{\"geoLng\":" + lng + "}", type, "geoLng");
      }
      taken("{\"geoLat\":51.507351,\"geoLng\":-0.127500}", type, "geoLat");
      taken("{\"geoLat\":-90,\"geoLng\":180}", type, "geoLng");
    }
  }

  @Test
  @DisplayName(
      "A weighing instrument's capacity and interval are above nothing, as its table requires,"
          + " and within NUMERIC(18,4)")
  void aWeighingInstrumentsMarkingsAreHeld() {
    for (Class<?> type :
        List.of(
            Dtos.CreateWeighingInstrumentRequest.class,
            Dtos.UpdateWeighingInstrumentRequest.class)) {
      for (String bad : List.of("0", "-15", "1E+15", "15.00001")) {
        held("{\"maxCapacity\":" + bad + "}", type, "maxCapacity");
        held("{\"scaleInterval\":" + bad + "}", type, "scaleInterval");
      }
      taken("{\"maxCapacity\":15,\"scaleInterval\":0.005}", type, "maxCapacity");
      taken("{\"maxCapacity\":15,\"scaleInterval\":0.0050}", type, "scaleInterval");
    }
  }

  @Test
  @DisplayName(
      "A day's sales sent for rating are money at no more than four places and units at three;"
          + " a day of returns is below zero")
  void aDaysSalesAreHeld() {
    String day =
        "{\"from\":\"2026-09-01\",\"to\":\"2026-09-30\",\"sellers\":[{\"userId\":\"u\","
            + "\"days\":[{\"day\":\"2026-09-01\",\"net\":%s,\"units\":%s}]}]}";
    held(day.formatted("10.00005", "1"), CommissionDtos.RateRequest.class, "net");
    held(day.formatted("1E+17", "1"), CommissionDtos.RateRequest.class, "net");
    held(day.formatted("10", "1.0005"), CommissionDtos.RateRequest.class, "units");
    taken(day.formatted("-10.125", "-2.5"), CommissionDtos.RateRequest.class, "net");
    taken(day.formatted("-10.125", "-2.5"), CommissionDtos.RateRequest.class, "units");
  }

  // ── a figure is judged as its column keeps it, not as it is written ─────────────

  private static void allTaken(String json, Class<?> type) {
    Object body = bind(json, type);
    try {
      Validations.validate(body);
    } catch (ApiException e) {
      throw new AssertionError(json + " -> " + e.code() + " " + e.details(), e);
    }
  }

  @Test
  @DisplayName(
      "A figure is judged as its column keeps it: a rate of 0.23000, a price of 49.000000 and a"
          + " coordinate of 51.5073510 are what their columns keep unchanged, and are taken; a"
          + " figure finer or larger than the column is still held")
  void trailingZerosAreNotPlaces() {
    allTaken(
        "{\"country\":\"IE\",\"effectiveFrom\":\"2026-01-01\",\"rate\":0.23000000}",
        BillingDtos.RateRequest.class);
    allTaken("{\"currency\":\"GBP\",\"amount\":49.000000}", PlanDtos.PriceRequest.class);
    String where = "{\"geoLat\":51.5073510,\"geoLng\":-0.12750000}";
    taken(where, Dtos.CreateStoreRequest.class, "geoLat");
    taken(where, Dtos.CreateStoreRequest.class, "geoLng");
    String scale = "{\"maxCapacity\":15.00000,\"scaleInterval\":0.0050000}";
    taken(scale, Dtos.CreateWeighingInstrumentRequest.class, "maxCapacity");
    taken(scale, Dtos.CreateWeighingInstrumentRequest.class, "scaleInterval");

    held(
        "{\"country\":\"IE\",\"effectiveFrom\":\"2026-01-01\",\"rate\":0.230010}",
        BillingDtos.RateRequest.class,
        "rate");
    held("{\"currency\":\"GBP\",\"amount\":49.000010}", PlanDtos.PriceRequest.class, "amount");
    held("{\"geoLat\":51.50735110}", Dtos.CreateStoreRequest.class, "geoLat");
    assertEquals(
        List.of("amount: numeric value out of bounds (<14 digits>.<4 digits> expected)"),
        refusalsOf(
            "{\"currency\":\"GBP\",\"amount\":1.0E+14}", PlanDtos.PriceRequest.class, "amount"));
  }
}
