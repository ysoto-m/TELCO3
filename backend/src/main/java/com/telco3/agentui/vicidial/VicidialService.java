package com.telco3.agentui.vicidial;

import com.telco3.agentui.agent.VicidialCredentialService;
import com.telco3.agentui.domain.Entities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

@Service
public class VicidialService {
  private static final Logger log = LoggerFactory.getLogger(VicidialService.class);

  private final VicidialClient client;
  private final VicidialDialResponseParser dialResponseParser;
  private final VicidialCredentialService credentialService;
  private final Environment environment;

  public VicidialService(VicidialClient client, VicidialDialResponseParser dialResponseParser, VicidialCredentialService credentialService, Environment environment) {
    this.client = client;
    this.dialResponseParser = dialResponseParser;
    this.credentialService = credentialService;
    this.environment = environment;
  }

  public String resolveModeForCampaign(String appUsername, String campaignId) {
    VicidialClient.CampaignDialConfig config = client.campaignDialConfig(campaignId);
    String mode = inferMode(config.dialMethod(), config.autoDialLevel());
    credentialService.updateSessionMode(appUsername, mode);
    if (isDevEnvironment()) {
      log.info("Vicidial campaign mode resolved agent={} campaign={} dialMethod={} autoDialLevel={} mode={}", appUsername, campaignId, config.dialMethod().orElse("N/A"), config.autoDialLevel().map(String::valueOf).orElse("N/A"), mode);
    }
    return mode;
  }

  private String inferMode(Optional<String> dialMethod, Optional<Double> autoDialLevel) {
    if (autoDialLevel.isPresent() && autoDialLevel.get() > 0D) {
      return "predictive";
    }
    return dialMethod.map(this::mapDialMethodToMode).orElse("predictive");
  }

  public String mapDialMethodToMode(String dialMethod) {
    String normalized = Objects.toString(dialMethod, "").trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "MANUAL", "INBOUND_MAN" -> "manual";
      case "ADAPT_PREDICTIVE", "PREDICTIVE" -> "predictive";
      default -> "predictive";
    };
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
    if (leadResult.outcome() == VicidialClient.ActiveLeadOutcome.SUCCESS) {
      Long leadId = extractLong(leadResult.rawBody(), "lead_id");
      if (leadId != null) {
        credentialService.updateDialRuntime(agentUser, "ACTIVE", session.currentCallId, leadId);
        return ActiveLeadState.ready(leadId, extract(leadResult.rawBody(), "phone_number"), extract(leadResult.rawBody(), "campaign"));
      }
    }
    credentialService.updateDialRuntime(agentUser, null, null, null);
    return ActiveLeadState.none();
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

  public record DialNextResult(String callId, Long leadId, String classification) {}

  public record ActiveLeadState(boolean hasLead, boolean dialing, String callId, Long leadId, String phoneNumber, String campaign) {
    public static ActiveLeadState dialing(String callId) {
      return new ActiveLeadState(false, true, callId, null, null, null);
    }

    public static ActiveLeadState ready(Long leadId, String phoneNumber, String campaign) {
      return new ActiveLeadState(true, false, null, leadId, phoneNumber, campaign);
    }

    public static ActiveLeadState none() {
      return new ActiveLeadState(false, false, null, null, null, null);
    }
  }
}
