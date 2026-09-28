// Cross-docking through the gateway (intent/cross-docking.md): a warehouse serves two shops; a
// buyer allocates a warehouse order's line to them (more than the line, a cashier and a shop order
// refused), submits it, and inventory-svc hears what the order owes each shop; the delivery arrives
// four short and goes straight across the dock — one PENDING transfer per shop from the batch the
// delivery made, shared in proportion to what each was owed, nothing owed any more — and the Leeds
// transfer is shipped and received there. A rival sees nothing of it.
//
//   k6/run.sh cross-dock-flow
import {
  ALL_CHECKS_PASS,
  addStore,
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

const INV = '/api/inventory-svc/admin/inventory';
const P = '/api/purchase-svc';

export function setup() {
  const tenant = onboardTenant(`xdock-${uniq()}`, { stores: 1 });
  const york = addStore(tenant, 'York');
  const depot = addStore(tenant, 'DC', 'WAREHOUSE');
  const { variantId } = sellableVariant(tenant, 'Cross-dock beans');
  const cashier = staffUser(tenant, 'CASHIER', [depot.id]);
  const rival = onboardTenant(`xdock-rival-${uniq()}`);
  return { tenant, york, depot, variantId, cashier, rival };
}

export default function ({ tenant, york, depot, variantId, cashier, rival }) {
  const owner = tenant.owner.token;
  const leeds = tenant.stores[0];
  const num = (v) => Number(v || 0);
  const available = (storeId) =>
    num(((data(call('GET', `${INV}/levels?store=${storeId}`, { token: owner })) || []).find((r) => r.variantId === variantId) || {}).available);

  // ── the network, the order ───────────────────────────────────────────────────
  for (const shop of [leeds, york]) {
    must(call('PUT', `${INV}/network/serving`, { token: owner, body: { storeId: shop.id, warehouseId: depot.id, leadTimeDays: 1 } }), 200, 'served');
  }
  const supplier = must(call('POST', `${P}/suppliers`, { token: owner, body: { name: `Bean Co ${uniq()}`, vatRegistered: true, currency: tenant.currency || 'GBP' } }), 201, 'supplier').id;
  const po = must(call('POST', `${P}/purchase-orders`, { token: owner, body: { supplierId: supplier, storeId: depot.id } }), 201, 'the warehouse order').id;
  const line = must(call('POST', `${P}/purchase-orders/${po}/lines`, { token: owner, body: { variantId, qty: 40, unitPrice: 2.0 } }), 201, 'forty beans').id;
  const allocate = `${P}/purchase-orders/${po}/lines/${line}/allocations`;
  expect(call('PUT', allocate, { token: owner, body: { allocations: [{ storeId: leeds.id, qty: 30 }, { storeId: york.id, qty: 11 }] } }), '[-] more than the line', 400, 'PURCHASE_ALLOCATION_EXCEEDS_LINE');
  expect(call('PUT', allocate, { token: cashier.token, body: { allocations: [{ storeId: leeds.id, qty: 5 }] } }), '[-] a cashier allocates nothing', 403);
  const set = must(call('PUT', allocate, { token: owner, body: { allocations: [{ storeId: leeds.id, qty: 25 }, { storeId: york.id, qty: 15 }] } }), 200, 'allocated');
  truthy('[+] twenty-five for Leeds and fifteen for York', set.length === 2 && set.reduce((t, a) => t + num(a.qty), 0) === 40, set);
  const shopPo = must(call('POST', `${P}/purchase-orders`, { token: owner, body: { supplierId: supplier, storeId: leeds.id } }), 201, 'a shop order').id;
  const shopLine = must(call('POST', `${P}/purchase-orders/${shopPo}/lines`, { token: owner, body: { variantId, qty: 5, unitPrice: 2.0 } }), 201, 'a line').id;
  expect(call('PUT', `${P}/purchase-orders/${shopPo}/lines/${shopLine}/allocations`, { token: owner, body: { allocations: [{ storeId: york.id, qty: 5 }] } }), '[-] only a warehouse order crosses a dock', 400, 'PURCHASE_ALLOCATION_NOT_A_WAREHOUSE');

  must(call('POST', `${P}/purchase-orders/${po}/submit`, { token: owner }), 200, 'submitted');
  expect(call('PUT', allocate, { token: owner, body: { allocations: [{ storeId: leeds.id, qty: 5 }] } }), '[-] allocated while a draft only', 409, 'PURCHASE_ALLOCATION_ORDER_NOT_DRAFT');
  const heard = poll(60, () => (data(call('GET', `${INV}/network/crossdock?purchaseOrderId=${po}`, { token: owner })) || []).length === 2) >= 0;
  truthy('[+] inventory-svc knows what the order owes each shop', heard, data(call('GET', `${INV}/network/crossdock?purchaseOrderId=${po}`, { token: owner })));
  truthy('[+] a rival sees nothing owed', (data(call('GET', `${INV}/network/crossdock?purchaseOrderId=${po}`, { token: rival.owner.token })) || []).length === 0);

  // ── the delivery crosses the dock, four short ────────────────────────────────
  must(call('POST', `${P}/goods-receipts`, { token: owner, idem: true, body: { poId: po, storeId: depot.id, lines: [{ variantId, qtyReceived: 36 }] } }), 201, 'thirty-six arrive');
  const pending = (storeId) => (data(call('GET', `${INV}/transfers?store=${storeId}&status=PENDING`, { token: owner })) || []).filter((t) => t.source === 'CROSSDOCK' && t.toStoreId === storeId);
  const crossed = poll(60, () => pending(leeds.id).length === 1 && pending(york.id).length === 1) >= 0;
  const toLeeds = pending(leeds.id)[0] || { lines: [] };
  const toYork = pending(york.id)[0] || { lines: [] };
  const lq = num((toLeeds.lines[0] || {}).requestedQty);
  const yq = num((toYork.lines[0] || {}).requestedQty);
  truthy('[+] a PENDING cross-dock transfer to each shop, naming the order', crossed && toLeeds.purchaseOrderId === po && toYork.purchaseOrderId === po && toLeeds.fromStoreId === depot.id, { toLeeds, toYork });
  truthy('[+] thirty-six shared in proportion to twenty-five and fifteen', lq + yq === 36 && lq >= 22 && lq <= 23 && yq >= 13 && yq <= 14, { lq, yq });
  truthy('[+] ...and the line says the delivery came short', String((toLeeds.lines[0] || {}).reason || '').includes('the delivery came short'), toLeeds.lines);
  truthy('[+] what was not delivered is still owed', (data(call('GET', `${INV}/network/crossdock?purchaseOrderId=${po}`, { token: owner })) || []).reduce((t, o) => t + num(o.qty), 0) === 4);
  truthy('[+] the warehouse holds the crossing stock until it ships', available(depot.id) === 36, available(depot.id));

  // ── shipped and received at Leeds ────────────────────────────────────────────
  must(call('POST', `${INV}/transfers/${toLeeds.id}/ship`, { token: owner }), 200, 'shipped');
  must(call('POST', `${INV}/transfers/${toLeeds.id}/receive`, { token: owner }), 200, 'received');
  truthy('[+] Leeds holds what crossed to it', available(leeds.id) === lq, available(leeds.id));
  truthy('[+] the warehouse holds only York\'s, waiting to ship', available(depot.id) === yq, available(depot.id));
  expect(call('GET', `${INV}/transfers/${toLeeds.id}`, { token: rival.owner.token }), '[-] a rival reads no transfer of this business', 404);
}
