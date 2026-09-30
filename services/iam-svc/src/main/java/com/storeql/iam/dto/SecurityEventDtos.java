package com.storeql.iam.dto;

import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** The security-events read on the wire. Never a secret, hash, token, IP address or link. */
public final class SecurityEventDtos {

  private SecurityEventDtos() {}

  @Schema(name = "SecurityEvent", description = "One recorded security event of a login.")
  public record EventResponse(
      String id,
      @Schema(description = "Machine code, e.g. MFA_LOCKED, LOGIN_FAILED, PASSWORD_CHANGED.")
          String type,
      @Schema(description = "The login it concerns; null when none.") String userId,
      @Schema(description = "That login's address now; null for a deleted account.") String email,
      @Schema(description = "The business it belongs to; null for a shopper or the platform.")
          String tenantId,
      @Schema(description = "The safe part of the free text, for the codes that have one.")
          String detail,
      Instant at) {}

  @Schema(name = "SecurityEventPage", description = "Newest first; pass nextCursor as ?after=.")
  public record Page(List<EventResponse> items, String nextCursor) {}
}
