package com.telco3.agentui.vicidial;

import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class VicidialCampaignParser {

  public List<CampaignOption> parseCampaignOptions(String html) {
    if (html == null || html.isBlank()) {
      return List.of();
    }

    var options = Jsoup.parseBodyFragment(html)
        .select("option[value]");

    Set<String> seen = new LinkedHashSet<>();
    List<CampaignOption> campaigns = new ArrayList<>();
    for (var option : options) {
      String value = option.attr("value").trim();
      String label = option.text().trim();
      if (value.isBlank()) {
        continue;
      }
      if (label.toUpperCase(Locale.ROOT).contains("PLEASE SELECT A CAMPAIGN")) {
        continue;
      }
      if (seen.add(value)) {
        campaigns.add(new CampaignOption(value, label));
      }
    }
    return campaigns;
  }

  public record CampaignOption(String value, String label) {}
}
