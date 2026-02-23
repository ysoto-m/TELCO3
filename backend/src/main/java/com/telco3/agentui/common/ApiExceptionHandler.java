package com.telco3.agentui.common;

import com.telco3.agentui.vicidial.VicidialServiceException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<?> validation(MethodArgumentNotValidException ex){
    String message = ex.getBindingResult().getFieldError() == null
        ? "Validation error"
        : Objects.toString(ex.getBindingResult().getFieldError().getDefaultMessage(), "Validation error");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", "VALIDATION_ERROR");
    body.put("message", message);
    return ResponseEntity.badRequest().body(body);
  }

  @ExceptionHandler(VicidialServiceException.class)
  ResponseEntity<?> vicidial(VicidialServiceException ex) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("ok", false);
    body.put("code", ex.code());
    body.put("message", Objects.toString(ex.getMessage(), "Vicidial integration error"));
    ex.hint().ifPresent(hint -> body.put("hint", hint));
    ex.details().ifPresent(details -> body.put("details", details));
    return ResponseEntity.status(ex.status()).body(body);
  }

  @ExceptionHandler(IllegalStateException.class)
  ResponseEntity<?> illegalState(IllegalStateException ex){
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", "BUSINESS_ERROR");
    body.put("message", Objects.toString(ex.getMessage(), "Business rule violation"));
    return ResponseEntity.badRequest().body(body);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> generic(Exception ex){
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", "INTERNAL_ERROR");
    body.put("message", Objects.toString(ex.getMessage(), ex.getClass().getSimpleName()));
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
  }
}
