// Fresh yield, preparation and butchery loss through the gateway: a yield template (management;
// nothing to yield, more than the whole, the primal as its own cut and a cashier refused), a side
// received at cost, a breakdown recorded by a storekeeper — the primal gone, each cut a batch of
// its own under the primal's lot at its apportioned cost, the sirloin dated by its own shelf life
// and the mince by the side's, the loss against what was expected and at the primal's cost; the
// report adding the period up; more out than in, a cut nobody named, more primal than is on the
// shelf and an ended template refused.
//
//   k6/run.sh yield-flow
import {
  ALL_CHECKS_PASS,
  call,
  data,
  expect,
  must,
  onboardTenant,
  receive,
  sellableVariant,
  staffUser,
  truthy,
} from './lib/storeql.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS, setupTimeout: '4m' };

const I = '/api/inventory-svc';
const Y = `${I}/admin/inventory/yield`;

export function setup() {
  const tenant = onboardTenant('yield');
  const side = sellableVariant(tenant, 'Side of beef').variantId;
  const sirloin = sellableVariant(tenant, 'Sirloin steak per kg').variantId;
  const mince = sellableVariant(tenant, 'Beef mince per kg').variantId;
  const bones = sellableVariant(tenant, 'Marrow bones').variantId;
  const storekeeper = staffUser(tenant, 'STOREKEEPER', [tenant.stores[0].id]);
  const cashier = staffUser(tenant, 'CASHIER', [tenant.stores[0].id]);
  return { tenant, side, sirloin, mince, bones, storekeeper, cashier };
}

