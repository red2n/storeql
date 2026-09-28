// Stock that moves keeps what it is (SJ-D71, row 05.1), through the gateway: a transfer between
// stores, a move within one, and a customer return each used to arrive as one anonymous batch —
// TO-, MO-, RET- and a short reference — with no lot, no use-by date and no cost, so moved stock
// dropped out of the expiring view, a recall could only hold it as "lot unknown", and the margin
// report did not know what it cost. Now what arrives is one batch per source batch drawn, carrying
// each one's lot, date and cost, linked to it in the genealogy; the system number is kept only for
// stock whose source had no lot. And the refusals and abuse around it: a transfer short of stock takes
// and delivers nothing, nobody without a token moves stock, a rival business sees none of it.
//
//   k6/run.sh stock-provenance-flow
import { Counter } from 'k6/metrics';
import {
  ALL_CHECKS_PASS,
  call,
  data,
  expect,
  must,
  onboardTenant,
  poll,
  priceVariants,
  sellableVariant,
  staffUser,
  truthy,
  uniq,
} from './lib/storeql.js';

const completed = new Counter('flow_completed');
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
  setupTimeout: '4m',
};

const INV = '/api/inventory-svc/admin/inventory';

export function setup() {
  const tenant = onboardTenant(`provenance-${uniq()}`, { stores: 2 });
  const { variantId } = sellableVariant(tenant, 'Chilled soup');
  priceVariants(tenant, [variantId], '12.00');
  const rival = onboardTenant(`provenance-rival-${uniq()}`);
  const cashier = staffUser(tenant, 'CASHIER', [tenant.stores[0].id]);
  return { tenant, variantId, rival, cashier };
}

