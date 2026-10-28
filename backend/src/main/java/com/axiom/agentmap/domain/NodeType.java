package com.axiom.agentmap.domain;

import com.fasterxml.jackson.annotation.JsonValue;

public enum NodeType {
    AGENT("Agent"), DATA_ASSET("DataAsset"), PLATFORM("Platform"), DEPARTMENT("Department"), WORK_TYPE("WorkType");

    private final String code;

    NodeType(String code) { this.code = code; }

    @JsonValue
    public String code() { return code; }

    /** ID 접두사로 노드 종류를 정합니다. DEP를 D보다 먼저 검사합니다. */
    public static NodeType ofId(String id) {
        if (id == null || id.isEmpty()) return null;
        if (id.startsWith("DEP")) return DEPARTMENT;
        if (id.startsWith("WT")) return WORK_TYPE;
        if (id.startsWith("A")) return AGENT;
        if (id.startsWith("D")) return DATA_ASSET;
        if (id.startsWith("P")) return PLATFORM;
        return null;
    }

    public static NodeType ofCode(String code) {
        for (NodeType t : values()) if (t.code.equalsIgnoreCase(code)) return t;
        return null;
    }
}
