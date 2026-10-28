package com.axiom.agentmap;

import com.axiom.agentmap.api.ImportDtos.CommitResult;
import com.axiom.agentmap.api.ImportDtos.ImportPreview;
import com.axiom.agentmap.domain.AgentEntity;
import com.axiom.agentmap.domain.RelationshipEntity;
import com.axiom.agentmap.importing.ImportService;
import com.axiom.agentmap.repository.AgentRepository;
import com.axiom.agentmap.repository.PendingEntityRepository;
import com.axiom.agentmap.repository.RelationshipRepository;
import com.axiom.agentmap.seed.DatasetLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/** reset sample → imports/samples → commit 하면 정답 DB 상태(Agent·관계 집합)가 그대로 나와야 함 */
@SpringBootTest(properties = "axiom.llm.provider=none")
class ImportReproducesAnswerKeyTest {
    @Autowired DatasetLoader loader;
    @Autowired ImportService imports;
    @Autowired AgentRepository agents;
    @Autowired RelationshipRepository relationships;
    @Autowired PendingEntityRepository pending;

    @AfterEach
    void restore() {
        loader.reset(DatasetLoader.Mode.FULL);
    }

    @Test
    void sampleImportReproducesAnswerKey() {
        loader.reset(DatasetLoader.Mode.SAMPLE);
        assertThat(agents.count()).isEqualTo(3);
        ImportPreview preview = imports.preview(imports.sampleFiles());
        assertThat(preview.files()).extracting(f -> f.parser()).containsExactly("structured", "structured", "structured", "structured", "rule");
        assertThat(preview.agents()).hasSize(50);

        JsonNode key = AnswerKey.extraction();
        // 특수 사례 (case, agent)
        Set<String> expectedCases = new TreeSet<>();
        for (JsonNode c : key.path("special_cases_in_files")) expectedCases.add(c.path("case").asString() + "|" + c.path("agent").asString());
        Set<String> actualCases = new TreeSet<>();
        preview.specialCases().forEach(c -> actualCases.add(c.caseName() + "|" + c.agentId()));
        assertThat(actualCases).isEqualTo(expectedCases);
        // 모호한 이름 후보
        preview.specialCases().stream().filter(c -> c.caseName().equals("모호한 이름")).forEach(c ->
                assertThat(c.candidates()).containsExactly("A001", "A011", "A015", "A037", "A042"));

        CommitResult result = imports.commit(preview.batchId());
        assertThat(result.addedAgents()).isEqualTo(50);
        assertThat(result.pending()).isEqualTo(3);

        // Agent 표준값 비교
        Map<String, AgentEntity> byId = new TreeMap<>();
        agents.findAll().forEach(a -> byId.put(a.id, a));
        List<String> mismatches = new ArrayList<>();
        for (JsonNode a : key.path("agents")) {
            String id = a.path("id").asString();
            AgentEntity e = byId.get(id);
            assertThat(e).as(id).isNotNull();
            check(mismatches, id, "name", a.path("name").asString(), e.name);
            check(mismatches, id, "department", text(a.path("department")), e.departmentId);
            List<String> owners = new ArrayList<>();
            a.path("owners").forEach(o -> owners.add(o.asString()));
            check(mismatches, id, "owners", String.join("; ", owners), e.owners);
            check(mismatches, id, "work_type", text(a.path("work_type")), e.workTypeId);
            check(mismatches, id, "description", text(a.path("description")), e.description);
            check(mismatches, id, "start_date", text(a.path("start_date")), e.startDate);
            check(mismatches, id, "monthly_calls", a.path("monthly_calls").isNull() ? null : String.valueOf(a.path("monthly_calls").asInt()),
                    e.monthlyCalls == null ? null : String.valueOf(e.monthlyCalls));
            check(mismatches, id, "status", text(a.path("status")), e.status);
            check(mismatches, id, "source_file", text(a.path("source_file")), e.sourceFile);
            JsonNode flags = AnswerKey.mapper().readTree(e.reviewFlagsJson);
            check(mismatches, id, "review", AnswerKey.mapper().writeValueAsString(a.path("review")),
                    AnswerKey.mapper().writeValueAsString(flags));
        }
        assertThat(mismatches).isEmpty();

        // 관계 집합 (type, source, target, status)
        Set<String> expected = new TreeSet<>();
        for (JsonNode r : key.path("relationships")) {
            expected.add(r.path("type").asString() + "|" + r.path("source").asString() + "|" + r.path("target").asString()
                    + "|" + r.path("status").asString());
        }
        Set<String> actual = new TreeSet<>();
        for (RelationshipEntity r : relationships.findAll()) actual.add(r.type + "|" + r.sourceId + "|" + r.targetId + "|" + r.status);
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        Set<String> extra = new TreeSet<>(actual);
        extra.removeAll(expected);
        assertThat(missing).as("빠진 관계").isEmpty();
        assertThat(extra).as("남는 관계").isEmpty();

        // 근거 건수(중복 진술 합치기: 구매 계약 검토 → 계약서 검토 근거 2건)
        RelationshipEntity merged = relationships.findAllWithEvidence().stream()
                .filter(r -> r.sourceId.equals("A031") && r.targetId.equals("A044")).findFirst().orElseThrow();
        assertThat(merged.evidence).hasSize(2);
        assertThat(merged.evidence.get(1).directionNote).contains("구매 계약 검토 Agent → 계약서 검토 Agent");

        // 미등록 대상
        Set<String> expPending = new TreeSet<>();
        key.path("pending_entities").forEach(p -> expPending.add(p.path("raw").asString() + "|" + p.path("kind").asString() + "|" + p.path("mentioned_by").asString()));
        Set<String> actPending = new TreeSet<>();
        pending.findAll().forEach(p -> actPending.add(p.rawLabel + "|" + p.kind + "|" + p.mentionedBy));
        assertThat(actPending).isEqualTo(expPending);
    }

    private static String text(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode() ? null : n.asString();
    }

    private static void check(List<String> out, String id, String field, String expected, String actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            out.add(id + "." + field + ": expected=" + expected + " actual=" + actual);
        }
    }
}
