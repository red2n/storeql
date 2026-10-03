// Single sign-on through a business's own identity provider (20.x; OpenID Connect Core 1.0,
// RFC 7636, RFC 9700), through the gateway against a real OpenID Connect server (mock-idp,
// navikt/mock-oauth2-server): an owner connects the provider and the platform reads it, a cashier
// signs in there and comes back with a ticket the app trades for a session, the second sign-in
// matches by subject, a business requires its provider of cashiers and their password stops
// working, the provider's own second factor is enough and a sign-in without one sets one up here
// — and the session says it began at the provider. Refused: a ticket twice, a ticket without the
// verifier that started it, ten redemptions of one ticket at once, a state twice, a return to
// anywhere but the app, a person the business never added, another business's staff, an address
// the provider never verified, a second person claiming a linked login, an issuer inside the
// network, a forged X-Auth-Methods, a manager or a cashier changing the settings, an owner
// requiring the provider of owners.
//
//   k6/run.sh sso-flow
import http from 'k6/http';
import k6crypto from 'k6/crypto';
import encoding from 'k6/encoding';
import { Counter } from 'k6/metrics';
import { ALL_CHECKS_PASS, BASE, call, claims, data, expect, login, must, onboardTenant, register, staffUser, totp, truthy, uniq } from './lib/storeql.js';

const completed = new Counter('flow_completed');
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
  setupTimeout: '4m',
};

const AUTH = '/api/iam-svc/auth';
const APP = __ENV.WEB_ORIGIN || 'http://localhost:8088';
// The provider as iam-svc reaches it, inside the network, and as this script reaches it.
const IDP_ISSUER = __ENV.IDP_ISSUER || 'http://mock-idp:8080/storeql';
const IDP_INTERNAL = __ENV.IDP_INTERNAL || 'http://mock-idp:8080';
const IDP_BROWSER = __ENV.IDP_BROWSER || 'http://localhost:8095';

export function setup() {
  const tenant = onboardTenant('sso');
  const store = tenant.stores[0].id;
  const other = onboardTenant('sso-other');
  return {
    tenant,
    cashier: staffUser(tenant, 'CASHIER', [store]),
    manager: staffUser(tenant, 'MANAGER', [store]),
    newcomer: staffUser(tenant, 'CASHIER', [store]),
    elsewhere: staffUser(other, 'CASHIER', [other.stores[0].id]),
    other,
    shopper: register('sso-shopper'),
    bystander: register('sso-bystander'),
  };
}

// ── the app's part ────────────────────────────────────────────────────────────

function newVerifier() {
  return encoding.b64encode(crypto.getRandomValues(new Uint8Array(32)).buffer, 'rawurl');
}

const challengeOf = (verifier) => k6crypto.sha256(verifier, 'base64rawurl');

function queryOf(url) {
  const out = {};
  const q = url.split('?')[1] || '';
  for (const pair of q.split('#')[0].split('&')) {
    const [k, v] = pair.split('=');
    if (k) out[decodeURIComponent(k)] = decodeURIComponent((v || '').replace(/\+/g, ' '));
  }
  return out;
}

/** What the app finds after the #: {sso_ticket} or {sso_error}. */
function fragmentOf(location) {
  const f = String(location || '').split('#')[1] || '';
  const [k, v] = f.split('=');
  return k ? { [k]: decodeURIComponent(v || '') } : {};
}

function start(slug, { returnTo = `${APP}/`, verifier = newVerifier() } = {}) {
  const res = call('POST', `${AUTH}/sso/start`, { body: { slug, codeChallenge: challengeOf(verifier), returnTo } });
  return { res, verifier, url: data(res).authorizationUrl };
}

/**
 * Signs in at the provider as `username` with these claims in the ID token, the way its login page
 * does: the page, then the form posted back to it. Answers where the provider sends the browser.
 */
function atProvider(authorizationUrl, username, idClaims) {
  const url = authorizationUrl.replace(IDP_INTERNAL, IDP_BROWSER);
  http.get(url, { redirects: 0, tags: { name: 'GET mock-idp authorize' } });
  const res = http.post(url, { username, claims: JSON.stringify(idClaims) }, { redirects: 0, tags: { name: 'POST mock-idp authorize' } });
  if (res.status !== 302) throw new Error(`mock-idp did not redirect: ${res.status} ${String(res.body).slice(0, 300)}`);
  return res.headers.Location;
}

