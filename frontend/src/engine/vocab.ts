// 표준 어휘(SKOS)와 표기 정규화. data/tools/build_dataset.py 의 정규화 규칙과 같습니다.
import vocabularyJson from '../generated/vocabulary.json';
import type { NodeType, RelType, Sensitivity } from '../api/types';

export type VocabKind = 'department' | 'work_type' | 'platform' | 'data' | 'status';

export interface Concept {
  id: string;
  prefLabel: string;
  altLabel?: string[];
  broader?: string;
  definition?: string;
  sensitivity?: Sensitivity;
  stored_in?: string | null;
}

export interface Vocabulary {
  relation_types: { code: RelType; prefLabel: string; domain: string; range: string; definition: string }[];
  sensitivity: Record<Sensitivity, string>;
  departments: Concept[];
  work_types: Concept[];
  platforms: Concept[];
  data_assets: Concept[];
  agent_status: { prefLabel: string; altLabel: string[] }[];
  needs_review_labels: Record<string, Record<string, { candidate: string; reason: string }>>;
  unregistered_labels: Record<string, Record<string, string>>;
}

export const vocabulary = vocabularyJson as unknown as Vocabulary;

export const REL_LABEL: Record<RelType, string> = Object.fromEntries(
  vocabulary.relation_types.map((r) => [r.code, r.prefLabel]),
) as Record<RelType, string>;

export function nodeType(id: string): NodeType {
  if (id.startsWith('DEP')) return 'Department';
  if (id.startsWith('WT')) return 'WorkType';
  if (id.startsWith('A')) return 'Agent';
  if (id.startsWith('D')) return 'DataAsset';
  return 'Platform';
}

type Match = { id: string | null; how: 'exact' | 'alias' | 'review' | 'unregistered' | 'unknown'; reason: string | null };

const index: Record<Exclude<VocabKind, 'status'>, Map<string, { id: string; how: 'exact' | 'alias' }>> = {
  department: new Map(),
  work_type: new Map(),
  platform: new Map(),
  data: new Map(),
};
const groups: [Exclude<VocabKind, 'status'>, Concept[]][] = [
  ['department', vocabulary.departments],
  ['work_type', vocabulary.work_types],
  ['platform', vocabulary.platforms],
  ['data', vocabulary.data_assets],
];
for (const [kind, items] of groups) {
  for (const c of items) {
    index[kind].set(c.prefLabel, { id: c.id, how: 'exact' });
    for (const a of c.altLabel ?? []) index[kind].set(a, { id: c.id, how: 'alias' });
  }
}
const statusIndex = new Map<string, string>();
for (const s of vocabulary.agent_status) {
  statusIndex.set(s.prefLabel, s.prefLabel);
  for (const a of s.altLabel) statusIndex.set(a, s.prefLabel);
}

/** 원문 표기 → 표준 ID. 별칭 사전에 없고 검토 목록에도 없으면 'unknown'(미등록으로 취급). */
export function resolve(kind: Exclude<VocabKind, 'status'>, raw: string): Match {
  const s = (raw ?? '').trim();
  const hit = index[kind].get(s);
  if (hit) return { id: hit.id, how: hit.how, reason: null };
  const review = vocabulary.needs_review_labels[kind]?.[s];
  if (review) return { id: review.candidate, how: 'review', reason: review.reason };
  const unreg = vocabulary.unregistered_labels[kind]?.[s];
  if (unreg) return { id: null, how: 'unregistered', reason: unreg };
  return { id: null, how: 'unknown', reason: '표준 목록에 없는 표기 — 신규 등록 여부 확인' };
}

export function resolveStatus(raw: string | null | undefined): string {
  const s = (raw ?? '').trim();
  return statusIndex.get(s) ?? (s || '운영');
}

/** 문장 속에서 찾을 수 있는 모든 표기(어휘 + 검토 목록 + 미등록 목록). 긴 표기부터. */
export function labelsFor(kind: Exclude<VocabKind, 'status'>): string[] {
  const set = new Set<string>(index[kind].keys());
  Object.keys(vocabulary.needs_review_labels[kind] ?? {}).forEach((k) => set.add(k));
  Object.keys(vocabulary.unregistered_labels[kind] ?? {}).forEach((k) => set.add(k));
  return [...set].sort((a, b) => b.length - a.length);
}

export const NAMES: Map<string, string> = new Map();
for (const [, items] of groups) for (const c of items) NAMES.set(c.id, c.prefLabel);

