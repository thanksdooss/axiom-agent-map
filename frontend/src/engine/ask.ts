// 자연어 질의: ② 질문 해석(규칙 기반) → 그래프 탐색 → ③ 결과에만 근거한 답변.
// LLM을 붙이면 interpret() 자리만 바뀌고, 실행·답변 규칙은 그대로입니다.
import type { AskResponse, GraphEdge, Intent, NodeRef, Scope, TraversalResponse } from '../api/types';
import {
  departmentCounts, duplicateCandidates, highSensitivityAgents, multiPlatformHighSensitivity, platformConcentration, topDepended,
} from './dashboard';
import { Graph, NOT_FOUND_MESSAGE } from './graph';
import { findMentions, type Mention } from './mentions';
import type { State } from './store';
import { nameOf } from './store';
import { nodeType } from './vocab';

export interface Interpretation {
  intent: Intent;
  anchor: NodeRef | null;
  anchorRaw: string | null;
  target: NodeRef | null;
  direction: 'forward' | 'reverse' | null;
  depth: number | null;
  scope: Scope;
  options?: NodeRef[];
}

export interface AskContext {
  lastAnchorId?: string | null;
}

const has = (q: string, re: RegExp) => re.test(q);

export function interpret(state: State, question: string, scope: Scope, ctx: AskContext = {}): Interpretation {
  const q = question.trim();
  const sc: Scope = has(q, /확정(된)?\s*관계만|확정 기준/) ? 'confirmed' : scope;
  const ms = findMentions(state, q, { kinds: ['agent', 'partialAgent', 'data', 'platform'] });
  const refOf = (m: Mention): NodeRef => ({ id: m.id!, name: nameOf(state, m.id!), type: nodeType(m.id!) });
  const agents = ms.filter((m) => m.kind === 'agent' && m.id);
  const nodes = ms.filter((m) => (m.kind === 'agent' || m.kind === 'data' || m.kind === 'platform') && m.id);
  const partial = ms.find((m) => m.kind === 'partialAgent');
  const base = { anchor: null, anchorRaw: null, target: null, direction: null, depth: null, scope: sc } as Interpretation;
  const sens = has(q, /민감도|개인정보|고민감/);

  if (has(q, /순환/)) return { ...base, intent: 'cycle' };
  if (has(q, /미등록|등록되지 않은|등록 안 된/)) return { ...base, intent: 'pending' };
  if (has(q, /검토되지 않은|검토 대기|아직 확정/)) return { ...base, intent: 'status' };
  if (agents[0] && has(q, /검토된|실제 관계|확정된 관계인가|확정됐/)) {
    return { ...base, intent: 'status', anchor: refOf(agents[0]), anchorRaw: agents[0].raw };
  }
  if (nodes.length >= 2 && has(q, /근거|누가 확인|검토자|확인했/)) {
    return { ...base, intent: 'evidence', anchor: refOf(nodes[0]), anchorRaw: nodes[0].raw, target: refOf(nodes[1]) };
  }
  if (agents.length >= 2 && has(q, /경로|어떻게 연결|연결돼 있/)) {
    return { ...base, intent: 'path', anchor: refOf(agents[0]), anchorRaw: agents[0].raw, target: refOf(agents[1]), direction: 'forward' };
  }
  if (agents[0] && has(q, /왜 점검|왜 영향|왜 포함/)) {
    const target = refOf(agents[0]);
    let anchorId = ctx.lastAnchorId ?? null;
    if (!anchorId) {
      const deps = new Graph(state, sc).dependencies(target.id, null).indirect
        .concat(new Graph(state, sc).dependencies(target.id, null).direct)
        .filter((x) => x.type === 'Agent')
        .sort((a, b) => b.depth - a.depth);
      anchorId = deps[0]?.id ?? null;
    }
    return {
      ...base, intent: 'path_explain', anchor: anchorId ? { id: anchorId, name: nameOf(state, anchorId), type: nodeType(anchorId) } : null,
      target, direction: 'reverse',
    };
  }
  if (sens && has(q, /3개 이상|여러 플랫폼|플랫폼을 많이/)) return { ...base, intent: 'insight' };
  if (sens && has(q, /부서/)) return { ...base, intent: 'aggregate' };
  if (sens && has(q, /몇 개|몇개|개수|수는|얼마나/)) return { ...base, intent: 'aggregate' };
  if (has(q, /부서별|어떤 Agent가 등록|등록돼 있/)) return { ...base, intent: 'aggregate' };
  if (has(q, /중복|비슷한 역할|비슷한 Agent/)) return { ...base, intent: 'similarity' };
  if (has(q, /플랫폼/) && has(q, /몰린|많이|집중|가장/)) return { ...base, intent: 'ranking' };
  if (has(q, /핵심 Agent|공통으로 의존|많이 의존|의존이 몰린/)) return { ...base, intent: 'ranking' };

  const unknown = q.match(/^(.+?)(?:를|을)\s*(?:쓰는|사용하는|이용하는)\s*Agent/);
  if (unknown && !nodes.length) {
    return { ...base, intent: 'unknown_entity', anchorRaw: unknown[1].trim() };
  }
  const anchor = nodes[0];
  if (!anchor && partial) {
    return {
      ...base, intent: 'ambiguous_anchor', anchorRaw: partial.raw,
      options: (partial.options ?? []).map((id) => ({ id, name: nameOf(state, id), type: 'Agent' as const })),
    };
  }
  if (anchor && has(q, /바꾸면|바꿔도|변경하면|교체하면|수정하면|점검|영향|멈추면|장애가 나면/)) {
    return { ...base, intent: 'impact', anchor: refOf(anchor), anchorRaw: anchor.raw, direction: 'reverse', depth: has(q, /직접/) ? 1 : null };
  }
  if (anchor && anchor.kind === 'agent' && has(q, /사용하나|사용해|쓰나|쓰는|의존|연결된 대상|간접|무엇을/)) {
    const depth = has(q, /간접/) ? 2 : has(q, /직접/) ? 1 : null;
    return { ...base, intent: 'dependencies', anchor: refOf(anchor), anchorRaw: anchor.raw, direction: 'forward', depth };
  }
  if (anchor) {
    return { ...base, intent: 'dependencies', anchor: refOf(anchor), anchorRaw: anchor.raw, direction: 'forward', depth: 1 };
  }
  return { ...base, intent: 'unsupported' };
}

