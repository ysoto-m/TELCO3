package com.telco3.agentui.manual2;

import com.telco3.agentui.agent.VicidialCredentialService;
import com.telco3.agentui.campaign.core.CampaignInteractionCoreService;
import com.telco3.agentui.domain.ContactoEntity;
import com.telco3.agentui.domain.GestionLlamadaEntity;
import com.telco3.agentui.domain.GestionLlamadaRepository;
import com.telco3.agentui.domain.InteraccionEntity;
import com.telco3.agentui.domain.InteraccionRepository;
import com.telco3.agentui.manual2.domain.FormularioManual2Entity;
import com.telco3.agentui.manual2.domain.FormularioManual2Repository;
import com.telco3.agentui.vicidial.VicidialClient;
import com.telco3.agentui.vicidial.VicidialRealtimeQueryService;
import com.telco3.agentui.vicidial.VicidialService;
import com.telco3.agentui.vicidial.VicidialServiceException;
import com.telco3.agentui.vicidial.domain.AgentVicidialCredentialRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class Manual2Service {
  private final FormularioManual2Repository formularioManual2Repository;
  private final GestionLlamadaRepository gestionLlamadaRepository;
  private final InteraccionRepository interaccionRepository;
  private final VicidialRealtimeQueryService realtimeQueryService;
  private final AgentVicidialCredentialRepository agentVicidialCredentialRepository;
  private final VicidialCredentialService credentialService;
  private final VicidialService vicidialService;
  private final VicidialClient vicidialClient;
  private final CampaignInteractionCoreService coreService;
  private final Manual2SubtipificacionService subtipificacionService;
  private final Manual2ContactoService contactoService;
  private final Manual2ReportService reportService;

  public Manual2Service(
      FormularioManual2Repository formularioManual2Repository,
      GestionLlamadaRepository gestionLlamadaRepository,
      InteraccionRepository interaccionRepository,
      VicidialRealtimeQueryService realtimeQueryService,
      AgentVicidialCredentialRepository agentVicidialCredentialRepository,
      VicidialCredentialService credentialService,
      VicidialService vicidialService,
      VicidialClient vicidialClient,
      CampaignInteractionCoreService coreService,
      Manual2SubtipificacionService subtipificacionService,
      Manual2ContactoService contactoService,
      Manual2ReportService reportService
  ) {
    this.formularioManual2Repository = formularioManual2Repository;
    this.gestionLlamadaRepository = gestionLlamadaRepository;
    this.interaccionRepository = interaccionRepository;
    this.realtimeQueryService = realtimeQueryService;
    this.agentVicidialCredentialRepository = agentVicidialCredentialRepository;
    this.credentialService = credentialService;
    this.vicidialService = vicidialService;
    this.vicidialClient = vicidialClient;
    this.coreService = coreService;
    this.subtipificacionService = subtipificacionService;
    this.contactoService = contactoService;
    this.reportService = reportService;
  }

  public List<Map<String, Object>> listDispositions(String campaignId) {
    String campaign = coreService.firstNonBlank(campaignId, "Manual2");
    return realtimeQueryService.fetchActiveDispositions(campaign).stream()
        .map(row -> Map.<String, Object>of(
            "status", row.status(),
            "label", coreService.firstNonBlank(row.statusName(), row.status()),
            "source", row.sourceTable()
        ))
        .toList();
  }

  public List<Map<String, Object>> listSubtipificaciones(String campaignId, String tipificacion) {
    return subtipificacionService.listSubtipificaciones(campaignId, tipificacion);
  }

  public List<Map<String, Object>> listAdminSubtipificaciones(String campaignId) {
    return subtipificacionService.listAdminSubtipificaciones(campaignId);
  }

  @Transactional
  public Map<String, Object> createOrUpdateSubtipificacion(String adminUser, UpsertSubtipificacionRequest request) {
    return subtipificacionService.createOrUpdateSubtipificacion(adminUser, request);
  }

  @Transactional
  public Map<String, Object> setSubtipificacionActivo(String adminUser, String campaignId, String codigo, boolean activo) {
    return subtipificacionService.setSubtipificacionActivo(adminUser, campaignId, codigo, activo);
  }

  public Map<String, Object> lookupContacto(String phoneNumber) {
    return contactoService.lookupContacto(phoneNumber);
  }

  @Transactional
  public Map<String, Object> registerInteractionStart(String agentUser, RegisterInteractionRequest request) {
    String telefono = coreService.normalizePhone(request.phoneNumber());
    if (!StringUtils.hasText(telefono)) {
      return Map.of("ok", false, "reason", "PHONE_REQUIRED");
    }

    ContactoEntity contacto = coreService.upsertContacto(telefono, null, null, null, "MANUAL2");
    InteraccionEntity interaccion = coreService.findActiveInteraction(agentUser, telefono, request.callId()).orElseGet(InteraccionEntity::new);

    if (interaccion.id == null) {
      interaccion.fechaInicio = coreService.nowLima();
      interaccion.estado = "ACTIVA";
    }
    interaccion.clienteId = contacto.id;
    interaccion.telefono = telefono;
    interaccion.campana = coreService.firstNonBlank(request.campaignId(), "Manual2");
    interaccion.modoLlamada = coreService.firstNonBlank(request.modoLlamada(), "manual");
    interaccion.agente = agentUser;
    interaccion.callId = coreService.firstNonBlank(request.callId(), interaccion.callId);
    interaccion.uniqueId = coreService.firstNonBlank(request.uniqueId(), interaccion.uniqueId);
    interaccion.leadId = coreService.firstNonNull(request.leadId(), interaccion.leadId);
    interaccion.nombreAudio = coreService.firstNonBlank(coreService.sanitizeRecordingName(request.nombreAudio()), interaccion.nombreAudio);
    interaccionRepository.save(interaccion);

    return Map.of(
        "ok", true,
        "interactionId", interaccion.id,
        "telefono", telefono,
        "callId", Objects.toString(interaccion.callId, "")
    );
  }

  @Transactional
  public Map<String, Object> saveFormulario(String agentUser, SaveFormularioRequest request) {
    String telefono = coreService.normalizePhone(request.phoneNumber());
    String campana = coreService.firstNonBlank(request.campaignId(), "Manual2");
    if (!StringUtils.hasText(telefono)) {
      throw new VicidialServiceException(HttpStatus.BAD_REQUEST, "MANUAL2_PHONE_REQUIRED", "El telefono es obligatorio.", "Ingrese un telefono valido.", null);
    }

    ContactoEntity contacto = coreService.upsertContacto(telefono, request.nombres(), request.apellidos(), request.documento(), request.origen());
    FormularioManual2Entity formulario = new FormularioManual2Entity();
    formulario.contactoId = contacto.id;
    formulario.telefono = telefono;
    formulario.comentario = request.comentario();
    formulario.campana = campana;
    formulario.creadoPor = agentUser;
    formulario.fechaRegistro = coreService.nowLima();
    formularioManual2Repository.save(formulario);

    return Map.of(
        "ok", true,
        "formularioId", formulario.id,
        "contactoId", contacto.id,
        "telefono", telefono,
        "fechaRegistro", coreService.formatLocal(formulario.fechaRegistro)
    );
  }

  @Transactional
  public Map<String, Object> saveGestionFinal(String agentUser, SaveGestionRequest request) {
    String telefono = coreService.normalizePhone(request.phoneNumber());
    String campana = coreService.firstNonBlank(request.campaignId(), "Manual2");
    String disposicion = coreService.firstNonBlank(request.disposicion());
    String tipificacion = coreService.firstNonBlank(request.tipificacion(), disposicion);
    String comentarioGestion = coreService.firstNonBlank(request.comentario(), request.observaciones());

    if (!StringUtils.hasText(telefono)) {
      throw new VicidialServiceException(HttpStatus.BAD_REQUEST, "MANUAL2_PHONE_REQUIRED", "El telefono es obligatorio.", "Ingrese un telefono valido.", null);
    }
    if (!StringUtils.hasText(disposicion)) {
      throw new VicidialServiceException(HttpStatus.BAD_REQUEST, "MANUAL2_DISPOSITION_REQUIRED", "La disposicion es obligatoria.", "Seleccione una disposicion valida.", null);
    }

    coreService.assertCallFinished(agentUser, campana, request.leadId(), request.callId(), telefono);

    ContactoEntity contacto = coreService.upsertContacto(telefono, request.nombres(), request.apellidos(), request.documento(), request.origen());

    FormularioManual2Entity formulario = new FormularioManual2Entity();
    formulario.contactoId = contacto.id;
    formulario.telefono = telefono;
    formulario.comentario = comentarioGestion;
    formulario.campana = campana;
    formulario.creadoPor = agentUser;
    formulario.fechaRegistro = coreService.nowLima();
    formularioManual2Repository.save(formulario);

    String recordingName = coreService.sanitizeRecordingName(request.nombreAudio());

    InteraccionEntity interaccion = coreService.resolveInteractionForFinalSave(
        agentUser,
        contacto.id,
        telefono,
        campana,
        request.modoLlamada(),
        request.leadId(),
        request.callId(),
        request.uniqueId(),
        recordingName,
        request.duracion()
    );

    String vicidialSyncStatus = "SKIPPED";
    String vicidialSyncError = null;
    if (request.leadId() != null) {
      var sessionOpt = agentVicidialCredentialRepository.findByAppUsername(agentUser).filter(s -> s.connected);
      String agentPass = credentialService.resolveAgentPass(agentUser).orElse(null);
      try {
        if (sessionOpt.isPresent() && StringUtils.hasText(agentPass)) {
          var sync = vicidialService.syncPostCallDisposition(
              agentUser,
              agentPass,
              sessionOpt.get(),
              campana,
              request.leadId(),
              request.callId(),
              request.uniqueId(),
              telefono,
              disposicion
          );
          if (sync.synced()) {
            vicidialSyncStatus = "SYNCED";
            recordingName = coreService.firstNonBlank(recordingName, coreService.sanitizeRecordingName(sync.recordingFilename()));
          } else {
            vicidialSyncStatus = "FAILED";
            vicidialSyncError = coreService.firstNonBlank(sync.error(), "Post-call sync failed");
          }
        } else {
          vicidialClient.externalStatus(agentUser, disposicion, request.leadId(), campana);
          vicidialSyncStatus = "SYNCED";
        }
      } catch (Exception ex) {
        vicidialSyncStatus = "FAILED";
        vicidialSyncError = coreService.firstNonBlank(ex.getMessage(), ex.getClass().getSimpleName());
      }
    }

    if (StringUtils.hasText(recordingName)) {
      interaccion.nombreAudio = recordingName;
      interaccionRepository.save(interaccion);
    }

    GestionLlamadaEntity gestion = new GestionLlamadaEntity();
    gestion.formularioManual2Id = formulario.id;
    gestion.contactoId = contacto.id;
    gestion.interaccionId = interaccion.id;
    gestion.agente = agentUser;
    gestion.fechaGestion = coreService.nowLima();
    gestion.tipificacion = tipificacion;
    gestion.disposicion = disposicion;
    gestion.subtipificacion = request.subtipificacion();
    gestion.observaciones = comentarioGestion;
    gestion.modoLlamada = request.modoLlamada();
    gestion.leadId = request.leadId();
    gestion.callId = request.callId();
    gestion.uniqueId = request.uniqueId();
    gestion.nombreAudio = recordingName;
    gestion.duracion = interaccion.duracion;
    gestion.campana = campana;
    gestion.telefono = telefono;
    gestion.vicidialSyncStatus = vicidialSyncStatus;
    gestion.vicidialSyncError = vicidialSyncError;
    gestionLlamadaRepository.save(gestion);

    return Map.of(
        "ok", true,
        "formularioId", formulario.id,
        "gestionId", gestion.id,
        "interaccionId", interaccion.id,
        "contactoId", contacto.id,
        "fechaGestion", coreService.formatLocal(gestion.fechaGestion),
        "vicidialSyncStatus", vicidialSyncStatus,
        "vicidialSyncError", Objects.toString(vicidialSyncError, ""),
        "nombreAudio", Objects.toString(gestion.nombreAudio, "")
    );
  }

  public Map<String, Object> historyByTelefono(String phoneNumber) {
    return contactoService.historyByTelefono(phoneNumber);
  }

  public ReportResult report(ReportFilter filter) {
    return reportService.report(filter);
  }

  public String exportCsv(ReportFilter filter) {
    return reportService.exportCsv(filter);
  }

  public record SaveFormularioRequest(
      String campaignId,
      String phoneNumber,
      String nombres,
      String apellidos,
      String documento,
      String origen,
      String comentario
  ) {
  }

  public record SaveGestionRequest(
      String campaignId,
      String phoneNumber,
      String nombres,
      String apellidos,
      String documento,
      String origen,
      String comentario,
      String tipificacion,
      String disposicion,
      String subtipificacion,
      String observaciones,
      String modoLlamada,
      Long leadId,
      String callId,
      String uniqueId,
      String nombreAudio,
      Integer duracion
  ) {
  }

  public record RegisterInteractionRequest(
      String campaignId,
      String modoLlamada,
      String phoneNumber,
      Long leadId,
      String callId,
      String uniqueId,
      String nombreAudio
  ) {
  }

  public record UpsertSubtipificacionRequest(
      String campana,
      String codigo,
      String nombre,
      String tipificacion,
      Boolean activo
  ) {
  }

  public record ReportFilter(
      LocalDate from,
      LocalDate to,
      String campana,
      String agente,
      String tipificacion,
      String disposicion,
      String subtipificacion,
      String telefono,
      String cliente,
      int page,
      int size
  ) {
  }

  public record ReportResult(
      List<Map<String, Object>> items,
      long total,
      int page,
      int size,
      String generatedAt
  ) {
  }
}
