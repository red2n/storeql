-- Gap #36: 18 Oracle item attribute groups.
-- System-seeded reference tables (no tenant_id) define the groups and their typed fields.
-- Tenant-scoped variant_attribute_group_values stores per-variant values as JSONB.

CREATE TABLE item_attribute_groups (
    group_code  TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    description TEXT NOT NULL
);

CREATE TABLE item_attribute_group_fields (
    id          UUID    PRIMARY KEY,
    group_code  TEXT    NOT NULL REFERENCES item_attribute_groups(group_code) ON DELETE CASCADE,
    field_code  TEXT    NOT NULL,
    label       TEXT    NOT NULL,
    data_type   TEXT    NOT NULL CHECK (data_type IN ('TEXT','NUMBER','BOOLEAN')),
    required    BOOLEAN NOT NULL DEFAULT false,
    sort_order  INT     NOT NULL DEFAULT 0,
    UNIQUE (group_code, field_code)
);
CREATE INDEX idx_iagf_group ON item_attribute_group_fields (group_code);

CREATE TABLE variant_attribute_group_values (
    id          UUID        PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    variant_id  UUID        NOT NULL REFERENCES product_variants(id) ON DELETE CASCADE,
    group_code  TEXT        NOT NULL REFERENCES item_attribute_groups(group_code),
    values      JSONB       NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, variant_id, group_code)
);
CREATE INDEX idx_vagv_tenant_variant ON variant_attribute_group_values (tenant_id, variant_id);

-- ── Seed the 18 Oracle attribute groups ──────────────────────────────────────

INSERT INTO item_attribute_groups (group_code, name, description) VALUES
  ('MAIN',               'Main',                   'Core item control flags: lot, serial, revision, primary UOM'),
  ('COSTING',            'Costing',                'Cost method enablement and rollup settings'),
  ('PURCHASING',         'Purchasing',             'Buyer-facing pricing, UOM, and delivery rules'),
  ('RECEIVING',          'Receiving',              'Receipt routing, tolerances, and inspection rules'),
  ('PHYSICAL_ATTRIBUTES','Physical Attributes',    'Weight, volume, and container dimensions'),
  ('GENERAL_PLANNING',   'General Planning',       'Reorder method, safety stock, and min/max quantities'),
  ('MRP_MPS_PLANNING',   'MRP / MPS Planning',     'MRP planning method, make/buy code, ATP'),
  ('LEAD_TIMES',         'Lead Times',             'Preprocessing, processing, and post-processing days'),
  ('WIP',                'Work In Process',        'WIP supply type and overcompletion tolerance'),
  ('ORDER_MANAGEMENT',   'Order Management',       'Customer ordering, shippability, and return rules'),
  ('INVOICING',          'Invoicing',              'Invoice enablement, accounting and tax rules'),
  ('SERVICE',            'Service',                'Serviceability, warranty, and contract item settings'),
  ('WEB',                'Web',                    'E-commerce visibility and web description'),
  ('ASSET_MGMT',         'Asset Management',       'Capital asset tracking and depreciation category'),
  ('BOM',                'Bill of Materials',      'BOM item type and engineering item flag'),
  ('QUALITY',            'Quality',                'Inspection enablement and skip-lot percentage'),
  ('HAZMAT',             'Hazardous Material',     'Dangerous goods classification and UN number'),
  ('PROCESS_MFG',        'Process Manufacturing',  'Process recipe, costing, and execution flags');

-- ── MAIN ─────────────────────────────────────────────────────────────────────
INSERT INTO item_attribute_group_fields (id,group_code,field_code,label,data_type,required,sort_order) VALUES
  ('01a090a0-1bc3-706d-a79c-7a663ea33eab', 'MAIN','primary_uom',             'Primary UOM',                    'TEXT',    false, 1),
  ('01a090a0-1bc3-706e-93bb-c395e90a186f', 'MAIN','lot_control_code',        'Lot Control',                    'TEXT',    false, 2),
  ('01a090a0-1bc3-706f-a44e-d71a1baccd04', 'MAIN','serial_number_control',   'Serial Number Control',          'TEXT',    false, 3),
  ('01a090a0-1bc3-7070-b4a2-d7e71dcc0b95', 'MAIN','revision_qty_control',    'Revision Qty Control',           'TEXT',    false, 4),
  ('01a090a0-1bc3-7071-a99f-317f7f857993', 'MAIN','restrict_subinventories', 'Restrict Subinventories',        'BOOLEAN', false, 5),
  ('01a090a0-1bc3-7072-8eab-d1f761230cfb', 'MAIN','restrict_locators',       'Restrict Locators',              'BOOLEAN', false, 6);

