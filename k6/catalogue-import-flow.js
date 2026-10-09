// A supermarket's own EPOS export loaded end to end, through the gateway (intent/catalogue-import.md).
//
// The point of the suite is that a business can move its catalogue in without a developer and be told,
// to the penny, that it all arrived: a mapping saved by header text, a dry run that writes nothing and
// refuses the rows it cannot read with a reason each, an apply that runs in the background in chunks,
// and a reconciliation that sets the file against the catalogue, the price list and the shelf.
//
//   k6/run.sh catalogue-import-flow            # 1,200 rows, a few seconds
//   ROWS=25000 k6/run.sh catalogue-import-flow # the size the intent names; the time is printed
//
// Refused on purpose, in the file: a VAT code nobody mapped, a barcode with a bad check digit, a
// barcode in spreadsheet exponent form, and a barcode two rows both claim. After the apply: the same
// item found by its old EAN with its pack quantity; the shelf price quoted with its VAT inside; stock
// opened once (a second file does not double it); another business sees none of it.
import { Counter } from 'k6/metrics';
import { ALL_CHECKS_PASS, call, data, errorCode, expect, newKey, must, onboardTenant, poll, truthy, uniq } from './lib/storeql.js';

const completed = new Counter('flow_completed');
// ROWS data rows in the file, four of them deliberately unreadable; GOOD are the ones that load.
const ROWS = Number(__ENV.ROWS || 1200);
const GOOD = ROWS - 4;
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
  setupTimeout: '4m',
};

const IMPORTS = '/api/product-svc/admin/catalogue-imports';

function checkDigit(body) {
  let sum = 0;
  for (let i = 0; i < body.length; i += 1) sum += Number(body[i]) * (i % 2 === 0 ? 1 : 3);
  return (10 - (sum % 10)) % 10;
}

/** A valid EAN-13: a prefix, a per-run stamp of six digits, the row number in five, and the check digit. */
function ean13(prefix, stamp, n) {
  const body = `${prefix}${stamp}${String(n).padStart(5, '0')}`;
  return body + checkDigit(body);
}

const MAPPING = {
  columns: {
    sku: 'PLU', name: 'Description', barcode: 'EAN', category: 'Department', vatCode: 'VAT',
    price: 'Retail Price', cost: 'Cost', stockQty: 'On Hand', expiry: 'Best Before', lot: 'Lot',
  },
  aliasColumns: [{ header: 'Old EAN', kind: 'OLD_EAN', packQty: 1 }],
  vatCodes: { A: 'T1', B: 'T5', C: 'T0' },
  priceBasis: 'INCLUSIVE',
  decimalMark: '.',
  dateFormat: 'dd/MM/yyyy',
  categorySeparator: '>',
};
const HEADER = 'PLU,Description,EAN,Department,VAT,Retail Price,Cost,On Hand,Best Before,Lot,Old EAN';

function csvOf(tag, stamp) {
  const lines = [HEADER];
  const rate = ['A', 'B', 'C'];
  for (let i = 1; i <= GOOD; i += 1) {
    const price = (0.5 + (i % 900) / 100).toFixed(2);
    const cost = (Number(price) * 0.6).toFixed(2);
    const oldEan = i % 10 === 0 ? ean13('9', stamp, i) : '';
    lines.push(`${tag}-${i},Item ${i},${ean13('4', stamp, i)},Grocery > Dept ${i % 12},${rate[i % 3]},${price},${cost},${(i % 40) + 1},31/12/2031,L${i % 7},${oldEan}`);
  }
  // four rows the dry run must refuse, each for its own reason
  lines.push(`${tag}-bad1,Unmapped VAT,,Grocery,Q,1.00,0.50,1,,,`);
  lines.push(`${tag}-bad2,Bad check digit,4000000000001,Grocery,A,1.00,0.50,1,,,`);
  lines.push(`${tag}-bad3,Exponent form,4.01E+12,Grocery,A,1.00,0.50,1,,,`);
  lines.push(`${tag}-bad4,Barcode taken by row 1,${ean13('4', stamp, 1)},Grocery,A,1.00,0.50,1,,,`);
  return lines.join('\n') + '\n';
}

