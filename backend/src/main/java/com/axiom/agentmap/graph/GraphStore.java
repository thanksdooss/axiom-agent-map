package com.axiom.agentmap.graph;

import com.axiom.agentmap.api.GraphDtos.Evidence;
import com.axiom.agentmap.domain.AgentEntity;
import com.axiom.agentmap.domain.DataResourceEntity;
import com.axiom.agentmap.domain.DepartmentEntity;
import com.axiom.agentmap.domain.EvidenceEntity;
import com.axiom.agentmap.domain.NodeType;
import com.axiom.agentmap.domain.PlatformEntity;
import com.axiom.agentmap.domain.RelationshipEntity;
import com.axiom.agentmap.domain.Scope;
import com.axiom.agentmap.domain.WorkTypeEntity;
import com.axiom.agentmap.repository.AgentRepository;
import com.axiom.agentmap.repository.DataResourceRepository;
import com.axiom.agentmap.repository.DepartmentRepository;
import com.axiom.agentmap.repository.PlatformRepository;
import com.axiom.agentmap.repository.RelationshipRepository;
import com.axiom.agentmap.repository.WorkTypeRepository;
import com.axiom.agentmap.vocabulary.Lexicon;
import com.axiom.agentmap.vocabulary.Vocabulary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 저장소(JPA) → 그래프 스냅샷·표기 사전 변환. 그래프 알고리즘은 GraphView에만 있으므로
 * 저장소를 PostgreSQL·Neo4j로 바꾸면 이 클래스만 고치면 됩니다.
 */
@Component
public class GraphStore {
    private final AgentRepository agents;
    private final DataResourceRepository data;
    private final PlatformRepository platforms;
    private final DepartmentRepository departments;
    private final WorkTypeRepository workTypes;
    private final RelationshipRepository relationships;
    private final Vocabulary vocabulary;

    public GraphStore(AgentRepository agents, DataResourceRepository data, PlatformRepository platforms,
                      DepartmentRepository departments, WorkTypeRepository workTypes,
                      RelationshipRepository relationships, Vocabulary vocabulary) {
        this.agents = agents;
        this.data = data;
        this.platforms = platforms;
        this.departments = departments;
        this.workTypes = workTypes;
        this.relationships = relationships;
        this.vocabulary = vocabulary;
    }

    @Transactional(readOnly = true)
    public GraphView view(Scope scope) {
        Map<String, GraphView.Node> nodes = new LinkedHashMap<>();
        for (AgentEntity a : agents.findAll()) {
            nodes.put(a.id, new GraphView.Node(a.id, NodeType.AGENT, a.name, null, null, a.status));
        }
        for (DataResourceEntity d : data.findAll()) {
            nodes.put(d.id, new GraphView.Node(d.id, NodeType.DATA_ASSET, d.name, d.category, d.sensitivity, null));
        }
        for (PlatformEntity p : platforms.findAll()) {
            nodes.put(p.id, new GraphView.Node(p.id, NodeType.PLATFORM, p.name, p.category, null, null));
        }
        for (DepartmentEntity d : departments.findAll()) {
            nodes.put(d.id, new GraphView.Node(d.id, NodeType.DEPARTMENT, d.name, null, null, null));
        }
        for (WorkTypeEntity w : workTypes.findAll()) {
            nodes.put(w.id, new GraphView.Node(w.id, NodeType.WORK_TYPE, w.name, null, null, null));
        }
        List<GraphView.Rel> rels = new ArrayList<>();
        for (RelationshipEntity r : relationships.findAllWithEvidence()) rels.add(toRel(r));
        return new GraphView(scope, nodes, rels);
    }

    public static GraphView.Rel toRel(RelationshipEntity r) {
        List<Evidence> ev = new ArrayList<>();
        for (EvidenceEntity e : r.evidence) ev.add(new Evidence(e.file, e.locator, e.text, e.matchNote, e.directionNote));
        List<String> cands = r.candidatesShown == null || r.candidatesShown.isBlank() ? null
                : Arrays.stream(r.candidatesShown.split(",\\s*")).toList();
        return new GraphView.Rel(r.id, r.type, r.sourceId, r.targetId, r.status, r.reviewReason, r.reviewedBy,
                r.reviewedAt, List.copyOf(ev), cands);
    }

    /** 현재 저장된 노드 이름·별칭 + 어휘의 검토 필요·미등록 표기로 만든 표기 사전 */
    @Transactional(readOnly = true)
    public Lexicon lexicon() {
        List<Lexicon.NodeLabel> dep = new ArrayList<>();
        for (DepartmentEntity d : departments.findAll()) dep.add(new Lexicon.NodeLabel(d.id, d.name, split(d.altLabels)));
        List<Lexicon.NodeLabel> wt = new ArrayList<>();
        for (WorkTypeEntity w : workTypes.findAll()) wt.add(new Lexicon.NodeLabel(w.id, w.name, split(w.altLabels)));
        List<Lexicon.NodeLabel> pl = new ArrayList<>();
        for (PlatformEntity p : platforms.findAll()) pl.add(new Lexicon.NodeLabel(p.id, p.name, split(p.altLabels)));
        List<Lexicon.NodeLabel> da = new ArrayList<>();
        for (DataResourceEntity d : data.findAll()) da.add(new Lexicon.NodeLabel(d.id, d.name, split(d.altLabels)));
        List<Lexicon.NodeLabel> ag = new ArrayList<>();
        for (AgentEntity a : agents.findAll()) ag.add(new Lexicon.NodeLabel(a.id, a.name, List.of()));
        dep.sort((x, y) -> x.id().compareTo(y.id()));
        wt.sort((x, y) -> x.id().compareTo(y.id()));
        pl.sort((x, y) -> x.id().compareTo(y.id()));
        da.sort((x, y) -> x.id().compareTo(y.id()));
        ag.sort((x, y) -> x.id().compareTo(y.id()));
        return new Lexicon(vocabulary, dep, wt, pl, da, ag);
    }

    public static List<String> split(String joined) {
        if (joined == null || joined.isBlank()) return List.of();
        return Arrays.stream(joined.split(";\\s*")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
