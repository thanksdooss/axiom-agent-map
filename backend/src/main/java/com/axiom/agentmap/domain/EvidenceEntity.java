package com.axiom.agentmap.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** relationship_evidence 테이블: 관계마다 근거 문구와 출처(파일·위치). */
@Entity
@Table(name = "relationship_evidence")
public class EvidenceEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rel_id")
    public RelationshipEntity relationship;
    @Column(nullable = false)
    public int seq;
    @Column(name = "source_file", length = 300)
    public String file;
    @Column(name = "source_locator", length = 300)
    public String locator;
    @Column(name = "evidence_text", length = 2000)
    public String text;
    @Column(name = "match_note", length = 500)
    public String matchNote;
    @Column(name = "direction_note", length = 500)
    public String directionNote;

    public EvidenceEntity() {}

    public EvidenceEntity(String file, String locator, String text, String matchNote, String directionNote) {
        this.file = file;
        this.locator = locator;
        this.text = text;
        this.matchNote = matchNote;
        this.directionNote = directionNote;
    }
}