/** The browser arriving at the callback; where it is sent next. */
function callback(queryString) {
  const res = http.get(`${BASE}${AUTH}/sso/callback${queryString}`, { redirects: 0, tags: { name: 'GET /api/iam-svc/auth/sso/callback' } });
  return { res, location: res.headers.Location, back: fragmentOf(res.headers.Location) };
}

/** Back from the provider to the callback, with what the provider sent. */
function returnFrom(providerLocation) {
  return callback(`?${providerLocation.split('?')[1]}`);
}

const redeem = (ticket, codeVerifier) => call('POST', `${AUTH}/sso/token`, { body: { ticket, codeVerifier } });

/** A whole sign-in: start, the provider, the callback. What the app came back with, and its verifier. */
function signIn(slug, username, idClaims) {
  const s = start(slug);
  must(s.res, 200, `start ${slug}`);
  const cb = returnFrom(atProvider(s.url, username, idClaims));
  return { ...cb, verifier: s.verifier, state: queryOf(s.url).state };
}

/** A whole sign-in, redeemed. */
function signInAndRedeem(slug, username, idClaims) {
  const s = signIn(slug, username, idClaims);
  if (!s.back.sso_ticket) throw new Error(`no ticket for ${username}: ${s.location}`);
  return redeem(s.back.sso_ticket, s.verifier);
}

const amrOf = (res) => claims(data(res).accessToken || '').amr || [];
const verified = (email, extra = {}) => ({ email, email_verified: true, ...extra });

