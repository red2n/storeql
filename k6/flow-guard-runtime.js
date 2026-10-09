// Runtime flow guard — whether a tenant or a store may transact right now.
//
// A platform admin suspends a tenant, or an owner closes a store, and every place money or stock
// can move must stop: the gateway (storefront browsing and customer checkout), iam-svc (staff
// login, token refresh, POS sessions), cart-svc and order-svc. Only that tenant or store stops,
// and everything resumes once it is reopened. The change travels as TenantStatusChanged /
// StoreStatusChanged events, so each guard is polled until it takes effect and the delay is
// printed.
//
//   k6/run.sh flow-guard-runtime
import { sleep } from 'k6';
import {
  ALL_CHECKS_PASS,
  call,
  data,
  errorCode,
  expect,
  login,
  must,
  onboardTenant,
  platformAdmin,
  poll,
  priceVariants,
  receive,
  register,
  sellableVariant,
  setStoreStatus,
  setTenantStatus,
  staffUser,
  truthy,
  uniq,
} from './lib/storeql.js';

export const options = {
  scenarios: { guard: { executor: 'per-vu-iterations', vus: 1, iterations: 1, maxDuration: '20m' } },
  thresholds: ALL_CHECKS_PASS,
  setupTimeout: '5m',
};

/** Longest a status change may take to reach every guard: outbox poll + Kafka + the gateway's 15 s cache. */
const PROPAGATION = 60;

export function setup() {
  const admin = platformAdmin();
  const tenants = {
    a: onboardTenant('guard-a', { country: 'IN', currency: 'INR', stores: 2 }),
    b: onboardTenant('guard-b', { country: 'GB', currency: 'GBP', stores: 1 }),
  };
  for (const t of Object.values(tenants)) {
    t.variantId = sellableVariant(t, 'Guarded tea').variantId;
    priceVariants(t, [t.variantId]);
    // Stock at every store: where checkout holds are enforced (the production default) an online
    // order for a variant with none is refused ORDER_INSUFFICIENT_STOCK, and these guards place
    // several. Harmless where holds are off.
    for (const s of t.stores) must(receive(t, s.id, t.variantId, 50), [200, 201], 'stock for the guarded tea');
    t.customer = register(`${t.label}-shopper`);
  }
  // Staff who never sells or opens a till: their cart is not checked out by an order, and their
  // refresh token is not revoked along with ended POS sessions.
  tenants.a.manager = staffUser(tenants.a, 'MANAGER', tenants.a.stores.map((s) => s.id));
  return { admin, a: tenants.a, b: tenants.b };
}

// ── the guarded surfaces ──────────────────────────────────────────────────────

function cart(t, storeId) {
  return call('POST', '/api/cart-svc/cart', { token: t.owner.token, body: { storeId } });
}

/** Opens a POS session and, when that works, closes it again so the next probe can open one. */
function posSession(t, storeId) {
  const res = call('POST', '/api/iam-svc/auth/pos/sessions', { token: t.owner.token, body: { storeId } });
  if (res.status === 201) call('DELETE', `/api/iam-svc/auth/pos/sessions/${data(res).id}`, { token: t.owner.token });
  return res;
}

function posOrder(t, storeId) {
  return call('POST', '/api/order-svc/orders', {
    token: t.owner.token,
    idem: true,
    body: { storeId, channel: 'POS', fulfilmentType: 'INSTORE', paymentMethod: 'CASH', items: [{ variantId: t.variantId, qty: 1 }] },
  });
}

function onlineOrder(t, storeId) {
  return call('POST', '/api/order-svc/orders', {
    token: t.customer.token,
    storefront: t.tenantId,
    idem: true,
    body: { storeId, channel: 'ONLINE', fulfilmentType: 'PICKUP', items: [{ variantId: t.variantId, qty: 1 }] },
  });
}

function storefront(t) {
  return call('GET', '/api/product-svc/catalog/products?limit=1', { storefront: t.tenantId });
}

function ownerLogin(t) {
  return login(t.owner);
}

/** A refresh rotates the token, and replaying a spent one revokes every session — keep the new one. */
function refresh(user) {
  const res = call('POST', '/api/iam-svc/auth/refresh', { body: { refreshToken: user.refreshToken } });
  if (res.status === 200) {
    user.token = data(res).accessToken;
    user.refreshToken = data(res).refreshToken;
  }
  return res;
}

function activeStorefrontStores(t) {
  return (data(call('GET', '/api/tenant-svc/storefront/stores', { storefront: t.tenantId })) || []).map((s) => s.storeId);
}

const report = [];

/** Poll `probe` until it answers `status` (+ `code`) or `seconds` pass; one check either way. */
function until(label, seconds, probe, status, code) {
  const statuses = Array.isArray(status) ? status : [status];
  let res = null;
  const took = poll(seconds, () => {
    res = probe();
    return statuses.includes(res.status) && (!code || errorCode(res) === code);
  });
  const ok = expect(res, label, status, code);
  const answer = `${res.status}${errorCode(res) ? ` ${errorCode(res)}` : ''}`;
  report.push(`${ok ? 'PASS' : 'FAIL'}  ${label} → ${answer}${ok && took > 0 ? `  (after ${took}s)` : ''}`);
}

