-- A band's threshold is held at the scale of what it counts, never an assumed two places: a
-- percentage band's at the minor units of the business's currency (ISO 4217 — none for yen, three
-- for a Kuwaiti dinar), a per-unit band's at a quantity's three. NUMERIC(18,2) rounded a dinar's
-- third decimal and a unit count's third away on the way in. Widening the scale (and keeping the
-- sixteen integer digits it had, so no stored value can overflow) changes no value
-- (numeric equality ignores scale, so uq_band_threshold is unaffected); the service reads each
-- threshold back at its own scale.
ALTER TABLE commission_scheme_bands ALTER COLUMN threshold_from TYPE NUMERIC(20,4);
