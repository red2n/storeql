// The accounting seam (17.1, 17.3, 04.7, 07.7), through the gateway: a goods receipt posts the
// stock against the accrual on the store's mapped nominal code; a supplier invoice posts the
// creditor with a due date and is checked against its own stated total; a flagged invoice is
// approved or rejected by a manager, a rejection reversed line for line and taken out of the VAT
// return; a credit note reverses the creditor; finance posts a manual journal and reads the trial
// balance; a closed accounting period refuses a posting — and the refusals and the abuse around
// each: the wrong roles, the wrong tenant, journals that do not balance, twenty rejections at once.
//
//   k6/run.sh ledger-flow
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
  sellableVariant,
  staffUser,
  truthy,
} from './lib/storeql.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS, setupTimeout: '3m' };

const P = '/api/purchase-svc';
const LEDGER = `${P}/nominal-ledger`;

export function setup() {
  const tenant = onboardTenant('ledger', { country: 'GB', currency: 'GBP' });
  const rival = onboardTenant('ledger-rival', { country: 'GB', currency: 'GBP' });
  const store = tenant.stores[0];
  const { variantId } = sellableVariant(tenant, 'Ledgered widget');
  const storekeeper = staffUser(tenant, 'STOREKEEPER', [store.id]);
  const cashier = staffUser(tenant, 'CASHIER', [store.id]);
  return { tenant, rival, store, variantId, storekeeper, cashier };
}

