package com.telco3.agentui.legacy;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
/**
 * Legacy repository for english-named customer model.
 * Kept for compatibility with legacy interaction flows.
 */
@Deprecated(forRemoval = false, since = "1.3.0")
public interface CustomerRepository extends JpaRepository<CustomerEntity,Long> { Optional<CustomerEntity> findByDni(String dni); }
