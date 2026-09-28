package com.storeql.tenant.mapper;

import com.storeql.tenant.domain.Domain.DeliveryArea;
import com.storeql.tenant.domain.Domain.DutyState;
import com.storeql.tenant.domain.Domain.IncidentSheet;
import com.storeql.tenant.domain.Domain.NoticeDuties;
import com.storeql.tenant.domain.Domain.NoticeIssue;
import com.storeql.tenant.domain.Domain.NoticeReport;
import com.storeql.tenant.domain.Domain.SecurityNotice;
import com.storeql.tenant.domain.Domain.StaffAssignment;
import com.storeql.tenant.domain.Domain.StageStatus;
import com.storeql.tenant.domain.Domain.Store;
import com.storeql.tenant.domain.Domain.Tenant;
import com.storeql.tenant.domain.Domain.TenantInventoryConfig;
import com.storeql.tenant.domain.Domain.Zone;
import com.storeql.tenant.dto.Dtos.DeliveryAreaResponse;
import com.storeql.tenant.dto.Dtos.IncidentEventResponse;
import com.storeql.tenant.dto.Dtos.IncidentResponse;
import com.storeql.tenant.dto.Dtos.IncidentStageResponse;
import com.storeql.tenant.dto.Dtos.IncidentSummaryResponse;
import com.storeql.tenant.dto.Dtos.NoticeDutyResponse;
import com.storeql.tenant.dto.Dtos.NoticesIssuedResponse;
import com.storeql.tenant.dto.Dtos.SecurityNoticeResponse;
import com.storeql.tenant.dto.Dtos.StaffResponse;
import com.storeql.tenant.dto.Dtos.StoreResponse;
import com.storeql.tenant.dto.Dtos.TenantInventoryConfigResponse;
import com.storeql.tenant.dto.Dtos.TenantResponse;
import com.storeql.tenant.dto.Dtos.ZoneResponse;
import com.storeql.tenant.service.IncidentRules;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Entity → DTO conversion (never expose entities over HTTP). */
public final class Mappers {

  private Mappers() {}

  /**
   * Converts a tenant to its wire form.
   *
   * @param t the tenant to convert
   * @return its API representation
   */
  public static TenantResponse toTenant(Tenant t) {
    return new TenantResponse(
        t.id().toString(),
        t.name(),
        t.legalName(),
        t.status(),
        t.country(),
        t.currency(),
        ts(t.createdAt()),
        ts(t.updatedAt()),
        t.vatNumber(),
        t.einvoiceScheme(),
        t.einvoiceId(),
        t.deactivatedReason(),
        t.mode() == null ? Tenant.MODE_LIVE : t.mode(),
        t.sandboxOf() == null ? null : t.sandboxOf().toString());
  }

  /**
   * Converts a store to its wire form.
   *
   * @param s the store to convert
   * @return its API representation, with the stored tender CSV split into a list
   */
  public static StoreResponse toStore(Store s) {
    return new StoreResponse(
        s.id().toString(),
        s.name(),
        s.code(),
        s.type(),
        s.status(),
        s.isDefault(),
        s.line1(),
        s.line2(),
        s.city(),
        s.state(),
        s.country(),
        s.pincode(),
        s.geoLat(),
        s.geoLng(),
        s.timezone(),
        s.businessHours(),
        s.showPrices(),
        paymentMethodsList(s.enabledPaymentMethods()),
        s.tillPhone(),
        ts(s.createdAt()),
        ts(s.updatedAt()));
  }

  /** CSV column → JSON list; a null/blank column (pre-migration row) falls back to the default. */
  /**
   * Splits the stored comma-separated tender list into its parts.
   *
   * <p>Kept as CSV in the column but exposed as a list, so the wire contract does not leak the
   * storage shape.
   *
   * @param csv the stored value, which may be {@code null} or blank
   * @return the tender codes, empty when nothing is stored
   */
  public static java.util.List<String> paymentMethodsList(String csv) {
    String effective = csv == null || csv.isBlank() ? Store.DEFAULT_PAYMENT_METHODS : csv;
    return java.util.Arrays.stream(effective.split(",")).map(String::trim).toList();
  }

  /**
   * Converts a zone to its wire form.
   *
   * @param z the zone to convert
   * @return its API representation
   */
  public static ZoneResponse toZone(Zone z) {
    return new ZoneResponse(
        z.id().toString(),
        z.storeId().toString(),
        z.name(),
        z.code(),
        z.type(),
        z.status(),
        ts(z.createdAt()),
        ts(z.updatedAt()));
  }

