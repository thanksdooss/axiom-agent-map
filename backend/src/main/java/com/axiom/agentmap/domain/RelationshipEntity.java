package com.axiom.agentmap.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;

/** relationships 테이블. 방향은 쓰는 쪽(source) → 제공하는 쪽(target). */
@Entity
@Table(name = "relationships")
public class RelationshipEntity {
    @Id
    @Column(name = "rel_id", length = 16)
    public String id;
    @Enumerated(EnumType.STRING)
    @Column(name = "rel_type", nullable = false, length = 32)
    public RelType type;
    @Column(name = "source_id", nullable = false, length = 16)
    public String sourceId;
    @Column(name = "target_id", nullable = false, length = 16)
    public String targetId;
    @Enumerated(EnumType.STRING)
    @Column(name = "rel_status", nullable = false, length = 16)
    public RelStatus status;
    @Column(name = "review_reason", length = 1000)
    public String reviewReason;
    @Column(name = "reviewed_by", length = 100)
    public String reviewedBy;
    @Column(name = "reviewed_at", length = 16)
    public String reviewedAt;
    /** 모호한 이름에서 보여준 후보 Agent ID (", "로 이음) */
    @Column(name = "candidates_shown", length = 500)
    public String candidatesShown;

    @OneToMany(mappedBy = "relationship", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("seq ASC")
    public List<EvidenceEntity> evidence = new ArrayList<>();

    public RelationshipEntity() {}

    public void addEvidence(EvidenceEntity e) {
        e.relationship = this;
        e.seq = evidence.size();
        evidence.add(e);
    }
}
