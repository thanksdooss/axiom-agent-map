// 브라우저 엔진을 data/answer_key 의 정답과 맞춰 봅니다.
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import golden from '../generated/query_golden_set.json';
import dashboardExpected from '../generated/dashboard_expected.json';
import type { Scope, TraversalResponse } from '../api/types';
import { ask } from './ask';
import { buildDashboard, duplicateCandidates } from './dashboard';
import { extractFromText } from './extract';
import { buildPreview, commitBatch, readFiles } from './importers';
import { answerKey, initialState, nameOf } from './store';

type Gold = { id: string; question: string; intent: string; params: Record<string, any>; expected: any };
const queries = (golden as { queries: Gold[] }).queries;
const SCOPES: [string, Scope][] = [['all_statuses', 'all'], ['confirmed_only', 'confirmed']];

const summarize = (t: TraversalResponse) => ({
  direct: t.direct.map((x) => x.name),
  indirect: t.indirect.map((x) => ({
    name: x.name, depth: x.depth, path: x.pathNames.join(t.direction === 'forward' ? ' → ' : ' ← '),
  })),
  count: t.count,
});

function run(q: Gold, scope: Scope) {
  const state = initialState('full');
  return ask(state, q.question, scope);
}

describe('자연어 질의 25개 (규칙 엔진)', () => {
  for (const q of queries) {
    it(`${q.id} ${q.question}`, () => {
      const r = run(q, 'all');
      expect(r.interpretation.intent).toBe(q.intent);
      const anchor = q.params.anchor?.id ?? q.params.source?.id ?? q.params.relation?.source;
      if (anchor) expect(r.interpretation.anchor?.id).toBe(anchor);
      const target = q.params.target?.id ?? q.params.relation?.target;
      if (target) expect(r.interpretation.target?.id).toBe(target);
      if (q.params.direction) expect(r.interpretation.direction).toBe(q.params.direction);
      if (q.params.depth !== undefined) expect(r.interpretation.depth).toBe(q.params.depth === 'all' ? null : q.params.depth);

      const both = q.expected && typeof q.expected === 'object' && 'all_statuses' in q.expected;
      for (const [gk, scope] of both ? SCOPES : [['', 'all' as Scope] as [string, Scope]]) {
        const res = run(q, scope);
        const exp = both ? q.expected[gk] : q.expected;
        const out: any = res.result;
        switch (q.id) {
          case 'Q01':
            expect(out.totalAgents).toBe(q.expected.total_agents);
            expect(out.byDepartment).toEqual(q.expected.by_department[scope === 'all' ? 'all_statuses' : 'confirmed_only']);
            break;
          case 'Q02': case 'Q03': case 'Q04': case 'Q06': case 'Q15': case 'Q16': case 'Q19':
            expect(summarize(out)).toEqual(exp);
            break;
          case 'Q09':
            expect(summarize(out)).toEqual(exp.result);
            expect(res.answer).toContain('발견되지 않았습니다');
            break;
          case 'Q05':
            expect(out.path).toEqual(exp.path);
            expect(out.statuses).toEqual(exp.statuses);
            break;
          case 'Q07':
            expect(out.relationId).toBe(exp.relation_id);
            expect(out.status).toBe(exp.status);
            expect(out.evidence.map((e: any) => e.text)).toEqual(exp.evidence.map((e: any) => e.text));
            break;
          case 'Q08':
            expect(out.map((x: any) => [x.relationId, x.status])).toEqual(exp.map((x: any) => [x.relation_id, x.status]));
            break;
          case 'Q10':
            expect(out).toEqual(exp);
            break;
          case 'Q11': case 'Q12': case 'Q14': case 'Q18': case 'Q20':
            expect(out).toEqual(exp);
            break;
          case 'Q13': {
            const got = out.map((p: any) => [...p.pair].sort().join('|'));
            for (const m of exp.must_include_pairs) expect(got).toContain([...m.pair].sort().join('|'));
            break;
          }
          case 'Q17':
            expect(out.path).toEqual(exp.path);
            expect(out.hops).toBe(exp.hops);
            break;
          case 'Q21':
            expect(out.count).toBe(exp.count);
            expect(out.relations.map((e: any) => e.id)).toEqual(exp.relations.map((e: any) => e.id));
            break;
          case 'Q22':
            expect(out.relationId).toBe(exp.relation_id);
            expect(out.reviewedBy).toBe(exp.reviewed_by);
            break;
          case 'Q23':
            expect(res.answer).toContain('등록된 대상이 아닙니다');
            break;
          case 'Q24':
            expect(res.clarification?.options.map((o) => o.name).sort()).toEqual([...exp.candidates].sort());
            break;
          case 'Q25':
            expect(out.map((p: any) => p.rawLabel)).toEqual(exp.map((p: any) => p.raw));
            break;
          default:
            throw new Error(`검사 규칙 없음 ${q.id}`);
        }
      }
    });
  }
});