  /**
   * Converts a staff assignment to its wire form.
   *
   * @param s the staff assignment to convert
   * @return its API representation: the user, the store and the role granted there
   */
  public static StaffResponse toStaff(StaffAssignment s) {
    return new StaffResponse(
        s.id().toString(),
        s.userId().toString(),
        s.storeId().toString(),
        s.role(),
        s.baseTier(),
        ts(s.createdAt()));
  }

  /**
   * Converts a custom role to its wire form.
   *
   * @param r the role
   * @return its API representation
   */
  public static com.storeql.tenant.dto.Dtos.RoleResponse toRole(
      com.storeql.tenant.domain.Domain.TenantRole r) {
    return new com.storeql.tenant.dto.Dtos.RoleResponse(
        r.code(),
        r.name(),
        r.baseTier(),
        r.permissions().stream().sorted().toList(),
        r.description(),
        true,
        ts(r.createdAt()),
        ts(r.updatedAt()));
  }

  /**
   * A built-in tier in the same shape as a custom role, so one list shows both.
   *
   * @param tier OWNER, MANAGER, STOREKEEPER or CASHIER
   * @return its API representation, holding the tier's default permissions
   */
  public static com.storeql.tenant.dto.Dtos.RoleResponse toBuiltInRole(String tier) {
    return new com.storeql.tenant.dto.Dtos.RoleResponse(
        tier,
        tier.charAt(0) + tier.substring(1).toLowerCase(java.util.Locale.ROOT),
        tier,
        com.storeql.web.Permissions.defaultsFor(tier).stream().sorted().toList(),
        "Built in",
        false,
        null,
        null);
  }

  /**
   * Converts the tenant's inventory-control parameters to their wire form.
   *
   * @param c the configuration to convert
   * @return its API representation
   */
  public static TenantInventoryConfigResponse toDto(TenantInventoryConfig c) {
    return new TenantInventoryConfigResponse(
        c.id().toString(),
        c.tenantId().toString(),
        c.lotControlEnabled(),
        c.serialControlEnabled(),
        c.gradeControlEnabled(),
        c.expiryTrackingEnabled(),
        c.costingMethod(),
        c.defaultUom(),
        c.reorderAlertEnabled(),
        c.autoReserveOnOrder(),
        ts(c.createdAt()),
        ts(c.updatedAt()));
  }

  /**
   * Converts a delivery area to its wire form.
   *
   * @param a the delivery area to convert
   * @return its API representation: the pincode and its priority
   */
  public static DeliveryAreaResponse toDto(DeliveryArea a) {
    return new DeliveryAreaResponse(
        a.id().toString(), a.storeId().toString(), a.pincode(), a.priority(), ts(a.createdAt()));
  }

  private static String ts(Instant i) {
    return i == null ? null : i.toString();
  }

  /**
   * Converts one history entry to its wire form.
   *
   * @param v the entry
   * @return its API representation
   */
  public static com.storeql.tenant.dto.Dtos.InstrumentVerificationResponse toVerification(
      com.storeql.tenant.domain.Domain.InstrumentVerification v) {
    return new com.storeql.tenant.dto.Dtos.InstrumentVerificationResponse(
        v.id().toString(),
        v.kind(),
        v.performedOn().toString(),
        v.performedBy(),
        v.certificateRef(),
        v.passed(),
        v.nextDue() == null ? null : v.nextDue().toString(),
        v.notes(),
        ts(v.recordedAt()));
  }

  /**
   * Converts an instrument with its standing to its wire form.
   *
   * @param w the instrument and its derived standing
   * @return its API representation
   */
  public static com.storeql.tenant.dto.Dtos.WeighingInstrumentResponse toInstrument(
      com.storeql.tenant.domain.Domain.WeighingInstrumentWithStanding w) {
    var i = w.instrument();
    return new com.storeql.tenant.dto.Dtos.WeighingInstrumentResponse(
        i.id().toString(),
        i.storeId().toString(),
        i.identifier(),
        i.serialNumber(),
        i.make(),
        i.model(),
        i.kind(),
        i.maxCapacity(),
        i.capacityUom(),
        i.scaleInterval(),
        i.approvalRef(),
        i.zoneId() == null ? null : i.zoneId().toString(),
        i.labelScheme(),
        i.status(),
        w.certified(),
        w.standing(),
        w.latest() == null ? null : toVerification(w.latest()),
        ts(i.createdAt()),
        ts(i.updatedAt()));
  }

