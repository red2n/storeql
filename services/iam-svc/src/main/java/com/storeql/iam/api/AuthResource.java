package com.storeql.iam.api;

import com.storeql.iam.dto.Dtos;
import com.storeql.iam.dto.Dtos.BusinessRegisterRequest;
import com.storeql.iam.dto.Dtos.ChangePasswordRequest;
import com.storeql.iam.dto.Dtos.LoginRequest;
import com.storeql.iam.dto.Dtos.LogoutRequest;
import com.storeql.iam.dto.Dtos.ProvisionStaffRequest;
import com.storeql.iam.dto.Dtos.ProvisionStaffResponse;
import com.storeql.iam.dto.Dtos.RefreshRequest;
import com.storeql.iam.dto.Dtos.RegisterRequest;
import com.storeql.iam.dto.Dtos.StaffUserResponse;
import com.storeql.iam.dto.Dtos.TokenResponse;
import com.storeql.iam.service.AuthService;
import com.storeql.iam.service.StaffDirectory;
import com.storeql.ids.Ids;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Public authentication endpoints (docs/ARCHITECTURE.md §10, iam-svc). These are reachable without
 * a tenant/JWT — they MINT identity. Thin controllers: validate DTO, delegate to {@link
 * AuthService}, return the envelope.
 */
@Path("/auth")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Auth")
public class AuthResource {

  @Inject AuthService auth;
  @Inject StaffDirectory staff;
  @Inject TenantContext ctx;

  /**
   * Admin endpoint. Find-or-create a staff account by email and return the userId the caller
   * assigns a store role to via tenant-svc. Tenant comes from the JWT, never the body. The login
   * found is only ever the business's own; one made is made in the business, so a shopper's
   * account, an unfinished business sign-up or another business's login with the same email is
   * never taken over, and none of them refuses the request.
   *
   * <p>Also gated by AdminAuthorizationFilter on the {@code /admin/} path prefix, but asserted here
   * too rather than relying on that alone — a future rename/move of this path off {@code /admin/}
   * must not silently drop the management-role requirement.
   */
  @Operation(
      summary = "Provision a staff account",
      description =
          "Find-or-create a staff account by email within the caller's business: the business's"
              + " own login with this email (created: false), or a new staff login made in the"
              + " business with the given password (created: true). A shopper's account, an"
              + " unfinished business sign-up or another business's login with the same email is"
              + " a separate identity and is left untouched: a person may work for several"
              + " businesses, each with its own login. Returns the userId the caller assigns a"
              + " store role to via tenant-svc, which binds only a login already in the business."
              + " Requires PLATFORM_ADMIN, OWNER, or MANAGER.")
  @APIResponse(responseCode = "200", description = "Staff user found or created")
  @APIResponse(responseCode = "403", description = "Caller lacks an admin/owner/manager role")
  @POST
  @Path("/admin/staff-users")
  public ApiResponse<ProvisionStaffResponse> provisionStaff(ProvisionStaffRequest req) {
    Validations.validate(req);
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
    return ApiResponse.ok(auth.provisionStaff(ctx.requireTenantId(), req.email(), req.password()));
  }

  /**
   * Admin endpoint. Names the business's staff among the ids by their login email, for screens that
   * hold only a user id (staff assignments, the audit trail). Tenant comes from the JWT: another
   * business's staff, a customer and an unknown id are left out, never refused one by one.
   *
   * <p>Role asserted here as well as by AdminAuthorizationFilter, as for {@link #provisionStaff}.
   *
   * <p>Scoped by the caller's stores ({@link TenantContext#storeIds()}). Held to no store — an
   * owner, a business-wide manager, the platform admin — every match in the business is named, as
   * before. Held to one or more stores, only staff who hold a role at one of those stores or a
   * business-wide role in the caller's business are named; the rest are left out, never refused one
   * by one.
   *
   * @param ids comma-separated UUIDv7s, at most 100
   * @return {@code [{userId, email}]} for the staff found
   */
  @Operation(
      summary = "Name staff users by id",
      description =
          "The login email of each of the caller's business's staff among ?ids= (comma-separated,"
              + " at most 100). Another business's staff, customers and unknown ids are left out"
              + " of the answer. A caller held to one or more stores is answered only staff at"
              + " those stores or with a business-wide role; an owner, a business-wide manager or"
              + " the platform admin is answered every match. Requires PLATFORM_ADMIN, OWNER, or"
              + " MANAGER.")
  @APIResponse(responseCode = "200", description = "The staff found among the ids")
  @APIResponse(
      responseCode = "400",
      description = "More than 100 ids (STAFF_IDS_TOO_MANY), or one not a UUIDv7 (INVALID_UUID)")
  @APIResponse(responseCode = "403", description = "Caller lacks an admin/owner/manager role")
  @GET
  @Path("/admin/staff-users")
  public ApiResponse<List<StaffUserResponse>> staffUsers(@QueryParam("ids") String ids) {
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
    return ApiResponse.ok(staff.logins(ctx.requireTenantId(), ids, ctx.storeIds()));
  }

