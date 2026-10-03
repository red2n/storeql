// Store communications and broadcast (store operations & workforce), through the gateway.
//
// A shop runs on notices as much as on lists — the price change on Monday, the recall on the counter,
// the new closing procedure. Until now the only way to tell every store was a message outside the
// platform, and the only way to know who had read it was to ask. A notice is never edited: what staff
// acknowledged is the text they saw, so a correction withdraws and publishes again, and both stay.
//
// What only a live stack proves is who may press what. Publishing is management's, under the /admin/
// prefix the gateway gates; reading and acknowledging is the staff's own, outside that prefix, because
// a notice only management could read reaches nobody. And that a notice reaches the people it is
// addressed to — this store, this role — and nobody else.
//
// The assertions that matter: one acknowledgement per person however many taps, a manager's reach that
// names who has not read a notice, an urgent notice announced to wake the devices and a routine one not,
// and a withdrawn notice gone from the shop floor while its acknowledgements stand.
//
// Refused: an unknown priority, an expiry that is not an instant, one before publication, a store nobody
// holds, a blank title, withdrawing without a reason or twice, acknowledging twice, acknowledging what
// is withdrawn or not addressed to you, a cashier publishing or reading the reach, a login held to one
// store acknowledging at another, another business seeing or acknowledging any of it, and nobody at all.
// An owner (held to no store) reads and acknowledges at any store without an assignment there, as they
// work any store's task list; staff below management are held to where they are assigned (409
// WORKFORCE_NOT_ASSIGNED, proved by BroadcastIT, since every k6 staff login names its stores).
//
//   k6/run.sh broadcast-flow
import { Counter } from 'k6/metrics';
import { ALL_CHECKS_PASS, addStore, call, data, expect, sellingTenant, staffUser, truthy, uniq } from './lib/storeql.js';

const completed = new Counter('flow_completed');
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
  setupTimeout: '4m',
};

const ADMIN = '/api/tenant-svc/admin/workforce/broadcasts';
const READ = '/api/tenant-svc/workforce/broadcasts';

export function setup() {
  const shop = sellingTenant(`notices-${uniq().slice(0, 6)}`);
  const second = addStore(shop.tenant, 'S2');
  const cashierB = staffUser(shop.tenant, 'CASHIER', [second.id]);
  return { shop, second, cashierB, rival: shop.rival };
}

