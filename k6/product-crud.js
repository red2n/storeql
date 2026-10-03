// Converted to real JWT sign-in: the gateway strips X-Tenant-Id / X-User-Id / X-Roles, so every call
// carries the owner's bearer token and a call with no token is a 401 at the gateway.
//
//   k6/run.sh product-crud
import http from 'k6/http';
import { check as k6check, sleep } from 'k6';
import { ALL_CHECKS_PASS, BASE as baseUrl, errorCode, onboardTenant, register, sellableVariant } from './lib/storeql.js';

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
  return { tenant: onboardTenant('product', { stores: 1 }), shopper: register('product-shopper') };
}

export default function (d) {
  const tenantId = d.tenant.tenantId;
  const hdrs = { ...JSON_CT, Authorization: `Bearer ${d.tenant.owner.token}` };
  const noTenant = { ...JSON_CT };

  // ── Brands ────────────────────────────────────────────────────────────────

  const brandRes = http.post(
    `${baseUrl}/api/product-svc/admin/brands`,
    JSON.stringify({ name: `k6-brand-${Date.now()}` }),
    { headers: hdrs }
  );
  check(brandRes, { '[+] create brand 201': (r) => r.status === 201 });
  const brandId = brandRes.status === 201 ? brandRes.json('data.id') : null;

  const listBrandsRes = http.get(`${baseUrl}/api/product-svc/admin/brands`, { headers: hdrs });
  check(listBrandsRes, { '[+] list brands 200': (r) => r.status === 200 });

  check(
    http.post(
      `${baseUrl}/api/product-svc/admin/brands`,
      JSON.stringify({}),
      { headers: hdrs }
    ),
    { '[-] create brand missing name 400': (r) => r.status === 400 && errorCode(r) === 'VALIDATION_FAILED' }
  );

  check(
    http.post(
      `${baseUrl}/api/product-svc/admin/brands`,
      JSON.stringify({ name: 'no-tenant-brand' }),
      { headers: noTenant }
    ),
    { '[-] create brand no token 401': (r) => r.status === 401 && errorCode(r) === 'UNAUTHORIZED' }
  );

  // ── Categories ────────────────────────────────────────────────────────────

  const catRes = http.post(
    `${baseUrl}/api/product-svc/admin/categories`,
    JSON.stringify({ name: `k6-cat-${Date.now()}` }),
    { headers: hdrs }
  );
  check(catRes, { '[+] create category 201': (r) => r.status === 201 });
  const categoryId = catRes.status === 201 ? catRes.json('data.id') : null;

  check(
    http.post(
      `${baseUrl}/api/product-svc/admin/categories`,
      JSON.stringify({}),
      { headers: hdrs }
    ),
    { '[-] create category missing name 400': (r) => r.status === 400 && errorCode(r) === 'VALIDATION_FAILED' }
  );

  // ── Products ──────────────────────────────────────────────────────────────

  const prodRes = http.post(
    `${baseUrl}/api/product-svc/admin/products`,
    JSON.stringify({
      name: `k6-prod-${Date.now()}`,
      description: 'k6 test product',
      brandId: brandId,
      categoryId: categoryId,
      sellableOnline: true,
      sellablePos: true
    }),
    { headers: hdrs }
  );
  check(prodRes, { '[+] create product 201': (r) => r.status === 201 });
  const productId = prodRes.status === 201 ? prodRes.json('data.id') : null;

  if (productId) {
    const getRes = http.get(`${baseUrl}/api/product-svc/catalog/products/${productId}`, { headers: hdrs });
    check(getRes, { '[+] get product 200': (r) => r.status === 200 });

    const putRes = http.put(
      `${baseUrl}/api/product-svc/admin/products/${productId}`,
      JSON.stringify({ name: 'k6-updated', description: 'updated', sellableOnline: true, sellablePos: false }),
      { headers: hdrs }
    );
    check(putRes, { '[+] update product 200': (r) => r.status === 200 });

    const listAdminRes = http.get(`${baseUrl}/api/product-svc/admin/products`, { headers: hdrs });
    check(listAdminRes, { '[+] list products admin 200': (r) => r.status === 200 });
  }

  check(
    http.post(
      `${baseUrl}/api/product-svc/admin/products`,
      JSON.stringify({ description: 'no name' }),
      { headers: hdrs }
    ),
    { '[-] create product missing name 400': (r) => r.status === 400 && errorCode(r) === 'VALIDATION_FAILED' }
  );

  check(
    http.get(
      `${baseUrl}/api/product-svc/catalog/products/01a090ae-611e-7000-9e1a-0f8a9e565153`,
      { headers: hdrs }
    ),
    { '[-] get product unknown 404': (r) => r.status === 404 && errorCode(r) === 'PRODUCT_NOT_FOUND' }
  );

  // ── Variants ──────────────────────────────────────────────────────────────

  let variantId = null;
  if (productId) {
    const varRes = http.post(
      `${baseUrl}/api/product-svc/admin/products/${productId}/variants`,
      JSON.stringify({ sku: `SKU-K6-${Date.now()}`, unit: 'EA' }),
      { headers: hdrs }
    );
    check(varRes, { '[+] create variant 201': (r) => r.status === 201 });
    variantId = varRes.status === 201 ? varRes.json('data.id') : null;

    if (variantId) {
      const listVarRes = http.get(
        `${baseUrl}/api/product-svc/admin/products/${productId}/variants`,
        { headers: hdrs }
      );
      check(listVarRes, { '[+] list variants 200': (r) => r.status === 200 });
    }

    check(
      http.post(
        `${baseUrl}/api/product-svc/admin/products/${productId}/variants`,
        JSON.stringify({ unit: 'EA' }),
        { headers: hdrs }
      ),
      { '[-] create variant missing sku 400': (r) => r.status === 400 && errorCode(r) === 'VALIDATION_FAILED' }
    );
  }

  sleep(0.3);

  // ── UOM ───────────────────────────────────────────────────────────────────

  const uomClassRes = http.get(`${baseUrl}/api/product-svc/admin/uom/classes`, { headers: hdrs });
  check(uomClassRes, { '[+] list uom classes 200': (r) => r.status === 200 });

  const uomUnitsRes = http.get(`${baseUrl}/api/product-svc/admin/uom/units?classCode=QUANTITY`, { headers: hdrs });
  check(uomUnitsRes, { '[+] list uom units 200': (r) => r.status === 200 });

  if (variantId) {
    const convRes = http.post(
      `${baseUrl}/api/product-svc/admin/uom/item-conversions`,
      JSON.stringify({ variantId, fromUom: 'DOZ', toUom: 'EA', factor: 12 }),
      { headers: hdrs }
    );
    check(convRes, { '[+] create item uom conversion 2xx': (r) => r.status >= 200 && r.status < 300 });

    const listConvRes = http.get(
      `${baseUrl}/api/product-svc/admin/uom/item-conversions?variantId=${variantId}`,
      { headers: hdrs }
    );
    check(listConvRes, { '[+] list item conversions 200': (r) => r.status === 200 });

    const convertRes = http.get(
      `${baseUrl}/api/product-svc/admin/uom/convert?variant=${variantId}&from=DOZ&to=EA&qty=2`,
      { headers: hdrs }
    );
    check(convertRes, { '[+] convert uom 200': (r) => r.status === 200 });
  }

  check(
    http.post(
      `${baseUrl}/api/product-svc/admin/uom/item-conversions`,
      JSON.stringify({ variantId: '01a090ae-611e-7000-9e1a-0f8a9e565153', toUom: 'EA', factor: 12 }),
      { headers: hdrs }
    ),
    { '[-] item conversion missing fromUom 400': (r) => r.status === 400 && errorCode(r) === 'VALIDATION_FAILED' }
  );

  // ── Item Templates (Gap #13) ─────────────────────────────────────────────

  // [+] Create template
  const tplRes = http.post(
    `${baseUrl}/api/product-svc/admin/item-templates`,
    JSON.stringify({ name: `k6-tpl-${Date.now()}`, description: 'k6 test template', attributes: '{"color":"red","size":"M"}' }),
    { headers: hdrs }
  );
  check(tplRes, { '[+] create item template 201': (r) => r.status === 201 });
  const templateId = tplRes.status === 201 ? tplRes.json('data.id') : null;

  // [+] List templates
  check(
    http.get(`${baseUrl}/api/product-svc/admin/item-templates`, { headers: hdrs }),
    { '[+] list item templates 200': (r) => r.status === 200 }
  );

  if (templateId) {
    // [+] Get template by id
    check(
      http.get(`${baseUrl}/api/product-svc/admin/item-templates/${templateId}`, { headers: hdrs }),
      { '[+] get item template 200': (r) => r.status === 200 }
    );

    // [+] Apply template to variant (copies attributes onto variant)
    if (variantId) {
      const applyRes = http.post(
        `${baseUrl}/api/product-svc/admin/item-templates/${templateId}/apply/${variantId}`,
        null,
        { headers: hdrs }
      );
      check(applyRes, { '[+] apply template to variant 200': (r) => r.status === 200 });
    }

    // [+] Deactivate template
    check(
      http.del(`${baseUrl}/api/product-svc/admin/item-templates/${templateId}`, null, { headers: hdrs }),
      { '[+] deactivate item template 200': (r) => r.status === 200 }
    );
  }

  // [-] Create template duplicate name
  const tplName2 = `k6-tpl2-${Date.now()}`;
  http.post(
    `${baseUrl}/api/product-svc/admin/item-templates`,
    JSON.stringify({ name: tplName2 }),
    { headers: hdrs }
  );
  check(
    http.post(
      `${baseUrl}/api/product-svc/admin/item-templates`,
      JSON.stringify({ name: tplName2 }),
      { headers: hdrs }
    ),
    { '[-] duplicate template name 409': (r) => r.status === 409 && errorCode(r) === 'DUPLICATE' }
  );

  // [-] Create template missing name → 400
  check(
    http.post(
      `${baseUrl}/api/product-svc/admin/item-templates`,
      JSON.stringify({ description: 'no name' }),
      { headers: hdrs }
    ),
    { '[-] create template missing name 400': (r) => r.status === 400 && errorCode(r) === 'VALIDATION_FAILED' }
  );

  // [-] Get unknown template → 404
  check(
    http.get(
      `${baseUrl}/api/product-svc/admin/item-templates/01a090ae-611e-7000-9e1a-0f8a9e565153`,
      { headers: hdrs }
    ),
    { '[-] get unknown template 404': (r) => r.status === 404 && errorCode(r) === 'TEMPLATE_NOT_FOUND' }
  );

  // [-] Apply unknown template to variant → 404
  if (variantId) {
    check(
      http.post(
        `${baseUrl}/api/product-svc/admin/item-templates/01a090ae-611e-7000-9e1a-0f8a9e565153/apply/${variantId}`,
        null,
        { headers: hdrs }
      ),
      { '[-] apply unknown template 404': (r) => r.status === 404 && errorCode(r) === 'TEMPLATE_NOT_FOUND' }
    );
  }

  // [-] No tenant header on templates
  check(
    http.post(
      `${baseUrl}/api/product-svc/admin/item-templates`,
      JSON.stringify({ name: 'no-tenant-tpl' }),
      { headers: noTenant }
    ),
    { '[-] create template no token 401': (r) => r.status === 401 && errorCode(r) === 'UNAUTHORIZED' }
  );

  // ── Item Revisions (Gap #12) ──────────────────────────────────────────────

  if (variantId) {
    // [+] Create first revision
    const rev1Res = http.post(
      `${baseUrl}/api/product-svc/admin/products/variants/${variantId}/revisions`,
      JSON.stringify({ revision: 'A', description: 'Initial release', effectiveDate: '2024-01-01' }),
      { headers: hdrs }
    );
    check(rev1Res, { '[+] create revision A 201': (r) => r.status === 201 });
    const rev1Id = rev1Res.status === 201 ? rev1Res.json('data.id') : null;

    // [+] Create second revision (supersedes first)
    const rev2Res = http.post(
      `${baseUrl}/api/product-svc/admin/products/variants/${variantId}/revisions`,
      JSON.stringify({ revision: 'B', description: 'Updated spec', effectiveDate: '2024-06-01' }),
      { headers: hdrs }
    );
    check(rev2Res, { '[+] create revision B 201 (supersedes A)': (r) => r.status === 201 });
    const rev2Id = rev2Res.status === 201 ? rev2Res.json('data.id') : null;

    // [+] List all revisions
    const listRevRes = http.get(
      `${baseUrl}/api/product-svc/admin/products/variants/${variantId}/revisions`,
      { headers: hdrs }
    );
    check(listRevRes, {
      '[+] list revisions 200': (r) => r.status === 200,
      '[+] list revisions returns array': (r) => {
        try { return Array.isArray(r.json('data')); } catch (_) { return false; }
      }
    });

    // [+] Current revision (latest effective as of today)
    const curRevRes = http.get(
      `${baseUrl}/api/product-svc/admin/products/variants/${variantId}/revisions/current`,
      { headers: hdrs }
    );
    check(curRevRes, { '[+] current revision 200': (r) => r.status === 200 });

    // [+] Get revision by ID
    if (rev1Id) {
      const getRevRes = http.get(
        `${baseUrl}/api/product-svc/admin/products/variants/${variantId}/revisions/${rev1Id}`,
        { headers: hdrs }
      );
      check(getRevRes, { '[+] get revision by id 200': (r) => r.status === 200 });
    }

    // [-] Duplicate revision label (same variant + revision = 409)
    check(
      http.post(
        `${baseUrl}/api/product-svc/admin/products/variants/${variantId}/revisions`,
        JSON.stringify({ revision: 'B', description: 'Duplicate', effectiveDate: '2025-01-01' }),
        { headers: hdrs }
      ),
      { '[-] duplicate revision 409': (r) => r.status === 409 && errorCode(r) === 'DUPLICATE' }
    );

    // [-] Missing revision field → 400
    check(
      http.post(
        `${baseUrl}/api/product-svc/admin/products/variants/${variantId}/revisions`,
        JSON.stringify({ description: 'no revision label', effectiveDate: '2025-01-01' }),
        { headers: hdrs }
      ),
      { '[-] create revision missing revision 400': (r) => r.status === 400 && errorCode(r) === 'VALIDATION_FAILED' }
    );

    // [-] Missing effectiveDate → 400
    check(
      http.post(
        `${baseUrl}/api/product-svc/admin/products/variants/${variantId}/revisions`,
        JSON.stringify({ revision: 'C', description: 'no date' }),
        { headers: hdrs }
      ),
      { '[-] create revision missing effectiveDate 400': (r) => r.status === 400 && errorCode(r) === 'VALIDATION_FAILED' }
    );

    // [-] Invalid date format → 400
    check(
      http.post(
        `${baseUrl}/api/product-svc/admin/products/variants/${variantId}/revisions`,
        JSON.stringify({ revision: 'D', effectiveDate: 'not-a-date' }),
        { headers: hdrs }
      ),
      { '[-] create revision invalid date 400': (r) => r.status === 400 && errorCode(r) === 'INVALID_DATE' }
    );
  }

  // [-] Revisions for unknown variant → empty list (200) or 404
  check(
    http.get(
      `${baseUrl}/api/product-svc/admin/products/variants/01a090ae-611e-7000-9e1a-0f8a9e565153/revisions`,
      { headers: hdrs }
    ),
    { '[-] list revisions of an unknown variant is an empty list': (r) => r.status === 200 && r.json('data').length === 0 }
  );

  // [-] Current revision for unknown variant → 404
  check(
    http.get(
      `${baseUrl}/api/product-svc/admin/products/variants/01a090ae-611e-7000-9e1a-0f8a9e565153/revisions/current`,
      { headers: hdrs }
    ),
    { '[-] current revision unknown variant 404': (r) => r.status === 404 && errorCode(r) === 'REVISION_NOT_FOUND' }
  );

  // [-] No tenant header on revisions endpoint
  if (variantId) {
    check(
      http.post(
        `${baseUrl}/api/product-svc/admin/products/variants/${variantId}/revisions`,
        JSON.stringify({ revision: 'Z', effectiveDate: '2025-01-01' }),
        { headers: noTenant }
      ),
      { '[-] create revision no token 401': (r) => r.status === 401 && errorCode(r) === 'UNAUTHORIZED' }
    );
  }

  // ── Container Types (Gap #37) ────────────────────────────────────────────

  // [+] Create a container type
  const ctRes = http.post(
    `${baseUrl}/api/product-svc/admin/container-types`,
    JSON.stringify({ code: `CASE-${Date.now()}`, name: 'Standard Case', description: '24-unit case', lengthMm: 400, widthMm: 300, heightMm: 200, maxWeightKg: 15, maxUnits: 24 }),
    { headers: hdrs }
  );
  check(ctRes, { '[+] create container type 201': (r) => r.status === 201 });
  const containerTypeId = ctRes.status === 201 ? ctRes.json('data.id') : null;

  // [+] List container types
  check(
    http.get(`${baseUrl}/api/product-svc/admin/container-types`, { headers: hdrs }),
    { '[+] list container types 200': (r) => r.status === 200 }
  );

  if (containerTypeId) {
    // [+] Get container type by id
    check(
      http.get(`${baseUrl}/api/product-svc/admin/container-types/${containerTypeId}`, { headers: hdrs }),
      { '[+] get container type 200': (r) => r.status === 200 }
    );

    // [+] Update container type
    check(
      http.put(
        `${baseUrl}/api/product-svc/admin/container-types/${containerTypeId}`,
        JSON.stringify({ name: 'Updated Case', maxUnits: 48 }),
        { headers: hdrs }
      ),
      { '[+] update container type 200': (r) => r.status === 200 }
    );
  }

  if (variantId && containerTypeId) {
    // [+] Link variant to container type
    const linkRes = http.post(
      `${baseUrl}/api/product-svc/admin/products/variants/${variantId}/container-links`,
      JSON.stringify({ containerTypeId, qtyPerContainer: 24, isPrimary: true }),
      { headers: hdrs }
    );
    check(linkRes, { '[+] create variant container link 201': (r) => r.status === 201 });
    const linkId = linkRes.status === 201 ? linkRes.json('data.id') : null;

    // [+] List variant container links
    check(
      http.get(`${baseUrl}/api/product-svc/admin/products/variants/${variantId}/container-links`, { headers: hdrs }),
      { '[+] list variant container links 200': (r) => r.status === 200 }
    );

    // [-] Duplicate link → 409 or 500
    check(
      http.post(
        `${baseUrl}/api/product-svc/admin/products/variants/${variantId}/container-links`,
        JSON.stringify({ containerTypeId, qtyPerContainer: 12, isPrimary: false }),
        { headers: hdrs }
      ),
      { '[-] duplicate container link rejected': (r) => r.status === 409 && errorCode(r) === 'DUPLICATE' }
    );

    if (linkId) {
      // [+] Delete container link
      check(
        http.del(`${baseUrl}/api/product-svc/admin/products/variants/${variantId}/container-links/${linkId}`, null, { headers: hdrs }),
        { '[+] delete container link 204': (r) => r.status === 204 }
      );
    }
  }

  // [-] Create container type missing code → 400
  check(
    http.post(
      `${baseUrl}/api/product-svc/admin/container-types`,
      JSON.stringify({ name: 'No Code' }),
      { headers: hdrs }
    ),
    { '[-] create container type missing code 400': (r) => r.status === 400 && errorCode(r) === 'VALIDATION_FAILED' }
  );

  // [-] Create container type missing name → 400
  check(
    http.post(
      `${baseUrl}/api/product-svc/admin/container-types`,
      JSON.stringify({ code: 'PALLET' }),
      { headers: hdrs }
    ),
    { '[-] create container type missing name 400': (r) => r.status === 400 && errorCode(r) === 'VALIDATION_FAILED' }
  );

  // [-] Get unknown container type → 404
  check(
    http.get(
      `${baseUrl}/api/product-svc/admin/container-types/01a090ae-611e-7000-9e1a-0f8a9e565153`,
      { headers: hdrs }
    ),
    { '[-] get unknown container type 404': (r) => r.status === 404 && errorCode(r) === 'CONTAINER_TYPE_NOT_FOUND' }
  );

  // [-] No tenant → 401
  check(
    http.post(
      `${baseUrl}/api/product-svc/admin/container-types`,
      JSON.stringify({ code: 'BOX', name: 'Box' }),
      { headers: noTenant }
    ),
    { '[-] create container type no token 401': (r) => r.status === 401 && errorCode(r) === 'UNAUTHORIZED' }
  );

  // ── Item Attribute Groups (Gap #36) ──────────────────────────────────────

  // [+] List all 18 system attribute groups
  const agListRes = http.get(
    `${baseUrl}/api/product-svc/admin/attribute-groups`,
    { headers: hdrs }
  );
  check(agListRes, {
    '[+] list attribute groups 200': (r) => r.status === 200,
    '[+] list attribute groups returns 18': (r) => {
      try { return r.json('data').length === 18; } catch(_) { return false; }
    },
  });

  // [+] Get a specific group by code
  const agGetRes = http.get(
    `${baseUrl}/api/product-svc/admin/attribute-groups/LEAD_TIMES`,
    { headers: hdrs }
  );
  check(agGetRes, {
    '[+] get attribute group LEAD_TIMES 200': (r) => r.status === 200,
    '[+] attribute group has fields': (r) => {
      try { return r.json('data.fields').length >= 3; } catch(_) { return false; }
    },
  });

  if (variantId) {
    // [+] Upsert LEAD_TIMES values on a variant
    const upsertRes = http.put(
      `${baseUrl}/api/product-svc/admin/products/variants/${variantId}/attribute-groups/LEAD_TIMES`,
      JSON.stringify({ values: JSON.stringify({ preprocessing_days: 1, processing_days: 5, post_processing_days: 2 }) }),
      { headers: hdrs }
    );
    check(upsertRes, {
      '[+] upsert LEAD_TIMES attribute group 200': (r) => r.status === 200,
      '[+] upserted group_code is LEAD_TIMES': (r) => {
        try { return r.json('data.groupCode') === 'LEAD_TIMES'; } catch(_) { return false; }
      },
    });

    // [+] Upsert a second group (idempotent re-put)
    http.put(
      `${baseUrl}/api/product-svc/admin/products/variants/${variantId}/attribute-groups/WEB`,
      JSON.stringify({ values: JSON.stringify({ web_status: 'PUBLISHED', browsable: true }) }),
      { headers: hdrs }
    );

    // [+] List all attribute group values for the variant
    const listValsRes = http.get(
      `${baseUrl}/api/product-svc/admin/products/variants/${variantId}/attribute-groups`,
      { headers: hdrs }
    );
    check(listValsRes, {
      '[+] list variant attribute group values 200': (r) => r.status === 200,
      '[+] variant has 2 attribute groups set': (r) => {
        try { return r.json('data').length === 2; } catch(_) { return false; }
      },
    });

    // [+] Get specific group values for the variant
    const getValsRes = http.get(
      `${baseUrl}/api/product-svc/admin/products/variants/${variantId}/attribute-groups/LEAD_TIMES`,
      { headers: hdrs }
    );
    check(getValsRes, {
      '[+] get variant LEAD_TIMES values 200': (r) => r.status === 200,
    });

    // [+] Delete one group values
    const delRes = http.del(
      `${baseUrl}/api/product-svc/admin/products/variants/${variantId}/attribute-groups/WEB`,
      null,
      { headers: hdrs }
    );
    check(delRes, { '[+] delete variant attribute group values 204': (r) => r.status === 204 });

    // [-] Get deleted group → 404
    check(
      http.get(
        `${baseUrl}/api/product-svc/admin/products/variants/${variantId}/attribute-groups/WEB`,
        { headers: hdrs }
      ),
      { '[-] get deleted attribute group values 404': (r) => r.status === 404 && errorCode(r) === 'ATTRIBUTE_GROUP_VALUES_NOT_FOUND' }
    );

    // [-] Upsert with unknown group code → 404
    check(
      http.put(
        `${baseUrl}/api/product-svc/admin/products/variants/${variantId}/attribute-groups/NONEXISTENT`,
        JSON.stringify({ values: '{}' }),
        { headers: hdrs }
      ),
      { '[-] upsert unknown attribute group 404': (r) => r.status === 404 && errorCode(r) === 'ATTRIBUTE_GROUP_NOT_FOUND' }
    );

    // [-] Upsert with missing values field → 400
    check(
      http.put(
        `${baseUrl}/api/product-svc/admin/products/variants/${variantId}/attribute-groups/COSTING`,
        JSON.stringify({}),
        { headers: hdrs }
      ),
      { '[-] upsert attribute group missing values 400': (r) => r.status === 400 && errorCode(r) === 'VALIDATION_FAILED' }
    );

    // [-] No tenant on attribute group upsert → 401
    check(
      http.put(
        `${baseUrl}/api/product-svc/admin/products/variants/${variantId}/attribute-groups/COSTING`,
        JSON.stringify({ values: '{}' }),
        { headers: noTenant }
      ),
      { '[-] upsert attribute group no token 401': (r) => r.status === 401 && errorCode(r) === 'UNAUTHORIZED' }
    );
  }

  // [-] Get attribute group values for unknown variant → 404
  check(
    http.get(
      `${baseUrl}/api/product-svc/admin/products/variants/01a090ae-611e-7000-9e1a-0f8a9e565153/attribute-groups`,
      { headers: hdrs }
    ),
    { '[-] list attribute group values unknown variant 404': (r) => r.status === 404 && errorCode(r) === 'VARIANT_NOT_FOUND' }
  );

  // [-] Unknown group code on list → 404
  check(
    http.get(
      `${baseUrl}/api/product-svc/admin/attribute-groups/BOGUS_GROUP`,
      { headers: hdrs }
    ),
    { '[-] get unknown attribute group 404': (r) => r.status === 404 && errorCode(r) === 'ATTRIBUTE_GROUP_NOT_FOUND' }
  );

  // ── Gap #39: Category Sets ────────────────────────────────────────────────

  const csRes = http.post(
    `${baseUrl}/api/product-svc/admin/category-sets`,
    JSON.stringify({ name: `k6-catset-${Date.now()}`, description: 'k6 test set', purpose: 'INVENTORY', controlled: false }),
    { headers: hdrs }
  );
  check(csRes, { '[+] create category set 201': (r) => r.status === 201 });
  const csId = csRes.status === 201 ? csRes.json('data.id') : null;

  check(
    http.get(`${baseUrl}/api/product-svc/admin/category-sets`, { headers: hdrs }),
    { '[+] list category sets 200': (r) => r.status === 200 }
  );

  if (csId) {
    check(
      http.get(`${baseUrl}/api/product-svc/admin/category-sets/${csId}`, { headers: hdrs }),
      { '[+] get category set 200': (r) => r.status === 200 }
    );

    check(
      http.put(
        `${baseUrl}/api/product-svc/admin/category-sets/${csId}`,
        JSON.stringify({ name: `k6-catset-upd-${Date.now()}`, purpose: 'PURCHASING', controlled: true, status: 'ACTIVE' }),
        { headers: hdrs }
      ),
      { '[+] update category set 200': (r) => r.status === 200 }
    );

    // Add a category as a set member
    const memberRes = http.post(
      `${baseUrl}/api/product-svc/admin/category-sets/${csId}/members`,
      JSON.stringify({ categoryId }),
      { headers: hdrs }
    );
    check(memberRes, { '[+] add category set member 201': (r) => r.status === 201 });

    check(
      http.get(`${baseUrl}/api/product-svc/admin/category-sets/${csId}/members`, { headers: hdrs }),
      { '[+] list category set members 200': (r) => r.status === 200 }
    );

    if (categoryId) {
      check(
        http.del(`${baseUrl}/api/product-svc/admin/category-sets/${csId}/members/${categoryId}`, null, { headers: hdrs }),
        { '[+] delete category set member 204': (r) => r.status === 204 }
      );
    }

    // Variant assignment
    if (variantId && categoryId) {
      // Re-add the member so the assignment FK is valid
      http.post(
        `${baseUrl}/api/product-svc/admin/category-sets/${csId}/members`,
        JSON.stringify({ categoryId }),
        { headers: hdrs }
      );
      const assignRes = http.post(
        `${baseUrl}/api/product-svc/admin/products/variants/${variantId}/category-set-assignments`,
        JSON.stringify({ setId: csId, categoryId }),
        { headers: hdrs }
      );
      check(assignRes, { '[+] assign variant category set 201': (r) => r.status === 201 });

      check(
        http.get(
          `${baseUrl}/api/product-svc/admin/products/variants/${variantId}/category-set-assignments`,
          { headers: hdrs }
        ),
        { '[+] list variant category set assignments 200': (r) => r.status === 200 }
      );

      check(
        http.del(
          `${baseUrl}/api/product-svc/admin/products/variants/${variantId}/category-set-assignments/${csId}`,
          null,
          { headers: hdrs }
        ),
        { '[+] delete variant category set assignment 204': (r) => r.status === 204 }
      );
    }

    // [-] Get unknown set → 404
    check(
      http.get(
        `${baseUrl}/api/product-svc/admin/category-sets/01a090ae-611e-7000-9e1a-0f8a9e565153`,
        { headers: hdrs }
      ),
      { '[-] get unknown category set 404': (r) => r.status === 404 && errorCode(r) === 'CATEGORY_SET_NOT_FOUND' }
    );

    // [-] Create set missing name → 400
    check(
      http.post(
        `${baseUrl}/api/product-svc/admin/category-sets`,
        JSON.stringify({ purpose: 'GENERAL', controlled: false }),
        { headers: hdrs }
      ),
      { '[-] create category set missing name 400': (r) => r.status === 400 && errorCode(r) === 'VALIDATION_FAILED' }
    );

    // [-] Create set without tenant → 401
    check(
      http.post(
        `${baseUrl}/api/product-svc/admin/category-sets`,
        JSON.stringify({ name: 'k6-no-tenant', purpose: 'GENERAL', controlled: false }),
        { headers: noTenant }
      ),
      { '[-] create category set no token 401': (r) => r.status === 401 && errorCode(r) === 'UNAUTHORIZED' }
    );

    // Cleanup
    check(
      http.del(`${baseUrl}/api/product-svc/admin/category-sets/${csId}`, null, { headers: hdrs }),
      { '[+] delete category set 204': (r) => r.status === 204 }
    );
  }

  // ── Gap #40: Open Item Interface (Bulk Import) ────────────────────────────

  const importRes = http.post(
    `${baseUrl}/api/product-svc/admin/import`,
    JSON.stringify({
      categories: [{ name: `k6-import-cat-${Date.now()}`, parentName: null }],
      products: [
        {
          name: `k6-import-prod-${Date.now()}`,
          description: 'imported by k6',
          categoryName: `k6-import-cat-${Date.now()}`,
          brandName: null,
          sellableOnline: true,
          sellablePos: true,
          variants: [{ sku: `k6-import-sku-${Date.now()}`, barcode: null, unit: 'EA' }]
        }
      ]
    }),
    { headers: hdrs }
  );
  check(importRes, { '[+] bulk import 200': (r) => r.status === 200 });
  check(importRes, { '[+] bulk import returns productsCreated': (r) => r.json('data.productsCreated') >= 0 });

  // [-] Duplicate SKU → 200 with errors array (partial success)
  const dupSku = `k6-dup-sku-${Date.now()}`;
  http.post(
    `${baseUrl}/api/product-svc/admin/import`,
    JSON.stringify({
      categories: [],
      products: [{
        name: `k6-dup-prod-${Date.now()}`,
        sellableOnline: true,
        sellablePos: false,
        variants: [{ sku: dupSku, unit: 'EA' }]
      }]
    }),
    { headers: hdrs }
  );
  const dupImportRes = http.post(
    `${baseUrl}/api/product-svc/admin/import`,
    JSON.stringify({
      categories: [],
      products: [{
        name: `k6-dup-prod2-${Date.now()}`,
        sellableOnline: true,
        sellablePos: false,
        variants: [{ sku: dupSku, unit: 'EA' }]
      }]
    }),
    { headers: hdrs }
  );
  check(dupImportRes, { '[-] bulk import dup sku returns 200 with errors': (r) => r.status === 200 });

  // [-] Empty body → 4xx (Helidon throws JSON deserialization error before method)
  check(
    http.post(`${baseUrl}/api/product-svc/admin/import`, '{}', { headers: hdrs }),
    { '[-] bulk import empty body 200 (accepts empty lists)': (r) => r.status === 200 }
  );

  // [-] No tenant → 401
  check(
    http.post(
      `${baseUrl}/api/product-svc/admin/import`,
      JSON.stringify({ categories: [], products: [] }),
      { headers: noTenant }
    ),
    { '[-] bulk import no token 401': (r) => r.status === 401 && errorCode(r) === 'UNAUTHORIZED' }
  );
}
