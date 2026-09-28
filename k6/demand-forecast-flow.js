// The statistical demand forecast (06.x) through the gateway: the run for a store, what it says
// about a shop that opened today (nothing yet, and it says so), the forecasts read back, the
// reorder-point computation with the forecast seam in place, the promotion windows pricing-svc
// answers for the store (what the forecast reads for its uplift), and the refusals — a horizon
// outside a day to a year, a store that is not an id, a keeper held to their own store, a rival, no
// token.
// The arithmetic itself is proved in inventory-svc's ForecastingTest and ForecastIT over months of
// seeded history; a stack that came up today has one day of sales, and they land in the buckets
// tomorrow.
//
//   k6/run.sh demand-forecast-flow
import {
  ALL_CHECKS_PASS,
  call,
  data,
  expect,
  onboardTenant,
  priceVariants,
  receive,
  sellableVariant,
  staffUser,
  truthy,
  uniq,
} from './lib/storeql.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS, setupTimeout: '4m' };

const INV = '/api/inventory-svc/admin/inventory';

export function setup() {
  const tenant = onboardTenant(`forecast-${uniq()}`, { stores: 2 });
  const { variantId } = sellableVariant(tenant, 'Forecast beans');
  priceVariants(tenant, [variantId], '2.00');
  const keeper = staffUser(tenant, 'STOREKEEPER', [tenant.stores[1].id]);
  const rival = onboardTenant(`forecast-rival-${uniq()}`);
  return { tenant, variantId, keeper, rival };
}

