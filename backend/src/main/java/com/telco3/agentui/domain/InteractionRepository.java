package com.telco3.agentui.domain;
import com.telco3.agentui.domain.Entities.InteractionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.OffsetDateTime;
import java.util.List;
public interface InteractionRepository extends JpaRepository<InteractionEntity,Long> {
 List<InteractionEntity> findTop20ByDniOrderByCreatedAtDesc(String dni);
 List<InteractionEntity> findByCreatedAtBetween(OffsetDateTime from, OffsetDateTime to);
 List<InteractionEntity> findByCreatedAtBetweenAndCampaign(OffsetDateTime from, OffsetDateTime to, String campaign);
}
