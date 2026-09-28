// A forgotten password, reset by email, through the gateway and a real mail catcher (Mailpit): the
// rules a new password must meet are public; asking for a link answers the same 202 for an address
// nobody holds, one that holds three logins, one whose business is suspended and the platform
// administrator's. One address that is a shopper and staff at a British and a Polish business gets
// ONE email naming both businesses, with a link per login; the British staff link is followed as a
// person would — a password the policy refuses leaves it good, the reset signs nobody in, the old
// password and every earlier session of that login stop, the link is spent, a made-up one is
// refused alike — while the Polish staff login and the shopper login keep their passwords and
// their sessions. A second request, in Polish, replaces the first email's links; a third in a
// language the platform does not have comes in English; a fourth in the hour is answered the same
// and sends nothing. A login with an authenticator is still asked for it after a reset. Nothing
// reaches the address nobody holds, the suspended business's staff or the platform administrator,
// and no business's notification log — read by its owner, its manager, its storekeeper or its
// cashier — holds the reset or any of its links.
//
// Needs Mailpit (docker compose's `mailpit`; MAILPIT_URL, default http://localhost:8025) with
// notification-svc emailing to it (storeql.notification.channel=email, the compose default), and the
// platform administrator's login (PLATFORM_ADMIN_* — k6/run.sh reads them from .env).
//
//   k6/run.sh password-reset-flow
import http from 'k6/http';
import { sleep } from 'k6';
import {
  ALL_CHECKS_PASS,
  answerSecondFactor,
  call,
  claims,
  data,
  errorCode,
  expect,
  must,
  onboardTenant,
  platformAdmin,
  poll,
  register,
  setTenantStatus,
  signInUntil,
  staffUser,
  totp,
  truthy,
  uniq,
} from './lib/storeql.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS, setupTimeout: '6m' };

const AUTH = '/api/v1/iam-svc/auth';
const N = '/api/v1/notification-svc';
const TS = '/api/v1/tenant-svc';
const MAILPIT = (__ENV.MAILPIT_URL || 'http://localhost:8025').replace(/\/+$/, '');
const RESET_LINK = /\/#\/reset-password\/([A-Za-z0-9_-]+)/g;

// ── the mail catcher ──────────────────────────────────────────────────────────────────────────────

/** A Mailpit timestamp (nanoseconds and all) as epoch millis; one it cannot read counts as now. */
function when(text) {
  const at = Date.parse(String(text || '').replace(/(\.\d{3})\d+/, '$1'));
  return Number.isNaN(at) ? Date.now() : at;
}

/** Every message Mailpit holds for an address (its summaries: ID, Subject, To, Created). */
function inbox(address) {
  const query = encodeURIComponent(`to:"${address}"`);
  const res = http.get(`${MAILPIT}/api/v1/search?query=${query}&limit=100`, { tags: { name: 'GET mailpit search' } });
  if (res.status !== 200) return [];
  let found = {};
  try {
    found = JSON.parse(res.body) || {};
  } catch (_) {
    return [];
  }
  const wanted = address.toLowerCase();
  return (found.messages || []).filter((m) => (m.To || []).some((to) => String(to.Address || '').toLowerCase() === wanted));
}

const opened = {};
/** One message's subject and plain text, read once. */
function readMessage(id) {
  if (!opened[id]) {
    const res = http.get(`${MAILPIT}/api/v1/message/${id}`, { tags: { name: 'GET mailpit message' } });
    if (res.status !== 200) return {};
    let m = {};
    try {
      m = JSON.parse(res.body) || {};
    } catch (_) {
      return {};
    }
    // The link is in the plain text (Mailpit's Text); the HTML part stands in if the text has none.
    const text = String(m.Text || '');
    opened[id] = { subject: m.Subject || '', text: text.includes('/#/reset-password/') ? text : String(m.HTML || text) };
  }
  return opened[id];
}

