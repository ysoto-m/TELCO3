package com.telco3.agentui.domain;
import com.telco3.agentui.domain.Entities.InteractionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface InteractionRepository extends JpaRepository<InteractionEntity,Long> {
 List<InteractionEntity> findTop20ByDniOrderByCreatedAtDesc(String dni);
 List<InteractionEntity> findByCreatedAtBetween(OffsetDateTime from, OffsetDateTime to);
 List<InteractionEntity> findByCreatedAtBetweenAndCampaign(OffsetDateTime from, OffsetDateTime to, String campaign);

 @Query("""
    select i from InteractionEntity i
    where (:campaign is null or i.campaign = :campaign)
      and (:agentUser is null or i.agentUser = :agentUser)
      and (:dispo is null or i.dispo = :dispo)
      and i.createdAt between :from and :to
 """)
 Page<InteractionEntity> findFiltered(
     @Param("campaign") String campaign,
     @Param("agentUser") String agentUser,
     @Param("dispo") String dispo,
     @Param("from") OffsetDateTime from,
     @Param("to") OffsetDateTime to,
     Pageable pageable
 );

 @Query("select distinct i.campaign from InteractionEntity i where i.campaign is not null and i.campaign <> ''")
 List<String> findDistinctCampaigns();
}
