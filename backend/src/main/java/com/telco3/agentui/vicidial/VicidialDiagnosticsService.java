package com.telco3.agentui.vicidial;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.core.env.Environment;

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
  private final VicidialConfigService configService;
  private final Environment environment;

  public VicidialDiagnosticsService(VicidialConfigService configService, Environment environment) {
    this.configService = configService;
    this.environment = environment;
  }

  public boolean isDevMode() {
    boolean profileDev = Arrays.stream(environment.getActiveProfiles()).anyMatch("dev"::equalsIgnoreCase);
    return profileDev || "dev".equalsIgnoreCase(environment.getProperty("APP_ENV", environment.getProperty("app.env", "")));
  }

  public VicidialResolvedConfig resolvedConfig() {
    var resolved = configService.resolve();
    String healthPath = environment.getProperty("VICIDIAL_HEALTH_PATH", "/");
    if (!healthPath.startsWith("/")) {
      healthPath = "/" + healthPath;
    }
    return new VicidialResolvedConfig(resolved.baseUrl(), resolved.apiUser(), resolved.apiPass(), resolved.source(), healthPath, resolved.originHint());
  }

  public void assertVicidialReadyOrThrow() {
    VicidialResolvedConfig config = resolvedConfig();
    validateConfigOrThrow(config);
    HealthProbeResult probe = probe(config);
    if (!probe.reachable()) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("cause", Objects.toString(probe.causeClass(), "Unknown"));
      details.put("error", Objects.toString(probe.error(), "No response"));
      details.put("hint", config.originHint());
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
    if (!StringUtils.hasText(config.baseUrl()) || !isHttpUrl(config.baseUrl()) || !StringUtils.hasText(config.user()) || !StringUtils.hasText(config.apiPass())) {
      throw new VicidialServiceException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "VICIDIAL_CONFIG_MISSING",
          "Falta configuración de Vicidial (BASE_URL/USER/PASS). Configure en el Panel Admin o variables de entorno.",
          "Revisar panel Admin, docker-compose/.env y printenv dentro del contenedor. " + config.originHint(),
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

  private String abbreviate(String msg) {
    if (!StringUtils.hasText(msg)) {
      return "Sin detalle";
    }
    String normalized = msg.replace('\n', ' ').trim();
    return normalized.length() > 200 ? normalized.substring(0, 200) : normalized;
  }

  public record VicidialResolvedConfig(String baseUrl, String user, String apiPass, String source, String healthPath,
                                       String originHint) {
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
