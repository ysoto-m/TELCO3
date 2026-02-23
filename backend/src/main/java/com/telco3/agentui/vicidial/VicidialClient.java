package com.telco3.agentui.vicidial;

import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.core.Exceptions;

import java.time.Duration;
import java.net.ConnectException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

@Component
public class VicidialClient {
  private final VicidialConfigService configService;
  private final Duration connectTimeout;
  private final Duration readTimeout;

  public VicidialClient(
      VicidialConfigService configService,
      @Value("${vicidial.http.connect-timeout-ms:4000}") long connectTimeoutMs,
      @Value("${vicidial.http.read-timeout-ms:12000}") long readTimeoutMs
  ) {
    this.configService = configService;
    this.connectTimeout = Duration.ofMillis(connectTimeoutMs);
    this.readTimeout = Duration.ofMillis(readTimeoutMs);
  }

  private VicidialHttpResult call(String path, Map<String, String> params) {
    var s = configService.resolve();
    params.put("user", s.apiUser());
    params.put("pass", s.apiPass());
    if (StringUtils.hasText(s.source())) {
      params.put("source", s.source());
    }
    return executeGet(s.baseUrl(), path, params);
  }

  private VicidialHttpResult post(String path, Map<String, String> params) {
    var s = configService.resolve();
    params.put("user", s.apiUser());
    params.put("pass", s.apiPass());
    if (StringUtils.hasText(s.source())) {
      params.put("source", s.source());
    }
    return executePost(s.baseUrl(), path, params);
  }

  private VicidialHttpResult executeGet(String baseUrl, String path, Map<String, String> params) {
    try {
      return client(baseUrl)
          .get()
          .uri(u -> {
            var b = u.path(path);
            params.forEach(b::queryParam);
            return b.build();
          })
          .exchangeToMono(resp -> resp.bodyToMono(String.class).defaultIfEmpty("").map(body -> new VicidialHttpResult(resp.statusCode().value(), body)))
          .timeout(readTimeout)
          .blockOptional()
          .orElse(new VicidialHttpResult(0, ""));
    } catch (Exception ex) {
      throw mapClientException(ex);
    }
  }

  private VicidialHttpResult executePost(String baseUrl, String path, Map<String, String> params) {
    try {
      return client(baseUrl)
          .post()
          .uri(path)
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .bodyValue(toForm(params))
          .exchangeToMono(resp -> resp.bodyToMono(String.class).defaultIfEmpty("").map(body -> new VicidialHttpResult(resp.statusCode().value(), body)))
          .timeout(readTimeout)
          .blockOptional()
          .orElse(new VicidialHttpResult(0, ""));
    } catch (Exception ex) {
      throw mapClientException(ex);
    }
  }

  private WebClient client(String baseUrl) {
    var httpClient = HttpClient.create()
        .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(connectTimeout.toMillis()))
        .responseTimeout(readTimeout);
    return WebClient.builder()
        .baseUrl(baseUrl)
        .clientConnector(new ReactorClientHttpConnector(httpClient))
        .build();
  }

  private RuntimeException mapClientException(Exception ex) {
    Throwable root = Exceptions.unwrap(ex);
    if (root instanceof TimeoutException) {
      return new VicidialServiceException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "VICIDIAL_UNREACHABLE",
          "Vicidial no respondió dentro del tiempo esperado.",
          "Verifique conectividad a VICIDIAL_BASE_URL y estado del servidor AGC/API.",
          Map.of("cause", "TimeoutException"));
    }
    if (root instanceof ConnectException || root instanceof java.net.UnknownHostException) {
      return new VicidialServiceException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "VICIDIAL_UNREACHABLE",
          "No fue posible conectar con Vicidial.",
          "Revise VICIDIAL_BASE_URL, DNS/ruta de red y disponibilidad del servidor.",
          Map.of("cause", root.getClass().getSimpleName()));
    }
    return new RuntimeException(root.getMessage(), root);
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
    return call("/agc/api.php", new HashMap<>(Map.of("function", "external_status", "agent_user", agent, "value", dispo, "dispo_choice", dispo, "lead_id", String.valueOf(leadId), "campaign", campaign))).body();
  }

  public String previewAction(String agent, Long leadId, String campaign, String action) {
    return call("/agc/api.php", new HashMap<>(Map.of("function", "preview_dial_action", "agent_user", agent, "lead_id", String.valueOf(leadId), "campaign", campaign, "value", action))).body();
  }

  public String pause(String agent, String action) {
    return call("/agc/api.php", new HashMap<>(Map.of("function", "external_pause", "agent_user", agent, "value", action))).body();
  }

  public String activeLead(String agent) {
    return call("/agc/api.php", new HashMap<>(Map.of("function", "st_get_agent_active_lead", "agent_user", agent))).body();
  }

  public String leadSearch(String phone) {
    return call("/vicidial/non_agent_api.php", new HashMap<>(Map.of("function", "lead_search", "phone_number", phone))).body();
  }

  public String leadInfo(Long leadId) {
    return call("/vicidial/non_agent_api.php", new HashMap<>(Map.of("function", "lead_all_info", "lead_id", String.valueOf(leadId)))).body();
  }

  public String addLead(String phone, String first, String last, String dni, String listId) {
    return call("/vicidial/non_agent_api.php", new HashMap<>(Map.of("function", "add_lead", "phone_number", phone, "first_name", first, "last_name", last, "vendor_lead_code", dni, "list_id", listId))).body();
  }

  public String agentLogin(String agentUser, String agentPass, String phoneLogin, String phonePass, String campaign) {
    var params = new HashMap<String, String>();
    params.put("function", "agent_login");
    params.put("agent_user", agentUser);
    params.put("agent_pass", agentPass);
    params.put("phone_login", phoneLogin);
    params.put("phone_pass", phonePass);
    params.put("campaign", campaign);
    return post("/agc/api.php", params).body();
  }

  public String agentLogout(String agentUser) {
    return call("/agc/api.php", new HashMap<>(Map.of("function", "agent_logoff", "agent_user", agentUser))).body();
  }

  public String agentStatus(String agentUser) {
    return call("/agc/api.php", new HashMap<>(Map.of("function", "agent_status", "agent_user", agentUser))).body();
  }

  public String liveAgents() {
    return call("/agc/api.php", new HashMap<>(Map.of("function", "live_agents"))).body();
  }

  public VicidialHttpResult campaignsForAgent(String agentUser, String agentPass) {
    var s = configService.resolve();
    var params = new HashMap<String, String>();
    params.put("user", agentUser);
    params.put("pass", agentPass);
    params.put("ACTION", "LogiNCamPaigns");
    params.put("format", "html");
    return executePost(s.baseUrl(), "/agc/vdc_db_query.php", params);
  }

  public String campaigns() {
    return call("/agc/api.php", new HashMap<>(Map.of("function", "campaign_status"))).body();
  }

  public record VicidialHttpResult(int statusCode, String body) {
    public String snippet() {
      return Optional.ofNullable(body).orElse("").substring(0, Math.min(Optional.ofNullable(body).orElse("").length(), 200));
    }
  }
}