-- ── COSTING ──────────────────────────────────────────────────────────────────
INSERT INTO item_attribute_group_fields (id,group_code,field_code,label,data_type,required,sort_order) VALUES
  ('01a090a0-1bc3-7073-a961-1d8127d606f3', 'COSTING','costing_enabled',            'Costing Enabled',               'BOOLEAN', false, 1),
  ('01a090a0-1bc3-7074-9565-65ffbcb5b918', 'COSTING','inventory_asset_value',      'Inventory Asset Value',         'BOOLEAN', false, 2),
  ('01a090a0-1bc3-7075-8335-44dcec15b329', 'COSTING','default_include_in_rollup',  'Include in Cost Rollup',        'BOOLEAN', false, 3),
  ('01a090a0-1bc3-7076-85fa-8876481573ad', 'COSTING','std_lot_size',               'Standard Lot Size',             'NUMBER',  false, 4);

-- ── PURCHASING ───────────────────────────────────────────────────────────────
INSERT INTO item_attribute_group_fields (id,group_code,field_code,label,data_type,required,sort_order) VALUES
  ('01a090a0-1bc3-7077-bfc4-8693c6b85382', 'PURCHASING','list_price',                'List Price',                    'NUMBER',  false, 1),
  ('01a090a0-1bc3-7078-ab95-dd9c1ce3e793', 'PURCHASING','market_price',              'Market Price',                  'NUMBER',  false, 2),
  ('01a090a0-1bc3-7079-9ce3-2d2ec709c1a6', 'PURCHASING','price_tolerance_pct',       'Price Tolerance %',             'NUMBER',  false, 3),
  ('01a090a0-1bc3-707a-9c4d-455bf1ea507e', 'PURCHASING','allow_express_delivery',    'Allow Express Delivery',        'BOOLEAN', false, 4),
  ('01a090a0-1bc3-707b-883d-1b057b97f862', 'PURCHASING','allow_unordered_receipts',  'Allow Unordered Receipts',      'BOOLEAN', false, 5),
  ('01a090a0-1bc3-707c-9222-8584ebc6bf30', 'PURCHASING','allow_substitute_receipts', 'Allow Substitute Receipts',     'BOOLEAN', false, 6);

-- ── RECEIVING ────────────────────────────────────────────────────────────────
INSERT INTO item_attribute_group_fields (id,group_code,field_code,label,data_type,required,sort_order) VALUES
  ('01a090a0-1bc3-707d-980b-ec48f1bba450', 'RECEIVING','receipt_routing',            'Receipt Routing',               'TEXT',    false, 1),
  ('01a090a0-1bc3-707e-9408-5380144b1eb7', 'RECEIVING','qty_rcv_tolerance_pct',      'Qty Receive Tolerance %',       'NUMBER',  false, 2),
  ('01a090a0-1bc3-707f-b7e4-2d4babc69541', 'RECEIVING','days_early_receipt_allowed', 'Days Early Receipt Allowed',    'NUMBER',  false, 3),
  ('01a090a0-1bc3-7080-8313-816f53805b01', 'RECEIVING','days_late_receipt_allowed',  'Days Late Receipt Allowed',     'NUMBER',  false, 4),
  ('01a090a0-1bc3-7081-98a2-8195e3c7f8f4', 'RECEIVING','over_receipt_action',        'Over Receipt Action',           'TEXT',    false, 5),
  ('01a090a0-1bc3-7082-91e4-35b870cbecee', 'RECEIVING','enforce_ship_to_loc',        'Enforce Ship-To Location',      'BOOLEAN', false, 6);

