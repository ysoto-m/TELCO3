package com.telco3.agentui.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telco3.agentui.domain.*;
import com.telco3.agentui.domain.Entities.*;
import com.telco3.agentui.vicidial.VicidialClient;
import com.telco3.agentui.vicidial.VicidialServiceException;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

@RestController @RequestMapping("/api/agent")
public class AgentController {
  private final VicidialClient vicidial;
  private final InteractionRepository interactions;
  private final CustomerRepository customers;
  private final CustomerPhoneRepository phones;
  private final VicidialCredentialService credentialService;
  private final AgentVicidialSessionService vicidialSessionService;
  private final UserRepository userRepository;
  private final AgentVicidialCredentialRepository agentVicidialCredentialRepository;
  private final boolean vicidialDebug;
  private final ObjectMapper mapper = new ObjectMapper();

  public AgentController(
      VicidialClient vicidial,
      InteractionRepository interactions,
      CustomerRepository customers,
      CustomerPhoneRepository phones,
      VicidialCredentialService credentialService,
      AgentVicidialSessionService vicidialSessionService,
      UserRepository userRepository,
      AgentVicidialCredentialRepository agentVicidialCredentialRepository,
      @Value("${app.vicidial.debug:false}") boolean vicidialDebug
  ){
    this.vicidial=vicidial;
    this.interactions=interactions;
    this.customers=customers;
    this.phones=phones;
    this.credentialService = credentialService;
    this.vicidialSessionService = vicidialSessionService;
    this.userRepository = userRepository;
    this.agentVicidialCredentialRepository = agentVicidialCredentialRepository;
    this.vicidialDebug = vicidialDebug;
  }

  public record AgentProfileResponse(boolean hasAgentPass, String lastPhoneLogin, String lastCampaign, boolean rememberCredentials, boolean connected, String connectedPhoneLogin, String connectedCampaign, String agentUser) {}
  public record UpdateAgentPassReq(@NotBlank String agentPass) {}
  public record PhoneConnectReq(@NotBlank String phoneLogin) {}
  public record CampaignConnectReq(@NotBlank String campaignId, String mode, Boolean rememberCredentials) {}
  public record DialNextReq(@NotBlank String campaignId, String mode) {}
  public record ManualDialReq(@NotBlank String campaignId, @NotBlank String phoneNumber, String phoneCode, Integer dialTimeout, String dialPrefix, String preview) {}
  public record InteractionReq(@NotBlank String mode,Long leadId,@NotBlank String phoneNumber,@NotBlank String campaign,@NotBlank String dni,@NotBlank String dispo,String notes,Map<String,Object> extra){}

  @GetMapping("/profile")
  AgentProfileResponse profile(Authentication auth) {
    var state = credentialService.getProfile(requireAuth(auth));
    return new AgentProfileResponse(state.hasAgentPass(), state.lastPhoneLogin(), state.lastCampaign(), state.rememberCredentials(), state.connected(), state.connectedPhoneLogin(), state.connectedCampaign(), state.agentUser());
  }

  @PutMapping("/profile/agent-pass")
  Map<String, Object> updateOwnAgentPass(@RequestBody UpdateAgentPassReq req, Authentication auth) throws Exception {
    credentialService.updateAgentPass(requireAuth(auth), req.agentPass());
    return Map.of("ok", true);
  }

  @PostMapping("/vicidial/phone/connect")
  Map<String, Object> connectPhone(@RequestBody PhoneConnectReq req, Authentication auth) {
    String username = requireAuth(auth);
    return vicidialSessionService.connectPhone(username, req.phoneLogin());
  }

  @PostMapping("/vicidial/phone/disconnect")
  Map<String, Object> disconnectPhone(Authentication auth) {
    String username = requireAuth(auth);
    return vicidialSessionService.disconnectPhone(username);
  }

  @GetMapping("/vicidial/campaigns")
  Map<String, Object> campaigns(Authentication auth) {
    String username = requireAuth(auth);
    return vicidialSessionService.listCampaigns(username);
  }

