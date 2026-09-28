package com.storeql.iam.service;

import com.storeql.iam.auth.JwtService;
import com.storeql.iam.config.ServiceConfig;
import com.storeql.iam.domain.User;
import com.storeql.iam.dto.Dtos.SandboxTokenResponse;
import com.storeql.iam.repo.SandboxRepository;
import com.storeql.iam.repo.UserRepository;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Getting into a business's sandbox (22.8). The live owner trades its token for one that names the
 * sandbox as its tenant: an owner there, with {@code amr: [sandbox]} so the app can show where it
 * is, and no refresh token — the sandbox session lasts one access token and is re-entered from the
 * live one. Nobody but the owner, and never from inside a sandbox.
 */
@ApplicationScoped
public class SandboxAccessService {

  /** How the token says it was got: by trading a live owner's token, not by signing in. */
  static final String AMR_SANDBOX = "sandbox";

  @Inject SandboxRepository sandboxes;
  @Inject UserRepository users;
  @Inject JwtService jwt;
  @Inject ServiceConfig config;

  /**
   * @param liveTenantId the caller's tenant, from its verified token
   * @param userId the caller
   * @throws ApiException {@code 409 SANDBOX_NESTED} from inside a sandbox, {@code 404
   *     SANDBOX_NOT_FOUND} when the business has no active sandbox
   */
  public SandboxTokenResponse enter(UUID liveTenantId, UUID userId) {
    if (sandboxes.liveOf(liveTenantId).isPresent()) {
      throw ApiException.conflict(
          "SANDBOX_NESTED", "You are already in the sandbox; go back to the live business first");
    }
    UUID sandbox =
        sandboxes
            .activeSandboxOf(liveTenantId)
            .orElseThrow(
                () -> ApiException.notFound("SANDBOX_NOT_FOUND", "This business has no sandbox"));
    User user =
        users
            .findById(userId)
            .orElseThrow(
                () -> ApiException.unauthorized("INVALID_CREDENTIALS", "User no longer exists"));
    String access =
        jwt.issueAccessToken(
            userId,
            sandbox,
            User.TYPE_STAFF,
            user.email(),
            Set.of("OWNER"),
            Set.of(),
            null,
            List.of(AMR_SANDBOX));
    users.audit(liveTenantId, userId, "SANDBOX_ENTERED", sandbox.toString());
    return new SandboxTokenResponse(
        access, "Bearer", config.accessTtlSeconds(), sandbox.toString());
  }
}
