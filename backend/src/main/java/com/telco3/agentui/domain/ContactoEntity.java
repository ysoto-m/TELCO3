package com.telco3.agentui.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "contactos")
public class ContactoEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(nullable = false, unique = true)
  public String telefono;

  public String nombres;
  public String apellidos;
  public String documento;
  public String origen;

  @Column(name = "fecha_creacion", nullable = false)
  public LocalDateTime fechaCreacion = LocalDateTime.now();

  @Column(name = "fecha_actualizacion", nullable = false)
  public LocalDateTime fechaActualizacion = LocalDateTime.now();

  @PrePersist
  void onCreate() {
    LocalDateTime now = LocalDateTime.now();
    if (fechaCreacion == null) {
      fechaCreacion = now;
    }
    fechaActualizacion = now;
  }

  @PreUpdate
  void onUpdate() {
    fechaActualizacion = LocalDateTime.now();
  }
}
