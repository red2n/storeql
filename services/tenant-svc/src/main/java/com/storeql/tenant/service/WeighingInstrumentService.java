package com.storeql.tenant.service;

import com.storeql.ids.Ids;
import com.storeql.tenant.domain.Domain.InstrumentVerification;
import com.storeql.tenant.domain.Domain.WeighingInstrument;
import com.storeql.tenant.domain.Domain.WeighingInstrumentWithStanding;
import com.storeql.tenant.dto.Dtos.CreateWeighingInstrumentRequest;
import com.storeql.tenant.dto.Dtos.RecordVerificationRequest;
import com.storeql.tenant.dto.Dtos.UpdateWeighingInstrumentRequest;
import com.storeql.tenant.repo.TenantRepository;
import com.storeql.tenant.repo.WeighingInstrumentRepository;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import java.io.StringReader;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The weighing-instrument register (Weights and Measures Act 1985 s.11): what a store weighs for
 * trade on, and whether each instrument may be used for trade today.
 *
 * <p>Standing is derived on every read from the append-only history, never stored, so it cannot
 * drift from the evidence: an instrument is certified when it is in service, its latest history
 * entry is a pass, and that pass is not yet due again. A repair is never a pass, so an instrument
 * repaired since its last verification is out of trade until it is verified again — which is what
 * the Act says about a stamp obliterated by repair.
 */
@ApplicationScoped
public class WeighingInstrumentService {

  @Inject WeighingInstrumentRepository repo;
  @Inject TenantRepository tenants;

  /**
   * Adds an instrument to a store's register. It starts NEVER_VERIFIED: nothing is certified by
   * being written down.
   *
   * @param tenantId owning tenant
   * @param storeId the store it stands in
   * @param req its description
   * @return the instrument and its standing
   * @throws ApiException {@code STORE_NOT_FOUND} (404); {@code INSTRUMENT_KIND_UNKNOWN}, {@code
   *     INSTRUMENT_SCHEME_INVALID}, {@code ZONE_NOT_FOUND} (400); {@code INSTRUMENT_DUPLICATE}
   *     (409)
   */
  public WeighingInstrumentWithStanding create(
      UUID tenantId, UUID storeId, CreateWeighingInstrumentRequest req) {
    requireStore(tenantId, storeId);
    String kind = kindOf(req.kind());
    UUID zoneId = zoneOf(tenantId, storeId, req.zoneId());
    String scheme = schemeOf(kind, req.labelScheme());
    Instant now = Instant.now();
    var created =
        repo.create(
            new WeighingInstrument(
                Ids.newId(),
                tenantId,
                storeId,
                req.identifier().trim(),
                req.serialNumber().trim(),
                blank(req.make()),
                blank(req.model()),
                kind,
                req.maxCapacity(),
                req.capacityUom() == null
                    ? null
                    : req.capacityUom().trim().toUpperCase(Locale.ROOT),
                req.scaleInterval(),
                blank(req.approvalRef()),
                zoneId,
                scheme,
                WeighingInstrument.STATUS_IN_SERVICE,
                now,
                now));
    return withStanding(created, null);
  }

  /**
   * Rewrites an instrument's description. Its history and status are untouched.
   *
   * @param tenantId owning tenant
   * @param storeId the store, which the instrument must belong to
   * @param id the instrument
   * @param req the new description
   * @return the instrument and its standing
   */
  public WeighingInstrumentWithStanding update(
      UUID tenantId, UUID storeId, UUID id, UpdateWeighingInstrumentRequest req) {
    WeighingInstrument existing = requireInstrument(tenantId, storeId, id);
    String kind = kindOf(req.kind());
    UUID zoneId = zoneOf(tenantId, storeId, req.zoneId());
    String scheme = schemeOf(kind, req.labelScheme());
    var updated =
        repo.update(
            new WeighingInstrument(
                existing.id(),
                tenantId,
                storeId,
                req.identifier().trim(),
                req.serialNumber().trim(),
                blank(req.make()),
                blank(req.model()),
                kind,
                req.maxCapacity(),
                req.capacityUom() == null
                    ? null
                    : req.capacityUom().trim().toUpperCase(Locale.ROOT),
                req.scaleInterval(),
                blank(req.approvalRef()),
                zoneId,
                scheme,
                existing.status(),
                existing.createdAt(),
                Instant.now()));
    return withStanding(updated, latestOf(tenantId, id));
  }

