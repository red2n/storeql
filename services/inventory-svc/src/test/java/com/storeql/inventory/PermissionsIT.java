package com.storeql.inventory;

import com.storeql.test.PermissionGate;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.WebTarget;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Granular permissions (20.10) at this service's decision points, driven by {@link PermissionGate}:
 * the tier gate by path still admits a MANAGER; the permission check then refuses one whose custom
 * role was narrowed out of the decision, with the permission named, before any argument is looked
 * at; a token carrying no claim is judged by the tier's defaults as before.
 */
@HelidonTest
class PermissionsIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("inventory");

  private static final String T = "01a090ae-611e-702c-a97b-d1b8025478e1";
  private static final String USER = "01a090ae-611e-700b-bde4-50df0324c37c";
  private static final String ID = "01a090ae-611e-703c-a378-a4972ea461c8";

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private PermissionGate gate() {
    return new PermissionGate(target, T, USER);
  }

  @Test
  @DisplayName("Adjusting stock needs stock.adjust; a storekeeper narrowed out of it cannot")
  void stockAdjustmentIsGated() {
    String body =
        "{\"storeId\":\""
            + ID
            + "\",\"variantId\":\""
            + ID
            + "\",\"delta\":-1,\"reason\":\"DAMAGED\"}";
    gate().assertGated("POST", "/admin/inventory/adjust", body, "stock.adjust");
    gate().assertTierNarrows("POST", "/admin/inventory/adjust", body, "STOREKEEPER");
  }

  /**
   * SJ-D73: a cashier could raise a transfer to another store and ship it. Moving stock is
   * stock.transfer, held by a storekeeper and never by the till.
   */
  @Test
  @DisplayName("Raising a transfer needs stock.transfer; a storekeeper narrowed out of it cannot")
  void transfersAreGated() {
    String body =
        "{\"fromStoreId\":\""
            + ID
            + "\",\"toStoreId\":\""
            + USER
            + "\",\"transferType\":\"DIRECT\",\"lines\":[{\"variantId\":\""
            + ID
            + "\",\"requestedQty\":1}]}";
    gate().assertGated("POST", "/admin/inventory/transfers", body, "stock.transfer");
    gate().assertTierNarrows("POST", "/admin/inventory/transfers", body, "STOREKEEPER");
    gate().assertTierRefused("POST", "/admin/inventory/transfers", body, "CASHIER");
    gate().assertGated("POST", "/admin/inventory/transfers/" + ID + "/ship", "", "stock.transfer");
  }

  @Test
  @DisplayName("Raising a move order needs stock.transfer too")
  void moveOrdersAreGated() {
    String body =
        "{\"fromStoreId\":\""
            + ID
            + "\",\"toStoreId\":\""
            + ID
            + "\",\"lines\":[{\"variantId\":\""
            + ID
            + "\",\"requestedQty\":1}]}";
    gate().assertGated("POST", "/admin/inventory/move-orders", body, "stock.transfer");
    gate().assertTierRefused("POST", "/admin/inventory/move-orders", body, "CASHIER");
  }
}
