package com.axiom.agentmap.api;

import com.axiom.agentmap.api.GraphDtos.GraphEdge;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public final class AgentDtos {
    private AgentDtos() {}

    /** candidate는 문자열·숫자·null */
    public record ReviewFlag(String field, String raw, Object candidate, String reason) {}

    public record AgentSummary(String id, String name, String departmentId, String departmentName,
                               String workTypeId, String workTypeName, String status, List<String> owners,
                               List<String> platformIds, List<String> dataIds, boolean highSensitivity,
                               int reviewFlagCount) {}

    public record AgentDetail(String id, String name, String departmentId, String departmentName,
                              String workTypeId, String workTypeName, String status, List<String> owners,
                              List<String> platformIds, List<String> dataIds, boolean highSensitivity,
                              int reviewFlagCount, String description, String startDate, Integer monthlyCalls,
                              String sourceFile, List<ReviewFlag> reviewFlags, List<GraphEdge> outgoing,
                              List<GraphEdge> incoming) {}

    /** 일괄 등록 미리보기의 Agent (AgentSummary + isNew·reviewFlags·sourceFile) */
    public record ImportAgent(String id, String name, String departmentId, String departmentName,
                              String workTypeId, String workTypeName, String status, List<String> owners,
                              List<String> platformIds, List<String> dataIds, boolean highSensitivity,
                              int reviewFlagCount, @JsonProperty("isNew") boolean isNew,
                              List<ReviewFlag> reviewFlags, String sourceFile,
                              String description, String startDate, Integer monthlyCalls) {}
}
