// Supplier payment runs and remittance (17.10), through the gateway: suppliers carry bank details
// and a remittance email; a manager proposes a run from what is due, offsetting a supplier's credit
// note and leaving out the supplier with no bank details; the owner approves it; the bank file is
// downloaded; the run is paid, which settles the invoices and posts Dr Creditors / Cr Bank; the
// supplier's remittance advice reaches notification-svc — and the refusals and the abuse around
// each: the wrong roles, the wrong tenant, the proposer approving their own run, a storekeeper
// setting bank details, bank details changed after approval, twenty payments at once and ten
// proposals racing for the same invoices.
//
//   k6/run.sh payment-run-flow
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import {
  ALL_CHECKS_PASS,
  BASE,
  businessWideManager,
  call,
  data,
  errorCode,
  expect,
  must,
  onboardTenant,
  poll,
  sellableVariant,
  staffUser,
  truthy,
} from './lib/storeql.js';

// A script exception ends the iteration with every check run so far green; the counter is only
// added on the last line, so a flow that stopped part-way fails instead of passing on five checks.
const completed = new Counter('flow_completed');
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
  // The abuse cases send twenty requests at once; k6 would otherwise hold six per host back.
  batch: 20,
  batchPerHost: 20,
  setupTimeout: '4m',
};

const P = '/api/purchase-svc';
const RUNS = `${P}/payment-runs`;

export function setup() {
  const tenant = onboardTenant('payrun', { country: 'GB', currency: 'GBP' });
  const rival = onboardTenant('payrun-rival', { country: 'GB', currency: 'GBP' });
  const store = tenant.stores[0];
  const { variantId } = sellableVariant(tenant, 'Paid-for widget');
  // A run gathers every store's invoices and pays them from the business's accounts: the manager
  // who proposes is the whole business's; one held to a store is refused BUSINESS_WIDE_ONLY.
  const manager = businessWideManager(tenant);
  const branchManager = staffUser(tenant, 'MANAGER', [store.id]);
  const storekeeper = staffUser(tenant, 'STOREKEEPER', [store.id]);
  const cashier = staffUser(tenant, 'CASHIER', [store.id]);
  return { tenant, rival, store, variantId, manager, branchManager, storekeeper, cashier };
}

