package com.telco3.agentui.domain;

import com.telco3.agentui.domain.Entities.AgentVicidialCredentialEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AgentVicidialCredentialRepository extends JpaRepository<AgentVicidialCredentialEntity, Long> {
  Optional<AgentVicidialCredentialEntity> findByAppUsernameAndAgentUser(String appUsername, String agentUser);
}
