package com.axiom.agentmap.importing;

import java.util.List;

/**
 * 파서 결과. 정형 파일은 agents가 채워지고, Word 서술형은 sections(단락)가 채워진 뒤
 * 추출기(LLM 또는 규칙)가 agents를 만듭니다.
 */
public record ParsedFile(String name, String format, List<SubmittedAgent> agents, List<NarrativeSection> sections) {
    /** Word 문서의 Agent 한 개: 제목 문단 + 서술 문단 */
    public record NarrativeSection(String agentName, int headingParagraph, int paragraph, List<String> sentences,
                                   String headingText, String submitterText, String submitterLocator) {}
}
