// Ship-from-store and dark-store picking through the gateway: a business opens a dark store — a shop
// with no shop floor — that serves a postcode; the storefront lists it delivery-only, a collection
// there is refused and no till opens at it (the shop's does). A shopper's delivery for that postcode
// is placed at the dark store, paid, picked by a wave there (picked and packed: FULFILLED), waits
// for the courier on the store's fulfilment queue, is dispatched once by the storekeeper with the
// carrier and reference — the shopper's history says so and they are told — and the stock left the
// dark store. A pickup at the shop is picked by hand, the shopper told it is ready, and collected
// by the cashier naming who took it. A rival business reads none of it and hands nothing over.
//
//   k6/run.sh ship-from-store-flow
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
const N = '/api/notification-svc';
const TS = '/api/tenant-svc';
const IAM = '/api/iam-svc';

export function setup() {
  const tenant = onboardTenant('sfs');
  const shop = tenant.stores[0];
  const t = tenant.owner.token;
  const dark = must(call('POST', `${TS}/admin/stores`, { token: t, body: { name: 'Online hub', code: `HUB-${uniq()}`.slice(0, 32), type: 'DARK_STORE', line1: '1 Dock Road', city: 'Leeds', pincode: 'LS1 1AA', country: 'GB', timezone: 'Europe/London', geoLat: 53.79, geoLng: -1.54 } }), 201, 'a dark store');
  const pincode = `LS${uniq()}`.slice(0, 12);
  must(call('POST', `${TS}/admin/stores/${dark.id}/delivery-areas`, { token: t, body: { pincode, priority: 1 } }), 201, 'the dark store delivers to the postcode');
  const apples = sellableVariant(tenant, 'Apples').variantId;
  priceVariants(tenant, [apples], '2.00');
  const keeper = staffUser(tenant, 'STOREKEEPER', [dark.id]);
  const cashier = staffUser(tenant, 'CASHIER', [dark.id, shop.id]);
  const rival = onboardTenant('sfs-rival');
  return { tenant, shop, dark: { id: dark.id, name: dark.name }, pincode, apples, keeper, cashier, rival, shopper: register('sfs-shopper') };
}

