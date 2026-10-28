package com.axiom.agentmap.llm;

import com.axiom.agentmap.config.AxiomProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 호환 Chat Completions(HTTP) 구현. 기본 URL·모델은 설정, 키는 환경변수 OPENAI_API_KEY.
 * 과정에서 받은 비(非)Anthropic 키나 사내 게이트웨이용입니다.
 */
public class OpenAiCompatibleLlmClient implements LlmClient {
    private final HttpClient http;
    private final JsonMapper mapper;
    private final AxiomProperties.OpenAi cfg;
    private final String apiKey;
    private final Duration timeout;

    public OpenAiCompatibleLlmClient(AxiomProperties.OpenAi cfg, String apiKey, int timeoutSeconds, JsonMapper mapper) {
        this.cfg = cfg;
        this.apiKey = apiKey;
        this.mapper = mapper;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override public boolean enabled() { return true; }
    @Override public String provider() { return "openai"; }
    @Override public String model() { return cfg.getModel(); }

    @Override
    public String json(String system, String user, String schemaName, Map<String, Object> schema) {
        Map<String, Object> body = body(system, user);
        if (cfg.isJsonSchema()) {
            body.put("response_format", Map.of("type", "json_schema",
                    "json_schema", Map.of("name", schemaName, "strict", true, "schema", schema)));
        } else {
            body.put("response_format", Map.of("type", "json_object"));
            body.put("messages", List.of(
                    Map.of("role", "system", "content", system + "\n\n다음 JSON Schema를 따르는 JSON만 출력하세요:\n"
                            + mapper.writeValueAsString(schema)),
                    Map.of("role", "user", "content", user)));
        }
        return post(body);
    }

    @Override
    public String text(String system, String user) {
        return post(body(system, user));
    }

    private Map<String, Object> body(String system, String user) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.getModel());
        body.put("messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)));
        return body;
    }

    private String post(Map<String, Object> body) {
        String url = cfg.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new LlmException("OpenAI 호환 서버 연결 실패: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("OpenAI 호환 호출이 중단되었습니다.", e);
        }
        if (res.statusCode() / 100 != 2) throw new LlmException("OpenAI 호환 서버 오류 HTTP " + res.statusCode());
        JsonNode root = mapper.readTree(res.body());
        JsonNode msg = root.path("choices").path(0).path("message");
        if (msg.path("refusal").isString() && !msg.path("refusal").asString().isBlank()) {
            throw new LlmException("모델이 요청을 거절했습니다.");
        }
        String content = msg.path("content").asString("");
        if (content.isBlank()) throw new LlmException("빈 응답");
        return content;
    }
}
