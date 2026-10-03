-- Money in the currency's own minor units (SJ-D25, as purchase-svc's V5 and this service's own V30
-- sale_revenue and V40 bond tables did; the currency minor-units sweep of 2 Oct 2026).
--
-- A breakdown's costs were NUMERIC(18,2) — a statement about sterling. Postgres rounds to a
-- column's declared scale on write without complaint, so a dinar side costing 500.125 was kept as
-- 500.13 and its cuts' unit costs lost their fils, while a yen side came back as 50000.00.
-- inventory-svc now rounds a breakdown's cost, its loss at cost and each cut's unit cost half up to
-- the business currency's minor units (common-service Fx.minorUnits) before it writes them;
-- unconstrained NUMERIC keeps them at that scale. Existing rows are untouched.
--
-- Left as it is, on purpose: reorder_point_plans.ordering_cost NUMERIC(18,2), a planning estimate
-- (the cost of placing one order, an input to the square root of the EOQ), not money that is owed,
-- paid or posted; a fils either way moves no order quantity.

ALTER TABLE yield_runs
    ALTER COLUMN input_cost   TYPE NUMERIC,
    ALTER COLUMN loss_at_cost TYPE NUMERIC;

ALTER TABLE yield_run_outputs ALTER COLUMN unit_cost TYPE NUMERIC;
