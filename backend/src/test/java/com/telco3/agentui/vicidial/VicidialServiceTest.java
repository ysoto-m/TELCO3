package com.telco3.agentui.vicidial;

import com.telco3.agentui.agent.VicidialCredentialService;
import com.telco3.agentui.domain.Entities;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VicidialServiceTest {

  @Test
  void mapsDialMethodToMode() {
    VicidialService service = new VicidialService(mock(VicidialClient.class), new VicidialDialResponseParser(),
        mock(VicidialCredentialService.class), new MockEnvironment(), mock(VicidialRuntimeDataSourceFactory.class));
    assertEquals("manual", service.mapDialMethodToMode("MANUAL"));
    assertEquals("manual", service.mapDialMethodToMode("INBOUND_MAN"));
    assertEquals("predictive", service.mapDialMethodToMode("PREDICTIVE"));
  }

  @Test
  void dialNextWithoutLeadReturnsDialingClassification() {
    VicidialClient client = mock(VicidialClient.class);
    when(client.activeLeadSafe("agent1"))
        .thenReturn(new VicidialClient.ActiveLeadResult(VicidialClient.ActiveLeadOutcome.NO_ACTIVE_LEAD, "", 200));
    VicidialCredentialService credentialService = mock(VicidialCredentialService.class);
    VicidialService service = new VicidialService(client, new VicidialDialResponseParser(), credentialService,
        new MockEnvironment(), mock(VicidialRuntimeDataSourceFactory.class));

    var result = service.dialNextWithLeadRetry("agent1", "M123456789", "M123456789", null);

    assertEquals("DIALING_NO_LEAD_YET", result.classification());
    assertNull(result.leadId());
    verify(credentialService).updateDialRuntime("agent1", "DIALING", "M123456789", null);
  }

  @Test
  void classifyActiveLeadReturnsDialingWhenSessionInDialState() {
    VicidialService service = new VicidialService(mock(VicidialClient.class), new VicidialDialResponseParser(),
        mock(VicidialCredentialService.class), new MockEnvironment(), mock(VicidialRuntimeDataSourceFactory.class));
    Entities.AgentVicidialCredentialEntity session = new Entities.AgentVicidialCredentialEntity();
    session.currentDialStatus = "DIALING";
    session.currentCallId = "M777";

    var state = service.classifyActiveLead("agent1", session);

    assertTrue(state.dialing());
    assertFalse(state.hasLead());
    assertEquals("M777", state.callId());
  }
}
