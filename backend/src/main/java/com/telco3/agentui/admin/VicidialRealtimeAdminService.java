package com.telco3.agentui.admin;

import com.telco3.agentui.vicidial.VicidialRealtimeQueryService;
import com.telco3.agentui.vicidial.VicidialRealtimeStatusMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class VicidialRealtimeAdminService {
  private final VicidialRealtimeQueryService queryService;
  private final VicidialRealtimeStatusMapper statusMapper;
  private final int serviceLevelThresholdSec;

  public VicidialRealtimeAdminService(
      VicidialRealtimeQueryService queryService,
      VicidialRealtimeStatusMapper statusMapper,
      @Value("${app.vicidial.realtime.service-level-threshold-seconds:20}") int serviceLevelThresholdSec
  ) {
    this.queryService = queryService;
    this.statusMapper = statusMapper;
    this.serviceLevelThresholdSec = serviceLevelThresholdSec;
  }

  public RealtimeSummaryResponse summary() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    List<RealtimeAgentItem> agentItems = buildAgentItems(null, null, null, null, now);
    long connectedAgents = agentItems.size();
    long availableAgents = countByVisibleStatus(agentItems, "Disponible");
    long incallAgents = countByVisibleStatus(agentItems, "En llamada");
    long pausedAgents = countByVicidialStatus(agentItems, "PAUSED");
    long wrapupAgents = countByVisibleStatus(agentItems, "WrapUp");
    var metrics = queryService.fetchTodayMetrics(serviceLevelThresholdSec);

    return new RealtimeSummaryResponse(
        connectedAgents,
        availableAgents,
        incallAgents,
        pausedAgents,
        wrapupAgents,
        metrics.answeredCalls(),
        metrics.abandonedCalls(),
        metrics.serviceLevelPercent(),
        metrics.averageWaitSeconds(),
        metrics.averageTalkSeconds(),
        now
    );
  }

  public RealtimeAgentsResponse agents(String campaign, String status, String pauseCode, String search) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    List<RealtimeAgentItem> items = buildAgentItems(campaign, status, pauseCode, search, now);
    return new RealtimeAgentsResponse(items, items.size(), now);
  }

  public PauseCodesResponse pauseCodes() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    Map<String, PauseCodeItem> itemsByCode = new LinkedHashMap<>();

    for (var row : queryService.fetchPauseCodes()) {
      String code = normalizeToken(row.pauseCode());
      if (!StringUtils.hasText(code)) {
        continue;
      }
      String pauseName = firstNonBlank(row.pauseName(), code);
      String visibleName = statusMapper.visibleNameForPauseCode(code, pauseName);
      itemsByCode.put(code, new PauseCodeItem(code, pauseName, visibleName, Objects.toString(row.billable(), "")));
    }

    statusMapper.pauseCodeCatalog().forEach((code, visible) -> {
      itemsByCode.putIfAbsent(code, new PauseCodeItem(code, code, visible, ""));
    });

    List<PauseCodeItem> items = itemsByCode.values().stream()
        .sorted(Comparator.comparing(PauseCodeItem::pauseCode))
        .toList();
    return new PauseCodesResponse(items, now);
  }

  public CampaignsResponse campaigns() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    return new CampaignsResponse(queryService.fetchCampaigns(), now);
  }

  public LeadImportDesignResponse leadImportDesign() {
    return new LeadImportDesignResponse(
        "NOT_IMPLEMENTED",
        "Endpoint reservado para carga masiva controlada desde CRM backend.",
        List.of(
            "Validar campana y list_id destino",
            "Parsear CSV en lote y normalizar phone_code/phone_number",
            "Aplicar validacion DNC y duplicados",
            "Insertar por lotes en tablas Vicidial con trazabilidad",
            "Registrar job y resultado para auditoria"
        )
    );
  }

  private List<RealtimeAgentItem> buildAgentItems(
      String campaign,
      String status,
      String pauseCode,
      String search,
      OffsetDateTime now
  ) {
    Map<String, Long> completedByAgent = queryService.fetchCompletedTodayByAgent();
    List<RealtimeAgentItem> rows = new ArrayList<>();
    for (var row : queryService.fetchLiveAgents(campaign, pauseCode, search)) {
      String agentUser = Objects.toString(row.agentUser(), "").trim();
      if (!StringUtils.hasText(agentUser)) {
        continue;
      }
      String technicalStatus = normalizeToken(row.vicidialStatus());
      String normalizedPauseCode = normalizeToken(row.pauseCode());
      var statusView = statusMapper.map(technicalStatus, normalizedPauseCode);
      if (!statusMapper.matchesStatusFilter(status, technicalStatus, statusView.visibleStatus())) {
        continue;
      }
      String agentName = firstNonBlank(row.agentName(), agentUser);
      String currentPhone = firstNonBlank(row.currentPhone(), null);
      long stateSeconds = toStateSeconds(row.stateReferenceTime(), now);
      String connectedTo = resolveConnectedTo(statusView.visibleStatus(), currentPhone, row.channel(), row.confExten());
      rows.add(new RealtimeAgentItem(
          agentUser,
          agentName,
          firstNonBlank(row.extension(), null),
          firstNonBlank(row.campaignId(), null),
          technicalStatus,
          firstNonBlank(normalizedPauseCode, null),
          statusView.visibleStatus(),
          statusView.subStatus(),
          currentPhone,
          connectedTo,
          stateSeconds,
          row.lastCallTime(),
          completedByAgent.getOrDefault(agentUser, 0L)
      ));
    }
    rows.sort(Comparator
        .comparingInt((RealtimeAgentItem i) -> statusPriority(i.visibleStatus()))
        .thenComparing(RealtimeAgentItem::agentUser));
    return rows;
  }

  private long countByVisibleStatus(List<RealtimeAgentItem> items, String visibleStatus) {
    return items.stream()
        .filter(item -> Objects.equals(item.visibleStatus(), visibleStatus))
        .count();
  }

  private long countByVicidialStatus(List<RealtimeAgentItem> items, String technicalStatus) {
    String normalized = normalizeToken(technicalStatus);
    return items.stream()
        .filter(item -> Objects.equals(item.vicidialStatus(), normalized))
        .count();
  }

  private int statusPriority(String visibleStatus) {
    return switch (Objects.toString(visibleStatus, "")) {
      case "En llamada" -> 0;
      case "Disponible" -> 1;
      case "WrapUp" -> 2;
      case "Pausa", "Break", "Baño", "Capacitación", "Soporte", "Consulta supervisor", "Refrigerio", "Reunión", "Back Office" -> 3;
      case "Desconectado" -> 4;
      default -> 5;
    };
  }

  private String resolveConnectedTo(String visibleStatus, String currentPhone, String channel, String confExten) {
    if ("En llamada".equals(visibleStatus)) {
      if (StringUtils.hasText(currentPhone)) {
        return "Cliente";
      }
      String normalizedChannel = Objects.toString(channel, "").toUpperCase(Locale.ROOT);
      String normalizedConf = Objects.toString(confExten, "").replaceAll("[^0-9]+", "");
      if (normalizedChannel.contains("TRUNK")) {
        return "Cliente";
      }
      if (StringUtils.hasText(normalizedConf) && normalizedChannel.contains("/" + normalizedConf)) {
        return "Conferencia";
      }
      if (normalizedChannel.startsWith("LOCAL/")) {
        return "Conferencia";
      }
      return "Cliente";
    }
    if ("En cola".equals(visibleStatus)) {
      return "Cola";
    }
    if ("WrapUp".equals(visibleStatus)) {
      return "Gestión";
    }
    return null;
  }

  private long toStateSeconds(OffsetDateTime stateReferenceTime, OffsetDateTime now) {
    if (stateReferenceTime == null || now == null) {
      return 0;
    }
    long seconds = Duration.between(stateReferenceTime, now).getSeconds();
    return Math.max(seconds, 0);
  }

  private String normalizeToken(String value) {
    return Objects.toString(value, "")
        .trim()
        .toUpperCase(Locale.ROOT);
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (StringUtils.hasText(value)) {
        return value.trim();
      }
    }
    return null;
  }

  public record RealtimeSummaryResponse(
      long connectedAgents,
      long availableAgents,
      long incallAgents,
      long pausedAgents,
      long wrapupAgents,
      long answeredCalls,
      long abandonedCalls,
      int serviceLevelPercent,
      long averageWaitSeconds,
      long averageTalkSeconds,
      OffsetDateTime generatedAt
  ) {
  }

  public record RealtimeAgentsResponse(List<RealtimeAgentItem> items, long total, OffsetDateTime generatedAt) {
  }

  public record RealtimeAgentItem(
      String agentUser,
      String agentName,
      String extension,
      String campaignId,
      String vicidialStatus,
      String pauseCode,
      String visibleStatus,
      String subStatus,
      String currentPhone,
      String connectedTo,
      long stateSeconds,
      OffsetDateTime lastCallTime,
      long completedToday
  ) {
  }

  public record PauseCodesResponse(List<PauseCodeItem> items, OffsetDateTime generatedAt) {
  }

  public record PauseCodeItem(String pauseCode, String pauseName, String visibleName, String billable) {
  }

  public record CampaignsResponse(List<String> items, OffsetDateTime generatedAt) {
  }

  public record LeadImportDesignResponse(String code, String message, List<String> steps) {
  }
}
