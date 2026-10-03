// Asymmetric token signing with key rotation (20.15; RFC 7517, RFC 8725), through the gateway:
// an access token is RS256 and names the key that signed it; the public halves are published as a
// key set anybody may read, and nothing in it or in the key register is key material; the gateway
// verifies against that set and holds no secret. Refused: a token HMAC-signed with the public key
// (algorithm confusion), a token that says `none`, a payload raised after signing, a key id nobody
// published, a flood of them. Rotation: only the platform administrator; the new key is published
// at once and signs after its lead time, tokens signed before it go on working, and the first
// token the new key signs is accepted by the gateway without a restart.
//
//   k6/run.sh token-signing-flow
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';
import { sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { ALL_CHECKS_PASS, call, data, expect, login, must, onboardTenant, platformAdmin, poll, staffUser, truthy, uniq } from './lib/storeql.js';

const completed = new Counter('flow_completed');
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
  setupTimeout: '3m',
};

const AUTH = '/api/iam-svc/auth';
const JWKS = `${AUTH}/.well-known/jwks.json`;
const KEYS = `${AUTH}/admin/signing-keys`;
// How long a rotated key is published before it signs (storeql.jwt.publish-lead-seconds, 60).
const LEAD_SECONDS = Number(__ENV.JWT_PUBLISH_LEAD_SECONDS || 60);

const b64 = (s) => encoding.b64encode(s, 'rawurl');
const part = (token, i) => JSON.parse(encoding.b64decode(token.split('.')[i], 'rawurl', 's'));
const keySet = () => {
  const res = call('GET', JWKS);
  try {
    return { res, keys: JSON.parse(res.body).keys || [] };
  } catch (_) {
    return { res, keys: [] };
  }
};
const me = (token) => call('GET', `${AUTH}/me`, { token });

export function setup() {
  const tenant = onboardTenant('token-signing');
  const cashier = staffUser(tenant, 'CASHIER', [tenant.stores[0].id]);
  return { tenant, cashier, admin: platformAdmin() };
}

