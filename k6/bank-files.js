// Bank-standard payment files (17.12), through the gateway: the business sets the accounts it pays
// from; an approved sterling run downloads as a Bacs Standard 18 file and a euro run as a SEPA
// pain.001; the bank's pain.002 status report is uploaded back — a close match on a payee's name
// holds that payment until a manager releases it with a reason, a payee the bank could not match is
// not payable, and the run is not paid while anything is held — and the refusals and the abuse
// around each: the wrong roles, the wrong tenant, a DTD in the report, a report for another file or
// naming a payment the run does not make, ten releases at once, a replayed report, the paying
// account changed after approval, a Bacs payment date too soon and a payee the format cannot pay.
//
// Euro orders need spend authority: when PURCHASE_APPROVAL_LIMITS is set it must carry EUR rows (as in
// .env.example), or every euro order waits, fail-closed, for an approval nobody has the authority to give.
//
//   k6/run.sh bank-files
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
  sellableVariant,
  staffUser,
  truthy,
} from './lib/storeql.js';

// Added on the last line only, so a flow that stopped part-way fails instead of passing.
const completed = new Counter('flow_completed');
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
  batch: 10,
  batchPerHost: 10,
  setupTimeout: '4m',
};

const P = '/api/purchase-svc';
const RUNS = `${P}/payment-runs`;
const ACCOUNTS = `${RUNS}/paying-accounts`;

export function setup() {
  const tenant = onboardTenant('bankfile', { country: 'GB', currency: 'GBP' });
  const rival = onboardTenant('bankfile-rival', { country: 'GB', currency: 'GBP' });
  const store = tenant.stores[0];
  const { variantId } = sellableVariant(tenant, 'Filed-for widget');
  // The accounts payments leave from and every run's file are the whole business's: the finance
  // manager is held to no store; one held to a store is refused BUSINESS_WIDE_ONLY.
  const manager = businessWideManager(tenant);
  const branchManager = staffUser(tenant, 'MANAGER', [store.id]);
  const storekeeper = staffUser(tenant, 'STOREKEEPER', [store.id]);
  const cashier = staffUser(tenant, 'CASHIER', [store.id]);
  return { tenant, rival, store, variantId, manager, branchManager, storekeeper, cashier };
}

const PAIN002 = 'urn:iso:std:iso:20022:tech:xsd:pain.002.001.10';

// The bank's pain.002 status report on a file: one TxInfAndSts per payment, the VoP result as Prtry.
const report = (messageId, original, txs) =>
  `<?xml version="1.0" encoding="UTF-8"?><Document xmlns="${PAIN002}"><CstmrPmtStsRpt>` +
  `<GrpHdr><MsgId>${messageId}</MsgId><CreDtTm>${new Date().toISOString()}</CreDtTm></GrpHdr>` +
  `<OrgnlGrpInfAndSts><OrgnlMsgId>${original}</OrgnlMsgId><OrgnlMsgNmId>pain.001.001.09</OrgnlMsgNmId></OrgnlGrpInfAndSts>` +
  `<OrgnlPmtInfAndSts><OrgnlPmtInfId>${original}</OrgnlPmtInfId>` +
  txs
    .map(({ e2e, status, match, name }) =>
      `<TxInfAndSts><OrgnlEndToEndId>${e2e}</OrgnlEndToEndId><TxSts>${status}</TxSts>` +
      (match ? `<StsRsnInf><Rsn><Prtry>${match}</Prtry></Rsn>${name ? `<AddtlInf>${name}</AddtlInf>` : ''}</StsRsnInf>` : '') +
      '</TxInfAndSts>')
    .join('') +
  '</OrgnlPmtInfAndSts></CstmrPmtStsRpt></Document>';

// The whole file rejected by the bank.
const rejected = (messageId, original, reason) =>
  `<?xml version="1.0" encoding="UTF-8"?><Document xmlns="${PAIN002}"><CstmrPmtStsRpt>` +
  `<GrpHdr><MsgId>${messageId}</MsgId><CreDtTm>${new Date().toISOString()}</CreDtTm></GrpHdr>` +
  `<OrgnlGrpInfAndSts><OrgnlMsgId>${original}</OrgnlMsgId><OrgnlMsgNmId>pain.001.001.09</OrgnlMsgNmId>` +
  `<GrpSts>RJCT</GrpSts><StsRsnInf><Rsn><Cd>${reason}</Cd></Rsn></StsRsnInf></OrgnlGrpInfAndSts></CstmrPmtStsRpt></Document>`;

