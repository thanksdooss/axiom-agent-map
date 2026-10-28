package com.axiom.agentmap.extraction;

import com.axiom.agentmap.api.ExtractionDtos.ApplyRequest;
import com.axiom.agentmap.api.ExtractionDtos.ExtractionResult;
import com.axiom.agentmap.api.ExtractionDtos.Item;
import com.axiom.agentmap.api.ExtractionDtos.Outcome;
import com.axiom.agentmap.api.ExtractionDtos.Subject;
import com.axiom.agentmap.api.GraphDtos.GraphEdge;
import com.axiom.agentmap.api.GraphDtos.NodeRef;
import com.axiom.agentmap.domain.EvidenceEntity;
import com.axiom.agentmap.domain.NodeType;
import com.axiom.agentmap.domain.PendingEntityRecord;
import com.axiom.agentmap.domain.RelStatus;
import com.axiom.agentmap.domain.RelType;
import com.axiom.agentmap.domain.RelationshipEntity;
import com.axiom.agentmap.domain.Scope;
import com.axiom.agentmap.graph.GraphService;
import com.axiom.agentmap.graph.GraphStore;
import com.axiom.agentmap.graph.GraphView;
import com.axiom.agentmap.importing.ImportNormalizer;
import com.axiom.agentmap.llm.LlmClient;
import com.axiom.agentmap.repository.PendingEntityRepository;
import com.axiom.agentmap.repository.RelationshipRepository;
import com.axiom.agentmap.review.IdGenerator;
import com.axiom.agentmap.text.Cues;
import com.axiom.agentmap.text.Texts;
import com.axiom.agentmap.vocabulary.Lexicon;
import com.axiom.agentmap.vocabulary.Lexicon.How;
import com.axiom.agentmap.vocabulary.Lexicon.Kind;
import com.axiom.agentmap.vocabulary.TextScanner;
import com.axiom.agentmap.vocabulary.TextScanner.Mention;
import com.axiom.agentmap.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 관계 추출(자유 입력 한 문장). 표기 → 어휘 표준화 → 기존 관계와 비교해 결과를 분류합니다.
 * LLM이 있으면 LLM이 원문 표기만 뽑고, 없으면 규칙 기반 문장 분석을 씁니다(둘 다 같은 표준화·분류 규칙).
 */
@Service
public class ExtractionService {
    private static final Logger log = LoggerFactory.getLogger(ExtractionService.class);
    public static final String EVIDENCE_FILE = "AI 관계 추출 (자유 입력)";
    private static final Pattern OBJECT_PHRASE = Pattern.compile(
            "^\\s*(.+?)\\s*(?:을|를|에서|와|과|의 결과를)\\s*(?:다시\\s*)?(?:사용|쓰|씀|참고|읽|호출|연동|가져|받아|활용|이용|조회)");

    /** 문장에서 읽은 원문 표기 (규칙·LLM 공통 중간 형태) */
    record Mentioned(RelType type, String targetRaw, String targetId, How how, String reason, List<String> candidates) {}

    private final GraphStore store;
    private final GraphService graph;
    private final RelationshipRepository relationships;
    private final PendingEntityRepository pending;
    private final IdGenerator ids;
    private final LlmClient llm;
    private final JsonMapper mapper;

