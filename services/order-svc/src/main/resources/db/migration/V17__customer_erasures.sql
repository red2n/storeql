-- A customer erased by a shop must lose their name, phone and address from that shop's orders too, and
-- the erasure reaches the shopper's online orders as well as the shop's own customer record.
--
-- An order that is still open keeps its delivery details until it is finished: they are needed to
-- deliver it, and performing the contract is a lawful reason to delay erasure. So the erasure is
-- recorded here, and a sweeper redacts each open order once it reaches a settled state. The sale
-- itself — amounts, tax, lines — stays: tax law requires it. Only what identifies the person goes.
CREATE TABLE customer_erasures (
    tenant_id   UUID        NOT NULL,
    customer_id UUID        NOT NULL,
    event_id    UUID        NOT NULL,
    erased_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Erasure has to reach a person by either id: the shop erases a customer it knows (customer_id), and
    -- that same person's online orders are filed under their login (login_id). Recording both on the
    -- erasure lets one sweep find both.
    login_id    UUID,
    CONSTRAINT pk_customer_erasures PRIMARY KEY (tenant_id, customer_id)
);
CREATE INDEX idx_customer_erasures_login
    ON customer_erasures (login_id) WHERE login_id IS NOT NULL;
