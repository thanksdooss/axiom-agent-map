package com.axiom.agentmap.importing;

import com.axiom.agentmap.api.GraphDtos.Evidence;
import com.axiom.agentmap.domain.RelType;
import com.axiom.agentmap.importing.SubmittedAgent.Raw;
import com.axiom.agentmap.llm.LlmClient;
import com.axiom.agentmap.llm.LlmException;
import com.axiom.agentmap.vocabulary.Lexicon;
import com.axiom.agentmap.vocabulary.Lexicon.Kind;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Word 서술형을 LLM으로 읽어 "원문 표기 + 문장 번호"만 받습니다. ID·관계 상태는 정하지 않고,
 * 받은 표기를 규칙 추출과 같은 SubmittedAgent로 바꿔 어휘 표준화(ImportNormalizer)에 넘깁니다.
 */
public class LlmNarrativeExtractor {
    private static final String SYSTEM = """
            당신은 사내 AI Agent 현황 보고서(한국어 서술형)에서 사실만 뽑는 추출기입니다.
            규칙:
            - 표기는 문장에 적힌 그대로 복사합니다. 표준 어휘 목록에 같은 표기가 있으면 그 표기를 씁니다. 바꾸거나 번역하지 않습니다.
            - 문장 번호(sentence)는 1부터 셉니다.
            - usesResultOf: 이 Agent가 결과를 가져다 쓰는(호출·참고하는) 다른 Agent·외부 서비스 표기.
            - providedTo: "X Agent가 이 Agent의 결과를 받아 씁니다"처럼 제공하는 쪽(이 Agent)이 주어인 문장에서 X.
            - "사용하지 않습니다" 같은 부정문의 대상은 negated에만, "예정"·"계획"은 future에만 넣고 관계 목록에는 넣지 않습니다.
            - 이름 일부만 적힌 Agent(예: "분석 Agent")도 적힌 그대로 usesResultOf에 넣습니다. 추측해서 완성하지 않습니다.
            - 값이 없으면 label을 null로 둡니다.
            """;

    private final LlmClient llm;
    private final JsonMapper mapper;

    public LlmNarrativeExtractor(LlmClient llm, JsonMapper mapper) {
        this.llm = llm;
        this.mapper = mapper;
    }

    public List<SubmittedAgent> extract(String file, List<ParsedFile.NarrativeSection> sections, Lexicon lex) {
        StringBuilder user = new StringBuilder();
        user.append("표준 어휘(참고용 표기 목록):\n");
        for (Kind k : EnumSet.of(Kind.DEPARTMENT, Kind.WORK_TYPE, Kind.PLATFORM, Kind.DATA)) {
            List<String> labels = lex.terms(EnumSet.of(k), true, true, false).stream().map(Lexicon.Term::label).distinct().toList();
            user.append("- ").append(k.vocabKey).append(": ").append(String.join(", ", labels)).append('\n');
        }
        user.append("- 등록된 Agent 이름: ").append(String.join(", ", lex.agentNames().values())).append("\n\n");
        for (int i = 0; i < sections.size(); i++) {
            ParsedFile.NarrativeSection s = sections.get(i);
            user.append("[Agent ").append(i).append("] ").append(s.agentName()).append('\n');
            for (int j = 0; j < s.sentences().size(); j++) {
                user.append("  (").append(j + 1).append(") ").append(s.sentences().get(j)).append('\n');
            }
        }
        String json = llm.json(SYSTEM, user.toString(), "narrative_extraction", schema());
        JsonNode root = mapper.readTree(json);
        List<SubmittedAgent> out = new ArrayList<>();
        for (int i = 0; i < sections.size(); i++) {
            ParsedFile.NarrativeSection sec = sections.get(i);
            JsonNode n = null;
            for (JsonNode x : root.path("agents")) if (x.path("index").asInt(-1) == i) n = x;
            if (n == null) throw new LlmException("LLM 응답에 Agent " + i + "가 없습니다.");
            out.add(toAgent(file, i, sec, n));
        }
        return out;
    }

