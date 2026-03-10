package com.telco3.agentui.manual2.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "formulario_manual2")
public class FormularioManual2Entity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "contacto_id")
  public Long contactoId;

  @Column(nullable = false)
  public String telefono;

  @Column(columnDefinition = "text")
  public String comentario;

  @Column(name = "campana", nullable = false)
  public String campana;

  @Column(name = "creado_por", nullable = false)
  public String creadoPor;

  @Column(name = "fecha_registro", nullable = false)
  public LocalDateTime fechaRegistro = LocalDateTime.now();
}
