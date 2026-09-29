// Business flow guard — onboarding to first sale, in order.
//
// Walks a new business from sign-up to a sale through every step it depends on, and at each step
// proves the guard: a step works once what it needs exists, and is refused when attempted too
// early, by the wrong person, or against someone else's tenant.
//
//   1 auth          start a business (the owner's own sign-up), log in, who-am-I; a shopper's
//                   sign-up beside it is still a customer
//   2 onboarding    create the tenant, first store, OWNER grant, no reaching into other tenants
//   3 locations     stores and zones
//   4 staff         provision and assign a storekeeper; they get staff powers, not management ones
//   5 catalogue     brands, categories, products, variants, storefront reads
//   6 stock         receive (idempotent), adjust, levels, batches, movements, thresholds
//   7 status        onboarding checklist
//   8 selling       prices, reservations, POS orders (idempotent, currency-checked)
//
//   k6/run.sh flow-guard-comprehensive
import { group } from 'k6';
import {
  ALL_CHECKS_PASS,
  PASSWORD,
  call,
  claims,
  data,
  expect,
  login,
  newId,
  onboardTenant,
  priceVariants,
  register,
  registerBusiness,
  signInUntil,
  truthy,
  uniq,
} from './lib/storeql.js';

export const options = {
  scenarios: { flow: { executor: 'per-vu-iterations', vus: 1, iterations: 1, maxDuration: '10m' } },
  thresholds: ALL_CHECKS_PASS,
};

const UNKNOWN = '01a0b000-0000-7000-8000-000000000000';