-- ── PHYSICAL_ATTRIBUTES ──────────────────────────────────────────────────────
INSERT INTO item_attribute_group_fields (id,group_code,field_code,label,data_type,required,sort_order) VALUES
  ('01a090a0-1bc3-7083-b449-415e366f45e3', 'PHYSICAL_ATTRIBUTES','unit_weight',      'Unit Weight',                   'NUMBER',  false, 1),
  ('01a090a0-1bc3-7084-875d-280c1d3de06a', 'PHYSICAL_ATTRIBUTES','weight_uom',       'Weight UOM',                    'TEXT',    false, 2),
  ('01a090a0-1bc3-7085-8aad-46e3433422e6', 'PHYSICAL_ATTRIBUTES','unit_volume',      'Unit Volume',                   'NUMBER',  false, 3),
  ('01a090a0-1bc3-7086-b6c6-4d7d31a92371', 'PHYSICAL_ATTRIBUTES','volume_uom',       'Volume UOM',                    'TEXT',    false, 4),
  ('01a090a0-1bc3-7087-92a9-8e6ae6655130', 'PHYSICAL_ATTRIBUTES','container_type_code','Container Type',              'TEXT',    false, 5);

-- ── GENERAL_PLANNING ─────────────────────────────────────────────────────────
INSERT INTO item_attribute_group_fields (id,group_code,field_code,label,data_type,required,sort_order) VALUES
  ('01a090a0-1bc3-7088-b1e8-6521cb669294', 'GENERAL_PLANNING','planning_method',     'Planning Method',               'TEXT',    false, 1),
  ('01a090a0-1bc3-7089-9017-5a6f3ade6c1e', 'GENERAL_PLANNING','safety_stock_method', 'Safety Stock Method',           'TEXT',    false, 2),
  ('01a090a0-1bc3-708a-b76f-1c6c5b313b31', 'GENERAL_PLANNING','safety_stock_pct',    'Safety Stock %',                'NUMBER',  false, 3),
  ('01a090a0-1bc3-708b-80de-34c9cf463513', 'GENERAL_PLANNING','safety_stock_days',   'Safety Stock Days',             'NUMBER',  false, 4),
  ('01a090a0-1bc3-708c-a46b-30744e6396e3', 'GENERAL_PLANNING','min_minmax_qty',      'Min Min-Max Qty',               'NUMBER',  false, 5),
  ('01a090a0-1bc3-708d-9ef9-30834a0be2b1', 'GENERAL_PLANNING','max_minmax_qty',      'Max Min-Max Qty',               'NUMBER',  false, 6),
  ('01a090a0-1bc3-708e-ba4b-b6493e251022', 'GENERAL_PLANNING','fixed_order_qty',     'Fixed Order Qty',               'NUMBER',  false, 7),
  ('01a090a0-1bc3-708f-99e4-b59fe7bdce58', 'GENERAL_PLANNING','fixed_days_supply',   'Fixed Days Supply',             'NUMBER',  false, 8);

-- ── MRP_MPS_PLANNING ─────────────────────────────────────────────────────────
INSERT INTO item_attribute_group_fields (id,group_code,field_code,label,data_type,required,sort_order) VALUES
  ('01a090a0-1bc3-7090-9957-4df42b0574d4', 'MRP_MPS_PLANNING','mrp_planning_method', 'MRP Planning Method',           'TEXT',    false, 1),
  ('01a090a0-1bc3-7091-a9f7-295def44d843', 'MRP_MPS_PLANNING','make_buy_code',       'Make/Buy Code',                 'TEXT',    false, 2),
  ('01a090a0-1bc3-7092-a666-f724fb3b39e5', 'MRP_MPS_PLANNING','calculate_atp',       'Calculate ATP',                 'BOOLEAN', false, 3),
  ('01a090a0-1bc3-7093-953c-7e1ba981f89e', 'MRP_MPS_PLANNING','capable_to_promise',  'Capable to Promise',            'BOOLEAN', false, 4);

-- ── LEAD_TIMES ───────────────────────────────────────────────────────────────
INSERT INTO item_attribute_group_fields (id,group_code,field_code,label,data_type,required,sort_order) VALUES
  ('01a090a0-1bc3-7094-bc13-955d02c8b187', 'LEAD_TIMES','preprocessing_days',        'Pre-Processing Days',           'NUMBER',  false, 1),
  ('01a090a0-1bc3-7095-8308-eb87e8b5ca0c', 'LEAD_TIMES','processing_days',           'Processing Days',               'NUMBER',  false, 2),
  ('01a090a0-1bc3-7096-acea-e23fa0571f71', 'LEAD_TIMES','post_processing_days',      'Post-Processing Days',          'NUMBER',  false, 3);

