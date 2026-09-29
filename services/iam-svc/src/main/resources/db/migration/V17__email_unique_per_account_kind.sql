-- A shopper's account and a business account are separate identities (29 Sep 2026, as at Shopify,
-- Square and Stripe): one person may shop with an address and run a business with the same one.
--
-- V1 made an email unique per tenant scope and put every login of no business into one scope
-- (NULLS NOT DISTINCT), so someone who already shopped with an address could not sign up a
-- business with it (409 USER_ALREADY_EXISTS). Now:
--   * inside a business an address is one login, as before;
--   * outside any business it is one login of each kind: one shopper's (CUSTOMER), and one of the
--     business kind (STAFF: a business sign-up not yet onboarded, a login its business has let go,
--     the platform administrator). Two shopper sign-ups, or two business sign-ups, still cannot
--     share an address, and no business sign-up can take the platform administrator's.
--
-- Only indexes change, and each is looser than the one it replaces, so every row already here
-- satisfies them and a restore cannot trip on them (no backup drill needed).
--
-- The phone index stays as V1 made it: only a shopper's sign-up records a phone (business sign-ups
-- and provisioned staff carry none), so the two kinds never compete for one.
DROP INDEX uq_users_tenant_email;

CREATE UNIQUE INDEX uq_users_business_email ON users (tenant_id, lower(email))
    WHERE tenant_id IS NOT NULL AND email IS NOT NULL;

CREATE UNIQUE INDEX uq_users_unbound_email ON users (type, lower(email))
    WHERE tenant_id IS NULL AND email IS NOT NULL;
