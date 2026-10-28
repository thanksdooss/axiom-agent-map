package com.axiom.agentmap;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** data/answer_key 정답 파일 읽기 (테스트 실행 위치: backend/) */
public final class AnswerKey {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    public static final Path DATA = Path.of(System.getProperty("user.dir")).resolve("../data").normalize();

    private AnswerKey() {}

    public static JsonNode read(String name) {
        try {
            return MAPPER.readTree(Files.readString(DATA.resolve("answer_key").resolve(name)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static JsonNode extraction() { return read("extraction_answer_key.json"); }
    public static JsonNode golden() { return read("query_golden_set.json"); }
    public static JsonNode dashboard() { return read("dashboard_expected.json"); }

    public static JsonMapper mapper() { return MAPPER; }
}
