package com.telco3.agentui.domain;
import com.telco3.agentui.domain.Entities.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface UserRepository extends JpaRepository<UserEntity,Long> { Optional<UserEntity> findByUsernameAndActiveTrue(String username); }
