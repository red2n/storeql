package com.storeql.gateway;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The API versioning policy (22.8), as one piece of arithmetic the door applies to every path.
 *
 * <p>A version is a path segment: {@code /api/v1/{service}/…}. Business services are
 * version-agnostic — the gateway peels the segment off ({@link ProxyResource.Route}) and would
 * branch on it if a {@code v2} ever meant a different upstream. Breaking changes ship only as a new
 * version; additive ones arrive within a version. The unversioned alias {@code /api/{service}/…}
 * predates versions and is deprecated: every answer on it carries {@code Deprecation} (RFC 9745,
 * the day it was deprecated as {@code @<seconds>}), {@code Sunset} (RFC 8594, the day it stops) and
 * a {@code Link} to its successor; from the sunset day it answers {@code 410}. A version nobody
 * published answers {@code 404} with a {@code Link} to the latest. The whole of it is described at
 * {@code GET /api/versions}, which needs no credential.
 */
public final class ApiVersions {

  private ApiVersions() {}

  private static final Pattern VERSION = Pattern.compile("v\\d+");
  private static final DateTimeFormatter IMF_FIXDATE =
      DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
          .withZone(ZoneOffset.UTC);

  /** How the alias is named in the description: it is not a version, it is the lack of one. */
  public static final String UNVERSIONED = "unversioned";

  /** The policy in a paragraph, as the description carries it. */
  public static final String POLICY_TEXT =
      "Breaking changes ship only as a new version under /api/v{n}/. A version stays supported for"
          + " at least twelve months after its successor is published; its retirement is announced"
          + " with Deprecation and Sunset headers on every answer before the day, and from that day"
          + " it answers 410. Additive changes — a new field, a new route, a new event type — arrive"
          + " within a version and never bump it. The unversioned /api/{service}/ form is the"
          + " deprecated alias of the current version.";

  /**
   * What the deployment publishes.
   *
   * @param current the version new integrations are pointed at
   * @param versions every version still answered, {@code current} among them
   * @param aliasDeprecatedSince the day the unversioned alias was deprecated
   * @param aliasSunset the day it stops answering, after that one
   */
  public record Policy(
      String current,
      List<String> versions,
      LocalDate aliasDeprecatedSince,
      LocalDate aliasSunset) {

    public Policy {
      for (String v : versions) {
        if (!VERSION.matcher(v.trim()).matches()) {
          throw new IllegalArgumentException("an API version is v<n>, not '" + v + "'");
        }
      }
      versions =
          List.copyOf(
              versions.stream()
                  .map(String::trim)
                  .sorted(Comparator.comparingInt(v -> Integer.parseInt(v.substring(1))))
                  .toList());
      if (!versions.contains(current)) {
        throw new IllegalArgumentException(
            "the current API version " + current + " is not among those published " + versions);
      }
      if (!aliasSunset.isAfter(aliasDeprecatedSince)) {
        throw new IllegalArgumentException(
            "the alias's sunset "
                + aliasSunset
                + " must follow its deprecation "
                + aliasDeprecatedSince);
      }
    }

    /** From configuration: the versions as a comma-separated list, the days as ISO dates. */
    public static Policy of(
        String current, String versionsCsv, String deprecatedSince, String sunset) {
      List<String> versions = new ArrayList<>();
      for (String v : versionsCsv.split(",")) {
        String trimmed = v.trim();
        if (!trimmed.isEmpty()) versions.add(trimmed);
      }
      return new Policy(
          current.trim(),
          versions,
          LocalDate.parse(deprecatedSince.trim()),
          LocalDate.parse(sunset.trim()));
    }
  }

  /** What the door does with a path. */
  public sealed interface Verdict permits Ok, Unknown, Retired {}

  /** Passes; {@code alias} when it came in on the deprecated unversioned form. */
  public record Ok(boolean alias) implements Verdict {}

  /** A version nobody published. */
  public record Unknown(String version) implements Verdict {}

  /** The unversioned alias, on or after its sunset. */
  public record Retired() implements Verdict {}

  public static Verdict check(Policy policy, String rawPath, Instant now) {
    String path = normalize(rawPath);
    if (!path.startsWith("api/") || "api/versions".equals(path)) {
      return new Ok(false);
    }
    String rest = path.substring("api/".length());
    int slash = rest.indexOf('/');
    String head = slash < 0 ? rest : rest.substring(0, slash);
    if (VERSION.matcher(head).matches()) {
      return policy.versions().contains(head) ? new Ok(false) : new Unknown(head);
    }
    return aliasRetired(policy, now) ? new Retired() : new Ok(true);
  }

  static boolean aliasRetired(Policy policy, Instant now) {
    return !now.isBefore(startOfDay(policy.aliasSunset()));
  }

  /** RFC 9745: {@code @<unix seconds>} of the day the alias was deprecated. */
  public static String deprecationHeader(Policy policy) {
    return "@" + startOfDay(policy.aliasDeprecatedSince()).getEpochSecond();
  }

  /** RFC 8594: the sunset as an HTTP date (IMF-fixdate). */
  public static String sunsetHeader(Policy policy) {
    return IMF_FIXDATE.format(startOfDay(policy.aliasSunset()));
  }

  public static String successorLink(Policy policy) {
    return "</api/" + policy.current() + ">; rel=\"successor-version\"";
  }

  public static String latestLink(Policy policy) {
    return "</api/" + policy.current() + ">; rel=\"latest-version\"";
  }

  /**
   * One version as described.
   *
   * @param status {@code current}, {@code supported}, {@code deprecated} or {@code retired}
   * @param base where its routes live, with {@code {service}} to fill in
   * @param deprecatedSince the day it was deprecated (ISO date), or null
   * @param sunset the day it stops (ISO date), or null
   * @param successor the base of what to move to, or null
   */
  public record Version(
      String version,
      String status,
      String base,
      String deprecatedSince,
      String sunset,
      String successor) {}

  /** The document at {@code GET /api/versions}. */
  public record Description(String current, List<Version> versions, String policy, String openapi) {
    public Description {
      versions = List.copyOf(versions);
    }
  }

  public static Description describe(Policy policy, Instant now) {
    List<Version> out = new ArrayList<>();
    for (String v : policy.versions()) {
      out.add(
          new Version(
              v,
              v.equals(policy.current()) ? "current" : "supported",
              "/api/" + v + "/{service}",
              null,
              null,
              null));
    }
    out.add(
        new Version(
            UNVERSIONED,
            aliasRetired(policy, now) ? "retired" : "deprecated",
            "/api/{service}",
            policy.aliasDeprecatedSince().toString(),
            policy.aliasSunset().toString(),
            "/api/" + policy.current()));
    return new Description(
        policy.current(),
        List.copyOf(out),
        POLICY_TEXT,
        "/api/" + policy.current() + "/{service}/openapi");
  }

  private static Instant startOfDay(LocalDate day) {
    return day.atStartOfDay(ZoneOffset.UTC).toInstant();
  }

  private static String normalize(String path) {
    String p = path == null ? "" : path;
    while (p.startsWith("/")) p = p.substring(1);
    while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
    return p;
  }
}
