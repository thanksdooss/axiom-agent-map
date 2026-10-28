package com.axiom.agentmap.graph;

import com.axiom.agentmap.api.GraphDtos.Evidence;
import com.axiom.agentmap.domain.NodeType;
import com.axiom.agentmap.domain.RelStatus;
import com.axiom.agentmap.domain.RelType;
import com.axiom.agentmap.domain.Scope;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 한 scope의 그래프 스냅샷과 탐색 알고리즘. build_dataset.py의 Graph 클래스와 같은 규칙입니다.
 * <ul>
 *   <li>BFS, 방문한 노드는 다시 넣지 않음(순환 안전)</li>
 *   <li>같은 깊이에서는 ID 오름차순</li>
 *   <li>의존(정방향): 결과사용·데이터사용을 따라가고 데이터 노드에서 멈춤</li>
 *   <li>영향(역방향): 첫 단계는 기준 노드 종류에 맞는 관계, 이후는 결과사용 역방향</li>
 *   <li>REJECTED는 어느 scope에도 없음</li>
 * </ul>
 * 저장소와 무관한 순수 객체라 PostgreSQL·Neo4j로 바꿔도 이 클래스는 그대로 씁니다.
 */
public final class GraphView {
    public record Node(String id, NodeType type, String label, String category, String sensitivity,
                       String agentStatus) {}

    public record Rel(String id, RelType type, String source, String target, RelStatus status, String reviewReason,
                      String reviewedBy, String reviewedAt, List<Evidence> evidence, List<String> candidatesShown) {}

    /** 탐색 결과 한 칸: 도달한 노드, 깊이, 마지막 관계, 기준 노드부터의 경로(ID)와 경로 관계 */
    public record Hop(String id, int depth, Rel via, List<String> path, List<Rel> pathRels) {}

    public static final int UNLIMITED = Integer.MAX_VALUE;

    private final Scope scope;
    private final Map<String, Node> nodes;
    private final List<Rel> allRels;
    private final List<Rel> rels;
    private final Map<String, List<Rel>> out = new HashMap<>();
    private final Map<String, List<Rel>> inc = new HashMap<>();

    public GraphView(Scope scope, Map<String, Node> nodes, List<Rel> allRels) {
        this.scope = scope;
        this.nodes = Collections.unmodifiableMap(new TreeMap<>(nodes));
        List<Rel> sortedAll = new ArrayList<>(allRels);
        sortedAll.sort(Comparator.comparing(Rel::id));
        this.allRels = Collections.unmodifiableList(sortedAll);
        List<Rel> inScope = new ArrayList<>();
        for (Rel r : sortedAll) {
            if (!scope.includes(r.status())) continue;
            if (!this.nodes.containsKey(r.source()) || !this.nodes.containsKey(r.target())) continue;
            inScope.add(r);
            out.computeIfAbsent(r.source(), k -> new ArrayList<>()).add(r);
            inc.computeIfAbsent(r.target(), k -> new ArrayList<>()).add(r);
        }
        this.rels = Collections.unmodifiableList(inScope);
    }

    public Scope scope() { return scope; }
    public Map<String, Node> nodes() { return nodes; }
    public Node node(String id) { return nodes.get(id); }
    public boolean has(String id) { return nodes.containsKey(id); }
    /** 이 scope의 관계 (REJECTED 제외, ID 순) */
    public List<Rel> rels() { return rels; }
    /** 모든 상태의 관계 (ID 순) */
    public List<Rel> allRels() { return allRels; }

    public String name(String id) {
        Node n = nodes.get(id);
        return n == null ? id : n.label();
    }

    public List<Rel> out(String id) { return out.getOrDefault(id, List.of()); }
    public List<Rel> in(String id) { return inc.getOrDefault(id, List.of()); }

    public int degree(String id) { return out(id).size() + in(id).size(); }

    public List<String> agentIds() {
        List<String> ids = new ArrayList<>();
        for (Node n : nodes.values()) if (n.type() == NodeType.AGENT) ids.add(n.id());
        return ids;
    }

    /** source에서 t 종류로 가는 대상 ID (오름차순) */
    public List<String> targets(String source, RelType t) {
        List<String> ids = new ArrayList<>();
        for (Rel r : out(source)) if (r.type() == t) ids.add(r.target());
        ids.sort(String::compareTo);
        return ids;
    }

    /** 정방향 의존: anchor가 쓰는 Agent·데이터. depth는 UNLIMITED 가능. */
    public List<Hop> dependencies(String anchor, int depth) {
        Set<String> seen = new HashSet<>(Set.of(anchor));
        List<Hop> res = new ArrayList<>();
        Deque<Hop> q = new ArrayDeque<>();
        q.add(new Hop(anchor, 0, null, List.of(anchor), List.of()));
        while (!q.isEmpty()) {
            Hop h = q.poll();
            if (h.depth() == depth) continue;
            List<Rel> edges = new ArrayList<>(out(h.id()));
            edges.sort(Comparator.comparing(Rel::target));
            for (Rel r : edges) {
                if ((r.type() != RelType.USES_RESULT_OF && r.type() != RelType.ACCESSES_DATA) || seen.contains(r.target())) {
                    continue;
                }
                seen.add(r.target());
                Hop next = extend(h, r.target(), r);
                res.add(next);
                if (r.type() == RelType.USES_RESULT_OF) q.add(next);
            }
        }
        return res;
    }

