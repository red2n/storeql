package com.storeql.product.domain.imports;

import java.time.Instant;
import java.util.UUID;

/** The rows the catalogue import keeps (V17): plain values, no behaviour. */
public final class ImportRecords {

  private ImportRecords() {}

  /** A saved mapping. */
  public record Mapping(
      UUID id, UUID tenantId, String name, String json, String hash, Instant updatedAt) {}

  /** An uploaded file, without its content (read separately, once). */
  public record File(
      UUID id,
      UUID tenantId,
      UUID storeId,
      String fileName,
      String sha256,
      int sizeBytes,
      String encoding,
      char delimiter,
      int rowCount,
      Instant createdAt) {}

  /** A job: one run of a file under a mapping. */
  public record Job(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID fileId,
      UUID mappingId,
      String mappingHash,
      String fileSha256,
      String kind,
      UUID dryRunOf,
      UUID priceListId,
      String status,
      String phase,
      String counts,
      String failureCode,
      String failureDetail,
      String starter,
      Instant createdAt,
      Instant finishedAt) {

    public static final String DRY_RUN = "DRY_RUN";
    public static final String APPLY = "APPLY";
    public static final String DRY_RUN_DONE = "DRY_RUN_DONE";
    public static final String DRY_RUN_FAILED = "DRY_RUN_FAILED";
    public static final String QUEUED = "QUEUED";
    public static final String APPLYING = "APPLYING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";
    public static final String EXPIRED = "EXPIRED";
  }

  /** One row's recorded result. */
  public record RowResult(
      UUID id, int line, String sku, String action, String refusals, String gaps, String changes) {}
}
