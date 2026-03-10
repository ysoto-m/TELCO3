package com.telco3.agentui.manual2;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/agent/manual2")
public class Manual2AgentController {
  private final Manual2Service manual2Service;

  public Manual2AgentController(Manual2Service manual2Service) {
    this.manual2Service = manual2Service;
  }

  @GetMapping("/disposiciones")
  public Map<String, Object> disposiciones(@RequestParam(required = false) String campaignId, Authentication auth) {
    requireAuth(auth);
    List<Map<String, Object>> items = manual2Service.listDispositions(campaignId);
    return Map.of("items", items, "campaignId", campaignId == null ? "Manual2" : campaignId);
  }

  @GetMapping("/subtipificaciones")
  public Map<String, Object> subtipificaciones(
      @RequestParam(required = false) String campaignId,
      @RequestParam(required = false) String tipificacion,
      Authentication auth
  ) {
    requireAuth(auth);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("items", manual2Service.listSubtipificaciones(campaignId, tipificacion));
    response.put("campaignId", campaignId == null ? "Manual2" : campaignId);
    response.put("tipificacion", tipificacion);
    return response;
  }

  @GetMapping("/contacto")
  public Map<String, Object> contacto(@RequestParam String phoneNumber, Authentication auth) {
    requireAuth(auth);
    return manual2Service.lookupContacto(phoneNumber);
  }

  @Deprecated(forRemoval = false, since = "1.3.0")
  @PostMapping("/formulario")
  public Map<String, Object> guardarFormulario(@RequestBody Manual2Service.SaveFormularioRequest request, Authentication auth) {
    return manual2Service.saveFormulario(requireAuth(auth), request);
  }

  @PostMapping("/gestion")
  public Map<String, Object> guardarGestion(@RequestBody Manual2Service.SaveGestionRequest request, Authentication auth) {
    return manual2Service.saveGestionFinal(requireAuth(auth), request);
  }

  @Deprecated(forRemoval = false, since = "1.3.0")
  @GetMapping("/historial")
  public Map<String, Object> historial(@RequestParam String phoneNumber, Authentication auth) {
    requireAuth(auth);
    return manual2Service.historyByTelefono(phoneNumber);
  }

  private String requireAuth(Authentication auth) {
    if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
      throw new RuntimeException("Unauthorized");
    }
    return auth.getName();
  }
}
