// The automatic order proposal (06.x) through the gateway: a store sells most of what it received,
// the reorder point is computed from the month's demand (sixty sold this month is two a day, a
// reorder point of fourteen over a week's lead time; ten left on the shelf and nothing on order), and a proposal run raises a DRAFT order on
// the supplier the business last bought from — with the arithmetic on the line — then refuses to
// raise a second while the first is a draft; a keeper of another store, a cover outside a day to a
// year, a store that is not an id, a rival and no token are refused.
//
//   k6/run.sh order-proposal-flow
import {
  ALL_CHECKS_PASS,
  addStore,
  call,
  data,
  expect,
  must,
  onboardTenant,
  poll,
  priceVariants,
  receive,
  sellableVariant,
  staffUser,
  truthy,
  uniq,
} from './lib/storeql.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS, setupTimeout: '4m' };

const INV = '/api/inventory-svc/admin/inventory';
const PURCHASE = '/api/purchase-svc';

export function setup() {
  const tenant = onboardTenant(`proposal-${uniq()}`, { stores: 1 });
  const other = addStore(tenant, 'Other');
  const { variantId } = sellableVariant(tenant, 'Proposal beans');
  priceVariants(tenant, [variantId], '4.00');
  const keeper = staffUser(tenant, 'STOREKEEPER', [other.id]);
  const rival = onboardTenant(`proposal-rival-${uniq()}`);
  return { tenant, variantId, keeper, rival };
}

