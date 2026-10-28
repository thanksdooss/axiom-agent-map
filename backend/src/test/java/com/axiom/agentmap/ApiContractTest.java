package com.axiom.agentmap;

import com.axiom.agentmap.seed.DatasetLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 계약의 JSON 모양(camelCase 필드·노드 수)과 주요 엔드포인트 */
@SpringBootTest(properties = "axiom.llm.provider=none")
@AutoConfigureMockMvc
class ApiContractTest {
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired DatasetLoader loader;

    @AfterEach
    void restore() {
        loader.reset(DatasetLoader.Mode.FULL);
    }

    private JsonNode json(org.springframework.test.web.servlet.RequestBuilder rb) throws Exception {
        return mapper.readTree(mvc.perform(rb).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void graphHas53AgentsAndContractFields() throws Exception {
        JsonNode g = json(get("/api/v1/graph"));
        assertThat(g.path("scope").asString()).isEqualTo("all");
        long agents = 0;
        for (JsonNode n : g.path("nodes")) if (n.path("type").asString().equals("Agent")) agents++;
        assertThat(agents).isEqualTo(53);
        assertThat(g.path("nodes").size()).isEqualTo(53 + 10 + 8 + 17 + 30);
        assertThat(g.path("edges").size()).isEqualTo(326);
        JsonNode node = g.path("nodes").path(0);
        assertThat(fieldNames(node)).containsExactlyInAnyOrder("id", "type", "label", "group", "sensitivity", "agentStatus", "degree");
        JsonNode edge = g.path("edges").path(0);
        assertThat(fieldNames(edge)).contains("id", "type", "typeLabel", "source", "target", "sourceName", "targetName", "status",
                "reviewReason", "evidence", "reviewedBy", "reviewedAt");
        assertThat(fieldNames(edge.path("evidence").path(0))).contains("file", "locator", "text");
        JsonNode confirmed = json(get("/api/v1/graph").param("scope", "confirmed"));
        assertThat(confirmed.path("edges").size()).isEqualTo(302);
    }

    @Test
    void traversalsPathCyclesNeighborhood() throws Exception {
        JsonNode t = json(get("/api/v1/agents/A005/impact"));
        assertThat(fieldNames(t)).containsExactlyInAnyOrder("anchor", "direction", "depth", "scope", "direct", "indirect", "count", "edgeIds", "message");
        assertThat(t.path("count").asInt()).isEqualTo(5);
        assertThat(t.path("direct").path(0).path("via").asString()).isEqualTo("USES_RESULT_OF");
        JsonNode empty = json(get("/api/v1/agents/A013/impact"));
        assertThat(empty.path("message").asString()).isEqualTo("등록된 관계에서는 발견되지 않았습니다. 안전하다고 확정할 수는 없습니다.");
        JsonNode p = json(get("/api/v1/path").param("from", "A051").param("to", "A005"));
        assertThat(p.path("path").size()).isEqualTo(5);
        assertThat(p.path("edgeIds").size()).isEqualTo(4);
        JsonNode c = json(get("/api/v1/cycles"));
        assertThat(c.path("cycles").path(0).path(0).asString()).isEqualTo("A024");
        JsonNode nb = json(get("/api/v1/nodes/A001/neighborhood").param("depth", "1"));
        assertThat(nb.path("center").path("id").asString()).isEqualTo("A001");
        JsonNode d = json(get("/api/v1/data-resources/D001/impact").param("scope", "confirmed"));
        assertThat(d.path("count").asInt()).isEqualTo(10);
        JsonNode pl = json(get("/api/v1/platforms/P01/impact"));
        assertThat(pl.path("count").asInt()).isEqualTo(14);
        mvc.perform(get("/api/v1/agents/A999")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/graph").param("scope", "weird")).andExpect(status().isBadRequest());
    }

    @Test
    void agentsListAndDetail() throws Exception {
        JsonNode list = json(get("/api/v1/agents").param("q", "분석"));
        assertThat(list.size()).isEqualTo(5);
        JsonNode a = json(get("/api/v1/agents/A023").param("scope", "confirmed"));
        assertThat(a.path("departmentId").isNull()).isTrue();
        assertThat(a.path("reviewFlags").size()).isEqualTo(1);
        assertThat(a.path("reviewFlags").path(0).path("candidate").asString()).isEqualTo("DEP05");
        JsonNode all = json(get("/api/v1/agents/A023"));
        assertThat(all.path("departmentId").asString()).isEqualTo("DEP05");
        assertThat(all.path("outgoing").size()).isGreaterThan(0);
        JsonNode dept = json(get("/api/v1/agents").param("department", "none").param("scope", "confirmed"));
        assertThat(dept.size()).isEqualTo(5);
    }

    @Test
    void healthVocabularyReset() throws Exception {
        JsonNode h = json(get("/api/v1/health"));
        assertThat(h.path("status").asString()).isEqualTo("ok");
        assertThat(h.path("llm").path("enabled").asBoolean()).isFalse();
        assertThat(h.path("llm").has("provider")).isTrue();
        JsonNode v = json(get("/api/v1/vocabulary"));
        assertThat(v.path("platforms").size()).isEqualTo(17);
        JsonNode r = json(post("/api/v1/admin/reset").contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"sample\"}"));
        assertThat(r.path("agents").asInt()).isEqualTo(3);
        JsonNode g = json(get("/api/v1/graph"));
        long agents = 0;
        for (JsonNode n : g.path("nodes")) if (n.path("type").asString().equals("Agent")) agents++;
        assertThat(agents).isEqualTo(3);
        assertThat(g.path("edges").size()).isEqualTo(14 + 28);
    }

    @Test
    void multipartImportPreviewAndCommitAfterSampleReset() throws Exception {
        json(post("/api/v1/admin/reset").contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"sample\"}"));
        Path raw = AnswerKey.DATA.resolve("raw");
        var req = multipart("/api/v1/imports");
        try (var files = Files.list(raw)) {
            for (Path f : files.sorted().toList()) {
                req.file(new MockMultipartFile("files[]", f.getFileName().toString(), "application/octet-stream", Files.readAllBytes(f)));
            }
        }
        JsonNode preview = json(req);
        assertThat(preview.path("files").size()).isEqualTo(5);
        assertThat(preview.path("agents").size()).isEqualTo(50);
        assertThat(preview.path("agents").path(0).has("isNew")).isTrue();
        assertThat(preview.path("agents").path(0).path("isNew").asBoolean()).isTrue();
        assertThat(preview.path("relationships").path(0).has("duplicateOf")).isTrue();
        assertThat(preview.path("specialCases").path(0).has("case")).isTrue();
        assertThat(preview.path("pending").size()).isEqualTo(3);
        JsonNode commit = json(post("/api/v1/imports/" + preview.path("batchId").asString() + "/commit"));
        assertThat(commit.path("addedAgents").asInt()).isEqualTo(50);
        assertThat(commit.path("addedRelationships").asInt()).isEqualTo(326 - 42);
        assertThat(commit.path("pending").asInt()).isEqualTo(3);
        mvc.perform(post("/api/v1/imports/" + preview.path("batchId").asString() + "/commit")).andExpect(status().isNotFound());

        // 정답 상태에서 다시 올리면 모두 중복 → 근거만 합침(같은 근거는 다시 넣지 않음)
        loader.reset(DatasetLoader.Mode.FULL);
        JsonNode again = json(post("/api/v1/imports/samples"));
        for (JsonNode a : again.path("agents")) assertThat(a.path("isNew").asBoolean()).isFalse();
        for (JsonNode r : again.path("relationships")) assertThat(r.path("duplicateOf").isNull()).isFalse();
        JsonNode c2 = json(post("/api/v1/imports/" + again.path("batchId").asString() + "/commit"));
        assertThat(c2.path("addedAgents").asInt()).isZero();
        assertThat(c2.path("addedRelationships").asInt()).isZero();
        assertThat(c2.path("pending").asInt()).isZero();
    }

    @Test
    void askAndExtractEndpoints() throws Exception {
        JsonNode r = json(post("/api/v1/ask").contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"고객분석 Agent를 바꾸면 어디를 점검해야 하나?\",\"scope\":\"confirmed\"}"));
        assertThat(r.path("engine").asString()).isEqualTo("rule");
        assertThat(r.path("interpretation").path("intent").asString()).isEqualTo("impact");
        assertThat(r.path("interpretation").path("scope").asString()).isEqualTo("confirmed");
        assertThat(r.path("interpretation").path("anchor").path("type").asString()).isEqualTo("Agent");
        assertThat(r.path("highlight").path("nodeIds").size()).isEqualTo(3);
        assertThat(r.path("answer").asString()).contains("캠페인 문구 생성 Agent");
        JsonNode amb = json(post("/api/v1/ask").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"분석 Agent를 바꾸면 어디를 점검해야 해?\"}"));
        assertThat(amb.path("clarification").path("options").size()).isEqualTo(5);

