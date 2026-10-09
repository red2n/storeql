// A drawer counts its own money, through the gateway (intent/till-sessions-and-registers.md, slice 3).
//
// Two tills open at one store on the SESSION basis each report only the tenders, refunds and drops that name
// them: the other till's takings are not in this one's expected cash, cash a return paid out is counted in
// the drawer that gave it, and money naming no session is shown apart as "not at a till" and is in no drawer.
// A drawer opened the old way (no basis) still counts the store's money in its window, whichever drawer that
// money names. A tender naming a session that is closed, or another business's, is refused and writes nothing
// -- the app answers that by sending the tender again naming none -- but a RETRY of a tender that was taken
// is answered with the first tender, whatever became of its drawer; so is a retried manager's refund. Every
// way cash leaves a drawer is counted in the drawer that gave it (a return, an exchange's cash back, a
// cancelled held sale, a void, a manager's refund), what is stored at the close is what the close answered,
// and another business reads none of it.
//
//   k6/run.sh till-sessions-flow
import { Counter } from 'k6/metrics';
import { ALL_CHECKS_PASS, call, data, expect, must, newId, poll, sellingTenant, staffUser, truthy } from './lib/storeql.js';

const completed = new Counter('flow_completed');
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
  setupTimeout: '4m',
};

const O = '/api/order-svc';
const P = '/api/payment-svc';
const TILLS = `${P}/admin/cash/till-sessions`;

export function setup() {
  const shop = sellingTenant('till-money');
  shop.manager = staffUser(shop.tenant, 'MANAGER', [shop.store.id]);
  shop.cashierTwo = staffUser(shop.tenant, 'CASHIER', [shop.store.id]);
  shop.cashierThree = staffUser(shop.tenant, 'CASHIER', [shop.store.id]);
  return shop;
}

