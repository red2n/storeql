// Invoices to business buyers (18.9), through the gateway. The business records how its invoices name
// it; a customer is recorded as a VAT-registered business with a billing address. A till sale to that
// customer, paid for in cash, is invoiced without anyone asking: EN 16931 UBL as issued — Peppol BIS
// Billing 3.0 when both parties have a network address — and CII and Factur-X written from it, each
// downloaded with its name and not sniffed. A till discount is stated net, so the VAT follows what was
// paid. A return against an invoiced sale is credited, naming the invoice. Numbers run without a gap
// past every refusal. An Indian business's document is also written for the Invoice Registration
// Portal, or kept with what the portal would refuse. Refused: a basket never paid for, a sale naming
// nobody, a customer not registered or without an address, a document the portal would refuse asked
// for as IRP, an unknown format, a cursor that is not a document. Abuse: ten requests at once for one
// sale, a cashier issuing, a storekeeper reading, another tenant's reads, downloads and requests, a
// return that was never invoiced credited, a path that is not an id.
//
//   k6/run.sh einvoice-outbound
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import {
  ALL_CHECKS_PASS,
  BASE,
  call,
  data,
  expect,
  must,
  poll,
  priceVariants,
  sellableVariant,
  sellingTenant,
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
  setupTimeout: '5m',
};

const O = '/api/order-svc';
const OUR_VAT = 'GB123456789';
const BUYER_VAT = 'GB555555555';
const OUR_GSTIN = '27AAPFU0939F1ZV';
const BUYER_GSTIN = '29AAGCB7383J1Z4';

/** Records how the business's invoices name it: its VAT number, and an e-invoicing address. */
const identity = (tenant, body) => {
  const token = tenant.owner.token;
  const profile = must(call('GET', '/api/tenant-svc/admin/tenant', { token }), 200, 'tenant profile');
  return must(
    call('PUT', '/api/tenant-svc/admin/tenant', { token, body: { businessName: profile.name, legalName: profile.legalName, ...body } }),
    200,
    'tenant identity'
  );
};

/** A customer on the shop's record, with a billing address when given one, and a VAT registration when given one. */
const customer = (tenant, label, { address, vat } = {}) => {
  const token = tenant.owner.token;
  const run = Date.now().toString(36);
  const c = must(
    call('POST', '/api/customer-svc/customers', { token, body: { email: `${label}-${run}@k6.storeql.test`, firstName: label, lastName: 'Buyer' } }),
    201,
    `customer ${label}`
  );
  if (address) must(call('POST', `/api/customer-svc/customers/${c.id}/addresses`, { token, body: { type: 'BILLING', ...address } }), 201, `address ${label}`);
  if (vat) must(call('POST', '/api/pricing-svc/customer-vat-status', { token, body: { customerId: c.id, vatRegistered: true, ...vat } }), [200, 201], `vat status ${label}`);
  return c.id;
};

export function setup() {
  const gb = sellingTenant('einvoice-out', { price: '10.00' });
  identity(gb.tenant, { vatNumber: OUR_VAT, einvoiceScheme: '9932', einvoiceId: OUR_VAT });
  const leeds = { line1: '2 Mill Lane', city: 'Leeds', country: 'GB', pincode: 'LS1 4AB' };
  const cafe = customer(gb.tenant, 'cafe', { address: leeds, vat: { vatNumber: BUYER_VAT, countryCode: 'GB', legalName: 'Cafe Leeds Ltd', einvoiceScheme: '9932', einvoiceId: BUYER_VAT } });
  const shopper = customer(gb.tenant, 'shopper', { address: leeds });
  const nowhere = customer(gb.tenant, 'nowhere', { vat: { vatNumber: 'GB222222222', countryCode: 'GB', legalName: 'Nowhere Ltd' } });

  // An Indian business: a GSTIN for a VAT number, 18% GST, and an HSN code on what it sells.
  const india = sellingTenant('einvoice-in', { country: 'IN', currency: 'INR', price: '100.00' });
  identity(india.tenant, { vatNumber: OUR_GSTIN });
  must(
    call('PUT', `/api/product-svc/admin/products/variants/${india.variantId}/compliance`, { token: india.tenant.owner.token, body: { hsnCode: '1006', soldBy: 'EACH' } }),
    200,
    'HSN code'
  );
  const { variantId: noHsn } = sellableVariant(india.tenant, 'Jaggery');
  priceVariants(india.tenant, [noHsn], '50.00');
  const bengaluru = customer(india.tenant, 'stores', {
    address: { line1: '4 Residency Road', city: 'Bengaluru', country: 'IN', pincode: '560025' },
    vat: { vatNumber: BUYER_GSTIN, countryCode: 'IN', legalName: 'Bengaluru Stores Pvt Ltd' },
  });
  return { gb, cafe, shopper, nowhere, india, noHsn, bengaluru };
}

