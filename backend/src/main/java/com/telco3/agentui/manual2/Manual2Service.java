package com.telco3.agentui.manual2;

import com.telco3.agentui.agent.VicidialCredentialService;
import com.telco3.agentui.domain.AgentVicidialCredentialRepository;
import com.telco3.agentui.domain.ContactoEntity;
import com.telco3.agentui.domain.ContactoRepository;
import com.telco3.agentui.domain.FormularioManual2Entity;
import com.telco3.agentui.domain.FormularioManual2Repository;
import com.telco3.agentui.domain.GestionLlamadaEntity;
import com.telco3.agentui.domain.GestionLlamadaRepository;
import com.telco3.agentui.domain.InteraccionEntity;
import com.telco3.agentui.domain.InteraccionRepository;
import com.telco3.agentui.domain.SubtipificacionEntity;
import com.telco3.agentui.domain.SubtipificacionRepository;
import com.telco3.agentui.vicidial.VicidialClient;
import com.telco3.agentui.vicidial.VicidialRealtimeQueryService;
import com.telco3.agentui.vicidial.VicidialService;
import com.telco3.agentui.vicidial.VicidialServiceException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
public class Manual2Service {
  private static final ZoneId LIMA_ZONE = ZoneId.of("America/Lima");
  private static final DateTimeFormatter LOCAL_DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private final ContactoRepository contactoRepository;
  private final FormularioManual2Repository formularioManual2Repository;
  private final GestionLlamadaRepository gestionLlamadaRepository;
  private final InteraccionRepository interaccionRepository;
  private final SubtipificacionRepository subtipificacionRepository;
  private final VicidialRealtimeQueryService realtimeQueryService;
  private final AgentVicidialCredentialRepository agentVicidialCredentialRepository;
  private final VicidialCredentialService credentialService;
  private final VicidialService vicidialService;
  private final VicidialClient vicidialClient;
  private final JdbcTemplate jdbcTemplate;

