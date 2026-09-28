// A product safety recall reaching the people who bought the product (05.10), end to end through
// the gateway: a shopper's online order, a till sale to a walk-in who left a number, and an
// anonymous till sale of one lot; the recall opened against that lot; the notices order-svc issues; the
// notification the shopper gets; the shopper choosing a remedy; staff settling through a return;
// and the wrong caller and the wrong input at every step. A German business shows GPSR's stricter
// notice rules binding.
//
//   k6/run.sh recall-buyers
import { group } from 'k6';
import http from 'k6/http';
import {
  ALL_CHECKS_PASS,
  BASE,
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
} from './lib/storeql.js';

export const options = {
  scenarios: { flow: { executor: 'per-vu-iterations', vus: 1, iterations: 1, maxDuration: '10m' } },
  setupTimeout: '5m',
  thresholds: ALL_CHECKS_PASS,
  batch: 20,
  batchPerHost: 20,
};

const NOTICE = 'Do not eat. Bring the jar back to any of our stores for a full refund or a new jar.';

function receiveLot(tenant, storeId, variantId, qty, lot, expiry) {
  return call('POST', '/api/inventory-svc/admin/inventory/receive', {
    token: tenant.owner.token,
    idem: true,
    body: { storeId, variantId, qty, batchNo: lot, expiryDate: expiry, costPrice: '4.00' },
  });
}

export function setup() {
  const gb = onboardTenant('recall-gb', { country: 'GB', currency: 'GBP' });
  const rival = onboardTenant('recall-rival', { country: 'GB', currency: 'GBP' });
  const store = gb.stores[0];
  const variantId = sellableVariant(gb, 'Crunchy peanut butter').variantId;
  priceVariants(gb, [variantId], '3.50');
  // One lot at the store; every sale below draws from it.
  must(receiveLot(gb, store.id, variantId, 30, 'L-2291', '2026-12-31'), [200, 201], 'receive lot');
  const cashier = staffUser(gb, 'CASHIER', [store.id]);
  const manager = staffUser(gb, 'MANAGER', [store.id]);
  const shopper = register('recall-shopper');
  const bystander = register('recall-bystander');
  // A second line nobody has bought, for the recalls group 2 opens to test the offer's rules.
  const spare = sellableVariant(gb, 'Smooth peanut butter').variantId;
  const de = onboardTenant('recall-de', { country: 'DE', currency: 'EUR' });
  const deVariant = sellableVariant(de, 'Erdnussbutter').variantId;
  return { gb, rival, store, variantId, spare, cashier, manager, shopper, bystander, de, deVariant };
}

