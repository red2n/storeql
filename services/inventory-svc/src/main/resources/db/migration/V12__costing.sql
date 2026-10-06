CREATE TABLE costing_methods (
    id             UUID           NOT NULL,
    tenant_id      UUID           NOT NULL,
    store_id       UUID           NOT NULL,
    variant_id     UUID           NOT NULL,
    method         TEXT           NOT NULL DEFAULT 'AVERAGE',
    average_cost   NUMERIC(18,6)  NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT pk_costing_methods  PRIMARY KEY (id),
    CONSTRAINT uq_costing_method   UNIQUE (tenant_id, store_id, variant_id),
    CONSTRAINT chk_costing_method  CHECK (method IN ('FIFO','AVERAGE'))
);
CREATE INDEX idx_costing_tenant ON costing_methods (tenant_id, store_id);

CREATE TABLE accounting_periods (
    id          UUID        NOT NULL,
    tenant_id   UUID        NOT NULL,
    store_id    UUID        NOT NULL,
    period_name TEXT        NOT NULL,
    period_date DATE        NOT NULL,
    status      TEXT        NOT NULL DEFAULT 'OPEN',
    opened_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at   TIMESTAMPTZ,
    CONSTRAINT pk_accounting_periods PRIMARY KEY (id),
    CONSTRAINT uq_accounting_period  UNIQUE (tenant_id, store_id, period_date),
    CONSTRAINT chk_period_status     CHECK (status IN ('OPEN','CLOSED'))
);
CREATE INDEX idx_acct_period_tenant ON accounting_periods (tenant_id, store_id, period_date DESC);
