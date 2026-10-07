-- Gap #38: Configurable picking rules engine.
-- Admins define named rules (FIFO / FEFO / LIFO / FEFO_GRADE / ZONE_PRIORITY),
-- assign them to scopes (GLOBAL / STORE / PRODUCT), and the consume path
-- resolves the most-specific applicable rule at deduction time.

CREATE TABLE picking_rules (
    id                UUID        PRIMARY KEY,
    tenant_id         UUID        NOT NULL,
    name              TEXT        NOT NULL,
    strategy          TEXT        NOT NULL
                          CHECK (strategy IN ('FIFO','FEFO','LIFO','FEFO_GRADE','ZONE_PRIORITY')),
    grade_preference  TEXT,
    status            TEXT        NOT NULL DEFAULT 'ACTIVE',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name)
);
CREATE INDEX idx_picking_rules_tenant ON picking_rules (tenant_id);

-- Zone priority list for ZONE_PRIORITY rules (lower priority value = picked first).
CREATE TABLE picking_rule_zone_priorities (
    id          UUID    PRIMARY KEY,
    tenant_id   UUID    NOT NULL,
    rule_id     UUID    NOT NULL REFERENCES picking_rules(id) ON DELETE CASCADE,
    zone_id     UUID    NOT NULL,
    priority    INT     NOT NULL,
    UNIQUE (tenant_id, rule_id, zone_id)
);
CREATE INDEX idx_przp_rule ON picking_rule_zone_priorities (tenant_id, rule_id);

-- Rule-to-scope assignments. Most specific scope wins at resolve time:
-- PRODUCT > STORE > GLOBAL.
CREATE TABLE picking_rule_assignments (
    id          UUID    PRIMARY KEY,
    tenant_id   UUID    NOT NULL,
    rule_id     UUID    NOT NULL REFERENCES picking_rules(id) ON DELETE CASCADE,
    scope_type  TEXT    NOT NULL CHECK (scope_type IN ('GLOBAL','STORE','PRODUCT')),
    scope_id    UUID,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, scope_type, scope_id)
);
CREATE INDEX idx_pra_tenant_scope ON picking_rule_assignments (tenant_id, scope_type, scope_id);
