// Shelf space and range (07.17, 07.18), through the gateway.
//
// Two decisions a buyer makes about a shelf: how much of it a line gets, and which shops carry the
// line at all. The platform could record neither. Space had no fixtures, no layouts and no way to
// say a category was promised eight per cent of a store; range had product_stores — which stores
// carry a line today — and nothing about who decided that, when, or what they weighed it against.
//
// The assertion this suite exists for on the space side is that a layout is CHECKED AGAINST THE
// FURNITURE IT IS DRAWN FOR, and checked when it is saved: facings times a variant's recorded width
// either fits the shelf or is refused, because a layout found not to fit after a reset has been
// scheduled around it is found out too late. Then the capacity it implies CROSSES KAFKA into
// inventory-svc, where the shelf-gap report answers what it would take to fill the bay — the number
// a reorder level cannot give, because 20 units is plenty for a bay holding 12 and a gap in one
// holding 60. Only a live stack proves that hop: an integration test runs without a broker.
//
// On the range side: a change is INTENT UNTIL IT IS APPLIED — recorded with a date and a reason,
// doing nothing until the sweep runs on the day, and applied exactly once — and a closing review
// produces the changes its decisions imply, carrying the figures they were decided on.
//
// Refused: a layout wider than its shelf, a shelf the fixture does not have, a second draft for one
// fixture, an empty layout published, a published layout edited, a de-list against a line ranged
// everywhere, an empty cluster, neither target or both, no reason, an unknown action, two clusters
// sharing a code, a review closed with a line unread, an own-brand line dropped with no note, money
// with no currency, a cashier re-laying a shelf, another business reading this one's layouts, and an
// anonymous caller.
//
//   k6/run.sh merchandising-range
import { Counter } from 'k6/metrics';
import { ALL_CHECKS_PASS, addStore, call, data, expect, poll, sellingTenant, truthy, uniq } from './lib/storeql.js';

const completed = new Counter('flow_completed');
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
  setupTimeout: '4m',
};

const MERCH = '/api/product-svc/admin/merchandising';
const RANGE = '/api/product-svc/admin/assortment';
const GAPS = '/api/inventory-svc/admin/inventory/reports/shelf-gaps';

/** Today, and a day, as the API writes them. */
function isoDay(offsetDays = 0) {
  const d = new Date(Date.now() + offsetDays * 86400000);
  return d.toISOString().slice(0, 10);
}