export default function ({ tenant, side, sirloin, mince, bones, storekeeper, cashier }) {
  const owner = tenant.owner.token;
  const store = tenant.stores[0];
  const num = (v) => Number(v || 0);
  const today = new Date().toISOString().slice(0, 10);
  const plusDays = (n) => new Date(Date.now() + n * 86400000).toISOString().slice(0, 10);
  const level = (variantId) => (data(call('GET', `${I}/admin/inventory/levels?store=${store.id}`, { token: owner })) || []).find((l) => l.variantId === variantId) || {};
  const batches = (variantId) => data(call('GET', `${I}/admin/inventory/batches?store=${store.id}&variant=${variantId}`, { token: owner })) || [];

  // ── 1. the template: what a side should yield ─────────────────────────────
  const templateBody = {
    name: 'Side of beef',
    inputVariantId: side,
    unit: 'kg',
    outputs: [
      { variantId: sirloin, expectedPct: 35, costShare: 60, shelfLifeDays: 5 },
      { variantId: mince, expectedPct: 45, costShare: 40 },
    ],
  };
  const template = must(call('POST', `${Y}/templates`, { token: owner, body: templateBody }), 201, 'a yield template');
  truthy('[+] the template expects a fifth of the side to be lost', num(template.expectedLossPct) === 20 && template.outputs.length === 2 && template.active === true, template);
  truthy('[+] each cut keeps the cost share and shelf life it was given', num(template.outputs.find((o) => o.variantId === mince).costShare) === 40 && template.outputs.find((o) => o.variantId === sirloin).shelfLifeDays === 5, template.outputs);
  expect(call('POST', `${Y}/templates`, { token: owner, body: { name: 'Empty', inputVariantId: side, outputs: [] } }), '[-] a template with nothing to yield', 400, 'INVENTORY_YIELD_OUTPUTS_REQUIRED');
  expect(call('POST', `${Y}/templates`, { token: owner, body: { name: 'Too much', inputVariantId: side, outputs: [{ variantId: sirloin, expectedPct: 70 }, { variantId: mince, expectedPct: 40 }] } }), '[-] cuts adding up to more than the whole', 400, 'INVENTORY_YIELD_SHARES_INVALID');
  expect(call('POST', `${Y}/templates`, { token: owner, body: { name: 'Circular', inputVariantId: side, outputs: [{ variantId: side, expectedPct: 90 }] } }), '[-] the primal as its own cut', 400, 'INVENTORY_YIELD_OUTPUT_IS_INPUT');
  expect(call('POST', `${Y}/templates`, { token: cashier.token, body: templateBody }), '[-] a cashier defines no yield', 403);
  truthy('[+] the template is listed', (data(call('GET', `${Y}/templates`, { token: owner })) || []).some((t) => t.id === template.id), null);

  // ── 2. the side received, the breakdown recorded ──────────────────────────
  const received = must(receive(tenant, store.id, side, 100, '5.00'), 201, 'a side of 100 at 5.00');
  const run = must(call('POST', `${Y}/runs`, { token: storekeeper.token, body: { storeId: store.id, templateId: template.id, inputQty: 100, outputs: [{ variantId: sirloin, qty: 34 }, { variantId: mince, qty: 44 }], reference: 'Monday side' } }), 201, 'a breakdown');
  truthy('[+] 100 in at 500.00, 78 out, 22 lost against 20 expected: 2 over, 110.00 at cost', num(run.inputCost) === 500 && num(run.outputQty) === 78 && num(run.lossQty) === 22 && num(run.expectedLossQty) === 20 && num(run.lossVariance) === 2 && num(run.lossAtCost) === 110 && num(run.lossPct) === 22, run);
  const sirloinOut = (run.outputs || []).find((o) => o.variantId === sirloin) || {};
  const minceOut = (run.outputs || []).find((o) => o.variantId === mince) || {};
  truthy('[+] the cuts carry the side\'s cost by share: sirloin 8.82, mince 4.55 a unit', num(sirloinOut.unitCost) === 8.82 && num(minceOut.unitCost) === 4.55 && num(sirloinOut.expectedQty) === 35, run.outputs);
  truthy('[+] the side is gone and the cuts are on the shelf', num(level(side).onHand) === 0 && num(level(sirloin).available) === 34 && num(level(mince).available) === 44, { side: level(side), sirloin: level(sirloin), mince: level(mince) });
  const sirloinBatch = batches(sirloin)[0] || {};
  const minceBatch = batches(mince)[0] || {};
  truthy('[+] each cut is a batch under the side\'s lot, the sirloin dated by its own shelf life and the mince by the side\'s', sirloinBatch.batchNo === received.batchNo && sirloinBatch.id === sirloinOut.batchId && sirloinBatch.expiryDate === plusDays(5) && minceBatch.batchNo === received.batchNo && (minceBatch.expiryDate || null) === (received.expiryDate || null), { sirloinBatch, minceBatch, received });
  const descendants = data(call('GET', `${I}/admin/inventory/lot-genealogy/batch/${received.id}/descendants`, { token: owner })) || {};
  truthy('[+] the genealogy knows the cuts came from the side', JSON.stringify(descendants).includes(sirloinBatch.id) && JSON.stringify(descendants).includes(minceBatch.id), descendants);

  // ── 3. the report ─────────────────────────────────────────────────────────
  const report = data(call('GET', `${Y}/runs?storeId=${store.id}&from=2026-01-01&to=${today}`, { token: owner })) || {};
  truthy('[+] the period adds up: one run, 22 of 100 lost against 20, 110.00 at cost', (report.runs || []).length === 1 && report.totals && num(report.totals.lossQty) === 22 && num(report.totals.expectedLossQty) === 20 && num(report.totals.lossAtCost) === 110 && num(report.totals.inputQty) === 100, report.totals);
  truthy('[+] the run reads back with its reference', report.runs[0].reference === 'Monday side' && report.runs[0].templateName === 'Side of beef', report.runs[0]);

  // ── 4. refused by name ────────────────────────────────────────────────────
  must(receive(tenant, store.id, side, 50, '5.00'), 201, 'another 50');
  expect(call('POST', `${Y}/runs`, { token: owner, body: { storeId: store.id, templateId: template.id, inputQty: 50, outputs: [{ variantId: sirloin, qty: 30 }, { variantId: mince, qty: 25 }] } }), '[-] more out than in', 400, 'INVENTORY_YIELD_OUTPUT_EXCEEDS_INPUT');
  expect(call('POST', `${Y}/runs`, { token: owner, body: { storeId: store.id, templateId: template.id, inputQty: 50, outputs: [{ variantId: bones, qty: 5 }] } }), '[-] a cut the template never named', 400, 'INVENTORY_YIELD_OUTPUT_UNKNOWN');
  expect(call('POST', `${Y}/runs`, { token: owner, body: { storeId: store.id, templateId: template.id, inputQty: 80, outputs: [{ variantId: sirloin, qty: 28 }, { variantId: mince, qty: 36 }] } }), '[-] more primal than is on the shelf', 422, 'INVENTORY_YIELD_INSUFFICIENT_INPUT');
  truthy('[+] nothing was drawn by the refusals', num(level(side).onHand) === 50, level(side));
  must(call('POST', `${Y}/templates/${template.id}/end`, { token: owner, body: {} }), 200, 'template ended');
  expect(call('POST', `${Y}/runs`, { token: owner, body: { storeId: store.id, templateId: template.id, inputQty: 50, outputs: [{ variantId: sirloin, qty: 17 }, { variantId: mince, qty: 22 }] } }), '[-] an ended template breaks nothing more', 409, 'INVENTORY_YIELD_TEMPLATE_ENDED');
  expect(call('POST', `${Y}/templates/${template.id}/end`, { token: owner, body: {} }), '[-] ended twice is not found live', 404, 'INVENTORY_YIELD_TEMPLATE_NOT_FOUND');
}
