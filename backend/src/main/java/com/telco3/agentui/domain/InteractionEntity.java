package com.telco3.agentui.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Legacy domain (english naming) kept for compatibility with existing agent interactions endpoints.
 * New campaign work should prefer {@link InteraccionEntity}.
 */
@Deprecated(forRemoval = false, since = "1.3.0")
@Entity(name = "InteractionEntity")
@Table(name = "interactions")
public class InteractionEntity {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
  public Long customerId;
  public String dni;
  public String phoneNumber;
  public Long leadId;
  public String campaign;
  public String agentUser;
  public String mode;
  public String dispo;
  @Column(columnDefinition = "text") public String notes;
  @Column(columnDefinition = "text") public String extraJson;
  @Enumerated(EnumType.STRING) public Entities.SyncStatus syncStatus = Entities.SyncStatus.PENDING;
  @Column(columnDefinition = "text") public String lastError;
  public OffsetDateTime createdAt = OffsetDateTime.now();
}
