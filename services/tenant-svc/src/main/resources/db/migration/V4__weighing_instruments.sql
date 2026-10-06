-- Weighing instruments used for trade, and their verification history.
--
--   Weights and Measures Act 1985 s.11          using for trade a weighing instrument that has not
--                                               been passed as fit for use for trade and stamped is
--                                               an offence; so is using one whose stamp has been
--                                               obliterated by repair or adjustment
--   Non-automatic Weighing Instruments Regs 2016 conformity assessment of the instrument itself
--   Weights and Measures Act 1985 s.7 / s.8     the trading-standards inspector's power to test and
--                                               to require re-verification
--
-- The till has sold by weight from "the reading the approved scale shows" since 816640d. What it
-- never had was a way to know which scale that was, or whether it was approved. This is the
-- register: every instrument a store weighs for trade on, with what it is, where it is, and an
-- append-only history of every verification, inspection and repair — because a repair breaks the
-- stamp and the instrument is not fit for trade again until it is re-verified. From here the till
-- can refuse to sell by weight from an instrument that is not in service and certified, and a
-- sold-by-weight line can record which instrument produced its reading.

CREATE TABLE weighing_instruments (
    id             UUID          PRIMARY KEY,
    tenant_id      UUID          NOT NULL REFERENCES tenants (id),
    store_id       UUID          NOT NULL REFERENCES stores (id),
    -- The shop's own name for it: "Deli counter scale", "Checkout 3 scale".
    identifier     TEXT          NOT NULL,
    serial_number  TEXT          NOT NULL,
    make           TEXT,
    model          TEXT,
    -- COUNTER: read by a person at the till; LABELLING: prints a price or weight barcode the till
    -- scans; PLATFORM, HANGING: the same law, a different shape.
    kind           TEXT          NOT NULL DEFAULT 'COUNTER',
    -- Max capacity and the verification scale interval e, as marked on the instrument's plate.
    max_capacity   NUMERIC(18,4),
    capacity_uom   TEXT,
    scale_interval NUMERIC(18,4),
    -- The type-approval / conformity certificate reference on the plate.
    approval_ref   TEXT,
    zone_id        UUID          REFERENCES zones (id),
    -- For a LABELLING scale: how it encodes a price or a weight in the barcode it prints, so the
    -- till can read the label instead of asking the cashier to key the reading. JSON, because the
    -- conventions differ by country and vendor: which two-digit prefixes are the shop's own, how
    -- many digits name the item, whether the number that follows is a price or a weight, and its
    -- decimal places. Null for every other kind, and for a labelling scale nobody has set up yet.
    label_scheme   TEXT,
    status         TEXT          NOT NULL DEFAULT 'IN_SERVICE',
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_wi_kind CHECK (kind IN ('COUNTER', 'LABELLING', 'PLATFORM', 'HANGING')),
    CONSTRAINT chk_wi_status CHECK (status IN ('IN_SERVICE', 'OUT_OF_SERVICE', 'RETIRED')),
    CONSTRAINT chk_wi_capacity CHECK (max_capacity IS NULL OR max_capacity > 0),
    CONSTRAINT chk_wi_capacity_uom CHECK ((max_capacity IS NULL) = (capacity_uom IS NULL)),
    CONSTRAINT chk_wi_scheme CHECK (label_scheme IS NULL OR kind = 'LABELLING'),
    CONSTRAINT uq_wi_identifier UNIQUE (tenant_id, store_id, identifier),
    CONSTRAINT uq_wi_serial UNIQUE (tenant_id, serial_number)
);
CREATE INDEX idx_wi_store ON weighing_instruments (tenant_id, store_id, status);

-- Append-only: never UPDATE or DELETE (golden rule #8). This is the evidence an inspector reads.
CREATE TABLE weighing_instrument_verifications (
    id              UUID        PRIMARY KEY,
    tenant_id       UUID        NOT NULL,
    instrument_id   UUID        NOT NULL REFERENCES weighing_instruments (id),
    -- INITIAL: passed as fit for trade and stamped; RE_VERIFICATION: after repair or adjustment,
    -- or on the verifier's schedule; INSPECTION: a trading-standards test; REPAIR: work that
    -- breaks the stamp — recorded so the register shows the instrument was out of trade from then.
    kind            TEXT        NOT NULL,
    performed_on    DATE        NOT NULL,
    -- Who did it: the approved verifier or the inspector, by name and organisation.
    performed_by    TEXT        NOT NULL,
    certificate_ref TEXT,
    -- Whether the instrument was passed. A REPAIR is never a pass: it needs a verification after.
    passed          BOOLEAN     NOT NULL,
    -- When the verifier says it is due again; null when the instrument is verified until repaired.
    next_due        DATE,
    notes           TEXT,
    recorded_by     UUID,
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_wiv_kind CHECK (kind IN ('INITIAL', 'RE_VERIFICATION', 'INSPECTION', 'REPAIR')),
    CONSTRAINT chk_wiv_repair_not_pass CHECK (kind <> 'REPAIR' OR passed = FALSE),
    CONSTRAINT chk_wiv_due CHECK (next_due IS NULL OR next_due >= performed_on)
);
CREATE INDEX idx_wiv_instrument
    ON weighing_instrument_verifications (tenant_id, instrument_id, performed_on DESC, recorded_at DESC);

