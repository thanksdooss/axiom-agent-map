package com.axiom.agentmap.vocabulary;

import com.axiom.agentmap.text.Texts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 문장에서 표준 어휘 표기를 찾습니다. 공백을 무시하고(“상품 정보 DB” = “상품정보 DB”), 왼쪽부터 가장 긴 표기를 고르며
 * 겹치지 않게 잡습니다. 왼쪽 경계는 단어 시작이어야 하고(“비밀유지계약서”의 “계약서”는 제외),
 * 영문으로 끝나는 표기는 오른쪽도 영문·숫자로 이어지면 안 됩니다.
 */
public final class TextScanner {
    public record Mention(Lexicon.Term term, int start, int end, String surface) {
        public Lexicon.Kind kind() { return term.kind(); }
        public String id() { return term.id(); }
    }

    /** 'X Agent' 형태지만 등록된 이름과 정확히 맞지 않는 표기 (예: "분석 Agent") */
    public record LooseAgent(String raw, String word, int start, int end, String following) {}

    private static final String PARTICLE_START = "와과를을는은이가에로도만랑";
    private static final Pattern LOOSE_AGENT = Pattern.compile("([가-힣A-Za-z0-9&·]+)\\s*Agent(?![A-Za-z])");

    private TextScanner() {}

    /**
     * @param particleKinds 이 종류는 표기 바로 뒤가 조사(와·를·는…)·공백·문장부호여야 함 ('의'로 꾸미는 말은 제외)
     */
    public static List<Mention> scan(String text, List<Lexicon.Term> terms, Set<Lexicon.Kind> particleKinds) {
        List<Mention> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        StringBuilder compact = new StringBuilder();
        List<Integer> orig = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) {
                compact.append(c);
                orig.add(i);
            }
        }
        List<Lexicon.Term> sorted = new ArrayList<>(terms);
        sorted.removeIf(t -> t.compact().isEmpty());
        sorted.sort(Comparator.comparingInt((Lexicon.Term t) -> -t.compact().length())
                .thenComparingInt(t -> t.kind().ordinal() == Lexicon.Kind.AGENT.ordinal() ? 0 : 1)
                .thenComparingInt(t -> t.core() ? 1 : 0));
        String ct = compact.toString();
        int i = 0;
        while (i < ct.length()) {
            Mention found = null;
            for (Lexicon.Term t : sorted) {
                int len = t.compact().length();
                if (i + len > ct.length() || !ct.startsWith(t.compact(), i)) continue;
                int s = orig.get(i);
                int e = orig.get(i + len - 1) + 1;
                if (s > 0 && Texts.isWordChar(text.charAt(s - 1))) continue;
                char last = t.compact().charAt(len - 1);
                if (Texts.isLatinOrDigit(last) && e < text.length() && Texts.isLatinOrDigit(text.charAt(e))) continue;
                if (particleKinds != null && particleKinds.contains(t.kind()) && !particleOk(text, e)) continue;
                found = new Mention(t, s, e, text.substring(s, e));
                i += len;
                break;
            }
            if (found != null) out.add(found);
            else i++;
        }
        return out;
    }

    public static List<Mention> scan(String text, List<Lexicon.Term> terms) {
        return scan(text, terms, EnumSet.noneOf(Lexicon.Kind.class));
    }

    private static boolean particleOk(String text, int e) {
        if (e >= text.length()) return true;
        char c = text.charAt(e);
        if (Character.isWhitespace(c)) return true;
        if (",.;:!?)]}'\"·/".indexOf(c) >= 0) return true;
        return PARTICLE_START.indexOf(c) >= 0;
    }

    /** 이미 잡힌 표기와 겹치지 않는 'X Agent' 표기 */
    public static List<LooseAgent> looseAgents(String text, List<Mention> covered) {
        List<LooseAgent> out = new ArrayList<>();
        Matcher m = LOOSE_AGENT.matcher(text);
        while (m.find()) {
            int s = m.start(), e = m.end();
            boolean overlap = covered.stream().anyMatch(c -> c.start() < e && s < c.end());
            if (overlap) continue;
            int f = e;
            StringBuilder following = new StringBuilder();
            while (f < text.length() && Texts.isHangul(text.charAt(f))) following.append(text.charAt(f++));
            out.add(new LooseAgent(text.substring(s, e), m.group(1), s, e, following.toString()));
        }
        return out;
    }
}
