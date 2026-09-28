// Depot / DC replenishment through the gateway (intent/depot-dc-replenishment.md): a shop sells
// sixty of seventy beans and its reorder point is computed from the month; a warehouse is set to
// serve it; the warehouse proposes a DRAFT transfer for what the shop needs, with the arithmetic on
// the line, refuses a second run while it waits, and a person releases it — shipped from the
// warehouse, received at the shop. The shop's purchase proposal no longer buys the beans; the
// warehouse's buys them for the shop. A store that is not a warehouse cannot serve, a store type
// that is neither is refused, a cashier and a rival are refused.
//
//   k6/run.sh dc-replenishment-flow
import {
  ALL_CHECKS_PASS,
  addStore,
  call,
  data,
  expect,
  must,
  newKey,
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
  const tenant = onboardTenant(`depot-${uniq()}`, { stores: 1 });
  // The warehouse exists before inventory-svc reads the business's stores.
  const depot = addStore(tenant, 'DC', 'WAREHOUSE');
  const { variantId } = sellableVariant(tenant, 'Depot beans');
  priceVariants(tenant, [variantId], '4.00');
  const cashier = staffUser(tenant, 'CASHIER', [tenant.stores[0].id]);
  const rival = onboardTenant(`depot-rival-${uniq()}`);
  return { tenant, depot, variantId, cashier, rival };
}

