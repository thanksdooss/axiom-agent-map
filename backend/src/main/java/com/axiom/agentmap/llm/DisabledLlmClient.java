package com.axiom.agentmap.llm;

import java.util.Map;

/** LLM 없음 → 모든 기능이 규칙 기반으로 동작합니다. */
public class DisabledLlmClient implements LlmClient {
    @Override public boolean enabled() { return false; }
    @Override public String provider() { return null; }
    @Override public String model() { return null; }

    @Override
    public String json(String system, String user, String schemaName, Map<String, Object> schema) {
        throw new LlmException("LLM이 설정되지 않았습니다.");
    }

    @Override
    public String text(String system, String user) {
        throw new LlmException("LLM이 설정되지 않았습니다.");
    }
}
