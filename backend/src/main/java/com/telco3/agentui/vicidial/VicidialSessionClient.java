package com.telco3.agentui.vicidial;


import org.springframework.stereotype.Component;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class VicidialSessionClient {
  private final VicidialConfigService configService;
  private final Map<String, SessionContext> sessionByAgent = new ConcurrentHashMap<>();

  public VicidialSessionClient(VicidialConfigService configService) {
    this.configService = configService;
  }

  public String connectPhone(String agentUser, String phoneLogin, String phonePass) {
    configService.assertVicidialApiConfigured();
    var settings = configService.resolve();
    String baseUrl = normalizeBaseUrl(settings.baseUrl());

    SessionContext existing = sessionByAgent.get(agentUser);
    CookieManager cookieManager = existing == null
        ? new CookieManager(null, CookiePolicy.ACCEPT_ALL)
        : existing.cookieManager();
    HttpClient client = existing == null
        ? HttpClient.newBuilder()
            .cookieHandler(cookieManager)
            .connectTimeout(Duration.ofSeconds(8))
            .build()
        : existing.client();

    Map<String, String> form = new LinkedHashMap<>();
    form.put("DB", "0");
    form.put("JS_browser_height", "641");
    form.put("JS_browser_width", "695");
    form.put("LOGINvarONE", "");
    form.put("LOGINvarTWO", "");
    form.put("LOGINvarTHREE", "");
    form.put("LOGINvarFOUR", "");
    form.put("LOGINvarFIVE", "");
    form.put("hide_relogin_fields", "");
    form.put("phone_login", phoneLogin);
    form.put("phone_pass", phonePass);
    String body = toForm(form);

    try {
      HttpRequest req = HttpRequest.newBuilder()
          .uri(URI.create(baseUrl + "/agc/vicidial.php"))
          .header("Content-Type", "application/x-www-form-urlencoded")
          .timeout(Duration.ofSeconds(12))
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build();

      HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());
      sessionByAgent.putIfAbsent(agentUser, new SessionContext(client, cookieManager));
      return "HTTP=" + response.statusCode() + " BODY=" + response.body();
    } catch (Exception e) {
      throw new RuntimeException("Error conectando phone en AGC: " + e.getMessage(), e);
    }
  }

  public void clear(String agentUser) {
    sessionByAgent.remove(agentUser);
  }

  private String normalizeBaseUrl(String raw) {
    String val = Objects.toString(raw, "").trim();
    if (val.endsWith("/")) return val.substring(0, val.length() - 1);
    return val;
  }

  private String toForm(Map<String, String> params) {
    StringBuilder sb = new StringBuilder();
    params.forEach((k, v) -> {
      if (!sb.isEmpty()) sb.append('&');
      sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8));
      sb.append('=');
      sb.append(URLEncoder.encode(Objects.toString(v, ""), StandardCharsets.UTF_8));
    });
    return sb.toString();
  }

  record SessionContext(HttpClient client, CookieManager cookieManager) {}
}
