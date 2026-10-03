package com.storeql.tenant.repo;

import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxRow;
import com.storeql.tenant.domain.StoreTasks;
import com.storeql.tenant.domain.StoreTasks.Instance;
import com.storeql.tenant.domain.StoreTasks.InstanceItem;
import com.storeql.tenant.domain.StoreTasks.Template;
import com.storeql.tenant.domain.StoreTasks.TemplateItem;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The work a shop does every day, and the record that it was done (store operations & workforce).
 *
 * <p>Two things here are the database's to enforce. <b>One occurrence per list per store per
 * day</b> is a unique constraint, because the sweeper and a manager generating a day by hand can
 * both be running, and a day generated twice would double every report and let one job be signed
 * off by two people. And a list's text is <b>copied onto the occurrence</b> when the day is
 * generated, so editing the list tomorrow does not rewrite what somebody did today.
 */
@ApplicationScoped
public class StoreTaskRepository extends BaseOutboxRepository {

  private static final String TEMPLATE_COLUMNS =
      "id, tenant_id, store_id, title, instructions, kind, days_of_week, due_time, grace_minutes,"
          + " role, required, status, created_at, created_by, withdrawn_at, withdrawn_by";

  private static final String TEMPLATE_ITEM_COLUMNS = "id, template_id, position, text, required";

  private static final String INSTANCE_COLUMNS =
      "id, tenant_id, store_id, template_id, business_date, due_at, status, title, kind, role,"
          + " required, completed_at, completed_by, skipped_reason, note, created_at";

  /**
   * The same columns qualified for a join, spelled out: a statement built from a method call on a
   * constant is one SpotBugs refuses, and rightly — only a constant provably carries no input.
   */
  private static final String INSTANCE_COLUMNS_QUALIFIED =
      "i.id, i.tenant_id, i.store_id, i.template_id, i.business_date, i.due_at, i.status, i.title,"
          + " i.kind, i.role, i.required, i.completed_at, i.completed_by, i.skipped_reason, i.note,"
          + " i.created_at";

  private static final String INSTANCE_ITEM_COLUMNS =
      "id, instance_id, position, text, required, ticked_at, ticked_by";

  /** A store and the clock its day is read on, for the sweeper. */
  public record StoreClock(UUID storeId, String timezone) {}

  // ── the lists ───────────────────────────────────────────────────────────────

