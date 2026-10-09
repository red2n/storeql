// Store task lists and opening/closing checklists (store operations & workforce), through the gateway.
//
// The roster says who is in and the clock says they turned up; neither says what they were meant to
// DO. A shop runs on a list — open up, count the float, check the bins, lock the back door — and a
// manager who cannot see whether it was finished is managing by hope. One mechanism: a task may carry
// lines, and a task with lines IS a checklist, finished when every required line is ticked.
//
// What only a live stack proves is who may press what. Writing the list is management's, under the
// /admin/ prefix the gateway gates; working it is the staff's own, outside that prefix, because a
// checklist only a manager could tick is a checklist nobody keeps. And that the same 08:00 falls due
// at a different instant in a different shop, because a list is read on the store's own clock.
//
// The assertions that matter to an auditor: a day is generated once however many ask, a checklist is
// refused until its required lines are ticked, a skipped task needs a reason, a line ticked twice is a
// conflict, and a list withdrawn today leaves what was already done alone.
//
// Refused: an unknown kind, a time that is not one, a day outside the week, a weekly list with no day,
// a blank line, a range that is too long, finishing with lines outstanding, ticking a line twice,
// skipping with no reason, finishing what is finished, raising the same job twice, raising a withdrawn
// list, a cashier writing the list or reading the manager's day, somebody not on the store's staff
// ticking its list, another business touching any of it, and nobody at all.
//
//   k6/run.sh store-tasks-flow
import { Counter } from 'k6/metrics';
import { ALL_CHECKS_PASS, call, data, expect, must, sellingTenant, truthy, uniq } from './lib/storeql.js';

const completed = new Counter('flow_completed');
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
  setupTimeout: '4m',
};

const ADMIN = '/api/tenant-svc/admin/workforce/tasks';
const WORK = '/api/tenant-svc/workforce/tasks';

export function setup() {
  const shop = sellingTenant(`tasks-${uniq().slice(0, 6)}`);
  // A second store on another clock, so "today" and "00:01" can be shown to be the store's own. Made
  // directly rather than through addStore, whose clock follows the tenant's country.
  const mumbai = must(
    call('POST', '/api/tenant-svc/admin/stores', {
      token: shop.tenant.owner.token,
      body: { name: `${shop.tenant.label} Mumbai`, code: `MUM-${uniq()}`.slice(0, 32), type: 'STORE', line1: '12 Marine Drive', city: 'Mumbai', pincode: '400001', country: shop.tenant.country, timezone: 'Asia/Kolkata' },
    }),
    201,
    'the Mumbai store',
  );
  return { shop, mumbai, rival: shop.rival };
}

