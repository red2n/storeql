package com.storeql.order.service;

import com.storeql.ids.Ids;
import com.storeql.order.client.ProductClient;
import com.storeql.order.client.TenantClient;
import com.storeql.order.domain.Domain;
import com.storeql.order.domain.Domain.FiscalReceipt;
import com.storeql.order.domain.Domain.FiscalStoreSettings;
import com.storeql.order.domain.Domain.Order;
import com.storeql.order.domain.Domain.OrderItem;
import com.storeql.order.domain.Domain.TseDevice;
import com.storeql.order.domain.Domain.TseStamp;
import com.storeql.order.fiscal.DsfinvkExport;
import com.storeql.order.fiscal.FiscalModules;
import com.storeql.order.fiscal.FiscalRegimeModule;
import com.storeql.order.fiscal.PtSigningKey;
import com.storeql.order.fiscal.RegisterSnapshot;
import com.storeql.order.fiscal.SaftPtExport;
import com.storeql.order.fiscal.SaleFigures;
import com.storeql.order.fiscal.TseProvider;
import com.storeql.order.fiscal.TseProviders;
import com.storeql.order.repo.FiscalReceiptRepository;
import com.storeql.order.repo.FiscalSettingsRepository;
import com.storeql.order.repo.OrderRepository;
import com.storeql.order.repo.TseDeviceRepository;
import com.storeql.service.StoreStatusRepository;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The fiscal regime a store trades under, and what it does to a receipt (18.5).
 *
 * <p>Three things: a manager places a store under a regime (and registers the device the regime
 * needs); a sale is stamped the way the regime says before and during its numbering; and the
 * register is written out as the file the regime's inspector asks for. The regimes themselves are
 * {@link FiscalRegimeModule}s; this class knows which store is under which and nothing about any
 * country's law.
 */
@ApplicationScoped
public class FiscalService {

  private static final System.Logger LOG = System.getLogger(FiscalService.class.getName());

  @Inject FiscalSettingsRepository settingsRepo;
  @Inject TseDeviceRepository deviceRepo;
  @Inject FiscalReceiptRepository receiptRepo;
  @Inject OrderRepository orders;
  @Inject StoreStatusRepository storeStatus;
  @Inject FiscalModules modules;
  @Inject TseProviders tseProviders;
  @Inject PtSigningKey ptKey;
  @Inject TenantClient tenants;
  @Inject com.storeql.service.TenantProfiles profiles;
  @Inject ProductClient products;

  /** The software producer's tax id for a Portuguese file's header; the tenant's own when unset. */
  @Inject
  @ConfigProperty(name = "storeql.fiscal.pt.producer-tax-id")
  Optional<String> producerTaxId;

  @Inject
  @ConfigProperty(name = "storeql.version", defaultValue = "1.0")
  String productVersion;

  // ── settings ──────────────────────────────────────────────────────────────

  /** A store's settings with what this deployment can offer beside them. */
  public record SettingsView(
      FiscalStoreSettings settings,
      TseDevice device,
      List<String> regimes,
      List<String> tseProviders,
      boolean ptKeyConfigured,
      String ptPublicKey) {}

  /**
   * What a store is under.
   *
   * @param tenantId owning tenant
   * @param storeId the store
   * @return the settings, NONE when none were ever set
   */
  public SettingsView settings(UUID tenantId, UUID storeId) {
    FiscalStoreSettings s =
        settingsRepo.find(tenantId, storeId).orElse(FiscalStoreSettings.none(tenantId, storeId));
    TseDevice d = deviceRepo.findByStore(tenantId, storeId).orElse(null);
    return new SettingsView(
        s,
        d,
        modules.regimes(),
        tseProviders.available(),
        ptKey.isConfigured(),
        ptKey.publicKeyBase64());
  }

  /** What a manager asks for when placing a store under a regime. */
  public record SettingsChange(
      String regime,
      String taxRegistrationNumber,
      String certificateNumber,
      String seriesValidationCode,
      String tseProvider,
      String tseTssId,
      String tseClientId) {}

