package com.storeql.iam.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.storeql.ids.Ids;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Which logins a password sign-in may open (29 Sep 2026): a shopper's account and a business
 * account are separate identities, so the kind asked for is tried alone while the address holds one
 * of it, and the other only when it holds none.
 */
class UserTest {

  private static final User SHOPPER = login(null, User.TYPE_CUSTOMER);
  private static final User FOUNDER = login(null, User.TYPE_STAFF);
  private static final User CASHIER = login(Ids.newId(), User.TYPE_STAFF);

  @Test
  void aStorefrontSignInTriesOnlyTheShoppersAccount() {
    List<User> logins = List.of(CASHIER, SHOPPER, FOUNDER);
    assertEquals(List.of(SHOPPER), User.signInCandidates(logins, User.TYPE_CUSTOMER));
  }

  @Test
  void aBusinessSignInTriesOnlyTheBusinessAccountsInTheOrderGiven() {
    List<User> logins = List.of(CASHIER, SHOPPER, FOUNDER);
    assertEquals(List.of(CASHIER, FOUNDER), User.signInCandidates(logins, User.TYPE_STAFF));
  }

  @Test
  void anAddressWithOneKindOnlySignsInWithItFromAnywhere() {
    assertEquals(List.of(SHOPPER), User.signInCandidates(List.of(SHOPPER), User.TYPE_STAFF));
    assertEquals(List.of(FOUNDER), User.signInCandidates(List.of(FOUNDER), User.TYPE_CUSTOMER));
    assertEquals(List.of(), User.signInCandidates(List.of(), User.TYPE_STAFF));
  }

  @Test
  void aSignInThatNamesItsKindNeverOpensTheOther() {
    List<User> logins = List.of(CASHIER, SHOPPER, FOUNDER);
    assertEquals(List.of(SHOPPER), User.ofKind(logins, User.TYPE_CUSTOMER));
    assertEquals(List.of(CASHIER, FOUNDER), User.ofKind(logins, User.TYPE_STAFF));
    assertEquals(List.of(), User.ofKind(List.of(SHOPPER), User.TYPE_STAFF));
    assertEquals(List.of(), User.ofKind(List.of(FOUNDER), User.TYPE_CUSTOMER));
  }

  private static User login(UUID tenantId, String type) {
    Instant now = Instant.now();
    return new User(
        Ids.newId(), tenantId, type, "same@example.com", null, "hash", "ACTIVE", now, now);
  }
}
