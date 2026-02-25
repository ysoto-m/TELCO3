package com.telco3.agentui.agent;

import com.telco3.agentui.vicidial.VicidialClient;
import com.telco3.agentui.vicidial.VicidialCampaignParser;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AgentVicidialSessionService {
  private static final Logger log = LoggerFactory.getLogger(AgentVicidialSessionService.class);

  private final VicidialSessionClient sessionClient;
  private final VicidialClient vicidialClient;
  private final VicidialCredentialService credentialService;
  private final VicidialCampaignParser campaignParser;
  private final VicidialDiagnosticsService diagnosticsService;
  private final Environment environment;
  private final Map<String, AgentVicidialState> stateByAgent = new ConcurrentHashMap<>();

  public AgentVicidialSessionService(VicidialSessionClient sessionClient, VicidialClient vicidialClient, VicidialCredentialService credentialService, VicidialCampaignParser campaignParser, VicidialDiagnosticsService diagnosticsService, Environment environment) {
    this.sessionClient = sessionClient;
    this.vicidialClient = vicidialClient;
    this.credentialService = credentialService;
    this.campaignParser = campaignParser;
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

    long startedAt = System.nanoTime();
    var result = vicidialClient.campaignsForAgent(credentials.agentUser(), credentials.agentPass());
    long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
    debugCampaignCall(credentials.agentUser(), result.statusCode(), elapsedMs, result.snippet(), credentials.fallbackUsed());

    if (result.body().contains("ERROR: Invalid Username/Password")) {
      throw new VicidialServiceException(
          HttpStatus.BAD_REQUEST,
          "VICIDIAL_INVALID_CREDENTIALS",
          "Vicidial rechazó user/pass del agente para listar campañas.",
          "Verifique usuario y clave del agente en Vicidial (sin usar phone_login/phone_pass para este flujo).",
          Map.of(
              "agentUser", mask(credentials.agentUser()),
              "statusCode", result.statusCode(),
              "rawSnippet", result.snippet()
          )
      );
    }

    List<VicidialCampaignParser.CampaignOption> campaignOptions = campaignParser.parseCampaignOptions(result.body());
    if (campaignOptions.isEmpty()) {
      throw new VicidialServiceException(
          HttpStatus.BAD_REQUEST,
          "VICIDIAL_EMPTY_CAMPAIGNS",
          "Vicidial respondió sin opciones de campaña en ACTION=LogiNCamPaigns.",
          "Revise permisos de campañas del agente (user_group/user_level) o parámetros enviados (ACTION/format).",
          Map.of(
              "agentUser", mask(credentials.agentUser()),
              "statusCode", result.statusCode(),
              "rawSnippet", result.snippet()
          )
      );
    }

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("phoneLogin", state.phoneLogin);
    response.put("campaigns", campaignOptions.stream().map(VicidialCampaignParser.CampaignOption::value).toList());
    response.put("campaignOptions", campaignOptions);
    response.put("raw", result.body());
    if (credentials.fallbackUsed()) {
      response.put("warning", credentials.warning());
    }
    return response;
  }

  public Map<String, Object> connectCampaign(String agentUser, String campaignId, String mode, boolean remember) {
    AgentVicidialState state = requirePhoneConnected(agentUser);

    String agentPass = credentialService.resolveAgentPass(agentUser)
        .orElseThrow(() -> new VicidialServiceException(
            HttpStatus.BAD_REQUEST,
            "VICIDIAL_AGENT_CREDENTIALS_MISSING",
            "Falta agent_pass del agente en tabla users. El administrador debe completarlo para continuar.",
            "Actualice users.agent_pass_encrypted para el usuario autenticado.",
            Map.of("agentUser", mask(agentUser), "phoneLogin", state.phoneLogin)
        ));

    long startedAt = System.nanoTime();
    String phonePass = buildPhonePass(state.phoneLogin);
    var result = vicidialClient.connectToCampaign(agentUser, agentPass, state.phoneLogin, phonePass, campaignId, null, null);
    long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
    debugConnectCall(agentUser, state.phoneLogin, campaignId, result.statusCode(), elapsedMs, result.snippet());

    state.campaign = campaignId;
    state.mode = mode;
    credentialService.saveLastSelection(agentUser, state.phoneLogin, campaignId, remember);
    credentialService.markConnected(agentUser, state.phoneLogin, campaignId);
    RuntimeSessionFields runtime = extractRuntimeSessionFields(result.body());
    credentialService.updateRuntimeSession(
        agentUser,
        runtime.sessionName(),
        runtime.serverIp(),
        runtime.confExten(),
        runtime.extension(),
        runtime.protocol(),
        runtime.agentLogId()
    );

    return Map.of(
        "ok", true,
        "campaign", campaignId,
        "campaignId", campaignId,
        "mode", mode,
        "phoneLogin", state.phoneLogin,
        "raw", result.body()
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

  private void debugConnectCall(String agentUser, String phoneLogin, String campaignId, int statusCode, long durationMs, String snippet) {
    if (!isDevEnvironment()) {
      return;
    }
    log.info(
        "Vicidial campaign connect debug endpoint=/agc/vicidial.php agentUser={} phoneLogin={} campaignId={} status={} durationMs={} snippet={}",
        mask(agentUser),
        phoneLogin,
        campaignId,
        statusCode,
        durationMs,
        snippet
    );
  }

  private void debugCampaignCall(String agentUser, int statusCode, long durationMs, String snippet, boolean fallbackUsed) {
    if (!isDevEnvironment()) {
      return;
    }
    log.info(
        "Vicidial campaigns debug endpoint=/agc/vdc_db_query.php action=LogiNCamPaigns format=html agentUser={} status={} durationMs={} snippet={} fallbackUsed={}",
        mask(agentUser),
        statusCode,
        durationMs,
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
    if (value.length() <= 4) return "****";
    return value.substring(0, 4) + "****";
  }

  static class AgentVicidialState {
    boolean phoneConnected;
    String phoneLogin;
    String campaign;
    String mode;
  }

  private RuntimeSessionFields extractRuntimeSessionFields(String rawBody) {
    String safeBody = Objects.toString(rawBody, "");
    String sessionName = extractJsVar(safeBody, "session_name");
    String serverIp = extractJsVar(safeBody, "server_ip");
    String confExten = extractJsVar(safeBody, "conf_exten");
    String extension = extractJsVar(safeBody, "extension");
    String protocol = extractJsVar(safeBody, "protocol");
    Long agentLogId = parseLong(extractJsVar(safeBody, "agent_log_id"));
    return new RuntimeSessionFields(sessionName, serverIp, confExten, extension, protocol, agentLogId);
  }

  private String extractJsVar(String html, String field) {
    Pattern quoted = Pattern.compile("(?:var\\s+)?" + Pattern.quote(field) + "\\s*=\\s*['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE);
    Matcher quotedMatcher = quoted.matcher(html);
    if (quotedMatcher.find()) {
      return quotedMatcher.group(1).trim();
    }
    Pattern unquoted = Pattern.compile("(?:var\\s+)?" + Pattern.quote(field) + "\\s*=\\s*([0-9]+)", Pattern.CASE_INSENSITIVE);
    Matcher unquotedMatcher = unquoted.matcher(html);
    if (unquotedMatcher.find()) {
      return unquotedMatcher.group(1).trim();
    }
    return null;
  }

  private Long parseLong(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private record RuntimeSessionFields(String sessionName, String serverIp, String confExten, String extension, String protocol, Long agentLogId) {
  }
}
