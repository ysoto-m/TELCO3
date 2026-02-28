package com.telco3.agentui.agent;

import com.telco3.agentui.domain.*;
import com.telco3.agentui.domain.Entities.AgentVicidialCredentialEntity;
import com.telco3.agentui.domain.Entities.Role;
import com.telco3.agentui.domain.Entities.UserEntity;
import com.telco3.agentui.vicidial.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentControllerVicidialFlowTest {

  @Test
  void activeLeadWithoutSessionReturnsNotConnected() {
    AgentController controller = controllerWithMocks();
    Authentication auth = new UsernamePasswordAuthenticationToken("48373608", "n/a");

    VicidialServiceException ex = assertThrows(VicidialServiceException.class, () -> controller.active(auth));

    assertEquals("VICIDIAL_NOT_CONNECTED", ex.code());
    assertEquals(HttpStatus.CONFLICT, ex.status());
  }

  @Test
  void activeLeadNoLeadClassificationReturnsBusinessCode() {
    Fixture f = fixtureConnected();
    when(f.vicidialService.classifyActiveLead(eq("48373608"), any()))
        .thenReturn(VicidialService.ActiveLeadState.none(200, "NO_ACTIVE_LEAD", "no active lead"));

    Map<String, Object> response = f.controller.active(new UsernamePasswordAuthenticationToken("48373608", "n/a"));

    assertEquals(false, response.get("ok"));
    assertEquals("VICIDIAL_NO_ACTIVE_LEAD", response.get("code"));
  }

  @Test
  void activeLeadReloginRequiredReturnsConflict() {
    Fixture f = fixtureConnected();
    when(f.vicidialService.classifyActiveLead(eq("48373608"), any()))
        .thenReturn(VicidialService.ActiveLeadState.relogin(200, "<html>login</html>"));

    VicidialServiceException ex = assertThrows(VicidialServiceException.class,
        () -> f.controller.active(new UsernamePasswordAuthenticationToken("48373608", "n/a")));

    assertEquals("VICIDIAL_RELOGIN_REQUIRED", ex.code());
    assertEquals(HttpStatus.CONFLICT, ex.status());
  }

  private AgentController controllerWithMocks() {
    Fixture f = fixture();
    return f.controller;
  }

  private Fixture fixtureConnected() {
    Fixture f = fixture();
    AgentVicidialCredentialEntity session = new AgentVicidialCredentialEntity();
    session.connected = true;
    session.connectedPhoneLogin = "1001";
    session.connectedCampaign = "MANUAL01";
    session.serverIp = "10.0.0.1";
    session.sessionName = "session";
    session.agentLogId = 99L;
    when(f.agentVicidialCredentialRepository.findByAppUsername("48373608")).thenReturn(Optional.of(session));
    return f;
  }

  private Fixture fixture() {
    InteractionRepository interactions = mock(InteractionRepository.class);
    CustomerRepository customers = mock(CustomerRepository.class);
    CustomerPhoneRepository phones = mock(CustomerPhoneRepository.class);
    VicidialCredentialService credentialService = mock(VicidialCredentialService.class);
    AgentVicidialSessionService vicidialSessionService = mock(AgentVicidialSessionService.class);
    UserRepository userRepository = mock(UserRepository.class);
    AgentVicidialCredentialRepository agentVicidialCredentialRepository = mock(AgentVicidialCredentialRepository.class);
    VicidialDialRequestBuilder dialRequestBuilder = mock(VicidialDialRequestBuilder.class);
    VicidialDialResponseParser dialResponseParser = new VicidialDialResponseParser();
    VicidialService vicidialService = mock(VicidialService.class);

    UserEntity u = new UserEntity();
    u.username = "48373608";
    u.role = Role.AGENT;
    u.active = true;
    when(userRepository.findByUsernameAndActiveTrue("48373608")).thenReturn(Optional.of(u));

    AgentController controller = new AgentController(
        mock(VicidialClient.class), interactions, customers, phones, credentialService, vicidialSessionService,
        userRepository, agentVicidialCredentialRepository, dialRequestBuilder, dialResponseParser,
        new MockEnvironment(), vicidialService, false
    );
    return new Fixture(controller, agentVicidialCredentialRepository, vicidialService);
  }

  private record Fixture(AgentController controller,
                         AgentVicidialCredentialRepository agentVicidialCredentialRepository,
                         VicidialService vicidialService) {
  }
}