    /** 첫 단계 관계 종류: Agent→결과사용, 데이터→데이터사용, 플랫폼→사용 플랫폼 (부서·업무 유형은 소속·업무 성격) */
    public static RelType firstImpactType(String anchor) {
        NodeType t = NodeType.ofId(anchor);
        if (t == null) return RelType.USES_RESULT_OF;
        return switch (t) {
            case AGENT -> RelType.USES_RESULT_OF;
            case DATA_ASSET -> RelType.ACCESSES_DATA;
            case PLATFORM -> RelType.USES_PLATFORM;
            case DEPARTMENT -> RelType.BELONGS_TO;
            case WORK_TYPE -> RelType.HAS_WORK_TYPE;
        };
    }

    /** 역방향 영향: anchor를 바꾸면 점검할 Agent */
    public List<Hop> impact(String anchor, int depth) {
        RelType first = firstImpactType(anchor);
        Set<String> seen = new HashSet<>(Set.of(anchor));
        List<Hop> res = new ArrayList<>();
        Deque<Hop> q = new ArrayDeque<>();
        q.add(new Hop(anchor, 0, null, List.of(anchor), List.of()));
        while (!q.isEmpty()) {
            Hop h = q.poll();
            if (h.depth() == depth) continue;
            RelType want = h.depth() == 0 ? first : RelType.USES_RESULT_OF;
            List<Rel> edges = new ArrayList<>(in(h.id()));
            edges.sort(Comparator.comparing(Rel::source));
            for (Rel r : edges) {
                if (r.type() != want || seen.contains(r.source())) continue;
                seen.add(r.source());
                Hop next = extend(h, r.source(), r);
                res.add(next);
                q.add(next);
            }
        }
        return res;
    }

    private static Hop extend(Hop h, String id, Rel r) {
        List<String> p = new ArrayList<>(h.path());
        p.add(id);
        List<Rel> pr = new ArrayList<>(h.pathRels());
        pr.add(r);
        return new Hop(id, h.depth() + 1, r, List.copyOf(p), List.copyOf(pr));
    }

    /** 결과사용 정방향 최단 경로. 없으면 null. */
    public Hop path(String src, String dst) {
        Set<String> seen = new HashSet<>(Set.of(src));
        Deque<Hop> q = new ArrayDeque<>();
        q.add(new Hop(src, 0, null, List.of(src), List.of()));
        while (!q.isEmpty()) {
            Hop h = q.poll();
            if (h.id().equals(dst)) return h;
            List<Rel> edges = new ArrayList<>(out(h.id()));
            edges.sort(Comparator.comparing(Rel::target));
            for (Rel r : edges) {
                if (r.type() == RelType.USES_RESULT_OF && !seen.contains(r.target())) {
                    seen.add(r.target());
                    q.add(extend(h, r.target(), r));
                }
            }
        }
        return null;
    }

    /** 결과사용 순환. 각 순환은 가장 작은 ID부터 시작하도록 회전, 전체는 사전순. */
    public List<List<String>> cycles() {
        Set<List<String>> found = new TreeSet<>(GraphView::compareLists);
        Set<String> starts = new TreeSet<>();
        for (Rel r : rels) if (r.type() == RelType.USES_RESULT_OF) starts.add(r.source());
        for (String s : starts) {
            Deque<List<String>> stack = new ArrayDeque<>();
            stack.push(List.of(s));
            while (!stack.isEmpty()) {
                List<String> p = stack.pop();
                String n = p.get(p.size() - 1);
                for (Rel r : out(n)) {
                    if (r.type() != RelType.USES_RESULT_OF) continue;
                    String t = r.target();
                    if (t.equals(s)) {
                        int k = p.indexOf(Collections.min(p));
                        List<String> cyc = new ArrayList<>(p.subList(k, p.size()));
                        cyc.addAll(p.subList(0, k));
                        found.add(List.copyOf(cyc));
                    } else if (!p.contains(t) && p.size() < 10) {
                        List<String> np = new ArrayList<>(p);
                        np.add(t);
                        stack.push(np);
                    }
                }
            }
        }
        return new ArrayList<>(found);
    }

    private static int compareLists(List<String> a, List<String> b) {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int c = a.get(i).compareTo(b.get(i));
            if (c != 0) return c;
        }
        return Integer.compare(a.size(), b.size());
    }

    /** 양방향 이웃 (깊이 depth까지 모든 관계 종류) → 노드 ID와 거리 */
    public Map<String, Integer> neighborhood(String center, int depth) {
        Map<String, Integer> dist = new TreeMap<>();
        dist.put(center, 0);
        Deque<String> q = new ArrayDeque<>(List.of(center));
        while (!q.isEmpty()) {
            String n = q.poll();
            int d = dist.get(n);
            if (d >= depth) continue;
            List<String> next = new ArrayList<>();
            for (Rel r : out(n)) next.add(r.target());
            for (Rel r : in(n)) next.add(r.source());
            next.sort(String::compareTo);
            for (String m : next) {
                if (!dist.containsKey(m)) {
                    dist.put(m, d + 1);
                    q.add(m);
                }
            }
        }
        return dist;
    }

    /** (type, source, target)로 관계 찾기 (모든 상태) */
    public Rel find(RelType type, String source, String target) {
        for (Rel r : allRels) {
            if (r.type() == type && r.source().equals(source) && r.target().equals(target)) return r;
        }
        return null;
    }
}
