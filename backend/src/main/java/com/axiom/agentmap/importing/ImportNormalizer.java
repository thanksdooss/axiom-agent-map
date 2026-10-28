package com.axiom.agentmap.importing;

import com.axiom.agentmap.api.AgentDtos.ReviewFlag;
import com.axiom.agentmap.api.GraphDtos.Evidence;
import com.axiom.agentmap.api.ImportDtos.SpecialCase;
import com.axiom.agentmap.domain.RelStatus;
import com.axiom.agentmap.domain.RelType;
import com.axiom.agentmap.importing.SubmittedAgent.Raw;
import com.axiom.agentmap.text.Cues;
import com.axiom.agentmap.text.Normalizers;
import com.axiom.agentmap.text.Texts;
import com.axiom.agentmap.vocabulary.Lexicon;
import com.axiom.agentmap.vocabulary.Lexicon.How;
import com.axiom.agentmap.vocabulary.Lexicon.Kind;
import com.axiom.agentmap.vocabulary.Lexicon.Resolution;
import com.axiom.agentmap.vocabulary.TextScanner;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * 원문 값 → 표준값·관계. build_dataset.py의 build_model과 같은 규칙입니다.
 * 정확·별칭 일치 → CONFIRMED, 검토 필요 표기 → CANDIDATE(사유), 미등록 표기 → 미등록 대상, 자기 참조 → 거부.
 */
public class ImportNormalizer {
    public static final String SELF_REFERENCE = "자기 참조 — 출발과 도착이 같은 관계는 저장하지 않음";
    public static final String KIND_DATA = "DataAsset";
    public static final String KIND_AGENT = "Agent/외부 서비스";

    private final Lexicon lex;
    private final ImportBatch batch;

    public ImportNormalizer(Lexicon lex, ImportBatch batch) {
        this.lex = lex;
        this.batch = batch;
    }

    private ImportBatch.Rel addRel(RelType t, String src, String tgt, RelStatus status, Evidence ev, String reason,
                                   List<String> candidates) {
        String k = ImportBatch.key(t, src, tgt);
        ImportBatch.Rel r = batch.rels.get(k);
        if (r == null) {
            r = new ImportBatch.Rel();
            r.type = t;
            r.source = src;
            r.target = tgt;
            r.status = status;
            r.reviewReason = reason;
            r.candidatesShown = candidates;
            batch.rels.put(k, r);
        }
        if (ev != null) r.evidence.add(ev);
        if (status == RelStatus.CANDIDATE && r.status == RelStatus.CONFIRMED) {
            r.status = RelStatus.CANDIDATE;
            r.reviewReason = reason;
        }
        return r;
    }

    private static void flag(ImportBatch.Agent a, String field, String raw, String reason, Object candidate) {
        a.review.add(new ReviewFlag(field, raw == null ? "" : raw, candidate, reason));
    }

    private void pending(String raw, String kind, String agentId, String reason, Evidence ev) {
        ImportBatch.Pending p = new ImportBatch.Pending();
        p.raw = raw;
        p.kind = kind;
        p.mentionedBy = agentId;
        p.reason = reason;
        p.evidence = ev;
        batch.pending.add(p);
    }

    private void special(String c, String agentId, String raw, String expected, Evidence ev, List<String> cands,
                         RelType type, String target, String reason) {
        batch.specialCases.add(new SpecialCase(c, agentId, raw, expected, ev, cands, type, target, reason));
    }

