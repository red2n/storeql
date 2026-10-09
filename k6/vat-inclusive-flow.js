// Shelf prices, VAT inside, end to end through the gateway (intent/vat-inclusive-pricing.md).
//
// A UK shop's prices on the shelf are what the customer pays: 1.29 is 1.29, with the VAT inside it. This
// suite proves that across the services a sale touches: pricing-svc quotes the shelf price exactly and
// says how much VAT is inside, order-svc totals the sale to the penny and prints the VAT table on the
// receipt, payment-svc takes exactly that, and purchase-svc's ledger posts sales and VAT output that add
// back to what was paid -- and a refund takes back no more than it was.
//
//   k6/run.sh vat-inclusive-flow
import { Counter } from 'k6/metrics';
import {
  ALL_CHECKS_PASS, call, data, expect, must, onboardTenant, poll, receive, sellableVariant, staffUser, truthy,
} from './lib/storeql.js';

const completed = new Counter('flow_completed');
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
  setupTimeout: '4m',
};

const LEDGER = '/api/purchase-svc/nominal-ledger';
const SHELF = [
  { name: 'Hovis loaf', price: '1.29', code: 'T1', vat: '0.22', net: '1.07' }, // 1.29 x 20/120 = 0.215 -> 0.22
  { name: 'Strawberry jam', price: '1.99', code: 'T5', vat: '0.09', net: '1.90' }, // 1.99 x 5/105 = 0.0948 -> 0.09
  { name: 'Children\'s book', price: '2.49', code: 'T0', vat: '0.00', net: '2.49' },
];

export function setup() {
  const tenant = onboardTenant('vat-incl');
  const rival = onboardTenant('vat-incl-rival');
  const owner = tenant.owner.token;
  const store = tenant.stores[0];
  for (const [code, name, rate] of [['T1', 'Standard', 0.2], ['T5', 'Reduced', 0.05], ['T0', 'Zero', 0]]) {
    must(call('POST', '/api/pricing-svc/vat-rates', { token: owner, body: { code, name, rate, exempt: false, effectiveFrom: '2020-01-01T00:00:00Z' } }), 201, `VAT ${code}`);
  }
  const list = must(call('POST', '/api/pricing-svc/admin/price-lists', {
    token: owner,
    body: { name: 'Shelf prices', currency: 'GBP', taxMode: 'INCLUSIVE', effectiveFrom: new Date(Date.now() - 86400000).toISOString() },
  }), 201, 'shelf price list');
  const items = SHELF.map((s) => ({ ...s, ...sellableVariant(tenant, s.name) }));
  for (const i of items) must(receive(tenant, store.id, i.variantId, 20, '0.50'), [200, 201], 'receive');
  const cashier = staffUser(tenant, 'CASHIER', [store.id]);
  return { tenant, rival, store, items, list, cashier };
}

