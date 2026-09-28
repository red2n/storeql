// Dropship through the gateway (the other half of "Consignment and dropship stock ownership"):
// stock the business never holds. An arrangement names the supplier that fulfils a variant per
// order; inventory-svc is told, so the variant is available with nothing on the shelf and a
// shopper's delivery order takes a hold that draws nothing; the paid order raises one DRAFT
// purchase order for the supplier, shipped to the customer, at the arrangement's cost; it is never
// received into stock — its delivery is marked instead, and the cost of goods never held is posted
// against what the supplier will invoice. Refused by name and by role throughout; ending the
// arrangement makes the variant a stocked one again.
//
//   k6/run.sh dropship-flow
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
  register,
  sellableVariant,
  staffUser,
  truthy,
  uniq,
} from './lib/storeql.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS, setupTimeout: '4m' };

const P = '/api/purchase-svc';
const I = '/api/inventory-svc';
const O = '/api/order-svc';
const NOBODY = '01a0b000-0000-7000-8000-000000000000';

export function setup() {
  const tenant = onboardTenant('dropship', { stores: 1 });
  ensureStandardVat(tenant);
  const { variantId } = sellableVariant(tenant, 'Dropshipped desk');
  priceVariants(tenant, [variantId], '120.00');
  const cashier = staffUser(tenant, 'CASHIER', [tenant.stores[0].id]);
  return { tenant, variantId, cashier, shopper: register('dropship-shopper') };
}

