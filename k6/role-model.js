// The role model (20.10), through the gateway: a tenant defines roles of its own on the built-in
// tiers — a shift lead who is a manager but cannot void a sale or post a journal, a trainee who is
// a cashier who cannot open the drawer — assigns staff to them, and their tokens carry the
// permissions; every gated decision refuses the narrowed role by name and admits the plain one; a
// role redefined reaches its holders at their next sign-in; a role in use cannot be deleted; a
// removed assignment leaves the login (SJ-D51); a forged permission header is stripped at the
// door; and twenty definitions of one code at once produce one role.
//
//   k6/run.sh role-model
import http from 'k6/http';
import {
  ALL_CHECKS_PASS,
  BASE,
  call,
  claims,
  data,
  expect,
  must,
  onboardTenant,
  register,
  sellableVariant,
  signInUntil,
  staffUser,
  truthy,
} from './lib/storeql.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS, setupTimeout: '3m' };

const ROLES = '/api/tenant-svc/admin/roles';
const STAFF = '/api/tenant-svc/admin/staff';

export function setup() {
  const tenant = onboardTenant('roles', { country: 'GB', currency: 'GBP' });
  const rival = onboardTenant('roles-rival', { country: 'GB', currency: 'GBP' });
  const store = tenant.stores[0];
  const storekeeper = staffUser(tenant, 'STOREKEEPER', [store.id]);
  const cashier = staffUser(tenant, 'CASHIER', [store.id]);
  const manager = staffUser(tenant, 'MANAGER', [store.id]);
  const { variantId } = sellableVariant(tenant, 'Role widget');
  return { tenant, rival, store, storekeeper, cashier, manager, variantId, lead: register('roles-lead'), trainee: register('roles-trainee') };
}

