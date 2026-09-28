// Consignment stock ownership through the gateway: a purchase order on consignment is received
// without the goods becoming the business's stock or its debt (the batch says whose it is, the
// valuation sets it apart, the ledger posts nothing at the door); a till sale drawn from that
// batch is announced by inventory-svc and owed to the supplier in purchase-svc at the order's
// price, once; a settlement gathers the period's sales into one statement, once. Refused by name
// and by role: an ownership nobody defined, consignment stock with no supplier, an invoice against
// a consignment order, a period that ends before it starts, a cashier settling.
//
//   k6/run.sh consignment-flow
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

const P = '/api/purchase-svc';
const I = '/api/inventory-svc';
const O = '/api/order-svc';

export function setup() {
  const tenant = onboardTenant('consign', { stores: 1 });
  ensureStandardVat(tenant);
  const { variantId } = sellableVariant(tenant, 'Consigned candle');
  priceVariants(tenant, [variantId], '9.00');
  const cashier = staffUser(tenant, 'CASHIER', [tenant.stores[0].id]);
  return { tenant, variantId, cashier };
}

export default function ({ tenant, variantId, cashier }) {
  const owner = tenant.owner.token;
  const store = tenant.stores[0];
  const num = (v) => Number(v || 0);
  const today = new Date().toISOString().slice(0, 10);

  // ── 1. a consignment order, received: the supplier's stock on our shelf ────
  const supplier = must(call('POST', `${P}/suppliers`, { token: owner, body: { name: `Sale or Return ${uniq()}`, vatRegistered: true, currency: 'GBP' } }), 201, 'a supplier');
  const po = must(call('POST', `${P}/purchase-orders`, { token: owner, body: { supplierId: supplier.id, storeId: store.id, ownership: 'consignment' } }), 201, 'a consignment order');
  truthy('[+] the order says whose the goods will be', po.ownership === 'CONSIGNMENT', po);
  truthy('[+] an order that says nothing is ours on arrival', data(call('POST', `${P}/purchase-orders`, { token: owner, body: { supplierId: supplier.id, storeId: store.id } })).ownership === 'OWNED', null);
  expect(call('POST', `${P}/purchase-orders`, { token: owner, body: { supplierId: supplier.id, storeId: store.id, ownership: 'BORROWED' } }), '[-] an ownership nobody defined', 400, 'PURCHASE_OWNERSHIP_INVALID');
  must(call('POST', `${P}/purchase-orders/${po.id}/lines`, { token: owner, body: { variantId, qty: 5, unitPrice: '3.00' } }), 201, 'five at 3.00');
  must(call('POST', `${P}/purchase-orders/${po.id}/submit`, { token: owner, body: {} }), 200, 'submitted');
  const before = data(call('GET', `${P}/nominal-ledger/trial-balance`, { token: owner }));
  must(call('POST', `${P}/goods-receipts`, { token: owner, idem: true, body: { poId: po.id, storeId: store.id, lines: [{ variantId, qtyReceived: 5 }] } }), 201, 'received');
  const after = data(call('GET', `${P}/nominal-ledger/trial-balance`, { token: owner }));
  truthy('[+] the receipt posts nothing: no stock asset, nothing owed at the door', num(after.totalDebit) === num(before.totalDebit), { before: before.totalDebit, after: after.totalDebit });
  expect(call('POST', `${P}/supplier-invoices`, { token: owner, body: { poId: po.id, invoiceNumber: `INV-${uniq()}`, invoiceDate: today, vatAmount: 0, lines: [{ variantId, qty: 5, unitPrice: 3.0 }] } }), '[-] a consignment order is not invoiced on receipt', 409, 'PURCHASE_CONSIGNMENT_NOT_INVOICED');

  const arrived = poll(60, () => (data(call('GET', `${I}/admin/inventory/batches?store=${store.id}&variant=${variantId}`, { token: owner })) || []).length > 0);
  truthy('[+] inventory-svc receives the batch from the GoodsReceived event', arrived >= 0, arrived);
  const batches = data(call('GET', `${I}/admin/inventory/batches?store=${store.id}&variant=${variantId}`, { token: owner })) || [];
  const batch = batches[0] || {};
  truthy("[+] the batch is the supplier's, at the order's price", batch.ownership === 'CONSIGNMENT' && batch.ownerSupplierId === supplier.id && num(batch.costPrice) === 3 && num(batch.remainingQty) === 5, batch);
  const valued = (data(call('GET', `${I}/admin/inventory/reports/valuation?groupBy=VARIANT&limit=200`, { token: owner })) || []).find((r) => r.groupKey === variantId) || {};
  truthy('[+] the valuation sets it apart: 5 on consignment worth 15.00, none of it our value', num(valued.consignmentQty) === 5 && num(valued.consignmentValue) === 15 && num(valued.value) === 0, valued);

  // ── 2. refused by name at the goods-in door ────────────────────────────────
  expect(call('POST', `${I}/admin/inventory/receive`, { token: owner, idem: true, body: { storeId: store.id, variantId, qty: 1, ownership: 'CONSIGNMENT' } }), '[-] consignment stock with no supplier to belong to', 400, 'INVENTORY_CONSIGNMENT_SUPPLIER_REQUIRED');
  expect(call('POST', `${I}/admin/inventory/receive`, { token: owner, idem: true, body: { storeId: store.id, variantId, qty: 1, ownership: 'BORROWED' } }), '[-] an ownership nobody defined, at goods-in', 400, 'INVENTORY_OWNERSHIP_INVALID');

  // ── 3. a till sale draws from the supplier's stock: owed, once ─────────────
  const order = must(call('POST', `${O}/orders`, { token: owner, idem: true, body: { storeId: store.id, channel: 'POS', fulfilmentType: 'INSTORE', paymentMethod: 'CASH', items: [{ variantId, qty: 2 }] } }), 201, 'a till sale of two');
  must(call('POST', '/api/payment-svc/payments', { token: owner, idem: true, body: { orderId: order.id, amount: order.total, method: 'CASH', storeId: store.id, currency: 'GBP' } }), [200, 201], 'paid in cash');
  // A till sale is handed over the moment it is paid for; the OrderFulfilled event draws the stock.
  const handed = poll(60, () => data(call('GET', `${O}/orders/${order.id}`, { token: owner })).status === 'FULFILLED');
  truthy('[+] paying for the till sale hands it over', handed >= 0, handed);
  const owed = poll(90, () => (data(call('GET', `${P}/admin/consignment/sales?supplierId=${supplier.id}&settled=false`, { token: owner })) || []).length > 0);
  truthy('[+] purchase-svc hears of the sale', owed >= 0, owed);
  const sales = data(call('GET', `${P}/admin/consignment/sales?supplierId=${supplier.id}&settled=false`, { token: owner })) || [];
  truthy("[+] one sale of two, owed at the order's price: 6.00", sales.length === 1 && num(sales[0].qty) === 2 && num(sales[0].unitCost) === 3 && num(sales[0].amount) === 6 && sales[0].currency === 'GBP' && sales[0].settled === false, sales);
  const tb = data(call('GET', `${P}/nominal-ledger/trial-balance`, { token: owner }));
  const row = (code) => (tb.rows || []).find((r) => r.nominalCode === code) || {};
  truthy('[+] the ledger owes the supplier the moment it sold: purchases 6.00 against creditors', num(row('5010').balance) === 6 && num(row('2100').balance) <= -6, { purchases: row('5010'), creditors: row('2100') });
  const left = (data(call('GET', `${I}/admin/inventory/batches?store=${store.id}&variant=${variantId}`, { token: owner })) || [])[0] || {};
  truthy("[+] what is left is still the supplier's: 3", left.ownership === 'CONSIGNMENT' && num(left.remainingQty) === 3, left);

  // ── 4. settled once ────────────────────────────────────────────────────────
  const statement = must(call('POST', `${P}/admin/consignment/settlements`, { token: owner, body: { supplierId: supplier.id, from: '2026-01-01', to: today } }), 201, 'a statement');
  truthy('[+] the statement gathers the sale: 6.00, one sale, a reference to invoice against', num(statement.total) === 6 && statement.salesCount === 1 && statement.currency === 'GBP' && String(statement.reference).startsWith('CS-'), statement);
  truthy('[+] nothing is left unsettled', (data(call('GET', `${P}/admin/consignment/sales?supplierId=${supplier.id}&settled=false`, { token: owner })) || []).length === 0, null);
  truthy('[+] the statement reads back with its sale', (data(call('GET', `${P}/admin/consignment/settlements/${statement.id}`, { token: owner })).sales || []).length === 1, null);
  expect(call('POST', `${P}/admin/consignment/settlements`, { token: owner, body: { supplierId: supplier.id, from: '2026-01-01', to: today } }), '[-] settled once: the same period again has nothing', 409, 'PURCHASE_CONSIGNMENT_NOTHING_TO_SETTLE');
  expect(call('POST', `${P}/admin/consignment/settlements`, { token: owner, body: { supplierId: supplier.id, from: today, to: '2026-01-01' } }), '[-] a period that ends before it starts', 400, 'PURCHASE_CONSIGNMENT_PERIOD_INVALID');
  expect(call('POST', `${P}/admin/consignment/settlements`, { token: cashier.token, body: { supplierId: supplier.id, from: '2026-01-01', to: today } }), '[-] a cashier does not settle suppliers', 403);
  expect(call('GET', `${P}/admin/consignment/sales`, { token: cashier.token }), '[-] nor reads what is owed', 403);
}
