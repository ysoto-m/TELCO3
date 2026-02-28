package com.telco3.agentui.vicidial;

import com.telco3.agentui.domain.AppConfigEntity;
import com.telco3.agentui.domain.AppConfigRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class VicidialConfigService {
  public static final String KEY_BASE_URL = "VICIDIAL_BASE_URL";
  public static final String KEY_API_USER = "VICIDIAL_API_USER";
  public static final String KEY_API_PASS = "VICIDIAL_API_PASS";
  public static final String KEY_SOURCE = "VICIDIAL_SOURCE";
  public static final String KEY_DB_HOST = "vicidial.dbHost";
  public static final String KEY_DB_PORT = "vicidial.dbPort";
  public static final String KEY_DB_NAME = "vicidial.dbName";
  public static final String KEY_DB_USER = "vicidial.dbUser";
  public static final String KEY_DB_PASS = "vicidial.dbPass";
  public static final String KEY_BASE_URL_LOWER = "vicidial.baseUrl";
  public static final String KEY_API_USER_LOWER = "vicidial.apiUser";
  public static final String KEY_API_PASS_LOWER = "vicidial.apiPass";
  public static final String KEY_SOURCE_LOWER = "vicidial.source";

  private final AppConfigRepository configRepository;
  private final Environment environment;
  private final SecretKeySpec secretKey;
  private final boolean seedDevConfig;
  private final AtomicLong configVersion = new AtomicLong(0);

  public VicidialConfigService(
      AppConfigRepository configRepository,
      Environment environment,
      @Value("${app.crypto-key}") String cryptoKey,
      @Value("${app.seed-dev-config:false}") boolean seedDevConfig
  ) {
    this.configRepository = configRepository;
    this.environment = environment;
    this.secretKey = new SecretKeySpec(cryptoKey.getBytes(StandardCharsets.UTF_8), "AES");
    this.seedDevConfig = seedDevConfig;
  }

  @PostConstruct
  void seedForDevIfNeeded() {
    boolean devProfile = java.util.Arrays.stream(environment.getActiveProfiles()).anyMatch("dev"::equalsIgnoreCase);
    if (!devProfile && !seedDevConfig) {
      return;
    }

    upsertIfMissing(KEY_BASE_URL, environment.getProperty(KEY_BASE_URL, "http://172.17.248.220"), false);
    upsertIfMissing(KEY_API_USER, environment.getProperty(KEY_API_USER, "apiuser"), false);
    upsertIfMissing(KEY_API_PASS, environment.getProperty(KEY_API_PASS, "rushpe15"), true);

    String source = environment.getProperty(KEY_SOURCE, "react_crm");
    if (StringUtils.hasText(source)) {
      upsertIfMissing(KEY_SOURCE, source, false);
    }
  }

  public ResolvedVicidialConfig resolve() {
    ValueWithOrigin baseUrl = resolveValue(new String[]{KEY_BASE_URL_LOWER, KEY_BASE_URL}, false);
    ValueWithOrigin apiUser = resolveValue(new String[]{KEY_API_USER_LOWER, KEY_API_USER}, false);
    ValueWithOrigin apiPass = resolveValue(new String[]{KEY_API_PASS_LOWER, KEY_API_PASS}, true);
    ValueWithOrigin source = resolveValue(new String[]{KEY_SOURCE_LOWER, KEY_SOURCE}, false);

    String normalizedBase = cleanBaseUrl(baseUrl.value());
    boolean missing = !StringUtils.hasText(normalizedBase) || !StringUtils.hasText(apiUser.value()) || !StringUtils.hasText(apiPass.value());
    String originHint = "Config origen: baseUrl=" + baseUrl.origin() + ", user=" + apiUser.origin() + ", pass=" + apiPass.origin();

    return new ResolvedVicidialConfig(normalizedBase, trim(apiUser.value()), trim(apiPass.value()), trim(source.value()), missing, originHint);
  }

  public StoredVicidialConfig getStoredConfigMasked() {
    return new StoredVicidialConfig(
        firstStored(KEY_BASE_URL_LOWER, KEY_BASE_URL),
        firstStored(KEY_API_USER_LOWER, KEY_API_USER),
        firstStored(KEY_API_PASS_LOWER, KEY_API_PASS) == null ? null : "********",
        firstStored(KEY_SOURCE_LOWER, KEY_SOURCE),
        firstStored(KEY_DB_HOST),
        firstStored(KEY_DB_PORT),
        firstStored(KEY_DB_NAME),
        firstStored(KEY_DB_USER),
        firstStored(KEY_DB_PASS) == null ? null : "********",
        resolveLatestUpdateAt()
    );
  }

  public void saveConfig(VicidialConfigUpdateRequest request) {
    upsert(KEY_BASE_URL_LOWER, cleanBaseUrl(request.baseUrl()), false);
    upsert(KEY_API_USER_LOWER, trim(request.apiUser()), false);
    upsert(KEY_API_PASS_LOWER, trim(request.apiPass()), true);

    if (StringUtils.hasText(request.source())) {
      upsert(KEY_SOURCE_LOWER, trim(request.source()), false);
    }
    if (StringUtils.hasText(request.dbHost())) {
      upsert(KEY_DB_HOST, trim(request.dbHost()), false);
    }
    if (StringUtils.hasText(request.dbPort())) {
      upsert(KEY_DB_PORT, trim(request.dbPort()), false);
    }
    if (StringUtils.hasText(request.dbName())) {
      upsert(KEY_DB_NAME, trim(request.dbName()), false);
    }
    if (StringUtils.hasText(request.dbUser())) {
      upsert(KEY_DB_USER, trim(request.dbUser()), false);
    }
    if (StringUtils.hasText(request.dbPass())) {
      upsert(KEY_DB_PASS, trim(request.dbPass()), true);
    }
    configVersion.incrementAndGet();
  }

  public ResolvedVicidialDbConfig resolveDbConfig() {
    ValueWithOrigin host = resolveValue(new String[]{KEY_DB_HOST}, false);
    ValueWithOrigin port = resolveValue(new String[]{KEY_DB_PORT}, false);
    ValueWithOrigin dbName = resolveValue(new String[]{KEY_DB_NAME}, false);
    ValueWithOrigin dbUser = resolveValue(new String[]{KEY_DB_USER}, false);
    ValueWithOrigin dbPass = resolveValue(new String[]{KEY_DB_PASS}, true);

    boolean missing = !StringUtils.hasText(host.value())
        || !StringUtils.hasText(port.value())
        || !StringUtils.hasText(dbName.value())
        || !StringUtils.hasText(dbUser.value())
        || !StringUtils.hasText(dbPass.value());

    return new ResolvedVicidialDbConfig(trim(host.value()), trim(port.value()), trim(dbName.value()), trim(dbUser.value()), trim(dbPass.value()), missing, configVersion.get());
  }

  public void assertVicidialApiConfigured() {
    ResolvedVicidialConfig cfg = resolve();
    if (!cfg.missingRequired()) {
      return;
    }
    throw new VicidialServiceException(
        org.springframework.http.HttpStatus.CONFLICT,
        "VICIDIAL_SETTINGS_MISSING",
        "Falta configuración de Vicidial.",
        "configure en Admin > Settings",
        null
    );
  }

  public long configVersion() {
    return configVersion.get();
  }

  private void upsertIfMissing(String key, String value, boolean encrypted) {
    if (configRepository.findById(key).isPresent() || !StringUtils.hasText(value)) {
      return;
    }
    upsert(key, value, encrypted);
  }

  private void upsert(String key, String value, boolean encrypted) {
    AppConfigEntity entity = configRepository.findById(key).orElseGet(AppConfigEntity::new);
    entity.key = key;
    entity.value = encrypted ? encrypt(value) : value;
    entity.updatedAt = OffsetDateTime.now();
    configRepository.save(entity);
  }

  private ValueWithOrigin resolveValue(String[] keys, boolean encrypted) {
    String matchedKey = null;
    String fromDb = null;
    for (String key : keys) {
      fromDb = getStored(key);
      if (StringUtils.hasText(fromDb)) {
        matchedKey = key;
        break;
      }
    }
    if (StringUtils.hasText(fromDb)) {
      if (encrypted) {
        try {
          return new ValueWithOrigin(decrypt(fromDb), "DB:" + matchedKey);
        } catch (Exception ex) {
          return new ValueWithOrigin(null, "DB_INVALID:" + matchedKey);
        }
      }
      return new ValueWithOrigin(fromDb, "DB:" + matchedKey);
    }

    String fromEnv = null;
    for (String key : keys) {
      fromEnv = environment.getProperty(key);
      if (StringUtils.hasText(fromEnv)) {
        break;
      }
    }
    return new ValueWithOrigin(fromEnv, StringUtils.hasText(fromEnv) ? "ENV" : "MISSING");
  }

  private String firstStored(String... keys) {
    for (String key : keys) {
      String value = getStored(key);
      if (StringUtils.hasText(value)) {
        return value;
      }
    }
    return null;
  }

  private String getStored(String key) {
    return configRepository.findById(key).map(v -> v.value).orElse(null);
  }

  private OffsetDateTime resolveLatestUpdateAt() {
    return configRepository.findAll().stream()
        .filter(c -> c.key != null && c.key.startsWith("VICIDIAL_"))
        .map(c -> c.updatedAt)
        .filter(Objects::nonNull)
        .max(OffsetDateTime::compareTo)
        .orElse(null);
  }

  private String encrypt(String raw) {
    try {
      Cipher cipher = Cipher.getInstance("AES");
      cipher.init(Cipher.ENCRYPT_MODE, secretKey);
      return Base64.getEncoder().encodeToString(cipher.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException("No se pudo cifrar configuración de Vicidial", ex);
    }
  }

  private String decrypt(String encrypted) {
    try {
      Cipher cipher = Cipher.getInstance("AES");
      cipher.init(Cipher.DECRYPT_MODE, secretKey);
      return new String(cipher.doFinal(Base64.getDecoder().decode(encrypted)), StandardCharsets.UTF_8);
    } catch (Exception ex) {
      throw new IllegalStateException("No se pudo descifrar configuración de Vicidial", ex);
    }
  }

  private String cleanBaseUrl(String value) {
    String cleaned = trim(value);
    if (cleaned.endsWith("/")) {
      return cleaned.substring(0, cleaned.length() - 1);
    }
    return cleaned;
  }

  private String trim(String value) {
    return Objects.toString(value, "").trim();
  }

  private record ValueWithOrigin(String value, String origin) {
  }

  public record ResolvedVicidialConfig(String baseUrl, String apiUser, String apiPass, String source,
                                       boolean missingRequired, String originHint) {
  }

  public record StoredVicidialConfig(String baseUrl, String apiUser, String apiPassMasked, String source,
                                     String dbHost, String dbPort, String dbName, String dbUser, String dbPassMasked,
                                     OffsetDateTime updatedAt) {
  }

  public record VicidialConfigUpdateRequest(String baseUrl, String apiUser, String apiPass, String source,
                                            String dbHost, String dbPort, String dbName, String dbUser, String dbPass) {
  }

  public record ResolvedVicidialDbConfig(String dbHost, String dbPort, String dbName, String dbUser, String dbPass,
                                         boolean missingRequired, long configVersion) {
  }
}
