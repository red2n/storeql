-- Sales read-model (CQRS projection), built from order and payment events. One row per confirmed
-- order; refunds accumulate so net = gross - refunded.
--
-- Money is kept at its own currency's minor units, never an assumed two places. A Kuwaiti,
-- Bahraini, Jordanian, Omani, Tunisian or Iraqi dinar has three decimal places (ISO 4217), and
-- Postgres rounds a value to the column's scale without a word, so a KWD 1.125 sale must not be
-- reported as 1.13. Four places hold any ISO 4217 currency's minor units (the most is four, CLF and
-- UYW), as unit_price does in sales_line_facts. The reports show each figure at its own currency's
-- units (Mappers.money), so pounds still read 12.50 and yen 1500.
--
-- A voided till sale leaves every sales report: the fact is MARKED, never deleted, because a voided
-- sale is still something that happened and a vanished row would be as hard to explain to an
-- auditor as the receipt order-svc refuses to remove. Every report that sums sales_facts (or its
-- lines) reads only the facts with no voided_at.
--
-- A return made without a receipt has no sale to refund against. order-svc announces it itself
-- (NoReceiptReturnRecorded), because payment-svc refunds only what a sale captured. The sales
-- reports must still show the refund on its day at its store, so it lands here as a row of its own:
-- keyed by the return (a UUIDv7 that is never an order id), gross zero, the refund in
-- refunded_amount. Sales, days and takings-against-labour then net it without a second table to keep
-- in step. It is flagged no_receipt, so the count of orders never includes it.
CREATE TABLE sales_facts (
    tenant_id       UUID          NOT NULL,
    order_id        UUID          NOT NULL,
    store_id        UUID,
    channel         TEXT,                          -- ONLINE | POS
    customer_id     UUID,
    gross_amount    NUMERIC(18,4) NOT NULL,
    refunded_amount NUMERIC(18,4) NOT NULL DEFAULT 0,
    currency        TEXT          NOT NULL,
    confirmed_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    voided_at       TIMESTAMPTZ,
    no_receipt      BOOLEAN       NOT NULL DEFAULT false,
    PRIMARY KEY (tenant_id, order_id)
);

-- Reports filter by tenant + a confirmed_at date range, optionally narrowing by store/channel.
CREATE INDEX idx_sales_facts_tenant_confirmed ON sales_facts (tenant_id, confirmed_at);

COMMENT ON COLUMN sales_facts.voided_at IS
    'When the void was heard (OrderVoided); null while the sale stands. A voided sale is left out of every sales report, its row kept.';
COMMENT ON COLUMN sales_facts.no_receipt IS
    'True for a return made without a receipt (NoReceiptReturnRecorded): order_id is the return, gross is zero, refunded_amount is the refund. Never counted as an order.';
