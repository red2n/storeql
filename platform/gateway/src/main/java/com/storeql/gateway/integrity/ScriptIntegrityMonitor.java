package com.storeql.gateway.integrity;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.metrics.annotation.Gauge;

/**
 * Change-and-tamper detection on the scripts of the pages that lead to payment (PCI DSS v4.0.1
 * 11.6.1, and SAQ-A's condition that the merchant's site cannot be altered by scripts).
 *
 * <p>The web image lists every script it serves with a reason and a SHA-256 in {@code
 * /script-inventory.json}, generated from the bundle at image build. On a schedule this monitor
 * reads the served entry page, finds every script it loads, and checks each against the inventory:
 * an unlisted script, one whose bytes differ, or one that has gone is drift. Drift is logged at
 * ERROR, held for {@code GET /admin/security/script-integrity}, and exposed as the gauge {@code
 * storeql_script_integrity_drift} for Prometheus to alert on. A shell it cannot read is reported as
 * unavailable rather than as clean.
 */
@ApplicationScoped
public class ScriptIntegrityMonitor {

  private static final System.Logger LOG = System.getLogger(ScriptIntegrityMonitor.class.getName());
  private static final Pattern SCRIPT_SRC =
      Pattern.compile("<script[^>]*\\ssrc=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

  /** One difference between what the shell serves and what its inventory says. */
  public record Drift(String kind, String path, String detail) {}

  /** The outcome of one check. */
  public record Report(
      Instant checkedAt, String webUrl, boolean available, int scripts, List<Drift> drift) {
    /** The drift is kept as an immutable copy, so the report cannot be changed after the check. */
    public Report {
      drift = List.copyOf(drift);
    }

    public boolean clean() {
      return available && drift.isEmpty();
    }
  }

  // Optional, not defaultValue = "": MicroProfile Config reads an empty string as "no value", so
  // an empty default fails the deployment wherever the key is unset instead of leaving this off.
  @Inject
  @ConfigProperty(name = "storeql.gateway.script-integrity.web-url")
  Optional<String> configuredWebUrl;

  String webUrl;

  @Inject
  @ConfigProperty(name = "storeql.gateway.script-integrity.interval-minutes", defaultValue = "1440")
  long intervalMinutes;

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private volatile Report last;
  private ScheduledExecutorService scheduler;

  @PostConstruct
  void start() {
    webUrl = configuredWebUrl.orElse("");
    if (webUrl.isBlank()) {
      LOG.log(System.Logger.Level.INFO, "script integrity monitor off: no web url configured");
      return;
    }
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "script-integrity");
              t.setDaemon(true);
              return t;
            });
    scheduler.scheduleAtFixedRate(
        this::checkQuietly, 1, Math.max(1, intervalMinutes), TimeUnit.MINUTES);
  }

  @PreDestroy
  void stop() {
    if (scheduler != null) scheduler.shutdownNow();
    // Not close(): it waits for requests in flight; shutdownNow() cancels them.
    http.shutdownNow();
  }

  /** Whether a web url is configured, so a check can run. */
  public boolean enabled() {
    return webUrl != null && !webUrl.isBlank();
  }

  /** The last report, or null before the first check. */
  public Report last() {
    return last;
  }

  /** Drift found by the last check: 0 clean, -1 when the shell could not be read. */
  @Gauge(name = "storeql.script.integrity.drift", unit = "none", absolute = true)
  public long driftGauge() {
    Report r = last;
    if (r == null) return 0;
    return r.available ? r.drift.size() : -1;
  }

  private void checkQuietly() {
    try {
      check();
    } catch (RuntimeException e) {
      LOG.log(System.Logger.Level.WARNING, "script integrity check failed: " + e.getMessage());
    }
  }

  /** Runs a check now against the configured shell and keeps the report. */
  public Report check() {
    Report report = check(webUrl);
    last = report;
    if (!report.available) {
      LOG.log(
          System.Logger.Level.WARNING,
          "script integrity: the web shell at {0} could not be read",
          report.webUrl);
    } else if (!report.drift.isEmpty()) {
      LOG.log(
          System.Logger.Level.ERROR,
          "SCRIPT INTEGRITY DRIFT on {0}: {1}",
          report.webUrl,
          report.drift);
    }
    return report;
  }

  /** Checks one shell: the entry page's scripts against the inventory it serves. */
  Report check(String base) {
    String root = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    Instant now = Instant.now();
    Optional<String> inventoryRead = fetchText(root + "/script-inventory.json");
    Optional<String> pageRead = fetchText(root + "/index.html");
    if (inventoryRead.isEmpty() || pageRead.isEmpty()) {
      return new Report(now, root, false, 0, List.of());
    }
    String inventoryText = inventoryRead.get();
    String page = pageRead.get();
    Map<String, String> expected = new LinkedHashMap<>();
    try (var reader = Json.createReader(new StringReader(inventoryText))) {
      JsonArray scripts = reader.readObject().getJsonArray("scripts");
      for (JsonObject s : scripts.getValuesAs(JsonObject.class)) {
        expected.put(s.getString("path"), s.getString("sha256"));
      }
    } catch (RuntimeException e) {
      return new Report(
          now,
          root,
          true,
          0,
          List.of(new Drift("INVENTORY_UNREADABLE", "script-inventory.json", e.getMessage())));
    }
    List<Drift> drift = new ArrayList<>();
    Matcher m = SCRIPT_SRC.matcher(page);
    while (m.find()) {
      String src = m.group(1).replaceFirst("^\\./", "").replaceFirst("^/", "");
      if (src.contains("://") || !expected.containsKey(src)) {
        drift.add(
            new Drift(
                "UNLISTED", src, "the entry page loads a script the inventory does not name"));
      }
    }
    for (Map.Entry<String, String> e : expected.entrySet()) {
      Optional<byte[]> body = fetchBytes(root + "/" + e.getKey());
      if (body.isEmpty()) {
        drift.add(new Drift("MISSING", e.getKey(), "listed in the inventory, not served"));
        continue;
      }
      String actual = sha256(body.get());
      if (!actual.equalsIgnoreCase(e.getValue())) {
        drift.add(
            new Drift("CHANGED", e.getKey(), "served " + actual + ", inventory " + e.getValue()));
      }
    }
    return new Report(now, root, true, expected.size(), List.copyOf(drift));
  }

  private Optional<String> fetchText(String url) {
    return fetchBytes(url).map(b -> new String(b, StandardCharsets.UTF_8));
  }

  /** The body of a 200, or empty when the shell answers otherwise or cannot be reached. */
  private Optional<byte[]> fetchBytes(String url) {
    try {
      HttpResponse<byte[]> res =
          http.send(
              HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build(),
              HttpResponse.BodyHandlers.ofByteArray());
      return res.statusCode() == 200 ? Optional.of(res.body()) : Optional.empty();
    } catch (IOException | IllegalArgumentException e) {
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }

  static String sha256(byte[] body) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(body))
          .toLowerCase(Locale.ROOT);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