  /**
   * Public customer self-signup, minting an account and its first token pair.
   *
   * @param req the email, password and optional phone to register
   * @return {@code 201} with the new account's access and refresh tokens
   * @throws com.storeql.web.ApiException {@code USER_ALREADY_EXISTS} (409) when another shopper's
   *     login already holds the email or phone; the same person's business account does not count
   */
  @Operation(
      summary = "Register a new customer",
      description = "Public self-signup. No JWT required — this endpoint mints identity.")
  @APIResponse(responseCode = "201", description = "Account created, tokens issued")
  @APIResponse(
      responseCode = "409",
      description = "USER_ALREADY_EXISTS: another shopper's account holds the email or phone")
  @POST
  @Path("/register")
  public Response register(RegisterRequest req) {
    Validations.validate(req);
    TokenResponse tokens = auth.register(req.email(), req.password(), req.phone());
    return Response.status(Response.Status.CREATED).entity(ApiResponse.ok(tokens)).build();
  }

  /**
   * Public business sign-up ("Start a business"): the login a new business will be run with.
   *
   * <p>A STAFF login with no tenant and no role, signed in at once. It is not a shopper's account —
   * {@link #register} stays for those — and it reaches no business's data: it creates its own
   * business next (tenant-svc {@code POST /onboarding}), whose {@code TenantCreated} makes it that
   * business's OWNER.
   *
   * @param req the email, password and optional phone to sign up with
   * @return {@code 201} with the new login's access and refresh tokens
   * @throws com.storeql.web.ApiException {@code USER_ALREADY_EXISTS} (409) when the email or phone
   *     is already used by another business login of no business (a sign-up not yet onboarded, or
   *     the platform administrator's) — a shopper's account with it is a separate identity and does
   *     not count; the password policy's own codes (400) for a password it refuses
   */
  @Operation(
      summary = "Sign up to start a business",
      description =
          "Public. Creates a staff login with no business and no role yet and returns its token"
              + " pair; the business is created next (tenant-svc POST /onboarding), which makes"
              + " this login its OWNER. The password policy applies (GET /auth/password-policy)."
              + " A phone is optional and kept as POST /auth/register keeps one. A shopper signs"
              + " up at POST /auth/register instead; an address or phone the person already shops"
              + " with may sign up here too, as a separate account.")
  @APIResponse(responseCode = "201", description = "Login created, tokens issued")
  @APIResponse(
      responseCode = "400",
      description =
          "VALIDATION_FAILED, or the password policy's PASSWORD_TOO_SHORT, PASSWORD_TOO_LONG,"
              + " PASSWORD_IS_IDENTITY, PASSWORD_BREACHED")
  @APIResponse(
      responseCode = "409",
      description =
          "USER_ALREADY_EXISTS: another business sign-up of no business holds the email or phone")
  @POST
  @Path("/register/business")
  public Response registerBusiness(BusinessRegisterRequest req) {
    Validations.validate(req);
    TokenResponse tokens = auth.registerBusiness(req.email(), req.password(), req.phone());
    return Response.status(Response.Status.CREATED).entity(ApiResponse.ok(tokens)).build();
  }

