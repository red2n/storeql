-- The work a shop does every day, and the record that it was done (store operations & workforce).
--
-- The roster says who is in (V17) and the clock says they turned up (V17); neither says what they were
-- meant to *do*. Opening up, counting the float, checking the bins, putting the delivery away, locking
-- the back door — a shop runs on a list, and a manager who cannot see whether the list was finished is
-- managing by hope.
--
-- **One mechanism, not two.** A task may carry items; a task with no items is a single thing to do, and
-- a task with items *is* a checklist, finished when every required line is ticked. Two separate models —
-- "tasks" and "checklists" — would have the same fields, the same report and two places to fix a bug.
--
-- This is NOT the food-safety check (inventory-svc `fs_*`). That one is statutory due diligence about
-- temperatures at monitoring points, judged against limits it records at the time, and it keeps its own
-- records because the law asks for those records. This is the shop's own work, and a shop deciding it
-- will sweep the yard on Fridays is not making a HACCP record.
CREATE TABLE task_templates (
    id             UUID          PRIMARY KEY,
    tenant_id      UUID          NOT NULL,
    -- Null means every store the business has, which is the ordinary case: an opening checklist is the
    -- same everywhere. A store id narrows it to one shop's own work.
    store_id       UUID,
    title          TEXT          NOT NULL,
    instructions   TEXT,
    -- OPENING and CLOSING are the two a manager asks about by name; the others are the rest of the list.
    kind           TEXT          NOT NULL,
    -- Which days it falls due, as ISO day numbers (1 = Monday). Empty means every day, because a list
    -- that applies to no day at all is a list nobody wrote on purpose.
    days_of_week   SMALLINT[]    NOT NULL DEFAULT '{}',
    -- Read on the STORE's own clock, not UTC: a shop's day starts when the shop opens.
    due_time       TIME          NOT NULL,
    -- How long after it falls due before it counts as missed. A closing check at 22:00 is not missed at
    -- 22:01.
    grace_minutes  INTEGER       NOT NULL DEFAULT 60,
    -- Whose job it is, as staff_assignments names roles; null means anybody on shift.
    role           TEXT,
    -- A required task makes the day incomplete until it is done or explained; an optional one does not.
    required       BOOLEAN       NOT NULL DEFAULT TRUE,
    status         TEXT          NOT NULL DEFAULT 'ACTIVE',
    created_at     TIMESTAMPTZ   NOT NULL,
    created_by     UUID          NOT NULL,
    withdrawn_at   TIMESTAMPTZ,
    withdrawn_by   UUID,

    CONSTRAINT ck_task_kind CHECK (kind IN ('OPENING', 'CLOSING', 'DAILY', 'WEEKLY', 'AD_HOC')),
    CONSTRAINT ck_task_status CHECK (status IN ('ACTIVE', 'WITHDRAWN')),
    CONSTRAINT ck_task_grace CHECK (grace_minutes BETWEEN 0 AND 1440),
    -- Withdrawn means somebody withdrew it, and an unattributable withdrawal is not a record.
    CONSTRAINT ck_task_withdrawn CHECK (
        (status <> 'WITHDRAWN') OR (withdrawn_at IS NOT NULL AND withdrawn_by IS NOT NULL)
    )
);

CREATE INDEX idx_task_templates_tenant ON task_templates (tenant_id, status, kind);
CREATE INDEX idx_task_templates_store ON task_templates (tenant_id, store_id) WHERE store_id IS NOT NULL;

-- The lines of a checklist, in the order they are worked.
CREATE TABLE task_template_items (
    id          UUID    PRIMARY KEY,
    tenant_id   UUID    NOT NULL,
    template_id UUID    NOT NULL REFERENCES task_templates(id),
    position    INTEGER NOT NULL,
    text        TEXT    NOT NULL,
    required    BOOLEAN NOT NULL DEFAULT TRUE,

    CONSTRAINT uq_task_item_position UNIQUE (template_id, position)
);

