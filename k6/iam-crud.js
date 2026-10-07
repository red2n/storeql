// iam-svc: sign-up, sign-in (a sign-in that names its kind opens only that kind), token rotation,
// password and account lifecycle, staff provisioning and POS sessions (a cashier's own, the open
// sessions a manager's to list, a colleague's session ended only by a manager at the store, with a
// reason) — each with the refusals that keep them safe.
//
//   k6/run.sh iam-crud
import {
  ALL_CHECKS_PASS,
  PASSWORD,
  call,
  claims,
  data,
  expect,
  login,
  onboardTenant,
  platformAdmin,
  register,
  staffUser,
  truthy,
  uniq,
} from './lib/storeql.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS, setupTimeout: '4m' };

const UNKNOWN = '01a0b000-0000-7000-8000-000000000000';

export function setup() {
  const tenant = onboardTenant('iam', { stores: 1 });
  const storeId = tenant.stores[0].id;
  // Two cashiers and a manager at the store, for who may end whose POS session.
  const cashier = staffUser(tenant, 'CASHIER', [storeId]);
  const colleague = staffUser(tenant, 'CASHIER', [storeId]);
  const manager = staffUser(tenant, 'MANAGER', [storeId]);
  return { admin: platformAdmin(), tenant, rival: onboardTenant('iam-rival', { stores: 1 }), cashier, colleague, manager };
}

