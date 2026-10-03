package com.storeql.inventory.dto;

import jakarta.json.bind.JsonbException;
import jakarta.json.bind.serializer.DeserializationContext;
import jakarta.json.bind.serializer.JsonbDeserializer;
import jakarta.json.stream.JsonParser;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Whole numbers in a request body, read exactly or not at all.
 *
 * <p>JSON-B's own reading of an {@code int}, {@code Integer}, {@code long} or {@code Long} field
 * cuts the number down without a word: Yasson asks the parser for {@code getInt()}/{@code
 * getLong()}, and Parsson answers with {@code getBigDecimal().intValue()}/{@code longValue()}, so
 * {@code 4294967326} is bound as {@code 30}, {@code 30.9} as {@code 30} and {@code 1E+80000000} as
 * {@code 0}, and the cut figure then passes every {@code @Min}/{@code @Max} and is written with a
 * {@code 200}. Common-web's {@code Validations.validate} never sees what was sent, only the cut
 * figure. So every whole-number field of a request body is read here instead ({@code
 * RequestBodyNumbersTest} fails the build on one that is not): a number is taken only when it is
 * exactly a whole number within the field's type ({@code 30.0} and {@code 3E1} are 30), and
 * anything else is a {@link JsonbException}, which the platform answers as {@code 400
 * REQUEST_BODY_INVALID} — the body is not the shape the request takes, as {@code "thirty"} is not.
 * A number written as a string ({@code "30"}) is read as JSON-B always read it, by {@code
 * Integer.parseInt}/{@code Long.parseLong}, which are exact already.
 *
 * <p>Annotate the field, never the type: {@code @JsonbTypeDeserializer(WholeNumbers.ExactInt.class)
 * Integer days}. A {@code null} stays {@code null} (a required field says {@code @NotNull}), and a
 * list or set keeps a {@code null} element so that {@code Validations.validate} names it.
 */
public final class WholeNumbers {

  private WholeNumbers() {}

  /** An {@code int} or {@code Integer} field. */
  public static final class ExactInt implements JsonbDeserializer<Integer> {
    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext ctx, Type rtType) {
      return readInt(parser);
    }
  }

  /** A {@code long} or {@code Long} field. */
  public static final class ExactLong implements JsonbDeserializer<Long> {
    @Override
    public Long deserialize(JsonParser parser, DeserializationContext ctx, Type rtType) {
      return readLong(parser);
    }
  }

  /** A {@code List<Integer>} field: each element read as {@link ExactInt} reads one. */
  public static final class ExactIntList implements JsonbDeserializer<List<Integer>> {
    @Override
    public List<Integer> deserialize(JsonParser parser, DeserializationContext ctx, Type rtType) {
      return readInts(parser, new ArrayList<>());
    }
  }

  /** A {@code Set<Integer>} field: each element read as {@link ExactInt} reads one. */
  public static final class ExactIntSet implements JsonbDeserializer<Set<Integer>> {
    @Override
    public Set<Integer> deserialize(JsonParser parser, DeserializationContext ctx, Type rtType) {
      return readInts(parser, new LinkedHashSet<>());
    }
  }

  // A JSON null stays null, so that @NotNull decides: an empty list in its place would pass it.
  @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
  private static <C extends Collection<Integer>> C readInts(JsonParser parser, C into) {
    if (parser.currentEvent() == JsonParser.Event.VALUE_NULL) {
      return null;
    }
    if (parser.currentEvent() != JsonParser.Event.START_ARRAY) {
      throw new JsonbException("A list of whole numbers is required");
    }
    while (parser.hasNext()) {
      if (parser.next() == JsonParser.Event.END_ARRAY) {
        return into;
      }
      into.add(readInt(parser));
    }
    throw new JsonbException("A list of whole numbers is not closed");
  }

  private static Integer readInt(JsonParser parser) {
    return switch (parser.currentEvent()) {
      case VALUE_NULL -> null;
      case VALUE_STRING -> parse(parser.getString(), true).intValue();
      case VALUE_NUMBER -> exact(parser.getBigDecimal(), true).intValue();
      default -> throw notWhole(null);
    };
  }

  private static Long readLong(JsonParser parser) {
    return switch (parser.currentEvent()) {
      case VALUE_NULL -> null;
      case VALUE_STRING -> parse(parser.getString(), false);
      case VALUE_NUMBER -> exact(parser.getBigDecimal(), false);
      default -> throw notWhole(null);
    };
  }

  /**
   * The number as a whole number of the field's width, or a refusal. {@code intValueExact} and
   * {@code longValueExact} judge a number by its precision and scale before any arithmetic, so
   * {@code 1E+80000000} and {@code 1E-80000000} are refused at once, never expanded.
   */
  private static Long exact(BigDecimal number, boolean narrow) {
    try {
      return narrow ? number.intValueExact() : number.longValueExact();
    } catch (ArithmeticException e) {
      throw notWhole(e);
    }
  }

  private static Long parse(String text, boolean narrow) {
    try {
      return narrow ? Integer.parseInt(text) : Long.parseLong(text);
    } catch (NumberFormatException e) {
      throw notWhole(e);
    }
  }

  private static JsonbException notWhole(Exception cause) {
    return new JsonbException("A whole number within the field's range is required", cause);
  }
}
