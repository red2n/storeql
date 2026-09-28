-- Bonded and duty-suspended stock, the buyer's side.
--
-- A purchase order for excise goods may be placed under bond: the goods arrive at an approved
-- warehouse with the duty suspended, owned at cost without it. The receipt tells inventory-svc so
-- (GoodsReceived carries dutyStatus). When inventory-svc releases goods to home use it announces
-- DutyReleased with the duty it computed at the variant's rate; that duty is owed to the revenue
-- the day of the release — Excise Duty against Excise Duty Payable — and each announcement is
-- recorded once, so a redelivery owes nothing twice. The releases of a period are what an excise
-- return is made from.

ALTER TABLE purchase_orders ADD COLUMN duty_status TEXT NOT NULL DEFAULT 'DUTY_PAID';
ALTER TABLE purchase_orders
    ADD CONSTRAINT ck_po_duty_status CHECK (duty_status IN ('DUTY_PAID', 'DUTY_SUSPENDED'));

CREATE TABLE duty_releases (
    id            UUID        PRIMARY KEY,
    tenant_id     UUID        NOT NULL,
    event_id      UUID        NOT NULL,                      -- inventory-svc's announcement, once
    release_id    UUID,                                      -- inventory-svc's release, referenced
    store_id      UUID,
    variant_id    UUID        NOT NULL,
    qty           NUMERIC     NOT NULL,
    duty_per_unit NUMERIC     NOT NULL,
    duty_amount   NUMERIC     NOT NULL,
    currency      CHAR(3)     NOT NULL,
    reference     TEXT,
    released_on   DATE        NOT NULL,
    recorded_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_duty_release_event UNIQUE (tenant_id, event_id),
    CONSTRAINT chk_duty_release_qty CHECK (qty > 0),
    CONSTRAINT chk_duty_release_amount CHECK (duty_amount >= 0)
);
CREATE INDEX ix_duty_releases_period ON duty_releases (tenant_id, released_on DESC, id);
