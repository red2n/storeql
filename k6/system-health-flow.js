// The system-health screen (intent/system-health-dashboard.md), through the gateway: every request is
// counted by route group and outcome and a refusal comes back with a request id the business can
// quote; the figures and the last 24 hours of failures are read by the owner, a business-wide
// manager and a custom role given system.health, and by nobody else (a cashier and a storekeeper are
// SYSTEM_HEALTH_NOT_PERMITTED, a manager held to a store is BUSINESS_WIDE_ONLY, a shopper is not
// staff); a failure carries the id of the answer that failed, its route pattern and its stable code,
// and nothing of the query string or the ids in the path; another business's traffic and failures
// are never shown, even to a caller naming our ids; the waiting-work read answers the six queues in
// a fixed order with the same access rules.
//
//   k6/run.sh system-health-flow
import { sleep } from 'k6';
import { ALL_CHECKS_PASS, businessWideManager, call, data, errorCode, expect, must, newId, onboardTenant, provisionStaff, register, signInUntil, staffUser, truthy } from './lib/storeql.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS, setupTimeout: '4m' };

const HEALTH = '/api/v1/system-health';
const WAITING = '/api/v1/reporting-svc/admin/reports/system-health/waiting-work';
const ROLES = '/api/v1/tenant-svc/admin/roles';
const STAFF = '/api/v1/tenant-svc/admin/staff';
const KINDS = ['PURCHASE_ORDER_APPROVAL', 'PAYMENT_RUN', 'SUPPLIER_INVOICE', 'ACCOUNTING_SYNC', 'CARD_REFUND', 'PRIVACY_REQUEST'];

export function setup() {
  const tenant = onboardTenant('health', { country: 'GB', currency: 'GBP' });
  const rival = onboardTenant('health-rival', { country: 'IN', currency: 'INR' });
  const store = tenant.stores[0];
  return {
    tenant,
    rival,
    store,
    manager: businessWideManager(tenant),
    heldManager: staffUser(tenant, 'MANAGER', [store.id]),
    storekeeper: staffUser(tenant, 'STOREKEEPER', [store.id]),
    cashier: staffUser(tenant, 'CASHIER', [store.id]),
    shopper: register('health-shopper'),
  };
}

const summary = (token, headers = {}) => call('GET', `${HEALTH}/summary`, { token, headers });
const failures = (token, query = '', headers = {}) => call('GET', `${HEALTH}/failures${query}`, { token, headers });

/** Calls fn once a second until it returns something, for up to `seconds`; that value, or null. */
function until(seconds, fn) {
  const deadline = Date.now() + seconds * 1000;
  for (;;) {
    const value = fn();
    if (value) return value;
    if (Date.now() >= deadline) return null;
    sleep(1);
  }
}

const total = (res) => (data(res).windows || {}).last24Hours ? data(res).windows.last24Hours.total : -1;

