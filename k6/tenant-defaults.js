// Tenant defaults (SJ-D53), through the gateway: a business that trades in yen gets yen, and one
// that trades in dinars gets dinars, wherever a request leaves the currency or country out — a
// price list, a customer's VAT status, a supplier, store credit, a Z-report, a gift card, a till
// sale. Nothing falls back to pounds any more. The refusals and the abuse around each: a currency
// that contradicts the tenant's, malformed codes, and twenty defaulted writes at once.
//
//   k6/run.sh tenant-defaults
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import {
  ALL_CHECKS_PASS,
  BASE,
  call,
  data,
  errorCode,
  expect,
  must,
  onboardTenant,
  priceVariants,
  receive,
  sellableVariant,
  staffUser,
  truthy,
} from './lib/storeql.js';

// Added on the last line only, so a flow that stopped part-way fails instead of passing.
const completed = new Counter('flow_completed');
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
  batch: 20,
  batchPerHost: 20,
  setupTimeout: '4m',
};

export function setup() {
  const yen = onboardTenant('defaults-yen', { country: 'JP', currency: 'JPY' });
  const pound = onboardTenant('defaults-pound', { country: 'GB', currency: 'GBP' });
  const dinar = onboardTenant('defaults-dinar', { country: 'KW', currency: 'KWD' });
  const { variantId } = sellableVariant(yen, 'Yen widget');
  priceVariants(yen, [variantId], '500');
  must(receive(yen, yen.stores[0].id, variantId, 20, '300'), [200, 201], 'receive stock');
  // SJ-D56: a French business that has not set its VAT rate, with a product priced and in stock.
  const bare = onboardTenant('defaults-no-vat', { country: 'FR', currency: 'EUR' });
  const bareVariant = sellableVariant(bare, 'Bare widget').variantId;
  must(receive(bare, bare.stores[0].id, bareVariant, 20, '3.00'), [200, 201], 'receive bare stock');
  const bareCashier = staffUser(bare, 'CASHIER', [bare.stores[0].id]);
  return { yen, pound, dinar, variantId, bare, bareVariant, bareCashier };
}

