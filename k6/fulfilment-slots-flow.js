// Delivery and collection slots through the gateway: a Polish business trading in Warsaw and an
// Indian one in Kolkata, every window set and shown in its own shop's time. The Polish owner and a
// manager held to the first shop set its collection and delivery windows for each day of the week
// (a window overlapping another of its kind, ending before it starts, taking nobody, on no weekday,
// with a negative cut-off or of no kind refused; a delivery window may share a collection
// window's hours; the manager refused the second shop; a storekeeper and a cashier refused
// outright). A guest reads the next seven days in Warsaw's time with what each window has left,
// none within its cut-off, a window that closes a day ahead left out; shoppers fill a two-place
// window (the same checkout sent twice takes one place), the third is refused FULL and takes the
// midday window, a cancellation gives a place back and the manager raises a window by one. A
// checkout without a window where one is offered, with half of one, a moment that is no occurrence,
// a delivery window for a collection, one past its cut-off, one at a shop that offers none and a
// till sale with one are refused by name; a delivery to the first shop's postcode, named at the
// second, holds the first shop's evening window. The order, the shopper's history and the store's
// list — in window order, the same page by page — carry the window in the shop's own time; the
// second shop, offering none, checks out as before. The Indian business shows its windows in
// Kolkata's time, different moments from Warsaw's at the same clock time; its owner, manager,
// storekeeper and cashier, naming the Polish shop and its windows, read none, set none and take no
// place, its storefront lists none, and nothing of the Polish business moves.
//
//   k6/run.sh fulfilment-slots-flow
import {
  ALL_CHECKS_PASS,
  call,
  data,
  errorCode,
  expect,
  must,
  newKey,
  nextCursor,
  onboardTenant,
  priceVariants,
  receive,
  register,
  sellableVariant,
  staffUser,
  truthy,
  uniq,
} from './lib/storeql.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS, setupTimeout: '5m' };

const O = '/api/v1/order-svc';
const TS = '/api/v1/tenant-svc';
const WAW = 'Europe/Warsaw';
const KOL = 'Asia/Kolkata';

// ── clocks ────────────────────────────────────────────────────────────────────────────────────────
// k6 carries no time-zone database, so the two rules this suite needs are written out: the EU's
// summer time (01:00 UTC on the last Sunday of March to the last Sunday of October) and India's one
// offset. The server works from java.time's rules; these only check its answers.

function lastSunday(year, month) {
  const end = new Date(Date.UTC(year, month + 1, 0, 1));
  return end.getTime() - end.getUTCDay() * 86400000;
}

/** Minutes the zone's clocks are ahead of UTC at an instant. */
function offset(zone, at) {
  if (zone === KOL) return 330;
  const year = new Date(at).getUTCFullYear();
  return at >= lastSunday(year, 2) && at < lastSunday(year, 9) ? 120 : 60;
}

const wall = (zone, at) => new Date(at + offset(zone, at) * 60000).toISOString();
const localDate = (zone, at) => wall(zone, at).slice(0, 10);
const localTime = (zone, at) => wall(zone, at).slice(11, 16);
const addDays = (date, n) => new Date(Date.parse(`${date}T12:00:00Z`) + n * 86400000).toISOString().slice(0, 10);
const isoWeekday = (date) => new Date(Date.parse(`${date}T12:00:00Z`)).getUTCDay() || 7;

/** The instant a date's clock time is in a zone (none of the times used here falls in a change). */
function instantOf(zone, date, time) {
  const asUtc = Date.parse(`${date}T${time}:00Z`);
  return asUtc - offset(zone, asUtc - offset(zone, asUtc) * 60000) * 60000;
}

/** Every slot of a listing, each with its day's date. */
const slotsOf = (listing) => (listing.days || []).reduce((all, day) => all.concat((day.slots || []).map((s) => Object.assign({ date: day.date }, s))), []);

/** Each slot's clock times are its instants in the zone, on its day. */
const inZone = (zone, slots) => slots.every((s) => localDate(zone, Date.parse(s.startsAt)) === s.date && localTime(zone, Date.parse(s.startsAt)) === s.startTime && localTime(zone, Date.parse(s.endsAt)) === s.endTime);

