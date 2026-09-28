// Loyalty tiers and points expiry (13.x) through the gateway: the business's programme — tiers
// with their thresholds and earn multipliers, months a point lives, months of earning that count —
// set by management and refused by name when its shape cannot be honoured; a customer reaching a
// tier on qualifying points, the multiplier applied to the next till sale and said so on the
// ledger; points spent from the lot that dies first; the sweep run now finding nothing due yet; and
// the shopper's own view. Points dying is proved in customer-svc's LoyaltyProgrammeIT over
// back-dated lots — a stack that came up today has nothing a year old.
//
//   k6/run.sh loyalty-flow
import {
  ALL_CHECKS_PASS,
  call,
  data,
  expect,
  must,
  poll,
  sellingTenant,
  truthy,
  uniq,
} from './lib/storeql.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS, setupTimeout: '4m' };

const CUST = '/api/customer-svc';
const PROGRAMME = `${CUST}/admin/loyalty/programme`;
const LADDER = [
  { name: 'BRONZE', threshold: 0, multiplier: 1 },
  { name: 'SILVER', threshold: 10, multiplier: 1.5 },
  { name: 'GOLD', threshold: 50, multiplier: 2 },
];

export function setup() {
  return sellingTenant('loyalty', { price: '10.00', costPrice: '4.00' });
}

