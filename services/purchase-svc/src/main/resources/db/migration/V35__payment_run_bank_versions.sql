-- A payment run stops when a payee's bank details change after it was approved (payment diversion,
-- V10). That was judged by comparing two times from two clocks: the change was stamped by the
-- service, the approval by the database. When they disagree the guard fails both ways — a change
-- made after approval but stamped earlier was paid to the new account, and one made before was
-- refused. A change is now counted instead: every real change moves the supplier's version, the
-- approval keeps each payee's version, and a run whose payee has moved on is stopped. The stamp
-- stays for people (who changed it, when) and for the proposal's "changed recently" warning.

ALTER TABLE suppliers
    ADD COLUMN bank_details_version INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN suppliers.bank_details_version IS
    'Moves by one on every real change of the bank details; a payment run keeps the version it was approved with.';

CREATE TABLE payment_run_payees (
    tenant_id            UUID    NOT NULL,
    run_id               UUID    NOT NULL REFERENCES payment_runs(id),
    supplier_id          UUID    NOT NULL REFERENCES suppliers(id),
    bank_details_version INTEGER NOT NULL,
    CONSTRAINT pk_payment_run_payees PRIMARY KEY (tenant_id, run_id, supplier_id)
);

COMMENT ON TABLE payment_run_payees IS
    'Each payee of an approved payment run with the version of its bank details at the approval; written on the approval''s transaction.';

-- Runs approved before this migration: their payees' versions are taken as they stand, except a
-- payee whose stamp already reads after the approval, which is kept one behind so the run stays
-- stopped as it was.
INSERT INTO payment_run_payees (tenant_id, run_id, supplier_id, bank_details_version)
SELECT DISTINCT r.tenant_id,
       r.id,
       s.id,
       CASE WHEN s.bank_details_changed_at > r.approved_at THEN -1 ELSE 0 END
FROM payment_runs r
JOIN payment_run_items i ON i.tenant_id = r.tenant_id AND i.run_id = r.id
JOIN suppliers s ON s.tenant_id = i.tenant_id AND s.id = i.supplier_id
WHERE r.status = 'APPROVED';
