-- Promotion redemptions: the append-only ledger the promotion engine counts usage from.
--
-- The promotions table carries what the engine applies (its type and shape, priority, exclusive,
-- coupon_code, the usage caps and the BOGO / MIX_MATCH quantities; see the promotions table in
-- V1). Every one of those changes what a tenant is charged, and the engine reads every one: the
-- minimum order amount, the store a promotion is confined to, and the category scope are all
-- applied. Selection is by priority, never by value, because a PERCENT's value (15, meaning 15%)
-- and a FLAT's (20, meaning an amount) are different units and cannot be ordered against each other.

-- ── redemptions: an append-only ledger, and the only source of a usage count ─────────────────
--
-- Append-only (golden rule #8), except tenant erasure. A counter column on promotions
-- would have been cheaper to read and impossible to audit: "this coupon is exhausted" is a claim a
-- tenant will dispute, and the answer has to be a list of orders rather than a number.
CREATE TABLE promotion_redemptions (
    id            UUID          PRIMARY KEY,
    tenant_id     UUID          NOT NULL,
    promotion_id  UUID          NOT NULL REFERENCES promotions (id),
    order_id      UUID          NOT NULL,
    customer_id   UUID,
    -- What this promotion actually took off that order, so a redemption is worth auditing rather
    -- than merely counting.
    amount        NUMERIC       NOT NULL,
    currency      TEXT          NOT NULL,
    redeemed_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- One redemption per promotion per order. This is the whole idempotency story: a retried
-- checkout, or an offline POS sale replaying its writes, must not burn a second use of a coupon.
-- The same replay question applies to gift cards: ask what a replay would do, not only whether the
-- code is correct.
CREATE UNIQUE INDEX idx_promotion_redemptions_once
    ON promotion_redemptions (tenant_id, promotion_id, order_id);

-- Serves both caps: total usage (tenant + promotion) and per-customer usage.
CREATE INDEX idx_promotion_redemptions_customer
    ON promotion_redemptions (tenant_id, promotion_id, customer_id)
    WHERE customer_id IS NOT NULL;
