package com.storeql.iam;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.storeql.iam.mfa.MfaTestAuthenticator;
import com.storeql.iam.mfa.Totp;
import com.storeql.iam.repo.UserRepository;
import com.storeql.iam.service.AuthService;
import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Second factors over HTTP and Postgres (20.12): an authenticator app, recovery codes and a passkey
 * each answering a sign-in; a business's rule making a login set one up; the lost-phone reset; and
 * what must not work — a code twice, a guess too many, another login's waiting sign-in, a passkey
 * signed for another site, the last factor removed against the rule.
 *
 * <p>Requests carry the identity headers the gateway would have stamped from a verified token.
 */
@HelidonTest
class MfaIT {

  private static final PostgresSupport PG;
  private static final String PASSWORD = "a phrase long enough";
  private static final String ORIGIN = "http://localhost:8088";
  private static final String CONSUMER = "mfa-it";

  static {
    PG = PostgresSupport.start();
    PG.migrate("classpath:db/migration");
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    System.setProperty("storeql.jwt.secret", "integration-test-secret-of-at-least-32-chars");
  }

  @Inject WebTarget target;
  @Inject UserRepository users;
  @Inject AuthService auth;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  /** A response, read once. */
  private record Answer(int status, JsonObject body) {
    JsonObject data() {
      return body.getJsonObject("data");
    }

    String code() {
      return body.containsKey("code") ? body.getString("code") : null;
    }
  }

  /** Who a request comes from, as the gateway would have stamped it. */
  private record Caller(UUID userId, String roles, UUID tenantId, String scope) {
    static final Caller NOBODY = new Caller(null, null, null, null);
  }

  private Answer call(String method, String path, Caller who, String json) {
    Invocation.Builder b = target.path(path).request();
    if (who.userId() != null) b = b.header("X-User-Id", who.userId());
    if (who.roles() != null) b = b.header("X-Roles", who.roles());
    if (who.tenantId() != null) b = b.header("X-Tenant-Id", who.tenantId());
    if (who.scope() != null) b = b.header("X-Auth-Scope", who.scope());
    Response r =
        switch (method) {
          case "GET" -> b.get();
          case "DELETE" -> b.delete();
          case "PUT" -> b.put(Entity.entity(json, MediaType.APPLICATION_JSON));
          default -> b.post(Entity.entity(json == null ? "{}" : json, MediaType.APPLICATION_JSON));
        };
    String text = r.readEntity(String.class);
    JsonObject body =
        text == null || text.isBlank()
            ? JsonObject.EMPTY_JSON_OBJECT
            : Json.createReader(new StringReader(text)).readObject();
    return new Answer(r.getStatus(), body);
  }

  private static String credentials(String email) {
    return "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}";
  }

  private UUID register(String email) {
    Answer a = call("POST", "/auth/register", Caller.NOBODY, credentials(email));
    assertThat(a.body().toString(), a.status(), is(201));
    return Ids.parse(JWT.decode(a.data().getString("accessToken")).getSubject());
  }

  /**
   * A staff login made in the business the one way there is — staff provisioning — with {@link
   * #PASSWORD}; a StaffAssigned then binds it, as it binds only a login already there.
   */
  private UUID provisioned(UUID tenant, String email) {
    return Ids.parse(auth.provisionStaff(tenant, email, PASSWORD).userId());
  }

  private Answer login(String email) {
    return call("POST", "/auth/login", Caller.NOBODY, credentials(email));
  }

  private static String code(String base32Secret, int stepsFromNow) {
    return Totp.code(Totp.fromBase32(base32Secret), Totp.stepAt(Instant.now()) + stepsFromNow);
  }

  private Answer answer(String mfaToken, String method, String code) {
    return call(
        "POST",
        "/auth/mfa/login",
        Caller.NOBODY,
        "{\"mfaToken\":\""
            + mfaToken
            + "\",\"method\":\""
            + method
            + "\",\"code\":\""
            + code
            + "\"}");
  }

  /** Sets an authenticator app up for a login; returns its secret and recovery codes. */
  private record Enrolled(String secret, List<String> recoveryCodes) {}

