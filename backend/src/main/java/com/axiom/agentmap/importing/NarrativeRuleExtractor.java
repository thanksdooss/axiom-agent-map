package com.axiom.agentmap.importing;

import com.axiom.agentmap.api.GraphDtos.Evidence;
import com.axiom.agentmap.domain.RelType;
import com.axiom.agentmap.importing.SubmittedAgent.Raw;
import com.axiom.agentmap.text.Cues;
import com.axiom.agentmap.text.Texts;
import com.axiom.agentmap.vocabulary.Lexicon;
import com.axiom.agentmap.vocabulary.Lexicon.Kind;
import com.axiom.agentmap.vocabulary.TextScanner;
import com.axiom.agentmap.vocabulary.TextScanner.Mention;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Word 서술형 규칙 기반 추출기. 문장마다 표준 어휘 표기를 찾고(가장 긴 표기 우선, 모든 종류 함께),
 * 단서 표현으로 관계 종류를 정합니다.
 * <ul>
 *   <li>연동·연결·공유 → 사용 플랫폼, 참고·씁니다·확인·검색·바탕으로 → 데이터사용</li>
 *   <li>호출·결과를 받아·가져와 → 결과사용, "X Agent가 이 Agent의 … 결과를 받아 씁니다" → 방향을 뒤집음(X → 이 Agent)</li>
 *   <li>"사용하지 않습니다" 같은 부정문, "예정" 같은 미래 계획 → 관계 없음(특수 사례로만 기록)</li>
 * </ul>
 * 여기서는 원문 표기만 모으고, ID·상태는 ImportNormalizer가 어휘로 정합니다.
 */
public class NarrativeRuleExtractor {
    private static final Pattern OWNER_TITLE = Pattern.compile("([가-힣]{2,4})\\s(변호사|책임|매니저|선임|님)(?=[이가은는께의]|\\s|$)");
    private static final Pattern OWNER_ROLE = Pattern.compile("(\\S+팀)\\s담당자");
    private static final Pattern OWNER_NONE = Pattern.compile("담당자는\\s*(아직|없|미정)");
    private static final Pattern DATE = Pattern.compile(
            "\\d{4}년 \\d{1,2}월(?: (?:초|중순|말))?|\\d{4}[-./]\\d{1,2}(?:[-./]\\d{1,2})?|\\d{2}/\\d{1,2}/\\d{1,2}"
                    + "|(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) \\d{4}|작년 \\d{1,2}월|작년 말|\\d{4}/Q[1-4]|\\d{4} [상하]반기");
    private static final List<Pattern> USAGE = List.of(
            Pattern.compile("(?:월|한 달에)\\s*(\\d[\\d,]*\\s*건(?:\\s*(?:정도|내외))?)"),
            Pattern.compile("(하루 한 번)"),
            Pattern.compile("(매일)"));
    private static final Pattern STATUS = Pattern.compile("운영\\s?중|파일럿|Pilot|PoC|중단|중지");
    private static final Set<String> FOLLOW_OK = Set.of("가", "의", "를", "와", "과", "에게", "로부터", "에서", "이");

