-- Food-safety checks: temperature monitoring (the cold chain) and HACCP due-diligence records.
--
-- One model serves both, because a temperature reading is a HACCP check with a number and limits,
-- and an opening check is one with a yes or no. What the law asks for is the same in both cases:
-- that the check was made, by whom, when, against what limit, and what was done when it failed.
--
--   Food Safety and Hygiene (England) Regulations 2013, Sch.4   chilled food at or below 8 °C,
--                                                              hot food held at or above 63 °C
--   Regulation (EC) 852/2004 art.5 (retained)                  HACCP-based procedures and records
--   Food Safety Act 1990 s.21                                  due diligence: these records are
--                                                              the defence
--
-- Records and corrective actions are append-only, like stock_movements: a check that was made is
-- never edited, and a limit that later changes must not rewrite what the limit was at the time,
-- which is why each record carries the limits it was judged against.

CREATE TABLE fs_check_types (
    id         UUID        PRIMARY KEY,
    -- NULL for the platform's reference types, which no tenant can edit; a tenant adds its own.
    tenant_id  UUID,
    code       TEXT        NOT NULL,
    name       TEXT        NOT NULL,
    kind       TEXT        NOT NULL,
    min_value  NUMERIC(6,2),
    max_value  NUMERIC(6,2),
    unit       TEXT,
    basis      TEXT,
    -- True only where the limit is law rather than guidance, so a screen never presents FSA advice
    -- as a statutory requirement.
    statutory  BOOLEAN     NOT NULL DEFAULT FALSE,
    active     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_fs_type_kind CHECK (kind IN ('TEMPERATURE', 'PASS_FAIL')),
    CONSTRAINT chk_fs_type_unit CHECK ((kind = 'TEMPERATURE') = (unit IS NOT NULL)),
    CONSTRAINT chk_fs_type_limits CHECK (
        (kind = 'TEMPERATURE' AND (min_value IS NOT NULL OR max_value IS NOT NULL))
        OR (kind = 'PASS_FAIL' AND min_value IS NULL AND max_value IS NULL)),
    CONSTRAINT chk_fs_type_range CHECK (min_value IS NULL OR max_value IS NULL OR min_value <= max_value)
);
CREATE UNIQUE INDEX uq_fs_check_types_code
    ON fs_check_types (tenant_id, code) NULLS NOT DISTINCT;

INSERT INTO fs_check_types (id, tenant_id, code, name, kind, min_value, max_value, unit, basis, statutory) VALUES
 ('01a090a0-1bc3-70b9-a4f2-357a63f90f41', NULL, 'CHILLED_STORAGE', 'Chilled storage', 'TEMPERATURE', NULL, 8.00, 'C',
  'Food Safety and Hygiene (England) Regulations 2013, Sch.4: chilled food at or below 8 °C', TRUE),
 ('01a090a0-1bc3-70ba-b5cb-636f3ad926be', NULL, 'FROZEN_STORAGE', 'Frozen storage', 'TEMPERATURE', NULL, -18.00, 'C',
  'Quick-frozen foodstuffs kept at or below −18 °C', FALSE),
 ('01a090a0-1bc3-70bb-ac46-5c62a30a895d', NULL, 'HOT_HOLDING', 'Hot holding', 'TEMPERATURE', 63.00, NULL, 'C',
  'Food Safety and Hygiene (England) Regulations 2013, Sch.4: hot food held at or above 63 °C', TRUE),
 ('01a090a0-1bc3-70bc-8922-274dfb9f853c', NULL, 'COOKING_CORE', 'Cooking: core temperature', 'TEMPERATURE', 75.00, NULL, 'C',
  'FSA guidance: a core temperature of 75 °C, or an equivalent time and temperature', FALSE),
 ('01a090a0-1bc3-70bd-8df6-1714b7665d5f', NULL, 'DELIVERY_CHILLED', 'Delivery: chilled', 'TEMPERATURE', NULL, 8.00, 'C',
  'Food Safety and Hygiene (England) Regulations 2013, Sch.4: chilled food at or below 8 °C', TRUE),
 ('01a090a0-1bc3-70be-8eb1-c6d004351829', NULL, 'DELIVERY_FROZEN', 'Delivery: frozen', 'TEMPERATURE', NULL, -15.00, 'C',
  'Accepting a frozen delivery: stored at −18 °C, with a brief rise to −15 °C tolerated in transit', FALSE),
 ('01a090a0-1bc3-70bf-ab53-eb45b1289166', NULL, 'OPENING_CHECKS', 'Opening checks', 'PASS_FAIL', NULL, NULL, NULL,
  'Safer Food, Better Business daily diary', FALSE),
 ('01a090a0-1bc3-70c0-9dba-fe3acc9b334b', NULL, 'CLOSING_CHECKS', 'Closing checks', 'PASS_FAIL', NULL, NULL, NULL,
  'Safer Food, Better Business daily diary', FALSE),
 ('01a090a0-1bc3-70c1-b9d5-729021ffb57b', NULL, 'CLEANING', 'Cleaning schedule', 'PASS_FAIL', NULL, NULL, NULL,
  'Safer Food, Better Business cleaning schedule', FALSE),
 ('01a090a0-1bc3-70c2-90a7-6ccee7feffd8', NULL, 'PEST_CHECK', 'Pest check', 'PASS_FAIL', NULL, NULL, NULL,
  'Safer Food, Better Business pest control', FALSE);

