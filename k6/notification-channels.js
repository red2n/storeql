// Notification channels beyond email (13.7), through the gateway: a text to a number and a push to
// the devices a shopper registered from the storefront, both on the log with their channel; the
// shop reading which channels it has — and the refusals: the wrong number, the wrong channel,
// marketing where consent cannot stand, a guest registering, a shopper sending, a rival shop;
// and the abuse: a full device list, the same token twenty times, thirty texts to a bad number.
//
//   k6/run.sh notification-channels
import http from 'k6/http';
import {
  ALL_CHECKS_PASS,
  BASE,
  call,
  data,
  errorCode,
  expect,
  newId,
  onboardTenant,
  register,
  truthy,
} from './lib/storeql.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS, setupTimeout: '3m' };

const SEND = '/api/notification-svc/notifications/send';
const DEVICES = '/api/notification-svc/notifications/devices';
const LOG = '/api/notification-svc/admin/notifications';

export function setup() {
  const tenant = onboardTenant('channels', { stores: 1 });
  const rival = onboardTenant('channels-rival', { stores: 1 });
  return { tenant, rival, shopper: register('channels-shopper'), other: register('channels-other') };
}

export default function ({ tenant, rival, shopper, other }) {
  const t = tenant.owner.token;
  const shop = { storefront: tenant.tenantId };
  const list = (res) => { const d = data(res); return Array.isArray(d) ? d : []; };
  const token = (seed) => `tok-${seed}-${'x'.repeat(48)}`;

  // ── what this deployment can send on ─────────────────────────────────────────
  const channels = call('GET', `${LOG}/channels`, { token: t });
  expect(channels, '[+] the shop reads its channels', 200);
  truthy('[+] EMAIL, SMS and PUSH, the last two simulated here', ['EMAIL', 'SMS', 'PUSH'].every((c) => list(channels).some((r) => r.channel === c)) && list(channels).filter((r) => r.channel !== 'EMAIL').every((r) => r.provider === 'SIMULATED' && r.configured), JSON.stringify(list(channels)));
  expect(call('GET', `${LOG}/channels`, { token: shopper.token, ...shop }), '[-] a shopper cannot', 403, 'FORBIDDEN');

  // ── SMS ──────────────────────────────────────────────────────────────────────
  const number = '+447700900123';
  const sms = call('POST', SEND, { token: t, body: { channel: 'SMS', recipient: number, subject: 'Your order', body: 'Your order is ready to collect.', type: 'ORDER_READY', eventId: newId() } });
  expect(sms, '[+] a text goes to the number', 202);
  truthy('[+] ...and says which channel', data(sms).channel === 'SMS', JSON.stringify(data(sms)));
  const smsLog = call('GET', `${LOG}?channel=SMS&recipient=${encodeURIComponent(number)}`, { token: t });
  expect(smsLog, '[+] the log, narrowed to SMS', 200);
  truthy('[+] ...shows it on the SMS channel', list(smsLog).some((n) => n.channel === 'SMS' && n.recipient === number), JSON.stringify(list(smsLog)));
  expect(call('POST', SEND, { token: t, body: { channel: 'SMS', recipient: '07700 900123', subject: 's', body: 'b' } }), '[-] a number that is not E.164', 400, 'SMS_RECIPIENT_INVALID');
  expect(call('POST', SEND, { token: t, body: { channel: 'SMS', recipient: number, subject: 's', body: 'x'.repeat(1601) } }), '[-] a body over 1600 characters', 400, 'SMS_BODY_TOO_LONG');
  expect(call('POST', SEND, { token: t, body: { channel: 'PIGEON', recipient: number, subject: 's', body: 'b' } }), '[-] an unknown channel', 400, 'CHANNEL_UNKNOWN');
  expect(call('POST', SEND, { token: t, body: { channel: 'SMS', recipient: number, subject: 's', body: '20% off', category: 'MARKETING', customerId: newId() } }), '[-] marketing by SMS with no consent on record', 409, 'MARKETING_CONSENT_MISSING');
  expect(call('POST', SEND, { token: shopper.token, ...shop, body: { channel: 'SMS', recipient: number, subject: 's', body: 'b' } }), '[-] a shopper cannot send', 403, 'FORBIDDEN');

  // ── push: the shopper registers a device from the storefront ─────────────────
  expect(call('POST', DEVICES, { ...shop, body: { platform: 'ANDROID', token: token('guest') } }), '[-] a guest cannot register a device', 401, 'UNAUTHORIZED');
  expect(call('POST', DEVICES, { token: shopper.token, ...shop, body: { platform: 'PALM', token: token('a') } }), '[-] an unknown platform', 400, 'DEVICE_PLATFORM_UNKNOWN');
  expect(call('POST', DEVICES, { token: shopper.token, ...shop, body: { platform: 'ANDROID', token: 'short' } }), '[-] a token that is too short', 400, 'VALIDATION_FAILED');
  expect(call('POST', SEND, { token: t, body: { channel: 'PUSH', recipient: shopper.userId, subject: 'Ready', body: 'Collect it.' } }), '[-] a push to a login with no device', 409, 'PUSH_NO_DEVICE');
  const reg = call('POST', DEVICES, { token: shopper.token, ...shop, body: { platform: 'android', token: token('phone') } });
  expect(reg, '[+] the shopper registers their phone', 201);
  truthy('[+] ...and the whole token is never shown back', data(reg).platform === 'ANDROID' && !JSON.stringify(data(reg)).includes('x'.repeat(48)), JSON.stringify(data(reg)));
  const deviceId = data(reg).id;
  expect(call('POST', DEVICES, { token: shopper.token, ...shop, body: { platform: 'ANDROID', token: token('phone') } }), '[+] the same token again refreshes it', 201);
  truthy('[+] ...as one device', list(call('GET', DEVICES, { token: shopper.token, ...shop })).length === 1);
  const pushed = call('POST', SEND, { token: t, body: { channel: 'PUSH', recipient: shopper.userId, subject: 'Ready', body: 'Collect it.', type: 'ORDER_READY', eventId: newId() } });
  expect(pushed, '[+] the shop pushes to the shopper', 202);
  truthy('[+] ...on the PUSH channel', data(pushed).channel === 'PUSH');
  truthy('[+] the log shows it', list(call('GET', `${LOG}?channel=PUSH&recipient=${shopper.userId}`, { token: t })).length >= 1);
  expect(call('POST', SEND, { token: t, body: { channel: 'PUSH', recipient: shopper.userId, subject: 'Offers', body: '20% off', category: 'MARKETING', customerId: newId() } }), '[-] marketing cannot go by push', 409, 'MARKETING_CHANNEL_UNSUPPORTED');
  expect(call('DELETE', `${DEVICES}/${deviceId}`, { token: other.token, ...shop }), '[-] another shopper cannot remove it', 404, 'DEVICE_NOT_FOUND');
  truthy('[-] another shopper does not see it', list(call('GET', DEVICES, { token: other.token, ...shop })).length === 0);
  truthy('[-] the rival shop sees no such device either', list(call('GET', DEVICES, { token: shopper.token, storefront: rival.tenantId })).length === 0);
  expect(call('POST', SEND, { token: rival.owner.token, body: { channel: 'PUSH', recipient: shopper.userId, subject: 'x', body: 'y' } }), '[-] and cannot push to it', 409, 'PUSH_NO_DEVICE');
  expect(call('DELETE', `${DEVICES}/${deviceId}`, { token: shopper.token, ...shop }), '[+] the shopper removes their phone', 204);
  expect(call('POST', SEND, { token: t, body: { channel: 'PUSH', recipient: shopper.userId, subject: 'Ready', body: 'b' } }), '[-] and nothing reaches it any more', 409, 'PUSH_NO_DEVICE');

  // ── abuse ────────────────────────────────────────────────────────────────────
  for (let i = 0; i < 10; i++) expect(call('POST', DEVICES, { token: other.token, ...shop, body: { platform: 'IOS', token: token(`d${i}`) } }), `[+] device ${i + 1} of 10`, 201);
  expect(call('POST', DEVICES, { token: other.token, ...shop, body: { platform: 'IOS', token: token('d11') } }), '[-] an eleventh device is refused', 409, 'PUSH_DEVICE_LIMIT');
  const same = http.batch(Array.from({ length: 20 }, () => ['POST', `${BASE}${DEVICES}`, JSON.stringify({ platform: 'WEB', token: token('d3') }), { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${other.token}`, 'X-Storefront-Tenant': tenant.tenantId }, tags: { name: 'POST /api/notification-svc/notifications/devices (same token)' } }]));
  truthy('[+] the same token twenty times at once is still one device, and still ten in all', same.every((r) => r.status === 201) && list(call('GET', DEVICES, { token: other.token, ...shop })).length === 10, same.map((r) => r.status).join(','));
  const hammer = http.batch(Array.from({ length: 30 }, (_, i) => ['POST', `${BASE}${SEND}`, JSON.stringify({ channel: 'SMS', recipient: '+0', subject: 's', body: `spam ${i}` }), { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${t}` }, tags: { name: 'POST /api/notification-svc/notifications/send (hammer)' } }]));
  truthy(
    '[-] thirty texts to a bad number: every one refused',
    hammer.every((r) => (r.status === 400 && errorCode(r) === 'SMS_RECIPIENT_INVALID') || r.status === 429),
    hammer.map((r) => `${r.status} ${errorCode(r)}`).join(','),
  );
  truthy('[-] ...and none on the log', list(call('GET', `${LOG}?channel=SMS&recipient=%2B0`, { token: t })).length === 0);
}