export default function ({ tenant, rival, store, variantId, manager, branchManager, storekeeper, cashier }) {
  const owner = tenant.owner.token;
  const today = new Date().toISOString().slice(0, 10);
  const plusDays = (iso, n) => new Date(Date.parse(`${iso}T00:00:00Z`) + n * 86400000).toISOString().slice(0, 10);
  const num = (v) => Number(v || 0);
  const stamp = Date.now();
  const ukBank = { bankAccountName: 'Acme Ltd', bankSortCode: '12-34-56', bankAccountNumber: '31415926' };
  const deBank = { bankAccountName: 'Muster GmbH', bankIban: 'DE89 3704 0044 0532 0130 00', bankBic: 'DEUTDEFF' };

  const supplier = (name, extra = {}) =>
    must(call('POST', `${P}/suppliers`, { token: owner, body: { name: `${name} ${stamp}`, currency: 'GBP', paymentTermsDays: 30, ...extra } }), 201, `supplier ${name}`);
  // An order received in full and invoiced as ordered, dated so it is due by today.
  const dueInvoice = (sup, number, qty, price, invoiceDate = plusDays(today, -40)) => {
    const po = must(call('POST', `${P}/purchase-orders`, { token: owner, body: { supplierId: sup.id, storeId: store.id } }), 201, 'purchase order');
    must(call('POST', `${P}/purchase-orders/${po.id}/lines`, { token: owner, body: { variantId, qty, unitPrice: price } }), 201, 'a line');
    must(call('POST', `${P}/purchase-orders/${po.id}/submit`, { token: owner, body: {} }), 200, 'submit');
    must(call('POST', `${P}/goods-receipts`, { token: owner, idem: true, body: { poId: po.id, storeId: store.id, lines: [{ variantId, qtyReceived: qty }] } }), 201, 'goods receipt');
    const inv = must(call('POST', `${P}/supplier-invoices`, { token: owner, body: { poId: po.id, invoiceNumber: `${number}-${stamp}`, invoiceDate, vatAmount: '0', lines: [{ variantId, qty, unitPrice: price }] } }), 201, `invoice ${number}`);
    return { po, inv };
  };
  const propose = (token, body) => call('POST', RUNS, { token, body });
  const act = (token, id, action, body = {}) => call('POST', `${RUNS}/${id}/${action}`, { token, body });
  const ledgerFor = (runId) => {
    const lines = data(call('GET', `${P}/nominal-ledger?limit=100&from=${today}&to=${today}`, { token: owner }));
    return (Array.isArray(lines) ? lines : []).filter((l) => l.sourceType === 'SUPPLIER_PAYMENT' && l.sourceRef === runId);
  };

  // ── suppliers: bank details validated, gated and masked ──────────────────────
  expect(call('POST', `${P}/suppliers`, { token: owner, body: { name: `Bad Sort ${stamp}`, bankAccountName: 'Bad', bankSortCode: '12-AB-56', bankAccountNumber: '31415926' } }), '[-] a sort code that is not six digits is refused', 400, 'PURCHASE_BANK_DETAILS_INVALID');
  expect(call('POST', `${P}/suppliers`, { token: owner, body: { name: `Bad Iban ${stamp}`, bankAccountName: 'Bad', bankIban: 'GB82WEST12345698765433' } }), '[-] an IBAN whose check digits fail is refused', 400, 'PURCHASE_BANK_DETAILS_INVALID');
  expect(call('POST', `${P}/suppliers`, { token: storekeeper.token, body: { name: `Store Banked ${stamp}`, ...ukBank } }), '[-] a storekeeper cannot set where a supplier is paid', 403, 'PERMISSION_DENIED');
  const acmeRes = call('POST', `${P}/suppliers`, { token: owner, body: { name: `Acme Ltd ${stamp}`, currency: 'GBP', paymentTermsDays: 30, remittanceEmail: `ap-${stamp}@acme.example`, ...ukBank } });
  expect(acmeRes, '[+] the owner adds a supplier with bank details and a remittance email', 201);
  const acme = data(acmeRes);
  truthy('[+] ...and the account number comes back masked, never in full', acme.bankAccountNumberMasked === '****5926' && !String(acmeRes.body).includes('31415926') && acme.hasBankDetails === true, acme);
  const muster = supplier('Muster GmbH', deBank);
  const noBank = supplier('No Bank Ltd');

  // ── what is due ──────────────────────────────────────────────────────────────
  const a1 = dueInvoice(acme, 'INV-A1', 10, '3.00');
  const a2 = dueInvoice(acme, 'INV-A2', 4, '2.50');
  const b1 = dueInvoice(muster, 'INV-B1', 8, '2.50');
  dueInvoice(noBank, 'INV-C1', 4, '3.00');
  const notDue = dueInvoice(acme, 'INV-A3', 6, '2.50', today);
  // The receipt reaches inventory-svc's on-hand through GoodsReceived, so the return waits for it.
  let ret = null;
  let lastReturn = null;
  const stocked = poll(60, () => {
    lastReturn = call('POST', `${P}/vendor-returns`, { token: owner, idem: true, body: { poId: a1.po.id, reason: 'DAMAGED', lines: [{ variantId, qty: 2 }] } });
    if (lastReturn.status === 201) ret = data(lastReturn);
    return ret !== null;
  });
  if (ret === null) throw new Error(`return to vendor never accepted: ${lastReturn && lastReturn.status} ${lastReturn && String(lastReturn.body).slice(0, 300)}`);
  truthy('[+] the goods go back once the receipt has reached the stock', stocked >= 0);
  const credited = must(call('POST', `${P}/vendor-returns/${ret.id}/credit`, { token: owner, body: { creditNoteNumber: `CN-${stamp}`, creditNoteDate: today } }), 200, 'credit note');
  const acmeNet = num(a1.inv.grossAmount) + num(a2.inv.grossAmount) - num(credited.creditAmount);
  const expectedTotal = acmeNet + num(b1.inv.grossAmount);

  // ── propose ──────────────────────────────────────────────────────────────────
  expect(propose(storekeeper.token, { payUpTo: today, paymentDate: today }), '[-] a storekeeper cannot propose a payment run', 403, 'FORBIDDEN');
  expect(call('GET', RUNS, { token: cashier.token }), '[-] a cashier cannot list payment runs', 403, 'FORBIDDEN');
  expect(propose(branchManager.token, { payUpTo: today, paymentDate: today }), '[-] a manager held to one store cannot propose a run: it is the whole business\'s', 403, 'BUSINESS_WIDE_ONLY');
  expect(call('GET', RUNS, { token: branchManager.token }), '[-] ...nor list the runs', 403, 'BUSINESS_WIDE_ONLY');
  expect(propose(manager.token, { payUpTo: today, paymentDate: plusDays(today, -1) }), '[-] a payment date in the past is refused', 400, 'PURCHASE_PAYMENT_DATE_INVALID');
  expect(propose(manager.token, { payUpTo: plusDays(today, 800), paymentDate: today }), '[-] a run more than a year ahead is refused', 400, 'PURCHASE_PAYMENT_DATE_INVALID');
  expect(propose(manager.token, { payUpTo: today, paymentDate: today, currency: 'EUR' }), '[-] nothing is due in a currency nobody invoiced in', 409, 'PURCHASE_PAYMENT_RUN_NOTHING_DUE');

  const proposedRes = propose(manager.token, { payUpTo: today, paymentDate: today });
  expect(proposedRes, '[+] a manager proposes a run from what is due', 201);
  const run = data(proposedRes);
  const suppliers = run.suppliers || [];
  const acmeLine = suppliers.find((s) => s.supplierId === acme.id) || {};
  truthy('[+] the run pays two suppliers, offsetting the credit note against Acme', suppliers.length === 2 && Math.abs(num(acmeLine.net) - acmeNet) < 0.005 && (acmeLine.documents || []).length === 3, run);
  truthy('[+] ...totals what is due, and leaves out the invoice not yet due', Math.abs(num(run.total) - expectedTotal) < 0.005 && !JSON.stringify(run).includes(notDue.inv.id), run);
  truthy('[+] the supplier with no bank details is left out, with the reason', (run.excluded || []).some((e) => e.supplierId === noBank.id && e.reason === 'NO_BANK_DETAILS'), run.excluded);
  truthy('[+] bank details keyed minutes ago are flagged for a phone call', (acmeLine.warnings || []).includes('BANK_DETAILS_CHANGED_RECENTLY'), acmeLine);
  truthy('[+] the run reference fits a BACS reference', /^PAY\d{6}-[0-9A-F]{6}$/.test(run.reference || ''), run.reference);
  const again = propose(manager.token, { payUpTo: today, paymentDate: today });
  expect(again, '[-] proposing again finds nothing: the invoices are held by the run', 409, 'PURCHASE_PAYMENT_RUN_NOTHING_DUE');

  // ── approve ──────────────────────────────────────────────────────────────────
  expect(call('GET', `${RUNS}/${run.id}`, { token: rival.owner.token }), '[-] another tenant cannot see the run', 404, 'PURCHASE_PAYMENT_RUN_NOT_FOUND');
  expect(act(rival.owner.token, run.id, 'approve'), '[-] ...nor approve it', 404, 'PURCHASE_PAYMENT_RUN_NOT_FOUND');
  expect(call('GET', `${RUNS}/${run.id}/bank-file`, { token: owner }), '[-] there is no bank file before approval', 409, 'PURCHASE_PAYMENT_RUN_NOT_APPROVED');
  expect(act(owner, run.id, 'pay'), '[-] a run is not paid before approval', 409, 'PURCHASE_PAYMENT_RUN_NOT_APPROVED');
  expect(act(manager.token, run.id, 'approve'), '[-] the proposer cannot approve their own run', 403, 'PURCHASE_PAYMENT_RUN_SELF_APPROVAL');
  expect(act(storekeeper.token, run.id, 'approve'), '[-] nor can a storekeeper', 403, 'FORBIDDEN');
  expect(act(branchManager.token, run.id, 'approve'), '[-] nor a manager held to one store', 403, 'BUSINESS_WIDE_ONLY');
  expect(call('GET', `${RUNS}/${run.id}`, { token: branchManager.token }), '[-] ...who cannot read the run either', 403, 'BUSINESS_WIDE_ONLY');
  const approvedRes = act(owner, run.id, 'approve');
  expect(approvedRes, '[+] the owner approves it', 200);
  truthy('[+] ...and it reads APPROVED', data(approvedRes).status === 'APPROVED', data(approvedRes));

  // ── the bank file ────────────────────────────────────────────────────────────
  const file = http.get(`${BASE}${RUNS}/${run.id}/bank-file`, { headers: { Authorization: `Bearer ${owner}` }, tags: { name: 'GET /payment-runs/{id}/bank-file' } });
  const rows = String(file.body || '').split('\r\n').filter((l) => l.length > 0);
  truthy('[+] the bank file downloads as CSV, never cached', file.status === 200 && String(file.headers['Content-Type'] || '').startsWith('text/csv') && String(file.headers['Cache-Control'] || '').includes('no-store'), { status: file.status, headers: file.headers });
  truthy('[+] ...one payment per supplier with the account in full and the run reference', rows.length === 3 && rows.some((r) => r.includes(',123456,31415926,,,') && r.endsWith(`,GBP,${run.reference}`)) && rows.some((r) => r.includes('DE89370400440532013000,DEUTDEFF')), rows);
  expect(call('GET', `${RUNS}/${run.id}/bank-file`, { token: rival.owner.token }), '[-] another tenant cannot download it', 404, 'PURCHASE_PAYMENT_RUN_NOT_FOUND');

  // ── abuse: twenty payments at once ───────────────────────────────────────────
  const payUrl = `${BASE}${RUNS}/${run.id}/pay`;
  const params = { headers: { Authorization: `Bearer ${owner}`, 'Content-Type': 'application/json' }, tags: { name: 'POST /payment-runs/{id}/pay' } };
  const burst = http.batch(Array.from({ length: 20 }, () => ['POST', payUrl, '{}', params]));
  const paidCount = burst.filter((r) => r.status === 200).length;
  const refused = burst.filter((r) => r.status === 409 && errorCode(r) === 'PURCHASE_PAYMENT_RUN_ALREADY_PAID').length;
  truthy('[abuse] twenty payments of the run at once pay it once', paidCount === 1 && refused === 19, burst.map((r) => `${r.status} ${errorCode(r)}`));
  const lines = ledgerFor(run.id);
  const debits = lines.reduce((t, l) => t + num(l.debit), 0);
  truthy('[+] paying posts Dr 2100 / Cr 1200 per supplier for the run total, once', lines.length === 4 && Math.abs(debits - expectedTotal) < 0.005 && lines.every((l) => (num(l.debit) > 0 ? l.nominalCode === '2100' : l.nominalCode === '1200')), lines);
  const settled = data(call('GET', `${P}/supplier-invoices/${a1.inv.id}`, { token: owner }));
  truthy('[+] the invoices it held are settled by the run', settled.paid === true && settled.paymentRunId === run.id, settled);
  truthy('[+] the invoice not yet due is still unpaid', data(call('GET', `${P}/supplier-invoices/${notDue.inv.id}`, { token: owner })).paid === false);

  // ── remittance advice, delivered by notification-svc ─────────────────────────
  const advised = poll(90, () => {
    const feed = call('GET', '/api/notification-svc/admin/notifications?limit=100', { token: owner });
    return feed.status === 200 && String(feed.body).includes(`Remittance advice ${run.reference}`) && String(feed.body).includes(`ap-${stamp}@acme.example`);
  });
  truthy('[+] Acme is emailed its remittance advice for the run', advised >= 0, advised);

  // ── abuse: payment diversion ─────────────────────────────────────────────────
  dueInvoice(muster, 'INV-B2', 6, '2.50');
  const second = must(propose(manager.token, { payUpTo: today, paymentDate: today }), 201, 'second run');
  must(act(owner, second.id, 'approve'), 200, 'approve second run');
  expect(call('PUT', `${P}/suppliers/${muster.id}`, { token: owner, body: { name: muster.name, bankAccountName: 'Muster GmbH', bankIban: 'GB33 BUKB 2020 1555 5555 55' } }), '[+] the supplier\'s bank details are changed after approval', 200);
  expect(act(owner, second.id, 'pay'), '[abuse] a run is not paid to bank details changed after its approval', 409, 'PURCHASE_PAYMENT_RUN_BANK_DETAILS_CHANGED');
  expect(call('GET', `${RUNS}/${second.id}/bank-file`, { token: owner }), '[abuse] ...nor is its bank file produced', 409, 'PURCHASE_PAYMENT_RUN_BANK_DETAILS_CHANGED');
  truthy('[abuse] ...and nothing was posted for it', ledgerFor(second.id).length === 0);
  expect(act(branchManager.token, second.id, 'cancel', { reason: 'not mine to cancel' }), '[-] a manager held to one store cannot cancel a run', 403, 'BUSINESS_WIDE_ONLY');
  expect(act(manager.token, second.id, 'cancel', {}), '[-] cancelling needs a reason', 400, 'VALIDATION_FAILED');
  expect(act(manager.token, second.id, 'cancel', { reason: 'bank details changed after approval' }), '[+] the run is cancelled with a reason', 200);
  expect(act(manager.token, second.id, 'cancel', { reason: 'twice' }), '[-] ...once', 409, 'PURCHASE_PAYMENT_RUN_CANCELLED');
  expect(act(owner, run.id, 'cancel', { reason: 'too late' }), '[-] a paid run cannot be cancelled', 409, 'PURCHASE_PAYMENT_RUN_ALREADY_PAID');

  // ── abuse: ten proposals racing for the freed invoice ────────────────────────
  const raceParams = { headers: { Authorization: `Bearer ${manager.token}`, 'Content-Type': 'application/json' }, tags: { name: 'POST /payment-runs' } };
  const racing = http.batch(Array.from({ length: 10 }, () => ['POST', `${BASE}${RUNS}`, JSON.stringify({ payUpTo: today, paymentDate: today }), raceParams]));
  const won = racing.filter((r) => r.status === 201);
  const lost = racing.filter((r) => r.status === 409 && ['PURCHASE_PAYMENT_RUN_NOTHING_DUE', 'PURCHASE_PAYMENT_RUN_CONFLICT'].includes(errorCode(r)));
  truthy('[abuse] ten proposals racing for the same invoice make one run', won.length === 1 && lost.length === 9, racing.map((r) => `${r.status} ${errorCode(r)}`));
  const reproposed = won.length ? data(won[0]) : {};
  truthy('[+] the new run flags the changed bank details', JSON.stringify(reproposed).includes('BANK_DETAILS_CHANGED_RECENTLY'), reproposed);

  const listed = data(call('GET', `${RUNS}?limit=10`, { token: manager.token }));
  truthy('[+] the runs are listed newest first', Array.isArray(listed) && listed.length === 3 && listed[0].id === reproposed.id, listed);
  expect(call('GET', `${RUNS}?status=SENT`, { token: manager.token }), '[-] an unknown status is refused', 400, 'PURCHASE_PAYMENT_RUN_STATUS_UNKNOWN');
  truthy('[-] another tenant lists none of them', (data(call('GET', RUNS, { token: rival.owner.token })) || []).length === 0);
  completed.add(1);
}
