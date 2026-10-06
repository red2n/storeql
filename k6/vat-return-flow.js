// SJ-D39: box 4 of the VAT return is real. A supplier invoice captured in purchase-svc reaches
// pricing-svc as SupplierInvoiceCaptured and shows up in the return as input VAT (box 4) and net
// purchases (box 7), by invoice date; box 5 is what is actually owed. Another tenant's invoices are
// another tenant's return, and the return is management-only.
//
//   k6/run.sh vat-return-flow
import { ALL_CHECKS_PASS, call, data, expect, must, onboardTenant, poll, sellableVariant, staffUser, truthy } from './lib/storeql.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS };

export function setup() {
  const tenant = onboardTenant('vat', { country: 'GB', currency: 'GBP' });
  const rival = onboardTenant('vat-rival', { country: 'GB', currency: 'GBP' });
  // The return is HMRC's, in pounds: a business outside the UK is not offered it.
  const yen = onboardTenant('vat-yen', { country: 'JP', currency: 'JPY' });
  const store = tenant.stores[0];
  const { variantId } = sellableVariant(tenant, 'Invoiced widget');
  const cashier = staffUser(tenant, 'CASHIER', [store.id]);
  return { tenant, rival, yen, store, variantId, cashier };
}

export default function ({ tenant, rival, yen, store, variantId, cashier }) {
  const owner = tenant.owner.token;
  const period = { from: '2026-09-01T00:00:00Z', to: '2026-10-01T00:00:00Z' };
  const vatReturn = (token) => call('GET', `/api/pricing-svc/vat-return?from=${period.from}&to=${period.to}`, { token });

  const before = vatReturn(owner);
  expect(before, 'the return reads before any invoice', 200);
  truthy('and says it is fit to file, with the boxes it computes named', data(before).fitToFile === true && JSON.stringify(data(before).computedBoxes) === '[1,3,4,5,6,7]', data(before));
  const box4Before = Number(data(before).box4);

  // ── a purchase order, invoiced ────────────────────────────────────────────
  const supplier = must(call('POST', '/api/purchase-svc/suppliers', { token: owner, body: { name: `VAT Supplier ${Date.now()}`, vatRegistered: true, currency: 'GBP' } }), 201, 'supplier');
  const po = must(call('POST', '/api/purchase-svc/purchase-orders', { token: owner, body: { supplierId: supplier.id, storeId: store.id } }), 201, 'purchase order');
  expect(call('POST', `/api/purchase-svc/purchase-orders/${po.id}/lines`, { token: owner, body: { variantId, qty: 10, unitPrice: '15.00' } }), 'a line on the order', 201);
  expect(call('POST', `/api/purchase-svc/purchase-orders/${po.id}/submit`, { token: owner, body: {} }), 'the order is submitted', 200);
  const invoice = call('POST', '/api/purchase-svc/supplier-invoices', { token: owner, body: { poId: po.id, invoiceNumber: `INV-${Date.now()}`, invoiceDate: '2026-09-10', vatAmount: '30.00', lines: [{ variantId, qty: 10, unitPrice: '15.00' }] } });
  expect(invoice, 'the supplier invoice is captured: net 150, VAT 30', 201);
  truthy('with its figures', Number(data(invoice).netAmount) === 150 && Number(data(invoice).vatAmount) === 30, data(invoice));

  // ── and reaches the return ────────────────────────────────────────────────
  const arrived = poll(30, () => Number(data(vatReturn(owner)).box4) === box4Before + 30);
  truthy('box 4 rises by the invoice VAT within 30 s', arrived >= 0, data(vatReturn(owner)));
  const after = data(vatReturn(owner));
  truthy('box 7 carries the net purchases', Number(after.box7) >= 150, after);
  truthy('box 5 is |box 3 - box 4|', Math.abs(Number(after.box3) - Number(after.box4)).toFixed(2) === Number(after.box5).toFixed(2), after);
  truthy("a rival tenant's return is untouched", Number(data(vatReturn(rival.owner.token)).box4) === 0, data(vatReturn(rival.owner.token)));
  expect(vatReturn(cashier.token), 'a cashier cannot read the return', 403);
  expect(call('GET', `/api/pricing-svc/vat-return?from=${period.to}&to=${period.from}`, { token: owner }), 'a period that ends before it starts is refused', 400, 'PRICING_INVALID_PERIOD');

  // ── and is filed: Making Tax Digital (18.5) ───────────────────────────────
  const mtd = '/api/pricing-svc/vat-return/mtd';
  const offer = call('GET', `${mtd}/registration`, { token: owner });
  expect(offer, 'before registering, the offer is read', 200);
  truthy('not registered, the simulator on offer', data(offer).registered === false && (data(offer).providers || []).includes('SIMULATED'), data(offer));
  expect(call('GET', `${mtd}/obligations?from=2026-01-01T00:00:00Z&to=2026-12-31T00:00:00Z`, { token: owner }), 'obligations need a registration', 404, 'MTD_NOT_REGISTERED');
  expect(call('PUT', `${mtd}/registration`, { token: owner, body: { vrn: '123456783', provider: 'SIMULATED' } }), 'a VAT number with a wrong check digit is refused', 400, 'MTD_VRN_INVALID');
  expect(call('PUT', `${mtd}/registration`, { token: owner, body: { vrn: '123456782', provider: 'SAGE' } }), 'an unknown provider is refused', 400, 'MTD_PROVIDER_UNKNOWN');
  expect(call('PUT', `${mtd}/registration`, { token: cashier.token, body: { vrn: '123456782', provider: 'SIMULATED' } }), 'a cashier cannot register', 403);
  expect(call('PUT', `${mtd}/registration`, { token: yen.owner.token, body: { vrn: '123456782', provider: 'SIMULATED' } }), 'a business outside the UK cannot register for the UK return', 409, 'VAT_RETURN_NOT_AVAILABLE');
  expect(call('GET', `${mtd}/registration`, { token: yen.owner.token }), 'and is offered no registration', 200);
  truthy('nothing was registered for it', data(call('GET', `${mtd}/registration`, { token: yen.owner.token })).registered === false);
  const hmrc = call('PUT', `${mtd}/registration`, { token: owner, body: { vrn: '123456782', provider: 'HMRC' } });
  truthy('HMRC is refused unless the deployment is configured for it, and accepted when it is', hmrc.status === 200 ? data(hmrc).provider === 'HMRC' : hmrc.status === 409, hmrc.body);
  const registered = call('PUT', `${mtd}/registration`, { token: owner, body: { vrn: 'GB 123 4567 82', provider: 'simulated' } });
  expect(registered, 'the owner registers the number, through the simulator', 200);
  truthy('nine digits, upper-cased provider', data(registered).vrn === '123456782' && data(registered).provider === 'SIMULATED' && data(registered).registered === true, data(registered));

  const obligations = call('GET', `${mtd}/obligations?from=2026-07-01T00:00:00Z&to=2026-09-30T00:00:00Z`, { token: owner });
  expect(obligations, 'the periods to file for are read', 200);
  truthy('the quarter the invoice fell in is open', (data(obligations) || []).some((o) => o.periodKey === '26A3' && o.status === 'O'), data(obligations));

  const filing = { periodKey: '26A3', from: '2026-07-01T00:00:00Z', to: '2026-10-01T00:00:00Z', finalised: true, client: { timezone: 'UTC+01:00', deviceId: 'k6' } };
  expect(call('POST', `${mtd}/submissions`, { token: owner, body: Object.assign({}, filing, { finalised: false }) }), 'a return not declared final is not filed', 400, 'MTD_NOT_FINALISED');
  expect(call('POST', `${mtd}/submissions`, { token: owner, body: Object.assign({}, filing, { periodKey: '26-3' }) }), 'a period key that is not HMRC\'s is refused', 400, 'MTD_PERIOD_KEY_INVALID');
  expect(call('POST', `${mtd}/submissions`, { token: cashier.token, body: filing }), 'a cashier cannot file', 403);
  const filed = call('POST', `${mtd}/submissions`, { token: owner, body: filing });
  expect(filed, 'the owner files the quarter from the records', 201);
  truthy('accepted, with the invoice VAT in box 4 and the net in box 7 as whole pounds', data(filed).status === 'ACCEPTED' && Number(data(filed).box4) === 30 && Number(data(filed).box7) === 150 && Number(data(filed).box5) === 30, data(filed));
  truthy('and HMRC\'s receipt kept', typeof data(filed).formBundleNumber === 'string' && data(filed).formBundleNumber.length === 12 && !!data(filed).receiptId, data(filed));
  expect(call('POST', `${mtd}/submissions`, { token: owner, body: filing }), 'the same period cannot be filed twice', 409, 'MTD_DUPLICATE_SUBMISSION');
  const fulfilled = call('GET', `${mtd}/obligations?from=2026-07-01T00:00:00Z&to=2026-09-30T00:00:00Z`, { token: owner });
  truthy('the obligation now reads fulfilled', (data(fulfilled) || []).some((o) => o.periodKey === '26A3' && o.status === 'F'), data(fulfilled));
  const listed = call('GET', `${mtd}/submissions`, { token: owner });
  expect(listed, 'the filing is listed', 200);
  truthy('once', (data(listed) || []).filter((s) => s.periodKey === '26A3').length === 1, data(listed));
  expect(call('GET', `${mtd}/submissions/${data(filed).id}`, { token: owner }), 'and readable by id', 200);
  expect(call('GET', `${mtd}/submissions/${data(filed).id}`, { token: rival.owner.token }), "but not by a rival tenant's owner", 404);
  truthy("a rival tenant's filings are its own: none", (data(call('GET', `${mtd}/submissions`, { token: rival.owner.token })) || []).length === 0);
  expect(call('GET', `${mtd}/hmrc/authorize-url?redirectUri=https://shop.example/cb`, { token: owner }), 'a business filing through the simulator has no grant to give', 409, 'MTD_NOT_HMRC');
  expect(call('DELETE', `${mtd}/submissions/${data(filed).id}`, { token: owner }), 'nothing deletes a filing', [404, 405]);
}
