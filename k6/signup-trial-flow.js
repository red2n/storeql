// Self-serve signup and trials (21.13), through the gateway: a prospect reads the price list with no
// login — the plans on sale, what they cost, what they include, how long the trial is; a draft and a
// plan sold by hand are not on it. A business chooses a plan as it signs up and starts on that plan's
// trial; a plan not on sale is refused before the business exists; a login owns one business, so a
// second signup on it is refused — a second trial as much as a second shop. Three days before the
// trial ends the business is emailed the day and the price; the day it ends it is emailed the first
// invoice with a link that pays it, with no sign-in; each once, however often the billing run runs.
//
//   k6/run.sh signup-trial-flow
import { Counter } from 'k6/metrics';
import {
  ALL_CHECKS_PASS,
  call,
  data,
  expect,
  must,
  platformAdmin,
  poll,
  register,
  signInUntil,
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
const BILLING = '/api/tenant-svc/platform/billing';
const MINE = '/api/tenant-svc/admin/tenant/billing';
const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
const written = (iso) => { const [y, m, d] = iso.split('-'); return `${Number(d)} ${MONTHS[Number(m) - 1]} ${y}`; };
const day = (iso, n) => { const d = new Date(`${iso}T00:00:00Z`); d.setUTCDate(d.getUTCDate() + n); return d.toISOString().slice(0, 10); };

export function setup() {
  return { admin: platformAdmin() };
}

export default function ({ admin }) {
  const tag = uniq().toUpperCase().slice(0, 8);
  const root = admin.token;
  const plan = (method, path, body) => call(method, `${PLANS}${path || ''}`, { token: root, body });
  const write = (code, trialDays, isPublic) => must(plan('POST', '', { code, name: `${code} plan`, description: 'sold to shops', billingInterval: 'MONTH', trialDays, isPublic, sortOrder: 1 }), 201, `write ${code}`).id;
  const sell = (id) => {
    must(plan('POST', `/${id}/prices`, { currency: 'GBP', amount: 49 }), 200, 'price it');
    must(plan('PUT', `/${id}/includes`, { grants: [{ key: 'stores.max', limitValue: 2 }, { key: 'feature.storefront', enabled: true }] }), 200, 'what it includes');
    must(plan('POST', `/${id}/activate`), 200, 'sell it');
    return id;
  };
  must(call('PUT', `${BILLING}/profile`, { token: root, body: { legalName: `StoreQL Trials ${tag} Ltd`, addressLine1: '1 Quay Street', city: 'Dublin', country: 'IE', vatNumber: `IE${tag}X`, invoicePrefix: 'INV', paymentTermsDays: 7, taxRate: '0.2300' } }), 200, 'the platform says who it is');

  // ── the price list, read by a prospect with no login ────────────────────────────────────────────
  const onSale = sell(write(`TRIAL-${tag}`, 14, true));
  const drafted = write(`DRAFT-${tag}`, 14, true);
  const byHand = sell(write(`HAND-${tag}`, 0, false));
  must(plan('POST', `/${onSale}/default`), 200, 'the default plan');
  const list = call('GET', '/api/tenant-svc/plans');
  expect(list, '[+] a prospect reads the plans on sale with no login', 200);
  const plans = Array.isArray(data(list)) ? data(list) : [];
  const listed = plans.find((p) => p.id === onSale);
  truthy('[+] with the price, what it includes, the trial and which is the default', !!listed && listed.trialDays === 14 && listed.isDefault === true && (listed.prices || []).some((p) => p.currency === 'GBP' && Number(p.amount) === 49) && (listed.includes || []).some((g) => g.key === 'stores.max'), listed);
  truthy('[-] a draft is not for sale', !plans.some((p) => p.id === drafted), plans.map((p) => p.code));
  truthy('[-] a plan sold by hand is not on the list', !plans.some((p) => p.id === byHand), plans.map((p) => p.code));
  expect(call('GET', `${PLANS}`), '[-] the platform\'s own price list, with its drafts, is not public', 401);

  // ── choosing a plan at signup ───────────────────────────────────────────────────────────────────
  const owner = register(`trial-${tag}-owner`);
  const signup = (name, planId, token = owner.token) => call('POST', '/api/tenant-svc/onboarding/tenants', { token, body: { businessName: `${name} ${uniq()}`, country: 'GB', currency: 'GBP', ...(planId ? { planId } : {}) } });
  expect(signup('Drafted', drafted), '[-] a plan not on sale cannot be chosen, and no business is left behind', 409, 'PLAN_NOT_SOLD');
  expect(signup('By hand', byHand), '[-] nor one the platform sells only by hand', 409, 'PLAN_NOT_PUBLIC');
  const created = signup('Trial shop', onSale);
  expect(created, '[+] a business signs up on the plan it chose', 201);
  const tenantId = data(created).id;
  signInUntil(owner, (c) => c.tenant === tenantId && (c.roles || []).includes('OWNER'));
  const mine = () => (data(call('GET', MINE, { token: owner.token })) || {}).subscription || {};
  const invoices = () => data(call('GET', `${MINE}/invoices?limit=50`, { token: owner.token })) || [];
  const sub = mine();
  const trialEnd = sub.trialEnd;
  truthy('[+] and starts on that plan\'s trial, ending in fourteen days, nothing owed yet', sub.planId === onSale && sub.status === 'TRIALING' && !!trialEnd && invoices().length === 0, sub);
  expect(signup('Second shop', onSale), '[-] a login owns one business: a second signup is a second trial, refused', 409, 'TENANT_ALREADY_OWNED');

  // ── the trial's end is announced, each stage once ──────────────────────────────────────────────
  const run = (asOf) => call('POST', `${BILLING}/run?asOf=${asOf}`, { token: root });
  const notices = () => (data(call('GET', `/api/notification-svc/admin/notifications?recipient=${encodeURIComponent(owner.email)}&limit=50`, { token: owner.token })) || []).filter((n) => n.type === 'TRIAL_ENDING' || n.type === 'TRIAL_ENDED');
  expect(run(day(trialEnd, -5)), '[+] five days before the end the run says nothing about it', 200);
  expect(run(day(trialEnd, -3)), '[+] three days before, it is announced', 200);
  let ending = [];
  poll(30, () => { ending = notices().filter((n) => n.type === 'TRIAL_ENDING'); return ending.length >= 1; });
  truthy('[+] to the owner\'s sign-up address, naming the day and what the plan will cost', ending.length === 1 && ending[0].subject.includes(written(trialEnd)) && ending[0].body.includes('£49.00 a month') && ending[0].body.includes(`StoreQL Trials ${tag} Ltd`), ending.map((n) => n.subject));
  expect(run(day(trialEnd, -2)), '[+] the run again says nothing more', 200);
  truthy('[+] ...one notice, not two', notices().filter((n) => n.type === 'TRIAL_ENDING').length === 1, notices().map((n) => n.type));
  expect(run(trialEnd), '[+] the day it ends, the first invoice is raised', 200);
  let ended = [];
  poll(30, () => { ended = notices().filter((n) => n.type === 'TRIAL_ENDED'); return ended.length >= 1; });
  const invoice = invoices()[0];
  truthy('[+] and announced, naming the invoice and carrying the link that pays it', ended.length === 1 && !!invoice && ended[0].subject.includes(invoice.number) && /\/#\/pay\/[A-Za-z0-9_-]+/.test(ended[0].body), { subjects: ended.map((n) => n.subject), invoice: invoice && invoice.number });
  const after = mine();
  truthy('[+] the trial is over: the subscription is active and billed', after.status === 'ACTIVE' && !after.trialEnd, after);
  const link = (ended[0].body.match(/\/#\/pay\/([A-Za-z0-9_-]+)/) || [])[1];
  const paid = call('POST', `/api/tenant-svc/billing/pay/${link}`);
  expect(paid, '[+] the link pays the first invoice with no sign-in', 200);
  truthy('[+] ...and it is settled', data(paid).status === 'PAID', data(paid));
  expect(run(day(trialEnd, 1)), '[+] the run a day later announces nothing again', 200);
  truthy('[+] ...two notices in all', notices().length === 2, notices().map((n) => n.type));

  completed.add(1);
}
