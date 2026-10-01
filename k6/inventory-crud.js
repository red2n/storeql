// Converted to real JWT sign-in: the gateway strips X-Tenant-Id / X-User-Id / X-Roles, so every call
// carries the owner's bearer token and a call with no token is a 401 at the gateway.
//
//   k6/run.sh inventory-crud
import http from 'k6/http';
import { check as k6check, sleep } from 'k6';
import { ALL_CHECKS_PASS, BASE as baseUrl, onboardTenant, register, sellableVariant } from './lib/storeql.js';

export const options = { vus: 1, iterations: 1, thresholds: ALL_CHECKS_PASS, setupTimeout: '3m' };

const JSON_CT = { 'Content-Type': 'application/json' };

/** k6's check, plus the response on failure so a red check says why. */
function check(res, sets) {
  const ok = k6check(res, sets);
  if (!ok) {
    const failed = Object.entries(sets).filter(([, fn]) => { try { return !fn(res); } catch (_) { return true; } }).map(([name]) => name);
    console.error(`✗ ${failed.join(' | ')}: got ${res && res.status} ${String(res && res.body).slice(0, 400)}`);
  }
  return ok;
}

export function setup() {
  const tenant = onboardTenant('inventory', { stores: 2 });
  return {
    tenant,
    variantId: sellableVariant(tenant, 'Stocked beans').variantId,
    shopper: register('inventory-shopper'),
    rival: onboardTenant('inventory-rival'),
  };
}

