package com.telco3.agentui.vicidial;

import com.telco3.agentui.domain.Entities;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Component
public class VicidialDialRequestBuilder {
  private final VicidialClient vicidialClient;
  private final VicidialCampaignParser campaignParser;
  private final Map<String, String> campaignAliases;

  public VicidialDialRequestBuilder(
      VicidialClient vicidialClient,
      VicidialCampaignParser campaignParser,
      VicidialDialProperties dialProperties
  ) {
    this.vicidialClient = vicidialClient;
    this.campaignParser = campaignParser;
    this.campaignAliases = dialProperties.getCampaignAliases();
  }

  public Map<String, String> buildDialNextPayload(
      String agentUser,
      String agentPass,
      Entities.AgentVicidialCredentialEntity session,
      String requestedCampaign
  ) {
    return buildPayload(agentUser, agentPass, session, requestedCampaign, null);
  }

  public Map<String, String> buildManualDialPayload(
      String agentUser,
      String agentPass,
      Entities.AgentVicidialCredentialEntity session,
      String requestedCampaign,
      ManualDialOverrides overrides
  ) {
    return buildPayload(agentUser, agentPass, session, requestedCampaign, overrides);
  }

  private Map<String, String> buildPayload(
      String agentUser,
      String agentPass,
      Entities.AgentVicidialCredentialEntity session,
      String requestedCampaign,
      ManualDialOverrides overrides
  ) {
    validateSession(session);
    String campaign = resolveCampaign(agentUser, agentPass, requestedCampaign, session.connectedCampaign);

    LinkedHashMap<String, String> payload = new LinkedHashMap<>();
    payload.put("ACTION", "manDiaLnextCaLL");
    payload.put("server_ip", session.serverIp);
    payload.put("session_name", session.sessionName);
    payload.put("user", agentUser);
    payload.put("pass", agentPass);
    payload.put("campaign", campaign);
    payload.put("conf_exten", defaultIfBlank(session.confExten, session.connectedPhoneLogin));
    payload.put("exten", session.connectedPhoneLogin);
    payload.put("phone_login", session.connectedPhoneLogin);
    payload.put("agent_log_id", String.valueOf(session.agentLogId));
    payload.put("phone_code", overrides == null ? "" : defaultIfBlank(overrides.phoneCode(), "51"));
    payload.put("phone_number", overrides == null ? "" : defaultIfBlank(overrides.phoneNumber(), ""));
    payload.put("dial_timeout", overrides == null ? "" : String.valueOf(normalizedDialTimeout(overrides.dialTimeout())));
    payload.put("dial_prefix", overrides == null ? "" : defaultIfBlank(overrides.dialPrefix(), "9"));
    payload.put("preview", overrides == null ? "NO" : "YES");
    payload.put("list_id", "");
    payload.put("channel", buildChannel(session));
    return payload;
  }

  private String resolveCampaign(String agentUser, String agentPass, String requestedCampaign, String fallbackCampaign) {
    String preferred = defaultIfBlank(requestedCampaign, fallbackCampaign);
    if (preferred == null || preferred.isBlank()) {
      throw new VicidialServiceException(HttpStatus.CONFLICT,
          "VICIDIAL_SESSION_INCOMPLETE",
          "La sesión Vicidial está incompleta.",
          "Falta campaign para generar la llamada manual.",
          Map.of("missingField", "campaign"));
    }

    String aliasMapped = mapAlias(preferred);
    if (!aliasMapped.equals(preferred)) {
      return aliasMapped;
    }

    var campaignsResult = vicidialClient.campaignsForAgent(agentUser, agentPass);
    List<VicidialCampaignParser.CampaignOption> options = campaignParser.parseCampaignOptions(campaignsResult.body());
    Optional<String> exactOption = options.stream()
        .map(VicidialCampaignParser.CampaignOption::value)
        .filter(value -> value.equalsIgnoreCase(preferred))
        .findFirst();
    return exactOption.orElse(preferred);
  }

  private String mapAlias(String campaign) {
    if (campaignAliases == null || campaignAliases.isEmpty()) {
      return campaign;
    }
    for (Map.Entry<String, String> entry : campaignAliases.entrySet()) {
      if (Objects.equals(entry.getKey(), campaign) || entry.getKey().equalsIgnoreCase(campaign)) {
        return entry.getValue();
      }
    }
    return campaign;
  }

  private void validateSession(Entities.AgentVicidialCredentialEntity session) {
    Map<String, String> missing = new LinkedHashMap<>();
    captureMissing(missing, "phone_login", session.connectedPhoneLogin);
    captureMissing(missing, "campaign", session.connectedCampaign);
    captureMissing(missing, "session_name", session.sessionName);
    captureMissing(missing, "server_ip", session.serverIp);
    if (session.agentLogId == null) {
      missing.put("agent_log_id", "required");
    }
    if (!missing.isEmpty()) {
      throw new VicidialServiceException(HttpStatus.CONFLICT,
          "VICIDIAL_SESSION_INCOMPLETE",
          "La sesión Vicidial está incompleta para marcación manual.",
          "Vuelva a conectar campaña para refrescar session_name/server_ip/agent_log_id.",
          Map.of("missing", missing.keySet()));
    }
  }

  private int normalizedDialTimeout(Integer value) {
    return value == null || value <= 0 ? 60 : value;
  }

  private void captureMissing(Map<String, String> missing, String field, String value) {
    if (value == null || value.isBlank()) {
      missing.put(field, "required");
    }
  }

  private String defaultIfBlank(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private String buildChannel(Entities.AgentVicidialCredentialEntity session) {
    if (session.extension != null && !session.extension.isBlank()) {
      return session.extension;
    }
    if (session.protocol != null && !session.protocol.isBlank() && session.confExten != null && !session.confExten.isBlank()) {
      return session.protocol.toUpperCase(Locale.ROOT) + "/" + session.confExten;
    }
    return "";
  }

  public record ManualDialOverrides(String phoneNumber, String phoneCode, Integer dialTimeout, String dialPrefix) {
  }
}
