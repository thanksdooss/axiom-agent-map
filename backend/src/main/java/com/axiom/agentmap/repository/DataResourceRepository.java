package com.axiom.agentmap.repository;

import com.axiom.agentmap.domain.DataResourceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DataResourceRepository extends JpaRepository<DataResourceEntity, String> {
}