export default function ({ tenant, rival, store, variantId, cashier, cashierTwo, cashierThree, manager }) {
  const owner = tenant.owner.token;
  const num = (v) => Number(v || 0);
  const same = (a, b) => Math.abs(num(a) - num(b)) < 0.0051;

  const open = (who, extra = {}) => call('POST', TILLS, { token: who.token, body: { storeId: store.id, floatAmount: 100, ...extra } });
  const xReport = (id, token = manager.token) => call('GET', `${TILLS}/${id}/x-report`, { token });
  const report = (id) => must(xReport(id), 200, 'the drawer’s X report');
  const cashIn = (r, section) => num((((r[section] || {}).CASH) || {}).sales);

  /** A till sale of one item, unpaid: its order id and what is owed. */
  const sale = (qty = 1) => must(
    call('POST', `${O}/orders`, {
      token: owner, idem: true,
      body: { storeId: store.id, channel: 'POS', fulfilmentType: 'INSTORE', paymentMethod: 'CASH', items: [{ variantId, qty }] },
    }),
    201,
    'a till sale'
  );
  /** Cash for a sale, naming the drawer it was rung on (or none). */
  const pay = (order, session, token = owner) => call('POST', `${P}/payments`, {
    token, idem: true, raw: true,
    body: { orderId: order.id, amount: order.total, method: 'CASH', storeId: store.id, ...(session ? { tillSessionId: session } : {}) },
  });
  /** The same tender under a key of the caller's choosing, so a lost response can be retried. */
  const payKeyed = (order, session, key) => call('POST', `${P}/payments`, {
    token: owner, idem: key, raw: true,
    body: { orderId: order.id, amount: order.total, method: 'CASH', storeId: store.id, ...(session ? { tillSessionId: session } : {}) },
  });
  const closeAt = (id, counted) => call('POST', `${TILLS}/${id}/close`, { token: manager.token, body: { countedCash: Number(counted).toFixed(2) } });
  const paid = (order, session) => {
    const t = must(pay(order, session), [200, 201], 'cash taken');
    truthy('[+] the sale is handed over', poll(60, () => (data(call('GET', `${O}/orders/${order.id}`, { token: owner })) || {}).status === 'FULFILLED') >= 0);
    return t;
  };

  // ── two tills at one store, each its own drawer ─────────────────────────────────────────────────
  const a = must(open(cashier, { basis: 'SESSION' }), 201, 'till A opens');
  const b = must(open(cashierTwo, { floatAmount: 50, basis: 'SESSION' }), 201, 'till B opens at the same store');
  truthy('[+] each is on the SESSION basis', a.basis === 'SESSION' && b.basis === 'SESSION', [a, b]);

  const sale1 = sale(2);
  const sale2 = sale(3);
  paid(sale1, a.id);
  paid(sale2, b.id);
  const ra = report(a.id);
  const rb = report(b.id);
  truthy('[+] till A reports its own basis and its own sale only', ra.basis === 'SESSION' && same(ra.cashSales, sale1.total), ra);
  truthy('[+] ...and expects its float plus that sale', same(ra.expectedCashInTill, 100 + num(sale1.total)), ra);
  truthy('[+] till B counts its own, not A’s', same(rb.cashSales, sale2.total) && same(rb.expectedCashInTill, 50 + num(sale2.total)), rb);

  // ── money naming no drawer is in none ───────────────────────────────────────────────────────────
  const loose = sale(1);
  paid(loose, null);
  const afterLoose = report(a.id);
  truthy('[+] a tender naming no session is shown apart, as not at a till', same(cashIn(afterLoose, 'notAtTill'), loose.total), afterLoose.notAtTill);
  truthy('[+] ...and is in neither drawer', same(afterLoose.expectedCashInTill, ra.expectedCashInTill) && same(report(b.id).expectedCashInTill, rb.expectedCashInTill), afterLoose);

  // ── cash a return paid out comes from the drawer that gave it ───────────────────────────────────
  const given = must(
    call('POST', `${O}/orders/${sale1.id}/returns`, {
      token: manager.token, idem: true,
      body: { reason: 'k6 return', refundMethod: 'ORIGINAL', tillSessionId: b.id, items: [{ variantId, qty: 1, condition: 'SEALED' }] },
    }),
    [200, 201],
    'a return given at till B'
  );
  const refunded = num(data(given).refundAmount);
  let rbAfter = {};
  truthy('[+] till B counts the cash it paid out', poll(60, () => {
    rbAfter = report(b.id);
    return same(rbAfter.cashRefunds, refunded);
  }) >= 0, rbAfter);
  truthy('[+] ...so B expects its float plus its sale less the refund', same(rbAfter.expectedCashInTill, 50 + num(sale2.total) - refunded), rbAfter);
  truthy('[+] ...while A, whose sale it was, is untouched', same(report(a.id).expectedCashInTill, 100 + num(sale1.total)), report(a.id));

  // ── a drop is its drawer's ──────────────────────────────────────────────────────────────────────
  expect(call('POST', `${TILLS}/${a.id}/drops`, { token: manager.token, idem: true, body: { amount: 5 } }), '[+] a cash drop is taken from till A', [200, 201]);
  truthy('[+] ...and only A’s expected cash falls', same(report(a.id).expectedCashInTill, 100 + num(sale1.total) - 5) && same(report(b.id).expectedCashInTill, 50 + num(sale2.total) - refunded), report(a.id));

  // ── what a session must be ──────────────────────────────────────────────────────────────────────
  const rivalToken = rival.owner.token;
  expect(call('POST', `${P}/payments`, {
    token: rivalToken, idem: true, raw: true,
    body: { orderId: newId(), amount: '1.00', method: 'CASH', storeId: rival.stores[0].id, tillSessionId: a.id },
  }), '[-] another business’s tender cannot name our drawer', 404, 'TILL_SESSION_NOT_FOUND');
  expect(xReport(a.id, rivalToken), '[-] another business cannot read our drawer', 404);
  expect(call('POST', `${P}/payments`, { token: owner, idem: true, raw: true, body: { orderId: sale(1).id, amount: '1.00', method: 'CASH', storeId: store.id, tillSessionId: 'not-an-id' } }),
    '[-] a session id that is not an id is a 400', 400);
  expect(open(cashierThree, { basis: 'MAYBE' }), '[-] a basis nobody defined is refused', 400, 'TILL_BASIS_INVALID');

  // ── closing: each drawer counts its own cash, and says why it is short ──────────────────────────
  const expectedA = num(report(a.id).expectedCashInTill);
  const closedA = call('POST', `${TILLS}/${a.id}/close`, { token: manager.token, body: { countedCash: (expectedA - 1).toFixed(2), note: 'gave a pound too much change' } });
  expect(closedA, '[+] till A closes, counted a pound short', 200);
  truthy('[+] ...and says so: over/short is -1.00, with the note', same(data(closedA).overShort, -1) && data(closedA).note === 'gave a pound too much change', data(closedA));
  const expectedB = num(report(b.id).expectedCashInTill);
  const closedB = call('POST', `${TILLS}/${b.id}/close`, { token: manager.token, body: { countedCash: expectedB.toFixed(2) } });
  expect(closedB, '[+] till B closes on the exact count', 200);
  truthy('[+] ...with nothing over or short', same(data(closedB).overShort, 0), data(closedB));
  const storedA = must(call('GET', `${TILLS}/${a.id}`, { token: manager.token }), 200, 'till A read back');
  truthy('[+] what is stored at the close is what the close answered', storedA.status === 'CLOSED' && same(storedA.overShort, data(closedA).overShort), storedA);
  truthy('[+] ...and a closed drawer’s report does not move afterwards', same(report(a.id).expectedCashInTill, expectedA) && same(report(b.id).expectedCashInTill, expectedB), report(a.id));

  // ── a session that has been closed is refused, and nothing is written ───────────────────────────
  const stale = sale(1);
  expect(pay(stale, b.id), '[-] a tender naming a closed drawer is refused', 409, 'TILL_SESSION_NOT_OPEN');
  const bare = call('GET', `${P}/payments/by-order/${stale.id}`, { token: owner });
  truthy('[+] ...and wrote nothing', (data(bare) || []).length === 0, data(bare));
  expect(pay(stale, null), '[+] the same money, naming none, is recorded: it is the cashier’s fallback', [200, 201]);

  // ── a retry is answered, whatever became of the drawer ──────────────────────────────────────────
  const retryDrawer = must(open(manager, { basis: 'SESSION' }), 201, 'a drawer for a retried tender');
  const retried = sale(1);
  const retryKey = newId();
  const firstTry = must(payKeyed(retried, retryDrawer.id, retryKey), [200, 201], 'cash taken, the response then lost');
  expect(closeAt(retryDrawer.id, 100 + num(retried.total)), '[+] the drawer is closed before the cashier retries', 200);
  const again = payKeyed(retried, retryDrawer.id, retryKey);
  expect(again, '[+] a retried tender whose drawer has since closed answers the first tender', [200, 201]);
  truthy('[+] ...the same tender, taken once', data(again).id === firstTry.id && (data(call('GET', `${P}/payments/by-order/${retried.id}`, { token: owner })) || []).length === 1, data(again));
  expect(payKeyed(sale(1), retryDrawer.id, newId()), '[-] a new tender naming that closed drawer is still refused', 409, 'TILL_SESSION_NOT_OPEN');

  // ── a manager's refund naming a drawer is judged as a tender is, and a retry of it is answered ──
  const refundDrawer = must(open(manager, { basis: 'SESSION' }), 201, 'a drawer for a manager’s refund');
  const refundable = sale(2);
  const tendered = paid(refundable, refundDrawer.id);
  const refundTo = (token, session, key, amount = 1) => call('POST', `${P}/payments/by-order/${refundable.id}/refunds`, {
    token, idem: key,
    body: { paymentId: tendered.id, amount, method: 'CASH', reason: 'k6 refund', ...(session ? { tillSessionId: session } : {}) },
  });
  expect(refundTo(rivalToken, refundDrawer.id, newId()), '[-] another business’s refund cannot name our drawer', 404, 'TILL_SESSION_NOT_FOUND');
  expect(refundTo(manager.token, b.id, newId()), '[-] a refund naming a closed drawer is refused', 409, 'TILL_SESSION_NOT_OPEN');
  expect(refundTo(manager.token, newId(), newId()), '[-] a refund naming a drawer that does not exist is refused', 404, 'TILL_SESSION_NOT_FOUND');
  truthy('[+] ...and none of them wrote a refund', (data(call('GET', `${P}/payments/by-order/${refundable.id}/refunds`, { token: owner })) || []).length === 0, data(call('GET', `${P}/payments/by-order/${refundable.id}/refunds`, { token: owner })));
  const refundKey = newId();
  const gave = refundTo(manager.token, refundDrawer.id, refundKey);
  expect(gave, '[+] a manager’s refund naming an open drawer is recorded', 201);
  truthy('[+] ...and that drawer expects 1.00 less cash', same(report(refundDrawer.id).expectedCashInTill, 100 + num(refundable.total) - 1), report(refundDrawer.id));
  expect(closeAt(refundDrawer.id, 100 + num(refundable.total) - 1), '[+] the drawer is closed', 200);
  const gaveAgain = refundTo(manager.token, refundDrawer.id, refundKey);
  expect(gaveAgain, '[+] a retried refund whose drawer has since closed answers the first refund', 201);
  truthy('[+] ...the same refund, given once', data(gaveAgain).id === data(gave).id && (data(call('GET', `${P}/payments/by-order/${refundable.id}/refunds`, { token: owner })) || []).length === 1, data(gaveAgain));

  // ── every way cash leaves a till is counted in that till: an exchange's cash back, a held sale ──
  // ── cancelled at the till, a void ──────────────────────────────────────────────────────────────
  const gives = must(open(manager, { basis: 'SESSION' }), 201, 'a drawer for exchange, cancel and void');
  const swapped = sale(2);
  paid(swapped, gives.id);
  const swap = must(call('POST', `${O}/orders/${swapped.id}/exchange`, {
    token: manager.token, idem: true,
    body: { reason: 'k6 exchange', returnItems: [{ variantId, qty: 2, condition: 'SEALED' }], newItems: [{ variantId, qty: 1 }], tillSessionId: gives.id },
  }), [200, 201], 'an exchange rung at the till');
  const cashBack = num(swap.refundToCustomer);
  truthy('[+] returning two and taking one gives some cash back', cashBack > 0, swap);
  let rg = {};
  truthy('[+] the exchange’s cash back leaves the drawer that rang it', poll(60, () => {
    rg = report(gives.id);
    return same(rg.cashRefunds, cashBack);
  }) >= 0, rg);

  const held = sale(2);
  const half = (num(held.total) / 2).toFixed(2);
  must(call('POST', `${P}/payments`, {
    token: owner, idem: true, raw: true,
    body: { orderId: held.id, amount: half, method: 'CASH', storeId: store.id, tillSessionId: gives.id },
  }), [200, 201], 'half of a sale taken in cash');
  expect(call('POST', `${O}/orders/${held.id}/cancel`, { token: manager.token, body: { reason: 'k6 held sale', tillSessionId: gives.id } }), '[+] a held sale is cancelled at the till', 200);
  truthy('[+] the cash it took goes back out of the drawer that cancelled it', poll(60, () => {
    rg = report(gives.id);
    return same(rg.cashRefunds, cashBack + num(half));
  }) >= 0, rg);

  const toVoid = sale(1);
  paid(toVoid, gives.id);
  expect(call('POST', `${O}/orders/${toVoid.id}/void`, { token: manager.token, idem: true, body: { reason: 'k6 void', tillSessionId: gives.id } }), '[+] a till sale is voided at the till', [200, 201]);
  truthy('[+] the cash a void hands back leaves the drawer that voided it', poll(60, () => {
    rg = report(gives.id);
    return same(rg.cashRefunds, cashBack + num(half) + num(toVoid.total));
  }) >= 0, rg);
  truthy('[+] so the drawer expects its float plus the swapped sale less the cash back', same(rg.expectedCashInTill, 100 + num(swapped.total) - cashBack), rg);
  truthy('[+] ...and none of those refunds is left apart as not at a till', same((((rg.notAtTill || {}).CASH) || {}).refunds, 0), rg.notAtTill);
  expect(closeAt(gives.id, 100 + num(swapped.total) - cashBack), '[+] and it closes on that count', 200);

  // ── a drawer opened the old way still counts the store's money in its window ────────────────────
  const exact = must(open(cashierTwo, { basis: 'SESSION' }), 201, 'an exact drawer beside the old-way one');
  const legacy = must(open(cashierThree), 201, 'a drawer opened with no basis');
  truthy('[+] it is on the WINDOW basis', legacy.basis === 'WINDOW', legacy);
  const old = sale(1);
  paid(old, null);
  const named = sale(1);
  paid(named, exact.id);
  const rl = report(legacy.id);
  truthy('[+] it counts the store’s cash in its window, naming a drawer or not', same(rl.cashSales, num(old.total) + num(named.total)) && rl.notAtTill == null, rl);
  truthy('[+] ...while the exact drawer beside it counts only what names it', same(report(exact.id).cashSales, named.total), report(exact.id));
  expect(call('POST', `${TILLS}/${legacy.id}/close`, { token: manager.token, body: { countedCash: num(rl.expectedCashInTill).toFixed(2) } }), '[+] and closes as it always did', 200);
  expect(closeAt(exact.id, num(report(exact.id).expectedCashInTill)), '[+] the exact drawer closes too', 200);

  completed.add(1);
}
