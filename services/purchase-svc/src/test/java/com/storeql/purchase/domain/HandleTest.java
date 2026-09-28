package com.storeql.purchase.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** How a journal's description names a document: "#" and the last eight of its id. */
class HandleTest {

  private static final UUID ID = Ids.parse("01a0905d-7082-7518-9ec6-aee90d72a43e");

  @Test
  @DisplayName("A handle is '#' and the id's last eight hex digits — never the whole id")
  void aHandleIsTheTailOfTheId() {
    assertThat(Handle.of(ID), is("#0d72a43e"));
    assertThat(Handle.of(ID), not(containsString(ID.toString())));
    // Never the front: every v7 id minted in the same minute starts the same way.
    assertThat(Handle.of(ID), not(containsString("01a0905d")));
  }

  @Test
  @DisplayName("A purchase order is 'PO #' and its handle, as the procurement screen shows it")
  void aPurchaseOrderIsNamedAsTheProcurementScreenNamesIt() {
    assertThat(Handle.purchaseOrder(ID), is("PO #0d72a43e"));
  }

  @Test
  @DisplayName("There is no handle for no document")
  void noIdNoHandle() {
    assertThrows(NullPointerException.class, () -> Handle.of(null));
  }
}
