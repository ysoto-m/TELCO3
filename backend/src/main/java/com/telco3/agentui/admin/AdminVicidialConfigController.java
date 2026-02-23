package com.telco3.agentui.admin;

import com.telco3.agentui.vicidial.VicidialConfigService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/config/vicidial")
public class AdminVicidialConfigController {
  private final VicidialConfigService configService;

  public AdminVicidialConfigController(VicidialConfigService configService) {
    this.configService = configService;
  }

  @GetMapping
  public Map<String, Object> get() {
    var config = configService.getStoredConfigMasked();
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("baseUrl", config.baseUrl());
    response.put("apiUser", config.apiUser());
    response.put("apiPass", config.apiPassMasked());
    response.put("source", config.source());
    response.put("updatedAt", config.updatedAt());
    return response;
  }

  @PutMapping
  @ResponseStatus(HttpStatus.OK)
  public Map<String, Object> put(@Valid @RequestBody VicidialConfigRequest req) {
    validate(req);
    configService.saveConfig(new VicidialConfigService.VicidialConfigUpdateRequest(
        req.baseUrl(),
        req.apiUser(),
        req.apiPass(),
        req.source()
    ));
    return Map.of("ok", true);
  }

  private void validate(VicidialConfigRequest req) {
    if (!isValidHttpUrl(req.baseUrl())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "BASE_URL debe ser una URL http/https válida");
    }
    if (!StringUtils.hasText(req.apiUser())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "USER es requerido");
    }
    if (!StringUtils.hasText(req.apiPass())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PASS es requerido");
    }
  }

  private boolean isValidHttpUrl(String raw) {
    try {
      URI uri = URI.create(raw);
      return "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
    } catch (Exception ex) {
      return false;
    }
  }

  public record VicidialConfigRequest(@NotBlank String baseUrl, @NotBlank String apiUser, @NotBlank String apiPass,
                                      String source) {
  }
}