/** The tokens of the reset links in a text, each once, in the order they appear. */
function tokensIn(text) {
  const re = new RegExp(RESET_LINK.source, 'g');
  const seen = [];
  let m;
  while ((m = re.exec(String(text))) !== null) if (!seen.includes(m[1])) seen.push(m[1]);
  return seen;
}

/**
 * The reset emails an address has had since `since` (epoch millis), oldest first. A message is one
 * when it carries a reset link: in the dev stack every other notification is emailed too.
 */
function resetMails(address, since = 0) {
  return inbox(address)
    .filter((m) => when(m.Created) >= since)
    .map((m) => Object.assign({ created: when(m.Created) }, readMessage(m.ID)))
    .filter((m) => String(m.text || '').includes('/#/reset-password/'))
    .sort((x, y) => x.created - y.created)
    .map((m) => Object.assign(m, { tokens: tokensIn(m.text) }));
}

/**
 * Which login each link in a reset email is for. The email lists each login and then its link, so
 * a link belongs to the business named last before it since the previous link, and to the shopper
 * login when no business is named there.
 */
function linksByLogin(text, names) {
  const re = new RegExp(RESET_LINK.source, 'g');
  const out = {};
  let from = 0;
  let m;
  while ((m = re.exec(String(text))) !== null) {
    const before = String(text).slice(from, m.index);
    from = m.index + m[0].length;
    if (Object.values(out).includes(m[1])) continue;
    let who = 'SHOPPER';
    let at = -1;
    for (const [key, name] of Object.entries(names)) {
      const i = before.lastIndexOf(name);
      if (i > at) {
        at = i;
        who = key;
      }
    }
    if (!out[who]) out[who] = m[1];
  }
  return out;
}

// ── identities ────────────────────────────────────────────────────────────────────────────────────

function registerAs(email, password) {
  const d = must(call('POST', `${AUTH}/register`, { body: { email, password } }), 201, `register ${email}`);
  return { email, password, userId: claims(d.accessToken).sub, token: d.accessToken, refreshToken: d.refreshToken };
}

/** The address signed up afresh and made staff at one of the business's stores. */
function staffLogin(tenant, role, storeId, email, password) {
  const user = registerAs(email, password);
  must(call('POST', `${TS}/admin/staff`, { token: tenant.owner.token, body: { userId: user.userId, storeId, role } }), 201, `assign ${role}`);
  signInUntil(user, (c) => c.tenant === tenant.tenantId && (c.roles || []).includes(role));
  return user;
}

/**
 * A sign-in with a password (20.12): 200 with tokens, or 200 owing a second factor or its set-up —
 * either way the password was right. Anything else (401) it was not.
 */
function signIn(email, password) {
  const res = call('POST', `${AUTH}/login`, { body: { email, password } });
  const d = data(res);
  const owes = d.mfaRequired ? 'factor' : d.mfaEnrolmentRequired ? 'enrolment' : null;
  return { res, token: owes ? null : d.accessToken || null, owes, mfaToken: d.mfaToken || null };
}

/**
 * The next authenticator code the server has not seen: no code works twice and one step either side
 * of now is accepted, so a login that answers again walks forward, waiting when it must.
 */
function nextCode(app) {
  for (;;) {
    const now = Math.floor(Date.now() / 30000);
    const step = Math.max(app.lastStep + 1, now - 1);
    if (step <= now + 1) {
      app.lastStep = step;
      return totp(app.secret, step - now);
    }
    sleep(5);
  }
}

/** A password of exactly n characters, never seen in a breach and holding no login. */
function exactly(n) {
  let text = `new ${uniq()} phrase`;
  while (text.length < n) text += ' words';
  return text.slice(0, n);
}

