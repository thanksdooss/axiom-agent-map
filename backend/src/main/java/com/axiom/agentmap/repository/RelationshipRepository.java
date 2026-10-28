package com.axiom.agentmap.repository;

import com.axiom.agentmap.domain.RelationshipEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface RelationshipRepository extends JpaRepository<RelationshipEntity, String> {
    /** 근거까지 한 번에 읽습니다(N+1 방지). */
    @Query("select distinct r from RelationshipEntity r left join fetch r.evidence order by r.id")
    List<RelationshipEntity> findAllWithEvidence();
}
