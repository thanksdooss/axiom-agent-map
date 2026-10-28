package com.axiom.agentmap.ask;

import com.axiom.agentmap.api.AskDtos.AskResponse;
import com.axiom.agentmap.api.AskDtos.Clarification;
import com.axiom.agentmap.api.AskDtos.Highlight;
import com.axiom.agentmap.api.AskDtos.Interpretation;
import com.axiom.agentmap.api.GraphDtos.GraphEdge;
import com.axiom.agentmap.api.GraphDtos.NodeRef;
import com.axiom.agentmap.api.GraphDtos.PathResponse;
import com.axiom.agentmap.api.GraphDtos.TraversalItem;
import com.axiom.agentmap.api.GraphDtos.TraversalResponse;
import com.axiom.agentmap.api.ReviewDtos.PendingEntity;
import com.axiom.agentmap.dashboard.DashboardService;
import com.axiom.agentmap.dashboard.DashboardService.Depended;
import com.axiom.agentmap.dashboard.DashboardService.DepartmentCount;
import com.axiom.agentmap.dashboard.DashboardService.PlatformCount;
import com.axiom.agentmap.dashboard.SimilarityService;
import com.axiom.agentmap.domain.NodeType;
import com.axiom.agentmap.domain.RelStatus;
import com.axiom.agentmap.domain.RelType;
import com.axiom.agentmap.domain.Scope;
import com.axiom.agentmap.graph.GraphService;
import com.axiom.agentmap.graph.GraphStore;
import com.axiom.agentmap.graph.GraphView;
import com.axiom.agentmap.llm.LlmClient;
import com.axiom.agentmap.review.ReviewService;
import com.axiom.agentmap.text.Texts;
import com.axiom.agentmap.vocabulary.Lexicon;
import com.axiom.agentmap.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 자연어 질의: ② 질문 해석(LLM 또는 규칙) → 그래프 탐색 실행 → ③ 답변(탐색 결과에만 근거).
 * LLM 답변은 결과에 없는 Agent 이름이 나오면 버리고 규칙 답변을 씁니다.
 */
@Service
public class AskService {
    private static final Logger log = LoggerFactory.getLogger(AskService.class);
    public static final String IMPACT_EMPTY = "등록된 관계에서는 점검 대상이 발견되지 않았습니다. 다만 등록되지 않은 연결이 있을 수 있어 안전하다고 확정할 수는 없습니다.";

    public record PathExplain(NodeRef from, NodeRef to, List<String> path, List<String> pathNames, List<String> edgeIds,
                              List<RelStatus> statuses) {}
    public record AggregateByDepartment(int totalAgents, List<DepartmentCount> byDepartment) {}
    public record AgentCount(int count, double share, List<NodeRef> agents) {}
    public record StatusResult(int count, List<GraphEdge> relations) {}
    public record SimilarPair(List<NodeRef> pair, double score, boolean sameWorkType) {}
    public record SimilarityResult(List<SimilarPair> pairs) {}
    public record CycleResult(List<List<String>> cycles, List<List<String>> cycleNames) {}
    public record UnknownEntity(String raw, String type) {}
    public record Candidates(String raw, List<NodeRef> candidates) {}

    private record Executed(Object result, String answer, Set<String> nodes, Set<String> edges, Clarification clarification) {}

    private final GraphStore store;
    private final GraphService graph;
    private final ReviewService review;
    private final SimilarityService similarity;
    private final LlmClient llm;
    private final JsonMapper mapper;

