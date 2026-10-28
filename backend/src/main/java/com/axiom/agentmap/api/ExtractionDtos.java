package com.axiom.agentmap.api;

import com.axiom.agentmap.api.GraphDtos.NodeRef;
import com.axiom.agentmap.domain.RelType;

import java.util.List;

public final class ExtractionDtos {
    private ExtractionDtos() {}

    public enum Outcome { NEW, DUPLICATE, AMBIGUOUS, UNREGISTERED, SELF_REFERENCE, NEGATED, FUTURE, NO_SUBJECT }

    public record ExtractionRequest(String text, String contextAgentId) {}

    public record Subject(String raw, NodeRef resolved) {}

    public record Item(RelType type, String sourceId, String targetId, String targetRaw, Outcome outcome,
                       String existingRelId, List<NodeRef> options, String evidenceText, String reason) {}

    public record ExtractionResult(String engine, Subject subject, String action, List<Item> items) {}

    public record ApplyRequest(List<Item> items, String reviewer) {}
}
