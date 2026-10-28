package com.axiom.agentmap.dashboard;

import com.axiom.agentmap.api.GraphDtos.NodeRef;
import com.axiom.agentmap.domain.AgentEntity;
import com.axiom.agentmap.domain.NodeType;
import com.axiom.agentmap.domain.RelStatus;
import com.axiom.agentmap.domain.RelType;
import com.axiom.agentmap.domain.Scope;
import com.axiom.agentmap.graph.GraphService;
import com.axiom.agentmap.graph.GraphView;
import com.axiom.agentmap.repository.AgentRepository;
import com.axiom.agentmap.repository.PendingEntityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 경영진 대시보드 지표와 규칙 기반 인사이트 */
@Service
public class DashboardService {
    public static final String UNASSIGNED = "(미확정)";

    public record DepartmentCount(String id, String name, int count) {}
    public record HighSensitivity(int count, double share, List<NodeRef> agents) {}
    public record PlatformCount(String id, String name, int count) {}
    public record Depended(String id, String name, int dependents) {}
    public record Insight(String kind, String title, String description, List<String> nodeIds) {}
    public record Dashboard(String scope, int totalAgents, Map<String, Integer> agentsByStatus,
                            List<DepartmentCount> agentsByDepartment, HighSensitivity highSensitivity,
                            List<PlatformCount> platformConcentration, Map<String, Integer> dataSensitivity,
                            List<Depended> topDepended, Map<String, Integer> relationshipsByStatus,
                            int pendingEntities, List<Insight> insights) {}

    private final GraphService graph;
    private final AgentRepository agents;
    private final PendingEntityRepository pending;
    private final SimilarityService similarity;