    /** 한 Agent 표준화. id는 미리 정해 둡니다(파일 순서대로 현재 최댓값 다음 번호). */
    public ImportBatch.Agent normalize(SubmittedAgent s, String aid, boolean isNew) {
        ImportBatch.Agent a = new ImportBatch.Agent();
        a.id = aid;
        a.isNew = isNew;
        a.name = s.nameText().strip();
        a.sourceFile = s.file;
        String aname = a.name;

        // 소속
        String deptRaw = s.dept == null ? "" : s.dept.text().strip();
        if (deptRaw.isEmpty()) {
            String[] hint = inferDepartment(s.submitter);
            if (hint != null) {
                a.department = hint[0];
                flag(a, "department", "", hint[1], hint[0]);
                addRel(RelType.BELONGS_TO, aid, hint[0], RelStatus.CANDIDATE, s.submitter.evidence(), hint[1], null);
            } else {
                flag(a, "department", "", "소속 부서 누락", null);
            }
        } else {
            Resolution r = lex.resolve(Kind.DEPARTMENT, deptRaw);
            if (r.known()) {
                a.department = r.id();
                if (r.reason() != null) flag(a, "department", deptRaw, r.reason(), r.id());
                addRel(RelType.BELONGS_TO, aid, r.id(), r.how() == How.REVIEW ? RelStatus.CANDIDATE : RelStatus.CONFIRMED,
                        s.dept.evidence(), r.reason(), null);
            } else {
                flag(a, "department", deptRaw, "표준 부서 목록에 없음 — 확인 필요", null);
            }
        }

        // 업무 성격
        String wtRaw = s.type == null ? "" : s.type.text().strip();
        Resolution wt = lex.resolve(Kind.WORK_TYPE, wtRaw);
        if (wt.known()) {
            a.workType = wt.id();
            addRel(RelType.HAS_WORK_TYPE, aid, wt.id(), wt.how() == How.REVIEW ? RelStatus.CANDIDATE : RelStatus.CONFIRMED,
                    s.type.evidence(), wt.reason(), null);
        } else {
            flag(a, "work_type", wtRaw, wtRaw.isEmpty() ? "업무 유형 누락" : "표준 업무 유형에 없음 — 확인 필요", null);
        }

        // 사용 플랫폼
        for (Raw p : s.platforms) {
            Resolution r = lex.resolve(Kind.PLATFORM, p.text().strip());
            if (r.known()) {
                if (r.reason() != null) flag(a, "platforms", p.text(), r.reason(), r.id());
                addRel(RelType.USES_PLATFORM, aid, r.id(), r.how() == How.REVIEW ? RelStatus.CANDIDATE : RelStatus.CONFIRMED,
                        p.evidence(), r.reason(), null);
            } else {
                String reason = r.reason() != null ? r.reason() : "표준 플랫폼 목록에 없음 — 신규 등록할지 확인";
                flag(a, "platforms", p.text(), reason, null);
                pending(p.text().strip(), "Platform", aid, reason, p.evidence());
            }
        }

        // 데이터사용
        for (Raw d : s.data) {
            Resolution r = lex.resolve(Kind.DATA, d.text().strip());
            if (r.known()) {
                if (r.reason() != null) flag(a, "data", d.text(), r.reason(), r.id());
                addRel(RelType.ACCESSES_DATA, aid, r.id(), r.how() == How.REVIEW ? RelStatus.CANDIDATE : RelStatus.CONFIRMED,
                        d.evidence(), r.reason(), null);
            } else {
                String reason = r.reason() != null ? r.reason() : "표준 데이터 목록에 없음 — 신규 데이터로 등록할지 확인";
                flag(a, "data", d.text(), reason, null);
                pending(d.text().strip(), KIND_DATA, aid, reason, d.evidence());
            }
        }

        // 결과사용
        for (Raw c : s.calls) {
            String raw = c.text().strip();
            String unreg = lex.resolve(Kind.AGENT, raw).how() == How.UNREGISTERED ? lex.resolve(Kind.AGENT, raw).reason() : null;
            if (unreg != null) {
                flag(a, "calls", raw, unreg, null);
                pending(raw, KIND_AGENT, aid, unreg, c.evidence());
                special("미등록 대상", aid, raw, "미등록 대상 검토 항목으로 보냄", c.evidence(), null, null, null, null);
                continue;
            }
            String tid = lex.agentIdByName(raw);
            if (tid == null) {
                handlePartial(a, aid, raw, c);
                continue;
            }
            if (tid.equals(aid)) {
                flag(a, "calls", raw, SELF_REFERENCE, null);
                special("자기 참조", aid, raw, "검증 단계에서 거부", c.evidence(), null, null, null, null);
                continue;
            }
            Evidence e = c.evidence();
            String canonical = lex.name(tid);
            if (!canonical.equals(raw)) e = e.withMatch("공백 차이로 '" + canonical + "'와 매칭");
            addRel(RelType.USES_RESULT_OF, aid, tid, RelStatus.CONFIRMED, e, null, null);
        }

        // 제공하는 쪽이 주어인 문장 → 방향을 뒤집어 저장
        for (Raw p : s.providedTo) {
            String src = lex.agentIdByName(p.text());
            if (src == null || src.equals(aid)) continue;
            String srcName = lex.name(src);
            Evidence e = p.evidence().withDirectionNote(
                    "제공하는 쪽(" + aname + ")이 주어인 문장 — 관계 방향은 " + srcName + " → " + aname);
            ImportBatch.Rel r = addRel(RelType.USES_RESULT_OF, src, aid, RelStatus.CONFIRMED, e, null, null);
            special("중복 진술·방향", aid, p.text(),
                    srcName + " → " + aname + " 관계 하나로 합치고 근거 " + r.evidence.size() + "건을 모두 남김", e,
                    null, null, null, null);
        }

        // 부정문·미래 계획 → 관계 없음
        for (SubmittedAgent.Negated n : s.negated) {
            Kind kind = n.type() == RelType.ACCESSES_DATA ? Kind.DATA : n.type() == RelType.USES_PLATFORM ? Kind.PLATFORM : Kind.AGENT;
            Resolution r = lex.resolve(kind, n.label());
            special("부정문", aid, null, "관계를 만들지 않음", n.evidence(), null, n.type(), r.id(),
                    "부정문 — '" + n.expression() + "'라서 관계를 만들면 안 됨");
        }
        for (SubmittedAgent.Future f : s.futures) {
            special("미래 계획", aid, f.raw(), "관계를 만들지 않음", f.evidence(), null, null, null, "현재 사용 중인 관계가 아님");
        }
        if (s.note != null && !Texts.isBlank(s.note.value())) {
            String note = s.note.value().strip();
            if (Cues.has(Cues.FUTURE, note)) {
                special("미래 계획", aid, note, "관계를 만들지 않음", s.note.evidence(), null, null, null, "현재 사용 중인 관계가 아님");
            }
        }

        // 담당자·운영 시작일·사용량·상태
        String ownerRaw = s.owner == null ? null : s.owner.value();
        Normalizers.Parsed<List<String>> owners = Normalizers.parseOwner(ownerRaw);
        a.owners = new ArrayList<>(owners.value());
        if (owners.reason() != null) flag(a, "owners", ownerRaw, owners.reason(), null);
        String dateRaw = s.date == null ? null : s.date.value();
        Normalizers.Parsed<String> date = Normalizers.parseDate(dateRaw);
        a.startDate = date.value();
        if (date.reason() != null) flag(a, "start_date", dateRaw, date.reason(), date.value());
        String usageRaw = s.usage == null ? null : s.usage.value();
        Normalizers.Parsed<Integer> usage = Normalizers.parseUsage(usageRaw);
        a.monthlyCalls = usage.value();
        if (usage.reason() != null) flag(a, "monthly_calls", usageRaw, usage.reason(), usage.value());
        String statusRaw = s.status == null ? "" : s.status.text().strip();
        Resolution st = lex.resolve(Kind.STATUS, statusRaw);
        if (st.known()) {
            a.status = st.id();
        } else {
            a.status = statusRaw.isEmpty() ? null : statusRaw;
            flag(a, "status", statusRaw, "표준 상태(운영·파일럿·중단)에 없음 — 확인 필요", null);
        }
        a.description = s.desc == null ? null : s.desc.value();

        Map<String, Object> raw = a.raw;
        raw.put("dept", s.dept == null ? null : s.dept.value());
        raw.put("owner", ownerRaw);
        raw.put("type", s.type == null ? null : s.type.value());
        raw.put("date", dateRaw);
        raw.put("usage", usageRaw);
        raw.put("status", s.status == null ? null : s.status.value());
        raw.put("platforms", s.platforms.stream().map(Raw::value).toList());
        raw.put("data", s.data.stream().map(Raw::value).toList());
        raw.put("calls", s.calls.stream().map(Raw::value).toList());
        return a;
    }

