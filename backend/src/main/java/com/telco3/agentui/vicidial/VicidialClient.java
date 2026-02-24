package com.telco3.agentui.vicidial;

import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.core.Exceptions;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.cookie.CookieStore;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.DefaultRedirectStrategy;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.hc.core5.util.Timeout;
import org.apache.hc.client5.http.ConnectTimeoutException;

import java.time.Duration;
import java.time.Instant;
import java.net.ConnectException;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.net.SocketTimeoutException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class VicidialClient {
  private final VicidialConfigService configService;
  private final Duration connectTimeout;
  private final Duration readTimeout;
  private final Duration cookieTtl;
  private final ConcurrentHashMap<String, CookieSession> cookieSessions = new ConcurrentHashMap<>();

  public VicidialClient(
      VicidialConfigService configService,
      @Value("${vicidial.http.connect-timeout-ms:4000}") long connectTimeoutMs,
      @Value("${vicidial.http.read-timeout-ms:12000}") long readTimeoutMs,
      @Value("${vicidial.session.cookie-ttl-minutes:30}") long cookieTtlMinutes
  ) {
    this.configService = configService;
    this.connectTimeout = Duration.ofMillis(connectTimeoutMs);
    this.readTimeout = Duration.ofMillis(readTimeoutMs);
    this.cookieTtl = Duration.ofMinutes(cookieTtlMinutes);
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

  public VicidialHttpResult connectCampaign(String agentUser, String agentPass, String phoneLogin, String campaignId) {
    var s = configService.resolve();
    if (!StringUtils.hasText(s.baseUrl())) {
      throw new VicidialServiceException(HttpStatus.BAD_REQUEST,
          "VICIDIAL_CONFIG_MISSING",
          "No hay baseUrl de Vicidial configurada para conectar campaña.");
    }

    var params = buildCampaignConnectParams(agentUser, agentPass, phoneLogin, campaignId);
    VicidialHttpResult result = executePostWithCookies(s.baseUrl(), "/agc/vicidial.php", params, agentUser);

    if (result.statusCode() >= 400) {
      throw new VicidialServiceException(HttpStatus.BAD_GATEWAY,
          "VICIDIAL_HTTP_ERROR",
          "Vicidial devolvió un estado HTTP de error al conectar campaña.",
          "Verifique endpoint /agc/vicidial.php y disponibilidad del servidor Vicidial.",
          Map.of("status", result.statusCode()));
    }

    String body = Objects.toString(result.body(), "");
    if (containsInvalidCredentials(body)) {
      throw new VicidialServiceException(HttpStatus.BAD_REQUEST,
          "VICIDIAL_INVALID_CREDENTIALS",
          "Vicidial rechazó las credenciales del agente para conectar campaña.",
          "Revise agent_user/agent_pass en Vicidial.",
          Map.of("httpStatus", result.statusCode(), "rawSnippet", result.snippet()));
    }
    if (containsAnyError(body)) {
      throw new VicidialServiceException(HttpStatus.BAD_REQUEST,
          "VICIDIAL_CAMPAIGN_CONNECT_FAILED",
          "Vicidial devolvió un error al conectar campaña.",
          "Revise permisos del user en Vicidial, campaña asignada y phone_login válido.",
          Map.of("httpStatus", result.statusCode(), "rawSnippet", result.snippet()));
    }
    if (!hasConnectSuccessSignals(body)) {
      throw new VicidialServiceException(HttpStatus.BAD_REQUEST,
          "VICIDIAL_CAMPAIGN_CONNECT_FAILED",
          "No fue posible confirmar conexión de campaña en Vicidial.",
          "Revise permisos del user en Vicidial, campaña asignada, phone_login válido y respuesta HTML AGC.",
          Map.of("httpStatus", result.statusCode(), "rawSnippet", result.snippet()));
    }
    return result;
  }

  Map<String, String> buildCampaignConnectParams(String agentUser, String agentPass, String phoneLogin, String campaignId) {
    var params = new LinkedHashMap<String, String>();
    params.put("DB", "0");
    params.put("JS_browser_height", "641");
    params.put("JS_browser_width", "695");
    params.put("phone_login", phoneLogin);
    params.put("phone_pass", "anexo_" + phoneLogin);
    params.put("LOGINvarONE", "");
    params.put("LOGINvarTWO", "");
    params.put("LOGINvarTHREE", "");
    params.put("LOGINvarFOUR", "");
    params.put("LOGINvarFIVE", "");
    params.put("hide_relogin_fields", "");
    params.put("VD_login", agentUser);
    params.put("VD_pass", agentPass);
    params.put("VD_campaign", campaignId);
    return params;
  }

  boolean containsInvalidCredentials(String body) {
    return Objects.toString(body, "").contains("ERROR: Invalid Username/Password");
  }

  boolean hasConnectSuccessSignals(String body) {
    String text = Objects.toString(body, "");
    return text.contains("LOGOUT")
        || text.contains("Logout")
        || text.contains("AGENT_")
        || text.contains("vicidial.php?relogin")
        || text.contains("SESSION_name");
  }

  private boolean containsAnyError(String body) {
    return Objects.toString(body, "").contains("ERROR:");
  }

  private VicidialHttpResult executePostWithCookies(String baseUrl, String path, Map<String, String> params, String agentUser) {
    CookieStore cookieStore = cookieStoreFor(agentUser);
    RequestConfig requestConfig = RequestConfig.custom()
        .setConnectTimeout(Timeout.ofMilliseconds(connectTimeout.toMillis()))
        .setResponseTimeout(Timeout.ofMilliseconds(readTimeout.toMillis()))
        .build();

    try (CloseableHttpClient httpClient = HttpClients.custom()
        .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create().build())
        .setDefaultCookieStore(cookieStore)
        .setDefaultRequestConfig(requestConfig)
        .setRedirectStrategy(DefaultRedirectStrategy.INSTANCE)
        .build()) {

      HttpPost request = new HttpPost(resolveUri(baseUrl, path));
      request.setHeader("Content-Type", MediaType.APPLICATION_FORM_URLENCODED_VALUE);
      request.setEntity(new UrlEncodedFormEntity(toNameValuePairs(params), StandardCharsets.UTF_8));

      return httpClient.execute(request, response -> {
        String body;
        try {
          body = response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity());
        } catch (ParseException ex) {
          throw new IOException("No fue posible parsear respuesta de Vicidial", ex);
        }
        touchCookieSession(agentUser);
        return new VicidialHttpResult(response.getCode(), body);
      });
    } catch (ConnectTimeoutException ex) {
      throw new VicidialServiceException(HttpStatus.SERVICE_UNAVAILABLE,
          "VICIDIAL_UNREACHABLE",
          "Vicidial no respondió dentro del tiempo esperado.",
          "Verifique conectividad a VICIDIAL_BASE_URL y estado del servidor.",
          Map.of("cause", ex.getClass().getSimpleName()));
    } catch (SocketTimeoutException ex) {
      throw new VicidialServiceException(HttpStatus.SERVICE_UNAVAILABLE,
          "VICIDIAL_UNREACHABLE",
          "Vicidial no respondió dentro del tiempo esperado.",
          "Verifique conectividad a VICIDIAL_BASE_URL y estado del servidor.",
          Map.of("cause", ex.getClass().getSimpleName()));
    } catch (ConnectException | java.net.UnknownHostException ex) {
      throw new VicidialServiceException(HttpStatus.SERVICE_UNAVAILABLE,
          "VICIDIAL_UNREACHABLE",
          "No fue posible conectar con Vicidial.",
          "Revise VICIDIAL_BASE_URL, DNS/ruta de red y disponibilidad del servidor.",
          Map.of("cause", ex.getClass().getSimpleName()));
    } catch (IOException ex) {
      throw new RuntimeException(ex.getMessage(), ex);
    }
  }

  private List<NameValuePair> toNameValuePairs(Map<String, String> params) {
    List<NameValuePair> pairs = new ArrayList<>();
    params.forEach((key, value) -> pairs.add(new BasicNameValuePair(key, Objects.toString(value, ""))));
    return pairs;
  }

  private URI resolveUri(String baseUrl, String path) {
    try {
      return new URI(baseUrl + path);
    } catch (URISyntaxException ex) {
      throw new IllegalArgumentException("VICIDIAL_BASE_URL inválido: " + baseUrl, ex);
    }
  }

  private CookieStore cookieStoreFor(String agentUser) {
    evictExpiredCookieSessions();
    CookieSession session = cookieSessions.compute(agentUser, (key, existing) -> {
      if (existing == null || existing.expiresAt().isBefore(Instant.now())) {
        return new CookieSession(new BasicCookieStore(), Instant.now().plus(cookieTtl));
      }
      return new CookieSession(existing.cookieStore(), Instant.now().plus(cookieTtl));
    });
    return session.cookieStore();
  }

  private void touchCookieSession(String agentUser) {
    cookieSessions.computeIfPresent(agentUser, (key, existing) -> new CookieSession(existing.cookieStore(), Instant.now().plus(cookieTtl)));
  }

  private void evictExpiredCookieSessions() {
    Instant now = Instant.now();
    cookieSessions.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
  }

  public record VicidialHttpResult(int statusCode, String body) {
    public String snippet() {
      return Optional.ofNullable(body).orElse("").substring(0, Math.min(Optional.ofNullable(body).orElse("").length(), 200));
    }
  }

  private record CookieSession(CookieStore cookieStore, Instant expiresAt) {
  }
}
