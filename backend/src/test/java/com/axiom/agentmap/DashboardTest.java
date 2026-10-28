package com.axiom.agentmap;

import com.axiom.agentmap.agents.AgentService;
import com.axiom.agentmap.api.AgentDtos.AgentSummary;
import com.axiom.agentmap.dashboard.DashboardService;
import com.axiom.agentmap.dashboard.DashboardService.Dashboard;
import com.axiom.agentmap.dashboard.DashboardService.Insight;
import com.axiom.agentmap.domain.RelType;
import com.axiom.agentmap.domain.Scope;
import com.axiom.agentmap.graph.GraphService;
import com.axiom.agentmap.graph.GraphView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 대시보드 지표가 dashboard_expected.json과 같은지 (두 기준) */
@SpringBootTest(properties = "axiom.llm.provider=none")
class DashboardTest {
    @Autowired DashboardService dashboard;
    @Autowired GraphService graph;
    @Autowired AgentService agents;

    @Test
    void matchesExpectedForBothScopes() {
        JsonNode exp = AnswerKey.dashboard();
        for (Scope scope : List.of(Scope.ALL, Scope.CONFIRMED)) {
            Dashboard d = dashboard.dashboard(scope);
            assertThat(d.scope()).isEqualTo(scope.code());
            assertThat(d.totalAgents()).isEqualTo(exp.path("total_agents").asInt());
            assertThat(d.agentsByStatus()).isEqualTo(intMap(exp.path("agents_by_status")));
            assertThat(d.dataSensitivity()).isEqualTo(intMap(exp.path("data_assets_by_sensitivity")));
            assertThat(d.relationshipsByStatus()).isEqualTo(intMap(exp.path("relationships_by_status")));
            assertThat(d.pendingEntities()).isEqualTo(exp.path("pending_entities").asInt());

            JsonNode view = exp.path("by_scope").path(scope == Scope.ALL ? "all_statuses" : "confirmed_only");
            Map<String, Integer> byDept = new LinkedHashMap<>();
            d.agentsByDepartment().forEach(x -> byDept.put(x.name(), x.count()));
            assertThat(byDept).isEqualTo(intMap(view.path("agents_by_department")));
            d.agentsByDepartment().stream().filter(x -> x.name().equals(DashboardService.UNASSIGNED))
                    .forEach(x -> assertThat(x.id()).isNull());
            assertThat(d.highSensitivity().count()).isEqualTo(view.path("agents_accessing_high_sensitivity").asInt());
            assertThat(d.highSensitivity().share()).isEqualTo(view.path("share_accessing_high_sensitivity").asDouble());
            assertThat(d.highSensitivity().agents()).hasSize(d.highSensitivity().count());
            assertThat(d.topDepended()).hasSize(6);
            assertThat(d.topDepended().get(0).name()).isEqualTo("단가 조회 Agent");
            assertThat(d.topDepended().get(0).dependents()).isEqualTo(5);
            assertThat(d.platformConcentration().get(0).count()).isEqualTo(11);

            List<Insight> ins = d.insights();
            Insight multi = ins.stream().filter(i -> i.kind().equals("HIGH_SENS_MULTI_PLATFORM")).findFirst().orElseThrow();
            assertThat(multi.nodeIds()).containsExactly("A025", "A041", "A046");
            List<List<String>> dup = ins.stream().filter(i -> i.kind().equals("DUPLICATE_CANDIDATE")).map(Insight::nodeIds).toList();
            assertThat(dup).contains(List.of("A031", "A044"), List.of("A016", "A036"), List.of("A006", "A012"));
            assertThat(ins).anyMatch(i -> i.kind().equals("CYCLE") && i.nodeIds().equals(List.of("A024", "A025")));
            assertThat(ins).anyMatch(i -> i.kind().equals("REVIEW_BACKLOG") && i.title().contains("10건"));
            Insight conc = ins.stream().filter(i -> i.kind().equals("PLATFORM_CONCENTRATION")).findFirst().orElseThrow();
            assertThat(conc.nodeIds()).isEqualTo(scope == Scope.ALL ? List.of("P03", "P07") : List.of("P07"));
        }
    }

    @Test
    void relationshipCountsAndReviewFlags() {
        JsonNode exp = AnswerKey.dashboard();
        GraphView g = graph.view(Scope.ALL);
        Map<String, Integer> byType = new LinkedHashMap<>();
        for (RelType t : RelType.values()) byType.put(t.label(), 0);
        g.rels().forEach(r -> byType.merge(r.type().label(), 1, Integer::sum));
        assertThat(byType).isEqualTo(intMap(exp.path("relationships_by_type")));
        List<AgentSummary> list = agents.list(null, null, null, Scope.ALL);
        assertThat(list).hasSize(53);
        assertThat(list.stream().mapToInt(AgentSummary::reviewFlagCount).sum()).isEqualTo(exp.path("review_flags").asInt());
        assertThat(list.stream().filter(a -> a.reviewFlagCount() > 0).count()).isEqualTo(exp.path("agents_with_review_flags").asInt());
    }

    private static Map<String, Integer> intMap(JsonNode n) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (var e : n.properties()) m.put(e.getKey(), e.getValue().asInt());
        return m;
    }
}
