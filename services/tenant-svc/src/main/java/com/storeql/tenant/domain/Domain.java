package com.storeql.tenant.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Domain records for tenant-svc (Tenant → Stores → Zones). */
public final class Domain {

  private Domain() {}

  public record Tenant(
      UUID id,
      String name,
      String legalName,
      String status,
      UUID planId,
      UUID ownerUserId,
      String country,
      String currency,
      Instant createdAt,
      Instant updatedAt,
      String vatNumber,
      String einvoiceScheme,
      String einvoiceId,
      /**
       * Why it is switched off: NON_PAYMENT or ADMINISTRATOR, and null when it is on (21.12).
       *
       * <p>The distinction paying up rests on. Only NON_PAYMENT is ever lifted by money — an
       * administrator's decision is not an argument a payment can win — so without this the two
       * suspensions are indistinguishable and a payment would overrule one of them silently.
       */
      String deactivatedReason,
      /** LIVE, or SANDBOX for a business's test double (22.8). */
      String mode,
      /** For a sandbox, the live business it stands in for; null for a live business. */
      UUID sandboxOf) {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_INACTIVE = "INACTIVE";
    public static final String MODE_LIVE = "LIVE";
    public static final String MODE_SANDBOX = "SANDBOX";

    /** Why a sandbox is switched off: its owner removed it (22.8). Never lifted. */
    public static final String REASON_SANDBOX_DELETED = "SANDBOX_DELETED";

    public boolean isSandbox() {
      return MODE_SANDBOX.equals(mode);
    }

    /**
     * The name the business answers to in public: its legal name, else the name it signed up with,
     * trimmed. The storefront's accessibility statement speaks for this business — the European
     * Accessibility Act's service provider — never for one of its stores.
     *
     * @return the legal name when it is not blank, else the name; null only when both are blank
     */
    public String businessName() {
      if (legalName != null && !legalName.isBlank()) {
        return legalName.strip();
      }
      return name == null || name.isBlank() ? null : name.strip();
    }
  }

  public record Store(
      UUID id,
      UUID tenantId,
      String name,
      String code,
      String type,
      String line1,
      String line2,
      String city,
      String state,
      String country,
      String pincode,
      java.math.BigDecimal geoLat,
      java.math.BigDecimal geoLng,
      String timezone,
      String businessHours,
      String status,
      boolean isDefault,
      boolean showPrices,
      // CSV subset of PAYMENT_METHODS, e.g. "CASH,CARD,UPI" — the tenders this store accepts.
      String enabledPaymentMethods,
      // What the till asks for the customer's phone (a phone at the till): one of TILL_PHONE.
      String tillPhone,
      Instant createdAt,
      Instant updatedAt) {
    public static final String TYPE_STORE = "STORE";
    public static final String TYPE_WAREHOUSE = "WAREHOUSE";

    /**
     * A shop with no shop floor (ship-from-store and dark-store picking): it holds stock and fills
     * online orders for delivery; no collection is offered there and no till opens.
     */
    public static final String TYPE_DARK_STORE = "DARK_STORE";

    public static final java.util.List<String> TYPES =
        java.util.List.of(TYPE_STORE, TYPE_WAREHOUSE, TYPE_DARK_STORE);
    public static final java.util.List<String> PAYMENT_METHODS =
        java.util.List.of("CASH", "CARD", "UPI", "WALLET");
    public static final String DEFAULT_PAYMENT_METHODS = "CASH,CARD";

    /**
     * What a store's till asks for the customer's phone (a phone at the till): REQUIRED refuses a
     * till sale with neither a number nor a customer, OPTIONAL asks and takes a blank, OFF never
     * asks. OPTIONAL until the owner or a manager chooses.
     */
    public static final java.util.List<String> TILL_PHONE =
        java.util.List.of("REQUIRED", "OPTIONAL", "OFF");

    public static final String DEFAULT_TILL_PHONE = "OPTIONAL";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_SUSPENDED = "SUSPENDED";
    public static final String STATUS_CLOSED = "CLOSED";
    public static final java.util.List<String> STATUSES =
        java.util.List.of(STATUS_ACTIVE, STATUS_SUSPENDED, STATUS_CLOSED);
  }

