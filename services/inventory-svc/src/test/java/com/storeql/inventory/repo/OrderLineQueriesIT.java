package com.storeql.inventory.repo;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.ids.Ids;
import com.storeql.inventory.RecordingDataSource;
import com.storeql.inventory.domain.Domain.Batch;
import com.storeql.inventory.domain.Domain.MoveOrder;
import com.storeql.inventory.domain.Domain.MoveOrderLine;
import com.storeql.inventory.domain.Domain.TransferOrder;
import com.storeql.inventory.domain.Domain.TransferOrderLine;
import com.storeql.service.OutboxRow;
import com.storeql.test.PostgresSupport;
import com.storeql.web.ApiException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * The lines of a move order and of a transfer order are read and written the way every tenant-owned
 * row is: {@code tenant_id} is the first condition of the statement, and the tenant-led index of
 * the table serves it. The statements are the ones the repository really prepares (read off its
 * connection, never copied here); a row of another business that names our order is neither read,
 * drawn, shipped nor received by what we do to ours.
 */
class OrderLineQueriesIT {

  private static final String SCHEMA = "inventory";

  private static final PostgresSupport PG = PostgresSupport.start();

  static {
    Flyway.configure()
        .dataSource(PG.jdbcUrl(), PG.username(), PG.password())
        .locations("classpath:db/migration")
        .schemas(SCHEMA)
        .defaultSchema(SCHEMA)
        .createSchemas(true)
        .load()
        .migrate();
  }

  /** The repository over a data source of this test's own: nothing else of the service is up. */
  private static final class Repo extends InventoryRepository {
    Repo(DataSource ds) {
      this.dataSource = ds;
      this.expiryDay = ExpiryDay.forTest(tenant -> Map.of(), Clock.systemUTC(), Runnable::run);
    }
  }

  /** The network's reads of what is on its way, over the same recording data source. */
  private static final class Network extends NetworkRepository {
    Network(DataSource ds) {
      this.dataSource = ds;
    }
  }

  @AfterAll
  static void stop() {
    PG.stop();
  }

  private static DataSource dataSource() {
    PGSimpleDataSource ds = new PGSimpleDataSource();
    ds.setUrl(PG.jdbcUrl());
    ds.setUser(PG.username());
    ds.setPassword(PG.password());
    ds.setCurrentSchema(SCHEMA);
    return ds;
  }

  // ── harness ────────────────────────────────────────────────────────────────

  private static final UUID T = Ids.newId();
  private static final UUID OTHER_T = Ids.newId();

  private static BigDecimal n(String v) {
    return new BigDecimal(v);
  }

  private static OutboxRow event(UUID tenant, UUID aggregate) {
    return new OutboxRow("Probe", "storeql.inventory.probe", tenant, aggregate, "{}");
  }

  private static void stock(Repo repo, UUID tenant, UUID store, UUID variant, String qty) {
    UUID id = Ids.newId();
    Batch batch =
        new Batch(
            id,
            tenant,
            store,
            variant,
            "L-" + Ids.shortRef(id),
            n(qty),
            n(qty),
            n("1.00"),
            null,
            Instant.now(),
            Batch.STATUS_ACTIVE,
            Batch.MATERIAL_AVAILABLE,
            null,
            null,
            null);
    repo.receive(batch, "MANUAL", null, event(tenant, id), null);
  }

  private static MoveOrder moveOrder(Repo repo, UUID store, UUID other, UUID variant, String qty) {
    MoveOrder order =
        new MoveOrder(
            Ids.newId(),
            T,
            store,
            other,
            null,
            null,
            null,
            MoveOrder.DRAFT,
            Instant.now(),
            null,
            null,
            null);
    repo.createMoveOrder(
        order, List.of(new MoveOrderLine(Ids.newId(), T, order.id(), variant, n(qty), null)));
    return order;
  }

  private static TransferOrder transfer(
      Repo repo, String type, UUID from, UUID to, UUID variant, String qty) {
    TransferOrder order =
        new TransferOrder(
            Ids.newId(),
            T,
            from,
            to,
            type,
            TransferOrder.PENDING,
            null,
            Instant.now(),
            null,
            null,
            TransferOrder.SOURCE_MANUAL,
            null);
    repo.createTransferOrder(
        order,
        List.of(new TransferOrderLine(Ids.newId(), T, order.id(), variant, n(qty), null, null)));
    return order;
  }

  private static void exec(String sql) throws SQLException {
    try (Connection c = dataSource().getConnection();
        Statement st = c.createStatement()) {
      st.execute(sql);
    }
  }

