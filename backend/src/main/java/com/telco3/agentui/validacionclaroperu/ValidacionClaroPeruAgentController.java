package com.telco3.agentui.validacionclaroperu;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agent/validacion-claro-peru")
public class ValidacionClaroPeruAgentController {
  private final ValidacionClaroPeruService service;

  public ValidacionClaroPeruAgentController(ValidacionClaroPeruService service) {
    this.service = service;
  }

  @GetMapping("/disposiciones")
  public Map<String, Object> disposiciones(@RequestParam(required = false) String campaignId, Authentication auth) {
    requireAuth(auth);
    List<Map<String, Object>> items = service.listDispositions(campaignId);
    return Map.of("items", items, "campaignId", campaignId == null ? ValidacionClaroPeruService.DEFAULT_CAMPAIGN : campaignId);
  }

  @GetMapping("/subtipificaciones")
  public Map<String, Object> subtipificaciones(
      @RequestParam(required = false) String campaignId,
      @RequestParam(required = false) String tipificacion,
      Authentication auth
  ) {
    requireAuth(auth);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("items", service.listSubtipificaciones(campaignId, tipificacion));
    response.put("campaignId", campaignId == null ? ValidacionClaroPeruService.DEFAULT_CAMPAIGN : campaignId);
    response.put("tipificacion", tipificacion);
    return response;
  }

  @GetMapping("/formulario")
  public Map<String, Object> formulario(@RequestParam String documento, Authentication auth) {
    requireAuth(auth);
    return service.lookupByDocumento(documento);
  }

  @PostMapping("/gestion")
  public Map<String, Object> guardarGestion(
      @RequestBody ValidacionClaroPeruService.SaveGestionRequest request,
      Authentication auth
  ) {
    return service.saveGestionFinal(requireAuth(auth), request);
  }

  private String requireAuth(Authentication auth) {
    if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
      throw new RuntimeException("Unauthorized");
    }
    return auth.getName();
  }
}

