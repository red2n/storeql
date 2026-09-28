// Dunning and suspend-for-non-payment (21.12), through the gateway: an invoice goes past its date and
// the platform chases it on a schedule — a notice at each offset the policy names, then the service
// interrupted, then the debt given up on. A run that has not run for a week owes the business every
// notice it missed, once, because suspending a business the platform never finished telling was late is
// the opposite of what dunning is for.
//
// The check that matters most is the last one: paying up brings back a business the platform suspended
// for non-payment, and does NOT bring back one an administrator switched off. Without a recorded reason
// those two are indistinguishable and a payment would quietly overrule somebody's decision.
//
// And the way back has to work without a login, because a suspended business cannot sign in: the pay
// link in every notice is the whole capability, pays one named invoice, and a link for an invoice
// already settled opens nothing.
//
// The notice itself is real (SJ-D68): each reminder, and the suspension, is an email to the address on
// the subscription — the owner's from sign-up (SJ-D72) — naming the invoice and the sum, in the
// platform's name, carrying the link; the newest notice's link is the one that pays.
//
//   k6/run.sh dunning-flow
import { Counter } from 'k6/metrics';
import { ALL_CHECKS_PASS, call, data, expect, must, onboardTenant, platformAdmin, poll, truthy, uniq } from './lib/storeql.js';

const completed = new Counter('flow_completed');
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
  setupTimeout: '4m',
};

const PLANS = '/api/tenant-svc/platform/plans';
const BILLING = '/api/tenant-svc/platform/billing';
const DUNNING = `${BILLING}/dunning`;
const MINE = '/api/tenant-svc/admin/tenant/billing';
const PLATFORM = '/api/tenant-svc/platform';

export function setup() {
  return { admin: platformAdmin() };
}

