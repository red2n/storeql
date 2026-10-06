-- inventory-svc schema: the stock source of truth. README §7.
-- References store_id/variant_id from other services but NEVER joins their tables (database-per-service).
-- Quantities are NUMERIC (exact). stock_movements is APPEND-ONLY: no UPDATE path, and its one DELETE
-- relocates old rows to stock_movements_archive (MovementArchiveRepository), which keeps every row.

CREATE TABLE inventory_batches (
    id                         UUID PRIMARY KEY,
    tenant_id                  UUID NOT NULL,
    store_id                   UUID NOT NULL,
    variant_id                 UUID NOT NULL,
    batch_no                   TEXT,
    received_qty               NUMERIC(18,3) NOT NULL,
    remaining_qty              NUMERIC(18,3) NOT NULL,            -- the ONLY mutable quantity field
    -- Kept to four places: a unit's share of freight is rarely a whole minor unit, and rounding it on
    -- every application would leave a reversal a minor unit away from where it started. The currency's
    -- minor unit belongs to the valuation report, not to the record it is built from.
    cost_price                 NUMERIC(18,4),
    expiry_date                DATE,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Lifecycle status. ACTIVE is the only value any code writes, and the CHECK below says so: a batch
    -- that runs out keeps its status and says so through remaining_qty = 0, and one past its date is
    -- judged by the store's own day (Expiry), never by a status of its own. A status that something
    -- starts to write is added to the CHECK by the change that writes it.
    status                     TEXT NOT NULL DEFAULT 'ACTIVE',
    -- Physical condition, orthogonal to the lifecycle status:
    -- AVAILABLE | QUARANTINE | INSPECTION | DAMAGED | RECALLED.
    material_status            TEXT NOT NULL DEFAULT 'AVAILABLE',
    material_status_reason     TEXT,
    material_status_changed_at TIMESTAMPTZ,
    grade                      TEXT,                              -- e.g. A | B | C | REJECT
    -- The physical zone/aisle the batch sits in within its store. zone_id is owned by tenant-svc
    -- (Tenant -> Store -> Zone); referenced here by id only, never joined (database-per-service).
    zone_id                    UUID,
    -- A retried POST /admin/inventory/receive must not double-count stock. Nullable, unique only when
    -- present: event-driven receives (GoodsReceivedHandler, through InventoryService.receiveOnce) use
    -- their own dedupe and never set this column.
    idempotency_key            TEXT,
    -- Whose the stock is. OWNED is the business's own; CONSIGNMENT is the supplier's, and then the
    -- supplier is named (purchase-svc owns suppliers; the id is referenced, never joined). Ownership
    -- rides with the stock through a transfer or a lot split, it is what the valuation report sets
    -- apart, and a sale drawn from a consignment batch is announced to purchase-svc as
    -- ConsignmentStockSold at the batch's cost, which is the order's price.
    ownership                  TEXT NOT NULL DEFAULT 'OWNED',
    owner_supplier_id          UUID,
    -- Whether the excise duty has been paid. Duty-suspended stock sits in an approved bonded store: it
    -- is on hand but never available (no hold, no sale, no transfer draws it), and it is valued at cost
    -- without the duty, which crystallises only on release to home use.
    duty_status                TEXT NOT NULL DEFAULT 'DUTY_PAID',
    CONSTRAINT chk_batch_status CHECK (status IN ('ACTIVE')),
    CONSTRAINT chk_material_status CHECK (
        material_status IN ('AVAILABLE', 'QUARANTINE', 'INSPECTION', 'DAMAGED', 'RECALLED')
    ),
    CONSTRAINT chk_batch_ownership CHECK (ownership IN ('OWNED', 'CONSIGNMENT')),
    CONSTRAINT chk_batch_owner_named CHECK (ownership = 'OWNED' OR owner_supplier_id IS NOT NULL),
    CONSTRAINT chk_batch_duty_status CHECK (duty_status IN ('DUTY_PAID', 'DUTY_SUSPENDED'))
);
-- FIFO scan order: soonest expiry first, then oldest. Index supports the deduction query.
CREATE INDEX idx_batches_fifo ON inventory_batches (tenant_id, store_id, variant_id, expiry_date NULLS LAST, created_at);
-- ACTIVE batches (every batch: ACTIVE is the only status). listExpiringBatches reads them; the draw and
-- level queries filter on material_status, not on status.
CREATE INDEX idx_batches_active ON inventory_batches (tenant_id, store_id, variant_id)
    WHERE status = 'ACTIVE';
-- Availability: stock levels count only AVAILABLE batches (the levels query filters on material_status).
CREATE INDEX idx_batches_available_material
    ON inventory_batches (tenant_id, store_id, variant_id, expiry_date NULLS LAST, created_at)
    WHERE material_status = 'AVAILABLE' AND status = 'ACTIVE';
-- Listing batches filtered by material_status.
CREATE INDEX idx_batches_material_status
    ON inventory_batches (tenant_id, material_status);
