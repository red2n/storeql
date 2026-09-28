// Per-tenant rate limits and storage caps (21.11), through the gateway: a plan says how many API
// requests a minute a business may make — across every login it has and its online shop — and how
// many megabytes of product images and of supplier e-invoice documents it may keep. Each is refused by
// the door that owns the thing counted: the gateway counts requests once it knows whose they are and
// answers 429 with how long to wait; product-svc measures images as they would stand and refuses the
// one past the cap before storing a byte; purchase-svc does the same for documents. Another business
// has its own minute and its own room; the platform's administrator is nobody's tenant and is not
// counted; a plan that names no rate or cap holds nothing back.
//
//   k6/run.sh tenant-limits-flow
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import {
  ALL_CHECKS_PASS,
  BASE,
  call,
  data,
  expect,
  must,
  onboardTenant,
  platformAdmin,
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

const PLANS = '/api/tenant-svc/platform/plans';
const PRODUCTS = '/api/product-svc/admin/products';

export function setup() {
  return { admin: platformAdmin() };
}

export default function ({ admin }) {
  const tag = uniq().toUpperCase().slice(0, 8);
  const root = admin.token;
  const plan = (method, path, body) => call(method, `${PLANS}${path || ''}`, { token: root, body });
  const sell = (code, grants) => {
    const written = must(plan('POST', '', { code, name: `${code} plan`, description: 'sold to shops', billingInterval: 'MONTH', trialDays: 14, isPublic: true, sortOrder: 1 }), 201, `write ${code}`);
    must(plan('POST', `/${written.id}/prices`, { currency: 'GBP', amount: 49 }), 200, 'price it');
    must(plan('PUT', `/${written.id}/includes`, { grants }), 200, 'what it includes');
    must(plan('POST', `/${written.id}/activate`), 200, 'sell it');
    return written.id;
  };

  // ── the keys are on offer, and a plan carries them ──────────────────────────────────────────────
  const keys = (data(call('GET', `${PLANS}/entitlement-keys`, { token: root })) || {}).entitlements || [];
  truthy('[+] the platform can sell a request rate and two storage caps, each naming who refuses', ['requests.per-minute', 'images.mb.max', 'documents.mb.max'].every((k) => keys.some((e) => e.key === k && e.limit === true && !!e.enforcedBy)), keys.map((e) => e.key));
  expect(plan('PUT', `/${sell(`SHAPE-${tag}`, [])}/includes`, { grants: [{ key: 'requests.per-minute', enabled: true }] }), '[-] a rate is a number, not a yes or no', 400, 'PLAN_ENTITLEMENT_SHAPE');

  const tight = sell(`TIGHT-${tag}`, [
    { key: 'requests.per-minute', limitValue: 60 },
    { key: 'images.mb.max', limitValue: 1 },
    { key: 'documents.mb.max', limitValue: 0 },
    { key: 'feature.storefront', enabled: true },
  ]);
  // The default plan is the platform's, not this suite's: remembered here and put back at the end,
  // so a business onboarded by a later suite does not land on sixty requests a minute.
  const before = (data(call('GET', PLANS, { token: root })) || []).find((p) => p.isDefault);
  must(plan('POST', `/${tight}/default`), 200, 'the default plan');
  const shop = onboardTenant(`limits-${tag}`, { country: 'GB', currency: 'GBP' });
  const owner = shop.owner.token;
  const mine = data(call('GET', '/api/tenant-svc/admin/tenant/plan', { token: owner })) || {};
  truthy('[+] a business that signs up lands on it and sees the rate and the caps against its plan', mine.plan && mine.plan.code === `TIGHT-${tag}` && ['requests.per-minute', 'images.mb.max', 'documents.mb.max'].every((k) => (mine.usage || []).some((u) => u.key === k && u.limitValue !== undefined)), mine.usage);

  // A roomier plan for the neighbour, so its minute and its room are its own.
  const roomy = sell(`ROOMY-${tag}`, [{ key: 'requests.per-minute', limitValue: 10000 }]);
  const rival = onboardTenant(`limits-rival-${tag}`, { country: 'GB', currency: 'GBP' });
  must(call('PUT', `/api/tenant-svc/platform/tenants/${rival.tenantId}/plan`, { token: root, body: { planId: roomy, reason: 'a bigger shop' } }), 200, 'the rival on the roomy plan');

  // ── storage: images are measured as they would stand, and refused before a byte is stored ───────
  const product = (n) => must(call('POST', PRODUCTS, { token: owner, body: { name: `Pictured ${n} ${tag}`, sellablePos: true } }), 201, `product ${n}`).id;
  const image = 'x'.repeat(200 * 1024); // under the 256 KB ceiling per image; five fit in a megabyte
  const upload = (productId, token = owner) => http.put(`${BASE}${PRODUCTS}/${productId}/image`, image, { headers: { 'Content-Type': 'image/png', Authorization: `Bearer ${token}` }, tags: { name: 'PUT /api/product-svc/admin/products/{id}/image' } });
  const products = Array.from({ length: 6 }, (_, i) => product(i));
  const five = products.slice(0, 5).map((p) => upload(p).status);
  truthy('[+] five images of 200 KB fit in a megabyte', five.every((s) => s === 200), five);
  const sixth = upload(products[5]);
  truthy('[-] the sixth is refused, naming the plan\'s figure and what it would make', sixth.status === 409 && sixth.body.includes('PLAN_LIMIT_REACHED') && sixth.body.includes('allows 1 MB of product images') && sixth.body.includes('would make 1.2 MB'), { status: sixth.status, body: String(sixth.body).slice(0, 200) });
  expect(call('GET', `/api/product-svc/catalog/products/${products[5]}/image`, { storefront: shop.tenantId }), '[-] ...and nothing of it was kept', 404);
  truthy('[+] replacing one of the five is measured as the room it frees plus the room it takes', upload(products[0]).status === 200, 'replace');
  expect(call('DELETE', `${PRODUCTS}/${products[1]}/image`, { token: owner }), '[+] deleting one makes room', 200);
  truthy('[+] ...and the sixth fits now', upload(products[5]).status === 200, 'sixth after delete');

  // ── storage: a plan that keeps no documents refuses the first one, unread ────────────────────────
  const doc = call('POST', '/api/purchase-svc/e-invoices', { token: owner, body: '<?xml version="1.0"?><Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"/>', headers: { 'Content-Type': 'application/xml' } });
  expect(doc, '[-] a supplier e-invoice is refused by the document cap before it is read', 409, 'PLAN_LIMIT_REACHED');
  truthy('[-] ...naming the figure', String(doc.body).includes('allows 0 MB of supplier e-invoice documents'), String(doc.body).slice(0, 200));

  // ── the plan's minute: counted once the request is known to be this business's ──────────────────
  const ping = (token) => call('GET', '/api/tenant-svc/admin/tenant', { token });
  let refused = null;
  let sent = 0;
  for (let i = 0; i < 80 && !refused; i++) {
    const r = ping(owner);
    sent++;
    if (r.status === 429) refused = r;
  }
  truthy('[-] within the plan\'s 60 a minute the business is told to wait — this flow had already spent a good part of them', !!refused && sent <= 61, { sent, status: refused && refused.status });
  truthy('[-] ...with the plan\'s code and how long until the minute turns', !!refused && refused.body.includes('PLAN_RATE_LIMIT_REACHED') && Number(refused.headers['Retry-After']) >= 1 && Number(refused.headers['Retry-After']) <= 60, refused && { code: String(refused.body).slice(0, 120), retry: refused.headers['Retry-After'] });
  expect(call('GET', `/api/product-svc/catalog/products?limit=1`, { storefront: shop.tenantId }), '[-] the shop\'s guests are counted with the business, so they wait too', 429, 'PLAN_RATE_LIMIT_REACHED');
  expect(ping(rival.owner.token), '[+] the neighbour\'s minute is its own', 200);
  expect(call('GET', `${PLANS}/entitlement-keys`, { token: root }), '[+] the platform\'s administrator is nobody\'s tenant and is not counted', 200);
  expect(ping(owner), '[-] a second try inside the minute is still told to wait', 429, 'PLAN_RATE_LIMIT_REACHED');
  must(plan('POST', `/${before ? before.id : roomy}/default`), 200, 'the default plan put back');

  completed.add(1);
}
