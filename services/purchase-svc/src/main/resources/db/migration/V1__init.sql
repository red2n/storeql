-- purchase-svc base schema: suppliers and purchasing, goods receipts, intercompany invoices (Gap #20),
-- the FRS 102 nominal ledger and the outbox. Later migrations add to it.
-- UK GAAP / HMRC compliant. All monetary values in NUMERIC. All timestamps UTC.

SET search_path TO purchase;

-- ── Suppliers ─────────────────────────────────────────────────────────────────
-- Country and currency have no default: every insert binds them, so one left out is a NOT NULL
-- failure, never a supplier silently filed under pounds or dollars.
CREATE TABLE suppliers (
  id                      UUID        PRIMARY KEY,
  tenant_id               UUID        NOT NULL,
  name                    VARCHAR(200) NOT NULL,
  vat_number              VARCHAR(20),
  vat_registered          BOOLEAN     NOT NULL DEFAULT false,
  country_code            CHAR(2)     NOT NULL,
  currency                CHAR(3)     NOT NULL,
  payment_terms_days      INT         NOT NULL DEFAULT 30,
  -- The supplier's quoted lead time in days: the promise a delivery is measured against when the
  -- order named no date. Null when the supplier has never quoted one.
  lead_time_days          INT,
  -- Where the supplier's e-invoices come from: its Peppol participant identifier, as an EAS scheme
  -- (0088 GLN, 9930 German VAT, 0208 Belgian enterprise number …) and the identifier within it.
  einvoice_scheme         TEXT,
  einvoice_id             TEXT,
  -- Where a supplier is paid and told. Account details are for a UK Faster Payments / BACS payment
  -- (sort code + account number) or an international one (IBAN, with a BIC). A change to them is
  -- the classic payment-diversion fraud, so the moment and the person are recorded and a payment run
  -- flags a change made shortly before it.
  remittance_email        TEXT,
  bank_account_name       TEXT,
  bank_sort_code          VARCHAR(6),
  bank_account_number     VARCHAR(8),
  bank_iban               VARCHAR(34),
  bank_bic                VARCHAR(11),
  bank_details_changed_at TIMESTAMPTZ,
  bank_details_changed_by UUID,
  bank_details_version    INTEGER     NOT NULL DEFAULT 0,
  created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_supplier_lead_time CHECK (lead_time_days IS NULL OR lead_time_days >= 0)
);
CREATE INDEX idx_suppliers_tenant     ON suppliers(tenant_id);
CREATE UNIQUE INDEX uq_suppliers_tenant_name ON suppliers(tenant_id, name);
CREATE UNIQUE INDEX uq_suppliers_einvoice_address
    ON suppliers (tenant_id, einvoice_scheme, lower(einvoice_id))
    WHERE einvoice_id IS NOT NULL;
CREATE INDEX idx_suppliers_vat_number
    ON suppliers (tenant_id, upper(replace(vat_number, ' ', '')))
    WHERE vat_number IS NOT NULL;

COMMENT ON COLUMN suppliers.bank_details_version IS
    'Moves by one on every real change of the bank details; a payment run keeps the version it was approved with.';

-- ── Purchase Orders ───────────────────────────────────────────────────────────
-- Status. PENDING_APPROVAL is deliberately a state and not a boolean: "waiting for a decision" and
-- "decided" are different facts, and a supplier must not receive an order that is merely waiting.
-- SUBMITTED is reachable two ways, approved or under the raiser's own authority, and is receivable.
-- A goods receipt moves an order by quantity: PARTIALLY_RECEIVED while some of it has arrived and
-- more is still expected, RECEIVED once everything ordered has arrived.
-- CLOSED is a short-close: part arrived, the rest never will, and we have stopped waiting. Distinct
-- from RECEIVED because "we got it all" and "we gave up on the rest" are different facts, and a
-- supplier scorecard that cannot tell them apart is worthless. Distinct from CANCELLED because stock
-- IS booked against this order.
--
-- Cancellation. An order is cancelled at most once (the transition is guarded to DRAFT and SUBMITTED,
-- the two states a cancel is allowed from), so its reason lives on the order rather than in a
-- history table: every read path that shows the status already loads this row. A received order is
-- not cancelled, because stock is booked against it; the return to vendor is its reverse. A
-- cancelled order carries its reason and timestamp, and a live order carries neither, so a stale
-- reason cannot survive on an active order.
--
-- Money. Unconstrained NUMERIC, not NUMERIC(14,2): the currency decides the precision, not the column
-- (SJ-D25). Totals.of rounds at the currency's own minor units before the write, and that scale
-- survives the round trip. A fixed scale of 2 stored a yen figure at two decimal places (3702.00)
-- and rounded the third decimal of a dinar.
--
-- Home-currency figure. The spend authority is decided against the order's figure in the business's
-- home currency. The rate and the translated net are kept on the order, because a rate moves and the
-- record must not. The net is unconstrained for the same reason as the totals: Fx.toHome rounds it to
-- the home currency's own minor units before the write, and a fixed scale would round it again (BHD
-- 0.462 stored as 0.46). fx_rate is a rate, not an amount, so it keeps NUMERIC(24,10) at Fx.RATE_SCALE.
CREATE TABLE purchase_orders (
  id                UUID        PRIMARY KEY,
  tenant_id         UUID        NOT NULL,
  supplier_id       UUID        NOT NULL,
  store_id          UUID        NOT NULL,
  status            VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  currency          CHAR(3)     NOT NULL,
  total_net         NUMERIC     NOT NULL DEFAULT 0,
  total_vat         NUMERIC     NOT NULL DEFAULT 0,
  total_gross       NUMERIC     NOT NULL DEFAULT 0,
  expected_delivery DATE,
  fx_rate           NUMERIC(24,10), -- home units per one unit of the order's currency, as used at submission
  total_net_home    NUMERIC,        -- the net translated into the home currency at that rate
  home_currency     CHAR(3),        -- the home currency the translation was into
  cancelled_at      TIMESTAMPTZ,
  cancelled_reason  TEXT,
  -- A short-close records why, on the same pattern as the cancellation reason: a closure whose stated
  -- reason is not the one recorded is worse than no reason at all.
  closed_at         TIMESTAMPTZ,
  closed_reason     TEXT,
  -- Who raised it. Recorded from the JWT at creation, never from the request body (golden rule #3
  -- applies to identity as much as to tenant_id). Null where the raiser was not captured: inventing
  -- one would be worse than admitting none, the same rule as stock_movements.actor_id (SJ-D4).
  created_by        UUID,
  -- An approval names its approver and when; a rejected order goes back to DRAFT and keeps neither.
  approved_by       UUID,
  approved_at       TIMESTAMPTZ,
  -- MANUAL a person raised it; PROPOSAL the automatic order proposal; DROPSHIP the order a dropship
  -- sale raised for its supplier; RFQ an award of a request for quotes.
  source            TEXT        NOT NULL DEFAULT 'MANUAL',
  -- OWNED or CONSIGNMENT: whose the goods will be on arrival. A consignment receipt posts nothing
  -- (see consignment_sales).
  ownership         TEXT        NOT NULL DEFAULT 'OWNED',
  -- A dropship order knows the sale it fulfils and where the supplier ships it.
  sales_order_id    UUID,           -- order-svc's order, referenced
  ship_to           TEXT,           -- the customer, as the order said
  -- DUTY_PAID or DUTY_SUSPENDED: a bonded order's goods arrive with the excise duty suspended (see
  -- duty_releases).
  duty_status       TEXT        NOT NULL DEFAULT 'DUTY_PAID',
  -- When the order went to the supplier: the moment it became SUBMITTED, whether straight from DRAFT
  -- or through approval. Null for an order that was never submitted.
  submitted_at      TIMESTAMPTZ,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_po_status CHECK (status IN (
      'DRAFT',
      'PENDING_APPROVAL',
      'SUBMITTED',
      'PARTIALLY_RECEIVED',
      'RECEIVED',
      'CLOSED',
      'CANCELLED'
  )),
  CONSTRAINT chk_po_cancelled_fields CHECK (
    (status =  'CANCELLED' AND cancelled_at IS NOT NULL AND cancelled_reason IS NOT NULL)
    OR
    (status <> 'CANCELLED' AND cancelled_at IS NULL     AND cancelled_reason IS NULL)
  ),
  -- Scoped to the states an approval decision actually produces. SUBMITTED is reachable two ways --
  -- approved, or under the raiser's own authority and never routed for approval -- so the first
  -- branch leaves it out, and the second (no condition on the approval fields) takes it.
  CONSTRAINT chk_po_approved_fields CHECK (
    (status IN ('DRAFT','PENDING_APPROVAL') AND approved_by IS NULL AND approved_at IS NULL)
    OR
    status NOT IN ('DRAFT','PENDING_APPROVAL')
  ),
  CONSTRAINT chk_po_source CHECK (source IN ('MANUAL', 'PROPOSAL', 'DROPSHIP', 'RFQ')),
  CONSTRAINT chk_po_ownership CHECK (ownership IN ('OWNED', 'CONSIGNMENT')),
  CONSTRAINT chk_po_duty_status CHECK (duty_status IN ('DUTY_PAID', 'DUTY_SUSPENDED'))
);
CREATE INDEX idx_po_tenant          ON purchase_orders(tenant_id);
CREATE INDEX idx_po_tenant_supplier ON purchase_orders(tenant_id, supplier_id);
CREATE INDEX idx_po_tenant_store    ON purchase_orders(tenant_id, store_id);