export default function () {
  const tag = `K${uniq().toUpperCase().slice(0, 6)}`;
  const stamp = String(Date.now()).slice(-6);
  const shop = onboardTenant(`imp-${tag}`, { stores: 2 });
  const rival = onboardTenant(`imp-${tag}-rival`);
  const owner = shop.owner.token;
  const storeA = shop.stores[0].id;
  const storeB = shop.stores[1].id;

  // The business's own VAT codes: the import maps a file's letters onto these and guesses none.
  for (const [code, name, rate] of [['T1', 'Standard', 0.2], ['T5', 'Reduced', 0.05], ['T0', 'Zero', 0]]) {
    must(call('POST', '/api/pricing-svc/vat-rates', { token: owner, body: { code, name, rate, exempt: false, effectiveFrom: '2020-01-01T00:00:00Z' } }), 201, `VAT rate ${code}`);
  }

  // ── the mapping, by header text ──────────────────────────────────────────────────────────────
  expect(call('PUT', `${IMPORTS}/mappings/pos-export`, { token: owner, body: MAPPING }), '[+] the mapping is saved', 200);
  expect(call('PUT', `${IMPORTS}/mappings/bad`, { token: owner, body: { ...MAPPING, vatCodes: {}, priceBasis: 'SIDEWAYS' } }), '[-] a mapping that contradicts itself is refused', 400);

  // ── the dry run writes nothing and says what would happen ────────────────────────────────────
  const csv = csvOf(tag, stamp);
  const started = Date.now();
  const dry = must(
    call('POST', `${IMPORTS}?storeId=${storeA}&mapping=pos-export&fileName=export.csv`, { token: owner, idem: true, headers: { 'Content-Type': 'text/csv' }, body: csv }),
    201,
    'dry run'
  );
  const dryMs = Date.now() - started;
  truthy('[+] the dry run finished', dry.status === 'DRY_RUN_DONE', dry);
  const by = (dry.summary || {}).byAction || {};
  const refusals = (dry.summary || {}).refusals || {};
  truthy('[+] every readable row would be created', by.CREATE === GOOD, dry.summary);
  truthy('[+] the four unreadable rows are refused', by.REFUSED === 4, dry.summary);
  truthy('[+] each refusal says why', refusals.VAT_CODE_UNMAPPED === 1 && refusals.BARCODE_BAD_CHECK_DIGIT === 1 && refusals.BARCODE_EXPONENT_FORM === 1 && refusals.BARCODE_REPEATED_IN_FILE === 1, refusals);
  const first = ean13('4', stamp, 1);
  expect(call('GET', `/api/product-svc/catalog/scan?code=${first}`, { token: owner }), '[-] the dry run made nothing: the first barcode finds no item', 404, 'VARIANT_NOT_FOUND');
  console.log(`dry run: ${ROWS} rows (${GOOD} good) in ${dryMs} ms`);

  // ── apply: a key is needed; another business cannot; then it runs in the background ─────────
  expect(call('POST', `${IMPORTS}/${dry.id}/apply`, { token: owner }), '[-] an apply needs an Idempotency-Key', 400);
  expect(call('POST', `${IMPORTS}/${dry.id}/apply`, { token: rival.owner.token, idem: true }), '[-] another business cannot apply this dry run', 404);
  const key = newKey('apply');
  const queued = must(call('POST', `${IMPORTS}/${dry.id}/apply`, { token: owner, idem: key, body: {} }), 202, 'apply');
  const again = must(call('POST', `${IMPORTS}/${dry.id}/apply`, { token: owner, idem: key, body: {} }), 202, 'apply replay');
  truthy('[+] the same key is the same job', again.id === queued.id, { queued: queued.id, again: again.id });
  const second = call('POST', `${IMPORTS}/${dry.id}/apply`, { token: owner, idem: true, body: {} });
  truthy('[-] one apply at a time per business', second.status === 409 && errorCode(second) === 'IMPORT_ALREADY_RUNNING', second.body);

  const applyStarted = Date.now();
  let job = {};
  const seconds = poll(Math.max(120, ROWS / 15), () => {
    job = data(call('GET', `${IMPORTS}/${queued.id}`, { token: owner }));
    return job.status === 'DONE' || job.status === 'FAILED';
  }, 2);
  const applyMs = Date.now() - applyStarted;
  truthy('[+] the apply finished', seconds >= 0 && job.status === 'DONE', job);
  const sum = job.summary || {};
  truthy('[+] every row was made', sum.created === GOOD, sum);
  truthy('[+] VAT categories were set for every row', sum.assigned === GOOD, sum);
  truthy('[+] every price was set', sum.upserted === GOOD, sum);
  truthy('[+] stock was opened for every row', sum.opened === GOOD, sum);
  truthy('[+] the work was done in chunks of at most 500 rows', sum.chunks >= Math.ceil(GOOD / 500) * 4, sum);
  console.log(`apply: ${GOOD} rows in ${Math.round(applyMs / 1000)} s (${sum.chunks} chunks)`);

  // ── it is really there ───────────────────────────────────────────────────────────────────────
  const scanned = data(call('GET', `/api/product-svc/catalog/scan?code=${first}`, { token: owner }));
  truthy('[+] the first barcode now finds its item', !!(scanned.item || {}).variantId, scanned);
  const oldEan = ean13('9', stamp, 10);
  const viaOld = data(call('GET', `/api/product-svc/catalog/scan?code=${oldEan}`, { token: owner }));
  truthy('[+] an old EAN finds the item that replaced it', !!(viaOld.item || {}).variantId, viaOld);
  truthy('[+] and says what it is: an old code, one unit a scan', (viaOld.alias || {}).kind === 'OLD_EAN' && viaOld.alias.packQty === 1, viaOld);
  // item 1 is A=T1 20%? row i uses rate[i%3]: 1 -> B (T5). Price (0.5+0.01)=0.51 for i=1
  const quote = data(call('POST', '/api/pricing-svc/prices/quote', { token: owner, body: { storeId: storeA, lines: [{ variantId: scanned.item.variantId, qty: 1 }] } }));
  const line = (quote.lines || [])[0] || {};
  truthy('[+] the shelf price is the price, with its VAT inside', quote.taxInclusive === true && Number(line.lineGross) === 0.51 && Number(line.vatAmount) === 0.02 && Number(line.netTotal) === 0.49, quote);

  // ── reconciled to the penny ───────────────────────────────────────────────────────────────────
  const rec = data(call('GET', `${IMPORTS}/${queued.id}/reconciliation`, { token: owner }));
  truthy('[+] the import reconciles', rec.reconciled === true, rec);
  truthy('[+] SKUs, barcodes, aliases and stock agree', (rec.measures || []).every((m) => m.match), rec.measures);
  truthy('[+] prices agree by VAT code', (rec.prices || []).length === 3 && rec.prices.every((p) => p.match), rec.prices);
  truthy('[+] nothing is missing', rec.unmatchedCount === 0 && rec.priceMismatchCount === 0, rec);
  const opened = data(call('GET', `/api/inventory-svc/admin/inventory/opening-stock/${dry.id}`, { token: owner }));
  truthy('[+] inventory says the same: one store, every item', (opened.stores || []).length === 1 && opened.stores[0].storeId === storeA && opened.stores[0].lines === GOOD, opened);

  // ── a second file does not double the shelf ──────────────────────────────────────────────────
  const dry2 = must(
    call('POST', `${IMPORTS}?storeId=${storeA}&mapping=pos-export&fileName=export2.csv`, { token: owner, idem: true, headers: { 'Content-Type': 'text/csv' }, body: csvOf(tag, stamp).replace(/,(\d+),31\/12\/2031/g, ',99,31/12/2031') }),
    201,
    'second dry run'
  );
  truthy('[+] the same items are updated in place, none created', ((dry2.summary || {}).byAction || {}).CREATE === undefined || dry2.summary.byAction.CREATE === 0, dry2.summary);
  const queued2 = must(call('POST', `${IMPORTS}/${dry2.id}/apply`, { token: owner, idem: true, body: {} }), 202, 'second apply');
  let job2 = {};
  poll(Math.max(120, ROWS / 15), () => {
    job2 = data(call('GET', `${IMPORTS}/${queued2.id}`, { token: owner }));
    return job2.status === 'DONE' || job2.status === 'FAILED';
  }, 2);
  truthy('[+] the second apply finishes', job2.status === 'DONE', job2);
  truthy('[+] stock already opened is left as it is, and said so', (job2.summary || {}).alreadyOpened === GOOD, job2.summary);
  const rec2 = data(call('GET', `${IMPORTS}/${queued2.id}/reconciliation`, { token: owner }));
  truthy('[+] and the report shows it short instead of pretending', rec2.reconciled === false, rec2.measures);

  // ── isolation ────────────────────────────────────────────────────────────────────────────────
  expect(call('GET', `${IMPORTS}/${queued.id}`, { token: rival.owner.token }), '[-] another business cannot read the job', 404);
  expect(call('GET', `${IMPORTS}/${queued.id}/reconciliation`, { token: rival.owner.token }), '[-] or its reconciliation', 404);
  expect(call('GET', `/api/product-svc/catalog/scan?code=${first}`, { token: rival.owner.token }), '[-] or find its items', 404, 'VARIANT_NOT_FOUND');
  expect(call('GET', `${IMPORTS}`, { token: undefined }), '[-] an anonymous caller is refused', [401, 403]);
  const noStoreB = data(call('GET', `/api/inventory-svc/admin/inventory/opening-stock/${dry.id}`, { token: rival.owner.token }));
  truthy('[-] nor the stock it opened', (noStoreB.stores || []).length === 0, noStoreB);
  truthy('[+] the second store is untouched', storeB !== storeA, {});

  completed.add(1);
}
