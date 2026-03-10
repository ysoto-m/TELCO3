package com.telco3.agentui.legacy;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Legacy entity (english naming). Keep for compatibility.
 */
@Deprecated(forRemoval = false, since = "1.3.0")
@Entity
@Table(name = "customer_phones")
public class CustomerPhoneEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  public Long customerId;
  public String phoneNumber;
  public boolean isPrimary;
  public OffsetDateTime createdAt = OffsetDateTime.now();
}
