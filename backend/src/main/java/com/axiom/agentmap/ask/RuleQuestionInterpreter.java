package com.axiom.agentmap.ask;

import com.axiom.agentmap.api.AskDtos.Interpretation;
import com.axiom.agentmap.api.AskDtos.RelationRef;
import com.axiom.agentmap.api.GraphDtos.NodeRef;
import com.axiom.agentmap.domain.NodeType;
import com.axiom.agentmap.domain.RelType;
import com.axiom.agentmap.domain.Scope;
import com.axiom.agentmap.graph.GraphView;
import com.axiom.agentmap.text.Cues;
import com.axiom.agentmap.vocabulary.Lexicon;
import com.axiom.agentmap.vocabulary.Lexicon.Kind;
import com.axiom.agentmap.vocabulary.TextScanner;
import com.axiom.agentmap.vocabulary.TextScanner.Mention;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 규칙 기반 질문 해석(②). 표준 어휘로 기준 대상을 찾고(이름은 가려 둔 뒤) 핵심 표현으로 의도를 고릅니다.
 * 결과는 query_golden_set.json의 params와 같은 의도·기준·방향·깊이입니다.
 */
public class RuleQuestionInterpreter {
    public static final String DEFAULT_SENS = "상";
    private static final Pattern LATIN = Pattern.compile("[A-Za-z][A-Za-z0-9.&+\\-]*(?:\\s+[A-Za-z][A-Za-z0-9.&+\\-]*)*");
    private static final Pattern N_OR_MORE = Pattern.compile("(\\d+)\\s*개\\s*이상");
    private static final Pattern N_STEPS = Pattern.compile("(\\d+)\\s*(?:단계|홉|hop)|깊이\\s*(\\d+)");

    /** 해석 + 모호할 때 보여 줄 후보 */
    public record Result(Interpretation interpretation, List<String> options) {}

    private record Entity(String id, NodeType type, String surface, int start) {}

