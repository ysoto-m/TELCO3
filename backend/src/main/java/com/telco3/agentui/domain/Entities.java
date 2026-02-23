package com.telco3.agentui.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

public class Entities {
  public enum Role { AGENT, REPORT_ADMIN }
  public enum SyncStatus { PENDING, SYNCED, FAILED }

  @Entity @Table(name="users")
  public static class UserEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(nullable=false, unique=true) public String username;
    @Column(nullable=false) public String passwordHash;
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

  @Entity @Table(name="customers")
  public static class CustomerEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(unique=true, nullable=false) public String dni;
    public String firstName;
    public String lastName;
    public OffsetDateTime createdAt = OffsetDateTime.now();
  }

  @Entity @Table(name="customer_phones")
  public static class CustomerPhoneEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    public Long customerId;
    public String phoneNumber;
    public boolean isPrimary;
    public OffsetDateTime createdAt = OffsetDateTime.now();
  }

  @Entity @Table(name="interactions")
  public static class InteractionEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    public Long customerId;
    public String dni;
    public String phoneNumber;
    public Long leadId;
    public String campaign;
    public String agentUser;
    public String mode;
    public String dispo;
    @Column(columnDefinition="text") public String notes;
    @Column(columnDefinition="text") public String extraJson;
    @Enumerated(EnumType.STRING) public SyncStatus syncStatus = SyncStatus.PENDING;
    @Column(columnDefinition="text") public String lastError;
    public OffsetDateTime createdAt = OffsetDateTime.now();
  }

  @Entity @Table(name="agent_vicidial_credentials")
  public static class AgentVicidialCredentialEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(nullable = false) public String appUsername;
    @Column(nullable = false) public String agentUser;
    @Column(nullable = false, columnDefinition = "text") public String agentPassEncrypted;
    @Column(nullable = false) public String phoneLogin;
    @Column(nullable = false, columnDefinition = "text") public String phonePassEncrypted;
    public String campaign;
    public OffsetDateTime updatedAt = OffsetDateTime.now();
  }
}
