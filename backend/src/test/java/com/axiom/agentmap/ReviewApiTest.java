package com.axiom.agentmap;

import com.axiom.agentmap.seed.DatasetLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 검토 PATCH(확정·반려·대상 변경)와 미등록 대상 처리 — HTTP 계층까지 */
@SpringBootTest(properties = "axiom.llm.provider=none")
@AutoConfigureMockMvc
class ReviewApiTest {
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired DatasetLoader loader;

    @AfterEach
    void restore() {
        loader.reset(DatasetLoader.Mode.FULL);
    }

    private JsonNode json(org.springframework.test.web.servlet.RequestBuilder rb, int status) throws Exception {
        String body = mvc.perform(rb).andExpect(status().is(status)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return mapper.readTree(body);
    }

    private JsonNode patchRel(String id, String body) throws Exception {
        return json(patch("/api/v1/relationships/" + id).contentType(MediaType.APPLICATION_JSON).content(body), 200);
    }

    @Test
    void confirmRejectAndRetarget() throws Exception {
        String today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
        JsonNode cands = json(get("/api/v1/relationships").param("status", "CANDIDATE"), 200);
        assertThat(cands.size()).isEqualTo(10);

        // 확정: 기본 검토자 "검토자", 오늘 날짜
        JsonNode confirmed = patchRel("R0025", "{\"action\":\"confirm\"}");
        assertThat(confirmed.path("status").asString()).isEqualTo("CONFIRMED");
        assertThat(confirmed.path("reviewedBy").asString()).isEqualTo("검토자");
        assertThat(confirmed.path("reviewedAt").asString()).isEqualTo(today);
        assertThat(confirmed.path("typeLabel").asString()).isEqualTo("사용 플랫폼");

        // 반려: 그래프·집계에서 빠짐
        JsonNode rejected = patchRel("R0029", "{\"action\":\"reject\",\"reviewer\":\"김검토\"}");
        assertThat(rejected.path("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.path("reviewedBy").asString()).isEqualTo("김검토");
        JsonNode graph = json(get("/api/v1/graph"), 200);
        boolean present = false;
        for (JsonNode e : graph.path("edges")) if (e.path("id").asString().equals("R0029")) present = true;
        assertThat(present).isFalse();
        JsonNode dash = json(get("/api/v1/dashboard"), 200);
        assertThat(dash.path("relationshipsByStatus").path("CANDIDATE").asInt()).isEqualTo(8);
        assertThat(dash.path("relationshipsByStatus").path("CONFIRMED").asInt()).isEqualTo(303);

        // 대상 변경: 모호한 이름(분석 Agent)에서 다른 후보 선택
        JsonNode amb = json(get("/api/v1/relationships").param("status", "CANDIDATE").param("nodeId", "A047"), 200);
        JsonNode r0261 = null;
        for (JsonNode e : amb) if (e.path("id").asString().equals("R0261")) r0261 = e;
        assertThat(r0261).isNotNull();
        assertThat(r0261.path("candidatesShown").size()).isEqualTo(5);
        JsonNode retarget = patchRel("R0261", "{\"action\":\"confirm\",\"targetId\":\"A001\"}");
        assertThat(retarget.path("target").asString()).isEqualTo("A001");
        assertThat(retarget.path("targetName").asString()).isEqualTo("고객분석 Agent");
        assertThat(retarget.path("status").asString()).isEqualTo("CONFIRMED");
        JsonNode impact = json(get("/api/v1/agents/A001/impact"), 200);
        boolean found = false;
        for (JsonNode x : impact.path("direct")) if (x.path("id").asString().equals("A047")) found = true;
        assertThat(found).isTrue();

        // 잘못된 대상 종류·자기 참조·없는 관계
        json(patch("/api/v1/relationships/R0254").contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"confirm\",\"targetId\":\"P01\"}"), 400);
        json(patch("/api/v1/relationships/R9999").contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"confirm\"}"), 404);
        JsonNode err = json(patch("/api/v1/relationships/R0025").contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"maybe\"}"), 400);
        assertThat(err.path("error").asString()).isEqualTo("BAD_REQUEST");
    }

    @Test
    void retargetChangesTargetOrMergesIntoExisting() throws Exception {
        // R0284(이사회 자료 작성 → 매출 실적, CANDIDATE)의 대상을 회계 전표로: 없던 관계라 대상만 바뀜
        JsonNode r = patchRel("R0284", "{\"action\":\"confirm\",\"targetId\":\"D023\"}");
        assertThat(r.path("id").asString()).isEqualTo("R0284");
        assertThat(r.path("target").asString()).isEqualTo("D023");
        // R0288(예산 편성 보조 → 급여 정보)의 대상을 회계 전표로: 같은 관계가 이미 있어 근거를 그쪽으로 합치고 후보는 지움
        JsonNode merged = patchRel("R0288", "{\"action\":\"confirm\",\"targetId\":\"D023\"}");
        assertThat(merged.path("id").asString()).isNotEqualTo("R0288");
        assertThat(merged.path("evidence").size()).isEqualTo(2);
        JsonNode left = json(get("/api/v1/relationships").param("status", "CANDIDATE").param("nodeId", "A052"), 200);
        assertThat(left.size()).isZero();
    }

    @Test
    void pendingEntitiesResolve() throws Exception {
        JsonNode list = json(get("/api/v1/pending-entities"), 200);
        assertThat(list.size()).isEqualTo(3);
        assertThat(list.path(0).path("state").asString()).isEqualTo("OPEN");
        assertThat(list.path(0).path("mentionedByName").asString()).isEqualTo("협력사 평가 Agent");
        JsonNode reg = json(post("/api/v1/pending-entities/PE02/resolve").contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"register\",\"name\":\"소송 관련 문서\"}"), 200);
        assertThat(reg.path("state").asString()).isEqualTo("REGISTERED");
        JsonNode deps = json(get("/api/v1/agents/A047/dependencies").param("depth", "1"), 200);
        boolean hasNew = false;
        for (JsonNode x : deps.path("direct")) if (x.path("name").asString().equals("소송 관련 문서")) hasNew = true;
        assertThat(hasNew).isTrue();
        JsonNode map = json(post("/api/v1/pending-entities/PE03/resolve").contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"dismiss\"}"), 200);
        assertThat(map.path("state").asString()).isEqualTo("DISMISSED");
        JsonNode dash = json(get("/api/v1/dashboard"), 200);
        assertThat(dash.path("pendingEntities").asInt()).isEqualTo(1);
    }
}