export default function ({ shop, second, cashierB, rival }) {
  const owner = shop.tenant.owner.token;
  const cashier = shop.cashier;
  const keeper = shop.storekeeper;
  const storeA = shop.store.id;
  const post = (path, body, token) => call('POST', path, { token: token || owner, body });
  const get = (path, token) => call('GET', path, { token: token || owner });
  const mine = (user, storeId) => data(call('GET', `${READ}?storeId=${storeId}`, { token: user.token })) || [];
  const ack = (user, id, storeId) => call('POST', `${READ}/${id}/acknowledgement`, { token: user.token, body: { storeId } });

  // ── to every store ───────────────────────────────────────────────────────────────────────────────
  const notice = data(post(ADMIN, { title: 'Bananas up 10p', body: 'From Monday.', priority: 'INFO', requiresAck: true }));
  truthy('[+] a notice is published to every store, pinned until withdrawn', notice && notice.status === 'PUBLISHED' && !notice.storeId && !notice.expiresAt, notice);
  const seenA = mine(cashier, storeA);
  truthy("[+] the cashier at the first store sees it, unacknowledged", seenA.length === 1 && seenA[0].id === notice.id && !seenA[0].acknowledgedAt, seenA);
  truthy('[+] ...and so does the cashier at the second', mine(cashierB, second.id).some((n) => n.id === notice.id), 'second store');

  expect(ack(cashier, notice.id, storeA), '[+] acknowledged, once', 201);
  truthy('[+] ...and the notice now says when', (mine(cashier, storeA)[0] || {}).acknowledgedAt !== undefined, mine(cashier, storeA)[0]);
  expect(ack(cashier, notice.id, storeA), '[-] a second tap is a conflict, not a second reading: the constraint decides', 409, 'BROADCAST_ALREADY_ACKNOWLEDGED');

  const reach = data(get(`${ADMIN}/${notice.id}/reach`));
  const atA = reach.find((r) => r.storeId === storeA);
  const atB = reach.find((r) => r.storeId === second.id);
  truthy("[+] the manager's reach, per store: two addressed at the first, one acknowledged, the storekeeper named as outstanding", atA && atA.addressed === 2 && atA.acknowledged === 1 && atA.outstanding.join() === keeper.userId && atA.complete === false, atA);
  truthy('[+] ...and the second store with its cashier outstanding', atB && atB.addressed === 1 && atB.outstanding.join() === cashierB.userId, atB);

  // ── to one store, one role ───────────────────────────────────────────────────────────────────────
  const keepers = data(post(ADMIN, { title: 'Delivery at six', body: 'Be in the yard.', priority: 'IMPORTANT', storeId: storeA, role: 'STOREKEEPER' }));
  truthy('[+] a notice to one store and one role', keepers && keepers.storeId === storeA && keepers.role === 'STOREKEEPER', keepers);
  truthy('[+] ...reaches the storekeeper there', mine(keeper, storeA).some((n) => n.id === keepers.id), 'keeper sees it');
  truthy('[+] ...and not the cashier beside them', !mine(cashier, storeA).some((n) => n.id === keepers.id), 'cashier does not');
  truthy('[+] ...nor the other store', !mine(cashierB, second.id).some((n) => n.id === keepers.id), 'other store does not');
  expect(ack(cashier, keepers.id, storeA), '[-] acknowledging what is not addressed to you is refused', 409, 'BROADCAST_NOT_ADDRESSED');
  truthy('[+] reach counts only the store and role it went to', data(get(`${ADMIN}/${keepers.id}/reach`)).length === 1 && data(get(`${ADMIN}/${keepers.id}/reach`))[0].addressed === 1, 'reach');

  // ── urgent, withdrawn, expired ───────────────────────────────────────────────────────────────────
  const urgent = data(post(ADMIN, { title: 'Recall', body: 'Batch 42 off the shelf now.', priority: 'URGENT' }));
  truthy('[+] an urgent notice is published', urgent && urgent.priority === 'URGENT', urgent);
  expect(post(`${ADMIN}/${urgent.id}/withdrawal`, { reason: '' }), '[-] withdrawing needs a reason', 400);
  const withdrawn = data(post(`${ADMIN}/${urgent.id}/withdrawal`, { reason: 'wrong batch' }));
  truthy('[+] withdrawn, with why', withdrawn && withdrawn.status === 'WITHDRAWN' && withdrawn.withdrawnReason === 'wrong batch', withdrawn);
  expect(post(`${ADMIN}/${urgent.id}/withdrawal`, { reason: 'again' }), '[-] ...once', 409, 'BROADCAST_WITHDRAWN');
  truthy('[+] gone from the shop floor', !mine(cashier, storeA).some((n) => n.id === urgent.id), 'not current');
  expect(ack(cashier, urgent.id, storeA), '[-] and cannot be acknowledged', 409, 'BROADCAST_NOT_CURRENT');
  truthy('[+] still there for management, out of what is offered', !(data(get(ADMIN)) || []).some((n) => n.id === urgent.id) && (data(get(`${ADMIN}?all=true`)) || []).some((n) => n.id === urgent.id), 'kept');
  expect(post(ADMIN, { title: 'Nothing', body: 'x', priority: 'INFO', expiresAt: '2020-01-01T00:00:00Z' }), '[-] a notice that expires before it is published tells nobody anything', 400, 'BROADCAST_INVALID');

  // ── refusals, and who may press what ─────────────────────────────────────────────────────────────
  expect(post(ADMIN, { title: 'x', body: 'y', priority: 'SHOUT' }), '[-] an unknown priority is refused', 400, 'BROADCAST_INVALID');
  expect(post(ADMIN, { title: 'x', body: 'y', priority: 'INFO', expiresAt: 'tomorrow' }), '[-] an expiry that is not an instant is refused', 400, 'BROADCAST_EXPIRY_INVALID');
  expect(post(ADMIN, { title: 'x', body: 'y', priority: 'INFO', storeId: '01900000-0000-7000-8000-0000000000ab' }), '[-] a store nobody holds is refused', 404, 'STORE_NOT_FOUND');
  expect(post(ADMIN, { title: '', body: 'y', priority: 'INFO' }), '[-] a blank title is refused', 400);
  expect(post(ADMIN, { title: 'Sneak', body: 'y', priority: 'URGENT' }, cashier.token), '[-] a cashier does not publish', 403);
  expect(get(`${ADMIN}/${notice.id}/reach`, cashier.token), '[-] nor reads the reach', 403);
  // A store-scoped token is refused at the store it is not held to. An owner is held to no store and
  // acts at any store of the business, as on the task list (intent/workforce-rules.md, "Notices are
  // worked as the task list is"): the every-store notice reaches them at the second store too.
  expect(ack(shop.storekeeper, notice.id, second.id), '[-] a store-scoped login does not acknowledge at another store', 403, 'STORE_ACCESS_DENIED');
  truthy('[+] the owner, assigned nowhere, reads the second store\'s notices', mine({ token: owner }, second.id).some((n) => n.id === notice.id), 'owner reads');
  expect(ack({ token: owner }, notice.id, second.id), '[+] ...and acknowledges the every-store notice there without an assignment', 201);
  const atBAfter = data(get(`${ADMIN}/${notice.id}/reach`)).find((r) => r.storeId === second.id);
  truthy(
    "[+] ...but the second store's reach still counts only its own staff: head office reading it is not the branch reading it",
    atBAfter && atBAfter.addressed === 1 && atBAfter.acknowledged === 0 && atBAfter.outstanding.join() === cashierB.userId,
    atBAfter,
  );
  expect(ack({ token: owner }, keepers.id, storeA), '[-] the owner is addressed by their own tier, never taken for the storekeepers', 409, 'BROADCAST_NOT_ADDRESSED');
  expect(get(`${ADMIN}/${notice.id}`, rival.owner.token), '[abuse] another business does not see the notice', 404, 'BROADCAST_NOT_FOUND');
  expect(call('POST', `${READ}/${notice.id}/acknowledgement`, { token: rival.owner.token, body: { storeId: rival.stores[0].id } }), '[abuse] nor acknowledges it', 404, 'BROADCAST_NOT_FOUND');
  // The store is judged before the caller: another business naming our store is told it does not
  // exist, never that it is not theirs (intent/workforce-rules.md, "A store is judged in one order").
  expect(get(`${READ}?storeId=${storeA}`, rival.owner.token), "[abuse] nor reads our store's notices, which is no store of theirs", 404, 'STORE_NOT_FOUND');
  expect(post(ADMIN, { title: 'x', body: 'y', priority: 'INFO', storeId: storeA }, rival.owner.token), '[abuse] nor publishes to our store', 404, 'STORE_NOT_FOUND');
  expect(call('GET', `${READ}?storeId=${storeA}`, {}), '[abuse] nobody at all is refused at the door', 401);

  completed.add(1);
}
