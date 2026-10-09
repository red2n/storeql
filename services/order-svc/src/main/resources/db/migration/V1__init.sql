-- order-svc schema: saga coordinator for online checkout and in-store POS.
-- Gap #14 POS: post-void, layaway (deposit + deferred pickup), gift cards (issue/reload/redeem).
-- Money is NUMERIC (exact). Append-only tables have no UPDATE/DELETE paths.

-- Money is unconstrained NUMERIC: the currency decides the precision, not the column. order-svc
-- rounds every amount half up to the currency's minor units (common-service Fx.minorUnits) before it
-- writes it, so a yen business keeps whole yen and a dinar business keeps three places. A fixed scale
-- would round a dinar to two places on every order, return, gift card and deposit without an error.
-- The exceptions are a commission statement line's amount, threshold_from and rated_commission, which
-- are written as computed, not rounded (V18).
-- Left at a fixed scale, on purpose:
--   * sales_invoices and ereporting_submissions: EN 16931 (BR-DEC) and the French e-reporting flux
--     state amounts to at most two decimals; an invoice or report in those formats is in euros (or
--     another two-decimal currency) by the law that asks for it.
--   * order_items.vat_amount NUMERIC(18,4), return_items.unit_price NUMERIC(18,4), the return
--     policy's ceilings NUMERIC(18,4), fiscal receipts' NUMERIC(18,4) totals: already finer than
--     any currency's minor units.
--   * Quantities (NUMERIC(18,3) on stock and order lines; NUMERIC(14,3) for the containers on a deposit
--     line, order_deposits.qty in V16): how finely stock is counted has nothing to do with the currency.
-- Money-bearing currency columns carry no default: an insert that leaves one out fails with a NOT NULL
-- violation instead of stamping a currency the business did not choose.

