package com.telco3.agentui.vicidial;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VicidialDialResponseParserTest {

  private final VicidialDialResponseParser parser = new VicidialDialResponseParser();

  @Test
  void parseSuccessFromPlainTextResponse() {
    String raw = "M251231123456789\nlead_id: 12345\nstatus: SENT";
    var result = parser.parse(raw);
    assertTrue(result.success());
    assertEquals(VicidialDialResponseParser.DialClassification.SUCCESS, result.classification());
    assertEquals("M251231123456789", result.callId());
    assertEquals(12345L, result.leadId());
  }

  @Test
  void parseSuccessWithoutLeadId() {
    String raw = "M251231123456789\nstatus: SENT";
    var result = parser.parse(raw);
    assertTrue(result.success());
    assertEquals(VicidialDialResponseParser.DialClassification.SUCCESS, result.classification());
    assertEquals("M251231123456789", result.callId());
    assertNull(result.leadId());
  }

  @Test
  void parseReloginFromHtmlLoginPage() {
    String raw = "<html><body><form><input name=\"VD_login\"/></form>Please login</body></html>";
    var result = parser.parse(raw);
    assertFalse(result.success());
    assertEquals(VicidialDialResponseParser.DialClassification.RELOGIN_REQUIRED, result.classification());
  }

  @Test
  void parseNoLeads() {
    String raw = "ERROR: There are no leads in the hopper for this campaign";
    var result = parser.parse(raw);
    assertFalse(result.success());
    assertEquals(VicidialDialResponseParser.DialClassification.NO_LEADS, result.classification());
  }
  @Test
  void parseNoLeadsTakesPriorityOverLoginMarkers() {
    String raw = "<html><body>Please login - there are no leads in the hopper for this campaign</body></html>";
    var result = parser.parse(raw);
    assertFalse(result.success());
    assertEquals(VicidialDialResponseParser.DialClassification.NO_LEADS, result.classification());
  }

  @Test
  void parseDetailedExtractsHarStyleManualDialFields() {
    String raw = """
        ACTION: manDiaLnextCaLL
        callId = M3071629250000001166
        lead_id = 1166
        phone_number = 970222277
        list_id = 106
        status = INCALL
        """;
    var result = parser.parseDetailed(raw);
    assertTrue(result.success());
    assertEquals(VicidialDialResponseParser.DialClassification.SUCCESS, result.classification());
    assertEquals("M3071629250000001166", result.callId());
    assertEquals(1166L, result.leadId());
    assertEquals("970222277", result.phoneNumber());
    assertEquals("106", result.listId());
    assertEquals("INCALL", result.leadStatus());
  }

  @Test
  void parseDetailedExtractsQuotedJavascriptStyleFields() {
    String raw = """
        var call_id = 'M3071755390000000549';
        var lead_id = '549';
        var phone_number = '970222277';
        var list_id = '106';
        var status = 'INCALL';
        """;
    var result = parser.parseDetailed(raw);
    assertTrue(result.success());
    assertEquals(VicidialDialResponseParser.DialClassification.SUCCESS, result.classification());
    assertEquals("M3071755390000000549", result.callId());
    assertEquals(549L, result.leadId());
    assertEquals("970222277", result.phoneNumber());
    assertEquals("106", result.listId());
    assertEquals("INCALL", result.leadStatus());
  }

  @Test
  void parsePermissionDeniedIsInvalidSessionNotRelogin() {
    String raw = "ERROR: auth USER DOES NOT HAVE PERMISSION TO PERFORM FUNCTION";
    var result = parser.parseDetailed(raw);
    assertFalse(result.success());
    assertEquals(VicidialDialResponseParser.DialClassification.INVALID_SESSION, result.classification());
  }

  @Test
  void parseDetailedExtractsOfficialPositionalDialFields() {
    String raw = "M3071629250000001166 1166 NEW   106 -7.00 1 970222277";
    var result = parser.parseDetailed(raw);
    assertTrue(result.success());
    assertEquals("M3071629250000001166", result.callId());
    assertEquals(1166L, result.leadId());
    assertEquals("970222277", result.phoneNumber());
    assertEquals("106", result.listId());
    assertEquals("NEW", result.leadStatus());
  }

}
