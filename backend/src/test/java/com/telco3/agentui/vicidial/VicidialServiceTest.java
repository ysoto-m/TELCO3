package com.telco3.agentui.vicidial;

import com.telco3.agentui.agent.VicidialCredentialService;
import com.telco3.agentui.domain.Entities;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VicidialServiceTest {

  @Test
  void mapsDialMethodToMode() {
    VicidialService service = new VicidialService(mock(VicidialClient.class), new VicidialDialResponseParser(), mock(VicidialCredentialService.class), new MockEnvironment());
    assertEquals("manual", service.mapDialMethodToMode("MANUAL"));
    assertEquals("manual", service.mapDialMethodToMode("INBOUND_MAN"));
    assertEquals("predictive", service.mapDialMethodToMode("PREDICTIVE"));
    assertEquals("predictive", service.mapDialMethodToMode("ADAPT_PREDICTIVE"));
  }

  @Test
  void dialNextWithoutLeadReturnsDialingClassification() {
    VicidialClient client = mock(VicidialClient.class);
    when(client.activeLeadSafe("agent1"))
        .thenReturn(new VicidialClient.ActiveLeadResult(VicidialClient.ActiveLeadOutcome.NO_ACTIVE_LEAD, "", 200));
    VicidialCredentialService credentialService = mock(VicidialCredentialService.class);
    VicidialService service = new VicidialService(client, new VicidialDialResponseParser(), credentialService, new MockEnvironment());

    var result = service.dialNextWithLeadRetry("agent1", "M123456789", "M123456789", null);

    assertEquals("DIALING_NO_LEAD_YET", result.classification());
    assertNull(result.leadId());
    verify(credentialService).updateDialRuntime("agent1", "DIALING", "M123456789", null);
  }

  @Test
  void classifyActiveLeadReturnsDialingWhenSessionInDialState() {
    VicidialService service = new VicidialService(mock(VicidialClient.class), new VicidialDialResponseParser(), mock(VicidialCredentialService.class), new MockEnvironment());
    Entities.AgentVicidialCredentialEntity session = new Entities.AgentVicidialCredentialEntity();
    session.currentDialStatus = "DIALING";
    session.currentCallId = "M777";

    var state = service.classifyActiveLead("agent1", session);

    assertTrue(state.dialing());
    assertFalse(state.hasLead());
    assertEquals("M777", state.callId());
  }

  @Test
  void resolveModeForCampaignPersistsMode() {
    VicidialClient client = mock(VicidialClient.class);
    when(client.campaignDialMethod("MAN2")).thenReturn(Optional.of("MANUAL"));
    VicidialCredentialService credentialService = mock(VicidialCredentialService.class);
    VicidialService service = new VicidialService(client, new VicidialDialResponseParser(), credentialService, new MockEnvironment());

    String mode = service.resolveModeForCampaign("agent1", "MAN2");

    assertEquals("manual", mode);
    verify(credentialService).updateSessionMode("agent1", "manual");
  }
}
