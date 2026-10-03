package com.storeql.web;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Bean Validation's cascade skips a null element of a list, so {@code {"lines":[null]}} used to
 * pass every rule and fail in the service with a 500. Every body is now refused for a hole in a
 * list, by name, with the rest of what is wrong with it.
 *
 * <p>A number is bound as written: {@code 1E-80000000} is twelve characters and a BigDecimal of
 * scale eighty million, which costs nothing until something rescales or writes it out. Every body
 * is refused, at once and before any constraint runs, for a number with more than 32 whole digits
 * or 32 decimal places, by name, wherever it sits.
 */
class ValidationsTest {

  public record Line(@NotBlank String variantId, BigDecimal price) {}

  public record Lines(@NotEmpty List<@Valid Line> lines, List<String> notes) {}

  public record Outer(@Valid Lines inner, List<List<String>> grid) {}

  /** A body of figures: one bounded by {@code @Digits}, the rest by nothing of their own. */
  public record Figures(
      @Digits(integer = 14, fraction = 4) BigDecimal price,
      BigDecimal qty,
      List<BigDecimal> rates,
      List<List<BigDecimal>> grid,
      BigInteger count,
      @NotBlank String reason) {}

  private static Figures price(String price) {
    return new Figures(new BigDecimal(price), null, null, null, null, "r");
  }

  private static Figures qty(String qty) {
    return new Figures(null, new BigDecimal(qty), null, null, null, "r");
  }

  private static Figures count(BigInteger count) {
    return new Figures(null, null, null, null, count, "r");
  }

  private static ApiException refused(Object body) {
    return assertThrows(ApiException.class, () -> Validations.validate(body));
  }

  /** A refusal that must come back well under a second, whatever the number's exponent. */
  private static ApiException refusedFast(Object body) {
    return assertTimeoutPreemptively(Duration.ofMillis(500), () -> refused(body));
  }

  /** The validator factory is built once, on first use; no timing below should pay for it. */
  @BeforeAll
  static void warmUp() {
    Validations.validate(new Lines(List.of(new Line("v1", BigDecimal.ONE)), null));
  }

  @Test
  @DisplayName("A null element of a list is refused by its place, never let through")
  void aNullElementIsRefusedByItsPlace() {
    ApiException e = refused(new Lines(Arrays.asList(new Line("v1", BigDecimal.ONE), null), null));
    assertEquals(400, e.status());
    assertEquals("VALIDATION_FAILED", e.code());
    assertEquals(List.of("lines[1]: must not be null"), e.details());
  }

  @Test
  @DisplayName("A hole is reported with the body's other faults, and found at any depth")
  void holesAreFoundAtDepthAndReportedWithTheRest() {
    ApiException e =
        refused(
            new Outer(
                new Lines(List.of(new Line(" ", null)), Arrays.asList("a", null)),
                List.of(List.of("x"), Arrays.asList((String) null))));
    assertEquals(
        List.of(
            "grid[1][0]: must not be null",
            "inner.notes[1]: must not be null",
            "variantId: must not be blank"),
        e.details());
  }

  @Test
  @DisplayName("A list with no holes, an empty optional list and an absent one all pass")
  void wholeListsPass() {
    assertDoesNotThrow(
        () -> Validations.validate(new Lines(List.of(new Line("v1", BigDecimal.TEN)), List.of())));
    assertDoesNotThrow(
        () -> Validations.validate(new Lines(List.of(new Line("v1", BigDecimal.TEN)), null)));
  }

  @Test
  @DisplayName(
      "A huge exponent either way is a fast 400 by name, whether or not the field has @Digits")
  void aHugeExponentIsRefusedFastByName() {
    for (String bad :
        new String[] {
          "1E+80000000",
          "1E-80000000",
          "-1E+80000000",
          "-1E-80000000",
          "0E-80000000",
          "0E+80000000",
          // precision() - scale() as an int wraps here, so @Digits' own arithmetic passes it
          "1E+2147483647",
          "1E+2147483648",
          "1E-2147483647"
        }) {
      ApiException onDigits = refusedFast(price(bad));
      assertEquals(400, onDigits.status(), bad);
      assertEquals("VALIDATION_FAILED", onDigits.code(), bad);
      assertEquals(List.of("price: is out of range"), onDigits.details(), bad);
      assertEquals(List.of("qty: is out of range"), refusedFast(qty(bad)).details(), bad);
    }
  }

  @Test
  @DisplayName("A number written with a million bits of digits is refused without counting them")
  void aVeryLongNumberIsRefusedWithoutCountingItsDigits() {
    BigInteger huge = BigInteger.ONE.shiftLeft(3_000_000).subtract(BigInteger.ONE);
    // An allowed scale, so only its length gives it away: counting its 903,090 digits is what
    // the guard must not do.
    assertEquals(
        List.of("qty: is out of range"),
        refusedFast(new Figures(null, new BigDecimal(huge, 32), null, null, null, "r")).details());
    assertEquals(List.of("count: is out of range"), refusedFast(count(huge)).details());
    assertEquals(List.of("count: is out of range"), refusedFast(count(huge.negate())).details());
  }

