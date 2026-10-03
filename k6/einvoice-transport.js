// The e-invoicing transport seam (07.13, 18.9), through the gateway, over the simulated network —
// the only provider a stack with no access-point contract can choose, and the suite says so: the
// real access point is deployed, offered, and refused as unconfigured. The owner chooses Peppol; a
// till sale to a registered buyer is invoiced and the document is delivered without anyone asking,
// with the network's reference kept; a buyer the network does not know is refused for good, and sent
// again by hand only to be refused again; a buyer whose access point is slow is taken and asked after
// until delivered; a buyer with no electronic address has nowhere to receive. The outbox lists and
// pages every attempt. Refused: a network or provider that does not exist, a provider without
// credentials, Peppol for a business with no address, an account too long, sending what was
// delivered, sending with no network chosen, a status that does not exist, a cashier choosing or
// reading. Abuse: ten sends at once for one document sending once, another tenant's reads and sends,
// a path that is not an id. Delivery in: a sale to a business on this platform is delivered into
// that business's inbox, where it waits for its supplier by the network it came over with the
// network's reference; the delivery route itself, as an access point calls it, is tried with the
// same document (already there), without the key, with a wrong one, with a staff token instead,
// for a network that does not deliver in, for a buyer nobody holds, with no document, and ten
// times at once.
//
//   k6/run.sh einvoice-transport
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
const TRANSPORT = `${O}/admin/einvoicing/transport`;
const BUYER_VAT = 'GB555555555';
const P = '/api/purchase-svc';
// The key every network presents when it delivers; docker-compose's default unless the stack sets one.
const DELIVERY_KEY = __ENV.STOREQL_EINVOICE_INBOUND_KEY || 'dev-einvoice-inbound-key';

// A GLN (GS1 GTIN-13) with its check digit, as Peppol's 0088 scheme requires.
const gln = (first12) => {
  let sum = 0;
  for (let i = 0; i < 12; i++) sum += Number(first12[11 - i]) * (i % 2 === 0 ? 3 : 1);
  return `${first12}${(10 - (sum % 10)) % 10}`;
};

const identity = (tenant, body) => {
  const token = tenant.owner.token;
  const profile = must(call('GET', '/api/tenant-svc/admin/tenant', { token }), 200, 'tenant profile');
  return must(
    call('PUT', '/api/tenant-svc/admin/tenant', { token, body: { businessName: profile.name, legalName: profile.legalName, ...body } }),
    200,
    'tenant identity'
  );
};

const customer = (tenant, label, endpointId, { scheme = '9932', vatNumber = BUYER_VAT } = {}) => {
  const token = tenant.owner.token;
  const run = Date.now().toString(36);
  const c = must(
    call('POST', '/api/customer-svc/customers', { token, body: { email: `${label}-${run}@k6.storeql.test`, firstName: label, lastName: 'Buyer' } }),
    201,
    `customer ${label}`
  );
  must(call('POST', `/api/customer-svc/customers/${c.id}/addresses`, { token, body: { type: 'BILLING', line1: '2 Mill Lane', city: 'Leeds', country: 'GB', pincode: 'LS1 4AB' } }), 201, `address ${label}`);
  const vat = { customerId: c.id, vatRegistered: true, vatNumber, countryCode: 'GB', legalName: `${label} Ltd` };
  if (endpointId) Object.assign(vat, { einvoiceScheme: scheme, einvoiceId: endpointId });
  must(call('POST', '/api/pricing-svc/customer-vat-status', { token, body: vat }), [200, 201], `vat status ${label}`);
  return c.id;
};

export function setup() {
  const gb = sellingTenant('einvoice-tx', { price: '10.00' });
  // A GLN of this suite's own: the outbound suite's business keeps its own address.
  identity(gb.tenant, { vatNumber: 'GB123456789', einvoiceScheme: '0088', einvoiceId: gln(`5790000${String(Date.now() % 100000).padStart(5, '0')}`) });
  const noaddr = sellingTenant('einvoice-tx-noaddr', { price: '10.00' });
  identity(noaddr.tenant, { vatNumber: 'GB987654321' });
  const cafe = customer(gb.tenant, 'cafe', BUYER_VAT);
  const nobody = customer(gb.tenant, 'nobody', `${BUYER_VAT}REJECT`);
  const slow = customer(gb.tenant, 'slow', `${BUYER_VAT}LATER`);
  const offline = customer(gb.tenant, 'offline', null);
  // A business on this platform, with its own address and VAT number: what the simulated network
  // delivers to is its inbox.
  const inbox = sellingTenant('einvoice-tx-inbox', { price: '10.00' });
  const inboxGln = gln(`5790001${String((Date.now() + 7) % 100000).padStart(5, '0')}`);
  const INBOX_VAT = 'GB444444444';
  identity(inbox.tenant, { vatNumber: INBOX_VAT, einvoiceScheme: '0088', einvoiceId: inboxGln });
  const neighbour = customer(gb.tenant, 'neighbour', inboxGln, { scheme: '0088', vatNumber: INBOX_VAT });
  return { gb, noaddr, cafe, nobody, slow, offline, inbox, inboxGln, neighbour };
}