describe('대시보드', () => {
  it('정답 지표와 같다', () => {
    const state = initialState('full');
    const e = dashboardExpected as any;
    for (const [gk, scope] of SCOPES) {
      const d = buildDashboard(state, scope);
      expect(d.totalAgents).toBe(e.total_agents);
      expect(d.agentsByStatus).toEqual(e.agents_by_status);
      expect(d.dataSensitivity).toEqual(e.data_assets_by_sensitivity);
      expect(d.relationshipsByStatus).toEqual(e.relationships_by_status);
      expect(d.pendingEntities).toBe(e.pending_entities);
      expect(Object.fromEntries(d.agentsByDepartment.map((x) => [x.name, x.count]))).toEqual(e.by_scope[gk].agents_by_department);
      expect(d.highSensitivity.count).toBe(e.by_scope[gk].agents_accessing_high_sensitivity);
      expect(d.highSensitivity.share).toBe(e.by_scope[gk].share_accessing_high_sensitivity);
    }
  });
  it('중복 후보 상위 6쌍에 의도한 3쌍이 들어 있다', () => {
    const state = initialState('full');
    const top = duplicateCandidates(state).map((p) => p.pair.map((id) => nameOf(state, id)).sort().join('|'));
    console.log(top);
  });
});

describe('자유 입력 관계 추출 8건', () => {
  for (const c of answerKey.free_text_cases) {
    it(`${c.id} ${c.input}`, () => {
      const state = initialState('full');
      const r = extractFromText(state, c.input, c.context_agent ?? undefined);
      expect(r.action).toBe(c.expected_action);
      for (const e of c.expected) {
        expect(r.items.some((i) => i.type === e.type && i.sourceId === e.source && i.targetId === e.target)).toBe(true);
      }
    });
  }
});

describe('일괄 등록 재현', () => {
  it('샘플 모드 + 원본 5개 파일 → 정답 DB 상태', async () => {
    const state = initialState('sample');
    const dir = join(__dirname, '..', '..', '..', 'data', 'raw');
    const files = readdirSync(dir).sort().map((name) => {
      const b = readFileSync(join(dir, name));
      return { name, buf: b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength) as ArrayBuffer };
    });
    const { raws, meta } = await readFiles(state, files);
    expect(meta.map((m) => m.agents)).toEqual([10, 10, 10, 10, 10]);
    const preview = buildPreview(state, raws, meta);
    commitBatch(state, preview.batchId);

    const key = (a: any) => [a.id, a.name, a.department, a.work_type, a.owners.join(','), a.start_date, a.monthly_calls, a.status].join(' / ');
    expect(state.agents.slice(3).map(key)).toEqual(answerKey.agents.map(key));
    for (const a of answerKey.agents) {
      const got = state.agents.find((x) => x.id === a.id)!;
      expect(got.review.map((f) => f.field).sort(), a.name).toEqual(a.review.map((f) => f.field).sort());
    }
    const rel = (e: any) => `${e.type} ${e.source}→${e.target} ${e.status}`;
    expect(state.edges.map(rel).sort()).toEqual(answerKey.relationships.map(rel).sort());
    expect(state.pending.map((p) => p.rawLabel).sort()).toEqual(answerKey.pending_entities.map((p) => p.raw).sort());
    expect(preview.specialCases.map((c) => c.case).sort()).toEqual(answerKey.special_cases_in_files.map((c) => c.case).sort());
  });
});
