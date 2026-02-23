package com.telco3.agentui.agent;

import com.telco3.agentui.vicidial.VicidialClient;
import com.telco3.agentui.vicidial.VicidialDiagnosticsService;
import com.telco3.agentui.vicidial.VicidialServiceException;
import com.telco3.agentui.vicidial.VicidialSessionClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AgentVicidialSessionService {
  private static final Logger log = LoggerFactory.getLogger(AgentVicidialSessionService.class);

  private final VicidialSessionClient sessionClient;
  private final VicidialClient vicidialClient;
  private final VicidialCredentialService credentialService;
  private final VicidialDiagnosticsService diagnosticsService;
  private final Environment environment;
  private final Map<String, AgentVicidialState> stateByAgent = new ConcurrentHashMap<>();

  public AgentVicidialSessionService(VicidialSessionClient sessionClient, VicidialClient vicidialClient, VicidialCredentialService credentialService, VicidialDiagnosticsService diagnosticsService, Environment environment) {
    this.sessionClient = sessionClient;
    this.vicidialClient = vicidialClient;
    this.credentialService = credentialService;
    this.diagnosticsService = diagnosticsService;
    this.environment = environment;
  }

  public Map<String, Object> connectPhone(String agentUser, String phoneLogin) {
    String phonePass = buildPhonePass(phoneLogin);
    String raw = sessionClient.connectPhone(agentUser, phoneLogin, phonePass);
    boolean connected = isSuccessful(raw);
    if (!connected) {
      stateByAgent.remove(agentUser);
      throw new IllegalStateException("No fue posible conectar anexo en VICIdial AGC");
    }

    var state = new AgentVicidialState();
    state.phoneConnected = true;
    state.phoneLogin = phoneLogin;
    stateByAgent.put(agentUser, state);
    return Map.of(
        "ok", true,
        "phoneConnected", true,
        "phoneLogin", phoneLogin,
        "raw", raw
    );
  }

  public Map<String, Object> disconnectPhone(String agentUser) {
    sessionClient.clear(agentUser);
    stateByAgent.remove(agentUser);
    credentialService.markDisconnected(agentUser);
    return Map.of("ok", true);
  }

  public Map<String, Object> listCampaigns(String appUsername) {
    AgentVicidialState state = requirePhoneConnected(appUsername);
    VicidialCredentialService.AgentVicidialCredentials credentials = credentialService.resolveAgentCredentials(appUsername);

    if (credentials.agentPass() == null || credentials.agentPass().isBlank()) {
      throw new VicidialServiceException(
          HttpStatus.BAD_REQUEST,
          "VICIDIAL_AGENT_CREDENTIALS_MISSING",
          "Falta agent_pass del agente en tabla users. El administrador debe completarlo para continuar.",
          "Actualice users.agent_pass_encrypted para el usuario autenticado.",
          Map.of("agentUser", credentials.agentUser(), "phoneLogin", state.phoneLogin)
      );
    }

    String phonePass = buildPhonePass(state.phoneLogin);
    var result = vicidialClient.campaignsForAgent(credentials.agentUser(), credentials.agentPass(), state.phoneLogin, phonePass);
    debugCampaignCall(credentials.agentUser(), state.phoneLogin, result.statusCode(), result.snippet(), credentials.fallbackUsed());

    List<String> campaigns = parseCampaigns(result.body());
    if (campaigns.isEmpty()) {
      throw new VicidialServiceException(
          HttpStatus.BAD_REQUEST,
          "VICIDIAL_EMPTY_CAMPAIGNS",
          "Vicidial respondió sin campañas para el agente autenticado.",
          "Verifique user/agent_pass del agente, phone_login conectado y permisos de campaña en Vicidial.",
          Map.of(
              "agentUser", mask(credentials.agentUser()),
              "phoneLogin", state.phoneLogin,
              "statusCode", result.statusCode(),
              "rawSnippet", result.snippet()
          )
      );
    }

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("phoneLogin", state.phoneLogin);
    response.put("campaigns", campaigns);
    response.put("raw", result.body());
    if (credentials.fallbackUsed()) {
      response.put("warning", credentials.warning());
    }
    return response;
  }

  public Map<String, Object> connectCampaign(String agentUser, String campaignId, String mode, boolean remember) {
    AgentVicidialState state = requirePhoneConnected(agentUser);

    String agentPass = credentialService.resolveAgentPass(agentUser)
        .orElseThrow(() -> new IllegalStateException("Debe configurar agent_pass en Perfil antes de conectar campaña"));

    String raw = vicidialClient.agentLogin(agentUser, agentPass, state.phoneLogin, buildPhonePass(state.phoneLogin), campaignId);
    boolean connected = isSuccessful(raw);
    if (!connected) {
      throw new IllegalStateException("No fue posible conectar a la campaña indicada");
    }

    state.campaign = campaignId;
    state.mode = mode;
    credentialService.saveLastSelection(agentUser, state.phoneLogin, campaignId, remember);
    credentialService.markConnected(agentUser, state.phoneLogin, campaignId);

    return Map.of(
        "ok", true,
        "campaign", campaignId,
        "mode", mode,
        "phoneLogin", state.phoneLogin,
        "raw", raw
    );
  }

  public Map<String, Object> status(String agentUser) {
    diagnosticsService.assertVicidialReadyOrThrow();
    AgentVicidialState state = stateByAgent.get(agentUser);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("ok", true);
    response.put("agentUser", agentUser);
    response.put("phoneConnected", state != null && state.phoneConnected);
    response.put("phoneLogin", state == null ? null : state.phoneLogin);
    response.put("campaign", state == null ? null : state.campaign);
    response.put("mode", state == null ? null : state.mode);
    response.put("typingEnabled", state != null && state.phoneConnected && state.campaign != null);
    return response;
  }

  private AgentVicidialState requirePhoneConnected(String agentUser) {
    AgentVicidialState state = stateByAgent.get(agentUser);
    if (state == null || !state.phoneConnected) {
      throw new IllegalStateException("Debe conectar anexo antes de continuar");
    }
    return state;
  }

  private String buildPhonePass(String phoneLogin) {
    return "anexo_" + phoneLogin;
  }

  private boolean isSuccessful(String raw) {
    String normalized = Objects.toString(raw, "").toUpperCase(Locale.ROOT);
    return normalized.contains("SUCCESS") || normalized.contains("LOGGED") || normalized.contains("200");
  }

  private List<String> parseCampaigns(String raw) {
    if (raw == null || raw.isBlank()) return List.of();
    var campaigns = new LinkedHashSet<String>();
    var matcher = java.util.regex.Pattern.compile("campaign(?:_id)?=([^&\\s]+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(raw);
    while (matcher.find()) campaigns.add(matcher.group(1));
    if (campaigns.isEmpty()) {
      raw.lines().map(String::trim).filter(line -> !line.isBlank()).forEach(campaigns::add);
    }
    return campaigns.stream().limit(200).toList();
  }

  private void debugCampaignCall(String agentUser, String phoneLogin, int statusCode, String snippet, boolean fallbackUsed) {
    if (!isDevEnvironment()) {
      return;
    }
    log.info(
        "Vicidial campaigns debug endpoint=/agc/api.php function=campaign_status agentUser={} phone_login={} status={} snippet={} fallbackUsed={}",
        mask(agentUser),
        phoneLogin,
        statusCode,
        snippet,
        fallbackUsed
    );
  }

  private boolean isDevEnvironment() {
    boolean profileDev = Arrays.stream(environment.getActiveProfiles()).anyMatch("dev"::equalsIgnoreCase);
    String appEnv = environment.getProperty("APP_ENV", environment.getProperty("app.env", ""));
    return profileDev || "dev".equalsIgnoreCase(appEnv);
  }

  private String mask(String value) {
    if (value == null || value.isBlank()) return "***";
    if (value.length() <= 2) return "**";
    return value.substring(0, 2) + "***" + value.substring(value.length() - 1);
  }

  static class AgentVicidialState {
    boolean phoneConnected;
    String phoneLogin;
    String campaign;
    String mode;
  }
}
