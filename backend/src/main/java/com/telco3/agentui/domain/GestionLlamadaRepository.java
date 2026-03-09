package com.telco3.agentui.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GestionLlamadaRepository extends JpaRepository<GestionLlamadaEntity, Long> {
  List<GestionLlamadaEntity> findTop100ByTelefonoOrderByFechaGestionDesc(String telefono);
  long countByTelefono(String telefono);
}
