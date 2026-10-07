// Item lifecycle (master data): a line is listed before it goes on sale, sells, is run down and is
// taken off. A NEW_LINE is hidden from the shop and refused at the till with its launch day; launched,
// it lists and scans; DISCONTINUED it still sells while stock lasts but leaves the low-stock report
// and the planning run raises nothing for it; REINSTATED it is back; DELISTED it is gone from the
// shop and the till. Refused: a move a line cannot make from where it is, a launch day on a line
// already on sale, an unknown status at creation, a cashier's move; abuse: a rival's owner moving
// our line.
//
//   k6/run.sh item-lifecycle-flow
import { Counter } from 'k6/metrics';
import {
  ALL_CHECKS_PASS,
  SAFETY_INFORMATION,
  call,
  data,
  expect,
  gtin13,
  must,
  poll,
  priceVariants,
  sellingTenant,
  truthy,
  uniq,
} from './lib/storeql.js';

const completed = new Counter('flow_completed');
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
  setupTimeout: '5m',
};

const P = '/api/product-svc';
const INV = '/api/inventory-svc/admin/inventory';

export function setup() {
  return { t: sellingTenant('lifecycle', { country: 'GB', currency: 'GBP' }) };
}

export default function ({ t }) {
  const owner = t.tenant.owner.token;
  const cashier = t.cashier.token;
  const rival = t.rival.owner.token;
  const sf = t.tenant.tenantId;
  const barcode = gtin13();
  const move = (id, action, token = owner) => call('POST', `${P}/admin/products/${id}/${action}`, { token });
  const listed = (id) => (data(call('GET', `${P}/catalog/products?limit=100`, { storefront: sf })) || []).some((p) => p.id === id);
  const scan = () => call('GET', `${P}/catalog/variants/by-barcode/${barcode}`, { token: cashier });
  const lowStock = () => (data(call('GET', `${INV}/reports/low-stock?storeId=${t.store.id}`, { token: owner })) || []);
  const inLowStock = (variantId) => lowStock().some((r) => r.variantId === variantId);

  // ── listed before it goes on sale ────────────────────────────────────────────────────────────────
  expect(call('POST', `${P}/admin/products`, { token: owner, body: { name: `Odd ${uniq()}`, status: 'RETIRED', safetyInformation: SAFETY_INFORMATION } }), '[-] a status the lifecycle does not know is refused', 400, 'PRODUCT_STATUS_INVALID');
  expect(call('POST', `${P}/admin/products`, { token: owner, body: { name: `Odd ${uniq()}`, launchOn: '2027-01-01', safetyInformation: SAFETY_INFORMATION } }), '[-] a launch day belongs to a new line, not one on sale', 400, 'PRODUCT_LAUNCH_ON_NEEDS_NEW_LINE');
  expect(call('POST', `${P}/admin/products`, { token: owner, body: { name: `Odd ${uniq()}`, status: 'NEW_LINE', launchOn: 'soon', safetyInformation: SAFETY_INFORMATION } }), '[-] a launch day is a date', 400, 'PRODUCT_LAUNCH_ON_INVALID');
  const created = call('POST', `${P}/admin/products`, { token: owner, body: { name: `Spring cola ${uniq()}`, status: 'new_line', launchOn: '2026-10-01', sellableOnline: true, sellablePos: true, safetyInformation: SAFETY_INFORMATION } });
  expect(created, '[+] a new line is listed with the day it goes on sale', 201);
  const product = data(created) || {};
  truthy('[+] ...NEW_LINE, launch day kept', product.status === 'NEW_LINE' && product.launchOn === '2026-10-01', product);
  const pid = product.id;
  const variant = must(call('POST', `${P}/admin/products/${pid}/variants`, { token: owner, body: { sku: `SC-${uniq()}`, barcode, unit: 'PCS' } }), 201, 'variant');
  priceVariants(t.tenant, [variant.id], '1.20');
  truthy('[+] the shop does not list it', !listed(pid), 'listed early');
  expect(scan(), '[-] the till refuses it, naming the day', 409, 'PRODUCT_NOT_ON_SALE_YET');
  expect(move(pid, 'discontinue'), '[-] a line not yet on sale cannot be run down', 409, 'PRODUCT_LIFECYCLE_INVALID');
  expect(move(pid, 'reinstate'), '[-] nor reinstated', 409, 'PRODUCT_LIFECYCLE_INVALID');
  expect(move(pid, 'launch', cashier), '[-] a cashier does not launch a line', 403);
  expect(move(pid, 'launch', rival), "[abuse] another business's owner does not launch our line", 404, 'PRODUCT_NOT_FOUND');

  // ── on sale ──────────────────────────────────────────────────────────────────────────────────────
  const launched = move(pid, 'launch');
  expect(launched, '[+] the owner launches it', 200);
  truthy('[+] ...ACTIVE, the launch day cleared', (data(launched) || {}).status === 'ACTIVE' && !(data(launched) || {}).launchOn, data(launched));
  truthy('[+] the shop lists it now', listed(pid), 'not listed');
  expect(scan(), '[+] the till scans it', 200);
  expect(move(pid, 'launch'), '[-] launched twice is not a move', 409, 'PRODUCT_LIFECYCLE_INVALID');
  expect(call('PUT', `${P}/admin/products/${pid}`, { token: owner, body: { name: `Spring cola ${uniq()}`, sellableOnline: true, sellablePos: true } }), '[+] an edit keeps the state', 200);

  // ── replenishment sees it, then loses it ─────────────────────────────────────────────────────────
  expect(call('POST', `${INV}/thresholds`, { token: owner, body: { storeId: t.store.id, variantId: variant.id, threshold: 10, maxQty: 40 } }), '[+] a reorder threshold is set for it', [200, 201]);
  truthy('[+] with nothing on hand it is on the low-stock report', poll(20, () => inLowStock(variant.id)) >= 0, 'not low');
  const discontinued = move(pid, 'discontinue');
  expect(discontinued, '[+] the owner marks it for run-down', 200);
  truthy('[+] ...DISCONTINUED, with the moment', (data(discontinued) || {}).status === 'DISCONTINUED' && !!(data(discontinued) || {}).discontinuedAt, data(discontinued));
  truthy('[+] the shop still lists it: sold while stock lasts', listed(pid), 'not listed');
  expect(scan(), '[+] the till still sells it', 200);
  truthy('[+] ...but it has left the low-stock report', poll(30, () => !inLowStock(variant.id)) >= 0, 'still low');
  const run = call('POST', `${INV}/planning/run`, { token: owner, body: { storeId: t.store.id } });
  expect(run, '[+] the planning run goes through', [200, 201]);
  truthy('[+] ...and raises nothing for a line being run down', !(data(run) || []).some((s) => s.variantId === variant.id), data(run));
  expect(move(pid, 'discontinue'), '[-] run down twice is not a move', 409, 'PRODUCT_LIFECYCLE_INVALID');
  expect(move(pid, 'launch'), '[-] nor launched', 409, 'PRODUCT_LIFECYCLE_INVALID');
  truthy('[+] the admin list filters by the state', (data(call('GET', `${P}/admin/products?status=DISCONTINUED&limit=100`, { token: owner })) || []).some((p) => p.id === pid), 'not in DISCONTINUED');

  // ── back, then gone ──────────────────────────────────────────────────────────────────────────────
  expect(move(pid, 'reinstate'), '[+] the owner reinstates it', 200);
  truthy('[+] ...back on the low-stock report', poll(30, () => inLowStock(variant.id)) >= 0, 'not low again');
  expect(call('DELETE', `${P}/admin/products/${pid}`, { token: owner }), '[+] the owner delists it', 200);
  truthy('[+] the shop no longer lists it', !listed(pid), 'still listed');
  expect(scan(), '[-] the till no longer finds it', 404, 'VARIANT_NOT_FOUND');
  truthy('[+] ...and it has left the low-stock report for good', poll(30, () => !inLowStock(variant.id)) >= 0, 'still low');
  expect(move(pid, 'reinstate'), '[-] a delisted line is not reinstated', 409, 'PRODUCT_LIFECYCLE_INVALID');
  expect(move(pid, 'launch'), '[-] nor launched', 409, 'PRODUCT_LIFECYCLE_INVALID');

  completed.add(1);
}