CREATE INDEX idx_task_template_items ON task_template_items (tenant_id, template_id, position);

-- One occurrence: this store, this business date.
--
-- Generated ahead of being worked rather than written when somebody ticks it, because **a task nobody
-- did has to exist to be missed**. A list that only appears once somebody opens it cannot report the
-- morning nobody opened it, which is the single thing this row exists for.
CREATE TABLE task_instances (
    id              UUID          PRIMARY KEY,
    tenant_id       UUID          NOT NULL,
    store_id        UUID          NOT NULL,
    template_id     UUID          NOT NULL REFERENCES task_templates(id),
    -- The store's own date, on the store's own clock.
    business_date   DATE          NOT NULL,
    due_at          TIMESTAMPTZ   NOT NULL,
    status          TEXT          NOT NULL DEFAULT 'OPEN',
    -- Copied from the template when the day was generated, so editing the list tomorrow does not
    -- rewrite what somebody did today.
    title           TEXT          NOT NULL,
    kind            TEXT          NOT NULL,
    role            TEXT,
    required        BOOLEAN       NOT NULL,
    completed_at    TIMESTAMPTZ,
    completed_by    UUID,
    skipped_reason  TEXT,
    note            TEXT,
    created_at      TIMESTAMPTZ   NOT NULL,

    CONSTRAINT ck_instance_status CHECK (status IN ('OPEN', 'DONE', 'SKIPPED', 'MISSED')),
    -- Done means somebody did it, at a time. A tick with nobody behind it is what an audit cannot use.
    CONSTRAINT ck_instance_done CHECK (
        (status <> 'DONE') OR (completed_at IS NOT NULL AND completed_by IS NOT NULL)
    ),
    -- A skipped closing check with no reason is precisely what an auditor asks about.
    CONSTRAINT ck_instance_skipped CHECK (
        (status <> 'SKIPPED') OR (skipped_reason IS NOT NULL AND completed_by IS NOT NULL)
    ),
    -- One occurrence per list per store per day: generating a day twice would double every report and
    -- let the same job be signed off by two people.
    CONSTRAINT uq_task_instance_day UNIQUE (tenant_id, template_id, store_id, business_date)
);

CREATE INDEX idx_task_instances_day ON task_instances (tenant_id, store_id, business_date, due_at);
CREATE INDEX idx_task_instances_open
    ON task_instances (tenant_id, due_at) WHERE status = 'OPEN';

-- The lines of an occurrence, with their text carried across for the same reason.
CREATE TABLE task_instance_items (
    id           UUID        PRIMARY KEY,
    tenant_id    UUID        NOT NULL,
    instance_id  UUID        NOT NULL REFERENCES task_instances(id),
    position     INTEGER     NOT NULL,
    text         TEXT        NOT NULL,
    required     BOOLEAN     NOT NULL,
    ticked_at    TIMESTAMPTZ,
    ticked_by    UUID,

    CONSTRAINT uq_instance_item_position UNIQUE (instance_id, position),
    CONSTRAINT ck_instance_item_ticked CHECK (
        (ticked_at IS NULL) = (ticked_by IS NULL)
    )
);

CREATE INDEX idx_task_instance_items ON task_instance_items (tenant_id, instance_id, position);

COMMENT ON TABLE task_templates IS
    'The work a shop does on a schedule. A template with items is a checklist; one without is a single task.';
COMMENT ON TABLE task_instances IS
    'One occurrence per list per store per day, generated ahead of being worked so a task nobody did still exists to be missed.';
COMMENT ON COLUMN task_instances.title IS
    'Copied from the template at generation, so editing the list tomorrow does not rewrite what was done today.';
COMMENT ON COLUMN task_instances.due_at IS
    'Computed from the store''s own timezone and the template''s due time: a shop''s day starts when the shop opens.';
