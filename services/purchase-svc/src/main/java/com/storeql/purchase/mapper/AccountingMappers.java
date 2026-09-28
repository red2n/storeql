package com.storeql.purchase.mapper;

import com.storeql.purchase.client.accounting.AccountingPackage;
import com.storeql.purchase.domain.Accounting;
import com.storeql.purchase.dto.AccountingDtos;
import com.storeql.purchase.service.AccountingService;
import java.util.List;

/** Accounting connectors (17.9) to their wire form. */
public final class AccountingMappers {
  private AccountingMappers() {}

  public static AccountingDtos.ProviderResponse toProvider(Accounting.Provider p) {
    return new AccountingDtos.ProviderResponse(
        p.code(), p.name(), p.required(), p.optional(), p.tokens());
  }

  public static AccountingDtos.ConnectionResponse toConnection(AccountingService.View v) {
    Accounting.Connection c = v.connection();
    Accounting.Counts n = v.counts();
    return new AccountingDtos.ConnectionResponse(
        c.id().toString(),
        c.provider(),
        c.status(),
        c.settings(),
        c.syncFrom(),
        v.hasRefreshToken(),
        c.createdAt(),
        c.updatedAt(),
        c.lastSyncAt(),
        c.lastError(),
        c.disabledReason(),
        new AccountingDtos.CountsResponse(
            n.pending(), n.delivered(), n.failed(), n.uncertain(), n.skipped()));
  }

  public static AccountingDtos.ExternalAccountResponse toAccount(
      AccountingPackage.ExternalAccount a) {
    return new AccountingDtos.ExternalAccountResponse(a.id(), a.code(), a.name(), a.type());
  }

  public static AccountingDtos.MappingResponse toMapping(Accounting.Mapping m) {
    return new AccountingDtos.MappingResponse(
        m.nominalCode(), m.externalAccount(), m.externalName());
  }

  public static AccountingDtos.RunResponse toRun(Accounting.Run r) {
    return new AccountingDtos.RunResponse(r.queued(), r.delivered(), r.failed(), r.uncertain());
  }

  public static AccountingDtos.SyncResponse toSync(Accounting.Sync s) {
    return toSync(s, null, null);
  }

  public static AccountingDtos.SyncResponse toSync(AccountingService.Detail d) {
    return toSync(
        d.sync(),
        d.lines().stream().map(Mappers::toDto).toList(),
        d.attempts().stream()
            .map(
                a ->
                    new AccountingDtos.AttemptResponse(
                        a.attempt(),
                        a.at(),
                        a.statusCode(),
                        a.error(),
                        a.snippet(),
                        a.durationMs()))
            .toList());
  }

  private static AccountingDtos.SyncResponse toSync(
      Accounting.Sync s,
      List<com.storeql.purchase.dto.Dtos.NominalLedgerEntryResponse> lines,
      List<AccountingDtos.AttemptResponse> attempts) {
    return new AccountingDtos.SyncResponse(
        s.id().toString(),
        s.journalId().toString(),
        s.status(),
        s.attempts(),
        s.externalId(),
        s.lastError(),
        s.nextAttemptAt(),
        s.createdAt(),
        s.deliveredAt(),
        s.entryDate(),
        s.description(),
        s.sourceType(),
        s.total(),
        lines,
        attempts);
  }
}