    /** 'X Agent'처럼 이름 일부만 맞는 표기: 후보가 여럿이면 후보 제시(CANDIDATE), 하나면 추정, 없으면 미등록 대상 */
    private void handlePartial(ImportBatch.Agent a, String aid, String raw, Raw c) {
        String word = raw.replaceAll("\\s*Agent$", "").strip();
        List<String> cands = word.isEmpty() ? List.of() : lex.agentsContaining(word, aid);
        String sentence = Texts.nz(c.evidence().text());
        if (cands.size() >= 2) {
            String best = cands.get(0);
            String clue = null;
            int bestScore = 0;
            for (String id : cands) {
                for (String tok : lex.name(id).split("\\s+")) {
                    if (tok.equals("Agent") || tok.contains(word) || tok.length() < 2) continue;
                    if (sentence.replace(raw, "").contains(tok)) {
                        int score = tok.length();
                        if (score > bestScore) {
                            bestScore = score;
                            best = id;
                            clue = tok;
                        }
                    }
                }
            }
            String bestName = lex.name(best);
            String reason = "'" + raw + "'" + Texts.josa(raw, "과", "와") + " 부분 일치하는 Agent가 여러 개 — 후보를 보여주고 사용자 선택 필요"
                    + (clue != null ? " (문맥상 '" + clue + "'" + Texts.josa(clue, "이라", "라") + " " + bestName
                    + Texts.josa(bestName, "이", "가") + " 유력)" : " (문맥 단서 없음 — 첫 후보로 임시 지정)");
            flag(a, "calls", raw, reason, best);
            addRel(RelType.USES_RESULT_OF, aid, best, RelStatus.CANDIDATE, c.evidence(), reason, cands);
            special("모호한 이름", aid, raw, "후보를 보여주고 사용자가 " + best + "(" + bestName + ")를 고름", c.evidence(),
                    cands, null, null, null);
        } else if (cands.size() == 1) {
            String id = cands.get(0);
            String reason = "이름 일부만 일치 — '" + lex.name(id) + "'로 추정, 확인 필요";
            flag(a, "calls", raw, reason, id);
            addRel(RelType.USES_RESULT_OF, aid, id, RelStatus.CANDIDATE, c.evidence(), reason, null);
        } else {
            String reason = "등록되지 않은 Agent·외부 서비스 — 등록할지 확인";
            flag(a, "calls", raw, reason, null);
            pending(raw, KIND_AGENT, aid, reason, c.evidence());
            special("미등록 대상", aid, raw, "미등록 대상 검토 항목으로 보냄", c.evidence(), null, null, null, null);
        }
    }

    /** 소속이 비어 있으면 파일 머리의 제출 부서에서 표준 부서가 하나만 보일 때 추정 → [id, 사유] */
    private String[] inferDepartment(Raw submitter) {
        if (submitter == null || Texts.isBlank(submitter.value())) return null;
        List<TextScanner.Mention> ms = TextScanner.scan(submitter.value(),
                lex.terms(EnumSet.of(Kind.DEPARTMENT), false, false, false));
        List<String> ids = ms.stream().map(TextScanner.Mention::id).distinct().toList();
        if (ids.size() != 1) return null;
        String name = lex.name(ids.get(0));
        return new String[]{ids.get(0), "소속 부서 누락 — 제출 부서(" + submitter.value().strip() + ")와 업무로 보아 "
                + name + Texts.josa(name, "으로", "로") + " 추정"};
    }
}
