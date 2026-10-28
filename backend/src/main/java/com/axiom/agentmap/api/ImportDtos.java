package com.axiom.agentmap.api;

import com.axiom.agentmap.api.AgentDtos.ImportAgent;
import com.axiom.agentmap.api.GraphDtos.Evidence;
import com.axiom.agentmap.api.ReviewDtos.PendingEntity;
import com.axiom.agentmap.domain.RelStatus;
import com.axiom.agentmap.domain.RelType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public final class ImportDtos {
    private ImportDtos() {}

    public record FileSummary(String name, String format, int agents, String parser) {}

    /** GraphEdge + duplicateOf (저장소에 이미 있는 같은 관계 ID) */
    public record ImportEdge(String id, RelType type, String typeLabel, String source, String target,
                             String sourceName, String targetName, RelStatus status, String reviewReason,
                             List<Evidence> evidence, String reviewedBy, String reviewedAt,
                             @JsonInclude(JsonInclude.Include.NON_NULL) List<String> candidatesShown,
                             String duplicateOf) {}

    public record SpecialCase(@JsonProperty("case") String caseName, String agentId,
                              @JsonInclude(JsonInclude.Include.NON_NULL) String raw, String expected,
                              Evidence evidence,
                              @JsonInclude(JsonInclude.Include.NON_NULL) List<String> candidates,
                              @JsonInclude(JsonInclude.Include.NON_NULL) RelType type,
                              @JsonInclude(JsonInclude.Include.NON_NULL) String target,
                              @JsonInclude(JsonInclude.Include.NON_NULL) String reason) {}

    public record ImportPreview(String batchId, List<FileSummary> files, List<ImportAgent> agents,
                                List<ImportEdge> relationships, List<PendingEntity> pending,
                                List<SpecialCase> specialCases) {}

    public record CommitResult(int addedAgents, int addedRelationships, int mergedEvidence, int pending) {}
}
