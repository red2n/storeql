package com.storeql.customer.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeql.customer.domain.Domain.Customer;
import com.storeql.customer.domain.Domain.StoreCreditAccount;
import com.storeql.ids.Ids;
import com.storeql.service.OutboxRow;
import com.storeql.web.ApiException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A store-credit spend is once per order because the order's earlier spend is looked for while the
 * account is held. Two spends for one order (a till's second press, a retry after a timeout) both
 * wait on the account's {@code FOR UPDATE}; the second, let in once the first commits, reads in a
 * new snapshot and finds the first's {@code REDEEM}. Looked for before the lock, both would find
 * nothing, and the second would take the credit again: there is no unique key behind it.
 *
 * <p>The customer's row is read first ({@code FOR SHARE}, so an erasure waits for the spend or the
 * spend sees it), then the account is locked, then the order is looked for: the same order of locks
 * as a manual issue, so the two never deadlock.
 */
class StoreCreditRedeemLockOrderTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID CUSTOMER = Ids.newId();
  private static final UUID ORDER = Ids.newId();

  /** The statements prepared, in order. */
  private final List<String> prepared = new ArrayList<>();

  private Connection conn;
  private CustomerRepository repo;
  private String status;
  private boolean spentForOrder;

  /** The repository over a stand-in connection; {@code dataSource} is the base class's field. */
  private static final class OverConnection extends CustomerRepository {
    OverConnection(DataSource ds) {
      this.dataSource = ds;
    }
  }

  @BeforeEach
  void setUp() throws SQLException {
    conn = mock(Connection.class);
    DataSource ds = mock(DataSource.class);
    when(ds.getConnection()).thenReturn(conn);
    when(conn.prepareStatement(anyString()))
        .thenAnswer(inv -> statement(inv.getArgument(0, String.class)));
    repo = new OverConnection(ds);
    status = Customer.STATUS_ACTIVE;
    spentForOrder = false;
  }

  private PreparedStatement statement(String sql) throws SQLException {
    prepared.add(sql);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(ps.executeQuery()).thenReturn(rs);
    when(ps.executeUpdate()).thenReturn(1);
    if (sql.contains("FROM customers")) {
      when(rs.next()).thenReturn(true);
      when(rs.getString(1)).thenReturn(status);
    } else if (sql.contains("FROM store_credit_ledger")) {
      when(rs.next()).thenReturn(spentForOrder);
    } else if (sql.contains("FROM store_credit_accounts")) {
      when(rs.next()).thenReturn(true);
      OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
      when(rs.getObject("id", UUID.class)).thenReturn(Ids.newId());
      when(rs.getObject("tenant_id", UUID.class)).thenReturn(TENANT);
      when(rs.getObject("customer_id", UUID.class)).thenReturn(CUSTOMER);
      when(rs.getBigDecimal("balance")).thenReturn(new BigDecimal("100.00"));
      when(rs.getString("currency")).thenReturn("GBP");
      when(rs.getObject("created_at", OffsetDateTime.class)).thenReturn(at);
      when(rs.getObject("updated_at", OffsetDateTime.class)).thenReturn(at);
    }
    return ps;
  }

  private StoreCreditAccount redeem() {
    return repo.redeemStoreCredit(
        TENANT,
        CUSTOMER,
        new BigDecimal("30.00"),
        "GBP",
        ORDER,
        "tender",
        new OutboxRow("StoreCreditRedeemed", "t", TENANT, CUSTOMER, "{}"));
  }

  private int first(String fragment) {
    for (int i = 0; i < prepared.size(); i++) {
      if (prepared.get(i).contains(fragment)) {
        return i;
      }
    }
    return -1;
  }

  private int customerRead() {
    return first("FROM customers");
  }

  private int accountLock() {
    for (int i = 0; i < prepared.size(); i++) {
      String sql = prepared.get(i);
      if (sql.contains("FROM store_credit_accounts") && sql.contains("FOR UPDATE")) {
        return i;
      }
    }
    return -1;
  }

  private int orderLookup() {
    return first("FROM store_credit_ledger");
  }

  private void assertLockedInOrder() {
    assertTrue(customerRead() >= 0, "the customer is read: " + prepared);
    assertTrue(accountLock() > customerRead(), "the account is locked after: " + prepared);
    assertTrue(
        orderLookup() > accountLock(), "the order is looked for under the lock: " + prepared);
  }

  private boolean wrote(String fragment) {
    return prepared.stream().anyMatch(sql -> sql.startsWith(fragment));
  }

  @Test
  @DisplayName("The order's earlier spend is looked for only once the account is held")
  void theOrderIsLookedForUnderTheAccountLock() throws SQLException {
    spentForOrder = true;

    redeem();

    assertLockedInOrder();
    assertFalse(wrote("UPDATE store_credit_accounts"), "nothing more is taken: " + prepared);
    assertFalse(wrote("INSERT INTO store_credit_ledger"), "no second REDEEM: " + prepared);
    assertFalse(wrote("INSERT INTO outbox"), "no second event: " + prepared);
    verify(conn).commit();
  }

  @Test
  @DisplayName("A first spend for the order is taken after the same three steps, in that order")
  void aFirstSpendIsTakenUnderTheLock() throws SQLException {
    redeem();

    assertLockedInOrder();
    assertTrue(first("UPDATE store_credit_accounts") > orderLookup(), "taken after: " + prepared);
    assertTrue(first("INSERT INTO store_credit_ledger") > orderLookup(), "one REDEEM: " + prepared);
    verify(conn).commit();
  }

  @Test
  @DisplayName(
      "An erased customer's new spend is refused 409 after the order was looked for under the"
          + " lock, and everything is rolled back")
  void anErasedNewSpendIsRefusedUnderTheLock() throws SQLException {
    status = Customer.STATUS_ANONYMIZED;

    ApiException e = assertThrows(ApiException.class, this::redeem);

    assertEquals(409, e.status());
    assertEquals("CUSTOMER_ANONYMIZED", e.code());
    assertLockedInOrder();
    assertFalse(wrote("UPDATE store_credit_accounts"), "nothing taken: " + prepared);
    assertFalse(wrote("INSERT INTO store_credit_ledger"), "no REDEEM: " + prepared);
    assertFalse(wrote("INSERT INTO outbox"), "no event: " + prepared);
    verify(conn).rollback();
    verify(conn, never()).commit();
  }

  @Test
  @DisplayName(
      "An erased customer's spend made before the erasure answers its retry as it stands,"
          + " nothing more taken")
  void anErasedReplayAnswersAsItStands() throws SQLException {
    status = Customer.STATUS_ANONYMIZED;
    spentForOrder = true;

    StoreCreditAccount answered = redeem();

    assertEquals(0, answered.balance().compareTo(new BigDecimal("100.00")));
    assertLockedInOrder();
    assertFalse(wrote("UPDATE store_credit_accounts"), "nothing more taken: " + prepared);
    assertFalse(wrote("INSERT INTO store_credit_ledger"), "no second REDEEM: " + prepared);
    assertFalse(wrote("INSERT INTO outbox"), "no second event: " + prepared);
    verify(conn).commit();
    verify(conn, never()).rollback();
  }
}
