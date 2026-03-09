package com.telco3.agentui.campaign.core;

import com.telco3.agentui.agent.VicidialCredentialService;
import com.telco3.agentui.domain.AgentVicidialCredentialRepository;
import com.telco3.agentui.domain.ContactoEntity;
import com.telco3.agentui.domain.ContactoRepository;
import com.telco3.agentui.domain.InteraccionEntity;
import com.telco3.agentui.domain.InteraccionRepository;
import com.telco3.agentui.vicidial.VicidialService;
import com.telco3.agentui.vicidial.VicidialServiceException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Core reusable de interacciones/gestiones para campanas.
 * Manual2 lo usa hoy y futuras campanas pueden reutilizarlo.
 */
@Service
public class CampaignInteractionCoreService {
  private static final ZoneId LIMA_ZONE = ZoneId.of("America/Lima");
  private static final DateTimeFormatter LOCAL_DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private final ContactoRepository contactoRepository;
  private final InteraccionRepository interaccionRepository;
  private final AgentVicidialCredentialRepository agentVicidialCredentialRepository;
  private final VicidialCredentialService credentialService;
  private final VicidialService vicidialService;

  public CampaignInteractionCoreService(
      ContactoRepository contactoRepository,
      InteraccionRepository interaccionRepository,
      AgentVicidialCredentialRepository agentVicidialCredentialRepository,
      VicidialCredentialService credentialService,
      VicidialService vicidialService
  ) {
    this.contactoRepository = contactoRepository;
    this.interaccionRepository = interaccionRepository;
    this.agentVicidialCredentialRepository = agentVicidialCredentialRepository;
    this.credentialService = credentialService;
    this.vicidialService = vicidialService;
  }

  public String normalizePhone(String phoneNumber) {
    String value = Objects.toString(phoneNumber, "").replaceAll("[^0-9]+", "");
    return StringUtils.hasText(value) ? value : "";
  }

  public String sanitizeRecordingName(String value) {
    String normalized = firstNonBlank(value);
    if (!StringUtils.hasText(normalized)) {
      return null;
    }
    if ("0".equals(normalized.trim())) {
      return null;
    }
    return normalized.trim();
  }

  public String firstNonBlank(String... values) {
    for (String value : values) {
      if (StringUtils.hasText(value)) {
        return value.trim();
      }
    }
    return null;
  }

