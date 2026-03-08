package com.telco3.agentui.vicidial;

import org.springframework.util.StringUtils;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class VicidialDialResponseParser {
  private static final Pattern CALL_ID_PATTERN = Pattern.compile("^M[0-9A-Z]{6,}$");
  private static final Pattern LEAD_ID_PATTERN = Pattern.compile("(?i)(?:lead_id|leadid)\\s*[:=]?\\s*([0-9]{1,12})");
  private static final Pattern FIRST_TOKEN = Pattern.compile("^\\s*([A-Za-z0-9_-]+)");
  private static final Pattern KEY_VALUE_PATTERN = Pattern.compile("^\\s*([A-Za-z0-9_\\-\\s]+?)\\s*[:=]\\s*(.*?)\\s*$");

  public ParsedDialResponse parse(String rawBody) {
    DetailedParsedDialResponse detailed = parseDetailed(rawBody);
    return new ParsedDialResponse(detailed.classification(), detailed.success(), detailed.callId(), detailed.leadId());
  }

  public DetailedParsedDialResponse parseDetailed(String rawBody) {
    String body = Objects.toString(rawBody, "");
    String normalized = body.toLowerCase(Locale.ROOT);
    String firstLine = body.lines().findFirst().orElse("").trim();
    String firstToken = firstToken(firstLine);
    Map<String, String> parsed = parseKeyValues(body);
    String callId = firstNonBlank(parsed, "call_id", "callid", "callerid", "call_id_number");
    if (!StringUtils.hasText(callId)) {
      callId = findCallId(firstToken, body);
    }
    Long leadId = parseLong(firstNonBlank(parsed, "lead_id", "leadid"));
    if (leadId == null) {
      leadId = findLeadId(body);
    }
    String phoneNumber = firstNonBlank(parsed, "phone_number", "phonenumber");
    String listId = firstNonBlank(parsed, "list_id", "listid");
    String leadStatus = firstNonBlank(parsed, "lead_status", "leadstatus", "status", "result");
    PositionalDialFields positional = parsePositionalDialFields(body);
    callId = firstNonBlank(callId, positional.callId());
    leadId = firstNonNull(leadId, positional.leadId());
    phoneNumber = firstNonBlank(phoneNumber, positional.phoneNumber());
    listId = firstNonBlank(listId, positional.listId());
    leadStatus = firstNonBlank(leadStatus, positional.leadStatus());

    if (containsAny(normalized, "no leads", "hopper")) {
      return new DetailedParsedDialResponse(DialClassification.NO_LEADS, false, callId, leadId, phoneNumber, listId, leadStatus, parsed);
    }
    if (containsAny(
        normalized,
        "agent_user is not logged in",
        "not logged in",
        "re-login",
        "please login",
        "name=\"vd_login\"",
        "name='vd_login'",
        "id=\"vd_login\"",
        "id='vd_login'",
        "name=\"vd_pass\"",
        "name='vd_pass'",
        "id=\"vd_pass\"",
        "id='vd_pass'"
    )) {
      return new DetailedParsedDialResponse(DialClassification.RELOGIN_REQUIRED, false, callId, leadId, phoneNumber, listId, leadStatus, parsed);
    }
    if (containsAny(normalized, "permission denied", "does not have permission", "permission to perform function", "invalid session")) {
      return new DetailedParsedDialResponse(DialClassification.INVALID_SESSION, false, callId, leadId, phoneNumber, listId, leadStatus, parsed);
    }
    if (containsAny(normalized, "missing", "must be", "error: user", "error: campaign", "error: phone", "invalid phone", "invalid campaign", "invalid list")) {
      return new DetailedParsedDialResponse(DialClassification.INVALID_PARAMS, false, callId, leadId, phoneNumber, listId, leadStatus, parsed);
    }
    if (containsAny(normalized, "error:", "permission")) {
      return new DetailedParsedDialResponse(DialClassification.INVALID_SESSION, false, callId, leadId, phoneNumber, listId, leadStatus, parsed);
    }

    boolean hasKnownMarker = callId != null
        || leadId != null
        || StringUtils.hasText(phoneNumber)
        || StringUtils.hasText(listId)
        || StringUtils.hasText(leadStatus)
        || containsAny(normalized, "mandialnextcall", "call_id", "lead_id", "channel", "phone_number", "list_id");
    boolean success = (callId != null) || leadId != null || hasKnownMarker;
    DialClassification classification = success ? DialClassification.SUCCESS : DialClassification.UNKNOWN;
    return new DetailedParsedDialResponse(classification, success, callId, leadId, phoneNumber, listId, leadStatus, parsed);
  }

  private String firstToken(String firstLine) {
    Matcher matcher = FIRST_TOKEN.matcher(Objects.toString(firstLine, ""));
    return matcher.find() ? matcher.group(1).trim() : "";
  }

  private String findCallId(String firstToken, String body) {
    if (CALL_ID_PATTERN.matcher(firstToken).matches()) {
      return firstToken;
    }
    Matcher matcher = Pattern.compile("\\b(M[0-9A-Z]{8,})\\b").matcher(body);
    return matcher.find() ? matcher.group(1) : null;
  }

  private Long findLeadId(String body) {
    Matcher matcher = LEAD_ID_PATTERN.matcher(body);
    if (matcher.find()) {
      return parseLong(matcher.group(1));
    }
    return null;
  }

  private Long parseLong(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private Map<String, String> parseKeyValues(String body) {
    Map<String, String> parsed = new LinkedHashMap<>();
    for (String line : Objects.toString(body, "").split("\\r?\\n")) {
      String trimmed = Objects.toString(line, "").trim();
      if (!StringUtils.hasText(trimmed)) {
        continue;
      }
      if (trimmed.contains("&") && trimmed.contains("=")) {
        for (String pair : trimmed.split("&")) {
          parsePair(parsed, pair);
        }
      }
      if (trimmed.contains("|")) {
        for (String pair : trimmed.split("\\|")) {
          parsePair(parsed, pair);
        }
      }
      parsePair(parsed, trimmed);
    }
    return parsed;
  }

  private void parsePair(Map<String, String> parsed, String candidate) {
    Matcher matcher = KEY_VALUE_PATTERN.matcher(Objects.toString(candidate, ""));
    if (!matcher.find()) {
      return;
    }
    String key = normalizeKey(matcher.group(1));
    String value = sanitizeValue(matcher.group(2));
    if (!StringUtils.hasText(key) || !StringUtils.hasText(value)) {
      return;
    }
    parsed.putIfAbsent(key, value);
    String compact = key.replace("_", "");
    if (!compact.equals(key)) {
      parsed.putIfAbsent(compact, value);
    }
  }

  private String normalizeKey(String raw) {
    String key = Objects.toString(raw, "").trim().toLowerCase(Locale.ROOT);
    if (!StringUtils.hasText(key)) {
      return "";
    }
    key = key
        .replaceAll("[^a-z0-9]+", "_")
        .replaceAll("_+", "_")
        .replaceAll("^_+", "")
        .replaceAll("_+$", "");
    if (key.startsWith("var_")) {
      key = key.substring(4);
    }
    if (key.startsWith("window_")) {
      key = key.substring(7);
    }
    if (key.startsWith("this_")) {
      key = key.substring(5);
    }
    return key;
  }

  private String sanitizeValue(String raw) {
    String value = Objects.toString(raw, "").trim();
    if (!StringUtils.hasText(value)) {
      return value;
    }
    return value
        .replaceAll("^[\"']+", "")
        .replaceAll("[\"']+$", "")
        .replaceAll("[;,]+$", "")
        .trim();
  }

  private String firstNonBlank(Map<String, String> parsed, String... keys) {
    for (String key : keys) {
      String normalized = normalizeKey(key);
      String value = parsed.get(normalized);
      if (!StringUtils.hasText(value)) {
        value = parsed.get(normalized.replace("_", ""));
      }
      if (StringUtils.hasText(value)) {
        return value.trim();
      }
    }
    return null;
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

  private PositionalDialFields parsePositionalDialFields(String body) {
    String safeBody = Objects.toString(body, "");
    if (!StringUtils.hasText(safeBody)) {
      return PositionalDialFields.empty();
    }
    Matcher callMatcher = Pattern.compile("\\b(M[0-9A-Z]{8,})\\b").matcher(safeBody);
    if (!callMatcher.find()) {
      return PositionalDialFields.empty();
    }
    String callId = callMatcher.group(1);
    String sourceLine = null;
    for (String line : safeBody.split("\\r?\\n")) {
      if (Objects.toString(line, "").contains(callId)) {
        sourceLine = line;
        break;
      }
    }
    if (!StringUtils.hasText(sourceLine)) {
      sourceLine = safeBody;
    }
    int callPos = sourceLine.indexOf(callId);
    String tail = callPos >= 0
        ? sourceLine.substring(Math.min(callPos + callId.length(), sourceLine.length())).trim()
        : sourceLine.trim();
    if (!StringUtils.hasText(tail)) {
      return new PositionalDialFields(callId, null, null, null, null);
    }
    String[] tokens = tail.split("\\s+");
    Long leadId = tokens.length >= 1 ? parseLong(tokens[0]) : null;
    String leadStatus = tokens.length >= 2 && tokens[1].matches("[A-Za-z0-9_\\-]{1,20}") ? tokens[1] : null;
    String listId = null;
    String phoneNumber = null;
    int startIndex = 0;
    if (leadId != null) {
      startIndex = 1;
    }
    if (StringUtils.hasText(leadStatus)) {
      startIndex = 2;
    }
    for (int i = startIndex; i < tokens.length; i++) {
      String token = tokens[i];
      if (listId == null && token.matches("[0-9]{2,6}")) {
        listId = token;
      }
      if (phoneNumber == null && token.matches("[0-9]{7,15}")) {
        phoneNumber = token;
      }
      if (listId != null && phoneNumber != null) {
        break;
      }
    }
    return new PositionalDialFields(callId, leadId, phoneNumber, listId, leadStatus);
  }

  private boolean containsAny(String normalized, String... values) {
    for (String value : values) {
      if (normalized.contains(value)) {
        return true;
      }
    }
    return false;
  }

  public record ParsedDialResponse(DialClassification classification, boolean success, String callId, Long leadId) {
  }

  public record DetailedParsedDialResponse(
      DialClassification classification,
      boolean success,
      String callId,
      Long leadId,
      String phoneNumber,
      String listId,
      String leadStatus,
      Map<String, String> parsedValues
  ) {
  }

  private record PositionalDialFields(String callId, Long leadId, String phoneNumber, String listId, String leadStatus) {
    static PositionalDialFields empty() {
      return new PositionalDialFields(null, null, null, null, null);
    }
  }

  public enum DialClassification {
    SUCCESS,
    RELOGIN_REQUIRED,
    NO_LEADS,
    INVALID_SESSION,
    INVALID_PARAMS,
    UNKNOWN
  }
}