export default function ({ tenant, cashier, manager, newcomer, elsewhere, other, shopper, bystander }) {
  const owner = tenant.owner;
  const slug = `sso-${uniq()}`.toLowerCase().slice(0, 40);
  const connection = (over = {}) => ({
    slug,
    issuer: IDP_ISSUER,
    clientId: 'storeql-k6',
    clientSecret: 'k6 client secret',
    enabled: true,
    requiredTiers: [],
    requireVerifiedEmail: true,
    ...over,
  });
  const put = (token, body) => call('PUT', `${AUTH}/admin/sso`, { token, body });
  // A sign-in that succeeds, so the suite's refused passwords never add up to a lockout.
  const breather = () => login(bystander);

  // ── an owner connects the provider ───────────────────────────────────────────────────────────────
  expect(call('GET', `${AUTH}/admin/sso`, { token: owner.token }), '[+] none connected yet', 404, 'SSO_NOT_CONFIGURED');
  expect(put(manager.token, connection()), '[-] a manager does not connect the provider', 403, 'FORBIDDEN');
  expect(put(cashier.token, connection()), '[-] a cashier does not connect the provider', 403, 'FORBIDDEN');
  expect(put(shopper.token, connection()), '[-] a shopper does not connect the provider', 403, 'FORBIDDEN');
  expect(put(owner.token, connection({ requiredTiers: ['OWNER'] })), '[-] an owner is never made to use the provider', 400, 'SSO_OWNER_NOT_REQUIRABLE');
  expect(put(owner.token, connection({ requiredTiers: ['JANITOR'] })), '[-] a tier that does not exist', 400, 'SSO_TIER_UNKNOWN');
  expect(put(owner.token, connection({ clientSecret: null })), '[-] the first connection needs its secret', 400, 'SSO_SECRET_REQUIRED');
  expect(put(owner.token, connection({ issuer: 'http://idp.example.com' })), '[-] a provider is HTTPS', 400, 'SSO_ISSUER_INVALID');
  expect(put(owner.token, connection({ slug: 'Not A Name' })), '[-] a sign-in name is lower-case letters, digits and hyphens', 400, 'VALIDATION_FAILED');
  const saved = put(owner.token, connection());
  expect(saved, '[+] the owner connects the provider', 200);
  truthy('[+] the secret is held and never shown', data(saved).clientSecretSet === true && !String(saved.body).includes('k6 client secret'), saved.body);
  truthy('[+] the redirect URI to register is given', String(data(saved).callbackUrl || '').endsWith('/api/iam-svc/auth/sso/callback'), data(saved));
  expect(call('GET', `${AUTH}/admin/sso`, { token: manager.token }), '[+] a manager can read it', 200);
  expect(call('GET', `${AUTH}/admin/sso`, { token: cashier.token }), '[-] a cashier cannot', 403, 'FORBIDDEN');
  expect(call('GET', `${AUTH}/admin/sso`, { token: other.owner.token }), '[-] another business sees none of it', 404, 'SSO_NOT_CONFIGURED');
  expect(put(other.owner.token, { ...connection(), slug }), '[-] another business cannot take the name', 409, 'SSO_SLUG_TAKEN');

  const ready = must(call('GET', `${AUTH}/admin/sso/readiness`, { token: owner.token }), 200, 'readiness');
  truthy('[+] ready: the provider was read, discovery and keys', ready.ready === true && ready.checks.length === 6, ready);

  // An issuer inside the network is saved as typed, and never called.
  expect(put(owner.token, connection({ issuer: 'https://169.254.169.254/latest' })), '[+] a typed issuer is saved', 200);
  const metadata = must(call('GET', `${AUTH}/admin/sso/readiness`, { token: owner.token }), 200, 'readiness, metadata issuer');
  const discovery = (metadata.checks || []).find((c) => c.code === 'DISCOVERY_READ') || {};
  truthy('[-] a cloud metadata address is not called', metadata.ready === false && discovery.satisfied === false && String(discovery.detail).includes('not an address this platform calls'), metadata);
  const s0 = start(slug);
  expect(s0.res, '[-] nor started against', 502, 'SSO_PROVIDER_ADDRESS_REFUSED');
  must(put(owner.token, connection({ clientSecret: null })), 200, 'back to mock-idp, secret kept');

  // ── starting ─────────────────────────────────────────────────────────────────────────────────────
  expect(start('nobody-signs-in-as-this').res, '[-] a name nobody signs in with', 404, 'SSO_NOT_FOUND');
  expect(start(slug, { returnTo: 'https://evil.example.com/' }).res, '[-] a return to anywhere but the app', 400, 'SSO_RETURN_REFUSED');
  expect(start(slug, { returnTo: `${APP}/#/steal` }).res, '[-] a return with a fragment of its own', 400, 'SSO_RETURN_REFUSED');
  expect(call('POST', `${AUTH}/sso/start`, { body: { slug, codeChallenge: 'plain' } }), '[-] a challenge that is not S256', 400, 'VALIDATION_FAILED');
  const s1 = start(slug.toUpperCase());
  expect(s1.res, '[+] started, the name in any case', 200);
  const q = queryOf(s1.url || '');
  truthy(
    '[+] to the provider: the code flow, S256, the platform\'s own challenge, a state and a nonce',
    q.response_type === 'code' && q.code_challenge_method === 'S256' && q.code_challenge !== challengeOf(s1.verifier) && q.state && q.nonce && q.scope === 'openid email profile',
    q,
  );

  // ── a cashier signs in ───────────────────────────────────────────────────────────────────────────
  const first = returnFrom(atProvider(s1.url, 'cashier-at-idp', verified(cashier.email)));
  expect(first.res, '[+] back from the provider: sent on to the app', 303);
  truthy('[+] with a ticket, in the fragment', String(first.location).startsWith(`${APP}/#sso_ticket=`), first.location);
  const ticket = first.back.sso_ticket;
  truthy('[-] the state is spent', returnFrom(atProvider(s1.url, 'cashier-at-idp', verified(cashier.email))).back.sso_error === 'SSO_STATE_INVALID');
  truthy('[-] a state never issued', callback('?code=x&state=never-issued').back.sso_error === 'SSO_STATE_INVALID');
  truthy('[-] no state at all', callback('').back.sso_error === 'SSO_STATE_INVALID');

  expect(redeem(ticket, newVerifier()), '[-] a ticket in a browser that did not start it', 401, 'SSO_TICKET_INVALID');
  expect(redeem(ticket, s1.verifier), '[-] and it is spent by the attempt', 401, 'SSO_TICKET_INVALID');

  const signedIn = signInAndRedeem(slug, 'cashier-at-idp', verified(cashier.email));
  expect(signedIn, '[+] the ticket and its verifier: a session', 200);
  const token = claims(data(signedIn).accessToken || '');
  truthy('[+] the cashier\'s, at the business, proved by the provider', token.sub === cashier.userId && token.tenant === tenant.tenantId && (token.roles || []).includes('CASHIER') && JSON.stringify(token.amr) === '["sso"]', token);
  expect(call('GET', `${AUTH}/me`, { token: data(signedIn).accessToken }), '[+] and it works', 200);
  const renewed = call('POST', `${AUTH}/refresh`, { body: { refreshToken: data(signedIn).refreshToken } });
  expect(renewed, '[+] renewed', 200);
  truthy('[+] as what it was', JSON.stringify(amrOf(renewed)) === '["sso"]', amrOf(renewed));

  // Ten redemptions of one ticket at once: one sign-in.
  const race = signIn(slug, 'cashier-at-idp', verified(cashier.email));
  const answers = http.batch(
    Array.from({ length: 10 }, () => ({
      method: 'POST',
      url: `${BASE}${AUTH}/sso/token`,
      body: JSON.stringify({ ticket: race.back.sso_ticket, codeVerifier: race.verifier }),
      params: { headers: { 'Content-Type': 'application/json' }, tags: { name: 'POST /api/iam-svc/auth/sso/token (race)' } },
    })),
  );
  truthy('[+] ten at once: exactly one session', answers.filter((r) => r.status === 200).length === 1 && answers.filter((r) => r.status === 401).length === 9, answers.map((r) => r.status));

  // The link is by subject from now on: an address changed at the provider changes nothing.
  const renamed = signInAndRedeem(slug, 'cashier-at-idp', verified('renamed-at-the-provider@example.com'));
  truthy('[+] the second sign-in matches by subject', renamed.status === 200 && claims(data(renamed).accessToken || '').sub === cashier.userId, renamed.body);

  // ── who is not let in ────────────────────────────────────────────────────────────────────────────
  truthy('[-] somebody the business never added', signIn(slug, 'stranger', verified(`stranger-${uniq()}@example.com`)).back.sso_error === 'SSO_NO_ACCOUNT');
  truthy('[-] another business\'s cashier', signIn(slug, 'elsewhere', verified(elsewhere.email)).back.sso_error === 'SSO_NO_ACCOUNT');
  truthy('[-] a shopper', signIn(slug, 'shopper', verified(shopper.email)).back.sso_error === 'SSO_NO_ACCOUNT');
  truthy('[-] an address the provider never verified', signIn(slug, 'unverified', { email: newcomer.email, email_verified: false }).back.sso_error === 'SSO_EMAIL_UNVERIFIED');
  truthy('[-] a second person claiming a linked login', signIn(slug, 'somebody-else', verified(cashier.email)).back.sso_error === 'SSO_ALREADY_LINKED');
  const cancelled = start(slug);
  truthy('[-] cancelled at the provider', callback(`?error=access_denied&state=${queryOf(cancelled.url).state}`).back.sso_error === 'SSO_CANCELLED');

  const links = must(call('GET', `${AUTH}/admin/sso/identities`, { token: manager.token }), 200, 'links');
  truthy('[+] the owner and manager see who is linked', links.items.length === 1 && links.items[0].loginEmail === cashier.email && links.items[0].subject === 'cashier-at-idp', links);
  expect(call('GET', `${AUTH}/admin/sso/identities`, { token: cashier.token }), '[-] a cashier does not', 403, 'FORBIDDEN');
  expect(call('DELETE', `${AUTH}/admin/sso/identities/${links.items[0].id}`, { token: other.owner.token }), '[-] another business cannot unlink it', 404, 'SSO_LINK_NOT_FOUND');

  // ── the provider required of cashiers ────────────────────────────────────────────────────────────
  breather();
  const before = login(newcomer);
  must(before, 200, 'a cashier password session before the rule');
  must(put(owner.token, connection({ clientSecret: null, requiredTiers: ['CASHIER'] })), 200, 'require the provider of cashiers');
  const refused = login(newcomer);
  expect(refused, '[-] a cashier\'s password stops working', 403, 'SSO_REQUIRED');
  truthy('[+] and the refusal names the business', String(refused.body).includes(`slug=${slug}`), refused.body);
  breather();
  expect(call('POST', `${AUTH}/refresh`, { body: { refreshToken: data(before).refreshToken } }), '[-] a password session is not renewed', 401, 'SSO_REQUIRED');
  expect(login(owner), '[+] an owner\'s password always works', 200);
  expect(login(manager), '[+] a manager\'s, not required, still works', 200);
  expect(signInAndRedeem(slug, 'newcomer-at-idp', verified(newcomer.email)), '[+] the cashier signs in through the provider', 200);

  // ── the second factor ────────────────────────────────────────────────────────────────────────────
  must(call('PUT', `${AUTH}/admin/mfa-policy`, { token: owner.token, body: { requiredTiers: ['CASHIER', 'MANAGER'] } }), 200, 'require a second factor');
  const withMfa = signInAndRedeem(slug, 'cashier-at-idp', verified(cashier.email, { amr: ['pwd', 'mfa'] }));
  truthy('[+] the provider\'s own second factor is enough', withMfa.status === 200 && JSON.stringify(amrOf(withMfa)) === '["sso","mfa"]', withMfa.body);

  const owed = signInAndRedeem(slug, 'cashier-at-idp', verified(cashier.email));
  truthy('[+] a provider that asked for one factor: a factor is set up here', data(owed).mfaEnrolmentRequired === true, owed.body);
  const enrolment = data(owed).accessToken;
  const begun = must(call('POST', `${AUTH}/mfa/totp`, { token: enrolment }), 200, 'begin authenticator');
  const done = must(call('POST', `${AUTH}/mfa/totp/confirm`, { token: enrolment, body: { code: totp(begun.secret, -1) } }), 200, 'confirm authenticator');
  truthy('[+] the session records that it began at the provider', JSON.stringify(claims(done.tokens.accessToken).amr) === '["sso","otp"]', claims(done.tokens.accessToken));

  // A client cannot say its session began at the provider: the gateway stamps it from the token.
  breather();
  const pw = must(login(manager), 200, 'manager password, factor now owed');
  truthy('[+] the manager owes a factor', pw.mfaEnrolmentRequired === true, pw);
  const mBegun = must(call('POST', `${AUTH}/mfa/totp`, { token: pw.accessToken, headers: { 'X-Auth-Methods': 'sso,mfa' } }), 200, 'manager begin');
  const mDone = must(
    call('POST', `${AUTH}/mfa/totp/confirm`, { token: pw.accessToken, headers: { 'X-Auth-Methods': 'sso,mfa' }, body: { code: totp(mBegun.secret, -1) } }),
    200,
    'manager confirm',
  );
  truthy('[-] a forged X-Auth-Methods is replaced by what the token says', JSON.stringify(claims(mDone.tokens.accessToken).amr) === '["pwd","otp"]', claims(mDone.tokens.accessToken));

  // ── undone ───────────────────────────────────────────────────────────────────────────────────────
  expect(call('DELETE', `${AUTH}/admin/sso/identities/${links.items[0].id}`, { token: owner.token }), '[+] the owner unlinks a login', 200);
  expect(call('DELETE', `${AUTH}/admin/sso`, { token: manager.token }), '[-] a manager does not disconnect', 403, 'FORBIDDEN');
  expect(call('DELETE', `${AUTH}/admin/sso`, { token: owner.token }), '[+] the owner disconnects', 200);
  expect(start(slug).res, '[+] and nobody signs in with the name', 404, 'SSO_NOT_FOUND');
  breather();
  const again = login(newcomer);
  truthy('[+] the cashier\'s password works again — on to the factor the business requires', again.status === 200 && data(again).mfaEnrolmentRequired === true, again.body);

  completed.add(1);
}
