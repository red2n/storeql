package com.storeql.iam.service;

import com.storeql.iam.domain.SecurityEvent;
import com.storeql.iam.repo.SecurityEventRepository;
import com.storeql.web.ApiException;
import com.storeql.web.Cursor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The security events a person may read: an owner or manager reads those of the logins of their own
 * business, the platform administrator all of them. The caller's business comes from the token,
 * never the request.
 */
@ApplicationScoped
public class SecurityEventService {

  private static final Pattern TYPE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

  @Inject SecurityEventRepository events;

  /** A page: newest first; {@code nextCursor} is the last item's id when more may follow. */
  public record Page(List<SecurityEvent> items, String nextCursor) {}

  /**
   * @param tenantId the caller's business, or null for the platform administrator
   * @param type exact action code filter, null for any
   * @param from inclusive, null for no lower bound
   * @param to exclusive, null for no upper bound
   * @throws ApiException {@code 400 SECURITY_EVENT_TYPE_INVALID} for a malformed type, {@code 400
   *     SECURITY_EVENT_PERIOD_INVALID} when {@code from} is not before {@code to}
   */
  public Page list(
      UUID tenantId,
      String type,
      UUID userId,
      Instant from,
      Instant to,
      UUID after,
      Integer limit) {
    String wanted = type == null || type.isBlank() ? null : type.trim();
    if (wanted != null && !TYPE.matcher(wanted).matches()) {
      throw ApiException.badRequest(
          "SECURITY_EVENT_TYPE_INVALID", "A type is an upper-case code such as MFA_LOCKED");
    }
    if (from != null && to != null && !from.isBefore(to)) {
      throw ApiException.badRequest(
          "SECURITY_EVENT_PERIOD_INVALID", "The period must start before it ends");
    }
    int size = Cursor.clampLimit(limit);
    List<SecurityEvent> found = events.list(tenantId, wanted, userId, from, to, after, size + 1);
    if (found.size() <= size) {
      return new Page(found, null);
    }
    List<SecurityEvent> page = found.subList(0, size);
    return new Page(page, page.get(size - 1).id().toString());
  }
}
