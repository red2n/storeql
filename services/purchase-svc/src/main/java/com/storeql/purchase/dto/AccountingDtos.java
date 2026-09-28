package com.storeql.purchase.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Accounting connectors (17.9) on the wire. */
public final class AccountingDtos {
  private AccountingDtos() {}

  @Schema(name = "AccountingProvider", description = "A package the platform can push journals to.")
  public record ProviderResponse(
      String code,
      String name,
      @Schema(description = "The settings a connection must carry.") List<String> settings,
      @Schema(description = "The settings it may carry.") List<String> optional,
      @Schema(description = "Where the tokens come from.") String tokens) {}

  @Schema(
      name = "AccountingCredentials",
      description = "What the package issued. Kept sealed; never shown again.")
  public record CredentialsRequest(
      String accessToken,
      String refreshToken,
      String clientId,
      String clientSecret,
      @Schema(description = "When the access token stops, ISO-8601; optional.") String expiresAt) {}

  @Schema(
      name = "ConnectAccountingRequest",
      description =
          "Connect the business's accounting package: which one, the identifiers it needs, the tokens"
              + " it issued, and the day from which journals are pushed.")
  public record ConnectRequest(
      @Schema(description = "XERO, QUICKBOOKS, SAGE or SIMULATED.") String provider,
      @Schema(description = "The package's identifiers, as the catalogue names them.")
          Map<String, String> settings,
      CredentialsRequest credentials,
      @Schema(description = "yyyy-MM-dd: journals dated from this day are pushed.")
          String syncFrom) {}

  @Schema(name = "AccountingCounts")
  public record CountsResponse(
      int pending, int delivered, int failed, int uncertain, int skipped) {}

  @Schema(
      name = "AccountingConnection",
      description = "The package a business is connected to. Never its tokens.")
  public record ConnectionResponse(
      String id,
      String provider,
      @Schema(description = "ACTIVE or DISABLED.") String status,
      Map<String, String> settings,
      LocalDate syncFrom,
      @Schema(description = "Whether the tokens can be refreshed without a person.")
          boolean hasRefreshToken,
      Instant createdAt,
      Instant updatedAt,
      Instant lastSyncAt,
      @Schema(description = "The first thing that went wrong on the last pass, or null.")
          String lastError,
      String disabledReason,
      CountsResponse counts) {}

  @Schema(name = "ExternalAccount", description = "One of the package's accounts.")
  public record ExternalAccountResponse(
      @Schema(description = "What a journal line names the account by in this package.") String id,
      @Schema(description = "The package's own code, or empty.") String code,
      String name,
      String type) {}

  @Schema(name = "AccountMappingRequest")
  public record MappingRequest(String nominalCode, String externalAccount, String externalName) {}

  @Schema(
      name = "AccountMappingsRequest",
      description = "The whole mapping; what is left out is removed.")
  public record MappingsRequest(List<MappingRequest> mappings) {}

  @Schema(name = "AccountMapping")
  public record MappingResponse(String nominalCode, String externalAccount, String externalName) {}

  @Schema(name = "AccountingRun", description = "What one pass did.")
  public record RunResponse(int queued, int delivered, int failed, int uncertain) {}

  @Schema(name = "AccountingSyncAttempt")
  public record AttemptResponse(
      int attempt, Instant at, Integer statusCode, String error, String snippet, int durationMs) {}

  @Schema(name = "AccountingSync", description = "One journal's journey to the package.")
  public record SyncResponse(
      String id,
      String journalId,
      @Schema(description = "PENDING, DELIVERED, FAILED, UNCERTAIN or SKIPPED.") String status,
      int attempts,
      @Schema(description = "What the package called it, once delivered.") String externalId,
      String lastError,
      Instant nextAttemptAt,
      Instant createdAt,
      Instant deliveredAt,
      LocalDate entryDate,
      String description,
      String sourceType,
      BigDecimal total,
      @Schema(description = "The journal's lines; on the detail only.")
          List<Dtos.NominalLedgerEntryResponse> lines,
      @Schema(description = "Every try; on the detail only.") List<AttemptResponse> attemptLog) {}

  @Schema(name = "AccountingSyncPage")
  public record SyncPage(List<SyncResponse> items, String nextCursor) {}

  @Schema(name = "SkipAccountingSyncRequest")
  public record SkipRequest(
      @Schema(description = "Why the journal is not to be pushed.") String reason) {}
}
