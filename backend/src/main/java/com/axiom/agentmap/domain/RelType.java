package com.axiom.agentmap.domain;

/** 허용 관계 코드 6개. 순서는 vocabulary.json relation_types 순서(관계 ID 정렬에도 씀). */
public enum RelType {
    USES_RESULT_OF("결과사용", NodeType.AGENT, NodeType.AGENT),
    ACCESSES_DATA("데이터사용", NodeType.AGENT, NodeType.DATA_ASSET),
    USES_PLATFORM("사용 플랫폼", NodeType.AGENT, NodeType.PLATFORM),
    BELONGS_TO("소속", NodeType.AGENT, NodeType.DEPARTMENT),
    HAS_WORK_TYPE("업무 성격", NodeType.AGENT, NodeType.WORK_TYPE),
    STORED_IN("저장 위치", NodeType.DATA_ASSET, NodeType.PLATFORM);

    private final String label;
    private final NodeType domain;
    private final NodeType range;

    RelType(String label, NodeType domain, NodeType range) {
        this.label = label;
        this.domain = domain;
        this.range = range;
    }

    public String label() { return label; }
    public NodeType domain() { return domain; }
    public NodeType range() { return range; }

    /** Agent에서 해당 종류의 대상으로 가는 관계 종류. */
    public static RelType fromAgentTo(NodeType targetType) {
        return switch (targetType) {
            case AGENT -> USES_RESULT_OF;
            case DATA_ASSET -> ACCESSES_DATA;
            case PLATFORM -> USES_PLATFORM;
            case DEPARTMENT -> BELONGS_TO;
            case WORK_TYPE -> HAS_WORK_TYPE;
        };
    }
}