    public ExtractionService(GraphStore store, GraphService graph, RelationshipRepository relationships,
                             PendingEntityRepository pending, IdGenerator ids, LlmClient llm, JsonMapper mapper) {
        this.store = store;
        this.graph = graph;
        this.relationships = relationships;
        this.pending = pending;
        this.ids = ids;
        this.llm = llm;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public ExtractionResult extract(String text, String contextAgentId) {
        if (Texts.isBlank(text)) throw ApiException.badRequest("text가 비어 있습니다.");
        Lexicon lex = store.lexicon();
        GraphView g = store.view(Scope.ALL);
        if (contextAgentId != null && !contextAgentId.isBlank() && (g.node(contextAgentId) == null
                || g.node(contextAgentId).type() != NodeType.AGENT)) {
            throw ApiException.notFound("기준 Agent가 없습니다: " + contextAgentId);
        }
        if (llm.enabled()) {
            try {
                return classify(llmMentions(text, lex), text, contextAgentId, lex, g, "llm");
            } catch (RuntimeException e) {
                log.warn("LLM 관계 추출 실패 — 규칙 기반으로 대신합니다: {}", e.getMessage());
            }
        }
        return classify(ruleMentions(text, lex), text, contextAgentId, lex, g, "rule");
    }

    /** 규칙·LLM 공통 결과: 주어 표기 + 대상 표기 목록 + 부정·미래 여부 */
    record Parsed(String subjectRaw, String subjectId, boolean providerSubject, List<Mentioned> targets,
                  boolean negated, boolean future) {}

    // ------------------------------------------------------------------ 규칙 기반

    Parsed ruleMentions(String text, Lexicon lex) {
        List<Lexicon.Term> terms = lex.terms(EnumSet.of(Kind.AGENT, Kind.DATA, Kind.PLATFORM, Kind.DEPARTMENT), true, true, true);
        List<Mention> ms = TextScanner.scan(text, terms, EnumSet.of(Kind.DATA));
        // " Agent" 없는 이름(core)은 뒤에 '결과'가 올 때만 Agent로 봄 ("고객 분석 결과")
        ms = new ArrayList<>(ms.stream().filter(m -> !m.term().core()
                || text.substring(m.end()).replaceFirst("^\\s*(의\\s*)?", "").startsWith("결과")).toList());
        boolean providerSubject = text.contains("이 Agent의") || text.contains("이 Agent를");
        String subjectRaw = null, subjectId = null;
        Mention subjectMention = null;
        for (Mention m : ms) {
            if (m.kind() != Kind.AGENT || m.id() == null || m.term().core()) continue;
            String after = text.substring(m.end());
            if (after.matches("^(는|은|가|이)(\\s.*|$)") || (providerSubject && after.matches("^(가|이)\\s.*"))) {
                subjectMention = m;
                subjectRaw = m.surface();
                subjectId = m.id();
                break;
            }
        }
        List<Mentioned> targets = new ArrayList<>();
        for (Mention m : ms) {
            if (m == subjectMention) continue;
            RelType t = switch (m.kind()) {
                case AGENT -> RelType.USES_RESULT_OF;
                case DATA -> RelType.ACCESSES_DATA;
                case PLATFORM -> RelType.USES_PLATFORM;
                case DEPARTMENT -> RelType.BELONGS_TO;
                default -> null;
            };
            if (t == null) continue;
            targets.add(new Mentioned(t, m.surface(), m.id(), m.term().how(), m.term().reason(), null));
        }
        for (TextScanner.LooseAgent la : TextScanner.looseAgents(text, ms)) {
            if (Cues.NOT_AGENT_WORDS.contains(la.word()) || la.following().startsWith("입니다")) continue;
            if (subjectMention == null && la.following().matches("^(는|은)")) continue;
            List<String> cands = lex.agentsContaining(la.word(), subjectId);
            How how = cands.size() >= 2 ? How.REVIEW : cands.size() == 1 ? How.ALIAS : How.UNKNOWN;
            targets.add(new Mentioned(RelType.USES_RESULT_OF, la.raw(), cands.size() == 1 ? cands.get(0) : null, how,
                    null, cands.size() >= 2 ? cands : null));
        }
        if (targets.isEmpty() && subjectMention != null) {
            Matcher m = OBJECT_PHRASE.matcher(text.substring(subjectMention.end()).replaceFirst("^(는|은|가|이)\\s*", ""));
            if (m.find()) {
                String phrase = m.group(1).strip();
                RelType t = phrase.matches(".*(서비스|API|Agent|에이전트).*") ? RelType.USES_RESULT_OF
                        : phrase.matches(".*(시스템|플랫폼|툴|도구).*") ? RelType.USES_PLATFORM : RelType.ACCESSES_DATA;
                targets.add(new Mentioned(t, phrase, null, How.UNKNOWN, null, null));
            }
        }
        return new Parsed(subjectRaw, subjectId, providerSubject && subjectId != null, targets,
                Cues.negationExpression(text) != null, Cues.has(Cues.FUTURE, text));
    }

    // ------------------------------------------------------------------ LLM

    private static final String SYSTEM = """
            한국어 문장에서 AI Agent 관계를 뽑습니다. 표기는 문장에 적힌 그대로 복사하고 추측해서 바꾸지 않습니다.
            subject: 결과·데이터·플랫폼을 '쓰는 쪽' Agent 표기(없으면 null).
            providerSubject: "X Agent가 이 Agent의 결과를 받아 씁니다"처럼 제공하는 쪽이 주어면 true.
            targets: 쓰는 대상. kind는 agent(다른 Agent·외부 서비스 결과), data, platform, department(소속).
            negated: "사용하지 않는다" 같은 부정문이면 true. future: "예정"·"계획"처럼 아직 아닌 일이면 true.
            """;

    Parsed llmMentions(String text, Lexicon lex) {
        Map<String, Object> target = obj(Map.of("raw", Map.of("type", "string"),
                "kind", Map.of("type", "string", "enum", List.of("agent", "data", "platform", "department"))));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("subject", Map.of("type", List.of("string", "null")));
        props.put("providerSubject", Map.of("type", "boolean"));
        props.put("targets", Map.of("type", "array", "items", target));
        props.put("negated", Map.of("type", "boolean"));
        props.put("future", Map.of("type", "boolean"));
        String user = "등록된 Agent 이름: " + String.join(", ", lex.agentNames().values()) + "\n\n문장: " + text;
        JsonNode n = mapper.readTree(llm.json(SYSTEM, user, "relation_extraction", obj(props)));
        String subjectRaw = n.path("subject").isNull() ? null : n.path("subject").asString(null);
        String subjectId = subjectRaw == null ? null : lex.agentIdByName(subjectRaw);
        List<Mentioned> targets = new ArrayList<>();
        for (JsonNode t : n.path("targets")) {
            String raw = t.path("raw").asString("");
            switch (t.path("kind").asString("")) {
                case "agent" -> {
                    Lexicon.Resolution r = lex.resolve(Kind.AGENT, raw);
                    if (r.known()) targets.add(new Mentioned(RelType.USES_RESULT_OF, raw, r.id(), r.how(), null, null));
                    else if (r.how() == How.UNREGISTERED) targets.add(new Mentioned(RelType.USES_RESULT_OF, raw, null, How.UNREGISTERED, r.reason(), null));
                    else {
                        String core = raw.replaceAll("\\s*(Agent|의 결과|결과)$", "").strip();
                        String id = lex.agentIdByName(core + " Agent");
                        List<String> cands = id != null ? List.of(id) : lex.agentsContaining(core, subjectId);
                        How how = cands.size() >= 2 ? How.REVIEW : cands.size() == 1 ? How.ALIAS : How.UNKNOWN;
                        targets.add(new Mentioned(RelType.USES_RESULT_OF, raw, cands.size() == 1 ? cands.get(0) : null, how,
                                null, cands.size() >= 2 ? cands : null));
                    }
                }
                case "data" -> targets.add(resolved(RelType.ACCESSES_DATA, lex.resolve(Kind.DATA, raw)));
                case "platform" -> targets.add(resolved(RelType.USES_PLATFORM, lex.resolve(Kind.PLATFORM, raw)));
                case "department" -> targets.add(resolved(RelType.BELONGS_TO, lex.resolve(Kind.DEPARTMENT, raw)));
                default -> { }
            }
        }
        return new Parsed(subjectRaw, subjectId, n.path("providerSubject").asBoolean(false), targets,
                n.path("negated").asBoolean(false), n.path("future").asBoolean(false));
    }

    private static Mentioned resolved(RelType t, Lexicon.Resolution r) {
        return new Mentioned(t, r.raw(), r.id(), r.how(), r.reason(), null);
    }

    private static Map<String, Object> obj(Map<String, Object> props) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("properties", props);
        m.put("required", new ArrayList<>(props.keySet()));
        m.put("additionalProperties", false);
        return m;
    }

