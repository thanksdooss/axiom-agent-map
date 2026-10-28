package com.axiom.agentmap.repository;

import com.axiom.agentmap.domain.PendingEntityRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PendingEntityRepository extends JpaRepository<PendingEntityRecord, String> {
}
