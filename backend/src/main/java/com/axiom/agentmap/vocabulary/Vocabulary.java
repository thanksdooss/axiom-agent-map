package com.axiom.agentmap.vocabulary;

import com.axiom.agentmap.config.DataPaths;
import com.axiom.agentmap.domain.RelType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * data/vocabulary/vocabulary.json (SKOS 형태 표준 어휘). 시작할 때 한 번 읽습니다.
 * prefLabel·altLabel은 노드 표에도 들어가고, 검토 필요 표기(needs_review_labels)와
 * 미등록 표기(unregistered_labels)는 표준화 규칙으로만 씁니다.
 */
@Component
public class Vocabulary {
    public record Concept(String id, String prefLabel, List<String> altLabels, String broader, String definition,
                          String sensitivity, String storedIn) {}

    public record ReviewLabel(String candidate, String reason) {}

    private final JsonNode raw;
    private final Map<RelType, String> relationLabels = new EnumMap<>(RelType.class);
    private final List<Concept> departments = new ArrayList<>();
    private final List<Concept> workTypes = new ArrayList<>();
    private final List<Concept> platforms = new ArrayList<>();
    private final List<Concept> dataAssets = new ArrayList<>();
    /** 표준 상태 → 별칭 */
    private final Map<String, List<String>> agentStatus = new LinkedHashMap<>();
    /** kind(platform·data·department…) → 원문 표기 → (추정 대상, 사유) */
    private final Map<String, Map<String, ReviewLabel>> needsReview = new LinkedHashMap<>();
    /** kind(data·agent…) → 원문 표기 → 사유 */
    private final Map<String, Map<String, String>> unregistered = new LinkedHashMap<>();

    public Vocabulary(DataPaths paths, JsonMapper mapper) {
        try {
            this.raw = mapper.readTree(Files.readString(paths.vocabulary()));
        } catch (IOException e) {
            throw new UncheckedIOException("어휘 파일을 읽을 수 없습니다: " + paths.vocabulary(), e);
        }
        for (JsonNode r : raw.path("relation_types")) {
            relationLabels.put(RelType.valueOf(r.path("code").asString()), r.path("prefLabel").asString());
        }
        readConcepts(raw.path("departments"), departments);
        readConcepts(raw.path("work_types"), workTypes);
        readConcepts(raw.path("platforms"), platforms);
        readConcepts(raw.path("data_assets"), dataAssets);
        for (JsonNode s : raw.path("agent_status")) {
            agentStatus.put(s.path("prefLabel").asString(), strings(s.path("altLabel")));
        }
        for (Map.Entry<String, JsonNode> k : raw.path("needs_review_labels").properties()) {
            Map<String, ReviewLabel> m = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> e : k.getValue().properties()) {
                m.put(e.getKey(), new ReviewLabel(text(e.getValue().path("candidate")), text(e.getValue().path("reason"))));
            }
            needsReview.put(k.getKey(), m);
        }
        for (Map.Entry<String, JsonNode> k : raw.path("unregistered_labels").properties()) {
            Map<String, String> m = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> e : k.getValue().properties()) {
                m.put(e.getKey(), e.getValue().asString());
            }
            unregistered.put(k.getKey(), m);
        }
    }

    private static void readConcepts(JsonNode arr, List<Concept> out) {
        for (JsonNode c : arr) {
            out.add(new Concept(c.path("id").asString(), c.path("prefLabel").asString(), strings(c.path("altLabel")),
                    text(c.path("broader")), text(c.path("definition")), text(c.path("sensitivity")),
                    text(c.path("stored_in"))));
        }
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) out.add(n.asString());
        return out;
    }

    private static String text(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() ? null : n.asString();
    }

    public JsonNode raw() { return raw; }

    public String relationLabel(RelType t) { return relationLabels.getOrDefault(t, t.label()); }

    public List<Concept> departments() { return Collections.unmodifiableList(departments); }
    public List<Concept> workTypes() { return Collections.unmodifiableList(workTypes); }
    public List<Concept> platforms() { return Collections.unmodifiableList(platforms); }
    public List<Concept> dataAssets() { return Collections.unmodifiableList(dataAssets); }
    public Map<String, List<String>> agentStatus() { return Collections.unmodifiableMap(agentStatus); }

    public Map<String, ReviewLabel> needsReview(String kind) {
        return needsReview.getOrDefault(kind, Map.of());
    }

    public Map<String, String> unregistered(String kind) {
        return unregistered.getOrDefault(kind, Map.of());
    }
}
