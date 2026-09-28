// The business's messages in its own words, and in its reader's language (13.x), through the gateway:
// the owner reads what the platform sends, drafts a Polish order confirmation, previews it with
// sample values and saves it as versions; a shopper who reads Polish orders and is written to in it,
// signed the way the shop chose; one who has not said gets the platform's English; and retiring the
// words sends them back to the platform's. Beside that, every refusal: a value the message does not
// have, a broken section, a recall notice that leaves out what the law says it must, a push too long
// for a phone, a language that is not one, the wrong message or form, staff below manager, a shopper,
// the rival shop — and five saves racing for the same next version.
//
//   k6/run.sh message-templates-flow
import http from 'k6/http';
import {
  ALL_CHECKS_PASS,
  BASE,
  call,
  data,
  expect,
  poll,
  register,
  sellingTenant,
  truthy,
} from './lib/storeql.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS, setupTimeout: '4m' };

const ADMIN = '/api/notification-svc/admin/notifications';
const ONE = (type, form, language) => `${ADMIN}/templates/${type}/${form}/${language}`;
const PL = ONE('ORDER_CONFIRMED', 'EMAIL', 'pl');
const POLISH = {
  subject: 'Zamówienie {{order}} potwierdzone',
  body: 'Dziękujemy za zamówienie!\n\nZamówienie {{order}}\nRazem: {{total}}\n\n— {{shop}}',
};

export function setup() {
  const s = sellingTenant('templates', { price: '12.50' });
  return { ...s, polish: register('templates-polish'), plain: register('templates-plain'), other: register('templates-other') };
}

