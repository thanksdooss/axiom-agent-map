package com.axiom.agentmap.ask;

import com.axiom.agentmap.api.AskDtos.Interpretation;
import com.axiom.agentmap.api.AskDtos.RelationRef;
import com.axiom.agentmap.api.GraphDtos.NodeRef;
import com.axiom.agentmap.domain.NodeType;
import com.axiom.agentmap.domain.RelType;
import com.axiom.agentmap.domain.Scope;
import com.axiom.agentmap.graph.GraphView;
import com.axiom.agentmap.llm.LlmClient;
import com.axiom.agentmap.llm.LlmException;
import com.axiom.agentmap.vocabulary.Lexicon;
import com.axiom.agentmap.vocabulary.Lexicon.Kind;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 질문 해석(②): 구조화 출력으로 의도와 기준 대상 "표기"만 받고, 표기 → ID는 어휘 사전으로 백엔드가 정합니다.
 */
public class LlmQuestionInterpreter {
    private static final List<String> INTENTS = List.of("aggregate", "dependencies", "impact", "path_explain", "evidence",
            "status", "ranking", "insight", "similarity", "path", "cycle", "unknown_entity", "ambiguous_anchor", "pending",
            "unsupported");
    private static final String SYSTEM = """
            사내 AI Agent 지도(온톨로지 그래프)에 대한 한국어 질문을 해석합니다. 답은 하지 말고 해석 JSON만 만듭니다.
            관계 방향은 '쓰는 쪽 → 제공하는 쪽'입니다.
            intent:
            - dependencies: 이 Agent가 무엇을 쓰나(정방향). depth: "직접"=1, "간접"=2, 그 밖에는 null.
            - impact: 이 대상(Agent·데이터·플랫폼)을 바꾸면 어디를 점검하나(역방향). depth는 보통 null.
            - path: 두 Agent 사이 경로. path_explain: "X가 왜 점검 대상인가"(X는 targetRaw).
            - evidence: 관계의 근거·확인자. status: 관계가 검토(확정)됐는지 또는 검토 대기 목록.
            - aggregate: 부서별 개수(groupBy=BELONGS_TO) 또는 민감도 '상' 데이터 접근 Agent 수.
            - ranking: 많이 의존되는 Agent(metric=dependents) 또는 Agent가 몰린 플랫폼(metric=platform_agents).
            - insight: 고민감도 데이터 + 플랫폼 N개 이상. similarity: 비슷한·중복 Agent. cycle: 순환. pending: 미등록 대상.
            - unsupported: 그 밖.
            anchorRaw·targetRaw는 질문에 적힌 표기 그대로 복사합니다(목록에 없으면 그대로 두면 됩니다).
            """;

    private final LlmClient llm;
    private final JsonMapper mapper;

    public LlmQuestionInterpreter(LlmClient llm, JsonMapper mapper) {
        this.llm = llm;
        this.mapper = mapper;
    }