    private SubmittedAgent toAgent(String file, int index, ParsedFile.NarrativeSection sec, JsonNode n) {
        SubmittedAgent a = new SubmittedAgent();
        a.file = file;
        a.format = "docx";
        a.index = index;
        a.name = new Raw(sec.agentName(), Evidence.of(file, "문단 " + sec.headingParagraph(), sec.agentName()));
        if (sec.submitterText() != null) {
            a.submitter = new Raw(sec.submitterText(), Evidence.of(file, sec.submitterLocator(), sec.submitterText()));
        }
        a.dept = raw(file, sec, n.path("department"));
        a.owner = raw(file, sec, n.path("owner"));
        a.type = raw(file, sec, n.path("workType"));
        a.desc = new Raw(n.path("description").isNull() ? "" : n.path("description").asString(""), a.type.evidence());
        a.date = raw(file, sec, n.path("startDate"));
        a.usage = raw(file, sec, n.path("monthlyUsage"));
        a.status = raw(file, sec, n.path("status"));
        list(file, sec, n.path("platforms"), a.platforms);
        list(file, sec, n.path("data"), a.data);
        list(file, sec, n.path("usesResultOf"), a.calls);
        list(file, sec, n.path("providedTo"), a.providedTo);
        for (JsonNode x : n.path("negated")) {
            RelType t = switch (x.path("kind").asString("")) {
                case "platform" -> RelType.USES_PLATFORM;
                case "agent" -> RelType.USES_RESULT_OF;
                default -> RelType.ACCESSES_DATA;
            };
            Raw r = raw(file, sec, x);
            a.negated.add(new SubmittedAgent.Negated(t, r.text(), x.path("expression").asString("사용하지 않음"), r.evidence()));
        }
        for (JsonNode x : n.path("future")) {
            Raw r = raw(file, sec, x);
            a.futures.add(new SubmittedAgent.Future(r.evidence().text(), r.evidence()));
        }
        return a;
    }

    private static Raw raw(String file, ParsedFile.NarrativeSection sec, JsonNode x) {
        String label = x.path("label").isNull() || x.path("label").isMissingNode() ? "" : x.path("label").asString("");
        int s = x.path("sentence").asInt(0);
        Evidence ev = s >= 1 && s <= sec.sentences().size()
                ? Evidence.of(file, "문단 " + sec.paragraph() + " 문장 " + s, sec.sentences().get(s - 1))
                : Evidence.of(file, "문단 " + sec.paragraph(), "");
        return new Raw(label, ev);
    }

    private static void list(String file, ParsedFile.NarrativeSection sec, JsonNode arr, List<Raw> out) {
        for (JsonNode x : arr) {
            Raw r = raw(file, sec, x);
            if (!r.text().isBlank()) out.add(r);
        }
    }

    private static Map<String, Object> labeled() {
        return obj(Map.of("label", Map.of("type", List.of("string", "null")), "sentence", Map.of("type", "integer")));
    }

    private static Map<String, Object> obj(Map<String, Object> props) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("properties", props);
        m.put("required", new ArrayList<>(props.keySet()));
        m.put("additionalProperties", false);
        return m;
    }

    static Map<String, Object> schema() {
        Map<String, Object> labeledList = Map.of("type", "array", "items", labeled());
        Map<String, Object> negated = obj(Map.of("label", Map.of("type", "string"), "sentence", Map.of("type", "integer"),
                "kind", Map.of("type", "string", "enum", List.of("data", "platform", "agent")),
                "expression", Map.of("type", "string")));
        Map<String, Object> future = obj(Map.of("label", Map.of("type", List.of("string", "null")), "sentence", Map.of("type", "integer")));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("index", Map.of("type", "integer"));
        props.put("department", labeled());
        props.put("owner", labeled());
        props.put("workType", labeled());
        props.put("description", Map.of("type", "string"));
        props.put("platforms", labeledList);
        props.put("data", labeledList);
        props.put("usesResultOf", labeledList);
        props.put("providedTo", labeledList);
        props.put("negated", Map.of("type", "array", "items", negated));
        props.put("future", Map.of("type", "array", "items", future));
        props.put("startDate", labeled());
        props.put("monthlyUsage", labeled());
        props.put("status", labeled());
        return obj(Map.of("agents", Map.of("type", "array", "items", obj(props))));
    }
}
