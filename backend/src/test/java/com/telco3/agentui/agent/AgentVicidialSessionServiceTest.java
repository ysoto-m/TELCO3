package com.telco3.agentui.agent;

import com.telco3.agentui.vicidial.VicidialClient;
import com.telco3.agentui.vicidial.VicidialCampaignParser;
import com.telco3.agentui.vicidial.VicidialDiagnosticsService;
import com.telco3.agentui.vicidial.VicidialServiceException;
import com.telco3.agentui.vicidial.VicidialSessionClient;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AgentVicidialSessionServiceTest {

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

    AgentVicidialSessionService service = new AgentVicidialSessionService(sessionClient, vicidialClient, credentialService, campaignParser, diagnosticsService, env);

    when(sessionClient.connectPhone("48373608", "1001", "anexo_1001")).thenReturn("SUCCESS");
    service.connectPhone("48373608", "1001");

    when(credentialService.resolveAgentCredentials("48373608"))
        .thenReturn(new VicidialCredentialService.AgentVicidialCredentials("48373608", null, false, null));

    VicidialServiceException ex = assertThrows(VicidialServiceException.class, () -> service.listCampaigns("48373608"));
    assertEquals("VICIDIAL_AGENT_CREDENTIALS_MISSING", ex.code());
    assertEquals(400, ex.status().value());
    verifyNoInteractions(vicidialClient);
  }
}
