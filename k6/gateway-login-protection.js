// Gateway brute-force protection: failed logins lock out both the account and the client IP.
//
// Five failed logins (401, or 403 such as a suspended tenant) block the account and the IP for
// storeql.gateway.brute-force.block-minutes (15). While blocked, the gateway answers 429
// LOGIN_LOCKED without asking iam-svc — even for the right password, and for any account from
// that IP. A success clears both counters.
//
// This locks out the machine running k6. k6/run.sh clears the lockout in the local Redis
// afterwards; against any other stack, logins from this host stay blocked for 15 minutes.
//
//   k6/run.sh gateway-login-protection
import { ALL_CHECKS_PASS, PASSWORD, call, expect, login, register, truthy } from './lib/storeql.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS };

const MAX_FAILURES = Number(__ENV.BRUTE_FORCE_MAX_FAILURES || 5);
const BLOCK_MINUTES = Number(__ENV.BRUTE_FORCE_BLOCK_MINUTES || 15);

export function setup() {
  return { victim: register('lockout-victim'), bystander: register('lockout-bystander') };
}

export default function ({ victim, bystander }) {
  expect(login(victim), 'the account logs in before any failure', 200);
  expect(login(bystander), 'so does another account', 200);

  const wrong = { email: victim.email, password: `not-${PASSWORD}` };
  for (let attempt = 1; attempt <= MAX_FAILURES; attempt++) {
    expect(call('POST', '/api/iam-svc/auth/login', { body: wrong }), `failed login ${attempt} of ${MAX_FAILURES} is a plain 401`, 401, 'INVALID_CREDENTIALS');
  }

  const locked = call('POST', '/api/iam-svc/auth/login', { body: wrong });
  expect(locked, 'the next attempt is locked out', 429, 'LOGIN_LOCKED');
  truthy('Retry-After tells the client how long', Number(locked.headers['Retry-After']) === BLOCK_MINUTES * 60, locked.headers);
  expect(login(victim), 'the right password is refused while locked', 429, 'LOGIN_LOCKED');
  expect(login(bystander), 'another account from the same IP is refused too', 429, 'LOGIN_LOCKED');
  expect(call('POST', '/api/iam-svc/auth/refresh', { body: { refreshToken: bystander.refreshToken } }), 'token refresh is not a login and still works', 200);
  expect(call('POST', '/api/iam-svc/auth/register', { body: { email: `after-lockout-${Date.now()}@k6.storeql.test`, password: PASSWORD } }), 'registration still works', 201);
  // Starting a business is a sign-up too, not a login: the lockout leaves it alone, as it does the
  // shopper's (both sit under the per-IP rate limit every path has).
  expect(call('POST', '/api/iam-svc/auth/register/business', { body: { email: `business-after-lockout-${Date.now()}@k6.storeql.test`, password: PASSWORD } }), 'starting a business still works', 201);
}