export default function (d) {
  const tenantId = d.tenant.tenantId;
  const storeId = d.tenant.stores[0].id;
  const variantId = d.variantId;
  const hdrs = { ...JSON_CT, Authorization: `Bearer ${d.tenant.owner.token}` };

  // ── Gap #1-#7: core inventory positive checks ─────────────────────────────

  const recRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/receive`,
    JSON.stringify({
      storeId: storeId || '01a090ae-611e-7001-a690-2682e4afcb55',
      variantId,
      qty: 50,
      batchNo: `BATCH-${Date.now()}`,
      costPrice: '9.99',
    }),
    { headers: hdrs }
  );
  check(recRes, { '[+] receive stock 201': (r) => r.status === 201 });

  sleep(0.5);

  const levelsRes = http.get(`${baseUrl}/api/inventory-svc/admin/inventory/levels`, { headers: hdrs });
  check(levelsRes, { '[+] levels 200': (r) => r.status === 200 });

  const adjRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/adjust`,
    JSON.stringify({
      storeId: storeId || '01a090ae-611e-7001-a690-2682e4afcb55',
      variantId,
      delta: -5,
      reason: 'k6-test-adjustment',
    }),
    { headers: hdrs }
  );
  check(adjRes, { '[+] adjust stock 200': (r) => r.status === 200 });

  // ── Gap #9: ABC Analysis — positive checks ───────────────────────────────

  // Compile with no demand history → run created, 0 items compiled
  const abcCompileEmptyRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/abc/compile`,
    JSON.stringify({}),
    { headers: hdrs }
  );
  check(abcCompileEmptyRes, {
    '[+] abc compile 201': (r) => r.status === 201,
    '[+] abc compile has itemsCompiled': (r) => {
      try { return typeof r.json('data.itemsCompiled') === 'number'; } catch (_) { return false; }
    },
  });

  // Compile with explicit VALUE criteria and custom thresholds
  const abcCompileValueRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/abc/compile`,
    JSON.stringify({
      storeId: storeId || '01a090ae-611e-7001-a690-2682e4afcb55',
      criteria: 'VALUE',
      thresholdA: 70,
      thresholdAB: 90,
    }),
    { headers: hdrs }
  );
  check(abcCompileValueRes, {
    '[+] abc compile VALUE 201': (r) => r.status === 201,
    '[+] abc compile criteria is VALUE': (r) => {
      try { return r.json('data.criteria') === 'VALUE'; } catch (_) { return false; }
    },
  });

  // Compile with VELOCITY criteria
  const abcCompileVelocityRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/abc/compile`,
    JSON.stringify({ criteria: 'VELOCITY' }),
    { headers: hdrs }
  );
  check(abcCompileVelocityRes, {
    '[+] abc compile VELOCITY 201': (r) => r.status === 201,
  });

  // List ABC assignments (may be empty if no demand data)
  const abcListRes = http.get(
    `${baseUrl}/api/inventory-svc/admin/inventory/abc/assignments`,
    { headers: hdrs }
  );
  check(abcListRes, {
    '[+] list abc assignments 200': (r) => r.status === 200,
    '[+] abc assignments is array': (r) => {
      try { return Array.isArray(r.json('data')); } catch (_) { return false; }
    },
  });

  // List filtered by class A
  const abcListARes = http.get(
    `${baseUrl}/api/inventory-svc/admin/inventory/abc/assignments?class=A`,
    { headers: hdrs }
  );
  check(abcListARes, {
    '[+] list abc class=A 200': (r) => r.status === 200,
  });

  // Compile is idempotent (re-run same params → 201, previous overwritten)
  const abcReRunRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/abc/compile`,
    JSON.stringify({ criteria: 'VALUE', thresholdA: 70, thresholdAB: 90 }),
    { headers: hdrs }
  );
  check(abcReRunRes, {
    '[+] abc re-compile is idempotent (201)': (r) => r.status === 201,
  });

  sleep(0.3);

  // ── Gap #9: ABC Analysis — negative checks ────────────────────────────────

  // Invalid criteria
  const abcBadCriteriaRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/abc/compile`,
    JSON.stringify({ criteria: 'COST' }),
    { headers: hdrs }
  );
  check(abcBadCriteriaRes, {
    '[-] invalid abc criteria → 400': (r) => r.status === 400,
  });

  // thresholdA >= thresholdAB
  const abcBadThreshRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/abc/compile`,
    JSON.stringify({ criteria: 'VALUE', thresholdA: 90, thresholdAB: 70 }),
    { headers: hdrs }
  );
  check(abcBadThreshRes, {
    '[-] thresholdA >= thresholdAB → 400': (r) => r.status === 400,
  });

  // thresholdA = 0
  const abcZeroThreshRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/abc/compile`,
    JSON.stringify({ criteria: 'VALUE', thresholdA: 0, thresholdAB: 90 }),
    { headers: hdrs }
  );
  check(abcZeroThreshRes, {
    '[-] thresholdA = 0 → 400': (r) => r.status === 400,
  });

  // Invalid class filter
  const abcBadClassRes = http.get(
    `${baseUrl}/api/inventory-svc/admin/inventory/abc/assignments?class=X`,
    { headers: hdrs }
  );
  check(abcBadClassRes, {
    '[-] invalid abc class filter → 400': (r) => r.status === 400,
  });

  // Get assignment for nonexistent variant → 404
  const abcNotFoundRes = http.get(
    `${baseUrl}/api/inventory-svc/admin/inventory/abc/assignments/` +
      `01a090ae-611e-7001-a690-2682e4afcb55/01a090ae-611e-7000-9e1a-0f8a9e565153`,
    { headers: hdrs }
  );
  check(abcNotFoundRes, {
    '[-] get non-existent abc assignment → 404': (r) => r.status === 404,
  });

  // No tenant header → 4xx
  const abcNoTenantRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/abc/compile`,
    JSON.stringify({ criteria: 'VALUE' }),
    { headers: JSON_CT }
  );
  check(abcNoTenantRes, {
    '[-] abc compile no token → 401': (r) => r.status === 401,
  });

  sleep(0.3);

  // ── Gap #8: Safety Stock — positive checks ────────────────────────────────

  // Set safety stock params (MAD method)
  const ssMADRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/safety-stock`,
    JSON.stringify({
      storeId: storeId || '01a090ae-611e-7001-a690-2682e4afcb55',
      variantId,
      method: 'MAD',
      leadTimeDays: 7,
      serviceLevelPct: 95,
    }),
    { headers: hdrs }
  );
  check(ssMADRes, {
    '[+] set safety stock MAD 201': (r) => r.status === 201,
    '[+] safety stock method is MAD': (r) => {
      try { return r.json('data.method') === 'MAD'; } catch (_) { return false; }
    },
    '[+] safety stock leadTimeDays is 7': (r) => {
      try { return r.json('data.leadTimeDays') === 7; } catch (_) { return false; }
    },
  });

  // Set safety stock params (USER_DEFINED method)
  const variantId2 = '01a090ae-611e-7006-8337-8ada32e08175';
  const ssUDRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/safety-stock`,
    JSON.stringify({
      storeId: storeId || '01a090ae-611e-7001-a690-2682e4afcb55',
      variantId: variantId2,
      method: 'USER_DEFINED',
      leadTimeDays: 14,
      userDefinedPct: 25,
    }),
    { headers: hdrs }
  );
  check(ssUDRes, {
    '[+] set safety stock USER_DEFINED 201': (r) => r.status === 201,
    '[+] safety stock method is USER_DEFINED': (r) => {
      try { return r.json('data.method') === 'USER_DEFINED'; } catch (_) { return false; }
    },
  });

  // List safety stock params
  const ssListRes = http.get(
    `${baseUrl}/api/inventory-svc/admin/inventory/safety-stock`,
    { headers: hdrs }
  );
  check(ssListRes, {
    '[+] list safety stock 200': (r) => r.status === 200,
    '[+] safety stock list is array': (r) => {
      try { return Array.isArray(r.json('data')); } catch (_) { return false; }
    },
  });

  // Compute safety stock (no demand history yet → returns 0 or rows=N computed)
  const ssComputeRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/safety-stock/compute`,
    JSON.stringify({ storeId: storeId || '01a090ae-611e-7001-a690-2682e4afcb55' }),
    { headers: hdrs }
  );
  check(ssComputeRes, {
    '[+] compute safety stock 200': (r) => r.status === 200,
    '[+] compute result has computed field': (r) => {
      try { return typeof r.json('data.computed') === 'number'; } catch (_) { return false; }
    },
  });

  // Get single safety stock params by storeId/variantId
  if (storeId) {
    const ssGetRes = http.get(
      `${baseUrl}/api/inventory-svc/admin/inventory/safety-stock/${storeId}/${variantId}`,
      { headers: hdrs }
    );
    check(ssGetRes, {
      '[+] get safety stock params 200': (r) => r.status === 200,
      '[+] get safety stock params returns storeId': (r) => {
        try { return typeof r.json('data.storeId') === 'string'; } catch (_) { return false; }
      },
    });
  }

  // Upsert idempotency: re-posting same variant should update (not 409)
  const ssUpsertRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/safety-stock`,
    JSON.stringify({
      storeId: storeId || '01a090ae-611e-7001-a690-2682e4afcb55',
      variantId,
      method: 'MAD',
      leadTimeDays: 10,
      serviceLevelPct: 98,
    }),
    { headers: hdrs }
  );
  check(ssUpsertRes, {
    '[+] upsert safety stock is idempotent (2xx)': (r) => r.status < 300,
    '[+] upsert updates leadTimeDays to 10': (r) => {
      try { return r.json('data.leadTimeDays') === 10; } catch (_) { return false; }
    },
  });

  sleep(0.3);

  // ── Gap #8: Safety Stock — negative checks ────────────────────────────────

  // Missing method field (should default gracefully or return 400)
  const ssMissingVariantRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/safety-stock`,
    JSON.stringify({
      storeId: storeId || '01a090ae-611e-7001-a690-2682e4afcb55',
      // variantId missing
      method: 'MAD',
    }),
    { headers: hdrs }
  );
  check(ssMissingVariantRes, {
    '[-] missing variantId → 400': (r) => r.status === 400,
  });

  // Invalid method value
  const ssBadMethodRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/safety-stock`,
    JSON.stringify({
      storeId: storeId || '01a090ae-611e-7001-a690-2682e4afcb55',
      variantId: '01a090ae-611e-7005-8b65-d402a5e8f0da',
      method: 'INVALID_METHOD',
    }),
    { headers: hdrs }
  );
  check(ssBadMethodRes, {
    '[-] invalid method → 400': (r) => r.status === 400,
  });

  // USER_DEFINED without userDefinedPct
  const ssMissingPctRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/safety-stock`,
    JSON.stringify({
      storeId: storeId || '01a090ae-611e-7001-a690-2682e4afcb55',
      variantId: '01a090ae-611e-7004-8b69-9aaf2af687c6',
      method: 'USER_DEFINED',
      // userDefinedPct missing
    }),
    { headers: hdrs }
  );
  check(ssMissingPctRes, {
    '[-] USER_DEFINED without userDefinedPct → 400': (r) => r.status === 400,
  });

  // Get non-existent safety stock params → 404
  const ssNotFoundRes = http.get(
    `${baseUrl}/api/inventory-svc/admin/inventory/safety-stock/` +
      `01a090ae-611e-7001-a690-2682e4afcb55/01a090ae-611e-7000-9e1a-0f8a9e565153`,
    { headers: hdrs }
  );
  check(ssNotFoundRes, {
    '[-] get non-existent safety stock → 404': (r) => r.status === 404,
  });

  // No tenant header → 401
  const ssNoTenantRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/safety-stock`,
    JSON.stringify({ storeId: '01a090ae-611e-7001-a690-2682e4afcb55', variantId, method: 'MAD' }),
    { headers: JSON_CT }
  );
  check(ssNoTenantRes, {
    '[-] no token → 401': (r) => r.status === 401,
  });

  // Invalid UUID for storeId
  const ssBadUUIDRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/safety-stock`,
    JSON.stringify({ storeId: 'not-a-uuid', variantId, method: 'MAD' }),
    { headers: hdrs }
  );
  check(ssBadUUIDRes, {
    '[-] invalid storeId UUID → 400': (r) => r.status === 400,
  });

  // ── Gap #10: Cycle Counting — positive checks ─────────────────────────────

  // Create a cycle count (will pick up ABC assignments from the A,B,C classes)
  const ccCreateRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/cycle-counts`,
    JSON.stringify({
      storeId,
      name: 'Monthly count',
      abcClasses: 'A,B,C',
      tolerancePct: 5,
    }),
    { headers: hdrs }
  );
  check(ccCreateRes, {
    '[+] create cycle count 201': (r) => r.status === 201,
    '[+] cycle count has id': (r) => {
      try { return JSON.parse(r.body).data.id !== undefined; } catch { return false; }
    },
    '[+] cycle count status OPEN': (r) => {
      try { return JSON.parse(r.body).data.status === 'OPEN'; } catch { return false; }
    },
  });

  let ccId = null;
  try { ccId = JSON.parse(ccCreateRes.body).data.id; } catch (_) {}

  // List cycle counts
  const ccListRes = http.get(
    `${baseUrl}/api/inventory-svc/admin/inventory/cycle-counts`,
    { headers: hdrs }
  );
  check(ccListRes, {
    '[+] list cycle counts 200': (r) => r.status === 200,
    '[+] cycle counts list is array': (r) => {
      try { return Array.isArray(JSON.parse(r.body).data); } catch { return false; }
    },
  });

  // Get cycle count by id
  if (ccId) {
    const ccGetRes = http.get(
      `${baseUrl}/api/inventory-svc/admin/inventory/cycle-counts/${ccId}`,
      { headers: hdrs }
    );
    check(ccGetRes, {
      '[+] get cycle count 200': (r) => r.status === 200,
      '[+] get cycle count returns name': (r) => {
        try { return JSON.parse(r.body).data.name === 'Monthly count'; } catch { return false; }
      },
    });

    // Enter a count on the first line if any lines exist
    const ccLinesRaw = JSON.parse(ccGetRes.body).data;
    if (ccLinesRaw && ccLinesRaw.totalLines > 0) {
      // We need to get the actual line ids — re-fetch with lines detail
      // Lines are not part of the header response but the endpoint returns them embedded
      // For the k6 test we derive a line id from a separate get (totalLines > 0 means lines exist)
    }

    // Approve within tolerance (no lines yet → autoApproved=0 is fine)
    const ccApproveRes = http.post(
      `${baseUrl}/api/inventory-svc/admin/inventory/cycle-counts/${ccId}/approve`,
      null,
      { headers: hdrs }
    );
    check(ccApproveRes, {
      '[+] approve cycle count 200': (r) => r.status === 200,
      '[+] approve result has autoApproved field': (r) => {
        try { return JSON.parse(r.body).data.autoApproved !== undefined; } catch { return false; }
      },
    });

    // Adjust cycle count (promotes PENDING_APPROVAL or OPEN header to ADJUSTED)
    const ccAdjustRes = http.post(
      `${baseUrl}/api/inventory-svc/admin/inventory/cycle-counts/${ccId}/adjust`,
      null,
      { headers: hdrs }
    );
    check(ccAdjustRes, {
      '[+] adjust cycle count 200': (r) => r.status === 200,
      '[+] adjust result has adjusted field': (r) => {
        try { return JSON.parse(r.body).data.adjusted !== undefined; } catch { return false; }
      },
    });
  }

  // ── Gap #10: Cycle Counting — negative checks ─────────────────────────────

  // Missing storeId
  const ccNoStoreRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/cycle-counts`,
    JSON.stringify({ name: 'Test' }),
    { headers: hdrs }
  );
  check(ccNoStoreRes, {
    '[-] create cycle count missing storeId → 400': (r) => r.status === 400,
  });

  // Missing name
  const ccNoNameRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/cycle-counts`,
    JSON.stringify({ storeId }),
    { headers: hdrs }
  );
  check(ccNoNameRes, {
    '[-] create cycle count missing name → 400': (r) => r.status === 400,
  });

  // Get non-existent cycle count → 404
  const ccNotFoundRes = http.get(
    `${baseUrl}/api/inventory-svc/admin/inventory/cycle-counts/01a090ae-611e-7007-b85c-1fbac22cb87b`,
    { headers: hdrs }
  );
  check(ccNotFoundRes, {
    '[-] get non-existent cycle count → 404': (r) => r.status === 404,
  });

  // No tenant header → 401
  const ccNoTenantRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/cycle-counts`,
    JSON.stringify({ storeId, name: 'T' }),
    { headers: JSON_CT }
  );
  check(ccNoTenantRes, {
    '[-] cycle count no token → 401': (r) => r.status === 401,
  });

  // Enter count on non-existent line → 404
  const ccBadLineRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/cycle-counts/` +
      '01a090ae-611e-7007-b85c-1fbac22cb87b/lines/01a090ae-611e-7006-8337-8ada32e08175/count',
    JSON.stringify({ countedQty: 10 }),
    { headers: hdrs }
  );
  check(ccBadLineRes, {
    '[-] enter count on non-existent line → 404': (r) => r.status === 404,
  });

  // ── Gap #11: Lot Genealogy — positive checks ──────────────────────────────

  // We need two batch ids: recRes already gave us a batch — receive a second one
  let parentBatchId = null;
  let childBatchId = null;
  try { parentBatchId = JSON.parse(recRes.body).data.id; } catch (_) {}

  const recRes2 = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/receive`,
    JSON.stringify({
      storeId: storeId || '01a090ae-611e-7001-a690-2682e4afcb55',
      variantId,
      qty: 10,
      batchNo: `CHILD-${Date.now()}`,
      costPrice: '9.99',
    }),
    { headers: hdrs }
  );
  try { childBatchId = JSON.parse(recRes2.body).data.id; } catch (_) {}

  // Create a SPLIT genealogy link (parent → child)
  const lgCreateRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/lot-genealogy`,
    JSON.stringify({
      parentBatchId: parentBatchId || '01a090ae-611e-7001-a690-2682e4afcb55',
      childBatchId: childBatchId || '01a090ae-611e-7002-b3ac-c478c91b06ac',
      qty: 10,
      relationType: 'SPLIT',
      notes: 'k6 split test',
    }),
    { headers: hdrs }
  );
  check(lgCreateRes, {
    '[+] create lot genealogy link 201': (r) => r.status === 201,
    '[+] lot link has id': (r) => {
      try { return JSON.parse(r.body).data.id !== undefined; } catch { return false; }
    },
    '[+] lot link relationType is SPLIT': (r) => {
      try { return JSON.parse(r.body).data.relationType === 'SPLIT'; } catch { return false; }
    },
  });

  // Receive a third batch so MERGE doesn't create a cycle
  const recRes3 = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/receive`,
    JSON.stringify({
      storeId: storeId || '01a090ae-611e-7001-a690-2682e4afcb55',
      variantId,
      qty: 5,
      batchNo: `MERGE-${Date.now()}`,
      costPrice: '9.99',
    }),
    { headers: hdrs }
  );
  let mergeBatchId = null;
  try { mergeBatchId = JSON.parse(recRes3.body).data.id; } catch (_) {}

  // Create a MERGE link: childBatchId + mergeBatchId → new merged batch (use parentBatchId as target)
  const lgMergeRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/lot-genealogy`,
    JSON.stringify({
      parentBatchId: mergeBatchId || '01a090ae-611e-7003-8eac-d9c47e691ad7',
      childBatchId: childBatchId || '01a090ae-611e-7002-b3ac-c478c91b06ac',
      qty: 5,
      relationType: 'MERGE',
    }),
    { headers: hdrs }
  );
  check(lgMergeRes, {
    '[+] create MERGE lot link 201': (r) => r.status === 201,
  });

  // Get ancestors of childBatchId
  if (childBatchId) {
    const lgAncestorsRes = http.get(
      `${baseUrl}/api/inventory-svc/admin/inventory/lot-genealogy/batch/${childBatchId}/ancestors`,
      { headers: hdrs }
    );
    check(lgAncestorsRes, {
      '[+] get lot ancestors 200': (r) => r.status === 200,
      '[+] ancestors list is array': (r) => {
        try { return Array.isArray(JSON.parse(r.body).data.ancestors); } catch { return false; }
      },
    });

    // Get descendants of parentBatchId
    const lgDescRes = http.get(
      `${baseUrl}/api/inventory-svc/admin/inventory/lot-genealogy/batch/${parentBatchId}/descendants`,
      { headers: hdrs }
    );
    check(lgDescRes, {
      '[+] get lot descendants 200': (r) => r.status === 200,
      '[+] descendants list is array': (r) => {
        try { return Array.isArray(JSON.parse(r.body).data.descendants); } catch { return false; }
      },
    });

    // Get direct links for childBatchId
    const lgLinksRes = http.get(
      `${baseUrl}/api/inventory-svc/admin/inventory/lot-genealogy/batch/${childBatchId}/links`,
      { headers: hdrs }
    );
    check(lgLinksRes, {
      '[+] get direct lot links 200': (r) => r.status === 200,
      '[+] direct links is array': (r) => {
        try { return Array.isArray(JSON.parse(r.body).data); } catch { return false; }
      },
    });
  }

  // Idempotent re-create returns conflict (409 / 4xx) — same parent+child pair
  if (parentBatchId && childBatchId) {
    const lgDupRes = http.post(
      `${baseUrl}/api/inventory-svc/admin/inventory/lot-genealogy`,
      JSON.stringify({ parentBatchId, childBatchId, qty: 10, relationType: 'SPLIT' }),
      { headers: hdrs }
    );
    check(lgDupRes, {
      '[-] duplicate lot link refused': (r) => r.status === 409,
    });
  }

  // ── Gap #11: Lot Genealogy — negative checks ──────────────────────────────

  // Missing parentBatchId
  const lgNoParentRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/lot-genealogy`,
    JSON.stringify({ childBatchId: childBatchId || '01a090ae-611e-7002-b3ac-c478c91b06ac', qty: 5,
      relationType: 'SPLIT' }),
    { headers: hdrs }
  );
  check(lgNoParentRes, {
    '[-] lot link missing parentBatchId → 400': (r) => r.status === 400,
  });

  // Missing childBatchId
  const lgNoChildRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/lot-genealogy`,
    JSON.stringify({ parentBatchId: parentBatchId || '01a090ae-611e-7001-a690-2682e4afcb55',
      qty: 5, relationType: 'SPLIT' }),
    { headers: hdrs }
  );
  check(lgNoChildRes, {
    '[-] lot link missing childBatchId → 400': (r) => r.status === 400,
  });

  // Missing qty
  const lgNoQtyRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/lot-genealogy`,
    JSON.stringify({
      parentBatchId: parentBatchId || '01a090ae-611e-7001-a690-2682e4afcb55',
      childBatchId: mergeBatchId || '01a090ae-611e-7003-8eac-d9c47e691ad7',
      relationType: 'SPLIT',
    }),
    { headers: hdrs }
  );
  check(lgNoQtyRes, {
    '[-] lot link missing qty → 400': (r) => r.status === 400,
  });

  // Invalid relationType
  const lgBadTypeRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/lot-genealogy`,
    JSON.stringify({
      parentBatchId: parentBatchId || '01a090ae-611e-7001-a690-2682e4afcb55',
      childBatchId: mergeBatchId || '01a090ae-611e-7003-8eac-d9c47e691ad7',
      qty: 5,
      relationType: 'INVALID',
    }),
    { headers: hdrs }
  );
  check(lgBadTypeRes, {
    '[-] lot link invalid relationType → 400': (r) => r.status === 400,
  });

  // No tenant header → 4xx
  const lgNoTenantRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/lot-genealogy`,
    JSON.stringify({
      parentBatchId: '01a090ae-611e-7001-a690-2682e4afcb55',
      childBatchId: '01a090ae-611e-7002-b3ac-c478c91b06ac',
      qty: 5,
      relationType: 'SPLIT',
    }),
    { headers: JSON_CT }
  );
  check(lgNoTenantRes, {
    '[-] lot genealogy no token → 401': (r) => r.status === 401,
  });

  // ── Gap #16: Physical Inventory ──────────────────────────────────────────

  // [+] Create physical inventory
  const piRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/physical-inventories`,
    JSON.stringify({ storeId: storeId, notes: 'k6 test count' }),
    { headers: hdrs }
  );
  check(piRes, { '[+] create physical inventory 201': (r) => r.status === 201 });
  const piId = piRes.status === 201 ? piRes.json('data.id') : null;

  // [+] List physical inventories
  check(
    http.get(`${baseUrl}/api/inventory-svc/admin/inventory/physical-inventories`, { headers: hdrs }),
    { '[+] list physical inventories 200': (r) => r.status === 200 }
  );

  if (piId && variantId) {
    // [+] Add a tag: the service records what the books hold now; a number sent is not used
    const tagRes = http.post(
      `${baseUrl}/api/inventory-svc/admin/inventory/physical-inventories/${piId}/tags`,
      JSON.stringify({ variantId: variantId, systemQty: 100000 }),
      { headers: hdrs }
    );
    check(tagRes, {
      '[+] add physical inventory tag 200': (r) => r.status === 200,
      '[+] the tag counts against the books, not the number sent': (r) =>
        r.status === 200 && Number(r.json('data.systemQty')) !== 100000,
    });
    const tagId = tagRes.status === 200 ? tagRes.json('data.id') : null;

    // [+] Get physical inventory (with tags)
    check(
      http.get(
        `${baseUrl}/api/inventory-svc/admin/inventory/physical-inventories/${piId}`,
        { headers: hdrs }
      ),
      { '[+] get physical inventory 200': (r) => r.status === 200 }
    );

    if (tagId) {
      // [+] Enter count
      const countRes = http.post(
        `${baseUrl}/api/inventory-svc/admin/inventory/physical-inventories/${piId}/tags/${tagId}/count`,
        JSON.stringify({ countedQty: 95 }),
        { headers: hdrs }
      );
      check(countRes, { '[+] count physical inventory tag 200': (r) => r.status === 200 });
    }

    // [+] Complete (applies adjustments)
    const completeRes = http.post(
      `${baseUrl}/api/inventory-svc/admin/inventory/physical-inventories/${piId}/complete`,
      null,
      { headers: hdrs }
    );
    check(completeRes, { '[+] complete physical inventory 200': (r) => r.status === 200 });

    // [-] Complete again → 409
    check(
      http.post(
        `${baseUrl}/api/inventory-svc/admin/inventory/physical-inventories/${piId}/complete`,
        null,
        { headers: hdrs }
      ),
      { '[-] complete already-completed PI 409': (r) => r.status === 409 }
    );
  }

  // [-] Create PI missing storeId → 400
  check(
    http.post(
      `${baseUrl}/api/inventory-svc/admin/inventory/physical-inventories`,
      JSON.stringify({ notes: 'no store' }),
      { headers: hdrs }
    ),
    { '[-] create PI missing storeId 400': (r) => r.status === 400 }
  );

  // [-] Add tag missing variantId → 400
  if (piId) {
    check(
      http.post(
        `${baseUrl}/api/inventory-svc/admin/inventory/physical-inventories/${piId}/tags`,
        JSON.stringify({ systemQty: 10 }),
        { headers: hdrs }
      ),
      { '[-] add tag missing variantId 400': (r) => r.status === 400 }
    );
  }

  // [-] No tenant header
  check(
    http.post(
      `${baseUrl}/api/inventory-svc/admin/inventory/physical-inventories`,
      JSON.stringify({ storeId: storeId }),
      { headers: JSON_CT }
    ),
    { '[-] create PI no token 401': (r) => r.status === 401 }
  );

  // ── Gap #17: Costing Methods ────────────────────────────────────────────────

  // [+] Upsert costing method AVERAGE
  const costingRes = http.put(
    `${baseUrl}/api/inventory-svc/admin/inventory/costing-methods`,
    JSON.stringify({ storeId: storeId, variantId: variantId, method: 'AVERAGE' }),
    { headers: hdrs }
  );
  check(costingRes, { '[+] upsert costing method 200': (r) => r.status === 200 });

  // [+] Get costing method by variant
  check(
    http.get(
      `${baseUrl}/api/inventory-svc/admin/inventory/costing-methods/by-variant?store=${storeId}&variant=${variantId}`,
      { headers: hdrs }
    ),
    { '[+] get costing method by variant 200': (r) => r.status === 200 }
  );

  // [+] List costing methods for store
  check(
    http.get(
      `${baseUrl}/api/inventory-svc/admin/inventory/costing-methods?store=${storeId}`,
      { headers: hdrs }
    ),
    { '[+] list costing methods 200': (r) => r.status === 200 }
  );

  // [+] Switch to FIFO
  check(
    http.put(
      `${baseUrl}/api/inventory-svc/admin/inventory/costing-methods`,
      JSON.stringify({ storeId: storeId, variantId: variantId, method: 'FIFO' }),
      { headers: hdrs }
    ),
    { '[+] switch costing method to FIFO 200': (r) => r.status === 200 }
  );

  // [-] Invalid method → 400
  check(
    http.put(
      `${baseUrl}/api/inventory-svc/admin/inventory/costing-methods`,
      JSON.stringify({ storeId: storeId, variantId: variantId, method: 'LIFO' }),
      { headers: hdrs }
    ),
    { '[-] upsert invalid costing method 400': (r) => r.status === 400 }
  );

  // [-] Missing variantId → 400
  check(
    http.put(
      `${baseUrl}/api/inventory-svc/admin/inventory/costing-methods`,
      JSON.stringify({ storeId: storeId, method: 'AVERAGE' }),
      { headers: hdrs }
    ),
    { '[-] upsert costing method missing variantId 400': (r) => r.status === 400 }
  );

  // ── Accounting Periods ──────────────────────────────────────────────────────

  // [+] Open an accounting period
  const periodRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/accounting-periods`,
    JSON.stringify({ storeId: storeId, periodName: 'June 2026', periodDate: '2026-06-01' }),
    { headers: hdrs }
  );
  check(periodRes, { '[+] open accounting period 201': (r) => r.status === 201 });
  const periodId = periodRes.status === 201 ? periodRes.json('data.id') : null;

  // [+] Get accounting period
  if (periodId) {
    check(
      http.get(
        `${baseUrl}/api/inventory-svc/admin/inventory/accounting-periods/${periodId}`,
        { headers: hdrs }
      ),
      { '[+] get accounting period 200': (r) => r.status === 200 }
    );
  }

  // [+] List accounting periods
  check(
    http.get(
      `${baseUrl}/api/inventory-svc/admin/inventory/accounting-periods?store=${storeId}`,
      { headers: hdrs }
    ),
    { '[+] list accounting periods 200': (r) => r.status === 200 }
  );

  if (periodId) {
    // [+] Close the accounting period
    check(
      http.post(
        `${baseUrl}/api/inventory-svc/admin/inventory/accounting-periods/${periodId}/close`,
        null,
        { headers: hdrs }
      ),
      { '[+] close accounting period 200': (r) => r.status === 200 }
    );

    // [-] Close already-closed period → 409
    check(
      http.post(
        `${baseUrl}/api/inventory-svc/admin/inventory/accounting-periods/${periodId}/close`,
        null,
        { headers: hdrs }
      ),
      { '[-] close already-closed period 409': (r) => r.status === 409 }
    );
  }

  // [-] Open period missing storeId → 400
  check(
    http.post(
      `${baseUrl}/api/inventory-svc/admin/inventory/accounting-periods`,
      JSON.stringify({ periodName: 'July 2026', periodDate: '2026-07-01' }),
      { headers: hdrs }
    ),
    { '[-] open period missing storeId 400': (r) => r.status === 400 }
  );

  // ── Gap #18: Kanban Replenishment ───────────────────────────────────────────

  // [+] Create SUPPLIER kanban card
  const kanbanRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/kanban-cards`,
    JSON.stringify({
      storeId: storeId,
      variantId: variantId,
      kanbanType: 'SUPPLIER',
      reorderQty: 50,
      supplierRef: 'SUP-001',
      notes: 'k6 test card'
    }),
    { headers: hdrs }
  );
  check(kanbanRes, { '[+] create SUPPLIER kanban card 201': (r) => r.status === 201 });
  const kanbanId = kanbanRes.status === 201 ? kanbanRes.json('data.id') : null;

  // [+] Create INTER_ORG kanban card
  const kanbanRes2 = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/kanban-cards`,
    JSON.stringify({
      storeId: storeId,
      variantId: variantId,
      kanbanType: 'INTER_ORG',
      reorderQty: 30,
      notes: 'inter-org card'
    }),
    { headers: hdrs }
  );
  check(kanbanRes2, { '[+] create INTER_ORG kanban card 201': (r) => r.status === 201 });
  const kanbanId2 = kanbanRes2.status === 201 ? kanbanRes2.json('data.id') : null;

  // [+] List kanban cards
  check(
    http.get(
      `${baseUrl}/api/inventory-svc/admin/inventory/kanban-cards?store=${storeId}`,
      { headers: hdrs }
    ),
    { '[+] list kanban cards 200': (r) => r.status === 200 }
  );

  // [+] Get kanban card by id
  if (kanbanId) {
    check(
      http.get(
        `${baseUrl}/api/inventory-svc/admin/inventory/kanban-cards/${kanbanId}`,
        { headers: hdrs }
      ),
      { '[+] get kanban card 200': (r) => r.status === 200 }
    );

    // [+] Trigger kanban card
    const triggerRes = http.post(
      `${baseUrl}/api/inventory-svc/admin/inventory/kanban-cards/${kanbanId}/trigger`,
      JSON.stringify({ notes: 'triggered by k6' }),
      { headers: hdrs }
    );
    check(triggerRes, { '[+] trigger kanban card 200': (r) => r.status === 200 });

    // [+] Replenish kanban card
    if (triggerRes.status === 200) {
      check(
        http.post(
          `${baseUrl}/api/inventory-svc/admin/inventory/kanban-cards/${kanbanId}/replenish`,
          null,
          { headers: hdrs }
        ),
        { '[+] replenish kanban card 200': (r) => r.status === 200 }
      );

      // [-] Trigger already-replenished card → 409
      check(
        http.post(
          `${baseUrl}/api/inventory-svc/admin/inventory/kanban-cards/${kanbanId}/trigger`,
          JSON.stringify({ notes: 'should fail' }),
          { headers: hdrs }
        ),
        { '[-] trigger non-EMPTY kanban 409': (r) => r.status === 409 }
      );
    }
  }

  // [+] List kanban cards filtered by status
  check(
    http.get(
      `${baseUrl}/api/inventory-svc/admin/inventory/kanban-cards?store=${storeId}&status=EMPTY`,
      { headers: hdrs }
    ),
    { '[+] list kanban cards by status 200': (r) => r.status === 200 }
  );

  // [-] Create kanban missing storeId → 400
  check(
    http.post(
      `${baseUrl}/api/inventory-svc/admin/inventory/kanban-cards`,
      JSON.stringify({ variantId: variantId, kanbanType: 'SUPPLIER', reorderQty: 10 }),
      { headers: hdrs }
    ),
    { '[-] create kanban missing storeId 400': (r) => r.status === 400 }
  );

  // [-] Create kanban invalid type → 400
  check(
    http.post(
      `${baseUrl}/api/inventory-svc/admin/inventory/kanban-cards`,
      JSON.stringify({ storeId: storeId, variantId: variantId, kanbanType: 'INVALID', reorderQty: 10 }),
      { headers: hdrs }
    ),
    { '[-] create kanban invalid type 400': (r) => r.status === 400 }
  );

  // [-] Get non-existent kanban card → 404
  check(
    http.get(
      `${baseUrl}/api/inventory-svc/admin/inventory/kanban-cards/01a090ae-611e-7009-93d3-a36b72cdedb8`,
      { headers: hdrs }
    ),
    { '[-] get unknown kanban card 404': (r) => r.status === 404 }
  );

  // ── Gap #19: Reorder Point + EOQ ────────────────────────────────────────────

  // [+] Upsert ROP plan
  const ropRes = http.put(
    `${baseUrl}/api/inventory-svc/admin/inventory/rop-plans`,
    JSON.stringify({
      storeId: storeId,
      variantId: variantId,
      leadTimeDays: 7,
      orderingCost: 50.00,
      holdingCostPct: 0.20,
      unitCost: 10.00
    }),
    { headers: hdrs }
  );
  check(ropRes, { '[+] upsert ROP plan 200': (r) => r.status === 200 });

  // [+] Get ROP plan by variant
  check(
    http.get(
      `${baseUrl}/api/inventory-svc/admin/inventory/rop-plans/by-variant?store=${storeId}&variant=${variantId}`,
      { headers: hdrs }
    ),
    { '[+] get ROP plan by variant 200': (r) => r.status === 200 }
  );

  // [+] List ROP plans for store
  check(
    http.get(
      `${baseUrl}/api/inventory-svc/admin/inventory/rop-plans?store=${storeId}`,
      { headers: hdrs }
    ),
    { '[+] list ROP plans 200': (r) => r.status === 200 }
  );

  // [+] Compute ROP plans (may compute 0 if no demand buckets; still 200)
  check(
    http.post(
      `${baseUrl}/api/inventory-svc/admin/inventory/rop-plans/compute?store=${storeId}`,
      null,
      { headers: hdrs }
    ),
    { '[+] compute ROP plans 200': (r) => r.status === 200 }
  );

  // [-] Upsert ROP missing storeId → 400
  check(
    http.put(
      `${baseUrl}/api/inventory-svc/admin/inventory/rop-plans`,
      JSON.stringify({ variantId: variantId, leadTimeDays: 7, orderingCost: 50, holdingCostPct: 0.2, unitCost: 10 }),
      { headers: hdrs }
    ),
    { '[-] upsert ROP missing storeId 400': (r) => r.status === 400 }
  );

  // [-] Get unknown ROP plan → 404
  check(
    http.get(
      `${baseUrl}/api/inventory-svc/admin/inventory/rop-plans/by-variant?store=${storeId}&variant=01a090ae-611e-7008-b3d2-2b7638fc65c1`,
      { headers: hdrs }
    ),
    { '[-] get unknown ROP plan 404': (r) => r.status === 404 }
  );

  // ── Picking Rules (Gap #38) ───────────────────────────────────────────────

  // [+] Create FEFO picking rule
  const prRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/picking-rules`,
    JSON.stringify({ name: `fefo-rule-${Date.now()}`, strategy: 'FEFO' }),
    { headers: hdrs }
  );
  check(prRes, { '[+] create picking rule FEFO 201': (r) => r.status === 201 });
  const pickingRuleId = prRes.status === 201 ? prRes.json('data.id') : null;

  // [+] Create ZONE_PRIORITY rule
  const zpRuleRes = http.post(
    `${baseUrl}/api/inventory-svc/admin/inventory/picking-rules`,
    JSON.stringify({ name: `zone-rule-${Date.now()}`, strategy: 'ZONE_PRIORITY' }),
    { headers: hdrs }
  );
  const zpRuleId = zpRuleRes.status === 201 ? zpRuleRes.json('data.id') : null;

  // [+] List picking rules
  check(
    http.get(`${baseUrl}/api/inventory-svc/admin/inventory/picking-rules`, { headers: hdrs }),
    { '[+] list picking rules 200': (r) => r.status === 200 }
  );

  if (pickingRuleId) {
    // [+] Get picking rule by id
    check(
      http.get(`${baseUrl}/api/inventory-svc/admin/inventory/picking-rules/${pickingRuleId}`, { headers: hdrs }),
      { '[+] get picking rule 200': (r) => r.status === 200 }
    );

    // [+] Assign rule to GLOBAL scope
    const assignRes = http.post(
      `${baseUrl}/api/inventory-svc/admin/inventory/picking-rule-assignments`,
      JSON.stringify({ ruleId: pickingRuleId, scopeType: 'GLOBAL' }),
      { headers: hdrs }
    );
    check(assignRes, { '[+] assign picking rule GLOBAL 201': (r) => r.status === 201 });
    const assignId = assignRes.status === 201 ? assignRes.json('data.id') : null;

    // [+] List assignments
    check(
      http.get(`${baseUrl}/api/inventory-svc/admin/inventory/picking-rule-assignments`, { headers: hdrs }),
      { '[+] list picking rule assignments 200': (r) => r.status === 200 }
    );

    // [+] Resolve rule for store+variant
    if (storeId && variantId) {
      const resolveRes = http.get(
        `${baseUrl}/api/inventory-svc/admin/inventory/picking-rules/resolve?store=${storeId}&variant=${variantId}`,
        { headers: hdrs }
      );
      check(resolveRes, {
        '[+] resolve picking rule 200': (r) => r.status === 200,
        '[+] resolve returns strategy': (r) => {
          try { return r.json('data.strategy') !== null; } catch (_) { return false; }
        },
      });
    }

    if (assignId) {
      check(
        http.del(`${baseUrl}/api/inventory-svc/admin/inventory/picking-rule-assignments/${assignId}`, null, { headers: hdrs }),
        { '[+] delete picking rule assignment 204': (r) => r.status === 204 }
      );
    }

    // [+] Deactivate picking rule
    check(
      http.del(`${baseUrl}/api/inventory-svc/admin/inventory/picking-rules/${pickingRuleId}`, null, { headers: hdrs }),
      { '[+] deactivate picking rule 200': (r) => r.status === 200 }
    );
  }

  if (zpRuleId) {
    // [+] Set zone priorities on ZONE_PRIORITY rule
    check(
      http.put(
        `${baseUrl}/api/inventory-svc/admin/inventory/picking-rules/${zpRuleId}/zone-priorities`,
        JSON.stringify({ zonePriorities: [{ zoneId: '01a090ae-611e-7001-a690-2682e4afcb55', priority: 1 }] }),
        { headers: hdrs }
      ),
      { '[+] set zone priorities 200': (r) => r.status === 200 }
    );

    check(
      http.get(`${baseUrl}/api/inventory-svc/admin/inventory/picking-rules/${zpRuleId}/zone-priorities`, { headers: hdrs }),
      { '[+] list zone priorities 200': (r) => r.status === 200 }
    );
  }

  // [-] Create picking rule invalid strategy → 400
  check(
    http.post(
      `${baseUrl}/api/inventory-svc/admin/inventory/picking-rules`,
      JSON.stringify({ name: 'bad', strategy: 'RANDOM' }),
      { headers: hdrs }
    ),
    { '[-] create picking rule invalid strategy 400': (r) => r.status === 400 }
  );

  // [-] Create picking rule missing name → 400
  check(
    http.post(
      `${baseUrl}/api/inventory-svc/admin/inventory/picking-rules`,
      JSON.stringify({ strategy: 'FIFO' }),
      { headers: hdrs }
    ),
    { '[-] create picking rule missing name 400': (r) => r.status === 400 }
  );

  // [-] Assign STORE scope without scopeId → 400
  if (pickingRuleId) {
    check(
      http.post(
        `${baseUrl}/api/inventory-svc/admin/inventory/picking-rule-assignments`,
        JSON.stringify({ ruleId: pickingRuleId, scopeType: 'STORE' }),
        { headers: hdrs }
      ),
      { '[-] assign picking rule STORE no scopeId 400': (r) => r.status === 400 }
    );
  }

  // [-] Get unknown picking rule → 404
  check(
    http.get(
      `${baseUrl}/api/inventory-svc/admin/inventory/picking-rules/01a090ae-611e-7000-9e1a-0f8a9e565153`,
      { headers: hdrs }
    ),
    { '[-] get unknown picking rule 404': (r) => r.status === 404 }
  );

  // [-] Resolve without required params → 400
  check(
    http.get(`${baseUrl}/api/inventory-svc/admin/inventory/picking-rules/resolve`, { headers: hdrs }),
    { '[-] resolve picking rule missing params 400': (r) => r.status === 400 }
  );

  // [-] No tenant → 401
  check(
    http.post(
      `${baseUrl}/api/inventory-svc/admin/inventory/picking-rules`,
      JSON.stringify({ name: 'x', strategy: 'FIFO' }),
      { headers: { 'Content-Type': 'application/json' } }
    ),
    { '[-] create picking rule no token 401': (r) => r.status === 401 }
  );

  // ── Serial numbers: a registration stores exactly what it answers ─────────
  const serialBatch = (() => {
    try {
      const rows = JSON.parse(http.get(`${baseUrl}/api/inventory-svc/admin/inventory/batches?store=${storeId}&variant=${variantId}&limit=1`, { headers: hdrs }).body).data || [];
      return rows[0] ? rows[0].id : null;
    } catch (_) { return null; }
  })();
  const serialsUrl = `${baseUrl}/api/inventory-svc/admin/inventory/serials`;
  const registerSerials = (b, headers = hdrs) => http.post(`${serialsUrl}/register`, JSON.stringify({ batchId: serialBatch, storeId, variantId, ...b }), { headers });
  const generated = registerSerials({ autoQty: 3, prefix: 'K6' });
  check(generated, { '[+] three generated serial numbers are registered': (r) => r.status === 201 && (JSON.parse(r.body).data || []).length === 3 });
  const generatedIds = (() => { try { return (JSON.parse(generated.body).data || []).map((x) => x.id); } catch (_) { return []; } })();
  check(generated, {
    '[+] ...and every one answered is stored, readable by its id': () => generatedIds.length === 3 && generatedIds.every((id) => http.get(`${serialsUrl}/${id}`, { headers: hdrs }).status === 200),
  });
  const own = `K6-OWN-${Date.now().toString(36).toUpperCase()}`;
  check(registerSerials({ serials: [own] }), { '[+] a serial number of our own is registered': (r) => r.status === 201 });
  check(registerSerials({ serials: [own] }), {
    '[-] the same number again is refused whole, never dropped': (r) => r.status === 409 && String(r.body).includes('SERIAL_ALREADY_REGISTERED') && String(r.body).includes(own),
  });
  check(registerSerials({ serials: [`${own}-B`, `${own}-B`] }), { '[-] a number repeated within one request is refused': (r) => r.status === 409 && String(r.body).includes('SERIAL_ALREADY_REGISTERED') });
  check(http.get(`${serialsUrl}/lookup?serial_no=${own}-B`, { headers: hdrs }), { '[-] ...and nothing of the refused request was stored': (r) => r.status === 404 });
  const rivalHdrs = { ...JSON_CT, Authorization: `Bearer ${d.rival.owner.token}` };
  check(registerSerials({ serials: [`${own}-R`] }, rivalHdrs), { "[-] another business cannot register serials against our batch": (r) => r.status === 403 || r.status === 404 });
  check(http.get(`${serialsUrl}/lookup?serial_no=${own}-R`, { headers: hdrs }), { '[-] ...and nothing was stored for it': (r) => r.status === 404 });
}
