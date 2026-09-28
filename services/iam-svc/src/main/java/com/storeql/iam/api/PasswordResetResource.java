package com.storeql.iam.api;

import com.storeql.iam.dto.Dtos.ForgotPasswordRequest;
import com.storeql.iam.dto.Dtos.ForgotPasswordResponse;
import com.storeql.iam.dto.Dtos.PasswordPolicyResponse;
import com.storeql.iam.dto.Dtos.ResetPasswordRequest;
import com.storeql.iam.dto.Dtos.ResetPasswordResponse;
import com.storeql.iam.service.PasswordResetService;
import com.storeql.web.ApiResponse;
import com.storeql.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Forgotten password (intent/password-reset.md), public — reachable with no token, which is the
 * whole point: the published policy, asking for a link, and spending one. Thin controller: {@link
 * PasswordResetService} holds every rule (golden rule #9).
 */
@Path("/auth")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Auth")
public class PasswordResetResource {

  @Inject PasswordResetService resetService;

  /**
   * The password rules in force, so a sign-up or reset page can show them before anyone types —
   * never refusing a password by a rule the person could not see.
   *
   * @return the policy
   */
  @Operation(
      summary = "The published password policy",
      description =
          "Public, no token needed. The same answer for everyone — a sign-up page and the"
              + " password-reset page both read it before anyone types.")
  @APIResponse(responseCode = "200", description = "The policy in force")
  @GET
  @Path("/password-policy")
  public ApiResponse<PasswordPolicyResponse> policy() {
    return ApiResponse.ok(resetService.policy());
  }

  /**
   * Asks for a password reset link. Answers {@code 202} the same way whatever the address — known
   * or unknown, a suspended login, or a request throttled for asking too often — so nothing here
   * can be used to test which addresses exist.
   *
   * @param req the address, and the language the person was using
   * @return {@code accepted: true}, always
   */
  @Operation(
      summary = "Ask for a password reset link",
      description =
          "Public. Answers 202 the same way whatever the address: known or unknown, a suspended"
              + " login, or a request throttled for asking too often this hour — never a signal an"
              + " attacker could use to test which addresses exist. Every eligible login using the"
              + " address gets its own link by email (or, for a business signing its staff in"
              + " through its own identity provider, a note instead of a link).")
  @APIResponse(
      responseCode = "202",
      description = "Accepted — the same answer whatever the address")
  @APIResponse(
      responseCode = "400",
      description = "VALIDATION_FAILED: email missing, blank, not an email, or over 254 characters")
  @POST
  @Path("/password/forgot")
  public Response forgot(ForgotPasswordRequest req) {
    Validations.validate(req);
    resetService.forgot(req.email(), req.language());
    return Response.status(Response.Status.ACCEPTED)
        .entity(ApiResponse.ok(new ForgotPasswordResponse(true)))
        .build();
  }

  /**
   * Spends a password reset link. On success, every other session of the login ends; the person
   * signs in as usual afterwards — no token is issued here, and a second factor, if the login has
   * one, is still asked.
   *
   * @param req the token from the link, and the new password
   * @return {@code reset: true}
   */
  @Operation(
      summary = "Spend a password reset link",
      description =
          "Public: the token from the link, and the new password. Spends the token once, good for"
              + " 30 minutes; the policy is checked before anything is spent, so a refused password"
              + " leaves the link usable. On success every other session of the login ends and the"
              + " person signs in as usual — a second factor, if they have one, is still asked."
              + " Never signs the caller in and never touches another login, even one sharing the"
              + " same address.")
  @APIResponse(responseCode = "200", description = "Password reset")
  @APIResponse(
      responseCode = "400",
      description =
          "PASSWORD_RESET_TOKEN_INVALID (unknown, used, expired, replaced, or the login is no"
              + " longer eligible), or the policy's own code for the new password"
              + " (PASSWORD_TOO_SHORT, PASSWORD_TOO_LONG, PASSWORD_IS_IDENTITY, PASSWORD_BREACHED)")
  @POST
  @Path("/password/reset")
  public ApiResponse<ResetPasswordResponse> reset(ResetPasswordRequest req) {
    Validations.validate(req);
    resetService.reset(req.token(), req.newPassword());
    return ApiResponse.ok(new ResetPasswordResponse(true));
  }
}
