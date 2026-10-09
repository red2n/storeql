package com.storeql.order.dto;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.storeql.ids.Ids;
import com.storeql.order.dto.Dtos.ExchangeNewItemRequest;
import com.storeql.order.dto.Dtos.ExchangeRequest;
import com.storeql.order.dto.Dtos.FulfilLine;
import com.storeql.order.dto.Dtos.LayawayItemRequest;
import com.storeql.order.dto.Dtos.OrderItemRequest;
import com.storeql.order.dto.Dtos.ParkSaleRequest;
import com.storeql.order.dto.Dtos.ParkedSaleItemRequest;
import com.storeql.order.dto.Dtos.ReturnItemRequest;
import com.storeql.order.dto.Dtos.ShortCloseRequest;
import com.storeql.order.dto.Dtos.SpecialOrderItemRequest;
import com.storeql.order.dto.Dtos.SubstituteRequest;
import com.storeql.web.ApiException;
import com.storeql.web.Validations;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Every quantity a request carries is bounded at the door: three decimal places wherever a person
 * types or chooses it (a return, a layaway, a special order, a pick, a short close, a substitute),
 * twenty where the till's scanner and floating point may send it (a sale's line, a parked basket,
 * an exchange's new items) — which the service then counts at the gram below or refuses — and never
 * an exponent that costs anything to look at.
 */
class QuantityBoundsTest {

  private static final String V = Ids.newId().toString();
  private static final BigDecimal PRICE = new BigDecimal("1.99");

  private static void refused(Object body) {
    ApiException e = assertThrows(ApiException.class, () -> Validations.validate(body));
    assertEquals("VALIDATION_FAILED", e.code());
    assertEquals(400, e.status());
  }

  private static void taken(Object body) {
    assertDoesNotThrow(() -> Validations.validate(body));
  }

  private static final List<Function<BigDecimal, Object>> TYPED =
      List.of(
          q -> new ReturnItemRequest(V, q, "SEALED"),
          q -> new LayawayItemRequest(V, q, PRICE),
          q -> new SpecialOrderItemRequest(V, q, PRICE, null),
          q -> new FulfilLine(V, q),
          q -> new ShortCloseRequest(q, "none left"),
          q -> new SubstituteRequest(V, q, null, "none left"));

  private static final List<Function<BigDecimal, Object>> TILL =
      List.of(
          q -> new OrderItemRequest(V, q, PRICE, null, null, null, null, null),
          q ->
              new ParkSaleRequest(
                  Ids.newId().toString(),
                  null,
                  null,
                  List.of(new ParkedSaleItemRequest(V, q, PRICE, null, null, null)),
                  null),
          // The till builds an exchange's new items with its scanner, as it builds a sale's: a
          // pack's label weight (0.37512) arrives as read (returns_screen.dart, scanBarcode).
          q -> new ExchangeNewItemRequest(V, q, null, null, null, null),
          q ->
              new ExchangeRequest(
                  "wrong size",
                  List.of(new ReturnItemRequest(V, BigDecimal.ONE, "SEALED")),
                  List.of(new ExchangeNewItemRequest(V, q, null, null, null, null)),
                  null,
                  null));

  @Test
  void aTypedQuantityIsThreePlacesAtMost() {
    for (Function<BigDecimal, Object> body : TYPED) {
      taken(body.apply(new BigDecimal("0.375")));
      taken(body.apply(new BigDecimal("2")));
      refused(body.apply(new BigDecimal("0.3755")));
      refused(body.apply(new BigDecimal("0.30000000000000004")));
      refused(body.apply(new BigDecimal("1000000000000000")));
    }
  }

  @Test
  void aTillsQuantityMayArriveAsItsDoubleButNoFiner() {
    for (Function<BigDecimal, Object> body : TILL) {
      taken(body.apply(new BigDecimal("0.375")));
      // Taken at the door; the service counts it as 0.300 (Quantities.fromTill).
      taken(body.apply(new BigDecimal("0.30000000000000004")));
      // A label's weight to five places (GS1 AI 3105): the service counts it at the gram below.
      taken(body.apply(new BigDecimal("0.37512")));
      refused(body.apply(new BigDecimal("1E-21")));
      refused(body.apply(new BigDecimal("1000000000000000")));
    }
  }

  @Test
  void anAbsurdExponentCostsNothingToRefuse() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(2),
        () -> {
          for (Function<BigDecimal, Object> body : TYPED)
            refused(body.apply(new BigDecimal("1E-80000000")));
          for (Function<BigDecimal, Object> body : TILL)
            refused(body.apply(new BigDecimal("1E-80000000")));
        });
  }

  /** A parked basket's lines are validated at all now: before, nothing below the list was. */
  @Test
  void aParkedBasketsLinesAreValidated() {
    refused(
        new ParkSaleRequest(
            Ids.newId().toString(),
            null,
            null,
            List.of(new ParkedSaleItemRequest(V, null, PRICE, null, null, null)),
            null));
    refused(
        new ParkSaleRequest(
            Ids.newId().toString(),
            null,
            null,
            List.of(new ParkedSaleItemRequest(V, new BigDecimal("-1"), PRICE, null, null, null)),
            null));
  }

  /** An exchange's new items are each required: a null one is a 400, not a 500 further in. */
  @Test
  void anExchangesNewItemsAreEachRequired() {
    java.util.List<ExchangeNewItemRequest> withAHole = new java.util.ArrayList<>();
    withAHole.add(null);
    refused(
        new ExchangeRequest(
            "wrong size",
            List.of(new ReturnItemRequest(V, BigDecimal.ONE, "SEALED")),
            withAHole,
            null,
            null));
  }
}
