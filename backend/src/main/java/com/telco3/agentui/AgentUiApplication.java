package com.telco3.agentui;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AgentUiApplication {
  public static void main(String[] args) { SpringApplication.run(AgentUiApplication.class, args); }
}
