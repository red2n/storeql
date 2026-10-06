-- Recall notice to buyers (readiness review 05.10).
--
--   Regulation (EU) 2023/988 art.35   an economic operator with customer data at its disposal
--                                     directly notifies every affected consumer it can identify
--   art.36                            what the notice says: the headline "Product safety recall",
--                                     the product and its lot, the hazard without words that play
--                                     it down, what to do, the remedies, a free contact
--   art.37                            at least two of repair, replacement and refund, the consumer
--                                     choosing, unless only one is possible or proportionate
--
-- A recall takes stock off sale and tells the stores. It also tells the people who had bought the
-- product, because every sale drew its stock from a batch this service knows the lot and date of,
-- under an order id. So a recall finds those sales as it opens and announces each order to order-svc,
-- which knows the buyer; the notice's remedies and contact ride with it (recalls, V27), and the recall
-- keeps the sales it found as evidence of who was reached.

-- Every sale a recall found in its scope: which order drew which batch, and how sure the recall is
-- that the pack was affected. Append-only; the record of who was reached.
CREATE TABLE recall_sales (
    id          UUID          PRIMARY KEY,
    tenant_id   UUID          NOT NULL,
    recall_id   UUID          NOT NULL REFERENCES recalls (id),
    order_id    UUID          NOT NULL,
    store_id    UUID          NOT NULL,
    variant_id  UUID          NOT NULL,
    batch_id    UUID          NOT NULL REFERENCES inventory_batches (id),
    batch_no    TEXT,
    expiry_date DATE,
    qty         NUMERIC(18,3) NOT NULL,
    sold_at     TIMESTAMPTZ   NOT NULL,
    match_type  TEXT          NOT NULL,
    CONSTRAINT chk_recall_sale_qty CHECK (qty > 0),
    CONSTRAINT chk_recall_sale_match CHECK (match_type IN ('IN_SCOPE', 'LOT_UNKNOWN', 'DATE_UNKNOWN')),
    -- One line per movement: a retried open finds the recall it already made, never a second line.
    CONSTRAINT uq_recall_sale UNIQUE (tenant_id, recall_id, order_id, batch_id)
);
CREATE INDEX idx_recall_sales_recall ON recall_sales (tenant_id, recall_id, order_id);
