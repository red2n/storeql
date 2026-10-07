package com.storeql.order.client;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Reading iam-svc's staff directory, {@code GET /auth/admin/staff-users?ids=}, as it answers today:
 * the business's own staff among the ids, at the stores asked about, each with a login email;
 * anybody else is simply left out. A login the answer names is staff there; one it leaves out is
 * not; an answer that cannot be read says neither, and a replayed sale's entry then names nobody.
 */
class StaffClientParseTest {

  private static final UUID CASHIER = Ids.parse("01a0f2b0-611e-7000-8000-0000000000a1");
  private static final String OTHER = "01a0f2b0-611e-7000-8000-0000000000a9";

  @Test
  void aLoginTheDirectoryNamesIsStaffThere() {
    String body =
        "{\"data\":[{\"userId\":\"" + CASHIER + "\",\"email\":\"ben@shop.test\"}],\"meta\":{}}";
    assertThat(StaffClient.named(CASHIER, body), is(Optional.of(true)));
  }

  @Test
  void aLoginTheDirectoryLeavesOutIsNot() {
    assertThat(StaffClient.named(CASHIER, "{\"data\":[]}"), is(Optional.of(false)));
    String somebodyElse = "{\"data\":[{\"userId\":\"" + OTHER + "\",\"email\":\"a@shop.test\"}]}";
    assertThat(StaffClient.named(CASHIER, somebodyElse), is(Optional.of(false)));
  }

  @Test
  void anAnswerThatCannotBeReadSaysNeither() {
    assertThat(StaffClient.named(CASHIER, "<html>busy</html>"), is(Optional.empty()));
    assertThat(StaffClient.named(CASHIER, "{\"data\":null}"), is(Optional.empty()));
    assertThat(
        "an id that is not a UUIDv7 spoils the answer rather than naming anybody",
        StaffClient.named(CASHIER, "{\"data\":[{\"userId\":\"1-1-1-1-1\"}]}"),
        is(Optional.empty()));
  }
}
