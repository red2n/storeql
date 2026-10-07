-- Product safety information for online offers.
--
-- The General Product Safety Regulation ((EU) 2023/988, applying since 13 December 2024) art.19
-- requires an online offer to show, clearly and visibly: the manufacturer's name, postal address and
-- electronic address; where the manufacturer is not established in the EU, the same for the person
-- responsible for the product in the EU; information identifying the product; and any warnings or
-- safety information. A product record that holds none of it cannot be listed lawfully in an EU
-- market, and a listing that shows an empty box is no better.
--
-- Whether the regulation binds a business, and whether a manufacturer's country is inside it, is
-- asked of the jurisdiction rules (GPSR_ONLINE_OFFER in tenant-svc), never decided here. A product
-- sold only at the till needs none of it; the rule is enforced when a product is offered online.
--
-- One row per product, kept beside the product rather than on it: most of a catalogue's reads never
-- need it, and it has its own writer and its own history of who last stated it.

CREATE TABLE product_safety_information (
    tenant_id                  UUID        NOT NULL,
    product_id                 UUID        NOT NULL REFERENCES products (id),
    manufacturer_name          TEXT        CHECK (char_length(manufacturer_name) BETWEEN 1 AND 200),
    manufacturer_address       TEXT        CHECK (char_length(manufacturer_address) BETWEEN 1 AND 500),
    -- An e-mail address or an https:// URL: art.19(a) asks for an electronic address.
    manufacturer_contact       TEXT        CHECK (char_length(manufacturer_contact) BETWEEN 3 AND 254),
    -- ISO 3166-1 alpha-2; whether it is inside the regime is jurisdiction data, not a list here.
    manufacturer_country       TEXT        CHECK (manufacturer_country ~ '^[A-Z]{2}$'),
    responsible_person_name    TEXT        CHECK (char_length(responsible_person_name) BETWEEN 1 AND 200),
    responsible_person_address TEXT        CHECK (char_length(responsible_person_address) BETWEEN 1 AND 500),
    responsible_person_contact TEXT        CHECK (char_length(responsible_person_contact) BETWEEN 3 AND 254),
    warnings                   TEXT        CHECK (char_length(warnings) BETWEEN 1 AND 4000),
    -- A business states that no warning applies; an empty warnings field alone says nothing.
    no_warnings                BOOLEAN     NOT NULL,
    updated_at                 TIMESTAMPTZ NOT NULL,
    updated_by                 UUID,
    PRIMARY KEY (tenant_id, product_id),
    CONSTRAINT chk_safety_warnings_or_none CHECK (NOT (no_warnings AND warnings IS NOT NULL))
);