    // ------------------------------------------------------------------ 분류

    private ExtractionResult classify(Parsed p, String text, String contextAgentId, Lexicon lex, GraphView g, String engine) {
        String subjectId = p.subjectId();
        String subjectRaw = p.subjectRaw();
        boolean flip = p.providerSubject() && subjectId != null && contextAgentId != null;
        if (subjectId == null && !Texts.isBlank(contextAgentId)) subjectId = contextAgentId;
        List<Item> items = new ArrayList<>();
        for (Mentioned m : p.targets()) {
            String source = subjectId;
            String target = m.targetId();
            if (flip && m.type() == RelType.USES_RESULT_OF) {
                source = p.subjectId();
                target = contextAgentId;
            }
            List<NodeRef> options = new ArrayList<>();
            Outcome outcome;
            String existing = null;
            String reason = m.reason();
            if (source == null) {
                outcome = Outcome.NO_SUBJECT;
                reason = "주어(어떤 Agent가 쓰는지)가 없음 — 기준 Agent를 골라 주세요";
            } else if (p.negated()) {
                outcome = Outcome.NEGATED;
                reason = "부정문 — 관계를 만들지 않음";
            } else if (p.future()) {
                outcome = Outcome.FUTURE;
                reason = "미래 계획 — 현재 사용 중인 관계가 아님";
            } else if (target != null && target.equals(source)) {
                outcome = Outcome.SELF_REFERENCE;
                reason = ImportNormalizer.SELF_REFERENCE;
            } else if (m.candidates() != null && m.candidates().size() >= 2) {
                outcome = Outcome.AMBIGUOUS;
                for (String c : m.candidates()) options.add(graph.ref(g, c));
                reason = "'" + m.targetRaw() + "'" + Texts.josa(m.targetRaw(), "과", "와") + " 부분 일치하는 Agent가 여러 개 — 후보를 보여주고 사용자 선택 필요";
            } else if (target == null) {
                outcome = Outcome.UNREGISTERED;
                reason = unregisteredReason(m, g);
            } else {
                GraphView.Rel ex = g.find(m.type(), source, target);
                if (ex != null && ex.status() != RelStatus.REJECTED) {
                    outcome = Outcome.DUPLICATE;
                    existing = ex.id();
                    reason = "기존 관계 " + ex.id() + "(" + ex.status() + ")와 중복 — 근거만 덧붙임";
                } else {
                    outcome = Outcome.NEW;
                    if (m.how() == How.REVIEW && reason == null) reason = "표기가 모호함 — 검토 필요";
                }
            }
            items.add(new Item(m.type(), source, target, m.targetRaw(), outcome, existing, options, text, reason));
        }
        NodeRef resolved = subjectId == null ? null : graph.ref(g, subjectId);
        return new ExtractionResult(engine, new Subject(subjectRaw, resolved), action(items), items);
    }