  /**
   * Places a store under a regime, registering the device the regime needs when one is named.
   * Documents already issued keep their stamps; the change applies to the next sale.
   *
   * @param tenantId owning tenant
   * @param storeId the store, which must be one of this tenant's
   * @param change what was asked for
   * @param userId the manager
   * @return the settings as they now stand
   * @throws ApiException {@code FISCAL_REGIME_UNKNOWN} (400) for a regime not deployed; {@code
   *     STORE_NOT_OPERATIONAL} (409) for a store that is not this tenant's or not active; and
   *     whatever the regime refuses
   */
  public SettingsView setSettings(UUID tenantId, UUID storeId, SettingsChange change, UUID userId) {
    String regime = change.regime() == null ? "" : change.regime().trim().toUpperCase(Locale.ROOT);
    FiscalRegimeModule module = modules.forRegime(regime);
    if (module == null) {
      throw ApiException.badRequest(
          "FISCAL_REGIME_UNKNOWN",
          "regime must be one of " + modules.regimes() + " — got: " + change.regime());
    }
    if (!storeStatus.isActive(tenantId, storeId)) {
      throw ApiException.conflict(
          "STORE_NOT_OPERATIONAL", "Store " + storeId + " is not an active store of this tenant");
    }
    TseDevice device = deviceRepo.findByStore(tenantId, storeId).orElse(null);
    String wantedProvider =
        change.tseProvider() == null ? null : change.tseProvider().trim().toUpperCase(Locale.ROOT);
    // A device is prepared in memory first and written only once the regime has accepted the
    // settings: a refused placement must leave nothing behind, and driving this live found the
    // first version registering a module for a store whose tax number was then refused.
    TseDevice replacement = null;
    if (wantedProvider != null && !wantedProvider.isBlank()) {
      TseProvider provider = tseProviders.forName(wantedProvider);
      if (provider == null) {
        throw ApiException.badRequest(
            "FISCAL_TSE_PROVIDER_UNKNOWN",
            "tseProvider must be one of "
                + tseProviders.names()
                + " — got: "
                + change.tseProvider());
      }
      boolean replace =
          device == null
              || !device.provider().equals(wantedProvider)
              || (change.tseTssId() != null
                  && !change.tseTssId().isBlank()
                  && !change.tseTssId().equals(device.externalTssId()));
      if (replace) {
        String clientId =
            change.tseClientId() == null || change.tseClientId().isBlank()
                ? storeId.toString()
                : change.tseClientId().trim();
        if (!clientId.matches("[A-Za-z0-9._-]{1,64}")) {
          throw ApiException.badRequest(
              "FISCAL_TSE_CLIENT_ID_INVALID",
              "tseClientId is letters, digits, dots, underscores and hyphens, at most 64");
        }
        try {
          replacement =
              provider.register(
                  new TseProvider.RegistrationRequest(
                      tenantId, storeId, clientId, change.tseTssId(), userId));
        } catch (TseProvider.TseException e) {
          ApiException refused =
              new ApiException(
                  502,
                  "FISCAL_TSE_UNAVAILABLE",
                  "The security module provider refused: " + e.getMessage(),
                  List.of());
          refused.initCause(e);
          throw refused;
        }
      }
    }
    FiscalStoreSettings settings =
        new FiscalStoreSettings(
            tenantId,
            storeId,
            regime,
            blankToNull(change.taxRegistrationNumber()),
            blankToNull(change.certificateNumber()),
            blankToNull(change.seriesValidationCode()),
            null,
            userId);
    module.validate(settings, replacement != null ? replacement : device);
    if (replacement != null) {
      if (device != null) {
        deviceRepo.deleteByStore(tenantId, storeId);
      }
      deviceRepo.insert(replacement);
    }
    settingsRepo.upsert(settings);
    LOG.log(
        System.Logger.Level.INFO,
        "store {0} placed under fiscal regime {1} by {2}",
        storeId,
        regime,
        userId);
    return settings(tenantId, storeId);
  }

  private static String blankToNull(String v) {
    return v == null || v.isBlank() ? null : v.trim();
  }

  // ── issuing ───────────────────────────────────────────────────────────────