  @PostMapping("/vicidial/campaign/connect")
  Map<String, Object> connectCampaign(@RequestBody CampaignConnectReq req, Authentication auth) {
    String username = requireAuth(auth);
    String mode = req.mode() == null || req.mode().isBlank() ? "predictive" : req.mode().toLowerCase(Locale.ROOT);
    boolean remember = req.rememberCredentials() == null || req.rememberCredentials();
    return vicidialSessionService.connectCampaign(username, req.campaignId(), mode, remember);
  }

  @GetMapping("/vicidial/status")
  Map<String, Object> status(Authentication auth) {
    return vicidialSessionService.status(requireAuth(auth));
  }

  @GetMapping("/active-lead")
  Map<String,Object> active(Authentication auth){
    String agentUser = requireAuth(auth);
    ensureAgentExists(agentUser);
    var session = requireConnectedSession(agentUser);
    requireSessionField(session.connectedPhoneLogin, "phone_login");
    requireSessionField(session.connectedCampaign, "campaign");

    var leadResult = vicidial.activeLeadSafe(agentUser);
    String raw = Objects.toString(leadResult.rawBody(), "");
    String rawSnippet = raw.substring(0, Math.min(raw.length(), 800));
    if (leadResult.outcome() == VicidialClient.ActiveLeadOutcome.RELOGIN_REQUIRED) {
      throw new VicidialServiceException(HttpStatus.CONFLICT,
          "VICIDIAL_RELOGIN_REQUIRED",
          "La sesión de Vicidial requiere re-login.",
          "Conecte anexo/campaña nuevamente para continuar.",
          buildActiveLeadDetails("RELOGIN_REQUIRED", leadResult.httpStatus(), rawSnippet, agentUser));
    }

    if (leadResult.outcome() == VicidialClient.ActiveLeadOutcome.NO_ACTIVE_LEAD || leadResult.outcome() == VicidialClient.ActiveLeadOutcome.UNKNOWN) {
      Map<String, Object> details = buildActiveLeadDetails("NO_ACTIVE_LEAD", leadResult.httpStatus(), rawSnippet, agentUser);
      return businessNoLeadResponse(details);
    }

    Long leadId = extractLong(raw, "lead_id");
    if (leadId == null) {
      return businessNoLeadResponse(buildActiveLeadDetails("PARSE_EMPTY_LEAD", leadResult.httpStatus(), rawSnippet, agentUser));
    }
    Map<String, Object> lead = new LinkedHashMap<>();
    lead.put("leadId", leadId);
    lead.put("phoneNumber", extract(raw, "phone_number"));
    lead.put("campaign", extract(raw, "campaign"));
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("ok", true);
    response.put("lead", lead);
    if (vicidialDebug) {
      response.put("rawSnippet", rawSnippet);
    }
    return response;
  }

  @PostMapping("/vicidial/dial/next")
  Map<String, Object> dialNext(@RequestBody DialNextReq req, Authentication auth) {
    String agentUser = requireAuth(auth);
    ensureAgentExists(agentUser);
    var session = requireConnectedSession(agentUser);
    requireSessionField(session.connectedPhoneLogin, "phone_login");
    requireSessionField(session.connectedCampaign, "campaign");

    var result = vicidial.externalDialManualNext(agentUser);
    String raw = Objects.toString(result.body(), "");
    String normalized = raw.toUpperCase(Locale.ROOT);
    String rawSnippet = raw.substring(0, Math.min(raw.length(), 800));

    if (normalized.contains("ERROR: AGENT_USER IS NOT LOGGED IN") || normalized.contains("RE-LOGIN")) {
      throw new VicidialServiceException(HttpStatus.CONFLICT,
          "VICIDIAL_RELOGIN_REQUIRED",
          "La sesión de Vicidial requiere re-login.",
          "Conecte anexo/campaña nuevamente para continuar.",
          buildManualNextDetails(result.statusCode(), rawSnippet, agentUser));
    }
    if (normalized.contains("ERROR: AUTH USER DOES NOT HAVE PERMISSION")) {
      throw new VicidialServiceException(HttpStatus.FORBIDDEN,
          "VICIDIAL_PERMISSION_DENIED",
          "El usuario API de Vicidial no tiene permisos para external_dial.",
          "Habilite permisos AGENT API (external_dial) para el API user en Vicidial.",
          buildManualNextDetails(result.statusCode(), rawSnippet, agentUser));
    }
    if (normalized.contains("NO LEADS IN THE HOPPER")) {
      throw new VicidialServiceException(HttpStatus.CONFLICT,
          "VICIDIAL_NO_LEADS",
          "No hay leads disponibles en el hopper para este agente.",
          "Evite reintentos automáticos agresivos; cargue leads o revise filtros.",
          buildManualNextDetails(result.statusCode(), rawSnippet, agentUser));
    }
    if (!normalized.contains("SUCCESS")) {
      throw new VicidialServiceException(HttpStatus.BAD_GATEWAY,
          "VICIDIAL_MANUAL_NEXT_FAILED",
          "Vicidial no confirmó la marcación manual siguiente.",
          "Revise la sesión del agente y permisos AGENT API en Vicidial.",
          buildManualNextDetails(result.statusCode(), rawSnippet, agentUser));
    }

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("ok", true);
    response.put("result", Map.of("code", "VICIDIAL_DIAL_NEXT_OK", "campaign", req.campaignId(), "mode", Objects.toString(req.mode(), "manual")));
    if (vicidialDebug) {
      response.put("rawSnippet", rawSnippet);
    }
    return response;
  }

