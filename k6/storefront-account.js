// The shopper's own account on the storefront (12.10): the profile and the address book, keyed on
// the token's login and reachable through the gateway by nobody else — with the guest, the other
// shopper, the rival shop, the wrong input, a full book and a race for its last place tried
// beside the right calls, and a delivery order placed with a saved address to close the loop.
//
//   k6/run.sh storefront-account
import http from 'k6/http';
import {
  ALL_CHECKS_PASS,
  BASE,
  call,
  data,
  expect,
  must,
  onboardTenant,
  priceVariants,
  receive,
  register,
  sellableVariant,
  truthy,
} from './lib/storeql.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS, setupTimeout: '4m' };

export function setup() {
  const tenant = onboardTenant('account', { stores: 1 });
  const rival = onboardTenant('account-rival', { stores: 1 });
  const storeId = tenant.stores[0].id;
  tenant.variantId = sellableVariant(tenant, 'Account mug').variantId;
  priceVariants(tenant, [tenant.variantId], '10.00');
  must(receive(tenant, storeId, tenant.variantId, 50), 201, 'receive stock');
  return { tenant, rival, shopper: register('account-shopper'), other: register('account-other') };
}

export default function ({ tenant, rival, shopper, other }) {
  const shop = { storefront: tenant.tenantId };
  const me = (method, path, body, token = shopper.token) => call(method, `/api/customer-svc/customers/me${path}`, { token, body, ...shop });
  const addr = (line1, isDefault = false) => ({ type: 'HOME', line1, city: 'London', country: 'GB', pincode: 'EC1A 1BB', isDefault });
  // The envelope's data as a list, so a refused call reads as an empty book rather than a crash.
  const list = (res) => { const d = data(res); return Array.isArray(d) ? d : []; };

  // ── the record, the profile ──────────────────────────────────────────────────
  expect(me('GET', ''), '[-] before claiming: the shop holds no record', 404, 'CUSTOMER_NOT_FOUND');
  expect(me('PUT', '', { firstName: 'Chris', lastName: 'Carter' }), '[-] nor a profile to edit', 404, 'CUSTOMER_NOT_FOUND');
  expect(me('POST', ''), '[+] the shopper claims their record', 200);
  const profile = me('PUT', '', { firstName: '  Chris ', lastName: 'Carter', phone: '07700900123' });
  expect(profile, '[+] and edits their profile', 200);
  truthy('[+] trimmed, and the email stays the login\'s', data(profile).firstName === 'Chris' && data(profile).email === shopper.email.toLowerCase(), JSON.stringify(data(profile)));
  expect(me('PUT', '', { firstName: '', lastName: 'Carter' }), '[-] a blank name', 400, 'VALIDATION_FAILED');
  expect(me('PUT', '', { firstName: 'Chris', lastName: 'Carter', phone: '1'.repeat(33) }), '[-] a phone longer than 32 characters', 400, 'VALIDATION_FAILED');

  // ── the address book ─────────────────────────────────────────────────────────
  truthy('[+] an empty book', list(me('GET', '/addresses')).length === 0);
  const home = data(me('POST', '/addresses', addr('12 High Street')));
  truthy('[+] a first address', !!home.id, JSON.stringify(home));
  const work = data(me('POST', '/addresses', addr('1 Office Park', true)));
  truthy('[+] a second, made the default', work.isDefault === true, JSON.stringify(work));
  let book = list(me('GET', '/addresses'));
  truthy('[+] two in the book, one default', book.length === 2 && book.filter((a) => a.isDefault).length === 1 && (book.find((a) => a.id === work.id) || {}).isDefault === true, JSON.stringify(book));
  expect(me('PUT', `/addresses/${home.id}`, addr('12 High Street', true)), '[+] the first is made the default instead', 200);
  book = list(me('GET', '/addresses'));
  truthy('[+] ...and the second is no longer', (book.find((a) => a.id === home.id) || {}).isDefault === true && !(book.find((a) => a.id === work.id) || {}).isDefault, JSON.stringify(book));
  expect(me('DELETE', `/addresses/${work.id}`), '[+] the second is removed', 204);
  expect(me('DELETE', `/addresses/${work.id}`), '[-] and cannot be removed twice', 404, 'ADDRESS_NOT_FOUND');
  expect(me('POST', '/addresses', { type: 'HOME', city: 'London', country: 'GB' }), '[-] an address without a first line', 400, 'VALIDATION_FAILED');
  expect(me('POST', '/addresses', { type: 'HOME', line1: '12 High Street' }), '[-] or without a country', 400, 'VALIDATION_FAILED');
  expect(me('POST', '/addresses', addr('x'.repeat(121))), '[-] a line longer than 120 characters', 400, 'VALIDATION_FAILED');
  expect(me('PUT', '/addresses/not-an-id', addr('Ghost')), '[-] an id that is not one never reaches the service', 403, 'FORBIDDEN');

  // ── the loop closed: a delivery order with the saved address ─────────────────
  const placed = call('POST', '/api/order-svc/orders', {
    token: shopper.token, ...shop, idem: true,
    body: {
      storeId: tenant.stores[0].id, channel: 'ONLINE', fulfilmentType: 'DELIVERY',
      deliveryLine1: home.line1, deliveryCity: home.city, deliveryPostalCode: home.pincode,
      deliveryRecipientName: 'Chris Carter', deliveryRecipientPhone: '07700900123', contactPhone: '07700900123',
      items: [{ variantId: tenant.variantId, qty: 1 }],
    },
  });
  expect(placed, '[+] a delivery order goes to the saved address', 201);

  // ── the wrong caller ─────────────────────────────────────────────────────────
  expect(call('GET', '/api/customer-svc/customers/me/addresses', { ...shop }), '[-] a guest has no book', 401, 'UNAUTHORIZED');
  expect(call('PUT', '/api/customer-svc/customers/me', { body: { firstName: 'A', lastName: 'B' }, ...shop }), '[-] nor a profile', 401, 'UNAUTHORIZED');
  expect(me('GET', '/addresses', undefined, other.token), '[-] another shopper has no record here', 404, 'CUSTOMER_NOT_FOUND');
  expect(me('PUT', `/addresses/${home.id}`, addr('Taken', true), other.token), '[-] and cannot touch this one\'s address', 404, 'CUSTOMER_NOT_FOUND');
  expect(me('DELETE', `/addresses/${home.id}`, undefined, other.token), '[-] nor remove it', 404, 'CUSTOMER_NOT_FOUND');
  truthy('[+] it is still there', list(me('GET', '/addresses')).some((a) => a.id === home.id && a.line1 === '12 High Street'));
  expect(call('GET', '/api/customer-svc/customers/me/addresses', { token: shopper.token, storefront: rival.tenantId }), '[-] at the rival shop the shopper has no record', 404, 'CUSTOMER_NOT_FOUND');
  expect(call('GET', '/api/customer-svc/customers/me/addresses', { token: shopper.token }), '[-] with no shop named there is no book to find', 401, 'NO_TENANT');

  // ── abuse: the cap, the race, the hammer ─────────────────────────────────────
  const filler = register('account-filler');
  const fill = (method, path, body) => call(method, `/api/customer-svc/customers/me${path}`, { token: filler.token, body, ...shop });
  expect(fill('POST', ''), '[+] a second shopper claims a record', 200);
  for (let i = 0; i < 10; i++) expect(fill('POST', '/addresses', addr(`Street ${i}`)), `[+] address ${i + 1} of 10`, 201);
  expect(fill('POST', '/addresses', addr('One too many')), '[-] the eleventh is refused', 409, 'CUSTOMER_ADDRESS_LIMIT');
  const racer = register('account-racer');
  expect(call('POST', '/api/customer-svc/customers/me', { token: racer.token, ...shop }), '[+] a third shopper claims a record', 200);
  const race = http.batch(Array.from({ length: 20 }, (_, i) => ['POST', `${BASE}/api/customer-svc/customers/me/addresses`, JSON.stringify(addr(`Race ${i}`)), { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${racer.token}`, 'X-Storefront-Tenant': tenant.tenantId }, tags: { name: 'POST /api/customer-svc/customers/me/addresses (race)' } }]));
  const got = race.filter((r) => r.status === 201).length;
  truthy('[+] twenty at once: exactly ten get in, ten are told the book is full', got === 10 && race.every((r) => r.status === 201 || r.status === 409), race.map((r) => r.status).join(','));
  truthy('[+] ...and the book holds ten', list(call('GET', '/api/customer-svc/customers/me/addresses', { token: racer.token, ...shop })).length === 10);
  const stranger = register('account-stranger');
  const hammer = http.batch(Array.from({ length: 20 }, (_, i) => ['PUT', `${BASE}/api/customer-svc/customers/me`, JSON.stringify({ firstName: `A${i}`, lastName: 'B' }), { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${stranger.token}`, 'X-Storefront-Tenant': tenant.tenantId }, tags: { name: 'PUT /api/customer-svc/customers/me (hammer)' } }]));
  truthy('[-] twenty profile writes with no record: every one not found, nothing created', hammer.every((r) => r.status === 404 || r.status === 429) && call('GET', '/api/customer-svc/customers/me', { token: stranger.token, ...shop }).status === 404, hammer.map((r) => r.status).join(','));
}
