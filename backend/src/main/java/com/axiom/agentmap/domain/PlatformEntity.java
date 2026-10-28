package com.axiom.agentmap.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "platforms")
public class PlatformEntity {
    @Id
    @Column(name = "platform_id", length = 16)
    public String id;
    @Column(nullable = false, length = 200)
    public String name;
    @Column(length = 100)
    public String category;
    @Column(name = "alt_labels", length = 2000)
    public String altLabels;

    public PlatformEntity() {}
}
