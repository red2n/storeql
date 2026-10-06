-- Four capabilities a retailer must be able to state about an item before it may be offered, all of
-- them law rather than product strategy: allergen declaration, country of origin, age-restricted
-- sales, and selling goods by weight.
--
-- The variant columns these rules read (country_of_origin, allergen_status, restriction_category,
-- sold_by, net content, tare, catch weight) are on product_variants in V1__init.sql, because three of
-- the four are attributes of a variant. This file holds the tables: the allergen list and the
-- declarations made against it, and the age rules that apply per country.

-- ---------------------------------------------------------------------------------------------
-- 1. Allergens.  Natasha's Law (Food Information (Amendment) (England) Regulations 2019) and
--    Regulation (EU) 1169/2011 Annex II, which lists exactly fourteen.
-- ---------------------------------------------------------------------------------------------

-- System-wide reference data, no tenant_id -- the fourteen are set by regulation, not by the
-- business, and a tenant that could edit them could quietly delete one. Same precedent as
-- uom_definitions in V3.
CREATE TABLE allergens (
    code       TEXT NOT NULL,
    name       TEXT NOT NULL,
    -- What a label has to say. Annex II names the category; the substances are examples of it.
    detail     TEXT,
    regulation TEXT NOT NULL,
    CONSTRAINT pk_allergens PRIMARY KEY (code)
);

INSERT INTO allergens (code, name, detail, regulation) VALUES
 ('CEREALS_GLUTEN','Cereals containing gluten','Wheat, rye, barley, oats, spelt, kamut','EU 1169/2011 Annex II (1)'),
 ('CRUSTACEANS','Crustaceans','Crab, lobster, prawns, scampi','EU 1169/2011 Annex II (2)'),
 ('EGGS','Eggs',NULL,'EU 1169/2011 Annex II (3)'),
 ('FISH','Fish',NULL,'EU 1169/2011 Annex II (4)'),
 ('PEANUTS','Peanuts',NULL,'EU 1169/2011 Annex II (5)'),
 ('SOYBEANS','Soybeans',NULL,'EU 1169/2011 Annex II (6)'),
 ('MILK','Milk','Including lactose','EU 1169/2011 Annex II (7)'),
 ('NUTS','Tree nuts','Almond, hazelnut, walnut, cashew, pecan, Brazil, pistachio, macadamia','EU 1169/2011 Annex II (8)'),
 ('CELERY','Celery',NULL,'EU 1169/2011 Annex II (9)'),
 ('MUSTARD','Mustard',NULL,'EU 1169/2011 Annex II (10)'),
 ('SESAME','Sesame seeds',NULL,'EU 1169/2011 Annex II (11)'),
 ('SULPHITES','Sulphur dioxide and sulphites','At concentrations above 10 mg/kg or 10 mg/l','EU 1169/2011 Annex II (12)'),
 ('LUPIN','Lupin',NULL,'EU 1169/2011 Annex II (13)'),
 ('MOLLUSCS','Molluscs','Mussels, oysters, squid, snails','EU 1169/2011 Annex II (14)');

-- A declaration, per variant.
--
-- presence is CONTAINS or MAY_CONTAIN and the distinction is legal, not cosmetic: "may contain"
-- is a cross-contamination warning, and collapsing the two into a boolean either invents a
-- declaration the producer never made or discards one they did.
CREATE TABLE variant_allergens (
    tenant_id     UUID        NOT NULL,
    variant_id    UUID        NOT NULL,
    allergen_code TEXT        NOT NULL,
    presence      TEXT        NOT NULL,
    declared_by   UUID,
    declared_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_variant_allergens  PRIMARY KEY (tenant_id, variant_id, allergen_code),
    CONSTRAINT fk_va_allergen        FOREIGN KEY (allergen_code) REFERENCES allergens (code),
    CONSTRAINT chk_va_presence       CHECK (presence IN ('CONTAINS','MAY_CONTAIN'))
);

CREATE INDEX idx_variant_allergens ON variant_allergens (tenant_id, variant_id);

-- Finding every product carrying an allergen is the query a recall runs, and the query a customer
-- with an allergy runs. Without this it is a full scan of the tenant's declarations.
CREATE INDEX idx_variant_allergens_by_allergen
    ON variant_allergens (tenant_id, allergen_code, presence);

-- ---------------------------------------------------------------------------------------------
-- 2. Age-restricted sales.
--
--    The minimum age is set by the country the STORE is in, not by the tenant and not by the
--    product -- the same bottle of wine is 18 in the UK, 20 in Japan and 21 in the US. So the
--    variant carries the category and the age is looked up per country at the till.
-- ---------------------------------------------------------------------------------------------

