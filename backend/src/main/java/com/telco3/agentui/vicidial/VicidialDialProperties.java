package com.telco3.agentui.vicidial;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "app.vicidial.dial")
public class VicidialDialProperties {
  private Map<String, String> campaignAliases = new LinkedHashMap<>();

  public Map<String, String> getCampaignAliases() {
    return campaignAliases;
  }

  public void setCampaignAliases(Map<String, String> campaignAliases) {
    this.campaignAliases = campaignAliases == null ? new LinkedHashMap<>() : new LinkedHashMap<>(campaignAliases);
  }
}
