// order-svc: the order lifecycle (place, pay, fulfil, return, cancel, void), receipts, the POS log,
// fiscal receipts, special orders, parked sales, no-sale, gift cards, layaways and the online
// shopper's view — with the refusals around each.
//
//   k6/run.sh order-crud
import { sleep } from 'k6';
import http from 'k6/http';
import {
  ALL_CHECKS_PASS,
  BASE,
  call,
  data,
  expect,
  issueGiftCardByHand,
  newKey,
  onboardTenant,
  poll,
  priceVariants,
  receive,
  register,
  sellGiftCard,
  sellableVariant,
  staffUser,
  truthy,
} from './lib/storeql.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS, setupTimeout: '4m' };

const UNKNOWN = '01a0b000-0000-7000-8000-000000000000';

export function setup() {
  const tenant = onboardTenant('order', { stores: 1 });
  const rival = onboardTenant('order-rival', { stores: 1 });
  const storeId = tenant.stores[0].id;
  tenant.variantId = sellableVariant(tenant, 'Ordered mug').variantId;
  priceVariants(tenant, [tenant.variantId], '12.50');
  if (receive(tenant, storeId, tenant.variantId, 200).status !== 201) throw new Error('receive failed');
  tenant.cashier = staffUser(tenant, 'CASHIER', [storeId]);
  return { tenant, rival, shopper: register('order-shopper'), stranger: register('order-stranger') };
}

