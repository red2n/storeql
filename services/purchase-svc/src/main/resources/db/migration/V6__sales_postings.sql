-- Sales and tender posting to revenue and control accounts (readiness review 17.7).
--
-- The ledger records what the business buys and what it sells. A till sale, the cash in the drawer, a
-- card payment and a refund reach order-svc and payment-svc, and this service owns the nominal ledger,
-- so it consumes the events that describe a sale and its money (OrderConfirmed, PaymentCaptured,
-- PaymentRefunded, NoReceiptReturnRecorded, the card disputes and the settlement) and posts them
-- through a receipts clearing account, which is how retail systems keep the takings honest when a
-- sale is paid in several parts:
--
--   each tender captured   Dr the tender's control account     Cr 1105 sales receipts clearing
--   the sale confirmed     Dr 1105 clearing (total)            Cr 4010 sales (net), Cr 2200 VAT output
--   a refund               Dr 4010 and 2200 by the sale's VAT ratio for the part the confirmed sale
--                          covers, Dr 1105 for the rest (all of it when the sale was never
--                          confirmed)                          Cr the refunded tender's control account
--
-- The sale, tender and refund postings carry the order as their source, so the receipts clearing
-- account nets to zero per order once a sale is paid and confirmed; anything left open is listed by
-- GET /nominal-ledger/sales-clearing. A gift card loaded in a sale (Dr 1105 / Cr 2310), and its
-- reversal when that sale is voided or cancelled (Dr 2310 / Cr 1105), post to 1105 with the order as
-- their source too (V7), so they net to zero on the order with the rest.

-- Consumer-level dedupe, as every consuming service keeps it (golden rule #7). The key is
-- (event_id, consumer), not event_id alone: one consumer's mark must not stop another consumer from
-- applying the same event (each consumer name is one purpose).
CREATE TABLE IF NOT EXISTS processed_events (
    event_id     UUID        NOT NULL,
    consumer     TEXT        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_processed_events PRIMARY KEY (event_id, consumer)
);

-- Written once per event handled and read only by its primary key, except the scheduled purge, which
-- deletes in batches oldest first (oldest processed_at): the plain index on the timestamp is the
-- whole cost, and it lets a batch find its rows without reading the table.
CREATE INDEX idx_processed_events_processed_at ON processed_events (processed_at);

-- A confirmed sale as order-svc announced it: what the refund's VAT share is worked out from.
CREATE TABLE sales_orders (
    tenant_id    UUID        NOT NULL,
    order_id     UUID        NOT NULL,
    store_id     UUID,
    currency     CHAR(3)     NOT NULL,
    total        NUMERIC     NOT NULL,
    tax_amount   NUMERIC     NOT NULL,
    confirmed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, order_id)
);

-- A tender as payment-svc captured it. One row per tender, so a tender announced twice under two
-- event deliveries is posted once.
CREATE TABLE sales_tenders (
    tenant_id   UUID        NOT NULL,
    payment_id  UUID        NOT NULL,
    order_id    UUID        NOT NULL,
    store_id    UUID,
    method      VARCHAR(30),
    amount      NUMERIC     NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, payment_id)
);
CREATE INDEX idx_sales_tenders_order ON sales_tenders (tenant_id, order_id);
