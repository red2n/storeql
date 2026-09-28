package com.storeql.iam.service;

import com.storeql.iam.dto.Dtos.StaffUserResponse;
import com.storeql.iam.repo.UserRepository;
import com.storeql.web.ApiException;
import com.storeql.web.Parsing;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Names a business's staff by their login email. tenant-svc's assignments and the audit trail carry
 * only a user id; the login is iam-svc's, so screens ask here rather than show the id.
 */
@ApplicationScoped
public class StaffDirectory {

  /** The most ids one request names — what a screen asks for in a page. */
  public static final int MAX_IDS = 100;

  @Inject UserRepository users;

  /**
   * The business's staff among the ids, each named once.
   *
   * <p>{@code storeIds} scopes the answer to the caller's own stores and business-wide roles — pass
   * {@code TenantContext.storeIds()} straight through; empty means the caller is held to no store
   * (an owner, a business-wide manager, the platform admin) and every match is named.
   *
   * @param tenantId the caller's business, from the token
   * @param idsCsv the {@code ids} query parameter as sent
   * @param storeIds the caller's stores; empty means unrestricted
   * @return the staff found, ordered by email; empty when none of the ids is the business's
   * @throws ApiException {@code 400 STAFF_IDS_TOO_MANY} beyond {@value #MAX_IDS} ids; {@code 400
   *     INVALID_UUID} when one is not a UUIDv7
   */
  public List<StaffUserResponse> logins(UUID tenantId, String idsCsv, Set<UUID> storeIds) {
    List<UUID> ids = parseIds(idsCsv);
    if (ids.isEmpty()) {
      return List.of();
    }
    return users.staffLogins(tenantId, ids, storeIds).stream()
        .map(s -> new StaffUserResponse(s.userId().toString(), s.email()))
        .toList();
  }

  /**
   * Reads {@code ?ids=}: comma-separated, blanks and repeats ignored, first-seen order kept. The
   * limit counts the ids as sent, so a long list is turned away before any of it is parsed.
   */
  static List<UUID> parseIds(String idsCsv) {
    if (idsCsv == null || idsCsv.isBlank()) {
      return List.of();
    }
    List<String> sent = new ArrayList<>();
    for (String part : idsCsv.split(",")) {
      String id = part.trim();
      if (!id.isEmpty()) {
        sent.add(id);
      }
    }
    if (sent.size() > MAX_IDS) {
      throw ApiException.badRequest(
          "STAFF_IDS_TOO_MANY", "At most " + MAX_IDS + " ids can be looked up at once");
    }
    Set<UUID> ids = new LinkedHashSet<>();
    for (String id : sent) {
      ids.add(Parsing.uuid(id, "ids"));
    }
    return List.copyOf(ids);
  }
}
