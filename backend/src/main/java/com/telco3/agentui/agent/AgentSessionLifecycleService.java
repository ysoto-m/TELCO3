package com.telco3.agentui.agent;

import com.telco3.agentui.vicidial.VicidialClient;
import com.telco3.agentui.vicidial.VicidialRuntimeDataSourceFactory;
import com.telco3.agentui.vicidial.VicidialService;
import com.telco3.agentui.vicidial.domain.AgentVicidialCredentialEntity;
import com.telco3.agentui.vicidial.domain.AgentVicidialCredentialRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
public class AgentSessionLifecycleService {
  private final AgentVicidialCredentialRepository repo;
  private final VicidialCredentialService credentialService;
  private final AgentVicidialSessionService vicidialSessionService;
  private final VicidialClient vicidialClient;
  private final VicidialService vicidialService;
  private final VicidialRuntimeDataSourceFactory runtimeDataSourceFactory;

  public AgentSessionLifecycleService(
      AgentVicidialCredentialRepository repo,
      VicidialCredentialService credentialService,
      AgentVicidialSessionService vicidialSessionService,
      VicidialClient vicidialClient,
      VicidialService vicidialService,
      VicidialRuntimeDataSourceFactory runtimeDataSourceFactory
  ) {
    this.repo = repo;
    this.credentialService = credentialService;
    this.vicidialSessionService = vicidialSessionService;
    this.vicidialClient = vicidialClient;
    this.vicidialService = vicidialService;
    this.runtimeDataSourceFactory = runtimeDataSourceFactory;
  }

  public LogoutResult logout(String appUsername, String reason) {
    Optional<AgentVicidialCredentialEntity> optional = repo.findByAppUsername(appUsername);
    if (optional.isEmpty()) {
      return new LogoutResult(true, false, "NO_SESSION", Map.of());
    }

    AgentVicidialCredentialEntity session = optional.get();
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

  private Optional<String> resolveAgentPass(String appUsername) {
    return credentialService.resolveAgentPass(appUsername);
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

  public record LogoutResult(boolean ok, boolean hadSession, String code, Map<String, Object> details) {
  }
}