  public Long firstNonNull(Long... values) {
    for (Long value : values) {
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  public LocalDateTime nowLima() {
    return LocalDateTime.now(LIMA_ZONE);
  }

  public String formatTimestamp(Timestamp timestamp) {
    if (timestamp == null) {
      return null;
    }
    return formatLocal(timestamp.toLocalDateTime());
  }

  public String formatLocal(LocalDateTime value) {
    if (value == null) {
      return null;
    }
    return value.format(LOCAL_DT_FMT);
  }

  @Transactional
  public ContactoEntity upsertContacto(String telefono, String nombres, String apellidos, String documento, String origen) {
    ContactoEntity contacto = contactoRepository.findByTelefono(telefono).orElseGet(ContactoEntity::new);
    LocalDateTime now = nowLima();

    contacto.telefono = telefono;
    contacto.nombres = firstNonBlank(nombres, contacto.nombres);
    contacto.apellidos = firstNonBlank(apellidos, contacto.apellidos);
    contacto.documento = firstNonBlank(documento, contacto.documento);
    contacto.origen = firstNonBlank(origen, contacto.origen, "MANUAL2");
    contacto.fechaActualizacion = now;
    if (contacto.fechaCreacion == null) {
      contacto.fechaCreacion = now;
    }
    return contactoRepository.save(contacto);
  }

  public Optional<InteraccionEntity> findActiveInteraction(String agentUser, String telefono, String callId) {
    if (StringUtils.hasText(callId)) {
      var byCallId = interaccionRepository.findFirstByEstadoAndCallIdOrderByFechaInicioDesc("ACTIVA", callId.trim());
      if (byCallId.isPresent()) {
        return byCallId;
      }
    }
    if (StringUtils.hasText(agentUser) && StringUtils.hasText(telefono)) {
      return interaccionRepository.findFirstByEstadoAndAgenteAndTelefonoOrderByFechaInicioDesc("ACTIVA", agentUser, telefono);
    }
    return Optional.empty();
  }

  @Transactional
  public InteraccionEntity resolveInteractionForFinalSave(
      String agentUser,
      Long contactoId,
      String telefono,
      String campana,
      String modoLlamada,
      Long leadId,
      String callId,
      String uniqueId,
      String nombreAudio,
      Integer duracion
  ) {
    InteraccionEntity interaccion = findActiveInteraction(agentUser, telefono, callId).orElseGet(InteraccionEntity::new);
    LocalDateTime now = nowLima();

    if (interaccion.id == null) {
      interaccion.fechaInicio = now;
      interaccion.estado = "ACTIVA";
    }

    interaccion.clienteId = contactoId;
    interaccion.telefono = telefono;
    interaccion.campana = campana;
    interaccion.modoLlamada = firstNonBlank(modoLlamada, "manual");
    interaccion.agente = agentUser;
    interaccion.callId = firstNonBlank(callId, interaccion.callId);
    interaccion.uniqueId = firstNonBlank(uniqueId, interaccion.uniqueId);
    interaccion.leadId = firstNonNull(leadId, interaccion.leadId);
    interaccion.nombreAudio = firstNonBlank(nombreAudio, interaccion.nombreAudio);

    if (interaccion.fechaInicio == null) {
      interaccion.fechaInicio = now;
    }
    interaccion.fechaFin = now;
    interaccion.estado = "FINALIZADA";

    if (duracion != null && duracion >= 0) {
      interaccion.duracion = duracion;
    } else if (interaccion.fechaInicio != null && interaccion.fechaFin != null) {
      interaccion.duracion = (int) Math.max(0, Duration.between(interaccion.fechaInicio, interaccion.fechaFin).toSeconds());
    }

    return interaccionRepository.save(interaccion);
  }

  public void assertCallFinished(String agentUser, String campaignId, Long leadId, String callId, String phoneNumber) {
    var sessionOpt = agentVicidialCredentialRepository.findByAppUsername(agentUser).filter(s -> s.connected);
    if (sessionOpt.isEmpty()) {
      return;
    }
    var session = sessionOpt.get();
    String agentPass = credentialService.resolveAgentPass(agentUser).orElse(null);
    if (!StringUtils.hasText(agentPass)) {
      return;
    }

    var snapshot = vicidialService.resolveRealtimeCallSnapshot(
        agentUser,
        agentPass,
        session,
        firstNonBlank(callId, session.currentCallId),
        firstNonNull(leadId, session.currentLeadId),
        phoneNumber,
        firstNonBlank(campaignId, session.connectedCampaign),
        true
    );

    boolean dialingRuntime = "DIALING".equalsIgnoreCase(Objects.toString(session.currentDialStatus, ""));
    boolean inCallStatus = "INCALL".equalsIgnoreCase(Objects.toString(snapshot.agentStatus(), ""));
    boolean hasMediaEvidence = firstNonBlank(snapshot.uniqueId(), snapshot.channel()) != null;
    boolean hasCallEvidence = firstNonBlank(snapshot.callId()) != null || snapshot.leadId() != null;
    boolean callActive = dialingRuntime || (inCallStatus && (hasMediaEvidence || hasCallEvidence));
    if (!callActive) {
      return;
    }

    Map<String, Object> details = new LinkedHashMap<>();
    details.put("agentStatus", snapshot.agentStatus());
    details.put("classification", snapshot.classification());
    details.put("callId", snapshot.callId());
    details.put("leadId", snapshot.leadId());
    details.put("uniqueId", snapshot.uniqueId());
    details.put("channel", snapshot.channel());

    throw new VicidialServiceException(
        HttpStatus.CONFLICT,
        "VICIDIAL_CALL_STILL_ACTIVE",
        "No se puede guardar la gestion final mientras la llamada sigue activa.",
        "Espere a que termine la llamada y vuelva a guardar.",
        details
    );
  }

  public String csv(Object value) {
    String text = Objects.toString(value, "");
    return '"' + text.replace("\"", "\"\"") + '"';
  }

  public String normalizeForLike(String value) {
    return Objects.toString(value, "").trim().toLowerCase(Locale.ROOT);
  }
}
