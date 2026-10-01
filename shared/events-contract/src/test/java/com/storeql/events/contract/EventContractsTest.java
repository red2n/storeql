package com.storeql.events.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import java.io.File;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class EventContractsTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID STORE = Ids.newId();
  private static final UUID AGG = Ids.newId();
  private static final UUID USER = Ids.newId();

  /**
   * One full payload per catalogued type. A new type with no entry here fails the catalogue test.
   */
  private static final Map<String, Supplier<String>> FULL = new LinkedHashMap<>();

  /** The same event with every optional member left out. */
  private static final Map<String, Supplier<String>> MINIMAL = new LinkedHashMap<>();

  static {
    FULL.put(
        ExceptionAlertRaised.TYPE,
        () ->
            ExceptionAlertRaised.payload(
                TENANT,
                AGG,
                "returns.rate",
                STORE,
                "STAFF",
                "user-key",
                new BigDecimal("7.5000"),
                new BigDecimal("5"),
                60,
                ExceptionAlertRaised.UNIT_MONEY,
                "EUR",
                List.of(new ExceptionAlertRaised.Evidence("RETURN", Ids.newId()))));
    MINIMAL.put(
        ExceptionAlertRaised.TYPE,
        () ->
            ExceptionAlertRaised.payload(
                TENANT,
                AGG,
                "returns.rate",
                null,
                "STAFF",
                "user-key",
                BigDecimal.TEN,
                BigDecimal.ONE,
                5,
                ExceptionAlertRaised.UNIT_COUNT,
                null,
                null));
    FULL.put(
        ExceptionRuleChanged.TYPE,
        () -> ExceptionRuleChanged.payload(TENANT, AGG, "returns.rate", STORE, 3));
    MINIMAL.put(
        ExceptionRuleChanged.TYPE,
        () -> ExceptionRuleChanged.payload(TENANT, AGG, "returns.rate", null, 3));
    FULL.put(
        ApprovalRequested.TYPE,
        () ->
            ApprovalRequested.payload(
                TENANT,
                AGG,
                "refund.large",
                STORE,
                "ORDER",
                Ids.newId(),
                USER,
                "CASHIER",
                ApprovalValue.money(new BigDecimal("120.50"), "GBP", new BigDecimal("120.50")),
                "sales.refund",
                2,
                Instant.parse("2026-10-01T10:00:00Z"),
                ApprovalRequested.SCOPE_BUSINESS,
                null));
    MINIMAL.put(
        ApprovalRequested.TYPE,
        () ->
            ApprovalRequested.payload(
                null,
                AGG,
                "platform.suspend",
                null,
                "TENANT",
                Ids.newId(),
                USER,
                null,
                ApprovalValue.none(),
                "platform.suspend",
                1,
                null,
                ApprovalRequested.SCOPE_PLATFORM,
                null));
    FULL.put(
        ApprovalDecided.TYPE,
        () ->
            ApprovalDecided.payload(
                TENANT,
                AGG,
                "refund.large",
                STORE,
                USER,
                ApprovalDecided.APPROVED,
                Ids.newId(),
                true,
                ApprovalValue.quantity(new BigDecimal("12"))));
    MINIMAL.put(
        ApprovalDecided.TYPE,
        () ->
            ApprovalDecided.payload(
                TENANT,
                AGG,
                "refund.large",
                null,
                USER,
                ApprovalDecided.REJECTED,
                Ids.newId(),
                false,
                ApprovalValue.none()));
    FULL.put(
        ApprovalExpired.TYPE,
        () -> ApprovalExpired.payload(TENANT, AGG, "refund.large", STORE, USER));
    MINIMAL.put(
        ApprovalExpired.TYPE,
        () -> ApprovalExpired.payload(TENANT, AGG, "refund.large", null, USER));
    FULL.put(
        AuthorityRuleChanged.TYPE,
        () -> AuthorityRuleChanged.payload(TENANT, AGG, "refund.large", 4, USER));
    MINIMAL.put(
        AuthorityRuleChanged.TYPE,
        () -> AuthorityRuleChanged.payload(TENANT, AGG, "refund.large", 4, USER));
    FULL.put(
        SensitiveReportRead.TYPE,
        () ->
            SensitiveReportRead.payload(
                TENANT,
                AGG,
                USER,
                "MANAGER",
                "margin",
                "channel=POS",
                "2026-09-01",
                "2026-09-30",
                List.of(STORE, Ids.newId())));
    MINIMAL.put(
        SensitiveReportRead.TYPE,
        () ->
            SensitiveReportRead.payload(
                TENANT, AGG, USER, "OWNER", "valuation", null, null, null, null));
    FULL.put(
        SubjectErasureCompleted.TYPE,
        () ->
            SubjectErasureCompleted.payload(
                TENANT,
                SubjectErasureCompleted.KIND_CUSTOMER,
                AGG,
                "order-svc",
                Map.of("orders", new SubjectErasureCompleted.Counts(0, 4, 4))));
    MINIMAL.put(
        SubjectErasureCompleted.TYPE,
        () ->
            SubjectErasureCompleted.payload(
                null, SubjectErasureCompleted.KIND_SHOPPER_LOGIN, AGG, "cart-svc", Map.of()));
    FULL.put(
        LedgerPeriodClosed.TYPE,
        () ->
            LedgerPeriodClosed.payload(
                TENANT, AGG, LocalDate.parse("2026-09-01"), "Europe/Dublin", 1, USER, null));
    MINIMAL.put(
        LedgerPeriodClosed.TYPE,
        () ->
            LedgerPeriodClosed.payload(
                TENANT, AGG, LocalDate.parse("2026-09-01"), "Asia/Kolkata", 1, null, null));
    FULL.put(
        LedgerPeriodReopened.TYPE,
        () ->
            LedgerPeriodReopened.payload(
                TENANT, AGG, LocalDate.parse("2026-09-01"), "Europe/Dublin", 1, USER, Ids.newId()));
    MINIMAL.put(
        LedgerPeriodReopened.TYPE,
        () ->
            LedgerPeriodReopened.payload(
                TENANT, AGG, LocalDate.parse("2026-09-01"), "Europe/Dublin", 1, USER, null));
    FULL.put(
        LedgerPeriodLocked.TYPE,
        () ->
            LedgerPeriodLocked.payload(
                TENANT, AGG, LocalDate.parse("2026-09-01"), "Europe/Dublin", 2, USER, null));
    MINIMAL.put(
        LedgerPeriodLocked.TYPE,
        () ->
            LedgerPeriodLocked.payload(
                TENANT, AGG, LocalDate.parse("2026-09-01"), "Europe/Dublin", 2, null, null));
    FULL.put(
        VariantHandlingSet.TYPE,
        () ->
            VariantHandlingSet.payload(
                TENANT,
                Ids.newId(),
                AGG,
                VariantHandlingSet.BEST_BEFORE,
                "CHILLED",
                VariantHandlingSet.TRACKING_LOT_EXPIRY));
    MINIMAL.put(
        VariantHandlingSet.TYPE,
        () -> VariantHandlingSet.payload(TENANT, Ids.newId(), AGG, null, null, null));
    FULL.put(
        ZoneStatusChanged.TYPE,
        () ->
            ZoneStatusChanged.payload(
                TENANT,
                STORE,
                AGG,
                ZoneStatusChanged.ACTIVE,
                ZoneStatusChanged.OUT_OF_SERVICE,
                "FROZEN"));
    MINIMAL.put(
        ZoneStatusChanged.TYPE,
        () ->
            ZoneStatusChanged.payload(
                TENANT, STORE, AGG, ZoneStatusChanged.ACTIVE, ZoneStatusChanged.RETIRED, null));
    FULL.put(
        CustomersMerged.TYPE,
        () -> CustomersMerged.payload(TENANT, AGG, Ids.newId(), Ids.newId(), Ids.newId()));
    MINIMAL.put(
        CustomersMerged.TYPE,
        () -> CustomersMerged.payload(TENANT, AGG, Ids.newId(), Ids.newId(), null));
    FULL.put(
        PlatformActionRecorded.TYPE,
        () ->
            PlatformActionRecorded.payload(
                AGG,
                USER,
                "OPERATOR",
                "tenant.suspend",
                TENANT,
                "non-payment",
                Ids.newId(),
                "req-1"));
    MINIMAL.put(
        PlatformActionRecorded.TYPE,
        () ->
            PlatformActionRecorded.payload(
                AGG, USER, "SUPPORT", "session.revoke", null, null, null, null));
  }

  @Test
  void everyEventClassIsOnTheCatalogueWithASample() throws Exception {
    Set<String> declared = new HashSet<>();
    File dir =
        new File(EventContracts.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    File pkg = new File(dir, EventContracts.class.getPackageName().replace('.', '/'));
    for (String f : pkg.list()) {
      if (!f.endsWith(".class") || f.contains("$")) {
        continue;
      }
      Class<?> c =
          Class.forName(EventContracts.class.getPackageName() + "." + f.replace(".class", ""));
      try {
        declared.add((String) c.getField("TYPE").get(null));
      } catch (NoSuchFieldException absent) {
        // not an event class
      }
    }
    Set<String> catalogued = new HashSet<>();
    EventContracts.ALL.forEach(c -> catalogued.add(c.type()));
    assertEquals(declared, catalogued, "an event class with a TYPE must be on EventContracts.ALL");
    assertEquals(catalogued, FULL.keySet(), "every catalogued type needs a full sample here");
    assertEquals(catalogued, MINIMAL.keySet(), "every catalogued type needs a minimal sample here");
    for (EventContract c : EventContracts.ALL) {
      assertTrue(c.reader() != null && EventContracts.byType(c.type()).isPresent());
    }
  }

  @Test
  void everyPayloadCarriesAV7EventIdAndTheEnvelopeAndRoundTrips() {
    for (EventContract c : EventContracts.ALL) {
      for (Supplier<String> s : List.of(FULL.get(c.type()), MINIMAL.get(c.type()))) {
        String json = s.get();
        UUID eventId =
            EventContracts.eventIdOf(json).orElseThrow(() -> new AssertionError(c.type()));
        assertTrue(Ids.isV7(eventId), c.type());
        Envelope e = envelopeOf(c.read(json));
        assertEquals(eventId, e.eventId());
        assertEquals(c.type(), e.eventType());
        assertTrue(e.occurredAt().isBefore(Instant.now().plusSeconds(5)));
        switch (c.tenantScope()) {
          case REQUIRED -> assertEquals(TENANT, e.requireTenant(), c.type());
          case NONE -> assertTrue(e.tenantId().isEmpty(), c.type());
          case OPTIONAL -> {
            assertFalse(json.contains("\"tenantId\":\"null\""), c.type());
          }
        }
      }
    }
  }

  @Test
  void eachPayloadIsAFreshEvent() {
    for (EventContract c : EventContracts.ALL) {
      assertFalse(
          EventContracts.eventIdOf(FULL.get(c.type()).get())
              .equals(EventContracts.eventIdOf(FULL.get(c.type()).get())),
          c.type());
    }
  }

  @Test
  void anUnknownMemberIsIgnored() {
    for (EventContract c : EventContracts.ALL) {
      String json = FULL.get(c.type()).get();
      String withExtra = json.replaceFirst("\\{", "{\"zzNew\":{\"a\":[1,2]},\"zzAlso\":\"x\",");
      assertEquals(c.read(json), c.read(withExtra), c.type());
    }
  }

  @Test
  void aWrongTypeOrAMissingRequiredMemberIsRefused() {
    String zone = FULL.get(ZoneStatusChanged.TYPE).get();
    assertThrows(IllegalArgumentException.class, () -> CustomersMerged.read(zone));
    assertThrows(
        IllegalArgumentException.class,
        () -> ZoneStatusChanged.read(zone.replace("\"newStatus\"", "\"other\"")));
    assertThrows(
        IllegalArgumentException.class,
        () -> ZoneStatusChanged.read(zone.replaceFirst("\"tenantId\":\"[^\"]*\",", "")));
    assertThrows(IllegalArgumentException.class, () -> ZoneStatusChanged.read("not json"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ZoneStatusChanged.read(
                zone.replaceFirst(
                    "\"eventId\":\"[^\"]*\"",
                    "\"eventId\":\"11111111-1111-1111-1111-111111111111\"")));
  }

  @Test
  void aBuilderRefusesAMissingRequiredArgument() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ZoneStatusChanged.payload(TENANT, STORE, AGG, "ACTIVE", null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SubjectErasureCompleted.payload(
                null, SubjectErasureCompleted.KIND_CUSTOMER, AGG, "order-svc", Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ApprovalRequested.payload(
                null,
                AGG,
                "a",
                null,
                "T",
                AGG,
                USER,
                null,
                ApprovalValue.none(),
                "p",
                1,
                null,
                "BUSINESS",
                null));
  }

  @Test
  void optionalMembersReadAsEmptyAndValuesSurvive() {
    var alert = ExceptionAlertRaised.read(MINIMAL.get(ExceptionAlertRaised.TYPE).get());
    assertEquals(Optional.empty(), alert.storeId());
    assertEquals(Optional.empty(), alert.currency());
    assertTrue(alert.evidence().isEmpty());
    var full = ExceptionAlertRaised.read(FULL.get(ExceptionAlertRaised.TYPE).get());
    assertEquals(STORE, full.storeId().orElseThrow());
    assertEquals(0, new BigDecimal("7.5").compareTo(full.observed()));
    assertEquals("EUR", full.currency().orElseThrow());
    assertEquals(1, full.evidence().size());

    var req = ApprovalRequested.read(MINIMAL.get(ApprovalRequested.TYPE).get());
    assertTrue(req.envelope().tenantId().isEmpty());
    assertEquals(ApprovalRequested.SCOPE_PLATFORM, req.scope());
    assertEquals(ApprovalValue.none(), req.value());
    var reqFull = ApprovalRequested.read(FULL.get(ApprovalRequested.TYPE).get());
    assertEquals(0, new BigDecimal("120.5").compareTo(reqFull.value().amount().orElseThrow()));
    assertEquals(2, reqFull.approvalsNeeded());
    assertEquals(Instant.parse("2026-10-01T10:00:00Z"), reqFull.expiresAt().orElseThrow());

    var vh = VariantHandlingSet.read(MINIMAL.get(VariantHandlingSet.TYPE).get());
    assertEquals(Optional.empty(), vh.dateKind());
    assertEquals(VariantHandlingSet.USE_BY, vh.effectiveDateKind());
    assertEquals(VariantHandlingSet.TRACKING_NONE, vh.tracking());

    var led = LedgerPeriodClosed.read(MINIMAL.get(LedgerPeriodClosed.TYPE).get());
    assertEquals(LocalDate.parse("2026-09-01"), led.month());
    assertEquals(Optional.empty(), led.by());
    assertEquals(Optional.empty(), led.approvalId());

    var sr = SensitiveReportRead.read(MINIMAL.get(SensitiveReportRead.TYPE).get());
    assertTrue(sr.stores().isEmpty());
    assertEquals(Optional.empty(), sr.filters());

    var er = SubjectErasureCompleted.read(FULL.get(SubjectErasureCompleted.TYPE).get());
    assertEquals(new SubjectErasureCompleted.Counts(0, 4, 4), er.counts().get("orders"));
    assertTrue(
        SubjectErasureCompleted.read(MINIMAL.get(SubjectErasureCompleted.TYPE).get())
            .envelope()
            .tenantId()
            .isEmpty());

    var pa = PlatformActionRecorded.read(MINIMAL.get(PlatformActionRecorded.TYPE).get());
    assertEquals(Optional.empty(), pa.targetTenantId());
    assertEquals(Optional.empty(), pa.reason());
    assertEquals(
        TENANT,
        PlatformActionRecorded.read(FULL.get(PlatformActionRecorded.TYPE).get())
            .targetTenantId()
            .orElseThrow());
  }

  @Test
  void aJsonNullReadsAsEmpty() {
    String json =
        FULL.get(ZoneStatusChanged.TYPE)
            .get()
            .replace("\"storageClass\":\"FROZEN\"", "\"storageClass\":null");
    assertEquals(Optional.empty(), ZoneStatusChanged.read(json).storageClass());
  }

  @Test
  void aStringWithQuotesAndControlCharactersSurvives() {
    String json =
        SensitiveReportRead.payload(
            TENANT, AGG, USER, "MANAGER", "margin", "q=\"a\\b\"\n\tz", null, null, null);
    assertEquals("q=\"a\\b\"\n\tz", SensitiveReportRead.read(json).filters().orElseThrow());
  }

  private static final Pattern TOPIC = Pattern.compile("^storeql\\.[a-z]+\\.[a-z]+(-[a-z]+)*$");

  /** Where the page names a topic that is not the kebab-case of the type, the page wins. */
  private static final Map<String, String> TOPIC_EXCEPTIONS =
      Map.of(
          PlatformActionRecorded.TYPE,
          "storeql.platform.action-recorded",
          ApprovalRequested.TYPE,
          "storeql.approvals.requested",
          ApprovalDecided.TYPE,
          "storeql.approvals.decided",
          ApprovalExpired.TYPE,
          "storeql.approvals.expired",
          SensitiveReportRead.TYPE,
          "storeql.inventory.report-read");

  @Test
  void topicsFollowTheNamingRule() {
    for (EventContract c : EventContracts.ALL) {
      String topic = c.perService() ? c.topicFor("inventory") : c.topic();
      assertTrue(TOPIC.matcher(topic).matches(), topic);
      assertFalse(topic.contains("{"), topic);
      String kebab = c.type().replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase();
      String expected = TOPIC_EXCEPTIONS.getOrDefault(c.type(), null);
      if (expected != null) {
        assertEquals(expected, topic);
      } else {
        assertTrue(topic.endsWith("." + kebab), topic + " vs " + kebab);
      }
    }
    assertEquals("storeql.pricing.report-read", SensitiveReportRead.topicFor("pricing"));
    assertEquals("storeql.iam.subject-erasure-completed", SubjectErasureCompleted.topicFor("iam"));
    assertThrows(IllegalStateException.class, SensitiveReportRead.CONTRACT::topic);
  }

  @Test
  void eventIdOfRefusesWhatIsNotAV7Id() {
    assertEquals(Optional.empty(), EventContracts.eventIdOf("{\"eventType\":\"X\"}"));
    assertEquals(Optional.empty(), EventContracts.eventIdOf("garbage"));
    assertEquals(
        Optional.empty(),
        EventContracts.eventIdOf("{\"eventId\":\"11111111-1111-1111-1111-111111111111\"}"));
  }

  private static Envelope envelopeOf(Object read) {
    try {
      return (Envelope) read.getClass().getMethod("envelope").invoke(read);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }
}