    public AskService(GraphStore store, GraphService graph, ReviewService review, SimilarityService similarity,
                      LlmClient llm, JsonMapper mapper) {
        this.store = store;
        this.graph = graph;
        this.review = review;
        this.similarity = similarity;
        this.llm = llm;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public AskResponse ask(String question, Scope scope) {
        if (Texts.isBlank(question)) throw ApiException.badRequest("question이 비어 있습니다.");
        Lexicon lex = store.lexicon();
        GraphView g = store.view(scope);
        RuleQuestionInterpreter.Result interp = null;
        String engine = "rule";
        if (llm.enabled()) {
            try {
                interp = new LlmQuestionInterpreter(llm, mapper).interpret(question, scope, lex, g);
                engine = "llm";
            } catch (RuntimeException e) {
                log.warn("LLM 질문 해석 실패 — 규칙 기반으로 대신합니다: {}", e.getMessage());
            }
        }
        if (interp == null) interp = new RuleQuestionInterpreter().interpret(question, scope, lex, g);
        Interpretation i = interp.interpretation();
        Executed ex = execute(i, interp.options(), g, lex);
        String answer = ex.answer();
        if (llm.enabled() && ex.clarification() == null && !"unsupported".equals(i.intent())) {
            answer = llmAnswer(question, i, ex, lex).orElse(answer);
        }
        return new AskResponse(question, engine, i, ex.clarification(), answer, ex.result(),
                new Highlight(new ArrayList<>(ex.nodes()), new ArrayList<>(ex.edges())));
    }

    /** 해석 → 실행 (규칙·LLM 공통). 테스트에서도 직접 씁니다. */
    @Transactional(readOnly = true)
    public AskResponse executeRule(String question, Scope scope) {
        Lexicon lex = store.lexicon();
        GraphView g = store.view(scope);
        RuleQuestionInterpreter.Result interp = new RuleQuestionInterpreter().interpret(question, scope, lex, g);
        Executed ex = execute(interp.interpretation(), interp.options(), g, lex);
        return new AskResponse(question, "rule", interp.interpretation(), ex.clarification(), ex.answer(), ex.result(),
                new Highlight(new ArrayList<>(ex.nodes()), new ArrayList<>(ex.edges())));
    }

    private Executed execute(Interpretation i, List<String> options, GraphView g, Lexicon lex) {
        Set<String> nodes = new LinkedHashSet<>();
        Set<String> edges = new LinkedHashSet<>();
        String scopeWord = g.scope() == Scope.CONFIRMED ? "확정된 " : "";
        switch (i.intent()) {
            case "dependencies", "impact" -> {
                if (i.anchor() == null) return unsupported(i);
                boolean dep = i.intent().equals("dependencies");
                TraversalResponse t = dep ? graph.dependencies(g, i.anchor().id(), i.depth()) : graph.impact(g, i.anchor().id(), i.depth());
                nodes.add(i.anchor().id());
                t.direct().forEach(x -> nodes.add(x.id()));
                t.indirect().forEach(x -> nodes.add(x.id()));
                edges.addAll(t.edgeIds());
                return new Executed(t, dep ? depAnswer(t, i, g, scopeWord) : impactAnswer(t, i), nodes, edges, null);
            }
            case "path_explain" -> {
                if (i.target() == null) return unsupported(i);
                if (i.anchor() == null) {
                    nodes.add(i.target().id());
                    return new Executed(new PathExplain(null, i.target(), null, List.of(), List.of(), List.of()),
                            i.target().name() + "가 기대는 상류 Agent가 등록된 " + scopeWord + "관계에서는 발견되지 않았습니다. 안전하다고 확정할 수는 없습니다.",
                            nodes, edges, null);
                }
                GraphView.Hop h = g.path(i.target().id(), i.anchor().id());
                List<String> ids = new ArrayList<>(h.path());
                java.util.Collections.reverse(ids);
                List<GraphView.Rel> rels = new ArrayList<>(h.pathRels());
                java.util.Collections.reverse(rels);
                List<String> names = ids.stream().map(g::name).toList();
                List<RelStatus> statuses = rels.stream().map(GraphView.Rel::status).toList();
                nodes.addAll(ids);
                rels.forEach(r -> edges.add(r.id()));
                String st = String.join("·", statuses.stream().map(Enum::name).toList());
                String note = statuses.stream().allMatch(s -> s == RelStatus.CONFIRMED) ? "모두 검토를 마친 확정 관계입니다."
                        : statuses.contains(RelStatus.SAMPLE) ? "경로의 관계가 팀 설계안 예시(SAMPLE)라 아직 검증 전입니다."
                        : "경로에 검토 대기(CANDIDATE) 관계가 있습니다.";
                String answer = i.target().name() + "는 " + String.join(" ← ", names) + " 경로로 " + i.anchor().name()
                        + "의 결과를 간접적으로 씁니다. 그래서 " + i.anchor().name() + "를 바꾸면 점검 대상이 됩니다. 경로 관계 상태: "
                        + st + " — " + note;
                return new Executed(new PathExplain(i.anchor(), i.target(), ids, names, rels.stream().map(GraphView.Rel::id).toList(),
                        statuses), answer, nodes, edges, null);
            }
            case "path" -> {
                if (i.anchor() == null || i.target() == null) return unsupported(i);
                PathResponse p = graph.path(g, i.anchor().id(), i.target().id());
                if (p.path() == null) {
                    nodes.add(i.anchor().id());
                    nodes.add(i.target().id());
                    return new Executed(p, i.anchor().name() + "에서 " + i.target().name() + "까지 결과사용 관계로 이어진 경로가 등록된 "
                            + scopeWord + "관계에서는 발견되지 않았습니다.", nodes, edges, null);
                }
                nodes.addAll(p.path());
                edges.addAll(p.edgeIds());
                return new Executed(p, i.anchor().name() + "에서 " + i.target().name() + "까지 " + (p.path().size() - 1)
                        + "단계로 연결됩니다: " + String.join(" → ", p.pathNames()), nodes, edges, null);
            }
            case "evidence" -> {
                if (i.relation() == null) {
                    if (i.anchor() == null) return unsupported(i);
                    List<GraphEdge> list = g.allRels().stream().filter(r -> r.source().equals(i.anchor().id())
                            && r.status() != RelStatus.REJECTED).map(r -> graph.edge(g, r)).toList();
                    list.forEach(e -> edges.add(e.id()));
                    nodes.add(i.anchor().id());
                    return new Executed(new StatusResult(list.size(), list), i.anchor().name() + "의 관계 " + list.size()
                            + "건과 근거를 함께 보여 드립니다.", nodes, edges, null);
                }
                GraphView.Rel r = g.find(i.relation().type(), i.relation().source(), i.relation().target());
                nodes.add(i.relation().source());
                nodes.add(i.relation().target());
                if (r == null || r.status() == RelStatus.REJECTED) {
                    return new Executed(null, g.name(i.relation().source()) + " → " + g.name(i.relation().target())
                            + " 관계는 등록돼 있지 않습니다. 근거를 확인할 수 없습니다.", nodes, edges, null);
                }
                edges.add(r.id());
                GraphEdge e = graph.edge(g, r);
                StringBuilder sb = new StringBuilder();
                sb.append(e.sourceName()).append(" → ").append(e.targetName()).append("(").append(e.typeLabel()).append(") 관계 ")
                        .append(e.id()).append("는 ").append(statusWord(e.status())).append("입니다.");
                for (var ev : e.evidence()) {
                    sb.append(" 근거: '").append(Texts.nz(ev.text())).append("' (").append(ev.file()).append(", ").append(ev.locator()).append(")");
                    if (ev.match() != null) sb.append(" — ").append(ev.match());
                    sb.append('.');
                }
                if (e.reviewedBy() != null) sb.append(" 확인: ").append(e.reviewedBy()).append(" (").append(e.reviewedAt()).append(").");
                else sb.append(" 아직 사람이 확인하지 않았습니다.");
                return new Executed(e, sb.toString(), nodes, edges, null);
            }
            case "status" -> {
                List<GraphEdge> list = new ArrayList<>();
                if (i.anchor() != null) {
                    nodes.add(i.anchor().id());
                    for (GraphView.Rel r : g.allRels()) {
                        if (!r.source().equals(i.anchor().id()) || r.status() == RelStatus.REJECTED) continue;
                        if (i.relationTypes() != null && !i.relationTypes().isEmpty() && !i.relationTypes().contains(r.type())) continue;
                        list.add(graph.edge(g, r));
                    }
                } else {
                    for (GraphView.Rel r : g.allRels()) if (r.status() == RelStatus.CANDIDATE) list.add(graph.edge(g, r));
                }
                list.forEach(e -> {
                    edges.add(e.id());
                    nodes.add(e.source());
                    nodes.add(e.target());
                });
                StringBuilder sb = new StringBuilder();
                if (i.anchor() == null) {
                    sb.append("아직 검토되지 않은(CANDIDATE) 관계는 ").append(list.size()).append("건입니다.");
                    for (GraphEdge e : list) sb.append("\n- ").append(e.id()).append(' ').append(e.sourceName()).append(" → ")
                            .append(e.targetName()).append(" (").append(e.typeLabel()).append("): ").append(Texts.nz(e.reviewReason()));
                } else if (list.isEmpty()) {
                    sb.append(i.anchor().name()).append("의 해당 관계는 등록돼 있지 않습니다.");
                } else {
                    for (GraphEdge e : list) {
                        sb.append(e.sourceName()).append(" → ").append(e.targetName()).append("(").append(e.typeLabel()).append(")은 ")
                                .append(statusWord(e.status())).append("입니다.");
                        if (e.status() == RelStatus.CANDIDATE) sb.append(" 사유: ").append(Texts.nz(e.reviewReason())).append('.');
                        if (e.status() == RelStatus.CONFIRMED && e.reviewedBy() != null) sb.append(" (확인: ").append(e.reviewedBy()).append(')');
                        sb.append(' ');
                    }
                }
                return new Executed(new StatusResult(list.size(), list), sb.toString().strip(), nodes, edges, null);
            }
            case "aggregate" -> {
                boolean sens = i.filter() != null && "상".equals(i.filter().get("dataSensitivity"));
                if ("BELONGS_TO".equals(i.groupBy())) {
                    List<String> agents = sens ? DashboardService.highSensitivityAgents(g) : g.agentIds();
                    List<DepartmentCount> by = new ArrayList<>(DashboardService.byDepartment(g, agents));
                    if (sens) by.sort(Comparator.comparingInt((DepartmentCount d) -> -d.count()).thenComparing(DepartmentCount::name));
                    nodes.addAll(agents);
                    String list = String.join(", ", by.stream().map(d -> d.name() + " " + d.count()).toList());
                    String answer = sens
                            ? "민감도 '상' 데이터를 쓰는 Agent " + agents.size() + "개의 부서 분포" + (g.scope() == Scope.CONFIRMED ? "(확정 관계 기준)" : "")
                            + ": " + list + "."
                            : "등록된 Agent는 " + g.agentIds().size() + "개입니다. 부서별(" + (g.scope() == Scope.CONFIRMED ? "확정된 소속 기준" : "소속 관계 기준")
                            + "): " + list + "." + (by.stream().anyMatch(d -> d.id() == null) ? " 소속이 확정되지 않은 Agent는 '(미확정)'으로 셉니다." : "");
                    return new Executed(new AggregateByDepartment(agents.size(), by), answer, nodes, edges, null);
                }
                List<String> high = DashboardService.highSensitivityAgents(g);
                nodes.addAll(high);
                double share = g.agentIds().isEmpty() ? 0 : Math.round(high.size() * 1000.0 / g.agentIds().size()) / 1000.0;
                return new Executed(new AgentCount(high.size(), share, high.stream().map(a -> graph.ref(g, a)).toList()),
                        "민감도 '상' 데이터에 접근하는 Agent는 " + high.size() + "개(전체의 " + String.format("%.1f", share * 100) + "%)입니다"
                                + (g.scope() == Scope.CONFIRMED ? "(확정 관계 기준)" : "(검토 대기·샘플 관계 포함)") + ": "
                                + String.join(", ", high.stream().map(g::name).toList()) + ".", nodes, edges, null);
            }
            case "insight" -> {
                int min = i.filter() != null && i.filter().get("platformCountGte") instanceof Number n ? n.intValue() : 3;
                List<String> ids = DashboardService.highSensitivityMultiPlatform(g, min);
                nodes.addAll(ids);
                String answer = ids.isEmpty() ? "고민감도 데이터에 접근하면서 플랫폼을 " + min + "개 이상 쓰는 Agent는 등록된 " + scopeWord + "관계에서는 발견되지 않았습니다."
                        : "고민감도 데이터에 접근하면서 플랫폼을 " + min + "개 이상 쓰는 Agent는 " + ids.size() + "개입니다: "
                        + String.join(", ", ids.stream().map(a -> g.name(a) + "(플랫폼 " + g.targets(a, RelType.USES_PLATFORM).size() + "개)").toList())
                        + ". 관리 복잡도가 높아 우선 점검 대상입니다.";
                return new Executed(new AgentCount(ids.size(), 0, ids.stream().map(a -> graph.ref(g, a)).toList()), answer, nodes, edges, null);
            }
            case "ranking" -> {
                if ("platform_agents".equals(i.metric())) {
                    List<PlatformCount> all = DashboardService.platformConcentration(g);
                    int top = i.top() == null ? 5 : i.top();
                    List<PlatformCount> rows = all.size() > top ? all.subList(0, top) : all;
                    rows.forEach(r -> nodes.add(r.id()));
                    return new Executed(rows, "Agent가 가장 많이 연동된 플랫폼: " + String.join(", ",
                            rows.stream().map(r -> r.name() + " " + r.count() + "개").toList()) + ". 장애·교체 시 영향이 큰 단일 장애점 후보입니다.",
                            nodes, edges, null);
                }
                List<Depended> rows = DashboardService.topDepended(g, i.top() == null ? 6 : i.top());
                rows.forEach(r -> nodes.add(r.id()));
                return new Executed(rows, "여러 Agent가 직접·간접으로 의존하는 핵심 Agent: " + String.join(", ",
                        rows.stream().map(r -> r.name() + "(" + r.dependents() + "개)").toList()) + ".", nodes, edges, null);
            }
            case "similarity" -> {
                List<SimilarPair> pairs = new ArrayList<>();
                for (SimilarityService.Pair p : similarity.topPairs()) {
                    pairs.add(new SimilarPair(List.of(graph.ref(g, p.a()), graph.ref(g, p.b())), p.score(), p.sameWorkType()));
                    nodes.add(p.a());
                    nodes.add(p.b());
                }
                return new Executed(new SimilarityResult(pairs), "이름·설명이 비슷한 중복 개발 후보(상위 " + pairs.size() + "쌍): "
                        + String.join("; ", pairs.stream().map(p -> p.pair().get(0).name() + " ↔ " + p.pair().get(1).name()
                        + String.format(" (%.2f)", p.score())).toList()) + ". 실제 중복인지는 담당자 확인이 필요합니다.", nodes, edges, null);
            }
            case "cycle" -> {
                List<List<String>> cycles = g.cycles();
                List<List<String>> names = cycles.stream().map(c -> c.stream().map(g::name).toList()).toList();
                for (List<String> c : cycles) {
                    nodes.addAll(c);
                    for (int k = 0; k < c.size(); k++) {
                        GraphView.Rel r = g.find(RelType.USES_RESULT_OF, c.get(k), c.get((k + 1) % c.size()));
                        if (r != null) edges.add(r.id());
                    }
                }
                String answer = cycles.isEmpty() ? "결과사용 순환은 등록된 " + scopeWord + "관계에서는 발견되지 않았습니다."
                        : "결과사용 순환이 " + cycles.size() + "개 있습니다: " + String.join("; ", names.stream()
                        .map(n -> String.join(" → ", n) + " → " + n.get(0)).toList()) + ". 자기 참조는 저장 단계에서 거부되어 포함되지 않습니다.";
                return new Executed(new CycleResult(cycles, names), answer, nodes, edges, null);
            }
            case "unknown_entity" -> {
                String word = switch (Texts.nz(i.anchorType())) {
                    case "DataAsset" -> "데이터";
                    case "Agent" -> "Agent";
                    default -> "플랫폼";
                };
                return new Executed(new UnknownEntity(i.anchorRaw(), i.anchorType()), i.anchorRaw() + Texts.josa(i.anchorRaw(), "은", "는")
                        + " 등록된 " + word + Texts.josa(word, "이", "가") + " 아닙니다. 등록된 " + word
                        + " 목록에서 고르거나 미등록 대상으로 올려 주세요.", nodes, edges, null);
            }
            case "ambiguous_anchor" -> {
                List<NodeRef> opts = options.stream().map(id -> graph.ref(g, id)).toList();
                opts.forEach(o -> nodes.add(o.id()));
                String msg = "'" + i.anchorRaw() + "'에 해당하는 Agent가 " + opts.size() + "개입니다. 어느 Agent인지 골라 주세요.";
                return new Executed(new Candidates(i.anchorRaw(), opts), msg + " 후보: "
                        + String.join(", ", opts.stream().map(NodeRef::name).toList()), nodes, edges, new Clarification(msg, opts));
            }
            case "pending" -> {
                List<PendingEntity> list = review.pendingList("OPEN");
                list.forEach(p -> { if (p.mentionedBy() != null) nodes.add(p.mentionedBy()); });
                StringBuilder sb = new StringBuilder("아직 등록되지 않은 대상은 " + list.size() + "건입니다.");
                for (PendingEntity p : list) sb.append("\n- ").append(p.rawLabel()).append(" (").append(p.kind()).append(", ")
                        .append(Texts.nz(p.mentionedByName())).append(" 언급): ").append(p.reason());
                return new Executed(list, sb.toString(), nodes, edges, null);
            }
            default -> {
                return unsupported(i);
            }
        }
    }

    private static Executed unsupported(Interpretation i) {
        return new Executed(null, "질문을 그래프 조회로 바꾸지 못했습니다. 예: '고객분석 Agent를 바꾸면 어디를 점검해야 하나?', "
                + "'상품추천 Agent는 무엇을 직접 사용하나?', 'Agent가 가장 많이 몰린 플랫폼은?'", new LinkedHashSet<>(), new LinkedHashSet<>(), null);
    }

    private static String statusWord(RelStatus s) {
        return switch (s) {
            case CONFIRMED -> "검토를 마친 확정(CONFIRMED) 관계";
            case CANDIDATE -> "검토 대기(CANDIDATE) 관계";
            case SAMPLE -> "팀 설계안 예시(SAMPLE, 검증 전) 관계";
            case REJECTED -> "반려된(REJECTED) 관계";
        };
    }

    private String depAnswer(TraversalResponse t, Interpretation i, GraphView g, String scopeWord) {
        String a = i.anchor().name();
        if (t.count() == 0) {
            StringBuilder sb = new StringBuilder("등록된 " + scopeWord + "관계에서는 " + a + "가 쓰는 대상이 발견되지 않았습니다. 안전하다고 확정할 수는 없습니다.");
            if (g.scope() == Scope.CONFIRMED) {
                TraversalResponse all = graph.dependencies(store.view(Scope.ALL), i.anchor().id(), i.depth());
                if (all.count() > 0) {
                    sb.append(" 다만 확정 전(SAMPLE·CANDIDATE) 관계까지 보면 ").append(all.count()).append("건이 있습니다: ")
                            .append(String.join(", ", all.direct().stream().map(x -> x.name() + "(" + x.status() + ")").toList())).append('.');
                }
            }
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder(a + "가 직접 쓰는 대상은 " + t.direct().size() + "개입니다: "
                + String.join(", ", t.direct().stream().map(x -> x.name() + "(" + (x.via() == RelType.USES_RESULT_OF ? "결과사용" : "데이터사용") + ")").toList()) + ".");
        if (!t.indirect().isEmpty()) {
            sb.append(" 간접 대상 ").append(t.indirect().size()).append("개: ")
                    .append(String.join(", ", t.indirect().stream().map(x -> x.name() + " [" + String.join(" → ", x.pathNames()) + "]").toList())).append('.');
        }
        appendStatusNote(sb, t);
        return sb.toString();
    }

    private String impactAnswer(TraversalResponse t, Interpretation i) {
        if (t.count() == 0) return IMPACT_EMPTY;
        String a = i.anchor().name();
        StringBuilder sb = new StringBuilder(a + Texts.josa(a, "을", "를") + " 바꾸면 점검할 Agent는 " + t.count() + "개입니다. 직접 "
                + t.direct().size() + "개: " + String.join(", ", t.direct().stream().map(TraversalItem::name).toList()) + ".");
        if (!t.indirect().isEmpty()) {
            sb.append(" 간접 ").append(t.indirect().size()).append("개: ")
                    .append(String.join(", ", t.indirect().stream().map(x -> x.name() + " [" + String.join(" ← ", x.pathNames()) + "]").toList())).append('.');
        }
        appendStatusNote(sb, t);
        return sb.toString();
    }

    private static void appendStatusNote(StringBuilder sb, TraversalResponse t) {
        long unconfirmed = java.util.stream.Stream.concat(t.direct().stream(), t.indirect().stream())
                .filter(x -> x.status() != RelStatus.CONFIRMED).count();
        if (unconfirmed > 0) sb.append(" (이 중 ").append(unconfirmed).append("건은 확정 전 SAMPLE·CANDIDATE 관계를 거칩니다.)");
    }

    /** ③ LLM 답변: 결과 JSON만 근거로. 결과에 없는 Agent 이름이 나오면 버립니다. */
    private java.util.Optional<String> llmAnswer(String question, Interpretation i, Executed ex, Lexicon lex) {
        try {
            String resultJson = mapper.writeValueAsString(Map.of("interpretation", i, "result", ex.result() == null ? Map.of() : ex.result(),
                    "ruleAnswer", ex.answer()));
            String system = """
                    당신은 사내 AI Agent 지도의 질의응답 도우미입니다. 주어진 조회 결과(JSON)에 있는 사실만으로 한국어로 짧게 답합니다.
                    - 결과에 없는 Agent·데이터·플랫폼 이름을 만들지 않습니다.
                    - 결과가 비어 있으면 '발견되지 않았다'고만 말하고 안전하다고 단정하지 않습니다.
                    - 확정 전(SAMPLE·CANDIDATE) 관계가 있으면 그 점을 함께 알립니다.
                    """;
            String text = llm.text(system, "질문: " + question + "\n조회 결과: " + resultJson).strip();
            for (String name : lex.agentNames().values()) {
                if (text.contains(name) && !resultJson.contains(name)) {
                    log.warn("LLM 답변에 조회 결과에 없는 Agent가 있어 규칙 답변을 씁니다: {}", name);
                    return java.util.Optional.empty();
                }
            }
            return text.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(text);
        } catch (RuntimeException e) {
            log.warn("LLM 답변 생성 실패 — 규칙 답변을 씁니다: {}", e.getMessage());
            return java.util.Optional.empty();
        }
    }

}
