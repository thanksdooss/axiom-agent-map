package com.axiom.agentmap.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "work_types")
public class WorkTypeEntity {
    @Id
    @Column(name = "work_type_id", length = 16)
    public String id;
    @Column(nullable = false, length = 200)
    public String name;
    @Column(length = 1000)
    public String definition;
    @Column(name = "alt_labels", length = 2000)
    public String altLabels;

    public WorkTypeEntity() {}
}
