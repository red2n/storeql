package com.storeql.inventory.dto;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.math.BigDecimal;

/**
 * A decimal no larger and no finer than where it is kept: at most {@link #integer()} whole digits
 * and {@link #fraction()} decimal places, judged on the figure, not on how it was written.
 *
 * <p>Bean Validation's {@code @Digits} counts a {@code BigDecimal}'s places as written, trailing
 * zeros too, so {@code @Digits(integer = 15, fraction = 3)} refused a quantity of {@code 1.0000},
 * which a {@code NUMERIC(18,3)} column keeps as {@code 1.000} unchanged. Callers forward a figure
 * as they read it: order-svc posts a basket's {@code 1.0000} to a hold, product-svc's import a
 * sheet's {@code 2.5000} to a receipt. A column overflows only on whole digits and rounds only a
 * place that is not zero, so that is what is judged here, on the figure with its trailing zeros
 * dropped: {@code 2.5000} has one place, {@code 1E+3} four whole digits, and a zero fits anywhere.
 * The refusal reads as {@code @Digits}' does. A {@code null} is valid ({@code @NotNull} decides).
 *
 * <p>The cost is bounded whatever the number: whole digits and places are worked in {@code long}
 * (where {@code @Digits}' {@code int} wraps on {@code 1E+2147483647} and lets it through), and a
 * figure written in more than {@link Check#MOST_UNSCALED_BITS} bits (some 77 digits) is refused
 * without being stripped, since stripping costs time with the square of its length. Common-web's
 * {@code Validations.validate} refuses every such figure before Bean Validation runs; this holds
 * where it is used alone. (Here rather than in common-web, where it belongs beside that walk,
 * because the pass that made it could change only tenant-svc and inventory-svc; tenant-svc has its
 * twin.)
 */
@Documented
@Constraint(validatedBy = Fits.Check.class)
@Target({FIELD, METHOD, PARAMETER, ANNOTATION_TYPE, TYPE_USE})
@Retention(RUNTIME)
public @interface Fits {

  /**
   * @return the refusal, in {@code @Digits}' words
   */
  String message() default
      "numeric value out of bounds (<{integer} digits>.<{fraction} digits> expected)";

  /**
   * @return the validation groups
   */
  Class<?>[] groups() default {};

  /**
   * @return the payload
   */
  Class<? extends Payload>[] payload() default {};

  /**
   * @return the most whole digits the figure may have
   */
  int integer();

  /**
   * @return the most decimal places the figure may have once its trailing zeros are dropped
   */
  int fraction();

  /** Judges a {@code BigDecimal} against its {@link Fits}. */
  final class Check implements ConstraintValidator<Fits, BigDecimal> {

    /** Above this an unscaled value has more than 77 digits: refused unstripped. */
    static final int MOST_UNSCALED_BITS = 256;

    private int integer;
    private int fraction;

    @Override
    public void initialize(Fits bounds) {
      integer = bounds.integer();
      fraction = bounds.fraction();
    }

    @Override
    public boolean isValid(BigDecimal value, ConstraintValidatorContext context) {
      if (value == null || value.signum() == 0) {
        return true;
      }
      if (value.unscaledValue().bitLength() > MOST_UNSCALED_BITS) {
        return false;
      }
      // Whole digits are the same before and after trailing zeros are dropped.
      if ((long) value.precision() - value.scale() > integer) {
        return false;
      }
      // Only places past the bound are stripped: at most 77 digits, and the scale stays above zero
      // while it falls, so it never underflows.
      return value.scale() <= fraction || value.stripTrailingZeros().scale() <= fraction;
    }
  }
}
