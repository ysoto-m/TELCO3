package com.telco3.agentui.agent;

import com.telco3.agentui.domain.AgentVicidialCredentialRepository;
import com.telco3.agentui.domain.Entities.AgentVicidialCredentialEntity;
import com.telco3.agentui.domain.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

@Service
public class VicidialCredentialService {
  private static final Logger log = LoggerFactory.getLogger(VicidialCredentialService.class);

  private final AgentVicidialCredentialRepository repo;
  private final UserRepository userRepository;
  private final SecretKeySpec key;
  private final Environment environment;

  public VicidialCredentialService(
      AgentVicidialCredentialRepository repo,
      UserRepository userRepository,
      @Value("${app.crypto-key}") String cryptoKey,
      Environment environment
  ) {
    this.repo = repo;
    this.userRepository = userRepository;
    this.key = new SecretKeySpec(cryptoKey.getBytes(StandardCharsets.UTF_8), "AES");
    this.environment = environment;
  }

  public AgentProfileState getProfile(String appUsername) {
    var entity = repo.findByAppUsername(appUsername).orElse(null);
    boolean hasAgentPass = resolveAgentPass(appUsername).isPresent();
    if (entity == null) {
      return new AgentProfileState(hasAgentPass, null, null, true, false, null, null, null, appUsername);
    }
    return new AgentProfileState(
        hasAgentPass,
        entity.lastPhoneLogin,
        entity.lastCampaign,
        entity.rememberCredentials,
        entity.connected,
        entity.connectedPhoneLogin,
        entity.connectedCampaign,
        entity.connectedAt,
        entity.agentUser
    );
  }

  public void updateAgentPass(String appUsername, String agentPass) throws Exception {
    var user = userRepository.findByUsername(appUsername)
        .orElseThrow(() -> new IllegalStateException("No existe usuario para almacenar agent_pass"));
    user.agentPassEncrypted = encrypt(agentPass);
    userRepository.save(user);
  }

  public Optional<String> resolveAgentPass(String appUsername) {
    return userRepository.findByUsername(appUsername)
        .map(e -> {
          if (e.agentPassEncrypted == null || e.agentPassEncrypted.isBlank()) return null;
          try {
            return decrypt(e.agentPassEncrypted);
          } catch (Exception ex) {
            throw new RuntimeException(ex);
          }
        });
  }

  public AgentVicidialCredentials resolveAgentCredentials(String appUsername) {
    String agentUser = appUsername;
    Optional<String> pass = resolveAgentPass(appUsername);
    if (pass.isPresent()) {
      return new AgentVicidialCredentials(agentUser, pass.get(), false, null);
    }

    if (isDevEnvironment()) {
      String fallback = "dev_" + appUsername;
      String warning = "DEV fallback activo: agent_pass ausente en users.agent_pass_encrypted. Usando valor temporal para pruebas.";
      log.warn("{} agentUser={}", warning, mask(agentUser));
      return new AgentVicidialCredentials(agentUser, fallback, true, warning);
    }
    return new AgentVicidialCredentials(agentUser, null, false, null);
  }

  public void saveLastSelection(String appUsername, String phoneLogin, String campaign, boolean rememberCredentials) {
    var entity = getOrCreate(appUsername);
    entity.rememberCredentials = rememberCredentials;
    if (rememberCredentials) {
      entity.lastPhoneLogin = phoneLogin;
      entity.lastCampaign = campaign;
    }
    entity.updatedAt = OffsetDateTime.now();
    repo.save(entity);
  }

  public void markConnected(String appUsername, String phoneLogin, String campaign) {
    var entity = getOrCreate(appUsername);
    entity.connected = true;
    entity.connectedPhoneLogin = phoneLogin;
    entity.connectedCampaign = campaign;
    entity.connectedAt = OffsetDateTime.now();
    entity.updatedAt = OffsetDateTime.now();
    repo.save(entity);
  }

  public void markDisconnected(String appUsername) {
    var entity = getOrCreate(appUsername);
    entity.connected = false;
    entity.connectedPhoneLogin = null;
    entity.connectedCampaign = null;
    entity.connectedAt = null;
    entity.updatedAt = OffsetDateTime.now();
    repo.save(entity);
  }

  private AgentVicidialCredentialEntity getOrCreate(String appUsername) {
    return repo.findByAppUsername(appUsername).orElseGet(() -> {
      var entity = new AgentVicidialCredentialEntity();
      entity.appUsername = appUsername;
      entity.agentUser = appUsername;
      return entity;
    });
  }

  private boolean isDevEnvironment() {
    boolean profileDev = Arrays.stream(environment.getActiveProfiles()).anyMatch("dev"::equalsIgnoreCase);
    String appEnv = environment.getProperty("APP_ENV", environment.getProperty("app.env", ""));
    return profileDev || "dev".equalsIgnoreCase(appEnv);
  }

  private String mask(String value) {
    if (value == null || value.isBlank()) return "***";
    if (value.length() <= 2) return "**";
    return value.substring(0, 2) + "***" + value.substring(value.length() - 1);
  }

  public record AgentVicidialCredentials(String agentUser, String agentPass, boolean fallbackUsed, String warning) {}

  public record AgentProfileState(
      boolean hasAgentPass,
      String lastPhoneLogin,
      String lastCampaign,
      boolean rememberCredentials,
      boolean connected,
      String connectedPhoneLogin,
      String connectedCampaign,
      OffsetDateTime connectedAt,
      String agentUser
  ) {}

  private String encrypt(String value) throws Exception {
    var cipher = Cipher.getInstance("AES");
    cipher.init(Cipher.ENCRYPT_MODE, key);
    return Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
  }

  private String decrypt(String value) throws Exception {
    var cipher = Cipher.getInstance("AES");
    cipher.init(Cipher.DECRYPT_MODE, key);
    return new String(cipher.doFinal(Base64.getDecoder().decode(value)), StandardCharsets.UTF_8);
  }
}