  private Enrolled enrolTotp(Caller who) {
    Answer begun = call("POST", "/auth/mfa/totp", who, null);
    assertThat(begun.body().toString(), begun.status(), is(200));
    String secret = begun.data().getString("secret");
    // The step before now: it leaves this step's code and the next one's for the sign-ins.
    Answer confirmed =
        call("POST", "/auth/mfa/totp/confirm", who, "{\"code\":\"" + code(secret, -1) + "\"}");
    assertThat(confirmed.body().toString(), confirmed.status(), is(200));
    return new Enrolled(
        secret,
        confirmed
            .data()
            .getJsonArray("recoveryCodes")
            .getValuesAs(v -> ((jakarta.json.JsonString) v).getString()));
  }

  // ── an authenticator app and recovery codes ────────────────────────────────

  @Test
  @DisplayName("An authenticator app: set up, asked for at every sign-in, and no code works twice")
  void anAuthenticatorApp() {
    String email = "mfa-totp@example.com";
    UUID me = register(email);
    Caller self = new Caller(me, "CUSTOMER", null, null);

    assertThat(login(email).data().containsKey("accessToken"), is(true));
    assertThat(call("GET", "/auth/mfa", self, null).data().getBoolean("totp"), is(false));

    Answer begun = call("POST", "/auth/mfa/totp", self, null);
    String secret = begun.data().getString("secret");
    assertThat(begun.data().getString("otpauthUri"), containsString("secret=" + secret));
    assertThat(begun.data().getString("otpauthUri"), containsString("issuer=StoreQL"));
    assertThat(
        "not a factor until it is confirmed: sign-in still answers with tokens",
        login(email).data().containsKey("accessToken"),
        is(true));

    Answer wrong = call("POST", "/auth/mfa/totp/confirm", self, "{\"code\":\"000000\"}");
    assertThat(wrong.status(), is(400));
    assertThat(wrong.code(), is("MFA_CODE_INVALID"));

    String first = code(secret, -1);
    Answer confirmed = call("POST", "/auth/mfa/totp/confirm", self, "{\"code\":\"" + first + "\"}");
    assertThat(confirmed.body().toString(), confirmed.status(), is(200));
    assertThat(confirmed.data().getJsonArray("recoveryCodes").size(), is(10));
    assertThat(
        "a full session gets no new tokens for it",
        confirmed.data().containsKey("tokens"),
        is(false));
    assertThat(call("POST", "/auth/mfa/totp", self, null).code(), is("MFA_TOTP_ALREADY_ACTIVE"));

    Answer status = call("GET", "/auth/mfa", self, null);
    assertThat(status.data().getBoolean("totp"), is(true));
    assertThat(status.data().getInt("recoveryCodesLeft"), is(10));

    // Now every sign-in owes the second factor, and says so instead of giving tokens.
    Answer owed = login(email);
    assertThat(owed.status(), is(200));
    assertThat(owed.data().getBoolean("mfaRequired"), is(true));
    assertThat(owed.data().containsKey("accessToken"), is(false));
    assertThat(owed.data().containsKey("refreshToken"), is(false));
    assertThat(
        owed.data().getJsonArray("mfaMethods").toString(), is("[\"TOTP\",\"RECOVERY_CODE\"]"));
    String waiting = owed.data().getString("mfaToken");

    assertThat(
        "the code that confirmed the app is spent",
        answer(waiting, "TOTP", first).code(),
        is("MFA_CODE_INVALID"));
    Answer in = answer(waiting, "TOTP", code(secret, 0));
    assertThat(in.body().toString(), in.status(), is(200));
    DecodedJWT token = JWT.decode(in.data().getString("accessToken"));
    assertThat(token.getClaim("amr").asList(String.class), contains("pwd", "otp"));
    assertThat(
        "the waiting sign-in is answered once",
        answer(waiting, "TOTP", code(secret, 1)).code(),
        is("MFA_CHALLENGE_EXPIRED"));

    // The session is renewed as what it was.
    Answer renewed =
        call(
            "POST",
            "/auth/refresh",
            Caller.NOBODY,
            "{\"refreshToken\":\"" + in.data().getString("refreshToken") + "\"}");
    assertThat(renewed.body().toString(), renewed.status(), is(200));
    assertThat(
        JWT.decode(renewed.data().getString("accessToken")).getClaim("amr").asList(String.class),
        contains("pwd", "otp"));
  }