export default function ({ tenant, shop, dark, pincode, apples, keeper, cashier, rival, shopper }) {
  const owner = tenant.owner.token;
  const num = (v) => Number(v || 0);
  const receive = (store, qty) => must(call('POST', `${I}/admin/inventory/receive`, { token: owner, idem: true, body: { storeId: store.id, variantId: apples, qty, costPrice: '1.00' } }), 201, `receive at ${store.name}`);
  const onHand = (store) => num(((data(call('GET', `${I}/admin/inventory/levels?store=${store.id}`, { token: owner })) || []).find((l) => l.variantId === apples) || {}).onHand);
  const status = (id) => (data(call('GET', `${O}/orders/${id}`, { token: owner })) || {}).status;
  const body = (storeId, fulfilment, qty) => Object.assign(
    { storeId, channel: 'ONLINE', fulfilmentType: fulfilment, items: [{ variantId: apples, qty }], contactPhone: '07700900123' },
    fulfilment === 'DELIVERY' ? { deliveryLine1: '12 High Street', deliveryCity: 'Leeds', deliveryPostalCode: pincode, deliveryRecipientName: 'Chris Carter', deliveryRecipientPhone: '07700900123' } : {},
  );
  const place = (storeId, fulfilment, qty) => call('POST', `${O}/orders`, { token: shopper.token, storefront: tenant.tenantId, idem: true, body: body(storeId, fulfilment, qty) });
  const pay = (order) => must(call('POST', `${P}/payments`, { token: owner, idem: true, body: { orderId: order.id, amount: order.total, method: 'CARD', storeId: order.storeId } }), [200, 201], 'paid by card');
  const told = (type, token = owner) => (data(call('GET', `${N}/admin/notifications?recipient=${encodeURIComponent(shopper.email)}&limit=50`, { token })) || []).filter((n) => n.type === type);

  // ── 1. the dark store: listed delivery-only; no collection, no till ──────────
  const listed = (data(call('GET', `${TS}/storefront/stores`, { storefront: tenant.tenantId })) || []).find((s) => s.storeId === dark.id) || {};
  truthy('[+] the storefront lists the dark store delivery-only', listed.type === 'DARK_STORE' && listed.pickupOffered === false, listed);
  truthy("[-] a rival's storefront lists no such store", !(data(call('GET', `${TS}/storefront/stores`, { storefront: rival.tenantId })) || []).some((s) => s.storeId === dark.id));
  expect(place(dark.id, 'PICKUP', 1), '[-] no collection at a dark store', 409, 'ORDER_PICKUP_NOT_OFFERED');
  // iam-svc learns the type from tenant-svc's announcement: a moment after the store is made.
  const noTill = poll(60, () => call('POST', `${IAM}/auth/pos/sessions`, { token: cashier.token, body: { storeId: dark.id } }).status === 409);
  truthy('[-] no till opens at a dark store', noTill >= 0, noTill);
  expect(call('POST', `${IAM}/auth/pos/sessions`, { token: cashier.token, body: { storeId: dark.id } }), '[-] ...and it says why', 409, 'POS_STORE_HAS_NO_TILL');
  const till = must(call('POST', `${IAM}/auth/pos/sessions`, { token: cashier.token, body: { storeId: shop.id } }), 201, "the shop's till opens");
  call('DELETE', `${IAM}/auth/pos/sessions/${till.id}`, { token: cashier.token });

  // ── 2. a delivery: placed at the dark store, paid, waved, dispatched ─────────
  receive(dark, 10);
  const delivery = must(place(shop.id, 'DELIVERY', 2), 201, 'a delivery');
  truthy('[+] the postcode puts the delivery at the dark store', delivery.storeId === dark.id, delivery);
  pay(delivery);
  truthy('[+] paid: confirmed', poll(60, () => status(delivery.id) === 'CONFIRMED') >= 0, status(delivery.id));
  const waiting = poll(90, () => (data(call('GET', `${I}/admin/inventory/waves/awaiting?storeId=${dark.id}`, { token: owner })) || []).some((a) => a.orderId === delivery.id));
  truthy('[+] it waits to be picked at the dark store', waiting >= 0);
  expect(call('POST', `${O}/orders/${delivery.id}/dispatch`, { token: keeper.token, body: { carrier: 'DPD' } }), '[-] not dispatched before it is picked', 409, 'ORDER_NOT_PICKED');
  const wave = must(call('POST', `${I}/admin/inventory/waves`, { token: keeper.token, idem: true, body: { storeId: dark.id, orderIds: [delivery.id] } }), 201, 'a wave at the dark store');
  must(call('POST', `${I}/admin/inventory/waves/${wave.id}/picks`, { token: keeper.token, body: { lines: wave.lines.map((l) => ({ lineId: l.id, pickedQty: l.directedQty })) } }), 200, 'picked');
  must(call('POST', `${I}/admin/inventory/waves/${wave.id}/complete`, { token: keeper.token, body: {} }), 200, 'completed');
  truthy('[+] the wave leaves the order picked and packed', poll(90, () => status(delivery.id) === 'FULFILLED') >= 0, status(delivery.id));
  truthy('[+] the stock left the dark store', poll(60, () => onHand(dark) === 8) >= 0, onHand(dark));
  const packed = data(call('GET', `${O}/orders?store=${dark.id}&status=FULFILLED&fulfilmentType=DELIVERY&handover=PENDING`, { token: owner })) || [];
  truthy('[+] it waits for the courier on the fulfilment queue', packed.some((o) => o.id === delivery.id), packed);
  expect(call('POST', `${O}/orders/${delivery.id}/collect`, { token: keeper.token, body: {} }), '[-] a delivery is not collected', 409, 'ORDER_HANDOVER_KIND_MISMATCH');
  const dispatched = must(call('POST', `${O}/orders/${delivery.id}/dispatch`, { token: keeper.token, body: { carrier: 'DPD', reference: '15501234567890', parcels: 1 } }), 200, 'dispatched');
  truthy('[+] the storekeeper dispatches it with the carrier and reference', dispatched.handover && dispatched.handover.kind === 'DISPATCHED' && dispatched.handover.carrier === 'DPD' && dispatched.handover.reference === '15501234567890', dispatched.handover);
  expect(call('POST', `${O}/orders/${delivery.id}/dispatch`, { token: keeper.token, body: { carrier: 'Evri' } }), '[-] dispatched once', 409, 'ORDER_ALREADY_HANDED_OVER');
  const mine = (data(call('GET', `${O}/orders/mine`, { token: shopper.token, storefront: tenant.tenantId })) || []).find((o) => o.id === delivery.id) || {};
  truthy("[+] the shopper's history says it is on its way", mine.handover && mine.handover.kind === 'DISPATCHED' && mine.handover.reference === '15501234567890', mine);
  truthy('[+] the shopper is told, naming the carrier', poll(60, () => told('ORDER_DISPATCHED').length >= 1) >= 0 && told('ORDER_DISPATCHED')[0].body.includes('DPD'), told('ORDER_DISPATCHED'));
  const gone = data(call('GET', `${O}/orders?store=${dark.id}&handover=DONE`, { token: owner })) || [];
  truthy('[+] and it is on the handed-over list, off the queue', gone.some((o) => o.id === delivery.id) && !(data(call('GET', `${O}/orders?store=${dark.id}&status=FULFILLED&fulfilmentType=DELIVERY&handover=PENDING`, { token: owner })) || []).some((o) => o.id === delivery.id), gone);

  // ── 3. a collection at the shop: picked by hand, the shopper told, collected ──
  receive(shop, 5);
  const pickup = must(place(shop.id, 'PICKUP', 1), 201, 'a pickup at the shop');
  pay(pickup);
  truthy('[+] paid: confirmed', poll(60, () => status(pickup.id) === 'CONFIRMED') >= 0, status(pickup.id));
  expect(call('POST', `${O}/orders/${pickup.id}/collect`, { token: owner, body: {} }), '[-] not collected before it is picked', 409, 'ORDER_NOT_PICKED');
  must(call('POST', `${O}/orders/${pickup.id}/fulfil`, { token: owner, body: {} }), 200, 'picked and packed by hand');
  truthy('[+] the shopper is told it is ready to collect', poll(60, () => told('ORDER_READY_FOR_COLLECTION').length >= 1) >= 0, told('ORDER_READY_FOR_COLLECTION'));
  const readyQueue = data(call('GET', `${O}/orders?store=${shop.id}&status=FULFILLED&fulfilmentType=PICKUP&handover=PENDING`, { token: owner })) || [];
  truthy('[+] it waits for the shopper on the queue', readyQueue.some((o) => o.id === pickup.id), readyQueue);
  expect(call('POST', `${O}/orders/${pickup.id}/dispatch`, { token: cashier.token, body: { carrier: 'DPD' } }), '[-] a pickup is not dispatched', 409, 'ORDER_HANDOVER_KIND_MISMATCH');
  const collected = must(call('POST', `${O}/orders/${pickup.id}/collect`, { token: cashier.token, body: { collectedBy: 'Chris Carter' } }), 200, 'collected');
  truthy('[+] the cashier records who collected it', collected.handover && collected.handover.kind === 'COLLECTED' && collected.handover.collectedBy === 'Chris Carter', collected.handover);
  expect(call('POST', `${O}/orders/${pickup.id}/collect`, { token: cashier.token, body: {} }), '[-] collected once', 409, 'ORDER_ALREADY_HANDED_OVER');

  // ── 4. another business reads none of it and hands nothing over ──────────────
  expect(call('GET', `${O}/orders/${delivery.id}`, { token: rival.owner.token }), '[-] a rival reads no order', 404);
  expect(call('POST', `${O}/orders/${pickup.id}/collect`, { token: rival.owner.token, body: {} }), '[-] a rival collects nothing', 404);
  expect(call('POST', `${O}/orders/${delivery.id}/dispatch`, { token: rival.owner.token, body: { carrier: 'DPD' } }), '[-] a rival dispatches nothing', 404);
  truthy("[-] a rival's queue is empty", (data(call('GET', `${O}/orders?store=${dark.id}&handover=DONE`, { token: rival.owner.token })) || []).length === 0);
  truthy("[-] a rival's notification log has nothing of the shopper", told('ORDER_DISPATCHED', rival.owner.token).length === 0);
}
