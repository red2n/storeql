// Accounting connectors (17.9), through the gateway against the platform's SIMULATED package: a
// manager reads what can be connected; the owner connects the package with the day to push from,
// reads its chart and maps a nominal code onto it; a manual journal and a goods receipt's posting
// are pushed once with the package's own id on the log, a journal the package refuses waits with the
// reason and a next try, is mapped and retried by hand and lands; one nobody wants is left out with a
// reason; switched off nothing is pushed, switched on it is; the clock pushes a journal without
// anyone asking; the owner disconnects and the log goes with it. Refused: a cashier reading, a
// manager connecting or disconnecting, an unknown package, a package with its settings or tokens
// missing, another business reading anything.
//
//   k6/run.sh accounting-connector-flow
import { Counter } from 'k6/metrics';
import {
  ALL_CHECKS_PASS,
  call,
  data,
  expect,
  must,
  onboardTenant,
  poll,
  sellableVariant,
  staffUser,
  truthy,
  uniq,
} from './lib/storeql.js';

const completed = new Counter('flow_completed');
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
  setupTimeout: '4m',
};

const P = '/api/purchase-svc';
const ACC = `${P}/accounting`;
const CONNECTION = `${ACC}/connection`;

export function setup() {
  const tenant = onboardTenant('accounting', { country: 'GB', currency: 'GBP' });
  const rival = onboardTenant('accounting-rival', { country: 'GB', currency: 'GBP' });
  const store = tenant.stores[0];
  return {
    tenant,
    rival,
    store,
    manager: staffUser(tenant, 'MANAGER', [store.id]),
    cashier: staffUser(tenant, 'CASHIER', [store.id]),
    variantId: sellableVariant(tenant, `Booked ${uniq()}`).variantId,
  };
}

const today = () => new Date().toISOString().slice(0, 10);
const daysAgo = (n) => new Date(Date.now() - n * 86400000).toISOString().slice(0, 10);

