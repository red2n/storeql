// Where things are and who may see them, through the gateway: the small follow-ups of 26 Sep 2026,
// each across businesses in different countries — Poland (two shops in Warsaw), Britain and India.
// "Only N left" on the storefront is off until a business sets its own threshold: a manager held
// to one shop, a storekeeper and a cashier cannot, nothing outside 1-1000 is taken, a count shows
// at or under the threshold and never above it, and one business's threshold reaches nothing of
// another's. Withdrawing the marketing purpose switches every marketing channel off on the spot, a
// channel cannot be switched back on — by the shopper or the shop — while it stands withdrawn, the
// staff's consent log shows why each went off, and granting the purpose again switches nothing on
// by itself. Staff are named by store: a manager held to the first shop names that shop's people
// and the business-wide owner, never the second shop's, and the staff list says the same. The
// shelf-gap report is opened to a storekeeper at their own shop and no further, every other report
// stays with management. Phone numbers are read in each business's own country — Polish, British
// and Indian — each finding its own customer by the national form, +E.164 and with spaces, never
// another business's. The password rules are public and one for everybody. The British business's
// owner, manager, storekeeper and cashier, naming our shops, customer and staff, read nothing and
// change nothing.
//
//   k6/run.sh location-and-access-flow
import {
  ALL_CHECKS_PASS,
  call,
  data,
  expect,
  must,
  onboardTenant,
  poll,
  receive,
  register,
  sellableVariant,
  staffUser,
  truthy,
  uniq,
} from './lib/storeql.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS, setupTimeout: '5m' };

const I = '/api/v1/inventory-svc';
const C = '/api/v1/customer-svc';
const IAM = '/api/v1/iam-svc';
const TS = '/api/v1/tenant-svc';
const MERCH = '/api/v1/product-svc/admin/merchandising';

const isoDay = (days) => new Date(Date.now() + days * 86400000).toISOString().slice(0, 10);

export function setup() {
  const pl = onboardTenant('where-pl', { country: 'PL', currency: 'PLN', stores: 2 });
  const [s1, s2] = pl.stores;
  const t = pl.owner.token;
  const staff = {
    managerS1: staffUser(pl, 'MANAGER', [s1.id]),
    keeperS1: staffUser(pl, 'STOREKEEPER', [s1.id]),
    keeperS2: staffUser(pl, 'STOREKEEPER', [s2.id]),
    cashierS1: staffUser(pl, 'CASHIER', [s1.id]),
  };
  const three = sellableVariant(pl, 'Pierogi').variantId;
  const five = sellableVariant(pl, 'Ogórki').variantId;
  const twenty = sellableVariant(pl, 'Kefir').variantId;
  must(receive(pl, s1.id, three, 3), [200, 201], 'three packs of pierogi');
  must(receive(pl, s1.id, five, 5), [200, 201], 'five jars of gherkins');
  must(receive(pl, s1.id, twenty, 20), [200, 201], 'twenty bottles of kefir');

  // A gondola at the first shop with forty pierogi of shelf (eight facings, five deep) and three to
  // fill it: the capacity crosses to inventory-svc on the layout's publication.
  const tag = uniq().toUpperCase().slice(0, 8);
  const fixture = must(call('POST', `${MERCH}/fixtures`, { token: t, body: { storeId: s1.id, code: `REG-${tag}`, name: 'Regał 1', kind: 'GONDOLA', shelfCount: 2, shelfWidthMm: 1000 } }), [200, 201], 'a gondola at the first shop');
  must(call('PUT', `${MERCH}/variants/${three}/facing-width`, { token: t, body: { facingWidthMm: 100 } }), 200, 'the pierogi measured');
  const layout = must(call('POST', `${MERCH}/fixtures/${fixture.id}/planograms`, { token: t, body: { effectiveFrom: isoDay(1) } }), [200, 201], 'a layout');
  must(call('PUT', `${MERCH}/planograms/${layout.id}/positions`, { token: t, body: { positions: [{ variantId: three, shelf: 1, sequence: 1, facings: 8, depth: 5, minPresentation: 10 }] } }), [200, 201], 'the pierogi on the shelf');
  must(call('POST', `${MERCH}/planograms/${layout.id}/publish`, { token: t, body: {} }), [200, 201], 'the layout published');

  const gb = onboardTenant('where-gb', { country: 'GB', currency: 'GBP' });
  const gbStore = gb.stores[0];
  const rivals = {
    OWNER: gb.owner,
    MANAGER: staffUser(gb, 'MANAGER', [gbStore.id]),
    STOREKEEPER: staffUser(gb, 'STOREKEEPER', [gbStore.id]),
    CASHIER: staffUser(gb, 'CASHIER', [gbStore.id]),
  };
  const scones = sellableVariant(gb, 'Scones').variantId;
  const crumpets = sellableVariant(gb, 'Crumpets').variantId;
  must(receive(gb, gbStore.id, scones, 3), [200, 201], 'three packs of scones');
  must(receive(gb, gbStore.id, crumpets, 20), [200, 201], 'twenty packs of crumpets');

  const india = onboardTenant('where-in', { country: 'IN', currency: 'INR' });
  const shopper = register('where-shopper');
  must(call('POST', `${C}/customers/me`, { token: shopper.token, storefront: pl.tenantId, body: {} }), 200, "the shopper's record at the Polish shop");
  return { pl, s1, s2, staff, three, five, twenty, gb, gbStore, rivals, scones, crumpets, india, shopper };
}

