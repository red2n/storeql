// Supplier lead-time tracking and scorecards through the gateway: a supplier with a quoted lead
// time, an order submitted and received in full (measured against the quote: on the day, early),
// a second order received short and closed short, two cases sent back; the deliveries as measured,
// the scorecard weighing on time, fill, quality and invoices into a score and a grade, the ranking
// with an idle supplier last and unscored; a cashier refused, a period that ends before it starts
// refused, and the quote corrected on the supplier.
//
//   k6/run.sh supplier-scorecard-flow
import {
  ALL_CHECKS_PASS,
  call,
  data,
  expect,
  must,
  onboardTenant,
  poll,
  sellableVariant,
  staffUser,
  truthy,
  uniq,
} from './lib/storeql.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS, setupTimeout: '4m' };

const P = '/api/purchase-svc';

export function setup() {
  const tenant = onboardTenant('score');
  const { variantId } = sellableVariant(tenant, 'Rump steak per kg');
  const cashier = staffUser(tenant, 'CASHIER', [tenant.stores[0].id]);
  return { tenant, variantId, cashier };
}

export default function ({ tenant, variantId, cashier }) {
  const owner = tenant.owner.token;
  const store = tenant.stores[0];
  const num = (v) => Number(v || 0);
  const today = new Date().toISOString().slice(0, 10);
  const plusDays = (n) => new Date(Date.now() + n * 86400000).toISOString().slice(0, 10);
  const order = (supplierId, qty, extra) => {
    const po = must(call('POST', `${P}/purchase-orders`, { token: owner, body: Object.assign({ supplierId, storeId: store.id }, extra || {}) }), 201, 'an order');
    must(call('POST', `${P}/purchase-orders/${po.id}/lines`, { token: owner, body: { variantId, qty, unitPrice: '20.00' } }), 201, 'a line');
    must(call('POST', `${P}/purchase-orders/${po.id}/submit`, { token: owner, body: {} }), 200, 'submitted');
    return po;
  };

  // ── 1. the supplier and the promise ────────────────────────────────────────
  const butcher = must(call('POST', `${P}/suppliers`, { token: owner, body: { name: `Highland Meats ${uniq()}`, vatRegistered: true, currency: 'GBP', leadTimeDays: 3 } }), 201, 'a supplier with a quote');
  truthy('[+] the supplier keeps its quoted lead time', butcher.leadTimeDays === 3, butcher);
  const idle = must(call('POST', `${P}/suppliers`, { token: owner, body: { name: `Quiet Farm ${uniq()}`, vatRegistered: true, currency: 'GBP' } }), 201, 'a supplier nobody orders from');
  truthy('[+] a supplier without a quote has none', idle.leadTimeDays === undefined || idle.leadTimeDays === null, idle);

  // ── 2. a delivery in full, measured against the quote ──────────────────────
  const po1 = order(butcher.id, 10);
  must(call('POST', `${P}/goods-receipts`, { token: owner, idem: true, body: { poId: po1.id, storeId: store.id, lines: [{ variantId, qtyReceived: 10 }] } }), 201, 'ten received');
  let deliveries = data(call('GET', `${P}/suppliers/${butcher.id}/deliveries`, { token: owner })) || [];
  truthy('[+] the delivery is measured: no days from order, three days early against the quote, complete', deliveries.length === 1 && deliveries[0].leadDays === 0 && deliveries[0].lateDays === -3 && deliveries[0].complete === true && deliveries[0].promisedDate === plusDays(3), deliveries);
  let card = data(call('GET', `${P}/suppliers/${butcher.id}/scorecard`, { token: owner })) || {};
  truthy('[+] one delivery on time and one order filled in full read as an A', card.deliveries.count === 1 && num(card.deliveries.onTimePct) === 100 && num(card.fill.fillRatePct) === 100 && card.grade === 'A' && num(card.score) === 100, card);

  // ── 3. a short delivery closed short, and two cases sent back ──────────────
  const po2 = order(butcher.id, 10, { expectedDelivery: plusDays(1) });
  must(call('POST', `${P}/goods-receipts`, { token: owner, idem: true, body: { poId: po2.id, storeId: store.id, lines: [{ variantId, qtyReceived: 6 }] } }), 201, 'six of ten received');
  must(call('POST', `${P}/purchase-orders/${po2.id}/close`, { token: owner, body: { reason: 'the supplier cannot complete' } }), 200, 'closed short');
  // A return goes back from the shelf: wait for inventory-svc to have booked both receipts.
  const onHand = () => num(((data(call('GET', `/api/inventory-svc/admin/inventory/levels?store=${store.id}`, { token: owner })) || []).find((l) => l.variantId === variantId) || {}).onHand);
  truthy('[+] the sixteen received are on the shelf', poll(90, () => onHand() >= 16) >= 0, onHand());
  must(call('POST', `${P}/vendor-returns`, { token: owner, idem: true, body: { poId: po1.id, reason: 'DAMAGED', lines: [{ variantId, qty: 2 }] } }), 201, 'two sent back');
  card = data(call('GET', `${P}/suppliers/${butcher.id}/scorecard?from=2026-01-01&to=${today}`, { token: owner })) || {};
  truthy('[+] two deliveries, both on time; sixteen of twenty filled with one order closed short', card.deliveries.count === 2 && card.deliveries.onTime === 2 && card.fill.orders === 2 && num(card.fill.orderedQty) === 20 && num(card.fill.receivedQty) === 16 && num(card.fill.fillRatePct) === 80 && card.fill.shortClosed === 1, card);
  truthy('[+] two of sixteen back is a return rate of 12.5 %', card.quality.returns === 1 && num(card.quality.returnedQty) === 2 && num(card.quality.returnRatePct) === 12.5, card.quality);
  // On time 100 at 40, fill 80 at 30, quality 87.5 at 20, no invoices: (4000 + 2400 + 1750) / 90.
  truthy('[+] the score weighs what is known: 90.6, an A', num(card.score) === 90.6 && card.grade === 'A' && card.leadTimeDays === 3, { score: card.score, grade: card.grade });

  // ── 4. the ranking, and who may read it ────────────────────────────────────
  const ranked = data(call('GET', `${P}/suppliers/scorecards`, { token: owner })) || [];
  const first = ranked.find((c) => c.supplierId === butcher.id);
  const last = ranked.find((c) => c.supplierId === idle.id);
  truthy('[+] the butcher is ranked above the idle supplier, who has no score', first && last && ranked.indexOf(first) < ranked.indexOf(last) && (last.score === undefined || last.score === null) && last.deliveries.count === 0, ranked.map((c) => [c.supplierName, c.score]));
  expect(call('GET', `${P}/suppliers/scorecards`, { token: cashier.token }), '[-] a cashier reads no scorecard', 403);
  expect(call('GET', `${P}/suppliers/${butcher.id}/deliveries`, { token: cashier.token }), '[-] nor the deliveries', 403);
  expect(call('GET', `${P}/suppliers/scorecards?from=2026-02-01&to=2026-01-01`, { token: owner }), '[-] a period that ends before it starts', 400, 'PURCHASE_PERIOD_INVALID');

  // ── 5. the quote corrected ─────────────────────────────────────────────────
  const corrected = must(call('PUT', `${P}/suppliers/${butcher.id}`, { token: owner, body: { name: butcher.name, vatRegistered: true, leadTimeDays: 2 } }), 200, 'the quote corrected');
  truthy('[+] the corrected quote reads back on the supplier and the card', corrected.leadTimeDays === 2 && (data(call('GET', `${P}/suppliers/${butcher.id}/scorecard`, { token: owner })) || {}).leadTimeDays === 2, corrected);
  deliveries = data(call('GET', `${P}/suppliers/${butcher.id}/deliveries`, { token: owner })) || [];
  truthy('[+] a delivery already measured keeps the promise it was measured against', deliveries.length === 2 && deliveries.every((d) => d.promisedDate === plusDays(3) || d.promisedDate === plusDays(1)), deliveries);
}