export default function ({ tenant, rival, store, storekeeper, cashier, manager, lead, trainee }) {
  const owner = tenant.owner.token;
  const list = (res) => { const d = data(res); return Array.isArray(d) ? d : []; };
  const define = (body, token = owner) => call('POST', ROLES, { token, body });
  const today = new Date().toISOString().slice(0, 10);
  const journal = (token, storeId) => call('POST', '/api/purchase-svc/nominal-ledger/journals', { token, body: { entryDate: today, description: 'role test', ...(storeId ? { storeId } : {}), lines: [{ nominalCode: '1001', debit: '1.00' }, { nominalCode: '3000', credit: '1.00' }] } });
  const noSale = (token, headers = {}) => call('POST', '/api/order-svc/pos/no-sale', { token, headers, body: { storeId: store.id, reason: 'drawer check' } });
  const me = (token) => data(call('GET', '/api/iam-svc/auth/me', { token }));

  // ── the catalogue and the built-in roles ─────────────────────────────────────
  const catalogue = call('GET', `${ROLES}/permissions`, { token: owner });
  expect(catalogue, '[+] the permission catalogue reads', 200);
  truthy('[+] ...thirteen permissions, each with a sentence and who holds it', list(catalogue).length === 13 && list(catalogue).every((p) => p.code && p.description && Array.isArray(p.defaultFor)), list(catalogue).length);
  const roles = call('GET', ROLES, { token: owner });
  expect(roles, '[+] the roles read', 200);
  truthy('[+] ...the four built-in tiers first, a manager holding everything, a cashier the drawer', list(roles).slice(0, 4).map((r) => r.code).join(',') === 'OWNER,MANAGER,STOREKEEPER,CASHIER' && list(roles)[1].permissions.length === 13 && list(roles)[3].permissions.join() === 'purchasing.approve,till.no_sale', list(roles).map((r) => r.code));
  expect(call('GET', ROLES, { token: cashier.token }), '[-] a cashier cannot read the roles', 403);

  // ── defining roles, and every way that is wrong ──────────────────────────────
  const shiftLead = { code: 'shift_lead', name: 'Shift lead', baseTier: 'MANAGER', permissions: ['purchasing.approve', 'purchasing.invoices.decide', 'till.manage', 'stock.adjust', 'staff.manage'], description: 'Runs the floor; the owner voids and posts' };
  const defined = define(shiftLead);
  expect(defined, '[+] a shift lead is defined on the manager tier', 201);
  truthy('[+] ...upper-cased, custom, holding what was asked and no more', data(defined).code === 'SHIFT_LEAD' && data(defined).custom === true && data(defined).permissions.length === 5 && !data(defined).permissions.includes('sales.void'), data(defined));
  expect(define({ code: 'TRAINEE', name: 'Trainee cashier', baseTier: 'cashier', permissions: [] }), '[+] a trainee is defined on the cashier tier, holding nothing', 201);
  expect(define(shiftLead), '[-] the same code again', 409, 'ROLE_ALREADY_EXISTS');
  expect(define({ code: 'shift lead', name: 'x', baseTier: 'MANAGER', permissions: [] }), '[-] a code with a space', 400, 'ROLE_CODE_INVALID');
  expect(define({ code: 'MANAGER', name: 'x', baseTier: 'MANAGER', permissions: [] }), '[-] a built-in name', 400, 'ROLE_CODE_RESERVED');
  expect(define({ code: 'GOD', name: 'x', baseTier: 'OWNER', permissions: [] }), '[-] standing on the owner', 400, 'ROLE_TIER_INVALID');
  expect(define({ code: 'GOD', name: 'x', baseTier: 'MANAGER', permissions: ['orders.everything'] }), '[-] a permission that is not one', 400, 'ROLE_PERMISSION_UNKNOWN');
  expect(define({ code: 'SUPER_CASHIER', name: 'x', baseTier: 'CASHIER', permissions: ['sales.void'] }), '[-] a cashier who voids sales is a manager, not a cashier', 400, 'ROLE_PERMISSION_OUTSIDE_TIER');
  expect(define({ code: 'X_ROLE', name: 'x', baseTier: 'MANAGER' }), '[-] no permissions list at all', 400);
  expect(define({ code: 'X_ROLE', name: 'x', baseTier: 'MANAGER', permissions: Array.from({ length: 51 }, () => 'sales.void') }), '[-] fifty-one permissions', 400);
  expect(define({ code: 'X_ROLE', name: 'x', baseTier: 'MANAGER', permissions: [] }, storekeeper.token), '[-] a storekeeper cannot define one', 403);
  expect(define({ code: 'X_ROLE', name: 'x', baseTier: 'MANAGER', permissions: [] }, cashier.token), '[-] nor a cashier', 403);
  expect(call('GET', `${ROLES}/SHIFT_LEAD`, { token: rival.owner.token }), '[-] the rival shop sees no such role', 404);
  truthy('[-] ...and its own list has none of ours', !list(call('GET', ROLES, { token: rival.owner.token })).some((r) => r.code === 'SHIFT_LEAD'));

  // ── staff take the roles and their tokens carry the permissions ─────────────
  expect(call('POST', STAFF, { token: owner, body: { userId: lead.userId, storeId: store.id, role: 'shift_lead' } }), '[+] a person is assigned as shift lead', 201);
  expect(call('POST', STAFF, { token: owner, body: { userId: trainee.userId, storeId: store.id, role: 'TRAINEE' } }), '[+] another as trainee', 201);
  expect(call('POST', STAFF, { token: owner, body: { userId: trainee.userId, storeId: store.id, role: 'HEAD_CHEF' } }), '[-] a role that is neither built in nor defined', 400, 'STAFF_ROLE_UNKNOWN');
  truthy('[+] the staff list shows the role and the tier it stands on', list(call('GET', STAFF, { token: owner })).some((s) => s.userId === lead.userId && s.role === 'SHIFT_LEAD' && s.baseTier === 'MANAGER'));
  const leadClaims = signInUntil(lead, (c) => c.tenant === tenant.tenantId && (c.roles || []).includes('MANAGER') && Array.isArray(c.perms));
  truthy('[+] the shift lead signs in as a MANAGER whose token carries exactly the five', leadClaims.perms.length === 5 && leadClaims.perms.includes('staff.manage') && !leadClaims.perms.includes('sales.void'), leadClaims.perms);
  const traineeClaims = signInUntil(trainee, (c) => c.tenant === tenant.tenantId && (c.roles || []).includes('CASHIER') && Array.isArray(c.perms));
  truthy('[+] the trainee signs in as a CASHIER whose token carries nothing', traineeClaims.perms.length === 0, traineeClaims.perms);
  truthy('[+] /auth/me shows the shift lead the same five', (me(lead.token).permissions || []).length === 5);
  truthy('[+] ...and the plain manager everything, from the tier', (me(manager.token).permissions || []).length === 13);
  truthy('[+] ...and the owner everything, never narrowed', (me(owner).permissions || []).length === 13);

  // ── every gated decision, refused by name and admitted by tier ───────────────
  expect(journal(lead.token), '[-] the shift lead cannot post a journal', 403, 'PERMISSION_DENIED');
  expect(journal(manager.token), '[+] a plain manager can', 201);
  expect(call('POST', `/api/order-svc/orders/${lead.userId}/void`, { token: lead.token, body: { reason: 'x' } }), '[-] the shift lead cannot void a sale', 403, 'PERMISSION_DENIED');
  expect(call('POST', ROLES, { token: lead.token, body: { code: 'JUNIOR', name: 'Junior', baseTier: 'CASHIER', permissions: [] } }), '[+] but may define a role: staff.manage was kept', 201);
  expect(noSale(trainee.token), '[-] the trainee cannot open the drawer', 403, 'PERMISSION_DENIED');
  expect(noSale(cashier.token), '[+] a plain cashier can', 201);
  expect(noSale(trainee.token, { 'X-Permissions': 'till.no_sale' }), '[-] a forged permission header is stripped at the gateway', 403, 'PERMISSION_DENIED');
  expect(call('POST', '/api/inventory-svc/admin/inventory/adjust', { token: lead.token, body: { storeId: store.id, variantId: lead.userId, delta: -1, reason: 'DAMAGED' } }), '[+] the shift lead may adjust stock: not this refusal', [200, 201, 404, 409, 422]);
  const adjust = call('POST', '/api/inventory-svc/admin/inventory/adjust', { token: lead.token, body: { storeId: store.id, variantId: lead.userId, delta: -1, reason: 'DAMAGED' } });
  truthy('[+] ...and whatever the route said, it was not PERMISSION_DENIED', !String(adjust.body).includes('PERMISSION_DENIED'));

  // ── a role redefined reaches its holders at their next sign-in ───────────────
  const redefined = call('PUT', `${ROLES}/SHIFT_LEAD`, { token: owner, body: { name: 'Shift lead', permissions: ['purchasing.approve', 'finance.journal'], description: '' } });
  expect(redefined, '[+] the shift lead is redefined: journals in, staff out', 200);
  truthy('[+] ...as read back', data(redefined).permissions.join() === 'finance.journal,purchasing.approve', data(redefined));
  expect(call('PUT', `${ROLES}/SHIFT_LEAD`, { token: owner, body: { name: 'x', permissions: ['sales.void'], description: '' } }), '[+] widening within the tier is allowed too', 200);
  expect(call('PUT', `${ROLES}/SHIFT_LEAD`, { token: owner, body: { name: 'Shift lead', permissions: ['purchasing.approve', 'finance.journal'], description: '' } }), '[+] and set back', 200);
  expect(call('PUT', `${ROLES}/TRAINEE`, { token: owner, body: { name: 'x', permissions: ['sales.void'], description: '' } }), '[-] a trainee cannot be widened past the cashier', 400, 'ROLE_PERMISSION_OUTSIDE_TIER');
  expect(call('PUT', `${ROLES}/NOBODY`, { token: owner, body: { name: 'x', permissions: [], description: '' } }), '[-] a role that does not exist', 404, 'ROLE_NOT_FOUND');
  expect(journal(lead.token), '[-] the old token still cannot post: a token carries what it was minted with', 403, 'PERMISSION_DENIED');
  const again = signInUntil(lead, (c) => Array.isArray(c.perms) && c.perms.includes('finance.journal'));
  truthy('[+] the next sign-in carries the new set', again.perms.join() === 'finance.journal,purchasing.approve', again.perms);
  expect(journal(lead.token), '[+] ...and the journal posts', 201);
  expect(call('POST', ROLES, { token: lead.token, body: { code: 'JUNIOR2', name: 'x', baseTier: 'CASHIER', permissions: [] } }), '[-] ...while defining a role is now refused', 403, 'PERMISSION_DENIED');

  // ── a role in use stays; a removed assignment leaves the login ───────────────
  expect(call('DELETE', `${ROLES}/TRAINEE`, { token: owner }), '[-] a role someone holds cannot be deleted', 409, 'ROLE_IN_USE');
  expect(call('DELETE', `${STAFF}/${trainee.userId}?store=${store.id}`, { token: owner }), '[+] the trainee is taken off the store', 200);
  const gone = signInUntil(trainee, (c) => !(c.roles || []).includes('CASHIER'));
  truthy('[+] ...and at the next sign-in is no longer a cashier (SJ-D51)', !(gone.roles || []).includes('CASHIER') && !gone.tenant, gone);
  expect(call('DELETE', `${ROLES}/TRAINEE`, { token: owner }), '[+] now the role can go', 204);
  expect(call('GET', `${ROLES}/TRAINEE`, { token: owner }), '[-] and is gone', 404, 'ROLE_NOT_FOUND');
  expect(call('DELETE', `${ROLES}/JUNIOR`, { token: storekeeper.token }), '[-] a storekeeper cannot delete a role', 403);
  expect(call('DELETE', `${ROLES}/JUNIOR`, { token: rival.owner.token }), '[-] nor the rival', 404);

  // ── abuse ────────────────────────────────────────────────────────────────────
  const race = http.batch(Array.from({ length: 20 }, () => ['POST', `${BASE}${ROLES}`, JSON.stringify({ code: 'RACER', name: 'Racer', baseTier: 'CASHIER', permissions: [] }), { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${owner}` }, tags: { name: 'POST /api/tenant-svc/admin/roles (race)' } }]));
  truthy('[+] twenty definitions of one code at once: one role, nineteen conflicts', race.filter((r) => r.status === 201).length === 1 && race.filter((r) => r.status === 409).length === 19, race.map((r) => r.status).join(','));
  const spam = http.batch(Array.from({ length: 30 }, (_, i) => ['POST', `${BASE}${ROLES}`, JSON.stringify({ code: `X${i}`, name: 'x', baseTier: 'MANAGER', permissions: ['orders.everything'] }), { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${owner}` }, tags: { name: 'POST /api/tenant-svc/admin/roles (spam)' } }]));
  truthy('[-] thirty roles naming a permission that is not one: every one refused', spam.every((r) => r.status === 400 || r.status === 429), spam.map((r) => r.status).join(','));
  truthy('[-] ...and none of them exists', !list(call('GET', ROLES, { token: owner })).some((r) => /^X\d+$/.test(r.code)));
  truthy('[+] the roles that stand: the tiers, SHIFT_LEAD, JUNIOR, RACER', list(call('GET', ROLES, { token: owner })).map((r) => r.code).join() === 'OWNER,MANAGER,STOREKEEPER,CASHIER,JUNIOR,RACER,SHIFT_LEAD');
}
