package com.axiom.agentmap.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "data_resources")
public class DataResourceEntity {
    @Id
    @Column(name = "data_id", length = 16)
    public String id;
    @Column(nullable = false, length = 200)
    public String name;
    /** 상·중·하 (미정이면 null) */
    @Column(length = 4)
    public String sensitivity;
    @Column(length = 100)
    public String category;
    @Column(name = "stored_in_platform_id", length = 16)
    public String storedInPlatformId;
    /** "; "로 이은 별칭 */
    @Column(name = "alt_labels", length = 2000)
    public String altLabels;

    public DataResourceEntity() {}
}