export default function ({ tenant, variantId, rival, cashier }) {
  const owner = tenant.owner.token;
  const [a, b] = tenant.stores;
  const soon = new Date(Date.now() + 10 * 86400e3).toISOString().slice(0, 10);
  const later = new Date(Date.now() + 60 * 86400e3).toISOString().slice(0, 10);

  const receive = (storeId, qty, lot, expiry, cost) => call('POST', `${INV}/receive`, {
    token: owner,
    idem: true,
    body: { storeId, variantId, qty, ...(lot ? { batchNo: lot } : {}), ...(expiry ? { expiryDate: expiry } : {}), ...(cost ? { costPrice: cost } : {}) },
  });
  const batches = (storeId, token = owner) => data(call('GET', `${INV}/batches?store=${storeId}&variant=${variantId}`, { token })) || [];
  const byLot = (storeId) => Object.fromEntries(batches(storeId).map((x) => [x.batchNo, x]));
  const expiring = (storeId, withinDays = 30) => (data(call('GET', `${INV}/batches/expiring?store=${storeId}&withinDays=${withinDays}`, { token: owner })) || []).map((x) => x.batchNo);
  const descendants = (batchId) => (data(call('GET', `${INV}/lot-genealogy/batch/${batchId}/descendants`, { token: owner })) || {}).descendants || [];
  const transfer = (type, qty, token = owner) => call('POST', `${INV}/transfers`, { token, body: { fromStoreId: a.id, toStoreId: b.id, transferType: type, lines: [{ variantId, requestedQty: qty }] } });

  // ── a direct transfer carries each lot, its date and its cost ─────────────────────────────────
  const l1 = must(receive(a.id, 10, 'L1', soon, '2.50'), [200, 201], 'lot L1 received').id;
  const l2 = must(receive(a.id, 5, 'L2', later, '3.00'), [200, 201], 'lot L2 received').id;
  const direct = must(transfer('DIRECT', 12), 201, 'a direct transfer');
  expect(call('POST', `${INV}/transfers/${direct.id}/ship`, { token: owner }), '[+] twelve of fifteen ship to the other store', 200);
  const arrived = byLot(b.id);
  truthy('[+] what arrives is two batches, one per lot drawn — soonest date first', Object.keys(arrived).sort().join(',') === 'L1,L2', Object.keys(arrived));
  truthy('[+] L1 arrives whole, with its use-by date and its cost', arrived.L1 && Number(arrived.L1.remainingQty) === 10 && arrived.L1.expiryDate === soon && Number(arrived.L1.costPrice) === 2.5, arrived.L1);
  truthy('[+] L2 arrives as the two units that made up the twelve, with its own date and cost', arrived.L2 && Number(arrived.L2.remainingQty) === 2 && arrived.L2.expiryDate === later && Number(arrived.L2.costPrice) === 3, arrived.L2);
  const left = byLot(a.id);
  truthy('[+] the sending store keeps what did not leave', Number(left.L1.remainingQty) === 0 && Number(left.L2.remainingQty) === 3, left);
  truthy('[+] the expiring view at the receiving store sees the moved stock — the harm SJ-D71 named', expiring(b.id).join(',') === 'L1', expiring(b.id));
  const kids = descendants(l1);
  truthy('[+] the arrival is the child of the batch it came from, by the quantity that moved', kids.length === 1 && kids[0].childBatchId === arrived.L1.id && Number(kids[0].qty) === 10 && kids[0].relationType === 'SPLIT', kids);

  // ── in transit: what arrives is what left, read back from the ledger ────────────────────────────
  const l3src = must(receive(a.id, 4, 'L3', later, '2.75'), [200, 201], 'lot L3 received').id;
  const transit = must(transfer('INTRANSIT', 4), 201, 'an in-transit transfer');
  expect(call('POST', `${INV}/transfers/${transit.id}/ship`, { token: owner }), '[+] four ship: the last three of L2, then one of L3', 200);
  truthy('[+] nothing arrives while it is on the road', !batches(b.id).some((x) => x.batchNo === 'L3'), batches(b.id).map((x) => x.batchNo));
  expect(call('POST', `${INV}/transfers/${transit.id}/receive`, { token: owner }), '[+] it arrives', 200);
  const arrivedNow = batches(b.id);
  const l2s = arrivedNow.filter((x) => x.batchNo === 'L2').reduce((s, x) => s + Number(x.remainingQty), 0);
  const l3 = arrivedNow.find((x) => x.batchNo === 'L3');
  truthy('[+] as its lots: the rest of L2 and one of L3, dates and costs intact', l2s === 5 && l3 && Number(l3.remainingQty) === 1 && l3.expiryDate === later && Number(l3.costPrice) === 2.75, arrivedNow);

  // ── a move within a store keeps the lot too ──────────────────────────────────────────────────────
  const move = must(call('POST', `${INV}/move-orders`, { token: owner, body: { fromStoreId: b.id, toStoreId: b.id, fromZone: 'BACK', toZone: 'FLOOR', lines: [{ variantId, requestedQty: 4 }] } }), 201, 'a move order');
  expect(call('POST', `${INV}/move-orders/${move.id}/pick`, { token: owner }), '[+] four are picked from the back to the floor', 200);
  const moved = batches(b.id).find((x) => x.batchNo === 'L1' && x.id !== arrived.L1.id);
  truthy('[+] and put down under L1 — the soonest date on the shelf — with its date and cost', !!moved && Number(moved.remainingQty) === 4 && moved.expiryDate === soon && Number(moved.costPrice) === 2.5, batches(b.id));

  // ── stock with no lot moves under the system number, and still keeps its date ───────────────────
  must(receive(a.id, 3, null, soon, null), [200, 201], 'stock with no lot');
  const plain = must(transfer('DIRECT', 3), 201, 'a transfer of it');
  expect(call('POST', `${INV}/transfers/${plain.id}/ship`, { token: owner }), '[+] it ships', 200);
  const anon = batches(b.id).find((x) => x.batchNo && x.batchNo.startsWith('TO-'));
  truthy('[+] it arrives under the transfer\'s own number, its date carried, no cost invented', !!anon && anon.expiryDate === soon && (anon.costPrice === null || anon.costPrice === undefined), anon);

  // ── a return comes back under the lot it was sold from ──────────────────────────────────────────
  const sale = must(call('POST', '/api/order-svc/orders', { token: owner, idem: true, body: { storeId: a.id, channel: 'POS', fulfilmentType: 'INSTORE', items: [{ variantId, qty: 2 }] } }), 201, 'a till sale of two');
  must(call('POST', '/api/payment-svc/payments', { token: owner, idem: true, body: { orderId: sale.id, amount: sale.total, method: 'CASH', storeId: a.id } }), [200, 201], 'paid in full');
  let sold = null;
  poll(60, () => { sold = byLot(a.id); return sold.L3 && Number(sold.L3.remainingQty) === 1; });
  truthy('[+] the sale drew two of the three L3 left — every other lot had gone', sold && Number(sold.L3.remainingQty) === 1 && Number(sold.L2.remainingQty) === 0, sold);
  expect(call('POST', `/api/order-svc/orders/${sale.id}/returns`, { token: owner, body: { reason: 'Changed their mind', items: [{ variantId, qty: 1 }] } }), '[+] one comes back', [200, 201]);
  let back = null;
  poll(60, () => { back = batches(a.id).find((x) => x.batchNo === 'L3' && x.id !== l3src); return !!back; });
  truthy('[+] under L3, the lot it was sold from, with its date and cost — not an anonymous RET- batch', !!back && Number(back.remainingQty) === 1 && back.expiryDate === later && Number(back.costPrice) === 2.75, batches(a.id));
  truthy('[+] and on the expiring view again, under its own date, where a returned chilled item belongs', expiring(a.id, 90).filter((x) => x === 'L3').length >= 1, expiring(a.id, 90));

  // ── a recall on the lot holds the moved stock as in scope, not as unknown ───────────────────────
  const recall = call('POST', '/api/inventory-svc/admin/recalls', { token: owner, body: { reference: `FSA-${uniq()}`, kind: 'RECALL', hazard: 'ALLERGEN', reason: 'Undeclared peanut', source: 'FSA', customerNotice: 'Do not eat. Return it for a refund.', remedies: ['REFUND', 'REPLACEMENT'], contactPhone: '0800 100 200', items: [{ variantId, batchNo: 'L1' }] } });
  expect(recall, '[+] a recall on lot L1 opens', 201);
  const held = Object.fromEntries(((data(recall) || {}).batches || []).map((x) => [x.batchId, x.match]));
  truthy('[+] and holds the transferred L1 at the other store as IN_SCOPE — it knows its lot now', held[arrived.L1.id] === 'IN_SCOPE', held);
  truthy('[+] and the four moved to the shop floor under L1 with it', !!moved && held[moved.id] === 'IN_SCOPE', held);
  truthy('[+] while the L2 that travelled with it is left alone', !(arrived.L2.id in held), held);


  // ── refusals and abuse ───────────────────────────────────────────────────────────────────────────
  const short = must(transfer('DIRECT', 500), 201, 'a transfer of more than there is');
  expect(call('POST', `${INV}/transfers/${short.id}/ship`, { token: owner }), '[-] a transfer short of stock ships nothing', 422, 'INSUFFICIENT_STOCK');
  truthy('[-] ...and nothing arrives from it', !batches(b.id).some((x) => x.batchNo === `TO-${short.id.slice(28)}`), batches(b.id).map((x) => x.batchNo));
  // A cashier CAN create a transfer today: the admin filter opens the whole /admin/inventory subtree to any
  // staff role and the transfer resource adds no stricter gate. Recorded as SJ-D73, not pinned here.
  expect(call('POST', `${INV}/transfers`, { body: { fromStoreId: a.id, toStoreId: b.id, transferType: 'DIRECT', lines: [{ variantId, requestedQty: 1 }] } }), '[-] nobody without a token moves stock', 401);
  expect(transfer('DIRECT', 1, cashier.token), '[-] a cashier does not move stock between stores (SJ-D73): stock.transfer is the storekeeper\'s', 403, 'PERMISSION_DENIED');
  expect(call('POST', `${INV}/move-orders/${move.id}/pick`, { token: owner }), '[-] a move already picked is not picked twice', [409, 422]);
  expect(call('POST', `${INV}/transfers/${direct.id}/receive`, { token: owner }), '[-] a direct transfer is not received twice', [409, 422]);
  truthy('[abuse] a rival business sees none of these batches', batches(b.id, rival.owner.token).length === 0, 'rival');
  expect(call('GET', `${INV}/lot-genealogy/batch/${l1}/descendants`, { token: rival.owner.token }), '[abuse] nor the lineage', [200, 404]);
  truthy('[abuse] ...which is empty for them, while the owner sees the arrival and, through it, the four moved to the floor', descendants(l1).some((k) => k.childBatchId === arrived.L1.id) && descendants(l1).some((k) => moved && k.childBatchId === moved.id) && ((data(call('GET', `${INV}/lot-genealogy/batch/${l1}/descendants`, { token: rival.owner.token })) || {}).descendants || []).length === 0, descendants(l1));

  completed.add(1);
}
