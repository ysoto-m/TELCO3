package com.telco3.agentui.validacionclaroperu;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/validacion-claro-peru")
public class ValidacionClaroPeruAdminController {
  private final ValidacionClaroPeruService service;

  public ValidacionClaroPeruAdminController(ValidacionClaroPeruService service) {
    this.service = service;
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
      @RequestParam(required = false) String documento,
      @RequestParam(required = false) String encuesta,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size
  ) {
    var filter = new ValidacionClaroPeruService.ReportFilter(
        from,
        to,
        campana,
        agente,
        tipificacion,
        disposicion,
        subtipificacion,
        telefono,
        documento,
        encuesta,
        page,
        size
    );
    var report = service.report(filter);
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
      @RequestParam(required = false) String documento,
      @RequestParam(required = false) String encuesta
  ) {
    var filter = new ValidacionClaroPeruService.ReportFilter(
        from,
        to,
        campana,
        agente,
        tipificacion,
        disposicion,
        subtipificacion,
        telefono,
        documento,
        encuesta,
        0,
        100000
    );
    return service.exportCsv(filter);
  }
}