    public Result interpret(String question, Scope scope, Lexicon lex, GraphView g) {
        String q = question == null ? "" : question.strip();
        List<Lexicon.Term> terms = lex.terms(EnumSet.of(Kind.AGENT, Kind.DATA, Kind.PLATFORM, Kind.DEPARTMENT), false, false, true);
        List<Mention> ms = TextScanner.scan(q, terms);
        List<Entity> entities = new ArrayList<>();
        for (Mention m : ms) {
            if (m.kind() == Kind.DEPARTMENT) continue; // 부서는 기준 대상이 아니라 필터로만 씀
            entities.add(new Entity(m.id(), m.kind().nodeType, m.surface(), m.start()));
        }
        // 이름 일부만 쓴 'X Agent'
        String ambiguousRaw = null;
        List<String> ambiguous = List.of();
        for (TextScanner.LooseAgent la : TextScanner.looseAgents(q, ms)) {
            if (Cues.NOT_AGENT_WORDS.contains(la.word()) || la.word().length() < 2) continue;
            List<String> c = lex.agentsContaining(la.word(), null);
            if (c.size() >= 2 && ambiguousRaw == null) {
                ambiguousRaw = la.raw();
                ambiguous = c;
            } else if (c.size() == 1) {
                entities.add(new Entity(c.get(0), NodeType.AGENT, la.raw(), la.start()));
            }
        }
        entities.sort((a, b) -> Integer.compare(a.start(), b.start()));
        String masked = mask(q, ms);
        Entity anchor = entities.isEmpty() ? null : entities.get(0);
        Entity second = entities.size() > 1 ? entities.get(1) : null;
        String sc = scope.code();

        // 1) 모호한 기준
        if (ambiguousRaw != null && anchor == null) {
            List<String> opts = new ArrayList<>(ambiguous);
            opts.sort((a, b) -> lex.name(a).compareTo(lex.name(b)));
            return new Result(base("ambiguous_anchor", null, ambiguousRaw, null, null, null, sc, "Agent"), opts);
        }
        // 2) 등록되지 않은 대상 (영문 제품명 등)
        if (anchor == null) {
            String unknown = unknownLatin(masked);
            if (unknown != null) {
                String type = has(q, "데이터|자료|DB") ? "DataAsset" : q.contains(unknown + " Agent") ? "Agent" : "Platform";
                return new Result(base("unknown_entity", null, unknown, null, null, null, sc, type), List.of());
            }
        }
        if (has(masked, "순환")) {
            return ok(with(base("cycle", null, null, null, null, null, sc, null), List.of(RelType.USES_RESULT_OF), null, null, null, null, null));
        }
        if (has(masked, "미등록|등록되지 않은|등록 안 된|등록이 안 된")) {
            return ok(with(base("pending", null, null, null, null, null, sc, null), null, null, Map.of("pending", true), null, null, null));
        }
        if (anchor == null && has(masked, "검토되지 않은|미검토|검토 대기|검토 안 된|확정되지 않은|미확정 관계|후보 관계")) {
            return ok(with(base("status", null, null, null, null, null, sc, null), null, null, Map.of("status", "CANDIDATE"), null, null, null));
        }
        if (anchor != null && has(masked, "근거|출처|누가 확인|누가 검토|검토자|확인했|확인한 사람")) {
            NodeRef a = ref(anchor, lex);
            if (second != null) {
                RelType t = RelType.fromAgentTo(second.type());
                return ok(with(base("evidence", a, anchor.surface(), ref(second, lex), null, null, sc, null), List.of(t), null, null,
                        null, null, new RelationRef(anchor.id(), t, second.id())));
            }
            return ok(base("evidence", a, anchor.surface(), null, null, null, sc, null));
        }
        if (anchor != null && has(masked, "검토된|확정된|실제 관계|검토 상태|검토됐|확정됐|상태는|상태가")) {
            List<RelType> types = has(masked, "데이터") ? List.of(RelType.ACCESSES_DATA)
                    : has(masked, "플랫폼") ? List.of(RelType.USES_PLATFORM)
                    : has(masked, "결과|의존") ? List.of(RelType.USES_RESULT_OF) : List.of();
            return ok(with(base("status", ref(anchor, lex), anchor.surface(), null, null, null, sc, null), types, null, null, null, null, null));
        }
        if (anchor != null && second != null && anchor.type() == NodeType.AGENT && second.type() == NodeType.AGENT
                && has(masked, "경로|어떻게 연결|어떤 식으로 연결|까지")) {
            return ok(with(base("path", ref(anchor, lex), anchor.surface(), ref(second, lex), "forward", null, sc, null),
                    List.of(RelType.USES_RESULT_OF), null, null, null, null, null));
        }
        if (anchor != null && anchor.type() == NodeType.AGENT && has(masked, "왜") && has(masked, "점검|영향|대상")) {
            String root = upstreamRoot(g, anchor.id());
            NodeRef rootRef = root == null ? null : new NodeRef(root, lex.name(root), NodeType.AGENT);
            return ok(with(base("path_explain", rootRef, anchor.surface(), ref(anchor, lex), "reverse", null, sc, null),
                    List.of(RelType.USES_RESULT_OF), null, null, null, null, null));
        }
        if (has(masked, "비슷한|중복|유사")) {
            return ok(with(base("similarity", null, null, null, null, null, sc, null), null, null, null,
                    "name_description_similarity", 6, null));
        }
        if (has(masked, "플랫폼") && has(masked, "가장 많|몰린|집중|많이 쓰|많이 연동")) {
            return ok(with(base("ranking", null, null, null, null, null, sc, null), List.of(RelType.USES_PLATFORM), null, null,
                    "platform_agents", 5, null));
        }
        if (anchor == null && has(masked, "공통으로 의존|핵심 Agent|가장 많이 의존|의존하는 Agent가 많|많이 의존")) {
            return ok(with(base("ranking", null, null, null, "reverse", null, sc, null), List.of(RelType.USES_RESULT_OF), null, null,
                    "dependents", 6, null));
        }
        boolean sens = has(masked, "고민감도|민감도|개인정보");
        Matcher nm = N_OR_MORE.matcher(masked);
        if (sens && has(masked, "플랫폼") && nm.find()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("dataSensitivity", DEFAULT_SENS);
            f.put("platformCountGte", Integer.parseInt(nm.group(1)));
            return ok(with(base("insight", null, null, null, null, null, sc, null), null, null, f, null, null, null));
        }
        if (anchor == null && has(masked, "부서별|어느 부서|부서마다|부서에 많|부서별로")) {
            Map<String, Object> f = sens ? Map.of("dataSensitivity", DEFAULT_SENS) : null;
            return ok(with(base("aggregate", null, null, null, null, null, sc, null), null, "BELONGS_TO", f, null, null, null));
        }
        if (anchor == null && sens) {
            return ok(with(base("aggregate", null, null, null, null, null, sc, null), List.of(RelType.ACCESSES_DATA), null,
                    Map.of("dataSensitivity", DEFAULT_SENS), null, null, null));
        }
        if (anchor != null) {
            Integer depth = steps(masked);
            boolean change = has(masked, "바꾸면|바꿔|바꾸|변경|교체|영향|점검|중단|폐기|수정|없애|내리면|끄면|장애");
            boolean use = has(masked, "사용|쓰|의존|연결|참고|필요|가져|읽");
            if (change || (use && anchor.type() != NodeType.AGENT)) {
                if (depth == null && has(masked, "직접")) depth = 1;
                if (depth == null && !change && anchor.type() != NodeType.AGENT) depth = 1;
                return ok(with(base("impact", ref(anchor, lex), anchor.surface(), null, "reverse", depth, sc, null),
                        impactTypes(anchor.type()), null, null, null, null, null));
            }
            if (use && anchor.type() == NodeType.AGENT) {
                if (depth == null && has(masked, "직접")) depth = 1;
                if (depth == null && has(masked, "간접")) depth = 2;
                return ok(with(base("dependencies", ref(anchor, lex), anchor.surface(), null, "forward", depth, sc, null),
                        List.of(RelType.USES_RESULT_OF, RelType.ACCESSES_DATA), null, null, null, null, null));
            }
        }
        if (anchor == null && has(masked, "몇 개|등록돼|등록된|목록|전체|몇 개야")) {
            return ok(with(base("aggregate", null, null, null, null, null, sc, null), null, "BELONGS_TO", null, null, null, null));
        }
        return ok(base("unsupported", anchor == null ? null : ref(anchor, lex), anchor == null ? null : anchor.surface(),
                null, null, null, sc, null));
    }

