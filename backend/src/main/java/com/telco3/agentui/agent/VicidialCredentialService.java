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

  public void save(String appUsername, String agentUser, String agentPass, String phoneLogin, String phonePass, String campaign) throws Exception {
    var entity = repo.findByAppUsernameAndAgentUser(appUsername, agentUser).orElse(new AgentVicidialCredentialEntity());
    entity.appUsername = appUsername;
    entity.agentUser = agentUser;
    entity.agentPassEncrypted = encrypt(agentPass);
    entity.phoneLogin = phoneLogin;
    entity.phonePassEncrypted = encrypt(phonePass);
    entity.campaign = campaign;
    entity.updatedAt = OffsetDateTime.now();
    repo.save(entity);
  }

  public Optional<ResolvedCredential> find(String appUsername, String agentUser) {
    return repo.findByAppUsernameAndAgentUser(appUsername, agentUser)
        .map(e -> {
          try {
            return new ResolvedCredential(e.agentUser, decrypt(e.agentPassEncrypted), e.phoneLogin, decrypt(e.phonePassEncrypted), e.campaign);
          } catch (Exception ex) {
            throw new RuntimeException(ex);
          }
        });
  }

  public record ResolvedCredential(String agentUser, String agentPass, String phoneLogin, String phonePass, String campaign) {}

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
