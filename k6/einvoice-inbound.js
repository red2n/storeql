// Supplier e-invoices in (07.13), through the gateway. The business records how e-invoices name it. A
// supplier's UBL invoice naming its order and order line is captured through the three-way match and
// kept byte for byte. A Factur-X PDF crosses the gateway intact and waits for its order until a person
// picks it, and the supplier's item code is remembered so its next invoice matches on its own. An
// invoice from a sender nobody knows waits for its supplier, and once matched the sender's address is
// remembered. A credit note closes the return it credits, but only for someone who may record one.
// Where a Polish buyer fetches from, and a readiness check that asks before a first fetch rather than
// leaving an inbox quietly empty. Refused: a VAT number or address that does not parse, a storekeeper setting them, half an address on a
// supplier, an address another supplier holds, an order that is not the supplier's, a line not on the
// order or not on the invoice, a line chosen twice, matching a non-compliant or misdirected invoice,
// refusing without the permission, without a reason or twice. Abuse: the same bytes twice, the same
// number in another file, a DTD, a document that is not an invoice, a PDF that is not one, an empty body,
// JSON, a card number inside an invoice, over 1 MB to any other route, over 10 MB of XML, over 21 MB at
// all, another tenant's reads and matches, ten matches at once.
//
//   k6/run.sh einvoice-inbound
import http from 'k6/http';
import { Counter } from 'k6/metrics';
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

// Written by the shared einvoice module (EInvoices.toUbl and toFacturX), so they break no EN 16931 or
// Peppol rule: apples at 2.50 with 20% VAT, from GB555555555 (GLN 5790000435975) to GB123456789. The
// invoice is 10 apples, the credit note 2, the PDF 4 with no order reference.
const INVOICE = open('./fixtures/einvoice-invoice.xml');
const CREDIT_NOTE = open('./fixtures/einvoice-credit-note.xml');
const FACTUR_X = open('./fixtures/einvoice-facturx.pdf', 'b');

const P = '/api/purchase-svc';
const EINV = `${P}/e-invoices`;
const TENANT = '/api/tenant-svc/admin/tenant';
const OUR_VAT = 'GB123456789';

export function setup() {
  const tenant = onboardTenant('einvoice', { country: 'GB', currency: 'GBP' });
  const rival = onboardTenant('einvoice-rival', { country: 'GB', currency: 'GBP' });
  const store = tenant.stores[0];
  const { variantId } = sellableVariant(tenant, 'Invoiced apples');
  const manager = staffUser(tenant, 'MANAGER', [store.id]);
  const storekeeper = staffUser(tenant, 'STOREKEEPER', [store.id]);
  return { tenant, rival, store, variantId, manager, storekeeper };
}

const fill = (template, values) =>
  Object.entries(values).reduce((xml, [token, value]) => xml.split(token).join(value), template);

const invoiceXml = ({ number, orderRef, orderLine = '999', sellerVat = 'GB555555555', sellerGln = '5790000435975', buyerVat = OUR_VAT, itemCode = 'K6-ITEM-CODE', buyerRef = 'SHOP-1' }) =>
  fill(INVOICE, {
    'K6-NUMBER': number,
    'K6-ORDER-REFERENCE': orderRef,
    'K6-ORDER-LINE': orderLine,
    GB555555555: sellerVat,
    '5790000435975': sellerGln,
    GB123456789: buyerVat,
    'K6-ITEM-CODE': itemCode,
    'SHOP-1': buyerRef,
  });

const creditNoteXml = ({ number, preceding }) => fill(CREDIT_NOTE, { 'K6-NUMBER': number, 'K6-PRECEDING': preceding });

// A GLN (GS1 GTIN-13) with its check digit, as Peppol's 0088 scheme requires.
const gln = (first12) => {
  let sum = 0;
  for (let i = 0; i < 12; i++) sum += Number(first12[11 - i]) * (i % 2 === 0 ? 3 : 1);
  return `${first12}${(10 - (sum % 10)) % 10}`;
};

const sameBytes = (a, b) => {
  const x = new Uint8Array(a);
  const y = new Uint8Array(b);
  if (x.length !== y.length) return false;
  for (let i = 0; i < x.length; i++) if (x[i] !== y[i]) return false;
  return true;
};

