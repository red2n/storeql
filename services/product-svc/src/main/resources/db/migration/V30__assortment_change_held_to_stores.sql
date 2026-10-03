-- Whether a range change was decided by a manager held to stores (2 Oct 2026, catalogue-hygiene).
--
-- A manager held to stores may change a line's range only at their own stores, and never move it to
-- or from "every store" (no product_stores rows): that is for an owner or a manager of the whole
-- business. The change is judged when it is recorded, but it is applied on its own day, and the
-- range may have moved in between — a line ranged to another shop when a branch manager planned to
-- list it at theirs may be sold everywhere by then, and listing it would take it off every other
-- shelf. So the change keeps who could make it, and the sweep judges it again on the range it finds.
--
-- Rows recorded before this were not marked, and are read as a business-wide decision, as they were
-- applied until now.

ALTER TABLE assortment_changes
    ADD COLUMN held_to_stores BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN assortment_changes.held_to_stores IS
    'Decided by a manager held to stores: never applied so as to move the line to or from every store.';
