package com.storeql.tenant.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.tenant.domain.Broadcasts;
import com.storeql.tenant.domain.Broadcasts.Broadcast;
import com.storeql.tenant.domain.Broadcasts.Reach;
import com.storeql.tenant.domain.StoreTasks;
import com.storeql.tenant.domain.StoreTasks.Instance;
import com.storeql.tenant.domain.StoreTasks.Template;
import com.storeql.tenant.dto.BroadcastDtos;
import com.storeql.tenant.dto.StoreTaskDtos;
import com.storeql.tenant.service.BroadcastService;
import com.storeql.tenant.service.StoreTaskService;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Notices and task lists under {@code /admin/workforce} are held to the caller's stores as the
 * roster is: one store's notice or list is read and changed at that store, one for every store is
 * read by anybody and changed only by a caller held to none, and the lists of them are the caller's
 * stores' own plus the every-store ones.
 *
 * <p>The real {@link TenantContext} decides; the services are stubs that record what reached them.
 */
class NoticeAndTaskHoldTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID A = Ids.newId();
  private static final UUID B = Ids.newId();

  private static TenantContext caller(String role, Set<UUID> heldTo) {
    TenantContext ctx = new TenantContext();
    try {
      Method set =
          TenantContext.class.getDeclaredMethod(
              "set", UUID.class, UUID.class, Set.class, Set.class, String.class);
      set.setAccessible(true);
      set.invoke(ctx, TENANT, Ids.newId(), Set.of(role), heldTo, "req");
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("could not populate a TenantContext", e);
    }
    return ctx;
  }

  private static void refused(ApiException e, String code) {
    assertEquals(403, e.status());
    assertEquals(code, e.code());
  }

  // ── notices ────────────────────────────────────────────────────────────────

  private static Broadcast notice(UUID id, UUID storeId) {
    return new Broadcast(
        id,
        TENANT,
        "Recall",
        "Batch 42 off the shelf.",
        "URGENT",
        storeId,
        null,
        true,
        Instant.now(),
        null,
        Broadcasts.PUBLISHED,
        Ids.newId(),
        null,
        null,
        null);
  }

  /** The business's notices by id, and what reached the service. */
  private static final class Notices extends BroadcastService {
    final Map<UUID, Broadcast> byId = new HashMap<>();
    final List<String> acted = new ArrayList<>();
    final List<Set<UUID>> heldTo = new ArrayList<>();

    UUID add(UUID storeId) {
      UUID id = Ids.newId();
      byId.put(id, notice(id, storeId));
      return id;
    }

    @Override
    public UUID requireStore(UUID tenantId, UUID storeId) {
      if (TENANT.equals(tenantId) && (A.equals(storeId) || B.equals(storeId))) return storeId;
      throw ApiException.notFound("STORE_NOT_FOUND", "no such store");
    }

    @Override
    public Broadcast broadcast(UUID tenantId, UUID id) {
      assertEquals(TENANT, tenantId);
      Broadcast b = byId.get(id);
      if (b == null) throw ApiException.notFound("BROADCAST_NOT_FOUND", "no such notice");
      return b;
    }

    @Override
    public List<Broadcast> broadcasts(
        UUID tenantId, boolean publishedOnly, Integer limit, Set<UUID> stores) {
      heldTo.add(stores);
      return List.of();
    }

    @Override
    public List<Reach> reach(UUID tenantId, UUID id, Set<UUID> stores) {
      broadcast(tenantId, id);
      acted.add("reach " + id);
      heldTo.add(stores);
      return List.of();
    }

    @Override
    public Broadcast withdraw(UUID tenantId, UUID id, String reason, UUID actorId) {
      acted.add("withdraw " + id);
      return broadcast(tenantId, id);
    }

    @Override
    public Broadcast publish(
        UUID tenantId,
        String title,
        String body,
        String priority,
        UUID storeId,
        String role,
        boolean requiresAck,
        Instant expiresAt,
        UUID actorId) {
      acted.add("publish " + storeId);
      return notice(Ids.newId(), storeId);
    }
  }

  private static BroadcastResource notices(TenantContext ctx, Notices svc) {
    BroadcastResource r = new BroadcastResource();
    r.ctx = ctx;
    r.svc = svc;
    return r;
  }

  private static BroadcastDtos.PublishRequest to(UUID storeId) {
    return new BroadcastDtos.PublishRequest(
        "Recall",
        "Batch 42 off the shelf.",
        "URGENT",
        storeId == null ? null : storeId.toString(),
        null,
        true,
        null);
  }

  private static final BroadcastDtos.WithdrawRequest WRONG_BATCH =
      new BroadcastDtos.WithdrawRequest("wrong batch");

  @Test
  @DisplayName("A branch manager's notices: their store's and every store's, never another's")
  void aStoreHeldManagerReadsAndChangesOnlyTheirNotices() {
    Notices svc = new Notices();
    UUID atA = svc.add(A);
    UUID atB = svc.add(B);
    UUID everywhere = svc.add(null);
    BroadcastResource r = notices(caller("MANAGER", Set.of(A)), svc);

    // Publishing: their store, never another's, never every store.
    r.publish(to(A));
    refused(assertThrows(ApiException.class, () -> r.publish(to(B))), "STORE_ACCESS_DENIED");
    refused(assertThrows(ApiException.class, () -> r.publish(to(null))), "BUSINESS_WIDE_ONLY");
    assertEquals(List.of("publish " + A), svc.acted);

    // Reading one, and its reach: their store's and every store's (the reach held to theirs).
    svc.acted.clear();
    r.one(atA);
    r.one(everywhere);
    r.reach(atA);
    r.reach(everywhere);
    assertEquals(List.of(Set.of(A), Set.of(A)), svc.heldTo, "reach names only their stores");
    refused(assertThrows(ApiException.class, () -> r.one(atB)), "STORE_ACCESS_DENIED");
    refused(assertThrows(ApiException.class, () -> r.reach(atB)), "STORE_ACCESS_DENIED");
    assertEquals(List.of("reach " + atA, "reach " + everywhere), svc.acted);

    // Withdrawing: their store's; another store's and every store's are refused, unmoved.
    svc.acted.clear();
    refused(
        assertThrows(ApiException.class, () -> r.withdraw(atB, WRONG_BATCH)),
        "STORE_ACCESS_DENIED");
    refused(
        assertThrows(ApiException.class, () -> r.withdraw(everywhere, WRONG_BATCH)),
        "BUSINESS_WIDE_ONLY");
    assertTrue(svc.acted.isEmpty(), "nothing was withdrawn");
    r.withdraw(atA, WRONG_BATCH);
    assertEquals(List.of("withdraw " + atA), svc.acted);

    // The list is held to their stores in the read itself.
    svc.heldTo.clear();
    r.list(null, null);
    assertEquals(List.of(Set.of(A)), svc.heldTo);
  }

  @Test
  @DisplayName("A caller held to no store reads and changes every notice of the business")
  void aCallerHeldToNoneReadsAndChangesEveryNotice() {
    for (String role : List.of("OWNER", "MANAGER")) {
      Notices svc = new Notices();
      UUID atB = svc.add(B);
      UUID everywhere = svc.add(null);
      BroadcastResource r = notices(caller(role, Set.of()), svc);
      r.publish(to(null));
      r.publish(to(B));
      r.one(atB);
      r.reach(atB);
      r.withdraw(atB, WRONG_BATCH);
      r.withdraw(everywhere, WRONG_BATCH);
      r.list(null, null);
      assertEquals(
          List.of(
              "publish null",
              "publish " + B,
              "reach " + atB,
              "withdraw " + atB,
              "withdraw " + everywhere),
          svc.acted,
          role);
      assertNull(svc.heldTo.get(0), role + ": the reach of every store");
      assertNull(svc.heldTo.get(1), role + ": every notice");
    }
  }

  @Test
  @DisplayName("A notice that is not the business's is not found, before any store is judged")
  void anUnknownNoticeIsNotFound() {
    Notices svc = new Notices();
    BroadcastResource r = notices(caller("MANAGER", Set.of(A)), svc);
    UUID theirs = Ids.newId();
    assertEquals(404, assertThrows(ApiException.class, () -> r.one(theirs)).status());
    assertEquals(404, assertThrows(ApiException.class, () -> r.reach(theirs)).status());
    assertEquals(
        404, assertThrows(ApiException.class, () -> r.withdraw(theirs, WRONG_BATCH)).status());
    assertTrue(svc.acted.isEmpty());
  }

  // ── task lists ─────────────────────────────────────────────────────────────

  private static Template list(UUID id, UUID storeId) {
    return new Template(
        id,
        TENANT,
        storeId,
        "Open up",
        null,
        StoreTasks.OPENING,
        Set.of(),
        LocalTime.of(8, 0),
        60,
        null,
        true,
        StoreTasks.ACTIVE,
        Instant.now(),
        Ids.newId(),
        null,
        null,
        List.of());
  }

  private static Instance task(UUID id, UUID storeId) {
    return new Instance(
        id,
        TENANT,
        storeId,
        Ids.newId(),
        LocalDate.now(),
        Instant.now(),
        StoreTasks.OPEN,
        "Open up",
        StoreTasks.OPENING,
        null,
        true,
        null,
        null,
        null,
        null,
        Instant.now(),
        List.of());
  }

  private static final class Lists extends StoreTaskService {
    final Map<UUID, Template> lists = new HashMap<>();
    final Map<UUID, Instance> tasks = new HashMap<>();
    final List<String> acted = new ArrayList<>();
    final List<Set<UUID>> heldTo = new ArrayList<>();

    UUID addList(UUID storeId) {
      UUID id = Ids.newId();
      lists.put(id, list(id, storeId));
      return id;
    }

    UUID addTask(UUID storeId) {
      UUID id = Ids.newId();
      tasks.put(id, task(id, storeId));
      return id;
    }

    @Override
    public UUID requireStore(UUID tenantId, UUID storeId) {
      if (TENANT.equals(tenantId) && (A.equals(storeId) || B.equals(storeId))) return storeId;
      throw ApiException.notFound("STORE_NOT_FOUND", "no such store");
    }

    @Override
    public Template template(UUID tenantId, UUID id) {
      assertEquals(TENANT, tenantId);
      Template t = lists.get(id);
      if (t == null) throw ApiException.notFound("TASK_LIST_NOT_FOUND", "no such list");
      return t;
    }

    @Override
    public List<Template> templates(UUID tenantId, boolean activeOnly, Set<UUID> stores) {
      heldTo.add(stores);
      return List.of();
    }

    @Override
    public Template withdraw(UUID tenantId, UUID templateId, UUID actorId) {
      acted.add("withdraw " + templateId);
      return template(tenantId, templateId);
    }

    @Override
    public Instance instance(UUID tenantId, UUID id) {
      Instance i = tasks.get(id);
      if (i == null) throw ApiException.notFound("TASK_NOT_FOUND", "no such task");
      return i;
    }

    @Override
    public Template create(
        UUID tenantId,
        UUID storeId,
        String title,
        String instructions,
        String kind,
        Set<Integer> daysOfWeek,
        LocalTime dueTime,
        Integer graceMinutes,
        String role,
        boolean required,
        List<Line> lines,
        UUID actorId) {
      acted.add("create " + storeId);
      return list(Ids.newId(), storeId);
    }
  }

  private static StoreTaskResource lists(TenantContext ctx, Lists svc) {
    StoreTaskResource r = new StoreTaskResource();
    r.ctx = ctx;
    r.svc = svc;
    return r;
  }

  private static StoreTaskDtos.TemplateRequest openingAt(UUID storeId) {
    return new StoreTaskDtos.TemplateRequest(
        "Open up",
        null,
        "OPENING",
        storeId == null ? null : storeId.toString(),
        null,
        "08:00",
        null,
        null,
        null,
        null);
  }

  @Test
  @DisplayName("A branch manager's lists: their store's and every store's, never another's")
  void aStoreHeldManagerReadsAndChangesOnlyTheirLists() {
    Lists svc = new Lists();
    UUID atA = svc.addList(A);
    UUID atB = svc.addList(B);
    UUID everywhere = svc.addList(null);
    UUID taskAtA = svc.addTask(A);
    UUID taskAtB = svc.addTask(B);
    StoreTaskResource r = lists(caller("MANAGER", Set.of(A)), svc);

    r.create(openingAt(A));
    refused(assertThrows(ApiException.class, () -> r.create(openingAt(B))), "STORE_ACCESS_DENIED");
    refused(
        assertThrows(ApiException.class, () -> r.create(openingAt(null))), "BUSINESS_WIDE_ONLY");
    assertEquals(List.of("create " + A), svc.acted);

    r.list(atA);
    r.list(everywhere);
    refused(assertThrows(ApiException.class, () -> r.list(atB)), "STORE_ACCESS_DENIED");
    r.task(taskAtA);
    refused(assertThrows(ApiException.class, () -> r.task(taskAtB)), "STORE_ACCESS_DENIED");

    svc.acted.clear();
    refused(assertThrows(ApiException.class, () -> r.withdraw(atB)), "STORE_ACCESS_DENIED");
    refused(assertThrows(ApiException.class, () -> r.withdraw(everywhere)), "BUSINESS_WIDE_ONLY");
    assertTrue(svc.acted.isEmpty(), "nothing was withdrawn");
    r.withdraw(atA);
    assertEquals(List.of("withdraw " + atA), svc.acted);

    r.lists(null);
    assertEquals(List.of(Set.of(A)), svc.heldTo, "the list is held to their stores");
  }

  @Test
  @DisplayName("A caller held to no store reads and changes every list of the business")
  void aCallerHeldToNoneReadsAndChangesEveryList() {
    for (String role : List.of("OWNER", "MANAGER")) {
      Lists svc = new Lists();
      UUID atB = svc.addList(B);
      UUID everywhere = svc.addList(null);
      UUID taskAtB = svc.addTask(B);
      StoreTaskResource r = lists(caller(role, Set.of()), svc);
      r.create(openingAt(null));
      r.create(openingAt(B));
      r.list(atB);
      r.task(taskAtB);
      r.withdraw(atB);
      r.withdraw(everywhere);
      r.lists(null);
      assertEquals(
          List.of("create null", "create " + B, "withdraw " + atB, "withdraw " + everywhere),
          svc.acted,
          role);
      assertNull(svc.heldTo.get(0), role + ": every list");
    }
  }

  @Test
  @DisplayName("A list or task that is not the business's is not found, before any store is judged")
  void anUnknownListOrTaskIsNotFound() {
    Lists svc = new Lists();
    StoreTaskResource r = lists(caller("MANAGER", Set.of(A)), svc);
    UUID theirs = Ids.newId();
    assertEquals(404, assertThrows(ApiException.class, () -> r.list(theirs)).status());
    assertEquals(404, assertThrows(ApiException.class, () -> r.withdraw(theirs)).status());
    assertEquals(404, assertThrows(ApiException.class, () -> r.task(theirs)).status());
    assertTrue(svc.acted.isEmpty());
  }
}