export const SENSITIVITY: Map<string, Sensitivity> = new Map(
  vocabulary.data_assets.map((d) => [d.id, d.sensitivity as Sensitivity]),
);

const MONTHS: Record<string, number> = { Jan: 1, Feb: 2, Mar: 3, Apr: 4, May: 5, Jun: 6, Jul: 7, Aug: 8, Sep: 9, Oct: 10, Nov: 11, Dec: 12 };
const pad = (n: number | string) => String(n).padStart(2, '0');

/** 원문 날짜 → [YYYY-MM 또는 YYYY-MM-DD, 검토 사유] */
export function parseDate(raw: unknown): [string | null, string | null] {
  const s = String(raw ?? '').trim().replace(/^'|'$/g, '');
  if (!s) return [null, '운영 시작일 누락'];
  const relative: Record<string, [string, string]> = {
    '작년 12월': ['2025-12', '상대 날짜 — 제출일(2026년 10월) 기준 2025년 12월로 추정'],
    '작년 말': ['2025-12', '상대 날짜 — 제출일 기준 2025년 말로 추정'],
    '4월 초': ['2026-04', '연도 없음 — 제출 연도(2026)로 추정, 일자 불명확'],
  };
  if (relative[s]) return relative[s];
  if (/^\d{4}\/Q[1-4]$/.test(s)) return [null, '분기 표기 — 일자 확인 필요'];
  if (/상반기|하반기/.test(s)) return [null, '반기 표기 — 일자 확인 필요'];
  let m = s.match(/^(\d{4})년 (\d{1,2})월 (초|중순|말)$/);
  if (m) return [`${m[1]}-${pad(m[2])}`, '일자 불명확 — 월까지만 확정'];
  m = s.match(/^(\d{4})[-./](\d{1,2})[-./](\d{1,2})$/);
  if (m) return [`${m[1]}-${pad(m[2])}-${pad(m[3])}`, null];
  m = s.match(/^(\d{2})\/(\d{1,2})\/(\d{1,2})$/);
  if (m) return [`20${m[1]}-${pad(m[2])}-${pad(m[3])}`, null];
  m = s.match(/^(\d{4})[-./](\d{1,2})$/);
  if (m) return [`${m[1]}-${pad(m[2])}`, null];
  m = s.match(/^(\d{4})년 (\d{1,2})월$/);
  if (m) return [`${m[1]}-${pad(m[2])}`, null];
  m = s.match(/^([A-Z][a-z]{2}) (\d{4})$/);
  if (m && MONTHS[m[1]]) return [`${m[2]}-${pad(MONTHS[m[1]])}`, null];
  return [null, `해석할 수 없는 날짜 표기 '${s}'`];
}

/** 원문 월 사용량 → [정수, 검토 사유] */
export function parseUsage(raw: unknown): [number | null, string | null] {
  const s = String(raw ?? '').trim().replace(/^'|'$/g, '');
  if (!s) return [null, '월 사용량 누락'];
  if (/^\d+\s*~\s*\d+$/.test(s)) return [null, '범위값 — 단일 값 확인 필요'];
  if (s === '매일' || s === '하루 한 번') return [30, '빈도 표현 — 월 약 30회로 추정'];
  if (s === '많음') return [null, '숫자가 아닌 값'];
  const t = s.replace(/^약\s*/, '').replace(/건?\s*(정도|내외)$/, '').trim();
  const m = t.match(/^([\d.,]+)\s*(천|만|k)?$/);
  if (!m) return [null, `숫자가 아닌 값 '${s}'`];
  let n = Number(m[1].replace(/,/g, ''));
  n *= m[2] === '천' ? 1000 : m[2] === '만' ? 10000 : m[2] === 'k' ? 1000 : 1;
  return [Math.round(n), null];
}

/** 담당자 원문 → [이름 목록, 검토 사유]. 직함(책임·매니저·선임·변호사·님)은 뗍니다. */
export function parseOwner(raw: unknown): [string[], string | null] {
  const s = String(raw ?? '').trim();
  if (!s) return [[], '담당자 누락'];
  if (/담당자$/.test(s)) return [[], '특정 담당자 이름 없음'];
  return [s.split(/\s*[/,]\s*/).map((p) => p.replace(/\s*(책임|매니저|선임|변호사|님)$/, '').trim()), null];
}

/** 공백을 지운 비교용 키 */
export const squash = (s: string) => s.replace(/\s+/g, '');
