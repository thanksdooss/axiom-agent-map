package com.axiom.agentmap;

import com.axiom.agentmap.api.ExtractionDtos.ExtractionResult;
import com.axiom.agentmap.api.ExtractionDtos.Item;
import com.axiom.agentmap.api.ExtractionDtos.Outcome;
import com.axiom.agentmap.extraction.ExtractionService;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** free_text_cases T01~T08: 규칙 엔진이 expected_action을 내고, 정답 관계는 기존 관계와 중복으로 표시 */
@SpringBootTest(properties = "axiom.llm.provider=none")
class FreeTextExtractionTest {
    @Autowired ExtractionService extraction;

    @TestFactory
    List<DynamicTest> freeTextCases() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : AnswerKey.extraction().path("free_text_cases")) {
            tests.add(DynamicTest.dynamicTest(c.path("id").asString() + " " + c.path("input").asString(), () -> check(c)));
        }
        assertThat(tests).hasSize(8);
        return tests;
    }

    private void check(JsonNode c) {
        String ctx = c.path("context_agent").isNull() ? null : c.path("context_agent").asString();
        ExtractionResult r = extraction.extract(c.path("input").asString(), ctx);
        assertThat(r.engine()).isEqualTo("rule");
        assertThat(r.action()).isEqualTo(c.path("expected_action").asString());
        assertThat(r.items()).as("새 관계는 만들지 않음").noneMatch(i -> i.outcome() == Outcome.NEW);
        JsonNode expected = c.path("expected");
        if (expected.isEmpty()) return;
        assertThat(r.items()).hasSize(expected.size());
        for (JsonNode e : expected) {
            Item item = r.items().stream().filter(i -> i.type().name().equals(e.path("type").asString())
                    && e.path("source").asString().equals(i.sourceId()) && e.path("target").asString().equals(i.targetId()))
                    .findFirst().orElseThrow(() -> new AssertionError("없는 관계: " + e));
            assertThat(item.outcome()).isEqualTo(Outcome.DUPLICATE);
            assertThat(item.existingRelId()).isEqualTo(e.path("existing").asString().split(" ")[0]);
        }
    }

    @TestFactory
    List<DynamicTest> detailsOfSpecialCases() {
        return List.of(
                DynamicTest.dynamicTest("T03 후보 5개", () -> {
                    ExtractionResult r = extraction.extract("분석 Agent의 결과를 사용해.", "A012");
                    assertThat(r.subject().resolved().id()).isEqualTo("A012");
                    assertThat(r.items().get(0).options()).extracting(o -> o.id())
                            .containsExactly("A001", "A011", "A015", "A037", "A042");
                }),
                DynamicTest.dynamicTest("T04 미등록 + 마켓인텔 API 연관", () -> {
                    ExtractionResult r = extraction.extract("시장 동향 리서치 Agent는 외부 시장 분석 서비스를 사용해.", null);
                    assertThat(r.items().get(0).outcome()).isEqualTo(Outcome.UNREGISTERED);
                    assertThat(r.items().get(0).targetRaw()).isEqualTo("외부 시장 분석 서비스");
                    assertThat(r.items().get(0).reason()).contains("마켓인텔 API");
                }),
                DynamicTest.dynamicTest("T05 자기 참조", () -> {
                    ExtractionResult r = extraction.extract("견적서 자동작성 Agent는 견적서 자동작성 Agent의 이전 결과를 다시 사용해.", null);
                    assertThat(r.items()).extracting(Item::outcome).containsExactly(Outcome.SELF_REFERENCE);
                }),
                DynamicTest.dynamicTest("새 관계는 NEW", () -> {
                    ExtractionResult r = extraction.extract("보도자료 초안 Agent는 캠페인 성과 데이터를 참고한다.", null);
                    assertThat(r.items()).extracting(Item::outcome).containsExactly(Outcome.NEW);
                    assertThat(r.items().get(0).targetId()).isEqualTo("D008");
                    assertThat(r.action()).isEqualTo("새 관계 후보로 등록");
                }));
    }
}
