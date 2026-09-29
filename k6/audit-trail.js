// The business audit trail (20.11): every discount, void, no-sale, cancel and return on the shop
// floor read back through the gateway as one stream naming who did it, the stock adjustment the
// unified screen reads beside it — and the refusals around it: the wrong role, the wrong tenant,
// the wrong input, a hammering cashier, a lifted cursor. Then an offline till sale under a recall:
// replayed within the grace it is placed and flagged for the store's manager, naming the cashier
// who rang it up and the manager who synced the till; too old, it is refused; another business
// sees none of it.
//
//   k6/run.sh audit-trail
import http from 'k6/http';
import { sleep } from 'k6';
import {
  ALL_CHECKS_PASS,
  BASE,
  call,
  data,
  expect,
  must,
  nextCursor,
  onboardTenant,
  poll,
  priceVariants,
  receive,
  sellableVariant,
  staffUser,
  truthy,
  uniq,
} from './lib/storeql.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS, setupTimeout: '4m' };

export function setup() {
  const tenant = onboardTenant('audit', { stores: 1 });
  const rival = onboardTenant('audit-rival', { stores: 1 });
  const storeId = tenant.stores[0].id;
  tenant.variantId = sellableVariant(tenant, 'Audited mug').variantId;
  priceVariants(tenant, [tenant.variantId], '10.00');
  must(receive(tenant, storeId, tenant.variantId, 100), 201, 'receive stock');
  tenant.cashier = staffUser(tenant, 'CASHIER', [storeId]);
  tenant.keeper = staffUser(tenant, 'STOREKEEPER', [storeId]);
  // For the offline sale: a store manager, a product nobody else sells, and a rival's manager.
  tenant.manager = staffUser(tenant, 'MANAGER', [storeId]);
  tenant.recalledVariant = sellableVariant(tenant, 'Recalled jam').variantId;
  priceVariants(tenant, [tenant.recalledVariant], '4.00');
  rival.manager = staffUser(rival, 'MANAGER', [rival.stores[0].id]);
  return { tenant, rival };
}

