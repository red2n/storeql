// Deferred revenue for loyalty points and gift card breakage (17.11), through the gateway. Points a
// customer earns on a till sale wait until the tenant's accountant sets a point's value and the
// breakage estimates, then post out of sales into deferred income; spending them releases their
// share, and the last lapse is breakage. A gift card sold is a liability against the tender, one
// given away is a cost, and spending one recognises its breakage once. Around it, the refusals and
// the abuse: the wrong roles, a rival tenant, estimates out of range, a card bought with a card or
// with store credit, more points spent than held, a replayed tender.
//
//   k6/run.sh deferred-revenue
import { sleep } from 'k6';
import { Counter } from 'k6/metrics';
import {
  ALL_CHECKS_PASS,
  call,
  data,
  expect,
  must,
  newId,
  poll,
  sellingTenant,
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

const LEDGER = '/api/purchase-svc/nominal-ledger';
const DR = `${LEDGER}/deferred-revenue`;
const ESTIMATES = { pointValue: 0.05, pointsBreakagePct: 20, giftCardBreakagePct: 10, reason: 'Two years of scheme data' };

export function setup() {
  return sellingTenant('deferred-rev', { price: '12.00', costPrice: '6.00' });
}

export default function ({ tenant, rival, store, variantId, storekeeper, cashier }) {
  const owner = tenant.owner.token;
  const num = (v) => Number(v || 0);
  const round = (v) => Math.round((v + Number.EPSILON) * 100) / 100;
  const ledger = () => {
    const all = data(call('GET', `${LEDGER}?limit=100`, { token: owner }));
    return Array.isArray(all) ? all : [];
  };
  const net = (lines, code) => round(lines.filter((l) => l.nominalCode === code).reduce((t, l) => t + num(l.debit) - num(l.credit), 0));
  const of = (lines, sourceType, ref) => lines.filter((l) => l.sourceType === sourceType && (!ref || l.sourceRef === ref));
  const view = (token = owner) => data(call('GET', DR, { token }));
  const setEstimates = (body, token = owner) => call('PUT', `${DR}/settings`, { token, body });
  const giftCard = (body) => call('POST', '/api/order-svc/gift-cards', { token: owner, body: { storeId: store.id, ...body } });
  const placeSale = (qty, customerId) =>
    must(call('POST', '/api/order-svc/orders', { token: owner, idem: true, body: { storeId: store.id, channel: 'POS', fulfilmentType: 'INSTORE', items: [{ variantId, qty }], ...(customerId ? { customerId } : {}) } }), 201, 'till sale');
  const pay = (orderId, amount, method, extra = {}, idem = true) =>
    call('POST', '/api/payment-svc/payments', { token: owner, idem, body: { orderId, amount, method, storeId: store.id, ...extra } });

  // ── 1. the estimates are management's, and refused by name ─────────────────
  expect(call('GET', DR, { token: cashier.token }), '[-] a cashier cannot read deferred revenue', 403);
  expect(setEstimates(ESTIMATES, storekeeper.token), '[-] a storekeeper cannot set the estimates', 403);
  expect(setEstimates(ESTIMATES, cashier.token), '[-] a cashier cannot set the estimates', 403);
  expect(setEstimates({ ...ESTIMATES, pointValue: 0 }), '[-] a point worth nothing is refused', 400, 'PURCHASE_POINT_VALUE_INVALID');
  expect(setEstimates({ ...ESTIMATES, pointValue: 1000.01 }), '[-] a point worth more than 1000 is refused', 400, 'PURCHASE_POINT_VALUE_INVALID');
  expect(setEstimates({ ...ESTIMATES, pointsBreakagePct: 96 }), '[-] 96% of points never spent is refused', 400, 'PURCHASE_BREAKAGE_OUT_OF_RANGE');
  expect(setEstimates({ ...ESTIMATES, giftCardBreakagePct: -1 }), '[-] a negative gift card breakage is refused', 400, 'PURCHASE_BREAKAGE_OUT_OF_RANGE');
  expect(setEstimates({ ...ESTIMATES, reason: '' }), '[-] estimates without a reason are refused', 400);
  expect(setEstimates({ ...ESTIMATES, pointValue: 'lots' }), '[-] a point value that is not a number is refused', 400);
  const fresh = view();
  truthy('[+] a new tenant has no estimates and nothing waiting', fresh && !fresh.settings && fresh.eventsAwaitingEstimates === 0, fresh);

  // ── 2. points earned before any estimates wait ─────────────────────────────
  const customer = must(call('POST', '/api/customer-svc/customers', { token: owner, body: { email: `deferred-${uniq()}@k6.storeql.test`, firstName: 'Dee', lastName: 'Ferral' } }), 201, 'customer');
  const sale = placeSale(10, customer.id);
  const total = num(sale.total);
  must(pay(sale.id, total.toFixed(2), 'CASH'), [200, 201], 'cash for the sale');
  let points = 0;
  const accrued = poll(60, () => {
    points = num(data(call('GET', `/api/customer-svc/customers/${customer.id}/loyalty`, { token: owner })).pointsBalance);
    return points > 0;
  });
  truthy('[+] the customer earns a point per pound on the till sale', accrued >= 0 && points === Math.floor(total * 100) / 100, { points, total });
  const waited = poll(60, () => view().eventsAwaitingEstimates >= 1);
  truthy('[+] the points wait for estimates and post nowhere', waited >= 0 && of(ledger(), 'LOYALTY_DEFERRAL').length === 0, view());

  // ── 3. the estimates set: the waiting points post ──────────────────────────
  const saved = must(setEstimates(ESTIMATES), 200, 'estimates');
  truthy('[+] the estimates are kept in the tenant\'s currency, with their reason', saved.settings && saved.settings.currency === 'GBP' && saved.settings.reason === ESTIMATES.reason, saved);
  truthy('[+] nothing is left waiting once they are set', saved.eventsAwaitingEstimates === 0, saved);
  let lines = [];
  poll(60, () => {
    lines = ledger();
    return of(lines, 'SALE', sale.id).length >= 2 && of(lines, 'LOYALTY_DEFERRAL', sale.id).length >= 2;
  });
  const netRevenue = -net(of(lines, 'SALE', sale.id), '4010');
  const standalone = points * 0.05 * 0.8;
  const deferral = round((netRevenue * standalone) / (netRevenue + standalone));
  const deferred = of(lines, 'LOYALTY_DEFERRAL', sale.id);
  truthy('[+] the points\' share of the sale\'s net revenue moves out of sales', deferral > 0 && net(deferred, '4010') === deferral, { deferral, netRevenue, points, deferred });
  truthy('[+] and into deferred income', net(deferred, '2330') === -deferral, deferred);
  truthy('[+] every line names the sale\'s store', deferred.every((l) => l.storeId === store.id), deferred);
  const rivalView = view(rival.owner.token);
  truthy('[-] a rival tenant sees none of it', rivalView && !rivalView.settings && num(rivalView.deferredIncome) === 0, rivalView);

  // ── 4. spending releases, and the last lapse is breakage ───────────────────
  const half = Math.floor(points / 2);
  must(call('POST', `/api/customer-svc/customers/${customer.id}/loyalty/redeem`, { token: owner, body: { points: half, reason: 'k6 redemption' } }), 200, 'half the points spent');
  const released = Math.min(deferral, round((deferral * half) / (points * 0.8)));
  let release = [];
  poll(60, () => {
    release = of(ledger(), 'LOYALTY_RELEASE');
    return release.length >= 2;
  });
  truthy('[+] points spent release their share of the expected spend', net(release, '4020') === -released, { released, release });
  expect(call('POST', `/api/customer-svc/customers/${customer.id}/loyalty/redeem`, { token: owner, body: { points: points * 10, reason: 'more than held' } }), '[-] spending more points than are held is refused', [409, 422]);
  const rest = round(points - half);
  must(call('POST', `/api/customer-svc/customers/${customer.id}/loyalty/adjust`, { token: owner, idem: true, body: { points: -rest, reason: 'k6 lapse' } }), 200, 'the rest lapse');
  poll(60, () => {
    release = of(ledger(), 'LOYALTY_RELEASE');
    return release.some((l) => l.nominalCode === '4030');
  });
  truthy('[+] when no points remain, the income left is breakage', net(release, '4030') === -round(deferral - released), { deferral, released, release });
  const lapsed = view();
  truthy('[+] deferred income and outstanding points are back to nothing', num(lapsed.deferredIncome) === 0 && num(lapsed.pointsOutstanding) === 0, lapsed);
  truthy('[+] only the refused redemption was not posted', release.filter((l) => l.nominalCode === '2330').length === 2, release);

  // ── 5. gift cards ──────────────────────────────────────────────────────────
  expect(giftCard({ amount: 100 }), '[-] a gift card has to say how it was paid for', 400);
  expect(giftCard({ amount: 100, paidBy: 'GIFT_CARD' }), '[-] a gift card is not bought with another gift card', 400, 'GIFT_CARD_PAID_BY_INVALID');
  expect(giftCard({ amount: 100, paidBy: 'STORE_CREDIT' }), '[-] nor with store credit', 400, 'GIFT_CARD_PAID_BY_INVALID');
  const card = must(giftCard({ amount: 100, paidBy: 'CARD' }), 201, 'a gift card sold by card');
  must(giftCard({ amount: 20, paidBy: 'PROMOTIONAL' }), 201, 'a gift card given away');
  let loads = [];
  poll(60, () => {
    loads = of(ledger(), 'GIFT_CARD_LOAD');
    return loads.length >= 4;
  });
  truthy('[+] the card sold is money in card clearing against the liability', net(loads, '1250') === 100 && net(loads, '2310') === -120, loads);
  truthy('[+] the card given away is a cost, and neither is revenue', net(loads, '6420') === 20 && net(loads, '4010') === 0, loads);

  const giftSale = placeSale(3);
  const spend = num(giftSale.total);
  // The till charges the card through its redeem; payment-svc records the GIFT_CARD tender itself from
  // the redemption (a client-posted GIFT_CARD payment is refused: PAYMENT_GIFT_CARD_VIA_REDEEM).
  const tenderKey = newId();
  const redeem = () => call('POST', `/api/order-svc/gift-cards/${card.code}/redeem`, { token: owner, idem: tenderKey, body: { amount: spend, orderId: giftSale.id } });
  const tender = must(redeem(), [200, 201], 'the card spent at the till');
  const replay = redeem();
  truthy('[-] the redeem replayed on its key is the same redemption', [200, 201].includes(replay.status) && data(replay).redemptionId === tender.redemptionId, { status: replay.status, first: tender.redemptionId, again: data(replay).redemptionId });
  expect(pay(giftSale.id, spend.toFixed(2), 'GIFT_CARD', { reference: card.code }), '[-] a gift card tender cannot be posted as a payment', 400, 'PAYMENT_GIFT_CARD_VIA_REDEEM');
  const breakageDue = Math.min(round((spend * 0.1) / 0.9), 12, round(120 - spend));
  let breakage = [];
  poll(60, () => {
    breakage = of(ledger(), 'GIFT_CARD_BREAKAGE');
    return breakage.length >= 2;
  });
  truthy('[+] spending the card recognises its share of breakage', net(breakage, '4031') === -breakageDue, { spend, breakageDue, breakage });
  sleep(5);
  truthy('[-] the replayed tender recognises no second breakage', of(ledger(), 'GIFT_CARD_BREAKAGE').length === 2);
  const cards = view();
  truthy('[+] loaded, spent and the liability left add up', num(cards.giftCardsLoaded) === 120 && num(cards.giftCardsRedeemed) === spend && round(num(cards.giftCardLiability)) === round(120 - spend - breakageDue), cards);
  truthy('[+] the trial balance still balances', data(call('GET', `${LEDGER}/trial-balance`, { token: owner })).balanced === true);

  // ── 6. a change of estimate keeps the earlier one ──────────────────────────
  const revised = must(setEstimates({ ...ESTIMATES, pointValue: 0.04, reason: 'Revised after the year end' }), 200, 'revised estimates');
  truthy('[+] a change applies and the earlier estimates stay as history', revised.history && revised.history.length === 2 && num(revised.settings.pointValue) === 0.04, revised);
  completed.add(1);
}
