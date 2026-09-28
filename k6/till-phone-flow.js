// A phone at the till (intent/phone-at-the-till.md), through the gateway, for a business in India
// with a shop in London and a rival in Britain. Each store says whether its till asks for the
// customer's phone — Required, Optional or Don't ask — Optional until the owner or a manager
// chooses; a cashier and a storekeeper cannot choose, nor anyone from another business, and a
// choice that is none of the three is refused. The till reads the choice from the store list a
// cashier may read. A Required shop refuses a till sale with neither a number nor a customer; the
// others take one with none. A number is read in the shop's own country, then the business's, and
// kept in international form beside the typed one; at the till one that is no phone where the
// business trades is refused, online it is kept as typed. Another business naming our shop is
// refused as it always was, our choice never applied to it. And a walk-in who gave the number the
// way people say it is texted when the lot they bought is recalled.
//
//   k6/run.sh till-phone-flow
import {
  ALL_CHECKS_PASS,
  call,
  data,
  errorCode,
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

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS, setupTimeout: '5m' };

const TS = '/api/v1/tenant-svc';
const O = '/api/v1/order-svc';
const P = '/api/v1/payment-svc';
const I = '/api/v1/inventory-svc';
const N = '/api/v1/notification-svc';
const LOT = 'L-TILL-1';

/** A shop of the Indian business, created with its till's choice (or none: the default). */
function shop(tenant, name, place, tillPhone) {
  return must(
    call('POST', `${TS}/admin/stores`, {
      token: tenant.owner.token,
      body: { name, code: `${name.slice(0, 6).toUpperCase()}-${uniq()}`.slice(0, 32), type: 'STORE', ...place, ...(tillPhone ? { tillPhone } : {}) },
    }),
    201,
    `store ${name}`
  );
}

const MUMBAI = { line1: '1 Marine Drive', city: 'Mumbai', pincode: '400020', country: 'IN', timezone: 'Asia/Kolkata' };
const LONDON = { line1: '1 High Street', city: 'London', pincode: 'EC1A 1BB', country: 'GB', timezone: 'Europe/London' };

export function setup() {
  // Every store is made before order-svc is asked anything about this business, so the first sale
  // reads them all as they are (order-svc keeps a business's stores for five minutes).
  const inb = onboardTenant('till-in', { country: 'IN', currency: 'INR' });
  const optional = inb.stores[0];
  const required = shop(inb, 'Required shop', MUMBAI, 'REQUIRED');
  const quiet = shop(inb, 'Quiet shop', MUMBAI, 'OFF');
  const london = shop(inb, 'London shop', LONDON, 'OPTIONAL');
  const all = [optional.id, required.id, quiet.id, london.id];
  const cashier = staffUser(inb, 'CASHIER', all);
  const keeper = staffUser(inb, 'STOREKEEPER', [optional.id]);
  const manager = staffUser(inb, 'MANAGER', [optional.id]);
  const chai = sellableVariant(inb, 'Masala chai').variantId;
  priceVariants(inb, [chai], '120.00');
  for (const id of all) {
    must(
      call('POST', `${I}/admin/inventory/receive`, { token: inb.owner.token, idem: true, body: { storeId: id, variantId: chai, qty: 40, batchNo: id === optional.id ? LOT : `L-${uniq()}`.slice(0, 32), costPrice: '80.00' } }),
      [200, 201],
      'chai on the shelf'
    );
  }
  const customer = must(
    call('POST', '/api/v1/customer-svc/customers', { token: inb.owner.token, body: { email: `till-${uniq()}@k6.storeql.test`, firstName: 'Kiran', lastName: 'Rao' } }),
    201,
    'a customer the shop knows'
  ).id;
  const rival = onboardTenant('till-rival', { country: 'GB', currency: 'GBP' });
  const rivals = {
    OWNER: rival.owner,
    MANAGER: staffUser(rival, 'MANAGER', [rival.stores[0].id]),
    CASHIER: staffUser(rival, 'CASHIER', [rival.stores[0].id]),
  };
  return { inb, optional, required, quiet, london, cashier, keeper, manager, chai, customer, rival, rivals, shopper: register('till-shopper') };
}

