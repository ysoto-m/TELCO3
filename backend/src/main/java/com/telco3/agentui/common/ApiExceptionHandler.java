package com.telco3.agentui.common;

import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<?> validation(MethodArgumentNotValidException ex){
    return ResponseEntity.badRequest().body(Map.of("error","VALIDATION_ERROR","message",ex.getBindingResult().getFieldError().getDefaultMessage()));
  }
  @ExceptionHandler(Exception.class)
  ResponseEntity<?> generic(Exception ex){
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error","INTERNAL_ERROR","message",ex.getMessage()));
  }
}
