// Bonded and duty-suspended stock through the gateway: a warehouse approved as a bonded store
// (management; a regime nobody defined and a cashier refused), a duty per unit for the whisky, a
// purchase order placed under bond whose receipt arrives with the duty suspended (the batch says
// so; on hand, in bond, nothing available; a hold refused); duty-suspended stock refused at a shop
// nobody approved and a status nobody defined refused; the valuation at cost without the duty and
// the duty it would crystallise beside it; a release to home use by a storekeeper that pays the
// duty (a movement of its own, a duty-paid batch, the rest still in bond), heard by purchase-svc
// as Excise Duty against Excise Duty Payable; more than is in bond, a variant with no rate, an
// unbonded shop and an ended approval refused.
//
//   k6/run.sh bond-flow
import {
  ALL_CHECKS_PASS,
  call,
  data,
  ensureStandardVat,
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

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS, setupTimeout: '4m' };

const I = '/api/inventory-svc';
const P = '/api/purchase-svc';
const BOND = `${I}/admin/inventory/bond`;

export function setup() {
  const tenant = onboardTenant('bond', { stores: 2 });
  ensureStandardVat(tenant);
  const { variantId } = sellableVariant(tenant, 'Single malt 70cl');
  const unrated = sellableVariant(tenant, 'Unrated gin').variantId;
  priceVariants(tenant, [variantId, unrated], '30.00');
  const storekeeper = staffUser(tenant, 'STOREKEEPER', [tenant.stores[1].id]);
  const cashier = staffUser(tenant, 'CASHIER', [tenant.stores[0].id]);
  return { tenant, variantId, unrated, storekeeper, cashier };
}

