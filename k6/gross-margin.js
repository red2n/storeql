// Gross margin and GMROI (19.7), through the gateway: a till sale's revenue, net of VAT, reaches
// inventory-svc with the stock it drew and is set against the cost of the batches it drew down —
// the same revenue purchase-svc posted to sales for that order; a return takes back its share of
// revenue and cost; a voided sale earns nothing — and the refusals and abuse around it: the wrong
// roles, a rival tenant, a forged tenant header, bad windows and groupings, a flood of reads.
//
//   k6/run.sh gross-margin
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import {
  ALL_CHECKS_PASS,
  BASE,
  call,
  claims,
  data,
  expect,
  must,
  poll,
  sellingTenant,
  truthy,
} from './lib/storeql.js';

const completed = new Counter('flow_completed');
export const options = {
  vus: 1,
  iterations: 1,
  batch: 20,
  batchPerHost: 20,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
  setupTimeout: '4m',
};

const REPORT = '/api/inventory-svc/admin/inventory/reports/gross-margin';
const TURN = '/api/inventory-svc/admin/inventory/reports/stock-turn';
const LEDGER = '/api/purchase-svc/nominal-ledger';

export function setup() {
  return sellingTenant('gross-margin', { price: '12.00', costPrice: '6.00' });
}

export default function ({ tenant, rival, store, variantId, storekeeper, cashier }) {
  const owner = tenant.owner.token;
  const num = (v) => Number(v || 0);
  const cents = (v) => Math.round(v * 100) / 100;
  const near = (a, b, tolerance) => Math.abs(num(a) - num(b)) <= tolerance;
  const from = new Date(Date.now() - 86400000).toISOString();
  const to = new Date(Date.now() + 86400000).toISOString();
  const window = `from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`;
  const report = (token, query = '&groupBy=VARIANT') => call('GET', `${REPORT}?${window}${query}`, { token });
  const rowOf = (token = owner, query) => ((data(report(token, query)) || {}).rows || []).find((r) => r.groupKey === variantId);
  const paidSale = (qty) => {
    const sale = must(call('POST', '/api/order-svc/orders', { token: owner, idem: true, body: { storeId: store.id, channel: 'POS', fulfilmentType: 'INSTORE', items: [{ variantId, qty }] } }), 201, 'till sale');
    must(call('POST', '/api/payment-svc/payments', { token: owner, idem: true, body: { orderId: sale.id, amount: sale.total, method: 'CASH', storeId: store.id } }), [200, 201], 'pay in full');
    return sale;
  };
  let row;
  const waitFor = (revenue) => poll(90, () => {
    row = rowOf();
    return row && cents(num(row.revenue)) === cents(revenue);
  }) >= 0;

  // ── a sale earns its revenue net of VAT, against the cost of what it drew ────
  const sale = paidSale(3);
  const earned = cents(num(sale.total) - num(sale.taxAmount));
  truthy('[+] the sale reaches the report with its net revenue', waitFor(earned), { row, earned, sale });
  truthy('[+] ...against the cost of the three units it drew at 6.00', cents(num(row.cogs)) === 18, row);
  truthy('[+] ...margin is revenue less cost', cents(num(row.grossMargin)) === cents(earned - 18), row);
  truthy('[+] ...margin % is of revenue', near(row.marginPercent, ((earned - 18) * 100) / earned, 0.051), row);
  truthy('[+] ...nothing sold went unpriced or uncosted', num(row.unpricedSaleQty) === 0 && num(row.uncostedSaleQty) === 0, row);
  const turn = ((data(call('GET', `${TURN}?${window}&groupBy=VARIANT`, { token: owner })) || {}).rows || []).find((r) => r.groupKey === variantId);
  truthy('[+] GMROI is the margin over the average holding stock turn reports', turn && num(row.averageValue) === num(turn.averageValue) && near(row.gmroi, num(row.grossMargin) / num(row.averageValue), 0.006), { row, turn });
  let sales = [];
  poll(60, () => {
    sales = (data(call('GET', `${LEDGER}?limit=100`, { token: owner })) || []).filter((l) => l.sourceRef === sale.id && l.nominalCode === '4010');
    return sales.length > 0;
  });
  const posted = cents(sales.reduce((t, l) => t + num(l.credit) - num(l.debit), 0));
  truthy('[+] the revenue is what the ledger posted to sales for the order', posted === earned, { posted, earned, sales });
  truthy('[+] by store, the sale rolls up to its store', ((data(report(owner, '&groupBy=STORE')) || {}).rows || []).some((r) => r.groupKey === store.id && cents(num(r.revenue)) === earned));

  // ── a return takes back its share of revenue and cost ────────────────────────
  expect(call('POST', `/api/order-svc/orders/${sale.id}/returns`, { token: owner, idem: true, body: { reason: 'Too big', items: [{ variantId, qty: 1, condition: 'SEALED' }] } }), '[+] one of the three is returned', [200, 201]);
  const afterReturn = cents(earned - cents(earned / 3));
  truthy('[+] the return takes back a third of the revenue', waitFor(afterReturn), { row, afterReturn });
  truthy('[+] ...and the cost of the unit that came back', cents(num(row.cogs)) === 12, row);

  // ── a voided sale earns nothing ──────────────────────────────────────────────
  const voided = paidSale(2);
  const voidedEarned = cents(num(voided.total) - num(voided.taxAmount));
  truthy('[+] a second sale adds its revenue', waitFor(cents(afterReturn + voidedEarned)), row);
  expect(call('POST', `/api/order-svc/orders/${voided.id}/void`, { token: owner, idem: true, body: { reason: 'rang up twice' } }), '[+] the second sale is voided', 200);
  truthy('[+] ...and its revenue leaves the report', waitFor(afterReturn), row);
  truthy('[+] ...with its cost', cents(num(row.cogs)) === 12, row);

  // ── refusals ─────────────────────────────────────────────────────────────────
  expect(report(storekeeper.token), '[-] a storekeeper cannot read margins', 403);
  expect(report(cashier.token), '[-] nor a cashier', 403);
  expect(call('GET', `${REPORT}?groupBy=VARIANT`, { token: owner }), '[-] a report with no window is refused', 400);
  expect(call('GET', `${REPORT}?from=${encodeURIComponent(to)}&to=${encodeURIComponent(from)}`, { token: owner }), '[-] a window that ends before it starts', 400, 'INVENTORY_INVALID_PERIOD');
  expect(call('GET', `${REPORT}?from=2026-01-01&to=2026-02-01`, { token: owner }), '[-] a bare date is not an instant', 400);
  expect(report(owner, '&groupBy=REASON'), '[-] a grouping that is not one', 400, 'INVENTORY_INVALID_GROUPING');
  expect(report(owner, '&storeId=not-a-store'), '[-] a store that is not an id', 400);
  truthy('[-] a rival tenant sees none of these sales', !rowOf(rival.owner.token));
  const ownerTenant = claims(owner).tenant;
  truthy('[-] (the owner\'s tenant id is known, so the forged header below is real)', !!ownerTenant, claims(owner));
  const forged = call('GET', `${REPORT}?${window}&groupBy=VARIANT`, { token: rival.owner.token, headers: { 'X-Tenant-Id': ownerTenant } });
  truthy('[-] a forged tenant header does not reach another tenant', forged.status !== 200 || !((data(forged) || {}).rows || []).some((r) => r.groupKey === variantId), forged.status);

  // ── abuse ────────────────────────────────────────────────────────────────────
  expect(report(owner, `&groupBy=${encodeURIComponent("STORE';DROP TABLE sale_revenue;--")}`), '[abuse] SQL in the grouping is a refused grouping', 400, 'INVENTORY_INVALID_GROUPING');
  const huge = report(owner, '&groupBy=VARIANT&limit=1000000');
  truthy('[abuse] an absurd limit is clamped, not obeyed', huge.status === 200 && ((data(huge) || {}).rows || []).length <= 100, huge.status);
  const params = { headers: { Authorization: `Bearer ${owner}`, Accept: 'application/json' }, tags: { name: 'GET gross-margin burst' } };
  const burst = http.batch(Array.from({ length: 20 }, () => ['GET', `${BASE}${REPORT}?${window}&groupBy=VARIANT`, null, params]));
  const figures = burst.map((r) => (((data(r) || {}).rows || []).find((x) => x.groupKey === variantId) || {}).revenue);
  truthy('[abuse] twenty concurrent reads all answer, with the same figure', burst.every((r) => r.status === 200) && new Set(figures.map((f) => cents(num(f)))).size === 1 && cents(num(figures[0])) === afterReturn, { statuses: burst.map((r) => r.status), figures });
  truthy('[abuse] reading the report changed nothing', cents(num(rowOf().revenue)) === afterReturn);

  completed.add(1);
}
