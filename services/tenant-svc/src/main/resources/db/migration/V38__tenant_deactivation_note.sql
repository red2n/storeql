-- Why a person switched a business off, in their own words (flow catalogue:
-- plat-suspend-and-reactivate-business gap 2). deactivated_reason stays the machine category
-- (NON_PAYMENT / ADMINISTRATOR); the note is what the administrator said, kept with who
-- (deactivated_by) and when (deactivated_at), which V24 already records. A suspension a business can
-- appeal has to be explainable.
ALTER TABLE tenants ADD COLUMN deactivated_note TEXT;

-- A business that is on has no note, the same pair rule as the reason.
ALTER TABLE tenants ADD CONSTRAINT ck_tenant_deactivated_note
    CHECK ((status = 'INACTIVE') OR (deactivated_note IS NULL));
