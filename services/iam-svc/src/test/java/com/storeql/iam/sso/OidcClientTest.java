package com.storeql.iam.sso;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.auth0.jwt.algorithms.Algorithm;
import com.storeql.service.Egress;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The client against a provider that holds codes to the rules: the whole exchange, and every way an
 * ID token that should not be believed is not — OpenID Connect Core §3.1.3.7, one check a test.
 */
class OidcClientTest {

  private static final String REDIRECT = "http://localhost:8090/api/iam-svc/auth/sso/callback";
  private static FakeOidcProvider provider;

  private OidcClient client;
  private Discovery discovery;

  @BeforeAll
  static void up() throws IOException {
    provider = new FakeOidcProvider();
  }

  @AfterAll
  static void down() {
    provider.close();
  }

  @BeforeEach
  void fresh() {
    provider.reset();
    client = new OidcClient();
    client.use(new Egress(Set.of("localhost")), Duration.ofSeconds(3), Clock.systemUTC());
    discovery = client.discoverFresh(provider.issuer());
  }

  /** A sign-in at the provider and the exchange that follows it, with this client's secret. */
  private IdToken signIn(FakeOidcProvider.Person person, String secret) {
    String verifier = Pkce.newVerifier();
    String nonce = Pkce.random(16);
    String code = code(person, Pkce.challenge(verifier), nonce);
    return client.exchange(
        discovery, FakeOidcProvider.CLIENT_ID, secret, code, REDIRECT, verifier, nonce);
  }

  private String code(FakeOidcProvider.Person person, String challenge, String nonce) {
    String url =
        discovery.authorizationEndpoint()
            + "?response_type=code&client_id="
            + FakeOidcProvider.CLIENT_ID
            + "&redirect_uri="
            + URLEncoder.encode(REDIRECT, StandardCharsets.UTF_8)
            + "&state=s&nonce="
            + nonce
            + "&code_challenge="
            + challenge
            + "&code_challenge_method=S256";
    return provider.authorize(url, person).get("code");
  }

  private String refusal(FakeOidcProvider.Person person) {
    return assertThrows(SsoRefused.class, () -> signIn(person, FakeOidcProvider.CLIENT_SECRET))
        .getMessage();
  }

  private static final FakeOidcProvider.Person ANA =
      FakeOidcProvider.Person.verified("ana-at-the-provider", "ana@example.com");

  // ── the exchange ────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A code, the secret and the verifier make an ID token: who, their address, how")
  void theExchange() {
    IdToken t = signIn(ANA, FakeOidcProvider.CLIENT_SECRET);
    assertThat(t.subject(), is("ana-at-the-provider"));
    assertThat(t.email(), is("ana@example.com"));
    assertThat(t.emailVerified(), is(true));
    assertThat(t.amr(), contains("pwd"));
    assertThat(t.multiFactor(), is(false));
  }

  @Test
  @DisplayName("A provider that says it asked for two factors has proved two")
  void theProvidersSecondFactor() {
    IdToken t =
        signIn(
            new FakeOidcProvider.Person("s", "s@example.com", true, List.of("pwd", "mfa", "otp")),
            FakeOidcProvider.CLIENT_SECRET);
    assertThat(t.multiFactor(), is(true));
  }

  @Test
  @DisplayName("email_verified as the string \"true\" counts; absent or \"false\" does not")
  void verifiedAsAString() {
    provider.tweakTokens(b -> b.withClaim("email_verified", "true"));
    assertThat(signIn(ANA, FakeOidcProvider.CLIENT_SECRET).emailVerified(), is(true));
    provider.tweakTokens(b -> b.withClaim("email_verified", "false"));
    assertThat(signIn(ANA, FakeOidcProvider.CLIENT_SECRET).emailVerified(), is(false));
    IdToken none =
        signIn(new FakeOidcProvider.Person("n", null, null, null), FakeOidcProvider.CLIENT_SECRET);
    assertThat(none.email(), nullValue());
    assertThat(none.emailVerified(), is(false));
    assertThat(none.amr().isEmpty(), is(true));
  }

  @Test
  @DisplayName("A wrong secret, a code spent, a code with another verifier: the provider refuses")
  void theProviderRefuses() {
    SsoRefused wrongSecret = assertThrows(SsoRefused.class, () -> signIn(ANA, "not the secret"));
    assertThat(wrongSecret.code(), is(SsoRefused.EXCHANGE_REFUSED));
    assertThat(wrongSecret.getMessage(), containsString("invalid_client"));

    String verifier = Pkce.newVerifier();
    String code = code(ANA, Pkce.challenge(verifier), "n");
    client.exchange(
        discovery,
        FakeOidcProvider.CLIENT_ID,
        FakeOidcProvider.CLIENT_SECRET,
        code,
        REDIRECT,
        verifier,
        "n");
    SsoRefused spent =
        assertThrows(
            SsoRefused.class,
            () ->
                client.exchange(
                    discovery,
                    FakeOidcProvider.CLIENT_ID,
                    FakeOidcProvider.CLIENT_SECRET,
                    code,
                    REDIRECT,
                    verifier,
                    "n"));
    assertThat(spent.code(), is(SsoRefused.EXCHANGE_REFUSED));

    String other = code(ANA, Pkce.challenge(verifier), "n");
    SsoRefused lifted =
        assertThrows(
            SsoRefused.class,
            () ->
                client.exchange(
                    discovery,
                    FakeOidcProvider.CLIENT_ID,
                    FakeOidcProvider.CLIENT_SECRET,
                    other,
                    REDIRECT,
                    Pkce.newVerifier(),
                    "n"));
    assertThat(lifted.code(), is(SsoRefused.EXCHANGE_REFUSED));
  }