-- ── WIP ──────────────────────────────────────────────────────────────────────
INSERT INTO item_attribute_group_fields (id,group_code,field_code,label,data_type,required,sort_order) VALUES
  ('01a090a0-1bc3-7097-b803-7cb83f157e0d', 'WIP','supply_type',                      'Supply Type',                   'TEXT',    false, 1),
  ('01a090a0-1bc3-7098-821f-f159d1b710b0', 'WIP','wip_enabled',                      'WIP Enabled',                   'BOOLEAN', false, 2),
  ('01a090a0-1bc3-7099-8ae0-18978c13ac38', 'WIP','overcompletion_tolerance_pct',     'Overcompletion Tolerance %',    'NUMBER',  false, 3);

-- ── ORDER_MANAGEMENT ─────────────────────────────────────────────────────────
INSERT INTO item_attribute_group_fields (id,group_code,field_code,label,data_type,required,sort_order) VALUES
  ('01a090a0-1bc3-709a-a5ab-29572e0ff18e', 'ORDER_MANAGEMENT','customer_ordered',    'Customer Ordered',              'BOOLEAN', false, 1),
  ('01a090a0-1bc3-709b-a908-9900dc42006c', 'ORDER_MANAGEMENT','shippable_item',      'Shippable Item',                'BOOLEAN', false, 2),
  ('01a090a0-1bc3-709c-934c-f1d447e27a16', 'ORDER_MANAGEMENT','returnable',          'Returnable',                    'BOOLEAN', false, 3),
  ('01a090a0-1bc3-709d-9e67-d9087aebf696', 'ORDER_MANAGEMENT','oe_transactable',     'OE Transactable',               'BOOLEAN', false, 4);

-- ── INVOICING ────────────────────────────────────────────────────────────────
INSERT INTO item_attribute_group_fields (id,group_code,field_code,label,data_type,required,sort_order) VALUES
  ('01a090a0-1bc3-709e-8c3c-55445cd390ea', 'INVOICING','invoiceable_item',           'Invoiceable Item',              'BOOLEAN', false, 1),
  ('01a090a0-1bc3-709f-afb1-04a0f59a09b7', 'INVOICING','invoice_enabled',            'Invoice Enabled',               'BOOLEAN', false, 2),
  ('01a090a0-1bc3-70a0-a839-a88aeb7e2f4f', 'INVOICING','accounting_rule_id',         'Accounting Rule',               'TEXT',    false, 3),
  ('01a090a0-1bc3-70a1-bcff-ddf171a4f3f3', 'INVOICING','tax_code',                   'Tax Code',                      'TEXT',    false, 4);

-- ── SERVICE ──────────────────────────────────────────────────────────────────
INSERT INTO item_attribute_group_fields (id,group_code,field_code,label,data_type,required,sort_order) VALUES
  ('01a090a0-1bc3-70a2-a3ab-e97a1b66886d', 'SERVICE','serviceable_product',          'Serviceable Product',           'BOOLEAN', false, 1),
  ('01a090a0-1bc3-70a3-8c14-5a2366f1aff2', 'SERVICE','service_starting_delay_days',  'Service Starting Delay (days)', 'NUMBER',  false, 2),
  ('01a090a0-1bc3-70a4-8e4d-0f6968a9ffdf', 'SERVICE','contract_item_type',           'Contract Item Type',            'TEXT',    false, 3),
  ('01a090a0-1bc3-70a5-a32e-ac5fe8fc37e2', 'SERVICE','warranty_vendor_id',           'Warranty Vendor',               'TEXT',    false, 4);

-- ── WEB ──────────────────────────────────────────────────────────────────────
INSERT INTO item_attribute_group_fields (id,group_code,field_code,label,data_type,required,sort_order) VALUES
  ('01a090a0-1bc3-70a6-8fa6-2e97ab9d49ad', 'WEB','web_status',                       'Web Status',                    'TEXT',    false, 1),
  ('01a090a0-1bc3-70a7-ac22-ab9f4ef3d1ac', 'WEB','browsable',                        'Browsable',                     'BOOLEAN', false, 2),
  ('01a090a0-1bc3-70a8-a9aa-4b52800cabda', 'WEB','web_long_description',             'Web Long Description',          'TEXT',    false, 3);

