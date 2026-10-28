package com.axiom.agentmap.llm;

import com.axiom.agentmap.config.AxiomProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * axiom.llm.provider = auto | anthropic | openai | none.
 * auto는 ANTHROPIC_API_KEY가 있으면 anthropic, 없으면 none(규칙 기반)입니다. 키 값은 로그에 남기지 않습니다.
 */
@Configuration
public class LlmConfiguration {
    private static final Logger log = LoggerFactory.getLogger(LlmConfiguration.class);

    @Bean
    public LlmClient llmClient(AxiomProperties props, JsonMapper mapper) {
        AxiomProperties.Llm llm = props.getLlm();
        String provider = llm.getProvider() == null ? "auto" : llm.getProvider().strip().toLowerCase();
        String anthropicKey = System.getenv("ANTHROPIC_API_KEY");
        String openaiKey = System.getenv("OPENAI_API_KEY");
        if (provider.equals("auto")) provider = notBlank(anthropicKey) ? "anthropic" : "none";
        switch (provider) {
            case "anthropic" -> {
                if (!notBlank(anthropicKey)) {
                    log.warn("axiom.llm.provider=anthropic이지만 ANTHROPIC_API_KEY가 없습니다 — 규칙 기반으로 동작합니다.");
                    return new DisabledLlmClient();
                }
                log.info("LLM: Anthropic ({})", llm.getAnthropic().getModel());
                return new AnthropicLlmClient(llm.getAnthropic(), llm.getTimeoutSeconds());
            }
            case "openai" -> {
                if (!notBlank(openaiKey)) {
                    log.warn("axiom.llm.provider=openai이지만 OPENAI_API_KEY가 없습니다 — 규칙 기반으로 동작합니다.");
                    return new DisabledLlmClient();
                }
                log.info("LLM: OpenAI 호환 ({} @ {})", llm.getOpenai().getModel(), llm.getOpenai().getBaseUrl());
                return new OpenAiCompatibleLlmClient(llm.getOpenai(), openaiKey, llm.getTimeoutSeconds(), mapper);
            }
            default -> {
                log.info("LLM: 사용 안 함 — 규칙 기반 엔진으로 동작합니다.");
                return new DisabledLlmClient();
            }
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
