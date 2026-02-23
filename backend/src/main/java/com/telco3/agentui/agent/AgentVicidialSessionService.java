package com.telco3.agentui.agent;

import com.telco3.agentui.vicidial.VicidialClient;
import com.telco3.agentui.vicidial.VicidialDiagnosticsService;
import com.telco3.agentui.vicidial.VicidialSessionClient;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AgentVicidialSessionService {
  private final VicidialSessionClient sessionClient;
  private final VicidialClient vicidialClient;
  private final VicidialCredentialService credentialService;
  private final VicidialDiagnosticsService diagnosticsService;
  private final Map<String, AgentVicidialState> stateByAgent = new ConcurrentHashMap<>();

  public AgentVicidialSessionService(VicidialSessionClient sessionClient, VicidialClient vicidialClient, VicidialCredentialService credentialService, VicidialDiagnosticsService diagnosticsService) {
    this.sessionClient = sessionClient;
    this.vicidialClient = vicidialClient;
    this.credentialService = credentialService;
    this.diagnosticsService = diagnosticsService;
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

  public Map<String, Object> listCampaigns(String agentUser) {
    AgentVicidialState state = requirePhoneConnected(agentUser);
    String raw = vicidialClient.campaignsForAgent(agentUser, state.phoneLogin, buildPhonePass(state.phoneLogin));
    List<String> campaigns = parseCampaigns(raw);
    return Map.of("phoneLogin", state.phoneLogin, "campaigns", campaigns, "raw", raw);
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

  static class AgentVicidialState {
    boolean phoneConnected;
    String phoneLogin;
    String campaign;
    String mode;
  }
}
