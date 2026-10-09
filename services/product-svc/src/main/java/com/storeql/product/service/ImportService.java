package com.storeql.product.service;

import com.storeql.ids.Ids;
import com.storeql.product.client.Caller;
import com.storeql.product.client.PricingClient;
import com.storeql.product.domain.imports.CsvException;
import com.storeql.product.domain.imports.CsvTable;
import com.storeql.product.domain.imports.DryRun;
import com.storeql.product.domain.imports.ImportItem;
import com.storeql.product.domain.imports.ImportMapping;
import com.storeql.product.domain.imports.ImportMappingJson;
import com.storeql.product.domain.imports.ImportRecords.Job;
import com.storeql.product.domain.imports.ImportRecords.Mapping;
import com.storeql.product.domain.imports.ImportRecords.RowResult;
import com.storeql.product.domain.imports.ImportRows;
import com.storeql.product.repo.ImportRepository;
import com.storeql.product.repo.ProductRepository;
import com.storeql.service.Entitlements;
import com.storeql.service.Fx;
import com.storeql.service.TenantProfiles;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The catalogue import's front half (intent/catalogue-import.md): save a mapping, take a file, and
 * judge it against what the business already has without writing a thing of the catalogue — the dry
 * run whose report the owner reads before anything is applied.
 *
 * <p>Only an owner or a manager of the whole business imports (a manager held to stores sees {@code
 * 403 BUSINESS_WIDE_ONLY}): an import sets what every store sells.
 */
@ApplicationScoped
public class ImportService {

  /** The most bytes one import takes. */
  public static final int MAX_BYTES = 12 * 1024 * 1024;

  /** The most data rows one import takes. */
  public static final int MAX_ROWS = 25_000;

  @Inject ImportRepository repo;
  @Inject ProductRepository products;
  @Inject TenantProfiles profiles;
  @Inject PricingClient pricing;
  @Inject Entitlements entitlements;

  // ── access ───────────────────────────────────────────────────────────────────

  private UUID require(TenantContext ctx) {
    UUID tenantId = ctx.requireTenantId();
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
    CatalogueStores.requireBusinessWide(
        ctx,
        "An import sets what every store sells, so it is for an owner or a manager of the whole business");
    return tenantId;
  }

  // ── mappings ─────────────────────────────────────────────────────────────────

  /**
   * Saves a mapping under its name.
   *
   * @throws ApiException 400 {@code IMPORT_MAPPING_INVALID} listing what is wrong with it
   */
  public Mapping saveMapping(TenantContext ctx, String name, ImportMapping mapping) {
    UUID tenantId = require(ctx);
    String clean = name == null ? "" : name.trim();
    if (clean.isEmpty() || clean.length() > 100) {
      throw ApiException.badRequest(
          "IMPORT_MAPPING_INVALID", "a mapping's name is 1 to 100 characters");
    }
    List<String> problems = mapping.problems();
    if (!problems.isEmpty()) {
      throw new ApiException(
          400, "IMPORT_MAPPING_INVALID", String.join("; ", problems), problems, null);
    }
    String json = ImportMappingJson.write(mapping);
    return repo.saveMapping(
        tenantId, clean, json, sha256(json.getBytes(StandardCharsets.UTF_8)), ctx.userId());
  }

  public Mapping getMapping(TenantContext ctx, String name) {
    UUID tenantId = require(ctx);
    return repo.findMapping(tenantId, name)
        .orElseThrow(
            () -> ApiException.notFound("IMPORT_MAPPING_NOT_FOUND", "no mapping is named " + name));
  }

  public List<Mapping> listMappings(TenantContext ctx) {
    return repo.listMappings(require(ctx));
  }

  // ── the dry run ──────────────────────────────────────────────────────────────

