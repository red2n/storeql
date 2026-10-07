package com.storeql.tenant.mapper;

import com.storeql.tenant.domain.Audit;
import com.storeql.tenant.dto.AuditDtos.EntryResponse;

/** The admin change log to the wire. */
public final class AuditMappers {
  private AuditMappers() {}

  public static EntryResponse toEntry(Audit.Entry e) {
    return new EntryResponse(
        e.id().toString(),
        e.type(),
        e.actorId() == null ? null : e.actorId().toString(),
        e.storeId() == null ? null : e.storeId().toString(),
        e.subjectId() == null ? null : e.subjectId().toString(),
        e.subjectCode(),
        e.fromValue(),
        e.toValue(),
        e.occurredAt().toString());
  }
}
