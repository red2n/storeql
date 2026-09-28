// Order orchestration and split fulfilment through the gateway: Leeds serves the shopper's postcode
// and holds the apples; York (near) and Hull (far) hold pears; a warehouse holds plenty of apples.
// A delivery of apples and pears is placed as one checkout in two parts — Leeds first, then York,
// the nearer of the two that hold the rest — each part held at its own shop. A part is never paid
// alone and the wrong amount is refused; one payment confirms both parts; each is fulfilled at its
// own shop and the stock leaves from there; the checkout is read by its shopper, not by another
// shopper or another business; the same key places it once; and an order no mix of shops can fill
// (the warehouse serves its shops, never a shopper) is refused with nothing held.
//
// Needs checkout holds on — order-svc's production default, which the dev rig turns off — because a
// delivery is routed only where stock is held at checkout:
//
//   STOREQL_ORDER_RESERVE_ENFORCE=true docker compose up -d order-svc
//   k6/run.sh split-fulfilment-flow
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
  register,
  sellableVariant,
  truthy,
  uniq,
} from './lib/storeql.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS, setupTimeout: '4m' };

const I = '/api/inventory-svc';
const O = '/api/order-svc';
const P = '/api/payment-svc';

export function setup() {
  const tenant = onboardTenant('split');
  const t = tenant.owner.token;
  const shop = (suffix, type, lat, lng) => {
    const code = `${suffix}-${uniq()}`.slice(0, 32);
    const s = must(call('POST', '/api/tenant-svc/admin/stores', { token: t, body: { name: `Split ${suffix}`, code, type, line1: '1 High Street', city: 'Leeds', pincode: 'LS1 1AA', country: 'GB', timezone: 'Europe/London', geoLat: lat, geoLng: lng } }), 201, `store ${suffix}`);
    return { id: s.id, name: s.name };
  };
  const leeds = shop('LEEDS', 'STORE', 53.8008, -1.5491);
  const york = shop('YORK', 'STORE', 53.96, -1.0873);
  const hull = shop('HULL', 'STORE', 53.7676, -0.3274);
  const depot = shop('DEPOT', 'WAREHOUSE', 53.79, -1.54);
  const pincode = `LS${uniq()}`.slice(0, 12);
  must(call('POST', `/api/tenant-svc/admin/stores/${leeds.id}/delivery-areas`, { token: t, body: { pincode, priority: 1 } }), 201, 'Leeds delivers to the postcode');
  const apples = sellableVariant(tenant, 'Apples').variantId;
  const pears = sellableVariant(tenant, 'Pears').variantId;
  priceVariants(tenant, [apples, pears], '2.50');
  const rival = onboardTenant('split-rival');
  return { tenant, leeds, york, hull, depot, pincode, apples, pears, rival, shopper: register('split-shopper'), stranger: register('split-stranger') };
}