export function setup() {
  const mail = http.get(`${MAILPIT}/api/v1/info`, { tags: { name: 'GET mailpit info' } });
  if (mail.status !== 200) throw new Error(`Mailpit is not reachable at ${MAILPIT} (${mail.status}): start the stack's mailpit, or set MAILPIT_URL`);
  const admin = platformAdmin();
  const gb = onboardTenant('pwreset-gb', { country: 'GB', currency: 'GBP' });
  const pl = onboardTenant('pwreset-pl', { country: 'PL', currency: 'PLN' });
  const nameOf = (tenant) => must(call('GET', `${TS}/admin/tenant`, { token: tenant.owner.token }), 200, `${tenant.label} profile`).name;
  const names = { A: nameOf(gb), B: nameOf(pl) };

  // One address, three logins. A sign-up made staff binds to that business and leaves the address
  // free for the next sign-up: the way a person comes to be a shopper and staff at two businesses.
  // Each login has its own password — that is how a sign-in tells them apart.
  const address = `pwreset-${uniq()}@k6.storeql.test`;
  const passwords = { A: `british staff ${uniq()}`, B: `polish staff ${uniq()}`, S: `just shopping ${uniq()}` };
  const staffA = staffLogin(gb, 'STOREKEEPER', gb.stores[0].id, address, passwords.A);
  const staffB = staffLogin(pl, 'CASHIER', pl.stores[0].id, address, passwords.B);
  const shopper = registerAs(address, passwords.S);

  const plStore = [pl.stores[0].id];
  const gbManager = staffUser(gb, 'MANAGER', [gb.stores[0].id]);
  const plRoles = { MANAGER: staffUser(pl, 'MANAGER', plStore), STOREKEEPER: staffUser(pl, 'STOREKEEPER', plStore), CASHIER: staffUser(pl, 'CASHIER', plStore) };

  // Staff of a business the platform has suspended: their login is active, their business is not.
  const idle = onboardTenant('pwreset-in', { country: 'IN', currency: 'INR' });
  const idleStaff = staffUser(idle, 'CASHIER', [idle.stores[0].id]);
  must(setTenantStatus(admin, idle.tenantId, 'INACTIVE'), 200, 'the platform suspends the Indian business');

  // A shopper with an authenticator app.
  const guarded = register('pwreset-mfa');
  const begun = must(call('POST', `${AUTH}/mfa/totp`, { token: guarded.token }), 200, 'begin an authenticator');
  const app = { secret: begun.secret, lastStep: 0 };
  must(call('POST', `${AUTH}/mfa/totp/confirm`, { token: guarded.token, body: { code: nextCode(app) } }), 200, 'confirm the authenticator');

  return { adminEmail: admin.email, gb, pl, names, address, passwords, staffA, staffB, shopper, gbManager, plRoles, idleStaff, guarded, app, last: register('pwreset-last') };
}