export default function ({ gb, rival, store, variantId, spare, cashier, manager, shopper, bystander, de, deVariant }) {
  const owner = gb.owner.token;
  const shop = { storefront: gb.tenantId };
  const onHand = () => {
    const rows = data(call('GET', `/api/inventory-svc/admin/inventory/levels?store=${store.id}&limit=100`, { token: owner })) || [];
    const row = rows.find((r) => r.variantId === variantId);
    return row ? Number(row.onHand) : NaN;
  };

  // ── three sales of the lot ────────────────────────────────────────────────────
  let shopperOrder;
  let guestOrder;
  let tillOrder;
  group('1. three buyers', () => {
    const placed = call('POST', '/api/order-svc/orders', {
      token: shopper.token, ...shop, idem: true,
      body: { storeId: store.id, channel: 'ONLINE', fulfilmentType: 'PICKUP', items: [{ variantId, qty: 2 }] },
    });
    shopperOrder = data(placed);
    expect(placed, '[+] a shopper orders two jars online', 201);
    expect(call('POST', `/api/order-svc/orders/${shopperOrder.id}/confirm`, { token: owner, body: {} }), '[+] confirmed', 200);
    expect(call('POST', `/api/order-svc/orders/${shopperOrder.id}/fulfil`, { token: owner }), '[+] and handed over', 200);

    // There is no anonymous checkout through the gateway; the buyer with no account is a walk-in
    // who leaves a number at the till.
    const guest = call('POST', '/api/order-svc/orders', {
      token: cashier.token, idem: true,
      body: { storeId: store.id, channel: 'POS', fulfilmentType: 'INSTORE', paymentMethod: 'CASH', currency: 'GBP', contactPhone: '+447400900123', items: [{ variantId, qty: 1 }] },
    });
    guestOrder = data(guest);
    expect(guest, '[+] a walk-in buys one at the till, leaving a number', 201);
    expect(
      call('POST', '/api/payment-svc/payments', { token: cashier.token, idem: true, body: { orderId: guestOrder.id, amount: guestOrder.total, method: 'CASH', storeId: store.id, currency: 'GBP' } }),
      '[+] paid in cash',
      [200, 201]
    );

    const till = call('POST', '/api/order-svc/orders', {
      token: cashier.token, idem: true,
      body: { storeId: store.id, channel: 'POS', fulfilmentType: 'INSTORE', paymentMethod: 'CASH', currency: 'GBP', items: [{ variantId, qty: 1 }] },
    });
    tillOrder = data(till);
    expect(till, '[+] another walk-in buys one, leaving nothing', 201);
    expect(
      call('POST', '/api/payment-svc/payments', { token: cashier.token, idem: true, body: { orderId: tillOrder.id, amount: tillOrder.total, method: 'CASH', storeId: store.id, currency: 'GBP' } }),
      '[+] paid in cash',
      [200, 201]
    );
    truthy('[+] inventory records the four jars sold', poll(60, () => onHand() === 26) >= 0, onHand());
  });

  // ── what a recall must offer ──────────────────────────────────────────────────
  group('2. the offer, and the words', () => {
    const base = (overrides) => ({
      reference: `K6-OFFER-${Math.random().toString(36).slice(2, 8)}`,
      kind: 'RECALL', hazard: 'ALLERGEN', reason: 'Undeclared peanut', source: 'FSA',
      customerNotice: NOTICE, items: [{ variantId: spare }],
      remedies: ['REFUND', 'REPLACEMENT'], contactPhone: '0800 100 200',
      ...overrides,
    });
    const open = (token, body) => call('POST', '/api/inventory-svc/admin/recalls', { token, body });
    expect(open(owner, base({ remedies: [] })), '[-] a recall with no remedy', 400, 'RECALL_REMEDIES_REQUIRED');
    expect(open(owner, base({ contactPhone: undefined })), '[-] or no contact', 400, 'RECALL_CONTACT_REQUIRED');
    expect(open(owner, base({ contactPhone: undefined, contactUrl: 'ftp://x' })), '[-] or a contact that is not a web address', 400, 'RECALL_CONTACT_URL_INVALID');
    expect(open(owner, base({ remedies: ['CASH'] })), '[-] or a remedy the law does not name', 400);
    expect(open(owner, base({ contactPhone: 'call me' })), '[-] or a number that is not one', 400);
    expect(open(owner, base({ soldFrom: '31/12/2025' })), '[-] or a date not written as one', 400);
    expect(open(cashier.token, base({})), '[-] a cashier cannot open a recall', 403);
    expect(open(shopper.token, base({})), '[-] nor a shopper', 403);
    expect(open(undefined, base({})), '[-] nor nobody', 401);
    // A German business: GPSR asks for two remedies or a reason, and plain words.
    const deBase = (overrides) => base({ items: [{ variantId: deVariant }], ...overrides });
    expect(open(de.owner.token, deBase({ remedies: ['REFUND'] })), '[-] one remedy with no reason, in Germany', 400, 'RECALL_REMEDIES_INSUFFICIENT');
    expect(open(de.owner.token, deBase({ customerNotice: `A precautionary recall. ${NOTICE}` })), '[-] "precautionary", in Germany', 400, 'RECALL_NOTICE_MINIMISES_RISK');
    const deOk = open(de.owner.token, deBase({ remedies: ['REFUND'], singleRemedyReason: 'Opened food cannot be repaired or replaced' }));
    expect(deOk, '[+] one remedy with its reason', 201);
    truthy('[+] ...kept on the recall', data(deOk).singleRemedyReason === 'Opened food cannot be repaired or replaced' && data(deOk).ordersAffected === 0, data(deOk));
    const gbSoft = open(owner, base({ customerNotice: `A precautionary recall. ${NOTICE}`, remedies: ['REFUND'] }));
    expect(gbSoft, '[+] a British business is not bound by the EU wording rule, nor to two remedies', 201);
  });

  // ── the recall reaches the buyers ─────────────────────────────────────────────
  let recall;
  let notices = [];
  group('3. every buyer told', () => {
    const opened = call('POST', '/api/inventory-svc/admin/recalls', {
      token: manager.token,
      body: {
        reference: `K6-RECALL-${Math.random().toString(36).slice(2, 8)}`, kind: 'RECALL', hazard: 'ALLERGEN',
        reason: 'Peanut not declared on the label', source: 'FSA', sourceReference: 'FSA-PRIN-42-2026',
        customerNotice: NOTICE, items: [{ variantId, batchNo: 'L-2291' }],
        remedies: ['REFUND', 'REPLACEMENT'], contactPhone: '0800 100 200', contactUrl: 'https://recall.example.com',
      },
    });
    recall = data(opened);
    expect(opened, '[+] a manager recalls the lot', 201);
    truthy('[+] the recall found the three orders that drew on it', recall.ordersAffected === 3 && Number(recall.qtySold) === 4, recall);
    truthy('[+] ...and carries the offer', recall.remedies.length === 2 && recall.contactPhone === '0800 100 200', recall);

    const progress = () => data(call('GET', `/api/order-svc/orders/recall-notices/progress?recallId=${recall.id}`, { token: owner }));
    truthy('[+] order-svc issued a notice per order', poll(60, () => progress().notices === 3) >= 0, progress());
    const p = progress();
    truthy('[+] two buyers could be told, one till sale names nobody', p.identified === 2 && p.unidentified === 1, p);
    notices = data(call('GET', `/api/order-svc/orders/recall-notices?recallId=${recall.id}&limit=100`, { token: owner })) || [];
    const mine = notices.find((n) => n.orderId === shopperOrder.id) || {};
    truthy('[+] the notice names the product, the lot and the remedies', mine.status === 'ISSUED' && String(mine.lines[0].productName).startsWith('Crunchy peanut butter') && mine.lines[0].batchNo === 'L-2291' && mine.remedies.length === 2, mine);
    const guest = notices.find((n) => n.orderId === guestOrder.id) || {};
    truthy('[+] the walk-in is identified by the number they left, which is never shown', guest.status === 'ISSUED' && guest.buyerIdentified === true && guest.buyerPhone === undefined, guest);
    const walkIn = notices.find((n) => n.orderId === tillOrder.id) || {};
    truthy('[+] the walk-in is kept, unidentified', walkIn.status === 'UNIDENTIFIED' && walkIn.buyerIdentified === false, walkIn);

    // The shopper's own view, and their written notice.
    const own = call('GET', '/api/order-svc/orders/recall-notices/mine', { token: shopper.token, ...shop });
    expect(own, '[+] the shopper sees the recall on their orders', 200);
    truthy('[+] ...their notice, no one else\'s', (data(own) || []).length === 1 && data(own)[0].orderId === shopperOrder.id, data(own));
    truthy('[-] a bystander sees none', (data(call('GET', '/api/order-svc/orders/recall-notices/mine', { token: bystander.token, ...shop })) || []).length === 0);
    expect(call('GET', '/api/order-svc/orders/recall-notices/mine', { ...shop }), '[-] nobody reads one without a token', 401);
    let log = [];
    truthy(
      '[+] the shopper was written to, once',
      poll(60, () => {
        log = data(call('GET', `/api/notification-svc/admin/notifications?recipient=${encodeURIComponent(shopper.email)}`, { token: owner })) || [];
        return log.some((m) => m.type === 'RECALL_NOTICE');
      }) >= 0 && log.filter((m) => m.type === 'RECALL_NOTICE').length === 1,
      log.map((m) => m.type)
    );
    const written = log.find((m) => m.type === 'RECALL_NOTICE') || {};
    truthy('[+] ...with the headline, the lot, what to do and the choice', (written.body || '').startsWith('PRODUCT SAFETY RECALL') && /lot L-2291/.test(written.body) && /Stop using this product immediately/.test(written.body) && /a refund or a replacement/.test(written.body) && /0800 100 200/.test(written.body), written.body);
    truthy('[+] ...and nothing that plays the risk down', !/precautionary|voluntary/i.test(written.body || ''));
    truthy('[+] the recall itself shows how far it reached', data(call('GET', `/api/inventory-svc/admin/inventory/recalls/${recall.id}`, { token: cashier.token })).ordersAffected === 3);
  });

  // ── the remedy ────────────────────────────────────────────────────────────────
  group('4. the buyer chooses, staff settle', () => {
    const mine = notices.find((n) => n.orderId === shopperOrder.id);
    const guest = notices.find((n) => n.orderId === guestOrder.id);
    const walkIn = notices.find((n) => n.orderId === tillOrder.id);
    const choose = (id, remedy, opts) => call('POST', `/api/order-svc/orders/recall-notices/${id}/remedy`, { body: { remedy }, ...opts });
    expect(choose(mine.id, 'REPAIR', { token: shopper.token, ...shop }), '[-] a remedy the recall did not offer', 409, 'RECALL_REMEDY_NOT_OFFERED');
    expect(choose(mine.id, 'CASH', { token: shopper.token, ...shop }), '[-] or one the law does not name', 400);
    expect(choose(mine.id, 'REFUND', { token: bystander.token, ...shop }), "[-] a bystander cannot choose on another's notice", 404);
    expect(choose(mine.id, 'REFUND', { ...shop }), '[-] nor nobody', 401);
    expect(choose(mine.id, 'REFUND', { token: shopper.token, storefront: rival.tenantId }), '[-] nor the shopper at another shop', 404);
    const chosen = choose(mine.id, 'REFUND', { token: shopper.token, ...shop });
    expect(chosen, '[+] the shopper chooses a refund', 200);
    truthy('[+] ...recorded as their own choice', data(chosen).status === 'REMEDY_CHOSEN' && data(chosen).remedy === 'REFUND' && data(chosen).remedyChosenVia === 'SHOPPER', data(chosen));
    expect(choose(mine.id, 'REPLACEMENT', { token: shopper.token, ...shop }), '[-] and cannot change their mind on the record', 409, 'RECALL_REMEDY_ALREADY_CHOSEN');

    // Staff: the refund is a return of the goods, naming the notice.
    expect(call('GET', `/api/order-svc/orders/recall-notices?recallId=${recall.id}`, { token: shopper.token, ...shop }), "[-] a shopper reads no recall's list", 403);
    expect(call('POST', `/api/order-svc/orders/recall-notices/${mine.id}/resolve`, { token: shopper.token, ...shop, body: { resolution: 'DECLINED' } }), '[-] nor settles one', 403);
    expect(call('POST', `/api/order-svc/orders/recall-notices/${mine.id}/resolve`, { token: cashier.token, body: { resolution: 'REFUNDED' } }), '[-] a refund is not settled by hand', 400, 'RECALL_REFUND_THROUGH_RETURN');
    expect(
      call('POST', `/api/order-svc/orders/${guestOrder.id}/returns`, { token: cashier.token, body: { reason: 'Recall', recallNoticeId: mine.id, items: [{ variantId, qty: 1 }] } }),
      "[-] a return cannot settle another order's notice",
      409,
      'RECALL_NOTICE_ORDER_MISMATCH'
    );
    truthy('[+] ...and recorded no return', (data(call('GET', `/api/order-svc/orders/${guestOrder.id}/returns`, { token: cashier.token })) || []).length === 0);
    const refund = call('POST', `/api/order-svc/orders/${shopperOrder.id}/returns`, { token: cashier.token, body: { reason: 'Product safety recall', recallNoticeId: mine.id, items: [{ variantId, qty: 1 }] } });
    expect(refund, '[+] the cashier takes a jar back and refunds', 201);
    const settled = (data(call('GET', `/api/order-svc/orders/recall-notices?recallId=${recall.id}`, { token: cashier.token })) || []).find((n) => n.id === mine.id) || {};
    truthy('[+] the notice is settled by that return', settled.status === 'RESOLVED' && settled.resolution === 'REFUNDED' && settled.returnId === data(refund).id, settled);
    expect(
      call('POST', `/api/order-svc/orders/${shopperOrder.id}/returns`, { token: cashier.token, body: { reason: 'Again', recallNoticeId: mine.id, items: [{ variantId, qty: 1 }] } }),
      '[-] a settled notice is not settled twice',
      409,
      'RECALL_NOTICE_RESOLVED'
    );
    const heldOnArrival = () => (data(call('GET', `/api/inventory-svc/admin/inventory/recalls/${recall.id}`, { token: cashier.token })).batches || []).find((b) => b.quarantinedOn === 'ARRIVAL');
    truthy('[+] the returned jar is held under the recall as it arrives, not back on sale', poll(60, () => !!heldOnArrival()) >= 0 && Number(heldOnArrival().remainingQty) === 1, heldOnArrival());

    // Twenty staff at once recording the choice of the walk-in who left a number: one is recorded.
    const race = http.batch(
      Array.from({ length: 20 }, (_, i) => [
        'POST',
        `${BASE}/api/order-svc/orders/recall-notices/${guest.id}/remedy`,
        JSON.stringify({ remedy: i % 2 ? 'REFUND' : 'REPLACEMENT' }),
        { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${cashier.token}` }, tags: { name: 'POST /api/order-svc/orders/recall-notices/{id}/remedy (race)' } },
      ])
    );
    truthy('[+] twenty choices at once: one recorded, nineteen refused', race.filter((r) => r.status === 200).length === 1 && race.filter((r) => r.status === 409 && errorCode(r) === 'RECALL_REMEDY_ALREADY_CHOSEN').length === 19, race.map((r) => r.status).join(','));
    const replaced = call('POST', `/api/order-svc/orders/recall-notices/${guest.id}/resolve`, { token: cashier.token, body: { resolution: 'REPLACED', notes: 'New jar handed over' } });
    expect(replaced, '[+] the walk-in who left a number took a new jar', 200);
    truthy('[+] ...settled with its notes', data(replaced).status === 'RESOLVED' && data(replaced).resolutionNotes === 'New jar handed over', data(replaced));
    expect(call('POST', `/api/order-svc/orders/recall-notices/${walkIn.id}/resolve`, { token: cashier.token, body: { resolution: 'DECLINED' } }), '[+] the walk-in came back and wanted nothing', 200);
    expect(call('POST', `/api/order-svc/orders/recall-notices/${walkIn.id}/resolve`, { token: cashier.token, body: { resolution: 'DECLINED' } }), '[-] not twice', 409, 'RECALL_NOTICE_RESOLVED');
    expect(call('POST', `/api/order-svc/orders/recall-notices/${walkIn.id}/resolve`, { token: cashier.token, body: { resolution: 'LOST' } }), '[-] nor with a word the record does not take', 400);
    expect(call('POST', `/api/order-svc/orders/recall-notices/${walkIn.id}/resolve`, { token: rival.owner.token, body: { resolution: 'DECLINED' } }), "[-] another business settles nothing of ours", 404);
    truthy("[-] another business sees none of our notices", (data(call('GET', `/api/order-svc/orders/recall-notices?recallId=${recall.id}`, { token: rival.owner.token })) || []).length === 0);
    const p = data(call('GET', `/api/order-svc/orders/recall-notices/progress?recallId=${recall.id}`, { token: manager.token }));
    truthy('[+] every buyer settled, and settling the walk-in did not make them a buyer told', p.notices === 3 && p.resolved === 3 && p.remedyChosen === 2 && p.chosen.REFUND + p.chosen.REPLACEMENT === 2 && p.identified === 2 && p.unidentified === 1, p);
  });
}
