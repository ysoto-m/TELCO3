package com.telco3.agentui.agent;

import com.telco3.agentui.domain.AgentVicidialCredentialRepository;
import com.telco3.agentui.domain.Entities;
import com.telco3.agentui.domain.UserRepository;
import com.telco3.agentui.vicidial.VicidialServiceException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class AgentSessionGuardService {
  private final UserRepository userRepository;
  private final AgentVicidialCredentialRepository agentVicidialCredentialRepository;

  public AgentSessionGuardService(UserRepository userRepository, AgentVicidialCredentialRepository agentVicidialCredentialRepository) {
    this.userRepository = userRepository;
    this.agentVicidialCredentialRepository = agentVicidialCredentialRepository;
  }

  public String requireAuth(Authentication auth) {
    if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
      throw new RuntimeException("Unauthorized");
    }
    return auth.getName();
  }

  public void ensureAgentExists(String agentUser) {
    if (userRepository.findByUsernameAndActiveTrue(agentUser).isEmpty()) {
      throw new VicidialServiceException(HttpStatus.NOT_FOUND,
          "AGENT_NOT_FOUND",
          "No existe un agente activo para el usuario autenticado.");
    }
  }

  public Entities.AgentVicidialCredentialEntity requireConnectedSession(String agentUser) {
    var session = agentVicidialCredentialRepository.findByAppUsername(agentUser)
        .orElseThrow(() -> new VicidialServiceException(
            HttpStatus.CONFLICT,
            "VICIDIAL_NOT_CONNECTED",
            "El agente no tiene sesion Vicidial conectada.",
            "conecte anexo/campana primero",
            null
        ));
    if (!session.connected) {
      throw new VicidialServiceException(HttpStatus.CONFLICT,
          "VICIDIAL_NOT_CONNECTED",
          "El agente no tiene sesion Vicidial conectada.",
          "conecte anexo/campana primero",
          null);
    }
    return session;
  }

  public void requireSessionField(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new VicidialServiceException(HttpStatus.CONFLICT,
          "VICIDIAL_SESSION_INCOMPLETE",
          "La sesion Vicidial esta incompleta.",
          "Falta el campo requerido: " + field,
          Map.of("missingField", field));
    }
  }
}
