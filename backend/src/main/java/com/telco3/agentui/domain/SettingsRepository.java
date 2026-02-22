package com.telco3.agentui.domain;
import com.telco3.agentui.domain.Entities.VicidialSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
public interface SettingsRepository extends JpaRepository<VicidialSettingsEntity,Long> {}
