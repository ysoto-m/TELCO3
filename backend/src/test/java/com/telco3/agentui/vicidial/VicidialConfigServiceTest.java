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
    service = new VicidialConfigService(repo, environment, "1234567890123456", false);
  }

  @Test
  void dbHasPriorityOverEnv() {
    environment.setProperty("VICIDIAL_BASE_URL", "http://from-env");
    environment.setProperty("VICIDIAL_API_USER", "env-user");
    environment.setProperty("VICIDIAL_API_PASS", "env-pass");

    when(repo.findById("VICIDIAL_BASE_URL")).thenReturn(Optional.of(entity("VICIDIAL_BASE_URL", "http://from-db")));
    when(repo.findById("VICIDIAL_API_USER")).thenReturn(Optional.of(entity("VICIDIAL_API_USER", "db-user")));

    VicidialConfigService temp = new VicidialConfigService(repo, environment, "1234567890123456", false);
    String encrypted = captureEncryptedPass(temp, "db-pass");
    when(repo.findById("VICIDIAL_API_PASS")).thenReturn(Optional.of(entity("VICIDIAL_API_PASS", encrypted)));
    when(repo.findById("VICIDIAL_SOURCE")).thenReturn(Optional.empty());

    var resolved = service.resolve();

    assertEquals("http://from-db", resolved.baseUrl());
    assertEquals("db-user", resolved.apiUser());
    assertEquals("db-pass", resolved.apiPass());
    assertFalse(resolved.missingRequired());
  }

  @Test
  void returnsMissingWhenRequiredValuesDoNotExist() {
    when(repo.findById(any())).thenReturn(Optional.empty());
    var resolved = service.resolve();
    assertTrue(resolved.missingRequired());
  }

  @Test
  void passwordIsStoredEncryptedAndResolvedDecrypted() {
    when(repo.findById(any())).thenReturn(Optional.empty());

    var saved = new ArrayList<AppConfigEntity>();
    when(repo.save(any())).thenAnswer(invocation -> {
      AppConfigEntity entity = invocation.getArgument(0);
      saved.add(entity);
      return entity;
    });

    service.saveConfig(new VicidialConfigService.VicidialConfigUpdateRequest("http://base", "user", "plain-pass", "react_crm"));

    String encrypted = saved.stream()
        .filter(e -> "VICIDIAL_API_PASS".equals(e.key))
        .map(e -> e.value)
        .findFirst()
        .orElseThrow();

    assertNotEquals("plain-pass", encrypted);

    when(repo.findById("VICIDIAL_BASE_URL")).thenReturn(Optional.of(entity("VICIDIAL_BASE_URL", "http://base")));
    when(repo.findById("VICIDIAL_API_USER")).thenReturn(Optional.of(entity("VICIDIAL_API_USER", "user")));
    when(repo.findById("VICIDIAL_API_PASS")).thenReturn(Optional.of(entity("VICIDIAL_API_PASS", encrypted)));
    when(repo.findById("VICIDIAL_SOURCE")).thenReturn(Optional.of(entity("VICIDIAL_SOURCE", "react_crm")));

    var resolved = service.resolve();
    assertEquals("plain-pass", resolved.apiPass());
  }

  private String captureEncryptedPass(VicidialConfigService localService, String plainPass) {
    when(repo.findById(any())).thenReturn(Optional.empty());

    var saved = new ArrayList<AppConfigEntity>();
    when(repo.save(any())).thenAnswer(invocation -> {
      AppConfigEntity entity = invocation.getArgument(0);
      saved.add(entity);
      return entity;
    });

    localService.saveConfig(new VicidialConfigService.VicidialConfigUpdateRequest("http://tmp", "tmp", plainPass, null));
    reset(repo);
    return saved.stream().filter(e -> "VICIDIAL_API_PASS".equals(e.key)).map(e -> e.value).findFirst().orElseThrow();
  }

  private AppConfigEntity entity(String key, String value) {
    AppConfigEntity e = new AppConfigEntity();
    e.key = key;
    e.value = value;
    return e;
  }
}