export default function ({ tenant, depot, variantId, cashier, rival }) {
  const owner = tenant.owner.token;
  const shop = tenant.stores[0];
  const num = (v) => Number(v || 0);
  const near = (a, b) => Math.abs(num(a) - num(b)) < 0.01;
  const available = (storeId) =>
    num(((data(call('GET', `${INV}/levels?store=${storeId}`, { token: owner })) || []).find((r) => r.variantId === variantId) || {}).available);

  // ── the shop: seventy in, sixty sold, a reorder point from the month ─────────
  expect(receive(tenant, shop.id, variantId, 70), '[+] seventy beans received at the shop', 201);
  const sale = must(call('POST', '/api/order-svc/orders', { token: owner, idem: true, body: { storeId: shop.id, channel: 'POS', fulfilmentType: 'INSTORE', paymentMethod: 'CASH', items: [{ variantId, qty: 60 }] } }), 201, 'sixty sold');
  must(call('POST', '/api/payment-svc/payments', { token: owner, idem: true, body: { orderId: sale.id, amount: sale.total, method: 'CASH', storeId: shop.id, currency: tenant.currency || 'GBP' } }), [200, 201], 'paid');
  truthy('[+] ten left on the shelf', poll(60, () => available(shop.id) === 10) >= 0, available(shop.id));
  expect(call('POST', `${INV}/demand/aggregate`, { token: owner, body: { storeId: shop.id, bucketType: 'MONTH' } }), '[+] the month folded into demand history', 200);
  expect(call('PUT', `${INV}/rop-plans`, { token: owner, body: { storeId: shop.id, variantId, leadTimeDays: 7, orderingCost: 50, holdingCostPct: 0.2, unitCost: 2.5 } }), '[+] a reorder plan at the shop', 200);
  expect(call('POST', `${INV}/rop-plans/compute?store=${shop.id}`, { token: owner }), '[+] its reorder point computed', 200);
  const plan = data(call('GET', `${INV}/rop-plans/by-variant?store=${shop.id}&variant=${variantId}`, { token: owner })) || {};
  truthy('[+] ten on the shelf is at or below the reorder point', num(plan.rop) >= 10 && num(plan.avgDailyDemand) > 0, plan);

  // ── the network ──────────────────────────────────────────────────────────────
  expect(call('POST', '/api/tenant-svc/admin/stores', { token: owner, body: { name: 'Odd', code: `ODD-${uniq()}`.slice(0, 32), type: 'SHED', line1: '1 High Street', city: 'Leeds', pincode: 'LS1 1AA', country: 'GB', timezone: 'Europe/London' } }), '[-] a store is a shop or a warehouse, nothing else', 400, 'TENANT_STORE_TYPE_INVALID');
  expect(call('PUT', `${INV}/network/serving`, { token: owner, body: { storeId: depot.id, warehouseId: shop.id, leadTimeDays: 2 } }), '[-] a shop does not serve', 400, 'INVENTORY_SERVING_NOT_A_WAREHOUSE');
  const serving = must(call('PUT', `${INV}/network/serving`, { token: owner, body: { storeId: shop.id, warehouseId: depot.id, leadTimeDays: 2 } }), 200, 'the warehouse serves the shop');
  truthy('[+] the shop is served by the warehouse, two days away', serving.warehouseId === depot.id && serving.leadTimeDays === 2, serving);
  expect(call('GET', `${INV}/network/serving`, { token: rival.owner.token }), '[+] a rival sees no network of this business', 200);
  truthy('[+] ...and it is empty', (data(call('GET', `${INV}/network/serving`, { token: rival.owner.token })) || []).length === 0);

  // ── the warehouse proposes, a person releases ───────────────────────────────
  expect(receive(tenant, depot.id, variantId, 25), '[+] twenty-five beans at the warehouse', 201);
  const proposals = `${INV}/network/proposals`;
  expect(call('POST', proposals, { token: cashier.token, idem: true, body: { warehouseId: depot.id } }), '[-] a cashier proposes no transfer', 403);
  const key = newKey();
  const run = must(call('POST', proposals, { token: owner, headers: { 'Idempotency-Key': key }, body: { warehouseId: depot.id } }), 201, 'a proposal run');
  const transfer = (run.transferOrders || [])[0] || {};
  const line = (transfer.lines || [])[0] || {};
  // Back to the reorder point, plus what sells over two days' lead time and seven days' cover.
  const expected = num(plan.rop) - 10 + num(plan.avgDailyDemand) * 9;
  truthy('[+] one DRAFT transfer from the warehouse to the shop', run.transfers === 1 && transfer.status === 'DRAFT' && transfer.source === 'PROPOSAL' && transfer.fromStoreId === depot.id && transfer.toStoreId === shop.id, run);
  truthy('[+] ...for what the shop needs, with the arithmetic on the line', near(line.requestedQty, Math.min(expected, 25)) && String(line.reason || '').includes('≤ reorder point'), { line, expected });
  const again = must(call('POST', proposals, { token: owner, headers: { 'Idempotency-Key': key }, body: { warehouseId: depot.id } }), 201, 'the same key');
  truthy('[+] the same key is the same run', again.id === run.id, again);
  expect(call('POST', proposals, { token: owner, idem: true, body: { warehouseId: depot.id } }), '[-] a second run waits for the draft', 409, 'INVENTORY_PROPOSAL_OPEN');

  // ── purchasing learns the network ────────────────────────────────────────────
  const supplier = must(call('POST', `${PURCHASE}/suppliers`, { token: owner, body: { name: `Bean Wholesale ${uniq()}`, vatRegistered: true, currency: tenant.currency || 'GBP', leadTimeDays: 3 } }), 201, 'supplier').id;
  const history = must(call('POST', `${PURCHASE}/purchase-orders`, { token: owner, body: { supplierId: supplier, storeId: depot.id } }), 201, 'an order').id;
  must(call('POST', `${PURCHASE}/purchase-orders/${history}/lines`, { token: owner, body: { variantId, qty: 10, unitPrice: 2.5 } }), 201, 'a line: where the business buys beans');
  const shopRun = must(call('POST', `${PURCHASE}/purchase-orders/proposals/run`, { token: owner, body: { storeId: shop.id } }), 200, 'the shop\'s purchase proposal');
  truthy('[+] the shop buys no beans: its warehouse sends them', (shopRun.orders || []).length === 0 && shopRun.considered === 0, shopRun);
  const depotRun = must(call('POST', `${PURCHASE}/purchase-orders/proposals/run`, { token: owner, body: { storeId: depot.id } }), 200, 'the warehouse\'s purchase proposal');
  truthy('[+] the warehouse buys beans for the shop it serves', (depotRun.orders || []).length === 1, depotRun);
  const poLines = data(call('GET', `${PURCHASE}/purchase-orders/${((depotRun.orders || [])[0] || {}).poId}/lines`, { token: owner })) || [];
  truthy('[+] ...and says so on the line', String((poLines[0] || {}).proposalReason || '').includes('for the 1 shop it serves'), poLines);

  // ── released, shipped, received ──────────────────────────────────────────────
  const released = must(call('POST', `${INV}/transfers/${transfer.id}/release`, { token: owner }), 200, 'released');
  truthy('[+] released, it is an ordinary transfer', released.status === 'PENDING', released);
  expect(call('POST', `${INV}/transfers/${transfer.id}/release`, { token: owner }), '[-] released once', 409, 'INVENTORY_TRANSFER_NOT_DRAFT');
  must(call('POST', `${INV}/transfers/${transfer.id}/ship`, { token: owner }), 200, 'shipped');
  truthy('[+] shipped: the warehouse holds what it did not send', near(available(depot.id), 25 - num(line.requestedQty)), available(depot.id));
  must(call('POST', `${INV}/transfers/${transfer.id}/receive`, { token: owner }), 200, 'received');
  truthy('[+] received: the shop holds what came', near(available(shop.id), 10 + num(line.requestedQty)), available(shop.id));
  expect(call('POST', `${INV}/transfers/${transfer.id}/release`, { token: rival.owner.token }), '[-] a rival releases nothing of this business', 404);
}