    public SubmittedAgent extract(String file, int index, ParsedFile.NarrativeSection sec, Lexicon lex) {
        SubmittedAgent a = new SubmittedAgent();
        a.file = file;
        a.format = "docx";
        a.index = index;
        a.name = new Raw(sec.agentName(), Evidence.of(file, "문단 " + sec.headingParagraph(), sec.agentName()));
        if (sec.submitterText() != null) {
            a.submitter = new Raw(sec.submitterText(), Evidence.of(file, sec.submitterLocator(), sec.submitterText()));
        }
        List<String> ss = sec.sentences();
        String selfId = lex.agentIdByName(sec.agentName());
        List<Lexicon.Term> terms = lex.terms(EnumSet.of(Kind.AGENT, Kind.DATA, Kind.PLATFORM, Kind.DEPARTMENT), true, true, false);
        List<List<Mention>> mentions = new ArrayList<>();
        for (String s : ss) mentions.add(TextScanner.scan(s, terms, EnumSet.of(Kind.DATA)));

        // 소속
        for (int i = 0; i < ss.size() && a.dept == null; i++) {
            for (Mention m : mentions.get(i)) {
                if (m.kind() == Kind.DEPARTMENT) {
                    a.dept = new Raw(m.term().label(), ev(file, sec, i));
                    break;
                }
            }
        }
        if (a.dept == null) a.dept = new Raw("", ev(file, sec, 0));

        // 담당자
        a.owner = owner(file, sec);

        // 업무 유형 + 설명
        int typeSentence = -1;
        String typeLabel = null;
        List<Lexicon.Term> wts = new ArrayList<>(lex.terms(EnumSet.of(Kind.WORK_TYPE), false, false, false));
        wts.sort(Comparator.comparingInt((Lexicon.Term t) -> -t.label().length()));
        for (int i = 0; i < ss.size() && typeLabel == null; i++) {
            String s = ss.get(i);
            for (Lexicon.Term t : wts) {
                if (s.contains(t.label() + " Agent입니다") || s.contains("업무 성격은 " + t.label())) {
                    typeLabel = t.label();
                    typeSentence = i;
                    break;
                }
            }
        }
        a.type = typeLabel == null ? new Raw("", ev(file, sec, 0)) : new Raw(typeLabel, ev(file, sec, typeSentence));
        String desc = typeSentence >= 0 ? describe(ss.get(typeSentence), typeLabel) : (ss.size() > 1 ? ss.get(1) : "");
        a.desc = new Raw(desc, ev(file, sec, Math.max(typeSentence, 0)));

        // 관계
        for (int i = 0; i < ss.size(); i++) {
            String s = ss.get(i);
            Evidence e = ev(file, sec, i);
            List<Mention> ms = mentions.get(i);
            String neg = Cues.negationExpression(s);
            boolean future = Cues.has(Cues.FUTURE, s);
            if (neg != null) {
                for (Mention m : ms) {
                    RelType t = m.kind() == Kind.DATA ? RelType.ACCESSES_DATA : m.kind() == Kind.PLATFORM ? RelType.USES_PLATFORM
                            : m.kind() == Kind.AGENT ? RelType.USES_RESULT_OF : null;
                    if (t != null && !(m.kind() == Kind.AGENT && selfId != null && selfId.equals(m.id()))) {
                        a.negated.add(new SubmittedAgent.Negated(t, m.term().label(), neg, e));
                    }
                }
                continue;
            }
            if (future) {
                a.futures.add(new SubmittedAgent.Future(s, e));
                continue;
            }
            if (Cues.has(Cues.PLATFORM, s)) {
                for (Mention m : ms) if (m.kind() == Kind.PLATFORM) a.platforms.add(new Raw(m.term().label(), e));
            }
            if (Cues.has(Cues.DATA, s)) {
                for (Mention m : ms) if (m.kind() == Kind.DATA) a.data.add(new Raw(m.term().label(), e));
            }
            if (Cues.has(Cues.RESULT, s)) {
                boolean providerSubject = s.contains("이 Agent의") || s.contains("이 Agent를");
                for (Mention m : ms) {
                    if (m.kind() != Kind.AGENT) continue;
                    if (m.id() != null && m.id().equals(selfId)) continue;
                    String after = s.substring(m.end());
                    if (providerSubject && m.id() != null && (after.startsWith("가 ") || after.startsWith("이 "))) {
                        a.providedTo.add(new Raw(m.term().label(), e));
                    } else {
                        a.calls.add(new Raw(m.term().label(), e));
                    }
                }
                for (TextScanner.LooseAgent la : TextScanner.looseAgents(s, ms)) {
                    if (Cues.NOT_AGENT_WORDS.contains(la.word()) || la.following().startsWith("입니다")) continue;
                    if (FOLLOW_OK.stream().noneMatch(la.following()::startsWith)) continue;
                    if (lex.resolve(Kind.WORK_TYPE, la.word()).how() == Lexicon.How.EXACT) continue;
                    a.calls.add(new Raw(la.raw(), e));
                }
            }
        }

        // 운영 시작일·사용량·상태
        a.date = firstMatch(file, sec, DATE, 0);
        a.usage = new Raw("", ev(file, sec, ss.size() - 1));
        for (Pattern p : USAGE) {
            Raw r = firstMatch(file, sec, p, 1);
            if (!r.text().isEmpty()) {
                a.usage = r;
                break;
            }
        }
        a.status = firstMatch(file, sec, STATUS, 0);
        return a;
    }

    private static Evidence ev(String file, ParsedFile.NarrativeSection sec, int i) {
        if (sec.sentences().isEmpty()) return Evidence.of(file, "문단 " + sec.paragraph(), "");
        return Evidence.of(file, "문단 " + sec.paragraph() + " 문장 " + (i + 1), sec.sentences().get(i));
    }

    private static Raw firstMatch(String file, ParsedFile.NarrativeSection sec, Pattern p, int group) {
        List<String> ss = sec.sentences();
        for (int i = 0; i < ss.size(); i++) {
            Matcher m = p.matcher(ss.get(i));
            if (m.find()) return new Raw(m.group(group), ev(file, sec, i));
        }
        return new Raw("", Evidence.of(file, "문단 " + sec.paragraph(), ""));
    }

    private static Raw owner(String file, ParsedFile.NarrativeSection sec) {
        List<String> ss = sec.sentences();
        for (int i = 0; i < ss.size(); i++) {
            String s = ss.get(i);
            Matcher m = OWNER_TITLE.matcher(s);
            while (m.find()) {
                if (m.group(1).endsWith("팀")) continue;
                String raw = m.group(2).equals("님") ? m.group(1) : m.group(1) + " " + m.group(2);
                return new Raw(raw, ev(file, sec, i));
            }
            Matcher r = OWNER_ROLE.matcher(s);
            if (r.find()) return new Raw(r.group(1) + " 담당자", ev(file, sec, i));
            if (OWNER_NONE.matcher(s).find()) return new Raw("", ev(file, sec, i));
        }
        return new Raw("", ev(file, sec, 0));
    }

    /**
     * 업무 유형 문장 → 짧은 기능 설명. "…를 만드는 문서 작성 Agent입니다." → "… 작성",
     * "…을 정리하는 …" → "… 정리", "…해 주며, 업무 성격은 …입니다." → "…".
     */
    static String describe(String sentence, String typeLabel) {
        String s = sentence.strip();
        s = s.replaceAll(",?\\s*업무 성격은 .*$", "");
        if (typeLabel != null) s = s.replace(" " + typeLabel + " Agent입니다.", "").replace(" " + typeLabel + " Agent입니다", "");
        s = s.replaceAll("\\.$", "").strip();
        s = s.replaceAll("^(\\S+)[을를] 올리면 ", "$1의 ");
        s = s.replaceAll("해 주며$", "");
        s = s.replaceAll("\\s*[을를] (만드는|쓰는|작성하는)$", " 작성");
        s = s.replaceAll("\\s*[을를] 보내는$", " 발송");
        s = s.replaceAll("\\s*[을를] (\\S+?)하는$", " $1");
        s = s.replaceAll("(\\S+?)하는$", "$1");
        return Texts.nz(s).strip();
    }
}