  /**
   * Writes a list with its lines in one transaction: a half-written checklist is worse than none.
   */
  public Template create(Template t) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO task_templates ("
                      + TEMPLATE_COLUMNS
                      + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, t.id());
            ps.setObject(2, t.tenantId());
            ps.setObject(3, t.storeId());
            ps.setString(4, t.title());
            ps.setString(5, t.instructions());
            ps.setString(6, t.kind());
            ps.setArray(7, days(c, t.daysOfWeek()));
            ps.setObject(8, t.dueTime());
            ps.setInt(9, t.graceMinutes());
            ps.setString(10, t.role());
            ps.setBoolean(11, t.required());
            ps.setString(12, t.status());
            ps.setObject(13, t.createdAt().atOffset(ZoneOffset.UTC));
            ps.setObject(14, t.createdBy());
            ps.setObject(15, null);
            ps.setObject(16, null);
            ps.executeUpdate();
          }
          insertTemplateItems(c, t);
          return t;
        },
        "record a task list");
  }

  private static void insertTemplateItems(Connection c, Template t) throws SQLException {
    if (t.items().isEmpty()) return;
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO task_template_items ("
                + TEMPLATE_ITEM_COLUMNS
                + ", tenant_id) VALUES (?,?,?,?,?,?)")) {
      for (TemplateItem i : t.items()) {
        ps.setObject(1, i.id());
        ps.setObject(2, t.id());
        ps.setInt(3, i.position());
        ps.setString(4, i.text());
        ps.setBoolean(5, i.required());
        ps.setObject(6, t.tenantId());
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  /**
   * Withdraws a list; false when it was not active. Occurrences already generated are left alone.
   */
  public boolean withdraw(UUID tenantId, UUID templateId, UUID actorId) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE task_templates SET status = ?, withdrawn_at = ?, withdrawn_by = ?"
                      + " WHERE tenant_id = ? AND id = ? AND status = ?")) {
            ps.setString(1, StoreTasks.WITHDRAWN);
            ps.setObject(2, OffsetDateTime.now(ZoneOffset.UTC));
            ps.setObject(3, actorId);
            ps.setObject(4, tenantId);
            ps.setObject(5, templateId);
            ps.setString(6, StoreTasks.ACTIVE);
            return ps.executeUpdate() > 0;
          }
        },
        "withdraw a task list");
  }

  public Optional<Template> template(UUID tenantId, UUID id) {
    List<Template> found =
        query(
            "SELECT " + TEMPLATE_COLUMNS + " FROM task_templates WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            StoreTaskRepository::mapTemplate,
            "read a task list");
    return withTemplateItems(tenantId, found).stream().findFirst();
  }

  /** The lists a business keeps, active ones first and newest within that. */
  public List<Template> templates(UUID tenantId, boolean activeOnly) {
    return templates(tenantId, activeOnly, null);
  }

  /**
   * The business's lists, held to stores: a store's own lists and those for every store (which fall
   * due at the caller's stores too).
   *
   * @param stores the caller's stores, or null for every list of the business
   */
  public List<Template> templates(UUID tenantId, boolean activeOnly, Set<UUID> stores) {
    String sql =
        "SELECT "
            + TEMPLATE_COLUMNS
            + " FROM task_templates WHERE tenant_id = ?"
            + (activeOnly ? " AND status = 'ACTIVE'" : "")
            + (stores == null ? "" : " AND (store_id IS NULL OR store_id = ANY(?))")
            + " ORDER BY status, kind, due_time, created_at DESC";
    return withTemplateItems(
        tenantId,
        query(
            sql,
            ps -> {
              ps.setObject(1, tenantId);
              if (stores != null) {
                ps.setArray(2, ps.getConnection().createArrayOf("uuid", stores.toArray()));
              }
            },
            StoreTaskRepository::mapTemplate,
            "list task lists"));
  }

  /** Every tenant with an active list: whom the sweeper works for. */
  public List<UUID> tenantsWithLists() {
    return query(
        "SELECT DISTINCT tenant_id FROM task_templates WHERE status = 'ACTIVE' ORDER BY tenant_id",
        ps -> {},
        rs -> rs.getObject("tenant_id", UUID.class),
        "tenants with task lists");
  }

  /** A business's open stores and the clock each one's day is read on. */
  public List<StoreClock> storeClocks(UUID tenantId) {
    return query(
        "SELECT id, timezone FROM stores WHERE tenant_id = ? AND status = 'ACTIVE' ORDER BY id",
        ps -> ps.setObject(1, tenantId),
        rs -> new StoreClock(rs.getObject("id", UUID.class), rs.getString("timezone")),
        "stores and their clocks");
  }

  /** One store's clock, for a day raised by hand. */
  public Optional<StoreClock> storeClock(UUID tenantId, UUID storeId) {
    return query(
            "SELECT id, timezone FROM stores WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, storeId);
            },
            rs -> new StoreClock(rs.getObject("id", UUID.class), rs.getString("timezone")),
            "a store's clock")
        .stream()
        .findFirst();
  }

  // ── the day ─────────────────────────────────────────────────────────────────

  /**
   * Generates an occurrence with its lines, or leaves the one already there.
   *
   * <p>The unique constraint decides: two sweeps, or a sweep and a manager, generating the same day
   * must produce one occurrence, and only the database can promise that under concurrency.
   *
   * @return true when this call created it
   */
  public boolean generate(Instance instance) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO task_instances ("
                      + INSTANCE_COLUMNS
                      + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                      + " ON CONFLICT ON CONSTRAINT uq_task_instance_day DO NOTHING")) {
            ps.setObject(1, instance.id());
            ps.setObject(2, instance.tenantId());
            ps.setObject(3, instance.storeId());
            ps.setObject(4, instance.templateId());
            ps.setObject(5, instance.businessDate());
            ps.setObject(6, instance.dueAt().atOffset(ZoneOffset.UTC));
            ps.setString(7, instance.status());
            ps.setString(8, instance.title());
            ps.setString(9, instance.kind());
            ps.setString(10, instance.role());
            ps.setBoolean(11, instance.required());
            ps.setObject(12, null);
            ps.setObject(13, null);
            ps.setString(14, null);
            ps.setString(15, null);
            ps.setObject(16, instance.createdAt().atOffset(ZoneOffset.UTC));
            if (ps.executeUpdate() == 0) return false;
          }
          if (!instance.items().isEmpty()) {
            try (PreparedStatement ps =
                c.prepareStatement(
                    "INSERT INTO task_instance_items ("
                        + INSTANCE_ITEM_COLUMNS
                        + ", tenant_id) VALUES (?,?,?,?,?,?,?,?)")) {
              for (InstanceItem i : instance.items()) {
                ps.setObject(1, i.id());
                ps.setObject(2, instance.id());
                ps.setInt(3, i.position());
                ps.setString(4, i.text());
                ps.setBoolean(5, i.required());
                ps.setObject(6, null);
                ps.setObject(7, null);
                ps.setObject(8, instance.tenantId());
                ps.addBatch();
              }
              ps.executeBatch();
            }
          }
          return true;
        },
        "generate a day's task");
  }

  public Optional<Instance> instance(UUID tenantId, UUID id) {
    List<Instance> found =
        query(
            "SELECT " + INSTANCE_COLUMNS + " FROM task_instances WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            StoreTaskRepository::mapInstance,
            "read a task");
    return withInstanceItems(tenantId, found).stream().findFirst();
  }

  /** A store's occurrences over a range of business dates, in the order they fall due. */
  public List<Instance> day(UUID tenantId, UUID storeId, LocalDate from, LocalDate to) {
    return withInstanceItems(
        tenantId,
        query(
            "SELECT "
                + INSTANCE_COLUMNS
                + " FROM task_instances"
                + " WHERE tenant_id = ? AND store_id = ? AND business_date >= ? AND business_date <= ?"
                + " ORDER BY business_date, due_at, title",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, storeId);
              ps.setObject(3, from);
              ps.setObject(4, to);
            },
            StoreTaskRepository::mapInstance,
            "read a store's tasks"));
  }

  /** Ticks one line of a checklist; false when the line was not there or already ticked. */
  public boolean tick(UUID tenantId, UUID instanceId, int position, UUID userId, Instant at) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE task_instance_items SET ticked_at = ?, ticked_by = ?"
                      + " WHERE tenant_id = ? AND instance_id = ? AND position = ?"
                      + " AND ticked_at IS NULL")) {
            ps.setObject(1, at.atOffset(ZoneOffset.UTC));
            ps.setObject(2, userId);
            ps.setObject(3, tenantId);
            ps.setObject(4, instanceId);
            ps.setInt(5, position);
            return ps.executeUpdate() > 0;
          }
        },
        "tick a checklist line");
  }

  /**
   * Settles an open occurrence as done or skipped.
   *
   * <p>Guarded on {@code status = 'OPEN'} in the statement itself, so two people finishing the same
   * job at once settle it once: the second update touches no row and is told so.
   */
  public boolean settle(
      UUID tenantId,
      UUID instanceId,
      String status,
      UUID userId,
      Instant at,
      String skippedReason,
      String note) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE task_instances SET status = ?, completed_at = ?, completed_by = ?,"
                      + " skipped_reason = ?, note = COALESCE(?, note)"
                      + " WHERE tenant_id = ? AND id = ? AND status = 'OPEN'")) {
            ps.setString(1, status);
            ps.setObject(2, at.atOffset(ZoneOffset.UTC));
            ps.setObject(3, userId);
            ps.setString(4, skippedReason);
            ps.setString(5, note);
            ps.setObject(6, tenantId);
            ps.setObject(7, instanceId);
            return ps.executeUpdate() > 0;
          }
        },
        "settle a task");
  }

  /**
   * Marks the open occurrences past their grace as missed, announcing each one.
   *
   * <p>The announcement and the mark are one transaction: a missed closing check that was marked
   * but never announced would be the manager's alert lost, and one announced but not marked would
   * be announced again every sweep.
   *
   * @param announce what to put on the outbox for each occurrence marked
   * @return the occurrences marked
   */
  public List<Instance> markMissed(
      Instant now, java.util.function.Function<Instance, OutboxRow> announce) {
    List<Instance> due =
        query(
            "SELECT "
                + INSTANCE_COLUMNS_QUALIFIED
                + " FROM task_instances i JOIN task_templates t ON t.id = i.template_id"
                + " WHERE i.status = 'OPEN' AND i.due_at + make_interval(mins => t.grace_minutes) < ?"
                + " ORDER BY i.due_at",
            ps -> ps.setObject(1, now.atOffset(ZoneOffset.UTC)),
            StoreTaskRepository::mapInstance,
            "find tasks past their grace");
    if (due.isEmpty()) return List.of();
    return inTx(
        c -> {
          List<Instance> marked = new ArrayList<>();
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE task_instances SET status = ? WHERE tenant_id = ? AND id = ?"
                      + " AND status = 'OPEN'")) {
            for (Instance i : due) {
              ps.setString(1, StoreTasks.MISSED);
              ps.setObject(2, i.tenantId());
              ps.setObject(3, i.id());
              if (ps.executeUpdate() > 0) {
                insertOutbox(c, announce.apply(i));
                marked.add(i);
              }
            }
          }
          return List.copyOf(marked);
        },
        "mark tasks missed");
  }

  // ── plumbing ────────────────────────────────────────────────────────────────

  private List<Template> withTemplateItems(UUID tenantId, List<Template> templates) {
    if (templates.isEmpty()) return templates;
    // One query for every list's lines, grouped here.
    Object[] ids = templates.stream().map(Template::id).toArray();
    Map<UUID, List<TemplateItem>> byTemplate = new LinkedHashMap<>();
    for (TemplateItem item :
        query(
            "SELECT "
                + TEMPLATE_ITEM_COLUMNS
                + " FROM task_template_items"
                + " WHERE tenant_id = ? AND template_id = ANY(?) ORDER BY template_id, position",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setArray(2, ps.getConnection().createArrayOf("uuid", ids));
            },
            rs ->
                new TemplateItem(
                    rs.getObject("id", UUID.class),
                    rs.getObject("template_id", UUID.class),
                    rs.getInt("position"),
                    rs.getString("text"),
                    rs.getBoolean("required")),
            "read a list's lines")) {
      byTemplate.computeIfAbsent(item.templateId(), k -> new ArrayList<>()).add(item);
    }
    List<Template> out = new ArrayList<>(templates.size());
    for (Template t : templates) {
      List<TemplateItem> items = byTemplate.getOrDefault(t.id(), List.of());
      out.add(
          new Template(
              t.id(),
              t.tenantId(),
              t.storeId(),
              t.title(),
              t.instructions(),
              t.kind(),
              t.daysOfWeek(),
              t.dueTime(),
              t.graceMinutes(),
              t.role(),
              t.required(),
              t.status(),
              t.createdAt(),
              t.createdBy(),
              t.withdrawnAt(),
              t.withdrawnBy(),
              items));
    }
    return List.copyOf(out);
  }

  private List<Instance> withInstanceItems(UUID tenantId, List<Instance> instances) {
    if (instances.isEmpty()) return instances;
    Object[] ids = instances.stream().map(Instance::id).toArray();
    Map<UUID, List<InstanceItem>> items = new LinkedHashMap<>();
    for (InstanceItem item :
        query(
            "SELECT "
                + INSTANCE_ITEM_COLUMNS
                + " FROM task_instance_items"
                + " WHERE tenant_id = ? AND instance_id = ANY(?) ORDER BY instance_id, position",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setArray(2, ps.getConnection().createArrayOf("uuid", ids));
            },
            rs ->
                new InstanceItem(
                    rs.getObject("id", UUID.class),
                    rs.getObject("instance_id", UUID.class),
                    rs.getInt("position"),
                    rs.getString("text"),
                    rs.getBoolean("required"),
                    instant(rs, "ticked_at"),
                    rs.getObject("ticked_by", UUID.class)),
            "read a task's lines")) {
      items.computeIfAbsent(item.instanceId(), k -> new ArrayList<>()).add(item);
    }
    List<Instance> out = new ArrayList<>(instances.size());
    for (Instance i : instances) {
      out.add(
          new Instance(
              i.id(),
              i.tenantId(),
              i.storeId(),
              i.templateId(),
              i.businessDate(),
              i.dueAt(),
              i.status(),
              i.title(),
              i.kind(),
              i.role(),
              i.required(),
              i.completedAt(),
              i.completedBy(),
              i.skippedReason(),
              i.note(),
              i.createdAt(),
              items.getOrDefault(i.id(), List.of())));
    }
    return List.copyOf(out);
  }

  private static Array days(Connection c, Set<Integer> days) throws SQLException {
    return c.createArrayOf("smallint", days.toArray(new Integer[0]));
  }

  private static Template mapTemplate(ResultSet rs) throws SQLException {
    Set<Integer> days = new LinkedHashSet<>();
    Array array = rs.getArray("days_of_week");
    if (array != null) {
      for (Object o : (Object[]) array.getArray()) days.add(((Number) o).intValue());
    }
    return new Template(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getString("title"),
        rs.getString("instructions"),
        rs.getString("kind"),
        days,
        rs.getObject("due_time", LocalTime.class),
        rs.getInt("grace_minutes"),
        rs.getString("role"),
        rs.getBoolean("required"),
        rs.getString("status"),
        instant(rs, "created_at"),
        rs.getObject("created_by", UUID.class),
        instant(rs, "withdrawn_at"),
        rs.getObject("withdrawn_by", UUID.class),
        List.of());
  }

  private static Instance mapInstance(ResultSet rs) throws SQLException {
    return new Instance(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("template_id", UUID.class),
        rs.getObject("business_date", LocalDate.class),
        instant(rs, "due_at"),
        rs.getString("status"),
        rs.getString("title"),
        rs.getString("kind"),
        rs.getString("role"),
        rs.getBoolean("required"),
        instant(rs, "completed_at"),
        rs.getObject("completed_by", UUID.class),
        rs.getString("skipped_reason"),
        rs.getString("note"),
        instant(rs, "created_at"),
        List.of());
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime at = rs.getObject(column, OffsetDateTime.class);
    return at == null ? null : at.toInstant();
  }

  /** The one race the database decides here, named so a caller can answer it. */
  @Override
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    if (UNIQUE_VIOLATION.equals(e.getSQLState())
        && e.getMessage() != null
        && e.getMessage().contains("uq_task_item_position")) {
      return ApiException.badRequest(
          "TASK_LINE_POSITION_REPEATED", "two lines of a list cannot share a position");
    }
    return super.handleTxSqlException(what, e);
  }
}