export default function ({ admin }) {
  const tag = uniq().toUpperCase().slice(0, 8);
  const root = admin.token;
  const asRoot = (method, path, body) => call(method, `${BILLING}${path}`, { token: root, body });
  const dun = (method, path, body) => call(method, `${DUNNING}${path}`, { token: root, body });

  must(asRoot('PUT', '/profile', {
    legalName: `StoreQL Dunning ${tag} Ltd`, addressLine1: '1 Quay Street', city: 'Dublin',
    country: 'IE', vatNumber: `IE${tag}D`, invoicePrefix: 'INV', paymentTermsDays: 0, taxRate: '0.2300',
  }), 200, 'the platform bills as somebody');

  // ── the policy, and what it will not accept ─────────────────────────────────────────────────────
  // The policy is a platform singleton, so this pair reads differently on a fresh stack and on one
  // that has already run this suite. Both readings are asserted rather than only the first, because a
  // check that can pass once per stack teaches the next person to ignore a red suite.
  const policy = data(dun('GET', '/policy'));
  if (policy.set === false) {
    truthy('[+] a platform that has set no policy still chases, on the published defaults', policy.reminderDays.join(',') === '1,3,5,7' && policy.suspendAfterDays === 14 && policy.uncollectibleAfterDays === 30, policy);
    truthy('[+] and says nobody set it, rather than inventing somebody who did', policy.set === false, policy);
  } else {
    truthy('[+] a policy somebody set says so, and says when', policy.set === true && !!policy.updatedAt, policy);
    truthy('[+] and still carries a full set of stages to chase on', policy.reminderDays.length > 0 && policy.suspendAfterDays > 0 && policy.uncollectibleAfterDays >= policy.suspendAfterDays, policy);
  }
  expect(dun('PUT', '/policy', { enabled: true, reminderDays: [1, 3, 20], suspendAfterDays: 14, uncollectibleAfterDays: 30 }), '[-] the service cannot be interrupted before the last reminder has gone', 400, 'DUNNING_POLICY_INVALID');
  expect(dun('PUT', '/policy', { enabled: true, reminderDays: [1], suspendAfterDays: 20, uncollectibleAfterDays: 10 }), '[-] nor the debt given up on before the service was interrupted', 400, 'DUNNING_POLICY_INVALID');
  // No code asserted here on purpose: "at least one reminder" is a shape rule and the DTO catches it
  // on the way in, before the service's named refusal can fire. Both are wanted — the shape rule for
  // the wire and the service's own guard for programmatic callers — so the check asserts the status.
  expect(dun('PUT', '/policy', { enabled: true, reminderDays: [], suspendAfterDays: 5, uncollectibleAfterDays: 10 }), '[-] nor anything taken away with no notice at all', 400);
  const tight = dun('PUT', '/policy', { enabled: true, reminderDays: [1, 2], suspendAfterDays: 3, uncollectibleAfterDays: 6 });
  expect(tight, '[+] a platform sets its own tolerance', 200);

  // ── a business with an invoice due today, so it is overdue tomorrow ─────────────────────────────
  const plan = (() => {
    const p = must(call('POST', PLANS, { token: root, body: { code: `DUN-${tag}`, name: `DUN ${tag} plan`, billingInterval: 'MONTH', trialDays: 0, isPublic: true, sortOrder: 1 } }), 201, 'a plan');
    must(call('POST', `${PLANS}/${p.id}/prices`, { token: root, body: { currency: 'EUR', amount: 10 } }), 200, 'a price');
    must(call('POST', `${PLANS}/${p.id}/activate`, { token: root }), 200, 'on sale');
    must(call('POST', `${PLANS}/${p.id}/default`, { token: root }), 200, 'the default');
    return p.id;
  })();

  const shop = onboardTenant(`dunning-${tag}`, { country: 'IE', currency: 'EUR' });
  const owner = shop.owner.token;
  const invoices = () => data(call('GET', `${MINE}/invoices?limit=50`, { token: owner })) || [];
  let first = null;
  poll(60, () => { first = invoices()[0]; return !!first; });
  truthy('[+] it was billed for its first period, due the day it was issued', !!first && first.dueDate === first.issueDate, first);

  const day = (n) => { const d = new Date(`${first.dueDate}T00:00:00Z`); d.setUTCDate(d.getUTCDate() + n); return d.toISOString().slice(0, 10); };
  const overdue = (asOf) => (data(dun('GET', `/overdue?asOf=${asOf}&limit=100`)) || []).find((o) => o.number === first.number);
  const stages = (id) => (data(dun('GET', `/invoices/${id}/events`)) || []).map((e) => e.step);
  // The notices the business was sent, as its owner reads them on the log; the link in each.
  const notices = () => (data(call('GET', `/api/notification-svc/admin/notifications?recipient=${encodeURIComponent(shop.owner.email)}&limit=50`, { token: owner })) || []).filter((n) => (n.body || '').includes(first.number));
  const linkIn = (n) => { const m = ((n && n.body) || '').match(/\/#\/pay\/([A-Za-z0-9_-]+)/); return m ? m[1] : null; };
  const noticesOfType = (type, n) => { let got = []; poll(30, () => { got = notices().filter((x) => x.type === type); return got.length >= n; }); return got; };
  // A day as the notice writes it for an English reader: "25 September 2026".
  const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
  const written = (iso) => { const [y, m, d] = iso.split('-'); return `${Number(d)} ${MONTHS[Number(m) - 1]} ${y}`; };

  // ── one reminder at a time, and only what is earned ─────────────────────────────────────────────
  expect(dun('POST', '/run?asOf=' + day(0)), '[+] on the day it is due nothing is chased', 200);
  truthy('[+] and nothing was recorded against it', stages(first.id).length === 0, stages(first.id));

  const one = dun('POST', '/run?asOf=' + day(1));
  expect(one, '[+] a day late earns the first notice', 200);
  truthy('[+] exactly the first, not the lot', stages(first.id).join(',') === 'REMINDER_1', stages(first.id));
  const reminders = noticesOfType('INVOICE_OVERDUE', 1);
  truthy('[+] and the notice is sent — to the owner\'s sign-up address, naming the invoice and the sum (SJ-D68)', reminders.length === 1 && reminders[0].subject.includes(first.number) && reminders[0].body.includes(Number(first.totalAmount).toFixed(2)), reminders.map((n) => n.subject));
  truthy('[+] in the platform\'s name, not the business\'s own', reminders.length === 1 && reminders[0].body.includes(`— StoreQL Dunning ${tag} Ltd`), reminders[0] && reminders[0].body);
  truthy('[+] saying when the service goes if it stays unpaid', reminders.length === 1 && reminders[0].body.includes(`still unpaid on ${written(day(3))}, your service will be interrupted`), reminders[0] && reminders[0].body);
  const firstLink = linkIn(reminders[0]);
  truthy('[+] and carrying a link that pays without a sign-in', !!firstLink, reminders[0] && reminders[0].body);
  truthy('[+] and the list says how late it is and what is coming', (() => { const o = overdue(day(1)); return o && o.daysOverdue === 1 && o.stage === 'REMINDER_1' && o.nextStep === 'REMINDER_2'; })(), overdue(day(1)));

  expect(dun('POST', '/run?asOf=' + day(1)), '[+] running again is safe', 200);
  truthy('[+] and chases nothing twice', stages(first.id).join(',') === 'REMINDER_1', stages(first.id));

  // ── a run that has not run for days owes every notice that was missed ───────────────────────────
  expect(dun('POST', '/run?asOf=' + day(2)), '[+] the second notice on its day', 200);
  truthy('[+] both, in order', stages(first.id).join(',') === 'REMINDER_1,REMINDER_2', stages(first.id));
  truthy('[+] and both were sent, once each', noticesOfType('INVOICE_OVERDUE', 2).length === 2, notices().map((n) => n.type));
  expect(call('POST', `/api/tenant-svc/billing/pay/${firstLink}`), '[-] the first notice\'s link no longer pays: the newest notice is the one to act on', 404, 'PAY_LINK_INVALID');

  // ── the service is interrupted, and the storefront closes ───────────────────────────────────────
  expect(dun('POST', '/run?asOf=' + day(3)), '[+] at three days the platform is taken away', 200);
  truthy('[+] the suspension is on the file, after both notices', stages(first.id).join(',') === 'REMINDER_1,REMINDER_2,SUSPENDED', stages(first.id));
  const suspended = data(call('GET', `${PLATFORM}/tenants/${shop.tenantId}`, { token: root }));
  truthy('[+] the business is switched off, and why is recorded', suspended.status === 'INACTIVE' && suspended.deactivatedReason === 'NON_PAYMENT', suspended);
  truthy('[+] and its subscription says so too', data(call('GET', MINE, { token: owner })).subscription.status === 'SUSPENDED', 'subscription status');

  // ── the way back, with no sign-in, because it cannot sign in ────────────────────────────────────
  const link = data(dun('GET', `/invoices/${first.id}/pay-link`)).token;
  truthy('[+] every notice carries a link that pays without a sign-in', !!link && link.length > 20, link ? 'a token' : link);
  expect(call('POST', `/api/tenant-svc/billing/pay/${link}xx`), '[abuse] a token nobody issued opens nothing', 404, 'PAY_LINK_INVALID');
  const paid = call('POST', `/api/tenant-svc/billing/pay/${link}`);
  expect(paid, '[+] the link pays the one invoice it names', 200);
  truthy('[+] which settles it', data(paid).status === 'PAID', data(paid));
  expect(call('POST', `/api/tenant-svc/billing/pay/${link}`), '[abuse] and an old link cannot pay it twice', 404, 'PAY_LINK_INVALID');
  truthy('[+] the file ends somewhere: it was resolved', stages(first.id).includes('RESOLVED'), stages(first.id));

  const back = data(call('GET', `${PLATFORM}/tenants/${shop.tenantId}`, { token: root }));
  truthy('[+] paying up brings the business back, and clears the reason', back.status === 'ACTIVE' && !back.deactivatedReason, back);
  // Back in, the owner can read what was sent while the door was shut: the suspension had its own notice.
  const cut = noticesOfType('SERVICE_SUSPENDED', 1);
  truthy('[+] the suspension was a notice too, with the way back in it', cut.length === 1 && cut[0].body.includes('interrupted') && !!linkIn(cut[0]), cut.map((n) => n.subject));

  // ── the check this whole row rests on ───────────────────────────────────────────────────────────
  // An administrator's decision is not an argument money can win.
  expect(call('PATCH', `${PLATFORM}/tenants/${shop.tenantId}/status`, { token: root, body: { status: 'INACTIVE' } }), '[+] an administrator switches the business off', 200);
  const byAdmin = data(call('GET', `${PLATFORM}/tenants/${shop.tenantId}`, { token: root }));
  truthy('[+] recorded as the administrator\'s doing, not the platform\'s', byAdmin.deactivatedReason === 'ADMINISTRATOR', byAdmin);

  const second = must(asRoot('POST', '/run?asOf=' + day(35)), 200, 'another period billed');
  const owing = invoices().find((i) => i.status === 'OPEN');
  truthy('[+] it owes something again', !!owing, invoices().map((i) => i.status));
  expect(asRoot('POST', `/invoices/${owing.id}/payments`, { amount: Number(owing.totalAmount), method: 'BANK_TRANSFER', providerRef: `ADMIN-${tag}` }), '[+] and pays it in full', 200);
  const still = data(call('GET', `${PLATFORM}/tenants/${shop.tenantId}`, { token: root }));
  truthy('[abuse] paying up does NOT undo an administrator\'s decision', still.status === 'INACTIVE' && still.deactivatedReason === 'ADMINISTRATOR', still);

  // ── extending a due date pauses the chase without forgiving the debt ────────────────────────────
  const later = must(asRoot('POST', '/run?asOf=' + day(70)), 200, 'a third period');
  const promised = invoices().find((i) => i.status === 'OPEN');
  // An action ON AN INVOICE, so it sits with `payments` and `write-off` under /billing/invoices/…,
  // not with the dunning reads under /billing/dunning/…. Calling it through dun() got a 404 and four
  // failed checks in a row, all of them this one line.
  expect(asRoot('PUT', `/invoices/${promised.id}/due-date`, { dueDate: day(120), reason: 'they promised to pay' }), '[+] a promise to pay moves the date out', 200);
  expect(asRoot('PUT', `/invoices/${promised.id}/due-date`, { dueDate: day(80), reason: 'back again' }), '[-] and a date only ever moves out, never in', 400, 'DUE_DATE_NOT_LATER');
  truthy('[+] the extension is on the file rather than a date that quietly moved', stages(promised.id).includes('DUE_DATE_EXTENDED'), stages(promised.id));
  expect(dun('POST', '/run?asOf=' + day(100)), '[+] and the chase is paused while the promise stands', 200);
  truthy('[+] nothing chased in the meantime', stages(promised.id).filter((x) => x.startsWith('REMINDER')).length === 0, stages(promised.id));

  // ── whose books these are ──────────────────────────────────────────────────────────────────────
  expect(call('GET', `${DUNNING}/policy`, { token: owner }), '[abuse] a business does not read the platform\'s dunning policy', 403);
  expect(call('PUT', `${DUNNING}/policy`, { token: owner, body: { enabled: false, reminderDays: [1], suspendAfterDays: 2, uncollectibleAfterDays: 3 } }), '[abuse] nor switch its own chasing off', 403);
  expect(call('POST', `${DUNNING}/run`, { token: owner }), '[abuse] nor run the chase', 403);
  expect(call('GET', `${DUNNING}/overdue`, { token: owner }), '[abuse] nor read everybody\'s arrears', 403);
  expect(call('GET', `${DUNNING}/policy`), '[-] nor does anybody without a token', 401);

  completed.add(1);
}
