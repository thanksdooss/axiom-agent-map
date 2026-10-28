package com.axiom.agentmap.llm;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.MessageCreateParams;
import com.axiom.agentmap.config.AxiomProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 외부 호출 없이 LLM 요청 모양 확인 (Anthropic 요청 본문, OpenAI 호환 HTTP는 로컬 가짜 서버) */
class LlmClientsTest {
    private static final Map<String, Object> SCHEMA = Map.of("type", "object",
            "properties", Map.of("intent", Map.of("type", "string")), "required", List.of("intent"),
            "additionalProperties", false);

    @Test
    void anthropicRequestUsesStructuredOutputEffortAndFallbacks() throws Exception {
        AxiomProperties.Anthropic cfg = new AxiomProperties.Anthropic();
        MessageCreateParams p = AnthropicLlmClient.jsonParams(cfg, "sys", "질문", SCHEMA);
        JsonNode body = ObjectMappers.jsonMapper().readTree(ObjectMappers.jsonMapper().writeValueAsString(p._body()));
        assertThat(body.path("model").asText()).isEqualTo("claude-opus-5-5");
        assertThat(body.path("output_config").path("effort").asText()).isEqualTo("low");
        assertThat(body.path("output_config").path("format").path("type").asText()).isEqualTo("json_schema");
        assertThat(body.path("output_config").path("format").path("schema").path("additionalProperties").asBoolean()).isFalse();
        assertThat(body.has("thinking")).isFalse();
        assertThat(p._additionalBodyProperties().get("fallbacks").toString()).contains("default");
        assertThat(p._headers().values("anthropic-beta")).contains("server-side-fallback-2026-07-01");
    }

    @Test
    void openAiCompatibleClientSendsJsonSchemaAndParsesContent() throws Exception {
        AtomicReference<String> seenBody = new AtomicReference<>();
        AtomicReference<String> seenAuth = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            seenBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            seenAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] out = "{\"choices\":[{\"message\":{\"content\":\"{\\\"intent\\\":\\\"cycle\\\"}\"}}]}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        try {
            AxiomProperties.OpenAi cfg = new AxiomProperties.OpenAi();
            cfg.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/");
            cfg.setModel("test-model");
            JsonMapper mapper = JsonMapper.builder().build();
            OpenAiCompatibleLlmClient c = new OpenAiCompatibleLlmClient(cfg, "test-key", 5, mapper);
            assertThat(c.json("sys", "q", "interp", SCHEMA)).isEqualTo("{\"intent\":\"cycle\"}");
            tools.jackson.databind.JsonNode req = mapper.readTree(seenBody.get());
            assertThat(req.path("model").asString()).isEqualTo("test-model");
            assertThat(req.path("response_format").path("type").asString()).isEqualTo("json_schema");
            assertThat(req.path("response_format").path("json_schema").path("strict").asBoolean()).isTrue();
            assertThat(seenAuth.get()).isEqualTo("Bearer test-key");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void disabledClientRefuses() {
        DisabledLlmClient d = new DisabledLlmClient();
        assertThat(d.enabled()).isFalse();
        assertThatThrownBy(() -> d.text("a", "b")).isInstanceOf(LlmException.class);
    }
}
