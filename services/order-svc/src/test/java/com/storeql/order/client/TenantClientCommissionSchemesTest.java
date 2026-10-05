package com.storeql.order.client;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeql.ids.Ids;
import com.storeql.order.config.ServiceConfig;
import com.storeql.test.JsonStub;
import com.storeql.web.TenantContext;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the client says to tenant-svc when it reads the commission schemes: the path tenant-svc
 * serves, and {@code all=true} as a query. The stub answers 404 to any other path, so a query
 * written into the path string — which the WebClient escapes into the path ("…/schemes%3Fall=true")
 * — is a failed test, where before it was a statement quietly drafted without its schemes' terms.
 */
class TenantClientCommissionSchemesTest {

  private static final UUID TENANT = Ids.newId();

  private JsonStub tenantSvc;
  private TenantClient client;
  private TenantContext ctx;

  @BeforeEach
  void start() {
    tenantSvc = JsonStub.start("tenant-svc");
    client = new TenantClient();
    // Discovery is never asked: the stub's configured URL decides where the call goes.
    client.config =
        new ServiceConfig() {
          @Override
          public String consulHost() {
            return "127.0.0.1";
          }

          @Override
          public int consulPort() {
            return 1;
          }
        };
    client.init();
    ctx = mock(TenantContext.class);
    when(ctx.roles()).thenReturn(Set.of("MANAGER"));
    when(ctx.userId()).thenReturn(Ids.newId());
  }

  @AfterEach
  void stop() {
    tenantSvc.close();
  }

  @Test
  @DisplayName("The schemes are read at tenant-svc's own path, with all=true as a query")
  void theSchemesAreAskedForByPathAndQuery() {
    String scheme = Ids.newId().toString();
    tenantSvc.on(
        "GET",
        "/admin/workforce/commission/schemes",
        200,
        "{\"data\":[{\"id\":\"" + scheme + "\",\"basis\":\"PER_UNIT\",\"currency\":\"eur\"}]}");

    var terms = client.commissionSchemes(TENANT, ctx);

    assertThat("tenant-svc was reached at a path it serves", terms.isPresent(), is(true));
    assertThat(terms.get().get(Ids.parse(scheme)).basis(), is("PER_UNIT"));
    assertThat(terms.get().get(Ids.parse(scheme)).currency(), is("EUR"));
    var call = tenantSvc.calls().get(0);
    assertThat(call.path(), is("/admin/workforce/commission/schemes"));
    assertThat(call.query(), is("all=true"));
    assertThat(call.tenantId(), is(TENANT.toString()));
  }
}
