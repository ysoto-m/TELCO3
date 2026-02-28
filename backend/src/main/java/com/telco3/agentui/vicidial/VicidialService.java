package com.telco3.agentui.vicidial;

import com.telco3.agentui.agent.VicidialCredentialService;
import com.telco3.agentui.domain.Entities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class VicidialService {
  private static final Logger log = LoggerFactory.getLogger(VicidialService.class);

  private final VicidialClient client;
  private final VicidialDialResponseParser dialResponseParser;
  private final VicidialCredentialService credentialService;
  private final Environment environment;
  private final VicidialRuntimeDataSourceFactory dataSourceFactory;

  public VicidialService(VicidialClient client, VicidialDialResponseParser dialResponseParser, VicidialCredentialService credentialService,
                         Environment environment, VicidialRuntimeDataSourceFactory dataSourceFactory) {
    this.client = client;
    this.dialResponseParser = dialResponseParser;
    this.credentialService = credentialService;
    this.environment = environment;
    this.dataSourceFactory = dataSourceFactory;
  }

  public String resolveModeForCampaign(String appUsername, String campaignId) {
    String mode = campaignMode(campaignId).mode();
    credentialService.updateSessionMode(appUsername, mode);
    if (isDevEnvironment()) {
      log.info("Vicidial campaign mode resolved agent={} campaign={} mode={}", appUsername, campaignId, mode);
    }
    return mode;
  }

  public CampaignMode campaignMode(String campaignId) {
    JdbcTemplate jdbc = new JdbcTemplate(dataSourceFactory.getOrCreate());
    Map<String, Object> row = jdbc.query(
        "SELECT campaign_id, dial_method FROM vicidial_campaigns WHERE campaign_id = ?",
        rs -> rs.next() ? Map.of("campaign_id", rs.getString("campaign_id"), "dial_method", rs.getString("dial_method")) : null,
        campaignId
    );
    if (row == null) {
      throw new VicidialServiceException(org.springframework.http.HttpStatus.NOT_FOUND,
          "VICIDIAL_CAMPAIGN_NOT_FOUND", "No se encontró la campaña en Vicidial.");
    }
    String dialMethodRaw = Objects.toString(row.get("dial_method"), "");
    String mode = mapDialMethodToMode(dialMethodRaw);
    return new CampaignMode(Objects.toString(row.get("campaign_id"), campaignId), dialMethodRaw, mode);
  }

  public String mapDialMethodToMode(String dialMethod) {
    String normalized = Objects.toString(dialMethod, "").trim().toUpperCase(Locale.ROOT);
    if (normalized.contains("MANUAL")) {
      return "manual";
    }
    return "predictive";
  }

  public DialNextResult dialNextWithLeadRetry(String agentUser, String rawBody, String callId, Long leadId) {
    Long resolvedLeadId = leadId;
    if (resolvedLeadId == null) {
      for (int i = 0; i < 5; i++) {
        var active = client.activeLeadSafe(agentUser);
        if (active.outcome() == VicidialClient.ActiveLeadOutcome.SUCCESS) {
          Long candidate = extractLong(active.rawBody(), "lead_id");
          if (candidate != null) {
            resolvedLeadId = candidate;
            break;
          }
        }
        sleepQuietly(300L);
      }
    }

    if (resolvedLeadId == null) {
      credentialService.updateDialRuntime(agentUser, "DIALING", callId, null);
      return new DialNextResult(callId, null, "DIALING_NO_LEAD_YET");
    }

    credentialService.updateDialRuntime(agentUser, "ACTIVE", callId, resolvedLeadId);
    return new DialNextResult(callId, resolvedLeadId, "READY");
  }

  public ActiveLeadState classifyActiveLead(String agentUser, Entities.AgentVicidialCredentialEntity session) {
    if ("DIALING".equalsIgnoreCase(session.currentDialStatus) && session.currentCallId != null) {
      return ActiveLeadState.dialing(session.currentCallId);
    }

    var leadResult = client.activeLeadSafe(agentUser);
    if (leadResult.outcome() == VicidialClient.ActiveLeadOutcome.RELOGIN_REQUIRED) {
      return ActiveLeadState.relogin(leadResult.statusCode(), leadResult.rawBody());
    }
    if (leadResult.outcome() == VicidialClient.ActiveLeadOutcome.NO_LEAD) {
      credentialService.updateDialRuntime(agentUser, null, null, null);
      return ActiveLeadState.none(leadResult.statusCode(), "NO_ACTIVE_LEAD", leadResult.rawBody());
    }
    if (leadResult.outcome() == VicidialClient.ActiveLeadOutcome.SUCCESS) {
      Long leadId = extractLong(leadResult.rawBody(), "lead_id");
      if (leadId != null) {
        credentialService.updateDialRuntime(agentUser, "ACTIVE", session.currentCallId, leadId);
        return ActiveLeadState.ready(leadId, extract(leadResult.rawBody(), "phone_number"), extract(leadResult.rawBody(), "campaign"));
      }
    }
    credentialService.updateDialRuntime(agentUser, null, null, null);
    return ActiveLeadState.none(leadResult.statusCode(), "UNKNOWN", leadResult.rawBody());
  }

  private String extract(String raw, String key) {
    var m = java.util.regex.Pattern.compile(key + "=([^&\\n]+)").matcher(Objects.toString(raw, ""));
    return m.find() ? m.group(1) : "";
  }

  private Long extractLong(String raw, String key) {
    try {
      return Long.parseLong(extract(raw, key));
    } catch (Exception ex) {
      return null;
    }
  }

  private void sleepQuietly(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
  }

  private boolean isDevEnvironment() {
    boolean profileDev = Arrays.stream(environment.getActiveProfiles()).anyMatch("dev"::equalsIgnoreCase);
    String appEnv = environment.getProperty("APP_ENV", environment.getProperty("app.env", ""));
    return profileDev || "dev".equalsIgnoreCase(appEnv);
  }

  public record CampaignMode(String campaignId, String dialMethodRaw, String mode) {}

  public record DialNextResult(String callId, Long leadId, String classification) {}

  public record ActiveLeadState(boolean hasLead, boolean dialing, boolean reloginRequired, String callId, Long leadId,
                                String phoneNumber, String campaign, int httpStatus, String classification, String rawBody) {
    public static ActiveLeadState dialing(String callId) {
      return new ActiveLeadState(false, true, false, callId, null, null, null, 200, "DIALING", "");
    }

    public static ActiveLeadState ready(Long leadId, String phoneNumber, String campaign) {
      return new ActiveLeadState(true, false, false, null, leadId, phoneNumber, campaign, 200, "SUCCESS", "");
    }

    public static ActiveLeadState none(int httpStatus, String classification, String rawBody) {
      return new ActiveLeadState(false, false, false, null, null, null, null, httpStatus, classification, rawBody);
    }

    public static ActiveLeadState relogin(int httpStatus, String rawBody) {
      return new ActiveLeadState(false, false, true, null, null, null, null, httpStatus, "RELOGIN_REQUIRED", rawBody);
    }
  }
}
