-- RFQ and sourcing.
--
-- A buyer asks several suppliers to quote for the same lines, records what each says, compares the
-- quotes in the business's own money, and awards the lines — which raises a draft purchase order
-- per supplier at the quoted prices. Every id is minted by the service (UUIDv7).

CREATE TABLE rfq_series (
    tenant_id   UUID   NOT NULL,
    next_number BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT pk_rfq_series PRIMARY KEY (tenant_id),
    CONSTRAINT ck_rfq_series_next CHECK (next_number >= 1)
);

CREATE TABLE rfqs (
    id               UUID        NOT NULL,
    tenant_id        UUID        NOT NULL,
    reference        TEXT        NOT NULL,
    title            TEXT        NOT NULL,
    -- The store the goods are for; tenant-svc's id, referenced never joined.
    store_id         UUID        NOT NULL,
    status           TEXT        NOT NULL,
    needed_by        DATE,
    -- The day quotes are due; advisory, the buyer closes the request by awarding or cancelling.
    closes_on        DATE,
    notes            TEXT,
    created_by       UUID,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    issued_at        TIMESTAMPTZ,
    awarded_at       TIMESTAMPTZ,
    awarded_by       UUID,
    cancelled_at     TIMESTAMPTZ,
    cancelled_reason TEXT,
    CONSTRAINT pk_rfqs PRIMARY KEY (id),
    CONSTRAINT uq_rfq_reference UNIQUE (tenant_id, reference),
    CONSTRAINT ck_rfq_status CHECK (status IN ('DRAFT', 'ISSUED', 'AWARDED', 'CANCELLED')),
    CONSTRAINT ck_rfq_title CHECK (length(trim(title)) > 0)
);
CREATE INDEX idx_rfqs_tenant ON rfqs (tenant_id, status, created_at DESC);

CREATE TABLE rfq_lines (
    id         UUID          NOT NULL,
    tenant_id  UUID          NOT NULL,
    rfq_id     UUID          NOT NULL,
    variant_id UUID          NOT NULL,
    qty        NUMERIC(14,3) NOT NULL,
    sort_order INT           NOT NULL,
    notes      TEXT,
    CONSTRAINT pk_rfq_lines PRIMARY KEY (id),
    CONSTRAINT uq_rfq_line UNIQUE (rfq_id, variant_id),
    CONSTRAINT fk_rfq_line_rfq FOREIGN KEY (rfq_id) REFERENCES rfqs (id) ON DELETE CASCADE,
    CONSTRAINT ck_rfq_line_qty CHECK (qty > 0)
);
CREATE INDEX idx_rfq_lines_tenant ON rfq_lines (tenant_id, rfq_id, sort_order);

-- One row per supplier asked: invited, then quoted or declined. A quote's terms live here; its
-- prices per line below.
CREATE TABLE rfq_suppliers (
    id             UUID        NOT NULL,
    tenant_id      UUID        NOT NULL,
    rfq_id         UUID        NOT NULL,
    supplier_id    UUID        NOT NULL,
    status         TEXT        NOT NULL DEFAULT 'INVITED',
    currency       CHAR(3),
    lead_time_days INT,
    valid_until    DATE,
    notes          TEXT,
    quoted_at      TIMESTAMPTZ,
    CONSTRAINT pk_rfq_suppliers PRIMARY KEY (id),
    CONSTRAINT uq_rfq_supplier UNIQUE (rfq_id, supplier_id),
    CONSTRAINT fk_rfq_supplier_rfq FOREIGN KEY (rfq_id) REFERENCES rfqs (id) ON DELETE CASCADE,
    CONSTRAINT fk_rfq_supplier_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (id),
    CONSTRAINT ck_rfq_supplier_status CHECK (status IN ('INVITED', 'QUOTED', 'DECLINED')),
    CONSTRAINT ck_rfq_supplier_lead CHECK (lead_time_days IS NULL OR lead_time_days >= 0)
);
CREATE INDEX idx_rfq_suppliers_tenant ON rfq_suppliers (tenant_id, rfq_id);
CREATE INDEX idx_rfq_suppliers_supplier ON rfq_suppliers (tenant_id, supplier_id);

CREATE TABLE rfq_quote_lines (
    id              UUID    NOT NULL,
    tenant_id       UUID    NOT NULL,
    rfq_supplier_id UUID    NOT NULL,
    rfq_line_id     UUID    NOT NULL,
    unit_price      NUMERIC NOT NULL,
    CONSTRAINT pk_rfq_quote_lines PRIMARY KEY (id),
    CONSTRAINT uq_rfq_quote_line UNIQUE (rfq_supplier_id, rfq_line_id),
    CONSTRAINT fk_rfq_quote_supplier FOREIGN KEY (rfq_supplier_id) REFERENCES rfq_suppliers (id) ON DELETE CASCADE,
    CONSTRAINT fk_rfq_quote_line FOREIGN KEY (rfq_line_id) REFERENCES rfq_lines (id) ON DELETE CASCADE,
    CONSTRAINT ck_rfq_quote_price CHECK (unit_price >= 0)
);
CREATE INDEX idx_rfq_quote_lines_tenant ON rfq_quote_lines (tenant_id, rfq_supplier_id);

-- A line awarded once, to one supplier, at the price they quoted, on the order it raised.
CREATE TABLE rfq_awards (
    id          UUID        NOT NULL,
    tenant_id   UUID        NOT NULL,
    rfq_id      UUID        NOT NULL,
    rfq_line_id UUID        NOT NULL,
    supplier_id UUID        NOT NULL,
    po_id       UUID        NOT NULL,
    unit_price  NUMERIC     NOT NULL,
    currency    CHAR(3)     NOT NULL,
    awarded_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_rfq_awards PRIMARY KEY (id),
    CONSTRAINT uq_rfq_award_line UNIQUE (rfq_line_id),
    CONSTRAINT fk_rfq_award_rfq FOREIGN KEY (rfq_id) REFERENCES rfqs (id) ON DELETE CASCADE,
    CONSTRAINT fk_rfq_award_line FOREIGN KEY (rfq_line_id) REFERENCES rfq_lines (id) ON DELETE CASCADE
);
CREATE INDEX idx_rfq_awards_tenant ON rfq_awards (tenant_id, rfq_id);

-- An order may now come from an award.
ALTER TABLE purchase_orders DROP CONSTRAINT IF EXISTS ck_po_source;
ALTER TABLE purchase_orders
    ADD CONSTRAINT ck_po_source CHECK (source IN ('MANUAL', 'PROPOSAL', 'DROPSHIP', 'RFQ'));