  /**
   * Takes an instrument out of service, puts it back, or retires it.
   *
   * @param tenantId owning tenant
   * @param storeId the store
   * @param id the instrument
   * @param statusRaw IN_SERVICE, OUT_OF_SERVICE or RETIRED
   * @return the instrument and its standing
   * @throws ApiException {@code INSTRUMENT_STATUS_UNKNOWN} (400); {@code INSTRUMENT_RETIRED} (409)
   *     when a retired instrument is brought back — retirement is final, a new plate is a new
   *     instrument
   */
  public WeighingInstrumentWithStanding setStatus(
      UUID tenantId, UUID storeId, UUID id, String statusRaw) {
    WeighingInstrument existing = requireInstrument(tenantId, storeId, id);
    String status = statusRaw == null ? "" : statusRaw.trim().toUpperCase(Locale.ROOT);
    if (!WeighingInstrument.STATUSES.contains(status)) {
      throw ApiException.badRequest(
          "INSTRUMENT_STATUS_UNKNOWN", "status is IN_SERVICE, OUT_OF_SERVICE or RETIRED");
    }
    if (WeighingInstrument.STATUS_RETIRED.equals(existing.status())
        && !WeighingInstrument.STATUS_RETIRED.equals(status)) {
      throw ApiException.conflict(
          "INSTRUMENT_RETIRED", "a retired instrument is not brought back; register a new one");
    }
    return withStanding(repo.updateStatus(tenantId, id, status), latestOf(tenantId, id));
  }

