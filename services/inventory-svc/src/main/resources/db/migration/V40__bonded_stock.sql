-- Bonded and duty-suspended stock (readiness review, Inventory & stock control).
--
-- Excise goods — spirits, wine, beer, tobacco — may be held in an approved warehouse with the duty
-- suspended: the business owns them at cost without the duty, and the duty crystallises only when
-- they are released to home use. Until now every batch was silently duty-paid: a bonded warehouse
-- could not say so, a release had no movement of its own, and the duty owed on it was nobody's
-- figure.
--
-- A store is approved as a bonded warehouse (the revenue's approval number; excise or customs
-- regime) and only then takes duty-suspended stock. A batch says whether its duty is paid or
-- suspended; suspended stock is on hand but never available — no hold, no sale, no transfer draws
-- it. A release to home use draws suspended batches FIFO into duty-paid batches of their own, in a
-- BOND_RELEASE movement, computes the duty at the variant's rate (the duty one unit crystallises,
-- set by management — the platform derives no rate from strength or volume) and announces
-- DutyReleased, which purchase-svc owes to the revenue.

CREATE TABLE bond_approvals (
    tenant_id       UUID        NOT NULL,
    store_id        UUID        NOT NULL,                  -- tenant-svc's store, referenced
    approval_number TEXT        NOT NULL,                  -- the revenue's approval of the warehouse
    regime          TEXT        NOT NULL,                  -- EXCISE | CUSTOMS
    active          BOOLEAN     NOT NULL DEFAULT TRUE,
    created_by      UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at        TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, store_id),
    CONSTRAINT chk_bond_regime CHECK (regime IN ('EXCISE', 'CUSTOMS'))
);

-- The duty one unit of a variant crystallises on release, in the business's home currency.
CREATE TABLE excise_duty_rates (
    tenant_id     UUID        NOT NULL,
    variant_id    UUID        NOT NULL,                    -- product-svc's variant, referenced
    duty_per_unit NUMERIC     NOT NULL,
    currency      CHAR(3)     NOT NULL,
    note          TEXT,                                    -- how the figure was arrived at
    updated_by    UUID,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, variant_id),
    CONSTRAINT chk_duty_per_unit CHECK (duty_per_unit >= 0)
);

ALTER TABLE inventory_batches
    ADD COLUMN duty_status TEXT NOT NULL DEFAULT 'DUTY_PAID',
    ADD CONSTRAINT chk_batch_duty_status CHECK (duty_status IN ('DUTY_PAID', 'DUTY_SUSPENDED'));
CREATE INDEX idx_batches_in_bond
    ON inventory_batches (tenant_id, store_id, variant_id) WHERE duty_status = 'DUTY_SUSPENDED';

-- Each release to home use: what left bond, at what rate, owing what. Append-only.
CREATE TABLE bond_releases (
    id            UUID        PRIMARY KEY,
    tenant_id     UUID        NOT NULL,
    store_id      UUID        NOT NULL,
    variant_id    UUID        NOT NULL,
    qty           NUMERIC(18,3) NOT NULL,
    duty_per_unit NUMERIC     NOT NULL,
    duty_amount   NUMERIC     NOT NULL,
    currency      CHAR(3)     NOT NULL,
    reference     TEXT,                                    -- the return or warrant it belongs to
    released_by   UUID,
    released_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_bond_release_qty CHECK (qty > 0)
);
CREATE INDEX idx_bond_releases_period ON bond_releases (tenant_id, released_at DESC, id);