        JsonNode ex = json(post("/api/v1/extractions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"보도자료 초안 Agent는 캠페인 성과 데이터를 참고한다.\"}"));
        assertThat(ex.path("items").path(0).path("outcome").asString()).isEqualTo("NEW");
        String apply = "{\"items\":" + mapper.writeValueAsString(ex.path("items")) + "}";
        JsonNode created = json(post("/api/v1/extractions/apply").contentType(MediaType.APPLICATION_JSON).content(apply));
        assertThat(created.size()).isEqualTo(1);
        assertThat(created.path(0).path("status").asString()).isEqualTo("CANDIDATE");
        assertThat(created.path(0).path("id").asString()).isEqualTo("R0327");

        JsonNode dup = json(post("/api/v1/extractions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"상품 추천 Agent는 고객 분석 결과와 상품 정보 DB를 사용해.\"}"));
        JsonNode merged = json(post("/api/v1/extractions/apply").contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":" + mapper.writeValueAsString(dup.path("items")) + "}"));
        assertThat(merged.size()).isEqualTo(2);
        assertThat(merged.path(0).path("status").asString()).isEqualTo("SAMPLE");
        assertThat(merged.path(0).path("evidence").size()).isEqualTo(2);
    }

    private static Set<String> fieldNames(JsonNode n) {
        Set<String> s = new HashSet<>();
        n.propertyNames().forEach(s::add);
        return s;
    }

}