    private String unregisteredReason(Mentioned m, GraphView g) {
        StringBuilder sb = new StringBuilder(m.reason() != null ? m.reason() : "등록된 대상과 매칭되지 않음 — 미등록 대상 검토 항목으로 보냄");
        for (PendingEntityRecord p : pending.findAll()) {
            if (!"OPEN".equals(p.state)) continue;
            boolean related = Texts.nz(p.evidenceText).contains(m.targetRaw()) || m.targetRaw().contains(p.rawLabel);
            if (related && !p.rawLabel.equals(m.targetRaw())) {
                sb.append(" (이미 올라온 미등록 항목 '").append(p.rawLabel).append("'와 같은 대상일 수 있음)");
            }
        }
        return sb.toString();
    }

    static String action(List<Item> items) {
        if (items.isEmpty()) return "추출된 관계 없음";
        if (items.stream().anyMatch(i -> i.outcome() == Outcome.NO_SUBJECT)) return "기준 Agent 선택 요청";
        Set<String> acts = new LinkedHashSet<>();
        for (Item i : items) {
            acts.add(switch (i.outcome()) {
                case DUPLICATE -> "중복 표시";
                case NEW -> "새 관계 후보로 등록";
                case AMBIGUOUS -> "후보 제시 후 선택 요청";
                case UNREGISTERED -> "미등록 대상 검토 항목으로 보냄";
                case SELF_REFERENCE -> "저장 거부 (자기 참조)";
                case NEGATED -> "관계 없음 (부정문)";
                case FUTURE -> "관계 없음 (미래 계획)";
                case NO_SUBJECT -> "기준 Agent 선택 요청";
            });
        }
        return String.join(" · ", acts);
    }

