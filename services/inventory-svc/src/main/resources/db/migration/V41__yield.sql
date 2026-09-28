-- Fresh yield, preparation and butchery loss.
--
-- A yield template says what a primal (a side of beef, a whole salmon, a sack of potatoes) should
-- break into and what share is expected to be lost as bone, fat, skin and trim; a breakdown at a
-- store consumes the primal, makes each cut a batch of its own under the primal's lot with the
-- primal's cost apportioned across the cuts, and records the loss against what was expected. The
-- runs of a period are the butchery-loss report. Every id is minted by the service (UUIDv7).

CREATE TABLE yield_templates (
    id               UUID        NOT NULL,
    tenant_id        UUID        NOT NULL,
    name             TEXT        NOT NULL,
    input_variant_id UUID        NOT NULL,
    -- The unit the shares are read in (kg, each); a label for people, not arithmetic.
    unit             TEXT,
    notes            TEXT,
    active           BOOLEAN     NOT NULL DEFAULT TRUE,
    created_by       UUID,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at         TIMESTAMPTZ,
    CONSTRAINT pk_yield_templates PRIMARY KEY (id),
    CONSTRAINT ck_yield_template_name CHECK (length(trim(name)) > 0)
);
CREATE INDEX idx_yield_templates_tenant ON yield_templates (tenant_id, active, name);

CREATE TABLE yield_template_outputs (
    id              UUID          NOT NULL,
    tenant_id       UUID          NOT NULL,
    template_id     UUID          NOT NULL,
    variant_id      UUID          NOT NULL,
    -- The share of the input quantity this cut is expected to be, in percent.
    expected_pct    NUMERIC(7,3)  NOT NULL,
    -- The relative share of the input's cost this cut carries: by weight unless the business
    -- says otherwise (a fillet is worth more per kilo than the mince from the same side).
    cost_share      NUMERIC(9,3)  NOT NULL,
    -- The cut's own shelf life from the day it is made; null keeps the primal's date.
    shelf_life_days INT,
    sort_order      INT           NOT NULL,
    CONSTRAINT pk_yield_template_outputs PRIMARY KEY (id),
    CONSTRAINT uq_yield_template_output UNIQUE (template_id, variant_id),
    CONSTRAINT fk_yield_template_output FOREIGN KEY (template_id) REFERENCES yield_templates (id) ON DELETE CASCADE,
    CONSTRAINT ck_yield_output_pct CHECK (expected_pct > 0 AND expected_pct <= 100),
    CONSTRAINT ck_yield_output_share CHECK (cost_share >= 0),
    CONSTRAINT ck_yield_output_life CHECK (shelf_life_days IS NULL OR shelf_life_days > 0)
);
CREATE INDEX idx_yield_template_outputs_tenant ON yield_template_outputs (tenant_id, template_id, sort_order);

CREATE TABLE yield_runs (
    id                UUID          NOT NULL,
    tenant_id         UUID          NOT NULL,
    store_id          UUID          NOT NULL,
    template_id       UUID          NOT NULL,
    input_variant_id  UUID          NOT NULL,
    input_qty         NUMERIC(18,3) NOT NULL,
    -- What the primal cost, from the batches it was drawn from; null when any had no cost.
    input_cost        NUMERIC(18,2),
    output_qty        NUMERIC(18,3) NOT NULL,
    loss_qty          NUMERIC(18,3) NOT NULL,
    expected_loss_qty NUMERIC(18,3) NOT NULL,
    -- The loss at the primal's unit cost: what the bin took, for the report. The cuts absorb it.
    loss_at_cost      NUMERIC(18,2),
    reference         TEXT,
    notes             TEXT,
    recorded_by       UUID,
    recorded_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_yield_runs PRIMARY KEY (id),
    CONSTRAINT ck_yield_run_qty CHECK (input_qty > 0 AND output_qty >= 0 AND output_qty <= input_qty),
    CONSTRAINT ck_yield_run_loss CHECK (loss_qty = input_qty - output_qty)
);
CREATE INDEX idx_yield_runs_tenant ON yield_runs (tenant_id, store_id, recorded_at DESC);

CREATE TABLE yield_run_outputs (
    id           UUID          NOT NULL,
    tenant_id    UUID          NOT NULL,
    run_id       UUID          NOT NULL,
    variant_id   UUID          NOT NULL,
    qty          NUMERIC(18,3) NOT NULL,
    expected_qty NUMERIC(18,3) NOT NULL,
    unit_cost    NUMERIC(18,2),
    -- The batch the cut became; null when nothing of this cut came out.
    batch_id     UUID,
    CONSTRAINT pk_yield_run_outputs PRIMARY KEY (id),
    CONSTRAINT uq_yield_run_output UNIQUE (run_id, variant_id),
    CONSTRAINT fk_yield_run_output FOREIGN KEY (run_id) REFERENCES yield_runs (id) ON DELETE CASCADE,
    CONSTRAINT ck_yield_run_output_qty CHECK (qty >= 0)
);
CREATE INDEX idx_yield_run_outputs_tenant ON yield_run_outputs (tenant_id, run_id);
