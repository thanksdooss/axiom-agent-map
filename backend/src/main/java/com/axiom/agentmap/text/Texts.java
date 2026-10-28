package com.axiom.agentmap.text;

import java.util.regex.Pattern;

/** 표기 비교용 작은 문자열 도구. */
public final class Texts {
    private static final Pattern WS = Pattern.compile("\\s+");

    private Texts() {}

    /** 공백을 모두 지운 비교용 키 ("상품 추천 Agent" → "상품추천Agent") */
    public static String compact(String s) {
        return s == null ? "" : WS.matcher(s).replaceAll("");
    }

    public static boolean isHangul(char c) {
        return (c >= '\uAC00' && c <= '\uD7A3') || (c >= '\u3131' && c <= '\u318E');
    }

    public static boolean isLatinOrDigit(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    /** 단어를 이루는 글자(한글·영문·숫자) */
    public static boolean isWordChar(char c) {
        return isHangul(c) || isLatinOrDigit(c);
    }

    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 받침 유무로 조사 고르기: 받침 있으면 withFinal, 없으면 without (영문·숫자는 없음으로 봄) */
    public static String josa(String word, String withFinal, String without) {
        if (word == null || word.isEmpty()) return without;
        char c = word.charAt(word.length() - 1);
        if (c >= '\uAC00' && c <= '\uD7A3') {
            return ((c - 0xAC00) % 28 != 0) ? withFinal : without;
        }
        return without;
    }
}