  @PostMapping("/vicidial/manual/next")
  Map<String, Object> backwardCompatibleDialNext(@RequestBody DialNextReq req, Authentication auth) {
    return dialNext(req, auth);
  }

  @PostMapping("/vicidial/dial/manual")
  Map<String, Object> manualDial(@RequestBody ManualDialReq req, Authentication auth) {
    String agentUser = requireAuth(auth);
    ensureAgentExists(agentUser);
    var session = requireConnectedSession(agentUser);
    String agentPass = credentialService.resolveAgentPass(agentUser)
        .orElseThrow(() -> new VicidialServiceException(HttpStatus.BAD_REQUEST,
            "VICIDIAL_AGENT_CREDENTIALS_MISSING",
            "No existe agent_pass configurado para el agente.",
            "Actualice users.agent_pass_encrypted antes de usar marcación manual.",
            null));

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

    String phoneCode = defaultIfBlank(req.phoneCode(), "51");
    String dialPrefix = defaultIfBlank(req.dialPrefix(), "9");
    int dialTimeout = req.dialTimeout() == null || req.dialTimeout() <= 0 ? 60 : req.dialTimeout();
    String preview = "YES".equalsIgnoreCase(req.preview()) ? "YES" : "NO";

    LinkedHashMap<String, String> payload = new LinkedHashMap<>();
    payload.put("ACTION", "manDiaLnextCaLL");
    payload.put("server_ip", session.serverIp);
    payload.put("session_name", session.sessionName);
    payload.put("user", agentUser);
    payload.put("pass", agentPass);
    payload.put("campaign", defaultIfBlank(req.campaignId(), session.connectedCampaign));
    payload.put("conf_exten", defaultIfBlank(session.confExten, session.connectedPhoneLogin));
    payload.put("exten", session.connectedPhoneLogin);
    payload.put("phone_login", session.connectedPhoneLogin);
    payload.put("agent_log_id", String.valueOf(session.agentLogId));
    payload.put("phone_code", phoneCode);
    payload.put("phone_number", req.phoneNumber());
    payload.put("dial_timeout", String.valueOf(dialTimeout));
    payload.put("dial_prefix", dialPrefix);
    payload.put("preview", preview);
    payload.put("list_id", "");
    payload.put("channel", buildChannel(session));

    var result = vicidial.manualDialNextCall(payload);
    String raw = Objects.toString(result.body(), "");
    String rawSnippet = raw.substring(0, Math.min(raw.length(), 800));
    VicidialClient.ManualDialOutcome outcome = vicidial.evaluateManualDialBody(raw);

    if (outcome == VicidialClient.ManualDialOutcome.RELOGIN_REQUIRED) {
      throw new VicidialServiceException(HttpStatus.CONFLICT, "VICIDIAL_RELOGIN_REQUIRED",
          "La sesión de Vicidial requiere re-login.",
          "Conecte anexo/campaña nuevamente para continuar.",
          buildManualDialDetails(result.statusCode(), rawSnippet, payload));
    }
    if (outcome == VicidialClient.ManualDialOutcome.INVALID_CREDENTIALS) {
      throw new VicidialServiceException(HttpStatus.BAD_REQUEST, "VICIDIAL_INVALID_CREDENTIALS",
          "Vicidial rechazó las credenciales de agente para la marcación manual.",
          "Verifique usuario/agent_pass del agente.",
          buildManualDialDetails(result.statusCode(), rawSnippet, payload));
    }
    if (outcome == VicidialClient.ManualDialOutcome.PERMISSION_DENIED) {
      throw new VicidialServiceException(HttpStatus.FORBIDDEN, "VICIDIAL_PERMISSION_DENIED",
          "Vicidial denegó permisos para marcación manual.",
          "Revise user_level/user_group y permisos de campaña para manual dial.",
          buildManualDialDetails(result.statusCode(), rawSnippet, payload));
    }
    if (outcome == VicidialClient.ManualDialOutcome.FAILED || outcome == VicidialClient.ManualDialOutcome.UNKNOWN) {
      throw new VicidialServiceException(HttpStatus.BAD_GATEWAY, "VICIDIAL_MANUAL_DIAL_FAILED",
          "Vicidial no confirmó la marcación manual.",
          "Valide parámetros de sesión (session_name/server_ip/agent_log_id) y estado del agente.",
          buildManualDialDetails(result.statusCode(), rawSnippet, payload));
    }

    Map<String, String> parsed = vicidial.parseKeyValueLines(raw);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("ok", true);
    response.put("callId", firstPresent(parsed, "call_id", "callid", "callerid"));
    response.put("leadId", toLong(firstPresent(parsed, "lead_id", "leadid")));
    response.put("status", firstPresent(parsed, "status", "result"));
    if (vicidialDebug) {
      response.put("rawSnippet", rawSnippet);
      response.put("details", Map.of("debugRequest", maskPayload(payload)));
    }
    return response;
  }

