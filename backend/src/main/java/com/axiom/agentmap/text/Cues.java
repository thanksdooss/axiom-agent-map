package com.axiom.agentmap.text;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 서술형 문장에서 관계를 읽을 때 쓰는 단서 표현 (규칙 기반 추출기 공용) */
public final class Cues {
    /** 플랫폼 연동 단서 */
    public static final Pattern PLATFORM = Pattern.compile("연동|연결|(으로|로) 결과를|공유|에서 (읽|가져|조회)");
    /** 데이터 사용 단서 */
    public static final Pattern DATA = Pattern.compile("참고|씁니다|쓰고|쓴다|사용|확인|검색|바탕으로|읽|활용|조회|가져|이용");
    /** 결과사용(다른 Agent 결과) 단서 */
    public static final Pattern RESULT = Pattern.compile("결과|호출|가져와|받아|참고|사용|쓴다|씁니다|읽");
    /** 부정 표현 — 관계를 만들지 않음 */
    public static final Pattern NEGATION = Pattern.compile(
            "(사용|참고|이용|연동|호출|활용|조회|연결)(하지|되지) 않(습니다|는다|아요|음|고|으며)?|쓰지 않(습니다|는다|아요|음|고)?|읽지 않(습니다|는다)?");
    /** 미래 계획 — 현재 관계가 아님 */
    public static final Pattern FUTURE = Pattern.compile("예정|계획(이다|입니다|임)|할 것|도입할|연동할");
    /** 'X Agent' 앞말로는 Agent 이름이 아닌 지시어·수식어 */
    public static final Set<String> NOT_AGENT_WORDS = Set.of("이", "그", "저", "해당", "이런", "그런", "각", "여러", "모든", "다른",
            "어떤", "어느", "핵심", "새", "같은", "본", "위", "아래", "전체", "쓰는", "사용하는", "접근하는", "영향받는");

    private Cues() {}

    public static boolean has(Pattern p, String s) {
        return p.matcher(s).find();
    }

    public static String negationExpression(String s) {
        Matcher m = NEGATION.matcher(s);
        return m.find() ? m.group() : null;
    }
}