export default function ({ tenant, rival, store, manager, cashier, variantId }) {
  const owner = tenant.owner.token;
  const journal = (description, debitCode, creditCode) =>
    must(
      call('POST', `${P}/nominal-ledger/journals`, {
        token: owner,
        body: { entryDate: today(), description, lines: [{ nominalCode: debitCode, nominalName: 'Debit side', debit: '120.00' }, { nominalCode: creditCode, nominalName: 'Credit side', credit: '120.00' }] },
      }),
      201,
      `journal ${description}`
    ).journalId;
  const sync = (token = owner) => call('POST', `${CONNECTION}/sync`, { token });
  const syncs = (query = '', token = owner) => (data(call('GET', `${ACC}/syncs${query}`, { token })) || {}).items || [];
  const byJournal = (journalId, query = '') => syncs(query).find((s) => s.journalId === journalId) || {};

  // ── what can be connected ─────────────────────────────────────────────────────────────────────
  const providers = data(call('GET', `${ACC}/providers`, { token: manager.token })) || [];
  truthy('[+] a manager reads the packages that can be connected: Xero, QuickBooks Online, Sage, and the stand-in', ['XERO', 'QUICKBOOKS', 'SAGE', 'SIMULATED'].every((c) => providers.some((p) => p.code === c && p.name && p.tokens)), providers.map((p) => p.code));
  truthy('[+] ...each with the settings it needs', (providers.find((p) => p.code === 'XERO') || {}).settings[0] === 'tenantId' && (providers.find((p) => p.code === 'QUICKBOOKS') || {}).settings[0] === 'realmId', providers);
  expect(call('GET', `${ACC}/providers`, { token: cashier.token }), '[-] a cashier does not', 403);
  expect(call('GET', CONNECTION, { token: owner }), '[-] nothing is connected to start with', 404, 'ACCOUNTING_NOT_CONNECTED');

  // ── connecting ────────────────────────────────────────────────────────────────────────────────
  expect(call('PUT', CONNECTION, { token: manager.token, body: { provider: 'SIMULATED', settings: {}, syncFrom: today() } }), '[-] a manager cannot connect a package', 403);
  expect(call('PUT', CONNECTION, { token: owner, body: { provider: 'NETSUITE', settings: {}, syncFrom: today() } }), '[-] a package nobody offers', 400, 'ACCOUNTING_PROVIDER_UNKNOWN');
  expect(call('PUT', CONNECTION, { token: owner, body: { provider: 'XERO', settings: {}, credentials: { accessToken: 't' }, syncFrom: today() } }), '[-] Xero without its organisation', 400, 'ACCOUNTING_SETTINGS_INVALID');
  expect(call('PUT', CONNECTION, { token: owner, body: { provider: 'XERO', settings: { tenantId: 'org' }, syncFrom: today() } }), '[-] Xero without a token', 400, 'ACCOUNTING_CREDENTIALS_MISSING');
  expect(call('PUT', CONNECTION, { token: owner, body: { provider: 'SIMULATED', settings: {}, syncFrom: 'someday' } }), '[-] a day that is not one', 400, 'ACCOUNTING_SYNC_FROM_INVALID');
  const connected = call('PUT', CONNECTION, { token: owner, body: { provider: 'SIMULATED', settings: { refuse: '9999' }, syncFrom: daysAgo(7) } });
  expect(connected, '[+] the owner connects the stand-in, pushing journals from a week ago', 200);
  const c = data(connected);
  truthy('[+] connected and pushing, nothing pushed yet, no token kept', c.provider === 'SIMULATED' && c.status === 'ACTIVE' && c.counts && c.counts.delivered === 0 && !('credentials' in c) && c.hasRefreshToken === false, c);
  truthy('[+] a manager reads the connection', (data(call('GET', CONNECTION, { token: manager.token })) || {}).id === c.id, 'manager read');
  expect(call('GET', CONNECTION, { token: rival.owner.token }), '[-] another business sees nothing', 404, 'ACCOUNTING_NOT_CONNECTED');

  // ── the chart and the mapping ─────────────────────────────────────────────────────────────────
  const chart = data(call('GET', `${CONNECTION}/accounts`, { token: manager.token })) || [];
  truthy('[+] the package\'s chart of accounts is read, which proves the connection', chart.some((a) => a.code === '1200' && a.name === 'Bank' && a.id === 'SIM-1200'), chart.slice(0, 3));
  expect(call('PUT', `${CONNECTION}/mappings`, { token: owner, body: { mappings: [{ nominalCode: 'not a code!', externalAccount: 'x' }] } }), '[-] a mapping from something that is not a nominal code', 400, 'ACCOUNTING_MAPPING_INVALID');
  const mapped = call('PUT', `${CONNECTION}/mappings`, { token: manager.token, body: { mappings: [{ nominalCode: '1001', externalAccount: 'SIM-1001', externalName: 'Stock on hand' }] } });
  expect(mapped, '[+] a manager maps stock onto the package\'s account', 200);
  truthy('[+] ...and reads the mapping back', (data(call('GET', `${CONNECTION}/mappings`, { token: manager.token })) || []).some((m) => m.nominalCode === '1001' && m.externalAccount === 'SIM-1001'), 'mapping');

  // ── journals pushed once ──────────────────────────────────────────────────────────────────────
  const rent = journal('Rent', '1001', '1200');
  const suspense = journal('Suspense', '9999', '1200');
  const first = sync(manager.token);
  expect(first, '[+] a manager pushes now', 200);
  truthy('[+] two journals queued, one pushed, one the package refused', (data(first).queued === 2 && data(first).delivered === 1 && data(first).failed === 1) || (byJournal(rent).status === 'DELIVERED' && byJournal(suspense).status === 'PENDING'), data(first));
  const landed = byJournal(rent);
  truthy('[+] the journal that landed carries the package\'s own id and one try', landed.status === 'DELIVERED' && landed.externalId === `sim-${rent}` && landed.attempts === 1 && landed.description === 'Rent', landed);
  const waiting = byJournal(suspense);
  truthy('[+] the refused one waits with the reason and a next try a minute away', waiting.status === 'PENDING' && waiting.attempts === 1 && String(waiting.lastError).includes('9999') && new Date(waiting.nextAttemptAt).getTime() > Date.now() + 30000, waiting);
  const detail = data(call('GET', `${ACC}/syncs/${landed.id}`, { token: manager.token })) || {};
  truthy('[+] a push read whole: the journal\'s lines and every try', (detail.lines || []).length === 2 && (detail.attemptLog || []).length === 1 && detail.attemptLog[0].statusCode === 200, detail);
  truthy('[+] filtered by status', syncs('?status=DELIVERED').length === 1 && syncs('?status=PENDING').length === 1, 'filters');
  expect(call('GET', `${ACC}/syncs/${landed.id}`, { token: rival.owner.token }), '[-] another business cannot read a push', 404, 'ACCOUNTING_SYNC_NOT_FOUND');
  expect(call('GET', `${ACC}/syncs`, { token: cashier.token }), '[-] nor can a cashier', 403);
  const again = must(sync(), 200, 'second pass');
  truthy('[+] a second pass pushes nothing twice and does not retry before its time', again.queued === 0 && again.delivered === 0 && again.failed === 0 && syncs().length === 2 && byJournal(rent).attempts === 1, { again, count: syncs().length });
  truthy('[+] the connection counts what happened', (data(call('GET', CONNECTION, { token: owner })) || {}).counts.delivered === 1 && (data(call('GET', CONNECTION, { token: owner })) || {}).lastSyncAt, 'counts');

  // ── the documents' postings go too ────────────────────────────────────────────────────────────
  const supplier = must(call('POST', `${P}/suppliers`, { token: owner, body: { name: `Books ${uniq()}`, vatRegistered: true, currency: 'GBP', paymentTermsDays: 30 } }), 201, 'supplier');
  const po = must(call('POST', `${P}/purchase-orders`, { token: owner, body: { supplierId: supplier.id, storeId: store.id } }), 201, 'purchase order');
  must(call('POST', `${P}/purchase-orders/${po.id}/lines`, { token: owner, body: { variantId, qty: 4, unitPrice: '2.50' } }), 201, 'a line');
  must(call('POST', `${P}/purchase-orders/${po.id}/submit`, { token: owner, body: {} }), 200, 'submit');
  must(call('POST', `${P}/goods-receipts`, { token: owner, idem: true, body: { poId: po.id, storeId: store.id, lines: [{ variantId, qtyReceived: 4 }] } }), 201, 'goods receipt');
  must(sync(), 200, 'push after receipt');
  truthy('[+] a goods receipt\'s posting is pushed like any journal', syncs('?status=DELIVERED').some((s) => s.sourceType === 'GOODS_RECEIPT' && s.externalId), syncs().map((x) => `${x.sourceType}:${x.status}`));

  // ── mapped and retried by hand ────────────────────────────────────────────────────────────────
  must(call('PUT', `${CONNECTION}/mappings`, { token: owner, body: { mappings: [{ nominalCode: '1001', externalAccount: 'SIM-1001' }, { nominalCode: '9999', externalAccount: 'SIM-1100' }] } }), 200, 'map suspense');
  expect(call('POST', `${ACC}/syncs/${waiting.id}/retry`, { token: cashier.token }), '[-] a cashier cannot retry a push', 403);
  const retried = call('POST', `${ACC}/syncs/${waiting.id}/retry`, { token: manager.token });
  expect(retried, '[+] mapped onto an account the package knows, a manager queues it again now', 200);
  must(sync(), 200, 'retry pass');
  truthy('[+] ...pushed on the next pass', byJournal(suspense).status === 'DELIVERED' && byJournal(suspense).attempts === 2 && byJournal(suspense).externalId === `sim-${suspense}`, byJournal(suspense));

  // ── left out with a reason ────────────────────────────────────────────────────────────────────
  must(call('PUT', `${CONNECTION}/mappings`, { token: owner, body: { mappings: [] } }), 200, 'clear the mapping');
  const unwanted = journal('Correction', '9999', '1200');
  must(sync(), 200, 'refused pass');
  truthy('[+] unmapped again, the next such journal is refused', byJournal(unwanted).status === 'PENDING' && String(byJournal(unwanted).lastError).includes('9999'), byJournal(unwanted));
  expect(call('POST', `${ACC}/syncs/${byJournal(unwanted).id}/skip`, { token: owner, body: {} }), '[-] leaving one out needs a reason', 400, 'ACCOUNTING_REASON_REQUIRED');
  const skipped = call('POST', `${ACC}/syncs/${byJournal(unwanted).id}/skip`, { token: owner, body: { reason: 'entered in the package by hand' } });
  expect(skipped, '[+] the owner leaves it out, saying why', 200);
  truthy('[+] ...and it is skipped, not tried again', data(skipped).status === 'SKIPPED' && data(skipped).lastError === 'entered in the package by hand' && must(sync(), 200, 'after skip').failed === 0 && byJournal(unwanted).status === 'SKIPPED', data(skipped));
  expect(call('POST', `${ACC}/syncs/${landed.id}/retry`, { token: owner }), '[-] a journal already in the package is not pushed again', 409, 'ACCOUNTING_SYNC_DELIVERED');

  // ── switched off and on ───────────────────────────────────────────────────────────────────────
  expect(call('POST', `${CONNECTION}/disable`, { token: manager.token }), '[-] a manager cannot switch it off', 403);
  truthy('[+] the owner switches it off', must(call('POST', `${CONNECTION}/disable`, { token: owner }), 200, 'disable').status === 'DISABLED', 'off');
  const whileOff = journal('While off', '1001', '1200');
  expect(sync(), '[-] nothing is pushed while off', 409, 'ACCOUNTING_DISABLED');
  const on = must(call('POST', `${CONNECTION}/enable`, { token: owner }), 200, 'enable');
  must(sync(), 200, 'after enable');
  truthy('[+] switched on, what was posted meanwhile goes', on.status === 'ACTIVE' && byJournal(whileOff).status === 'DELIVERED', byJournal(whileOff));

  // ── the clock ─────────────────────────────────────────────────────────────────────────────────
  const byClock = journal('By the clock', '1001', '1200');
  const took = poll(90, () => byJournal(byClock).status === 'DELIVERED');
  truthy('[+] a journal is pushed by the clock, nobody asking', took >= 0 && byJournal(byClock).externalId === `sim-${byClock}`, byJournal(byClock));

  // ── disconnected ──────────────────────────────────────────────────────────────────────────────
  expect(call('DELETE', CONNECTION, { token: manager.token }), '[-] a manager cannot disconnect', 403);
  expect(call('DELETE', CONNECTION, { token: owner }), '[+] the owner disconnects', 200);
  expect(call('GET', `${ACC}/syncs`, { token: owner }), '[+] ...and the log goes with it', 404, 'ACCOUNTING_NOT_CONNECTED');
  expect(call('GET', CONNECTION, { token: owner }), '[+] nothing is connected now', 404, 'ACCOUNTING_NOT_CONNECTED');

  completed.add(1);
}
