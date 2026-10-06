# Follow-ups — product images & the memory/cache/log/service audit

Picking this back up later. Everything below is **open work**, written down so none of it has to be
re-derived. The branch it relates to is `feature/client-side-product-image-compression`
(8 commits, `ad10044`..`6a22025`, branched from `main` at `2aa5584`).

> **Read the blocking section first.** Some of this branch has never been compiled or started.

---

## 1. BLOCKING — verification debt on the branch

Nothing here is a known bug. It is work that was written but never executed, because the authoring
environment had **no Flutter SDK and no Docker**. Java was compiled and unit-tested; Dart and all
infrastructure config were not run at all.

| What | Why it is outstanding | Command |
|---|---|---|
| Flutter analyze + test | No Flutter SDK. **4 commits of Dart have never been compiled.** | `cd frontends/storeql-app && flutter pub get && flutter analyze && flutter test` |
| Redis Testcontainers tests | No Docker. These cover **exactly** the code changed in `2244d13`. | `mvn -o -pl platform/gateway test` |
| `CatalogIT` (product-svc) | No Docker. | `mvn -o -pl services/product-svc test` |
| Stack boot | No Docker. A bad collector processor name or Loki delete-store fails **at boot**. | `docker compose config && docker compose up -d` |

Highest-risk spots if something fails:

- **`package:web` interop** in [image_compress_web.dart](../frontends/storeql-app/lib/shared/util/image_compress_web.dart)
  — the `drawImage` 5-arg overload and the `Blob`/`toDataURL` signatures were written against the
  API from memory, never compiled.
