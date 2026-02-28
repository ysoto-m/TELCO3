package com.telco3.agentui.vicidial;

import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

@Component
public class VicidialClient {
  private static final Logger log = LoggerFactory.getLogger(VicidialClient.class);
  private final VicidialConfigService configService;
  private final Duration connectTimeout;
  private final Duration readTimeout;
  private final Duration writeTimeout;
  private final boolean vicidialDebug;
  private final Duration cookieTtl;
  private final ConcurrentHashMap<String, CookieSession> cookieSessions = new ConcurrentHashMap<>();

  public VicidialClient(
      VicidialConfigService configService,
      @Value("${vicidial.http.connect-timeout-ms:4000}") long connectTimeoutMs,
      @Value("${vicidial.http.read-timeout-ms:12000}") long readTimeoutMs,
      @Value("${vicidial.http.write-timeout-ms:12000}") long writeTimeoutMs,
      @Value("${app.vicidial.debug:false}") boolean vicidialDebug
  ) {
    this.configService = configService;
    this.connectTimeout = Duration.ofMillis(connectTimeoutMs);
    this.readTimeout = Duration.ofMillis(readTimeoutMs);
    this.writeTimeout = Duration.ofMillis(writeTimeoutMs);
    this.vicidialDebug = vicidialDebug;
    this.cookieTtl = Duration.ofMinutes(30);
  }

  private VicidialHttpResult call(String path, Map<String, String> params) {
    configService.assertVicidialApiConfigured();
    var s = configService.resolve();
    params.put("user", s.apiUser());
    params.put("pass", s.apiPass());
    if (StringUtils.hasText(s.source())) {
      params.put("source", s.source());
    }
    return executeGet(s.baseUrl(), path, params);
  }