  /**
   * Tenant staff and customer login.
   *
   * <p>An email can name several logins — one per business it works for, and outside any business a
   * shopper's account and a business account — so {@code accountType} chooses the kind (the
   * storefront sends CUSTOMER; none means STAFF) and the password which login of that kind.
   *
   * @param req the email and password to authenticate, and where the person is signing in
   * @return the access and refresh token pair
   * @throws com.storeql.web.ApiException {@code 401} when the credentials do not match
   */
  @Operation(
      summary = "Log in with email and password",
      description =
          "Public tenant/customer login. No JWT required. accountType chooses between a shopper's"
              + " account and a business account on one address: CUSTOMER from a storefront, STAFF"
              + " (the default) for the admin console and the till. A login of the other kind is"
              + " signed in only when the address holds none of the kind asked for.")
  @APIResponse(responseCode = "200", description = "Credentials valid, tokens issued")
  @APIResponse(responseCode = "401", description = "Invalid email or password")
  @POST
  @Path("/login")
  public ApiResponse<TokenResponse> login(LoginRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(auth.login(req.email(), req.password(), req.accountType()));
  }

  /**
   * Platform console login — distinct from {@link #login} so a PLATFORM_ADMIN credential is never
   * valid on a store/POS login screen, and a tenant staff credential is never valid here.
   *
   * @param req the email and password to authenticate
   * @return the access and refresh token pair
   * @throws com.storeql.web.ApiException {@code 401} when the credentials do not match; {@code 403}
   *     when they are valid but the account is not a {@code PLATFORM_ADMIN}
   */
  @Operation(
      summary = "Log in to the platform console",
      description =
          "PLATFORM_ADMIN-only login, kept separate from /auth/login so tenant staff and platform"
              + " admin credentials are never interchangeable.")
  @APIResponse(responseCode = "200", description = "Credentials valid, tokens issued")
  @APIResponse(responseCode = "401", description = "Invalid email or password")
  @APIResponse(responseCode = "403", description = "Credential is valid but not a PLATFORM_ADMIN")
  @POST
  @Path("/platform-login")
  public ApiResponse<TokenResponse> platformLogin(LoginRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(auth.platformLogin(req.email(), req.password()));
  }

  /**
   * Exchanges a refresh token for a fresh token pair.
   *
   * <p>Authenticated by the refresh token itself, so it needs no bearer JWT — which is what lets a
   * client renew an expired session.
   *
   * @param req the refresh token to redeem
   * @return a new access and refresh token pair
   * @throws com.storeql.web.ApiException {@code 401} when the token is unknown, expired or revoked
   */
  @Operation(
      summary = "Exchange a refresh token for a new access token",
      description = "Public — authenticates via the refresh token itself, not a bearer JWT.")
  @APIResponse(responseCode = "200", description = "New token pair issued")
  @APIResponse(responseCode = "401", description = "Refresh token invalid, expired, or revoked")
  @POST
  @Path("/refresh")
  public ApiResponse<TokenResponse> refresh(RefreshRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(auth.refresh(req.refreshToken()));
  }

  /**
   * Revokes a refresh token, ending the ability to renew that session.
   *
   * <p>Access tokens already issued stay valid until they expire, so logout is not immediate
   * revocation of all access.
   *
   * @param req the refresh token to revoke
   * @return {@code logged_out}, also when the token was already revoked
   */
  @Operation(
      summary = "Log out",
      description = "Revokes the given refresh token. Idempotent-friendly: revoking twice is safe.")
  @APIResponse(responseCode = "200", description = "Refresh token revoked")
  @POST
  @Path("/logout")
  public ApiResponse<String> logout(LogoutRequest req) {
    Validations.validate(req);
    auth.logout(req.refreshToken());
    return ApiResponse.ok("logged_out");
  }

  /**
   * Signs the caller out everywhere: every refresh token of their own login is revoked.
   *
   * @return how many sessions were ended
   */
  @Operation(
      summary = "Sign out everywhere",
      description =
          "Ends every renewable session of the signed-in login, this one included; access tokens"
              + " already issued live out their few minutes. Only ever the caller's own login.")
  @APIResponse(responseCode = "200", description = "How many sessions were ended")
  @APIResponse(responseCode = "401", description = "Not signed in")
  @POST
  @Path("/sessions/revoke-all")
  public ApiResponse<Dtos.SessionsRevokedResponse> revokeAllSessions() {
    return ApiResponse.ok(
        new Dtos.SessionsRevokedResponse(auth.revokeAllSessions(ctx.requireUserId())));
  }

