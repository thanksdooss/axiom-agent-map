package com.axiom.agentmap.repository;

import com.axiom.agentmap.domain.DepartmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepartmentRepository extends JpaRepository<DepartmentEntity, String> {
}
