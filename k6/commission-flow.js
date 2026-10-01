// Commission and sales attribution (store operations & workforce), through the gateway.
//
// A shop that pays commission needs two things the platform had neither of: who a sale is credited to
// — which is NOT who rang it up, the only thing the POS journal knew — and the arrangement that turns
// a month of sales into money owed. They live in different services on purpose: the arrangement is a
// term of employment beside the pay rates in tenant-svc, and the sales are order-svc's, so the figures
// go over and the money comes back, and no order ever leaves the service that owns it.
//
// What only a live stack proves is the hop itself: the marginal bands are computed by the real
// tenant-svc from real figures, so a tiered scheme is asked the question a statement asks it. And who
// may press what: what anybody is owed is management's business, and the gateway gates the whole
// /admin/ prefix.
//
// The assertions that cost somebody money: bands are marginal (the part above a threshold earns the
// higher rate, and crossing it never re-rates what came before), a corrected scheme leaves the old
// rates alone and moves the people across from a day, a period still trading cannot be stated, and an
// approved statement is frozen — restated by a new one, never edited.
//
// Refused: a scheme with no bands, a first band above zero, a per-unit scheme with no currency, an
// unknown basis, two arrangements starting on one day, somebody who is not staff, withdrawing a scheme
// people are still on, correcting a version already replaced, a statement for a period that has not
// finished, a second statement for a period already approved, approving twice, discarding what was
// approved, a cashier reading or writing any of it, and another business touching any of it.
//
//   k6/run.sh commission-flow
import { Counter } from 'k6/metrics';
import { ALL_CHECKS_PASS, call, data, expect, sellingTenant, truthy, uniq } from './lib/storeql.js';

const completed = new Counter('flow_completed');
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
  setupTimeout: '4m',
};

const C = '/api/tenant-svc/admin/workforce/commission';
const O = '/api/order-svc/admin/commission';

/** A date that many days back, as a statement names one. */
function day(daysAgo) {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() - daysAgo);
  return d.toISOString().slice(0, 10);
}

export function setup() {
  const shop = sellingTenant(`commission-${uniq().slice(0, 6)}`);
  return { shop, rival: shop.rival };
}