  /**
   * Takes a file and judges it: the file is kept (once), every row is read and checked, and the
   * report is saved. Nothing of the catalogue, its prices or its stock is written.
   *
   * @param storeId the store the file is of (one store per file)
   * @param priceListId the price list the prices are meant for, or null
   * @param key the caller's idempotency key: a retry answers with the first job
   * @return the job, with its report
   * @throws ApiException 400 on a file that cannot be read as a table ({@code IMPORT_FILE_EMPTY},
   *     {@code IMPORT_CSV_MALFORMED}, {@code IMPORT_TOO_MANY_ROWS}); 413 {@code
   *     IMPORT_FILE_TOO_LARGE}; 404 for a mapping or a store that is not the business's
   */
  public Job dryRun(
      TenantContext ctx,
      UUID storeId,
      String fileName,
      byte[] bytes,
      String mappingName,
      UUID priceListId,
      String key) {
    UUID tenantId = require(ctx);
    if (key != null) {
      var earlier = repo.findJobByKey(tenantId, key);
      if (earlier.isPresent()) return earlier.get();
    }
    if (bytes == null || bytes.length > MAX_BYTES) {
      throw new ApiException(
          413,
          "IMPORT_FILE_TOO_LARGE",
          "a file is at most 12 MB; split it and import the parts",
          List.of(),
          null);
    }
    CatalogueStores.require(profiles, ctx, Set.of(storeId));
    Mapping saved = getMapping(ctx, mappingName);
    ImportMapping mapping = ImportMappingJson.read(saved.json());

    CsvTable.Parsed table;
    try {
      table = CsvTable.parse(bytes, MAX_ROWS);
    } catch (CsvException e) {
      throw new ApiException(400, e.code(), e.getMessage(), List.of(), e);
    }
    String sha = sha256(bytes);
    String name = fileName == null || fileName.isBlank() ? "import.csv" : fileName.trim();
    var file =
        repo.saveFile(
            tenantId,
            storeId,
            name.length() > 200 ? name.substring(0, 200) : name,
            sha,
            bytes,
            table.encoding(),
            table.delimiter(),
            table.rows().size(),
            headersJson(table.headers()),
            ctx.userId());

    String starter = starterJson(Caller.of(ctx));
    var bound = mapping.bind(table.headers());
    if (!bound.ok()) {
      return repo.saveDryRun(
          tenantId,
          storeId,
          file.id(),
          saved,
          sha,
          priceListId,
          Job.DRY_RUN_FAILED,
          "{}",
          "IMPORT_MAPPING_HEADER_MISSING",
          "the file has no column headed " + String.join(", ", bound.missing()),
          starter,
          ctx.userId(),
          key,
          List.of(),
          v -> new String[] {"[]", "[]", null});
    }

    int scale = Fx.minorUnits(profiles.requireCurrency(tenantId));
    List<ImportRows.ReadRow> read =
        ImportRows.readAll(mapping, bound, table.rows(), table.headers().size(), scale);

    Set<String> skus = new LinkedHashSet<>();
    Set<String> codes = new LinkedHashSet<>();
    for (var r : read) {
      if (r.item() == null) continue;
      ImportItem i = r.item();
      skus.add(i.sku());
      if (i.gtin14() != null) codes.add(i.gtin14());
      i.aliases().forEach(a -> codes.add(a.gtin14()));
    }
    var existing = repo.existingBySku(tenantId, skus);
    var holders = repo.holdersOf(tenantId, codes);
    Set<String> rates = pricing.configuredVatCodes(Caller.of(ctx)).orElse(null);

    DryRun.Plan plan = DryRun.plan(read, existing, holders, rates);

    JsonObjectBuilder counts = Json.createObjectBuilder();
    counts.add("rows", plan.summary().rows());
    counts.add("byAction", ints(plan.summary().byAction()));
    counts.add("refusals", ints(plan.summary().refusals()));
    counts.add("gaps", ints(plan.summary().gaps()));
    counts.add("vatCodes", ints(plan.summary().vatCodes()));
    counts.add("newProducts", plan.summary().newProducts());
    counts.add("priceBasis", mapping.priceBasis());
    counts.add("productsNow", products.countProducts(tenantId));
    var limit = entitlements.limit(tenantId, Entitlements.PRODUCTS_MAX);
    if (limit.isPresent()) counts.add("productsLimit", limit.getAsLong());

    return repo.saveDryRun(
        tenantId,
        storeId,
        file.id(),
        saved,
        sha,
        priceListId,
        Job.DRY_RUN_DONE,
        counts.build().toString(),
        null,
        null,
        starter,
        ctx.userId(),
        key,
        plan.rows(),
        ImportService::verdictJson);
  }

