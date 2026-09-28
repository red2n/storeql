-- A phone at the till (intent/phone-at-the-till.md): an order's contact number in international
-- form, beside the number as it was typed. Read at placement in the store's own country, then the
-- business's home and its other stores' — what a recall text is sent to, since a number typed the
-- usual way ("98860 21001") is not one an SMS gateway takes. Null when no number was given, or when
-- the one given could not be read (an online number is kept as typed and never refused over it).
-- Blanked with contact_phone when a customer is erased.
ALTER TABLE orders ADD COLUMN contact_phone_e164 TEXT;
ALTER TABLE orders
    ADD CONSTRAINT ck_orders_contact_phone_e164
        CHECK (contact_phone_e164 IS NULL OR contact_phone_e164 ~ '^\+[1-9][0-9]{6,14}$');
