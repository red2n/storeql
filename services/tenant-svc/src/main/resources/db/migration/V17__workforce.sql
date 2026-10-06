-- The roster and the time clock (store operations & workforce).
--
-- A shop's biggest controllable cost is its hours, and the platform had no record of them: staff were
-- assigned to stores with roles, and nothing said who was meant to be in on Tuesday or who actually
-- was. Every other row in this domain leans on that — labour cost against sales cannot be computed
-- without hours, and an absence cannot be seen without a plan to compare against.
--
-- What this is NOT is a till session. iam-svc's pos_sessions is about the drawer: a cashier may open
-- three tills in one shift and a storekeeper never opens one at all. Using it for hours would pay a
-- cashier for the gaps between tills and pay a storekeeper nothing.

-- The roster: what somebody is meant to work.
CREATE TABLE work_shifts (
    id               UUID        PRIMARY KEY,
    tenant_id        UUID        NOT NULL,
    store_id         UUID        NOT NULL,
    user_id          UUID        NOT NULL,
    starts_at        TIMESTAMPTZ NOT NULL,
    ends_at          TIMESTAMPTZ NOT NULL,
    -- What they are rostered to do, in the business's own words: "till 2", "back store". Not a role,
    -- because a role is an authorisation and this is a plan.
    duty             TEXT,
    status           TEXT        NOT NULL,
    note             TEXT,
    cancelled_reason TEXT,
    created_at       TIMESTAMPTZ NOT NULL,
    created_by       UUID        NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL,

    CONSTRAINT ck_shift_status CHECK (status IN ('PLANNED', 'PUBLISHED', 'CANCELLED')),
    CONSTRAINT ck_shift_window CHECK (ends_at > starts_at),
    -- A shift longer than 24 hours is a typo, not a shift.
    CONSTRAINT ck_shift_length CHECK (ends_at <= starts_at + INTERVAL '24 hours'),
    -- Called off with a reason, or not called off: an empty reason on a cancelled shift is the thing
    -- somebody asks about when a week's rota is disputed.
    CONSTRAINT ck_shift_cancelled CHECK (
        (status = 'CANCELLED') = (cancelled_reason IS NOT NULL AND length(btrim(cancelled_reason)) > 0)
    )
);

CREATE INDEX idx_shifts_store ON work_shifts (tenant_id, store_id, starts_at);
CREATE INDEX idx_shifts_person ON work_shifts (tenant_id, user_id, starts_at);

-- The clock: what somebody actually worked.
--
-- Corrections supersede rather than edit, as an invoice and a statutory filing do: a manager who
-- fixes a forgotten clock-out writes a new entry naming the one it replaces and why, and both stay on
-- the record. Hours that can be quietly rewritten are hours nobody can be held to.
CREATE TABLE time_entries (
    id               UUID        PRIMARY KEY,
    tenant_id        UUID        NOT NULL,
    store_id         UUID        NOT NULL,
    user_id          UUID        NOT NULL,
    -- The rostered shift this answers, when there was one. Null on purpose: somebody may work a shift
    -- nobody planned, and a plan may go unworked — and "did not turn up" is exactly what an
    -- attendance report exists to show, so neither side may require the other.
    shift_id         UUID        REFERENCES work_shifts (id),
    clocked_in_at    TIMESTAMPTZ NOT NULL,
    clocked_out_at   TIMESTAMPTZ,
    -- CLOCK when the person did it themselves; MANAGER when somebody wrote it for them.
    source           TEXT        NOT NULL,
    note             TEXT,
    adjusted_reason  TEXT,
    supersedes       UUID        REFERENCES time_entries (id) DEFERRABLE INITIALLY DEFERRED,
    superseded_by    UUID        REFERENCES time_entries (id) DEFERRABLE INITIALLY DEFERRED,
    created_at       TIMESTAMPTZ NOT NULL,
    created_by       UUID        NOT NULL,

    CONSTRAINT ck_time_source CHECK (source IN ('CLOCK', 'MANAGER')),
    CONSTRAINT ck_time_window CHECK (clocked_out_at IS NULL OR clocked_out_at > clocked_in_at),
    CONSTRAINT ck_time_length CHECK (
        clocked_out_at IS NULL OR clocked_out_at <= clocked_in_at + INTERVAL '24 hours'
    ),
    -- A correction says why. Without that the record is an edit with extra steps.
    CONSTRAINT ck_time_adjusted CHECK (
        supersedes IS NULL OR (adjusted_reason IS NOT NULL AND length(btrim(adjusted_reason)) > 0)
    ),
    CONSTRAINT ck_time_self CHECK (supersedes IS NULL OR supersedes <> id)
);

-- One open entry per person: clocking in twice is how somebody gets paid twice for one afternoon.
CREATE UNIQUE INDEX uq_time_entry_open
    ON time_entries (tenant_id, user_id)
    WHERE clocked_out_at IS NULL AND superseded_by IS NULL;

CREATE INDEX idx_time_entries_store ON time_entries (tenant_id, store_id, clocked_in_at);
CREATE INDEX idx_time_entries_person ON time_entries (tenant_id, user_id, clocked_in_at);

-- Breaks, inside the entry they belong to.
--
-- Paid or not is the employer's arrangement and the platform does not decide it; what the platform
-- must do is keep the distinction, because unpaid breaks come off the hours and paid ones do not.
CREATE TABLE time_entry_breaks (
    id            UUID        PRIMARY KEY,
    tenant_id     UUID        NOT NULL,
    time_entry_id UUID        NOT NULL REFERENCES time_entries (id) ON DELETE CASCADE,
    started_at    TIMESTAMPTZ NOT NULL,
    ended_at      TIMESTAMPTZ,
    kind          TEXT        NOT NULL,
    paid          BOOLEAN     NOT NULL,

    CONSTRAINT ck_break_kind CHECK (kind IN ('REST', 'MEAL')),
    CONSTRAINT ck_break_window CHECK (ended_at IS NULL OR ended_at > started_at)
);

-- One open break per entry: a second would make the arithmetic of a day undecidable.
CREATE UNIQUE INDEX uq_break_open
    ON time_entry_breaks (time_entry_id)
    WHERE ended_at IS NULL;

CREATE INDEX idx_breaks_entry ON time_entry_breaks (tenant_id, time_entry_id, started_at);

COMMENT ON TABLE work_shifts IS
    'The roster: what somebody is meant to work. Not what they did — that is time_entries.';
COMMENT ON TABLE time_entries IS
    'The clock. A correction supersedes and both stay: hours that can be quietly rewritten are hours nobody can be held to.';
COMMENT ON COLUMN time_entries.shift_id IS
    'The rostered shift this answers, or null: an unplanned shift and an unworked plan are both real.';
