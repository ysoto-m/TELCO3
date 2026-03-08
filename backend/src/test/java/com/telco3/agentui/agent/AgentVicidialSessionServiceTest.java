package com.telco3.agentui.agent;

import com.telco3.agentui.vicidial.VicidialClient;
import com.telco3.agentui.vicidial.VicidialCampaignParser;
import com.telco3.agentui.vicidial.VicidialDiagnosticsService;
import com.telco3.agentui.vicidial.VicidialService;
import com.telco3.agentui.vicidial.VicidialServiceException;
import com.telco3.agentui.vicidial.VicidialSessionClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentVicidialSessionServiceTest {

  @Mock
  private VicidialService vicidialService;

  @Test
  void listCampaignsReturns400WhenAgentCredentialsMissingOutsideDev() {
    VicidialSessionClient sessionClient = mock(VicidialSessionClient.class);
    VicidialClient vicidialClient = mock(VicidialClient.class);
    VicidialCredentialService credentialService = mock(VicidialCredentialService.class);
    VicidialCampaignParser campaignParser = new VicidialCampaignParser();
    VicidialDiagnosticsService diagnosticsService = mock(VicidialDiagnosticsService.class);
    Environment env = mock(Environment.class);
    when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});
    when(env.getProperty(eq("APP_ENV"), anyString())).thenReturn("prod");

    AgentVicidialSessionService service = new AgentVicidialSessionService(sessionClient, vicidialClient, credentialService, campaignParser, diagnosticsService, env, vicidialService);

    when(sessionClient.connectPhone("48373608", "1001", "anexo_1001")).thenReturn("SUCCESS");
    service.connectPhone("48373608", "1001");

    when(credentialService.resolveAgentCredentials("48373608"))
        .thenReturn(new VicidialCredentialService.AgentVicidialCredentials("48373608", null, false, null));

    VicidialServiceException ex = assertThrows(VicidialServiceException.class, () -> service.listCampaigns("48373608"));
    assertEquals("VICIDIAL_AGENT_CREDENTIALS_MISSING", ex.code());
    assertEquals(400, ex.status().value());
    verifyNoInteractions(vicidialClient);
  }

  @Test
  void connectCampaignPersistsRecordingExtenAsDialExten() {
    VicidialSessionClient sessionClient = mock(VicidialSessionClient.class);
    VicidialClient vicidialClient = mock(VicidialClient.class);
    VicidialCredentialService credentialService = mock(VicidialCredentialService.class);
    VicidialCampaignParser campaignParser = new VicidialCampaignParser();
    VicidialDiagnosticsService diagnosticsService = mock(VicidialDiagnosticsService.class);
    Environment env = mock(Environment.class);
    when(env.getActiveProfiles()).thenReturn(new String[]{"dev"});
    when(env.getProperty(eq("APP_ENV"), anyString())).thenReturn("dev");

    AgentVicidialSessionService service = new AgentVicidialSessionService(
        sessionClient, vicidialClient, credentialService, campaignParser, diagnosticsService, env, vicidialService);

    when(sessionClient.connectPhone("agent1", "1001", "anexo_1001")).thenReturn("SUCCESS");
    service.connectPhone("agent1", "1001");

    when(credentialService.resolveAgentPass("agent1")).thenReturn(java.util.Optional.of("secret"));
    when(vicidialClient.connectToCampaign(eq("agent1"), eq("secret"), eq("1001"), eq("anexo_1001"), eq("MANUAL2"), any(), any()))
        .thenReturn(new VicidialClient.VicidialHttpResult(200, """
            <script>
            var session_name = '1772928333_100113710146';
            var server_ip = '172.17.248.220';
            var conf_exten = '8600051';
            var extension = '1001';
            var recording_exten = '8309';
            var protocol = 'SIP';
            var agent_log_id = '416';
            </script>
            """));
    when(vicidialService.resolveModeForCampaign("agent1", "MANUAL2")).thenReturn("manual");

    service.connectCampaign("agent1", "MANUAL2", true);

    ArgumentCaptor<String> extensionCaptor = ArgumentCaptor.forClass(String.class);
    verify(credentialService).updateRuntimeSession(
        eq("agent1"),
        anyString(),
        anyString(),
        anyString(),
        extensionCaptor.capture(),
        eq("SIP"),
        eq(416L)
    );
    assertEquals("8309", extensionCaptor.getValue());
    verify(credentialService).saveLastSelection("agent1", "1001", "MANUAL2", true);
    verify(credentialService).markConnected("agent1", "1001", "MANUAL2", "manual");
  }

  @Test
  void connectCampaignExtractsManualDialListIdFromHiddenInput() {
    VicidialSessionClient sessionClient = mock(VicidialSessionClient.class);
    VicidialClient vicidialClient = mock(VicidialClient.class);
    VicidialCredentialService credentialService = mock(VicidialCredentialService.class);
    VicidialCampaignParser campaignParser = new VicidialCampaignParser();
    VicidialDiagnosticsService diagnosticsService = mock(VicidialDiagnosticsService.class);
    Environment env = mock(Environment.class);
    when(env.getActiveProfiles()).thenReturn(new String[]{"dev"});
    when(env.getProperty(eq("APP_ENV"), anyString())).thenReturn("dev");

    AgentVicidialSessionService service = new AgentVicidialSessionService(
        sessionClient, vicidialClient, credentialService, campaignParser, diagnosticsService, env, vicidialService);

    when(sessionClient.connectPhone("agent1", "1001", "anexo_1001")).thenReturn("SUCCESS");
    service.connectPhone("agent1", "1001");

    when(credentialService.resolveAgentPass("agent1")).thenReturn(java.util.Optional.of("secret"));
    when(vicidialClient.connectToCampaign(eq("agent1"), eq("secret"), eq("1001"), eq("anexo_1001"), eq("MANUAL2"), any(), any()))
        .thenReturn(new VicidialClient.VicidialHttpResult(200, """
            <script>
            var session_name = '1772928333_100113710146';
            var server_ip = '172.17.248.220';
            var conf_exten = '8600051';
            var recording_exten = '8309';
            var protocol = 'SIP';
            var agent_log_id = '416';
            </script>
            <input type="hidden" name="mdnLisT_id" value="998" />
            """));
    when(vicidialService.resolveModeForCampaign("agent1", "MANUAL2")).thenReturn("manual");

    service.connectCampaign("agent1", "MANUAL2", true);

    assertTrue(service.currentManualDialListId("agent1").isPresent());
    assertEquals("998", service.currentManualDialListId("agent1").orElse(""));
  }
}
