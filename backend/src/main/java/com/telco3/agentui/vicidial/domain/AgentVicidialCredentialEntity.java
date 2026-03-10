package com.telco3.agentui.vicidial.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "agent_vicidial_credentials")
public class AgentVicidialCredentialEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(nullable = false)
  public String appUsername;

  @Column(nullable = false)
  public String agentUser;

  @Column(columnDefinition = "text")
  public String agentPassEncrypted;

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