export default function ({ tenant, store, variantId, storekeeper, cashier }) {
  const owner = tenant.owner.token;
  const num = (v) => Number(v || 0);
  const programme = (body, token = owner) => call('PUT', PROGRAMME, { token, body });
  const loyalty = (customerId) => data(call('GET', `${CUST}/customers/${customerId}/loyalty`, { token: owner }));

  // ── 1. the programme: the default, then the business's own, refused by name when wrong ──
  const dflt = data(call('GET', PROGRAMME, { token: owner }));
  truthy('[+] a new business is on the platform default: four tiers, no expiry, lifetime qualification', dflt && dflt.isDefault === true && (dflt.tiers || []).length === 4 && dflt.expiryMonths == null, dflt);
  expect(call('GET', PROGRAMME, { token: cashier.token }), '[-] a cashier cannot read the programme', 403);
  expect(programme({ tiers: LADDER, expiryMonths: 12, qualifyingMonths: 12, reason: 'k6' }, storekeeper.token), '[-] a storekeeper cannot set it', 403);
  expect(programme({ tiers: [LADDER[0], LADDER[2], LADDER[1]], expiryMonths: 12, reason: 'k6' }), '[-] thresholds out of order are refused by name', 400, 'LOYALTY_TIERS_INVALID');
  expect(programme({ tiers: LADDER, expiryMonths: 0, reason: 'k6' }), '[-] a point that lives no months is refused by name', 400, 'LOYALTY_EXPIRY_INVALID');
  expect(programme({ tiers: LADDER, qualifyingMonths: 37, reason: 'k6' }), '[-] a qualifying window past three years is refused', 400, 'LOYALTY_EXPIRY_INVALID');
  expect(programme({ tiers: LADDER, expiryMonths: 12 }), '[-] a programme without a reason is refused', 400);
  expect(programme({ tiers: [{ name: 'bronze!', threshold: 0 }], reason: 'k6' }), '[-] a tier name is capitals, digits and underscores', 400, 'LOYALTY_TIERS_INVALID');
  const set = must(programme({ tiers: LADDER, expiryMonths: 12, qualifyingMonths: 12, reason: 'the autumn scheme' }), 200, 'programme set');
  truthy('[+] the programme is the business\'s own: three tiers, points living twelve months, a twelve-month window, the reason kept', set.isDefault === false && set.tiers.length === 3 && set.expiryMonths === 12 && set.qualifyingMonths === 12 && set.reason === 'the autumn scheme', set);
  truthy('[+] ...GOLD earns double', set.tiers.some((t) => t.name === 'GOLD' && num(t.multiplier) === 2), set.tiers);

  // ── 2. a customer reaches a tier on qualifying points, and the account shows the way up ──
  const customer = must(call('POST', `${CUST}/customers`, { token: owner, body: { email: `loyal-${uniq()}@k6.storeql.test`, firstName: 'Loyal', lastName: 'Shopper' } }), 201, 'customer');
  const fresh = loyalty(customer.id);
  truthy('[+] a new customer is BRONZE with nothing, SILVER ten points away, and points that will live twelve months', fresh.tier === 'BRONZE' && num(fresh.pointsBalance) === 0 && fresh.nextTier && fresh.nextTier.name === 'SILVER' && num(fresh.nextTier.pointsToGo) === 10 && fresh.expiryMonths === 12, fresh);
  const earned = must(call('POST', `${CUST}/customers/${customer.id}/loyalty/earn`, { token: owner, body: { points: 12, reason: 'welcome' } }), 200, 'twelve points');
  truthy('[+] twelve points reach SILVER', earned.tier === 'SILVER', earned);
  const silver = loyalty(customer.id);
  truthy('[+] ...the account says so: SILVER since now, twelve qualifying, ×1.5, GOLD thirty-eight away, nothing expiring soon', silver.tier === 'SILVER' && !!silver.tierSince && num(silver.qualifyingPoints) === 12 && num(silver.multiplier) === 1.5 && silver.nextTier.name === 'GOLD' && num(silver.nextTier.pointsToGo) === 38 && !silver.expiringSoon, silver);

  // ── 3. the multiplier on the next till sale, said so on the ledger ──────────
  const sale = must(call('POST', '/api/order-svc/orders', { token: owner, idem: true, body: { storeId: store.id, channel: 'POS', fulfilmentType: 'INSTORE', items: [{ variantId, qty: 1 }], customerId: customer.id } }), 201, 'a till sale for the customer');
  must(call('POST', '/api/payment-svc/payments', { token: owner, idem: true, body: { orderId: sale.id, amount: sale.total, method: 'CASH', storeId: store.id } }), [200, 201], 'paid in cash');
  const total = num(sale.total);
  let after = null;
  const accrued = poll(60, () => {
    after = loyalty(customer.id);
    return num(after.pointsBalance) > 12;
  });
  const expected = Math.floor(total * 1.5 * 100) / 100;
  truthy('[+] the sale earns a point per pound at SILVER\'s ×1.5', accrued >= 0 && Math.abs(num(after.pointsBalance) - (12 + expected)) < 0.011, { balance: after && after.pointsBalance, total, expected });
  const ledger = data(call('GET', `${CUST}/customers/${customer.id}/loyalty/ledger?limit=5`, { token: owner }));
  truthy('[+] ...and the ledger says which tier and multiplier did it', Array.isArray(ledger) && ledger.some((e) => e.type === 'EARN' && /SILVER ×1\.5/.test(e.reason || '')), ledger);

  // ── 4. spending and the sweep ───────────────────────────────────────────────
  must(call('POST', `${CUST}/customers/${customer.id}/loyalty/redeem`, { token: owner, body: { points: 5, reason: 'a discount' } }), 200, 'five spent');
  const spent = loyalty(customer.id);
  truthy('[+] spending leaves the tier and the qualifying points where they were', spent.tier === 'SILVER' && num(spent.qualifyingPoints) === num(silver.qualifyingPoints) + expected && Math.abs(num(spent.pointsBalance) - (7 + expected)) < 0.011, spent);
  const run = data(call('POST', `${CUST}/admin/loyalty/expiry/run`, { token: owner }));
  truthy('[+] the sweep run now finds nothing due: every point is under a year old and every tier holds', run && run.customers === 0 && num(run.points) === 0 && run.retiered === 0, run);
  expect(call('POST', `${CUST}/admin/loyalty/expiry/run`, { token: cashier.token }), '[-] a cashier cannot run the sweep', 403);

  // ── 5. lifting the expiry rule ──────────────────────────────────────────────
  const lifted = must(programme({ tiers: LADDER, qualifyingMonths: 12, reason: 'points live for ever again' }), 200, 'rule lifted');
  truthy('[+] with the expiry rule lifted, points live for ever and the account says so', lifted.expiryMonths == null && loyalty(customer.id).expiryMonths == null, lifted);
  expect(call('GET', PROGRAMME), '[-] no token', 401);
}
