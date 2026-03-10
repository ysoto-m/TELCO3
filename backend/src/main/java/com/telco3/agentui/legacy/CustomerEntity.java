package com.telco3.agentui.legacy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Legacy entity (english naming). Keep for compatibility.
 * Prefer com.telco3.agentui.domain.ContactoEntity in new features.
 */
@Deprecated(forRemoval = false, since = "1.3.0")
@Entity
@Table(name = "customers")
public class CustomerEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(unique = true, nullable = false)
  public String dni;

  public String firstName;
  public String lastName;
  public OffsetDateTime createdAt = OffsetDateTime.now();
}