export default function ({ tenant, leeds, york, hull, depot, pincode, apples, pears, rival, shopper, stranger }) {
  const owner = tenant.owner.token;
  const num = (v) => Number(v || 0);
  const receive = (store, variantId, qty) => must(call('POST', `${I}/admin/inventory/receive`, { token: owner, idem: true, body: { storeId: store.id, variantId, qty, costPrice: '1.00' } }), 201, `receive at ${store.name}`);
  const level = (store, variantId) => (data(call('GET', `${I}/admin/inventory/levels?store=${store.id}`, { token: owner })) || []).find((l) => l.variantId === variantId) || {};
  const status = (id) => (data(call('GET', `${O}/orders/${id}`, { token: owner })) || {}).status;
  const body = (items) => ({ storeId: leeds.id, channel: 'ONLINE', fulfilmentType: 'DELIVERY', deliveryLine1: '12 High Street', deliveryCity: 'Leeds', deliveryPostalCode: pincode, deliveryRecipientName: 'Chris Carter', deliveryRecipientPhone: '07700900123', contactPhone: '07700900123', items });
  const place = (items, key) => call('POST', `${O}/orders`, { token: shopper.token, storefront: tenant.tenantId, headers: { 'Idempotency-Key': key || newKey() }, body: body(items) });
  const pay = (who, payload) => call('POST', `${P}/payments/online`, { token: who.token, storefront: tenant.tenantId, idem: true, body: payload });

  // ── 1. the shelves: apples at Leeds and the depot, pears at York and Hull ─────
  receive(leeds, apples, 5);
  receive(york, pears, 5);
  receive(hull, pears, 5);
  receive(depot, apples, 100);

  // ── 2. one checkout, two parts: Leeds first, then York, the nearer ───────────
  const first = must(place([{ variantId: apples, qty: 2 }, { variantId: pears, qty: 3 }]), 201, 'a split delivery');
  const group = first.group || {};
  const parts = group.parts || [];
  const [p0, p1] = [parts[0] || {}, parts[1] || {}];
  truthy('[+] the checkout comes in two parts, the delivery-area store first and York second (needs checkout holds on)', parts.length === 2 && p0.storeId === leeds.id && p1.storeId === york.id && first.id === p0.orderId, { group, order: first.id, store: first.storeId });
  if (parts.length !== 2) return;
  truthy('[+] the parts add up to the checkout', Math.abs(num(p0.total) + num(p1.total) - num(group.total)) < 0.001 && num(p0.units) === 2 && num(p1.units) === 3, group);
  truthy('[+] each part is held at its own shop, nothing at Hull', num(level(leeds, apples).reserved) === 2 && num(level(york, pears).reserved) === 3 && num(level(hull, pears).reserved) === 0, { leeds: level(leeds, apples), york: level(york, pears), hull: level(hull, pears) });

  // ── 3. paid once, for the whole checkout ─────────────────────────────────────
  expect(pay(shopper, { orderId: parts[0].orderId, amount: parts[0].total, method: 'CARD' }), '[-] a part is never paid alone', 409, 'PAYMENT_ORDER_IN_GROUP');
  expect(pay(shopper, { groupId: group.id, amount: num(group.total) - 1, method: 'CARD' }), '[-] the checkout is paid in full', 400, 'PAYMENT_GROUP_AMOUNT_MISMATCH');
  expect(pay(stranger, { groupId: group.id, amount: group.total, method: 'CARD' }), "[-] another shopper cannot pay someone's checkout", 404, 'PAYMENT_GROUP_NOT_FOUND');
  const paid = must(pay(shopper, { groupId: group.id, amount: group.total, method: 'CARD' }), 201, 'the checkout paid');
  truthy('[+] one payment, a tender per part', (paid.tenders || []).length === 2 && paid.tenders[1].orderId === parts[1].orderId, paid);
  const confirmed = poll(60, () => status(parts[0].orderId) === 'CONFIRMED' && status(parts[1].orderId) === 'CONFIRMED');
  truthy('[+] both parts confirmed from the one payment', confirmed >= 0, { first: status(parts[0].orderId), second: status(parts[1].orderId) });

  // ── 4. the checkout, read by its shopper only ───────────────────────────────
  const read = must(call('GET', `${O}/order-groups/${group.id}`, { token: shopper.token, storefront: tenant.tenantId }), 200, 'the shopper reads the checkout');
  truthy('[+] the shopper sees both parts confirmed', (read.parts || []).length === 2 && read.parts.every((p) => p.status === 'CONFIRMED'), read);
  expect(call('GET', `${O}/order-groups/${group.id}`, { token: stranger.token, storefront: tenant.tenantId }), "[-] another shopper cannot read it", 404, 'ORDER_GROUP_NOT_FOUND');
  expect(call('GET', `${O}/order-groups/${group.id}`, { token: rival.owner.token }), '[-] another business cannot read it', 404, 'ORDER_GROUP_NOT_FOUND');
  const mine = data(call('GET', `${O}/orders/mine`, { token: shopper.token, storefront: tenant.tenantId })) || [];
  truthy('[+] the history names the checkout on both parts', mine.filter((o) => o.groupId === group.id).length === 2, mine);

  // ── 5. each part fulfilled at its own shop, the stock leaving from there ─────
  must(call('POST', `${O}/orders/${parts[0].orderId}/fulfil`, { token: owner, body: {} }), 200, 'Leeds hands over its part');
  must(call('POST', `${O}/orders/${parts[1].orderId}/fulfil`, { token: owner, body: {} }), 200, 'York hands over its part');
  const left = poll(60, () => num(level(leeds, apples).onHand) === 3 && num(level(york, pears).onHand) === 2);
  truthy('[+] the apples left Leeds and the pears left York; Hull untouched', left >= 0 && num(level(hull, pears).onHand) === 5, { leeds: level(leeds, apples), york: level(york, pears), hull: level(hull, pears) });

  // ── 6. the same key places the checkout once ─────────────────────────────────
  const key = newKey();
  const once = must(place([{ variantId: apples, qty: 1 }, { variantId: pears, qty: 1 }], key), 201, 'a second split');
  const again = must(place([{ variantId: apples, qty: 1 }, { variantId: pears, qty: 1 }], key), 201, 'the same key');
  truthy('[+] the same key returns the same checkout', again.id === once.id && once.group && again.group && again.group.id === once.group.id, { once: once.group, again: again.group });

  // ── 7. nothing that adds up: the warehouse never serves a shopper ────────────
  expect(place([{ variantId: apples, qty: 20 }]), '[-] no mix of shops holds twenty apples', 409, 'ORDER_UNFULFILLABLE');
  truthy('[+] and nothing is held at the depot', num(level(depot, apples).reserved) === 0, level(depot, apples));
}
