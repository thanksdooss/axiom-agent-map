package com.axiom.agentmap.agents;

import com.axiom.agentmap.api.AgentDtos.AgentDetail;
import com.axiom.agentmap.api.AgentDtos.AgentSummary;
import com.axiom.agentmap.api.AgentDtos.ReviewFlag;
import com.axiom.agentmap.api.GraphDtos.GraphEdge;
import com.axiom.agentmap.domain.AgentEntity;
import com.axiom.agentmap.domain.RelType;
import com.axiom.agentmap.domain.Scope;
import com.axiom.agentmap.graph.GraphService;
import com.axiom.agentmap.graph.GraphStore;
import com.axiom.agentmap.graph.GraphView;
import com.axiom.agentmap.repository.AgentRepository;
import com.axiom.agentmap.text.Texts;
import com.axiom.agentmap.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Agent 목록·상세. 소속·업무 유형·플랫폼·데이터는 현재 scope의 관계에서 읽습니다. */
@Service
public class AgentService {
    private final AgentRepository agents;
    private final GraphService graph;
    private final JsonMapper mapper;

    public AgentService(AgentRepository agents, GraphService graph, JsonMapper mapper) {
        this.agents = agents;
        this.graph = graph;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<AgentSummary> list(String q, String department, String status, Scope scope) {
        GraphView g = graph.view(scope);
        String needle = Texts.compact(q).toLowerCase();
        List<AgentSummary> out = new ArrayList<>();
        List<AgentEntity> all = new ArrayList<>(agents.findAll());
        all.sort(Comparator.comparing(a -> a.id));
        for (AgentEntity a : all) {
            AgentSummary s = summary(g, a);
            if (!needle.isEmpty() && !Texts.compact(a.name).toLowerCase().contains(needle)) continue;
            if (!Texts.isBlank(department)) {
                boolean none = department.equalsIgnoreCase("none") || department.equals("(미확정)");
                if (none ? s.departmentId() != null : !department.equals(s.departmentId())) continue;
            }
            if (!Texts.isBlank(status) && !status.equals(a.status)) continue;
            out.add(s);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public AgentDetail detail(String id, Scope scope) {
        AgentEntity a = agents.findById(id).orElseThrow(() -> ApiException.notFound("등록되지 않은 Agent입니다: " + id));
        GraphView g = graph.view(scope);
        AgentSummary s = summary(g, a);
        List<GraphEdge> outgoing = g.out(id).stream().map(r -> graph.edge(g, r)).toList();
        List<GraphEdge> incoming = g.in(id).stream().map(r -> graph.edge(g, r)).toList();
        return new AgentDetail(s.id(), s.name(), s.departmentId(), s.departmentName(), s.workTypeId(), s.workTypeName(),
                s.status(), s.owners(), s.platformIds(), s.dataIds(), s.highSensitivity(), s.reviewFlagCount(),
                a.description, a.startDate, a.monthlyCalls, a.sourceFile, reviewFlags(a.reviewFlagsJson), outgoing, incoming);
    }

    public AgentSummary summary(GraphView g, AgentEntity a) {
        List<String> deps = g.targets(a.id, RelType.BELONGS_TO);
        List<String> wts = g.targets(a.id, RelType.HAS_WORK_TYPE);
        List<String> platforms = g.targets(a.id, RelType.USES_PLATFORM);
        List<String> data = g.targets(a.id, RelType.ACCESSES_DATA);
        boolean high = data.stream().anyMatch(d -> g.node(d) != null && "상".equals(g.node(d).sensitivity()));
        String dep = deps.isEmpty() ? null : deps.get(0);
        String wt = wts.isEmpty() ? null : wts.get(0);
        return new AgentSummary(a.id, a.name, dep, dep == null ? null : g.name(dep), wt, wt == null ? null : g.name(wt),
                a.status, GraphStore.split(a.owners), platforms, data, high, reviewFlags(a.reviewFlagsJson).size());
    }

    public List<ReviewFlag> reviewFlags(String json) {
        List<ReviewFlag> out = new ArrayList<>();
        if (Texts.isBlank(json)) return out;
        for (JsonNode f : mapper.readTree(json)) {
            JsonNode c = f.path("candidate");
            Object cand = c.isNull() || c.isMissingNode() ? null : c.isNumber() ? (Object) c.asInt() : c.asString();
            out.add(new ReviewFlag(f.path("field").asString(), f.path("raw").asString(), cand, f.path("reason").asString()));
        }
        return out;
    }
}
