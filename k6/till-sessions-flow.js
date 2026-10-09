// A drawer counts its own money, through the gateway (intent/till-sessions-and-registers.md, slice 3).
//
// Two tills open at one store on the SESSION basis each report only the tenders, refunds and drops that name
// them: the other till's takings are not in this one's expected cash, cash a return paid out is counted in
// the drawer that gave it, and money naming no session is shown apart as "not at a till" and is in no drawer.
// A drawer opened the old way (no basis) still counts the store's money in its window. A tender naming a
// session that is closed, or another business's, is refused and writes nothing -- the app answers that by
// sending the tender again naming none -- and another business reads none of it.
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

  // ── a session that has been closed is refused, and nothing is written ───────────────────────────
  const stale = sale(1);
  expect(pay(stale, b.id), '[-] a tender naming a closed drawer is refused', 409, 'TILL_SESSION_NOT_OPEN');
  const bare = call('GET', `${P}/payments/by-order/${stale.id}`, { token: owner });
  truthy('[+] ...and wrote nothing', (data(bare) || []).length === 0, data(bare));
  expect(pay(stale, null), '[+] the same money, naming none, is recorded: it is the cashier’s fallback', [200, 201]);

  // ── a drawer opened the old way still counts the store's money in its window ────────────────────
  const legacy = must(open(cashierThree), 201, 'a drawer opened with no basis');
  truthy('[+] it is on the WINDOW basis', legacy.basis === 'WINDOW', legacy);
  const old = sale(1);
  paid(old, null);
  const rl = report(legacy.id);
  truthy('[+] it counts the store’s cash in its window, naming a drawer or not', same(rl.cashSales, old.total) && rl.notAtTill == null, rl);
  expect(call('POST', `${TILLS}/${legacy.id}/close`, { token: manager.token, body: { countedCash: num(rl.expectedCashInTill).toFixed(2) } }), '[+] and closes as it always did', 200);

  completed.add(1);
}
