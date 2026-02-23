package com.telco3.agentui.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telco3.agentui.domain.*;
import com.telco3.agentui.domain.Entities.*;
import com.telco3.agentui.vicidial.VicidialClient;
import jakarta.validation.constraints.NotBlank;
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
  private final ObjectMapper mapper = new ObjectMapper();

  public AgentController(VicidialClient vicidial, InteractionRepository interactions, CustomerRepository customers, CustomerPhoneRepository phones, VicidialCredentialService credentialService){
    this.vicidial=vicidial; this.interactions=interactions; this.customers=customers; this.phones=phones; this.credentialService = credentialService;
  }

  public record AgentProfileResponse(boolean hasAgentPass, String lastPhoneLogin, String lastCampaign, boolean rememberCredentials, boolean connected, String connectedPhoneLogin, String connectedCampaign, String agentUser) {}
  public record UpdateAgentPassReq(@NotBlank String agentPass) {}
  public record CampaignDiscoveryReq(@NotBlank String phoneLogin) {}
  public record CampaignDiscoveryResponse(String phoneLogin, String generatedPhonePass, List<String> campaigns, String raw, String error) {}
  public record ConnectReq(@NotBlank String phoneLogin, @NotBlank String campaign, Boolean rememberCredentials) {}

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

  @PostMapping("/campaigns/available")
  CampaignDiscoveryResponse campaigns(@RequestBody CampaignDiscoveryReq req, Authentication auth) {
    String username = requireAuth(auth);
    String phonePass = buildPhonePass(req.phoneLogin());
    try {
      String raw = vicidial.campaignsForAgent(username, req.phoneLogin(), phonePass);
      List<String> campaigns = parseCampaigns(raw);
      return new CampaignDiscoveryResponse(req.phoneLogin(), phonePass, campaigns, raw, null);
    } catch (Exception e) {
      return new CampaignDiscoveryResponse(req.phoneLogin(), phonePass, List.of(), "", e.getMessage());
    }
  }

  @PostMapping("/vicidial/connect")
  Map<String, Object> connect(@RequestBody ConnectReq req, Authentication auth) {
    String username = requireAuth(auth);
    String agentPass = credentialService.resolveAgentPass(username)
        .orElseThrow(() -> new RuntimeException("Debe configurar agent_pass en Perfil antes de conectar"));

    String phonePass = buildPhonePass(req.phoneLogin());
    String raw = vicidial.agentLogin(username, agentPass, req.phoneLogin(), phonePass, req.campaign());
    boolean ok = isSuccess(raw);
    if (ok) {
      boolean remember = req.rememberCredentials() == null || req.rememberCredentials();
      credentialService.saveLastSelection(username, req.phoneLogin(), req.campaign(), remember);
      credentialService.markConnected(username, req.phoneLogin(), req.campaign());
    }
    return Map.of(
        "ok", ok,
        "raw", raw,
        "campaign", req.campaign(),
        "phoneLogin", req.phoneLogin(),
        "connected", ok
    );
  }

  @PostMapping("/vicidial/disconnect")
  Map<String, Object> disconnect(Authentication auth) {
    String username = requireAuth(auth);
    var raw = vicidial.agentLogout(username);
    credentialService.markDisconnected(username);
    return Map.of("ok", isSuccess(raw), "raw", raw);
  }

  @GetMapping("/vicidial/status")
  Map<String, Object> status(Authentication auth) {
    String username = requireAuth(auth);
    var raw = vicidial.agentStatus(username);
    var profile = credentialService.getProfile(username);
    return Map.of(
        "agentUser", username,
        "status", extract(raw, "status"),
        "campaign", extract(raw, "campaign"),
        "extension", extract(raw, "extension"),
        "paused", extract(raw, "paused"),
        "connected", profile.connected(),
        "connectedCampaign", profile.connectedCampaign(),
        "connectedPhoneLogin", profile.connectedPhoneLogin(),
        "raw", raw
    );
  }

  @GetMapping("/active-lead")
  Map<String,Object> active(@RequestParam String agentUser){
    var raw=vicidial.activeLead(agentUser);
    Long leadId=extractLong(raw,"lead_id");
    return Map.of("leadId",leadId,"phoneNumber",extract(raw,"phone_number"),"campaign",extract(raw,"campaign"));
  }

  @GetMapping("/context")
  Map<String,Object> context(@RequestParam String agentUser,@RequestParam(required=false) Long leadId,@RequestParam(required=false) String phone,@RequestParam(required=false) String campaign,@RequestParam String mode){
    String data = leadId!=null ? vicidial.leadInfo(leadId) : vicidial.leadSearch(phone==null?"":phone);
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
    return Map.of("lead",Map.of("leadId",leadId==null?extractLong(data,"lead_id"):leadId,"phoneNumber",Objects.toString(phone,extract(data,"phone_number")),"campaign",Objects.toString(campaign,extract(data,"campaign_id")),"dni",dni),
      "customer",Map.of("dni",dni,"firstName",Objects.toString(c.firstName,"TODO"),"lastName",Objects.toString(c.lastName,"TODO")),
      "phones",ph,"interactions",hist,"dispoOptions",List.of("SALE","NOANS","CALLBK","DNC"));
  }

  public record InteractionReq(@NotBlank String agentUser,@NotBlank String mode,Long leadId,@NotBlank String phoneNumber,@NotBlank String campaign,@NotBlank String dni,@NotBlank String dispo,String notes,Map<String,Object> extra){}
  @PostMapping("/interactions")
  Map<String,Object> save(@RequestBody InteractionReq req) throws Exception {
    var i=new InteractionEntity(); i.agentUser=req.agentUser(); i.mode=req.mode(); i.leadId=req.leadId(); i.phoneNumber=req.phoneNumber(); i.campaign=req.campaign(); i.dni=req.dni(); i.dispo=req.dispo(); i.notes=req.notes(); i.createdAt=OffsetDateTime.now(); i.extraJson=mapper.writeValueAsString(req.extra()==null?Map.of():req.extra());
    customers.findByDni(req.dni()).ifPresent(c->i.customerId=c.id);
    try { vicidial.externalStatus(req.agentUser(),req.dispo(),req.leadId(),req.campaign()); i.syncStatus=SyncStatus.SYNCED; i.lastError=null; }
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

  public record PreviewReq(String agentUser,Long leadId,String campaign,String action){}
  @PostMapping("/preview-action") Map<String,Object> preview(@RequestBody PreviewReq req){ vicidial.previewAction(req.agentUser(),req.leadId(),req.campaign(),req.action()); return Map.of("ok",true); }
  public record PauseReq(String agentUser,String action){}
  @PostMapping("/pause") Map<String,Object> pause(@RequestBody PauseReq req){ vicidial.pause(req.agentUser(),req.action()); return Map.of("ok",true); }

  private String requireAuth(Authentication auth) {
    if (auth == null || auth.getName() == null || auth.getName().isBlank()) throw new RuntimeException("Unauthorized");
    return auth.getName();
  }

  private String buildPhonePass(String phoneLogin) {
    return "anexo_" + phoneLogin;
  }

  private List<String> parseCampaigns(String raw) {
    if (raw == null || raw.isBlank()) return List.of();
    var campaigns = new LinkedHashSet<String>();
    var matcher = java.util.regex.Pattern.compile("campaign(?:_id)?=([^&\\s]+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(raw);
    while (matcher.find()) campaigns.add(matcher.group(1));
    if (campaigns.isEmpty()) {
      raw.lines()
          .map(String::trim)
          .filter(line -> !line.isBlank())
          .forEach(campaigns::add);
    }
    return campaigns.stream().limit(200).toList();
  }

  private String extract(String raw, String key){
    var m=java.util.regex.Pattern.compile(key+"=([^&\\n]+)").matcher(raw); return m.find()?m.group(1):"";
  }
  private Long extractLong(String raw,String key){ try{return Long.parseLong(extract(raw,key));}catch(Exception e){return null;} }

  private boolean isSuccess(String raw) {
    String r = Objects.toString(raw, "").toUpperCase(Locale.ROOT);
    return r.contains("SUCCESS") || r.contains("LOGGED") || r.contains("OK");
  }
}