  @Test
  @DisplayName("Recovery codes work once each; five wrong answers end the wait")
  void recoveryCodesAndTheLimitOnGuessing() {
    String email = "mfa-recovery@example.com";
    UUID me = register(email);
    Caller self = new Caller(me, "CUSTOMER", null, null);
    Enrolled app = enrolTotp(self);

    String waiting = login(email).data().getString("mfaToken");
    String spare = app.recoveryCodes().get(0);
    Answer in = answer(waiting, "RECOVERY_CODE", spare.toLowerCase().replace("-", " "));
    assertThat(in.body().toString(), in.status(), is(200));
    assertThat(call("GET", "/auth/mfa", self, null).data().getInt("recoveryCodesLeft"), is(9));

    String again = login(email).data().getString("mfaToken");
    assertThat(
        "a recovery code is spent",
        answer(again, "RECOVERY_CODE", spare).code(),
        is("MFA_CODE_INVALID"));
    assertThat(answer(again, "RECOVERY_CODE", "AAAA-BBBB-CCCC").code(), is("MFA_CODE_INVALID"));
    assertThat(answer(again, "SMS", "123456").code(), is("MFA_METHOD_UNKNOWN"));

    // Guessing: the sign-in dies at the fifth wrong answer, and the right code comes too late.
    String guessed = login(email).data().getString("mfaToken");
    for (int i = 0; i < 5; i++) {
      assertThat(answer(guessed, "TOTP", String.format("%06d", 100000 + i)).status(), is(401));
    }
    Answer late = answer(guessed, "TOTP", code(app.secret(), 0));
    assertThat(late.status(), is(401));
    assertThat(late.code(), is("MFA_CHALLENGE_EXPIRED"));
    // The password again opens a new wait, and the right code still works there.
    Answer fresh = answer(login(email).data().getString("mfaToken"), "TOTP", code(app.secret(), 0));
    assertThat(fresh.body().toString(), fresh.status(), is(200));

    // New recovery codes ask for the password, and the old ones stop working.
    assertThat(
        call("POST", "/auth/mfa/recovery-codes", self, "{\"password\":\"not the password\"}")
            .status(),
        is(401));
    Answer regenerated =
        call("POST", "/auth/mfa/recovery-codes", self, "{\"password\":\"" + PASSWORD + "\"}");
    assertThat(regenerated.data().getJsonArray("recoveryCodes").size(), is(10));
    String old = login(email).data().getString("mfaToken");
    assertThat(
        answer(old, "RECOVERY_CODE", app.recoveryCodes().get(1)).code(), is("MFA_CODE_INVALID"));
  }

  @Test
  @DisplayName("Recovery codes need a factor, and an app never started cannot be confirmed")
  void recoveryCodesAndConfirmationNeedAFactor() {
    String email = "mfa-nofactor@example.com";
    UUID me = register(email);
    Caller self = new Caller(me, "CUSTOMER", null, null);

    Answer notStarted = call("POST", "/auth/mfa/totp/confirm", self, "{\"code\":\"123456\"}");
    assertThat(notStarted.body().toString(), notStarted.status(), is(400));
    assertThat(notStarted.code(), is("MFA_TOTP_NOT_STARTED"));

    Answer noFactor =
        call("POST", "/auth/mfa/recovery-codes", self, "{\"password\":\"" + PASSWORD + "\"}");
    assertThat(noFactor.body().toString(), noFactor.status(), is(400));
    assertThat(noFactor.code(), is("MFA_NOT_ENROLLED"));
    assertThat(noFactor.body().containsKey("data"), is(false));

    Answer status = call("GET", "/auth/mfa", self, null);
    assertThat("no factor was made", status.data().getBoolean("totp"), is(false));
    assertThat("no codes were stored", status.data().getInt("recoveryCodesLeft"), is(0));
    assertThat(
        "sign-in still answers with tokens",
        login(email).data().containsKey("accessToken"),
        is(true));

    // The same once the app is set up and taken away again.
    enrolTotp(self);
    Answer removed =
        call("POST", "/auth/mfa/totp/remove", self, "{\"password\":\"" + PASSWORD + "\"}");
    assertThat(removed.body().toString(), removed.status(), is(200));
    Answer again = call("POST", "/auth/mfa/totp/confirm", self, "{\"code\":\"123456\"}");
    assertThat(again.status(), is(400));
    assertThat(again.code(), is("MFA_TOTP_NOT_STARTED"));
  }

