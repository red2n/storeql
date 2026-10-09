package com.storeql.product.service;

import com.storeql.ids.Ids;
import com.storeql.product.client.Caller;
import com.storeql.product.client.PricingClient;
import com.storeql.product.domain.imports.CsvTable;
import com.storeql.product.domain.imports.ImportItem;
import com.storeql.product.domain.imports.ImportMapping;
import com.storeql.product.domain.imports.ImportMappingJson;
import com.storeql.product.domain.imports.ImportRecords.Job;
import com.storeql.product.domain.imports.ImportRows;
import com.storeql.product.repo.ImportRepository;
import com.storeql.product.repo.ImportRepository.Chunk;
import com.storeql.service.Fx;
import com.storeql.service.TenantProfiles;
import com.storeql.web.ApiException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Works the catalogue imports that are queued (intent/catalogue-import.md): claims a job under a
 * lease, runs its chunks in order — products, then VAT categories, then prices — each chunk marked
 * done on its own, and finishes the job. A worker that dies leaves its lease to run out and the
 * next claim resumes at the first unfinished chunk; every phase is idempotent, so no row is written
 * twice.
 *
 * <p>It acts for the person who started the job, as the gateway described them when they did (the
 * job keeps no token): the other services are asked as that caller, never as more. A peer that
 * cannot be reached leaves the chunk to be tried again when the lease runs out; a business refusal
 * (a VAT code with no rate, say) ends the job as FAILED with the peer's own code, and the same dry
 * run can be applied again once it is put right.
 */
@ApplicationScoped
public class ImportWorker {

  private static final Logger LOG = System.getLogger(ImportWorker.class.getName());

  /** Rows per chunk. */
  public static final int CHUNK = 500;

  private static final int LEASE_SECONDS = 60;

  @Inject ImportRepository repo;
  @Inject ImportApplier applier;
  @Inject PricingClient pricing;
  @Inject TenantProfiles profiles;

  @Inject
  @ConfigProperty(name = "storeql.product.import-worker.enabled", defaultValue = "true")
  boolean enabled;

  @Inject
  @ConfigProperty(name = "storeql.product.import-worker.seconds", defaultValue = "5")
  long seconds;

