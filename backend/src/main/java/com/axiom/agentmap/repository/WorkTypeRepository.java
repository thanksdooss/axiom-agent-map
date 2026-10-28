package com.axiom.agentmap.repository;

import com.axiom.agentmap.domain.WorkTypeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkTypeRepository extends JpaRepository<WorkTypeEntity, String> {
}
