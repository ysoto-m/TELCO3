package com.telco3.agentui.manual2;

import com.telco3.agentui.campaign.core.CampaignInteractionCoreService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class Manual2ReportService {
  private final JdbcTemplate jdbcTemplate;
  private final CampaignInteractionCoreService coreService;

  public Manual2ReportService(JdbcTemplate jdbcTemplate, CampaignInteractionCoreService coreService) {
    this.jdbcTemplate = jdbcTemplate;
    this.coreService = coreService;
  }

  public Manual2Service.ReportResult report(Manual2Service.ReportFilter filter) {
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

    return new Manual2Service.ReportResult(items, resolvedTotal, safePage, safeSize, coreService.formatLocal(coreService.nowLima()));
  }

  public String exportCsv(Manual2Service.ReportFilter filter) {
    Manual2Service.ReportFilter exportFilter = new Manual2Service.ReportFilter(
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
    Manual2Service.ReportResult report = report(exportFilter);

    StringBuilder sb = new StringBuilder("fecha_gestion,agente,campana,telefono,nombres,apellidos,documento,tipificacion,disposicion,subtipificacion,comentario,observaciones,lead_id,call_id,unique_id,nombre_audio,modo_llamada,duracion\n");
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
          .append(coreService.csv(row.get("observaciones"))).append(',')
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

  private void appendFilters(StringBuilder where, List<Object> params, Manual2Service.ReportFilter filter) {
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
      String normalizedPhone = coreService.normalizePhone(filter.telefono());
      if (StringUtils.hasText(normalizedPhone)) {
        where.append(" AND g.telefono LIKE ? ");
        params.add("%" + normalizedPhone + "%");
      }
    }
    if (StringUtils.hasText(filter.cliente())) {
      where.append(" AND LOWER(CONCAT(COALESCE(c.nombres,''), ' ', COALESCE(c.apellidos,''))) LIKE ? ");
      params.add("%" + coreService.normalizeForLike(filter.cliente()) + "%");
    }
  }
}