export default function ({ tenant, variantId, keeper, rival }) {
  const owner = tenant.owner.token;
  const store = tenant.stores[0];
  const orders = `${PURCHASE}/purchase-orders`;

  // ── where we buy it, and what is on order ───────────────────────────────────
  const supplier = must(call('POST', `${PURCHASE}/suppliers`, { token: owner, body: { name: `Bean Wholesale ${uniq()}`, vatRegistered: true, currency: tenant.currency || 'GBP' } }), 201, 'supplier').id;
  const history = must(call('POST', orders, { token: owner, body: { supplierId: supplier, storeId: store.id } }), 201, 'an order').id;
  expect(call('POST', `${orders}/${history}/lines`, { token: owner, body: { variantId, qty: 10, unitPrice: 2.5 } }), '[+] ten beans ordered at 2.50 — the price and the supplier the proposal will use; left a draft, so nothing is on order yet', 201);

  // ── the stock position: seventy in, sixty sold today ─────────────────────────
  expect(receive(tenant, store.id, variantId, 70), '[+] seventy beans received', 201);
  const sale = call('POST', '/api/order-svc/orders', { token: owner, idem: true, body: { storeId: store.id, channel: 'POS', fulfilmentType: 'INSTORE', paymentMethod: 'CASH', items: [{ variantId, qty: 60 }] } });
  expect(sale, '[+] sixty sold at the till', 201);
  expect(call('POST', '/api/payment-svc/payments', { token: owner, idem: true, body: { orderId: data(sale).id, amount: data(sale).total, method: 'CASH', storeId: store.id, currency: tenant.currency || 'GBP' } }), '[+] and paid for', [200, 201]);
  let onHand = null;
  poll(60, () => {
    const rows = data(call('GET', `${INV}/levels?store=${store.id}`, { token: owner })) || [];
    const row = rows.find((r) => r.variantId === variantId);
    onHand = row ? Number(row.available) : null;
    return onHand === 10;
  });
  truthy('[+] ten left on the shelf', onHand === 10, onHand);

  // ── the reorder point from this month's demand ───────────────────────────────
  expect(call('POST', `${INV}/demand/aggregate`, { token: owner, body: { storeId: store.id, bucketType: 'MONTH' } }), '[+] the month\'s sales folded into demand history', 200);
  expect(call('PUT', `${INV}/rop-plans`, { token: owner, body: { storeId: store.id, variantId, leadTimeDays: 7, orderingCost: 50, holdingCostPct: 0.2, unitCost: 2.5 } }), '[+] a reorder plan: a week\'s lead time', 200);
  expect(call('POST', `${INV}/rop-plans/compute?store=${store.id}`, { token: owner }), '[+] reorder points computed', 200);
  const plan = data(call('GET', `${INV}/rop-plans/by-variant?store=${store.id}&variant=${variantId}`, { token: owner })) || {};
  truthy('[+] sixty in the month is two a day: a reorder point of fourteen over a week\'s lead time, and an EOQ', Number(plan.rop) > 0 && Number(plan.rop) <= 20 && Number(plan.eoq) > 0, plan);

  // ── the proposal ─────────────────────────────────────────────────────────────
  const run = call('POST', `${orders}/proposals/run`, { token: owner, body: { storeId: store.id } });
  expect(run, '[+] the proposal runs for the store', 200);
  const r = data(run) || {};
  truthy('[+] one item considered, one draft raised on the supplier we bought from, nothing skipped', r.considered === 1 && Array.isArray(r.orders) && r.orders.length === 1 && r.orders[0].supplierId === supplier && r.orders[0].lines === 1 && (r.skipped || []).length === 0, r);
  const draftId = r.orders[0] && r.orders[0].poId;
  const draft = data(call('GET', `${orders}/${draftId}`, { token: owner })) || {};
  truthy('[+] a DRAFT marked as proposed, in the supplier\'s currency, due a week from now', draft.status === 'DRAFT' && draft.source === 'PROPOSAL' && draft.currency === (tenant.currency || 'GBP') && typeof draft.expectedDelivery === 'string', draft);
  const lines = data(call('GET', `${orders}/${draftId}/lines`, { token: owner })) || [];
  truthy('[+] one line: the EOQ at the price we last paid, with the arithmetic beside it', lines.length === 1 && Number(lines[0].qty) > 0 && Number(lines[0].unitPrice) === 2.5 && /on hand 10 \+ on order 0 = 10/.test(lines[0].proposalReason || '') && /reorder point/.test(lines[0].proposalReason || '') && /EOQ/.test(lines[0].proposalReason || ''), lines);
  truthy('[+] the order a person typed carries no reason', ((data(call('GET', `${orders}/${history}/lines`, { token: owner })) || [])[0] || {}).proposalReason == null, 'typed line');
  expect(call('POST', `${orders}/proposals/run`, { token: owner, body: { storeId: store.id } }), '[-] a second run waits for the first draft to be submitted or cancelled', 409, 'PURCHASE_PROPOSAL_OPEN');
  const runs = call('GET', `${orders}/proposals?store=${store.id}`, { token: owner });
  expect(runs, '[+] the store\'s runs read back', 200);
  truthy('[+] ...one run, its draft described', (data(runs) || []).length === 1 && data(runs)[0].orders[0].poId === draftId, data(runs));
  expect(call('POST', `${orders}/${draftId}/cancel`, { token: owner, body: { reason: 'checking the proposal first' } }), '[+] the draft can be cancelled like any other', 200);
  expect(call('POST', `${orders}/proposals/run`, { token: owner, body: { storeId: store.id, coverDays: 14 } }), '[+] ...and then proposed again', 200);

  // ── refusals ─────────────────────────────────────────────────────────────────
  expect(call('POST', `${orders}/proposals/run`, { token: keeper.token, body: { storeId: store.id } }), '[-] a keeper of another store does not propose for this one', 403, 'STORE_ACCESS_DENIED');
  expect(call('POST', `${orders}/proposals/run`, { token: owner, body: { storeId: store.id, coverDays: 0 } }), '[-] a cover of no days', 400, 'PURCHASE_PROPOSAL_COVER_INVALID');
  expect(call('POST', `${orders}/proposals/run`, { token: owner, body: { storeId: store.id, coverDays: 400 } }), '[-] a cover past a year', 400, 'PURCHASE_PROPOSAL_COVER_INVALID');
  expect(call('POST', `${orders}/proposals/run`, { token: owner, body: { storeId: 'the shop' } }), '[-] a store is an id', 400);
  expect(call('GET', `${orders}/proposals`, { token: owner }), '[-] runs are read per store', 400, 'STORE_REQUIRED');
  truthy('[-] another business has no runs for our store', (data(call('GET', `${orders}/proposals?store=${store.id}`, { token: rival.owner.token })) || []).length === 0, 'rival runs');
  expect(call('POST', `${orders}/proposals/run`, { body: { storeId: store.id } }), '[-] no token', 401);
}
