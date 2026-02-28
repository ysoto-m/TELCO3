package com.telco3.agentui.vicidial;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/campaigns")
public class CampaignController {
  private final VicidialService vicidialService;

  public CampaignController(VicidialService vicidialService) {
    this.vicidialService = vicidialService;
  }

  @GetMapping("/{campaignId}")
  public Map<String, Object> getCampaign(@PathVariable String campaignId) {
    VicidialService.CampaignMode mode = vicidialService.campaignMode(campaignId);
    Map<String, Object> campaign = new LinkedHashMap<>();
    campaign.put("campaignId", mode.campaignId());
    campaign.put("dialMethodRaw", mode.dialMethodRaw());
    campaign.put("mode", mode.mode());
    return Map.of("ok", true, "campaign", campaign);
  }
}