  @Test
  @DisplayName(
      "One login's waiting sign-in is no use with another login's code, and rubbish is refused")
  void aWaitingSignInBelongsToItsLogin() {
    UUID ana = register("mfa-ana@example.com");
    UUID ben = register("mfa-ben@example.com");
    enrolTotp(new Caller(ana, "CUSTOMER", null, null));
    Enrolled bens = enrolTotp(new Caller(ben, "CUSTOMER", null, null));

    String anasWait = login("mfa-ana@example.com").data().getString("mfaToken");
    assertThat(answer(anasWait, "TOTP", code(bens.secret(), 0)).code(), is("MFA_CODE_INVALID"));
    assertThat(
        answer(anasWait, "RECOVERY_CODE", bens.recoveryCodes().get(0)).code(),
        is("MFA_CODE_INVALID"));

    assertThat(answer("not-a-token", "TOTP", "123456").code(), is("MFA_CHALLENGE_EXPIRED"));
    assertThat(answer("", "TOTP", "123456").status(), is(400));
    assertThat(answer("x".repeat(500), "TOTP", "123456").status(), is(400));
    assertThat(
        call(
                "POST",
                "/auth/mfa/login",
                Caller.NOBODY,
                "{\"mfaToken\":\"" + anasWait + "\",\"method\":\"PASSKEY\"}")
            .code(),
        is("MFA_CODE_INVALID"));
    assertThat(
        "a login with no passkey is offered no passkey challenge",
        call(
                "POST",
                "/auth/mfa/login/passkey-options",
                Caller.NOBODY,
                "{\"mfaToken\":\"" + anasWait + "\"}")
            .code(),
        is("MFA_METHOD_NOT_ENROLLED"));

    // Nobody sets up, reads or removes a factor without being somebody.
    assertThat(call("GET", "/auth/mfa", Caller.NOBODY, null).status(), is(401));
    assertThat(call("POST", "/auth/mfa/totp", Caller.NOBODY, null).status(), is(401));
  }

  @Test
  @DisplayName("The password buys five guesses at a time, but not for ever: twenty and it locks")
  void guessingAcrossSignInsLocksTheFactor() {
    String email = "mfa-grind@example.com";
    UUID me = register(email);
    Enrolled app = enrolTotp(new Caller(me, "CUSTOMER", null, null));

    for (int signIn = 0; signIn < 4; signIn++) {
      String waiting = login(email).data().getString("mfaToken");
      for (int guess = 0; guess < 5; guess++) {
        assertThat(
            answer(waiting, "TOTP", String.format("%06d", 200000 + guess)).status(), is(401));
      }
    }
    // The twenty-first answer is the right one, and it waits like the rest.
    Answer locked =
        answer(login(email).data().getString("mfaToken"), "TOTP", code(app.secret(), 0));
    assertThat(locked.status(), is(429));
    assertThat(locked.code(), is("MFA_LOCKED"));
    Answer recovery =
        answer(
            login(email).data().getString("mfaToken"), "RECOVERY_CODE", app.recoveryCodes().get(0));
    assertThat("a recovery code is a guess too", recovery.status(), is(429));
  }

