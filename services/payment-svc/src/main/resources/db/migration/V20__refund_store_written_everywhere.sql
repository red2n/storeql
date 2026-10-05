-- Every refund names its store, so a store's reads take it from the refund alone (2 Oct 2026).
--
-- V12 gave the refunds written before it the store of the payment they refunded, and every path
-- that writes a refund names its store since (the payment's, or an exchange's own store; a card put
-- back through a terminal, the store of the sale it reverses). The store-held reads — the tender
-- mix and the settlement matcher — still fell back to the payment's store through a join, in case a
-- row had none. They now read refund_tenders.store_id alone.
--
-- That is the same answer only if no refund whose payment has a store is left without one. A row
-- written by an older build of payment-svc while V12's ran beside it (a rolling deploy) could be,
-- so V12's back-fill is run once more here, at the moment the fallback goes: it only fills a column
-- that was never set, from the payment refunded; no amount, method or time is touched, and a refund
-- whose payment carries no store stays store-less (it is counted only when every store is read, as
-- before). Idempotent: it selects NULLs only.
UPDATE refund_tenders r
   SET store_id = t.store_id
  FROM payment_tenders t
 WHERE r.store_id IS NULL
   AND t.tenant_id = r.tenant_id
   AND t.id = r.payment_id
   AND t.store_id IS NOT NULL;
