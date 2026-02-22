package com.telco3.agentui.domain;
import com.telco3.agentui.domain.Entities.CustomerPhoneEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface CustomerPhoneRepository extends JpaRepository<CustomerPhoneEntity,Long> {
 List<CustomerPhoneEntity> findByCustomerId(Long customerId);
 Optional<CustomerPhoneEntity> findByPhoneNumber(String phoneNumber);
}
