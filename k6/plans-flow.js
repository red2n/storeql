// Plans and packaging (21.8), through the gateway: the platform writes a price list — a draft, then
// a price, then what it includes, then on sale, then the plan a business signing up starts on — and
// a business that signs up lands on it. What the plan allows is then what the business can actually
// do: the store limit and the staff limit are refused by tenant-svc from its own data, and the
// product limit by product-svc, which reads the allowance from tenant-svc rather than its tables.
// A bigger plan lifts the ceiling; moving back down while the business is over it is refused, naming
// what is over. Retiring a plan keeps the businesses on it and offers it to nobody, and a business
// that signs up with no default plan is unrestricted and says so. Refused: selling a plan with no
// price, a draft as the default, a duplicate code, a promise nobody enforces, changing how often a
// plan bills under its subscribers, an owner writing the price list or putting itself on a plan, a
// cashier reading it, an anonymous caller.
//
//   k6/run.sh plans-flow
import { Counter } from 'k6/metrics';
import { ALL_CHECKS_PASS, call, data, expect, must, newId, onboardTenant, platformAdmin, staffUser, truthy, uniq } from './lib/storeql.js';

const completed = new Counter('flow_completed');
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
  setupTimeout: '4m',
};

const PLANS = '/api/tenant-svc/platform/plans';
const MINE = '/api/tenant-svc/admin/tenant/plan';
const STORES = '/api/tenant-svc/admin/stores';
const STAFF = '/api/tenant-svc/admin/staff';
const PRODUCTS = '/api/product-svc/admin/products';

export function setup() {
  return { admin: platformAdmin() };
}

