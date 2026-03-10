package com.telco3.agentui.validacionclaroperu.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "formulario_validacion_claro_peru")
public class FormularioValidacionClaroPeruEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "cliente_id")
  public Long clienteId;

  public String nombres;
  public String apellidos;

  @Column(nullable = false)
  public String documento;

  @Column(columnDefinition = "text")
  public String comentario;

  @Column(nullable = false)
  public String encuesta;

  @Column(name = "creado_por", nullable = false)
  public String creadoPor;

  @Column(name = "campana", nullable = false)
  public String campana;

  @Column(name = "fecha_registro", nullable = false)
  public LocalDateTime fechaRegistro = LocalDateTime.now();
}