  @Test
  @DisplayName("32 whole digits and 32 decimal places pass; one more either way is refused")
  void theBoundsAreThirtyTwoWholeDigitsAndThirtyTwoPlaces() {
    String nines = "9".repeat(32);
    assertDoesNotThrow(() -> Validations.validate(qty(nines + "." + nines)));
    assertDoesNotThrow(() -> Validations.validate(qty("-" + nines + "." + nines)));
    assertDoesNotThrow(() -> Validations.validate(qty("1E+31")));
    assertDoesNotThrow(() -> Validations.validate(qty("1E-32")));
    assertDoesNotThrow(() -> Validations.validate(qty("0E-32")));
    for (String bad :
        new String[] {
          "1" + "0".repeat(32),
          "-1" + "0".repeat(32) + ".5",
          "1E+32",
          "0." + "0".repeat(32) + "1",
          "1E-33",
          "0E-33",
          "0E+32"
        }) {
      assertEquals(List.of("qty: is out of range"), refused(qty(bad)).details(), bad);
    }
    BigInteger most = BigInteger.TEN.pow(32).subtract(BigInteger.ONE);
    assertDoesNotThrow(() -> Validations.validate(count(most)));
    assertDoesNotThrow(() -> Validations.validate(count(most.negate())));
    assertEquals(
        List.of("count: is out of range"), refused(count(most.add(BigInteger.ONE))).details());
  }

  @Test
  @DisplayName("Ordinary money, quantities, rates and a till's doubles all pass")
  void ordinaryFiguresPass() {
    for (String money : new String[] {"0.001", "123456789012.34", "-12.50", "0", "0.00", "1E+3"}) {
      assertDoesNotThrow(() -> Validations.validate(price(money)), money);
    }
    for (String figure :
        new String[] {
          "0.375", // a weighed quantity
          "3.3000000000000003", // 3 x 1.10 in a till's double
          "0.7999999999999999",
          "4.87125",
          "1.0E-7", // a double as Dart writes a small one
          "0.0000003074", // home dinars for one rial
          "460000000000000000000000000000", // pengo to the dollar, 1946
          "1E-12"
        }) {
      assertDoesNotThrow(() -> Validations.validate(qty(figure)), figure);
    }
    assertDoesNotThrow(
        () ->
            Validations.validate(
                new Figures(
                    new BigDecimal("19.99"),
                    new BigDecimal("2.5"),
                    List.of(new BigDecimal("0.2"), new BigDecimal("1.0825")),
                    List.of(List.of(BigDecimal.ONE), List.of()),
                    BigInteger.valueOf(Long.MAX_VALUE),
                    "r")));
  }

  @Test
  @DisplayName("An absurd number is named by its place in lists, lists of lists and nested records")
  void anAbsurdNumberIsNamedByItsPlaceAtAnyDepth() {
    ApiException e =
        refusedFast(
            new Figures(
                null,
                null,
                List.of(BigDecimal.ONE, new BigDecimal("1E+40")),
                List.of(List.of(BigDecimal.ONE), List.of(BigDecimal.TEN, new BigDecimal("1E-40"))),
                null,
                "r"));
    assertEquals(List.of("grid[1][1]: is out of range", "rates[1]: is out of range"), e.details());

    ApiException nested =
        refusedFast(
            new Outer(
                new Lines(
                    List.of(
                        new Line("v1", BigDecimal.ONE),
                        new Line("v2", new BigDecimal("1E-80000000"))),
                    null),
                null));
    assertEquals(List.of("inner.lines[1].price: is out of range"), nested.details());
  }

  @Test
  @DisplayName(
      "An absurd number is refused before Bean Validation, with the holes found on the same walk")
  void anAbsurdNumberIsRefusedBeforeBeanValidation() {
    // The blank reason is Bean Validation's to report; it never runs on a body like this.
    ApiException e =
        refusedFast(
            new Figures(
                new BigDecimal("1E+2147483647"),
                null,
                Arrays.asList(BigDecimal.ONE, null),
                null,
                null,
                " "));
    assertEquals(List.of("price: is out of range", "rates[1]: must not be null"), e.details());
    // With the number in range, the same body is judged by Bean Validation as before.
    ApiException judged =
        refused(
            new Figures(
                BigDecimal.ONE, null, Arrays.asList(BigDecimal.ONE, null), null, null, " "));
    assertEquals(
        List.of("rates[1]: must not be null", "reason: must not be blank"), judged.details());
  }

  @Test
  @DisplayName("No body at all is still BODY_REQUIRED")
  void noBodyIsStillBodyRequired() {
    assertEquals("BODY_REQUIRED", refused(null).code());
  }
}