export function setup() {
  const pl = onboardTenant('slots-pl', { country: 'PL', currency: 'PLN', stores: 2 });
  const [s1, s2] = pl.stores;
  const pincode = `WA${uniq()}`.slice(0, 12);
  must(call('POST', `${TS}/admin/stores/${s1.id}/delivery-areas`, { token: pl.owner.token, body: { pincode, priority: 1 } }), 201, 'the first shop delivers to the postcode');
  const bread = sellableVariant(pl, 'Chleb').variantId;
  priceVariants(pl, [bread], '6.50');
  must(receive(pl, s1.id, bread, 100), [200, 201], 'bread at the first shop');
  must(receive(pl, s2.id, bread, 100), [200, 201], 'bread at the second shop');
  const manager = staffUser(pl, 'MANAGER', [s1.id]);
  const keeper = staffUser(pl, 'STOREKEEPER', [s1.id]);
  const cashier = staffUser(pl, 'CASHIER', [s1.id]);

  const india = onboardTenant('slots-in', { country: 'IN', currency: 'INR' });
  const kStore = india.stores[0];
  const chai = sellableVariant(india, 'Chai').variantId;
  priceVariants(india, [chai], '120.00');
  must(receive(india, kStore.id, chai, 100), [200, 201], 'chai at the Indian shop');
  const rivals = {
    OWNER: india.owner,
    MANAGER: staffUser(india, 'MANAGER', [kStore.id]),
    STOREKEEPER: staffUser(india, 'STOREKEEPER', [kStore.id]),
    CASHIER: staffUser(india, 'CASHIER', [kStore.id]),
  };
  return {
    pl, s1, s2, pincode, bread, manager, keeper, cashier,
    india, kStore, chai, rivals,
    shoppers: [register('slots-ania'), register('slots-bartek'), register('slots-celina')],
    kShopper: register('slots-kiran'),
  };
}