    static List<RelType> impactTypes(NodeType t) {
        return switch (t) {
            case DATA_ASSET -> List.of(RelType.ACCESSES_DATA, RelType.USES_RESULT_OF);
            case PLATFORM -> List.of(RelType.USES_PLATFORM, RelType.USES_RESULT_OF);
            default -> List.of(RelType.USES_RESULT_OF);
        };
    }

    /** '왜 점검 대상인가' 질문의 기준: 이 Agent가 결과사용으로 기대는 가장 먼 상류 Agent */
    public static String upstreamRoot(GraphView g, String agentId) {
        String best = null;
        int bestDepth = 0;
        for (GraphView.Hop h : g.dependencies(agentId, GraphView.UNLIMITED)) {
            if (h.via().type() != RelType.USES_RESULT_OF) continue;
            if (h.depth() > bestDepth || (h.depth() == bestDepth && best != null && h.id().compareTo(best) < 0)) {
                best = h.id();
                bestDepth = h.depth();
            }
        }
        return best;
    }

    private static Integer steps(String s) {
        Matcher m = N_STEPS.matcher(s);
        if (!m.find()) return null;
        return Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2));
    }

    private static String unknownLatin(String masked) {
        Matcher m = LATIN.matcher(masked);
        while (m.find()) {
            String s = m.group().replaceAll("(\\s+Agent)+$", "").strip();
            if (s.isEmpty() || s.equals("Agent")) continue;
            return s;
        }
        return null;
    }

    private static String mask(String q, List<Mention> ms) {
        StringBuilder sb = new StringBuilder(q);
        for (Mention m : ms) {
            for (int i = m.start(); i < m.end(); i++) sb.setCharAt(i, '□');
        }
        return sb.toString();
    }

    private static boolean has(String s, String regex) {
        return Pattern.compile(regex).matcher(s).find();
    }

    private static NodeRef ref(Entity e, Lexicon lex) {
        return new NodeRef(e.id(), lex.name(e.id()), e.type());
    }

    private static Result ok(Interpretation i) {
        return new Result(i, List.of());
    }

    static Interpretation base(String intent, NodeRef anchor, String anchorRaw, NodeRef target, String direction,
                               Integer depth, String scope, String anchorType) {
        return new Interpretation(intent, anchor, anchorRaw, target, direction, depth, scope,
                anchorType != null ? anchorType : anchor == null ? null : anchor.type().code(),
                null, null, null, null, null, null);
    }

    static Interpretation with(Interpretation i, List<RelType> relationTypes, String groupBy, Map<String, Object> filter,
                               String metric, Integer top, RelationRef relation) {
        return new Interpretation(i.intent(), i.anchor(), i.anchorRaw(), i.target(), i.direction(), i.depth(), i.scope(),
                i.anchorType(), relationTypes, groupBy, filter, metric, top, relation);
    }
}
