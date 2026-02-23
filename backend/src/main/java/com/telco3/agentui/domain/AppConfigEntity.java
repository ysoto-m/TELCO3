package com.telco3.agentui.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "app_config")
public class AppConfigEntity {
  @Id
  @Column(name = "key", nullable = false, unique = true, length = 120)
  public String key;

  @Column(name = "value", columnDefinition = "text")
  public String value;

  @Column(name = "updated_at", nullable = false)
  public OffsetDateTime updatedAt;

  @PrePersist
  @PreUpdate
  void touchUpdatedAt() {
    updatedAt = OffsetDateTime.now();
  }
}
