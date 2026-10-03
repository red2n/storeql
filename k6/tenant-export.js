// Per-tenant export, erasure and import (21.14, EU Data Act (EU) 2023/2854 ch.VI), through the gateway:
// a business with a store, a catalogue item, a manager and a supplier takes out what every one of the
// twelve services holds for it, manifest and pages; gives notice to have its data erased; the
// platform's sweep starts the erasure, every service erases its share and tenant-svc records what
// each erased; a fresh business imports the export and reads back the same rows, table by table —
// and the refusals and the abuse around each: a manager or a rival reading the export, a table or
// cursor that is not one, credentials in the file, a second notice, an owner running the platform's
// sweep, a notice or the business record imported, rows that are not rows, a page replayed, ten
// imports of one page at once, and the erased business locked out.
//
//   k6/run.sh tenant-export
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import {
  ALL_CHECKS_PASS,
  BASE,
  call,
  claims,
  data,
  errorCode,
  expect,
  login,
  must,
  onboardTenant,
  platformAdmin,
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

// In the order an import loads them.
const SERVICES = [
  'tenant-svc', 'iam-svc', 'product-svc', 'pricing-svc', 'inventory-svc', 'purchase-svc',
  'order-svc', 'payment-svc', 'customer-svc', 'notification-svc', 'reporting-svc', 'cart-svc',
];
const DATA = (svc) => `/api/${svc}/admin/tenant-data`;
const SWITCHING = '/api/tenant-svc/admin/tenant/switching';

export function setup() {
  const leaving = onboardTenant('leaving', { country: 'GB', currency: 'GBP' });
  const rival = onboardTenant('leaving-rival', { country: 'GB', currency: 'GBP' });
  const store = leaving.stores[0];
  sellableVariant(leaving, 'Exported widget');
  const manager = staffUser(leaving, 'MANAGER', [store.id]);
  const platform = platformAdmin();
  return { leaving, rival, manager, platform };
}

export default function ({ leaving, rival, manager, platform }) {
  const owner = leaving.owner.token;
  const today = new Date().toISOString().slice(0, 10);
  must(call('POST', '/api/purchase-svc/suppliers', { token: owner, body: { name: `Exported supplier ${Date.now()}`, currency: 'GBP', paymentTermsDays: 30 } }), 201, 'supplier');

  const manifest = (svc, token) => must(call('GET', DATA(svc), { token }), 200, `${svc} manifest`);
  const rowsOf = (m, table) => ((m.tables || []).find((t) => t.name === table) || {}).rows || 0;
  const pagesOf = (svc, table, token) => {
    const pages = [];
    let after = null;
    do {
      const page = must(call('GET', `${DATA(svc)}/tables/${table}?limit=200${after ? `&after=${after}` : ''}`, { token }), 200, `${svc} ${table}`);
      pages.push(page.rows || []);
      after = page.nextCursor || null;
    } while (after);
    return pages;
  };

  // ── the export: every service's manifest and pages ───────────────────────────
  const before = {};
  const bundle = {};
  for (const svc of SERVICES) {
    before[svc] = manifest(svc, owner);
    bundle[svc] = (before[svc].tables || []).map((t) => ({
      table: t.name,
      rows: t.rows,
      skipped: t.importSkippedReason || null,
      derived: t.derived === true,
      pages: t.rows > 0 ? pagesOf(svc, t.name, owner) : [],
    }));
  }
  const read = (svc) => bundle[svc].reduce((n, t) => n + t.pages.reduce((m, p) => m + p.length, 0), 0);
  truthy('[+] every one of the twelve services serves the owner a complete manifest', SERVICES.every((s) => (before[s].tables || []).length > 0), SERVICES.map((s) => `${s}:${(before[s].tables || []).length}`));
  truthy('[+] ...holding the business: its record, its store, its catalogue item, its staff and its supplier', rowsOf(before['tenant-svc'], 'tenants') === 1 && rowsOf(before['tenant-svc'], 'stores') >= 1 && rowsOf(before['product-svc'], 'products') >= 1 && rowsOf(before['iam-svc'], 'users') >= 1 && rowsOf(before['purchase-svc'], 'suppliers') >= 1, SERVICES.map((s) => `${s}=${read(s)}`));
  truthy('[+] the rows read page by page are the rows each manifest counts', SERVICES.every((s) => bundle[s].every((t) => t.pages.reduce((n, p) => n + p.length, 0) === t.rows)), SERVICES.map((s) => `${s}=${read(s)}`));
  truthy('[-] no credential leaves in the export: no password hash, HMRC token or signing key', !/"(password_hash|hmrc_access_token|hmrc_refresh_token|private_key|token_hash)"/.test(JSON.stringify(bundle)));
  truthy('[+] the register names what is left out and why, and what is kept at erasure', Boolean(before['iam-svc'].excludedColumns['users.password_hash']) && Boolean(before['tenant-svc'].excludedTables.outbox) && Boolean(before['tenant-svc'].keptAtErasure.tenant_switches), before['iam-svc'].excludedColumns);

  expect(call('GET', DATA('order-svc'), { token: manager.token }), '[-] a manager cannot take the business\'s data out', 403);
  expect(call('GET', `${DATA('product-svc')}/tables/products`, { token: manager.token }), '[-] ...nor read its rows', 403);
  truthy('[-] a rival\'s manifest holds none of it', rowsOf(manifest('product-svc', rival.owner.token), 'products') === 0);
  expect(call('GET', `${DATA('product-svc')}/tables/outbox`, { token: owner }), '[-] a table left out is not served', 404, 'TENANT_DATA_TABLE_UNKNOWN');
  expect(call('GET', `${DATA('product-svc')}/tables/products?after=bm90LWEtY3Vyc29y`, { token: owner }), '[-] a cursor the pages did not give is refused', 400, 'TENANT_DATA_CURSOR_INVALID');

  // ── leaving: notice to erase, the platform's sweep, every service's evidence ─
  expect(call('POST', SWITCHING, { token: manager.token, body: { intent: 'ERASE', noticeEndsOn: today } }), '[-] a manager cannot give notice', 403);
  expect(call('POST', SWITCHING, { token: owner, body: { intent: 'SWITCH', noticeEndsOn: '2099-01-01' } }), '[-] notice longer than two months is refused', 400, 'SWITCHING_NOTICE_TOO_LONG');
  expect(call('POST', SWITCHING, { token: owner, body: { intent: 'ERASE', noticeEndsOn: today } }), '[+] the owner gives notice to have the business\'s data erased today', 201);
  expect(call('POST', SWITCHING, { token: owner, body: { intent: 'SWITCH', noticeEndsOn: today } }), '[-] a second notice is refused while one stands', 409, 'SWITCHING_NOTICE_ALREADY_GIVEN');
  expect(call('POST', '/api/tenant-svc/platform/tenants/switching/sweep', { token: owner, body: {} }), '[-] an owner cannot run the platform\'s sweep', 403);
  const sweepParams = { headers: { Authorization: `Bearer ${platform.token}`, 'Content-Type': 'application/json' }, tags: { name: 'POST /platform/tenants/switching/sweep' } };
  const sweeps = http.batch(Array.from({ length: 5 }, () => ['POST', `${BASE}/api/tenant-svc/platform/tenants/switching/sweep`, '{}', sweepParams]));
  truthy('[abuse] five sweeps at once all answer, and the erasure starts', sweeps.every((r) => r.status === 200) && sweeps.reduce((n, r) => n + (JSON.parse(r.body).data.started || 0), 0) >= 1, sweeps.map((r) => `${r.status} ${String(r.body).slice(0, 80)}`));

  let status = null;
  const erased = poll(180, () => {
    const res = call('GET', `/api/tenant-svc/platform/tenants/switching/${leaving.tenantId}`, { token: platform.token });
    status = res.status === 200 ? data(res) : null;
    return status !== null && status.stage === 'ERASED';
  });
  truthy('[+] every service erases its share and says so: the notice reads ERASED', erased >= 0, status && { stage: status.stage, awaiting: status.awaiting });
  truthy('[+] ...with evidence from all twelve, the business\'s rows counted', status !== null && status.evidence.length === 12 && status.evidence.some((e) => e.service === 'product-svc' && e.rowsErased >= rowsOf(before['product-svc'], 'products')), status && status.evidence.map((e) => `${e.service}=${e.rowsErased}`));
  // Staff are stopped where tokens are made: iam-svc refuses sign-in and refresh for a business made
  // inactive, and the business's staff logins are erased; a token issued before lives out its 15 minutes.
  const signIn = login(leaving.owner);
  const signedInto = signIn.status === 200 ? claims(data(signIn).accessToken) : null;
  truthy('[abuse] the erased business\'s owner cannot sign in to it again', signedInto === null || signedInto.tenant !== leaving.tenantId || !(signedInto.roles || []).includes('OWNER'), `${signIn.status} ${errorCode(signIn)}`);
  const refreshed = call('POST', '/api/iam-svc/auth/refresh', { body: { refreshToken: leaving.owner.refreshToken } });
  const refreshedInto = refreshed.status === 200 ? claims(data(refreshed).accessToken) : null;
  truthy('[abuse] ...nor refresh the session it had', refreshedInto === null || refreshedInto.tenant !== leaving.tenantId, `${refreshed.status} ${errorCode(refreshed)}`);
  const stale = call('GET', DATA('product-svc'), { token: owner });
  truthy('[abuse] ...and a token issued before erasure reads an empty business until it expires', stale.status === 401 || stale.status === 403 || (stale.status === 200 && (data(stale).tables || []).every((t) => t.rows === 0)), `${stale.status} ${errorCode(stale)}`);

  // ── arriving: a fresh business imports the export ─────────────────────────────
  const fresh = onboardTenant('arriving', { country: 'GB', currency: 'GBP' });
  const freshOwner = fresh.owner.token;
  const importPage = (svc, table, rows, token = freshOwner) => call('POST', `${DATA(svc)}/tables/${table}`, { token, body: { rows } });
  const freshBefore = {};
  for (const svc of SERVICES) freshBefore[svc] = manifest(svc, freshOwner);

  expect(importPage('tenant-svc', 'tenant_switches', [{ id: 'x' }]), '[abuse] a notice cannot be imported into another business', 409, 'TENANT_DATA_IMPORT_SKIPPED');
  expect(importPage('tenant-svc', 'tenants', []), '[abuse] ...nor the business record', 409, 'TENANT_DATA_IMPORT_SKIPPED');
  expect(importPage('product-svc', 'products', [1, 2]), '[-] rows that are not objects are refused', 400, 'TENANT_DATA_IMPORT_INVALID');
  const managerImport = importPage('product-svc', 'products', [{ id: 'x' }], manager.token);
  expect(managerImport, '[-] the erased business\'s manager cannot import either', [401, 403]);

  const refusals = [];
  let imported = 0;
  for (const svc of SERVICES) {
    for (const t of bundle[svc]) {
      if (t.skipped) continue;
      for (const rows of t.pages) {
        if (rows.length === 0) continue;
        const res = importPage(svc, t.table, rows);
        if (res.status === 200) imported += rows.length;
        else refusals.push(`${svc}.${t.table}: ${res.status} ${errorCode(res)} ${String(res.body).slice(0, 160)}`);
      }
    }
  }
  truthy('[+] a fresh business imports every table of the export', refusals.length === 0 && imported > 0, refusals);

  const mismatches = [];
  for (const svc of SERVICES) {
    const after = manifest(svc, freshOwner);
    for (const t of before[svc].tables || []) {
      if (t.importSkippedReason || t.derived || t.rows === 0) continue;
      const had = rowsOf(freshBefore[svc], t.name);
      const now = (after.tables || []).find((x) => x.name === t.name) || {};
      if (had === 0 && (now.rows !== t.rows || now.checksum !== t.checksum)) mismatches.push(`${svc}.${t.name}: ${t.rows}/${t.checksum} became ${now.rows}/${now.checksum}`);
      if (had > 0 && now.rows < had + t.rows) mismatches.push(`${svc}.${t.name}: ${had}+${t.rows} became ${now.rows}`);
    }
  }
  truthy('[+] ...and reads back the same rows: every table it did not have matches row for row and checksum for checksum', mismatches.length === 0, mismatches);

  const products = (bundle['product-svc'].find((t) => t.table === 'products') || { pages: [[]] }).pages[0];
  expect(importPage('product-svc', 'products', products), '[abuse] the same page imported again is refused', 409, 'TENANT_DATA_IMPORT_CONFLICT');
  const importParams = { headers: { Authorization: `Bearer ${freshOwner}`, 'Content-Type': 'application/json' }, tags: { name: 'POST /admin/tenant-data/tables/{table}' } };
  const burst = http.batch(Array.from({ length: 10 }, () => ['POST', `${BASE}${DATA('product-svc')}/tables/products`, JSON.stringify({ rows: products }), importParams]));
  truthy('[abuse] ten imports of a page already there are all refused', burst.every((r) => r.status === 409), burst.map((r) => `${r.status} ${errorCode(r)}`));
  truthy('[abuse] ...and the products read once', rowsOf(manifest('product-svc', freshOwner), 'products') === rowsOf(freshBefore['product-svc'], 'products') + rowsOf(before['product-svc'], 'products'));
  completed.add(1);
}
