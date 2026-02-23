package com.telco3.agentui.admin;

import com.telco3.agentui.agent.VicidialCredentialService;
import com.telco3.agentui.domain.Entities;
import com.telco3.agentui.domain.InteractionRepository;
import com.telco3.agentui.domain.UserRepository;
import com.telco3.agentui.domain.Entities.UserEntity;
import com.telco3.agentui.settings.SettingsController;
import com.telco3.agentui.vicidial.VicidialClient;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
  private final VicidialClient vicidial;
  private final InteractionRepository interactions;
  private final UserRepository users;
  private final SettingsController settingsController;
  private final PasswordEncoder encoder;
  private final VicidialCredentialService credentialService;

  public AdminController(VicidialClient vicidial, InteractionRepository interactions, UserRepository users, SettingsController settingsController, PasswordEncoder encoder, VicidialCredentialService credentialService) {
    this.vicidial = vicidial;
    this.interactions = interactions;
    this.users = users;
    this.settingsController = settingsController;
    this.encoder = encoder;
    this.credentialService = credentialService;
  }

  @GetMapping("/summary")
  Map<String, Object> summary() {
    int active = 0;
    int paused = 0;
    int incall = 0;
    String raw = "";
    boolean degraded = false;
    try {
      raw = vicidial.liveAgents();
      var lines = raw.lines().toList();
      active = lines.size();
      paused = (int) lines.stream().filter(l -> l.toUpperCase(Locale.ROOT).contains("PAUSE")).count();
      incall = (int) lines.stream().filter(l -> l.toUpperCase(Locale.ROOT).contains("INCALL")).count();
    } catch (Exception e) {
      degraded = true;
    }

    var todayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay().atOffset(ZoneOffset.UTC);
    var tomorrow = todayStart.plusDays(1);
    var list = interactions.findByCreatedAtBetween(todayStart, tomorrow);
    long interactionsToday = list.size();

    return Map.of(
        "activeAgents", active,
        "pausedAgents", paused,
        "incallAgents", incall,
        "interactionsToday", interactionsToday,
        "degraded", degraded,
        "raw", raw
    );
  }

  @GetMapping("/agents")
  Map<String, Object> agents() {
    try {
      var raw = vicidial.liveAgents();
      var rows = raw.lines()
          .filter(l -> !l.isBlank())
          .map(l -> Map.<String, Object>of(
              "raw", l,
              "agentUser", parseField(l, "user"),
              "status", parseField(l, "status"),
              "campaign", parseField(l, "campaign"),
              "extension", parseField(l, "extension"),
              "duration", parseField(l, "duration")
          )).toList();
      return Map.of("items", rows, "degraded", false);
    } catch (Exception e) {
      return Map.of("items", List.of(), "degraded", true, "message", "Vicidial no disponible");
    }
  }

  @GetMapping("/campaigns")
  Map<String, Object> campaigns() {
    try {
      var raw = vicidial.campaigns();
      var campaigns = raw.lines().filter(l -> !l.isBlank()).toList();
      return Map.of("items", campaigns, "degraded", false);
    } catch (Exception e) {
      return Map.of("items", interactions.findDistinctCampaigns(), "degraded", true, "message", "Usando campañas locales por degradación");
    }
  }

  @GetMapping("/interactions")
  Map<String, Object> interactions(
      @RequestParam(required = false) String campaign,
      @RequestParam(required = false) String agentUser,
      @RequestParam(required = false) String dispo,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size
  ) {
    var start = from.atStartOfDay().atOffset(ZoneOffset.UTC);
    var end = to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
    var result = interactions.findFiltered(emptyToNull(campaign), emptyToNull(agentUser), emptyToNull(dispo), start, end, PageRequest.of(page, size));
    return Map.of(
        "items", result.getContent(),
        "total", result.getTotalElements(),
        "page", result.getNumber(),
        "size", result.getSize()
    );
  }

  @GetMapping(value = "/interactions/export.csv", produces = "text/csv")
  String exportInteractions(
      @RequestParam(required = false) String campaign,
      @RequestParam(required = false) String agentUser,
      @RequestParam(required = false) String dispo,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
  ) {
    var start = from.atStartOfDay().atOffset(ZoneOffset.UTC);
    var end = to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
    var rows = interactions.findFiltered(emptyToNull(campaign), emptyToNull(agentUser), emptyToNull(dispo), start, end, PageRequest.of(0, 10000)).getContent();
    StringBuilder sb = new StringBuilder("id,dni,phone,agent,dispo,campaign,created_at,sync_status\n");
    rows.forEach(i -> sb
        .append(i.id).append(',')
        .append(csv(i.dni)).append(',')
        .append(csv(i.phoneNumber)).append(',')
        .append(csv(i.agentUser)).append(',')
        .append(csv(i.dispo)).append(',')
        .append(csv(i.campaign)).append(',')
        .append(i.createdAt).append(',')
        .append(i.syncStatus)
        .append('\n'));
    return sb.toString();
  }

  @GetMapping("/users")
  List<Map<String, Object>> listUsers() {
    return users.findAll().stream().map(u -> Map.<String, Object>of(
        "id", u.id,
        "username", u.username,
        "role", u.role.name(),
        "active", u.active
    )).toList();
  }

  public record UserUpsertReq(@NotBlank String username, String password, @NotBlank String role, Boolean active) {}

  @PostMapping("/users")
  Map<String, Object> createUser(@RequestBody UserUpsertReq req) {
    if (users.findByUsername(req.username()).isPresent()) throw new RuntimeException("Username already exists");
    UserEntity u = new UserEntity();
    u.username = req.username();
    u.passwordHash = req.password() == null || req.password().isBlank() ? "{plain}changeme" : encoder.encode(req.password());
    u.role = Entities.Role.valueOf(req.role());
    u.active = req.active() == null || req.active();
    users.save(u);
    return Map.of("ok", true, "id", u.id);
  }

  @PutMapping("/users/{id}")
  Map<String, Object> updateUser(@PathVariable Long id, @RequestBody UserUpsertReq req) {
    UserEntity u = users.findById(id).orElseThrow();
    u.username = req.username();
    if (req.password() != null && !req.password().isBlank()) u.passwordHash = encoder.encode(req.password());
    u.role = Entities.Role.valueOf(req.role());
    u.active = req.active() == null || req.active();
    users.save(u);
    return Map.of("ok", true);
  }



  public record AdminAgentPassReq(@NotBlank String agentPass) {}

  @PutMapping("/users/{id}/agent-pass")
  Map<String, Object> updateAgentPass(@PathVariable Long id, @RequestBody AdminAgentPassReq req) throws Exception {
    UserEntity u = users.findById(id).orElseThrow();
    credentialService.updateAgentPass(u.username, req.agentPass());
    return Map.of("ok", true);
  }

  @GetMapping("/settings")
  Map<String, Object> settings() {
    return settingsController.get();
  }

  @PutMapping("/settings")
  Map<String, Object> updateSettings(@RequestBody SettingsController.SettingsReq req) throws Exception {
    return settingsController.put(req);
  }

  private String parseField(String line, String key) {
    var m = java.util.regex.Pattern.compile(key + "=([^&\\s]+)").matcher(line);
    return m.find() ? m.group(1) : "";
  }

  private String csv(String v) {
    if (v == null) return "";
    return '"' + v.replace("\"", "\"\"") + '"';
  }

  private String emptyToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