export default function ({ adminEmail, gb, pl, names, address, passwords, staffA, staffB, gbManager, plRoles, idleStaff, guarded, app, last }) {
  const forgot = (email, language) => call('POST', `${AUTH}/password/forgot`, { body: language ? { email, language } : { email } });
  const reset = (token, newPassword) => call('POST', `${AUTH}/password/reset`, { body: { token, newPassword } });
  const refresh = (refreshToken) => call('POST', `${AUTH}/refresh`, { body: { refreshToken } });
  const accepted = (res) => res.status === 202 && JSON.stringify(data(res)) === '{"accepted":true}';
  const tenantOf = (token) => claims(token || '').tenant;
  const rolesOf = (token) => claims(token || '').roles || [];

  // ── 1. the rules, public ─────────────────────────────────────────────────────────────────────────
  const published = call('GET', `${AUTH}/password-policy`);
  expect(published, '[+] the password rules are read with no sign-in', 200);
  const policy = data(published);
  truthy('[+] ...fifteen characters or more, at most 128, never holding the login, the breach screen said', policy.minLength === 15 && policy.maxLength === 128 && policy.mustNotContainLogin === true && typeof policy.breachScreened === 'boolean', policy);
  truthy('[+] ...the same rules for a signed-in owner of either business', [gb.owner.token, pl.owner.token].every((token) => JSON.stringify(data(call('GET', `${AUTH}/password-policy`, { token }))) === JSON.stringify(policy)), policy);
  const min = policy.minLength || 15;

  // ── 2. asking for a link ─────────────────────────────────────────────────────────────────────────
  expect(call('POST', `${AUTH}/password/forgot`, { body: {} }), '[-] asking with no address is refused', 400, 'VALIDATION_FAILED');
  expect(forgot('not an address'), '[-] ...and with something that is not an email address', 400, 'VALIDATION_FAILED');
  const asked = forgot(address);
  truthy('[+] the shared address asks for a link and is told only that it was accepted: 202 {accepted:true}', accepted(asked), { status: asked.status, body: data(asked) });
  let first = [];
  const took = poll(90, () => (first = resetMails(address)).length >= 1);
  truthy(`[+] one email arrives for the address (${took}s)`, took >= 0 && first.length === 1, first.map((m) => m.subject));
  const e1 = first[0] || { subject: '', text: '', tokens: [] };
  truthy('[+] ...naming both businesses', e1.text.includes(names.A) && e1.text.includes(names.B), { names, text: e1.text.slice(0, 800) });
  truthy('[+] ...with three links, one per login', e1.tokens.length === 3, e1.tokens.length);
  truthy('[+] ...and saying when they stop working, in UTC', e1.text.includes('UTC'), e1.text.slice(0, 800));
  const links1 = linksByLogin(e1.text, { A: names.A, B: names.B });
  truthy('[+] ...a link for the shopper, one for staff at the British business and one for staff at the Polish one', Boolean(links1.SHOPPER && links1.A && links1.B) && new Set([links1.SHOPPER, links1.A, links1.B]).size === 3, links1);
  if (!links1.A || !links1.B || !links1.SHOPPER) return;

  // ── 3. the British staff link, followed ──────────────────────────────────────────────────────────
  const sessionA = must(refresh(staffA.refreshToken), 200, "staff A's session renews before the reset").refreshToken;
  const sessionB = must(refresh(staffB.refreshToken), 200, "staff B's session renews before the reset").refreshToken;
  expect(reset(links1.A, exactly(min - 1)), `[-] a new password of ${min - 1} characters is refused by the published minimum`, 400, 'PASSWORD_TOO_SHORT');
  expect(reset(links1.A, `${address.split('@')[0]} and some words`), '[-] ...and one holding the login', 400, 'PASSWORD_IS_IDENTITY');
  expect(call('POST', `${AUTH}/password/reset`, { body: { token: links1.A } }), '[-] ...and a reset with no new password', 400, 'VALIDATION_FAILED');
  const newA = exactly(min);
  const done = reset(links1.A, newA);
  expect(done, `[+] the refusals left the link good: ${min} characters reset the password`, 200);
  truthy('[+] ...it says so and signs nobody in: no token', data(done).reset === true && data(done).accessToken === undefined && data(done).refreshToken === undefined, data(done));
  expect(refresh(sessionA), "[-] staff A's session from before the reset is not renewed", 401, 'INVALID_REFRESH');
  expect(signIn(address, passwords.A).res, "[-] staff A's old password no longer signs in", 401, 'INVALID_CREDENTIALS');
  const inA = signIn(address, newA);
  truthy('[+] the new one signs staff A in, at the British business, as its storekeeper', tenantOf(inA.token) === gb.tenantId && rolesOf(inA.token).includes('STOREKEEPER'), claims(inA.token || ''));
  expect(reset(links1.A, exactly(min + 3)), '[-] the link is spent', 400, 'PASSWORD_RESET_TOKEN_INVALID');
  expect(reset(`${uniq()}${uniq()}${uniq()}`.slice(0, 43), exactly(min)), '[-] a made-up link is refused the same way', 400, 'PASSWORD_RESET_TOKEN_INVALID');
  const stillB = signIn(address, passwords.B);
  truthy('[+] staff B still signs in with the old password, at the Polish business', tenantOf(stillB.token) === pl.tenantId && rolesOf(stillB.token).includes('CASHIER'), claims(stillB.token || ''));
  const stillShopper = signIn(address, passwords.S);
  truthy('[+] ...and so does the shopper login, which belongs to no business', Boolean(stillShopper.token) && !tenantOf(stillShopper.token) && rolesOf(stillShopper.token).includes('CUSTOMER'), claims(stillShopper.token || ''));
  expect(refresh(sessionB), "[+] staff B's session from before the reset renews: a reset touches one login", 200);

  // ── 4. a newer request replaces the older links ──────────────────────────────────────────────────
  truthy('[+] the address asks again, in Polish, and is told the same', accepted(forgot(address, 'pl')));
  let second = [];
  truthy('[+] a second email arrives', poll(90, () => (second = resetMails(address)).length >= 2) >= 0, second.length);
  const e2 = second[1] || { subject: '', text: '', tokens: [] };
  truthy('[+] ...in Polish, not the English of the first', e2.subject !== '' && e2.subject !== e1.subject, [e1.subject, e2.subject]);
  truthy('[+] ...with three new links', e2.tokens.length === 3 && !e2.tokens.some((t) => e1.tokens.includes(t)), e2.tokens.length);
  const links2 = linksByLogin(e2.text, { A: names.A, B: names.B });
  expect(reset(links1.B, exactly(min)), "[-] the first email's link for staff B was replaced", 400, 'PASSWORD_RESET_TOKEN_INVALID');
  expect(reset(links1.SHOPPER, exactly(min)), "[-] ...and so was the shopper's", 400, 'PASSWORD_RESET_TOKEN_INVALID');
  const newB = exactly(min + 5);
  expect(reset(links2.B, newB), "[+] the second email's link for staff B resets staff B", 200);
  expect(signIn(address, passwords.B).res, "[-] staff B's old password no longer signs in", 401, 'INVALID_CREDENTIALS');
  const inB = signIn(address, newB);
  truthy('[+] ...the new one does, at the Polish business', tenantOf(inB.token) === pl.tenantId, claims(inB.token || ''));
  truthy("[+] staff A's new password and the shopper's own still sign in", tenantOf(signIn(address, newA).token) === gb.tenantId && Boolean(signIn(address, passwords.S).token));

  // ── 5. at most three requests an hour for one address ────────────────────────────────────────────
  truthy('[+] a third request, in a language the platform does not have, is told the same', accepted(forgot(address, 'fr')));
  let third = [];
  truthy('[+] ...its email arrives in English', poll(90, () => (third = resetMails(address)).length >= 3) >= 0 && (third[2] || {}).subject === e1.subject, (third[2] || {}).subject);
  const throttled = forgot(address);
  truthy('[+] a fourth request in the hour is answered word for word the same', accepted(throttled), { status: throttled.status, body: data(throttled) });

  // ── 6. addresses that get no link ────────────────────────────────────────────────────────────────
  const since = Date.now() - 60000;
  const nobody = `pwreset-nobody-${uniq()}@k6.storeql.test`;
  truthy('[+] an address nobody holds is answered exactly the same', accepted(forgot(nobody)));
  // iam-svc hears of the suspension from tenant-svc's announcement. A refresh says when it has — a
  // sign-in would count its refusals against this host at the gateway's lockout.
  let idleSession = idleStaff.refreshToken;
  const heard = poll(60, () => {
    const r = refresh(idleSession);
    if (r.status === 200) {
      idleSession = data(r).refreshToken;
      return false;
    }
    return r.status === 403 && errorCode(r) === 'TENANT_INACTIVE';
  });
  truthy(`[+] iam-svc knows the Indian business is suspended (${heard}s)`, heard >= 0);
  truthy('[+] ...and its staff member\'s address is answered exactly the same', accepted(forgot(idleStaff.email)));
  truthy("[+] ...and so is the platform administrator's", accepted(forgot(adminEmail)));

  // ── 7. a login with an authenticator ─────────────────────────────────────────────────────────────
  truthy('[+] a shopper with an authenticator asks for a link', accepted(forgot(guarded.email)));
  let guardedMail = [];
  truthy('[+] ...and gets one, for the one login', poll(90, () => (guardedMail = resetMails(guarded.email)).length >= 1) >= 0 && guardedMail[0].tokens.length === 1, guardedMail.map((m) => m.tokens.length));
  const newG = exactly(min + 2);
  expect(reset((guardedMail[0] || { tokens: [] }).tokens[0], newG), '[+] ...which resets the password', 200);
  expect(signIn(guarded.email, guarded.password).res, '[-] the old password no longer signs in', 401, 'INVALID_CREDENTIALS');
  const owed = signIn(guarded.email, newG);
  truthy('[+] the new one is still asked for the second factor, with no token until it is answered', owed.res.status === 200 && owed.owes === 'factor' && !owed.token && Boolean(owed.mfaToken), data(owed.res));
  const answered = answerSecondFactor(owed.mfaToken, 'TOTP', nextCode(app));
  expect(answered, '[+] ...the authenticator answers it', 200);
  truthy('[+] ...and the session says both factors', JSON.stringify(claims(data(answered).accessToken || '').amr) === '["pwd","otp"]', claims(data(answered).accessToken || '').amr);

  // ── 8. what was never sent ───────────────────────────────────────────────────────────────────────
  truthy('[+] one more address asks, last of all', accepted(forgot(last.email)));
  truthy('[+] ...and its email arrives: the requests before it have been dealt with', poll(90, () => resetMails(last.email).length >= 1) >= 0);
  sleep(5);
  truthy('[-] the fourth request sent nothing: three emails to the shared address, no more', resetMails(address).length === 3, resetMails(address).map((m) => m.subject));
  truthy('[-] nothing at all to the address nobody holds', inbox(nobody).length === 0, inbox(nobody).map((m) => m.Subject));
  truthy('[-] no link to staff of the suspended business', resetMails(idleStaff.email).length === 0, resetMails(idleStaff.email).map((m) => m.subject));
  truthy('[-] no link to the platform administrator', resetMails(adminEmail, since).length === 0, resetMails(adminEmail, since).map((m) => m.subject));

  // ── 9. no business's notification log holds the reset or its links ──────────────────────────────
  const tokens = [].concat(e1.tokens, e2.tokens, (third[2] || { tokens: [] }).tokens);
  const log = (token) => call('GET', `${N}/admin/notifications?recipient=${encodeURIComponent(address)}&limit=100`, { token });
  const clean = (res) => res.status === 200 && Array.isArray(data(res)) && !data(res).some((n) => n.type === 'PASSWORD_RESET') && !String(res.body).includes('reset-password') && !tokens.some((t) => String(res.body).includes(t));
  truthy("[-] the British owner's notification log holds no reset and no link", clean(log(gb.owner.token)), String(log(gb.owner.token).body).slice(0, 400));
  truthy('[-] ...nor does its manager\'s view of it', clean(log(gbManager.token)));
  truthy("[-] the Polish owner's log holds none either", clean(log(pl.owner.token)), String(log(pl.owner.token).body).slice(0, 400));
  truthy('[-] ...nor its manager\'s', clean(log(plRoles.MANAGER.token)));
  expect(log(plRoles.STOREKEEPER.token), '[-] its storekeeper reads no notification log at all', 403);
  expect(log(plRoles.CASHIER.token), '[-] ...nor its cashier', 403);
  expect(log(inA.token), '[-] ...nor staff A, a storekeeper, their own reset included', 403);
  const whole = call('GET', `${N}/admin/notifications?limit=100`, { token: gb.owner.token });
  truthy("[-] the British business's whole log, unfiltered, holds no reset", clean(whole), String(whole.body).slice(0, 400));
}
