package com.storeql.pricing.service;

import com.storeql.ids.Ids;
import com.storeql.pricing.domain.Domain.VatObligation;
import com.storeql.pricing.domain.Domain.VatRegistration;
import com.storeql.pricing.domain.Domain.VatReturn;
import com.storeql.pricing.domain.Domain.VatReturnSubmission;
import com.storeql.pricing.provider.HmrcMtdVatProvider;
import com.storeql.pricing.provider.TokenCipher;
import com.storeql.pricing.provider.VatSubmissionProvider;
import com.storeql.pricing.provider.VatSubmissionProviders;
import com.storeql.pricing.provider.Vrn;
import com.storeql.pricing.repo.MtdRepository;
import com.storeql.service.TenantProfiles;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Making Tax Digital for VAT (18.5): the digital link from the return this service computes to
 * HMRC. A tenant registers the number it files under and the path it files through; the manager
 * connects HMRC's grant once; a filing takes the boxes from {@link PricingService#computeVatReturn}
 * as they are, sends them, and records the answer — accepted or refused — where nothing can edit
 * it.
 *
 * <p>The return is UK law and in pounds: its boxes are the tax transactions' amounts summed as
 * recorded, with nothing converting them. So only a business that uses UK law may register for it
 * or file it — one whose own profile (tenant-svc's, never a request) says its home country is GB
 * and its currency GBP — and a profile that cannot be read refuses, because what goes to a tax
 * authority fails closed. Every other business is told {@code VAT_RETURN_NOT_AVAILABLE}.
 */
@ApplicationScoped
public class MtdService {

  private static final System.Logger LOG = System.getLogger(MtdService.class.getName());
  private static final int TOKEN_LEEWAY_SECONDS = 60;

  /** HMRC's return is filed by a business whose home country is this, in this currency. */
  static final String RETURN_COUNTRY = "GB";

  static final String RETURN_CURRENCY = "GBP";

  @Inject MtdRepository repo;
  @Inject PricingService pricing;
  @Inject VatSubmissionProviders providers;
  @Inject HmrcMtdVatProvider hmrc;
  @Inject TokenCipher cipher;
  @Inject TenantProfiles profiles;

  /** A registration and what the deployment can offer beside it. */
  public record RegistrationView(
      VatRegistration registration, List<String> providers, boolean hmrcConfigured) {}

  /**
   * @param tenantId owning tenant
   * @return the registration, with a null registration when the tenant has none yet — the offer is
   *     what the screen needs then
   */
  public RegistrationView registration(UUID tenantId) {
    return new RegistrationView(
        repo.findRegistration(tenantId).orElse(null), providers.available(), hmrc.isConfigured());
  }

  /**
   * Registers the VAT number a tenant files under and the path it files through. HMRC's grant is
   * kept when the provider stays HMRC and dropped when it changes: a grant is for one number.
   *
   * @throws ApiException {@code VAT_RETURN_NOT_AVAILABLE} (409) for a business that is not a UK
   *     one, and {@code TENANT_PROFILE_UNAVAILABLE} (503) when that cannot be told; {@code
   *     MTD_VRN_INVALID} (400) when the number fails HMRC's check digit; {@code
   *     MTD_PROVIDER_UNKNOWN} (400); {@code MTD_PROVIDER_NOT_CONFIGURED} (409) when the deployment
   *     lacks what the provider needs
   */
  public RegistrationView register(
      UUID tenantId, String vrnInput, String providerInput, UUID userId) {
    requireUkBusiness(tenantId);
    String vrn = Vrn.normalise(vrnInput);
    if (vrn == null) {
      throw ApiException.badRequest(
          "MTD_VRN_INVALID", "A VAT registration number is nine digits with HMRC's check digit");
    }
    String name = providerInput == null ? "" : providerInput.trim().toUpperCase(Locale.ROOT);
    VatSubmissionProvider provider = providers.forName(name);
    if (provider == null) {
      throw ApiException.badRequest(
          "MTD_PROVIDER_UNKNOWN",
          "provider must be one of " + VatRegistration.PROVIDERS + " — got: " + providerInput);
    }
    if (!provider.isConfigured()) {
      throw ApiException.conflict(
          "MTD_PROVIDER_NOT_CONFIGURED",
          "The "
              + name
              + " provider is not configured on this deployment (client id, secret and"
              + " token key are required)");
    }
    VatRegistration existing = repo.findRegistration(tenantId).orElse(null);
    boolean keepGrant =
        existing != null
            && existing.provider().equals(name)
            && existing.vrn().equals(vrn)
            && VatRegistration.PROVIDER_HMRC.equals(name);
    repo.upsertRegistration(
        new VatRegistration(
            tenantId,
            vrn,
            name,
            keepGrant ? existing.accessTokenCipher() : null,
            keepGrant ? existing.refreshTokenCipher() : null,
            keepGrant ? existing.tokenExpiresAt() : null,
            keepGrant ? existing.connectedAt() : null,
            null,
            userId));
    return registration(tenantId);
  }

  /**
   * Where the manager goes to grant HMRC access for the registered number.
   *
   * @throws ApiException {@code MTD_NOT_HMRC} (409) when the tenant files through a simulator
   */
  public String authorizeUrl(UUID tenantId, String redirectUri) {
    VatRegistration reg = requireRegistration(tenantId);
    requireHmrc(reg);
    if (redirectUri == null || !redirectUri.matches("https?://.+")) {
      throw ApiException.badRequest("MTD_REDIRECT_INVALID", "redirectUri must be an http(s) URL");
    }
    return hmrc.authorizeUrl(redirectUri, tenantId.toString());
  }

  /** Exchanges HMRC's code for the taxpayer's grant and keeps it, encrypted. */
  public RegistrationView connect(UUID tenantId, String code, String redirectUri, UUID userId) {
    VatRegistration reg = requireRegistration(tenantId);
    requireHmrc(reg);
    if (code == null || code.isBlank()) {
      throw ApiException.badRequest("MTD_CODE_REQUIRED", "code is required");
    }
    HmrcMtdVatProvider.Tokens t;
    try {
      t = hmrc.exchangeCode(code.trim(), redirectUri);
    } catch (VatSubmissionProvider.ProviderException e) {
      throw fromProvider(e.retryable() ? 503 : 400, e.code(), e.getMessage(), e);
    }
    repo.upsertRegistration(
        new VatRegistration(
            tenantId,
            reg.vrn(),
            reg.provider(),
            cipher.encrypt(t.accessToken()),
            t.refreshToken() == null ? null : cipher.encrypt(t.refreshToken()),
            t.expiresAt(),
            Instant.now(),
            null,
            userId));
    return registration(tenantId);
  }

  /**
   * The periods the taxpayer must file for.
   *
   * @throws ApiException {@code PRICING_INVALID_PERIOD} (400) when from is not before to
   */
  public List<VatObligation> obligations(UUID tenantId, Instant from, Instant to) {
    if (!from.isBefore(to)) {
      throw ApiException.badRequest("PRICING_INVALID_PERIOD", "from must be before to");
    }
    VatRegistration reg = fresh(requireRegistration(tenantId));
    try {
      return providers
          .forName(reg.provider())
          .obligations(reg, from, to, repo.acceptedPeriodKeys(tenantId, reg.vrn()));
    } catch (VatSubmissionProvider.ProviderException e) {
      throw fromProvider(e.retryable() ? 503 : 422, e.code(), e.getMessage(), e);
    }
  }

  /** What the manager's browser collected for HMRC's fraud-prevention headers. */
  public record ClientFingerprint(Map<String, String> values) {
    static Map<String, String> headers(ClientFingerprint f, UUID userId) {
      Map<String, String> h = new LinkedHashMap<>();
      Map<String, String> v = f == null || f.values() == null ? Map.of() : f.values();
      put(h, "Gov-Client-Timezone", v.get("timezone"));
      put(h, "Gov-Client-Screens", v.get("screens"));
      put(h, "Gov-Client-Window-Size", v.get("windowSize"));
      put(h, "Gov-Client-Browser-JS-User-Agent", v.get("userAgent"));
      put(h, "Gov-Client-Device-ID", v.get("deviceId"));
      put(h, "Gov-Client-Browser-Do-Not-Track", v.get("doNotTrack"));
      put(h, "Gov-Client-Public-IP", v.get("publicIp"));
      put(h, "Gov-Client-Public-IP-Timestamp", v.get("publicIpTimestamp"));
      if (userId != null) {
        h.put("Gov-Client-User-IDs", "storeql=" + userId);
      }
      return h;
    }

    private static void put(Map<String, String> h, String k, String v) {
      if (v != null && !v.isBlank()) {
        h.put(k, v.trim());
      }
    }
  }

  /**
   * Files the return for a period: the boxes as computed, boxes 6–9 in whole pounds as the API
   * requires, {@code finalised} because HMRC accepts nothing else. What HMRC answers is recorded
   * whether it accepted or refused; a refusal is also thrown, with HMRC's code.
   *
   * @throws ApiException {@code MTD_NOT_REGISTERED} (404); {@code VAT_RETURN_NOT_AVAILABLE} (409)
   *     for a business that is not a UK one, even with a registration on file, and {@code
   *     TENANT_PROFILE_UNAVAILABLE} (503) when that cannot be told; {@code MTD_NOT_FINALISED}
   *     (400); {@code MTD_PERIOD_KEY_INVALID} (400); {@code MTD_DUPLICATE_SUBMISSION} (409) when
   *     this period is already filed and accepted; HMRC's own code (422) when it refused; {@code
   *     HMRC_UNREACHABLE} (503)
   */
  public VatReturnSubmission submit(
      UUID tenantId,
      String periodKey,
      Instant from,
      Instant to,
      boolean finalised,
      ClientFingerprint client,
      UUID userId) {
    VatRegistration reg = requireRegistration(tenantId);
    // A registration on file does not make the business a UK one: the gate is asked again here,
    // where the figures would leave for HMRC, so a registration that predates it files nothing.
    requireUkBusiness(tenantId);
    if (!finalised) {
      throw ApiException.badRequest(
          "MTD_NOT_FINALISED", "A return is filed only when the taxpayer declares it final");
    }
    if (periodKey == null || !periodKey.trim().matches("[A-Z0-9#]{4}")) {
      throw ApiException.badRequest(
          "MTD_PERIOD_KEY_INVALID", "periodKey is HMRC's four-character key for the obligation");
    }
    String key = periodKey.trim();
    if (repo.acceptedPeriodKeys(tenantId, reg.vrn()).contains(key)) {
      throw ApiException.conflict(
          "MTD_DUPLICATE_SUBMISSION", "Period " + key + " has already been filed and accepted");
    }
    VatReturn computed = pricing.computeVatReturn(tenantId, from, to);
    VatReturn boxes =
        new VatReturn(
            computed.box1(),
            computed.box2(),
            computed.box3(),
            computed.box4(),
            computed.box5(),
            wholePounds(computed.box6()),
            wholePounds(computed.box7()),
            wholePounds(computed.box8()),
            wholePounds(computed.box9()),
            computed.periodFrom(),
            computed.periodTo());
    reg = fresh(reg);
    VatSubmissionProvider provider = providers.forName(reg.provider());
    Instant now = Instant.now();
    UUID id = Ids.newId();
    try {
      VatSubmissionProvider.Receipt receipt =
          provider.submit(reg, key, boxes, ClientFingerprint.headers(client, userId));
      VatReturnSubmission accepted =
          new VatReturnSubmission(
              id,
              tenantId,
              reg.vrn(),
              key,
              from,
              to,
              boxes,
              true,
              reg.provider(),
              VatReturnSubmission.STATUS_ACCEPTED,
              now,
              userId,
              receipt.processingDate(),
              receipt.formBundleNumber(),
              receipt.paymentIndicator(),
              receipt.chargeRefNumber(),
              receipt.receiptId(),
              receipt.receiptTimestamp(),
              null,
              null);
      repo.insertSubmission(accepted);
      LOG.log(
          System.Logger.Level.INFO,
          "VAT return {0} filed for tenant {1} via {2}",
          key,
          tenantId,
          reg.provider());
      return accepted;
    } catch (VatSubmissionProvider.ProviderException e) {
      VatReturnSubmission rejected =
          new VatReturnSubmission(
              id,
              tenantId,
              reg.vrn(),
              key,
              from,
              to,
              boxes,
              true,
              reg.provider(),
              VatReturnSubmission.STATUS_REJECTED,
              now,
              userId,
              null,
              null,
              null,
              null,
              null,
              null,
              e.code(),
              e.getMessage());
      repo.insertSubmission(rejected);
      throw fromProvider(e.retryable() ? 503 : 422, e.code(), e.getMessage(), e);
    }
  }

  /**
   * @return every return filed, newest first
   */
  public List<VatReturnSubmission> submissions(UUID tenantId) {
    return repo.listSubmissions(tenantId, 100);
  }

  /**
   * @throws ApiException {@code MTD_SUBMISSION_NOT_FOUND} (404) when it is not this tenant's
   */
  public VatReturnSubmission submission(UUID tenantId, UUID id) {
    return repo.findSubmission(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("MTD_SUBMISSION_NOT_FOUND", "No such filing"));
  }

  /** A provider's refusal as the API reports it, with the provider's failure kept as the cause. */
  private static ApiException fromProvider(
      int status, String code, String message, VatSubmissionProvider.ProviderException e) {
    ApiException out = new ApiException(status, code, message, List.of());
    out.initCause(e);
    return out;
  }

  /** HMRC's rule for boxes 6–9: whole pounds, rounded down. */
  static BigDecimal wholePounds(BigDecimal v) {
    return v.setScale(0, RoundingMode.DOWN);
  }

  /**
   * The business's own profile must say it uses UK law: home country GB and currency GBP.
   *
   * @throws ApiException {@code VAT_RETURN_NOT_AVAILABLE} (409) for any other business; {@code
   *     TENANT_PROFILE_UNAVAILABLE} (503) when tenant-svc cannot say, because nothing is assumed
   *     and nothing goes to a tax authority on a guess
   */
  private void requireUkBusiness(UUID tenantId) {
    String country = profiles.requireCountry(tenantId);
    String currency = profiles.requireCurrency(tenantId);
    if (!RETURN_COUNTRY.equals(country) || !RETURN_CURRENCY.equals(currency)) {
      throw ApiException.conflict(
          "VAT_RETURN_NOT_AVAILABLE",
          "The VAT return is HMRC's, in pounds, for a business whose home country is "
              + RETURN_COUNTRY
              + " and whose currency is "
              + RETURN_CURRENCY
              + "; this business's is "
              + country
              + " and "
              + currency);
    }
  }

  private VatRegistration requireRegistration(UUID tenantId) {
    return repo.findRegistration(tenantId)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "MTD_NOT_REGISTERED",
                    "This business has not registered a VAT number to file under"));
  }

  private static void requireHmrc(VatRegistration reg) {
    if (!VatRegistration.PROVIDER_HMRC.equals(reg.provider())) {
      throw ApiException.conflict(
          "MTD_NOT_HMRC",
          "This business files through " + reg.provider() + ", which needs no grant");
    }
  }

  /** The registration with a live access token: refreshed when it is about to expire. */
  private VatRegistration fresh(VatRegistration reg) {
    if (!VatRegistration.PROVIDER_HMRC.equals(reg.provider())
        || reg.refreshTokenCipher() == null
        || reg.tokenExpiresAt() == null
        || reg.tokenExpiresAt().isAfter(Instant.now().plusSeconds(TOKEN_LEEWAY_SECONDS))) {
      return reg;
    }
    HmrcMtdVatProvider.Tokens t;
    try {
      t = hmrc.refresh(cipher.decrypt(reg.refreshTokenCipher()));
    } catch (VatSubmissionProvider.ProviderException e) {
      throw fromProvider(
          e.retryable() ? 503 : 409,
          "HMRC_NOT_CONNECTED",
          "HMRC's grant could not be refreshed; connect again: " + e.getMessage(),
          e);
    }
    VatRegistration refreshed =
        new VatRegistration(
            reg.tenantId(),
            reg.vrn(),
            reg.provider(),
            cipher.encrypt(t.accessToken()),
            t.refreshToken() == null ? reg.refreshTokenCipher() : cipher.encrypt(t.refreshToken()),
            t.expiresAt(),
            reg.connectedAt(),
            null,
            reg.updatedBy());
    repo.upsertRegistration(refreshed);
    return refreshed;
  }
}
