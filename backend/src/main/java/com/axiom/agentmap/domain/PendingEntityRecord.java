package com.axiom.agentmap.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** pending_entities 테이블: 표준 목록에 없어 사람이 등록·매핑·기각을 정해야 하는 대상. */
@Entity
@Table(name = "pending_entities")
public class PendingEntityRecord {
    @Id
    @Column(name = "pending_id", length = 16)
    public String id;
    @Column(name = "raw_label", nullable = false, length = 300)
    public String rawLabel;
    @Column(length = 50)
    public String kind;
    @Column(name = "mentioned_by", length = 16)
    public String mentionedBy;
    @Column(length = 1000)
    public String reason;
    @Column(name = "source_file", length = 300)
    public String evidenceFile;
    @Column(name = "source_locator", length = 300)
    public String evidenceLocator;
    @Column(name = "evidence_text", length = 2000)
    public String evidenceText;
    @Column(name = "pending_state", length = 16)
    public String state;
    @Column(name = "resolved_to_id", length = 16)
    public String resolvedToId;

    public PendingEntityRecord() {}
}
