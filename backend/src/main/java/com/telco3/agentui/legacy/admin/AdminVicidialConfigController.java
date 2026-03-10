package com.telco3.agentui.legacy.admin;

import com.telco3.agentui.settings.SettingsController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Deprecated(forRemoval = false, since = "1.2.0")
@RestController
@RequestMapping("/api/admin/config/vicidial")
public class AdminVicidialConfigController {
  private final SettingsController settingsController;

  public AdminVicidialConfigController(SettingsController settingsController) {
    this.settingsController = settingsController;
  }

  @Deprecated(forRemoval = false, since = "1.2.0")
  @GetMapping
  public Map<String, Object> get() {
    return settingsController.get();
  }

  @Deprecated(forRemoval = false, since = "1.2.0")
  @PutMapping
  public Map<String, Object> put(@RequestBody VicidialConfigRequest req) {
    return settingsController.put(new SettingsController.SettingsReq(
        req.baseUrl,
        req.apiUser,
        req.apiPass,
        req.source,
        req.dbHost,
        req.dbPort,
        req.dbName,
        req.dbUser,
        req.dbPass
    ));
  }

  public static class VicidialConfigRequest {
    public String baseUrl;
    public String apiUser;
    public String apiPass;
    public String source;
    public String dbHost;
    public String dbPort;
    public String dbName;
    public String dbUser;
    public String dbPass;
  }
}