  /**
   * Issues (or returns) the numbered receipt for a sale, stamped as the store's regime requires:
   * the device signs before the number is taken, the document signer runs while it is taken.
   *
   * @param order the completed sale
   * @param seriesCode the numbering series
   * @param userId who is issuing, or null for the payment path
   * @return the document
   */
  public FiscalReceipt issue(Order order, String seriesCode, UUID userId) {
    var already = receiptRepo.findByOrder(order.tenantId(), order.id());
    if (already.isPresent()) {
      return already.get();
    }
    FiscalStoreSettings settings =
        settingsRepo
            .find(order.tenantId(), order.storeId())
            .orElse(FiscalStoreSettings.none(order.tenantId(), order.storeId()));
    FiscalRegimeModule module = modules.forSettings(settings);
    TseDevice device = deviceRepo.findByStore(order.tenantId(), order.storeId()).orElse(null);
    TseStamp tse = module.deviceStamp(figuresOf(order), device);
    String period = String.valueOf(order.createdAt().atZone(ZoneOffset.UTC).getYear());
    FiscalReceipt draft =
        new FiscalReceipt(
            Ids.newId(),
            order.tenantId(),
            order.storeId(),
            seriesCode,
            period,
            0L,
            null,
            order.id(),
            null,
            userId,
            order.currency(),
            order.total(),
            order.taxAmount(),
            null,
            null,
            null,
            null,
            settings.regime(),
            tse,
            null);
    return receiptRepo.issue(draft, null, module.documentSigner(settings));
  }

  /** The figures a regime signs about a sale: gross by rate, and how it was paid. */
  SaleFigures figuresOf(Order order) {
    List<OrderItem> items = orders.findOrderItems(order.tenantId(), order.id());
    Map<BigDecimal, BigDecimal> byRate = new LinkedHashMap<>();
    for (OrderItem i : items) {
      BigDecimal vat = i.vatAmount();
      if (vat == null) {
        vat =
            order.subtotal() == null || order.subtotal().signum() == 0 || order.taxAmount() == null
                ? BigDecimal.ZERO
                : order
                    .taxAmount()
                    .multiply(i.lineTotal())
                    .divide(order.subtotal(), 4, RoundingMode.HALF_UP);
      }
      BigDecimal rate = com.storeql.order.domain.LineRate.percent(i.vatRate(), vat, i.lineTotal());
      byRate.merge(rate, i.lineTotal().add(vat), BigDecimal::add);
    }
    // A discount or a rounding leaves the lines' sum off the order's total; the total is what was
    // paid and what the device must sign, so the difference lands on the largest rate bucket.
    BigDecimal linesGross = byRate.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal diff = order.total().subtract(linesGross);
    if (diff.signum() != 0 && !byRate.isEmpty()) {
      BigDecimal largest =
          byRate.entrySet().stream()
              .max(Map.Entry.comparingByValue())
              .map(Map.Entry::getKey)
              .orElseThrow();
      byRate.merge(largest, diff, BigDecimal::add);
    } else if (diff.signum() != 0) {
      byRate.put(BigDecimal.ZERO.setScale(2), diff);
    }
    List<SaleFigures.RateAmount> grossByRate = new ArrayList<>();
    for (var e : byRate.entrySet()) {
      grossByRate.add(new SaleFigures.RateAmount(e.getKey(), e.getValue()));
    }
    List<SaleFigures.TenderAmount> tenders = new ArrayList<>();
    for (var t : receiptRepo.tendersOfOrder(order.tenantId(), order.id())) {
      tenders.add(new SaleFigures.TenderAmount(t.method(), t.amount()));
    }
    if (tenders.isEmpty() && order.paymentMethod() != null) {
      tenders.add(new SaleFigures.TenderAmount(order.paymentMethod(), order.total()));
    }
    return new SaleFigures(order.id(), order.currency(), order.createdAt(), grossByRate, tenders);
  }

  // ── exports ───────────────────────────────────────────────────────────────

  /** A file for an inspector: its media type, a file name and the bytes. */
  public record Export(String contentType, String fileName, byte[] bytes) {}