    // ------------------------------------------------------------------ 적용

    @Transactional
    public List<GraphEdge> apply(ApplyRequest req) {
        if (req == null || req.items() == null) throw ApiException.badRequest("items가 필요합니다.");
        GraphView g = store.view(Scope.ALL);
        List<String> touched = new ArrayList<>();
        int relNo = ids.maxRelationship();
        for (Item it : req.items()) {
            if (it.outcome() == Outcome.NEGATED || it.outcome() == Outcome.FUTURE || it.outcome() == Outcome.SELF_REFERENCE) continue;
            String src = it.sourceId();
            String tgt = it.targetId();
            if (it.outcome() == Outcome.UNREGISTERED || (tgt == null && src != null)) {
                if (src != null && !Texts.isBlank(it.targetRaw())) addPending(it, src);
                continue;
            }
            if (src == null || tgt == null || it.type() == null) continue;
            if (src.equals(tgt)) throw ApiException.badRequest("출발과 도착이 같은 관계(자기 참조)는 저장할 수 없습니다.");
            if (!g.has(src) || !g.has(tgt)) throw ApiException.notFound("등록되지 않은 노드입니다: " + (g.has(src) ? tgt : src));
            if (NodeType.ofId(tgt) != it.type().range()) throw ApiException.badRequest(it.type() + "의 대상 종류가 맞지 않습니다: " + tgt);
            EvidenceEntity ev = new EvidenceEntity(EVIDENCE_FILE, "입력 문장", it.evidenceText(), null, null);
            GraphView.Rel ex = g.find(it.type(), src, tgt);
            RelationshipEntity r;
            if (ex != null) {
                r = relationships.findById(ex.id()).orElseThrow();
                boolean dup = r.evidence.stream().anyMatch(e -> Objects.equals(e.text, it.evidenceText()) && EVIDENCE_FILE.equals(e.file));
                if (!dup) r.addEvidence(ev);
            } else {
                r = new RelationshipEntity();
                r.id = IdGenerator.relId(++relNo);
                r.type = it.type();
                r.sourceId = src;
                r.targetId = tgt;
                r.status = RelStatus.CANDIDATE;
                r.reviewReason = "AI 관계 추출로 등록 — 사람 검토 필요" + (Texts.isBlank(req.reviewer()) ? "" : " (요청: " + req.reviewer() + ")");
                r.addEvidence(ev);
            }
            relationships.save(r);
            touched.add(r.id);
        }
        relationships.flush();
        GraphView after = store.view(Scope.ALL);
        List<GraphEdge> out = new ArrayList<>();
        for (String id : new LinkedHashSet<>(touched)) {
            after.allRels().stream().filter(r -> r.id().equals(id)).findFirst().ifPresent(r -> out.add(graph.edge(after, r)));
        }
        return out;
    }

    private void addPending(Item it, String src) {
        boolean dup = pending.findAll().stream().anyMatch(p -> p.rawLabel.equals(it.targetRaw()) && Objects.equals(p.mentionedBy, src));
        if (dup) return;
        PendingEntityRecord p = new PendingEntityRecord();
        p.id = ids.nextPending();
        p.rawLabel = it.targetRaw();
        p.kind = it.type() == RelType.ACCESSES_DATA ? ImportNormalizer.KIND_DATA
                : it.type() == RelType.USES_PLATFORM ? "Platform" : ImportNormalizer.KIND_AGENT;
        p.mentionedBy = src;
        p.reason = Texts.isBlank(it.reason()) ? "AI 관계 추출에서 등록된 대상과 매칭되지 않음" : it.reason();
        p.evidenceFile = EVIDENCE_FILE;
        p.evidenceLocator = "입력 문장";
        p.evidenceText = it.evidenceText();
        p.state = "OPEN";
        pending.saveAndFlush(p);
    }

}
