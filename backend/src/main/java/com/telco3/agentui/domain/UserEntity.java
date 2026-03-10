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

@Entity
@Table(name = "users")
public class UserEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(nullable = false, unique = true)
  public String username;

  @Column(nullable = false)
  public String passwordHash;

  @Column(columnDefinition = "text")
  public String agentPassEncrypted;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  public Role role;

  public boolean active = true;
  public OffsetDateTime createdAt = OffsetDateTime.now();
}
