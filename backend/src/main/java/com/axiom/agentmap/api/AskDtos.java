package com.axiom.agentmap.api;

import com.axiom.agentmap.api.GraphDtos.NodeRef;
import com.axiom.agentmap.domain.RelType;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

public final class AskDtos {
    private AskDtos() {}

    public record AskRequest(String question, String scope) {}

    public record RelationRef(String source, RelType type, String target) {}

    /**
     * 질문 해석. intent 이름은 query_golden_set.json과 같습니다.
     * 계약 필드(intent·anchor·anchorRaw·target·direction·depth·scope) 뒤의 필드는 보조 정보입니다.
     */
    public record Interpretation(String intent, NodeRef anchor, String anchorRaw, NodeRef target, String direction,
                                 Integer depth, String scope,
                                 @JsonInclude(JsonInclude.Include.NON_NULL) String anchorType,
                                 @JsonInclude(JsonInclude.Include.NON_EMPTY) List<RelType> relationTypes,
                                 @JsonInclude(JsonInclude.Include.NON_NULL) String groupBy,
                                 @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Object> filter,
                                 @JsonInclude(JsonInclude.Include.NON_NULL) String metric,
                                 @JsonInclude(JsonInclude.Include.NON_NULL) Integer top,
                                 @JsonInclude(JsonInclude.Include.NON_NULL) RelationRef relation) {}

    public record Clarification(String message, List<NodeRef> options) {}

    public record Highlight(List<String> nodeIds, List<String> edgeIds) {}

    public record AskResponse(String question, String engine, Interpretation interpretation, Clarification clarification,
                              String answer, Object result, Highlight highlight) {}
}