-- ── Order groups (split fulfilment) ───────────────────────────────────────────
-- A delivery order the delivery-area store cannot fill alone is placed as a group: one checkout,
-- one payment, one child order per store. Each child keeps its single store, so every consumer of
-- order events keeps working; the group is order-svc's own. Every id is bound by the service.
CREATE TABLE order_groups (
    id              UUID          NOT NULL,
    tenant_id       UUID          NOT NULL,
    customer_id     UUID,
    login_id        UUID,
    total           NUMERIC       NOT NULL,
    currency        TEXT          NOT NULL,
    idempotency_key TEXT,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_order_groups PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uq_order_groups_key ON order_groups (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
CREATE INDEX idx_order_groups_login ON order_groups (tenant_id, login_id, created_at DESC);

-- ── Fulfilment windows ────────────────────────────────────────────────────────
-- Delivery and collection slots (intent/delivery-and-collection-slots.md).
-- A store offers windows for delivery and for collection, set separately: the weekday, the time of
-- day in the store's own zone, how many orders it takes, and how long before it starts orders stop.
-- Weekly only (ISO weekday 1=Monday..7=Sunday); a dated exception (a holiday) is a later item —
-- a manager switches the window off and on again instead. Every id is bound by the service.
CREATE TABLE fulfilment_windows (
    id              UUID          NOT NULL,
    tenant_id       UUID          NOT NULL,
    store_id        UUID          NOT NULL,
    fulfilment_type TEXT          NOT NULL,
    weekday         SMALLINT      NOT NULL,
    start_time      TIME          NOT NULL,
    end_time        TIME          NOT NULL,
    capacity        INTEGER       NOT NULL,
    cutoff_minutes  INTEGER       NOT NULL DEFAULT 0,
    active          BOOLEAN       NOT NULL DEFAULT true,
    -- The store's own IANA zone (TenantProfiles.Stores.zoneOf) at the moment this was set: the
    -- fallback the storefront and checkout read when tenant-svc cannot be reached, never a
    -- platform default and never guessed.
    time_zone       TEXT          NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by      UUID          NOT NULL,
    CONSTRAINT pk_fulfilment_windows PRIMARY KEY (id),
    CONSTRAINT chk_fulfilment_windows_type CHECK (fulfilment_type IN ('DELIVERY', 'PICKUP')),
    CONSTRAINT chk_fulfilment_windows_weekday CHECK (weekday BETWEEN 1 AND 7),
    CONSTRAINT chk_fulfilment_windows_span CHECK (start_time < end_time),
    CONSTRAINT chk_fulfilment_windows_capacity CHECK (capacity >= 1),
    CONSTRAINT chk_fulfilment_windows_cutoff CHECK (cutoff_minutes >= 0)
);
-- Every read and write is by tenant then store: the admin list, the storefront read and the
-- overlap check on a write all filter this way first.
CREATE INDEX idx_fulfilment_windows_store
    ON fulfilment_windows (tenant_id, store_id, fulfilment_type, weekday);

-- ── Orders ────────────────────────────────────────────────────────────────────
-- customer_id is the shop's record of the buyer (customer-svc). login_id is the shopper's iam-svc
-- login on an online order. The two id spaces never meet, so they are kept in separate columns: loyalty,
-- the confirmation email and erasure each resolve a person by the id they have. Checkout fills both,
-- resolving one from the other through customer-svc (POST /customers/me). The login stays on the order
-- because it authorises a shopper to read their own order without a lookup, and because it lets an
-- erasure find an order whose customer record was never linked.
CREATE TABLE orders (
    id                    UUID PRIMARY KEY,
    tenant_id             UUID NOT NULL,
    store_id              UUID NOT NULL,
    customer_id           UUID,
    login_id              UUID,
    channel               TEXT NOT NULL,                    -- ONLINE | POS
    fulfilment_type       TEXT NOT NULL DEFAULT 'INSTORE',  -- INSTORE | PICKUP | DELIVERY
    -- PENDING | AWAITING_PRICE | CONFIRMED | PARTIALLY_FULFILLED | FULFILLED | REFUNDED |
    -- PARTIALLY_REFUNDED | CANCELLED | VOIDED
    status                TEXT NOT NULL DEFAULT 'PENDING',
    subtotal              NUMERIC NOT NULL,
    tax_amount            NUMERIC NOT NULL DEFAULT 0,
    -- The staff-applied discount on the order. Every grant is audited line by line in order_discounts.
    discount_amount       NUMERIC NOT NULL DEFAULT 0,
    -- Promotional discount, kept apart from discount_amount on purpose: a promotion is automatic, has
    -- no actor and answers to a rule, so it must not sit inside the staff role-ceiling check. Which
    -- promotions applied, and for how much, is in order_promotions.
    promotion_discount    NUMERIC NOT NULL DEFAULT 0,
    total                 NUMERIC NOT NULL,
    -- Split tenders each publish a PaymentCaptured for part of the total; paid_amount accumulates them,
    -- so the order confirms once they sum to the total.
    paid_amount           NUMERIC NOT NULL DEFAULT 0,
    -- Refunds are driven by payment-svc's PaymentRefunded event (a manual refund, or one on return or
    -- cancel). refunded_amount accumulates them against the total to decide REFUNDED vs PARTIALLY_REFUNDED,
    -- mirroring paid_amount.
    refunded_amount       NUMERIC NOT NULL DEFAULT 0,
    currency              TEXT NOT NULL,
    -- The tender the customer chose at checkout (CASH | CARD | UPI | WALLET). Nullable: POS orders can
    -- settle with several split tenders (recorded in payment-svc). For ONLINE orders this is the
    -- customer's declared intent — e.g. CASH + fulfilment DELIVERY is cash-on-delivery; actual settlement
    -- still lives in payment-svc.
    payment_method        TEXT,
    -- A sale to a tax-exempt buyer, and the reason it is exempt.
    tax_exempt            BOOLEAN NOT NULL DEFAULT false,
    exempt_reason         TEXT,
    -- The customer's or walk-in's own number, as typed, on any order that gives one. A delivery order
    -- also has delivery_recipient_phone, the number of whoever receives it.
    contact_phone         TEXT,
    -- The contact number in international form, read at placement in the store's own country, then the
    -- business's home and its other stores' (intent/phone-at-the-till.md). What a recall text is sent
    -- to. Null when no number was given, or when the one given could not be read: an online number is
    -- kept as typed and is never refused over it. Blanked with contact_phone when a customer is erased.
    contact_phone_e164    TEXT,
    -- Structured home-delivery address, captured when fulfilment_type = 'DELIVERY'. Nullable: pickup and
    -- in-store orders never populate these. Enforced at the service layer (required-when-DELIVERY), not a
    -- DB CHECK, since the column set differs per fulfilment type rather than being a fixed rule.
    delivery_line1            TEXT,
    delivery_line2            TEXT,
    delivery_city             TEXT,
    delivery_postal_code      TEXT,
    delivery_recipient_name   TEXT,
    delivery_recipient_phone  TEXT,
    -- Who is credited with the sale, which is not necessarily who rang it up (pos_log_entries.cashier_id).
    -- Null for a sale nobody is credited with: an online order usually has no seller, and says so by
    -- leaving this null rather than crediting whoever happened to confirm it.
    seller_user_id        UUID,
    -- The shopper's choice at checkout: on unless they turned it off. Read from the order, never from a
    -- request.
    allow_substitutions   BOOLEAN NOT NULL DEFAULT true,
    -- The part's place in its group, the delivery-area store's part first: the order the shopper reads
    -- them in. Set exactly when the order belongs to a group (order_groups, above).
    group_id              UUID,
    group_part            SMALLINT,
    -- The occurrence an order holds: the window it was taken from and the chosen occurrence's UTC
    -- instants, fixed at the moment of placing rather than recomputed later against rules that may since
    -- have changed. slot_time_zone is the zone the occurrence was resolved in at that moment, so the
    -- server can always show it in the store's own local time. All four are set together or not at all.
    -- slot_window_id names a fulfilment_windows row (above).
    slot_window_id        UUID,
    slot_starts_at        TIMESTAMPTZ,
    slot_ends_at          TIMESTAMPTZ,
    slot_time_zone        TEXT,
    -- Whether the order was sold at shelf prices, VAT inside (intent/vat-inclusive-pricing.md). Then
    -- subtotal is the sum of the lines' net after every discount, tax_amount the sum of their VAT, and
    -- total the sum of what the lines were paid (order_items.paid_gross) plus deposits and gift-card
    -- value; discount_amount and promotion_discount are what was given, already inside the lines and
    -- never subtracted again. false: an order priced net, VAT added.
    tax_inclusive         BOOLEAN NOT NULL DEFAULT false,
    -- Set once, by the sweeper, when an order waiting for a price passes the first limit.
    price_overdue_at      TIMESTAMPTZ,
    -- An exchange names the sale it bought (returns.exchange_order_id) and that sale names the return,
    -- so either can be found from the other. The return stays insert-only; the link on the order is
    -- written once, on the transaction that places it.
    exchanged_from_return_id UUID,
    notes                 TEXT,
    idempotency_key       TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_orders_group_part CHECK ((group_id IS NULL) = (group_part IS NULL)),
    CONSTRAINT chk_orders_slot_shape CHECK (
        (slot_window_id IS NULL AND slot_starts_at IS NULL AND slot_ends_at IS NULL AND slot_time_zone IS NULL)
        OR (slot_window_id IS NOT NULL AND slot_starts_at IS NOT NULL AND slot_ends_at IS NOT NULL AND slot_time_zone IS NOT NULL)),
    CONSTRAINT chk_orders_contact_phone_e164 CHECK (
        contact_phone_e164 IS NULL OR contact_phone_e164 ~ '^\+[1-9][0-9]{6,14}$'),
    CONSTRAINT fk_orders_group_id FOREIGN KEY (group_id) REFERENCES order_groups (id),
    CONSTRAINT fk_orders_slot_window_id FOREIGN KEY (slot_window_id) REFERENCES fulfilment_windows (id)
);
CREATE INDEX idx_orders_tenant     ON orders (tenant_id, store_id, created_at DESC);
CREATE INDEX idx_orders_customer   ON orders (tenant_id, customer_id) WHERE customer_id IS NOT NULL;
CREATE UNIQUE INDEX uq_orders_idem ON orders (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
-- Lookup by the shopper's login: an erasure reaches an online order through it.
CREATE INDEX idx_orders_login ON orders (tenant_id, login_id) WHERE login_id IS NOT NULL;
-- Partial, because most orders online have no seller and an index over nulls would be mostly empty.
CREATE INDEX idx_orders_seller ON orders (tenant_id, seller_user_id, created_at DESC) WHERE seller_user_id IS NOT NULL;
CREATE INDEX idx_orders_group  ON orders (tenant_id, group_id, group_part) WHERE group_id IS NOT NULL;
-- Capacity is counted here: the window row is locked first (FOR UPDATE), then the count reads the orders
-- already holding the same occurrence through this index (tenant, window, start). The index holds no
-- status: the query itself leaves out CANCELLED and VOIDED orders (COUNT DISTINCT coalesce(group_id, id)).
CREATE INDEX idx_orders_slot ON orders (tenant_id, slot_window_id, slot_starts_at) WHERE slot_window_id IS NOT NULL;
-- "Which order was this?" at the till: the receipt prints the last eight characters of the order id.
CREATE INDEX idx_orders_short_ref ON orders (tenant_id, (right(id::text, 8)));
CREATE INDEX idx_orders_awaiting_price ON orders (tenant_id, created_at) WHERE status = 'AWAITING_PRICE';
-- The stranded-order sweeper reads only PENDING orders, oldest change first: without this it scans and
-- sorts the whole orders table every few minutes.
CREATE INDEX idx_orders_pending ON orders (updated_at) WHERE status = 'PENDING';
-- The fulfilment queue (online orders still owing goods at a store) reads a few hundred rows of a
-- store's whole history otherwise.
CREATE INDEX idx_orders_owing ON orders (tenant_id, store_id, created_at, id)
  WHERE channel = 'ONLINE' AND status IN ('CONFIRMED', 'PARTIALLY_FULFILLED');

COMMENT ON COLUMN orders.seller_user_id IS
    'Who is credited with the sale, which is not necessarily who rang it up (pos_log_entries.cashier_id). Null for a sale nobody is credited with.';

CREATE TABLE order_items (
    id               UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL,
    order_id         UUID NOT NULL REFERENCES orders(id),
    variant_id       UUID NOT NULL,
    qty              NUMERIC(18,3) NOT NULL,
    unit_price       NUMERIC NOT NULL,
    line_total       NUMERIC NOT NULL,
    -- Line-level discount, independent of the order-level one.
    discount_amount  NUMERIC NOT NULL DEFAULT 0,
    discount_reason  TEXT,
    -- Which weighing instrument produced the reading a sold-by-weight line was priced on. Null for a line
    -- sold by the each. tenant-svc keeps the register of instruments; the till refuses to sell by weight
    -- from one that is not certified, and the line records the instrument it was weighed on, so an
    -- inspector can go from a receipt to a certificate.
    weighing_instrument_id UUID,
    -- Cumulative quantity handed over. The order is PARTIALLY_FULFILLED until every line is complete.
    -- Returns and void restocks are capped by what was actually handed over, not by what was ordered.
    fulfilled_qty    NUMERIC(18,3) NOT NULL DEFAULT 0,
    -- The VAT on each line, as the quote priced it. Both the German and the Portuguese file list every
    -- document by VAT rate, and a basket of 19% and 7% lines cannot be split from the order's one tax
    -- total. NULL for a line placed with server-side pricing off.
    vat_amount       NUMERIC(18,4),
    -- What the quote taxed each line at. The rate cannot be recovered from a rounded amount on a small
    -- line, and an invoice states it.
    vat_code         TEXT,
    vat_rate         NUMERIC(7,4),   -- the fraction the quote applied: 0.2000 for 20%
    -- 05.4 date-code markdown: a line sold at a reduced-price sticker records which markdown priced it,
    -- so pricing-svc can count the sticker down and a report can say what reducing to clear cost.
    markdown_id      UUID,
    -- What of a line will never be handed over (closed short), and which line a substitute replaces.
    -- outstanding = qty - fulfilled_qty - short_qty; a line's line_total and vat_amount are reduced pro
    -- rata to what stands.
    short_qty           NUMERIC(18,3) NOT NULL DEFAULT 0,
    substitutes_item_id UUID REFERENCES order_items (id),
    -- A shelf-price order (orders.tax_inclusive): what the customer paid for the line after every
    -- discount, VAT included. line_total is what is left once vat_amount is taken out of it, so
    -- paid_gross = line_total + vat_amount always. Null on an order priced net.
    paid_gross       NUMERIC,
    -- The shelf price of one unit before any promotion, in the price list's own terms: what a receipt
    -- prints beside the quantity. Null when the quote did not say.
    list_unit_price  NUMERIC,
    notes            TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_order_items_short_within CHECK (fulfilled_qty + short_qty <= qty),
    CONSTRAINT chk_order_items_paid_gross CHECK (
        paid_gross IS NULL OR (paid_gross >= 0 AND vat_amount IS NOT NULL AND paid_gross = line_total + vat_amount))
);
CREATE INDEX idx_order_items_tenant   ON order_items (tenant_id, order_id);
CREATE INDEX idx_order_items_markdown ON order_items (tenant_id, markdown_id) WHERE markdown_id IS NOT NULL;

-- Append-only status audit. Never UPDATE or DELETE.
CREATE TABLE order_status_history (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL,
    order_id    UUID NOT NULL,
    from_status TEXT,
    to_status   TEXT NOT NULL,
    reason      TEXT,
    changed_by  UUID,
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_order_history_tenant    ON order_status_history (tenant_id, order_id, changed_at);
CREATE INDEX idx_order_history_cancelled ON order_status_history (tenant_id, changed_at DESC)
    WHERE to_status = 'CANCELLED';

-- ── Returns (Gap #14) ────────────────────────────────────────────────────────

CREATE TABLE returns (
    id                UUID PRIMARY KEY,
    tenant_id         UUID NOT NULL,
    -- Null only for a return with no receipt (no_receipt), which has no sale to point at.
    order_id          UUID,
    store_id          UUID NOT NULL,
    reason            TEXT NOT NULL,
    refund_amount     NUMERIC NOT NULL,
    -- ORIGINAL | STORE_CREDIT | GIFT_CARD, as a caller chooses. EXCHANGE is written by a direct exchange
    -- (POST /orders/{id}/exchange), where the returned value pays the new basket; no caller picks it.
    refund_method     TEXT NOT NULL DEFAULT 'ORIGINAL',
    status            TEXT NOT NULL DEFAULT 'COMPLETED',  -- PENDING | COMPLETED | REJECTED
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ,
    -- Who took the goods back. Null where nobody is recorded: the audit trail shows such a return as
    -- unattributed rather than inventing an actor.
    created_by        UUID,
    -- A retried return answers with the first one.
    idempotency_key   UUID,
    -- The sales.refund holder who allowed a return outside the policy.
    approved_by       UUID,
    -- Why it needed them (WINDOW, CEILING, FAULTY_PAST_WINDOW, or NO_RECEIPT for a return with no receipt).
    -- An array, not a comma-joined string, so a reason can be asked for with && and never split by mistake.
    outside_policy    TEXT[] NOT NULL DEFAULT '{}',
    -- The card a GIFT_CARD refund was put on.
    gift_card_id      UUID,
    -- An exchange's new sale (orders.exchanged_from_return_id links back).
    exchange_order_id UUID,
    -- A return with no receipt: priced at today's price at that store, paid as store credit or a gift
    -- card only, never cash, never back to a tender that was never taken.
    no_receipt        BOOLEAN NOT NULL DEFAULT false,
    -- The customer whose store credit it goes to.
    customer_id       UUID,
    -- The phone or email the customer gave, kept for the record and never logged.
    customer_contact  TEXT,
    CONSTRAINT chk_returns_order_or_no_receipt CHECK (order_id IS NOT NULL OR no_receipt),
    CONSTRAINT chk_returns_no_receipt_method   CHECK (NOT no_receipt OR refund_method IN ('STORE_CREDIT', 'GIFT_CARD'))
);
CREATE INDEX idx_returns_tenant      ON returns (tenant_id, order_id);
CREATE INDEX idx_returns_tenant_time ON returns (tenant_id, created_at DESC);
CREATE UNIQUE INDEX uq_returns_idempotency_key ON returns (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX idx_returns_no_receipt  ON returns (tenant_id, store_id, created_at) WHERE no_receipt;

CREATE TABLE return_items (
    id            UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    return_id     UUID NOT NULL REFERENCES returns(id),
    variant_id    UUID NOT NULL,
    qty           NUMERIC(18,3) NOT NULL,
    refund_amount NUMERIC NOT NULL,
    -- SEALED (back on sale) | OPENED | DAMAGED | FAULTY. Null only on a line recorded without one; every
    -- new line names one.
    condition     TEXT,
    -- What each line was worth: a no-receipt return prices its lines at the current price, so the unit
    -- price (VAT included) and the VAT in the line are kept beside the refund. Null on a return against a
    -- sale, whose price is the sale's.
    unit_price    NUMERIC(18,4),
    tax_amount    NUMERIC(18,4),
    CONSTRAINT chk_return_items_condition CHECK (condition IS NULL OR condition IN ('SEALED', 'OPENED', 'DAMAGED', 'FAULTY'))
);
CREATE INDEX idx_return_items_tenant ON return_items (tenant_id, return_id);

-- ── Post-void log (Gap #14) — append-only ────────────────────────────────────

CREATE TABLE pos_void_log (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL,
    order_id        UUID NOT NULL,
    store_id        UUID NOT NULL,
    reason          TEXT,
    voided_by       UUID,
    voided_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- A retry answers with the first void.
    idempotency_key UUID
);
CREATE INDEX idx_pos_void_tenant ON pos_void_log (tenant_id, order_id);
-- The staff exception report ("what was voided last month", "what did this cashier void") and the audit trail.
CREATE INDEX idx_pos_void_tenant_time  ON pos_void_log (tenant_id, voided_at DESC);
CREATE INDEX idx_pos_void_tenant_actor ON pos_void_log (tenant_id, voided_by, voided_at DESC)
    WHERE voided_by IS NOT NULL;
CREATE UNIQUE INDEX uq_pos_void_idempotency_key ON pos_void_log (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- ── Layaway (Gap #14) ─────────────────────────────────────────────────────────

CREATE TABLE layaways (
    id            UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    store_id      UUID NOT NULL,
    customer_id   UUID,
    total_amount  NUMERIC NOT NULL,
    deposit_paid  NUMERIC NOT NULL DEFAULT 0,
    balance       NUMERIC NOT NULL,
    status        TEXT NOT NULL DEFAULT 'ACTIVE',     -- ACTIVE | COMPLETED | CANCELLED
    notes         TEXT,
    due_date      TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at  TIMESTAMPTZ,
    cancelled_at  TIMESTAMPTZ
);
CREATE INDEX idx_layaways_tenant   ON layaways (tenant_id, store_id, status);
-- Redaction finds a customer's layaways by customer.
CREATE INDEX idx_layaways_customer ON layaways (tenant_id, customer_id) WHERE customer_id IS NOT NULL;

CREATE TABLE layaway_items (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL,
    layaway_id  UUID NOT NULL REFERENCES layaways(id),
    variant_id  UUID NOT NULL,
    qty         NUMERIC(18,3) NOT NULL,
    unit_price  NUMERIC NOT NULL,
    line_total  NUMERIC NOT NULL
);
CREATE INDEX idx_layaway_items_tenant ON layaway_items (tenant_id, layaway_id);

-- Append-only deposit ledger.
CREATE TABLE layaway_deposits (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL,
    layaway_id      UUID NOT NULL,
    amount          NUMERIC NOT NULL,
    payment_method  TEXT,
    reference       TEXT,
    paid_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_layaway_deposits_tenant ON layaway_deposits (tenant_id, layaway_id, paid_at);

-- ── Gift cards (Gap #14) ──────────────────────────────────────────────────────

CREATE TABLE gift_cards (
    id               UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL,
    store_id         UUID NOT NULL,
    code             TEXT NOT NULL,
    initial_balance  NUMERIC NOT NULL,
    current_balance  NUMERIC NOT NULL,
    status           TEXT NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | DEPLETED | CANCELLED
    currency         TEXT NOT NULL,
    issued_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ,
    -- Where the value came from, and who put it there and why, for a card a manager loads by hand.
    source           TEXT,
    reason           TEXT,
    UNIQUE (tenant_id, code)
);
CREATE INDEX idx_gift_cards_tenant ON gift_cards (tenant_id, code);

-- Append-only balance ledger for each gift card.
CREATE TABLE gift_card_transactions (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL,
    gift_card_id    UUID NOT NULL,
    -- ISSUE | RELOAD | REDEEM | LOAD_REVERSED. REFUND and CANCEL are defined (GiftCardTransaction) but
    -- nothing writes them.
    tx_type         TEXT NOT NULL,
    amount          NUMERIC NOT NULL,
    balance_before  NUMERIC NOT NULL,
    balance_after   NUMERIC NOT NULL,
    order_id        UUID,
    reference       TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    source          TEXT,
    reason          TEXT,
    acted_by        UUID,
    -- A redemption, and a card issued or reloaded by hand, carries the caller's Idempotency-Key, and
    -- uq_gct_idempotency_key admits one such row per key. Every other row has none.
    idempotency_key UUID
);
CREATE INDEX idx_gct_tenant ON gift_card_transactions (tenant_id, gift_card_id, created_at);
-- One card may be redeemed only once per order, so a replayed sale cannot redeem twice. Redemptions with
-- no order (a manual back-office adjustment) are unconstrained, hence the partial index.
CREATE UNIQUE INDEX uq_gct_redeem_per_order
    ON gift_card_transactions (tenant_id, gift_card_id, order_id)
    WHERE tx_type = 'REDEEM' AND order_id IS NOT NULL;
CREATE UNIQUE INDEX uq_gct_idempotency_key
    ON gift_card_transactions (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL;

-- ── Outbox (transactional events) ─────────────────────────────────────────────
-- The outbox is deliberately cross-tenant: the relay drains every business's rows in the order they were
-- written, and the hourly purge trims every business's sent rows by age, so neither reads by tenant and
-- no index here starts with tenant_id.
-- A row that fails to publish is retried after a backoff (storeql.outbox.backoff-base-seconds, doubling,
-- capped at storeql.outbox.backoff-cap-seconds), and only that row's aggregate waits for it. After
-- storeql.outbox.max-attempts it is a dead letter: never claimed again, kept for an operator, and it
-- holds back its own aggregate only.

CREATE TABLE outbox (
    id           UUID PRIMARY KEY,
    event_type   TEXT NOT NULL,
    topic        TEXT NOT NULL,
    tenant_id    UUID,
    aggregate_id UUID NOT NULL,
    payload      TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    attempts         INT         NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error       TEXT,
    dead_at          TIMESTAMPTZ,
    CONSTRAINT chk_outbox_attempts CHECK (attempts >= 0),
    CONSTRAINT chk_outbox_dead_unpublished CHECK (dead_at IS NULL OR published_at IS NULL)
);
-- The claim (common-service BaseOutboxRepository.claim): rows that may publish now, in the order they
-- were written. This index serves its ordered scan (ORDER BY created_at, id LIMIT n); its predicate
-- leaves out dead letters, as the claim does. The claim's check for an earlier waiting row of the same
-- aggregate reads idx_outbox_aggregate_pending, below, and marking a row published and recording a
-- failure go by primary key. No index of every unsent row by created_at (idx_outbox_unpublished) is
-- kept: it would also hold the dead letters, which the claim's ordered scan never reads.
CREATE INDEX idx_outbox_claim ON outbox (created_at, id) WHERE published_at IS NULL AND dead_at IS NULL;
-- The per-aggregate check: an aggregate's earlier unpublished rows, and whether any is backing off or dead.
CREATE INDEX idx_outbox_aggregate_pending ON outbox (aggregate_id, created_at, id) WHERE published_at IS NULL;
-- The hourly purge (common-service OutboxPublisher) trims rows already sent, oldest first, in batches.
-- Partial, like the claim's: this index holds the rows already sent, so it stays as small as the
-- retention window once the purge keeps up.
CREATE INDEX idx_outbox_published ON outbox (published_at) WHERE published_at IS NOT NULL;
