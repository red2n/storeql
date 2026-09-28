// Substitutions for out-of-stock online lines through the gateway: a business declares which
// products stand in for which; a shopper's delivery (substitutions allowed, the default) is paid
// and waits to be picked; the picker finds the apples short and, from the suggestions, puts pears
// in the bag (dearer: charged the apples' price, nothing refunded) and plums (cheaper: the
// difference refunded by payment-svc, the shopper told); the last apple is closed short (refunded,
// the shopper told), the order is FULFILLED and dispatched. A second shopper who refused
// substitutions gets only a close. A rival business reads and changes nothing.
//
//   k6/run.sh substitution-flow
import {
  ALL_CHECKS_PASS,
  call,
  data,
  expect,
  must,
  onboardTenant,
  poll,
  priceVariants,
  register,
  sellableVariant,
  staffUser,
  truthy,
  uniq,
} from './lib/storeql.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS, setupTimeout: '4m' };

const I = '/api/inventory-svc';
const O = '/api/order-svc';
const P = '/api/payment-svc';
const PR = '/api/product-svc';
const N = '/api/notification-svc';
const TS = '/api/tenant-svc';

export function setup() {
  const tenant = onboardTenant('subs', { stores: 2 });
  const shop = tenant.stores[0];
  const other = tenant.stores[1];
  const t = tenant.owner.token;
  const pincode = `LS${uniq()}`.slice(0, 12);
  must(call('POST', `${TS}/admin/stores/${shop.id}/delivery-areas`, { token: t, body: { pincode, priority: 1 } }), 201, 'the shop delivers to the postcode');
  const apples = sellableVariant(tenant, 'Apples').variantId;
  const pears = sellableVariant(tenant, 'Pears').variantId;
  const plums = sellableVariant(tenant, 'Plums').variantId;
  const grapes = sellableVariant(tenant, 'Grapes').variantId;
  priceVariants(tenant, [apples], '2.00');
  priceVariants(tenant, [pears], '3.00');
  priceVariants(tenant, [plums], '1.50');
  priceVariants(tenant, [grapes], '4.00');
  for (const sub of [pears, plums, grapes]) {
    must(call('POST', `${PR}/admin/products/variants/${apples}/relationships`, { token: t, body: { relatedVariantId: sub, relationshipType: 'SUBSTITUTE' } }), 201, 'a declared substitute');
  }
  const keeper = staffUser(tenant, 'STOREKEEPER', [shop.id]);
  const elsewhere = staffUser(tenant, 'CASHIER', [other.id]);
  const rival = onboardTenant('subs-rival');
  return { tenant, shop, pincode, apples, pears, plums, grapes, keeper, elsewhere, rival, shopper: register('subs-shopper'), refuser: register('subs-refuser') };
}

