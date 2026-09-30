-- A return made without a receipt has no sale to refund against.
--
-- order-svc announces it itself (NoReceiptReturnRecorded), because payment-svc refunds only what a
-- sale captured. The sales reports must still show the refund on its day at its store, so it lands
-- in sales_facts as a row of its own: keyed by the return (a UUIDv7 that is never an order id),
-- gross zero, the refund in refunded_amount. Sales, days and takings-against-labour then net it
-- without a second table to keep in step.
--
-- The row is a refund, not a sale: it is flagged so the count of orders never includes it.
ALTER TABLE sales_facts ADD COLUMN no_receipt BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN sales_facts.no_receipt IS
    'True for a return made without a receipt (NoReceiptReturnRecorded): order_id is the return, gross is zero, refunded_amount is the refund. Never counted as an order.';