export default function ({ tenant, rival }) {
  const t = tenant.owner.token;
  const owner = tenant.owner.userId;
  const cashier = tenant.cashier;
  const storeId = tenant.stores[0].id;
  const variantId = tenant.variantId;
  const trail = (token, params = '') => call('GET', `/api/order-svc/admin/audit/events?store=${storeId}${params}`, { token });
  const sale = (extra = {}) => ({ storeId, channel: 'POS', fulfilmentType: 'INSTORE', paymentMethod: 'CASH', items: [{ variantId, qty: 2 }], ...extra });
  const place = (body) => data(call('POST', '/api/order-svc/orders', { token: t, idem: true, body }));
  const pay = (order) => {
    expect(call('POST', '/api/payment-svc/payments', { token: t, idem: true, body: { orderId: order.id, amount: order.total, method: 'CASH', storeId, currency: 'GBP' } }), `[+] pay for ${order.id.slice(0, 8)}`, [200, 201]);
    poll(30, () => data(call('GET', `/api/order-svc/orders/${order.id}`, { token: t })).status === 'FULFILLED');
  };

  // ── five sensitive actions by two people ─────────────────────────────────────
  const discounted = place(sale({ discountAmount: 2, discountReason: 'damaged box' }));
  truthy('[+] a discounted sale is placed by the owner', !!discounted.id, JSON.stringify(discounted));
  const voided = place(sale());
  pay(voided);
  expect(call('POST', `/api/order-svc/orders/${voided.id}/void`, { token: t, body: { reason: 'rang up twice' } }), '[+] a paid sale is voided by the owner', 200);
  expect(call('POST', '/api/order-svc/pos/no-sale', { token: cashier.token, body: { storeId, reason: 'drawer check' } }), '[+] the cashier opens the drawer without a sale', 201);
  const cancelled = place(sale());
  expect(call('POST', `/api/order-svc/orders/${cancelled.id}/cancel`, { token: t, body: { reason: 'customer walked out' } }), '[+] a pending sale is cancelled by the owner', 200);
  const returned = place(sale());
  pay(returned);
  expect(call('POST', `/api/order-svc/orders/${returned.id}/returns`, { token: t, body: { reason: 'chipped', items: [{ variantId, qty: 1 }] } }), '[+] one unit is taken back by the owner', 201);
  expect(call('POST', '/api/inventory-svc/admin/inventory/adjust', { token: t, idem: true, body: { storeId, variantId, delta: -3, reason: 'damaged' } }), '[+] and three are written off', [200, 201]);

  // ── the trail ────────────────────────────────────────────────────────────────
  const all = data(trail(t));
  const types = all.map((e) => e.type);
  truthy('[+] every action is on the trail', ['DISCOUNT', 'VOID', 'NO_SALE', 'CANCEL', 'RETURN'].every((k) => types.includes(k)), types.join(','));
  const byType = (k) => all.find((e) => e.type === k) || {};
  truthy('[+] the discount names the owner, the order, the money and the reason', byType('DISCOUNT').actorId === owner && byType('DISCOUNT').orderId === discounted.id && Number(byType('DISCOUNT').amount) === 2 && byType('DISCOUNT').reason === 'damaged box', JSON.stringify(byType('DISCOUNT')));
  truthy('[+] the void names the owner and its reason', byType('VOID').actorId === owner && byType('VOID').orderId === voided.id && byType('VOID').reason === 'rang up twice', JSON.stringify(byType('VOID')));
  truthy('[+] the no-sale names the cashier and no order', byType('NO_SALE').actorId === cashier.userId && !byType('NO_SALE').orderId, JSON.stringify(byType('NO_SALE')));
  truthy('[+] the cancel names the owner and the status it came from', byType('CANCEL').actorId === owner && byType('CANCEL').detail === 'PENDING', JSON.stringify(byType('CANCEL')));
  truthy('[+] the return names the owner, the refund and its method', byType('RETURN').actorId === owner && Number(byType('RETURN').amount) === 10 && byType('RETURN').detail === 'ORIGINAL', JSON.stringify(byType('RETURN')));
  const stamps = all.map((e) => e.occurredAt);
  truthy('[+] newest first', stamps.every((s, i) => i === 0 || s <= stamps[i - 1]), stamps.join(' '));
  const returns = data(call('GET', `/api/order-svc/orders/${returned.id}/returns`, { token: t }));
  truthy('[+] the order\'s own return record names who took it back', returns[0] && returns[0].createdBy === owner, JSON.stringify(returns));

  // ── narrowed ─────────────────────────────────────────────────────────────────
  const voids = data(trail(t, '&type=VOID'));
  truthy('[+] by type', voids.length >= 1 && voids.every((e) => e.type === 'VOID'), JSON.stringify(voids));
  const cashiersOwn = data(trail(t, `&actor=${cashier.userId}`));
  truthy('[+] by actor: the cashier only ever opened the drawer', cashiersOwn.length === 1 && cashiersOwn[0].type === 'NO_SALE', JSON.stringify(cashiersOwn));
  truthy('[+] by period: nothing tomorrow', data(trail(t, `&from=${new Date(Date.now() + 3600e3).toISOString()}`)).length === 0);

  // ── paged ────────────────────────────────────────────────────────────────────
  const seen = new Set();
  let after = null;
  let pages = 0;
  let dup = false;
  do {
    const res = trail(t, `&limit=2${after ? `&after=${after}` : ''}`);
    for (const e of data(res)) {
      if (seen.has(e.id)) dup = true;
      seen.add(e.id);
    }
    after = nextCursor(res);
    pages += 1;
  } while (after && pages < 10);
  truthy('[+] two at a time, no gaps, no repeats', !dup && seen.size >= 5 && pages >= 3, `${seen.size} events over ${pages} pages`);

  // ── the unified screen's second source ────────────────────────────────────────
  const adjustments = data(call('GET', `/api/inventory-svc/admin/inventory/movements?store=${storeId}&type=ADJUST`, { token: t }));
  const writtenOff = adjustments.find((m) => m.variantId === variantId && Number(m.qty) === -3) || {};
  truthy('[+] the stock adjustment names the owner too', writtenOff.actorId === owner, JSON.stringify(adjustments).slice(0, 400));

  // ── refusals ─────────────────────────────────────────────────────────────────
  expect(trail(cashier.token), '[-] a cashier cannot read the trail', 403);
  expect(trail(tenant.keeper.token), '[-] nor a storekeeper', 403);
  expect(call('GET', '/api/order-svc/admin/audit/events'), '[-] nor a guest', 401);
  truthy('[-] another tenant sees none of it', data(call('GET', '/api/order-svc/admin/audit/events', { token: rival.owner.token })).length === 0);
  expect(trail(t, '&type=WEATHER'), '[-] an unknown type', 400, 'AUDIT_TYPE_UNKNOWN');
  expect(trail(t, '&after=@@@'), '[-] a malformed cursor', 400, 'INVALID_CURSOR');
  expect(call('GET', '/api/order-svc/admin/audit/events?store=abc', { token: t }), '[-] a store that is not an id', 400);
  expect(trail(t, '&actor=abc'), '[-] an actor that is not an id', 400);
  expect(trail(t, '&from=2026-02-01T00:00:00Z&to=2026-01-01T00:00:00Z'), '[-] a period that ends before it starts', 400, 'AUDIT_RANGE_EMPTY');
  expect(call('POST', '/api/order-svc/admin/audit/events', { token: t, body: {} }), '[-] the trail cannot be written to', [404, 405]);

  // ── abuse ────────────────────────────────────────────────────────────────────
  const hammer = http.batch(Array.from({ length: 20 }, () => ['GET', `${BASE}/api/order-svc/admin/audit/events?store=${storeId}`, null, { headers: { Authorization: `Bearer ${cashier.token}` }, tags: { name: 'GET /api/order-svc/admin/audit/events (hammer)' } }]));
  truthy('[-] twenty cashier reads at once: every one refused', hammer.every((r) => r.status === 403 || r.status === 429), hammer.map((r) => r.status).join(','));
  const lifted = nextCursor(trail(t, '&limit=2'));
  truthy('[-] a cursor lifted from this tenant gives the rival nothing', !!lifted && data(call('GET', `/api/order-svc/admin/audit/events?after=${lifted}`, { token: rival.owner.token })).length === 0);
  const huge = trail(t, '&limit=100000');
  truthy('[+] an absurd page size is clamped, not refused', huge.status === 200 && data(huge).length <= 100, String(huge.status));

  // ── an offline till sale under a recall ──────────────────────────────────────
  // A till that lost its network completes the sale and replays it later, saying when it was rung
  // up and who rang it up. Within the grace (a day) it is placed whatever the recall says — the
  // goods have gone — and flagged for a manager; older, it is judged as a sale made now.
  const manager = tenant.manager;
  const recalled = tenant.recalledVariant;
  const opened = call('POST', '/api/inventory-svc/admin/recalls', {
    token: manager.token,
    body: {
      reference: `K6-OFFLINE-${uniq()}`, kind: 'RECALL', hazard: 'ALLERGEN', reason: 'Undeclared peanut',
      source: 'SUPPLIER', customerNotice: 'Do not eat. Bring it back for a full refund or a new one.',
      items: [{ variantId: recalled }], remedies: ['REFUND', 'REPLACEMENT'], contactPhone: '0800 100 200',
    },
  });
  expect(opened, '[+] a manager recalls every pack of a product', 201);
  const recall = data(opened);
  // order-svc keeps a business's recalls for ten seconds, so every sale below is judged with this one.
  sleep(11);
  const offlineSale = (capturedAt, rungUpBy) => call('POST', '/api/order-svc/orders', {
    token: manager.token,
    idem: true,
    body: { storeId, channel: 'POS', fulfilmentType: 'INSTORE', paymentMethod: 'CASH', items: [{ variantId: recalled, qty: 1 }], capturedAt, rungUpBy },
  });
  const flagsFor = (token, orderId) => data(trail(token, '&type=OFFLINE_SALE_OF_RECALLED_ITEM&limit=100')).filter((e) => e.orderId === orderId);
  // A second after the recall opened, by the server's own clock (its microseconds cut to what a date holds).
  const openedMs = Date.parse(String(recall.openedAt).replace(/(\.\d{3})\d+/, '$1'));
  const rungUp = new Date(Math.max(openedMs + 1000, Date.now() - 2000)).toISOString();

  const flagged = offlineSale(rungUp, cashier.userId);
  expect(flagged, '[+] rung up offline under the recall, synced by the manager: placed', 201);
  const flags = flagsFor(manager.token, data(flagged).id);
  const entry = flags[0] || {};
  truthy('[+] one entry for the store\'s manager, at the moment it was rung up', flags.length === 1 && Date.parse(entry.occurredAt) === Date.parse(rungUp), flags);
  truthy('[+] naming the cashier who rang it up, and the manager who synced it', entry.actorId === cashier.userId && entry.replayedBy === manager.userId, entry);
  truthy('[+] and the recall, in words', entry.detail === recall.reference && String(entry.reason).includes('was under product recall'), entry);

  const stranger = offlineSale(rungUp, rival.owner.userId);
  expect(stranger, '[+] a till naming another business\'s login: placed all the same', 201);
  const unnamed = flagsFor(manager.token, data(stranger).id)[0] || {};
  truthy('[+] ...flagged as rung up by an unknown member of staff', !unnamed.actorId && unnamed.replayedBy === manager.userId && !!unnamed.id, unnamed);

  const hourAgo = offlineSale(new Date(Date.now() - 3600e3).toISOString(), cashier.userId);
  expect(hourAgo, '[+] rung up an hour ago, before the recall opened: placed', 201);
  truthy('[+] ...with nothing to flag', flagsFor(manager.token, data(hourAgo).id).length === 0);

  expect(
    offlineSale(new Date(Date.now() - 3 * 86400e3).toISOString(), cashier.userId),
    '[-] rung up three days ago: too old to take on the till\'s word, refused',
    409,
    'ORDER_LINE_RECALLED'
  );
  expect(offlineSale(undefined, undefined), '[-] a sale made now is refused as ever', 409, 'ORDER_LINE_RECALLED');

  const rivals = data(call('GET', '/api/order-svc/admin/audit/events?type=OFFLINE_SALE_OF_RECALLED_ITEM', { token: rival.manager.token }));
  truthy('[-] another business\'s manager sees none of it', Array.isArray(rivals) && rivals.every((e) => e.orderId !== data(flagged).id && e.orderId !== data(stranger).id), rivals);
  const named = call('GET', `/api/order-svc/admin/audit/events?store=${storeId}&type=OFFLINE_SALE_OF_RECALLED_ITEM`, { token: rival.manager.token });
  truthy('[-] ...not even naming this store', named.status === 403 || (Array.isArray(data(named)) && data(named).length === 0), named.status);

  // Closed, so no later sale of the product — here or in a later suite — meets it.
  expect(
    call('POST', `/api/inventory-svc/admin/recalls/${recall.id}/close`, { token: manager.token, body: { notes: 'k6: nothing was stocked' } }),
    '[+] the recall is closed',
    200
  );
}
