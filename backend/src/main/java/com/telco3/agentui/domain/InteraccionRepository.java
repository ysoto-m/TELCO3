package com.telco3.agentui.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InteraccionRepository extends JpaRepository<InteraccionEntity, Long> {
  Optional<InteraccionEntity> findFirstByEstadoAndCallIdOrderByFechaInicioDesc(String estado, String callId);

  Optional<InteraccionEntity> findFirstByEstadoAndAgenteAndTelefonoOrderByFechaInicioDesc(String estado, String agente, String telefono);

  List<InteraccionEntity> findTop100ByTelefonoOrderByFechaInicioDesc(String telefono);
}
