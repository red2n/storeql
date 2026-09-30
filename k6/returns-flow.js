// Return controls (intent/return-controls.md, slices 1 and 2) through the gateway: a return says what
// condition each item is in (sealed goes back on sale, opened waits for a check), stays inside the
// business's policy or is taken by a manager who is named on it, is paid back the way the customer
// chose (the original tender, store credit, a gift card — new or topped up), takes back the
// loyalty points its part of the sale earned, and — like a void — is done once however often the
// same Idempotency-Key is sent. A sale is found by its printed receipt number. Another business's
// manager finds, returns and voids nothing of ours.
//
// Slice 2 adds the direct exchange (like for like moves no money, a dearer new basket charges only
// the difference, a cheaper one refunds only the difference, each once), the no-receipt return
// (off by default, a manager only, store credit or a gift card, capped) and the till's gift-card
// tender, which is taken by redeeming the card under an Idempotency-Key and recorded by payment-svc
// from the redemption (a client-posted GIFT_CARD payment is refused).
//
// The policy is the business's own: the cashier's limit is set tiny here, so the refusal is by
// ceiling (no currency amount is assumed anywhere).
//
//   k6/run.sh returns-flow
import { sleep } from 'k6';
import {
  ALL_CHECKS_PASS,
  call,
  data,
  expect,
  must,
  newKey,
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

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS, setupTimeout: '5m' };

const O = '/api/v1/order-svc';
const P = '/api/v1/payment-svc';
const I = '/api/v1/inventory-svc';
const C = '/api/v1/customer-svc';

export function setup() {
  const tenant = onboardTenant('returns', { country: 'GB', currency: 'GBP' });
  const store = tenant.stores[0];
  const { variantId } = sellableVariant(tenant, 'Returns widget');
  priceVariants(tenant, [variantId], '20.00');
  must(receive(tenant, store.id, variantId, 100, '8.00'), [200, 201], 'stock on the shelf');
  // A dearer and a cheaper item to exchange for (slice 2).
  const dear = sellableVariant(tenant, 'Returns premium widget').variantId;
  priceVariants(tenant, [dear], '30.00');
  must(receive(tenant, store.id, dear, 50, '12.00'), [200, 201], 'the dearer item on the shelf');
  const cheap = sellableVariant(tenant, 'Returns basic widget').variantId;
  priceVariants(tenant, [cheap], '10.00');
  must(receive(tenant, store.id, cheap, 50, '4.00'), [200, 201], 'the cheaper item on the shelf');
  const cashier = staffUser(tenant, 'CASHIER', [store.id]);
  const manager = staffUser(tenant, 'MANAGER', [store.id]);
  const customer = must(
    call('POST', `${C}/customers`, { token: tenant.owner.token, body: { email: `ret-${uniq()}@k6.storeql.test`, firstName: 'Rae', lastName: 'Turner' } }),
    201,
    'a customer the shop knows'
  ).id;
  const rival = onboardTenant('returns-rival', { country: 'GB', currency: 'GBP' });
  const rivalManager = staffUser(rival, 'MANAGER', [rival.stores[0].id]);
  return { tenant, store, variantId, dear, cheap, cashier, manager, customer, rival, rivalManager, shopper: register('returns-shopper') };
}

