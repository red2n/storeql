-- Cross-docking (intent/cross-docking.md). What each shop is owed of each product on a warehouse's
-- purchase order, as purchase-svc last announced it (a snapshot per order, replaced whole), drawn
-- down as the deliveries arrive; counted as on its way to the shop until then. A cross-dock
-- transfer names the order and the receipt it came from, and each line the batch it ships from.
-- Every id is bound by the service; the order and receipt ids are purchase-svc's, referenced.
CREATE TABLE crossdock_expected (
    id                UUID          NOT NULL,
    tenant_id         UUID          NOT NULL,
    purchase_order_id UUID          NOT NULL,
    warehouse_id      UUID          NOT NULL,
    store_id          UUID          NOT NULL,
    variant_id        UUID          NOT NULL,
    qty               NUMERIC(18,3) NOT NULL,
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_crossdock_expected PRIMARY KEY (id),
    CONSTRAINT uq_crossdock_expected UNIQUE (tenant_id, purchase_order_id, store_id, variant_id),
    CONSTRAINT ck_crossdock_expected_qty CHECK (qty > 0)
);
CREATE INDEX idx_crossdock_expected_store ON crossdock_expected (tenant_id, store_id, variant_id);

ALTER TABLE transfer_orders DROP CONSTRAINT chk_transfer_source;
ALTER TABLE transfer_orders ADD CONSTRAINT chk_transfer_source
    CHECK (source IN ('MANUAL','PROPOSAL','CROSSDOCK'));
ALTER TABLE transfer_orders ADD COLUMN purchase_order_id UUID;
ALTER TABLE transfer_orders ADD COLUMN goods_receipt_id UUID;
ALTER TABLE transfer_order_lines ADD COLUMN source_batch_id UUID;
