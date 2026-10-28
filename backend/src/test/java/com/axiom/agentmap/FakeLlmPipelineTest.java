package com.axiom.agentmap;

import com.axiom.agentmap.api.AskDtos.AskResponse;
import com.axiom.agentmap.api.ExtractionDtos.ExtractionResult;
import com.axiom.agentmap.api.ExtractionDtos.Outcome;
import com.axiom.agentmap.api.ImportDtos.ImportPreview;
import com.axiom.agentmap.ask.AskService;
import com.axiom.agentmap.domain.Scope;
import com.axiom.agentmap.extraction.ExtractionService;
import com.axiom.agentmap.importing.ImportService;
import com.axiom.agentmap.llm.LlmClient;
import com.axiom.agentmap.llm.LlmException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Map;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LLM 경로 배선: 가짜 LLM이 "원문 표기"만 돌려주면 백엔드가 어휘로 ID·상태를 정하는지,
 * 결과에 없는 Agent를 말하는 답변은 버리는지, LLM이 실패하면 규칙으로 돌아가는지.
 */
@SpringBootTest(properties = "axiom.llm.provider=none")
class FakeLlmPipelineTest {
    static volatile BiFunction<String, String, String> jsonHandler = (s, u) -> { throw new LlmException("not set"); };
    static volatile String textAnswer = "";

    @TestConfiguration
    static class FakeLlmConfig {
        @Bean
        @Primary
        LlmClient fakeLlm() {
            return new LlmClient() {
                public boolean enabled() { return true; }
                public String provider() { return "fake"; }
                public String model() { return "fake-model"; }
                public String json(String system, String user, String schemaName, Map<String, Object> schema) {
                    return jsonHandler.apply(schemaName, user);
                }
                public String text(String system, String user) { return textAnswer; }
            };
        }
    }

    @Autowired AskService ask;
    @Autowired ExtractionService extraction;
    @Autowired ImportService imports;

    @Test
    void llmInterpretationResolvesLabelsThroughVocabulary() {
        jsonHandler = (name, user) -> "{\"intent\":\"impact\",\"anchorRaw\":\"고객 분석 Agent\",\"anchorType\":\"Agent\",\"targetRaw\":null,"
                + "\"depth\":null,\"groupByDepartment\":false,\"highSensitivityOnly\":false,\"platformCountGte\":null,\"metric\":null}";
        textAnswer = "고객분석 Agent를 바꾸면 상품추천 Agent와 상담지원 Agent 등 4개를 점검해야 합니다.";
        AskResponse r = ask.ask("고객 분석 Agent 바꾸면?", Scope.ALL);
        assertThat(r.engine()).isEqualTo("llm");
        assertThat(r.interpretation().anchor().id()).isEqualTo("A001");
        assertThat(r.answer()).isEqualTo(textAnswer);

        // 결과에 없는 Agent 이름을 말하면 규칙 답변으로 대체
        textAnswer = "단가 조회 Agent도 점검하세요.";
        AskResponse guarded = ask.ask("고객 분석 Agent 바꾸면?", Scope.ALL);
        assertThat(guarded.answer()).doesNotContain("단가 조회 Agent").contains("상품추천 Agent");

        jsonHandler = (name, user) -> "{\"intent\":\"impact\",\"anchorRaw\":\"분석 Agent\",\"anchorType\":\"Agent\",\"targetRaw\":null,"
                + "\"depth\":null,\"groupByDepartment\":false,\"highSensitivityOnly\":false,\"platformCountGte\":null,\"metric\":null}";
        assertThat(ask.ask("분석 Agent 바꾸면?", Scope.ALL).interpretation().intent()).isEqualTo("ambiguous_anchor");

        jsonHandler = (name, user) -> { throw new LlmException("boom"); };
        AskResponse fallback = ask.ask("순환 의존 관계가 있나?", Scope.ALL);
        assertThat(fallback.engine()).isEqualTo("rule");
        assertThat(fallback.interpretation().intent()).isEqualTo("cycle");
    }

    @Test
    void llmExtractionUsesSameNormalization() {
        jsonHandler = (name, user) -> "{\"subject\":\"채용 서류 1차 검토 Agent\",\"providerSubject\":false,\"targets\":["
                + "{\"raw\":\"워크데이\",\"kind\":\"platform\"},{\"raw\":\"이력서\",\"kind\":\"data\"},{\"raw\":\"분석 Agent\",\"kind\":\"agent\"}],"
                + "\"negated\":false,\"future\":false}";
        ExtractionResult r = extraction.extract("아무 문장", null);
        assertThat(r.engine()).isEqualTo("llm");
        assertThat(r.subject().resolved().id()).isEqualTo("A034");
        assertThat(r.items()).extracting(i -> i.outcome()).containsExactly(Outcome.DUPLICATE, Outcome.DUPLICATE, Outcome.AMBIGUOUS);
        assertThat(r.items().get(0).targetId()).isEqualTo("P04");
        assertThat(r.items().get(1).targetId()).isEqualTo("D019");
    }

    @Test
    void docxImportFallsBackToRuleWhenLlmFails() {
        jsonHandler = (name, user) -> { throw new LlmException("boom"); };
        ImportPreview p = imports.preview(imports.sampleFiles());
        assertThat(p.files().get(4).parser()).isEqualTo("rule");
    }
}
