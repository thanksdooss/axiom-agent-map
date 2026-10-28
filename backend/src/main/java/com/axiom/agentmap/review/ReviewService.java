package com.axiom.agentmap.review;

import com.axiom.agentmap.api.GraphDtos.Evidence;
import com.axiom.agentmap.api.GraphDtos.GraphEdge;
import com.axiom.agentmap.api.ReviewDtos.PendingEntity;
import com.axiom.agentmap.api.ReviewDtos.PendingResolve;
import com.axiom.agentmap.api.ReviewDtos.RelationshipPatch;
import com.axiom.agentmap.config.DataPaths;
import com.axiom.agentmap.domain.AgentEntity;
import com.axiom.agentmap.domain.DataResourceEntity;
import com.axiom.agentmap.domain.EvidenceEntity;
import com.axiom.agentmap.domain.NodeType;
import com.axiom.agentmap.domain.PendingEntityRecord;
import com.axiom.agentmap.domain.RelStatus;
import com.axiom.agentmap.domain.RelType;
import com.axiom.agentmap.domain.RelationshipEntity;
import com.axiom.agentmap.domain.Scope;
import com.axiom.agentmap.graph.GraphService;
import com.axiom.agentmap.graph.GraphStore;
import com.axiom.agentmap.graph.GraphView;
import com.axiom.agentmap.repository.AgentRepository;
import com.axiom.agentmap.repository.DataResourceRepository;
import com.axiom.agentmap.repository.PendingEntityRepository;
import com.axiom.agentmap.repository.RelationshipRepository;
import com.axiom.agentmap.text.Texts;
import com.axiom.agentmap.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 검토: 관계 확정·반려·대상 변경, 미등록 대상 처리 */
@Service
public class ReviewService {
    public static final String DEFAULT_REVIEWER = "검토자";

    private final RelationshipRepository relationships;
    private final PendingEntityRepository pending;
    private final AgentRepository agents;
    private final DataResourceRepository data;
    private final GraphService graph;
    private final IdGenerator ids;
    private final DataPaths paths;

    public ReviewService(RelationshipRepository relationships, PendingEntityRepository pending, AgentRepository agents,
                         DataResourceRepository data, GraphService graph, IdGenerator ids, DataPaths paths) {
        this.relationships = relationships;
        this.pending = pending;
        this.agents = agents;
        this.data = data;
        this.graph = graph;
        this.ids = ids;
        this.paths = paths;
    }

    @Transactional(readOnly = true)
    public List<GraphEdge> list(String status, String type, String nodeId) {
        GraphView g = graph.view(Scope.ALL);
        Set<RelStatus> statuses = EnumSet.noneOf(RelStatus.class);
        if (!Texts.isBlank(status)) {
            for (String s : status.split(",")) statuses.add(RelStatus.valueOf(s.strip().toUpperCase()));
        } else {
            statuses.addAll(EnumSet.of(RelStatus.SAMPLE, RelStatus.CANDIDATE, RelStatus.CONFIRMED));
        }
        RelType t = Texts.isBlank(type) ? null : RelType.valueOf(type.strip().toUpperCase());
        List<GraphEdge> out = new ArrayList<>();
        for (GraphView.Rel r : g.allRels()) {
            if (!statuses.contains(r.status())) continue;
            if (t != null && r.type() != t) continue;
            if (!Texts.isBlank(nodeId) && !nodeId.equals(r.source()) && !nodeId.equals(r.target())) continue;
            out.add(graph.edge(g, r));
        }
        return out;
    }

    @Transactional
    public GraphEdge patch(String id, RelationshipPatch body) {
        if (body == null || Texts.isBlank(body.action())) throw ApiException.badRequest("action(confirm|reject)이 필요합니다.");
        RelationshipEntity r = relationships.findById(id).orElseThrow(() -> ApiException.notFound("없는 관계입니다: " + id));
        String reviewer = Texts.isBlank(body.reviewer()) ? DEFAULT_REVIEWER : body.reviewer().strip();
        String action = body.action().strip().toLowerCase();
        switch (action) {
            case "confirm" -> {
                if (!Texts.isBlank(body.targetId()) && !body.targetId().equals(r.targetId)) {
                    r = retarget(r, body.targetId().strip());
                }
                r.status = RelStatus.CONFIRMED;
            }
            case "reject" -> r.status = RelStatus.REJECTED;
            default -> throw ApiException.badRequest("action은 confirm 또는 reject입니다: " + body.action());
        }
        r.reviewedBy = reviewer;
        r.reviewedAt = paths.today();
        relationships.save(r);
        if (r.type == RelType.BELONGS_TO || r.type == RelType.HAS_WORK_TYPE) syncAgentColumns(r);
        relationships.flush();
        return edgeOf(r.id);
    }

    /** 모호한 이름에서 다른 후보를 고른 경우: 대상을 바꿉니다. 같은 관계가 이미 있으면 근거를 그쪽으로 합칩니다. */
    private RelationshipEntity retarget(RelationshipEntity r, String newTarget) {
        NodeType want = r.type.range();
        if (NodeType.ofId(newTarget) != want) {
            throw ApiException.badRequest(r.type + " 관계의 대상은 " + want.code() + "여야 합니다: " + newTarget);
        }
        GraphView g = graph.view(Scope.ALL);
        if (!g.has(newTarget)) throw ApiException.notFound("등록되지 않은 대상입니다: " + newTarget);
        if (newTarget.equals(r.sourceId)) throw ApiException.badRequest("출발과 도착이 같은 관계(자기 참조)는 저장할 수 없습니다.");
        GraphView.Rel existing = g.find(r.type, r.sourceId, newTarget);
        if (existing != null && !existing.id().equals(r.id)) {
            RelationshipEntity target = relationships.findById(existing.id()).orElseThrow();
            for (EvidenceEntity e : List.copyOf(r.evidence)) {
                target.addEvidence(new EvidenceEntity(e.file, e.locator, e.text, e.matchNote, e.directionNote));
            }
            relationships.delete(r);
            relationships.flush();
            return target;
        }
        r.targetId = newTarget;
        return r;
    }

