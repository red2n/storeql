// India's DPDP Act as a person meets it (13.12), through the gateway: the notice in their language,
// consent by purpose withdrawn in one step, who takes their grievances, the requests they make and
// the business's answer within its published period, a child's guardian, and a breach told to
// customers with the business's own duties on the platform's notice. An Indian business, whose
// Act binds from 13 May 2027 and whose mechanics work today; a British one beside it.
//
//   k6/run.sh dpdp-flow
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import {
  ALL_CHECKS_PASS,
  BASE,
  call,
  data,
  expect,
  must,
  platformAdmin,
  register,
  sellingTenant,
  truthy,
} from './lib/storeql.js';

const completed = new Counter('flow_completed');
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
  setupTimeout: '5m',
};

const C = '/api/customer-svc';
const PRIVACY = `${C}/customers/privacy`;
const MINE = `${C}/customers/me/privacy`;
const NOTICES = '/api/tenant-svc/admin/tenant/security-notices';
const INCIDENTS = '/api/tenant-svc/platform/security-incidents';

export function setup() {
  const admin = platformAdmin();
  const india = sellingTenant('dpdp', { country: 'IN', currency: 'INR' });
  const shopper = register('dpdp-shopper');
  must(call('POST', `${C}/customers/me`, { token: shopper.token, storefront: india.tenant.tenantId, body: {} }), 200, 'claim record');
  const kid = register('dpdp-kid');
  must(call('POST', `${C}/customers/me`, { token: kid.token, storefront: india.tenant.tenantId, body: {} }), 200, 'claim kid record');
  return { admin: admin.token, india, shopper, kid };
}

