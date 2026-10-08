package com.storeql.gateway.flow;

import com.storeql.gateway.ApiVersions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The only part of an address a flow record keeps: the path with every segment that is not a plain
 * word replaced by {@code {id}}, and the route group it belongs to.
 *
 * <p>A word is a lowercase letter then up to 39 more lowercase letters or hyphens. An id, a number,
 * an email, a phone number, a token, a date, a name with a capital or an apostrophe, a file name, a
 * matrix parameter and anything with a character outside that set all fail it, so they become
 * {@code {id}}: personal data in an address does not survive into a record, and neither does a
 * query string, which is never passed in. The one exception is the version segment right after
 * {@code /api}, which has a digit by design.
 *
 * <p>The group is the service segment once {@code /api} and the version are peeled off, and only a
 * name the gateway knows (a routable service, or a route it serves itself): any other is {@link
 * #OTHER}, so a client inventing paths cannot grow the number of groups, which are field names in
 * Redis.
 *
 * @param pattern the templated path, always starting with {@code /}
 * @param group the route group
 */
public record RoutePattern(String pattern, String group) {

  /** The group of an {@code /api} path naming nothing the gateway knows. */
  public static final String OTHER = "other";

  /** The group of everything the gateway serves outside {@code /api}. */
  public static final String GATEWAY = "gateway";

  /**
   * The group of the gateway's own routes for the health screen. They are the screen's own reads;
   * so is one route of reporting-svc's ({@link #isScreenRead(String, String)}).
   */
  public static final String SYSTEM_HEALTH = "system-health";

  /**
   * Groups of routes the gateway serves itself under {@code /api}, beside the routable services.
   */
  public static final Set<String> OWN_GROUPS = Set.of(SYSTEM_HEALTH, "versions");

  /** Segments kept; a longer path is cut and says so, so a pattern stays short. */
  static final int MAX_SEGMENTS = 12;

  private static final int MAX_WORD = 40;
  private static final String API = "api";
  private static final String REPORTING = "reporting-svc";

  /** What follows {@code /api[/vN]} in the waiting-work read the screen polls every few seconds. */
  private static final List<String> WAITING_WORK_ROUTE =
      List.of(REPORTING, "admin", "reports", "system-health", "waiting-work");

  /**
   * @param path the decoded request path, with or without its leading slash; no query string
   * @param groups the names a service segment may take as the group
   * @return the pattern and group
   */
  public static RoutePattern of(String path, Set<String> groups) {
    List<String> segments = segments(path);
    boolean underApi = !segments.isEmpty() && API.equals(segments.get(0));
    StringBuilder pattern = new StringBuilder();
    int shown = Math.min(segments.size(), MAX_SEGMENTS);
    for (int i = 0; i < shown; i++) {
      String segment = segments.get(i);
      boolean keep =
          isWord(segment) || (underApi && i == 1 && ApiVersions.isVersionSegment(segment));
      pattern.append('/').append(keep ? segment : "{id}");
    }
    if (segments.size() > shown) pattern.append("/{rest}");
    return new RoutePattern(
        pattern.length() == 0 ? "/" : pattern.toString(), group(segments, underApi, groups));
  }

  /**
   * @return whether this is one of the health screen's own reads ({@link #isScreenRead(String,
   *     String)})
   */
  public boolean isScreenRead() {
    return isScreenRead(pattern, group);
  }

  /**
   * Whether a request is the health screen's own polling rather than traffic: the gateway's own
   * system-health routes, and reporting-svc's waiting-work read, on any version and on the
   * unversioned alias. Nothing else is: not another report, not the same tail under another
   * service. It is judged on a record's pattern and group alone, so the filter that decides what is
   * counted and the store that decides what is listed ask the same question.
   *
   * @param pattern a route pattern, as {@link #pattern()} gives it; may be null
   * @param group its route group; may be null
   * @return true for the screen's own reads
   */
  public static boolean isScreenRead(String pattern, String group) {
    if (SYSTEM_HEALTH.equals(group)) return true;
    return REPORTING.equals(group) && isWaitingWork(pattern);
  }

  /** Exactly {@code /api[/vN]/reporting-svc/admin/reports/system-health/waiting-work}. */
  private static boolean isWaitingWork(String pattern) {
    List<String> segments = segments(pattern);
    if (segments.isEmpty() || !API.equals(segments.get(0))) return false;
    int service = segments.size() > 1 && ApiVersions.isVersionSegment(segments.get(1)) ? 2 : 1;
    return segments.subList(service, segments.size()).equals(WAITING_WORK_ROUTE);
  }

  private static String group(List<String> segments, boolean underApi, Set<String> groups) {
    if (!underApi) return GATEWAY;
    int at = segments.size() > 1 && ApiVersions.isVersionSegment(segments.get(1)) ? 2 : 1;
    if (segments.size() <= at) return OTHER;
    String candidate = segments.get(at);
    return groups.contains(candidate) ? candidate : OTHER;
  }

  private static List<String> segments(String path) {
    List<String> out = new ArrayList<>();
    if (path == null) return out;
    int start = 0;
    int n = path.length();
    for (int i = 0; i <= n; i++) {
      if (i == n || path.charAt(i) == '/') {
        if (i > start) out.add(path.substring(start, i));
        start = i + 1;
      }
    }
    return out;
  }

  private static boolean isWord(String s) {
    int n = s.length();
    if (n == 0 || n > MAX_WORD) return false;
    char first = s.charAt(0);
    if (first < 'a' || first > 'z') return false;
    for (int i = 1; i < n; i++) {
      char c = s.charAt(i);
      if ((c < 'a' || c > 'z') && c != '-') return false;
    }
    return true;
  }
}