-- A sale or a hold walks the SKU's batches that still hold stock, not its whole history of emptied
-- ones. The partial index holds only those, in the order a draw takes them.
CREATE INDEX idx_batches_live
    ON inventory_batches (tenant_id, store_id, variant_id, expiry_date NULLS LAST, created_at)
    WHERE remaining_qty > 0 AND material_status = 'AVAILABLE';
CREATE INDEX idx_batches_zone ON inventory_batches (tenant_id, store_id, zone_id) WHERE zone_id IS NOT NULL;
CREATE UNIQUE INDEX idx_batches_idem ON inventory_batches (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
-- Consignment stock by supplier and variant. No query reads it by supplier yet: the valuation
-- reports all consignment stock together.
CREATE INDEX idx_batches_consignment
    ON inventory_batches (tenant_id, owner_supplier_id, variant_id)
    WHERE ownership = 'CONSIGNMENT';
CREATE INDEX idx_batches_in_bond
    ON inventory_batches (tenant_id, store_id, variant_id) WHERE duty_status = 'DUTY_SUSPENDED';

-- Append-only ledger of every stock movement.
CREATE TABLE stock_movements (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL,
    store_id    UUID NOT NULL,
    variant_id  UUID NOT NULL,
    batch_id    UUID,
    -- Domain.MoveType is the whole vocabulary: what the code writes, no more. A customer return or a void
    -- is not a type of its own; it is a RECEIVE whose ref_type says RETURN or VOID.
    type        TEXT NOT NULL,                         -- RECEIVE|SALE|ADJUST|TRANSFER|RTV|RESERVE|BOND_RELEASE|YIELD|RELEASE|LOT_SPLIT|LOT_MERGE
    qty         NUMERIC(18,3) NOT NULL,                -- signed: +in / -out
    ref_type    TEXT,                                  -- e.g. GRN, ORDER, ADJUSTMENT
    ref_id      UUID,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- The controlled reason (transaction_reason_codes) the adjustment paths persist, so "why" is
    -- queryable beside "who". NULL where none was recorded.
    reason_code TEXT,
    -- Who made the movement, where the movement does not name them. A person's adjustment is
    -- attributed here (InventoryRepository.adjustTx, cycle counts, physical inventory, recall
    -- withdrawals), as is a yield a person recorded, a lot split or merge a person made (the lot action
    -- it cites names no one) and a receipt a person entered by hand
    -- (ref_type 'MANUAL', ref_id NULL, actor_id the signed-in user): a negative ADJUST of -50 units
    -- must be attributable to someone, because shrinkage is the highest-value audit case in retail.
    -- A system flow, or a receipt an event caused, names its cause through ref_type/ref_id and
    -- leaves this NULL. A manual receipt, lot split or lot merge made with no signed-in user on the
    -- request (a call between services) is NULL too.
    actor_id    UUID
);
CREATE INDEX idx_movements_tenant ON stock_movements (tenant_id, store_id, variant_id, created_at DESC);
-- "What did this member of staff adjust?" is the question a shrinkage investigation opens with. Partial,
-- because the column is NULL for every system-caused movement.
CREATE INDEX idx_movements_actor ON stock_movements (tenant_id, actor_id, created_at DESC)
    WHERE actor_id IS NOT NULL;
-- A void is recorded as a RECEIVE movement with ref_type = 'VOID' and ref_id = the order, which keeps it
-- distinguishable from a customer return (ref_type 'RETURN'). The demand history and dead-stock reports
-- exclude a SALE whose order was voided by looking for that receipt against the same order and variant.
-- Voids are rare next to other receipts, so a partial index keeps the lookup cheap.
CREATE INDEX idx_stock_movements_voids
    ON stock_movements (tenant_id, ref_id, variant_id)
    WHERE ref_type = 'VOID';
-- The live half of a recall's sales search (stock_movements) is served by variant and type
-- (idx_movements_variant_sales); the archive half is not.
CREATE INDEX idx_movements_variant_sales ON stock_movements (tenant_id, variant_id, created_at)
    WHERE type = 'SALE' AND ref_type = 'ORDER';

CREATE TABLE reservations (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL,
    store_id        UUID NOT NULL,
    variant_id      UUID NOT NULL,
    qty             NUMERIC(18,3) NOT NULL,
    order_id        UUID,
    status          TEXT NOT NULL DEFAULT 'HELD',          -- HELD|CONSUMED|RELEASED
    expires_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- A retried POST /inventory/reservations (e.g. order-svc timing out and resubmitting during checkout)
    -- must not hold stock twice for one checkout attempt. Nullable, unique only when present.
    idempotency_key TEXT,
    -- A hold on a dropship variant holds nothing on the shelf: it exists so the checkout can run as it
    -- always has, and it says so, so consuming it draws no batch.
    fulfilment      TEXT NOT NULL DEFAULT 'STOCK',         -- STOCK | DROPSHIP
    CONSTRAINT chk_reservation_fulfilment CHECK (fulfilment IN ('STOCK', 'DROPSHIP'))
);
CREATE INDEX idx_reservations_expiry ON reservations (status, expires_at) WHERE status = 'HELD';
CREATE UNIQUE INDEX idx_reservations_idem ON reservations (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
-- Every read of what is held for a store and variant, of an order's holds, or of a tenant's newest
-- holds starts at the tenant.
CREATE INDEX idx_reservations_held_variant
    ON reservations (tenant_id, store_id, variant_id)
    WHERE status = 'HELD';
CREATE INDEX idx_reservations_order
    ON reservations (tenant_id, order_id);
CREATE INDEX idx_reservations_tenant_created
    ON reservations (tenant_id, created_at DESC);

-- Per-(store,variant) reorder threshold → drives LowStock. Generic + optional.
CREATE TABLE reorder_thresholds (
    id         UUID PRIMARY KEY,
    tenant_id  UUID NOT NULL,
    store_id   UUID NOT NULL,
    variant_id UUID NOT NULL,
    threshold  NUMERIC(18,3) NOT NULL,
    -- The reorder-to level; null means threshold*2 (see replenishment_suggestions).
    max_qty    NUMERIC(14,4),
    UNIQUE (tenant_id, store_id, variant_id),
    CONSTRAINT chk_threshold_max_gt_min CHECK (max_qty IS NULL OR max_qty > threshold)
);
-- Read path for threshold lookups by store.
CREATE INDEX idx_thresholds_tenant_store
    ON reorder_thresholds (tenant_id, store_id);

-- Idempotency guard for event consumers. The key is (event_id, consumer), not event_id alone: one
-- consumer's mark must not stop another consumer from applying the same event (each consumer name is
-- one purpose).
CREATE TABLE processed_events (
    event_id     UUID NOT NULL,
    consumer     TEXT NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT processed_events_pkey PRIMARY KEY (event_id, consumer)
);
-- Consumer dedupe rows older than their retention (BaseOutboxRepository.purgeProcessedEvents; this
-- schema's timestamp is processed_at). The batch is chosen by the whole key, (event_id, consumer):
--   DELETE FROM processed_events WHERE (event_id, consumer) IN
--     (SELECT event_id, consumer FROM processed_events
--      WHERE processed_at < ? ORDER BY processed_at ASC LIMIT ? FOR UPDATE SKIP LOCKED)
CREATE INDEX idx_processed_events_processed_at
    ON processed_events (processed_at);

-- Transactional outbox. It is deliberately cross-tenant: the relay drains every business's rows in the
-- order they were written, and no statement on the table filters by tenant_id, so none of its indexes
-- leads with it. The column is not a lookup key: the relay's claim does not select it, and the only
-- production code that reads it is the dead-letter warning (BaseOutboxRepository.recordFailures),
-- which names whose event could not be published.
CREATE TABLE outbox (
    id           UUID PRIMARY KEY,
    event_type   TEXT NOT NULL,
    topic        TEXT NOT NULL,
    tenant_id    UUID,
    aggregate_id UUID NOT NULL,
    payload      TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    -- Retry and dead-letter state. A row that fails to publish is retried after a backoff
    -- (storeql.outbox.backoff-base-seconds, doubling, capped at storeql.outbox.backoff-cap-seconds),
    -- and only that row's aggregate waits for it. After storeql.outbox.max-attempts it is a dead
    -- letter: never claimed again, kept for an operator, and it holds back its own aggregate only.
    attempts        INT         NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error      TEXT,
    dead_at         TIMESTAMPTZ,
    CONSTRAINT ck_outbox_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_outbox_dead_unpublished CHECK (dead_at IS NULL OR published_at IS NULL)
);
-- Published outbox rows older than the retention (BaseOutboxRepository.purgePublished):
--   DELETE FROM outbox WHERE id IN (SELECT id FROM outbox
--     WHERE published_at IS NOT NULL AND published_at < ?
--     ORDER BY published_at ASC LIMIT ? FOR UPDATE SKIP LOCKED)
-- A partial index holds only the rows already published, oldest first, so a batch reads exactly the
-- rows it deletes.
CREATE INDEX idx_outbox_published
    ON outbox (published_at)
    WHERE published_at IS NOT NULL;
-- The claim (BaseOutboxRepository.claim): unpublished, not dead, in the order they were written. This
-- index serves its ordered scan (ORDER BY created_at, id LIMIT n); it does not cover next_attempt_at,
-- which the claim reads from the row (the backoff). The claim's check for an earlier waiting row of the
-- same aggregate reads the per-aggregate index below. Marking a row published and recording a failure go
-- by id, and the purge by published_at (above). No index of every unpublished row by created_at
-- (idx_outbox_unpublished) is kept: it would also hold the dead letters, which the claim's ordered scan
-- never reads, and the claim is the one statement that reads waiting rows in that order.
CREATE INDEX idx_outbox_claim
    ON outbox (created_at, id)
    WHERE published_at IS NULL AND dead_at IS NULL;
-- The per-aggregate check: an aggregate's earlier unpublished rows, and whether any is backing off.
CREATE INDEX idx_outbox_aggregate_pending
    ON outbox (aggregate_id, created_at, id)
    WHERE published_at IS NULL;
