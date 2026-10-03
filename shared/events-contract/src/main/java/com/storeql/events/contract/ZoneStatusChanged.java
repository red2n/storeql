package com.storeql.events.contract;

import com.storeql.events.EventPayload;
import java.util.Optional;
import java.util.UUID;

/**
 * A zone's status or storage class changed (intent/workforce-rules.md slice 9, plus the {@code
 * storageClass} of receiving-controls slice 4). A class change carries two equal statuses.
 * Producer: tenant-svc; consumer: inventory-svc's {@code zone_status} projection. {@code
 * aggregateId} is the zone's id.
 */
public final class ZoneStatusChanged {

  public static final String TYPE = "ZoneStatusChanged";
  public static final String TOPIC = "storeql.tenant.zone-status-changed";

  public static final String STORE_ID = "storeId";
  public static final String ZONE_ID = "zoneId";
  public static final String OLD_STATUS = "oldStatus";
  public static final String NEW_STATUS = "newStatus";
  public static final String STORAGE_CLASS = "storageClass";

  public static final String ACTIVE = "ACTIVE";
  public static final String OUT_OF_SERVICE = "OUT_OF_SERVICE";
  public static final String RETIRED = "RETIRED";

  public static final EventContract CONTRACT =
      new EventContract(TYPE, TOPIC, TenantScope.REQUIRED, ZoneStatusChanged::read);

  private ZoneStatusChanged() {}

  public record Read(
      Envelope envelope,
      UUID storeId,
      UUID zoneId,
      String oldStatus,
      String newStatus,
      Optional<String> storageClass) {}

  /** {@code storageClass} null = the zone has no class rule. */
  public static String payload(
      UUID tenantId,
      UUID storeId,
      UUID zoneId,
      String oldStatus,
      String newStatus,
      String storageClass) {
    return new JsonFields(EventPayload.base(TYPE, tenantId, zoneId))
        .uuid(STORE_ID, storeId)
        .uuid(ZONE_ID, zoneId)
        .str(OLD_STATUS, oldStatus)
        .str(NEW_STATUS, newStatus)
        .optStr(STORAGE_CLASS, storageClass)
        .close();
  }

  public static Read read(String json) {
    EventReader r = EventReader.open(json, TYPE);
    return new Read(
        r.envelope(TenantScope.REQUIRED),
        r.uuid(STORE_ID),
        r.uuid(ZONE_ID),
        r.string(OLD_STATUS),
        r.string(NEW_STATUS),
        r.optString(STORAGE_CLASS));
  }
}
