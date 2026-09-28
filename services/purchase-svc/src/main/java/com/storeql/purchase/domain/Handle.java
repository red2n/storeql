package com.storeql.purchase.domain;

import com.storeql.ids.Ids;
import java.util.UUID;

/**
 * How text people read names a document: "#" and the id's handle, as the screens show it — "PO
 * #0d72a43e", "sale #0d72a43e", "receipt #0d72a43e".
 *
 * <p>Every journal's description is written this way. It is read in the accounting package and on
 * the Integrations screen, where a whole id can be neither read nor matched by eye against the
 * order a person has in front of them; so a description names what it is about by handle, and never
 * by id. Like any handle it is not a key — two ids can share one — so nothing is ever looked up by
 * it: the journal's source reference still carries the id.
 */
public final class Handle {

  private Handle() {}

  /**
   * The handle people see for a document.
   *
   * @param id the document's id
   * @return "#" and the id's last {@value Ids#SHORT_REF_LENGTH} hex digits, lowercase
   * @throws NullPointerException when there is no id: no document, no handle
   */
  public static String of(UUID id) {
    return "#" + Ids.shortRef(id);
  }

  /**
   * A purchase order as the procurement screen names it. An order has no separate number, so this
   * is its number.
   *
   * @param id the order's id
   * @return "PO #" and the id's handle
   */
  public static String purchaseOrder(UUID id) {
    return "PO " + of(id);
  }
}