  private static String scalar(String sql) throws SQLException {
    try (Connection c = dataSource().getConnection();
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      if (!rs.next()) return null;
      Object v = rs.getObject(1);
      return v == null ? null : v.toString();
    }
  }

  /** A row of another business's, naming our move order, as no request could ever make one. */
  private static UUID foreignMoveLine(UUID order, UUID variant, String qty) throws SQLException {
    UUID id = Ids.newId();
    exec(
        "INSERT INTO move_order_lines (id, tenant_id, move_order_id, variant_id, requested_qty)"
            + " VALUES ('"
            + id
            + "','"
            + OTHER_T
            + "','"
            + order
            + "','"
            + variant
            + "',"
            + qty
            + ")");
    return id;
  }

  /** A row of another business's, naming our transfer order. */
  private static UUID foreignTransferLine(
      UUID order, UUID variant, String requested, String shipped) throws SQLException {
    UUID id = Ids.newId();
    exec(
        "INSERT INTO transfer_order_lines (id, tenant_id, transfer_order_id, variant_id,"
            + " requested_qty, shipped_qty) VALUES ('"
            + id
            + "','"
            + OTHER_T
            + "','"
            + order
            + "','"
            + variant
            + "',"
            + requested
            + ","
            + shipped
            + ")");
    return id;
  }

  private static String moveLineColumn(UUID line, String column) throws SQLException {
    return scalar("SELECT " + column + " FROM move_order_lines WHERE id = '" + line + "'");
  }

  private static String transferLineColumn(UUID line, String column) throws SQLException {
    return scalar("SELECT " + column + " FROM transfer_order_lines WHERE id = '" + line + "'");
  }

