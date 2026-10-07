// Multi-factor authentication (20.12; NIST SP 800-63B-4 AAL2, ASVS 5.0 V6, PCI DSS 8.4), through
// the gateway: an authenticator app set up and then asked for at every sign-in, recovery codes
// that work once, a passkey registered and used with a software authenticator, a business
// requiring a second factor of a tier — the next sign-in gets a token good only for setting one
// up, and a password-only session is not renewed — the lost-phone reset, and the platform
// administrator born with a factor. Refused: a code twice, a code for another login's sign-in, a
// fifth wrong answer's successor, an enrolment token anywhere but the second-factor routes, a
// scope header sent by a client, the last factor removed against the rule, a passkey signed for
// another origin, an assertion replayed, a cashier or a manager changing the rule, an owner
// resetting themselves or somebody else's staff.
//
// The suite's wrong answers count as failed sign-ins at the gateway (five lock this host out), so
// it keeps a successful sign-in between them; k6/run.sh clears the counters afterwards.
//
//   k6/run.sh mfa-flow
import { sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { ALL_CHECKS_PASS, PASSWORD, answerSecondFactor, call, claims, data, expect, login, must, onboardTenant, platformAdmin, register, staffUser, totp, truthy } from './lib/storeql.js';
import { SoftwarePasskey } from './lib/passkey.js';

const completed = new Counter('flow_completed');
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
  setupTimeout: '4m',
};

const AUTH = '/api/iam-svc/auth';
const ORIGIN = __ENV.WEB_ORIGIN || 'http://localhost:8088';

export function setup() {
  const tenant = onboardTenant('mfa');
  const store = tenant.stores[0].id;
  return {
    tenant,
    cashier: staffUser(tenant, 'CASHIER', [store]),
    manager: staffUser(tenant, 'MANAGER', [store]),
    shopper: register('mfa-shopper'),
    bystander: register('mfa-bystander'),
    admin: platformAdmin(),
  };
}

/**
 * The next code an authenticator would give that the server has not seen: no code works twice, and
 * one step either side of now is accepted, so a login that answers often walks forward through the
 * steps — and waits for the clock when it has used them all.
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

const me = (token) => call('GET', `${AUTH}/me`, { token });
const status = (token) => must(call('GET', `${AUTH}/mfa`, { token }), 200, 'second-factor status');

/** Sets an authenticator app up for a signed-in login. */
function enrolTotp(token) {
  const begun = must(call('POST', `${AUTH}/mfa/totp`, { token }), 200, 'begin authenticator');
  const app = { secret: begun.secret, lastStep: 0 };
  const confirmed = must(call('POST', `${AUTH}/mfa/totp/confirm`, { token, body: { code: nextCode(app) } }), 200, 'confirm authenticator');
  return { app, recoveryCodes: confirmed.recoveryCodes || [], tokens: confirmed.tokens, otpauthUri: begun.otpauthUri };
}