-- Statutory defaults, system-wide reference data like the allergen list.
--
-- A rule holds a minimum age and, where a statute refuses sale by date of birth rather than by age, a
-- born-before cut-off beside it. The generational tobacco ban (Tobacco and Vapes Act 2026, Royal Assent
-- 29 April 2026) makes it an offence to sell tobacco to anyone born on or after 1 January 2009, however
-- old they are. That is a date of birth, not an age, and a rule table that only holds a minimum age
-- cannot write it: in 2040 a 31-year-old born in 2009 is still refused. The minimum age stays — it is
-- still the rule for everyone born before the cut-off — and the cut-off sits beside it with the day it
-- takes effect. Anyone the cut-off catches is under 18 until 1 January 2027, so the minimum age already
-- refuses them until then; the effective date keeps the till's prompt honest rather than changing who
-- is served.
CREATE TABLE age_restriction_rules (
    country          CHAR(2) NOT NULL,
    category         TEXT    NOT NULL,
    minimum_age      INT     NOT NULL,
    note             TEXT,
    -- Refuse anyone born on or after this date, whatever their age.
    born_before      DATE,
    -- The day the cut-off takes effect; a statutory cut-off always has one.
    born_before_from DATE,
    CONSTRAINT pk_age_rules  PRIMARY KEY (country, category),
    CONSTRAINT chk_age_range CHECK (minimum_age BETWEEN 0 AND 120),
    CONSTRAINT chk_age_born_before_pair CHECK ((born_before IS NULL) = (born_before_from IS NULL))
);

INSERT INTO age_restriction_rules (country, category, minimum_age, note, born_before, born_before_from) VALUES
 ('GB','ALCOHOL',18,'Licensing Act 2003',NULL,NULL),
 ('GB','TOBACCO',18,'Children and Young Persons (Protection from Tobacco) Act 1991; Tobacco and Vapes Act 2026: no sale to anyone born on or after 1 Jan 2009',DATE '2009-01-01',DATE '2027-01-01'),
 ('GB','NICOTINE_VAPE',18,'Nicotine Inhaling Products (Age of Sale) Regulations 2015',NULL,NULL),
 ('GB','KNIVES',18,'Criminal Justice Act 1988 s.141A',NULL,NULL),
 ('GB','CORROSIVES',18,'Offensive Weapons Act 2019',NULL,NULL),
 ('GB','SOLVENTS',18,'Intoxicating Substances (Supply) Act 1985',NULL,NULL),
 ('GB','FIREWORKS',18,'Fireworks Regulations 2004',NULL,NULL),
 ('GB','LOTTERY',18,'raised from 16 in October 2021',NULL,NULL),
 ('GB','VIDEO_18',18,'Video Recordings Act 1984',NULL,NULL),
 ('GB','PETROL',16,NULL,NULL,NULL),
 ('US','ALCOHOL',21,'National Minimum Drinking Age Act 1984',NULL,NULL),
 ('US','TOBACCO',21,'federal Tobacco 21, December 2019',NULL,NULL),
 ('US','NICOTINE_VAPE',21,'federal Tobacco 21, December 2019',NULL,NULL),
 ('US','FIREWORKS',18,'varies by state -- override per tenant',NULL,NULL),
 ('JP','ALCOHOL',20,'Minor Drinking Prohibition Act -- unchanged by the 2022 majority reform',NULL,NULL),
 ('JP','TOBACCO',20,'Minor Smoking Prohibition Act',NULL,NULL),
 ('JP','NICOTINE_VAPE',20,NULL,NULL,NULL),
 ('CN','ALCOHOL',18,NULL,NULL,NULL),
 ('CN','TOBACCO',18,'Law on the Protection of Minors',NULL,NULL),
 ('IN','TOBACCO',18,'COTPA 2003',NULL,NULL),
 -- India sets the drinking age by state: 18 in some, 21 in others, 25 in Maharashtra for spirits,
 -- and prohibition in Gujarat and Bihar. 21 is the commonest and is deliberately the conservative
 -- floor; a tenant trading in a state that differs must override it, which is what the override
 -- table below exists for.
 ('IN','ALCOHOL',21,'varies by state (18-25, prohibition in some) -- override per store country',NULL,NULL);

-- A tenant's own rule wins over the statutory default. Needed both for jurisdictions that vary
-- below national level and for a business choosing to sell above the legal minimum, which is
-- allowed and is a policy some chains adopt. A business may adopt a born-before cut-off early, or an
-- earlier one, as its own policy: it applies at once.
CREATE TABLE tenant_age_restriction_rules (
    tenant_id   UUID    NOT NULL,
    country     CHAR(2) NOT NULL,
    category    TEXT    NOT NULL,
    minimum_age INT     NOT NULL,
    reason      TEXT,
    set_by      UUID,
    set_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    born_before DATE,
    CONSTRAINT pk_tenant_age_rules  PRIMARY KEY (tenant_id, country, category),
    CONSTRAINT chk_tenant_age_range CHECK (minimum_age BETWEEN 0 AND 120)
);
