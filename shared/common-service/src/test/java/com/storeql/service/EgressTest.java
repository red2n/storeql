package com.storeql.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An address typed by a business, called from inside the cluster: what it may be, and what it may
 * resolve to.
 */
class EgressTest {

  private static InetAddress ip(String literal) {
    try {
      return InetAddress.getByName(literal);
    } catch (UnknownHostException e) {
      throw new IllegalStateException(e);
    }
  }

  /** A resolver that answers from a table, so no test depends on real DNS. */
  private static Egress egress(Set<String> own, Map<String, String> dns) {
    return new Egress(
        own,
        host -> {
          String a = dns.get(host);
          if (a == null) throw new UnknownHostException(host);
          return new InetAddress[] {ip(a)};
        });
  }

  private static Egress.Kind refusal(Egress e, String url) {
    return assertThrows(Egress.Refused.class, () -> e.check(url)).kind();
  }

  @Test
  @DisplayName("A provider on a public address over HTTPS is called")
  void aPublicHttpsProviderIsCalled() {
    Egress e = egress(Set.of(), Map.of("login.example.com", "93.184.216.34"));
    assertEquals("login.example.com", e.check("https://login.example.com/tenant/v2.0").getHost());
  }

  @Test
  @DisplayName("Plain HTTP, credentials in the URL, a fragment, or no host: refused")
  void theShapeIsHeldFirst() {
    Egress e = egress(Set.of(), Map.of("login.example.com", "93.184.216.34"));
    assertEquals(Egress.Kind.ADDRESS, refusal(e, "http://login.example.com"));
    assertEquals(Egress.Kind.ADDRESS, refusal(e, "https://user:pw@login.example.com"));
    assertEquals(Egress.Kind.ADDRESS, refusal(e, "https://login.example.com/#x"));
    assertEquals(Egress.Kind.ADDRESS, refusal(e, "file:///etc/passwd"));
    assertEquals(Egress.Kind.ADDRESS, refusal(e, "not a url at all"));
    assertEquals(Egress.Kind.ADDRESS, refusal(e, null));
  }

  @Test
  @DisplayName("A name that resolves inside the network is refused, whatever it is called")
  void internalAddressesAreRefused() {
    Egress e =
        egress(
            Set.of(),
            Map.of(
                "metadata.example.com", "169.254.169.254",
                "db.example.com", "10.0.0.5",
                "consul.example.com", "172.18.0.4",
                "home.example.com", "192.168.1.1",
                "loop.example.com", "127.0.0.1",
                "v6loop.example.com", "::1",
                "v6private.example.com", "fd12:3456::1",
                "cgnat.example.com", "100.64.3.2",
                "zero.example.com", "0.0.0.0"));
    for (String host :
        new String[] {
          "metadata.example.com",
          "db.example.com",
          "consul.example.com",
          "home.example.com",
          "loop.example.com",
          "v6loop.example.com",
          "v6private.example.com",
          "cgnat.example.com",
          "zero.example.com"
        }) {
      assertEquals(Egress.Kind.ADDRESS, refusal(e, "https://" + host + "/"), host);
    }
    assertEquals(
        Egress.Kind.ADDRESS,
        refusal(
            egress(Set.of(), Map.of("169.254.169.254", "169.254.169.254")),
            "https://169.254.169.254/"),
        "an address typed as a literal is judged the same");
  }

  @Test
  @DisplayName("A name that does not resolve is unreachable, not refused")
  void anUnknownNameIsUnreachable() {
    assertEquals(
        Egress.Kind.UNRESOLVED, refusal(egress(Set.of(), Map.of()), "https://nowhere.example.com"));
  }

  @Test
  @DisplayName("A host the deployment names as its own is reached over HTTP, and only that host")
  void theDeploymentsOwnProviderIsReached() {
    Egress e = egress(Set.of("mock-idp"), Map.of("other", "10.0.0.9"));
    assertEquals(8080, e.check("http://mock-idp:8080/storeql").getPort());
    assertEquals(Egress.Kind.ADDRESS, refusal(e, "http://other:8080/"));
    assertEquals(Egress.Kind.ADDRESS, refusal(e, "https://other/"));
  }
}
