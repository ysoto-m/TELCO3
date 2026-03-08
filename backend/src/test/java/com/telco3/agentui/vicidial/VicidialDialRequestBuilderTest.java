package com.telco3.agentui.vicidial;

import com.telco3.agentui.domain.Entities;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VicidialDialRequestBuilderTest {

  @Test
  void buildDialNextPayloadDoesNotPropagateTaskConfNumPlaceholder() {
    VicidialClient client = mock(VicidialClient.class);
    VicidialCampaignParser parser = mock(VicidialCampaignParser.class);
    VicidialDialProperties properties = new VicidialDialProperties();
    properties.setCampaignAliases(Map.of());

    when(client.campaignsForAgent("agent1", "secret"))
        .thenReturn(new VicidialClient.VicidialHttpResult(200, "<option value='MANUAL2'>MANUAL2</option>"));
    when(parser.parseCampaignOptions(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(List.of(new VicidialCampaignParser.CampaignOption("MANUAL2", "MANUAL2")));

    VicidialDialRequestBuilder builder = new VicidialDialRequestBuilder(client, parser, properties);
    Entities.AgentVicidialCredentialEntity session = new Entities.AgentVicidialCredentialEntity();
    session.serverIp = "172.17.248.220";
    session.sessionName = "1772926042_100114082742";
    session.connectedPhoneLogin = "1001";
    session.connectedCampaign = "MANUAL2";
    session.agentLogId = 407L;
    session.confExten = "taskconfnum";
    session.extension = "taskconfnum";
    session.protocol = "Local";

    Map<String, String> payload = builder.buildDialNextPayload("agent1", "secret", session, "MANUAL2");

    assertEquals("1001", payload.get("conf_exten"));
    assertEquals("1001", payload.get("exten"));
    assertFalse(payload.getOrDefault("channel", "").toLowerCase().contains("taskconfnum"));
  }

  @Test
  void buildDialNextPayloadBuildsLocalChannelWhenConfExtenIsValid() {
    VicidialClient client = mock(VicidialClient.class);
    VicidialCampaignParser parser = mock(VicidialCampaignParser.class);
    VicidialDialProperties properties = new VicidialDialProperties();
    properties.setCampaignAliases(Map.of());

    when(client.campaignsForAgent("agent1", "secret"))
        .thenReturn(new VicidialClient.VicidialHttpResult(200, "<option value='MANUAL2'>MANUAL2</option>"));
    when(parser.parseCampaignOptions(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(List.of(new VicidialCampaignParser.CampaignOption("MANUAL2", "MANUAL2")));

    VicidialDialRequestBuilder builder = new VicidialDialRequestBuilder(client, parser, properties);
    Entities.AgentVicidialCredentialEntity session = new Entities.AgentVicidialCredentialEntity();
    session.serverIp = "172.17.248.220";
    session.sessionName = "1772926042_100114082742";
    session.connectedPhoneLogin = "1001";
    session.connectedCampaign = "MANUAL2";
    session.agentLogId = 407L;
    session.confExten = "8600051";
    session.extension = "1001";
    session.protocol = "SIP";

    Map<String, String> payload = builder.buildDialNextPayload("agent1", "secret", session, "MANUAL2");

    assertEquals("Local/58600051@default", payload.get("channel"));
    assertFalse(payload.get("channel").startsWith("SIP/"));
  }

  @Test
  void buildManualDialPayloadAddsLookupFieldsForTypedNumber() {
    VicidialClient client = mock(VicidialClient.class);
    VicidialCampaignParser parser = mock(VicidialCampaignParser.class);
    VicidialDialProperties properties = new VicidialDialProperties();
    properties.setCampaignAliases(Map.of());

    when(client.campaignsForAgent("agent1", "secret"))
        .thenReturn(new VicidialClient.VicidialHttpResult(200, "<option value='MANUAL2'>MANUAL2</option>"));
    when(parser.parseCampaignOptions(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(List.of(new VicidialCampaignParser.CampaignOption("MANUAL2", "MANUAL2")));

    VicidialDialRequestBuilder builder = new VicidialDialRequestBuilder(client, parser, properties);
    Entities.AgentVicidialCredentialEntity session = new Entities.AgentVicidialCredentialEntity();
    session.serverIp = "172.17.248.220";
    session.sessionName = "1772926042_100114082742";
    session.connectedPhoneLogin = "1001";
    session.connectedCampaign = "MANUAL2";
    session.agentLogId = 407L;
    session.confExten = "8600051";
    session.extension = "1001";
    session.protocol = "Local";

    Map<String, String> payload = builder.buildManualDialPayload(
        "agent1",
        "secret",
        session,
        "MANUAL2",
        new VicidialDialRequestBuilder.ManualDialOverrides("970222277", "51", 60, "9")
    );

    assertEquals("lookup", payload.get("stage"));
    assertEquals("MANUAL_DIALNOW", payload.get("agent_dialed_type"));
    assertEquals("1", payload.get("agent_dialed_number"));
    assertEquals("Y", payload.get("routing_initiated_recording"));
  }

  @Test
  void buildManualDialPayloadUsesRecordingExtenFallbackWhenExtensionMatchesPhoneLogin() {
    VicidialClient client = mock(VicidialClient.class);
    VicidialCampaignParser parser = mock(VicidialCampaignParser.class);
    VicidialDialProperties properties = new VicidialDialProperties();
    properties.setCampaignAliases(Map.of());
    properties.setDefaultRecordingExten("8309");

    when(client.campaignsForAgent("agent1", "secret"))
        .thenReturn(new VicidialClient.VicidialHttpResult(200, "<option value='MANUAL2'>MANUAL2</option>"));
    when(parser.parseCampaignOptions(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(List.of(new VicidialCampaignParser.CampaignOption("MANUAL2", "MANUAL2")));

    VicidialDialRequestBuilder builder = new VicidialDialRequestBuilder(client, parser, properties);
    Entities.AgentVicidialCredentialEntity session = new Entities.AgentVicidialCredentialEntity();
    session.serverIp = "172.17.248.220";
    session.sessionName = "1772926042_100114082742";
    session.connectedPhoneLogin = "1001";
    session.connectedCampaign = "MANUAL2";
    session.agentLogId = 407L;
    session.confExten = "8600051";
    session.extension = "1001";
    session.protocol = "SIP";

    Map<String, String> payload = builder.buildManualDialPayload(
        "agent1",
        "secret",
        session,
        "MANUAL2",
        new VicidialDialRequestBuilder.ManualDialOverrides("970222277", "51", 60, "9")
    );

    assertEquals("8309", payload.get("exten"));
  }

  @Test
  void buildManualDialPayloadUsesConfiguredDefaultPhoneCode() {
    VicidialClient client = mock(VicidialClient.class);
    VicidialCampaignParser parser = mock(VicidialCampaignParser.class);
    VicidialDialProperties properties = new VicidialDialProperties();
    properties.setCampaignAliases(Map.of());
    properties.setDefaultPhoneCode("1");

    when(client.campaignsForAgent("agent1", "secret"))
        .thenReturn(new VicidialClient.VicidialHttpResult(200, "<option value='MANUAL2'>MANUAL2</option>"));
    when(parser.parseCampaignOptions(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(List.of(new VicidialCampaignParser.CampaignOption("MANUAL2", "MANUAL2")));

    VicidialDialRequestBuilder builder = new VicidialDialRequestBuilder(client, parser, properties);
    Entities.AgentVicidialCredentialEntity session = new Entities.AgentVicidialCredentialEntity();
    session.serverIp = "172.17.248.220";
    session.sessionName = "1772926042_100114082742";
    session.connectedPhoneLogin = "1001";
    session.connectedCampaign = "MANUAL2";
    session.agentLogId = 407L;
    session.confExten = "8600051";
    session.extension = "8309";
    session.protocol = "SIP";

    Map<String, String> payload = builder.buildManualDialPayload(
        "agent1",
        "secret",
        session,
        "MANUAL2",
        new VicidialDialRequestBuilder.ManualDialOverrides("970222277", null, 60, "9")
    );

    assertEquals("1", payload.get("phone_code"));
  }
}
