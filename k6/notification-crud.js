// notification-svc: shortage alerts raised by real stock movements, sending a notification (with
// event-id dedupe) and the tenant's notification log.
//
//   k6/run.sh notification-crud
import {
  ALL_CHECKS_PASS,
  call,
  data,
  expect,
  newId,
  onboardTenant,
  poll,
  receive,
  register,
  sellableVariant,
  truthy,
  uniq,
} from './lib/storeql.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS, setupTimeout: '3m' };

const ALERTS = '/api/notification-svc/admin/notifications/shortage-alerts';

export function setup() {
  const tenant = onboardTenant('notify', { stores: 1 });
  const rival = onboardTenant('notify-rival', { stores: 1 });
  const storeId = tenant.stores[0].id;
  tenant.variantId = sellableVariant(tenant, 'Low stock soap').variantId;
  tenant.otherVariantId = sellableVariant(tenant, 'Plenty of rice').variantId;
  if (receive(tenant, storeId, tenant.variantId, 100).status !== 201) throw new Error('receive failed');
  return { tenant, rival };
}

export default function ({ tenant, rival }) {
  const t = tenant.owner.token;
  const storeId = tenant.stores[0].id;
  const variantId = tenant.variantId;

  // ── shortage alerts: threshold 50, then take 60 of 100 away ────────────────
  expect(
    call('POST', '/api/inventory-svc/admin/inventory/thresholds', { token: t, body: { storeId, variantId, threshold: '50' } }),
    '[+] set a reorder threshold of 50',
    201
  );
  expect(
    call('POST', '/api/inventory-svc/admin/inventory/adjust', { token: t, body: { storeId, variantId, delta: -60, reason: 'damaged in transit' } }),
    '[+] stock drops to 40',
    200
  );
  let alert = null;
  const took = poll(60, () => {
    alert = (data(call('GET', `${ALERTS}?variantId=${variantId}`, { token: t })) || [])[0] || null;
    return alert !== null;
  });
  truthy('[+] a shortage alert arrives', took >= 0, alert);
  truthy('[+] ...for this store, at 40 against 50', alert && alert.storeId === storeId && Number(alert.available) === 40 && Number(alert.threshold) === 50, alert);

  const byStore = call('GET', `${ALERTS}?storeId=${storeId}`, { token: t });
  expect(byStore, '[+] alerts for one store', 200);
  truthy('[+] ...include it', (data(byStore) || []).some((a) => a.variantId === variantId), data(byStore));
  const otherVariant = call('GET', `${ALERTS}?variantId=${tenant.otherVariantId}`, { token: t });
  expect(otherVariant, '[+] alerts for a well-stocked variant', 200);
  truthy('[+] ...are empty', (data(otherVariant) || []).length === 0, data(otherVariant));
  expect(call('GET', `${ALERTS}?limit=500`, { token: t }), '[+] an oversized limit is capped, not refused', 200);
  expect(call('GET', `${ALERTS}?storeId=not-a-uuid`, { token: t }), '[-] store filter must be a UUID', 400, 'INVALID_UUID');
  truthy("[-] a rival tenant sees none of our alerts", !(data(call('GET', ALERTS, { token: rival.owner.token })) || []).some((a) => a.variantId === variantId));
  expect(call('GET', ALERTS, { token: register('notify-shopper').token }), '[-] a customer cannot read alerts', 403, 'FORBIDDEN');
  expect(call('GET', ALERTS), '[-] no token', 401, 'UNAUTHORIZED');

  // ── send, with dedupe on (eventId, type) ───────────────────────────────────
  const recipient = `notify-${uniq()}@k6.storeql.test`;
  const message = { recipient, subject: 'Your order is ready', body: 'Collect it from the front desk.', type: 'ORDER_READY', eventId: newId() };
  expect(call('POST', '/api/notification-svc/notifications/send', { token: t, body: { ...message, subject: '' } }), '[-] send: subject required', 400, 'VALIDATION_FAILED');
  expect(
    call('POST', '/api/notification-svc/notifications/send', { token: t, body: { ...message, customerId: 'not-a-uuid' } }),
    '[-] send: customerId must be a UUID',
    400
  );
  expect(
    call('POST', '/api/notification-svc/notifications/send', { token: t, body: { ...message, eventId: 'evt-1' } }),
    '[-] send: eventId must be a UUID',
    400,
    'INVALID_UUID'
  );
  expect(call('POST', '/api/notification-svc/notifications/send', { token: register('notify-shopper2').token, body: message }), '[-] a customer cannot send', 403, 'FORBIDDEN');
  expect(call('POST', '/api/notification-svc/notifications/send', { token: t, body: message }), '[+] send a notification', 202);
  expect(call('POST', '/api/notification-svc/notifications/send', { token: t, body: message }), '[+] the same event sent twice is accepted', 202);

  let log = [];
  poll(30, () => {
    log = data(call('GET', `/api/notification-svc/admin/notifications?recipient=${encodeURIComponent(recipient)}`, { token: t })) || [];
    return log.length > 0;
  });
  truthy('[+] the notification log shows it once', log.length === 1, log);
  truthy("[-] a rival's log does not", (data(call('GET', `/api/notification-svc/admin/notifications?recipient=${encodeURIComponent(recipient)}`, { token: rival.owner.token })) || []).length === 0);
}
