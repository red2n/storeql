-- A recall's source was named for two UK regulators only (FSA, FSS), so a business anywhere else had
-- no accurate source but OTHER. Add country-neutral ones: REGULATOR (whichever authority the
-- business answers to; the notice's own reference says which), MANUFACTURER and SUPPLIER (who
-- issued it), INTERNAL (found in house). FSA and FSS stay valid, and existing rows are unchanged.
ALTER TABLE recalls DROP CONSTRAINT chk_recall_source;
ALTER TABLE recalls ADD CONSTRAINT chk_recall_source CHECK (source IN (
    'SUPPLIER', 'MANUFACTURER', 'REGULATOR', 'FSA', 'FSS', 'INTERNAL', 'OTHER'));