    public RuleQuestionInterpreter.Result interpret(String question, Scope scope, Lexicon lex, GraphView g) {
        String user = "등록된 Agent: " + String.join(", ", lex.agentNames().values()) + "\n질문: " + question;
        JsonNode n = mapper.readTree(llm.json(SYSTEM, user, "question_interpretation", schema()));
        String intent = n.path("intent").asString("unsupported");
        if (!INTENTS.contains(intent)) throw new LlmException("알 수 없는 intent: " + intent);
        String anchorRaw = str(n.path("anchorRaw"));
        String targetRaw = str(n.path("targetRaw"));
        Integer depth = n.path("depth").isInt() ? n.path("depth").asInt() : null;
        String sc = scope.code();
        NodeRef anchor = null;
        NodeRef target = null;
        if (anchorRaw != null) {
            Resolved r = resolve(anchorRaw, lex);
            if (r.ambiguous.size() >= 2) {
                List<String> opts = new ArrayList<>(r.ambiguous);
                opts.sort((a, b) -> lex.name(a).compareTo(lex.name(b)));
                return new RuleQuestionInterpreter.Result(RuleQuestionInterpreter.base("ambiguous_anchor", null, anchorRaw, null,
                        null, null, sc, "Agent"), opts);
            }
            if (r.ref == null) {
                return new RuleQuestionInterpreter.Result(RuleQuestionInterpreter.base("unknown_entity", null, anchorRaw, null,
                        null, null, sc, str(n.path("anchorType")) == null ? "Platform" : str(n.path("anchorType"))), List.of());
            }
            anchor = r.ref;
        }
        if (targetRaw != null) target = resolve(targetRaw, lex).ref;
        if (intent.equals("path_explain") && target == null && anchor != null) {
            target = anchor;
            anchor = null;
        }
        if (intent.equals("path_explain") && target != null && anchor == null) {
            String root = RuleQuestionInterpreter.upstreamRoot(g, target.id());
            anchor = root == null ? null : new NodeRef(root, lex.name(root), NodeType.AGENT);
        }
        String direction = switch (intent) {
            case "dependencies", "path" -> "forward";
            case "impact", "path_explain" -> "reverse";
            default -> null;
        };
        Interpretation base = RuleQuestionInterpreter.base(intent, anchor, anchorRaw, target, direction, depth, sc, null);
        Map<String, Object> filter = new LinkedHashMap<>();
        if (n.path("highSensitivityOnly").asBoolean(false)) filter.put("dataSensitivity", "상");
        if (n.path("platformCountGte").isInt()) filter.put("platformCountGte", n.path("platformCountGte").asInt());
        if (intent.equals("status") && anchor == null) filter.put("status", "CANDIDATE");
        if (intent.equals("pending")) filter.put("pending", true);
        String metric = str(n.path("metric"));
        Integer top = null;
        if (intent.equals("ranking")) top = "platform_agents".equals(metric) ? 5 : 6;
        else if (intent.equals("similarity")) top = 6;
        List<RelType> types = switch (intent) {
            case "dependencies" -> List.of(RelType.USES_RESULT_OF, RelType.ACCESSES_DATA);
            case "impact" -> anchor == null ? List.of() : RuleQuestionInterpreter.impactTypes(anchor.type());
            case "cycle", "path", "path_explain" -> List.of(RelType.USES_RESULT_OF);
            default -> List.of();
        };
        RelationRef relation = intent.equals("evidence") && anchor != null && target != null
                ? new RelationRef(anchor.id(), RelType.fromAgentTo(target.type()), target.id()) : null;
        return new RuleQuestionInterpreter.Result(RuleQuestionInterpreter.with(base, types,
                "aggregate".equals(intent) && n.path("groupByDepartment").asBoolean(false) ? "BELONGS_TO" : null,
                filter, metric, top, relation), List.of());
    }

    private record Resolved(NodeRef ref, List<String> ambiguous) {}

    private static Resolved resolve(String raw, Lexicon lex) {
        String id = lex.agentIdByName(raw);
        if (id == null) id = lex.agentIdByName(raw + " Agent");
        if (id != null) return new Resolved(new NodeRef(id, lex.name(id), NodeType.AGENT), List.of());
        for (Kind k : List.of(Kind.DATA, Kind.PLATFORM, Kind.DEPARTMENT)) {
            Lexicon.Resolution r = lex.resolve(k, raw);
            if (r.confirmed()) return new Resolved(new NodeRef(r.id(), lex.name(r.id()), k.nodeType), List.of());
        }
        String word = raw.replaceAll("\\s*Agent$", "").strip();
        List<String> c = word.isEmpty() ? List.of() : lex.agentsContaining(word, null);
        if (c.size() == 1) return new Resolved(new NodeRef(c.get(0), lex.name(c.get(0)), NodeType.AGENT), List.of());
        return new Resolved(null, c);
    }

    private static String str(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode() || n.asString("").isBlank() ? null : n.asString();
    }

    private static Map<String, Object> schema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("intent", Map.of("type", "string", "enum", INTENTS));
        props.put("anchorRaw", Map.of("type", List.of("string", "null")));
        props.put("anchorType", Map.of("type", List.of("string", "null"), "enum", java.util.Arrays.asList("Agent", "DataAsset", "Platform", null)));
        props.put("targetRaw", Map.of("type", List.of("string", "null")));
        props.put("depth", Map.of("type", List.of("integer", "null")));
        props.put("groupByDepartment", Map.of("type", "boolean"));
        props.put("highSensitivityOnly", Map.of("type", "boolean"));
        props.put("platformCountGte", Map.of("type", List.of("integer", "null")));
        props.put("metric", Map.of("type", List.of("string", "null"), "enum", java.util.Arrays.asList("dependents", "platform_agents", null)));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("properties", props);
        m.put("required", new ArrayList<>(props.keySet()));
        m.put("additionalProperties", false);
        return m;
    }
}