  public record Zone(
      UUID id,
      UUID tenantId,
      UUID storeId,
      String name,
      String code,
      String type,
      String status,
      Instant createdAt,
      Instant updatedAt) {
    public static final String TYPE_DEFAULT = "DEFAULT";
  }

  /**
   * One staff member's role at one store.
   *
   * @param role the role as assigned: a built-in tier, or a tenant's custom role code (20.10)
   * @param baseTier the tier the role stands on — what iam-svc binds and the token carries
   */
  public record StaffAssignment(
      UUID id,
      UUID tenantId,
      UUID userId,
      UUID storeId,
      String role,
      String baseTier,
      Instant createdAt) {}

  /** The built-in roles a staff member may be assigned directly. */
  public static final java.util.Set<String> STAFF_TIERS =
      java.util.Set.of("OWNER", "MANAGER", "STOREKEEPER", "CASHIER");

  /**
   * A tenant's custom role (20.10): a name of its own, standing on one tier and holding a subset of
   * that tier's permissions.
   *
   * @param code the tenant's code, upper snake case, unique per tenant
   * @param baseTier MANAGER, STOREKEEPER or CASHIER
   * @param permissions the permission codes held; a subset of the tier's defaults
   */
  public record TenantRole(
      UUID id,
      UUID tenantId,
      String code,
      String name,
      String baseTier,
      java.util.Set<String> permissions,
      String description,
      Instant createdAt,
      Instant updatedAt) {}

  /** Paired result of creating a store and its default zone atomically. */
  public record StoreWithZone(Store store, Zone defaultZone) {}

  // ── Gap #53: Inventory org parameters ────────────────────────────────────

  public record TenantInventoryConfig(
      UUID id,
      UUID tenantId,
      boolean lotControlEnabled,
      boolean serialControlEnabled,
      boolean gradeControlEnabled,
      boolean expiryTrackingEnabled,
      String costingMethod,
      String defaultUom,
      boolean reorderAlertEnabled,
      boolean autoReserveOnOrder,
      Instant createdAt,
      Instant updatedAt) {
    public static final String COSTING_FIFO = "FIFO";
    public static final String COSTING_AVERAGE = "AVERAGE";
    public static final String COSTING_STANDARD = "STANDARD";
  }

  public record TenantWithStore(Tenant tenant, Store store) {}

  /**
   * Just the two columns a currency replay needs. Deliberately not {@link Tenant}: a replay over
   * every tenant on the platform should not pull ten unused columns per row into memory.
   *
   * @param tenantId the tenant whose currency is being re-announced
   * @param currency ISO-4217 alpha-3 code, as declared at onboarding
   */
  public record TenantCurrency(UUID tenantId, String currency) {}

  /** Pincode → store fulfilment mapping (one row per store coverage). */
  public record DeliveryArea(
      UUID id, UUID tenantId, UUID storeId, String pincode, int priority, Instant createdAt) {}

  /**
   * A weighing instrument a store uses for trade (Weights and Measures Act 1985 s.11), and what the
   * register knows about it. Whether it may be used for trade today is {@link
   * WeighingInstrumentWithStanding#certified()}, derived from its verification history.
   */
  public record WeighingInstrument(
      UUID id,
      UUID tenantId,
      UUID storeId,
      String identifier,
      String serialNumber,
      String make,
      String model,
      String kind,
      java.math.BigDecimal maxCapacity,
      String capacityUom,
      java.math.BigDecimal scaleInterval,
      String approvalRef,
      UUID zoneId,
      String labelScheme,
      String status,
      Instant createdAt,
      Instant updatedAt) {
    public static final String STATUS_IN_SERVICE = "IN_SERVICE";
    public static final String STATUS_OUT_OF_SERVICE = "OUT_OF_SERVICE";
    public static final String STATUS_RETIRED = "RETIRED";
    public static final java.util.Set<String> KINDS =
        java.util.Set.of("COUNTER", "LABELLING", "PLATFORM", "HANGING");
    public static final java.util.Set<String> STATUSES =
        java.util.Set.of(STATUS_IN_SERVICE, STATUS_OUT_OF_SERVICE, STATUS_RETIRED);
  }

