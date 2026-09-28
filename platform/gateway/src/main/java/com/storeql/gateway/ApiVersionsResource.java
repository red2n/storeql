package com.storeql.gateway;

import com.storeql.web.ApiResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Clock;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * {@code GET /api/versions} (22.8): what versions of the API exist, which is current, when the
 * unversioned alias was deprecated and when it stops, where each version's OpenAPI description is,
 * and the policy in a paragraph. Public: it is the one thing an integrator reads before holding any
 * credential, and it names no business.
 */
@Path("/api/versions")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "API versions")
public class ApiVersionsResource {

  @Inject GatewayConfig config;

  Clock clock = Clock.systemUTC();

  @Operation(
      summary = "The API's versions and their policy",
      description =
          "The current version, every version still answered with its status (current, supported,"
              + " deprecated, retired), the deprecated unversioned alias with the day it was"
              + " deprecated and the day it stops, where a version's OpenAPI description is, and"
              + " the versioning policy. No credential is needed.")
  @GET
  public Response versions() {
    return Response.ok(ApiResponse.ok(ApiVersions.describe(config.apiVersions(), clock.instant())))
        .header("Cache-Control", "max-age=3600")
        .build();
  }
}
