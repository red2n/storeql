package com.storeql.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.payment.domain.CardSettlement;
import com.storeql.payment.domain.Domain.PaymentTender;
import com.storeql.payment.service.PaymentService;
import com.storeql.test.PostgresSupport;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Two promises the payment schema makes, read from the schema payment-svc migrates: the method of a
 * tender or a refund is one of the closed set the code writes (and the database says so), and every
 * table that holds a business's rows can be read by that business without scanning the table.
 */
class PaymentSchemaGuardsIT {

  private static final PostgresSupport PG =
      PostgresSupport.start().migrate("classpath:db/migration");

  /**
   * Every method any path writes to {@code payment_tenders} or {@code refund_tenders}: a payment
   * taken at the till or online, a refund under the method a person names or the tender's own, a
   * card payment given back another way (CASH, CARD, UPI or WALLET: {@code
   * TerminalSettlementIT.aCardGivenBackAnotherWayIsARefundUnderThatWay}), a store credit or a gift
   * card issued by a return, and the exchange tender and its refund.
   */
  private static final Set<String> METHODS =
      Set.of("CASH", "CARD", "UPI", "WALLET", "GIFT_CARD", "VOUCHER", "STORE_CREDIT", "EXCHANGE");

  @AfterAll
  static void stop() {
    PG.stop();
  }

  // ── the methods a tender or a refund may carry ─────────────────────────────

  /** What the code names, read from the code: the constants and the sets that validate input. */
  @SuppressWarnings("unchecked")
  private static Set<String> methodsTheCodeNames() throws ReflectiveOperationException {
    Set<String> names = new TreeSet<>();
    for (Field f : PaymentTender.class.getFields()) {
      if (Modifier.isStatic(f.getModifiers()) && f.getName().startsWith("METHOD_")) {
        names.add((String) f.get(null));
      }
    }
    names.add(PaymentService.METHOD_EXCHANGE);
    // What a till or a back office may send, and what a card refund closed another way may name.
    Field valid = PaymentService.class.getDeclaredField("VALID_METHODS");
    valid.setAccessible(true);
    names.addAll((Set<String>) valid.get(null));
    names.addAll(CardSettlement.ANOTHER_WAYS);
    return names;
  }

