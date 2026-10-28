package com.axiom.agentmap.repository;

import com.axiom.agentmap.domain.AgentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRepository extends JpaRepository<AgentEntity, String> {
}
