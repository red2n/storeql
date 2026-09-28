// Shared plumbing for the k6 suites: one way to call the gateway, read the response envelope,
// onboard a tenant and wait for the asynchronous parts of the platform to catch up.
//
// Every call goes through the gateway with a real JWT. The gateway strips client-supplied
// X-Tenant-Id / X-User-Id / X-Roles, so a script that sends those instead of a token is testing
// nothing.
// Under its own name: the global `crypto` is WebCrypto, which newId() and the software passkey use.
import k6crypto from 'k6/crypto';
import http from 'k6/http';
import { check, sleep } from 'k6';
import encoding from 'k6/encoding';

export const BASE = __ENV.BASE_URL || 'http://localhost:8090';
// Fifteen characters or more: a password that is the only factor (NIST SP 800-63B-4).
export const PASSWORD = 'K6-Passw0rd! for storeql';

const UUID_SEGMENT = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi;

/**
 * One request through the gateway.
 *
 * @param method HTTP method
 * @param path path starting with /api/
 * @param opts.body object (sent as JSON) or string
 * @param opts.token bearer token
 * @param opts.storefront tenant id for the storefront seam (X-Storefront-Tenant)
 * @param opts.idem true for a fresh Idempotency-Key, or the key to send
 * @param opts.headers extra headers
 */
export function call(method, path, opts = {}) {
  const headers = { 'Content-Type': 'application/json', Accept: 'application/json' };
  if (opts.token) headers.Authorization = `Bearer ${opts.token}`;
  if (opts.storefront) headers['X-Storefront-Tenant'] = opts.storefront;
  if (opts.idem) headers['Idempotency-Key'] = opts.idem === true ? newKey() : opts.idem;
  Object.assign(headers, opts.headers || {});
  let body = null;
  if (opts.body !== undefined && opts.body !== null) {
    body = typeof opts.body === 'string' ? opts.body : JSON.stringify(opts.body);
  }
  // Ids in the URL would give every request its own metric series.
  const name = `${method} ${path.split('?')[0].replace(UUID_SEGMENT, '{id}')}`;
  return http.request(method, `${BASE}${path}`, body, { headers, tags: { name } });
}

function envelope(res) {
  try {
    return JSON.parse(res.body) || {};
  } catch (_) {
    return {};
  }
}

/** The envelope's data, or {} when there is none. */
export function data(res) {
  const d = envelope(res).data;
  return d === undefined || d === null ? {} : d;
}

/** The envelope's error code, or null. */
export function errorCode(res) {
  return (envelope(res).error || {}).code || null;
}

export function nextCursor(res) {
  return (envelope(res).meta || {}).nextCursor || null;
}

export function claims(token) {
  try {
    const b64 = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    return JSON.parse(encoding.b64decode(b64, 'rawstd', 's'));
  } catch (_) {
    return {};
  }
}

let counter = 0;
/**
 * Unique within a run and across runs: millis + VU-local counter + random suffix, in base 36.
 *
 * <p>Base 36 on purpose. In decimal this was a run of fifteen to nineteen digits, and about one in
 * ten of those holds a Luhn-valid card number somewhere inside it — which the gateway's card-data
 * guard rightly refuses (400 CARD_DATA_NOT_ACCEPTED), failing whichever suite drew it. The millis
 * in base 36 begin with a letter, so no run of thirteen digits can form.
 */
export function uniq() {
  counter += 1;
  return `${Date.now().toString(36)}${counter.toString(36)}${Math.floor(Math.random() * 46656).toString(36)}`;
}

/**
 * An Idempotency-Key: a UUIDv7, the only kind StoreQL accepts (any other is 400
 * IDEMPOTENCY_KEY_INVALID). A fresh one per attempt at a write; pass the same one to retry it.
 * The argument only says, to the reader, what the key is for.
 */
// eslint-disable-next-line no-unused-vars
export function newKey(purpose = 'k6') {
  return newId();
}

