package com.storeql.customer.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.storeql.customer.repo.CustomerRepository;
import com.storeql.ids.Ids;
import com.storeql.web.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The customer list's {@code q}: trimmed, capped, and a blank one lists as if none were given. */
@ExtendWith(MockitoExtension.class)
class CustomerServiceListTest {

  private static final UUID TENANT = Ids.newId();

  @Mock CustomerRepository repo;
  private CustomerService service;

  @BeforeEach
  void setUp() {
    service = new CustomerService();
    service.repo = repo;
  }

  @Test
  void aBlankQueryListsExactlyAsNoQueryDoes() {
    service.list(TENANT, "   ", null, 20);
    service.list(TENANT, null, null, 20);
    verify(repo, org.mockito.Mockito.times(2)).listCustomers(TENANT, null, null, null, 20);
  }

  @Test
  void theQueryIsTrimmedAndThePageStillCapped() {
    String after = Ids.newId().toString();
    // "ada" is not phone-shaped, so no E.164 lookup is attempted (and none is stubbed here).
    service.list(TENANT, "  ada  ", after, 500);
    verify(repo).listCustomers(TENANT, "ada", null, after, 100);
  }

  @Test
  void aQueryOverAHundredCharactersIsRefusedBeforeTheDatabaseIsAsked() {
    ApiException e =
        assertThrows(ApiException.class, () -> service.list(TENANT, "x".repeat(101), null, 20));
    assertThat(e.status(), is(400));
    assertThat(e.code(), is("VALIDATION_FAILED"));
    assertThat(e.details(), contains("q: at most 100 characters"));
    verifyNoInteractions(repo);
  }

  @Test
  void aHundredCharactersAfterTrimmingIsAccepted() {
    String hundred = "x".repeat(100);
    service.list(TENANT, "  " + hundred + "  ", null, 20);
    verify(repo).listCustomers(TENANT, hundred, null, null, 20);
  }
}