export default function ({ tenant, variantId, cashier, shopper }) {
  const owner = tenant.owner.token;
  const store = tenant.stores[0];
  const num = (v) => Number(v || 0);
  const availability = () => (data(call('GET', `${I}/inventory/availability?store=${store.id}`, { storefront: tenant.tenantId })) || []).find((a) => a.variantId === variantId);

  // ── 1. nothing on the shelf, and the arrangement that sells it anyway ────────
  truthy('[+] with no stock the desk is not available', !availability() || availability().inStock === false, availability());
  const supplier = must(call('POST', `${P}/suppliers`, { token: owner, body: { name: `Desks Direct ${uniq()}`, vatRegistered: true, currency: 'GBP' } }), 201, 'a supplier');
  const arrangement = must(call('POST', `${P}/admin/dropship/arrangements`, { token: owner, body: { variantId, supplierId: supplier.id, unitCost: '80.00' } }), 201, 'an arrangement');
  truthy('[+] the arrangement names the supplier and its cost', arrangement.supplierId === supplier.id && num(arrangement.unitCost) === 80 && arrangement.active === true, arrangement);
  expect(call('POST', `${P}/admin/dropship/arrangements`, { token: owner, body: { variantId, supplierId: supplier.id, unitCost: '70.00' } }), '[-] one live arrangement per variant', 409, 'PURCHASE_DROPSHIP_ARRANGEMENT_EXISTS');
  expect(call('POST', `${P}/admin/dropship/arrangements`, { token: owner, body: { variantId: NOBODY, supplierId: NOBODY, unitCost: '1' } }), '[-] a supplier nobody has', 404, 'PURCHASE_SUPPLIER_NOT_FOUND');
  expect(call('POST', `${P}/admin/dropship/arrangements`, { token: cashier.token, body: { variantId, supplierId: supplier.id, unitCost: '1' } }), '[-] a cashier does not arrange sourcing', 403);
  const told = poll(60, () => availability() && availability().inStock === true);
  truthy('[+] inventory-svc is told: the desk is available with none on the shelf, and says why', told >= 0 && availability().dropship === true, availability());

  // ── 2. a shopper orders it for delivery: the hold draws nothing ──────────────
  const placed = call('POST', `${O}/orders`, {
    token: shopper.token, storefront: tenant.tenantId, idem: true,
    body: {
      storeId: store.id, channel: 'ONLINE', fulfilmentType: 'DELIVERY',
      deliveryLine1: '12 High Street', deliveryCity: 'Leeds', deliveryPostalCode: 'LS1 1AA',
      deliveryRecipientName: 'Chris Carter', deliveryRecipientPhone: '07700900123', contactPhone: '07700900123',
      items: [{ variantId, qty: 1 }],
    },
  });
  expect(placed, '[+] a delivery order for a desk nobody holds is taken', 201);
  const order = data(placed);
  must(call('POST', '/api/payment-svc/payments', { token: owner, idem: true, body: { orderId: order.id, amount: order.total, method: 'CARD', storeId: store.id } }), [200, 201], 'paid by card');
  const confirmed = poll(60, () => ['CONFIRMED', 'FULFILLED'].includes(data(call('GET', `${O}/orders/${order.id}`, { token: owner })).status));
  truthy('[+] the paid order is confirmed', confirmed >= 0, confirmed);
  const levels = data(call('GET', `${I}/admin/inventory/levels?store=${store.id}`, { token: owner })) || [];
  truthy('[+] nothing on the shelf moved: no level for a desk the business never held', !(levels.find ? levels : levels.items || []).find((l) => l.variantId === variantId), levels.length);

  // ── 3. the supplier's order, raised from the sale, delivered not received ────
  const raised = poll(90, () => (data(call('GET', `${P}/purchase-orders?limit=100`, { token: owner })) || []).some((po) => po.source === 'DROPSHIP' && po.salesOrderId === order.id));
  truthy('[+] purchase-svc raises the supplier\'s order from the confirmed sale', raised >= 0, raised);
  const po = (data(call('GET', `${P}/purchase-orders?limit=100`, { token: owner })) || []).find((p) => p.source === 'DROPSHIP' && p.salesOrderId === order.id) || {};
  truthy('[+] a DRAFT for the supplier, shipped to the customer, at the arrangement\'s cost: 80.00', po.status === 'DRAFT' && po.supplierId === supplier.id && String(po.shipTo || '').includes('Chris Carter') && String(po.shipTo || '').includes('LS1 1AA') && num(po.totalNet) === 80, po);
  const lines = data(call('GET', `${P}/purchase-orders/${po.id}/lines`, { token: owner })) || [];
  truthy('[+] one line: the desk, one of, at 80.00', lines.length === 1 && lines[0].variantId === variantId && num(lines[0].qty) === 1 && num(lines[0].unitPrice) === 80, lines);
  must(call('POST', `${P}/purchase-orders/${po.id}/submit`, { token: owner, body: {} }), 200, 'submitted to the supplier');
  expect(call('POST', `${P}/goods-receipts`, { token: owner, idem: true, body: { poId: po.id, storeId: store.id, lines: [{ variantId, qtyReceived: 1 }] } }), '[-] never received into stock', 409, 'PURCHASE_DROPSHIP_NOT_RECEIVED');
  const before = data(call('GET', `${P}/nominal-ledger/trial-balance`, { token: owner }));
  const delivered = must(call('POST', `${P}/purchase-orders/${po.id}/dropship-delivered`, { token: owner, body: {} }), 200, 'delivered to the customer');
  truthy('[+] delivered: RECEIVED with no stock', delivered.status === 'RECEIVED', delivered);
  const after = data(call('GET', `${P}/nominal-ledger/trial-balance`, { token: owner }));
  const row = (tb, code) => (tb.rows || []).find((r) => r.nominalCode === code) || { balance: 0 };
  truthy('[+] the cost of goods never held is posted against what the supplier will invoice: 80.00', num(row(after, '5020').balance) - num(row(before, '5020').balance) === 80 && num(row(before, '2109').balance) - num(row(after, '2109').balance) === 80, { before: row(before, '5020'), after: row(after, '5020') });
  expect(call('POST', `${P}/purchase-orders/${po.id}/dropship-delivered`, { token: owner, body: {} }), '[-] delivered once', 409, 'PURCHASE_PO_NOT_DELIVERABLE');
  truthy('[+] still nothing on the shelf', !(levels.find ? levels : levels.items || []).find((l) => l.variantId === variantId), null);

  // ── 4. the arrangement ended: a stocked variant again ───────────────────────
  const ended = must(call('POST', `${P}/admin/dropship/arrangements/${arrangement.id}/end`, { token: owner, body: {} }), 200, 'ended');
  truthy('[+] the arrangement is over', ended.active === false, ended);
  expect(call('POST', `${P}/admin/dropship/arrangements/${arrangement.id}/end`, { token: owner, body: {} }), '[-] ended once', 409, 'PURCHASE_DROPSHIP_ARRANGEMENT_ENDED');
  const gone = poll(60, () => !availability() || availability().inStock === false);
  truthy('[+] inventory-svc is told: with no stock the desk is not available again', gone >= 0, availability());
  expect(call('GET', `${P}/admin/dropship/arrangements`, { token: cashier.token }), '[-] a cashier reads no arrangements', 403);
}
