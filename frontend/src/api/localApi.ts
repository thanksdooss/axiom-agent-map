// 서버 없이 브라우저에서 도는 API 구현 (GitHub Pages 시연용). 백엔드와 같은 계약을 따릅니다.
import type {
  AgentDetail, AgentSummary, Api, AskResponse, CommitResult, Dashboard, ExtractionItem, ExtractionResult, GraphEdge,
  GraphResponse, Health, ImportPreview, NeighborhoodResponse, PathResponse, PendingEntity, RelStatus, RelType, Scope,
  TraversalResponse,
} from './types';
import { ask } from '../engine/ask';
import { buildDashboard } from '../engine/dashboard';
import { extractFromText } from '../engine/extract';
import { Graph } from '../engine/graph';
import { buildPreview, commitBatch, readFiles } from '../engine/importers';
import { clearSaved, initialState, loadState, nameOf, nextRelId, saveState, type State, today } from '../engine/store';
import { NAMES, REL_LABEL, SENSITIVITY } from '../engine/vocab';

const SAMPLE_FILES = ['01_영업_마케팅.json', '02_개발_IT운영.yaml', '03_고객지원_구매.csv', '04_인사_재무.xlsx', '05_법무_경영기획.docx'];

export class LocalApi implements Api {
  state: State;
  private lastAnchorId: string | null = null;

  constructor(state?: State) {
    this.state = state ?? loadState();
  }

  private save() {
    saveState(this.state);
  }

  private g(scope: Scope) {
    return new Graph(this.state, scope);
  }

  async health(): Promise<Health> {
    return { status: 'ok', llm: { enabled: false, provider: null, model: null }, mode: 'browser' };
  }

  async graph(scope: Scope): Promise<GraphResponse> {
    return this.g(scope).full();
  }

  async neighborhood(id: string, depth: number, scope: Scope): Promise<NeighborhoodResponse> {
    return this.g(scope).neighborhood(id, depth);
  }

  async path(from: string, to: string, scope: Scope): Promise<PathResponse> {
    return this.g(scope).path(from, to);
  }

  private summary(id: string, g: Graph): AgentSummary {
    const a = this.state.agents.find((x) => x.id === id)!;
    const dep = g.targets(id, 'BELONGS_TO')[0] ?? a.department;
    const wt = g.targets(id, 'HAS_WORK_TYPE')[0] ?? a.work_type;
    const dataIds = g.targets(id, 'ACCESSES_DATA');
    return {
      id, name: a.name, departmentId: dep, departmentName: dep ? NAMES.get(dep) ?? null : null,
      workTypeId: wt, workTypeName: wt ? NAMES.get(wt) ?? null : null, status: a.status, owners: a.owners,
      platformIds: g.targets(id, 'USES_PLATFORM'), dataIds,
      highSensitivity: dataIds.some((d) => SENSITIVITY.get(d) === '상'), reviewFlagCount: a.review.length,
    };
  }

  async agents(scope: Scope, q?: string): Promise<AgentSummary[]> {
    const g = this.g(scope);
    return this.state.agents
      .filter((a) => !q || a.name.replace(/\s/g, '').includes(q.replace(/\s/g, '')))
      .map((a) => this.summary(a.id, g));
  }

  async agent(id: string, scope: Scope): Promise<AgentDetail> {
    const g = this.g(scope);
    const a = this.state.agents.find((x) => x.id === id);
    if (!a) throw new Error(`Agent ${id}를 찾을 수 없습니다`);
    return {
      ...this.summary(id, g), description: a.description, startDate: a.start_date, monthlyCalls: a.monthly_calls,
      sourceFile: a.source_file, reviewFlags: a.review,
      outgoing: g.edges.filter((e) => e.source === id), incoming: g.edges.filter((e) => e.target === id),
    };
  }

  async dependencies(id: string, depth: number | null, scope: Scope): Promise<TraversalResponse> {
    return this.g(scope).dependencies(id, depth);
  }

  async impact(id: string, depth: number | null, scope: Scope): Promise<TraversalResponse> {
    this.lastAnchorId = id;
    return this.g(scope).impact(id, depth);
  }

  async relationships(filter: { status?: RelStatus; nodeId?: string }): Promise<GraphEdge[]> {
    return this.state.edges.filter(
      (e) => (!filter.status || e.status === filter.status) && (!filter.nodeId || e.source === filter.nodeId || e.target === filter.nodeId),
    );
  }