  @GetMapping("/context")
  Map<String,Object> context(Authentication auth,@RequestParam(required=false) Long leadId,@RequestParam(required=false) String mode){
    String agentUser = requireAuth(auth);
    ensureAgentExists(agentUser);
    var session = requireConnectedSession(agentUser);
    requireSessionField(session.connectedPhoneLogin, "phone_login");
    requireSessionField(session.connectedCampaign, "campaign");

    Long resolvedLeadId = leadId;
    if (resolvedLeadId == null) {
      var leadResult = vicidial.activeLeadSafe(agentUser);
      if (leadResult.outcome() == VicidialClient.ActiveLeadOutcome.RELOGIN_REQUIRED) {
        throw new VicidialServiceException(HttpStatus.CONFLICT,
            "VICIDIAL_RELOGIN_REQUIRED",
            "La sesión de Vicidial requiere re-login.",
            "Conecte anexo/campaña nuevamente para continuar.",
            null);
      }
      if (leadResult.outcome() != VicidialClient.ActiveLeadOutcome.SUCCESS) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("context", contextPayload(mode, session.connectedCampaign, session.connectedPhoneLogin, agentUser));
        response.put("lead", null);
        return response;
      }
      resolvedLeadId = extractLong(leadResult.rawBody(), "lead_id");
    }

    if (resolvedLeadId == null) {
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("ok", true);
      response.put("context", contextPayload(mode, session.connectedCampaign, session.connectedPhoneLogin, agentUser));
      response.put("lead", null);
      return response;
    }

