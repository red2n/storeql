-- A phone at the till (intent/phone-at-the-till.md): whether a store's till asks for the customer's
-- phone. OPTIONAL for every store, existing ones included (the user's choice, 2026-09-26): the till
-- asks and the cashier may leave it blank as the customer prefers. REQUIRED refuses a till sale
-- with neither a number nor a customer; OFF never asks. order-svc reads it through TenantProfiles.
ALTER TABLE stores ADD COLUMN till_phone TEXT NOT NULL DEFAULT 'OPTIONAL';
ALTER TABLE stores
    ADD CONSTRAINT ck_stores_till_phone CHECK (till_phone IN ('REQUIRED', 'OPTIONAL', 'OFF'));
