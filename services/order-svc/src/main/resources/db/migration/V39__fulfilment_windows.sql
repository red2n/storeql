-- Delivery and collection slots (intent/delivery-and-collection-slots.md).
-- A store offers windows for delivery and for collection, set separately: the weekday, the time of
-- day in the store's own zone, how many orders it takes, and how long before it starts orders stop.
-- Weekly only (ISO weekday 1=Monday..7=Sunday); a dated exception (a holiday) is a later item —
-- a manager switches the window off and on again instead. Every id is bound by the service.
CREATE TABLE fulfilment_windows (
    id              UUID          NOT NULL,
    tenant_id       UUID          NOT NULL,
    store_id        UUID          NOT NULL,
    fulfilment_type TEXT          NOT NULL,
    weekday         SMALLINT      NOT NULL,
    start_time      TIME          NOT NULL,
    end_time        TIME          NOT NULL,
    capacity        INTEGER       NOT NULL,
    cutoff_minutes  INTEGER       NOT NULL DEFAULT 0,
    active          BOOLEAN       NOT NULL DEFAULT true,
    -- The store's own IANA zone (TenantProfiles.Stores.zoneOf) at the moment this was set: the
    -- fallback the storefront and checkout read when tenant-svc cannot be reached, never a
    -- platform default and never guessed.
    time_zone       TEXT          NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by      UUID          NOT NULL,
    CONSTRAINT pk_fulfilment_windows PRIMARY KEY (id),
    CONSTRAINT ck_fulfilment_windows_type CHECK (fulfilment_type IN ('DELIVERY', 'PICKUP')),
    CONSTRAINT ck_fulfilment_windows_weekday CHECK (weekday BETWEEN 1 AND 7),
    CONSTRAINT ck_fulfilment_windows_span CHECK (start_time < end_time),
    CONSTRAINT ck_fulfilment_windows_capacity CHECK (capacity >= 1),
    CONSTRAINT ck_fulfilment_windows_cutoff CHECK (cutoff_minutes >= 0)
);
-- Every read and write is by tenant then store: the admin list, the storefront read and the
-- overlap check on a write all filter this way first.
CREATE INDEX idx_fulfilment_windows_store
    ON fulfilment_windows (tenant_id, store_id, fulfilment_type, weekday);

-- The occurrence an order holds: the window it was taken from and the chosen occurrence's UTC
-- instants, fixed at the moment of placing rather than recomputed later against rules that may
-- since have changed. slot_time_zone is the zone the occurrence was resolved in at that moment, so
-- the server can always show it in the store's own local time without asking tenant-svc again. All
-- four are set together or not at all: a store with no windows checks out exactly as before.
ALTER TABLE orders
    ADD COLUMN slot_window_id UUID REFERENCES fulfilment_windows (id),
    ADD COLUMN slot_starts_at TIMESTAMPTZ,
    ADD COLUMN slot_ends_at   TIMESTAMPTZ,
    ADD COLUMN slot_time_zone TEXT,
    ADD CONSTRAINT ck_orders_slot_shape CHECK (
        (slot_window_id IS NULL AND slot_starts_at IS NULL AND slot_ends_at IS NULL AND slot_time_zone IS NULL)
        OR (slot_window_id IS NOT NULL AND slot_starts_at IS NOT NULL AND slot_ends_at IS NOT NULL AND slot_time_zone IS NOT NULL));
-- Capacity is counted here: the window row is locked first, then this pair filters the orders
-- already holding the same occurrence (COUNT DISTINCT coalesce(group_id, id), status excluded).
CREATE INDEX idx_orders_slot ON orders (tenant_id, slot_window_id, slot_starts_at)
    WHERE slot_window_id IS NOT NULL;
