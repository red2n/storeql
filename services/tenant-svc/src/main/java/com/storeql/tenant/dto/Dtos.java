package com.storeql.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request/response DTOs for tenant-svc. No tenant_id in requests — it comes from the JWT/context.
 */
public final class Dtos {

  private Dtos() {}

  // ── requests ─────────────────────────────────────────────────────────────────

  @Schema(name = "CreateTenantRequest", description = "Create the business (tenant).")
  public record CreateTenantRequest(
      @NotBlank String businessName,
      String legalName,
      @Schema(description = "ISO 3166-1 alpha-2 country code.") @NotBlank @Size(min = 2, max = 2)
          String country,
      @Schema(description = "ISO 4217 currency code.") @NotBlank @Size(min = 3, max = 3)
          String currency,
      @Schema(
              description =
                  "The plan chosen at signup (21.13): one on sale to the public, else the platform's"
                      + " default. 409 PLAN_NOT_SOLD or PLAN_NOT_PUBLIC otherwise.")
          String planId) {}

  @Schema(name = "UpdateTenantRequest")
  public record UpdateTenantRequest(
      @NotBlank String businessName,
      String legalName,
      @Schema(
              description =
                  "The business's VAT identifier with its country prefix, e.g. GB123456789, as its"
                      + " e-invoices name it. Unchanged when omitted; removed when empty.")
          @jakarta.validation.constraints.Size(max = 32)
          String vatNumber,
      @Schema(
              description =
                  "Where the business receives e-invoices: the Peppol electronic address scheme"
                      + " (EAS), e.g. 0088 for a GLN. With einvoiceId; unchanged when both are"
                      + " omitted, removed when both are empty.")
          @jakarta.validation.constraints.Size(max = 8)
          String einvoiceScheme,
      @Schema(description = "The business's identifier within that scheme.")
          @jakarta.validation.constraints.Size(max = 128)
          String einvoiceId) {}

  @Schema(name = "CreateStoreRequest", description = "Create a store (STORE or WAREHOUSE).")
  public record CreateStoreRequest(
      @NotBlank String name,
      @NotBlank String code,
      @Schema(description = "STORE or WAREHOUSE. Defaults to STORE.") String type,
      String line1,
      String line2,
      String city,
      String state,
      String country,
      String pincode,
      @Schema(description = "Store latitude, for geo/delivery-area features.") BigDecimal geoLat,
      @Schema(description = "Store longitude, for geo/delivery-area features.") BigDecimal geoLng,
      @Schema(
              description =
                  "Required: the IANA zone the store trades in, such as Europe/London. Never"
                      + " defaulted — no country has one right answer.")
          String timezone,
      @Schema(description = "Free-form business hours (e.g. serialized weekly schedule).")
          String businessHours,
      @Schema(
              description =
                  "Null defaults to true (show prices). false = availability-only storefront.")
          Boolean showPrices,
      @Schema(description = "Null defaults to CASH,CARD. Subset of CASH, CARD, UPI, WALLET.")
          List<String> enabledPaymentMethods,
      @Schema(
              description =
                  "What the till asks for the customer's phone: REQUIRED, OPTIONAL or OFF. Null"
                      + " defaults to OPTIONAL.")
          String tillPhone) {}

  @Schema(name = "UpdateStoreRequest")
  public record UpdateStoreRequest(
      @NotBlank String name,
      String line1,
      String line2,
      String city,
      String state,
      String country,
      String pincode,
      @Schema(description = "Store latitude, for geo/delivery-area features.") BigDecimal geoLat,
      @Schema(description = "Store longitude, for geo/delivery-area features.") BigDecimal geoLng,
      @Schema(description = "IANA zone; null keeps the store's current zone.") String timezone,
      String businessHours,
      Boolean showPrices,
      @Schema(
              description =
                  "Null keeps current value. Subset of CASH, CARD, UPI, WALLET; must not be"
                      + " empty.")
          List<String> enabledPaymentMethods,
      @Schema(
              description =
                  "What the till asks for the customer's phone: REQUIRED, OPTIONAL or OFF. Null"
                      + " keeps the current value.")
          String tillPhone) {}

  @Schema(name = "PatchStatusRequest")
  public record PatchStatusRequest(
      @Schema(description = "New status, e.g. ACTIVE or INACTIVE.") @NotBlank String status) {}