export default function ({ tenant, rival, store, variantId, storekeeper, cashier, polish, plain, other }) {
  const t = tenant.owner.token;
  const shop = { storefront: tenant.tenantId };
  const list = (res) => { const d = data(res); return Array.isArray(d) ? d : []; };
  const problem = (res) => { try { return JSON.parse(res.body) || {}; } catch (_) { return {}; } };

  // ── what the platform sends, and what may be said in it ─────────────────────
  const catalogue = call('GET', `${ADMIN}/templates`, { token: t });
  expect(catalogue, '[+] the owner reads the messages the business sends', 200);
  const order = list(catalogue).find((m) => m.type === 'ORDER_CONFIRMED') || {};
  const recall = list(catalogue).find((m) => m.type === 'RECALL_NOTICE') || {};
  truthy('[+] eighteen messages, to customers, staff and suppliers — the platform\'s two billing notices and its two trial notices among them', list(catalogue).length === 18 && ['CUSTOMER', 'STAFF', 'SUPPLIER'].every((a) => list(catalogue).some((m) => m.audience === a)) && ['TRIAL_ENDING', 'TRIAL_ENDED'].every((t) => list(catalogue).some((m) => m.type === t)), list(catalogue).map((m) => m.type));
  truthy('[+] an order confirmation goes by email and push, and names its values', (order.forms || []).map((f) => f.form).join() === 'EMAIL,PUSH' && ['order', 'total', 'shop'].every((n) => (order.variables || []).some((v) => v.name === n)), order);
  truthy("[+] a recall notice by email keeps the law's six parts", ((recall.forms || []).find((f) => f.form === 'EMAIL') || {}).required.length === 6, recall.forms);
  truthy('[+] nothing written yet', list(catalogue).every((m) => m.forms.every((f) => f.written.length === 0)));

  const start = call('GET', PL, { token: t });
  expect(start, '[+] a Polish confirmation, not written yet', 200);
  truthy("[+] ...starts from the platform's words", data(start).source === 'DEFAULT' && data(start).version == null && data(start).body.startsWith('Thanks for your order!'), data(start));

  // ── a draft, written out with sample values ──────────────────────────────────
  const preview = call('POST', `${PL}/preview`, { token: t, body: POLISH });
  expect(preview, '[+] the owner previews a Polish draft', 200);
  truthy('[+] ...written out in Polish figures, with nothing left unfilled', /Razem: 24,60[\s\u00a0\u202f]GBP/.test(data(preview).body) && !data(preview).body.includes('{{') && data(preview).problems.length === 0, data(preview));
  const unknown = call('POST', `${PL}/preview`, { token: t, body: { subject: 'Hasło', body: 'Twoje hasło: {{password}} do {{order}}' } });
  truthy('[+] a preview names the value the message does not have, rather than refusing', unknown.status === 200 && data(unknown).problems.some((p) => p.code === 'TEMPLATE_VARIABLE_UNKNOWN' && p.names.includes('password')), data(unknown));
  truthy('[+] ...and saves nothing', list(call('GET', `${ADMIN}/templates`, { token: t })).every((m) => m.forms.every((f) => f.written.length === 0)));

  // ── the refusals ─────────────────────────────────────────────────────────────
  expect(call('PUT', PL, { token: t, body: { subject: 'Hasło', body: 'Twoje hasło: {{password}} do {{order}}' } }), '[-] a value the message does not have', 400, 'TEMPLATE_VARIABLE_UNKNOWN');
  truthy('[-] ...named in the refusal', JSON.stringify(problem(call('PUT', PL, { token: t, body: { subject: 'x', body: '{{password}} {{order}}' } })).details || '').includes('password'));
  expect(call('PUT', PL, { token: t, body: { subject: 'x', body: '{{#order}}Zamówienie {{order}}' } }), '[-] a section never closed', 400, 'TEMPLATE_INVALID');
  expect(call('PUT', PL, { token: t, body: { subject: 'x', body: 'Dziękujemy!' } }), '[-] a confirmation that never says which order', 422, 'TEMPLATE_PART_REQUIRED');
  expect(call('PUT', PL, { token: t, body: { subject: '', body: 'Zamówienie {{order}}' } }), '[-] an email with no subject', 400, 'TEMPLATE_SUBJECT_REQUIRED');
  expect(call('PUT', ONE('RECALL_NOTICE', 'EMAIL', 'en'), { token: t, body: { subject: 'A small update', body: 'We are checking {{product}} as a precaution.\n\n— {{shop}}' } }), '[-] a recall notice that leaves out what to do, the remedy and whom to call', 422, 'TEMPLATE_PART_REQUIRED');
  expect(call('PUT', ONE('ORDER_CONFIRMED', 'PUSH', 'pl'), { token: t, body: { subject: 'x', body: `Zamówienie {{order}} ${'x'.repeat(240)}` } }), '[-] a push longer than a phone shows', 400, 'TEMPLATE_TOO_LONG');
  expect(call('PUT', ONE('ORDER_CONFIRMED', 'EMAIL', 'polish'), { token: t, body: POLISH }), '[-] a language that is not an ISO 639 code', 400, 'TEMPLATE_LANGUAGE_INVALID');
  expect(call('PUT', ONE('WINNING_TICKET', 'EMAIL', 'pl'), { token: t, body: POLISH }), '[-] a message the platform does not send', 404, 'MESSAGE_UNKNOWN');
  expect(call('PUT', ONE('ORDER_CONFIRMED', 'SMS', 'pl'), { token: t, body: POLISH }), '[-] a form the message is not sent in', 404, 'MESSAGE_FORM_UNKNOWN');
  expect(call('PUT', PL, { token: t, body: { subject: POLISH.subject, body: 'x'.repeat(40001) } }), '[-] a body past forty thousand characters', 400);
  expect(call('PUT', PL, { token: cashier.token, body: POLISH }), '[-] a cashier cannot write the words', 403);
  expect(call('GET', `${ADMIN}/templates`, { token: storekeeper.token }), '[-] nor can a storekeeper read them', 403);
  expect(call('GET', `${ADMIN}/template-settings`, { token: polish.token, ...shop }), '[-] nor a shopper', [401, 403]);
  expect(call('GET', `${ADMIN}/templates`, {}), '[-] nor anyone without a token', 401);
  truthy('[-] none of that saved anything', list(call('GET', `${ADMIN}/templates`, { token: t })).every((m) => m.forms.every((f) => f.written.length === 0)));

  // ── saved, as versions ───────────────────────────────────────────────────────
  const v1 = call('PUT', PL, { token: t, body: { subject: 'Zamówienie {{order}}', body: 'Dziękujemy!\n\n{{order}}\n\n— {{shop}}' } });
  expect(v1, '[+] the owner saves the Polish confirmation', 200);
  truthy('[+] ...as version 1, the business\'s own', data(v1).source === 'BUSINESS' && data(v1).version === 1, data(v1));
  const v2 = call('PUT', PL, { token: t, body: POLISH });
  truthy('[+] a second save is version 2, and the first stays in the history, retired', v2.status === 200 && data(v2).version === 2 && data(v2).history.length === 2 && !!data(v2).history.find((h) => h.version === 1).retiredAt, data(v2));
  truthy('[+] the list says the business writes this one in Polish', ((list(call('GET', `${ADMIN}/templates`, { token: t })).find((m) => m.type === 'ORDER_CONFIRMED').forms.find((f) => f.form === 'EMAIL') || {}).written || []).some((w) => w.language === 'pl' && w.version === 2));
  truthy("[-] the rival shop still has the platform's words", data(call('GET', PL, { token: rival.owner.token })).source === 'DEFAULT' && list(call('GET', `${ADMIN}/templates`, { token: rival.owner.token })).every((m) => m.forms.every((f) => f.written.length === 0)));
  expect(call('DELETE', PL, { token: rival.owner.token }), '[-] and cannot retire this shop\'s', 404, 'TEMPLATE_NOT_WRITTEN');

  // Five saves of the push at once: each one either takes the next version or is told another did.
  const pushPl = ONE('ORDER_CONFIRMED', 'PUSH', 'pl');
  const race = http.batch(Array.from({ length: 5 }, (_, i) => ['PUT', `${BASE}${pushPl}`, JSON.stringify({ subject: 'Zamówienie', body: `Zamówienie {{order}} (${i})` }), { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${t}` }, tags: { name: 'PUT /api/notification-svc/admin/notifications/templates (race)' } }]));
  const racedHistory = data(call('GET', pushPl, { token: t })).history || [];
  truthy('[+] five racing saves: each saved or told another was, never a server error', race.every((r) => r.status === 200 || (r.status === 409 && problem(r).code === 'TEMPLATE_SAVED_MEANWHILE')) && race.some((r) => r.status === 200), race.map((r) => r.status).join(','));
  truthy('[+] ...and exactly one version is live, numbered without gaps', racedHistory.filter((h) => !h.retiredAt).length === 1 && racedHistory.map((h) => h.version).sort((a, b) => a - b).every((v, i) => v === i + 1) && racedHistory.length === race.filter((r) => r.status === 200).length, racedHistory);

  // ── how the shop signs ───────────────────────────────────────────────────────
  truthy("[+] signed with the business's name until the owner says otherwise", data(call('GET', `${ADMIN}/template-settings`, { token: t })).signedAs.startsWith('templates '), data(call('GET', `${ADMIN}/template-settings`, { token: t })));
  expect(call('PUT', `${ADMIN}/template-settings`, { token: t, body: { defaultLanguage: 'klingon', signOff: 'x' } }), '[-] a house language that is not one', 400);
  const signed = call('PUT', `${ADMIN}/template-settings`, { token: t, body: { defaultLanguage: 'en', signOff: 'Sklep Hollins' } });
  expect(signed, '[+] the owner signs as "Sklep Hollins"', 200);
  truthy('[+] ...and English stays the house language', data(signed).signedAs === 'Sklep Hollins' && data(signed).defaultLanguage === 'en', data(signed));

  // ── the reader's language ────────────────────────────────────────────────────
  const me = (who, method, body) => call(method, '/api/customer-svc/customers/me', { token: who.token, body, ...shop });
  expect(me(polish, 'POST'), '[+] a shopper claims their record', 200);
  expect(me(polish, 'PUT', { firstName: 'Ola', lastName: 'Nowak', preferredLanguage: 'polish' }), '[-] a language that is not a code', 400);
  const chosen = me(polish, 'PUT', { firstName: 'Ola', lastName: 'Nowak', preferredLanguage: 'PL' });
  expect(chosen, '[+] and says they read Polish', 200);
  truthy('[+] ...kept as the code', data(chosen).preferredLanguage === 'pl', data(chosen));
  truthy('[+] a later edit that does not mention it keeps it', data(me(polish, 'PUT', { firstName: 'Ola', lastName: 'Nowak' })).preferredLanguage === 'pl');
  expect(me(plain, 'POST'), '[+] a second shopper claims theirs, and says nothing of language', 200);

  const buyAndConfirm = (who, label) => {
    const placed = call('POST', '/api/order-svc/orders', { token: who.token, ...shop, idem: true, body: { storeId: store.id, channel: 'ONLINE', fulfilmentType: 'PICKUP', items: [{ variantId, qty: 1 }] } });
    expect(placed, `[+] ${label} orders`, 201);
    expect(call('POST', `/api/order-svc/orders/${data(placed).id}/confirm`, { token: t, body: {} }), `[+] ...and the shop confirms it`, 200);
    return { id: data(placed).id, total: Number(data(placed).total).toFixed(2) };
  };
  const confirmation = (who, { id: orderId }) => {
    let found = {};
    poll(60, () => {
      const log = list(call('GET', `${ADMIN}?recipient=${encodeURIComponent(who.email.toLowerCase())}&limit=100`, { token: t }));
      found = log.find((n) => n.type === 'ORDER_CONFIRMATION' && (n.body || '').includes(orderId)) || {};
      return !!found.id;
    });
    return found;
  };

  const polishOrder = buyAndConfirm(polish, 'the Polish reader');
  const inPolish = confirmation(polish, polishOrder);
  truthy('[+] their confirmation is written in Polish, in the words of version 2', inPolish.language === 'pl' && inPolish.template === 'v2' && inPolish.subject === `Zamówienie ${polishOrder.id} potwierdzone` && new RegExp(`Razem: ${polishOrder.total.replace('.', ',')}[\\s\\u00a0\\u202f]GBP`).test(inPolish.body), inPolish);
  truthy('[+] ...signed the way the shop chose', (inPolish.body || '').endsWith('— Sklep Hollins'), inPolish.body);

  const plainOrder = buyAndConfirm(plain, 'the shopper who never said');
  const inEnglish = confirmation(plain, plainOrder);
  truthy("[+] theirs goes out in the platform's English, in pounds", inEnglish.language === 'en' && inEnglish.template === 'default' && inEnglish.subject === 'Your order is confirmed' && (inEnglish.body || '').includes(`Total: £${plainOrder.total}`), inEnglish);

  // ── back to the platform's words ─────────────────────────────────────────────
  expect(call('DELETE', PL, { token: cashier.token }), '[-] a cashier cannot retire them', 403);
  expect(call('DELETE', PL, { token: t }), "[+] the owner goes back to the platform's words", 200);
  expect(call('DELETE', PL, { token: t }), '[-] and cannot do it twice', 404, 'TEMPLATE_NOT_WRITTEN');
  const after = data(call('GET', PL, { token: t }));
  truthy("[+] the platform's words again, with both versions kept in the history", after.source === 'DEFAULT' && after.history.length === 2 && after.history.every((h) => !!h.retiredAt), after);
  const againOrder = buyAndConfirm(polish, 'the Polish reader, again');
  const again = confirmation(polish, againOrder);
  truthy('[+] with no Polish words of the shop\'s, the Polish reader gets the platform\'s English — signed by the shop', again.language === 'en' && again.template === 'default' && (again.body || '').endsWith('— Sklep Hollins'), again);

  // ── abuse ────────────────────────────────────────────────────────────────────
  const flood = http.batch(Array.from({ length: 20 }, () => ['POST', `${BASE}${PL}/preview`, JSON.stringify({ subject: 's', body: 'x'.repeat(40001) }), { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${t}` }, tags: { name: 'POST /api/notification-svc/admin/notifications/templates/preview (flood)' } }]));
  truthy('[-] twenty oversized previews at once: every one refused', flood.every((r) => r.status === 400 || r.status === 413 || r.status === 429), flood.map((r) => r.status).join(','));
  expect(call('GET', `${ADMIN}/templates`, { token: other.token, ...shop }), '[-] a stranger with a storefront header reads nothing', [401, 403]);
}
