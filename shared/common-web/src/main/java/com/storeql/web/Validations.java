package com.storeql.web;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Explicit Bean Validation that produces our standard envelope.
 *
 * <p>We validate in the resource via {@link #validate(Object)} instead of relying on JAX-RS
 * {@code @Valid}, because Helidon registers its own {@code ConstraintViolationException} mapper
 * that returns a verbose, internals-leaking body. Calling this ourselves throws {@link
 * ApiException} with a clean {@code VALIDATION_FAILED} 400 (golden rule #15, docs/ARCHITECTURE.md
 * §14).
 */
public final class Validations {

  private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
  private static final Validator VALIDATOR = FACTORY.getValidator();

  private Validations() {}

  /**
   * Validate a bean against its Bean Validation ({@code jakarta.validation}) annotations, after
   * refusing what no annotation is relied on for: a hole in a list, and a number no figure in the
   * platform could be.
   *
   * <p>The body is walked first. A number out of range ({@link #outOfRange(BigDecimal)}) is refused
   * there and then, with the holes the same walk found, and Bean Validation does not run: no
   * constraint, and nothing after it, ever handles such a value. Otherwise the holes are reported
   * with every failed constraint.
   *
   * @param bean the DTO to validate, typically a deserialized request body
   * @throws ApiException 400 {@link ErrorCodes#BODY_REQUIRED} if {@code bean} is {@code null}; 400
   *     {@link ErrorCodes#VALIDATION_FAILED} with one {@code "<field>: <message>"} detail per
   *     failed constraint, null list element ({@code "lines[0]: must not be null"}) or number out
   *     of range ({@code "lines[0].price: is out of range"}), sorted, if validation fails
   */
  public static <T> void validate(T bean) {
    if (bean == null) {
      throw ApiException.badRequest(ErrorCodes.BODY_REQUIRED, "Request body required");
    }
    List<String> nulls = new ArrayList<>();
    List<String> outOfRange = new ArrayList<>();
    walk(bean, "", 0, nulls, outOfRange);
    if (!outOfRange.isEmpty()) {
      throw failed(Stream.concat(outOfRange.stream(), nulls.stream()));
    }
    Set<ConstraintViolation<T>> violations = VALIDATOR.validate(bean);
    if (violations.isEmpty() && nulls.isEmpty()) {
      return;
    }
    throw failed(
        Stream.concat(
            violations.stream().map(v -> leafField(v) + ": " + v.getMessage()), nulls.stream()));
  }

  private static ApiException failed(Stream<String> details) {
    return new ApiException(
        400, ErrorCodes.VALIDATION_FAILED, "Request validation failed", details.sorted().toList());
  }

  /** How deep a body is walked: request bodies are shallow trees. */
  private static final int MAX_DEPTH = 8;

  /**
   * The most whole digits a number in a request body may have. Twice the widest money or quantity
   * the platform holds (16 whole digits: every fixed column and every {@code @Digits} on a
   * request), and more than any exchange rate history has needed (the pengő, 1946: some 4.6 ×
   * 10<sup>29</sup> to the dollar, 30 digits).
   */
  static final int MOST_WHOLE_DIGITS = 32;

  /**
   * The most decimal places a number in a request body may be written with. Above the finest any
   * request takes (twenty, a till's tender as the app's double writes it; columns keep at most ten,
   * an exchange rate) and the finest real figure it could carry: a till's double for a real amount
   * has at most seventeen significant digits, and one finer than 10<sup>-15</sup> is a rounding
   * residue every field that rounds treats as nothing.
   */
  static final int MOST_DECIMAL_PLACES = 32;

  /**
   * An unscaled value longer than this has more than 77 digits, so too many whole digits at any
   * allowed scale: refused on its length, without counting its digits, which costs time with it.
   */
  private static final int MOST_UNSCALED_BITS = 256;

  /**
   * Whether a number is beyond any money, quantity or rate: more than {@link #MOST_WHOLE_DIGITS}
   * whole digits or more than {@link #MOST_DECIMAL_PLACES} decimal places as written (trailing
   * zeros count, as {@code @Digits} counts them; a zero is judged by how it is written too).
   *
   * <p>The cost is constant whatever the number: the scale is compared as a long, the length of the
   * unscaled value read from its bit count, and its digits counted only when that length is under
   * {@link #MOST_UNSCALED_BITS}. The value is never rescaled, stripped or written out: {@code
   * 1E-80000000} is twelve characters on the wire, and any of those on it builds a number eighty
   * million digits long. Bean Validation's {@code @Digits} is no guard here: it subtracts scale
   * from precision in an {@code int}, so {@code 1E+2147483647} wraps round and passes it.
   *
   * @param value a number from a request body
   * @return whether it is refused
   */
  static boolean outOfRange(BigDecimal value) {
    long scale = value.scale();
    if (scale > MOST_DECIMAL_PLACES) {
      return true;
    }
    if (value.unscaledValue().bitLength() > MOST_UNSCALED_BITS) {
      return true;
    }
    return value.precision() - scale > MOST_WHOLE_DIGITS;
  }

  /**
   * Whether a whole number has more than {@link #MOST_WHOLE_DIGITS} digits, at constant cost.
   *
   * @param value a whole number from a request body
   * @return whether it is refused
   */
  static boolean outOfRange(BigInteger value) {
    return value.bitLength() > MOST_UNSCALED_BITS
        || new BigDecimal(value).precision() > MOST_WHOLE_DIGITS;
  }

  /**
   * Walks a request body once, through our own records and every list in them, collecting every
   * null element of a list ({@code "lines[0]: must not be null"}) and every number out of range
   * ({@code "lines[0].price: is out of range"}).
   *
   * <p>Bean Validation's cascade skips a null element, so {@code {"lines":[null]}} passed every
   * rule and reached the service, which failed on it with a 500. A list of things is never sent
   * with holes in it, so a hole is refused here, for every body.
   *
   * <p>A number is bound as written, so a body can carry one no figure in the platform could be,
   * cheap to read and ruinous to use; that is refused here, for every body, before anything uses it
   * ({@link #outOfRange(BigDecimal)}).
   */
  private static void walk(
      Object value, String path, int depth, List<String> nulls, List<String> outOfRange) {
    if (value == null || depth > MAX_DEPTH) {
      return;
    }
    if (value instanceof BigDecimal number) {
      if (outOfRange(number)) {
        outOfRange.add(path + ": is out of range");
      }
      return;
    }
    if (value instanceof BigInteger number) {
      if (outOfRange(number)) {
        outOfRange.add(path + ": is out of range");
      }
      return;
    }
    if (value instanceof Collection<?> items) {
      int i = 0;
      for (Object item : items) {
        String at = path + "[" + i++ + "]";
        if (item == null) {
          nulls.add(at + ": must not be null");
        } else {
          walk(item, at, depth + 1, nulls, outOfRange);
        }
      }
      return;
    }
    Class<?> type = value.getClass();
    if (!type.isRecord() || !type.getName().startsWith("com.storeql.")) {
      return;
    }
    for (RecordComponent component : type.getRecordComponents()) {
      Object field;
      try {
        field = component.getAccessor().invoke(value);
      } catch (ReflectiveOperationException | IllegalArgumentException e) {
        continue; // a record this class cannot read is left to Bean Validation alone
      }
      walk(
          field,
          path.isEmpty() ? component.getName() : path + "." + component.getName(),
          depth + 1,
          nulls,
          outOfRange);
    }
  }

  /**
   * @param v a constraint violation
   * @return just the last path segment of {@code v}'s property path, e.g. {@code "email"} rather
   *     than {@code "address.email"}
   */
  private static String leafField(ConstraintViolation<?> v) {
    String path = v.getPropertyPath().toString();
    int dot = path.lastIndexOf('.');
    return dot >= 0 ? path.substring(dot + 1) : path;
  }
}
