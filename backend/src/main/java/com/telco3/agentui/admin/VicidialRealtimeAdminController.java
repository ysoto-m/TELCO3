package com.telco3.agentui.admin;

import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/vicidial")
public class VicidialRealtimeAdminController {
  private final VicidialRealtimeAdminService realtimeService;

  public VicidialRealtimeAdminController(VicidialRealtimeAdminService realtimeService) {
    this.realtimeService = realtimeService;
  }

  @GetMapping("/realtime/summary")
  public VicidialRealtimeAdminService.RealtimeSummaryResponse summary() {
    return realtimeService.summary();
  }

  @GetMapping("/realtime/agents")
  public VicidialRealtimeAdminService.RealtimeAgentsResponse agents(
      @RequestParam(required = false) String campaign,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String pauseCode,
      @RequestParam(required = false) String search
  ) {
    return realtimeService.agents(campaign, status, pauseCode, search);
  }

  @GetMapping("/realtime/pause-codes")
  public VicidialRealtimeAdminService.PauseCodesResponse pauseCodes() {
    return realtimeService.pauseCodes();
  }

  @GetMapping("/realtime/campaigns")
  public VicidialRealtimeAdminService.CampaignsResponse campaigns() {
    return realtimeService.campaigns();
  }

  @PostMapping("/leads/import")
  public Map<String, Object> importLeadsBase(@RequestBody LeadsImportRequest req) {
    var design = realtimeService.leadImportDesign();
    throw new ResponseStatusException(
        HttpStatus.NOT_IMPLEMENTED,
        design.message()
    );
  }

  public record LeadsImportRequest(
      @NotBlank String campaignId,
      @NotBlank String listId,
      String fileToken,
      Boolean dryRun
  ) {
  }
}
