package com.telco3.agentui.vicidial;

import com.telco3.agentui.domain.AppConfigEntity;
import com.telco3.agentui.domain.AppConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VicidialConfigServiceTest {
  private final AppConfigRepository repo = mock(AppConfigRepository.class);
  private final MockEnvironment environment = new MockEnvironment();
  private VicidialConfigService service;

  @BeforeEach
  void setUp() {
    reset(repo);
    when(repo.findById(any())).thenReturn(Optional.empty());
    service = new VicidialConfigService(repo, environment, "1234567890123456", false);
  }

  @Test
  void dbHasPriorityOverEnv() {
    environment.setProperty("vicidial.baseUrl", "http://from-env");
    environment.setProperty("vicidial.apiUser", "env-user");
    environment.setProperty("vicidial.apiPass", "env-pass");

    when(repo.findById("vicidial.baseUrl")).thenReturn(Optional.of(entity("vicidial.baseUrl", "http://from-db")));
    when(repo.findById("vicidial.apiUser")).thenReturn(Optional.of(entity("vicidial.apiUser", "db-user")));

    VicidialConfigService temp = new VicidialConfigService(repo, environment, "1234567890123456", false);
    String encrypted = captureEncryptedPass(temp, "db-pass");
    when(repo.findById("vicidial.apiPass")).thenReturn(Optional.of(entity("vicidial.apiPass", encrypted)));

    var resolved = service.resolve();

    assertEquals("http://from-db", resolved.baseUrl());
    assertEquals("db-user", resolved.apiUser());
    assertEquals("db-pass", resolved.apiPass());
    assertFalse(resolved.missingRequired());
  }

  @Test
  void returnsMissingWhenRequiredValuesDoNotExist() {
    var resolved = service.resolve();
    assertTrue(resolved.missingRequired());
  }

  @Test
  void passwordIsStoredEncryptedAndResolvedDecrypted() {
    var saved = new ArrayList<AppConfigEntity>();
    when(repo.save(any())).thenAnswer(invocation -> {
      AppConfigEntity entity = invocation.getArgument(0);
      saved.add(entity);
      return entity;
    });

    service.saveConfig(new VicidialConfigService.VicidialConfigUpdateRequest("http://base", "user", "plain-pass", "react_crm", null, null, null, null, null));

    String encrypted = saved.stream().filter(e -> "vicidial.apiPass".equals(e.key)).map(e -> e.value).findFirst().orElseThrow();
    assertNotEquals("plain-pass", encrypted);

    when(repo.findById("vicidial.baseUrl")).thenReturn(Optional.of(entity("vicidial.baseUrl", "http://base")));
    when(repo.findById("vicidial.apiUser")).thenReturn(Optional.of(entity("vicidial.apiUser", "user")));
    when(repo.findById("vicidial.apiPass")).thenReturn(Optional.of(entity("vicidial.apiPass", encrypted)));
    when(repo.findById("vicidial.source")).thenReturn(Optional.of(entity("vicidial.source", "react_crm")));

    var resolved = service.resolve();
    assertEquals("plain-pass", resolved.apiPass());
  }

  private String captureEncryptedPass(VicidialConfigService localService, String plainPass) {
    var saved = new ArrayList<AppConfigEntity>();
    when(repo.save(any())).thenAnswer(invocation -> {
      AppConfigEntity entity = invocation.getArgument(0);
      saved.add(entity);
      return entity;
    });

    localService.saveConfig(new VicidialConfigService.VicidialConfigUpdateRequest("http://tmp", "tmp", plainPass, null, null, null, null, null, null));
    reset(repo);
    when(repo.findById(any())).thenReturn(Optional.empty());
    return saved.stream().filter(e -> "vicidial.apiPass".equals(e.key)).map(e -> e.value).findFirst().orElseThrow();
  }

  private AppConfigEntity entity(String key, String value) {
    AppConfigEntity e = new AppConfigEntity();
    e.key = key;
    e.value = value;
    return e;
  }
}