export default function ({ tenant, rival, store, items, list, cashier }) {
  const owner = tenant.owner.token;
  const num = (v) => Number(v || 0);
  const round = (v) => Math.round(v * 100) / 100;
  const READY = '/api/pricing-svc/admin/pricing/vat-readiness';

  // ── nothing is priced until each item has a VAT category ───────────────────────────────────
  const priceBody = { items: items.map((i) => ({ variantId: i.variantId, price: i.price, minQty: 1 })) };
  const pricesUrl = `/api/pricing-svc/admin/price-lists/${list.id}/items/batch`;
  const early = call('POST', pricesUrl, { token: owner, body: priceBody });
  truthy('[-] a shelf price cannot be set for an item with no VAT category', early.status >= 400 || (data(early).errors || []).length === 3, early.body);
  const quoteBody = { storeId: store.id, lines: items.map((i) => ({ variantId: i.variantId, qty: 1 })) };
  expect(call('POST', '/api/pricing-svc/prices/quote', { token: owner, body: quoteBody }), '[-] so nothing is quoted', 404, 'PRICING_PRICE_NOT_FOUND');
  expect(call('GET', READY, { token: cashier.token }), '[-] a cashier cannot read the readiness check', 403);

  const assigned = data(call('POST', '/api/pricing-svc/product-vat-categories/batch', {
    token: owner,
    body: { items: items.map((i) => ({ variantId: i.variantId, vatCode: i.code })) },
  }));
  truthy('[+] the categories are set in one call', assigned.assigned === 3, assigned);
  must(call('POST', pricesUrl, { token: owner, body: priceBody }), [200, 201], 'shelf prices');
  const ready = data(call('GET', READY, { token: owner }));
  truthy('[+] the shop is ready: every priced item has its VAT category', ready.variantsWithoutCategory === 0 && ready.ready === true && ready.taxMode === 'INCLUSIVE', ready);

  // ── the quote: a shelf price is the price ──────────────────────────────────────────────────
  const quote = data(call('POST', '/api/pricing-svc/prices/quote', { token: owner, body: quoteBody }));
  truthy('[+] the quote says the prices include VAT', quote.taxInclusive === true, quote);
  const lines = quote.lines || [];
  truthy('[+] each line is exactly its shelf price', items.every((i, n) => num(lines[n].lineGross) === num(i.price)), lines);
  truthy('[+] with the VAT inside it, rounded once', items.every((i, n) => num(lines[n].vatAmount) === num(i.vat) && num(lines[n].netTotal) === num(i.net)), lines);
  truthy('[+] net and VAT add back to what is paid', lines.every((l) => round(num(l.netTotal) + num(l.vatAmount)) === num(l.lineGross)), lines);
  expect(call('POST', '/api/pricing-svc/prices/quote', { token: rival.owner.token, body: quoteBody }), '[-] another business cannot quote this shop\'s items', 404);

  // ── the till sale: the total is the sum of the shelf prices ─────────────────────────────────
  const sale = must(call('POST', '/api/order-svc/orders', {
    token: owner,
    idem: true,
    body: { storeId: store.id, channel: 'POS', fulfilmentType: 'INSTORE', items: items.map((i) => ({ variantId: i.variantId, qty: 1 })) },
  }), 201, 'till sale');
  const shelfTotal = round(items.reduce((t, i) => t + num(i.price), 0)); // 5.77
  truthy('[+] the sale totals the shelf prices to the penny', num(sale.total) === shelfTotal, sale);
  truthy('[+] and is marked as shelf-priced', sale.taxInclusive === true, sale);

  const receipt = data(call('GET', `/api/order-svc/orders/${sale.id}/receipt-document`, { token: owner }));
  truthy('[+] the receipt total is what was charged', num(receipt.total) === shelfTotal, receipt);
  const rows = receipt.vat || [];
  truthy('[+] the receipt prints a VAT table, a row per rate', rows.length === 3 && items.every((i) => rows.some((r) => r.vatCode === i.code && num(r.gross) === num(i.price) && num(r.vat) === num(i.vat) && num(r.net) === num(i.net))), rows);
  const vatTotal = round(items.reduce((t, i) => t + num(i.vat), 0)); // 0.31
  truthy('[+] the VAT total is the sum of the rows', num(receipt.vatTotal) === vatTotal, receipt);

  // ── payment: exactly the shelf total, and no more ───────────────────────────────────────────
  const pay = (amount) => call('POST', '/api/payment-svc/payments', { token: owner, idem: true, body: { orderId: sale.id, amount, method: 'CASH', storeId: store.id } });
  const tender = must(pay(shelfTotal.toFixed(2)), [200, 201], 'cash tender');
  truthy('[+] the cash tender is the total', num(tender.amount) === shelfTotal, tender);

  // ── the ledger adds back to what was paid ───────────────────────────────────────────────────
  const linesFor = () => {
    const all = data(call('GET', `${LEDGER}?limit=100`, { token: owner }));
    return (Array.isArray(all) ? all : []).filter((l) => l.sourceRef === sale.id);
  };
  const net = (ls, code) => round(ls.filter((l) => l.nominalCode === code).reduce((t, l) => t + num(l.debit) - num(l.credit), 0));
  let posted = [];
  truthy('[+] the sale reaches the ledger', poll(60, () => {
    posted = linesFor();
    return posted.filter((l) => l.sourceType === 'SALE').length >= 2;
  }) >= 0, posted.map((l) => `${l.sourceType} ${l.nominalCode} ${l.debit}/${l.credit}`));
  truthy('[+] VAT output is the VAT inside the shelf prices', round(-net(posted, '2200')) === vatTotal, net(posted, '2200'));
  truthy('[+] sales are the rest', round(-net(posted, '4010')) === round(shelfTotal - vatTotal), net(posted, '4010'));
  truthy('[+] sales plus VAT output is exactly what was paid', round(-net(posted, '4010') - net(posted, '2200')) === shelfTotal, posted);

  // ── a return takes back exactly what was paid, in the ledger and in the VAT return ─────────────
  const given = call('POST', `/api/order-svc/orders/${sale.id}/returns`, {
    token: owner, idem: true,
    body: { reason: 'returned loaf', items: [{ variantId: items[0].variantId, qty: 1, condition: 'SEALED' }] },
  });
  expect(given, '[+] a loaf is returned at its shelf price', [200, 201]);
  truthy('[+] and 1.29 comes back, not a figure worked out again', num(data(given).refundAmount) === 1.29, data(given));
  let refund = [];
  truthy('[+] the refund reaches the ledger', poll(60, () => {
    refund = linesFor().filter((l) => l.sourceType === 'SALE_REFUND');
    return refund.length >= 2;
  }) >= 0, refund);
  truthy('[+] sales and VAT output come down by exactly the refund', round(net(refund, '4010') + net(refund, '2200')) === 1.29, refund);
  expect(call('POST', `/api/payment-svc/payments/by-order/${sale.id}/refunds`, {
    token: owner, idem: true, body: { paymentId: tender.id, amount: '9.99', method: 'CASH', reason: 'too much' },
  }), '[-] more than was paid cannot be refunded', [400, 409, 422]);
  truthy('[+] the trial balance still balances', data(call('GET', `${LEDGER}/trial-balance`, { token: owner })).balanced === true);

  // The VAT return reads the sale and the return as the ledger does: 0.31 less 0.22 of VAT on 5.46 less 1.07 net.
  const from = new Date(Date.now() - 86400000).toISOString();
  const to = new Date(Date.now() + 2 * 86400000).toISOString();
  let vr = {};
  truthy('[+] the VAT return shows the sale and the return', poll(60, () => {
    vr = data(call('GET', `/api/pricing-svc/vat-return?from=${from}&to=${to}`, { token: owner }));
    return num(vr.box1) === 0.09 && num(vr.box6) === 4.39;
  }) >= 0, vr);
  truthy('[+] box 1 is the ledger\u2019s VAT output, to the penny', round(-net(linesFor(), '2200')) === num(vr.box1), { box1: vr.box1, ledger: -net(linesFor(), '2200') });
  truthy('[+] box 6 is the ledger\u2019s sales', round(-net(linesFor(), '4010')) === num(vr.box6), { box6: vr.box6, ledger: -net(linesFor(), '4010') });
  expect(call('GET', `/api/pricing-svc/vat-return?from=${from}&to=${to}`, { token: cashier.token }), '[-] a cashier cannot read the VAT return', 403);
  const theirs = data(call('GET', `/api/pricing-svc/vat-return?from=${from}&to=${to}`, { token: rival.owner.token }));
  truthy('[-] another business\u2019s return has none of it', num(theirs.box1) === 0 && num(theirs.box6) === 0, theirs);

  // ── isolation ───────────────────────────────────────────────────────────────────────────────
  expect(call('GET', `/api/order-svc/orders/${sale.id}/receipt-document`, { token: rival.owner.token }), '[-] another business cannot read this receipt', 404);
  truthy('[-] nor see its ledger lines', ((data(call('GET', `${LEDGER}?limit=100`, { token: rival.owner.token })) || []).filter((l) => l.sourceRef === sale.id)).length === 0);
  truthy('[+] the price list is the one made', !!list.id, list);

  completed.add(1);
}