  private ScheduledExecutorService scheduler;
  private final ReentrantLock running = new ReentrantLock();

  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    /* eager CDI startup */
  }

  @PostConstruct
  void start() {
    if (!enabled) {
      LOG.log(Level.INFO, "Import worker disabled");
      return;
    }
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "product-import-worker");
              t.setDaemon(true);
              return t;
            });
    long every = Math.max(1L, seconds);
    scheduler.scheduleWithFixedDelay(this::tickQuietly, every, every, TimeUnit.SECONDS);
  }

  @PreDestroy
  void stop() {
    if (scheduler != null) scheduler.shutdownNow();
  }

  private void tickQuietly() {
    try {
      drain();
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Import worker tick failed: " + e.getMessage(), e);
    }
  }

  /** Works every claimable job to its end, one after another; how many chunks were run. */
  public int drain() {
    int chunks = 0;
    int taken;
    do {
      taken = runOnce();
      chunks += taken;
    } while (taken > 0);
    return chunks;
  }

  /**
   * Claims one job and works it until it is finished, fails, or has nothing left that can be done
   * now. The number of chunks run; zero when nothing was claimable.
   */
  public int runOnce() {
    // One worker at a time per process; across processes the claim's SKIP LOCKED does it.
    if (!running.tryLock()) return 0;
    try {
      repo.expireStale();
      Optional<Job> claimed = repo.claim(LEASE_SECONDS);
      if (claimed.isEmpty()) return 0;
      return work(claimed.get());
    } finally {
      running.unlock();
    }
  }

  // ── one job ──────────────────────────────────────────────────────────────────

  private int work(Job job) {
    UUID tenantId = job.tenantId();
    int run = 0;
    Cx cx;
    try {
      cx = Cx.load(repo, profiles, job);
    } catch (RuntimeException e) {
      LOG.log(
          Level.WARNING, "Import job " + job.id() + " could not be loaded: " + e.getMessage(), e);
      repo.finishJob(
          tenantId,
          job.id(),
          Job.FAILED,
          "IMPORT_FILE_UNREADABLE",
          "the file could not be read again",
          "{}");
      return 1;
    }
    while (true) {
      Optional<Chunk> next = repo.nextChunk(tenantId, job.id());
      if (next.isEmpty()) {
        repo.finishJob(
            tenantId, job.id(), Job.DONE, null, null, summarise(repo.chunksOf(tenantId, job.id())));
        return run + 1;
      }
      Chunk chunk = next.get();
      repo.renew(tenantId, job.id(), chunk.phase(), LEASE_SECONDS);
      try {
        String detail = process(job, cx, chunk);
        repo.finishChunk(tenantId, chunk.id(), "DONE", detail);
        run++;
      } catch (ApiException e) {
        if (e.status() >= 500) {
          // The peer is not there: leave the chunk, let the lease run out, try again then.
          LOG.log(Level.WARNING, "Import {0} waits: {1} {2}", job.id(), e.code(), e.getMessage());
          repo.finishChunkRetry(tenantId, job.id());
          return run + 1;
        }
        repo.finishChunk(tenantId, chunk.id(), "FAILED", message(e));
        repo.finishJob(
            tenantId,
            job.id(),
            Job.FAILED,
            e.code(),
            e.getMessage(),
            summarise(repo.chunksOf(tenantId, job.id())));
        return run + 1;
      }
    }
  }

  private String process(Job job, Cx cx, Chunk chunk) {
    List<ImportItem> items = cx.itemsOf(chunk);
    return switch (chunk.phase()) {
      case "PRODUCTS" -> {
        var done = applier.apply(job.tenantId(), items);
        JsonArrayBuilder errors = Json.createArrayBuilder();
        done.errors().stream().limit(50).forEach(errors::add);
        yield Json.createObjectBuilder()
            .add("created", done.created())
            .add("updated", done.updated())
            .add("unchanged", done.unchanged())
            .add("aliases", done.aliases())
            .add("errorCount", done.errors().size())
            .add("errors", errors)
            .build()
            .toString();
      }
      case "VAT" -> {
        Map<String, UUID> ids = repo.variantIdsBySku(job.tenantId(), skus(items));
        List<PricingClient.VatItem> batch = new ArrayList<>();
        for (ImportItem i : items) {
          UUID id = ids.get(i.sku());
          if (id != null && i.vatCode() != null)
            batch.add(new PricingClient.VatItem(id.toString(), i.vatCode()));
        }
        int assigned = pricing.setVatCategories(cx.caller(), batch);
        yield Json.createObjectBuilder().add("assigned", assigned).build().toString();
      }
      case "PRICES" -> {
        Map<String, UUID> ids = repo.variantIdsBySku(job.tenantId(), skus(items));
        List<PricingClient.PriceItem> batch = new ArrayList<>();
        for (ImportItem i : items) {
          UUID id = ids.get(i.sku());
          if (id != null && i.price() != null)
            batch.add(new PricingClient.PriceItem(id.toString(), i.price()));
        }
        var result = pricing.setPricesOn(cx.caller(), job.priceListId().toString(), batch);
        JsonArrayBuilder errors = Json.createArrayBuilder();
        result.errors().stream().limit(50).forEach(errors::add);
        yield Json.createObjectBuilder()
            .add("upserted", result.upserted())
            .add("errorCount", result.errors().size())
            .add("errors", errors)
            .build()
            .toString();
      }
      default ->
          throw new ApiException(
              500, "IMPORT_PHASE_UNKNOWN", "no such phase " + chunk.phase(), List.of(), null);
    };
  }

  private static Set<String> skus(List<ImportItem> items) {
    Set<String> out = new java.util.LinkedHashSet<>();
    items.forEach(i -> out.add(i.sku()));
    return out;
  }

  private static String message(ApiException e) {
    return Json.createObjectBuilder()
        .add("code", e.code())
        .add("message", e.getMessage())
        .build()
        .toString();
  }

  /** What the chunks did, added up; the first errors are kept for the report. */
  static String summarise(List<Chunk> chunks) {
    Map<String, Integer> sums = new LinkedHashMap<>();
    JsonArrayBuilder samples = Json.createArrayBuilder();
    int kept = 0;
    for (Chunk c : chunks) {
      if (c.detail() == null) continue;
      try (var r = Json.createReader(new StringReader(c.detail()))) {
        JsonObject d = r.readObject();
        for (String k :
            List.of(
                "created",
                "updated",
                "unchanged",
                "aliases",
                "assigned",
                "upserted",
                "errorCount")) {
          if (d.containsKey(k)) sums.merge(k, d.getInt(k), Integer::sum);
        }
        if (d.containsKey("errors")) {
          for (var e : d.getJsonArray("errors")) {
            if (kept++ < 50) samples.add(e);
          }
        }
      } catch (RuntimeException ignored) {
        // A chunk's detail that is not ours to read adds nothing.
      }
    }
    JsonObjectBuilder out = Json.createObjectBuilder();
    sums.forEach(out::add);
    out.add("chunks", chunks.size());
    out.add("chunksDone", (int) chunks.stream().filter(c -> "DONE".equals(c.status())).count());
    out.add("errors", samples);
    return out.build().toString();
  }

  // ── the job's file, read once ────────────────────────────────────────────────

  /** A job's file read into rows, and who is acting. */
  private record Cx(Map<Integer, ImportItem> byLine, Caller caller) {
    static Cx load(ImportRepository repo, TenantProfiles profiles, Job job) {
      byte[] bytes = repo.fileContent(job.tenantId(), job.fileId()).orElseThrow();
      var mapping = repo.findMappingById(job.tenantId(), job.mappingId()).orElseThrow();
      ImportMapping m = ImportMappingJson.read(mapping.json());
      CsvTable.Parsed table = CsvTable.parse(bytes, ImportService.MAX_ROWS);
      var bound = m.bind(table.headers());
      int scale = Fx.minorUnits(profiles.requireCurrency(job.tenantId()));
      Map<Integer, ImportItem> byLine = new HashMap<>();
      for (var r : ImportRows.readAll(m, bound, table.rows(), table.headers().size(), scale)) {
        if (r.item() != null) byLine.put(r.line(), r.item());
      }
      return new Cx(byLine, callerOf(job));
    }

    List<ImportItem> itemsOf(Chunk c) {
      List<ImportItem> out = new ArrayList<>();
      new java.util.TreeMap<>(byLine)
          .subMap(c.firstLine(), true, c.lastLine(), true)
          .values()
          .forEach(out::add);
      return out;
    }
  }

  /** The starter as the gateway described them, rebuilt from the job. */
  static Caller callerOf(Job job) {
    try (var r = Json.createReader(new StringReader(job.starter()))) {
      JsonObject o = r.readObject();
      Set<String> roles = new java.util.HashSet<>();
      o.getJsonArray("roles")
          .getValuesAs(jakarta.json.JsonString.class)
          .forEach(v -> roles.add(v.getString()));
      Set<String> perms = new java.util.HashSet<>();
      o.getJsonArray("permissions")
          .getValuesAs(jakarta.json.JsonString.class)
          .forEach(v -> perms.add(v.getString()));
      Set<UUID> stores = new java.util.HashSet<>();
      o.getJsonArray("storeIds")
          .getValuesAs(jakarta.json.JsonString.class)
          .forEach(v -> stores.add(Ids.parse(v.getString())));
      return new Caller(
          job.tenantId(),
          o.containsKey("userId") ? Ids.parse(o.getString("userId")) : null,
          roles,
          stores,
          perms,
          null);
    }
  }
}