  /**
   * Appends one history entry: a verification, an inspection, or a repair.
   *
   * @param tenantId owning tenant
   * @param storeId the store
   * @param id the instrument
   * @param req the entry
   * @param recordedBy the staff member recording it, from the token
   * @return the entry as stored
   * @throws ApiException {@code VERIFICATION_KIND_UNKNOWN}, {@code VERIFICATION_DATE_INVALID},
   *     {@code VERIFICATION_REPAIR_NOT_PASS}, {@code VERIFICATION_DUE_BEFORE_DONE} (400); {@code
   *     INSTRUMENT_RETIRED} (409) — nothing is verified after retirement
   */
  public InstrumentVerification recordVerification(
      UUID tenantId, UUID storeId, UUID id, RecordVerificationRequest req, UUID recordedBy) {
    WeighingInstrument existing = requireInstrument(tenantId, storeId, id);
    if (WeighingInstrument.STATUS_RETIRED.equals(existing.status())) {
      throw ApiException.conflict("INSTRUMENT_RETIRED", "a retired instrument has no history left");
    }
    String kind = req.kind().trim().toUpperCase(Locale.ROOT);
    if (!InstrumentVerification.KINDS.contains(kind)) {
      throw ApiException.badRequest(
          "VERIFICATION_KIND_UNKNOWN", "kind is INITIAL, RE_VERIFICATION, INSPECTION or REPAIR");
    }
    LocalDate performedOn = dateOf(req.performedOn(), "performedOn");
    LocalDate nextDue =
        req.nextDue() == null || req.nextDue().isBlank() ? null : dateOf(req.nextDue(), "nextDue");
    if (performedOn.isAfter(LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1))) {
      throw ApiException.badRequest(
          "VERIFICATION_DATE_INVALID", "performedOn cannot be in the future");
    }
    boolean passed = Boolean.TRUE.equals(req.passed());
    if (InstrumentVerification.KIND_REPAIR.equals(kind) && passed) {
      throw ApiException.badRequest(
          "VERIFICATION_REPAIR_NOT_PASS",
          "a repair is never a pass: the instrument is verified again afterwards");
    }
    if (nextDue != null && nextDue.isBefore(performedOn)) {
      throw ApiException.badRequest(
          "VERIFICATION_DUE_BEFORE_DONE", "nextDue cannot be before performedOn");
    }
    return repo.recordVerification(
        new InstrumentVerification(
            Ids.newId(),
            tenantId,
            id,
            kind,
            performedOn,
            req.performedBy().trim(),
            blank(req.certificateRef()),
            passed,
            nextDue,
            blank(req.notes()),
            recordedBy,
            Instant.now()));
  }

  /**
   * Every instrument at a store with its standing, in one round trip.
   *
   * @param tenantId owning tenant
   * @param storeId the store
   * @param certifiedOnly when true, only instruments that may be used for trade today
   * @return the instruments
   */
  public List<WeighingInstrumentWithStanding> list(
      UUID tenantId, UUID storeId, boolean certifiedOnly) {
    requireStore(tenantId, storeId);
    Map<UUID, InstrumentVerification> latest = new HashMap<>();
    for (InstrumentVerification v : repo.latestByStore(tenantId, storeId)) {
      latest.put(v.instrumentId(), v);
    }
    return repo.listByStore(tenantId, storeId).stream()
        .map(i -> withStanding(i, latest.get(i.id())))
        .filter(w -> !certifiedOnly || w.certified())
        .toList();
  }

  /**
   * One instrument with its standing.
   *
   * @param tenantId owning tenant
   * @param storeId the store
   * @param id the instrument
   * @return the instrument
   */
  public WeighingInstrumentWithStanding get(UUID tenantId, UUID storeId, UUID id) {
    return withStanding(requireInstrument(tenantId, storeId, id), latestOf(tenantId, id));
  }

  /**
   * An instrument's history, newest first.
   *
   * @param tenantId owning tenant
   * @param storeId the store
   * @param id the instrument
   * @return the entries
   */
  public List<InstrumentVerification> history(UUID tenantId, UUID storeId, UUID id) {
    requireInstrument(tenantId, storeId, id);
    return repo.history(tenantId, id);
  }

  /** The rule, in one place: in service, latest entry a pass, and not yet due again. */
  static WeighingInstrumentWithStanding withStanding(
      WeighingInstrument i, InstrumentVerification latest) {
    String standing;
    if (WeighingInstrument.STATUS_RETIRED.equals(i.status())) {
      standing = "RETIRED";
    } else if (WeighingInstrument.STATUS_OUT_OF_SERVICE.equals(i.status())) {
      standing = "OUT_OF_SERVICE";
    } else if (latest == null) {
      standing = "NEVER_VERIFIED";
    } else if (InstrumentVerification.KIND_REPAIR.equals(latest.kind())) {
      standing = "REPAIRED_SINCE";
    } else if (!latest.passed()) {
      standing = "FAILED";
    } else if (latest.nextDue() != null
        && latest.nextDue().isBefore(LocalDate.now(java.time.ZoneOffset.UTC))) {
      standing = "OVERDUE";
    } else {
      standing = "CERTIFIED";
    }
    return new WeighingInstrumentWithStanding(i, latest, "CERTIFIED".equals(standing), standing);
  }

  private InstrumentVerification latestOf(UUID tenantId, UUID id) {
    List<InstrumentVerification> h = repo.history(tenantId, id);
    return h.isEmpty() ? null : h.get(0);
  }

  private void requireStore(UUID tenantId, UUID storeId) {
    tenants
        .findStore(tenantId, storeId)
        .orElseThrow(
            () -> ApiException.notFound("STORE_NOT_FOUND", "No such store in this tenant"));
  }

  private WeighingInstrument requireInstrument(UUID tenantId, UUID storeId, UUID id) {
    WeighingInstrument i =
        repo.find(tenantId, id)
            .orElseThrow(
                () -> ApiException.notFound("INSTRUMENT_NOT_FOUND", "No such weighing instrument"));
    if (!i.storeId().equals(storeId)) {
      throw ApiException.notFound("INSTRUMENT_NOT_FOUND", "No such weighing instrument");
    }
    return i;
  }

  private UUID zoneOf(UUID tenantId, UUID storeId, String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    UUID zoneId;
    try {
      zoneId = Ids.parse(raw.trim());
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, "ZONE_NOT_FOUND", "zoneId is not a UUID", List.of(), e);
    }
    var zone =
        tenants
            .findZone(tenantId, zoneId)
            .orElseThrow(() -> ApiException.badRequest("ZONE_NOT_FOUND", "No such zone"));
    if (!zone.storeId().equals(storeId)) {
      throw ApiException.badRequest("ZONE_NOT_FOUND", "the zone is in a different store");
    }
    return zoneId;
  }

  private static String kindOf(String raw) {
    if (raw == null || raw.isBlank()) {
      return "COUNTER";
    }
    String kind = raw.trim().toUpperCase(Locale.ROOT);
    if (!WeighingInstrument.KINDS.contains(kind)) {
      throw ApiException.badRequest(
          "INSTRUMENT_KIND_UNKNOWN", "kind is COUNTER, LABELLING, PLATFORM or HANGING");
    }
    return kind;
  }

  /**
   * A labelling scheme is JSON the till will read on every scan, so it is checked here once, not
   * trusted there every time: the prefixes are two digits, the item code 4 or 5 digits, the value a
   * PRICE or a WEIGHT with 0..3 decimals. Only a LABELLING scale may carry one.
   */
  static String schemeOf(String kind, String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    if (!"LABELLING".equals(kind)) {
      throw ApiException.badRequest(
          "INSTRUMENT_SCHEME_INVALID", "only a LABELLING scale carries a label scheme");
    }
    JsonObject o;
    try (var reader = Json.createReader(new StringReader(raw))) {
      o = reader.readObject();
    } catch (RuntimeException e) {
      throw new ApiException(
          400, "INSTRUMENT_SCHEME_INVALID", "labelScheme is not a JSON object", List.of(), e);
    }
    var prefixes = o.getJsonArray("prefixes");
    if (prefixes == null || prefixes.isEmpty()) {
      throw ApiException.badRequest("INSTRUMENT_SCHEME_INVALID", "labelScheme.prefixes is empty");
    }
    for (var p : prefixes) {
      if (!(p instanceof JsonString js) || !js.getString().matches("^[0-9]{2}$")) {
        throw ApiException.badRequest(
            "INSTRUMENT_SCHEME_INVALID", "each prefix is two digits, e.g. \"20\"");
      }
    }
    int itemDigits = wholeOr(o, "itemDigits", 0);
    if (itemDigits != 4 && itemDigits != 5) {
      throw ApiException.badRequest("INSTRUMENT_SCHEME_INVALID", "itemDigits is 4 or 5");
    }
    String valueKind = o.getString("valueKind", "");
    if (!"PRICE".equals(valueKind) && !"WEIGHT".equals(valueKind)) {
      throw ApiException.badRequest("INSTRUMENT_SCHEME_INVALID", "valueKind is PRICE or WEIGHT");
    }
    int decimals = wholeOr(o, "valueDecimals", -1);
    if (decimals < 0 || decimals > 3) {
      throw ApiException.badRequest("INSTRUMENT_SCHEME_INVALID", "valueDecimals is 0..3");
    }
    return o.toString();
  }

  /**
   * A scheme's number as it was written, or {@code otherwise} when it is absent or not a whole
   * number as written. The scheme is kept as sent and a till reads it back, so it is judged as
   * sent: JSON-P's {@code getInt} cuts a number down ({@code 4294967300} and {@code 4.9} are both
   * 4), and {@code 5.0} would be kept and read as a fraction. Judged by its scale before any
   * arithmetic, so {@code 1E+80000000} is never expanded.
   */
  private static int wholeOr(JsonObject o, String name, int otherwise) {
    if (!(o.get(name) instanceof JsonNumber n) || !n.isIntegral()) {
      return otherwise;
    }
    try {
      return n.bigDecimalValue().intValueExact();
    } catch (ArithmeticException e) {
      return otherwise;
    }
  }

  private static LocalDate dateOf(String raw, String field) {
    if (raw == null || raw.isBlank()) {
      throw ApiException.badRequest("VERIFICATION_DATE_INVALID", field + " must be an ISO date");
    }
    try {
      return LocalDate.parse(raw.trim());
    } catch (DateTimeParseException e) {
      throw new ApiException(
          400, "VERIFICATION_DATE_INVALID", field + " must be an ISO date", List.of(), e);
    }
  }

  private static String blank(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }
}
