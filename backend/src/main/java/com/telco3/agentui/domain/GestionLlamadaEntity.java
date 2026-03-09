package com.telco3.agentui.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "gestiones_llamadas")
public class GestionLlamadaEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "formulario_manual2_id")
  public Long formularioManual2Id;

  @Column(name = "contacto_id")
  public Long contactoId;

  @Column(name = "interaccion_id")
  public Long interaccionId;

  @Column(nullable = false)
  public String agente;

  @Column(name = "fecha_gestion", nullable = false)
  public LocalDateTime fechaGestion = LocalDateTime.now();

  @Column(nullable = false)
  public String disposicion;

  public String tipificacion;

  public String subtipificacion;

  @Column(columnDefinition = "text")
  public String observaciones;

  @Column(name = "modo_llamada")
  public String modoLlamada;

  @Column(name = "lead_id")
  public Long leadId;

  @Column(name = "call_id")
  public String callId;

  @Column(name = "unique_id")
  public String uniqueId;

  @Column(name = "nombre_audio")
  public String nombreAudio;

  public Integer duracion;

  @Column(name = "campana")
  public String campana;

  public String telefono;

  @Column(name = "vicidial_sync_status", nullable = false)
  public String vicidialSyncStatus = "PENDING";

  @Column(name = "vicidial_sync_error", columnDefinition = "text")
  public String vicidialSyncError;
}
