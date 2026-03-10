package com.telco3.agentui.vicidial;

import com.telco3.agentui.agent.VicidialCredentialService;
import com.telco3.agentui.vicidial.domain.AgentVicidialCredentialEntity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

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
    when(credentialService.resolveAgentPass("agent1")).thenReturn(java.util.Optional.empty());
    VicidialService service = new VicidialService(client, new VicidialDialResponseParser(), credentialService,
        new MockEnvironment(), mock(VicidialRuntimeDataSourceFactory.class));

    AgentVicidialCredentialEntity session = new AgentVicidialCredentialEntity();
    session.agentUser = "agent1";
    var result = service.dialNextWithLeadRetry("agent1", session, "M123456789", "M123456789", null);

    assertEquals("DIALING_NO_LEAD_YET", result.classification());
    assertNull(result.leadId());
    verify(credentialService).updateDialRuntime("agent1", "DIALING", "M123456789", null);
  }

  @Test
  void classifyActiveLeadReturnsDialingWhenSessionInDialState() {
    VicidialClient client = mock(VicidialClient.class);
    when(client.activeLeadSafe("agent1"))
        .thenReturn(new VicidialClient.ActiveLeadResult(VicidialClient.ActiveLeadOutcome.NO_ACTIVE_LEAD, "", 200));
    VicidialCredentialService credentialService = mock(VicidialCredentialService.class);
    when(credentialService.resolveAgentPass("agent1")).thenReturn(java.util.Optional.empty());
    VicidialService service = new VicidialService(client, new VicidialDialResponseParser(),
        credentialService, new MockEnvironment(), mock(VicidialRuntimeDataSourceFactory.class));
    AgentVicidialCredentialEntity session = new AgentVicidialCredentialEntity();
    session.currentDialStatus = "DIALING";
    session.currentCallId = "M777";

    var state = service.classifyActiveLead("agent1", session);

    assertTrue(state.dialing());
    assertFalse(state.hasLead());
    assertEquals("M777", state.callId());
  }

  @Test
  void followUpManualDialResolvesUniqueIdAndChannelAfterIncall() {
    VicidialClient client = mock(VicidialClient.class);
    VicidialCredentialService credentialService = mock(VicidialCredentialService.class);
    VicidialRuntimeDataSourceFactory dataSourceFactory = mock(VicidialRuntimeDataSourceFactory.class);
    MockEnvironment env = new MockEnvironment();
    env.setProperty("app.vicidial.dial.default-recording-exten", "8309");
    VicidialService service = new VicidialService(client, new VicidialDialResponseParser(), credentialService,
        env, dataSourceFactory);

    AgentVicidialCredentialEntity session = new AgentVicidialCredentialEntity();
    session.agentUser = "agent1";
    session.connectedPhoneLogin = "1001";
    session.connectedCampaign = "MANUAL01";
    session.sessionName = "sess";
    session.serverIp = "10.10.10.10";
    session.confExten = "8600051";
    session.protocol = "SIP";
    session.extension = "1001";
    session.agentLogId = 99L;

    when(client.vdcScriptDisplay(eq("agent1"), anyMap()))
        .thenReturn(new VicidialClient.VicidialHttpResult(200, "OK"));
    when(client.confExtenCheck(eq("agent1"), anyMap()))
        .thenReturn(new VicidialClient.VicidialHttpResult(200, "Logged-in: INCALL\nStatus: INCALL\ncall_id: M3071629250000001166\nlead_id: 1166"));
    when(client.manualDialLookCall(eq("agent1"), anyMap()))
        .thenReturn(new VicidialClient.VicidialHttpResult(200, "1772918965.46\nSIP/trunk220_24-00000016\n"));
    when(client.monitorConf(eq("agent1"), anyMap()))
        .thenReturn(new VicidialClient.VicidialHttpResult(200, "MONITOR_OK"));
    when(client.manualDialLogCall(eq("agent1"), anyMap(), eq("start")))
        .thenReturn(new VicidialClient.VicidialHttpResult(200, "LOG_START_OK"));

    var parsed = new VicidialDialResponseParser.DetailedParsedDialResponse(
        VicidialDialResponseParser.DialClassification.SUCCESS,
        true,
        "M3071629250000001166",
        1166L,
        "970222277",
        "106",
        "INCALL",
        Map.of()
    );

    var result = service.followUpManualDial("agent1", "secret", session, "MANUAL01", parsed, "970222277");

    assertTrue(result.incallConfirmed());
    assertEquals(1166L, result.leadId());
    assertEquals("1772918965.46", result.uniqueId());
    assertEquals("SIP/trunk220_24-00000016", result.channel());
    ArgumentCaptor<Map<String, String>> monitorPayloadCaptor = ArgumentCaptor.forClass(Map.class);
    verify(client).monitorConf(eq("agent1"), monitorPayloadCaptor.capture());
    assertEquals("Local/58600051@default", monitorPayloadCaptor.getValue().get("channel"));
    assertEquals("8309", monitorPayloadCaptor.getValue().get("exten"));
    verify(client).manualDialLogCall(eq("agent1"), anyMap(), eq("start"));
    verify(credentialService).updateDialRuntime("agent1", "ACTIVE", "M3071629250000001166", 1166L);
  }

  @Test
  void followUpManualDialDoesNotConfirmWhenOutboundChannelLoopsToAgent() {
    VicidialClient client = mock(VicidialClient.class);
    VicidialCredentialService credentialService = mock(VicidialCredentialService.class);
    VicidialRuntimeDataSourceFactory dataSourceFactory = mock(VicidialRuntimeDataSourceFactory.class);
    VicidialService service = new VicidialService(client, new VicidialDialResponseParser(), credentialService,
        new MockEnvironment(), dataSourceFactory);

    AgentVicidialCredentialEntity session = new AgentVicidialCredentialEntity();
    session.agentUser = "agent1";
    session.connectedPhoneLogin = "1001";
    session.connectedCampaign = "MANUAL01";
    session.sessionName = "sess";
    session.serverIp = "10.10.10.10";
    session.confExten = "8600051";
    session.protocol = "SIP";
    session.extension = "1001";
    session.agentLogId = 99L;

    when(client.vdcScriptDisplay(eq("agent1"), anyMap()))
        .thenReturn(new VicidialClient.VicidialHttpResult(200, "OK"));
    when(client.confExtenCheck(eq("agent1"), anyMap()))
        .thenReturn(new VicidialClient.VicidialHttpResult(200, "Logged-in: INCALL\nStatus: INCALL\ncall_id: M3071629250000001166\nlead_id: 1166"));
    when(client.manualDialLookCall(eq("agent1"), anyMap()))
        .thenReturn(new VicidialClient.VicidialHttpResult(200, "1772918965.46\nSIP/1001-0000002d\n"));
    when(credentialService.resolveAgentPass("agent1")).thenReturn(java.util.Optional.empty());
    when(client.activeLeadSafe("agent1"))
        .thenReturn(new VicidialClient.ActiveLeadResult(VicidialClient.ActiveLeadOutcome.NO_ACTIVE_LEAD, "", 200));

    var parsed = new VicidialDialResponseParser.DetailedParsedDialResponse(
        VicidialDialResponseParser.DialClassification.SUCCESS,
        true,
        "M3071629250000001166",
        1166L,
        "970222277",
        "106",
        "INCALL",
        Map.of()
    );

    var result = service.followUpManualDial("agent1", "secret", session, "MANUAL01", parsed, "970222277");

    assertFalse(result.incallConfirmed());
    assertEquals("DIALING_PENDING_CONFIRMATION", result.classification());
    assertEquals("AGENT_LOOP_DETECTED", result.details().get("outboundLeg"));
    verify(client, never()).monitorConf(eq("agent1"), anyMap());
    verify(client, never()).manualDialLogCall(eq("agent1"), anyMap(), eq("start"));
  }

  @Test
  void followUpManualDialDoesNotConfirmWhenOnlyConferenceLocalChannelExists() {
    VicidialClient client = mock(VicidialClient.class);
    VicidialCredentialService credentialService = mock(VicidialCredentialService.class);
    VicidialRuntimeDataSourceFactory dataSourceFactory = mock(VicidialRuntimeDataSourceFactory.class);
    VicidialService service = new VicidialService(client, new VicidialDialResponseParser(), credentialService,
        new MockEnvironment(), dataSourceFactory);

    AgentVicidialCredentialEntity session = new AgentVicidialCredentialEntity();
    session.agentUser = "agent1";
    session.connectedPhoneLogin = "1001";
    session.connectedCampaign = "MANUAL01";
    session.sessionName = "sess";
    session.serverIp = "10.10.10.10";
    session.confExten = "8600051";
    session.protocol = "SIP";
    session.extension = "8309";
    session.agentLogId = 99L;

    when(client.vdcScriptDisplay(eq("agent1"), anyMap()))
        .thenReturn(new VicidialClient.VicidialHttpResult(200, "OK"));
    when(client.confExtenCheck(eq("agent1"), anyMap()))
        .thenReturn(new VicidialClient.VicidialHttpResult(200, "Logged-in: INCALL\nStatus: INCALL\ncall_id: M3071629250000001166\nlead_id: 1166"));
    when(client.manualDialLookCall(eq("agent1"), anyMap()))
        .thenReturn(new VicidialClient.VicidialHttpResult(200, "1772918965.46\nLocal/58600051@default\n"));
    when(credentialService.resolveAgentPass("agent1")).thenReturn(java.util.Optional.empty());
    when(client.activeLeadSafe("agent1"))
        .thenReturn(new VicidialClient.ActiveLeadResult(VicidialClient.ActiveLeadOutcome.NO_ACTIVE_LEAD, "", 200));

    var parsed = new VicidialDialResponseParser.DetailedParsedDialResponse(
        VicidialDialResponseParser.DialClassification.SUCCESS,
        true,
        "M3071629250000001166",
        1166L,
        "970222277",
        "106",
        "INCALL",
        Map.of()
    );

    var result = service.followUpManualDial("agent1", "secret", session, "MANUAL01", parsed, "970222277");

    assertFalse(result.incallConfirmed());
    assertEquals("DIALING_PENDING_CONFIRMATION", result.classification());
    assertEquals("OUTBOUND_CHANNEL_NOT_DETECTED", result.details().get("outboundLeg"));
    verify(client, never()).monitorConf(eq("agent1"), anyMap());
    verify(client, never()).manualDialLogCall(eq("agent1"), anyMap(), eq("start"));
  }

  @Test
  void followUpManualDialDoesNotConfirmWhenOnlyMonitorExtensionChannelExists() {
    VicidialClient client = mock(VicidialClient.class);
    VicidialCredentialService credentialService = mock(VicidialCredentialService.class);
    VicidialRuntimeDataSourceFactory dataSourceFactory = mock(VicidialRuntimeDataSourceFactory.class);
    MockEnvironment env = new MockEnvironment();
    env.setProperty("app.vicidial.dial.default-recording-exten", "8309");
    VicidialService service = new VicidialService(client, new VicidialDialResponseParser(), credentialService,
        env, dataSourceFactory);

    AgentVicidialCredentialEntity session = new AgentVicidialCredentialEntity();
    session.agentUser = "agent1";
    session.connectedPhoneLogin = "1001";
    session.connectedCampaign = "MANUAL01";
    session.sessionName = "sess";
    session.serverIp = "10.10.10.10";
    session.confExten = "8600051";
    session.protocol = "SIP";
    session.extension = "8309";
    session.agentLogId = 99L;

    when(client.vdcScriptDisplay(eq("agent1"), anyMap()))
        .thenReturn(new VicidialClient.VicidialHttpResult(200, "OK"));
    when(client.confExtenCheck(eq("agent1"), anyMap()))
        .thenReturn(new VicidialClient.VicidialHttpResult(200, "Logged-in: INCALL\nStatus: INCALL\ncall_id: M3071629250000001166\nlead_id: 1166"));
    when(client.manualDialLookCall(eq("agent1"), anyMap()))
        .thenReturn(new VicidialClient.VicidialHttpResult(200, "1772918965.46\nSIP/8309-0000002e\n"));
    when(credentialService.resolveAgentPass("agent1")).thenReturn(java.util.Optional.empty());
    when(client.activeLeadSafe("agent1"))
        .thenReturn(new VicidialClient.ActiveLeadResult(VicidialClient.ActiveLeadOutcome.NO_ACTIVE_LEAD, "", 200));

    var parsed = new VicidialDialResponseParser.DetailedParsedDialResponse(
        VicidialDialResponseParser.DialClassification.SUCCESS,
        true,
        "M3071629250000001166",
        1166L,
        "970222277",
        "106",
        "INCALL",
        Map.of()
    );

    var result = service.followUpManualDial("agent1", "secret", session, "MANUAL01", parsed, "970222277");

    assertFalse(result.incallConfirmed());
    assertEquals("DIALING_PENDING_CONFIRMATION", result.classification());
    assertEquals("OUTBOUND_CHANNEL_NOT_DETECTED", result.details().get("outboundLeg"));
    verify(client, never()).monitorConf(eq("agent1"), anyMap());
    verify(client, never()).manualDialLogCall(eq("agent1"), anyMap(), eq("start"));
  }

  @Test
  void followUpManualDialPrefersOutboundChannelWhenConfCheckReturnsLocalChannel() {
    VicidialClient client = mock(VicidialClient.class);
    VicidialCredentialService credentialService = mock(VicidialCredentialService.class);
    VicidialRuntimeDataSourceFactory dataSourceFactory = mock(VicidialRuntimeDataSourceFactory.class);
    MockEnvironment env = new MockEnvironment();
    env.setProperty("app.vicidial.dial.default-recording-exten", "8309");
    VicidialService service = new VicidialService(client, new VicidialDialResponseParser(), credentialService,
        env, dataSourceFactory);

    AgentVicidialCredentialEntity session = new AgentVicidialCredentialEntity();
    session.agentUser = "agent1";
    session.connectedPhoneLogin = "1001";
    session.connectedCampaign = "MANUAL01";
    session.sessionName = "sess";
    session.serverIp = "10.10.10.10";
    session.confExten = "8600051";
    session.protocol = "SIP";
    session.extension = "8309";
    session.agentLogId = 99L;

    when(client.vdcScriptDisplay(eq("agent1"), anyMap()))
        .thenReturn(new VicidialClient.VicidialHttpResult(200, "OK"));
    when(client.confExtenCheck(eq("agent1"), anyMap()))
        .thenReturn(new VicidialClient.VicidialHttpResult(
            200,
            "Logged-in: INCALL\nStatus: INCALL\ncall_id: M3071629250000001166\nlead_id: 1166\nchannel: Local/58600051@default"
        ));
    when(client.manualDialLookCall(eq("agent1"), anyMap()))
        .thenReturn(new VicidialClient.VicidialHttpResult(
            200,
            "1772918965.46|Local/58600051@default|SIP/trunk220_24-00000016"
        ));
    when(client.monitorConf(eq("agent1"), anyMap()))
        .thenReturn(new VicidialClient.VicidialHttpResult(200, "MONITOR_OK"));
    when(client.manualDialLogCall(eq("agent1"), anyMap(), eq("start")))
        .thenReturn(new VicidialClient.VicidialHttpResult(200, "LOG_START_OK"));

    var parsed = new VicidialDialResponseParser.DetailedParsedDialResponse(
        VicidialDialResponseParser.DialClassification.SUCCESS,
        true,
        "M3071629250000001166",
        1166L,
        "970222277",
        "106",
        "INCALL",
        Map.of()
    );

    var result = service.followUpManualDial("agent1", "secret", session, "MANUAL01", parsed, "970222277");

    assertTrue(result.incallConfirmed());
    assertEquals("SIP/trunk220_24-00000016", result.channel());
    verify(client).monitorConf(eq("agent1"), anyMap());
    verify(client).manualDialLogCall(eq("agent1"), anyMap(), eq("start"));
  }
}

