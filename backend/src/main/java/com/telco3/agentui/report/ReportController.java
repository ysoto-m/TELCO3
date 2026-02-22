package com.telco3.agentui.report;

import com.telco3.agentui.domain.Repositories.InteractionRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.util.*;

@RestController @RequestMapping("/api/reports")
public class ReportController {
  private final InteractionRepository repo;
  public ReportController(InteractionRepository repo){this.repo=repo;}

  @GetMapping("/summary")
  Map<String,Object> summary(@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,@RequestParam(required=false) String campaign){
    var start=from.atStartOfDay().atOffset(ZoneOffset.UTC); var end=to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
    var list=campaign==null?repo.findByCreatedAtBetween(start,end):repo.findByCreatedAtBetweenAndCampaign(start,end,campaign);
    var byDispo=new HashMap<String,Long>(); list.forEach(i->byDispo.merge(i.dispo,1L,Long::sum));
    return Map.of("total",list.size(),"byDispo",byDispo);
  }

  @GetMapping(value="/export",produces="text/csv")
  String export(@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,@RequestParam String format){
    var start=from.atStartOfDay().atOffset(ZoneOffset.UTC); var end=to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
    var rows=repo.findByCreatedAtBetween(start,end);
    StringBuilder sb=new StringBuilder("id,dni,phone,agent,dispo,campaign,created_at,sync_status\n");
    rows.forEach(i->sb.append(i.id).append(',').append(i.dni).append(',').append(i.phoneNumber).append(',').append(i.agentUser).append(',').append(i.dispo).append(',').append(i.campaign).append(',').append(i.createdAt).append(',').append(i.syncStatus).append('\n'));
    return sb.toString();
  }
}