  @Schema(name = "CreateZoneRequest", description = "Create a zone (aisle/rack/etc.) in a store.")
  public record CreateZoneRequest(
      @NotBlank String name,
      @NotBlank String code,
      @Schema(description = "e.g. AISLE, COLD_ROOM, BACK_STORE. Defaults to AISLE.") String type) {}

  @Schema(name = "UpdateZoneRequest")
  public record UpdateZoneRequest(@NotBlank String name, @NotBlank String code, String type) {}

  @Schema(name = "AssignStaffRequest", description = "Assign a staff user a role at a store.")
  public record AssignStaffRequest(
      @Schema(description = "UUID of the user to assign (must already exist in iam-svc).") @NotBlank
          String userId,
      @Schema(description = "UUID of the store the role applies to.") @NotBlank String storeId,
      @Schema(
              description =
                  "A built-in role (OWNER, MANAGER, STOREKEEPER, CASHIER) or the code of one of"
                      + " the tenant's own roles (20.10).")
          @NotBlank
          @Size(max = 32)
          String role) {}

  @Schema(
      name = "DefineRoleRequest",
      description =
          "A custom role: a code and name of the tenant's own, the tier it stands on, and the"
              + " subset of that tier's permissions it holds.")
  public record DefineRoleRequest(
      @Schema(description = "Upper snake case, 2-32 characters, e.g. SHIFT_LEAD.")
          @NotBlank
          @Size(min = 2, max = 32)
          String code,
      @NotBlank @Size(max = 60) String name,
      @Schema(description = "MANAGER, STOREKEEPER or CASHIER.") @NotBlank String baseTier,
      @Schema(description = "Permission codes from GET /admin/roles/permissions; may be empty.")
          @NotNull
          @Size(max = 50)
          List<@NotBlank @Size(max = 64) String> permissions,
      @Size(max = 200) String description) {}

  @Schema(name = "UpdateRoleRequest", description = "Rename a role or change what it holds.")
  public record UpdateRoleRequest(
      @NotBlank @Size(max = 60) String name,
      @NotNull @Size(max = 50) List<@NotBlank @Size(max = 64) String> permissions,
      @Size(max = 200) String description) {}

  @Schema(name = "RoleResponse")
  public record RoleResponse(
      String code,
      String name,
      @Schema(description = "The tier the role stands on; a built-in role is its own tier.")
          String baseTier,
      List<String> permissions,
      String description,
      @Schema(description = "false for the built-in roles, which cannot be changed or removed.")
          boolean custom,
      String createdAt,
      String updatedAt) {}

  @Schema(name = "PermissionResponse", description = "One permission and who holds it by default.")
  public record PermissionResponse(String code, String description, List<String> defaultFor) {}

  // ── responses ────────────────────────────────────────────────────────────────

  @Schema(name = "TenantResponse")
  public record TenantResponse(
      String id,
      String name,
      String legalName,
      @Schema(description = "ACTIVE or INACTIVE.") String status,
      String country,
      String currency,
      String createdAt,
      String updatedAt,
      @Schema(description = "VAT identifier with its country prefix, or null.") String vatNumber,
      @Schema(description = "E-invoicing address scheme (EAS), or null.") String einvoiceScheme,
      @Schema(description = "Identifier within that scheme, or null.") String einvoiceId,
      @Schema(
              description =
                  "Why it is switched off: NON_PAYMENT (dunning, and lifted by paying up),"
                      + " ADMINISTRATOR (never lifted by a payment) or SANDBOX_DELETED (a sandbox"
                      + " its owner removed). Null when it is trading.")
          String deactivatedReason,
      @Schema(
              description =
                  "LIVE, or SANDBOX for a business's test double (22.8): nothing in a sandbox is"
                      + " real — no message leaves it, no money moves, nothing is billed.")
          String mode,
      @Schema(description = "For a SANDBOX, the live business it stands in for; null otherwise.")
          String sandboxOf) {}

  @Schema(name = "StoreResponse")
  public record StoreResponse(
      String id,
      String name,
      String code,
      @Schema(description = "STORE, WAREHOUSE or DARK_STORE.") String type,
      @Schema(description = "ACTIVE or INACTIVE.") String status,
      @Schema(description = "True if this is the tenant's default store.") boolean isDefault,
      String line1,
      String line2,
      String city,
      String state,
      String country,
      String pincode,
      BigDecimal geoLat,
      BigDecimal geoLng,
      String timezone,
      String businessHours,
      boolean showPrices,
      List<String> enabledPaymentMethods,
      @Schema(
              description =
                  "What the till asks for the customer's phone: REQUIRED, OPTIONAL or OFF.")
          String tillPhone,
      String createdAt,
      String updatedAt) {}

