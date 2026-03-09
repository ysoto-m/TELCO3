package com.telco3.agentui.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "interacciones")
public class InteraccionEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "cliente_id")
  public Long clienteId;

  @Column(nullable = false)
  public String telefono;

  @Column(name = "campana")
  public String campana;

  @Column(name = "modo_llamada")
  public String modoLlamada;

  @Column(nullable = false)
  public String agente;

  @Column(name = "fecha_inicio", nullable = false)
  public LocalDateTime fechaInicio = LocalDateTime.now();

  @Column(name = "fecha_fin")
  public LocalDateTime fechaFin;

  public Integer duracion;

  @Column(name = "call_id")
  public String callId;

  @Column(name = "unique_id")
  public String uniqueId;

  @Column(name = "lead_id")
  public Long leadId;

  @Column(name = "nombre_audio")
  public String nombreAudio;

  @Column(nullable = false)
  public String estado = "ACTIVA";
}