  // ── the business's rule ────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "A business requires a second factor of a tier: set up at the next sign-in, kept after")
  void theBusinesssRule() {
    UUID tenant = Ids.newId();
    UUID store = Ids.newId();
    UUID ownerId = register("mfa-owner@example.com");
    UUID cashierId = provisioned(tenant, "mfa-cashier@example.com");
    users.bindOwnerOnce(Ids.newId(), CONSUMER, ownerId, tenant, "OWNER");
    users.bindStaffOnce(Ids.newId(), CONSUMER, cashierId, tenant, "CASHIER", store);
    Caller owner = new Caller(ownerId, "OWNER,CUSTOMER", tenant, null);
    Caller cashier = new Caller(cashierId, "CASHIER,CUSTOMER", tenant, null);

    // A cashier's session from before the rule.
    Answer before = login("mfa-cashier@example.com");
    assertThat(before.data().containsKey("accessToken"), is(true));

    assertThat(
        call("GET", "/auth/admin/mfa-policy", owner, null)
            .data()
            .getJsonArray("requiredTiers")
            .size(),
        is(0));
    assertThat(
        call("PUT", "/auth/admin/mfa-policy", cashier, "{\"requiredTiers\":[\"CASHIER\"]}")
            .status(),
        is(403));
    assertThat(
        call(
                "PUT",
                "/auth/admin/mfa-policy",
                new Caller(ownerId, "MANAGER", tenant, null),
                "{\"requiredTiers\":[]}")
            .status(),
        is(403));
    assertThat(
        call("PUT", "/auth/admin/mfa-policy", owner, "{\"requiredTiers\":[\"JANITOR\"]}").code(),
        is("MFA_POLICY_TIER_UNKNOWN"));
    assertThat(call("PUT", "/auth/admin/mfa-policy", owner, "{}").status(), is(400));
    Answer set =
        call(
            "PUT",
            "/auth/admin/mfa-policy",
            owner,
            "{\"requiredTiers\":[\"MANAGER\",\"CASHIER\"]}");
    assertThat(set.body().toString(), set.status(), is(200));
    assertThat(
        set.data().getJsonArray("requiredTiers").toString(), is("[\"CASHIER\",\"MANAGER\"]"));
    assertThat(call("GET", "/auth/mfa", cashier, null).data().getBoolean("required"), is(true));
    assertThat(call("GET", "/auth/mfa", owner, null).data().getBoolean("required"), is(false));

    // The old session was a password's: it is not renewed.
    Answer renewal =
        call(
            "POST",
            "/auth/refresh",
            Caller.NOBODY,
            "{\"refreshToken\":\"" + before.data().getString("refreshToken") + "\"}");
    assertThat(renewal.status(), is(401));
    assertThat(renewal.code(), is("MFA_REQUIRED"));

    // The next sign-in gets a token that says who and nothing else.
    Answer owed = login("mfa-cashier@example.com");
    assertThat(owed.data().getBoolean("mfaEnrolmentRequired"), is(true));
    assertThat(owed.data().containsKey("refreshToken"), is(false));
    DecodedJWT limited = JWT.decode(owed.data().getString("accessToken"));
    assertThat(limited.getClaim("scope").asString(), is("mfa-enrol"));
    assertThat(limited.getClaim("roles").asList(String.class).isEmpty(), is(true));
    assertThat(limited.getClaim("tenant").isMissing(), is(true));

    // With it — and the scope the gateway stamps from it — the factor is set up, and the answer
    // is the real session.
    Caller enrolling = new Caller(cashierId, "", null, "mfa-enrol");
    String secret = call("POST", "/auth/mfa/totp", enrolling, null).data().getString("secret");
    Answer done =
        call(
            "POST", "/auth/mfa/totp/confirm", enrolling, "{\"code\":\"" + code(secret, -1) + "\"}");
    assertThat(done.body().toString(), done.status(), is(200));
    assertThat(done.data().getJsonArray("recoveryCodes").size(), is(10));
    DecodedJWT real = JWT.decode(done.data().getJsonObject("tokens").getString("accessToken"));
    assertThat(real.getClaim("roles").asList(String.class), hasItem("CASHIER"));
    assertThat(real.getClaim("tenant").asString(), is(tenant.toString()));
    assertThat(real.getClaim("amr").asList(String.class), contains("pwd", "otp"));
    assertThat(real.getClaim("scope").isMissing(), is(true));

    // The rule holds the factor in place.
    assertThat(
        call("POST", "/auth/mfa/totp/remove", cashier, "{\"password\":\"wrong password here\"}")
            .status(),
        is(401));
    Answer refused =
        call("POST", "/auth/mfa/totp/remove", cashier, "{\"password\":\"" + PASSWORD + "\"}");
    assertThat(refused.status(), is(409));
    assertThat(refused.code(), is("MFA_REQUIRED_BY_POLICY"));

    // The owner is not in a required tier: theirs comes off, with the password.
    enrolTotp(owner);
    assertThat(
        call("POST", "/auth/mfa/totp/remove", owner, "{\"password\":\"" + PASSWORD + "\"}")
            .status(),
        is(200));
    Answer status = call("GET", "/auth/mfa", owner, null);
    assertThat(status.data().getBoolean("totp"), is(false));
    assertThat(
        "the codes go with the last factor", status.data().getInt("recoveryCodesLeft"), is(0));
    assertThat(login("mfa-owner@example.com").data().containsKey("accessToken"), is(true));
  }

