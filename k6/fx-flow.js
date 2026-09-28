// Multi-currency pricing and FX (03.x) through the gateway: the business's exchange rates — a
// dated, append-only table of home units per one unit of another currency, set by management with
// a reason, read by staff (the read pricing-svc and purchase-svc make), refused by name when the
// currency, rate, day or reason cannot be kept; the currencies a shop can show prices in; a price
// and a basket shown in dollars at the business's rate beside the sterling that is charged; and a
// dollar purchase order whose spend authority is measured in sterling at that rate, the translated
// figure kept on the order. The ceiling itself is only enforced when PURCHASE_APPROVAL_LIMITS is
// set in .env, so the translation is checked on the order's record, not on a hold.
//
//   k6/run.sh fx-flow
import {
  ALL_CHECKS_PASS,
  call,
  data,
  expect,
  must,
  sellingTenant,
  truthy,
  uniq,
} from './lib/storeql.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS, setupTimeout: '4m' };

const RATES = '/api/tenant-svc/admin/tenant/fx-rates';
const PRICES = '/api/pricing-svc/prices';
const PURCHASE = '/api/purchase-svc';

export function setup() {
  return sellingTenant('fx', { price: '10.00', costPrice: '4.00' });
}

export default function ({ tenant, store, variantId, storekeeper, cashier }) {
  const owner = tenant.owner.token;
  const num = (v) => Number(v || 0);
  const setRate = (currency, body, token = owner) => call('PUT', `${RATES}/${currency}`, { token, body });

  // ── 1. the table: home currency only, then a rate with a reason, read by staff ─
  const empty = data(call('GET', RATES, { token: owner }));
  truthy('[+] a new business has its home currency and no rates', empty && empty.home === 'GBP' && Array.isArray(empty.rates) && empty.rates.length === 0, empty);
  const usd = must(setRate('usd', { rate: 0.8, reason: 'ECB reference, yesterday' }), 200, 'a dollar rate');
  truthy('[+] the rate is kept as set: USD, 0.80 GBP per dollar, from today, with its reason', usd.currency === 'USD' && num(usd.rate) === 0.8 && usd.reason === 'ECB reference, yesterday' && !!usd.effectiveFrom, usd);
  const staff = data(call('GET', RATES, { token: storekeeper.token }));
  truthy('[+] a storekeeper reads the sheet — the read the services make', staff && staff.rates.some((r) => r.currency === 'USD' && num(r.rate) === 0.8), staff);
  must(setRate('USD', { rate: 0.79, reason: 'corrected' }), 200, 'a second dollar rate');
  const history = data(call('GET', `${RATES}/USD/history`, { token: owner }));
  truthy('[+] the history keeps every rate, newest first', Array.isArray(history) && history.length === 2 && num(history[0].rate) === 0.79, history);
  truthy('[+] ...and the sheet shows the latest', num(data(call('GET', RATES, { token: owner })).rates.find((r) => r.currency === 'USD').rate) === 0.79, null);

  // ── 2. refused by name, and by role ────────────────────────────────────────
  expect(setRate('GBP', { rate: 1, reason: 'x' }), '[-] the home currency has no rate against itself', 400, 'FX_CURRENCY_INVALID');
  expect(setRate('XYZ', { rate: 1, reason: 'x' }), '[-] a code nobody knows', 400, 'FX_CURRENCY_INVALID');
  expect(setRate('USD', { rate: 0, reason: 'x' }), '[-] a rate of nothing', 400, 'FX_RATE_INVALID');
  expect(setRate('USD', { rate: 0.79, effectiveFrom: '1999-12-31', reason: 'x' }), '[-] a day before 2000', 400, 'FX_DATE_INVALID');
  expect(setRate('USD', { rate: 0.79, effectiveFrom: 'next week', reason: 'x' }), '[-] a day that is not a date', 400, 'FX_DATE_INVALID');
  expect(setRate('USD', { rate: 0.79 }), '[-] no reason', 400);
  expect(setRate('USD', { rate: 0.79, reason: 'x' }, storekeeper.token), '[-] a storekeeper cannot set a rate', 403);
  expect(setRate('USD', { rate: 0.79, reason: 'x' }, cashier.token), '[-] nor a cashier', 403);
  expect(call('GET', `${RATES}/USD/history`, { token: storekeeper.token }), '[-] the history is management\'s', 403);
  expect(call('GET', RATES), '[-] no token', 401);

  // ── 3. prices shown in dollars, charged in sterling ─────────────────────────
  const currencies = data(call('GET', `${PRICES}/currencies`, { token: owner }));
  truthy('[+] the shop can show prices in its own currency and the dollar', currencies && currencies.home === 'GBP' && JSON.stringify(currencies.currencies) === JSON.stringify(['GBP', 'USD']), currencies);
  const shown = data(call('POST', `${PRICES}/resolve`, { token: owner, body: { variantId, channel: 'ONLINE', qty: 1, displayCurrency: 'USD' } }));
  truthy('[+] the price is charged in sterling and shown in dollars at 0.79', shown && shown.currency === 'GBP' && shown.display && shown.display.currency === 'USD' && num(shown.display.rate) === 0.79 && Math.abs(num(shown.display.unitPrice) - Math.round((num(shown.unitPrice) / 0.79) * 100) / 100) < 0.011, shown);
  const basket = data(call('POST', `${PRICES}/quote`, { token: owner, body: { storeId: store.id, channel: 'ONLINE', lines: [{ variantId, qty: 2 }], displayCurrency: 'USD' } }));
  truthy('[+] a basket carries its totals in dollars beside the sterling', basket && basket.currency === 'GBP' && basket.display && basket.display.currency === 'USD' && Math.abs(num(basket.display.total) - Math.round((num(basket.total) / 0.79) * 100) / 100) < 0.011, basket);
  expect(call('POST', `${PRICES}/resolve`, { token: owner, body: { variantId, channel: 'ONLINE', qty: 1, displayCurrency: 'EUR' } }), '[-] a currency with no rate is refused by name', 400, 'FX_RATE_MISSING');
  expect(call('POST', `${PRICES}/resolve`, { token: owner, body: { variantId, channel: 'ONLINE', qty: 1, displayCurrency: 'POUNDS' } }), '[-] a code that is not a currency', 400, 'FX_CURRENCY_INVALID');

  // ── 4. a dollar purchase order measured in sterling ─────────────────────────
  const supplier = must(call('POST', `${PURCHASE}/suppliers`, { token: owner, body: { name: `Dollar Supplies ${uniq()}`, currency: 'USD' } }), 201, 'a dollar supplier');
  const po = must(call('POST', `${PURCHASE}/purchase-orders`, { token: owner, body: { supplierId: supplier.id, storeId: store.id } }), 201, 'a dollar order');
  must(call('POST', `${PURCHASE}/purchase-orders/${po.id}/lines`, { token: owner, body: { variantId, qty: 100, unitPrice: 4.0 } }), 201, 'four hundred dollars of it');
  const submitted = must(call('POST', `${PURCHASE}/purchase-orders/${po.id}/submit`, { token: owner, body: {} }), 200, 'submitted');
  truthy('[+] the order keeps the rate and the sterling figure its authority was measured against: $400 is £316 at 0.79', submitted.currency === 'USD' && num(submitted.fxRate) === 0.79 && num(submitted.totalNetHome) === 316 && submitted.homeCurrency === 'GBP', submitted);
}
