package com.telco3.agentui.vicidial;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class VicidialRealtimeQueryService {
  private static final Logger log = LoggerFactory.getLogger(VicidialRealtimeQueryService.class);
  private static final List<String> DROP_STATUSES = List.of("DROP", "XDROP", "ABANDON", "ABANDONED");

  private final VicidialRuntimeDataSourceFactory dataSourceFactory;
  private final long schemaCacheTtlMs;
  private final Map<String, Boolean> schemaCache = new ConcurrentHashMap<>();

  private volatile long schemaCacheValidUntilMs = 0L;

  public VicidialRealtimeQueryService(
      VicidialRuntimeDataSourceFactory dataSourceFactory,
      @Value("${app.vicidial.realtime.schema-cache-ms:60000}") long schemaCacheTtlMs
  ) {
    this.dataSourceFactory = dataSourceFactory;
    this.schemaCacheTtlMs = schemaCacheTtlMs;
  }

  public List<RealtimeAgentRow> fetchLiveAgents(String campaign, String pauseCode, String search) {
    JdbcTemplate jdbc = runtimeJdbc();
    if (!hasTable(jdbc, "vicidial_live_agents")) {
      log.warn("Vicidial realtime table missing table=vicidial_live_agents");
      return List.of();
    }

    boolean hasUser = hasColumn(jdbc, "vicidial_live_agents", "user");
    boolean hasStatus = hasColumn(jdbc, "vicidial_live_agents", "status");
    if (!hasUser || !hasStatus) {
      log.warn("Vicidial realtime table missing required columns user/status on vicidial_live_agents");
      return List.of();
    }

    boolean hasCampaign = hasColumn(jdbc, "vicidial_live_agents", "campaign_id");
    boolean hasExtension = hasColumn(jdbc, "vicidial_live_agents", "extension");
    boolean hasLeadId = hasColumn(jdbc, "vicidial_live_agents", "lead_id");
    boolean hasChannel = hasColumn(jdbc, "vicidial_live_agents", "channel");
    boolean hasPauseCode = hasColumn(jdbc, "vicidial_live_agents", "pause_code");
    boolean hasComments = hasColumn(jdbc, "vicidial_live_agents", "comments");
    boolean hasConfExten = hasColumn(jdbc, "vicidial_live_agents", "conf_exten");
    boolean hasLastStateChange = hasColumn(jdbc, "vicidial_live_agents", "last_state_change");
    boolean hasLastUpdateTime = hasColumn(jdbc, "vicidial_live_agents", "last_update_time");
    boolean hasLastCallTime = hasColumn(jdbc, "vicidial_live_agents", "last_call_time");

    boolean hasUsers = hasTable(jdbc, "vicidial_users")
        && hasColumn(jdbc, "vicidial_users", "user")
        && hasColumn(jdbc, "vicidial_users", "full_name");
    boolean hasListPhone = hasLeadId
        && hasTable(jdbc, "vicidial_list")
        && hasColumn(jdbc, "vicidial_list", "lead_id")
        && hasColumn(jdbc, "vicidial_list", "phone_number");

    String pauseExpr = hasPauseCode
        ? "NULLIF(vla.`pause_code`, '')"
        : (hasComments ? "NULLIF(vla.`comments`, '')" : "NULL");
    String campaignExpr = hasCampaign ? "vla.`campaign_id`" : "NULL";
    String extensionExpr = hasExtension ? "vla.`extension`" : "NULL";
    String leadExpr = hasLeadId ? "vla.`lead_id`" : "NULL";
    String channelExpr = hasChannel ? "vla.`channel`" : "NULL";
    String confExtenExpr = hasConfExten ? "vla.`conf_exten`" : "NULL";
    String currentPhoneExpr = hasListPhone ? "vll.`phone_number`" : "NULL";
    String agentNameExpr = hasUsers ? "COALESCE(NULLIF(vu.`full_name`, ''), vla.`user`)" : "vla.`user`";

    List<String> stateReferenceCandidates = new ArrayList<>();
    if (hasLastStateChange) {
      stateReferenceCandidates.add("vla.`last_state_change`");
    }
    if (hasLastUpdateTime) {
      stateReferenceCandidates.add("vla.`last_update_time`");
    }
    if (hasLastCallTime) {
      stateReferenceCandidates.add("vla.`last_call_time`");
    }
    String stateReferenceExpr = stateReferenceCandidates.isEmpty()
        ? "NULL"
        : "COALESCE(" + String.join(", ", stateReferenceCandidates) + ")";
    String lastCallExpr = hasLastCallTime ? "vla.`last_call_time`" : "NULL";

    StringBuilder sql = new StringBuilder("""
        SELECT
          vla.`user` AS agent_user,
          %s AS agent_name,
          %s AS extension,
          %s AS campaign_id,
          vla.`status` AS vicidial_status,
          %s AS pause_code,
          %s AS current_phone,
          %s AS channel,
          %s AS conf_exten,
          %s AS lead_id,
          %s AS state_reference_time,
          %s AS last_call_time
        FROM vicidial_live_agents vla
        """.formatted(
        agentNameExpr,
        extensionExpr,
        campaignExpr,
        pauseExpr,
        currentPhoneExpr,
        channelExpr,
        confExtenExpr,
        leadExpr,
        stateReferenceExpr,
        lastCallExpr
    ));

    if (hasUsers) {
      sql.append(" LEFT JOIN vicidial_users vu ON vu.`user` = vla.`user` ");
    }
    if (hasListPhone) {
      sql.append(" LEFT JOIN vicidial_list vll ON vll.`lead_id` = vla.`lead_id` ");
    }

    sql.append(" WHERE 1 = 1 ");
    List<Object> params = new ArrayList<>();

    if (StringUtils.hasText(campaign) && hasCampaign) {
      sql.append(" AND vla.`campaign_id` = ? ");
      params.add(campaign.trim());
    }
    if (StringUtils.hasText(pauseCode)) {
      if (hasPauseCode || hasComments) {
        sql.append(" AND UPPER(COALESCE(").append(pauseExpr).append(", '')) = ? ");
        params.add(pauseCode.trim().toUpperCase(Locale.ROOT));
      } else {
        return List.of();
      }
    }
    if (StringUtils.hasText(search)) {
      String like = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
      sql.append(" AND (LOWER(vla.`user`) LIKE ? OR LOWER(").append(agentNameExpr).append(") LIKE ? ");
      params.add(like);
      params.add(like);
      if (hasExtension) {
        sql.append(" OR LOWER(COALESCE(vla.`extension`, '')) LIKE ? ");
        params.add(like);
      }
      if (hasCampaign) {
        sql.append(" OR LOWER(COALESCE(vla.`campaign_id`, '')) LIKE ? ");
        params.add(like);
      }
      if (hasListPhone) {
        sql.append(" OR LOWER(COALESCE(vll.`phone_number`, '')) LIKE ? ");
        params.add(like);
      }
      sql.append(" ) ");
    }

    sql.append(" ORDER BY vla.`user` ASC ");
    try {
      return jdbc.query(sql.toString(), (rs, rowNum) -> {
        Long leadId = rs.getLong("lead_id");
        if (rs.wasNull() || leadId != null && leadId <= 0) {
          leadId = null;
        }
        return new RealtimeAgentRow(
            rs.getString("agent_user"),
            rs.getString("agent_name"),
            rs.getString("extension"),
            rs.getString("campaign_id"),
            rs.getString("vicidial_status"),
            rs.getString("pause_code"),
            rs.getString("current_phone"),
            rs.getString("channel"),
            rs.getString("conf_exten"),
            leadId,
            toOffsetDateTime(rs.getTimestamp("state_reference_time")),
            toOffsetDateTime(rs.getTimestamp("last_call_time"))
        );
      }, params.toArray());
    } catch (DataAccessException ex) {
      log.warn("Vicidial realtime agents query failed cause={}", ex.getClass().getSimpleName());
      return List.of();
    }
  }

  public Map<String, Long> fetchCompletedTodayByAgent() {
    JdbcTemplate jdbc = runtimeJdbc();
    Map<String, Long> totals = new HashMap<>();
    mergeCountsByUser(jdbc, totals, "vicidial_log", "call_date");
    mergeCountsByUser(jdbc, totals, "vicidial_closer_log", "call_date");
    return totals;
  }

  public TodayMetrics fetchTodayMetrics(int serviceLevelThresholdSec) {
    JdbcTemplate jdbc = runtimeJdbc();
    long answeredCalls = answeredCount(jdbc, "vicidial_log")
        + answeredCount(jdbc, "vicidial_closer_log");
    long abandonedCalls = abandonedCount(jdbc);
    long averageWaitSeconds = averageSeconds(jdbc, "vicidial_closer_log", "queue_seconds");
    long averageTalkSeconds = averageTalkSeconds(jdbc);
    int serviceLevelPercent = serviceLevelPercent(jdbc, serviceLevelThresholdSec);
    return new TodayMetrics(answeredCalls, abandonedCalls, serviceLevelPercent, averageWaitSeconds, averageTalkSeconds);
  }

  public List<PauseCodeRow> fetchPauseCodes() {
    JdbcTemplate jdbc = runtimeJdbc();
    if (!hasTable(jdbc, "vicidial_pause_codes") || !hasColumn(jdbc, "vicidial_pause_codes", "pause_code")) {
      return List.of();
    }
    String pauseNameColumn = hasColumn(jdbc, "vicidial_pause_codes", "pause_code_name")
        ? "pause_code_name"
        : (hasColumn(jdbc, "vicidial_pause_codes", "pause_name") ? "pause_name" : null);
    String billableColumn = hasColumn(jdbc, "vicidial_pause_codes", "billable") ? "billable" : null;
    String nameExpr = pauseNameColumn == null ? "NULL" : ("`" + pauseNameColumn + "`");
    String billableExpr = billableColumn == null ? "NULL" : ("`" + billableColumn + "`");
    String sql = """
        SELECT `pause_code` AS pause_code, %s AS pause_name, %s AS billable
          FROM vicidial_pause_codes
         ORDER BY `pause_code` ASC
        """.formatted(nameExpr, billableExpr);
    try {
      return jdbc.query(sql, (rs, rowNum) -> new PauseCodeRow(
          rs.getString("pause_code"),
          rs.getString("pause_name"),
          rs.getString("billable")
      ));
    } catch (DataAccessException ex) {
      log.warn("Vicidial pause codes query failed cause={}", ex.getClass().getSimpleName());
      return List.of();
    }
  }

  public List<String> fetchCampaigns() {
    JdbcTemplate jdbc = runtimeJdbc();
    List<String> campaigns = activeCampaignsFromLiveAgents(jdbc);
    if (!campaigns.isEmpty()) {
      return campaigns;
    }
    return activeCampaignsFromCatalog(jdbc);
  }

  public List<DispositionRow> fetchActiveDispositions(String campaignId) {
    JdbcTemplate jdbc = runtimeJdbc();
    List<DispositionRow> campaignStatuses = activeDispositionsFromCampaign(jdbc, campaignId);
    if (!campaignStatuses.isEmpty()) {
      return campaignStatuses;
    }
    return activeDispositionsFromCatalog(jdbc);
  }

  private List<String> activeCampaignsFromLiveAgents(JdbcTemplate jdbc) {
    if (!hasTable(jdbc, "vicidial_live_agents") || !hasColumn(jdbc, "vicidial_live_agents", "campaign_id")) {
      return List.of();
    }
    String sql = """
        SELECT DISTINCT `campaign_id`
          FROM vicidial_live_agents
         WHERE `campaign_id` IS NOT NULL
           AND `campaign_id` <> ''
         ORDER BY `campaign_id` ASC
        """;
    try {
      return jdbc.query(sql, (rs, rowNum) -> rs.getString("campaign_id"));
    } catch (DataAccessException ex) {
      log.warn("Vicidial campaigns realtime query failed cause={}", ex.getClass().getSimpleName());
      return List.of();
    }
  }

  private List<String> activeCampaignsFromCatalog(JdbcTemplate jdbc) {
    if (!hasTable(jdbc, "vicidial_campaigns") || !hasColumn(jdbc, "vicidial_campaigns", "campaign_id")) {
      return List.of();
    }
    boolean hasActive = hasColumn(jdbc, "vicidial_campaigns", "active");
    String sql = hasActive
        ? """
          SELECT `campaign_id`
            FROM vicidial_campaigns
           WHERE `active` = 'Y'
           ORDER BY `campaign_id` ASC
          """
        : """
          SELECT `campaign_id`
            FROM vicidial_campaigns
           ORDER BY `campaign_id` ASC
          """;
    try {
      return jdbc.query(sql, (rs, rowNum) -> rs.getString("campaign_id"));
    } catch (DataAccessException ex) {
      log.warn("Vicidial campaigns catalog query failed cause={}", ex.getClass().getSimpleName());
      return List.of();
    }
  }

  private List<DispositionRow> activeDispositionsFromCampaign(JdbcTemplate jdbc, String campaignId) {
    if (!StringUtils.hasText(campaignId)
        || !hasTable(jdbc, "vicidial_campaign_statuses")
        || !hasColumn(jdbc, "vicidial_campaign_statuses", "campaign_id")
        || !hasColumn(jdbc, "vicidial_campaign_statuses", "status")) {
      return List.of();
    }
    boolean hasStatusName = hasColumn(jdbc, "vicidial_campaign_statuses", "status_name");
    boolean hasSelectable = hasColumn(jdbc, "vicidial_campaign_statuses", "selectable");
    boolean hasActive = hasColumn(jdbc, "vicidial_campaign_statuses", "active");
    String statusNameExpr = hasStatusName ? "status_name" : "status";

    StringBuilder sql = new StringBuilder("""
        SELECT status, COALESCE(NULLIF(%s, ''), status) AS status_name
          FROM vicidial_campaign_statuses
         WHERE campaign_id = ?
        """.formatted(statusNameExpr));
    List<Object> params = new ArrayList<>();
    params.add(campaignId.trim());
    if (hasSelectable) {
      sql.append(" AND selectable = 'Y' ");
    }
    if (hasActive) {
      sql.append(" AND active = 'Y' ");
    }
    sql.append(" ORDER BY status ASC ");
    try {
      return jdbc.query(sql.toString(), (rs, rowNum) -> new DispositionRow(
          rs.getString("status"),
          rs.getString("status_name"),
          "vicidial_campaign_statuses"
      ), params.toArray());
    } catch (DataAccessException ex) {
      log.warn("Vicidial campaign statuses query failed campaign={} cause={}", campaignId, ex.getClass().getSimpleName());
      return List.of();
    }
  }

  private List<DispositionRow> activeDispositionsFromCatalog(JdbcTemplate jdbc) {
    if (!hasTable(jdbc, "vicidial_statuses") || !hasColumn(jdbc, "vicidial_statuses", "status")) {
      return List.of();
    }
    boolean hasStatusName = hasColumn(jdbc, "vicidial_statuses", "status_name");
    boolean hasSelectable = hasColumn(jdbc, "vicidial_statuses", "selectable");
    boolean hasActive = hasColumn(jdbc, "vicidial_statuses", "active");
    String statusNameExpr = hasStatusName ? "status_name" : "status";

    StringBuilder sql = new StringBuilder("""
        SELECT status, COALESCE(NULLIF(%s, ''), status) AS status_name
          FROM vicidial_statuses
         WHERE 1 = 1
        """.formatted(statusNameExpr));
    if (hasSelectable) {
      sql.append(" AND selectable = 'Y' ");
    }
    if (hasActive) {
      sql.append(" AND active = 'Y' ");
    }
    sql.append(" ORDER BY status ASC ");
    try {
      return jdbc.query(sql.toString(), (rs, rowNum) -> new DispositionRow(
          rs.getString("status"),
          rs.getString("status_name"),
          "vicidial_statuses"
      ));
    } catch (DataAccessException ex) {
      log.warn("Vicidial statuses catalog query failed cause={}", ex.getClass().getSimpleName());
      return List.of();
    }
  }

  private long answeredCount(JdbcTemplate jdbc, String table) {
    if (!hasTable(jdbc, table) || !hasColumn(jdbc, table, "call_date")) {
      return 0;
    }
    boolean hasLength = hasColumn(jdbc, table, "length_in_sec");
    String sql = hasLength
        ? "SELECT COUNT(*) FROM `%s` WHERE `call_date` >= CURDATE() AND COALESCE(`length_in_sec`, 0) > 0".formatted(table)
        : "SELECT COUNT(*) FROM `%s` WHERE `call_date` >= CURDATE()".formatted(table);
    try {
      Long value = jdbc.queryForObject(sql, Long.class);
      return value == null ? 0 : value;
    } catch (DataAccessException ex) {
      log.warn("Vicidial answered count query failed table={} cause={}", table, ex.getClass().getSimpleName());
      return 0;
    }
  }

  private long abandonedCount(JdbcTemplate jdbc) {
    if (!hasTable(jdbc, "vicidial_closer_log")
        || !hasColumn(jdbc, "vicidial_closer_log", "call_date")
        || !hasColumn(jdbc, "vicidial_closer_log", "status")) {
      return 0;
    }
    String placeholders = String.join(",", DROP_STATUSES.stream().map(v -> "?").toList());
    String sql = """
        SELECT COUNT(*)
          FROM vicidial_closer_log
         WHERE call_date >= CURDATE()
           AND UPPER(status) IN (%s)
        """.formatted(placeholders);
    try {
      Long value = jdbc.queryForObject(sql, Long.class, DROP_STATUSES.toArray());
      return value == null ? 0 : value;
    } catch (DataAccessException ex) {
      log.warn("Vicidial abandoned count query failed cause={}", ex.getClass().getSimpleName());
      return 0;
    }
  }

  private long averageSeconds(JdbcTemplate jdbc, String table, String column) {
    if (!hasTable(jdbc, table)
        || !hasColumn(jdbc, table, "call_date")
        || !hasColumn(jdbc, table, column)) {
      return 0;
    }
    String sql = "SELECT COALESCE(AVG(`%s`), 0) FROM `%s` WHERE `call_date` >= CURDATE()".formatted(column, table);
    try {
      Number value = jdbc.queryForObject(sql, Number.class);
      return value == null ? 0 : Math.round(value.doubleValue());
    } catch (DataAccessException ex) {
      log.warn("Vicidial average seconds query failed table={} column={} cause={}", table, column, ex.getClass().getSimpleName());
      return 0;
    }
  }

  private long averageTalkSeconds(JdbcTemplate jdbc) {
    TalkAggregate logAgg = talkAggregate(jdbc, "vicidial_log");
    TalkAggregate closerAgg = talkAggregate(jdbc, "vicidial_closer_log");
    long totalSeconds = logAgg.totalSeconds() + closerAgg.totalSeconds();
    long totalCalls = logAgg.calls() + closerAgg.calls();
    if (totalCalls <= 0) {
      return 0;
    }
    return Math.round((double) totalSeconds / (double) totalCalls);
  }

  private TalkAggregate talkAggregate(JdbcTemplate jdbc, String table) {
    if (!hasTable(jdbc, table)
        || !hasColumn(jdbc, table, "call_date")
        || !hasColumn(jdbc, table, "length_in_sec")) {
      return new TalkAggregate(0, 0);
    }
    String sql = """
        SELECT COALESCE(SUM(length_in_sec), 0) AS total_seconds, COUNT(*) AS calls
          FROM %s
         WHERE call_date >= CURDATE()
           AND COALESCE(length_in_sec, 0) > 0
        """.formatted(table);
    try {
      return jdbc.query(sql, rs -> {
        if (!rs.next()) {
          return new TalkAggregate(0, 0);
        }
        return new TalkAggregate(rs.getLong("total_seconds"), rs.getLong("calls"));
      });
    } catch (DataAccessException ex) {
      log.warn("Vicidial talk aggregate query failed table={} cause={}", table, ex.getClass().getSimpleName());
      return new TalkAggregate(0, 0);
    }
  }

  private int serviceLevelPercent(JdbcTemplate jdbc, int serviceLevelThresholdSec) {
    if (!hasTable(jdbc, "vicidial_closer_log")
        || !hasColumn(jdbc, "vicidial_closer_log", "call_date")
        || !hasColumn(jdbc, "vicidial_closer_log", "queue_seconds")) {
      return 0;
    }
    String sql = """
        SELECT COUNT(*) AS total_calls,
               SUM(CASE WHEN COALESCE(queue_seconds, 0) <= ? THEN 1 ELSE 0 END) AS within_sla
          FROM vicidial_closer_log
         WHERE call_date >= CURDATE()
        """;
    try {
      return jdbc.query(sql, rs -> {
        if (!rs.next()) {
          return 0;
        }
        long totalCalls = rs.getLong("total_calls");
        long withinSla = rs.getLong("within_sla");
        if (totalCalls <= 0) {
          return 0;
        }
        return (int) Math.round((withinSla * 100.0d) / totalCalls);
      }, serviceLevelThresholdSec);
    } catch (DataAccessException ex) {
      log.warn("Vicidial service level query failed cause={}", ex.getClass().getSimpleName());
      return 0;
    }
  }

  private void mergeCountsByUser(JdbcTemplate jdbc, Map<String, Long> target, String table, String dateColumn) {
    if (!hasTable(jdbc, table) || !hasColumn(jdbc, table, "user") || !hasColumn(jdbc, table, dateColumn)) {
      return;
    }
    String sql = """
        SELECT `user`, COUNT(*) AS qty
          FROM `%s`
         WHERE `%s` >= CURDATE()
         GROUP BY `user`
        """.formatted(table, dateColumn);
    try {
      jdbc.query(sql, rs -> {
        String user = rs.getString("user");
        long qty = rs.getLong("qty");
        if (!StringUtils.hasText(user)) {
          return;
        }
        target.merge(user, qty, Long::sum);
      });
    } catch (DataAccessException ex) {
      log.warn("Vicidial per-agent count query failed table={} cause={}", table, ex.getClass().getSimpleName());
    }
  }

  private JdbcTemplate runtimeJdbc() {
    return new JdbcTemplate(dataSourceFactory.getOrCreate());
  }

  private boolean hasTable(JdbcTemplate jdbc, String tableName) {
    String key = "table:" + tableName;
    return resolveSchemaCache(key, () -> {
      Long count = jdbc.queryForObject("""
          SELECT COUNT(*)
            FROM information_schema.tables
           WHERE table_schema = DATABASE()
             AND table_name = ?
          """, Long.class, tableName);
      return count != null && count > 0;
    });
  }

  private boolean hasColumn(JdbcTemplate jdbc, String tableName, String columnName) {
    String key = "column:" + tableName + "." + columnName;
    return resolveSchemaCache(key, () -> {
      Long count = jdbc.queryForObject("""
          SELECT COUNT(*)
            FROM information_schema.columns
           WHERE table_schema = DATABASE()
             AND table_name = ?
             AND column_name = ?
          """, Long.class, tableName, columnName);
      return count != null && count > 0;
    });
  }

  private boolean resolveSchemaCache(String key, java.util.function.Supplier<Boolean> supplier) {
    evictSchemaCacheIfExpired();
    return schemaCache.computeIfAbsent(key, ignored -> {
      try {
        return supplier.get();
      } catch (DataAccessException ex) {
        log.warn("Vicidial schema lookup failed key={} cause={}", key, ex.getClass().getSimpleName());
        return false;
      }
    });
  }

  private void evictSchemaCacheIfExpired() {
    long now = System.currentTimeMillis();
    if (now <= schemaCacheValidUntilMs) {
      return;
    }
    synchronized (this) {
      if (now > schemaCacheValidUntilMs) {
        schemaCache.clear();
        schemaCacheValidUntilMs = now + schemaCacheTtlMs;
      }
    }
  }

  private OffsetDateTime toOffsetDateTime(Timestamp ts) {
    if (ts == null) {
      return null;
    }
    return ts.toInstant().atOffset(ZoneOffset.UTC);
  }

  private record TalkAggregate(long totalSeconds, long calls) {
  }

  public record RealtimeAgentRow(
      String agentUser,
      String agentName,
      String extension,
      String campaignId,
      String vicidialStatus,
      String pauseCode,
      String currentPhone,
      String channel,
      String confExten,
      Long leadId,
      OffsetDateTime stateReferenceTime,
      OffsetDateTime lastCallTime
  ) {
  }

  public record PauseCodeRow(String pauseCode, String pauseName, String billable) {
  }

  public record DispositionRow(String status, String statusName, String sourceTable) {
  }

  public record TodayMetrics(
      long answeredCalls,
      long abandonedCalls,
      int serviceLevelPercent,
      long averageWaitSeconds,
      long averageTalkSeconds
  ) {
  }
}
