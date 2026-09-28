package com.storeql.service;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * Which addresses a service may call on a business's say-so. An identity provider's issuer (20.x)
 * and a webhook's URL (22.6) are typed by a business, while the service sits inside the cluster
 * beside the database, the other services and a cloud's metadata endpoint. So the address is HTTPS,
 * on a public address, with no credentials in the URL — unless the deployment names its host as one
 * of its own, as local development does for the stand-ins it runs beside the stack.
 *
 * <p>The host is resolved and every address it resolves to is checked. A name that resolves
 * elsewhere between this check and the connection (DNS rebinding) is not caught here; the network
 * policy that keeps a service's egress to the internet is what covers that.
 */
public final class Egress {

  /** Resolves a host name; replaceable in tests. */
  @FunctionalInterface
  public interface Resolver {
    InetAddress[] resolve(String host) throws UnknownHostException;
  }

  private final Set<String> insecureHosts;
  private final Resolver resolver;

  /**
   * @param insecureHosts host names, lower case, reachable over plain HTTP and at a private address
   */
  public Egress(Set<String> insecureHosts, Resolver resolver) {
    this.insecureHosts = Set.copyOf(insecureHosts);
    this.resolver = resolver;
  }

  public Egress(Set<String> insecureHosts) {
    this(insecureHosts, InetAddress::getAllByName);
  }

  /** Why an address may not be called. */
  public enum Kind {
    /** Not HTTPS, credentials or a fragment in the URL, no host, or inside the network. */
    ADDRESS,
    /** The host does not resolve. */
    UNRESOLVED
  }

  /** An address this service will not call, and which of the two reasons. */
  public static final class Refused extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final Kind kind;

    public Refused(Kind kind, String message) {
      super(message);
      this.kind = kind;
    }

    public Refused(Kind kind, String message, Throwable cause) {
      super(message, cause);
      this.kind = kind;
    }

    public Kind kind() {
      return kind;
    }
  }

  /**
   * The URL, if it may be called.
   *
   * @throws Refused when it may not
   */
  public URI check(String url) {
    if (url == null) throw refused("no URL");
    URI uri;
    try {
      uri = new URI(url);
    } catch (java.net.URISyntaxException e) {
      throw new Refused(Kind.ADDRESS, "address refused: not a URL", e);
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    if (host.isEmpty()) throw refused("no host");
    if (uri.getRawUserInfo() != null) throw refused("credentials in the URL");
    if (uri.getRawFragment() != null) throw refused("a fragment");
    boolean own = insecureHosts.contains(host);
    if (!"https".equals(scheme) && !(own && "http".equals(scheme))) {
      throw refused("not HTTPS");
    }
    if (own) return uri;
    InetAddress[] addresses;
    try {
      addresses = resolver.resolve(host);
    } catch (UnknownHostException e) {
      throw new Refused(Kind.UNRESOLVED, "the host " + host + " does not resolve", e);
    }
    for (InetAddress a : addresses) {
      if (internal(a)) throw refused(host + " resolves to an address inside the network");
    }
    return uri;
  }

  /** Loopback, private, link-local, unique-local, multicast or unspecified. */
  static boolean internal(InetAddress a) {
    if (a.isLoopbackAddress()
        || a.isAnyLocalAddress()
        || a.isLinkLocalAddress()
        || a.isSiteLocalAddress()
        || a.isMulticastAddress()) {
      return true;
    }
    byte[] b = a.getAddress();
    if (a instanceof Inet6Address) {
      // fc00::/7, unique local — IPv6's private range, which isSiteLocalAddress does not cover.
      return (b[0] & 0xfe) == 0xfc;
    }
    // 100.64.0.0/10, carrier-grade NAT; and 0.0.0.0/8.
    return ((b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64) || b[0] == 0;
  }

  private static Refused refused(String why) {
    return new Refused(Kind.ADDRESS, "address refused: " + why);
  }
}
