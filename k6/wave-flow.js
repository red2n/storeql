// Wave picking and directed putaway through the gateway: two zones at a store; older apples in the
// cold room, newer apples and the pears in aisle A; two shoppers' delivery orders paid and confirmed
// and waiting to be picked (a cashier may not build a wave); one wave — three lines walked aisle A
// first then the cold room, the old apples directed first (FEFO) and shared between the orders —
// picked one short and completed: the stock leaves from exactly those batches, order-svc fulfils
// the first order in full and the second in part, the remainder waits for the next wave, and the
// fulfilment that follows deducts nothing twice. Then directed putaway: a rule sends pears to aisle
// A on arrival with no zone; apples with no rule wait on the list and are placed in the cold room.
//
//   k6/run.sh wave-flow
import {
  ALL_CHECKS_PASS,
  call,
  data,
  expect,
  must,
  newKey,
  onboardTenant,
  poll,
  priceVariants,
  register,
  sellableVariant,
  staffUser,
  truthy,
} from './lib/storeql.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS, setupTimeout: '4m' };

const I = '/api/inventory-svc';
const O = '/api/order-svc';

export function setup() {
  const tenant = onboardTenant('wave');
  const store = tenant.stores[0];
  const t = tenant.owner.token;
  const aisle = must(call('POST', `/api/tenant-svc/admin/stores/${store.id}/zones`, { token: t, body: { name: 'Aisle A', code: 'A', type: 'AISLE' } }), 201, 'aisle A');
  const cold = must(call('POST', `/api/tenant-svc/admin/stores/${store.id}/zones`, { token: t, body: { name: 'Cold room', code: 'CR', type: 'COLD_ROOM' } }), 201, 'the cold room');
  const apples = sellableVariant(tenant, 'Apples per kg').variantId;
  const pears = sellableVariant(tenant, 'Pears per kg').variantId;
  priceVariants(tenant, [apples, pears], '2.50');
  const storekeeper = staffUser(tenant, 'STOREKEEPER', [store.id]);
  const cashier = staffUser(tenant, 'CASHIER', [store.id]);
  return { tenant, store, aisle, cold, apples, pears, storekeeper, cashier, shopper1: register('wave-shopper-1'), shopper2: register('wave-shopper-2') };
}