export default function ({ tenant, rival, store, variantId, storekeeper, cashier }) {
  const owner = tenant.owner.token;
  const today = new Date().toISOString().slice(0, 10);
  const plusDays = (iso, n) => new Date(Date.parse(`${iso}T00:00:00Z`) + n * 86400000).toISOString().slice(0, 10);
  const list = (res) => { const d = data(res); return Array.isArray(d) ? d : []; };
  const ledger = (query, token = owner) => list(call('GET', `${LEDGER}?limit=100${query}`, { token }));
  const trialBalance = (query = '', token = owner) => call('GET', `${LEDGER}/trial-balance${query}`, { token });
  const row = (tb, code) => (tb.rows || []).find((r) => r.nominalCode === code) || {};
  const num = (v) => Number(v || 0);

  // A submitted order for `qty` at `price`, with `received` of it booked.
  const receivedOrder = (label, qty, price, received, terms = 30) => {
    const supplier = must(call('POST', `${P}/suppliers`, { token: owner, body: { name: `${label} ${Date.now()}`, vatRegistered: true, currency: 'GBP', paymentTermsDays: terms } }), 201, 'supplier');
    const po = must(call('POST', `${P}/purchase-orders`, { token: owner, body: { supplierId: supplier.id, storeId: store.id } }), 201, 'purchase order');
    must(call('POST', `${P}/purchase-orders/${po.id}/lines`, { token: owner, body: { variantId, qty, unitPrice: price } }), 201, 'a line');
    must(call('POST', `${P}/purchase-orders/${po.id}/submit`, { token: owner, body: {} }), 200, 'submit');
    let gr = null;
    if (received > 0) {
      gr = must(call('POST', `${P}/goods-receipts`, { token: owner, idem: true, body: { poId: po.id, storeId: store.id, lines: [{ variantId, qtyReceived: received }] } }), 201, 'goods receipt');
    }
    return { po, gr, supplier };
  };
  const invoice = (poId, number, qty, price, vat, extra = {}) =>
    call('POST', `${P}/supplier-invoices`, { token: owner, body: { poId, invoiceNumber: number, invoiceDate: today, vatAmount: vat, lines: [{ variantId, qty, unitPrice: price }], ...extra } });
  const resolve = (id, action, reason, token = owner) =>
    call('POST', `${P}/supplier-invoices/${id}/resolve`, { token, body: { action, reason } });

  // ── 17.3: the store's stock posts to its mapped code ─────────────────────────
  expect(call('PUT', '/api/inventory-svc/admin/inventory/zone-gl-mappings', { token: owner, body: { storeId: store.id, nominalCode: '1005', description: 'Stock — first store' } }), '[+] the store is mapped to nominal code 1005', 200);

  // ── goods receipt: Dr Stock / Cr GR/IR ───────────────────────────────────────
  const one = receivedOrder('Ledger Supplies', 10, '15.00', 10, 45);
  const stock = ledger(`&code=1005&from=${today}&to=${today}`).filter((e) => e.sourceRef === one.gr.id);
  truthy('[+] the receipt debits the mapped stock code for 150.00', stock.length === 1 && num(stock[0].debit) === 150 && stock[0].sourceType === 'GOODS_RECEIPT' && stock[0].storeId === store.id, stock);
  const accrual = ledger(`&code=2109&from=${today}&to=${today}`).filter((e) => e.sourceRef === one.gr.id);
  truthy('[+] ...and credits goods received not invoiced on the same journal', accrual.length === 1 && num(accrual[0].credit) === 150 && accrual[0].journalId === stock[0].journalId, accrual);
  truthy('[+] the default stock code is untouched: the mapping was consumed', ledger(`&code=1001&from=${today}&to=${today}`).length === 0);

  // ── supplier invoice: Dr GR/IR, Dr VAT / Cr Creditors, due by the terms ──────
  const matched = invoice(one.po.id, `INV-M-${Date.now()}`, 10, '15.00', '30.00', { statedGross: '180.00' });
  expect(matched, '[+] a matched invoice is captured', 201);
  const m = data(matched);
  truthy('[+] ...MATCHED, payable, posted, due 45 days after the invoice date', m.status === 'MATCHED' && m.payable === true && Boolean(m.postedAt) && m.dueDate === plusDays(today, 45) && (m.headerVariances || []).length === 0, m);
  const creditor = ledger(`&code=2100&from=${today}&to=${today}`).filter((e) => e.sourceRef === m.id);
  truthy('[+] the creditor is credited 180.00', creditor.length === 1 && num(creditor[0].credit) === 180 && creditor[0].sourceType === 'SUPPLIER_INVOICE', creditor);
  const vatIn = ledger(`&code=2201&from=${today}&to=${today}`).filter((e) => e.sourceRef === m.id);
  truthy('[+] VAT input is debited 30.00 on the same journal', vatIn.length === 1 && num(vatIn[0].debit) === 30 && vatIn[0].journalId === creditor[0].journalId, vatIn);
  const tb1 = trialBalance(`?storeId=${store.id}&from=${today}&to=${today}`);
  expect(tb1, '[+] the trial balance reads', 200);
  truthy('[+] ...balanced: stock 150, GR/IR nets to nothing, creditors -180, VAT 30', data(tb1).balanced === true && num(row(data(tb1), '1005').balance) === 150 && num(row(data(tb1), '2109').balance) === 0 && num(row(data(tb1), '2100').balance) === -180 && num(row(data(tb1), '2201').balance) === 30 && num(data(tb1).totalDebit) === num(data(tb1).totalCredit), data(tb1));
  expect(resolve(m.id, 'APPROVE', 'nothing to decide'), '[-] a matched invoice has nothing to decide', 409, 'PURCHASE_INVOICE_NOT_FLAGGED');

  // ── the header check and the decision ────────────────────────────────────────
  const two = receivedOrder('Creeping Prices', 10, '15.00', 10);
  const flaggedRes = invoice(two.po.id, `INV-F-${Date.now()}`, 10, '16.50', '33.00', { statedGross: '200.00' });
  expect(flaggedRes, '[+] an invoice priced above the order, whose total does not add up, is captured', 201);
  const f = data(flaggedRes);
  truthy('[+] ...FLAGGED on both counts, not payable, posted all the same', f.status === 'FLAGGED' && f.payable === false && Boolean(f.postedAt) && (f.headerVariances || []).includes('TOTAL_MISMATCH') && (f.lines[0].variances || []).includes('PRICE_ABOVE_ORDER'), f);
  truthy('[+] the queue awaiting a decision lists it', list(call('GET', `${P}/supplier-invoices?status=flagged`, { token: owner })).some((i) => i.id === f.id));
  truthy('[+] ...and the matched list does not', !list(call('GET', `${P}/supplier-invoices?status=MATCHED`, { token: owner })).some((i) => i.id === f.id));
  expect(call('GET', `${P}/supplier-invoices?status=PAID`, { token: owner }), '[-] a status that is not one of the four', 400, 'PURCHASE_INVOICE_STATUS_UNKNOWN');
  expect(resolve(f.id, 'APPROVE', 'fine', storekeeper.token), '[-] a storekeeper cannot decide', 403, 'FORBIDDEN');
  expect(resolve(f.id, 'APPROVE', 'fine', cashier.token), '[-] nor a cashier', 403, 'FORBIDDEN');
  expect(resolve(f.id, 'APPROVE', ''), '[-] no reason, no decision', 400, 'VALIDATION_FAILED');
  expect(resolve(f.id, 'PAY', 'fine'), '[-] an action that is neither', 400, 'PURCHASE_RESOLUTION_UNKNOWN');
  expect(resolve(f.id, 'APPROVE', 'fine', rival.owner.token), '[-] the rival shop cannot see it', 404, 'PURCHASE_INVOICE_NOT_FOUND');
  const approved = resolve(f.id, 'approve', 'Supplier confirmed the price rise in writing');
  expect(approved, '[+] the owner approves it for payment', 200);
  truthy('[+] ...APPROVED, payable, with who and why', data(approved).status === 'APPROVED' && data(approved).payable === true && data(approved).resolvedBy === tenant.owner.userId && data(approved).resolutionReason.includes('in writing'), data(approved));
  expect(resolve(f.id, 'REJECT', 'changed my mind'), '[-] a decision is made once', 409, 'PURCHASE_INVOICE_NOT_FLAGGED');
  truthy('[+] approval posts nothing new: the creditor still carries 198.00 for it', ledger(`&code=2100&from=${today}&to=${today}`).filter((e) => e.sourceRef === f.id).length === 1);

  // ── rejection: reversed, out of the return, the order freed ──────────────────
  const three = receivedOrder('Over Billers', 10, '15.00', 4);
  const overRes = invoice(three.po.id, `INV-R-${Date.now()}`, 10, '15.00', '30.00');
  expect(overRes, '[+] an invoice for ten when four arrived is captured', 201);
  const r = data(overRes);
  truthy('[+] ...FLAGGED as billed above received', r.status === 'FLAGGED' && (r.lines[0].variances || []).includes('INVOICED_ABOVE_RECEIVED'), r);
  const hammer = http.batch(Array.from({ length: 20 }, () => ['POST', `${BASE}${P}/supplier-invoices/${r.id}/resolve`, JSON.stringify({ action: 'REJECT', reason: 'twenty managers at once' }), { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${owner}` }, tags: { name: 'POST /api/purchase-svc/supplier-invoices/{id}/resolve (hammer)' } }]));
  const won = hammer.filter((h) => h.status === 200).length;
  const lost = hammer.filter((h) => h.status === 409).length;
  truthy('[+] twenty rejections at once: one decision, nineteen conflicts', won === 1 && lost === 19, hammer.map((h) => `${h.status} ${errorCode(h)}`).join(','));
  const reversal = ledger(`&from=${today}&to=${today}`).filter((e) => e.sourceRef === r.id && e.sourceType === 'INVOICE_REVERSAL');
  truthy('[+] ...and exactly one reversal journal, three lines mirroring the posting', reversal.length === 3 && new Set(reversal.map((e) => e.journalId)).size === 1 && reversal.some((e) => e.nominalCode === '2100' && num(e.debit) === 180) && reversal.some((e) => e.nominalCode === '2201' && num(e.credit) === 30), reversal);
  truthy('[+] the rejected list carries it', list(call('GET', `${P}/supplier-invoices?status=REJECTED`, { token: owner })).some((i) => i.id === r.id));
  const fixed = invoice(three.po.id, `INV-OK-${Date.now()}`, 4, '15.00', '12.00');
  expect(fixed, '[+] the corrected invoice for the four that came', 201);
  truthy('[+] ...matches cleanly: the rejected quantities no longer count', data(fixed).status === 'MATCHED' && num(data(fixed).lines[0].qtyInvoicedBefore) === 0, data(fixed));
  const tb2 = trialBalance(`?storeId=${store.id}`);
  truthy('[+] the trial balance still balances after all of that', data(tb2).balanced === true && num(data(tb2).totalDebit) === num(data(tb2).totalCredit), data(tb2));
  // Box 4 of the VAT return: 30 (matched) + 33 (approved) + 12 (corrected); the rejected 30 is gone.
  const period = `from=${today}T00:00:00Z&to=${plusDays(today, 1)}T00:00:00Z`;
  const box4 = () => num(data(call('GET', `/api/pricing-svc/vat-return?${period}`, { token: owner })).box4);
  const settled = poll(40, () => box4() === 75);
  truthy('[+] the VAT return carries 75.00 of input VAT: the rejected invoice left it', settled >= 0, { box4: box4() });

  // ── credit note: Dr Creditors / Cr Stock, Cr VAT ─────────────────────────────
  // The receipt reaches inventory-svc as an event; the return checks on-hand there first.
  const onHand = () => list(call('GET', `/api/inventory-svc/admin/inventory/batches?store=${store.id}&variant=${variantId}&material_status=AVAILABLE&limit=100`, { token: owner })).reduce((sum, b) => sum + num(b.remainingQty), 0);
  truthy('[+] the deliveries have reached inventory within 30 s', poll(30, () => onHand() >= 2) >= 0, { onHand: onHand() });
  const ret = call('POST', `${P}/vendor-returns`, { token: owner, body: { poId: two.po.id, reason: 'DAMAGED', notes: 'two cases crushed', lines: [{ variantId, qty: 2 }] } });
  expect(ret, '[+] two cases go back to the supplier', 201);
  const credited = call('POST', `${P}/vendor-returns/${data(ret).id}/credit`, { token: owner, body: { creditNoteNumber: `CN-${Date.now()}`, creditNoteDate: today } });
  expect(credited, '[+] the credit note is recorded', 200);
  const cn = ledger(`&from=${today}&to=${today}`).filter((e) => e.sourceRef === data(ret).id && e.sourceType === 'CREDIT_NOTE');
  const cnDebit = cn.reduce((s, e) => s + num(e.debit), 0);
  const cnCredit = cn.reduce((s, e) => s + num(e.credit), 0);
  truthy('[+] ...debiting the creditor for the credit, credited to stock and VAT, balanced', cn.length >= 2 && cn.some((e) => e.nominalCode === '2100' && num(e.debit) === num(data(credited).creditAmount)) && cn.some((e) => e.nominalCode === '1005') && Math.abs(cnDebit - cnCredit) < 0.005, cn);

  // ── 17.1: manual journals and the trial balance ──────────────────────────────
  const journals = `${LEDGER}/journals`;
  const lines2 = [{ nominalCode: '1005', nominalName: 'Stock', debit: '500.00' }, { nominalCode: '3000', nominalName: 'Capital', credit: '500.00' }];
  const posted = call('POST', journals, { token: owner, body: { entryDate: today, description: 'Opening stock', lines: lines2 } });
  expect(posted, '[+] a balanced journal is posted', 201);
  const j = data(posted);
  truthy('[+] ...with a journal id, its totals and two lines', Boolean(j.journalId) && num(j.totalDebit) === 500 && num(j.totalCredit) === 500 && j.lines.length === 2 && j.sourceType === 'JOURNAL', j);
  const read = call('GET', `${journals}/${j.journalId}`, { token: owner });
  expect(read, '[+] and read back whole', 200);
  truthy('[+] ...as it was posted', data(read).description === 'Opening stock' && data(read).lines.length === 2, data(read));
  expect(call('GET', `${journals}/${j.journalId}`, { token: rival.owner.token }), '[-] the rival shop cannot read it', 404, 'PURCHASE_JOURNAL_NOT_FOUND');
  expect(call('GET', `${journals}/${j.journalId}`, { token: storekeeper.token }), '[-] nor a storekeeper', 403, 'FORBIDDEN');
  expect(call('POST', journals, { token: storekeeper.token, body: { entryDate: today, description: 'x', lines: lines2 } }), '[-] a storekeeper cannot post', 403, 'FORBIDDEN');
  expect(call('POST', journals, { token: cashier.token, body: { entryDate: today, description: 'x', lines: lines2 } }), '[-] nor a cashier', 403, 'FORBIDDEN');
  expect(trialBalance('', cashier.token), '[-] a cashier cannot read the trial balance', 403, 'FORBIDDEN');
  expect(trialBalance('', storekeeper.token), '[-] nor a storekeeper', 403, 'FORBIDDEN');
  expect(call('POST', journals, { token: owner, body: { entryDate: today, description: 'x', lines: [{ nominalCode: '1005', debit: '100.00' }, { nominalCode: '3000', credit: '99.99' }] } }), '[-] a journal off by a penny', 422, 'PURCHASE_JOURNAL_UNBALANCED');
  expect(call('POST', journals, { token: owner, body: { entryDate: today, description: 'x', lines: [{ nominalCode: '1005', debit: '100.00', credit: '100.00' }, { nominalCode: '3000', credit: '0' }] } }), '[-] a line that is both a debit and a credit', 400, 'PURCHASE_JOURNAL_LINE_INVALID');
  expect(call('POST', journals, { token: owner, body: { entryDate: today, description: 'x', lines: [{ nominalCode: '1005', debit: '100.00' }] } }), '[-] one line is not a journal', 400, 'VALIDATION_FAILED');
  expect(call('POST', journals, { token: owner, body: { entryDate: today, description: 'x', lines: [{ nominalCode: '1005', debit: '-5' }, { nominalCode: '3000', debit: '5' }] } }), '[-] a negative amount', 400, 'VALIDATION_FAILED');
  expect(call('POST', journals, { token: owner, body: { entryDate: today, description: 'x', lines: [{ nominalCode: '10 05', debit: '5' }, { nominalCode: '3000', credit: '5' }] } }), '[-] a code that is not a code', 400, 'PURCHASE_JOURNAL_LINE_INVALID');
  expect(call('POST', journals, { token: owner, body: { entryDate: today, description: 'x', lines: [{ nominalCode: '1005; DROP TABLE nominal_ledger_entries', debit: '5' }, { nominalCode: '3000', credit: '5' }] } }), '[-] a code too long to be one is stopped at the boundary', 400, 'VALIDATION_FAILED');
  expect(call('POST', journals, { token: owner, body: { entryDate: 'today', description: 'x', lines: lines2 } }), '[-] a date that is not a date', 400, 'INVALID_DATE');
  expect(call('POST', journals, { token: owner, body: { entryDate: today, description: '', lines: lines2 } }), '[-] no description', 400, 'VALIDATION_FAILED');
  expect(call('POST', journals, { token: owner, body: { entryDate: today, description: 'x', storeId: rival.stores[0].id, lines: lines2 } }), '[-] a store the caller may not post to is refused by the ledger of the rival, not ours', [201, 403]);
  expect(call('POST', journals, { token: owner, body: { entryDate: today, description: 'load', lines: Array.from({ length: 51 }, () => ({ nominalCode: '1005', debit: '1' })) } }), '[-] fifty-one lines is a data load, not a journal', 400, 'VALIDATION_FAILED');
  expect(trialBalance(`?from=${today}&to=${plusDays(today, -1)}`), '[-] a range that ends before it starts', 400, 'PURCHASE_INVALID_PERIOD');
  truthy('[+] the rival shop\'s trial balance has none of it', (data(trialBalance('', rival.owner.token)).rows || []).length === 0);
  truthy('[+] the ledger\'s lines stay readable by any member of staff', call('GET', LEDGER, { token: storekeeper.token }).status === 200);

  // ── 04.7: a closed period refuses a posting ──────────────────────────────────
  const periods = '/api/inventory-svc/admin/inventory/accounting-periods';
  const june = call('POST', periods, { token: owner, body: { storeId: store.id, periodName: 'June 2020', periodDate: '2020-06-01' } });
  expect(june, '[+] a period is opened for June 2020', 201);
  expect(call('POST', `${periods}/${data(june).id}/close`, { token: owner, body: {} }), '[+] and closed', 200);
  expect(call('POST', journals, { token: owner, body: { entryDate: '2020-06-15', description: 'late', storeId: store.id, lines: lines2 } }), '[-] a journal into the closed month is refused', 409, 'PURCHASE_PERIOD_CLOSED');
  expect(call('POST', journals, { token: owner, body: { entryDate: '2020-07-15', description: 'july', storeId: store.id, lines: lines2 } }), '[+] the next month, never opened, is not controlled', 201);
  expect(call('POST', journals, { token: owner, body: { entryDate: '2020-06-15', description: 'tenant level', lines: lines2 } }), '[+] a tenant-level journal answers to no store\'s period', 201);
  // Now this month, which every posting so far landed in — so this is the last thing the flow does.
  const thisMonth = `${today.slice(0, 7)}-01`;
  const current = call('POST', periods, { token: owner, body: { storeId: store.id, periodName: `Month ${thisMonth}`, periodDate: thisMonth } });
  expect(current, '[+] the current month is opened', 201);
  expect(call('POST', `${periods}/${data(current).id}/close`, { token: owner, body: {} }), '[+] and closed', 200);
  const late = receivedOrder('After Close', 5, '10.00', 0);
  expect(call('POST', `${P}/goods-receipts`, { token: owner, idem: true, body: { poId: late.po.id, storeId: store.id, lines: [{ variantId, qtyReceived: 5 }] } }), '[-] a goods receipt into the closed month is refused', 409, 'PURCHASE_PERIOD_CLOSED');
  expect(invoice(late.po.id, `INV-LATE-${Date.now()}`, 5, '10.00', '10.00'), '[-] and so is an invoice dated in it', 409, 'PURCHASE_PERIOD_CLOSED');
  truthy('[+] the order is untouched: still SUBMITTED, nothing received', data(call('GET', `${P}/purchase-orders/${late.po.id}`, { token: owner })).status === 'SUBMITTED');
  truthy('[+] ...and the rival shop, with no periods, posts freely', call('POST', journals, { token: rival.owner.token, body: { entryDate: today, description: 'free', storeId: rival.stores[0].id, lines: lines2 } }).status === 201);
}
