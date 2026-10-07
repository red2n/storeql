-- Gap #2: Unit of Measure model (Oracle Inventory Ch. 3)
-- uom_classes and uom_definitions are system-wide reference data (no tenant_id).
-- uom_item_conversions are tenant + variant scoped.

CREATE TABLE uom_classes (
    id   UUID NOT NULL,
    code TEXT NOT NULL,
    name TEXT NOT NULL,
    CONSTRAINT pk_uom_classes  PRIMARY KEY (id),
    CONSTRAINT uq_uom_class    UNIQUE (code)
);

CREATE TABLE uom_definitions (
    id         UUID NOT NULL,
    class_code TEXT NOT NULL,
    code       TEXT NOT NULL,
    name       TEXT NOT NULL,
    CONSTRAINT pk_uom_definitions PRIMARY KEY (id),
    CONSTRAINT uq_uom_def         UNIQUE (class_code, code),
    CONSTRAINT uq_uom_code        UNIQUE (code)
);

-- Standard conversions: 1 from_uom = factor to_uom (system-wide, both directions seeded).
CREATE TABLE uom_standard_conversions (
    id       UUID           NOT NULL,
    from_uom TEXT           NOT NULL,
    to_uom   TEXT           NOT NULL,
    factor   NUMERIC(18,8)  NOT NULL,
    CONSTRAINT pk_uom_std_conv PRIMARY KEY (id),
    CONSTRAINT uq_uom_std_conv UNIQUE (from_uom, to_uom)
);

-- Item-level conversion overrides: tenant + variant scoped.
CREATE TABLE uom_item_conversions (
    id         UUID           NOT NULL,
    tenant_id  UUID           NOT NULL,
    variant_id UUID           NOT NULL,
    from_uom   TEXT           NOT NULL,
    to_uom     TEXT           NOT NULL,
    factor     NUMERIC(18,8)  NOT NULL,
    CONSTRAINT pk_uom_item_conv PRIMARY KEY (id),
    CONSTRAINT uq_uom_item_conv UNIQUE (tenant_id, variant_id, from_uom, to_uom)
);

CREATE INDEX idx_uom_item_tenant_variant
    ON uom_item_conversions (tenant_id, variant_id);

-- ── Seed: UOM classes ───────────────────────────────────────────────────────
INSERT INTO uom_classes (id, code, name) VALUES
  ('01a090a0-1bc3-7006-b6f3-122bc02682f9', 'EACH',   'Count / Each'),
  ('01a090a0-1bc3-7007-9a03-45a1623b2e6a', 'WEIGHT', 'Weight'),
  ('01a090a0-1bc3-7008-9713-bd9ab79d76ee', 'VOLUME', 'Volume'),
  ('01a090a0-1bc3-7009-b866-740c00a52155', 'LENGTH', 'Length'),
  ('01a090a0-1bc3-700a-9a89-9bbbec9516c0', 'TIME',   'Time'),
  ('01a090a0-1bc3-700b-aea9-23d4d5037129', 'AREA',   'Area');

