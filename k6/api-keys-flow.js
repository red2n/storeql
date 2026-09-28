// A business's own API keys (22.7), through the gateway: an owner mints a key for one of its
// systems in a staff tier for one store, the key is shown once and listed without itself; the key
// reads and writes what that tier may, at that store and not another, counted against the
// business and stamped as the actor; it cannot sign in, manage logins, mint keys, start a business
// or act for the platform; a key a character off is refused, a revoked one stops within seconds,
// and a manager may read the list but not mint or revoke.
//
//   k6/run.sh api-keys-flow
import { Counter } from 'k6/metrics';
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

const completed = new Counter('flow_completed');
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
  setupTimeout: '4m',
};

const KEYS = '/api/iam-svc/auth/admin/api-keys';

export function setup() {
  const tenant = onboardTenant('keys');
  const second = addStore(tenant, 'B');
  const rival = onboardTenant('keys-rival');
  return {
    tenant,
    store: tenant.stores[0].id,
    second: second.id,
    rival,
    manager: staffUser(tenant, 'MANAGER', [tenant.stores[0].id]),
    variant: sellableVariant(tenant, `Keyed ${uniq()}`).variantId,
  };
}

export default function ({ tenant, store, second, rival, manager, variant }) {
  const owner = tenant.owner.token;
  const mint = (token, body) => call('POST', KEYS, { token, body });

  // ── minting ──────────────────────────────────────────────────────────────────────────────────
  const made = mint(owner, { name: 'Warehouse ERP', role: 'STOREKEEPER', storeIds: [store] });
  expect(made, '[+] the owner mints a key for its warehouse system, as a storekeeper of one store', 201);
  const key = data(made).key;
  const keyId = data(made).id;
  truthy('[+] a key: the prefix, forty random characters, shown with its first twelve', typeof key === 'string' && key.startsWith('sqk_') && key.length === 44 && data(made).prefix === key.slice(0, 12), data(made).prefix);
  const listed = data(call('GET', KEYS, { token: manager.token })) || {};
  truthy('[+] listed for a manager to see, without the key itself', (listed.items || []).some((k) => k.id === keyId && k.prefix === key.slice(0, 12) && !('key' in k)), listed);
  expect(mint(manager.token, { name: 'x', role: 'CASHIER' }), '[-] a manager may read keys, not mint them', 403);
  expect(mint(owner, { name: 'x', role: 'OWNER' }), '[-] never an owner\'s key', 400, 'API_KEY_ROLE_INVALID');
  expect(mint(owner, { name: 'x', role: 'PLATFORM_ADMIN' }), '[-] never the platform\'s', 400, 'API_KEY_ROLE_INVALID');
  expect(mint(owner, { name: 'x', role: 'MANAGER', expiresAt: '2020-01-01T00:00:00Z' }), '[-] a key expires on a day still to come', 400, 'API_KEY_EXPIRY_PAST');
  expect(mint(owner, { name: '', role: 'MANAGER' }), '[-] a key has a name', 400, 'API_KEY_NAME_INVALID');
  expect(mint(owner, { name: 'x', role: 'MANAGER', storeIds: ['not-a-store'] }), '[-] a store is an id', 400, 'INVALID_UUID');
  truthy('[-] a business sees only its own keys', ((data(call('GET', KEYS, { token: rival.owner.token })) || {}).items || []).length === 0, 'rival list');

  // ── what the key may do ──────────────────────────────────────────────────────────────────────
  const asKey = (method, path, body, extra = {}) => call(method, path, { token: key, body, ...extra });
  const received = asKey('POST', '/api/inventory-svc/admin/inventory/receive', { storeId: store, variantId: variant, qty: 5, batchNo: `K-${uniq()}`.slice(0, 32), costPrice: '4.00' }, { idem: true });
  expect(received, '[+] the key receives stock at its store, as a storekeeper may', 201);
  // Store scope reaches the services as it does for a person: every route that takes a store holds
  // the caller to the stores they were given (SJ-D74 closed the stock routes that did not).
  expect(asKey('POST', '/api/inventory-svc/admin/inventory/receive', { storeId: second, variantId: variant, qty: 5, batchNo: `K-${uniq()}`.slice(0, 32), costPrice: '4.00' }, { idem: true }), '[-] and not at another store: the key is held to the store it was given', 403, 'STORE_ACCESS_DENIED');
  expect(asKey('GET', `/api/inventory-svc/admin/inventory/levels?store=${second}`), '[-] nor does it read another store\'s stock', 403, 'STORE_ACCESS_DENIED');
  const managerKey = must(mint(owner, { name: 'Store ops', role: 'MANAGER', storeIds: [store] }), 201, 'a manager key for one store').key;
  const day = new Date().toISOString().slice(0, 10);
  const tasks = (storeId) => call('GET', `/api/tenant-svc/admin/workforce/tasks/days?storeId=${storeId}&from=${day}&to=${day}`, { token: managerKey });
  expect(tasks(store), '[+] a manager\'s key reads its own store\'s task days', 200);
  expect(tasks(second), '[-] and not another store\'s: the key is scoped to the store it was given', 403);
  expect(asKey('GET', `/api/inventory-svc/admin/inventory/levels?store=${store}`), '[+] it reads the stock it keeps', 200);
  expect(asKey('POST', '/api/tenant-svc/admin/stores', { name: 'Keyed', code: `KEY-${uniq()}`.slice(0, 16), country: 'GB', city: 'Leeds', line1: '1 Key St', postcode: 'LS1 1AA', timezone: 'Europe/London' }), '[-] a storekeeper\'s key cannot do a manager\'s work', 403);
  const audit = data(call('GET', `/api/iam-svc/auth/admin/api-keys`, { token: owner })) || {};
  truthy('[+] the use is remembered on the key', ((audit.items || []).find((k) => k.id === keyId) || {}).lastUsedAt, audit);

  // ── what a key is not ────────────────────────────────────────────────────────────────────────
  expect(asKey('GET', '/api/iam-svc/auth/me'), '[-] a key is not a person: it cannot sign in or read a session', 403, 'API_KEY_ROUTE_FORBIDDEN');
  expect(asKey('POST', KEYS, { name: 'more', role: 'CASHIER' }), '[-] nor mint keys', 403, 'API_KEY_ROUTE_FORBIDDEN');
  expect(asKey('POST', '/api/iam-svc/auth/mfa/totp/enrol'), '[-] nor set up a second factor', 403, 'API_KEY_ROUTE_FORBIDDEN');
  expect(asKey('POST', '/api/tenant-svc/onboarding/tenants', { businessName: 'Keyed Ltd', country: 'GB', currency: 'GBP' }), '[-] nor start a business', 403, 'API_KEY_ROUTE_FORBIDDEN');
  expect(asKey('GET', '/api/tenant-svc/platform/tenants'), '[-] nor act for the platform', 403, 'API_KEY_ROUTE_FORBIDDEN');
  expect(asKey('GET', '/api/tenant-svc/admin/tenant/billing'), '[-] nor read what only an owner may', 403);
  const forged = key.slice(0, 43) + (key.endsWith('A') ? 'B' : 'A');
  expect(call('GET', `/api/inventory-svc/admin/inventory/levels?store=${store}`, { token: forged }), '[-] a key a character off opens nothing', 401);
  expect(call('GET', `/api/inventory-svc/admin/inventory/levels?store=${store}`, { token: 'sqk_short' }), '[-] nor one the wrong length', 401);
  expect(call('GET', `/api/inventory-svc/admin/inventory/levels?store=${rival.stores[0].id}`, { token: key }), '[-] nor another business\'s stock: a store the key does not keep is refused before the business is even asked', 403, 'STORE_ACCESS_DENIED');

  // ── revoking ─────────────────────────────────────────────────────────────────────────────────
  expect(call('DELETE', `${KEYS}/${keyId}`, { token: manager.token }), '[-] a manager cannot revoke', 403);
  expect(call('DELETE', `${KEYS}/${keyId}`, { token: rival.owner.token }), '[-] nor another business\'s owner', 404, 'API_KEY_NOT_FOUND');
  const revoked = call('DELETE', `${KEYS}/${keyId}`, { token: owner });
  expect(revoked, '[+] the owner revokes it', 200);
  truthy('[+] ...and it stays on the list, marked', !!data(revoked).revokedAt, data(revoked));
  expect(call('DELETE', `${KEYS}/${keyId}`, { token: owner }), '[-] once', 409, 'API_KEY_REVOKED');
  const stopped = poll(30, () => call('GET', `/api/inventory-svc/admin/inventory/levels?store=${store}`, { token: key }).status === 401);
  truthy('[+] the key stops within seconds of being revoked', stopped >= 0, 'still answering');

  completed.add(1);
}
