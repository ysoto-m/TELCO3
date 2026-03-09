package com.telco3.agentui.domain;
import com.telco3.agentui.domain.Entities.CustomerPhoneEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
/**
 * Legacy repository for english-named customer phones.
 * Kept for compatibility with legacy interaction flows.
 */
@Deprecated(forRemoval = false, since = "1.3.0")
public interface CustomerPhoneRepository extends JpaRepository<CustomerPhoneEntity,Long> {
 List<CustomerPhoneEntity> findByCustomerId(Long customerId);
 Optional<CustomerPhoneEntity> findByPhoneNumber(String phoneNumber);
}