export default function ({ shop, mumbai, rival }) {
  const owner = shop.tenant.owner.token;
  const cashier = shop.cashier;
  const store = shop.store.id;
  const post = (path, body, token) => call('POST', path, { token: token || owner, body });
  const get = (path, token) => call('GET', path, { token: token || owner });
  const del = (path, token) => call('DELETE', path, { token: token || owner });
  const asCashier = (method, path, body) => call(method, path, { token: cashier.token, body });
  // The store's own date comes from the server, which is the only clock that counts: k6's runtime
  // does not honour a timeZone in toLocaleDateString, and a date computed here would be wrong on
  // exactly the nights this suite exists to check.

  // ── writing the list ─────────────────────────────────────────────────────────────────────────────
  const opening = data(
    post(`${ADMIN}/lists`, {
      title: 'Open up',
      kind: 'OPENING',
      dueTime: '00:01',
      graceMinutes: 1440,
      lines: [{ text: 'Unlock the door' }, { text: 'Count the float' }, { text: 'Water the plant', required: false }],
    }),
  );
  truthy('[+] an opening checklist is written, with its lines in order', opening && opening.checklist === true && opening.lines.length === 3 && opening.lines[1].position === 2, opening);
  const bins = data(post(`${ADMIN}/lists`, { title: 'Empty the bins', kind: 'DAILY', dueTime: '23:58' }));
  truthy('[+] a task without lines is a single thing to do, not a checklist', bins && bins.checklist === false, bins);
  // A weekday that is nobody's today: three days past the UTC date. The stores in this suite are
  // in London (UTC+0/+1) and Mumbai (UTC+5:30), whose own date is at most a day ahead of UTC's, so
  // "tomorrow in UTC" fell due in Mumbai every night between 00:00 and 05:30 IST and the count was
  // 3, not 2.
  const notToday = ((new Date().getUTCDay() + 6) % 7 + 3) % 7 + 1;
  const weekly = data(post(`${ADMIN}/lists`, { title: 'Sweep the yard', kind: 'WEEKLY', daysOfWeek: [notToday], dueTime: '17:00' }));
  truthy('[+] a weekly list names its day', weekly && weekly.daysOfWeek.join() === String(notToday), weekly);
  const adHoc = data(post(`${ADMIN}/lists`, { title: 'Put the delivery away', kind: 'AD_HOC', dueTime: '15:00' }));
  truthy('[+] an ad-hoc list is raised by hand', !!adHoc, adHoc);

  expect(post(`${ADMIN}/lists`, { title: 'X', kind: 'ROUTINE', dueTime: '08:00' }), '[-] an unknown kind is refused', 400, 'TASK_LIST_INVALID');
  expect(post(`${ADMIN}/lists`, { title: 'X', kind: 'DAILY', dueTime: 'eight' }), '[-] a time that is not one is refused', 400, 'TASK_TIME_INVALID');
  expect(post(`${ADMIN}/lists`, { title: 'X', kind: 'DAILY', dueTime: '08:00', daysOfWeek: [8] }), '[-] a day outside the week would read as scheduled and never fall due', 400, 'TASK_LIST_INVALID');
  expect(post(`${ADMIN}/lists`, { title: 'X', kind: 'WEEKLY', dueTime: '08:00' }), '[-] a weekly list with no day is refused', 400, 'TASK_LIST_INVALID');
  expect(post(`${ADMIN}/lists`, { title: 'X', kind: 'DAILY', dueTime: '08:00', lines: [{ text: '  ' }] }), '[-] a blank line is refused', 400, 'VALIDATION_FAILED');

  // ── the day ──────────────────────────────────────────────────────────────────────────────────────
  const first = post(`${ADMIN}/days`, { storeId: store });
  expect(first, "[+] a manager generates the store's day", 200);
  truthy('[+] ...two lists fall due today: the opening list and the bins; the weekly one is another day, the delivery is by hand', data(first) === 2, data(first));
  truthy('[+] a second generation creates nothing: the unique constraint decides, not the caller', data(post(`${ADMIN}/days`, { storeId: store })) === 0, 'idempotent');

  const today = data(asCashier('GET', `${WORK}?storeId=${store}`));
  truthy("[+] the cashier sees today's list at their store, with its lines, all on one business date", Array.isArray(today) && today.length === 2 && today.every((t) => t.businessDate === today[0].businessDate), today);
  const london = today[0].businessDate;
  const list = today.find((t) => t.title === 'Open up');
  const task = today.find((t) => t.title === 'Empty the bins');
  truthy('[+] ...the checklist naming what is still to tick', list && list.outstanding === 2 && list.items.length === 3, list);

  // ── working it ───────────────────────────────────────────────────────────────────────────────────
  expect(asCashier('POST', `${WORK}/${list.id}/complete`, {}), '[-] a checklist is not finished with required lines unticked: a closing list signed off with the safe open is what required is for', 409, 'TASK_LINES_OUTSTANDING');
  expect(asCashier('POST', `${WORK}/${list.id}/lines/1/tick`), '[+] a line is ticked', 200);
  const ticked = data(asCashier('POST', `${WORK}/${list.id}/lines/2/tick`));
  truthy('[+] ...by whoever is at the till, and the count comes down', ticked && ticked.outstanding === 0 && ticked.items[1].tickedBy === cashier.userId, ticked);
  expect(asCashier('POST', `${WORK}/${list.id}/lines/2/tick`), '[-] a line ticked twice is a conflict, not a second tick', 409, 'TASK_LINE_TICKED');
  expect(asCashier('POST', `${WORK}/${list.id}/lines/9/tick`), '[-] a line that is not on the list is not there', 404, 'TASK_LINE_NOT_FOUND');
  const done = data(asCashier('POST', `${WORK}/${list.id}/complete`, { note: 'all quiet' }));
  truthy('[+] finished: done, by the cashier, with the optional line left as it was', done && done.status === 'DONE' && done.completedBy === cashier.userId && !done.items[2].tickedAt, done);
  truthy('[+] ...and late, because it fell due at one minute past midnight — late is not missed', done && done.late === true, done);
  expect(asCashier('POST', `${WORK}/${list.id}/complete`, {}), '[-] finishing what is finished is refused', 409, 'TASK_NOT_OPEN');
  expect(asCashier('POST', `${WORK}/${task.id}/skip`, { reason: '' }), '[-] skipping needs a reason: a skipped closing check with none is what an auditor asks about', 400, 'VALIDATION_FAILED');
  const skipped = data(asCashier('POST', `${WORK}/${task.id}/skip`, { reason: 'Bin lorry did not come' }));
  truthy('[+] explained away, with the reason kept', skipped && skipped.status === 'SKIPPED' && skipped.skippedReason === 'Bin lorry did not come', skipped);

  const summary = data(get(`${ADMIN}/summary?storeId=${store}&from=${london}&to=${london}`));
  truthy("[+] the manager's day: one done late, one skipped, settled", summary && summary[0].done === 1 && summary[0].late === 1 && summary[0].skipped === 1 && summary[0].settled === true, summary);

  // ── raised by hand, and withdrawn ────────────────────────────────────────────────────────────────
  const raised = data(post(`${ADMIN}/raise`, { listId: adHoc.id, storeId: store }));
  truthy('[+] a job is raised by hand onto the day', raised && raised.status === 'OPEN' && raised.kind === 'AD_HOC', raised);
  expect(post(`${ADMIN}/raise`, { listId: adHoc.id, storeId: store }), '[-] the same job is not raised twice', 409, 'TASK_ALREADY_RAISED');
  expect(del(`${ADMIN}/lists/${adHoc.id}`), '[+] the list is withdrawn', 200);
  expect(del(`${ADMIN}/lists/${adHoc.id}`), '[-] ...once', 409, 'TASK_LIST_WITHDRAWN');
  expect(post(`${ADMIN}/raise`, { listId: adHoc.id, storeId: store }), '[-] a withdrawn list is not raised', 409, 'TASK_LIST_WITHDRAWN');
  truthy('[+] what was already raised stands', data(asCashier('GET', `${WORK}/${raised.id}`)).status === 'OPEN', 'raised stands');
  truthy('[+] withdrawn lists leave what is offered and stay in what is kept', (data(get(`${ADMIN}/lists`)) || []).every((l) => l.id !== adHoc.id) && (data(get(`${ADMIN}/lists?all=true`)) || []).some((l) => l.id === adHoc.id), 'withdrawn');

  // ── the store's own clock ────────────────────────────────────────────────────────────────────────
  const generated = data(post(`${ADMIN}/days`, { storeId: mumbai.id }));
  truthy('[+] the Mumbai store gets the business-wide lists for its own today', generated === 2, generated);
  const inMumbai = data(get(`${WORK}?storeId=${mumbai.id}`));
  const mumbaiOpening = inMumbai.find((t) => t.title === 'Open up');
  truthy('[+] ...and 00:01 in Mumbai is 18:31Z the evening before: the list is read on the store\'s clock', mumbaiOpening && mumbaiOpening.dueAt.endsWith('T18:31:00Z'), mumbaiOpening);
  expect(get(`${ADMIN}/days?storeId=${store}&from=2026-01-01&to=2026-12-31`), '[-] a range that is too long is refused', 400, 'TASK_RANGE_INVALID');

  // ── who may press what ───────────────────────────────────────────────────────────────────────────
  expect(asCashier('POST', `${ADMIN}/lists`, { title: 'Sneak', kind: 'DAILY', dueTime: '09:00' }), '[-] a cashier does not write the list', 403, 'FORBIDDEN');
  expect(asCashier('GET', `${ADMIN}/summary?storeId=${store}&from=${london}&to=${london}`), "[-] nor reads the manager's day", 403, 'FORBIDDEN');
  expect(call('POST', `${WORK}/${raised.id}/lines/1/tick`, { token: shop.storekeeper.token }), '[-] a line that is not there is not there for anybody', 404, 'TASK_LINE_NOT_FOUND');
  expect(call('POST', `${WORK}/${raised.id}/complete`, { token: rival.owner.token, body: {} }), '[abuse] another business does not finish this one\'s work', 404);
  expect(get(`${WORK}/${raised.id}`, rival.owner.token), '[abuse] nor sees it', 404);
  truthy('[abuse] nor its lists', (data(get(`${ADMIN}/lists?all=true`, rival.owner.token)) || []).length === 0, 'rival lists');
  // The store is judged before the caller: another business naming our store is told it does not
  // exist, never that it is not theirs (intent/workforce-rules.md, "A store is judged in one order").
  expect(get(`${ADMIN}/summary?storeId=${store}&from=${london}&to=${london}`, rival.owner.token), "[abuse] nor reads our store's day, which is no store of theirs", 404, 'STORE_NOT_FOUND');
  expect(get(`${WORK}?storeId=${store}`, rival.owner.token), "[abuse] nor our store's task list", 404, 'STORE_NOT_FOUND');
  expect(call('GET', `${WORK}?storeId=${store}`, {}), '[abuse] nobody at all is refused at the door', 401);

  completed.add(1);
}
