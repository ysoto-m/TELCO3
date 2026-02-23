package com.telco3.agentui.vicidial;

import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.Optional;

public class VicidialServiceException extends RuntimeException {
  private final HttpStatus status;
  private final String code;
  private final String hint;
  private final Map<String, Object> details;

  public VicidialServiceException(HttpStatus status, String code, String message) {
    this(status, code, message, null, null);
  }

  public VicidialServiceException(HttpStatus status, String code, String message, String hint, Map<String, Object> details) {
    super(message);
    this.status = status;
    this.code = code;
    this.hint = hint;
    this.details = details;
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }

  public Optional<String> hint() {
    return Optional.ofNullable(hint);
  }

  public Optional<Map<String, Object>> details() {
    return Optional.ofNullable(details);
  }
}