    private void syncAgentColumns(RelationshipEntity r) {
        agents.findById(r.sourceId).ifPresent(a -> {
            boolean on = r.status == RelStatus.CONFIRMED;
            if (r.type == RelType.BELONGS_TO && on) a.departmentId = r.targetId;
            if (r.type == RelType.HAS_WORK_TYPE && on) a.workTypeId = r.targetId;
            agents.save(a);
        });
    }

    private GraphEdge edgeOf(String relId) {
        GraphView g = graph.view(Scope.ALL);
        RelationshipEntity e = relationships.findById(relId).orElseThrow();
        return graph.edge(g, GraphStore.toRel(e));
    }

    // ------------------------------------------------------------------ 미등록 대상

    @Transactional(readOnly = true)
    public List<PendingEntity> pendingList(String state) {
        GraphView g = graph.view(Scope.ALL);
        List<PendingEntityRecord> all = new ArrayList<>(pending.findAll());
        all.sort(Comparator.comparing(p -> p.id));
        List<PendingEntity> out = new ArrayList<>();
        for (PendingEntityRecord p : all) {
            if (!Texts.isBlank(state) && !state.equalsIgnoreCase(p.state)) continue;
            out.add(toDto(g, p));
        }
        return out;
    }

    public PendingEntity toDto(GraphView g, PendingEntityRecord p) {
        return new PendingEntity(p.id, p.rawLabel, p.kind, p.mentionedBy, p.mentionedBy == null ? null : g.name(p.mentionedBy),
                p.reason, new Evidence(p.evidenceFile, p.evidenceLocator, p.evidenceText, null, null), p.state);
    }

    @Transactional
    public PendingEntity resolve(String id, PendingResolve body) {
        if (body == null || Texts.isBlank(body.action())) throw ApiException.badRequest("action(register|map|dismiss)이 필요합니다.");
        PendingEntityRecord p = pending.findById(id).orElseThrow(() -> ApiException.notFound("없는 미등록 대상입니다: " + id));
        if (!"OPEN".equals(p.state)) throw ApiException.conflict("이미 처리된 항목입니다: " + p.state);
        switch (body.action().strip().toLowerCase()) {
            case "register" -> {
                String newId = register(p, Texts.isBlank(body.name()) ? p.rawLabel : body.name().strip());
                linkMention(p, newId, "미등록 대상을 새로 등록함 — " + p.rawLabel);
                p.resolvedToId = newId;
                p.state = "REGISTERED";
            }
            case "map" -> {
                if (Texts.isBlank(body.mapToId())) throw ApiException.badRequest("map에는 mapToId가 필요합니다.");
                String target = body.mapToId().strip();
                if (!graph.view(Scope.ALL).has(target)) throw ApiException.notFound("등록되지 않은 대상입니다: " + target);
                linkMention(p, target, "미등록 표기 '" + p.rawLabel + "'를 기존 대상에 매핑함");
                p.resolvedToId = target;
                p.state = "MAPPED";
            }
            case "dismiss" -> p.state = "DISMISSED";
            default -> throw ApiException.badRequest("action은 register, map, dismiss 중 하나입니다: " + body.action());
        }
        pending.save(p);
        pending.flush();
        return toDto(graph.view(Scope.ALL), p);
    }

    private String register(PendingEntityRecord p, String name) {
        if ("DataAsset".equals(p.kind)) {
            DataResourceEntity d = new DataResourceEntity();
            d.id = ids.nextData();
            d.name = name;
            d.category = "미분류";
            d.altLabels = name.equals(p.rawLabel) ? "" : p.rawLabel;
            data.save(d);
            data.flush();
            return d.id;
        }
        AgentEntity a = new AgentEntity();
        a.id = ids.nextAgent();
        a.name = name;
        a.status = "운영";
        a.owners = "";
        a.description = "미등록 대상에서 등록 (" + p.kind + ")";
        a.sourceFile = Optional.ofNullable(p.evidenceFile).orElse("미등록 대상");
        a.reviewFlagsJson = "[]";
        agents.save(a);
        agents.flush();
        return a.id;
    }

    /** 언급한 Agent → 대상 관계를 확정 상태로 만듭니다(사람이 처리한 결과). */
    private void linkMention(PendingEntityRecord p, String targetId, String note) {
        if (p.mentionedBy == null) return;
        NodeType tt = NodeType.ofId(targetId);
        if (tt == null) return;
        RelType type = RelType.fromAgentTo(tt);
        GraphView g = graph.view(Scope.ALL);
        GraphView.Rel existing = g.find(type, p.mentionedBy, targetId);
        RelationshipEntity r = existing == null ? null : relationships.findById(existing.id()).orElse(null);
        if (r == null) {
            r = new RelationshipEntity();
            r.id = ids.nextRelationship();
            r.type = type;
            r.sourceId = p.mentionedBy;
            r.targetId = targetId;
            r.reviewReason = note;
        }
        r.status = RelStatus.CONFIRMED;
        r.reviewedBy = DEFAULT_REVIEWER;
        r.reviewedAt = paths.today();
        r.addEvidence(new EvidenceEntity(p.evidenceFile, p.evidenceLocator, p.evidenceText, null, null));
        relationships.save(r);
        relationships.flush();
    }
}