export default function ({ tenant, rival, store, variantId, manager, storekeeper }) {
  const owner = tenant.owner.token;
  // Letters between the digits: a long run of random digits can be a Luhn-valid card number, and the
  // gateway refuses a body carrying one.
  const stamp = Date.now().toString(36).toUpperCase();
  const digits = String(Date.now() % 1e8).padStart(8, '0');
  const auth = (token) => ({ Authorization: `Bearer ${token}` });

  const upload = (body, token = owner, type = 'application/xml') =>
    http.post(`${BASE}${EINV}`, body, { headers: { ...auth(token), 'Content-Type': type, Accept: 'application/json' }, tags: { name: 'POST /e-invoices' } });
  const original = (id, token = owner, binary = false) =>
    http.get(`${BASE}${EINV}/${id}/document`, { headers: auth(token), responseType: binary ? 'binary' : 'text', tags: { name: 'GET /e-invoices/{id}/document' } });
  const match = (id, body, token = owner) => call('POST', `${EINV}/${id}/match`, { token, body });
  const refuse = (id, reason, token = manager.token) => call('POST', `${EINV}/${id}/refuse`, { token, body: { reason } });
  const einvoice = (id, token = owner) => call('GET', `${EINV}/${id}`, { token });
  const supplier = (name, extra = {}) =>
    must(call('POST', `${P}/suppliers`, { token: owner, body: { name: `${name} ${stamp}`, currency: 'GBP', paymentTermsDays: 30, ...extra } }), 201, `supplier ${name}`);
  // An order for `qty` apples at 2.50, submitted and received in full.
  const receivedOrder = (supplierId, qty) => {
    const po = must(call('POST', `${P}/purchase-orders`, { token: owner, body: { supplierId, storeId: store.id, currency: 'GBP' } }), 201, 'purchase order');
    must(call('POST', `${P}/purchase-orders/${po.id}/lines`, { token: owner, body: { variantId, qty, unitPrice: '2.50' } }), 201, 'order line');
    must(call('POST', `${P}/purchase-orders/${po.id}/submit`, { token: owner, body: {} }), 200, 'submit');
    must(call('POST', `${P}/goods-receipts`, { token: owner, idem: true, body: { poId: po.id, storeId: store.id, lines: [{ variantId, qtyReceived: qty }] } }), 201, 'goods receipt');
    const lines = must(call('GET', `${P}/purchase-orders/${po.id}/lines`, { token: owner }), 200, 'order lines');
    return { id: po.id, lineId: lines[0].id };
  };
  const invoicesOn = (poId) => {
    const list = data(call('GET', `${P}/supplier-invoices?poId=${poId}`, { token: owner }));
    return Array.isArray(list) ? list : [];
  };

  // ── how e-invoices name the business ─────────────────────────────────────────
  const profile = must(call('GET', TENANT, { token: owner }), 200, 'tenant profile');
  const identity = (body, token = owner) =>
    call('PUT', TENANT, { token, body: { businessName: profile.name, legalName: profile.legalName, ...body } });
  expect(identity({ vatNumber: '12' }), '[-] a VAT number without its country prefix is refused', 400, 'TENANT_VAT_NUMBER_INVALID');
  expect(identity({ einvoiceScheme: '0088' }), '[-] half an e-invoicing address is refused', 400, 'TENANT_EINVOICE_ADDRESS_INVALID');
  expect(identity({ einvoiceScheme: '0088', einvoiceId: '5790000435976' }), '[-] a GLN whose check digit fails is refused', 400, 'TENANT_EINVOICE_ADDRESS_INVALID');
  expect(identity({ vatNumber: OUR_VAT }, storekeeper.token), '[-] a storekeeper cannot change how e-invoices name the business', 403, 'FORBIDDEN');
  const named = identity({ vatNumber: OUR_VAT, einvoiceScheme: '9932', einvoiceId: OUR_VAT });
  expect(named, '[+] the owner records the business VAT number and e-invoicing address', 200);
  truthy('[+] ...they read back, and the name is untouched', data(named).vatNumber === OUR_VAT && data(named).einvoiceScheme === '9932' && data(named).einvoiceId === OUR_VAT && data(named).name === profile.name, data(named));

  // ── suppliers and their orders ────────────────────────────────────────────────
  expect(call('POST', `${P}/suppliers`, { token: owner, body: { name: `Half ${stamp}`, currency: 'GBP', einvoiceScheme: '0088' } }), '[-] a supplier with half an e-invoicing address is refused', 400, 'PURCHASE_EINVOICE_ADDRESS_INVALID');
  const acme = supplier('Acme Fruit', { vatNumber: 'GB555555555', vatRegistered: true, countryCode: 'GB' });
  const farm = supplier('Orchard Farm', { countryCode: 'GB' });
  const po1 = receivedOrder(acme.id, 10);
  const po2 = receivedOrder(acme.id, 4);
  const po3 = receivedOrder(acme.id, 10);
  const farmPo = receivedOrder(farm.id, 20);

  // ── captured: a UBL invoice naming its order and order line ──────────────────
  const first = invoiceXml({ number: `A1-${stamp}`, orderRef: po1.id, orderLine: po1.lineId });
  const r1 = upload(first);
  expect(r1, '[+] a supplier\'s UBL invoice naming its order and order line is received', 201);
  const e1 = data(r1);
  truthy('[+] ...read as UBL with no rule broken, from Acme, for its order, and captured', e1.status === 'CAPTURED' && e1.container === 'XML' && e1.syntax === 'UBL' && (e1.violations || []).length === 0 && e1.supplierId === acme.id && e1.poId === po1.id && Boolean(e1.supplierInvoiceId), e1);
  truthy('[+] ...its line matched by the order line it references', (e1.lines || [])[0]?.poLineId === po1.lineId && e1.lines[0].matchedBy === 'ORDER_LINE', e1.lines);
  const keyed = data(call('GET', `${P}/supplier-invoices/${e1.supplierInvoiceId}`, { token: owner }));
  truthy('[+] ...into the three-way match as a keyed invoice goes: matched, 25.00 net', keyed.status === 'MATCHED' && Number(keyed.netAmount) === 25 && keyed.invoiceNumber === `A1-${stamp}`, keyed);
  const doc1 = original(e1.id);
  truthy('[+] the original downloads as it arrived, named, never sniffed', doc1.status === 200 && doc1.body === first && String(doc1.headers['Content-Type']).startsWith('application/xml') && String(doc1.headers['Content-Disposition']).includes(`A1-${stamp}.xml`) && doc1.headers['X-Content-Type-Options'] === 'nosniff', { status: doc1.status, headers: doc1.headers });
  const again = upload(first);
  truthy('[abuse] the same bytes sent again are the first receipt, not a second', again.status === 200 && data(again).alreadyReceived === true && data(again).id === e1.id, { status: again.status, body: String(again.body).slice(0, 300) });
  const resent = upload(invoiceXml({ number: `A1-${stamp}`, orderRef: po1.id, orderLine: po1.lineId, itemCode: 'RESENT' }));
  truthy('[abuse] the same invoice number in another file is a duplicate', resent.status === 201 && data(resent).status === 'DUPLICATE', data(resent));
  truthy('[abuse] ...and the order still has one invoice', invoicesOn(po1.id).length === 1, invoicesOn(po1.id));

  // ── a Factur-X PDF crosses the gateway and waits for its order ───────────────
  const r2 = upload(FACTUR_X, owner, 'application/pdf');
  expect(r2, '[+] a Factur-X PDF is received through the gateway', 201);
  const e2 = data(r2);
  truthy('[+] ...read from the CII inside it, from the supplier its VAT number names, waiting for its order', e2.container === 'PDF' && e2.syntax === 'CII' && e2.supplierId === acme.id && e2.status === 'NEEDS_ORDER' && Boolean(e2.problem), e2);
  const doc2 = original(e2.id, owner, true);
  truthy('[+] ...and the PDF comes back byte for byte', doc2.status === 200 && sameBytes(doc2.body, FACTUR_X) && String(doc2.headers['Content-Type']).startsWith('application/pdf'), { status: doc2.status, headers: doc2.headers });
  expect(match(e2.id, { poId: farmPo.id }), '[-] an order that is not the supplier\'s is refused', 400, 'PURCHASE_EINVOICE_ORDER_NOT_SUPPLIERS');
  expect(match(e2.id, { poId: po2.id, lines: [{ position: 1, poLineId: po1.lineId }] }), '[-] an order line from another order is refused', 400, 'PURCHASE_EINVOICE_LINE_NOT_ON_ORDER');
  expect(match(e2.id, { poId: po2.id, lines: [{ position: 3, poLineId: po2.lineId }] }), '[-] a line the invoice does not have is refused', 400, 'PURCHASE_EINVOICE_LINE_UNKNOWN');
  expect(match(e2.id, { poId: po2.id, lines: [{ position: 1, poLineId: po2.lineId }, { position: 1, poLineId: po2.lineId }] }), '[-] a line chosen twice is refused', 400, 'PURCHASE_EINVOICE_LINE_CHOSEN_TWICE');
  expect(match(e2.id, { poId: po2.id }, rival.owner.token), '[-] another tenant cannot match it', 404, 'PURCHASE_EINVOICE_NOT_FOUND');
  truthy('[-] ...nor did any refusal change it', data(einvoice(e2.id)).status === 'NEEDS_ORDER', data(einvoice(e2.id)));
  const m2 = match(e2.id, { poId: po2.id, lines: [{ position: 1, poLineId: po2.lineId }], remember: true });
  expect(m2, '[+] a person picks its order and line, remembering them', 200);
  truthy('[+] ...and it is captured, its line matched by that person', data(m2).status === 'CAPTURED' && data(m2).poId === po2.id && (data(m2).lines || [])[0]?.matchedBy === 'PERSON' && invoicesOn(po2.id).length === 1, data(m2));

  // ── what was remembered matches the next invoice; what was not waits ─────────
  const untaught = data(upload(invoiceXml({ number: `A3-${stamp}`, orderRef: po3.id, itemCode: 'UNTAUGHT' })));
  truthy('[-] a line whose code nobody taught waits for a person, with the reason', untaught.status === 'NEEDS_LINES' && Boolean(untaught.problem) && !untaught.supplierInvoiceId, untaught);
  const taught = data(upload(invoiceXml({ number: `A4-${stamp}`, orderRef: po3.id })));
  truthy('[+] a line carrying the item code a person matched before is matched on its own and captured', taught.status === 'CAPTURED' && (taught.lines || [])[0]?.matchedBy === 'ITEM_CODE' && (taught.lines || [])[0]?.poLineId === po3.lineId, taught);

  // ── refusing ──────────────────────────────────────────────────────────────────
  expect(refuse(untaught.id, 'Not our order', storekeeper.token), '[-] a storekeeper cannot refuse an e-invoice', 403, 'PERMISSION_DENIED');
  expect(refuse(untaught.id, ''), '[-] a refusal needs a reason', 400, 'VALIDATION_FAILED');
  const refused = refuse(untaught.id, 'Not our order');
  expect(refused, '[+] a manager refuses it with the reason', 200);
  truthy('[+] ...it is kept, refused, and never captured', data(refused).status === 'REFUSED' && data(refused).decisionReason === 'Not our order' && !data(refused).supplierInvoiceId, data(refused));
  expect(refuse(untaught.id, 'Again'), '[-] a settled e-invoice cannot be refused again', 409, 'PURCHASE_EINVOICE_SETTLED');
  expect(match(untaught.id, { poId: po3.id }), '[-] nor matched', 409, 'PURCHASE_EINVOICE_SETTLED');

  // ── a sender nobody knows: matched once, then known by its address ───────────
  const glnA = gln(`579${digits}1`);
  const unknown = data(upload(invoiceXml({ number: `F1-${stamp}`, orderRef: farmPo.id, orderLine: farmPo.lineId, sellerVat: `GB7${digits}`, sellerGln: glnA })));
  truthy('[-] an invoice from a VAT number and address no supplier holds waits for its supplier', unknown.status === 'NEEDS_SUPPLIER' && !unknown.supplierId && Boolean(unknown.problem), unknown);
  const wrong = data(match(unknown.id, { supplierId: acme.id }));
  truthy('[-] naming a supplier whose order it is not leaves it waiting for its order, saying why', wrong.status === 'NEEDS_ORDER' && String(wrong.problem).includes('not one of'), wrong);
  const known = match(unknown.id, { supplierId: farm.id, remember: true });
  expect(known, '[+] a person names the supplier that sent it, remembering its address', 200);
  truthy('[+] ...and it is captured against the order it names', data(known).status === 'CAPTURED' && data(known).supplierId === farm.id && data(known).poId === farmPo.id, data(known));
  const farmNow = (data(call('GET', `${P}/suppliers`, { token: owner })) || []).find((s) => s.id === farm.id) || {};
  truthy('[+] ...the supplier now has that e-invoicing address', farmNow.einvoiceScheme === '0088' && farmNow.einvoiceId === glnA, farmNow);
  const byAddress = data(upload(invoiceXml({ number: `F2-${stamp}`, orderRef: farmPo.id, orderLine: farmPo.lineId, sellerVat: `GB8${digits}`, sellerGln: glnA })));
  truthy('[+] its next invoice is known by the address it came from, whatever VAT number it gives, and captured', byAddress.status === 'CAPTURED' && byAddress.supplierId === farm.id && invoicesOn(farmPo.id).length === 2, byAddress);
  expect(call('POST', `${P}/suppliers`, { token: owner, body: { name: `Copycat ${stamp}`, currency: 'GBP', einvoiceScheme: '0088', einvoiceId: glnA } }), '[abuse] another supplier cannot take an address a supplier already sends from', 409, 'PURCHASE_SUPPLIER_EINVOICE_ADDRESS_TAKEN');

  // ── a credit note closes the return it credits, for someone who may record one ─
  let raised = null;
  const waited = poll(60, () => {
    const res = call('POST', `${P}/vendor-returns`, { token: owner, idem: true, body: { poId: po1.id, reason: 'DAMAGED', notes: 'crushed', lines: [{ variantId, qty: 2 }] } });
    if (res.status === 201) raised = data(res);
    return res.status === 201 || res.status !== 422;
  }, 2);
  truthy('[+] two apples go back to Acme on a debit note, once the stock has reached inventory', waited >= 0 && raised && raised.status === 'RAISED', raised);
  const byStorekeeper = upload(creditNoteXml({ number: `CN1-${stamp}`, preceding: `A1-${stamp}` }), storekeeper.token);
  truthy('[-] a storekeeper\'s upload of the credit note does not record it: it waits for someone who may', byStorekeeper.status === 201 && data(byStorekeeper).creditNote === true && data(byStorekeeper).status === 'NEEDS_DECISION', data(byStorekeeper));
  const credited = match(data(byStorekeeper).id, {}, manager.token);
  expect(credited, '[+] a manager finishes it', 200);
  truthy('[+] ...and the credit note closes the return it credits', data(credited).status === 'CREDITED' && data(credited).vendorReturnId === raised?.id, data(credited));
  truthy('[+] ...which now reads credited', data(call('GET', `${P}/vendor-returns/${raised?.id}`, { token: owner })).status === 'CREDITED');
  const second = data(upload(creditNoteXml({ number: `CN2-${stamp}`, preceding: `A1-${stamp}` })));
  truthy('[abuse] a second credit note finds no open return to close', second.status !== 'CREDITED' && !second.vendorReturnId, second);

  // ── kept, not captured: rules broken, or addressed to another business ────────
  const broken = data(upload(invoiceXml({ number: `B1-${stamp}`, orderRef: po3.id, orderLine: po3.lineId }).split('<cbc:PayableAmount currencyID="GBP">30.00').join('<cbc:PayableAmount currencyID="GBP">31.00')));
  truthy('[-] an invoice whose amount to pay does not add up is kept as not compliant, the rule named', broken.status === 'NOT_COMPLIANT' && (broken.violations || []).some((v) => v.severity === 'FATAL') && !broken.supplierInvoiceId, broken);
  expect(match(broken.id, { poId: po3.id }), '[-] a non-compliant invoice cannot be matched', 409, 'PURCHASE_EINVOICE_NOT_COMPLIANT');
  const elsewhere = data(upload(invoiceXml({ number: `M1-${stamp}`, orderRef: po3.id, orderLine: po3.lineId, buyerVat: 'GB987654321' })));
  truthy('[abuse] an invoice addressed to another business is set aside', elsewhere.status === 'MISDIRECTED' && !elsewhere.supplierInvoiceId, elsewhere);
  expect(match(elsewhere.id, { poId: po3.id }), '[abuse] ...and cannot be matched to this business\'s order', 409, 'PURCHASE_EINVOICE_MISDIRECTED');
  truthy('[abuse] ...so the order still has only the invoice it was billed', invoicesOn(po3.id).length === 1, invoicesOn(po3.id));

  // ── hostile documents ─────────────────────────────────────────────────────────
  expect(upload('<?xml version="1.0"?><!DOCTYPE Invoice [<!ENTITY x SYSTEM "file:///etc/passwd">]><Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2">&x;</Invoice>'), '[abuse] a document carrying a DTD is refused unread', 400, 'PURCHASE_EINVOICE_DTD_REFUSED');
  expect(upload('<Order/>'), '[-] XML that is not an invoice is refused', 400, 'PURCHASE_EINVOICE_NOT_AN_INVOICE');
  expect(upload('%PDF-1.7 not really', owner, 'application/pdf'), '[abuse] a file that only claims to be a PDF is refused', 400, 'PURCHASE_EINVOICE_PDF_UNREADABLE');
  expect(upload(''), '[-] an empty upload is refused', 400, 'PURCHASE_EINVOICE_EMPTY');
  expect(upload('{}', owner, 'application/json'), '[-] JSON is not an e-invoice', 415);
  expect(upload(invoiceXml({ number: `C1-${stamp}`, orderRef: po3.id, buyerRef: '4111111111111111' })), '[abuse] an invoice carrying a card number is refused at the gateway', 400, 'CARD_DATA_NOT_ACCEPTED');
  const padded = (bytes) => invoiceXml({ number: `P${bytes}-${stamp}`, orderRef: 'NO-ORDER' }).split('<cbc:BuyerReference>').join(`<!-- ${'x'.repeat(bytes)} --><cbc:BuyerReference>`);
  const big = upload(padded(1500000));
  truthy('[+] a 1.5 MB e-invoice is accepted: the upload route is not held to 1 MB', big.status === 201 && data(big).status === 'NEEDS_ORDER', { status: big.status, body: String(big.body).slice(0, 300) });
  expect(call('POST', `${P}/suppliers`, { token: owner, body: { name: 'x'.repeat(1500000), currency: 'GBP' } }), '[abuse] 1.5 MB to any other route is refused at the gateway', 413, 'PAYLOAD_TOO_LARGE');
  expect(upload(padded(12000000)), '[abuse] more than 10 MB of XML is refused unread', 413, 'PURCHASE_EINVOICE_TOO_LARGE');
  // The gateway's server stops reading past its limit: it answers 413 and closes the connection, and a
  // client still sending can see the close before the answer. Either way nothing may be kept, and the
  // gateway must still be up.
  const kept = () => (data(call('GET', `${EINV}?limit=100`, { token: owner })) || []).length;
  const keptBefore = kept();
  const huge = upload('x'.repeat(22000000));
  truthy('[abuse] more than 21 MB is refused at the gateway, answered 413 or cut off, and nothing is kept', [413, 0].includes(huge.status) && http.get(`${BASE}/health`).status === 200 && kept() === keptBefore, { status: huge.status, error: huge.error });

  // ── another tenant, and reading the inbox ────────────────────────────────────
  expect(einvoice(e1.id, rival.owner.token), '[-] another tenant cannot read an e-invoice', 404, 'PURCHASE_EINVOICE_NOT_FOUND');
  expect(original(e1.id, rival.owner.token), '[-] nor download its original', 404, 'PURCHASE_EINVOICE_NOT_FOUND');
  const rivalList = data(call('GET', EINV, { token: rival.owner.token }));
  truthy('[-] ...and lists none', Array.isArray(rivalList) && rivalList.length === 0, rivalList);
  expect(call('GET', `${EINV}?status=PAID`, { token: owner }), '[-] a status that does not exist is refused', 400, 'PURCHASE_EINVOICE_STATUS_INVALID');
  const captured = data(call('GET', `${EINV}?status=CAPTURED`, { token: owner }));
  truthy('[+] the captured ones are listed, newest first', Array.isArray(captured) && captured.length === 5 && captured.every((e) => e.status === 'CAPTURED') && captured[captured.length - 1].id === e1.id, captured);

  // ── ten people match the same invoice at once ────────────────────────────────
  const racePo = receivedOrder(farm.id, 10);
  const race = data(upload(invoiceXml({ number: `R1-${stamp}`, orderRef: racePo.id, orderLine: racePo.lineId, sellerVat: `GB6${digits}`, sellerGln: gln(`579${digits}2`) })));
  truthy('[abuse] an invoice from another unknown sender waits for its supplier', race.status === 'NEEDS_SUPPLIER', race);
  const url = `${BASE}${EINV}/${race.id}/match`;
  const params = { headers: { ...auth(owner), 'Content-Type': 'application/json' }, tags: { name: 'POST /e-invoices/{id}/match' } };
  const answers = http.batch(Array.from({ length: 10 }, () => ['POST', url, JSON.stringify({ supplierId: farm.id }), params]));
  const oks = answers.filter((r) => r.status === 200).length;
  const busy = answers.filter((r) => r.status === 409 && ['PURCHASE_EINVOICE_BUSY', 'PURCHASE_EINVOICE_SETTLED'].includes(errorCode(r))).length;
  truthy('[abuse] ten matches at once: one captures, nine are told it is taken', oks === 1 && busy === 9, answers.map((r) => `${r.status} ${errorCode(r) || ''}`));
  truthy('[abuse] ...and the order has exactly one invoice', data(einvoice(race.id)).status === 'CAPTURED' && invoicesOn(racePo.id).length === 1, invoicesOn(racePo.id));

  // ── where invoices are fetched from, and whether any could arrive (07.13) ────
  // Poland is the one network that delivers nothing: a buyer there asks, or receives nothing for
  // ever. This stack holds no KSeF endpoint, and the settings and the readiness check both say so
  // rather than leaving a shop with an inbox that is quietly empty.
  const INBOX = `${P}/admin/e-invoices/inbox`;
  const inboxSettings = (body, token = owner) => call('PUT', `${INBOX}/settings`, { token, body });
  const inboxReadiness = (token = owner) => call('GET', `${INBOX}/readiness`, { token });
  const NIP = '5260250274';

  const unset = inboxReadiness();
  expect(unset, '[+] a business that never chose where to fetch from is told what to do', 200);
  truthy('[+] ...and nothing is asked of any ministry', data(unset).ready === false && (data(unset).outstanding || []).some((o) => /choose where/.test(o)) && !data(unset).networkState, data(unset));

  expect(inboxSettings({ network: 'POST', provider: 'KSEF', providerAccount: NIP }), '[-] a network that does not exist is refused', 400, 'PURCHASE_INBOX_NETWORK_UNKNOWN');
  expect(inboxSettings({ network: 'KSEF', provider: 'ACCESS_POINT', providerAccount: NIP }), '[-] as is a provider that does not serve it', 400, 'PURCHASE_INBOX_PROVIDER_UNKNOWN');
  expect(inboxSettings({ network: 'KSEF', provider: 'KSEF', providerAccount: '123' }), '[-] KSeF knows a business by its NIP, and that is not one', 400, 'PURCHASE_INBOX_ACCOUNT_INVALID');
  expect(inboxSettings({ network: 'KSEF', provider: 'KSEF', providerAccount: NIP, secret: 'a-token' }), '[-] the ministry cannot be chosen on a deployment that holds no endpoint for it', 409, 'PURCHASE_INBOX_NOT_DEPLOYED');
  expect(inboxSettings({ network: 'KSEF', provider: 'SIMULATED', providerAccount: NIP }, storekeeper.token), '[-] a storekeeper does not choose where invoices are fetched from', 403, 'FORBIDDEN');
  expect(inboxReadiness(storekeeper.token), '[-] nor asks whether any could arrive', 403, 'FORBIDDEN');

  const standIn = inboxSettings({ network: 'KSEF', provider: 'SIMULATED', providerAccount: NIP });
  expect(standIn, '[+] the platform can stand in for the ministry, so the flow can be walked', 200);
  truthy('[+] ...and no token is asked for, nor given back', data(standIn).hasSecret === false && !/token/.test(JSON.stringify(data(standIn).providerSecret || '')), data(standIn));
  const standInReady = inboxReadiness();
  truthy('[+] and the check refuses to call that ready: nothing leaves the platform, so nothing arrives', data(standInReady).ready === false && data(standInReady).networkState === 'REFUSED' && /nothing arrives/.test(data(standInReady).networkDetail || ''), data(standInReady));
  const fetched = call('POST', `${INBOX}/fetch`, { token: owner, body: {} });
  expect(fetched, '[+] a fetch answers plainly rather than as an empty success', 200);
  truthy('[+] ...nothing left it, so nothing arrived', data(fetched).received === 0 && (data(fetched).notes || []).some((n) => /nothing left it/.test(n)), data(fetched));
  truthy('[abuse] a rival asking sees its own inbox, never this one', data(inboxReadiness(rival.owner.token)).provider !== 'SIMULATED', data(inboxReadiness(rival.owner.token)));

  completed.add(1);
}