export default function ({ tenant, variantId, unrated, storekeeper, cashier }) {
  const owner = tenant.owner.token;
  const [shop, warehouse] = tenant.stores;
  const num = (v) => Number(v || 0);
  const today = new Date().toISOString().slice(0, 10);
  const level = (storeId) => (data(call('GET', `${I}/admin/inventory/levels?store=${storeId}`, { token: owner })) || []).find((l) => l.variantId === variantId) || {};
  const tb = () => data(call('GET', `${P}/nominal-ledger/trial-balance`, { token: owner }));
  const row = (t, code) => (t.rows || []).find((r) => r.nominalCode === code) || { balance: 0 };

  // ── 1. the bonded warehouse and the duty the whisky crystallises ─────────────
  const approval = must(call('PUT', `${BOND}/approvals/${warehouse.id}`, { token: owner, body: { approvalNumber: `GBWK${uniq()}`, regime: 'excise' } }), 200, 'the warehouse approved');
  truthy('[+] the warehouse is approved as an excise warehouse', approval.storeId === warehouse.id && approval.regime === 'EXCISE' && approval.active === true, approval);
  expect(call('PUT', `${BOND}/approvals/${shop.id}`, { token: owner, body: { approvalNumber: 'X', regime: 'BONDED' } }), '[-] a regime nobody defined', 400, 'INVENTORY_BOND_REGIME_INVALID');
  expect(call('PUT', `${BOND}/approvals/${shop.id}`, { token: cashier.token, body: { approvalNumber: 'X', regime: 'EXCISE' } }), '[-] a cashier does not approve warehouses', 403);
  const rate = must(call('PUT', `${BOND}/duty-rates/${variantId}`, { token: owner, body: { dutyPerUnit: '2.50', note: '70cl at 40%: 0.28 lpa at GBP 31.64' } }), 200, 'a duty rate');
  truthy('[+] the duty per unit is kept in the home currency', num(rate.dutyPerUnit) === 2.5 && rate.currency === 'GBP', rate);
  expect(call('PUT', `${BOND}/duty-rates/${variantId}`, { token: storekeeper.token, body: { dutyPerUnit: '1' } }), '[-] a storekeeper does not set duty', 403);

  // ── 2. an order under bond, received with the duty suspended ────────────────
  const supplier = must(call('POST', `${P}/suppliers`, { token: owner, body: { name: `Distillers ${uniq()}`, vatRegistered: true, currency: 'GBP' } }), 201, 'a supplier');
  const po = must(call('POST', `${P}/purchase-orders`, { token: owner, body: { supplierId: supplier.id, storeId: warehouse.id, dutyStatus: 'DUTY_SUSPENDED' } }), 201, 'an order under bond');
  truthy('[+] the order says the goods arrive into bond', po.dutyStatus === 'DUTY_SUSPENDED', po);
  expect(call('POST', `${P}/purchase-orders`, { token: owner, body: { supplierId: supplier.id, storeId: warehouse.id, dutyStatus: 'DUTY_FREE' } }), '[-] a duty status nobody defined', 400, 'PURCHASE_DUTY_STATUS_INVALID');
  must(call('POST', `${P}/purchase-orders/${po.id}/lines`, { token: owner, body: { variantId, qty: 10, unitPrice: '20.00' } }), 201, 'ten at 20.00');
  must(call('POST', `${P}/purchase-orders/${po.id}/submit`, { token: owner, body: {} }), 200, 'submitted');
  must(call('POST', `${P}/goods-receipts`, { token: owner, idem: true, body: { poId: po.id, storeId: warehouse.id, lines: [{ variantId, qtyReceived: 10 }] } }), 201, 'received into bond');
  const arrived = poll(60, () => num(level(warehouse.id).onHand) === 10);
  truthy('[+] on hand at the warehouse, all of it in bond, none available', arrived >= 0 && num(level(warehouse.id).inBond) === 10 && num(level(warehouse.id).available) === 0, level(warehouse.id));
  const batches = data(call('GET', `${I}/admin/inventory/batches?store=${warehouse.id}&variant=${variantId}`, { token: owner })) || [];
  truthy('[+] the batch says its duty is suspended', batches.length === 1 && batches[0].dutyStatus === 'DUTY_SUSPENDED', batches);
  expect(call('POST', `${I}/inventory/reservations`, { token: owner, idem: true, body: { storeId: warehouse.id, variantId, qty: 1 } }), '[-] nothing in bond can be held for a sale', 422);
  expect(call('POST', `${I}/admin/inventory/receive`, { token: owner, idem: true, body: { storeId: shop.id, variantId, qty: 1, dutyStatus: 'DUTY_SUSPENDED' } }), '[-] duty-suspended stock at a shop nobody approved', 400, 'INVENTORY_STORE_NOT_BONDED');
  expect(call('POST', `${I}/admin/inventory/receive`, { token: owner, idem: true, body: { storeId: warehouse.id, variantId, qty: 1, dutyStatus: 'DUTY_FREE' } }), '[-] a duty status nobody defined, at goods-in', 400, 'INVENTORY_DUTY_STATUS_INVALID');
  const valued = (data(call('GET', `${I}/admin/inventory/reports/valuation?groupBy=VARIANT&limit=200`, { token: owner })) || []).find((r) => r.groupKey === variantId) || {};
  truthy('[+] valued at cost without the duty, the duty it would crystallise beside it: 200.00 and 25.00', num(valued.value) === 200 && num(valued.dutySuspendedQty) === 10 && num(valued.dutyPotential) === 25, valued);
  const inBond = (data(call('GET', `${BOND}/stock?storeId=${warehouse.id}`, { token: owner })) || []).find((s) => s.variantId === variantId) || {};
  truthy('[+] the stock in bond says what it carries', num(inBond.qty) === 10 && num(inBond.dutyPotential) === 25, inBond);

  // ── 3. released to home use by the storekeeper: the duty is paid ────────────
  const before = tb();
  const release = must(call('POST', `${BOND}/releases`, { token: storekeeper.token, body: { storeId: warehouse.id, variantId, qty: 4, reference: 'W5 September' } }), 201, 'four released');
  truthy('[+] four out of bond owe 10.00 of duty', num(release.qty) === 4 && num(release.dutyPerUnit) === 2.5 && num(release.dutyAmount) === 10 && release.currency === 'GBP', release);
  truthy('[+] six still in bond, four duty-paid and available', num(level(warehouse.id).inBond) === 6 && num(level(warehouse.id).available) === 4 && num(level(warehouse.id).onHand) === 10, level(warehouse.id));
  const after = data(call('GET', `${I}/admin/inventory/batches?store=${warehouse.id}&variant=${variantId}`, { token: owner })) || [];
  truthy('[+] the released four are a duty-paid batch of their own', after.length === 2 && after.some((b) => b.dutyStatus === 'DUTY_PAID' && num(b.remainingQty) === 4) && after.some((b) => b.dutyStatus === 'DUTY_SUSPENDED' && num(b.remainingQty) === 6), after);
  const period = data(call('GET', `${BOND}/releases?storeId=${warehouse.id}&from=2026-01-01&to=${today}`, { token: owner })) || {};
  truthy('[+] the releases of the period add up to the duty owed', (period.releases || []).length === 1 && num(period.totalDuty) === 10, period);
  const owed = poll(90, () => num(row(tb(), '5030').balance) - num(row(before, '5030').balance) === 10);
  truthy('[+] purchase-svc owes the duty to the revenue: Excise Duty 10.00 against Excise Duty Payable', owed >= 0 && num(row(before, '2140').balance) - num(row(tb(), '2140').balance) === 10, { before: row(before, '5030'), after: row(tb(), '5030') });
  const returned = data(call('GET', `${P}/admin/duty/releases?from=2026-01-01&to=${today}`, { token: owner })) || {};
  truthy('[+] the excise return figure reads back', (returned.releases || []).length === 1 && num(returned.totalDuty) === 10, returned);

  // ── 4. refused by name, and the approval ended ─────────────────────────────
  expect(call('POST', `${BOND}/releases`, { token: owner, body: { storeId: warehouse.id, variantId, qty: 7, reference: 'too many' } }), '[-] more than sits in bond', 422, 'INVENTORY_INSUFFICIENT_BONDED_STOCK');
  must(call('POST', `${I}/admin/inventory/receive`, { token: owner, idem: true, body: { storeId: warehouse.id, variantId: unrated, qty: 3, dutyStatus: 'DUTY_SUSPENDED' } }), 201, 'gin into bond');
  expect(call('POST', `${BOND}/releases`, { token: owner, body: { storeId: warehouse.id, variantId: unrated, qty: 1 } }), '[-] a variant with no duty rate', 409, 'INVENTORY_DUTY_RATE_MISSING');
  expect(call('POST', `${BOND}/releases`, { token: owner, body: { storeId: shop.id, variantId, qty: 1 } }), '[-] a shop that is not bonded', 409, 'INVENTORY_STORE_NOT_BONDED');
  expect(call('GET', `${P}/admin/duty/releases?from=2026-01-01&to=${today}`, { token: cashier.token }), '[-] a cashier reads no return', 403);
  must(call('POST', `${BOND}/approvals/${warehouse.id}/end`, { token: owner, body: {} }), 200, 'approval ended');
  expect(call('POST', `${I}/admin/inventory/receive`, { token: owner, idem: true, body: { storeId: warehouse.id, variantId, qty: 1, dutyStatus: 'DUTY_SUSPENDED' } }), '[-] an ended approval takes no more suspended stock', 400, 'INVENTORY_STORE_NOT_BONDED');
}
