package com.telco3.agentui.validacionclaroperu.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FormularioValidacionClaroPeruRepository extends JpaRepository<FormularioValidacionClaroPeruEntity, Long> {
  Optional<FormularioValidacionClaroPeruEntity> findFirstByDocumentoOrderByFechaRegistroDesc(String documento);
}