export default function ({ gb, noaddr, cafe, nobody, slow, offline, inbox, inboxGln, neighbour }) {
  const t = gb.tenant.owner.token;
  const auth = (token) => ({ Authorization: `Bearer ${token}` });
  const settings = (token = t) => call('GET', TRANSPORT, { token });
  const choose = (body, token = t) => call('PUT', TRANSPORT, { token, body });
  const send = (id, token = t) => call('POST', `${O}/admin/sales-invoices/${id}/transmissions`, { token, body: {} });
  const attempts = (id, token = t) => call('GET', `${O}/admin/sales-invoices/${id}/transmissions`, { token });
  const outbox = (query = '', token = t) => call('GET', `${O}/admin/einvoicing/transmissions${query}`, { token });
  const readiness = (token = t) => call('GET', `${O}/admin/einvoicing/readiness`, { token });
  const checkOf = (r, code) => (data(r).checks || []).find((c) => c.code === code) || {};
  const documentOf = (id) => data(call('GET', `${O}/admin/sales-invoices/${id}`, { token: t }));

  // A till sale, paid for in cash, invoiced on payment; the invoice once it exists.
  const invoiced = (customerId) => {
    const order = must(
      call('POST', `${O}/orders`, { token: t, idem: true, body: { storeId: gb.store.id, channel: 'POS', fulfilmentType: 'INSTORE', paymentMethod: 'CASH', customerId, items: [{ variantId: gb.variantId, qty: 1 }] } }),
      201,
      'till sale'
    );
    must(call('POST', '/api/payment-svc/payments', { token: t, idem: true, body: { orderId: order.id, amount: order.total, method: 'CASH', storeId: gb.store.id, currency: 'GBP' } }), [200, 201], 'cash payment');
    let found;
    poll(60, () => (found = (data(call('GET', `${O}/admin/orders/${order.id}/invoices`, { token: t })) || []).find((d) => d.kind === 'INVOICE')));
    truthy(`[+] the sale ${order.id.slice(0, 8)} is invoiced`, found, order.id);
    return found;
  };
  // The document's newest transmission once it is in one of the states; the worker runs every 15s.
  const transmissionOf = (id, states, seconds = 60) => {
    let found;
    poll(seconds, () => {
      const tr = documentOf(id).transmission;
      found = tr && states.includes(tr.status) ? tr : undefined;
      return found;
    });
    return found;
  };

  // ── what is offered ──────────────────────────────────────────────────────────────────────────────
  const offered = settings();
  expect(offered, '[+] the business starts sending nowhere, and is told what this deployment offers', 200);
  const s0 = data(offered);
  truthy('[+] ...no network yet, four to choose from, the simulated provider on each', s0.network === 'NONE' && s0.networks.join() === 'PEPPOL,FR_PDP,KSEF,IRP' && ['PEPPOL', 'FR_PDP', 'KSEF', 'IRP'].every((n) => (s0.providers[n] || []).includes('SIMULATED')), s0);
  truthy('[+] ...the Peppol access point deployed but not choosable: this stack holds no credentials', (s0.providers.PEPPOL || []).includes('ACCESS_POINT') && !(s0.available.PEPPOL || []).includes('ACCESS_POINT'), s0);
  truthy("[+] ...France's platform and India's portal likewise, and the portal takes the business's own credential", (s0.providers.FR_PDP || []).includes('PDP') && !(s0.available.FR_PDP || []).includes('PDP') && (s0.providers.IRP || []).includes('NIC') && !(s0.available.IRP || []).includes('NIC') && (s0.needingSecret.IRP || []).join() === 'NIC' && s0.hasSecret === false, s0);
  truthy("[+] ...and Poland's KSeF, which takes the business's token", (s0.providers.KSEF || []).includes('KSEF') && !(s0.available.KSEF || []).includes('KSEF') && (s0.needingSecret.KSEF || []).join() === 'KSEF', s0);
  truthy('[+] ...and the business\'s own electronic address', typeof s0.senderAddress === 'string' && s0.senderAddress.startsWith('0088:'), s0.senderAddress);

  // ── refusals ─────────────────────────────────────────────────────────────────────────────────────
  expect(settings(gb.cashier.token), '[-] a cashier does not read where invoices leave', 403, 'FORBIDDEN');
  expect(choose({ network: 'PEPPOL', provider: 'SIMULATED' }, gb.cashier.token), '[-] nor chooses it', 403, 'FORBIDDEN');
  expect(choose({ network: 'FAX', provider: 'SIMULATED' }), '[-] a network that does not exist is refused', 400, 'EINVOICE_NETWORK_UNKNOWN');
  expect(choose({ network: 'PEPPOL' }), '[-] a network needs a provider', 400, 'EINVOICE_PROVIDER_REQUIRED');
  expect(choose({ network: 'KSEF', provider: 'ACCESS_POINT' }), '[-] a provider that does not serve the network is refused', 400, 'EINVOICE_PROVIDER_UNKNOWN');
  expect(choose({ network: 'PEPPOL', provider: 'ACCESS_POINT' }), '[-] the access point cannot be chosen without its credentials', 409, 'EINVOICE_PROVIDER_NOT_CONFIGURED');
  expect(choose({ network: 'FR_PDP', provider: 'PDP' }), "[-] nor France's platform", 409, 'EINVOICE_PROVIDER_NOT_CONFIGURED');
  expect(choose({ network: 'IRP', provider: 'NIC', providerAccount: 'user', providerSecret: 'pass' }), "[-] nor India's portal", 409, 'EINVOICE_PROVIDER_NOT_CONFIGURED');
  expect(choose({ network: 'KSEF', provider: 'KSEF', providerSecret: 'token' }), '[-] nor KSeF', 409, 'EINVOICE_PROVIDER_NOT_CONFIGURED');
  expect(choose({ network: 'PEPPOL', provider: 'SIMULATED', providerSecret: 'a-password' }), '[-] a credential cannot be kept on a deployment with no secrets key', 409, 'EINVOICE_SECRETS_KEY_MISSING');
  expect(choose({ network: 'PEPPOL', provider: 'SIMULATED' }, noaddr.tenant.owner.token), '[-] Peppol needs the business\'s own electronic address', 409, 'EINVOICE_SENDER_ADDRESS_MISSING');
  expect(choose({ network: 'PEPPOL', provider: 'SIMULATED', providerAccount: 'x'.repeat(121) }), '[-] an account name too long is refused', 400, 'VALIDATION_FAILED');
  expect(choose({ network: 'KSEF', provider: 'SIMULATED' }, noaddr.tenant.owner.token), '[+] KSeF needs no address: the network takes the sender\'s own', 200);

  const chosen = choose({ network: 'PEPPOL', provider: 'SIMULATED', providerAccount: 'LE-K6' });
  expect(chosen, '[+] the owner chooses Peppol over the simulated provider', 200);
  truthy('[+] ...and reads it back', data(chosen).network === 'PEPPOL' && data(chosen).provider === 'SIMULATED' && data(chosen).providerAccount === 'LE-K6', data(chosen));

  // ── can a document go? ───────────────────────────────────────────────────────────────────────────
  // For the day a provider contract lands: what stands between this business and its first
  // e-invoice, with the network asked rather than assumed. Nothing is ever sent by a check.
  const nothingChosen = readiness(gb.rival.owner.token);
  expect(nothingChosen, '[+] a business that never chose is told what stands in the way', 200);
  truthy('[+] ...no network chosen, and no network asked anything', data(nothingChosen).ready === false && checkOf(nothingChosen, 'NETWORK_CHOSEN').satisfied === false && /no network is chosen/.test(checkOf(nothingChosen, 'NETWORK_CHOSEN').detail) && !data(nothingChosen).networkState, data(nothingChosen));
  // A business that never recorded a VAT number is told to record one — and told that, rather than
  // told its own details could not be read, which is the other way that check can fail and sends
  // somebody looking somewhere else entirely.
  truthy('[+] ...and is told to record the VAT number it never recorded, not that it could not be read', checkOf(nothingChosen, 'SELLER_VAT_ID').satisfied === false && /record the business/.test(checkOf(nothingChosen, 'SELLER_VAT_ID').detail) && !/could not be read/.test(checkOf(nothingChosen, 'SELLER_VAT_ID').detail), checkOf(nothingChosen, 'SELLER_VAT_ID'));

  const sentBefore = (data(outbox('?limit=100')) || []).length;
  const ready = readiness();
  expect(ready, '[+] with Peppol chosen the network is tried with what this business holds', 200);
  truthy('[+] ...ready, over the simulated network, every piece in place', data(ready).ready === true && data(ready).network === 'PEPPOL' && data(ready).networkState === 'READY' && (data(ready).checks || []).every((c) => c.satisfied), data(ready));
  truthy('[+] ...and ready says what it means on a stack with no contract: nothing leaves the platform', /nothing leaves it/.test(data(ready).networkDetail || '') && /provider contract/.test(data(ready).networkDetail || ''), data(ready).networkDetail);
  truthy('[+] ...the business is named by its address and its VAT number', /0088:/.test(checkOf(ready, 'SENDER_ADDRESS').detail || '') && checkOf(ready, 'SELLER_VAT_ID').satisfied === true, { sender: checkOf(ready, 'SENDER_ADDRESS'), vat: checkOf(ready, 'SELLER_VAT_ID') });
  truthy('[+] ...and a check sends nothing: the outbox is where it was', (data(outbox('?limit=100')) || []).length === sentBefore, sentBefore);
  expect(readiness(gb.cashier.token), '[-] a cashier does not ask whether the business can send', 403, 'FORBIDDEN');
  truthy("[abuse] a rival asking sees its own readiness, never this business's network", data(readiness(gb.rival.owner.token)).network === 'NONE', data(readiness(gb.rival.owner.token)));

  // ── delivered, refused, deferred, nowhere to go ──────────────────────────────────────────────────
  const inv = invoiced(cafe);
  const delivered = transmissionOf(inv.id, ['ACCEPTED']);
  truthy('[+] the invoice is delivered over the network without anyone asking', delivered, documentOf(inv.id));
  truthy('[+] ...with the network\'s reference, to the buyer\'s address, by the chosen provider', delivered && delivered.providerRef.startsWith('SIM-') && delivered.receiver === `9932:${BUYER_VAT}` && delivered.provider === 'SIMULATED' && delivered.sentAt, delivered);
  expect(attempts(inv.id), '[+] the attempts are read by document', 200);
  truthy('[+] ...one, delivered', data(attempts(inv.id)).length === 1, data(attempts(inv.id)));
  expect(send(inv.id), '[-] a delivered document is not sent again', 409, 'EINVOICE_ALREADY_SENT');

  const refused = invoiced(nobody);
  const rejected = transmissionOf(refused.id, ['REJECTED']);
  truthy('[+] a buyer the network does not know is refused, for good, with the reason', rejected && rejected.detail.includes('knows no participant'), rejected);
  const again = send(refused.id);
  expect(again, '[+] sent again by hand, the network answers at once', 200);
  truthy('[+] ...refused again: the address is in the document', data(again).status === 'REJECTED', data(again));
  truthy('[+] ...and both attempts are kept', data(attempts(refused.id)).length === 2, data(attempts(refused.id)));

  const deferred = invoiced(slow);
  const late = transmissionOf(deferred.id, ['ACCEPTED'], 110);
  truthy('[+] a buyer whose access point is slow is taken, asked after, and delivered', late && late.attempts >= 2, documentOf(deferred.id));

  const nowhere = invoiced(offline);
  expect(send(nowhere.id), '[-] a buyer with no electronic address has nowhere to receive', 409, 'EINVOICE_RECEIVER_ADDRESS_MISSING');
  truthy('[-] ...and nothing was queued for it', !documentOf(nowhere.id).transmission, documentOf(nowhere.id));

  // ── delivered into a business on this platform ────────────────────────────────────────────────────
  const home = invoiced(neighbour);
  const landed = transmissionOf(home.id, ['ACCEPTED']);
  truthy('[+] a sale to a business on this platform is delivered into that business\'s inbox', landed && /inbox on this platform/.test(landed.detail), landed);
  const inboxToken = inbox.tenant.owner.token;
  const inboxOf = (token) => data(call('GET', `${P}/e-invoices?limit=100`, { token })) || [];
  let arrived;
  poll(30, () => (arrived = inboxOf(inboxToken).find((d) => d.invoiceNumber === home.fullNumber)));
  truthy('[+] ...where it waits for its supplier, by the network it came over, with the network\'s reference', arrived && arrived.channel === 'SIMULATED' && arrived.status === 'NEEDS_SUPPLIER' && arrived.sellerVatId === 'GB123456789' && arrived.deliveryRef === landed.providerRef, arrived);
  truthy('[+] ...and the sender\'s own inbox has nothing of it', !inboxOf(t).find((d) => d.invoiceNumber === home.fullNumber), 'sender inbox');
  const original = call('GET', `${P}/e-invoices/${arrived && arrived.id}/document`, { token: inboxToken });
  const issued = call('GET', `${O}/admin/sales-invoices/${home.id}/document?format=UBL`, { token: t });
  truthy('[+] ...byte for byte the document the seller issued', original.status === 200 && issued.status === 200 && original.body === issued.body, [original.status, issued.status]);

  // The delivery route itself, as an access point would call it.
  const deliver = (network, headers, body = issued.body, contentType = 'application/xml') =>
    http.post(`${BASE}${P}/e-invoices/inbound/${network}`, body, { headers: { 'Content-Type': contentType, ...headers }, tags: { name: 'POST /e-invoices/inbound/{network}' } });
  const keyed = { 'X-EInvoice-Key': DELIVERY_KEY };
  const redelivered = deliver('peppol', { ...keyed, 'X-EInvoice-Reference': 'AP-K6-1' });
  expect(redelivered, '[+] an access point delivering the same document is told it is already there', 200);
  truthy('[+] ...by the first receipt\'s id, learning nothing of the receiver\'s own', data(redelivered).alreadyReceived === true && data(redelivered).id === (arrived && arrived.id) && data(redelivered).status === undefined && data(redelivered).supplierId === undefined, data(redelivered));
  expect(deliver('peppol', {}), '[-] a delivery without the key is refused before the document is read', 401, 'PURCHASE_EINVOICE_KEY_REFUSED');
  expect(deliver('peppol', { 'X-EInvoice-Key': 'not-the-key' }), '[-] as is a wrong key', 401, 'PURCHASE_EINVOICE_KEY_REFUSED');
  expect(deliver('peppol', auth(t)), '[abuse] a staff token is not a delivery key', 401, 'PURCHASE_EINVOICE_KEY_REFUSED');
  expect(deliver('peppol', { ...auth(gb.rival.owner.token), 'X-Tenant-Id': gb.rival.tenantId }), '[abuse] nor a rival\'s token with a tenant header', 401, 'PURCHASE_EINVOICE_KEY_REFUSED');
  expect(deliver('ksef', keyed), '[-] KSeF does not deliver in: a Polish buyer pulls', 400, 'PURCHASE_EINVOICE_NETWORK_UNKNOWN');
  expect(deliver('fax', keyed), '[-] nor a network that does not exist', 400, 'PURCHASE_EINVOICE_NETWORK_UNKNOWN');
  const stranger = issued.body.split(inboxGln).join(gln('579000199999')).split('GB444444444').join('GB000000001');
  expect(deliver('peppol', keyed, stranger), '[-] a document naming a buyer nobody on this platform holds lands nowhere', 404, 'PURCHASE_EINVOICE_RECEIVER_UNKNOWN');
  expect(deliver('peppol', keyed, ''), '[-] no document at all is refused', 400, 'PURCHASE_EINVOICE_EMPTY');
  expect(deliver('peppol', keyed, '{"invoice":1}', 'application/json'), '[-] JSON is not an e-invoice', 415);
  expect(deliver('peppol', keyed, '<Order/>'), '[-] XML that is not an invoice is refused', 400, 'PURCHASE_EINVOICE_NOT_AN_INVOICE');
  // SJ-D64: a network's delivery takes a document's cap, not the 1 MB every other route has — a Factur-X PDF is
  // megabytes. Two of them of nothing reach the service, which refuses them for what they are, not for their size.
  expect(deliver('peppol', keyed, `<Order>${'x'.repeat(2 * 1024 * 1024)}</Order>`), '[+] a delivery of two megabytes is read, not turned away at the door for its size', 400, 'PURCHASE_EINVOICE_NOT_AN_INVOICE');
  const inboxBefore = inboxOf(inboxToken).length;
  const flood = http.batch(Array.from({ length: 10 }, () => ['POST', `${BASE}${P}/e-invoices/inbound/peppol`, issued.body.replace(home.fullNumber, `${home.fullNumber}-X`), { headers: { 'Content-Type': 'application/xml', ...keyed }, tags: { name: 'POST /e-invoices/inbound/{network}' } }]));
  const oneRow = inboxOf(inboxToken).filter((d) => d.invoiceNumber === `${home.fullNumber}-X`).length;
  truthy('[abuse] ten deliveries at once of one document make one inbox row, each told so', flood.every((r) => [200, 201, 409].includes(r.status)) && flood.filter((r) => r.status === 201).length <= 1 && oneRow === 1 && inboxOf(inboxToken).length === inboxBefore + 1, { statuses: flood.map((r) => r.status), oneRow });

  // ── the outbox ───────────────────────────────────────────────────────────────────────────────────
  const accepted = outbox('?status=accepted&limit=1');
  expect(accepted, '[+] the outbox lists every attempt, filtered and paged', 200);
  truthy('[+] ...one delivered on the first page, and a cursor to the rest', data(accepted).length === 1 && data(accepted)[0].status === 'ACCEPTED' && accepted.json('meta.nextCursor'), accepted.body.slice(0, 300));
  const rest = outbox(`?limit=100&after=${accepted.json('meta.nextCursor')}`);
  truthy('[+] ...which reads', rest.status === 200 && data(rest).length >= 2, data(rest) && data(rest).length);
  expect(outbox('?status=LOST'), '[-] a status that does not exist is refused', 400, 'EINVOICE_TRANSMISSION_STATUS_UNKNOWN');
  expect(outbox('?after=not-a-cursor'), '[-] a cursor that is not an attempt is refused', 400, 'INVALID_UUID');

  // ── abuse ────────────────────────────────────────────────────────────────────────────────────────
  const rival = gb.rival.owner.token;
  truthy('[abuse] another tenant\'s outbox is empty', (data(outbox('', rival)) || []).length === 0, 'rival outbox');
  expect(attempts(inv.id, rival), '[abuse] another tenant does not see the attempts', 404, 'ORDER_INVOICE_NOT_FOUND');
  expect(send(inv.id, rival), '[abuse] nor sends the document', 404, 'ORDER_INVOICE_NOT_FOUND');
  const path = call('GET', `${O}/admin/sales-invoices/not-an-id/transmissions`, { token: t });
  truthy('[abuse] a path that is not an id is refused, not an error', path.status === 400 || path.status === 404, path.status);
  const before = data(attempts(refused.id)).length;
  const atOnce = http.batch(Array.from({ length: 10 }, () => ['POST', `${BASE}${O}/admin/sales-invoices/${refused.id}/transmissions`, '{}', { headers: { ...auth(t), 'Content-Type': 'application/json' }, tags: { name: 'POST /admin/sales-invoices/{id}/transmissions' } }]));
  const sent = atOnce.filter((r) => r.status === 200).length;
  const told = atOnce.filter((r) => r.status === 409).length;
  // The simulated network refuses at once, so a send that lands after the last refusal is a new
  // attempt by design; what must hold is that no two are ever in flight: every 200 is exactly one
  // attempt the network answered, every other request was told so, and nothing was lost.
  truthy('[abuse] ten sends at once for one document never overlap: each 200 one attempt, each other told', sent >= 1 && sent + told === 10 && data(attempts(refused.id)).length === before + sent, { sent, told, statuses: atOnce.map((r) => r.status) });

  // ── no network ───────────────────────────────────────────────────────────────────────────────────
  expect(choose({ network: 'NONE' }), '[+] the owner can choose to send nowhere again', 200);
  const kept = invoiced(cafe);
  expect(send(kept.id), '[-] with no network chosen a document is issued and kept, never sent', 409, 'EINVOICE_TRANSPORT_NOT_SET');
  truthy('[-] ...and nothing was queued', !documentOf(kept.id).transmission, documentOf(kept.id));
  truthy('[-] ...and readiness says so too, asking no network', data(readiness()).ready === false && !data(readiness()).networkState, data(readiness()));

  completed.add(1);
}