/**
 * A UUIDv7 for ids a script sends that end up stored (event ids, client-chosen ids): StoreQL keeps
 * only v7 ids, and the database audit counts anything else as a defect.
 */
export function newId() {
  const time = Date.now().toString(16).padStart(12, '0');
  const rnd = Array.from(crypto.getRandomValues(new Uint8Array(10)), (b) => b.toString(16).padStart(2, '0')).join('');
  const variant = ((parseInt(rnd[3], 16) & 0x3) | 0x8).toString(16);
  return `${time.slice(0, 8)}-${time.slice(8)}-7${rnd.slice(0, 3)}-${variant}${rnd.slice(4, 7)}-${rnd.slice(7, 19)}`;
}

/**
 * Check the status (one or a list) and, when given, the error code. On failure the response is
 * logged, so a red check says why without re-running.
 */
export function expect(res, label, status, code) {
  const statuses = Array.isArray(status) ? status : [status];
  const ok = check(res, {
    [label]: (r) => statuses.includes(r.status) && (!code || errorCode(r) === code),
  });
  if (!ok) {
    const got = `${res.status}${errorCode(res) ? ` ${errorCode(res)}` : ''}`;
    const want = `${statuses.join('/')}${code ? ` ${code}` : ''}`;
    console.error(`✗ ${label}: want ${want}, got ${got} — ${String(res.body).slice(0, 400)}`);
  }
  return ok;
}

/** A check on a value rather than a response. */
export function truthy(label, value, detail) {
  const ok = check(null, { [label]: () => Boolean(value) });
  if (!ok && detail !== undefined) console.error(`✗ ${label}: ${JSON.stringify(detail).slice(0, 400)}`);
  return ok;
}

/** Fail setup or an iteration outright when a prerequisite call did not succeed. */
export function must(res, status, what) {
  const statuses = Array.isArray(status) ? status : [status];
  if (!statuses.includes(res.status)) {
    throw new Error(`${what}: ${res.status} ${String(res.body).slice(0, 400)}`);
  }
  return data(res);
}

/** Call fn once a second until it returns truthy or `seconds` pass; seconds taken, or -1. */
export function poll(seconds, fn, interval = 1) {
  const deadline = Date.now() + seconds * 1000;
  const start = Date.now();
  for (;;) {
    if (fn()) return Math.round((Date.now() - start) / 1000);
    if (Date.now() >= deadline) return -1;
    sleep(interval);
  }
}

// ── identities ────────────────────────────────────────────────────────────────

export function register(label) {
  const email = `${label}-${uniq()}@k6.storeql.test`;
  const res = call('POST', '/api/iam-svc/auth/register', { body: { email, password: PASSWORD } });
  const d = must(res, 201, `register ${email}`);
  return { email, password: PASSWORD, token: d.accessToken, refreshToken: d.refreshToken, userId: claims(d.accessToken).sub };
}

export function login(user) {
  return call('POST', '/api/iam-svc/auth/login', { body: { email: user.email, password: user.password } });
}

/**
 * Log in until the token's claims satisfy `wanted` — role grants and tenant binding arrive over
 * Kafka after the call that caused them. Updates the user's tokens and returns the claims.
 */
export function signInUntil(user, wanted, seconds = 90) {
  let last = null;
  const took = poll(seconds, () => {
    last = login(user);
    const token = data(last).accessToken;
    if (!token || !wanted(claims(token))) return false;
    user.token = token;
    user.refreshToken = data(last).refreshToken;
    return true;
  });
  if (took < 0) throw new Error(`${user.email} never got the expected claims: ${last && last.status} ${last && last.body}`);
  return claims(user.token);
}

const BASE32 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';

