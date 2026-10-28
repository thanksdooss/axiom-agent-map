package com.axiom.agentmap.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.axiom.agentmap.config.AxiomProperties;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Anthropic 공식 Java SDK 구현. 키는 환경변수 ANTHROPIC_API_KEY(SDK fromEnv)에서만 읽고 로그에 남기지 않습니다.
 * 구조화 출력(output_config.format = JSON Schema)으로 해석·추출 JSON을 받습니다.
 * Claude Opus 5.5는 thinking을 끌 수 없으므로 effort(기본 low)로 깊이를 조절합니다.
 */
public class AnthropicLlmClient implements LlmClient {
    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final AnthropicClient client;
    private final AxiomProperties.Anthropic cfg;

    public AnthropicLlmClient(AxiomProperties.Anthropic cfg, int timeoutSeconds) {
        this.cfg = cfg;
        this.client = AnthropicOkHttpClient.builder()
                .fromEnv()
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .maxRetries(2)
                .build();
    }

    @Override public boolean enabled() { return true; }
    @Override public String provider() { return "anthropic"; }
    @Override public String model() { return cfg.getModel(); }

    @Override
    public String json(String system, String user, String schemaName, Map<String, Object> schema) {
        return call(jsonParams(cfg, system, user, schema));
    }

    @Override
    public String text(String system, String user) {
        return call(base(cfg, system, user).outputConfig(OutputConfig.builder()
                .effort(OutputConfig.Effort.of(cfg.getEffort())).build()).build());
    }

    /** 구조화 출력 요청 (테스트에서 요청 본문을 확인할 수 있게 분리) */
    static MessageCreateParams jsonParams(AxiomProperties.Anthropic cfg, String system, String user, Map<String, Object> schema) {
        JsonOutputFormat.Schema.Builder sb = JsonOutputFormat.Schema.builder();
        schema.forEach((k, v) -> sb.putAdditionalProperty(k, JsonValue.from(v)));
        OutputConfig output = OutputConfig.builder()
                .effort(OutputConfig.Effort.of(cfg.getEffort()))
                .format(JsonOutputFormat.builder().schema(sb.build()).build())
                .build();
        return base(cfg, system, user).outputConfig(output).build();
    }

    private static MessageCreateParams.Builder base(AxiomProperties.Anthropic cfg, String system, String user) {
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(cfg.getModel())
                .maxTokens(cfg.getMaxTokens())
                .system(system)
                .addUserMessage(user);
        if (cfg.isFallbacks()) {
            // 안전 분류기 거절 시 서버가 거절 범주에 맞는 모델로 다시 시도합니다.
            b.putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        return b;
    }

    private String call(MessageCreateParams params) {
        Message m;
        try {
            m = client.messages().create(params);
        } catch (AnthropicException e) {
            throw new LlmException("Anthropic 호출 실패: " + e.getClass().getSimpleName(), e);
        }
        StopReason stop = m.stopReason().orElse(null);
        if (StopReason.REFUSAL.equals(stop)) throw new LlmException("모델이 요청을 거절했습니다(refusal).");
        if (StopReason.MAX_TOKENS.equals(stop)) throw new LlmException("응답이 max_tokens에서 잘렸습니다.");
        String text = m.content().stream()
                .flatMap(cb -> cb.text().stream())
                .map(t -> t.text())
                .collect(Collectors.joining());
        if (text.isBlank()) throw new LlmException("빈 응답");
        return text;
    }

}
