package com.telco3.agentui.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ContactoRepository extends JpaRepository<ContactoEntity, Long> {
  Optional<ContactoEntity> findByTelefono(String telefono);
}
