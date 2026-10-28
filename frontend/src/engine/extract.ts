// ① 관계 추출(규칙 기반). 문장에서 주어 Agent와 대상(Agent·데이터·플랫폼·부서)을 찾고,
// 표준 어휘로 맞춘 뒤 기존 관계와 대조합니다. AI(LLM)는 '원문 표기'만 뽑고 ID·상태는 이 규칙이 정합니다.
import type { ExtractionItem, ExtractionResult, NodeRef, RelType } from '../api/types';
import { findMentions, type Mention } from './mentions';
import type { State } from './store';
import { nameOf } from './store';
import { nodeType } from './vocab';

const REL_FOR: Partial<Record<Mention['kind'], RelType>> = {
  agent: 'USES_RESULT_OF',
  partialAgent: 'USES_RESULT_OF',
  data: 'ACCESSES_DATA',
  platform: 'USES_PLATFORM',
  department: 'BELONGS_TO',
  work_type: 'HAS_WORK_TYPE',
  unregistered: 'USES_RESULT_OF',
};

const NEGATION = /(사용하지|쓰지|이용하지|참고하지)\s*않|않는다|않습니다/;
const FUTURE = /예정|계획이다|계획입니다|검토 중/;

function findExisting(state: State, type: RelType, source: string | null, target: string | null): string | null {
  if (!source || !target) return null;
  return state.edges.find((e) => e.type === type && e.source === source && e.target === target && e.status !== 'REJECTED')?.id ?? null;
}

export function extractFromText(state: State, text: string, contextAgentId?: string): ExtractionResult {
  const ms = findMentions(state, text);
  // 주어: 문장 앞쪽 "… Agent는/가/은/이"
  const subj = /^(.*?Agent)\s*(는|은|가|이)\s/.exec(text);
  let subject: Mention | undefined;
  if (subj) subject = ms.find((m) => m.kind === 'agent' && m.end <= subj[1].length + 1 && m.start < subj[1].length);
  const subjectId = subject?.id ?? contextAgentId ?? null;
  const subjectRef: NodeRef | null = subjectId ? { id: subjectId, name: nameOf(state, subjectId), type: 'Agent' } : null;
  const negated = NEGATION.test(text);
  const future = FUTURE.test(text);

  const items: ExtractionItem[] = [];
  for (const m of ms) {
    if (m === subject) continue;
    const type = REL_FOR[m.kind];
    if (!type) continue;
    // 주어 앞 수식어로 나온 부서("HR팀의 … Agent")는 소속 관계, 그 밖의 부서 언급은 무시
    if (m.kind === 'department' && !(subject && m.end <= subject.start)) continue;
    if (m.kind === 'work_type') continue;
    const base = {
      type, sourceId: subjectId, targetId: m.id, targetRaw: m.raw, existingRelId: null as string | null,
      options: [] as NodeRef[], evidenceText: text, reason: m.reason,
    };
    if (!subjectId) {
      items.push({ ...base, outcome: 'NO_SUBJECT', reason: '주어(어떤 Agent가 쓰는지)가 없음 — 기준 Agent를 골라야 함' });
    } else if (negated) {
      items.push({ ...base, outcome: 'NEGATED', reason: '부정문 — 관계를 만들지 않음' });
    } else if (future) {
      items.push({ ...base, outcome: 'FUTURE', reason: '현재가 아닌 계획 — 관계를 만들지 않음' });
    } else if (m.kind === 'partialAgent') {
      items.push({
        ...base, outcome: 'AMBIGUOUS',
        options: (m.options ?? []).filter((id) => id !== subjectId).map((id) => ({ id, name: nameOf(state, id), type: 'Agent' as const })),
      });
    } else if (m.kind === 'unregistered' || m.how === 'unknown' || m.how === 'unregistered') {
      items.push({ ...base, targetId: null, outcome: 'UNREGISTERED' });
    } else if (m.id === subjectId) {
      items.push({ ...base, outcome: 'SELF_REFERENCE', reason: '자기 참조 — 출발과 도착이 같아 저장하지 않음' });
    } else {
      const existing = findExisting(state, type, subjectId, m.id);
      items.push({ ...base, outcome: existing ? 'DUPLICATE' : 'NEW', existingRelId: existing });
    }
  }
  // 등록된 대상과 하나도 매칭되지 않은 목적어("외부 시장 분석 서비스를 사용해")는 미등록 대상으로
  if (subjectId && !negated && !future && !items.some((i) => i.outcome !== 'NO_SUBJECT')) {
    const obj = /([가-힣A-Za-z0-9 ]+?)(?:을|를)\s*(?:사용|쓴|씁|호출|참고|이용)/.exec(text.slice(subject ? subject.end : 0));
    if (obj) {
      const raw = obj[1].replace(/^\s*(는|은|가|이)\s*/, '').trim();
      const prior = state.pending.find((p) => p.evidence.text?.includes(raw));
      items.push({
        type: 'USES_RESULT_OF', sourceId: subjectId, targetId: null, targetRaw: raw, outcome: 'UNREGISTERED',
        existingRelId: null, options: [], evidenceText: text,
        reason: prior ? `등록된 대상과 매칭되지 않음 — 이미 올라온 미등록 항목 '${prior.rawLabel}'와 같은 대상일 수 있음` : '등록된 대상과 매칭되지 않음',
      });
    }
  }
  if (!subjectId && !items.length) {
    items.push({
      type: 'USES_RESULT_OF', sourceId: null, targetId: null, targetRaw: text, outcome: 'NO_SUBJECT', existingRelId: null,
      options: [], evidenceText: text, reason: '주어(어떤 Agent가 쓰는지)가 없음',
    });
  }
  return { engine: 'rule', subject: { raw: subject?.raw ?? null, resolved: subjectRef }, action: summarize(items), items };
}

export function summarize(items: ExtractionItem[]): string {
  const has = (o: ExtractionItem['outcome']) => items.some((i) => i.outcome === o);
  if (has('NO_SUBJECT')) return '기준 Agent 선택 요청';
  if (has('NEGATED')) return '관계 없음 (부정문)';
  if (has('FUTURE')) return '관계 없음 (미래 계획)';
  if (has('SELF_REFERENCE')) return '저장 거부 (자기 참조)';
  if (has('AMBIGUOUS')) return '후보 제시 후 선택 요청';
  const fresh = items.filter((i) => i.outcome === 'NEW').length;
  if (fresh) return `새 관계 후보 ${fresh}건`;
  if (has('UNREGISTERED')) return '미등록 대상 검토 항목으로 보냄';
  if (has('DUPLICATE')) return '중복 표시';
  return '찾은 관계 없음';
}

export function nodeRef(state: State, id: string): NodeRef {
  return { id, name: nameOf(state, id), type: nodeType(id) };
}
