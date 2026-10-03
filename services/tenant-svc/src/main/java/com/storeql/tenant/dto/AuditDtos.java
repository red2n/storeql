package com.storeql.tenant.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** The business's admin change log on the wire. */
public final class AuditDtos {
  private AuditDtos() {}

  @Schema(
      name = "AuditEntryResponse",
      description = "One change to a store's status or till setting, staff or a role.")
  public record EntryResponse(
      String id,
      @Schema(
              description =
                  "STORE_CREATED, STORE_STATUS_CHANGED, STORE_TILL_PHONE_CHANGED, STAFF_ASSIGNED,"
                      + " STAFF_UNASSIGNED, ROLE_DEFINED, ROLE_CHANGED or ROLE_DELETED.")
          String type,
      @Schema(description = "The login that made the change; null when nobody did.") String actorId,
      @Schema(description = "The store it concerns; null for a change to the whole business.")
          String storeId,
      @Schema(description = "The person a staff entry is about.") String subjectId,
      @Schema(description = "The role code a staff or role entry is about.") String subjectCode,
      @Schema(description = "What it was before; null for something new.") String from,
      @Schema(description = "What it is now; null for something removed.") String to,
      String occurredAt) {}
}
