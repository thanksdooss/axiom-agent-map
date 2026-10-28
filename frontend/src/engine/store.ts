// 브라우저 엔진의 상태 저장소. 정답 DB 상태(answer key)를 읽어 메모리에 두고, 검토·등록 결과는 localStorage에 남깁니다.
import keyJson from '../generated/extraction_answer_key.json';
import type { Evidence, GraphEdge, PendingEntity, RelStatus, RelType, ReviewFlag } from '../api/types';
import { NAMES, REL_LABEL } from './vocab';

export interface AgentRec {
  id: string;
  name: string;
  department: string | null;
  owners: string[];
  work_type: string | null;
  description: string;
  start_date: string | null;
  monthly_calls: number | null;
  status: string;
  source_file: string;
  review: ReviewFlag[];
}

interface KeyRel {
  id: string;
  type: RelType;
  source: string;
  target: string;
  status: RelStatus;
  evidence: Evidence[];
  review_reason: string | null;
  reviewed_by: string | null;
  reviewed_at: string | null;
  candidates_shown?: string[];
}

interface KeyPending {
  kind: string;
  raw: string;
  mentioned_by: string;
  reason: string;
  evidence: Evidence;
}

export interface AnswerKey {
  agents: (AgentRec & { raw: Record<string, unknown> })[];
  sample_agents: AgentRec[];
  relationships: KeyRel[];
  pending_entities: KeyPending[];
  special_cases_in_files: { case: string; agent: string; raw?: string; expected: string; evidence: Evidence }[];
  free_text_cases: {
    id: string;
    input: string;
    context_agent: string | null;
    expected_action: string;
    expected: { type: RelType; source: string; target: string }[];
  }[];
}

export const answerKey = keyJson as unknown as AnswerKey;

export interface State {
  mode: 'full' | 'sample';
  agents: AgentRec[];
  edges: GraphEdge[];
  pending: PendingEntity[];
  seq: number;
}

const STORAGE_KEY = 'axiom-agent-map-state-v1';

export function nameOf(state: State, id: string): string {
  return state.agents.find((a) => a.id === id)?.name ?? NAMES.get(id) ?? id;
}

function toEdge(r: KeyRel, names: (id: string) => string): GraphEdge {
  return {
    id: r.id,
    type: r.type,
    typeLabel: REL_LABEL[r.type],
    source: r.source,
    target: r.target,
    sourceName: names(r.source),
    targetName: names(r.target),
    status: r.status,
    reviewReason: r.review_reason,
    evidence: r.evidence.map((e) => ({ ...e, directionNote: e.direction_note ?? e.directionNote })),
    reviewedBy: r.reviewed_by,
    reviewedAt: r.reviewed_at,
    candidatesShown: r.candidates_shown,
  };
}

export function initialState(mode: 'full' | 'sample'): State {
  const sampleIds = new Set(answerKey.sample_agents.map((a) => a.id));
  const agents = mode === 'full' ? [...answerKey.sample_agents, ...answerKey.agents] : [...answerKey.sample_agents];
  const names = (id: string) => agents.find((a) => a.id === id)?.name ?? NAMES.get(id) ?? id;
  const rels = answerKey.relationships.filter(
    (r) => mode === 'full' || r.type === 'STORED_IN' || sampleIds.has(r.source),
  );
  const pending: PendingEntity[] =
    mode === 'full'
      ? answerKey.pending_entities.map((p, i) => ({
          id: `PE${i + 1}`,
          rawLabel: p.raw,
          kind: p.kind,
          mentionedBy: p.mentioned_by,
          mentionedByName: names(p.mentioned_by),
          reason: p.reason,
          evidence: p.evidence,
          state: 'OPEN',
        }))
      : [];
  return {
    mode,
    agents: agents.map((a) => {
      const copy = { ...a } as AgentRec & { raw?: unknown };
      delete copy.raw;
      return copy;
    }),
    edges: rels.map((r) => toEdge(r, names)),
    pending,
    seq: answerKey.relationships.length,
  };
}

export function loadState(): State {
  try {
    const saved = globalThis.localStorage?.getItem(STORAGE_KEY);
    if (saved) return JSON.parse(saved) as State;
  } catch {
    /* 저장소를 못 쓰면 기본 상태로 */
  }
  return initialState('full');
}

export function saveState(state: State): void {
  try {
    globalThis.localStorage?.setItem(STORAGE_KEY, JSON.stringify(state));
  } catch {
    /* 사생활 보호 모드 등에서는 저장하지 않음 */
  }
}

export function clearSaved(): void {
  try {
    globalThis.localStorage?.removeItem(STORAGE_KEY);
  } catch {
    /* 무시 */
  }
}

export function nextRelId(state: State): string {
  state.seq += 1;
  return `R${String(state.seq).padStart(4, '0')}`;
}

export const today = () => new Date().toISOString().slice(0, 10);
