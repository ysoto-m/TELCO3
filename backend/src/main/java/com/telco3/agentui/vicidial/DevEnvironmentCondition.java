package com.telco3.agentui.vicidial;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Arrays;

public class DevEnvironmentCondition implements Condition {
  @Override
  public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
    boolean profileDev = Arrays.stream(context.getEnvironment().getActiveProfiles()).anyMatch("dev"::equalsIgnoreCase);
    String appEnv = context.getEnvironment().getProperty("APP_ENV", context.getEnvironment().getProperty("app.env", ""));
    return profileDev || "dev".equalsIgnoreCase(appEnv);
  }
}
