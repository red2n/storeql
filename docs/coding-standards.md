# StoreQL Coding Standards

These rules apply to **every file in every service**, including future development. They are not suggestions — violating one is a bug even if the code compiles and tests pass. A Claude agent must enforce them automatically without being asked.

---

## 1. SQL rules

### 1.1 Never use `SELECT *`
Always name every column the query relies on. This makes the contract explicit, prevents silent breakage when a column is added or reordered, and keeps the query self-documenting.

```java
// WRONG
"SELECT * FROM products WHERE tenant_id = ? AND id = ?"

// RIGHT
"SELECT id, tenant_id, name, description, brand_id, category_id, status, " +
"sellable_online, sellable_pos, created_at, updated_at " +
"FROM products WHERE tenant_id = ? AND id = ?"
```

### 1.2 Every query must have a WHERE clause
No query — including simple lookups and list queries — may execute without at least one `WHERE` predicate. An unbounded `SELECT` against a tenant table is both a correctness bug (wrong data returned) and a performance bomb.

```java
// WRONG — no predicate, returns everything
"SELECT id, name FROM products ORDER BY created_at"

// WRONG — missing tenant isolation
"SELECT id, name FROM products WHERE status = 'ACTIVE'"

// RIGHT — tenant_id is always first
"SELECT id, tenant_id, name FROM products WHERE tenant_id = ? AND status = 'ACTIVE'"
```

This applies equally to `UPDATE` and `DELETE`. A bare `UPDATE table SET ...` with no `WHERE` is forbidden.

