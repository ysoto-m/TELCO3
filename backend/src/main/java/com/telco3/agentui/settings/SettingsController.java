package com.telco3.agentui.settings;

import com.telco3.agentui.vicidial.VicidialConfigService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {
  private final VicidialConfigService configService;

  public SettingsController(VicidialConfigService configService) {
    this.configService = configService;
  }

  public record SettingsReq(@NotBlank String baseUrl, @NotBlank String apiUser, @NotBlank String apiPass, String source,
                            String dbHost, String dbPort, String dbName, String dbUser, String dbPass) {
  }

  @GetMapping("/vicidial")
  public Map<String, Object> get() {
    var s = configService.getStoredConfigMasked();
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("baseUrl", n(s.baseUrl()));
    response.put("apiUser", n(s.apiUser()));
    response.put("apiPass", s.apiPassMasked());
    response.put("source", n(s.source()));
    response.put("dbHost", n(s.dbHost()));
    response.put("dbPort", n(s.dbPort()));
    response.put("dbName", n(s.dbName()));
    response.put("dbUser", n(s.dbUser()));
    response.put("dbPass", s.dbPassMasked());
    response.put("updatedAt", s.updatedAt());
    return response;
  }

  @PutMapping("/vicidial")
  public Map<String, Object> put(@RequestBody SettingsReq req) {
    configService.saveConfig(new VicidialConfigService.VicidialConfigUpdateRequest(
        req.baseUrl(), req.apiUser(), req.apiPass(), req.source(), req.dbHost(), req.dbPort(), req.dbName(), req.dbUser(), req.dbPass()
    ));
    return Map.of("ok", true);
  }

  private String n(String s) {
    return s == null ? "" : s;
  }
}