export default function ({ gb, cafe, shopper, nowhere, india, noHsn, bengaluru }) {
  const t = gb.tenant.owner.token;
  const store = gb.store.id;
  const auth = (token) => ({ Authorization: `Bearer ${token}` });
  const inv = (path, opts = {}) => call(opts.method || 'GET', `${O}/admin${path}`, { token: opts.token || t, body: opts.body });
  const issue = (orderId, token = t) => call('POST', `${O}/admin/orders/${orderId}/invoice`, { token, body: {} });
  const documentsOf = (orderId, token = t) => data(call('GET', `${O}/admin/orders/${orderId}/invoices`, { token }));
  const document = (id, format, token = t, binary = false) =>
    http.get(`${BASE}${O}/admin/sales-invoices/${id}/document${format ? `?format=${format}` : ''}`, { headers: auth(token), responseType: binary ? 'binary' : 'text', tags: { name: 'GET /admin/sales-invoices/{id}/document' } });

  // A till sale, paid for in cash, so it completes the way a till's sales do: on PaymentCaptured.
  const sell = (who, { customerId, qty = 2, extra = {} } = {}) => {
    const token = who.tenant.owner.token;
    const order = must(
      call('POST', `${O}/orders`, {
        token,
        idem: true,
        body: { storeId: who.store.id, channel: 'POS', fulfilmentType: 'INSTORE', paymentMethod: 'CASH', customerId, items: [{ variantId: who.variantId, qty }], ...extra },
      }),
      201,
      'till sale'
    );
    must(
      call('POST', '/api/payment-svc/payments', { token, idem: true, body: { orderId: order.id, amount: order.total, method: 'CASH', storeId: who.store.id, currency: who.tenant.currency } }),
      [200, 201],
      'cash payment'
    );
    truthy(`[+] the sale ${order.id.slice(0, 8)} completes once paid for`, poll(45, () => data(call('GET', `${O}/orders/${order.id}`, { token })).status === 'FULFILLED') >= 0, order.id);
    return order;
  };
  // poll answers with the seconds it took, so the document is caught on the way.
  const documentOf = (orderId, kind, token = t) => {
    let found;
    poll(45, () => (found = (documentsOf(orderId, token) || []).find((d) => d.kind === kind)));
    return found;
  };
  const invoiceOf = (orderId, token = t) => documentOf(orderId, 'INVOICE', token);

  // ── the invoice ──────────────────────────────────────────────────────────────────────────────────
  const sale = sell(gb, { customerId: cafe });
  const invoice = invoiceOf(sale.id);
  truthy('[+] a till sale to a registered business is invoiced without anyone asking', invoice, documentsOf(sale.id));
  truthy('[+] ...two at 10.00 and 20%: net 20.00, VAT 4.00, 24.00 to pay', invoice && invoice.netAmount === 20 && invoice.vatAmount === 4 && invoice.payableAmount === 24, invoice);
  truthy('[+] ...numbered in the invoice series, naming the buyer as registered', invoice && /^INV\/\d{4}\/\d{6}$/.test(invoice.fullNumber) && invoice.buyerName === 'Cafe Leeds Ltd' && invoice.buyerVatId === BUYER_VAT, invoice);
  truthy('[+] ...as Peppol BIS Billing 3.0, both parties having a network address', invoice && invoice.peppol === true && invoice.formats.join() === 'UBL,CII,FACTURX', invoice);
  const again = issue(sale.id);
  expect(again, '[+] asking again is answered with the same document', 200);
  truthy('[+] ...not a second number', data(again).id === invoice.id && documentsOf(sale.id).length === 1, data(again));
  expect(inv(`/sales-invoices/${invoice.id}`), '[+] the document is read by its id', 200);

  const ubl = document(invoice.id);
  expect(ubl, '[+] the UBL downloads as issued', 200);
  truthy('[+] ...as XML, named by its number, not sniffed, not cached', ubl.headers['Content-Type'] && ubl.headers['Content-Type'].startsWith('application/xml') && ubl.headers['Content-Disposition'] === `attachment; filename="${invoice.fullNumber.replace(/\//g, '-')}.xml"` && ubl.headers['X-Content-Type-Options'] === 'nosniff' && ubl.headers['Cache-Control'] === 'no-store', ubl.headers);
  truthy('[+] ...carrying the number, both VAT numbers and the Peppol customization', ubl.body.includes(invoice.fullNumber) && ubl.body.includes(OUR_VAT) && ubl.body.includes(BUYER_VAT) && ubl.body.includes('peppol.eu:2017:poacc:billing:3.0'), ubl.body.slice(0, 400));
  const cii = document(invoice.id, 'cii');
  expect(cii, '[+] the same document downloads as CII', 200);
  truthy('[+] ...a CrossIndustryInvoice with the same number', cii.body.includes('CrossIndustryInvoice') && cii.body.includes(invoice.fullNumber), cii.body.slice(0, 300));
  const pdf = document(invoice.id, 'FACTURX', t, true);
  expect(pdf, '[+] and as a Factur-X PDF', 200);
  truthy('[+] ...a PDF, named .pdf', String.fromCharCode(...new Uint8Array(pdf.body.slice(0, 5))) === '%PDF-' && pdf.headers['Content-Disposition'].endsWith('.pdf"'), pdf.headers);
  expect(document(invoice.id, 'IRP'), '[-] the portal\'s document belongs to an Indian business', 404, 'ORDER_INVOICE_IRP_NOT_APPLICABLE');
  expect(document(invoice.id, 'DOCX'), '[-] an unknown format is refused', 400, 'ORDER_INVOICE_FORMAT_UNKNOWN');

  // ── the till's discount ──────────────────────────────────────────────────────────────────────────
  const discounted = sell(gb, { customerId: cafe, extra: { discountAmount: 3.0, discountReason: 'Regular trade customer' } });
  const netted = invoiceOf(discounted.id);
  truthy('[+] a till discount off the total is stated net: 24.00 less 3.00 is 17.50 net, 3.50 VAT, 21.00 to pay', netted && netted.payableAmount === 21 && netted.netAmount === 17.5 && netted.vatAmount === 3.5, netted);

  // ── the credit note ──────────────────────────────────────────────────────────────────────────────
  const returned = must(call('POST', `${O}/orders/${sale.id}/returns`, { token: t, idem: true, body: { reason: 'one bag split', items: [{ variantId: gb.variantId, qty: 1, condition: 'DAMAGED' }] } }), 201, 'return');
  const credit = documentOf(sale.id, 'CREDIT_NOTE');
  truthy('[+] a return against the invoiced sale is credited without anyone asking', credit, documentsOf(sale.id));
  truthy('[+] ...one at 10.00 and 20%, in the credit note series, naming the invoice it credits', credit && /^CRN\/\d{4}\/\d{6}$/.test(credit.fullNumber) && credit.precedingInvoiceId === invoice.id && credit.returnId === returned.id && credit.payableAmount === 12, credit);
  const creditAgain = call('POST', `${O}/admin/returns/${returned.id}/credit-note`, { token: t, body: {} });
  expect(creditAgain, '[+] asking for it by hand is answered with the same credit note', 200);
  truthy('[+] ...and the return is credited once', data(creditAgain).id === credit.id && documentsOf(sale.id).length === 2, data(creditAgain));
  const creditUbl = document(credit.id);
  truthy('[+] the credit note downloads, typed 381 and naming the invoice', creditUbl.status === 200 && creditUbl.body.includes('<cbc:CreditNoteTypeCode>381') && creditUbl.body.includes(invoice.fullNumber), creditUbl.body.slice(0, 300));

  // ── refusals ─────────────────────────────────────────────────────────────────────────────────────
  const unpaid = must(call('POST', `${O}/orders`, { token: t, idem: true, body: { storeId: store, channel: 'POS', fulfilmentType: 'INSTORE', paymentMethod: 'CASH', customerId: cafe, items: [{ variantId: gb.variantId, qty: 1 }] } }), 201, 'unpaid basket');
  expect(issue(unpaid.id), '[-] a basket never paid for is not invoiced', 409, 'ORDER_INVOICE_NOT_SOLD');
  const anonymous = sell(gb, {});
  expect(issue(anonymous.id), '[-] a sale naming nobody has no one to invoice', 409, 'ORDER_INVOICE_NO_BUYER');
  const privately = sell(gb, { customerId: shopper });
  expect(issue(privately.id), '[-] a customer not recorded as a registered business gets a receipt, not an invoice', 409, 'ORDER_INVOICE_BUYER_NOT_REGISTERED');
  const homeless = sell(gb, { customerId: nowhere });
  expect(issue(homeless.id), '[-] a registered business with no address cannot be invoiced yet', 409, 'ORDER_INVOICE_BUYER_ADDRESS_MISSING');
  expect(issue('01990000-0000-7000-8000-000000000000'), '[-] an unknown sale is not found', 404, 'ORDER_NOT_FOUND');
  expect(inv('/sales-invoices/01990000-0000-7000-8000-000000000000'), '[-] nor an unknown document', 404, 'ORDER_INVOICE_NOT_FOUND');
  expect(inv('/sales-invoices?after=not-a-cursor'), '[-] a cursor that is not a document is refused', 400, 'INVALID_UUID');
  expect(call('POST', `${O}/admin/returns/01990000-0000-7000-8000-000000000000/credit-note`, { token: t, body: {} }), '[-] an unknown return cannot be credited', 404, 'ORDER_RETURN_NOT_FOUND');
  const uninvoicedReturn = must(call('POST', `${O}/orders/${privately.id}/returns`, { token: t, idem: true, body: { reason: 'changed mind', items: [{ variantId: gb.variantId, qty: 1, condition: 'SEALED' }] } }), 201, 'return on a receipted sale');
  expect(call('POST', `${O}/admin/returns/${uninvoicedReturn.id}/credit-note`, { token: t, body: {} }), '[-] a return on a sale that was never invoiced has nothing to credit', 409, 'ORDER_CREDIT_NOTE_NO_INVOICE');
  truthy('[-] ...and none of those took a document', [unpaid, anonymous, privately, homeless].every((o) => documentsOf(o.id).length === 0), 'documents');

  // ── abuse ────────────────────────────────────────────────────────────────────────────────────────
  expect(issue(sale.id, gb.cashier.token), '[abuse] a cashier cannot issue an invoice', 403);
  expect(inv('/sales-invoices', { token: gb.storekeeper.token }), '[abuse] a storekeeper cannot read the register', 403);
  expect(document(invoice.id, undefined, gb.cashier.token), '[abuse] nor download one', 403);
  const rival = gb.rival.owner.token;
  expect(inv(`/sales-invoices/${invoice.id}`, { token: rival }), '[abuse] another tenant does not see the document', 404);
  expect(document(invoice.id, undefined, rival), '[abuse] nor downloads it', 404);
  expect(issue(sale.id, rival), '[abuse] nor invoices the sale', 404);
  truthy('[abuse] nor sees it on the sale', (documentsOf(sale.id, rival) || []).length === 0, 'rival documents');
  const path = inv('/sales-invoices/not-an-id');
  truthy('[abuse] a path that is not an id is refused, not an error', path.status === 400 || path.status === 404, path.status);
  const raced = sell(gb, { customerId: cafe, qty: 1 });
  const atOnce = http.batch(Array.from({ length: 10 }, () => ['POST', `${BASE}${O}/admin/orders/${raced.id}/invoice`, '{}', { headers: { ...auth(t), 'Content-Type': 'application/json' }, tags: { name: 'POST /admin/orders/{id}/invoice' } }]));
  const ids = new Set(atOnce.map((r) => (r.status === 200 ? data(r).id : `HTTP ${r.status}`)));
  truthy('[abuse] ten requests at once for one sale answer with one document', ids.size === 1 && atOnce.every((r) => r.status === 200) && documentsOf(raced.id).length === 1, [...ids]);

  // ── the register ─────────────────────────────────────────────────────────────────────────────────
  const page1 = inv('/sales-invoices?limit=2');
  expect(page1, '[+] the register reads newest first, a page at a time', 200);
  const cursor = page1.json('meta.nextCursor');
  const page2 = cursor ? inv(`/sales-invoices?limit=100&after=${cursor}`) : null;
  const all = [...(data(page1) || []), ...(page2 ? data(page2) || [] : [])];
  const numbers = all.filter((d) => d.kind === 'INVOICE').map((d) => d.number).sort((a, b) => a - b);
  // Three invoices — the sale, the discounted sale, the raced one — and one credit note.
  truthy('[+] ...two on the first page, the rest after the cursor', data(page1).length === 2 && page2 && page2.status === 200 && all.length === 4, { first: data(page1).length, all: all.length });
  truthy('[+] the invoice numbers run 1, 2, 3 with no gap, past every refusal', numbers.join() === '1,2,3', numbers);

  // ── India ────────────────────────────────────────────────────────────────────────────────────────
  const ti = india.tenant.owner.token;
  const indian = sell(india, { customerId: bengaluru, qty: 1 });
  const irpInvoice = invoiceOf(indian.id, ti);
  truthy('[+] an Indian business\'s sale to a registered buyer is invoiced, 100.00 at 18% GST', irpInvoice && irpInvoice.payableAmount === 118 && irpInvoice.buyerVatId === BUYER_GSTIN, irpInvoice);
  truthy('[+] ...and written for the Invoice Registration Portal', irpInvoice && irpInvoice.formats.includes('IRP') && irpInvoice.irpProblems.length === 0, irpInvoice);
  const irp = document(irpInvoice.id, 'IRP', ti);
  expect(irp, '[+] the portal\'s INV-01 downloads as JSON', 200);
  truthy('[+] ...schema 1.1, Mumbai to Bengaluru as IGST, the item by its HSN code', irp.body.includes('"Version":"1.1"') && irp.body.includes(`"Gstin":"${OUR_GSTIN}"`) && irp.body.includes('"IgstAmt":18') && irp.body.includes('"HsnCd":"1006"'), irp.body.slice(0, 400));
  const objected = sell({ ...india, variantId: noHsn }, { customerId: bengaluru, qty: 1 });
  const withoutHsn = invoiceOf(objected.id, ti);
  truthy('[+] an item with no HSN code is invoiced, with what the portal would refuse kept', withoutHsn && !withoutHsn.formats.includes('IRP') && withoutHsn.irpProblems.some((p) => p.includes('HSN')), withoutHsn);
  expect(document(withoutHsn.id, 'IRP', ti), '[-] ...and the portal\'s document is refused until it is put right', 409, 'ORDER_INVOICE_IRP_NOT_READY');

  completed.add(1);
}