  private VicidialHttpResult post(String path, Map<String, String> params) {
    configService.assertVicidialApiConfigured();
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

  public VicidialHttpResult externalDialManualNext(String agentUser) {
    return post("/agc/api.php", new HashMap<>(Map.of(
        "function", "external_dial",
        "agent_user", agentUser,
        "value", "MANUALNEXT"
    )));
  }

  public VicidialHttpResult manualDialNextCall(Map<String, String> params) {
    configService.assertVicidialApiConfigured();
    var s = configService.resolve();
    var payload = new LinkedHashMap<String, String>();
    payload.putAll(params);
    if (StringUtils.hasText(s.source()) && !payload.containsKey("source")) {
      payload.put("source", s.source());
    }
    return executePost(s.baseUrl(), "/agc/vdc_db_query.php", payload);
  }

  public ManualDialOutcome evaluateManualDialBody(String body) {
    String normalized = normalize(body);
    if (containsLoginForm(body)
        || normalized.contains("agent_user is not logged in")
        || normalized.contains("not logged in")
        || normalized.contains("re-login")) {
      return ManualDialOutcome.RELOGIN_REQUIRED;
    }
    if (normalized.contains("invalid username/password") || normalized.contains("invalid user/pass")) {
      return ManualDialOutcome.INVALID_CREDENTIALS;
    }
    if (normalized.contains("does not have permission") || normalized.contains("permission")) {
      return ManualDialOutcome.PERMISSION_DENIED;
    }
    if (normalized.contains("error")) {
      return ManualDialOutcome.FAILED;
    }
    if (normalized.contains("call_id") || normalized.contains("lead_id") || normalized.contains("success")) {
      return ManualDialOutcome.SUCCESS;
    }
    return ManualDialOutcome.UNKNOWN;
  }

  public Map<String, String> parseKeyValueLines(String rawBody) {
    Map<String, String> parsed = new LinkedHashMap<>();
    String safeBody = Objects.toString(rawBody, "");
    String[] lines = safeBody.split("\\r?\\n");
    Pattern pattern = Pattern.compile("^\\s*([A-Za-z0-9_]+)\\s*[:=]\\s*(.*?)\\s*$");
    for (String line : lines) {
      Matcher matcher = pattern.matcher(line);
      if (matcher.find()) {
        parsed.put(matcher.group(1).toLowerCase(), matcher.group(2));
      }
    }
    Matcher callIdMatcher = Pattern.compile("\\b(M[0-9]{6,})\\b").matcher(safeBody);
    if (callIdMatcher.find()) {
      parsed.putIfAbsent("call_id", callIdMatcher.group(1));
    }
    return parsed;
  }

  public String activeLead(String agent) {
    return call("/agc/api.php", new HashMap<>(Map.of("function", "st_get_agent_active_lead", "agent_user", agent))).body();
  }

  public ActiveLeadResult activeLeadSafe(String agentUser) {
    VicidialHttpResult response = call("/agc/api.php", new HashMap<>(Map.of("function", "st_get_agent_active_lead", "agent_user", agentUser)));
    String body = Objects.toString(response.body(), "");
    return new ActiveLeadResult(evaluateActiveLeadBody(body), body, response.statusCode());
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
    configService.assertVicidialApiConfigured();
    var s = configService.resolve();
    var params = new HashMap<String, String>();
    params.put("user", agentUser);
    params.put("pass", agentPass);
    params.put("ACTION", "LogiNCamPaigns");
    params.put("format", "html");
    return executePost(s.baseUrl(), "/agc/vdc_db_query.php", params);
  }



  public Optional<String> campaignDialMethod(String campaignId) {
    return campaignDialConfig(campaignId).dialMethod();
  }

  public CampaignDialConfig campaignDialConfig(String campaignId) {
    if (!StringUtils.hasText(campaignId)) {
      return new CampaignDialConfig(Optional.empty(), Optional.empty());
    }
    var result = call("/vicidial/non_agent_api.php", new HashMap<>(Map.of("function", "campaign_status", "campaign_id", campaignId)));
    String body = Objects.toString(result.body(), "");
    return new CampaignDialConfig(extractDialMethod(body), extractAutoDialLevel(body));
  }

  private Optional<String> extractDialMethod(String body) {
    Matcher matcher = Pattern.compile("(?i)dial_method\\s*[:=]\\s*([A-Z_]+)").matcher(body);
    if (matcher.find()) {
      return Optional.ofNullable(matcher.group(1)).map(String::trim).filter(v -> !v.isBlank());
    }
    Map<String, String> lines = parseKeyValueLines(body);
    String value = lines.get("dial_method");
    if (value == null) {
      value = lines.get("dialmethod");
    }
    return Optional.ofNullable(value).map(String::trim).filter(v -> !v.isBlank());
  }

  private Optional<Double> extractAutoDialLevel(String body) {
    Matcher matcher = Pattern.compile("(?i)auto_dial_level\\s*[:=]\\s*([0-9]+(?:\\.[0-9]+)?)").matcher(body);
    if (matcher.find()) {
      return parseDoubleSafe(matcher.group(1));
    }
    Map<String, String> lines = parseKeyValueLines(body);
    String value = lines.get("auto_dial_level");
    if (value == null) {
      value = lines.get("autodiallevel");
    }
    return parseDoubleSafe(value);
  }

  private Optional<Double> parseDoubleSafe(String value) {
    if (!StringUtils.hasText(value)) {
      return Optional.empty();
    }
    try {
      return Optional.of(Double.parseDouble(value.trim()));
    } catch (NumberFormatException ignored) {
      return Optional.empty();
    }
  }

  public String campaigns() {
    return call("/agc/api.php", new HashMap<>(Map.of("function", "campaign_status"))).body();
  }

  public VicidialHttpResult connectCampaign(String agentUser, String agentPass, String phoneLogin, String campaignId) {
    return connectToCampaign(agentUser, agentPass, phoneLogin, "anexo_" + phoneLogin, campaignId, null, null);
  }

  public VicidialHttpResult connectToCampaign(
      String agentUser,
      String agentPass,
      String phoneLogin,
      String phonePass,
      String campaignId,
      Integer browserWidth,
      Integer browserHeight
  ) {
    configService.assertVicidialApiConfigured();
    var s = configService.resolve();
    if (!StringUtils.hasText(s.baseUrl())) {
      throw new VicidialServiceException(HttpStatus.BAD_REQUEST,
          "VICIDIAL_CONFIG_MISSING",
          "No hay baseUrl de Vicidial configurada para conectar campaña.");
    }

    var params = buildCampaignConnectParams(agentUser, agentPass, phoneLogin, phonePass, campaignId, browserWidth, browserHeight);
    long startedAt = System.nanoTime();
    VicidialHttpResult result = executePostWithCookies(s.baseUrl(), "/agc/vicidial.php", params, agentUser);
    long durationMs = (System.nanoTime() - startedAt) / 1_000_000;

    if (result.statusCode() >= 400) {
      throw new VicidialServiceException(HttpStatus.BAD_GATEWAY,
          "VICIDIAL_HTTP_ERROR",
          "Vicidial devolvió un estado HTTP de error al conectar campaña.",
          "Verifique endpoint /agc/vicidial.php y disponibilidad del servidor Vicidial.",
          Map.of("status", result.statusCode()));
    }

    String body = Objects.toString(result.body(), "");
    ConnectOutcome outcome = evaluateCampaignConnectBody(body);
    String classification = outcome.name();
    String errorTextSnippet = extractVisibleErrorText(body);
    debugConnectAttempt(s.baseUrl(), agentUser, phoneLogin, campaignId, result.statusCode(), durationMs, classification, result.snippet(400), params);

    Map<String, Object> details = new LinkedHashMap<>();
    details.put("httpStatus", result.statusCode());
    details.put("classification", classification);
    if (vicidialDebug && StringUtils.hasText(errorTextSnippet)) {
      details.put("errorTextSnippet", errorTextSnippet);
    }
    if (vicidialDebug) {
      details.put("rawSnippet", result.snippet(600));
    }
    if (vicidialDebug) {
      details.put("debugRequest", buildDebugRequest(s.baseUrl(), params));
    }

    if (outcome == ConnectOutcome.INVALID_CREDENTIALS) {
      throw new VicidialServiceException(HttpStatus.BAD_REQUEST,
          "VICIDIAL_INVALID_CREDENTIALS",
          "Vicidial rechazó las credenciales del agente para conectar campaña.",
          "Revise agent_user/agent_pass en Vicidial.",
          details);
    }
    if (outcome == ConnectOutcome.PHONE_INVALID) {
      throw new VicidialServiceException(HttpStatus.BAD_REQUEST,
          "VICIDIAL_PHONE_INVALID",
          "Vicidial rechazó el anexo o clave de teléfono del agente.",
          "Revise phone_login/phone_pass y estado del phone en Vicidial.",
          details);
    }
    if (outcome == ConnectOutcome.CAMPAIGN_NOT_ASSIGNED) {
      throw new VicidialServiceException(HttpStatus.BAD_REQUEST,
          "VICIDIAL_CAMPAIGN_NOT_ASSIGNED",
          "La campaña no está asignada o disponible para el agente en Vicidial.",
          "Valide que el agente tenga acceso a la campaña y que esté habilitada.",
          details);
    }
    if (outcome == ConnectOutcome.NO_LEADS) {
      throw new VicidialServiceException(HttpStatus.CONFLICT,
          "VICIDIAL_NO_LEADS",
          "La campaña no tiene leads disponibles (hopper vacío).",
          "Cargue base/leads en la campaña o verifique listas/filtros activos en Vicidial.",
          Map.of("campaignId", campaignId, "httpStatus", result.statusCode()));
    }
    if (outcome == ConnectOutcome.GENERIC_ERROR) {
      throw new VicidialServiceException(HttpStatus.BAD_REQUEST,
          "VICIDIAL_CAMPAIGN_CONNECT_FAILED",
          "Vicidial devolvió un error al conectar campaña.",
          "Revise permisos del user en Vicidial, campaña asignada y phone_login válido.",
          details);
    }
    if (outcome == ConnectOutcome.STILL_LOGIN_PAGE) {
      throw new VicidialServiceException(HttpStatus.BAD_REQUEST,
          "VICIDIAL_CAMPAIGN_CONNECT_FAILED",
          "No fue posible confirmar conexión de campaña en Vicidial.",
          "Vicidial devolvió pantalla de login/relogin.",
          details);
    }
    if (outcome != ConnectOutcome.SUCCESS) {
      throw new VicidialServiceException(HttpStatus.BAD_REQUEST,
          "VICIDIAL_CAMPAIGN_CONNECT_FAILED",
          "No fue posible confirmar conexión de campaña en Vicidial.",
          "Revise permisos del user en Vicidial, campaña asignada, phone_login válido y respuesta HTML AGC.",
          details);
    }
    return result;
  }

  Map<String, String> buildCampaignConnectParams(
      String agentUser,
      String agentPass,
      String phoneLogin,
      String phonePass,
      String campaignId,
      Integer browserWidth,
      Integer browserHeight
  ) {
    var params = new LinkedHashMap<String, String>();
    params.put("DB", "0");
    params.put("JS_browser_height", String.valueOf(browserHeight == null || browserHeight <= 0 ? 641 : browserHeight));
    params.put("JS_browser_width", String.valueOf(browserWidth == null || browserWidth <= 0 ? 695 : browserWidth));
    params.put("phone_login", phoneLogin);
    params.put("phone_pass", phonePass);
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
    String text = normalize(body);
    return text.contains("login incorrect")
        || text.contains("error: invalid username/password")
        || text.contains("invalid username/password")
        || text.contains("invalid password");
  }

  boolean isAgentScreen(String body) {
    String text = normalize(body);
    int markersAgent = 0;
    if (text.contains("agc_main.php")) markersAgent++;
    if (text.contains("vdc_db_query.php")) markersAgent++;
    if (text.contains("session_name")) markersAgent++;
    if (text.contains("server_ip")) markersAgent++;
    if (text.contains("campaign") && text.contains("phone_login")) markersAgent++;
    if (text.contains("closer") || text.contains("inbound") || text.contains("dialnext") || text.contains("pause code") || text.contains("dispo")) markersAgent++;
    return markersAgent >= 2;
  }

  private boolean containsAnyError(String body) {
    return Objects.toString(body, "").contains("ERROR:");
  }

  boolean containsLoginForm(String body) {
    String text = Objects.toString(body, "").toLowerCase();
    return text.contains("name=\"vd_login\"")
        || text.contains("id=\"vd_login\"")
        || text.contains("name=\"vd_pass\"")
        || text.contains("id=\"vd_pass\"")
        || text.contains("please login")
        || text.contains("agent login")
        || text.contains("re-login");
  }

  ConnectOutcome evaluateCampaignConnectBody(String body) {
    String normalized = normalize(body);
    if (containsInvalidCredentials(normalized)) {
      return ConnectOutcome.INVALID_CREDENTIALS;
    }
    if (containsPhoneInvalid(normalized)) {
      return ConnectOutcome.PHONE_INVALID;
    }
    if (containsCampaignNotAssigned(normalized)) {
      return ConnectOutcome.CAMPAIGN_NOT_ASSIGNED;
    }
    if (containsNoLeadsInHopper(normalized)) {
      return ConnectOutcome.NO_LEADS;
    }
    if (containsLoginForm(body)) {
      return ConnectOutcome.STILL_LOGIN_PAGE;
    }
    if (isAgentScreen(body)) {
      return ConnectOutcome.SUCCESS;
    }
    if (containsAnyError(body)) {
      return ConnectOutcome.GENERIC_ERROR;
    }
    return ConnectOutcome.UNKNOWN;
  }

  ActiveLeadOutcome evaluateActiveLeadBody(String body) {
    String normalized = normalize(body);
    if (containsLoginForm(body)) {
      return ActiveLeadOutcome.RELOGIN_REQUIRED;
    }
    if (normalized.contains("no active lead")
        || normalized.contains("there are no leads in the hopper")
        || normalized.contains("no leads in the hopper")
        || normalized.contains("no lead")) {
      return ActiveLeadOutcome.NO_ACTIVE_LEAD;
    }
    if (normalized.contains("lead_id=")) {
      return ActiveLeadOutcome.SUCCESS;
    }
    return ActiveLeadOutcome.UNKNOWN;
  }

  private boolean containsNoLeadsInHopper(String normalizedBody) {
    return normalizedBody.contains("no leads in the hopper")
        || normalizedBody.contains("there are no leads in the hopper for this campaign");
  }

  private boolean containsPhoneInvalid(String normalizedBody) {
    return normalizedBody.contains("invalid phone login")
        || normalizedBody.contains("invalid phone")
        || normalizedBody.contains("phone is not active")
        || normalizedBody.contains("phone login is not valid")
        || normalizedBody.contains("phone code is not valid")
        || normalizedBody.contains("invalid extension")
        || normalizedBody.contains("invalid station")
        || normalizedBody.contains("phone_login is not valid");
  }

  private String extractVisibleErrorText(String body) {
    String noScripts = Objects.toString(body, "")
        .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
        .replaceAll("(?is)<style[^>]*>.*?</style>", " ");
    String text = noScripts.replaceAll("(?is)<[^>]+>", " ")
        .replaceAll("&nbsp;", " ")
        .replaceAll("\\s+", " ")
        .trim();
    if (!StringUtils.hasText(text)) {
      return "";
    }
    return text.substring(0, Math.min(text.length(), 240));
  }

  private boolean containsCampaignNotAssigned(String normalizedBody) {
    return normalizedBody.contains("campaign not allowed")
        || normalizedBody.contains("no campaign selected")
        || normalizedBody.contains("is not in your user group allowed campaigns")
        || normalizedBody.contains("not active on campaign");
  }

  private String normalize(String body) {
    return Pattern.compile("\\s+").matcher(Objects.toString(body, "").toLowerCase()).replaceAll(" ");
  }

  private VicidialHttpResult executePostWithCookies(String baseUrl, String path, Map<String, String> params, String agentUser) {
    CookieStore cookieStore = cookieStoreFor(agentUser);
    RequestConfig requestConfig = RequestConfig.custom()
        .setConnectTimeout(Timeout.ofMilliseconds(connectTimeout.toMillis()))
        .setConnectionRequestTimeout(Timeout.ofMilliseconds(writeTimeout.toMillis()))
        .setResponseTimeout(Timeout.ofMilliseconds(readTimeout.toMillis()))
        .build();

    try (CloseableHttpClient httpClient = HttpClients.custom()
        .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create().build())
        .setDefaultCookieStore(cookieStore)
        .setDefaultRequestConfig(requestConfig)
        .setRedirectStrategy(DefaultRedirectStrategy.INSTANCE)
        .build()) {

      HttpPost request = new HttpPost(resolveUri(baseUrl, path));
      request.setHeader("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
      request.setHeader("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36");
      request.setHeader("Referer", normalizeBaseUrl(baseUrl) + "/agc/vicidial.php");
      request.setHeader("Accept", "text/html,*/*");
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

  private String normalizeBaseUrl(String baseUrl) {
    if (baseUrl == null) {
      return "";
    }
    return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
  }

  private Map<String, Object> buildDebugRequest(String baseUrl, Map<String, String> params) {
    Map<String, Object> debug = new LinkedHashMap<>();
    debug.put("url", normalizeBaseUrl(baseUrl) + "/agc/vicidial.php");
    debug.put("method", "POST");
    debug.put("formParams", maskSensitiveParams(params));
    debug.put("headers", Map.of(
        "Content-Type", "application/x-www-form-urlencoded; charset=UTF-8",
        "User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36",
        "Referer", normalizeBaseUrl(baseUrl) + "/agc/vicidial.php",
        "Accept", "text/html,*/*"
    ));
    return debug;
  }

  private Map<String, String> maskSensitiveParams(Map<String, String> params) {
    return params.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .collect(Collectors.toMap(
            Map.Entry::getKey,
            e -> isSensitiveField(e.getKey()) ? "***" : Objects.toString(e.getValue(), ""),
            (left, right) -> left,
            LinkedHashMap::new
        ));
  }

  private boolean isSensitiveField(String key) {
    return Objects.toString(key, "").toLowerCase().contains("pass");
  }

  private void debugConnectAttempt(String baseUrl, String agentUser, String phoneLogin, String campaignId, int statusCode, long durationMs, String classification, String snippet, Map<String, String> params) {
    if (!vicidialDebug) {
      return;
    }
    Map<String, String> safeParams = maskSensitiveParams(params);
    String encoded = safeParams.entrySet().stream()
        .map(e -> java.net.URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + java.net.URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
        .collect(Collectors.joining("&"));
    log.info("Vicidial connect debug method=POST url={} endpoint=/agc/vicidial.php agentUser={} phoneLogin={} campaignId={} status={} classification={} durationMs={} form={} snippet={}",
        normalizeBaseUrl(baseUrl) + "/agc/vicidial.php",
        maskAgentUser(agentUser),
        phoneLogin,
        campaignId,
        statusCode,
        classification,
        durationMs,
        encoded,
        snippet);
  }

  private String maskAgentUser(String agentUser) {
    if (!StringUtils.hasText(agentUser)) {
      return "***";
    }
    if (agentUser.length() <= 2) {
      return "**";
    }
    return agentUser.substring(0, 2) + "***";
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
      return snippet(200);
    }

    public String snippet(int maxLength) {
      String safeBody = Optional.ofNullable(body).orElse("");
      return safeBody.substring(0, Math.min(safeBody.length(), Math.max(maxLength, 0)));
    }
  }

  enum ConnectOutcome {
    SUCCESS,
    INVALID_CREDENTIALS,
    PHONE_INVALID,
    CAMPAIGN_NOT_ASSIGNED,
    NO_LEADS,
    STILL_LOGIN_PAGE,
    GENERIC_ERROR,
    UNKNOWN
  }

  public enum ActiveLeadOutcome {
    SUCCESS,
    NO_ACTIVE_LEAD,
    RELOGIN_REQUIRED,
    UNKNOWN
  }

  public record CampaignDialConfig(Optional<String> dialMethod, Optional<Double> autoDialLevel) {}

  public record ActiveLeadResult(ActiveLeadOutcome outcome, String rawBody, int httpStatus) {}

  public enum ManualDialOutcome {
    SUCCESS,
    RELOGIN_REQUIRED,
    INVALID_CREDENTIALS,
    PERMISSION_DENIED,
    FAILED,
    UNKNOWN
  }

  private record CookieSession(CookieStore cookieStore, Instant expiresAt) {
  }
}