    public DashboardService(GraphService graph, AgentRepository agents, PendingEntityRepository pending,
                            SimilarityService similarity) {
        this.graph = graph;
        this.agents = agents;
        this.pending = pending;
        this.similarity = similarity;
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard(Scope scope) {
        GraphView g = graph.view(scope);
        List<String> ids = g.agentIds();

        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (String s : List.of("운영", "파일럿", "중단")) byStatus.put(s, 0);
        List<AgentEntity> all = new ArrayList<>(agents.findAll());
        all.sort(Comparator.comparing(a -> a.id));
        for (AgentEntity a : all) byStatus.merge(a.status == null ? "(미정)" : a.status, 1, Integer::sum);

        List<DepartmentCount> byDept = byDepartment(g, ids);
        List<String> high = highSensitivityAgents(g);
        HighSensitivity hs = new HighSensitivity(high.size(),
                ids.isEmpty() ? 0 : Math.round(high.size() * 1000.0 / ids.size()) / 1000.0,
                high.stream().map(id -> graph.ref(g, id)).toList());

        Map<String, Integer> sens = new LinkedHashMap<>();
        sens.put("상", 0);
        sens.put("중", 0);
        sens.put("하", 0);
        for (GraphView.Node n : g.nodes().values()) {
            if (n.type() == NodeType.DATA_ASSET && n.sensitivity() != null) sens.merge(n.sensitivity(), 1, Integer::sum);
        }

        Map<String, Integer> relStatus = new LinkedHashMap<>();
        for (RelStatus s : List.of(RelStatus.SAMPLE, RelStatus.CANDIDATE, RelStatus.CONFIRMED)) relStatus.put(s.name(), 0);
        for (GraphView.Rel r : g.allRels()) {
            if (r.status() != RelStatus.REJECTED) relStatus.merge(r.status().name(), 1, Integer::sum);
        }

        int openPending = (int) pending.findAll().stream().filter(p -> "OPEN".equals(p.state)).count();
        List<PlatformCount> platforms = platformConcentration(g);
        List<Depended> top = topDepended(g, 6);
        List<Insight> insights = insights(g, platforms, relStatus.get("CANDIDATE"));
        return new Dashboard(scope.code(), ids.size(), byStatus, byDept, hs, platforms, sens, top, relStatus,
                openPending, insights);
    }

    /** 소속 관계로 부서별 Agent 수 (소속 미확정은 id null, name "(미확정)"), 이름순 */
    public static List<DepartmentCount> byDepartment(GraphView g, List<String> agentIds) {
        Map<String, Integer> counts = new TreeMap<>();
        Map<String, String> idOf = new LinkedHashMap<>();
        for (String a : agentIds) {
            List<String> deps = g.targets(a, RelType.BELONGS_TO);
            String name = deps.isEmpty() ? UNASSIGNED : g.name(deps.get(0));
            if (!deps.isEmpty()) idOf.put(name, deps.get(0));
            counts.merge(name, 1, Integer::sum);
        }
        List<DepartmentCount> out = new ArrayList<>();
        counts.forEach((name, c) -> out.add(new DepartmentCount(idOf.get(name), name, c)));
        return out;
    }

    /** 민감도 '상' 데이터에 접근하는 Agent (ID 순) */
    public static List<String> highSensitivityAgents(GraphView g) {
        List<String> out = new ArrayList<>();
        for (String a : g.agentIds()) {
            boolean high = g.targets(a, RelType.ACCESSES_DATA).stream()
                    .anyMatch(d -> g.node(d) != null && "상".equals(g.node(d).sensitivity()));
            if (high) out.add(a);
        }
        return out;
    }

    public static List<String> highSensitivityMultiPlatform(GraphView g, int minPlatforms) {
        return highSensitivityAgents(g).stream()
                .filter(a -> g.targets(a, RelType.USES_PLATFORM).size() >= minPlatforms).toList();
    }

    /** 플랫폼별 연동 Agent 수, 내림차순(같으면 이름순) */
    public static List<PlatformCount> platformConcentration(GraphView g) {
        Map<String, Integer> c = new LinkedHashMap<>();
        for (GraphView.Rel r : g.rels()) if (r.type() == RelType.USES_PLATFORM) c.merge(r.target(), 1, Integer::sum);
        List<PlatformCount> out = new ArrayList<>();
        c.forEach((id, n) -> out.add(new PlatformCount(id, g.name(id), n)));
        out.sort(Comparator.comparingInt((PlatformCount p) -> -p.count()).thenComparing(PlatformCount::name));
        return out;
    }

    /** 결과사용 역방향(깊이 제한 없음)으로 의존하는 Agent 수 상위 N (같으면 이름순) */
    public static List<Depended> topDepended(GraphView g, int n) {
        List<Depended> rows = new ArrayList<>();
        for (String a : g.agentIds()) {
            int c = g.impact(a, GraphView.UNLIMITED).size();
            if (c > 0) rows.add(new Depended(a, g.name(a), c));
        }
        rows.sort(Comparator.comparingInt((Depended d) -> -d.dependents()).thenComparing(Depended::name));
        return rows.size() > n ? rows.subList(0, n) : rows;
    }

    private List<Insight> insights(GraphView g, List<PlatformCount> platforms, int candidates) {
        List<Insight> out = new ArrayList<>();
        List<String> multi = highSensitivityMultiPlatform(g, 3);
        if (!multi.isEmpty()) {
            out.add(new Insight("HIGH_SENS_MULTI_PLATFORM",
                    "고민감도 데이터 + 플랫폼 3개 이상 Agent " + multi.size() + "개",
                    "민감도 '상' 데이터에 접근하면서 3개 이상 플랫폼과 연동되어 관리 복잡도가 높습니다: "
                            + String.join(", ", multi.stream().map(g::name).toList()), multi));
        }
        for (SimilarityService.Pair p : similarity.topPairs()) {
            out.add(new Insight("DUPLICATE_CANDIDATE", "중복 개발 후보: " + p.aName() + " ↔ " + p.bName(),
                    String.format("이름·설명 유사도 %.2f%s — 같은 역할을 따로 만들고 있는지 확인하세요.", p.score(),
                            p.sameWorkType() ? ", 업무 유형 같음" : ""), List.of(p.a(), p.b())));
        }
        if (!platforms.isEmpty()) {
            int max = platforms.get(0).count();
            List<PlatformCount> tops = platforms.stream().filter(p -> p.count() == max).toList();
            out.add(new Insight("PLATFORM_CONCENTRATION",
                    "연동 집중 플랫폼: " + String.join("·", tops.stream().map(PlatformCount::name).toList()),
                    "각각 Agent " + max + "개가 연동되어 있어 장애·교체 시 영향이 큽니다(단일 장애점).",
                    tops.stream().map(PlatformCount::id).toList()));
        }
        if (candidates > 0) {
            List<String> nodes = g.allRels().stream().filter(r -> r.status() == RelStatus.CANDIDATE)
                    .map(GraphView.Rel::source).distinct().sorted().toList();
            out.add(new Insight("REVIEW_BACKLOG", "검토 대기 관계 " + candidates + "건",
                    "표기가 모호해 사람 확인을 기다리는 관계입니다. 확정 기준 통계에는 빠져 있습니다.", nodes));
        }
        for (List<String> c : g.cycles()) {
            out.add(new Insight("CYCLE", "결과사용 순환: " + String.join(" → ", c.stream().map(g::name).toList()),
                    "서로의 결과를 쓰는 순환이 있습니다. 변경 시 무한 반영·장애 전파를 점검하세요.", c));
        }
        return out;
    }
}
