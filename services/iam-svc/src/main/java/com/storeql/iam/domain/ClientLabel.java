package com.storeql.iam.domain;

import java.net.InetAddress;
import java.util.Locale;

/**
 * What iam-svc keeps of the client a sign-in came from, and no more: a short device label read from
 * the User-Agent and the network truncated to a prefix (an IPv4 /24, an IPv6 /48). Never the whole
 * address, never the raw User-Agent. Pure.
 */
public final class ClientLabel {

  /** What a session shows when nothing is known of its client. */
  public static final String UNKNOWN = "Unknown device";

  private ClientLabel() {}

  /**
   * A short label such as {@code "Chrome on Windows"} or {@code "StoreQL app on Android"}.
   *
   * @param userAgent the User-Agent header, or null
   * @return the label, {@link #UNKNOWN} when there is nothing to read
   */
  public static String device(String userAgent) {
    if (userAgent == null || userAgent.isBlank()) return UNKNOWN;
    String ua = userAgent.toLowerCase(Locale.ROOT);
    String os =
        ua.contains("android")
            ? "Android"
            : ua.contains("iphone") || ua.contains("ipad") || ua.contains("ios")
                ? "iOS"
                : ua.contains("windows")
                    ? "Windows"
                    : ua.contains("mac os") || ua.contains("macintosh") || ua.contains("darwin")
                        ? "macOS"
                        : ua.contains("linux") ? "Linux" : null;
    String client =
        ua.contains("dart/") || ua.contains("storeql")
            ? "StoreQL app"
            : ua.contains("edg/")
                ? "Edge"
                : ua.contains("opr/") || ua.contains("opera")
                    ? "Opera"
                    : ua.contains("firefox/")
                        ? "Firefox"
                        : ua.contains("chrome/") || ua.contains("crios/")
                            ? "Chrome"
                            : ua.contains("safari/") ? "Safari" : null;
    if (client == null && os == null) return UNKNOWN;
    if (client == null) return os;
    return os == null ? client : client + " on " + os;
  }

  /**
   * The network as a truncated prefix: {@code 203.0.113.0/24} or {@code 2001:db8:1::/48}.
   *
   * @param address a literal IP address (the first of a forwarded list is read), or null
   * @return the prefix, or null when there is no readable address
   */
  public static String network(String address) {
    if (address == null || address.isBlank()) return null;
    String first = address.split(",")[0].trim();
    if (!first.matches("[0-9a-fA-F:.]+")) return null;
    try {
      byte[] b = InetAddress.getByName(first).getAddress();
      if (b.length == 4) {
        return (b[0] & 0xff) + "." + (b[1] & 0xff) + "." + (b[2] & 0xff) + ".0/24";
      }
      return String.format(
          "%x:%x:%x::/48",
          ((b[0] & 0xff) << 8) | (b[1] & 0xff),
          ((b[2] & 0xff) << 8) | (b[3] & 0xff),
          ((b[4] & 0xff) << 8) | (b[5] & 0xff));
    } catch (java.net.UnknownHostException e) {
      return null;
    }
  }
}
