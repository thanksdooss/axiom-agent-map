package com.axiom.agentmap;

import com.axiom.agentmap.api.AskDtos.AskResponse;
import com.axiom.agentmap.api.AskDtos.Interpretation;
import com.axiom.agentmap.ask.AskService;
import com.axiom.agentmap.domain.Scope;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 자연어 질의 정답 25개: 규칙 기반 해석이 params(의도·기준·방향·깊이…)와 같고,
 * 실행 결과가 expected와 같은지 두 기준(all_statuses / confirmed_only)으로 확인합니다.
 */
@SpringBootTest(properties = "axiom.llm.provider=none")
class GoldenQueryTest {
    @Autowired AskService ask;
    @Autowired JsonMapper mapper;

    @TestFactory
    List<DynamicTest> goldenQueries() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode q : AnswerKey.golden().path("queries")) {
            JsonNode exp = q.path("expected");
            boolean split = exp.has("all_statuses");
            for (Scope scope : split ? List.of(Scope.ALL, Scope.CONFIRMED) : List.of(Scope.ALL)) {
                String name = q.path("id").asString() + " [" + scope.code() + "] " + q.path("question").asString();
                tests.add(DynamicTest.dynamicTest(name, () -> check(q, scope)));
            }
        }
        assertThat(tests).hasSizeGreaterThanOrEqualTo(25);
        return tests;
    }

    private void check(JsonNode q, Scope scope) {
        AskResponse r = ask.executeRule(q.path("question").asString(), scope);
        checkParams(q, r.interpretation());
        JsonNode exp = q.path("expected");
        if (exp.has("all_statuses")) exp = exp.path(scope == Scope.ALL ? "all_statuses" : "confirmed_only");
        JsonNode res = mapper.valueToTree(r.result());
        String id = q.path("id").asString();
        switch (q.path("intent").asString()) {
            case "dependencies" -> assertThat(summarize(res, " → ")).isEqualTo(exp);
            case "impact" -> {
                if (exp.has("result")) {
                    assertThat(summarize(res, " ← ")).isEqualTo(exp.path("result"));
                    assertThat(r.answer()).isEqualTo(exp.path("answer").asString());
                } else {
                    assertThat(summarize(res, " ← ")).isEqualTo(exp);
                }
            }
            case "path_explain" -> {
                assertThat(res.path("pathNames")).isEqualTo(exp.path("path"));
                assertThat(res.path("statuses")).isEqualTo(exp.path("statuses"));
            }
            case "path" -> {
                assertThat(res.path("pathNames")).isEqualTo(exp.path("path"));
                assertThat(res.path("path").size() - 1).isEqualTo(exp.path("hops").asInt());
            }
            case "evidence" -> {
                assertThat(res.path("id").asString()).isEqualTo(exp.path("relation_id").asString());
                assertThat(res.path("status").asString()).isEqualTo(exp.path("status").asString());
                assertThat(res.path("reviewedBy").asString()).isEqualTo(exp.path("reviewed_by").asString());
                assertThat(res.path("reviewedAt").asString()).isEqualTo(exp.path("reviewed_at").asString());
                if (exp.has("evidence")) {
                    ArrayNode ev = mapper.createArrayNode();
                    for (JsonNode e : res.path("evidence")) {
                        ObjectNode o = ev.addObject();
                        o.put("file", e.path("file").asString());
                        o.put("locator", e.path("locator").asString());
                        o.put("text", e.path("text").asString());
                    }
                    assertThat((JsonNode) ev).isEqualTo(exp.path("evidence"));
                }
            }
            case "status" -> {
                JsonNode rels = res.path("relations");
                if (exp.isArray()) {
                    ArrayNode a = mapper.createArrayNode();
                    for (JsonNode e : rels) {
                        ObjectNode o = a.addObject();
                        o.put("relation_id", e.path("id").asString());
                        o.put("target", e.path("targetName").asString());
                        o.put("status", e.path("status").asString());
                        o.put("review_reason", e.path("reviewReason").asString());
                    }
                    assertThat((JsonNode) a).isEqualTo(exp);
                } else {
                    assertThat(res.path("count").asInt()).isEqualTo(exp.path("count").asInt());
                    ArrayNode a = mapper.createArrayNode();
                    for (JsonNode e : rels) {
                        ObjectNode o = a.addObject();
                        o.put("id", e.path("id").asString());
                        o.put("relation", e.path("sourceName").asString() + " → " + e.path("targetName").asString());
                        o.put("type", e.path("typeLabel").asString());
                        o.put("reason", e.path("reviewReason").asString());
                    }
                    assertThat((JsonNode) a).isEqualTo(exp.path("relations"));
                }
            }
            case "aggregate" -> {
                if (id.equals("Q01")) {
                    assertThat(res.path("totalAgents").asInt()).isEqualTo(q.path("expected").path("total_agents").asInt());
                    JsonNode byDept = q.path("expected").path("by_department").path(scope == Scope.ALL ? "all_statuses" : "confirmed_only");
                    assertThat(deptMap(res)).isEqualTo(byDept);
                } else if (res.has("byDepartment")) {
                    assertThat(deptMap(res)).isEqualTo(exp);
                    List<String> order = new ArrayList<>();
                    res.path("byDepartment").forEach(d -> order.add(d.path("name").asString()));
                    assertThat(order).isEqualTo(new ArrayList<>(exp.propertyNames()));
                } else {
                    assertThat(res.path("count").asInt()).isEqualTo(exp.path("count").asInt());
                    assertThat(names(res.path("agents"))).isEqualTo(exp.path("agents"));
                }
            }
            case "ranking" -> {
                ArrayNode a = mapper.createArrayNode();
                for (JsonNode row : res) {
                    ObjectNode o = a.addObject();
                    if (row.has("dependents")) {
                        o.put("agent", row.path("name").asString());
                        o.put("dependents", row.path("dependents").asInt());
                    } else {
                        o.put("platform", row.path("name").asString());
                        o.put("agents", row.path("count").asInt());
                    }
                }
                assertThat((JsonNode) a).isEqualTo(exp);
            }
            case "insight" -> assertThat(names(res.path("agents"))).isEqualTo(exp);
            case "similarity" -> {
                List<List<String>> pairs = new ArrayList<>();
                for (JsonNode p : res.path("pairs")) {
                    pairs.add(List.of(p.path("pair").path(0).path("name").asString(), p.path("pair").path(1).path("name").asString()));
                }
                for (JsonNode must : exp.path("must_include_pairs")) {
                    String a = must.path("pair").path(0).asString(), b = must.path("pair").path(1).asString();
                    assertThat(pairs).as("중복 후보 %s ↔ %s", a, b).anyMatch(p -> p.containsAll(List.of(a, b)));
                }
            }
            case "cycle" -> assertThat(res.path("cycleNames")).isEqualTo(exp);
            case "unknown_entity" -> {
                assertThat(r.answer()).isEqualTo(exp.path("answer").asString());
                assertThat(r.answer()).doesNotContain("0개");
            }
            case "ambiguous_anchor" -> {
                assertThat(names(res.path("candidates"))).isEqualTo(exp.path("candidates"));
                assertThat(r.clarification()).isNotNull();
                assertThat(r.clarification().options()).hasSize(exp.path("candidates").size());
            }
            case "pending" -> {
                ArrayNode a = mapper.createArrayNode();
                for (JsonNode p : res) {
                    ObjectNode o = a.addObject();
                    o.put("raw", p.path("rawLabel").asString());
                    o.put("kind", p.path("kind").asString());
                    o.put("mentioned_by", p.path("mentionedByName").asString());
                    o.put("reason", p.path("reason").asString());
                }
                assertThat((JsonNode) a).isEqualTo(exp);
            }
            default -> throw new AssertionError("알 수 없는 intent " + q.path("intent"));
        }
    }

    private void checkParams(JsonNode q, Interpretation i) {
        JsonNode p = q.path("params");
        assertThat(i.intent()).as("intent").isEqualTo(q.path("intent").asString());
        JsonNode anchor = p.has("anchor") ? p.path("anchor") : p.path("source");
        if (anchor.has("id")) {
            assertThat(i.anchor()).as("anchor").isNotNull();
            assertThat(i.anchor().id()).as("anchor").isEqualTo(anchor.path("id").asString());
        } else if (anchor.has("raw")) {
            assertThat(i.anchorRaw()).as("anchorRaw").isEqualTo(anchor.path("raw").asString());
            assertThat(i.anchorType()).as("anchorType").isEqualTo(anchor.path("type").asString());
        } else if (!p.has("relation")) {
            assertThat(i.anchor()).as("anchor 없음").isNull();
        }
        if (p.has("target")) assertThat(i.target().id()).as("target").isEqualTo(p.path("target").path("id").asString());
        if (p.has("direction")) assertThat(i.direction()).as("direction").isEqualTo(p.path("direction").asString());
        if (p.has("depth")) {
            if (p.path("depth").isNumber()) assertThat(i.depth()).as("depth").isEqualTo(p.path("depth").asInt());
            else assertThat(i.depth()).as("depth(all)").isNull();
        }
        if (p.has("relation_types")) {
            List<String> types = i.relationTypes() == null ? List.of() : i.relationTypes().stream().map(Enum::name).toList();
            List<String> want = new ArrayList<>();
            p.path("relation_types").forEach(t -> want.add(t.asString()));
            assertThat(types).as("relation_types").isEqualTo(want);
        }
        if (p.has("group_by")) assertThat(i.groupBy()).as("group_by").isEqualTo(p.path("group_by").asString());
        if (p.has("top")) assertThat(i.top()).as("top").isEqualTo(p.path("top").asInt());
        if (p.has("relation")) {
            assertThat(i.relation()).as("relation").isNotNull();
            assertThat(i.relation().source()).isEqualTo(p.path("relation").path("source").asString());
            assertThat(i.relation().type().name()).isEqualTo(p.path("relation").path("type").asString());
            assertThat(i.relation().target()).isEqualTo(p.path("relation").path("target").asString());
        }
        if (p.has("filter")) {
            Map<String, Object> f = i.filter();
            assertThat(f).as("filter").isNotNull();
            for (var e : p.path("filter").properties()) {
                String key = switch (e.getKey()) {
                    case "data.sensitivity" -> "dataSensitivity";
                    case "platform_count_gte" -> "platformCountGte";
                    default -> e.getKey();
                };
                JsonNode v = e.getValue();
                Object want = v.isBoolean() ? (Object) v.asBoolean() : v.isInt() ? (Object) v.asInt() : v.asString();
                assertThat(f.get(key)).as("filter." + key).isEqualTo(want);
            }
        }
    }

    private JsonNode summarize(JsonNode t, String arrow) {
        ObjectNode o = mapper.createObjectNode();
        ArrayNode direct = o.putArray("direct");
        t.path("direct").forEach(x -> direct.add(x.path("name").asString()));
        ArrayNode indirect = o.putArray("indirect");
        for (JsonNode x : t.path("indirect")) {
            ObjectNode i = indirect.addObject();
            i.put("name", x.path("name").asString());
            i.put("depth", x.path("depth").asInt());
            List<String> p = new ArrayList<>();
            x.path("pathNames").forEach(n -> p.add(n.asString()));
            i.put("path", String.join(arrow, p));
        }
        o.put("count", t.path("count").asInt());
        return o;
    }

    private JsonNode deptMap(JsonNode res) {
        ObjectNode o = mapper.createObjectNode();
        res.path("byDepartment").forEach(d -> o.put(d.path("name").asString(), d.path("count").asInt()));
        return o;
    }

    private JsonNode names(JsonNode refs) {
        ArrayNode a = mapper.createArrayNode();
        refs.forEach(x -> a.add(x.path("name").asString()));
        return a;
    }
}
