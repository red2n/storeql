-- A payment run stops when a payee's bank details change after it was approved (payment diversion).
-- That is judged by counting, not by comparing two clocks: every real change of a supplier's bank
-- details moves its version (suppliers.bank_details_version, V1), the approval keeps each payee's
-- version here, and a run whose payee has moved on is stopped. The stamp of the last change
-- (suppliers.bank_details_changed_at) stays for people: who changed it, and when.

-- Each payee of an approved payment run with the version of its bank details at the approval.
CREATE TABLE payment_run_payees (
    tenant_id            UUID    NOT NULL,
    run_id               UUID    NOT NULL REFERENCES payment_runs(id),
    supplier_id          UUID    NOT NULL REFERENCES suppliers(id),
    bank_details_version INTEGER NOT NULL,
    CONSTRAINT pk_payment_run_payees PRIMARY KEY (tenant_id, run_id, supplier_id)
);

COMMENT ON TABLE payment_run_payees IS
    'Each payee of an approved payment run with the version of its bank details at the approval; written on the approval''s transaction.';
