// 경영진 대시보드 지표와 인사이트.
import type { Dashboard, Insight, Scope, Sensitivity } from '../api/types';
import { Graph } from './graph';
import type { State } from './store';
import { nameOf } from './store';
import { SENSITIVITY, vocabulary } from './vocab';

const byCountThenName = <T extends { name: string }>(key: (x: T) => number) => (a: T, b: T) =>
  key(b) - key(a) || (a.name < b.name ? -1 : a.name > b.name ? 1 : 0);

export function highSensitivityAgents(g: Graph): string[] {
  return g.state.agents
    .map((a) => a.id)
    .filter((id) => g.targets(id, 'ACCESSES_DATA').some((d) => SENSITIVITY.get(d) === '상'))
    .sort();
}

export function departmentCounts(g: Graph, ids?: string[]): { id: string | null; name: string; count: number }[] {
  const counts = new Map<string, { id: string | null; name: string; count: number }>();
  for (const id of ids ?? g.state.agents.map((a) => a.id)) {
    const dep = g.targets(id, 'BELONGS_TO')[0] ?? null;
    const name = dep ? nameOf(g.state, dep) : '(미확정)';
    const cur = counts.get(name) ?? { id: dep, name, count: 0 };
    cur.count += 1;
    counts.set(name, cur);
  }
  return [...counts.values()].sort(byCountThenName((x) => x.count));
}

export function platformConcentration(g: Graph) {
  const counts = new Map<string, number>();
  for (const e of g.edges) if (e.type === 'USES_PLATFORM') counts.set(e.target, (counts.get(e.target) ?? 0) + 1);
  return [...counts.entries()]
    .map(([id, count]) => ({ id, name: nameOf(g.state, id), count }))
    .sort(byCountThenName((x) => x.count));
}

export function topDepended(g: Graph, top = 6) {
  return g.state.agents
    .map((a) => ({ id: a.id, name: a.name, dependents: g.impact(a.id, null).count }))
    .filter((x) => x.dependents > 0)
    .sort(byCountThenName((x) => x.dependents))
    .slice(0, top);
}

export function multiPlatformHighSensitivity(g: Graph): string[] {
  return highSensitivityAgents(g).filter((id) => g.targets(id, 'USES_PLATFORM').length >= 3);
}

const bigrams = (s: string) => {
  const t = s.replace(/Agent/g, '').replace(/[\s·,.()]/g, '');
  const out = new Set<string>();
  for (let i = 0; i < t.length - 1; i++) out.add(t.slice(i, i + 2));
  return out;
};

/** 이름·설명의 글자 2-gram 유사도(Jaccard)로 역할이 비슷한 Agent 쌍을 찾습니다. 임베딩 유사도의 가벼운 대용입니다. */
export function duplicateCandidates(state: State, top = 6): { pair: [string, string]; score: number }[] {
  const grams = state.agents.map((a) => ({ id: a.id, name: bigrams(a.name), desc: bigrams(a.description) }));
  const jac = (x: Set<string>, y: Set<string>) => {
    let inter = 0;
    x.forEach((v) => y.has(v) && inter++);
    return inter / (x.size + y.size - inter || 1);
  };
  const pairs: { pair: [string, string]; score: number }[] = [];
  for (let i = 0; i < grams.length; i++) {
    for (let j = i + 1; j < grams.length; j++) {
      const score = 0.4 * jac(grams[i].name, grams[j].name) + 0.6 * jac(grams[i].desc, grams[j].desc);
      pairs.push({ pair: [grams[i].id, grams[j].id], score: Math.round(score * 1000) / 1000 });
    }
  }
  return pairs.sort((a, b) => b.score - a.score).slice(0, top);
}

export function buildDashboard(state: State, scope: Scope): Dashboard {
  const g = new Graph(state, scope);
  const total = state.agents.length;
  const hi = highSensitivityAgents(g);
  const plat = platformConcentration(g);
  const dataSensitivity = { 상: 0, 중: 0, 하: 0 } as Record<Sensitivity, number>;
  vocabulary.data_assets.forEach((d) => (dataSensitivity[d.sensitivity as Sensitivity] += 1));
  const relationshipsByStatus: Record<string, number> = {};
  state.edges.forEach((e) => (relationshipsByStatus[e.status] = (relationshipsByStatus[e.status] ?? 0) + 1));
  const agentsByStatus: Record<string, number> = {};
  state.agents.forEach((a) => (agentsByStatus[a.status] = (agentsByStatus[a.status] ?? 0) + 1));

  const insights: Insight[] = [];
  const multi = multiPlatformHighSensitivity(g);
  if (multi.length) {
    insights.push({
      kind: 'HIGH_SENS_MULTI_PLATFORM',
      title: `고민감도 데이터 + 플랫폼 3개 이상: ${multi.length}개`,
      description: `${multi.map((id) => nameOf(state, id)).join(', ')} — 연동 범위가 넓어 권한 관리가 복잡합니다.`,
      nodeIds: multi,
    });
  }
  if (plat.length) {
    const max = plat[0].count;
    const tops = plat.filter((p) => p.count === max);
    insights.push({
      kind: 'PLATFORM_CONCENTRATION',
      title: `${tops.map((p) => p.name).join('·')}에 Agent ${max}개씩 연동`,
      description: '한 플랫폼에 장애가 나면 여러 Agent가 함께 멈출 수 있습니다(단일 장애점).',
      nodeIds: tops.map((p) => p.id),
    });
  }
  const dups = duplicateCandidates(state, 4);
  if (dups.length) {
    insights.push({
      kind: 'DUPLICATE_CANDIDATE',
      title: `역할이 비슷한 Agent 후보 ${dups.length}쌍`,
      description: dups.map((d) => `${nameOf(state, d.pair[0])} ↔ ${nameOf(state, d.pair[1])}`).join(' / '),
      nodeIds: [...new Set(dups.flatMap((d) => d.pair))],
    });
  }
  const backlog = state.edges.filter((e) => e.status === 'CANDIDATE');
  if (backlog.length) {
    insights.push({
      kind: 'REVIEW_BACKLOG',
      title: `검토 대기 관계 ${backlog.length}건`,
      description: '표기가 모호해 추정한 관계입니다. 검토 화면에서 확정하거나 반려하세요.',
      nodeIds: [...new Set(backlog.flatMap((e) => [e.source, e.target]))],
    });
  }
  const cycles = g.cycles();
  if (cycles.length) {
    insights.push({
      kind: 'CYCLE',
      title: `순환 의존 ${cycles.length}개`,
      description: cycles.map((c) => c.map((id) => nameOf(state, id)).join(' → ')).join(' / '),
      nodeIds: cycles.flat(),
    });
  }

  return {
    scope,
    totalAgents: total,
    agentsByStatus,
    agentsByDepartment: departmentCounts(g),
    highSensitivity: {
      count: hi.length,
      share: total ? Math.round((hi.length / total) * 1000) / 1000 : 0,
      agents: hi.map((id) => g.ref(id)),
    },
    platformConcentration: plat,
    dataSensitivity,
    topDepended: topDepended(g),
    relationshipsByStatus,
    pendingEntities: state.pending.filter((p) => p.state === 'OPEN').length,
    insights,
  };
}