- **`FutureOr` return-type inference** on the rewired `productImageProvider` in
  [storefront_providers.dart](../frontends/storeql-app/lib/features/storefront/storefront_providers.dart#L442)
  — relies on the closure's context type being applied downward.
- **`image: ^4.9.1`** promoted from a transitive dev dependency to a direct one. Should not shift
  resolution (it was already in `pubspec.lock`) but is unverified.
- **Loki + collector configs** — validated as YAML and for internal coherence only. Neither has
  been started.

`mvn clean` does not run in that environment (the clean plugin has a missing dep in the offline
cache — pre-existing, unrelated). Build without it.

---

## 2. Decisions to confirm

Two deliberate calls that a second opinion should ratify. Both are cheap to reverse.

### 2a. Brute-force protection fails open

[BruteForceProtectionService.isBlocked](../platform/gateway/src/main/java/com/storeql/gateway/filters/BruteForceProtectionService.java)
returns `false` when Redis cannot answer.

The reasoning: failing closed denies every login for all tenants for as long as Redis is away — a
total authentication outage caused by a cache going down, and trivially weaponised by anyone able
to disturb Redis. Failing open costs a window where credential-stuffing is unthrottled *at the
gateway*, but iam-svc still verifies every password, so this is defence-in-depth rather than the
last line.

**The reverse is defensible.** If the threat model prefers it, `return true` in the catch block is
the only line that changes. Rate limiting also fails open, but that one is the uncontroversial
industry default and needs no decision.

### 2b. Legacy product images above 256 KB

`V14__product_images.sql` (folded) adds the size constraint, enforced inline on every new write. Rows written
under the old 512 KB cap were never re-checked, so any that exist are still serving. A database that applied the earlier `NOT VALID` form still needs the `VALIDATE` below.

```sql
-- Find rows still in breach:
SELECT tenant_id, product_id, octet_length(bytes) AS size_bytes
  FROM product_images
 WHERE octet_length(bytes) >= 262144
 ORDER BY size_bytes DESC;

-- Once that returns nothing, promote to fully enforced:
ALTER TABLE product_images VALIDATE CONSTRAINT product_images_size_under_256kb;
```

Until that runs, "no image over 256 KB in the system" is true going forward but not retroactively.

---

## 3. Open findings from the audit (not acted on)

Ranked by value. None are urgent.

### 3a. Four more unbounded keyed caches (Flutter)

[storefront_providers.dart](../frontends/storeql-app/lib/features/storefront/storefront_providers.dart)
lines ~457, ~477, ~485, ~512 — variants, prices, offers. Same never-disposed `family` pattern that
`productImageProvider` had, but these hold small parsed DTOs, so it is tens of KB rather than tens
of MB. `ImageByteCache` is a generic-enough shape to reuse if standardising.

### 3b. Hikari has no leak detection

[DataSourceProducer.java](../shared/common-service/src/main/java/com/storeql/service/DataSourceProducer.java)
sets `connectionTimeout` and `maxLifetime` but not `leakDetectionThreshold`. Standard for catching
connections that are never returned. Cheap; most valuable in staging.

### 3c. Inconsistent upstream read timeouts

product-svc uses `readTimeout(30s)` to inventory and pricing; **every other client uses 5s**. A slow
dependency pins product-svc threads six times longer, and its `@CircuitBreaker` will not trip on
slowness — only on failure. Either justify the 30s in a comment or bring it in line.

### 3d. Kafka consumer memory is untuned

No `max.poll.records` or `max.partition.fetch.bytes` anywhere. Defaults (500 records,
`fetch.max.bytes` 50 MB) against a 512 MB heap. Probably fine; worth a look under load.

### 3e. Containers with no `mem_limit`

`postgres`, `consul`, `grafana`, `pgadmin`, and the exporters. Postgres is defensible (it manages
its own `shared_buffers`/`work_mem`, and this is now noted in `.env.example`); redis is
self-bounded by `maxmemory 256mb`. The rest are small. Capped total across everything that *does*
set a limit is ~12.6 GB.

### 3f. Loki retention is not env-tunable

`retention_period: 168h` is hardcoded, against the repo convention of `${VAR:-default}` everywhere
else. Making it tunable needs `-config.expand-env=true` on Loki's command line. **Deliberately
skipped**: it introduces a way for Loki to fail at boot, and there was no Docker available to
verify it. Do it with a stack running.

---

## 4. Larger items, if the product pushes that way

Neither is needed now. Both were anticipated by the original design comment in
`V14__product_images.sql` ("the stack has no object store yet").

### 4a. Server-side image normalisation

The 256 KB cap is enforced on *bytes*, not *dimensions*. A hand-crafted 255 KB 8000×8000 PNG passes
every check and then costs ~256 MB of RGBA on any client that decodes it. The Flutter app is
protected — it clamps decode width — but nothing else is, and the invariant is weaker than it
looks. A decode-and-re-encode pass in product-svc would close it, at the cost of an image codec
dependency (ImageIO plus TwelveMonkeys for WebP).

### 4b. Object storage for images

Currently images are `BYTEA` rows read in full through the service on every storefront render. An
object store plus a URL column removes them from the DB, the service heap, and the request path in
one move. This is the real fix for the whole class of problem; the client compression and the
256 KB cap are what make it not urgent.

---

## 5. What was done (for context when picking this up)

| Commit | Change |
|---|---|
| `ad10044` | Client-side image compression to a 256 KB budget (web canvas / `package:image`) |
| `47d0518` | 256 KB enforced as a system invariant: service cap + `V14__product_images.sql` (folded) CHECK constraint |
| `21b980e` | Thumbnails decode to their layout box — ~4.9 MB → ~114 KB for the 72×72 tile |
| `3d95c66` | Product image byte cache bounded by an LRU (16 MB / 200 entries) |
| `2244d13` | Redis fails open; Lettuce command timeout 60s → 250ms |
| `ac4f365` | otel-collector: `memory_limiter`, bounded exporter queues, `mem_limit`, `GOMEMLIMIT` |
| `785abc0` | Logs default to INFO, level settable via `STOREQL_LOG_LEVEL` without a rebuild |
| `6a22025` | Loki retention (7 days) via compactor, plus explicit ingest/query limits |

The last four compose deliberately: a throttled Loki returns 429 to the collector, whose bounded
queue and memory limiter shed load rather than accumulate it, and P3 means far less is sent in the
first place.
