package com.storeql.inventory.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.inventory.dto.Dtos.AdjustRequest;
import com.storeql.inventory.dto.Dtos.BatchReceiveRequest;
import com.storeql.inventory.dto.Dtos.BondReleaseRequest;
import com.storeql.inventory.dto.Dtos.CreateKanbanCardRequest;
import com.storeql.inventory.dto.Dtos.ReceiveRequest;
import com.storeql.inventory.dto.Dtos.ReserveRequest;
import com.storeql.inventory.dto.Dtos.SetSafetyStockRequest;
import com.storeql.inventory.dto.Dtos.SetZonePrioritiesRequest;
import com.storeql.inventory.dto.Dtos.ThresholdRequest;
import com.storeql.inventory.dto.Dtos.UpsertCostingMethodRequest;
import com.storeql.inventory.dto.Dtos.UpsertRopPlanRequest;
import com.storeql.inventory.dto.Dtos.YieldOutputSpecRequest;
import com.storeql.inventory.dto.Dtos.YieldRunRequest;
import com.storeql.inventory.dto.Fits;
import com.storeql.inventory.dto.NetworkDtos;
import com.storeql.inventory.dto.WaveDtos.PickLineRequest;
import com.storeql.inventory.dto.WholeNumbers;
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
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.constraints.Digits;
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
 * Validation's {@code @Digits}, which counts trailing zeros as places and so refused a quantity of
 * {@code 1.0000} that the column keeps unchanged. Both are checked here for every body every
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
  private static final String CELSIUS =
      "FoodSafetyService.celsius: FOOD_SAFETY_TOO_PRECISE past two places,"
          + " FOOD_SAFETY_OUT_OF_RANGE outside -273.15 to 1000, within NUMERIC(6,2)";

  private static final Map<String, String> BOUNDED_BY_THE_SERVICE =
      Map.ofEntries(
          Map.entry("dto.FoodSafetyDtos$CreateCheckTypeRequest.minValue", CELSIUS),
          Map.entry("dto.FoodSafetyDtos$CreateCheckTypeRequest.maxValue", CELSIUS),
          Map.entry("dto.FoodSafetyDtos$UpdateCheckTypeRequest.minValue", CELSIUS),
          Map.entry("dto.FoodSafetyDtos$UpdateCheckTypeRequest.maxValue", CELSIUS),
          Map.entry("dto.FoodSafetyDtos$CreatePointRequest.minValue", CELSIUS),
          Map.entry("dto.FoodSafetyDtos$CreatePointRequest.maxValue", CELSIUS),
          Map.entry("dto.FoodSafetyDtos$UpdatePointRequest.minValue", CELSIUS),
          Map.entry("dto.FoodSafetyDtos$UpdatePointRequest.maxValue", CELSIUS),
          Map.entry("dto.FoodSafetyDtos$RecordCheckRequest.value", CELSIUS),
          Map.entry(
              "dto.RecallDtos$StoreActionRequest.qtyFound",
              "RecallService.recordStoreAction: RECALL_QTY_INVALID below zero, past three places or"
                  + " fifteen whole digits, within NUMERIC(18,3)"),
          Map.entry(
              "dto.Dtos$DutyRateRequest.dutyPerUnit",
              "excise_duty_rates.duty_per_unit is unconstrained NUMERIC: kept as sent, never"
                  + " rounded or overflowed; Validations' 32 whole digits and 32 places bound it"));

  // ── every body: whole numbers read exactly, decimals bounded ──────────────────

  /** Every type a resource method of this service takes as its body. */
  private static Map<String, Type> bodies() {
    Map<String, Type> out = new java.util.TreeMap<>();
    for (JavaClass c :
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.storeql.inventory.api")) {
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
          cls.getName().replaceFirst("^com\\.storeql\\.inventory\\.", "") + "." + rc.getName();
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
          cls.getName().replaceFirst("^com\\.storeql\\.inventory\\.", "") + "." + rc.getName();
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

  // ── the binding itself, through the JSON-B every service binds with ─────────────

  private static <T> T bind(String json, Class<T> type) {
    return JSONB.fromJson(json, type);
  }

  private static void refusedAtBinding(String json, Class<?> type) {
    assertThrows(JsonbException.class, () -> bind(json, type), json);
  }

  private static final String ROP =
      "{\"storeId\":\"s\",\"variantId\":\"v\",\"leadTimeDays\":%s,\"orderingCost\":%s,"
          + "\"holdingCostPct\":%s,\"unitCost\":%s}";

  @Test
  @DisplayName(
      "A reorder-point plan's lead time is the days sent, or the body is refused: 4294967303 is"
          + " not bound as 7")
  void aPlansLeadTimeIsReadExactly() {
    for (String cut : List.of("4294967303", "1E+80000000", "7.5", "\"7.5\"")) {
      refusedAtBinding(ROP.formatted(cut, "50", "20", "1"), UpsertRopPlanRequest.class);
    }
    assertEquals(
        7, bind(ROP.formatted("7", "50", "20", "1"), UpsertRopPlanRequest.class).leadTimeDays());
    assertEquals(
        7, bind(ROP.formatted("7.0", "50", "20", "1"), UpsertRopPlanRequest.class).leadTimeDays());
  }

  @Test
  @DisplayName(
      "A serving's lead time, a proposal's cover, a hold's lifetime and a zone's priority are the"
          + " figures sent, or the body is refused")
  void otherWholeNumbersAreReadExactly() {
    refusedAtBinding(
        "{\"storeId\":\"s\",\"warehouseId\":\"w\",\"leadTimeDays\":4294967298}",
        NetworkDtos.ServingRequest.class);
    refusedAtBinding(
        "{\"warehouseId\":\"w\",\"coverDays\":4294967303}",
        NetworkDtos.TransferProposalRequest.class);
    refusedAtBinding(
        "{\"storeId\":\"s\",\"variantId\":\"v\",\"qty\":1,\"ttlSeconds\":9223372036854775808}",
        ReserveRequest.class);
    refusedAtBinding(
        "{\"storeId\":\"s\",\"variantId\":\"v\",\"qty\":1,\"ttlSeconds\":900.5}",
        ReserveRequest.class);
    refusedAtBinding(
        "{\"zonePriorities\":[{\"zoneId\":\"z\",\"priority\":4294967297}]}",
        SetZonePrioritiesRequest.class);
    assertEquals(
        2,
        bind(
                "{\"zonePriorities\":[{\"zoneId\":\"z\",\"priority\":2}]}",
                SetZonePrioritiesRequest.class)
            .zonePriorities()
            .get(0)
            .priority());
    assertEquals(
        900L,
        bind(
                "{\"storeId\":\"s\",\"variantId\":\"v\",\"qty\":1,\"ttlSeconds\":900}",
                ReserveRequest.class)
            .ttlSeconds());
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
      "A receipt's quantity is what a batch keeps: 1E+20 overflowed it as a 500 and 0.0004 was"
          + " kept as a batch of nothing")
  void aReceiptIsHeldToTheBatch() {
    String receive = "{\"storeId\":\"s\",\"variantId\":\"v\",\"qty\":%s,\"costPrice\":%s}";
    held(receive.formatted("1E+20", "1"), ReceiveRequest.class, "qty");
    held(receive.formatted("1E+15", "1"), ReceiveRequest.class, "qty");
    held(receive.formatted("0.0004", "1"), ReceiveRequest.class, "qty");
    held(receive.formatted("1", "1.00005"), ReceiveRequest.class, "costPrice");
    held(receive.formatted("1", "1E+14"), ReceiveRequest.class, "costPrice");
    taken(receive.formatted("999999999999999.999", "12.3456"), ReceiveRequest.class, "qty");
    taken(receive.formatted("0.001", "12.3456"), ReceiveRequest.class, "costPrice");
    taken(receive.formatted("2.500", "1.50000000"), ReceiveRequest.class, "qty");
  }

  @Test
  @DisplayName(
      "A reorder-point plan's costs are held to the plan: a holding cost of 1000 overflowed"
          + " NUMERIC(7,4) as a 500 and 50.005 was kept as 50.01")
  void aPlansCostsAreHeld() {
    held(ROP.formatted("7", "50", "1000", "1"), UpsertRopPlanRequest.class, "holdingCostPct");
    held(ROP.formatted("7", "50", "20.00005", "1"), UpsertRopPlanRequest.class, "holdingCostPct");
    held(ROP.formatted("7", "50.005", "20", "1"), UpsertRopPlanRequest.class, "orderingCost");
    held(ROP.formatted("7", "50", "20", "1.0000005"), UpsertRopPlanRequest.class, "unitCost");
    held(ROP.formatted("366", "50", "20", "1"), UpsertRopPlanRequest.class, "leadTimeDays");
    taken(
        ROP.formatted("365", "50.01", "999.9999", "1.000001"),
        UpsertRopPlanRequest.class,
        "holdingCostPct");
    taken(
        ROP.formatted("365", "50.01", "999.9999", "1.000001"),
        UpsertRopPlanRequest.class,
        "orderingCost");
    taken(
        ROP.formatted("365", "50.01", "999.9999", "1.000001"),
        UpsertRopPlanRequest.class,
        "leadTimeDays");
  }

  @Test
  @DisplayName(
      "A hold lives for a second to a year: Long.MAX_VALUE seconds was past any instant, a 500")
  void aHoldsLifetimeIsHeld() {
    String reserve = "{\"storeId\":\"s\",\"variantId\":\"v\",\"qty\":%s,\"ttlSeconds\":%s}";
    held(reserve.formatted("1", "9223372036854775807"), ReserveRequest.class, "ttlSeconds");
    held(reserve.formatted("1", "0"), ReserveRequest.class, "ttlSeconds");
    held(reserve.formatted("1", "31536001"), ReserveRequest.class, "ttlSeconds");
    held(reserve.formatted("1.0001", "900"), ReserveRequest.class, "qty");
    taken(reserve.formatted("1", "172800"), ReserveRequest.class, "ttlSeconds");
    taken(reserve.formatted("1", "null"), ReserveRequest.class, "ttlSeconds");
  }

  @Test
  @DisplayName(
      "Every other quantity is a quantity as kept: fifteen whole digits and three places, signed"
          + " where it may be")
  void quantitiesAreHeld() {
    held("{\"storeId\":\"s\",\"variantId\":\"v\",\"delta\":-1E+15}", AdjustRequest.class, "delta");
    held("{\"storeId\":\"s\",\"variantId\":\"v\",\"delta\":0.0001}", AdjustRequest.class, "delta");
    taken("{\"storeId\":\"s\",\"variantId\":\"v\",\"delta\":-2.5}", AdjustRequest.class, "delta");
    held("{\"storeId\":\"s\",\"variantId\":\"v\",\"qty\":1E+16}", BondReleaseRequest.class, "qty");
    held(
        "{\"storeId\":\"s\",\"templateId\":\"t\",\"inputQty\":1.0005,\"outputs\":[]}",
        YieldRunRequest.class,
        "inputQty");
    held(
        "{\"storeId\":\"s\",\"variantId\":\"v\",\"kanbanType\":\"PRODUCTION\",\"reorderQty\":1E+16}",
        CreateKanbanCardRequest.class,
        "reorderQty");
    held(
        "{\"storeId\":\"s\",\"variantId\":\"v\",\"method\":\"AVERAGE\",\"averageCost\":0.0000001}",
        UpsertCostingMethodRequest.class,
        "averageCost");
    held("{\"lineId\":\"l\",\"pickedQty\":1E+16}", PickLineRequest.class, "pickedQty");
    held(
        "{\"storeId\":\"s\",\"variantId\":\"v\",\"threshold\":1E+9}",
        ThresholdRequest.class,
        "threshold");
    held(
        "{\"storeId\":\"s\",\"variantId\":\"v\",\"threshold\":5,\"maxQty\":1E+9}",
        ThresholdRequest.class,
        "maxQty");
    held(
        "{\"storeId\":\"s\",\"variantId\":\"v\",\"method\":\"MAD\",\"serviceLevelPct\":100}",
        SetSafetyStockRequest.class,
        "serviceLevelPct");
    taken(
        "{\"storeId\":\"s\",\"variantId\":\"v\",\"method\":\"MAD\",\"serviceLevelPct\":99.9}",
        SetSafetyStockRequest.class,
        "serviceLevelPct");
    held(
        "{\"variantId\":\"v\",\"expectedPct\":10,\"costShare\":-1}",
        YieldOutputSpecRequest.class,
        "costShare");
    held(
        "{\"variantId\":\"v\",\"expectedPct\":10,\"shelfLifeDays\":0}",
        YieldOutputSpecRequest.class,
        "shelfLifeDays");
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
      "A figure is judged as its column keeps it: order-svc's hold of 1.0000 and product-svc's"
          + " import of 2.5000 were refused by @Digits for zeros the column keeps unchanged, and are"
          + " taken; a figure finer or larger than the column is still held")
  void trailingZerosAreNotPlaces() {
    // order-svc's InventoryClient posts Quantities.typed's 1.0000 as written.
    allTaken(
        "{\"storeId\":\"s\",\"variantId\":\"v\",\"qty\":1.0000,\"ttlSeconds\":172800}",
        ReserveRequest.class);
    // product-svc's CSV import posts a sheet's 2.5000 as written, a chunk to a call.
    allTaken(
        "{\"items\":[{\"storeId\":\"s\",\"variantId\":\"v\",\"qty\":2.5000},"
            + "{\"storeId\":\"s\",\"variantId\":\"w\",\"qty\":1.0000}]}",
        BatchReceiveRequest.class);
    allTaken(
        "{\"storeId\":\"s\",\"variantId\":\"v\",\"qty\":2.5000,\"costPrice\":1.50000000}",
        ReceiveRequest.class);
    allTaken(
        "{\"storeId\":\"s\",\"variantId\":\"v\",\"qty\":999999999999999.999000,"
            + "\"costPrice\":0.00000000}",
        ReceiveRequest.class);
    allTaken("{\"storeId\":\"s\",\"variantId\":\"v\",\"delta\":-2.50000000}", AdjustRequest.class);
    // Whole digits are the figure's too, however it is written: 1E+3 is a thousand.
    allTaken("{\"storeId\":\"s\",\"variantId\":\"v\",\"qty\":1E+3}", ReceiveRequest.class);

    String reserve = "{\"storeId\":\"s\",\"variantId\":\"v\",\"qty\":%s}";
    held(reserve.formatted("1.00010"), ReserveRequest.class, "qty");
    held(reserve.formatted("1000000000000000.000"), ReserveRequest.class, "qty");
    held(reserve.formatted("1.0E+15"), ReserveRequest.class, "qty");
    held(
        "{\"items\":[{\"storeId\":\"s\",\"variantId\":\"v\",\"qty\":2.50050}]}",
        BatchReceiveRequest.class,
        "qty");
    held(
        "{\"storeId\":\"s\",\"variantId\":\"v\",\"qty\":1,\"costPrice\":1.000050}",
        ReceiveRequest.class,
        "costPrice");
    // Told in @Digits' own words, so a caller that read those still reads these.
    assertEquals(
        List.of("qty: numeric value out of bounds (<15 digits>.<3 digits> expected)"),
        refusalsOf(reserve.formatted("1.00010"), ReserveRequest.class, "qty"));
  }

  /** A body of this test's own, to judge {@link Fits} alone, with no walk before it. */
  record Figure(@Fits(integer = 15, fraction = 3) BigDecimal qty) {}

  private static Set<ConstraintViolation<Figure>> judged(String figure) {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      return factory.getValidator().validate(new Figure(new BigDecimal(figure)));
    }
  }

  @Test
  @DisplayName(
      "@Fits on its own neither wraps nor expands: 1E+2147483647 and a figure written in 300"
          + " digits are refused at once, and a zero is taken however it is written")
  void fitsAloneIsSafe() {
    for (String no :
        List.of(
            "1E+2147483647",
            "-1E+2147483647",
            "1E-2147483647",
            "1E+15",
            "0.0001",
            "1" + "0".repeat(300) + "E-300")) {
      assertEquals(1, judged(no).size(), no);
    }
    for (String yes :
        List.of(
            "0",
            "0.0000",
            "0E+2147483647",
            "0E-2147483647",
            "-999999999999999.999",
            "999999999999999.9990000",
            "1.0000",
            "1E+14")) {
      assertEquals(Set.of(), judged(yes), yes);
    }
  }
}
