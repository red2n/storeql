package com.storeql.inventory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.inventory.domain.Recall.Disposition;
import com.storeql.inventory.service.RecallService.RecordStoreAction;
import com.storeql.web.ApiException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A store's recall action is refused for a quantity its record cannot keep as sent ({@code
 * recall_store_actions.qty_found NUMERIC(18,3)}), under the route's own code and before anything is
 * read or written: the service has no repository here, so reaching one would fail otherwise.
 */
class RecallServiceTest {

  private static RecordStoreAction action(String qty) {
    return new RecordStoreAction(
        Ids.newId(),
        Ids.newId(),
        Ids.newId(),
        Ids.newId(),
        new BigDecimal(qty),
        Disposition.DESTROYED,
        true,
        null);
  }

  @Test
  @DisplayName(
      "A quantity found below zero, past three places or past fifteen whole digits is"
          + " RECALL_QTY_INVALID: sixteen digits overflowed the record as a 500")
  void aQuantityTheRecordCannotKeepIsRefused() {
    RecallService service = new RecallService();
    for (String qty : List.of("-1", "1.2345", "1E+15", "1000000000000000", "1E+20", "-0.001")) {
      ApiException e =
          assertThrows(
              ApiException.class, () -> service.recordStoreAction(action(qty), store -> {}), qty);
      assertEquals(400, e.status(), qty);
      assertEquals("RECALL_QTY_INVALID", e.code(), qty);
    }
  }

  @Test
  @DisplayName("The store is asked about before the quantity")
  void theStoreIsAskedFirst() {
    RecallService service = new RecallService();
    ApiException denied = ApiException.forbidden("STORE_ACCESS_DENIED", "not yours");
    ApiException e =
        assertThrows(
            ApiException.class,
            () ->
                service.recordStoreAction(
                    action("1E+20"),
                    store -> {
                      throw denied;
                    }));
    assertEquals(denied, e);
  }
}
