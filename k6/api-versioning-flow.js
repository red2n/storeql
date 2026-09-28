// The API versioning policy (22.8), at the door: GET /api/versions describes it with no credential
// — the current version, every version with its status, the deprecated unversioned alias with the
// day it was deprecated and the day it stops, where a version's OpenAPI description is, and the
// policy in a paragraph; every answer on the alias carries Deprecation (RFC 9745, a date), Sunset
// (RFC 8594) and a Link to the successor, whatever the status, and the versioned form carries none;
// a version nobody published is 404 API_VERSION_UNKNOWN with a Link to the latest; an authenticated
// call answers the same on either form; every service describes itself in OpenAPI 3.1 under /api/v1.
//
//   k6/run.sh api-versioning-flow
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import { ALL_CHECKS_PASS, BASE, call, data, errorCode, expect, must, onboardTenant, truthy } from './lib/storeql.js';

const completed = new Counter('flow_completed');
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { ...ALL_CHECKS_PASS, flow_completed: ['count==1'] },
  setupTimeout: '4m',
};

const SERVICES = ['iam-svc', 'tenant-svc', 'product-svc', 'inventory-svc', 'pricing-svc', 'cart-svc', 'order-svc', 'payment-svc', 'purchase-svc', 'customer-svc', 'notification-svc', 'reporting-svc'];
const DEPRECATION = '@1790121600'; // 2026-09-23T00:00:00Z
const SUNSET = 'Thu, 30 Sep 2027 00:00:00 GMT';

export function setup() {
  return { tenant: onboardTenant('versions') };
}

const header = (res, name) => res.headers[name] || res.headers[name.toLowerCase()] || res.headers[name.charAt(0).toUpperCase() + name.slice(1).toLowerCase()] || null;

export default function ({ tenant }) {
  // ── the document ─────────────────────────────────────────────────────────────────────────────
  const doc = http.get(`${BASE}/api/versions`);
  expect(doc, '[+] the versions document needs no credential', 200);
  const d = data(doc);
  truthy('[+] v1 is current, at /api/v1/{service}', d.current === 'v1' && (d.versions || []).some((v) => v.version === 'v1' && v.status === 'current' && v.base === '/api/v1/{service}'), d);
  const alias = (d.versions || []).find((v) => v.version === 'unversioned') || {};
  truthy('[+] the unversioned alias is deprecated, with the day it was and the day it stops, and its successor', alias.status === 'deprecated' && alias.deprecatedSince === '2026-09-23' && alias.sunset === '2027-09-30' && alias.successor === '/api/v1' && alias.base === '/api/{service}', alias);
  truthy('[+] the policy in a paragraph, and where a description is found', typeof d.policy === 'string' && d.policy.includes('twelve months') && d.openapi === '/api/v1/{service}/openapi', { policy: (d.policy || '').slice(0, 60), openapi: d.openapi });
  truthy('[+] the document itself is neither deprecated nor a service call', !header(doc, 'Deprecation') && !header(doc, 'Sunset') && String(header(doc, 'Cache-Control') || '').includes('max-age'), doc.headers);

  // ── the alias is marked, the versioned form is not ─────────────────────────────────────────
  const onAlias = http.get(`${BASE}/api/tenant-svc/plans`);
  expect(onAlias, '[+] a public route on the alias answers', 200);
  truthy('[+] ...with Deprecation as an RFC 9745 date', header(onAlias, 'Deprecation') === DEPRECATION, header(onAlias, 'Deprecation'));
  truthy('[+] ...Sunset as an RFC 8594 HTTP date', header(onAlias, 'Sunset') === SUNSET, header(onAlias, 'Sunset'));
  truthy('[+] ...and a Link to the successor', String(header(onAlias, 'Link') || '').includes('</api/v1>; rel="successor-version"'), header(onAlias, 'Link'));
  const onV1 = http.get(`${BASE}/api/v1/tenant-svc/plans`);
  expect(onV1, '[+] the same route on /api/v1 answers the same', 200);
  truthy('[+] ...and carries none of it', !header(onV1, 'Deprecation') && !header(onV1, 'Sunset') && !String(header(onV1, 'Link') || '').includes('successor-version'), onV1.headers);
  truthy('[+] the two forms answer the same body', JSON.stringify(data(onAlias)) === JSON.stringify(data(onV1)), 'bodies differ');
  const refused = call('POST', '/api/iam-svc/auth/login', { body: { email: 'nobody@k6.storeql.test', password: 'wrong-and-long-enough' } });
  truthy('[+] a refusal on the alias is marked too', refused.status === 401 && header(refused, 'Deprecation') === DEPRECATION && header(refused, 'Sunset') === SUNSET, { status: refused.status, headers: refused.headers });

  // ── a version nobody published ───────────────────────────────────────────────────────────────
  const unknown = http.get(`${BASE}/api/v9/tenant-svc/plans`);
  expect(unknown, '[-] a version nobody published is not found', 404, 'API_VERSION_UNKNOWN');
  truthy('[-] ...as a problem, with a Link to the latest', String(header(unknown, 'Content-Type') || '').includes('application/problem+json') && String(header(unknown, 'Link') || '').includes('</api/v1>; rel="latest-version"'), unknown.headers);
  truthy('[-] ...whatever follows it', errorCode(call('GET', '/api/v0/order-svc/orders', { token: tenant.owner.token })) === 'API_VERSION_UNKNOWN', 'v0');

  // ── an authenticated call, either way ────────────────────────────────────────────────────────
  const owner = tenant.owner.token;
  const viaAlias = must(call('GET', '/api/tenant-svc/admin/tenant', { token: owner }), 200, 'profile via alias');
  const viaV1 = must(call('GET', '/api/v1/tenant-svc/admin/tenant', { token: owner }), 200, 'profile via v1');
  truthy('[+] an authenticated call answers the same on both forms', viaAlias.id === tenant.tenantId && viaV1.id === tenant.tenantId && viaAlias.name === viaV1.name, { viaAlias: viaAlias.id, viaV1: viaV1.id });
  const written = call('PUT', '/api/v1/tenant-svc/admin/tenant', { token: owner, body: { businessName: `${viaAlias.name} v1`, legalName: viaAlias.legalName } });
  expect(written, '[+] a write on /api/v1 lands', 200);
  truthy('[+] ...and is read back on the alias', must(call('GET', '/api/tenant-svc/admin/tenant', { token: owner }), 200, 'read back').name === `${viaAlias.name} v1`, 'name');

  // ── every service describes itself in OpenAPI 3.1 under /api/v1 ─────────────────────────────
  for (const svc of SERVICES) {
    const description = http.get(`${BASE}/api/v1/${svc}/openapi`);
    const text = String(description.body || '');
    truthy(`[+] ${svc} describes itself in OpenAPI 3.1 at /api/v1/${svc}/openapi`, description.status === 200 && /["']?openapi["']?\s*:\s*["']?3\.1/.test(text), { status: description.status, head: text.slice(0, 60) });
  }
  const gateway = http.get(`${BASE}/openapi`, { headers: { Authorization: `Bearer ${owner}` } });
  truthy('[+] the gateway\'s own description lists /api/versions', gateway.status === 200 && String(gateway.body || '').includes('/api/versions'), { status: gateway.status });

  completed.add(1);
}
