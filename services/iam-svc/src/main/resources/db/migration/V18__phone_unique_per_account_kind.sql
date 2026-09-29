-- A shopper's account and a business account are separate identities (29 Sep 2026), for a phone as
-- V17 made them for an email: the business sign-up now takes an optional phone, as the shopper's
-- sign-up always has, and one person may give the same number to both.
--
-- V1 made a phone unique per tenant scope and put every login of no business into one scope
-- (NULLS NOT DISTINCT). V17 left that index alone because only a shopper's sign-up recorded a
-- phone; now a business sign-up records one too, and under V1's index someone who already shopped
-- with a number could not start a business with it (409 USER_ALREADY_EXISTS). Now, exactly as V17
-- did for an email:
--   * inside a business a phone is one login, as before;
--   * outside any business it is one login of each kind: one shopper's (CUSTOMER), and one of the
--     business kind (STAFF: a business sign-up not yet onboarded, a login its business has let go,
--     the platform administrator). Two shopper sign-ups, or two business sign-ups, still cannot
--     share a number.
--
-- Only indexes change, and each is looser than the one it replaces, so every row already here
-- satisfies them and a restore cannot trip on them (no backup drill needed).
DROP INDEX uq_users_tenant_phone;

CREATE UNIQUE INDEX uq_users_business_phone ON users (tenant_id, phone)
    WHERE tenant_id IS NOT NULL AND phone IS NOT NULL;

CREATE UNIQUE INDEX uq_users_unbound_phone ON users (type, phone)
    WHERE tenant_id IS NULL AND phone IS NOT NULL;
