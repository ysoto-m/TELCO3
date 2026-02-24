package com.telco3.agentui.agent;

import com.telco3.agentui.domain.*;
import com.telco3.agentui.vicidial.VicidialClient;
import com.telco3.agentui.vicidial.VicidialServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AgentController.class)
@AutoConfigureMockMvc(addFilters = false)
class AgentVicidialStatusControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockBean
  private VicidialClient vicidialClient;

  @MockBean
  private InteractionRepository interactionRepository;

  @MockBean
  private CustomerRepository customerRepository;

  @MockBean
  private CustomerPhoneRepository customerPhoneRepository;

  @MockBean
  private VicidialCredentialService vicidialCredentialService;

  @MockBean
  private AgentVicidialSessionService vicidialSessionService;

  @MockBean
  private UserRepository userRepository;

  @MockBean
  private AgentVicidialCredentialRepository agentVicidialCredentialRepository;

  @Test
  @WithMockUser(username = "agent1")
  void statusReturns503WhenBaseUrlMissing() throws Exception {
    when(vicidialSessionService.status("agent1"))
        .thenThrow(new VicidialServiceException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "VICIDIAL_CONFIG_MISSING",
            "VICIDIAL_BASE_URL no está configurado. Configure VICIDIAL_BASE_URL en variables de entorno (por ejemplo: http://172.17.248.220).",
            "Revisar docker-compose/.env y printenv dentro del contenedor",
            null
        ));

    mockMvc.perform(get("/api/agent/vicidial/status"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.ok").value(false))
        .andExpect(jsonPath("$.code").value("VICIDIAL_CONFIG_MISSING"));
  }

  @Test
  @WithMockUser(username = "agent1")
  void statusReturns503WhenVicidialUnreachable() throws Exception {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("cause", "ConnectException");
    details.put("error", "Connection refused");

    when(vicidialSessionService.status("agent1"))
        .thenThrow(new VicidialServiceException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "VICIDIAL_UNREACHABLE",
            "No se pudo conectar a Vicidial usando VICIDIAL_BASE_URL=http://172.17.248.220. Verifique conectividad y endpoint.",
            null,
            details
        ));

    mockMvc.perform(get("/api/agent/vicidial/status"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.ok").value(false))
        .andExpect(jsonPath("$.code").value("VICIDIAL_UNREACHABLE"))
        .andExpect(jsonPath("$.details.cause").value("ConnectException"));
  }

  @Test
  @WithMockUser(username = "agent1")
  void statusReturns200WhenVicidialIsReady() throws Exception {
    when(vicidialSessionService.status("agent1"))
        .thenReturn(Map.of("ok", true, "agentUser", "agent1", "phoneConnected", false, "typingEnabled", false));

    mockMvc.perform(get("/api/agent/vicidial/status"))
        .andExpect(status().isOk())

        .andExpect(jsonPath("$.agentUser").value("agent1"))
        .andExpect(jsonPath("$.apiPass").doesNotExist())
        .andExpect(jsonPath("$.pass").doesNotExist());
  }

  @Test
  @WithMockUser(username = "agent1")
  void activeLeadWithoutConnectedSessionReturnsVicidialNotConnected() throws Exception {
    var user = new com.telco3.agentui.domain.Entities.UserEntity();
    user.username = "agent1";
    when(userRepository.findByUsernameAndActiveTrue("agent1")).thenReturn(Optional.of(user));
    when(agentVicidialCredentialRepository.findByAppUsername("agent1")).thenReturn(Optional.empty());

    mockMvc.perform(get("/api/agent/active-lead"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VICIDIAL_NOT_CONNECTED"));
  }

  @Test
  @WithMockUser(username = "agent1")
  void activeLeadWithoutLeadReturnsNoActiveLeadBusinessPayload() throws Exception {
    var user = new com.telco3.agentui.domain.Entities.UserEntity();
    user.username = "agent1";
    var session = new com.telco3.agentui.domain.Entities.AgentVicidialCredentialEntity();
    session.connected = true;
    session.connectedPhoneLogin = "1001";
    session.connectedCampaign = "IVR";

    when(userRepository.findByUsernameAndActiveTrue("agent1")).thenReturn(Optional.of(user));
    when(agentVicidialCredentialRepository.findByAppUsername("agent1")).thenReturn(Optional.of(session));
    when(vicidialClient.activeLeadSafe("agent1"))
        .thenReturn(new VicidialClient.ActiveLeadResult(VicidialClient.ActiveLeadOutcome.NO_ACTIVE_LEAD, "No active lead", 200));

    mockMvc.perform(get("/api/agent/active-lead"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ok").value(false))
        .andExpect(jsonPath("$.code").value("VICIDIAL_NO_ACTIVE_LEAD"));
  }

  @Test
  @WithMockUser(username = "agent1")
  void activeLeadWhenVicidialReturnsLoginPageRequiresRelogin() throws Exception {
    var user = new com.telco3.agentui.domain.Entities.UserEntity();
    user.username = "agent1";
    var session = new com.telco3.agentui.domain.Entities.AgentVicidialCredentialEntity();
    session.connected = true;
    session.connectedPhoneLogin = "1001";
    session.connectedCampaign = "IVR";

    when(userRepository.findByUsernameAndActiveTrue("agent1")).thenReturn(Optional.of(user));
    when(agentVicidialCredentialRepository.findByAppUsername("agent1")).thenReturn(Optional.of(session));
    when(vicidialClient.activeLeadSafe("agent1"))
        .thenReturn(new VicidialClient.ActiveLeadResult(VicidialClient.ActiveLeadOutcome.RELOGIN_REQUIRED, "<html>login</html>", 200));

    mockMvc.perform(get("/api/agent/active-lead"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VICIDIAL_RELOGIN_REQUIRED"));
  }

  @Test
  @WithMockUser(username = "agent1")
  void manualNextWithoutConnectedSessionReturnsVicidialNotConnected() throws Exception {
    var user = new com.telco3.agentui.domain.Entities.UserEntity();
    user.username = "agent1";
    when(userRepository.findByUsernameAndActiveTrue("agent1")).thenReturn(Optional.of(user));
    when(agentVicidialCredentialRepository.findByAppUsername("agent1")).thenReturn(Optional.empty());

    mockMvc.perform(post("/api/agent/vicidial/manual/next")
            .contentType("application/json")
            .content("{\"campaignId\":\"Manual\",\"mode\":\"manual\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VICIDIAL_NOT_CONNECTED"));
  }

  @Test
  @WithMockUser(username = "agent1")
  void manualNextWhenAgentNotLoggedInRequiresRelogin() throws Exception {
    var user = new com.telco3.agentui.domain.Entities.UserEntity();
    user.username = "agent1";
    var session = new com.telco3.agentui.domain.Entities.AgentVicidialCredentialEntity();
    session.connected = true;
    session.connectedPhoneLogin = "1001";
    session.connectedCampaign = "Manual";

    when(userRepository.findByUsernameAndActiveTrue("agent1")).thenReturn(Optional.of(user));
    when(agentVicidialCredentialRepository.findByAppUsername("agent1")).thenReturn(Optional.of(session));
    when(vicidialClient.externalDialManualNext("agent1"))
        .thenReturn(new VicidialClient.VicidialHttpResult(200, "ERROR: agent_user is not logged in"));

    mockMvc.perform(post("/api/agent/vicidial/manual/next")
            .contentType("application/json")
            .content("{\"campaignId\":\"Manual\",\"mode\":\"manual\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VICIDIAL_RELOGIN_REQUIRED"));
  }

  @Test
  @WithMockUser(username = "agent1")
  void manualNextPermissionDeniedMaps403() throws Exception {
    var user = new com.telco3.agentui.domain.Entities.UserEntity();
    user.username = "agent1";
    var session = new com.telco3.agentui.domain.Entities.AgentVicidialCredentialEntity();
    session.connected = true;
    session.connectedPhoneLogin = "1001";
    session.connectedCampaign = "Manual";

    when(userRepository.findByUsernameAndActiveTrue("agent1")).thenReturn(Optional.of(user));
    when(agentVicidialCredentialRepository.findByAppUsername("agent1")).thenReturn(Optional.of(session));
    when(vicidialClient.externalDialManualNext("agent1"))
        .thenReturn(new VicidialClient.VicidialHttpResult(200, "ERROR: auth USER DOES NOT HAVE PERMISSION TO PERFORM FUNCTION"));

    mockMvc.perform(post("/api/agent/vicidial/manual/next")
            .contentType("application/json")
            .content("{\"campaignId\":\"Manual\",\"mode\":\"manual\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("VICIDIAL_PERMISSION_DENIED"));
  }

  @Test
  @WithMockUser(username = "agent1")
  void contextWithoutConnectedSessionReturnsVicidialNotConnected() throws Exception {
    var user = new com.telco3.agentui.domain.Entities.UserEntity();
    user.username = "agent1";
    when(userRepository.findByUsernameAndActiveTrue("agent1")).thenReturn(Optional.of(user));
    when(agentVicidialCredentialRepository.findByAppUsername("agent1")).thenReturn(Optional.empty());

    mockMvc.perform(get("/api/agent/context").param("mode", "predictive"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VICIDIAL_NOT_CONNECTED"));
  }

}
