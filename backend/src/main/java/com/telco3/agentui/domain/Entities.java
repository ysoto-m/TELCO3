package com.telco3.agentui.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

public class Entities {
  public enum SyncStatus { PENDING, SYNCED, FAILED }

  @Entity @Table(name="users")
  public static class UserEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(nullable=false, unique=true) public String username;
    @Column(nullable=false) public String passwordHash;
    @Column(columnDefinition = "text") public String agentPassEncrypted;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public Role role;
    public boolean active = true;
    public OffsetDateTime createdAt = OffsetDateTime.now();
  }

  @Entity @Table(name="vicidial_settings")
  public static class VicidialSettingsEntity {
    @Id public Long id = 1L;
    public String baseUrl;
    public String apiUser;
    public String apiPassEncrypted;
    public String source;
    public OffsetDateTime updatedAt = OffsetDateTime.now();
  }

  /**
   * Legacy entity (english naming). Keep for compatibility.
   * Prefer {@link com.telco3.agentui.domain.ContactoEntity} in new features.
   */
  @Deprecated(forRemoval = false, since = "1.3.0")
  @Entity @Table(name="customers")
  public static class CustomerEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(unique=true, nullable=false) public String dni;
    public String firstName;
    public String lastName;
    public OffsetDateTime createdAt = OffsetDateTime.now();
  }

  /**
   * Legacy entity (english naming). Keep for compatibility.
   */
  @Deprecated(forRemoval = false, since = "1.3.0")
  @Entity @Table(name="customer_phones")
  public static class CustomerPhoneEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    public Long customerId;
    public String phoneNumber;
    public boolean isPrimary;
    public OffsetDateTime createdAt = OffsetDateTime.now();
  }

  @Entity @Table(name="agent_vicidial_credentials")
  public static class AgentVicidialCredentialEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(nullable = false) public String appUsername;
    @Column(nullable = false) public String agentUser;
    @Column(columnDefinition = "text") public String agentPassEncrypted;
    public String lastPhoneLogin;
    public String lastCampaign;
    public boolean rememberCredentials = true;
    public boolean connected;
    public String connectedPhoneLogin;
    public String connectedCampaign;
    public String connectedMode;
    public String sessionName;
    public String serverIp;
    public String confExten;
    public String extension;
    public String protocol;
    public Long agentLogId;
    public String currentDialStatus;
    public String currentCallId;
    public Long currentLeadId;
    public String crmSessionId;
    public OffsetDateTime lastHeartbeatAt;
    public OffsetDateTime lastBrowserExitAt;
    public String sessionStatus;
    public Integer cleanupAttempts = 0;
    public String cleanupStatus;
    public OffsetDateTime logoutTime;
    public String lastKnownVicidialStatus;
    public OffsetDateTime connectedAt;
    public OffsetDateTime updatedAt = OffsetDateTime.now();
  }
}
