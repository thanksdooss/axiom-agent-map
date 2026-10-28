package com.axiom.agentmap.vocabulary;

import com.axiom.agentmap.domain.NodeType;
import com.axiom.agentmap.text.Texts;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 표기 → 표준 ID 사전. 현재 저장된 노드 이름(prefLabel)·별칭(altLabel)에
 * 어휘의 검토 필요 표기·미등록 표기를 더해 만듭니다.
 * resolve는 build_dataset.py의 resolve와 같은 순서(정확·별칭 → 검토 필요 → 미등록)입니다.
 */
public final class Lexicon {
    public enum Kind {
        DEPARTMENT("department", NodeType.DEPARTMENT), WORK_TYPE("work_type", NodeType.WORK_TYPE),
        PLATFORM("platform", NodeType.PLATFORM), DATA("data", NodeType.DATA_ASSET),
        AGENT("agent", NodeType.AGENT), STATUS("status", null);

        public final String vocabKey;
        public final NodeType nodeType;

        Kind(String vocabKey, NodeType nodeType) {
            this.vocabKey = vocabKey;
            this.nodeType = nodeType;
        }

        public static Kind of(NodeType t) {
            for (Kind k : values()) if (k.nodeType == t) return k;
            return null;
        }
    }

    public enum How { EXACT, ALIAS, REVIEW, UNREGISTERED, UNKNOWN }

    public record Resolution(Kind kind, String raw, String id, How how, String reason) {
        public boolean known() { return how == How.EXACT || how == How.ALIAS || how == How.REVIEW; }
        public boolean confirmed() { return how == How.EXACT || how == How.ALIAS; }
    }

    /** 문장 스캔용 표기. core=true는 Agent 이름에서 " Agent"를 뗀 형태(예: "고객 분석"). */
    public record Term(Kind kind, String label, String compact, String id, How how, String reason, boolean core) {}

    private final Map<Kind, Map<String, Resolution>> exact = new EnumMap<>(Kind.class);
    private final Map<Kind, Map<String, Resolution>> loose = new EnumMap<>(Kind.class);
    private final Map<String, String> agentByCompact = new LinkedHashMap<>();
    private final Map<String, String> names = new TreeMap<>();
    private final Map<String, String> statusPref = new LinkedHashMap<>();
    private final Vocabulary vocab;

    public record NodeLabel(String id, String name, Collection<String> altLabels) {}

    public Lexicon(Vocabulary vocab, List<NodeLabel> departments, List<NodeLabel> workTypes,
                   List<NodeLabel> platforms, List<NodeLabel> data, List<NodeLabel> agents) {
        this.vocab = vocab;
        for (Kind k : Kind.values()) {
            exact.put(k, new LinkedHashMap<>());
            loose.put(k, new LinkedHashMap<>());
        }
        index(Kind.DEPARTMENT, departments);
        index(Kind.WORK_TYPE, workTypes);
        index(Kind.PLATFORM, platforms);
        index(Kind.DATA, data);
        for (NodeLabel a : agents) addAgent(a.id(), a.name());
        vocab.agentStatus().forEach((pref, alts) -> {
            put(Kind.STATUS, pref, new Resolution(Kind.STATUS, pref, pref, How.EXACT, null));
            for (String a : alts) put(Kind.STATUS, a, new Resolution(Kind.STATUS, a, pref, How.ALIAS, null));
        });
    }

    private void index(Kind kind, List<NodeLabel> nodes) {
        for (NodeLabel n : nodes) {
            names.put(n.id(), n.name());
            put(kind, n.name(), new Resolution(kind, n.name(), n.id(), How.EXACT, null));
        }
        for (NodeLabel n : nodes) {
            if (n.altLabels() == null) continue;
            for (String a : n.altLabels()) {
                if (!Texts.isBlank(a)) put(kind, a, new Resolution(kind, a, n.id(), How.ALIAS, null));
            }
        }
    }

    private void put(Kind kind, String label, Resolution r) {
        exact.get(kind).putIfAbsent(label, r);
        loose.get(kind).putIfAbsent(Texts.compact(label).toLowerCase(), r);
    }