const list = (names: string[]) => (names.length ? names.join(', ') : '없음');
const scopeLabel = (s: Scope) => (s === 'confirmed' ? '확정된 관계 기준' : '모든 관계(샘플·후보 포함) 기준');

function traversalAnswer(t: TraversalResponse, verb: string): string {
  if (!t.count) return `${t.anchor.name}: ${NOT_FOUND_MESSAGE}`;
  const parts = [`${t.anchor.name}${verb}: 직접 ${t.direct.length}개(${list(t.direct.map((x) => x.name))})`];
  if (t.indirect.length) {
    parts.push(`간접 ${t.indirect.length}개 — ${t.indirect.map((x) => `${x.name}(${x.pathNames.join(t.direction === 'forward' ? ' → ' : ' ← ')})`).join(', ')}`);
  }
  const unconfirmed = [...t.direct, ...t.indirect].filter((x) => x.status !== 'CONFIRMED');
  if (unconfirmed.length) parts.push(`이 중 ${unconfirmed.length}개는 확정되지 않은 관계(${[...new Set(unconfirmed.map((x) => x.status))].join('·')})를 거칩니다`);
  return parts.join('. ') + '.';
}

export function execute(state: State, it: Interpretation): Pick<AskResponse, 'answer' | 'result' | 'highlight' | 'clarification'> {
  const g = new Graph(state, it.scope);
  const none = { nodeIds: [] as string[], edgeIds: [] as string[] };
  const edgesOf = (ids: string[]) => g.edges.filter((e) => ids.includes(e.source) && ids.includes(e.target)).map((e) => e.id);
  switch (it.intent) {
    case 'dependencies':
    case 'impact': {
      const t = it.intent === 'impact' ? g.impact(it.anchor!.id, it.depth) : g.dependencies(it.anchor!.id, it.depth);
      const answer = traversalAnswer(t, it.intent === 'impact' ? '을(를) 바꾸면 점검할 대상' : '이(가) 쓰는 대상') + ` (${scopeLabel(it.scope)})`;
      return { answer, result: t, highlight: { nodeIds: [t.anchor.id, ...t.direct.map((x) => x.id), ...t.indirect.map((x) => x.id)], edgeIds: t.edgeIds }, clarification: null };
    }
    case 'path_explain': {
      if (!it.anchor) return { answer: `${it.target!.name}을(를) 점검 대상으로 만든 경로를 찾지 못했습니다. 어떤 Agent를 바꾸는 상황인지 알려 주세요.`, result: null, highlight: none, clarification: null };
      const p = g.path(it.target!.id, it.anchor.id);
      if (!p.path) return { answer: `${it.anchor.name}에서 ${it.target!.name}까지 이어지는 결과사용 경로가 없습니다.`, result: p, highlight: none, clarification: null };
      const names = [...p.pathNames].reverse();
      const statuses = p.edgeIds.map((id) => g.edges.find((e) => e.id === id)!.status).reverse();
      const answer = `${names.join(' ← ')} 순서로 결과를 이어 받아 쓰기 때문에, ${it.anchor.name}이(가) 바뀌면 ${it.target!.name}도 간접 점검 대상이 됩니다. 경로의 관계 상태: ${statuses.join(', ')}.`;
      return { answer, result: { path: names, statuses }, highlight: { nodeIds: p.path, edgeIds: p.edgeIds }, clarification: null };
    }
    case 'path': {
      const p = g.path(it.anchor!.id, it.target!.id);
      const answer = p.path
        ? `${p.pathNames.join(' → ')} (${p.path.length - 1}단계)로 연결돼 있습니다.`
        : `${it.anchor!.name}에서 ${it.target!.name}까지 결과사용으로 이어진 경로가 없습니다.`;
      return { answer, result: { path: p.pathNames, hops: p.path ? p.path.length - 1 : null }, highlight: { nodeIds: p.path ?? [], edgeIds: p.edgeIds }, clarification: null };
    }
    case 'evidence': {
      const all = new Graph(state, 'all');
      const e = all.edges.find((x) => x.source === it.anchor!.id && x.target === it.target!.id)
        ?? all.edges.find((x) => x.source === it.target!.id && x.target === it.anchor!.id);
      if (!e) return { answer: `${it.anchor!.name}와(과) ${it.target!.name} 사이에 등록된 관계가 없습니다.`, result: null, highlight: none, clarification: null };
      const ev = e.evidence.map((x) => `「${x.text ?? ''}」 (${x.file} · ${x.locator})`).join(' / ');
      const who = e.reviewedBy ? `${e.reviewedBy}가 ${e.reviewedAt}에 확인했습니다` : '아직 사람이 확인하지 않았습니다';
      const answer = `${e.sourceName} → ${e.targetName} (${e.typeLabel}, ${e.status}). 근거: ${ev}. ${who}.`;
      return { answer, result: { relationId: e.id, status: e.status, evidence: e.evidence, reviewedBy: e.reviewedBy, reviewedAt: e.reviewedAt }, highlight: { nodeIds: [e.source, e.target], edgeIds: [e.id] }, clarification: null };
    }
    case 'status': {
      if (it.anchor) {
        const rels = new Graph(state, 'all').edges.filter((e) => e.source === it.anchor!.id && e.type === 'ACCESSES_DATA');
        const answer = rels.map((e) => `${e.targetName}: ${e.status === 'CONFIRMED' ? '검토 완료' : e.status === 'CANDIDATE' ? `검토 대기 — ${e.reviewReason}` : '샘플(검증 전)'}`).join(' / ');
        return { answer: `${it.anchor.name}의 데이터 관계 — ${answer || '없음'}`, result: rels.map((e) => ({ relationId: e.id, target: e.targetName, status: e.status, reviewReason: e.reviewReason })), highlight: { nodeIds: [it.anchor.id, ...rels.map((e) => e.target)], edgeIds: rels.map((e) => e.id) }, clarification: null };
      }
      const cand = state.edges.filter((e) => e.status === 'CANDIDATE');
      return { answer: `검토 대기(CANDIDATE) 관계는 ${cand.length}건입니다: ${cand.map((e) => `${e.sourceName} → ${e.targetName}`).join(', ')}.`, result: { count: cand.length, relations: cand }, highlight: { nodeIds: [...new Set(cand.flatMap((e) => [e.source, e.target]))], edgeIds: cand.map((e) => e.id) }, clarification: null };
    }
    case 'aggregate': {
      const q = it.anchorRaw ?? '';
      void q;
      return { answer: '', result: null, highlight: none, clarification: null };
    }
    case 'ranking':
    case 'insight':
    case 'similarity':
      return { answer: '', result: null, highlight: none, clarification: null };
    case 'cycle': {
      const cycles = g.cycles();
      const answer = cycles.length
        ? `순환 의존 ${cycles.length}개: ${cycles.map((c) => [...c, c[0]].map((id) => nameOf(state, id)).join(' → ')).join(' / ')}. 탐색은 이미 방문한 Agent를 다시 넣지 않아 무한 반복하지 않습니다.`
        : '결과사용 관계에서 순환은 발견되지 않았습니다.';
      return { answer, result: cycles.map((c) => c.map((id) => nameOf(state, id))), highlight: { nodeIds: cycles.flat(), edgeIds: edgesOf(cycles.flat()) }, clarification: null };
    }
    case 'pending': {
      const open = state.pending.filter((p) => p.state === 'OPEN');
      const answer = open.length ? `아직 등록되지 않은 대상 ${open.length}건: ${open.map((p) => `${p.rawLabel}(${p.mentionedByName}에서 언급)`).join(', ')}.` : '미등록 대상이 없습니다.';
      return { answer, result: open, highlight: { nodeIds: open.map((p) => p.mentionedBy), edgeIds: [] }, clarification: null };
    }
    case 'unknown_entity':
      return { answer: `${it.anchorRaw}은(는) 등록된 대상이 아닙니다. 등록된 목록에서 고르거나 미등록 대상으로 올려 주세요. (0개라는 뜻이 아닙니다)`, result: null, highlight: none, clarification: null };
    case 'ambiguous_anchor':
      return { answer: `'${it.anchorRaw}'에 해당하는 Agent가 여러 개입니다. 어느 Agent인지 골라 주세요.`, result: null, highlight: { nodeIds: (it.options ?? []).map((o) => o.id), edgeIds: [] }, clarification: { message: '어느 Agent를 말씀하시나요?', options: it.options ?? [] } };
    default:
      return { answer: '아직 답할 수 없는 질문입니다. 예: "고객분석 Agent를 바꾸면 어디를 점검해야 하나?", "CRM 고객정보를 쓰는 Agent는?"', result: null, highlight: none, clarification: null };
  }
}