  /**
   * One append-only entry in an instrument's history: a verification that passed or failed, an
   * inspection, or a repair that broke the stamp.
   */
  public record InstrumentVerification(
      UUID id,
      UUID tenantId,
      UUID instrumentId,
      String kind,
      java.time.LocalDate performedOn,
      String performedBy,
      String certificateRef,
      boolean passed,
      java.time.LocalDate nextDue,
      String notes,
      UUID recordedBy,
      Instant recordedAt) {
    public static final String KIND_REPAIR = "REPAIR";
    public static final java.util.Set<String> KINDS =
        java.util.Set.of("INITIAL", "RE_VERIFICATION", "INSPECTION", KIND_REPAIR);
  }

  /**
   * An instrument with its standing for trade, derived rather than stored: in service, its latest
   * history entry a pass, and that pass not yet due again. A repair is never a pass, so an
   * instrument repaired since its last verification is not certified until verified again.
   */
  public record WeighingInstrumentWithStanding(
      WeighingInstrument instrument,
      InstrumentVerification latest,
      boolean certified,
      String standing) {}

  /**
   * A legal obligation as it reaches one country: the obligation's own window, narrowed to the
   * country's membership of the regime it comes through (V9). A British business is not bound by EU
   * law made after 31 January 2020.
   */
  public record LegalObligation(
      String code,
      String scopeKind,
      String scope,
      java.time.LocalDate effectiveFrom,
      java.time.LocalDate effectiveTo,
      String citation,
      String summary) {
    public static final String IN_FORCE = "IN_FORCE";
    public static final String UPCOMING = "UPCOMING";

    /** IN_FORCE on {@code day}, or UPCOMING when it has not taken effect by then. */
    public String statusOn(java.time.LocalDate day) {
      return effectiveFrom.isAfter(day) ? UPCOMING : IN_FORCE;
    }

    /** True when the obligation had stopped applying before {@code day}. */
    public boolean endedBefore(java.time.LocalDate day) {
      return effectiveTo != null && effectiveTo.isBefore(day);
    }

    /** False for a window that closed before it opened: a membership that ended first. */
    public boolean everApplies() {
      return effectiveTo == null || !effectiveTo.isBefore(effectiveFrom);
    }
  }

  /** The obligations that bind a country on a day, in force first and then those still to come. */
  public record ObligationSheet(
      String country,
      java.time.LocalDate on,
      java.util.List<LegalObligation> obligations,
      java.util.List<CashLimit> cashLimits,
      java.util.List<DepositScheme> depositSchemes) {}

  /**
   * A rule the law dates: in force from a day, until a day or open-ended. Shared by the cash limits
   * (09.17) and the deposit schemes (09.16), which the sheet reports with the same status words.
   */
  public interface Dated {
    java.time.LocalDate effectiveFrom();

    java.time.LocalDate effectiveTo();

    default boolean inForceOn(java.time.LocalDate day) {
      return !effectiveFrom().isAfter(day)
          && (effectiveTo() == null || !effectiveTo().isBefore(day));
    }

    default boolean endedBefore(java.time.LocalDate day) {
      return effectiveTo() != null && effectiveTo().isBefore(day);
    }

    default String status(java.time.LocalDate day) {
      return inForceOn(day) ? LegalObligation.IN_FORCE : LegalObligation.UPCOMING;
    }
  }

  /**
   * A deposit return scheme reaching a country (09.16): what a drink in an in-scope container
   * carries as a deposit, in the currency the law names, and how the deposit is taxed.
   */
  public record DepositScheme(
      String scopeKind,
      String scope,
      String currency,
      java.math.BigDecimal depositEach,
      java.util.List<String> materials,
      int minVolumeMl,
      int maxVolumeMl,
      String vatTreatment,
      java.time.LocalDate effectiveFrom,
      java.time.LocalDate effectiveTo,
      String citation,
      String summary)
      implements Dated {}

