-- What management tells the shop floor, and who has read it (store operations & workforce).
--
-- A shop runs on notices as much as on lists: the price change on Monday, the recall on the counter,
-- the new closing procedure, the fire drill on Thursday. Until now the only way to tell every store
-- was a message outside the platform, and the only way to know who had read it was to ask. This is
-- the notice, its audience, and the record of who acknowledged it.
--
-- A notice is never edited. What staff acknowledged is the text they saw; a notice that changed under
-- its acknowledgements would make every one of them meaningless. A correction withdraws and publishes
-- again, and both stay.
CREATE TABLE store_broadcasts (
    id             UUID          PRIMARY KEY,
    tenant_id      UUID          NOT NULL,
    title          TEXT          NOT NULL,
    body           TEXT          NOT NULL,
    -- INFO is read when convenient; IMPORTANT is read today; URGENT is read now, and is what the
    -- store's devices are woken for.
    priority       TEXT          NOT NULL,
    -- Null means every store the business has. A store id narrows it to one shop.
    store_id       UUID,
    -- Null means everybody on the store's staff; a role narrows it (by the assignment's role or tier).
    role           TEXT,
    -- Whether staff must acknowledge it: a manager's view then shows who has not, by name.
    requires_ack   BOOLEAN       NOT NULL DEFAULT FALSE,
    published_at   TIMESTAMPTZ   NOT NULL,
    -- After this it is no longer current: a notice about Thursday's drill has nothing to say on
    -- Friday. Null pins it until withdrawn.
    expires_at     TIMESTAMPTZ,
    status         TEXT          NOT NULL DEFAULT 'PUBLISHED',
    created_by     UUID          NOT NULL,
    withdrawn_at   TIMESTAMPTZ,
    withdrawn_by   UUID,
    withdrawn_reason TEXT,

    CONSTRAINT ck_broadcast_priority CHECK (priority IN ('INFO', 'IMPORTANT', 'URGENT')),
    CONSTRAINT ck_broadcast_status CHECK (status IN ('PUBLISHED', 'WITHDRAWN')),
    CONSTRAINT ck_broadcast_expiry CHECK (expires_at IS NULL OR expires_at > published_at),
    CONSTRAINT ck_broadcast_withdrawn CHECK (
        (status <> 'WITHDRAWN')
        OR (withdrawn_at IS NOT NULL AND withdrawn_by IS NOT NULL AND withdrawn_reason IS NOT NULL)
    )
);

CREATE INDEX idx_broadcasts_current
    ON store_broadcasts (tenant_id, published_at DESC) WHERE status = 'PUBLISHED';
CREATE INDEX idx_broadcasts_store
    ON store_broadcasts (tenant_id, store_id, published_at DESC) WHERE store_id IS NOT NULL;

-- Who acknowledged what, once. Append-only: an acknowledgement is a fact about a moment.
CREATE TABLE store_broadcast_acks (
    id            UUID        PRIMARY KEY,
    tenant_id     UUID        NOT NULL,
    broadcast_id  UUID        NOT NULL REFERENCES store_broadcasts(id),
    user_id       UUID        NOT NULL,
    store_id      UUID        NOT NULL,
    acked_at      TIMESTAMPTZ NOT NULL,

    -- One acknowledgement per person per notice: a second tap is not a second reading.
    CONSTRAINT uq_broadcast_ack UNIQUE (broadcast_id, user_id)
);

CREATE INDEX idx_broadcast_acks ON store_broadcast_acks (tenant_id, broadcast_id, store_id);

COMMENT ON TABLE store_broadcasts IS
    'A notice from management to the shop floor: never edited, withdrawn and published again instead, so what was acknowledged is what was seen.';
COMMENT ON TABLE store_broadcast_acks IS
    'Who acknowledged which notice, once. What a manager reads to see who has not.';
