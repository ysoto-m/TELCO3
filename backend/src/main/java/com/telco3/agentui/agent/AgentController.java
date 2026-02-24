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
  public record ManualNextReq(@NotBlank String campaignId, String mode) {}
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

  @PostMapping("/vicidial/manual/next")
  Map<String, Object> manualNext(@RequestBody ManualNextReq req, Authentication auth) {
    String agentUser = requireAuth(auth);
    ensureAgentExists(agentUser);
    var session = requireConnectedSession(agentUser);
    requireSessionField(session.connectedPhoneLogin, "phone_login");
    requireSessionField(session.connectedCampaign, "campaign");

    var result = vicidial.externalDialManualNext(agentUser);
    String raw = Objects.toString(result.body(), "");
    String normalized = raw.toUpperCase(Locale.ROOT);
    String rawSnippet = raw.substring(0, Math.min(raw.length(), 800));

    if (normalized.contains("ERROR: AGENT_USER IS NOT LOGGED IN")) {
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
    if (!normalized.contains("SUCCESS")) {
      throw new VicidialServiceException(HttpStatus.BAD_GATEWAY,
          "VICIDIAL_MANUAL_NEXT_FAILED",
          "Vicidial no confirmó la marcación manual siguiente.",
          "Revise la sesión del agente y permisos AGENT API en Vicidial.",
          buildManualNextDetails(result.statusCode(), rawSnippet, agentUser));
    }

    return Map.of("ok", true, "code", "VICIDIAL_MANUAL_NEXT_OK");
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
        response.put("context", Map.of(
            "mode", Objects.toString(mode, "predictive"),
            "campaign", session.connectedCampaign,
            "phoneLogin", session.connectedPhoneLogin,
            "agentUser", agentUser
        ));
        response.put("lead", null);
        return response;
      }
      resolvedLeadId = extractLong(leadResult.rawBody(), "lead_id");
    }

    if (resolvedLeadId == null) {
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("ok", true);
      response.put("context", Map.of(
          "mode", Objects.toString(mode, "predictive"),
          "campaign", session.connectedCampaign,
          "phoneLogin", session.connectedPhoneLogin,
          "agentUser", agentUser
      ));
      response.put("lead", null);
      return response;
    }

    String data = vicidial.leadInfo(resolvedLeadId);
    String dni = extract(data,"vendor_lead_code");
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