  /** Public storefront config for a store (what the guest shop needs to render). */
  @Schema(
      name = "StorefrontConfigResponse",
      description = "Public storefront config for a store (what the guest shop needs to render).")
  public record StorefrontConfigResponse(
      String storeId,
      String storeName,
      @Schema(description = "ACTIVE or INACTIVE.") String status,
      boolean showPrices,
      List<String> enabledPaymentMethods,
      String line1,
      String city,
      String country,
      String pincode,
      String phone,
      @Schema(
              description =
                  "The deposit return scheme in force where this store trades, or null (09.16).")
          DepositSchemeResponse depositScheme,
      @Schema(description = "STORE, WAREHOUSE or DARK_STORE.") String type,
      @Schema(
              description =
                  "Whether a shopper may collect an order here. False at a dark store, which fills"
                      + " online orders for delivery only (ship-from-store and dark-store picking).")
          boolean pickupOffered,
      @Schema(
              description =
                  "The business the store belongs to: the tenant's legal name, else its name. Always"
                      + " the business whose storefront is asked. The storefront's accessibility"
                      + " statement names it as the service provider, never a store.")
          String businessName,
      @Schema(
              description =
                  "What the store's till asks for the customer's phone: REQUIRED, OPTIONAL or OFF"
                      + " (a phone at the till). The till reads it here, from the list a cashier"
                      + " may read; it names nobody.")
          String tillPhone) {}

  @Schema(name = "ZoneResponse")
  public record ZoneResponse(
      String id,
      String storeId,
      String name,
      String code,
      @Schema(description = "e.g. AISLE, COLD_ROOM, BACK_STORE, DEFAULT.") String type,
      @Schema(description = "ACTIVE or INACTIVE.") String status,
      String createdAt,
      String updatedAt) {}

  @Schema(name = "StaffResponse")
  public record StaffResponse(
      String id,
      String userId,
      String storeId,
      @Schema(description = "The role as assigned: a tier or a custom role code.") String role,
      @Schema(description = "The tier the assignment stands on.") String baseTier,
      String assignedAt) {}

  @Schema(name = "OnboardingStatus", description = "Setup-checklist state for the tenant.")
  public record OnboardingStatus(
      boolean tenantActive, boolean hasDefaultStore, List<String> nextSteps) {}

  // ── Combined onboarding (tenant + first store in one call) ───────────────

  @Schema(
      name = "OnboardRequest",
      description = "Combined onboarding: create tenant + first store in one call.")
  public record OnboardRequest(
      // tenant
      @NotBlank String businessName,
      String legalName,
      @Schema(description = "ISO 3166-1 alpha-2 country code.") @NotBlank @Size(min = 2, max = 2)
          String country,
      @Schema(description = "ISO 4217 currency code.") @NotBlank @Size(min = 3, max = 3)
          String currency,
      // first store
      @NotBlank String storeName,
      @NotBlank String storeCode,
      @Schema(description = "STORE or WAREHOUSE. Defaults to STORE.") String storeType,
      String storeLine1,
      String storeCity,
      String storeCountry,
      String storePincode,
      @Schema(
              description =
                  "Required: the IANA zone the first store trades in, such as Europe/London.")
          String storeTimezone,
      @Schema(
              description =
                  "The plan chosen at signup (21.13): one on sale to the public, else the platform's"
                      + " default. 409 PLAN_NOT_SOLD or PLAN_NOT_PUBLIC otherwise.")
          String planId) {}

  @Schema(name = "OnboardResponse")
  public record OnboardResponse(TenantResponse tenant, StoreResponse store) {}

  // ── Gap #53: Inventory org parameters ────────────────────────────────────

  @Schema(
      name = "UpsertInventoryConfigRequest",
      description =
          "Per-tenant inventory-control parameters. Every field is optional — unset fields keep"
              + " their current (or default) value.")
  public record UpsertInventoryConfigRequest(
      Boolean lotControlEnabled,
      Boolean serialControlEnabled,
      Boolean gradeControlEnabled,
      Boolean expiryTrackingEnabled,
      @Schema(description = "One of FIFO, AVERAGE, STANDARD.")
          @Pattern(regexp = "FIFO|AVERAGE|STANDARD", message = "must be FIFO, AVERAGE or STANDARD")
          String costingMethod,
      @Schema(description = "Default unit-of-measure code, e.g. EA.") @Size(max = 16)
          String defaultUom,
      Boolean reorderAlertEnabled,
      Boolean autoReserveOnOrder) {}