export default function ({ pl, s1, s2, staff, three, five, twenty, gb, gbStore, rivals, scones, crumpets, india, shopper }) {
  const owner = pl.owner.token;
  const roles = Object.keys(rivals);
  const list = (res) => (Array.isArray(data(res)) ? data(res) : []);

  // ── (a) "only N left": off until a business sets its own threshold ───────────────────────────────
  const SETTINGS = `${I}/admin/inventory/storefront-settings`;
  const threshold = (token) => call('GET', SETTINGS, { token });
  const setThreshold = (token, lowStockThreshold) => call('PUT', SETTINGS, { token, body: { lowStockThreshold } });
  const shelf = (tenant, storeId) => list(call('GET', `${I}/inventory/availability?store=${storeId}`, { storefront: tenant.tenantId }));
  const onlyLeft = (rows, variantId) => (rows.find((r) => r.variantId === variantId) || {}).onlyLeft;
  const unset = threshold(staff.cashierS1.token);
  expect(unset, '[+] any staff member reads the storefront threshold', 200);
  truthy('[+] ...off until the business sets one', data(unset).lowStockThreshold == null, data(unset));
  const offShelf = shelf(pl, s1.id);
  truthy('[+] with it off the storefront shows no count, however few are left', offShelf.some((r) => r.variantId === three && r.inStock === true) && [three, five, twenty].every((v) => onlyLeft(offShelf, v) == null), offShelf);
  expect(setThreshold(staff.managerS1.token, 5), '[-] a manager held to one shop cannot set a business-wide threshold', 403);
  expect(setThreshold(staff.keeperS1.token, 5), '[-] ...nor a storekeeper', 403);
  expect(setThreshold(staff.cashierS1.token, 5), '[-] ...nor a cashier', 403);
  expect(setThreshold(owner, 0), '[-] a threshold of nothing is refused', 400, 'INVENTORY_LOW_STOCK_THRESHOLD_INVALID');
  expect(setThreshold(owner, 1001), '[-] ...and one over a thousand', 400, 'INVENTORY_LOW_STOCK_THRESHOLD_INVALID');
  expect(setThreshold(gb.owner.token, 1000), '[+] the British owner sets a threshold of a thousand for the British shop', 200);
  truthy("[+] ...and the British storefront counts what it has: three scones, twenty crumpets", poll(60, () => {
    const rows = shelf(gb, gbStore.id);
    return onlyLeft(rows, scones) === 3 && onlyLeft(rows, crumpets) === 20;
  }) >= 0, shelf(gb, gbStore.id));
  const stillOff = shelf(pl, s1.id);
  truthy("[-] the British threshold reaches nothing Polish: still no count", [three, five, twenty].every((v) => onlyLeft(stillOff, v) == null), stillOff);
  const set = setThreshold(owner, 5);
  expect(set, '[+] the Polish owner sets five', 200);
  truthy('[+] ...recorded with who set it', data(set).lowStockThreshold === 5 && data(set).updatedBy === pl.owner.userId && Boolean(data(set).updatedAt), data(set));
  truthy('[+] three pierogi show "only 3 left", five gherkins "only 5", twenty kefir no count', poll(60, () => {
    const rows = shelf(pl, s1.id);
    return onlyLeft(rows, three) === 3 && onlyLeft(rows, five) === 5 && onlyLeft(rows, twenty) == null;
  }) >= 0, shelf(pl, s1.id));
  truthy('[-] the Polish threshold reaches nothing British: twenty crumpets still counted', onlyLeft(shelf(gb, gbStore.id), crumpets) === 20, shelf(gb, gbStore.id));
  truthy('[-] the British storefront, naming our shop, shows nothing of our stock', !shelf(gb, s1.id).some((r) => [three, five, twenty].includes(r.variantId)), shelf(gb, s1.id));
  const tried = ['MANAGER', 'STOREKEEPER', 'CASHIER'].map((role) => setThreshold(rivals[role].token, 1));
  truthy("[-] the British manager, storekeeper and cashier, each held to a shop, change no threshold", tried.every((r) => r.status === 403), tried.map((r) => r.status));
  truthy('[+] ...ours still five and theirs a thousand', data(threshold(staff.keeperS1.token)).lowStockThreshold === 5 && data(threshold(rivals.CASHIER.token)).lowStockThreshold === 1000);
  expect(setThreshold(owner, null), '[+] the Polish owner switches it off again', 200);
  truthy('[+] ...and the count goes', poll(60, () => onlyLeft(shelf(pl, s1.id), three) == null) >= 0, shelf(pl, s1.id));

  // ── (b) withdrawing the marketing purpose switches every channel off ─────────────────────────────
  const mine = (path, method = 'GET', body) => call(method, `${C}/customers/me${path}`, { token: shopper.token, storefront: pl.tenantId, body });
  const channels = (rows) => (Array.isArray(rows) ? rows : []).reduce((by, p) => Object.assign(by, { [p.channel]: p.granted }), {});
  expect(mine('/privacy/consents', 'PUT', { choices: [{ purpose: 'MARKETING', granted: true }] }), '[+] a shopper agrees to marketing', 200);
  const on = mine('/marketing', 'PUT', { channels: [{ channel: 'EMAIL', granted: true }, { channel: 'SMS', granted: true }], notice: 'Email and text me offers' });
  expect(on, '[+] ...and to email and texts', 200);
  truthy('[+] ...both on', channels(data(on)).EMAIL === true && channels(data(on)).SMS === true, data(on));
  const customerId = data(mine('')).id;
  const allowed = (channel) => data(call('GET', `${C}/customers/${customerId}/marketing/allowance?channel=${channel}`, { token: owner })).allowed;
  truthy('[+] the shop may email them', allowed('EMAIL') === true);
  expect(mine('/privacy/consents', 'PUT', { choices: [{ purpose: 'MARKETING', granted: false }] }), '[+] the shopper withdraws the marketing purpose', 200);
  truthy('[+] ...and both channels read off at once', channels(data(mine('/marketing'))).EMAIL === false && channels(data(mine('/marketing'))).SMS === false, data(mine('/marketing')));
  truthy('[+] ...for the shop too: nothing may be sent on either', channels(data(call('GET', `${C}/customers/${customerId}/marketing`, { token: owner }))).SMS === false && allowed('EMAIL') === false && allowed('SMS') === false);
  expect(mine('/marketing', 'PUT', { channels: [{ channel: 'EMAIL', granted: true }] }), '[-] a channel cannot be switched on while the purpose stands withdrawn', 409, 'MARKETING_PURPOSE_NOT_GRANTED');
  expect(call('PUT', `${C}/customers/${customerId}/marketing`, { token: owner, body: { channels: [{ channel: 'SMS', granted: true }] } }), '[-] ...not by the shop either', 409, 'MARKETING_PURPOSE_NOT_GRANTED');
  const exported = data(call('GET', `${C}/customers/${customerId}/export`, { token: owner }));
  const withdrawn = (exported.marketingConsentLog || []).filter((e) => e.source === 'PURPOSE_WITHDRAWN');
  truthy("[+] the shop's consent log shows each channel switched off because the purpose was withdrawn", withdrawn.length === 2 && withdrawn.every((e) => e.granted === false) && ['EMAIL', 'SMS'].every((c) => withdrawn.some((e) => e.channel === c)), exported.marketingConsentLog);
  const logged = (exported.marketingConsentLog || []).length;
  for (const role of roles) {
    const who = rivals[role].token;
    const name = role.toLowerCase();
    expect(call('GET', `${C}/customers/${customerId}/marketing`, { token: who }), `[-] the British ${name}, naming our customer, reads nothing`, 404, 'CUSTOMER_NOT_FOUND');
    expect(call('PUT', `${C}/customers/${customerId}/marketing`, { token: who, body: { channels: [{ channel: 'EMAIL', granted: true }] } }), `[-] ...and the British ${name} switches nothing on`, 404, 'CUSTOMER_NOT_FOUND');
  }
  expect(call('GET', `${C}/customers/${customerId}/export`, { token: rivals.OWNER.token }), '[-] the British owner exports nothing of ours', 404);
  const untouched = data(call('GET', `${C}/customers/${customerId}/export`, { token: owner }));
  truthy('[+] nothing of ours moved: both channels off, the log as it was', (untouched.marketingConsentLog || []).length === logged && channels(untouched.marketingPreferences).EMAIL === false && channels(untouched.marketingPreferences).SMS === false, untouched.marketingPreferences);
  expect(mine('/privacy/consents', 'PUT', { choices: [{ purpose: 'MARKETING', granted: true }] }), '[+] the shopper agrees to marketing again', 200);
  truthy('[+] ...which switches no channel on by itself', channels(data(mine('/marketing'))).EMAIL === false && channels(data(mine('/marketing'))).SMS === false, data(mine('/marketing')));
  const back = mine('/marketing', 'PUT', { channels: [{ channel: 'EMAIL', granted: true }] });
  expect(back, '[+] ...and email is theirs to switch on again', 200);
  truthy('[+] ...email on, texts still off', channels(data(back)).EMAIL === true && channels(data(back)).SMS === false, data(back));

  // ── (c) staff named by store ─────────────────────────────────────────────────────────────────────
  const everyone = [pl.owner.userId, staff.managerS1.userId, staff.keeperS1.userId, staff.keeperS2.userId, staff.cashierS1.userId];
  const named = (token) => call('GET', `${IAM}/auth/admin/staff-users?ids=${everyone.join(',')}`, { token });
  const namedIds = (res) => list(res).map((s) => s.userId);
  const byManager = named(staff.managerS1.token);
  expect(byManager, '[+] a manager held to the first shop names staff by id', 200);
  truthy("[+] ...the first shop's storekeeper and cashier, themself, and the owner who works across the business", [pl.owner.userId, staff.managerS1.userId, staff.keeperS1.userId, staff.cashierS1.userId].every((id) => namedIds(byManager).includes(id)), list(byManager));
  truthy("[-] ...never the second shop's storekeeper", !namedIds(byManager).includes(staff.keeperS2.userId), list(byManager));
  truthy('[+] the owner names all five', everyone.every((id) => namedIds(named(owner)).includes(id)), list(named(owner)));
  for (const role of ['OWNER', 'MANAGER']) {
    const theirs = named(rivals[role].token);
    truthy(`[-] the British ${role.toLowerCase()}, naming our staff's ids, names none of them`, theirs.status === 200 && namedIds(theirs).length === 0, list(theirs));
  }
  expect(named(rivals.STOREKEEPER.token), '[-] the British storekeeper names nobody: it is a manager\'s read', 403);
  expect(named(rivals.CASHIER.token), '[-] ...nor its cashier names anybody', 403);
  expect(named(staff.keeperS1.token), '[-] ...nor our own storekeeper', 403);
  const assignments = (token) => list(call('GET', `${TS}/admin/staff?limit=100`, { token }));
  const managerView = assignments(staff.managerS1.token);
  truthy("[+] the manager's staff list: the first shop's assignments and the business-wide ones only", managerView.length > 0 && managerView.every((a) => a.storeId === s1.id || a.storeId == null) && managerView.some((a) => a.userId === staff.keeperS1.userId), managerView);
  truthy("[-] ...not the second shop's storekeeper", !managerView.some((a) => a.userId === staff.keeperS2.userId), managerView);
  const ownerView = assignments(owner);
  truthy("[+] the owner's list has both shops' people", ownerView.some((a) => a.userId === staff.keeperS2.userId && a.storeId === s2.id) && ownerView.some((a) => a.userId === staff.keeperS1.userId && a.storeId === s1.id), ownerView);
  truthy("[-] the British owner's list has none of ours", !assignments(rivals.OWNER.token).some((a) => everyone.includes(a.userId)), assignments(rivals.OWNER.token));

  // ── (d) shelf gaps for the storekeeper, at their own shop ────────────────────────────────────────
  const gaps = (token, storeId) => call('GET', `${I}/admin/inventory/reports/shelf-gaps?storeId=${storeId}&limit=100`, { token });
  let row = null;
  const reached = poll(90, () => {
    row = list(gaps(staff.keeperS1.token, s1.id)).find((r) => r.variantId === three) || null;
    return row !== null && row.capacity === 40;
  });
  truthy(`[+] the first shop's storekeeper reads its shelf gaps (${reached}s)`, reached >= 0, row);
  truthy('[+] ...forty pierogi of shelf, three to fill it: thirty-seven to go', Boolean(row) && row.capacity === 40 && Number(row.gap) === 37, row);
  expect(gaps(staff.keeperS1.token, s2.id), "[-] ...not the second shop's", 403, 'STORE_ACCESS_DENIED');
  expect(gaps(staff.cashierS1.token, s1.id), '[-] a cashier reads no shelf gaps', 403);
  expect(gaps(staff.managerS1.token, s1.id), "[+] the first shop's manager does", 200);
  const rivalOwnerGaps = gaps(rivals.OWNER.token, s1.id);
  truthy('[-] the British owner, naming our shop, reads an empty report', rivalOwnerGaps.status === 200 && list(rivalOwnerGaps).length === 0, String(rivalOwnerGaps.body).slice(0, 200));
  expect(gaps(rivals.MANAGER.token, s1.id), '[-] the British manager, held to its own shop, reads none of ours', 403, 'STORE_ACCESS_DENIED');
  expect(gaps(rivals.STOREKEEPER.token, s1.id), '[-] ...nor its storekeeper', 403, 'STORE_ACCESS_DENIED');
  expect(gaps(rivals.CASHIER.token, s1.id), '[-] ...nor its cashier reads our shelf', 403);
  expect(call('GET', `${I}/admin/inventory/reports/low-stock?storeId=${s1.id}`, { token: staff.keeperS1.token }), '[-] every other report stays with management: low stock', 403);
  expect(call('GET', `${I}/admin/inventory/reports/valuation?storeId=${s1.id}`, { token: staff.keeperS1.token }), '[-] ...and the valuation', 403);

  // ── (e) phone numbers, read in each business's own country ───────────────────────────────────────
  const books = [
    { biz: pl, country: 'Polish', typed: '512 345 678', forms: ['512345678', '512 345 678', '+48512345678', '+48 512 345 678'], e164: '+48512345678' },
    // Not Ofcom's drama range (07700 900xxx): libphonenumber holds those numbers invalid on purpose,
    // so a business could never read one as a British mobile. 07400 123456 is its own example.
    { biz: gb, country: 'British', typed: '07400 123456', forms: ['07400123456', '07400 123456', '+447400123456', '+44 7400 123456'], e164: '+447400123456' },
    { biz: india, country: 'Indian', typed: '98765 43210', forms: ['9876543210', '98765 43210', '+919876543210', '+91 98765 43210'], e164: '+919876543210' },
  ];
  for (const book of books) {
    const made = call('POST', `${C}/customers`, { token: book.biz.owner.token, body: { email: `phone-${uniq()}@k6.storeql.test`, phone: book.typed, firstName: 'Phone', lastName: book.country } });
    expect(made, `[+] the ${book.country} business records a customer's phone as ${book.typed}`, 201);
    book.customerId = data(made).id;
    truthy(`[+] ...kept as typed, and as ${book.e164} where the answer says`, data(made).phone === book.typed && (data(made).phoneE164 == null || data(made).phoneE164 === book.e164), data(made));
  }
  const search = (token, q) => {
    const d = data(call('GET', `${C}/customers?q=${encodeURIComponent(q)}&limit=100`, { token }));
    return (d.items || []).map((c) => c.id);
  };
  const lookup = (token, phone) => call('GET', `${C}/customers/lookup?phone=${encodeURIComponent(phone)}`, { token });
  for (const book of books) {
    const token = book.biz.owner.token;
    const missed = book.forms.filter((q) => !search(token, q).includes(book.customerId));
    truthy(`[+] the ${book.country} business finds its customer by ${book.forms.join(', ')}`, missed.length === 0, { missed });
    const unseen = book.forms.filter((q) => data(lookup(token, q)).id !== book.customerId);
    truthy(`[+] ...and the ${book.country} business looks them up by any of those forms`, unseen.length === 0, { unseen });
    for (const other of books.filter((b) => b !== book)) {
      truthy(`[-] the ${book.country} business finds no ${other.country} customer, even by ${other.e164}`, !search(token, other.e164).includes(other.customerId) && !search(token, other.typed).includes(other.customerId), other.e164);
      expect(lookup(token, other.e164), `[-] ...and the ${book.country} business looks up no ${other.country} customer by ${other.e164}`, 404, 'CUSTOMER_NOT_FOUND');
    }
  }
  truthy("[-] the British cashier finds no Polish customer by the Polish number", search(rivals.CASHIER.token, '+48 512 345 678').length === 0);

  // ── (f) the password rules: public, and one for everybody ────────────────────────────────────────
  const POLICY = `${IAM}/auth/password-policy`;
  const published = call('GET', POLICY);
  expect(published, '[+] the password rules are read with no sign-in', 200);
  const rules = data(published);
  truthy('[+] ...fifteen characters or more, at most 128, never holding the login', rules.minLength === 15 && rules.maxLength === 128 && rules.mustNotContainLogin === true && typeof rules.breachScreened === 'boolean', rules);
  const same = [pl.owner.token, rivals.OWNER.token, india.owner.token, staff.cashierS1.token, shopper.token].every((token) => JSON.stringify(data(call('GET', POLICY, { token }))) === JSON.stringify(rules));
  truthy('[+] ...the same for every business, every role and a shopper', same && JSON.stringify(data(call('GET', POLICY, { storefront: pl.tenantId }))) === JSON.stringify(rules), rules);
}
