package com.axiom.agentmap.importing;

import com.axiom.agentmap.api.GraphDtos.Evidence;
import com.axiom.agentmap.domain.RelType;

import java.util.ArrayList;
import java.util.List;

/**
 * 부서 제출 파일 한 행(또는 서술형 한 단락)에서 읽은 원문 값. 아직 표준화 전입니다.
 * 각 값에는 근거(파일·위치·원문)가 붙습니다.
 */
public class SubmittedAgent {
    /** 원문 값 + 근거 */
    public record Raw(String value, Evidence evidence) {
        public static Raw empty(Evidence ev) { return new Raw(null, ev); }
        public String text() { return value == null ? "" : value; }
    }

    /** 부정문으로 언급된 대상 (관계를 만들지 않음) */
    public record Negated(RelType type, String label, String expression, Evidence evidence) {}

    /** 미래 계획으로 언급된 내용 (관계를 만들지 않음) */
    public record Future(String raw, Evidence evidence) {}

    public String file;
    public String format;
    public int index;
    public Raw name;
    public Raw dept;
    public Raw owner;
    public Raw type;
    public Raw desc;
    public Raw date;
    public Raw usage;
    public Raw status;
    public Raw note;
    public final List<Raw> platforms = new ArrayList<>();
    public final List<Raw> data = new ArrayList<>();
    public final List<Raw> calls = new ArrayList<>();
    /** 제공하는 쪽이 주어인 문장: 여기 적힌 Agent가 이 Agent의 결과를 씀 */
    public final List<Raw> providedTo = new ArrayList<>();
    public final List<Negated> negated = new ArrayList<>();
    public final List<Future> futures = new ArrayList<>();
    /** 파일 머리의 제출 부서 표기 (소속 누락 시 추정에 씀) */
    public Raw submitter;

    public String nameText() { return name == null ? "" : name.text(); }
}