  /** The plan of {@code sql} with every {@code ?} a uuid, and sequential and bitmap scans off. */
  private static String plan(String sql) throws SQLException {
    try (Connection c = dataSource().getConnection();
        Statement st = c.createStatement()) {
      st.execute("SET enable_seqscan = off");
      st.execute("SET enable_bitmapscan = off");
      StringBuilder out = new StringBuilder();
      try (PreparedStatement ps = c.prepareStatement("EXPLAIN " + sql)) {
        int params = (int) sql.chars().filter(ch -> ch == '?').count();
        for (int i = 1; i <= params; i++) ps.setObject(i, Ids.newId());
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) out.append(rs.getString(1)).append('\n');
        }
      }
      return out.toString();
    }
  }

  // ── the statements ────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "Every read and write of a move or transfer order's lines leads with tenant_id, and the"
          + " tenant-led index serves it")
  void everyLineStatementLeadsWithTheTenantAndRidesItsIndex() throws SQLException {
    List<String> prepared = new CopyOnWriteArrayList<>();
    Repo repo = new Repo(RecordingDataSource.of(dataSource(), prepared));
    UUID a = Ids.newId();
    UUID b = Ids.newId();
    UUID v = Ids.newId();
    stock(repo, T, a, v, "100");

    MoveOrder mo = moveOrder(repo, a, b, v, "4");
    assertThat(repo.listMoveOrderLines(T, mo.id()).size(), is(1));
    repo.pickMoveOrder(T, mo.id(), event(T, mo.id()));

    TransferOrder inTransit = transfer(repo, TransferOrder.TYPE_INTRANSIT, a, b, v, "5");
    repo.shipTransferOrder(T, inTransit.id(), event(T, inTransit.id()));
    repo.receiveTransferOrder(T, inTransit.id(), event(T, inTransit.id()));
    TransferOrder direct = transfer(repo, TransferOrder.TYPE_DIRECT, a, b, v, "6");
    repo.shipTransferOrder(T, direct.id(), event(T, direct.id()));
    transfer(repo, TransferOrder.TYPE_INTRANSIT, a, b, v, "2");
    Network network = new Network(RecordingDataSource.of(dataSource(), prepared));
    network.inboundByVariant(T, b);
    network.committedByVariant(T, a);

    Set<String> statements = new LinkedHashSet<>();
    for (String sql : prepared) {
      String flat = sql.replaceAll("\\s+", " ").trim();
      boolean lines = flat.contains("move_order_lines") || flat.contains("transfer_order_lines");
      if (lines && !flat.startsWith("INSERT INTO")) statements.add(flat);
    }

    // Not vacuous: the read of each table, and every update the flows make.
    assertThat(
        statements.stream()
            .filter(s -> s.startsWith("SELECT") && s.contains("move_order_lines"))
            .count(),
        is(1L));
    assertThat(
        statements.stream()
            .filter(s -> s.startsWith("UPDATE move_order_lines SET picked_qty"))
            .count(),
        is(1L));
    assertThat(
        statements.stream().filter(s -> s.contains("FROM transfer_order_lines WHERE")).count(),
        is(1L));
    assertThat(
        statements.stream().filter(s -> s.startsWith("UPDATE transfer_order_lines")).count(),
        is(3L));

    // The network's two reads join the lines to their orders on the tenant and the order, and lead
    // with the order's tenant; the join is what reaches the lines.
    List<String> joins = statements.stream().filter(sql -> sql.contains(" JOIN ")).toList();
    assertThat(joins.size(), is(2));
    for (String sql : joins) {
      assertThat(sql, sql, matchesPattern("(?is).*\\bWHERE o\\.tenant_id = \\?.*"));
      assertThat(
          sql, sql, containsString("ON o.tenant_id = l.tenant_id AND o.id = l.transfer_order_id"));
      String plan = plan(sql);
      assertThat(sql + "\n" + plan, plan, containsString("idx_transfer_order_lines_tenant"));
      assertThat(sql + "\n" + plan, plan, not(containsString("Seq Scan")));
    }

    for (String sql : statements) {
      if (joins.contains(sql)) continue;
      assertThat(sql, sql, matchesPattern("(?is).*\\bWHERE tenant_id = \\?.*"));
      String index =
          sql.contains("move_order_lines")
              ? "idx_move_order_lines_tenant"
              : "idx_transfer_order_lines_tenant";
      String plan = plan(sql);
      assertThat(sql + "\n" + plan, plan, containsString(index));
      assertThat(sql + "\n" + plan, plan, not(containsString("Seq Scan")));
    }
  }

  @Test
  @DisplayName("Each lines table keeps its primary key and the one tenant-led lookup index")
  void eachLinesTableKeepsTheTenantLedIndexOnly() throws SQLException {
    for (String[] table :
        new String[][] {
          {"move_order_lines", "idx_move_order_lines_tenant", "(tenant_id, move_order_id)"},
          {
            "transfer_order_lines",
            "idx_transfer_order_lines_tenant",
            "(tenant_id, transfer_order_id)"
          }
        }) {
      List<String> found = new ArrayList<>();
      try (Connection c = dataSource().getConnection();
          Statement st = c.createStatement();
          ResultSet rs =
              st.executeQuery(
                  "SELECT indexname || ' ' || indexdef FROM pg_indexes WHERE schemaname = '"
                      + SCHEMA
                      + "' AND tablename = '"
                      + table[0]
                      + "' ORDER BY indexname")) {
        while (rs.next()) found.add(rs.getString(1));
      }
      assertThat(found.toString(), found.size(), is(2));
      assertThat(found.toString(), found.get(0), containsString(table[1] + " ON"));
      assertThat(found.toString(), found.get(0), containsString(table[2]));
      assertThat(found.toString(), found.get(1), containsString("pk_" + table[0]));
    }
  }

  // ── a row of another business ─────────────────────────────────────────────

  @Test
  @DisplayName(
      "A line of another business that names our move order is not read, not drawn, not picked")
  void aForeignMoveLineIsNeitherReadNorDrawnNorPicked() throws SQLException {
    Repo repo = new Repo(dataSource());
    UUID a = Ids.newId();
    UUID b = Ids.newId();
    UUID ours = Ids.newId();
    UUID theirs = Ids.newId();
    stock(repo, T, a, ours, "10");
    stock(repo, T, a, theirs, "20");
    MoveOrder mo = moveOrder(repo, a, b, ours, "4");
    UUID foreign = foreignMoveLine(mo.id(), theirs, "7");

    List<MoveOrderLine> read = repo.listMoveOrderLines(T, mo.id());
    assertThat(read.size(), is(1));
    assertThat(read.get(0).variantId(), is(ours));
    assertThat(repo.listMoveOrderLines(OTHER_T, mo.id()).size(), is(1));
    assertThat(repo.listMoveOrderLines(Ids.newId(), mo.id()).size(), is(0));

    MoveOrder picked = repo.pickMoveOrder(T, mo.id(), event(T, mo.id()));
    assertThat(picked.status(), is(MoveOrder.COMPLETED));

    assertThat(moveLineColumn(foreign, "picked_qty"), is(nullValue()));
    assertThat(
        "what the foreign line asked for was never drawn from our shelf",
        scalar(
            "SELECT sum(remaining_qty) FROM inventory_batches WHERE tenant_id = '"
                + T
                + "' AND store_id = '"
                + a
                + "' AND variant_id = '"
                + theirs
                + "'"),
        is("20.000"));
    assertThat(
        scalar(
            "SELECT sum(picked_qty) FROM move_order_lines WHERE tenant_id = '"
                + T
                + "' AND move_order_id = '"
                + mo.id()
                + "'"),
        is("4.000"));
  }

  @Test
  @DisplayName(
      "Another business's staff cannot pick our move order at the repository either: 404, and"
          + " nothing moves")
  void anotherBusinessCannotPickOurMoveOrder() throws SQLException {
    Repo repo = new Repo(dataSource());
    UUID a = Ids.newId();
    UUID b = Ids.newId();
    UUID v = Ids.newId();
    stock(repo, T, a, v, "10");
    MoveOrder mo = moveOrder(repo, a, b, v, "4");

    ApiException refused =
        org.junit.jupiter.api.Assertions.assertThrows(
            ApiException.class,
            () -> repo.pickMoveOrder(OTHER_T, mo.id(), event(OTHER_T, mo.id())));
    assertThat(refused.status(), is(404));
    assertThat(
        scalar(
            "SELECT status FROM move_orders WHERE tenant_id = '"
                + T
                + "' AND id = '"
                + mo.id()
                + "'"),
        is("DRAFT"));
    assertThat(
        scalar(
            "SELECT picked_qty FROM move_order_lines WHERE tenant_id = '"
                + T
                + "' AND move_order_id = '"
                + mo.id()
                + "'"),
        is(nullValue()));
  }

  @Test
  @DisplayName(
      "A line of another business that names our transfer is not shipped and not received with it")
  void aForeignTransferLineIsNeitherShippedNorReceived() throws SQLException {
    Repo repo = new Repo(dataSource());
    UUID a = Ids.newId();
    UUID b = Ids.newId();
    UUID v = Ids.newId();
    UUID theirs = Ids.newId();
    stock(repo, T, a, v, "50");

    TransferOrder inTransit = transfer(repo, TransferOrder.TYPE_INTRANSIT, a, b, v, "5");
    UUID foreign = foreignTransferLine(inTransit.id(), theirs, "9", "3");
    repo.shipTransferOrder(T, inTransit.id(), event(T, inTransit.id()));
    assertThat(transferLineColumn(foreign, "shipped_qty"), is("3.000"));
    assertThat(transferLineColumn(foreign, "received_qty"), is(nullValue()));
    repo.receiveTransferOrder(T, inTransit.id(), event(T, inTransit.id()));
    assertThat(transferLineColumn(foreign, "shipped_qty"), is("3.000"));
    assertThat(transferLineColumn(foreign, "received_qty"), is(nullValue()));
    assertThat(
        "our own line went through both steps",
        scalar(
            "SELECT shipped_qty || '/' || received_qty FROM transfer_order_lines WHERE tenant_id"
                + " = '"
                + T
                + "' AND transfer_order_id = '"
                + inTransit.id()
                + "'"),
        is("5.000/5.000"));

    TransferOrder direct = transfer(repo, TransferOrder.TYPE_DIRECT, a, b, v, "6");
    UUID foreignDirect = foreignTransferLine(direct.id(), theirs, "9", "NULL");
    repo.shipTransferOrder(T, direct.id(), event(T, direct.id()));
    assertThat(transferLineColumn(foreignDirect, "shipped_qty"), is(nullValue()));
    assertThat(transferLineColumn(foreignDirect, "received_qty"), is(nullValue()));
    assertThat(
        "our own line shipped and arrived at once",
        scalar(
            "SELECT shipped_qty || '/' || received_qty FROM transfer_order_lines WHERE tenant_id"
                + " = '"
                + T
                + "' AND transfer_order_id = '"
                + direct.id()
                + "'"),
        is("6.000/6.000"));
    assertThat(
        "a line that is not ours is never read as ours",
        repo.listTransferOrderLines(T, direct.id()).stream()
            .map(TransferOrderLine::variantId)
            .toList(),
        is(List.of(v)));
    assertThat(
        "and the other business reads only its own",
        repo.listTransferOrderLines(OTHER_T, direct.id()).stream()
            .map(TransferOrderLine::variantId)
            .toList(),
        is(List.of(theirs)));
  }
}