**A bounded count is the one shape whose outer query has no `WHERE`:** `SELECT count(*) AS n FROM (SELECT 1 FROM t WHERE tenant_id = ? AND status = 'X' LIMIT ?) AS waiting` counts a derived table, and the predicate (tenant first) and the bound are in the inner select, which is the only access to the table. Use it when a figure is polled and "N or more" will do (the system-health screen's waiting-work counts, `PendingWorkCount.CAP`); `count(*)` and `count(1)` are the same work in PostgreSQL, what costs is the rows visited.

### 1.3 Tenant filter is always the first WHERE condition
Per the golden rule in CLAUDE.md: `tenant_id` from the JWT, first predicate, every time. See golden rule #3.

---

### 1.4 Migration files
Flyway's standard name: `V<n>__<description>.sql`, lowercase snake_case words that say what the file creates (`V5__input_vat.sql`). Each service's versions are unique integers (a new table is the next number; after the first release tag a gap left by a lost numbering race is allowed). <!-- migration-policy:v1 --> Until the first release tag a migration only creates, and a change to a table goes into the `CREATE TABLE` in the file that creates it; from the tag on, published files are frozen and a change is a new additive migration, a destructive move carrying its `-- storeql:contract` marker (the full rule: [ARCHITECTURE §7](ARCHITECTURE.md#7-anatomy-of-one-service)). `validateMigrationNaming` is on, so a misspelled file name fails startup instead of being skipped.

### 1.5 Table and column names
Tables: plural lowercase snake_case (`order_items`; a log, a projection or a ledger may be singular: `audit_log`). Columns: singular lowercase snake_case; a timestamp ends `_at`, a date `_on`, an id `_id`.

### 1.6 Constraint and index names
Name every constraint and index you write, with the prefix of its type:

| Object | Prefix | Example |
|---|---|---|
| Primary key | `pk_` | `pk_processed_events` |
| Foreign key | `fk_` | `fk_orders_group_id` |
| Unique constraint or unique index | `uq_` | `uq_users_business_email` |
| Check | `chk_` | `chk_zone_status` |
| Other index | `idx_` | `idx_orders_tenant_created` |

A name is `<prefix><what it protects or serves>`, at most 63 characters. Names Postgres picks for an unnamed constraint (`orders_pkey`, `x_y_fkey`, `x_y_check`) are left alone, and so are the `v7_` checks common-service's callback adds. `PostgresSupport.stop()` fails an integration test class whose schema has a name outside the rule (`badObjectNames()`), so a violation is a failing build.

## 2. SOLID principles

### 2.1 Single Responsibility (SRP)
Each class has exactly one reason to change.

| Layer | Its only job |
|---|---|
| `api/` Resource | Deserialise request → call service → serialise response. No logic, no DB calls. |
| `service/` Service | Orchestrate domain rules. No HTTP concerns, no SQL. |
| `repo/` Repository | SQL in, domain objects out. No business rules. |
| `messaging/` Consumer | Poll Kafka, dispatch to handler. No business logic. |
| `messaging/` Handler | Process one event payload. No Kafka lifecycle. |
| `config/` | Hold injected config values. Nothing else. |

**Concrete rule:** If a Kafka consumer class contains JSON parsing or service calls outside of a delegate, extract a handler bean. The consumer owns the poll loop; the handler owns what to do with each record.

```java
// WRONG — consumer doing business logic inline
private void handle(String json) {
    JsonObject obj = Json.createReader(...).readObject();
    UUID tenantId = UUID.fromString(obj.getString("tenantId"));
    service.doSomething(tenantId, ...);  // business logic inside the consumer
}

// RIGHT — consumer delegates to handler
private void pollQuietly() {
    for (var rec : consumer.poll(Duration.ofMillis(500))) {
        handler.handle(rec.value());  // handler is a separate @ApplicationScoped bean
    }
}
```

### 2.2 Open/Closed (OCP)
Classes are open for extension, closed for modification. Concretely:

- **Topic names come from `@ConfigProperty`**, not from hardcoded string constants. Adding or renaming a topic must not require editing the consumer class.

  ```java
  // WRONG
  private static final String TOPIC = "storeql.tenant.tenant-created";

  // RIGHT
  @Inject @ConfigProperty(name = "storeql.kafka.topics.tenant-created",
                          defaultValue = "storeql.tenant.tenant-created")
  String topic;
  ```

- **Status/type branching:** Avoid `if/else` or `switch` chains on domain status strings inside service or repo classes. If a new status would require editing the class body, the design is wrong — push the logic into the domain object or a strategy.

### 2.3 Liskov Substitution (LSP)
Any implementation of an interface must honour the full contract of that interface — not just the method signatures. If a `ServiceRegistry` resolves a service name to a healthy instance, every implementation must do that; none may silently return an empty result for non-exceptional cases without documenting the deviation.

### 2.4 Interface Segregation (ISP)
Interfaces must be narrow. A class should not be forced to implement methods it does not use.

- Keep interfaces to 1–3 related methods.
- `OutboxStore` (2 methods) and `ServiceRegistry` (1 method) are the reference examples of correct size.
- If an interface grows beyond ~5 methods, split it.

### 2.5 Dependency Inversion (DIP)
High-level modules depend on abstractions, not on concrete classes.

- **Inject interfaces, not implementations.** `ProxyResource` injects `ServiceRegistry`, not `ConsulClient`. When the lookup mechanism changes, `ProxyResource` is untouched.
- **Produce infrastructure objects via CDI `@Produces` factory beans**, not with `new` inside business classes. `new` inside a `@PostConstruct` to build Kafka infrastructure is allowed only in the consumer/publisher bootstrap class itself (the class whose sole job is that lifecycle).

  ```java
  // WRONG — ProxyResource building its own collaborators
  @PostConstruct
  void init() {
      this.consul = new ConsulClient(config.consulHost(), config.consulPort());
      this.webClient = WebClient.builder().build();
  }

  // RIGHT — ProxyResource receives abstractions via injection
  @Inject ServiceRegistry registry;
  @Inject WebClient webClient;
  // GatewayBeans @Produces both
  ```

---

## 3. Java style rules

- **No comments explaining WHAT the code does.** Well-named identifiers do that. Comments are only for WHY: a hidden constraint, a subtle invariant, a Helidon/Kafka behaviour that would surprise a reader.
- **No multi-line docstrings on obvious methods.** One-line class Javadoc is fine; paragraph-length method docs are not.
- **Money is `BigDecimal` / `NUMERIC`.** Never `double` or `float` for any monetary value, quantity, or rate.
- **Time is `Instant` (UTC) in domain objects.** Convert to `ZonedDateTime` at the API edge only, and only when the client needs a timezone.
- **IDs are `UUID`, and only UUIDv7.** Never `long`, never `String` for primary keys.
  - Mint with `Ids.newId()`. A key a redelivered event must reproduce (a dedupe id per line) is `Ids.derived(eventId, name)`. A random value that is not an id comes from `SecureRandom`.
  - Never `UUID.randomUUID()` (v4) or `UUID.nameUUIDFromBytes()` (v3) — PMD `UseTimeOrderedIds`.
  - Read an id with `Ids.parse(text)`, never `UUID.fromString` — it refuses any version but 7, the wrong variant and non-canonical text; never `new UUID(msb, lsb)` outside `common-ids` — PMD `ParseIdsAsV7`, and ArchUnit `IDS_ARE_V7` in tests. At the HTTP edge `common-web` already does it (`400 INVALID_UUID`).
  - An `Idempotency-Key` is a UUIDv7: `Ids.newId()` per attempt, `Ids.derived(attemptId, "step")` for a step of it (`400 IDEMPOTENCY_KEY_INVALID` otherwise). That includes a key a service makes for itself or sends to another service — `Ids.derived(orderId, "store-credit")`, never `"sc:" + orderId` — and a body's key field, read through `IdempotencyKeys.effective(header, body)`. Every `idempotency_key` column refuses anything but a canonical lowercase v7.
  - Every uuid column carries a database `CHECK` for v7, added by common-service's `afterMigrate__uuid_v7_everywhere.sql`; a migration adds nothing for it.
  - Never `gen_random_uuid()` / `uuid_generate_v4()` in SQL — PMD `NoDatabaseMintedIds`. Every `INSERT` names `id` in its column list and binds `Ids.newId()`.
  - Never a column `DEFAULT` that fills in a uuid, in any migration — the integration-test audit in `PostgresSupport.stop()` fails the build, and the Flyway `afterMigrate` check fails `flyway migrate`. Seed rows carry literal v7 ids.
  - A short handle for people (order number, batch-number suffix) is `Ids.shortRef(id)`, the end of the id — never `id.toString().substring(0, n)` (PMD `ShortRefFromIdTail`).

---

## 4. Quick checklist for every PR / code change

Before marking any work done, verify:

- [ ] No `SELECT *` anywhere in the diff.
- [ ] Every SQL query has a `WHERE` clause. Every `UPDATE`/`DELETE` has a `WHERE`.
- [ ] `tenant_id` is the first `WHERE` condition on every tenant-scoped query.
- [ ] Resource class contains no business logic and no SQL.
- [ ] Service class contains no SQL and no HTTP concerns.
- [ ] Kafka consumer class contains no business logic (handler bean holds that).
- [ ] Topic names are `@ConfigProperty` with a sensible default.
- [ ] New collaborators are injected, not `new`-ed inside business classes.
- [ ] No floating-point for money or quantities.