-- ── ASSET_MGMT ───────────────────────────────────────────────────────────────
INSERT INTO item_attribute_group_fields (id,group_code,field_code,label,data_type,required,sort_order) VALUES
  ('01a090a0-1bc3-70a9-b192-f6302044d10c', 'ASSET_MGMT','asset_creation',            'Asset Creation',                'TEXT',    false, 1),
  ('01a090a0-1bc3-70aa-aff3-ebd4d4049be1', 'ASSET_MGMT','asset_category_id',         'Asset Category',                'TEXT',    false, 2),
  ('01a090a0-1bc3-70ab-8235-17122278e5d2', 'ASSET_MGMT','capitalize',                'Capitalize',                    'BOOLEAN', false, 3);

-- ── BOM ──────────────────────────────────────────────────────────────────────
INSERT INTO item_attribute_group_fields (id,group_code,field_code,label,data_type,required,sort_order) VALUES
  ('01a090a0-1bc3-70ac-bdd4-1cbe91b4fc1f', 'BOM','bom_item_type',                    'BOM Item Type',                 'TEXT',    false, 1),
  ('01a090a0-1bc3-70ad-9006-cdcf25b34ac5', 'BOM','bom_enabled',                      'BOM Enabled',                   'BOOLEAN', false, 2),
  ('01a090a0-1bc3-70ae-937d-c807ec03ee78', 'BOM','eng_item',                         'Engineering Item',               'BOOLEAN', false, 3);

-- ── QUALITY ──────────────────────────────────────────────────────────────────
INSERT INTO item_attribute_group_fields (id,group_code,field_code,label,data_type,required,sort_order) VALUES
  ('01a090a0-1bc3-70af-85e8-a2654323bc40', 'QUALITY','quality_inspection_enabled',   'Quality Inspection Enabled',    'BOOLEAN', false, 1),
  ('01a090a0-1bc3-70b0-b418-f10811ad8e64', 'QUALITY','skip_lot_pct',                 'Skip Lot %',                    'NUMBER',  false, 2);

-- ── HAZMAT ───────────────────────────────────────────────────────────────────
INSERT INTO item_attribute_group_fields (id,group_code,field_code,label,data_type,required,sort_order) VALUES
  ('01a090a0-1bc3-70b1-aa99-ed11382c7c1d', 'HAZMAT','hazardous_material',            'Hazardous Material',            'BOOLEAN', false, 1),
  ('01a090a0-1bc3-70b2-b4ea-eca3818cd5d7', 'HAZMAT','un_number',                     'UN Number',                     'TEXT',    false, 2),
  ('01a090a0-1bc3-70b3-824c-92e91c4e016a', 'HAZMAT','hazard_class_id',               'Hazard Class',                  'TEXT',    false, 3),
  ('01a090a0-1bc3-70b4-a9ce-fc7e6c65e0e6', 'HAZMAT','shipping_name',                 'Proper Shipping Name',          'TEXT',    false, 4);

-- ── PROCESS_MFG ──────────────────────────────────────────────────────────────
INSERT INTO item_attribute_group_fields (id,group_code,field_code,label,data_type,required,sort_order) VALUES
  ('01a090a0-1bc3-70b5-8746-c6dca89de645', 'PROCESS_MFG','process_item',             'Process Item',                  'BOOLEAN', false, 1),
  ('01a090a0-1bc3-70b6-af38-505398e17b9c', 'PROCESS_MFG','recipe_enabled',           'Recipe Enabled',                'BOOLEAN', false, 2),
  ('01a090a0-1bc3-70b7-b0fa-0c8f279791ca', 'PROCESS_MFG','process_costing_enabled',  'Process Costing Enabled',       'BOOLEAN', false, 3),
  ('01a090a0-1bc3-70b8-af35-39b13c22a2c9', 'PROCESS_MFG','process_supply_type',      'Process Supply Type',           'TEXT',    false, 4);