  /**
   * The register as the file a regime's inspector asks for.
   *
   * @param tenantId owning tenant
   * @param storeId the store
   * @param series the series, or null for MAIN
   * @param period the fiscal year
   * @param format {@code dsfinvk} or {@code saft-pt}
   * @param ctx the caller, whose identity is forwarded to tenant-svc and product-svc
   * @return the file
   * @throws ApiException {@code FISCAL_EXPORT_FORMAT_UNKNOWN} (400); {@code
   *     FISCAL_EXPORT_DEPENDENCY_UNAVAILABLE} (503) when the store's identity could not be read — a
   *     file that names no business is not a file an inspector accepts
   */
  public Export export(
      UUID tenantId, UUID storeId, String series, String period, String format, TenantContext ctx) {
    String f = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
    if (!"dsfinvk".equals(f) && !"saft-pt".equals(f)) {
      throw ApiException.badRequest(
          "FISCAL_EXPORT_FORMAT_UNKNOWN", "format must be csv, json, dsfinvk or saft-pt");
    }
    RegisterSnapshot snapshot = snapshot(tenantId, storeId, series, period, ctx);
    if ("dsfinvk".equals(f)) {
      return new Export(
          "application/zip",
          "dsfinvk-" + snapshot.store().code() + "-" + period + ".zip",
          DsfinvkExport.write(snapshot));
    }
    String xml = SaftPtExport.write(snapshot, producerTaxId.orElse(null), productVersion);
    return new Export(
        "application/xml",
        "saft-pt-" + snapshot.store().code() + "-" + period + ".xml",
        xml.getBytes(StandardCharsets.UTF_8));
  }

  RegisterSnapshot snapshot(
      UUID tenantId, UUID storeId, String series, String period, TenantContext ctx) {
    String s =
        series == null || series.isBlank()
            ? Domain.FiscalReceipt.DEFAULT_SERIES
            : series.trim().toUpperCase(Locale.ROOT);
    var store =
        tenants
            .store(tenantId, storeId, ctx)
            .orElseThrow(
                () ->
                    new ApiException(
                        503,
                        "FISCAL_EXPORT_DEPENDENCY_UNAVAILABLE",
                        "The store's identity could not be read from tenant-svc; the file would"
                            + " name no business",
                        List.of()));
    var tenant = tenants.tenant(tenantId, ctx).orElse(null);
    FiscalStoreSettings settings =
        settingsRepo.find(tenantId, storeId).orElse(FiscalStoreSettings.none(tenantId, storeId));
    TseDevice device = deviceRepo.findByStore(tenantId, storeId).orElse(null);
    List<FiscalReceipt> docs = receiptRepo.listSeries(tenantId, storeId, s, period, 1_000_000);
    var lines = receiptRepo.linesInSeries(tenantId, storeId, s, period);
    var tenders = receiptRepo.tendersInSeries(tenantId, storeId, s, period);
    Map<Long, FiscalReceiptRepository.RegisterOrder> byNumber = new HashMap<>();
    for (var o : receiptRepo.ordersInSeries(tenantId, storeId, s, period)) {
      byNumber.put(o.number(), o);
    }
    var ids =
        lines.stream().map(FiscalReceiptRepository.RegisterLine::variantId).distinct().toList();
    Map<UUID, RegisterSnapshot.ProductName> names = new HashMap<>();
    products
        .names(tenantId, ids, ctx)
        .ifPresent(
            m ->
                m.forEach(
                    (id, v) ->
                        names.put(
                            id,
                            new RegisterSnapshot.ProductName(v.productName(), v.sku(), v.unit()))));
    String currency =
        docs.isEmpty()
            ? (tenant == null || tenant.currency() == null
                ? profiles.requireCurrency(tenantId)
                : tenant.currency())
            : docs.get(0).currency();
    return new RegisterSnapshot(
        settings,
        device,
        new RegisterSnapshot.Business(
            tenant == null || tenant.legalName() == null ? store.name() : tenant.legalName(),
            settings.taxRegistrationNumber(),
            tenant == null ? store.country() : tenant.country()),
        new RegisterSnapshot.Store(
            store.id(),
            store.name(),
            store.code() == null ? storeId.toString() : store.code(),
            store.line1(),
            store.line2(),
            store.city(),
            store.state(),
            store.country(),
            store.pincode()),
        s,
        period,
        currency,
        Instant.now(),
        docs,
        lines,
        tenders,
        byNumber,
        names);
  }
}
