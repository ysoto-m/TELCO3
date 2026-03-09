package com.telco3.agentui.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telco3.agentui.domain.*;
import com.telco3.agentui.domain.Entities.*;
import com.telco3.agentui.manual2.Manual2Service;
import com.telco3.agentui.vicidial.VicidialClient;
import com.telco3.agentui.vicidial.VicidialDialRequestBuilder;
import com.telco3.agentui.vicidial.VicidialDialResponseParser;
import com.telco3.agentui.vicidial.VicidialServiceException;
import com.telco3.agentui.vicidial.VicidialService;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

@RestController @RequestMapping("/api/agent")
public class AgentController {
  private static final Logger log = LoggerFactory.getLogger(AgentController.class);
  private final VicidialClient vicidial;
  private final InteractionRepository interactions;
  private final CustomerRepository customers;
  private final CustomerPhoneRepository phones;
  private final VicidialCredentialService credentialService;
  private final AgentVicidialSessionService vicidialSessionService;
  private final UserRepository userRepository;
  private final AgentVicidialCredentialRepository agentVicidialCredentialRepository;
  private final VicidialDialRequestBuilder dialRequestBuilder;
  private final VicidialDialResponseParser dialResponseParser;
  private final Environment environment;
  private final VicidialService vicidialService;
  private final AgentSessionLifecycleService agentSessionLifecycleService;
  private final Manual2Service manual2Service;
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
      VicidialDialRequestBuilder dialRequestBuilder,
      VicidialDialResponseParser dialResponseParser,
      Environment environment,
      VicidialService vicidialService,
      AgentSessionLifecycleService agentSessionLifecycleService,
      Manual2Service manual2Service,
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
    this.dialRequestBuilder = dialRequestBuilder;
    this.dialResponseParser = dialResponseParser;
    this.environment = environment;
    this.vicidialService = vicidialService;
    this.agentSessionLifecycleService = agentSessionLifecycleService;
    this.manual2Service = manual2Service;
    this.vicidialDebug = vicidialDebug;
  }

  public record AgentProfileResponse(boolean hasAgentPass, String lastPhoneLogin, String lastCampaign, boolean rememberCredentials, boolean connected, String connectedPhoneLogin, String connectedCampaign, String agentUser) {}
  public record UpdateAgentPassReq(@NotBlank String agentPass) {}
  public record PhoneConnectReq(@NotBlank String phoneLogin) {}
  public record CampaignConnectReq(@NotBlank String campaignId, Boolean rememberCredentials) {}
  public record DialNextReq(@NotBlank String campaignId) {}
  public record ManualDialReq(@NotBlank String campaignId, @NotBlank String phoneNumber, String phoneCode, Integer dialTimeout, String dialPrefix, String preview) {}
  public record HangupReq(String campaignId, String dispo, String mode, Long leadId, String phoneNumber, String dni, String notes, Map<String, Object> extra) {}
  public record SessionHeartbeatReq(String sessionId, String lastKnownVicidialStatus) {}
  public record SessionBrowserExitReq(String sessionId, String agentUser, String reason) {}
  public record AgentLogoutReq(String reason) {}
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
    boolean remember = req.rememberCredentials() == null || req.rememberCredentials();
    return vicidialSessionService.connectCampaign(username, req.campaignId(), remember);
  }

  @GetMapping("/vicidial/status")
  Map<String, Object> status(Authentication auth) {
    return vicidialSessionService.status(requireAuth(auth));
  }

  @PostMapping("/vicidial/poll")
  Map<String, Object> pollVicidialSession(Authentication auth) {
    String agentUser = requireAuth(auth);
    ensureAgentExists(agentUser);
    var session = requireConnectedSession(agentUser);
    String agentPass = credentialService.resolveAgentPass(agentUser)
        .orElseThrow(() -> new VicidialServiceException(HttpStatus.UNPROCESSABLE_ENTITY,
            "VICIDIAL_AGENT_CREDENTIALS_MISSING",
            "No existe agent_pass configurado para el agente.",
            "Actualice users.agent_pass_encrypted antes de usar polling Vicidial.",
            null));

    Map<String, String> heartbeatPayload = buildVdcHeartbeatPayload(agentUser, agentPass, session, session.connectedCampaign);
    var callbackCountResult = vicidial.callbacksCount(agentUser, heartbeatPayload);
    var updateSettingsResult = vicidial.updateSettings(agentUser, heartbeatPayload);

    return Map.of(
        "ok", true,
        "agentUser", agentUser,
        "campaign", session.connectedCampaign,
        "updateSettingsHttpStatus", updateSettingsResult.statusCode(),
        "callbacksCountHttpStatus", callbackCountResult.statusCode(),
        "updateSettingsSnippet", updateSettingsResult.snippet(180),
        "callbacksCountSnippet", callbackCountResult.snippet(180)
    );
  }

  @GetMapping("/active-lead")
  Map<String,Object> active(Authentication auth){
    String agentUser = requireAuth(auth);
    ensureAgentExists(agentUser);
    var session = requireConnectedSession(agentUser);
    requireSessionField(session.connectedPhoneLogin, "phone_login");
    requireSessionField(session.connectedCampaign, "campaign");
    requireSessionField(session.serverIp, "server_ip");
    requireSessionField(session.sessionName, "session_name");
    if (session.agentLogId == null) {
      throw new VicidialServiceException(HttpStatus.CONFLICT, "VICIDIAL_SESSION_INCOMPLETE", "La sesión Vicidial está incompleta.", "Falta el campo requerido: agent_log_id", Map.of("missingField", "agent_log_id"));
    }

    var state = vicidialService.classifyActiveLead(agentUser, session);
    if (state.reloginRequired()) {
      throw new VicidialServiceException(HttpStatus.CONFLICT,
          "VICIDIAL_RELOGIN_REQUIRED",
          "La sesión de Vicidial requiere re-login.",
          "Conecte anexo/campaña nuevamente para continuar.",
          null);
    }
    if (state.dialing()) {
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("ok", false);
      response.put("code", "VICIDIAL_DIALING");
      response.put("message", "Marcando... espere");
      response.put("details", Map.of("classification", "DIALING", "callId", state.callId()));
      return response;
    }

    if (!state.hasLead()) {
      String raw = Objects.toString(state.rawBody(), "");
      String rawSnippet = raw.substring(0, Math.min(raw.length(), 800));
      Map<String, Object> details = buildActiveLeadDetails(state.classification(), state.httpStatus(), rawSnippet, agentUser, state.details());
      if ("AGENT_PAUSED".equalsIgnoreCase(state.classification())) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("code", "VICIDIAL_AGENT_PAUSED");
        body.put("message", "El agente está en pausa");
        body.put("hint", "Quite la pausa en Vicidial para recibir/continuar el lead activo.");
        body.put("details", details);
        return body;
      }
      return businessNoLeadResponse(details);
    }

    Map<String, Object> lead = new LinkedHashMap<>();
    lead.put("leadId", state.leadId());
    lead.put("phoneNumber", state.phoneNumber());
    lead.put("campaign", state.campaign());
    lead.put("callId", state.details().get("callId"));
    lead.put("uniqueId", state.details().get("uniqueId"));
    lead.put("channel", state.details().get("channel"));
    lead.put("agentStatus", state.details().get("agentStatus"));
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("ok", true);
    response.put("lead", lead);
    response.put("runtime", state.details());
    return response;
  }

  @PostMapping("/vicidial/dial/next")
  Map<String, Object> dialNext(@RequestBody DialNextReq req, Authentication auth) {
    String agentUser = requireAuth(auth);
    ensureAgentExists(agentUser);
    var session = requireConnectedSession(agentUser);
    String agentPass = credentialService.resolveAgentPass(agentUser)
        .orElseThrow(() -> new VicidialServiceException(HttpStatus.UNPROCESSABLE_ENTITY,
            "VICIDIAL_AGENT_CREDENTIALS_MISSING",
            "No existe agent_pass configurado para el agente.",
            "Actualice users.agent_pass_encrypted antes de usar marcacion manual.",
            null));

    String resolvedMode = vicidialService.resolveModeForCampaign(agentUser, req.campaignId());
    Map<String, String> payload = dialRequestBuilder.buildDialNextPayload(agentUser, agentPass, session, req.campaignId());
    return executeDialFlow(
        agentUser,
        agentPass,
        session,
        req.campaignId(),
        resolvedMode,
        payload,
        null,
        "VICIDIAL_MANUAL_NEXT_FAILED",
        "Vicidial no confirmo la marcacion manual siguiente."
    );
  }

  @PostMapping("/session/heartbeat")
  Map<String, Object> sessionHeartbeat(@RequestBody(required = false) SessionHeartbeatReq req, Authentication auth) {
    String agentUser = requireAuth(auth);
    String sessionId = req == null ? null : req.sessionId();
    String lastKnownVicidialStatus = req == null ? null : req.lastKnownVicidialStatus();
    var result = agentSessionLifecycleService.heartbeat(agentUser, sessionId, lastKnownVicidialStatus);
    return Map.of(
        "ok", result.ok(),
        "sessionId", result.sessionId(),
        "status", result.status(),
        "connected", result.connected(),
        "serverTime", result.serverTime()
    );
  }

  @PostMapping("/session/browser-exit")
  Map<String, Object> sessionBrowserExit(
      @RequestBody(required = false) SessionBrowserExitReq req,
      @RequestParam(required = false) String sessionId,
      @RequestParam(required = false) String agentUser,
      @RequestParam(required = false) String reason,
      Authentication auth
  ) {
    String authenticatedUser = auth == null ? null : auth.getName();
    String resolvedSessionId = firstNonBlank(req == null ? null : req.sessionId(), sessionId);
    String resolvedAgentUser = firstNonBlank(req == null ? null : req.agentUser(), agentUser);
    String resolvedReason = firstNonBlank(req == null ? null : req.reason(), reason);
    var result = agentSessionLifecycleService.browserExit(authenticatedUser, resolvedAgentUser, resolvedSessionId, resolvedReason);
    return Map.of(
        "ok", result.ok(),
        "updated", result.updated(),
        "code", result.code(),
        "serverTime", result.serverTime()
    );
  }

  @PostMapping("/logout")
  Map<String, Object> logoutAgent(@RequestBody(required = false) AgentLogoutReq req, Authentication auth) {
    String agentUser = requireAuth(auth);
    String reason = req == null ? null : req.reason();
    var result = agentSessionLifecycleService.logout(agentUser, reason);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("ok", result.ok());
    response.put("hadSession", result.hadSession());
    response.put("code", result.code());
    response.put("details", result.details());
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
        .orElseThrow(() -> new VicidialServiceException(HttpStatus.UNPROCESSABLE_ENTITY,
            "VICIDIAL_AGENT_CREDENTIALS_MISSING",
            "No existe agent_pass configurado para el agente.",
            "Actualice users.agent_pass_encrypted antes de usar marcacion manual.",
            null));

    String mode = vicidialService.resolveModeForCampaign(agentUser, req.campaignId());
    if (!"manual".equalsIgnoreCase(mode)) {
      throw new VicidialServiceException(HttpStatus.CONFLICT, "VICIDIAL_MODE_NOT_MANUAL", "La campana no esta en modo manual.", "Use una campana con dial_method MANUAL para marcacion manual.", Map.of("mode", mode));
    }

    var overrides = new VicidialDialRequestBuilder.ManualDialOverrides(req.phoneNumber(), req.phoneCode(), req.dialTimeout(), req.dialPrefix());
    LinkedHashMap<String, String> payload = new LinkedHashMap<>(dialRequestBuilder.buildManualDialPayload(agentUser, agentPass, session, req.campaignId(), overrides));
    payload.put("preview", "YES".equalsIgnoreCase(req.preview()) ? "YES" : "NO");

    return executeDialFlow(
        agentUser,
        agentPass,
        session,
        req.campaignId(),
        mode,
        payload,
        req.phoneNumber(),
        "VICIDIAL_MANUAL_DIAL_FAILED",
        "Vicidial no confirmo la marcacion manual."
    );
  }

  private Map<String, Object> executeDialFlow(
      String agentUser,
      String agentPass,
      Entities.AgentVicidialCredentialEntity session,
      String campaignId,
      String mode,
      Map<String, String> payload,
      String requestedPhoneNumber,
      String failureCode,
      String failureMessage
  ) {
    enrichManualDialListId(agentUser, campaignId, payload);
    var realtimeBeforeDial = vicidialService.resolveRealtimeCallSnapshot(
        agentUser,
        session,
        session.currentCallId,
        session.currentLeadId,
        requestedPhoneNumber,
        campaignId,
        true
    );
    String activeCallId = firstNonBlank(realtimeBeforeDial.callId(), session.currentCallId);
    boolean hasMediaEvidence = firstNonBlank(realtimeBeforeDial.uniqueId(), realtimeBeforeDial.channel()) != null;
    boolean runtimeMarkedActive = "ACTIVE".equalsIgnoreCase(Objects.toString(session.currentDialStatus, ""));
    boolean hasActiveCallEvidence = hasMediaEvidence || (runtimeMarkedActive && activeCallId != null);
    if (isInCallStatus(realtimeBeforeDial.agentStatus()) && hasActiveCallEvidence) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("classification", realtimeBeforeDial.classification());
      details.put("agentStatus", realtimeBeforeDial.agentStatus());
      details.put("callId", activeCallId);
      details.put("leadId", realtimeBeforeDial.leadId());
      details.put("uniqueId", realtimeBeforeDial.uniqueId());
      details.put("channel", realtimeBeforeDial.channel());
      if (realtimeBeforeDial.details() != null && !realtimeBeforeDial.details().isEmpty()) {
        details.put("runtime", realtimeBeforeDial.details());
      }
      throw new VicidialServiceException(HttpStatus.CONFLICT,
          "VICIDIAL_AGENT_INCALL",
          "El agente ya tiene una llamada activa (INCALL).",
          "Finalice/disponibilice la llamada actual antes de iniciar otra marcacion manual.",
          details);
    }

    Map<String, String> heartbeatPayload = buildVdcHeartbeatPayload(agentUser, agentPass, session, campaignId);
    var callbackCountResult = vicidial.callbacksCount(agentUser, heartbeatPayload);
    var updateSettingsResult = vicidial.updateSettings(agentUser, heartbeatPayload);

    var result = vicidial.manualDialNextCall(agentUser, payload);
    String raw = Objects.toString(result.body(), "");
    String rawSnippet = raw.substring(0, Math.min(raw.length(), 800));
    var parsed = dialResponseParser.parseDetailed(raw);

    if (!parsed.success()) {
      throw dialBusinessException(failureCode, result.statusCode(), parsed.classification(), rawSnippet, payload, failureMessage);
    }

    var followUp = vicidialService.followUpManualDial(agentUser, agentPass, session, campaignId, parsed, requestedPhoneNumber);
    if (!followUp.incallConfirmed()) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("classification", followUp.classification());
      details.put("agentStatus", followUp.agentStatus());
      details.put("callId", followUp.callId());
      details.put("leadId", followUp.leadId());
      details.put("uniqueId", followUp.uniqueId());
      details.put("channel", followUp.channel());
      details.put("preflight", Map.of(
          "callbacksCountHttpStatus", callbackCountResult.statusCode(),
          "updateSettingsHttpStatus", updateSettingsResult.statusCode()
      ));
      if (followUp.details() != null && !followUp.details().isEmpty()) {
        details.putAll(followUp.details());
      }
      if (debugAllowed()) {
        details.put("rawSnippet", rawSnippet);
        details.put("debugRequest", maskedUrlEncodedPayload(payload));
      }
      throw new VicidialServiceException(HttpStatus.CONFLICT,
          "VICIDIAL_DIAL_NOT_CONFIRMED",
          "Vicidial no confirmo transicion real a INCALL despues de manDiaLnextCaLL.",
          "Verifique estado READY/PAUSED, sesion de agente y flujo AGC conf_exten_check.",
          details);
    }

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("ok", true);
    response.put("result", Map.of(
        "code", "VICIDIAL_DIAL_NEXT_OK",
        "campaign", Objects.toString(payload.get("campaign"), campaignId),
        "mode", mode,
        "classification", followUp.classification()
    ));
    response.put("callId", followUp.callId());
    response.put("leadId", followUp.leadId());
    response.put("phoneNumber", followUp.phoneNumber());
    response.put("status", firstNonBlank(parsed.leadStatus(), followUp.leadStatus()));
    response.put("listId", firstNonBlank(parsed.listId(), followUp.listId(), payload.get("list_id")));
    response.put("classification", followUp.classification());
    response.put("agentStatus", followUp.agentStatus());
    response.put("agentRuntimeStatus", followUp.agentStatus());
    response.put("uniqueId", followUp.uniqueId());
    response.put("channel", followUp.channel());
    response.put("preflight", Map.of(
        "callbacksCountHttpStatus", callbackCountResult.statusCode(),
        "updateSettingsHttpStatus", updateSettingsResult.statusCode(),
        "callbacksCountSnippet", callbackCountResult.snippet(180),
        "updateSettingsSnippet", updateSettingsResult.snippet(180)
    ));
    if (followUp.details() != null && !followUp.details().isEmpty()) {
      response.put("details", new LinkedHashMap<>(followUp.details()));
    }
    if ("MANUAL2".equalsIgnoreCase(firstNonBlank(campaignId, payload.get("campaign"), session.connectedCampaign))) {
      try {
        manual2Service.registerInteractionStart(
            agentUser,
            new Manual2Service.RegisterInteractionRequest(
                campaignId,
                mode,
                followUp.phoneNumber(),
                followUp.leadId(),
                followUp.callId(),
                followUp.uniqueId(),
                null
            )
        );
      } catch (Exception ex) {
        log.warn("Manual2 interaction start registration failed agent={} cause={}", agentUser, ex.getClass().getSimpleName());
      }
    }
    maybeAttachDebug(response, payload, rawSnippet);
    return response;
  }

  private void enrichManualDialListId(String agentUser, String campaignId, Map<String, String> payload) {
    if (!payload.containsKey("list_id") || StringUtils.hasText(payload.get("list_id"))) {
      return;
    }
    Optional<String> runtimeListId = Optional.ofNullable(vicidialSessionService.currentManualDialListId(agentUser))
        .orElse(Optional.empty());
    Optional<String> campaignListId = Optional.ofNullable(
        vicidialService.resolveManualDialListId(firstNonBlank(payload.get("campaign"), campaignId))
    ).orElse(Optional.empty());
    String resolvedListId = runtimeListId.filter(StringUtils::hasText).orElseGet(() ->
        campaignListId.orElse(null)
    );
    if (StringUtils.hasText(resolvedListId)) {
      payload.put("list_id", resolvedListId);
    }
  }

  @GetMapping("/context")
  Map<String,Object> context(Authentication auth,@RequestParam(required=false) Long leadId){
    String agentUser = requireAuth(auth);
    ensureAgentExists(agentUser);
    var session = requireConnectedSession(agentUser);
    requireSessionField(session.connectedPhoneLogin, "phone_login");
    requireSessionField(session.connectedCampaign, "campaign");
    requireSessionField(session.serverIp, "server_ip");
    requireSessionField(session.sessionName, "session_name");
    if (session.agentLogId == null) {
      throw new VicidialServiceException(HttpStatus.CONFLICT, "VICIDIAL_SESSION_INCOMPLETE", "La sesion Vicidial esta incompleta.", "Falta el campo requerido: agent_log_id", Map.of("missingField", "agent_log_id"));
    }

    var realtime = vicidialService.resolveRealtimeCallSnapshot(
        agentUser,
        session,
        session.currentCallId,
        session.currentLeadId,
        null,
        session.connectedCampaign,
        true
    );
    if (realtime.reloginRequired()) {
      throw new VicidialServiceException(HttpStatus.CONFLICT,
          "VICIDIAL_RELOGIN_REQUIRED",
          "La sesion de Vicidial requiere re-login.",
          "Conecte anexo/campana nuevamente para continuar.",
          null);
    }

    Long resolvedLeadId = leadId != null ? leadId : realtime.leadId();
    if (resolvedLeadId == null) {
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("ok", true);
      response.putAll(contextPayload(session.connectedMode, session.connectedCampaign, session.connectedPhoneLogin, agentUser));
      response.put("lead", null);
      response.put("runtime", runtimePayload(realtime));
      return response;
    }

    String data = vicidial.leadInfo(resolvedLeadId);
    String dni = extract(data, "vendor_lead_code");
    if (dni == null || dni.isBlank()) {
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("ok", true);
      response.putAll(contextPayload(session.connectedMode, session.connectedCampaign, session.connectedPhoneLogin, agentUser));
      response.put("lead", Map.of("leadId", resolvedLeadId));
      response.put("runtime", runtimePayload(realtime));
      response.put("customer", null);
      response.put("phones", List.of());
      response.put("interactions", List.of());
      response.put("dispoOptions", List.of("SALE", "NOANS", "CALLBK", "DNC"));
      return response;
    }

    var c = customers.findByDni(dni).orElseGet(() -> {
      var nc = new CustomerEntity();
      nc.dni = dni;
      nc.firstName = "TODO";
      nc.lastName = "TODO";
      return nc;
    });
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
    lead.put("callId", firstNonBlank(realtime.callId(), session.currentCallId));
    lead.put("uniqueId", realtime.uniqueId());
    lead.put("channel", realtime.channel());
    lead.put("agentStatus", realtime.agentStatus());

    Map<String, Object> customer = new LinkedHashMap<>();
    customer.put("dni", dni);
    customer.put("firstName", Objects.toString(c.firstName, "TODO"));
    customer.put("lastName", Objects.toString(c.lastName, "TODO"));

    Map<String, Object> response = new LinkedHashMap<>();
    response.putAll(contextPayload(session.connectedMode, session.connectedCampaign, session.connectedPhoneLogin, agentUser));
    response.put("lead", lead);
    response.put("runtime", runtimePayload(realtime));
    response.put("customer", customer);
    response.put("phones", ph);
    response.put("interactions", hist);
    response.put("dispoOptions", List.of("SALE", "NOANS", "CALLBK", "DNC"));
    return response;
  }

  @PostMapping("/interactions")
  Map<String,Object> save(@RequestBody InteractionReq req, Authentication auth) throws Exception {
    String agentUser = requireAuth(auth);
    assertFinalDispositionAllowed(agentUser);

    var i = buildInteractionEntity(
        agentUser,
        req.mode(),
        req.leadId(),
        req.phoneNumber(),
        req.campaign(),
        req.dni(),
        req.dispo(),
        req.notes(),
        req.extra()
    );

    try {
      vicidial.externalStatus(agentUser, req.dispo(), req.leadId(), req.campaign());
      i.syncStatus = SyncStatus.SYNCED;
      i.lastError = null;
    } catch (Exception e) {
      i.syncStatus = SyncStatus.FAILED;
      i.lastError = e.getMessage();
    }
    interactions.save(i);
    return Map.of("id", i.id, "syncStatus", i.syncStatus.name(), "message", i.syncStatus == SyncStatus.SYNCED ? "Synced" : "Saved locally");
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
  @PostMapping("/vicidial/call/hangup")
  Map<String, Object> hangupCall(@RequestBody(required = false) HangupReq req, Authentication auth) {
    String agentUser = requireAuth(auth);
    ensureAgentExists(agentUser);
    var session = requireConnectedSession(agentUser);
    String agentPass = credentialService.resolveAgentPass(agentUser)
        .orElseThrow(() -> new VicidialServiceException(HttpStatus.UNPROCESSABLE_ENTITY,
            "VICIDIAL_AGENT_CREDENTIALS_MISSING",
            "No existe agent_pass configurado para el agente.",
            "Actualice users.agent_pass_encrypted antes de colgar llamada.",
            null));
    String campaignId = firstNonBlank(req == null ? null : req.campaignId(), session.connectedCampaign);
    String dispo = req == null ? null : req.dispo();
    var result = vicidialService.hangupActiveCall(agentUser, agentPass, session, campaignId, dispo);

    InteractionEntity persistedInteraction = maybePersistHangupDisposition(agentUser, req, result, session, campaignId);

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("ok", result.executed());
    response.put("code", result.executed() ? "VICIDIAL_HANGUP_SENT" : "VICIDIAL_HANGUP_NO_ACTIVE_CALL");
    response.put("classification", result.classification());
    response.put("callId", result.callId());
    response.put("leadId", result.leadId());
    response.put("uniqueId", result.uniqueId());
    response.put("channel", result.channel());
    response.put("agentStatus", result.agentStatus());
    response.put("details", result.details());
    if (persistedInteraction != null) {
      response.put("interactionSaved", true);
      response.put("interactionId", persistedInteraction.id);
      response.put("interactionSyncStatus", persistedInteraction.syncStatus.name());
    } else {
      response.put("interactionSaved", false);
    }
    return response;
  }
  public record PauseReq(String action){}
  @PostMapping("/pause") Map<String,Object> pause(@RequestBody PauseReq req, Authentication auth){ vicidial.pause(requireAuth(auth),req.action()); return Map.of("ok",true); }

  private void assertFinalDispositionAllowed(String agentUser) {
    var sessionOpt = agentVicidialCredentialRepository.findByAppUsername(agentUser)
        .filter(s -> s.connected);
    if (sessionOpt.isEmpty()) {
      return;
    }
    var session = sessionOpt.get();
    String agentPass = credentialService.resolveAgentPass(agentUser).orElse(null);
    if (!StringUtils.hasText(agentPass)) {
      return;
    }
    var snapshot = vicidialService.resolveRealtimeCallSnapshot(
        agentUser,
        agentPass,
        session,
        session.currentCallId,
        session.currentLeadId,
        null,
        session.connectedCampaign,
        true
    );
    boolean dialingRuntime = "DIALING".equalsIgnoreCase(Objects.toString(session.currentDialStatus, ""));
    boolean hasMediaEvidence = firstNonBlank(snapshot.uniqueId(), snapshot.channel()) != null;
    boolean hasCallEvidence = firstNonBlank(snapshot.callId()) != null || snapshot.leadId() != null;
    boolean callActive = dialingRuntime || (isInCallStatus(snapshot.agentStatus()) && (hasMediaEvidence || hasCallEvidence));
    if (!callActive) {
      return;
    }

    Map<String, Object> details = new LinkedHashMap<>();
    details.put("classification", snapshot.classification());
    details.put("agentStatus", snapshot.agentStatus());
    details.put("callId", snapshot.callId());
    details.put("leadId", snapshot.leadId());
    details.put("uniqueId", snapshot.uniqueId());
    details.put("channel", snapshot.channel());
    throw new VicidialServiceException(
        HttpStatus.CONFLICT,
        "VICIDIAL_CALL_STILL_ACTIVE",
        "No se puede guardar tipificacion final mientras la llamada sigue activa.",
        "Cuelgue la llamada y luego confirme la tipificacion final.",
        details
    );
  }

  private InteractionEntity maybePersistHangupDisposition(
      String agentUser,
      HangupReq req,
      VicidialService.HangupResult hangupResult,
      Entities.AgentVicidialCredentialEntity session,
      String fallbackCampaign
  ) {
    if (req == null || !StringUtils.hasText(req.dispo()) || hangupResult == null || !hangupResult.executed()) {
      return null;
    }
    if (req.extra() != null) {
      Object skipSave = req.extra().get("skipCrmInteractionSave");
      if ("true".equalsIgnoreCase(Objects.toString(skipSave, "")) || Boolean.TRUE.equals(skipSave)) {
        return null;
      }
    }
    String campaign = firstNonBlank(req.campaignId(), fallbackCampaign, session.connectedCampaign);
    String phoneNumber = firstNonBlank(req.phoneNumber());
    String dni = firstNonBlank(req.dni());
    String mode = firstNonBlank(req.mode(), session.connectedMode, "manual");
    Long leadId = firstNonNull(req.leadId(), hangupResult.leadId(), session.currentLeadId);

    if (!StringUtils.hasText(phoneNumber) && leadId == null) {
      return null;
    }

    InteractionEntity interaction;
    try {
      Map<String, Object> extra = new LinkedHashMap<>();
      if (req.extra() != null && !req.extra().isEmpty()) {
        extra.putAll(req.extra());
      }
      extra.put("savedAfterHangup", true);
      extra.put("hangupExecuted", hangupResult.executed());
      extra.put("hangupClassification", hangupResult.classification());
      interaction = buildInteractionEntity(
          agentUser,
          mode,
          leadId,
          phoneNumber,
          campaign,
          dni,
          req.dispo(),
          req.notes(),
          extra
      );
    } catch (Exception ex) {
      log.warn("Could not build interaction payload after hangup agent={} cause={}", agentUser, ex.getClass().getSimpleName());
      return null;
    }

    Integer updateDispoHttpStatus = toInteger(hangupResult.details().get("updateDispoHttpStatus"));
    boolean synced = hangupResult.executed() && (updateDispoHttpStatus == null || updateDispoHttpStatus < 400);
    interaction.syncStatus = synced ? SyncStatus.SYNCED : SyncStatus.FAILED;
    interaction.lastError = synced ? null : "Hangup flow did not confirm updateDISPO";
    interactions.save(interaction);
    return interaction;
  }

  private InteractionEntity buildInteractionEntity(
      String agentUser,
      String mode,
      Long leadId,
      String phoneNumber,
      String campaign,
      String dni,
      String dispo,
      String notes,
      Map<String, Object> extra
  ) throws Exception {
    var interaction = new InteractionEntity();
    interaction.agentUser = agentUser;
    interaction.mode = mode;
    interaction.leadId = leadId;
    interaction.phoneNumber = phoneNumber;
    interaction.campaign = campaign;
    interaction.dni = dni;
    interaction.dispo = dispo;
    interaction.notes = notes;
    interaction.createdAt = OffsetDateTime.now();
    interaction.extraJson = mapper.writeValueAsString(extra == null ? Map.of() : extra);
    if (StringUtils.hasText(dni)) {
      customers.findByDni(dni).ifPresent(c -> interaction.customerId = c.id);
    }
    return interaction;
  }

  private Integer toInteger(Object value) {
    if (value == null) {
      return null;
    }
    try {
      return Integer.parseInt(Objects.toString(value, "").trim());
    } catch (Exception ex) {
      return null;
    }
  }

  private String requireAuth(Authentication auth) {
    if (auth == null || auth.getName() == null || auth.getName().isBlank()) throw new RuntimeException("Unauthorized");
    return auth.getName();
  }

  private String extract(String raw, String key){
    Map<String, String> parsed = vicidial.parseKeyValueLines(raw);
    String value = firstPresent(parsed, key);
    if (value != null && !value.isBlank()) {
      return value;
    }
    var m = java.util.regex.Pattern.compile(key + "=([^&\\n]+)").matcher(Objects.toString(raw, ""));
    return m.find() ? m.group(1) : "";
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

  private Long toLong(String value) {
    try {
      return value == null || value.isBlank() ? null : Long.parseLong(value.trim());
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private Map<String, String> buildVdcHeartbeatPayload(
      String agentUser,
      String agentPass,
      Entities.AgentVicidialCredentialEntity session,
      String campaignId
  ) {
    LinkedHashMap<String, String> payload = new LinkedHashMap<>();
    payload.put("user", agentUser);
    payload.put("pass", agentPass);
    payload.put("server_ip", session.serverIp);
    payload.put("session_name", session.sessionName);
    payload.put("campaign", campaignId);
    payload.put("agent_log_id", Objects.toString(session.agentLogId, ""));
    payload.put("format", "text");
    return payload;
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private Long firstNonNull(Long... values) {
    for (Long value : values) {
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  private String firstPresent(Map<String, String> parsed, String... keys) {
    for (String key : keys) {
      String normalized = normalizeParsedKey(key);
      String value = parsed.get(normalized);
      if ((value == null || value.isBlank()) && normalized.contains("_")) {
        value = parsed.get(normalized.replace("_", ""));
      }
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private boolean isInCallStatus(String status) {
    return "INCALL".equalsIgnoreCase(Objects.toString(status, ""));
  }

  private String normalizeParsedKey(String key) {
    return Objects.toString(key, "")
        .trim()
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9]+", "_")
        .replaceAll("_+", "_")
        .replaceAll("^_+", "")
        .replaceAll("_+$", "");
  }

  private Map<String, Object> contextPayload(String mode, String campaign, String phoneLogin, String agentUser) {
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("mode", Objects.toString(mode, "predictive"));
    context.put("campaign", campaign);
    context.put("phoneLogin", phoneLogin);
    context.put("agentUser", agentUser);
    return context;
  }

  private Map<String, Object> runtimePayload(VicidialService.RealtimeCallSnapshot realtime) {
    Map<String, Object> runtime = new LinkedHashMap<>();
    runtime.put("classification", realtime.classification());
    runtime.put("agentStatus", realtime.agentStatus());
    runtime.put("callId", realtime.callId());
    runtime.put("leadId", realtime.leadId());
    runtime.put("uniqueId", realtime.uniqueId());
    runtime.put("channel", realtime.channel());
    if (realtime.details() != null && !realtime.details().isEmpty()) {
      runtime.put("details", realtime.details());
    }
    return runtime;
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

  private Map<String, Object> buildActiveLeadDetails(String classification, int httpStatus, String rawSnippet, String agentUser, Map<String, Object> extraDetails) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("classification", classification);
    details.put("httpStatus", httpStatus);
    if (extraDetails != null && !extraDetails.isEmpty()) {
      details.putAll(extraDetails);
    }
    if (debugAllowed()) {
      details.put("rawSnippet", rawSnippet);
      details.put("debugRequest", Map.of(
          "endpoint", "/agc/api.php",
          "function", "st_get_agent_active_lead",
          "agentUser", agentUser
      ));
    }
    return details;
  }

  private VicidialServiceException dialBusinessException(String code, int httpStatus, VicidialDialResponseParser.DialClassification classification, String rawSnippet, Map<String, String> payload, String defaultMessage) {
    HttpStatus status = switch (classification) {
      case RELOGIN_REQUIRED -> HttpStatus.UNAUTHORIZED;
      case INVALID_SESSION -> HttpStatus.CONFLICT;
      case NO_LEADS -> HttpStatus.CONFLICT;
      case INVALID_PARAMS -> HttpStatus.UNPROCESSABLE_ENTITY;
      case UNKNOWN -> HttpStatus.CONFLICT;
      case SUCCESS -> HttpStatus.OK;
    };
    String responseCode = switch (classification) {
      case RELOGIN_REQUIRED -> "VICIDIAL_RELOGIN_REQUIRED";
      case INVALID_SESSION -> "VICIDIAL_INVALID_SESSION";
      case NO_LEADS -> "VICIDIAL_NO_LEADS";
      case INVALID_PARAMS -> "VICIDIAL_INVALID_PARAMS";
      default -> code;
    };
    String hint = switch (classification) {
      case RELOGIN_REQUIRED -> "Conecte anexo/campaña nuevamente para continuar.";
      case INVALID_SESSION -> "Revise sesión AGC/cookies y consistencia de parámetros de marcación.";
      case NO_LEADS -> "No hay leads disponibles en el hopper para este agente.";
      case INVALID_PARAMS -> "Revise los parámetros enviados para marcación manual.";
      default -> "Valide parámetros de sesión (session_name/server_ip/agent_log_id) y estado del agente.";
    };
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("httpStatus", httpStatus);
    details.put("classification", classification.name());
    if (debugAllowed()) {
      details.put("rawSnippet", rawSnippet);
      details.put("debugRequest", maskedUrlEncodedPayload(payload));
    }
    return new VicidialServiceException(status, responseCode, defaultMessage, hint, details);
  }

  private void maybeAttachDebug(Map<String, Object> response, Map<String, String> payload, String rawSnippet) {
    if (!debugAllowed()) {
      return;
    }
    Map<String, Object> details = new LinkedHashMap<>();
    Object existing = response.get("details");
    if (existing instanceof Map<?, ?> existingMap) {
      existingMap.forEach((key, value) -> details.put(Objects.toString(key, ""), value));
    }
    details.put("debugRequest", maskedUrlEncodedPayload(payload));
    details.put("rawSnippet", rawSnippet);
    response.put("details", details);
  }

  private String maskedUrlEncodedPayload(Map<String, String> payload) {
    return payload.entrySet().stream()
        .map(e -> e.getKey() + "=" + urlEncode(e.getKey().toLowerCase(Locale.ROOT).contains("pass") ? "***" : Objects.toString(e.getValue(), "")))
        .reduce((a, b) -> a + "&" + b)
        .orElse("");
  }

  private String urlEncode(String value) {
    return java.net.URLEncoder.encode(Objects.toString(value, ""), java.nio.charset.StandardCharsets.UTF_8);
  }

  private boolean debugAllowed() {
    if (!vicidialDebug) {
      return false;
    }
    return Arrays.stream(environment.getActiveProfiles()).noneMatch("prod"::equalsIgnoreCase)
        && !"prod".equalsIgnoreCase(environment.getProperty("APP_ENV", environment.getProperty("app.env", "")));
  }
}