export default function ({ pl, s1, s2, pincode, bread, manager, keeper, cashier, india, kStore, chai, rivals, shoppers, kShopper }) {
  const owner = pl.owner.token;
  const [ania, bartek, celina] = shoppers;
  const windowsAt = (storeId, token = owner) => call('GET', `${O}/admin/fulfilment-windows?storeId=${storeId}`, { token });
  const addWindow = (body, token = owner) => call('POST', `${O}/admin/fulfilment-windows`, { token, body });
  const editWindow = (id, body, token = owner) => call('PUT', `${O}/admin/fulfilment-windows/${id}`, { token, body });
  const week = (storeId, fulfilmentType, startTime, endTime, capacity, cutoffMinutes, token = owner) =>
    [1, 2, 3, 4, 5, 6, 7].map((weekday) => addWindow({ storeId, fulfilmentType, weekday, startTime, endTime, capacity, cutoffMinutes }, token));
  const listing = (tenant, storeId, type) => call('GET', `${O}/storefront/fulfilment-slots?store=${storeId}&type=${type}`, { storefront: tenant.tenantId });
  const slotAt = (tenant, storeId, type, windowId) => slotsOf(data(listing(tenant, storeId, type))).find((s) => s.windowId === windowId);
  const withSlot = (body, slot) => (slot ? Object.assign(body, { slotWindowId: slot.windowId, slotStartsAt: slot.startsAt }) : body);
  const pickup = (storeId, slot, variantId = bread, contactPhone = '+48 600 100 200') => withSlot({ storeId, channel: 'ONLINE', fulfilmentType: 'PICKUP', items: [{ variantId, qty: 1 }], contactPhone }, slot);
  const delivery = (storeId, slot) =>
    withSlot({ storeId, channel: 'ONLINE', fulfilmentType: 'DELIVERY', items: [{ variantId: bread, qty: 1 }], contactPhone: '+48 600 100 200', deliveryLine1: 'ul. Marszałkowska 1', deliveryCity: 'Warszawa', deliveryPostalCode: pincode, deliveryRecipientName: 'Anna Kowalska', deliveryRecipientPhone: '+48 600 100 200' }, slot);
  const place = (who, tenant, body, key) => call('POST', `${O}/orders`, { token: who.token, storefront: tenant.tenantId, idem: key || true, body });
  const tillSale = (extra) => call('POST', `${O}/orders`, { token: cashier.token, idem: true, body: Object.assign({ storeId: s1.id, channel: 'POS', fulfilmentType: 'INSTORE', paymentMethod: 'CASH', items: [{ variantId: bread, qty: 1 }] }, extra || {}) });
  const win = (weekday, startTime, endTime, capacity = 2, cutoffMinutes = 60, fulfilmentType = 'PICKUP', storeId = s1.id) => ({ storeId, fulfilmentType, weekday, startTime, endTime, capacity, cutoffMinutes });
  const created = (responses) => responses.every((r) => r.status === 201);

  // ── 1. the Polish shop's windows ─────────────────────────────────────────────────────────────────
  const morning = week(s1.id, 'PICKUP', '09:00', '11:00', 2, 60);
  truthy('[+] the owner sets a two-place collection window, 09:00-11:00, for every day of the week', created(morning), morning.map((r) => `${r.status} ${errorCode(r) || ''}`));
  const monday = data(morning[0]);
  truthy("[+] ...Monday's, in the shop's own zone, set by the owner", monday.storeId === s1.id && monday.fulfilmentType === 'PICKUP' && monday.weekday === 1 && monday.startTime === '09:00' && monday.endTime === '11:00' && monday.capacity === 2 && monday.cutoffMinutes === 60 && monday.active === true && monday.timeZone === WAW && monday.updatedBy === pl.owner.userId, monday);
  const middays = week(s1.id, 'PICKUP', '12:00', '14:00', 3, 60, manager.token);
  truthy('[+] a manager held to the first shop sets its midday windows', created(middays) && data(middays[0]).updatedBy === manager.userId, middays.map((r) => r.status));
  const evenings = week(s1.id, 'DELIVERY', '17:00', '19:00', 3, 120);
  truthy('[+] ...and the owner its evening deliveries', created(evenings), evenings.map((r) => r.status));
  expect(addWindow(win(1, '10:00', '12:00')), '[-] a window overlapping another of its kind that day is refused', 400, 'ORDER_SLOT_WINDOW_INVALID');
  expect(addWindow(win(1, '19:00', '17:00')), '[-] ...one that ends before it starts', 400, 'ORDER_SLOT_WINDOW_INVALID');
  expect(addWindow(win(1, '15:00', '16:00', 0)), '[-] ...one that takes nobody', 400, 'ORDER_SLOT_WINDOW_INVALID');
  expect(addWindow(win(8, '15:00', '16:00')), '[-] ...one on no day of the week', 400, 'ORDER_SLOT_WINDOW_INVALID');
  expect(addWindow(win(1, '15:00', '16:00', 2, -1)), '[-] ...one with a negative cut-off', 400, 'ORDER_SLOT_WINDOW_INVALID');
  expect(addWindow(win(1, '15:00', '16:00', 2, 60, 'COURIER')), '[-] ...and one of no kind the platform has', 400, 'ORDER_SLOT_WINDOW_INVALID');
  const shared = addWindow(win(1, '09:00', '10:00', 1, 60, 'DELIVERY'));
  expect(shared, "[+] a delivery window may share a collection window's hours", 201);
  const off = editWindow(data(shared).id, { weekday: 1, startTime: '09:00', endTime: '10:00', capacity: 1, cutoffMinutes: 60, active: false }, manager.token);
  expect(off, '[+] the manager switches it off again', 200);
  truthy('[+] ...off, its shop and kind unchanged', data(off).active === false && data(off).storeId === s1.id && data(off).fulfilmentType === 'DELIVERY', data(off));
  expect(addWindow(win(2, '15:00', '16:00', 2, 60, 'PICKUP', s2.id), manager.token), "[-] the manager cannot set the second shop's windows", 403, 'STORE_ACCESS_DENIED');
  expect(windowsAt(s2.id, manager.token), '[-] ...nor read them', 403, 'STORE_ACCESS_DENIED');
  expect(windowsAt(s1.id, keeper.token), '[-] a storekeeper reads no windows', 403);
  expect(addWindow(win(2, '15:00', '16:00'), cashier.token), '[-] a cashier sets none', 403);
  expect(call('GET', `${O}/admin/fulfilment-windows`, { token: owner }), '[-] windows are read a shop at a time', 400, 'ORDER_SLOT_STORE_REQUIRED');
  const set = data(windowsAt(s1.id));
  truthy("[+] the owner reads the first shop's twenty-two windows", Array.isArray(set) && set.length === 22 && set.every((w) => w.storeId === s1.id), Array.isArray(set) ? set.length : set);

  // ── 2. the storefront: seven days in Warsaw's time ───────────────────────────────────────────────
  const today = localDate(WAW, Date.now());
  const read = listing(pl, s1.id, 'PICKUP');
  const todayAfter = localDate(WAW, Date.now());
  expect(read, "[+] a guest reads the first shop's collection slots", 200);
  const week1 = data(read);
  const days = week1.days || [];
  truthy('[+] ...seven days from today in Warsaw', week1.storeId === s1.id && week1.fulfilmentType === 'PICKUP' && week1.timeZone === WAW && week1.offered === true && days.length === 7 && (days[0].date === today || days[0].date === todayAfter) && days.every((d, i) => d.date === addDays(days[0].date, i)), { timeZone: week1.timeZone, offered: week1.offered, dates: days.map((d) => d.date) });
  const all = slotsOf(week1);
  truthy("[+] ...each slot's clock time its instant in Warsaw, on its day", all.length > 0 && inZone(WAW, all), all.slice(0, 3));
  truthy('[+] ...none within its cut-off', all.every((s) => Date.parse(s.startsAt) >= Date.now() + 58 * 60000), all.filter((s) => Date.parse(s.startsAt) < Date.now() + 58 * 60000));
  const tomorrow = days[1] || { date: addDays(today, 1), slots: [] };
  const weekday = isoWeekday(tomorrow.date);
  const first = (tomorrow.slots || []).find((s) => s.startTime === '09:00');
  const midday = (tomorrow.slots || []).find((s) => s.startTime === '12:00');
  truthy("[+] tomorrow: the 09:00 window with two places and the midday one with three", Boolean(first && midday) && first.left === 2 && first.full === false && first.endTime === '11:00' && midday.left === 3 && midday.endTime === '14:00', tomorrow);
  if (!first || !midday) return;
  truthy("[+] ...each the window set for tomorrow's weekday", first.windowId === data(morning[weekday - 1]).id && midday.windowId === data(middays[weekday - 1]).id, { weekday, first: first.windowId, midday: midday.windowId });
  const late = addWindow({ storeId: s1.id, fulfilmentType: 'PICKUP', weekday, startTime: '00:00', endTime: '00:30', capacity: 5, cutoffMinutes: 1440 });
  expect(late, "[+] a collection window at midnight that closes a day ahead", 201);
  const closed = { windowId: data(late).id, startsAt: new Date(instantOf(WAW, tomorrow.date, '00:00')).toISOString().replace('.000Z', 'Z') };
  truthy('[+] ...is left out of the listing: its cut-off has passed', !slotsOf(data(listing(pl, s1.id, 'PICKUP'))).some((s) => s.windowId === closed.windowId));
  const deliveries = data(listing(pl, s1.id, 'DELIVERY'));
  const evening = ((((deliveries.days || [])[1] || {}).slots) || []).find((s) => s.startTime === '17:00');
  truthy("[+] deliveries are listed on their own: tomorrow 17:00-19:00, three places, the switched-off window nowhere", deliveries.offered === true && deliveries.fulfilmentType === 'DELIVERY' && Boolean(evening) && evening.endTime === '19:00' && evening.left === 3 && !slotsOf(deliveries).some((s) => s.windowId === data(shared).id), deliveries);
  const nothing = data(listing(pl, s2.id, 'PICKUP'));
  truthy('[+] the second shop offers no windows: seven empty days', nothing.offered === false && (nothing.days || []).length === 7 && nothing.days.every((d) => (d.slots || []).length === 0), nothing);
  expect(call('GET', `${O}/storefront/fulfilment-slots?type=PICKUP`, { storefront: pl.tenantId }), '[-] the listing names a shop', 400, 'ORDER_SLOT_STORE_REQUIRED');
  expect(listing(pl, s1.id, 'COURIER'), '[-] ...and a kind the platform has', 400, 'ORDER_SLOT_TYPE_INVALID');
  if (!evening) return;

  // ── 3. filling a window ──────────────────────────────────────────────────────────────────────────
  const left = (slot) => (slotAt(pl, s1.id, 'PICKUP', slot.windowId) || {}).left;
  const key = newKey();
  const o1 = must(place(ania, pl, pickup(s1.id, first), key), 201, 'a collection at 09:00 tomorrow');
  truthy("[+] the order holds the window, told in the shop's own time", Boolean(o1.slot) && Date.parse(o1.slot.startsAt) === Date.parse(first.startsAt) && Date.parse(o1.slot.endsAt) === Date.parse(first.endsAt) && o1.slot.timeZone === WAW && o1.slot.date === tomorrow.date && o1.slot.startTime === '09:00' && o1.slot.endTime === '11:00', o1.slot);
  truthy('[+] ...and takes one of its two places', left(first) === 1, left(first));
  const replay = must(place(ania, pl, pickup(s1.id, first), key), 201, 'the same checkout sent again');
  truthy('[+] the same checkout sent again is the same order and takes no second place', replay.id === o1.id && left(first) === 1, { first: o1.id, again: replay.id, left: left(first) });
  const o2 = must(place(bartek, pl, pickup(s1.id, first)), 201, 'the last place at 09:00');
  const full = slotAt(pl, s1.id, 'PICKUP', first.windowId) || {};
  truthy('[+] the second shopper takes the last place: the window reads full', full.left === 0 && full.full === true, full);
  expect(place(celina, pl, pickup(s1.id, first)), '[-] a third is refused: the window is full', 409, 'ORDER_SLOT_FULL');
  const o3 = must(place(celina, pl, pickup(s1.id, midday)), 201, 'the midday window instead');
  truthy('[+] ...and takes the midday window instead', Boolean(o3.slot) && o3.slot.startTime === '12:00' && left(midday) === 2, o3.slot);
  must(call('POST', `${O}/orders/${o1.id}/cancel`, { token: owner, body: { reason: 'Plans changed' } }), 200, 'the first collection cancelled');
  const freed = slotAt(pl, s1.id, 'PICKUP', first.windowId) || {};
  truthy('[+] a cancelled order gives its place back', freed.left === 1 && freed.full === false, freed);
  const o4 = must(place(celina, pl, pickup(s1.id, first)), 201, 'the freed place');
  truthy('[+] ...which the refused shopper now takes', Boolean(o4.slot) && o4.slot.startTime === '09:00' && left(first) === 0, o4.slot);
  const raised = editWindow(midday.windowId, { weekday, startTime: '12:00', endTime: '14:00', capacity: 4, cutoffMinutes: 60, active: true }, manager.token);
  expect(raised, "[+] the manager raises tomorrow's midday window to four", 200);
  truthy('[+] ...and the storefront shows the extra place', data(raised).capacity === 4 && left(midday) === 3, { window: data(raised), left: left(midday) });

  // ── 4. refused at checkout ───────────────────────────────────────────────────────────────────────
  expect(place(ania, pl, pickup(s1.id)), '[-] a collection where the shop offers windows needs one', 400, 'ORDER_SLOT_REQUIRED');
  expect(place(ania, pl, delivery(s1.id)), '[-] ...and so does a delivery', 400, 'ORDER_SLOT_REQUIRED');
  expect(place(ania, pl, Object.assign(pickup(s1.id), { slotWindowId: midday.windowId })), '[-] half a window is no window', 400, 'ORDER_SLOT_UNKNOWN');
  expect(place(ania, pl, pickup(s1.id, { windowId: midday.windowId, startsAt: new Date(Date.parse(midday.startsAt) + 60000).toISOString() })), '[-] a moment that is no occurrence of the window', 400, 'ORDER_SLOT_UNKNOWN');
  expect(place(ania, pl, pickup(s1.id, evening)), '[-] a delivery window for a collection', 400, 'ORDER_SLOT_UNKNOWN');
  expect(place(ania, pl, pickup(s1.id, closed)), '[-] a window past its cut-off', 409, 'ORDER_SLOT_CLOSED');
  expect(place(ania, pl, pickup(s2.id, midday)), "[-] the first shop's window at the second, which offers none", 400, 'ORDER_SLOT_UNKNOWN');
  expect(tillSale({ slotWindowId: midday.windowId, slotStartsAt: midday.startsAt }), '[-] a till sale has no window', 400, 'ORDER_SLOT_NOT_APPLICABLE');
  truthy('[+] ...and none of those took a place', left(midday) === 3 && left(first) === 0, { midday: left(midday), first: left(first) });

  // ── 5. a delivery, and a shop with no windows ────────────────────────────────────────────────────
  const delivered = must(place(bartek, pl, delivery(s2.id, evening)), 201, 'a delivery in the evening window');
  truthy("[+] the postcode puts the delivery at the first shop, in its evening window", delivered.storeId === s1.id && Boolean(delivered.slot) && delivered.slot.startTime === '17:00' && delivered.slot.endTime === '19:00' && delivered.slot.timeZone === WAW && delivered.slot.date === tomorrow.date, { storeId: delivered.storeId, slot: delivered.slot });
  const dayAfter = (((days[2] || {}).slots) || []).find((s) => s.startTime === '12:00');
  const later = must(place(bartek, pl, pickup(s1.id, dayAfter)), 201, 'a collection the day after tomorrow');
  const plain = must(place(ania, pl, pickup(s2.id)), 201, 'a collection at the second shop');
  truthy('[+] the second shop, offering no windows, checks out as before: no window on the order', plain.slot == null, plain.slot);
  const till = must(tillSale(), 201, 'a till sale');
  truthy('[+] a till sale at the first shop goes through with no window', till.slot == null, till.slot);

  // ── 6. the window wherever the order is read ─────────────────────────────────────────────────────
  const staffView = data(call('GET', `${O}/orders/${o4.id}`, { token: owner }));
  truthy('[+] staff read the window on the order', Boolean(staffView.slot) && staffView.slot.startTime === '09:00' && staffView.slot.date === tomorrow.date && staffView.slot.timeZone === WAW, staffView.slot);
  const history = (who) => {
    const d = data(call('GET', `${O}/orders/mine`, { token: who.token, storefront: pl.tenantId }));
    return Array.isArray(d) ? d : [];
  };
  const mine = history(celina).find((o) => o.id === o4.id) || {};
  truthy("[+] the shopper's history carries it in the shop's time", Boolean(mine.slot) && mine.slot.date === tomorrow.date && mine.slot.startTime === '09:00' && mine.slot.endTime === '11:00' && mine.slot.timeZone === WAW, mine);
  const gone = history(ania).find((o) => o.id === o1.id) || {};
  truthy('[+] ...the cancelled one too, cancelled', gone.status === 'CANCELLED' && Boolean(gone.slot) && gone.slot.startTime === '09:00', gone);
  const bySlot = data(call('GET', `${O}/orders?store=${s1.id}&sort=slot&limit=100`, { token: owner }));
  const list = Array.isArray(bySlot) ? bySlot : [];
  const ids = list.map((o) => o.id);
  const firstBare = list.findIndex((o) => !o.slot);
  const withSlots = firstBare < 0 ? list : list.slice(0, firstBare);
  truthy("[+] the shop's orders by window: earliest first, orders with none last", withSlots.every((o, i) => i === 0 || Date.parse(withSlots[i - 1].slot.startsAt) <= Date.parse(o.slot.startsAt)) && (firstBare < 0 || list.slice(firstBare).every((o) => !o.slot)), list.map((o) => [o.id, o.slot && o.slot.startsAt]));
  const at = (order) => ids.indexOf(order.id);
  truthy('[+] ...ours in turn: the 09:00s, midday, the evening delivery, the day after, the till sale', [o1, o2, o4].every((o) => at(o) >= 0 && at(o) < at(o3)) && at(o3) < at(delivered) && at(delivered) < at(later) && at(later) < at(till), { ids, o1: o1.id, o2: o2.id, o4: o4.id, o3: o3.id, delivered: delivered.id, later: later.id, till: till.id });
  const paged = [];
  let cursor = null;
  for (let page = 0; page < 20; page++) {
    const res = call('GET', `${O}/orders?store=${s1.id}&sort=slot&limit=2${cursor ? `&after=${encodeURIComponent(cursor)}` : ''}`, { token: owner });
    const rows = data(res);
    if (res.status !== 200 || !Array.isArray(rows)) break;
    rows.forEach((o) => paged.push(o.id));
    cursor = nextCursor(res);
    if (!cursor) break;
  }
  truthy('[+] ...and two at a time, page by page, the same list with nothing twice', JSON.stringify(paged) === JSON.stringify(ids) && new Set(paged).size === paged.length, { paged, ids });

  // ── 7. the Indian shop, in Kolkata's time ────────────────────────────────────────────────────────
  const kWeek = week(kStore.id, 'PICKUP', '09:00', '11:00', 2, 60, india.owner.token);
  truthy("[+] the Indian owner sets its shop's collection windows, in Kolkata's zone", created(kWeek) && data(kWeek[0]).timeZone === KOL, kWeek.map((r) => r.status));
  expect(windowsAt(kStore.id, rivals.MANAGER.token), "[+] its manager reads its own shop's windows", 200);
  const kToday = localDate(KOL, Date.now());
  const kListing = data(listing(india, kStore.id, 'PICKUP'));
  const kTodayAfter = localDate(KOL, Date.now());
  const kDays = kListing.days || [];
  truthy('[+] its storefront shows seven days from today in Kolkata, each clock time its instant there', kListing.timeZone === KOL && kDays.length === 7 && (kDays[0].date === kToday || kDays[0].date === kTodayAfter) && slotsOf(kListing).length > 0 && inZone(KOL, slotsOf(kListing)), { timeZone: kListing.timeZone, dates: kDays.map((d) => d.date) });
  const kTomorrow = kDays[1] || { date: '', slots: [] };
  const kFirst = (kTomorrow.slots || []).find((s) => s.startTime === '09:00');
  truthy("[+] 09:00 in Kolkata and 09:00 in Warsaw are different moments, each its own zone's", Boolean(kFirst) && Date.parse(kFirst.startsAt) === instantOf(KOL, kTomorrow.date, '09:00') && Date.parse(first.startsAt) === instantOf(WAW, tomorrow.date, '09:00') && Date.parse(kFirst.startsAt) !== Date.parse(first.startsAt), { kolkata: kFirst, warsaw: first.startsAt });
  const kOrder = place(kShopper, india, pickup(kStore.id, kFirst, chai, '+91 98765 43210'));
  expect(kOrder, '[+] a shopper of the Indian business takes a window at its shop', 201);
  truthy('[+] ...held in Kolkata\'s time', Boolean(data(kOrder).slot) && data(kOrder).slot.timeZone === KOL && data(kOrder).slot.startTime === '09:00', data(kOrder).slot);

  // ── 8. the Indian business, naming the Polish shop and windows, reaches nothing ──────────────────
  const firstRow = (data(windowsAt(s1.id)) || []).find((w) => w.id === first.windowId) || {};
  const tamper = { weekday, startTime: '09:00', endTime: '11:00', capacity: 50, cutoffMinutes: 0, active: true };
  const storeRefused = (res) => (res.status === 404 && errorCode(res) === 'ORDER_SLOT_STORE_NOT_FOUND') || (res.status === 403 && errorCode(res) === 'STORE_ACCESS_DENIED');
  expect(windowsAt(s1.id, rivals.OWNER.token), "[-] the Indian owner, naming the Polish shop, reads no windows", 404, 'ORDER_SLOT_STORE_NOT_FOUND');
  expect(addWindow(win(3, '20:00', '21:00'), rivals.OWNER.token), '[-] ...the owner sets none there', 404, 'ORDER_SLOT_STORE_NOT_FOUND');
  expect(editWindow(first.windowId, tamper, rivals.OWNER.token), '[-] ...and the owner changes none of its windows', 404, 'ORDER_SLOT_WINDOW_NOT_FOUND');
  const mgrRead = windowsAt(s1.id, rivals.MANAGER.token);
  truthy('[-] its manager, held to its own shop, reads none there (refused at the store or not found)', storeRefused(mgrRead), `${mgrRead.status} ${errorCode(mgrRead)}`);
  const mgrAdd = addWindow(win(3, '20:00', '21:00'), rivals.MANAGER.token);
  truthy('[-] ...the manager sets none there', storeRefused(mgrAdd), `${mgrAdd.status} ${errorCode(mgrAdd)}`);
  expect(editWindow(first.windowId, tamper, rivals.MANAGER.token), '[-] ...and the manager changes none of its windows', 404, 'ORDER_SLOT_WINDOW_NOT_FOUND');
  expect(windowsAt(s1.id, rivals.STOREKEEPER.token), '[-] its storekeeper reads none', 403);
  expect(editWindow(first.windowId, tamper, rivals.STOREKEEPER.token), '[-] ...and changes none', 403);
  expect(windowsAt(s1.id, rivals.CASHIER.token), '[-] its cashier reads none', 403);
  expect(addWindow(win(3, '20:00', '21:00'), rivals.CASHIER.token), '[-] ...and sets none', 403);
  expect(listing(india, s1.id, 'PICKUP'), "[-] its storefront lists none of the Polish shop's windows", 404, 'ORDER_SLOT_STORE_NOT_FOUND');
  const across = place(kShopper, india, pickup(s1.id, midday));
  truthy('[-] its shopper takes no place at the Polish shop', (across.status === 409 && errorCode(across) === 'STORE_NOT_OPERATIONAL') || (across.status === 400 && errorCode(across) === 'ORDER_SLOT_UNKNOWN'), `${across.status} ${errorCode(across)}`);
  expect(place(kShopper, india, pickup(kStore.id, midday, chai, '+91 98765 43210')), '[-] ...nor with a Polish window at its own shop', 400, 'ORDER_SLOT_UNKNOWN');
  truthy("[-] the Indian owner's order list holds nothing of the Polish shop", (() => {
    const d = data(call('GET', `${O}/orders?store=${s1.id}&sort=slot`, { token: rivals.OWNER.token }));
    return Array.isArray(d) && d.length === 0;
  })());
  expect(call('GET', `${O}/orders/${o4.id}`, { token: rivals.OWNER.token }), '[-] ...and it reads no Polish order', 404, 'ORDER_NOT_FOUND');
  const after = data(windowsAt(s1.id));
  const firstAfter = (Array.isArray(after) ? after : []).find((w) => w.id === first.windowId) || {};
  truthy('[+] and nothing of ours moved: the same windows, the 09:00 as set and still full, midday with three left', Array.isArray(after) && after.length === set.length + 1 && firstAfter.capacity === firstRow.capacity && firstAfter.capacity === 2 && firstAfter.cutoffMinutes === 60 && firstAfter.active === true && left(first) === 0 && left(midday) === 3, { count: Array.isArray(after) ? after.length : after, first: firstAfter, left: [left(first), left(midday)] });
}