export default function ({ yen, pound, dinar, variantId, bare, bareVariant, bareCashier }) {
  const today = new Date().toISOString().slice(0, 10);
  const stamp = Date.now();
  const store = yen.stores[0];
  const from = new Date(Date.now() - 86400000).toISOString();
  // Letters only, never digits: `Math.random()` writes a seventeen-digit run, and a body carrying a
  // digit run the gateway reads as a Luhn-valid card number is refused CARD_DATA_NOT_ACCEPTED — which
  // failed this suite's malformed-currency check about one run in twenty, on the run's digits alone.
  const slug = () => Math.random().toString(36).replace(/[^a-z]/g, '').slice(0, 8) || 'kx';

  const priceList = (tenant, extra = {}) =>
    call('POST', '/api/pricing-svc/admin/price-lists', { token: tenant.owner.token, body: { name: `defaults ${stamp} ${slug()}`, channel: 'ONLINE', effectiveFrom: from, ...extra } });

  // ── a price list: the tenant's own currency ──────────────────────────────────
  const yenList = priceList(yen);
  expect(yenList, '[+] a price list with no currency is created', 201);
  truthy('[+] ...in the yen tenant\'s yen', data(yenList).currency === 'JPY', data(yenList));
  truthy('[+] a pound tenant\'s is in pounds: per tenant, not one default', data(priceList(pound)).currency === 'GBP');
  truthy('[+] a dinar tenant\'s is in dinars', data(priceList(dinar)).currency === 'KWD');

  // ── a customer's VAT status: the tenant's own country ────────────────────────
  const vat = call('POST', '/api/pricing-svc/customer-vat-status', { token: yen.owner.token, body: { customerId: '01a090ae-611e-70f0-8a00-00000000e001', vatRegistered: false, reverseChargeEligible: false } });
  expect(vat, '[+] a VAT status with no country is recorded', [200, 201]);
  truthy('[+] ...in the yen tenant\'s country', data(vat).countryCode === 'JP', data(vat));

  // ── a supplier: the tenant's own currency and country ────────────────────────
  const supplier = call('POST', '/api/purchase-svc/suppliers', { token: yen.owner.token, body: { name: `Osaka Trading ${stamp}` } });
  expect(supplier, '[+] a supplier with no currency or country is created', 201);
  truthy('[+] ...in yen, in Japan', data(supplier).currency === 'JPY' && data(supplier).countryCode === 'JP', data(supplier));

  // ── store credit: the tenant's own currency ──────────────────────────────────
  const customer = must(call('POST', '/api/customer-svc/customers', { token: yen.owner.token, body: { email: `yuki-${stamp}@example.com`, firstName: 'Yuki', lastName: 'Sato' } }), 201, 'customer');
  const credit = call('POST', `/api/customer-svc/customers/${customer.id}/store-credit/issue`, { token: yen.owner.token, idem: true, body: { amount: 500, reason: 'goodwill' } });
  expect(credit, '[+] store credit with no currency is issued', 200);
  truthy('[+] ...in yen', data(credit).currency === 'JPY', data(credit));
  truthy('[+] reading the balance with no currency reads the yen account', data(call('GET', `/api/customer-svc/customers/${customer.id}/store-credit`, { token: yen.owner.token })).currency === 'JPY');

  // ── a Z-report: the tenant's own currency ────────────────────────────────────
  const z = call('POST', '/api/payment-svc/admin/cash/z-report', { token: yen.owner.token, body: { storeId: store.id, businessDate: today, countedCash: 0 } });
  expect(z, '[+] a Z-report with no currency is generated', [200, 201]);
  truthy('[+] ...in yen', data(z).currency === 'JPY', data(z));

  // ── a gift card and a till sale: the tenant's own currency, and no other ─────
  const card = call('POST', '/api/order-svc/gift-cards', { token: yen.owner.token, idem: true, body: { storeId: store.id, amount: 1000, paidBy: 'CASH' } });
  expect(card, '[+] a gift card with no currency is issued', [200, 201]);
  truthy('[+] ...in yen', data(card).currency === 'JPY', data(card));
  expect(call('POST', '/api/order-svc/gift-cards', { token: yen.owner.token, idem: true, body: { storeId: store.id, amount: 1000, currency: 'GBP', paidBy: 'CASH' } }), '[-] a gift card in pounds for a yen tenant is refused', 400, 'ORDER_CURRENCY_MISMATCH');
  const sale = call('POST', '/api/order-svc/orders', { token: yen.owner.token, idem: true, body: { storeId: store.id, channel: 'POS', items: [{ variantId, qty: 1, unitPrice: 500 }] } });
  expect(sale, '[+] a till sale with no currency is placed', 201);
  truthy('[+] ...in yen', data(sale).currency === 'JPY', data(sale));
  expect(call('POST', '/api/order-svc/orders', { token: yen.owner.token, idem: true, body: { storeId: store.id, channel: 'POS', currency: 'USD', items: [{ variantId, qty: 1, unitPrice: 500 }] } }), '[-] a sale in dollars for a yen tenant is refused', 400, 'ORDER_CURRENCY_MISMATCH');

  // ── abuse: a blank code is the tenant's; a malformed one is refused, never stored ──
  const blanks = ['', ' '].map((c) => priceList(yen, { currency: c }));
  truthy('[abuse] a blank currency is the tenant\'s yen, not an empty code', blanks.every((r) => r.status === 201 && data(r).currency === 'JPY'), blanks.map((r) => `${r.status} ${data(r).currency}`));
  const bad = ['12', 'POUNDS', "';-", '<b>', 'GB P', 'ZZZ'];
  const badLists = bad.map((c) => priceList(yen, { currency: c }));
  truthy('[abuse] malformed currency codes are refused by name, never stored and never a server error', badLists.every((r) => r.status === 400 && errorCode(r) === 'CURRENCY_INVALID'), badLists.map((r) => `${r.status} ${errorCode(r)}`));
  const badCountry = call('POST', '/api/purchase-svc/suppliers', { token: yen.owner.token, body: { name: `Bad Country ${stamp}`, countryCode: 'UK' } });
  expect(badCountry, '[abuse] a country that is not an ISO code is refused', 400, 'COUNTRY_INVALID');
  expect(call('POST', `/api/customer-svc/customers/${customer.id}/store-credit/issue`, { token: yen.owner.token, idem: true, body: { amount: 5, reason: 'x', currency: 'POUNDS' } }), '[abuse] store credit in a currency that is not one is refused', 400, 'CURRENCY_INVALID');

  // ── abuse: twenty defaulted price lists at once all agree ───────────────────
  const params = { headers: { Authorization: `Bearer ${yen.owner.token}`, 'Content-Type': 'application/json' }, tags: { name: 'POST /admin/price-lists' } };
  const burst = http.batch(Array.from({ length: 20 }, (_, i) => ['POST', `${BASE}/api/pricing-svc/admin/price-lists`, JSON.stringify({ name: `burst ${stamp} ${i}`, channel: 'POS', effectiveFrom: from }), params]));
  truthy('[abuse] twenty price lists at once are all created in yen', burst.every((r) => r.status === 201 && data(r).currency === 'JPY'), burst.map((r) => `${r.status} ${data(r).currency}`));

  // ── SJ-D56: no VAT rate is ever assumed ─────────────────────────────────────
  const bareOwner = bare.owner.token;
  const bareStore = bare.stores[0].id;
  const bareList = must(call('POST', '/api/pricing-svc/admin/price-lists', { token: bareOwner, body: { name: `no vat ${stamp}`, effectiveFrom: from } }), 201, 'bare price list');
  must(call('POST', `/api/pricing-svc/admin/price-lists/${bareList.id}/items`, { token: bareOwner, body: { variantId: bareVariant, price: 10, minQty: 1 } }), [200, 201], 'bare price');
  const shopperQuote = () => call('POST', '/api/pricing-svc/prices/resolve', { storefront: bare.tenantId, body: { variantId: bareVariant, channel: 'ONLINE', qty: 1 } });
  const tillSale = () => call('POST', '/api/order-svc/orders', { token: bareOwner, idem: true, body: { storeId: bareStore, channel: 'POS', items: [{ variantId: bareVariant, qty: 1 }] } });
  expect(shopperQuote(), '[-] no standard VAT rate set: a shopper is quoted nothing, and told why', 409, 'PRICING_VAT_RATE_NOT_CONFIGURED');
  expect(call('POST', '/api/pricing-svc/prices/quote', { token: bareOwner, body: { channel: 'POS', lines: [{ variantId: bareVariant, qty: 1 }] } }), '[-] ...nor is a basket', 409, 'PRICING_VAT_RATE_NOT_CONFIGURED');
  expect(tillSale(), '[-] ...and a till sale is refused with that reason, not a 503', 409, 'PRICING_VAT_RATE_NOT_CONFIGURED');
  const vatBody = (rate) => ({ code: 'T1', name: 'Taux normal', rate, exempt: false, effectiveFrom: '2020-01-01T00:00:00Z' });
  expect(call('POST', '/api/pricing-svc/vat-rates', { token: bareCashier.token, body: vatBody(0.2) }), '[-] a cashier cannot set what sales are taxed at', 403);
  expect(call('POST', '/api/pricing-svc/vat-rates', { token: bareOwner, body: vatBody(20) }), '[-] a percentage sent where the fraction belongs is refused', 400, 'VALIDATION_FAILED');
  const shoppers = http.batch(Array.from({ length: 20 }, () => ['POST', `${BASE}/api/pricing-svc/prices/resolve`, JSON.stringify({ variantId: bareVariant, channel: 'ONLINE', qty: 1 }), { headers: { 'Content-Type': 'application/json', 'X-Storefront-Tenant': bare.tenantId }, tags: { name: 'POST resolve no vat' } }]));
  truthy('[abuse] twenty shoppers at once are all refused by name, none with a server error', shoppers.every((r) => r.status === 409 && errorCode(r) === 'PRICING_VAT_RATE_NOT_CONFIGURED'), shoppers.map((r) => `${r.status} ${errorCode(r)}`));
  const ownerHeaders = { headers: { Authorization: `Bearer ${bareOwner}`, 'Content-Type': 'application/json' }, tags: { name: 'POST vat-rates rush' } };
  const setting = http.batch(Array.from({ length: 20 }, () => ['POST', `${BASE}/api/pricing-svc/vat-rates`, JSON.stringify(vatBody(0.2)), ownerHeaders]));
  truthy('[abuse] twenty owners setting the rate at once make exactly one', setting.filter((r) => r.status === 201).length === 1 && setting.every((r) => r.status === 201 || r.status === 409), setting.map((r) => r.status));
  // SJ-D57: a body that is not the JSON a request takes is refused by name on every service, never a 500.
  const ownerJson = { headers: { Authorization: `Bearer ${bareOwner}`, 'Content-Type': 'application/json' } };
  const garbled = [
    ['pricing-svc', `${BASE}/api/pricing-svc/vat-rates`, JSON.stringify({ ...vatBody(0.2), rate: 'twenty' })],
    ['pricing-svc', `${BASE}/api/pricing-svc/admin/price-lists`, '{not json'],
    ['order-svc', `${BASE}/api/order-svc/orders`, JSON.stringify({ storeId: bareStore, channel: 'POS', items: 'one widget' })],
    ['product-svc', `${BASE}/api/product-svc/admin/products`, JSON.stringify({ name: { first: 'Bare' } })],
    ['customer-svc', `${BASE}/api/customer-svc/customers`, '["not", "an", "object"]'],
    ['purchase-svc', `${BASE}/api/purchase-svc/suppliers`, '{"name": "x", '],
  ];
  const refusedBodies = garbled.map(([svc, url, body]) => [svc, http.post(url, body, { ...ownerJson, tags: { name: `POST garbled ${svc}` } })]);
  truthy('[abuse] a garbled or mistyped body is 400 REQUEST_BODY_INVALID on every service, not a 500', refusedBodies.every(([, r]) => r.status === 400 && errorCode(r) === 'REQUEST_BODY_INVALID'), refusedBodies.map(([svc, r]) => `${svc} ${r.status} ${errorCode(r)}`));

  const priced = shopperQuote();
  expect(priced, '[+] once the owner has set 20%, the shopper is quoted', 200);
  truthy('[+] ...at 20%, the business\'s own rate', Math.abs(data(priced).vatRate - 0.2) < 1e-9 && Math.abs(data(priced).totalWithVat - 12) < 0.006, data(priced));
  expect(tillSale(), '[+] ...and the till sale goes through', 201);

  completed.add(1);
}
