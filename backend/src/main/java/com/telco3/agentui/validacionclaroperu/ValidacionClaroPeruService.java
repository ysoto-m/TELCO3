package com.telco3.agentui.validacionclaroperu;

import com.telco3.agentui.agent.VicidialCredentialService;
import com.telco3.agentui.campaign.core.CampaignInteractionCoreService;
import com.telco3.agentui.domain.ContactoEntity;
import com.telco3.agentui.domain.GestionLlamadaEntity;
import com.telco3.agentui.domain.GestionLlamadaRepository;
import com.telco3.agentui.domain.InteraccionEntity;
import com.telco3.agentui.domain.InteraccionRepository;
import com.telco3.agentui.manual2.Manual2SubtipificacionService;
import com.telco3.agentui.validacionclaroperu.domain.FormularioValidacionClaroPeruEntity;
import com.telco3.agentui.validacionclaroperu.domain.FormularioValidacionClaroPeruRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class ValidacionClaroPeruService {
  public static final String DEFAULT_CAMPAIGN = "ValidacionClaroPeru";
  private static final String ORIGEN_CONTACTO = "VALIDACION_CLARO_PERU";

  private final FormularioValidacionClaroPeruRepository formularioRepository;
  private final GestionLlamadaRepository gestionLlamadaRepository;
  private final InteraccionRepository interaccionRepository;
  private final VicidialRealtimeQueryService realtimeQueryService;
  private final AgentVicidialCredentialRepository agentVicidialCredentialRepository;
  private final VicidialCredentialService credentialService;
  private final VicidialService vicidialService;
  private final VicidialClient vicidialClient;
  private final CampaignInteractionCoreService coreService;
  private final Manual2SubtipificacionService subtipificacionService;
  private final ValidacionClaroPeruReportService reportService;

  public ValidacionClaroPeruService(
      FormularioValidacionClaroPeruRepository formularioRepository,
      GestionLlamadaRepository gestionLlamadaRepository,
      InteraccionRepository interaccionRepository,
      VicidialRealtimeQueryService realtimeQueryService,
      AgentVicidialCredentialRepository agentVicidialCredentialRepository,
      VicidialCredentialService credentialService,
      VicidialService vicidialService,
      VicidialClient vicidialClient,
      CampaignInteractionCoreService coreService,
      Manual2SubtipificacionService subtipificacionService,
      ValidacionClaroPeruReportService reportService
  ) {
    this.formularioRepository = formularioRepository;
    this.gestionLlamadaRepository = gestionLlamadaRepository;
    this.interaccionRepository = interaccionRepository;
    this.realtimeQueryService = realtimeQueryService;
    this.agentVicidialCredentialRepository = agentVicidialCredentialRepository;
    this.credentialService = credentialService;
    this.vicidialService = vicidialService;
    this.vicidialClient = vicidialClient;
    this.coreService = coreService;
    this.subtipificacionService = subtipificacionService;
    this.reportService = reportService;
  }

  public List<Map<String, Object>> listDispositions(String campaignId) {
    String campaign = resolveCampaign(campaignId);
    return realtimeQueryService.fetchActiveDispositions(campaign).stream()
        .map(row -> Map.<String, Object>of(
            "status", row.status(),
            "label", coreService.firstNonBlank(row.statusName(), row.status()),
            "source", row.sourceTable()
        ))
        .toList();
  }

  public List<Map<String, Object>> listSubtipificaciones(String campaignId, String tipificacion) {
    return subtipificacionService.listSubtipificaciones(resolveCampaign(campaignId), tipificacion);
  }

  public Map<String, Object> lookupByDocumento(String documento) {
    String normalizedDocument = coreService.firstNonBlank(documento);
    if (!StringUtils.hasText(normalizedDocument)) {
      return Map.of("found", false, "documento", "");
    }
    var latest = formularioRepository.findFirstByDocumentoOrderByFechaRegistroDesc(normalizedDocument);
    if (latest.isEmpty()) {
      return Map.of("found", false, "documento", normalizedDocument);
    }
    FormularioValidacionClaroPeruEntity item = latest.get();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("found", true);
    payload.put("documento", normalizedDocument);
    payload.put("formulario", Map.of(
        "id", item.id,
        "clienteId", item.clienteId,
        "nombres", Objects.toString(item.nombres, ""),
        "apellidos", Objects.toString(item.apellidos, ""),
        "documento", Objects.toString(item.documento, ""),
        "comentario", Objects.toString(item.comentario, ""),
        "encuesta", Objects.toString(item.encuesta, ""),
        "campana", Objects.toString(item.campana, DEFAULT_CAMPAIGN),
        "creadoPor", Objects.toString(item.creadoPor, ""),
        "fechaRegistro", coreService.formatLocal(item.fechaRegistro)
    ));
    return payload;
  }

  @Transactional
  public Map<String, Object> saveGestionFinal(String agentUser, SaveGestionRequest request) {
    String campaign = resolveCampaign(request.campaignId());
    String telefono = coreService.normalizePhone(request.phoneNumber());
    String disposicion = coreService.firstNonBlank(request.disposicion());
    String tipificacion = coreService.firstNonBlank(request.tipificacion(), disposicion);
    String documento = coreService.firstNonBlank(request.documento());
    String comentarioGestion = coreService.firstNonBlank(request.comentario(), request.observaciones());
    String encuesta = normalizeEncuesta(request.encuesta());

    if (!StringUtils.hasText(telefono)) {
      throw new VicidialServiceException(
          HttpStatus.BAD_REQUEST,
          "VALIDACION_CLARO_PERU_PHONE_REQUIRED",
          "El telefono es obligatorio para guardar la gestion final.",
          "Confirme el numero marcado antes de guardar.",
          null
      );
    }
    if (!StringUtils.hasText(disposicion)) {
      throw new VicidialServiceException(
          HttpStatus.BAD_REQUEST,
          "VALIDACION_CLARO_PERU_DISPOSITION_REQUIRED",
          "La tipificacion/disposicion es obligatoria.",
          "Seleccione una disposicion valida.",
          null
      );
    }
    if (!StringUtils.hasText(documento)) {
      throw new VicidialServiceException(
          HttpStatus.BAD_REQUEST,
          "VALIDACION_CLARO_PERU_DOCUMENT_REQUIRED",
          "El documento es obligatorio para este formulario.",
          "Ingrese documento antes de guardar la gestion.",
          null
      );
    }
    if (!StringUtils.hasText(encuesta)) {
      throw new VicidialServiceException(
          HttpStatus.BAD_REQUEST,
          "VALIDACION_CLARO_PERU_ENCUESTA_REQUIRED",
          "La encuesta es obligatoria.",
          "Seleccione SI o NO.",
          null
      );
    }

    coreService.assertCallFinished(agentUser, campaign, request.leadId(), request.callId(), telefono);

    ContactoEntity contacto = coreService.upsertContacto(
        telefono,
        request.nombres(),
        request.apellidos(),
        documento,
        ORIGEN_CONTACTO
    );

    FormularioValidacionClaroPeruEntity formulario = new FormularioValidacionClaroPeruEntity();
    formulario.clienteId = contacto.id;
    formulario.nombres = request.nombres();
    formulario.apellidos = request.apellidos();
    formulario.documento = documento;
    formulario.comentario = comentarioGestion;
    formulario.encuesta = encuesta;
    formulario.campana = campaign;
    formulario.creadoPor = agentUser;
    formulario.fechaRegistro = coreService.nowLima();
    formularioRepository.save(formulario);

    String recordingName = coreService.sanitizeRecordingName(request.nombreAudio());
    InteraccionEntity interaccion = coreService.resolveInteractionForFinalSave(
        agentUser,
        contacto.id,
        telefono,
        campaign,
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
              campaign,
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
          vicidialClient.externalStatus(agentUser, disposicion, request.leadId(), campaign);
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
    gestion.formularioValidacionClaroPeruId = formulario.id;
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
    gestion.campana = campaign;
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

  public ReportResult report(ReportFilter filter) {
    return reportService.report(filter);
  }

  public String exportCsv(ReportFilter filter) {
    return reportService.exportCsv(filter);
  }

  private String resolveCampaign(String campaignId) {
    return coreService.firstNonBlank(campaignId, DEFAULT_CAMPAIGN);
  }

  private String normalizeEncuesta(String encuesta) {
    String normalized = coreService.firstNonBlank(encuesta);
    if (!StringUtils.hasText(normalized)) {
      return null;
    }
    String upper = normalized.toUpperCase();
    if ("SI".equals(upper) || "NO".equals(upper)) {
      return upper;
    }
    return null;
  }

  public record SaveGestionRequest(
      String campaignId,
      String phoneNumber,
      String nombres,
      String apellidos,
      String documento,
      String comentario,
      String encuesta,
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

  public record ReportFilter(
      LocalDate from,
      LocalDate to,
      String campana,
      String agente,
      String tipificacion,
      String disposicion,
      String subtipificacion,
      String telefono,
      String documento,
      String encuesta,
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