export default function ({ admin, tenant, rival, cashier, colleague, manager }) {
  // ── sign-up and sign-in ─────────────────────────────────────────────────────
  const email = `iam-${uniq()}@k6.storeql.test`;
  expect(call('POST', '/api/iam-svc/auth/register', { body: { email, password: 'short' } }), '[-] register: password too short', 400, 'VALIDATION_FAILED');
  expect(call('POST', '/api/iam-svc/auth/register', { body: { email: 'nope', password: PASSWORD } }), '[-] register: not an email', 400, 'VALIDATION_FAILED');
  const reg = call('POST', '/api/iam-svc/auth/register', { body: { email, password: PASSWORD } });
  expect(reg, '[+] register', 201);
  expect(call('POST', '/api/iam-svc/auth/register', { body: { email: email.toUpperCase(), password: PASSWORD } }), '[-] register: email taken (any case)', 409, 'USER_ALREADY_EXISTS');
  const user = { email, password: PASSWORD, token: data(reg).accessToken, refreshToken: data(reg).refreshToken };
  truthy('[+] a sign-up is a CUSTOMER', (claims(user.token).roles || []).includes('CUSTOMER'), claims(user.token));

  // A sign-in that names its kind opens only that kind: an address holding only the other kind is
  // answered as an unknown one. Each refusal is followed by a sign-in that works, so the gateway's
  // count of failed sign-ins from this host never builds up.
  const signInAs = (who, accountType) => call('POST', '/api/iam-svc/auth/login', { body: { email: who.email, password: who.password, accountType } });
  const asShopper = signInAs(user, 'CUSTOMER');
  expect(asShopper, '[+] login: a shopper address signs in as a CUSTOMER', 200);
  truthy('[+] ...and the token is a shopper\'s, of no business', (claims(data(asShopper).accessToken).roles || []).includes('CUSTOMER') && !claims(data(asShopper).accessToken).tenant, claims(data(asShopper).accessToken));
  const shopperAsStaff = signInAs(user, 'STAFF');
  expect(shopperAsStaff, '[-] login: a shopper address signing in as STAFF opens nothing', 401, 'INVALID_CREDENTIALS');
  truthy('[-] ...and no token comes back', !data(shopperAsStaff).accessToken && !data(shopperAsStaff).refreshToken);
  const asStaff = signInAs(tenant.owner, 'STAFF');
  expect(asStaff, '[+] login: a staff address signs in as STAFF', 200);
  truthy('[+] ...and the token is the business\'s', claims(data(asStaff).accessToken).tenant === tenant.tenantId, claims(data(asStaff).accessToken));
  const staffAsShopper = signInAs(tenant.owner, 'CUSTOMER');
  expect(staffAsShopper, '[-] login: a staff address signing in as CUSTOMER opens nothing', 401, 'INVALID_CREDENTIALS');
  truthy('[-] ...and no token comes back', !data(staffAsShopper).accessToken && !data(staffAsShopper).refreshToken);
  expect(signInAs(user, 'SOMETHING'), '[-] login: a kind that is neither is refused', 400, 'VALIDATION_FAILED');

  expect(login(user), '[+] login', 200);
  expect(call('POST', '/api/iam-svc/auth/login', { body: { email, password: 'Wrong-Passw0rd!' } }), '[-] login: wrong password', 401, 'INVALID_CREDENTIALS');
  expect(call('POST', '/api/iam-svc/auth/login', { body: { email: `nobody-${uniq()}@k6.storeql.test`, password: PASSWORD } }), '[-] login: unknown user', 401, 'INVALID_CREDENTIALS');
  expect(call('POST', '/api/iam-svc/auth/platform-login', { body: { email, password: PASSWORD } }), '[-] platform-login: a customer is not a platform admin', 401, 'INVALID_CREDENTIALS');
  expect(call('POST', '/api/iam-svc/auth/login', { body: { email: admin.email, password: admin.password } }), '[-] login: platform admin must use platform-login', 401, 'INVALID_CREDENTIALS');
  // Four refused sign-ins in a row; a fifth failure from this host, the refusals of the next block
  // included, would lock it for fifteen minutes. A sign-in that works clears the count.
  expect(login(user), '[+] login still works after those refusals', 200);
  expect(
    call('POST', '/api/iam-svc/bootstrap/admin', { body: { email: `second-admin-${uniq()}@k6.storeql.test`, password: PASSWORD } }),
    '[-] bootstrap: only once per deployment',
    409,
    'BOOTSTRAP_ALREADY_DONE'
  );

  const me = call('GET', '/api/iam-svc/auth/me', { token: user.token });
  expect(me, '[+] who am I', 200);
  truthy('[+] who am I: my email and roles', data(me).email === email && (data(me).roles || []).includes('CUSTOMER'), data(me));
  expect(call('GET', '/api/iam-svc/auth/me'), '[-] who am I: no token', 401, 'UNAUTHORIZED');
  expect(call('GET', '/api/iam-svc/auth/me', { token: `${user.token}x` }), '[-] who am I: tampered token', 401, 'UNAUTHORIZED');

  // ── refresh rotation and theft detection ────────────────────────────────────
  const spent = user.refreshToken;
  const rotated = call('POST', '/api/iam-svc/auth/refresh', { body: { refreshToken: spent } });
  expect(rotated, '[+] refresh rotates the token pair', 200);
  const fresh = data(rotated).refreshToken;
  truthy('[+] refresh returns a new refresh token', fresh && fresh !== spent);
  expect(call('POST', '/api/iam-svc/auth/refresh', { body: { refreshToken: spent } }), '[-] refresh: a spent token is refused', 401, 'INVALID_REFRESH');
  expect(call('POST', '/api/iam-svc/auth/refresh', { body: { refreshToken: fresh } }), '[-] refresh: replaying a spent token revoked the whole family', 401, 'INVALID_REFRESH');
  expect(call('POST', '/api/iam-svc/auth/refresh', { body: {} }), '[-] refresh: token required', 400, 'VALIDATION_FAILED');

  // ── logout ──────────────────────────────────────────────────────────────────
  const session = data(login(user));
  expect(call('POST', '/api/iam-svc/auth/logout', { token: session.accessToken, body: { refreshToken: session.refreshToken } }), '[+] logout', 200);
  expect(call('POST', '/api/iam-svc/auth/refresh', { body: { refreshToken: session.refreshToken } }), '[-] refresh after logout', 401, 'INVALID_REFRESH');

  // ── password change ─────────────────────────────────────────────────────────
  const beforeChange = data(login(user));
  const newPassword = 'K6-Changed-Passw0rd!';
  expect(
    call('PUT', '/api/iam-svc/auth/change-password', { token: beforeChange.accessToken, body: { currentPassword: 'Wrong-Passw0rd!', newPassword } }),
    '[-] change password: current password wrong',
    401,
    'INVALID_CREDENTIALS'
  );
  expect(
    call('PUT', '/api/iam-svc/auth/change-password', { token: beforeChange.accessToken, body: { currentPassword: PASSWORD, newPassword: 'short' } }),
    '[-] change password: new password too short',
    400,
    'VALIDATION_FAILED'
  );
  expect(
    call('PUT', '/api/iam-svc/auth/change-password', { token: beforeChange.accessToken, body: { currentPassword: PASSWORD, newPassword } }),
    '[+] change password',
    200
  );
  expect(login(user), '[-] old password no longer logs in', 401, 'INVALID_CREDENTIALS');
  expect(call('POST', '/api/iam-svc/auth/refresh', { body: { refreshToken: beforeChange.refreshToken } }), '[-] tokens issued before the change are revoked', 401, 'INVALID_REFRESH');
  user.password = newPassword;
  expect(login(user), '[+] new password logs in', 200);

  // ── account deletion (customers only) ───────────────────────────────────────
  const leaving = register('iam-leaving');
  expect(call('POST', '/api/iam-svc/auth/delete-account', { token: leaving.token, body: { password: 'Wrong-Passw0rd!' } }), '[-] delete account: password asked again', 401, 'INVALID_CREDENTIALS');
  expect(call('POST', '/api/iam-svc/auth/delete-account', { token: leaving.token, body: { password: PASSWORD } }), '[+] delete account', 200);
  expect(login(leaving), '[-] a deleted account cannot log in', 401, 'INVALID_CREDENTIALS');
  expect(
    call('POST', '/api/iam-svc/auth/delete-account', { token: tenant.owner.token, body: { password: PASSWORD } }),
    '[-] delete account: staff accounts are not deleted here',
    403,
    'ACCOUNT_MANAGED_BY_EMPLOYER'
  );

  // ── staff provisioning ──────────────────────────────────────────────────────
  const staffEmail = `iam-staff-${uniq()}@k6.storeql.test`;
  expect(
    call('POST', '/api/iam-svc/auth/admin/staff-users', { token: user.token, body: { email: staffEmail, password: PASSWORD } }),
    '[-] provision staff: a customer cannot',
    403,
    'FORBIDDEN'
  );
  const provisioned = call('POST', '/api/iam-svc/auth/admin/staff-users', { token: tenant.owner.token, body: { email: staffEmail, password: PASSWORD } });
  expect(provisioned, '[+] provision a staff login', 200);
  truthy('[+] provisioned login has an id', data(provisioned).userId, data(provisioned));
  expect(
    call('POST', '/api/iam-svc/auth/admin/staff-users', { token: tenant.owner.token, body: { email: staffEmail, password: 'short' } }),
    '[-] provision staff: password too short',
    400,
    'VALIDATION_FAILED'
  );

  // ── POS sessions ────────────────────────────────────────────────────────────
  const t = tenant.owner.token;
  const storeId = tenant.stores[0].id;
  expect(call('POST', '/api/iam-svc/auth/pos/sessions', { token: t, body: { idleTimeoutSeconds: 300 } }), '[-] POS session: store required', 400, 'VALIDATION_FAILED');
  expect(
    call('POST', '/api/iam-svc/auth/pos/sessions', { token: t, body: { storeId, idleTimeoutSeconds: 10 } }),
    '[-] POS session: idle timeout below a minute',
    400,
    'POS_SESSION_INVALID_TIMEOUT'
  );
  expect(
    call('POST', '/api/iam-svc/auth/pos/sessions', { token: user.token, body: { storeId, idleTimeoutSeconds: 300 } }),
    '[-] POS session: a customer cannot open one',
    403,
    'FORBIDDEN'
  );
  const started = call('POST', '/api/iam-svc/auth/pos/sessions', { token: t, body: { storeId, idleTimeoutSeconds: 300 } });
  expect(started, '[+] POS session opens', 201);
  const sessionId = data(started).id;
  truthy('[+] POS session is ACTIVE with the timeout asked for', data(started).status === 'ACTIVE' && data(started).idleTimeoutSeconds === 300, data(started));
  expect(call('PUT', `/api/iam-svc/auth/pos/sessions/${sessionId}/activity`, { token: t }), '[+] POS session activity', 204);
  const active = call('GET', '/api/iam-svc/auth/pos/sessions', { token: t });
  expect(active, '[+] list active POS sessions', 200);
  truthy('[+] the open session is listed', (data(active) || []).some((s) => s.id === sessionId), data(active));
  truthy('[-] a rival does not see it', !(data(call('GET', '/api/iam-svc/auth/pos/sessions', { token: rival.owner.token })) || []).some((s) => s.id === sessionId));
  expect(call('PUT', `/api/iam-svc/auth/pos/sessions/${sessionId}/activity`, { token: rival.owner.token }), '[-] a rival cannot touch it', 404, 'POS_SESSION_NOT_FOUND');
  expect(call('DELETE', `/api/iam-svc/auth/pos/sessions/${sessionId}`, { token: rival.owner.token }), '[-] a rival cannot end it', 404, 'POS_SESSION_NOT_FOUND');
  expect(call('DELETE', `/api/iam-svc/auth/pos/sessions/${sessionId}`, { token: t }), '[+] POS session ends', 204);
  expect(call('PUT', `/api/iam-svc/auth/pos/sessions/${sessionId}/activity`, { token: t }), '[-] activity on an ended session', 409, 'POS_SESSION_NOT_ACTIVE');
  expect(call('PUT', `/api/iam-svc/auth/pos/sessions/${UNKNOWN}/activity`, { token: t }), '[-] activity on an unknown session', 404, 'POS_SESSION_NOT_FOUND');

  // Whose session it is: a cashier reads and ends their own; the open sessions are management's to
  // list, and a colleague's session is ended only by a manager at the store, who says why.
  const sessions = '/api/iam-svc/auth/pos/sessions';
  const openAt = (who) => call('POST', sessions, { token: who.token, body: { storeId, idleTimeoutSeconds: 300 } });
  const mineOf = (who) => data(call('GET', `${sessions}/mine`, { token: who.token })) || [];
  const theirs = openAt(cashier);
  expect(theirs, '[+] a cashier opens a POS session at their store', 201);
  const theirId = data(theirs).id;
  const colleagues = openAt(colleague);
  expect(colleagues, '[+] ...and so does a colleague', 201);
  expect(call('GET', sessions, { token: cashier.token }), '[-] a cashier cannot list who is signed in at the tills', 403, 'FORBIDDEN');
  const mine = call('GET', `${sessions}/mine`, { token: cashier.token });
  expect(mine, '[+] a cashier reads their own open sessions', 200);
  truthy('[+] ...theirs, and nobody else\'s', (data(mine) || []).length === 1 && data(mine)[0].id === theirId && data(mine)[0].userId === cashier.userId, data(mine));
  const managed = call('GET', `${sessions}?storeId=${storeId}`, { token: manager.token });
  expect(managed, '[+] a manager lists the open sessions at their store', 200);
  truthy('[+] ...both cashiers\' among them', [theirId, data(colleagues).id].every((id) => (data(managed) || []).some((x) => x.id === id)), data(managed));
  expect(call('GET', `${sessions}?storeId=${rival.stores[0].id}`, { token: manager.token }), "[-] a manager cannot list a store that is not theirs", 403, 'STORE_ACCESS_DENIED');
  expect(call('DELETE', `${sessions}/${theirId}`, { token: colleague.token }), "[-] a cashier cannot end a colleague's session", 403, 'POS_SESSION_NOT_YOURS');
  expect(call('DELETE', `${sessions}/${theirId}?reason=${encodeURIComponent('k6 not mine')}`, { token: colleague.token }), '[-] ...nor with a reason', 403, 'POS_SESSION_NOT_YOURS');
  expect(call('DELETE', `${sessions}/${theirId}`, { token: manager.token }), "[-] a manager ending someone else's session must say why", 400, 'POS_SESSION_REASON_REQUIRED');
  expect(call('DELETE', `${sessions}/${theirId}?reason=${encodeURIComponent('x'.repeat(201))}`, { token: manager.token }), '[-] ...in no more than 200 characters', 400, 'POS_SESSION_REASON_REQUIRED');
  expect(call('DELETE', `${sessions}/${theirId}?reason=${encodeURIComponent('k6 not ours')}`, { token: rival.owner.token }), "[-] a rival's owner cannot end it, reason or not", 404, 'POS_SESSION_NOT_FOUND');
  expect(call('PUT', `${sessions}/${theirId}/activity`, { token: cashier.token }), '[+] after every refusal the session is still open', 204);
  expect(call('DELETE', `${sessions}/${theirId}?reason=${encodeURIComponent('k6 left the till unattended')}`, { token: manager.token }), "[+] a manager at the store ends the cashier's session, with a reason", 204);
  expect(call('PUT', `${sessions}/${theirId}/activity`, { token: cashier.token }), '[-] ...and it is over', 409, 'POS_SESSION_NOT_ACTIVE');
  truthy('[+] ...gone from the cashier\'s own, the colleague\'s untouched', mineOf(cashier).length === 0 && mineOf(colleague).some((x) => x.id === data(colleagues).id), { cashier: mineOf(cashier), colleague: mineOf(colleague) });
  expect(call('DELETE', `${sessions}/${data(colleagues).id}`, { token: colleague.token }), '[+] a cashier ends their own session with no reason', 204);

  expect(call('POST', '/api/iam-svc/auth/pos/sessions/sweep', { token: t }), '[-] idle sweep: owners cannot run it', 403, 'FORBIDDEN');
  expect(call('POST', '/api/iam-svc/auth/pos/sessions/sweep', { token: admin.token }), '[+] idle sweep: platform admin', 200);
}
