package com.axiom.agentmap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** application.yml의 axiom.* 설정. */
@ConfigurationProperties(prefix = "axiom")
public class AxiomProperties {
    /** 데이터 폴더(raw·vocabulary·answer_key). 상대 경로면 실행 위치 기준. */
    private String dataDir = "../data";
    /** 시작할 때 적재할 모드: full | sample */
    private String startupMode = "full";
    private List<String> corsOrigins = new ArrayList<>(List.of("http://localhost:5173"));
    /** 검토 일자·오늘 날짜 계산에 쓰는 시간대 */
    private String timezone = "Asia/Seoul";
    private final Llm llm = new Llm();

    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }
    public String getStartupMode() { return startupMode; }
    public void setStartupMode(String startupMode) { this.startupMode = startupMode; }
    public List<String> getCorsOrigins() { return corsOrigins; }
    public void setCorsOrigins(List<String> corsOrigins) { this.corsOrigins = corsOrigins; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public Llm getLlm() { return llm; }

    public static class Llm {
        /** anthropic | openai | none | auto(ANTHROPIC_API_KEY가 있으면 anthropic, 없으면 none) */
        private String provider = "auto";
        /** LLM 호출 제한 시간(초) */
        private int timeoutSeconds = 60;
        private final Anthropic anthropic = new Anthropic();
        private final OpenAi openai = new OpenAi();

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public Anthropic getAnthropic() { return anthropic; }
        public OpenAi getOpenai() { return openai; }
    }

    public static class Anthropic {
        private String model = "claude-opus-5-5";
        /** low | medium | high | xhigh | max */
        private String effort = "low";
        /** 안전 분류기가 거절하면 서버 측에서 다른 모델로 재시도(server-side fallbacks) */
        private boolean fallbacks = true;
        private long maxTokens = 16000;

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getEffort() { return effort; }
        public void setEffort(String effort) { this.effort = effort; }
        public boolean isFallbacks() { return fallbacks; }
        public void setFallbacks(boolean fallbacks) { this.fallbacks = fallbacks; }
        public long getMaxTokens() { return maxTokens; }
        public void setMaxTokens(long maxTokens) { this.maxTokens = maxTokens; }
    }

    public static class OpenAi {
        private String baseUrl = "https://api.openai.com/v1";
        private String model = "gpt-4o-mini";
        /** json_schema 응답 형식을 지원하지 않는 호환 서버면 false (json_object로 요청) */
        private boolean jsonSchema = true;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public boolean isJsonSchema() { return jsonSchema; }
        public void setJsonSchema(boolean jsonSchema) { this.jsonSchema = jsonSchema; }
    }
}