  @Schema(name = "TenantInventoryConfigResponse")
  public record TenantInventoryConfigResponse(
      String id,
      String tenantId,
      boolean lotControlEnabled,
      boolean serialControlEnabled,
      boolean gradeControlEnabled,
      boolean expiryTrackingEnabled,
      @Schema(description = "FIFO, AVERAGE, or STANDARD.") String costingMethod,
      String defaultUom,
      boolean reorderAlertEnabled,
      boolean autoReserveOnOrder,
      String createdAt,
      String updatedAt) {}

  // ── delivery areas ─────────────────────────────────────────────────────────

  @Schema(
      name = "CreateDeliveryAreaRequest",
      description = "Map a pincode (postal code) to this store for home delivery fulfilment.")
  public record CreateDeliveryAreaRequest(
      @NotBlank @Size(max = 32) String pincode,
      @Schema(description = "Lower number = higher priority when multiple stores cover a pincode.")
          Integer priority) {}

  @Schema(name = "DeliveryAreaResponse")
  public record DeliveryAreaResponse(
      String id, String storeId, String pincode, int priority, String createdAt) {}

  @Schema(name = "FulfilmentResolveResponse")
  public record FulfilmentResolveResponse(
      @Schema(description = "Store that should fulfil a DELIVERY order for the given pincode.")
          String storeId,
      String storeName,
      String storeCode,
      String pincode,
      int priority) {}

  @Schema(
      name = "CurrencyRepublishResponse",
      description = "How many tenants had their declared currency re-announced.")
  public record CurrencyRepublishResponse(
      @Schema(
              description =
                  "Tenants announced. Zero means no tenant in scope has a currency recorded, not"
                      + " that the replay failed.")
          int tenantsAnnounced) {}

  // ── weighing instruments (Weights and Measures Act 1985) ────────────────────

  @Schema(name = "CreateWeighingInstrumentRequest")
  public record CreateWeighingInstrumentRequest(
      @NotBlank @Schema(description = "The shop's own name for it, unique within the store.")
          String identifier,
      @NotBlank @Schema(description = "As on the plate; unique within the tenant.")
          String serialNumber,
      String make,
      String model,
      @Schema(description = "COUNTER (default), LABELLING, PLATFORM or HANGING.") String kind,
      @Schema(description = "Max capacity as marked on the plate.") BigDecimal maxCapacity,
      @Schema(description = "UOM for maxCapacity, e.g. KG.") String capacityUom,
      @Schema(description = "The verification scale interval e, as marked.")
          BigDecimal scaleInterval,
      @Schema(description = "Type-approval / conformity certificate reference.") String approvalRef,
      @Schema(description = "The zone it stands in, if any.") String zoneId,
      @Schema(
              description =
                  "LABELLING only: JSON {prefixes:[\"20\",…], itemDigits:4|5, valueKind:"
                      + " \"PRICE\"|\"WEIGHT\", valueDecimals:n, priceCheckDigit:bool} describing"
                      + " the barcode the scale prints.")
          String labelScheme) {}

  @Schema(name = "UpdateWeighingInstrumentRequest")
  public record UpdateWeighingInstrumentRequest(
      @NotBlank String identifier,
      @NotBlank String serialNumber,
      String make,
      String model,
      String kind,
      BigDecimal maxCapacity,
      String capacityUom,
      BigDecimal scaleInterval,
      String approvalRef,
      String zoneId,
      String labelScheme) {}

  @Schema(
      name = "RecordVerificationRequest",
      description = "One entry in the instrument's history. Append-only.")
  public record RecordVerificationRequest(
      @NotBlank @Schema(description = "INITIAL, RE_VERIFICATION, INSPECTION or REPAIR.")
          String kind,
      @NotBlank @Schema(description = "ISO date the work was done.") String performedOn,
      @NotBlank @Schema(description = "The verifier or inspector, by name and organisation.")
          String performedBy,
      String certificateRef,
      @NotNull @Schema(description = "Whether the instrument was passed. A REPAIR is never a pass.")
          Boolean passed,
      @Schema(description = "ISO date it is due again; null when verified until repaired.")
          String nextDue,
      String notes) {}

