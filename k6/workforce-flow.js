// The roster and the time clock (store operations & workforce), through the gateway.
//
// A shop's biggest controllable cost is its hours, and the platform kept no record of them: staff were
// assigned to stores with roles, and nothing said who was meant to be in on Tuesday or who actually
// was. Every other row in this domain leans on it — labour cost against sales cannot be computed
// without hours, and an absence cannot be seen without a plan to compare against.
//
// What only a live stack proves is who may press what. Clocking in is the staff's own act, so it sits
// OUTSIDE the /admin/ prefix the gateway and the services gate to management: a cashier clocks
// themselves in and is refused the management roster in the same breath, and a manager writing
// somebody else's hours goes through a different route that records who pressed it.
//
// The assertions that cost somebody money are here too: one open entry per person however many times
// the button is pressed, an unpaid break coming off the hours, an open entry reported as open rather
// than as zero, and a correction that supersedes rather than edits.
//
// Refused: a shift or a clock-in at a store somebody is not assigned to, a shift rostered for somebody
// else, a second break, clocking out twice, a correction with no reason or no Idempotency-Key, a key
// sent again with another reason, correcting an entry already corrected, a cashier reading the management roster, another business touching these hours, and an
// anonymous caller.
//
//   k6/run.sh workforce-flow
import { Counter } from 'k6/metrics';
import { ALL_CHECKS_PASS, addStore, call, data, expect, newKey, poll, sellingTenant, staffUser, truthy, uniq } from './lib/storeql.js';

const completed = new Counter('flow_completed');
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
  setupTimeout: '4m',
};

const W = '/api/tenant-svc/admin/workforce';
const CLOCK = '/api/tenant-svc/workforce/clock';

/** An instant, hours from the start of a day some days from now. */
function at(daysFromNow, hour) {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() + daysFromNow);
  d.setUTCHours(hour, 0, 0, 0);
  // Seconds precision: the platform writes an instant without milliseconds, and a string comparison
  // against ".000Z" would fail for a reason that has nothing to do with the hours.
  return d.toISOString().replace('.000Z', 'Z');
}

export function setup() {
  // sellingTenant gives a shop with a store and the two staff roles, so no fixture is built twice.
  // A business in Germany: the daily-rest and break rules asserted below are the EU working-time directive's,
  // and they are applied only to a business they bind. One outside that regime is not flagged for them.
  const shop = sellingTenant(`workforce-${uniq().slice(0, 6)}`, { country: 'DE', currency: 'EUR' });
  const rival = shop.rival;
  return { shop, rival };
}

