package com.telco3.agentui.vicidial;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
public class VicidialRealtimeStatusMapper {
  private static final Set<String> DISCONNECTED_STATUSES = Set.of(
      "DEAD",
      "LOGGED_OUT",
      "LOGOUT",
      "DISCONNECTED",
      "INACTIVE"
  );

  private static final Map<String, String> PAUSE_CODE_VISIBLE = buildPauseCodeVisible();

  public StatusView map(String vicidialStatus, String pauseCode) {
    String technical = normalizeToken(vicidialStatus);
    String pause = normalizeToken(pauseCode);
    String visible = switch (technical) {
      case "READY" -> "Disponible";
      case "INCALL" -> "En llamada";
      case "QUEUE" -> "En cola";
      case "CLOSER" -> "WrapUp";
      case "PAUSED" -> mapPauseVisibleStatus(pause);
      default -> {
        if (!StringUtils.hasText(technical)) {
          yield "Desconocido";
        }
        yield DISCONNECTED_STATUSES.contains(technical) ? "Desconectado" : "Desconocido";
      }
    };

    String subStatus = "PAUSED".equals(technical)
        ? (StringUtils.hasText(pause) ? pause : "PAUSED")
        : (StringUtils.hasText(technical) ? technical : "UNKNOWN");
    return new StatusView(visible, subStatus);
  }

  public String visibleNameForPauseCode(String pauseCode, String defaultLabel) {
    String normalizedPause = normalizeToken(pauseCode);
    if (PAUSE_CODE_VISIBLE.containsKey(normalizedPause)) {
      return PAUSE_CODE_VISIBLE.get(normalizedPause);
    }
    if (StringUtils.hasText(defaultLabel)) {
      return defaultLabel.trim();
    }
    return "Pausa";
  }

  public boolean matchesStatusFilter(String filter, String vicidialStatus, String visibleStatus) {
    String normalizedFilter = normalizeText(filter);
    if (!StringUtils.hasText(normalizedFilter)) {
      return true;
    }
    String normalizedTechnical = normalizeText(vicidialStatus);
    String normalizedVisible = normalizeText(visibleStatus);
    return normalizedFilter.equals(normalizedTechnical)
        || normalizedFilter.equals(normalizedVisible)
        || normalizedVisible.contains(normalizedFilter);
  }

  public Map<String, String> pauseCodeCatalog() {
    return PAUSE_CODE_VISIBLE;
  }

  private String mapPauseVisibleStatus(String pauseCode) {
    if (PAUSE_CODE_VISIBLE.containsKey(pauseCode)) {
      return PAUSE_CODE_VISIBLE.get(pauseCode);
    }
    return "Pausa";
  }

  private static Map<String, String> buildPauseCodeVisible() {
    Map<String, String> map = new LinkedHashMap<>();
    map.put("BREAK", "Break");
    map.put("BANO", "Baño");
    map.put("CAPA", "Capacitación");
    map.put("SOPORTE", "Soporte");
    map.put("SUP", "Consulta supervisor");
    map.put("REFRI", "Refrigerio");
    map.put("REUNION", "Reunión");
    map.put("BACKOFFICE", "Back Office");
    return Map.copyOf(map);
  }

  private String normalizeToken(String value) {
    return Objects.toString(value, "")
        .trim()
        .toUpperCase(Locale.ROOT);
  }

  private String normalizeText(String value) {
    String normalized = Normalizer.normalize(Objects.toString(value, ""), Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "")
        .toLowerCase(Locale.ROOT)
        .trim();
    normalized = normalized.replaceAll("[^a-z0-9]+", " ").trim();
    normalized = normalized.replaceAll("\\s+", " ");
    return normalized;
  }

  public record StatusView(String visibleStatus, String subStatus) {
  }
}
