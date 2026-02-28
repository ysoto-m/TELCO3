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
}
