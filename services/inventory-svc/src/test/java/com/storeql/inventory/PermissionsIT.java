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

  /**
   * A cycle count ends in stock adjustments, so opening one, approving its variances and posting
   * them is stock.adjust, the same as adjusting stock by hand. A cashier could do all three.
   */
  @Test
  @DisplayName("Opening, approving and posting a cycle count needs stock.adjust; a cashier cannot")
  void cycleCountsAreGated() {
    String body = "{\"storeId\":\"" + ID + "\",\"name\":\"Aisle 4\"}";
    gate().assertGated("POST", "/admin/inventory/cycle-counts", body, "stock.adjust");
    gate().assertTierNarrows("POST", "/admin/inventory/cycle-counts", body, "STOREKEEPER");
    gate().assertTierRefused("POST", "/admin/inventory/cycle-counts", body, "CASHIER");
    for (String step : new String[] {"approve", "adjust"}) {
      String path = "/admin/inventory/cycle-counts/" + ID + "/" + step;
      gate().assertGated("POST", path, "", "stock.adjust");
      gate().assertTierRefused("POST", path, "", "CASHIER");
    }
  }

  /**
   * A physical inventory's completion posts the variances, measured from each tag's system
   * quantity, so starting one, adding a tag and completing are stock.adjust too.
   */
  @Test
  @DisplayName("Starting, tagging and completing a physical inventory needs stock.adjust")
  void physicalInventoriesAreGated() {
    String body = "{\"storeId\":\"" + ID + "\",\"notes\":\"Year end\"}";
    gate().assertGated("POST", "/admin/inventory/physical-inventories", body, "stock.adjust");
    gate().assertTierNarrows("POST", "/admin/inventory/physical-inventories", body, "STOREKEEPER");
    gate().assertTierRefused("POST", "/admin/inventory/physical-inventories", body, "CASHIER");
    String tags = "/admin/inventory/physical-inventories/" + ID + "/tags";
    String tag = "{\"variantId\":\"" + ID + "\",\"systemQty\":1}";
    gate().assertGated("POST", tags, tag, "stock.adjust");
    gate().assertTierRefused("POST", tags, tag, "CASHIER");
    String complete = "/admin/inventory/physical-inventories/" + ID + "/complete";
    gate().assertGated("POST", complete, "", "stock.adjust");
    gate().assertTierRefused("POST", complete, "", "CASHIER");
  }

  /**
   * RET-30 / INV-17: writing stock off, returned stock included, is stock.adjust. A cashier holds
   * nothing of the warehouse by default and is refused on the tier alone.
   */
  @Test
  @DisplayName("A cashier cannot write stock off, and it is refused by permission, not just hidden")
  void aCashierCannotWriteStockOff() {
    String body =
        "{\"storeId\":\""
            + ID
            + "\",\"variantId\":\""
            + ID
            + "\",\"delta\":-1,\"reason\":\"DAMAGED\"}";
    gate().assertTierRefused("POST", "/admin/inventory/adjust", body, "CASHIER");
  }

  /** A release from bond crystallises a duty debt: stock work (stock.adjust), never the till's. */
  @Test
  @DisplayName("Releasing bonded stock needs stock.adjust; a cashier cannot")
  void bondReleaseIsGated() {
    String body = "{\"storeId\":\"" + ID + "\",\"variantId\":\"" + ID + "\",\"qty\":1}";
    String path = "/admin/inventory/bond/releases";
    gate().assertGated("POST", path, body, "stock.adjust");
    gate().assertTierNarrows("POST", path, body, "STOREKEEPER");
    gate().assertTierRefused("POST", path, body, "CASHIER");
  }

  /** A breakdown draws the primal and makes new stock: stock.adjust. */
  @Test
  @DisplayName("Recording a yield breakdown needs stock.adjust; a cashier cannot")
  void yieldRunsAreGated() {
    String body =
        "{\"storeId\":\"" + ID + "\",\"templateId\":\"" + ID + "\",\"inputQty\":1,\"outputs\":[]}";
    String path = "/admin/inventory/yield/runs";
    gate().assertGated("POST", path, body, "stock.adjust");
    gate().assertTierNarrows("POST", path, body, "STOREKEEPER");
    gate().assertTierRefused("POST", path, body, "CASHIER");
  }

  /**
   * Depot replenishment: proposing transfers to a warehouse's shops and releasing a proposed one
   * are stock.transfer, so a till cannot start or release a movement of stock between sites.
   */
  @Test
  @DisplayName("Proposing and releasing depot transfers need stock.transfer; a cashier cannot")
  void depotTransfersAreGated() {
    String propose = "/admin/inventory/network/proposals";
    String body = "{\"warehouseId\":\"" + ID + "\"}";
    gate().assertGated("POST", propose, body, "stock.transfer");
    gate().assertTierNarrows("POST", propose, body, "STOREKEEPER");
    gate().assertTierRefused("POST", propose, body, "CASHIER");
    String release = "/admin/inventory/transfers/" + ID + "/release";
    gate().assertGated("POST", release, "", "stock.transfer");
    gate().assertTierNarrows("POST", release, "", "STOREKEEPER");
    gate().assertTierRefused("POST", release, "", "CASHIER");
  }
}