  /** The obligations that bind a country on a day, in wire form. */
  public static com.storeql.tenant.dto.Dtos.ObligationsResponse toObligations(
      com.storeql.tenant.domain.Domain.ObligationSheet sheet) {
    return new com.storeql.tenant.dto.Dtos.ObligationsResponse(
        sheet.country(),
        sheet.on().toString(),
        sheet.obligations().stream()
            .map(
                o ->
                    new com.storeql.tenant.dto.Dtos.ObligationResponse(
                        o.code(),
                        o.scope(),
                        o.effectiveFrom().toString(),
                        o.effectiveTo() == null ? null : o.effectiveTo().toString(),
                        o.citation(),
                        o.summary(),
                        o.statusOn(sheet.on())))
            .toList(),
        sheet.cashLimits().stream()
            .map(
                l ->
                    new com.storeql.tenant.dto.Dtos.CashLimitResponse(
                        l.scope(),
                        l.currency(),
                        l.fromAmount(),
                        l.effectiveFrom().toString(),
                        l.effectiveTo() == null ? null : l.effectiveTo().toString(),
                        l.citation(),
                        l.summary(),
                        l.status(sheet.on())))
            .toList(),
        sheet.depositSchemes().stream().map(d -> toDepositScheme(d, sheet.on())).toList());
  }

  /** A deposit scheme in wire form, its status on a day (09.16). */
  public static com.storeql.tenant.dto.Dtos.DepositSchemeResponse toDepositScheme(
      com.storeql.tenant.domain.Domain.DepositScheme d, java.time.LocalDate on) {
    return new com.storeql.tenant.dto.Dtos.DepositSchemeResponse(
        d.scope(),
        d.currency(),
        d.depositEach(),
        d.materials(),
        d.minVolumeMl(),
        d.maxVolumeMl(),
        d.vatTreatment(),
        d.effectiveFrom().toString(),
        d.effectiveTo() == null ? null : d.effectiveTo().toString(),
        d.citation(),
        d.summary(),
        d.status(on));
  }

  // ── Security incidents (21.15) ────────────────────────────────────────────

  public static IncidentResponse toIncident(IncidentSheet s) {
    var i = s.incident();
    return new IncidentResponse(
        i.id().toString(),
        i.kind(),
        i.title(),
        i.summary(),
        ts(i.awareAt()),
        ts(i.openedAt()),
        i.affectsAllTenants(),
        i.tenantIds().stream().map(UUID::toString).toList(),
        s.closed() ? "CLOSED" : "OPEN",
        s.stages().stream()
            .map(
                st ->
                    new IncidentStageResponse(
                        st.stage(),
                        st.summary(),
                        st.citation(),
                        ts(st.dueAt()),
                        ts(st.doneAt()),
                        st.state()))
            .toList(),
        s.events().stream()
            .map(
                e ->
                    new IncidentEventResponse(
                        e.id().toString(),
                        e.kind(),
                        ts(e.occurredAt()),
                        ts(e.recordedAt()),
                        e.reference(),
                        e.note()))
            .toList(),
        s.noticesIssued(),
        s.noticesAcknowledged());
  }

  public static IncidentSummaryResponse toIncidentSummary(IncidentSheet s) {
    StageStatus next = IncidentRules.next(s.stages());
    var i = s.incident();
    return new IncidentSummaryResponse(
        i.id().toString(),
        i.kind(),
        i.title(),
        ts(i.awareAt()),
        s.closed() ? "CLOSED" : "OPEN",
        next == null ? null : next.stage(),
        next == null ? null : ts(next.dueAt()),
        s.stages().stream().anyMatch(st -> "OVERDUE".equals(st.state())));
  }

  public static NoticesIssuedResponse toNoticesIssued(NoticeIssue n) {
    return new NoticesIssuedResponse(n.issued(), n.total(), n.acknowledged());
  }

  public static SecurityNoticeResponse toSecurityNotice(SecurityNotice n, NoticeDuties duties) {
    List<NoticeDutyResponse> out = new ArrayList<>();
    for (DutyState d : duties.duties()) {
      NoticeReport r = d.report();
      out.add(
          new NoticeDutyResponse(
              d.duty(),
              d.citation(),
              d.summary(),
              ts(d.dueAt()),
              d.state(),
              r == null ? null : ts(r.doneAt()),
              r == null ? null : r.reference(),
              r == null ? null : r.note(),
              r == null || r.recordedBy() == null ? null : r.recordedBy().toString()));
    }
    return new SecurityNoticeResponse(
        n.id().toString(),
        n.incidentId().toString(),
        n.title(),
        n.body(),
        ts(n.issuedAt()),
        ts(n.acknowledgedAt()),
        n.acknowledgedAt() != null,
        duties.regime(),
        duties.binding(),
        duties.bindsFrom() == null ? null : duties.bindsFrom().toString(),
        out);
  }
}
