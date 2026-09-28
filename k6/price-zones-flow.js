// Price zones and competitor-driven repricing (03.x) through the gateway: a business groups its
// stores into price zones; a price list bound to a zone is what its stores charge and every other
// store falls back to the tenant-wide list; a store sits in one zone at most. What a rival charges
// is recorded as seen (one at a time or in bulk), in the business's own currency; a repricing rule
// on the zone's list turns the freshest, lowest rival price into a proposal — undercut by 1%, down
// to a .99, never below 80% of the current price — which management applies into that list, so
// the zone's till moves and the other store's does not. Refused by name and by role throughout.
//
//   k6/run.sh price-zones-flow
import {
  ALL_CHECKS_PASS,
  call,
  data,
  ensureStandardVat,
  expect,
  must,
  onboardTenant,
  priceVariants,
  sellableVariant,
  staffUser,
  truthy,
  uniq,
} from './lib/storeql.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS, setupTimeout: '4m' };

const P = '/api/pricing-svc';
const NOBODY = '01a0b000-0000-7000-8000-000000000000';

export function setup() {
  const tenant = onboardTenant('zones', { stores: 2 });
  const rival = onboardTenant('zones-rival', { stores: 1 });
  ensureStandardVat(tenant);
  const { variantId } = sellableVariant(tenant, 'Zoned kettle');
  priceVariants(tenant, [variantId], '10.00');
  const cashier = staffUser(tenant, 'CASHIER', [tenant.stores[0].id]);
  return { tenant, rival, variantId, cashier };
}

