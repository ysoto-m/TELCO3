package com.telco3.agentui.vicidial;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class VicidialStartupValidator {
  private static final Logger log = LoggerFactory.getLogger(VicidialStartupValidator.class);
  private final VicidialDiagnosticsService diagnosticsService;

  public VicidialStartupValidator(VicidialDiagnosticsService diagnosticsService) {
    this.diagnosticsService = diagnosticsService;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void validateOnReady() {
    if (!diagnosticsService.isDevMode()) {
      return;
    }

    var config = diagnosticsService.resolvedConfig();
    try {
      diagnosticsService.validateConfigOrThrow(config);
    } catch (VicidialServiceException ex) {
      log.warn("Vicidial configuration warning (dev): {}", ex.getMessage());
      return;
    }

    var probe = diagnosticsService.probe(config);
    if (!probe.reachable()) {
      log.warn("Vicidial unreachable at startup (dev). base_url={} cause={} error={}", config.baseUrl(), probe.causeClass(), probe.error());
    }
  }
}
