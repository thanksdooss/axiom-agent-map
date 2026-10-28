package com.axiom.agentmap.api;

import com.axiom.agentmap.api.GraphDtos.Evidence;

public final class ReviewDtos {
    private ReviewDtos() {}

    public record RelationshipPatch(String action, String reviewer, String targetId) {}

    public record PendingEntity(String id, String rawLabel, String kind, String mentionedBy, String mentionedByName,
                                String reason, Evidence evidence, String state) {}

    public record PendingResolve(String action, String mapToId, String name) {}
}