export default function ({ tenant, rival, variantId, cashier }) {
  const owner = tenant.owner.token;
  const [south, north] = tenant.stores;
  const num = (v) => Number(v || 0);
  const priceAt = (storeId) =>
    num(data(call('POST', `${P}/prices/resolve`, { token: owner, body: { variantId, channel: 'POS', qty: 1, ...(storeId ? { storeId } : {}) } })).unitPrice);

  // ── 1. a zone, its stores, its own price list ───────────────────────────────
  const zone = must(call('POST', `${P}/admin/price-zones`, { token: owner, body: { name: `North ${uniq()}`, description: 'Up the M1' } }), 201, 'a price zone');
  truthy('[+] a new zone has no stores yet', Array.isArray(zone.storeIds) && zone.storeIds.length === 0, zone);
  const assigned = must(call('PUT', `${P}/admin/price-zones/${zone.id}/stores`, { token: owner, body: { storeIds: [north.id] } }), 200, 'the north store in it');
  truthy('[+] the zone lists the store put in it', assigned.storeIds.length === 1 && assigned.storeIds[0] === north.id, assigned);
  const zoned = must(call('POST', `${P}/admin/price-lists`, { token: owner, body: { name: `North prices ${uniq()}`, channel: 'ALL', currency: 'GBP', effectiveFrom: '2024-01-01T00:00:00Z', zoneId: zone.id } }), 201, "the zone's price list");
  truthy('[+] the list is bound to the zone', zoned.zoneId === zone.id, zoned);
  must(call('POST', `${P}/admin/price-lists/${zoned.id}/items`, { token: owner, body: { variantId, price: 9.0, minQty: 1 } }), 200, 'nine pounds in the north');

  truthy('[+] the north store charges the zone price', priceAt(north.id) === 9, null);
  truthy('[+] the south store charges the tenant-wide price', priceAt(south.id) === 10, null);
  truthy('[+] no store named: the tenant-wide price, never a zone\'s', priceAt(null) === 10, null);

  // ── 2. refused by name, and by role ────────────────────────────────────────
  expect(call('POST', `${P}/admin/price-zones`, { token: owner, body: { name: zone.name } }), '[-] a second zone of the same name', 409, 'PRICING_ZONE_NAME_EXISTS');
  expect(call('PUT', `${P}/admin/price-zones/${zone.id}/stores`, { token: owner, body: { storeIds: [rival.stores[0].id] } }), "[-] another business's store is not ours to price", 400, 'PRICING_ZONE_STORE_UNKNOWN');
  expect(call('PUT', `${P}/admin/price-zones/${NOBODY}/stores`, { token: owner, body: { storeIds: [north.id] } }), '[-] a zone nobody made', 404, 'PRICING_ZONE_NOT_FOUND');
  expect(call('POST', `${P}/admin/price-lists`, { token: owner, body: { name: `Nowhere ${uniq()}`, channel: 'ALL', currency: 'GBP', effectiveFrom: '2024-01-01T00:00:00Z', zoneId: NOBODY } }), '[-] a list bound to a zone nobody made', 400, 'PRICING_ZONE_UNKNOWN');
  expect(call('POST', `${P}/admin/price-zones`, { token: cashier.token, body: { name: 'Till' } }), '[-] a cashier does not draw zones', 403);
  expect(call('GET', `${P}/admin/price-zones`, { token: rival.owner.token }), '[+] another business reads only its own zones', 200);
  truthy("[+] ...and has none of ours", data(call('GET', `${P}/admin/price-zones`, { token: rival.owner.token })).length === 0, null);

  // ── 3. rivals seen, one at a time and in bulk ──────────────────────────────
  const today = new Date().toISOString().slice(0, 10);
  const seen = must(call('POST', `${P}/admin/competitor-prices`, { token: owner, body: { variantId, competitor: 'Rival A', price: 8.5, zoneId: zone.id, observedOn: today } }), 201, 'Rival A at 8.50 in the north');
  truthy('[+] the sighting is kept in the business\'s own currency, typed by hand', seen.currency === 'GBP' && seen.source === 'MANUAL' && seen.competitor === 'Rival A', seen);
  const batch = must(call('POST', `${P}/admin/competitor-prices/batch`, { token: owner, body: { observations: [
    { variantId, competitor: 'Rival B', price: 8.9, zoneId: zone.id, observedOn: today },
    { variantId, competitor: 'Rival A', price: 7.0, observedOn: '2026-01-01' },
  ] } }), 200, 'two more in bulk');
  truthy('[+] the bulk import records every row', batch.recorded === 2, batch);
  truthy('[+] the sightings read back newest first', data(call('GET', `${P}/admin/competitor-prices?variantId=${variantId}`, { token: owner })).length === 3, null);
  expect(call('POST', `${P}/admin/competitor-prices`, { token: owner, body: { variantId, competitor: 'Rival C', price: 8.5, currency: 'USD' } }), '[-] a rival\'s price in another currency', 400, 'PRICING_COMPETITOR_CURRENCY_MISMATCH');
  expect(call('POST', `${P}/admin/competitor-prices`, { token: owner, body: { variantId, competitor: 'Rival C', price: 8.5, observedOn: '2999-01-01' } }), '[-] a sighting from the future', 400, 'PRICING_COMPETITOR_DATE_INVALID');
  expect(call('POST', `${P}/admin/competitor-prices`, { token: cashier.token, body: { variantId, competitor: 'Rival C', price: 8.5 } }), '[-] a cashier does not record rivals', 403);

  // ── 4. a rule on the zone's list: proposed, applied, and only the zone moves ─
  const rule = must(call('POST', `${P}/admin/repricing/rules`, { token: owner, body: { name: `North undercut ${uniq()}`, priceListId: zoned.id, strategy: 'UNDERCUT_PERCENT', value: 1, floorPercent: 80, rounding: 'ENDING_99', maxAgeDays: 14 } }), 201, 'a rule');
  truthy("[+] the rule's zone is its list's", rule.zoneId === zone.id, rule);
  expect(call('POST', `${P}/admin/repricing/rules`, { token: owner, body: { name: `Guess ${uniq()}`, priceListId: zoned.id, strategy: 'GUESS', floorPercent: 80 } }), '[-] a strategy nobody knows', 400, 'REPRICING_STRATEGY_INVALID');
  expect(call('POST', `${P}/admin/repricing/rules`, { token: owner, body: { name: `Too deep ${uniq()}`, priceListId: zoned.id, strategy: 'UNDERCUT_PERCENT', value: 150, floorPercent: 80 } }), '[-] an undercut of more than everything', 400, 'REPRICING_VALUE_INVALID');
  expect(call('POST', `${P}/admin/repricing/rules`, { token: owner, body: { name: `No list ${uniq()}`, priceListId: NOBODY, strategy: 'MATCH_LOWEST', floorPercent: 80 } }), '[-] a rule on a list nobody made', 400, 'PRICING_LIST_UNKNOWN');

  const run = must(call('POST', `${P}/admin/repricing/rules/${rule.id}/run`, { token: owner, body: {} }), 200, 'the rule run');
  // 8.50 × 0.99 = 8.415 → down to 7.99; the floor of 7.20 holds; Rival A's January 7.00 is stale.
  truthy('[+] one proposal: 9.00 → 7.99 against Rival A at 8.50', run.proposed === 1 && run.proposals[0].competitor === 'Rival A' && num(run.proposals[0].competitorPrice) === 8.5 && num(run.proposals[0].currentPrice) === 9 && num(run.proposals[0].proposedPrice) === 7.99, run);
  const proposal = run.proposals[0];
  const again = must(call('POST', `${P}/admin/repricing/rules/${rule.id}/run`, { token: owner, body: {} }), 200, 'run again');
  truthy('[+] a second run refreshes the open proposal rather than piling up', again.proposed === 1 && data(call('GET', `${P}/admin/repricing/proposals`, { token: owner })).length === 1, again);

  const applied = must(call('POST', `${P}/admin/repricing/proposals/${proposal.id}/apply`, { token: owner, body: {} }), 200, 'applied');
  truthy('[+] the proposal is applied', applied.status === 'APPLIED', applied);
  truthy('[+] the north store now charges 7.99', priceAt(north.id) === 7.99, null);
  truthy('[+] the south store still charges 10.00', priceAt(south.id) === 10, null);
  expect(call('POST', `${P}/admin/repricing/proposals/${proposal.id}/dismiss`, { token: owner, body: {} }), '[-] a proposal is decided once', 409, 'REPRICING_PROPOSAL_DECIDED');
  expect(call('POST', `${P}/admin/repricing/proposals/${NOBODY}/apply`, { token: owner, body: {} }), '[-] a proposal nobody made', 404, 'REPRICING_PROPOSAL_NOT_FOUND');
  const settled = must(call('POST', `${P}/admin/repricing/rules/${rule.id}/run`, { token: owner, body: {} }), 200, 'run once more');
  truthy('[+] at 7.99 against a rival at 8.50 there is nothing left to propose', settled.proposed === 0, settled);
  truthy('[+] the applied proposal is on the record', data(call('GET', `${P}/admin/repricing/proposals?status=APPLIED`, { token: owner })).length === 1, null);
  expect(call('GET', `${P}/admin/repricing/proposals`, { token: cashier.token }), '[-] a cashier reads no proposals', 403);
}
