package com.telco3.agentui.vicidial;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@Component
public class VicidialClient {
  private final VicidialConfigService configService;

  public VicidialClient(VicidialConfigService configService) {
    this.configService = configService;
  }

  private String call(String path, Map<String, String> params) {
    var s = configService.resolve();
    params.put("user", s.apiUser());
    params.put("pass", s.apiPass());
    if (StringUtils.hasText(s.source())) {
      params.put("source", s.source());
    }
    return WebClient.create(s.baseUrl()).get().uri(u -> {
      var b = u.path(path);
      params.forEach(b::queryParam);
      return b.build();
    }).retrieve().bodyToMono(String.class).blockOptional().orElse("");
  }

  private String post(String path, Map<String, String> params) {
    var s = configService.resolve();
    params.put("user", s.apiUser());
    params.put("pass", s.apiPass());
    if (StringUtils.hasText(s.source())) {
      params.put("source", s.source());
    }
    return WebClient.create(s.baseUrl())
        .post()
        .uri(path)
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .bodyValue(toForm(params))
        .retrieve()
        .bodyToMono(String.class)
        .blockOptional()
        .orElse("");
  }

  private String toForm(Map<String, String> params) {
    StringBuilder sb = new StringBuilder();
    params.forEach((k, v) -> {
      if (!sb.isEmpty()) sb.append('&');
      sb.append(java.net.URLEncoder.encode(k, java.nio.charset.StandardCharsets.UTF_8));
      sb.append('=');
      sb.append(java.net.URLEncoder.encode(Objects.toString(v, ""), java.nio.charset.StandardCharsets.UTF_8));
    });
    return sb.toString();
  }

  public String externalStatus(String agent, String dispo, Long leadId, String campaign) {
    return call("/agc/api.php", new HashMap<>(Map.of("function", "external_status", "agent_user", agent, "value", dispo, "dispo_choice", dispo, "lead_id", String.valueOf(leadId), "campaign", campaign)));
  }

  public String previewAction(String agent, Long leadId, String campaign, String action) {
    return call("/agc/api.php", new HashMap<>(Map.of("function", "preview_dial_action", "agent_user", agent, "lead_id", String.valueOf(leadId), "campaign", campaign, "value", action)));
  }

  public String pause(String agent, String action) {
    return call("/agc/api.php", new HashMap<>(Map.of("function", "external_pause", "agent_user", agent, "value", action)));
  }

  public String activeLead(String agent) {
    return call("/agc/api.php", new HashMap<>(Map.of("function", "st_get_agent_active_lead", "agent_user", agent)));
  }

  public String leadSearch(String phone) {
    return call("/vicidial/non_agent_api.php", new HashMap<>(Map.of("function", "lead_search", "phone_number", phone)));
  }

  public String leadInfo(Long leadId) {
    return call("/vicidial/non_agent_api.php", new HashMap<>(Map.of("function", "lead_all_info", "lead_id", String.valueOf(leadId))));
  }

  public String addLead(String phone, String first, String last, String dni, String listId) {
    return call("/vicidial/non_agent_api.php", new HashMap<>(Map.of("function", "add_lead", "phone_number", phone, "first_name", first, "last_name", last, "vendor_lead_code", dni, "list_id", listId)));
  }

  public String agentLogin(String agentUser, String agentPass, String phoneLogin, String phonePass, String campaign) {
    var params = new HashMap<String, String>();
    params.put("function", "agent_login");
    params.put("agent_user", agentUser);
    params.put("agent_pass", agentPass);
    params.put("phone_login", phoneLogin);
    params.put("phone_pass", phonePass);
    params.put("campaign", campaign);
    return post("/agc/api.php", params);
  }

  public String agentLogout(String agentUser) {
    return call("/agc/api.php", new HashMap<>(Map.of("function", "agent_logoff", "agent_user", agentUser)));
  }

  public String agentStatus(String agentUser) {
    return call("/agc/api.php", new HashMap<>(Map.of("function", "agent_status", "agent_user", agentUser)));
  }

  public String liveAgents() {
    return call("/agc/api.php", new HashMap<>(Map.of("function", "live_agents")));
  }

  public String campaignsForAgent(String agentUser, String phoneLogin, String phonePass) {
    var params = new HashMap<String, String>();
    params.put("function", "campaign_status");
    params.put("agent_user", agentUser);
    params.put("phone_login", phoneLogin);
    params.put("phone_pass", phonePass);
    return call("/agc/api.php", params);
  }

  public String campaigns() {
    return call("/agc/api.php", new HashMap<>(Map.of("function", "campaign_status")));
  }
}