export default function () {
  const tag = uniq().toUpperCase().slice(0, 8);
  const shop = sellingTenant(`merch-${tag}`);
  const owner = shop.tenant.owner.token;
  const rival = shop.rival.owner.token;
  const store = shop.store.id;

  const post = (path, body, token) => call('POST', path, { token: token || owner, body });
  const put = (path, body, token) => call('PUT', path, { token: token || owner, body });
  const get = (path, token) => call('GET', path, { token: token || owner });

  // ── a fixture, and the measurement every fit check is made of ────────────────────────────────────
  const fixture = data(
    post(`${MERCH}/fixtures`, {
      storeId: store,
      code: `GOND-${tag}`,
      name: 'Aisle 4 gondola',
      kind: 'GONDOLA',
      shelfCount: 4,
      shelfWidthMm: 1200,
    }),
  );
  truthy('[+] a gondola offers every millimetre its shelves add up to', fixture.totalWidthMm === 4800, fixture);

  // 150mm of facing on a 1200mm shelf: eight fit, nine do not.
  expect(
    put(`${MERCH}/variants/${shop.variantId}/facing-width`, { facingWidthMm: 150 }),
    '[+] a line is measured once, on the variant, for every shelf it stands on',
    200,
  );

  // ── a layout, checked as it is saved ─────────────────────────────────────────────────────────────
  const draft = data(post(`${MERCH}/fixtures/${fixture.id}/planograms`, { effectiveFrom: isoDay(7) }));
  truthy('[+] a layout starts as a draft at version 1', draft.status === 'DRAFT' && draft.version === 1, draft);

  expect(
    post(`${MERCH}/fixtures/${fixture.id}/planograms`, { effectiveFrom: isoDay(14) }),
    '[-] a second draft for one fixture is refused: two people drawing one shelf is a merge nobody wins',
    409,
    'PLANOGRAM_DRAFT_EXISTS',
  );

  expect(
    put(`${MERCH}/planograms/${draft.id}/positions`, {
      positions: [{ variantId: shop.variantId, shelf: 1, sequence: 1, facings: 9, depth: 3, minPresentation: 6 }],
    }),
    '[-] nine 150mm facings on a 1200mm shelf is refused when it is saved, not at publication',
    409,
    'PLANOGRAM_SHELF_OVERFLOWS',
  );

  expect(
    put(`${MERCH}/planograms/${draft.id}/positions`, {
      positions: [{ variantId: shop.variantId, shelf: 9, sequence: 1, facings: 1, depth: 1, minPresentation: 0 }],
    }),
    '[-] a shelf the fixture does not have is refused',
    400,
    'PLANOGRAM_SHELF_BEYOND_FIXTURE',
  );

  expect(
    post(`${MERCH}/planograms/${draft.id}/publish`, {}),
    '[-] an empty layout is refused: published over a full one it would say the shelf holds nothing',
    409,
    'PLANOGRAM_EMPTY',
  );

  // Eight facings, five deep: 40 units, and 1200mm exactly.
  const fit = data(
    put(`${MERCH}/planograms/${draft.id}/positions`, {
      positions: [{ variantId: shop.variantId, shelf: 1, sequence: 1, facings: 8, depth: 5, minPresentation: 12 }],
    }),
  );
  truthy('[+] the save answers with the fit, to the millimetre', fit[0].usedMm === 1200 && fit[0].overflows === false, fit);

  const saved = data(get(`${MERCH}/planograms/${draft.id}`));
  truthy('[+] capacity is the database\'s arithmetic, not the caller\'s', saved.totalCapacity === 40, saved);

  const published = data(post(`${MERCH}/planograms/${draft.id}/publish`, {}));
  truthy('[+] a layout in force', published.status === 'PUBLISHED', published);

  expect(
    put(`${MERCH}/planograms/${draft.id}/positions`, {
      positions: [{ variantId: shop.variantId, shelf: 1, sequence: 1, facings: 4, depth: 5, minPresentation: 6 }],
    }),
    '[-] a published layout is never edited again',
    409,
    'PLANOGRAM_NOT_DRAFT',
  );

  // A second version supersedes the first rather than replacing it.
  const second = data(post(`${MERCH}/fixtures/${fixture.id}/planograms`, { effectiveFrom: isoDay(21) }));
  put(`${MERCH}/planograms/${second.id}/positions`, {
    positions: [{ variantId: shop.variantId, shelf: 1, sequence: 1, facings: 6, depth: 5, minPresentation: 10 }],
  });
  const successor = data(post(`${MERCH}/planograms/${second.id}/publish`, {}));
  truthy(
    '[+] version 2 supersedes version 1, which is kept',
    successor.version === 2 && successor.supersedes === draft.id,
    successor,
  );
  const superseded = data(get(`${MERCH}/planograms/${draft.id}`));
  truthy('[+] the version it replaced says so', superseded.status === 'SUPERSEDED', superseded);

  // ── the hop only a live stack proves: capacity reaches replenishment ──────────────────────────────
  //
  // 30 units of shelf (6 facings x 5 deep) against 50 on hand: no gap. The number that matters is
  // the capacity itself — a reorder level has no idea how much shelf there is to fill.
  let gapRow = null;
  const seconds = poll(90, () => {
    const rows = data(get(`${GAPS}?storeId=${store}&limit=50`)) || [];
    gapRow = rows.find((r) => r.variantId === shop.variantId);
    // Version 1 (40 units) is published before version 2 (30), so the wait is for the layout in
    // force and not merely for the line to appear — otherwise this asserts about a replaced shelf.
    return !!gapRow && gapRow.capacity === 30;
  });
  truthy(`[+] the published shelf reached replenishment in ${seconds}s`, seconds >= 0, gapRow);
  truthy(
    '[+] and it is the layout in force that replenishment holds, not the one it replaced',
    gapRow && gapRow.capacity === 30 && gapRow.minPresentation === 10,
    gapRow,
  );
  truthy('[+] 50 on hand against a 30-unit shelf leaves nothing to fill', gapRow && gapRow.gap === '0.000', gapRow);

  // ── space: a promise against what the bays actually give ──────────────────────────────────────────
  const category = data(post('/api/product-svc/admin/categories', { name: `Soft drinks ${tag}` }));
  const spaced = data(post('/api/product-svc/admin/products', { name: `Cola ${tag}`, categoryId: category.id }));
  const spacedVariant = data(post(`/api/product-svc/admin/products/${spaced.id}/variants`, { sku: `COLA-${tag}` }));
  put(`${MERCH}/variants/${spacedVariant.id}/facing-width`, { facingWidthMm: 100 });
  const endCap = data(
    post(`${MERCH}/fixtures`, {
      storeId: store,
      code: `END-${tag}`,
      name: 'End cap',
      kind: 'END_CAP',
      shelfCount: 2,
      shelfWidthMm: 800,
    }),
  );
  const capDraft = data(post(`${MERCH}/fixtures/${endCap.id}/planograms`, { effectiveFrom: isoDay(1) }));
  put(`${MERCH}/planograms/${capDraft.id}/positions`, {
    positions: [{ variantId: spacedVariant.id, shelf: 1, sequence: 1, facings: 4, depth: 3, minPresentation: 4 }],
  });
  post(`${MERCH}/planograms/${capDraft.id}/publish`, {});

  const plan = data(put(`${MERCH}/space-plans`, { storeId: store, categoryId: category.id, targetShare: 0.2 }));
  truthy('[+] a category is promised a share of the store', plan.targetShare === '0.2000', plan);
  const space = data(get(`${MERCH}/space?store=${store}`));
  const line = space.find((l) => l.categoryId === category.id);
  // 4 facings x 100mm = 400mm of the store's 6400mm of shelf: 6.25% against a promise of 20%.
  truthy('[+] the actual share is measured from the layouts in force', line && line.actualMm === 400, line);
  truthy(
    '[+] and the variance is signed, because over- and under-spaced are different problems',
    line && line.variance.startsWith('-'),
    line,
  );

  expect(
    put(`${MERCH}/space-plans`, { storeId: store, categoryId: category.id, targetShare: 1.5 }),
    '[-] a share of more than the whole store is refused',
    400,
    'SPACE_SHARE_INVALID',
  );

  // ── a reset: the day a category is re-laid, all at once ──────────────────────────────────────────
  const reset = data(post(`${MERCH}/resets`, { categoryId: category.id, name: `Spring ${tag}`, scheduledFor: isoDay(30) }));
  expect(post(`${MERCH}/resets/${reset.id}/planograms`, { planogramId: capDraft.id }), '[+] a published layout joins the reset', 200);
  expect(
    post(`${MERCH}/resets/${reset.id}/planograms`, { planogramId: capDraft.id }),
    '[-] one layout is not put on the shelf twice by the same reset',
    409,
    'RESET_PLANOGRAM_ATTACHED',
  );
  expect(post(`${MERCH}/resets/${reset.id}/cancel`, { reason: '' }), '[-] a reset called off with no reason is refused', 400);
  const cancelled = data(post(`${MERCH}/resets/${reset.id}/cancel`, { reason: 'Supplier pulled the range' }));
  truthy('[+] a cancelled reset keeps why', cancelled.status === 'CANCELLED' && !!cancelled.cancelledReason, cancelled);

  // ── range: a cluster, and a change that is intent until it is applied ─────────────────────────────
  const second_store = addStore(shop.tenant, 'north');
  const cluster = data(post(`${RANGE}/clusters`, { code: `NORTH-${tag}`, name: 'Northern shops' }));
  expect(
    post(`${RANGE}/clusters`, { code: `NORTH-${tag}`, name: 'Northern shops again' }),
    '[-] two active clusters cannot share a code',
    409,
    'CLUSTER_CODE_TAKEN',
  );

  const ranged = data(post('/api/product-svc/admin/products', { name: `Regional line ${tag}` }));
  const pending = data(
    post(`${RANGE}/changes`, {
      productId: ranged.id,
      clusterId: cluster.id,
      action: 'LIST',
      effectiveFrom: isoDay(3),
      reason: 'Trial in the north',
    }),
  );
  truthy('[+] a change is recorded with a date and a reason, and not yet applied', !pending.appliedAt, pending);

  // Nothing has moved, and the day has not come.
  const before = data(get(`/api/product-svc/admin/products/${ranged.id}/stores`));
  truthy('[+] the live range is untouched: no rows means every store', before.length === 0, before);
  truthy('[+] and a sweep today applies nothing', data(post(`${RANGE}/changes/apply`, {})).applied === 0);

  // The cluster gains a shop AFTER the decision — membership is read on the day it is applied.
  // product-svc asks tenant-svc about a store it has not met at most every thirty seconds, so a shop
  // opened a moment ago may be unknown for a while: ask again until it is found.
  let named = post(`${RANGE}/clusters/${cluster.id}/stores`, { storeIds: [store, second_store.id] });
  poll(40, () => {
    if (named.status !== 404) return true;
    named = post(`${RANGE}/clusters/${cluster.id}/stores`, { storeIds: [store, second_store.id] });
    return named.status !== 404;
  }, 3);
  expect(named, '[+] a cluster names its shops, and the same list sent twice is not an error', 200);
  const swept = data(call('POST', `${RANGE}/changes/apply?asOf=${isoDay(3)}`, { token: owner, body: {} }));
  truthy('[+] on the day, the change is applied', swept.applied === 1, swept);
  const after = data(get(`/api/product-svc/admin/products/${ranged.id}/stores`));
  truthy(
    '[+] both shops carry it, the one that joined the cluster after the decision included',
    after.length === 2 && after.includes(second_store.id),
    after,
  );
  truthy(
    '[+] and it is applied exactly once, so a de-list done by hand is not undone by a sweep',
    data(call('POST', `${RANGE}/changes/apply?asOf=${isoDay(30)}`, { token: owner, body: {} })).applied === 0,
  );

  // ── the refusals that keep the log honest ────────────────────────────────────────────────────────
  const everywhere = data(post('/api/product-svc/admin/products', { name: `Everywhere ${tag}` }));
  const impossible = data(
    post(`${RANGE}/changes`, {
      productId: everywhere.id,
      storeId: store,
      action: 'DELIST',
      effectiveFrom: isoDay(-1),
      reason: 'Poor seller here',
    }),
  );
  const refused = data(post(`${RANGE}/changes/apply`, {}));
  truthy(
    '[-] a de-list against a line ranged everywhere is refused: no rows means every store',
    refused.applied === 0 && refused.notApplied.some((n) => n.changeId === impossible.id && n.code === 'ASSORTMENT_RANGED_EVERYWHERE'),
    refused,
  );
  truthy(
    '[+] and it stays due, so fixing the cause is enough — nothing is re-entered',
    (data(get(`${RANGE}/changes/due`)) || []).some((c) => c.id === impossible.id),
  );

  // Give the line a range and the de-list becomes expressible. Two sweeps, not one: within a single
  // sweep the de-list is tried first — same day, recorded earlier — and is still refused at that
  // moment, because the range it needs does not exist yet. The end state is what is asserted rather
  // than a count, because the scheduled sweeper applies the same work on its own tick.
  post(`${RANGE}/changes`, {
    productId: everywhere.id,
    storeId: second_store.id,
    action: 'LIST',
    effectiveFrom: isoDay(-1),
    reason: 'Keep it in the north',
  });
  post(`${RANGE}/changes/apply`, {});
  post(`${RANGE}/changes/apply`, {});
  const narrowed = data(get(`/api/product-svc/admin/products/${everywhere.id}/stores`));
  truthy(
    '[+] and once the line has a range, the de-list is applied: kept in one shop, gone from the other',
    narrowed.includes(second_store.id) && !narrowed.includes(store),
    narrowed,
  );

  const emptyCluster = data(post(`${RANGE}/clusters`, { code: `EMPTY-${tag}`, name: 'Nobody yet' }));
  const intoNothing = data(
    post(`${RANGE}/changes`, {
      productId: everywhere.id,
      clusterId: emptyCluster.id,
      action: 'LIST',
      effectiveFrom: isoDay(-1),
      reason: 'Planned group',
    }),
  );
  truthy(
    '[-] a change aimed at an empty cluster is refused rather than applied as a silent no-op',
    (data(post(`${RANGE}/changes/apply`, {})).notApplied || []).some((n) => n.changeId === intoNothing.id && n.code === 'CLUSTER_EMPTY'),
  );

  expect(
    post(`${RANGE}/changes`, { productId: ranged.id, action: 'LIST', reason: 'why' }),
    '[-] a change aimed at neither a store nor a cluster is refused',
    400,
    'ASSORTMENT_TARGET_REQUIRED',
  );
  expect(
    post(`${RANGE}/changes`, { productId: ranged.id, storeId: store, clusterId: cluster.id, action: 'LIST', reason: 'why' }),
    '[-] and one aimed at both is refused too',
    400,
    'ASSORTMENT_TARGET_REQUIRED',
  );
  expect(
    post(`${RANGE}/changes`, { productId: ranged.id, storeId: store, action: 'LIST', reason: '   ' }),
    '[-] a range change with no reason is refused: that is what the log is for',
    400,
  );
  expect(
    post(`${RANGE}/changes`, { productId: ranged.id, storeId: store, action: 'MAYBE', reason: 'why' }),
    '[-] an unknown action is refused before it reaches the table',
    400,
    'ASSORTMENT_ACTION_UNKNOWN',
  );

  // ── a range review: the comparison behind a drop ─────────────────────────────────────────────────
  const reviewed = data(post('/api/product-svc/admin/products', { name: `Bottom of category ${tag}`, categoryId: category.id }));
  const keeper = data(post(`/api/product-svc/admin/products/${reviewed.id}/variants`, { sku: `KEEP-${tag}` }));
  const dropped = data(post(`/api/product-svc/admin/products/${reviewed.id}/variants`, { sku: `DROP-${tag}` }));
  const review = data(
    post(`${RANGE}/reviews`, {
      categoryId: category.id,
      name: `H2 soft drinks ${tag}`,
      periodFrom: '2026-01-01',
      periodTo: '2026-07-01',
    }),
  );
  expect(
    post(`${RANGE}/reviews/${review.id}/lines`, { lines: [{ variantId: keeper.id, revenue: 900.0, ownBrand: false }] }),
    '[-] money with no currency is refused: a revenue nobody can add up later',
    400,
    'REVIEW_LINE_CURRENCY',
  );
  expect(
    post(`${RANGE}/reviews/${review.id}/lines`, {
      lines: [
        { variantId: keeper.id, unitsSold: 4200.0, revenue: 9100.0, margin: 2100.0, currency: 'GBP', rankInCategory: 4, ownBrand: false },
        { variantId: dropped.id, unitsSold: 61.0, revenue: 140.0, margin: 12.0, currency: 'GBP', rankInCategory: 47, ownBrand: true },
      ],
    }),
    '[+] the lines under review carry the figures they are judged on, as a snapshot',
    200,
  );
  expect(
    post(`${RANGE}/reviews/${review.id}/close`, { storeId: store, effectiveFrom: isoDay(10) }),
    '[-] a review closed with a line unread is refused',
    409,
    'REVIEW_LINES_UNDECIDED',
  );
  expect(
    post(`${RANGE}/reviews/${review.id}/decisions`, { variantId: dropped.id, decision: 'DELIST' }),
    '[-] dropping an own-brand line with no note is refused: the margin lost is the business\'s own',
    400,
    'REVIEW_OWN_BRAND_NOTE',
  );
  expect(
    post(`${RANGE}/reviews/${review.id}/decisions`, { variantId: dropped.id, decision: 'DELIST', note: 'Reformulation replaces it in March' }),
    '[+] with a note it goes through, because a buyer may well be right',
    200,
  );
  expect(post(`${RANGE}/reviews/${review.id}/decisions`, { variantId: keeper.id, decision: 'KEEP' }), '[+] the other line is kept', 200);

  const closed = data(post(`${RANGE}/reviews/${review.id}/close`, { storeId: store, effectiveFrom: isoDay(10) }));
  truthy('[+] the review is decided', closed.review.status === 'DECIDED', closed.review);
  truthy(
    '[+] a kept variant holds the product in range, and the review says so rather than silently dropping it',
    closed.changes.length === 0 && closed.leftAlone.length === 1 && closed.leftAlone[0].productId === reviewed.id,
    closed,
  );

  // A product whose every reviewed variant is dropped does leave the range, with the figures on the
  // change: the sentence somebody needs a year later.
  const goner = data(post('/api/product-svc/admin/products', { name: `Gone ${tag}`, categoryId: category.id }));
  const gonerVariant = data(post(`/api/product-svc/admin/products/${goner.id}/variants`, { sku: `GONE-${tag}` }));
  const review2 = data(
    post(`${RANGE}/reviews`, { categoryId: category.id, name: `Winter cull ${tag}`, periodFrom: '2026-01-01', periodTo: '2026-04-01' }),
  );
  post(`${RANGE}/reviews/${review2.id}/lines`, {
    lines: [{ variantId: gonerVariant.id, unitsSold: 3.0, revenue: 7.0, margin: 0.4, currency: 'GBP', rankInCategory: 92, ownBrand: false }],
  });
  post(`${RANGE}/reviews/${review2.id}/decisions`, { variantId: gonerVariant.id, decision: 'DELIST' });
  const culled = data(post(`${RANGE}/reviews/${review2.id}/close`, { clusterId: cluster.id, effectiveFrom: isoDay(10) }));
  truthy(
    '[+] every variant dropped means the product leaves the range, reasoned and dated',
    culled.changes.length === 1 &&
      culled.changes[0].action === 'DELIST' &&
      culled.changes[0].reason.includes('ranked 92 in category') &&
      !culled.changes[0].appliedAt,
    culled,
  );
  expect(
    post(`${RANGE}/reviews/${review2.id}/close`, { clusterId: cluster.id }),
    '[-] a review is closed once',
    409,
    'REVIEW_NOT_OPEN',
  );

  // ── who may do any of this ───────────────────────────────────────────────────────────────────────
  expect(
    post(`${MERCH}/fixtures`, { storeId: store, code: `X-${tag}`, name: 'x', kind: 'GONDOLA', shelfCount: 1, shelfWidthMm: 900 }, shop.cashier.token),
    '[-] a cashier does not re-lay a shelf',
    403,
  );
  expect(get(`${RANGE}/reviews`, shop.cashier.token), '[-] nor read the range decisions', 403);
  expect(get(`${MERCH}/planograms/${draft.id}`, rival), '[-] another business cannot read this one\'s layout', 404);
  expect(
    post(`${RANGE}/reviews/${review.id}/close`, { storeId: store }, rival),
    '[-] nor close its review by naming the id',
    404,
  );
  expect(call('GET', `${MERCH}/space?store=${store}`), '[-] an anonymous caller is refused', 401);
  expect(call('GET', `${GAPS}?storeId=${store}`), '[-] and so is an anonymous read of the shelf gaps', 401);

  completed.add(1);
}
