package com.telco3.agentui.manual2;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/manual2")
public class Manual2AdminController {
  private final Manual2Service manual2Service;

  public Manual2AdminController(Manual2Service manual2Service) {
    this.manual2Service = manual2Service;
  }

  @GetMapping("/reporte")
  public Map<String, Object> reporte(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String campana,
      @RequestParam(required = false) String agente,
      @RequestParam(required = false) String tipificacion,
      @RequestParam(required = false) String disposicion,
      @RequestParam(required = false) String subtipificacion,
      @RequestParam(required = false) String telefono,
      @RequestParam(required = false) String cliente,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size
  ) {
    var filter = new Manual2Service.ReportFilter(from, to, campana, agente, tipificacion, disposicion, subtipificacion, telefono, cliente, page, size);
    var report = manual2Service.report(filter);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("items", report.items());
    body.put("total", report.total());
    body.put("page", report.page());
    body.put("size", report.size());
    body.put("generatedAt", report.generatedAt());
    return body;
  }

  @GetMapping(value = "/reporte.csv", produces = MediaType.TEXT_PLAIN_VALUE)
  public String reporteCsv(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String campana,
      @RequestParam(required = false) String agente,
      @RequestParam(required = false) String tipificacion,
      @RequestParam(required = false) String disposicion,
      @RequestParam(required = false) String subtipificacion,
      @RequestParam(required = false) String telefono,
      @RequestParam(required = false) String cliente
  ) {
    var filter = new Manual2Service.ReportFilter(from, to, campana, agente, tipificacion, disposicion, subtipificacion, telefono, cliente, 0, 100000);
    return manual2Service.exportCsv(filter);
  }

  @GetMapping("/historial")
  public Map<String, Object> historial(@RequestParam String phoneNumber) {
    return manual2Service.historyByTelefono(phoneNumber);
  }

  @GetMapping("/subtipificaciones")
  public Map<String, Object> subtipificaciones(@RequestParam(required = false) String campana) {
    return Map.of(
        "items", manual2Service.listAdminSubtipificaciones(campana),
        "campana", campana == null ? "Manual2" : campana
    );
  }

  @PostMapping("/subtipificaciones")
  public Map<String, Object> createOrUpdateSubtipificacion(
      @RequestBody Manual2Service.UpsertSubtipificacionRequest request,
      Authentication auth
  ) {
    return manual2Service.createOrUpdateSubtipificacion(requireAuth(auth), request);
  }

  @PatchMapping("/subtipificaciones/{codigo}/activo")
  public Map<String, Object> setSubtipificacionActivo(
      @PathVariable String codigo,
      @RequestParam(required = false) String campana,
      @RequestParam boolean activo,
      Authentication auth
  ) {
    return manual2Service.setSubtipificacionActivo(requireAuth(auth), campana, codigo, activo);
  }

  private String requireAuth(Authentication auth) {
    if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
      throw new RuntimeException("Unauthorized");
    }
    return auth.getName();
  }
}
