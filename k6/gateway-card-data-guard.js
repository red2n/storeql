// The PCI DSS scope as a control: the gateway refuses any request carrying a payment card number.
//
// StoreQL is SAQ-A because no card detail reaches any StoreQL service — the customer completes
// against the payment provider. That is true of the checkout and false of every free-text field
// until something enforces it. The gateway scans every body and query string for a card number
// (15/16/19 digits, Luhn-valid, an issuer range that is issued at that length) and answers
// 400 CARD_DATA_NOT_ACCEPTED without forwarding, echoing or logging it. Retail data that looks
// like a card — EAN-13 barcodes, GTIN-14s, IMEIs, phone numbers, all-digit ids — must keep
// flowing, or the guard is switched off within a week and protects nothing after that. And a
// real sale must still go through with the guard in place.
//
//   k6/run.sh gateway-card-data-guard
import { ALL_CHECKS_PASS, call, data, expect, must, onboardTenant, priceVariants, receive, sellableVariant, truthy } from './lib/storeql.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS };

const VISA = '4111 1111 1111 1111';
const AMEX = '378282246310005';
const MC = '5555-5555-5555-4444';

export function setup() {
  const tenant = onboardTenant('pci', { country: 'GB', currency: 'GBP' });
  const store = tenant.stores[0];
  const { productId, variantId } = sellableVariant(tenant, 'PCI Widget');
  priceVariants(tenant, [variantId], '9.00');
  must(receive(tenant, store.id, variantId, 20), [200, 201], 'receive stock');
  return { tenant, store, productId, variantId };
}

function noDigitsOf(res, pan) {
  const digits = pan.replace(/\D/g, '');
  return !res.body.includes(digits) && !res.body.includes(digits.slice(0, 8)) && !res.body.includes(digits.slice(-8));
}

export default function ({ tenant, store, productId, variantId }) {
  const owner = tenant.owner.token;
  const products = '/api/product-svc/admin/products';
  const product = (body) => call('POST', products, { token: owner, body: { name: `PCI ${Date.now()}`, ...body } });

  // ── refused, every way a card number actually arrives ─────────────────────
  const inNote = product({ description: `customer paid on ${VISA}, ring back` });
  expect(inNote, 'a card number in a description is refused at the gateway', 400, 'CARD_DATA_NOT_ACCEPTED');
  truthy('and the refusal does not repeat the number', noDigitsOf(inNote, VISA), inNote.body);
  expect(product({ description: `amex ${AMEX}` }), 'an Amex, 15 digits, plain', 400, 'CARD_DATA_NOT_ACCEPTED');
  expect(product({ description: `mc ${MC}` }), 'a Mastercard with hyphens', 400, 'CARD_DATA_NOT_ACCEPTED');
  expect(
    call('POST', `${products}/${productId}/variants`, { token: owner, body: { sku: `PCI-${Date.now()}`, barcode: '4111111111111111', unit: 'PCS' } }),
    'a card number typed into a barcode field is still a card number',
    400,
    'CARD_DATA_NOT_ACCEPTED'
  );
  expect(
    call('PUT', `${products}/${productId}`, { token: owner, body: { name: 'PCI Widget', description: `nested { "card": "${VISA}" }` } }),
    'a PUT with one nested in text',
    400,
    'CARD_DATA_NOT_ACCEPTED'
  );
  expect(call('GET', `${products}?search=${encodeURIComponent(VISA)}`, { token: owner }), 'a card number in a query string is refused — it would be in the access log', 400, 'CARD_DATA_NOT_ACCEPTED');
  expect(
    call('POST', '/api/order-svc/orders', { token: owner, idem: true, body: { storeId: store.id, channel: 'POS', fulfilmentType: 'INSTORE', currency: 'GBP', notes: `pay with ${VISA}`, items: [{ variantId, qty: 1, unitPrice: '9.00' }] } }),
    'an order note carrying a card number places no order',
    400,
    'CARD_DATA_NOT_ACCEPTED'
  );
  expect(call('POST', '/api/iam-svc/auth/login', { body: { email: 'x@example.com', password: VISA } }), 'even an unauthenticated request is refused, not forwarded', 400, 'CARD_DATA_NOT_ACCEPTED');

  // ── the retail data that must keep flowing ────────────────────────────────
  const uk = call('POST', `${products}/${productId}/variants`, { token: owner, body: { sku: `PCI-UK-${Date.now()}`, barcode: '5012345678900', unit: 'PCS' } });
  expect(uk, 'a UK EAN-13 barcode is not a card', 201);
  const de = call('POST', `${products}/${productId}/variants`, { token: owner, body: { sku: `PCI-DE-${Date.now()}`, barcode: '4006381333931', unit: 'PCS' } });
  expect(de, 'a German EAN-13 that happens to pass Luhn is not a card', 201);
  expect(product({ description: 'IMEI 353918050478917, serial 36123456789012, call +44 7911 123456, ref ORD-2026-000042' }), 'an IMEI, a GTIN-14, a phone number and an order ref are not cards', 201);
  expect(product({ description: 'looks like a card but fails Luhn: 4111111111111112' }), 'sixteen digits that fail Luhn are not a card', 201);
  expect(product({ description: `id 01234567-8901-7234-8567-890123456789 and stamp ${Date.now()}` }), 'an all-digit id and a timestamp are not cards', 201);
  // SJ-D67: 5100 7069 8187 0918 is Luhn-valid in Mastercard's range and 4236 7684 9322 3069 in Visa's;
  // inside a UUID's shape each is an identifier, whatever its digits add up to.
  expect(product({ description: 'ids 019993e0-5100-7069-8187-0918e7284e47 and 01998bcd-4236-7684-9322-3069a2373271' }), 'two ids whose digit groups line up as card numbers are ids (SJ-D67)', 201);

  // ── the real flow, with the guard in place ────────────────────────────────
  const sale = call('POST', '/api/order-svc/orders', { token: owner, idem: true, body: { storeId: store.id, channel: 'POS', fulfilmentType: 'INSTORE', currency: 'GBP', items: [{ variantId, qty: 1, unitPrice: '9.00' }] } });
  expect(sale, 'a till sale is placed as before', 201);
  const tender = call('POST', '/api/payment-svc/payments', { token: owner, idem: true, body: { orderId: data(sale).id, amount: data(sale).total, method: 'CASH', storeId: store.id, currency: 'GBP' } });
  expect(tender, 'and paid for — the tender carries no card detail, which is the whole point', [200, 201]);

  // ── abuse: hammering the guard costs nothing and blocks nothing else ──────
  const seen = {};
  for (let i = 0; i < 25; i++) {
    const r = product({ description: `try ${i} ${VISA}` });
    seen[r.status] = (seen[r.status] || 0) + 1;
  }
  truthy('twenty-five card numbers in a row, twenty-five refusals', seen[400] === 25, seen);
  expect(product({ description: 'the next honest request is served' }), 'the guard is stateless: nothing was locked', 201);
}
