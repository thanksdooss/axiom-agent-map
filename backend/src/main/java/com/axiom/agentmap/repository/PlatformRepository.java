package com.axiom.agentmap.repository;

import com.axiom.agentmap.domain.PlatformEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformRepository extends JpaRepository<PlatformEntity, String> {
}