    String data = vicidial.leadInfo(resolvedLeadId);
    String dni = extract(data,"vendor_lead_code");
    if (dni == null || dni.isBlank()) {
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("ok", true);
      response.put("context", contextPayload(mode, session.connectedCampaign, session.connectedPhoneLogin, agentUser));
      response.put("lead", Map.of("leadId", resolvedLeadId));
      response.put("customer", null);
      response.put("phones", List.of());
      response.put("interactions", List.of());
      response.put("dispoOptions", List.of("SALE", "NOANS", "CALLBK", "DNC"));
      response.put("mode", Objects.toString(mode, "predictive"));
      return response;
    }
    var c = customers.findByDni(dni).orElseGet(()->{var nc=new CustomerEntity(); nc.dni=dni; nc.firstName="TODO"; nc.lastName="TODO"; return nc;});
    List<Map<String,Object>> ph =
    c.id == null
        ? List.<Map<String, Object>>of()
        : phones.findByCustomerId(c.id).stream()
            .map(p -> Map.<String, Object>of(
                "phoneNumber", p.phoneNumber,
                "isPrimary", p.isPrimary
            ))
            .toList();
    var hist =
    interactions.findTop20ByDniOrderByCreatedAtDesc(dni).stream()
        .map(i -> Map.<String, Object>of(
            "id", i.id,
            "dispo", i.dispo,
            "notes", Objects.toString(i.notes, ""),
            "createdAt", i.createdAt,
            "syncStatus", i.syncStatus.name()
        ))
        .toList();
    Map<String, Object> lead = new LinkedHashMap<>();
    lead.put("leadId", resolvedLeadId);
    lead.put("phoneNumber", extract(data, "phone_number"));
    lead.put("campaign", extract(data, "campaign_id"));
    lead.put("dni", dni);

