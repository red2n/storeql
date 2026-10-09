// The till's card rule, through the gateway (intent/card-payments.md, slice 1).
//
// A card the till cannot see the machine for must still be findable in the acquirer's file, so a card typed
// in at a till carries the machine's own receipt reference, always. Where the store has a card machine
// StoreQL drives, a typed card is refused outright -- the card goes through the machine -- unless the owner
// has allowed a standalone machine at that store, and then it carries the reference too. Only an owner
// allows it, per store, and never across businesses.
//
//   k6/run.sh card-rule-flow
import { Counter } from 'k6/metrics';
import { ALL_CHECKS_PASS, call, data, expect, must, sellingTenant, staffUser, truthy } from './lib/storeql.js';

const completed = new Counter('flow_completed');
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
  setupTimeout: '4m',
};

const PAY = '/api/payment-svc/payments';

export function setup() {
  const shop = sellingTenant('card-rule');
  shop.manager = staffUser(shop.tenant, 'MANAGER', [shop.store.id]);
  return shop;
}

export default function ({ tenant, rival, store, variantId, cashier, manager }) {
  const owner = tenant.owner.token;
  const sale = () => must(call('POST', '/api/order-svc/orders', {
    token: owner, idem: true,
    body: { storeId: store.id, channel: 'POS', fulfilmentType: 'INSTORE', items: [{ variantId, qty: 1 }] },
  }), 201, 'till sale');
  // raw: exactly what is written, no reference added for convenience
  const card = (order, reference, token = cashier.token) => call('POST', PAY, {
    raw: true, token, idem: true,
    body: { orderId: order.id, amount: order.total, method: 'CARD', storeId: store.id, ...(reference === undefined ? {} : { reference }) },
  });
  const SETTING = `/api/payment-svc/admin/payments/stores/${store.id}/standalone-card`;

  // ── a store with no machine StoreQL sees: the reference is required ─────────────────────────────
  const first = sale();
  expect(card(first), '[-] a typed card with no reference is refused', 400, 'PAYMENT_CARD_REFERENCE_REQUIRED');
  expect(card(first, '   '), '[-] spaces are not a reference', 400, 'PAYMENT_CARD_REFERENCE_REQUIRED');
  expect(card(first, 'x'.repeat(65)), '[-] nor is a reference too long to be one', 400, 'PAYMENT_CARD_REFERENCE_INVALID');
  const taken = card(first, '  AUTH 99120  ');
  expect(taken, '[+] with the machine’s reference it is taken', [200, 201]);
  truthy('[+] and kept as typed, trimmed', data(taken).reference === 'AUTH 99120', data(taken));
  const cash = sale();
  expect(call('POST', PAY, { raw: true, token: cashier.token, idem: true, body: { orderId: cash.id, amount: cash.total, method: 'CASH', storeId: store.id } }),
    '[+] cash needs no reference', [200, 201]);

  // ── a store with a registered machine: the card goes through it ─────────────────────────────────
  must(call('POST', '/api/payment-svc/admin/payments/terminals', { token: owner, body: { storeId: store.id, label: 'Till 1', vendor: 'SIMULATED' } }), 201, 'register a card machine');
  const second = sale();
  expect(card(second, 'AUTH 1'), '[-] a typed card is refused where the store has a card machine', 409, 'PAYMENT_CARD_NEEDS_TERMINAL');

  // ── who may allow a standalone machine ──────────────────────────────────────────────────────────
  expect(call('PUT', SETTING, { token: cashier.token, body: { allowed: true } }), '[-] a cashier cannot allow it', 403);
  expect(call('PUT', SETTING, { token: manager.token, body: { allowed: true } }), '[-] nor can a manager: a fraud control is not theirs to loosen', 403);
  expect(call('PUT', SETTING, { token: rival.owner.token, body: { allowed: true } }), '[-] another business’s owner cannot touch our store', 404, 'STORE_NOT_FOUND');
  expect(call('GET', SETTING, { token: rival.owner.token }), '[-] or read it', 404, 'STORE_NOT_FOUND');
  expect(call('PUT', SETTING, { token: owner, body: { allowed: true } }), '[+] the owner can', 200);
  const read = data(call('GET', SETTING, { token: manager.token }));
  truthy('[+] a manager can read it, with who changed it and when', read.allowed === true && read.changes.length === 1 && !!read.changes[0].changedBy && !!read.changes[0].changedAt, read);
  truthy('[+] and what the store has taken on standalone machines', read.standaloneTenders30d >= 1 && read.cardTenders30d >= read.standaloneTenders30d, read);

  // ── allowed: the reference is still required, and it can be switched off again ─────────────────
  expect(card(second), '[-] allowed, a typed card still needs the reference', 400, 'PAYMENT_CARD_REFERENCE_REQUIRED');
  expect(card(second, 'AUTH 2'), '[+] with it, it is taken', [200, 201]);
  expect(call('PUT', SETTING, { token: owner, body: { allowed: false } }), '[+] the owner switches it off again', 200);
  expect(card(sale(), 'AUTH 3'), '[-] and the card goes through the machine once more', 409, 'PAYMENT_CARD_NEEDS_TERMINAL');
  const history = data(call('GET', SETTING, { token: owner })).changes;
  truthy('[+] both changes are kept', history.length === 2 && history[0].allowed === false && history[1].allowed === true, history);

  completed.add(1);
}
