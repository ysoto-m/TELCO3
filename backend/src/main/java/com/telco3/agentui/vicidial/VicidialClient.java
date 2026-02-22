package com.telco3.agentui.vicidial;

import com.telco3.agentui.settings.SettingsController;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;

@Component
public class VicidialClient {
  private final SettingsController settings;
  public VicidialClient(SettingsController settings){this.settings=settings;}

  private String call(String path, Map<String,String> params){
    var s=settings.current();
    params.put("source", s.source); params.put("user", s.apiUser); params.put("pass", settings.decryptedPass());
    return WebClient.create(s.baseUrl).get().uri(u->{
      var b=u.path(path); params.forEach(b::queryParam); return b.build();
    }).retrieve().bodyToMono(String.class).blockOptional().orElse("");
  }
  public String externalStatus(String agent,String dispo,Long leadId,String campaign){
    return call("/agc/api.php",new HashMap<>(Map.of("function","external_status","agent_user",agent,"value",dispo,"dispo_choice",dispo,"lead_id",String.valueOf(leadId),"campaign",campaign)));
  }
  public String previewAction(String agent,Long leadId,String campaign,String action){
    return call("/agc/api.php",new HashMap<>(Map.of("function","preview_dial_action","agent_user",agent,"lead_id",String.valueOf(leadId),"campaign",campaign,"value",action)));
  }
  public String pause(String agent,String action){ return call("/agc/api.php",new HashMap<>(Map.of("function","external_pause","agent_user",agent,"value",action))); }
  public String activeLead(String agent){ return call("/agc/api.php",new HashMap<>(Map.of("function","st_get_agent_active_lead","agent_user",agent))); }
  public String leadSearch(String phone){ return call("/vicidial/non_agent_api.php",new HashMap<>(Map.of("function","lead_search","phone_number",phone))); }
  public String leadInfo(Long leadId){ return call("/vicidial/non_agent_api.php",new HashMap<>(Map.of("function","lead_all_info","lead_id",String.valueOf(leadId)))); }
  public String addLead(String phone,String first,String last,String dni,String listId){
    return call("/vicidial/non_agent_api.php",new HashMap<>(Map.of("function","add_lead","phone_number",phone,"first_name",first,"last_name",last,"vendor_lead_code",dni,"list_id",listId)));
  }
}