/** Base 32 (RFC 4648, padding and spaces forgiven) to bytes. */
function fromBase32(text) {
  const clean = String(text).replace(/[\s=-]/g, '').toUpperCase();
  const out = [];
  let buffer = 0;
  let bits = 0;
  for (const ch of clean) {
    const value = BASE32.indexOf(ch);
    if (value < 0) throw new Error('not base 32');
    buffer = (buffer << 5) | value;
    bits += 5;
    if (bits >= 8) {
      out.push((buffer >> (bits - 8)) & 0xff);
      bits -= 8;
    }
  }
  return new Uint8Array(out).buffer;
}

/**
 * The code an authenticator app shows for a base-32 secret (RFC 6238: HMAC-SHA-1, six digits,
 * thirty seconds), `stepsFromNow` time steps away — the server accepts one either side of now, and
 * no code twice, so a flow that signs in more than once asks for the next step's code.
 */
export function totp(secretBase32, stepsFromNow = 0) {
  const step = Math.floor(Date.now() / 30000) + stepsFromNow;
  const counter = new Uint8Array(8);
  let n = step;
  for (let i = 7; i >= 0; i--) {
    counter[i] = n & 0xff;
    n = Math.floor(n / 256);
  }
  const hex = k6crypto.hmac('sha1', fromBase32(secretBase32), counter.buffer, 'hex');
  const offset = parseInt(hex.slice(-1), 16);
  const binary = parseInt(hex.substr(offset * 2, 8), 16) & 0x7fffffff;
  return String(binary % 1000000).padStart(6, '0');
}

/** Answer a sign-in's second factor (20.12). */
export function answerSecondFactor(mfaToken, method, code) {
  return call('POST', '/api/iam-svc/auth/mfa/login', { body: { mfaToken, method, code } });
}

/**
 * Sign in as the platform administrator, who is born with an authenticator (20.12): the password,
 * then a code. Suites run one after another against the same account and no code works twice, so
 * a code the server has already seen is answered with the next step's, and failing that the wait
 * is for the step to turn.
 */
export function platformAdmin() {
  const email = __ENV.PLATFORM_ADMIN_EMAIL;
  const password = __ENV.PLATFORM_ADMIN_PASSWORD;
  const secret = __ENV.PLATFORM_ADMIN_TOTP_SECRET;
  if (!email || !password) {
    throw new Error('set PLATFORM_ADMIN_EMAIL and PLATFORM_ADMIN_PASSWORD (k6/run.sh reads them from .env)');
  }
  let last = null;
  for (let attempt = 0; attempt < 8; attempt++) {
    const first = must(call('POST', '/api/iam-svc/auth/platform-login', { body: { email, password } }), 200, 'platform-login');
    if (first.accessToken && !first.mfaEnrolmentRequired) return { email, password, token: first.accessToken };
    if (!first.mfaRequired) throw new Error(`platform-login: the administrator has no second factor and must set one up: ${JSON.stringify(first).slice(0, 200)}`);
    if (!secret) throw new Error('set PLATFORM_ADMIN_TOTP_SECRET (k6/run.sh reads it from .env): the platform administrator signs in with a code');
    for (const stepsFromNow of [0, 1]) {
      last = answerSecondFactor(first.mfaToken, 'TOTP', totp(secret, stepsFromNow));
      if (last.status === 200) return { email, password, token: data(last).accessToken };
    }
    // Both of this moment's codes were used by a sign-in just before: wait for the step to turn.
    sleep(10);
  }
  throw new Error(`platform-login: the second factor was never accepted: ${last && last.status} ${last && last.body}`);
}

// ── tenants ───────────────────────────────────────────────────────────────────

const STORE_DEFAULTS = {
  GB: { city: 'London', pincode: 'EC1A 1BB', timezone: 'Europe/London' },
  IN: { city: 'Mumbai', pincode: '400001', timezone: 'Asia/Kolkata' },
  US: { city: 'Austin', pincode: '73301', timezone: 'America/Chicago' },
  DE: { city: 'Berlin', pincode: '10117', timezone: 'Europe/Berlin' },
  PT: { city: 'Lisboa', pincode: '1100-148', timezone: 'Europe/Lisbon' },
  FR: { city: 'Paris', pincode: '75001', timezone: 'Europe/Paris' },
  PL: { city: 'Warszawa', pincode: '00-001', timezone: 'Europe/Warsaw' },
};