export default function ({ tenant, variantId, keeper, rival }) {
  const owner = tenant.owner.token;
  const [a, b] = tenant.stores;

  // ── something to forecast from, one day of it ───────────────────────────────
  expect(receive(tenant, a.id, variantId, 50), '[+] fifty beans received at A', 201);
  const order = call('POST', '/api/order-svc/orders', {
    token: owner,
    idem: true,
    body: { storeId: a.id, channel: 'POS', fulfilmentType: 'INSTORE', paymentMethod: 'CASH', items: [{ variantId, qty: 3 }] },
  });
  expect(order, '[+] three sold at the till', 201);
  expect(
    call('POST', '/api/payment-svc/payments', {
      token: owner,
      idem: true,
      body: { orderId: data(order).id, amount: data(order).total, method: 'CASH', storeId: a.id, currency: tenant.currency || 'GBP' },
    }),
    '[+] and paid for',
    [200, 201]
  );

  // ── a week of the beans on promotion, which the forecast reads as a window ──
  const PR = '/api/pricing-svc';
  const promo = call('POST', `${PR}/admin/promotions`, {
    token: owner,
    body: { name: `Beans week ${uniq()}`, type: 'PERCENT', value: 20, channel: 'ALL', storeId: a.id, startsAt: new Date(Date.now() - 86400000).toISOString(), endsAt: new Date(Date.now() + 6 * 86400000).toISOString(), priority: 1 },
  });
  expect(promo, '[+] a week of twenty percent off at A', 201);
  expect(call('POST', `${PR}/admin/promotions/${data(promo).id}/items`, { token: owner, body: { scopeType: 'VARIANT', scopeId: variantId } }), '[+] ...scoped to the beans', [200, 201]);
  const windows = call('GET', `${PR}/admin/promotions/windows?store=${a.id}`, { token: keeper.token });
  expect(windows, "[+] the store's promotion windows read as staff — the read inventory-svc makes for its forecast", 200);
  truthy(
    '[+] ...the beans week among them: scoped to the beans, at A, ending in six days',
    (data(windows) || []).some((w) => w.promotionId === data(promo).id && !w.allVariants && (w.variantIds || []).includes(variantId) && w.storeId === a.id && !!w.endsAt),
    data(windows)
  );
  expect(call('GET', `${PR}/admin/promotions/windows`, { token: owner }), '[-] the windows are of a store', 400, 'STORE_REQUIRED');
  expect(call('GET', `${PR}/admin/promotions/windows?store=${a.id}&from=next-week`, { token: owner }), '[-] from is a date', 400, 'PRICING_INVALID_DATE');

  // ── the run ─────────────────────────────────────────────────────────────────
  const run = call('POST', `${INV}/forecasts/run`, { token: owner, body: { storeId: a.id, horizonDays: 28 } });
  expect(run, '[+] the forecast runs for the store, the promotion windows read', 200);
  truthy('[+] ...and says honestly that a shop that opened today has no history to forecast from yet', data(run).variants === 0 && data(run).horizonDays === 28 && data(run).storeId === a.id, data(run));
  truthy('[+] ...so nothing is seasonal or promoted yet either, and it says so', data(run).seasonal === 0 && data(run).promoted === 0, data(run));
  const defaulted = call('POST', `${INV}/forecasts/run`, { token: owner, body: { storeId: a.id } });
  expect(defaulted, '[+] the horizon defaults to four weeks', 200);
  truthy('[+] ...twenty-eight days', data(defaulted).horizonDays === 28, data(defaulted));
  const list = call('GET', `${INV}/forecasts?store=${a.id}`, { token: owner });
  expect(list, "[+] the store's forecasts read", 200);
  truthy('[+] ...an empty list rather than an error', Array.isArray(data(list)) && data(list).length === 0, data(list));
  expect(call('GET', `${INV}/forecasts/${a.id}/${variantId}`, { token: owner }), '[-] no forecast for the beans yet, and it says so', 404, 'FORECAST_NOT_FOUND');

  // ── the reorder point, with the forecast seam in place ──────────────────────
  expect(
    call('PUT', `${INV}/rop-plans`, { token: owner, body: { storeId: a.id, variantId, leadTimeDays: 7, orderingCost: 50, holdingCostPct: 0.2, unitCost: 2 } }),
    '[+] a reorder plan for the beans',
    200
  );
  expect(call('POST', `${INV}/rop-plans/compute?store=${a.id}`, { token: owner }), '[+] reorder points computed: the forecast when there is one, the monthly average when not', 200);

  // ── refusals ────────────────────────────────────────────────────────────────
  expect(call('POST', `${INV}/forecasts/run`, { token: owner, body: { storeId: a.id, horizonDays: 0 } }), '[-] a horizon of no days', 400, 'FORECAST_HORIZON_INVALID');
  expect(call('POST', `${INV}/forecasts/run`, { token: owner, body: { storeId: a.id, horizonDays: 400 } }), '[-] a horizon past a year', 400, 'FORECAST_HORIZON_INVALID');
  expect(call('POST', `${INV}/forecasts/run`, { token: owner, body: { storeId: 'the shop' } }), '[-] a store is an id', 400);
  expect(call('POST', `${INV}/forecasts/run`, { token: owner, body: {} }), '[-] a store is required', 400);
  expect(call('POST', `${INV}/forecasts/run`, { token: keeper.token, body: { storeId: a.id } }), '[-] a keeper of B does not forecast A', 403, 'STORE_ACCESS_DENIED');
  expect(call('GET', `${INV}/forecasts?store=${a.id}`, { token: keeper.token }), "[-] nor read A's forecasts", 403, 'STORE_ACCESS_DENIED');
  expect(call('GET', `${INV}/forecasts/${a.id}/${variantId}`, { token: keeper.token }), '[-] not even one at a time', 403, 'STORE_ACCESS_DENIED');
  expect(call('GET', `${INV}/forecasts`, { token: keeper.token }), '[+] a keeper of one store reads it without naming it', 200);
  expect(call('POST', `${INV}/forecasts/run`, { token: keeper.token, body: { storeId: b.id } }), '[+] and forecasts their own store', 200);
  expect(call('GET', `${INV}/forecasts`, { token: owner }), '[-] an owner of two stores names the one to read', 400, 'STORE_REQUIRED');
  expect(call('GET', `${INV}/forecasts/${a.id}/${variantId}`, { token: rival.owner.token }), "[-] another business's store has nothing of ours to read", 404);
  expect(call('GET', `${INV}/forecasts?store=${a.id}`), '[-] no token', 401);
}
