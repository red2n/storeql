package com.storeql.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;
import org.eclipse.microprofile.health.Readiness;

/**
 * The three probes every service answers, and the rule that separates them.
 *
 * <p><b>Liveness answers one question: is this process wedged?</b> Nothing else. It never looks at
 * the database, at Kafka, at another service or at anything across a network, because the only
 * remedy the cluster has for a failing liveness probe is to kill the container — and killing a
 * healthy process because its database is slow turns a database problem into a restart storm, with
 * every replacement pod hitting the same slow database and dying in turn. So {@code /health/live}
 * is a 200 with no dependency behind it.
 *
 * <p><b>Readiness answers a different question: can I serve a request right now?</b> Failing it
 * takes the replica out of the load balancer and nothing more, so it may look at the database — and
 * does, through {@link DatabaseProbe}, which asks in the background precisely so a readiness probe
 * never queues behind real traffic for a connection. A pool that is merely busy keeps the replica
 * in rotation; see that class for why.
 *
 * <p><b>Startup answers when the other two may begin.</b> Nothing registers a startup check here,
 * so {@code /health/started} is UP as soon as the HTTP server answers, which is what the startup
 * probe is for: it holds liveness off until the process is listening, however long boot takes.
 *
 * <p>Which endpoint a probe must call therefore matters, and the aggregate is the wrong one:
 *
 * <ul>
 *   <li>{@code /health/live} — this class's {@link ProcessLiveness} alone. What a liveness probe
 *       calls.
 *   <li>{@code /health/ready} — the database and the Kafka consumers. What a readiness probe calls.
 *   <li>{@code /health/started} — what a startup probe calls.
 *   <li>{@code /health} — <b>every</b> check, the database included. For a dashboard or a human.
 *       <b>Never for a liveness probe:</b> pointing liveness here silently makes the database a
 *       reason to kill the pod, which is the exact failure the split above exists to prevent.
 * </ul>
 *
 * <p>A deeper look — a round trip made on demand, with the pool's own figures — is {@link
 * DeepHealthResource}, which no probe calls.
 *
 * <p>Services start in any order and gate on readiness: docs/ARCHITECTURE.md §17.
 */
public final class HealthChecks {

  private HealthChecks() {}

  /**
   * Liveness probe: always UP once the CDI container is up — never checks external deps, and must
   * stay that way. See the class doc.
   */
  @Liveness
  @ApplicationScoped
  public static class ProcessLiveness implements HealthCheck {
    @Inject ServiceSettings settings;

    /**
     * @return an UP response named after {@link ServiceSettings#serviceName()}
     */
    @Override
    public HealthCheckResponse call() {
      return HealthCheckResponse.up(settings.serviceName());
    }
  }

  /**
   * Readiness probe: DOWN when the database has stopped answering — never when it is merely busy.
   */
  @Readiness
  @ApplicationScoped
  public static class DatabaseReadiness implements HealthCheck {
    @Inject DatabaseProbe probe;

    /**
     * @return {@code "database"} as {@link DatabaseProbe#verdict()} has it, with the reason
     *     attached as data (a server-side diagnostic — this response is not client-facing API
     *     output, so it's exempt from the "never leak" rule that applies to {@link
     *     com.storeql.web.ApiResponse})
     */
    @Override
    public HealthCheckResponse call() {
      DatabaseProbe.Verdict verdict = probe.verdict();
      return HealthCheckResponse.named("database")
          .status(verdict.up())
          .withData("detail", verdict.detail())
          .build();
    }
  }

  /**
   * Readiness probe: DOWN, in {@code strict} migrate mode, until the schema is at this build's
   * version, so a service never serves a half-migrated schema. Never DOWN in {@code lenient} or
   * {@code off} mode (see {@link FlywayRunner}).
   */
  @Readiness
  @ApplicationScoped
  public static class SchemaReadiness implements HealthCheck {
    @Inject FlywayRunner runner;

    /**
     * @return {@code "schema"}, DOWN while a strict deployment's migrations have not run, with the
     *     reason as data
     */
    @Override
    public HealthCheckResponse call() {
      boolean held = runner.holdsReadiness();
      FlywayRunner.MigrationFailedException failed = runner.failure();
      String detail =
          !held
              ? "migrated"
              : failed != null
                  ? "a migration failed; see the log"
                  : "waiting for the database; migrating as soon as it answers";
      return HealthCheckResponse.named("schema").status(!held).withData("detail", detail).build();
    }
  }

  /**
   * A consumer that fails to start (bad bootstrap config, broker unreachable, etc.) must not leave
   * the service silently "ready" while it never processes another event — see {@link
   * KafkaConsumerRegistry}.
   */
  @Readiness
  @ApplicationScoped
  public static class KafkaConsumerReadiness implements HealthCheck {

    /**
     * @return {@code "kafka-consumers"} UP if {@link KafkaConsumerRegistry#failedConsumers()} is
     *     empty; otherwise DOWN with the failed consumer names attached as data
     */
    @Override
    public HealthCheckResponse call() {
      Set<String> failed = KafkaConsumerRegistry.failedConsumers();
      // A running loop whose broker has stopped answering is not ready either: it is not taking
      // events, and traffic for its projections would otherwise keep arriving at a stopped replica.
      long windowMillis = Math.max(1, Cfg.getLong("storeql.kafka.stall-seconds", 60L)) * 1000L;
      Set<String> stalled =
          KafkaConsumerRegistry.stalledConsumers(System.currentTimeMillis(), windowMillis);
      if (failed.isEmpty() && stalled.isEmpty()) {
        return HealthCheckResponse.named("kafka-consumers").up().build();
      }
      return HealthCheckResponse.named("kafka-consumers")
          .down()
          .withData("failed", String.join(",", failed))
          .withData("stalled", String.join(",", stalled))
          .build();
    }
  }
}