/**
 * Register an owner, create their tenant, wait until their token carries the tenant and OWNER,
 * then add `stores` stores (the first is the default store, with its DEFAULT zone).
 */
export function onboardTenant(label, { country = 'GB', currency = 'GBP', stores = 1 } = {}) {
  const owner = register(`${label}-owner`);
  const run = uniq();
  const created = call('POST', '/api/tenant-svc/onboarding/tenants', {
    token: owner.token,
    body: { businessName: `${label} ${run}`, legalName: `${label} ${run} Ltd`, country, currency },
  });
  const tenantId = must(created, 201, `create tenant ${label}`).id;
  signInUntil(owner, (c) => c.tenant === tenantId && (c.roles || []).includes('OWNER'));

  const tenant = { label, tenantId, country, currency, owner, stores: [] };
  for (let i = 0; i < stores; i++) tenant.stores.push(addStore(tenant, `S${i + 1}`));
  return tenant;
}

export function addStore(tenant, suffix, type = 'STORE') {
  const place = STORE_DEFAULTS[tenant.country] || STORE_DEFAULTS.GB;
  const code = `${suffix}-${uniq()}`.slice(0, 32);
  const res = call('POST', '/api/tenant-svc/admin/stores', {
    token: tenant.owner.token,
    body: { name: `${tenant.label} ${suffix}`, code, type, line1: '1 High Street', country: tenant.country, ...place },
  });
  const store = must(res, 201, `create store ${code}`);
  return { id: store.id, code, name: store.name };
}

/** A staff login in the tenant with `role` at `storeIds`, signed in with that role in its token. */
export function staffUser(tenant, role, storeIds) {
  const user = register(`${tenant.label}-${role.toLowerCase()}`);
  for (const storeId of storeIds) {
    const res = call('POST', '/api/tenant-svc/admin/staff', {
      token: tenant.owner.token,
      body: { userId: user.userId, storeId, role },
    });
    must(res, 201, `assign ${role}`);
  }
  signInUntil(user, (c) => c.tenant === tenant.tenantId && (c.roles || []).includes(role));
  return user;
}

export function setTenantStatus(admin, tenantId, status) {
  return call('PATCH', `/api/tenant-svc/platform/tenants/${tenantId}/status`, { token: admin.token, body: { status } });
}

export function setStoreStatus(tenant, storeId, status) {
  return call('PATCH', `/api/tenant-svc/admin/stores/${storeId}/status`, { token: tenant.owner.token, body: { status } });
}

// ── catalogue and stock ───────────────────────────────────────────────────────

/** A product with one variant, sellable online and at the till. Returns { productId, variantId, sku }. */
/**
 * What an online offer shows about a product (01.12): an EU manufacturer and no warnings. Sent for
 * every tenant; where EU product safety law binds one, a product cannot be offered online without it.
 */
export const SAFETY_INFORMATION = {
  manufacturerName: 'k6 Manufacturing GmbH',
  manufacturerAddress: 'Teststraße 1, 10115 Berlin',
  manufacturerContact: 'safety@k6.storeql.test',
  manufacturerCountry: 'DE',
  noWarnings: true,
};

