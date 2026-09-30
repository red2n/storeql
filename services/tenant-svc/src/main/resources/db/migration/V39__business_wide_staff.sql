-- A business-wide staff assignment: a MANAGER-tier person (head office) held to no store. Until now
-- every assignment named a store, so a manager was always held to stores and what belongs to the
-- whole business (roles, currencies, plan, retention...) had no manager who could change it, only
-- the owner. store_id NULL now means "the whole business"; the service allows it for the MANAGER
-- tier alone, and only an owner grants or removes it.
ALTER TABLE staff_assignments ALTER COLUMN store_id DROP NOT NULL;

-- (tenant, user, store, role) is unique for stores; NULLs never collide there, so the business-wide
-- row needs its own: one per person and role.
CREATE UNIQUE INDEX uq_staff_business_wide
    ON staff_assignments (tenant_id, user_id, role) WHERE store_id IS NULL;