  async review(id: string, action: 'confirm' | 'reject', targetId?: string): Promise<GraphEdge> {
    const e = this.state.edges.find((x) => x.id === id);
    if (!e) throw new Error(`관계 ${id}를 찾을 수 없습니다`);
    if (targetId && targetId !== e.target) {
      e.target = targetId;
      e.targetName = nameOf(this.state, targetId);
    }
    e.status = action === 'confirm' ? 'CONFIRMED' : 'REJECTED';
    e.reviewedBy = '검토자';
    e.reviewedAt = today();
    this.save();
    return e;
  }

  async pendingEntities(): Promise<PendingEntity[]> {
    return this.state.pending;
  }

  async resolvePending(id: string, action: 'register' | 'map' | 'dismiss', mapToId?: string): Promise<PendingEntity> {
    const p = this.state.pending.find((x) => x.id === id);
    if (!p) throw new Error(`미등록 항목 ${id}를 찾을 수 없습니다`);
    if (action === 'map' && mapToId) {
      const type: RelType = mapToId.startsWith('A') ? 'USES_RESULT_OF' : mapToId.startsWith('P') ? 'USES_PLATFORM' : 'ACCESSES_DATA';
      this.state.edges.push({
        id: nextRelId(this.state), type, typeLabel: REL_LABEL[type], source: p.mentionedBy, target: mapToId,
        sourceName: nameOf(this.state, p.mentionedBy), targetName: nameOf(this.state, mapToId), status: 'CONFIRMED',
        reviewReason: null, evidence: [{ ...p.evidence, match: `미등록 표기 '${p.rawLabel}'를 사람이 매핑` }],
        reviewedBy: '검토자', reviewedAt: today(),
      });
      p.state = 'MAPPED';
    } else {
      p.state = action === 'register' ? 'REGISTERED' : 'DISMISSED';
    }
    this.save();
    return p;
  }

  async dashboard(scope: Scope): Promise<Dashboard> {
    return buildDashboard(this.state, scope);
  }

  async importSamples(): Promise<ImportPreview> {
    const base = import.meta.env.BASE_URL ?? '/';
    const files = await Promise.all(
      SAMPLE_FILES.map(async (name) => ({ name, buf: await (await fetch(`${base}samples/${encodeURIComponent(name)}`)).arrayBuffer() })),
    );
    return this.preview(files);
  }

  async importFiles(files: File[]): Promise<ImportPreview> {
    return this.preview(await Promise.all(files.map(async (f) => ({ name: f.name, buf: await f.arrayBuffer() }))));
  }

  async preview(files: { name: string; buf: ArrayBuffer }[]): Promise<ImportPreview> {
    const { raws, meta } = await readFiles(this.state, files);
    return buildPreview(this.state, raws, meta);
  }

  async commitImport(batchId: string): Promise<CommitResult> {
    const r = commitBatch(this.state, batchId);
    this.save();
    return r;
  }

  async extract(text: string, contextAgentId?: string): Promise<ExtractionResult> {
    return extractFromText(this.state, text, contextAgentId);
  }

  async applyExtraction(items: ExtractionItem[]): Promise<GraphEdge[]> {
    const out: GraphEdge[] = [];
    for (const it of items) {
      if (!it.sourceId || !it.targetId) continue;
      const existing = this.state.edges.find((e) => e.type === it.type && e.source === it.sourceId && e.target === it.targetId && e.status !== 'REJECTED');
      const ev = { file: '직접 입력', locator: today(), text: it.evidenceText };
      if (existing) {
        existing.evidence.push(ev);
        out.push(existing);
        continue;
      }
      if (it.outcome !== 'NEW' && it.outcome !== 'AMBIGUOUS') continue;
      const e: GraphEdge = {
        id: nextRelId(this.state), type: it.type, typeLabel: REL_LABEL[it.type], source: it.sourceId, target: it.targetId,
        sourceName: nameOf(this.state, it.sourceId), targetName: nameOf(this.state, it.targetId), status: 'CANDIDATE',
        reviewReason: it.reason ?? 'AI 관계 추출 결과 — 사람 검토 필요', evidence: [ev], reviewedBy: null, reviewedAt: null,
      };
      this.state.edges.push(e);
      out.push(e);
    }
    this.save();
    return out;
  }

  async ask(question: string, scope: Scope): Promise<AskResponse> {
    const res = ask(this.state, question, scope, { lastAnchorId: this.lastAnchorId });
    if (res.interpretation.intent === 'impact' && res.interpretation.anchor) this.lastAnchorId = res.interpretation.anchor.id;
    return res;
  }

  async reset(mode: 'full' | 'sample'): Promise<void> {
    clearSaved();
    this.state = initialState(mode);
    this.lastAnchorId = null;
    this.save();
  }
}
