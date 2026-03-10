package com.telco3.agentui.validacionclaroperu;

import com.telco3.agentui.campaign.core.CampaignInteractionCoreService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ValidacionClaroPeruReportService {
  private final JdbcTemplate jdbcTemplate;
  private final CampaignInteractionCoreService coreService;

  public ValidacionClaroPeruReportService(JdbcTemplate jdbcTemplate, CampaignInteractionCoreService coreService) {
    this.jdbcTemplate = jdbcTemplate;
    this.coreService = coreService;
  }

  public ValidacionClaroPeruService.ReportResult report(ValidacionClaroPeruService.ReportFilter filter) {
    LocalDateTime from = filter.from().atStartOfDay();
    LocalDateTime to = filter.to().plusDays(1).atStartOfDay();
    int safePage = Math.max(filter.page(), 0);
    int safeSize = Math.min(Math.max(filter.size(), 1), 500);
    int offset = safePage * safeSize;
    String campaign = coreService.firstNonBlank(filter.campana(), ValidacionClaroPeruService.DEFAULT_CAMPAIGN);

    StringBuilder where = new StringBuilder("""
        WHERE g.fecha_gestion >= ? AND g.fecha_gestion < ? AND UPPER(g.campana) = UPPER(?)
        """);
    List<Object> params = new ArrayList<>();
    params.add(Timestamp.valueOf(from));
    params.add(Timestamp.valueOf(to));
    params.add(campaign);

    appendFilters(where, params, filter);

    String baseFrom = """
        FROM gestiones_llamadas g
        LEFT JOIN formulario_validacion_claro_peru f ON f.id = g.formulario_validacion_claro_peru_id
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
          COALESCE(f.nombres, c.nombres, '') AS nombres,
          COALESCE(f.apellidos, c.apellidos, '') AS apellidos,
          COALESCE(f.documento, c.documento, '') AS documento,
          COALESCE(g.tipificacion, g.disposicion, '') AS tipificacion,
          COALESCE(g.disposicion, '') AS disposicion,
          COALESCE(g.subtipificacion, '') AS subtipificacion,
          COALESCE(f.comentario, g.observaciones, '') AS comentario,
          COALESCE(f.encuesta, '') AS encuesta,
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
      row.put("fechaGestion", coreService.formatTimestamp(rs.getTimestamp("fecha_gestion")));
      row.put("agente", rs.getString("agente"));
      row.put("campana", rs.getString("campana"));
      row.put("telefono", rs.getString("telefono"));
      row.put("nombres", rs.getString("nombres"));
      row.put("apellidos", rs.getString("apellidos"));
      row.put("documento", rs.getString("documento"));
      row.put("tipificacion", rs.getString("tipificacion"));
      row.put("disposicion", rs.getString("disposicion"));
      row.put("subtipificacion", rs.getString("subtipificacion"));
      row.put("comentario", rs.getString("comentario"));
      row.put("encuesta", rs.getString("encuesta"));
      row.put("leadId", (Long) rs.getObject("lead_id"));
      row.put("callId", rs.getString("call_id"));
      row.put("uniqueId", rs.getString("unique_id"));
      row.put("nombreAudio", rs.getString("nombre_audio"));
      row.put("modoLlamada", rs.getString("modo_llamada"));
      row.put("duracion", (Integer) rs.getObject("duracion"));
      row.put("vicidialSyncStatus", rs.getString("vicidial_sync_status"));
      return row;
    }, dataParams.toArray());

    return new ValidacionClaroPeruService.ReportResult(items, resolvedTotal, safePage, safeSize, coreService.formatLocal(coreService.nowLima()));
  }

  public String exportCsv(ValidacionClaroPeruService.ReportFilter filter) {
    ValidacionClaroPeruService.ReportFilter exportFilter = new ValidacionClaroPeruService.ReportFilter(
        filter.from(),
        filter.to(),
        filter.campana(),
        filter.agente(),
        filter.tipificacion(),
        filter.disposicion(),
        filter.subtipificacion(),
        filter.telefono(),
        filter.documento(),
        filter.encuesta(),
        0,
        100000
    );
    ValidacionClaroPeruService.ReportResult report = report(exportFilter);

    StringBuilder sb = new StringBuilder("fecha_gestion,agente,campana,telefono,nombres,apellidos,documento,tipificacion,disposicion,subtipificacion,comentario,encuesta,lead_id,call_id,unique_id,nombre_audio,modo_llamada,duracion\n");
    for (Map<String, Object> row : report.items()) {
      sb.append(coreService.csv(row.get("fechaGestion"))).append(',')
          .append(coreService.csv(row.get("agente"))).append(',')
          .append(coreService.csv(row.get("campana"))).append(',')
          .append(coreService.csv(row.get("telefono"))).append(',')
          .append(coreService.csv(row.get("nombres"))).append(',')
          .append(coreService.csv(row.get("apellidos"))).append(',')
          .append(coreService.csv(row.get("documento"))).append(',')
          .append(coreService.csv(row.get("tipificacion"))).append(',')
          .append(coreService.csv(row.get("disposicion"))).append(',')
          .append(coreService.csv(row.get("subtipificacion"))).append(',')
          .append(coreService.csv(row.get("comentario"))).append(',')
          .append(coreService.csv(row.get("encuesta"))).append(',')
          .append(coreService.csv(row.get("leadId"))).append(',')
          .append(coreService.csv(row.get("callId"))).append(',')
          .append(coreService.csv(row.get("uniqueId"))).append(',')
          .append(coreService.csv(row.get("nombreAudio"))).append(',')
          .append(coreService.csv(row.get("modoLlamada"))).append(',')
          .append(coreService.csv(row.get("duracion")))
          .append('\n');
    }
    return sb.toString();
  }

  private void appendFilters(StringBuilder where, List<Object> params, ValidacionClaroPeruService.ReportFilter filter) {
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
      String normalizedPhone = coreService.normalizePhone(filter.telefono());
      if (StringUtils.hasText(normalizedPhone)) {
        where.append(" AND g.telefono LIKE ? ");
        params.add("%" + normalizedPhone + "%");
      }
    }
    if (StringUtils.hasText(filter.documento())) {
      where.append(" AND COALESCE(f.documento, c.documento, '') = ? ");
      params.add(filter.documento().trim());
    }
    if (StringUtils.hasText(filter.encuesta())) {
      where.append(" AND UPPER(COALESCE(f.encuesta, '')) = ? ");
      params.add(filter.encuesta().trim().toUpperCase());
    }
  }
}