export default function ({ admin }) {
  const tag = uniq().toUpperCase().slice(0, 10);
  const root = admin.token;
  const plan = (method, path, body) => call(method, `${PLANS}${path || ''}`, { token: root, body });
  const planBody = (code, interval) => ({ code, name: `${code} plan`, description: 'sold to shops', billingInterval: interval, trialDays: 14, isPublic: true, sortOrder: 1 });

  // A plan on sale, allowing exactly what it is given.
  const sell = (code, grants) => {
    const written = must(plan('POST', '', planBody(code, 'MONTH')), 201, `write ${code}`);
    must(plan('POST', `/${written.id}/prices`, { currency: 'GBP', amount: 49 }), 200, 'price it');
    must(plan('PUT', `/${written.id}/includes`, { grants }), 200, 'what it includes');
    must(plan('POST', `/${written.id}/activate`), 200, 'sell it');
    return written.id;
  };
  const makeDefault = (id) => must(plan('POST', `/${id}/default`), 200, 'the default plan');
  const mine = (tenant) => data(call('GET', MINE, { token: tenant.owner.token }));
  const usageOf = (tenant, key) => (mine(tenant).usage || []).find((u) => u.key === key) || {};
  const addStore = (tenant, code) => call('POST', STORES, { token: tenant.owner.token, body: { name: `Store ${code}`, code, timezone: 'Europe/London', country: 'GB' } });
  const addProduct = (tenant, n) => call('POST', PRODUCTS, { token: tenant.owner.token, body: { name: `Thing ${n} ${tag}`, sellableOnline: false, sellablePos: true } });
  const assign = (tenant, userId, storeId, role) => call('POST', STAFF, { token: tenant.owner.token, body: { userId, storeId, role } });

  // ── the price list ───────────────────────────────────────────────────────────────────────────────
  const draft = must(plan('POST', '', planBody(`DRAFT-${tag}`, 'MONTH')), 201, 'a draft plan');
  truthy('[+] a new plan is a draft: nothing is sold until somebody sells it', draft.status === 'DRAFT' && draft.isDefault === false, draft);
  expect(plan('POST', `/${draft.id}/activate`), '[-] a plan with no price is a promise with no number', 409, 'PLAN_HAS_NO_PRICE');
  expect(plan('POST', `/${draft.id}/default`), '[-] a draft is not on sale, so nobody starts on it', 409, 'PLAN_NOT_SOLD');
  expect(plan('POST', '', planBody(`draft-${tag}`, 'MONTH')), '[-] a code is taken once, whatever its case', 409, 'PLAN_CODE_TAKEN');
  expect(plan('POST', '', planBody(`BAD CODE ${tag}`, 'MONTH')), '[-] a code is letters, digits, dash and underscore', 400, 'PLAN_CODE_INVALID');
  expect(plan('POST', '', planBody(`WEEKLY-${tag}`, 'FORTNIGHT')), '[-] a plan bills by month or year', 400, 'PLAN_INTERVAL_UNKNOWN');
  expect(plan('PUT', `/${draft.id}/includes`, { grants: [{ key: 'support.priority', enabled: true }] }), '[-] a key nobody enforces is a promise nobody keeps', 400, 'PLAN_ENTITLEMENT_UNKNOWN');
  expect(plan('PUT', `/${draft.id}/includes`, { grants: [{ key: 'stores.max', enabled: true }] }), '[-] a limit is a number, not a yes or no', 400, 'PLAN_ENTITLEMENT_SHAPE');
  expect(plan('POST', `/${draft.id}/prices`, { currency: 'pounds', amount: 1 }), '[-] a currency is a three-letter code', 400);
  expect(plan('POST', `/${draft.id}/prices`, { currency: 'GBP', amount: 1, effectiveFrom: 'soon' }), '[-] a price takes effect on a date', 400, 'PLAN_PRICE_DATE_INVALID');

  const keys = data(call('GET', `${PLANS}/entitlement-keys`, { token: root }));
  truthy('[+] the keys on offer are the ones something actually enforces', (keys.entitlements || []).map((e) => e.key).sort().join(',') === 'documents.mb.max,feature.storefront,images.mb.max,products.max,requests.per-minute,staff.max,stores.max', keys);

  // ── a business lands on the default plan ─────────────────────────────────────────────────────────
  const small = sell(`SMALL-${tag}`, [
    { key: 'stores.max', limitValue: 2 },
    { key: 'staff.max', limitValue: 2 },
    { key: 'products.max', limitValue: 2 },
    { key: 'feature.storefront', enabled: true },
  ]);
  makeDefault(small);

  const shop = onboardTenant(`plans-${tag}`, { country: 'GB', currency: 'GBP' });
  const first = mine(shop);
  truthy('[+] a business that signs up lands on the plan the platform sells', first.plan && first.plan.code === `SMALL-${tag}`, first.plan);
  truthy('[+] and sees what it is allowed against what it is using', usageOf(shop, 'stores.max').limitValue === 2 && usageOf(shop, 'stores.max').used === 1, usageOf(shop, 'stores.max'));
  truthy('[+] a price it is sold at', (first.plan.prices || []).some((p) => p.currency === 'GBP' && Number(p.amount) === 49), first.plan.prices);

  // ── the store limit, refused by the service that owns the stores ─────────────────────────────────
  const second = addStore(shop, `S2-${tag}`);
  expect(second, '[+] the second store fits', 201);
  const third = addStore(shop, `S3-${tag}`);
  expect(third, '[-] and the third does not', 409, 'PLAN_LIMIT_REACHED');
  truthy('[+] the refusal names the figures, so it can be acted on', /allows 2 stores/.test(third.body) && /has 2/.test(third.body), String(third.body).slice(0, 200));

  // ── the staff limit counts people, not assignments ───────────────────────────────────────────────
  const stores = [shop.stores[0], data(second)];
  truthy('[+] the business has the two stores its plan allows', stores.every((st) => !!st.id), stores.map((st) => st && st.id));
  const ann = newId();
  expect(assign(shop, ann, stores[0].id, 'CASHIER'), '[+] somebody is taken on', 201);
  expect(assign(shop, newId(), stores[0].id, 'CASHIER'), '[+] and a second', 201);
  expect(assign(shop, ann, stores[1].id, 'MANAGER'), '[+] the same person at a second store is not a second person', 201);
  const hired = assign(shop, newId(), stores[0].id, 'CASHIER');
  expect(hired, '[-] a third person does not fit', 409, 'PLAN_LIMIT_REACHED');
  truthy('[+] ...and the plan screen agrees about how many there are', usageOf(shop, 'staff.max').used === 2, usageOf(shop, 'staff.max'));

  // ── the product limit, refused by the service that owns the products ─────────────────────────────
  expect(addProduct(shop, 1), '[+] the first product fits', 201);
  expect(addProduct(shop, 2), '[+] and the second', 201);
  const over = addProduct(shop, 3);
  expect(over, '[-] the third is past what the plan allows', 409, 'PLAN_LIMIT_REACHED');
  truthy('[+] product-svc read the allowance from tenant-svc, not from its own tables', /allows 2 products/.test(over.body), String(over.body).slice(0, 200));
  truthy('[+] and the plan screen leaves that count to the service that owns it', usageOf(shop, 'products.max').used === undefined, usageOf(shop, 'products.max'));

  // ── a bigger plan, and the way back down ─────────────────────────────────────────────────────────
  const big = sell(`BIG-${tag}`, [{ key: 'stores.max', limitValue: 10 }, { key: 'staff.max', limitValue: 10 }]);
  const moved = call('PUT', `/api/tenant-svc/platform/tenants/${shop.tenantId}/plan`, { token: root, body: { planId: big, reason: 'they grew' } });
  expect(moved, '[+] the platform moves the business to a bigger plan', 200);
  truthy('[+] and the ceiling lifts', data(moved).plan.code === `BIG-${tag}`, data(moved).plan.code);
  expect(addStore(shop, `S3B-${tag}`), '[+] so the third store fits now', 201);

  const down = call('PUT', `/api/tenant-svc/platform/tenants/${shop.tenantId}/plan`, { token: root, body: { planId: small } });
  expect(down, '[-] moving back down while the business is over it is refused', 409, 'PLAN_LIMIT_EXCEEDED_NOW');
  truthy('[+] naming what is over, rather than taking a store away', /Stores and warehouses 3 of 2/.test(down.body), String(down.body).slice(0, 240));

  // ── retiring, and a business with no plan ────────────────────────────────────────────────────────
  expect(plan('POST', `/${small}/retire`), '[+] a plan is taken off sale', 200);
  expect(plan('POST', `/${small}/retire`), '[-] and only once', 409, 'PLAN_NOT_SOLD');
  expect(plan('POST', `/${big}/retire`), '[+] the default plan too', 200);
  truthy('[+] a business on a retired plan keeps what it bought', mine(shop).plan.code === `BIG-${tag}`, mine(shop).plan.code);

  const later = onboardTenant(`plans-none-${tag}`, { country: 'GB', currency: 'GBP' });
  const none = mine(later);
  truthy('[+] a business that signs up with nothing on sale is on no plan, and says so', !none.plan && none.note === 'This business is on no plan', none);
  expect(addStore(later, `FREE-${tag}`), '[+] and no plan is no limit', 201);

  // ── whose price list it is ───────────────────────────────────────────────────────────────────────
  const owner = shop.owner.token;
  const cashier = staffUser(shop, 'CASHIER', [stores[0].id]);
  expect(call('GET', PLANS, { token: owner }), '[abuse] a business does not read the price list', 403);
  expect(call('POST', PLANS, { token: owner, body: planBody(`SNEAK-${tag}`, 'MONTH') }), '[abuse] nor write one', 403);
  expect(call('PUT', `/api/tenant-svc/platform/tenants/${shop.tenantId}/plan`, { token: owner, body: { planId: big } }), '[abuse] nor put itself on a plan', 403);
  expect(call('GET', MINE, { token: cashier.token }), '[abuse] a cashier does not read what the business pays for', 403);
  expect(call('GET', MINE), '[-] nor does anybody without a token', 401);
  expect(call('GET', `${PLANS}/entitlement-keys`, { token: owner }), '[abuse] the catalogue is the platform\'s own', 403);
  truthy('[+] but the business reads its own plan', !!mine(shop).plan, 'own plan');

  completed.add(1);
}