export default function ({ inb, optional, required, quiet, london, cashier, keeper, manager, chai, customer, rival, rivals, shopper }) {
  const owner = inb.owner.token;
  const storeOf = (id, token = owner) => call('GET', `${TS}/admin/stores/${id}`, { token });
  const choose = (id, tillPhone, token = owner, place = MUMBAI) =>
    call('PUT', `${TS}/admin/stores/${id}`, { token, body: { name: 'Mumbai shop', ...place, tillPhone } });
  const listed = (token) => data(call('GET', `${TS}/storefront/stores`, { token })) || [];
  const tillSale = (storeId, extra = {}, token = cashier.token) =>
    call('POST', `${O}/orders`, { token, idem: true, body: { storeId, channel: 'POS', fulfilmentType: 'INSTORE', paymentMethod: 'CASH', items: [{ variantId: chai, qty: 1 }], ...extra } });
  const online = (storeId, contactPhone) =>
    call('POST', `${O}/orders`, { token: shopper.token, storefront: inb.tenantId, idem: true, body: { storeId, channel: 'ONLINE', fulfilmentType: 'PICKUP', items: [{ variantId: chai, qty: 1 }], contactPhone } });

  // ── 1. the store's choice ────────────────────────────────────────────────────────────────────────
  truthy('[+] a new shop asks Optional until its owner chooses', data(storeOf(optional.id)).tillPhone === 'OPTIONAL', data(storeOf(optional.id)));
  truthy('[+] ...and each shop made with a choice keeps it', data(storeOf(required.id)).tillPhone === 'REQUIRED' && data(storeOf(quiet.id)).tillPhone === 'OFF');
  const tills = listed(cashier.token);
  const tillOf = (id) => (tills.find((s) => s.storeId === id) || {}).tillPhone;
  truthy('[+] the till reads every choice from the store list a cashier may read', tillOf(optional.id) === 'OPTIONAL' && tillOf(required.id) === 'REQUIRED' && tillOf(quiet.id) === 'OFF', tills.map((s) => `${s.storeName}:${s.tillPhone}`));
  expect(choose(optional.id, 'required'), '[+] the owner chooses Required, read the way it was meant', 200);
  truthy('[+] ...and it is kept', data(storeOf(optional.id)).tillPhone === 'REQUIRED');
  expect(choose(optional.id, 'OPTIONAL', manager.token), '[+] a manager of the shop chooses Optional again', 200);
  expect(choose(optional.id, 'SOMETIMES'), '[-] a choice that is none of the three', 400, 'STORE_TILL_PHONE_INVALID');
  expect(choose(optional.id, 'OFF', cashier.token), '[-] a cashier cannot choose for the till', 403);
  expect(choose(optional.id, 'OFF', keeper.token), '[-] nor a storekeeper', 403);
  for (const [role, who] of Object.entries(rivals)) {
    const r = choose(optional.id, 'OFF', who.token);
    truthy(`[-] the rival's ${role.toLowerCase()} cannot choose for our shop`, r.status === 404 || r.status === 403, `${r.status} ${errorCode(r)}`);
  }
  expect(storeOf(optional.id, rivals.OWNER.token), "[-] ...nor read our shop's", 404);
  truthy("[-] ...nor find it in their till's store list", !listed(rivals.CASHIER.token).some((s) => s.storeId === optional.id));
  truthy('[+] nothing moved', data(storeOf(optional.id)).tillPhone === 'OPTIONAL');

  // ── 2. at the till ───────────────────────────────────────────────────────────────────────────────
  expect(tillSale(required.id), '[-] a Required shop refuses a till sale with neither a number nor a customer', 409, 'ORDER_CONTACT_PHONE_REQUIRED');
  expect(tillSale(required.id, { contactPhone: '   ' }), '[-] ...a blank is no number', 409, 'ORDER_CONTACT_PHONE_REQUIRED');
  const given = tillSale(required.id, { contactPhone: '98860 21001' });
  expect(given, '[+] a number given the way people say it is taken', 201);
  truthy('[+] ...kept as typed and in international form', data(given).contactPhone === '98860 21001' && data(given).contactPhoneE164 === '+919886021001', data(given));
  expect(tillSale(required.id, { customerId: customer }), '[+] a customer the shop knows is taken instead of a number', 201);
  const bare = tillSale(optional.id);
  expect(bare, '[+] an Optional shop takes a sale with no number, as the customer prefers', 201);
  truthy('[+] ...and keeps none', data(bare).contactPhone === undefined && data(bare).contactPhoneE164 === undefined, data(bare));
  expect(tillSale(quiet.id), "[+] a Don't-ask shop never asks", 201);
  expect(tillSale(optional.id, { contactPhone: '12345' }), '[-] a number that is no phone where the business trades is refused at the till', 400, 'ORDER_CONTACT_PHONE_INVALID');
  expect(tillSale(optional.id, { contactPhone: '+44 7700 900111' }), '[-] ...as is a + number that is not one', 400, 'ORDER_CONTACT_PHONE_INVALID');
  truthy('[+] a number given at the London shop is read as British', data(tillSale(london.id, { contactPhone: '07400 123456' })).contactPhoneE164 === '+447400123456');
  const typedOnline = online(optional.id, '12345');
  expect(typedOnline, '[+] online, a number that does not read is kept as typed, the order placed', 201);
  truthy('[+] ...with no international form', data(typedOnline).contactPhone === '12345' && data(typedOnline).contactPhoneE164 === undefined, data(typedOnline));
  truthy("[+] a shopper's number is kept in international form too", data(online(optional.id, '98450 12345')).contactPhoneE164 === '+919845012345');

  // ── 3. another business ──────────────────────────────────────────────────────────────────────────
  for (const [role, who] of Object.entries(rivals)) {
    const r = tillSale(required.id, {}, who.token);
    truthy(`[-] the rival's ${role.toLowerCase()} naming our Required shop is refused as always, never for a phone`, (r.status === 409 && errorCode(r) === 'STORE_NOT_OPERATIONAL') || (r.status === 403 && errorCode(r) === 'STORE_ACCESS_DENIED'), `${r.status} ${errorCode(r)}`);
  }
  const theirs = data(call('GET', `${O}/orders?limit=100`, { token: rivals.OWNER.token })) || [];
  truthy('[-] ...and nothing of ours reached their orders', !theirs.some((o) => [optional.id, required.id, quiet.id, london.id].includes(o.storeId)), theirs.length);

  // ── 4. a recall reaches the walk-in ─────────────────────────────────────────────────────────────
  const walkIn = must(tillSale(optional.id, { contactPhone: '98860 21001' }), 201, 'a walk-in leaves a number');
  must(call('POST', `${P}/payments`, { token: cashier.token, idem: true, body: { orderId: walkIn.id, amount: walkIn.total, method: 'CASH', storeId: optional.id } }), [200, 201], 'paid in cash');
  truthy(
    '[+] the sale is handed over',
    poll(60, () => (data(call('GET', `${O}/orders/${walkIn.id}`, { token: owner })) || {}).status === 'FULFILLED') >= 0
  );
  // inventory-svc draws the sale from the lot when it hears the order was handed over; a recall
  // opened before that finds no sale to reach. The walk-in's is the only paid sale at this shop.
  const onHand = () => {
    const rows = data(call('GET', `${I}/admin/inventory/levels?store=${optional.id}&limit=100`, { token: owner })) || [];
    const row = rows.find((r) => r.variantId === chai);
    return row ? Number(row.onHand) : NaN;
  };
  truthy('[+] ...and inventory draws it from the lot', poll(60, () => onHand() === 39) >= 0, onHand());
  const recall = must(
    call('POST', `${I}/admin/recalls`, {
      token: owner,
      body: { reference: `K6-TILL-${uniq()}`.slice(0, 40), kind: 'RECALL', hazard: 'ALLERGEN', reason: 'Undeclared peanut', source: 'SUPPLIER', customerNotice: 'Do not drink it. Bring it back for a refund.', items: [{ variantId: chai, batchNo: LOT }], remedies: ['REFUND'], contactPhone: '1800 11 4000' },
    }),
    201,
    'the lot is recalled'
  );
  truthy('[+] the recall reaches the walk-in\'s sale', recall.ordersAffected === 1, recall);
  let texts = [];
  truthy(
    '[+] the walk-in who gave "98860 21001" is texted at +919886021001',
    poll(90, () => {
      texts = data(call('GET', `${N}/admin/notifications?channel=SMS&recipient=${encodeURIComponent('+919886021001')}&limit=50`, { token: owner })) || [];
      return texts.some((n) => n.type === 'RECALL_NOTICE_SMS');
    }) >= 0,
    { recall: recall.id, texts: texts.map((n) => `${n.type}:${n.recipient}`) }
  );
}