  private static void tender(String method) throws SQLException {
    try (Connection c = PG.dataSource().getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO payment_tenders (id, tenant_id, order_id, amount, method)"
                    + " VALUES (?,?,?,?,?)")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, Ids.newId());
      ps.setObject(3, Ids.newId());
      ps.setBigDecimal(4, new BigDecimal("1.00"));
      ps.setString(5, method);
      ps.executeUpdate();
    }
  }

  private static void refund(String method) throws SQLException {
    try (Connection c = PG.dataSource().getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO refund_tenders (id, tenant_id, order_id, payment_id, amount, method)"
                    + " VALUES (?,?,?,?,?,?)")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, Ids.newId());
      ps.setObject(3, Ids.newId());
      ps.setObject(4, Ids.newId());
      ps.setBigDecimal(5, new BigDecimal("1.00"));
      ps.setString(6, method);
      ps.executeUpdate();
    }
  }

  private static String constraintDefinition(String name) throws SQLException {
    try (Connection c = PG.dataSource().getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?")) {
      ps.setString(1, name);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    }
  }

  private static Set<String> literals(String definition) {
    Set<String> out = new TreeSet<>();
    Matcher m = Pattern.compile("'([^']*)'").matcher(definition);
    while (m.find()) out.add(m.group(1));
    return out;
  }

  @Test
  @DisplayName("The database's set of methods is exactly the set the code writes, no more")
  void theCheckNamesExactlyTheMethodsTheCodeWrites() throws Exception {
    // The set is the code's: a method added there without being added to the check fails here, and
    // a method left in the check that nothing writes any more fails here too.
    assertEquals(new TreeSet<>(METHODS), methodsTheCodeNames());

    for (String check : new String[] {"chk_payment_tenders_method", "chk_refund_tenders_method"}) {
      String definition = constraintDefinition(check);
      assertNotNull(definition, check + " does not exist");
      assertEquals(new TreeSet<>(METHODS), literals(definition), check + ": " + definition);
    }
  }

  @Test
  @DisplayName("Every method the code writes is taken by a tender and by a refund")
  void everyMethodTheCodeWritesIsAccepted() throws Exception {
    for (String method : METHODS) {
      tender(method);
      refund(method);
    }
  }

  @Test
  @DisplayName("A method outside the set is refused by the database, for a tender and a refund")
  void aMethodOutsideTheSetIsRefused() {
    // 'ORIGINAL' is what a return announces about where the value goes, never what a refund row
    // is written under; lower case is what a client sends before the service upper-cases it.
    for (String method : new String[] {"BITCOIN", "ORIGINAL", "cash", "", "CASH "}) {
      SQLException t = assertThrows(SQLException.class, () -> tender(method), "tender " + method);
      assertEquals("23514", t.getSQLState(), t.getMessage());
      assertTrue(t.getMessage().contains("chk_payment_tenders_method"), t.getMessage());
      SQLException r = assertThrows(SQLException.class, () -> refund(method), "refund " + method);
      assertEquals("23514", r.getSQLState(), r.getMessage());
      assertTrue(r.getMessage().contains("chk_refund_tenders_method"), r.getMessage());
    }
  }

  // ── a business's rows are found by its own id ──────────────────────────────

  /** The tables whose key is the row's id alone: each still has an index that leads with tenant. */
  private static final List<String> KEYED_BY_ID_ALONE =
      List.of(
          "card_terminals",
          "terminal_payments",
          "terminal_attempt_decisions",
          "card_refund_dues",
          "given_up_orders",
          "card_refund_due_closures");

  @Test
  @DisplayName(
      "Every table with a tenant_id has an index that leads with it, but the outbox, which is"
          + " cross-tenant on purpose")
  void everyTenantTableIsReadByItsTenantFirst() throws SQLException {
    List<String> without = new ArrayList<>();
    List<String> keyedByIdAlone = new ArrayList<>();
    try (Connection c = PG.dataSource().getConnection();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT t.relname,"
                    + " EXISTS (SELECT 1 FROM pg_index i WHERE i.indrelid = t.oid AND i.indisprimary"
                    + "   AND i.indnatts = 1 AND i.indkey[0] = (SELECT attnum FROM pg_attribute"
                    + "     WHERE attrelid = t.oid AND attname = 'id')) AS keyed_by_id_alone,"
                    + " EXISTS (SELECT 1 FROM pg_index i JOIN pg_attribute ia"
                    + "   ON ia.attrelid = i.indrelid AND ia.attnum = i.indkey[0]"
                    + "   WHERE i.indrelid = t.oid AND ia.attname = 'tenant_id') AS tenant_led"
                    + " FROM pg_class t JOIN pg_namespace n ON n.oid = t.relnamespace"
                    + " WHERE n.nspname = 'public' AND t.relkind = 'r'"
                    + " AND EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = t.oid"
                    + "   AND a.attname = 'tenant_id' AND NOT a.attisdropped)"
                    + " ORDER BY 1")) {
      while (rs.next()) {
        if (rs.getBoolean("keyed_by_id_alone")) keyedByIdAlone.add(rs.getString("relname"));
        if (!rs.getBoolean("tenant_led")) without.add(rs.getString("relname"));
      }
    }
    // The outbox is drained by one relay across every business, oldest first, and no statement on
    // it filters by tenant: it has no tenant-led index by design, and tenant_id is not a lookup key
    // on its rows (null for some).
    assertEquals(List.of("outbox"), without, "tables with a tenant_id and no index led by it");
    // Six tables keep their own id as the whole key; each is reached by its tenant through an
    // index.
    assertEquals(
        new TreeSet<>(KEYED_BY_ID_ALONE),
        new TreeSet<>(keyedByIdAlone.stream().filter(t -> !t.equals("outbox")).toList()));
  }
}
