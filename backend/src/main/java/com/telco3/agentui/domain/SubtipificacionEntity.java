package com.telco3.agentui.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "subtipificaciones")
public class SubtipificacionEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "campana", nullable = false)
  public String campana;

  @Column(nullable = false)
  public String codigo;

  @Column(nullable = false)
  public String nombre;

  @Column(nullable = false)
  public String tipificacion;

  @Column(nullable = false)
  public boolean activo = true;

  @Column(name = "fecha_creacion", nullable = false)
  public LocalDateTime fechaCreacion = LocalDateTime.now();
}