/** Answered right now, no waiting — for what must never have been affected. */
const now = (label, probe, status, code) => until(label, 0, probe, status, code);

function everythingOpen(t, storeId, tag) {
  now(`${tag} cart opens`, () => cart(t, storeId), [200, 201]);
  now(`${tag} POS session opens`, () => posSession(t, storeId), 201);
  now(`${tag} POS order placed`, () => posOrder(t, storeId), 201);
  now(`${tag} online order placed`, () => onlineOrder(t, storeId), 201);
}

export default function (d) {
  const { admin, a, b } = d;
  const [a1, a2] = a.stores.map((s) => s.id);
  const b1 = b.stores[0].id;

  report.push('── 1. who may flip the switches ──');
  expect(setTenantStatus({ token: a.owner.token }, a.tenantId, 'INACTIVE'), 'owner cannot suspend their own tenant', 403);
  expect(setTenantStatus({ token: null }, a.tenantId, 'INACTIVE'), 'no token cannot suspend a tenant', 401);
  expect(setTenantStatus(admin, a.tenantId, 'SLEEPING'), 'unknown tenant status rejected', 400, 'INVALID_STATUS');
  expect(setTenantStatus(admin, '01a0b000-0000-7000-8000-000000000000', 'INACTIVE'), 'unknown tenant 404', 404);
  expect(setStoreStatus(b, a1, 'CLOSED'), "another tenant's owner cannot close A's store", 404);
  expect(setStoreStatus(a, a1, 'NAPPING'), 'unknown store status rejected', 400, 'INVALID_STATUS');
  expect(
    call('PATCH', `/api/tenant-svc/admin/stores/${a1}/status`, { token: a.customer.token, body: { status: 'CLOSED' } }),
    'a shopper cannot close a store',
    403
  );

  report.push('── 2. baseline: both tenants trade ──');
  everythingOpen(a, a1, 'A');
  everythingOpen(b, b1, 'B');
  now('A storefront serves', () => storefront(a), 200);
  now('A owner logs in', () => ownerLogin(a), 200);
  now('A owner refreshes', () => refresh(a.owner), 200);
  now('A manager refreshes', () => refresh(a.manager), 200);
  truthy('A storefront lists both stores', [a1, a2].every((id) => activeStorefrontStores(a).includes(id)), activeStorefrontStores(a));

  report.push("── 3. a store id from another tenant is never operational ──");
  now("B cannot open a cart at A's store", () => cart(b, a2), 409, 'STORE_NOT_OPERATIONAL');
  now("B cannot open a POS session at A's store", () => posSession(b, a2), 409, 'STORE_NOT_OPERATIONAL');
  now("B cannot place a POS order at A's store", () => posOrder(b, a2), 409, 'STORE_NOT_OPERATIONAL');

  report.push('── 4. platform admin suspends tenant A ──');
  const liveSession = data(call('POST', '/api/iam-svc/auth/pos/sessions', { token: a.owner.token, body: { storeId: a2 } })).id;
  truthy('a POS session is open before the suspension', liveSession);
  expect(setTenantStatus(admin, a.tenantId, 'INACTIVE'), 'admin suspends A', 200);
  until('A cart refused', PROPAGATION, () => cart(a, a1), 409, 'TENANT_NOT_OPERATIONAL');
  until('A POS session refused', PROPAGATION, () => posSession(a, a1), 409, 'TENANT_NOT_OPERATIONAL');
  until('A POS order refused', PROPAGATION, () => posOrder(a, a1), 409, 'TENANT_NOT_OPERATIONAL');
  until('A storefront refused at the gateway', PROPAGATION, () => storefront(a), 403, 'TENANT_INACTIVE');
  until('A online checkout refused at the gateway', PROPAGATION, () => onlineOrder(a, a1), 403, 'TENANT_INACTIVE');
  until('A owner login refused', PROPAGATION, () => ownerLogin(a), 403, 'TENANT_INACTIVE');
  until('A manager token refresh refused', PROPAGATION, () => refresh(a.manager), 403, 'TENANT_INACTIVE');
  // Ending a POS session revokes that cashier's refresh tokens outright.
  until('A owner refresh token revoked with their POS session', PROPAGATION, () => refresh(a.owner), 401, 'INVALID_REFRESH');
  until(
    'the open POS session was ended',
    PROPAGATION,
    () => call('PUT', `/api/iam-svc/auth/pos/sessions/${liveSession}/activity`, { token: a.owner.token }),
    409,
    'POS_SESSION_NOT_ACTIVE'
  );
  expect(setTenantStatus(admin, a.tenantId, 'INACTIVE'), 'suspending again is harmless', 200);
  expect(
    call('POST', '/api/tenant-svc/admin/stores', {
      token: a.owner.token,
      body: { name: 'Pop-up', code: `POP-${uniq()}`, line1: '2 High Street', city: 'Pune', country: 'IN', pincode: '411001', timezone: 'Asia/Kolkata' },
    }),
    'a suspended tenant cannot open a new store',
    422,
    'TENANT_NOT_ACTIVE'
  );
  everythingOpen(b, b1, 'B unaffected:');
  now('B storefront unaffected', () => storefront(b), 200);
  now('B owner login unaffected', () => ownerLogin(b), 200);

  report.push('── 5. admin reactivates A — its stores stay shut until the owner reopens them ──');
  expect(setTenantStatus(admin, a.tenantId, 'ACTIVE'), 'admin reactivates A', 200);
  until('A storefront serves again', PROPAGATION, () => storefront(a), 200);
  until('A owner logs in again', PROPAGATION, () => ownerLogin(a), 200);
  // The refresh above was refused, so sign in afresh for a token pair to refresh with.
  const relogin = ownerLogin(a);
  a.owner.token = data(relogin).accessToken;
  a.owner.refreshToken = data(relogin).refreshToken;
  now('A owner refreshes again', () => refresh(a.owner), 200);
  now('A manager refreshes again: that token was never revoked', () => refresh(a.manager), 200);
  until('A cart still refused: stores suspended', PROPAGATION, () => cart(a, a1), 409, 'STORE_NOT_OPERATIONAL');
  until('A POS session still refused: stores suspended', PROPAGATION, () => posSession(a, a1), 409, 'STORE_NOT_OPERATIONAL');
  until('A online checkout still refused: stores suspended', PROPAGATION, () => onlineOrder(a, a1), 409, 'STORE_NOT_OPERATIONAL');
  truthy('A storefront lists no stores', activeStorefrontStores(a).length === 0, activeStorefrontStores(a));
  for (const id of [a1, a2]) expect(setStoreStatus(a, id, 'ACTIVE'), 'owner reopens an A store', 200);
  until('A cart opens again', PROPAGATION, () => cart(a, a1), [200, 201]);
  until('A POS session opens again', PROPAGATION, () => posSession(a, a1), 201);
  until('A POS order placed again', PROPAGATION, () => posOrder(a, a1), 201);
  until('A online order placed again', PROPAGATION, () => onlineOrder(a, a1), 201);
  until('A second store trades again', PROPAGATION, () => posOrder(a, a2), 201);

  report.push('── 6. owner closes one store ──');
  const cartBefore = data(call('POST', '/api/cart-svc/cart', { token: a.manager.token, body: { storeId: a1 } })).id;
  const sessionBefore = data(call('POST', '/api/iam-svc/auth/pos/sessions', { token: a.owner.token, body: { storeId: a1 } })).id;
  truthy('a cart and a POS session are open before the closure', cartBefore && sessionBefore);
  expect(setStoreStatus(a, a1, 'CLOSED'), 'owner closes store 1', 200);
  until('closed store: cart refused', PROPAGATION, () => cart(a, a1), 409, 'STORE_NOT_OPERATIONAL');
  until(
    'closed store: adding to an open cart refused',
    PROPAGATION,
    () => call('POST', '/api/cart-svc/cart/items', { token: a.manager.token, body: { cartId: cartBefore, variantId: a.variantId, qty: 1 } }),
    409,
    'STORE_NOT_OPERATIONAL'
  );
  until('closed store: POS session refused', PROPAGATION, () => posSession(a, a1), 409, 'STORE_NOT_OPERATIONAL');
  until('closed store: POS order refused', PROPAGATION, () => posOrder(a, a1), 409, 'STORE_NOT_OPERATIONAL');
  until('closed store: online order refused', PROPAGATION, () => onlineOrder(a, a1), 409, 'STORE_NOT_OPERATIONAL');
  until(
    "closed store: the open POS session was ended",
    PROPAGATION,
    () => call('PUT', `/api/iam-svc/auth/pos/sessions/${sessionBefore}/activity`, { token: a.owner.token }),
    409,
    'POS_SESSION_NOT_ACTIVE'
  );
  until('closed store: storefront no longer lists it', PROPAGATION, () => {
    const listed = activeStorefrontStores(a);
    return { status: !listed.includes(a1) && listed.includes(a2) ? 200 : 0, body: JSON.stringify(listed) };
  }, 200);
  everythingOpen(a, a2, 'other A store unaffected:');
  now('A storefront still serves', () => storefront(a), 200);
  now('A owner still logs in', () => ownerLogin(a), 200);
  now('B unaffected by the closure', () => posOrder(b, b1), 201);

  report.push('── 7. owner reopens the store ──');
  expect(setStoreStatus(a, a1, 'ACTIVE'), 'owner reopens store 1', 200);
  until('reopened store: cart opens', PROPAGATION, () => cart(a, a1), [200, 201]);
  until('reopened store: POS session opens', PROPAGATION, () => posSession(a, a1), 201);
  until('reopened store: POS order placed', PROPAGATION, () => posOrder(a, a1), 201);
  until('reopened store: online order placed', PROPAGATION, () => onlineOrder(a, a1), 201);

  sleep(0.1);
  console.log(`\nRUNTIME FLOW GUARD (run ${uniq()})\n${report.join('\n')}`);
}