-- ── Seed: UOM definitions ───────────────────────────────────────────────────
INSERT INTO uom_definitions (id, class_code, code, name) VALUES
  -- EACH
  ('01a090a0-1bc3-700c-b786-1dc97903c161', 'EACH', 'EA',     'Each'),
  ('01a090a0-1bc3-700d-bb71-aac4c9c8c216', 'EACH', 'PAIR',   'Pair'),
  ('01a090a0-1bc3-700e-a384-607c742669c4', 'EACH', 'DOZEN',  'Dozen'),
  ('01a090a0-1bc3-700f-856c-1f502688856e', 'EACH', 'SCORE',  'Score'),
  ('01a090a0-1bc3-7010-a496-b289ef2b0a7d', 'EACH', 'GROSS',  'Gross (144)'),
  ('01a090a0-1bc3-7011-b748-937d68fe7492', 'EACH', 'CASE',   'Case'),
  ('01a090a0-1bc3-7012-83cf-dd5dc67c50be', 'EACH', 'PALLET', 'Pallet'),
  ('01a090a0-1bc3-7013-ae18-5cff131bb0c6', 'EACH', 'BOX',    'Box'),
  ('01a090a0-1bc3-7014-b689-b6007577b4fe', 'EACH', 'BAG',    'Bag'),
  ('01a090a0-1bc3-7015-be45-5dfd58bc7bbd', 'EACH', 'BUNDLE', 'Bundle'),
  -- WEIGHT
  ('01a090a0-1bc3-7016-af94-def6fa656b1b', 'WEIGHT', 'KG', 'Kilogram'),
  ('01a090a0-1bc3-7017-a00e-f6238de933a9', 'WEIGHT', 'G',  'Gram'),
  ('01a090a0-1bc3-7018-94ed-9a5ac37a380a', 'WEIGHT', 'LB', 'Pound'),
  ('01a090a0-1bc3-7019-9509-a52cf6dd3d8e', 'WEIGHT', 'OZ', 'Ounce'),
  ('01a090a0-1bc3-701a-8e80-e4139035b044', 'WEIGHT', 'T',  'Metric Tonne'),
  -- VOLUME
  ('01a090a0-1bc3-701b-892d-09a87119e08e', 'VOLUME', 'L',     'Litre'),
  ('01a090a0-1bc3-701c-b69c-887a53206206', 'VOLUME', 'ML',    'Millilitre'),
  ('01a090a0-1bc3-701d-89a9-647e3ba3e37a', 'VOLUME', 'CL',    'Centilitre'),
  ('01a090a0-1bc3-701e-810b-a9d974fc9525', 'VOLUME', 'FL_OZ', 'Fluid Ounce'),
  ('01a090a0-1bc3-701f-9ee5-55b15a770d26', 'VOLUME', 'GAL',   'Gallon (US)'),
  -- LENGTH
  ('01a090a0-1bc3-7020-be04-77acf9652f83', 'LENGTH', 'M',  'Metre'),
  ('01a090a0-1bc3-7021-bc69-27876338bd97', 'LENGTH', 'CM', 'Centimetre'),
  ('01a090a0-1bc3-7022-9d00-ef67bc662897', 'LENGTH', 'MM', 'Millimetre'),
  ('01a090a0-1bc3-7023-ba82-3de8e74c2f4b', 'LENGTH', 'FT', 'Foot'),
  ('01a090a0-1bc3-7024-94a8-c36d96746cbb', 'LENGTH', 'IN', 'Inch'),
  -- TIME
  ('01a090a0-1bc3-7025-b95e-533cae0df9e0', 'TIME', 'DAY',   'Day'),
  ('01a090a0-1bc3-7026-8827-d07b7f1b0b55', 'TIME', 'HOUR',  'Hour'),
  ('01a090a0-1bc3-7027-b95c-ca80f90ac8db', 'TIME', 'WEEK',  'Week'),
  ('01a090a0-1bc3-7028-a910-dcbfe4291f4f', 'TIME', 'MONTH', 'Month'),
  -- AREA
  ('01a090a0-1bc3-7029-9721-fa20bba295ca', 'AREA', 'SQM',  'Square Metre'),
  ('01a090a0-1bc3-702a-9c1b-f414fda05770', 'AREA', 'SQFT', 'Square Foot'),
  ('01a090a0-1bc3-702b-9a5d-05e6080cca91', 'AREA', 'HA',   'Hectare');

