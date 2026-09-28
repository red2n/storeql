// Webhooks to a business's own systems (22.6), through the gateway and a stand-in receiver on the
// compose network (webhook-sink, which answers with what it received): an owner reads the event
// catalogue, registers an endpoint and is shown its secret once; a ping reaches the receiver signed
// and the delivery log shows the try; stock booked in through the API reaches it as a StockReceived
// delivery, signed, the event whole; an endpoint nobody answers is tried and queued again with a
// wait and a reason, and sent again by hand; a rotated secret signs from then on; switched off,
// nothing is sent. Refused: a manager registering, an unknown event, a private or plain-HTTP
// address, another business reading the endpoint or the delivery.
//
//   k6/run.sh webhooks-flow
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';
import { Counter } from 'k6/metrics';
import {
  ALL_CHECKS_PASS,
  call,
  data,
  expect,
  must,
  onboardTenant,
  poll,
  receive,
  sellableVariant,
  staffUser,
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

const HOOKS = '/api/notification-svc/admin/webhooks';
const SINK = __ENV.WEBHOOK_SINK || 'http://webhook-sink:8080';

export function setup() {
  const tenant = onboardTenant('hooks');
  const rival = onboardTenant('hooks-rival');
  return {
    tenant,
    store: tenant.stores[0].id,
    rival,
    manager: staffUser(tenant, 'MANAGER', [tenant.stores[0].id]),
    variant: sellableVariant(tenant, `Hooked ${uniq()}`).variantId,
  };
}

/** The Standard Webhooks headers and the body as the receiver echoed them, from the attempt's response snippet. */
function echoed(attempt) {
  try {
    const echo = JSON.parse(attempt.responseSnippet);
    const h = echo.headers || {};
    return { id: h['webhook-id'], timestamp: h['webhook-timestamp'], signature: h['webhook-signature'], event: h['x-storeql-event'], body: typeof echo.body === 'string' ? echo.body : JSON.stringify(echo.json || echo.body) };
  } catch (e) {
    return {};
  }
}

/** What any receiver written to the specification does: v1 over id.timestamp.body under the secret's bytes. */
function verifies(secret, got) {
  if (!got || !got.signature || !got.body || !got.id || !got.timestamp) return false;
  const key = encoding.b64decode(secret.slice('whsec_'.length));
  const expected = crypto.hmac('sha256', key, `${got.id}.${got.timestamp}.${got.body}`, 'base64');
  return got.signature.split(' ').some((s) => s === `v1,${expected}`);
}

export default function ({ tenant, store, rival, manager, variant }) {
  const owner = tenant.owner.token;
  const tag = uniq();
  const hooks = (method, path, body, token = owner) => call(method, `${HOOKS}${path}`, { token, body });
  const delivery = (id) => data(hooks('GET', `/deliveries/${id}`)) || {};
  const landed = (id) => { let d = {}; const took = poll(45, () => { d = delivery(id); return d.status === 'DELIVERED'; }); return took >= 0 ? d : d; };

  // ── the catalogue and an endpoint ─────────────────────────────────────────────────────────────
  const catalogue = data(hooks('GET', '/events', undefined, manager.token)) || [];
  truthy('[+] a manager reads what a business may be told of: orders, payments, stock, products, prices, customers', catalogue.length >= 15 && ['OrderPlaced', 'StockReceived', 'PriceChanged'].every((t) => catalogue.some((e) => e.type === t && e.description)), catalogue.map((e) => e.type));
  const made = hooks('POST', '/endpoints', { url: `${SINK}/hooks/${tag}`, description: 'Warehouse ERP', events: ['StockReceived', 'OrderPlaced'] });
  expect(made, '[+] the owner registers an endpoint for stock and orders', 201);
  const endpoint = data(made).id;
  const secret = data(made).secret;
  truthy('[+] and is shown its secret once', typeof secret === 'string' && secret.startsWith('whsec_') && secret.length > 40, data(made).secret && 'shown');
  const listed = data(hooks('GET', '/endpoints', undefined, manager.token)) || [];
  truthy('[+] listed without the secret, switched on', listed.some((e) => e.id === endpoint && e.enabled === true && !('secret' in e) && e.events.length === 2), listed);
  expect(hooks('POST', '/endpoints', { url: `${SINK}/x`, description: 'x', events: ['OrderPlaced'] }, manager.token), '[-] a manager reads endpoints, not registers them', 403);
  expect(hooks('POST', '/endpoints', { url: `${SINK}/x`, description: 'x', events: ['OrderPlaced', 'SomethingElse'] }), '[-] an event nobody publishes', 400, 'WEBHOOK_EVENT_UNKNOWN');
  expect(hooks('POST', '/endpoints', { url: `${SINK}/x`, description: 'x', events: [] }), '[-] an endpoint asks for something', 400, 'WEBHOOK_EVENTS_EMPTY');
  expect(hooks('POST', '/endpoints', { url: 'https://10.0.0.5/hook', description: 'x', events: ['OrderPlaced'] }), '[-] an address inside the network', 400, 'WEBHOOK_URL_INVALID');
  expect(hooks('POST', '/endpoints', { url: 'http://example.com/hook', description: 'x', events: ['OrderPlaced'] }), '[-] plain HTTP to the world', 400, 'WEBHOOK_URL_INVALID');
  expect(hooks('POST', '/endpoints', { url: 'https://user:pw@example.com/hook', description: 'x', events: ['OrderPlaced'] }), '[-] credentials in the address', 400, 'WEBHOOK_URL_INVALID');
  truthy('[-] another business sees none of it', ((data(hooks('GET', '/endpoints', undefined, rival.owner.token)) || []).length === 0), 'rival list');
  expect(hooks('GET', `/endpoints/${endpoint}`, undefined, rival.owner.token), '[-] nor reads it by id', 404, 'WEBHOOK_ENDPOINT_NOT_FOUND');

  // ── a ping, signed, on the log ────────────────────────────────────────────────────────────────
  const ping = hooks('POST', `/endpoints/${endpoint}/ping`, undefined, manager.token);
  expect(ping, '[+] a manager sends a test delivery', 202);
  const pinged = landed(data(ping).deliveryId);
  truthy('[+] it lands within seconds: delivered on the first try, the receiver answered 200', pinged.status === 'DELIVERED' && pinged.attempts === 1 && pinged.lastStatus === 200 && (pinged.attemptLog || []).length === 1, pinged);
  const echo = echoed((pinged.attemptLog || [])[0] || {});
  truthy('[+] the receiver saw a Ping, signed to the Standard Webhooks specification under the secret over the body as sent', echo.event === 'Ping' && echo.id === data(ping).deliveryId && verifies(secret, echo), { event: echo.event, id: echo.id, signature: echo.signature });
  truthy('[+] ...an envelope naming the delivery, the type and the event whole', (() => { try { const b = JSON.parse(echo.body); return b.id === data(ping).deliveryId && b.type === 'Ping' && b.data && b.data.eventType === 'Ping'; } catch (e) { return false; } })(), echo.body);
  expect(hooks('GET', `/deliveries/${data(ping).deliveryId}`, undefined, rival.owner.token), '[-] another business cannot read the delivery', 404, 'WEBHOOK_DELIVERY_NOT_FOUND');

  // ── a real event, through the bus ─────────────────────────────────────────────────────────────
  must(receive(tenant, store, variant, 7, '3.00'), 201, 'stock booked in');
  let stock = null;
  poll(60, () => { stock = ((data(hooks('GET', `/deliveries?endpointId=${endpoint}&status=DELIVERED&limit=20`)) || {}).items || []).find((d) => d.eventType === 'StockReceived'); return !!stock; });
  truthy('[+] stock booked in reaches the endpoint as a StockReceived delivery', !!stock, 'no StockReceived delivery');
  if (stock) {
    const detail = delivery(stock.id);
    const got = echoed((detail.attemptLog || [])[0] || {});
    truthy('[+] ...signed, with the event whole: the store and the variant', got.event === 'StockReceived' && verifies(secret, got) && got.body.includes(store) && got.body.includes(variant), { event: got.event, body: (got.body || '').slice(0, 200) });
  }
  truthy('[+] the endpoint remembers when it last delivered', !!(data(hooks('GET', `/endpoints/${endpoint}`)) || {}).lastDeliveredAt, 'lastDeliveredAt');

  // ── a receiver nobody answers ─────────────────────────────────────────────────────────────────
  const dark = must(hooks('POST', '/endpoints', { url: `${SINK.replace(/:\d+$/, '')}:9/hooks/${tag}`, description: 'Nobody home', events: ['OrderPlaced'] }), 201, 'a dark endpoint');
  const darkPing = must(hooks('POST', `/endpoints/${dark.id}/ping`), 202, 'ping the dark').deliveryId;
  let tried = {};
  poll(45, () => { tried = delivery(darkPing); return tried.attempts >= 1; });
  truthy('[+] a receiver nobody answers: tried, still pending, the reason recorded, the next try a minute away', tried.status === 'PENDING' && tried.attempts === 1 && !!tried.lastError && !!tried.nextAttemptAt && new Date(tried.nextAttemptAt).getTime() > Date.now() + 30_000, tried);
  const again = hooks('POST', `/deliveries/${darkPing}/redeliver`);
  expect(again, '[+] sent again by hand, now', 200);
  truthy('[+] ...queued at once, the try so far kept', data(again).status === 'PENDING' && data(again).attempts === 1, data(again));
  expect(hooks('POST', `/deliveries/${darkPing}/redeliver`, undefined, rival.owner.token), '[-] not by another business', 404);
  expect(hooks('DELETE', `/endpoints/${dark.id}`, undefined, manager.token), '[-] a manager cannot remove an endpoint', 403);
  expect(hooks('DELETE', `/endpoints/${dark.id}`), '[+] the owner removes it', 200);
  expect(hooks('GET', `/deliveries/${darkPing}`), '[+] ...and its log with it', 404);

  // ── a rotated secret, and switched off ────────────────────────────────────────────────────────
  expect(hooks('POST', `/endpoints/${endpoint}/secret`, undefined, manager.token), '[-] a manager cannot rotate the secret', 403);
  const rotated = hooks('POST', `/endpoints/${endpoint}/secret`);
  expect(rotated, '[+] the owner rotates the secret', 200);
  const fresh = data(rotated).secret;
  const ping2 = landed(must(hooks('POST', `/endpoints/${endpoint}/ping`), 202, 'ping again').deliveryId);
  const echo2 = echoed((ping2.attemptLog || [])[0] || {});
  truthy('[+] the next delivery is signed under the new secret and no longer the old', ping2.status === 'DELIVERED' && verifies(fresh, echo2) && !verifies(secret, echo2), { signature: echo2.signature });
  expect(hooks('PUT', `/endpoints/${endpoint}`, { enabled: false }), '[+] switched off', 200);
  const quiet = must(hooks('POST', `/endpoints/${endpoint}/ping`), 202, 'ping while off').deliveryId;
  poll(12, () => false);
  truthy('[-] nothing is sent to an endpoint switched off', delivery(quiet).status === 'PENDING' && delivery(quiet).attempts === 0, delivery(quiet));
  expect(hooks('PUT', `/endpoints/${endpoint}`, { enabled: true }), '[+] switched on again', 200);
  const woke = landed(quiet);
  truthy('[+] ...and what waited is sent', woke.status === 'DELIVERED', woke);
  const log = (data(hooks('GET', `/deliveries?endpointId=${endpoint}&limit=50`)) || {}).items || [];
  truthy('[+] the log, newest first: four deliveries, every one landed', log.length === 4 && log[0].id === quiet && log.every((d) => d.status === 'DELIVERED'), log.map((d) => `${d.eventType}:${d.status}`));

  completed.add(1);
}