export default function ({ admin, india, shopper, kid }) {
  const t = india.tenant;
  const owner = t.owner.token;
  const cashier = india.cashier.token;
  const rival = india.rival.owner.token;
  const sf = t.tenantId;
  const me = (token, path, method = 'GET', body) => call(method, `${MINE}${path}`, { token, storefront: sf, body });
  const pub = (query) => call('GET', `${PRIVACY}/notice${query}`, { storefront: sf });
  const staff = (token, path, method = 'GET', body) => call(method, `${PRIVACY}${path}`, { token, body });

  // ── the notice, before anyone signs up ───────────────────────────────────────────────────────────
  const before = pub('?language=hi');
  expect(before, '[+] the notice is read before signing up, without a token', 200);
  const b = data(before);
  truthy('[+] ...none published yet: English and the Eighth Schedule\'s twenty-two offered, four purposes named', b.notice == null && b.languages.length === 23 && b.purposes.length === 4 && b.requested === 'hi', b);
  truthy('[+] ...and the Act\'s date is told: it binds this business from 13 May 2027', b.dpdp === false && b.dpdpFrom === '2027-05-13', [b.dpdp, b.dpdpFrom]);
  expect(pub('?language=fr'), '[-] a language not offered is refused', 400, 'PRIVACY_LANGUAGE_UNKNOWN');

  // ── the grievance contact and the period ─────────────────────────────────────────────────────────
  const settings = staff(owner, '/settings', 'PUT', { grievanceName: 'Grievance Officer', grievanceEmail: 'privacy@dpdp.k6.test', responseDays: 15 });
  expect(settings, '[+] the owner names who takes grievances and the days the business answers in', 200);
  truthy('[+] ...read back', data(settings).hasGrievanceContact === true && data(settings).responseDays === 15, data(settings));
  expect(staff(owner, '/settings', 'PUT', { responseDays: 91 }), '[-] longer than ninety days is refused (r.14(3))', 400, 'PRIVACY_RESPONSE_DAYS_INVALID');
  expect(staff(owner, '/settings', 'PUT', { grievanceEmail: 'not an address' }), '[-] an email that is not one is refused', 400, 'PRIVACY_GRIEVANCE_CONTACT_INVALID');
  expect(staff(cashier, '/settings', 'PUT', { responseDays: 5 }), '[-] a cashier does not set it', 403, 'FORBIDDEN');

  // ── the notice per language ──────────────────────────────────────────────────────────────────────
  const en = staff(owner, '/notices', 'POST', { language: 'en', title: 'How we use your data', body: 'We keep your orders and, if you agree, tell you about offers.' });
  expect(en, '[+] the owner publishes the notice in English', 201);
  truthy('[+] ...version 1', data(en).version === 1, data(en));
  const hi = staff(owner, '/notices', 'POST', { language: 'hi', title: 'हम आपके डेटा का उपयोग कैसे करते हैं', body: 'हम आपके ऑर्डर रखते हैं।' });
  expect(hi, '[+] ...and in Hindi', 201);
  truthy('[+] ...named as Hindi', data(hi).languageName === 'Hindi', data(hi));
  const en2 = staff(owner, '/notices', 'POST', { language: 'en', title: 'How we use your data', body: 'Second wording.' });
  truthy('[+] publishing again is a new version, never a rewrite', en2.status === 201 && data(en2).version === 2, data(en2));
  expect(staff(cashier, '/notices', 'POST', { language: 'en', title: 't', body: 'b' }), '[-] a cashier does not publish', 403, 'FORBIDDEN');
  expect(staff(owner, '/notices', 'POST', { language: 'fr', title: 't', body: 'b' }), '[-] nor anyone in a language not offered', 400, 'PRIVACY_LANGUAGE_UNKNOWN');
  expect(staff(owner, '/notices', 'POST', { language: 'en', title: '', body: 'b' }), '[-] nor without a title', 400, 'PRIVACY_NOTICE_TEXT_INVALID');
  const served = data(pub('?language=hi'));
  truthy('[+] a Hindi reader gets the Hindi notice, with the grievance contact beside it', served.served === 'hi' && served.notice.title.includes('डेटा') && served.settings.grievanceEmail === 'privacy@dpdp.k6.test', served);
  const tamil = data(pub('?language=ta'));
  truthy('[+] a Tamil reader, with none published, gets English and is told', tamil.served === 'en' && tamil.requested === 'ta' && tamil.notice.version === 2, tamil);
  truthy('[+] ...and sees which languages are published', tamil.languages.filter((l) => l.published).map((l) => l.code).join() === 'en,hi', tamil.languages.filter((l) => l.published));

  // ── consent by purpose ───────────────────────────────────────────────────────────────────────────
  const mine = me(shopper.token, '');
  expect(mine, '[+] the shopper reads their consents', 200);
  const m0 = data(mine);
  truthy('[+] ...four purposes, every one off, an adult who may be tracked if they agree', m0.consents.length === 4 && m0.consents.every((c) => !c.granted) && m0.child === false && m0.canTrack === true, m0);
  const chosen = me(shopper.token, '/consents', 'PUT', { choices: [{ purpose: 'MARKETING', granted: true }, { purpose: 'LOYALTY', granted: true }], language: 'hi' });
  expect(chosen, '[+] the shopper agrees to two purposes against the Hindi notice', 200);
  const granted = (v, p) => (data(v).consents.find((c) => c.purpose === p) || {}).granted;
  const marketing = data(chosen).consents.find((c) => c.purpose === 'MARKETING');
  truthy('[+] ...marketing and loyalty on, analytics off, the notice version recorded', granted(chosen, 'MARKETING') === true && granted(chosen, 'LOYALTY') === true && granted(chosen, 'ANALYTICS') === false && marketing.noticeLanguage === 'hi' && marketing.noticeVersion === 1, data(chosen));
  const withdrawn = me(shopper.token, '/consents', 'DELETE');
  expect(withdrawn, '[+] every consent withdrawn in one call: as easy as giving it (s.6(4))', 200);
  truthy('[+] ...all off', data(withdrawn).consents.every((c) => !c.granted), data(withdrawn));
  expect(me(shopper.token, '/consents', 'PUT', { choices: [{ purpose: 'SURVEILLANCE', granted: true }] }), '[-] a purpose that does not exist is refused', 400, 'PRIVACY_PURPOSE_UNKNOWN');
  expect(me(shopper.token, '/consents', 'PUT', { choices: [] }), '[-] no purpose at all is refused', 400, 'VALIDATION_FAILED');
  const shopperId = data(call('GET', `${C}/customers/me`, { token: shopper.token, storefront: sf })).id;
  const log = call('GET', `${C}/customers/${shopperId}/privacy/log`, { token: owner });
  expect(log, '[+] the owner reads the evidence: every grant and withdrawal', 200);
  truthy('[+] ...four entries, the withdrawals last', data(log).length === 4 && data(log)[0].source === 'WITHDRAW_ALL' && data(log)[3].source === 'PREFERENCE_CENTRE', data(log));
  expect(call('GET', `${C}/customers/${shopperId}/privacy/log`, { token: cashier }), '[-] a cashier does not read it', 403, 'PERMISSION_DENIED');
  expect(call('GET', `${C}/customers/${shopperId}/privacy/log`, { token: rival }), '[abuse] nor another business', 404, 'CUSTOMER_NOT_FOUND');

  // ── a child ──────────────────────────────────────────────────────────────────────────────────────
  const dob = new Date(Date.now() - 14 * 365.25 * 86400 * 1000).toISOString().slice(0, 10);
  expect(call('PUT', `${C}/customers/me`, { token: kid.token, storefront: sf, body: { firstName: 'Kavi', lastName: 'R', dob } }), '[+] a shopper gives a date of birth fourteen years ago', 200);
  const kidView = data(me(kid.token, ''));
  truthy('[+] ...and is a child who may not be tracked', kidView.child === true && kidView.canTrack === false, kidView);
  expect(me(kid.token, '/consents', 'PUT', { choices: [{ purpose: 'MARKETING', granted: true }] }), '[-] marketing to a child is refused without a guardian (s.9)', 409, 'PRIVACY_GUARDIAN_CONSENT_REQUIRED');
  expect(me(kid.token, '/consents', 'PUT', { choices: [{ purpose: 'LOYALTY', granted: true }] }), '[+] loyalty tracks nobody: the child may choose it', 200);
  const kidId = data(call('GET', `${C}/customers/me`, { token: kid.token, storefront: sf })).id;
  const guardian = (token, body) => call('POST', `${C}/customers/${kidId}/privacy/guardian`, { token, body });
  expect(guardian(owner, { guardianName: 'R. Kumar', verification: 'DOCUMENT_SEEN', reference: 'passport 12345678' }), '[-] a reference that is a document number is refused', 400, 'PRIVACY_REFERENCE_IS_A_NUMBER');
  expect(guardian(owner, { guardianName: 'R. Kumar', verification: 'HEARSAY' }), '[-] a verification the Rules do not know is refused (r.10)', 400, 'PRIVACY_VERIFICATION_UNKNOWN');
  expect(guardian(cashier, { guardianName: 'R. Kumar', verification: 'DOCUMENT_SEEN' }), '[-] a cashier does not record it', 403, 'FORBIDDEN');
  const g = guardian(owner, { guardianName: 'R. Kumar', verification: 'DOCUMENT_SEEN', reference: 'passport seen at the counter' });
  expect(g, '[+] a manager records the parent\'s consent and how the parent was verified', 200);
  truthy('[+] ...standing', data(g).standing === true, data(g));
  expect(me(kid.token, '/consents', 'PUT', { choices: [{ purpose: 'MARKETING', granted: true }] }), '[+] now marketing may be chosen', 200);
  const gone = call('DELETE', `${C}/customers/${kidId}/privacy/guardian`, { token: owner });
  expect(gone, '[+] the guardian\'s consent is withdrawn', 200);
  const after = data(me(kid.token, ''));
  truthy('[+] ...and marketing fell with it while loyalty stands', after.consents.find((c) => c.purpose === 'MARKETING').granted === false && after.consents.find((c) => c.purpose === 'LOYALTY').granted === true && after.canTrack === false, after);
  expect(call('DELETE', `${C}/customers/${kidId}/privacy/guardian`, { token: owner }), '[-] withdrawn twice is not found', 404, 'PRIVACY_GUARDIAN_CONSENT_NOT_FOUND');
  // The adult's own date of birth is what makes them an adult; a person who gave none is not refused a parent.
  expect(call('PUT', `${C}/customers/me`, { token: shopper.token, storefront: sf, body: { firstName: 'Asha', lastName: 'R', dob: '1990-06-01' } }), '[+] the adult shopper gives a date of birth', 200);
  expect(call('POST', `${C}/customers/${shopperId}/privacy/guardian`, { token: owner, body: { guardianName: 'Someone', verification: 'DETAILS_HELD' } }), '[-] an adult\'s consent is theirs alone', 409, 'PRIVACY_NOT_A_CHILD');

  // ── requests ─────────────────────────────────────────────────────────────────────────────────────
  const today = new Date().toISOString().slice(0, 10);
  const due = new Date(Date.now() + 15 * 86400 * 1000).toISOString().slice(0, 10);
  const grievance = me(shopper.token, '/requests', 'POST', { kind: 'GRIEVANCE', detail: 'You emailed after I withdrew.' });
  expect(grievance, '[+] the shopper raises a grievance', 201);
  truthy(`[+] ...open, due within the published fifteen days (${today} + 15)`, data(grievance).status === 'OPEN' && data(grievance).dueOn === due && data(grievance).overdue === false, data(grievance));
  expect(me(shopper.token, '/requests', 'POST', { kind: 'NOMINATION' }), '[-] a nomination names somebody', 400, 'PRIVACY_NOMINEE_REQUIRED');
  expect(me(shopper.token, '/requests', 'POST', { kind: 'REFUND' }), '[-] a request the Act does not know is refused', 400, 'PRIVACY_REQUEST_KIND_UNKNOWN');
  const nomination = me(shopper.token, '/requests', 'POST', { kind: 'NOMINATION', nomineeName: 'Arun', nomineeContact: 'arun@dpdp.k6.test' });
  expect(nomination, '[+] a nomination with a name is opened (s.14)', 201);
  truthy('[+] the shopper sees both', data(me(shopper.token, '/requests')).length === 2, data(me(shopper.token, '/requests')));
  const queue = staff(cashier, '/requests?status=OPEN');
  expect(queue, '[+] the queue is read by any staff member', 200);
  truthy('[+] ...and holds the grievance', data(queue).some((r) => r.id === data(grievance).id), data(queue).map((r) => r.id));
  expect(staff(owner, '/requests?status=LOST'), '[-] a status that does not exist is refused', 400, 'PRIVACY_REQUEST_STATUS_UNKNOWN');
  const id = data(grievance).id;
  expect(staff(cashier, `/requests/${id}/resolve`, 'POST', { status: 'RESOLVED', resolution: 'Stopped.' }), '[-] a cashier does not answer it', 403, 'PERMISSION_DENIED');
  const resolved = staff(owner, `/requests/${id}/resolve`, 'POST', { status: 'RESOLVED', resolution: 'Stopped, and the log corrected.' });
  expect(resolved, '[+] the owner answers it', 200);
  truthy('[+] ...settled, with when', data(resolved).status === 'RESOLVED' && Boolean(data(resolved).resolvedAt), data(resolved));
  expect(staff(owner, `/requests/${id}/resolve`, 'POST', { status: 'REFUSED', resolution: 'again' }), '[-] answered twice is refused', 409, 'PRIVACY_REQUEST_SETTLED');
  expect(staff(rival, `/requests/${data(nomination).id}/resolve`, 'POST', { status: 'REFUSED', resolution: 'x' }), '[abuse] another business cannot answer it', 404, 'PRIVACY_REQUEST_NOT_FOUND');
  truthy('[+] the shopper reads the answer', (data(me(shopper.token, '/requests')).find((r) => r.id === id) || {}).resolution === 'Stopped, and the log corrected.', data(me(shopper.token, '/requests')));
  for (let i = 0; i < 19; i++) me(shopper.token, '/requests', 'POST', { kind: 'ACCESS' });
  expect(me(shopper.token, '/requests', 'POST', { kind: 'ACCESS' }), '[abuse] a shopper cannot flood the queue: twenty open at most', 409, 'PRIVACY_REQUESTS_OPEN_LIMIT');

  // ── a breach: the platform's notice, the business's own duties, the customers told ───────────────
  const incident = must(call('POST', INCIDENTS, { token: admin, body: { kind: 'PERSONAL_DATA_BREACH', title: 'k6 dpdp breach', summary: 'Found by the k6 dpdp-flow suite', awareAt: new Date(Date.now() - 3600 * 1000).toISOString(), tenantIds: [sf] } }), 201, 'open breach');
  must(call('POST', `${INCIDENTS}/${incident.id}/notices`, { token: admin, body: { message: 'Names and emails were read.' } }), 200, 'issue notices');
  const notices = call('GET', NOTICES, { token: owner });
  expect(notices, '[+] the business reads the platform\'s notice', 200);
  const notice = (data(notices) || []).find((n) => n.incidentId === incident.id);
  truthy('[+] ...with its own duties under the DPDP Act, three of them, binding from 13 May 2027', notice && notice.regime === 'DPDP' && notice.binding === false && notice.bindsFrom === '2027-05-13' && notice.duties.length === 3, notice);
  const duty = (n, d) => (n.duties || []).find((x) => x.duty === d) || {};
  truthy('[+] ...the report to the Board due 72 hours from the notice, the rest without delay', duty(notice, 'BOARD_REPORTED').state === 'DUE' && Date.parse(duty(notice, 'BOARD_REPORTED').dueAt) === Date.parse(notice.issuedAt) + 72 * 3600 * 1000 && duty(notice, 'PRINCIPALS_TOLD').state === 'WAITING', notice.duties);
  const report = (body, token = owner) => call('POST', `${NOTICES}/${notice.id}/reports`, { token, body });
  const told = report({ duty: 'BOARD_INTIMATED', reference: 'DPB-K6-0042', note: 'By the portal.' });
  expect(told, '[+] the owner records the Board told, with its reference', 201);
  truthy('[+] ...done', duty(data(told), 'BOARD_INTIMATED').state === 'DONE' && duty(data(told), 'BOARD_INTIMATED').reference === 'DPB-K6-0042', data(told));
  expect(report({ duty: 'BOARD_INTIMATED' }), '[-] recorded twice is refused', 409, 'SECURITY_NOTICE_DUTY_DONE');
  expect(report({ duty: 'AUTHORITY_NOTIFIED' }), '[-] a GDPR duty is not an Indian business\'s', 400, 'SECURITY_NOTICE_DUTY_UNKNOWN');
  expect(report({ duty: 'PRINCIPALS_TOLD' }, cashier), '[-] a cashier does not record one', 403, 'FORBIDDEN');
  expect(report({ duty: 'SUBJECTS_TOLD' }, rival), '[abuse] another business has no such notice', 404, 'SECURITY_NOTICE_NOT_FOUND');
  const intimation = staff(owner, '/breach-intimations', 'POST', { noticeId: notice.id, subject: 'About your data', body: 'On 15 September names and emails were read. We closed the hole. Change any password you reused. Write to privacy@dpdp.k6.test.' });
  expect(intimation, '[+] the owner tells every reachable customer, in plain words (r.7(1))', 201);
  truthy('[+] ...both shoppers reached, nobody missed', data(intimation).recipients >= 2 && data(intimation).failures === 0, data(intimation));
  const principals = report({ duty: 'PRINCIPALS_TOLD', reference: data(intimation).id });
  expect(principals, '[+] ...and records the people told, the intimation as its reference', 201);
  truthy('[+] ...two of three duties done, the report to the Board still due', duty(data(principals), 'PRINCIPALS_TOLD').state === 'DONE' && duty(data(principals), 'BOARD_REPORTED').state === 'DUE', data(principals).duties);
  truthy('[+] the intimation is kept', (data(staff(owner, '/breach-intimations')) || []).some((i) => i.id === data(intimation).id), 'intimations');
  expect(staff(owner, '/breach-intimations', 'POST', { subject: '', body: 'x' }), '[-] an intimation needs words', 400, 'PRIVACY_INTIMATION_TEXT_INVALID');
  expect(staff(cashier, '/breach-intimations', 'POST', { subject: 's', body: 'b' }), '[-] a cashier does not send one', 403, 'FORBIDDEN');
  truthy('[abuse] another business sees no notice of it', !(data(call('GET', NOTICES, { token: rival })) || []).some((n) => n.incidentId === incident.id), 'rival notices');
  expect(call('GET', `${C}/customers/${shopperId}/privacy`, { token: shopper.token, storefront: sf }), '[abuse] a shopper cannot read a record by id: that is the staff route', 403, 'FORBIDDEN');
  truthy('[abuse] another business has no notice published', data(call('GET', `${PRIVACY}/notice`, { storefront: india.rival.tenantId })).notice == null, 'rival notice');
  expect(http.get(`${BASE}${MINE}`, { headers: { 'X-Storefront-Tenant': sf } }), '[abuse] my consents need a signed-in shopper', 401, 'UNAUTHORIZED');

  completed.add(1);
}