// Creditor name to the end-to-end id a pain.001 gave its payment.
const endToEndIds = (xml) => {
  const out = {};
  for (const tx of String(xml).split('<CdtTrfTxInf>').slice(1)) {
    const e2e = (tx.match(/<EndToEndId>([^<]+)<\/EndToEndId>/) || [])[1];
    const name = (tx.match(/<Cdtr>\s*<Nm>([^<]+)<\/Nm>/) || [])[1];
    if (e2e && name) out[name] = e2e;
  }
  return out;
};

export default function ({ tenant, rival, store, variantId, manager, branchManager, storekeeper, cashier }) {
  const owner = tenant.owner.token;
  const today = new Date().toISOString().slice(0, 10);
  const plusDays = (iso, n) => new Date(Date.parse(`${iso}T00:00:00Z`) + n * 86400000).toISOString().slice(0, 10);
  const stamp = Date.now();
  const header = (res) => (name) => String(res.headers[name] || '');
  const ukBank = { bankAccountName: 'Acme Ltd', bankSortCode: '12-34-56', bankAccountNumber: '31415926' };
  const deBank = { bankAccountName: 'Muster GmbH', bankIban: 'DE89 3704 0044 0532 0130 00', bankBic: 'DEUTDEFF' };
  const frBank = { bankAccountName: 'Dupont SA', bankIban: 'FR14 2004 1010 0505 0001 3M02 606' };
  const gbpAccount = { accountName: 'Corner Shop Ltd', sortCode: '40-28-11', accountNumber: '12345678', serviceUserNumber: '123456' };
  const eurAccount = { accountName: 'Corner Shop BV', iban: 'NL91 ABNA 0417 1643 00', bic: 'ABNANL2A' };

  const supplier = (name, currency, bank) =>
    must(call('POST', `${P}/suppliers`, { token: owner, body: { name: `${name} ${stamp}`, currency, paymentTermsDays: 30, ...bank } }), 201, `supplier ${name}`);
  // An order in the currency received in full and invoiced as ordered, due ten days ago.
  const dueInvoice = (sup, number, currency, qty, price) => {
    const po = must(call('POST', `${P}/purchase-orders`, { token: owner, body: { supplierId: sup.id, storeId: store.id, currency } }), 201, 'purchase order');
    must(call('POST', `${P}/purchase-orders/${po.id}/lines`, { token: owner, body: { variantId, qty, unitPrice: price } }), 201, 'a line');
    must(call('POST', `${P}/purchase-orders/${po.id}/submit`, { token: owner, body: {} }), 200, 'submit');
    must(call('POST', `${P}/goods-receipts`, { token: owner, idem: true, body: { poId: po.id, storeId: store.id, lines: [{ variantId, qtyReceived: qty }] } }), 201, 'goods receipt');
    must(call('POST', `${P}/supplier-invoices`, { token: owner, body: { poId: po.id, invoiceNumber: `${number}-${stamp}`, invoiceDate: plusDays(today, -40), vatAmount: '0', lines: [{ variantId, qty, unitPrice: price }] } }), 201, `invoice ${number}`);
  };
  // Proposed by the manager, approved by the owner.
  const approvedRun = (currency, paymentDate) => {
    const run = must(call('POST', RUNS, { token: manager.token, body: { payUpTo: today, paymentDate, currency } }), 201, `${currency} run`);
    return must(call('POST', `${RUNS}/${run.id}/approve`, { token: owner, body: {} }), 200, `approve ${run.reference}`);
  };
  const fileOf = (runId, format, token = owner) =>
    http.get(`${BASE}${RUNS}/${runId}/bank-file?format=${format}`, { headers: { Authorization: `Bearer ${token}` }, tags: { name: 'GET /payment-runs/{id}/bank-file' } });
  const upload = (runId, xml, token = manager.token) =>
    http.post(`${BASE}${RUNS}/${runId}/status-report`, xml, { headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/xml' }, tags: { name: 'POST /payment-runs/{id}/status-report' } });
  const release = (runId, supplierId, body, token = manager.token) => call('POST', `${RUNS}/${runId}/payments/${supplierId}/release`, { token, body });
  const checkOf = (run, supplierId) => ((run && run.suppliers) || []).find((s) => s.supplierId === supplierId)?.bankCheck || {};

  // ── paying accounts: validated, gated, masked ────────────────────────────────
  expect(call('PUT', `${ACCOUNTS}/GBP`, { token: storekeeper.token, body: gbpAccount }), '[-] a storekeeper cannot set the account payments leave from', 403, 'FORBIDDEN');
  expect(call('GET', ACCOUNTS, { token: cashier.token }), '[-] a cashier cannot see the paying accounts', 403, 'FORBIDDEN');
  expect(call('PUT', `${ACCOUNTS}/GBP`, { token: owner, body: { ...gbpAccount, sortCode: '40-28-1X' } }), '[-] a sort code that is not six digits is refused', 400, 'PURCHASE_PAYING_ACCOUNT_INVALID');
  expect(call('PUT', `${ACCOUNTS}/EUR`, { token: owner, body: { ...eurAccount, iban: 'NL91 ABNA 0417 1643 01' } }), '[-] an IBAN whose check digits fail is refused', 400, 'PURCHASE_PAYING_ACCOUNT_INVALID');
  expect(call('PUT', `${ACCOUNTS}/EUR`, { token: owner, body: { ...eurAccount, serviceUserNumber: '123456' } }), '[-] a Bacs service user number without a UK account is refused', 400, 'PURCHASE_PAYING_ACCOUNT_INVALID');
  expect(call('PUT', `${ACCOUNTS}/EUR`, { token: owner, body: { accountName: 'Corner Shop', sortCode: '40-28-11', accountNumber: '12345678' } }), '[-] a euro paying account without an IBAN is refused', 400, 'PURCHASE_PAYING_ACCOUNT_INVALID');
  expect(call('PUT', `${ACCOUNTS}/POUNDS`, { token: owner, body: gbpAccount }), '[-] a currency that is not ISO 4217 is refused', 400, 'PURCHASE_INVALID_CURRENCY');
  expect(call('PUT', `${ACCOUNTS}/GBP`, { token: owner, body: { sortCode: '40-28-11' } }), '[-] an account without its holder\'s name is refused', 400, 'VALIDATION_FAILED');
  expect(call('PUT', `${ACCOUNTS}/GBP`, { token: branchManager.token, body: gbpAccount }), '[-] a manager held to one store cannot set the account the whole business pays from', 403, 'BUSINESS_WIDE_ONLY');
  expect(call('GET', ACCOUNTS, { token: branchManager.token }), '[-] ...nor read the paying accounts', 403, 'BUSINESS_WIDE_ONLY');
  const setGbp = call('PUT', `${ACCOUNTS}/GBP`, { token: manager.token, body: gbpAccount });
  expect(setGbp, '[+] a finance manager sets the sterling paying account', 200);
  truthy('[+] ...which sends Bacs, and its account number comes back masked, never in full', data(setGbp).sendsBacs === true && data(setGbp).accountNumberMasked === '****5678' && !String(setGbp.body).includes('12345678'), data(setGbp));
  must(call('PUT', `${ACCOUNTS}/EUR`, { token: owner, body: eurAccount }), 200, 'euro paying account');
  const accounts = data(call('GET', ACCOUNTS, { token: manager.token })) || [];
  truthy('[+] one paying account per currency is listed, the euro one sending SEPA', accounts.length === 2 && accounts.some((a) => a.currency === 'EUR' && a.sendsSepa === true && !String(a.ibanMasked).includes('0417164300')), accounts);
  truthy('[-] another tenant lists none of them', (data(call('GET', ACCOUNTS, { token: rival.owner.token })) || []).length === 0);

  // ── a sterling run is a Bacs Standard 18 file ────────────────────────────────
  const acme = supplier('Acme Ltd', 'GBP', ukBank);
  dueInvoice(acme, 'INV-A1', 'GBP', 7, '5.00');
  const gbpRun = approvedRun('GBP', plusDays(today, 10));
  const bacs = fileOf(gbpRun.id, 'BACS18');
  const records = String(bacs.body || '').split('\r\n').filter((r) => r.length > 0);
  const bacsHeader = header(bacs);
  truthy('[+] an approved sterling run downloads as a Bacs Standard 18 file, never cached', bacs.status === 200 && bacsHeader('Content-Type').startsWith('text/plain') && bacsHeader('Cache-Control').includes('no-store') && bacsHeader('Content-Disposition').includes('.txt'), { status: bacs.status, headers: bacs.headers, body: String(bacs.body).slice(0, 300) });
  truthy('[+] ...labelled VOL1 to UTL1, 80-character labels and 100-character records', records.length === 9 && records[0].startsWith('VOL1') && records[8].startsWith('UTL1') && records.every((r) => r.length === (/^\d/.test(r) ? 100 : 80)), records);
  truthy('[+] ...crediting Acme 35.00 from the paying account under the run reference, with its contra', records[4]?.startsWith('12345631415926099402811') && records[4].slice(35, 46) === '00000003500' && records[4].slice(64, 82).trim() === gbpRun.reference && records[5]?.startsWith('40281112345678017'), records);
  expect(fileOf(gbpRun.id, 'PAIN001'), '[-] a sterling run is not a SEPA file', 409, 'PURCHASE_BANK_FILE_FORMAT_UNSUPPORTED');
  expect(fileOf(gbpRun.id, 'SWIFT'), '[-] an unknown format is refused', 400, 'PURCHASE_BANK_FILE_FORMAT_UNKNOWN');
  expect(fileOf(gbpRun.id, 'BACS18', rival.owner.token), '[-] another tenant cannot download it', 404, 'PURCHASE_PAYMENT_RUN_NOT_FOUND');
  expect(fileOf(gbpRun.id, 'BACS18', cashier.token), '[-] nor can a cashier', 403, 'FORBIDDEN');
  const csv = fileOf(gbpRun.id, 'CSV');
  truthy('[+] the CSV is still there for a bank that takes it', csv.status === 200 && header(csv)('Content-Type').startsWith('text/csv'), csv.status);

  // ── abuse: the paying account changed after approval; too soon for Bacs ─────
  must(call('PUT', `${ACCOUNTS}/GBP`, { token: owner, body: { ...gbpAccount, accountNumber: '87654321' } }), 200, 'changed sterling account');
  expect(fileOf(gbpRun.id, 'BACS18'), '[abuse] a run approved before the paying account changed has no Bacs file', 409, 'PURCHASE_PAYMENT_RUN_PAYING_ACCOUNT_CHANGED');
  dueInvoice(acme, 'INV-A2', 'GBP', 1, '5.00');
  const todayRun = approvedRun('GBP', today);
  expect(fileOf(todayRun.id, 'BACS18'), '[-] a Bacs file made today cannot pay today', 409, 'PURCHASE_BANK_FILE_TOO_LATE');

  // ── a euro run is a SEPA pain.001 ────────────────────────────────────────────
  const muster = supplier('Muster GmbH', 'EUR', deBank);
  const dupont = supplier('Dupont SA', 'EUR', frBank);
  dueInvoice(muster, 'INV-M1', 'EUR', 4, '25.00');
  dueInvoice(dupont, 'INV-D1', 'EUR', 2, '10.50');
  const eurRun = approvedRun('EUR', plusDays(today, 3));
  const pain = fileOf(eurRun.id, 'PAIN001');
  const xml = String(pain.body || '');
  const e2e = endToEndIds(xml);
  truthy('[+] an approved euro run downloads as a pain.001, never cached', pain.status === 200 && header(pain)('Content-Type').startsWith('application/xml') && header(pain)('Cache-Control').includes('no-store'), { status: pain.status, body: xml.slice(0, 300) });
  truthy('[+] ...from the euro account to each IBAN, the run reference its message id', xml.includes(`<MsgId>${eurRun.reference}</MsgId>`) && xml.includes('<NbOfTxs>2</NbOfTxs>') && xml.includes('<IBAN>NL91ABNA0417164300</IBAN>') && xml.includes('<IBAN>DE89370400440532013000</IBAN>') && xml.includes('<IBAN>FR1420041010050500013M02606</IBAN>') && Object.keys(e2e).length === 2, { e2e, xml: xml.slice(0, 800) });
  truthy('[+] ...and the same file each time it is fetched', String(fileOf(eurRun.id, 'pain001').body) === xml);
  expect(fileOf(eurRun.id, 'BACS18'), '[-] a euro run is not a Bacs file', 409, 'PURCHASE_BANK_FILE_FORMAT_UNSUPPORTED');

  // ── the bank's answer: a close match held until released ─────────────────────
  const answer = report(`RPT1-${stamp}`, eurRun.reference, [
    { e2e: e2e['Muster GmbH'], status: 'ACCP', match: 'CMTC', name: 'MUSTER HANDELS GMBH' },
    { e2e: e2e['Dupont SA'], status: 'ACCP', match: 'MTCH' },
  ]);
  expect(upload(eurRun.id, answer, cashier.token), '[-] a cashier cannot upload the bank\'s answer', 403, 'FORBIDDEN');
  expect(upload(eurRun.id, answer, rival.owner.token), '[-] nor can another tenant', 404, 'PURCHASE_PAYMENT_RUN_NOT_FOUND');
  expect(upload(eurRun.id, '<?xml version="1.0"?><!DOCTYPE d [<!ENTITY x SYSTEM "file:///etc/passwd">]><d>&x;</d>'), '[abuse] a report carrying a DTD is refused unread', 400, 'PURCHASE_STATUS_REPORT_INVALID');
  expect(upload(eurRun.id, 'not a status report'), '[-] a file that is not XML is refused', 400, 'PURCHASE_STATUS_REPORT_INVALID');
  expect(upload(eurRun.id, answer.replace(`<OrgnlMsgId>${eurRun.reference}`, '<OrgnlMsgId>PAY000000-000000')), '[abuse] a report answering another file is refused', 409, 'PURCHASE_STATUS_REPORT_NOT_FOR_RUN');
  expect(upload(eurRun.id, report(`RPTX-${stamp}`, eurRun.reference, [{ e2e: '0123456789abcdef0123456789abcdef', status: 'ACCP', match: 'MTCH' }])), '[abuse] a report naming a payment the run does not make is refused', 409, 'PURCHASE_STATUS_REPORT_UNKNOWN_PAYMENT');

  const recorded = upload(eurRun.id, answer);
  expect(recorded, '[+] a manager uploads the bank\'s status report', 200);
  const held = checkOf(data(recorded), muster.id);
  truthy('[+] ...the close match on Muster is held with the name the bank holds, and releasable', held.payeeMatch === 'CMTC' && held.matchedName === 'MUSTER HANDELS GMBH' && held.held === true && held.releasable === true, data(recorded));
  truthy('[+] ...and the payee the bank matched is not held', checkOf(data(recorded), dupont.id).held === false, data(recorded));
  const heldPay = call('POST', `${RUNS}/${eurRun.id}/pay`, { token: owner, body: {} });
  expect(heldPay, '[abuse] a run with a held payment is not paid', 409, 'PURCHASE_PAYMENT_RUN_PAYEE_HELD');
  truthy('[abuse] ...and the refusal names who is held and why', String(heldPay.body).includes('Muster GmbH') && String(heldPay.body).includes('CLOSE_MATCH'), String(heldPay.body).slice(0, 400));

  expect(release(eurRun.id, muster.id, {}), '[-] releasing needs a reason', 400, 'VALIDATION_FAILED');
  expect(release(eurRun.id, dupont.id, { reason: 'checked' }), '[-] a payment the bank did not hold is not released', 409, 'PURCHASE_PAYEE_NOT_HELD');
  expect(release(eurRun.id, muster.id, { reason: 'checked' }, storekeeper.token), '[-] a storekeeper cannot release a held payment', 403, 'FORBIDDEN');
  const releaseParams = { headers: { Authorization: `Bearer ${owner}`, 'Content-Type': 'application/json' }, tags: { name: 'POST /payment-runs/{id}/payments/{supplierId}/release' } };
  const releaseBody = JSON.stringify({ reason: 'Rang Muster: the bank holds their registered name' });
  const burst = http.batch(Array.from({ length: 10 }, () => ['POST', `${BASE}${RUNS}/${eurRun.id}/payments/${muster.id}/release`, releaseBody, releaseParams]));
  const releasedOnce = burst.filter((r) => r.status === 200).length === 1 && burst.filter((r) => r.status === 409 && errorCode(r) === 'PURCHASE_PAYEE_ALREADY_RELEASED').length === 9;
  truthy('[abuse] ten releases at once release it once', releasedOnce, burst.map((r) => `${r.status} ${errorCode(r)}`));
  const replayed = upload(eurRun.id, answer);
  expect(replayed, '[abuse] the same report uploaded again is taken once', 200);
  const afterReplay = checkOf(data(replayed), muster.id);
  truthy('[abuse] ...and does not undo the release', Boolean(afterReplay.releasedAt) && String(afterReplay.releaseReason).includes('Rang Muster') && afterReplay.releasable === false, afterReplay);
  const paid = call('POST', `${RUNS}/${eurRun.id}/pay`, { token: owner, body: {} });
  expect(paid, '[+] with the close match released, the run is paid', 200);
  truthy('[+] ...and reads PAID', data(paid).status === 'PAID', data(paid));
  expect(upload(eurRun.id, report(`RPT2-${stamp}`, eurRun.reference, [{ e2e: e2e['Dupont SA'], status: 'ACSC' }])), '[-] a paid run takes no further report', 409, 'PURCHASE_PAYMENT_RUN_NOT_AWAITING_BANK');

  // ── a payee the bank cannot match, and a rejected file ───────────────────────
  dueInvoice(muster, 'INV-M2', 'EUR', 1, '10.00');
  const noMatchRun = approvedRun('EUR', plusDays(today, 3));
  const noMatchIds = endToEndIds(fileOf(noMatchRun.id, 'PAIN001').body);
  must(upload(noMatchRun.id, report(`RPT3-${stamp}`, noMatchRun.reference, [{ e2e: noMatchIds['Muster GmbH'], status: 'PDNG', match: 'NMTC' }])), 200, 'no-match report');
  expect(release(noMatchRun.id, muster.id, { reason: 'trust me' }, owner), '[abuse] a payee the bank could not match cannot be released', 409, 'PURCHASE_PAYEE_NOT_RELEASABLE');
  expect(call('POST', `${RUNS}/${noMatchRun.id}/pay`, { token: owner, body: {} }), '[abuse] ...nor its run paid', 409, 'PURCHASE_PAYMENT_RUN_PAYEE_HELD');
  const bounced = checkOf(must(upload(noMatchRun.id, rejected(`RPT4-${stamp}`, noMatchRun.reference, 'FF01')), 200, 'rejected file'), muster.id);
  truthy('[+] a file the bank rejected holds its payments, releasable by nobody', bounced.status === 'RJCT' && bounced.reasonCode === 'FF01' && bounced.held === true && bounced.releasable === false, bounced);
  expect(call('POST', `${RUNS}/${noMatchRun.id}/cancel`, { token: manager.token, body: { reason: 'the bank could not match Muster' } }), '[+] the run is cancelled instead', 200);

  // ── a payee the format cannot pay ────────────────────────────────────────────
  const acmeEu = supplier('Acme Europe', 'EUR', { ...ukBank, bankAccountName: 'Acme Europe' });
  dueInvoice(acmeEu, 'INV-E1', 'EUR', 1, '10.00');
  const mixedRun = approvedRun('EUR', plusDays(today, 3));
  const unsupported = fileOf(mixedRun.id, 'PAIN001');
  expect(unsupported, '[-] a euro run paying a supplier with no IBAN has no pain.001', 409, 'PURCHASE_BANK_FILE_PAYEE_UNSUPPORTED');
  truthy('[-] ...and the refusal names the supplier', String(unsupported.body).includes('Acme Europe'), String(unsupported.body).slice(0, 300));
  completed.add(1);
}
