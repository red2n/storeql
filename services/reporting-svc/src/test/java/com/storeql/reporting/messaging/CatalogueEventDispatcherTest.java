package com.storeql.reporting.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.storeql.ids.Ids;
import com.storeql.reporting.service.ReportingService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * CatalogueEventDispatcher routes product-svc's catalogue events to the variant → product →
 * category projection that sales by category reads, and skips what it cannot read without throwing,
 * so the consumer loop acks it.
 */
class CatalogueEventDispatcherTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID PRODUCT = Ids.newId();
  private static final UUID LEAF = Ids.newId();
  private static final UUID ROOT = Ids.newId();
  private static final UUID V1 = Ids.newId();
  private static final UUID V2 = Ids.newId();

  private static final class CapturingService extends ReportingService {
    int categorised;
    int variants;
    UUID productId;
    List<UUID> categoryPath;
    List<UUID> variantIds;
    Instant occurredAt;
    UUID variantId;
    UUID variantProductId;

    @Override
    public void applyProductCategorised(
        UUID tenantId,
        UUID productId,
        List<UUID> categoryPath,
        List<UUID> variantIds,
        Instant occurredAt) {
      this.categorised++;
      this.productId = productId;
      this.categoryPath = categoryPath;
      this.variantIds = variantIds;
      this.occurredAt = occurredAt;
    }

    @Override
    public void applyVariantCreated(UUID tenantId, UUID variantId, UUID productId) {
      this.variants++;
      this.variantId = variantId;
      this.variantProductId = productId;
    }
  }

  private CapturingService service;
  private CatalogueEventDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    service = new CapturingService();
    dispatcher = new CatalogueEventDispatcher();
    dispatcher.service = service;
  }

  @Test
  void productCategorisedProjectsThePathAndTheVariants() {
    String json =
        "{\"eventId\":\""
            + Ids.newId()
            + "\",\"eventType\":\"ProductCategorised\",\"tenantId\":\""
            + TENANT
            + "\",\"aggregateId\":\""
            + PRODUCT
            + "\",\"occurredAt\":\"2026-09-23T10:00:00Z\",\"productId\":\""
            + PRODUCT
            + "\",\"categoryPath\":[\""
            + LEAF
            + "\",\""
            + ROOT
            + "\"],\"variantIds\":[\""
            + V1
            + "\",\""
            + V2
            + "\"]}";

    dispatcher.dispatch("storeql.catalog.product-categorised", json);

    assertEquals(1, service.categorised);
    assertEquals(PRODUCT, service.productId);
    assertEquals(List.of(LEAF, ROOT), service.categoryPath, "leaf first, root last, as published");
    assertEquals(List.of(V1, V2), service.variantIds);
    assertEquals(Instant.parse("2026-09-23T10:00:00Z"), service.occurredAt);
  }

  @Test
  void aProductWithNoCategoryHasAnEmptyPath() {
    String json =
        "{\"eventId\":\""
            + Ids.newId()
            + "\",\"eventType\":\"ProductCategorised\",\"tenantId\":\""
            + TENANT
            + "\",\"aggregateId\":\""
            + PRODUCT
            + "\",\"occurredAt\":\"2026-09-23T10:00:00Z\",\"productId\":\""
            + PRODUCT
            + "\",\"categoryPath\":[],\"variantIds\":[]}";

    dispatcher.dispatch("storeql.catalog.product-categorised", json);

    assertEquals(1, service.categorised);
    assertEquals(List.of(), service.categoryPath);
    assertNotNull(service.occurredAt);
  }

  @Test
  void variantCreatedTiesTheVariantToItsProduct() {
    String json =
        "{\"eventId\":\""
            + Ids.newId()
            + "\",\"eventType\":\"VariantCreated\",\"tenantId\":\""
            + TENANT
            + "\",\"aggregateId\":\""
            + V1
            + "\",\"occurredAt\":\"2026-09-23T10:00:00Z\",\"productId\":\""
            + PRODUCT
            + "\",\"sku\":\"COLA-330\"}";

    dispatcher.dispatch("storeql.catalog.variant-created", json);

    assertEquals(1, service.variants);
    assertEquals(V1, service.variantId);
    assertEquals(PRODUCT, service.variantProductId);
  }

  @Test
  void malformedAndUnknownEventsAreSkippedWithoutThrowing() {
    dispatcher.dispatch("storeql.catalog.product-categorised", "{not json");
    dispatcher.dispatch(
        "storeql.catalog.product-categorised", "{\"eventType\":\"ProductCategorised\"}");
    dispatcher.dispatch("storeql.catalog.product-created", "{\"eventType\":\"ProductCreated\"}");
    assertEquals(0, service.categorised);
    assertEquals(0, service.variants);
  }
}
