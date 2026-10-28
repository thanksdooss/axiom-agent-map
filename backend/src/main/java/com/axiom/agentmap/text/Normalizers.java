package com.axiom.agentmap.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 원문 값 표준화. data/tools/build_dataset.py의 parse_date · parse_usage · parse_owner와 같은 규칙입니다.
 * 결과는 (표준값, 검토 사유) 쌍이며 사유가 있으면 검토 필요 표시(review flag)를 붙입니다.
 */
public final class Normalizers {
    public record Parsed<T>(T value, String reason) {}

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("Jan", 1), Map.entry("Feb", 2), Map.entry("Mar", 3), Map.entry("Apr", 4),
            Map.entry("May", 5), Map.entry("Jun", 6), Map.entry("Jul", 7), Map.entry("Aug", 8),
            Map.entry("Sep", 9), Map.entry("Oct", 10), Map.entry("Nov", 11), Map.entry("Dec", 12));
    private static final Map<String, Parsed<String>> RELATIVE_DATES = Map.of(
            "작년 12월", new Parsed<>("2025-12", "상대 날짜 — 제출일(2026년 10월) 기준 2025년 12월로 추정"),
            "작년 말", new Parsed<>("2025-12", "상대 날짜 — 제출일 기준 2025년 말로 추정"),
            "4월 초", new Parsed<>("2026-04", "연도 없음 — 제출 연도(2026)로 추정, 일자 불명확"));

    private static final Pattern QUARTER = Pattern.compile("\\d{4}/Q[1-4]");
    private static final Pattern HALF = Pattern.compile("상반기|하반기");
    private static final Pattern KO_MONTH_PART = Pattern.compile("(\\d{4})년 (\\d{1,2})월 (초|중순|말)");
    private static final Pattern YMD = Pattern.compile("(\\d{4})[-./](\\d{1,2})[-./](\\d{1,2})");
    private static final Pattern SHORT_YMD = Pattern.compile("(\\d{2})/(\\d{1,2})/(\\d{1,2})");
    private static final Pattern YM = Pattern.compile("(\\d{4})[-./](\\d{1,2})");
    private static final Pattern KO_YM = Pattern.compile("(\\d{4})년 (\\d{1,2})월");
    private static final Pattern EN_MY = Pattern.compile("([A-Z][a-z]{2}) (\\d{4})");

    private static final Pattern RANGE = Pattern.compile("\\d+\\s*~\\s*\\d+");
    private static final Pattern ABOUT = Pattern.compile("^약\\s*");
    private static final Pattern COUNT_SUFFIX = Pattern.compile("건?\\s*(정도|내외)$");
    private static final Pattern NUMBER = Pattern.compile("([\\d.,]+)\\s*(천|만|k)?");

    private static final Pattern TITLES = Pattern.compile("\\s*(책임|매니저|선임|변호사|님)$");
    private static final Pattern OWNER_SPLIT = Pattern.compile("\\s*[/,]\\s*");

    public static final String UNPARSED = "형식을 해석할 수 없음 — 확인 필요";

    private Normalizers() {}

    private static String strip(String raw) {
        String s = raw == null ? "" : raw.strip();
        // Python str.strip("'")
        int b = 0, e = s.length();
        while (b < e && s.charAt(b) == '\'') b++;
        while (e > b && s.charAt(e - 1) == '\'') e--;
        return s.substring(b, e);
    }

    /** 원문 날짜 → (YYYY-MM 또는 YYYY-MM-DD, 검토 사유) */
    public static Parsed<String> parseDate(String raw) {
        String s = strip(raw);
        if (s.isEmpty()) return new Parsed<>(null, "운영 시작일 누락");
        if (RELATIVE_DATES.containsKey(s)) return RELATIVE_DATES.get(s);
        if (QUARTER.matcher(s).matches()) return new Parsed<>(null, "분기 표기 — 일자 확인 필요");
        if (HALF.matcher(s).find()) return new Parsed<>(null, "반기 표기 — 일자 확인 필요");
        Matcher m = KO_MONTH_PART.matcher(s);
        if (m.matches()) return new Parsed<>(m.group(1) + "-" + two(m.group(2)), "일자 불명확 — 월까지만 확정");
        m = YMD.matcher(s);
        if (m.matches()) return new Parsed<>(m.group(1) + "-" + two(m.group(2)) + "-" + two(m.group(3)), null);
        m = SHORT_YMD.matcher(s);
        if (m.matches()) return new Parsed<>("20" + m.group(1) + "-" + two(m.group(2)) + "-" + two(m.group(3)), null);
        m = YM.matcher(s);
        if (m.matches()) return new Parsed<>(m.group(1) + "-" + two(m.group(2)), null);
        m = KO_YM.matcher(s);
        if (m.matches()) return new Parsed<>(m.group(1) + "-" + two(m.group(2)), null);
        m = EN_MY.matcher(s);
        if (m.matches() && MONTHS.containsKey(m.group(1))) {
            return new Parsed<>(m.group(2) + "-" + two(String.valueOf(MONTHS.get(m.group(1)))), null);
        }
        return new Parsed<>(null, "날짜 " + UNPARSED);
    }

    /** 원문 월 사용량 → (정수, 검토 사유) */
    public static Parsed<Integer> parseUsage(String raw) {
        String s = strip(raw);
        if (s.isEmpty()) return new Parsed<>(null, "월 사용량 누락");
        if (RANGE.matcher(s).matches()) return new Parsed<>(null, "범위값 — 단일 값 확인 필요");
        if (s.equals("매일") || s.equals("하루 한 번")) return new Parsed<>(30, "빈도 표현 — 월 약 30회로 추정");
        if (s.equals("많음")) return new Parsed<>(null, "숫자가 아닌 값");
        String t = ABOUT.matcher(s).replaceFirst("");
        t = COUNT_SUFFIX.matcher(t).replaceFirst("").strip();
        Matcher m = NUMBER.matcher(t);
        if (!m.matches()) return new Parsed<>(null, "사용량 " + UNPARSED);
        double num;
        try {
            num = Double.parseDouble(m.group(1).replace(",", ""));
        } catch (NumberFormatException e) {
            return new Parsed<>(null, "사용량 " + UNPARSED);
        }
        String unit = m.group(2);
        if ("천".equals(unit) || "k".equals(unit)) num *= 1000;
        else if ("만".equals(unit)) num *= 10000;
        return new Parsed<>((int) Math.round(num), null);
    }

    /** 담당자 원문 → (이름 목록, 검토 사유). 직함을 떼고 '/'·','로 나눕니다. */
    public static Parsed<List<String>> parseOwner(String raw) {
        String s = raw == null ? "" : raw.strip();
        if (s.isEmpty()) return new Parsed<>(List.of(), "담당자 누락");
        if (s.endsWith("담당자") && !s.contains(" / ")) {
            // "법무팀 담당자"처럼 이름 없이 역할만 적힌 경우
            if (s.matches("\\S*팀 담당자|담당자")) return new Parsed<>(List.of(), "특정 담당자 이름 없음");
        }
        List<String> out = new ArrayList<>();
        for (String p : OWNER_SPLIT.split(s)) {
            String name = TITLES.matcher(p).replaceFirst("").strip();
            if (!name.isEmpty()) out.add(name);
        }
        return new Parsed<>(out, null);
    }

    private static String two(String n) {
        int v = Integer.parseInt(n);
        return v < 10 ? "0" + v : String.valueOf(v);
    }
}