  @Schema(name = "InstrumentVerificationResponse")
  public record InstrumentVerificationResponse(
      String id,
      String kind,
      String performedOn,
      String performedBy,
      String certificateRef,
      boolean passed,
      String nextDue,
      String notes,
      String recordedAt) {}

  @Schema(
      name = "WeighingInstrumentResponse",
      description =
          "An instrument and its standing for trade. certified is derived from the history on"
              + " every read: in service, latest entry a pass, and not yet due again.")
  public record WeighingInstrumentResponse(
      String id,
      String storeId,
      String identifier,
      String serialNumber,
      String make,
      String model,
      String kind,
      BigDecimal maxCapacity,
      String capacityUom,
      BigDecimal scaleInterval,
      String approvalRef,
      String zoneId,
      String labelScheme,
      @Schema(description = "IN_SERVICE, OUT_OF_SERVICE or RETIRED.") String status,
      @Schema(description = "May be used for trade today.") boolean certified,
      @Schema(
              description =
                  "Why, in a word: CERTIFIED, NEVER_VERIFIED, FAILED, REPAIRED_SINCE, OVERDUE,"
                      + " OUT_OF_SERVICE, RETIRED.")
          String standing,
      InstrumentVerificationResponse latestVerification,
      String createdAt,
      String updatedAt) {}

  // ── Legal obligations ─────────────────────────────────────────────────────

  @org.eclipse.microprofile.openapi.annotations.media.Schema(name = "ObligationResponse")
  public record ObligationResponse(
      @org.eclipse.microprofile.openapi.annotations.media.Schema(
              description = "Stable code a service checks, such as PRICE_REDUCTION_PRIOR_PRICE.")
          String code,
      @org.eclipse.microprofile.openapi.annotations.media.Schema(
              description = "EU for a regime's law, or the country's own code.")
          String scope,
      String effectiveFrom,
      @org.eclipse.microprofile.openapi.annotations.media.Schema(
              description = "The last day it applies; null while it still does.")
          String effectiveTo,
      String citation,
      String summary,
      @org.eclipse.microprofile.openapi.annotations.media.Schema(
              description = "IN_FORCE on the day asked about, or UPCOMING.")
          String status) {}

  @org.eclipse.microprofile.openapi.annotations.media.Schema(name = "ObligationsResponse")
  public record ObligationsResponse(
      String country,
      String on,
      java.util.List<ObligationResponse> obligations,
      @Schema(description = "The cash payment limits that reach the country (09.17).")
          java.util.List<CashLimitResponse> cashLimits,
      @Schema(description = "The deposit return schemes that reach the country (09.16).")
          java.util.List<DepositSchemeResponse> depositSchemes) {}

  @Schema(
      name = "DepositScheme",
      description =
          "A deposit return scheme: a drink in an in-scope container carries depositEach, in the"
              + " currency the law names, refunded when the container comes back.")
  public record DepositSchemeResponse(
      @Schema(description = "EU for a regime's law, or the country's own code.") String scope,
      String currency,
      java.math.BigDecimal depositEach,
      @Schema(description = "PET, ALUMINIUM, STEEL, GLASS.") java.util.List<String> materials,
      int minVolumeMl,
      int maxVolumeMl,
      @Schema(
              description =
                  "OUTSIDE_SCOPE: no VAT on the deposit at the sale; STANDARD: taxed as the drink.")
          String vatTreatment,
      String effectiveFrom,
      String effectiveTo,
      String citation,
      String summary,
      @Schema(description = "IN_FORCE or UPCOMING on the day asked about.") String status) {}

  @Schema(
      name = "CashLimit",
      description =
          "A cash payment limit: cash of fromAmount or more, in the currency the law names, is"
              + " refused where the store trades.")
  public record CashLimitResponse(
      @Schema(description = "EU for a regime's law, or the country's own code.") String scope,
      String currency,
      java.math.BigDecimal fromAmount,
      String effectiveFrom,
      String effectiveTo,
      String citation,
      String summary,
      @Schema(description = "IN_FORCE or UPCOMING on the day asked about.") String status) {}

  // ── Security incidents (21.15) ────────────────────────────────────────────