  // ── the apply ────────────────────────────────────────────────────────────────

  private static final long DRY_RUN_VALID_HOURS = 24;

  /**
   * Queues the apply of a dry run. The work is done by {@link ImportWorker}, in chunks, and the job
   * is read for its progress.
   *
   * @param dryRunId the dry run to apply: the file and mapping it judged are applied, not whatever
   *     they have become
   * @param priceListId the price list the prices go on, or null to make one in the file's price
   *     basis; a named one must be of that basis
   * @param key the caller's idempotency key: a retry answers with the first job
   * @throws ApiException 404 {@code IMPORT_JOB_NOT_FOUND}; 409 {@code IMPORT_DRY_RUN_REQUIRED} (not
   *     a finished dry run, or its mapping changed since), {@code IMPORT_JOB_EXPIRED} (older than a
   *     day), {@code IMPORT_PRICE_BASIS_MISMATCH}, {@code IMPORT_ALREADY_RUNNING}, {@code
   *     PLAN_LIMIT_REACHED}
   */
  public Job apply(TenantContext ctx, UUID dryRunId, UUID priceListId, String key) {
    UUID tenantId = require(ctx);
    if (key != null) {
      var earlier = repo.findJobByKey(tenantId, key);
      if (earlier.isPresent()) return earlier.get();
    }
    Job dry = getJob(ctx, dryRunId);
    if (!Job.DRY_RUN.equals(dry.kind()) || !Job.DRY_RUN_DONE.equals(dry.status())) {
      throw ApiException.conflict(
          "IMPORT_DRY_RUN_REQUIRED",
          "only a finished dry run can be applied; run the file as a dry run first");
    }
    if (dry.createdAt()
        .isBefore(
            java.time.Instant.now()
                .minus(DRY_RUN_VALID_HOURS, java.time.temporal.ChronoUnit.HOURS))) {
      throw ApiException.conflict(
          "IMPORT_JOB_EXPIRED",
          "this dry run is more than a day old; run the file again to see what it would do now");
    }
    Mapping saved =
        repo.findMappingById(tenantId, dry.mappingId())
            .orElseThrow(
                () ->
                    ApiException.conflict(
                        "IMPORT_DRY_RUN_REQUIRED", "the mapping the dry run used is gone"));
    if (!saved.hash().equals(dry.mappingHash())) {
      throw ApiException.conflict(
          "IMPORT_DRY_RUN_REQUIRED",
          "the mapping changed since the dry run; run the file again under it");
    }
    ImportMapping mapping = ImportMappingJson.read(saved.json());

    // The plan's ceiling on products, against what the dry run would add and what is there now.
    var counts = parse(dry.counts());
    long adds = counts.getInt("newProducts", 0);
    var limit = entitlements.limit(tenantId, Entitlements.PRODUCTS_MAX);
    if (limit.isPresent()) {
      long have = products.countProducts(tenantId);
      if (have + adds > limit.getAsLong()) {
        throw Entitlements.limitReached(limit.getAsLong(), "products", have);
      }
    }

    boolean prices = mapping.columns().containsKey("price");
    boolean vat = mapping.columns().containsKey("vatCode") || mapping.defaultVatCode() != null;
    Caller caller = Caller.of(ctx);
    UUID listId = priceListId != null ? priceListId : dry.priceListId();
    if (prices) {
      if (listId != null) {
        var info = pricing.priceList(caller, listId.toString());
        if (!info.taxMode().equals(mapping.priceBasis())) {
          throw ApiException.conflict(
              "IMPORT_PRICE_BASIS_MISMATCH",
              "the file's prices are "
                  + ("INCLUSIVE".equals(mapping.priceBasis())
                      ? "shelf prices (VAT included)"
                      : "net prices")
                  + " and price list "
                  + listId
                  + " is the other kind; a price is never converted");
        }
      } else {
        String currency = profiles.requireCurrency(tenantId);
        listId =
            Ids.parse(
                pricing.createPriceList(
                    caller,
                    "Imported prices " + dry.id().toString().substring(24),
                    currency,
                    mapping.priceBasis()));
      }
    }

    List<Integer> lines = repo.applicableLines(tenantId, dry.id());
    List<String> phases = new java.util.ArrayList<>();
    if (!lines.isEmpty()) {
      phases.add("PRODUCTS");
      if (vat) phases.add("VAT");
      if (prices) phases.add("PRICES");
      if (mapping.columns().containsKey("stockQty")) phases.add("STOCK");
    }
    return repo.createApplyJob(
        dry, listId, starterJson(caller), ctx.userId(), key, lines, phases, ImportWorker.CHUNK);
  }

