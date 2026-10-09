package com.storeql.product.repo;

import com.storeql.ids.Ids;
import com.storeql.product.domain.imports.DryRun;
import com.storeql.product.domain.imports.ImportRecords.File;
import com.storeql.product.domain.imports.ImportRecords.Job;
import com.storeql.product.domain.imports.ImportRecords.Mapping;
import com.storeql.product.domain.imports.ImportRecords.RowResult;
import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The catalogue import's tables (V17): mappings, files, jobs, their row results, and the reads a
 * dry run needs of the catalogue itself. Every statement names {@code tenant_id} first.
 */
@ApplicationScoped
public class ImportRepository extends BaseJdbcRepository {

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
    return t == null ? null : t.toInstant();
  }

  // ── mappings ──────────────────────────────────────────────────────────────

  /** Saves a mapping under its name, replacing the one of that name. */
  public Mapping saveMapping(UUID tenantId, String name, String json, String hash, UUID by) {
    UUID id = Ids.newId();
    exec(
        "INSERT INTO import_mappings (id,tenant_id,name,mapping,mapping_hash,created_by)"
            + " VALUES (?,?,?,?,?,?)"
            + " ON CONFLICT (tenant_id,name) DO UPDATE SET mapping=EXCLUDED.mapping,"
            + " mapping_hash=EXCLUDED.mapping_hash, updated_at=now()",
        ps -> {
          ps.setObject(1, id);
          ps.setObject(2, tenantId);
          ps.setString(3, name);
          ps.setString(4, json);
          ps.setString(5, hash);
          ps.setObject(6, by);
        },
        "save import mapping");
    return findMapping(tenantId, name).orElseThrow();
  }

  public Optional<Mapping> findMapping(UUID tenantId, String name) {
    return query(
            "SELECT id,tenant_id,name,mapping,mapping_hash,updated_at FROM import_mappings"
                + " WHERE tenant_id=? AND name=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, name);
            },
            ImportRepository::mapMapping,
            "find import mapping")
        .stream()
        .findFirst();
  }

  public Optional<Mapping> findMappingById(UUID tenantId, UUID id) {
    return query(
            "SELECT id,tenant_id,name,mapping,mapping_hash,updated_at FROM import_mappings"
                + " WHERE tenant_id=? AND id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            ImportRepository::mapMapping,
            "find import mapping by id")
        .stream()
        .findFirst();
  }

  public List<Mapping> listMappings(UUID tenantId) {
    return query(
        "SELECT id,tenant_id,name,mapping,mapping_hash,updated_at FROM import_mappings"
            + " WHERE tenant_id=? ORDER BY name",
        ps -> ps.setObject(1, tenantId),
        ImportRepository::mapMapping,
        "list import mappings");
  }

  private static Mapping mapMapping(ResultSet rs) throws SQLException {
    return new Mapping(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("name"),
        rs.getString("mapping"),
        rs.getString("mapping_hash"),
        instant(rs, "updated_at"));
  }

  // ── files ─────────────────────────────────────────────────────────────────

  /**
   * Keeps a file, once: the same bytes for the same store are the file already held, so a retried
   * upload makes no second copy.
   *
   * @return the file as held
   */
  public File saveFile(
      UUID tenantId,
      UUID storeId,
      String fileName,
      String sha256,
      byte[] content,
      String encoding,
      char delimiter,
      int rowCount,
      String headersJson,
      UUID by) {
    UUID id = Ids.newId();
    exec(
        "INSERT INTO import_files (id,tenant_id,store_id,file_name,sha256,size_bytes,content,"
            + "encoding,delimiter,row_count,headers,uploaded_by)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)"
            + " ON CONFLICT (tenant_id,store_id,sha256) DO NOTHING",
        ps -> {
          ps.setObject(1, id);
          ps.setObject(2, tenantId);
          ps.setObject(3, storeId);
          ps.setString(4, fileName);
          ps.setString(5, sha256);
          ps.setInt(6, content.length);
          ps.setBytes(7, content);
          ps.setString(8, encoding);
          ps.setString(9, String.valueOf(delimiter));
          ps.setInt(10, rowCount);
          ps.setString(11, headersJson);
          ps.setObject(12, by);
        },
        "save import file");
    return query(
            "SELECT id,tenant_id,store_id,file_name,sha256,size_bytes,encoding,delimiter,row_count,"
                + "created_at FROM import_files WHERE tenant_id=? AND store_id=? AND sha256=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, storeId);
              ps.setString(3, sha256);
            },
            ImportRepository::mapFile,
            "read import file")
        .get(0);
  }

  public Optional<File> findFile(UUID tenantId, UUID id) {
    return query(
            "SELECT id,tenant_id,store_id,file_name,sha256,size_bytes,encoding,delimiter,row_count,"
                + "created_at FROM import_files WHERE tenant_id=? AND id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            ImportRepository::mapFile,
            "find import file")
        .stream()
        .findFirst();
  }

  /** The file's bytes. */
  public Optional<byte[]> fileContent(UUID tenantId, UUID id) {
    return query(
            "SELECT content FROM import_files WHERE tenant_id=? AND id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            rs -> rs.getBytes(1),
            "read import file content")
        .stream()
        .findFirst();
  }

  private static File mapFile(ResultSet rs) throws SQLException {
    return new File(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getString("file_name"),
        rs.getString("sha256"),
        rs.getInt("size_bytes"),
        rs.getString("encoding"),
        rs.getString("delimiter").charAt(0),
        rs.getInt("row_count"),
        instant(rs, "created_at"));
  }

  // ── jobs and row results ──────────────────────────────────────────────────

  private static final String JOB_COLUMNS =
      "id,tenant_id,store_id,file_id,mapping_id,mapping_hash,file_sha256,kind,dry_run_of,"
          + "price_list_id,status,phase,counts,failure_code,failure_detail,starter,created_at,"
          + "finished_at";

  /**
   * Records a finished dry run with its row results, on one transaction: the report is whole or it
   * is not there.
   */
  public Job saveDryRun(
      UUID tenantId,
      UUID storeId,
      UUID fileId,
      Mapping mapping,
      String fileSha256,
      UUID priceListId,
      String status,
      String countsJson,
      String failureCode,
      String failureDetail,
      String starterJson,
      UUID by,
      String idempotencyKey,
      List<DryRun.RowVerdict> rows,
      java.util.function.Function<DryRun.RowVerdict, String[]> json) {
    UUID jobId = Ids.newId();
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO import_jobs (id,tenant_id,store_id,file_id,mapping_id,mapping_hash,"
                      + "file_sha256,kind,price_list_id,status,counts,failure_code,failure_detail,"
                      + "idempotency_key,started_by,starter,finished_at,last_progress_at)"
                      + " VALUES (?,?,?,?,?,?,?,'DRY_RUN',?,?,?,?,?,?,?,?,now(),now())")) {
            ps.setObject(1, jobId);
            ps.setObject(2, tenantId);
            ps.setObject(3, storeId);
            ps.setObject(4, fileId);
            ps.setObject(5, mapping.id());
            ps.setString(6, mapping.hash());
            ps.setString(7, fileSha256);
            ps.setObject(8, priceListId);
            ps.setString(9, status);
            ps.setString(10, countsJson);
            ps.setString(11, failureCode);
            ps.setString(12, failureDetail);
            ps.setString(13, idempotencyKey);
            ps.setObject(14, by);
            ps.setString(15, starterJson);
            ps.executeUpdate();
          }
          if (!rows.isEmpty()) {
            try (PreparedStatement ps =
                c.prepareStatement(
                    "INSERT INTO import_row_results (id,tenant_id,job_id,line,sku,action,refusals,"
                        + "gaps,changes) VALUES (?,?,?,?,?,?,?,?,?)")) {
              for (DryRun.RowVerdict v : rows) {
                String[] j = json.apply(v);
                ps.setObject(1, Ids.newId());
                ps.setObject(2, tenantId);
                ps.setObject(3, jobId);
                ps.setInt(4, v.line());
                ps.setString(5, v.sku());
                ps.setString(6, v.action());
                ps.setString(7, j[0]);
                ps.setString(8, j[1]);
                ps.setString(9, j[2]);
                ps.addBatch();
              }
              ps.executeBatch();
            }
          }
          return findJobTx(c, tenantId, jobId).orElseThrow();
        },
        "save dry run");
  }

  public Optional<Job> findJob(UUID tenantId, UUID id) {
    return inTx(c -> findJobTx(c, tenantId, id), "find import job");
  }

  public Optional<Job> findJobByKey(UUID tenantId, String key) {
    return query(
            "SELECT " + JOB_COLUMNS + " FROM import_jobs WHERE tenant_id=? AND idempotency_key=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, key);
            },
            ImportRepository::mapJob,
            "find import job by key")
        .stream()
        .findFirst();
  }

  public List<Job> listJobs(UUID tenantId, int limit) {
    return query(
        "SELECT "
            + JOB_COLUMNS
            + " FROM import_jobs WHERE tenant_id=?"
            + " ORDER BY created_at DESC, id DESC LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setInt(2, limit);
        },
        ImportRepository::mapJob,
        "list import jobs");
  }

  private static Optional<Job> findJobTx(Connection c, UUID tenantId, UUID id) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT " + JOB_COLUMNS + " FROM import_jobs WHERE tenant_id=? AND id=?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, id);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(mapJob(rs)) : Optional.empty();
      }
    }
  }

  private static Job mapJob(ResultSet rs) throws SQLException {
    return new Job(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("file_id", UUID.class),
        rs.getObject("mapping_id", UUID.class),
        rs.getString("mapping_hash"),
        rs.getString("file_sha256"),
        rs.getString("kind"),
        rs.getObject("dry_run_of", UUID.class),
        rs.getObject("price_list_id", UUID.class),
        rs.getString("status"),
        rs.getString("phase"),
        rs.getString("counts"),
        rs.getString("failure_code"),
        rs.getString("failure_detail"),
        rs.getString("starter"),
        instant(rs, "created_at"),
        instant(rs, "finished_at"));
  }

  /** A page of a job's row results in line order, optionally of one action. */
  public List<RowResult> rows(UUID tenantId, UUID jobId, String action, int afterLine, int limit) {
    return query(
        "SELECT id,line,sku,action,refusals,gaps,changes FROM import_row_results"
            + " WHERE tenant_id=? AND job_id=? AND line>?"
            + (action == null ? "" : " AND action=?")
            + " ORDER BY line LIMIT ?",
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          ps.setObject(i++, jobId);
          ps.setInt(i++, afterLine);
          if (action != null) ps.setString(i++, action);
          ps.setInt(i, limit);
        },
        rs ->
            new RowResult(
                rs.getObject("id", UUID.class),
                rs.getInt("line"),
                rs.getString("sku"),
                rs.getString("action"),
                rs.getString("refusals"),
                rs.getString("gaps"),
                rs.getString("changes")),
        "list import row results");
  }

  // ── apply jobs and their chunks ───────────────────────────────────────────

  /** One piece of an apply: a phase over a slice of the file's rows. */
  public record Chunk(
      UUID id,
      String phase,
      int seq,
      int firstLine,
      int lastLine,
      int rowCount,
      String status,
      String detail) {}

  /** The phases of an apply, in the order they run. */
  public static final List<String> PHASES = List.of("PRODUCTS", "VAT", "PRICES", "STOCK");

  /**
   * Queues an apply of a dry run, with its chunks, on one transaction: the work is whole or absent.
   *
   * @param lines the lines of the rows to apply (everything the dry run neither refused nor
   *     skipped), in order
   * @param phases the phases that have anything to do, in order
   * @throws com.storeql.web.ApiException 409 {@code IMPORT_ALREADY_RUNNING} when the business has
   *     an apply queued or running
   */
  public Job createApplyJob(
      Job dryRun,
      UUID priceListId,
      String starterJson,
      UUID by,
      String idempotencyKey,
      List<Integer> lines,
      List<String> phases,
      int chunkSize) {
    UUID jobId = Ids.newId();
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO import_jobs (id,tenant_id,store_id,file_id,mapping_id,mapping_hash,"
                      + "file_sha256,kind,dry_run_of,price_list_id,status,phase,counts,"
                      + "idempotency_key,started_by,starter,started_at,last_progress_at)"
                      + " VALUES (?,?,?,?,?,?,?,'APPLY',?,?,'QUEUED',?,'{}',?,?,?,now(),now())")) {
            ps.setObject(1, jobId);
            ps.setObject(2, dryRun.tenantId());
            ps.setObject(3, dryRun.storeId());
            ps.setObject(4, dryRun.fileId());
            ps.setObject(5, dryRun.mappingId());
            ps.setString(6, dryRun.mappingHash());
            ps.setString(7, dryRun.fileSha256());
            ps.setObject(8, dryRun.id());
            ps.setObject(9, priceListId);
            ps.setString(10, phases.isEmpty() ? null : phases.get(0));
            ps.setString(11, idempotencyKey);
            ps.setObject(12, by);
            ps.setString(13, starterJson);
            ps.executeUpdate();
          } catch (SQLException sqle) {
            if (UNIQUE_VIOLATION.equals(sqle.getSQLState())
                && String.valueOf(sqle.getMessage()).contains("uq_import_jobs_one_applying")) {
              throw new com.storeql.web.ApiException(
                  409,
                  "IMPORT_ALREADY_RUNNING",
                  "an import is already running for this business; wait for it to finish",
                  List.of(),
                  sqle);
            }
            throw sqle;
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO import_chunks (id,tenant_id,job_id,phase,seq,first_line,last_line,"
                      + "row_count) VALUES (?,?,?,?,?,?,?,?)")) {
            for (String phase : phases) {
              int seq = 0;
              for (int from = 0; from < lines.size(); from += chunkSize) {
                int to = Math.min(lines.size(), from + chunkSize);
                ps.setObject(1, Ids.newId());
                ps.setObject(2, dryRun.tenantId());
                ps.setObject(3, jobId);
                ps.setString(4, phase);
                ps.setInt(5, seq++);
                ps.setInt(6, lines.get(from));
                ps.setInt(7, lines.get(to - 1));
                ps.setInt(8, to - from);
                ps.addBatch();
              }
            }
            ps.executeBatch();
          }
          return findJobTx(c, dryRun.tenantId(), jobId).orElseThrow();
        },
        "queue import apply");
  }

  /**
   * Takes one job that needs working, if any: queued, or running with a lease that has run out (its
   * worker died). {@code SKIP LOCKED}, so two workers never take the same one.
   */
  public Optional<Job> claim(int leaseSeconds) {
    return inTx(
        c -> {
          UUID id = null;
          UUID tenant = null;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT id, tenant_id FROM import_jobs WHERE status IN ('QUEUED','APPLYING')"
                      + " AND (lease_until IS NULL OR lease_until < now())"
                      + " ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED")) {
            try (ResultSet rs = ps.executeQuery()) {
              if (rs.next()) {
                id = rs.getObject(1, UUID.class);
                tenant = rs.getObject(2, UUID.class);
              }
            }
          }
          if (id == null) return Optional.<Job>empty();
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE import_jobs SET status='APPLYING', lease_until = now() + (? * interval '1 second'),"
                      + " last_progress_at = now() WHERE tenant_id=? AND id=?")) {
            ps.setInt(1, leaseSeconds);
            ps.setObject(2, tenant);
            ps.setObject(3, id);
            ps.executeUpdate();
          }
          return findJobTx(c, tenant, id);
        },
        "claim import job");
  }

  /** Extends the lease of a job the worker is still working. */
  public void renew(UUID tenantId, UUID jobId, String phase, int leaseSeconds) {
    exec(
        "UPDATE import_jobs SET lease_until = now() + (? * interval '1 second'),"
            + " last_progress_at = now(), phase = ? WHERE tenant_id=? AND id=?",
        ps -> {
          ps.setInt(1, leaseSeconds);
          ps.setString(2, phase);
          ps.setObject(3, tenantId);
          ps.setObject(4, jobId);
        },
        "renew import job lease");
  }

  /** The first chunk not yet done, in phase order. */
  public Optional<Chunk> nextChunk(UUID tenantId, UUID jobId) {
    return query(
            "SELECT id,phase,seq,first_line,last_line,row_count,status,detail FROM import_chunks"
                + " WHERE tenant_id=? AND job_id=? AND status='PENDING'"
                + " ORDER BY CASE phase WHEN 'PRODUCTS' THEN 1 WHEN 'VAT' THEN 2 WHEN 'PRICES' THEN 3"
                + " ELSE 4 END, seq LIMIT 1",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, jobId);
            },
            ImportRepository::mapChunk,
            "next import chunk")
        .stream()
        .findFirst();
  }

  public List<Chunk> chunksOf(UUID tenantId, UUID jobId) {
    return query(
        "SELECT id,phase,seq,first_line,last_line,row_count,status,detail FROM import_chunks"
            + " WHERE tenant_id=? AND job_id=? ORDER BY CASE phase WHEN 'PRODUCTS' THEN 1"
            + " WHEN 'VAT' THEN 2 WHEN 'PRICES' THEN 3 ELSE 4 END, seq",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, jobId);
        },
        ImportRepository::mapChunk,
        "list import chunks");
  }

  private static Chunk mapChunk(ResultSet rs) throws SQLException {
    return new Chunk(
        rs.getObject("id", UUID.class),
        rs.getString("phase"),
        rs.getInt("seq"),
        rs.getInt("first_line"),
        rs.getInt("last_line"),
        rs.getInt("row_count"),
        rs.getString("status"),
        rs.getString("detail"));
  }

  /** Marks a chunk done (or failed) with what happened in it. */
  public void finishChunk(UUID tenantId, UUID chunkId, String status, String detail) {
    exec(
        "UPDATE import_chunks SET status=?, detail=?, done_at=now() WHERE tenant_id=? AND id=?",
        ps -> {
          ps.setString(1, status);
          ps.setString(2, detail);
          ps.setObject(3, tenantId);
          ps.setObject(4, chunkId);
        },
        "finish import chunk");
  }

  /**
   * A peer was not there: lets the job's lease run out so the next claim tries the same chunk
   * again, and notes that nothing was lost.
   */
  public void finishChunkRetry(UUID tenantId, UUID jobId) {
    exec(
        "UPDATE import_jobs SET lease_until = now() + interval '30 seconds' WHERE tenant_id=? AND id=?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, jobId);
        },
        "back off an import job");
  }

  /** Ends a job: done, or failed with the reason. */
  public void finishJob(
      UUID tenantId,
      UUID jobId,
      String status,
      String failureCode,
      String failureDetail,
      String countsJson) {
    exec(
        "UPDATE import_jobs SET status=?, failure_code=?, failure_detail=?, counts=?,"
            + " finished_at=now(), last_progress_at=now(), lease_until=NULL WHERE tenant_id=? AND id=?",
        ps -> {
          ps.setString(1, status);
          ps.setString(2, failureCode);
          ps.setString(3, failureDetail);
          ps.setString(4, countsJson);
          ps.setObject(5, tenantId);
          ps.setObject(6, jobId);
        },
        "finish import job");
  }

  /** Ends jobs nobody has moved for a day: an apply left half-done is not resumed a week later. */
  public int expireStale() {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE import_jobs SET status='EXPIRED', finished_at=now(), lease_until=NULL,"
                      + " failure_code='IMPORT_JOB_EXPIRED', failure_detail='nothing moved for 24 hours'"
                      + " WHERE status IN ('QUEUED','APPLYING')"
                      + " AND last_progress_at < now() - interval '24 hours'")) {
            return ps.executeUpdate();
          }
        },
        "expire stale import jobs");
  }

  /** The price list the business's most recent import priced into, if any. */
  public Optional<UUID> latestPriceList(UUID tenantId) {
    return query(
            "SELECT price_list_id FROM import_jobs WHERE tenant_id = ? AND kind = 'APPLY'"
                + " AND price_list_id IS NOT NULL ORDER BY created_at DESC, id DESC LIMIT 1",
            ps -> ps.setObject(1, tenantId),
            rs -> rs.getObject(1, UUID.class),
            "find the last import's price list")
        .stream()
        .findFirst();
  }

  /** The lines the dry run neither refused nor skipped, in order: what an apply works over. */
  public List<Integer> applicableLines(UUID tenantId, UUID dryRunId) {
    return query(
        "SELECT line FROM import_row_results WHERE tenant_id=? AND job_id=?"
            + " AND action IN ('CREATE','UPDATE','UNCHANGED') ORDER BY line",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, dryRunId);
        },
        rs -> rs.getInt(1),
        "list applicable import lines");
  }

  // ── the catalogue, as an apply reads and writes it ────────────────────────

  /** A variant as an apply sees it, with its product. */
  public record VariantRef(
      UUID variantId,
      UUID productId,
      String name,
      String description,
      String barcode,
      String gtin14,
      String unit,
      String soldBy,
      String netContentUom,
      UUID brandId,
      UUID categoryId,
      boolean sellableOnline,
      boolean sellablePos,
      String manufacturerPn,
      String attributes) {}

  public Optional<VariantRef> variantBySku(UUID tenantId, String sku) {
    return query(
            "SELECT v.id, v.product_id, p.name, p.description, v.barcode, v.gtin14, v.unit, v.sold_by,"
                + " v.net_content_uom, p.brand_id, p.category_id, p.sellable_online, p.sellable_pos,"
                + " v.manufacturer_pn, v.attributes"
                + " FROM product_variants v JOIN products p ON p.id = v.product_id"
                + " WHERE v.tenant_id = ? AND v.sku = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, sku);
            },
            rs ->
                new VariantRef(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getString(6),
                    rs.getString(7),
                    rs.getString(8),
                    rs.getString(9),
                    rs.getObject(10, UUID.class),
                    rs.getObject(11, UUID.class),
                    rs.getBoolean(12),
                    rs.getBoolean(13),
                    rs.getString(14),
                    rs.getString(15)),
            "find variant by exact sku")
        .stream()
        .findFirst();
  }

  /** The variant ids of the SKUs, by SKU. */
  public Map<String, UUID> variantIdsBySku(UUID tenantId, Collection<String> skus) {
    Map<String, UUID> out = new HashMap<>();
    if (skus.isEmpty()) return out;
    query(
            "SELECT sku, id FROM product_variants WHERE tenant_id = ? AND sku = ANY(?)",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setArray(2, ps.getConnection().createArrayOf("text", List.copyOf(skus).toArray()));
            },
            rs -> new Object[] {rs.getString(1), rs.getObject(2, UUID.class)},
            "read variant ids by sku")
        .forEach(r -> out.put((String) r[0], (UUID) r[1]));
    return out;
  }

  /**
   * Gives a variant a further code, once. A code another variant already holds is left with that
   * variant; the caller is told.
   *
   * @return true when the code was added or the variant already had it, false when another holds it
   */
  public boolean addAlias(UUID tenantId, UUID variantId, String gtin14, String kind, int packQty) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO variant_barcode_aliases (id,tenant_id,variant_id,gtin14,kind,pack_qty)"
                      + " VALUES (?,?,?,?,?,?) ON CONFLICT (tenant_id,gtin14) DO NOTHING")) {
            ps.setObject(1, Ids.newId());
            ps.setObject(2, tenantId);
            ps.setObject(3, variantId);
            ps.setString(4, gtin14);
            ps.setString(5, kind);
            ps.setInt(6, packQty);
            if (ps.executeUpdate() == 1) return true;
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT variant_id FROM variant_barcode_aliases WHERE tenant_id=? AND gtin14=?")) {
            ps.setObject(1, tenantId);
            ps.setString(2, gtin14);
            try (ResultSet rs = ps.executeQuery()) {
              return rs.next() && variantId.equals(rs.getObject(1, UUID.class));
            }
          }
        },
        "add barcode alias");
  }

  // ── the catalogue, as a dry run reads it ──────────────────────────────────

  /** What the catalogue holds for each of the SKUs, by SKU. */
  public Map<String, DryRun.Existing> existingBySku(UUID tenantId, Collection<String> skus) {
    Map<String, DryRun.Existing> out = new HashMap<>();
    if (skus.isEmpty()) return out;
    List<String> list = List.copyOf(skus);
    for (int from = 0; from < list.size(); from += 2000) {
      List<String> part = list.subList(from, Math.min(list.size(), from + 2000));
      query(
              "SELECT v.sku, p.name, v.gtin14, v.sold_by, v.unit, b.name AS brand,"
                  + " c.name AS category"
                  + " FROM product_variants v JOIN products p ON p.id = v.product_id"
                  + " LEFT JOIN brands b ON b.id = p.brand_id"
                  + " LEFT JOIN categories c ON c.id = p.category_id"
                  + " WHERE v.tenant_id = ? AND v.sku = ANY(?)",
              ps -> {
                ps.setObject(1, tenantId);
                ps.setArray(2, ps.getConnection().createArrayOf("text", part.toArray()));
              },
              rs ->
                  new DryRun.Existing(
                      rs.getString("sku"),
                      rs.getString("name"),
                      rs.getString("gtin14"),
                      rs.getString("sold_by"),
                      rs.getString("unit"),
                      rs.getString("brand"),
                      rs.getString("category")),
              "read existing variants")
          .forEach(e -> out.put(e.sku(), e));
    }
    return out;
  }

  /** Who holds each GTIN-14 now: a variant's barcode or an alias, as the holder's SKU. */
  public Map<String, String> holdersOf(UUID tenantId, Collection<String> gtin14s) {
    Map<String, String> out = new HashMap<>();
    if (gtin14s.isEmpty()) return out;
    List<String> list = List.copyOf(gtin14s);
    for (int from = 0; from < list.size(); from += 2000) {
      List<String> part = list.subList(from, Math.min(list.size(), from + 2000));
      query(
              "SELECT v.gtin14 AS code, v.sku FROM product_variants v"
                  + " WHERE v.tenant_id = ? AND v.gtin14 = ANY(?)"
                  + " UNION ALL"
                  + " SELECT a.gtin14 AS code, v.sku FROM variant_barcode_aliases a"
                  + " JOIN product_variants v ON v.id = a.variant_id"
                  + " WHERE a.tenant_id = ? AND a.gtin14 = ANY(?)",
              ps -> {
                var arr = ps.getConnection().createArrayOf("text", part.toArray());
                ps.setObject(1, tenantId);
                ps.setArray(2, arr);
                ps.setObject(3, tenantId);
                ps.setArray(4, arr);
              },
              rs -> new String[] {rs.getString("code"), rs.getString("sku")},
              "read barcode holders")
          .forEach(h -> out.put(h[0], h[1]));
    }
    return out;
  }
}