export default function ({ tenant, shop, pincode, apples, pears, plums, grapes, keeper, elsewhere, rival, shopper, refuser }) {
  const owner = tenant.owner.token;
  const num = (v) => Number(v || 0);
  const receive = (variantId, qty) => must(call('POST', `${I}/admin/inventory/receive`, { token: owner, idem: true, body: { storeId: shop.id, variantId, qty, costPrice: '1.00' } }), 201, 'received');
  const onHand = (variantId) => num(((data(call('GET', `${I}/admin/inventory/levels?store=${shop.id}`, { token: owner })) || []).find((l) => l.variantId === variantId) || {}).onHand);
  const order = (id) => data(call('GET', `${O}/orders/${id}`, { token: owner })) || {};
  const line = (o, variantId) => ((o.items || []).find((i) => i.variantId === variantId) || {});
  const owing = (token = keeper.token) => data(call('GET', `${O}/orders/owing?store=${shop.id}`, { token })) || [];
  const awaiting = () => data(call('GET', `${I}/admin/inventory/waves/awaiting?storeId=${shop.id}`, { token: owner })) || [];
  const refunds = (id) => data(call('GET', `${P}/payments/by-order/${id}/refunds`, { token: owner })) || [];
  const told = (who, type, token = owner) => (data(call('GET', `${N}/admin/notifications?recipient=${encodeURIComponent(who.email)}&limit=50`, { token })) || []).filter((n) => n.type === type);
  const place = (who, fulfilment, qty, extra) => call('POST', `${O}/orders`, { token: who.token, storefront: tenant.tenantId, idem: true, body: Object.assign(
    { storeId: shop.id, channel: 'ONLINE', fulfilmentType: fulfilment, items: [{ variantId: apples, qty }], contactPhone: '07700900123' },
    fulfilment === 'DELIVERY' ? { deliveryLine1: '12 High Street', deliveryCity: 'Leeds', deliveryPostalCode: pincode, deliveryRecipientName: 'Chris Carter', deliveryRecipientPhone: '07700900123' } : {},
    extra || {},
  ) });
  const pay = (o) => must(call('POST', `${P}/payments`, { token: owner, idem: true, body: { orderId: o.id, amount: o.total, method: 'CARD', storeId: o.storeId } }), [200, 201], 'paid by card');
  const substitute = (id, body, token = keeper.token) => call('POST', `${O}/orders/${id}/lines/${apples}/substitute`, { token, idem: true, body });
  const short = (id, body, token = keeper.token) => call('POST', `${O}/orders/${id}/lines/${apples}/short`, { token, idem: true, body });

  receive(apples, 10);
  receive(pears, 5);
  receive(plums, 5);

  // ── 1. a delivery, substitutions allowed by default, paid and waiting ────────
  const delivery = must(place(shopper, 'DELIVERY', 3), 201, 'a delivery of three apples');
  truthy('[+] substitutions are allowed unless the shopper says otherwise', delivery.allowSubstitutions === true, delivery);
  pay(delivery);
  truthy('[+] paid: confirmed', poll(60, () => order(delivery.id).status === 'CONFIRMED') >= 0, order(delivery.id).status);
  // The picker works from the store's waiting list, which inventory-svc projects from the
  // confirmation a moment later; a line is closed or substituted once the order is on it.
  truthy('[+] it waits to be picked at the shop', poll(90, () => awaiting().some((a) => a.orderId === delivery.id)) >= 0, awaiting());
  const owed = owing().find((o) => o.orderId === delivery.id) || {};
  truthy('[+] the store sees what it owes: three apples, substitutions allowed', owed.allowSubstitutions === true && (owed.lines || []).some((l) => l.variantId === apples && num(l.outstandingQty) === 3), owed);
  const suggested = data(call('GET', `${O}/orders/${delivery.id}/lines/${apples}/substitutes`, { token: keeper.token })) || [];
  const availableOf = (v) => num((suggested.find((s) => s.variantId === v) || {}).available);
  truthy('[+] the declared stand-ins are suggested with what the shelf has', availableOf(pears) === 5 && availableOf(plums) === 5 && suggested.some((s) => s.variantId === grapes) && availableOf(grapes) === 0, suggested);

  // ── 2. refusals before anything moves ────────────────────────────────────────
  expect(substitute(delivery.id, { substituteVariantId: grapes, qty: 1 }), '[-] a stand-in the shelf has none of', 409, 'ORDER_SUBSTITUTE_NOT_SELLABLE');
  expect(substitute(delivery.id, { substituteVariantId: apples, qty: 1 }), '[-] a stand-in must be another product', 400, 'ORDER_SUBSTITUTE_SAME_VARIANT');
  expect(short(delivery.id, { qty: 5 }), '[-] no more than the line still owes', 409, 'ORDER_LINE_QTY_EXCEEDS_OUTSTANDING');
  expect(substitute(delivery.id, { substituteVariantId: pears, qty: 1 }, elsewhere.token), '[-] staff at another store cannot', 403, 'STORE_ACCESS_DENIED');
  expect(short(delivery.id, {}, elsewhere.token), '[-] ...nor close a line short', 403, 'STORE_ACCESS_DENIED');

  // ── 3. a dearer substitute: charged the apples' price, nothing back ──────────
  const before = order(delivery.id);
  const withPears = must(substitute(delivery.id, { substituteVariantId: pears, qty: 1 }), 200, 'pears for one apple');
  truthy('[+] the shopper pays no more than they did', num(withPears.total) === num(before.total), { before: before.total, after: withPears.total });
  truthy('[+] the pears line is charged the apples\' unit price, picked at once, standing in for the apples', num(line(withPears, pears).unitPrice) <= num(line(before, apples).unitPrice) && num(line(withPears, pears).outstandingQty) === 0 && line(withPears, pears).substitutesItemId === line(before, apples).id, withPears.items);
  truthy('[+] the apples owe two now, and the order is part-picked', num(line(withPears, apples).outstandingQty) === 2 && withPears.status === 'PARTIALLY_FULFILLED', withPears);
  truthy('[+] the pears left the shelf', poll(60, () => onHand(pears) === 4) >= 0, onHand(pears));
  truthy('[+] the waiting line asks for two apples', poll(60, () => (((awaiting().find((a) => a.orderId === delivery.id) || {}).lines || []).find((l) => l.variantId === apples) || {}).qtyOutstanding !== undefined && num(((awaiting().find((a) => a.orderId === delivery.id) || {}).lines || []).find((l) => l.variantId === apples).qtyOutstanding) === 2) >= 0, awaiting());
  truthy('[+] the shopper is told what was swapped and that they pay no more', poll(60, () => told(shopper, 'ORDER_LINE_SUBSTITUTED').length >= 1) >= 0 && told(shopper, 'ORDER_LINE_SUBSTITUTED')[0].body.includes('pay no more'), told(shopper, 'ORDER_LINE_SUBSTITUTED'));

  // ── 4. a cheaper substitute: the difference goes back ────────────────────────
  const withPlums = must(substitute(delivery.id, { substituteVariantId: plums, qty: 1 }), 200, 'plums for one apple');
  const difference = num(withPears.total) - num(withPlums.total);
  truthy('[+] a cheaper stand-in lowers the total', difference > 0, { before: withPears.total, after: withPlums.total });
  truthy('[+] payment-svc refunds the difference to the card', poll(60, () => refunds(delivery.id).some((r) => Math.abs(num(r.amount) - difference) < 0.005)) >= 0, refunds(delivery.id));
  truthy('[+] the order keeps its status: the goods are still to be handed over', order(delivery.id).status === 'PARTIALLY_FULFILLED', order(delivery.id).status);

  // ── 5. the last apple closed short: refunded, FULFILLED, dispatched ──────────
  const closed = must(short(delivery.id, { reason: 'none left on the shelf' }), 200, 'the last apple closed short');
  truthy('[+] every line picked or closed: the order is FULFILLED, owing less', closed.status === 'FULFILLED' && num(closed.total) < num(withPlums.total) && num(line(closed, apples).shortQty) === 3, closed);
  truthy('[+] the apple\'s money goes back', poll(60, () => refunds(delivery.id).length >= 2) >= 0, refunds(delivery.id));
  truthy('[+] the shopper is told what could not be supplied', poll(60, () => told(shopper, 'ORDER_LINE_SHORT').length >= 1) >= 0 && told(shopper, 'ORDER_LINE_SHORT')[0].body.includes('could not include'), told(shopper, 'ORDER_LINE_SHORT'));
  truthy('[+] the store owes nothing more on it', !owing().some((o) => o.orderId === delivery.id), owing());
  truthy('[+] it waits to be picked no more', poll(60, () => !awaiting().some((a) => a.orderId === delivery.id)) >= 0, awaiting());
  must(call('POST', `${O}/orders/${delivery.id}/dispatch`, { token: keeper.token, body: { carrier: 'DPD', reference: '15501234567890' } }), 200, 'dispatched');
  expect(short(delivery.id, {}), '[-] a picked order has no line to close', 409, 'ORDER_LINE_NOT_ADJUSTABLE');
  expect(short(delivery.id, { qty: 1 }, owner), '[-] the same is true for its owner', 409, 'ORDER_LINE_NOT_ADJUSTABLE');

  // ── 6. a shopper who refused substitutions gets only a close ─────────────────
  const pickup = must(place(refuser, 'PICKUP', 1, { allowSubstitutions: false }), 201, 'a pickup, no substitutions');
  truthy('[+] the order records the refusal', pickup.allowSubstitutions === false, pickup);
  pay(pickup);
  truthy('[+] paid: confirmed', poll(60, () => order(pickup.id).status === 'CONFIRMED') >= 0, order(pickup.id).status);
  expect(substitute(pickup.id, { substituteVariantId: pears, qty: 1 }), '[-] the shopper\'s no is final', 409, 'ORDER_SUBSTITUTION_NOT_ALLOWED');
  const closedPickup = must(short(pickup.id, {}), 200, 'closed short');
  truthy('[+] closed short: complete, and the shopper told', closedPickup.status === 'FULFILLED' && poll(60, () => told(refuser, 'ORDER_LINE_SHORT').length >= 1) >= 0, closedPickup);

  // ── 7. a rival business reads and changes nothing ────────────────────────────
  const fresh = must(place(shopper, 'DELIVERY', 1), 201, 'another delivery');
  pay(fresh);
  truthy('[+] paid: confirmed', poll(60, () => order(fresh.id).status === 'CONFIRMED') >= 0, order(fresh.id).status);
  expect(short(fresh.id, {}, rival.owner.token), '[-] a rival closes nothing', 404, 'ORDER_NOT_FOUND');
  expect(substitute(fresh.id, { substituteVariantId: pears, qty: 1 }, rival.owner.token), '[-] a rival substitutes nothing', 404, 'ORDER_NOT_FOUND');
  expect(call('GET', `${O}/orders/${fresh.id}/lines/${apples}/substitutes`, { token: rival.owner.token }), '[-] a rival reads no suggestions', 404, 'ORDER_NOT_FOUND');
  truthy('[-] a rival\'s owing list is empty', owing(rival.owner.token).length === 0);
  truthy('[-] a rival\'s notification log has nothing of the shopper', told(shopper, 'ORDER_LINE_SUBSTITUTED', rival.owner.token).length === 0);
  const untouched = order(fresh.id);
  truthy('[+] and nothing of ours moved', untouched.status === 'CONFIRMED' && num(line(untouched, apples).shortQty) === 0 && (untouched.items || []).length === 1, untouched);
}
