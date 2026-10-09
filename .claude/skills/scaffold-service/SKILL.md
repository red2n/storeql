---
name: scaffold-service
description: Scaffold a new StoreQL business microservice (Helidon MP, Java 25 LTS, release 21) with the standard module layout, layered packages, Flyway migration, health/metrics, config, and Consul registration. Use when creating a brand-new service under services/.
---

# Scaffold a new StoreQL microservice

Use this when adding a **new business microservice** to `services/`. It produces a service that already satisfies the [golden rules](../../../CLAUDE.md) and the [Definition of Done](../../../docs/ARCHITECTURE.md#19-definition-of-done).

> Read first if unsure: [ARCHITECTURE §7 Anatomy of a service](../../../docs/ARCHITECTURE.md#7-anatomy-of-one-service), the target service's spec in [docs/API-GUIDE.md](../../../docs/API-GUIDE.md). **Fastest path: use `services/iam-svc` as the structural reference** (auth, outbox, validation) or `services/inventory-svc` for a full domain-rich example.

## Reuse `shared/common-service` — do NOT re-write infra (saves ~100 lines/service; keeps Duplo low)

The DataSource producer, Flyway runner, Consul registrar, health checks, and Kafka outbox publisher live in `shared/common-service`. A new service:
1. Depends on `com.storeql:common-service`.
2. `ServiceConfig implements com.storeql.service.ServiceSettings` (serviceName/port, db url/user/pwd/schema, consul host/port/enabled, kafka enabled/bootstrap, outboxPollSeconds). Add any service-specific config as extra fields.
3. If it has an outbox: its repo `implements com.storeql.service.OutboxStore` (`pendingOutbox` + `markPublished`, using `OutboxStore.PendingOutbox`). If it has no outbox, skip this — the shared publisher no-ops.
4. **Do NOT create** `DataSourceProducer`, `FlywayMigration`, `ConsulRegistration`, `HealthChecks`, or `OutboxPublisher` — they are shared. Keep only service-specific messaging (consumers, sweepers).

Validate after adding a service: `scripts/duplo.sh` (duplication should stay ~10%).

## Helidon 4.4.1 setup gotchas — apply these or things break (learned building iam-svc + inventory-svc)

1. **`mainClass` = `io.helidon.Main`** (NOT a custom `Server.create()` main) — else `/health` & `/metrics` 404.
2. **No catch-all `ExceptionMapper<Throwable>`/`<Exception>`** — it shadows the framework's `/health` & `/metrics` routes. Keep only the specific `ApiExceptionMapper` (in common-web).
3. **Slim `helidon-microprofile-core` ships JSON-P, not JSON-B** — add `org.glassfish.jersey.media:jersey-media-json-binding:3.1.11` + `org.eclipse:yasson:3.0.4`, or JAX-RS can't serialize DTO records.
4. **Bean Validation: `@Valid` is ignored / Helidon's mapper leaks internals.** Add `io.helidon.microprofile.bean-validation:helidon-microprofile-bean-validation` (pulls Hibernate Validator) and validate explicitly in the resource with `com.storeql.web.Validations.validate(dto)` (returns the clean `VALIDATION_FAILED` 400 envelope). Do NOT rely on `@Valid` on resource params.
5. **Runnable jar needs `target/libs/`** — add `maven-dependency-plugin:copy-dependencies` (phase `package`, outputDir `target/libs`). The Helidon parent sets the jar manifest `Class-Path: libs/*`.
6. **Native image support is optional.** If you want GraalVM native builds, add a `native` Maven profile using `org.graalvm.buildtools:native-maven-plugin` and build with `mvn -Pnative clean package` under GraalVM `JAVA_HOME`.
7. **Build and run on JDK 25** (Temurin; point `JAVA_HOME` at it or put its `bin/` first on the PATH). The jars still target `maven.compiler.release` 21, so do not use language features newer than 21 ([intent/jdk-25.md](../../../intent/jdk-25.md)).
8. **JDBC null UUID**: never bind a null UUID via `setObject(i, null)` in `col = ?` — Postgres can't infer the type. Use a separate `col IS NULL` query branch.
8. **Database-per-service = a schema per service.** All services share one Postgres `storeql` db in compose, so isolate by schema or their Flyway histories collide. Add `storeql.db.schema` (default = short name, e.g. `iam`); set `ds.setCurrentSchema(schema)` and Flyway `.schemas(s).defaultSchema(s).createSchemas(true)`.
9. **Background beans (Kafka publisher/consumer) must be EAGER.** A `@PostConstruct`-only `@ApplicationScoped` bean is never instantiated (CDI is lazy) → it silently never runs. Add `void onStart(@Observes @Initialized(ApplicationScoped.class) Object e) {}` to force eager init.

## Inputs to confirm before generating

1. **Service name** — `<x>-svc` (kebab). Must match the name in [docs/API-GUIDE.md](../../../docs/API-GUIDE.md) and Consul.
2. **Local dev port** — from [PRD §8](../../../PRD.md) (e.g. `inventory-svc` = 8004). Prod uses 8080 for all.
3. **Owns (tables)** — the entities this service owns (from its [ARCHITECTURE §10](../../../docs/ARCHITECTURE.md#10-the-business-services) entry). No other service's tables.
4. **Events** — published (past tense) + consumed (from [docs/API-GUIDE.md](../../../docs/API-GUIDE.md) / [ARCHITECTURE §11](../../../docs/ARCHITECTURE.md#11-how-services-talk-to-each-other)).
5. **Sync dependencies** — which other services it calls ([ARCHITECTURE §11](../../../docs/ARCHITECTURE.md#11-how-services-talk-to-each-other) sync map).

If any are unknown, stop and check docs/API-GUIDE.md / docs/ARCHITECTURE.md — do not invent ownership or events.

## Steps

1. **Create the Maven module** `services/<x>-svc/` and add it to the parent `pom.xml` `<modules>`. Inherit the parent (Java 25 build / release 21, Helidon BOM). Add dependencies: Helidon MP (server, config, health, metrics, JWT-auth, fault-tolerance), Helidon Messaging + Kafka connector (only if it publishes/consumes events), JPA + PostgreSQL driver, Flyway, Bean Validation, and the `shared/common-ids` + `shared/common-web` + `shared/events-contract` modules.

2. **Create the package layout** under `src/main/java/com/storeql/<x>/`:
   ```
   api/        # JAX-RS resources — THIN: validate DTO, call service, return DTO. No DB, no logic.
   dto/        # request/response records (the API contract). Bean Validation annotations here.
   service/    # business logic + transactions. The brain.
   domain/     # JPA entities (= tables). Never returned over HTTP.
   repo/       # persistence. Every tenant query filters tenant_id FIRST.
   messaging/  # Kafka producers (drain outbox) + consumers (idempotent). Only if eventing.
   client/     # typed REST clients to other services via discovery. Timeout+retry+breaker+fallback.
   mapper/     # entity ↔ DTO.
   config/     # MP Config injection, beans.
   ```

3. **Wire the cross-cutting basics** (mostly from `shared/common-web`):
   - Response envelope `{data,error,meta}` + exception mapper (correct HTTP codes, stable error `code`, no stack/SQL leakage).
   - Tenant context filter: extract `tenant_id`, `userId`, `roles` from the verified JWT; expose to `service/`.
   - `X-Request-Id` propagation + tracing.
   - Cursor pagination helper.

4. **Database**: create `src/main/resources/db/migration/V1__init.sql` defining the owned tables. Every tenant-owned table: `id UUID PK` with **no `DEFAULT`** — the service binds `Ids.newId()` (UUIDv7) in every `INSERT`, and seed rows carry literal v7 ids (Flyway's `afterMigrate` check fails a column that generates its own uuid), `tenant_id UUID NOT NULL`, composite index starting `tenant_id`, `timestamptz` UTC times, `NUMERIC` for money/qty, append-only tables have no UPDATE/DELETE paths. Follow the tenant-filter rules in [docs/coding-standards.md](../../../docs/coding-standards.md).

5. **Config**: `src/main/resources/META-INF/microprofile-config.properties` with non-secret defaults (port, app name = service name). DB URL, Kafka brokers, secrets come from **config service / env** — never hardcoded. Register the service name with **Consul** on startup; deregister on shutdown.

6. **Health/metrics**: implement `/health/started`, `/health/live`, and `/health/ready` — **ready must check real dependencies** (DB connection + Kafka reachable + config loaded). Expose `/metrics`. (Mostly provided by Helidon MP Health/Metrics; add dependency readiness checks.)

7. **Eventing (if applicable)**:
   - Add an `outbox` table; publish events by writing to `outbox` in the **same transaction** as the state change, drained to Kafka by `messaging/`.
   - Consumers are **idempotent** (dedupe on event id / business key) and live in `messaging/` but delegate logic to `service/`.
   - Topics: `storeql.<domain>.<event>`. Contracts go in `shared/events-contract`, not here.

8. **Tests**: a unit test for a core `service/` rule + a **Testcontainers** integration test (real Postgres, and Kafka if eventing) covering the service's primary flow. The service is not done without this.

9. **Docker**: ensure it builds with `mvn clean install`, containerizes, and is added to `docker-compose.yml` with a healthcheck (`/health/ready`) and `depends_on: {postgres: service_healthy, kafka: service_healthy, consul: service_healthy}` per [ARCHITECTURE §17](../../../docs/ARCHITECTURE.md#17-production-deployment--startup-ordering).

## Verify before declaring done

Run through [ARCHITECTURE §19 Definition of Done](../../../docs/ARCHITECTURE.md#19-definition-of-done). Critically: no cross-service DB access, DTOs in/out, tenant filtering, discovery registration, external config, outbox + idempotent consumers, 3 health probes with real readiness, starts in any order, sync calls resilient, tests present, `mvn clean install` green.
