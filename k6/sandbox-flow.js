// A business's sandbox (22.8), through the gateway: the owner makes one — a tenant of its own,
// marked SANDBOX and naming the live business, on the SANDBOX plan with the live default store
// copied — one at a time, never by a manager; the platform sees it for what it is and cannot put
// the live business on the sandbox plan; the owner trades its token for one in the sandbox (an owner
// there, amr [sandbox], no refresh) and mints a sqk_test_ key that acts in the sandbox alone; what the
// key creates in the sandbox is invisible to the live business; a webhook registered in the sandbox
// is delivered and an in-app message is logged; removed, the sandbox is switched off with the reason,
// its key stops within seconds and its token is not renewed or obtained again, and another can be made.
//
//   k6/run.sh sandbox-flow
import { Counter } from 'k6/metrics';
import {
  ALL_CHECKS_PASS,
  call,
  claims,
  data,
  errorCode,
  expect,
  must,
  newId,
  onboardTenant,
  platformAdmin,
  poll,
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

const SANDBOX = '/api/tenant-svc/admin/tenant/sandbox';
const TOKEN = '/api/iam-svc/auth/sandbox/token';
const KEYS = '/api/iam-svc/auth/admin/api-keys';
const SINK = __ENV.WEBHOOK_SINK || 'http://webhook-sink:8080';

export function setup() {
  const tenant = onboardTenant('sandbox');
  return {
    tenant,
    store: tenant.stores[0],
    manager: staffUser(tenant, 'MANAGER', [tenant.stores[0].id]),
    platform: platformAdmin(),
  };
}

export default function ({ tenant, store, manager, platform }) {
  const owner = tenant.owner.token;
  const live = tenant.tenantId;

  // ── made by the owner, one at a time ─────────────────────────────────────────────────────────
  expect(call('GET', SANDBOX, { token: owner }), '[-] a business starts with no sandbox', 404, 'SANDBOX_NOT_FOUND');
  expect(call('POST', SANDBOX, { token: manager.token }), '[-] a manager cannot make one', 403);
  const made = call('POST', SANDBOX, { token: owner });
  expect(made, '[+] the owner makes the sandbox', 201);
  const sandbox = data(made);
  truthy('[+] a tenant of its own, marked, naming the live business, on its own plan', sandbox.id && sandbox.id !== live && sandbox.mode === 'SANDBOX' && sandbox.sandboxOf === live && sandbox.status === 'ACTIVE' && String(sandbox.name).endsWith('(sandbox)') && sandbox.country === tenant.country && sandbox.currency === tenant.currency, sandbox);
  expect(call('POST', SANDBOX, { token: owner }), '[-] one at a time', 409, 'SANDBOX_EXISTS');
  truthy('[+] a manager reads it', (data(call('GET', SANDBOX, { token: manager.token })) || {}).id === sandbox.id, 'manager read');
  truthy('[+] the live business still says it is live', (data(call('GET', '/api/tenant-svc/admin/tenant', { token: owner })) || {}).mode === 'LIVE', 'live profile');

  // ── the platform sees it for what it is ──────────────────────────────────────────────────────
  const seen = data(call('GET', `/api/tenant-svc/platform/tenants/${sandbox.id}`, { token: platform.token })) || {};
  truthy('[+] the platform sees the sandbox for what it is', seen.mode === 'SANDBOX' && seen.sandboxOf === live, seen);
  const plans = data(call('GET', '/api/tenant-svc/platform/plans', { token: platform.token })) || [];
  const sandboxPlan = plans.find((p) => p.code === 'SANDBOX') || {};
  truthy('[+] the SANDBOX plan exists, sold to nobody', sandboxPlan.id && sandboxPlan.status === 'ACTIVE' && sandboxPlan.isPublic === false && sandboxPlan.isDefault === false, sandboxPlan);
  expect(call('PUT', `/api/tenant-svc/platform/tenants/${live}/plan`, { token: platform.token, body: { planId: sandboxPlan.id } }), '[-] the live business cannot be put on it', 409, 'PLAN_SANDBOX_ONLY');
  truthy('[-] nor is it on the price list', !(data(call('GET', '/api/tenant-svc/plans')) || []).some((p) => p.code === 'SANDBOX'), 'price list');

  // ── into the sandbox ─────────────────────────────────────────────────────────────────────────
  let entered = null;
  poll(60, () => { entered = call('POST', TOKEN, { token: owner }); return entered.status === 200; });
  expect(entered, '[+] the owner trades its token for one in the sandbox', 200);
  const inside = data(entered).accessToken;
  const c = claims(inside || 'x.x.x') || {};
  truthy('[+] an owner there, marked sandbox, no refresh token', c.tenant === sandbox.id && (c.roles || []).includes('OWNER') && (c.amr || []).includes('sandbox') && !data(entered).refreshToken && data(entered).tenantId === sandbox.id, { claims: c, answer: data(entered) });
  expect(call('POST', TOKEN, { token: manager.token }), '[-] a manager is not let in', 403);
  expect(call('POST', TOKEN, { token: inside }), '[-] there is no sandbox of a sandbox', 409, 'SANDBOX_NESTED');
  const insideProfile = data(call('GET', '/api/tenant-svc/admin/tenant', { token: inside })) || {};
  truthy('[+] inside, the profile is the sandbox', insideProfile.id === sandbox.id && insideProfile.mode === 'SANDBOX', insideProfile);
  truthy('[+] ...on the SANDBOX plan', ((data(call('GET', '/api/tenant-svc/admin/tenant/plan', { token: inside })) || {}).plan || {}).code === 'SANDBOX', 'plan');
  const copied = data(call('GET', '/api/tenant-svc/admin/stores', { token: inside })) || [];
  truthy('[+] ...with the live default store copied in', copied.length === 1 && copied[0].code === store.code && copied[0].id !== store.id && copied[0].isDefault === true, copied);
  truthy('[+] ...and the sandbox reads as itself', (data(call('GET', SANDBOX, { token: inside })) || {}).id === sandbox.id, 'self');

  // ── a sandbox key ────────────────────────────────────────────────────────────────────────────
  const minted = call('POST', KEYS, { token: owner, body: { name: 'ERP rehearsal', role: 'MANAGER', sandbox: true } });
  expect(minted, '[+] the owner mints a sandbox key from the live business', 201);
  const key = data(minted).key;
  truthy('[+] it starts sqk_test_, forty-nine characters, marked', typeof key === 'string' && key.startsWith('sqk_test_') && key.length === 49 && data(minted).sandbox === true && data(minted).prefix === key.slice(0, 12), data(minted).prefix);
  const fromInside = call('POST', KEYS, { token: inside, body: { name: 'From inside', role: 'STOREKEEPER' } });
  expect(fromInside, '[+] a key minted inside the sandbox', 201);
  truthy('[+] ...is a sandbox key whether or not it says so', data(fromInside).sandbox === true && String(data(fromInside).key).startsWith('sqk_test_'), data(fromInside).prefix);
  const listed = (data(call('GET', KEYS, { token: owner })) || {}).items || [];
  truthy('[+] the live business lists its sandbox keys beside the live ones, marked', listed.filter((k) => k.sandbox === true).length === 2, listed.map((k) => `${k.name}:${k.sandbox}`));
  truthy('[+] ...and inside the sandbox the list is the same', (((data(call('GET', KEYS, { token: inside })) || {}).items || []).length) === listed.length, 'inside list');

  // ── the key acts in the sandbox alone ────────────────────────────────────────────────────────
  const product = call('POST', '/api/product-svc/admin/products', { token: key, body: { name: `Rehearsal ${uniq()}`, sellableOnline: true, sellablePos: true } });
  expect(product, '[+] the key creates a product in the sandbox', 201);
  const productId = data(product).id;
  expect(call('GET', `/api/product-svc/admin/products/${productId}`, { token: inside }), '[+] ...which the sandbox sees', 200);
  expect(call('GET', `/api/product-svc/admin/products/${productId}`, { token: owner }), '[-] ...and the live business does not', 404);
  const platformTry = call('GET', '/api/tenant-svc/platform/tenants', { token: key });
  truthy('[-] the key is no more the platform than any key', platformTry.status === 403, platformTry.status);

  // ── integrations rehearse in the sandbox ─────────────────────────────────────────────────────
  const hook = call('POST', '/api/notification-svc/admin/webhooks/endpoints', { token: inside, body: { url: `${SINK}/hooks/sandbox-${uniq()}`, description: 'Rehearsal ERP', events: ['ProductCreated', 'OrderPlaced'] } });
  expect(hook, '[+] a webhook is registered in the sandbox', 201);
  const ping = must(call('POST', `/api/notification-svc/admin/webhooks/endpoints/${data(hook).id}/ping`, { token: inside }), 202, 'ping');
  let delivery = {};
  poll(45, () => { delivery = data(call('GET', `/api/notification-svc/admin/webhooks/deliveries/${ping.deliveryId}`, { token: inside })) || {}; return delivery.status === 'DELIVERED'; });
  truthy('[+] ...and is delivered: a sandbox rehearses webhooks for real', delivery.status === 'DELIVERED', delivery);
  const recipient = `sandbox-${uniq()}@k6.storeql.test`;
  expect(call('POST', '/api/notification-svc/notifications/send', { token: inside, body: { recipient, subject: 'Rehearsal', body: 'Nothing real.', type: 'MANUAL', eventId: newId(), channel: 'APP' } }), '[+] an in-app message inside the sandbox is accepted', 202);
  let log = [];
  poll(30, () => { log = data(call('GET', `/api/notification-svc/admin/notifications?recipient=${encodeURIComponent(recipient)}`, { token: inside })) || []; return log.length > 0; });
  truthy('[+] ...and logged there: what would have gone, and to whom', log.length === 1 && log[0].channel === 'APP', log);
  truthy('[-] the live business sees no such message', ((data(call('GET', `/api/notification-svc/admin/notifications?recipient=${encodeURIComponent(recipient)}`, { token: owner })) || []).length) === 0, 'live log');

  // ── removed ──────────────────────────────────────────────────────────────────────────────────
  expect(call('DELETE', SANDBOX, { token: manager.token }), '[-] a manager cannot remove it', 403);
  expect(call('DELETE', SANDBOX, { token: inside }), '[-] nor is it removed from inside', 409, 'SANDBOX_NESTED');
  const gone = call('DELETE', SANDBOX, { token: owner });
  expect(gone, '[+] the owner removes it', 200);
  truthy('[+] switched off, with the reason', data(gone).id === sandbox.id && data(gone).status === 'INACTIVE' && data(gone).deactivatedReason === 'SANDBOX_DELETED', data(gone));
  expect(call('GET', SANDBOX, { token: owner }), '[+] ...and there is none now', 404, 'SANDBOX_NOT_FOUND');
  let keyStopped = null;
  poll(60, () => { keyStopped = call('GET', '/api/product-svc/admin/products?limit=1', { token: key }); return keyStopped.status !== 200; });
  truthy('[+] the sandbox key stops within seconds', keyStopped && (keyStopped.status === 401 || keyStopped.status === 403), keyStopped && keyStopped.status);
  // A token already issued lasts out its fifteen minutes, as a suspended business's staff tokens do;
  // it cannot be renewed (no refresh token) or obtained again (below), and what it reads says so.
  const insideNow = call('GET', '/api/tenant-svc/admin/tenant', { token: inside });
  truthy('[+] ...and from inside, the sandbox reads as switched off for what is left of its token', insideNow.status === 200 && data(insideNow).status === 'INACTIVE' && data(insideNow).deactivatedReason === 'SANDBOX_DELETED', data(insideNow));
  // iam-svc learns the sandbox is gone from tenant-svc's event, on its own consumer: the key above
  // can stop first (revoked with the sandbox), so wait for the event as the key check waits.
  let entry = null;
  poll(60, () => { entry = call('POST', TOKEN, { token: owner }); return entry.status !== 200; });
  expect(entry, '[-] there is nothing to enter', 404, 'SANDBOX_NOT_FOUND');

  // ── and another ──────────────────────────────────────────────────────────────────────────────
  const again = call('POST', SANDBOX, { token: owner });
  expect(again, '[+] another can be made at once', 201);
  truthy('[+] ...a fresh one', data(again).id && data(again).id !== sandbox.id && data(again).status === 'ACTIVE', data(again).id);
  let reentered = null;
  poll(60, () => { reentered = call('POST', TOKEN, { token: owner }); return reentered.status === 200 && data(reentered).tenantId === data(again).id; });
  truthy('[+] ...and it is the one entered now', reentered && reentered.status === 200 && data(reentered).tenantId === data(again).id, reentered && data(reentered));
  expect(call('GET', `/api/product-svc/admin/products/${productId}`, { token: data(reentered).accessToken }), '[-] the old sandbox\'s product is not in the new one', 404);

  completed.add(1);
}
