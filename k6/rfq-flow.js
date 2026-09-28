// RFQ and sourcing through the gateway: a request for two lines to three suppliers (a cashier
// refused; nothing to quote for refused), issued, a quote before issue refused; two quotes — one in
// euros against a rate the business keeps — and a decline; the comparison in pounds with the lowest
// per line marked, the totals at home and ranked, each supplier's grade beside the bid; the award of
// each line to its lowest raising one DRAFT order per supplier in their currency at the quoted price
// for the day the goods are needed; awarded once; a second request cancelled with a reason.
//
//   k6/run.sh rfq-flow
import {
  ALL_CHECKS_PASS,
  call,
  data,
  expect,
  must,
  onboardTenant,
  sellableVariant,
  staffUser,
  truthy,
  uniq,
} from './lib/storeql.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS, setupTimeout: '4m' };

const P = '/api/purchase-svc';

export function setup() {
  const tenant = onboardTenant('rfq');
  const a = sellableVariant(tenant, 'Rib of beef per kg').variantId;
  const b = sellableVariant(tenant, 'Lamb shoulder per kg').variantId;
  const cashier = staffUser(tenant, 'CASHIER', [tenant.stores[0].id]);
  const storekeeper = staffUser(tenant, 'STOREKEEPER', [tenant.stores[0].id]);
  return { tenant, a, b, cashier, storekeeper };
}

