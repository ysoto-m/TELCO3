package com.telco3.agentui.vicidial;

import com.telco3.agentui.domain.Entities;
import com.telco3.agentui.settings.SettingsController;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Service
public class VicidialDiagnosticsService {
  private final SettingsController settingsController;
  private final Environment environment;

  public VicidialDiagnosticsService(SettingsController settingsController, Environment environment) {
    this.settingsController = settingsController;
    this.environment = environment;
  }

  public boolean isDevMode() {
    boolean profileDev = Arrays.stream(environment.getActiveProfiles()).anyMatch("dev"::equalsIgnoreCase);
    return profileDev || "dev".equalsIgnoreCase(environment.getProperty("APP_ENV", environment.getProperty("app.env", "")));
  }

  public VicidialResolvedConfig resolvedConfig() {
    Entities.VicidialSettingsEntity settings = null;
    try {
      settings = settingsController.current();
    } catch (Exception ignored) {
    }

    String baseUrl = firstNonBlank(environment.getProperty("VICIDIAL_BASE_URL"), settings == null ? null : settings.baseUrl);
    String user = firstNonBlank(environment.getProperty("VICIDIAL_API_USER"), settings == null ? null : settings.apiUser);
    String source = firstNonBlank(environment.getProperty("VICIDIAL_SOURCE"), settings == null ? null : settings.source);
    String apiPass = environment.getProperty("VICIDIAL_API_PASS");
    if (!StringUtils.hasText(apiPass) && settings != null && StringUtils.hasText(settings.apiPassEncrypted)) {
      try {
        apiPass = settingsController.decryptedPass();
      } catch (Exception ignored) {
      }
    }

    String healthPath = environment.getProperty("VICIDIAL_HEALTH_PATH", "/");
    if (!healthPath.startsWith("/")) {
      healthPath = "/" + healthPath;
    }
    return new VicidialResolvedConfig(cleanBaseUrl(baseUrl), user, apiPass, source, healthPath);
  }

  public void assertVicidialReadyOrThrow() {
    VicidialResolvedConfig config = resolvedConfig();
    validateConfigOrThrow(config);
    HealthProbeResult probe = probe(config);
    if (!probe.reachable()) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("cause", Objects.toString(probe.causeClass(), "Unknown"));
      details.put("error", Objects.toString(probe.error(), "No response"));
      throw new VicidialServiceException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "VICIDIAL_UNREACHABLE",
          "No se pudo conectar a Vicidial usando VICIDIAL_BASE_URL=" + config.baseUrl() + ". Verifique conectividad y endpoint.",
          null,
          details
      );
    }
  }

  public void validateConfigOrThrow(VicidialResolvedConfig config) {
    if (!StringUtils.hasText(config.baseUrl()) || !isHttpUrl(config.baseUrl())) {
      throw new VicidialServiceException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "VICIDIAL_CONFIG_MISSING",
          "VICIDIAL_BASE_URL no está configurado. Configure VICIDIAL_BASE_URL en variables de entorno (por ejemplo: http://172.17.248.220).",
          "Revisar docker-compose/.env y printenv dentro del contenedor",
          null
      );
    }
  }

  public HealthProbeResult probe(VicidialResolvedConfig config) {
    URI target = URI.create(config.baseUrl() + config.healthPath());
    Instant start = Instant.now();
    try {
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
      HttpRequest request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(3)).GET().build();
      HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
      long latency = Duration.between(start, Instant.now()).toMillis();
      return new HealthProbeResult(true, String.valueOf(response.statusCode()), latency, null, null);
    } catch (Exception ex) {
      long latency = Duration.between(start, Instant.now()).toMillis();
      return new HealthProbeResult(false, "timeout", latency, abbreviate(ex.getMessage()), ex.getClass().getSimpleName());
    }
  }

  public DnsResult dnsResolve(String baseUrl) {
    try {
      URI uri = URI.create(baseUrl);
      String host = uri.getHost();
      if (!StringUtils.hasText(host)) {
        return new DnsResult(null, null, "Host vacío");
      }
      InetAddress addr = InetAddress.getByName(host);
      return new DnsResult(host, addr.getHostAddress(), null);
    } catch (Exception ex) {
      return new DnsResult(null, null, abbreviate(ex.getMessage()));
    }
  }

  private boolean isHttpUrl(String baseUrl) {
    try {
      URI uri = URI.create(baseUrl);
      String scheme = uri.getScheme();
      return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    } catch (Exception ignored) {
      return false;
    }
  }

  private String cleanBaseUrl(String raw) {
    String value = Objects.toString(raw, "").trim();
    if (value.endsWith("/")) {
      return value.substring(0, value.length() - 1);
    }
    return value;
  }

  private String firstNonBlank(String first, String second) {
    if (StringUtils.hasText(first)) {
      return first.trim();
    }
    return Objects.toString(second, "").trim();
  }

  private String abbreviate(String msg) {
    if (!StringUtils.hasText(msg)) {
      return "Sin detalle";
    }
    String normalized = msg.replace('\n', ' ').trim();
    return normalized.length() > 200 ? normalized.substring(0, 200) : normalized;
  }

  public record VicidialResolvedConfig(String baseUrl, String user, String apiPass, String source, String healthPath) {
    public String maskedPass() {
      if (!StringUtils.hasText(apiPass)) {
        return "";
      }
      return "****";
    }
  }

  public record HealthProbeResult(boolean reachable, String httpStatus, long latencyMs, String error, String causeClass) {}

  public record DnsResult(String host, String ip, String error) {}
}
