package com.axiom.agentmap.graph;

import com.axiom.agentmap.api.GraphDtos.CyclesResponse;
import com.axiom.agentmap.api.GraphDtos.GraphEdge;
import com.axiom.agentmap.api.GraphDtos.GraphNode;
import com.axiom.agentmap.api.GraphDtos.GraphResponse;
import com.axiom.agentmap.api.GraphDtos.NeighborhoodResponse;
import com.axiom.agentmap.api.GraphDtos.NodeRef;
import com.axiom.agentmap.api.GraphDtos.PathResponse;
import com.axiom.agentmap.api.GraphDtos.TraversalItem;
import com.axiom.agentmap.api.GraphDtos.TraversalResponse;
import com.axiom.agentmap.domain.NodeType;
import com.axiom.agentmap.domain.RelType;
import com.axiom.agentmap.domain.Scope;
import com.axiom.agentmap.vocabulary.Vocabulary;
import com.axiom.agentmap.web.ApiException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.axiom.agentmap.api.GraphDtos.NOT_FOUND_MESSAGE;

/** 그래프 조회·탐색 API의 응답을 만듭니다(탐색 자체는 GraphView). */
@Service
public class GraphService {
    private final GraphStore store;
    private final Vocabulary vocabulary;

    public GraphService(GraphStore store, Vocabulary vocabulary) {
        this.store = store;
        this.vocabulary = vocabulary;
    }

    public GraphView view(Scope scope) {
        return store.view(scope);
    }

    // ------------------------------------------------------------------ 변환

    public GraphEdge edge(GraphView g, GraphView.Rel r) {
        return new GraphEdge(r.id(), r.type(), vocabulary.relationLabel(r.type()), r.source(), r.target(),
                g.name(r.source()), g.name(r.target()), r.status(), r.reviewReason(), r.evidence(),
                r.reviewedBy(), r.reviewedAt(), r.candidatesShown());
    }

    public GraphNode node(GraphView g, GraphView.Node n) {
        String group = switch (n.type()) {
            case AGENT -> {
                List<String> deps = g.targets(n.id(), RelType.BELONGS_TO);
                yield deps.isEmpty() ? null : deps.get(0);
            }
            case DATA_ASSET, PLATFORM -> n.category();
            default -> null;
        };
        return new GraphNode(n.id(), n.type(), n.label(), group, n.type() == NodeType.DATA_ASSET ? n.sensitivity() : null,
                n.type() == NodeType.AGENT ? n.agentStatus() : null, g.degree(n.id()));
    }

    public NodeRef ref(GraphView g, String id) {
        GraphView.Node n = g.node(id);
        if (n == null) return new NodeRef(id, id, NodeType.ofId(id));
        return new NodeRef(n.id(), n.label(), n.type());
    }

    public GraphView.Node require(GraphView g, String id) {
        GraphView.Node n = g.node(id);
        if (n == null) throw ApiException.notFound("등록되지 않은 노드입니다: " + id);
        return n;
    }

    // ------------------------------------------------------------------ 그래프

    public GraphResponse graph(Scope scope) {
        GraphView g = view(scope);
        List<GraphNode> nodes = new ArrayList<>();
        for (GraphView.Node n : g.nodes().values()) nodes.add(node(g, n));
        List<GraphEdge> edges = new ArrayList<>();
        for (GraphView.Rel r : g.rels()) edges.add(edge(g, r));
        return new GraphResponse(scope.code(), nodes, edges);
    }

    public NeighborhoodResponse neighborhood(String id, int depth, Scope scope) {
        if (depth < 1 || depth > 3) throw ApiException.badRequest("depth는 1~3이어야 합니다.");
        GraphView g = view(scope);
        require(g, id);
        Map<String, Integer> dist = g.neighborhood(id, depth);
        List<GraphNode> nodes = new ArrayList<>();
        for (String n : dist.keySet()) nodes.add(node(g, g.node(n)));
        List<GraphEdge> edges = new ArrayList<>();
        for (GraphView.Rel r : g.rels()) {
            Integer ds = dist.get(r.source()), dt = dist.get(r.target());
            if (ds != null && dt != null && Math.min(ds, dt) < depth) edges.add(edge(g, r));
        }
        return new NeighborhoodResponse(scope.code(), ref(g, id), nodes, edges);
    }

    public PathResponse path(String from, String to, Scope scope) {
        GraphView g = view(scope);
        require(g, from);
        require(g, to);
        return path(g, from, to);
    }

    public PathResponse path(GraphView g, String from, String to) {
        GraphView.Hop h = g.path(from, to);
        if (h == null) return new PathResponse(ref(g, from), ref(g, to), null, List.of(), List.of());
        List<String> names = h.path().stream().map(g::name).toList();
        List<String> edgeIds = h.pathRels().stream().map(GraphView.Rel::id).toList();
        return new PathResponse(ref(g, from), ref(g, to), h.path(), names, edgeIds);
    }

    public CyclesResponse cycles(Scope scope) {
        return new CyclesResponse(view(scope).cycles());
    }

    // ------------------------------------------------------------------ 탐색

    public TraversalResponse dependencies(String agentId, Integer depth, Scope scope) {
        GraphView g = view(scope);
        GraphView.Node n = require(g, agentId);
        if (n.type() != NodeType.AGENT) throw ApiException.badRequest("의존 탐색은 Agent만 할 수 있습니다: " + agentId);
        return dependencies(g, agentId, depth);
    }

    public TraversalResponse dependencies(GraphView g, String agentId, Integer depth) {
        List<GraphView.Hop> hops = g.dependencies(agentId, depthLimit(depth));
        return traversal(g, agentId, "forward", depth, hops);
    }

    public TraversalResponse impact(String id, NodeType expected, Integer depth, Scope scope) {
        GraphView g = view(scope);
        GraphView.Node n = require(g, id);
        if (expected != null && n.type() != expected) {
            throw ApiException.badRequest(id + "는 " + expected.code() + " 노드가 아닙니다.");
        }
        return impact(g, id, depth);
    }

    public TraversalResponse impact(GraphView g, String id, Integer depth) {
        List<GraphView.Hop> hops = g.impact(id, depthLimit(depth));
        return traversal(g, id, "reverse", depth, hops);
    }

    private static int depthLimit(Integer depth) {
        if (depth == null) return GraphView.UNLIMITED;
        if (depth < 1) throw ApiException.badRequest("depth는 1 이상이어야 합니다.");
        return depth;
    }

    private TraversalResponse traversal(GraphView g, String anchor, String direction, Integer depth,
                                        List<GraphView.Hop> hops) {
        List<TraversalItem> direct = new ArrayList<>();
        List<TraversalItem> indirect = new ArrayList<>();
        Set<String> edgeIds = new LinkedHashSet<>();
        for (GraphView.Hop h : hops) {
            GraphView.Node n = g.node(h.id());
            TraversalItem item = new TraversalItem(h.id(), n.label(), n.type(), h.depth(), h.via().type(),
                    h.via().status(), h.path(), h.path().stream().map(g::name).toList());
            (h.depth() == 1 ? direct : indirect).add(item);
            edgeIds.add(h.via().id());
        }
        return new TraversalResponse(ref(g, anchor), direction, depth, g.scope().code(), direct, indirect, hops.size(),
                new ArrayList<>(edgeIds), hops.isEmpty() ? NOT_FOUND_MESSAGE : null);
    }
}