  @Schema(name = "CreateIncidentRequest")
  public record CreateIncidentRequest(
      @Schema(description = "EXPLOITED_VULNERABILITY, SEVERE_INCIDENT or PERSONAL_DATA_BREACH.")
          @NotBlank
          String kind,
      @NotBlank String title,
      @NotBlank String summary,
      @Schema(description = "ISO-8601 instant the platform became aware; every clock runs from it.")
          @NotBlank
          String awareAt,
      @Schema(description = "The businesses affected; absent or empty means every business.")
          List<String> tenantIds) {}

  @Schema(name = "RecordIncidentEventRequest")
  public record RecordIncidentEventRequest(
      @Schema(
              description =
                  "EARLY_WARNING_SENT, NOTIFICATION_SENT, MITIGATION_AVAILABLE, FINAL_REPORT_SENT,"
                      + " NOTE or CLOSED.")
          @NotBlank
          String kind,
      @Schema(description = "When it happened, ISO-8601; now when absent.") String occurredAt,
      @Schema(description = "The authority's reference, such as a single reporting platform case.")
          String reference,
      String note) {}

  @Schema(name = "IssueNoticesRequest")
  public record IssueNoticesRequest(
      @Schema(description = "What the businesses affected are told, and what they should do.")
          @NotBlank
          String message) {}

  @Schema(name = "IncidentStageResponse")
  public record IncidentStageResponse(
      String stage,
      String summary,
      String citation,
      @Schema(description = "Null while the clock waits on its anchor or the law sets no time.")
          String dueAt,
      String doneAt,
      @Schema(description = "DONE, DUE, OVERDUE, WAITING or NO_DEADLINE.") String state) {}

  @Schema(name = "IncidentEventResponse")
  public record IncidentEventResponse(
      String id,
      String kind,
      String occurredAt,
      String recordedAt,
      String reference,
      String note) {}

  @Schema(name = "IncidentResponse")
  public record IncidentResponse(
      String id,
      String kind,
      String title,
      String summary,
      String awareAt,
      String openedAt,
      boolean affectsAllTenants,
      List<String> tenantIds,
      @Schema(description = "OPEN or CLOSED.") String status,
      List<IncidentStageResponse> stages,
      List<IncidentEventResponse> events,
      int noticesIssued,
      int noticesAcknowledged) {}

  @Schema(name = "IncidentSummaryResponse")
  public record IncidentSummaryResponse(
      String id,
      String kind,
      String title,
      String awareAt,
      String status,
      @Schema(description = "The unfinished stage due soonest; null when no clock is running.")
          String nextStage,
      String nextDueAt,
      @Schema(description = "True when any stage is past its deadline.") boolean overdue) {}

  @Schema(name = "NoticesIssuedResponse")
  public record NoticesIssuedResponse(int issued, int total, int acknowledged) {}

  @Schema(name = "SecurityNoticeResponse")
  public record SecurityNoticeResponse(
      String id,
      String incidentId,
      String title,
      String body,
      String issuedAt,
      String acknowledgedAt,
      boolean acknowledged,
      @Schema(
              description =
                  "DPDP or GDPR for a breach; null for a notice of anything else (13.12).")
          String regime,
      @Schema(description = "Whether that regime binds the business today.") boolean binding,
      @Schema(description = "The day it starts to, when the register names one.") String bindsFrom,
      List<NoticeDutyResponse> duties) {}

  @Schema(
      name = "NoticeDuty",
      description = "One duty the business owes on a breach notice, and what it recorded.")
  public record NoticeDutyResponse(
      String duty,
      String citation,
      String summary,
      @Schema(description = "When the clock runs out, from the notice; null for 'without delay'.")
          String dueAt,
      @Schema(description = "DONE, DUE, OVERDUE or WAITING.") String state,
      String doneAt,
      String reference,
      String note,
      String recordedBy) {}

  @Schema(name = "RecordNoticeDutyRequest")
  public record RecordDutyRequest(
      @NotBlank
          @Schema(
              description =
                  "A duty of the regime: PRINCIPALS_TOLD, BOARD_INTIMATED, BOARD_REPORTED, AUTHORITY_NOTIFIED or SUBJECTS_TOLD.")
          String duty,
      @Schema(description = "When it was done, ISO-8601; now when absent.") String doneAt,
      @Schema(description = "The Board's or authority's reference, or the intimation's id.")
          String reference,
      String note) {}
}
