package com.storeql.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.test.PostgresSupport;
import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * The outbox's drain right, taken and given back through PgBouncer in transaction pooling mode, the
 * way every service reaches Postgres.
 *
 * <p>A session-level advisory lock cannot be given back reliably there: the unlock statement may
 * run on a different server connection than the lock did, answers {@code false} with a warning
 * rather than an error, and leaves the lock on the first connection until PgBouncer recycles it (up
 * to its {@code server_lifetime}, 30 to 60 minutes). Every later drain then finds "another replica"
 * holding the schema and publishes nothing, with no log line. That is what stalled tenant-svc's
 * events for up to half an hour while the k6 suites ran. These tests run against a real PgBouncer
 * so it cannot come back.
 */
class OutboxDrainLockThroughPgBouncerTest {

  private static final String SCHEMA = "drain_test";
  private static PostgresSupport pg;
  private static GenericContainer<?> bouncer;
  private static DataSource viaBouncer;
  private static DataSource direct;

  /** The shared drain, over a data source, as one replica of a service. */
  private static final class Replica extends BaseOutboxRepository {
    Replica(DataSource ds) {
      this.dataSource = ds;
    }
  }

  @BeforeAll
  static void start() throws Exception {
    pg = PostgresSupport.start();
    direct = pg.dataSource();
    try (Connection c = direct.getConnection();
        Statement st = c.createStatement()) {
      st.execute("CREATE SCHEMA " + SCHEMA);
      // as each service's role has its schema in its search_path: every server connection agrees
      st.execute("ALTER ROLE " + pg.username() + " SET search_path TO " + SCHEMA);
    }
    Matcher m = Pattern.compile("jdbc:postgresql://([^:/]+):(\\d+)/(\\w+)").matcher(pg.jdbcUrl());
    assertTrue(m.find(), pg.jdbcUrl());
    File users = File.createTempFile("userlist", ".txt");
    users.deleteOnExit();
    Files.writeString(users.toPath(), "\"" + pg.username() + "\" \"" + pg.password() + "\"\n");
    users.setReadable(true, false);
    bouncer =
        new GenericContainer<>("pgbouncer/pgbouncer:latest")
            .withExposedPorts(6432)
            .withExtraHost("host.docker.internal", "host-gateway")
            .withEnv(
                "DATABASES",
                "* = host=host.docker.internal port=" + m.group(2) + " dbname=" + m.group(3))
            .withEnv("PGBOUNCER_POOL_MODE", "transaction")
            .withEnv("PGBOUNCER_DEFAULT_POOL_SIZE", "3")
            .withEnv("PGBOUNCER_MAX_CLIENT_CONN", "100")
            .withEnv("PGBOUNCER_AUTH_TYPE", "trust")
            .withEnv("PGBOUNCER_AUTH_FILE", "/etc/pgbouncer/userlist.txt")
            .withEnv("PGBOUNCER_IGNORE_STARTUP_PARAMETERS", "extra_float_digits,options")
            .withCopyFileToContainer(
                MountableFile.forHostPath(users.toPath()), "/etc/pgbouncer/userlist.txt")
            .waitingFor(Wait.forListeningPort());
    bouncer.start();
    PGSimpleDataSource ds = new PGSimpleDataSource();
    ds.setServerNames(new String[] {bouncer.getHost()});
    ds.setPortNumbers(new int[] {bouncer.getMappedPort(6432)});
    ds.setDatabaseName(m.group(3));
    ds.setUser(pg.username());
    ds.setPassword(pg.password());
    viaBouncer = ds;
  }

  @AfterAll
  static void stop() {
    if (bouncer != null) bouncer.stop();
    if (pg != null) pg.stop();
  }

  /** Advisory locks held by any session of the database, seen from a direct connection. */
  private static int advisoryLocksHeld() throws Exception {
    try (Connection c = direct.getConnection();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND granted")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  @Test
  void aDrainRightGivenBackThroughThePoolerIsReallyGivenBack() throws Exception {
    Replica replica = new Replica(viaBouncer);

    // 1. take the drain right: its connection goes back to the pool's idle list
    var first = replica.tryDrainLock();
    assertTrue(first.isPresent(), "the drain right is free at the start");

    // 2. while it is held, another client keeps a transaction open on the pooler: it takes the
    //    server connection the lock was taken on, so the unlock below cannot run there
    try (Connection other = viaBouncer.getConnection()) {
      other.setAutoCommit(false);
      try (Statement st = other.createStatement()) {
        st.execute("SELECT 1");
      }
      first.get().close(); // the drain ends and gives the right back
      other.rollback();
    }

    // 3. nothing may be left holding it, and the next drain must be able to take it
    assertEquals(0, advisoryLocksHeld(), "no session is left holding the drain lock");
    for (int i = 0; i < 10; i++) {
      var next = replica.tryDrainLock();
      assertTrue(next.isPresent(), "drain " + i + " finds the right free after the last one ended");
      next.get().close();
    }
    assertEquals(0, advisoryLocksHeld());
  }

  @Test
  void onlyOneReplicaHoldsTheDrainRightAtATime() throws Exception {
    Replica a = new Replica(viaBouncer);
    Replica b = new Replica(viaBouncer);
    var held = a.tryDrainLock();
    assertTrue(held.isPresent());
    try {
      assertTrue(b.tryDrainLock().isEmpty(), "a second replica is refused while the first drains");
    } finally {
      held.get().close();
    }
    var second = b.tryDrainLock();
    assertTrue(second.isPresent(), "and gets it once the first has finished");
    second.get().close();
    assertEquals(0, advisoryLocksHeld());
  }

  @Test
  void aDrainThatDiesWithoutClosingLeavesNothingBehind() throws Exception {
    Replica replica = new Replica(viaBouncer);
    var lease = replica.tryDrainLock().orElseThrow();
    // the drain's connection is lost (its client goes away) before close() runs
    Connection lost = null;
    try {
      java.lang.reflect.Field f = lease.getClass().getDeclaredField("c");
      f.setAccessible(true);
      lost = (Connection) f.get(lease);
      lost.abort(Runnable::run);
    } finally {
      if (lost != null && !lost.isClosed()) lost.close();
    }
    long deadline = System.nanoTime() + 20_000_000_000L;
    while (advisoryLocksHeld() > 0 && System.nanoTime() < deadline) Thread.sleep(200);
    assertEquals(0, advisoryLocksHeld(), "a lost drain connection takes its lock with it");
    assertTrue(replica.tryDrainLock().isPresent());
  }
}