export function sellableVariant(tenant, name, extra = {}) {
  const t = tenant.owner.token;
  const run = uniq();
  const product = must(
    call('POST', '/api/product-svc/admin/products', {
      token: t,
      body: { name: `${name} ${run}`, sellableOnline: true, sellablePos: true, safetyInformation: SAFETY_INFORMATION, ...extra },
    }),
    201,
    `create product ${name}`
  );
  const sku = `SKU-${run}`.slice(0, 40);
  const variant = must(
    call('POST', `/api/product-svc/admin/products/${product.id}/variants`, {
      token: t,
      body: { sku, barcode: `${run}`.slice(0, 13), unit: 'PCS' },
    }),
    201,
    `create variant ${sku}`
  );
  return { productId: product.id, variantId: variant.id, sku };
}

/**
 * An active all-channel price list in the tenant's own currency — order-svc resolves prices from
 * it and refuses an order whose currency differs from the tenant's.
 */
// The standard VAT rate a test business in each country charges. pricing-svc never assumes one
// (SJ-D56): a business sets its own, exempt if it charges none — as Kuwait's and a US seller's do.
const STANDARD_VAT = { GB: 0.2, IN: 0.18, US: 0, DE: 0.19, PT: 0.23, FR: 0.2, JP: 0.1, KW: 0, PL: 0.23 };

/** Sets the business's standard VAT rate (T1) unless it already has one: nothing is quoted without it. */
export function ensureStandardVat(tenant) {
  if (tenant.standardVat) return;
  const token = tenant.owner.token;
  if (call('GET', '/api/pricing-svc/vat-rates/T1', { token }).status !== 200) {
    const rate = STANDARD_VAT[tenant.country];
    if (rate === undefined) throw new Error(`no standard VAT rate known for ${tenant.country}`);
    must(
      call('POST', '/api/pricing-svc/vat-rates', {
        token,
        body: { code: 'T1', name: 'Standard rate', rate, exempt: rate === 0, effectiveFrom: '2020-01-01T00:00:00Z' },
      }),
      201,
      'standard VAT rate'
    );
  }
  tenant.standardVat = true;
}

export function priceVariants(tenant, variantIds, price = '25.00') {
  ensureStandardVat(tenant);
  const t = tenant.owner.token;
  const list = must(
    call('POST', '/api/pricing-svc/admin/price-lists', {
      token: t,
      body: { name: `k6 ${uniq()}`, currency: tenant.currency, effectiveFrom: new Date(Date.now() - 86400000).toISOString() },
    }),
    201,
    'create price list'
  );
  must(
    call('POST', `/api/pricing-svc/admin/price-lists/${list.id}/items/batch`, {
      token: t,
      body: { items: variantIds.map((variantId) => ({ variantId, price, minQty: 1 })) },
    }),
    [200, 201],
    'price list items'
  );
  return list.id;
}

export function receive(tenant, storeId, variantId, qty, costPrice = '10.00') {
  return call('POST', '/api/inventory-svc/admin/inventory/receive', {
    token: tenant.owner.token,
    idem: true,
    body: { storeId, variantId, qty, batchNo: `B-${uniq()}`.slice(0, 32), costPrice },
  });
}

/** k6 thresholds shared by the functional suites: every check must pass. */
export const ALL_CHECKS_PASS = { checks: ['rate==1.0'] };

/**
 * A tenant selling one priced, stocked variant from its first store, with a rival tenant and the two
 * staff roles the refusal checks need: the setup the ledger and margin flows share.
 */
export function sellingTenant(label, { price = '12.00', qty = 50, costPrice = '6.00', country = 'GB', currency = 'GBP' } = {}) {
  const tenant = onboardTenant(label, { country, currency });
  const rival = onboardTenant(`${label}-rival`, { country, currency });
  const store = tenant.stores[0];
  const { variantId } = sellableVariant(tenant, `${label} widget`);
  priceVariants(tenant, [variantId], price);
  must(receive(tenant, store.id, variantId, qty, costPrice), [200, 201], 'receive stock');
  const storekeeper = staffUser(tenant, 'STOREKEEPER', [store.id]);
  const cashier = staffUser(tenant, 'CASHIER', [store.id]);
  return { tenant, rival, store, variantId, storekeeper, cashier };
}