export default function ({ tenant, cashier, admin }) {
  const owner = tenant.owner;

  // ── what a token is, and what is published ───────────────────────────────────────────────────────
  const header = part(owner.token, 0);
  truthy('[+] an access token is RS256', header.alg === 'RS256', header);
  truthy('[+] and names the key that signed it', typeof header.kid === 'string' && header.kid.length > 0, header);

  const published = keySet();
  expect(published.res, '[+] the key set is read without a token', 200);
  truthy('[+] it says how long it may be cached', /max-age=\d+/.test(published.res.headers['Cache-Control'] || ''), published.res.headers);
  const signingKey = published.keys.find((k) => k.kid === header.kid);
  truthy('[+] the key the token names is in it', !!signingKey, { kid: header.kid, kids: published.keys.map((k) => k.kid) });
  truthy(
    '[+] every key is an RS256 signing key with a modulus and an exponent',
    published.keys.length > 0 && published.keys.every((k) => k.kty === 'RSA' && k.alg === 'RS256' && k.use === 'sig' && k.n && k.e),
    published.keys.map((k) => ({ kty: k.kty, alg: k.alg, use: k.use })),
  );
  truthy(
    '[-] no private parameter is ever published',
    published.keys.every((k) => ['d', 'p', 'q', 'dp', 'dq', 'qi', 'k'].every((f) => k[f] === undefined)) && !/private|sealed/i.test(published.res.body),
    Object.keys(published.keys[0] || {}),
  );
  expect(me(owner.token), '[+] the gateway accepts the token against that set', 200);

  // ── what is not ours is refused ──────────────────────────────────────────────────────────────────
  const claimsNow = part(owner.token, 1);
  const raised = { ...claimsNow, roles: ['PLATFORM_ADMIN'], type: 'PLATFORM' };
  const hmacToken = (secret, kid = header.kid) => {
    const signingInput = `${b64(JSON.stringify({ alg: 'HS256', typ: 'JWT', kid }))}.${b64(JSON.stringify(raised))}`;
    return `${signingInput}.${crypto.hmac('sha256', secret, signingInput, 'base64rawurl')}`;
  };
  // Algorithm confusion: the public key is public, so anyone can HMAC with it. Every form of it a
  // careless verifier might use as "the key": the modulus bytes, the modulus text, the whole set.
  const confused = [encoding.b64decode((signingKey || {}).n || 'AA', 'rawurl'), (signingKey || {}).n || 'x', published.res.body];
  confused.forEach((secret, i) => {
    const res = call('GET', KEYS, { token: hmacToken(secret) });
    expect(res, `[abuse] an HS256 token keyed with the public key (form ${i + 1}) is refused`, 401);
  });
  const none = `${b64(JSON.stringify({ alg: 'none', typ: 'JWT', kid: header.kid }))}.${b64(JSON.stringify(raised))}.`;
  expect(call('GET', KEYS, { token: none }), '[abuse] a token that says it needs no signature is refused', 401);
  expect(call('GET', KEYS, { token: `${none}${owner.token.split('.')[2]}` }), '[abuse] as it is with a borrowed signature', 401);

  const [h, , sig] = owner.token.split('.');
  expect(call('GET', KEYS, { token: `${h}.${b64(JSON.stringify(raised))}.${sig}` }), '[abuse] a payload raised to platform administrator after signing is refused', 401);
  const otherKid = b64(JSON.stringify({ ...header, kid: `stranger-${uniq()}` }));
  expect(me(`${otherKid}.${owner.token.split('.')[1]}.${sig}`), '[-] a key id nobody published is refused', 401, 'UNAUTHORIZED');
  const noKid = b64(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
  expect(me(`${noKid}.${owner.token.split('.')[1]}.${sig}`), '[-] as is a token that names no key', 401, 'UNAUTHORIZED');
  expect(me('not.a.token'), '[-] and one that is no token at all', 401, 'UNAUTHORIZED');

  let floodRefused = 0;
  for (let i = 0; i < 40; i++) {
    const kid = b64(JSON.stringify({ alg: 'RS256', typ: 'JWT', kid: `flood-${uniq()}` }));
    if (me(`${kid}.${owner.token.split('.')[1]}.${sig}`).status === 401) floodRefused++;
  }
  truthy('[abuse] forty tokens naming forty unknown keys are forty refusals', floodRefused === 40, { floodRefused });
  expect(me(owner.token), '[+] and a real token still verifies after the flood', 200);

  // ── the key set is read-only, the register is the platform administrator's ───────────────────────
  for (const method of ['POST', 'PUT', 'DELETE']) {
    const res = call(method, JWKS, { body: { keys: [] } });
    truthy(`[abuse] the key set cannot be written with ${method}`, [401, 403, 404, 405].includes(res.status), { status: res.status });
  }
  expect(call('GET', KEYS), '[-] the key register needs a token', 401, 'UNAUTHORIZED');
  expect(call('GET', KEYS, { token: owner.token }), '[-] an owner does not read the key register', 403, 'FORBIDDEN');
  expect(call('POST', `${KEYS}/rotate`), '[-] rotation needs a token', 401, 'UNAUTHORIZED');
  expect(call('POST', `${KEYS}/rotate`, { token: owner.token }), '[abuse] an owner does not rotate the platform’s key', 403);
  expect(call('POST', `${KEYS}/rotate`, { token: cashier.token }), '[abuse] nor does a cashier', 403);

  const listed = call('GET', KEYS, { token: admin.token });
  const register = must(listed, 200, 'signing key register');
  truthy('[+] the platform administrator sees one key signing', register.filter((k) => k.status === 'ACTIVE').length === 1, register);
  truthy('[-] and no key material, sealed or public', !/private|sealed|publicKey|"n"\s*:/i.test(listed.body), { body: String(listed.body).slice(0, 200) });

  // ── rotation ─────────────────────────────────────────────────────────────────────────────────────
  const before = keySet().keys.map((k) => k.kid);
  const rotated = call('POST', `${KEYS}/rotate`, { token: admin.token });
  const next = must(rotated, 201, 'rotate');
  truthy('[+] rotation makes a new key the active one', next.status === 'ACTIVE' && !before.includes(next.kid), next);

  const afterRotation = keySet().keys.map((k) => k.kid);
  truthy('[+] it is published at once, beside the key it replaces', afterRotation.includes(next.kid) && afterRotation.includes(header.kid), afterRotation);
  expect(me(owner.token), '[+] a token signed before the rotation goes on working', 200);

  const during = data(login(owner)).accessToken || '';
  truthy(
    '[+] published before it is used: a login inside the lead time is signed by a key already known',
    during !== '' && before.includes(part(during, 0).kid),
    { kid: during && part(during, 0).kid },
  );
  expect(me(during), '[+] and is accepted', 200);

  let fresh = '';
  const took = poll(LEAD_SECONDS + 60, () => {
    fresh = data(login(owner)).accessToken || '';
    return fresh !== '' && part(fresh, 0).kid === next.kid;
  }, 5);
  truthy('[+] after the lead time the new key signs', took >= 0, { took, kid: fresh && part(fresh, 0).kid, want: next.kid });
  expect(me(fresh), '[+] the gateway accepts the first token it signed, with no restart', 200);
  expect(me(owner.token), '[+] while the token the old key signed still works', 200);
  expect(call('GET', KEYS, { token: admin.token }), '[+] as does the administrator’s own', 200);

  const registerNow = must(call('GET', KEYS, { token: admin.token }), 200, 'signing key register after rotation');
  const old = registerNow.find((k) => k.kid === header.kid) || {};
  truthy('[+] the replaced key is retiring, with the moment it began', old.status === 'RETIRING' && !!old.retiringAt, old);
  truthy('[+] and still exactly one key is active', registerNow.filter((k) => k.status === 'ACTIVE').length === 1, registerNow.map((k) => k.status));

  // A forged token naming the new key is no better than one naming the old.
  expect(call('GET', KEYS, { token: hmacToken(published.res.body, next.kid) }), '[abuse] an HS256 token naming the new key is refused too', 401);
  sleep(0.1);

  completed.add(1);
}