  /**
   * The caller's own signed-in sessions.
   *
   * @param sessionId the caller's current session, stamped by the gateway as {@code X-Session-Id}
   *     from the {@code sid} claim of the verified access token (a client's own copy is stripped
   *     there); marks which one is this one, and only ever among the caller's own
   * @return the live sessions, most recently used first
   */
  @Operation(
      summary = "List my sessions",
      description =
          "Where the signed-in login is signed in: when each began, when it was last renewed, the"
              + " client as far as iam knows it, and which one is the caller's.")
  @APIResponse(responseCode = "200", description = "The caller's live sessions")
  @GET
  @Path("/sessions")
  public ApiResponse<List<Dtos.SessionResponse>> mySessions(
      @jakarta.ws.rs.HeaderParam(com.storeql.web.HttpHeaders.SESSION_ID) String sessionId) {
    java.util.UUID current = null;
    try {
      if (sessionId != null && !sessionId.isBlank()) current = Ids.parse(sessionId.trim());
    } catch (RuntimeException e) {
      current = null;
    }
    return ApiResponse.ok(auth.sessionsOf(ctx.requireUserId(), current));
  }

  /**
   * Signs one of the caller's own sessions out.
   *
   * @param id the session
   * @return {@code 204}
   * @throws com.storeql.web.ApiException {@code 404 SESSION_NOT_FOUND} for another login's, an
   *     unknown or an already ended session
   */
  @Operation(
      summary = "Sign one session out",
      description = "Revokes the session's refresh token chain. Only the caller's own.")
  @APIResponse(responseCode = "204", description = "Session ended")
  @APIResponse(responseCode = "404", description = "No such live session of the caller's")
  @jakarta.ws.rs.DELETE
  @Path("/sessions/{id}")
  public Response endSession(@jakarta.ws.rs.PathParam("id") java.util.UUID id) {
    auth.endSession(ctx.requireUserId(), id);
    return Response.noContent().build();
  }

  /**
   * Changes the calling user's own password.
   *
   * <p>Re-verifies the current password so a session left open on a shared device cannot change it.
   *
   * @param req the current password and the replacement
   * @return {@code password_changed}
   * @throws com.storeql.web.ApiException {@code 401} when the current password is wrong
   */
  @Operation(
      summary = "Change the current user's password",
      description =
          "Requires the current password to re-verify identity before setting the new one.")
  @APIResponse(responseCode = "200", description = "Password changed")
  @APIResponse(responseCode = "401", description = "Current password is incorrect")
  @PUT
  @Path("/change-password")
  public ApiResponse<String> changePassword(ChangePasswordRequest req) {
    Validations.validate(req);
    auth.changePassword(
        ctx.requireUserId(), req.currentPassword(), req.newPassword(), req.language());
    return ApiResponse.ok("password_changed");
  }

  /**
   * The account holder erases their own login (SJ-D43).
   *
   * <p>Customer accounts only — a staff account is removed by the business that employs its holder.
   * What a shop holds about the person (orders, loyalty, its own customer profile) is not touched
   * here; that shop erases it on request.
   *
   * @param req the password, required again as proof of presence
   * @return {@code account_deleted}
   * @throws com.storeql.web.ApiException {@code 401} when the password is wrong; {@code 403} when
   *     the caller holds a staff account
   */
  @Operation(
      summary = "Delete my account",
      description =
          "The account holder deletes their own login (SJ-D43). Requires the password again, so a"
              + " session left open on a shared device cannot do it. The login's email, phone and"
              + " password are erased, every refresh token is revoked, and AccountDeleted tells"
              + " other services. Already-issued access tokens stay valid until they expire, as"
              + " they do after logout. Records a shop holds — orders, loyalty, its own customer"
              + " profile — stay with that shop, which erases them itself on request. A staff"
              + " account is removed by the business that employs its holder, not here.")
  @APIResponse(responseCode = "200", description = "Account deleted")
  @APIResponse(responseCode = "401", description = "Password is incorrect")
  @APIResponse(responseCode = "403", description = "A staff account cannot be deleted here")
  @POST
  @Path("/delete-account")
  public ApiResponse<String> deleteAccount(com.storeql.iam.dto.Dtos.DeleteAccountRequest req) {
    Validations.validate(req);
    auth.deleteAccount(ctx.requireUserId(), req.password());
    return ApiResponse.ok("account_deleted");
  }
}