  @Test
  @DisplayName("A lost phone: the owner resets their own staff, nobody else's, never themselves")
  void theLostPhone() {
    UUID tenant = Ids.newId();
    UUID other = Ids.newId();
    UUID store = Ids.newId();
    UUID ownerId = register("mfa-reset-owner@example.com");
    UUID staffId = provisioned(tenant, "mfa-reset-staff@example.com");
    UUID strangerId = provisioned(other, "mfa-reset-stranger@example.com");
    users.bindOwnerOnce(Ids.newId(), CONSUMER, ownerId, tenant, "OWNER");
    users.bindStaffOnce(Ids.newId(), CONSUMER, staffId, tenant, "CASHIER", store);
    users.bindStaffOnce(Ids.newId(), CONSUMER, strangerId, other, "CASHIER", Ids.newId());
    Caller owner = new Caller(ownerId, "OWNER", tenant, null);
    enrolTotp(new Caller(staffId, "CASHIER", tenant, null));
    enrolTotp(new Caller(strangerId, "CASHIER", other, null));
    assertThat(login("mfa-reset-staff@example.com").data().getBoolean("mfaRequired"), is(true));

    assertThat(
        call(
                "DELETE",
                "/auth/admin/staff-users/" + staffId + "/mfa",
                new Caller(staffId, "CASHIER", tenant, null),
                null)
            .status(),
        is(403));
    assertThat(
        call("DELETE", "/auth/admin/staff-users/" + ownerId + "/mfa", owner, null).code(),
        is("MFA_RESET_SELF"));
    Answer notOurs = call("DELETE", "/auth/admin/staff-users/" + strangerId + "/mfa", owner, null);
    assertThat("another business's staff are not there to be found", notOurs.status(), is(404));
    assertThat(notOurs.code(), is("USER_NOT_FOUND"));
    Answer nobody = call("DELETE", "/auth/admin/staff-users/" + Ids.newId() + "/mfa", owner, null);
    assertThat(nobody.status(), is(404));
    assertThat(nobody.code(), is("USER_NOT_FOUND"));
    assertThat(
        call("DELETE", "/auth/admin/staff-users/not-a-uuid/mfa", owner, null).status(), is(400));
    assertThat(login("mfa-reset-stranger@example.com").data().getBoolean("mfaRequired"), is(true));

    assertThat(
        call("DELETE", "/auth/admin/staff-users/" + staffId + "/mfa", owner, null).status(),
        is(200));
    Answer after = login("mfa-reset-staff@example.com");
    assertThat("no factor left to ask for", after.data().containsKey("accessToken"), is(true));
    assertThat(after.data().containsKey("mfaRequired"), is(false));
  }

