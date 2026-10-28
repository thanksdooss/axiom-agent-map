package com.axiom.agentmap.llm;

import java.util.Map;

/**
 * LLM 호출 경계. 구현: Anthropic(공식 Java SDK) · OpenAI 호환(HTTP) · 없음(규칙 기반).
 * LLM은 원문 표기·해석 JSON만 만들고, ID·관계 상태는 항상 백엔드 규칙(어휘 표준화)이 정합니다.
 */
public interface LlmClient {
    boolean enabled();

    /** "anthropic" | "openai" | null */
    String provider();

    String model();

    /**
     * JSON Schema로 형식을 제한한 응답(JSON 문자열).
     * @throws LlmException 호출 실패·거절·형식 오류
     */
    String json(String system, String user, String schemaName, Map<String, Object> schema);

    /** 자유 형식 텍스트 응답 */
    String text(String system, String user);
}