  /**
   * A cash payment limit that reaches a country (09.17): the amount at and above which cash is
   * refused, in the currency the law names, with the instrument behind it.
   */
  public record CashLimit(
      String scopeKind,
      String scope,
      String currency,
      java.math.BigDecimal fromAmount,
      java.time.LocalDate effectiveFrom,
      java.time.LocalDate effectiveTo,
      String citation,
      String summary)
      implements Dated {}

  // ── security incidents (21.15) ─────────────────────────────────────────────

  /** A security incident on the platform register. */
  public record SecurityIncident(
      UUID id,
      String kind,
      String title,
      String summary,
      Instant awareAt,
      Instant openedAt,
      UUID openedBy,
      boolean affectsAllTenants,
      java.util.List<UUID> tenantIds) {}

  /** One entry in an incident's append-only timeline. */
  public record IncidentEvent(
      UUID id,
      UUID incidentId,
      String kind,
      Instant occurredAt,
      Instant recordedAt,
      UUID recordedBy,
      String reference,
      String note) {}

  /** A statutory reporting stage for a kind of incident, as reference data (V11). */
  public record ReportingStage(
      String incidentKind,
      String stage,
      String anchor,
      String dueAfter,
      int position,
      String citation,
      String summary) {}

  /** A stage as it stands: due, overdue, done, waiting on its anchor, or with no fixed time. */
  public record StageStatus(
      String stage, String summary, String citation, Instant dueAt, Instant doneAt, String state) {}

  /** An incident with its timeline, its stages as they stand, and how its notices stand. */
  public record IncidentSheet(
      SecurityIncident incident,
      java.util.List<IncidentEvent> events,
      java.util.List<StageStatus> stages,
      boolean closed,
      int noticesIssued,
      int noticesAcknowledged) {}

  /** A notice to one business about an incident, and whether it has been acknowledged. */
  public record SecurityNotice(
      UUID id,
      UUID tenantId,
      UUID incidentId,
      String title,
      String body,
      Instant issuedAt,
      UUID issuedBy,
      Instant acknowledgedAt,
      UUID acknowledgedBy) {}

  /** What issuing notices did: how many were new, how many exist, how many are acknowledged. */
  public record NoticeIssue(int issued, int total, int acknowledged) {}

  /**
   * One duty a business owes when told of a personal data breach, by the regime that binds it
   * (13.12): reference data with its citation, as the platform's own reporting stages are.
   */
  public record BreachDuty(
      String regime,
      String duty,
      String anchor,
      String dueAfter,
      int position,
      String citation,
      String summary) {}

  /** What a business recorded it did about one duty on one notice. */
  public record NoticeReport(
      UUID id,
      UUID tenantId,
      UUID noticeId,
      String duty,
      Instant doneAt,
      String reference,
      String note,
      UUID recordedBy,
      Instant recordedAt) {}

  /** One duty as it stands on one notice: its clock from the notice, and what was recorded. */
  public record DutyState(
      String duty,
      String citation,
      String summary,
      Instant dueAt,
      String state,
      NoticeReport report) {}

  /** A notice's duties under the regime that binds the business, and from when it binds. */
  public record NoticeDuties(
      String regime, boolean binding, java.time.LocalDate bindsFrom, List<DutyState> duties) {

    public static NoticeDuties none() {
      return new NoticeDuties(null, false, null, List.of());
    }
  }

  /**
   * One exchange rate a business keeps (03.x): {@code rate} home units per one unit of {@code
   * currency}, in force from {@code effectiveFrom}. A row is never changed; a new rate is a new
   * row.
   */
  public record FxRate(
      UUID id,
      UUID tenantId,
      String currency,
      BigDecimal rate,
      LocalDate effectiveFrom,
      String reason,
      UUID setBy,
      Instant setAt) {}
}
