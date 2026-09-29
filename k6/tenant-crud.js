// tenant-svc: the business, its stores, zones, delivery areas, staff, inventory settings, the
// storefront's view of them and the platform console — with the refusals around each.
//
//   k6/run.sh tenant-crud
import {
  ALL_CHECKS_PASS,
  call,
  data,
  expect,
  nextCursor,
  onboardTenant,
  platformAdmin,
  provisionStaff,
  register,
  registerBusiness,
  truthy,
  uniq,
} from './lib/storeql.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS, setupTimeout: '3m' };

const UNKNOWN = '01a0b000-0000-7000-8000-000000000000';

export function setup() {
  return { admin: platformAdmin(), tenant: onboardTenant('tenant', { stores: 1 }), rival: onboardTenant('tenant-rival', { stores: 1 }) };
}

export default function ({ admin, tenant, rival }) {
  const t = tenant.owner.token;
  const storeId = tenant.stores[0].id;

  // ── one-shot onboarding: tenant and first store together ───────────────────
  // The founder signs up to start a business: a staff login of no business and no role yet.
  const founder = registerBusiness('tenant-founder');
  const combined = call('POST', '/api/tenant-svc/onboarding', {
    token: founder.token,
    body: { businessName: `One Shot ${uniq()}`, country: 'GB', currency: 'GBP', storeName: 'Flagship', storeCode: `FLAG-${uniq()}`.slice(0, 24), storeCity: 'Leeds', storeCountry: 'GB', storeTimezone: 'Europe/London' },
  });
  expect(combined, '[+] onboard tenant and first store in one call', 201);
  expect(
    call('POST', '/api/tenant-svc/onboarding', { token: founder.token, body: { businessName: 'No store', country: 'GB', currency: 'GBP' } }),
    '[-] one-shot onboarding: store name and code required',
    400
  );
  expect(
    call('POST', '/api/tenant-svc/onboarding/tenants', { token: founder.token, body: { businessName: 'Bad', country: 'GBR', currency: 'GBP' } }),
    '[-] create tenant: country is two letters',
    400
  );

  // ── tenant profile ──────────────────────────────────────────────────────────
  const profile = call('GET', '/api/tenant-svc/admin/tenant', { token: t });
  expect(profile, '[+] read tenant profile', 200);
  truthy('[+] profile is our tenant, ACTIVE, in GBP', data(profile).id === tenant.tenantId && data(profile).status === 'ACTIVE' && data(profile).currency === 'GBP', data(profile));
  expect(call('PUT', '/api/tenant-svc/admin/tenant', { token: t, body: { businessName: `Renamed ${uniq()}`, legalName: 'Renamed Ltd' } }), '[+] update tenant profile', 200);
  expect(call('PUT', '/api/tenant-svc/admin/tenant', { token: t, body: { legalName: 'No name' } }), '[-] update tenant: business name required', 400);
  expect(call('GET', '/api/tenant-svc/admin/tenant'), '[-] tenant profile: no token', 401);
  // The founder's sign-up token still names no business (it gains one at the next sign-in): no role.
  expect(call('GET', '/api/tenant-svc/admin/tenant', { token: founder.token }), '[-] tenant profile: a token of no business and no role', 403);
  expect(call('GET', '/api/tenant-svc/admin/tenant', { token: register('tenant-shopper').token }), '[-] tenant profile: a shopper token', 403);

  // ── stores ──────────────────────────────────────────────────────────────────
  const code = `S2-${uniq()}`.slice(0, 24);
  expect(call('POST', '/api/tenant-svc/admin/stores', { token: t, body: { code } }), '[-] create store: name required', 400);
  const warehouse = call('POST', '/api/tenant-svc/admin/stores', {
    token: t,
    body: { name: 'Back Warehouse', code, type: 'WAREHOUSE', line1: '9 Dock Rd', city: 'London', country: 'GB', pincode: 'E16 2QU', timezone: 'Europe/London', geoLat: 51.5072, geoLng: 0.0277, showPrices: false, enabledPaymentMethods: ['CARD'] },
  });
  expect(warehouse, '[+] create a warehouse', 201);
  const warehouseId = data(warehouse).id;
  truthy('[+] a second store is not the default', data(warehouse).isDefault === false && data(warehouse).type === 'WAREHOUSE', data(warehouse));
  expect(call('POST', '/api/tenant-svc/admin/stores', { token: t, body: { name: 'Clash', code, timezone: 'Europe/London' } }), '[-] create store: code taken', 409);
  expect(call('POST', '/api/tenant-svc/admin/stores', { token: rival.owner.token, body: { name: 'Same code, other tenant', code, timezone: 'Europe/London' } }), '[+] store codes are per tenant', 201);

  const page1 = call('GET', '/api/tenant-svc/admin/stores?limit=1', { token: t });
  expect(page1, '[+] list stores, one per page', 200);
  const cursor = nextCursor(page1);
  truthy('[+] a first page of one has a cursor', (data(page1) || []).length === 1 && cursor, { rows: data(page1), cursor });
  const page2 = call('GET', `/api/tenant-svc/admin/stores?limit=1&after=${encodeURIComponent(cursor || '')}`, { token: t });
  expect(page2, '[+] list stores, next page', 200);
  truthy('[+] pages do not repeat a store', (data(page2) || []).length === 1 && data(page2)[0].id !== data(page1)[0].id, data(page2));
  // Cursor.clampLimit: below 1 means the default page (20), above 100 means 100.
  const clamped = call('GET', '/api/tenant-svc/admin/stores?limit=0', { token: t });
  expect(clamped, '[-] list stores: limit below 1 falls back to the default page', 200);
  truthy('[-] ...which holds both stores', (data(clamped) || []).length === 2, data(clamped));
  expect(call('GET', '/api/tenant-svc/admin/stores?after=garbage', { token: t }), '[-] list stores: malformed cursor', 400);

  expect(call('GET', `/api/tenant-svc/admin/stores/${warehouseId}`, { token: t }), '[+] get store', 200);
  expect(call('GET', `/api/tenant-svc/admin/stores/${warehouseId}`, { token: rival.owner.token }), "[-] a rival cannot read our store", 404);
  // A UUID path parameter that does not convert is a JAX-RS 404, never a 5xx.
  expect(call('GET', '/api/tenant-svc/admin/stores/not-a-uuid', { token: t }), '[-] get store: id is not a UUID', 400, 'INVALID_UUID');
  expect(call('PUT', `/api/tenant-svc/admin/stores/${warehouseId}`, { token: t, body: { name: 'Main Warehouse', showPrices: true } }), '[+] update store', 200);
  // SJ-D54: a store's zone is required, must be real, and an update that leaves it out keeps it.
  truthy('[+] an update that leaves the zone out keeps it, not UTC', data(call('GET', `/api/tenant-svc/admin/stores/${warehouseId}`, { token: t })).timezone === 'Europe/London');
  expect(call('POST', '/api/tenant-svc/admin/stores', { token: t, body: { name: 'No zone', code: `NZ-${uniq()}`.slice(0, 24) } }), '[-] create store: a time zone is required', 400, 'STORE_TIMEZONE_REQUIRED');
  expect(call('POST', '/api/tenant-svc/admin/stores', { token: t, body: { name: 'Bad zone', code: `BZ-${uniq()}`.slice(0, 24), timezone: 'Mars/Olympus' } }), '[-] create store: the zone must be a real one', 400, 'STORE_TIMEZONE_INVALID');
  expect(call('PUT', `/api/tenant-svc/admin/stores/${warehouseId}`, { token: t, body: { name: 'Main Warehouse', timezone: 'UTC+01:00' } }), '[-] update store: an offset is not a zone', 400, 'STORE_TIMEZONE_INVALID');
  expect(call('POST', '/api/tenant-svc/onboarding', { token: registerBusiness('zone-founder').token, body: { businessName: `No Zone ${uniq()}`, country: 'AU', currency: 'AUD', storeName: 'Sydney', storeCode: `SYD-${uniq()}`.slice(0, 24) } }), '[-] one-shot onboarding: the first store needs a zone', 400, 'STORE_TIMEZONE_REQUIRED');
  expect(call('PUT', `/api/tenant-svc/admin/stores/${UNKNOWN}`, { token: t, body: { name: 'Ghost' } }), '[-] update unknown store', 404);
  expect(call('PATCH', `/api/tenant-svc/admin/stores/${warehouseId}/status`, { token: t, body: { status: 'CLOSED' } }), '[+] close a store', 200);
  expect(call('PATCH', `/api/tenant-svc/admin/stores/${warehouseId}/status`, { token: t, body: { status: 'ACTIVE' } }), '[+] reopen a store', 200);
  expect(call('PATCH', `/api/tenant-svc/admin/stores/${warehouseId}/status`, { token: t, body: { status: 'DEMOLISHED' } }), '[-] store status must be known', 400, 'INVALID_STATUS');

  // ── zones ───────────────────────────────────────────────────────────────────
  const zones = call('GET', `/api/tenant-svc/admin/stores/${warehouseId}/zones`, { token: t });
  expect(zones, '[+] list zones', 200);
  truthy('[+] every store starts with a DEFAULT zone', (data(zones) || []).some((z) => z.code === 'DEFAULT'), data(zones));
  const cold = call('POST', `/api/tenant-svc/admin/stores/${warehouseId}/zones`, { token: t, body: { name: 'Cold room', code: 'COLD', type: 'COLD_ROOM' } });
  expect(cold, '[+] add a zone', 201);
  expect(call('POST', `/api/tenant-svc/admin/stores/${warehouseId}/zones`, { token: t, body: { name: 'Cold again', code: 'COLD' } }), '[-] zone code taken in this store', 409);
  expect(call('POST', `/api/tenant-svc/admin/stores/${warehouseId}/zones`, { token: t, body: { code: 'NONAME' } }), '[-] add zone: name required', 400);
  expect(call('POST', `/api/tenant-svc/admin/stores/${warehouseId}/zones`, { token: rival.owner.token, body: { name: 'Squat', code: 'SQUAT' } }), "[-] a rival cannot add zones to our store", 404);
  expect(call('GET', `/api/tenant-svc/admin/stores/${warehouseId}/zones/${data(cold).id}`, { token: t }), '[+] get zone', 200);
  expect(call('PUT', `/api/tenant-svc/admin/stores/${warehouseId}/zones/${data(cold).id}`, { token: t, body: { name: 'Freezer', code: 'COLD', type: 'COLD_ROOM' } }), '[+] update zone', 200);
  expect(call('PATCH', `/api/tenant-svc/admin/stores/${warehouseId}/zones/${data(cold).id}/status`, { token: t, body: { status: 'INACTIVE' } }), '[+] deactivate zone', 200);
  expect(call('GET', `/api/tenant-svc/admin/stores/${warehouseId}/zones/${UNKNOWN}`, { token: t }), '[-] get unknown zone', 404);

  // ── delivery areas and fulfilment routing ──────────────────────────────────
  const pincode = `K6${uniq()}`.slice(0, 12);
  const area = call('POST', `/api/tenant-svc/admin/stores/${warehouseId}/delivery-areas`, { token: t, body: { pincode, priority: 1 } });
  expect(area, '[+] map a delivery area', 201);
  expect(call('POST', `/api/tenant-svc/admin/stores/${warehouseId}/delivery-areas`, { token: t, body: { pincode, priority: 2 } }), '[-] delivery area already mapped', 409);
  expect(call('POST', `/api/tenant-svc/admin/stores/${warehouseId}/delivery-areas`, { token: t, body: { priority: 1 } }), '[-] delivery area: pincode required', 400);
  expect(call('GET', `/api/tenant-svc/admin/stores/${warehouseId}/delivery-areas`, { token: t }), '[+] list delivery areas', 200);
  const routed = call('GET', `/api/tenant-svc/fulfilment/resolve?pincode=${encodeURIComponent(pincode)}`, { storefront: tenant.tenantId });
  expect(routed, '[+] a mapped pincode resolves', 200);
  truthy('[+] ...to the store that delivers there', data(routed).storeId === warehouseId, data(routed));
  expect(call('GET', '/api/tenant-svc/fulfilment/resolve', { storefront: tenant.tenantId }), '[-] resolve: pincode required', 400);
  expect(call('DELETE', `/api/tenant-svc/admin/stores/${warehouseId}/delivery-areas/${data(area).id}`, { token: t }), '[+] unmap a delivery area', [200, 204]);

  // ── staff ───────────────────────────────────────────────────────────────────
  // A login made in the business: the only kind an assignment binds (a shopper's sign-up is never staff).
  const clerk = provisionStaff(tenant, 'tenant-clerk');
  expect(call('POST', '/api/tenant-svc/admin/staff', { token: t, body: { userId: clerk.userId, storeId } }), '[-] assign staff: role required', 400);
  expect(call('POST', '/api/tenant-svc/admin/staff', { token: t, body: { userId: clerk.userId, storeId: UNKNOWN, role: 'CASHIER' } }), '[-] assign staff: unknown store', 404);
  expect(
    call('POST', '/api/tenant-svc/admin/staff', { token: t, body: { userId: clerk.userId, storeId: rival.stores[0].id, role: 'CASHIER' } }),
    "[-] assign staff to a rival's store",
    404
  );
  expect(call('POST', '/api/tenant-svc/admin/staff', { token: t, body: { userId: clerk.userId, storeId, role: 'CASHIER' } }), '[+] assign a cashier', 201);
  const staff = call('GET', '/api/tenant-svc/admin/staff', { token: t });
  expect(staff, '[+] list staff', 200);
  truthy('[+] the cashier is listed', (data(staff) || []).some((s) => s.userId === clerk.userId), data(staff));
  expect(call('DELETE', `/api/tenant-svc/admin/staff/${clerk.userId}`, { token: t }), '[-] remove staff: which store', 400, 'MISSING_STORE');
  expect(call('DELETE', `/api/tenant-svc/admin/staff/${clerk.userId}?store=${storeId}`, { token: t }), '[+] remove staff from a store', [200, 204]);
  truthy('[+] ...and they are no longer listed', !(data(call('GET', '/api/tenant-svc/admin/staff', { token: t })) || []).some((s) => s.userId === clerk.userId && s.storeId === storeId));
  expect(call('POST', '/api/tenant-svc/admin/staff', { token: founder.token, body: { userId: founder.userId, storeId, role: 'OWNER' } }), '[-] a customer cannot assign staff', 403);

  // ── inventory settings ──────────────────────────────────────────────────────
  expect(call('GET', '/api/tenant-svc/admin/inventory-config', { token: t }), '[-] inventory settings before any are saved', 404);
  const settings = { lotControlEnabled: true, serialControlEnabled: false, gradeControlEnabled: true, expiryTrackingEnabled: true, costingMethod: 'FIFO', defaultUom: 'EA', reorderAlertEnabled: true, autoReserveOnOrder: false };
  expect(call('PUT', '/api/tenant-svc/admin/inventory-config', { token: t, body: settings }), '[+] save inventory settings', 200);
  const saved = call('GET', '/api/tenant-svc/admin/inventory-config', { token: t });
  expect(saved, '[+] read inventory settings', 200);
  truthy('[+] settings read back as saved', data(saved).costingMethod === 'FIFO' && data(saved).lotControlEnabled === true, data(saved));
  expect(call('PUT', '/api/tenant-svc/admin/inventory-config', { token: t, body: { costingMethod: 'AVERAGE' } }), '[+] change one setting', 200);
  const partial = data(call('GET', '/api/tenant-svc/admin/inventory-config', { token: t }));
  truthy('[+] other settings keep their values', partial.costingMethod === 'AVERAGE' && partial.lotControlEnabled === true, partial);
  expect(call('PUT', '/api/tenant-svc/admin/inventory-config', { token: t, body: { costingMethod: 'LIFO' } }), '[-] costing method must be FIFO, AVERAGE or STANDARD', 400);
  expect(call('GET', '/api/tenant-svc/admin/inventory-config', { token: rival.owner.token }), "[-] a rival does not get our settings", 404);

  // ── storefront view ─────────────────────────────────────────────────────────
  const shop = { storefront: tenant.tenantId };
  const cfg = call('GET', `/api/tenant-svc/storefront/config?store=${warehouseId}`, shop);
  expect(cfg, '[+] storefront store config', 200);
  truthy('[+] config reflects the update', data(cfg).storeId === warehouseId && data(cfg).showPrices === true, data(cfg));
  expect(call('GET', '/api/tenant-svc/storefront/config', shop), '[-] storefront config: store required', 400, 'STORE_REQUIRED');
  expect(call('GET', `/api/tenant-svc/storefront/config?store=${warehouseId}`, { storefront: rival.tenantId }), "[-] another shop cannot show our store", 404);
  const active = call('GET', '/api/tenant-svc/storefront/active', shop);
  expect(active, '[+] storefront: may the tenant trade', 200);
  truthy('[+] ...yes', data(active).active === true, data(active));
  expect(call('GET', '/api/tenant-svc/storefront/stores', shop), '[+] storefront store list', 200);

  // ── platform console ────────────────────────────────────────────────────────
  expect(call('GET', '/api/tenant-svc/platform/tenants', { token: t }), '[-] an owner cannot list every tenant', 403);
  const all = call('GET', '/api/tenant-svc/platform/tenants?limit=100', { token: admin.token });
  expect(all, '[+] platform admin lists tenants', 200);
  truthy('[+] ...one page at a time', (data(all) || []).length > 0 && (data(all) || []).length <= 100, (data(all) || []).length);
  expect(call('POST', `/api/tenant-svc/platform/tenants/republish-currency?tenantId=${tenant.tenantId}`, { token: t }), '[-] an owner cannot republish currencies', 403);
  expect(call('POST', `/api/tenant-svc/platform/tenants/republish-currency?tenantId=${tenant.tenantId}`, { token: admin.token }), '[+] platform admin republishes a currency', 200);
  expect(call('POST', '/api/tenant-svc/platform/tenants/republish-currency?tenantId=nope', { token: admin.token }), '[-] republish: tenant id is not a UUID', 400);
}
