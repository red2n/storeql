// pricing-svc: VAT rates, price lists and their lifecycle, price resolution, promotions and basket
// quotes, price overrides and the VAT return — with the refusals around each.
//
//   k6/run.sh pricing-crud
import {
  ALL_CHECKS_PASS,
  call,
  data,
  ensureStandardVat,
  expect,
  onboardTenant,
  poll,
  register,
  sellableVariant,
  truthy,
  uniq,
} from './lib/storeql.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS, setupTimeout: '3m' };

const UNKNOWN = '01a0b000-0000-7000-8000-000000000000';
const YESTERDAY = () => new Date(Date.now() - 86400000).toISOString();

export function setup() {
  const tenant = onboardTenant('pricing', { stores: 1 });
  const rival = onboardTenant('pricing-rival', { stores: 1 });
  tenant.variantId = sellableVariant(tenant, 'Priced kettle').variantId;
  tenant.unpricedId = sellableVariant(tenant, 'Unpriced toaster').variantId;
  return { tenant, rival, shopper: register('pricing-shopper') };
}

export default function ({ tenant, rival, shopper }) {
  const t = tenant.owner.token;
  const storeId = tenant.stores[0].id;
  const variantId = tenant.variantId;

  // ── VAT rates ───────────────────────────────────────────────────────────────
  // A tenant's VAT codes are unique and at most 8 characters, like HMRC's T0/T1/T5.
  const code = `S${uniq()}`.slice(-8);
  const vat = { code, name: 'Standard', rate: 0.2, exempt: false, description: '20%', effectiveFrom: '2020-01-01T00:00:00Z' };
  expect(call('POST', '/api/pricing-svc/vat-rates', { token: t, body: vat }), '[+] create VAT rate', 201);
  expect(call('POST', '/api/pricing-svc/vat-rates', { token: t, body: vat }), '[-] VAT code taken', 409, 'PRICING_VAT_CODE_EXISTS');
  expect(call('POST', '/api/pricing-svc/vat-rates', { token: t, body: { ...vat, code: undefined } }), '[-] VAT rate: code required', 400, 'VALIDATION_FAILED');
  expect(call('POST', '/api/pricing-svc/vat-rates', { token: t, body: { ...vat, code: 'NEG1', rate: -0.1 } }), '[-] VAT rate: not negative', 400, 'VALIDATION_FAILED');
  expect(call('POST', '/api/pricing-svc/vat-rates', { token: t, body: { ...vat, code: 'PCT1', rate: 20 } }), '[-] VAT rate: a fraction, not a percentage', 400, 'VALIDATION_FAILED');
  expect(call('POST', '/api/pricing-svc/vat-rates', { token: t, body: { ...vat, code: 'STANDARD9' } }), '[-] VAT code longer than 8 characters', 400, 'VALIDATION_FAILED');
  expect(call('POST', '/api/pricing-svc/vat-rates', { token: shopper.token, body: { ...vat, code: 'SHOP' } }), '[-] a customer cannot create VAT rates', 403, 'FORBIDDEN');
  expect(call('GET', '/api/pricing-svc/vat-rates', { token: t }), '[+] list VAT rates', 200);
  expect(call('GET', `/api/pricing-svc/vat-rates/${code}`, { token: t }), '[+] get VAT rate', 200);
  expect(call('GET', '/api/pricing-svc/vat-rates/NOPE', { token: t }), '[-] unknown VAT code', 404, 'PRICING_VAT_CODE_NOT_FOUND');
  expect(call('GET', `/api/pricing-svc/vat-rates/${code}`, { token: rival.owner.token }), "[-] a rival does not see our VAT code", 404, 'PRICING_VAT_CODE_NOT_FOUND');
  expect(call('PUT', `/api/pricing-svc/vat-rates/${code}`, { token: t, body: { ...vat, name: 'Standard rate' } }), '[+] update VAT rate', 200);

  // ── price lists ─────────────────────────────────────────────────────────────
  const listBody = { name: `Retail ${uniq()}`, channel: 'ALL', currency: 'GBP', effectiveFrom: YESTERDAY() };
  expect(call('POST', '/api/pricing-svc/admin/price-lists', { token: t, body: { ...listBody, effectiveFrom: undefined } }), '[-] price list: effective-from required', 400, 'VALIDATION_FAILED');
  expect(call('POST', '/api/pricing-svc/price-lists', { token: t, body: listBody }), '[-] price lists are created under /admin', 405);
  const created = call('POST', '/api/pricing-svc/admin/price-lists', { token: t, body: listBody });
  expect(created, '[+] create price list', 201);
  const listId = data(created).id;
  expect(call('GET', '/api/pricing-svc/price-lists', { token: t }), '[+] list price lists', 200);
  expect(call('GET', `/api/pricing-svc/price-lists/${listId}`, { token: t }), '[+] get price list', 200);
  expect(call('GET', `/api/pricing-svc/price-lists/${listId}`, { token: rival.owner.token }), "[-] a rival cannot read our price list", 404, 'PRICING_LIST_NOT_FOUND');
  expect(call('POST', `/api/pricing-svc/admin/price-lists/${listId}/items`, { token: t, body: { variantId } }), '[-] price list item: price required', 400, 'VALIDATION_FAILED');
  expect(call('POST', `/api/pricing-svc/admin/price-lists/${listId}/items`, { token: t, body: { variantId, price: 0, minQty: 1 } }), '[-] price list item: price above zero', 400, 'VALIDATION_FAILED');
  expect(call('POST', `/api/pricing-svc/admin/price-lists/${listId}/items`, { token: t, body: { variantId, price: 49.99, minQty: 1 } }), '[+] price an item', [200, 201]);
  expect(
    call('POST', `/api/pricing-svc/admin/price-lists/${listId}/items/batch`, { token: t, body: { items: [{ variantId, price: 44.99, minQty: 10 }] } }),
    '[+] batch-price a quantity break',
    [200, 201]
  );
  expect(call('POST', `/api/pricing-svc/admin/price-lists/${UNKNOWN}/items`, { token: t, body: { variantId, price: 1, minQty: 1 } }), '[-] item on an unknown price list', 404, 'PRICING_LIST_NOT_FOUND');
  const items = call('GET', `/api/pricing-svc/price-lists/${listId}/items`, { token: t });
  expect(items, '[+] list price list items', 200);
  truthy('[+] both price breaks are listed', (data(items) || []).filter((i) => i.variantId === variantId).length === 2, data(items));

  // ── price resolution follows the list's lifecycle ──────────────────────────
  const resolve = (qty, opts = { token: t }) => call('POST', '/api/pricing-svc/prices/resolve', { ...opts, body: { variantId, storeId, channel: 'POS', qty } });
  // SJ-D56: the VAT rate above has its own code; an uncategorised kettle is charged the standard rate
  // (T1), and until the business sets one nothing is quoted — not a guessed 20%.
  expect(resolve(1), '[-] no standard VAT rate yet: no price is quoted', 409, 'PRICING_VAT_RATE_NOT_CONFIGURED');
  expect(resolve(1, { storefront: tenant.tenantId }), '[-] ...to a guest shopper either', 409, 'PRICING_VAT_RATE_NOT_CONFIGURED');
  ensureStandardVat(tenant);
  const one = resolve(1);
  expect(one, '[+] resolve a price', 200);
  truthy('[+] one kettle costs 49.99 GBP', Number(data(one).unitPrice) === 49.99 && data(one).currency === 'GBP', data(one));
  truthy('[+] ten kettles hit the quantity break', Number(data(resolve(10)).unitPrice) === 44.99, data(resolve(10)));
  expect(resolve(1, { storefront: tenant.tenantId }), '[+] a guest shopper resolves the same price', 200);
  expect(
    call('POST', '/api/pricing-svc/prices/resolve', { token: t, body: { variantId: tenant.unpricedId, storeId, channel: 'POS', qty: 1 } }),
    '[-] resolve an unpriced variant',
    404,
    'PRICING_PRICE_NOT_FOUND'
  );
  expect(resolve(1, { token: rival.owner.token }), "[-] a rival cannot resolve our variant's price", 404, 'PRICING_PRICE_NOT_FOUND');
  expect(call('POST', `/api/pricing-svc/admin/price-lists/${listId}/deactivate`, { token: t, body: {} }), '[-] deactivate: reason required', 400, 'PRICING_REASON_REQUIRED');
  expect(call('POST', `/api/pricing-svc/admin/price-lists/${listId}/deactivate`, { token: t, body: { reason: 'Season over' } }), '[+] deactivate the price list', 200);
  expect(resolve(1), '[-] no active list, no price', 404, 'PRICING_PRICE_NOT_FOUND');
  expect(call('POST', `/api/pricing-svc/admin/price-lists/${listId}/deactivate`, { token: t, body: { reason: 'Again' } }), '[-] deactivate an inactive list', 409, 'PRICING_ALREADY_IN_STATE');
  expect(call('POST', `/api/pricing-svc/admin/price-lists/${listId}/activate`, { token: t, body: { reason: 'Back on sale' } }), '[+] reactivate it', 200);
  expect(resolve(1), '[+] the price is back', 200);
  const history = call('GET', `/api/pricing-svc/admin/price-lists/${listId}/status-history`, { token: t });
  expect(history, '[+] price list status history', 200);
  truthy('[+] ...records both changes', (data(history) || []).length >= 2, data(history));

  // ── promotions and basket quotes ───────────────────────────────────────────
  const promo = { name: `Ten off ${uniq()}`, type: 'PERCENT', value: 10, channel: 'ALL', startsAt: YESTERDAY(), priority: 1 };
  expect(call('POST', '/api/pricing-svc/admin/promotions', { token: t, body: { ...promo, type: 'MAGIC' } }), '[-] promotion type must be known', 400, 'PRICING_INVALID_PROMOTION_TYPE');
  expect(call('POST', '/api/pricing-svc/admin/promotions', { token: t, body: { ...promo, value: 0 } }), '[-] promotion value above zero', 400, 'VALIDATION_FAILED');
  expect(call('POST', '/api/pricing-svc/admin/promotions', { token: shopper.token, body: promo }), '[-] a customer cannot create promotions', 403, 'FORBIDDEN');
  const made = call('POST', '/api/pricing-svc/admin/promotions', { token: t, body: promo });
  expect(made, '[+] create a 10% promotion', 201);
  const promoId = data(made).id;
  expect(call('POST', `/api/pricing-svc/admin/promotions/${promoId}/items`, { token: t, body: { scopeType: 'VARIANT', scopeId: variantId } }), '[+] scope it to the kettle', [200, 201]);
  expect(call('POST', `/api/pricing-svc/admin/promotions/${promoId}/items`, { token: t, body: { scopeId: variantId } }), '[-] promotion scope type required', 400, 'VALIDATION_FAILED');
  const quote = () => call('POST', '/api/pricing-svc/prices/quote', { token: t, body: { storeId, channel: 'POS', lines: [{ variantId, qty: 2 }] } });
  let q = null;
  poll(10, () => {
    q = data(quote());
    return Number(q.totalDiscount) > 0;
  });
  truthy('[+] quote: 10% off two kettles', Math.abs(Number(q.totalDiscount) - 10) < 0.01 && (q.appliedPromotions || []).some((p) => p.promotionId === promoId), q);
  expect(call('POST', '/api/pricing-svc/prices/quote', { token: t, body: { storeId, lines: [] } }), '[-] quote: needs lines', 400, 'VALIDATION_FAILED');
  const offers = call('GET', '/api/pricing-svc/promotions', { storefront: tenant.tenantId });
  expect(offers, '[+] storefront offers banner', 200);
  truthy('[+] ...shows the promotion', (data(offers) || []).some((p) => p.id === promoId), data(offers));
  truthy("[-] a rival's storefront does not", !(data(call('GET', '/api/pricing-svc/promotions', { storefront: rival.tenantId })) || []).some((p) => p.id === promoId));
  expect(call('POST', `/api/pricing-svc/admin/promotions/${promoId}/deactivate`, { token: t, body: { reason: 'Ended early' } }), '[+] end the promotion', 200);
  truthy('[+] quote: no discount once it has ended', Number(data(quote()).totalDiscount) === 0, data(quote()));
  expect(call('GET', `/api/pricing-svc/admin/promotions/${promoId}/status-history`, { token: t }), '[+] promotion status history', 200);

  // ── price overrides ─────────────────────────────────────────────────────────
  const override = { variantId, storeId, originalPrice: 49.99, overridePrice: 39.99, overrideReason: 'Damaged box', overriddenBy: tenant.owner.userId };
  expect(call('POST', '/api/pricing-svc/admin/price-overrides', { token: t, body: override }), '[+] record a price override', 201);
  expect(call('POST', '/api/pricing-svc/admin/price-overrides', { token: t, body: { ...override, variantId: undefined } }), '[-] override: variant required', 400, 'VALIDATION_FAILED');
  expect(call('POST', '/api/pricing-svc/admin/price-overrides', { token: t, body: { ...override, storeId: undefined } }), '[-] override: store required', 400, 'VALIDATION_FAILED');
  expect(call('POST', '/api/pricing-svc/admin/price-overrides', { token: t, body: { ...override, overridePrice: -1 } }), '[-] override: not negative', 400, 'VALIDATION_FAILED');
  expect(call('POST', '/api/pricing-svc/admin/price-overrides', { token: shopper.token, body: override }), '[-] a customer cannot override prices', 403, 'FORBIDDEN');
  expect(call('POST', '/api/pricing-svc/admin/price-overrides', { body: override }), '[-] no token', 401, 'UNAUTHORIZED');
  const byStore = call('GET', `/api/pricing-svc/admin/price-overrides?storeId=${storeId}`, { token: t });
  expect(byStore, '[+] overrides at the store', 200);
  truthy('[+] ...include ours', (data(byStore) || []).some((o) => o.variantId === variantId), data(byStore));
  expect(call('GET', `/api/pricing-svc/admin/price-overrides?variantId=${variantId}`, { token: t }), '[+] overrides for the variant', 200);
  truthy("[-] a rival's overrides do not include ours", !(data(call('GET', '/api/pricing-svc/admin/price-overrides', { token: rival.owner.token })) || []).some((o) => o.variantId === variantId));

  // ── VAT return ──────────────────────────────────────────────────────────────
  expect(call('GET', '/api/pricing-svc/vat-return?from=2026-01-01T00:00:00Z&to=2026-12-31T23:59:59Z', { token: t }), '[+] VAT return for the year', 200);
  expect(call('GET', '/api/pricing-svc/vat-return?from=2026-12-31T00:00:00Z&to=2026-01-01T00:00:00Z', { token: t }), '[-] VAT return: from after to', 400, 'PRICING_INVALID_PERIOD');
  expect(call('GET', '/api/pricing-svc/vat-return?from=2026-01-01&to=2026-12-31', { token: t }), '[-] VAT return: dates must be ISO instants', 400, 'INVALID_DATE');
  expect(call('GET', '/api/pricing-svc/vat-return?from=2026-01-01T00:00:00Z&to=2026-12-31T23:59:59Z', { token: shopper.token }), '[-] a customer cannot read the VAT return', 403, 'FORBIDDEN');
}
