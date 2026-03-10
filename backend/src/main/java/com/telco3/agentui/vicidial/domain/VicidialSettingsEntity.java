package com.telco3.agentui.vicidial.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Compatibility entity for historical Vicidial settings storage.
 */
@Deprecated(forRemoval = false, since = "1.3.0")
@Entity
@Table(name = "vicidial_settings")
public class VicidialSettingsEntity {
  @Id
  public Long id = 1L;
  public String baseUrl;
  public String apiUser;
  public String apiPassEncrypted;
  public String source;
  public OffsetDateTime updatedAt = OffsetDateTime.now();
}
