-- Cross-docking (intent/cross-docking.md): a line of a warehouse's purchase order allocated to the
-- shops the warehouse serves (inventory-svc's network, read over REST, never joined). Set while the
-- order is a DRAFT; announced to inventory-svc as a snapshot when the order is submitted, and as an
-- empty one when it is rejected, cancelled or closed short. Every id is bound by the service.
CREATE TABLE purchase_order_line_allocations (
    id          UUID          NOT NULL,
    tenant_id   UUID          NOT NULL,
    po_id       UUID          NOT NULL,
    po_line_id  UUID          NOT NULL,
    store_id    UUID          NOT NULL,
    qty         NUMERIC(18,3) NOT NULL,
    created_by  UUID,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_po_line_allocations PRIMARY KEY (id),
    CONSTRAINT uq_po_line_allocation UNIQUE (tenant_id, po_line_id, store_id),
    CONSTRAINT ck_po_line_allocation_qty CHECK (qty > 0)
);
CREATE INDEX idx_po_line_allocations_po ON purchase_order_line_allocations (tenant_id, po_id);
