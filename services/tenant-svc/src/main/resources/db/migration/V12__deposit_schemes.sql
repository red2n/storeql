-- Deposit return schemes for drinks containers (readiness review 09.16).
--
-- From 1 October 2027 a drink sold in England or Northern Ireland in a PET, steel or aluminium
-- container of 150 ml to 3 litres carries a deposit of 20p, refunded when the container comes
-- back (Deposit Scheme for Drinks Containers (England and Northern Ireland) Regulations 2025,
-- SI 2025/67); the deposit is outside the scope of VAT at every sale, VAT being accounted for on
-- unredeemed deposits alone (VATA 1994 ss.55B–55D, Finance (No. 2) Act 2023). Germany has run one
-- since 2003: 25 cents on single-use plastic, metal and glass containers up to 3 litres
-- (Verpackungsgesetz §31), and there the deposit is part of the price and carries VAT at the
-- drink's rate. Every member state runs one for plastic bottles and metal cans by 2029 (Regulation
-- (EU) 2025/40); what each charges is its own to set, so the Regulation itself is an obligation
-- and not an amount. A scheme is jurisdiction data with its citation, in the currency the law
-- names; order-svc charges the deposit as its own line where the store trades, and refunds it
-- when a container is returned. Nothing in code carries a figure.
CREATE TABLE deposit_schemes (
    scope_kind     TEXT           NOT NULL,
    scope          TEXT           NOT NULL,
    currency       CHAR(3)        NOT NULL,
    deposit_each   NUMERIC(10, 2) NOT NULL,
    -- The containers in scope: materials as a comma-separated list, and the volume range.
    materials      TEXT           NOT NULL,
    min_volume_ml  INTEGER        NOT NULL,
    max_volume_ml  INTEGER        NOT NULL,
    -- OUTSIDE_SCOPE: the deposit carries no VAT at the sale; STANDARD: it is taxed as the drink is.
    vat_treatment  TEXT           NOT NULL,
    effective_from DATE           NOT NULL,
    effective_to   DATE,
    citation       TEXT           NOT NULL,
    summary        TEXT           NOT NULL,
    CONSTRAINT pk_deposit_schemes PRIMARY KEY (scope_kind, scope, effective_from),
    CONSTRAINT chk_deposit_scope_kind CHECK (scope_kind IN ('REGIME', 'COUNTRY')),
    CONSTRAINT chk_deposit_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_deposit_each CHECK (deposit_each > 0),
    CONSTRAINT chk_deposit_volumes CHECK (min_volume_ml > 0 AND max_volume_ml >= min_volume_ml),
    CONSTRAINT chk_deposit_vat CHECK (vat_treatment IN ('OUTSIDE_SCOPE', 'STANDARD')),
    CONSTRAINT chk_deposit_window CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
CREATE INDEX idx_deposit_schemes_scope ON deposit_schemes (scope_kind, scope);

INSERT INTO deposit_schemes (scope_kind, scope, currency, deposit_each, materials, min_volume_ml, max_volume_ml, vat_treatment, effective_from, effective_to, citation, summary) VALUES
 ('COUNTRY','GB','GBP',0.20,'PET,ALUMINIUM,STEEL',150,3000,'OUTSIDE_SCOPE',DATE '2027-10-01',NULL,
  'Deposit Scheme for Drinks Containers (England and Northern Ireland) Regulations 2025, SI 2025/67; VATA 1994 ss.55B–55D',
  'A 20p deposit on a drink in a PET, steel or aluminium container of 150 ml to 3 litres, refunded on return; outside the scope of VAT at the sale.'),
 ('COUNTRY','DE','EUR',0.25,'PET,ALUMINIUM,STEEL,GLASS',100,3000,'STANDARD',DATE '2003-01-01',NULL,
  'Verpackungsgesetz §31',
  'A 25 cent deposit on a drink in a single-use plastic, metal or glass container of 0.1 to 3 litres, refunded on return; taxed as the drink is.');