  @Test
  @DisplayName("A provider that is down is unreachable, and says nothing of what it answered")
  void aProviderDown() {
    provider.breakDiscovery(true);
    SsoRefused down = assertThrows(SsoRefused.class, () -> client.discoverFresh(provider.issuer()));
    assertThat(down.code(), is(SsoRefused.UNREACHABLE));
    SsoRefused closed =
        assertThrows(SsoRefused.class, () -> client.discoverFresh("http://localhost:1"));
    assertThat(closed.code(), is(SsoRefused.UNREACHABLE));
  }

  @Test
  @DisplayName("Without the deployment naming it, a provider on this machine is not called")
  void aLocalProviderIsNotCalledUnlessNamed() {
    OidcClient strict = new OidcClient();
    strict.use(new Egress(Set.of()), Duration.ofSeconds(3), Clock.systemUTC());
    SsoRefused refused =
        assertThrows(SsoRefused.class, () -> strict.discoverFresh(provider.issuer()));
    assertThat(refused.code(), is(SsoRefused.ADDRESS_REFUSED));
  }

  // ── the ID token (§3.1.3.7) ─────────────────────────────────────────────────

  @Test
  @DisplayName("Signed by a key the provider does not publish")
  void aForgedSignature() {
    provider.signWith(FakeOidcProvider.strangersKey());
    assertThat(refusal(ANA).toLowerCase(java.util.Locale.ROOT), containsString("signature"));
  }

  @Test
  @DisplayName("Not signed at all, or signed with an HMAC")
  void notRs256() {
    provider.signWith(Algorithm.none());
    assertThat(refusal(ANA), containsString("not RS256"));
    provider.signWith(Algorithm.HMAC256("the provider's public key, perhaps"));
    assertThat(refusal(ANA), containsString("not RS256"));
  }

  @Test
  @DisplayName("Issued by somebody else, or for another client")
  void anotherIssuerOrAudience() {
    provider.tweakTokens(b -> b.withIssuer("https://evil.example.com"));
    assertThat(refusal(ANA), containsString("iss"));
    provider.tweakTokens(b -> b.withAudience("another-client"));
    assertThat(refusal(ANA), containsString("aud"));
  }

  @Test
  @DisplayName("Several audiences with no azp, or an azp that is another client")
  void theAuthorisedParty() {
    provider.tweakTokens(b -> b.withAudience(FakeOidcProvider.CLIENT_ID, "another-client"));
    assertThat(refusal(ANA), containsString("another party"));
    provider.tweakTokens(
        b ->
            b.withAudience(FakeOidcProvider.CLIENT_ID, "another-client")
                .withClaim("azp", "another-client"));
    assertThat(refusal(ANA), containsString("another party"));
    provider.tweakTokens(
        b ->
            b.withAudience(FakeOidcProvider.CLIENT_ID, "another-client")
                .withClaim("azp", FakeOidcProvider.CLIENT_ID));
    assertThat(signIn(ANA, FakeOidcProvider.CLIENT_SECRET).subject(), is("ana-at-the-provider"));
  }

  @Test
  @DisplayName("Expired beyond the minute of skew; a minute's skew is forgiven")
  void expiry() {
    provider.tweakTokens(b -> b.withExpiresAt(Instant.now().minusSeconds(120)));
    assertThat(refusal(ANA), containsString("expired"));
    provider.tweakTokens(b -> b.withExpiresAt(Instant.now().minusSeconds(20)));
    assertThat(signIn(ANA, FakeOidcProvider.CLIENT_SECRET).subject(), is("ana-at-the-provider"));
  }

  @Test
  @DisplayName("Minted for another sign-in: the nonce is not this one's")
  void anotherSignInsNonce() {
    provider.tweakTokens(b -> b.withClaim("nonce", "somebody else's"));
    assertThat(refusal(ANA), containsString("nonce"));
  }

  @Test
  @DisplayName("No subject, or one too long to be an identifier")
  void theSubject() {
    provider.tweakTokens(b -> b.withSubject(null));
    assertThat(refusal(ANA), containsString("subject"));
    provider.tweakTokens(b -> b.withSubject("x".repeat(256)));
    assertThat(refusal(ANA), containsString("subject"));
  }
}
