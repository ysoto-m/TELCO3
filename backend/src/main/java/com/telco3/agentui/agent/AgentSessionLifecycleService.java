package com.telco3.agentui.agent;

import com.telco3.agentui.domain.AgentVicidialCredentialRepository;
import com.telco3.agentui.domain.Entities;
import com.telco3.agentui.vicidial.VicidialClient;
import com.telco3.agentui.vicidial.VicidialRuntimeDataSourceFactory;
import com.telco3.agentui.vicidial.VicidialService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class AgentSessionLifecycleService {
  private static final Logger log = LoggerFactory.getLogger(AgentSessionLifecycleService.class);

  private final AgentVicidialCredentialRepository repo;
  private final VicidialCredentialService credentialService;
  private final AgentVicidialSessionService vicidialSessionService;
  private final VicidialClient vicidialClient;
  private final VicidialService vicidialService;
  private final VicidialRuntimeDataSourceFactory runtimeDataSourceFactory;
  private final long orphanThresholdMs;

  public AgentSessionLifecycleService(
      AgentVicidialCredentialRepository repo,
      VicidialCredentialService credentialService,
      AgentVicidialSessionService vicidialSessionService,
      VicidialClient vicidialClient,
      VicidialService vicidialService,
      VicidialRuntimeDataSourceFactory runtimeDataSourceFactory,
      @Value("${app.agent-session.orphan-threshold-ms:60000}") long orphanThresholdMs
  ) {
    this.repo = repo;
    this.credentialService = credentialService;
    this.vicidialSessionService = vicidialSessionService;
    this.vicidialClient = vicidialClient;
    this.vicidialService = vicidialService;
    this.runtimeDataSourceFactory = runtimeDataSourceFactory;
    this.orphanThresholdMs = orphanThresholdMs;
  }

  public HeartbeatResult heartbeat(String appUsername, String sessionId, String lastKnownVicidialStatus) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    var entity = repo.findByAppUsername(appUsername).orElseGet(() -> {
      var created = new Entities.AgentVicidialCredentialEntity();
      created.appUsername = appUsername;
      created.agentUser = appUsername;
      return created;
    });
    entity.crmSessionId = resolveSessionId(sessionId, entity.crmSessionId);
    entity.lastHeartbeatAt = now;
    entity.sessionStatus = entity.connected
        ? "ACTIVE"
        : (entity.logoutTime != null ? "LOGGED_OUT" : "AUTHENTICATED");
    if (StringUtils.hasText(lastKnownVicidialStatus)) {
      entity.lastKnownVicidialStatus = lastKnownVicidialStatus.trim().toUpperCase();
    }
    entity.updatedAt = now;
    repo.save(entity);
    return new HeartbeatResult(true, entity.crmSessionId, entity.sessionStatus, entity.connected, now);
  }

  public BrowserExitResult browserExit(String authenticatedUser, String hintedAgentUser, String sessionId, String reason) {
    var entity = locateSessionEntity(authenticatedUser, hintedAgentUser, sessionId);
    if (entity.isEmpty()) {
      return new BrowserExitResult(true, false, "SESSION_NOT_FOUND", OffsetDateTime.now(ZoneOffset.UTC));
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    Entities.AgentVicidialCredentialEntity row = entity.get();
    row.lastBrowserExitAt = now;
    row.sessionStatus = "BROWSER_EXITED";
    row.cleanupStatus = firstNonBlank(reason, "BROWSER_EXIT_SIGNAL");
    row.updatedAt = now;
    repo.save(row);

    if (!row.connected) {
      return new BrowserExitResult(true, true, "BROWSER_EXIT_RECORDED", now);
    }

    try {
      LogoutResult logoutResult = logout(row.appUsername, firstNonBlank(reason, "BROWSER_EXIT"));
      return new BrowserExitResult(logoutResult.ok(), true, logoutResult.code(), now);
    } catch (Exception ex) {
      updateSessionState(row.appUsername, "INCONSISTENT_CLEANUP_FAILED", "BROWSER_EXIT_CLEANUP_FAILED", true);
      return new BrowserExitResult(false, true, "BROWSER_EXIT_CLEANUP_FAILED", now);
    }
  }

  public LogoutResult logout(String appUsername, String reason) {
    Optional<Entities.AgentVicidialCredentialEntity> optional = repo.findByAppUsername(appUsername);
    if (optional.isEmpty()) {
      return new LogoutResult(true, false, "NO_SESSION", Map.of());
    }

    Entities.AgentVicidialCredentialEntity session = optional.get();
    String vicidialAgentUser = firstNonBlank(session.agentUser, appUsername);
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("connectedBeforeLogout", session.connected);
    details.put("reason", firstNonBlank(reason, "USER_LOGOUT"));
    details.put("sessionId", session.crmSessionId);
    details.put("vicidialAgentUser", vicidialAgentUser);

    try {
      if (session.connected) {
        Optional<String> agentPassOpt = resolveAgentPass(appUsername);
        if (agentPassOpt.isPresent()) {
          String agentPass = agentPassOpt.get();
          try {
            var hangup = vicidialService.hangupActiveCall(
                vicidialAgentUser,
                agentPass,
                session,
                session.connectedCampaign,
                "N"
            );
            details.put("hangupClassification", hangup.classification());
          } catch (Exception ex) {
            details.put("hangupError", ex.getClass().getSimpleName());
          }
          try {
            var logoutFlow = vicidialService.logoutAgentSession(vicidialAgentUser, agentPass, session);
            details.put("logoutFlowExecuted", logoutFlow.executed());
            details.put("logoutFlowAccepted", logoutFlow.userLogoutAccepted());
            details.put("logoutFlowConfirmed", logoutFlow.loggedOutConfirmed());
            if (logoutFlow.details() != null && !logoutFlow.details().isEmpty()) {
              details.put("logoutFlowDetails", logoutFlow.details());
            }
            if (!logoutFlow.loggedOutConfirmed()) {
              details.put("agentLogoutFallbackUsed", true);
              runFallbackApiLogout(vicidialAgentUser, details, 2);
            }
          } catch (Exception ex) {
            details.put("logoutFlowError", ex.getClass().getSimpleName());
            details.put("agentLogoutFallbackUsed", true);
            runFallbackApiLogout(vicidialAgentUser, details, 2);
          }
        } else {
          details.put("logoutFlowError", "MISSING_AGENT_PASS");
          details.put("agentLogoutFallbackUsed", true);
          runFallbackApiLogout(vicidialAgentUser, details, 2);
        }
      }
    } finally {
      vicidialClient.clearAgentCookies(vicidialAgentUser);
      if (!vicidialAgentUser.equalsIgnoreCase(appUsername)) {
        vicidialClient.clearAgentCookies(appUsername);
      }
      vicidialSessionService.disconnectPhone(appUsername);
      updateAfterCleanup(appUsername, "LOGGED_OUT", "MANUAL_LOGOUT_OK", "LOGOUT_SUCCESS");
    }

    boolean stillInLiveAgents = isAgentInLiveAgents(vicidialAgentUser);
    details.put("stillInLiveAgents", stillInLiveAgents);
    return new LogoutResult(true, true, stillInLiveAgents ? "LOGOUT_PARTIAL" : "LOGOUT_OK", details);
  }

  @Scheduled(fixedDelayString = "${app.agent-session.reconcile-interval-ms:60000}")
  public void reconcileOrphanSessions() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    List<Entities.AgentVicidialCredentialEntity> connected = repo.findByConnectedTrue();
    for (Entities.AgentVicidialCredentialEntity session : connected) {
      try {
        reconcileOne(session, now);
      } catch (Exception ex) {
        log.warn("Agent session reconcile failed agent={} cause={}", session.appUsername, ex.getClass().getSimpleName());
      }
    }
  }

  private void reconcileOne(Entities.AgentVicidialCredentialEntity session, OffsetDateTime now) {
    if (!isStale(session.lastHeartbeatAt, session.connectedAt, now)) {
      return;
    }

    RuntimePresence presence = inspectRuntimePresence(session);
    if (!presence.runtimeReachable()) {
      updateSessionState(session.appUsername, "STALE_RUNTIME_UNKNOWN", "RUNTIME_UNAVAILABLE", false);
      return;
    }

    if (!presence.inLiveAgents()) {
      vicidialSessionService.disconnectPhone(session.appUsername);
      updateAfterCleanup(session.appUsername, "DISCONNECTED", "AUTO_FIXED_LOCAL_STATE", "NOT_IN_LIVE_AGENTS");
      return;
    }

    if (presence.hasActiveCall()) {
      updateSessionState(session.appUsername, "INCONSISTENT_ACTIVE_CALL", "SKIPPED_ACTIVE_CALL", true);
      return;
    }

    boolean logoutOk = attemptOperationalLogout(session);
    if (logoutOk) {
      vicidialSessionService.disconnectPhone(session.appUsername);
      updateAfterCleanup(session.appUsername, "DISCONNECTED", "AUTO_CLEANUP_OK", "ORPHAN_TIMEOUT_NO_ACTIVE_CALL");
    } else {
      updateSessionState(session.appUsername, "INCONSISTENT_CLEANUP_FAILED", "AUTO_CLEANUP_FAILED", true);
    }
  }

  private boolean attemptOperationalLogout(Entities.AgentVicidialCredentialEntity session) {
    String vicidialAgentUser = firstNonBlank(session.agentUser, session.appUsername);
    try {
      String agentPass = resolveAgentPass(session.appUsername).orElse(null);
      if (StringUtils.hasText(agentPass)) {
        try {
          vicidialService.hangupActiveCall(vicidialAgentUser, agentPass, session, session.connectedCampaign, "N");
        } catch (Exception ex) {
          log.debug("Orphan cleanup hangup ignored agent={} cause={}", session.appUsername, ex.getClass().getSimpleName());
        }
        try {
          var logoutFlow = vicidialService.logoutAgentSession(vicidialAgentUser, agentPass, session);
          if (logoutFlow.loggedOutConfirmed()) {
            return true;
          }
        } catch (Exception ex) {
          log.debug("Operational AGC logout flow failed agent={} cause={}", session.appUsername, ex.getClass().getSimpleName());
        }
        try {
          vicidialClient.agentLogout(vicidialAgentUser);
        } catch (Exception ex) {
          log.debug("Operational API logout fallback failed agent={} cause={}", session.appUsername, ex.getClass().getSimpleName());
        }
      } else {
        try {
          vicidialClient.agentLogout(vicidialAgentUser);
        } catch (Exception ex) {
          log.debug("Operational API logout without agent_pass failed agent={} cause={}", session.appUsername, ex.getClass().getSimpleName());
        }
      }
      sleepQuietly(350L);
      boolean stillLive = isAgentInLiveAgents(vicidialAgentUser);
      if (!stillLive) {
        vicidialClient.clearAgentCookies(vicidialAgentUser);
      }
      return !stillLive;
    } catch (Exception ex) {
      log.warn("Operational logout failed agent={} cause={}", session.appUsername, ex.getClass().getSimpleName());
      return false;
    }
  }

  private RuntimePresence inspectRuntimePresence(Entities.AgentVicidialCredentialEntity session) {
    JdbcTemplate jdbc;
    try {
      jdbc = new JdbcTemplate(runtimeDataSourceFactory.getOrCreate());
    } catch (Exception ex) {
      return RuntimePresence.runtimeUnavailable();
    }

    String liveSql = """
        SELECT status, lead_id, uniqueid, channel, extension
          FROM vicidial_live_agents
         WHERE user = ?
         ORDER BY last_update_time DESC
         LIMIT 1
        """;
    try {
      String vicidialAgentUser = firstNonBlank(session.agentUser, session.appUsername);
      return jdbc.query(liveSql, rs -> {
        if (!rs.next()) {
          return RuntimePresence.notInLiveAgents();
        }
        String status = rs.getString("status");
        Long leadId = rs.getLong("lead_id");
        if (rs.wasNull() || leadId != null && leadId <= 0) {
          leadId = null;
        }
        String uniqueId = rs.getString("uniqueid");
        String channel = rs.getString("channel");
        String extension = firstNonBlank(rs.getString("extension"), session.connectedPhoneLogin);
        boolean hasActiveCall = hasAutoCall(jdbc, session.connectedCampaign, leadId, uniqueId, channel, extension);
        return RuntimePresence.inLiveAgents(status, hasActiveCall);
      }, vicidialAgentUser);
    } catch (DataAccessException ex) {
      return RuntimePresence.runtimeUnavailable();
    }
  }

  private boolean hasAutoCall(
      JdbcTemplate jdbc,
      String campaignId,
      Long leadId,
      String uniqueId,
      String channel,
      String extension
  ) {
    String sql = """
        SELECT COUNT(*)
          FROM vicidial_auto_calls
         WHERE (? IS NULL OR campaign_id = ?)
           AND (
             (? IS NOT NULL AND lead_id = ?)
             OR (? IS NOT NULL AND uniqueid = ?)
             OR (? IS NOT NULL AND channel = ?)
             OR (? IS NOT NULL AND extension = ?)
           )
        """;
    try {
      Long count = jdbc.queryForObject(sql, Long.class,
          campaignId, campaignId,
          leadId, leadId,
          uniqueId, uniqueId,
          channel, channel,
          extension, extension);
      return count != null && count > 0;
    } catch (DataAccessException ex) {
      return false;
    }
  }

  private boolean isAgentInLiveAgents(String agentUser) {
    if (!StringUtils.hasText(agentUser)) {
      return false;
    }
    try {
      JdbcTemplate jdbc = new JdbcTemplate(runtimeDataSourceFactory.getOrCreate());
      Long count = jdbc.queryForObject("SELECT COUNT(*) FROM vicidial_live_agents WHERE user = ?", Long.class, agentUser);
      return count != null && count > 0;
    } catch (Exception ex) {
      return false;
    }
  }

  private void updateAfterCleanup(String appUsername, String sessionStatus, String cleanupStatus, String reason) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    repo.findByAppUsername(appUsername).ifPresent(entity -> {
      entity.sessionStatus = sessionStatus;
      entity.cleanupStatus = cleanupStatus;
      entity.logoutTime = now;
      entity.lastKnownVicidialStatus = firstNonBlank(reason, entity.lastKnownVicidialStatus);
      entity.updatedAt = now;
      repo.save(entity);
    });
  }

  private void updateSessionState(String appUsername, String sessionStatus, String cleanupStatus, boolean incrementAttempts) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    repo.findByAppUsername(appUsername).ifPresent(entity -> {
      entity.sessionStatus = sessionStatus;
      entity.cleanupStatus = cleanupStatus;
      if (incrementAttempts) {
        entity.cleanupAttempts = Objects.requireNonNullElse(entity.cleanupAttempts, 0) + 1;
      }
      entity.updatedAt = now;
      repo.save(entity);
    });
  }

  private Optional<String> resolveAgentPass(String appUsername) {
    return credentialService.resolveAgentPass(appUsername);
  }

  private Optional<Entities.AgentVicidialCredentialEntity> locateSessionEntity(
      String authenticatedUser,
      String hintedAgentUser,
      String sessionId
  ) {
    if (StringUtils.hasText(authenticatedUser)) {
      Optional<Entities.AgentVicidialCredentialEntity> fromAuth = repo.findByAppUsername(authenticatedUser);
      if (fromAuth.isPresent()) {
        if (StringUtils.hasText(hintedAgentUser)
            && !authenticatedUser.trim().equalsIgnoreCase(hintedAgentUser.trim())) {
          return Optional.empty();
        }
        return fromAuth;
      }
    }
    if (StringUtils.hasText(sessionId)) {
      Optional<Entities.AgentVicidialCredentialEntity> bySession = repo.findByCrmSessionId(sessionId.trim());
      if (bySession.isPresent()) {
        return bySession;
      }
    }
    return Optional.empty();
  }

  private boolean isStale(OffsetDateTime lastHeartbeatAt, OffsetDateTime connectedAt, OffsetDateTime now) {
    if (lastHeartbeatAt == null) {
      if (connectedAt == null) {
        return true;
      }
      return Duration.between(connectedAt, now).toMillis() >= orphanThresholdMs;
    }
    long silentMillis = Duration.between(lastHeartbeatAt, now).toMillis();
    return silentMillis >= orphanThresholdMs;
  }

  private String resolveSessionId(String incomingSessionId, String existingSessionId) {
    if (StringUtils.hasText(incomingSessionId)) {
      return incomingSessionId.trim();
    }
    if (StringUtils.hasText(existingSessionId)) {
      return existingSessionId.trim();
    }
    return UUID.randomUUID().toString();
  }

  private String snippet(String raw, int max) {
    String safe = Objects.toString(raw, "");
    return safe.substring(0, Math.min(safe.length(), Math.max(max, 0)));
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (StringUtils.hasText(value)) {
        return value.trim();
      }
    }
    return null;
  }

  private void sleepQuietly(long millis) {
    try {
      Thread.sleep(Math.max(millis, 0L));
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
  }

  private void runFallbackApiLogout(String vicidialAgentUser, Map<String, Object> details, int maxAttempts) {
    boolean logoutSent = false;
    int attempts = Math.max(maxAttempts, 1);
    for (int attempt = 1; attempt <= attempts; attempt++) {
      try {
        String raw = vicidialClient.agentLogout(vicidialAgentUser);
        details.put("agentLogoutAttempt" + attempt + "Snippet", snippet(raw, 180));
        logoutSent = true;
        sleepQuietly(350L);
        if (!isAgentInLiveAgents(vicidialAgentUser)) {
          break;
        }
      } catch (Exception ex) {
        details.put("agentLogoutAttempt" + attempt + "Error", ex.getClass().getSimpleName());
      }
    }
    details.put("agentLogoutSent", logoutSent);
  }

  public record HeartbeatResult(boolean ok, String sessionId, String status, boolean connected, OffsetDateTime serverTime) {
  }

  public record BrowserExitResult(boolean ok, boolean updated, String code, OffsetDateTime serverTime) {
  }

  public record LogoutResult(boolean ok, boolean hadSession, String code, Map<String, Object> details) {
  }

  private record RuntimePresence(boolean runtimeReachable, boolean inLiveAgents, boolean hasActiveCall, String agentStatus) {
    static RuntimePresence runtimeUnavailable() {
      return new RuntimePresence(false, false, false, null);
    }

    static RuntimePresence notInLiveAgents() {
      return new RuntimePresence(true, false, false, null);
    }

    static RuntimePresence inLiveAgents(String status, boolean hasActiveCall) {
      return new RuntimePresence(true, true, hasActiveCall, status);
    }
  }
}