  // ── passkeys ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A passkey: registered, signs in, and is no use from another site or another key")
  void aPasskey() {
    String email = "mfa-passkey@example.com";
    UUID me = register(email);
    Caller self = new Caller(me, "CUSTOMER", null, null);
    MfaTestAuthenticator key = new MfaTestAuthenticator("localhost");

    Answer options = call("POST", "/auth/mfa/passkeys/options", self, null);
    assertThat(options.body().toString(), options.status(), is(200));
    assertThat(options.data().getString("rpId"), is("localhost"));
    assertThat(options.data().getString("attestation"), is("none"));
    assertThat(options.data().getString("userVerification"), is("required"));
    assertThat(options.data().getJsonArray("algorithms").toString(), is("[-7,-257]"));
    String registration = options.data().getString("registrationToken");
    byte[] challenge = Base64.getUrlDecoder().decode(options.data().getString("challenge"));

    // Made for a look-alike origin: refused, and the challenge is spent with it.
    assertThat(
        call(
                "POST",
                "/auth/mfa/passkeys",
                self,
                key.registrationJson(
                    registration, "Ana's laptop", challenge, "http://localhost.evil.test"))
            .code(),
        is("MFA_PASSKEY_INVALID"));
    assertThat(
        call(
                "POST",
                "/auth/mfa/passkeys",
                self,
                key.registrationJson(registration, "Ana's laptop", challenge, ORIGIN))
            .code(),
        is("MFA_CHALLENGE_EXPIRED"));

    Answer fresh = call("POST", "/auth/mfa/passkeys/options", self, null);
    String token = fresh.data().getString("registrationToken");
    byte[] freshChallenge = Base64.getUrlDecoder().decode(fresh.data().getString("challenge"));
    // Somebody else holding the registration token cannot finish it as themselves.
    UUID thief = register("mfa-passkey-thief@example.com");
    assertThat(
        call(
                "POST",
                "/auth/mfa/passkeys",
                new Caller(thief, "CUSTOMER", null, null),
                key.registrationJson(token, "mine now", freshChallenge, ORIGIN))
            .code(),
        is("MFA_CHALLENGE_EXPIRED"));
    Answer made =
        call(
            "POST",
            "/auth/mfa/passkeys",
            self,
            key.registrationJson(token, "Ana's laptop", freshChallenge, ORIGIN));
    assertThat(made.body().toString(), made.status(), is(200));
    assertThat(
        "the first factor brings the recovery codes",
        made.data().getJsonArray("recoveryCodes").size(),
        is(10));

    Answer status = call("GET", "/auth/mfa", self, null);
    assertThat(status.data().getJsonArray("passkeys").size(), is(1));
    JsonObject listed = status.data().getJsonArray("passkeys").getJsonObject(0);
    assertThat(listed.getString("name"), is("Ana's laptop"));
    assertThat("never the key", status.body().toString(), not(containsString("publicKey")));

    // The same authenticator again: already registered.
    Answer second = call("POST", "/auth/mfa/passkeys/options", self, null);
    assertThat(second.data().getJsonArray("excludeCredentials").size(), is(1));
    assertThat(
        call(
                "POST",
                "/auth/mfa/passkeys",
                self,
                key.registrationJson(
                    second.data().getString("registrationToken"),
                    "again",
                    Base64.getUrlDecoder().decode(second.data().getString("challenge")),
                    ORIGIN))
            .code(),
        is("MFA_PASSKEY_ALREADY_REGISTERED"));

    // Sign-in.
    Answer owed = login(email);
    assertThat(
        owed.data().getJsonArray("mfaMethods").toString(), is("[\"PASSKEY\",\"RECOVERY_CODE\"]"));
    String waiting = owed.data().getString("mfaToken");
    assertThat(
        "no challenge asked for yet, so nothing a passkey signed can match",
        call(
                "POST",
                "/auth/mfa/login",
                Caller.NOBODY,
                key.assertionJson(waiting, new byte[32], ORIGIN))
            .code(),
        is("MFA_CODE_INVALID"));
    Answer ask =
        call(
            "POST",
            "/auth/mfa/login/passkey-options",
            Caller.NOBODY,
            "{\"mfaToken\":\"" + waiting + "\"}");
    assertThat(ask.body().toString(), ask.status(), is(200));
    assertThat(ask.data().getJsonArray("allowCredentials").size(), is(1));
    byte[] toSign = Base64.getUrlDecoder().decode(ask.data().getString("challenge"));

    assertThat(
        "signed for a phishing site's origin",
        call(
                "POST",
                "/auth/mfa/login",
                Caller.NOBODY,
                key.assertionJson(waiting, toSign, "https://storeql.evil.test"))
            .code(),
        is("MFA_CODE_INVALID"));
    MfaTestAuthenticator stranger = new MfaTestAuthenticator("localhost");
    assertThat(
        "another authenticator claiming this credential",
        call(
                "POST",
                "/auth/mfa/login",
                Caller.NOBODY,
                stranger.assertionJsonAs(key.credentialIdText(), waiting, toSign, ORIGIN))
            .code(),
        is("MFA_CODE_INVALID"));

    Answer in =
        call("POST", "/auth/mfa/login", Caller.NOBODY, key.assertionJson(waiting, toSign, ORIGIN));
    assertThat(in.body().toString(), in.status(), is(200));
    assertThat(
        JWT.decode(in.data().getString("accessToken")).getClaim("amr").asList(String.class),
        contains("pwd", "hwk"));
    assertThat(
        "the same assertion again",
        call("POST", "/auth/mfa/login", Caller.NOBODY, key.lastAssertionJson(waiting)).code(),
        is("MFA_CHALLENGE_EXPIRED"));

    // Removed with the password; with it the last factor, and the codes that went with it.
    String id = listed.getString("id");
    assertThat(
        call(
                "POST",
                "/auth/mfa/passkeys/" + id + "/remove",
                self,
                "{\"password\":\"nope nope nope nope\"}")
            .status(),
        is(401));
    Answer noSuchPasskey =
        call(
            "POST",
            "/auth/mfa/passkeys/" + Ids.newId() + "/remove",
            self,
            "{\"password\":\"" + PASSWORD + "\"}");
    assertThat(noSuchPasskey.status(), is(404));
    assertThat(noSuchPasskey.code(), is("MFA_PASSKEY_NOT_FOUND"));
    assertThat(
        call(
                "POST",
                "/auth/mfa/passkeys/" + id + "/remove",
                self,
                "{\"password\":\"" + PASSWORD + "\"}")
            .status(),
        is(200));
    assertThat(login(email).data().containsKey("accessToken"), is(true));
  }