export default function ({ tenant, rival, shopper, stranger }) {
  const t = tenant.owner.token;
  const storeId = tenant.stores[0].id;
  const variantId = tenant.variantId;
  const sale = (extra = {}) => ({ storeId, channel: 'POS', fulfilmentType: 'INSTORE', paymentMethod: 'CASH', items: [{ variantId, qty: 2 }], ...extra });
  const place = (body, opts = {}) => call('POST', '/api/order-svc/orders', { token: t, idem: true, body, ...opts });
  const pay = (order) =>
    call('POST', '/api/payment-svc/payments', { token: t, idem: true, body: { orderId: order.id, amount: order.total, method: 'CASH', storeId, currency: 'GBP' } });
  const statusOf = (id) => data(call('GET', `/api/order-svc/orders/${id}`, { token: t })).status;
  const parkedOnline = () =>
    call('POST', '/api/order-svc/orders', { token: shopper.token, storefront: tenant.tenantId, idem: true, body: { storeId, channel: 'ONLINE', fulfilmentType: 'PICKUP', items: [{ variantId, qty: 1 }] } });

  // ── placing orders ──────────────────────────────────────────────────────────
  expect(place({ ...sale(), channel: undefined }), '[-] order: channel required', 400);
  expect(place({ ...sale(), items: [{ variantId, qty: 0 }] }), '[-] order: quantity above zero', 400);
  expect(place(sale({ paymentMethod: 'BARTER' })), '[-] order: payment method must be known', 400, 'ORDER_PAYMENT_METHOD_INVALID');
  expect(place(sale({ fulfilmentType: 'DELIVERY' })), '[-] delivery needs an address', 400, 'ORDER_DELIVERY_ADDRESS_REQUIRED');
  expect(place(sale(), { token: shopper.token, storefront: tenant.tenantId }), '[-] a shopper cannot place a POS order', 403);
  expect(call('POST', '/api/order-svc/orders', { idem: true, body: sale() }), '[-] no token', 401);

  const exempt = place(sale({ taxExempt: true, exemptReason: 'Registered charity' }));
  expect(exempt, '[+] place a tax-exempt POS order', 201);
  // Only recorded: VAT still comes from pricing-svc's quote (VAT codes, customer VAT status).
  truthy('[+] tax exempt: flag and reason recorded, priced from the list', data(exempt).taxExempt === true && data(exempt).exemptReason === 'Registered charity' && Number(data(exempt).subtotal) === 25, data(exempt));

  const order = data(place(sale()));
  truthy('[+] a POS order starts PENDING', order.status === 'PENDING', order);
  expect(call('GET', `/api/order-svc/orders/${order.id}`, { token: t }), '[+] get order', 200);
  expect(call('GET', `/api/order-svc/orders/${order.id}`, { token: rival.owner.token }), "[-] a rival cannot read our order", 404);
  expect(call('GET', `/api/order-svc/orders/${UNKNOWN}`, { token: t }), '[-] unknown order', 404);
  const list = call('GET', `/api/order-svc/orders?store=${storeId}&channel=POS&limit=100`, { token: t });
  expect(list, '[+] list POS orders at the store', 200);
  truthy('[+] ...includes both', [order.id, data(exempt).id].every((id) => (data(list) || []).some((o) => o.id === id)), (data(list) || []).length);
  expect(call('GET', '/api/order-svc/orders', { token: shopper.token }), "[-] a shopper cannot list the tenant's orders", 403);

  // ── pay, fulfil, return ─────────────────────────────────────────────────────
  expect(
    call('POST', `/api/order-svc/orders/${order.id}/returns`, { token: t, idem: true, body: { reason: 'Too early', items: [{ variantId, qty: 1, condition: 'SEALED' }] } }),
    '[-] return before the goods were handed over',
    409,
    'ORDER_CANNOT_RETURN'
  );
  expect(pay(order), '[+] take payment', [200, 201]);
  // A till sale is handed over the moment it is paid for.
  poll(30, () => statusOf(order.id) === 'FULFILLED');
  truthy('[+] paying for a till sale fulfils it', statusOf(order.id) === 'FULFILLED', statusOf(order.id));
  expect(call('POST', `/api/order-svc/orders/${order.id}/fulfil`, { token: t }), '[-] fulfil twice', 409, 'ORDER_NOT_FULFILLABLE');
  expect(call('POST', `/api/order-svc/orders/${order.id}/cancel`, { token: t, body: { reason: 'Too late' } }), '[-] cancel a fulfilled order', 409);
  expect(call('POST', `/api/order-svc/orders/${order.id}/returns`, { token: t, idem: true, body: { items: [{ variantId, qty: 1, condition: 'SEALED' }] } }), '[-] return: reason required', 400);
  expect(
    call('POST', `/api/order-svc/orders/${order.id}/returns`, { token: t, idem: true, body: { reason: 'Too many', items: [{ variantId, qty: 5, condition: 'SEALED' }] } }),
    '[-] return more than was bought',
    [400, 409, 422]
  );
  expect(
    call('POST', `/api/order-svc/orders/${order.id}/returns`, { token: t, idem: true, body: { reason: 'Chipped', refundMethod: 'ORIGINAL', items: [{ variantId, qty: 1, condition: 'DAMAGED' }] } }),
    '[+] return one mug',
    201
  );
  expect(call('GET', `/api/order-svc/orders/${order.id}/returns`, { token: t }), '[+] list returns', 200);
  const history = call('GET', `/api/order-svc/orders/${order.id}/history`, { token: t });
  expect(history, '[+] order history', 200);
  truthy('[+] history ends FULFILLED', JSON.stringify(data(history)).includes('FULFILLED'), data(history));

  // ── part-fulfilment (SJ-D35) ────────────────────────────────────────────────
  const onHand = () => {
    const rows = data(call('GET', `/api/inventory-svc/admin/inventory/levels?store=${storeId}&limit=100`, { token: t })) || [];
    const row = rows.find((r) => r.variantId === variantId);
    return row ? Number(row.onHand) : NaN;
  };
  // The till sale (-2) and the returned mug (+1) above reach inventory-svc asynchronously; wait for
  // the level to settle so what follows measures the part-fulfilment and nothing else.
  // The chipped mug came back DAMAGED: it is off sale in a batch of its own (return controls), not
  // back on the shelf, so on hand is what the sale left.
  truthy('[+] inventory settled after the till sale and the return', poll(30, () => onHand() === 200 - 2) >= 0, onHand());
  const before = onHand();
  const partial = data(place({ storeId, channel: 'ONLINE', fulfilmentType: 'PICKUP', items: [{ variantId, qty: 4 }] }));
  expect(call('POST', `/api/order-svc/orders/${partial.id}/confirm`, { token: t, body: {} }), '[+] confirm a four-mug pickup order', 200);
  expect(
    call('POST', `/api/order-svc/orders/${partial.id}/fulfil`, { token: t, body: { lines: [{ variantId, qty: 5 }] } }),
    '[-] hand over more than was ordered',
    409,
    'ORDER_FULFIL_QTY_EXCEEDS_OUTSTANDING'
  );
  expect(
    call('POST', `/api/order-svc/orders/${partial.id}/fulfil`, { token: t, body: { lines: [{ variantId: UNKNOWN, qty: 1 }] } }),
    '[-] hand over a line that is not on the order',
    400,
    'ORDER_FULFIL_LINE_UNKNOWN'
  );
  const one = call('POST', `/api/order-svc/orders/${partial.id}/fulfil`, { token: t, body: { lines: [{ variantId, qty: 1 }] } });
  expect(one, '[+] hand over one of four', 200);
  truthy('[+] the order is PARTIALLY_FULFILLED with one handed over', data(one).status === 'PARTIALLY_FULFILLED' && Number(data(one).items[0].fulfilledQty) === 1, data(one));
  expect(call('POST', `/api/order-svc/orders/${partial.id}/cancel`, { token: t, body: { reason: 'changed mind' } }), '[-] cancel once goods have gone out', 409, 'ORDER_PARTLY_FULFILLED');
  expect(
    call('POST', `/api/order-svc/orders/${partial.id}/returns`, { token: t, idem: true, body: { reason: 'Too many', refundMethod: 'ORIGINAL', items: [{ variantId, qty: 2, condition: 'SEALED' }] } }),
    '[-] return two when only one was handed over',
    409,
    'RETURN_QTY_EXCEEDS_PURCHASED'
  );
  truthy('[+] inventory deducted exactly the one that left', poll(30, () => onHand() === before - 1) >= 0, { before, now: onHand() });
  const rest = call('POST', `/api/order-svc/orders/${partial.id}/fulfil`, { token: t });
  expect(rest, '[+] hand over the rest with no body', 200);
  truthy('[+] now FULFILLED, four of four', data(rest).status === 'FULFILLED' && Number(data(rest).items[0].fulfilledQty) === 4, data(rest));
  truthy('[+] inventory deducted the other three, and nothing twice', poll(30, () => onHand() === before - 4) >= 0, { before, now: onHand() });
  expect(call('POST', `/api/order-svc/orders/${partial.id}/fulfil`, { token: t }), '[-] nothing left to hand over', 409, 'ORDER_NOT_FULFILLABLE');
  const partialHistory = JSON.stringify(data(call('GET', `/api/order-svc/orders/${partial.id}/history`, { token: t })));
  truthy('[+] the history says how much went when', partialHistory.includes('part-fulfilled: 1 of 4'), partialHistory);

  // ── catalog mode: placed without prices, priced by a manager (SJ-D41) ─────
  const catalog = place({ storeId, channel: 'POS', fulfilmentType: 'PICKUP', awaitingPrice: true, items: [{ variantId, qty: 3, unitPrice: 0 }] });
  expect(catalog, '[+] a catalog-mode till order is placed without prices', 201);
  truthy('[+] and waits for a price rather than sitting PENDING for the sweeper', data(catalog).status === 'AWAITING_PRICE', data(catalog));
  const cashierToken = tenant.cashier ? tenant.cashier.token : null;
  expect(call('POST', `/api/order-svc/orders/${data(catalog).id}/price`, { token: t, body: { lines: [{ variantId, unitPrice: -1 }], taxAmount: 0 } }), '[-] a price below zero', 400);
  expect(call('POST', `/api/order-svc/orders/${data(catalog).id}/price`, { token: t, body: { lines: [{ variantId: UNKNOWN, unitPrice: 4.5 }], taxAmount: 0 } }), '[-] a line that is not on the order', 400, 'ORDER_PRICE_LINE_UNKNOWN');
  const priced = call('POST', `/api/order-svc/orders/${data(catalog).id}/price`, { token: t, body: { lines: [{ variantId, unitPrice: 4.5 }], taxAmount: 2.7 } });
  expect(priced, '[+] a manager prices it', 200);
  truthy('[+] PENDING, totals recomputed: 3 × 4.50 + 2.70', data(priced).status === 'PENDING' && Number(data(priced).total) === 16.2 && Number(data(priced).subtotal) === 13.5, data(priced));
  expect(call('POST', `/api/order-svc/orders/${data(catalog).id}/price`, { token: t, body: { lines: [{ variantId, unitPrice: 9 }], taxAmount: 0 } }), '[-] priced twice', 409, 'ORDER_NOT_AWAITING_PRICE');
  expect(pay(data(priced)), '[+] and paid for like any other till order', [200, 201]);
  poll(30, () => statusOf(data(catalog).id) === 'FULFILLED');
  truthy('[+] handed over when the payment lands', statusOf(data(catalog).id) === 'FULFILLED', statusOf(data(catalog).id));
  expect(place({ storeId, channel: 'ONLINE', fulfilmentType: 'PICKUP', awaitingPrice: true, items: [{ variantId, qty: 1 }] }), '[-] an online order cannot be placed awaiting a price', 400, 'ORDER_AWAITING_PRICE_POS_ONLY');

  // ── cancel and void ─────────────────────────────────────────────────────────
  const toCancel = data(place(sale()));
  expect(call('POST', `/api/order-svc/orders/${toCancel.id}/cancel`, { token: t, body: {} }), '[-] cancel: a body must give a reason', 400);
  expect(call('POST', `/api/order-svc/orders/${toCancel.id}/cancel`, { token: t, body: { reason: '' } }), '[-] cancel: a blank reason is refused — what the admin dialog used to send (SJ-D49)', 400);
  expect(call('POST', `/api/order-svc/orders/${toCancel.id}/cancel`, { token: t, body: { reason: 'x'.repeat(501) } }), '[-] cancel: a reason longer than 500 characters', 400);
  expect(call('POST', `/api/order-svc/orders/${toCancel.id}/cancel`, { token: t, body: { reason: 'Customer walked out' } }), '[+] cancel a pending order', 200);
  expect(call('POST', `/api/order-svc/orders/${toCancel.id}/fulfil`, { token: t }), '[-] fulfil a cancelled order', 409, 'ORDER_NOT_FULFILLABLE');
  expect(
    call('POST', `/api/order-svc/orders/${toCancel.id}/returns`, { token: t, idem: true, body: { reason: 'Never had it', items: [{ variantId, qty: 1, condition: 'SEALED' }] } }),
    '[-] return a cancelled order',
    409,
    'ORDER_CANNOT_RETURN'
  );
  const toVoid = data(place(sale()));
  expect(call('POST', `/api/order-svc/orders/${toVoid.id}/void`, { token: t, idem: true, body: {} }), '[-] void: reason required', 400);
  expect(call('POST', `/api/order-svc/orders/${toVoid.id}/void`, { token: t, idem: true, body: { reason: 'Rang up twice' } }), '[+] void a POS order', 200);
  truthy('[+] ...VOIDED', statusOf(toVoid.id) === 'VOIDED', statusOf(toVoid.id));
  expect(call('POST', `/api/order-svc/orders/${toVoid.id}/void`, { token: t, idem: true, body: { reason: 'Again' } }), '[-] void twice', 409);

  // ── the back-office void (09.13) ────────────────────────────────────────────
  // A management action: the cashier who rang the sale is refused before it is touched, an
  // online order is refused because it is cancelled or returned instead, and the manager's
  // void is counted against them on the staff exception report.
  const standingSale = data(place(sale()));
  expect(pay(standingSale), '[+] pay for a sale to void from the back office', [200, 201]);
  poll(30, () => statusOf(standingSale.id) === 'FULFILLED');
  truthy('[+] ...handed over at the till', statusOf(standingSale.id) === 'FULFILLED', statusOf(standingSale.id));
  expect(call('POST', `/api/order-svc/orders/${standingSale.id}/void`, { token: cashierToken, idem: true, body: { reason: 'Rang up twice' } }), '[-] a cashier cannot void a completed sale', 403);
  truthy('[+] ...and the sale still stands', statusOf(standingSale.id) === 'FULFILLED', statusOf(standingSale.id));
  expect(call('POST', `/api/order-svc/orders/${standingSale.id}/void`, { token: t, idem: true, body: { reason: 'Rang up twice' } }), '[+] the owner voids it from the back office', 200);
  truthy('[+] ...VOIDED', statusOf(standingSale.id) === 'VOIDED', statusOf(standingSale.id));
  const webOrder = data(parkedOnline());
  expect(call('POST', `/api/order-svc/orders/${webOrder.id}/void`, { token: t, idem: true, body: { reason: 'Not a till sale' } }), '[-] an online order is cancelled or returned, never voided', 409, 'ORDER_VOID_ONLY_POS');
  const exceptionRows = data(call('GET', `/api/order-svc/admin/reports/exceptions?storeId=${storeId}&groupBy=ACTOR`, { token: t }));
  const ownerRow = (exceptionRows.rows || []).find((r) => r.groupKey === tenant.owner.userId);
  truthy('[+] the voids are counted against who made them', !!ownerRow && Number(ownerRow.voids) >= 2, JSON.stringify(exceptionRows));
  expect(call('GET', `/api/order-svc/admin/reports/exceptions?storeId=${storeId}`, { token: cashierToken }), '[-] a cashier cannot read who is voiding', 403);

  // ── abuse: the void does not wear down, leak across tenants, or race ─────────
  const voidAt = (id, token, reason) => ['POST', `${BASE}/api/order-svc/orders/${id}/void`, JSON.stringify({ reason }), { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}`, 'Idempotency-Key': newKey('void') }, tags: { name: 'POST /api/order-svc/orders/{id}/void (batch)' } }];
  const abused = data(place(sale()));
  expect(pay(abused), '[+] a second paid sale for the abuse cases', [200, 201]);
  poll(30, () => statusOf(abused.id) === 'FULFILLED');
  const hammered = http.batch(Array.from({ length: 20 }, (_, i) => voidAt(abused.id, cashierToken, `attempt ${i}`)));
  truthy('[-] twenty cashier attempts at once: every one refused', hammered.every((r) => r.status === 403 || r.status === 429), hammered.map((r) => r.status).join(','));
  truthy('[+] ...and the sale still stands', statusOf(abused.id) === 'FULFILLED', statusOf(abused.id));
  expect(call('POST', `/api/order-svc/orders/${abused.id}/void`, { token: rival.owner.token, idem: true, body: { reason: 'Not mine' } }), '[-] another tenant cannot void it', 404);
  expect(call('POST', `/api/order-svc/orders/${abused.id}/void`, { token: t, idem: true, body: { reason: 'x'.repeat(501) } }), '[-] a reason longer than 500 characters', 400);
  truthy('[+] ...and it still stands', statusOf(abused.id) === 'FULFILLED', statusOf(abused.id));
  const raceVoids = http.batch(Array.from({ length: 6 }, () => voidAt(abused.id, t, 'race')));
  const raceWon = raceVoids.filter((r) => r.status === 200).length;
  truthy('[+] six managers at once: exactly one void, the rest told it is done', raceWon === 1 && raceVoids.every((r) => r.status === 200 || r.status === 409), raceVoids.map((r) => r.status).join(','));
  truthy('[+] ...VOIDED once', statusOf(abused.id) === 'VOIDED', statusOf(abused.id));

  // ── receipts, POS log, fiscal receipt ──────────────────────────────────────
  const receipts = `/api/order-svc/admin/orders/${order.id}/receipts`;
  expect(call('POST', receipts, { token: t, body: { receiptType: 'PRINT', printCount: 1 } }), '[+] print a receipt', 201);
  expect(call('POST', receipts, { token: t, body: { receiptType: 'THERMAL', printCount: 1 } }), '[+] a receipt printed on thermal paper is recorded as such (09.12)', 201);
  expect(call('POST', receipts, { token: t, body: { receiptType: 'SAVE', printCount: 1 } }), '[+] and one kept as a file', 201);
  expect(call('POST', receipts, { token: t, body: { receiptType: 'EMAIL', emailedTo: 'customer@example.com' } }), '[+] email a receipt', 201);
  expect(call('POST', receipts, { token: t, body: { receiptType: 'EMAIL' } }), '[-] email receipt: address required', 400);
  expect(call('GET', receipts, { token: t }), '[+] list receipts', 200);
  truthy('[+] the list names how each came out', ['PRINT', 'THERMAL', 'SAVE', 'EMAIL'].every((k) => data(call('GET', receipts, { token: t })).some((r) => r.receiptType === k)));
  expect(call('POST', `/api/order-svc/admin/orders/${UNKNOWN}/receipts`, { token: t, body: { receiptType: 'PRINT' } }), '[-] receipt for an unknown order', 404);

  const logged = call('POST', `/api/order-svc/pos/log/orders/${order.id}`, { token: t });
  expect(logged, '[+] write the POS log entry', 201);
  // The till calls this right after taking money, so it is on the retry path: a repeat is the same entry.
  const relogged = call('POST', `/api/order-svc/pos/log/orders/${order.id}`, { token: t });
  truthy('[+] writing it again returns the same entry', relogged.status === 201 && data(relogged).id === data(logged).id, { first: data(logged).id, again: data(relogged).id });
  expect(call('POST', `/api/order-svc/pos/log/orders/${data(parkedOnline()).id}`, { token: t }), '[-] POS log is for POS orders only', 400, 'POSLOG_NOT_POS');
  expect(call('POST', `/api/order-svc/pos/log/orders/${UNKNOWN}`, { token: t }), '[-] POS log for an unknown order', 404);
  expect(call('GET', `/api/order-svc/admin/pos-log?storeId=${storeId}`, { token: t }), '[+] list the POS log', 200);
  expect(call('GET', `/api/order-svc/admin/pos-log/orders/${order.id}`, { token: t }), '[+] POS log for the order', 200);

  const fiscal = call('POST', `/api/order-svc/admin/orders/${order.id}/fiscal-receipt`, { token: t });
  expect(fiscal, '[+] issue the fiscal receipt', 200);
  const again = call('POST', `/api/order-svc/admin/orders/${order.id}/fiscal-receipt`, { token: t });
  truthy('[+] issuing again returns the same number', again.status === 200 && JSON.stringify(data(again)) === JSON.stringify(data(fiscal)), { first: data(fiscal), again: data(again) });
  expect(call('GET', `/api/order-svc/orders/${order.id}/fiscal-receipt`, { token: t }), '[+] read the fiscal receipt', 200);
  expect(call('GET', `/api/order-svc/admin/fiscal-receipts?storeId=${storeId}`, { token: t }), '[+] list fiscal receipts', 200);
  expect(call('GET', `/api/order-svc/admin/fiscal-receipts/audit?storeId=${storeId}`, { token: t }), '[+] fiscal receipt audit', 200);
  expect(call('GET', '/api/order-svc/admin/fiscal-receipts', { token: t }), '[-] fiscal receipts: store required', 400);

  // ── special orders ──────────────────────────────────────────────────────────
  const special = { storeId, customerName: 'Jane Doe', customerEmail: 'jane@example.com', deliveryAddress: '123 High Street', requestedDeliveryDate: '2027-07-01', items: [{ variantId, qty: 1, unitPrice: 49.99 }], currency: 'GBP' };
  expect(call('POST', '/api/order-svc/admin/special-orders', { token: t, body: { ...special, storeId: undefined } }), '[-] special order: store required', 400);
  const so = call('POST', '/api/order-svc/admin/special-orders', { token: t, body: special });
  expect(so, '[+] create a special order', 201);
  const soId = data(so).id;
  expect(call('GET', '/api/order-svc/admin/special-orders', { token: t }), '[+] list special orders', 200);
  expect(call('GET', `/api/order-svc/admin/special-orders/${soId}`, { token: t }), '[+] get special order', 200);
  expect(call('GET', `/api/order-svc/admin/special-orders/${soId}`, { token: rival.owner.token }), "[-] a rival cannot read it", 404);
  expect(call('POST', `/api/order-svc/admin/special-orders/${soId}/fulfil`, { token: t }), '[-] fulfil before confirming', 409);
  expect(call('POST', `/api/order-svc/admin/special-orders/${soId}/confirm`, { token: t }), '[+] confirm special order', 200);
  expect(call('POST', `/api/order-svc/admin/special-orders/${soId}/fulfil`, { token: t }), '[+] fulfil special order', 200);
  expect(call('POST', `/api/order-svc/admin/special-orders/${soId}/cancel`, { token: t }), '[-] cancel a fulfilled special order', 409);
  expect(call('POST', '/api/order-svc/admin/special-orders', { token: shopper.token, body: special }), '[-] a customer cannot create special orders', 403);

  // ── parked sales and no-sale ────────────────────────────────────────────────
  const parked = call('POST', '/api/order-svc/pos/parked-sales', { token: t, body: { storeId, customerName: 'Queue 2', items: [{ variantId, qty: 1, unitPrice: 12.5 }] } });
  expect(parked, '[+] park a sale', 201);
  expect(call('POST', '/api/order-svc/pos/parked-sales', { token: t, body: { storeId } }), '[-] park: items required', 400);
  expect(call('GET', `/api/order-svc/pos/parked-sales?storeId=${storeId}`, { token: t }), '[+] list parked sales', 200);
  expect(call('GET', `/api/order-svc/pos/parked-sales/${data(parked).id}`, { token: t }), '[+] get parked sale', 200);
  expect(call('GET', `/api/order-svc/pos/parked-sales/${data(parked).id}`, { token: rival.owner.token }), "[-] a rival cannot see it", 404);
  expect(call('DELETE', `/api/order-svc/pos/parked-sales/${data(parked).id}`, { token: t }), '[+] discard parked sale', [200, 204]);
  expect(call('GET', `/api/order-svc/pos/parked-sales/${data(parked).id}`, { token: t }), '[-] a discarded sale is gone', 404);
  expect(call('POST', '/api/order-svc/pos/no-sale', { token: t, body: { storeId, reason: 'Change for the float' } }), '[+] open the drawer for no sale', 201);
  expect(call('POST', '/api/order-svc/pos/no-sale', { token: shopper.token, body: { storeId } }), '[-] a customer cannot open the drawer', 403);

  // ── gift cards ──────────────────────────────────────────────────────────────
  // Value handed out by hand is management's: a reason, an Idempotency-Key and no tender. A card a
  // customer pays for is a gift-card line on a till sale, loaded when the sale is paid.
  const giftCards = '/api/order-svc/gift-cards';
  const issueKey = newKey('gift-issue');
  const issued = () => issueGiftCardByHand(t, storeId, 50, { key: issueKey, extra: { currency: 'GBP', note: 'a late order' } });
  const card = issued();
  expect(card, '[+] the owner issues a £50 gift card by hand, for a reason', 201);
  const code = data(card).code;
  const cardAgain = issued();
  truthy('[+] ...and the same issue sent again with its key is the first card, not a second', [200, 201].includes(cardAgain.status) && data(cardAgain).code === code, { first: code, again: data(cardAgain).code, status: cardAgain.status });
  expect(issueGiftCardByHand(t, storeId, 60, { key: issueKey }), '[-] gift card: a key that issued one card does not issue another amount', 409, 'IDEMPOTENCY_KEY_REUSED');
  expect(issueGiftCardByHand(t, storeId, 0), '[-] gift card: amount above zero', 400);
  expect(issueGiftCardByHand(t, storeId, 50, { reason: null }), '[-] gift card: a hand issue says why', 400, 'GIFT_CARD_REASON_REQUIRED');
  expect(issueGiftCardByHand(t, storeId, 50, { reason: 'BIRTHDAY' }), '[-] gift card: a reason that is none of the four is refused', 400, 'GIFT_CARD_REASON_REQUIRED');
  expect(issueGiftCardByHand(t, storeId, 50, { key: false }), '[-] gift card: a hand issue needs an Idempotency-Key', 400, 'IDEMPOTENCY_KEY_REQUIRED');
  expect(issueGiftCardByHand(t, storeId, 50, { extra: { paidBy: 'CARD' } }), '[-] gift card: money taken for a card is a sale, never a hand issue', 409, 'GIFT_CARD_NEEDS_SALE');
  expect(issueGiftCardByHand(cashierToken, storeId, 50), '[-] gift card: a cashier cannot issue one by hand', 403, 'GIFT_CARD_NEEDS_SALE');
  expect(call('GET', `${giftCards}/${code}`, { token: t }), '[+] check a gift card', 200);
  expect(call('GET', `${giftCards}/${code}`, { token: rival.owner.token }), "[-] another tenant's card is unknown", 404);
  expect(call('POST', `${giftCards}/${code}/redeem`, { token: t, idem: true, body: { amount: 20, orderId: order.id } }), '[+] redeem £20', [200, 201]);
  expect(call('POST', `${giftCards}/${code}/redeem`, { token: t, idem: true, body: { amount: 31, orderId: order.id } }), '[-] redeem more than the balance', [409, 422]);
  expect(call('POST', `${giftCards}/${code}/redeem`, { token: t, body: { amount: 1, orderId: order.id } }), '[-] redeem: an Idempotency-Key is required', 400);
  const reload = (body, opts = {}) => call('POST', `${giftCards}/${code}/reload`, { token: t, idem: true, body, ...opts });
  expect(reload({ amount: 10, reason: 'COMPENSATION' }), '[+] the owner reloads £10 by hand, for a reason', 200);
  expect(reload({ amount: 10 }), '[-] reload: a hand reload says why', 400, 'GIFT_CARD_REASON_REQUIRED');
  expect(reload({ amount: 10, reason: 'GOODWILL' }, { idem: false }), '[-] reload: a hand reload needs an Idempotency-Key', 400, 'IDEMPOTENCY_KEY_REQUIRED');
  expect(reload({ amount: 10, reason: 'GOODWILL', paidBy: 'CASH' }), '[-] reload: money taken for a top-up is a sale', 409, 'GIFT_CARD_NEEDS_SALE');
  expect(reload({ amount: 10, reason: 'GOODWILL' }, { token: cashierToken }), '[-] reload: a cashier cannot reload one by hand', 403, 'GIFT_CARD_NEEDS_SALE');
  const balance = data(call('GET', `${giftCards}/${code}`, { token: t }));
  truthy('[+] balance is 50 - 20 + 10, and none of the refusals moved it', Number(balance.currentBalance) === 40 && Number(balance.initialBalance) === 50, balance);

  // Sold at the till, by the cashier: the card is a line of the sale and is loaded by the payment.
  const cardLine = (loads, extra = {}) => ({ storeId, channel: 'POS', fulfilmentType: 'INSTORE', items: [], giftCardLoads: loads, ...extra });
  const sold = sellGiftCard(tenant, storeId, 25, { code, token: cashierToken });
  truthy('[+] a cashier sells a £25 top-up as a line of a till sale: the card is the whole total', Number(sold.order.total) === 25 && sold.order.status === 'PENDING', sold.order);
  truthy('[+] ...nothing is loaded while the sale is unpaid', sold.unpaid === 40, sold);
  truthy('[+] ...and the card is loaded when the sale is paid', sold.landed >= 0 && sold.after === 65, sold);
  const mixed = sellGiftCard(tenant, storeId, 10, { code, token: cashierToken, items: [{ variantId, qty: 1 }] });
  truthy('[+] goods and a card on one sale: the card is added to what the goods cost, with no VAT of its own', Math.abs(Number(mixed.order.total) - (Number(mixed.order.subtotal) + Number(mixed.order.taxAmount) + 10)) < 0.005 && Number(mixed.order.subtotal) > 0, mixed.order);
  truthy('[+] ...and the card is loaded with its own line, not the goods', mixed.landed >= 0 && mixed.after === 75, mixed);
  const fresh = sellGiftCard(tenant, storeId, 30, { token: cashierToken });
  truthy('[+] a new card sold with nothing else is a sale of its own, handed over when paid', Number(fresh.order.total) === 30 && poll(30, () => statusOf(fresh.order.id) === 'FULFILLED') >= 0, { total: fresh.order.total, status: statusOf(fresh.order.id) });
  const freshLoad = (fresh.loads || [])[0] || {};
  truthy('[+] ...and the sale itself says which card it made: a new one, loaded, with its code', fresh.landed >= 0 && freshLoad.status === 'LOADED' && freshLoad.kind === 'NEW' && !!freshLoad.code && Number(freshLoad.amount) === 30, fresh.loads);
  truthy('[+] ...and that card holds the £30', !!freshLoad.code && Number((data(call('GET', `${giftCards}/${freshLoad.code}`, { token: t })) || {}).currentBalance) === 30, freshLoad);
  expect(call('GET', `/api/order-svc/orders/${fresh.order.id}/gift-card-loads`, { token: rival.owner.token }), "[-] a rival cannot read the card our sale made", 404, 'ORDER_NOT_FOUND');
  expect(place(cardLine([{ amount: 10 }], { fulfilmentType: 'PICKUP' })), '[-] a gift card is sold across the counter, never for collection', 400, 'ORDER_GIFT_CARD_INSTORE_ONLY');
  expect(place(cardLine([{ amount: 0 }])), '[-] a gift-card line: amount above zero', 400);
  expect(place(cardLine([{ amount: 10, code: 'NO-SUCH-CARD' }])), '[-] a gift-card line naming a card nobody holds is refused', 404, 'GIFT_CARD_NOT_FOUND');
  expect(place({ ...cardLine([{ amount: 10, code }]), storeId: rival.stores[0].id }, { token: rival.owner.token }), "[-] a rival's sale cannot top up our card", 404, 'GIFT_CARD_NOT_FOUND');
  sleep(3);
  const loaded = data(call('GET', `${giftCards}/${code}`, { token: t }));
  truthy('[+] each sale loaded the card once, and the refused lines loaded nothing', Number(loaded.currentBalance) === 75, loaded);
  const tx = call('GET', `${giftCards}/${code}/transactions`, { token: t });
  expect(tx, '[+] gift card transactions', 200);
  const txs = Array.isArray(data(tx)) ? data(tx) : [];
  truthy('[+] ...the issue, the redemption, the hand reload and a reload for each sale', txs.filter((x) => x.txType === 'ISSUE').length === 1 && txs.filter((x) => x.txType === 'REDEEM').length === 1 && txs.filter((x) => x.txType === 'RELOAD').length === 3, txs);
  truthy('[+] ...each sale named on its own load', [sold, mixed].every((s) => txs.filter((x) => x.txType === 'RELOAD' && x.orderId === s.order.id).length === 1), txs);
  expect(call('GET', `${giftCards}/NO-SUCH-CARD`, { token: t }), '[-] unknown gift card', 404);

  // A card sold on a sale that is then voided goes back off the card, on the void's own transaction;
  // a card that was spent refuses the void whole (the rest of the money is a return of the goods).
  const cardOf = (code) => data(call('GET', `${giftCards}/${encodeURIComponent(code)}`, { token: t })) || {};
  const gone = sellGiftCard(tenant, storeId, 20, { token: cashierToken });
  const goneCard = (gone.loads || [])[0] || {};
  poll(30, () => statusOf(gone.order.id) === 'FULFILLED');
  expect(call('POST', `/api/order-svc/orders/${gone.order.id}/void`, { token: t, idem: true, body: { reason: 'Rang the card up in error' } }), '[+] void a sale that sold a new gift card', 200);
  truthy('[+] ...the card is taken back off: balance 0 and CANCELLED', !!goneCard.code && Number(cardOf(goneCard.code).currentBalance) === 0 && cardOf(goneCard.code).status === 'CANCELLED', cardOf(goneCard.code));
  truthy('[+] ...and the sale is VOIDED', statusOf(gone.order.id) === 'VOIDED', statusOf(gone.order.id));
  const kept = sellGiftCard(tenant, storeId, 30, { token: cashierToken });
  const keptCard = (kept.loads || [])[0] || {};
  poll(30, () => statusOf(kept.order.id) === 'FULFILLED');
  expect(call('POST', `${giftCards}/${encodeURIComponent(keptCard.code)}/redeem`, { token: t, idem: true, body: { amount: 10, orderId: order.id } }), '[+] part of the card sold on a sale is spent', [200, 201]);
  expect(call('POST', `/api/order-svc/orders/${kept.order.id}/void`, { token: t, idem: true, body: { reason: 'Changed their mind' } }), '[-] void a sale whose card was spent: refused whole', 409, 'ORDER_GIFT_CARD_SPENT');
  truthy('[+] ...the sale is still FULFILLED and the card still holds £20', statusOf(kept.order.id) === 'FULFILLED' && Number(cardOf(keptCard.code).currentBalance) === 20 && cardOf(keptCard.code).status === 'ACTIVE', cardOf(keptCard.code));

  // ── layaways ────────────────────────────────────────────────────────────────
  const lay = { storeId, items: [{ variantId, qty: 2, unitPrice: 12.5 }], initialDeposit: 5, paymentMethod: 'CASH', dueDate: '2027-01-31T00:00:00Z' };
  expect(call('POST', '/api/order-svc/layaways', { token: t, body: { ...lay, initialDeposit: 0 } }), '[-] layaway: deposit above zero', 400);
  expect(call('POST', '/api/order-svc/layaways', { token: t, body: { ...lay, dueDate: '31/01/2027' } }), '[-] layaway: due date is an ISO instant', 400, 'INVALID_DATE');
  const layaway = call('POST', '/api/order-svc/layaways', { token: t, body: lay });
  expect(layaway, '[+] start a layaway with £5 down', 201);
  const layId = data(layaway).id;
  expect(call('POST', `/api/order-svc/layaways/${layId}/complete`, { token: t }), '[-] complete before it is paid for', [400, 409, 422]);
  expect(call('POST', `/api/order-svc/layaways/${layId}/deposits`, { token: t, body: { amount: 20, paymentMethod: 'CASH' } }), '[+] pay the rest', [200, 201]);
  expect(call('POST', `/api/order-svc/layaways/${layId}/complete`, { token: t }), '[+] complete the layaway', 200);
  // Like order transitions, a layaway not in the state the action needs answers "not found or not active".
  expect(call('POST', `/api/order-svc/layaways/${layId}/cancel`, { token: t, body: { reason: 'Changed mind' } }), '[-] cancel a completed layaway', 404, 'LAYAWAY_NOT_FOUND');
  expect(call('GET', `/api/order-svc/layaways/${layId}`, { token: t }), '[+] get layaway', 200);
  expect(call('GET', `/api/order-svc/layaways/${layId}`, { token: rival.owner.token }), "[-] a rival cannot see it", 404);

  // ── the online shopper ──────────────────────────────────────────────────────
  const online = call('POST', '/api/order-svc/orders', {
    token: shopper.token,
    storefront: tenant.tenantId,
    idem: true,
    body: { storeId, channel: 'ONLINE', fulfilmentType: 'PICKUP', items: [{ variantId, qty: 1 }], contactPhone: '+447700900123' },
  });
  expect(online, '[+] a shopper places a pickup order', 201);
  const onlineId = data(online).id;
  const mine = call('GET', '/api/order-svc/orders/mine', { token: shopper.token, storefront: tenant.tenantId });
  expect(mine, '[+] my orders', 200);
  truthy('[+] ...include it', (data(mine) || []).some((o) => o.id === onlineId), data(mine));
  truthy("[-] another shopper's orders do not include it", !(data(call('GET', '/api/order-svc/orders/mine', { token: stranger.token, storefront: tenant.tenantId })) || []).some((o) => o.id === onlineId));

  // ── store reports ───────────────────────────────────────────────────────────
  expect(call('GET', `/api/order-svc/admin/reports/sales-by-hour?storeId=${storeId}`, { token: t }), '[+] sales by hour', 200);
  expect(call('GET', `/api/order-svc/admin/reports/sales-by-staff?storeId=${storeId}`, { token: t }), '[+] sales by staff', 200);
  expect(call('GET', `/api/order-svc/admin/reports/exceptions?storeId=${storeId}`, { token: t }), '[+] exceptions report (voids, returns)', 200);
  expect(call('GET', `/api/order-svc/admin/pos/stock-positions?storeId=${storeId}`, { token: t }), '[+] POS stock positions', 200);
  expect(call('GET', `/api/order-svc/admin/reports/sales-by-hour?storeId=${storeId}`, { token: shopper.token }), '[-] a customer cannot read store reports', 403);
}