  private static jakarta.json.JsonObject parse(String json) {
    try (var r = Json.createReader(new java.io.StringReader(json == null ? "{}" : json))) {
      return r.readObject();
    }
  }

  // ── reads ────────────────────────────────────────────────────────────────────

  public Job getJob(TenantContext ctx, UUID id) {
    UUID tenantId = require(ctx);
    return repo.findJob(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("IMPORT_JOB_NOT_FOUND", "no import job " + id));
  }

  public List<Job> listJobs(TenantContext ctx, int limit) {
    return repo.listJobs(require(ctx), limit);
  }

  /** A page of a job's rows. */
  public List<RowResult> rows(TenantContext ctx, UUID id, String action, int afterLine, int limit) {
    getJob(ctx, id);
    return repo.rows(ctx.requireTenantId(), id, action, afterLine, limit);
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  static String[] verdictJson(DryRun.RowVerdict v) {
    JsonArrayBuilder refusals = Json.createArrayBuilder();
    v.refusals()
        .forEach(
            r ->
                refusals.add(
                    Json.createObjectBuilder().add("code", r.code()).add("detail", r.detail())));
    JsonArrayBuilder gaps = Json.createArrayBuilder();
    v.gaps().forEach(gaps::add);
    JsonArrayBuilder changes = Json.createArrayBuilder();
    v.changes().forEach(changes::add);
    return new String[] {
      refusals.build().toString(), gaps.build().toString(), changes.build().toString()
    };
  }

  private static JsonObjectBuilder ints(Map<String, Integer> m) {
    JsonObjectBuilder b = Json.createObjectBuilder();
    new java.util.TreeMap<>(m).forEach(b::add);
    return b;
  }

  private static String headersJson(List<String> headers) {
    JsonArrayBuilder b = Json.createArrayBuilder();
    headers.forEach(b::add);
    return b.build().toString();
  }

  /**
   * Who started a job, as the gateway described them: what the worker asks the other services as.
   */
  static String starterJson(Caller c) {
    JsonArrayBuilder roles = Json.createArrayBuilder();
    new java.util.TreeSet<>(c.roles()).forEach(roles::add);
    JsonArrayBuilder perms = Json.createArrayBuilder();
    new java.util.TreeSet<>(c.permissions()).forEach(perms::add);
    JsonArrayBuilder stores = Json.createArrayBuilder();
    c.storeIds().stream().map(UUID::toString).sorted().forEach(stores::add);
    JsonObjectBuilder b =
        Json.createObjectBuilder()
            .add("roles", roles)
            .add("permissions", perms)
            .add("storeIds", stores);
    if (c.userId() != null) b.add("userId", c.userId().toString());
    return b.build().toString();
  }

  static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
