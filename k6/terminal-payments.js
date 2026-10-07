// EMV terminals and pinpads (07.16), through the gateway.
//
// A CARD tender used to be recorded because a cashier said so. Nothing asked a terminal whether the
// card was approved, and nothing kept what a card receipt has to carry — the scheme, the four digits
// a receipt may print, the authorisation code, the application the card ran, how it was read and how
// the cardholder was verified.
//
// The check this suite exists for is the double charge: the SAME Idempotency-Key must never reach the
// terminal twice. That is how a real customer gets charged twice — the screen does not change, the
// cashier presses again — so the attempt is claimed before the device is asked anything.
//
// The second is the timeout, which is not a decline. A decline took nothing and a failure never
// started, but a timeout means the card MAY have been charged: no tender is recorded, nothing is
// retried, and the attempt keeps its reference so it can be found in the acquirer's settlement file.
//
// The third is the server's own guard (2 Oct 2026): a machine with a card payment that is not
// settled — still at the machine, approved and neither recorded on its sale nor put back, or timed
// out with nobody's word on it — takes no new press, whatever the till remembers. A manager settles
// it: puts it back with a reason, or says what the machine shows — and only one whose role holds
// sales.refund, since either can send money back. A cancel at the till asks the machine to stop and
// settles nothing: only the machine's answer (or a manager, for one that never comes) does. And a
// card sale given up (voided or cancelled) goes back on the card through the machine that took it,
// never as a refund in the books alone — even when the machine's approval becomes known only after
// the sale was given up — and no card is recorded on it after.
//
// The simulator picks its outcome from the amount's minor units, the acquirers' own convention:
// .01 declines, .02 is cancelled, .03 times out, .04 fails. Everything else is approved. A refund
// declines on .01, fails on .04 and is never answered on .05.
//
// And a refund the machine has not put back — still at it, or never answered with nobody's word on
// it — settles nothing: the sale it would reverse keeps the machine held, and so does the refund,
// and no other refund of that card is asked until it is accounted for. A machine holding a card
// nobody has settled is not retired, and a card is taken in the business's own currency only.
//
// And a retired machine strands nothing (3 Oct 2026): a card it took goes back through the machine
// that replaced it at its store (a refund is linked to the sale by the vendor's reference, so it
// needs the vendor, not the device); the last machine of its make there is not retired while a card
// is owed money back; and when no machine can put a card back, a manager records how it was given
// back another way — once, with the books' refund written with it.
//
//   k6/run.sh terminal-payments
import { Counter } from 'k6/metrics';
import { ALL_CHECKS_PASS, addStore, call, data, expect, must, newId, newKey, poll, provisionStaff, sellingTenant, signInUntil, staffUser, truthy, uniq } from './lib/storeql.js';

const completed = new Counter('flow_completed');
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
  setupTimeout: '4m',
};

const ADMIN = '/api/payment-svc/admin/payments/terminals';
const TERM = '/api/payment-svc/payments/terminal';