-- Finding what is waiting for me is the query the approval feature is used through; without it every
-- approver's landing screen is a full scan of the tenant's purchase orders.
CREATE INDEX idx_po_pending ON purchase_orders (tenant_id, status) WHERE status = 'PENDING_APPROVAL';

CREATE INDEX idx_po_sales_order ON purchase_orders (tenant_id, sales_order_id)
    WHERE sales_order_id IS NOT NULL;

-- ── Purchase Order Lines ──────────────────────────────────────────────────────
CREATE TABLE purchase_order_lines (
  id              UUID          PRIMARY KEY,
  tenant_id       UUID          NOT NULL,
  po_id           UUID          NOT NULL REFERENCES purchase_orders(id),
  variant_id      UUID          NOT NULL,
  qty             NUMERIC(14,3) NOT NULL,
  -- Unconstrained: a unit price legitimately carries more precision than the currency's minor unit.
  -- 1,000 screws at £0.0125 each is an ordinary trade price, and a fixed scale of 2 rounds it to
  -- £0.01: a line of £12.50 is written as £10.00, 20% short of what it costs (SJ-D25).
  unit_price      NUMERIC       NOT NULL,
  vat_code        VARCHAR(10)   NOT NULL DEFAULT 'T1',
  -- Why the proposal put this line here, in the buyer's words; null on a line a person typed.
  proposal_reason TEXT,
  created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_pol_tenant ON purchase_order_lines(tenant_id);
CREATE INDEX idx_pol_po     ON purchase_order_lines(tenant_id, po_id);

-- Three-way match: the order's lines are compared with receipt lines by variant, which is the same
-- answer a warehouse would give when counting what arrived.
CREATE INDEX idx_purchase_order_lines_variant
    ON purchase_order_lines (tenant_id, po_id, variant_id);

-- ── Goods Receipts (GRN) ──────────────────────────────────────────────────────
CREATE TABLE goods_receipts (
  id              UUID        PRIMARY KEY,
  tenant_id       UUID        NOT NULL,
  po_id           UUID        NOT NULL,
  store_id        UUID        NOT NULL,
  received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  -- The caller's Idempotency-Key for the receipt, where one was sent: unique per tenant, so a retried
  -- receipt is one receipt.
  idempotency_key VARCHAR(255)
);
CREATE INDEX idx_gr_tenant ON goods_receipts(tenant_id);
-- The receipts of one order. The outstanding-quantity query joins receipt lines to their receipt to
-- reach the PO, and does it inside the receive transaction, so it is on the hot path of every
-- delivery; the three-way match, the order's list of deliveries and the supplier scorecard read the
-- same pair.
CREATE INDEX idx_goods_receipts_po ON goods_receipts (tenant_id, po_id);
CREATE UNIQUE INDEX uq_goods_receipt_idempotency
    ON goods_receipts (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- ── GRN Lines ─────────────────────────────────────────────────────────────────
-- goods_receipt_lines carries no po_line_id: a delivery note names products rather than order rows.
-- Receipt lines are matched to order lines by variant, so two lines on one order for the same
-- variant aggregate together.
CREATE TABLE goods_receipt_lines (
  id            UUID          PRIMARY KEY,
  tenant_id     UUID          NOT NULL,
  gr_id         UUID          NOT NULL REFERENCES goods_receipts(id),
  variant_id    UUID          NOT NULL,
  qty_received  NUMERIC(14,3) NOT NULL,
  created_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_grl_tenant ON goods_receipt_lines(tenant_id);
CREATE INDEX idx_grl_gr     ON goods_receipt_lines(tenant_id, gr_id);
CREATE INDEX idx_goods_receipt_lines_gr ON goods_receipt_lines (tenant_id, gr_id, variant_id);

-- ── Intercompany Invoices (Gap #20: Oracle Inventory Ch. 19) ─────────────────
-- AR = Accounts Receivable raised by sending store
-- AP = Accounts Payable raised by receiving store
-- payment_due_date = invoice_date + payment_terms_days (BACS default 30)
-- vat_disregarded = true when both stores share a group VAT registration (HMRC VAT Notice 700/2)
-- Deliberately unrelated to supplier invoices: store to store inside one tenant, with no supplier and
-- nothing to match against.
CREATE TABLE intercompany_invoices (
  id               UUID          PRIMARY KEY,
  tenant_id        UUID          NOT NULL,
  invoice_type     VARCHAR(10)   NOT NULL,
  from_store_id    UUID          NOT NULL,
  to_store_id      UUID          NOT NULL,
  transfer_ref     UUID,
  net_amount       NUMERIC       NOT NULL,
  vat_amount       NUMERIC       NOT NULL DEFAULT 0,
  gross_amount     NUMERIC       NOT NULL,
  vat_code         VARCHAR(10)   NOT NULL DEFAULT 'T1',
  vat_disregarded  BOOLEAN       NOT NULL DEFAULT false,
  status           VARCHAR(20)   NOT NULL DEFAULT 'RAISED',
  invoice_date     DATE          NOT NULL DEFAULT CURRENT_DATE,
  payment_due_date DATE          NOT NULL,
  currency         CHAR(3)       NOT NULL,
  created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
  CONSTRAINT chk_ii_type   CHECK (invoice_type IN ('AR','AP')),
  CONSTRAINT chk_ii_status CHECK (status IN ('RAISED','SETTLED'))
);
CREATE INDEX idx_ii_tenant      ON intercompany_invoices(tenant_id);
CREATE INDEX idx_ii_tenant_type ON intercompany_invoices(tenant_id, invoice_type);
CREATE INDEX idx_ii_tenant_from ON intercompany_invoices(tenant_id, from_store_id);
CREATE INDEX idx_ii_transfer     ON intercompany_invoices(tenant_id, transfer_ref) WHERE transfer_ref IS NOT NULL;

-- ── Nominal Ledger Entries (FRS 102 / UK GAAP, double-entry, append-only) ────
-- Nominal codes follow Sage/Xero UK standard chart:
--   1100 Trade Debtors Control   1200 Bank Current Account
--   2100 Trade Creditors Control 2200 VAT Output  2201 VAT Input
--   4000 Sales - Intercompany    5000 Purchases - Intercompany
-- INVARIANT: debit = credit across entries for same source_ref (balanced journal)
-- NO UPDATE or DELETE paths — this table is append-only per FRS 102 s.2.51
--
-- The accounting seam. The ledger records what the business buys and sells, not only what happens
-- between its stores:
--   goods receipt     Dr Stock (the store's mapped nominal code, or 1001)   Cr 2109 GR/IR
--   supplier invoice  Dr 2109 GR/IR net, Dr 2201 VAT input                  Cr 2100 Creditors
--   credit note       Dr 2100 Creditors                                     Cr Stock, Cr 2201
--   rejection         the invoice's posting, reversed line for line
--   manual journal    whatever finance says, as long as it balances
-- The posting model is SAP's rather than "post on approval": an invoice with variances outside
-- tolerance is POSTED and BLOCKED, not held unposted. The liability exists the moment the supplier
-- has invoiced, whatever the buyer thinks of the figures; what a variance stops is payment.
--
-- Debit and credit are unconstrained NUMERIC (SJ-D25), as every money column here is.
CREATE TABLE nominal_ledger_entries (
  id            UUID          PRIMARY KEY,
  tenant_id     UUID          NOT NULL,
  entry_date    DATE          NOT NULL,
  nominal_code  VARCHAR(10)   NOT NULL,
  nominal_name  VARCHAR(100)  NOT NULL,
  debit         NUMERIC       NOT NULL DEFAULT 0,
  credit        NUMERIC       NOT NULL DEFAULT 0,
  description   VARCHAR(500)  NOT NULL,
  source_ref    UUID,
  created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
  -- The lines of one double-entry posting share a journal id, so a journal can be read back whole and
  -- a reversal can be built from it. Null for a row that is not part of a journal.
  journal_id    UUID,
  -- What produced the posting: one of the Domain.SOURCE_ constants (GOODS_RECEIPT,
  -- SUPPLIER_INVOICE, SALE, SALE_REFUND, GIFT_CARD_LOAD and so on). No check constrains the column,
  -- so those constants are the list. source_ref already says which document.
  source_type   VARCHAR(30),
  -- The store the posting belongs to, so a trial balance can be read per store. Null for a
  -- tenant-level journal.
  store_id      UUID
);
CREATE INDEX idx_nle_tenant      ON nominal_ledger_entries(tenant_id);
CREATE INDEX idx_nle_tenant_code ON nominal_ledger_entries(tenant_id, nominal_code);
CREATE INDEX idx_nle_tenant_date ON nominal_ledger_entries(tenant_id, entry_date);
CREATE INDEX idx_nle_tenant_journal ON nominal_ledger_entries (tenant_id, journal_id);
CREATE INDEX idx_nle_tenant_store_date ON nominal_ledger_entries (tenant_id, store_id, entry_date);
-- The clearing report groups the clearing account by order.
CREATE INDEX idx_nle_tenant_code_source ON nominal_ledger_entries (tenant_id, nominal_code, source_ref);

-- ── Outbox ────────────────────────────────────────────────────────────────────
-- The outbox is cross-tenant on purpose: the relay drains every business's rows in one created_at
-- order, so none of its indexes starts with tenant_id.
--
-- A row that fails to publish is retried after a backoff (storeql.outbox.backoff-base-seconds,
-- doubling, capped at storeql.outbox.backoff-cap-seconds), and only that row's aggregate waits for
-- it. After storeql.outbox.max-attempts it is a dead letter: never claimed again, kept for an
-- operator, and it holds back its own aggregate only.
CREATE TABLE outbox (
  id              UUID        PRIMARY KEY,
  event_type      VARCHAR(100) NOT NULL,
  topic           VARCHAR(200) NOT NULL,
  tenant_id       UUID        NOT NULL,
  aggregate_id    UUID        NOT NULL,
  payload         TEXT        NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  published       BOOLEAN     NOT NULL DEFAULT false,
  published_at    TIMESTAMPTZ,
  attempts        INT         NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_error      TEXT,
  dead_at         TIMESTAMPTZ,
  CONSTRAINT chk_outbox_attempts CHECK (attempts >= 0),
  CONSTRAINT chk_outbox_dead_unpublished CHECK (dead_at IS NULL OR published_at IS NULL)
);

-- The relay claims the oldest rows that may publish now, a batch at a time, on each tick and again
-- at once while a batch comes back full (BaseOutboxRepository.claim). A row behind a dead or
-- backing-off row of its own aggregate is left for later:
--
--   SELECT o.id, o.aggregate_id, o.topic, o.payload FROM outbox o
--     WHERE o.published_at IS NULL AND o.dead_at IS NULL AND o.next_attempt_at <= now()
--       AND NOT EXISTS (SELECT 1 FROM outbox p WHERE p.aggregate_id = o.aggregate_id
--         AND p.published_at IS NULL AND (p.dead_at IS NOT NULL OR p.next_attempt_at > now())
--         AND (p.created_at, p.id) < (o.created_at, o.id))
--     ORDER BY o.created_at, o.id LIMIT ?
--
-- The relay marks published_at and leaves published false, so the partial indexes test published_at,
-- not the boolean.
--
-- The claim's index: the waiting rows that are not dead, in the order the claim's ORDER BY asks for,
-- so the rows of a batch come out in that order. The claim is the one statement that reads waiting
-- rows in that order: its check for an earlier row of the same aggregate reads the index below, the
-- relay marks a row published, and records a failure, by its id, and the purge reads only published
-- rows (idx_outbox_published, below). No index of every unpublished row by created_at
-- (idx_outbox_unpublished) is kept: it would also hold the dead letters, which the claim's ordered scan
-- never reads. OutboxPurgeIndexIT plans the claim as the repository prepares it and asserts this
-- index.
CREATE INDEX idx_outbox_claim
    ON outbox (created_at, id)
    WHERE published_at IS NULL AND dead_at IS NULL;

-- The per-aggregate check: an aggregate's earlier unpublished rows, and whether any is dead or
-- backing off.
CREATE INDEX idx_outbox_aggregate_pending
    ON outbox (aggregate_id, created_at, id)
    WHERE published_at IS NULL;

-- Once an hour the purge deletes, in batches of a thousand, the outbox rows that were published more
-- than a retention ago, and the processed_events rows past their own cutoff (indexed in V6). Each
-- outbox batch is this statement, oldest first:
--
--   DELETE FROM outbox WHERE id IN (SELECT id FROM outbox
--     WHERE published_at IS NOT NULL AND published_at < ? ORDER BY published_at ASC LIMIT ?
--     FOR UPDATE SKIP LOCKED)
--
-- A published row is the one the claim's index has let go of; a partial index on published_at holds
-- exactly those. The claim never reads it, and each row the relay publishes writes one entry into it.
CREATE INDEX idx_outbox_published
    ON outbox (published_at) WHERE published_at IS NOT NULL;