export default function () {
  const run = uniq();
  const ctx = {};

  group('1 auth', () => {
    // The owner starts a business: its own sign-up, never a shopper's.
    const START = '/api/iam-svc/auth/register/business';
    const email = `flow-owner-${run}@k6.storeql.test`;
    expect(call('POST', START, { body: { email, password: 'short' } }), 'start a business: weak password rejected', 400);
    expect(call('POST', START, { body: { email, password: 'fourteen chars' } }), 'start a business: the password policy holds', 400, 'PASSWORD_TOO_SHORT');
    expect(call('POST', START, { body: { email: 'not-an-email', password: PASSWORD } }), 'start a business: bad email rejected', 400);
    const reg = call('POST', START, { body: { email, password: PASSWORD } });
    expect(reg, 'start a business', 201);
    expect(call('POST', START, { body: { email, password: PASSWORD } }), 'start a business: same email twice', 409, 'USER_ALREADY_EXISTS');
    // A shopper's account and a business account are separate identities (29 Sep 2026): the owner's
    // address may shop too, as a login of its own — once — and a sign-in names which it means.
    const alsoShopper = call('POST', '/api/iam-svc/auth/register', { body: { email, password: PASSWORD } });
    expect(alsoShopper, 'register: the owner\'s email as a shopper too, a separate account', 201);
    expect(call('POST', '/api/iam-svc/auth/register', { body: { email, password: PASSWORD } }), 'register: one shopper account per email', 409, 'USER_ALREADY_EXISTS');
    ctx.owner = { email, password: PASSWORD, token: data(reg).accessToken, refreshToken: data(reg).refreshToken };
    ctx.owner.userId = claims(ctx.owner.token).sub;
    expect(call('POST', '/api/iam-svc/auth/login', { body: { email, password: 'Wrong-Passw0rd!' } }), 'login: wrong password', 401);
    const atWork = login(ctx.owner);
    expect(atWork, 'login', 200);
    truthy('login: running a business opens the business account, not the shopper one on its email', claims(data(atWork).accessToken).sub === ctx.owner.userId, claims(data(atWork).accessToken));
    const atTheShop = claims(data(call('POST', '/api/iam-svc/auth/login', { body: { email, password: PASSWORD, accountType: 'CUSTOMER' } })).accessToken);
    truthy('login: a storefront opens the shopper account', atTheShop.type === 'CUSTOMER' && atTheShop.sub === claims(data(alsoShopper).accessToken).sub, atTheShop);
    expect(call('GET', '/api/iam-svc/auth/me', { token: ctx.owner.token }), 'who am I', 200);
    expect(call('GET', '/api/iam-svc/auth/me'), 'who am I: no token', 401);
    const owner = claims(ctx.owner.token);
    truthy('a business sign-up is staff with no tenant and no role yet', owner.type === 'STAFF' && !owner.tenant && Array.isArray(owner.roles) && owner.roles.length === 0, owner);

    // A shopper's sign-up is unchanged.
    const shopperEmail = `flow-shopper-${run}@k6.storeql.test`;
    expect(call('POST', '/api/iam-svc/auth/register', { body: { email: shopperEmail, password: 'short' } }), 'register: weak password rejected', 400);
    expect(call('POST', '/api/iam-svc/auth/register', { body: { email: 'not-an-email', password: PASSWORD } }), 'register: bad email rejected', 400);
    const shopper = call('POST', '/api/iam-svc/auth/register', { body: { email: shopperEmail, password: PASSWORD } });
    expect(shopper, 'register', 201);
    expect(call('POST', '/api/iam-svc/auth/register', { body: { email: shopperEmail, password: PASSWORD } }), 'register: same email twice', 409);
    const shopping = claims(data(shopper).accessToken);
    truthy('a shopper sign-up is a customer with no tenant', shopping.type === 'CUSTOMER' && !shopping.tenant && (shopping.roles || []).includes('CUSTOMER'), shopping);
  });

  group('2 onboarding', () => {
    const t = ctx.owner.token;
    expect(call('GET', '/api/tenant-svc/admin/tenant', { token: t }), 'before a tenant: admin reads refused', 403);
    expect(
      call('POST', '/api/tenant-svc/admin/stores', { token: t, body: { name: 'Too soon', code: 'EARLY', timezone: 'Europe/London' } }),
      'before a tenant: cannot add a store',
      403
    );
    expect(
      call('POST', '/api/tenant-svc/onboarding/tenants', { token: t, body: { businessName: 'No country', currency: 'INR' } }),
      'create tenant: country required',
      400
    );
    expect(
      call('POST', '/api/tenant-svc/onboarding/tenants', { body: { businessName: 'Anon', country: 'IN', currency: 'INR' } }),
      'create tenant: needs a signed-in user',
      401
    );
    const created = call('POST', '/api/tenant-svc/onboarding/tenants', {
      token: t,
      body: { businessName: `Flow Co ${run}`, legalName: `Flow Co ${run} Pvt Ltd`, country: 'IN', currency: 'INR' },
    });
    expect(created, 'create tenant', 201);
    ctx.tenantId = data(created).id;
    ctx.tenant = { label: 'flow', tenantId: ctx.tenantId, country: 'IN', currency: 'INR', owner: ctx.owner };

    // Until iam applies TenantCreated the owner's token has no tenant; the onboarding paths accept
    // the tenant id from the caller, and tenant-svc checks the caller really owns it.
    const stranger = register('flow-stranger');
    const spoof = { 'X-Tenant-Id': ctx.tenantId };
    expect(
      call('POST', '/api/tenant-svc/onboarding/stores', { token: stranger.token, headers: spoof, body: { name: 'Hijack', code: 'HIJACK', timezone: 'Europe/London' } }),
      "a stranger naming someone else's tenant cannot add a store",
      403,
      'TENANT_ACCESS_DENIED'
    );
    expect(
      call('GET', '/api/tenant-svc/onboarding/status', { token: stranger.token, headers: spoof }),
      "a stranger cannot read someone else's onboarding status",
      403,
      'TENANT_ACCESS_DENIED'
    );
    // Another business sign-up, still without a business of its own, is as much a stranger here.
    const founder = registerBusiness('flow-other-founder');
    expect(
      call('POST', '/api/tenant-svc/onboarding/stores', { token: founder.token, headers: spoof, body: { name: 'Hijack', code: 'HIJACK2', timezone: 'Europe/London' } }),
      "another business sign-up naming this tenant cannot add a store",
      403,
      'TENANT_ACCESS_DENIED'
    );
    expect(
      call('GET', '/api/tenant-svc/onboarding/status', { token: founder.token, headers: spoof }),
      "...nor read its onboarding status",
      403,
      'TENANT_ACCESS_DENIED'
    );
    expect(call('GET', '/api/tenant-svc/admin/tenant', { token: founder.token, headers: spoof }), '...nor read its profile', 403);
    const first = call('POST', '/api/tenant-svc/onboarding/stores', {
      token: t,
      headers: spoof,
      body: { name: 'Flow Main', code: `MAIN-${run}`.slice(0, 24), type: 'STORE', line1: '1 Market St', city: 'Mumbai', state: 'MH', country: 'IN', pincode: '400001', timezone: 'Asia/Kolkata' },
    });
    expect(first, 'owner adds the first store before the grant arrives', 201);
    ctx.storeId = data(first).id;
    truthy('the first store is the default', data(first).isDefault === true, data(first));

    signInUntil(ctx.owner, (c) => c.tenant === ctx.tenantId && (c.roles || []).includes('OWNER'));
    truthy('the owner token now carries the tenant and OWNER', claims(ctx.owner.token).tenant === ctx.tenantId);
    expect(call('GET', '/api/tenant-svc/admin/tenant', { token: ctx.owner.token }), 'read tenant profile', 200);
    expect(
      call('PUT', '/api/tenant-svc/admin/tenant', { token: ctx.owner.token, body: { businessName: `Flow Co Renamed ${run}` } }),
      'update tenant profile',
      200
    );
    expect(call('PUT', '/api/tenant-svc/admin/tenant', { token: ctx.owner.token, body: {} }), 'update tenant: name required', 400);

    // A second business: its owner must see nothing of the first, whatever headers they send.
    ctx.rival = onboardTenant('flow-rival', { country: 'GB', currency: 'GBP', stores: 1 });
    const rivalStores = call('GET', '/api/tenant-svc/admin/stores', { token: ctx.rival.owner.token, headers: spoof });
    expect(rivalStores, 'rival lists stores with a spoofed X-Tenant-Id', 200);
    truthy('spoofed X-Tenant-Id is ignored: rival sees only its own stores', !(data(rivalStores) || []).some((s) => s.id === ctx.storeId), data(rivalStores));
    expect(call('GET', `/api/tenant-svc/admin/stores/${ctx.storeId}`, { token: ctx.rival.owner.token }), "rival cannot read our store", 404);
  });

  group('3 locations', () => {
    const t = ctx.owner.token;
    expect(call('GET', '/api/tenant-svc/admin/stores', { token: t }), 'list stores', 200);
    expect(call('GET', `/api/tenant-svc/admin/stores/${ctx.storeId}`, { token: t }), 'get store', 200);
    expect(call('GET', `/api/tenant-svc/admin/stores/${UNKNOWN}`, { token: t }), 'get unknown store', 404);
    expect(call('PUT', `/api/tenant-svc/admin/stores/${ctx.storeId}`, { token: t, body: { name: 'Flow Main (renamed)' } }), 'update store', 200);
    expect(
      call('POST', '/api/tenant-svc/admin/stores', { token: t, body: { name: 'Dup', code: `MAIN-${run}`.slice(0, 24), timezone: 'Europe/London' } }),
      'store code is unique in the tenant',
      409
    );
    expect(call('PATCH', `/api/tenant-svc/admin/stores/${ctx.storeId}/status`, { token: t, body: { status: 'ACTIVE' } }), 'set store status', 200);

    const zones = call('GET', `/api/tenant-svc/admin/stores/${ctx.storeId}/zones`, { token: t });
    expect(zones, 'list zones', 200);
    truthy('a new store comes with its DEFAULT zone', (data(zones) || []).some((z) => z.code === 'DEFAULT'), data(zones));
    const zone = call('POST', `/api/tenant-svc/admin/stores/${ctx.storeId}/zones`, { token: t, body: { name: 'Aisle A', code: 'AISLE-A', type: 'AISLE' } });
    expect(zone, 'add a zone', 201);
    ctx.zoneId = data(zone).id;
    expect(
      call('POST', `/api/tenant-svc/admin/stores/${ctx.storeId}/zones`, { token: t, body: { name: 'Aisle A again', code: 'AISLE-A', type: 'AISLE' } }),
      'zone code is unique in the store',
      409
    );
    expect(
      call('POST', `/api/tenant-svc/admin/stores/${UNKNOWN}/zones`, { token: t, body: { name: 'Nowhere', code: 'NOWHERE' } }),
      'zone in an unknown store',
      404
    );
    expect(call('GET', `/api/tenant-svc/admin/stores/${ctx.storeId}/zones/${ctx.zoneId}`, { token: t }), 'get zone', 200);
    expect(
      call('PUT', `/api/tenant-svc/admin/stores/${ctx.storeId}/zones/${ctx.zoneId}`, { token: t, body: { name: 'Aisle A1', code: 'AISLE-A', type: 'AISLE' } }),
      'update zone',
      200
    );
    expect(
      call('PATCH', `/api/tenant-svc/admin/stores/${ctx.storeId}/zones/${ctx.zoneId}/status`, { token: t, body: { status: 'ACTIVE' } }),
      'set zone status',
      200
    );
  });

  group('4 staff', () => {
    const t = ctx.owner.token;
    // Staff are made in the business the one way there is: the owner provisions the address, and an
    // assignment then binds that login (a StaffAssigned never takes on a shopper's sign-up by id).
    const keeperEmail = `flow-storekeeper-${run}@k6.storeql.test`;
    const made = call('POST', '/api/iam-svc/auth/admin/staff-users', { token: t, body: { email: keeperEmail, password: PASSWORD } });
    expect(made, 'provision a storekeeper login in the business', 200);
    ctx.keeper = { email: keeperEmail, password: PASSWORD, userId: (data(made) || {}).userId };
    ctx.keeper.token = (data(login(ctx.keeper)) || {}).accessToken;
    expect(
      call('POST', '/api/tenant-svc/admin/staff', { token: t, body: { userId: ctx.keeper.userId, storeId: UNKNOWN, role: 'STOREKEEPER' } }),
      'assign staff to an unknown store',
      404
    );
    expect(
      call('POST', '/api/tenant-svc/admin/staff', { token: t, body: { userId: ctx.keeper.userId, storeId: ctx.storeId, role: 'STOREKEEPER' } }),
      'assign a storekeeper',
      201
    );
    expect(call('GET', '/api/tenant-svc/admin/staff', { token: t }), 'list staff', 200);
    expect(
      call('POST', '/api/tenant-svc/admin/staff', { token: ctx.keeper.token, body: { userId: ctx.keeper.userId, storeId: ctx.storeId, role: 'OWNER' } }),
      'before the grant: the storekeeper cannot promote themselves',
      403
    );
    signInUntil(ctx.keeper, (c) => c.tenant === ctx.tenantId && (c.roles || []).includes('STOREKEEPER'));
    expect(call('GET', `/api/tenant-svc/admin/stores/${ctx.storeId}`, { token: ctx.keeper.token }), 'storekeeper reads their store', 200);
    expect(
      call('PUT', '/api/tenant-svc/admin/tenant', { token: ctx.keeper.token, body: { businessName: 'Taken over' } }),
      'storekeeper cannot change the tenant',
      403
    );
    expect(
      call('POST', '/api/tenant-svc/admin/staff', { token: ctx.keeper.token, body: { userId: ctx.keeper.userId, storeId: ctx.storeId, role: 'OWNER' } }),
      'storekeeper cannot assign roles',
      403
    );
  });

  group('5 catalogue', () => {
    const t = ctx.owner.token;
    expect(call('POST', '/api/product-svc/admin/brands', { token: t, body: {} }), 'brand: name required', 400);
    const brand = call('POST', '/api/product-svc/admin/brands', { token: t, body: { name: `Flow Brand ${run}` } });
    expect(brand, 'create brand', 201);
    expect(call('GET', '/api/product-svc/admin/brands', { token: t }), 'list brands', 200);
    expect(call('GET', `/api/product-svc/admin/brands/${data(brand).id}`, { token: t }), 'get brand', 200);
    expect(call('PUT', `/api/product-svc/admin/brands/${data(brand).id}`, { token: t, body: { name: `Flow Brand 2 ${run}` } }), 'update brand', 200);
    const category = call('POST', '/api/product-svc/admin/categories', { token: t, body: { name: `Flow Category ${run}` } });
    expect(category, 'create category', 201);
    expect(call('GET', '/api/product-svc/admin/categories', { token: t }), 'list categories', 200);

    expect(
      call('POST', '/api/product-svc/admin/products', { token: ctx.keeper.token, body: { name: 'Keeper product' } }),
      'storekeeper cannot create products',
      403
    );
    const product = call('POST', '/api/product-svc/admin/products', {
      token: t,
      body: { name: `Flow Tea ${run}`, description: 'Assam', brandId: data(brand).id, categoryId: data(category).id, sellableOnline: true, sellablePos: true },
    });
    expect(product, 'create product', 201);
    ctx.productId = data(product).id;
    expect(call('GET', '/api/product-svc/admin/products', { token: t }), 'list products', 200);
    expect(call('GET', `/api/product-svc/admin/products/${ctx.productId}`, { token: t }), 'get product', 200);
    expect(call('GET', `/api/product-svc/admin/products/${ctx.productId}`, { token: ctx.rival.owner.token }), "rival cannot read our product", 404);
    expect(
      call('PUT', `/api/product-svc/admin/products/${ctx.productId}`, { token: t, body: { name: `Flow Tea (loose) ${run}`, sellableOnline: true, sellablePos: true } }),
      'update product',
      200
    );
    const sku = `FLOW-${run}`.slice(0, 40);
    expect(call('POST', `/api/product-svc/admin/products/${UNKNOWN}/variants`, { token: t, body: { sku: `X-${sku}` } }), 'variant of an unknown product', 404);
    const variant = call('POST', `/api/product-svc/admin/products/${ctx.productId}/variants`, {
      token: t,
      body: { sku, barcode: `${run}`.slice(-13), attributes: JSON.stringify({ pack: '250g' }), unit: 'PCS' },
    });
    expect(variant, 'create variant', 201);
    ctx.variantId = data(variant).id;
    expect(call('POST', `/api/product-svc/admin/products/${ctx.productId}/variants`, { token: t, body: { sku } }), 'sku is unique in the tenant', 409);
    expect(call('GET', `/api/product-svc/admin/products/${ctx.productId}/variants`, { token: t }), 'list variants', 200);

    const shop = { storefront: ctx.tenantId };
    expect(call('GET', '/api/product-svc/catalog/products', shop), 'storefront: catalogue', 200);
    expect(call('GET', `/api/product-svc/catalog/products/${ctx.productId}`, shop), 'storefront: product', 200);
    expect(call('GET', `/api/product-svc/catalog/products/${ctx.productId}/variants`, shop), 'storefront: variants', 200);
    expect(
      call('GET', `/api/product-svc/catalog/products/${ctx.productId}`, { storefront: ctx.rival.tenantId }),
      "storefront: another shop does not show our product",
      404
    );
    expect(call('GET', '/api/product-svc/catalog/products'), 'storefront: no shop named', 401);
  });

  group('6 stock', () => {
    const t = ctx.owner.token;
    const receipt = { storeId: ctx.storeId, variantId: ctx.variantId, qty: 500, batchNo: `B-${run}`.slice(0, 32), costPrice: '250.00', expiryDate: '2027-12-31' };
    expect(call('POST', '/api/inventory-svc/admin/inventory/receive', { token: t, idem: true, body: { ...receipt, qty: 0 } }), 'receive: qty must be positive', 400);
    const key = newId();
    const received = call('POST', '/api/inventory-svc/admin/inventory/receive', { token: t, idem: key, body: receipt });
    expect(received, 'receive stock', 201);
    ctx.batchId = data(received).id;
    const replay = call('POST', '/api/inventory-svc/admin/inventory/receive', { token: t, idem: key, body: receipt });
    expect(replay, 'receive: retried with the same Idempotency-Key', [200, 201]);
    truthy('the retry replays the same batch', data(replay).id === ctx.batchId, data(replay));

    const levels = call('GET', `/api/inventory-svc/admin/inventory/levels?store=${ctx.storeId}`, { token: t });
    expect(levels, 'stock levels', 200);
    const onHand = ((data(levels) || []).find((l) => l.variantId === ctx.variantId) || {}).onHand;
    truthy('the retried receipt was not counted twice', Number(onHand) === 500, data(levels));
    expect(call('GET', `/api/inventory-svc/admin/inventory/levels?store=${ctx.storeId}`, { token: ctx.rival.owner.token }), 'rival reads levels (their own tenant)', 200);
    truthy(
      "rival's levels never include our stock",
      !(data(call('GET', `/api/inventory-svc/admin/inventory/levels?store=${ctx.storeId}`, { token: ctx.rival.owner.token })) || []).some((l) => l.variantId === ctx.variantId)
    );

    expect(call('GET', `/api/inventory-svc/admin/inventory/batches?store=${ctx.storeId}&variant=${ctx.variantId}`, { token: t }), 'list batches', 200);
    expect(call('GET', `/api/inventory-svc/admin/inventory/batches/${ctx.batchId}`, { token: t }), 'get batch', 200);
    expect(
      call('POST', '/api/inventory-svc/admin/inventory/receive', { token: ctx.keeper.token, idem: true, body: { ...receipt, batchNo: `K-${run}`.slice(0, 32), qty: 10 } }),
      'storekeeper receives stock',
      201
    );
    expect(
      call('POST', '/api/inventory-svc/admin/inventory/adjust', { token: t, body: { storeId: ctx.storeId, variantId: ctx.variantId, delta: 50, reason: 'cycle count correction' } }),
      'adjust stock',
      200
    );
    expect(
      call('POST', '/api/inventory-svc/admin/inventory/adjust', { token: t, body: { storeId: ctx.storeId, variantId: ctx.variantId, delta: -100000, reason: 'shrinkage' } }),
      'adjust: cannot take away more than is on hand',
      422,
      'INSUFFICIENT_STOCK'
    );
    expect(call('GET', `/api/inventory-svc/admin/inventory/movements?store=${ctx.storeId}&limit=20`, { token: t }), 'stock movements', 200);
    expect(
      call('POST', '/api/inventory-svc/admin/inventory/thresholds', { token: t, body: { storeId: ctx.storeId, variantId: ctx.variantId, threshold: '50.000' } }),
      'set reorder threshold',
      201
    );
    expect(call('GET', `/api/inventory-svc/admin/inventory/thresholds?store=${ctx.storeId}`, { token: t }), 'list thresholds', 200);
  });

  group('7 status', () => {
    const status = call('GET', '/api/tenant-svc/onboarding/status', { token: ctx.owner.token });
    expect(status, 'onboarding status', 200);
    truthy('the checklist shows an active tenant with a default store', data(status).tenantActive === true && data(status).hasDefaultStore === true, data(status));
  });

  group('8 selling', () => {
    const t = ctx.owner.token;
    const pos = (body, idem) => call('POST', '/api/order-svc/orders', { token: t, idem, body });
    const sale = { storeId: ctx.storeId, channel: 'POS', fulfilmentType: 'INSTORE', paymentMethod: 'CASH', items: [{ variantId: ctx.variantId, qty: 1 }] };

    expect(pos(sale, true), 'order before the variant has a price', [400, 404, 409, 422]);
    priceVariants(ctx.tenant, [ctx.variantId], '120.00');

    expect(pos(sale, undefined), 'order: Idempotency-Key required', 400, 'MISSING_IDEMPOTENCY_KEY');
    expect(pos({ ...sale, items: [] }, true), 'order: needs items', 400);
    expect(pos({ ...sale, currency: 'USD' }, true), "order: not in the tenant's currency", 400, 'ORDER_CURRENCY_MISMATCH');
    expect(
      call('POST', '/api/order-svc/orders', { token: ctx.rival.owner.token, idem: true, body: { ...sale, items: [{ variantId: ctx.variantId, qty: 1 }], storeId: ctx.rival.stores[0].id } }),
      "rival cannot sell our variant",
      [400, 404, 409, 422]
    );
    const key = newId();
    const placed = pos(sale, key);
    expect(placed, 'POS order placed', 201);
    truthy('the order is in INR at the listed price', data(placed).currency === 'INR' && Number(data(placed).subtotal) === 120, data(placed));
    const again = pos(sale, key);
    expect(again, 'POS order retried with the same Idempotency-Key', [200, 201]);
    truthy('the retry returns the same order', data(again).id === data(placed).id, { first: data(placed).id, again: data(again).id });
    expect(
      call('POST', '/api/order-svc/orders', { token: ctx.keeper.token, idem: true, body: sale }),
      'a storekeeper cannot ring up a POS sale',
      403
    );

    const reserve = (qty, ttlSeconds = 300) =>
      call('POST', '/api/inventory-svc/inventory/reservations', { token: t, idem: true, body: { storeId: ctx.storeId, variantId: ctx.variantId, qty, ttlSeconds } });
    expect(reserve(1000000), 'reserve more than is available', 422, 'INSUFFICIENT_STOCK');
    const held = reserve(5);
    expect(held, 'reserve stock', 201);
    const heldId = data(held).id;
    expect(call('GET', `/api/inventory-svc/inventory/reservations?store=${ctx.storeId}&status=HELD`, { token: t }), 'list held reservations', 200);
    expect(call('GET', `/api/inventory-svc/inventory/reservations/${heldId}`, { token: t }), 'get reservation', 200);
    expect(call('GET', `/api/inventory-svc/inventory/reservations/${heldId}`, { token: ctx.rival.owner.token }), "rival cannot see our reservation", 404);
    expect(call('POST', `/api/inventory-svc/inventory/reservations/${heldId}/consume`, { token: t, body: {} }), 'consume reservation', 200);
    const consumed = call('GET', `/api/inventory-svc/inventory/reservations/${heldId}`, { token: t });
    truthy('reservation is CONSUMED', data(consumed).status === 'CONSUMED', data(consumed));
    expect(call('POST', `/api/inventory-svc/inventory/reservations/${heldId}/consume`, { token: t, body: {} }), 'consume twice refused', 422, 'RESERVATION_NOT_HELD');
    // Release is a saga compensation and must be safe to repeat: on anything not HELD it does nothing.
    const lateRelease = call('POST', `/api/inventory-svc/inventory/reservations/${heldId}/release`, { token: t, body: {} });
    expect(lateRelease, 'release after consume is accepted', 200);
    truthy('...and changes nothing', data(lateRelease) === 'noop' && data(call('GET', `/api/inventory-svc/inventory/reservations/${heldId}`, { token: t })).status === 'CONSUMED', data(lateRelease));

    const second = reserve(3, 60);
    expect(second, 'reserve again', 201);
    expect(call('POST', `/api/inventory-svc/inventory/reservations/${data(second).id}/release`, { token: t, body: {} }), 'release reservation', 200);
    expect(call('POST', `/api/inventory-svc/inventory/reservations/${data(second).id}/consume`, { token: t, body: {} }), 'consume a released reservation refused', 422, 'RESERVATION_NOT_HELD');
    expect(call('POST', `/api/inventory-svc/inventory/reservations/${UNKNOWN}/consume`, { token: t, body: {} }), 'consume an unknown reservation', 404);
  });
}