/** 집계·순위·인사이트·유사도 질문 실행 (질문 문장을 함께 봐야 해서 따로 둡니다) */
export function executeAggregate(state: State, it: Interpretation, question: string) {
  const g = new Graph(state, it.scope);
  const none = { nodeIds: [] as string[], edgeIds: [] as string[] };
  const sens = /민감도|개인정보|고민감/.test(question);
  if (it.intent === 'aggregate' && sens && /부서/.test(question)) {
    const rows = departmentCounts(g, highSensitivityAgents(g));
    return { answer: `민감도 '상' 데이터를 쓰는 Agent는 ${rows.map((r) => `${r.name} ${r.count}개`).join(', ')}입니다 (${scopeLabel(it.scope)}).`, result: Object.fromEntries(rows.map((r) => [r.name, r.count])), highlight: { nodeIds: highSensitivityAgents(g), edgeIds: [] }, clarification: null };
  }
  if (it.intent === 'aggregate' && sens) {
    const ids = highSensitivityAgents(g);
    return { answer: `민감도 '상' 데이터에 접근하는 Agent는 ${ids.length}개입니다 (${scopeLabel(it.scope)}).`, result: { count: ids.length, agents: ids.map((id) => nameOf(state, id)) }, highlight: { nodeIds: ids, edgeIds: g.edges.filter((e) => e.type === 'ACCESSES_DATA' && ids.includes(e.source)).map((e) => e.id) }, clarification: null };
  }
  if (it.intent === 'aggregate') {
    const rows = departmentCounts(g);
    return { answer: `등록된 Agent는 ${state.agents.length}개입니다. 부서별: ${rows.map((r) => `${r.name} ${r.count}`).join(', ')} (${scopeLabel(it.scope)}).`, result: { totalAgents: state.agents.length, byDepartment: Object.fromEntries(rows.map((r) => [r.name, r.count])) }, highlight: none, clarification: null };
  }
  if (it.intent === 'ranking' && /플랫폼/.test(question)) {
    const rows = platformConcentration(g).slice(0, 5);
    return { answer: `Agent가 가장 많이 연동된 플랫폼: ${rows.map((r) => `${r.name} ${r.count}개`).join(', ')} (${scopeLabel(it.scope)}).`, result: rows.map((r) => ({ platform: r.name, agents: r.count })), highlight: { nodeIds: rows.map((r) => r.id), edgeIds: [] }, clarification: null };
  }
  if (it.intent === 'ranking') {
    const rows = topDepended(g);
    return { answer: `여러 Agent가 의존하는 핵심 Agent: ${rows.map((r) => `${r.name}(${r.dependents}개가 의존)`).join(', ')} (${scopeLabel(it.scope)}).`, result: rows.map((r) => ({ agent: r.name, dependents: r.dependents })), highlight: { nodeIds: rows.map((r) => r.id), edgeIds: [] }, clarification: null };
  }
  if (it.intent === 'insight') {
    const ids = multiPlatformHighSensitivity(g);
    return { answer: ids.length ? `고민감도 데이터에 접근하면서 플랫폼을 3개 이상 쓰는 Agent: ${ids.map((id) => nameOf(state, id)).join(', ')}.` : '해당하는 Agent가 없습니다.', result: ids.map((id) => nameOf(state, id)), highlight: { nodeIds: ids, edgeIds: g.edges.filter((e) => ids.includes(e.source) && (e.type === 'USES_PLATFORM' || e.type === 'ACCESSES_DATA')).map((e) => e.id) }, clarification: null };
  }
  if (it.intent === 'similarity') {
    const pairs = duplicateCandidates(state);
    return { answer: `역할이 비슷한 Agent 후보: ${pairs.map((p) => `${nameOf(state, p.pair[0])} ↔ ${nameOf(state, p.pair[1])}`).join(' / ')}. 이름·설명 유사도 기준이며, 실제 중복 여부는 사람이 판단해야 합니다.`, result: pairs.map((p) => ({ pair: p.pair.map((id) => nameOf(state, id)), score: p.score })), highlight: { nodeIds: [...new Set(pairs.flatMap((p) => p.pair))], edgeIds: [] }, clarification: null };
  }
  return null;
}

export function ask(state: State, question: string, scope: Scope, ctx: AskContext = {}): AskResponse {
  const it = interpret(state, question, scope, ctx);
  const isAgg = ['aggregate', 'ranking', 'insight', 'similarity'].includes(it.intent);
  const out = (isAgg ? executeAggregate(state, it, question) : null) ?? execute(state, it);
  const { options: _o, ...interpretation } = it;
  void _o;
  return { question, engine: 'rule', interpretation, ...out };
}

export type { GraphEdge };
