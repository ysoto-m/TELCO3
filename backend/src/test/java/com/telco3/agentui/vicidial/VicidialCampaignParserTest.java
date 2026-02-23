package com.telco3.agentui.vicidial;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VicidialCampaignParserTest {

  private final VicidialCampaignParser parser = new VicidialCampaignParser();

  @Test
  void parseCampaignOptionsExtractsValueAndLabelFromRealSelectHtml() {
    String html = """
        <select size=1 name=VD_campaign onchange=LoadIngroups()>
          <option value=\"\">-- PLEASE SELECT A CAMPAIGN --</option>
          <option value=\"IVR\">IVR - Inbound Test</option>
          <option value=\"SALES\">SALES</option>
        </select>
        """;

    List<VicidialCampaignParser.CampaignOption> result = parser.parseCampaignOptions(html);

    assertEquals(2, result.size());
    assertEquals("IVR", result.get(0).value());
    assertEquals("IVR - Inbound Test", result.get(0).label());
    assertEquals("SALES", result.get(1).value());
    assertEquals("SALES", result.get(1).label());
  }
}