export default function ({ tenant, store, variantId, dear, cheap, cashier, manager, customer, rival, rivalManager, shopper }) {
  const owner = tenant.owner.token;
  const num = (v) => Number(v || 0);
  const close = (a, b) => Math.abs(num(a) - num(b)) < 0.011;
  const bal = (r) => num(r.balance != null ? r.balance : r.currentBalance);

  const available = () => {
    const rows = data(call('GET', `${I}/admin/inventory/levels?store=${store.id}&limit=100`, { token: owner })) || [];
    const row = rows.find((r) => r.variantId === variantId);
    return row ? num(row.available) : NaN;
  };
  const batches = () => data(call('GET', `${I}/admin/inventory/batches?store=${store.id}&variant=${variantId}`, { token: owner })) || [];
  const returnsOf = (orderId, token = owner) => data(call('GET', `${O}/orders/${orderId}/returns`, { token })) || [];
  const points = () => num(data(call('GET', `${C}/customers/${customer}/loyalty`, { token: owner })).pointsBalance);
  const credit = () => num(data(call('GET', `${C}/customers/${customer}/store-credit?currency=GBP`, { token: owner })).balance);

  /** A till sale of `qty`, paid in cash and handed over; the stock is drawn once it is. */
  const tillSale = (qty, customerId, { pay = true } = {}) => {
    const s = must(
      call('POST', `${O}/orders`, {
        token: owner,
        idem: true,
        body: { storeId: store.id, channel: 'POS', fulfilmentType: 'INSTORE', paymentMethod: 'CASH', items: [{ variantId, qty }], ...(customerId ? { customerId } : {}) },
      }),
      201,
      'a till sale'
    );
    if (pay) {
      must(call('POST', `${P}/payments`, { token: owner, idem: true, body: { orderId: s.id, amount: s.total, method: 'CASH', storeId: store.id } }), [200, 201], 'paid in cash');
      truthy('[+] the sale is handed over', poll(60, () => (data(call('GET', `${O}/orders/${s.id}`, { token: owner })) || {}).status === 'FULFILLED') >= 0);
    }
    return s;
  };
  const line = (qty, condition) => ({ variantId, qty, ...(condition ? { condition } : {}) });
  const giveBack = (orderId, { token = manager.token, items, method = 'ORIGINAL', key = true, extra = {} } = {}) =>
    call('POST', `${O}/orders/${orderId}/returns`, {
      token,
      ...(key ? { idem: key } : {}),
      body: { reason: 'k6 return', refundMethod: method, items, ...extra },
    });
  const policy = (body, token = manager.token) => call('PUT', `${O}/admin/return-policy`, { token, body });
  const reasonsOf = (res) => {
    try {
      const b = JSON.parse(res.body);
      return JSON.stringify(b.details || (b.error || {}).details || []);
    } catch (_) {
      return '[]';
    }
  };

  // ── 0. the sales ────────────────────────────────────────────────────────────────────────────────
  const sale = tillSale(6, customer);
  const noCustomer = tillSale(2);
  truthy('[+] the sale is drawn from the shelf', poll(60, () => available() === 92) >= 0, available());
  const earned = poll(60, () => points() > 0);
  truthy('[+] the customer earned points on the sale', earned >= 0, points());
  const pointsBefore = points();
  const online = must(
    call('POST', `${O}/orders`, {
      token: shopper.token,
      storefront: tenant.tenantId,
      idem: true,
      body: { storeId: store.id, channel: 'ONLINE', fulfilmentType: 'PICKUP', items: [{ variantId, qty: 1 }] },
    }),
    201,
    'an online sale'
  );

  // ── 1. the policy ───────────────────────────────────────────────────────────────────────────────
  const dflt = data(call('GET', `${O}/admin/return-policy`, { token: manager.token }));
  truthy('[+] a business that set nothing has the default: a 30-day window, no cashier limit, no no-receipt returns', dflt.windowDays === 30 && (dflt.cashierCeiling == null) && dflt.noReceiptAllowed === false, dflt);
  expect(policy({ windowDays: 30, cashierCeiling: 1, noReceiptAllowed: false }, cashier.token), '[-] a cashier cannot set the policy', 403);
  expect(call('GET', `${O}/admin/return-policy`, { token: cashier.token }), '[-] ...nor read it', 403);
  expect(policy({ windowDays: 30, cashierCeiling: 1, noReceiptAllowed: false }), '[+] a manager sets a tiny cashier limit', 200);
  truthy('[+] ...and it is kept', num(data(call('GET', `${O}/admin/return-policy`, { token: owner })).cashierCeiling) === 1);

  // ── 2. what a return must say ───────────────────────────────────────────────────────────────────
  expect(giveBack(sale.id, { items: [line(1)] }), '[-] a return with no condition is refused', 400, 'ORDER_RETURN_CONDITION_REQUIRED');
  expect(giveBack(sale.id, { items: [line(1, 'GOOD')] }), '[-] a condition that is none of the four is refused', 400, 'ORDER_RETURN_CONDITION_INVALID');
  expect(giveBack(sale.id, { items: [line(1, 'SEALED')], key: false }), '[-] a return with no Idempotency-Key is refused', 400, 'IDEMPOTENCY_KEY_REQUIRED');
  truthy('[+] nothing of that came back', returnsOf(sale.id).length === 0);

  // ── 3. the cashier's limit, and the manager who takes what is over it ───────────────────────────
  const refused = giveBack(sale.id, { token: cashier.token, items: [line(1, 'SEALED')] });
  expect(refused, "[-] a cashier's return over the limit needs a manager", 403, 'ORDER_RETURN_NEEDS_MANAGER');
  truthy('[-] ...and says it is the ceiling', reasonsOf(refused).includes('CEILING'), reasonsOf(refused));
  truthy('[-] ...nothing was refunded or recorded, and the shelf did not move', returnsOf(sale.id).length === 0 && available() === 92, { returns: returnsOf(sale.id).length, available: available() });

  // Another business, while the limit stands: it finds, returns and voids nothing of ours.
  expect(call('GET', `${O}/orders/by-receipt?number=${encodeURIComponent('NO/0000/000000')}`, { token: manager.token }), '[-] a number nobody was given is not found', 404, 'ORDER_RECEIPT_NOT_FOUND');
  expect(giveBack(sale.id, { token: rivalManager.token, items: [line(1, 'SEALED')] }), "[-] the rival's manager cannot return our sale", 404);
  expect(giveBack(online.id, { token: rivalManager.token, items: [line(1, 'SEALED')] }), '[-] ...nor our online order', 404);
  expect(call('POST', `${O}/orders/${sale.id}/void`, { token: rivalManager.token, idem: true, body: { reason: 'not ours' } }), '[-] ...nor void our sale', 404);
  truthy("[-] their policy is their own: our limit is not theirs", (data(call('GET', `${O}/admin/return-policy`, { token: rivalManager.token })).cashierCeiling == null));
  truthy('[-] ...and nothing of ours moved', returnsOf(sale.id).length === 0 && returnsOf(online.id).length === 0);

  // The manager takes the same return, and is named on it.
  const key = newKey('return');
  const first = giveBack(sale.id, { items: [line(1, 'SEALED')], key });
  expect(first, "[+] the manager's same return is taken", 201);
  const r1 = data(first);
  truthy('[+] ...naming the manager who approved it, and why', !!r1.approvedBy && r1.approvedBy !== cashier.userId && JSON.stringify(r1.outsidePolicy || []).includes('CEILING'), r1);

  // ── 4. a retry is the same return ───────────────────────────────────────────────────────────────
  const replay = giveBack(sale.id, { items: [line(1, 'SEALED')], key });
  expect(replay, '[+] the same return sent again with the same key is answered', [200, 201]);
  truthy('[+] ...with the first return', data(replay).id === r1.id, { first: r1.id, replay: data(replay).id });
  truthy('[+] ...and the order has one return, not two', returnsOf(sale.id).length === 1, returnsOf(sale.id).length);

  // ── 5. the shelf follows the condition ──────────────────────────────────────────────────────────
  truthy('[+] a sealed item is back on sale: available rises by one, once', poll(60, () => available() === 93) >= 0, available());
  expect(policy({ windowDays: 30, cashierCeiling: null, noReceiptAllowed: false }), '[+] the manager takes the limit off', 200);
  const opened = giveBack(sale.id, { token: cashier.token, items: [line(1, 'OPENED')] });
  expect(opened, '[+] inside the policy the cashier takes an opened item back alone', 201);
  truthy('[+] ...with nobody to approve it', !data(opened).approvedBy, data(opened));
  truthy('[+] an opened item waits for a check: a batch in INSPECTION appears', poll(60, () => batches().some((b) => b.materialStatus === 'INSPECTION')) >= 0, batches().map((b) => b.materialStatus));
  truthy('[+] ...and available is still where the sealed one left it', available() === 93, available());

  // ── 6. store credit ─────────────────────────────────────────────────────────────────────────────
  expect(giveBack(noCustomer.id, { items: [line(1, 'SEALED')], method: 'STORE_CREDIT' }), '[-] store credit on a sale that names no customer is refused', 409, 'ORDER_RETURN_STORE_CREDIT_NEEDS_CUSTOMER');
  truthy('[-] ...and nothing was recorded', returnsOf(noCustomer.id).length === 0);
  const creditBefore = credit();
  const toCredit = giveBack(sale.id, { items: [line(1, 'SEALED')], method: 'STORE_CREDIT' });
  expect(toCredit, "[+] store credit on the customer's sale is taken", 201);
  const creditAmount = num(data(toCredit).refundAmount);
  truthy("[+] the customer's store credit rises by the refund", poll(90, () => close(credit(), creditBefore + creditAmount)) >= 0, { before: creditBefore, refund: creditAmount, now: credit() });

  // ── 7. a gift card, new and topped up, spent ────────────────────────────────────────────────────
  const toCard = giveBack(sale.id, { items: [line(1, 'SEALED')], method: 'GIFT_CARD' });
  expect(toCard, '[+] a refund to a new gift card is taken', 201);
  const card = data(toCard).giftCard || {};
  truthy('[+] ...with a card whose balance is the refund', !!card.code && close(card.balance, data(toCard).refundAmount), data(toCard));
  const read = data(call('GET', `${O}/gift-cards/${encodeURIComponent(card.code)}`, { token: owner }));
  truthy('[+] ...which the till can look up', close(read.currentBalance, card.balance), read);
  const topped = giveBack(sale.id, { items: [line(1, 'SEALED')], method: 'GIFT_CARD', extra: { giftCardCode: card.code } });
  expect(topped, '[+] a later refund tops the same card up', 201);
  const both = num(card.balance) + num(data(topped).refundAmount);
  truthy('[+] ...its balance the two refunds together', close((data(topped).giftCard || {}).balance, both), data(topped));
  // Spent at the till: the card is charged for an unpaid sale, and payment-svc records the tender.
  const cardSale = tillSale(1, null, { pay: false });
  const spent = call('POST', `${O}/gift-cards/${encodeURIComponent(card.code)}/redeem`, { token: owner, idem: true, body: { amount: cardSale.total, orderId: cardSale.id } });
  expect(spent, '[+] the card can be redeemed for a sale', [200, 201]);
  truthy('[+] ...leaving the two refunds less the sale', close(bal(data(spent)), both - num(cardSale.total)), data(spent));
  expect(giveBack(sale.id, { items: [line(1, 'SEALED')], method: 'GIFT_CARD', extra: { giftCardCode: 'NO-SUCH-CARD' } }), '[-] topping up a card that does not exist is refused', 404, 'GIFT_CARD_NOT_FOUND');

  // ── 8. the points follow the money ──────────────────────────────────────────────────────────────
  truthy('[+] the customer\'s points fall after the returns', poll(90, () => points() < pointsBefore) >= 0, { before: pointsBefore, now: points() });

  // ── 9. the receipt number finds the sale ────────────────────────────────────────────────────────
  const receipt = data(call('GET', `${O}/orders/${sale.id}/fiscal-receipt?wait=20`, { token: owner }));
  truthy('[+] the sale has its numbered receipt', !!receipt.fullNumber, receipt);
  const found = call('GET', `${O}/orders/by-receipt?number=${encodeURIComponent(receipt.fullNumber)}`, { token: cashier.token });
  expect(found, '[+] the printed number finds the sale for the cashier', 200);
  truthy('[+] ...the right one', (data(found).id || (data(found).order || {}).id) === sale.id, data(found));
  expect(call('GET', `${O}/orders/by-receipt?number=${encodeURIComponent(receipt.fullNumber)}`, { token: rivalManager.token }), "[-] another business's manager finds nothing by it", 404, 'ORDER_RECEIPT_NOT_FOUND');

  // ── 10. a void is done once ─────────────────────────────────────────────────────────────────────
  const toVoid = tillSale(1, null, { pay: false });
  expect(call('POST', `${O}/orders/${toVoid.id}/void`, { token: manager.token, body: { reason: 'k6 no key' } }), '[-] a void with no Idempotency-Key is refused', 400, 'IDEMPOTENCY_KEY_REQUIRED');
  const voidKey = newKey('void');
  const voided = call('POST', `${O}/orders/${toVoid.id}/void`, { token: manager.token, idem: voidKey, body: { reason: 'k6 rang up twice' } });
  expect(voided, '[+] a manager voids the sale', [200, 201]);
  const again = call('POST', `${O}/orders/${toVoid.id}/void`, { token: manager.token, idem: voidKey, body: { reason: 'k6 rang up twice' } });
  expect(again, '[+] the same void sent again with the same key is answered, not refused', [200, 201]);
  truthy('[+] ...as the first, and the sale is void', (data(call('GET', `${O}/orders/${toVoid.id}`, { token: owner })) || {}).status === 'VOIDED');
  expect(call('POST', `${O}/orders/${toVoid.id}/void`, { token: manager.token, idem: true, body: { reason: 'k6 a second, different void' } }), '[-] a different void of the same sale is refused', 409);

  // ═══ Slice 2 ═══════════════════════════════════════════════════════════════════════════════════
  const tendersOf = (orderId) => data(call('GET', `${P}/payments/by-order/${orderId}`, { token: owner })) || [];
  const refundsOfOrder = (orderId) => data(call('GET', `${P}/payments/by-order/${orderId}/refunds`, { token: owner })) || [];
  const sum = (rows, method, field = 'amount') => rows.filter((r) => (r.method || r.refundMethod) === method).reduce((t, r) => t + num(r[field]), 0);
  const count = (rows, method) => rows.filter((r) => (r.method || r.refundMethod) === method).length;
  // What went back to the customer's own tenders: a refund row carries the tender's method (CASH, CARD…), so it is every row that is not the exchange's.
  const paidBack = (rows) => rows.filter((r) => (r.method || r.refundMethod) !== 'EXCHANGE');
  const exchange = (orderId, { from = variantId, to = variantId, token = manager.token, key = true, condition = 'SEALED' } = {}) =>
    call('POST', `${O}/orders/${orderId}/exchange`, {
      token,
      ...(key ? { idem: key } : {}),
      body: {
        reason: 'k6 exchange',
        returnItems: [{ variantId: from, qty: 1, ...(condition ? { condition } : {}) }],
        newItems: [{ variantId: to, qty: 1 }],
        customerId: customer,
      },
    });

  // ── 11. a direct exchange: the returned value pays for the new basket ───────────────────────────
  const likeSale = tillSale(1, customer);
  const likeKey = newKey('exchange');
  const like = exchange(likeSale.id, { key: likeKey });
  expect(like, '[+] a like-for-like exchange is taken', 201);
  const likeData = data(like);
  const likeOrder = likeData.order || {};
  truthy('[+] ...it moves no money: nothing due and nothing to give back', close(likeData.dueFromCustomer, 0) && close(likeData.refundToCustomer, 0), likeData);
  truthy('[+] ...the returned value is what the sale was worth', close(likeData.exchangeAmount, likeSale.total), likeData);
  truthy('[+] ...the new order holds an EXCHANGE tender for it, once', poll(90, () => close(sum(tendersOf(likeOrder.id), 'EXCHANGE'), likeData.exchangeAmount)) >= 0, tendersOf(likeOrder.id));
  truthy('[+] ...and the old order was refunded to EXCHANGE, with nothing sent back to the customer', poll(90, () => count(refundsOfOrder(likeSale.id), 'EXCHANGE') === 1) >= 0 && paidBack(refundsOfOrder(likeSale.id)).length === 0, refundsOfOrder(likeSale.id));
  const likeAgain = exchange(likeSale.id, { key: likeKey });
  expect(likeAgain, '[+] the same exchange sent again with the same key is answered', [200, 201]);
  truthy('[+] ...with the first, and the money moved once', ((data(likeAgain).return || {}).id === (likeData.return || {}).id) && count(tendersOf(likeOrder.id), 'EXCHANGE') === 1 && count(refundsOfOrder(likeSale.id), 'EXCHANGE') === 1, { first: likeData.return, again: data(likeAgain).return });
  expect(exchange(likeSale.id, { key: false }), '[-] an exchange with no Idempotency-Key is refused', 400, 'IDEMPOTENCY_KEY_REQUIRED');
  expect(exchange(likeSale.id, { condition: null }), '[-] an exchange names the condition of what comes back', 400, 'ORDER_RETURN_CONDITION_REQUIRED');
  expect(exchange(noCustomer.id, { token: rivalManager.token }), "[-] the rival's manager cannot exchange our sale", 404);

  const dearSale = tillSale(1, customer);
  const dearer = exchange(dearSale.id, { to: dear });
  expect(dearer, '[+] an exchange for a dearer item is taken', 201);
  const dearData = data(dearer);
  const dearOrder = dearData.order || {};
  truthy('[+] ...it charges only the difference', num(dearData.dueFromCustomer) > 0 && close(dearData.refundToCustomer, 0) && close(num(dearData.dueFromCustomer) + num(dearData.exchangeAmount), dearOrder.total), dearData);
  truthy('[+] ...the returned value is on the new order as an EXCHANGE tender', poll(90, () => close(sum(tendersOf(dearOrder.id), 'EXCHANGE'), dearData.exchangeAmount)) >= 0, tendersOf(dearOrder.id));
  must(call('POST', `${P}/payments`, { token: owner, idem: true, body: { orderId: dearOrder.id, amount: dearData.dueFromCustomer, method: 'CASH', storeId: store.id } }), [200, 201], 'the difference paid in cash at the till');
  truthy('[+] ...the difference paid, the new order is settled and handed over', poll(90, () => (data(call('GET', `${O}/orders/${dearOrder.id}`, { token: owner })) || {}).status === 'FULFILLED') >= 0, data(call('GET', `${O}/orders/${dearOrder.id}`, { token: owner })).status);
  truthy('[+] ...the customer paid the difference once, not the whole price', close(sum(tendersOf(dearOrder.id), 'CASH'), dearData.dueFromCustomer) && count(tendersOf(dearOrder.id), 'EXCHANGE') === 1, tendersOf(dearOrder.id));

  const cheapSale = tillSale(1, customer);
  const cheaper = exchange(cheapSale.id, { to: cheap });
  expect(cheaper, '[+] an exchange for a cheaper item is taken', 201);
  const cheapData = data(cheaper);
  const cheapOrder = cheapData.order || {};
  truthy('[+] ...it owes nothing, and gives back only the difference', close(cheapData.dueFromCustomer, 0) && num(cheapData.refundToCustomer) > 0 && close(num(cheapData.refundToCustomer) + num(cheapOrder.total), cheapSale.total), cheapData);
  truthy('[+] ...the difference goes back to how the customer paid, once', poll(90, () => close(paidBack(refundsOfOrder(cheapSale.id)).reduce((t, r) => t + num(r.amount), 0), cheapData.refundToCustomer)) >= 0, refundsOfOrder(cheapSale.id));
  sleep(3);
  truthy('[+] ...and no second refund follows', paidBack(refundsOfOrder(cheapSale.id)).length === 1 && count(refundsOfOrder(cheapSale.id), 'EXCHANGE') === 1, refundsOfOrder(cheapSale.id));

  // An exchange is held to the same policy as a return.
  expect(policy({ windowDays: 30, cashierCeiling: 1, noReceiptAllowed: false }), '[+] a manager sets the cashier limit again', 200);
  const exSale = tillSale(1, customer);
  const held = exchange(exSale.id, { token: cashier.token });
  expect(held, "[-] a cashier's exchange over the limit needs a manager", 403, 'ORDER_RETURN_NEEDS_MANAGER');
  truthy('[-] ...naming the ceiling, and nothing moved', reasonsOf(held).includes('CEILING') && refundsOfOrder(exSale.id).length === 0, reasonsOf(held));
  expect(exchange(exSale.id), '[+] the manager takes it', 201);

  // ── 12. a return with no receipt ────────────────────────────────────────────────────────────────
  const noReceipt = (body, { token = manager.token, key = true } = {}) =>
    call('POST', `${O}/returns/no-receipt`, {
      token,
      ...(key ? { idem: key } : {}),
      body: { storeId: store.id, reason: 'k6 no receipt', customerContact: '+447700900123', items: [line(1, 'SEALED')], ...body },
    });
  expect(policy({ windowDays: 30, cashierCeiling: null, noReceiptAllowed: false }), '[+] a manager takes the cashier limit off', 200);
  expect(noReceipt({ refundMethod: 'GIFT_CARD' }), '[-] a business that has not switched it on takes no return without a receipt', 409, 'ORDER_NO_RECEIPT_RETURNS_OFF');
  expect(policy({ windowDays: 30, cashierCeiling: null, noReceiptAllowed: true, noReceiptCeiling: 100000 }), '[+] a manager switches no-receipt returns on', 200);
  const noReceiptOn = noReceipt({ refundMethod: 'GIFT_CARD' }, { token: cashier.token });
  expect(noReceiptOn, '[-] a cashier cannot take a return without a receipt', 403, 'ORDER_RETURN_NEEDS_MANAGER');
  truthy('[-] ...and the reason is the missing receipt', reasonsOf(noReceiptOn).includes('NO_RECEIPT'), reasonsOf(noReceiptOn));
  expect(noReceipt({ refundMethod: 'ORIGINAL' }), '[-] the refund is never back to a tender: there is none', 400, 'ORDER_NO_RECEIPT_METHOD_INVALID');
  expect(noReceipt({ refundMethod: 'STORE_CREDIT' }), '[-] store credit needs a customer', 409, 'ORDER_RETURN_STORE_CREDIT_NEEDS_CUSTOMER');
  expect(noReceipt({ refundMethod: 'GIFT_CARD', items: [{ variantId, qty: 1 }] }), '[-] a return names the condition', 400, 'ORDER_RETURN_CONDITION_REQUIRED');
  expect(noReceipt({ refundMethod: 'GIFT_CARD' }, { key: false }), '[-] a return with no Idempotency-Key is refused', 400, 'IDEMPOTENCY_KEY_REQUIRED');

  const creditBefore2 = credit();
  const nrKey = newKey('no-receipt');
  const nrCredit = noReceipt({ refundMethod: 'STORE_CREDIT', customerId: customer }, { key: nrKey });
  expect(nrCredit, '[+] a manager takes an item back to store credit with no receipt', 201);
  const nrAmount = num(data(nrCredit).refundAmount);
  truthy('[+] ...at the current price', nrAmount > 0 && close(nrAmount, likeSale.total), { refund: nrAmount, oneUnit: likeSale.total });
  truthy("[+] ...and the customer's store credit rises by it", poll(90, () => close(credit(), creditBefore2 + nrAmount)) >= 0, { before: creditBefore2, refund: nrAmount, now: credit() });
  const nrAgain = noReceipt({ refundMethod: 'STORE_CREDIT', customerId: customer }, { key: nrKey });
  truthy('[+] the same return sent again with the same key is the first', [200, 201].includes(nrAgain.status) && data(nrAgain).id === data(nrCredit).id, { first: data(nrCredit).id, again: data(nrAgain).id });
  sleep(3);
  truthy('[+] ...and the credit rose once', close(credit(), creditBefore2 + nrAmount), { before: creditBefore2, refund: nrAmount, now: credit() });

  const nrCard = noReceipt({ refundMethod: 'GIFT_CARD' });
  expect(nrCard, '[+] a manager takes an item back to a new gift card with no receipt', 201);
  truthy('[+] ...a card worth the refund', !!(data(nrCard).giftCard || {}).code && close((data(nrCard).giftCard || {}).balance, data(nrCard).refundAmount), data(nrCard));

  const rivalCredit = () => num(data(call('GET', `${C}/customers/${customer}/store-credit?currency=GBP`, { token: rival.owner.token })).balance);
  const rivalTry = noReceipt({ refundMethod: 'STORE_CREDIT', customerId: customer }, { token: rivalManager.token });
  expect(rivalTry, "[-] the rival's manager cannot take a return into our store or customer", [403, 404, 409]);
  truthy('[-] ...and no credit moved', rivalCredit() === 0 && close(credit(), creditBefore2 + nrAmount));

  expect(policy({ windowDays: 30, cashierCeiling: null, noReceiptAllowed: true, noReceiptCeiling: 1 }), '[+] a manager sets a tiny no-receipt ceiling', 200);
  expect(noReceipt({ refundMethod: 'GIFT_CARD' }), '[-] a return over the ceiling is refused', 422, 'ORDER_NO_RECEIPT_OVER_CEILING');

  // ── 13. the till's gift-card tender: the card is charged first, by its redeem ───────────────────
  const gc = must(call('POST', `${O}/gift-cards`, { token: owner, body: { storeId: store.id, amount: 200, currency: 'GBP', paidBy: 'CARD' } }), 201, 'a gift card to spend');
  const gcSale = tillSale(1, null, { pay: false });
  const redeemKey = newKey('gift-redeem');
  const redeemBody = { amount: gcSale.total, orderId: gcSale.id };
  const redeemUrl = `${O}/gift-cards/${encodeURIComponent(gc.code)}/redeem`;
  expect(call('POST', redeemUrl, { token: owner, body: redeemBody }), '[-] a redeem with no Idempotency-Key is refused', 400, 'IDEMPOTENCY_KEY_REQUIRED');
  const charged = call('POST', redeemUrl, { token: owner, idem: redeemKey, body: redeemBody });
  expect(charged, '[+] the till charges the gift card for the sale', [200, 201]);
  truthy('[+] ...the answer names the redemption, the card, the amount and what is left', !!data(charged).redemptionId && !!data(charged).giftCardId && close(data(charged).amount, gcSale.total) && close(data(charged).balance, 200 - num(gcSale.total)), data(charged));
  truthy('[+] ...and payment-svc records the GIFT_CARD tender itself, once', poll(90, () => close(sum(tendersOf(gcSale.id), 'GIFT_CARD'), gcSale.total)) >= 0 && count(tendersOf(gcSale.id), 'GIFT_CARD') === 1, tendersOf(gcSale.id));
  truthy('[+] ...and the paid sale is handed over', poll(90, () => (data(call('GET', `${O}/orders/${gcSale.id}`, { token: owner })) || {}).status === 'FULFILLED') >= 0);
  const chargedAgain = call('POST', redeemUrl, { token: owner, idem: redeemKey, body: redeemBody });
  truthy('[+] the same charge sent again with the same key is the first', [200, 201].includes(chargedAgain.status) && data(chargedAgain).redemptionId === data(charged).redemptionId, { first: data(charged).redemptionId, again: data(chargedAgain).redemptionId });
  sleep(3);
  truthy('[+] ...the card was charged once and the sale has one tender', close(data(call('GET', `${O}/gift-cards/${encodeURIComponent(gc.code)}`, { token: owner })).currentBalance, 200 - num(gcSale.total)) && count(tendersOf(gcSale.id), 'GIFT_CARD') === 1);
  expect(call('POST', `${P}/payments`, { token: owner, idem: true, body: { orderId: gcSale.id, amount: gcSale.total, method: 'GIFT_CARD', storeId: store.id, reference: gc.code } }), '[-] a GIFT_CARD tender cannot be posted as a payment', 400, 'PAYMENT_GIFT_CARD_VIA_REDEEM');

  const refusedSale = tillSale(1, null, { pay: false });
  expect(call('POST', redeemUrl, { token: owner, idem: true, body: { amount: 100000, orderId: refusedSale.id } }), '[-] a card without enough on it is refused', 409, 'GIFT_CARD_INSUFFICIENT_BALANCE');
  expect(call('POST', `${O}/gift-cards/NO-SUCH-CARD/redeem`, { token: owner, idem: true, body: { amount: 1, orderId: refusedSale.id } }), '[-] an unknown card is refused', 404, 'GIFT_CARD_NOT_FOUND');
  expect(call('POST', redeemUrl, { token: rivalManager.token, idem: true, body: { amount: 1, orderId: refusedSale.id } }), "[-] the rival's staff cannot spend our card", 404);
  truthy('[-] ...and the refused sale took no tender and is still unpaid', tendersOf(refusedSale.id).length === 0 && (data(call('GET', `${O}/orders/${refusedSale.id}`, { token: owner })) || {}).status !== 'FULFILLED');
}
