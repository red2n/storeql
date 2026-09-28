-- Multi-currency pricing and FX (pricing & promotions, 03.x).
--
-- A business keeps one home currency and, until now, no way to relate another currency to it:
-- procurement was genuinely multi-currency (a supplier's currency, its own minor units), and so a
-- spend ceiling had to be declared per currency because nothing could translate one, and a shop
-- could show a price in no currency but its own. This is the business's own rate table: per other
-- currency, the number of home units one unit of it buys, from a day, with a reason and who set it.
-- Append-only: a new rate is a new row, the current one the latest that has taken effect, and the
-- history is the audit trail an accountant asks for. Rates are the business's to keep; the
-- platform fetches none.
CREATE TABLE fx_rates (
    id             UUID           NOT NULL,
    tenant_id      UUID           NOT NULL,
    currency       CHAR(3)        NOT NULL,           -- the other currency, ISO 4217
    rate           NUMERIC(24,10) NOT NULL,           -- home units per one unit of currency
    effective_from DATE           NOT NULL,
    reason         TEXT           NOT NULL,
    set_by         UUID,
    set_at         TIMESTAMPTZ    NOT NULL,
    CONSTRAINT pk_fx_rates PRIMARY KEY (id),
    CONSTRAINT ck_fx_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_fx_rate CHECK (rate > 0)
);
CREATE INDEX idx_fx_rates_current ON fx_rates (tenant_id, currency, effective_from DESC, set_at DESC);