export default function ({ tenant, a, b, cashier, storekeeper }) {
  const owner = tenant.owner.token;
  const store = tenant.stores[0];
  const num = (v) => Number(v || 0);
  const plusDays = (n) => new Date(Date.now() + n * 86400000).toISOString().slice(0, 10);
  const find = (rows, field, value) => (rows || []).find((r) => r[field] === value) || {};

  // ── 1. the suppliers, the rate, the request ────────────────────────────────
  const s1 = must(call('POST', `${P}/suppliers`, { token: owner, body: { name: `Highland Meats ${uniq()}`, vatRegistered: true, currency: 'GBP', leadTimeDays: 3 } }), 201, 'a pound supplier');
  const s2 = must(call('POST', `${P}/suppliers`, { token: owner, body: { name: `Boucherie Nord ${uniq()}`, vatRegistered: true, currency: 'EUR' } }), 201, 'a euro supplier');
  const s3 = must(call('POST', `${P}/suppliers`, { token: owner, body: { name: `Quiet Farm ${uniq()}`, vatRegistered: true, currency: 'GBP' } }), 201, 'a third');
  must(call('PUT', '/api/tenant-svc/admin/tenant/fx-rates/eur', { token: owner, body: { rate: 0.85, reason: 'ECB reference' } }), 200, 'a euro rate');
  const body = { title: 'Autumn beef', storeId: store.id, neededBy: plusDays(14), lines: [{ variantId: a, qty: 10 }, { variantId: b, qty: 5 }], supplierIds: [s1.id, s2.id, s3.id] };
  expect(call('POST', `${P}/rfqs`, { token: cashier.token, body }), '[-] a cashier raises no request', 403);
  expect(call('POST', `${P}/rfqs`, { token: owner, body: Object.assign({}, body, { lines: [] }) }), '[-] nothing to quote for', 400, 'PURCHASE_RFQ_LINES_REQUIRED');
  const rfq = must(call('POST', `${P}/rfqs`, { token: storekeeper.token, body }), 201, 'a request');
  truthy('[+] raised as a numbered draft with its lines and the suppliers invited', /^RFQ-\d{6}$/.test(rfq.reference) && rfq.status === 'DRAFT' && rfq.lines.length === 2 && rfq.bids.length === 3 && rfq.bids.every((x) => x.status === 'INVITED'), rfq);
  const quote1 = { currency: 'GBP', leadTimeDays: 4, lines: [{ variantId: a, unitPrice: '10.00' }, { variantId: b, unitPrice: '4.00' }] };
  expect(call('PUT', `${P}/rfqs/${rfq.id}/quotes/${s1.id}`, { token: owner, body: quote1 }), '[-] no quote before the request goes out', 409, 'PURCHASE_RFQ_NOT_ISSUED');
  const issued = must(call('POST', `${P}/rfqs/${rfq.id}/issue`, { token: owner, body: {} }), 200, 'issued');
  truthy('[+] issued, with the moment it went out', issued.status === 'ISSUED' && !!issued.issuedAt, issued);

  // ── 2. quotes, a decline, the comparison at home ───────────────────────────
  must(call('PUT', `${P}/rfqs/${rfq.id}/quotes/${s1.id}`, { token: owner, body: quote1 }), 200, 'the pound quote');
  must(call('PUT', `${P}/rfqs/${rfq.id}/quotes/${s2.id}`, { token: owner, body: { currency: 'EUR', leadTimeDays: 6, lines: [{ variantId: a, unitPrice: '11.00' }, { variantId: b, unitPrice: '5.00' }] } }), 200, 'the euro quote');
  const compared = must(call('POST', `${P}/rfqs/${rfq.id}/quotes/${s3.id}/decline`, { token: owner, body: {} }), 200, 'the third declines');
  truthy('[+] two quoted and one declined, each bid carrying a grade slot', find(compared.bids, 'supplierId', s3.id).status === 'DECLINED' && find(compared.bids, 'supplierId', s1.id).status === 'QUOTED' && find(compared.bids, 'supplierId', s1.id).prices.length === 2, compared.bids);
  const cmp = compared.comparison || {};
  const lineA = find(cmp.lines, 'variantId', a);
  const lineB = find(cmp.lines, 'variantId', b);
  truthy('[+] compared in pounds: the euro quote wins the rib at 9.35, the pound quote the lamb at 4.00', cmp.homeCurrency === 'GBP' && num(find(lineA.prices, 'supplierId', s2.id).homeUnitPrice) === 9.35 && find(lineA.prices, 'supplierId', s2.id).lowest === true && find(lineB.prices, 'supplierId', s1.id).lowest === true, cmp);
  const sum2 = find(cmp.bids, 'supplierId', s2.id);
  const sum1 = find(cmp.bids, 'supplierId', s1.id);
  truthy('[+] totals at home ranked: 114.75 first, 120.00 second, the decliner unranked', num(sum2.homeTotal) === 114.75 && sum2.rank === 1 && num(sum1.homeTotal) === 120 && sum1.rank === 2 && find(cmp.bids, 'supplierId', s3.id).rank === undefined, cmp.bids);
  expect(call('PUT', `${P}/rfqs/${rfq.id}/quotes/${s1.id}`, { token: owner, body: { currency: 'GBP', lines: [] } }), '[-] a quote with nothing on it', 400, 'PURCHASE_RFQ_QUOTE_EMPTY');
  const listed = data(call('GET', `${P}/rfqs?status=ISSUED`, { token: owner })) || [];
  truthy('[+] listed as issued with two of three quoted', find(listed, 'id', rfq.id).quotes === 2 && find(listed, 'id', rfq.id).suppliers === 3, listed);

  // ── 3. the award: a draft order per supplier at the quoted price ──────────
  expect(call('POST', `${P}/rfqs/${rfq.id}/award`, { token: owner, body: { awards: [{ variantId: a, supplierId: s3.id }] } }), '[-] a line goes only to a supplier who priced it', 409, 'PURCHASE_RFQ_NOT_QUOTED');
  const awarded = must(call('POST', `${P}/rfqs/${rfq.id}/award`, { token: storekeeper.token, body: { awards: [{ variantId: a, supplierId: s2.id }, { variantId: b, supplierId: s1.id }] } }), 200, 'awarded');
  truthy('[+] awarded: two lines, two draft orders', awarded.status === 'AWARDED' && awarded.awards.length === 2 && awarded.purchaseOrderIds.length === 2, awarded);
  const awardA = find(awarded.awards, 'variantId', a);
  const po2 = data(call('GET', `${P}/purchase-orders/${awardA.poId}`, { token: owner })) || {};
  const lines2 = data(call('GET', `${P}/purchase-orders/${awardA.poId}/lines`, { token: owner })) || [];
  truthy('[+] the euro order is a DRAFT from the RFQ, in euros, ten at 11.00, for the day the goods are needed', po2.status === 'DRAFT' && po2.source === 'RFQ' && po2.currency === 'EUR' && po2.supplierId === s2.id && po2.expectedDelivery === plusDays(14) && lines2.length === 1 && num(lines2[0].unitPrice) === 11 && num(lines2[0].qty) === 10, { po2, lines2 });
  const awardB = find(awarded.awards, 'variantId', b);
  const po1 = data(call('GET', `${P}/purchase-orders/${awardB.poId}`, { token: owner })) || {};
  truthy('[+] the pound order carries the lamb at 4.00: net 20.00', po1.currency === 'GBP' && num(po1.totalNet) === 20, po1);
  expect(call('POST', `${P}/rfqs/${rfq.id}/award`, { token: owner, body: { awards: [{ variantId: a, supplierId: s1.id }] } }), '[-] awarded once', 409, 'PURCHASE_RFQ_NOT_ISSUED');
  expect(call('POST', `${P}/rfqs/${rfq.id}/cancel`, { token: owner, body: { reason: 'too late' } }), '[-] not cancelled once awarded', 409, 'PURCHASE_RFQ_CLOSED');

  // ── 4. another request, cancelled with a reason ────────────────────────────
  const second = must(call('POST', `${P}/rfqs`, { token: owner, body: Object.assign({}, body, { title: 'Winter lamb' }) }), 201, 'a second request');
  const cancelled = must(call('POST', `${P}/rfqs/${second.id}/cancel`, { token: owner, body: { reason: 'the range was dropped' } }), 200, 'cancelled');
  truthy('[+] cancelled with its reason, and numbered after the first', cancelled.status === 'CANCELLED' && cancelled.cancelledReason === 'the range was dropped' && cancelled.reference > rfq.reference, cancelled);
  expect(call('POST', `${P}/rfqs/${second.id}/issue`, { token: owner, body: {} }), '[-] a cancelled request is not issued', 409, 'PURCHASE_RFQ_NOT_DRAFT');
}