  public Manual2Service(
      ContactoRepository contactoRepository,
      FormularioManual2Repository formularioManual2Repository,
      GestionLlamadaRepository gestionLlamadaRepository,
      InteraccionRepository interaccionRepository,
      SubtipificacionRepository subtipificacionRepository,
      VicidialRealtimeQueryService realtimeQueryService,
      AgentVicidialCredentialRepository agentVicidialCredentialRepository,
      VicidialCredentialService credentialService,
      VicidialService vicidialService,
      VicidialClient vicidialClient,
      JdbcTemplate jdbcTemplate
  ) {
    this.contactoRepository = contactoRepository;
    this.formularioManual2Repository = formularioManual2Repository;
    this.gestionLlamadaRepository = gestionLlamadaRepository;
    this.interaccionRepository = interaccionRepository;
    this.subtipificacionRepository = subtipificacionRepository;
    this.realtimeQueryService = realtimeQueryService;
    this.agentVicidialCredentialRepository = agentVicidialCredentialRepository;
    this.credentialService = credentialService;
    this.vicidialService = vicidialService;
    this.vicidialClient = vicidialClient;
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<Map<String, Object>> listDispositions(String campaignId) {
    String campaign = firstNonBlank(campaignId, "Manual2");
    return realtimeQueryService.fetchActiveDispositions(campaign).stream()
        .map(row -> Map.<String, Object>of(
            "status", row.status(),
            "label", firstNonBlank(row.statusName(), row.status()),
            "source", row.sourceTable()
        ))
        .toList();
  }

  public List<Map<String, Object>> listSubtipificaciones(String campaignId, String tipificacion) {
    String campaign = firstNonBlank(campaignId, "Manual2");
    List<SubtipificacionEntity> rows;
    if (StringUtils.hasText(tipificacion)) {
      rows = subtipificacionRepository.findByCampanaAndTipificacionAndActivoOrderByNombreAsc(
          campaign,
          tipificacion.trim().toUpperCase(Locale.ROOT),
          true
      );
    } else {
      rows = subtipificacionRepository.findByCampanaAndActivoOrderByNombreAsc(campaign, true);
    }
    return rows.stream().map(this::toSubtipificacionItem).toList();
  }

  public List<Map<String, Object>> listAdminSubtipificaciones(String campaignId) {
    String campaign = firstNonBlank(campaignId, "Manual2");
    return subtipificacionRepository.findByCampanaOrderByTipificacionAscNombreAsc(campaign).stream()
        .map(this::toSubtipificacionItem)
        .toList();
  }

  @Transactional
  public Map<String, Object> createOrUpdateSubtipificacion(String adminUser, UpsertSubtipificacionRequest request) {
    String campaign = firstNonBlank(request.campana(), "Manual2");
    String codigo = firstNonBlank(request.codigo());
    String nombre = firstNonBlank(request.nombre());
    String tipificacion = firstNonBlank(request.tipificacion());

    if (!StringUtils.hasText(codigo) || !StringUtils.hasText(nombre) || !StringUtils.hasText(tipificacion)) {
      throw new VicidialServiceException(
          HttpStatus.BAD_REQUEST,
          "MANUAL2_SUBTIP_REQUIRED",
          "Codigo, nombre y tipificacion son obligatorios.",
          "Complete todos los campos requeridos.",
          null
      );
    }

    SubtipificacionEntity entity = subtipificacionRepository.findByCampanaAndCodigo(campaign, codigo)
        .orElseGet(SubtipificacionEntity::new);
    entity.campana = campaign;
    entity.codigo = codigo.trim().toUpperCase(Locale.ROOT);
    entity.nombre = nombre.trim();
    entity.tipificacion = tipificacion.trim().toUpperCase(Locale.ROOT);
    entity.activo = request.activo() == null || request.activo();
    if (entity.fechaCreacion == null) {
      entity.fechaCreacion = nowLima();
    }
    subtipificacionRepository.save(entity);

    return Map.of(
        "ok", true,
        "item", toSubtipificacionItem(entity),
        "updatedBy", Objects.toString(adminUser, "")
    );
  }

  @Transactional
  public Map<String, Object> setSubtipificacionActivo(String adminUser, String campaignId, String codigo, boolean activo) {
    String campaign = firstNonBlank(campaignId, "Manual2");
    String normalizedCode = firstNonBlank(codigo);
    if (!StringUtils.hasText(normalizedCode)) {
      throw new VicidialServiceException(
          HttpStatus.BAD_REQUEST,
          "MANUAL2_SUBTIP_CODE_REQUIRED",
          "El codigo de subtipificacion es obligatorio.",
          "Envie un codigo valido.",
          null
      );
    }

    SubtipificacionEntity entity = subtipificacionRepository.findByCampanaAndCodigo(campaign, normalizedCode)
        .orElseThrow(() -> new VicidialServiceException(
            HttpStatus.NOT_FOUND,
            "MANUAL2_SUBTIP_NOT_FOUND",
            "No se encontro la subtipificacion solicitada.",
            "Verifique campana y codigo.",
            Map.of("campana", campaign, "codigo", normalizedCode)
        ));
    entity.activo = activo;
    subtipificacionRepository.save(entity);

    return Map.of(
        "ok", true,
        "item", toSubtipificacionItem(entity),
        "updatedBy", Objects.toString(adminUser, "")
    );
  }

  public Map<String, Object> lookupContacto(String phoneNumber) {
    String telefono = normalizePhone(phoneNumber);
    if (!StringUtils.hasText(telefono)) {
      return Map.of("found", false, "telefono", "");
    }

    var contactoOpt = contactoRepository.findByTelefono(telefono);
    long totalGestiones = gestionLlamadaRepository.countByTelefono(telefono);
    List<Map<String, Object>> historial = gestionLlamadaRepository.findTop100ByTelefonoOrderByFechaGestionDesc(telefono).stream()
        .limit(20)
        .map(this::toGestionItem)
        .toList();

    if (contactoOpt.isEmpty()) {
      return Map.of(
          "found", false,
          "telefono", telefono,
          "totalGestiones", totalGestiones,
          "historial", historial
      );
    }

    ContactoEntity contacto = contactoOpt.get();
    return Map.of(
        "found", true,
        "telefono", telefono,
        "contacto", Map.of(
            "id", contacto.id,
            "telefono", contacto.telefono,
            "nombres", Objects.toString(contacto.nombres, ""),
            "apellidos", Objects.toString(contacto.apellidos, ""),
            "documento", Objects.toString(contacto.documento, ""),
            "origen", Objects.toString(contacto.origen, ""),
            "fechaCreacion", formatLocal(contacto.fechaCreacion)
        ),
        "totalGestiones", totalGestiones,
        "historial", historial
    );
  }

  @Transactional
  public Map<String, Object> registerInteractionStart(String agentUser, RegisterInteractionRequest request) {
    String telefono = normalizePhone(request.phoneNumber());
    if (!StringUtils.hasText(telefono)) {
      return Map.of("ok", false, "reason", "PHONE_REQUIRED");
    }

    ContactoEntity contacto = upsertContacto(telefono, null, null, null, "MANUAL2");
    InteraccionEntity interaccion = findActiveInteraction(agentUser, telefono, request.callId()).orElseGet(InteraccionEntity::new);

    if (interaccion.id == null) {
      interaccion.fechaInicio = nowLima();
      interaccion.estado = "ACTIVA";
    }
    interaccion.clienteId = contacto.id;
    interaccion.telefono = telefono;
    interaccion.campana = firstNonBlank(request.campaignId(), "Manual2");
    interaccion.modoLlamada = firstNonBlank(request.modoLlamada(), "manual");
    interaccion.agente = agentUser;
    interaccion.callId = firstNonBlank(request.callId(), interaccion.callId);
    interaccion.uniqueId = firstNonBlank(request.uniqueId(), interaccion.uniqueId);
    interaccion.leadId = firstNonNull(request.leadId(), interaccion.leadId);
    interaccion.nombreAudio = firstNonBlank(sanitizeRecordingName(request.nombreAudio()), interaccion.nombreAudio);
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
    String telefono = normalizePhone(request.phoneNumber());
    String campana = firstNonBlank(request.campaignId(), "Manual2");
    if (!StringUtils.hasText(telefono)) {
      throw new VicidialServiceException(HttpStatus.BAD_REQUEST, "MANUAL2_PHONE_REQUIRED", "El telefono es obligatorio.", "Ingrese un telefono valido.", null);
    }

    ContactoEntity contacto = upsertContacto(telefono, request.nombres(), request.apellidos(), request.documento(), request.origen());
    FormularioManual2Entity formulario = new FormularioManual2Entity();
    formulario.contactoId = contacto.id;
    formulario.telefono = telefono;
    formulario.comentario = request.comentario();
    formulario.campana = campana;
    formulario.creadoPor = agentUser;
    formulario.fechaRegistro = nowLima();
    formularioManual2Repository.save(formulario);

    return Map.of(
        "ok", true,
        "formularioId", formulario.id,
        "contactoId", contacto.id,
        "telefono", telefono,
        "fechaRegistro", formatLocal(formulario.fechaRegistro)
    );
  }

  @Transactional
  public Map<String, Object> saveGestionFinal(String agentUser, SaveGestionRequest request) {
    String telefono = normalizePhone(request.phoneNumber());
    String campana = firstNonBlank(request.campaignId(), "Manual2");
    String disposicion = firstNonBlank(request.disposicion());
    String tipificacion = firstNonBlank(request.tipificacion(), disposicion);

    if (!StringUtils.hasText(telefono)) {
      throw new VicidialServiceException(HttpStatus.BAD_REQUEST, "MANUAL2_PHONE_REQUIRED", "El telefono es obligatorio.", "Ingrese un telefono valido.", null);
    }
    if (!StringUtils.hasText(disposicion)) {
      throw new VicidialServiceException(HttpStatus.BAD_REQUEST, "MANUAL2_DISPOSITION_REQUIRED", "La disposicion es obligatoria.", "Seleccione una disposicion valida.", null);
    }

    assertCallFinished(agentUser, campana, request.leadId(), request.callId(), telefono);

    ContactoEntity contacto = upsertContacto(telefono, request.nombres(), request.apellidos(), request.documento(), request.origen());

    FormularioManual2Entity formulario = new FormularioManual2Entity();
    formulario.contactoId = contacto.id;
    formulario.telefono = telefono;
    formulario.comentario = request.comentario();
    formulario.campana = campana;
    formulario.creadoPor = agentUser;
    formulario.fechaRegistro = nowLima();
    formularioManual2Repository.save(formulario);

    String recordingName = sanitizeRecordingName(request.nombreAudio());

    InteraccionEntity interaccion = resolveInteractionForFinalSave(
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
            recordingName = firstNonBlank(recordingName, sanitizeRecordingName(sync.recordingFilename()));
          } else {
            vicidialSyncStatus = "FAILED";
            vicidialSyncError = firstNonBlank(sync.error(), "Post-call sync failed");
          }
        } else {
          vicidialClient.externalStatus(agentUser, disposicion, request.leadId(), campana);
          vicidialSyncStatus = "SYNCED";
        }
      } catch (Exception ex) {
        vicidialSyncStatus = "FAILED";
        vicidialSyncError = firstNonBlank(ex.getMessage(), ex.getClass().getSimpleName());
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
    gestion.fechaGestion = nowLima();
    gestion.tipificacion = tipificacion;
    gestion.disposicion = disposicion;
    gestion.subtipificacion = request.subtipificacion();
    gestion.observaciones = firstNonBlank(request.observaciones(), request.comentario());
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
        "fechaGestion", formatLocal(gestion.fechaGestion),
        "vicidialSyncStatus", vicidialSyncStatus,
        "vicidialSyncError", Objects.toString(vicidialSyncError, ""),
        "nombreAudio", Objects.toString(gestion.nombreAudio, "")
    );
  }

  public Map<String, Object> historyByTelefono(String phoneNumber) {
    String telefono = normalizePhone(phoneNumber);
    if (!StringUtils.hasText(telefono)) {
      return Map.of("items", List.of(), "total", 0, "telefono", "");
    }

    var contacto = contactoRepository.findByTelefono(telefono).orElse(null);
    var rows = gestionLlamadaRepository.findTop100ByTelefonoOrderByFechaGestionDesc(telefono);
    List<Map<String, Object>> items = rows.stream().map(this::toGestionItem).toList();

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("telefono", telefono);
    response.put("total", rows.size());
    if (contacto != null) {
      response.put("contacto", Map.of(
          "id", contacto.id,
          "nombres", Objects.toString(contacto.nombres, ""),
          "apellidos", Objects.toString(contacto.apellidos, ""),
          "documento", Objects.toString(contacto.documento, "")
      ));
    } else {
      response.put("contacto", null);
    }
    response.put("items", items);
    return response;
  }

  public ReportResult report(ReportFilter filter) {
    LocalDateTime from = filter.from().atStartOfDay();
    LocalDateTime to = filter.to().plusDays(1).atStartOfDay();
    int safePage = Math.max(filter.page(), 0);
    int safeSize = Math.min(Math.max(filter.size(), 1), 500);
    int offset = safePage * safeSize;

    StringBuilder where = new StringBuilder("""
        WHERE g.fecha_gestion >= ? AND g.fecha_gestion < ?
        """);
    List<Object> params = new ArrayList<>();
    params.add(Timestamp.valueOf(from));
    params.add(Timestamp.valueOf(to));

    appendFilters(where, params, filter);

    String baseFrom = """
        FROM gestiones_llamadas g
        LEFT JOIN formulario_manual2 f ON f.id = g.formulario_manual2_id
        LEFT JOIN contactos c ON c.id = g.contacto_id
        """;

    String countSql = "SELECT COUNT(*) " + baseFrom + " " + where;
    Long total = jdbcTemplate.queryForObject(countSql, Long.class, params.toArray());
    long resolvedTotal = total == null ? 0L : total;

    List<Object> dataParams = new ArrayList<>(params);
    dataParams.add(safeSize);
    dataParams.add(offset);

    String dataSql = """
        SELECT
          g.id,
          g.fecha_gestion,
          g.agente,
          g.campana,
          g.telefono,
          COALESCE(c.nombres, '') AS nombres,
          COALESCE(c.apellidos, '') AS apellidos,
          COALESCE(c.documento, '') AS documento,
          COALESCE(g.tipificacion, g.disposicion, '') AS tipificacion,
          COALESCE(g.disposicion, '') AS disposicion,
          COALESCE(g.subtipificacion, '') AS subtipificacion,
          COALESCE(g.observaciones, '') AS observaciones,
          COALESCE(f.comentario, '') AS comentario,
          g.lead_id,
          g.call_id,
          g.unique_id,
          g.nombre_audio,
          g.modo_llamada,
          g.duracion,
          g.vicidial_sync_status
        """ + baseFrom + " " + where + " ORDER BY g.fecha_gestion DESC LIMIT ? OFFSET ?";

    List<Map<String, Object>> items = jdbcTemplate.query(dataSql, (rs, rowNum) -> {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", rs.getLong("id"));
      row.put("fechaGestion", formatTimestamp(rs.getTimestamp("fecha_gestion")));
      row.put("agente", rs.getString("agente"));
      row.put("campana", rs.getString("campana"));
      row.put("telefono", rs.getString("telefono"));
      row.put("nombres", rs.getString("nombres"));
      row.put("apellidos", rs.getString("apellidos"));
      row.put("documento", rs.getString("documento"));
      row.put("tipificacion", rs.getString("tipificacion"));
      row.put("disposicion", rs.getString("disposicion"));
      row.put("subtipificacion", rs.getString("subtipificacion"));
      row.put("observaciones", rs.getString("observaciones"));
      row.put("comentario", rs.getString("comentario"));
      row.put("leadId", (Long) rs.getObject("lead_id"));
      row.put("callId", rs.getString("call_id"));
      row.put("uniqueId", rs.getString("unique_id"));
      row.put("nombreAudio", rs.getString("nombre_audio"));
      row.put("modoLlamada", rs.getString("modo_llamada"));
      row.put("duracion", (Integer) rs.getObject("duracion"));
      row.put("vicidialSyncStatus", rs.getString("vicidial_sync_status"));
      return row;
    }, dataParams.toArray());

    return new ReportResult(items, resolvedTotal, safePage, safeSize, formatLocal(nowLima()));
  }

  public String exportCsv(ReportFilter filter) {
    ReportFilter exportFilter = new ReportFilter(
        filter.from(),
        filter.to(),
        filter.campana(),
        filter.agente(),
        filter.tipificacion(),
        filter.disposicion(),
        filter.subtipificacion(),
        filter.telefono(),
        filter.cliente(),
        0,
        100000
    );
    ReportResult report = report(exportFilter);

    StringBuilder sb = new StringBuilder("fecha_gestion,agente,campana,telefono,nombres,apellidos,documento,tipificacion,disposicion,subtipificacion,comentario,observaciones,lead_id,call_id,unique_id,nombre_audio,modo_llamada,duracion\n");
    for (Map<String, Object> row : report.items()) {
      sb.append(csv(row.get("fechaGestion"))).append(',')
          .append(csv(row.get("agente"))).append(',')
          .append(csv(row.get("campana"))).append(',')
          .append(csv(row.get("telefono"))).append(',')
          .append(csv(row.get("nombres"))).append(',')
          .append(csv(row.get("apellidos"))).append(',')
          .append(csv(row.get("documento"))).append(',')
          .append(csv(row.get("tipificacion"))).append(',')
          .append(csv(row.get("disposicion"))).append(',')
          .append(csv(row.get("subtipificacion"))).append(',')
          .append(csv(row.get("comentario"))).append(',')
          .append(csv(row.get("observaciones"))).append(',')
          .append(csv(row.get("leadId"))).append(',')
          .append(csv(row.get("callId"))).append(',')
          .append(csv(row.get("uniqueId"))).append(',')
          .append(csv(row.get("nombreAudio"))).append(',')
          .append(csv(row.get("modoLlamada"))).append(',')
          .append(csv(row.get("duracion")))
          .append('\n');
    }
    return sb.toString();
  }

  private void appendFilters(StringBuilder where, List<Object> params, ReportFilter filter) {
    if (StringUtils.hasText(filter.campana())) {
      where.append(" AND g.campana = ? ");
      params.add(filter.campana().trim());
    }
    if (StringUtils.hasText(filter.agente())) {
      where.append(" AND g.agente = ? ");
      params.add(filter.agente().trim());
    }
    if (StringUtils.hasText(filter.tipificacion())) {
      where.append(" AND COALESCE(g.tipificacion, g.disposicion, '') = ? ");
      params.add(filter.tipificacion().trim());
    }
    if (StringUtils.hasText(filter.disposicion())) {
      where.append(" AND g.disposicion = ? ");
      params.add(filter.disposicion().trim());
    }
    if (StringUtils.hasText(filter.subtipificacion())) {
      where.append(" AND g.subtipificacion = ? ");
      params.add(filter.subtipificacion().trim());
    }
    if (StringUtils.hasText(filter.telefono())) {
      String normalizedPhone = normalizePhone(filter.telefono());
      if (StringUtils.hasText(normalizedPhone)) {
        where.append(" AND g.telefono LIKE ? ");
        params.add("%" + normalizedPhone + "%");
      }
    }
    if (StringUtils.hasText(filter.cliente())) {
      where.append(" AND LOWER(CONCAT(COALESCE(c.nombres,''), ' ', COALESCE(c.apellidos,''))) LIKE ? ");
      params.add("%" + filter.cliente().trim().toLowerCase(Locale.ROOT) + "%");
    }
  }

  private void assertCallFinished(String agentUser, String campaignId, Long leadId, String callId, String phoneNumber) {
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

  private ContactoEntity upsertContacto(String telefono, String nombres, String apellidos, String documento, String origen) {
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

  private InteraccionEntity resolveInteractionForFinalSave(
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

  private Optional<InteraccionEntity> findActiveInteraction(String agentUser, String telefono, String callId) {
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

  private Map<String, Object> toGestionItem(GestionLlamadaEntity gestion) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("id", gestion.id);
    item.put("fechaGestion", formatLocal(gestion.fechaGestion));
    item.put("agente", gestion.agente);
    item.put("campana", gestion.campana);
    item.put("telefono", gestion.telefono);
    item.put("tipificacion", firstNonBlank(gestion.tipificacion, gestion.disposicion));
    item.put("disposicion", gestion.disposicion);
    item.put("subtipificacion", gestion.subtipificacion);
    item.put("observaciones", Objects.toString(gestion.observaciones, ""));
    item.put("leadId", gestion.leadId);
    item.put("callId", gestion.callId);
    item.put("uniqueId", gestion.uniqueId);
    item.put("nombreAudio", gestion.nombreAudio);
    item.put("duracion", gestion.duracion);
    item.put("interaccionId", gestion.interaccionId);
    return item;
  }

  private Map<String, Object> toSubtipificacionItem(SubtipificacionEntity entity) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("id", entity.id);
    item.put("campana", Objects.toString(entity.campana, ""));
    item.put("codigo", Objects.toString(entity.codigo, ""));
    item.put("nombre", Objects.toString(entity.nombre, ""));
    item.put("tipificacion", Objects.toString(entity.tipificacion, ""));
    item.put("activo", entity.activo);
    item.put("fechaCreacion", formatLocal(entity.fechaCreacion));
    return item;
  }

  private String normalizePhone(String phoneNumber) {
    String value = Objects.toString(phoneNumber, "").replaceAll("[^0-9]+", "");
    return StringUtils.hasText(value) ? value : "";
  }

  private String sanitizeRecordingName(String value) {
    String normalized = firstNonBlank(value);
    if (!StringUtils.hasText(normalized)) {
      return null;
    }
    if ("0".equals(normalized.trim())) {
      return null;
    }
    return normalized.trim();
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (StringUtils.hasText(value)) {
        return value.trim();
      }
    }
    return null;
  }

  private Long firstNonNull(Long... values) {
    for (Long value : values) {
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  private String csv(Object value) {
    String text = Objects.toString(value, "");
    return '"' + text.replace("\"", "\"\"") + '"';
  }

  private LocalDateTime nowLima() {
    return LocalDateTime.now(LIMA_ZONE);
  }

  private String formatTimestamp(Timestamp timestamp) {
    if (timestamp == null) {
      return null;
    }
    return formatLocal(timestamp.toLocalDateTime());
  }

  private String formatLocal(LocalDateTime value) {
    if (value == null) {
      return null;
    }
    return value.format(LOCAL_DT_FMT);
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