    Map<String, Object> customer = new LinkedHashMap<>();
    customer.put("dni", dni);
    customer.put("firstName", Objects.toString(c.firstName, "TODO"));
    customer.put("lastName", Objects.toString(c.lastName, "TODO"));

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("lead", lead);
    response.put("customer", customer);
    response.put("phones", ph);
    response.put("interactions", hist);
    response.put("dispoOptions", List.of("SALE", "NOANS", "CALLBK", "DNC"));
    response.put("mode", Objects.toString(mode, "predictive"));
    return response;
  }

  @PostMapping("/interactions")
  Map<String,Object> save(@RequestBody InteractionReq req, Authentication auth) throws Exception {
    String agentUser = requireAuth(auth);
    var i=new InteractionEntity(); i.agentUser=agentUser; i.mode=req.mode(); i.leadId=req.leadId(); i.phoneNumber=req.phoneNumber(); i.campaign=req.campaign(); i.dni=req.dni(); i.dispo=req.dispo(); i.notes=req.notes(); i.createdAt=OffsetDateTime.now(); i.extraJson=mapper.writeValueAsString(req.extra()==null?Map.of():req.extra());
    customers.findByDni(req.dni()).ifPresent(c->i.customerId=c.id);
    try { vicidial.externalStatus(agentUser,req.dispo(),req.leadId(),req.campaign()); i.syncStatus=SyncStatus.SYNCED; i.lastError=null; }
    catch (Exception e){ i.syncStatus=SyncStatus.FAILED; i.lastError=e.getMessage(); }
    interactions.save(i);
    return Map.of("id",i.id,"syncStatus",i.syncStatus.name(),"message",i.syncStatus==SyncStatus.SYNCED?"Synced":"Saved locally");
  }

  @PostMapping("/interactions/{id}/retry-vicidial")
  Map<String,Object> retry(@PathVariable Long id){
    var i=interactions.findById(id).orElseThrow();
    try { vicidial.externalStatus(i.agentUser,i.dispo,i.leadId,i.campaign); i.syncStatus=SyncStatus.SYNCED; i.lastError=null; }
    catch(Exception e){ i.syncStatus=SyncStatus.FAILED; i.lastError=e.getMessage(); }
    interactions.save(i);
    return Map.of("id",i.id,"syncStatus",i.syncStatus.name(),"message",Objects.toString(i.lastError,"Retry done"));
  }

  public record PreviewReq(Long leadId,String campaign,String action){}
  @PostMapping("/preview-action") Map<String,Object> preview(@RequestBody PreviewReq req, Authentication auth){ vicidial.previewAction(requireAuth(auth),req.leadId(),req.campaign(),req.action()); return Map.of("ok",true); }
  public record PauseReq(String action){}
  @PostMapping("/pause") Map<String,Object> pause(@RequestBody PauseReq req, Authentication auth){ vicidial.pause(requireAuth(auth),req.action()); return Map.of("ok",true); }

  private String requireAuth(Authentication auth) {
    if (auth == null || auth.getName() == null || auth.getName().isBlank()) throw new RuntimeException("Unauthorized");
    return auth.getName();
  }

  private String extract(String raw, String key){
    var m=java.util.regex.Pattern.compile(key+"=([^&\\n]+)").matcher(Objects.toString(raw, "")); return m.find()?m.group(1):"";
  }
  private Long extractLong(String raw,String key){ try{return Long.parseLong(extract(raw,key));}catch(Exception e){return null;} }

  private void ensureAgentExists(String agentUser) {
    if (userRepository.findByUsernameAndActiveTrue(agentUser).isEmpty()) {
      throw new VicidialServiceException(HttpStatus.NOT_FOUND,
          "AGENT_NOT_FOUND",
          "No existe un agente activo para el usuario autenticado.");
    }
  }

  private Entities.AgentVicidialCredentialEntity requireConnectedSession(String agentUser) {
    var session = agentVicidialCredentialRepository.findByAppUsername(agentUser)
        .orElseThrow(() -> new VicidialServiceException(
            HttpStatus.CONFLICT,
            "VICIDIAL_NOT_CONNECTED",
            "El agente no tiene sesión Vicidial conectada.",
            "conecte anexo/campaña primero",
            null
        ));
    if (!session.connected) {
      throw new VicidialServiceException(HttpStatus.CONFLICT,
          "VICIDIAL_NOT_CONNECTED",
          "El agente no tiene sesión Vicidial conectada.",
          "conecte anexo/campaña primero",
          null);
    }
    return session;
  }

  private void requireSessionField(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new VicidialServiceException(HttpStatus.CONFLICT,
          "VICIDIAL_SESSION_INCOMPLETE",
          "La sesión Vicidial está incompleta.",
          "Falta el campo requerido: " + field,
          Map.of("missingField", field));
    }
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
      return session.protocol + "/" + session.confExten;
    }
    return "";
  }

  private Long toLong(String value) {
    try {
      return value == null || value.isBlank() ? null : Long.parseLong(value.trim());
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private String firstPresent(Map<String, String> parsed, String... keys) {
    for (String key : keys) {
      String value = parsed.get(key);
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private Map<String, Object> maskPayload(Map<String, String> payload) {
    Map<String, Object> masked = new LinkedHashMap<>();
    payload.forEach((key, value) -> masked.put(key, key.toLowerCase(Locale.ROOT).contains("pass") ? "***" : value));
    return masked;
  }

  private Map<String, Object> buildManualDialDetails(int httpStatus, String rawSnippet, Map<String, String> payload) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("httpStatus", httpStatus);
    if (vicidialDebug) {
      details.put("rawSnippet", rawSnippet);
      details.put("debugRequest", maskPayload(payload));
    }
    return details;
  }

  private Map<String, Object> contextPayload(String mode, String campaign, String phoneLogin, String agentUser) {
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("mode", Objects.toString(mode, "predictive"));
    context.put("campaign", campaign);
    context.put("phoneLogin", phoneLogin);
    context.put("agentUser", agentUser);
    return context;
  }

  private Map<String, Object> businessNoLeadResponse(Map<String, Object> details) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("ok", false);
    body.put("code", "VICIDIAL_NO_ACTIVE_LEAD");
    body.put("message", "No hay lead activo");
    body.put("hint", "Espere que entre llamada / verifique hopper");
    body.put("details", details);
    return body;
  }

  private Map<String, Object> buildActiveLeadDetails(String classification, int httpStatus, String rawSnippet, String agentUser) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("classification", classification);
    details.put("httpStatus", httpStatus);
    if (vicidialDebug) {
      details.put("rawSnippet", rawSnippet);
      details.put("debugRequest", Map.of(
          "endpoint", "/agc/api.php",
          "function", "st_get_agent_active_lead",
          "agentUser", agentUser
      ));
    }
    return details;
  }

  private Map<String, Object> buildManualNextDetails(int httpStatus, String rawSnippet, String agentUser) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("httpStatus", httpStatus);
    if (vicidialDebug) {
      details.put("rawSnippet", rawSnippet);
      details.put("debugRequest", Map.of(
          "endpoint", "/agc/api.php",
          "function", "external_dial",
          "value", "MANUALNEXT",
          "agentUser", agentUser,
          "source", "***",
          "user", "***",
          "pass", "***"
      ));
    }
    return details;
  }
}