export default function ({ tenant, rival, store, manager, heldManager, storekeeper, cashier, shopper }) {
  const owner = tenant.owner.token;

  // ── a quiet business starts empty ──────────────────────────────────────────────────────────
  const first = summary(owner);
  expect(first, '[+] the owner reads the figures', 200);
  const s0 = data(first);
  truthy('[+] ...live, with the three windows, the groups and the two series', s0.available === true && ['last5Minutes', 'lastHour', 'last24Hours'].every((w) => s0.windows && s0.windows[w] && typeof s0.windows[w].total === 'number') && Array.isArray(s0.byGroup) && s0.perMinute.length === 60 && s0.perHour.length === 24, s0);
  truthy('[+] ...the screen\'s own reads are not part of the traffic it shows', !(s0.byGroup || []).some((g) => g.group === 'system-health'), s0.byGroup);

  // ── traffic: successes, a business answer, a refusal and a failure the business can quote ──
  for (let i = 0; i < 3; i++) must(call('GET', '/api/v1/tenant-svc/admin/tenant', { token: owner }), 200, 'profile');
  const missing = call('GET', `/api/v1/order-svc/orders/${newId()}?customerEmail=private-${newId()}@k6.storeql.test`, { token: owner });
  expect(missing, '[-] an order nobody has is a business answer (404), not a failure', 404);
  const refused = call('GET', ROLES, { token: cashier.token });
  expect(refused, '[-] a cashier reading the roles is refused', 403, 'FORBIDDEN');
  const refusedId = refused.headers['X-Request-Id'] || refused.headers['x-request-id'];
  truthy('[+] ...and the refusal comes back with a request id to quote', typeof refusedId === 'string' && refusedId.length === 36, refused.headers);

  const seen = until(30, () => {
    const s = data(summary(owner));
    const w = (s.windows || {}).last5Minutes || {};
    return w.total >= 5 && w.failed >= 1 ? s : null;
  });
  truthy('[+] the figures count what was sent, by outcome', !!seen, data(summary(owner)));
  if (seen) {
    const w = seen.windows.last5Minutes;
    truthy('[+] ...successes, the 404 as a client answer, the refusal as a failure, and a rate', w.succeeded >= 3 && w.clientErrors >= 1 && w.failed >= 1 && w.failureRate > 0 && w.failureRate <= 1, w);
    truthy('[+] ...grouped by what the request does, never by address', (seen.byGroup || []).some((g) => g.group === 'tenant-svc' && g.total >= 3) && (seen.byGroup || []).some((g) => g.group === 'order-svc'), seen.byGroup);
    truthy('[+] ...and the minute it happened in is in the series', seen.perMinute.reduce((n, p) => n + p.total, 0) >= 5, seen.perMinute.slice(-3));
  }

  // The waiting-work read is the screen's own too: polled, it adds nothing to the figures. A call
  // made after it is the marker: once the marker is counted, anything the reads left would be too.
  const tenantCalls = () => ((data(summary(owner)).byGroup || []).find((g) => g.group === 'tenant-svc') || { total: 0 }).total;
  const beforeMarker = tenantCalls();
  expect(call('GET', WAITING, { token: owner }), '[+] the screen reads its waiting work', 200);
  expect(call('GET', '/api/reporting-svc/admin/reports/system-health/waiting-work', { token: owner }), '[+] ...on the unversioned alias too, as the app does', 200);
  must(call('GET', '/api/v1/tenant-svc/admin/tenant', { token: owner }), 200, 'marker call');
  truthy('[+] a call after them is counted', until(30, () => tenantCalls() > beforeMarker), 'marker');
  truthy('[+] ...and neither waiting-work read is part of the traffic the screen shows', !(data(summary(owner)).byGroup || []).some((g) => g.group === 'reporting-svc'), data(summary(owner)).byGroup);

  // ── the failure list: quotable, and nothing private in it ───────────────────────────────────
  const listed = until(30, () => {
    const items = (data(failures(owner)).items) || [];
    return items.find((f) => f.requestId === refusedId) || null;
  });
  truthy('[+] the refusal is listed under the id it came back with', !!listed, data(failures(owner)));
  if (listed) {
    truthy('[+] ...with its status, its stable code, its method and a route pattern', listed.status === 403 && listed.code === 'FORBIDDEN' && listed.method === 'GET' && listed.routePattern === '/api/v1/tenant-svc/admin/roles' && listed.group === 'tenant-svc', listed);
    truthy('[+] ...who sent it and how long it took', listed.userId === cashier.userId && typeof listed.ms === 'number' && !!listed.at, listed);
  }
  const everything = JSON.stringify(data(failures(owner)));
  truthy('[+] nothing of a query string or a path\'s ids is kept', !everything.includes('private-') && !everything.includes('customerEmail'), 'the failure list');
  truthy('[+] a failure list is capped and paged by cursor', (data(failures(owner, '?limit=1')).items || []).length <= 1 && expectBadLimit(owner), 'limit');

  // ── who may read it ─────────────────────────────────────────────────────────────────────────
  expect(summary(manager.token), '[+] a business-wide manager reads the figures', 200);
  expect(failures(manager.token), '[+] ...and the failures', 200);
  expect(summary(heldManager.token), '[-] a manager held to a store is not given the whole business', 403, 'BUSINESS_WIDE_ONLY');
  expect(failures(heldManager.token), '[-] ...nor its failures', 403, 'BUSINESS_WIDE_ONLY');
  expect(summary(storekeeper.token), '[-] a storekeeper is not permitted', 403, 'SYSTEM_HEALTH_NOT_PERMITTED');
  expect(summary(cashier.token), '[-] nor is a cashier', 403, 'SYSTEM_HEALTH_NOT_PERMITTED');
  expect(failures(cashier.token), '[-] ...nor their failures', 403, 'SYSTEM_HEALTH_NOT_PERMITTED');
  const shopperRead = call('GET', `${HEALTH}/summary`, { token: shopper.token, storefront: tenant.tenantId });
  truthy('[-] a shopper is refused', shopperRead.status === 403 || shopperRead.status === 401, { status: shopperRead.status, code: errorCode(shopperRead) });
  expect(call('GET', `${HEALTH}/summary`), '[-] no token is refused', 401);
  expect(call('POST', `${HEALTH}/summary`, { token: owner, body: {} }), '[-] the screen only reads', 405);
  truthy('[-] the screen is not served on the unversioned alias', call('GET', '/api/system-health/summary', { token: owner }).status !== 200, 'alias');

  // ── a role the owner gives it to: the IT person ─────────────────────────────────────────────
  expect(call('POST', ROLES, { token: owner, body: { code: 'IT_SUPPORT', name: 'IT support', baseTier: 'MANAGER', permissions: ['system.health'] } }), '[+] the owner defines an IT role on the manager tier holding only system.health', 201);
  const it = staffUserWithRole(tenant, 'IT_SUPPORT');
  expect(summary(it.token), '[+] the person holding it reads the figures', 200);
  expect(failures(it.token), '[+] ...and the failures', 200);
  expect(call('POST', ROLES, { token: owner, body: { code: 'BUYER', name: 'Buyer', baseTier: 'MANAGER', permissions: ['purchasing.approve'] } }), '[+] a manager role narrowed to something else', 201);
  const buyer = staffUserWithRole(tenant, 'BUYER');
  expect(summary(buyer.token), '[-] a manager narrowed away from it is refused by name', 403, 'SYSTEM_HEALTH_NOT_PERMITTED');

  // ── another business sees none of it, even naming ours ──────────────────────────────────────
  const theirs = rival.owner.token;
  const naming = { 'X-Tenant-Id': tenant.tenantId, 'X-Store-Ids': store.id };
  const rivalSummary = summary(theirs, naming);
  expect(rivalSummary, '[+] the rival owner reads their own figures', 200);
  truthy('[-] ...none of ours: not our requests, not our groups', total(rivalSummary) < total(summary(owner)) && !(data(rivalSummary).byGroup || []).some((g) => g.group === 'order-svc'), data(rivalSummary));
  truthy('[-] ...and none of our failures, though they name our tenant and store', !(data(failures(theirs, `?tenantId=${tenant.tenantId}`, naming)).items || []).some((f) => f.requestId === refusedId || f.userId === cashier.userId), 'rival failures');

  // ── waiting work ────────────────────────────────────────────────────────────────────────────
  const waiting = call('GET', WAITING, { token: owner });
  expect(waiting, '[+] the owner reads what is waiting for a person', 200);
  const w = data(waiting);
  truthy('[+] ...the six queues, in a fixed order, each with words and a count', (w.items || []).map((i) => i.kind).join(',') === KINDS.join(',') && w.items.every((i) => typeof i.label === 'string' && i.label.includes(' ') && (i.count === null || typeof i.count === 'number') && typeof i.capped === 'boolean'), w);
  truthy('[+] ...every source answered, so nothing is unreachable and nothing is guessed', Array.isArray(w.unreachable) && w.unreachable.length === 0 && w.items.every((i) => typeof i.count === 'number'), w);
  truthy('[+] ...a quiet business has nothing waiting', w.items.every((i) => i.count === 0), w.items.map((i) => `${i.kind}=${i.count}`));
  truthy('[+] ...and nowhere near the cap, so nothing says "or more"', w.items.every((i) => i.capped === false), w.items.map((i) => `${i.kind}=${i.capped}`));
  expect(call('GET', WAITING, { token: manager.token }), '[+] a business-wide manager reads it', 200);
  expect(call('GET', WAITING, { token: it.token }), '[+] ...and so does the IT role', 200);
  expect(call('GET', WAITING, { token: heldManager.token }), '[-] a manager held to a store is not given the whole business', 403, 'BUSINESS_WIDE_ONLY');
  expect(call('GET', WAITING, { token: buyer.token }), '[-] a role without the permission is refused by name', 403, 'SYSTEM_HEALTH_NOT_PERMITTED');
  expect(call('GET', WAITING, { token: cashier.token }), '[-] a cashier is refused', 403);
  expect(call('GET', WAITING, { token: storekeeper.token }), '[-] a storekeeper is refused', 403);
  expect(call('GET', WAITING), '[-] no token is refused', 401);
  const theirWaiting = call('GET', `${WAITING}?tenantId=${tenant.tenantId}`, { token: theirs, headers: naming });
  expect(theirWaiting, '[+] the rival owner reads their own queues', 200);
  truthy('[-] ...the same six, theirs alone', (data(theirWaiting).items || []).length === 6 && data(theirWaiting).items.every((i) => i.count === 0 && i.capped === false), data(theirWaiting));
}

function expectBadLimit(token) {
  const res = failures(token, '?limit=abc');
  return res.status === 400 && errorCode(res) === 'INVALID_LIMIT';
}

/**
 * A login whose only assignment is the custom role `role`, business-wide: assigned by the owner and
 * signed in, so its token carries that role's tier and permissions and nothing of a plain manager.
 */
function staffUserWithRole(tenant, role) {
  const user = provisionStaff(tenant, `${tenant.label}-${role.toLowerCase()}`);
  must(call('POST', STAFF, { token: tenant.owner.token, body: { userId: user.userId, role, businessWide: true } }), 201, `assign ${role} business-wide`);
  signInUntil(user, (c) => c.tenant === tenant.tenantId && (c.roles || []).includes('MANAGER') && Array.isArray(c.perms) && (c.storeIds || []).length === 0);
  return user;
}
