package com.telco3.agentui.vicidial;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class VicidialDialResponseParser {
  private static final Pattern CALL_ID_PATTERN = Pattern.compile("^M[0-9A-Z]{8,}$");
  private static final Pattern LEAD_ID_PATTERN = Pattern.compile("(?i)(?:lead_id|leadid)\\s*[:=]?\\s*([0-9]{1,12})");
  private static final Pattern FIRST_TOKEN = Pattern.compile("^\\s*([A-Za-z0-9_-]+)");

  public ParsedDialResponse parse(String rawBody) {
    String body = Objects.toString(rawBody, "");
    String normalized = body.toLowerCase(Locale.ROOT);
    String firstLine = body.lines().findFirst().orElse("").trim();
    String firstToken = firstToken(firstLine);

    if (containsAny(normalized, "re-login", "not logged in", "login", "agent login", "name=\"vd_login\"")) {
      return new ParsedDialResponse(DialClassification.RELOGIN_REQUIRED, false, null, findLeadId(body));
    }
    if (containsAny(normalized, "no leads", "hopper")) {
      return new ParsedDialResponse(DialClassification.NO_LEADS, false, null, findLeadId(body));
    }
    if (containsAny(normalized, "invalid", "missing", "must be", "error: user", "error: campaign", "error: phone")) {
      return new ParsedDialResponse(DialClassification.INVALID_PARAMS, false, null, findLeadId(body));
    }
    if (containsAny(normalized, "error:", "invalid")) {
      return new ParsedDialResponse(DialClassification.INVALID_SESSION, false, null, findLeadId(body));
    }

    boolean hasKnownMarker = containsAny(normalized, "mandialnextcall", "call_id", "lead_id", "channel");
    Long leadId = findLeadId(body);
    String callId = findCallId(firstToken, body);
    boolean success = (callId != null) || leadId != null || hasKnownMarker;
    DialClassification classification = success ? DialClassification.SUCCESS : DialClassification.UNKNOWN;
    return new ParsedDialResponse(classification, success, callId, leadId);
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
      try {
        return Long.parseLong(matcher.group(1));
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
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

  public enum DialClassification {
    SUCCESS,
    RELOGIN_REQUIRED,
    NO_LEADS,
    INVALID_SESSION,
    INVALID_PARAMS,
    UNKNOWN
  }
}
