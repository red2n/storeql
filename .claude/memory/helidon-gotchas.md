---
name: helidon-gotchas
description: Helidon 4.x + Java 25 (release 21) known pitfalls specific to StoreQL. Read before scaffolding or debugging any back-end service.
metadata:
  type: project
---

# Helidon 4.4.x gotchas (learned building iam-svc + inventory-svc)

These bite silently and waste hours — apply them automatically.

| # | Symptom | Root cause | Fix |
|---|---|---|---|
| 1 | `/health` and `/metrics` return 404 | Custom `Server.create()` main | Use `mainClass = io.helidon.Main` |
| 2 | `/health` 404 after adding exception mapper | Catch-all `ExceptionMapper<Throwable>` shadows framework routes | Only add `ApiExceptionMapper` for specific app exceptions |
| 3 | JAX-RS returns 500 for DTO serialization | Slim `helidon-microprofile-core` ships JSON-P, not JSON-B | Add `jersey-media-json-binding:3.1.11` + `yasson:3.0.4` |
| 4 | `@Valid` on resource params silently ignored | Helidon validator doesn't integrate as expected | Call `Validations.validate(dto)` explicitly in the resource |
| 5 | Fat jar won't start (ClassNotFoundException) | `target/libs/` not populated | Add `maven-dependency-plugin:copy-dependencies` to package phase |
| 6 | (June note, now retired) "Build or runtime failure with JDK 25" | Recorded against an older Helidon; no cause was written down | On Helidon 4.5.5 and Temurin 25.0.4 the build gates (format, Checkstyle, PMD, SpotBugs, ArchUnit) and inventory-svc and the gateway pass unchanged (8 Oct 2026, [intent/jdk-25.md](../../intent/jdk-25.md)). The real 25 trap is the image: `eclipse-temurin:25-jre` has no curl, so healthchecks use `infra/healthcheck.sh` |
| 7 | `PSQLException: ERROR: could not determine data type of parameter $1` | `setObject(i, null)` for a UUID column | Use a separate `col IS NULL` query branch |
| 8 | Flyway histories collide between services | All services share one Postgres DB in compose | Set `storeql.db.schema`; call `setCurrentSchema(schema)` and `.schemas(s).defaultSchema(s).createSchemas(true)` on Flyway |
| 9 | Kafka consumer / outbox publisher never runs | CDI is lazy; `@ApplicationScoped` bean not instantiated | Add `void onStart(@Observes @Initialized(ApplicationScoped.class) Object e) {}` |

**Why:** These were discovered during iam-svc and inventory-svc implementation. The scaffold-service skill already applies them but they can re-appear when adding a new bean type or upgrading a dependency.
