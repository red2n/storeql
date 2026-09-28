// Data retention (21.16), end to end through the gateway: the law's floor following the countries a
// business trades in; a period under it refused by name; holds placed, obeyed and released; the
// three purges run for real — personal details off settled orders, the notification log, inactive
// customer records — keeping what a hold keeps and what is still open; every run on the register;
// and the wrong caller and the wrong input at every step.
//
//   k6/run.sh retention-flow
import { group } from 'k6';
import http from 'k6/http';
import {
  ALL_CHECKS_PASS,
  BASE,
  addStore,
  call,
  data,
  errorCode,
  expect,
  must,
  onboardTenant,
  poll,
  priceVariants,
  receive,
  register,
  sellableVariant,
  staffUser,
  truthy,
  uniq,
} from './lib/storeql.js';

export const options = {
  scenarios: { flow: { executor: 'per-vu-iterations', vus: 1, iterations: 1, maxDuration: '10m' } },
  setupTimeout: '5m',
  thresholds: ALL_CHECKS_PASS,
  batch: 20,
  batchPerHost: 20,
};

const SHEET = '/api/tenant-svc/admin/tenant/retention';
const SWEEP = {
  order: '/api/order-svc/admin/orders/retention/sweep',
  customer: '/api/customer-svc/admin/customers/retention/sweep',
  notification: '/api/notification-svc/admin/notifications/retention/sweep',
};

export function setup() {
  const gb = onboardTenant('retention', { country: 'GB', currency: 'GBP' });
  const rival = onboardTenant('retention-rival', { country: 'GB', currency: 'GBP' });
  const store = gb.stores[0];
  const { variantId } = sellableVariant(gb, 'Retention jam');
  priceVariants(gb, [variantId], '4.00');
  must(receive(gb, store.id, variantId, 50), [200, 201], 'receive stock');
  const cashier = staffUser(gb, 'CASHIER', [store.id]);
  const storekeeper = staffUser(gb, 'STOREKEEPER', [store.id]);
  const person = (who) =>
    must(
      call('POST', '/api/customer-svc/customers', { token: gb.owner.token, body: { email: `retention-${who}-${uniq()}@k6.storeql.test`, firstName: who, lastName: 'Carter' } }),
      201,
      `customer ${who}`
    );
  return { gb, rival, store, variantId, cashier, storekeeper, shopper: register('retention-shopper'), gone: person('gone'), kept: person('kept') };
}

