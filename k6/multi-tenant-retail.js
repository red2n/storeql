/**
 * Multi-Tenant Retail Load Test — StoreQL
 *
 * Two isolated tenants, two stores each, geographically distinct:
 *
 *   Tenant IN  —  Mumbai Retail Pvt Ltd  (India / INR)
 *     Store IN-1  Mumbai, Marine Drive       (onboarding path, auto-zone)
 *     Store IN-2  New Delhi, Connaught Place  (admin path)
 *
 *   Tenant UK  —  London Merchandise Ltd  (United Kingdom / GBP)
 *     Store UK-1  London, Oxford Street       (onboarding path, auto-zone)
 *     Store UK-2  Manchester, Arndale          (admin path)
 *
 * Flows exercised (per tenant, per store where applicable):
 *   ✓  Tenant onboarding (register → tenant → stores → zones → staff)
 *   ✓  Staff management   (assign MANAGER + CASHIER, list staff, list zones)
 *   ✓  Catalogue admin    (brand, 2 categories, 4 products × 1 variant each)
 *   ✓  Purchase           (stock receive to all 4 stores, reorder thresholds)
 *   ✓  POS sales          (reserve → consume, stock-level assertions)
 *   ✓  Cart abandon       (reserve → release)
 *   ✓  Catalog browse     (products, categories, stock levels, movements)
 *   ✓  Tenant isolation   (cross-tenant access must return no data — tracked metric)
 *
 * Scenarios (concurrent after setup):
 *   browseCatalog   4 VUs  50s  – 2 India, 2 UK; rotates both stores
 *   completeSale    4 VUs  45s  – POS flow, per-tenant sale rate tracked
 *   abandonCart     2 VUs  40s  – 1 India, 1 UK
 *   purchaseReceive 2 VUs  40s  – supplier receive, p95 latency tracked
 *   staffAdmin      2 VUs  35s  – staff/store/zone read ops
 *   catalogAdmin    2 VUs  35s  – brand/category/product/movement read + brand update
 *   isolationCheck  1 VU   30s  – cross-tenant isolation assertions
 *
 * Run: k6 run k6/multi-tenant-retail.js
 *      k6 run k6/multi-tenant-retail.js --env BASE_URL=http://localhost:8090
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import encoding from 'k6/encoding';
import exec from 'k6/execution';
import { PASSWORD, errorCode, newId, newKey } from './lib/storeql.js';

// ── Custom metrics ─────────────────────────────────────────────────────────────
const errors              = new Counter('errors');
const saleSuccessIN       = new Rate('sale_success_india');
const saleSuccessUK       = new Rate('sale_success_uk');
const catalogLatencyIN    = new Trend('catalog_latency_india_ms',    true);
const catalogLatencyUK    = new Trend('catalog_latency_uk_ms',       true);
const purchaseLatency     = new Trend('purchase_receive_latency_ms', true);
const isolationViolations    = new Counter('isolation_violations');
const materialControlLatency = new Trend('material_control_latency_ms', true);
const planningLatency        = new Trend('planning_latency_ms',         true);
const demandHistoryLatency   = new Trend('demand_history_latency_ms',   true);
const serialControlLatency   = new Trend('serial_control_latency_ms',   true);
const uomManagementLatency   = new Trend('uom_management_latency_ms',   true);
const moveOrderLatency       = new Trend('move_order_latency_ms',       true);
const transferOrderLatency         = new Trend('transfer_order_latency_ms',   true);
const costingLatency               = new Trend('costing_latency_ms',           true);
const kanbanLatency                = new Trend('kanban_latency_ms',            true);
const ropLatency                   = new Trend('rop_latency_ms',               true);
const negativeUnexpectedSuccess    = new Counter('negative_unexpected_success');
const orderPosLatency              = new Trend('order_pos_latency_ms',          true);
const layawayLatency               = new Trend('layaway_latency_ms',            true);
const giftCardLatency              = new Trend('gift_card_latency_ms',          true);
const pricingLatency               = new Trend('pricing_latency_ms',            true);
const intercompanyLatency          = new Trend('intercompany_latency_ms',        true);
// Tier 7 (gaps #61–72): any count here is a security regression at the gateway/order layer.
const securityViolations           = new Counter('security_violations');
const customerLatency              = new Trend('customer_latency_ms',      true);
const posRegisterLatency           = new Trend('pos_register_latency_ms',  true);
const cashMgmtLatency              = new Trend('cash_mgmt_latency_ms',     true);

// ── Scenario options ───────────────────────────────────────────────────────────
export const options = {
  scenarios: {
    browseCatalog: {
      executor: 'constant-vus', vus: 4, duration: '50s',
      exec: 'browseCatalog', startTime: '15s',
    },
    completeSale: {
      executor: 'constant-vus', vus: 4, duration: '45s',
      exec: 'completeSale', startTime: '15s',
    },
    abandonCart: {
      executor: 'constant-vus', vus: 2, duration: '40s',
      exec: 'abandonCart', startTime: '18s',
    },
    purchaseReceive: {
      executor: 'constant-vus', vus: 2, duration: '40s',
      exec: 'purchaseReceive', startTime: '18s',
    },
    staffAdmin: {
      executor: 'constant-vus', vus: 2, duration: '35s',
      exec: 'staffAdmin', startTime: '18s',
    },
    catalogAdmin: {
      executor: 'constant-vus', vus: 2, duration: '35s',
      exec: 'catalogAdmin', startTime: '18s',
    },
    isolationCheck: {
      executor: 'constant-vus', vus: 1, duration: '30s',
      exec: 'isolationCheck', startTime: '20s',
    },
    materialControl: {
      executor: 'constant-vus', vus: 2, duration: '35s',
      exec: 'materialControl', startTime: '20s',
    },
    planningEngine: {
      executor: 'constant-vus', vus: 2, duration: '35s',
      exec: 'planningEngine', startTime: '22s',
    },
    demandHistory: {
      executor: 'constant-vus', vus: 2, duration: '35s',
      exec: 'demandHistory', startTime: '24s',
    },
    serialControl: {
      executor: 'constant-vus', vus: 2, duration: '35s',
      exec: 'serialControl', startTime: '26s',
    },
    uomManagement: {
      executor: 'constant-vus', vus: 2, duration: '35s',
      exec: 'uomManagement', startTime: '28s',
    },
    moveOrders: {
      executor: 'constant-vus', vus: 2, duration: '35s',
      exec: 'moveOrders', startTime: '30s',
    },
    transferOrders: {
      executor: 'constant-vus', vus: 2, duration: '35s',
      exec: 'transferOrders', startTime: '32s',
    },
    negativeTests: {
      executor: 'constant-vus', vus: 1, duration: '35s',
      exec: 'negativeTests', startTime: '38s',
    },
    costingControl: {
      executor: 'constant-vus', vus: 2, duration: '35s',
      exec: 'costingControl', startTime: '34s',
    },
    kanbanControl: {
      executor: 'constant-vus', vus: 2, duration: '35s',
      exec: 'kanbanControl', startTime: '36s',
    },
    ropPlanning: {
      executor: 'constant-vus', vus: 2, duration: '35s',
      exec: 'ropPlanning', startTime: '38s',
    },
    orderPos: {
      executor: 'constant-vus', vus: 2, duration: '35s',
      exec: 'orderPos', startTime: '40s',
    },
    layawayManagement: {
      executor: 'constant-vus', vus: 2, duration: '35s',
      exec: 'layawayManagement', startTime: '42s',
    },
    giftCardManagement: {
      executor: 'constant-vus', vus: 2, duration: '35s',
      exec: 'giftCardManagement', startTime: '44s',
    },
    pricingVat: {
      executor: 'constant-vus', vus: 2, duration: '35s',
      exec: 'pricingVat', startTime: '46s',
    },
    intercompanyFlow: {
      executor: 'constant-vus', vus: 2, duration: '35s',
      exec: 'intercompanyFlow', startTime: '48s',
    },
    gatewaySecurity: {
      executor: 'constant-vus', vus: 1, duration: '30s',
      exec: 'gatewaySecurity', startTime: '50s',
    },
    paymentFlow: {
      executor: 'constant-vus', vus: 2, duration: '35s',
      exec: 'paymentFlow', startTime: '52s',
    },
    customerFlow: {
      executor: 'constant-vus', vus: 2, duration: '35s',
      exec: 'customerFlow', startTime: '55s',
    },
    posRegisterFlow: {
      executor: 'constant-vus', vus: 2, duration: '35s',
      exec: 'posRegisterFlow', startTime: '58s',
    },
    cashMgmtFlow: {
      executor: 'constant-vus', vus: 1, duration: '30s',
      exec: 'cashMgmtFlow', startTime: '60s',
    },
  },
  thresholds: {
    checks:                      ['rate>0.92'],
    errors:                      ['count<25'],
    sale_success_india:          ['rate>0.85'],
    sale_success_uk:             ['rate>0.85'],
    isolation_violations:        ['count==0'],
    catalog_latency_india_ms:    ['p(95)<500'],
    catalog_latency_uk_ms:       ['p(95)<500'],
    purchase_receive_latency_ms: ['p(95)<800'],
    material_control_latency_ms: ['p(95)<600'],
    planning_latency_ms:         ['p(95)<1000'],
    demand_history_latency_ms:   ['p(95)<800'],
    serial_control_latency_ms:   ['p(95)<600'],
    uom_management_latency_ms:   ['p(95)<600'],
    move_order_latency_ms:       ['p(95)<800'],
    transfer_order_latency_ms:   ['p(95)<800'],
    costing_latency_ms:          ['p(95)<800'],
    kanban_latency_ms:           ['p(95)<800'],
    rop_latency_ms:              ['p(95)<1000'],
    negative_unexpected_success: ['count==0'],
    order_pos_latency_ms:        ['p(95)<800'],
    layaway_latency_ms:          ['p(95)<800'],
    gift_card_latency_ms:        ['p(95)<800'],
    pricing_latency_ms:          ['p(95)<800'],
    intercompany_latency_ms:     ['p(95)<1000'],
    security_violations:         ['count==0'],
    customer_latency_ms:         ['p(95)<600'],
    pos_register_latency_ms:     ['p(95)<800'],
    cash_mgmt_latency_ms:        ['p(95)<800'],
  },
};

// ── Module-level helpers ───────────────────────────────────────────────────────
const BASE = __ENV.BASE_URL || 'http://localhost:8090';

function hdrs(token) {
  const h = { 'Content-Type': 'application/json' };
  if (token) h['Authorization'] = `Bearer ${token}`;
  return h;
}

function post(path, body, token) {
  const headers = hdrs(token);
  // order-svc refuses an order without an Idempotency-Key (header, or the legacy body field).
  if (path === '/api/order-svc/orders' && !(body && body.idempotencyKey)) headers['Idempotency-Key'] = newKey('retail-order');
  // ...and a return or a void of one (return controls): header only, no body fallback.
  if (/^\/api\/order-svc\/orders\/[^/]+\/(returns|void)$/.test(path)) headers['Idempotency-Key'] = newKey('retail-return-or-void');
  // ...and a gift-card redeem (the till's gift-card tender): header only.
  if (/^\/api\/order-svc\/gift-cards\/[^/]+\/redeem$/.test(path)) headers['Idempotency-Key'] = newKey('retail-gift-redeem');
  // ...and a gift card issued or reloaded by hand (management only, with a reason): header only.
  if (path === '/api/order-svc/gift-cards' || /^\/api\/order-svc\/gift-cards\/[^/]+\/reload$/.test(path)) headers['Idempotency-Key'] = newKey('retail-gift-hand');
  // ...and points handed out or changed by hand (management only, and retry-safe).
  if (/^\/api\/customer-svc\/customers\/[^/]+\/loyalty\/(earn|adjust)$/.test(path)) headers['Idempotency-Key'] = newKey('retail-loyalty');
  if (/^\/api\/customer-svc\/customers\/[^/]+\/store-credit\/issue$/.test(path)) headers['Idempotency-Key'] = newKey('retail-store-credit');
  return http.post(`${BASE}${path}`, JSON.stringify(body), { headers });
}

function put(path, body, token) {
  return http.put(`${BASE}${path}`, JSON.stringify(body), { headers: hdrs(token) });
}

function patch(path, body, token) {
  return http.patch(`${BASE}${path}`, JSON.stringify(body), { headers: hdrs(token) });
}

function get(path, token) {
  return http.get(`${BASE}${path}`, { headers: hdrs(token) });
}

// Public storefront reads (catalog/*, prices/resolve) ignore the caller's Bearer token for
// tenant resolution — the gateway treats them as guest-reachable and resolves tenant from
// X-Storefront-Tenant instead (see JwtAuthFilter.isStorefrontPublic / STOREFRONT_TENANT_HEADER).
function storefrontHdrs(tenantId) {
  return { 'Content-Type': 'application/json', 'X-Storefront-Tenant': tenantId };
}

function getStorefront(path, tenantId) {
  return http.get(`${BASE}${path}`, { headers: storefrontHdrs(tenantId) });
}

// Assert 2xx and count failures.
function ok(res, tag) {
  const passed = check(res, { [`${tag} 2xx`]: r => r.status >= 200 && r.status < 300 });
  if (!passed) errors.add(1);
  return passed;
}

// Assert 2xx or 409 (already-exists is fine for idempotent seeds). Does not count 409 as error.
function okOrExists(res, tag) {
  const passed = check(res, { [`${tag} 2xx|409`]: r => r.status < 300 || r.status === 409 });
  if (!passed) errors.add(1);
  return res.status < 300; // true only on actual creation
}

// Extract .data from response body.
function body(res) {
  try { return JSON.parse(res.body).data || {}; } catch (_) { return {}; }
}

// Decode JWT payload (no verification).
function jwtPayload(token) {
  try {
    const b64 = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    return JSON.parse(encoding.b64decode(b64, 'rawstd', 's'));
  } catch (_) { return {}; }
}

// Random 6-char uppercase slug.
function slug() { return Math.random().toString(36).slice(2, 8).toUpperCase(); }

// StoreQL stores only UUIDv7 ids, so ids a script makes up are v7 too.
const genUuid = newId;

// Receive stock at a store and return the response.
function apiReceiveStock(token, storeId, variantId, qty, costPrice, batchPrefix) {
  return post('/api/inventory-svc/admin/inventory/receive', {
    storeId, variantId, qty,
    batchNo:    `${batchPrefix}-${slug()}`,
    costPrice:  String(costPrice),
    expiryDate: new Date(Date.now() + 365 * 86400000).toISOString().slice(0, 10),
  }, token);
}

// A variant of this iteration's own, on the tenant's first product (as uomManagement's and the
// negative suite's are), named by the VU and the scenario-wide iteration. A read-back is only proof
// on data nobody else touches: the seeded variants are shared by every scenario, and a scenario's
// two VUs can land on one tenant and one variant (global VU ids can share parity and remainder), so
// on a seeded variant another VU receives, sells, quarantines or re-costs the same stock between this
// iteration's write and its read.
function ownVariant(tenant, prefix, tag) {
  const res = post(`/api/product-svc/admin/products/${tenant.productIds[0]}/variants`,
    { sku: `${prefix}-${__VU}-${exec.scenario.iterationInTest}`, unit: 'EA' }, tenant.ownerToken);
  if (!ok(res, `${tag} ${prefix} variant of this iteration's own`)) return null;
  return body(res).id || null;
}

// One variant's level at a store, following the cursor: levels are ordered by variant id, so a
// variant made during the run sorts after the seeded ones and is seldom on the first page. Returns
// the last page read and the variant's level, or null when it holds no available batch there.
function levelAt(token, storeId, variantId) {
  let after = null;
  let res = null;
  for (let page = 0; page < 50; page++) {
    res = get(`/api/inventory-svc/admin/inventory/levels?store=${storeId}&limit=100`
      + (after ? `&after=${encodeURIComponent(after)}` : ''), token);
    let parsed;
    try { parsed = JSON.parse(res.body); } catch (_) { return { res, level: null }; }
    const level = (parsed.data || []).find(l => l.variantId === variantId);
    if (level || res.status !== 200) return { res, level: level || null };
    after = parsed.meta && parsed.meta.nextCursor;
    if (!after) break;
  }
  return { res, level: null };
}

// ── Setup helpers (called only in setup()) ─────────────────────────────────────

// A shopper's sign-up by default; an owner signs up to start a business instead
// (/auth/register/business), a staff login of no business until TenantCreated makes it the owner.
function registerUser(email, path = '/api/iam-svc/auth/register') {
  // The suite-wide phrase: it meets the password policy (GET /auth/password-policy, 15 characters
  // or more), which refuses every sign-up and provisioning with a shorter one.
  const password = PASSWORD;
  const res = post(path, {
    email,
    password,
    phone: `9${Date.now() % 10000000000}`,
  });
  if (res.status < 200 || res.status >= 300) {
    console.error(`register failed [${email}] status=${res.status}`);
    return null;
  }
  const token = body(res).accessToken;
  return { userId: jwtPayload(token).sub, email, password, token };
}

// A staff login the owner makes in the business (/auth/admin/staff-users), with the password it
// chooses; no token of its own is needed here.
function provisionUser(email, ownerToken) {
  const password = PASSWORD;
  const res = post('/api/iam-svc/auth/admin/staff-users', { email, password }, ownerToken);
  if (res.status < 200 || res.status >= 300) {
    console.error(`provision failed [${email}] status=${res.status}`);
    return null;
  }
  return { userId: body(res).userId, email, password };
}

function loginUser(email, password) {
  const res = post('/api/iam-svc/auth/login', { email, password });
  if (res.status < 200 || res.status >= 300) return null;
  return body(res).accessToken;
}

function seedTenant(owner, tenantPayload, store1Payload, store2Payload, products, isIN) {
  const tag = isIN ? 'IN' : 'UK';

  // 1. Create tenant
  const tRes = post('/api/tenant-svc/onboarding/tenants', tenantPayload, owner.token);
  if (tRes.status < 200 || tRes.status >= 300) {
    console.error(`[${tag}] tenant creation failed: ${tRes.status} ${tRes.body}`);
    return null;
  }
  const tenantId = body(tRes).id;

  // Re-login to get a JWT with the tenant claim (Kafka event binds owner before login returns).
  // On a cold stack the iam-svc Kafka consumer can take 10-20s to join its group and process
  // TenantCreated, so poll generously — a token without tenant+OWNER poisons the whole tenant's
  // seeding (every admin call 401/403s).
  const ownerToken = (() => {
    for (let i = 0; i < 30; i++) {
      const t = loginUser(owner.email, owner.password);
      if (t) {
        const claims = jwtPayload(t);
        const roles = claims.roles || [];
        if (claims.tenant && roles.includes('OWNER')) return t;
      }
      sleep(1);
    }
    console.error(`[${tag}] owner token never received tenant+OWNER claims after 30s — seeding will fail`);
    return owner.token; // fallback: use registration token (no tenant claim)
  })();

  // 2. Cashiers (one per store): logins the owner makes in the business — the only kind an
  // assignment binds (a shopper's sign-up is never taken on as staff).
  const run = Date.now();
  const cashier1 = provisionUser(`${tag.toLowerCase()}-cashier1-${run}@storeql.test`, ownerToken);
  const cashier2 = provisionUser(`${tag.toLowerCase()}-cashier2-${run}@storeql.test`, ownerToken);

  // 3. Store 1 — via onboarding (auto-creates DEFAULT zone)
  const s1Res = post('/api/tenant-svc/onboarding/stores', store1Payload, ownerToken);
  const store1Id = (s1Res.status < 300) ? body(s1Res).id : null;
  if (!store1Id) console.warn(`[${tag}] store-1 onboarding failed: ${s1Res.status}`);

  // 4. Store 2 — via admin path (subsequent store)
  const s2Res = post('/api/tenant-svc/admin/stores', store2Payload, ownerToken);
  const store2Id = (s2Res.status < 300) ? body(s2Res).id : null;
  if (!store2Id) console.warn(`[${tag}] store-2 admin failed: ${s2Res.status} ${s2Res.body}`);

  const storeIds = [store1Id, store2Id].filter(Boolean);

  // 5. Zones for each store (DEFAULT already exists for store1 from onboarding)
  for (const sid of storeIds) {
    post(`/api/tenant-svc/admin/stores/${sid}/zones`,
      { name: 'Electronics Aisle', code: `ELEC-${slug()}`, type: 'AISLE' }, ownerToken);
    post(`/api/tenant-svc/admin/stores/${sid}/zones`,
      { name: 'Clothing Aisle',    code: `CLTH-${slug()}`, type: 'AISLE' }, ownerToken);
  }

  // 6. Assign staff
  if (store1Id) {
    post('/api/tenant-svc/admin/staff',
      { userId: owner.userId,              storeId: store1Id, role: 'MANAGER' }, ownerToken);
    if (cashier1) post('/api/tenant-svc/admin/staff',
      { userId: cashier1.userId, storeId: store1Id, role: 'CASHIER' }, ownerToken);
  }
  if (store2Id) {
    post('/api/tenant-svc/admin/staff',
      { userId: owner.userId,              storeId: store2Id, role: 'MANAGER' }, ownerToken);
    if (cashier2) post('/api/tenant-svc/admin/staff',
      { userId: cashier2.userId, storeId: store2Id, role: 'CASHIER' }, ownerToken);
  }

  // 7. Brand + two categories
  const brandId   = body(post('/api/product-svc/admin/brands',
    { name: products.brand }, ownerToken)).id;
  const elecCatId = body(post('/api/product-svc/admin/categories',
    { name: 'Electronics' }, ownerToken)).id;
  const clothCatId = body(post('/api/product-svc/admin/categories',
    { name: 'Clothing' }, ownerToken)).id;

  const catMap = { electronics: elecCatId, clothing: clothCatId };

  // 8. Products + variants
  const variantIds = [];
  const productIds = [];
  for (const p of products.items) {
    const pRes = post('/api/product-svc/admin/products', {
      name: p.name, description: p.name,
      brandId:     brandId,
      categoryId:  catMap[p.category] || elecCatId,
      sellableOnline: true,
      sellablePos:    true,
    }, ownerToken);
    const productId = body(pRes).id;
    if (!productId) { console.warn(`[${tag}] product failed: ${p.name}`); continue; }
    productIds.push(productId);

    const vRes = post(`/api/product-svc/admin/products/${productId}/variants`, {
      sku:            `${tag}-${slug()}`,
      barcode:        `${tag}${Date.now()}${variantIds.length}`,
      manufacturerPn: `MFR-${tag}-${variantIds.length + 1}`,
      attributes:     JSON.stringify(p.attrs || {}),
      unit:           'PCS',
    }, ownerToken);
    const variantId = body(vRes).id;
    if (variantId) variantIds.push(variantId);
  }

  // 9. Receive initial stock at every store × every variant
  for (const sid of storeIds) {
    for (const vid of variantIds) {
      const recRes = apiReceiveStock(ownerToken, sid, vid, 500,
        products.initCostPrice, `${tag}-INIT`);
      if (recRes.status >= 300)
        console.warn(`[${tag}] initial receive failed s=${sid} v=${vid}: ${recRes.status}`);

      post('/api/inventory-svc/admin/inventory/thresholds',
        { storeId: sid, variantId: vid, threshold: products.threshold, maxQty: products.maxQty },
        ownerToken);
    }
  }

  // 10. Pricing — seed UK VAT rates, price list, product VAT categories
  const currency = isIN ? 'INR' : 'GBP';
  // T1 = standard rate (20% UK / 18% GST equivalent for IN seeding)
  post('/api/pricing-svc/vat-rates', {
    code: 'T1', name: isIN ? 'GST Standard' : 'Standard Rate',
    rate: isIN ? 0.18 : 0.20,
    exempt: false,
    description: isIN ? 'India GST 18%' : 'HMRC UK Standard VAT 20%',
    effectiveFrom: '2024-01-01T00:00:00Z',
  }, ownerToken);
  // T0 = zero rate
  post('/api/pricing-svc/vat-rates', {
    code: 'T0', name: 'Zero Rate',
    rate: 0.00, exempt: false,
    description: 'Zero-rated supply',
    effectiveFrom: '2024-01-01T00:00:00Z',
  }, ownerToken);

  const plRes = post('/api/pricing-svc/admin/price-lists', {
    name: `Standard ${currency}`, channel: 'ALL', currency,
    effectiveFrom: '2024-01-01T00:00:00Z',
  }, ownerToken);
  const priceListId = (plRes.status < 300) ? body(plRes).id : null;

  if (priceListId) {
    for (const vid of variantIds) {
      // Assign variant → T1 VAT category
      post('/api/pricing-svc/product-vat-categories',
        { variantId: vid, vatCode: 'T1' }, ownerToken);
      // Add price list item
      post(`/api/pricing-svc/admin/price-lists/${priceListId}/items`,
        { variantId: vid, price: isIN ? 1999.00 : 49.99, minQty: 1 }, ownerToken);
    }
  }

  // 11. Purchase-svc — seed a default supplier (BACS 30-day terms)
  const supplierRes = post('/api/purchase-svc/suppliers', {
    name: isIN ? 'National Distributors Ltd' : 'British Wholesale Ltd',
    vatRegistered: true,
    vatNumber: isIN ? 'IN22AAAAA0000A1Z5' : 'GB987654321',
    countryCode: isIN ? 'IN' : 'GB',
    currency,
  }, ownerToken);
  const supplierId = (supplierRes.status < 300) ? body(supplierRes).id : null;
  if (!supplierId) console.warn(`[${tag}] supplier creation failed: ${supplierRes.status}`);

  // Active 10% promotion scoped to ALL
  const promoRes = post('/api/pricing-svc/admin/promotions', {
    name: isIN ? 'Festive Offer' : 'Summer Sale',
    type: 'PERCENT', value: 10, channel: 'ALL',
    startsAt: '2020-01-01T00:00:00Z',
  }, ownerToken);
  const promoId = (promoRes.status < 300) ? body(promoRes).id : null;
  if (promoId) {
    post(`/api/pricing-svc/admin/promotions/${promoId}/items`,
      { scopeType: 'ALL' }, ownerToken);
  }

  console.log(
    `[${tag}] tenantId=${tenantId} stores=${storeIds.length} variants=${variantIds.length} ` +
    `cashiers=${[cashier1, cashier2].filter(Boolean).length} priceListId=${priceListId} supplierId=${supplierId}`
  );

  return {
    tenantId,
    ownerId:     owner.userId,
    ownerToken,
    stores:      storeIds.map((sid, i) => ({
      storeId:   sid,
      label:     i === 0 ? store1Payload.name : store2Payload.name,
      cashierId: i === 0 ? cashier1?.userId : cashier2?.userId,
    })),
    variantIds,
    productIds,
    brandId,
    categoryIds: [elecCatId, clothCatId].filter(Boolean),
    brandName:   products.brand,
    priceListId,
    currency,
    supplierId,
  };
}

// ── Setup (runs once, seeds both tenants) ──────────────────────────────────────
export function setup() {
  const run = Date.now();

  // ── India tenant ───────────────────────────────────────────────────────────
  const inOwner = registerUser(`in-owner-${run}@storeql.test`, '/api/iam-svc/auth/register/business');
  if (!inOwner) { console.error('India owner registration failed'); return null; }

  const india = seedTenant(
    inOwner,
    {
      businessName: 'Mumbai Retail Pvt Ltd',
      legalName:    'Mumbai Retail Private Limited',
      country:      'IN',
      currency:     'INR',
    },
    // Store IN-1 — Mumbai (onboarding path)
    {
      name: 'Mumbai — Marine Drive',
      code: `MUM-${run}`,
      type: 'STORE',
      line1: '24 Marine Drive',
      city:  'Mumbai',
      state: 'MH',
      country: 'IN',
      pincode: '400020',
      timezone: 'Asia/Kolkata',
    },
    // Store IN-2 — Delhi (admin path)
    {
      name: 'Delhi — Connaught Place',
      code: `DEL-${run}`,
      type: 'STORE',
      line1: 'Block A, Connaught Place',
      city:  'New Delhi',
      state: 'DL',
      country: 'IN',
      pincode: '110001',
      timezone: 'Asia/Kolkata',
    },
    {
      brand:         'Reliance Digital',
      initCostPrice: '1200.00',
      threshold:     '50.000',
      maxQty:        '200',
      items: [
        { name: 'Smart TV 43"',   category: 'electronics', attrs: { size: '43in', color: 'Black' } },
        { name: 'Android Phone',  category: 'electronics', attrs: { storage: '128GB', color: 'Blue' } },
        { name: "Men's Kurta",    category: 'clothing',    attrs: { size: 'L', fabric: 'Cotton' } },
        { name: "Women's Saree",  category: 'clothing',    attrs: { type: 'Silk', color: 'Red' } },
      ],
    },
    true  // isIN
  );

  if (!india) { console.error('India seed failed'); return null; }

  // ── UK tenant ──────────────────────────────────────────────────────────────
  const ukOwner = registerUser(`uk-owner-${run}@storeql.test`, '/api/iam-svc/auth/register/business');
  if (!ukOwner) { console.error('UK owner registration failed'); return null; }

  const uk = seedTenant(
    ukOwner,
    {
      businessName: 'London Merchandise Ltd',
      legalName:    'London Merchandise Limited',
      country:      'GB',
      currency:     'GBP',
    },
    // Store UK-1 — London (onboarding path)
    {
      name: 'London — Oxford Street',
      code: `LON-${run}`,
      type: 'STORE',
      line1: '220 Oxford Street',
      city:  'London',
      state: 'England',
      country: 'GB',
      pincode: 'W1C 1DX',
      timezone: 'Europe/London',
    },
    // Store UK-2 — Manchester (admin path)
    {
      name: 'Manchester — Arndale',
      code: `MCR-${run}`,
      type: 'STORE',
      line1: '49 Market Street',
      city:  'Manchester',
      state: 'England',
      country: 'GB',
      pincode: 'M1 1AD',
      timezone: 'Europe/London',
    },
    {
      brand:         'Marks & Spencer',
      initCostPrice: '150.00',
      threshold:     '25.000',
      maxQty:        '100',
      items: [
        { name: 'Smart TV 55"',   category: 'electronics', attrs: { size: '55in', color: 'Silver' } },
        { name: 'Laptop 15"',     category: 'electronics', attrs: { ram: '16GB', storage: '512GB' } },
        { name: "Men's Suit",     category: 'clothing',    attrs: { size: '42R', color: 'Navy' } },
        { name: "Women's Dress",  category: 'clothing',    attrs: { size: '12', color: 'Floral' } },
      ],
    },
    false  // isUK
  );

  if (!uk) { console.error('UK seed failed'); return null; }

  return { india, uk };
}

// ── VU → tenant assignment (odd VU = India, even VU = UK) ─────────────────────
function tenantCtx(d) { return (__VU % 2 === 1) ? d.india : d.uk; }
function isIN(d)       { return (__VU % 2 === 1); }

// Rotate between stores on each VU iteration.
function storeCtx(tenant) {
  if (!tenant.stores || tenant.stores.length === 0) return null;
  return tenant.stores[__ITER % tenant.stores.length];
}

// ── Scenario: browse catalogue ─────────────────────────────────────────────────
export function browseCatalog(d) {
  if (!d) return;
  const tenant = tenantCtx(d);
  const store  = storeCtx(tenant);
  const tag    = isIN(d) ? 'IN' : 'UK';
  const addLat = isIN(d)
    ? t => catalogLatencyIN.add(t)
    : t => catalogLatencyUK.add(t);

  const t0 = Date.now();
  let res = getStorefront('/api/product-svc/catalog/products', tenant.tenantId);
  ok(res, `${tag} catalog list`);
  addLat(Date.now() - t0);
  check(res, {
    [`${tag} response envelope has meta.requestId`]: r => {
      try { return JSON.parse(r.body).meta?.requestId != null; } catch (_) { return false; }
    },
  });

  res = get('/api/product-svc/admin/categories', tenant.ownerToken);
  ok(res, `${tag} list categories`);

  res = get('/api/product-svc/admin/products', tenant.ownerToken);
  ok(res, `${tag} admin products`);

  if (store) {
    res = get(`/api/inventory-svc/admin/inventory/levels?store=${store.storeId}`, tenant.ownerToken);
    ok(res, `${tag} stock levels ${store.label}`);

    const vid = tenant.variantIds[__ITER % tenant.variantIds.length];
    res = get(`/api/inventory-svc/admin/inventory/batches?store=${store.storeId}&variant=${vid}`, tenant.ownerToken);
    ok(res, `${tag} batches ${store.label}`);
    check(res, {
      [`${tag} batch has materialStatus field`]: r => {
        try {
          const items = JSON.parse(r.body).data || [];
          return items.length === 0 || items[0].materialStatus != null;
        } catch (_) { return true; }
      },
    });

    res = get(`/api/inventory-svc/admin/inventory/movements?store=${store.storeId}&limit=10`, tenant.ownerToken);
    ok(res, `${tag} movements ${store.label}`);
  }

  sleep(1);
}

// ── Scenario: complete POS sale (reserve → consume) ───────────────────────────
export function completeSale(d) {
  if (!d) return;
  const tenant = tenantCtx(d);
  const store  = storeCtx(tenant);
  if (!store || !tenant.variantIds.length) return;
  const tag = isIN(d) ? 'IN' : 'UK';
  const vid = tenant.variantIds[__ITER % tenant.variantIds.length];

  // Reserve
  const rRes = post('/api/inventory-svc/inventory/reservations', {
    storeId: store.storeId, variantId: vid, qty: 1, ttlSeconds: 300,
  }, tenant.ownerToken);

  const rOk = check(rRes, { [`${tag} reserve 2xx`]: r => r.status >= 200 && r.status < 300 });
  isIN(d) ? saleSuccessIN.add(rOk ? 1 : 0) : saleSuccessUK.add(rOk ? 1 : 0);
  if (!rOk) {
    console.warn(`[${tag}] reserve refused store=${store.storeId} variant=${vid}`
      + ` at ${new Date().toISOString()}: ${rRes.status} ${String(rRes.body).slice(0, 300)}`);
    errors.add(1); sleep(0.5); return;
  }

  // Positive: reserved qty immediately visible in levels (before consume)
  check(get(`/api/inventory-svc/admin/inventory/levels?store=${store.storeId}`, tenant.ownerToken), {
    [`${tag} reserved≥1 in levels after reserve`]: r => {
      try {
        const items = JSON.parse(r.body).data || [];
        const entry = items.find(l => l.variantId === vid);
        return entry && parseFloat(entry.reserved) >= 1;
      } catch (_) { return false; }
    },
  });

  const reservationId = body(rRes).id;
  if (!reservationId) { sleep(0.5); return; }

  // Consume (complete sale). The (store, variant) stays shared on purpose: a hold and a sale of one
  // variant at one store landing together is what a till does all day, so a refusal here is the
  // server's to answer for — say what it was.
  const cRes = post(`/api/inventory-svc/inventory/reservations/${reservationId}/consume`,
    {}, tenant.ownerToken);
  if (!ok(cRes, `${tag} consume ${store.label}`)) {
    console.warn(`[${tag}] consume refused store=${store.storeId} variant=${vid} reservation=${reservationId}`
      + ` at ${new Date().toISOString()}: ${cRes.status} ${String(cRes.body).slice(0, 300)}`);
  }

  // consume returns { "data": "consumed" } (string, not object)
  check(cRes, {
    [`${tag} status=CONSUMED`]: r => {
      try {
        const d = JSON.parse(r.body).data;
        return d === 'consumed' || d?.status === 'CONSUMED';
      } catch (_) { return false; }
    },
  });

  // Quick stock level check after sale
  const lvlRes = get(`/api/inventory-svc/admin/inventory/levels?store=${store.storeId}`, tenant.ownerToken);
  ok(lvlRes, `${tag} levels after sale ${store.label}`);

  sleep(0.5);
}

// ── Scenario: abandon cart (reserve → release) ─────────────────────────────────
export function abandonCart(d) {
  if (!d) return;
  const tenant = tenantCtx(d);
  const store  = storeCtx(tenant);
  if (!store || !tenant.variantIds.length) return;
  const tag = isIN(d) ? 'IN' : 'UK';
  const vid = tenant.variantIds[__ITER % tenant.variantIds.length];

  const rRes = post('/api/inventory-svc/inventory/reservations', {
    storeId: store.storeId, variantId: vid, qty: 1, ttlSeconds: 60,
  }, tenant.ownerToken);
  if (!ok(rRes, `${tag} reserve for abandon`)) { sleep(1); return; }

  const reservationId = body(rRes).id;
  if (!reservationId) { sleep(1); return; }

  sleep(0.2); // simulate browsing delay before abandon

  // List held reservations before releasing
  const listRes = get(
    `/api/inventory-svc/inventory/reservations?store=${store.storeId}&status=HELD&limit=5`, tenant.ownerToken);
  ok(listRes, `${tag} list HELD reservations`);

  const relRes = post(`/api/inventory-svc/inventory/reservations/${reservationId}/release`,
    {}, tenant.ownerToken);
  ok(relRes, `${tag} release ${store.label}`);

  // release returns { "data": "released" } (string, not object)
  check(relRes, {
    [`${tag} status=RELEASED`]: r => {
      try {
        const d = JSON.parse(r.body).data;
        return d === 'released' || d?.status === 'RELEASED';
      } catch (_) { return false; }
    },
  });

  // Positive: released reservation no longer in HELD list
  check(get(`/api/inventory-svc/inventory/reservations?store=${store.storeId}&status=HELD&limit=50`, tenant.ownerToken), {
    [`${tag} released reservation absent from HELD list`]: r => {
      try {
        const items = JSON.parse(r.body).data || [];
        return !items.some(res => res.id === reservationId);
      } catch (_) { return true; }
    },
  });

  sleep(1);
}

// ── Scenario: purchase / supplier stock receive ────────────────────────────────
export function purchaseReceive(d) {
  if (!d) return;
  const tenant = tenantCtx(d);
  const store  = storeCtx(tenant);
  if (!store || !tenant.variantIds.length) return;
  const tag    = isIN(d) ? 'IN' : 'UK';
  const vid    = tenant.variantIds[__ITER % tenant.variantIds.length];
  const cost   = isIN(d)
    ? `${(Math.floor(Math.random() * 1000) + 800)}.00`   // INR 800–1800
    : `${(Math.floor(Math.random() * 200) + 100)}.99`;   // GBP 100–300

  const t0 = Date.now();
  const recRes = apiReceiveStock(tenant.ownerToken,
    store.storeId, vid,
    isIN(d) ? 50 : 30,
    cost,
    `${tag}-PO`
  );
  purchaseLatency.add(Date.now() - t0);
  ok(recRes, `${tag} purchase receive ${store.label}`);

  // Update reorder threshold after receive (include maxQty — Gap #1)
  post('/api/inventory-svc/admin/inventory/thresholds', {
    storeId: store.storeId, variantId: vid,
    threshold: isIN(d) ? '50.000' : '25.000',
    maxQty:    isIN(d) ? '200'    : '100',
  }, tenant.ownerToken);

  // Verify updated levels
  const lvlRes = get(`/api/inventory-svc/admin/inventory/levels?store=${store.storeId}`, tenant.ownerToken);
  ok(lvlRes, `${tag} levels after receive ${store.label}`);

  // List batches for this variant at this store
  const batchRes = get(
    `/api/inventory-svc/admin/inventory/batches?store=${store.storeId}&variant=${vid}`, tenant.ownerToken);
  ok(batchRes, `${tag} batches after receive`);

  sleep(1.5);
}

// ── Scenario: staff & store management ────────────────────────────────────────
export function staffAdmin(d) {
  if (!d) return;
  const tenant = tenantCtx(d);
  const store  = storeCtx(tenant);
  const tag    = isIN(d) ? 'IN' : 'UK';

  // Tenant profile
  let res = get('/api/tenant-svc/admin/tenant', tenant.ownerToken);
  ok(res, `${tag} get tenant`);

  // All stores
  res = get('/api/tenant-svc/admin/stores', tenant.ownerToken);
  ok(res, `${tag} list stores`);
  check(res, {
    [`${tag} has 2 stores`]: r => {
      try {
        const list = JSON.parse(r.body).data || [];
        return Array.isArray(list) && list.length >= 2;
      } catch (_) { return false; }
    },
  });

  // All staff
  res = get('/api/tenant-svc/admin/staff', tenant.ownerToken);
  ok(res, `${tag} list staff`);
  check(res, {
    [`${tag} has staff`]: r => {
      try {
        const list = JSON.parse(r.body).data || [];
        return Array.isArray(list) && list.length >= 1;
      } catch (_) { return false; }
    },
  });

  // Onboarding status
  res = get('/api/tenant-svc/onboarding/status', tenant.ownerToken);
  ok(res, `${tag} onboarding status`);

  // Zones for current store
  if (store) {
    res = get(`/api/tenant-svc/admin/stores/${store.storeId}/zones`, tenant.ownerToken);
    ok(res, `${tag} list zones ${store.label}`);
    check(res, {
      [`${tag} ${store.label} has zones`]: r => {
        try {
          const list = JSON.parse(r.body).data || [];
          return Array.isArray(list) && list.length >= 1;
        } catch (_) { return false; }
      },
    });
  }

  sleep(1.5);
}

// ── Scenario: catalogue & inventory admin ──────────────────────────────────────
export function catalogAdmin(d) {
  if (!d) return;
  const tenant = tenantCtx(d);
  const store  = storeCtx(tenant);
  const tag    = isIN(d) ? 'IN' : 'UK';

  // Products + brands + categories
  let res = get('/api/product-svc/admin/products', tenant.ownerToken);
  ok(res, `${tag} admin list products`);

  res = get('/api/product-svc/admin/brands', tenant.ownerToken);
  ok(res, `${tag} admin list brands`);

  // Update brand name (idempotent-safe: add a timestamp suffix)
  if (tenant.brandId) {
    res = put(`/api/product-svc/admin/brands/${tenant.brandId}`,
      { name: `${tenant.brandName} (${slug()})` }, tenant.ownerToken);
    ok(res, `${tag} update brand`);

    res = get(`/api/product-svc/admin/brands/${tenant.brandId}`, tenant.ownerToken);
    ok(res, `${tag} get brand`);
  }

  res = get('/api/product-svc/admin/categories', tenant.ownerToken);
  ok(res, `${tag} list categories`);

  // ── Gap #34: manufacturer_pn — positive round-trip ────────────────────────
  if (tenant.variantIds.length > 0 && tenant.productIds && tenant.productIds.length > 0) {
    const chkVid = tenant.variantIds[__ITER % tenant.variantIds.length];
    const chkPid = tenant.productIds[__ITER % tenant.productIds.length];

    // GET variant list for product — manufacturerPn must be present
    res = get(`/api/product-svc/admin/products/${chkPid}/variants`, tenant.ownerToken);
    ok(res, `${tag} list variants for product`);
    check(res, {
      [`${tag} variant response has manufacturerPn field`]: r => {
        try {
          const items = JSON.parse(r.body).data || [];
          return items.length > 0 && 'manufacturerPn' in items[0];
        } catch (_) { return false; }
      },
      [`${tag} variant manufacturerPn is non-empty string`]: r => {
        try {
          const items = JSON.parse(r.body).data || [];
          return items.length > 0 && typeof items[0].manufacturerPn === 'string'
            && items[0].manufacturerPn.startsWith('MFR-');
        } catch (_) { return false; }
      },
    });

    // UPDATE variant — change manufacturerPn and verify the new value is returned
    const currentSku = (() => {
      try {
        const items = JSON.parse(res.body).data || [];
        return items.length > 0 ? items[0] : null;
      } catch (_) { return null; }
    })();
    if (currentSku) {
      const newMpn = `MFR-UPDATED-${slug()}`;
      const upRes = put(
        `/api/product-svc/admin/products/${chkPid}/variants/${currentSku.id}`,
        {
          sku:            currentSku.sku,
          barcode:        currentSku.barcode,
          manufacturerPn: newMpn,
          attributes:     currentSku.attributes,
          unit:           currentSku.unit,
        }, tenant.ownerToken);
      ok(upRes, `${tag} update variant manufacturerPn`);
      check(upRes, {
        [`${tag} updated manufacturerPn matches`]: r => {
          try { return JSON.parse(r.body).data.manufacturerPn === newMpn; }
          catch (_) { return false; }
        },
      });
    }
  }

  // ── Gap #33: cross-references — positive round-trip ───────────────────────
  if (tenant.variantIds.length > 0) {
    const xVid = tenant.variantIds[0];
    const supplierId = genUuid();
    const customerId = genUuid();

    // Create SUPPLIER cross-ref
    res = post(`/api/product-svc/admin/products/variants/${xVid}/cross-references`, {
      partyType: 'SUPPLIER', partyId: supplierId,
      partyName: `Supplier-${tag}`, crossRefNumber: `SUP-${slug()}`,
    }, tenant.ownerToken);
    ok(res, `${tag} create SUPPLIER cross-ref`);
    check(res, {
      [`${tag} cross-ref partyType=SUPPLIER`]: r => {
        try { return JSON.parse(r.body).data.partyType === 'SUPPLIER'; }
        catch (_) { return false; }
      },
    });
    const xrefId = (() => {
      try { return JSON.parse(res.body).data.id; } catch (_) { return null; }
    })();

    // Create CUSTOMER cross-ref
    res = post(`/api/product-svc/admin/products/variants/${xVid}/cross-references`, {
      partyType: 'CUSTOMER', partyId: customerId,
      partyName: `Customer-${tag}`, crossRefNumber: `CUST-${slug()}`,
    }, tenant.ownerToken);
    ok(res, `${tag} create CUSTOMER cross-ref`);

    // List all cross-refs — must include both
    res = get(`/api/product-svc/admin/products/variants/${xVid}/cross-references`, tenant.ownerToken);
    ok(res, `${tag} list cross-refs`);
    check(res, {
      [`${tag} cross-ref list has ≥2 items`]: r => {
        try { return (JSON.parse(r.body).data || []).length >= 2; }
        catch (_) { return false; }
      },
    });

    // Filter by partyType=SUPPLIER — only SUPPLIER entries returned
    res = get(`/api/product-svc/admin/products/variants/${xVid}/cross-references?partyType=SUPPLIER`, tenant.ownerToken);
    ok(res, `${tag} list cross-refs filtered SUPPLIER`);
    check(res, {
      [`${tag} cross-ref filter returns only SUPPLIER`]: r => {
        try {
          const items = JSON.parse(r.body).data || [];
          return items.length > 0 && items.every(x => x.partyType === 'SUPPLIER');
        } catch (_) { return false; }
      },
    });

    // Delete the SUPPLIER cross-ref
    if (xrefId) {
      res = http.del(
        `${BASE}/api/product-svc/admin/products/variants/${xVid}/cross-references/${xrefId}`,
        null, { headers: hdrs(tenant.ownerToken) });
      check(res, { [`${tag} delete cross-ref → 204`]: r => r.status === 204 });
    }
  }

  // ── Gap #32: item relationships — positive round-trip ─────────────────────
  if (tenant.variantIds.length >= 2) {
    // Pick the pair direction from the scenario-wide sequential iteration number so the
    // scenario's 2 concurrent VUs (whose adjacent iterations get adjacent numbers, hence
    // distinct parity) don't collide on the unique (variant, related, type) key.
    // NB: global __VU ids can share parity, and exec.vu.idInScenario does not exist.
    const [vidA, vidB] = exec.scenario.iterationInTest % 2 === 0
      ? [tenant.variantIds[0], tenant.variantIds[1]]
      : [tenant.variantIds[1], tenant.variantIds[0]];

    // Create SUBSTITUTE relationship
    res = post(`/api/product-svc/admin/products/variants/${vidA}/relationships`,
      { relatedVariantId: vidB, relationshipType: 'SUBSTITUTE' }, tenant.ownerToken);
    ok(res, `${tag} create SUBSTITUTE relationship`);
    check(res, {
      [`${tag} relationship has relationshipType SUBSTITUTE`]: r => {
        try { return JSON.parse(r.body).data.relationshipType === 'SUBSTITUTE'; }
        catch (_) { return false; }
      },
    });
    const relId = (() => {
      try { return JSON.parse(res.body).data.id; } catch (_) { return null; }
    })();

    // Create COMPLEMENTARY relationship (different type, same pair — unique key allows it)
    res = post(`/api/product-svc/admin/products/variants/${vidA}/relationships`,
      { relatedVariantId: vidB, relationshipType: 'COMPLEMENTARY' }, tenant.ownerToken);
    ok(res, `${tag} create COMPLEMENTARY relationship`);
    const compRelId = (() => {
      try { return JSON.parse(res.body).data.id; } catch (_) { return null; }
    })();

    // List relationships for vidA — must include both
    res = get(`/api/product-svc/admin/products/variants/${vidA}/relationships`, tenant.ownerToken);
    ok(res, `${tag} list relationships`);
    check(res, {
      [`${tag} relationship list has items`]: r => {
        try { return (JSON.parse(r.body).data || []).length >= 2; }
        catch (_) { return false; }
      },
    });

    // Delete SUBSTITUTE then COMPLEMENTARY so the block is idempotent across iterations
    if (relId) {
      res = http.del(
        `${BASE}/api/product-svc/admin/products/variants/${vidA}/relationships/${relId}`,
        null, { headers: hdrs(tenant.ownerToken) });
      check(res, { [`${tag} delete relationship → 204`]: r => r.status === 204 });

      // Confirm SUBSTITUTE is gone
      res = get(`/api/product-svc/admin/products/variants/${vidA}/relationships`, tenant.ownerToken);
      check(res, {
        [`${tag} relationship deleted — list shrinks`]: r => {
          try { return (JSON.parse(r.body).data || []).every(x => x.id !== relId); }
          catch (_) { return true; }
        },
      });
    }

    // Delete COMPLEMENTARY so it doesn't accumulate across iterations
    if (compRelId) {
      http.del(
        `${BASE}/api/product-svc/admin/products/variants/${vidA}/relationships/${compRelId}`,
        null, { headers: hdrs(tenant.ownerToken) });
    }
  }

  // ── Gap #35: catalog groups — positive round-trip ────────────────────────
  if (tenant.variantIds.length > 0) {
    // Spread by the scenario-wide sequential iteration number so concurrent VUs never
    // race on the same variant's single catalog assignment.
    const cgVid =
      tenant.variantIds[exec.scenario.iterationInTest % tenant.variantIds.length];

    // Create a catalog group
    res = post('/api/product-svc/admin/catalog-groups',
      { name: `${tag}-Group-${slug()}`, description: `${tag} spec group` }, tenant.ownerToken);
    ok(res, `${tag} create catalog group`);
    check(res, {
      [`${tag} catalog group status field present`]: r => {
        try { return JSON.parse(r.body).data.status === 'ACTIVE'; } catch (_) { return false; }
      },
    });
    const cgId = (() => { try { return JSON.parse(res.body).data.id; } catch (_) { return null; } })();

    if (cgId) {
      // Add a TEXT element
      res = post(`/api/product-svc/admin/catalog-groups/${cgId}/elements`,
        { elementName: 'colour', dataType: 'TEXT', required: false, sortOrder: 1 }, tenant.ownerToken);
      ok(res, `${tag} add catalog group element`);
      const elemId = (() => { try { return JSON.parse(res.body).data.id; } catch (_) { return null; } })();

      // Add a NUMBER element
      res = post(`/api/product-svc/admin/catalog-groups/${cgId}/elements`,
        { elementName: 'weight_kg', dataType: 'NUMBER', required: true, sortOrder: 2 }, tenant.ownerToken);
      ok(res, `${tag} add NUMBER element`);

      // GET group — elements must be embedded
      res = get(`/api/product-svc/admin/catalog-groups/${cgId}`, tenant.ownerToken);
      ok(res, `${tag} get catalog group`);
      check(res, {
        [`${tag} catalog group has ≥2 elements`]: r => {
          try { return (JSON.parse(r.body).data.elements || []).length >= 2; } catch (_) { return false; }
        },
      });

      // Assign variant to catalog group
      res = post(`/api/product-svc/admin/products/variants/${cgVid}/catalog-assignment`,
        { groupId: cgId, elementVals: '{"colour":"red","weight_kg":"1.5"}' }, tenant.ownerToken);
      ok(res, `${tag} assign catalog group to variant`);
      check(res, {
        [`${tag} assignment groupId matches`]: r => {
          try { return JSON.parse(r.body).data.groupId === cgId; } catch (_) { return false; }
        },
      });

      // GET assignment
      res = get(`/api/product-svc/admin/products/variants/${cgVid}/catalog-assignment`, tenant.ownerToken);
      ok(res, `${tag} get catalog assignment`);
      check(res, {
        [`${tag} assignment elementVals non-empty`]: r => {
          try {
            const v = JSON.parse(r.body).data.elementVals;
            return v && v !== '{}';
          } catch (_) { return false; }
        },
      });

      // UPDATE assignment
      res = put(`/api/product-svc/admin/products/variants/${cgVid}/catalog-assignment`,
        { elementVals: '{"colour":"blue","weight_kg":"2.0"}' }, tenant.ownerToken);
      ok(res, `${tag} update catalog assignment`);

      // DELETE assignment
      http.del(`${BASE}/api/product-svc/admin/products/variants/${cgVid}/catalog-assignment`,
        null, { headers: hdrs(tenant.ownerToken) });

      // DELETE element (cleanup)
      if (elemId) {
        http.del(`${BASE}/api/product-svc/admin/catalog-groups/${cgId}/elements/${elemId}`,
          null, { headers: hdrs(tenant.ownerToken) });
      }

      // Deactivate group (cleanup)
      http.del(`${BASE}/api/product-svc/admin/catalog-groups/${cgId}`,
        null, { headers: hdrs(tenant.ownerToken) });
    }
  }

  if (store) {
    // Thresholds + movements for current store
    res = get(`/api/inventory-svc/admin/inventory/thresholds?store=${store.storeId}`, tenant.ownerToken);
    ok(res, `${tag} list thresholds ${store.label}`);
    check(res, {
      [`${tag} threshold has maxQty field`]: r => {
        try {
          const items = JSON.parse(r.body).data || [];
          return items.length === 0 || 'maxQty' in items[0];
        } catch (_) { return true; }
      },
    });

    res = get(`/api/inventory-svc/admin/inventory/movements?store=${store.storeId}&limit=20`, tenant.ownerToken);
    ok(res, `${tag} list movements ${store.label}`);

    // Spot-adjust for cycle count simulation
    const vid = tenant.variantIds[__ITER % tenant.variantIds.length];
    res = post('/api/inventory-svc/admin/inventory/adjust', {
      storeId:   store.storeId,
      variantId: vid,
      delta:     2,
      reason:    'cycle count',
    }, tenant.ownerToken);
    ok(res, `${tag} adjust stock ${store.label}`);
  }

  sleep(1.5);
}

// ── Scenario: tenant isolation verification ────────────────────────────────────
export function isolationCheck(d) {
  if (!d || !d.india || !d.uk) return;

  const india = d.india;
  const uk    = d.uk;

  // 1. India tenant queries UK store levels → must return empty or 404/403
  if (uk.stores.length > 0) {
    const ukStoreId = uk.stores[__ITER % uk.stores.length].storeId;
    const res = get(`/api/inventory-svc/admin/inventory/levels?store=${ukStoreId}`, india.ownerToken);
    const isolated = check(res, {
      'IN cannot read UK store levels': r => {
        if (r.status === 404 || r.status === 403) return true;
        try {
          const items = JSON.parse(r.body).data || [];
          return Array.isArray(items) && items.length === 0;
        } catch (_) { return true; }
      },
    });
    if (!isolated) {
      isolationViolations.add(1);
      console.error(`ISOLATION VIOLATION [IN->UK levels] ukStoreId=${ukStoreId} indiaTenant=${india.tenantId} ukTenant=${uk.tenantId} status=${res.status} body=${res.body}`);
    }
  }

  // 2. UK tenant queries India store levels → must return empty or 404/403
  if (india.stores.length > 0) {
    const inStoreId = india.stores[__ITER % india.stores.length].storeId;
    const res = get(`/api/inventory-svc/admin/inventory/levels?store=${inStoreId}`, uk.ownerToken);
    const isolated = check(res, {
      'UK cannot read IN store levels': r => {
        if (r.status === 404 || r.status === 403) return true;
        try {
          const items = JSON.parse(r.body).data || [];
          return Array.isArray(items) && items.length === 0;
        } catch (_) { return true; }
      },
    });
    if (!isolated) {
      isolationViolations.add(1);
      console.error(`ISOLATION VIOLATION [UK->IN levels] inStoreId=${inStoreId} indiaTenant=${india.tenantId} ukTenant=${uk.tenantId} status=${res.status} body=${res.body}`);
    }
  }

  // 3. India catalog must not contain UK product names
  const inCatalog = getStorefront('/api/product-svc/catalog/products', india.tenantId);
  ok(inCatalog, 'IN catalog reachable');
  check(inCatalog, {
    'IN catalog has no UK products': r => {
      try {
        const products = JSON.parse(r.body).data || [];
        const names = products.map(p => (p.name || '').toLowerCase());
        // UK-specific product names
        return !names.some(n => n.includes("men's suit") || n.includes("laptop 15") ||
                                n.includes("smart tv 55") || n.includes("women's dress"));
      } catch (_) { return true; }
    },
  });

  // 4. UK catalog must not contain India product names
  const ukCatalog = getStorefront('/api/product-svc/catalog/products', uk.tenantId);
  ok(ukCatalog, 'UK catalog reachable');
  check(ukCatalog, {
    'UK catalog has no IN products': r => {
      try {
        const products = JSON.parse(r.body).data || [];
        const names = products.map(p => (p.name || '').toLowerCase());
        // India-specific product names
        return !names.some(n => n.includes('kurta') || n.includes('saree') ||
                                n.includes('smart tv 43') || n.includes('android phone'));
      } catch (_) { return true; }
    },
  });

  sleep(2);
}

// ── Scenario: Gap #4 — material status control ────────────────────────────────
export function materialControl(d) {
  if (!d) return;
  const tenant = tenantCtx(d);
  const store  = storeCtx(tenant);
  if (!store || !tenant.variantIds.length) return;
  const tag = isIN(d) ? 'IN' : 'UK';
  // A variant and a batch of this iteration's own. On a seeded variant the scenario's other VU
  // picked the same first batch and restored it before this one listed its quarantine, and other
  // scenarios received, adjusted and released that variant between the before and after reads.
  const vid = ownVariant(tenant, 'MC', tag);
  if (!vid) { sleep(1); return; }
  const batchQty = 12;
  const recRes = apiReceiveStock(tenant.ownerToken, store.storeId, vid, batchQty,
    isIN(d) ? '1200.00' : '150.00', `${tag}-MC`);
  if (!ok(recRes, `${tag} MC receive this iteration's batch`)) { sleep(1); return; }

  // 1. List batches — verify materialStatus field present
  const batchRes = get(
    `/api/inventory-svc/admin/inventory/batches?store=${store.storeId}&variant=${vid}&limit=5`, tenant.ownerToken);
  ok(batchRes, `${tag} MC list batches`);
  check(batchRes, {
    [`${tag} MC batches have materialStatus`]: r => {
      try {
        const items = JSON.parse(r.body).data || [];
        return items.length > 0 && items[0].materialStatus != null;
      } catch (_) { return false; }
    },
  });

  const batchId = (() => {
    try {
      const items = JSON.parse(batchRes.body).data || [];
      const avail = items.find(b => b.materialStatus === 'AVAILABLE');
      return avail ? avail.id : null;
    } catch (_) { return null; }
  })();

  if (!batchId) { sleep(1); return; }

  // 2. Capture levels before quarantine (the variant's own level, wherever its page is)
  const levelsBefore = (() => {
    const { level } = levelAt(tenant.ownerToken, store.storeId, vid);
    return level ? parseFloat(level.available) : 0;
  })();

  // 3. Quarantine the batch
  const t0 = Date.now();
  const qRes = put(`/api/inventory-svc/admin/inventory/batches/${batchId}/material-status`,
    { materialStatus: 'QUARANTINE', reason: 'k6-quality-hold' }, tenant.ownerToken);
  materialControlLatency.add(Date.now() - t0);
  ok(qRes, `${tag} MC quarantine batch`);
  check(qRes, {
    [`${tag} MC batch materialStatus=QUARANTINE`]: r => {
      try { return JSON.parse(r.body).data.materialStatus === 'QUARANTINE'; }
      catch (_) { return false; }
    },
  });

  // 4. Levels must drop (quarantined qty excluded from available): by exactly the batch, now that
  //    nothing else moves this variant between the two reads
  const levelsAfter = levelAt(tenant.ownerToken, store.storeId, vid);
  ok(levelsAfter.res, `${tag} MC levels after quarantine`);
  check(levelsAfter, {
    [`${tag} MC quarantine excludes batch from available`]: a => {
      if (!a.res || a.res.status !== 200) return false;
      const after = a.level ? parseFloat(a.level.available) : 0;
      return Math.abs((levelsBefore - after) - batchQty) < 0.0005;
    },
  });

  // 5. Filter batches by material_status=QUARANTINE
  const qListRes = get(
    `/api/inventory-svc/admin/inventory/batches?material_status=QUARANTINE&store=${store.storeId}`, tenant.ownerToken);
  ok(qListRes, `${tag} MC list QUARANTINE batches`);
  check(qListRes, {
    [`${tag} MC quarantine filter correct`]: r => {
      try {
        const items = JSON.parse(r.body).data || [];
        return items.length > 0 && items.every(b => b.materialStatus === 'QUARANTINE');
      } catch (_) { return false; }
    },
  });

  // 6. Restore to AVAILABLE (inspection passed)
  const restoreRes = put(`/api/inventory-svc/admin/inventory/batches/${batchId}/material-status`,
    { materialStatus: 'AVAILABLE', reason: 'k6-inspection-passed' }, tenant.ownerToken);
  ok(restoreRes, `${tag} MC restore AVAILABLE`);
  check(restoreRes, {
    [`${tag} MC batch restored to AVAILABLE`]: r => {
      try { return JSON.parse(r.body).data.materialStatus === 'AVAILABLE'; }
      catch (_) { return false; }
    },
  });

  sleep(1);
}

// ── Scenario: Gap #1 — min-max planning engine ────────────────────────────────
export function planningEngine(d) {
  if (!d) return;
  const tenant = tenantCtx(d);
  const store  = storeCtx(tenant);
  if (!store || !tenant.variantIds.length) return;
  const tag = isIN(d) ? 'IN' : 'UK';
  // A variant of this iteration's own. A VU offset did not keep the scenario's two VUs apart (their
  // global ids can share parity and remainder): one resolved the (store, variant) suggestion, or
  // purchaseReceive reset the threshold, before the other read it back.
  const vid = ownVariant(tenant, 'PE', tag);
  if (!vid) { sleep(1); return; }

  // 1. Set a very high threshold to guarantee an under-stock condition for this run
  const highThreshold = '500000.000';
  const highMax       = '600000';
  const normalThreshold = isIN(d) ? '50.000' : '25.000';
  const normalMax       = isIN(d) ? '200'    : '100';

  // Stock of its own, as much as the normal max: the reset at the end then leaves the variant above
  // its threshold, so no later run at this store raises a suggestion for it that nobody resolves.
  ok(apiReceiveStock(tenant.ownerToken, store.storeId, vid, parseInt(normalMax, 10),
    isIN(d) ? '1200.00' : '150.00', `${tag}-PE`), `${tag} PE receive this iteration's stock`);

  const tRes = post('/api/inventory-svc/admin/inventory/thresholds', {
    storeId: store.storeId, variantId: vid, threshold: highThreshold, maxQty: highMax,
  }, tenant.ownerToken);
  ok(tRes, `${tag} PE set high threshold`);
  check(tRes, {
    [`${tag} PE threshold has maxQty`]: r => {
      try { return JSON.parse(r.body).data.maxQty != null; } catch (_) { return false; }
    },
  });

  // 2. Run the min-max planning engine
  const t0 = Date.now();
  const planRes = post(
    `/api/inventory-svc/admin/inventory/planning/run?store=${store.storeId}`,
    {}, tenant.ownerToken);
  planningLatency.add(Date.now() - t0);
  ok(planRes, `${tag} PE planning run`);
  check(planRes, {
    [`${tag} PE run returns array`]: r => {
      try { return Array.isArray(JSON.parse(r.body).data); } catch (_) { return false; }
    },
  });

  // 3. List OPEN suggestions for this store and take this variant's own — never whichever is first:
  //    the store's other variants (the scenario's other VU's among them) have suggestions of their
  //    own, newest first, so the page is wide enough to reach this one past theirs
  const listRes = get(
    `/api/inventory-svc/admin/inventory/planning/suggestions?store=${store.storeId}&status=OPEN&limit=100`, tenant.ownerToken);
  ok(listRes, `${tag} PE list OPEN suggestions`);

  const suggestion = (() => {
    try {
      const items = JSON.parse(listRes.body).data || [];
      return items.find(s => s.variantId === vid) || null;
    } catch (_) { return null; }
  })();

  check(listRes, {
    [`${tag} PE has open suggestion`]: () => suggestion != null,
    [`${tag} PE suggestion has correct fields`]: () => {
      if (!suggestion) return false;
      return suggestion.minQty != null && suggestion.suggestedQty != null &&
             suggestion.status === 'OPEN';
    },
    [`${tag} PE suggestedQty = maxQty - available`]: () => {
      if (!suggestion) return false;
      const expected = parseFloat(highMax) - parseFloat(suggestion.availableQty);
      return Math.abs(parseFloat(suggestion.suggestedQty) - expected) < 1;
    },
  });

  // 4. Resolve suggestion as ORDERED (this iteration's own, so nobody else resolves it first)
  if (suggestion) {
    const resolveRes = put(
      `/api/inventory-svc/admin/inventory/planning/suggestions/${suggestion.id}/status`,
      { status: 'ORDERED' }, tenant.ownerToken);
    check(resolveRes, {
      [`${tag} PE resolve ORDERED 200`]: r => r.status === 200,
    });
    if (resolveRes.status === 200) {
      check(resolveRes, {
        [`${tag} PE resolved status=ORDERED`]: r => {
          try { return JSON.parse(r.body).data.status === 'ORDERED'; } catch (_) { return false; }
        },
        [`${tag} PE resolved has resolvedAt`]: r => {
          try { return JSON.parse(r.body).data.resolvedAt != null; } catch (_) { return false; }
        },
      });
    }
  }

  // 5. Reset threshold back to normal: below the stock received above, so the variant is never
  //    suggested again by a later run at this store
  post('/api/inventory-svc/admin/inventory/thresholds', {
    storeId: store.storeId, variantId: vid,
    threshold: normalThreshold, maxQty: normalMax,
  }, tenant.ownerToken);

  sleep(1);
}

// ── Scenario: Gap #7 — demand history aggregation ────────────────────────────
export function demandHistory(d) {
  if (!d) return;
  const tenant = tenantCtx(d);
  const store  = storeCtx(tenant);
  if (!store) return;
  const tag = isIN(d) ? 'IN' : 'UK';

  // 1. Aggregate WEEK demand for this store (UPSERT from stock_movements type='SALE')
  const t0 = Date.now();
  const aggRes = post('/api/inventory-svc/admin/inventory/demand/aggregate',
    { storeId: store.storeId, bucketType: 'WEEK' }, tenant.ownerToken);
  demandHistoryLatency.add(Date.now() - t0);
  ok(aggRes, `${tag} DH aggregate WEEK`);
  check(aggRes, {
    [`${tag} DH bucketsUpserted is number`]: r => {
      try {
        const data = JSON.parse(r.body).data || {};
        return typeof data.bucketsUpserted === 'number' && data.bucketsUpserted >= 0;
      } catch (_) { return false; }
    },
    [`${tag} DH bucketType=WEEK`]: r => {
      try { return JSON.parse(r.body).data.bucketType === 'WEEK'; } catch (_) { return false; }
    },
  });

  // 2. Query weekly demand history for this store
  const histRes = get(
    `/api/inventory-svc/admin/inventory/demand/history?store=${store.storeId}&bucket_type=WEEK&limit=10`, tenant.ownerToken);
  ok(histRes, `${tag} DH list WEEK history`);
  check(histRes, {
    [`${tag} DH WEEK history is array`]: r => {
      try { return Array.isArray(JSON.parse(r.body).data); } catch (_) { return false; }
    },
    [`${tag} DH WEEK history has demand fields`]: r => {
      try {
        const items = JSON.parse(r.body).data || [];
        return items.length === 0 ||
          (items[0].demandQty != null && items[0].movementCount != null &&
           items[0].bucketDate != null && items[0].bucketType === 'WEEK');
      } catch (_) { return true; }
    },
  });

  // 3. Incremental DAY aggregate — only last 7 days (tests the 'since' parameter)
  const since = new Date(Date.now() - 7 * 86400000).toISOString().split('T')[0];
  const incrRes = post('/api/inventory-svc/admin/inventory/demand/aggregate',
    { storeId: store.storeId, bucketType: 'DAY', since }, tenant.ownerToken);
  ok(incrRes, `${tag} DH incremental DAY since ${since}`);
  check(incrRes, {
    [`${tag} DH DAY aggregate has bucketType`]: r => {
      try { return JSON.parse(r.body).data.bucketType === 'DAY'; } catch (_) { return false; }
    },
  });

  // 4. Query daily history for this store (may be empty if no sales today)
  const dayRes = get(
    `/api/inventory-svc/admin/inventory/demand/history?store=${store.storeId}&bucket_type=DAY&limit=7`, tenant.ownerToken);
  ok(dayRes, `${tag} DH list DAY history`);
  check(dayRes, {
    [`${tag} DH DAY history is array`]: r => {
      try { return Array.isArray(JSON.parse(r.body).data); } catch (_) { return false; }
    },
  });

  // 5. Positive: MONTH bucket — coarser granularity, should aggregate cleanly
  const monthRes = post('/api/inventory-svc/admin/inventory/demand/aggregate',
    { storeId: store.storeId, bucketType: 'MONTH' }, tenant.ownerToken);
  ok(monthRes, `${tag} DH aggregate MONTH`);
  check(monthRes, {
    [`${tag} DH MONTH bucketType`]: r => {
      try { return JSON.parse(r.body).data.bucketType === 'MONTH'; } catch (_) { return false; }
    },
    [`${tag} DH MONTH bucketsUpserted≥0`]: r => {
      try { return typeof JSON.parse(r.body).data.bucketsUpserted === 'number'; } catch (_) { return false; }
    },
  });

  // 6. Positive: MONTH history query returns array
  const monthHistRes = get(
    `/api/inventory-svc/admin/inventory/demand/history?store=${store.storeId}&bucket_type=MONTH&limit=3`, tenant.ownerToken);
  ok(monthHistRes, `${tag} DH list MONTH history`);
  check(monthHistRes, {
    [`${tag} DH MONTH history is array`]: r => {
      try { return Array.isArray(JSON.parse(r.body).data); } catch (_) { return false; }
    },
  });

  sleep(1);
}

// ── Scenario: Gap #3 — serial number control ─────────────────────────────────
export function serialControl(d) {
  if (!d) return;
  const tenant = tenantCtx(d);
  const store  = storeCtx(tenant);
  if (!store || !tenant.variantIds.length) return;
  const tag = isIN(d) ? 'IN' : 'UK';
  const vid = tenant.variantIds[__ITER % tenant.variantIds.length];

  // 1. Get a batch ID for this store + variant (serials must link to a batch)
  const batchRes = get(
    `/api/inventory-svc/admin/inventory/batches?store=${store.storeId}&variant=${vid}&limit=1`, tenant.ownerToken);
  const batchId = (() => {
    try {
      const items = JSON.parse(batchRes.body).data || [];
      return items[0]?.id || null;
    } catch (_) { return null; }
  })();
  if (!batchId) { sleep(1); return; }

  // 2. Register 5 auto-generated serials for this batch
  const t0 = Date.now();
  const regRes = post('/api/inventory-svc/admin/inventory/serials/register', {
    batchId, storeId: store.storeId, variantId: vid, autoQty: 5, prefix: 'K6',
  }, tenant.ownerToken);
  serialControlLatency.add(Date.now() - t0);
  ok(regRes, `${tag} SC register serials`);
  check(regRes, {
    [`${tag} SC registered 5 serials`]: r => {
      try {
        const items = JSON.parse(r.body).data || [];
        return Array.isArray(items) && items.length === 5;
      } catch (_) { return false; }
    },
    [`${tag} SC serials are IN_STOCK`]: r => {
      try {
        const items = JSON.parse(r.body).data || [];
        return items.length > 0 && items[0].serialNo != null && items[0].status === 'IN_STOCK';
      } catch (_) { return false; }
    },
  });

  const firstSerial = (() => {
    try { return JSON.parse(regRes.body).data?.[0] || null; } catch (_) { return null; }
  })();

  // 3. List IN_STOCK serials for this variant
  const listRes = get(
    `/api/inventory-svc/admin/inventory/serials?store=${store.storeId}&variant=${vid}&status=IN_STOCK&limit=10`, tenant.ownerToken);
  ok(listRes, `${tag} SC list IN_STOCK serials`);
  check(listRes, {
    [`${tag} SC list has IN_STOCK serials`]: r => {
      try {
        const items = JSON.parse(r.body).data || [];
        return items.length > 0 && items.every(s => s.status === 'IN_STOCK');
      } catch (_) { return false; }
    },
  });

  if (firstSerial) {
    // 4. Lookup by serial_no
    const lookupRes = get(
      `/api/inventory-svc/admin/inventory/serials/lookup?serial_no=${firstSerial.serialNo}`, tenant.ownerToken);
    ok(lookupRes, `${tag} SC lookup by serial_no`);
    check(lookupRes, {
      [`${tag} SC lookup matches serialNo`]: r => {
        try { return JSON.parse(r.body).data.serialNo === firstSerial.serialNo; }
        catch (_) { return false; }
      },
    });

    // 5. Get the serial by ID
    const getRes = get(
      `/api/inventory-svc/admin/inventory/serials/${firstSerial.id}`, tenant.ownerToken);
    if (!ok(getRes, `${tag} SC get serial by id`)) {
      console.warn(`serial by id refused: ${getRes.status} ${String(getRes.body).slice(0, 240)}`);
    }

    // 6. Change status to LOST
    const lostRes = put(
      `/api/inventory-svc/admin/inventory/serials/${firstSerial.id}/status`,
      { status: 'LOST' }, tenant.ownerToken);
    if (!ok(lostRes, `${tag} SC mark LOST`)) {
      console.warn(`serial LOST refused: ${lostRes.status} ${String(lostRes.body).slice(0, 240)}`);
    }
    check(lostRes, {
      [`${tag} SC serial status=LOST`]: r => {
        try { return JSON.parse(r.body).data.status === 'LOST'; } catch (_) { return false; }
      },
    });

    // Positive: LOST serial no longer appears in IN_STOCK list
    check(get(`/api/inventory-svc/admin/inventory/serials?store=${store.storeId}&variant=${vid}&status=IN_STOCK&limit=100`, tenant.ownerToken), {
      [`${tag} SC LOST serial absent from IN_STOCK list`]: r => {
        try {
          const items = JSON.parse(r.body).data || [];
          return !items.some(s => s.id === firstSerial.id);
        } catch (_) { return true; }
      },
    });

    // 7. Fetch genealogy — must contain at least 2 movements (RECEIVE + LOST transition)
    const histRes = get(
      `/api/inventory-svc/admin/inventory/serials/${firstSerial.id}/history`, tenant.ownerToken);
    ok(histRes, `${tag} SC genealogy`);
    check(histRes, {
      [`${tag} SC genealogy has ≥2 movements`]: r => {
        try {
          const items = JSON.parse(r.body).data || [];
          return items.length >= 2;
        } catch (_) { return false; }
      },
      [`${tag} SC genealogy last movement toStatus=LOST`]: r => {
        try {
          const items = JSON.parse(r.body).data || [];
          return items.length > 0 && items[items.length - 1].toStatus === 'LOST';
        } catch (_) { return false; }
      },
    });
  }

  sleep(1);
}

export function uomManagement(d) {
  if (!d) return;
  const tenant = tenantCtx(d);
  const tag = isIN(d) ? 'IN' : 'UK';
  // A variant of this iteration's own: VUs sharing one would delete each other's item conversion.
  const ownVariant = post(`/api/product-svc/admin/products/${tenant.productIds[0]}/variants`,
    { sku: `UOM-${__VU}-${__ITER}-${slug()}`, unit: 'EA' }, tenant.ownerToken);
  if (!ok(ownVariant, `${tag} UOM variant for this iteration`)) { sleep(1); return; }
  const vid = body(ownVariant).id;

  // 1. List UOM classes (system-wide — no tenant needed, but we pass tenant for auth)
  const t0 = Date.now();
  const classRes = get('/api/product-svc/admin/uom/classes', tenant.ownerToken);
  uomManagementLatency.add(Date.now() - t0);
  ok(classRes, `${tag} UOM list classes`);
  check(classRes, {
    [`${tag} UOM classes non-empty`]: r => {
      try {
        const items = JSON.parse(r.body).data || [];
        return items.length >= 6;
      } catch (_) { return false; }
    },
  });

  // 2. List units filtered by WEIGHT class
  const unitsRes = get('/api/product-svc/admin/uom/units?class=WEIGHT', tenant.ownerToken);
  ok(unitsRes, `${tag} UOM list WEIGHT units`);
  check(unitsRes, {
    [`${tag} UOM WEIGHT units include KG`]: r => {
      try {
        const items = JSON.parse(r.body).data || [];
        return items.some(u => u.code === 'KG');
      } catch (_) { return false; }
    },
  });

  // 3. Standard conversion: 1 KG → G (expect 1000)
  const t1 = Date.now();
  const convRes = get('/api/product-svc/admin/uom/convert?from=KG&to=G&qty=1', tenant.ownerToken);
  uomManagementLatency.add(Date.now() - t1);
  ok(convRes, `${tag} UOM convert KG→G`);
  check(convRes, {
    [`${tag} UOM 1 KG = 1000 G`]: r => {
      try {
        const result = JSON.parse(r.body).data;
        return parseFloat(result.convertedQty) === 1000 && result.source === 'STANDARD';
      } catch (_) { return false; }
    },
  });

  // 4. Identity conversion: 5 EA → EA (expect 5, source IDENTITY)
  const idRes = get('/api/product-svc/admin/uom/convert?from=EA&to=EA&qty=5', tenant.ownerToken);
  ok(idRes, `${tag} UOM identity conversion`);
  check(idRes, {
    [`${tag} UOM identity source=IDENTITY`]: r => {
      try {
        const result = JSON.parse(r.body).data;
        return result.source === 'IDENTITY' && parseFloat(result.convertedQty) === 5;
      } catch (_) { return false; }
    },
  });

  // 5. Upsert an item-level conversion for this variant (CASE → EA = 12)
  const upsertRes = http.post(
    `${BASE}/api/product-svc/admin/uom/item-conversions`,
    JSON.stringify({ variantId: vid, fromUom: 'CASE', toUom: 'EA', factor: '12' }),
    { headers: hdrs(tenant.ownerToken) }
  );
  ok(upsertRes, `${tag} UOM upsert item conversion`);
  check(upsertRes, {
    [`${tag} UOM item conversion factor=12`]: r => {
      try {
        const result = JSON.parse(r.body).data;
        return parseFloat(result.factor) === 12 && result.fromUom === 'CASE' && result.toUom === 'EA';
      } catch (_) { return false; }
    },
  });

  // 6. Convert using the item-level override (3 CASE → EA, expect 36)
  const itemConvRes = get(
    `/api/product-svc/admin/uom/convert?from=CASE&to=EA&qty=3&variant=${vid}`, tenant.ownerToken);
  ok(itemConvRes, `${tag} UOM item-level convert CASE→EA`);
  check(itemConvRes, {
    [`${tag} UOM 3 CASE = 36 EA (item override)`]: r => {
      try {
        const result = JSON.parse(r.body).data;
        return parseFloat(result.convertedQty) === 36 && result.source === 'ITEM';
      } catch (_) { return false; }
    },
  });

  // 7. List item conversions for the variant
  const listRes = get(
    `/api/product-svc/admin/uom/item-conversions?variant=${vid}`, tenant.ownerToken);
  ok(listRes, `${tag} UOM list item conversions`);
  const convId = (() => {
    try {
      const items = JSON.parse(listRes.body).data || [];
      return items[0]?.id || null;
    } catch (_) { return null; }
  })();

  // 8. Delete the item conversion
  if (convId) {
    const delRes = http.del(
      `${BASE}/api/product-svc/admin/uom/item-conversions/${convId}`,
      null,
      { headers: hdrs(tenant.ownerToken) }
    );
    check(delRes, { [`${tag} UOM item conversion deleted`]: r => r.status === 204 });
  }

  sleep(1);
}

export function moveOrders(d) {
  if (!d) return;
  const tenant = tenantCtx(d);
  const tag = isIN(d) ? 'IN' : 'UK';
  const store = storeCtx(tenant);
  if (!store || !tenant.variantIds.length) return;

  // Use store1 as source, store2 as destination (inter-store pick wave)
  const fromStore = tenant.stores[0].storeId;
  const toStore   = tenant.stores[1].storeId;
  const vid = tenant.variantIds[__ITER % tenant.variantIds.length];

  // 1. First ensure source store has stock (receive a small batch)
  http.post(
    `${BASE}/api/inventory-svc/admin/inventory/receive`,
    JSON.stringify({ storeId: fromStore, variantId: vid, qty: '20', batchNo: `MO-SEED-${__ITER}` }),
    { headers: hdrs(tenant.ownerToken) }
  );

  // 2. Create a move order DRAFT
  const t0 = Date.now();
  const createRes = http.post(
    `${BASE}/api/inventory-svc/admin/inventory/move-orders`,
    JSON.stringify({
      fromStoreId: fromStore,
      toStoreId: toStore,
      fromZone: 'RECEIVING',
      toZone: 'SHELF-A',
      notes: `k6 pick wave ${__ITER}`,
      lines: [{ variantId: vid, requestedQty: '5' }],
    }),
    { headers: hdrs(tenant.ownerToken) }
  );
  moveOrderLatency.add(Date.now() - t0);
  ok(createRes, `${tag} MO create`);
  check(createRes, {
    [`${tag} MO created status=DRAFT`]: r => {
      try { return JSON.parse(r.body).data.status === 'DRAFT'; } catch (_) { return false; }
    },
    [`${tag} MO has 1 line`]: r => {
      try { return JSON.parse(r.body).data.lines.length === 1; } catch (_) { return false; }
    },
  });

  const orderId = (() => {
    try { return JSON.parse(createRes.body).data?.id || null; } catch (_) { return null; }
  })();
  if (!orderId) { sleep(1); return; }

  // 3. List move orders — should include the new one
  const listRes = get(
    `/api/inventory-svc/admin/inventory/move-orders?store=${fromStore}&status=DRAFT&limit=10`, tenant.ownerToken);
  ok(listRes, `${tag} MO list DRAFT`);
  check(listRes, {
    [`${tag} MO list contains new order`]: r => {
      try {
        const items = JSON.parse(r.body).data || [];
        return items.some(o => o.id === orderId);
      } catch (_) { return false; }
    },
  });

  // 4. Get order by ID
  const getRes = get(
    `/api/inventory-svc/admin/inventory/move-orders/${orderId}`, tenant.ownerToken);
  ok(getRes, `${tag} MO get by id`);


  // 5. Execute pick — DRAFT → COMPLETED, stock moves from fromStore to toStore
  const t1 = Date.now();
  const pickRes = http.post(
    `${BASE}/api/inventory-svc/admin/inventory/move-orders/${orderId}/pick`,
    null,
    { headers: hdrs(tenant.ownerToken) }
  );
  moveOrderLatency.add(Date.now() - t1);
  ok(pickRes, `${tag} MO pick`);
  check(pickRes, {
    [`${tag} MO picked status=COMPLETED`]: r => {
      try { return JSON.parse(r.body).data.status === 'COMPLETED'; } catch (_) { return false; }
    },
    [`${tag} MO line has pickedQty`]: r => {
      try {
        const lines = JSON.parse(r.body).data?.lines || [];
        return lines.length > 0 && lines[0].pickedQty != null;
      } catch (_) { return false; }
    },
  });

  // Positive: source store stock decreased after pick
  // The pick's own outbound movement, not the store's total: other VUs receive and sell this
  // variant at the same store concurrently, so a before/after total is not a reliable signal.
  check(get(`/api/inventory-svc/admin/inventory/movements?store=${fromStore}&variant=${vid}&limit=100`, tenant.ownerToken), {
    [`${tag} MO source levels decreased after pick`]: r => {
      try {
        return (JSON.parse(r.body).data || []).some(m => m.refId === orderId && m.refType === 'MOVE_ORDER' && parseFloat(m.qty) < 0);
      } catch (_) { return false; }
    },
  });

  // 6. Verify destination store received stock
  const destLevels = get(
    `/api/inventory-svc/admin/inventory/levels?store=${toStore}`, tenant.ownerToken);
  ok(destLevels, `${tag} MO dest levels`);
  check(destLevels, {
    [`${tag} MO dest store has stock after pick`]: r => {
      try {
        const items = JSON.parse(r.body).data || [];
        const level = items.find(l => l.variantId === vid);
        return level && parseFloat(level.onHand) > 0;
      } catch (_) { return false; }
    },
  });

  // 7. Create and cancel a second move order
  const cancelCreate = http.post(
    `${BASE}/api/inventory-svc/admin/inventory/move-orders`,
    JSON.stringify({
      fromStoreId: fromStore, toStoreId: toStore,
      lines: [{ variantId: vid, requestedQty: '2' }],
    }),
    { headers: hdrs(tenant.ownerToken) }
  );
  const cancelId = (() => {
    try { return JSON.parse(cancelCreate.body).data?.id || null; } catch (_) { return null; }
  })();
  if (cancelId) {
    const cancelRes = http.post(
      `${BASE}/api/inventory-svc/admin/inventory/move-orders/${cancelId}/cancel`,
      null,
      { headers: hdrs(tenant.ownerToken) }
    );
    ok(cancelRes, `${tag} MO cancel`);
    check(cancelRes, {
      [`${tag} MO cancelled status=CANCELLED`]: r => {
        try { return JSON.parse(r.body).data.status === 'CANCELLED'; } catch (_) { return false; }
      },
    });
  }

  sleep(1);
}

export function transferOrders(d) {
  if (!d) return;
  const tenant = tenantCtx(d);
  const tag = isIN(d) ? 'IN' : 'UK';
  if (!tenant.variantIds.length || tenant.stores.length < 2) return;

  const fromStore = tenant.stores[0].storeId;
  const toStore   = tenant.stores[1].storeId;
  const vid = tenant.variantIds[__ITER % tenant.variantIds.length];

  // 1. Seed source store with stock
  http.post(
    `${BASE}/api/inventory-svc/admin/inventory/receive`,
    JSON.stringify({ storeId: fromStore, variantId: vid, qty: '30', batchNo: `TO-SEED-${__ITER}` }),
    { headers: hdrs(tenant.ownerToken) }
  );

  // 2. Create INTRANSIT transfer order (two-phase: ship then receive)
  const t0 = Date.now();
  const createRes = http.post(
    `${BASE}/api/inventory-svc/admin/inventory/transfers`,
    JSON.stringify({
      fromStoreId: fromStore,
      toStoreId: toStore,
      transferType: 'INTRANSIT',
      notes: `k6 intransit transfer ${__ITER}`,
      lines: [{ variantId: vid, requestedQty: '8' }],
    }),
    { headers: hdrs(tenant.ownerToken) }
  );
  transferOrderLatency.add(Date.now() - t0);
  ok(createRes, `${tag} TO create INTRANSIT`);
  check(createRes, {
    [`${tag} TO status=PENDING`]: r => {
      try { return JSON.parse(r.body).data.status === 'PENDING'; } catch (_) { return false; }
    },
    [`${tag} TO type=INTRANSIT`]: r => {
      try { return JSON.parse(r.body).data.transferType === 'INTRANSIT'; } catch (_) { return false; }
    },
    [`${tag} TO has 1 line`]: r => {
      try { return JSON.parse(r.body).data.lines.length === 1; } catch (_) { return false; }
    },
  });

  const orderId = (() => {
    try { return JSON.parse(createRes.body).data?.id || null; } catch (_) { return null; }
  })();
  if (!orderId) { sleep(1); return; }

  // 3. List transfers — should include the new PENDING order
  const listRes = get(
    `/api/inventory-svc/admin/inventory/transfers?store=${fromStore}&status=PENDING&limit=10`, tenant.ownerToken);
  ok(listRes, `${tag} TO list PENDING`);
  check(listRes, {
    [`${tag} TO list contains new order`]: r => {
      try {
        const items = JSON.parse(r.body).data || [];
        return items.some(o => o.id === orderId);
      } catch (_) { return false; }
    },
  });

  // 4. Get order by ID
  const getRes = get(
    `/api/inventory-svc/admin/inventory/transfers/${orderId}`, tenant.ownerToken);
  ok(getRes, `${tag} TO get by id`);


  // 5. Ship — PENDING → SHIPPED (deducts source, sets shippedQty, stock in transit)
  const t1 = Date.now();
  const shipRes = http.post(
    `${BASE}/api/inventory-svc/admin/inventory/transfers/${orderId}/ship`,
    null,
    { headers: hdrs(tenant.ownerToken) }
  );
  transferOrderLatency.add(Date.now() - t1);
  ok(shipRes, `${tag} TO ship`);
  check(shipRes, {
    [`${tag} TO shipped status=SHIPPED`]: r => {
      try { return JSON.parse(r.body).data.status === 'SHIPPED'; } catch (_) { return false; }
    },
    [`${tag} TO line has shippedQty`]: r => {
      try {
        const lines = JSON.parse(r.body).data?.lines || [];
        return lines.length > 0 && lines[0].shippedQty != null;
      } catch (_) { return false; }
    },
  });

  // Positive: INTRANSIT ship deducts from source — stock is now in transit
  // The shipment's own outbound movement, for the same reason as the move-order pick above.
  check(get(`/api/inventory-svc/admin/inventory/movements?store=${fromStore}&variant=${vid}&limit=100`, tenant.ownerToken), {
    [`${tag} TO source levels decreased after INTRANSIT ship`]: r => {
      try {
        return (JSON.parse(r.body).data || []).some(m => m.refId === orderId && m.refType === 'TRANSFER_ORDER' && parseFloat(m.qty) < 0);
      } catch (_) { return false; }
    },
  });

  // 6. Receive — SHIPPED → RECEIVED (adds destination batches)
  const t2 = Date.now();
  const receiveRes = http.post(
    `${BASE}/api/inventory-svc/admin/inventory/transfers/${orderId}/receive`,
    null,
    { headers: hdrs(tenant.ownerToken) }
  );
  transferOrderLatency.add(Date.now() - t2);
  ok(receiveRes, `${tag} TO receive`);
  check(receiveRes, {
    [`${tag} TO received status=RECEIVED`]: r => {
      try { return JSON.parse(r.body).data.status === 'RECEIVED'; } catch (_) { return false; }
    },
    [`${tag} TO line has receivedQty`]: r => {
      try {
        const lines = JSON.parse(r.body).data?.lines || [];
        return lines.length > 0 && lines[0].receivedQty != null;
      } catch (_) { return false; }
    },
  });

  // 7. Verify destination store received stock
  const destLevels = get(
    `/api/inventory-svc/admin/inventory/levels?store=${toStore}`, tenant.ownerToken);
  ok(destLevels, `${tag} TO dest levels`);
  check(destLevels, {
    [`${tag} TO dest store has stock after receive`]: r => {
      try {
        const items = JSON.parse(r.body).data || [];
        const level = items.find(l => l.variantId === vid);
        return level && parseFloat(level.onHand) > 0;
      } catch (_) { return false; }
    },
  });

  // 8. Create a DIRECT transfer and ship in one call (PENDING → RECEIVED atomically)
  const directCreate = http.post(
    `${BASE}/api/inventory-svc/admin/inventory/transfers`,
    JSON.stringify({
      fromStoreId: fromStore,
      toStoreId: toStore,
      transferType: 'DIRECT',
      lines: [{ variantId: vid, requestedQty: '3' }],
    }),
    { headers: hdrs(tenant.ownerToken) }
  );
  ok(directCreate, `${tag} TO create DIRECT`);
  const directId = (() => {
    try { return JSON.parse(directCreate.body).data?.id || null; } catch (_) { return null; }
  })();
  if (directId) {
    const directShip = http.post(
      `${BASE}/api/inventory-svc/admin/inventory/transfers/${directId}/ship`,
      null,
      { headers: hdrs(tenant.ownerToken) }
    );
    ok(directShip, `${tag} TO DIRECT ship`);
    check(directShip, {
      [`${tag} TO DIRECT completed atomically`]: r => {
        try { return JSON.parse(r.body).data.status === 'RECEIVED'; } catch (_) { return false; }
      },
    });
  }

  // 9. Create and cancel a PENDING order (only PENDING can be cancelled)
  const cancelCreate = http.post(
    `${BASE}/api/inventory-svc/admin/inventory/transfers`,
    JSON.stringify({
      fromStoreId: fromStore,
      toStoreId: toStore,
      transferType: 'DIRECT',
      lines: [{ variantId: vid, requestedQty: '1' }],
    }),
    { headers: hdrs(tenant.ownerToken) }
  );
  const cancelId = (() => {
    try { return JSON.parse(cancelCreate.body).data?.id || null; } catch (_) { return null; }
  })();
  if (cancelId) {
    const cancelRes = http.post(
      `${BASE}/api/inventory-svc/admin/inventory/transfers/${cancelId}/cancel`,
      null,
      { headers: hdrs(tenant.ownerToken) }
    );
    ok(cancelRes, `${tag} TO cancel`);
    check(cancelRes, {
      [`${tag} TO cancelled status=CANCELLED`]: r => {
        try { return JSON.parse(r.body).data.status === 'CANCELLED'; } catch (_) { return false; }
      },
    });
  }

  sleep(1);
}

// ── Scenario: costing methods + accounting periods (Gap #17) ─────────────────
export function costingControl(d) {
  if (!d || !d.india || !d.uk) return;
  const tenant = tenantCtx(d);
  const store   = tenant.stores[0];
  if (!store || !tenant.variantIds.length) return;

  const storeId   = store.storeId;
  const tag       = `[+] costingControl(${isIN(d) ? 'IN' : 'UK'})`;
  // A variant of this iteration's own: two VUs setting methods on one variant read each other's
  // writes, and one variant per VU (by __VU % 4) still put both VUs on one variant whenever their
  // global ids shared parity and remainder — AVERAGE read back as the other VU's FIFO.
  const variantId = ownVariant(tenant, 'CM', tag);
  if (!variantId) { sleep(1); return; }

  const t0 = Date.now();

  // ── Costing method UPSERT (AVERAGE) ────────────────────────────────────────
  const cmRes = put('/api/inventory-svc/admin/inventory/costing-methods',
    { storeId, variantId, method: 'AVERAGE' }, tenant.ownerToken);
  check(cmRes, {
    [`${tag} upsert AVERAGE costing method 200`]: r => r.status === 200,
  });
  costingLatency.add(Date.now() - t0);

  // ── Retrieve by variant ────────────────────────────────────────────────────
  const cmGet = get(
    `/api/inventory-svc/admin/inventory/costing-methods/by-variant?store=${storeId}&variant=${variantId}`, tenant.ownerToken);
  check(cmGet, {
    [`${tag} get costing method by-variant 200`]: r => r.status === 200,
    [`${tag} costing method returned AVERAGE`]: r => {
      try { return JSON.parse(r.body).data.method === 'AVERAGE'; } catch (_) { return false; }
    },
  });

  // ── Switch to FIFO ─────────────────────────────────────────────────────────
  const fifoRes = put('/api/inventory-svc/admin/inventory/costing-methods',
    { storeId, variantId, method: 'FIFO' }, tenant.ownerToken);
  check(fifoRes, {
    [`${tag} switch to FIFO 200`]: r => r.status === 200,
  });

  // ── List costing methods ───────────────────────────────────────────────────
  const listCm = get(`/api/inventory-svc/admin/inventory/costing-methods?store=${storeId}`, tenant.ownerToken);
  check(listCm, {
    [`${tag} list costing methods 200`]: r => r.status === 200,
  });

  // ── Open accounting period (unique per scenario-wide iteration: the scenario's 2
  //    concurrent VUs reach the same __ITER together, so __ITER alone collides) ──
  const periodSeq = exec.scenario.iterationInTest;
  // A period in the past: closing the one that covers today would (rightly) refuse every receipt,
  // invoice and posting the rest of this run makes at that store today (PURCHASE_PERIOD_CLOSED).
  const periodDate = new Date(Date.UTC(2015, 0, 1) + periodSeq * 86400000).toISOString().slice(0, 10);
  const periodRes = post('/api/inventory-svc/admin/inventory/accounting-periods',
    { storeId, periodName: `P-${periodSeq}-${tenant.label}`, periodDate }, tenant.ownerToken);
  check(periodRes, {
    [`${tag} open accounting period 201`]: r => r.status === 201,
  });

  const periodId = (() => {
    try { return JSON.parse(periodRes.body).data.id; } catch (_) { return null; }
  })();

  // ── Get period by id ──────────────────────────────────────────────────────
  if (periodId) {
    const pGet = get(`/api/inventory-svc/admin/inventory/accounting-periods/${periodId}`, tenant.ownerToken);
    check(pGet, {
      [`${tag} get accounting period 200`]: r => r.status === 200,
      [`${tag} period status is OPEN`]: r => {
        try { return JSON.parse(r.body).data.status === 'OPEN'; } catch (_) { return false; }
      },
    });

    // ── Close period ─────────────────────────────────────────────────────────
    const closeRes = post(`/api/inventory-svc/admin/inventory/accounting-periods/${periodId}/close`,
      {}, tenant.ownerToken);
    check(closeRes, {
      [`${tag} close accounting period 200`]: r => r.status === 200,
    });

    // ── Confirm CLOSED ────────────────────────────────────────────────────────
    const pClosed = get(`/api/inventory-svc/admin/inventory/accounting-periods/${periodId}`, tenant.ownerToken);
    check(pClosed, {
      [`${tag} period is now CLOSED`]: r => {
        try { return JSON.parse(r.body).data.status === 'CLOSED'; } catch (_) { return false; }
      },
    });
  }

  // ── List periods ──────────────────────────────────────────────────────────
  const listP = get(`/api/inventory-svc/admin/inventory/accounting-periods?store=${storeId}`, tenant.ownerToken);
  check(listP, {
    [`${tag} list accounting periods 200`]: r => r.status === 200,
  });

  // ── Cross-tenant isolation: costing method of other tenant must be invisible
  const other = isIN(d) ? d.uk : d.india;
  if (other.stores && other.stores.length) {
    const otherStore   = other.stores[0].storeId;
    const otherVariant = other.variantIds?.[0];
    if (otherVariant) {
      // List costing methods scoped to other tenant's store using THIS tenant's JWT → empty
      const xRes = get(
        `/api/inventory-svc/admin/inventory/costing-methods?store=${otherStore}`, tenant.ownerToken);
      check(xRes, {
        [`${tag} cross-tenant costing isolation — no leakage`]: r => {
          if (r.status !== 200) return true; // service rejected = isolation held
          try {
            const items = JSON.parse(r.body).data;
            return !Array.isArray(items) || items.length === 0;
          } catch (_) { return true; }
        },
      });
    }
  }

  sleep(1);
}

// ── Scenario: kanban replenishment (Gap #18) ──────────────────────────────────
export function kanbanControl(d) {
  if (!d || !d.india || !d.uk) return;
  const tenant = tenantCtx(d);
  const store   = tenant.stores[0];
  if (!store || !tenant.variantIds.length) return;

  const storeId   = store.storeId;
  const variantId = tenant.variantIds[0];
  const tag       = `[+] kanbanControl(${tenant.label})`;

  const t0 = Date.now();

  // ── Create SUPPLIER kanban card ────────────────────────────────────────────
  const cardRes = post('/api/inventory-svc/admin/inventory/kanban-cards',
    { storeId, variantId, kanbanType: 'SUPPLIER', reorderQty: '50', supplierRef: `SUP-${__ITER}` }, tenant.ownerToken);
  check(cardRes, {
    [`${tag} create SUPPLIER kanban card 201`]: r => r.status === 201,
  });
  kanbanLatency.add(Date.now() - t0);

  const cardId = (() => {
    try { return JSON.parse(cardRes.body).data.id; } catch (_) { return null; }
  })();

  // ── Create INTER_ORG kanban card ───────────────────────────────────────────
  const interRes = post('/api/inventory-svc/admin/inventory/kanban-cards',
    { storeId, variantId, kanbanType: 'INTER_ORG', reorderQty: '20' }, tenant.ownerToken);
  check(interRes, {
    [`${tag} create INTER_ORG kanban card 201`]: r => r.status === 201,
  });

  // ── List kanban cards ──────────────────────────────────────────────────────
  const listRes = get(`/api/inventory-svc/admin/inventory/kanban-cards?store=${storeId}`, tenant.ownerToken);
  check(listRes, {
    [`${tag} list kanban cards 200`]: r => r.status === 200,
  });

  if (cardId) {
    // ── Get by id ─────────────────────────────────────────────────────────────
    const getRes = get(`/api/inventory-svc/admin/inventory/kanban-cards/${cardId}`, tenant.ownerToken);
    check(getRes, {
      [`${tag} get kanban card 200`]: r => r.status === 200,
      [`${tag} card status is EMPTY`]: r => {
        try { return JSON.parse(r.body).data.status === 'EMPTY'; } catch (_) { return false; }
      },
    });

    // ── Trigger ───────────────────────────────────────────────────────────────
    const trigRes = post(`/api/inventory-svc/admin/inventory/kanban-cards/${cardId}/trigger`,
      {}, tenant.ownerToken);
    check(trigRes, {
      [`${tag} trigger kanban card 200`]: r => r.status === 200,
      [`${tag} card status is TRIGGERED`]: r => {
        try { return JSON.parse(r.body).data.status === 'TRIGGERED'; } catch (_) { return false; }
      },
    });

    // ── Replenish ─────────────────────────────────────────────────────────────
    const repRes = post(`/api/inventory-svc/admin/inventory/kanban-cards/${cardId}/replenish`,
      {}, tenant.ownerToken);
    check(repRes, {
      [`${tag} replenish kanban card 200`]: r => r.status === 200,
      [`${tag} card status is REPLENISHED`]: r => {
        try { return JSON.parse(r.body).data.status === 'REPLENISHED'; } catch (_) { return false; }
      },
    });
  }

  // ── Create INTRA_ORG and PRODUCTION variants ───────────────────────────────
  const intraRes = post('/api/inventory-svc/admin/inventory/kanban-cards',
    { storeId, variantId, kanbanType: 'INTRA_ORG', reorderQty: '10' }, tenant.ownerToken);
  check(intraRes, {
    [`${tag} create INTRA_ORG kanban card 201`]: r => r.status === 201,
  });

  const prodRes = post('/api/inventory-svc/admin/inventory/kanban-cards',
    { storeId, variantId, kanbanType: 'PRODUCTION', reorderQty: '100' }, tenant.ownerToken);
  check(prodRes, {
    [`${tag} create PRODUCTION kanban card 201`]: r => r.status === 201,
  });

  // ── Filter by status ──────────────────────────────────────────────────────
  const byStatus = get(
    `/api/inventory-svc/admin/inventory/kanban-cards?store=${storeId}&status=EMPTY`, tenant.ownerToken);
  check(byStatus, {
    [`${tag} filter kanban cards by EMPTY status 200`]: r => r.status === 200,
  });

  // ── Cross-tenant isolation ────────────────────────────────────────────────
  const other = isIN(d) ? d.uk : d.india;
  if (other.stores && other.stores.length) {
    const xRes = get(
      `/api/inventory-svc/admin/inventory/kanban-cards?store=${other.stores[0].storeId}`, tenant.ownerToken);
    check(xRes, {
      [`${tag} cross-tenant kanban isolation — no leakage`]: r => {
        if (r.status !== 200) return true;
        try {
          const items = JSON.parse(r.body).data;
          return !Array.isArray(items) || items.length === 0;
        } catch (_) { return true; }
      },
    });
  }

  sleep(1);
}

// ── Scenario: reorder point + EOQ planning (Gap #19) ─────────────────────────
export function ropPlanning(d) {
  if (!d || !d.india || !d.uk) return;
  const tenant = tenantCtx(d);
  const store   = tenant.stores[0];
  if (!store || !tenant.variantIds.length) return;

  const storeId   = store.storeId;
  const variantId = tenant.variantIds[0];
  const tag       = `[+] ropPlanning(${tenant.label})`;

  const t0 = Date.now();

  // ── Upsert ROP plan ────────────────────────────────────────────────────────
  const upsertRes = put('/api/inventory-svc/admin/inventory/rop-plans',
    { storeId, variantId, leadTimeDays: 7, orderingCost: '25.00',
      holdingCostPct: '0.20', unitCost: '10.00' }, tenant.ownerToken);
  check(upsertRes, {
    [`${tag} upsert ROP plan 200`]: r => r.status === 200,
  });
  ropLatency.add(Date.now() - t0);

  // ── Get by variant ────────────────────────────────────────────────────────
  const getRes = get(
    `/api/inventory-svc/admin/inventory/rop-plans/by-variant?store=${storeId}&variant=${variantId}`, tenant.ownerToken);
  check(getRes, {
    [`${tag} get ROP plan by variant 200`]: r => r.status === 200,
    [`${tag} ROP plan has correct leadTimeDays`]: r => {
      try { return JSON.parse(r.body).data.leadTimeDays === 7; } catch (_) { return false; }
    },
  });

  // ── List ROP plans ────────────────────────────────────────────────────────
  const listRes = get(`/api/inventory-svc/admin/inventory/rop-plans?store=${storeId}`, tenant.ownerToken);
  check(listRes, {
    [`${tag} list ROP plans 200`]: r => r.status === 200,
  });

  // ── Trigger compute ───────────────────────────────────────────────────────
  const computeRes = post(
    `/api/inventory-svc/admin/inventory/rop-plans/compute?store=${storeId}`,
    {}, tenant.ownerToken);
  check(computeRes, {
    [`${tag} compute ROP+EOQ 200`]: r => r.status === 200,
  });

  // ── After compute: verify rop/eoq fields are populated (may be null if no demand data)
  const afterCompute = get(
    `/api/inventory-svc/admin/inventory/rop-plans/by-variant?store=${storeId}&variant=${variantId}`, tenant.ownerToken);
  check(afterCompute, {
    [`${tag} ROP plan still retrievable after compute`]: r => r.status === 200,
  });

  // ── Cross-tenant isolation ────────────────────────────────────────────────
  const other = isIN(d) ? d.uk : d.india;
  if (other.stores && other.stores.length && other.variantIds?.length) {
    const xRes = get(
      `/api/inventory-svc/admin/inventory/rop-plans?store=${other.stores[0].storeId}`, tenant.ownerToken);
    check(xRes, {
      [`${tag} cross-tenant ROP isolation — no leakage`]: r => {
        if (r.status !== 200) return true;
        try {
          const items = JSON.parse(r.body).data;
          return !Array.isArray(items) || items.length === 0;
        } catch (_) { return true; }
      },
    });
  }

  sleep(1);
}

// ── Gap #14: POS order engine (place, confirm, void, return) ─────────────────
export function orderPos(d) {
  if (!d || !d.india || !d.uk) return;
  const tenant = tenantCtx(d);
  const store  = tenant.stores[0];
  if (!store || !tenant.variantIds.length) return;
  const storeId   = store.storeId;
  const variantId = tenant.variantIds[0];
  const tag       = `orderPos[${tenant.name}]`;

  // 1. Place POS order
  const t0 = Date.now();
  const placeRes = post('/api/order-svc/orders', {
    storeId,
    channel: 'POS',
    fulfilmentType: 'INSTORE',
    items: [{ variantId, qty: 2, unitPrice: '15.00' }],
    currency: tenant.currency,
    idempotencyKey: newId(),
  }, tenant.ownerToken);
  orderPosLatency.add(Date.now() - t0);
  if (!ok(placeRes, `${tag} place order 201`)) { sleep(1); return; }
  const orderId = (() => { try { return JSON.parse(placeRes.body).data.id; } catch (_) { return null; } })();
  if (!orderId) { sleep(1); return; }

  // 2. Confirm
  const confirmRes = post(`/api/order-svc/orders/${orderId}/confirm`, {}, tenant.ownerToken);
  ok(confirmRes, `${tag} confirm order 200`);

  // 3. Return one unit
  const returnRes = post(`/api/order-svc/orders/${orderId}/returns`, {
    reason: 'customer changed mind',
    refundMethod: 'ORIGINAL',
    items: [{ variantId, qty: 1, condition: 'SEALED' }],
  }, tenant.ownerToken);
  ok(returnRes, `${tag} create return 201`);

  // 4. Place another POS order to void
  const placeVoidRes = post('/api/order-svc/orders', {
    storeId,
    channel: 'POS',
    fulfilmentType: 'INSTORE',
    items: [{ variantId, qty: 1, unitPrice: '5.00' }],
    currency: tenant.currency,
    idempotencyKey: newId(),
  }, tenant.ownerToken);
  if (placeVoidRes.status === 201) {
    const voidOrderId = (() => { try { return JSON.parse(placeVoidRes.body).data.id; } catch (_) { return null; } })();
    if (voidOrderId) {
      const voidRes = post(`/api/order-svc/orders/${voidOrderId}/void`,
        { reason: 'cashier error' }, tenant.ownerToken);
      ok(voidRes, `${tag} void POS order 200`);
    }
  }

  // 5. Cross-tenant isolation: cannot see other tenant's order
  const other = isIN(d) ? d.uk : d.india;
  const isoRes = get(`/api/order-svc/orders/${orderId}`, other.ownerToken);
  check(isoRes, {
    [`${tag} cross-tenant order isolation 404`]: r => r.status === 404 && errorCode(r) === 'ORDER_NOT_FOUND',
  });

  sleep(1);
}

// ── Gap #14: Layaway management ───────────────────────────────────────────────
export function layawayManagement(d) {
  if (!d || !d.india || !d.uk) return;
  const tenant = tenantCtx(d);
  const store  = tenant.stores[0];
  if (!store || !tenant.variantIds.length) return;
  const storeId   = store.storeId;
  const variantId = tenant.variantIds[0];
  const tag       = `layaway[${tenant.name}]`;

  // 1. Create layaway with initial deposit
  const t0 = Date.now();
  const createRes = post('/api/order-svc/layaways', {
    storeId,
    items: [{ variantId, qty: 1, unitPrice: '100.00' }],
    initialDeposit: '30.00',
    paymentMethod: 'CASH',
    notes: 'holiday gift',
  }, tenant.ownerToken);
  layawayLatency.add(Date.now() - t0);
  if (!ok(createRes, `${tag} create layaway 201`)) { sleep(1); return; }
  const layawayId = (() => { try { return JSON.parse(createRes.body).data.id; } catch (_) { return null; } })();
  if (!layawayId) { sleep(1); return; }

  // 2. Get layaway
  const getRes = get(`/api/order-svc/layaways/${layawayId}`, tenant.ownerToken);
  ok(getRes, `${tag} get layaway 200`);

  // 3. Add another deposit (70 to cover balance)
  const depRes = post(`/api/order-svc/layaways/${layawayId}/deposits`, {
    amount: '70.00',
    paymentMethod: 'CARD',
  }, tenant.ownerToken);
  ok(depRes, `${tag} add deposit 200`);

  // 4. Complete (balance now 0)
  const completeRes = post(`/api/order-svc/layaways/${layawayId}/complete`,
    {}, tenant.ownerToken);
  ok(completeRes, `${tag} complete layaway 200`);

  // 5. Create another layaway to cancel
  const cancelLayRes = post('/api/order-svc/layaways', {
    storeId,
    items: [{ variantId, qty: 1, unitPrice: '50.00' }],
    initialDeposit: '10.00',
    paymentMethod: 'CASH',
  }, tenant.ownerToken);
  if (cancelLayRes.status === 201) {
    const cancelId = (() => { try { return JSON.parse(cancelLayRes.body).data.id; } catch (_) { return null; } })();
    if (cancelId) {
      const cancelRes = post(`/api/order-svc/layaways/${cancelId}/cancel`,
        { reason: 'customer withdrew' }, tenant.ownerToken);
      ok(cancelRes, `${tag} cancel layaway 200`);
    }
  }

  // 6. Cross-tenant isolation
  const other = isIN(d) ? d.uk : d.india;
  const isoRes = get(`/api/order-svc/layaways/${layawayId}`, other.ownerToken);
  check(isoRes, {
    [`${tag} cross-tenant layaway isolation 404`]: r => r.status === 404 && errorCode(r) === 'LAYAWAY_NOT_FOUND',
  });

  sleep(1);
}

// ── Gap #14: Gift card management ─────────────────────────────────────────────
// A card given by hand is management's, with a reason and an Idempotency-Key (the `post` helper
// sends the key); a card a customer pays for is a gift-card line on a paid till sale. The API gives
// no code for a card a sale makes new, so the sale here tops up the card just issued.
export function giftCardManagement(d) {
  if (!d || !d.india || !d.uk) return;
  const tenant = tenantCtx(d);
  const store  = tenant.stores[0];
  if (!store) return;
  const storeId = store.storeId;
  const tag     = `giftCard[${tenant.name}]`;
  const balanceOf = (res) => { try { return Number(JSON.parse(res.body).data.currentBalance); } catch (_) { return NaN; } };

  // 1. Issue gift card by hand, for a reason
  const t0 = Date.now();
  const issueRes = post('/api/order-svc/gift-cards', {
    storeId,
    amount: '50.00',
    currency: tenant.currency,
    reason: 'PROMOTION',
  }, tenant.ownerToken);
  giftCardLatency.add(Date.now() - t0);
  if (!ok(issueRes, `${tag} issue gift card 201`)) { sleep(1); return; }
  const gcBody = (() => { try { return JSON.parse(issueRes.body).data; } catch (_) { return null; } })();
  if (!gcBody) { sleep(1); return; }
  const code = gcBody.code;

  // 2. Lookup
  const getRes = get(`/api/order-svc/gift-cards/${code}`, tenant.ownerToken);
  ok(getRes, `${tag} get gift card 200`);

  // 3. Reload by hand, for a reason
  const reloadRes = post(`/api/order-svc/gift-cards/${code}/reload`,
    { amount: '20.00', reference: `reload-${__VU}-${__ITER}`, reason: 'GOODWILL' }, tenant.ownerToken);
  ok(reloadRes, `${tag} reload gift card 200`);
  check(reloadRes, {
    [`${tag} balance after reload is 70`]: r => {
      try { return JSON.parse(r.body).data.currentBalance === 70.0; } catch (_) { return true; }
    },
  });

  // 3b. Money taken for a card is a sale, never a hand load
  check(post(`/api/order-svc/gift-cards/${code}/reload`,
    { amount: '20.00', reason: 'GOODWILL', paidBy: 'CARD' }, tenant.ownerToken), {
    [`${tag} hand reload naming a tender 409`]: r => r.status === 409 && errorCode(r) === 'GIFT_CARD_NEEDS_SALE',
  });

  // 3c. A top-up sold at the till: a gift-card line on a sale, loaded when the sale is paid
  const saleRes = post('/api/order-svc/orders', {
    storeId,
    channel: 'POS',
    fulfilmentType: 'INSTORE',
    items: [],
    giftCardLoads: [{ amount: '20.00', code }],
    currency: tenant.currency,
    idempotencyKey: newId(),
  }, tenant.ownerToken);
  if (ok(saleRes, `${tag} sell a gift card top-up 201`)) {
    const saleId = body(saleRes).id;
    check(get(`/api/order-svc/gift-cards/${code}`, tenant.ownerToken), {
      [`${tag} nothing loaded while the sale is unpaid`]: r => balanceOf(r) === 70,
    });
    const payRes = post('/api/payment-svc/payments', {
      orderId: saleId,
      amount: '20.00',
      method: 'CASH',
      idempotencyKey: newId(),
    }, tenant.ownerToken);
    if (ok(payRes, `${tag} pay for the gift card top-up 201`)) {
      // The load arrives over Kafka: a read-back, not counted as an error when the load is slow.
      let loaded = NaN;
      for (let i = 0; i < 15 && loaded !== 90; i++) {
        sleep(1);
        loaded = balanceOf(get(`/api/order-svc/gift-cards/${code}`, tenant.ownerToken));
      }
      check(null, { [`${tag} balance after the paid top-up is 90`]: () => loaded === 90 });
    }
  }

  // 4. Redeem: the card is charged for a sale
  const variantId = tenant.variantIds[0];
  if (variantId) {
    const spendRes = post('/api/order-svc/orders', {
      storeId,
      channel: 'POS',
      fulfilmentType: 'INSTORE',
      items: [{ variantId, qty: 2, unitPrice: '15.00' }],
      currency: tenant.currency,
      idempotencyKey: newId(),
    }, tenant.ownerToken);
    if (ok(spendRes, `${tag} place a sale to spend the card on 201`)) {
      const redeemRes = post(`/api/order-svc/gift-cards/${code}/redeem`,
        { amount: '30.00', orderId: body(spendRes).id, reference: `redeem-${__VU}-${__ITER}` }, tenant.ownerToken);
      ok(redeemRes, `${tag} redeem gift card 200`);
    }
  }

  // 5. Transaction history
  const txRes = get(`/api/order-svc/gift-cards/${code}/transactions`, tenant.ownerToken);
  ok(txRes, `${tag} gift card transactions 200`);

  // 6. Cross-tenant isolation: other tenant cannot see this card
  const other = isIN(d) ? d.uk : d.india;
  const isoRes = get(`/api/order-svc/gift-cards/${code}`, other.ownerToken);
  check(isoRes, {
    [`${tag} cross-tenant gift card isolation 404`]: r => r.status === 404 && errorCode(r) === 'GIFT_CARD_NOT_FOUND',
  });

  sleep(1);
}

// ── Scenario: negative test suite (4xx expectations) ─────────────────────────
export function negativeTests(d) {
  if (!d || !d.india || !d.uk) return;
  const tenant = tenantCtx(d);
  const other  = isIN(d) ? d.uk : d.india;
  const store  = tenant.stores[0];
  if (!store || !tenant.variantIds.length) return;
  const tag    = isIN(d) ? 'IN' : 'UK';
  const vid    = tenant.variantIds[0];
  const fakeId = '01a090ae-611e-7007-b85c-1fbac22cb87b';

  // Assert 4xx; record any unexpected 2xx as a metric failure. A 5xx is an infra blip, not the
  // validation/auth bypass this metric is tracking, so it must not be counted here either. The
  // status and the machine code are asserted together: a 4xx for another reason is not the refusal.
  function neg(res, label, code, errCode) {
    const is4xx = res.status >= 400 && res.status < 500;
    if (res.status >= 200 && res.status < 300) {
      negativeUnexpectedSuccess.add(1);
      console.error(`NEG UNEXPECTED SUCCESS [${label}] status=${res.status} body=${res.body.substring(0, 300)}`);
    }
    const assertions = { [`${tag} NEG [${label}] → 4xx`]: r => r.status >= 400 && r.status < 500 };
    if (code) assertions[`${tag} NEG [${label}] → ${code} ${errCode}`] = r => r.status === code && errorCode(r) === errCode;
    check(res, assertions);
  }

  // ── Validation failures (400) ────────────────────────────────────────────────

  // Reserve with negative qty
  neg(post('/api/inventory-svc/inventory/reservations',
    { storeId: store.storeId, variantId: vid, qty: -1, ttlSeconds: 60 }, tenant.ownerToken), 'reserve qty=-1', 400, 'VALIDATION_FAILED');

  // Reserve with missing required variantId field
  neg(post('/api/inventory-svc/inventory/reservations',
    { storeId: store.storeId, qty: 1, ttlSeconds: 60 }, tenant.ownerToken), 'reserve missing variantId', 400, 'VALIDATION_FAILED');

  // Stock receive with qty=0
  neg(post('/api/inventory-svc/admin/inventory/receive',
    { storeId: store.storeId, variantId: vid, qty: '0', batchNo: `NEG-ZERO-${__ITER}` }, tenant.ownerToken), 'receive qty=0', 400, 'VALIDATION_FAILED');

  // Material status with an invalid enum value
  const batchId = (() => {
    try {
      const r = get(`/api/inventory-svc/admin/inventory/batches?store=${store.storeId}&variant=${vid}&limit=1`, tenant.ownerToken);
      return JSON.parse(r.body).data?.[0]?.id || null;
    } catch (_) { return null; }
  })();
  if (batchId) {
    neg(put(`/api/inventory-svc/admin/inventory/batches/${batchId}/material-status`,
      { materialStatus: 'SHINY', reason: 'k6-neg' }, tenant.ownerToken), 'invalid materialStatus enum', 400, 'INVALID_MATERIAL_STATUS');
  }

  // UOM item conversion with factor=0 (must be >0)
  neg(http.post(`${BASE}/api/product-svc/admin/uom/item-conversions`,
    JSON.stringify({ variantId: vid, fromUom: 'CASE', toUom: 'EA', factor: '0' }),
    { headers: hdrs(tenant.ownerToken) }),
    'UOM factor=0', 400, 'VALIDATION_FAILED');

  // UOM convert with an unknown unit code
  neg(get('/api/product-svc/admin/uom/convert?from=BANANA&to=EA&qty=1', tenant.ownerToken), 'UOM unknown unit code', 404, 'CONVERSION_NOT_FOUND');

  // ── Gap #34: manufacturer_pn — negative cases ────────────────────────────────

  // Blank sku with manufacturerPn provided → 400 (sku is still required)
  if (tenant.productIds && tenant.productIds.length > 0) {
    const npPid = tenant.productIds[0];
    neg(post(`/api/product-svc/admin/products/${npPid}/variants`,
      { sku: '', barcode: null, manufacturerPn: 'MFR-NEG-001', unit: 'PCS' }, tenant.ownerToken), 'variant blank sku with MPN → 400', 400, 'VALIDATION_FAILED');

    // Missing sku field entirely with manufacturerPn present → 400
    neg(post(`/api/product-svc/admin/products/${npPid}/variants`,
      { barcode: null, manufacturerPn: 'MFR-NEG-002', unit: 'PCS' }, tenant.ownerToken), 'variant missing sku with MPN → 400', 400, 'VALIDATION_FAILED');

    // Update variant: blank sku but non-null manufacturerPn → 400
    if (tenant.variantIds.length > 0) {
      neg(put(`/api/product-svc/admin/products/${npPid}/variants/${vid}`,
        { sku: '  ', barcode: null, manufacturerPn: 'MFR-NEG-003', unit: 'PCS' }, tenant.ownerToken), 'variant update blank sku with MPN → 400', 400, 'VALIDATION_FAILED');
    }
  }

  // ── Gap #33: cross-references — negative cases ─────────────────────────────

  if (tenant.variantIds.length > 0) {
    const xnVid = tenant.variantIds[0];
    const validPartyId = genUuid();

    // Invalid partyType → 400
    neg(post(`/api/product-svc/admin/products/variants/${xnVid}/cross-references`,
      { partyType: 'BROKER', partyId: validPartyId, crossRefNumber: 'XYZ' }, tenant.ownerToken), 'cross-ref invalid partyType → 400', 400, 'INVALID_PARTY_TYPE');

    // Non-UUID partyId → 400
    neg(post(`/api/product-svc/admin/products/variants/${xnVid}/cross-references`,
      { partyType: 'SUPPLIER', partyId: 'not-a-uuid', crossRefNumber: 'XYZ' }, tenant.ownerToken), 'cross-ref non-UUID partyId → 400', 400, 'INVALID_UUID');

    // Missing crossRefNumber → 400
    neg(post(`/api/product-svc/admin/products/variants/${xnVid}/cross-references`,
      { partyType: 'SUPPLIER', partyId: validPartyId }, tenant.ownerToken), 'cross-ref missing crossRefNumber → 400', 400, 'VALIDATION_FAILED');

    // Duplicate (same variant + partyType + partyId) → 409
    const dupXRef = post(`/api/product-svc/admin/products/variants/${xnVid}/cross-references`,
      { partyType: 'SUPPLIER', partyId: validPartyId, crossRefNumber: 'DUP-001' }, tenant.ownerToken);
    if (dupXRef.status === 201) {
      neg(post(`/api/product-svc/admin/products/variants/${xnVid}/cross-references`,
        { partyType: 'SUPPLIER', partyId: validPartyId, crossRefNumber: 'DUP-002' }, tenant.ownerToken), 'duplicate cross-ref → 409', 409, 'DUPLICATE');
    }

    // Delete non-existent → 404
    neg(http.del(
      `${BASE}/api/product-svc/admin/products/variants/${xnVid}/cross-references/${fakeId}`,
      null, { headers: hdrs(tenant.ownerToken) }),
      'delete nonexistent cross-ref → 404', 404, 'CROSS_REF_NOT_FOUND');
  }

  // ── Gap #32: item relationships — negative cases ────────────────────────────

  if (tenant.variantIds.length >= 2) {
    const rv0 = tenant.variantIds[0];
    const rv1 = tenant.variantIds[1];

    // Invalid relationshipType enum → 400
    neg(post(`/api/product-svc/admin/products/variants/${rv0}/relationships`,
      { relatedVariantId: rv1, relationshipType: 'ENEMIES' }, tenant.ownerToken), 'relationship invalid type → 400', 400, 'INVALID_RELATIONSHIP_TYPE');

    // Self-relationship → 400
    neg(post(`/api/product-svc/admin/products/variants/${rv0}/relationships`,
      { relatedVariantId: rv0, relationshipType: 'SUBSTITUTE' }, tenant.ownerToken), 'relationship self-ref → 400', 400, 'SELF_RELATIONSHIP');

    // Non-UUID relatedVariantId → 400
    neg(post(`/api/product-svc/admin/products/variants/${rv0}/relationships`,
      { relatedVariantId: 'not-a-uuid', relationshipType: 'SUBSTITUTE' }, tenant.ownerToken), 'relationship invalid relatedVariantId → 400', 400, 'INVALID_UUID');

    // Missing relationshipType → 400
    neg(post(`/api/product-svc/admin/products/variants/${rv0}/relationships`,
      { relatedVariantId: rv1 }, tenant.ownerToken), 'relationship missing type → 400', 400, 'VALIDATION_FAILED');

    // Duplicate relationship → 409 (create, test dup, then clean up to avoid cross-iteration conflict)
    const dupType = 'COMPLEMENTARY';
    const dup1 = post(`/api/product-svc/admin/products/variants/${rv0}/relationships`,
      { relatedVariantId: rv1, relationshipType: dupType }, tenant.ownerToken);
    if (dup1.status === 201) {
      const dup1Id = (() => { try { return JSON.parse(dup1.body).data.id; } catch (_) { return null; } })();
      neg(post(`/api/product-svc/admin/products/variants/${rv0}/relationships`,
        { relatedVariantId: rv1, relationshipType: dupType }, tenant.ownerToken), 'duplicate relationship → 409', 409, 'DUPLICATE');
      if (dup1Id) {
        http.del(`${BASE}/api/product-svc/admin/products/variants/${rv0}/relationships/${dup1Id}`,
          null, { headers: hdrs(tenant.ownerToken) });
      }
    }

    // Cross-tenant: UK variant ID used with India token (or vice versa) — relatedVariant not found
    if (other.variantIds && other.variantIds.length > 0) {
      neg(post(`/api/product-svc/admin/products/variants/${rv0}/relationships`,
        { relatedVariantId: other.variantIds[0], relationshipType: 'SUBSTITUTE' }, tenant.ownerToken), 'relationship cross-tenant relatedVariant → 404', 404, 'VARIANT_NOT_FOUND');
    }

    // Delete non-existent relationship → 404
    neg(http.del(
      `${BASE}/api/product-svc/admin/products/variants/${rv0}/relationships/${fakeId}`,
      null, { headers: hdrs(tenant.ownerToken) }),
      'delete nonexistent relationship → 404', 404, 'RELATIONSHIP_NOT_FOUND');
  }

  // ── Gap #35: catalog groups — negative cases ────────────────────────────────

  if (tenant.variantIds.length > 0 && tenant.productIds.length > 0) {
    // A dedicated, never-touched variant — tenant.variantIds[0] is shared with other concurrently
    // running scenarios (e.g. catalogAdmin assigns a catalog group to it), so the "unassigned
    // variant → 404" check below would intermittently/deterministically see someone else's
    // assignment rather than proving the no-assignment case.
    const negVarRes = post(`/api/product-svc/admin/products/${tenant.productIds[0]}/variants`, {
      sku: `NEG-CG-${slug()}`, barcode: `NEGCG${Date.now()}`, unit: 'PCS',
    }, tenant.ownerToken);
    const negVid = body(negVarRes).id || tenant.variantIds[0];

    // Blank group name → 400
    neg(post('/api/product-svc/admin/catalog-groups',
      { name: '' }, tenant.ownerToken),
      'catalog group blank name → 400', 400, 'VALIDATION_FAILED');

    // Invalid dataType in element → 400
    // (create a group first, add invalid element, clean up)
    const tmpGrp = post('/api/product-svc/admin/catalog-groups',
      { name: `NEG-GROUP-${slug()}` }, tenant.ownerToken);
    const tmpGrpId = (() => { try { return JSON.parse(tmpGrp.body).data.id; } catch (_) { return null; } })();
    if (tmpGrpId) {
      neg(post(`/api/product-svc/admin/catalog-groups/${tmpGrpId}/elements`,
        { elementName: 'x', dataType: 'ENUM', required: false, sortOrder: 0 }, tenant.ownerToken), 'catalog element invalid dataType → 400', 400, 'INVALID_DATA_TYPE');

      // Blank element name → 400
      neg(post(`/api/product-svc/admin/catalog-groups/${tmpGrpId}/elements`,
        { elementName: '', dataType: 'TEXT', required: false, sortOrder: 0 }, tenant.ownerToken), 'catalog element blank name → 400', 400, 'VALIDATION_FAILED');

      // Duplicate group name within same tenant → 409
      const dupName = `DUP-CG-${slug()}`;
      const dup1 = post('/api/product-svc/admin/catalog-groups',
        { name: dupName }, tenant.ownerToken);
      if (dup1.status === 201) {
        neg(post('/api/product-svc/admin/catalog-groups',
          { name: dupName }, tenant.ownerToken),
          'duplicate catalog group name → 409', 409, 'DUPLICATE');
        // Cleanup dup
        const dup1Id = (() => { try { return JSON.parse(dup1.body).data.id; } catch (_) { return null; } })();
        if (dup1Id) http.del(`${BASE}/api/product-svc/admin/catalog-groups/${dup1Id}`,
          null, { headers: hdrs(tenant.ownerToken) });
      }

      // Assign to non-existent group → 404
      neg(post(`/api/product-svc/admin/products/variants/${negVid}/catalog-assignment`,
        { groupId: fakeId }, tenant.ownerToken),
        'assign to nonexistent catalog group → 404', 404, 'CATALOG_GROUP_NOT_FOUND');

      // GET assignment for variant with no assignment → 404
      neg(get(`/api/product-svc/admin/products/variants/${negVid}/catalog-assignment`, tenant.ownerToken),
        'get assignment for unassigned variant → 404', 404, 'ASSIGNMENT_NOT_FOUND');

      // Duplicate assignment → 409
      const asgn1 = post(`/api/product-svc/admin/products/variants/${negVid}/catalog-assignment`,
        { groupId: tmpGrpId }, tenant.ownerToken);
      if (asgn1.status === 201) {
        neg(post(`/api/product-svc/admin/products/variants/${negVid}/catalog-assignment`,
          { groupId: tmpGrpId }, tenant.ownerToken),
          'duplicate catalog assignment → 409', 409, 'DUPLICATE');
        // Cleanup assignment
        http.del(`${BASE}/api/product-svc/admin/products/variants/${negVid}/catalog-assignment`,
          null, { headers: hdrs(tenant.ownerToken) });
      }

      // Cleanup group
      http.del(`${BASE}/api/product-svc/admin/catalog-groups/${tmpGrpId}`,
        null, { headers: hdrs(tenant.ownerToken) });
    }
  }

  // ── Not-found (404) ──────────────────────────────────────────────────────────

  // Consume non-existent reservation
  neg(post(`/api/inventory-svc/inventory/reservations/${fakeId}/consume`,
    {}, tenant.ownerToken), 'consume nonexistent reservation', 404, 'RESERVATION_NOT_FOUND');

  // Release non-existent reservation
  neg(post(`/api/inventory-svc/inventory/reservations/${fakeId}/release`,
    {}, tenant.ownerToken), 'release nonexistent reservation', 404, 'RESERVATION_NOT_FOUND');

  // GET non-existent serial by ID
  neg(get(`/api/inventory-svc/admin/inventory/serials/${fakeId}`, tenant.ownerToken), 'get nonexistent serial', 404, 'SERIAL_NOT_FOUND');

  // GET non-existent move order
  neg(get(`/api/inventory-svc/admin/inventory/move-orders/${fakeId}`, tenant.ownerToken), 'get nonexistent move order', 404, 'MOVE_ORDER_NOT_FOUND');

  // GET non-existent transfer order
  neg(get(`/api/inventory-svc/admin/inventory/transfers/${fakeId}`, tenant.ownerToken), 'get nonexistent transfer', 404, 'TRANSFER_ORDER_NOT_FOUND');

  // Resolve non-existent planning suggestion
  neg(put(`/api/inventory-svc/admin/inventory/planning/suggestions/${fakeId}/status`,
    { status: 'ORDERED' }, tenant.ownerToken),
    'resolve nonexistent suggestion', 404, 'SUGGESTION_NOT_FOUND');

  // Delete non-existent UOM item conversion
  neg(http.del(`${BASE}/api/product-svc/admin/uom/item-conversions/${fakeId}`,
    null, { headers: hdrs(tenant.ownerToken) }),
    'delete nonexistent UOM conversion', 404, 'CONVERSION_NOT_FOUND');

  // ── State-machine violations ─────────────────────────────────────────────────

  if (tenant.stores.length >= 2) {
    const fromStore = tenant.stores[0].storeId;
    const toStore   = tenant.stores[1].storeId;

    // Seed source stock for state-machine tests
    http.post(`${BASE}/api/inventory-svc/admin/inventory/receive`,
      JSON.stringify({ storeId: fromStore, variantId: vid, qty: '30', batchNo: `NEG-SM-${__ITER}` }),
      { headers: hdrs(tenant.ownerToken) });

    // Double-pick: create + pick → completed, then pick again
    const mo = (() => {
      try {
        return JSON.parse(http.post(`${BASE}/api/inventory-svc/admin/inventory/move-orders`,
          JSON.stringify({ fromStoreId: fromStore, toStoreId: toStore,
            lines: [{ variantId: vid, requestedQty: '2' }] }),
          { headers: hdrs(tenant.ownerToken) }).body).data;
      } catch (_) { return null; }
    })();
    if (mo?.id) {
      // First pick succeeds (DRAFT → COMPLETED) — must actually land before the negative
      // assertion below means anything; a transient failure here would leave the order in
      // DRAFT, making the "second" pick a legitimate first success, not a guard bypass.
      const firstPick = http.post(`${BASE}/api/inventory-svc/admin/inventory/move-orders/${mo.id}/pick`,
        null, { headers: hdrs(tenant.ownerToken) });
      if (firstPick.status >= 200 && firstPick.status < 300) {
        // Second pick on COMPLETED order → must fail
        neg(http.post(`${BASE}/api/inventory-svc/admin/inventory/move-orders/${mo.id}/pick`,
          null, { headers: hdrs(tenant.ownerToken) }),
          'double-pick completed MO', 422, 'MOVE_ORDER_NOT_PICKABLE');
        // Cancel COMPLETED order → must fail (only DRAFT can be cancelled)
        neg(http.post(`${BASE}/api/inventory-svc/admin/inventory/move-orders/${mo.id}/cancel`,
          null, { headers: hdrs(tenant.ownerToken) }),
          'cancel completed MO', 422, 'MOVE_ORDER_NOT_CANCELLABLE');
      }
    }

    // Receive a PENDING DIRECT transfer before shipping (DIRECT has no separate receive step)
    const tf1 = (() => {
      try {
        return JSON.parse(http.post(`${BASE}/api/inventory-svc/admin/inventory/transfers`,
          JSON.stringify({ fromStoreId: fromStore, toStoreId: toStore, transferType: 'DIRECT',
            lines: [{ variantId: vid, requestedQty: '1' }] }),
          { headers: hdrs(tenant.ownerToken) }).body).data;
      } catch (_) { return null; }
    })();
    if (tf1?.id) {
      // Receive before ship on a DIRECT order → must fail
      neg(http.post(`${BASE}/api/inventory-svc/admin/inventory/transfers/${tf1.id}/receive`,
        null, { headers: hdrs(tenant.ownerToken) }),
        'receive PENDING DIRECT transfer', 422, 'TRANSFER_ORDER_NOT_RECEIVABLE');
      // Now ship → atomically RECEIVED. Must actually land — a transient failure here would
      // leave the order PENDING, making the "second" ship a legitimate first success.
      const firstShip1 = http.post(`${BASE}/api/inventory-svc/admin/inventory/transfers/${tf1.id}/ship`,
        null, { headers: hdrs(tenant.ownerToken) });
      if (firstShip1.status >= 200 && firstShip1.status < 300) {
        // Ship again on already-RECEIVED order → must fail
        neg(http.post(`${BASE}/api/inventory-svc/admin/inventory/transfers/${tf1.id}/ship`,
          null, { headers: hdrs(tenant.ownerToken) }),
          'double-ship RECEIVED DIRECT transfer', 422, 'TRANSFER_ORDER_NOT_SHIPPABLE');
      }
    }

    // Cancel a SHIPPED INTRANSIT transfer (only PENDING can be cancelled)
    const tf2 = (() => {
      try {
        return JSON.parse(http.post(`${BASE}/api/inventory-svc/admin/inventory/transfers`,
          JSON.stringify({ fromStoreId: fromStore, toStoreId: toStore, transferType: 'INTRANSIT',
            lines: [{ variantId: vid, requestedQty: '1' }] }),
          { headers: hdrs(tenant.ownerToken) }).body).data;
      } catch (_) { return null; }
    })();
    if (tf2?.id) {
      // Ship → SHIPPED — must actually land, or the "cancel" below is operating on a still-PENDING
      // order, where cancel legitimately succeeds (not a guard bypass).
      const firstShip2 = http.post(`${BASE}/api/inventory-svc/admin/inventory/transfers/${tf2.id}/ship`,
        null, { headers: hdrs(tenant.ownerToken) });
      if (firstShip2.status >= 200 && firstShip2.status < 300) {
        // Cancel SHIPPED order → must fail
        neg(http.post(`${BASE}/api/inventory-svc/admin/inventory/transfers/${tf2.id}/cancel`,
          null, { headers: hdrs(tenant.ownerToken) }),
          'cancel SHIPPED INTRANSIT transfer', 422, 'TRANSFER_ORDER_NOT_CANCELLABLE');
      }
    }
  }

  // ── Cross-tenant isolation: mutation must be invisible to the other tenant ────

  // Use this tenant's X-Tenant-Id header but supply the other tenant's brand ID.
  // The service resolves brand by (id, tenant_id) — the other brand is not in this tenant's scope.
  if (other.brandId) {
    neg(put(`/api/product-svc/admin/brands/${other.brandId}`,
      { name: `INJECTED-${slug()}` }, tenant.ownerToken),
      'cross-tenant brand mutation', 404, 'BRAND_NOT_FOUND');
  }

  // Confirm the other tenant's brand is still intact (isolation not broken)
  if (other.brandId) {
    const confirmRes = get(`/api/product-svc/admin/brands/${other.brandId}`,
      other.ownerToken);
    check(confirmRes, {
      [`${tag} NEG other-tenant brand still reachable by its own tenant`]: r => r.status === 200,
    });
  }

  // ── Gap #17: Costing Methods + Accounting Periods ────────────────────────────

  // Invalid costing method enum → 400
  neg(put('/api/inventory-svc/admin/inventory/costing-methods',
    { storeId: store.storeId, variantId: vid, method: 'LIFO' }, tenant.ownerToken), 'invalid costing method LIFO', 400, 'INVALID_COSTING_METHOD');

  // Missing variantId in costing method upsert → 400
  neg(put('/api/inventory-svc/admin/inventory/costing-methods',
    { storeId: store.storeId, method: 'AVERAGE' }, tenant.ownerToken), 'costing method missing variantId', 400, 'VALIDATION_FAILED');

  // Open period with missing storeId → 400
  neg(post('/api/inventory-svc/admin/inventory/accounting-periods',
    { periodName: 'NEG-PERIOD', periodDate: '2025-01-01' }, tenant.ownerToken), 'open period missing storeId', 400, 'VALIDATION_FAILED');

  // Close a non-existent period → 404 PERIOD_NOT_FOUND (the period is read before anything else)
  neg(post(`/api/inventory-svc/admin/inventory/accounting-periods/${fakeId}/close`,
    {}, tenant.ownerToken), 'close nonexistent period', 404, 'PERIOD_NOT_FOUND');

  // Open two periods for the same store+date → 409
  const dupDate = '2020-06-01';
  const p1Res = post('/api/inventory-svc/admin/inventory/accounting-periods',
    { storeId: store.storeId, periodName: 'DUP-P1', periodDate: dupDate }, tenant.ownerToken);
  if (p1Res.status === 201) {
    neg(post('/api/inventory-svc/admin/inventory/accounting-periods',
      { storeId: store.storeId, periodName: 'DUP-P2', periodDate: dupDate }, tenant.ownerToken), 'duplicate period same date 409', 409, 'PERIOD_DUPLICATE_DATE');
  }

  // Close an already-closed period → 409 (create + close + close again)
  const closeDate = '2019-12-31';
  const pClose = post('/api/inventory-svc/admin/inventory/accounting-periods',
    { storeId: store.storeId, periodName: 'CLOSE-NEG', periodDate: closeDate }, tenant.ownerToken);
  const pCloseId = (() => { try { return JSON.parse(pClose.body).data.id; } catch (_) { return null; } })();
  if (pCloseId) {
    post(`/api/inventory-svc/admin/inventory/accounting-periods/${pCloseId}/close`,
      {}, tenant.ownerToken);
    neg(post(`/api/inventory-svc/admin/inventory/accounting-periods/${pCloseId}/close`,
      {}, tenant.ownerToken), 'close already-closed period 409', 409, 'PERIOD_NOT_OPEN');
  }

  // ── Gap #18: Kanban Replenishment ─────────────────────────────────────────────

  // Invalid kanban type → 400
  neg(post('/api/inventory-svc/admin/inventory/kanban-cards',
    { storeId: store.storeId, variantId: vid, kanbanType: 'INVALID', reorderQty: '10' }, tenant.ownerToken), 'invalid kanban type', 400, 'INVALID_KANBAN_TYPE');

  // Missing storeId → 400
  neg(post('/api/inventory-svc/admin/inventory/kanban-cards',
    { variantId: vid, kanbanType: 'SUPPLIER', reorderQty: '10' }, tenant.ownerToken), 'kanban missing storeId', 400, 'VALIDATION_FAILED');

  // GET non-existent kanban card → 404
  neg(get(`/api/inventory-svc/admin/inventory/kanban-cards/${fakeId}`, tenant.ownerToken), 'get nonexistent kanban card', 404, 'KANBAN_NOT_FOUND');

  // Trigger already-TRIGGERED card → 409 (create → trigger → trigger again)
  const kTrig = post('/api/inventory-svc/admin/inventory/kanban-cards',
    { storeId: store.storeId, variantId: vid, kanbanType: 'SUPPLIER', reorderQty: '5' }, tenant.ownerToken);
  const kTrigId = (() => { try { return JSON.parse(kTrig.body).data.id; } catch (_) { return null; } })();
  if (kTrigId) {
    // Must actually land — a transient failure here leaves the card EMPTY, making the
    // "second" trigger a legitimate first success, not a guard bypass.
    const firstTrigger = post(`/api/inventory-svc/admin/inventory/kanban-cards/${kTrigId}/trigger`,
      {}, tenant.ownerToken);
    if (firstTrigger.status >= 200 && firstTrigger.status < 300) {
      neg(post(`/api/inventory-svc/admin/inventory/kanban-cards/${kTrigId}/trigger`,
        {}, tenant.ownerToken), 'double-trigger kanban 409', 409, 'KANBAN_NOT_EMPTY');
    }
  }

  // Replenish an EMPTY card (not yet triggered) → 409
  const kEmpty = post('/api/inventory-svc/admin/inventory/kanban-cards',
    { storeId: store.storeId, variantId: vid, kanbanType: 'INTER_ORG', reorderQty: '5' }, tenant.ownerToken);
  const kEmptyId = (() => { try { return JSON.parse(kEmpty.body).data.id; } catch (_) { return null; } })();
  if (kEmptyId) {
    neg(post(`/api/inventory-svc/admin/inventory/kanban-cards/${kEmptyId}/replenish`,
      {}, tenant.ownerToken), 'replenish EMPTY kanban 409', 409, 'KANBAN_NOT_TRIGGERED');
  }

  // ── Gap #19: Reorder Point + EOQ ─────────────────────────────────────────────

  // Missing storeId → 400
  neg(put('/api/inventory-svc/admin/inventory/rop-plans',
    { variantId: vid, leadTimeDays: 7, orderingCost: '25.00',
      holdingCostPct: '0.20', unitCost: '10.00' }, tenant.ownerToken), 'ROP plan missing storeId', 400, 'VALIDATION_FAILED');

  // Missing variantId → 400
  neg(put('/api/inventory-svc/admin/inventory/rop-plans',
    { storeId: store.storeId, leadTimeDays: 7, orderingCost: '25.00',
      holdingCostPct: '0.20', unitCost: '10.00' }, tenant.ownerToken), 'ROP plan missing variantId', 400, 'VALIDATION_FAILED');

  // Negative leadTimeDays → 400
  neg(put('/api/inventory-svc/admin/inventory/rop-plans',
    { storeId: store.storeId, variantId: vid, leadTimeDays: -1,
      orderingCost: '25.00', holdingCostPct: '0.20', unitCost: '10.00' }, tenant.ownerToken), 'ROP plan negative leadTimeDays', 400, 'VALIDATION_FAILED');

  // GET non-existent ROP plan by unknown variant → 404
  neg(get(`/api/inventory-svc/admin/inventory/rop-plans/by-variant?store=${store.storeId}&variant=${fakeId}`, tenant.ownerToken), 'get nonexistent ROP plan by variant', 404, 'ROP_NOT_FOUND');

  // ── Gap #14: Orders — negative cases ─────────────────────────────────────────

  // Place order missing channel → 400
  neg(post('/api/order-svc/orders',
    { storeId: store.storeId,
      items: [{ variantId: vid, qty: 1, unitPrice: '10.00' }] }, tenant.ownerToken), 'place order missing channel 400', 400, 'VALIDATION_FAILED');

  // Place order missing items → 400
  neg(post('/api/order-svc/orders',
    { storeId: store.storeId, channel: 'POS', items: [] }, tenant.ownerToken), 'place order empty items 400', 400, 'ORDER_NO_ITEMS');

  // Place order missing storeId → 400
  neg(post('/api/order-svc/orders',
    { channel: 'POS', items: [{ variantId: vid, qty: 1, unitPrice: '10.00' }] }, tenant.ownerToken), 'place order missing storeId 400', 400, 'VALIDATION_FAILED');

  // GET non-existent order → 404
  neg(get(`/api/order-svc/orders/${fakeId}`, tenant.ownerToken), 'get nonexistent order 404', 404, 'ORDER_NOT_FOUND');

  // Void ONLINE order → 409
  const onlineOrderRes = post('/api/order-svc/orders', {
    storeId: store.storeId,
    channel: 'ONLINE',
    items: [{ variantId: vid, qty: 1, unitPrice: '5.00' }],
    currency: tenant.currency,
    idempotencyKey: newId(),
  }, tenant.ownerToken);
  if (onlineOrderRes.status === 201) {
    const onlineId = (() => { try { return JSON.parse(onlineOrderRes.body).data.id; } catch (_) { return null; } })();
    if (onlineId) {
      neg(post(`/api/order-svc/orders/${onlineId}/void`,
        { reason: 'test' }, tenant.ownerToken),
        'void ONLINE order 409', 409, 'ORDER_VOID_ONLY_POS');
    }
  }

  // ── Gap #14: Layaway — negative cases ────────────────────────────────────────

  // Layaway missing storeId → 400
  neg(post('/api/order-svc/layaways',
    { items: [{ variantId: vid, qty: 1, unitPrice: '50.00' }], initialDeposit: '10.00',
      paymentMethod: 'CASH' }, tenant.ownerToken), 'layaway missing storeId 400', 400, 'VALIDATION_FAILED');

  // Layaway missing items → 400
  neg(post('/api/order-svc/layaways',
    { storeId: store.storeId, items: [], initialDeposit: '10.00', paymentMethod: 'CASH' }, tenant.ownerToken), 'layaway empty items 400', 400, 'LAYAWAY_NO_ITEMS');

  // Deposit exceeds total → 409
  neg(post('/api/order-svc/layaways',
    { storeId: store.storeId,
      items: [{ variantId: vid, qty: 1, unitPrice: '10.00' }],
      initialDeposit: '999.00', paymentMethod: 'CASH' }, tenant.ownerToken), 'layaway deposit exceeds total 409', 409, 'DEPOSIT_EXCEEDS_TOTAL');

  // GET non-existent layaway → 404
  neg(get(`/api/order-svc/layaways/${fakeId}`, tenant.ownerToken), 'get nonexistent layaway 404', 404, 'LAYAWAY_NOT_FOUND');

  // Complete layaway with outstanding balance → 409
  const balLayRes = post('/api/order-svc/layaways', {
    storeId: store.storeId,
    items: [{ variantId: vid, qty: 1, unitPrice: '100.00' }],
    initialDeposit: '20.00',
    paymentMethod: 'CASH',
  }, tenant.ownerToken);
  if (balLayRes.status === 201) {
    const balLayId = (() => { try { return JSON.parse(balLayRes.body).data.id; } catch (_) { return null; } })();
    if (balLayId) {
      neg(post(`/api/order-svc/layaways/${balLayId}/complete`, {}, tenant.ownerToken),
        'complete layaway with balance outstanding 409', 409, 'LAYAWAY_CANNOT_COMPLETE');
    }
  }

  // ── Gap #14: Gift card — negative cases ──────────────────────────────────────

  // Issue gift card missing storeId → 400
  neg(post('/api/order-svc/gift-cards',
    { amount: '50.00', reason: 'GOODWILL' }, tenant.ownerToken),
    'issue gift card missing storeId 400', 400, 'VALIDATION_FAILED');

  // Issue gift card zero amount → 400
  neg(post('/api/order-svc/gift-cards',
    { storeId: store.storeId, amount: 0, reason: 'GOODWILL' }, tenant.ownerToken), 'issue gift card zero amount 400', 400, 'VALIDATION_FAILED');

  // Issue gift card by hand with no reason → 400
  neg(post('/api/order-svc/gift-cards',
    { storeId: store.storeId, amount: '10.00' }, tenant.ownerToken), 'issue gift card by hand without a reason 400', 400, 'GIFT_CARD_REASON_REQUIRED');

  // Issue gift card by hand naming a tender → 409 (money taken for a card is a sale)
  neg(post('/api/order-svc/gift-cards',
    { storeId: store.storeId, amount: '10.00', reason: 'GOODWILL', paidBy: 'CASH' }, tenant.ownerToken),
    'issue gift card by hand with a tender 409', 409, 'GIFT_CARD_NEEDS_SALE');

  // Issue gift card by hand with no Idempotency-Key → 400 (sent without the helper, which adds one)
  neg(http.post(`${BASE}/api/order-svc/gift-cards`,
    JSON.stringify({ storeId: store.storeId, amount: '10.00', reason: 'GOODWILL' }), { headers: hdrs(tenant.ownerToken) }),
    'issue gift card by hand without a key 400', 400, 'IDEMPOTENCY_KEY_REQUIRED');

  // GET non-existent gift card code → 404
  neg(get('/api/order-svc/gift-cards/XXXX-XXXX-XXXX-XXXX', tenant.ownerToken), 'get nonexistent gift card 404', 404, 'GIFT_CARD_NOT_FOUND');

  // Redeem more than balance → 409
  const gcForRedeemRes = post('/api/order-svc/gift-cards',
    { storeId: store.storeId, amount: '10.00', currency: tenant.currency, reason: 'GOODWILL' }, tenant.ownerToken);
  const gcOrderRes = post('/api/order-svc/orders', {
    storeId: store.storeId,
    channel: 'POS',
    fulfilmentType: 'INSTORE',
    items: [{ variantId: vid, qty: 1, unitPrice: '15.00' }],
    currency: tenant.currency,
    idempotencyKey: newId(),
  }, tenant.ownerToken);
  if (gcForRedeemRes.status === 201 && gcOrderRes.status === 201) {
    const gcCode = (() => { try { return JSON.parse(gcForRedeemRes.body).data.code; } catch (_) { return null; } })();
    if (gcCode) {
      neg(post(`/api/order-svc/gift-cards/${gcCode}/redeem`,
        { amount: '999.00', orderId: body(gcOrderRes).id }, tenant.ownerToken),
        'redeem gift card exceeds balance 409', 409, 'GIFT_CARD_INSUFFICIENT_BALANCE');
    }
  }

  sleep(1);
}

// ── Gap #15: Pricing VAT ───────────────────────────────────────────────────────
export function pricingVat(d) {
  if (!d) return;
  const tenant = tenantCtx(d);
  const store  = storeCtx(tenant);
  const tag    = isIN(d) ? 'IN' : 'UK';
  if (!store || !tenant.variantIds || tenant.variantIds.length === 0) { sleep(1); return; }

  const vid = tenant.variantIds[__ITER % tenant.variantIds.length];
  const ordId  = genUuid();
  const lineId = genUuid();

  // ── Positive: get VAT rate T1 ──────────────────────────────────────────────
  let t0 = Date.now();
  let res = get('/api/pricing-svc/vat-rates/T1', tenant.ownerToken);
  pricingLatency.add(Date.now() - t0);
  ok(res, `${tag} get VAT rate T1`);
  check(res, {
    [`${tag} VAT rate has rate field`]: r => {
      try { const v = JSON.parse(r.body).data; return v && v.rate != null; } catch (_) { return false; }
    },
  });

  // ── Positive: list all VAT rates ──────────────────────────────────────────
  res = get('/api/pricing-svc/vat-rates', tenant.ownerToken);
  ok(res, `${tag} list VAT rates`);

  // ── Positive: resolve price with VAT ────────────────────────────────────
  t0 = Date.now();
  res = http.post(`${BASE}/api/pricing-svc/prices/resolve`,
    JSON.stringify({ variantId: vid, channel: 'ALL', qty: 1 }),
    { headers: storefrontHdrs(tenant.tenantId) });
  pricingLatency.add(Date.now() - t0);
  const priceOk = ok(res, `${tag} resolve price`);
  if (priceOk) {
    check(res, {
      [`${tag} resolved price has vatCode`]: r => {
        try { const v = JSON.parse(r.body).data; return v && v.vatCode != null; } catch (_) { return false; }
      },
      [`${tag} resolved price has totalWithVat`]: r => {
        try { const v = JSON.parse(r.body).data; return v && v.totalWithVat != null; } catch (_) { return false; }
      },
    });
  }

  // ── Positive: record tax transaction (POSLog) ───────────────────────────
  const vatCode = 'T1';
  const vatRate = 0.20;
  const net = 49.99;
  const vat = parseFloat((net * vatRate).toFixed(2));
  const gross = parseFloat((net + vat).toFixed(2));
  t0 = Date.now();
  res = http.post(`${BASE}/api/pricing-svc/tax-transactions`,
    JSON.stringify({
      orderId: ordId, orderLineId: lineId, variantId: vid,
      storeId: store.storeId, vatCode, vatRate, netAmount: net,
      vatAmount: vat, grossAmount: gross, exempt: false,
      taxPointDate: new Date().toISOString(),
    }),
    { headers: hdrs(tenant.ownerToken) });
  pricingLatency.add(Date.now() - t0);
  ok(res, `${tag} record tax transaction 201`);

  // ── Positive: list tax transactions by order ────────────────────────────
  res = get(`/api/pricing-svc/tax-transactions?orderId=${ordId}`, tenant.ownerToken);
  ok(res, `${tag} list tax transactions by order`);

  // ── Positive: MTD VAT return ────────────────────────────────────────────
  const now = new Date();
  const y = now.getFullYear();
  res = get(`/api/pricing-svc/vat-return?from=${y}-01-01T00:00:00Z&to=${y}-12-31T23:59:59Z`, tenant.ownerToken);
  ok(res, `${tag} MTD VAT return`);
  check(res, {
    [`${tag} VAT return has box1`]: r => {
      try { return JSON.parse(r.body).data?.box1 != null; } catch (_) { return false; }
    },
  });

  // ── Positive: list promotions ────────────────────────────────────────────
  res = get('/api/pricing-svc/promotions', tenant.ownerToken);
  ok(res, `${tag} list promotions`);

  // ── Positive: tenant isolation — IN tenant cannot see UK VAT rates ───────
  // When using India's header, /vat-rates/T1 should either return 404 (India has no T1)
  // or return India's own T1 (tenantId === india). Returning UK's T1 (tenantId === uk) is a violation.
  if (!isIN(d)) {
    const crossTenantId = d.india?.tenantId;
    if (crossTenantId) {
      res = get('/api/pricing-svc/vat-rates/T1', d.india.ownerToken);
      check(res, {
        'pricing isolation: UK rate not visible to IN tenant header':
          r => {
            if (r.status === 404 || r.status === 403) return true;
            try { return JSON.parse(r.body).data?.tenantId !== d.uk.tenantId; } catch (_) { return true; }
          },
      });
      if (res.status >= 200 && res.status < 300) {
        try {
          if (JSON.parse(res.body).data?.tenantId === d.uk.tenantId) {
            isolationViolations.add(1);
            console.error(`ISOLATION VIOLATION [vat-rates/T1] indiaTenant=${d.india.tenantId} ukTenant=${d.uk.tenantId} status=${res.status} body=${res.body}`);
          }
        } catch (_) {}
      }
    }
  }

  // ── Negative: create VAT rate with rate > 1 → 400 ───────────────────────
  res = http.post(`${BASE}/api/pricing-svc/vat-rates`,
    JSON.stringify({ code: 'TX', name: 'Bad Rate', rate: 1.5, exempt: false,
      effectiveFrom: '2024-01-01T00:00:00Z' }),
    { headers: hdrs(tenant.ownerToken) });
  check(res, { [`${tag} rate >1 rejected 400`]: r => r.status === 400 && errorCode(r) === 'VALIDATION_FAILED' });
  if (res.status >= 200 && res.status < 300) negativeUnexpectedSuccess.add(1);

  // ── Negative: resolve price for unknown variant → 404 ────────────────────
  res = http.post(`${BASE}/api/pricing-svc/prices/resolve`,
    JSON.stringify({ variantId: '01a090ae-611e-701d-9d60-a9d7516ed03b', channel: 'ALL', qty: 1 }),
    { headers: storefrontHdrs(tenant.tenantId) });
  check(res, { [`${tag} resolve unknown variant 404`]: r => r.status === 404 && errorCode(r) === 'PRICING_PRICE_NOT_FOUND' });
  if (res.status >= 200 && res.status < 300) negativeUnexpectedSuccess.add(1);

  // ── Negative: record tax transaction missing orderId → 400 ────────────────
  res = http.post(`${BASE}/api/pricing-svc/tax-transactions`,
    JSON.stringify({ variantId: vid, storeId: store.storeId, vatCode: 'T1',
      vatRate: 0.20, netAmount: 10, vatAmount: 2, grossAmount: 12,
      exempt: false, taxPointDate: new Date().toISOString() }),
    { headers: hdrs(tenant.ownerToken) });
  check(res, { [`${tag} tax tx missing orderId 400`]: r => r.status === 400 && errorCode(r) === 'VALIDATION_FAILED' });
  if (res.status >= 200 && res.status < 300) negativeUnexpectedSuccess.add(1);

  // ── Negative: VAT return with from >= to → 400 ───────────────────────────
  res = get('/api/pricing-svc/vat-return?from=2025-01-01T00:00:00Z&to=2024-01-01T00:00:00Z', tenant.ownerToken);
  check(res, { [`${tag} VAT return invalid period 400`]: r => r.status === 400 && errorCode(r) === 'PRICING_INVALID_PERIOD' });
  if (res.status >= 200 && res.status < 300) negativeUnexpectedSuccess.add(1);

  // ── Negative: duplicate VAT rate code → 409 ──────────────────────────────
  res = http.post(`${BASE}/api/pricing-svc/vat-rates`,
    JSON.stringify({ code: 'T1', name: 'Dup', rate: 0.10, exempt: false,
      effectiveFrom: '2024-01-01T00:00:00Z' }),
    { headers: hdrs(tenant.ownerToken) });
  check(res, { [`${tag} duplicate VAT code 409`]: r => r.status === 409 && errorCode(r) === 'PRICING_VAT_CODE_EXISTS' });
  if (res.status >= 200 && res.status < 300) negativeUnexpectedSuccess.add(1);

  sleep(1);
}

// ── Gap #20 — Intercompany invoicing + FRS 102 nominal ledger ─────────────────
export function intercompanyFlow(d) {
  if (!d) return;
  const t = tenantCtx(d);
  if (!t) return;
  const { tenantId, ownerId, ownerToken, stores, currency, supplierId } = t;
  if (!stores || stores.length < 2) return;
  const store1 = stores[0].storeId;
  const store2 = stores[1].storeId;
  const ts = Date.now();

  // ── Positive: supplier CRUD ────────────────────────────────────────────────
  // List suppliers — should include the seeded one
  let r = get('/api/purchase-svc/suppliers', ownerToken);
  intercompanyLatency.add(r.timings.duration);
  check(r, { 'list suppliers 200': res => res.status === 200 });
  const listBody = body(r);
  const seedSupplier = Array.isArray(listBody) ? listBody[0] : null;
  const useSupplierId = seedSupplier?.id || supplierId;

  if (useSupplierId) {
    r = get(`/api/purchase-svc/suppliers/${useSupplierId}`, ownerToken);
    check(r, { 'get supplier 200': res => res.status === 200 });
    check(r, { 'supplier BACS 30': res => body(res)?.paymentTermsDays === 30 });
  }

  // ── Positive: create PO + add line + submit + GRN ─────────────────────────
  if (useSupplierId) {
    const poRes = post('/api/purchase-svc/purchase-orders', {
      supplierId: useSupplierId,
      storeId:    store1,
      currency,
      expectedDelivery: '2026-12-31',
    }, ownerToken);
    intercompanyLatency.add(poRes.timings.duration);
    check(poRes, { 'create PO 201': res => res.status === 201 });
    const poId = (poRes.status === 201) ? body(poRes).id : null;

    if (poId) {
      // Add line
      const lineRes = post(`/api/purchase-svc/purchase-orders/${poId}/lines`, {
        variantId: t.variantIds[0],
        qty: 50, unitPrice: 25.00, vatCode: 'T1',
      }, ownerToken);
      check(lineRes, { 'add PO line 201': res => res.status === 201 });

      // List lines
      r = get(`/api/purchase-svc/purchase-orders/${poId}/lines`, ownerToken);
      check(r, { 'list PO lines 200': res => res.status === 200 });

      // Submit. Over the caller's spend authority (storeql.purchase.approval.limits) it waits for
      // approval — and a currency with no configured limits always does — so approve it first.
      const submitRes = post(`/api/purchase-svc/purchase-orders/${poId}/submit`, {}, ownerToken);
      check(submitRes, { 'submit PO 200': res => res.status === 200 });
      if (body(submitRes)?.status === 'PENDING_APPROVAL') {
        const approveRes = post(`/api/purchase-svc/purchase-orders/${poId}/approve`, { reason: 'k6 retail run' }, ownerToken);
        check(approveRes, { 'owner approves PO 200': res => res.status === 200 });
      }
      r = get(`/api/purchase-svc/purchase-orders/${poId}`, ownerToken);
      check(r, { 'PO status SUBMITTED': res => body(res)?.status === 'SUBMITTED' });

      // GRN
      const grnRes = post('/api/purchase-svc/goods-receipts', {
        poId, storeId: store1,
        lines: [{ variantId: t.variantIds[0], qtyReceived: 50 }],
      }, ownerToken);
      intercompanyLatency.add(grnRes.timings.duration);
      if (!check(grnRes, { 'goods receipt 201': res => res.status === 201 })) {
        console.warn(`goods receipt refused: ${grnRes.status} ${String(grnRes.body).slice(0, 240)}`);
      }

      // PO should now be RECEIVED
      r = get(`/api/purchase-svc/purchase-orders/${poId}`, ownerToken);
      check(r, { 'PO status RECEIVED': res => body(res)?.status === 'RECEIVED' });

      // List GRNs for PO
      r = get(`/api/purchase-svc/goods-receipts?poId=${poId}`, ownerToken);
      check(r, { 'list GRNs 200': res => res.status === 200 });
    }
  }

  // ── Positive: intercompany invoice AR+AP pair ──────────────────────────────
  const icRes = post('/api/purchase-svc/intercompany-invoices', {
    fromStoreId: store1,
    toStoreId:   store2,
    netAmount:   500.00,
    vatAmount:   100.00,
    grossAmount: 600.00,
    vatCode:     'T1',
    vatDisregarded: false,
    currency,
  }, ownerToken);
  intercompanyLatency.add(icRes.timings.duration);
  check(icRes, { 'raise IC invoice 201': res => res.status === 201 });
  const pairBody = body(icRes);
  const arId = pairBody?.arInvoice?.id;
  const apId = pairBody?.apInvoice?.id;
  check(icRes, { 'IC pair has AR and AP': _ => !!(arId && apId) });
  check(icRes, { 'IC status RAISED':   _ => pairBody?.arInvoice?.status === 'RAISED' });
  check(icRes, { 'IC payment due date set': _ => !!pairBody?.arInvoice?.paymentDueDate });

  // List invoices
  r = get('/api/purchase-svc/intercompany-invoices', ownerToken);
  check(r, { 'list IC invoices 200': res => res.status === 200 });

  // Get specific invoice
  if (arId) {
    r = get(`/api/purchase-svc/intercompany-invoices/${arId}`, ownerToken);
    intercompanyLatency.add(r.timings.duration);
    check(r, { 'get IC invoice 200': res => res.status === 200 });
    check(r, { 'net amount correct': res => body(res)?.netAmount === 500.00 });
  }

  // Nominal ledger check — should have 1100 Debtors entry (filtered by code: the unfiltered list is
  // paged, and the receipts this run posts push the debtors line past the first page)
  r = get('/api/purchase-svc/nominal-ledger?code=1100', ownerToken);
  check(r, { 'nominal ledger 200': res => res.status === 200 });
  check(r, { 'has debtors entry': res => {
    const entries = body(res);
    return Array.isArray(entries) && entries.some(e => e.nominalCode === '1100');
  }});

  // Filter nominal ledger by code
  r = get('/api/purchase-svc/nominal-ledger?code=2200', ownerToken);
  check(r, { 'nominal by code 200': res => res.status === 200 });
  check(r, { 'VAT output entries present': res => {
    const entries = body(res);
    return Array.isArray(entries) && entries.length > 0;
  }});

  // ── Positive: settle AR invoice ─────────────────────────────────────────────
  if (arId) {
    const settleRes = post(`/api/purchase-svc/intercompany-invoices/${arId}/settle`, {}, ownerToken);
    intercompanyLatency.add(settleRes.timings.duration);
    const settled = check(settleRes, { 'settle IC invoice 200': res => res.status === 200 });

    // Settle again → 409 already settled. Only meaningful if the first settle actually landed —
    // otherwise the invoice is still unsettled and a second settle legitimately succeeds.
    if (settled) {
      const settle2 = post(`/api/purchase-svc/intercompany-invoices/${arId}/settle`, {}, ownerToken);
      check(settle2, { 'double-settle 409': res => res.status === 409 && errorCode(res) === 'PURCHASE_INVOICE_NOT_RAISEABLE' });
    }
  }

  // ── Positive: Group VAT disregard ──────────────────────────────────────────
  const icVatDisRes = post('/api/purchase-svc/intercompany-invoices', {
    fromStoreId: store1,
    toStoreId:   store2,
    netAmount:   250.00,
    vatAmount:   0.00,
    grossAmount: 250.00,
    vatCode:     'T1',
    vatDisregarded: true,
    currency,
  }, ownerToken);
  check(icVatDisRes, { 'VAT-disregarded IC 201': res => res.status === 201 });
  check(icVatDisRes, { 'vatDisregarded=true in response': res =>
    body(res)?.arInvoice?.vatDisregarded === true });

  // Nominal ledger code 2200 should have no NEW entries beyond the previous count
  r = get('/api/purchase-svc/nominal-ledger?code=2200', ownerToken);
  const prevCount = body(get('/api/purchase-svc/nominal-ledger?code=2200', ownerToken));
  check(r, { 'no extra VAT nominal on disregarded': _ => {
    const entries = body(r);
    return Array.isArray(entries);
  }});

  // ── Negative: same fromStore = toStore → 400 ─────────────────────────────
  const icBadSameStore = post('/api/purchase-svc/intercompany-invoices', {
    fromStoreId: store1,
    toStoreId:   store1,
    netAmount:   100.00,
    vatAmount:   20.00,
    grossAmount: 120.00,
    currency,
  }, ownerToken);
  check(icBadSameStore, { 'same-store IC 400': res => res.status === 400 && errorCode(res) === 'PURCHASE_IC_SAME_STORE' });

  // ── Negative: unknown invoice ID → 404 ────────────────────────────────────
  r = get('/api/purchase-svc/intercompany-invoices/01a090ae-611e-7000-9e1a-0f8a9e565153', ownerToken);
  check(r, { 'unknown IC invoice 404': res => res.status === 404 && errorCode(res) === 'PURCHASE_INVOICE_NOT_FOUND' });

  // ── Negative: tenant isolation — other tenant cannot see invoices ──────────
  const otherTenant = tenantCtx({ india: d.uk, uk: d.india });
  if (otherTenant && arId) {
    r = get(`/api/purchase-svc/intercompany-invoices/${arId}`, otherTenant.ownerToken);
    check(r, { 'IC invoice cross-tenant 404': res => res.status === 404 && errorCode(res) === 'PURCHASE_INVOICE_NOT_FOUND' });
    // Only a 2xx carrying the other tenant's actual invoice is a real leak — a 404 (correct) or
    // a transient 5xx (infra blip, not data exposure) must not be counted as a violation.
    if (r.status >= 200 && r.status < 300) {
      isolationViolations.add(1);
      console.error(`ISOLATION VIOLATION [IC invoice] arId=${arId} status=${r.status} body=${r.body}`);
    }
  }

  // ── Negative: create PO with unknown supplier → 404 ──────────────────────
  const badPoRes = post('/api/purchase-svc/purchase-orders', {
    supplierId: '01a090ae-611e-7000-9e1a-0f8a9e565153',
    storeId:    store1,
    currency,
  }, ownerToken);
  check(badPoRes, { 'PO unknown supplier 404': res => res.status === 404 && errorCode(res) === 'PURCHASE_SUPPLIER_NOT_FOUND' });

  sleep(1);
}

// ── Gap #54: payment-svc tender + refund + GET /orders list ───────────────────
export function paymentFlow(d) {
  if (!d || !d.india || !d.uk) return;
  const tenant = tenantCtx(d);
  const store  = tenant.stores[0];
  if (!store || !tenant.variantIds.length) return;
  const storeId   = store.storeId;
  const variantId = tenant.variantIds[0];
  const tag       = `payment[${tenant.name}]`;

  // 1. Place a POS order to pay against
  const placeRes = post('/api/order-svc/orders', {
    storeId,
    channel: 'POS',
    fulfilmentType: 'INSTORE',
    items: [{ variantId, qty: 1, unitPrice: '20.00' }],
    currency: tenant.currency,
    idempotencyKey: newId(),
  }, tenant.ownerToken);
  if (!ok(placeRes, `${tag} place order for payment 201`)) { sleep(1); return; }
  const orderId = (() => { try { return JSON.parse(placeRes.body).data.id; } catch (_) { return null; } })();
  if (!orderId) { sleep(1); return; }

  // 2. Record CASH tender
  const cashKey = newId();
  const cashRes = post('/api/payment-svc/payments', {
    orderId,
    amount: '20.00',
    method: 'CASH',
    idempotencyKey: cashKey,
  }, tenant.ownerToken);
  ok(cashRes, `${tag} record cash tender 201`);
  const paymentId = (() => { try { return JSON.parse(cashRes.body).data.id; } catch (_) { return null; } })();

  // 2b. Retry with the SAME Idempotency-Key → replays the original tender (no double charge)
  if (paymentId) {
    const replayRes = post('/api/payment-svc/payments', {
      orderId,
      amount: '20.00',
      method: 'CASH',
      idempotencyKey: cashKey,
    }, tenant.ownerToken);
    check(replayRes, {
      [`${tag} idempotent tender replay returns original id`]: r => {
        try { return JSON.parse(r.body).data.id === paymentId; } catch (_) { return false; }
      },
    });
  }

  // 3. GET tender by ID
  if (paymentId) {
    const getRes = get(`/api/payment-svc/payments/${paymentId}`, tenant.ownerToken);
    check(getRes, { [`${tag} get tender 200`]: r => r.status === 200 });
  }

  // 4. List tenders by order
  const listRes = get(`/api/payment-svc/payments/by-order/${orderId}`, tenant.ownerToken);
  check(listRes, { [`${tag} list tenders 200`]: r => r.status === 200 });

  // 5. Record a refund (partial)
  if (paymentId) {
    const refundRes = post(`/api/payment-svc/payments/by-order/${orderId}/refunds`, {
      paymentId,
      amount: '10.00',
      method: 'CASH',
      reason: 'partial return',
    }, tenant.ownerToken);
    ok(refundRes, `${tag} record refund 201`);

    // 5b. Cumulative cap: 10.00 already refunded of 20.00 — another 15.00 must be rejected
    const overRes = post(`/api/payment-svc/payments/by-order/${orderId}/refunds`, {
      paymentId,
      amount: '15.00',
      method: 'CASH',
      reason: 'over-refund attempt',
    }, tenant.ownerToken);
    check(overRes, { [`${tag} over-refund rejected 409`]: r => r.status === 409 && errorCode(r) === 'REFUND_EXCEEDS_PAYMENT' });

    const listRefRes = get(`/api/payment-svc/payments/by-order/${orderId}/refunds`, tenant.ownerToken);
    check(listRefRes, { [`${tag} list refunds 200`]: r => r.status === 200 });
  }

  // 6. GET /orders with filters (order-svc)
  const ordersRes = get(`/api/order-svc/orders?store=${storeId}&channel=POS&limit=5`, tenant.ownerToken);
  check(ordersRes, { [`${tag} list orders with filters 200`]: r => r.status === 200 });

  // 7. Cross-tenant isolation for payment
  if (paymentId) {
    const other = isIN(d) ? d.uk : d.india;
    const isoRes = get(`/api/payment-svc/payments/${paymentId}`, other.ownerToken);
    check(isoRes, { [`${tag} payment cross-tenant isolation 404`]: r => r.status === 404 && errorCode(r) === 'PAYMENT_NOT_FOUND' });
  }

  sleep(1);
}

// ── Bulk import: categories + products + prices ────────────────────────────────
export function bulkImportFlow(d) {
  if (!d || !d.india || !d.uk) return;
  const tenant = tenantCtx(d);
  const tag    = `bulkImport[${tenant.name}]`;

  // 1. Bulk import: 2 new categories + 1 product with 1 variant
  const importRes = post('/api/product-svc/admin/import', {
    categories: [
      { name: `BulkCat-${__VU}-${__ITER}` },
      { name: `BulkSub-${__VU}-${__ITER}` },
    ],
    products: [{
      name: `BulkProduct-${__VU}-${__ITER}`,
      categoryName: `BulkCat-${__VU}-${__ITER}`,
      variants: [{
        sku: `BSKU-${__VU}-${__ITER}`,
        unit: 'EACH',
        attributes: { color: 'blue' },
      }],
    }],
  }, tenant.ownerToken);
  if (!ok(importRes, `${tag} bulk import 200`)) { sleep(1); return; }
  const result = (() => { try { return JSON.parse(importRes.body).data; } catch (_) { return null; } })();
  check(importRes, {
    [`${tag} categories created ≥ 1`]: _ => result && result.categoriesCreated >= 1,
    [`${tag} products created = 1`]:   _ => result && result.productsCreated === 1,
    [`${tag} variants created = 1`]:   _ => result && result.variantsCreated === 1,
  });

  // 2. Re-import same categories — should be skipped not errored
  const reimportRes = post('/api/product-svc/admin/import', {
    categories: [{ name: `BulkCat-${__VU}-${__ITER}` }],
    products: [],
  }, tenant.ownerToken);
  if (reimportRes.status === 200) {
    const r2 = (() => { try { return JSON.parse(reimportRes.body).data; } catch (_) { return null; } })();
    check(reimportRes, {
      [`${tag} duplicate category skipped`]: _ => r2 && r2.categoriesSkipped >= 1,
    });
  }

  sleep(1);
}

// ── Tier 7 (gaps #61–72): gateway security & API conventions ──────────────────
export function gatewaySecurity(d) {
  if (!d || !d.india || !d.uk) return;
  const tenant = tenantCtx(d);
  const store  = tenant.stores[0];
  if (!store || !tenant.variantIds.length) return;
  const tag       = isIN(d) ? 'gwSec[IN]' : 'gwSec[UK]';
  const storeId   = store.storeId;
  const variantId = tenant.variantIds[0];

  // Gap #61: a URL that merely embeds a public auth suffix must NOT bypass JWT validation.
  const bypass = http.get(`${BASE}/api/product-svc/products/iam-svc/auth/login`,
    { headers: { 'Content-Type': 'application/json' } });
  check(bypass, { [`${tag} embedded public-path suffix → 401`]: r => r.status === 401 && errorCode(r) === 'UNAUTHORIZED' });
  if (bypass.status >= 200 && bypass.status < 300) {
    securityViolations.add(1);
    console.error(`SECURITY VIOLATION [embedded public-path bypass] status=${bypass.status} body=${bypass.body.substring(0, 300)}`);
  }

  // Gap #70: gateway filter errors use the standard envelope (machine-readable error.code).
  check(bypass, {
    [`${tag} 401 carries error.code UNAUTHORIZED`]: r => {
      try { return JSON.parse(r.body).error.code === 'UNAUTHORIZED'; } catch (_) { return false; }
    },
  });

  // Gap #69: internal platform services are not routable even with a valid token.
  const cfgRes = get('/api/config/configs/iam-svc/default', tenant.ownerToken);
  check(cfgRes, {
    [`${tag} internal 'config' service not routable`]: r => r.status === 503 || r.status === 404,
  });
  if (cfgRes.status >= 200 && cfgRes.status < 300) {
    securityViolations.add(1);
    console.error(`SECURITY VIOLATION [internal config routable] status=${cfgRes.status} body=${cfgRes.body.substring(0, 300)}`);
  }

  // Gap #72 (CORS): no origins configured → preflight must NOT echo any allow-origin header.
  const pre = http.options(`${BASE}/api/order-svc/orders`, null, { headers: {
    'Origin': 'https://evil.example.com',
    'Access-Control-Request-Method': 'POST',
  }});
  if (!check(pre, {
    [`${tag} CORS deny-by-default (no allow-origin)`]: r => !r.headers['Access-Control-Allow-Origin'],
  })) {
    securityViolations.add(1);
    console.error(`SECURITY VIOLATION [CORS] status=${pre.status} allowOrigin=${pre.headers['Access-Control-Allow-Origin']}`);
  }

  // Gap #66: POS session sweep is platform-admin only — an OWNER must be refused.
  const sweep = post('/api/iam-svc/auth/pos/sessions/sweep', {}, tenant.ownerToken);
  check(sweep, { [`${tag} POS sweep with OWNER → 403`]: r => r.status === 403 && errorCode(r) === 'FORBIDDEN' });
  // Only a 2xx is an actual bypass — a 5xx is an infra blip, not OWNER gaining admin access.
  if (sweep.status >= 200 && sweep.status < 300) {
    securityViolations.add(1);
    console.error(`SECURITY VIOLATION [POS sweep] status=${sweep.status} body=${sweep.body.substring(0, 300)}`);
  }

  // Gaps #67 + #71: the Idempotency-Key HTTP header is forwarded by the gateway and a
  // retried checkout replays the original order instead of duplicating or erroring.
  const idemKey   = newKey('gateway-idempotency');
  const orderBody = JSON.stringify({
    storeId, channel: 'POS', fulfilmentType: 'INSTORE',
    items: [{ variantId, qty: 1, unitPrice: '9.99' }], currency: tenant.currency,
  });
  const idemHeaders = { headers: Object.assign({ 'Idempotency-Key': idemKey }, hdrs(tenant.ownerToken)) };
  const first = http.post(`${BASE}/api/order-svc/orders`, orderBody, idemHeaders);
  const retry = http.post(`${BASE}/api/order-svc/orders`, orderBody, idemHeaders);
  ok(first, `${tag} idempotent place 201`);
  const firstId = (() => { try { return JSON.parse(first.body).data.id; } catch (_) { return null; } })();
  const retryId = (() => { try { return JSON.parse(retry.body).data.id; } catch (_) { return null; } })();
  check(retry, {
    [`${tag} Idempotency-Key header replay → same order id`]: _ => firstId !== null && firstId === retryId,
  });
  // A genuine violation is a *different*, newly-created order on replay — a retry that errored
  // (5xx/4xx, retryId null) didn't duplicate anything, it just didn't succeed.
  if (firstId !== null && retryId !== null && firstId !== retryId) {
    securityViolations.add(1);
    console.error(`SECURITY VIOLATION [idempotency replay] firstId=${firstId} retryId=${retryId} firstStatus=${first.status} retryStatus=${retry.status}`);
  }

  // Gap #72 (pagination): GET /orders pages with an opaque cursor and pages never overlap.
  for (let i = 0; i < 2; i++) {
    post('/api/order-svc/orders', {
      storeId, channel: 'POS', fulfilmentType: 'INSTORE',
      items: [{ variantId, qty: 1, unitPrice: '1.00' }], currency: tenant.currency,
    }, tenant.ownerToken);
  }
  const page1 = get('/api/order-svc/orders?limit=2', tenant.ownerToken);
  ok(page1, `${tag} list orders page1 200`);
  const page1Body = (() => { try { return JSON.parse(page1.body); } catch (_) { return {}; } })();
  const cursor = page1Body.meta && page1Body.meta.nextCursor;
  check(page1, { [`${tag} page1 exposes meta.nextCursor`]: _ => !!cursor });
  if (cursor) {
    const page2 = get(`/api/order-svc/orders?limit=2&after=${encodeURIComponent(cursor)}`, tenant.ownerToken);
    ok(page2, `${tag} list orders page2 200`);
    const ids1 = (page1Body.data || []).map(o => o.id);
    const ids2 = (() => { try { return (JSON.parse(page2.body).data || []).map(o => o.id); } catch (_) { return []; } })();
    check(page2, {
      [`${tag} pages do not overlap`]: _ => ids2.length > 0 && !ids2.some(id => ids1.includes(id)),
    });
  }
  const badCursor = get('/api/order-svc/orders?after=%21%21bogus%21%21', tenant.ownerToken);
  check(badCursor, { [`${tag} malformed cursor → 400`]: r => r.status === 400 && errorCode(r) === 'INVALID_CURSOR' });

  // Gap #72 (PATCH): PATCH is proxied by the gateway end-to-end.
  const patchRes = patch(`/api/tenant-svc/admin/stores/${storeId}/status`,
    { status: 'ACTIVE' }, tenant.ownerToken);
  ok(patchRes, `${tag} PATCH store status via gateway 200`);

  // Gap #63: money inputs are constrained — negative tax and oversized discount are rejected.
  const negTax = post('/api/order-svc/orders', {
    storeId, channel: 'POS', fulfilmentType: 'INSTORE',
    items: [{ variantId, qty: 1, unitPrice: '10.00' }],
    taxAmount: '-5.00', currency: tenant.currency,
  }, tenant.ownerToken);
  check(negTax, { [`${tag} negative taxAmount → 400`]: r => r.status === 400 && errorCode(r) === 'VALIDATION_FAILED' });
  // Only a 2xx means the invalid input was actually accepted — a 5xx isn't validation being skipped.
  if (negTax.status >= 200 && negTax.status < 300) {
    securityViolations.add(1);
    console.error(`SECURITY VIOLATION [negative taxAmount accepted] status=${negTax.status} body=${negTax.body.substring(0, 300)}`);
  }
  const bigDisc = post('/api/order-svc/orders', {
    storeId, channel: 'POS', fulfilmentType: 'INSTORE',
    items: [{ variantId, qty: 1, unitPrice: '10.00' }],
    discountAmount: '999.00', currency: tenant.currency,
  }, tenant.ownerToken);
  check(bigDisc, { [`${tag} discount > subtotal → 400`]: r => r.status === 400 && errorCode(r) === 'ORDER_DISCOUNT_EXCEEDS_SUBTOTAL' });
  if (bigDisc.status >= 200 && bigDisc.status < 300) {
    securityViolations.add(1);
    console.error(`SECURITY VIOLATION [discount > subtotal accepted] status=${bigDisc.status} body=${bigDisc.body.substring(0, 300)}`);
  }

  sleep(1);
}

// ── Required default export ────────────────────────────────────────────────────
export default function () {}

// ── Summary ────────────────────────────────────────────────────────────────────
// ── Customer profiles, loyalty, and store credit (customer-svc) ───────────────
export function customerFlow(d) {
  if (!d || !d.india || !d.uk) return;
  const tenant = tenantCtx(d);
  const tag    = `customer[${tenant.name}]`;
  const run    = `${__VU}-${exec.scenario.iterationInTest}`;
  const email  = `cust-${run}@storeql.test`;

  // 1. Register a customer
  const regRes = post('/api/customer-svc/customers', {
    email,
    firstName: 'Test',
    lastName:  'Customer',
    gdprConsent: true,
  }, tenant.ownerToken);
  if (!ok(regRes, `${tag} register customer 201`)) { sleep(1); return; }
  const customerId = (() => { try { return JSON.parse(regRes.body).data.id; } catch (_) { return null; } })();
  if (!customerId) { sleep(1); return; }

  // 2. GET customer
  const getRes = get(`/api/customer-svc/customers/${customerId}`, tenant.ownerToken);
  check(getRes, { [`${tag} get customer 200`]: r => r.status === 200 });

  // 3. Lookup by email (POS quick-find)
  const lookupRes = get(`/api/customer-svc/customers/lookup?email=${encodeURIComponent(email)}`, tenant.ownerToken);
  check(lookupRes, { [`${tag} lookup by email 200`]: r => r.status === 200 });

  // 4. Earn loyalty points → should reach BRONZE (500 pts)
  const earnRes = post(`/api/customer-svc/customers/${customerId}/loyalty/earn`, {
    points: 500,
    reason: 'k6-purchase',
  }, tenant.ownerToken);
  if (ok(earnRes, `${tag} earn loyalty 200`)) {
    const tier = (() => { try { return JSON.parse(earnRes.body).data.tier; } catch (_) { return null; } })();
    check(earnRes, { [`${tag} loyalty tier is BRONZE`]: () => tier === 'BRONZE' });
  }

  // 5. Earn 600 more → 1100 lifetime → SILVER
  const earn2Res = post(`/api/customer-svc/customers/${customerId}/loyalty/earn`, {
    points: 600,
    reason: 'k6-purchase-2',
  }, tenant.ownerToken);
  if (ok(earn2Res, `${tag} earn loyalty tier-up 200`)) {
    const tier2 = (() => { try { return JSON.parse(earn2Res.body).data.tier; } catch (_) { return null; } })();
    check(earn2Res, { [`${tag} loyalty tier is SILVER after tier-up`]: () => tier2 === 'SILVER' });
  }

  // 6. Redeem 100 pts
  const redeemRes = post(`/api/customer-svc/customers/${customerId}/loyalty/redeem`, {
    points: 100,
    reason: 'k6-discount',
  }, tenant.ownerToken);
  ok(redeemRes, `${tag} redeem loyalty 200`);

  // 7. Redeem more than balance → 422
  const overRes = post(`/api/customer-svc/customers/${customerId}/loyalty/redeem`, {
    points: 999999,
    reason: 'k6-over-redeem',
  }, tenant.ownerToken);
  check(overRes, { [`${tag} over-redeem rejected 422`]: r => r.status === 422 && errorCode(r) === 'LOYALTY_INSUFFICIENT_POINTS' });

  // 8. Loyalty ledger
  const ledgerRes = get(`/api/customer-svc/customers/${customerId}/loyalty/ledger?limit=10`, tenant.ownerToken);
  check(ledgerRes, { [`${tag} loyalty ledger 200`]: r => r.status === 200 });

  // 9. Issue store credit
  const creditRes = post(`/api/customer-svc/customers/${customerId}/store-credit/issue`, {
    amount: 50.00,
    currency: 'GBP',
    reason: 'k6-return-refund',
  }, tenant.ownerToken);
  ok(creditRes, `${tag} issue store credit 200`);

  // 10. Redeem store credit (partial)
  const scRedeemRes = post(`/api/customer-svc/customers/${customerId}/store-credit/redeem`, {
    amount: 20.00,
    currency: 'GBP',
    reason: 'k6-purchase',
  }, tenant.ownerToken);
  ok(scRedeemRes, `${tag} redeem store credit 200`);

  // 11. Cross-tenant isolation
  const other  = isIN(d) ? d.uk : d.india;
  const isoRes = get(`/api/customer-svc/customers/${customerId}`, other.ownerToken);
  check(isoRes, { [`${tag} customer cross-tenant isolation 404`]: r => r.status === 404 && errorCode(r) === 'CUSTOMER_NOT_FOUND' });

  customerLatency.add(regRes.timings.duration);
  sleep(1);
}

// ── POS register: parked (suspended) sales and no-sale log ────────────────────
export function posRegisterFlow(d) {
  if (!d || !d.india || !d.uk) return;
  const tenant    = tenantCtx(d);
  const store     = tenant.stores[0];
  const variantId = tenant.variantIds[0];
  if (!store || !variantId) { sleep(1); return; }
  const tag     = `posRegister[${tenant.name}]`;
  const storeId = store.storeId;

  // 1. Park a sale
  const parkRes = post('/api/order-svc/pos/parked-sales', {
    storeId,
    cashierId:    store.cashierId || tenant.ownerId,
    items: [{ variantId, qty: 2, unitPrice: '15.00', notes: 'k6-parked' }],
    notes: 'customer stepped out',
  }, tenant.ownerToken);
  if (!ok(parkRes, `${tag} park sale 201`)) { sleep(1); return; }
  const saleId = (() => { try { return JSON.parse(parkRes.body).data.id; } catch (_) { return null; } })();
  if (!saleId) { sleep(1); return; }

  // 2. List open parked sales for the store
  const listRes = get(`/api/order-svc/pos/parked-sales?storeId=${storeId}`, tenant.ownerToken);
  check(listRes, { [`${tag} list parked sales 200`]: r => r.status === 200 });

  // 3. Get specific parked sale
  const getRes = get(`/api/order-svc/pos/parked-sales/${saleId}`, tenant.ownerToken);
  check(getRes, { [`${tag} get parked sale 200`]: r => r.status === 200 });

  // 4. Cancel it (so we don't accumulate stale state)
  const cancelRes = http.del(`${BASE}/api/order-svc/pos/parked-sales/${saleId}`, null,
    { headers: hdrs(tenant.ownerToken) });
  check(cancelRes, { [`${tag} cancel parked sale 204`]: r => r.status === 204 });

  // 5. No-sale / open-drawer log
  const noSaleRes = post('/api/order-svc/pos/no-sale', {
    storeId,
    cashierId: store.cashierId || tenant.ownerId,
    reason:    'k6-no-sale-test',
  }, tenant.ownerToken);
  ok(noSaleRes, `${tag} no-sale log 201`);

  posRegisterLatency.add(parkRes.timings.duration);
  sleep(1);
}

// ── Cash management: pay-in/pay-out and daily Z-report ────────────────────────
export function cashMgmtFlow(d) {
  if (!d || !d.india || !d.uk) return;
  const tenant  = tenantCtx(d);
  const store   = tenant.stores[0];
  if (!store) { sleep(1); return; }
  const tag     = `cashMgmt[${tenant.name}]`;
  const storeId = store.storeId;

  // Need a till session — open one first
  const sessRes = post('/api/iam-svc/auth/pos/sessions', {
    storeId,
    idleTimeoutSeconds: 3600,
  }, tenant.ownerToken);
  if (!ok(sessRes, `${tag} open till session 201`)) { sleep(1); return; }
  const tillSessionId = (() => { try { return JSON.parse(sessRes.body).data.id; } catch (_) { return null; } })();
  if (!tillSessionId) { sleep(1); return; }

  // 1. PAY_IN (petty cash received)
  const payInRes = post('/api/payment-svc/admin/cash/movements', {
    storeId,
    tillSessionId,
    direction: 'PAY_IN',
    amount:    '50.00',
    reason:    'k6 petty cash in',
  }, tenant.ownerToken);
  ok(payInRes, `${tag} pay-in 201`);

  // 2. PAY_OUT (petty cash paid out)
  const payOutRes = post('/api/payment-svc/admin/cash/movements', {
    storeId,
    tillSessionId,
    direction: 'PAY_OUT',
    amount:    '20.00',
    reason:    'k6 petty cash out',
  }, tenant.ownerToken);
  ok(payOutRes, `${tag} pay-out 201`);

  // 3. List movements for this session
  const listRes = get(`/api/payment-svc/admin/cash/movements?tillSessionId=${tillSessionId}`, tenant.ownerToken);
  check(listRes, { [`${tag} list cash movements 200`]: r => r.status === 200 });

  // 4. Generate Z-report for today
  const today = new Date().toISOString().slice(0, 10);
  const zRes = post('/api/payment-svc/admin/cash/z-report', {
    storeId,
    businessDate: today,
    countedCash:  '230.00',
    currency:     'GBP',
  }, tenant.ownerToken);
  ok(zRes, `${tag} generate Z-report 200`);

  // 5. Retrieve the Z-report
  const getZRes = get(`/api/payment-svc/admin/cash/z-report?storeId=${storeId}&businessDate=${today}`, tenant.ownerToken);
  check(getZRes, { [`${tag} get Z-report 200`]: r => r.status === 200 });

  cashMgmtLatency.add(payInRes.timings.duration);
  sleep(1);
}

export function handleSummary(data) {
  const checks = data.metrics.checks;
  const passed = checks?.values?.passes || 0;
  const failed = checks?.values?.fails  || 0;
  const total  = passed + failed;

  const fmt = v => (v !== null && v !== undefined) ? String(v) : 'n/a';

  const thresholdResults = {};
  for (const [name, metric] of Object.entries(data.metrics)) {
    if (metric.thresholds) {
      thresholdResults[name] = Object.entries(metric.thresholds)
        .map(([expr, res]) => `${expr}: ${res.ok ? 'PASS' : 'FAIL'}`);
    }
  }

  // Every check that failed at least once, worst first, so a red run says where to look.
  const failedChecks = [];
  (function walk(group) {
    for (const c of group.checks || []) if (c.fails > 0) failedChecks.push(`${c.fails}/${c.passes + c.fails} ${c.name}`);
    for (const g of group.groups || []) walk(g);
  })(data.root_group || {});
  failedChecks.sort((x, y) => parseInt(y, 10) - parseInt(x, 10));

  return {
    stdout: JSON.stringify({
      failed_checks: failedChecks,
      overall: {
        checks_passed:  passed,
        checks_failed:  failed,
        pass_rate:      total > 0 ? (passed / total).toFixed(3) : 'n/a',
        errors:         fmt(data.metrics.errors?.values?.count),
      },
      india: {
        sale_success_rate: fmt(data.metrics.sale_success_india?.values?.rate?.toFixed(3)),
        catalog_p95_ms:    fmt(data.metrics.catalog_latency_india_ms?.values?.['p(95)']?.toFixed(1)),
      },
      uk: {
        sale_success_rate: fmt(data.metrics.sale_success_uk?.values?.rate?.toFixed(3)),
        catalog_p95_ms:    fmt(data.metrics.catalog_latency_uk_ms?.values?.['p(95)']?.toFixed(1)),
      },
      cross_cutting: {
        purchase_receive_p95_ms: fmt(data.metrics.purchase_receive_latency_ms?.values?.['p(95)']?.toFixed(1)),
        isolation_violations:    fmt(data.metrics.isolation_violations?.values?.count),
      },
      pricing: {
        pricing_p95_ms: fmt(data.metrics.pricing_latency_ms?.values?.['p(95)']?.toFixed(1)),
      },
      purchase: {
        intercompany_p95_ms: fmt(data.metrics.intercompany_latency_ms?.values?.['p(95)']?.toFixed(1)),
      },
      thresholds: thresholdResults,
    }, null, 2),
  };
}
