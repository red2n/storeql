// Sales and tender posting to revenue and control accounts (17.7), through the gateway: a till sale
// paid part in cash and part by card reaches purchase-svc's ledger as tender postings against a
// receipts clearing account and a sale posting that clears it into sales and VAT output; a card
// refund takes revenue and VAT back and credits card clearing; money taken for a sale that was
// never confirmed stays on the clearing report until it is refunded — and the refusals and the
// abuse around it: the wrong roles, a rival tenant, a bad store, ten payments on one key.
//
//   k6/run.sh sales-posting
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
} from './lib/storeql.js';

const completed = new Counter('flow_completed');
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
  setupTimeout: '4m',
};

const LEDGER = '/api/purchase-svc/nominal-ledger';

export function setup() {
  return sellingTenant('sales-post', { price: '12.00', costPrice: '6.00' });
}

export default function ({ tenant, rival, store, variantId, storekeeper, cashier }) {
  const owner = tenant.owner.token;
  const num = (v) => Number(v || 0);
  const round = (v) => Math.round(v * 100) / 100;
  const linesFor = (orderId, token = owner) => {
    const all = data(call('GET', `${LEDGER}?limit=100`, { token }));
    return (Array.isArray(all) ? all : []).filter((l) => l.sourceRef === orderId);
  };
  const net = (lines, code) => round(lines.filter((l) => l.nominalCode === code).reduce((t, l) => t + num(l.debit) - num(l.credit), 0));
  const open = (token = owner, query = '') => data(call('GET', `${LEDGER}/sales-clearing${query}`, { token }));
  const placeSale = (qty) => must(call('POST', '/api/order-svc/orders', { token: owner, idem: true, body: { storeId: store.id, channel: 'POS', fulfilmentType: 'INSTORE', items: [{ variantId, qty }] } }), 201, 'till sale');
  const pay = (orderId, amount, method, idem = true) => call('POST', '/api/payment-svc/payments', { token: owner, idem, body: { orderId, amount, method, storeId: store.id } });

  // ── a sale paid in cash and card ─────────────────────────────────────────────
  const sale = placeSale(3);
  const total = num(sale.total);
  const cash = round(total - 10);
  const cashTender = data(pay(sale.id, cash.toFixed(2), 'CASH'));
  const cardTender = must(pay(sale.id, '10.00', 'CARD'), [200, 201], 'card tender');
  truthy('[+] the sale is paid in two tenders', cashTender.id && cardTender.id, { cashTender, cardTender });

  let posted = [];
  const seconds = poll(60, () => {
    posted = linesFor(sale.id);
    return posted.filter((l) => l.sourceType === 'SALE').length >= 2 && posted.filter((l) => l.sourceType === 'SALE_TENDER').length === 4;
  });
  truthy('[+] the sale and both tenders reach the ledger', seconds >= 0, posted.map((l) => `${l.sourceType} ${l.nominalCode} ${l.debit}/${l.credit}`));
  truthy('[+] cash is held in the tills and card in card clearing', net(posted, '1210') === cash && net(posted, '1250') === 10, { cash: net(posted, '1210'), card: net(posted, '1250') });
  truthy('[+] sales plus VAT output is the sale total', round(-net(posted, '4010') - net(posted, '2200')) === total, { sales: net(posted, '4010'), vat: net(posted, '2200'), total });
  truthy('[+] receipts clearing nets to zero for the sale', net(posted, '1105') === 0, net(posted, '1105'));
  truthy('[+] every posting names the sale\'s store', posted.every((l) => l.storeId === store.id));
  const openNow = open();
  truthy('[+] a sale paid in full is not on the clearing report', Array.isArray(openNow) && !openNow.some((o) => o.orderId === sale.id), openNow);
  truthy('[+] the trial balance still balances', data(call('GET', `${LEDGER}/trial-balance`, { token: owner })).balanced === true);

  // ── a card refund ────────────────────────────────────────────────────────────
  expect(call('POST', `/api/payment-svc/payments/by-order/${sale.id}/refunds`, { token: owner, idem: true, body: { paymentId: cardTender.id, amount: '4.00', method: 'CARD', reason: 'price match' } }), '[+] part of the card payment is refunded', [200, 201]);
  let refundLines = [];
  truthy('[+] the refund reaches the ledger', poll(60, () => {
    refundLines = linesFor(sale.id).filter((l) => l.sourceType === 'SALE_REFUND');
    return refundLines.length >= 2;
  }) >= 0, refundLines);
  truthy('[+] ...crediting card clearing with what was refunded', net(refundLines, '1250') === -4, refundLines);
  truthy('[+] ...and taking the same back from sales and VAT', round(net(refundLines, '4010') + net(refundLines, '2200')) === 4, refundLines);

  // ── money taken for a sale never confirmed ───────────────────────────────────
  const partial = placeSale(2);
  const partTender = must(pay(partial.id, '5.00', 'CASH'), [200, 201], 'part payment');
  let listed = [];
  truthy('[+] a part-paid sale shows on the clearing report', poll(60, () => {
    listed = open();
    return Array.isArray(listed) && listed.some((o) => o.orderId === partial.id && num(o.balance) === -5);
  }) >= 0, listed);
  truthy('[+] ...and on its store\'s report', (open(owner, `?storeId=${store.id}`) || []).some((o) => o.orderId === partial.id));
  expect(call('POST', `/api/payment-svc/payments/by-order/${partial.id}/refunds`, { token: owner, idem: true, body: { paymentId: partTender.id, amount: '5.00', method: 'CASH', reason: 'walked out' } }), '[+] the part payment is refunded', [200, 201]);
  truthy('[+] ...and the order leaves the clearing report', poll(60, () => !(open() || []).some((o) => o.orderId === partial.id)) >= 0);

  // ── refusals ─────────────────────────────────────────────────────────────────
  expect(call('GET', `${LEDGER}/sales-clearing`, { token: storekeeper.token }), '[-] a storekeeper cannot read the clearing report', 403, 'FORBIDDEN');
  expect(call('GET', `${LEDGER}/sales-clearing`, { token: cashier.token }), '[-] nor a cashier', 403, 'FORBIDDEN');
  expect(call('GET', `${LEDGER}/sales-clearing?storeId=not-a-store`, { token: owner }), '[-] a store that is not an id is refused', 400, 'INVALID_UUID');
  truthy('[-] a rival tenant\'s ledger has none of this sale', linesFor(sale.id, rival.owner.token).length === 0);
  truthy('[-] ...nor its clearing report', (open(rival.owner.token) || []).length === 0);

  // ── abuse: ten payments on one key are one tender, posted once ───────────────
  const keyed = placeSale(1);
  const key = newId();
  const tries = Array.from({ length: 10 }, () => pay(keyed.id, '3.00', 'CARD', key));
  truthy('[abuse] ten payments on one idempotency key answer as one', tries.every((r) => [200, 201].includes(r.status)) && new Set(tries.map((r) => data(r).id)).size === 1, tries.map((r) => r.status));
  let keyedLines = [];
  poll(60, () => {
    keyedLines = linesFor(keyed.id).filter((l) => l.sourceType === 'SALE_TENDER');
    return keyedLines.length >= 2;
  });
  truthy('[abuse] ...and the ledger holds one tender posting for them', keyedLines.length === 2 && net(keyedLines, '1250') === 3, keyedLines);

  completed.add(1);
}