-- A chiller, a freezer, a hot cabinet, a goods-in bay, or a store's daily checklist. Its limits may
-- be stricter than its type's, never laxer; the service enforces that, the schema only the range.
CREATE TABLE fs_monitoring_points (
    id              UUID        PRIMARY KEY,
    tenant_id       UUID        NOT NULL,
    store_id        UUID        NOT NULL,
    zone_id         UUID,
    name            TEXT        NOT NULL,
    check_type_id   UUID        NOT NULL REFERENCES fs_check_types (id),
    min_value       NUMERIC(6,2),
    max_value       NUMERIC(6,2),
    frequency_hours INTEGER     NOT NULL,
    active          BOOLEAN     NOT NULL DEFAULT TRUE,
    created_by      UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_fs_point_frequency CHECK (frequency_hours BETWEEN 1 AND 744),
    CONSTRAINT chk_fs_point_range CHECK (min_value IS NULL OR max_value IS NULL OR min_value <= max_value)
);
CREATE INDEX idx_fs_points_store ON fs_monitoring_points (tenant_id, store_id);
CREATE UNIQUE INDEX uq_fs_points_name ON fs_monitoring_points (tenant_id, store_id, lower(name));

-- Who switched a point off or on, and why. A table rather than columns on the point, because a
-- point is switched repeatedly and only a trail can say who turned it back on (the SJ-D33 shape).
CREATE TABLE fs_point_status_changes (
    id         UUID        PRIMARY KEY,
    tenant_id  UUID        NOT NULL,
    point_id   UUID        NOT NULL REFERENCES fs_monitoring_points (id),
    active     BOOLEAN     NOT NULL,
    reason     TEXT        NOT NULL,
    changed_by UUID,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_fs_point_status_point ON fs_point_status_changes (tenant_id, point_id, changed_at DESC);

CREATE TABLE fs_check_records (
    id              UUID        PRIMARY KEY,
    tenant_id       UUID        NOT NULL,
    store_id        UUID        NOT NULL,
    point_id        UUID        NOT NULL REFERENCES fs_monitoring_points (id),
    check_type_id   UUID        NOT NULL REFERENCES fs_check_types (id),
    kind            TEXT        NOT NULL,
    value           NUMERIC(6,2),
    unit            TEXT,
    -- The limits as they stood when the check was judged.
    min_value       NUMERIC(6,2),
    max_value       NUMERIC(6,2),
    result          TEXT        NOT NULL,
    notes           TEXT,
    ref_type        TEXT,
    ref_id          UUID,
    recorded_by     UUID        NOT NULL,
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    idempotency_key TEXT,
    CONSTRAINT chk_fs_record_kind CHECK (kind IN ('TEMPERATURE', 'PASS_FAIL')),
    CONSTRAINT chk_fs_record_result CHECK (result IN ('PASS', 'FAIL')),
    CONSTRAINT chk_fs_record_value CHECK ((kind = 'TEMPERATURE') = (value IS NOT NULL))
);
CREATE INDEX idx_fs_records_store_time ON fs_check_records (tenant_id, store_id, recorded_at DESC, id DESC);
CREATE INDEX idx_fs_records_point_time ON fs_check_records (tenant_id, point_id, recorded_at DESC);
CREATE UNIQUE INDEX uq_fs_records_idempotency
    ON fs_check_records (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL;

CREATE TABLE fs_corrective_actions (
    id               UUID        PRIMARY KEY,
    tenant_id        UUID        NOT NULL,
    record_id        UUID        NOT NULL REFERENCES fs_check_records (id),
    action           TEXT        NOT NULL,
    food_disposition TEXT        NOT NULL,
    recorded_by      UUID        NOT NULL,
    recorded_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_fs_action_disposition CHECK (
        food_disposition IN ('NONE', 'DISCARDED', 'MOVED', 'REHEATED', 'RECOOKED', 'OTHER'))
);
CREATE INDEX idx_fs_actions_record ON fs_corrective_actions (tenant_id, record_id);

-- A manager's periodic sign-off of a store's records: the verification step HACCP requires. The
-- counts are what the manager was shown, kept as they stood.
CREATE TABLE fs_reviews (
    id                  UUID        PRIMARY KEY,
    tenant_id           UUID        NOT NULL,
    store_id            UUID        NOT NULL,
    period_from         TIMESTAMPTZ NOT NULL,
    period_to           TIMESTAMPTZ NOT NULL,
    records_count       INTEGER     NOT NULL,
    failures_count      INTEGER     NOT NULL,
    open_failures_count INTEGER     NOT NULL,
    notes               TEXT,
    reviewed_by         UUID        NOT NULL,
    reviewed_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_fs_review_period CHECK (period_from < period_to)
);
CREATE INDEX idx_fs_reviews_store ON fs_reviews (tenant_id, store_id, reviewed_at DESC, id DESC);

-- One overdue alert per point per missed due time, so the sweeper can run every few minutes without
-- repeating itself.
CREATE TABLE fs_overdue_alerts (
    tenant_id  UUID        NOT NULL,
    point_id   UUID        NOT NULL REFERENCES fs_monitoring_points (id),
    due_since  TIMESTAMPTZ NOT NULL,
    alerted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_fs_overdue_alerts PRIMARY KEY (tenant_id, point_id, due_since)
);