export default function () {
  const tag = uniq().toUpperCase().slice(0, 8);
  const shop = sellingTenant(`emv-${tag}`);
  const owner = shop.tenant.owner.token;
  const cashier = shop.cashier.token;
  const store = shop.store.id;
  // A manager at this store, and staff held to another store of the same business: store-held
  // callers act only where they are assigned.
  const manager = staffUser(shop.tenant, 'MANAGER', [store]);
  const elsewhere = addStore(shop.tenant, 'S2');
  const elsewhereManager = staffUser(shop.tenant, 'MANAGER', [elsewhere.id]);
  const elsewhereCashier = staffUser(shop.tenant, 'CASHIER', [elsewhere.id]);
  // A manager at this store whose role (20.10) does not hold sales.refund: a manager in every other
  // way, who sends no money back to a card — not by a refund, a person's word on a machine, or a retry.
  expect(call('POST', '/api/tenant-svc/admin/roles', { token: owner, body: { code: 'TILL_LEAD', name: 'Till lead', baseTier: 'MANAGER', permissions: ['sales.void', 'till.manage'] } }),
    '[+] a manager role without sales.refund is defined', 201);
  const lead = provisionStaff(shop.tenant, `emv-${tag}-lead`);
  must(call('POST', '/api/tenant-svc/admin/staff', { token: owner, body: { userId: lead.userId, storeId: store, role: 'TILL_LEAD' } }), 201, 'assign the till lead');
  signInUntil(lead, (c) => c.tenant === shop.tenant.tenantId && (c.roles || []).includes('MANAGER') && Array.isArray(c.perms) && !c.perms.includes('sales.refund'));

  // ── the register ────────────────────────────────────────────────────────────────────────────────
  const vendors = data(call('GET', `${ADMIN}/vendors`, { token: owner })) || [];
  truthy('[+] only makes this deployment can talk to are offered', vendors.includes('SIMULATED'), vendors);

  const registered = call('POST', ADMIN, { token: owner, body: { storeId: store, label: `Till ${tag}`, vendor: 'SIMULATED', serial: `SN-${tag}` } });
  expect(registered, '[+] a business registers a card machine for a store', 201);
  const terminal = data(registered);
  truthy('[+] it is on the counter and says it is simulated', terminal.status === 'ACTIVE' && terminal.vendor === 'SIMULATED', terminal);

  expect(call('POST', ADMIN, { token: owner, body: { storeId: store, label: `Till ${tag}`, vendor: 'SIMULATED' } }),
    '[-] two active machines cannot share a label in one store', 409, 'TERMINAL_ALREADY_REGISTERED');
  expect(call('POST', ADMIN, { token: owner, body: { storeId: store, label: `Other ${tag}`, vendor: 'MY_OWN_PINPAD' } }),
    '[-] nor a make the platform cannot talk to', 400, 'TERMINAL_VENDOR_UNKNOWN');
  // Two layers refuse a card number, and through the gateway the OUTER one answers first: the code
  // is the gateway's CARD_DATA_NOT_ACCEPTED, not the service's TERMINAL_CARD_DATA_NOT_ACCEPTED,
  // because the request never reaches payment-svc. The service's own guard is proven by
  // TerminalPaymentIT, which calls it with no gateway in front. Asserting the inner code here read
  // as "the belt is missing" when in fact the braces had caught it.
  expect(call('POST', ADMIN, { token: owner, body: { storeId: store, label: '4242 4242 4242 4242', vendor: 'SIMULATED' } }),
    '[abuse] and a card number is refused before it reaches the service at all', 400, 'CARD_DATA_NOT_ACCEPTED');

  // ── taking a card ───────────────────────────────────────────────────────────────────────────────
  // Each attempt is against its own order, so one outcome cannot be mistaken for another's. The id is
  // a real UUIDv7 from the lib: the platform keeps only v7 ids and its own audit counts anything else
  // as a defect, so a hand-made one would be a defect this suite introduced.
  const sale = (amount, key, token, orderId) => call('POST', TERM, {
    token: token || cashier,
    idem: key,
    body: { terminalId: terminal.id, orderId: orderId || newId(), amount, currency: shop.tenant.currency },
  });
  const body = (res) => { try { return JSON.parse(res.body) || {}; } catch (_) { return {}; } };
  const detailsOf = (res) => JSON.stringify(body(res).details || (body(res).error || {}).details || []);
  const holding = (token) => data(call('GET', `${TERM}/unsettled?terminalId=${terminal.id}`, { token: token || cashier })) || [];

  const key = newKey('press');
  const first = sale('12.50', key);
  expect(first, '[+] the machine answers, and the card is approved', 201);
  const approved = data(first);
  truthy('[+] with what a card receipt has to carry', !!approved.scheme && !!approved.panLast4 && !!approved.authCode && !!approved.entryMode && !!approved.aid, approved);
  truthy('[+] and the receipt line already assembled, so every printer agrees', /\*\*\*\*/.test(approved.receiptLine || ''), approved.receiptLine);
  truthy('[abuse] four digits of the card and no more of it anywhere', (approved.panLast4 || '').length === 4 && !JSON.stringify(approved).includes('4242424242424242'), approved.panLast4);

  // THE check. A second press with the same key must find the first attempt.
  const again = call('POST', TERM, { token: cashier, idem: key, body: { terminalId: terminal.id, orderId: approved.orderId, amount: '12.50', currency: shop.tenant.currency } });
  expect(again, '[+] a second press with the same key is answered, not refused', 201);
  truthy('[+] and it is the SAME attempt, not a second charge on the card', data(again).id === approved.id, { first: approved.id, again: data(again).id });
  truthy('[+] with the same authorisation code', data(again).authCode === approved.authCode, { a: approved.authCode, b: data(again).authCode });
  const history = data(call('GET', `${TERM}/by-order/${approved.orderId}`, { token: cashier })) || [];
  truthy('[+] one attempt on the record for one press, however many times it was pressed', history.length === 1, history.length);

  // ── a machine with a card payment unsettled takes no new one ──────────────────────────────────
  // Whatever the till remembers: the server refuses a new press while an approval is neither recorded
  // on its sale nor put back, so a sale changed mid-payment is never charged twice.
  truthy('[+] an approval nobody has recorded says it holds the machine', approved.standing === 'APPROVED_UNRECORDED', approved.standing);
  const refused = sale('7.00', newKey('press'));
  expect(refused, '[abuse] a new press while an approval is unrecorded is refused, whatever the till remembers', 409, 'TERMINAL_UNSETTLED_APPROVAL');
  truthy('[+] and the refusal names the payment that holds the machine, its sale and its amount',
    detailsOf(refused).includes(`attemptId=${approved.id}`) && detailsOf(refused).includes(`orderId=${approved.orderId}`) && detailsOf(refused).includes('amount=12.50'), detailsOf(refused));
  expect(sale('12.50', newKey('press'), cashier, approved.orderId), '[abuse] the same sale under a new key is a second payment too', 409, 'TERMINAL_UNSETTLED_APPROVAL');
  const replayed = call('POST', TERM, { token: cashier, idem: key, body: { terminalId: terminal.id, orderId: approved.orderId, amount: '12.50', currency: shop.tenant.currency } });
  expect(replayed, '[+] while a replay under the first key is still answered', 201);
  truthy('[+] ...with the first attempt', data(replayed).id === approved.id, data(replayed).id);
  truthy('[+] the machine says what holds it', holding().length === 1 && holding()[0].id === approved.id, holding());
  expect(call('GET', `${TERM}/unsettled?terminalId=${terminal.id}`, { token: shop.rival.owner.token }), '[abuse] another business reads nothing of our machine', 404, 'TERMINAL_NOT_FOUND');
  expect(call('GET', `${TERM}/unsettled?terminalId=${terminal.id}`, { token: elsewhereManager.token }), '[abuse] nor a manager held to another store', 403, 'STORE_ACCESS_DENIED');
  expect(sale('7.00', newKey('press'), elsewhereCashier.token), '[abuse] a cashier held to another store takes no card on this one', 403, 'STORE_ACCESS_DENIED');

  // A cancel is a question for the machine, not its answer: on a card the machine already answered it
  // changes nothing, and the approval stands.
  const cancelled0 = call('POST', `${TERM}/${approved.id}/cancel`, { token: cashier, body: {} });
  expect(cancelled0, '[+] a cancel after the machine approved is answered', 200);
  truthy('[abuse] ...and never turns the approval into a cancellation', data(cancelled0).state === 'APPROVED' && data(cancelled0).standing === 'APPROVED_UNRECORDED', data(cancelled0));

  // ── putting money back ────────────────────────────────────────────────────────────────────────
  const REFUNDS = `${TERM}/${approved.id}/refunds`;
  expect(call('POST', REFUNDS, { token: lead.token, idem: newKey('refund'), body: { amount: '1.00', reason: 'not mine to give' } }),
    '[abuse] a manager whose role does not hold sales.refund puts nothing back on a card', 403, 'PERMISSION_DENIED');
  truthy('[abuse] ...and nothing reached the machine', !(data(call('GET', `${TERM}/by-order/${approved.orderId}`, { token: owner })) || []).some((a) => a.kind === 'REFUND'), 'no refund');
  expect(call('POST', REFUNDS, { token: owner, idem: newKey('refund'), body: { amount: '5.00' } }),
    '[-] money goes back only with a reason', 400, 'VALIDATION_FAILED');
  expect(call('POST', REFUNDS, { token: owner, idem: newKey('refund'), body: { amount: '5.00', reason: 'x'.repeat(501) } }),
    '[-] and a reason of at most 500 characters', 400, 'VALIDATION_FAILED');
  const back = call('POST', REFUNDS, { token: owner, idem: newKey('refund'), body: { amount: '5.00', reason: 'one item was faulty' } });
  expect(back, '[+] money goes back on the card that paid, with a reason', 201);
  truthy('[+] linked to the attempt it puts back, never to a card presented again', data(back).refundOf === approved.id && data(back).kind === 'REFUND', data(back));
  truthy('[+] and the reason is kept with who asked', data(back).reason === 'one item was faulty' && !!data(back).requestedBy, data(back));
  expect(call('POST', `${TERM}/${data(back).id}/cancel`, { token: cashier, body: {} }),
    '[-] a refund is not taken at the pinpad, so it is not cancelled there', 409, 'TERMINAL_NOT_A_SALE');
  expect(sale('7.00', newKey('press')), '[abuse] part of it put back, the rest still holds the machine', 409, 'TERMINAL_UNSETTLED_APPROVAL');
  expect(call('POST', REFUNDS, { token: owner, idem: newKey('refund'), body: { amount: '999.00', reason: 'too much' } }),
    '[-] never more than the card took', 409, 'TERMINAL_REFUND_TOO_LARGE');
  expect(call('POST', REFUNDS, { token: owner, idem: newKey('refund'), body: { amount: '7.51', reason: 'a penny over' } }),
    '[-] nor more than is still on it', 409, 'TERMINAL_REFUND_TOO_LARGE');
  expect(call('POST', REFUNDS, { token: cashier, idem: newKey('refund'), body: { amount: '1.00', reason: 'mine to give' } }),
    '[abuse] a cashier does not put money back on a card', 403);
  expect(call('POST', REFUNDS, { token: elsewhereManager.token, idem: newKey('refund'), body: { amount: '1.00', reason: 'not my store' } }),
    '[abuse] nor a manager held to another store', 403, 'STORE_ACCESS_DENIED');
  expect(call('POST', REFUNDS, { token: shop.rival.owner.token, idem: newKey('refund'), body: { amount: '1.00', reason: 'not ours' } }),
    '[abuse] nor another business, which finds no such payment', 404, 'TERMINAL_ATTEMPT_NOT_FOUND');
  expect(call('POST', REFUNDS, { token: owner, idem: newKey('refund'), body: { amount: '7.50', reason: 'the customer paid in cash instead' } }),
    '[+] putting the rest back on the card', 201);
  truthy('[+] ...settles the machine', holding().length === 0, holding());

  // ── the outcomes that are not approvals ────────────────────────────────────────────────────────
  const declined = data(sale('9.01', newKey('press')));
  truthy('[-] a declined card is settled and says why, in the machine’s own words', declined.state === 'DECLINED' && !!declined.outcomeDetail, declined);
  truthy('[-] and nothing is printed for it, because nothing was taken', !declined.receiptLine && !declined.paymentId, declined);
  truthy('[+] and it does not hold the machine', declined.standing === 'SETTLED', declined.standing);
  expect(call('POST', `${TERM}/${declined.id}/refunds`, { token: owner, idem: newKey('refund'), body: { amount: '1.00', reason: 'nothing' } }),
    '[-] and nothing at all is put back against an attempt that took nothing', 409, 'TERMINAL_NOT_APPROVED');

  const cancelled = data(sale('9.02', newKey('press')));
  truthy('[-] a cancellation at the machine is not an approval', cancelled.state === 'CANCELLED', cancelled);

  const failed = data(sale('9.04', newKey('press')));
  truthy('[-] a fault is settled too — nothing is left waiting for ever', failed.state === 'FAILED' && !!failed.settledAt, failed);

  const timedOut = data(sale('9.03', newKey('press')));
  truthy('[abuse] a timeout is its own state, never a decline and never an approval', timedOut.state === 'TIMED_OUT', timedOut);
  truthy('[abuse] no tender: the platform does not claim money it cannot prove it took', !timedOut.paymentId, timedOut);
  // The reference is the only way to find this attempt in the acquirer's settlement file, which is the
  // only way to learn whether the card was really charged.
  truthy('[+] but it keeps its reference, which is how the money is traced', !!timedOut.providerRef, timedOut);
  expect(sale('5.00', newKey('press')), '[abuse] a timeout nobody has looked at holds the machine: the card may have been charged', 409, 'TERMINAL_UNSETTLED_APPROVAL');

  // ── a person says what the machine shows ───────────────────────────────────────────────────────
  const SETTLE = `${TERM}/${timedOut.id}/settle`;
  const looked = { outcome: 'NOT_TAKEN', reason: 'the machine shows no transaction' };
  expect(call('POST', SETTLE, { token: cashier, idem: newKey('settle'), body: looked }), '[abuse] a cashier does not say what the machine shows', 403);
  expect(call('POST', SETTLE, { token: owner, body: looked }), '[-] a decision needs an Idempotency-Key', 400, 'IDEMPOTENCY_KEY_REQUIRED');
  expect(call('POST', SETTLE, { token: owner, idem: newKey('settle'), body: { outcome: 'NOT_TAKEN' } }), '[-] and a reason', 400, 'VALIDATION_FAILED');
  expect(call('POST', SETTLE, { token: owner, idem: newKey('settle'), body: { outcome: 'MAYBE', reason: 'unsure' } }), '[-] and one of what a machine can show', 400, 'TERMINAL_OUTCOME_INVALID');
  expect(call('POST', SETTLE, { token: shop.rival.owner.token, idem: newKey('settle'), body: looked }), '[abuse] another business cannot settle our machine', 404, 'TERMINAL_ATTEMPT_NOT_FOUND');
  expect(call('POST', SETTLE, { token: elsewhereManager.token, idem: newKey('settle'), body: looked }), '[abuse] nor a manager held to another store', 403, 'STORE_ACCESS_DENIED');
  expect(call('POST', SETTLE, { token: lead.token, idem: newKey('settle'), body: looked }),
    '[abuse] nor a manager whose role does not hold sales.refund: what a person says decides whether money goes back', 403, 'PERMISSION_DENIED');
  const settleKey = newKey('settle');
  const settled = call('POST', SETTLE, { token: manager.token, idem: settleKey, body: looked });
  expect(settled, '[+] a manager at the store says what the machine shows, with a reason', 200);
  const decision = data(settled).decision || {};
  truthy('[+] kept with who and when, and the payment no longer holds the machine',
    decision.outcome === 'NOT_TAKEN' && decision.reason === looked.reason && !!decision.decidedBy && !!decision.decidedAt && data(settled).standing === 'SETTLED', data(settled));
  const settledAgain = call('POST', SETTLE, { token: manager.token, idem: settleKey, body: looked });
  expect(settledAgain, '[+] a replay under the same key answers with the first', 200);
  truthy('[+] ...the same decision', (data(settledAgain).decision || {}).decidedAt === decision.decidedAt, data(settledAgain).decision);
  expect(call('POST', SETTLE, { token: owner, idem: newKey('settle'), body: { outcome: 'APPROVED', reason: 'a second look' } }),
    '[-] it is decided once', 409, 'TERMINAL_ATTEMPT_ALREADY_DECIDED');
  expect(call('POST', `${TERM}/${approved.id}/settle`, { token: owner, idem: newKey('settle'), body: looked }),
    '[-] and only a payment the machine did not answer is decided by a person', 409, 'TERMINAL_NOT_TIMED_OUT');

  // ── a refund the machine never answers settles nothing ────────────────────────────────────────
  // The sale changed after its card was approved: a manager puts the approval back, and the machine
  // never answers the refund (the simulator's .05). Money may or may not be back on the card, so
  // neither the sale nor the refund lets the machine take the changed sale until a person says what
  // it shows — a refund only asked used to count as back, and the changed sale was charged again.
  const changing = data(sale('10.10', newKey('press')));
  truthy('[+] the card for a sale about to change is approved', changing.state === 'APPROVED', changing);
  const quiet0 = call('POST', `${TERM}/${changing.id}/refunds`, { token: manager.token, idem: newKey('refund'), body: { amount: '5.05', reason: 'the sale changed' } });
  expect(quiet0, '[+] a manager puts some of it back, and the machine never answers the refund', 201);
  truthy('[abuse] ...which is a timeout nobody has looked at, never a refund made', data(quiet0).state === 'TIMED_OUT' && data(quiet0).standing === 'UNDECIDED' && data(quiet0).kind === 'REFUND', data(quiet0));
  const heldByRefund = sale('12.00', newKey('press'), cashier, changing.orderId);
  expect(heldByRefund, '[abuse] the changed sale is not charged while the first card may still be all on it', 409, 'TERMINAL_UNSETTLED_APPROVAL');
  truthy('[+] ...and the refusal names both: the sale still on the card, and the refund nobody has accounted for',
    detailsOf(heldByRefund).includes(`attemptId=${changing.id};`) && detailsOf(heldByRefund).includes('onCard=10.10;state=APPROVED;standing=APPROVED_UNRECORDED;kind=SALE')
      && detailsOf(heldByRefund).includes(`state=TIMED_OUT;standing=UNDECIDED;kind=REFUND;refundOf=${changing.id}`), detailsOf(heldByRefund));
  truthy('[+] the machine says both hold it', holding().length === 2 && holding().some((a) => a.id === data(quiet0).id), holding());
  // One refund of a card at a time: the first may already have put money back, and a second asked
  // meanwhile is how a card is refunded twice when the first answers late.
  const second = call('POST', `${TERM}/${changing.id}/refunds`, { token: manager.token, idem: newKey('refund'), body: { amount: '1.00', reason: 'another item' } });
  expect(second, '[abuse] another refund of that card waits until the first is accounted for', 409, 'TERMINAL_REFUND_UNDECIDED');
  truthy('[+] ...and names the refund it waits for', detailsOf(second).includes(`attemptId=${data(quiet0).id};state=TIMED_OUT`), detailsOf(second));
  truthy('[abuse] ...and nothing more reached the machine',
    (data(call('GET', `${TERM}/by-order/${changing.orderId}`, { token: owner })) || []).filter((a) => a.kind === 'REFUND').length === 1, 'one refund');
  const QUIET = `${TERM}/${data(quiet0).id}/settle`;
  const noRefund = { outcome: 'NOT_TAKEN', reason: 'the machine shows no refund' };
  expect(call('POST', QUIET, { token: shop.rival.owner.token, idem: newKey('settle'), body: noRefund }),
    '[abuse] another business cannot say what our refund did', 404, 'TERMINAL_ATTEMPT_NOT_FOUND');
  expect(call('POST', QUIET, { token: elsewhereManager.token, idem: newKey('settle'), body: noRefund }),
    '[abuse] nor a manager held to another store', 403, 'STORE_ACCESS_DENIED');
  expect(call('POST', QUIET, { token: manager.token, idem: newKey('settle'), body: { outcome: 'NOT_TAKEN' } }),
    '[-] saying what a refund did needs a reason', 400, 'VALIDATION_FAILED');
  expect(call('POST', QUIET, { token: manager.token, idem: newKey('settle'), body: noRefund }),
    '[+] a manager at the store says the refund did not go through, with a reason', 200);
  truthy('[abuse] ...and the sale it would have reversed still holds the machine, all of it on the card',
    holding().length === 1 && holding()[0].id === changing.id && holding()[0].standing === 'APPROVED_UNRECORDED', holding());
  expect(sale('12.00', newKey('press'), cashier, changing.orderId), '[abuse] so the changed sale is still refused', 409, 'TERMINAL_UNSETTLED_APPROVAL');
  expect(call('POST', `${TERM}/${changing.id}/refunds`, { token: manager.token, idem: newKey('refund'), body: { amount: '10.10', reason: 'the sale changed' } }),
    '[+] all of it put back for real', 201);
  truthy('[+] ...frees the machine', holding().length === 0, holding());
  const changed = sale('12.00', newKey('press'), cashier, changing.orderId);
  expect(changed, '[+] and the changed sale is charged once', 201);
  // (Its order is not a real sale here, so it is put back rather than recorded, freeing the machine.)
  must(call('POST', `${TERM}/${data(changed).id}/refunds`, { token: manager.token, idem: newKey('refund'), body: { amount: '12.00', reason: 'suite tidy-up' } }), 201, 'put the changed sale back');
  truthy('[+] ...and once that is settled too, the machine is free', holding().length === 0, holding());

  // ── a card sale recorded on its order, then voided: back on the card, through the machine ───────
  const till = must(call('POST', '/api/order-svc/orders', { token: owner, idem: true, body: { storeId: store, channel: 'POS', fulfilmentType: 'INSTORE', items: [{ variantId: shop.variantId, qty: 1 }] } }), 201, 'till sale');
  const due = Number(till.total).toFixed(2);
  const took = data(sale(due, newKey('press'), cashier, till.id));
  truthy('[+] the card for a real sale is approved on the machine', took.state === 'APPROVED', took);
  const PAY = '/api/payment-svc/payments';
  const tender = (amount, extra = {}) => call('POST', PAY, { token: cashier, idem: newKey('pay'), body: { orderId: till.id, amount, method: 'CARD', storeId: store, terminalPaymentId: took.id, ...extra } });
  expect(tender((Number(due) - 0.01).toFixed(2)), '[-] a tender records exactly what the machine took', 409, 'TERMINAL_AMOUNT_MISMATCH');
  expect(call('POST', PAY, { token: cashier, idem: newKey('pay'), body: { orderId: newId(), amount: due, method: 'CARD', storeId: store, terminalPaymentId: took.id } }),
    '[-] ...on its own sale', 409, 'TERMINAL_ATTEMPT_OTHER_ORDER');
  expect(call('POST', PAY, { token: shop.rival.owner.token, idem: newKey('pay'), body: { orderId: till.id, amount: due, method: 'CARD', terminalPaymentId: took.id } }),
    '[abuse] another business cannot record our machine’s payment', 404, 'TERMINAL_ATTEMPT_NOT_FOUND');
  expect(call('POST', PAY, { token: shop.rival.owner.token, idem: newKey('pay'), body: { orderId: till.id, amount: due, method: 'CARD', storeId: store, terminalPaymentId: took.id } }),
    '[abuse] ...not even naming our store', 404, 'TERMINAL_ATTEMPT_NOT_FOUND');
  truthy('[abuse] ...and no tender was written for our sale', (data(call('GET', `${PAY}/by-order/${till.id}`, { token: owner })) || []).length === 0, 'none');
  const recorded = tender(due);
  expect(recorded, '[+] the till records the approval on its sale, naming it', 201);
  truthy('[+] ...which settles the machine for the next sale', holding().length === 0, holding());
  expect(tender(due), '[-] an approval is recorded once', 409, 'TERMINAL_ATTEMPT_ALREADY_RECORDED');
  expect(call('POST', `${PAY}/by-order/${till.id}/refunds`, { token: owner, idem: true, body: { paymentId: data(recorded).id, amount: '1.00', method: 'CARD', reason: 'price match' } }),
    '[abuse] a card a machine took is never refunded in the books alone', 409, 'PAYMENT_REFUND_VIA_TERMINAL');
  // A refund in the books is the store's where its tender was taken (cash for it leaves that
  // store's drawer): another business finds no tender, and a manager held to another store of
  // ours may not make it — before anything is counted, replayed or written.
  const BOOKS = `${PAY}/by-order/${till.id}/refunds`;
  const cashBackHere = { paymentId: data(recorded).id, amount: '1.00', method: 'CASH', reason: 'not my store' };
  expect(call('POST', BOOKS, { token: shop.rival.owner.token, idem: true, body: cashBackHere }), '[abuse] another business cannot refund our tender in its books', 404, 'PAYMENT_NOT_FOUND');
  expect(call('POST', BOOKS, { token: elsewhereManager.token, idem: true, body: cashBackHere }), '[abuse] nor a manager held to another store, in cash from this one', 403, 'STORE_ACCESS_DENIED');
  expect(call('POST', BOOKS, { token: elsewhereManager.token, idem: true, body: { ...cashBackHere, amount: '999999.00' } }), '[abuse] ...told nothing of what is left on it either', 403, 'STORE_ACCESS_DENIED');
  truthy('[abuse] ...and nothing was refunded', (data(call('GET', BOOKS, { token: owner })) || []).length === 0, 'none');

  expect(call('POST', `/api/order-svc/orders/${till.id}/void`, { token: owner, idem: true, body: { reason: 'rang up twice' } }), '[+] the card sale is voided', 200);
  let onCard = null;
  poll(60, () => {
    onCard = (data(call('GET', `${TERM}/by-order/${till.id}`, { token: owner })) || []).find((a) => a.kind === 'REFUND' && a.state === 'APPROVED') || null;
    return onCard;
  });
  truthy('[+] ...and what the card paid goes back on it, through the machine that took it', !!onCard && onCard.refundOf === took.id && Number(onCard.amount) === Number(due), onCard);
  let inBooks = null;
  poll(30, () => {
    inBooks = (data(call('GET', `${PAY}/by-order/${till.id}/refunds`, { token: owner })) || []).find((r) => r.method === 'CARD') || null;
    return inBooks;
  });
  truthy('[+] ...and only then in the books, for exactly what went back', !!inBooks && Number(inBooks.amount) === Number(due), inBooks);
  const owedNow = data(call('GET', `${TERM}/refund-dues?state=REFUNDED`, { token: manager.token })) || [];
  truthy('[+] the money owed back is shown put back', owedNow.some((d) => d.orderId === till.id && d.state === 'REFUNDED'), owedNow.map((d) => d.state));
  truthy('[abuse] and another business sees none of it',
    !(data(call('GET', `${TERM}/refund-dues?state=REFUNDED`, { token: shop.rival.owner.token })) || []).some((d) => d.orderId === till.id), 'rival');
  truthy('[abuse] ...not even naming our store',
    !(data(call('GET', `${TERM}/refund-dues?state=REFUNDED&storeId=${store}`, { token: shop.rival.owner.token })) || []).some((d) => d.orderId === till.id), 'rival');
  expect(call('GET', `${TERM}/refund-dues?storeId=${store}`, { token: elsewhereManager.token }), '[abuse] a manager held to another store does not read this store’s', 403, 'STORE_ACCESS_DENIED');
  expect(call('GET', `${TERM}/refund-dues`, { token: cashier }), '[abuse] a cashier does not read what is owed back to cards', 403);
  const voidedDue = owedNow.find((d) => d.orderId === till.id) || {};
  expect(call('POST', `${TERM}/refund-dues/${voidedDue.id}/retry`, { token: lead.token, idem: newKey('retry'), body: {} }),
    '[abuse] a manager whose role does not hold sales.refund asks no machine to put money back', 403, 'PERMISSION_DENIED');

  // ── an approval learnt of after its sale was given up: owed back, never recorded ─────────────────
  // The machine did not answer; the sale is cancelled; only then does a manager see it approved. What
  // the card paid goes straight back through the machine, and no tender is recorded on the sale.
  const lost = must(call('POST', '/api/order-svc/orders', { token: owner, idem: true, body: { storeId: store, channel: 'POS', fulfilmentType: 'INSTORE', items: [{ variantId: shop.variantId, qty: 1 }] } }), 201, 'till sale to give up');
  const quiet = data(sale('9.03', newKey('press'), cashier, lost.id));
  truthy('[+] the machine does not answer the card for the sale', quiet.state === 'TIMED_OUT' && quiet.standing === 'UNDECIDED', quiet);
  expect(call('POST', `/api/order-svc/orders/${lost.id}/cancel`, { token: owner, body: { reason: 'customer walked out' } }), '[+] the sale is cancelled while nobody knows what the card did', 200);
  let givenUp = null;
  poll(60, () => {
    givenUp = sale('9.00', newKey('press'), cashier, lost.id);
    return givenUp.status === 409 && JSON.stringify(body(givenUp)).includes('PAYMENT_ORDER_GIVEN_UP');
  });
  expect(givenUp, '[abuse] no new card is taken for a sale given up', 409, 'PAYMENT_ORDER_GIVEN_UP');
  expect(call('POST', PAY, { token: cashier, idem: newKey('pay'), body: { orderId: lost.id, amount: '9.03', method: 'CARD', storeId: store, terminalPaymentId: quiet.id } }),
    '[abuse] nor is a tender recorded on it, naming the machine’s payment', 409, 'PAYMENT_ORDER_GIVEN_UP');
  const seen = call('POST', `${TERM}/${quiet.id}/settle`, { token: manager.token, idem: newKey('settle'), body: { outcome: 'APPROVED', reason: 'the machine printed an approval slip' } });
  expect(seen, '[+] a manager then sees it approved', 200);
  truthy('[+] ...and what the card paid is owed back and put back at once, so it holds nothing', data(seen).standing === 'SETTLED', data(seen));
  const backOnCard = (data(call('GET', `${TERM}/by-order/${lost.id}`, { token: owner })) || []).find((a) => a.kind === 'REFUND' && a.refundOf === quiet.id);
  truthy('[+] ...through the machine that took it, linked to that payment', !!backOnCard && backOnCard.state === 'APPROVED' && Number(backOnCard.amount) === 9.03, backOnCard);
  const lostDue = (data(call('GET', `${TERM}/refund-dues?state=REFUNDED`, { token: manager.token })) || []).find((d) => d.orderId === lost.id);
  truthy('[+] ...shown as money owed back and put back, never recorded as paid', !!lostDue && lostDue.paymentId == null && lostDue.source === 'ORDER_EVENT', lostDue);
  expect(call('POST', PAY, { token: cashier, idem: newKey('pay'), body: { orderId: lost.id, amount: '9.03', method: 'CARD', storeId: store, terminalPaymentId: quiet.id } }),
    '[abuse] the till’s queued tender, replayed, is refused naming the payment', 409, 'PAYMENT_ORDER_GIVEN_UP');
  expect(call('POST', PAY, { token: cashier, idem: newKey('pay'), body: { orderId: lost.id, amount: '9.03', method: 'CARD', storeId: store } }),
    '[abuse] ...and not naming it', 409, 'PAYMENT_ORDER_GIVEN_UP');
  truthy('[abuse] ...so the cancelled sale has no tender', (data(call('GET', `${PAY}/by-order/${lost.id}`, { token: owner })) || []).length === 0, 'none');
  truthy('[+] and the machine is free for the next sale', holding().length === 0, holding());

  // ── what the amount may be ────────────────────────────────────────────────────────────────────
  // Refused before the machine is looked at, so a held machine says nothing different here.
  for (const [amount, why] of [['0.00', 'nothing'], ['-5.00', 'a negative'], ['1.005', 'a third decimal place']]) {
    expect(call('POST', TERM, { token: cashier, body: { terminalId: terminal.id, orderId: approved.orderId, amount, currency: shop.tenant.currency } }),
      `[-] ${why} is refused rather than rounded`, 400);
  }
  // And it is in the business's own currency: a sale in another is not one this business makes.
  const otherCurrency = shop.tenant.currency === 'EUR' ? 'USD' : 'EUR';
  const foreign = call('POST', TERM, { token: cashier, idem: newKey('press'), body: { terminalId: terminal.id, orderId: newId(), amount: '7.00', currency: otherCurrency } });
  expect(foreign, '[-] a card is taken in the business’s own currency, never another', 409, 'TERMINAL_CURRENCY_MISMATCH');
  truthy('[-] ...and the refusal names the business’s own', detailsOf(foreign).includes(`currency=${shop.tenant.currency}`), detailsOf(foreign));
  truthy('[abuse] ...and nothing reached the machine', holding().length === 0, holding());

  // Two real card sales on this machine, recorded on their orders, which will outlive it: what a
  // retired machine took still goes back when its sale is voided afterwards.
  const paidByCard = (label) => {
    const order = must(call('POST', '/api/order-svc/orders', { token: owner, idem: true, body: { storeId: store, channel: 'POS', fulfilmentType: 'INSTORE', items: [{ variantId: shop.variantId, qty: 1 }] } }), 201, `till sale ${label}`);
    const amount = Number(order.total).toFixed(2);
    const card = data(sale(amount, newKey('press'), cashier, order.id));
    must(call('POST', PAY, { token: cashier, idem: newKey('pay'), body: { orderId: order.id, amount, method: 'CARD', storeId: store, terminalPaymentId: card.id } }), 201, `record the card for ${label}`);
    return { order, amount, card };
  };
  const swapped = paidByCard('paid before the swap');
  const stranded = paidByCard('paid before the machines went');
  truthy('[+] two card sales are recorded on the machine, which is free again', swapped.card.state === 'APPROVED' && stranded.card.state === 'APPROVED' && holding().length === 0, holding());

  // ── retiring one ──────────────────────────────────────────────────────────────────────────────
  // Not while it holds a card nobody has accounted for: retiring it would strand that card — no new
  // sale on it to hold back, and no machine to put the money back through.
  const stuck = data(sale('9.03', newKey('press')));
  truthy('[+] a card the machine never answered', stuck.state === 'TIMED_OUT' && stuck.standing === 'UNDECIDED', stuck);
  const RETIRE = `${ADMIN}/${terminal.id}/retire`;
  const heldRetire = call('POST', RETIRE, { token: owner, body: { reason: 'screen cracked' } });
  expect(heldRetire, '[abuse] a machine holding a card nobody has accounted for is not retired', 409, 'TERMINAL_UNSETTLED_APPROVAL');
  truthy('[+] ...and the refusal names that card, as the guard on a new sale does',
    detailsOf(heldRetire).includes(`attemptId=${stuck.id}`) && detailsOf(heldRetire).includes('standing=UNDECIDED'), detailsOf(heldRetire));
  expect(call('POST', RETIRE, { token: shop.rival.owner.token, body: { reason: 'not ours' } }), '[abuse] another business cannot retire our machine', 404, 'TERMINAL_NOT_FOUND');
  expect(call('POST', RETIRE, { token: elsewhereManager.token, body: { reason: 'not my store' } }), '[abuse] nor a manager held to another store', 403, 'STORE_ACCESS_DENIED');
  truthy('[abuse] ...and it is still in service', (data(call('GET', ADMIN, { token: owner })) || []).some((t) => t.id === terminal.id && t.status === 'ACTIVE'), 'active');
  expect(call('POST', `${TERM}/${stuck.id}/settle`, { token: manager.token, idem: newKey('settle'), body: { outcome: 'NOT_TAKEN', reason: 'the machine shows no transaction' } }),
    '[+] a manager says what the machine shows', 200);
  expect(call('POST', RETIRE, { token: owner, body: { reason: 'screen cracked' } }), '[+] a machine is retired with a reason, once every card on it is settled', 200);
  expect(sale('7.00', newKey('press')), '[-] a retired machine takes no more cards', 409, 'TERMINAL_RETIRED');
  const all = data(call('GET', ADMIN, { token: owner })) || [];
  truthy('[+] and is kept rather than deleted, because payments point at it', all.some((t) => t.id === terminal.id && t.status === 'RETIRED'), all.map((t) => t.status));
  // Which frees the label for the machine that replaces it — what happens when a pinpad is swapped.
  const replaced = call('POST', ADMIN, { token: owner, body: { storeId: store, label: `Till ${tag}`, vendor: 'SIMULATED' } });
  expect(replaced, '[+] its name is free for the machine that replaces it', 201);
  const replacement = data(replaced);

  // ── a retired machine strands nothing ──────────────────────────────────────────────────────────
  // A refund is linked to the sale by the vendor's own reference, so it needs the vendor and not the
  // device: a card the retired machine took goes back through the one that replaced it at its store.
  const duesIn = (state, token) => data(call('GET', `${TERM}/refund-dues?state=${state}`, { token: token || manager.token })) || [];
  expect(call('POST', `/api/order-svc/orders/${swapped.order.id}/void`, { token: owner, idem: true, body: { reason: 'voided after the machine was swapped' } }),
    '[+] a card sale the retired machine took is voided', 200);
  let viaReplacement = null;
  poll(60, () => {
    viaReplacement = (data(call('GET', `${TERM}/by-order/${swapped.order.id}`, { token: owner })) || []).find((a) => a.kind === 'REFUND' && a.state === 'APPROVED') || null;
    return viaReplacement;
  });
  truthy('[+] ...and its card is put back through the machine that replaced it, linked to the sale it reverses',
    !!viaReplacement && viaReplacement.refundOf === swapped.card.id && viaReplacement.terminalId === replacement.id && Number(viaReplacement.amount) === Number(swapped.amount), viaReplacement);
  let swappedDue = null;
  poll(30, () => {
    swappedDue = duesIn('REFUNDED').find((d) => d.orderId === swapped.order.id) || null;
    return swappedDue;
  });
  truthy('[+] ...so nothing is owed for it any more', !!swappedDue && swappedDue.state === 'REFUNDED', swappedDue);

  // With no machine of that make left at the store, nothing can put a card back: what is owed waits
  // for a manager, who records how it was given back another way.
  expect(call('POST', `${ADMIN}/${replacement.id}/retire`, { token: owner, body: { reason: 'the store stops taking cards' } }),
    '[+] the last machine is retired while nothing is owed back to a card', 200);
  expect(call('POST', `/api/order-svc/orders/${stranded.order.id}/void`, { token: owner, idem: true, body: { reason: 'voided after the machines went' } }),
    '[+] another card sale the first machine took is voided', 200);
  let owedDue = null;
  poll(60, () => {
    owedDue = duesIn('NEEDS_ATTENTION').find((d) => d.orderId === stranded.order.id) || null;
    return owedDue;
  });
  truthy('[+] ...and what its card paid is owed back, waiting for a person: no machine can be asked',
    !!owedDue && owedDue.saleAttemptId === stranded.card.id && Number(owedDue.amount) === Number(stranded.amount) && /TERMINAL_RETIRED/.test(owedDue.attention || ''), owedDue);
  owedDue = owedDue || {};
  const DUE = `${TERM}/refund-dues/${owedDue.id}`;
  const booksOf = (orderId) => data(call('GET', `${PAY}/by-order/${orderId}/refunds`, { token: owner })) || [];
  truthy('[abuse] ...and nothing in the books says it went back', booksOf(stranded.order.id).length === 0, booksOf(stranded.order.id));
  const noMachine = call('POST', `${DUE}/retry`, { token: manager.token, idem: newKey('retry'), body: {} });
  expect(noMachine, '[-] asking again finds no machine of that make in service at the store', 409, 'TERMINAL_RETIRED');
  truthy('[-] ...and says which machine and make', detailsOf(noMachine).includes(`terminalId=${terminal.id}`) && detailsOf(noMachine).includes('vendor=SIMULATED'), detailsOf(noMachine));

  // A machine of that make registered now is the only one that could put it back, so it is not
  // retired while the money is owed.
  const spare = must(call('POST', ADMIN, { token: owner, body: { storeId: store, label: `Spare ${tag}`, vendor: 'SIMULATED' } }), 201, 'a spare machine');
  const SPARE_RETIRE = `${ADMIN}/${spare.id}/retire`;
  const owing = call('POST', SPARE_RETIRE, { token: owner, body: { reason: 'not needed after all' } });
  expect(owing, '[abuse] the last machine of its make at a store is not retired while a card is owed money back', 409, 'TERMINAL_REFUNDS_OWED');
  truthy('[+] ...and the refusal names what is owed and the sale it goes back to',
    detailsOf(owing).includes(`dueId=${owedDue.id}`) && detailsOf(owing).includes(`attemptId=${stranded.card.id}`) && detailsOf(owing).includes('state=NEEDS_ATTENTION'), detailsOf(owing));
  expect(call('POST', SPARE_RETIRE, { token: shop.rival.owner.token, body: { reason: 'not ours' } }), '[abuse] another business cannot retire it either way', 404, 'TERMINAL_NOT_FOUND');
  truthy('[abuse] ...and it is still in service', (data(call('GET', ADMIN, { token: owner })) || []).some((t) => t.id === spare.id && t.status === 'ACTIVE'), 'active');

  const AW = `${DUE}/another-way`;
  const cashBack = { method: 'CASH', reason: 'the card machine is gone; given back from the drawer' };
  expect(call('POST', AW, { token: shop.rival.owner.token, idem: newKey('way'), body: cashBack }), '[abuse] another business cannot close what we owe a card', 404, 'CARD_REFUND_DUE_NOT_FOUND');
  expect(call('POST', AW, { token: elsewhereManager.token, idem: newKey('way'), body: cashBack }), '[abuse] nor a manager held to another store', 403, 'STORE_ACCESS_DENIED');
  expect(call('POST', AW, { token: lead.token, idem: newKey('way'), body: cashBack }), '[abuse] nor a manager whose role does not hold sales.refund', 403, 'PERMISSION_DENIED');
  expect(call('POST', AW, { token: cashier, idem: newKey('way'), body: cashBack }), '[abuse] nor a cashier', 403);
  expect(call('POST', AW, { token: manager.token, body: cashBack }), '[-] giving it back another way needs an Idempotency-Key', 400, 'IDEMPOTENCY_KEY_REQUIRED');
  expect(call('POST', AW, { token: manager.token, idem: newKey('way'), body: { method: 'CASH' } }), '[-] and a reason', 400, 'VALIDATION_FAILED');
  expect(call('POST', AW, { token: manager.token, idem: newKey('way'), body: { method: 'GIFT_CARD', reason: 'a gift card instead' } }),
    '[-] and a way money leaves the business, never value issued', 400, 'CARD_REFUND_METHOD_INVALID');
  expect(call('POST', AW, { token: manager.token, idem: newKey('way'), body: { method: 'CARD', reason: 'the acquirer refunded it' } }),
    '[-] the acquirer’s own refund needs its reference', 400, 'CARD_REFUND_REFERENCE_REQUIRED');
  truthy('[abuse] ...and nothing moved: still owed, nothing in the books',
    (data(call('GET', DUE, { token: manager.token })) || {}).state === 'NEEDS_ATTENTION' && booksOf(stranded.order.id).length === 0, booksOf(stranded.order.id));
  const wayKey = newKey('way');
  const given = call('POST', AW, { token: manager.token, idem: wayKey, body: cashBack });
  expect(given, '[+] a manager at the store records that it was given back in cash', 200);
  const way = (data(given) || {}).anotherWay || {};
  truthy('[+] ...kept with how, who, when and why, and nothing is owed after',
    (data(given) || {}).state === 'REFUNDED_ANOTHER_WAY' && way.method === 'CASH' && way.reason === cashBack.reason && !!way.closedBy && !!way.closedAt, data(given));
  truthy('[+] ...and the books give it back with it, in cash, for exactly what the card paid',
    booksOf(stranded.order.id).length === 1 && booksOf(stranded.order.id)[0].method === 'CASH' && Number(booksOf(stranded.order.id)[0].amount) === Number(stranded.amount), booksOf(stranded.order.id));
  const givenAgain = call('POST', AW, { token: manager.token, idem: wayKey, body: cashBack });
  expect(givenAgain, '[+] a replay under the same key answers with the first', 200);
  expect(call('POST', AW, { token: manager.token, idem: newKey('way'), body: cashBack }), '[-] it is given back once', 409, 'CARD_REFUND_DUE_SETTLED');
  expect(call('POST', `${DUE}/retry`, { token: manager.token, idem: newKey('retry'), body: {} }), '[-] and no machine is asked for it after', 409, 'CARD_REFUND_DUE_SETTLED');
  truthy('[abuse] ...so the books hold one refund, and no machine was asked',
    booksOf(stranded.order.id).length === 1 && !(data(call('GET', `${TERM}/by-order/${stranded.order.id}`, { token: owner })) || []).some((a) => a.kind === 'REFUND'), booksOf(stranded.order.id));
  truthy('[+] it is listed as given back another way', duesIn('REFUNDED_ANOTHER_WAY').some((d) => d.id === owedDue.id), 'listed');
  truthy('[abuse] ...and another business sees none of it', !duesIn('REFUNDED_ANOTHER_WAY', shop.rival.owner.token).some((d) => d.id === owedDue.id), 'rival');
  expect(call('POST', SPARE_RETIRE, { token: owner, body: { reason: 'not needed after all' } }), '[+] with nothing owed, the last machine is retired', 200);

  // ── whose machines these are ───────────────────────────────────────────────────────────────────
  expect(call('GET', ADMIN, { token: cashier }), '[abuse] a cashier does not read the register of machines', 403);
  expect(call('GET', ADMIN, { token: shop.rival.owner.token }), '[+] another business reads its own register', 200);
  truthy('[abuse] which holds none of this one’s machines', ((data(call('GET', ADMIN, { token: shop.rival.owner.token })) || []).length === 0), 'empty');
  expect(call('POST', TERM, { token: shop.rival.owner.token, body: { terminalId: terminal.id, orderId: approved.orderId, amount: '5.00', currency: shop.tenant.currency } }),
    '[abuse] and it cannot take a card on this one’s machine by naming its id', 404, 'TERMINAL_NOT_FOUND');
  expect(call('GET', `${TERM}/by-order/${approved.orderId}`), '[-] nor does anybody without a token', 401);

  completed.add(1);
}
