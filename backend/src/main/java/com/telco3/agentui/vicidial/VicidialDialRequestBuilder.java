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
  private final VicidialDialProperties dialProperties;

  public VicidialDialRequestBuilder(
      VicidialClient vicidialClient,
      VicidialCampaignParser campaignParser,
      VicidialDialProperties dialProperties
  ) {
    this.vicidialClient = vicidialClient;
    this.campaignParser = campaignParser;
    this.dialProperties = dialProperties;
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
    String resolvedConfExten = normalizeConfExten(session.confExten, session.connectedPhoneLogin);
    String resolvedExten = resolveDialExten(session);
    boolean manualNumberDial = overrides != null && overrides.phoneNumber() != null && !overrides.phoneNumber().isBlank();

    LinkedHashMap<String, String> payload = new LinkedHashMap<>();
    payload.put("ACTION", "manDiaLnextCaLL");
    payload.put("server_ip", session.serverIp);
    payload.put("session_name", session.sessionName);
    payload.put("user", agentUser);
    payload.put("pass", agentPass);
    payload.put("campaign", campaign);
    payload.put("conf_exten", resolvedConfExten);
    payload.put("exten", resolvedExten);
    payload.put("ext_context", "default");
    payload.put("phone_login", session.connectedPhoneLogin);
    payload.put("qm_extension", session.connectedPhoneLogin);
    payload.put("agent_log_id", String.valueOf(session.agentLogId));
    payload.put("callback_id", "");
    payload.put("lead_id", "");
    payload.put("phone_code", overrides == null ? "" : defaultIfBlank(overrides.phoneCode(), defaultIfBlank(dialProperties.getDefaultPhoneCode(), "1")));
    payload.put("phone_number", overrides == null ? "" : defaultIfBlank(overrides.phoneNumber(), ""));
    payload.put("dial_timeout", overrides == null ? "" : String.valueOf(normalizedDialTimeout(overrides.dialTimeout())));
    payload.put("dial_prefix", overrides == null ? "" : defaultIfBlank(overrides.dialPrefix(), "9"));
    payload.put("preview", overrides == null ? "NO" : "YES");
    payload.put("dial_method", "MANUAL");
    payload.put("last_VDRP_stage", "PAUSED");
    payload.put("list_id", "");
    if (manualNumberDial) {
      payload.put("stage", "lookup");
      payload.put("campaign_cid", "0000000000");
      payload.put("use_internal_dnc", "N");
      payload.put("use_campaign_dnc", "N");
      payload.put("omit_phone_code", "N");
      payload.put("manual_dial_filter", "NONE");
      payload.put("manual_dial_search_filter", "NONE");
      payload.put("vendor_lead_code", "");
      payload.put("usegroupalias", "0");
      payload.put("account", "");
      payload.put("agent_dialed_number", "1");
      payload.put("agent_dialed_type", "MANUAL_DIALNOW");
      payload.put("vtiger_callback_id", "0");
      payload.put("manual_dial_call_time_check", "DISABLED");
      payload.put("dial_ingroup", "");
      payload.put("nocall_dial_flag", "DISABLED");
      payload.put("cid_lock", "0");
      payload.put("routing_initiated_recording", "Y");
      payload.put("manual_dial_validation", "N");
      payload.put("recording_filename", "FULLDATE_CUSTPHONE");
    }
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
    String normalizedConfExten = normalizeConfExten(session.confExten, null);
    if (normalizedConfExten != null) {
      return buildConferenceLocalChannel(normalizedConfExten);
    }
    String extension = Objects.toString(session.extension, "").trim();
    if (isLocalChannel(extension) && !containsPlaceholderToken(extension)) {
      return extension;
    }
    return "";
  }

  private String resolveDialExten(Entities.AgentVicidialCredentialEntity session) {
    String extension = firstNonBlank(
        session.extension,
        normalizeDialExten(dialProperties.getDefaultRecordingExten()),
        session.connectedPhoneLogin
    );
    if (extension == null || extension.isBlank()) {
      return "";
    }
    String value = extension.trim();
    if (containsPlaceholderToken(value)) {
      return defaultIfBlank(session.connectedPhoneLogin, "");
    }
    String normalized = value;
    if (normalized.contains("/")) {
      normalized = normalized.substring(normalized.indexOf('/') + 1);
    }
    if (normalized.contains("@")) {
      normalized = normalized.substring(0, normalized.indexOf('@'));
    }
    if (normalized.contains("-")) {
      normalized = normalized.substring(0, normalized.indexOf('-'));
    }
    normalized = normalized.trim();
    normalized = normalized.isBlank() ? defaultIfBlank(session.connectedPhoneLogin, "") : normalized;
    if (isAgentPhoneExtension(normalized, session.connectedPhoneLogin)) {
      String fallbackRecordingExten = normalizeDialExten(dialProperties.getDefaultRecordingExten());
      if (fallbackRecordingExten != null && !isAgentPhoneExtension(fallbackRecordingExten, session.connectedPhoneLogin)) {
        return fallbackRecordingExten;
      }
      return "";
    }
    return normalized;
  }

  private boolean isLocalChannel(String value) {
    String candidate = Objects.toString(value, "").trim();
    return !candidate.isBlank() && candidate.regionMatches(true, 0, "Local/", 0, "Local/".length()) && candidate.contains("@");
  }

  private String buildConferenceLocalChannel(String normalizedConfExten) {
    String digits = Objects.toString(normalizedConfExten, "").replaceAll("[^0-9]+", "");
    if (digits.isBlank()) {
      return "";
    }
    String monitorNumber = digits.startsWith("5") ? digits : "5" + digits;
    return "Local/" + monitorNumber + "@default";
  }

  private String normalizeConfExten(String value, String fallback) {
    String candidate = defaultIfBlank(value, fallback);
    if (candidate == null || candidate.isBlank() || containsPlaceholderToken(candidate)) {
      return fallback == null ? null : fallback.replaceAll("[^0-9]+", "");
    }
    String digits = candidate.replaceAll("[^0-9]+", "");
    if (!digits.isBlank()) {
      return digits;
    }
    return containsPlaceholderToken(candidate) ? null : candidate;
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private String normalizeDialExten(String value) {
    String candidate = Objects.toString(value, "").trim();
    if (candidate.isBlank() || containsPlaceholderToken(candidate)) {
      return null;
    }
    String digits = candidate.replaceAll("[^0-9]+", "");
    return digits.isBlank() ? candidate : digits;
  }

  private boolean isAgentPhoneExtension(String candidate, String phoneLogin) {
    String left = Objects.toString(candidate, "").replaceAll("[^0-9]+", "");
    String right = Objects.toString(phoneLogin, "").replaceAll("[^0-9]+", "");
    return !left.isBlank() && !right.isBlank() && left.equals(right);
  }

  private boolean containsPlaceholderToken(String value) {
    String normalized = Objects.toString(value, "").toLowerCase(Locale.ROOT);
    return normalized.contains("taskconfnum")
        || normalized.contains("session_id")
        || normalized.contains("undefined")
        || normalized.contains("null")
        || normalized.contains("+")
        || normalized.contains("&");
  }

  public record ManualDialOverrides(String phoneNumber, String phoneCode, Integer dialTimeout, String dialPrefix) {
  }
}
