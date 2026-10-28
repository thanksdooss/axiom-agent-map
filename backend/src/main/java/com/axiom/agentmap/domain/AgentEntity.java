package com.axiom.agentmap.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * agents 테이블 (seed/agents.csv와 같은 열). 소속·업무 유형은 관계(BELONGS_TO·HAS_WORK_TYPE)가 원본이고
 * 여기 열은 등록 시점의 표준값을 함께 보관합니다. 단순한 MVP라 필드를 공개합니다(필드 접근).
 */
@Entity
@Table(name = "agents")
public class AgentEntity {
    @Id
    @Column(name = "agent_id", length = 16)
    public String id;
    @Column(nullable = false, length = 200)
    public String name;
    @Column(name = "department_id", length = 16)
    public String departmentId;
    /** "; "로 이은 담당자 이름 */
    @Column(length = 500)
    public String owners;
    @Column(name = "work_type_id", length = 16)
    public String workTypeId;
    @Column(length = 2000)
    public String description;
    @Column(name = "start_date", length = 16)
    public String startDate;
    @Column(name = "monthly_calls")
    public Integer monthlyCalls;
    @Column(name = "agent_status", length = 16)
    public String status;
    @Column(name = "source_file", length = 200)
    public String sourceFile;
    /** 검토 필요 표시 목록(JSON 배열) */
    @Column(name = "review_flags_json", length = 20000)
    public String reviewFlagsJson;
    /** 원본 표기(JSON 객체) */
    @Column(name = "raw_json", length = 20000)
    public String rawJson;

    public AgentEntity() {}
}