export default async function ({ tenant, cashier, manager, shopper, bystander, admin }) {
  const owner = tenant.owner;
  // A sign-in that succeeds, to keep the suite's deliberate failures from adding up to a lockout.
  const breather = () => login(bystander);

  // ── an authenticator app ─────────────────────────────────────────────────────────────────────────
  truthy('[+] a login starts with no second factor, and none required of it', status(owner.token).totp === false && status(owner.token).required === false);
  const begun = must(call('POST', `${AUTH}/mfa/totp`, { token: owner.token }), 200, 'begin authenticator');
  truthy('[+] setting one up answers a secret and the URI an app scans', /^[A-Z2-7]{32}$/.test(begun.secret) && begun.otpauthUri.includes(`secret=${begun.secret}`) && begun.otpauthUri.includes('issuer=StoreQL'), begun.otpauthUri);
  truthy('[+] it is no factor until a code confirms it: sign-in still answers tokens', !!data(login(owner)).accessToken);
  expect(call('POST', `${AUTH}/mfa/totp/confirm`, { token: owner.token, body: { code: '000000' } }), '[-] a wrong code confirms nothing', 400, 'MFA_CODE_INVALID');
  const ownerApp = { secret: begun.secret, lastStep: 0 };
  const confirmCode = nextCode(ownerApp);
  const confirmed = must(call('POST', `${AUTH}/mfa/totp/confirm`, { token: owner.token, body: { code: confirmCode } }), 200, 'confirm authenticator');
  truthy('[+] confirming it answers ten recovery codes, once', (confirmed.recoveryCodes || []).length === 10 && confirmed.tokens == null, confirmed);
  expect(call('POST', `${AUTH}/mfa/totp`, { token: owner.token }), '[-] a second app cannot be started over an active one', 409, 'MFA_TOTP_ALREADY_ACTIVE');

  const owed = must(login(owner), 200, 'sign-in with a factor');
  truthy('[+] every sign-in now owes the second factor, and answers no token until it has it', owed.mfaRequired === true && owed.accessToken == null && owed.refreshToken == null && !!owed.mfaToken, owed);
  truthy('[+] it says what the login can answer with', JSON.stringify(owed.mfaMethods) === '["TOTP","RECOVERY_CODE"]', owed.mfaMethods);
  expect(answerSecondFactor(owed.mfaToken, 'TOTP', confirmCode), '[abuse] the code that confirmed the app is spent: no code works twice', 401, 'MFA_CODE_INVALID');
  const signedIn = answerSecondFactor(owed.mfaToken, 'TOTP', nextCode(ownerApp));
  expect(signedIn, '[+] the next code signs in', 200);
  const session = data(signedIn);
  truthy('[+] the token says how its holder was authenticated', JSON.stringify(claims(session.accessToken).amr) === '["pwd","otp"]', claims(session.accessToken).amr);
  expect(me(session.accessToken), '[+] and works', 200);
  expect(answerSecondFactor(owed.mfaToken, 'TOTP', nextCode(ownerApp)), '[-] a waiting sign-in is answered once', 401, 'MFA_CHALLENGE_EXPIRED');
  const renewed = must(call('POST', `${AUTH}/refresh`, { body: { refreshToken: session.refreshToken } }), 200, 'refresh');
  truthy('[+] a renewed session says the same', JSON.stringify(claims(renewed.accessToken).amr) === '["pwd","otp"]', claims(renewed.accessToken).amr);
  breather();

  // ── recovery codes ───────────────────────────────────────────────────────────────────────────────
  const spare = confirmed.recoveryCodes[0];
  const viaRecovery = answerSecondFactor(must(login(owner), 200, 'sign-in').mfaToken, 'RECOVERY_CODE', spare.toLowerCase().replace(/-/g, ' '));
  expect(viaRecovery, '[+] a recovery code signs in, however it is typed', 200);
  truthy('[+] and is counted off', status(data(viaRecovery).accessToken).recoveryCodesLeft === 9);
  const again = must(login(owner), 200, 'sign-in').mfaToken;
  expect(answerSecondFactor(again, 'RECOVERY_CODE', spare), '[abuse] a recovery code works once', 401, 'MFA_CODE_INVALID');
  expect(answerSecondFactor(again, 'SMS', '123456'), '[-] a method the platform does not have', 400, 'MFA_METHOD_UNKNOWN');
  expect(answerSecondFactor('not-a-token', 'TOTP', '123456'), '[-] a sign-in nobody is waiting on', 401, 'MFA_CHALLENGE_EXPIRED');
  expect(answerSecondFactor('x'.repeat(500), 'TOTP', '123456'), '[abuse] an oversized token never reaches the lookup', 400);
  breather();

  // ── the business's rule ──────────────────────────────────────────────────────────────────────────
  const POLICY = `${AUTH}/admin/mfa-policy`;
  expect(call('PUT', POLICY, { token: cashier.token, body: { requiredTiers: [] } }), '[abuse] a cashier does not change the rule', 403);
  expect(call('PUT', POLICY, { token: manager.token, body: { requiredTiers: [] } }), '[abuse] nor does a manager', 403);
  expect(call('GET', POLICY, { token: manager.token }), '[+] a manager may read it', 200);
  expect(call('GET', POLICY), '[-] nobody reads it anonymously', 401);
  expect(call('PUT', POLICY, { token: session.accessToken, body: { requiredTiers: ['JANITOR'] } }), '[-] a tier that does not exist', 400, 'MFA_POLICY_TIER_UNKNOWN');
  const rule = must(call('PUT', POLICY, { token: session.accessToken, body: { requiredTiers: ['MANAGER', 'CASHIER'] } }), 200, 'set the rule');
  truthy('[+] the owner requires a second factor of cashiers and managers', JSON.stringify(rule.requiredTiers) === '["CASHIER","MANAGER"]', rule);

  expect(call('POST', `${AUTH}/refresh`, { body: { refreshToken: cashier.refreshToken } }), '[+] a session that was only a password\'s is not renewed once the rule applies', 401, 'MFA_REQUIRED');
  const mustEnrol = must(login(cashier), 200, 'cashier sign-in under the rule');
  const limited = claims(mustEnrol.accessToken || '');
  truthy('[+] the cashier\'s next sign-in gets a token that says who and nothing else', mustEnrol.mfaEnrolmentRequired === true && mustEnrol.refreshToken == null && limited.scope === 'mfa-enrol' && (limited.roles || []).length === 0 && limited.tenant == null, limited);
  expect(me(mustEnrol.accessToken), '[abuse] which reaches nothing but the second-factor routes: not the login\'s own profile', 403, 'MFA_ENROLMENT_REQUIRED');
  expect(call('GET', '/api/order-svc/orders', { token: mustEnrol.accessToken }), '[abuse] not the order book', 403, 'MFA_ENROLMENT_REQUIRED');
  expect(call('GET', POLICY, { token: mustEnrol.accessToken }), '[abuse] not the rule itself', 403, 'MFA_ENROLMENT_REQUIRED');
  const cashierFactor = enrolTotp(mustEnrol.accessToken);
  const real = claims((cashierFactor.tokens || {}).accessToken || '');
  truthy('[+] setting the factor up with it answers the real session', (real.roles || []).includes('CASHIER') && real.tenant === tenant.tenantId && JSON.stringify(real.amr) === '["pwd","otp"]' && real.scope == null, real);
  truthy('[+] and the recovery codes', cashierFactor.recoveryCodes.length === 10);
  const cashierToken = cashierFactor.tokens.accessToken;
  expect(me(cashierToken), '[+] the real session works', 200);
  truthy('[+] the cashier is told the factor is required of them', status(cashierToken).required === true);
  expect(call('POST', `${AUTH}/mfa/totp/remove`, { token: cashierToken, body: { password: `not-${PASSWORD}` } }), '[-] removing a factor asks for the password', 401, 'INVALID_CREDENTIALS');
  expect(call('POST', `${AUTH}/mfa/totp/remove`, { token: cashierToken, body: { password: PASSWORD } }), '[abuse] and the rule holds the last one in place', 409, 'MFA_REQUIRED_BY_POLICY');
  expect(call('GET', `${AUTH}/me`, { token: cashierToken, headers: { 'X-Auth-Scope': 'mfa-enrol' } }), '[abuse] a scope header sent by a client changes nothing', 200);

  // One login's waiting sign-in is no use with another login's code.
  breather();
  const cashiersWait = must(login(cashier), 200, 'cashier sign-in').mfaToken;
  expect(answerSecondFactor(cashiersWait, 'TOTP', nextCode(ownerApp)), '[abuse] the owner\'s code does not answer the cashier\'s sign-in', 401, 'MFA_CODE_INVALID');
  expect(answerSecondFactor(cashiersWait, 'TOTP', nextCode(cashierFactor.app)), '[+] the cashier\'s own does', 200);

  // ── guessing ─────────────────────────────────────────────────────────────────────────────────────
  const guessed = must(login(owner), 200, 'sign-in to guess at').mfaToken;
  let refusals = 0;
  for (let i = 0; i < 5; i++) {
    if (answerSecondFactor(guessed, 'TOTP', String(100000 + i)).status === 401) refusals++;
    if (i % 2 === 1) breather();
  }
  truthy('[abuse] five guesses are five refusals', refusals === 5, { refusals });
  expect(answerSecondFactor(guessed, 'RECOVERY_CODE', confirmed.recoveryCodes[1]), '[abuse] and the wait is over: even a right answer comes too late', 401, 'MFA_CHALLENGE_EXPIRED');
  breather();
  expect(answerSecondFactor(must(login(owner), 200, 'sign-in').mfaToken, 'RECOVERY_CODE', confirmed.recoveryCodes[1]), '[+] the password opens a new wait, and the right answer works there', 200);

  // ── the lost phone ───────────────────────────────────────────────────────────────────────────────
  const reset = (token, userId) => call('DELETE', `${AUTH}/admin/staff-users/${userId}/mfa`, { token });
  expect(reset(cashierToken, cashier.userId), '[abuse] a cashier resets nobody', 403);
  expect(reset(session.accessToken, owner.userId), '[-] an owner does not reset themselves', 400, 'MFA_RESET_SELF');
  expect(reset(session.accessToken, bystander.userId), '[abuse] nor anybody who is not their staff', 404);
  expect(reset(session.accessToken, cashier.userId), '[+] the owner resets the cashier who lost their phone', 200);
  expect(me(cashierToken), '[+] the cashier\'s token lives out its minutes', 200);
  truthy('[+] and their next sign-in sets a factor up again, because the rule still says so', must(login(cashier), 200, 'cashier after reset').mfaEnrolmentRequired === true);
  expect(reset(admin.token, owner.userId), '[+] the platform administrator resets anybody', 200);
  truthy('[+] the owner, in no required tier, is back to a password', !!must(login(owner), 200, 'owner after reset').accessToken);

  // ── a passkey ────────────────────────────────────────────────────────────────────────────────────
  const key = await SoftwarePasskey.create('localhost');
  const creation = must(call('POST', `${AUTH}/mfa/passkeys/options`, { token: shopper.token }), 200, 'passkey creation options');
  truthy('[+] a shopper may have a passkey too: the options ask for no attestation and a verified person', creation.rpId === 'localhost' && creation.attestation === 'none' && creation.userVerification === 'required' && JSON.stringify(creation.algorithms) === '[-7,-257]', creation);
  const phished = await key.register(creation, 'http://localhost.evil.test');
  expect(call('POST', `${AUTH}/mfa/passkeys`, { token: shopper.token, body: { registrationToken: creation.registrationToken, name: 'Laptop', ...phished } }), '[abuse] a registration made for a look-alike origin is refused', 400, 'MFA_PASSKEY_INVALID');
  const creationAgain = must(call('POST', `${AUTH}/mfa/passkeys/options`, { token: shopper.token }), 200, 'passkey creation options');
  const made = await key.register(creationAgain, ORIGIN);
  expect(call('POST', `${AUTH}/mfa/passkeys`, { token: bystander.token, body: { registrationToken: creationAgain.registrationToken, name: 'Mine now', ...made } }), '[abuse] somebody else holding the registration token cannot finish it as themselves', 400, 'MFA_CHALLENGE_EXPIRED');
  const registered = must(call('POST', `${AUTH}/mfa/passkeys`, { token: shopper.token, body: { registrationToken: creationAgain.registrationToken, name: 'Laptop', ...made } }), 200, 'register passkey');
  truthy('[+] the passkey is registered, and as the first factor brings the recovery codes', (registered.recoveryCodes || []).length === 10);
  const listed = status(shopper.token);
  truthy('[+] it is listed by name, never by key', listed.passkeys.length === 1 && listed.passkeys[0].name === 'Laptop' && !JSON.stringify(listed).includes('publicKey'), listed);

  const passkeyOwed = must(login(shopper), 200, 'shopper sign-in');
  truthy('[+] the shopper\'s sign-in now owes the passkey', passkeyOwed.mfaRequired === true && JSON.stringify(passkeyOwed.mfaMethods) === '["PASSKEY","RECOVERY_CODE"]', passkeyOwed.mfaMethods);
  const request = must(call('POST', `${AUTH}/mfa/login/passkey-options`, { body: { mfaToken: passkeyOwed.mfaToken } }), 200, 'passkey request options');
  truthy('[+] the challenge names the passkey it may be answered with', request.allowCredentials.length === 1 && request.allowCredentials[0] === key.credentialIdText && request.rpId === 'localhost', request);
  const forPhisher = await key.assert(request, 'https://storeql.evil.test');
  expect(call('POST', `${AUTH}/mfa/login`, { body: { mfaToken: passkeyOwed.mfaToken, method: 'PASSKEY', assertion: forPhisher } }), '[abuse] an assertion signed for a phishing site\'s origin is no use here', 401, 'MFA_CODE_INVALID');
  const stranger = await SoftwarePasskey.create('localhost');
  const forged = { ...(await stranger.assert(request, ORIGIN)), credentialId: key.credentialIdText };
  expect(call('POST', `${AUTH}/mfa/login`, { body: { mfaToken: passkeyOwed.mfaToken, method: 'PASSKEY', assertion: forged } }), '[abuse] another authenticator claiming this credential is refused', 401, 'MFA_CODE_INVALID');
  breather();
  const assertion = await key.assert(request, ORIGIN);
  const withPasskey = call('POST', `${AUTH}/mfa/login`, { body: { mfaToken: passkeyOwed.mfaToken, method: 'PASSKEY', assertion } });
  expect(withPasskey, '[+] the passkey signs in', 200);
  truthy('[+] and the token says a hardware key did it', JSON.stringify(claims(data(withPasskey).accessToken).amr) === '["pwd","hwk"]', claims(data(withPasskey).accessToken).amr);
  expect(call('POST', `${AUTH}/mfa/login`, { body: { mfaToken: passkeyOwed.mfaToken, method: 'PASSKEY', assertion } }), '[abuse] the same assertion again', 401, 'MFA_CHALLENGE_EXPIRED');
  expect(call('POST', `${AUTH}/mfa/passkeys/${listed.passkeys[0].id}/remove`, { token: data(withPasskey).accessToken, body: { password: PASSWORD } }), '[+] the shopper removes it, with the password', 200);
  truthy('[+] and is back to a password', !!must(login(shopper), 200, 'shopper after removal').accessToken);

  // ── the platform administrator ───────────────────────────────────────────────────────────────────
  const adminAmr = claims(admin.token).amr;
  truthy('[+] the platform administrator signed in with a second factor', JSON.stringify(adminAmr) === '["pwd","otp"]', adminAmr);
  const adminOwed = must(call('POST', `${AUTH}/platform-login`, { body: { email: admin.email, password: admin.password } }), 200, 'platform sign-in');
  truthy('[+] a right password alone gets the administrator no token', adminOwed.mfaRequired === true && adminOwed.accessToken == null, adminOwed);
  truthy('[+] a factor is required of the administrator', status(admin.token).required === true);
  expect(call('POST', `${AUTH}/mfa/totp/remove`, { token: admin.token, body: { password: admin.password } }), '[abuse] and cannot be removed', 409, 'MFA_REQUIRED_BY_POLICY');

  // Leave the business as it was found: nobody is made to have a factor.
  expect(call('PUT', POLICY, { token: must(login(owner), 200, 'owner').accessToken, body: { requiredTiers: [] } }), '[+] the rule is lifted again', 200);
  breather();
  completed.add(1);
}