  // ── the platform administrator ─────────────────────────────────────────────

  @Test
  @DisplayName("The platform administrator is born with a second factor and is always asked for it")
  void thePlatformAdministrator() {
    String secret = Totp.base32(Totp.newSecret());
    Answer tooShort =
        call(
            "POST",
            "/bootstrap/admin",
            Caller.NOBODY,
            "{\"email\":\"root@example.com\",\"password\":\""
                + PASSWORD
                + "\",\"totpSecret\":\"ABCDEFGH\"}");
    assertThat(tooShort.code(), is("MFA_SECRET_INVALID"));
    Answer born =
        call(
            "POST",
            "/bootstrap/admin",
            Caller.NOBODY,
            "{\"email\":\"root@example.com\",\"password\":\""
                + PASSWORD
                + "\",\"totpSecret\":\""
                + secret
                + "\"}");
    assertThat(born.body().toString(), born.status(), is(201));
    Answer twice =
        call(
            "POST",
            "/bootstrap/admin",
            Caller.NOBODY,
            "{\"email\":\"root-two@example.com\",\"password\":\""
                + PASSWORD
                + "\",\"totpSecret\":\""
                + Totp.base32(Totp.newSecret())
                + "\"}");
    assertThat(twice.status(), is(409));
    assertThat(twice.code(), is("BOOTSTRAP_ALREADY_DONE"));
    assertThat(
        "the second administrator was never made", login("root-two@example.com").status(), is(401));

    Answer owed =
        call("POST", "/auth/platform-login", Caller.NOBODY, credentials("root@example.com"));
    assertThat(owed.body().toString(), owed.status(), is(200));
    assertThat(owed.data().getBoolean("mfaRequired"), is(true));
    assertThat(owed.data().containsKey("accessToken"), is(false));
    assertThat(owed.data().getJsonArray("mfaMethods").toString(), is("[\"TOTP\"]"));

    Answer in = answer(owed.data().getString("mfaToken"), "TOTP", code(secret, 0));
    assertThat(in.body().toString(), in.status(), is(200));
    DecodedJWT token = JWT.decode(in.data().getString("accessToken"));
    assertThat(token.getClaim("roles").asList(String.class), hasItem("PLATFORM_ADMIN"));
    assertThat(token.getClaim("amr").asList(String.class), contains("pwd", "otp"));

    UUID root = Ids.parse(token.getSubject());
    Caller admin = new Caller(root, "PLATFORM_ADMIN", null, null);
    assertThat(call("GET", "/auth/mfa", admin, null).data().getBoolean("required"), is(true));
    Answer refused =
        call("POST", "/auth/mfa/totp/remove", admin, "{\"password\":\"" + PASSWORD + "\"}");
    assertThat(refused.code(), is("MFA_REQUIRED_BY_POLICY"));
    assertThat(refused.body().getString("detail", ""), not(nullValue()));
  }
}
