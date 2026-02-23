package com.telco3.agentui.agent;

import com.telco3.agentui.domain.AgentVicidialCredentialRepository;
import com.telco3.agentui.domain.Entities.AgentVicidialCredentialEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;

@Service
public class VicidialCredentialService {
  private final AgentVicidialCredentialRepository repo;
  private final SecretKeySpec key;

  public VicidialCredentialService(AgentVicidialCredentialRepository repo, @Value("${app.crypto-key}") String cryptoKey) {
    this.repo = repo;
    this.key = new SecretKeySpec(cryptoKey.getBytes(StandardCharsets.UTF_8), "AES");
  }

  public AgentProfileState getProfile(String appUsername) {
    var entity = repo.findByAppUsername(appUsername).orElse(null);
    if (entity == null) {
      return new AgentProfileState(false, null, null, true, false, null, null, null, appUsername);
    }
    return new AgentProfileState(
        entity.agentPassEncrypted != null && !entity.agentPassEncrypted.isBlank(),
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
    var entity = getOrCreate(appUsername);
    entity.agentPassEncrypted = encrypt(agentPass);
    entity.updatedAt = OffsetDateTime.now();
    repo.save(entity);
  }

  public Optional<String> resolveAgentPass(String appUsername) {
    return repo.findByAppUsername(appUsername)
        .map(e -> {
          if (e.agentPassEncrypted == null || e.agentPassEncrypted.isBlank()) return null;
          try {
            return decrypt(e.agentPassEncrypted);
          } catch (Exception ex) {
            throw new RuntimeException(ex);
          }
        });
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