-- ── Seed: standard conversions (both directions) ────────────────────────────
INSERT INTO uom_standard_conversions (id, from_uom, to_uom, factor) VALUES
  -- EACH class (relative to EA)
  ('01a090a0-1bc3-702c-ac81-a39d3d868ed5', 'PAIR',   'EA',     2),
  ('01a090a0-1bc3-702d-8d90-d60b076bbc89', 'EA',     'PAIR',   0.5),
  ('01a090a0-1bc3-702e-b521-df56116fe7ba', 'DOZEN',  'EA',     12),
  ('01a090a0-1bc3-702f-9233-0fce79b3d408', 'EA',     'DOZEN',  0.08333333),
  ('01a090a0-1bc3-7030-922c-b00f56407c1a', 'SCORE',  'EA',     20),
  ('01a090a0-1bc3-7031-a2a6-84c21931af96', 'EA',     'SCORE',  0.05),
  ('01a090a0-1bc3-7032-b8af-de455c2b7830', 'GROSS',  'EA',     144),
  ('01a090a0-1bc3-7033-815d-d49eae44e839', 'EA',     'GROSS',  0.00694444),
  ('01a090a0-1bc3-7034-84c4-9dff067b3f9c', 'CASE',   'EA',     24),
  ('01a090a0-1bc3-7035-ba42-078962ed2af6', 'EA',     'CASE',   0.04166667),
  ('01a090a0-1bc3-7036-83a3-27b03ccba8c8', 'PALLET', 'EA',     100),
  ('01a090a0-1bc3-7037-8000-e1e4feaabad7', 'EA',     'PALLET', 0.01),
  ('01a090a0-1bc3-7038-bff2-5af980bf4477', 'BOX',    'EA',     6),
  ('01a090a0-1bc3-7039-a42e-dc3ad3f9a012', 'EA',     'BOX',    0.16666667),
  -- WEIGHT class (relative to KG)
  ('01a090a0-1bc3-703a-aa86-30151d9790ed', 'G',  'KG', 0.001),
  ('01a090a0-1bc3-703b-98ab-1e266c73af0d', 'KG', 'G',  1000),
  ('01a090a0-1bc3-703c-952d-ae2e97cb9c97', 'LB', 'KG', 0.45359237),
  ('01a090a0-1bc3-703d-917d-41f880d5d7da', 'KG', 'LB', 2.20462262),
  ('01a090a0-1bc3-703e-a2fa-15c36908b15f', 'OZ', 'KG', 0.02834952),
  ('01a090a0-1bc3-703f-b7d9-dfac694b15ca', 'KG', 'OZ', 35.27396195),
  ('01a090a0-1bc3-7040-82a8-732384fc969c', 'T',  'KG', 1000),
  ('01a090a0-1bc3-7041-8289-3ace65d3364d', 'KG', 'T',  0.001),
  -- VOLUME class (relative to L)
  ('01a090a0-1bc3-7042-a2e0-f005d2eb8da2', 'ML',    'L', 0.001),
  ('01a090a0-1bc3-7043-bc26-d7281ce6bb7a', 'L',     'ML', 1000),
  ('01a090a0-1bc3-7044-a05d-7eb0eecd0284', 'CL',    'L', 0.01),
  ('01a090a0-1bc3-7045-845e-4308cb5fa335', 'L',     'CL', 100),
  ('01a090a0-1bc3-7046-8759-87c688d53f26', 'FL_OZ', 'L', 0.02957353),
  ('01a090a0-1bc3-7047-b649-e7f03861d194', 'L',     'FL_OZ', 33.81402265),
  ('01a090a0-1bc3-7048-82f7-d4ac740df339', 'GAL',   'L', 3.78541178),
  ('01a090a0-1bc3-7049-982a-9521187a1637', 'L',     'GAL', 0.26417205),
  -- LENGTH class (relative to M)
  ('01a090a0-1bc3-704a-a362-cc087b244104', 'CM', 'M',  0.01),
  ('01a090a0-1bc3-704b-aa73-f878e0432146', 'M',  'CM', 100),
  ('01a090a0-1bc3-704c-9310-1a210a50fa19', 'MM', 'M',  0.001),
  ('01a090a0-1bc3-704d-9c40-e928df491d95', 'M',  'MM', 1000),
  ('01a090a0-1bc3-704e-973f-14504cfa9bbf', 'FT', 'M',  0.3048),
  ('01a090a0-1bc3-704f-aad4-16cc0db4904a', 'M',  'FT', 3.28083990),
  ('01a090a0-1bc3-7050-8e5f-90a545b47873', 'IN', 'M',  0.0254),
  ('01a090a0-1bc3-7051-bd7f-1ebf7d640314', 'M',  'IN', 39.37007874),
  -- TIME class (relative to DAY)
  ('01a090a0-1bc3-7052-ac7e-fb99b2ada3c3', 'HOUR',  'DAY',  0.04166667),
  ('01a090a0-1bc3-7053-b955-a0a5570a2636', 'DAY',   'HOUR', 24),
  ('01a090a0-1bc3-7054-9756-1a4823cab3f0', 'WEEK',  'DAY',  7),
  ('01a090a0-1bc3-7055-9d63-430a6ac1c9c3', 'DAY',   'WEEK', 0.14285714),
  ('01a090a0-1bc3-7056-9580-b33078096be3', 'MONTH', 'DAY',  30.4375),
  ('01a090a0-1bc3-7057-b171-21c19addc1ca', 'DAY',   'MONTH', 0.03285421),
  -- AREA class (relative to SQM)
  ('01a090a0-1bc3-7058-bf67-3b5921d52731', 'SQFT', 'SQM', 0.09290304),
  ('01a090a0-1bc3-7059-9da7-cdf092ee6542', 'SQM',  'SQFT', 10.76391042),
  ('01a090a0-1bc3-705a-ad1f-16557724b786', 'HA',   'SQM', 10000),
  ('01a090a0-1bc3-705b-a960-93434eaa7b1a', 'SQM',  'HA',  0.0001);
