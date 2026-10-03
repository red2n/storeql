-- A refund belongs to the store its payment was taken at. Every refund path now writes
-- refund_tenders.store_id (V4 added the column, but nothing filled it), so a Z report or a tender
-- mix reads it directly. This gives the rows written before that the store of the payment they
-- refunded. It only fills a column that was never set; no amount, method or time is touched, and a
-- refund whose payment carries no store stays store-less. Idempotent: it selects NULLs only.
UPDATE refund_tenders r
   SET store_id = t.store_id
  FROM payment_tenders t
 WHERE r.store_id IS NULL
   AND t.tenant_id = r.tenant_id
   AND t.id = r.payment_id
   AND t.store_id IS NOT NULL;