export default function ({ shop, rival }) {
  const owner = shop.tenant.owner.token;
  const cashier = shop.cashier;
  const keeper = shop.storekeeper;
  const store = shop.store.id;
  const post = (path, body, token) => call('POST', path, { token: token || owner, body });
  const get = (path, token) => call('GET', path, { token: token || owner });

  // ── the rota ─────────────────────────────────────────────────────────────────────────────────────
  const late = data(
    post(`${W}/shifts`, { storeId: store, userId: cashier.userId, startsAt: at(1, 14), endsAt: at(1, 22) }),
  );
  truthy('[+] a shift is rostered, planned rather than published', late && late.status === 'PLANNED', late);
  truthy('[+] ...and reads as hours, the way a rota is discussed', late && late.hours === '8.0', late);
  const early = data(
    post(`${W}/shifts`, { storeId: store, userId: cashier.userId, startsAt: at(2, 6), endsAt: at(2, 12) }),
  );
  truthy('[+] a second shift the next morning', !!early, early);

  const roster = data(get(`${W}/shifts?from=${at(0, 0)}&to=${at(5, 0)}`));
  const codes = (roster.concerns || []).map((c) => c.code);
  truthy(
    '[+] eight hours between two shifts is said, not refused: the directive is national law, with derogations',
    codes.includes('DAILY_REST_SHORT'),
    roster.concerns,
  );
  truthy('[+] ...and an eight-hour shift asks for a break', codes.includes('BREAK_EXPECTED'), codes);
  truthy(
    '[+] ...each concern naming the person it is about, because rest is a fact about a person',
    (roster.concerns || []).every((c) => c.userId === cashier.userId),
    roster.concerns,
  );

  // Publishing is a retryable write (intent/workforce-rules.md): each call carries its own fresh
  // Idempotency-Key, so the second is a new attempt and meets the shift already published.
  expect(
    call('POST', `${W}/shifts/${late.id}/publish`, { token: owner, body: {}, idem: true }),
    '[+] published, and staff may rely on it',
    200,
  );
  expect(
    call('POST', `${W}/shifts/${late.id}/publish`, { token: owner, body: {}, idem: true }),
    '[-] only a planned shift is published',
    409,
    'WORKFORCE_SHIFT_NOT_PLANNED',
  );
  expect(post(`${W}/shifts/${early.id}/cancel`, { reason: '' }), '[-] called off with no reason is refused', 400);
  const cancelled = data(post(`${W}/shifts/${early.id}/cancel`, { reason: 'store closed for a delivery' }));
  truthy('[+] a called-off shift keeps why', cancelled.status === 'CANCELLED' && !!cancelled.cancelledReason, cancelled);

  expect(
    post(`${W}/shifts`, { storeId: store, userId: shop.rival.owner.userId, startsAt: at(1, 9), endsAt: at(1, 17) }),
    '[-] somebody who does not work at the store is not rostered there',
    409,
    'WORKFORCE_NOT_ASSIGNED',
  );

  // ── the clock, as the person's own act ───────────────────────────────────────────────────────────
  const inEntry = data(post(`${CLOCK}/in`, { storeId: store }, cashier.token));
  truthy('[+] a cashier clocks themselves in, outside the management prefix', !!inEntry && inEntry.source === 'CLOCK', inEntry);
  // Absent rather than null: the envelope omits nulls, and absent is the point — an open entry
  // reported as zero looks like a day nobody worked.
  truthy(
    '[+] ...and an open entry has no hours yet, rather than zero',
    inEntry && !('hoursWorked' in inEntry),
    inEntry,
  );
  expect(
    post(`${CLOCK}/in`, { storeId: store }, cashier.token),
    '[-] a second tap is one entry, not two afternoons\' pay',
    409,
    'WORKFORCE_ALREADY_CLOCKED_IN',
  );
  truthy(
    '[+] the open entry is the one they are on',
    data(get(`${CLOCK}/open`, cashier.token)).id === inEntry.id,
  );

  expect(post(`${CLOCK}/breaks/start`, { kind: 'MEAL', paid: false }, cashier.token), '[+] a break starts', 200);
  expect(
    post(`${CLOCK}/breaks/start`, { kind: 'REST', paid: true }, cashier.token),
    '[-] one break at a time, or the day\'s arithmetic is undecidable',
    409,
    'WORKFORCE_BREAK_OPEN',
  );
  const out = data(post(`${CLOCK}/out`, {}, cashier.token));
  truthy('[+] going home closes a break somebody forgot to end', out && out.breaks[0].endedAt !== null, out);
  truthy('[+] ...and the hours are known once the entry is closed', out && out.hoursWorked !== null, out);
  expect(post(`${CLOCK}/out`, {}, cashier.token), '[-] clocking out twice is refused', 409, 'WORKFORCE_NOT_CLOCKED_IN');
  expect(
    post(`${CLOCK}/breaks/start`, { kind: 'REST', paid: true }, cashier.token),
    '[-] and a break belongs to a shift somebody is working',
    409,
    'WORKFORCE_NOT_CLOCKED_IN',
  );

  // A shift rostered for somebody else is not one you can clock on to.
  const keeperShift = data(
    post(`${W}/shifts`, { storeId: store, userId: keeper.userId, startsAt: at(0, 9), endsAt: at(0, 17) }),
  );
  expect(
    post(`${CLOCK}/in`, { storeId: store, shiftId: keeperShift.id }, cashier.token),
    '[-] a shift is not transferable',
    400,
    'WORKFORCE_SHIFT_NOT_THEIRS',
  );

  // ── a correction, which supersedes ───────────────────────────────────────────────────────────────
  // Correcting hours is a retryable write (intent/workforce-rules.md): every attempt carries an
  // Idempotency-Key, and the same request sent again under its key is answered with the correction it
  // made rather than with "already corrected".
  const adjust = (id, body, idem = true) => call('POST', `${W}/time-entries/${id}/adjust`, { token: owner, body, idem });
  const correction = {
    clockedInAt: at(0, 9),
    clockedOutAt: at(0, 17),
    reason: 'the terminal was down at the end of the shift',
  };
  expect(
    call('POST', `${W}/time-entries/${inEntry.id}/adjust`, { token: owner, body: correction }),
    '[-] a correction is a retryable write, so it carries a key',
    400,
    'IDEMPOTENCY_KEY_REQUIRED',
  );
  expect(
    adjust(inEntry.id, { clockedOutAt: at(0, 17), reason: '' }),
    '[-] a correction without a reason is an edit with extra steps',
    400,
    'VALIDATION_FAILED',
  );
  const fixKey = newKey();
  const fixed = data(adjust(inEntry.id, correction, fixKey));
  truthy('[+] a correction supersedes the entry it replaces', fixed && fixed.supersedes === inEntry.id, fixed);
  truthy('[+] ...is recorded as the manager\'s act, not the person\'s', fixed && fixed.source === 'MANAGER', fixed);
  // The corrected clock times, and the break carried across with them — which is why the hours are a
  // little under the eight the times span: the unpaid break comes off, as it must.
  truthy(
    '[+] ...with the corrected clock times, and the break carried across',
    fixed && fixed.clockedInAt === at(0, 9) && fixed.clockedOutAt === at(0, 17) && fixed.breaks.length === 1,
    fixed,
  );
  truthy(
    '[+] ...so the hours are the time spanned less the unpaid break',
    fixed && fixed.hoursWorked !== undefined && Number(fixed.hoursWorked) > 7.8 && Number(fixed.hoursWorked) <= 8,
    fixed,
  );
  const retried = adjust(inEntry.id, correction, fixKey);
  expect(retried, '[+] the same correction sent again under its key is answered with the one it made', 200);
  truthy('[+] ...the same entry, not a second correction', data(retried).id === fixed.id, data(retried));
  expect(
    adjust(inEntry.id, { ...correction, reason: 'something else' }, fixKey),
    '[-] the key sent with another reason is not a replay',
    409,
    'IDEMPOTENCY_KEY_REUSED',
  );
  // A whole window: it is judged before whether the entry stands, and a clock-out alone would be read
  // against the real clock-in (the moment this ran), so from 18:00 UTC it would be refused as a window.
  expect(
    adjust(inEntry.id, { clockedInAt: at(0, 9), clockedOutAt: at(0, 18), reason: 'again' }),
    '[-] an entry already corrected is not corrected twice',
    409,
    'WORKFORCE_ENTRY_NOT_STANDING',
  );
  const entries = data(get(`${W}/time-entries?from=${at(0, 0)}&to=${at(1, 0)}`)) || [];
  truthy(
    '[+] the hours of a window count the correction once, not both entries',
    entries.filter((e) => e.userId === cashier.userId).length === 1,
    entries,
  );

  // ── attendance ───────────────────────────────────────────────────────────────────────────────────
  const attendance = data(get(`${W}/attendance?from=${at(-1, 0).slice(0, 10)}&to=${at(2, 0).slice(0, 10)}`)) || [];
  const today = attendance.find((d) => d.userId === cashier.userId && d.day === at(0, 0).slice(0, 10));
  truthy('[+] the day shows the hours worked against what was rostered', !!today, attendance);
  truthy(
    '[+] ...and the storekeeper rostered today with nothing clocked is an absence',
    attendance.some((d) => d.userId === keeper.userId && d.absent === true),
    attendance,
  );

  // A manager held to one store reads that store's attendance and is refused another store's.
  const otherStore = addStore(shop.tenant, 'WF2');
  const heldManager = staffUser(shop.tenant, 'MANAGER', [store]);
  expect(
    get(`${W}/attendance?store=${otherStore.id}`, heldManager.token),
    '[-] a manager held to one store does not read another store\'s attendance',
    403,
    'STORE_ACCESS_DENIED',
  );
  expect(get(`${W}/attendance?store=${store}`, heldManager.token), '[+] ...but reads their own store\'s', 200);

  // ── who may press what ───────────────────────────────────────────────────────────────────────────
  expect(get(`${W}/shifts?from=${at(0, 0)}`, cashier.token), '[-] the management roster is management\'s', 403);
  expect(get(`${CLOCK}/shifts`, cashier.token), '[+] but their own roster is theirs to read', 200);
  expect(
    post(`${W}/time-entries?user=${cashier.userId}`, { storeId: store }, cashier.token),
    '[-] a cashier does not write somebody\'s hours',
    403,
  );
  const written = data(post(`${W}/time-entries?user=${keeper.userId}`, { storeId: store }));
  truthy(
    '[+] a manager may, and it is recorded as theirs so an audit knows who pressed it',
    written && written.source === 'MANAGER' && written.userId === keeper.userId,
    written,
  );
  expect(call('POST', `${CLOCK}/in`, { body: { storeId: store } }), '[-] an anonymous caller is refused', 401);
  expect(
    get(`${W}/time-entries?from=${at(0, 0)}`, rival.owner.token),
    '[+] another business reads its own hours, which are none',
    200,
  );
  truthy(
    '[+] ...and not this one\'s',
    (data(get(`${W}/time-entries?from=${at(0, 0)}`, rival.owner.token)) || []).length === 0,
  );

  // ── labour against sales: the hop only a live stack proves ───────────────────────────────────────
  //
  // What an hour costs is tenant-svc's, the sales are order-svc's, and the report is reporting-svc's.
  // The event that joins them carries a store, a day and money and NO PERSON, so pay never leaves the
  // service that keeps it — and that is exactly what cannot be checked without the three of them
  // running.
  expect(
    post(`${W}/pay-rates`, { userId: keeper.userId, hourlyRate: '12.00' }),
    '[+] what an hour of somebody\'s time costs, from a date',
    201,
  );
  expect(
    post(`${W}/pay-rates`, { userId: keeper.userId, hourlyRate: '13.50' }),
    '[-] two rates starting the same morning is an undecidable cost',
    409,
    'WORKFORCE_RATE_EXISTS',
  );
  expect(
    post(`${W}/pay-rates`, { userId: keeper.userId, hourlyRate: '-1.00', effectiveFrom: '2026-01-01' }),
    '[-] an hour costs nothing or something, never less than nothing',
    400,
    'WORKFORCE_RATE_INVALID',
  );
  expect(get(`${W}/pay-rates?user=${keeper.userId}`, cashier.token), '[-] and what people are paid is management\'s', 403);

  // A shift worked, and a sale taken, on the same day. The clock-out is then corrected to the shift
  // actually worked — as a manager does for a terminal that was down — which is also how a real
  // number of hours gets into a live test without waiting eight hours for it.
  const openKeeper = data(get(`${CLOCK}/open`, keeper.token));
  const keeperEntry = openKeeper ? openKeeper.id : data(post(`${CLOCK}/in`, { storeId: store }, keeper.token)).id;
  const worked = data(post(`${CLOCK}/out`, {}, keeper.token));
  truthy('[+] the storekeeper\'s hours are recorded', worked && worked.hoursWorked !== undefined, worked);
  const fullShift = data(
    adjust(keeperEntry, {
      clockedInAt: at(0, 9),
      clockedOutAt: at(0, 17),
      reason: 'the back-store terminal was down all morning',
    }),
  );
  truthy('[+] ...and corrected to the shift they actually worked', fullShift && fullShift.hoursWorked === '8.0', fullShift);
  const sale = data(
    call('POST', '/api/order-svc/orders', {
      token: owner,
      idem: true,
      body: {
        storeId: store,
        channel: 'POS',
        fulfilmentType: 'INSTORE',
        paymentMethod: 'CASH',
        items: [{ variantId: shop.variantId, qty: 2 }],
      },
    }),
  );
  call('POST', '/api/payment-svc/payments', {
    token: owner,
    idem: true,
    body: { orderId: sale.id, amount: sale.total, method: 'CASH', storeId: store, currency: shop.tenant.currency },
  });

  const reportFrom = at(0, 0).slice(0, 10);
  const reportTo = at(1, 0).slice(0, 10);
  let row = null;
  const seconds = poll(90, () => {
    const report = data(call('GET', `/api/reporting-svc/admin/reports/sales/labour?from=${reportFrom}&to=${reportTo}`, { token: owner }));
    row = ((report || {}).rows || []).find((r) => r.day === reportFrom);
    // Wait for the storekeeper's costed shift too, not only the first row of the day: the cashier's
    // corrected (uncosted) shift and the storekeeper's are two events, and the report may show the
    // first a few seconds before the second. A cost that never arrives still fails the checks below.
    return !!row && Number(row.hours) > 0 && Number(row.net) > 0 && row.labourCost != null && Number(row.labourCost) >= 96;
  });
  truthy(`[+] the day's hours and its takings meet in one report, ${seconds}s after the clock`, seconds >= 0, row);
  truthy(
    '[+] ...with the eight hours costed at the 12.00 rate in force on the day',
    row && row.labourCost !== null && Number(row.labourCost) >= 96,
    row,
  );
  truthy(
    '[+] ...the correction having replaced the figure it superseded rather than adding to it',
    row && Number(row.hours) < 20,
    row,
  );
  truthy(
    '[+] ...and labour as a share of what was taken, which is the figure a shop is run on',
    row && row.labourPercent !== null && Number(row.labourPercent) > 0,
    row,
  );
  truthy(
    '[+] ...while the cashier\'s hours, which nobody set a rate for, are counted and called uncosted rather than free',
    row && Number(row.uncostedHours) > 0,
    row,
  );
  expect(
    call('GET', `/api/reporting-svc/admin/reports/sales/labour?from=${reportFrom}&to=${reportTo}`, { token: cashier.token }),
    '[-] a cashier does not read the labour report',
    403,
  );
  truthy(
    '[+] and another business sees none of it',
    (
      (data(
        call('GET', `/api/reporting-svc/admin/reports/sales/labour?from=${reportFrom}&to=${reportTo}`, {
          token: rival.owner.token,
        }),
      ) || {}).rows || []
    ).every((r) => Number(r.hours) === 0),
  );

  completed.add(1);
}