export default function ({ gb, rival, store, variantId, cashier, storekeeper, shopper, gone, kept }) {
  const owner = gb.owner.token;
  const sheet = (token = owner) => data(call('GET', SHEET, { token }));
  const cls = (s, code) => (s.classes || []).find((c) => c.code === code) || {};
  const setPeriod = (code, periodDays, token = owner) => call('PUT', `${SHEET}/${code}`, { token, body: { periodDays } });
  const sweep = (which, token = owner) => call('POST', SWEEP[which], { token, body: {} });
  const orderOf = (id) => data(call('GET', `/api/order-svc/orders/${id}`, { token: owner }));
  const logFor = (email) => data(call('GET', `/api/notification-svc/admin/notifications?recipient=${encodeURIComponent(email)}&limit=100`, { token: owner })) || [];

  // ── the floor follows the countries traded in ─────────────────────────────────
  group('1. the floor', () => {
    let s = sheet();
    truthy('[+] a British business answers to British law', JSON.stringify(s.countries) === '["GB"]' && cls(s, 'TRANSACTIONS').floorDays === 2190 && /Value Added Tax Act 1994/.test(cls(s, 'TRANSACTIONS').floorCitation), s);
    truthy('[+] ...personal data has no statutory floor, and nothing is set yet', cls(s, 'CUSTOMER_RECORDS').floorDays === undefined && (s.classes || []).every((c) => c.periodDays === undefined), s.classes);
    addStore({ ...gb, country: 'DE' }, 'DE');
    s = sheet();
    truthy('[+] a German shop brings Germany\'s longer floor with it', s.countries.includes('DE') && cls(s, 'TRANSACTIONS').floorDays === 2920 && cls(s, 'TRANSACTIONS').floorScope === 'DE', s);
    expect(call('GET', SHEET, { token: storekeeper.token }), '[+] a storekeeper reads the schedule', 200);
    expect(call('GET', SHEET, { token: cashier.token }), '[+] so does a cashier', 200);
    expect(call('GET', SHEET, { token: shopper.token }), '[-] a shopper does not', [401, 403]);
    expect(call('GET', SHEET), '[-] nor does nobody', 401);
    truthy('[-] a rival business sees its own schedule, not ours', JSON.stringify(sheet(rival.owner.token).countries) === '["GB"]');

    const under = setPeriod('TRANSACTIONS', 2190);
    expect(under, '[-] six years is under Germany\'s eight', 400, 'RETENTION_BELOW_LEGAL_MINIMUM');
    truthy('[-] ...and the refusal names the floor and the law', /2920/.test(under.body) && /Abgabenordnung/.test(under.body), under.body);
    expect(setPeriod('TRANSACTIONS', 2919), '[-] one day under is still under', 400, 'RETENTION_BELOW_LEGAL_MINIMUM');
    expect(setPeriod('TRANSACTIONS', 2920), '[+] the floor itself is allowed', 200);
    expect(setPeriod('TRANSACTIONS', 3650), '[+] and longer', 200);
    expect(setPeriod('TRANSACTIONS', -1), '[-] a negative period', 400);
    expect(setPeriod('TRANSACTIONS', 36501), '[-] more than a hundred years', 400);
    expect(call('PUT', `${SHEET}/TRANSACTIONS`, { token: owner, body: { periodDays: 'six years' } }), '[-] words for a number', 400);
    expect(call('PUT', `${SHEET}/TRANSACTIONS`, { token: owner, body: {} }), '[-] no period at all', 400);
    expect(setPeriod('DIARIES', 30), '[-] a class that does not exist', 400, 'RETENTION_CLASS_UNKNOWN');
    expect(setPeriod(encodeURIComponent("TRANSACTIONS';DROP TABLE retention_schedules;--"), 30), '[-] SQL in the class', [400, 404]);
    expect(setPeriod('TRANSACTIONS', 3650, cashier.token), '[-] a cashier cannot set a period', 403);
    expect(setPeriod('TRANSACTIONS', 3650, storekeeper.token), '[-] nor a storekeeper', 403);
    expect(setPeriod('TRANSACTIONS', 3650, shopper.token), '[-] nor a shopper', [401, 403]);
    truthy('[+] the latest decision is in force', cls(sheet(), 'TRANSACTIONS').periodDays === 3650);
    truthy('[-] and the rival\'s is untouched', cls(sheet(rival.owner.token), 'TRANSACTIONS').periodDays === undefined);

    const race = http.batch(
      Array.from({ length: 20 }, (_, i) => [
        'PUT',
        `${BASE}${SHEET}/NOTIFICATION_LOG`,
        JSON.stringify({ periodDays: i + 1 }),
        { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${owner}` }, tags: { name: 'PUT /api/tenant-svc/admin/tenant/retention/{class} (race)' } },
      ])
    );
    const inForce = cls(sheet(), 'NOTIFICATION_LOG').periodDays;
    truthy('[+] twenty decisions at once: all kept, one in force', race.every((r) => r.status === 200) && inForce >= 1 && inForce <= 20, { statuses: race.map((r) => r.status).join(','), inForce });
  });

  // ── sales that leave personal details behind ──────────────────────────────────
  const sale = (customerId, phone, pay) => {
    const placed = must(
      call('POST', '/api/order-svc/orders', { token: cashier.token, idem: true, body: { storeId: store.id, channel: 'POS', fulfilmentType: 'INSTORE', paymentMethod: 'CASH', currency: 'GBP', customerId, contactPhone: phone, items: [{ variantId, qty: 1 }] } }),
      201,
      'till sale'
    );
    if (pay) {
      must(call('POST', '/api/payment-svc/payments', { token: cashier.token, idem: true, body: { orderId: placed.id, amount: placed.total, method: 'CASH', storeId: store.id, currency: 'GBP' } }), [200, 201], 'cash payment');
    }
    return placed;
  };
  let saleGone;
  let saleKept;
  let saleHeld;
  let saleOpen;
  group('2. four sales', () => {
    saleGone = sale(gone.id, '+447400900111', true);
    saleKept = sale(kept.id, '+447400900222', true);
    saleHeld = sale(undefined, '+447400900333', true);
    saleOpen = sale(undefined, '+447400900444', false);
    truthy('[+] three are settled', poll(60, () => [saleGone, saleKept, saleHeld].every((o) => orderOf(o.id).status === 'FULFILLED')) >= 0);
    truthy('[+] one is still open', orderOf(saleOpen.id).status === 'PENDING', orderOf(saleOpen.id).status);
    truthy('[+] both customers were emailed their confirmations', poll(60, () => logFor(gone.email).length > 0 && logFor(kept.email).length > 0) >= 0, { gone: logFor(gone.email).length, kept: logFor(kept.email).length });
  });

  // ── holds ─────────────────────────────────────────────────────────────────────
  let classHold;
  group('3. holds', () => {
    const place = (body, token = owner) => call('POST', `${SHEET}/holds`, { token, body });
    expect(place({ subjectKind: 'ALL', subjectId: kept.id, reason: 'x' }), '[-] a hold on everything names no one', 400, 'RETENTION_HOLD_SUBJECT');
    expect(place({ subjectKind: 'CUSTOMER', reason: 'x' }), '[-] a hold on a customer names one', 400, 'RETENTION_HOLD_SUBJECT');
    expect(place({ subjectKind: 'ORDER', subjectId: 'not-an-id', reason: 'x' }), '[-] an order id that is not one', 400);
    expect(place({ subjectKind: 'LOTS', reason: 'x' }), '[-] a kind that does not exist', 400);
    expect(place({ subjectKind: 'ALL', dataClass: 'DIARIES', reason: 'x' }), '[-] a class that does not exist', 400, 'RETENTION_CLASS_UNKNOWN');
    expect(place({ subjectKind: 'ALL' }), '[-] a hold with no reason', 400);
    expect(place({ subjectKind: 'ALL', reason: 'x' }, cashier.token), '[-] a cashier cannot place a hold', 403);
    expect(place({ subjectKind: 'CUSTOMER', subjectId: kept.id, reason: 'Open complaint' }), '[+] a hold on a customer, every class', 201);
    expect(place({ subjectKind: 'ORDER', subjectId: saleHeld.id, dataClass: 'ORDER_PERSONAL_DATA', reason: 'Chargeback' }), '[+] a hold on one order', 201);
    classHold = data(place({ subjectKind: 'ALL', dataClass: 'NOTIFICATION_LOG', reason: 'Regulator enquiry' }));
    truthy('[+] a hold on a whole class', classHold.active === true && classHold.dataClass === 'NOTIFICATION_LOG', classHold);
    truthy('[+] three holds in force', (sheet().holds || []).length === 3, sheet().holds);
    expect(call('POST', `${SHEET}/holds/${classHold.id}/release`, { token: rival.owner.token, body: { reason: 'x' } }), "[-] a rival cannot release our hold", 404);
    truthy('[-] and sees none of ours', (data(call('GET', `${SHEET}/holds`, { token: rival.owner.token })) || []).length === 0);
    expect(call('GET', `${SHEET}/holds`, { token: storekeeper.token }), '[-] a storekeeper does not read the holds', 403);
  });

  // ── personal details off settled orders ───────────────────────────────────────
  group('4. orders', () => {
    expect(sweep('order'), '[-] no period set: nothing to purge', 409, 'RETENTION_PERIOD_NOT_SET');
    expect(setPeriod('ORDER_PERSONAL_DATA', 0), '[+] the business decides: as soon as settled', 200);
    // Set, then run at once: the purge reads the schedule it was just given.
    const race = http.batch(
      Array.from({ length: 20 }, () => [
        'POST',
        `${BASE}${SWEEP.order}`,
        '{}',
        { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${owner}` }, tags: { name: 'POST /api/order-svc/admin/orders/retention/sweep (race)' } },
      ])
    );
    const runs = race.map((r) => data(r));
    const purged = runs.reduce((n, r) => n + (r.rowsAffected || 0), 0);
    truthy('[+] twenty sweeps at once redact the one order due, once between them', race.every((r) => r.status === 200) && purged === 1, { statuses: race.map((r) => r.status).join(','), purged });
    truthy('[+] ...and every one counts the two held orders as held', runs.every((r) => r.heldSkipped === 2), runs.map((r) => r.heldSkipped));
    truthy('[+] the settled order lost its phone, in both forms, and kept its total', orderOf(saleGone.id).contactPhone === undefined && orderOf(saleGone.id).contactPhoneE164 === undefined && Number(orderOf(saleGone.id).total) > 0, orderOf(saleGone.id));
    truthy('[+] the held customer\'s order kept its phone', orderOf(saleKept.id).contactPhone === '+447400900222' && orderOf(saleKept.id).contactPhoneE164 === '+447400900222');
    truthy('[+] the held order kept its phone', orderOf(saleHeld.id).contactPhone === '+447400900333');
    truthy('[+] the open order kept its phone', orderOf(saleOpen.id).contactPhone === '+447400900444');
    expect(sweep('order', cashier.token), '[-] a cashier cannot run a purge', 403);
    expect(sweep('order', storekeeper.token), '[-] nor a storekeeper', 403);
    expect(sweep('order', shopper.token), '[-] nor a shopper', [401, 403]);
    expect(sweep('order', rival.owner.token), "[-] a rival's owner purges only their own business, which has no period", 409, 'RETENTION_PERIOD_NOT_SET');
    truthy('[+] our orders are untouched by the rival\'s attempt', orderOf(saleKept.id).contactPhone === '+447400900222');
  });

  // ── the notification log ──────────────────────────────────────────────────────
  group('5. messages', () => {
    expect(setPeriod('NOTIFICATION_LOG', 0), '[+] messages go as soon as sent', 200);
    const held = data(sweep('notification'));
    truthy('[+] a hold on the class stops the purge', held.rowsAffected === 0 && held.heldSkipped >= 2 && logFor(gone.email).length > 0, held);
    expect(call('POST', `${SHEET}/holds/${classHold.id}/release`, { token: owner, body: {} }), '[-] a release needs a reason', 400);
    expect(call('POST', `${SHEET}/holds/${classHold.id}/release`, { token: cashier.token, body: { reason: 'x' } }), '[-] a cashier cannot release a hold', 403);
    expect(call('POST', `${SHEET}/holds/${classHold.id}/release`, { token: owner, body: { reason: 'Enquiry closed' } }), '[+] the class hold is released', 200);
    expect(call('POST', `${SHEET}/holds/${classHold.id}/release`, { token: owner, body: { reason: 'Again' } }), '[-] not twice', 409, 'RETENTION_HOLD_RELEASED');
    const run = data(sweep('notification'));
    truthy('[+] now the messages go, and the held customer\'s stay', run.rowsAffected >= 1 && run.heldSkipped >= 1 && logFor(gone.email).length === 0 && logFor(kept.email).length > 0, { run, gone: logFor(gone.email).length, kept: logFor(kept.email).length });
  });

  // ── customer records ──────────────────────────────────────────────────────────
  group('6. customers', () => {
    expect(sweep('customer'), '[-] no period set for customer records', 409, 'RETENTION_PERIOD_NOT_SET');
    expect(setPeriod('CUSTOMER_RECORDS', 0), '[+] customer records go when nothing is happening on them', 200);
    const run = data(sweep('customer'));
    truthy('[+] the inactive record is erased, the held one kept', run.rowsAffected === 1 && run.heldSkipped === 1, run);
    const customer = (id) => data(call('GET', `/api/customer-svc/customers/${id}`, { token: owner }));
    truthy('[+] ...erased as the customer could have asked', customer(gone.id).status === 'ANONYMIZED' && !String(customer(gone.id).email).includes('retention-gone'), customer(gone.id));
    truthy('[+] ...and the held customer is still themselves', customer(kept.id).status === 'ACTIVE' && customer(kept.id).email === kept.email, customer(kept.id));
    truthy('[+] a second run finds nothing more', data(sweep('customer')).rowsAffected === 0);
    expect(sweep('customer', cashier.token), '[-] a cashier cannot erase customers by purge', 403);
  });

  // ── the register ──────────────────────────────────────────────────────────────
  group('7. the register', () => {
    let runs = [];
    const services = () => new Set(runs.map((r) => r.service));
    const orderRuns = () => runs.filter((r) => r.service === 'order-svc');
    truthy(
      '[+] every purge is on the register, from all three services',
      poll(90, () => {
        runs = data(call('GET', `${SHEET}/runs?limit=100`, { token: owner })) || [];
        // The register is fed by events, one per run: wait for the last of order-svc's twenty, not the first.
        return orderRuns().length >= 20 && services().has('notification-svc') && services().has('customer-svc');
      }) >= 0,
      runs.map((r) => `${r.service}:${r.rowsAffected}`)
    );
    truthy('[+] ...each order-svc sweep once, one of them the redaction', orderRuns().length === 20 && orderRuns().some((r) => r.rowsAffected === 1), orderRuns().length);
    truthy('[-] a rival\'s register is empty', (data(call('GET', `${SHEET}/runs`, { token: rival.owner.token })) || []).length === 0);
    expect(call('GET', `${SHEET}/runs`, { token: storekeeper.token }), '[-] a storekeeper does not read the register', 403);
    truthy('[+] the transactions themselves are never purged', Number(orderOf(saleGone.id).total) > 0 && orderOf(saleGone.id).items.length === 1, orderOf(saleGone.id));
    truthy('[+] a reply error names no stack or SQL', errorCode(setPeriod('DIARIES', 1)) === 'RETENTION_CLASS_UNKNOWN');
  });
}