export default function ({ shop, rival }) {
  const owner = shop.tenant.owner.token;
  const cashier = shop.cashier;
  const keeper = shop.storekeeper;
  const store = shop.store.id;
  const variant = shop.variantId;
  const post = (path, body, token) => call('POST', path, { token: token || owner, body });
  // A sale is a retryable write, so the gateway insists on an Idempotency-Key: without one every
  // order below answers 400 MISSING_IDEMPOTENCY_KEY, which is the door doing its job.
  const sell = (body) => call('POST', '/api/order-svc/orders', { token: owner, idem: true, body });
  const put = (path, body, token) => call('PUT', path, { token: token || owner, body });
  const get = (path, token) => call('GET', path, { token: token || owner });
  const del = (path, token) => call('DELETE', path, { token: token || owner });

  // ── the arrangement ──────────────────────────────────────────────────────────────────────────────
  const flat = data(
    post(`${C}/schemes`, { name: 'Counter 2%', basis: 'PERCENT_OF_NET', bands: [{ thresholdFrom: 0, rate: 2 }] }),
  );
  truthy('[+] a flat arrangement is recorded, active, with one band', flat && flat.status === 'ACTIVE' && flat.bands.length === 1, flat);
  truthy('[+] ...and a percentage carries no currency, because a ratio is not an amount', flat && !flat.currency, flat);

  const tiered = data(
    post(`${C}/schemes`, {
      name: 'Counter tiered',
      basis: 'PERCENT_OF_NET',
      bands: [{ thresholdFrom: 0, rate: 1 }, { thresholdFrom: 1000, rate: 5 }],
    }),
  );
  truthy('[+] a tiered arrangement keeps its bands in order', tiered && tiered.bands.map((b) => b.thresholdFrom).join() === '0.00,1000.00', tiered && tiered.bands);

  expect(post(`${C}/schemes`, { name: 'No bands', basis: 'PERCENT_OF_NET', bands: [] }), '[-] a scheme with no bands earns nothing and is refused', 400);
  expect(
    post(`${C}/schemes`, { name: 'Starts high', basis: 'PERCENT_OF_NET', bands: [{ thresholdFrom: 500, rate: 2 }] }),
    '[-] a first band above zero would leave the first sales of every period earning nothing',
    400,
    'COMMISSION_SCHEME_INVALID',
  );
  expect(
    post(`${C}/schemes`, { name: 'Per unit', basis: 'PER_UNIT', bands: [{ thresholdFrom: 0, rate: 1 }] }),
    '[-] a per-unit scheme pays an amount, so it needs a currency',
    400,
    'COMMISSION_SCHEME_INVALID',
  );
  expect(
    post(`${C}/schemes`, { name: 'Margin', basis: 'PERCENT_OF_MARGIN', bands: [{ thresholdFrom: 0, rate: 2 }] }),
    '[-] an unknown basis is refused rather than guessed',
    400,
    'COMMISSION_SCHEME_INVALID',
  );

  // ── who is on it ─────────────────────────────────────────────────────────────────────────────────
  expect(put(`${C}/staff/${cashier.userId}`, { schemeId: tiered.id, effectiveFrom: day(400) }), '[+] somebody is put on an arrangement from a day', 201);
  expect(
    put(`${C}/staff/${cashier.userId}`, { schemeId: tiered.id, effectiveFrom: day(400) }),
    '[-] two arrangements starting on one day leave the rate undecidable',
    409,
    'COMMISSION_ARRANGEMENT_EXISTS',
  );
  // A path that is not an id at all: refused by the door, not read as somebody.
  expect(put(`${C}/staff/not-a-person`, { schemeId: flat.id }), '[-] a path that is not an id is refused', 400, 'INVALID_UUID');
  const history = data(get(`${C}/staff/${cashier.userId}`));
  truthy('[+] the arrangement reads back as a history, newest first', Array.isArray(history) && history.length === 1 && history[0].schemeId === tiered.id, history);

  // ── what a period of sales earns, computed by the service that holds the arrangement ─────────────
  const rated = data(
    post(`${C}/rate`, {
      from: day(400),
      to: day(370),
      sellers: [
        { userId: cashier.userId, days: [{ day: day(395), net: '600.00' }, { day: day(390), net: '900.00' }] },
      ],
    }),
  );
  const person = rated && rated[0];
  truthy('[+] 1500 of sales under 1% to a thousand then 5% above it earns 10 + 25', person && person.commission === '35.00', person);
  truthy('[+] ...as two bands, so somebody paid on it can see which part earned which rate', person && person.segments[0].bands.length === 2, person && person.segments[0]);
  truthy(
    '[+] ...and the threshold belongs to the band above it: the first thousand stays at 1%',
    person && person.segments[0].bands[0].commission === '10.00' && person.segments[0].bands[1].commission === '25.00',
    person && person.segments[0].bands,
  );
  const atThreshold = data(
    post(`${C}/rate`, { from: day(400), to: day(370), sellers: [{ userId: cashier.userId, days: [{ day: day(395), net: '1000.00' }] }] }),
  );
  truthy('[+] exactly at the threshold earns the lower band only, so crossing it re-rates nothing', atThreshold[0].commission === '10.00', atThreshold[0]);
  const unearned = data(
    post(`${C}/rate`, { from: day(400), to: day(370), sellers: [{ userId: keeper.userId, days: [{ day: day(395), net: '500.00' }] }] }),
  );
  truthy(
    '[+] somebody on no arrangement has their sales reported and earns nothing, rather than being left out',
    unearned[0].commission === '0.00' && unearned[0].segments[0].amount === '500.00' && !unearned[0].segments[0].schemeId,
    unearned[0],
  );
  expect(post(`${C}/rate`, { from: day(370), to: day(400), sellers: [] }), '[-] a period that ends before it starts is refused', 400, 'COMMISSION_PERIOD_INVALID');

  // ── a correction leaves the old rates alone ──────────────────────────────────────────────────────
  const corrected = data(
    post(`${C}/schemes/${tiered.id}/corrections`, {
      name: 'Counter tiered',
      basis: 'PERCENT_OF_NET',
      effectiveFrom: day(380),
      bands: [{ thresholdFrom: 0, rate: 2 }, { thresholdFrom: 1000, rate: 6 }],
    }),
  );
  truthy('[+] a correction is a new version naming the one it replaced', corrected && corrected.supersedes === tiered.id, corrected);
  const old = data(get(`${C}/schemes/${tiered.id}`));
  truthy('[+] ...and the old version keeps its rates, because commission was earned under them', old && old.bands[0].rate === '1.0000' && old.supersededBy === corrected.id, old);
  const split = data(
    post(`${C}/rate`, {
      from: day(400),
      to: day(370),
      sellers: [{ userId: cashier.userId, days: [{ day: day(395), net: '500.00' }, { day: day(375), net: '500.00' }] }],
    }),
  );
  truthy(
    '[+] a month spanning the correction is two segments, each under its own rates: 5 + 10',
    split[0].segments.length === 2 && split[0].commission === '15.00',
    split[0],
  );
  truthy(
    '[+] ...and the new arrangement starts its own band progression rather than inheriting the climb',
    split[0].segments[1].bands[0].thresholdFrom === '0.00',
    split[0].segments[1],
  );
  expect(post(`${C}/schemes/${tiered.id}/corrections`, { name: 'Again', basis: 'PERCENT_OF_NET', bands: [{ thresholdFrom: 0, rate: 9 }] }), '[-] a version already replaced is not corrected again', 409, 'COMMISSION_SCHEME_SUPERSEDED');
  expect(put(`${C}/staff/${keeper.userId}`, { schemeId: tiered.id }), '[-] nor is anybody put on it', 409, 'COMMISSION_SCHEME_NOT_CURRENT');
  expect(del(`${C}/schemes/${corrected.id}`), '[-] a scheme people are still on is not withdrawn quietly', 409, 'COMMISSION_SCHEME_IN_USE');
  expect(put(`${C}/staff/${keeper.userId}`, { schemeId: flat.id, effectiveFrom: day(3) }), '[+] the storekeeper goes on the flat scheme', 201);
  // Off it yesterday, having gone on it three days ago: whether anybody is still on a scheme is asked
  // of today, so an arrangement that ends before it begins leaves them on it.
  expect(put(`${C}/staff/${keeper.userId}`, { effectiveFrom: day(1) }), '[+] and comes off it, which is a decision and not an absence', 201);
  expect(del(`${C}/schemes/${flat.id}`), '[+] once nobody is on it, it can be withdrawn', 200);
  truthy('[+] ...and is gone from what is offered, while still there to explain a statement', (data(get(`${C}/schemes`)) || []).every((s) => s.id !== flat.id) && (data(get(`${C}/schemes?all=true`)) || []).some((s) => s.id === flat.id), 'withdrawn');

  // ── who a sale is credited to ────────────────────────────────────────────────────────────────────
  const sale = data(
    sell({
      storeId: store,
      channel: 'POS',
      fulfilmentType: 'INSTORE',
      currency: shop.tenant.currency,
      items: [{ variantId: variant, qty: 1 }],
      sellerUserId: cashier.userId,
    }),
  );
  truthy('[+] a till sale is credited to the assistant the till named, not to whoever took the money', sale && sale.sellerUserId === cashier.userId, sale);
  const ownSale = data(
    sell({
      storeId: store,
      channel: 'POS',
      fulfilmentType: 'INSTORE',
      currency: shop.tenant.currency,
      items: [{ variantId: variant, qty: 1 }],
    }),
  );
  truthy('[+] with nobody named, a till sale is credited to whoever is operating it', ownSale && ownSale.sellerUserId === shop.tenant.owner.userId, ownSale);
  expect(
    sell({
      storeId: store,
      channel: 'ONLINE',
      fulfilmentType: 'PICKUP',
      currency: shop.tenant.currency,
      items: [{ variantId: variant, qty: 1 }],
      sellerUserId: cashier.userId,
    }),
    '[-] an online sale is credited to nobody: a website does the selling',
    400,
    'ORDER_SELLER_POS_ONLY',
  );

  const credited = data(put(`${O}/sales/${sale.id}/seller`, { sellerUserId: keeper.userId, reason: 'the storekeeper served them' }));
  truthy('[+] a sale is re-credited, and who changed it and why is kept', credited && credited.fromUserId === cashier.userId && credited.toUserId === keeper.userId, credited);
  // The boundary answers first: reason is @NotBlank, so a missing one is VALIDATION_FAILED with the
  // field named, and the service's own COMMISSION_REASON_REQUIRED stands behind it for callers that
  // are not HTTP. Either way the change is refused, which is the part that matters — commission
  // follows attribution.
  expect(put(`${O}/sales/${sale.id}/seller`, { sellerUserId: cashier.userId }), '[-] without a reason it is refused: commission follows the change', 400, 'VALIDATION_FAILED');
  const changes = data(get(`${O}/sales/${sale.id}/seller`));
  truthy('[+] the history reads newest first', Array.isArray(changes) && changes.length === 1 && changes[0].reason === 'the storekeeper served them', changes);
  // A well-formed id nobody holds, written out rather than generated: a random digit run in a request
  // can be a Luhn-valid card number, and the gateway would refuse it for that instead.
  expect(get(`${O}/sales/01900000-0000-7000-8000-0000000000ab/seller`), '[-] a sale nobody holds is not there', 404);

  // ── the statement ────────────────────────────────────────────────────────────────────────────────
  expect(post(`${O}/statements`, { from: day(3), to: day(0) }), '[-] a period still trading cannot be stated: it would be paid and then contradicted', 400, 'COMMISSION_PERIOD_INVALID');
  const statement = data(post(`${O}/statements`, { from: day(400), to: day(390) }));
  truthy('[+] a statement for a finished period is drafted, in the business own currency', statement && statement.status === 'DRAFT' && statement.currency === shop.tenant.currency, statement);
  truthy('[+] ...and today sales are not in it, because they are not in that period', statement && statement.netSales === '0.00', statement);
  expect(post(`${O}/statements/${statement.id}/approval`, {}), '[+] approved, which freezes it', 200);
  const frozen = data(get(`${O}/statements/${statement.id}`));
  truthy('[+] ...with who approved it and when', frozen && frozen.status === 'APPROVED' && frozen.approvedBy === shop.tenant.owner.userId && !!frozen.approvedAt, frozen);
  expect(post(`${O}/statements/${statement.id}/approval`, {}), '[-] approving twice is refused', 409, 'COMMISSION_STATEMENT_NOT_DRAFT');
  expect(del(`${O}/statements/${statement.id}`), '[-] an approved statement is a record: it is restated, never thrown away', 409, 'COMMISSION_STATEMENT_NOT_DRAFT');
  expect(post(`${O}/statements`, { from: day(400), to: day(390) }), '[-] a second statement for a period already approved is refused', 409, 'COMMISSION_STATEMENT_STANDS');

  const restated = data(post(`${O}/statements`, { from: day(400), to: day(390), supersedes: statement.id }));
  truthy('[+] a restatement names the statement it replaces', restated && restated.supersedes === statement.id, restated);
  truthy('[+] ...and the first one stands until it is approved, so a month is never left with nothing', data(get(`${O}/statements/${statement.id}`)).status === 'APPROVED', 'still approved');
  expect(post(`${O}/statements/${restated.id}/approval`, {}), '[+] approving the restatement closes the old one', 200);
  truthy('[+] ...which is superseded, pointing at what replaced it', data(get(`${O}/statements/${statement.id}`)).supersededBy === restated.id, 'superseded');
  const drafts = data(post(`${O}/statements`, { from: day(380), to: day(375) }));
  expect(del(`${O}/statements/${drafts.id}`), '[+] a draft is a working document and can be thrown away', 204);
  expect(get(`${O}/statements/${drafts.id}`), '[-] ...and is then gone', 404);
  expect(get(`${O}/statements?status=PAID`), '[-] a status that does not exist is refused', 400, 'COMMISSION_STATEMENT_STATUS_UNKNOWN');

  // ── who may see what anybody is owed ─────────────────────────────────────────────────────────────
  expect(get(`${C}/schemes`, cashier.token), '[-] a cashier does not read the arrangements', 403);
  expect(post(`${C}/schemes`, { name: 'Mine', basis: 'PERCENT_OF_NET', bands: [{ thresholdFrom: 0, rate: 50 }] }, cashier.token), '[-] nor writes one', 403);
  expect(post(`${C}/rate`, { from: day(400), to: day(390), sellers: [] }, cashier.token), '[-] nor asks what sales earn', 403);
  expect(get(`${O}/statements`, cashier.token), '[-] nor reads the statements', 403);
  expect(put(`${O}/sales/${sale.id}/seller`, { sellerUserId: cashier.userId, reason: 'crediting myself' }, cashier.token), '[abuse] nor credits a sale to themselves', 403);

  const rivalToken = rival.owner.token;
  truthy('[abuse] another business sees none of these arrangements', (data(get(`${C}/schemes?all=true`, rivalToken)) || []).length === 0, 'rival schemes');
  expect(get(`${C}/schemes/${corrected.id}`, rivalToken), '[abuse] nor reads one by id', 404, 'COMMISSION_SCHEME_NOT_FOUND');
  expect(get(`${O}/statements/${statement.id}`, rivalToken), '[abuse] nor a statement', 404);
  expect(put(`${O}/sales/${sale.id}/seller`, { sellerUserId: keeper.userId, reason: 'not theirs to credit' }, rivalToken), '[abuse] nor credits this business sale', 404);
  const rivalRated = data(post(`${C}/rate`, { from: day(400), to: day(390), sellers: [{ userId: cashier.userId, days: [{ day: day(395), net: '1000.00' }] }] }, rivalToken));
  truthy('[abuse] and rating another business person under their own arrangements earns nothing', rivalRated[0].commission === '0.00', rivalRated[0]);

  completed.add(1);
}
