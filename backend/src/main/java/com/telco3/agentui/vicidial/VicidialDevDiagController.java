package com.telco3.agentui.vicidial;

import org.springframework.context.annotation.Conditional;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@RestController
@Conditional(DevEnvironmentCondition.class)
@RequestMapping("/api/dev/vicidial")
public class VicidialDevDiagController {
  private final VicidialDiagnosticsService diagnosticsService;

  public VicidialDevDiagController(VicidialDiagnosticsService diagnosticsService) {
    this.diagnosticsService = diagnosticsService;
  }

  @GetMapping("/diag")
  Map<String, Object> diag(Authentication auth) {
    var config = diagnosticsService.resolvedConfig();
    var probe = diagnosticsService.probe(config);
    var dns = diagnosticsService.dnsResolve(config.baseUrl());

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("requestedBy", auth == null ? "anonymous" : Objects.toString(auth.getName(), "anonymous"));
    out.put("baseUrl", config.baseUrl());
    out.put("user", config.user());
    out.put("source", config.source());
    out.put("apiPass", config.maskedPass());
    out.put("healthPath", config.healthPath());
    out.put("reachable", probe.reachable());
    out.put("httpStatus", probe.httpStatus());
    out.put("latencyMs", probe.latencyMs());
    out.put("error", probe.error());

    Map<String, Object> dnsData = new LinkedHashMap<>();
    dnsData.put("host", dns.host());
    dnsData.put("ip", dns.ip());
    dnsData.put("error", dns.error());
    out.put("dns", dnsData);

    return out;
  }
}