    public void addAgent(String id, String name) {
        names.put(id, name);
        agentByCompact.putIfAbsent(Texts.compact(name), id);
    }

    public String name(String id) { return names.get(id); }

    public Map<String, String> agentNames() {
        Map<String, String> out = new TreeMap<>();
        agentByCompact.forEach((k, id) -> out.put(id, names.get(id)));
        return out;
    }

    /** 공백을 무시하고 Agent 이름이 정확히 같은지 */
    public String agentIdByName(String raw) {
        return raw == null ? null : agentByCompact.get(Texts.compact(raw));
    }

    /** 이름에 token(공백 무시)이 들어 있는 Agent ID 목록 (ID 오름차순) */
    public List<String> agentsContaining(String token, String excludeId) {
        String t = Texts.compact(token);
        List<String> out = new ArrayList<>();
        if (t.isEmpty()) return out;
        agentByCompact.forEach((k, id) -> {
            if (!id.equals(excludeId) && k.contains(t)) out.add(id);
        });
        out.sort(String::compareTo);
        return out;
    }

    /**
     * 원문 표기 → 표준 ID. 정확·별칭 일치(EXACT/ALIAS) → 검토 필요 표기(REVIEW) → 미등록 표기(UNREGISTERED) → UNKNOWN.
     * 정확히 같은 표기가 없으면 공백·대소문자만 다른 표기도 별칭으로 봅니다.
     */
    public Resolution resolve(Kind kind, String raw) {
        String s = raw == null ? "" : raw.strip();
        if (kind == Kind.AGENT) {
            String id = agentIdByName(s);
            if (id != null) return new Resolution(kind, s, id, names.get(id).equals(s) ? How.EXACT : How.ALIAS, null);
        } else {
            Resolution r = exact.get(kind).get(s);
            if (r != null) return new Resolution(kind, s, r.id(), r.how(), null);
        }
        Vocabulary.ReviewLabel rv = vocab.needsReview(kind.vocabKey).get(s);
        if (rv != null) return new Resolution(kind, s, rv.candidate(), How.REVIEW, rv.reason());
        String ur = vocab.unregistered(kind.vocabKey).get(s);
        if (ur != null) return new Resolution(kind, s, null, How.UNREGISTERED, ur);
        if (kind != Kind.AGENT) {
            Resolution r = loose.get(kind).get(Texts.compact(s).toLowerCase());
            if (r != null && !s.isEmpty()) return new Resolution(kind, s, r.id(), How.ALIAS, null);
        }
        return new Resolution(kind, s, null, How.UNKNOWN, null);
    }

    /** 스캔용 표기 목록. review·unregistered 표기도 포함할지 고릅니다. */
    public List<Term> terms(Set<Kind> kinds, boolean includeReview, boolean includeUnregistered, boolean agentCores) {
        List<Term> out = new ArrayList<>();
        for (Kind k : kinds) {
            if (k == Kind.AGENT) {
                agentByCompact.forEach((c, id) -> {
                    String name = names.get(id);
                    out.add(new Term(k, name, c, id, How.EXACT, null, false));
                    if (agentCores) {
                        String core = name.replaceAll("\\s*Agent$", "");
                        String cc = Texts.compact(core);
                        if (!core.equals(name) && cc.length() >= 3) out.add(new Term(k, core, cc, id, How.ALIAS, null, true));
                    }
                });
            } else if (k != Kind.STATUS) {
                exact.get(k).forEach((label, r) -> out.add(new Term(k, label, Texts.compact(label), r.id(), r.how(), null, false)));
            }
            if (includeReview) {
                vocab.needsReview(k.vocabKey).forEach((label, rv) ->
                        out.add(new Term(k, label, Texts.compact(label), rv.candidate(), How.REVIEW, rv.reason(), false)));
            }
            if (includeUnregistered) {
                vocab.unregistered(k.vocabKey).forEach((label, reason) ->
                        out.add(new Term(k, label, Texts.compact(label), null, How.UNREGISTERED, reason, false)));
            }
        }
        return out;
    }
}