export default function ({ tenant, store, aisle, cold, apples, pears, storekeeper, cashier, shopper1, shopper2 }) {
  const owner = tenant.owner.token;
  const num = (v) => Number(v || 0);
  const plusDays = (n) => new Date(Date.now() + n * 86400000).toISOString().slice(0, 10);
  const receive = (variantId, qty, zoneId, lot, expiry) => must(call('POST', `${I}/admin/inventory/receive`, { token: owner, idem: true, body: Object.assign({ storeId: store.id, variantId, qty, batchNo: lot, costPrice: '1.00' }, zoneId ? { zoneId } : {}, expiry ? { expiryDate: expiry } : {}) }), 201, `receive ${lot}`);
  const level = (variantId) => (data(call('GET', `${I}/admin/inventory/levels?store=${store.id}`, { token: owner })) || []).find((l) => l.variantId === variantId) || {};
  const awaiting = () => data(call('GET', `${I}/admin/inventory/waves/awaiting?storeId=${store.id}`, { token: owner })) || [];
  const order = (shopper, items) => {
    const placed = must(call('POST', `${O}/orders`, { token: shopper.token, storefront: tenant.tenantId, idem: true, body: { storeId: store.id, channel: 'ONLINE', fulfilmentType: 'DELIVERY', deliveryLine1: '12 High Street', deliveryCity: 'Leeds', deliveryPostalCode: 'LS1 1AA', deliveryRecipientName: 'Chris Carter', deliveryRecipientPhone: '07700900123', contactPhone: '07700900123', items } }), 201, 'an order');
    must(call('POST', '/api/payment-svc/payments', { token: owner, idem: true, body: { orderId: placed.id, amount: placed.total, method: 'CARD', storeId: store.id } }), [200, 201], 'paid by card');
    return placed;
  };
  const status = (id) => (data(call('GET', `${O}/orders/${id}`, { token: owner })) || {}).status;

  // ── 1. the shelf: old apples in the cold room, new apples and pears in aisle A ─
  const oldApples = receive(apples, 5, cold.id, 'A-OLD', plusDays(5));
  receive(apples, 10, aisle.id, 'A-NEW', plusDays(30));
  receive(pears, 8, aisle.id, 'P-1', null);
  truthy('[+] fifteen apples and eight pears on the shelf', num(level(apples).onHand) === 15 && num(level(pears).onHand) === 8, { apples: level(apples), pears: level(pears) });

  // ── 2. two delivery orders, paid: they wait at the store to be picked ────────
  const order1 = order(shopper1, [{ variantId: apples, qty: 3 }, { variantId: pears, qty: 2 }]);
  // Payments confirm asynchronously: the first order is confirmed before the second is placed, so
  // "the first first" below is a fact about the orders and not about which payment landed first.
  poll(60, () => status(order1.id) === 'CONFIRMED');
  const order2 = order(shopper2, [{ variantId: apples, qty: 4 }]);
  const waiting = poll(90, () => awaiting().length === 2);
  truthy('[+] both confirmed orders wait to be picked, the first first', waiting >= 0 && awaiting()[0].orderId === order1.id && awaiting()[1].orderId === order2.id, awaiting());
  expect(call('POST', `${I}/admin/inventory/waves`, { token: cashier.token, idem: true, body: { storeId: store.id } }), '[-] a cashier builds no wave', 403);

  // ── 3. the wave: one walk, the old apples first, shared between the orders ───
  const key = newKey();
  const wave = must(call('POST', `${I}/admin/inventory/waves`, { token: storekeeper.token, headers: { 'Idempotency-Key': key }, body: { storeId: store.id } }), 201, 'a wave');
  truthy('[+] three lines for two orders, aisle A walked before the cold room', wave.status === 'OPEN' && wave.orderCount === 2 && wave.lines.length === 3 && wave.lines[0].zoneId === aisle.id && wave.lines[2].zoneId === cold.id, wave.lines);
  const oldLine = wave.lines.find((l) => l.batchId === oldApples.id) || {};
  truthy('[+] the old apples are directed first: five, three for the first order and two for the second', num(oldLine.directedQty) === 5 && oldLine.orders.length === 2 && num(oldLine.orders.find((o) => o.orderId === order1.id).qty) === 3 && num(oldLine.orders.find((o) => o.orderId === order2.id).qty) === 2, oldLine);
  const again = must(call('POST', `${I}/admin/inventory/waves`, { token: storekeeper.token, headers: { 'Idempotency-Key': key }, body: { storeId: store.id } }), 201, 'the same key');
  truthy('[+] the same key builds no second wave', again.id === wave.id, again);
  expect(call('POST', `${I}/admin/inventory/waves`, { token: storekeeper.token, idem: true, body: { storeId: store.id } }), '[-] nothing else waits', 409, 'INVENTORY_WAVE_NOTHING_TO_PICK');

  // ── 4. picked one short, completed: the stock leaves from those batches ───────
  const picks = wave.lines.map((l) => ({ lineId: l.id, pickedQty: l.id === oldLine.id ? 4 : l.directedQty }));
  expect(call('POST', `${I}/admin/inventory/waves/${wave.id}/picks`, { token: storekeeper.token, body: { lines: [{ lineId: oldLine.id, pickedQty: 9 }] } }), '[-] more than directed', 400, 'INVENTORY_WAVE_PICK_EXCEEDS_LINE');
  must(call('POST', `${I}/admin/inventory/waves/${wave.id}/picks`, { token: storekeeper.token, body: { lines: picks } }), 200, 'picks recorded');
  const done = must(call('POST', `${I}/admin/inventory/waves/${wave.id}/complete`, { token: storekeeper.token, body: {} }), 200, 'completed');
  truthy('[+] completed: six apples and two pears gone, the old batch down to one', done.status === 'COMPLETED' && num(level(apples).onHand) === 9 && num(level(pears).onHand) === 6, { apples: level(apples), pears: level(pears) });
  const fulfilled = poll(90, () => status(order1.id) === 'FULFILLED' && status(order2.id) === 'PARTIALLY_FULFILLED');
  truthy('[+] order-svc fulfilled the first order in full and the second in part, from the wave alone', fulfilled >= 0, { order1: status(order1.id), order2: status(order2.id) });
  const left = awaiting();
  truthy('[+] one apple of the second order still waits for the next wave', left.length === 1 && left[0].orderId === order2.id && num(left[0].lines[0].qtyOutstanding) === 1, left);
  // The fulfilments have landed in inventory-svc once the wave's lines show their revenue on the
  // gross-margin report (the revenue is recorded when the pick is acknowledged); only then does
  // "deducted nothing twice" mean anything.
  const from = new Date(Date.now() - 86400000).toISOString();
  const to = new Date(Date.now() + 86400000).toISOString();
  const margin = () => ((data(call('GET', `${I}/admin/inventory/reports/gross-margin?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}&storeId=${store.id}&groupBy=VARIANT`, { token: owner })) || {}).rows || []);
  const revenueLanded = poll(60, () => margin().some((r) => r.groupKey === pears && num(r.revenue) > 0) && margin().some((r) => r.groupKey === apples && num(r.revenue) > 0)) >= 0;
  truthy('[+] the fulfilments that followed deducted nothing twice', revenueLanded && num(level(apples).onHand) === 9 && num(level(pears).onHand) === 6, { revenueLanded, apples: level(apples), pears: level(pears), margin: margin() });
  expect(call('POST', `${I}/admin/inventory/waves/${wave.id}/complete`, { token: storekeeper.token, body: {} }), '[-] completed once', 409, 'INVENTORY_WAVE_NOT_OPEN');

  // ── 5. directed putaway: a rule places the pears, the apples wait to be placed ─
  const rule = must(call('PUT', `${I}/admin/inventory/putaway/rules`, { token: owner, body: { storeId: store.id, variantId: pears, zoneId: aisle.id } }), 200, 'pears go to aisle A');
  truthy('[+] the rule names the product and the zone', rule.variantId === pears && rule.zoneId === aisle.id, rule);
  expect(call('PUT', `${I}/admin/inventory/putaway/rules`, { token: storekeeper.token, body: { storeId: store.id, zoneId: cold.id } }), '[-] a storekeeper sets no rule', 403);
  const pearsIn = receive(pears, 4, null, 'P-2', null);
  const pearBatch = (data(call('GET', `${I}/admin/inventory/batches?store=${store.id}&variant=${pears}`, { token: owner })) || []).find((b) => b.id === pearsIn.id) || {};
  truthy('[+] pears arriving with no zone land in aisle A', pearBatch.zoneId === aisle.id, pearBatch);
  const applesIn = receive(apples, 6, null, 'A-3', null);
  const tasks = data(call('GET', `${I}/admin/inventory/putaway/tasks?storeId=${store.id}`, { token: owner })) || [];
  const task = tasks.find((t) => t.batchId === applesIn.id) || {};
  truthy('[+] apples with no rule wait on the putaway list', tasks.length === 1 && task.status === 'OPEN' && task.variantId === apples, tasks);
  expect(call('POST', `${I}/admin/inventory/putaway/tasks/${task.id}/place`, { token: storekeeper.token, body: {} }), '[-] placing needs a zone', 400, 'INVENTORY_PUTAWAY_ZONE_REQUIRED');
  const placed = must(call('POST', `${I}/admin/inventory/putaway/tasks/${task.id}/place`, { token: storekeeper.token, body: { zoneId: cold.id } }), 200, 'placed in the cold room');
  const appleBatch = (data(call('GET', `${I}/admin/inventory/batches?store=${store.id}&variant=${apples}`, { token: owner })) || []).find((b) => b.id === applesIn.id) || {};
  truthy('[+] placed: the batch sits in the cold room and the list is empty', placed.status === 'PLACED' && appleBatch.zoneId === cold.id && (data(call('GET', `${I}/admin/inventory/putaway/tasks?storeId=${store.id}`, { token: owner })) || []).length === 0, { placed, appleBatch });
  expect(call('POST', `${I}/admin/inventory/putaway/tasks/${task.id}/place`, { token: storekeeper.token, body: { zoneId: cold.id } }), '[-] placed once', 409, 'INVENTORY_PUTAWAY_TASK_PLACED');
}
