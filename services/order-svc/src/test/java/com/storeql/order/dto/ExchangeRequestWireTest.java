package com.storeql.order.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.order.dto.Dtos.ExchangeNewItemRequest;
import com.storeql.order.dto.Dtos.ExchangeRequest;
import com.storeql.web.V7JsonbProvider;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.annotation.JsonbCreator;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * An exchange's body as the service reads it: through the JSON-B every service binds request bodies
 * with ({@link V7JsonbProvider}, Yasson). Yasson builds a record through its canonical constructor
 * only when the record has exactly one, so a second, convenience constructor on a body record stops
 * every body of that shape from binding (400 {@code REQUEST_BODY_INVALID} before the service is
 * reached) — which a test that builds the record in Java never sees.
 */
class ExchangeRequestWireTest {

  private static final Jsonb JSON = new V7JsonbProvider().getContext(ExchangeRequest.class);
  private static final String VARIANT = "0198a000-0000-7000-8000-000000000002";
  private static final String OTHER = "0198a000-0000-7000-8000-000000000003";
  private static final String STICKER = "0198a000-0000-7000-8000-000000000004";
  private static final String SCALE = "0198a000-0000-7000-8000-000000000005";

  @Test
  void aNewItemThatSaysNothingOfItsPackBinds() {
    String body =
        "{\"reason\":\"wrong size\",\"returnItems\":[{\"variantId\":\""
            + VARIANT
            + "\",\"qty\":1,\"condition\":\"SEALED\"}],\"newItems\":[{\"variantId\":\""
            + OTHER
            + "\",\"qty\":1}]}";

    ExchangeRequest req = JSON.fromJson(body, ExchangeRequest.class);

    ExchangeNewItemRequest bought = req.newItems().get(0);
    assertEquals(OTHER, bought.variantId());
    assertEquals(0, BigDecimal.ONE.compareTo(bought.qty()));
    assertNull(bought.batchNo());
    assertNull(bought.expiry());
    assertNull(bought.markdownId());
    assertNull(bought.weighingInstrumentId());
    assertEquals("SEALED", req.returnItems().get(0).condition());
  }

  @Test
  void aNewItemCarriesWhatItsPackSaidOfItself() {
    String body =
        "{\"reason\":\"swap\",\"returnItems\":[{\"variantId\":\""
            + VARIANT
            + "\",\"qty\":1,\"condition\":\"SEALED\"}],\"newItems\":[{\"variantId\":\""
            + OTHER
            + "\",\"qty\":0.37512,\"weighingInstrumentId\":\""
            + SCALE
            + "\",\"markdownId\":\""
            + STICKER
            + "\",\"batchNo\":\"L-42\",\"expiry\":\"2026-11-30\"}]}";

    ExchangeNewItemRequest bought = JSON.fromJson(body, ExchangeRequest.class).newItems().get(0);

    assertEquals(0, new BigDecimal("0.37512").compareTo(bought.qty()));
    assertEquals(SCALE, bought.weighingInstrumentId());
    assertEquals(STICKER, bought.markdownId());
    assertEquals("L-42", bought.batchNo());
    assertEquals("2026-11-30", bought.expiry());
  }

  /**
   * Every record the order service reads from a body or writes to one can be built by JSON-B:
   * exactly one constructor, or one marked {@link JsonbCreator}. A second constructor is how the
   * exchange went down once.
   */
  @Test
  void everyDtoRecordHasOneConstructorJsonbCanUse() {
    List<String> unbindable = new ArrayList<>();
    for (Class<?> holder :
        List.of(
            Dtos.class,
            CommissionDtos.class,
            EInvoiceTransportDtos.class,
            EReportingDtos.class,
            RecallNoticeDtos.class,
            SalesInvoiceDtos.class)) {
      for (Class<?> c : nestedRecords(holder)) {
        Constructor<?>[] ctors = c.getDeclaredConstructors();
        boolean marked =
            Arrays.stream(ctors).anyMatch(k -> k.isAnnotationPresent(JsonbCreator.class));
        if (ctors.length != 1 && !marked) unbindable.add(c.getName());
      }
    }
    assertTrue(unbindable.isEmpty(), "records JSON-B cannot build: " + unbindable);
  }

  private static List<Class<?>> nestedRecords(Class<?> holder) {
    List<Class<?>> found = new ArrayList<>();
    if (holder.isRecord()) found.add(holder);
    for (Class<?> inner : holder.getDeclaredClasses()) found.addAll(nestedRecords(inner));
    return found;
  }
}
