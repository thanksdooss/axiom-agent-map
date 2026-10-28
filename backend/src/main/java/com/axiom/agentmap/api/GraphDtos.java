package com.axiom.agentmap.api;

import com.axiom.agentmap.domain.NodeType;
import com.axiom.agentmap.domain.RelStatus;
import com.axiom.agentmap.domain.RelType;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** 계약(docs/api-contract.md)의 공통 타입. 필드 이름이 곧 JSON 이름입니다. */
public final class GraphDtos {
    private GraphDtos() {}

    public record GraphNode(String id, NodeType type, String label, String group, String sensitivity,
                            String agentStatus, int degree) {}

    public record Evidence(String file, String locator, String text,
                           @JsonInclude(JsonInclude.Include.NON_NULL) String match,
                           @JsonInclude(JsonInclude.Include.NON_NULL) String directionNote) {
        public static Evidence of(String file, String locator, String text) {
            return new Evidence(file, locator, text, null, null);
        }

        public Evidence withMatch(String m) { return new Evidence(file, locator, text, m, directionNote); }

        public Evidence withDirectionNote(String d) { return new Evidence(file, locator, text, match, d); }
    }

    public record GraphEdge(String id, RelType type, String typeLabel, String source, String target,
                            String sourceName, String targetName, RelStatus status, String reviewReason,
                            List<Evidence> evidence, String reviewedBy, String reviewedAt,
                            @JsonInclude(JsonInclude.Include.NON_NULL) List<String> candidatesShown) {}

    public record NodeRef(String id, String name, NodeType type) {}

    public record GraphResponse(String scope, List<GraphNode> nodes, List<GraphEdge> edges) {}

    public record NeighborhoodResponse(String scope, NodeRef center, List<GraphNode> nodes, List<GraphEdge> edges) {}

    public record PathResponse(NodeRef from, NodeRef to, List<String> path, List<String> pathNames, List<String> edgeIds) {}

    public record CyclesResponse(List<List<String>> cycles) {}

    public record TraversalItem(String id, String name, NodeType type, int depth, RelType via, RelStatus status,
                                List<String> path, List<String> pathNames) {}

    public record TraversalResponse(NodeRef anchor, String direction, Integer depth, String scope,
                                    List<TraversalItem> direct, List<TraversalItem> indirect, int count,
                                    List<String> edgeIds, String message) {}

    public static final String NOT_FOUND_MESSAGE = "등록된 관계에서는 발견되지 않았습니다. 안전하다고 확정할 수는 없습니다.";
}
